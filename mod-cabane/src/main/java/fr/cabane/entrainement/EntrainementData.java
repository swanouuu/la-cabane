package fr.cabane.entrainement;

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
 * Ce que la salle d'entraînement retient : d'où vient chaque joueur parti à la salle (pour le bouton du retour), son mode
 * de jeu d'avant, la version construite de la salle et le numéro de la construction (les pnj d'une construction
 * précédente sont refusés).
 */
public class EntrainementData extends SavedData {
    private static final String NAME = "cabane_entrainement";

    public record Retour(ResourceLocation dimension, double x, double y, double z, float yaw, float pitch) {
    }

    private final Map<UUID, Retour> retours = new HashMap<>();
    private final Map<UUID, GameType> modes = new HashMap<>();
    private int versionConstruite;
    private int construction;

    public static EntrainementData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(new SavedData.Factory<>(EntrainementData::new, EntrainementData::load, null), NAME);
    }

    private static EntrainementData load(CompoundTag tag, HolderLookup.Provider registries) {
        EntrainementData data = new EntrainementData();
        CompoundTag retours = tag.getCompound("retours");
        for (String cle : retours.getAllKeys()) {
            CompoundTag r = retours.getCompound(cle);
            ResourceLocation dim = ResourceLocation.tryParse(r.getString("dim"));
            if (dim != null) {
                data.retours.put(UUID.fromString(cle), new Retour(dim, r.getDouble("x"), r.getDouble("y"), r.getDouble("z"), r.getFloat("yaw"), r.getFloat("pitch")));
            }
        }
        CompoundTag modes = tag.getCompound("modes");
        for (String cle : modes.getAllKeys()) {
            data.modes.put(UUID.fromString(cle), GameType.byId(modes.getInt(cle)));
        }
        data.versionConstruite = tag.getInt("version");
        data.construction = tag.getInt("construction");
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
        tag.putInt("version", versionConstruite);
        tag.putInt("construction", construction);
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

    /** Commence une nouvelle construction et rend son numéro. */
    public int nouvelleConstruction() {
        construction++;
        setDirty();
        return construction;
    }
}
