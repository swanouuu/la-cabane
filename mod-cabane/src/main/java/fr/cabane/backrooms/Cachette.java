package fr.cabane.backrooms;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * La place dans un casier : une entité invisible posée au fond du casier, sur laquelle le joueur « monte » pour s'y
 * cacher. Il voit le couloir à travers les fentes (ClientBackrooms), peut tourner la tête mais pas se retourner, et
 * ressort devant le casier avec la touche pour descendre (celle du jeu, affichée par Minecraft).
 */
public class Cachette extends Entity {
    private static final EntityDataAccessor<Byte> SENS = SynchedEntityData.defineId(Cachette.class, EntityDataSerializers.BYTE);
    /** Jusqu'où l'on peut tourner la tête à partir du devant du casier, de chaque côté. */
    private static final float ANGLE = 55F;

    public Cachette(EntityType<? extends Cachette> type, Level level) {
        super(type, level);
        noPhysics = true;
        setNoGravity(true);
    }

    /** Une cachette dans le casier dont le bas est en {@code bas}, tournée vers {@code sens} (0 sud, 1 ouest, 2 nord, 3 est). */
    public static Cachette dans(Level level, BlockPos bas, int sens) {
        Cachette c = new Cachette(Backrooms.CACHETTE.get(), level);
        c.entityData.set(SENS, (byte) sens);
        float yaw = Labyrinthe.DIRECTIONS[sens].toYRot();
        c.moveTo(bas.getX() + 0.5, bas.getY(), bas.getZ() + 0.5, yaw, 0);
        return c;
    }

    public int sens() {
        return entityData.get(SENS);
    }

    public float lacet() {
        return Labyrinthe.DIRECTIONS[sens()].toYRot();
    }

    /** Le milieu de la case juste devant le casier, au sol. */
    public Vec3 devant() {
        int s = sens();
        return new Vec3(getX() + Labyrinthe.DX[s] * 0.95, getY(), getZ() + Labyrinthe.DZ[s] * 0.95);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(SENS, (byte) 0);
    }

    @Override
    public void tick() {
        super.tick();
        if (!level().isClientSide && (!isVehicle() || !(level().getBlockState(blockPosition()).getBlock() instanceof BlocsBackrooms.Casier))) {
            ejectPassengers();
            discard();
        }
    }

    @Override
    protected Vec3 getPassengerAttachmentPoint(Entity passager, EntityDimensions dimensions, float partiel) {
        return Vec3.ZERO;
    }

    @Override
    protected void addPassenger(Entity passager) {
        super.addPassenger(passager);
        passager.setYRot(lacet());
        passager.setYHeadRot(lacet());
        passager.yRotO = lacet();
        if (passager instanceof LivingEntity l) {
            l.setYBodyRot(lacet());
        }
        if (!level().isClientSide && passager instanceof ServerPlayer j) {
            // dans le casier, on ne dépasse pas : les autres ne voient pas qu'on est là
            j.setInvisible(true);
        }
    }

    @Override
    protected void removePassenger(Entity passager) {
        super.removePassenger(passager);
        if (!level().isClientSide && passager instanceof ServerPlayer j) {
            j.setInvisible(j.hasEffect(MobEffects.INVISIBILITY));
            level().playSound(null, getX(), getY() + 1, getZ(), Backrooms.SON_CASIER.get(), SoundSource.BLOCKS, 0.8F, 1.1F);
        }
    }

    @Override
    public Vec3 getDismountLocationForPassenger(LivingEntity passager) {
        return devant();
    }

    /** On peut regarder à travers les fentes, à gauche et à droite, mais pas derrière soi. */
    @Override
    public void onPassengerTurned(Entity passager) {
        float f = Mth.wrapDegrees(passager.getYRot() - lacet());
        float g = Mth.clamp(f, -ANGLE, ANGLE);
        passager.yRotO += g - f;
        passager.setYRot(passager.getYRot() + g - f);
        passager.setYHeadRot(passager.getYRot());
        if (passager instanceof LivingEntity l) {
            l.setYBodyRot(lacet());
        }
    }

    @Override
    public boolean shouldBeSaved() {
        return false;
    }

    @Override
    public boolean isPickable() {
        return false;
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
    }
}
