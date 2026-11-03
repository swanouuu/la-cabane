package fr.cabane.hub;

import fr.cabane.Cabane;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * L'aéroport du départ, dans sa propre dimension (cabane:aeroport : une plaine d'herbe à perte de vue, sous un ciel de
 * fin de matinée qui ne change jamais) : la piste, le tarmac et sa porte 12, l'aérogare et sa passerelle, la tour de
 * contrôle, quelques véhicules de piste. L'avion, lui, n'est pas en blocs : la cinématique le dessine (il roule, décolle,
 * vole). Personne ne s'y promène : on n'y passe que le temps de la cinématique, cachés dans une cave sous le tarmac,
 * pendant que la caméra filme.
 *
 * <p>Coordonnées absolues : l'avion est garé le long de z = 0, le nez vers l'est ; l'aérogare est au nord, la piste au sud.
 */
public final class AeroportBuilder {
    /** À augmenter quand l'aéroport change : il est reconstruit au décollage suivant. */
    public static final int VERSION = 3;
    /**
     * Le niveau où l'on marche (l'herbe est juste dessous). Pas plus bas que le niveau de la mer : en dessous, le jeu
     * assombrit le brouillard et tout le bas du ciel, comme dans une grotte.
     */
    public static final int SOL = 64;
    /**
     * Où attendent les passagers pendant la cinématique : une cage de barrières invisible, au-dessus du milieu de la zone
     * filmée (et pas sous terre, pour la même raison).
     */
    public static final BlockPos ATTENTE = new BlockPos(0, SOL + 60, 20);
    /** Le milieu de la piste (elle court d'ouest en est). */
    public static final int PISTE_Z = 38;
    public static final int PISTE_X = 140;
    /** La façade vitrée de l'aérogare, côté tarmac. */
    public static final int FACADE_Z = -22;
    public static final String TAG = "cabane_aeroport";

    private static final int X1 = -150;
    private static final int X2 = 150;
    private static final int Z1 = -60;
    private static final int Z2 = 62;
    private static final int HAUT = SOL + 35;
    private static final int FLAGS = Block.UPDATE_CLIENTS;
    private static final BlockState AIR = Blocks.AIR.defaultBlockState();

    private final ServerLevel level;
    private final BlockPos.MutableBlockPos curseur = new BlockPos.MutableBlockPos();

    private AeroportBuilder(ServerLevel level) {
        this.level = level;
    }

    /** Construit (ou reconstruit) tout l'aéroport, d'un coup : une plaine plate se génère très vite. */
    public static void construire(ServerLevel level) {
        long debut = System.currentTimeMillis();
        AeroportBuilder b = new AeroportBuilder(level);
        b.sol();
        b.aerogare();
        b.passerelle();
        b.tour();
        b.vehicules();
        b.attente();
        b.textes();
        Cabane.LOGGER.info("Aéroport du décollage construit (version {}) en {} ms", VERSION, System.currentTimeMillis() - debut);
    }

    private void pose(int x, int y, int z, BlockState s) {
        curseur.set(x, y, z);
        if (level.getBlockState(curseur) != s) {
            level.setBlock(curseur, s, FLAGS);
        }
    }

    private void boite(int x1, int y1, int z1, int x2, int y2, int z2, BlockState s) {
        for (int x = x1; x <= x2; x++) {
            for (int y = y1; y <= y2; y++) {
                for (int z = z1; z <= z2; z++) {
                    pose(x, y, z, s);
                }
            }
        }
    }

    // ------------------------------------------------------------------ le sol : herbe, tarmac, piste

    private void sol() {
        for (int x = X1; x <= X2; x++) {
            for (int z = Z1; z <= Z2; z++) {
                pose(x, SOL - 1, z, revetement(x, z));
                // tout ce qui dépasse du sol disparaît (une ancienne version de l'aéroport)
                for (int y = SOL; y <= HAUT; y++) {
                    curseur.set(x, y, z);
                    if (!level.getBlockState(curseur).isAir()) {
                        level.setBlock(curseur, AIR, FLAGS);
                    }
                }
            }
        }
    }

