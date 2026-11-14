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
    /** Les donjons dont le boss est mort (jusqu au prochain groupe), sauf celui d Ugrak (vaincu). */
    private final java.util.Set<String> vaincus = new java.util.HashSet<>();
    /** La version construite de chaque donjon tracé. */
    private final Map<String, Integer> versions = new HashMap<>();
    /** Quand chaque joueur pourra de nouveau entrer dans un portail (millisecondes). */
    private final Map<UUID, Long> recharges = new HashMap<>();
    /** Le dernier donjon de chaque joueur (pour ne pas lui redonner le même). */
    private final Map<UUID, String> derniers = new HashMap<>();

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
        net.minecraft.nbt.ListTag v = tag.getList("vaincus", net.minecraft.nbt.Tag.TAG_STRING);
        for (int k = 0; k < v.size(); k++) {
            data.vaincus.add(v.getString(k));
        }
        CompoundTag ver = tag.getCompound("versions");
        for (String cle : ver.getAllKeys()) {
            data.versions.put(cle, ver.getInt(cle));
        }
        CompoundTag rec = tag.getCompound("recharges");
        for (String cle : rec.getAllKeys()) {
            data.recharges.put(UUID.fromString(cle), rec.getLong(cle));
        }
        CompoundTag der = tag.getCompound("derniers");
        for (String cle : der.getAllKeys()) {
            data.derniers.put(UUID.fromString(cle), der.getString(cle));
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
        tag.putInt("version", versionConstruite);
        tag.putBoolean("vaincu", vaincu);
        tag.putBoolean("evenement", evenement);
        net.minecraft.nbt.ListTag v = new net.minecraft.nbt.ListTag();
        for (String t : vaincus) {
            v.add(net.minecraft.nbt.StringTag.valueOf(t));
        }
        tag.put("vaincus", v);
        CompoundTag ver = new CompoundTag();
        versions.forEach(ver::putInt);
        tag.put("versions", ver);
        CompoundTag rec = new CompoundTag();
        long maintenant = System.currentTimeMillis();
        recharges.forEach((id, t) -> {
            if (t > maintenant) {
                rec.putLong(id.toString(), t);
            }
        });
        tag.put("recharges", rec);
        CompoundTag der = new CompoundTag();
        derniers.forEach((id, t) -> der.putString(id.toString(), t));
        tag.put("derniers", der);
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

    public boolean vaincu(TypeDonjon type) {
        return type == TypeDonjon.OGRE ? vaincu : vaincus.contains(type.id());
    }

    public void setVaincu(TypeDonjon type, boolean v) {
        if (type == TypeDonjon.OGRE) {
            vaincu = v;
        } else if (v) {
            vaincus.add(type.id());
        } else {
            vaincus.remove(type.id());
        }
        setDirty();
    }

    public int version(TypeDonjon type) {
        return versions.getOrDefault(type.id(), 0);
    }

    public void setVersion(TypeDonjon type, int version) {
        versions.put(type.id(), version);
        setDirty();
    }

    /** Dans combien de millisecondes ce joueur pourra rentrer dans un portail (0 : tout de suite). */
    public long recharge(UUID joueur) {
        return Math.max(0, recharges.getOrDefault(joueur, 0L) - System.currentTimeMillis());
    }

    public void setRecharge(UUID joueur, long dureeMs) {
        recharges.put(joueur, System.currentTimeMillis() + dureeMs);
        setDirty();
    }

    public TypeDonjon dernier(UUID joueur) {
        String d = derniers.get(joueur);
        return d == null ? null : TypeDonjon.parId(d);
    }

    public void setDernier(UUID joueur, TypeDonjon type) {
        derniers.put(joueur, type.id());
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
