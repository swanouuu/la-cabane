package fr.cabane.police;

import fr.cabane.CabaneItems;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * La voiture de police : quatre places, pas d'essence, une sirène. Comme un bateau, c'est le client du
 * conducteur qui la pilote (ZQSD) ; elle monte les marches d'un bloc.
 */
public class VoitureEntity extends Entity {
    private static final EntityDataAccessor<Boolean> SIRENE = SynchedEntityData.defineId(VoitureEntity.class, EntityDataSerializers.BOOLEAN);
    private static final float VITESSE_MAX = 0.62F;
    private static final float MARCHE_ARRIERE = 0.22F;
    /** Les sièges : x (gauche +), z (avant +). */
    private static final double[][] SIEGES = {{0.42, 0.15}, {-0.42, 0.15}, {0.42, -0.75}, {-0.42, -0.75}};

    private float vitesse;
    private float rotation;
    private int lerpSteps;
    private double lerpX;
    private double lerpY;
    private double lerpZ;
    private float lerpYRot;

    public VoitureEntity(EntityType<? extends VoitureEntity> type, Level level) {
        super(type, level);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(SIRENE, false);
    }

    public boolean sirene() {
        return entityData.get(SIRENE);
    }

    void basculerSirene() {
        entityData.set(SIRENE, !sirene());
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        entityData.set(SIRENE, tag.getBoolean("Sirene"));
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        tag.putBoolean("Sirene", sirene());
    }

    @Override
    public InteractionResult interact(Player joueur, InteractionHand main) {
        if (joueur.isSecondaryUseActive()) {
            return InteractionResult.PASS;
        }
        if (!level().isClientSide) {
            // « true » : on peut remonter tout de suite après être descendu (sans le délai de Minecraft)
            return getPassengers().size() < SIEGES.length && joueur.startRiding(this, true) ? InteractionResult.CONSUME : InteractionResult.PASS;
        }
        return InteractionResult.SUCCESS;
    }

    /** Accroupi + frapper (ou en créatif) : la voiture retourne dans l'inventaire. Sinon, elle encaisse. */
    @Override
    public boolean hurt(DamageSource source, float degats) {
        if (level().isClientSide || isRemoved()) {
            return true;
        }
        if (source.getEntity() instanceof Player joueur && (joueur.isShiftKeyDown() || joueur.isCreative()) && getPassengers().isEmpty()) {
            if (!joueur.isCreative()) {
                spawnAtLocation(new ItemStack(CabaneItems.VOITURE_POLICE.get()));
            }
            discard();
        }
        return true;
    }

    @Override
    public boolean isPickable() {
        return !isRemoved();
    }

    @Override
    public boolean canBeCollidedWith() {
        return true;
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    @Override
    public float maxUpStep() {
        return 1.05F;
    }

    @Override
    protected boolean canAddPassenger(Entity passager) {
        return getPassengers().size() < SIEGES.length;
    }

    @Override
    public LivingEntity getControllingPassenger() {
        return getFirstPassenger() instanceof Player joueur ? joueur : null;
    }

    @Override
    protected Vec3 getPassengerAttachmentPoint(Entity passager, EntityDimensions dimensions, float partialTick) {
        int i = Math.max(0, getPassengers().indexOf(passager));
        double[] siege = SIEGES[Math.min(i, SIEGES.length - 1)];
        return new Vec3(siege[0], 0.45, siege[1]).yRot(-getYRot() * Mth.DEG_TO_RAD);
    }

    @Override
    protected void positionRider(Entity passager, Entity.MoveFunction deplacer) {
        super.positionRider(passager, deplacer);
        // les passagers tournent avec la voiture
        passager.setYRot(passager.getYRot() + rotation);
        passager.setYHeadRot(passager.getYHeadRot() + rotation);
    }

    /** On descend à côté de sa portière, pas dans la carrosserie. */
    @Override
    public Vec3 getDismountLocationForPassenger(LivingEntity passager) {
        Vec3 droite = new Vec3(-1, 0, 0).yRot(-getYRot() * Mth.DEG_TO_RAD);
        double cote = passager.position().subtract(position()).dot(droite) >= 0 ? 1 : -1;
        for (double ecart : new double[] {1.7, 2.4}) {
            for (double sens : new double[] {cote, -cote}) {
                Vec3 p = position().add(droite.scale(sens * ecart));
                Vec3 sur = net.minecraft.world.entity.vehicle.DismountHelper.findSafeDismountLocation(passager.getType(), level(),
                        net.minecraft.core.BlockPos.containing(p), true);
                if (sur != null) {
                    return sur;
                }
            }
        }
        return super.getDismountLocationForPassenger(passager);
    }

    /** Pour le son du moteur : la vitesse actuelle, de 0 à 1. */
    public float regime() {
        Vec3 v = getDeltaMovement();
        return (float) Math.min(1, Math.sqrt(v.x * v.x + v.z * v.z) / VITESSE_MAX);
    }

    @Override
    public boolean causeFallDamage(float distance, float multiplier, DamageSource source) {
        return false;
    }

    @Override
    public void lerpTo(double x, double y, double z, float yRot, float xRot, int steps) {
        lerpX = x;
        lerpY = y;
        lerpZ = z;
        lerpYRot = yRot;
        lerpSteps = 3;
    }

    @Override
    public void tick() {
        super.tick();
        rotation = 0;
        if (isControlledByLocalInstance()) {
            conduire();
        } else if (lerpSteps > 0) {
            setPos(getX() + (lerpX - getX()) / lerpSteps, getY() + (lerpY - getY()) / lerpSteps, getZ() + (lerpZ - getZ()) / lerpSteps);
            setYRot(getYRot() + Mth.wrapDegrees(lerpYRot - getYRot()) / lerpSteps);
            lerpSteps--;
        }
    }

    private void conduire() {
        float avant = 0;
        float cote = 0;
        if (getControllingPassenger() instanceof Player conducteur) {
            avant = conducteur.zza;
            cote = conducteur.xxa;
        }
        if (avant > 0) {
            vitesse = Math.min(VITESSE_MAX, vitesse + (vitesse < 0 ? 0.06F : 0.018F));
        } else if (avant < 0) {
            vitesse = Math.max(-MARCHE_ARRIERE, vitesse - (vitesse > 0 ? 0.05F : 0.015F));
        } else {
            vitesse *= 0.93F;
            if (Math.abs(vitesse) < 0.005F) {
                vitesse = 0;
            }
        }
        if (isInWater()) {
            vitesse *= 0.7F;
        }
        // on ne braque qu'en roulant, et à l'envers en marche arrière
        float braquage = Math.min(1F, Math.abs(vitesse) / 0.15F) * Math.signum(vitesse);
        rotation = -cote * 4.5F * braquage;
        setYRot(getYRot() + rotation);
        double rad = getYRot() * Mth.DEG_TO_RAD;
        double vy = onGround() ? -0.04 : Math.max(-1.5, getDeltaMovement().y - 0.08);
        if (isInWater()) {
            vy = 0.04;
        }
        setDeltaMovement(-Math.sin(rad) * vitesse, vy, Math.cos(rad) * vitesse);
        move(MoverType.SELF, getDeltaMovement());
        if (horizontalCollision) {
            vitesse *= 0.4F;
        }
    }
}
