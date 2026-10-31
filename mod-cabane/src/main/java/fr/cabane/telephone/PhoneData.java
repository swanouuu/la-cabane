package fr.cabane.telephone;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * Les cartes SIM du serveur. Un numéro est gravé une fois pour toutes dans un téléphone (voir
 * {@link fr.cabane.CabaneComponents#NUMERO}) ; ici on garde quels numéros sont pris, et le carnet
 * et les appels récents de chaque SIM. Sauvegardé avec le monde.
 */
public class PhoneData extends SavedData {
    private static final String NAME = "cabane_sims";

    /** Numéro → nom du joueur qui a activé la SIM. */
    private final Map<String, String> sims = new HashMap<>();
    /** Le carnet de chaque SIM : numéro → nom, dans l'ordre d'ajout. */
    private final Map<String, LinkedHashMap<String, String>> contacts = new HashMap<>();
    /** Les derniers appels de chaque SIM, du plus récent au plus ancien. */
    private final Map<String, LinkedList<Recent>> recents = new HashMap<>();

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
        for (Tag t : tag.getList("sims", Tag.TAG_COMPOUND)) {
            CompoundTag sim = (CompoundTag) t;
            String numero = sim.getString("numero");
            data.sims.put(numero, sim.getString("nom"));
            LinkedHashMap<String, String> carnet = new LinkedHashMap<>();
            for (Tag e : sim.getList("contacts", Tag.TAG_COMPOUND)) {
                carnet.put(((CompoundTag) e).getString("numero"), ((CompoundTag) e).getString("nom"));
            }
            LinkedList<Recent> liste = new LinkedList<>();
            for (Tag e : sim.getList("recents", Tag.TAG_COMPOUND)) {
                CompoundTag r = (CompoundTag) e;
                liste.add(new Recent(r.getString("numero"), r.getInt("type"), r.getLong("temps")));
            }
            data.contacts.put(numero, carnet);
            data.recents.put(numero, liste);
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag liste = new ListTag();
        sims.forEach((numero, nom) -> {
            CompoundTag sim = new CompoundTag();
            sim.putString("numero", numero);
            sim.putString("nom", nom);
            ListTag lc = new ListTag();
            contacts.getOrDefault(numero, new LinkedHashMap<>()).forEach((n, nomContact) -> {
                CompoundTag e = new CompoundTag();
                e.putString("numero", n);
                e.putString("nom", nomContact);
                lc.add(e);
            });
            ListTag lr = new ListTag();
            for (Recent r : recents.getOrDefault(numero, new LinkedList<>())) {
                CompoundTag e = new CompoundTag();
                e.putString("numero", r.numero());
                e.putInt("type", r.type());
                e.putLong("temps", r.temps());
                lr.add(e);
            }
            sim.put("contacts", lc);
            sim.put("recents", lr);
            liste.add(sim);
        });
        tag.put("sims", liste);
        return tag;
    }

    public boolean existe(String numero) {
        return sims.containsKey(numero);
    }

    /** @return false si le numéro est déjà pris. */
    public boolean activer(String numero, String nom) {
        if (sims.containsKey(numero)) {
            return false;
        }
        sims.put(numero, nom);
        setDirty();
        return true;
    }

    public LinkedHashMap<String, String> contacts(String sim) {
        return contacts.getOrDefault(sim, new LinkedHashMap<>());
    }

    public void ajouterContact(String sim, String numero, String nom) {
        LinkedHashMap<String, String> carnet = contacts.computeIfAbsent(sim, k -> new LinkedHashMap<>());
        if (carnet.size() < 60 || carnet.containsKey(numero)) {
            carnet.put(numero, nom);
            setDirty();
        }
    }

    public void supprimerContact(String sim, String numero) {
        if (contacts.getOrDefault(sim, new LinkedHashMap<>()).remove(numero) != null) {
            setDirty();
        }
    }

    public List<Recent> recents(String sim) {
        return recents.getOrDefault(sim, new LinkedList<>());
    }

    public void ajouterRecent(String sim, String numero, int type) {
        LinkedList<Recent> liste = recents.computeIfAbsent(sim, k -> new LinkedList<>());
        liste.addFirst(new Recent(numero, type, System.currentTimeMillis()));
        while (liste.size() > 20) {
            liste.removeLast();
        }
        setDirty();
    }

    /** L'appel entrant le plus récent de ce numéro devient « manqué ». */
    public void marquerManque(String sim, String numero) {
        LinkedList<Recent> liste = recents.get(sim);
        if (liste != null && !liste.isEmpty() && liste.getFirst().numero().equals(numero) && liste.getFirst().type() == ENTRANT) {
            Recent r = liste.removeFirst();
            liste.addFirst(new Recent(r.numero(), MANQUE, r.temps()));
            setDirty();
        }
    }

    /** Numéro → nom de celui qui a activé la SIM, trié par numéro (pour la commande d'admin). */
    public Map<String, String> directory() {
        return new TreeMap<>(sims);
    }
}
