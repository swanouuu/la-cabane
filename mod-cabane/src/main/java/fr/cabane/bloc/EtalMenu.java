package fr.cabane.bloc;

import fr.cabane.CabaneBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemStackHandler;
import net.neoforged.neoforge.items.SlotItemHandler;

/** Le menu du propriétaire : les 9 lots de l'étal (grille 3x3) et son inventaire. */
public class EtalMenu extends AbstractContainerMenu {
    public static final int LOT_X = 8;
    public static final int LOT_Y = 20;
    public static final int LOT_DX = 74;
    public static final int LOT_DY = 22;
    public static final int INV_X = 35;
    public static final int INV_Y = 100;

    private final BlockPos pos;
    private final long[] prix;

    public EtalMenu(int id, Inventory inventaire, RegistryFriendlyByteBuf buf) {
        this(id, inventaire, new ItemStackHandler(EtalBlockEntity.LOTS), buf.readBlockPos(), buf.readLongArray());
    }

    public EtalMenu(int id, Inventory inventaire, IItemHandler stock, BlockPos pos, long[] prix) {
        super(CabaneBlocks.ETAL_MENU.get(), id);
        this.pos = pos;
        this.prix = prix;
        for (int i = 0; i < EtalBlockEntity.LOTS; i++) {
            addSlot(new SlotItemHandler(stock, i, LOT_X + (i % 3) * LOT_DX, LOT_Y + (i / 3) * LOT_DY));
        }
        for (int ligne = 0; ligne < 3; ligne++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new Slot(inventaire, col + ligne * 9 + 9, INV_X + col * 18, INV_Y + ligne * 18));
            }
        }
        for (int col = 0; col < 9; col++) {
            addSlot(new Slot(inventaire, col, INV_X + col * 18, INV_Y + 58));
        }
    }

    public BlockPos pos() {
        return pos;
    }

    public long[] prix() {
        return prix;
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        Slot slot = slots.get(index);
        if (!slot.hasItem()) {
            return ItemStack.EMPTY;
        }
        ItemStack stack = slot.getItem();
        ItemStack copie = stack.copy();
        int lots = EtalBlockEntity.LOTS;
        if (index < lots) {
            if (!moveItemStackTo(stack, lots, slots.size(), true)) {
                return ItemStack.EMPTY;
            }
        } else if (!moveItemStackTo(stack, 0, lots, false)) {
            return ItemStack.EMPTY;
        }
        if (stack.isEmpty()) {
            slot.setByPlayer(ItemStack.EMPTY);
        } else {
            slot.setChanged();
        }
        return copie;
    }

    @Override
    public boolean stillValid(Player player) {
        return player.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) < 64
                && (player.level().isClientSide || player.level().getBlockEntity(pos) instanceof EtalBlockEntity);
    }
}
