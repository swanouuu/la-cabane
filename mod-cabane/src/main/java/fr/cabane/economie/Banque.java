package fr.cabane.economie;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * Les comptes en SakaiDollars. L'argent n'est pas un objet : il vit ici, sur le serveur,
 * donc on ne peut ni le voler, ni le perdre en mourant.
 */
public class Banque extends SavedData {
    public static final long SOLDE_DEPART = 100;
    private static final String NAME = "cabane_banque";

    private final Map<UUID, Long> soldes = new HashMap<>();
    private final Map<UUID, String> noms = new HashMap<>();

    public static Banque get(MinecraftServer server) {
        return server.overworld().getDataStorage()
                .computeIfAbsent(new SavedData.Factory<>(Banque::new, Banque::load, null), NAME);
    }

    private static Banque load(CompoundTag tag, HolderLookup.Provider registries) {
        Banque banque = new Banque();
        ListTag comptes = tag.getList("comptes", Tag.TAG_COMPOUND);
        for (int i = 0; i < comptes.size(); i++) {
            CompoundTag c = comptes.getCompound(i);
            banque.soldes.put(c.getUUID("joueur"), c.getLong("solde"));
            banque.noms.put(c.getUUID("joueur"), c.getString("nom"));
        }
        return banque;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag comptes = new ListTag();
        soldes.forEach((id, solde) -> {
            CompoundTag c = new CompoundTag();
            c.putUUID("joueur", id);
            c.putLong("solde", solde);
            c.putString("nom", noms.getOrDefault(id, ""));
            comptes.add(c);
        });
        tag.put("comptes", comptes);
        return tag;
    }

    /** @return true si le compte vient d'être créé. */
    public boolean ouvrir(UUID joueur, String nom) {
        noms.put(joueur, nom);
        boolean nouveau = soldes.putIfAbsent(joueur, SOLDE_DEPART) == null;
        setDirty();
        return nouveau;
    }

    public boolean aUnCompte(UUID joueur) {
        return soldes.containsKey(joueur);
    }

    public long solde(UUID joueur) {
        return soldes.getOrDefault(joueur, 0L);
    }

    public String nom(UUID joueur) {
        return noms.getOrDefault(joueur, "?");
    }

    public void crediter(UUID joueur, long montant) {
        soldes.merge(joueur, Math.max(0, montant), Long::sum);
        setDirty();
    }

    /** @return false (et rien ne bouge) si le solde ne suffit pas. */
    public boolean debiter(UUID joueur, long montant) {
        long solde = solde(joueur);
        if (montant < 0 || solde < montant) {
            return false;
        }
        soldes.put(joueur, solde - montant);
        setDirty();
        return true;
    }

    public void definir(UUID joueur, long montant) {
        soldes.put(joueur, Math.max(0, montant));
        setDirty();
    }

    public List<Map.Entry<UUID, Long>> classement(int n) {
        return soldes.entrySet().stream()
                .sorted(Map.Entry.<UUID, Long>comparingByValue(Comparator.reverseOrder()))
                .limit(n)
                .toList();
    }
}
