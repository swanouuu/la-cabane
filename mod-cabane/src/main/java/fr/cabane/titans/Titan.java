package fr.cabane.titans;

import fr.cabane.hub.HubData;
import java.util.EnumSet;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Un titan : un géant maigre de 13 à 16 blocs, qui marche sur les joueurs, lève le bras et l'abat devant lui (lentement,
 * on a le temps de voir venir). Beaucoup de vie, des coups pas trop forts. Son point faible : la nuque. Un coup porté de
 * derrière ou d'au-dessus, ou une flèche qui l'atteint là, fait cinq fois plus mal. Mort, il tombe sur le côté et part en
 * vapeur. Il ne casse rien.
 */
public class Titan extends Monster {
    /** Le dos voûté (en radians), le même que dans outils/titans.mjs. */
    private static final double VOUTE = 0.13;

    /**
     * Les cinq titans du rendu (docs/titans) : leurs tailles en pixels de titan (1/4 de bloc, les mêmes que
     * outils/titans.mjs), leur vie, leurs coups (en demi-cœurs) et leur vitesse.
     */
    public enum Sorte {
        BARBU("barbu", "Le Barbu", ChatFormatting.GOLD, 26, 18, 12, 10, 550, 5, 0.25),
        BIGLEUX("bigleux", "Le Bigleux", ChatFormatting.YELLOW, 30, 17, 12, 9, 450, 4, 0.31),
        SOURIANT("souriant", "Le Souriant", ChatFormatting.RED, 28, 18, 12, 10, 500, 5, 0.27),
        MACHOIRE("machoire", "La Mâchoire", ChatFormatting.DARK_RED, 32, 20, 10, 11, 700, 6, 0.24),
        CHAUVE("chauve", "Le Chauve", ChatFormatting.LIGHT_PURPLE, 24, 16, 12, 9, 400, 4, 0.31);

        public final String id;
        public final String nom;
        final ChatFormatting couleur;
        final int jambe;
        final int tronc;
        final int tete;
        final int largeur;
        final double vie;
        final double coups;
        final double vitesse;

        Sorte(String id, String nom, ChatFormatting couleur, int jambe, int tronc, int tete, int largeur, double vie, double coups, double vitesse) {
            this.id = id;
            this.nom = nom;
            this.couleur = couleur;
            this.jambe = jambe;
            this.tronc = tronc;
            this.tete = tete;
            this.largeur = largeur;
            this.vie = vie;
            this.coups = coups;
            this.vitesse = vitesse;
        }

        /** Le haut de la tête, en blocs. */
        public float hauteur() {
            return (float) (jambe + 4 + (tronc - 4 + 2.6 + tete) * Math.cos(VOUTE)) / 4F;
        }

        /** Le milieu de la nuque, en blocs au-dessus des pieds. */
        public float nuque() {
            return (float) (jambe + 4 + (tronc - 4 + 1.6) * Math.cos(VOUTE)) / 4F;
        }

        float largeurBoite() {
            return (largeur + 2) / 4F;
        }

        public static Sorte de(int i) {
            Sorte[] s = values();
            return s[Math.floorMod(i, s.length)];
        }
    }

    private static final EntityDataAccessor<Integer> SORTE = SynchedEntityData.defineId(Titan.class, EntityDataSerializers.INT);
    /** Le tick (tickCount) où il a levé le bras pour frapper : le dessin en tire le geste. */
    private static final EntityDataAccessor<Integer> COUP = SynchedEntityData.defineId(Titan.class, EntityDataSerializers.INT);
    /** La durée d'un coup, en ticks : le bras monte, puis s'abat (le coup porte à IMPACT). */
    public static final int DUREE_COUP = 22;
    private static final int IMPACT = 15;
    private static final int DUREE_MORT = 60;

    private int bloque;

    public Titan(EntityType<? extends Titan> type, Level level) {
        super(type, level);
        setPersistenceRequired();
        xpReward = 120;
    }

    public static AttributeSupplier.Builder attributs() {
        return Monster.createMonsterAttributes().add(Attributes.MAX_HEALTH, 500).add(Attributes.ATTACK_DAMAGE, 5).add(Attributes.ARMOR, 4)
                .add(Attributes.MOVEMENT_SPEED, 0.27).add(Attributes.FOLLOW_RANGE, 96).add(Attributes.KNOCKBACK_RESISTANCE, 1)
                .add(Attributes.STEP_HEIGHT, 3.1).add(Attributes.SAFE_FALL_DISTANCE, 64);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(SORTE, Sorte.SOURIANT.ordinal());
        builder.define(COUP, -1000);
    }

