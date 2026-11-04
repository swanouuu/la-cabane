package fr.cabane.hub;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.StringTag;
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
    /** La version de l'aéroport construit dans la dimension du décollage (voir AeroportBuilder.VERSION). */
    private int versionAeroport;
    /** Le Président est-il tombé ? */
    private boolean libere;
    /** Le centre du PLATO 6Z (au sol), une fois construit. */
    private BlockPos forteresse;
    /** L'île du combat de Vegeta, tant qu'elle existe (pour l'effacer même si le serveur s'est arrêté en plein combat). */
    private BlockPos ileVegeta;
    /** Combien de fois le serveur a démarré (la demande du jour du Hejj change à chaque fois). */
    private int demarrages;
    /** L'hôte a-t-il ouvert la forteresse (/cabane forteresse ouvrir) ? */
    private boolean forteresseOuverte;
    /** Le combat Vegeta contre Goku : 0 pas encore prévu, 1 armé par l'hôte, 2 joué. */
    private int vegeta;
    /** Qui les joueurs ont aidé : « goku » ou « vegeta » (vide avant le combat). */
    private String issueVegeta = "";
    /** La partie de l'histoire débloquée par l'hôte (/histoire partie) : 1 le village, 2 le secret de la Mairie, 3 le PLATO 6Z. */
    private int partie = 1;
    /** Les cartes de reliques vendues par Karim, dans l'ordre (la clé du lieu) : chaque nouvelle coûte trois fois plus. */
    private final List<String> cartesReliques = new ArrayList<>();
    /** Les reliques déjà trouvées sur le serveur, dans l'ordre (chacune fait venir plus de Pillards que la précédente). */
    private final List<String> reliquesTrouvees = new ArrayList<>();
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
        data.versionAeroport = tag.getInt("versionAeroport");
        data.libere = tag.getBoolean("libere");
        NbtUtils.readBlockPos(tag, "forteresse").ifPresent(pos -> data.forteresse = pos);
        NbtUtils.readBlockPos(tag, "ileVegeta").ifPresent(pos -> data.ileVegeta = pos);
        data.demarrages = tag.getInt("demarrages");
        data.forteresseOuverte = tag.getBoolean("forteresseOuverte");
        data.vegeta = tag.getInt("vegeta");
        data.issueVegeta = tag.getString("issueVegeta");
        data.partie = Math.max(1, tag.getInt("partie"));
        tag.getList("cartesReliques", Tag.TAG_STRING).forEach(t -> data.cartesReliques.add(t.getAsString()));
        tag.getList("reliquesTrouvees", Tag.TAG_STRING).forEach(t -> data.reliquesTrouvees.add(t.getAsString()));
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
        tag.putInt("versionAeroport", versionAeroport);
        tag.putBoolean("libere", libere);
        if (ileVegeta != null) {
            tag.put("ileVegeta", NbtUtils.writeBlockPos(ileVegeta));
        }
        tag.putInt("demarrages", demarrages);
        if (forteresse != null) {
            tag.put("forteresse", NbtUtils.writeBlockPos(forteresse));
        }
        tag.putBoolean("forteresseOuverte", forteresseOuverte);
        tag.putInt("vegeta", vegeta);
        tag.putString("issueVegeta", issueVegeta);
        tag.putInt("partie", partie);
        ListTag cartes = new ListTag();
        cartesReliques.forEach(c -> cartes.add(StringTag.valueOf(c)));
        tag.put("cartesReliques", cartes);
        ListTag trouvees = new ListTag();
        reliquesTrouvees.forEach(r -> trouvees.add(StringTag.valueOf(r)));
        tag.put("reliquesTrouvees", trouvees);
        ListTag list = new ListTag();
        kits.forEach(id -> list.add(NbtUtils.createUUID(id)));
        tag.put("kits", list);
        return tag;
    }

    public int vegeta() {
        return vegeta;
    }

    public String issueVegeta() {
        return issueVegeta;
    }

    public void setVegeta(int etat, String issue) {
        vegeta = etat;
        issueVegeta = issue;
        setDirty();
    }

    /** La partie de l'histoire en cours ; une fois Beauvais libéré, tout est forcément débloqué. */
    public int partie() {
        return libere ? 3 : partie;
    }

    public void setPartie(int valeur) {
        partie = valeur;
        setDirty();
    }

    /** Le rang de la carte de ce lieu chez Karim (0 pour la première vendue), ou le rang qu'aurait la prochaine. */
    public int rangCarte(String lieu) {
        int i = cartesReliques.indexOf(lieu);
        return i >= 0 ? i : cartesReliques.size();
    }

    public boolean carteDejaVendue(String lieu) {
        return cartesReliques.contains(lieu);
    }

    public void carteVendue(String lieu) {
        if (!cartesReliques.contains(lieu)) {
            cartesReliques.add(lieu);
            setDirty();
        }
    }

    /** @return le rang de cette relique (1 pour la première trouvée sur le serveur), ou 0 si elle l'avait déjà été. */
    public int reliqueTrouvee(String relique) {
        if (reliquesTrouvees.contains(relique)) {
            return 0;
        }
        reliquesTrouvees.add(relique);
        setDirty();
        return reliquesTrouvees.size();
    }

    public boolean libere() {
        return libere;
    }

    public void setLibere(boolean valeur) {
        libere = valeur;
        setDirty();
    }

    public int nouveauDemarrage() {
        demarrages++;
        setDirty();
        return demarrages;
    }

    public BlockPos ileVegeta() {
        return ileVegeta;
    }

    public void setIleVegeta(BlockPos pos) {
        ileVegeta = pos;
        setDirty();
    }

    public BlockPos forteresse() {
        return forteresse;
    }

    public void setForteresse(BlockPos pos) {
        forteresse = pos;
        setDirty();
    }

    public boolean forteresseOuverte() {
        return forteresseOuverte;
    }

    public void setForteresseOuverte(boolean valeur) {
        forteresseOuverte = valeur;
        setDirty();
    }

    public int versionPnj() {
        return versionPnj;
    }

    public void setVersionPnj(int version) {
        versionPnj = version;
        setDirty();
    }

    public int versionAeroport() {
        return versionAeroport;
    }

    public void setVersionAeroport(int version) {
        versionAeroport = version;
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
