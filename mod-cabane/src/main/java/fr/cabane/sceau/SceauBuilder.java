package fr.cabane.sceau;

import fr.cabane.CabaneEntities;
import fr.cabane.hachibi3.Hachibi3;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LightBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * La salle du sceau, d'après le croquis validé (la cage de Kyûbi dans la tête de Naruto, mais avec le Hachibi) : une
 * immense salle fermée, le sol couvert d'une fine couche d'eau rouge, et en travers une muraille de barreaux entre deux
 * piliers sombres couverts de spirales, le papier du sceau au milieu. Derrière, le Hachibi géant (hachibi3), enchaîné,
 * tourné vers nous.
 *
 * <p>La salle est centrée sur x = 0 ; les barreaux sont sur la ligne z = 0, la bête au nord (z négatif), nous au sud. Chaque
 * bloc se calcule par {@link #bloc} : la construction pose ce qui manque et efface le reste.
 */
public final class SceauBuilder {
    /** À changer après toute modification : la salle se reconstruit à la prochaine visite. */
    public static final int VERSION = 2;
    /** Le bas de la couche d'eau (les dalles) ; on marche à SOL + 0,5. */
    public static final int SOL = 64;
    /** La hauteur de la salle, celle du portail (sous le linteau), la demi-largeur intérieure. */
    static final int H = 96, H_PORTE = 82, LARGE = 70;
    /** Les piliers commencent à |x| = PILIER ; les barreaux vont de -BARRES à BARRES, tous les 5 blocs. */
    static final int PILIER = 52, BARRES = 50;
    /** Le fond de la cage (nord) et le mur derrière nous (sud). */
    static final int Z_FOND = -123, Z_AVANT = 46;
    /** Où se tient la bête : ses poings et sa tête restent bien derrière les barreaux. */
    static final int Z_BETE = -45;
    /** Un peu décalée, pour que sa tête tombe entre deux barreaux et non derrière le montant du milieu. */
    static final double X_BETE = 3.0;
    /** Toutes les entités posées par la salle. */
    public static final String TAG = "cabane_sceau";

    public static Vec3 arrivee() {
        return new Vec3(0.5, SOL + 0.5, 34.5);
    }

    /** On arrive tourné vers les barreaux (le nord). */
    public static final float ARRIVEE_YAW = 180F;

    /** Le bouton du retour, sur son socle, juste à droite de l'arrivée. */
    static final BlockPos SOCLE = new BlockPos(3, SOL, 35);

    private static final BlockState AIR = Blocks.AIR.defaultBlockState();
    private static final BlockState EAU = Blocks.MUD_BRICK_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.BOTTOM)
            .setValue(BlockStateProperties.WATERLOGGED, true);
    private static final BlockState BARRE = Blocks.POLISHED_BASALT.defaultBlockState();
    private static final BlockState BARRE_MILIEU = Blocks.POLISHED_DEEPSLATE.defaultBlockState();
    private static final BlockState PILIER_FOND = Blocks.POLISHED_BLACKSTONE.defaultBlockState();
    private static final BlockState PILIER_TRAIT = Blocks.PURPLE_TERRACOTTA.defaultBlockState();
    private static final BlockState PLAFOND = Blocks.DEEPSLATE_TILES.defaultBlockState();
    private static final BlockState SOUS_SOL = Blocks.PACKED_MUD.defaultBlockState();
    private static final BlockState[] MURS = {Blocks.SMOOTH_SANDSTONE.defaultBlockState(), Blocks.SANDSTONE.defaultBlockState(),
            Blocks.SMOOTH_SANDSTONE.defaultBlockState(), Blocks.MUD_BRICKS.defaultBlockState(), Blocks.SMOOTH_SANDSTONE.defaultBlockState()};

    static boolean dansLaSalle(double x, double y, double z) {
        return Math.abs(x) < LARGE + 3 && z > Z_FOND - 3 && z < Z_AVANT + 3 && y > SOL - 4 && y < SOL + H + 3;
    }

    /** Le bloc voulu en (x, y, z) de la dimension. */
    static BlockState bloc(int x, int y, int z) {
        int dy = y - SOL;
        boolean dedans = Math.abs(x) <= LARGE && z >= Z_FOND && z <= Z_AVANT;
        // la coque : sous-sol, plafond, murs (deux blocs d'épaisseur)
        if (dy < 0) {
            return SOUS_SOL;
        }
        if (dy >= H) {
            return PLAFOND;
        }
        if (!dedans) {
            return MURS[Math.floorMod(hash(x, y, z), MURS.length)];
        }
        // les deux piliers, de part et d'autre du portail, jusqu'au plafond
        if (Math.abs(x) >= PILIER && z >= -3 && z <= 2) {
            int u = Math.abs(x) == PILIER ? z + 40 : x;
            return spirale(u, dy) ? PILIER_TRAIT : PILIER_FOND;
        }
        // le linteau au-dessus des barreaux
        if (dy >= H_PORTE && z >= -2 && z <= 1) {
            return spirale(x, dy) ? PILIER_TRAIT : PILIER_FOND;
        }
        // les barreaux, et le montant du milieu où est collé le sceau
        if (z == 0 && dy < H_PORTE) {
            if (x == 0) {
                return BARRE_MILIEU;
            }
            if (Math.floorMod(x, 5) == 0 && Math.abs(x) <= BARRES) {
                return BARRE;
            }
            // entre les barreaux : on voit à travers, on ne passe pas
            return dy == 0 ? EAU : Blocks.BARRIER.defaultBlockState();
        }
        if ((z == -1 || z == 1) && x == 0 && dy < H_PORTE) {
            return BARRE_MILIEU;
        }
        if (dy == 0) {
            return x == SOCLE.getX() && z == SOCLE.getZ() ? PILIER_FOND : EAU;
        }
        if (dy == 1 && x == SOCLE.getX() && z == SOCLE.getZ()) {
            return Sceau.BOUTON.get().defaultBlockState();
        }
        // une lumière jaunâtre, faible, qui vient de partout et de nulle part
        if (dy == 4 && Math.floorMod(x, 10) == 5 && Math.floorMod(z, 10) == 5) {
            return Blocks.LIGHT.defaultBlockState().setValue(LightBlock.LEVEL, 14);
        }
        if ((dy == 40 || dy == 72) && Math.floorMod(x, 20) == 10 && Math.floorMod(z, 20) == 10) {
            return Blocks.LIGHT.defaultBlockState().setValue(LightBlock.LEVEL, 12);
        }
        return AIR;
    }

    /**
     * Les spirales des piliers : des volutes de 12 × 12, une sur deux retournée, qui s'enchaînent sur toute la hauteur.
     * u court le long de la face, v monte.
     */
    static boolean spirale(int u, int v) {
        int tu = Math.floorDiv(u, 12), tv = Math.floorDiv(v, 12);
        double a = Math.floorMod(u, 12) - 5.5, b = Math.floorMod(v, 12) - 5.5;
        if (((tu + tv) & 1) == 1) {
            a = -a;
        }
        double r = Math.sqrt(a * a + b * b);
        if (r > 6.2) {
            return false;
        }
        double theta = Math.atan2(b, a);
        if (theta < 0) {
            theta += Math.PI * 2;
        }
        double pas = 2.9, k = pas / (Math.PI * 2);
        double d = Math.floorMod((long) Math.floor((r - k * theta) * 1000), (long) (pas * 1000)) / 1000.0;
        return d < 1.05 && r > 0.8;
    }

    private static int hash(int x, int y, int z) {
        int h = x * 73856093 ^ y * 19349663 ^ z * 83492791;
        h ^= h >>> 13;
        return h * 0x5bd1e995 >>> 16;
    }

    // ---------------------------------------------------------------- la construction

    static void construire(ServerLevel l, int construction) {
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int x = -LARGE - 2; x <= LARGE + 2; x++) {
            for (int z = Z_FOND - 2; z <= Z_AVANT + 2; z++) {
                for (int y = SOL - 2; y <= SOL + H + 1; y++) {
                    BlockState voulu = bloc(x, y, z);
                    p.set(x, y, z);
                    if (l.getBlockState(p) != voulu) {
                        l.setBlock(p, voulu, Block.UPDATE_CLIENTS);
                    }
                }
            }
        }
        AABB zone = new AABB(-LARGE - 3, SOL - 3, Z_FOND - 3, LARGE + 4, SOL + H + 3, Z_AVANT + 4);
        for (Entity e : l.getEntities((Entity) null, zone, e -> e.getTags().contains(TAG) || e instanceof Hachibi3)) {
            e.discard();
        }
        poserBete(l, construction);
        poserSceau(l, construction);
    }

    /** Le Hachibi géant, debout dans l'eau au fond de la cage, tourné vers nous. */
    static void poserBete(ServerLevel l, int construction) {
        Hachibi3 bete = CabaneEntities.HACHIBI3.get().create(l);
        if (bete == null) {
            return;
        }
        bete.moveTo(X_BETE, SOL, Z_BETE + 0.5, 0F, 0F);
        bete.addTag(TAG);
        bete.addTag(tagConstruction(construction));
        l.addFreshEntity(bete);
    }

    /** Le papier du sceau collé sur le montant du milieu, lisible depuis notre côté (le sud). */
    private static void poserSceau(ServerLevel l, int construction) {
        Display.TextDisplay papier = EntityType.TEXT_DISPLAY.create(l);
        if (papier == null) {
            return;
        }
        var tag = new net.minecraft.nbt.CompoundTag();
        papier.saveWithoutId(tag);
        tag.putString("text", "{\"text\":\"封\",\"color\":\"#a01010\",\"bold\":true}");
        tag.putInt("background", 0xFFEFE6C8);
        tag.putString("billboard", "fixed");
        tag.putInt("line_width", 40);
        var transfo = new net.minecraft.nbt.CompoundTag();
        transfo.put("left_rotation", liste(0, 0, 0, 1));
        transfo.put("right_rotation", liste(0, 0, 0, 1));
        transfo.put("translation", liste(0, 0, 0));
        transfo.put("scale", liste(20, 34, 1));
        tag.put("transformation", transfo);
        var lum = new net.minecraft.nbt.CompoundTag();
        lum.putInt("block", 11);
        lum.putInt("sky", 0);
        tag.put("brightness", lum);
        papier.load(tag);
        papier.moveTo(0.5, SOL + 36, 2.03, 0F, 0F);
        papier.addTag(TAG);
        papier.addTag(tagConstruction(construction));
        l.addFreshEntity(papier);
    }

    private static net.minecraft.nbt.ListTag liste(float... v) {
        net.minecraft.nbt.ListTag t = new net.minecraft.nbt.ListTag();
        for (float f : v) {
            t.add(net.minecraft.nbt.FloatTag.valueOf(f));
        }
        return t;
    }

    public static String tagConstruction(int n) {
        return TAG + ".n" + n;
    }

    /** La hauteur de la surface de l'eau (où naissent les ronds dans l'eau). */
    static double surface() {
        return SOL + 0.9;
    }

    private SceauBuilder() {
    }
}
