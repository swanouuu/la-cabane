package fr.cabane.backrooms;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * Ce que les backrooms retiennent : si le rêve est en cours, et pour chaque joueur parti dedans, l'endroit exact d'où il
 * vient, son mode de jeu, et ce qu'il avait sur lui (inventaire, niveaux, vie, faim), rendu tel quel au réveil.
 */
public class BackroomsData extends SavedData {
    private static final String NAME = "cabane_backrooms";

    /** Le joueur avant le rêve. */
    public record Avant(ResourceLocation dimension, double x, double y, double z, float yaw, float pitch, GameType mode, ListTag inventaire,
            int niveau, float progres, float vie, int faim, float saturation) {
        CompoundTag enTag() {
            CompoundTag t = new CompoundTag();
            t.putString("dim", dimension.toString());
            t.putDouble("x", x);
            t.putDouble("y", y);
            t.putDouble("z", z);
            t.putFloat("yaw", yaw);
            t.putFloat("pitch", pitch);
            t.putInt("mode", mode.getId());
            t.put("inv", inventaire);
            t.putInt("niveau", niveau);
            t.putFloat("progres", progres);
            t.putFloat("vie", vie);
            t.putInt("faim", faim);
            t.putFloat("saturation", saturation);
            return t;
        }

        static Avant deTag(CompoundTag t) {
            ResourceLocation dim = ResourceLocation.tryParse(t.getString("dim"));
            return dim == null ? null : new Avant(dim, t.getDouble("x"), t.getDouble("y"), t.getDouble("z"), t.getFloat("yaw"), t.getFloat("pitch"),
                    GameType.byId(t.getInt("mode")), t.getList("inv", Tag.TAG_COMPOUND), t.getInt("niveau"), t.getFloat("progres"),
                    t.getFloat("vie"), t.getInt("faim"), t.getFloat("saturation"));
        }
    }

    private final Map<UUID, Avant> avants = new HashMap<>();
    private boolean actif;

    public static BackroomsData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(new SavedData.Factory<>(BackroomsData::new, BackroomsData::load, null), NAME);
    }

    private static BackroomsData load(CompoundTag tag, HolderLookup.Provider registries) {
        BackroomsData data = new BackroomsData();
        CompoundTag avants = tag.getCompound("avants");
        for (String cle : avants.getAllKeys()) {
            Avant a = Avant.deTag(avants.getCompound(cle));
            if (a != null) {
                data.avants.put(UUID.fromString(cle), a);
            }
        }
        data.actif = tag.getBoolean("actif");
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        CompoundTag avants = new CompoundTag();
        this.avants.forEach((id, a) -> avants.put(id.toString(), a.enTag()));
        tag.put("avants", avants);
        tag.putBoolean("actif", actif);
        return tag;
    }

    public Avant avant(UUID joueur) {
        return avants.get(joueur);
    }

    public void setAvant(UUID joueur, Avant avant) {
        if (avant == null) {
            avants.remove(joueur);
        } else {
            avants.put(joueur, avant);
        }
        setDirty();
    }

    public Map<UUID, Avant> avants() {
        return avants;
    }

    public boolean actif() {
        return actif;
    }

    public void setActif(boolean actif) {
        this.actif = actif;
        setDirty();
    }
}
