package fr.cabane.lames;

import java.util.UUID;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * La grande lame de chakra de Hiramekarei : elle se dresse au-dessus du joueur, s'abat devant lui (au 6e tick, c'est
 * là qu'elle frappe : de 4 à 8 blocs de long et de 6 à 12 dégâts selon la charge, 3 de large), puis s'efface. Le client la dessine
 * ({@code ClientLames}).
 */
public class LameChakra extends Entity {
    private static final EntityDataAccessor<Float> CHARGE = SynchedEntityData.defineId(LameChakra.class, EntityDataSerializers.FLOAT);
    public static final int FRAPPE = 6, VIE = 18;
    private UUID proprio;

    public LameChakra(EntityType<? extends LameChakra> type, Level level) {
        super(type, level);
        noPhysics = true;
    }

    public LameChakra(Level level, ServerPlayer joueur, float charge) {
        this(Lames.CHAKRA.get(), level);
        proprio = joueur.getUUID();
        setPos(joueur.getX(), joueur.getY() + 1.3, joueur.getZ());
        setYRot(joueur.getYRot());
        entityData.set(CHARGE, charge);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder b) {
        b.define(CHARGE, 1F);
    }

    public float charge() {
        return entityData.get(CHARGE);
    }

    /** Sa longueur en blocs. */
    public float longueur() {
        return 4 + 4 * charge();
    }

    @Override
    public void tick() {
        super.tick();
        if (level().isClientSide) {
            return;
        }
        if (tickCount > VIE) {
            discard();
            return;
        }
        if (tickCount != FRAPPE) {
            return;
        }
        ServerLevel monde = (ServerLevel) level();
        ServerPlayer p = proprio == null ? null : monde.getServer().getPlayerList().getPlayer(proprio);
        Vec3 dir = Vec3.directionFromRotation(0, getYRot());
        float lg = longueur();
        for (int k = 1; k <= lg * 2; k++) {
            Vec3 q = position().add(dir.scale(k * 0.5)).add(0, -1.2, 0);
            monde.sendParticles(ParticleTypes.GLOW, q.x, q.y + 0.2, q.z, 3, 0.4, 0.1, 0.4, 0.02);
            if (k % 3 == 0) {
                monde.sendParticles(ParticleTypes.SWEEP_ATTACK, q.x, q.y + 0.4, q.z, 1, 0, 0, 0, 0);
            }
        }
        monde.playSound(null, getX(), getY(), getZ(), SoundEvents.GENERIC_EXPLODE.value(), SoundSource.PLAYERS, 0.8F, 1.4F);
        for (LivingEntity e : monde.getEntitiesOfClass(LivingEntity.class, getBoundingBox().inflate(lg + 1, 3, lg + 1))) {
            if (e == p || !e.isAlive()) {
                continue;
            }
            Vec3 rel = e.position().subtract(position());
            double long_ = rel.x * dir.x + rel.z * dir.z, travers = Math.abs(rel.x * dir.z - rel.z * dir.x);
            if (long_ > 0.3 && long_ < lg + 0.5 && travers < 1.6 && rel.y > -2.5 && rel.y < 2) {
                e.hurt(p != null ? monde.damageSources().playerAttack(p) : monde.damageSources().magic(), (6 + 6 * charge()) * (p instanceof net.minecraft.server.level.ServerPlayer sp ? Zones.force(sp) : 1));
                e.setDeltaMovement(dir.x * 0.8, 0.25, dir.z * 0.8);
                e.hurtMarked = true;
            }
        }
    }

    @Override
    public boolean shouldBeSaved() {
        return false;
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
    }
}
