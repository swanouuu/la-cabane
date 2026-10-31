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
    /** Le carnet de chaque joueur : numéro → nom, dans l'ordre d'ajout. */
    private final Map<UUID, java.util.LinkedHashMap<String, String>> contacts = new HashMap<>();
    /** Les derniers appels de chaque joueur, du plus récent au plus ancien. */
    private final Map<UUID, java.util.LinkedList<Recent>> recents = new HashMap<>();

    public static final int SORTANT = 0;
    public static final int ENTRANT = 1;
    public static final int MANQUE = 2;

    public record Recent(String numero, int type, long temps) {
    }

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
        for (Tag t : tag.getList("carnets", Tag.TAG_COMPOUND)) {
            CompoundTag c = (CompoundTag) t;
            java.util.LinkedHashMap<String, String> carnet = new java.util.LinkedHashMap<>();
            for (Tag e : c.getList("contacts", Tag.TAG_COMPOUND)) {
                carnet.put(((CompoundTag) e).getString("numero"), ((CompoundTag) e).getString("nom"));
            }
            java.util.LinkedList<Recent> liste = new java.util.LinkedList<>();
            for (Tag e : c.getList("recents", Tag.TAG_COMPOUND)) {
                CompoundTag r = (CompoundTag) e;
                liste.add(new Recent(r.getString("numero"), r.getInt("type"), r.getLong("temps")));
            }
            data.contacts.put(c.getUUID("joueur"), carnet);
            data.recents.put(c.getUUID("joueur"), liste);
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
        ListTag carnets = new ListTag();
        java.util.Set<UUID> joueurs = new java.util.HashSet<>(contacts.keySet());
        joueurs.addAll(recents.keySet());
        for (UUID joueur : joueurs) {
            CompoundTag c = new CompoundTag();
            c.putUUID("joueur", joueur);
            ListTag lc = new ListTag();
            contacts.getOrDefault(joueur, new java.util.LinkedHashMap<>()).forEach((numero, nom) -> {
                CompoundTag e = new CompoundTag();
                e.putString("numero", numero);
                e.putString("nom", nom);
                lc.add(e);
            });
            ListTag lr = new ListTag();
            for (Recent r : recents.getOrDefault(joueur, new java.util.LinkedList<>())) {
                CompoundTag e = new CompoundTag();
                e.putString("numero", r.numero());
                e.putInt("type", r.type());
                e.putLong("temps", r.temps());
                lr.add(e);
            }
            c.put("contacts", lc);
            c.put("recents", lr);
            carnets.add(c);
        }
        tag.put("carnets", carnets);
        return tag;
    }

    public java.util.LinkedHashMap<String, String> contacts(UUID joueur) {
        return contacts.getOrDefault(joueur, new java.util.LinkedHashMap<>());
    }

    public void ajouterContact(UUID joueur, String numero, String nom) {
        java.util.LinkedHashMap<String, String> carnet = contacts.computeIfAbsent(joueur, k -> new java.util.LinkedHashMap<>());
        if (carnet.size() < 60 || carnet.containsKey(numero)) {
            carnet.put(numero, nom);
            setDirty();
        }
    }

    public void supprimerContact(UUID joueur, String numero) {
        if (contacts.getOrDefault(joueur, new java.util.LinkedHashMap<>()).remove(numero) != null) {
            setDirty();
        }
    }

    public java.util.List<Recent> recents(UUID joueur) {
        return recents.getOrDefault(joueur, new java.util.LinkedList<>());
    }

    public void ajouterRecent(UUID joueur, String numero, int type) {
        java.util.LinkedList<Recent> liste = recents.computeIfAbsent(joueur, k -> new java.util.LinkedList<>());
        liste.addFirst(new Recent(numero, type, System.currentTimeMillis()));
        while (liste.size() > 20) {
            liste.removeLast();
        }
        setDirty();
    }

    /** L'appel entrant le plus récent de ce numéro devient « manqué ». */
    public void marquerManque(UUID joueur, String numero) {
        java.util.LinkedList<Recent> liste = recents.get(joueur);
        if (liste != null && !liste.isEmpty() && liste.getFirst().numero().equals(numero) && liste.getFirst().type() == ENTRANT) {
            Recent r = liste.removeFirst();
            liste.addFirst(new Recent(r.numero(), MANQUE, r.temps()));
            setDirty();
        }
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
