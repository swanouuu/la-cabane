package fr.cabane.arene;

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
 * Ce que l'arène retient : d'où vient chaque joueur (pour le bouton rouge de la salle d'entrée), son mode de jeu d'avant,
 * le stuff mis de côté de chaque combattant (rendu en sortant du combat, même après un plantage) et la version construite.
 */
public class AreneData extends SavedData {
    private static final String NAME = "cabane_arene";

    public record Retour(ResourceLocation dimension, double x, double y, double z, float yaw, float pitch) {
    }

    private final Map<UUID, Retour> retours = new HashMap<>();
    private final Map<UUID, GameType> modes = new HashMap<>();
    private final Map<UUID, CompoundTag> rangements = new HashMap<>();
    private int versionConstruite;

    public static AreneData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(new SavedData.Factory<>(AreneData::new, AreneData::load, null), NAME);
    }

    private static AreneData load(CompoundTag tag, HolderLookup.Provider registries) {
        AreneData data = new AreneData();
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
        CompoundTag rangements = tag.getCompound("rangements");
        for (String cle : rangements.getAllKeys()) {
            data.rangements.put(UUID.fromString(cle), rangements.getCompound(cle));
        }
        data.versionConstruite = tag.getInt("version");
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
        CompoundTag rangements = new CompoundTag();
        this.rangements.forEach((id, r) -> rangements.put(id.toString(), r));
        tag.put("rangements", rangements);
        tag.putInt("version", versionConstruite);
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

    /** Le stuff mis de côté d'un combattant (null : rien de rangé). */
    public CompoundTag rangement(UUID joueur) {
        return rangements.get(joueur);
    }

    public void setRangement(UUID joueur, CompoundTag r) {
        if (r == null) {
            rangements.remove(joueur);
        } else {
            rangements.put(joueur, r);
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
}
