package fr.cabane.photo;

import fr.cabane.CabaneComponents;
import fr.cabane.CabaneEntities;
import fr.cabane.CabaneItems;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerEntity;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.decoration.HangingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Une photo accrochée au mur, comme un tableau, de 1 x 1 à 8 x 8 blocs. On la décroche en la frappant. */
public class TableauPhoto extends HangingEntity {
    private static final EntityDataAccessor<Long> PHOTO = SynchedEntityData.defineId(TableauPhoto.class, EntityDataSerializers.LONG);
    private static final EntityDataAccessor<Integer> LARGEUR = SynchedEntityData.defineId(TableauPhoto.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> HAUTEUR = SynchedEntityData.defineId(TableauPhoto.class, EntityDataSerializers.INT);

    public TableauPhoto(EntityType<? extends TableauPhoto> type, Level level) {
        super(type, level);
    }

    public TableauPhoto(Level level, BlockPos pos, Direction direction, PhotoImprimee photo) {
        super(CabaneEntities.TABLEAU_PHOTO.get(), level, pos);
        entityData.set(PHOTO, photo.id());
        entityData.set(LARGEUR, Math.clamp(photo.largeur(), 1, Impression.MAX));
        entityData.set(HAUTEUR, Math.clamp(photo.hauteur(), 1, Impression.MAX));
        setDirection(direction);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(PHOTO, 0L);
        builder.define(LARGEUR, 1);
        builder.define(HAUTEUR, 1);
    }

    @Override
    public void onSyncedDataUpdated(EntityDataAccessor<?> key) {
        if ((LARGEUR.equals(key) || HAUTEUR.equals(key)) && pos != null) {
            recalculateBoundingBox();
        }
        super.onSyncedDataUpdated(key);
    }

    public long photo() {
        return entityData.get(PHOTO);
    }

    public int largeur() {
        return entityData.get(LARGEUR);
    }

    public int hauteur() {
        return entityData.get(HAUTEUR);
    }

    @Override
    protected AABB calculateBoundingBox(BlockPos pos, Direction direction) {
        Vec3 centre = Vec3.atCenterOf(pos).relative(direction, -0.46875);
        double dx = largeur() % 2 == 0 ? 0.5 : 0.0;
        double dy = hauteur() % 2 == 0 ? 0.5 : 0.0;
        Vec3 milieu = centre.relative(direction.getCounterClockWise(), dx).relative(Direction.UP, dy);
        Direction.Axis axe = direction.getAxis();
        return AABB.ofSize(milieu, axe == Direction.Axis.X ? 0.0625 : largeur(), hauteur(), axe == Direction.Axis.Z ? 0.0625 : largeur());
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        tag.putLong("photo", photo());
        tag.putInt("largeur", largeur());
        tag.putInt("hauteur", hauteur());
        tag.putByte("facing", (byte) direction.get2DDataValue());
        super.addAdditionalSaveData(tag);
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        entityData.set(PHOTO, tag.getLong("photo"));
        entityData.set(LARGEUR, Math.clamp(tag.getInt("largeur"), 1, Impression.MAX));
        entityData.set(HAUTEUR, Math.clamp(tag.getInt("hauteur"), 1, Impression.MAX));
        direction = Direction.from2DDataValue(tag.getByte("facing"));
        super.readAdditionalSaveData(tag);
        setDirection(direction);
    }

    private ItemStack objet() {
        ItemStack stack = new ItemStack(CabaneItems.PHOTO.get());
        stack.set(CabaneComponents.PHOTO.get(), new PhotoImprimee(photo(), largeur(), hauteur()));
        return stack;
    }

    @Override
    public void dropItem(@Nullable Entity casseur) {
        if (level().getGameRules().getBoolean(GameRules.RULE_DOENTITYDROPS)) {
            playSound(SoundEvents.PAINTING_BREAK, 1F, 1.2F);
            if (casseur instanceof Player joueur && joueur.hasInfiniteMaterials()) {
                return;
            }
            spawnAtLocation(objet());
        }
    }

    @Override
    public void playPlacementSound() {
        playSound(SoundEvents.PAINTING_PLACE, 1F, 1.2F);
    }

    @Override
    public void moveTo(double x, double y, double z, float yaw, float pitch) {
        setPos(x, y, z);
    }

    @Override
    public void lerpTo(double x, double y, double z, float yRot, float xRot, int steps) {
        setPos(x, y, z);
    }

    @Override
    public Vec3 trackingPosition() {
        return Vec3.atLowerCornerOf(pos);
    }

    @Override
    public Packet<ClientGamePacketListener> getAddEntityPacket(ServerEntity entity) {
        return new ClientboundAddEntityPacket(this, direction.get3DDataValue(), getPos());
    }

    @Override
    public void recreateFromPacket(ClientboundAddEntityPacket packet) {
        super.recreateFromPacket(packet);
        setDirection(Direction.from3DDataValue(packet.getData()));
    }

    @Override
    public ItemStack getPickResult() {
        return objet();
    }
}
