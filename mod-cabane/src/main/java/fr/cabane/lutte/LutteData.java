package fr.cabane.lutte;

import fr.cabane.lutte.RingBlocs.Couleur;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.phys.Vec3;

/**
 * Ce que le catch retient : tous les rings posés (dans quelle dimension, où, quelle taille, la couleur des cordes),
 * d'où vient chaque joueur parti à la salle, son mode de jeu d'avant, et la version construite de la salle.
 */
public class LutteData extends SavedData {
    private static final String NAME = "cabane_catch";

    /**
     * Un ring : le coin nord-ouest du tablier (x0, y0, z0), sa taille (l le long de x, p le long de z), la couleur des
     * cordes ; salle : celui de la salle (on ne le range pas).
     */
    public record Ring(ResourceLocation dim, int x0, int y0, int z0, int l, int p, Couleur couleur, boolean salle) {
        /** Le dessus du tapis. */
        public int dessus() {
            return y0 + 2;
        }

        public Vec3 centre() {
            return new Vec3(x0 + l / 2.0, dessus(), z0 + p / 2.0);
        }

        /** Est-on sur le tapis (entre les cordes, ou presque) ? */
        public boolean dedans(Vec3 pos) {
            return pos.x >= x0 && pos.x <= x0 + l && pos.z >= z0 && pos.z <= z0 + p && pos.y >= dessus() - 0.6 && pos.y <= dessus() + 4;
        }

        /** Ce bloc fait-il partie du ring (le plancher, les cordes, les poteaux, les marches tout autour) ? */
        public boolean contient(BlockPos b) {
            return b.getX() >= x0 - 1 && b.getX() <= x0 + l && b.getZ() >= z0 - 1 && b.getZ() <= z0 + p && b.getY() >= y0 && b.getY() <= y0 + 2;
        }

        public String cle() {
            return dim + "@" + x0 + "," + y0 + "," + z0;
        }
    }

    public record Retour(ResourceLocation dimension, double x, double y, double z, float yaw, float pitch) {
    }

    private final List<Ring> rings = new ArrayList<>();
    private final Map<UUID, Retour> retours = new HashMap<>();
    private final Map<UUID, GameType> modes = new HashMap<>();
    private int versionConstruite;

    public static LutteData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(new SavedData.Factory<>(LutteData::new, LutteData::load, null), NAME);
    }

    private static LutteData load(CompoundTag tag, HolderLookup.Provider registries) {
        LutteData data = new LutteData();
        for (Tag t : tag.getList("rings", Tag.TAG_COMPOUND)) {
            CompoundTag r = (CompoundTag) t;
            ResourceLocation dim = ResourceLocation.tryParse(r.getString("dim"));
            if (dim != null) {
                data.rings.add(new Ring(dim, r.getInt("x"), r.getInt("y"), r.getInt("z"), r.getInt("l"), r.getInt("p"),
                        Couleur.values()[Math.floorMod(r.getInt("couleur"), Couleur.values().length)], r.getBoolean("salle")));
            }
        }
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
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag liste = new ListTag();
        for (Ring r : rings) {
            CompoundTag t = new CompoundTag();
            t.putString("dim", r.dim().toString());
            t.putInt("x", r.x0());
            t.putInt("y", r.y0());
            t.putInt("z", r.z0());
            t.putInt("l", r.l());
            t.putInt("p", r.p());
            t.putInt("couleur", r.couleur().ordinal());
            t.putBoolean("salle", r.salle());
            liste.add(t);
        }
        tag.put("rings", liste);
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
        return tag;
    }

    public List<Ring> rings() {
        return rings;
    }

    public void ajouter(Ring r) {
        rings.add(r);
        setDirty();
    }

    public void enlever(Ring r) {
        rings.remove(r);
        setDirty();
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
}
