package fr.cabane.pouvoir;

import fr.cabane.Cabane;
import io.netty.buffer.ByteBuf;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/**
 * Le Susano niveau 3, le chevalier (listes « susano3 » : niveaux 1, 2 puis 3 ; « susanodev » : droit au niveau 3).
 * Le clic gauche, ce sont les coups d'épée (dans {@link Pouvoirs#frapper}, plus loin et plus fort). Ici, le clic droit :
 * il tend un grand arc, et la flèche part un peu plus tard. Elle file droit (en aidant un peu à viser) et ce qu'elle
 * touche prend feu : les flammes noires d'Amaterasu, plus longues et plus fortes qu'un feu normal ; l'eau ne les éteint
 * pas. Accroupi : le grand coup ; il lève son épée et l'abat, une vague de flammes noires part tout autour de lui et
 * embrase ce qu'elle touche. Les flammes, la flèche et la vague sont dessinées chez chaque joueur (ClientSusano3).
 */
@EventBusSubscriber(modid = Cabane.MODID)
public final class Susano3 {
    public static final String NOM = "susano3";
    public static final String DEV = "susanodev";
    /** L'arc se recharge en 8 secondes ; on le tend en une demi-seconde. */
    public static final int RECHARGE_ARC = 160;
    public static final int TENSION = 10;
    private static final double PORTEE_ARC = 48;
    private static final float DEGATS_FLECHE = 10;
    /** Les flammes noires : 2 dégâts par seconde pendant 10 secondes (un feu normal : 1 par seconde, bien moins longtemps). */
    public static final int DUREE_FEU = 200;
    private static final float DEGATS_FEU = 2;

    /** Le grand coup (accroupi) : 15 secondes de recharge ; l'épée touche le sol au bout de 9 ticks. */
    public static final int RECHARGE_GRAND = 300;
    public static final int IMPACT_GRAND = 9;
    private static final float DEGATS_GRAND = 18;
    /** Il touche tout ce qui est à 8 blocs autour de lui, et jusqu'à 12 blocs devant. */
    private static final double RAYON_GRAND = 8;
    private static final double PORTEE_GRAND = 12;

    public static final ResourceKey<DamageType> AMATERASU = ResourceKey.create(Registries.DAMAGE_TYPE, Cabane.id("amaterasu"));

    public static boolean est(String pouvoir) {
        return NOM.equals(pouvoir) || DEV.equals(pouvoir);
    }

    /** Qui tend son arc : le joueur → le tick où il a commencé. Et quand chacun a tiré pour la dernière fois. */
    private static final Map<UUID, Integer> TIRS = new HashMap<>();
    private static final Map<UUID, Integer> DERNIER_TIR = new HashMap<>();
    /** Qui abat son épée : le joueur → le tick du coup. Et quand chacun l'a fait pour la dernière fois. */
    private static final Map<UUID, Integer> GRANDS = new HashMap<>();
    private static final Map<UUID, Integer> DERNIER_GRAND = new HashMap<>();

    /** Ce qui brûle : l'entité → jusqu'à quand, et qui a tiré. */
    private record Feu(ServerLevel monde, int fin, UUID tireur) {
    }

    private static final Map<Integer, Feu> FEUX = new HashMap<>();

    /** Serveur → ceux qui voient cette entité : elle brûle de flammes noires pendant ce nombre de ticks (0 : éteinte). */
    public record Flammes(int entite, int ticks) implements CustomPacketPayload {
        public static final Type<Flammes> TYPE = new Type<>(Cabane.id("amaterasu"));
        public static final StreamCodec<ByteBuf, Flammes> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, Flammes::entite, ByteBufCodecs.VAR_INT, Flammes::ticks, Flammes::new);

