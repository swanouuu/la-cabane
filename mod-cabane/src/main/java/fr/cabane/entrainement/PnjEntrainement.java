package fr.cabane.entrainement;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * Un élève de la salle d'entraînement : un skin tiré au hasard, pas de nom au-dessus de la tête. On peut le taper pour
 * essayer ses techniques : il encaisse, recule, a mal, mais ne meurt jamais et reprend vite ses forces. Il fait quelques
 * pas autour de sa place (sauf celui qui est perché quelque part), regarde les joueurs, et revient à sa place si un coup
 * l'en a trop éloigné (dans l'eau, dans un trou…).
 */
public class PnjEntrainement extends PathfinderMob {
    /** Les skins possibles : ceux de base du jeu, et ceux des joueurs du serveur (les mannequins du commissariat). */
    static final String[] SKINS = {
        "minecraft:textures/entity/player/wide/steve.png", "minecraft:textures/entity/player/wide/alex.png",
        "minecraft:textures/entity/player/wide/ari.png", "minecraft:textures/entity/player/wide/efe.png",
        "minecraft:textures/entity/player/wide/kai.png", "minecraft:textures/entity/player/wide/makena.png",
        "minecraft:textures/entity/player/wide/noor.png", "minecraft:textures/entity/player/wide/sunny.png",
        "minecraft:textures/entity/player/wide/zuri.png", "cabane:textures/entity/pnj/cible_1.png",
        "cabane:textures/entity/pnj/cible_2.png", "cabane:textures/entity/pnj/cible_3.png",
    };
    private static final EntityDataAccessor<String> SKIN = SynchedEntityData.defineId(PnjEntrainement.class, EntityDataSerializers.STRING);

    private Vec3 maison = Vec3.ZERO;
    private float regardMaison;
    /** Il se promène autour de sa place (sinon il reste où il est : sur un îlot, un perchoir, une chaise…). */
    private boolean flaneur;
    private int loin;
    private int dernierCoup;

    public PnjEntrainement(EntityType<? extends PnjEntrainement> type, Level level) {
        super(type, level);
        setPersistenceRequired();
        setCanPickUpLoot(false);
    }

    public static AttributeSupplier.Builder attributs() {
        return PathfinderMob.createMobAttributes().add(Attributes.MAX_HEALTH, 40).add(Attributes.MOVEMENT_SPEED, 0.25)
                .add(Attributes.FOLLOW_RANGE, 12).add(Attributes.KNOCKBACK_RESISTANCE, 0.2);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(SKIN, SKINS[0]);
    }

    public String skin() {
        return entityData.get(SKIN);
    }

    /** Lui donne un skin au hasard, sa place et son regard de départ. */
    public PnjEntrainement preparer(Vec3 place, float regard, boolean flaneur) {
        entityData.set(SKIN, SKINS[random.nextInt(SKINS.length)]);
        this.maison = place;
        this.regardMaison = regard;
        this.flaneur = flaneur;
        moveTo(place.x, place.y, place.z, regard, 0F);
        setYHeadRot(regard);
        setYBodyRot(regard);
        restrictTo(BlockPos.containing(place), flaneur ? 3 : 0);
        return this;
    }

    @Override
    protected void registerGoals() {
        goalSelector.addGoal(0, new FloatGoal(this));
        goalSelector.addGoal(5, new WaterAvoidingRandomStrollGoal(this, 0.6, 0.02F) {
            @Override
            public boolean canUse() {
                return flaneur && super.canUse();
            }
        });
        goalSelector.addGoal(6, new LookAtPlayerGoal(this, Player.class, 8F));
        goalSelector.addGoal(7, new RandomLookAroundGoal(this));
    }

    // ------------------------------------------------------------------ encaisser sans jamais tomber

    @Override
    protected void actuallyHurt(DamageSource source, float degats) {
        super.actuallyHurt(source, degats);
        dernierCoup = tickCount;
        if (!source.is(DamageTypes.GENERIC_KILL) && getHealth() < 2F) {
            setHealth(getMaxHealth());
        }
    }

    @Override
    protected void customServerAiStep() {
        super.customServerAiStep();
        if (!(level() instanceof ServerLevel level)) {
            return;
        }
        // il reprend ses forces quand on le laisse tranquille
        if (tickCount - dernierCoup > 60 && getHealth() < getMaxHealth() && tickCount % 10 == 0) {
            heal(4F);
        }
        // trop loin de sa place (projeté, tombé dans l'eau ou dans un trou) : il y revient
        if (tickCount % 20 == 0) {
            double d = position().distanceTo(maison);
            loin = d > (flaneur ? 6 : 1.5) || getY() < maison.y - 1.5 ? loin + 1 : 0;
            if (loin >= 4 && maison != Vec3.ZERO) {
                level.sendParticles(ParticleTypes.POOF, getX(), getY() + 1, getZ(), 12, 0.3, 0.6, 0.3, 0.02);
                getNavigation().stop();
                setDeltaMovement(Vec3.ZERO);
                moveTo(maison.x, maison.y, maison.z, regardMaison, 0F);
                clearFire();
                fallDistance = 0;
                level.sendParticles(ParticleTypes.POOF, maison.x, maison.y + 1, maison.z, 12, 0.3, 0.6, 0.3, 0.02);
                loin = 0;
            }
        }
    }

    @Override
    protected SoundEvent getHurtSound(DamageSource source) {
        return SoundEvents.PLAYER_HURT;
    }

    @Override
    protected SoundEvent getDeathSound() {
        return SoundEvents.PLAYER_DEATH;
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    @Override
    protected void doPush(net.minecraft.world.entity.Entity entite) {
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
        tag.putString("Skin", skin());
        tag.putDouble("MaisonX", maison.x);
        tag.putDouble("MaisonY", maison.y);
        tag.putDouble("MaisonZ", maison.z);
        tag.putFloat("Regard", regardMaison);
        tag.putBoolean("Flaneur", flaneur);
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        if (tag.contains("Skin")) {
            entityData.set(SKIN, tag.getString("Skin"));
        }
        maison = new Vec3(tag.getDouble("MaisonX"), tag.getDouble("MaisonY"), tag.getDouble("MaisonZ"));
        regardMaison = tag.getFloat("Regard");
        flaneur = tag.getBoolean("Flaneur");
        restrictTo(BlockPos.containing(maison), flaneur ? 3 : 0);
    }
}