    private static BlockState revetement(int x, int z) {
        BlockState blanc = Blocks.WHITE_CONCRETE.defaultBlockState();
        BlockState jaune = Blocks.YELLOW_CONCRETE.defaultBlockState();
        BlockState lumiere = Blocks.SEA_LANTERN.defaultBlockState();
        int ax = Math.abs(x);
        // la piste : 13 de large, bandes latérales, axe en pointillés, seuils « touches de piano », marques de visée
        if (ax <= PISTE_X && Math.abs(z - PISTE_Z) <= 6) {
            int dz = z - PISTE_Z;
            if (Math.abs(dz) == 6) {
                return blanc;
            }
            if (ax >= 131 && ax <= 138 && dz != 0 && Math.floorMod(dz + 5, 3) != 2) {
                return blanc;
            }
            if (ax >= 100 && ax <= 112 && (Math.abs(dz) == 3 || Math.abs(dz) == 4)) {
                return blanc;
            }
            if (dz == 0 && ax < 126 && Math.floorMod(x, 10) < 5) {
                return blanc;
            }
            return Blocks.GRAY_CONCRETE.defaultBlockState();
        }
        // les feux de bord de piste et de bout de piste
        if (ax <= PISTE_X && Math.abs(z - PISTE_Z) == 7 && Math.floorMod(x, 12) == 0) {
            return lumiere;
        }
        if (ax == PISTE_X + 1 && Math.abs(z - PISTE_Z) <= 5 && Math.floorMod(z, 2) == 0) {
            return lumiere;
        }
        // la voie de circulation, du tarmac jusqu'à la piste, avec sa ligne jaune
        if (x >= 38 && x <= 46 && z >= 22 && z <= 31) {
            return x == 42 ? jaune : Blocks.GRAY_CONCRETE.defaultBlockState();
        }
        // l'intérieur de l'aérogare
        if (ax <= 34 && z >= -46 && z <= FACADE_Z) {
            return Blocks.POLISHED_ANDESITE.defaultBlockState();
        }
        // le tarmac : dalles de béton clair, la ligne de guidage de la porte 12 et la barre d'arrêt du nez
        if (ax <= 44 && z >= -21 && z <= 21) {
            if (z == 0 && x >= -14 && x <= 20 || x == 21 && Math.abs(z) <= 3) {
                return jaune;
            }
            if (z == 0 && x > 21 && x <= 42 || x == 42 && z > 0) {
                return jaune;
            }
            if (Math.floorMod(x, 8) == 0 || Math.floorMod(z, 8) == 0) {
                return Blocks.LIGHT_GRAY_CONCRETE_POWDER.defaultBlockState();
            }
            return Blocks.LIGHT_GRAY_CONCRETE.defaultBlockState();
        }
        return Blocks.GRASS_BLOCK.defaultBlockState();
    }

    // ------------------------------------------------------------------ l'aérogare