        @Override
        public Type<Flammes> type() {
            return TYPE;
        }
    }

    @SubscribeEvent
    public static void reseau(RegisterPayloadHandlersEvent event) {
        event.registrar("1").playToClient(Flammes.TYPE, Flammes.CODEC,
                (p, ctx) -> ctx.enqueueWork(() -> fr.cabane.client.pouvoir.ClientSusano3.flammes(p)));
    }

    /** Le clic droit au niveau 3 : il tend l'arc (s'il est rechargé). */
    static void tirer(ServerPlayer joueur) {
        int maintenant = joueur.server.getTickCount();
        if (TIRS.containsKey(joueur.getUUID())) {
            return;
        }
        int reste = DERNIER_TIR.getOrDefault(joueur.getUUID(), -10_000) + RECHARGE_ARC - maintenant;
        if (reste > 0) {
            joueur.displayClientMessage(Component.literal("L'arc se reforme… " + (reste + 19) / 20 + " s").withStyle(ChatFormatting.GRAY), true);
            return;
        }
        TIRS.put(joueur.getUUID(), maintenant);
        DERNIER_TIR.put(joueur.getUUID(), maintenant);
        Pouvoirs.geste(joueur, Pouvoirs.ARC, -1);
        joueur.serverLevel().playSound(null, joueur.getX(), joueur.getY() + 2, joueur.getZ(), SoundEvents.CROSSBOW_LOADING_MIDDLE.value(), SoundSource.PLAYERS, 1.4F, 0.5F);
    }

    static void endormir(ServerPlayer joueur) {
        TIRS.remove(joueur.getUUID());
        GRANDS.remove(joueur.getUUID());
    }

    /** Accroupi au niveau 3 : il lève son épée (s'il le peut). */
    static void grandCoup(ServerPlayer joueur) {
        int maintenant = joueur.server.getTickCount();
        if (GRANDS.containsKey(joueur.getUUID())) {
            return;
        }
        int reste = DERNIER_GRAND.getOrDefault(joueur.getUUID(), -10_000) + RECHARGE_GRAND - maintenant;
        if (reste > 0) {
            joueur.displayClientMessage(Component.literal("Le grand coup se recharge… " + (reste + 19) / 20 + " s").withStyle(ChatFormatting.GRAY), true);
            return;
        }
        GRANDS.put(joueur.getUUID(), maintenant);
        DERNIER_GRAND.put(joueur.getUUID(), maintenant);
        Pouvoirs.geste(joueur, Pouvoirs.GRAND_COUP, -1);
    }

    /** L'épée touche le sol : tout ce qui est autour (et loin devant) prend le coup et s'embrase de flammes noires. */
    private static void abattre(ServerPlayer joueur) {
        ServerLevel monde = joueur.serverLevel();
        Vec3 devant = Pouvoirs.devant(joueur), ici = joueur.position();
        for (LivingEntity e : monde.getEntitiesOfClass(LivingEntity.class, joueur.getBoundingBox().inflate(PORTEE_GRAND, 4, PORTEE_GRAND),
                e -> Pouvoirs.cible(joueur, e))) {
            Vec3 vers = e.position().subtract(ici), plat = new Vec3(vers.x, 0, vers.z);
            double d = plat.length();
            if (d > RAYON_GRAND && (d > PORTEE_GRAND || plat.normalize().dot(devant) < 0.6)) {
                continue;
            }
            e.hurt(joueur.damageSources().playerAttack(joueur), DEGATS_GRAND);
            if (d > 0.1) {
                e.knockback(1.6, -plat.x / d, -plat.z / d);
            }
            e.setDeltaMovement(e.getDeltaMovement().add(0, 0.45, 0));
            e.hurtMarked = true;
            enflammer(e, joueur);
        }
        Vec3 impact = ici.add(devant.scale(5));
        monde.sendParticles(ParticleTypes.EXPLOSION_EMITTER, impact.x, impact.y + 0.3, impact.z, 1, 0, 0, 0, 0);
        for (int i = 0; i < 48; i++) {
            double a = i / 48.0 * Math.PI * 2;
            monde.sendParticles(ParticleTypes.SQUID_INK, ici.x + Math.cos(a) * RAYON_GRAND * 0.7, ici.y + 0.3, ici.z + Math.sin(a) * RAYON_GRAND * 0.7,
                    3, 0.6, 0.3, 0.6, 0.08);
            monde.sendParticles(ParticleTypes.LARGE_SMOKE, ici.x + Math.cos(a) * RAYON_GRAND * 0.5, ici.y + 0.2, ici.z + Math.sin(a) * RAYON_GRAND * 0.5,
                    1, 0.4, 0.2, 0.4, 0.04);
        }
        monde.playSound(null, impact.x, impact.y, impact.z, SoundEvents.GENERIC_EXPLODE.value(), SoundSource.PLAYERS, 2.5F, 0.5F);
        monde.playSound(null, impact.x, impact.y, impact.z, SoundEvents.FIRECHARGE_USE, SoundSource.PLAYERS, 2F, 0.4F);
        monde.playSound(null, impact.x, impact.y, impact.z, SoundEvents.WITHER_SHOOT, SoundSource.PLAYERS, 1.2F, 0.5F);
    }

    /** La flèche part : elle file là où il regarde, et ce qu'elle touche en premier prend les flammes noires. */
    private static void lacher(ServerPlayer joueur) {
        ServerLevel monde = joueur.serverLevel();
        Vec3 oeil = joueur.getEyePosition(), regard = joueur.getLookAngle();
        Vec3 bout = oeil.add(regard.scale(PORTEE_ARC));
        HitResult mur = monde.clip(new ClipContext(oeil, bout, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, joueur));
        if (mur.getType() != HitResult.Type.MISS) {
            bout = mur.getLocation();
        }
        // la cible : ce qui vit le plus près sur la ligne, en aidant un peu à viser
        LivingEntity cible = null;
        double meilleure = Double.MAX_VALUE;
        for (LivingEntity e : monde.getEntitiesOfClass(LivingEntity.class, new AABB(oeil, bout).inflate(1.5), e -> Pouvoirs.cible(joueur, e))) {
            var touche = e.getBoundingBox().inflate(0.7).clip(oeil, bout);
            if (touche.isPresent() && touche.get().distanceToSqr(oeil) < meilleure) {
                meilleure = touche.get().distanceToSqr(oeil);
                cible = e;
            }
        }
        Pouvoirs.geste(joueur, Pouvoirs.FLECHE, cible == null ? -1 : cible.getId());
        monde.playSound(null, joueur.getX(), joueur.getY() + 2, joueur.getZ(), SoundEvents.CROSSBOW_SHOOT, SoundSource.PLAYERS, 2F, 0.5F);
        monde.playSound(null, joueur.getX(), joueur.getY() + 2, joueur.getZ(), SoundEvents.BLAZE_SHOOT, SoundSource.PLAYERS, 1F, 0.4F);
        if (cible == null) {
            monde.sendParticles(ParticleTypes.SQUID_INK, bout.x, bout.y, bout.z, 20, 0.3, 0.3, 0.3, 0.05);
            return;
        }
        cible.hurt(joueur.damageSources().playerAttack(joueur), DEGATS_FLECHE);
        enflammer(cible, joueur);
    }

    /** Les flammes noires prennent sur cette entité (ou repartent pour 10 secondes si elle brûlait déjà). */
    public static void enflammer(LivingEntity cible, ServerPlayer tireur) {
        if (!(cible.level() instanceof ServerLevel monde)) {
            return;
        }
        FEUX.put(cible.getId(), new Feu(monde, monde.getServer().getTickCount() + DUREE_FEU, tireur.getUUID()));
        cible.clearFire();
        PacketDistributor.sendToPlayersTrackingEntityAndSelf(cible, new Flammes(cible.getId(), DUREE_FEU));
        monde.playSound(null, cible.getX(), cible.getY(), cible.getZ(), SoundEvents.FIRECHARGE_USE, SoundSource.PLAYERS, 1.2F, 0.4F);
    }

    @SubscribeEvent
    public static void tick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        int maintenant = server.getTickCount();
        // les arcs tendus : la flèche part au bout de la tension
        for (Map.Entry<UUID, Integer> tir : new ArrayList<>(TIRS.entrySet())) {
            ServerPlayer joueur = server.getPlayerList().getPlayer(tir.getKey());
            if (joueur == null) {
                TIRS.remove(tir.getKey());
            } else if (maintenant - tir.getValue() >= TENSION) {
                TIRS.remove(tir.getKey());
                lacher(joueur);
            }
        }
        // les grands coups : l'épée touche le sol au bout de l'élan
        for (Map.Entry<UUID, Integer> coup : new ArrayList<>(GRANDS.entrySet())) {
            ServerPlayer joueur = server.getPlayerList().getPlayer(coup.getKey());
            if (joueur == null) {
                GRANDS.remove(coup.getKey());
            } else if (maintenant - coup.getValue() >= IMPACT_GRAND) {
                GRANDS.remove(coup.getKey());
                abattre(joueur);
            }
        }
        // les flammes noires brûlent : des dégâts chaque seconde, de la fumée noire ; l'eau n'y fait rien
        for (Map.Entry<Integer, Feu> entree : new ArrayList<>(FEUX.entrySet())) {
            Feu feu = entree.getValue();
            Entity e = feu.monde().getEntity(entree.getKey());
            if (!(e instanceof LivingEntity cible) || !cible.isAlive() || maintenant >= feu.fin()) {
                FEUX.remove(entree.getKey());
                if (e != null) {
                    PacketDistributor.sendToPlayersTrackingEntityAndSelf(e, new Flammes(entree.getKey(), 0));
                }
                continue;
            }
            cible.clearFire();
            if ((feu.fin() - maintenant) % 20 == 0) {
                ServerPlayer tireur = server.getPlayerList().getPlayer(feu.tireur());
                cible.invulnerableTime = 0;
                cible.hurt(new DamageSource(feu.monde().registryAccess().registryOrThrow(Registries.DAMAGE_TYPE).getHolderOrThrow(AMATERASU), tireur), DEGATS_FEU);
            }
            if (maintenant % 3 == 0) {
                feu.monde().sendParticles(ParticleTypes.SQUID_INK, cible.getX(), cible.getY(0.6), cible.getZ(), 2, 0.3, 0.4, 0.3, 0.02);
            }
        }
    }

    private Susano3() {
    }
}
