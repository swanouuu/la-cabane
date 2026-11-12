package fr.cabane.pouvoir;

import fr.cabane.Cabane;
import fr.cabane.client.pouvoir.ClientContre;
import io.netty.buffer.ByteBuf;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import org.joml.Vector3f;

/**
 * Le contre de Minato (touche L, Minato éveillé, niveau 1 ou 2), d'après l'aperçu validé (docs/minato2/apercu-contre-kurama.mp4) :
 * il se met en garde 20 secondes. Le premier vrai joueur qui le frappe au corps à corps est arrêté net, à main nue : le
 * coup ne passe pas. L'attaquant voit alors ses yeux rouges (« Calme-toi. »), puis Kurama, énorme, accroupi, la tête
 * basse, la gueule ouverte (« Kurama... ») ; les autres voient Kurama derrière Minato, tout s'assombrit. Au bout de 8
 * secondes, l'attaquant est repoussé. Une minute de recharge à partir de la garde. Tout se dessine dans {@link ClientContre}.
 */
@EventBusSubscriber(modid = Cabane.MODID)
public final class Contre {
    /** La garde (20 s), la recharge (1 min, depuis la garde), la scène (8 s, puis l'attaquant est repoussé). */
    public static final int GARDE = 20 * 20, RECHARGE = 60 * 20, DUREE = 8 * 20;
    /** Quand Kurama gronde (2,4 s : il apparaît). */
    private static final int GRONDE = 48;

    /** Les Minato en garde : jusqu'à quand. */
    private static final Map<UUID, Integer> GARDES = new HashMap<>();
    /** Quand chacun peut se remettre en garde. */
    private static final Map<UUID, Integer> PRET = new HashMap<>();

    /** Un contre en cours : l'attaquant, le tick de départ, où était Minato, et la direction Minato → attaquant. */
    private record Scene(int attaquant, int debut, Vec3 pos, Vec3 dir) {
    }

    private static final Map<UUID, Scene> SCENES = new HashMap<>();

    // ================================================================ réseau

    /** Client → serveur : la touche L, Minato éveillé. */
    public record Activer() implements CustomPacketPayload {
        public static final Type<Activer> TYPE = new Type<>(Cabane.id("minato_contre"));
        public static final StreamCodec<ByteBuf, Activer> CODEC = StreamCodec.unit(new Activer());

        @Override
        public Type<Activer> type() {
            return TYPE;
        }
    }

    /** Serveur → ceux qui sont autour : ce Minato est en garde (ou ne l'est plus) ; recharge : dans combien de ticks il pourra recommencer. */
    public record Garde(UUID joueur, boolean active, int recharge) implements CustomPacketPayload {
        public static final Type<Garde> TYPE = new Type<>(Cabane.id("minato_garde"));
        public static final StreamCodec<ByteBuf, Garde> CODEC = StreamCodec.composite(
                UUIDUtil.STREAM_CODEC, Garde::joueur, ByteBufCodecs.BOOL, Garde::active, ByteBufCodecs.VAR_INT, Garde::recharge, Garde::new);

        @Override
        public Type<Garde> type() {
            return TYPE;
        }
    }

    /** Serveur → ceux qui sont autour : le contre part (Minato, l'attaquant, où était Minato, la direction Minato → attaquant). */
    public record Vision(UUID joueur, int attaquant, Vector3f pos, Vector3f dir) implements CustomPacketPayload {
        public static final Type<Vision> TYPE = new Type<>(Cabane.id("minato_vision"));
        public static final StreamCodec<ByteBuf, Vision> CODEC = StreamCodec.composite(
                UUIDUtil.STREAM_CODEC, Vision::joueur, ByteBufCodecs.VAR_INT, Vision::attaquant, ByteBufCodecs.VECTOR3F, Vision::pos,
                ByteBufCodecs.VECTOR3F, Vision::dir, Vision::new);

        @Override
        public Type<Vision> type() {
            return TYPE;
        }
    }

