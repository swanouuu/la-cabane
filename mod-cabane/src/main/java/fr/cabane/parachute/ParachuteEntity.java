package fr.cabane.parachute;

import fr.cabane.CabaneItems;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * Le parachute ouvert : le joueur y est accroché, descend doucement et se dirige
 * avec ZQSD (comme un bateau, c'est son client qui pilote). Au sol ou dans l'eau, il se replie ;
 * accroupi, on coupe les sangles.
 */
public class ParachuteEntity extends Entity {
    private static final double CHUTE = -0.12;
    private static final double VITESSE = 0.32;

    private int lerpSteps;
    private double lerpX;
    private double lerpY;
    private double lerpZ;
    private int vie;

    public ParachuteEntity(EntityType<? extends ParachuteEntity> type, Level level) {
        super(type, level);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
    }

    @Override
    public LivingEntity getControllingPassenger() {
        return getFirstPassenger() instanceof Player joueur ? joueur : null;
    }

    @Override
    public boolean shouldRiderSit() {
        return false;
    }

    @Override
    protected Vec3 getPassengerAttachmentPoint(Entity passager, EntityDimensions dimensions, float partialTick) {
        return Vec3.ZERO;
    }

    @Override
    public boolean causeFallDamage(float distance, float multiplier, DamageSource source) {
        return false;
    }

    @Override
    public boolean isPickable() {
        return false;
    }

    @Override
    public void lerpTo(double x, double y, double z, float yRot, float xRot, int steps) {
        lerpX = x;
        lerpY = y;
        lerpZ = z;
        lerpSteps = 3;
    }

    @Override
    public void tick() {
        super.tick();
        vie++;
        resetFallDistance();
        Entity passager = getFirstPassenger();
        if (passager != null) {
            passager.resetFallDistance();
        }
        if (isControlledByLocalInstance()) {
            if (passager instanceof Player joueur) {
                setYRot(joueur.getYRot());
                float avant = joueur.zza;
                float cote = joueur.xxa;
                double rad = Math.toRadians(getYRot());
                double vx = (-Math.sin(rad) * avant + Math.cos(rad) * cote) * VITESSE + -Math.sin(rad) * 0.08;
                double vz = (Math.cos(rad) * avant + Math.sin(rad) * cote) * VITESSE + Math.cos(rad) * 0.08;
                setDeltaMovement(vx, CHUTE, vz);
            } else {
                setDeltaMovement(0, CHUTE, 0);
            }
            move(MoverType.SELF, getDeltaMovement());
        } else if (lerpSteps > 0) {
            setPos(getX() + (lerpX - getX()) / lerpSteps, getY() + (lerpY - getY()) / lerpSteps, getZ() + (lerpZ - getZ()) / lerpSteps);
            lerpSteps--;
        }
        if (!level().isClientSide) {
            boolean coupe = passager == null || passager.isShiftKeyDown();
            if (vie > 5 && (onGround() || isInWater() || isInLava() || coupe || vie > 20 * 120)) {
                ejectPassengers();
                discard();
            }
        }
    }

    /** Déplier le parachute sous le joueur et l'y accrocher. */
    public static void deployer(Player joueur) {
        ParachuteEntity parachute = new ParachuteEntity(fr.cabane.CabaneEntities.PARACHUTE.get(), joueur.level());
        parachute.moveTo(joueur.getX(), joueur.getY(), joueur.getZ(), joueur.getYRot(), 0F);
        joueur.level().addFreshEntity(parachute);
        joueur.resetFallDistance();
        joueur.startRiding(parachute, true);
    }

    /** Le parachute plié dans l'inventaire (pour savoir s'il en reste). */
    public static boolean aUnParachute(Player joueur) {
        return joueur.getInventory().hasAnyMatching(s -> s.is(CabaneItems.PARACHUTE.get()));
    }
}
