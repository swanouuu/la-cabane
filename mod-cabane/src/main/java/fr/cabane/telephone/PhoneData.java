package fr.cabane.telephone;

import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

/** Annuaire du serveur : quel joueur a quel numéro. Sauvegardé avec le monde. */
public class PhoneData extends SavedData {
    private static final String NAME = "cabane_telephones";

    private final Map<UUID, String> numbers = new HashMap<>();
    private final Map<String, UUID> owners = new HashMap<>();
    private final Map<UUID, String> names = new HashMap<>();

    public static PhoneData get(MinecraftServer server) {
        return server.overworld().getDataStorage()
                .computeIfAbsent(new SavedData.Factory<>(PhoneData::new, PhoneData::load, null), NAME);
    }

    private static PhoneData load(CompoundTag tag, HolderLookup.Provider registries) {
        PhoneData data = new PhoneData();
        ListTag list = tag.getList("numeros", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompound(i);
            UUID player = entry.getUUID("joueur");
            String number = entry.getString("numero");
            data.numbers.put(player, number);
            data.owners.put(number, player);
            data.names.put(player, entry.getString("nom"));
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        numbers.forEach((player, number) -> {
            CompoundTag entry = new CompoundTag();
            entry.putUUID("joueur", player);
            entry.putString("numero", number);
            entry.putString("nom", names.getOrDefault(player, ""));
            list.add(entry);
        });
        tag.put("numeros", list);
        return tag;
    }

    public String numberOf(UUID player) {
        return numbers.get(player);
    }

    public UUID ownerOf(String number) {
        return owners.get(number);
    }

    /** @return false si le numéro appartient déjà à quelqu'un d'autre. */
    public boolean assign(UUID player, String name, String number) {
        UUID current = owners.get(number);
        if (current != null && !current.equals(player)) {
            return false;
        }
        String old = numbers.put(player, number);
        if (old != null) {
            owners.remove(old);
        }
        owners.put(number, player);
        names.put(player, name);
        setDirty();
        return true;
    }

    /** Numéro → nom du joueur, trié par numéro (pour la commande d'admin). */
    public Map<String, String> directory() {
        Map<String, String> result = new TreeMap<>();
        owners.forEach((number, player) -> result.put(number, names.getOrDefault(player, player.toString())));
        return result;
    }
}