    /** Ce titan devient celui-là : son nom, sa taille, sa vie, ses coups, son pas. */
    public void preparer(Sorte s) {
        entityData.set(SORTE, s.ordinal());
        setCustomName(Component.literal(s.nom).withStyle(s.couleur, ChatFormatting.BOLD));
        setCustomNameVisible(true);
        getAttribute(Attributes.MAX_HEALTH).setBaseValue(s.vie);
        getAttribute(Attributes.ATTACK_DAMAGE).setBaseValue(s.coups);
        getAttribute(Attributes.MOVEMENT_SPEED).setBaseValue(s.vitesse);
        setHealth(getMaxHealth());
        refreshDimensions();
    }

    public Sorte sorte() {
        return Sorte.de(entityData.get(SORTE));
    }

    /** Où en est son coup (0 à 1), ou 0 s'il ne frappe pas. */
    public float coup(float partiel) {
        float k = (tickCount + partiel - entityData.get(COUP)) / DUREE_COUP;
        return k > 0 && k < 1 ? k : 0;
    }

    @Override
    public void onSyncedDataUpdated(EntityDataAccessor<?> cle) {
        super.onSyncedDataUpdated(cle);
        if (SORTE.equals(cle)) {
            refreshDimensions();
        }
    }

    @Override
    protected EntityDimensions getDefaultDimensions(Pose pose) {
        Sorte s = sorte();
        return EntityDimensions.scalable(s.largeurBoite(), s.hauteur()).withEyeHeight(s.hauteur() - 0.9F);
    }

    // ---------------------------------------------------------------- ce qu'il fait

    @Override
    protected void registerGoals() {
        goalSelector.addGoal(0, new FloatGoal(this));
        goalSelector.addGoal(1, new Attaque());
        goalSelector.addGoal(3, new VersLeVillage());
        goalSelector.addGoal(6, new LookAtPlayerGoal(this, Player.class, 40F));
        goalSelector.addGoal(7, new RandomLookAroundGoal(this));
        targetSelector.addGoal(1, new HurtByTargetGoal(this));
        targetSelector.addGoal(2, new NearestAttackableTargetGoal<>(this, Player.class, false, false));
    }

    /** Il fonce sur sa cible à grandes enjambées (sans chercher de chemin : il enjambe), puis lève le bras et l'abat. */
    private final class Attaque extends Goal {
        private int recharge;
        private int frappe;

        Attaque() {
            setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
        }

        @Override
        public boolean canUse() {
            LivingEntity c = getTarget();
            return c != null && c.isAlive();
        }

        @Override
        public boolean canContinueToUse() {
            LivingEntity c = getTarget();
            return c != null && c.isAlive() && !(c instanceof Player p && (p.isSpectator() || p.isCreative()));
        }

        @Override
        public boolean requiresUpdateEveryTick() {
            return true;
        }

        @Override
        public void stop() {
            frappe = 0;
        }

        private double portee(LivingEntity c) {
            return getBbWidth() / 2 + 3.4 + c.getBbWidth() / 2;
        }

        @Override
        public void tick() {
            LivingEntity c = getTarget();
            if (c == null) {
                return;
            }
            getLookControl().setLookAt(c, 30F, 40F);
            double d = Math.hypot(c.getX() - getX(), c.getZ() - getZ());
            double portee = portee(c);
            if (frappe > 0) {
                // le bras est en l'air : il ne bouge plus, il vise
                getMoveControl().setWantedPosition(getX(), getY(), getZ(), 0);
                if (--frappe == 0) {
                    impact(c, portee);
                }
            } else if (d > portee - 1) {
                getMoveControl().setWantedPosition(c.getX(), c.getY(), c.getZ(), 1.0);
            } else {
                getMoveControl().setWantedPosition(getX(), getY(), getZ(), 0);
            }
            if (recharge > 0) {
                recharge--;
            } else if (frappe == 0 && d <= portee && c.getY() < getY() + getBbHeight() && c.getY() > getY() - 4) {
                entityData.set(COUP, tickCount);
                frappe = IMPACT;
                recharge = 50;
                playSound(SoundEvents.RAVAGER_ATTACK, 2F, 0.55F);
            }
        }