    @SubscribeEvent
    public static void reseau(RegisterPayloadHandlersEvent event) {
        event.registrar("1")
                .playToServer(Activer.TYPE, Activer.CODEC, (p, ctx) -> ctx.enqueueWork(() -> {
                    if (ctx.player() instanceof ServerPlayer joueur) {
                        activer(joueur);
                    }
                }))
                .playToClient(Garde.TYPE, Garde.CODEC, (p, ctx) -> ctx.enqueueWork(() -> ClientContre.garde(p)))
                .playToClient(Vision.TYPE, Vision.CODEC, (p, ctx) -> ctx.enqueueWork(() -> ClientContre.vision(p)));
    }

    /** À tous ceux qui sont à moins de 128 blocs de ce point. */
    private static void envoyer(ServerLevel monde, Vec3 autour, CustomPacketPayload paquet) {
        for (ServerPlayer p : monde.players()) {
            if (p.distanceToSqr(autour) < 128 * 128) {
                PacketDistributor.sendToPlayer(p, paquet);
            }
        }
    }

    // ================================================================ la garde

    private static void activer(ServerPlayer joueur) {
        UUID id = joueur.getUUID();
        if (Minato.niveau(id) < 1 || SCENES.containsKey(id)) {
            return;
        }
        int maintenant = joueur.server.getTickCount();
        if (GARDES.containsKey(id)) {
            joueur.displayClientMessage(Component.literal("Tu es déjà en garde.").withStyle(ChatFormatting.GOLD), true);
            return;
        }
        int reste = PRET.getOrDefault(id, 0) - maintenant;
        if (reste > 0) {
            joueur.displayClientMessage(Component.literal("Contre : encore " + (reste + 19) / 20 + " s").withStyle(ChatFormatting.RED), true);
            return;
        }
        GARDES.put(id, maintenant + GARDE);
        PRET.put(id, maintenant + RECHARGE);
        envoyer(joueur.serverLevel(), joueur.position(), new Garde(id, true, RECHARGE));
        joueur.displayClientMessage(Component.literal("En garde : 20 s. Le premier qui te frappe va le regretter.").withStyle(ChatFormatting.RED), true);
        ServerLevel monde = joueur.serverLevel();
        monde.playSound(null, joueur.blockPosition(), SoundEvents.WARDEN_HEARTBEAT, SoundSource.PLAYERS, 1.6F, 0.7F);
        monde.sendParticles(rouge(), joueur.getX(), joueur.getY(0.1), joueur.getZ(), 40, 1.2, 0.05, 1.2, 0.02);
    }

    private static void finirGarde(ServerPlayer joueur) {
        if (GARDES.remove(joueur.getUUID()) != null) {
            envoyer(joueur.serverLevel(), joueur.position(), new Garde(joueur.getUUID(), false,
                    Math.max(0, PRET.getOrDefault(joueur.getUUID(), 0) - joueur.server.getTickCount())));
        }
    }

    private static DustParticleOptions rouge() {
        return new DustParticleOptions(new Vector3f(1F, 0.15F, 0.08F), 1.4F);
    }

