package fr.cabane.labo;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * Ce que le Laboratoire retient : d'où vient chaque joueur (pour le ramener pile au même endroit),
 * le mode de jeu qu'il avait avant d'y entrer, son numéro de sujet, et la version construite du labo.
 */
public class LaboData extends SavedData {
    private static final String NAME = "cabane_labo";

    /** L'endroit exact d'où l'on est parti : dimension, position et regard. */
    public record Retour(ResourceLocation dimension, double x, double y, double z, float yaw, float pitch) {
    }

    private final Map<UUID, Retour> retours = new HashMap<>();
    private final Map<UUID, GameType> modes = new HashMap<>();
    private final Map<UUID, Integer> sujets = new HashMap<>();
    private int versionConstruite;
    /** Le numéro de la dernière construction : les entités d'une construction plus ancienne sont effacées. */
    private int construction;
    private int experiences;

    public static LaboData get(MinecraftServer server) {
        return server.overworld().getDataStorage()
                .computeIfAbsent(new SavedData.Factory<>(LaboData::new, LaboData::load, null), NAME);
    }

    private static LaboData load(CompoundTag tag, HolderLookup.Provider registries) {
        LaboData data = new LaboData();
        CompoundTag retours = tag.getCompound("retours");
        for (String cle : retours.getAllKeys()) {
            CompoundTag r = retours.getCompound(cle);
            ResourceLocation dim = ResourceLocation.tryParse(r.getString("dim"));
            if (dim != null) {
                data.retours.put(UUID.fromString(cle), new Retour(dim, r.getDouble("x"), r.getDouble("y"), r.getDouble("z"),
                        r.getFloat("yaw"), r.getFloat("pitch")));
            }
        }
        CompoundTag modes = tag.getCompound("modes");
        for (String cle : modes.getAllKeys()) {
            data.modes.put(UUID.fromString(cle), GameType.byId(modes.getInt(cle)));
        }
        CompoundTag sujets = tag.getCompound("sujets");
        for (String cle : sujets.getAllKeys()) {
            data.sujets.put(UUID.fromString(cle), sujets.getInt(cle));
        }
        data.versionConstruite = tag.getInt("version");
        data.construction = tag.getInt("construction");
        data.experiences = tag.getInt("experiences");
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        CompoundTag retours = new CompoundTag();
        this.retours.forEach((id, r) -> {
            CompoundTag t = new CompoundTag();
            t.putString("dim", r.dimension().toString());
            t.putDouble("x", r.x());
            t.putDouble("y", r.y());
            t.putDouble("z", r.z());
            t.putFloat("yaw", r.yaw());
            t.putFloat("pitch", r.pitch());
            retours.put(id.toString(), t);
        });
        tag.put("retours", retours);
        CompoundTag modes = new CompoundTag();
        this.modes.forEach((id, m) -> modes.putInt(id.toString(), m.getId()));
        tag.put("modes", modes);
        CompoundTag sujets = new CompoundTag();
        this.sujets.forEach((id, n) -> sujets.putInt(id.toString(), n));
        tag.put("sujets", sujets);
        tag.putInt("version", versionConstruite);
        tag.putInt("construction", construction);
        tag.putInt("experiences", experiences);
        return tag;
    }

    public Retour retour(UUID joueur) {
        return retours.get(joueur);
    }

    public void setRetour(UUID joueur, Retour retour) {
        if (retour == null) {
            retours.remove(joueur);
        } else {
            retours.put(joueur, retour);
        }
        setDirty();
    }

    public GameType mode(UUID joueur) {
        return modes.get(joueur);
    }

    public void setMode(UUID joueur, GameType mode) {
        if (mode == null) {
            modes.remove(joueur);
        } else {
            modes.put(joueur, mode);
        }
        setDirty();
    }

    /** Le numéro de sujet du joueur : attribué à sa première visite (le 12 s'est échappé, on commence au 13). */
    public int sujet(UUID joueur) {
        Integer n = sujets.get(joueur);
        if (n == null) {
            n = 13 + sujets.size();
            sujets.put(joueur, n);
            setDirty();
        }
        return n;
    }

    public int versionConstruite() {
        return versionConstruite;
    }

    public void setVersionConstruite(int version) {
        versionConstruite = version;
        setDirty();
    }

    public int construction() {
        return construction;
    }

    /** Une nouvelle construction commence : son numéro. */
    public int nouvelleConstruction() {
        construction++;
        setDirty();
        return construction;
    }

    /** Le numéro de la prochaine expérience sur la table. */
    public int experienceSuivante() {
        experiences++;
        setDirty();
        return 1046 + experiences;
    }
}
