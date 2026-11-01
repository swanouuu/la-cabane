package fr.cabane.hub;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

/** Où est le centre de map, et qui a déjà reçu son kit de départ. */
public class HubData extends SavedData {
    private static final String NAME = "cabane_centre";

    private BlockPos centre;
    /** La partie a-t-elle commencé (/start) ? Avant, on attend dans la salle d'embarquement. */
    private boolean demarre;
    /** La version des PNJ posés (voir HubEvents.VERSION_PNJ). */
    private int versionPnj;
    private final Set<UUID> kits = new HashSet<>();

    public static HubData get(MinecraftServer server) {
        return server.overworld().getDataStorage()
                .computeIfAbsent(new SavedData.Factory<>(HubData::new, HubData::load, null), NAME);
    }

    private static HubData load(CompoundTag tag, HolderLookup.Provider registries) {
        HubData data = new HubData();
        NbtUtils.readBlockPos(tag, "centre").ifPresent(pos -> data.centre = pos);
        data.demarre = tag.getBoolean("demarre");
        data.versionPnj = tag.getInt("versionPnj");
        ListTag list = tag.getList("kits", Tag.TAG_INT_ARRAY);
        for (Tag entry : list) {
            data.kits.add(NbtUtils.loadUUID(entry));
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        if (centre != null) {
            tag.put("centre", NbtUtils.writeBlockPos(centre));
        }
        tag.putBoolean("demarre", demarre);
        tag.putInt("versionPnj", versionPnj);
        ListTag list = new ListTag();
        kits.forEach(id -> list.add(NbtUtils.createUUID(id)));
        tag.put("kits", list);
        return tag;
    }

    public int versionPnj() {
        return versionPnj;
    }

    public void setVersionPnj(int version) {
        versionPnj = version;
        setDirty();
    }

    public boolean demarre() {
        return demarre;
    }

    public void setDemarre(boolean valeur) {
        demarre = valeur;
        setDirty();
    }

    public BlockPos centre() {
        return centre;
    }

    public void setCentre(BlockPos pos) {
        centre = pos;
        setDirty();
    }

    /** @return true la première fois pour ce joueur. */
    public boolean markKit(UUID player) {
        boolean first = kits.add(player);
        if (first) {
            setDirty();
        }
        return first;
    }
}