    /**
     * En garde, un vrai joueur le frappe au corps à corps : le coup s'arrête dans sa main, et la scène part. Le premier
     * seulement (la garde retombe).
     */
    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void frappe(LivingIncomingDamageEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer minato) || !GARDES.containsKey(minato.getUUID())) {
            return;
        }
        if (!event.getSource().is(DamageTypes.PLAYER_ATTACK) || !(event.getSource().getEntity() instanceof ServerPlayer attaquant)
                || attaquant == minato || attaquant instanceof FakePlayer || event.getSource().getDirectEntity() != attaquant) {
            return;
        }
        event.setCanceled(true);
        finirGarde(minato);
        Vec3 d = new Vec3(attaquant.getX() - minato.getX(), 0, attaquant.getZ() - minato.getZ());
        d = d.lengthSqr() < 1.0E-4 ? Vec3.directionFromRotation(0, minato.getYRot()) : d.normalize();
        int maintenant = minato.server.getTickCount();
        SCENES.put(minato.getUUID(), new Scene(attaquant.getId(), maintenant, minato.position(), d));
        envoyer(minato.serverLevel(), minato.position(), new Vision(minato.getUUID(), attaquant.getId(),
                new Vector3f((float) minato.getX(), (float) minato.getY(), (float) minato.getZ()), new Vector3f((float) d.x, 0, (float) d.z)));
        ServerLevel monde = minato.serverLevel();
        Vec3 main = minato.position().add(d.scale(0.6)).add(0, 1.4, 0);
        monde.playSound(null, minato.blockPosition(), SoundEvents.SHIELD_BLOCK, SoundSource.PLAYERS, 1.4F, 0.6F);
        monde.playSound(null, minato.blockPosition(), SoundEvents.ANVIL_LAND, SoundSource.PLAYERS, 0.5F, 1.6F);
        monde.sendParticles(rouge(), main.x, main.y, main.z, 50, 0.25, 0.25, 0.25, 0.08);
        attaquant.setDeltaMovement(Vec3.ZERO);
        attaquant.hurtMarked = true;
    }

    @SubscribeEvent
    public static void tick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        int maintenant = server.getTickCount();
        // la garde retombe au bout de 20 s, ou quand il s'endort
        for (Map.Entry<UUID, Integer> e : new ArrayList<>(GARDES.entrySet())) {
            ServerPlayer joueur = server.getPlayerList().getPlayer(e.getKey());
            if (joueur == null) {
                GARDES.remove(e.getKey());
            } else if (maintenant >= e.getValue() || Minato.niveau(e.getKey()) < 1) {
                finirGarde(joueur);
                joueur.displayClientMessage(Component.literal("Ta garde retombe.").withStyle(ChatFormatting.GRAY), true);
            }
        }
        for (Map.Entry<UUID, Scene> e : new ArrayList<>(SCENES.entrySet())) {
            Scene s = e.getValue();
            ServerPlayer minato = server.getPlayerList().getPlayer(e.getKey());
            ServerLevel monde = minato != null ? minato.serverLevel() : server.overworld();
            int age = maintenant - s.debut();
            if (!(monde.getEntity(s.attaquant()) instanceof ServerPlayer attaquant) || !attaquant.isAlive()) {
                SCENES.remove(e.getKey());
                continue;
            }
            if (age == GRONDE) {
                monde.playSound(null, attaquant.blockPosition(), SoundEvents.ENDER_DRAGON_GROWL, SoundSource.HOSTILE, 2.5F, 0.55F);
                monde.playSound(null, attaquant.blockPosition(), SoundEvents.RAVAGER_ROAR, SoundSource.HOSTILE, 2F, 0.5F);
            }
            if (age < DUREE) {
                // il reste là, figé par la vision
                attaquant.setDeltaMovement(0, Math.min(0, attaquant.getDeltaMovement().y), 0);
                attaquant.hurtMarked = true;
                continue;
            }
            // la vision finie : il est repoussé
            SCENES.remove(e.getKey());
            attaquant.setDeltaMovement(s.dir().scale(2.2).add(0, 0.55, 0));
            attaquant.hurtMarked = true;
            attaquant.fallDistance = 0;
            monde.playSound(null, attaquant.blockPosition(), SoundEvents.PLAYER_ATTACK_KNOCKBACK, SoundSource.PLAYERS, 1.4F, 0.7F);
            monde.sendParticles(new DustParticleOptions(new Vector3f(1F, 0.5F, 0.12F), 1.6F), attaquant.getX(), attaquant.getY(1), attaquant.getZ(),
                    40, 0.6, 0.8, 0.6, 0.05);
        }
    }

    @SubscribeEvent
    public static void arret(ServerStoppingEvent event) {
        GARDES.clear();
        PRET.clear();
        SCENES.clear();
    }

    private Contre() {
    }
}
