package fr.cabane.parc;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
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

/**
 * Quelqu'un dans le parc, pour qu'il y ait de la vie : un visiteur qui se promène autour de sa place (la place, un
 * bout d'avenue, la file du Dragon…), parfois un goûter à la main, ou un employé qui reste à son guichet ou à son
 * stand et regarde les gens passer. Son skin est un des skins de base de Minecraft, tiré au hasard (d'après son
 * identifiant). Il ne parle pas, on ne peut pas le blesser, et il ne disparaît jamais.
 */
public class VisiteurParc extends PathfinderMob {
    /** Ce qu'il fait : il se promène, ou il travaille (il ne bouge pas de sa place). */
    public static final byte PROMENEUR = 0, EMPLOYE = 1, RACHIDA = 2;
    /** Rachida, devant la fontaine, face à l'avenue (elle répète ses répliques, côté joueur : AmbianceParc). */
    public static final double RACHIDA_X = 0.5, RACHIDA_Z = 9.5;

    private static final EntityDataAccessor<Byte> ROLE = SynchedEntityData.defineId(VisiteurParc.class, EntityDataSerializers.BYTE);
    private int rayon = 12;

    public VisiteurParc(EntityType<? extends VisiteurParc> type, Level level) {
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
        builder.define(ROLE, PROMENEUR);
    }

    public byte role() {
        return entityData.get(ROLE);
    }

    /** Un promeneur qui reste à moins de {@code rayon} blocs d'ici, avec peut-être quelque chose à la main. */
    VisiteurParc promeneur(int rayon, ItemStack main) {
        entityData.set(ROLE, PROMENEUR);
        this.rayon = rayon;
        restrictTo(blockPosition(), rayon);
        if (!main.isEmpty()) {
            setItemSlot(EquipmentSlot.MAINHAND, main);
            setDropChance(EquipmentSlot.MAINHAND, 0F);
        }
        return this;
    }

    /** Rachida : elle reste plantée devant la fontaine, son nom au-dessus de la tête. */
    VisiteurParc rachida() {
        entityData.set(ROLE, RACHIDA);
        restrictTo(blockPosition(), 0);
        setCustomName(net.minecraft.network.chat.Component.literal("Rachida"));
        setCustomNameVisible(true);
        return this;
    }

    /** Un employé : il reste à sa place. */
    VisiteurParc employe() {
        entityData.set(ROLE, EMPLOYE);
        restrictTo(blockPosition(), 0);
        return this;
    }

    @Override
    protected void registerGoals() {
        goalSelector.addGoal(0, new FloatGoal(this));
        goalSelector.addGoal(5, new WaterAvoidingRandomStrollGoal(this, 0.5) {
            @Override
            public boolean canUse() {
                return role() == PROMENEUR && super.canUse();
            }
        });
        goalSelector.addGoal(6, new LookAtPlayerGoal(this, Player.class, 8F));
        goalSelector.addGoal(7, new RandomLookAroundGoal(this));
    }

    @Override
    public void tick() {
        super.tick();
        // l'employé ne quitte pas sa place, même bousculé
        if (!level().isClientSide && role() != PROMENEUR && hasRestriction()) {
            BlockPos p = getRestrictCenter();
            if (distanceToSqr(p.getX() + 0.5, getY(), p.getZ() + 0.5) > 0.6) {
                moveTo(p.getX() + 0.5, getY(), p.getZ() + 0.5, getYRot(), getXRot());
            }
        }
    }

    /** On ne peut pas lui faire de mal. */
    @Override
    public boolean hurt(DamageSource source, float degats) {
        return false;
    }

    @Override
    public boolean isInvulnerableTo(DamageSource source) {
        return true;
    }

    /** Il ne dit rien : un clic droit ne fait rien. */
    @Override
    protected InteractionResult mobInteract(Player joueur, InteractionHand main) {
        return InteractionResult.PASS;
    }

    @Override
    public boolean removeWhenFarAway(double distance) {
        return false;
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putByte("role", role());
        tag.putInt("rayon", rayon);
        if (hasRestriction()) {
            tag.putLong("chez", getRestrictCenter().asLong());
        }
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        entityData.set(ROLE, tag.getByte("role"));
        rayon = tag.contains("rayon") ? tag.getInt("rayon") : 12;
        // sa place : là où il se promène (ou là où il travaille)
        if (tag.contains("chez")) {
            restrictTo(BlockPos.of(tag.getLong("chez")), role() != PROMENEUR ? 0 : rayon);
        }
    }
}
