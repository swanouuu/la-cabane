package fr.cabane.vegeta;

import fr.cabane.CabaneEntities;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.AbstractHurtingProjectile;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** Une boule d'énergie : elle file droit, explose à l'impact (sans abîmer les blocs) et brûle un peu ceux qu'elle touche. */
public class KiBlast extends AbstractHurtingProjectile {
    /** 0 : jaune (Vegeta), 1 : bleue (Goku). */
    private static final EntityDataAccessor<Integer> COULEUR = SynchedEntityData.defineId(KiBlast.class, EntityDataSerializers.INT);
    private float degats = 6;

    public KiBlast(EntityType<? extends KiBlast> type, Level level) {
        super(type, level);
    }

    public KiBlast(Level level, LivingEntity tireur, Vec3 direction, int couleur, float degats) {
        super(CabaneEntities.KI_BLAST.get(), tireur, direction.normalize(), level);
        entityData.set(COULEUR, couleur);
        this.degats = degats;
        setDeltaMovement(direction.normalize().scale(1.1));
        accelerationPower = 0.02;
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(COULEUR, 0);
    }

    public int couleur() {
        return entityData.get(COULEUR);
    }

    @Override
    protected boolean shouldBurn() {
        return false;
    }

    @Override
    protected ParticleOptions getTrailParticle() {
        return ParticleTypes.END_ROD;
    }

    @Override
    protected boolean canHitEntity(Entity cible) {
        // les boules ne touchent pas leur camp : celles du boss ne blessent pas le boss, celles de l'allié ne visent que le boss
        if (getOwner() instanceof CombattantZ tireur && cible instanceof CombattantZ autre) {
            return tireur.allie() != autre.allie();
        }
        if (getOwner() instanceof CombattantZ tireur && tireur.allie()) {
            return false;
        }
        return super.canHitEntity(cible);
    }

    @Override
    protected void onHitEntity(EntityHitResult resultat) {
        super.onHitEntity(resultat);
        if (level() instanceof ServerLevel) {
            Entity cible = resultat.getEntity();
            Entity tireur = getOwner();
            cible.hurt(damageSources().mobProjectile(this, tireur instanceof LivingEntity l ? l : null), degats);
            cible.push(getDeltaMovement().x * 0.4, 0.25, getDeltaMovement().z * 0.4);
        }
    }

    @Override
    protected void onHit(HitResult resultat) {
        super.onHit(resultat);
        if (level() instanceof ServerLevel serveur) {
            serveur.sendParticles(ParticleTypes.EXPLOSION, getX(), getY(), getZ(), 2, 0.3, 0.3, 0.3, 0);
            serveur.sendParticles(ParticleTypes.END_ROD, getX(), getY(), getZ(), 12, 0.2, 0.2, 0.2, 0.25);
            serveur.playSound(null, getX(), getY(), getZ(), SoundEvents.GENERIC_EXPLODE.value(), getSoundSource(), 0.6F, 1.4F);
            discard();
        }
    }

    @Override
    protected void onHitBlock(BlockHitResult resultat) {
        super.onHitBlock(resultat);
    }

    @Override
    public void tick() {
        super.tick();
        if (tickCount > 100 && !level().isClientSide) {
            discard();
        }
    }
}
