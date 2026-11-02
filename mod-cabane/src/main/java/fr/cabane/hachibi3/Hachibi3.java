package fr.cabane.hachibi3;

import fr.cabane.Cabane;
import fr.cabane.CabaneEntities;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * Le Hachibi géant (hachibi3) : la bête entière, rose, tête de taureau et huit tentacules, 76 blocs de haut, d'après la
 * maquette validée (docs/hachibi-geant). Il est enchaîné par les poignets et le cou : il ne bouge pas, n'attaque personne
 * et rien ne le blesse ; de temps en temps, il rugit en tirant sur ses chaînes. On le fait apparaître avec /hachibi3
 * (devant soi, tourné vers soi) ou /summon cabane:hachibi3 ; le bouton de la salle des rituels du labo en libère un
 * derrière le mur (voir labo.Scellement).
 */
@EventBusSubscriber(modid = Cabane.MODID)
public class Hachibi3 extends Entity {
    /** Le moment (temps du monde) où il a commencé son dernier cri, -1 s'il n'a pas encore crié. */
    private static final EntityDataAccessor<Integer> CRI = SynchedEntityData.defineId(Hachibi3.class, EntityDataSerializers.INT);
    /** La durée d'un cri, en ticks. */
    public static final int DUREE_CRI = 70;
    /** Jusqu'où on l'entend et on sent la secousse de son cri. */
    public static final double PORTEE_CRI = 140;

    private int prochainCri = 300;

    public Hachibi3(EntityType<? extends Hachibi3> type, Level level) {
        super(type, level);
        noPhysics = true;
        // il est bien plus grand que sa boîte : on le dessine même quand son centre est hors de l'écran
        noCulling = true;
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(CRI, -1);
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
    }

    @Override
    public void tick() {
        super.tick();
        if (level() instanceof ServerLevel level && --prochainCri <= 0) {
            prochainCri = 500 + random.nextInt(500);
            if (level.getNearestPlayer(this, PORTEE_CRI) != null) {
                crier();
            }
        }
    }

    /** Il rugit : la tête se lève, la gueule s'ouvre, les chaînes se tendent et tout tremble autour. */
    public void crier() {
        entityData.set(CRI, (int) level().getGameTime());
        prochainCri = Math.max(prochainCri, DUREE_CRI + 100);
        Vec3 tete = tete();
        level().playSound(null, tete.x, tete.y, tete.z, SoundEvents.ENDER_DRAGON_GROWL, SoundSource.HOSTILE, 9F, 0.45F);
        level().playSound(null, tete.x, tete.y, tete.z, SoundEvents.RAVAGER_ROAR, SoundSource.HOSTILE, 9F, 0.35F);
        level().playSound(null, tete.x, tete.y, tete.z, SoundEvents.WARDEN_ROAR, SoundSource.HOSTILE, 7F, 0.5F);
        level().playSound(null, getX(), getY() + 10, getZ(), SoundEvents.CHAIN_BREAK, SoundSource.BLOCKS, 5F, 0.4F);
    }

    /** Où est sa tête (pour les sons) : 36 blocs plus haut, une vingtaine devant. */
    public Vec3 tete() {
        return position().add(Vec3.directionFromRotation(0, getYRot()).scale(22)).add(0, 36, 0);
    }

    /** Où en est son cri, de 0 (au repos) à 1 (la tête levée, la gueule ouverte). */
    public float cri(float partiel) {
        int debut = entityData.get(CRI);
        if (debut < 0) {
            return 0;
        }
        float t = (float) (level().getGameTime() - debut) + partiel;
        if (t < 0 || t > DUREE_CRI) {
            return 0;
        }
        float monte = Mth.clamp(t / 10F, 0, 1), descend = Mth.clamp((DUREE_CRI - t) / 18F, 0, 1);
        float k = Math.min(monte, descend);
        return k * k * (3 - 2 * k);
    }

    @Override
    public boolean hurt(DamageSource source, float amount) {
        return false;
    }

    @Override
    public boolean isPickable() {
        return false;
    }

    @Override
    public boolean isAttackable() {
        return false;
    }

    @Override
    public PushReaction getPistonPushReaction() {
        return PushReaction.IGNORE;
    }

    @Override
    public boolean shouldRenderAtSqrDistance(double distance) {
        return distance < 400 * 400;
    }

    // ------------------------------------------------------------------ /hachibi3

    @SubscribeEvent
    public static void commandes(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("hachibi3").requires(s -> s.hasPermission(2))
                .executes(ctx -> invoquer(ctx.getSource()))
                .then(Commands.literal("cri").executes(ctx -> {
                    Hachibi3 bete = plusProche(ctx.getSource());
                    if (bete == null) {
                        ctx.getSource().sendFailure(Component.literal("Aucun Hachibi géant à moins de 200 blocs."));
                        return 0;
                    }
                    bete.crier();
                    return 1;
                }))
                .then(Commands.literal("sceau").executes(ctx -> {
                    // comme le bouton de la salle des rituels du labo
                    ServerLevel labo = fr.cabane.labo.Labo.niveau(ctx.getSource().getServer());
                    if (labo == null) {
                        ctx.getSource().sendFailure(Component.literal("La dimension du Laboratoire est introuvable."));
                        return 0;
                    }
                    fr.cabane.labo.Scellement.liberer(labo);
                    return 1;
                }))
                .then(Commands.literal("retirer").executes(ctx -> {
                    Hachibi3 bete = plusProche(ctx.getSource());
                    if (bete == null) {
                        ctx.getSource().sendFailure(Component.literal("Aucun Hachibi géant à moins de 200 blocs."));
                        return 0;
                    }
                    bete.discard();
                    ctx.getSource().sendSuccess(() -> Component.literal("Le Hachibi géant est retiré."), true);
                    return 1;
                })));
    }

    /** Devant soi, à 45 blocs, posé sur le sol et tourné vers soi. */
    private static int invoquer(CommandSourceStack source) {
        ServerLevel level = source.getLevel();
        Vec3 ici = source.getPosition();
        float lacet = source.getRotation().y;
        Vec3 devant = Vec3.directionFromRotation(0, lacet);
        int x = Mth.floor(ici.x + devant.x * 45), z = Mth.floor(ici.z + devant.z * 45);
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        if (y <= level.getMinBuildHeight()) {
            y = Mth.floor(ici.y);
        }
        Hachibi3 bete = CabaneEntities.HACHIBI3.get().create(level);
        if (bete == null) {
            return 0;
        }
        bete.moveTo(x + 0.5, y, z + 0.5, Mth.wrapDegrees(lacet + 180), 0);
        level.addFreshEntity(bete);
        bete.crier();
        source.sendSuccess(() -> Component.literal("Le Hachibi apparaît, enchaîné.").withStyle(ChatFormatting.RED), true);
        return 1;
    }

    private static Hachibi3 plusProche(CommandSourceStack source) {
        Vec3 ici = source.getPosition();
        return source.getLevel().getEntitiesOfClass(Hachibi3.class, new AABB(ici, ici).inflate(200)).stream()
                .min(java.util.Comparator.comparingDouble(b -> b.distanceToSqr(ici))).orElse(null);
    }
}
