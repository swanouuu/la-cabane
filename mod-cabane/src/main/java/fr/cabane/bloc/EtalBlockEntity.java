package fr.cabane.bloc;

import fr.cabane.CabaneBlocks;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.items.ItemStackHandler;

/**
 * Le stock d'un étal : 9 lots, chacun avec son prix. Pas de Container exposé :
 * les entonnoirs ne peuvent rien y prendre.
 */
public class EtalBlockEntity extends BlockEntity {
    public static final int LOTS = 9;

    private UUID proprietaire;
    private String nomProprietaire = "";
    private final long[] prix = new long[LOTS];
    private final ItemStackHandler stock = new ItemStackHandler(LOTS) {
        @Override
        protected void onContentsChanged(int slot) {
            setChanged();
            if (level != null && !level.isClientSide) {
                level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
            }
        }
    };

    public EtalBlockEntity(BlockPos pos, BlockState state) {
        super(CabaneBlocks.ETAL_BE.get(), pos, state);
    }

    public ItemStackHandler stock() {
        return stock;
    }

    public long prix(int lot) {
        return prix[lot];
    }

    public long[] tousLesPrix() {
        return prix.clone();
    }

    public void definirPrix(long[] nouveaux) {
        for (int i = 0; i < LOTS && i < nouveaux.length; i++) {
            prix[i] = Math.max(0, Math.min(1_000_000_000L, nouveaux[i]));
        }
        setChanged();
    }

    public UUID proprietaire() {
        return proprietaire;
    }

    public String nomProprietaire() {
        return nomProprietaire;
    }

    public void definirProprietaire(UUID id, String nom) {
        proprietaire = id;
        nomProprietaire = nom;
        setChanged();
    }

    public boolean estProprietaire(UUID id) {
        return id.equals(proprietaire);
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        if (proprietaire != null) {
            tag.putUUID("proprietaire", proprietaire);
        }
        tag.putString("nom", nomProprietaire);
        tag.put("stock", stock.serializeNBT(registries));
        tag.putLongArray("prix", prix);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        if (tag.hasUUID("proprietaire")) {
            proprietaire = tag.getUUID("proprietaire");
        }
        nomProprietaire = tag.getString("nom");
        stock.deserializeNBT(registries, tag.getCompound("stock"));
        long[] lus = tag.getLongArray("prix");
        System.arraycopy(lus, 0, prix, 0, Math.min(lus.length, LOTS));
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        return saveWithoutMetadata(registries);
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }
}