    private void aerogare() {
        BlockState blanc = Blocks.WHITE_CONCRETE.defaultBlockState();
        BlockState gris = Blocks.LIGHT_GRAY_CONCRETE.defaultBlockState();
        BlockState vitre = Blocks.LIGHT_BLUE_STAINED_GLASS.defaultBlockState();
        int x1 = -34, x2 = 34, z1 = -46, z2 = FACADE_Z, haut = SOL + 12;
        for (int x = x1; x <= x2; x++) {
            for (int z = z1; z <= z2; z++) {
                boolean bord = x == x1 || x == x2 || z == z1 || z == z2;
                for (int y = SOL; y < haut; y++) {
                    BlockState s = AIR;
                    if (z == z2) {
                        // la grande façade vitrée, ses montants et ses bandeaux
                        s = Math.floorMod(x, 6) == 0 || y == SOL + 3 || y == haut - 1 || x == x1 || x == x2 ? blanc : vitre;
                    } else if (bord) {
                        s = y == SOL + 6 ? gris : (y == SOL + 4 || y == SOL + 8) && Math.floorMod(x + z, 4) != 0 ? vitre : blanc;
                    } else if (y == SOL + 3 && z >= -34) {
                        // l'étage des départs, au niveau de la passerelle
                        s = Blocks.POLISHED_DIORITE.defaultBlockState();
                    }
                    pose(x, y, z, s);
                }
                // le toit, ses lumières et son parapet
                boolean lampe = !bord && Math.floorMod(x, 6) == 3 && Math.floorMod(z, 6) == 2;
                pose(x, haut, z, lampe ? Blocks.SEA_LANTERN.defaultBlockState() : gris);
                if (bord) {
                    pose(x, haut + 1, z, blanc);
                }
            }
        }
        // à l'étage, des rangées de sièges face aux vitres
        for (int x = -28; x <= 28; x++) {
            if (x >= 5 && x <= 12 || Math.floorMod(x, 9) == 0) {
                continue;
            }
            for (int z : new int[] {-27, -31}) {
                pose(x, SOL + 4, z, Blocks.QUARTZ_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.NORTH));
            }
        }
        for (int x : new int[] {-30, -16, 18, 30}) {
            pose(x, SOL + 4, -24, Blocks.POTTED_BAMBOO.defaultBlockState());
        }
        // la porte de la passerelle
        boite(8, SOL + 4, FACADE_Z, 9, SOL + 6, FACADE_Z, AIR);
        // au rez-de-chaussée, les comptoirs d'enregistrement
        for (int x = -24; x <= 24; x += 8) {
            boite(x, SOL, -40, x + 4, SOL, -40, Blocks.SMOOTH_QUARTZ.defaultBlockState());
        }
    }

    // ------------------------------------------------------------------ la passerelle de la porte 12

    private void passerelle() {
        BlockState gris = Blocks.LIGHT_GRAY_CONCRETE.defaultBlockState();
        BlockState fonce = Blocks.GRAY_CONCRETE.defaultBlockState();
        BlockState soufflet = Blocks.BLACK_CONCRETE.defaultBlockState();
        for (int z = FACADE_Z + 1; z <= -4; z++) {
            boolean bout = z >= -5;
            for (int x = 7; x <= 10; x++) {
                pose(x, SOL + 3, z, bout ? soufflet : fonce);
                pose(x, SOL + 7, z, bout ? soufflet : x >= 8 && x <= 9 && Math.floorMod(z, 4) == 0 ? Blocks.SEA_LANTERN.defaultBlockState() : gris);
            }
            for (int y = SOL + 4; y <= SOL + 6; y++) {
                BlockState mur = bout ? soufflet : y == SOL + 5 ? Blocks.LIGHT_GRAY_STAINED_GLASS.defaultBlockState() : gris;
                pose(7, y, z, mur);
                pose(10, y, z, mur);
            }
        }
        // le pied de la passerelle, sur ses roues
        boite(8, SOL, -10, 9, SOL + 2, -10, Blocks.POLISHED_ANDESITE.defaultBlockState());
        pose(7, SOL, -10, Blocks.BLACK_CONCRETE.defaultBlockState());
        pose(10, SOL, -10, Blocks.BLACK_CONCRETE.defaultBlockState());
    }

    // ------------------------------------------------------------------ la tour de contrôle

    private void tour() {
        BlockState blanc = Blocks.WHITE_CONCRETE.defaultBlockState();
        BlockState gris = Blocks.LIGHT_GRAY_CONCRETE.defaultBlockState();
        int x1 = 50, z1 = -44, sommet = SOL + 26;
        for (int y = SOL; y < sommet; y++) {
            boite(x1, y, z1, x1 + 3, y, z1 + 3, Math.floorMod(y, 6) == 0 ? gris : blanc);
        }
        // la vigie : un étage vitré qui déborde, et le gyrophare
        boite(x1 - 2, sommet, z1 - 2, x1 + 5, sommet, z1 + 5, blanc);
        for (int x = x1 - 2; x <= x1 + 5; x++) {
            for (int z = z1 - 2; z <= z1 + 5; z++) {
                boolean bord = x == x1 - 2 || x == x1 + 5 || z == z1 - 2 || z == z1 + 5;
                boolean coin = (x == x1 - 2 || x == x1 + 5) && (z == z1 - 2 || z == z1 + 5);
                for (int y = sommet + 1; y <= sommet + 3; y++) {
                    pose(x, y, z, !bord ? AIR : coin ? blanc : Blocks.LIGHT_BLUE_STAINED_GLASS.defaultBlockState());
                }
                pose(x, sommet + 4, z, gris);
            }
        }
        pose(x1 + 1, sommet + 5, z1 + 1, Blocks.SEA_LANTERN.defaultBlockState());
        pose(x1 + 1, sommet + 6, z1 + 1, Blocks.RED_STAINED_GLASS.defaultBlockState());
    }

    // ------------------------------------------------------------------ les véhicules de piste

    private void vehicules() {
        BlockState jaune = Blocks.YELLOW_CONCRETE.defaultBlockState();
        BlockState noir = Blocks.BLACK_CONCRETE.defaultBlockState();
        // le tracteur à bagages et ses chariots, sous l'aile droite
        pose(-1, SOL, 10, jaune);
        pose(-2, SOL, 10, jaune);
        pose(-2, SOL + 1, 10, noir);
        for (int x : new int[] {-5, -8, -11}) {
            pose(x, SOL, 10, Blocks.BARREL.defaultBlockState());
            pose(x - 1, SOL, 10, Blocks.CHEST.defaultBlockState().setValue(net.minecraft.world.level.block.ChestBlock.FACING, Direction.SOUTH));
        }
        // le camion-citerne, garé plus loin
        boite(-26, SOL, 14, -21, SOL + 1, 15, Blocks.WHITE_CONCRETE.defaultBlockState());
        boite(-20, SOL, 14, -19, SOL + 1, 15, Blocks.RED_CONCRETE.defaultBlockState());
        pose(-19, SOL + 1, 14, Blocks.BLACK_STAINED_GLASS.defaultBlockState());
        pose(-19, SOL + 1, 15, Blocks.BLACK_STAINED_GLASS.defaultBlockState());
        // des plots orange au bout des ailes
        for (int[] p : new int[][] {{-7, -18}, {-7, 18}, {-24, -4}, {-24, 4}}) {
            pose(p[0], SOL, p[1], Blocks.ORANGE_CANDLE.defaultBlockState());
        }
    }

    /** La cage où attendent les passagers pendant la cinématique : des barrières, que personne ne voit. */
    private void attente() {
        int x = ATTENTE.getX();
        int y = ATTENTE.getY();
        int z = ATTENTE.getZ();
        boite(x - 2, y - 1, z - 2, x + 2, y + 3, z + 2, Blocks.BARRIER.defaultBlockState());
        boite(x - 1, y, z - 1, x + 1, y + 2, z + 1, AIR);
    }

    // ------------------------------------------------------------------ les panneaux

    private void textes() {
        texte("titre", 0.5, SOL + 14.2, FACADE_Z + 0.6, 0F, "{\"text\":\"AÉROPORT INTERNATIONAL\",\"color\":\"white\",\"bold\":true}", 4F);
        texte("porte", 9, SOL + 8.2, FACADE_Z + 0.6, 0F, "[{\"text\":\"PORTE 12\\n\",\"color\":\"gold\",\"bold\":true},{\"text\":\"BV2026 — BEAUVAIS\",\"color\":\"white\"}]", 1.1F);
        texte("tour", 51.5, SOL + 32.6, -42, 0F, "{\"text\":\"TOUR\",\"color\":\"white\",\"bold\":true}", 1.5F);
    }

    /**
     * Un texte flottant. Son identifiant est toujours le même : après une reconstruction, l'ancien (s'il traîne encore
     * sur le disque) est refusé au chargement, il n'y a jamais de doublon.
     */
    private void texte(String cle, double x, double y, double z, float yaw, String json, float echelle) {
        UUID id = UUID.nameUUIDFromBytes(("cabane:aeroport/" + cle).getBytes(StandardCharsets.UTF_8));
        Entity ancien = level.getEntity(id);
        if (ancien != null) {
            ancien.discard();
        }
        CompoundTag tag = new CompoundTag();
        tag.putString("id", "minecraft:text_display");
        tag.putString("text", json);
        tag.putString("billboard", "fixed");
        tag.putString("alignment", "center");
        tag.putInt("background", 0);
        tag.putInt("line_width", 400);
        tag.putBoolean("shadow", true);
        CompoundTag t = new CompoundTag();
        t.put("left_rotation", floats(0, 0, 0, 1));
        t.put("right_rotation", floats(0, 0, 0, 1));
        t.put("translation", floats(0, 0, 0));
        t.put("scale", floats(echelle, echelle, echelle));
        tag.put("transformation", t);
        ListTag tags = new ListTag();
        tags.add(StringTag.valueOf(TAG));
        tag.put("Tags", tags);
        tag.putUUID("UUID", id);
        Entity e = EntityType.loadEntityRecursive(tag, level, ent -> {
            ent.moveTo(x, y, z, yaw, 0F);
            return ent;
        });
        if (e != null) {
            level.addFreshEntity(e);
        }
    }

    private static ListTag floats(float... v) {
        ListTag l = new ListTag();
        for (float f : v) {
            l.add(FloatTag.valueOf(f));
        }
        return l;
    }
}
