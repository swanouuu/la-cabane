package fr.cabane.pouvoir;

import fr.cabane.Cabane;
import fr.cabane.client.pouvoir.ClientCorbeau;
import fr.cabane.telephone.VoiceBridge;
import io.netty.buffer.ByteBuf;
import java.util.ArrayList;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.ChatFormatting;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.player.Abilities;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityEvent;
import net.neoforged.neoforge.event.entity.living.LivingChangeTargetEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/**
 * Le corbeau (susanodev seulement, sans limite de temps ; docs/susanodev/planche-corbeau.png) : son corps explose en encre
 * et en plumes, un corbeau s'envole. Il vole vite, il entend tout le monde en vocal et personne ne l'entend, il n'a pas de
 * nom, il ne frappe pas, ne casse rien et rien ne le touche. Le dessin est dans {@link ClientCorbeau}.
 */
@EventBusSubscriber(modid = Cabane.MODID)
public final class Corbeau {
    private static final float VITESSE_VOL = 0.09F, VITESSE_NORMALE = 0.05F;
    private static final EntityDimensions TAILLE = EntityDimensions.scalable(0.5F, 0.5F).withEyeHeight(0.4F);
    /** Sert aux deux côtés : le serveur le remplit lui-même, le client d'après {@link Etat}. */
    private static final Set<UUID> ACTIFS = ConcurrentHashMap.newKeySet();

    private Corbeau() {
    }

    public static boolean actif(UUID joueur) {
        return ACTIFS.contains(joueur);
    }

    public static boolean aucun() {
        return ACTIFS.isEmpty();
    }

    /** Le client recopie ici ce que le serveur lui dit. */
    public static void definir(UUID joueur, boolean actif) {
        if (actif) {
            ACTIFS.add(joueur);
        } else {
            ACTIFS.remove(joueur);
        }
    }

    /** Serveur → tous : ce joueur passe en corbeau ou en revient ({@code annonce} : avec l'explosion de plumes). */
    public record Etat(UUID joueur, boolean actif, boolean annonce) implements CustomPacketPayload {
        public static final Type<Etat> TYPE = new Type<>(Cabane.id("corbeau_etat"));
        public static final StreamCodec<ByteBuf, Etat> CODEC = StreamCodec.composite(
                UUIDUtil.STREAM_CODEC, Etat::joueur, ByteBufCodecs.BOOL, Etat::actif, ByteBufCodecs.BOOL, Etat::annonce, Etat::new);

        @Override
        public Type<Etat> type() {
            return TYPE;
        }
    }

    @SubscribeEvent
    public static void reseau(RegisterPayloadHandlersEvent event) {
        event.registrar("1").playToClient(Etat.TYPE, Etat.CODEC, (p, ctx) -> ctx.enqueueWork(() -> ClientCorbeau.etat(p)));
    }

    // ================================================================ se changer

    static void basculer(ServerPlayer joueur) {
        if (actif(joueur.getUUID())) {
            AABB debout = new AABB(joueur.getX() - 0.3, joueur.getY(), joueur.getZ() - 0.3, joueur.getX() + 0.3, joueur.getY() + 1.8, joueur.getZ() + 0.3);
            if (!joueur.level().noCollision(joueur, debout)) {
                message(joueur, "Pas assez de place pour redevenir toi.");
                return;
            }
            sortir(joueur, true);
            return;
        }
        if (Pouvoirs.niveau(joueur) > 0) {
            message(joueur, "Range d'abord ton Susano.");
            return;
        }
        if (joueur.level().dimension() == Genjutsu.DIMENSION) {
            return;
        }
        joueur.stopRiding();
        // il décolle tout de suite : au sol, le jeu lui couperait le vol
        if (joueur.level().noCollision(joueur, joueur.getBoundingBox().move(0, 1.2, 0))) {
            joueur.connection.teleport(joueur.getX(), joueur.getY() + 1.2, joueur.getZ(), joueur.getYRot(), joueur.getXRot());
        }
        ACTIFS.add(joueur.getUUID());
        joueur.setSilent(true);
        VoiceBridge.muet(joueur.getUUID(), true);
        Abilities a = joueur.getAbilities();
        a.mayfly = true;
        a.flying = true;
        a.setFlyingSpeed(VITESSE_VOL);
        joueur.onUpdateAbilities();
        joueur.refreshDimensions();
        PacketDistributor.sendToAllPlayers(new Etat(joueur.getUUID(), true, true));
    }