        /** Le bras s'abat devant lui : la cible et ceux qui sont tout près du point d'impact prennent le coup. */
        private void impact(LivingEntity c, double portee) {
            if (!(level() instanceof ServerLevel monde)) {
                return;
            }
            Vec3 avant = Vec3.directionFromRotation(0, yBodyRot);
            Vec3 point = position().add(avant.scale(Math.min(portee - 0.6, Math.max(2, Math.hypot(c.getX() - getX(), c.getZ() - getZ())))));
            monde.sendParticles(ParticleTypes.EXPLOSION, point.x, point.y + 0.3, point.z, 3, 0.8, 0.2, 0.8, 0);
            monde.sendParticles(ParticleTypes.CAMPFIRE_COSY_SMOKE, point.x, point.y + 0.2, point.z, 12, 1.2, 0.1, 1.2, 0.02);
            monde.playSound(null, point.x, point.y, point.z, SoundEvents.GENERIC_EXPLODE.value(), SoundSource.HOSTILE, 1.2F, 0.6F);
            List<LivingEntity> touches = monde.getEntitiesOfClass(LivingEntity.class, new AABB(point, point).inflate(2.4, 3, 2.4),
                    e -> e != Titan.this && !(e instanceof Titan) && e.isAlive());
            if (c.isAlive() && !touches.contains(c) && Math.hypot(c.getX() - getX(), c.getZ() - getZ()) <= portee + 1
                    && c.getY() < getY() + getBbHeight() && c.getY() > getY() - 4) {
                touches.add(c);
            }
            for (LivingEntity e : touches) {
                if (e instanceof Player p && (p.isCreative() || p.isSpectator())) {
                    continue;
                }
                if (doHurtTarget(e)) {
                    e.push(avant.x * 0.9, 0.45, avant.z * 0.9);
                    e.hurtMarked = true;
                }
            }
        }
    }

    /** Sans cible : il marche vers la place du village, puis traîne dans les rues. */
    private final class VersLeVillage extends Goal {
        private Vec3 but;
        private int duree;

        VersLeVillage() {
            setFlags(EnumSet.of(Flag.MOVE));
        }

        @Override
        public boolean canUse() {
            return getTarget() == null && !level().isClientSide;
        }

        @Override
        public void start() {
            duree = 0;
            but = null;
        }

        @Override
        public void tick() {
            if (but == null || --duree <= 0 || position().distanceToSqr(but) < 9) {
                BlockPos centre = HubData.get(getServer()).centre();
                Vec3 c = centre == null ? position() : Vec3.atBottomCenterOf(centre);
                double r = position().distanceTo(c) > 30 ? 6 : 28;
                double a = random.nextDouble() * Math.PI * 2;
                but = c.add(Math.cos(a) * r * random.nextDouble(), 1, Math.sin(a) * r * random.nextDouble());
                duree = 300;
            }
            getMoveControl().setWantedPosition(but.x, but.y, but.z, 0.8);
        }
    }

    @Override
    public void aiStep() {
        super.aiStep();
        if (level().isClientSide || !isAlive()) {
            return;
        }
        // bloqué contre un mur trop haut pour l'enjamber : il saute par-dessus
        boolean veut = getMoveControl().hasWanted() && getDeltaMovement().horizontalDistanceSqr() < 0.0025;
        bloque = horizontalCollision && onGround() && veut ? bloque + 1 : 0;
        if (bloque > 30) {
            bloque = 0;
            Vec3 avant = Vec3.directionFromRotation(0, getYRot());
            setDeltaMovement(avant.x * 0.35, 0.95, avant.z * 0.35);
            hasImpulse = true;
        }
        // il marche lourdement : un grondement à chaque pas
        if (onGround() && getDeltaMovement().horizontalDistanceSqr() > 0.004 && tickCount % 11 == 0) {
            playSound(SoundEvents.RAVAGER_STEP, 1.6F, 0.45F + random.nextFloat() * 0.1F);
        }
        // une petite régénération, comme tous les titans (la vapeur qui sort de ses plaies)
        if (tickCount % 60 == 0 && getHealth() < getMaxHealth() && level() instanceof ServerLevel monde) {
            heal(3);
            monde.sendParticles(ParticleTypes.CLOUD, getX(), getY() + getBbHeight() * 0.6, getZ(), 6, 0.6, 1.5, 0.6, 0.01);
        }
    }

    // ---------------------------------------------------------------- la nuque

    /** La nuque, dans le monde : un peu en arrière du cou, juste sous le crâne. */
    public AABB nuque() {
        Vec3 avant = Vec3.directionFromRotation(0, yBodyRot);
        Vec3 c = position().add(avant.scale(-0.15)).add(0, sorte().nuque(), 0);
        return AABB.ofSize(c, 1.9, 1.8, 1.8);
    }

