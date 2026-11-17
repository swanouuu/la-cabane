package fr.cabane.parc;

import java.util.List;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * Une auto-tamponneuse (grosse : 1,4 fois la taille de la maquette). Celui qui monte dedans conduit avec ses touches
 * pour avancer et tourner, comme un bateau : c'est son jeu qui la fait rouler, le serveur suit. Les autos vides se
 * baladent toutes seules sur la piste. Quand elles se cognent (entre elles ou contre la bordure), elles rebondissent,
 * la perche crache des étincelles et ça fait « bonk ».
 */
public class AutoParc extends Entity {
    private static final EntityDataAccessor<Byte> COULEUR = SynchedEntityData.defineId(AutoParc.class, EntityDataSerializers.BYTE);
    /** La piste (le centre et les demi-côtés, en blocs) : pour les autos qui roulent seules. */
    public static final double PISTE_X = -35, PISTE_Z = 80, PISTE_W = 18, PISTE_D = 12;
    private static final double RAYON = 1.45;

    private int lerpPas;
    private double lerpX, lerpY, lerpZ;
    private float lerpLacet;
    private float tourne;
    private double cibleX, cibleZ;
    /** Chez le joueur : le dernier choc (ticks restants), pour les étincelles. */
    public int choc;

    public AutoParc(EntityType<? extends AutoParc> type, Level level) {
        super(type, level);
        setNoGravity(true);
    }

    static AutoParc creer(Level level, int couleur, double x, double z, float lacet) {
        AutoParc a = new AutoParc(Parc.AUTO.get(), level);
        a.entityData.set(COULEUR, (byte) couleur);
        a.moveTo(x, Maneges.SOL, z, lacet, 0F);
        return a;
    }

    public int couleur() {
        return entityData.get(COULEUR);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(COULEUR, (byte) 0);
    }

    @Override
    public LivingEntity getControllingPassenger() {
        return getFirstPassenger() instanceof Player p ? p : null;
    }

    @Override
    public void tick() {
        super.tick();
        if (choc > 0) {
            choc--;
        }
        if (isControlledByLocalInstance()) {
            lerpPas = 0;
            syncPacketPositionCodec(getX(), getY(), getZ());
            conduire();
        } else {
            setDeltaMovement(Vec3.ZERO);
            if (lerpPas > 0) {
                lerpPositionAndRotationStep(lerpPas, lerpX, lerpY, lerpZ, lerpLacet, 0);
                lerpPas--;
            }
        }
    }

    /** Avancer et tourner : avec les touches du conducteur, ou tout seul vers un point au hasard de la piste. */
    private void conduire() {
        float avance, virage;
        if (getControllingPassenger() instanceof Player p) {
            avance = Mth.clamp(p.zza, -1F, 1F);
            virage = Mth.clamp(p.xxa, -1F, 1F);
        } else {
            double dx = cibleX - getX(), dz = cibleZ - getZ();
            if (dx * dx + dz * dz < 4 || random.nextFloat() < 0.008F || cibleX == 0 && cibleZ == 0) {
                cibleX = PISTE_X + (random.nextDouble() - 0.5) * 2 * (PISTE_W - 3);
                cibleZ = PISTE_Z + (random.nextDouble() - 0.5) * 2 * (PISTE_D - 3);
            }
            float voulu = (float) Math.toDegrees(Math.atan2(-dx, dz));
            virage = Mth.clamp(Mth.wrapDegrees(voulu - getYRot()) / 30F, -1F, 1F) * -1F;
            avance = 0.55F;
        }
        // en marche arrière, le volant est inversé (comme une vraie voiture)
        tourne = virage * 4.5F * (avance < -0.1F ? -1 : 1);
        setYRot(getYRot() - tourne);
        double a = Math.toRadians(getYRot());
        Vec3 dir = new Vec3(-Math.sin(a), 0, Math.cos(a));
        Vec3 v = getDeltaMovement().add(dir.scale(avance * (avance > 0 ? 0.045 : 0.025))).multiply(0.9, 0, 0.9);
        // les autres autos : on se repousse et on rebondit
        List<AutoParc> autres = level().getEntitiesOfClass(AutoParc.class, getBoundingBox().inflate(2.5), e -> e != this);
        for (AutoParc o : autres) {
            double dx = getX() - o.getX(), dz = getZ() - o.getZ(), d = Math.sqrt(dx * dx + dz * dz);
            if (d < RAYON * 2 && d > 1e-3) {
                double nx = dx / d, nz = dz / d, pen = RAYON * 2 - d;
                setPos(getX() + nx * pen * 0.5, getY(), getZ() + nz * pen * 0.5);
                double rel = v.x * nx + v.z * nz;
                if (rel < 0.05) {
                    v = v.add(nx * (0.28 - rel * 1.2), 0, nz * (0.28 - rel * 1.2));
                    bonk();
                }
            }
        }
        setDeltaMovement(v);
        Vec3 avant = position();
        move(MoverType.SELF, v);
        if (horizontalCollision) {
            Vec3 fait = position().subtract(avant);
            double rx = Math.abs(fait.x - v.x) > 1e-4 ? -v.x * 0.7 : v.x, rz = Math.abs(fait.z - v.z) > 1e-4 ? -v.z * 0.7 : v.z;
            setDeltaMovement(rx, 0, rz);
            if (v.horizontalDistanceSqr() > 0.01) {
                bonk();
            }
        }
    }

