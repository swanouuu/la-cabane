package fr.cabane.donjon;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.saveddata.SavedData;

/** Ce que le donjon retient : d'où vient chaque joueur, son mode de jeu d'avant, et la version construite du donjon. */
public class DonjonData extends SavedData {
    private static final String NAME = "cabane_donjon";

    /** L'endroit exact d'où l'on est parti : dimension, position et regard. */
    public record Retour(ResourceLocation dimension, double x, double y, double z, float yaw, float pitch) {
    }

    private final Map<UUID, Retour> retours = new HashMap<>();
    private final Map<UUID, GameType> modes = new HashMap<>();
    private int versionConstruite;
    /** Le boss est mort : le donjon est terminé (jusqu'au prochain groupe). */
    private boolean vaincu;
    /** L'hôte a lancé l'événement (/cabane evenement jinwoo) : le portail est ouvert, Jin-woo emmène les groupes. */
    private boolean evenement;

    public static DonjonData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(new SavedData.Factory<>(DonjonData::new, DonjonData::load, null), NAME);
    }

    private static DonjonData load(CompoundTag tag, HolderLookup.Provider registries) {
        DonjonData data = new DonjonData();
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
        data.vaincu = tag.getBoolean("vaincu");
        data.evenement = tag.getBoolean("evenement");
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
        tag.putBoolean("vaincu", vaincu);
        tag.putBoolean("evenement", evenement);
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

    public boolean evenement() {
        return evenement;
    }

    public void setEvenement(boolean e) {
        evenement = e;
        setDirty();
    }

    public boolean vaincu() {
        return vaincu;
    }

    public void setVaincu(boolean v) {
        vaincu = v;
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
