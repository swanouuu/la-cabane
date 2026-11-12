package fr.cabane.donjon;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;

/**
 * Les sbires du donjon : les gobelins (petits, rapides, une massue d'os, en bandes) et les brutes (deux fois et demie
 * plus grandes, casquées, un marteau de fer, lentes mais elles frappent fort). Ils peuplent les salles avant l'arène,
 * et Ugrak les appelle pendant le combat. Ils ne disparaissent pas d'eux-mêmes.
 */
public class Sbire extends Monster {
    public Sbire(EntityType<? extends Sbire> type, Level level) {
        super(type, level);
        setPersistenceRequired();
        xpReward = brute() ? 25 : 8;
    }

    public boolean brute() {
        return getType() == Donjon.BRUTE.get();
    }

    public static AttributeSupplier.Builder attributsGobelin() {
        return Monster.createMonsterAttributes().add(Attributes.MAX_HEALTH, 34).add(Attributes.ATTACK_DAMAGE, 5).add(Attributes.ARMOR, 4)
                .add(Attributes.MOVEMENT_SPEED, 0.34).add(Attributes.FOLLOW_RANGE, 40);
    }

    public static AttributeSupplier.Builder attributsBrute() {
        return Monster.createMonsterAttributes().add(Attributes.MAX_HEALTH, 110).add(Attributes.ATTACK_DAMAGE, 11).add(Attributes.ARMOR, 10)
                .add(Attributes.MOVEMENT_SPEED, 0.25).add(Attributes.FOLLOW_RANGE, 40).add(Attributes.KNOCKBACK_RESISTANCE, 0.7)
                .add(Attributes.STEP_HEIGHT, 1.1);
    }

    @Override
    protected void registerGoals() {
        goalSelector.addGoal(0, new FloatGoal(this));
        goalSelector.addGoal(2, new MeleeAttackGoal(this, brute() ? 1.0 : 1.25, true));
        goalSelector.addGoal(6, new WaterAvoidingRandomStrollGoal(this, 0.8));
        goalSelector.addGoal(7, new LookAtPlayerGoal(this, Player.class, 12));
        goalSelector.addGoal(8, new RandomLookAroundGoal(this));
        targetSelector.addGoal(1, new HurtByTargetGoal(this));
        targetSelector.addGoal(2, new NearestAttackableTargetGoal<>(this, Player.class, false));
    }

    @Override
    public boolean removeWhenFarAway(double distance) {
        return false;
    }

    /** Le donjon marche aussi en Paisible : ses monstres y restent. */
    @Override
    protected boolean shouldDespawnInPeaceful() {
        return false;
    }

    @Override
    protected SoundEvent getAmbientSound() {
        return brute() ? SoundEvents.RAVAGER_AMBIENT : SoundEvents.PIGLIN_AMBIENT;
    }

    @Override
    protected SoundEvent getHurtSound(DamageSource source) {
        return brute() ? SoundEvents.RAVAGER_HURT : SoundEvents.PIGLIN_HURT;
    }

    @Override
    protected SoundEvent getDeathSound() {
        return brute() ? SoundEvents.RAVAGER_DEATH : SoundEvents.PIGLIN_DEATH;
    }

    @Override
    public float getVoicePitch() {
        return brute() ? 0.7F : 1.5F + random.nextFloat() * 0.2F;
    }

    /** Ses coups ne baissent pas avec la difficulté non plus. */
    @Override
    public boolean doHurtTarget(net.minecraft.world.entity.Entity cible) {
        float degats = (float) getAttributeValue(Attributes.ATTACK_DAMAGE);
        boolean touche = cible.hurt(Ogre.coup(this), degats);
        if (touche) {
            swing(net.minecraft.world.InteractionHand.MAIN_HAND);
            if (brute() && cible instanceof net.minecraft.world.entity.LivingEntity l) {
                l.knockback(0.8, getX() - cible.getX(), getZ() - cible.getZ());
            }
        }
        return touche;
    }

    /** Il sort du sol : un nuage de fumée et des étincelles de lave. */
    public void surgir(ServerLevel sl) {
        sl.sendParticles(ParticleTypes.LARGE_SMOKE, getX(), getY() + 0.4, getZ(), 14, 0.4, 0.3, 0.4, 0.02);
        sl.sendParticles(ParticleTypes.LAVA, getX(), getY() + 0.3, getZ(), 6, 0.3, 0.1, 0.3, 0.1);
    }
}
