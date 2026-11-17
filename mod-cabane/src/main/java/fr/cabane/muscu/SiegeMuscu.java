package fr.cabane.muscu;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * La place invisible du joueur sur une machine : elle le tient à la bonne hauteur (debout sur le tapis, assis sur le
 * banc, couché sur le développé couché) et tourné dans le sens de la machine. Elle disparaît quand il descend.
 */
public class SiegeMuscu extends Entity {
    private static final EntityDataAccessor<BlockPos> MACHINE = SynchedEntityData.defineId(SiegeMuscu.class, EntityDataSerializers.BLOCK_POS);
    private int vide;

    public SiegeMuscu(EntityType<? extends SiegeMuscu> type, Level level) {
        super(type, level);
        noPhysics = true;
        setNoGravity(true);
    }

    static SiegeMuscu creer(Level level, BlockPos pos) {
        SiegeMuscu s = new SiegeMuscu(Muscu.SIEGE.get(), level);
        s.entityData.set(MACHINE, pos.immutable());
        s.setPos(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
        return s;
    }

    public BlockPos machinePos() {
        return entityData.get(MACHINE);
    }

    /** La machine sous ce siège et sa direction, ou null si elle a été cassée. */
    public Machine machine() {
        BlockState etat = level().getBlockState(machinePos());
        return etat.getBlock() instanceof MachineBlock b ? b.machine : null;
    }

    public Direction face() {
        BlockState etat = level().getBlockState(machinePos());
        return etat.hasProperty(HorizontalDirectionalBlock.FACING) ? etat.getValue(HorizontalDirectionalBlock.FACING) : Direction.NORTH;
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(MACHINE, BlockPos.ZERO);
    }

    @Override
    public void tick() {
        super.tick();
        if (level().isClientSide) {
            return;
        }
        if (machine() == null) {
            ejectPassengers();
            discard();
            return;
        }
        if (!isVehicle()) {
            if (++vide > 2) {
                discard();
            }
        } else {
            vide = 0;
        }
    }

    @Override
    protected void positionRider(Entity passager, MoveFunction bouger) {
        if (!hasPassenger(passager)) {
            return;
        }
        Machine m = machine();
        if (m == null) {
            return;
        }
        Direction face = face();
        BlockPos p = machinePos();
        Vec3 h = Machine.versMonde(face, m.hanche.x, m.hanche.y - Poses.HANCHE, m.hanche.z);
        bouger.accept(passager, p.getX() + 0.5 + h.x, p.getY() + h.y, p.getZ() + 0.5 + h.z);
        if (passager instanceof LivingEntity l) {
            l.setYBodyRot(Machine.lacet(face));
        }
        passager.fallDistance = 0;
    }

    @Override
    protected void removePassenger(Entity passager) {
        super.removePassenger(passager);
        if (passager instanceof ServerPlayer j) {
            Muscu.annuler(j);
        }
    }

    @Override
    public Vec3 getDismountLocationForPassenger(LivingEntity passager) {
        // on descend à côté de la machine, du côté gauche
        Direction face = face();
        Vec3 c = Machine.versMonde(face, 1.0, 0, 0);
        BlockPos p = machinePos();
        return new Vec3(p.getX() + 0.5 + c.x, p.getY(), p.getZ() + 0.5 + c.z);
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
    public boolean shouldRiderSit() {
        return true;
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
    }

    /** Pas sauvegardé : un siège vide n'a rien à faire dans le monde au prochain chargement. */
    @Override
    public boolean shouldBeSaved() {
        return false;
    }
}