    /** Il redevient lui : on l'entend de nouveau, il ne vole plus (et il ne s'écrase pas s'il était en l'air). */
    static void sortir(ServerPlayer joueur, boolean annonce) {
        if (!ACTIFS.remove(joueur.getUUID())) {
            return;
        }
        joueur.setSilent(false);
        VoiceBridge.muet(joueur.getUUID(), false);
        Abilities a = joueur.getAbilities();
        a.setFlyingSpeed(VITESSE_NORMALE);
        if (!joueur.isCreative() && !joueur.isSpectator()) {
            a.mayfly = false;
            a.flying = false;
        }
        joueur.onUpdateAbilities();
        joueur.refreshDimensions();
        joueur.fallDistance = 0;
        if (!joueur.onGround() && !a.flying) {
            joueur.addEffect(new MobEffectInstance(MobEffects.SLOW_FALLING, 100, 0, false, false));
        }
        PacketDistributor.sendToAllPlayers(new Etat(joueur.getUUID(), false, annonce));
    }

    private static void message(ServerPlayer joueur, String texte) {
        joueur.displayClientMessage(Component.literal(texte).withStyle(ChatFormatting.GRAY), true);
    }

    @SubscribeEvent
    public static void tick(ServerTickEvent.Post event) {
        if (ACTIFS.isEmpty()) {
            return;
        }
        for (UUID u : new ArrayList<>(ACTIFS)) {
            ServerPlayer p = event.getServer().getPlayerList().getPlayer(u);
            if (p == null) {
                continue;
            }
            if (!Susano3.DEV.equals(Pouvoirs.pouvoir(p))) {
                sortir(p, true);
            } else if (!p.getAbilities().mayfly) {
                p.getAbilities().mayfly = true;
                p.onUpdateAbilities();
            }
        }
    }

    /** Ses yeux s'endorment : il redevient lui. */
    static void endormir(ServerPlayer joueur) {
        sortir(joueur, true);
    }

    // ================================================================ un corbeau, c'est petit

    @SubscribeEvent
    public static void taille(EntityEvent.Size event) {
        if (event.getEntity() instanceof Player p && actif(p.getUUID())) {
            event.setNewSize(TAILLE);
        }
    }

    // ================================================================ il ne touche à rien, et rien ne le touche

    @SubscribeEvent
    public static void intouchable(LivingIncomingDamageEvent event) {
        if (!ACTIFS.isEmpty() && (actif(event.getEntity().getUUID()) || (event.getSource().getEntity() != null && actif(event.getSource().getEntity().getUUID())))) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void frapper(AttackEntityEvent event) {
        if (actif(event.getEntity().getUUID())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void cible(LivingChangeTargetEvent event) {
        if (event.getNewAboutToBeSetTarget() != null && actif(event.getNewAboutToBeSetTarget().getUUID())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void casser(BlockEvent.BreakEvent event) {
        if (actif(event.getPlayer().getUUID())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void poser(BlockEvent.EntityPlaceEvent event) {
        if (event.getEntity() instanceof Player p && actif(p.getUUID())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void clicBloc(PlayerInteractEvent.RightClickBlock event) {
        if (actif(event.getEntity().getUUID())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void clicObjet(PlayerInteractEvent.RightClickItem event) {
        if (actif(event.getEntity().getUUID())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void clicEntite(PlayerInteractEvent.EntityInteract event) {
        if (actif(event.getEntity().getUUID())) {
            event.setCanceled(true);
        }
    }

    // ================================================================ les arrivées, les départs

    @SubscribeEvent
    public static void arrivee(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer p) {
            for (UUID u : ACTIFS) {
                PacketDistributor.sendToPlayer(p, new Etat(u, true, false));
            }
        }
    }

    @SubscribeEvent
    public static void depart(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer p) {
            sortir(p, false);
        }
    }

    @SubscribeEvent
    public static void mort(LivingDeathEvent event) {
        if (event.getEntity() instanceof ServerPlayer p) {
            sortir(p, false);
        }
    }

    @SubscribeEvent
    public static void changeDeMonde(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer p) {
            sortir(p, false);
        }
    }

    @SubscribeEvent
    public static void arret(ServerStoppingEvent event) {
        for (UUID u : new ArrayList<>(ACTIFS)) {
            VoiceBridge.muet(u, false);
        }
        ACTIFS.clear();
    }
}
