package fr.cabane.mission;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.animal.Wolf;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * Le chien du château : il flaire les ANBU à 6 blocs, même accroupis, même cachés (et les voit à 10). Alors il aboie, l'alarme
 * part, et il fonce mordre. Un coup dans le dos le met K.O. (il se couche) comme les gardes.
 */
public class MissionChien extends Wolf {
    String mission = "";
    Vec3 maison = Vec3.ZERO;
    private int ko, recharge, aboiement;
    private ServerPlayer cible;

    public MissionChien(EntityType<? extends Wolf> type, Level level) {
        super(type, level);
        setPersistenceRequired();
    }

    MissionChien preparer(String mission, Scene.Pnj p) {
        this.mission = mission;
        maison = p.p();
        moveTo(maison.x, maison.y, maison.z, p.lacet(), 0F);
        return this;
    }

    @Override
    protected void registerGoals() {
        goalSelector.addGoal(0, new FloatGoal(this));
    }

    @Override
    protected void customServerAiStep() {
        super.customServerAiStep();
        if (!(level() instanceof ServerLevel level)) {
            return;
        }
        if (recharge > 0) {
            recharge--;
        }
        if (aboiement > 0) {
            aboiement--;
        }
        if (ko > 0) {
            getNavigation().stop();
            if (--ko == 0) {
                setInSittingPose(false);
            }
            return;
        }
        Session s = Missions.session(mission);
        if (s == null || s.finie()) {
            flaner();
            return;
        }
        if (tickCount % 5 == 0) {
            cible = null;
            double mieux = Double.MAX_VALUE;
            for (ServerPlayer h : s.cibles(level)) {
                double d = distanceTo(h);
                boolean flaire = d < 6;
                boolean vu = d < 10 && Missions.voit(level, getEyePosition(), h, this) && !Missions.cache(level, h);
                if ((flaire || vu) && d < mieux) {
                    mieux = d;
                    cible = h;
                }
            }
            if (cible != null && aboiement == 0) {
                aboiement = 200;
                level.playSound(null, getX(), getY(), getZ(), SoundEvents.WOLF_GROWL, SoundSource.HOSTILE, 1.6F, 1F);
                level.playSound(null, getX(), getY(), getZ(), SoundEvents.WOLF_AMBIENT, SoundSource.HOSTILE, 2F, 0.8F);
                s.alarme(level, cible.position(), cible, false);
            }
        }
        if (cible == null || cible.isRemoved() || !s.estCible(cible)) {
            cible = null;
            flaner();
            return;
        }
        if (tickCount % 5 == 0 || getNavigation().isDone()) {
            getNavigation().moveTo(cible, 1.4);
        }
        getLookControl().setLookAt(cible, 30F, 30F);
        if (distanceToSqr(cible) < 2.4 && recharge <= 0) {
            swing(InteractionHand.MAIN_HAND);
            cible.hurt(Missions.coup(this), 2F);
            recharge = 30;
        }
        if (position().distanceTo(maison) > 30) {
            cible = null;
        }
    }

    private void flaner() {
        if (position().distanceTo(maison) > 6) {
            if (getNavigation().isDone()) {
                getNavigation().moveTo(maison.x, maison.y, maison.z, 1.0);
            }
        } else if (random.nextInt(120) == 0) {
            getNavigation().moveTo(maison.x + random.nextInt(9) - 4, maison.y, maison.z + random.nextInt(9) - 4, 0.8);
        }
    }

    @Override
    public boolean hurt(DamageSource source, float degats) {
        if (source.is(DamageTypeTags.BYPASSES_INVULNERABILITY)) {
            return super.hurt(source, degats);
        }
        if (level().isClientSide || source.is(DamageTypeTags.IS_FALL)) {
            return false;
        }
        if (ko > 0) {
            return false;
        }
        if (source.getEntity() instanceof Player j && source.getDirectEntity() == j) {
            Vec3 regard = Vec3.directionFromRotation(0, yBodyRot);
            double dx = j.getX() - getX(), dz = j.getZ() - getZ(), hd = Math.sqrt(dx * dx + dz * dz);
            if (hd > 0.01 && (regard.x * dx + regard.z * dz) / hd < -0.1 && cible == null) {
                assommer();
                return true;
            }
        }
        boolean r = super.hurt(source, degats);
        if (getHealth() <= 2) {
            setHealth(getMaxHealth());
            assommer();
        }
        return r;
    }

    private void assommer() {
        ko = MissionGarde.DUREE_KO;
        cible = null;
        setInSittingPose(true);
        playSound(SoundEvents.WOLF_WHINE, 1F, 1F);
    }

    @Override
    public void die(DamageSource source) {
        if (source.is(DamageTypeTags.BYPASSES_INVULNERABILITY)) {
            super.die(source);
            return;
        }
        setHealth(getMaxHealth());
        assommer();
    }

    @Override
    public InteractionResult mobInteract(Player joueur, InteractionHand main) {
        return InteractionResult.PASS;
    }

    @Override
    public boolean removeWhenFarAway(double distance) {
        return false;
    }

    @Override
    public boolean isPersistenceRequired() {
        return true;
    }

    @Override
    protected boolean shouldDropLoot() {
        return false;
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putString("Mission", mission);
        tag.putDouble("MX", maison.x);
        tag.putDouble("MY", maison.y);
        tag.putDouble("MZ", maison.z);
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        mission = tag.getString("Mission");
        maison = new Vec3(tag.getDouble("MX"), tag.getDouble("MY"), tag.getDouble("MZ"));
    }
}
