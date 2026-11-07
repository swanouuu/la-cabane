package fr.cabane.mission;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.saveddata.SavedData;

/** Ce que les missions retiennent : d'où vient chaque joueur, son mode de jeu d'avant, le numéro de la dernière construction de chaque scène. */
public class MissionData extends SavedData {
    private static final String NAME = "cabane_infiltration";

    /** L'endroit exact d'où l'on est parti : dimension, position et regard. */
    public record Retour(ResourceLocation dimension, double x, double y, double z, float yaw, float pitch) {
    }

    private final Map<UUID, Retour> retours = new HashMap<>();
    private final Map<UUID, GameType> modes = new HashMap<>();
    private final Map<String, Integer> constructions = new HashMap<>();

    public static MissionData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(new SavedData.Factory<>(MissionData::new, MissionData::load, null), NAME);
    }

    private static MissionData load(CompoundTag tag, HolderLookup.Provider registries) {
        MissionData data = new MissionData();
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
        CompoundTag c = tag.getCompound("constructions");
        for (String cle : c.getAllKeys()) {
            data.constructions.put(cle, c.getInt(cle));
        }
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
        CompoundTag c = new CompoundTag();
        constructions.forEach(c::putInt);
        tag.put("constructions", c);
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

    public int construction(String scene) {
        return constructions.getOrDefault(scene, 0);
    }

    public int nouvelleConstruction(String scene) {
        int n = construction(scene) + 1;
        constructions.put(scene, n);
        setDirty();
        return n;
    }
}
