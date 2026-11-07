package fr.cabane.mission;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import fr.cabane.Cabane;
import java.io.Reader;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Clearable;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CrossCollisionBlock;
import net.minecraft.world.level.block.FenceBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.TripWireBlock;
import net.minecraft.world.level.block.WallBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Une des six scènes des missions, telle que l'a exportée docs/missions/generateur/jeu.mjs (data/cabane/infiltration/&lt;id&gt;.json) :
 * ses blocs (une palette et un RLE), ses gens, sa cellule, sa sortie, ses objectifs, ses lasers, ses caméras, ses projecteurs, ses
 * pièges. Les coordonnées du fichier sont celles de la maquette (y = 0 au ras du sol) ; la scène est posée dans sa dimension à
 * ({@link #ox}, {@link #SOL}, {@link #oz}).
 */
final class Scene {
    /** Le y du monde qui correspond au y = 0 de la maquette. */
    static final int SOL = 64;
    private static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;
    private static final Map<String, Scene> CACHE = new HashMap<>();

    record Pnj(String type, String skin, Vec3 p, float lacet, String arme, double portee, double angle, List<Vec3> ronde, boolean fermee,
               boolean lanterne, boolean lampe, float taille) {
    }

    record Oeil(Vec3 p, float lacet, double portee, double angle, double balayage) {
    }

    record Objet(String id, String item, Vec3 p) {
    }

    record Piege(String type, AABB zone, AABB tronc, AABB frappe, int[] boite, int[] boiteTronc, String couvercle) {
    }

    record Texte(String texte, Vec3 p, float lacet, float taille, int couleur) {
    }

    final String id;
    final String titre;
    final boolean nuit;
    final int ox;
    final int oz;
    private final int[] origine;
    private final int[] taille;
    private final List<String> palette = new ArrayList<>();
    private final byte[] blocs;
    final Vec3 depart;
    final float departLacet;
    final Vec3 mechant;
    final float mechantLacet;
    final AABB sortie;
    final AABB grille;
    final Vec3 dedans;
    final Vec3 dehors;
    final List<Objet> objets = new ArrayList<>();
    final List<Pnj> pnj = new ArrayList<>();
    final List<Vec3[]> lasers = new ArrayList<>();
    final List<Oeil> cameras = new ArrayList<>();
    final List<Oeil> projecteurs = new ArrayList<>();
    final List<AABB> reservoirs = new ArrayList<>();
    final List<Piege> pieges = new ArrayList<>();
    final List<Texte> textes = new ArrayList<>();
    final int compteRebours;
    final boolean brume;
    final AABB gong;
    final Vec3 gongPoint;

    private Scene(String id, int ox, int oz, JsonObject j) {
        this.id = id;
        this.ox = ox;
        this.oz = oz;
        titre = j.get("titre").getAsString();
        nuit = "nuit".equals(j.get("dimension").getAsString());
        origine = ints(j.getAsJsonArray("origine"));
        taille = ints(j.getAsJsonArray("taille"));
        j.getAsJsonArray("palette").forEach(e -> palette.add(e.getAsString()));
        blocs = Base64.getDecoder().decode(j.get("blocs").getAsString());
        double[] d = doubles(j.getAsJsonArray("depart"));
        depart = point(d[0], d[1], d[2]);
        departLacet = (float) -d[3];
        double[] m = doubles(j.getAsJsonArray("mechant"));
        mechant = point(m[0], m[1], m[2]);
        mechantLacet = (float) -m[3];
        sortie = zone(ints(j.getAsJsonArray("sortie")));
        JsonObject c = j.getAsJsonObject("cellule");
        grille = zone(ints(c.getAsJsonArray("grille")));
        dedans = point(doubles(c.getAsJsonArray("dedans")));
        dehors = point(doubles(c.getAsJsonArray("dehors")));
        for (JsonElement e : j.getAsJsonArray("objets")) {
            JsonObject o = e.getAsJsonObject();
            objets.add(new Objet(o.get("id").getAsString(), o.get("item").getAsString(), point(doubles(o.getAsJsonArray("p")))));
        }
        for (JsonElement e : j.getAsJsonArray("pnj")) {
            JsonObject o = e.getAsJsonObject();
            double[] p = doubles(o.getAsJsonArray("p"));
            double[] vue = o.has("vue") ? doubles(o.getAsJsonArray("vue")) : new double[] {9, 50};
            List<Vec3> ronde = new ArrayList<>();
            if (o.has("ronde")) {
                for (JsonElement r : o.getAsJsonArray("ronde")) {
                    ronde.add(point(doubles(r.getAsJsonArray())));
                }
            }
            pnj.add(new Pnj(o.get("type").getAsString(), o.get("skin").getAsString(), point(p[0], p[1], p[2]), -o.get("lacet").getAsFloat(),
                    o.has("arme") ? o.get("arme").getAsString() : null, vue[0], vue[1], ronde, !o.has("ferme") || o.get("ferme").getAsBoolean(),
                    o.has("lanterne"), o.has("lampe"), o.has("taille") ? o.get("taille").getAsFloat() : 1F));
        }
        for (JsonElement e : j.getAsJsonArray("lasers")) {
            double[] l = doubles(e.getAsJsonArray());
            lasers.add(new Vec3[] {point(l[0], l[1], l[2]), point(l[3], l[4], l[5])});
        }
        for (String cle : new String[] {"cameras", "projecteurs"}) {
            for (JsonElement e : j.getAsJsonArray(cle)) {
                JsonObject o = e.getAsJsonObject();
                Oeil oeil = new Oeil(point(doubles(o.getAsJsonArray("p"))), -o.get("lacet").getAsFloat(), o.get("portee").getAsDouble(),
                        o.get("angle").getAsDouble(), o.get("balayage").getAsDouble());
                (cle.equals("cameras") ? cameras : projecteurs).add(oeil);
            }
        }
        for (JsonElement e : j.getAsJsonArray("reservoirs")) {
            reservoirs.add(zone(ints(e.getAsJsonArray())));
        }
        for (JsonElement e : j.getAsJsonArray("pieges")) {
            JsonObject o = e.getAsJsonObject();
            int[] z = ints(o.getAsJsonArray("zone"));
            int[] t = o.has("tronc") ? ints(o.getAsJsonArray("tronc")) : null;
            pieges.add(new Piege(o.get("type").getAsString(), zone(z), t == null ? null : zone(t),
                    o.has("frappe") ? zone(ints(o.getAsJsonArray("frappe"))) : null, z, t,
                    o.has("couvercle") ? o.get("couvercle").getAsString() : null));
        }
        for (JsonElement e : j.getAsJsonArray("textes")) {
            JsonObject o = e.getAsJsonObject();
            textes.add(new Texte(o.get("texte").getAsString(), point(doubles(o.getAsJsonArray("p"))), -o.get("lacet").getAsFloat(),
                    o.get("taille").getAsFloat(), o.get("couleur").getAsInt()));
        }
        compteRebours = j.get("compteRebours").getAsInt();
        brume = j.get("brume").getAsBoolean();
        if (j.has("gong")) {
            JsonObject g = j.getAsJsonObject("gong");
            gong = zone(ints(g.getAsJsonArray("zone")));
            gongPoint = point(doubles(g.getAsJsonArray("p")));
        } else {
            gong = null;
            gongPoint = null;
        }
    }

    /** La scène {@code id}, lue une fois pour toutes dans les données du mod. */
    static Scene charger(MinecraftServer server, String id, int ox, int oz) {
        return CACHE.computeIfAbsent(id, k -> {
            try (Reader r = server.getResourceManager().getResourceOrThrow(Cabane.id("infiltration/" + id + ".json")).openAsReader()) {
                return new Scene(id, ox, oz, JsonParser.parseReader(r).getAsJsonObject());
            } catch (Exception e) {
                throw new IllegalStateException("Scène de mission illisible : " + id, e);
            }
        });
    }

    // ------------------------------------------------------------------ les coordonnées

    Vec3 point(double x, double y, double z) {
        return new Vec3(ox + x, SOL + y, oz + z);
    }

    private Vec3 point(double[] p) {
        return point(p[0], p[1], p[2]);
    }

    BlockPos bloc(int x, int y, int z) {
        return new BlockPos(ox + x, SOL + y, oz + z);
    }

    /** Une zone de blocs (bornes comprises) de la maquette, en coordonnées du monde. */
    AABB zone(int[] z) {
        return new AABB(ox + z[0], SOL + z[1], oz + z[2], ox + z[3] + 1, SOL + z[4] + 1, oz + z[5] + 1);
    }

    /** Toute la scène (avec ses bords), en coordonnées du monde. */
    AABB etendue() {
        return new AABB(ox + origine[0], SOL + origine[1], oz + origine[2], ox + origine[0] + taille[0], SOL + origine[1] + taille[1],
                oz + origine[2] + taille[2]);
    }

    String tag() {
        return "mission." + id;
    }

    String tag(int numero) {
        return "mission." + id + ".n" + numero;
    }

    // ------------------------------------------------------------------ la construction

    /** Remet la scène dans l'état de la maquette (seuls les blocs qui ont changé sont reposés), et repose ses textes. */
    void construire(ServerLevel level, int numero) {
        AABB e = etendue();
        for (int cx = ((int) e.minX) >> 4; cx <= ((int) e.maxX) >> 4; cx++) {
            for (int cz = ((int) e.minZ) >> 4; cz <= ((int) e.maxZ) >> 4; cz++) {
                level.getChunk(cx, cz);
            }
        }
        for (Entity ent : level.getEntities((Entity) null, e.inflate(4), x -> x.getTags().contains(tag()) || x instanceof ItemEntity
                || x instanceof AbstractArrow || x instanceof FallingBlockEntity)) {
            ent.discard();
        }
        var registre = level.holderLookup(Registries.BLOCK);
        BlockState[] etats = new BlockState[palette.size()];
        for (int i = 0; i < etats.length; i++) {
            try {
                etats[i] = BlockStateParser.parseForBlock(registre, palette.get(i), false).blockState();
            } catch (Exception ex) {
                Cabane.LOGGER.warn("Mission {} : bloc inconnu {}", id, palette.get(i));
                etats[i] = Blocks.AIR.defaultBlockState();
            }
        }
        int sx = taille[0], sy = taille[1], sz = taille[2];
        List<BlockPos> raccords = new ArrayList<>();
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        int pos = 0, cellule = 0, total = sx * sy * sz;
        while (pos < blocs.length && cellule < total) {
            int[] lu = new int[2];
            pos = varint(blocs, pos, lu);
            int indice = lu[0];
            pos = varint(blocs, pos, lu);
            int n = lu[0];
            BlockState etat = etats[indice];
            for (int k = 0; k < n && cellule < total; k++, cellule++) {
                int x = cellule % sx, z = (cellule / sx) % sz, y = cellule / (sx * sz);
                p.set(ox + origine[0] + x, SOL + origine[1] + y, oz + origine[2] + z);
                BlockState avant = level.getBlockState(p);
                if (avant != etat) {
                    if (avant.hasBlockEntity()) {
                        Clearable.tryClear(level.getBlockEntity(p));
                    }
                    level.setBlock(p, etat, FLAGS);
                }
                Block b = etat.getBlock();
                if (b instanceof CrossCollisionBlock || b instanceof FenceBlock || b instanceof WallBlock || b instanceof StairBlock
                        || b instanceof TripWireBlock) {
                    raccords.add(p.immutable());
                }
            }
        }
        for (BlockPos r : raccords) {
            BlockState s = level.getBlockState(r);
            BlockState raccorde = Block.updateFromNeighbourShapes(s, level, r);
            if (raccorde != s && !raccorde.isAir()) {
                level.setBlock(r, raccorde, FLAGS);
            }
        }
        for (Texte t : textes) {
            texte(level, numero, t);
        }
    }

    private void texte(ServerLevel level, int numero, Texte t) {
        CompoundTag tag = new CompoundTag();
        tag.putString("id", "minecraft:text_display");
        tag.putString("text", Component.Serializer.toJson(Component.literal(t.texte()).withStyle(s -> s.withColor(t.couleur()).withBold(true)),
                level.registryAccess()));
        tag.putString("billboard", "fixed");
        tag.putString("alignment", "center");
        tag.putInt("background", 0);
        tag.putBoolean("shadow", true);
        CompoundTag lumiere = new CompoundTag();
        lumiere.putInt("block", 15);
        lumiere.putInt("sky", 15);
        tag.put("brightness", lumiere);
        CompoundTag tr = new CompoundTag();
        tr.put("left_rotation", floats(0, 0, 0, 1));
        tr.put("right_rotation", floats(0, 0, 0, 1));
        tr.put("translation", floats(0, 0, 0));
        tr.put("scale", floats(t.taille(), t.taille(), t.taille()));
        tag.put("transformation", tr);
        ListTag tags = new ListTag();
        tags.add(net.minecraft.nbt.StringTag.valueOf(tag()));
        tags.add(net.minecraft.nbt.StringTag.valueOf(tag(numero)));
        tag.put("Tags", tags);
        Entity ent = EntityType.loadEntityRecursive(tag, level, x -> {
            x.moveTo(t.p().x, t.p().y, t.p().z, t.lacet(), 0F);
            return x;
        });
        if (ent != null) {
            level.addFreshEntity(ent);
        }
    }

    // ------------------------------------------------------------------ outils

    private static ListTag floats(float... v) {
        ListTag l = new ListTag();
        for (float f : v) {
            l.add(FloatTag.valueOf(f));
        }
        return l;
    }

    private static int varint(byte[] b, int pos, int[] sortie) {
        int v = 0, decalage = 0;
        while (true) {
            int o = b[pos++] & 0xFF;
            v |= (o & 0x7F) << decalage;
            if ((o & 0x80) == 0) {
                break;
            }
            decalage += 7;
        }
        sortie[0] = v;
        return pos;
    }

    private static int[] ints(JsonArray a) {
        int[] r = new int[a.size()];
        for (int i = 0; i < r.length; i++) {
            r[i] = a.get(i).getAsInt();
        }
        return r;
    }

    private static double[] doubles(JsonArray a) {
        double[] r = new double[a.size()];
        for (int i = 0; i < r.length; i++) {
            r[i] = a.get(i).getAsDouble();
        }
        return r;
    }
}
