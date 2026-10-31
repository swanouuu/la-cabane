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

/**
 * La mémoire d'un bloc interactif : qui l'a posé, et pour les jeux du casino, la caisse. Le gérant
 * remplit la caisse, les gains des joueurs en sortent, les mises y entrent ; il récupère le tout.
 */
public class InteractifBlockEntity extends BlockEntity {
    private UUID proprietaire;
    private String nomProprietaire = "";
    private long caisse;

    public InteractifBlockEntity(BlockPos pos, BlockState state) {
        super(CabaneBlocks.INTERACTIF_BE.get(), pos, state);
    }

    public UUID proprietaire() {
        return proprietaire;
    }

    public String nomProprietaire() {
        return nomProprietaire;
    }

    public boolean estProprietaire(UUID id) {
        return id.equals(proprietaire);
    }

    public void definirProprietaire(UUID id, String nom) {
        proprietaire = id;
        nomProprietaire = nom;
        modifie();
    }

    public long caisse() {
        return caisse;
    }

    public void ajouterCaisse(long montant) {
        caisse = Math.max(0, caisse + montant);
        modifie();
    }

    private void modifie() {
        setChanged();
        if (level != null && !level.isClientSide) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        if (proprietaire != null) {
            tag.putUUID("proprietaire", proprietaire);
        }
        tag.putString("nom", nomProprietaire);
        tag.putLong("caisse", caisse);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        proprietaire = tag.hasUUID("proprietaire") ? tag.getUUID("proprietaire") : null;
        nomProprietaire = tag.getString("nom");
        caisse = tag.getLong("caisse");
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