    /** Le choc : un bruit, et des étincelles en haut de la perche. */
    private void bonk() {
        if (choc > 0) {
            return;
        }
        choc = 8;
        double a = Math.toRadians(getYRot());
        double px = getX() + Math.sin(a) * 1.6, py = getY() + 8.0, pz = getZ() - Math.cos(a) * 1.6;
        if (level() instanceof ServerLevel s) {
            s.playSound(null, getX(), getY(), getZ(), SoundEvents.SHIELD_BLOCK, SoundSource.NEUTRAL, 0.9F, 0.7F + random.nextFloat() * 0.3F);
            s.sendParticles(ParticleTypes.ELECTRIC_SPARK, px, py, pz, 14, 0.2, 0.1, 0.2, 0.2);
        } else {
            level().playLocalSound(getX(), getY(), getZ(), SoundEvents.SHIELD_BLOCK, SoundSource.NEUTRAL, 0.9F, 0.7F + random.nextFloat() * 0.3F, false);
            for (int i = 0; i < 14; i++) {
                level().addParticle(ParticleTypes.ELECTRIC_SPARK, px, py, pz, (random.nextDouble() - 0.5) * 0.4, random.nextDouble() * 0.1, (random.nextDouble() - 0.5) * 0.4);
            }
        }
    }

    @Override
    public void lerpTo(double x, double y, double z, float yRot, float xRot, int pas) {
        lerpX = x;
        lerpY = y;
        lerpZ = z;
        lerpLacet = yRot;
        lerpPas = 3;
    }

    @Override
    public double lerpTargetX() {
        return lerpPas > 0 ? lerpX : getX();
    }

    @Override
    public double lerpTargetY() {
        return lerpPas > 0 ? lerpY : getY();
    }

    @Override
    public double lerpTargetZ() {
        return lerpPas > 0 ? lerpZ : getZ();
    }

    @Override
    public float lerpTargetYRot() {
        return lerpPas > 0 ? lerpLacet : getYRot();
    }

    @Override
    protected void positionRider(Entity passager, MoveFunction bouger) {
        if (!hasPassenger(passager)) {
            return;
        }
        double a = Math.toRadians(getYRot());
        // assis un peu en arrière du milieu
        double x = getX() + Math.sin(a) * 0.28, z = getZ() - Math.cos(a) * 0.28;
        bouger.accept(passager, x, getY() + 0.62, z);
        // la tête suit l'auto quand elle tourne
        passager.setYRot(passager.getYRot() - tourne);
        passager.setYHeadRot(passager.getYHeadRot() - tourne);
        if (passager instanceof LivingEntity l) {
            l.setYBodyRot(getYRot());
        }
    }

    @Override
    public void onPassengerTurned(Entity passager) {
        // on peut regarder sur les côtés, pas derrière
        float d = Mth.wrapDegrees(passager.getYRot() - getYRot());
        float c = Mth.clamp(d, -120F, 120F);
        passager.yRotO += c - d;
        passager.setYRot(passager.getYRot() + c - d);
        passager.setYHeadRot(passager.getYRot());
    }

    @Override
    protected boolean canAddPassenger(Entity passager) {
        return getPassengers().isEmpty();
    }

    @Override
    public InteractionResult interact(Player joueur, InteractionHand main) {
        if (joueur.isSecondaryUseActive() || joueur.isPassenger() || isVehicle()) {
            return InteractionResult.PASS;
        }
        if (!level().isClientSide) {
            joueur.startRiding(this);
        }
        return InteractionResult.sidedSuccess(level().isClientSide);
    }

    @Override
    public Vec3 getDismountLocationForPassenger(LivingEntity passager) {
        double a = Math.toRadians(getYRot());
        return new Vec3(getX() + Math.cos(a) * 2.2, getY() + 0.05, getZ() + Math.sin(a) * 2.2);
    }

    @Override
    public boolean hurt(DamageSource source, float degats) {
        return false;
    }

    @Override
    public boolean isPickable() {
        return !isRemoved();
    }

    @Override
    public boolean isPushable() {
        return false;
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
