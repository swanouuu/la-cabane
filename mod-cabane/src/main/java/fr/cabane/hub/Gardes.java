package fr.cabane.hub;

import fr.cabane.Cabane;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.phys.EntityHitResult;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.ProjectileImpactEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * La garde présidentielle : quiconque frappe le Président (ou un de ses gardes) est pris en chasse par
 * les quatre gardes pendant deux minutes. Les gardes sont invulnérables ; ils se calment quand le temps
 * est écoulé, ou quand le fautif est mort.
 */
@EventBusSubscriber(modid = Cabane.MODID)
public final class Gardes {
    public static final String MACRON = "cabane_macron";
    public static final String GARDE = "cabane_garde";
    private static final long COLERE = 20 * 120;
    private static final float DEGATS = 8F;
    private static final double POURSUITE = 64;

    /** Les ennemis du Président, et jusqu'à quand (en ticks de jeu). */
    private static final Map<UUID, Long> ENNEMIS = new HashMap<>();

    private static boolean protege(Entity e) {
        return e.getTags().contains(MACRON) || e.getTags().contains(GARDE);
    }

    @SubscribeEvent
    public static void onAttaque(AttackEntityEvent event) {
        if (event.getEntity() instanceof ServerPlayer joueur && protege(event.getTarget())) {
            facher(joueur);
        }
    }

    @SubscribeEvent
    public static void onProjectile(ProjectileImpactEvent event) {
        if (event.getRayTraceResult() instanceof EntityHitResult hit && protege(hit.getEntity())
                && event.getProjectile().getOwner() instanceof ServerPlayer joueur) {
            facher(joueur);
        }
    }

    /** Commande de l'hôte : la garde poursuit ce joueur comme s'il avait frappé le Président. */
    public static void lacher(ServerPlayer joueur) {
        facher(joueur);
    }

    private static void facher(ServerPlayer joueur) {
        if (joueur.isCreative() || joueur.isSpectator()) {
            return;
        }
        Long avant = ENNEMIS.put(joueur.getUUID(), joueur.serverLevel().getGameTime() + COLERE);
        if (avant == null) {
            joueur.serverLevel().playSound(null, joueur.blockPosition(), SoundEvents.RAID_HORN.value(), SoundSource.HOSTILE, 2F, 1F);
            joueur.displayClientMessage(Component.literal("« À la garde ! » La garde présidentielle te prend en chasse !")
                    .withStyle(ChatFormatting.RED, ChatFormatting.BOLD), true);
            joueur.server.getPlayerList().broadcastSystemMessage(Component.literal("⚔ " + joueur.getGameProfile().getName()
                    + " a levé la main sur le Président ! La garde est à ses trousses.").withStyle(ChatFormatting.RED), false);
        }
    }

    private static void pardonner(MinecraftServer server, UUID id, String message) {
        ENNEMIS.remove(id);
        ServerPlayer joueur = server.getPlayerList().getPlayer(id);
        if (joueur != null) {
            joueur.displayClientMessage(Component.literal(message).withStyle(ChatFormatting.GOLD), false);
        }
    }

    @SubscribeEvent
    public static void onMort(LivingDeathEvent event) {
        if (event.getEntity() instanceof ServerPlayer joueur && ENNEMIS.containsKey(joueur.getUUID())) {
            pardonner(joueur.server, joueur.getUUID(), "La garde présidentielle te laisse tranquille… pour cette fois.");
        }
    }

    @SubscribeEvent
    public static void onTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        if (ENNEMIS.isEmpty() || server.getTickCount() % 20 != 0) {
            return;
        }
        long maintenant = server.overworld().getGameTime();
        for (Iterator<Map.Entry<UUID, Long>> it = ENNEMIS.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, Long> e = it.next();
            if (e.getValue() <= maintenant) {
                it.remove();
                ServerPlayer joueur = server.getPlayerList().getPlayer(e.getKey());
                if (joueur != null) {
                    joueur.displayClientMessage(Component.literal("La garde présidentielle s'est calmée. Ne recommence pas.").withStyle(ChatFormatting.GOLD), false);
                }
            }
        }
    }

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        ENNEMIS.clear();
    }

    /** Chaque garde reçoit, en plus de ses objectifs Easy NPC, la chasse aux ennemis du Président. */
    @SubscribeEvent
    public static void onArrivee(EntityJoinLevelEvent event) {
        if (!event.getLevel().isClientSide() && event.getEntity() instanceof PathfinderMob garde && garde.getTags().contains(GARDE)) {
            garde.goalSelector.addGoal(0, new Chasse(garde));
        }
    }

    private static final class Chasse extends Goal {
        private final PathfinderMob garde;
        private ServerPlayer cible;
        private int recharge;
        private int chemin;

        Chasse(PathfinderMob garde) {
            this.garde = garde;
            setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
        }

        private boolean valable(ServerPlayer joueur) {
            return joueur != null && joueur.isAlive() && !joueur.isCreative() && !joueur.isSpectator()
                    && joueur.level() == garde.level() && ENNEMIS.containsKey(joueur.getUUID())
                    && garde.distanceToSqr(joueur) < POURSUITE * POURSUITE;
        }

        @Override
        public boolean canUse() {
            if (ENNEMIS.isEmpty() || garde.getServer() == null) {
                return false;
            }
            ServerPlayer plusProche = null;
            for (UUID id : ENNEMIS.keySet()) {
                ServerPlayer joueur = garde.getServer().getPlayerList().getPlayer(id);
                if (valable(joueur) && (plusProche == null || garde.distanceToSqr(joueur) < garde.distanceToSqr(plusProche))) {
                    plusProche = joueur;
                }
            }
            cible = plusProche;
            return cible != null;
        }

        @Override
        public boolean canContinueToUse() {
            return valable(cible);
        }

        @Override
        public void start() {
            chemin = 0;
            garde.setAggressive(true);
        }

        @Override
        public void stop() {
            cible = null;
            garde.setAggressive(false);
            garde.getNavigation().stop();
        }

        @Override
        public boolean requiresUpdateEveryTick() {
            return true;
        }

        @Override
        public void tick() {
            garde.getLookControl().setLookAt(cible, 30F, 30F);
            if (--chemin <= 0) {
                chemin = 5;
                garde.getNavigation().moveTo(cible, 1.5);
            }
            if (recharge > 0) {
                recharge--;
                return;
            }
            double portee = 2.4 + garde.getBbWidth() / 2;
            if (garde.distanceToSqr(cible) <= portee * portee && garde.hasLineOfSight(cible)) {
                recharge = 16;
                garde.swing(InteractionHand.MAIN_HAND);
                if (cible.hurt(garde.damageSources().mobAttack(garde), DEGATS)) {
                    cible.knockback(0.5, garde.getX() - cible.getX(), garde.getZ() - cible.getZ());
                }
            }
        }
    }

    private Gardes() {
    }
}