    /** Le coup a-t-il touché la nuque ? Une flèche (ou tout projectile) qui y arrive, ou un coup porté de derrière ou d'au-dessus. */
    private boolean nuqueTouchee(DamageSource source) {
        AABB n = nuque();
        Entity direct = source.getDirectEntity();
        if (direct instanceof Projectile) {
            Vec3 p = direct.position();
            return n.inflate(0.4).contains(p) || n.inflate(0.4).clip(p.subtract(direct.getDeltaMovement()), p.add(direct.getDeltaMovement())).isPresent();
        }
        if (source.getEntity() instanceof Player joueur && direct == joueur) {
            Vec3 oeil = joueur.getEyePosition();
            if (!n.contains(oeil) && n.clip(oeil, oeil.add(joueur.getLookAngle().scale(8))).isEmpty()) {
                return false;
            }
            Vec3 avant = Vec3.directionFromRotation(0, yBodyRot);
            Vec3 centre = n.getCenter();
            return oeil.subtract(centre).dot(avant) < 0.25 || oeil.y > centre.y + 0.7;
        }
        return false;
    }

    @Override
    public boolean hurt(DamageSource source, float montant) {
        if (!level().isClientSide && isAlive() && montant > 0 && nuqueTouchee(source)) {
            montant *= 5;
            if (level() instanceof ServerLevel monde) {
                Vec3 c = nuque().getCenter();
                monde.sendParticles(ParticleTypes.CRIT, c.x, c.y, c.z, 30, 0.5, 0.5, 0.5, 0.4);
                monde.sendParticles(ParticleTypes.DAMAGE_INDICATOR, c.x, c.y, c.z, 8, 0.4, 0.4, 0.4, 0.2);
                monde.playSound(null, c.x, c.y, c.z, SoundEvents.PLAYER_ATTACK_CRIT, SoundSource.PLAYERS, 2F, 0.6F);
            }
            if (source.getEntity() instanceof ServerPlayer joueur) {
                joueur.displayClientMessage(Component.literal("LA NUQUE ! × 5").withStyle(ChatFormatting.RED, ChatFormatting.BOLD), true);
            }
        }
        return super.hurt(source, montant);
    }

    // ---------------------------------------------------------------- la mort : il tombe, et part en vapeur

    @Override
    protected void tickDeath() {
        deathTime++;
        if (level().isClientSide) {
            int n = deathTime > 18 ? 8 : 2;
            float h = getBbHeight();
            for (int i = 0; i < n; i++) {
                double x = getX() + (random.nextDouble() - 0.5) * h * 0.6;
                double z = getZ() + (random.nextDouble() - 0.5) * h * 0.6;
                level().addParticle(i % 2 == 0 ? ParticleTypes.CLOUD : ParticleTypes.POOF, x, getY() + random.nextDouble() * (deathTime > 18 ? 2.5 : h), z,
                        0, 0.06 + random.nextDouble() * 0.05, 0);
            }
        } else if (deathTime == 20) {
            playSound(SoundEvents.GENERIC_EXPLODE.value(), 2F, 0.4F);
        } else if (deathTime >= DUREE_MORT && !isRemoved()) {
            level().broadcastEntityEvent(this, (byte) 60);
            remove(RemovalReason.KILLED);
        }
    }

    /** De 0 (debout) à 1 (à terre), pour le dessin. */
    public float chute(float partiel) {
        return deathTime <= 0 ? 0 : Mth.clamp((deathTime + partiel - 1) / 22F, 0, 1);
    }

    @Override
    protected SoundEvent getAmbientSound() {
        return SoundEvents.RAVAGER_AMBIENT;
    }

    @Override
    public float getVoicePitch() {
        return 0.45F + random.nextFloat() * 0.1F;
    }

    @Override
    protected SoundEvent getHurtSound(DamageSource source) {
        return SoundEvents.RAVAGER_HURT;
    }

    @Override
    protected SoundEvent getDeathSound() {
        return SoundEvents.RAVAGER_DEATH;
    }

    @Override
    protected float getSoundVolume() {
        return 2F;
    }

    // ---------------------------------------------------------------- un géant : pas de chute, pas d'étouffement, jamais effacé

    @Override
    public boolean causeFallDamage(float distance, float multiplicateur, DamageSource source) {
        return false;
    }

    @Override
    public boolean isInWall() {
        return false;
    }

    @Override
    public boolean removeWhenFarAway(double distance) {
        return false;
    }

    @Override
    protected boolean shouldDespawnInPeaceful() {
        return false;
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putInt("Sorte", entityData.get(SORTE));
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        entityData.set(SORTE, tag.getInt("Sorte"));
        refreshDimensions();
    }
}
