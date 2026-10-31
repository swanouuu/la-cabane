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
    private final Set<UUID> kits = new HashSet<>();

    public static HubData get(MinecraftServer server) {
        return server.overworld().getDataStorage()
                .computeIfAbsent(new SavedData.Factory<>(HubData::new, HubData::load, null), NAME);
    }

    private static HubData load(CompoundTag tag, HolderLookup.Provider registries) {
        HubData data = new HubData();
        NbtUtils.readBlockPos(tag, "centre").ifPresent(pos -> data.centre = pos);
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
        ListTag list = new ListTag();
        kits.forEach(id -> list.add(NbtUtils.createUUID(id)));
        tag.put("kits", list);
        return tag;
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
