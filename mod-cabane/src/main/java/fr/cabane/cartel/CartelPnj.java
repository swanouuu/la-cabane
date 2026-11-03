package fr.cabane.cartel;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * Un gars du cartel : son skin (patron, sicario, narco, carioca, garde, reina, instructeur), sa pose (au repos,
 * l'arme en travers, en joue, les bras croisés) et son arme en main. Il est invincible, ne bouge presque pas, regarde
 * les joueurs qui passent, et n'attaque jamais personne. Au stand de tir, il a une cible : il lâche des rafales dessus
 * (vraies traçantes, vrais bruits, aucun dégât).
 */
public class CartelPnj extends PathfinderMob {
    public static final int REPOS = 0;
    public static final int PORTE = 1;
    public static final int VISE = 2;
    public static final int CROISE = 3;

    private static final EntityDataAccessor<String> SKIN = SynchedEntityData.defineId(CartelPnj.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<Byte> POSE = SynchedEntityData.defineId(CartelPnj.class, EntityDataSerializers.BYTE);

    /** La cible du stand de tir (null : il ne tire pas). */
    private Vec3 cible;
    /** Il se promène un peu autour de sa place. */
    private boolean flaneur;
    private int prochaineRafale = 40;
    private int ballesRestantes;

    public CartelPnj(EntityType<? extends CartelPnj> type, Level level) {
        super(type, level);
        setPersistenceRequired();
        setInvulnerable(true);
        setCanPickUpLoot(false);
    }

    public static AttributeSupplier.Builder attributs() {
        return PathfinderMob.createMobAttributes().add(Attributes.MAX_HEALTH, 20).add(Attributes.MOVEMENT_SPEED, 0.25).add(Attributes.FOLLOW_RANGE, 16);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(SKIN, "sicario");
        builder.define(POSE, (byte) REPOS);
    }

    public String skin() {
        return entityData.get(SKIN);
    }

    public int pose() {
        return entityData.get(POSE);
    }

    /** Habille le gars, lui donne sa pose, son arme et (s'il tire) sa cible. */
    public CartelPnj preparer(String skin, int pose, ItemStack arme, Vec3 cible, boolean flaneur) {
        entityData.set(SKIN, skin);
        entityData.set(POSE, (byte) pose);
        setItemSlot(EquipmentSlot.MAINHAND, arme);
        setDropChance(EquipmentSlot.MAINHAND, 0F);
        this.cible = cible;
        this.flaneur = flaneur;
        restrictTo(blockPosition(), flaneur ? 6 : 1);
        prochaineRafale = 20 + random.nextInt(80);
        return this;
    }

    @Override
    protected void registerGoals() {
        goalSelector.addGoal(0, new FloatGoal(this));
        goalSelector.addGoal(5, new WaterAvoidingRandomStrollGoal(this, 0.55) {
            @Override
            public boolean canUse() {
                return flaneur && super.canUse();
            }
        });
        goalSelector.addGoal(6, new LookAtPlayerGoal(this, Player.class, 8F) {
            @Override
            public boolean canUse() {
                return cible == null && super.canUse();
            }
        });
        goalSelector.addGoal(7, new RandomLookAroundGoal(this) {
            @Override
            public boolean canUse() {
                return cible == null && super.canUse();
            }
        });
    }

    // ------------------------------------------------------------------ le stand de tir

    @Override
    protected void customServerAiStep() {
        super.customServerAiStep();
        if (cible == null || !(level() instanceof ServerLevel level) || !(getMainHandItem().getItem() instanceof ArmeItem arme)) {
            return;
        }
        // il garde les yeux sur sa cible
        Vec3 oeil = getEyePosition();
        Vec3 d = cible.subtract(oeil);
        float yaw = (float) (Mth.atan2(d.z, d.x) * Mth.RAD_TO_DEG) - 90F;
        float pitch = (float) -(Mth.atan2(d.y, Math.sqrt(d.x * d.x + d.z * d.z)) * Mth.RAD_TO_DEG);
        setYRot(yaw);
        yHeadRot = yaw;
        yBodyRot = yaw;
        setXRot(pitch);
        if (level.players().stream().noneMatch(j -> j.distanceToSqr(this) < 96 * 96)) {
            return;
        }
        if (ballesRestantes > 0) {
            if (tickCount % Math.max(1, arme.spec.cadence()) == 0) {
                tirer(level, arme, oeil, d.normalize());
                ballesRestantes--;
            }
            return;
        }
        if (--prochaineRafale <= 0) {
            ArmeItem.Spec s = arme.spec;
            ballesRestantes = s.tir() != ArmeItem.Tir.BALLE ? 1 : s.auto() ? 4 + random.nextInt(7) : 1 + random.nextInt(3);
            prochaineRafale = s.tir() == ArmeItem.Tir.ROQUETTE ? 220 + random.nextInt(200) : 40 + random.nextInt(90);
        }
    }

    private void tirer(ServerLevel level, ArmeItem arme, Vec3 oeil, Vec3 dir) {
        ArmeItem.Spec s = arme.spec;
        Vec3 canon = ArmeItem.bouche(this, oeil, dir);
        level.playSound(null, canon.x, canon.y, canon.z, s.son().get(), SoundSource.HOSTILE, 2.5F, 0.94F + random.nextFloat() * 0.12F);
        if (s.tir() == ArmeItem.Tir.ROQUETTE) {
            ProjectileArme.factice(level, this, canon, dir);
            return;
        }
        for (int i = 0; i < s.plombs(); i++) {
            Vec3 v = ArmeItem.devier(dir, Math.max(0.6F, s.dispersion()), this);
            ArmeItem.tracante(level, canon, oeil.add(v.scale(oeil.distanceTo(cible))), s.degats() >= 8 ? 1 : 0, 0);
        }
    }

    // ------------------------------------------------------------------ invincible, immobile, éternel

    @Override
    public boolean hurt(DamageSource source, float degats) {
        return source.is(DamageTypeTags.BYPASSES_INVULNERABILITY) && super.hurt(source, degats);
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
        tag.putByte("Pose", (byte) pose());
        tag.putBoolean("Flaneur", flaneur);
        if (cible != null) {
            tag.putDouble("CibleX", cible.x);
            tag.putDouble("CibleY", cible.y);
            tag.putDouble("CibleZ", cible.z);
        }
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        entityData.set(SKIN, tag.getString("Skin"));
        entityData.set(POSE, tag.getByte("Pose"));
        flaneur = tag.getBoolean("Flaneur");
        cible = tag.contains("CibleX") ? new Vec3(tag.getDouble("CibleX"), tag.getDouble("CibleY"), tag.getDouble("CibleZ")) : null;
        BlockPos maison = blockPosition();
        restrictTo(maison, flaneur ? 6 : 1);
    }
}
