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
