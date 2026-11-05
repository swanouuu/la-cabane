package fr.cabane.cartel;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Clearable;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.LanternBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;

/**
 * Construit le repaire du cartel au milieu de sa dimension, d'après la maquette (docs/cartel/generateur/monde.mjs, mêmes
 * coordonnées, même hasard) : le sol et la colline, le mur et ses miradors, le portail EL CARTEL, la grande allée et ses
 * stands d'armes, la place à la fontaine d'or, la hacienda d'El Patrón et sa piscine, le stand de tir, l'héliport, le
 * dépôt de conteneurs, la favela et son Christ, le désert, et tous les gars. Coordonnées : x vers l'est, z vers le sud,
 * y = 0 au ras du sol (le sol est en y = -1).
 */
final class CartelBuilder {
    /** À changer quand on modifie la construction : le repaire se reconstruit à la visite suivante. */
    static final int VERSION = 4;
    /** Le y du monde qui correspond au y = 0 de la maquette (le premier bloc d'air au-dessus du sol). */
    static final int SOL = 64;
    static final Vec3 ARRIVEE = new Vec3(0.5, SOL, 52.5);
    static final String TAG = "cartel";
    private static final int BORD = 64;
    private static final int HAUT = 50;
    private static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;
    // le mur d'enceinte
    private static final int MX0 = -38, MX1 = 46, MZ0 = -34, MZ1 = 58;

    private final ServerLevel level;
    private final String construction;
    /** Les blocs à raccorder à leurs voisins à la fin (barrières, vitres, escaliers…). */
    private final List<BlockPos> raccords = new ArrayList<>();
    private final List<int[]> favela = new ArrayList<>();
    private final List<double[]> couloirs = new ArrayList<>();

    private CartelBuilder(ServerLevel level, int numero) {
        this.level = level;
        this.construction = tagConstruction(numero);
    }

    static void construire(ServerLevel level, int numero) {
        new CartelBuilder(level, numero).tout();
    }

    static String tagConstruction(int numero) {
        return "cartel.n" + numero;
    }

    private void tout() {
        for (int cx = -BORD >> 4; cx <= (BORD - 1) >> 4; cx++) {
            for (int cz = -BORD >> 4; cz <= (BORD - 1) >> 4; cz++) {
                level.getChunk(cx, cz);
            }
        }
        nettoyer();
        sol();
        mur();
        allee();
        place();
        hacienda();
        piscine();
        standDeTir();
        heliport();
        depot();
        favela();
        desert();
        finitions();
        gens();
    }

    // ================================================================== le hasard de la maquette (le même qu'en JS)

    static double hache(int i, int j, int k) {
        int h = i * 374761393 + j * 668265263 + k * 2147483647;
        h = (h ^ (h >>> 13)) * 1274126177;
        return ((h ^ (h >>> 16)) & 0xFFFFFFFFL) / 4294967296.0;
    }

    /** La hauteur du terrain : plat dans le cartel, la colline de la favela qui monte au nord et à l'ouest. */
    static int hauteur(int x, int z) {
        double n = -z - 37, o = -x - 41;
        double h = Math.max(0, Math.max(n * 0.62, o * 0.62));
        if (n > 0 && o > 0) {
            h += Math.min(n, o) * 0.35;
        }
        if (h > 0) {
            h += 1.6 * Math.sin(x * 0.21) + 1.3 * Math.cos(z * 0.17);
        }
        return (int) Math.max(0, Math.round(Math.min(26, h)));
    }

    private static boolean dansMur(int x, int z) {
        return x > MX0 && x < MX1 && z > MZ0 && z < MZ1;
    }

    // ================================================================== le nettoyage et le sol

    private void nettoyer() {
        AABB zone = new AABB(-BORD, SOL - 6, -BORD, BORD, SOL + HAUT, BORD);
        for (Entity e : level.getEntities((Entity) null, zone, e -> e.getTags().contains(TAG) || e instanceof ItemEntity || e instanceof ProjectileArme)) {
            e.discard();
        }
        for (int x = -BORD; x < BORD; x++) {
            for (int z = -BORD; z < BORD; z++) {
                for (int y = -1; y < HAUT; y++) {
                    BlockPos p = new BlockPos(x, SOL + y, z);
                    if (!level.getBlockState(p).isAir()) {
                        Clearable.tryClear(level.getBlockEntity(p));
                        level.setBlock(p, Blocks.AIR.defaultBlockState(), FLAGS);
                    }
                }
            }
        }
    }

    private void sol() {
        for (int x = -BORD; x < BORD; x++) {
            for (int z = -BORD; z < BORD; z++) {
                int h = hauteur(x, z);
                for (int y = -1; y < h; y++) {
                    Block b;
                    if (y < h - 1) {
                        b = Blocks.DIRT;
                    } else if (dansMur(x, z)) {
                        b = hache(x, z, 1) < 0.25 ? Blocks.COARSE_DIRT : Blocks.PACKED_MUD;
                    } else if (h > 0) {
                        b = hache(x, z, 2) < 0.15 ? Blocks.COARSE_DIRT : Blocks.GRASS_BLOCK;
                    } else {
                        b = hache(x >> 1, z >> 1, 3) < 0.35 ? Blocks.COARSE_DIRT : Blocks.RED_SAND;
                    }
                    pose(x, y, z, b);
                }
            }
        }
        // la piste qui arrive au portail
        for (int z = 58; z < BORD; z++) {
            for (int x = -4; x <= 4; x++) {
                pose(x, -1, z, Math.abs(x) == 4 ? Blocks.COARSE_DIRT : Blocks.DIRT_PATH);
            }
        }
    }

    // ================================================================== le mur, le portail, les miradors

    private void mur() {
        BlockState[] graffitis = {Blocks.MAGENTA_GLAZED_TERRACOTTA.defaultBlockState(), Blocks.LIGHT_BLUE_GLAZED_TERRACOTTA.defaultBlockState(),
                Blocks.LIME_GLAZED_TERRACOTTA.defaultBlockState()};
        for (int x = MX0; x <= MX1; x++) {
            for (int z = MZ0; z <= MZ1; z++) {
                if (x != MX0 && x != MX1 && z != MZ0 && z != MZ1) {
                    continue;
                }
                if (z == MZ1 && Math.abs(x) <= 3) {
                    continue;
                }
                for (int y = 0; y <= 3; y++) {
                    BlockState s = y == 0 ? Blocks.STONE_BRICKS.defaultBlockState()
                            : (x + z) % 23 == 0 && y == 2 ? graffitis[Math.floorMod(x * 7 + z, 3)] : Blocks.SMOOTH_SANDSTONE.defaultBlockState();
                    pose(x, y, z, s);
                }
                // le barbelé tout le long
                raccord(x, 4, z, Blocks.IRON_BARS.defaultBlockState());
            }
        }
        // le portail : deux piliers blancs coiffés d'or, l'arche en bois sombre et son enseigne
        for (int s : new int[] {-1, 1}) {
            remplir(s * 4, 0, 57, s * 5, 7, 59, Blocks.WHITE_CONCRETE.defaultBlockState());
            remplir(s * 4, 8, 57, s * 5, 8, 59, Blocks.GOLD_BLOCK.defaultBlockState());
        }
        remplir(-3, 6, 58, 3, 7, 58, Blocks.DARK_OAK_PLANKS.defaultBlockState());
        String enseigne = "{\"text\":\"EL CARTEL\",\"color\":\"gold\",\"bold\":true}";
        texte(0.5, 6.15, 59.06, 0F, enseigne, 3.2F, 0);
        texte(0.5, 6.15, 57.94, 180F, enseigne, 3.2F, 0);
        for (int[] c : new int[][] {{MX0, MZ0}, {MX1, MZ0}, {MX0, MZ1}, {MX1, MZ1}}) {
            mirador(c[0], c[1]);
        }
        // le bouton du retour, juste devant l'arrivée, et son écriteau
        pose(3, 0, 50, Blocks.POLISHED_ANDESITE);
        pose(3, 1, 50, Cartel.BOUTON.get().defaultBlockState());
        texte(3.5, 2.2, 50.5, 0F, "{\"text\":\"⏎ RETOUR\",\"color\":\"red\",\"bold\":true}", 0.9F, 0x88000000);
    }

    private void mirador(int cx, int cz) {
        for (int[] d : new int[][] {{-1, -1}, {1, -1}, {-1, 1}, {1, 1}}) {
            for (int y = 0; y <= 6; y++) {
                pose(cx + d[0], y, cz + d[1], Blocks.DARK_OAK_LOG);
            }
        }
        remplir(cx - 2, 7, cz - 2, cx + 2, 7, cz + 2, Blocks.OAK_PLANKS.defaultBlockState());
        for (int x = cx - 2; x <= cx + 2; x++) {
            for (int z = cz - 2; z <= cz + 2; z++) {
                if (Math.abs(x - cx) == 2 || Math.abs(z - cz) == 2) {
                    pose(x, 8, z, Math.floorMod(x + z, 2) == 1 ? Blocks.SANDSTONE : Blocks.OAK_PLANKS);
                }
            }
        }
        for (int[] d : new int[][] {{-2, -2}, {2, -2}, {-2, 2}, {2, 2}}) {
            raccord(cx + d[0], 9, cz + d[1], Blocks.DARK_OAK_FENCE.defaultBlockState());
            raccord(cx + d[0], 10, cz + d[1], Blocks.DARK_OAK_FENCE.defaultBlockState());
        }
        remplir(cx - 2, 11, cz - 2, cx + 2, 11, cz + 2, Blocks.WAXED_EXPOSED_CUT_COPPER_SLAB.defaultBlockState());
        pose(cx, 10, cz, Blocks.LANTERN.defaultBlockState().setValue(LanternBlock.HANGING, true));
    }

    // ================================================================== la grande allée

    private void allee() {
        for (int z = -3; z <= 57; z++) {
            for (int x = -5; x <= 5; x++) {
                Block b = Math.abs(x) == 5 ? Blocks.POLISHED_ANDESITE : Math.abs(x) <= 1 ? Blocks.SMOOTH_SANDSTONE
                        : hache(x, z, 4) < 0.2 ? Blocks.PACKED_MUD : Blocks.MUD_BRICKS;
                pose(x, -1, z, b);
            }
        }
        Block[][] couleurs = {{Blocks.RED_WOOL, Blocks.WHITE_WOOL}, {Blocks.GREEN_WOOL, Blocks.WHITE_WOOL}, {Blocks.YELLOW_WOOL, Blocks.GREEN_WOOL},
                {Blocks.RED_WOOL, Blocks.GREEN_WOOL}, {Blocks.BLUE_WOOL, Blocks.YELLOW_WOOL}, {Blocks.BLACK_WOOL, Blocks.RED_WOOL}};
        String[][] armes = {{"ak47", "uzi", "ak47"}, {"rpg7", "grenade", "m79"}, {"thompson", "mac10", "canon_scie"}, {"barrett", "m60", "ak47"},
                {"desert_eagle_or", "ak47_or", "machette"}, {"minigun", "molotov", "uzi"}};
        int k = 0;
        for (int z = 50; z >= 6; z -= 9, k++) {
            stand(-1, z, couleurs[k % couleurs.length], armes[k % armes.length]);
            stand(1, z - 4, couleurs[(k + 3) % couleurs.length], armes[(k + 2) % armes.length]);
        }
        for (int z = 54; z >= 2; z -= 9) {
            palmier(-6, 0, z, 7 + Math.floorMod(z, 3));
            palmier(6, 0, z - 4, 6 + Math.floorMod(z, 2));
        }
        // les guirlandes de fanions de papier (papel picado) au-dessus de l'allée
        net.minecraft.world.item.Item[] fanions = {Items.PINK_STAINED_GLASS_PANE, Items.YELLOW_STAINED_GLASS_PANE, Items.LIME_STAINED_GLASS_PANE, Items.LIGHT_BLUE_STAINED_GLASS_PANE,
                Items.ORANGE_STAINED_GLASS_PANE, Items.PURPLE_STAINED_GLASS_PANE};
        for (int z = 52; z >= 4; z -= 8) {
            for (int n = 0; n <= 14; n++) {
                double t = n / 14.0;
                double x = -5.5 + 11 * t, zz = z + 1.5 * t, y = 6.4 - 0.9 * Math.sin(Math.PI * t);
                Quaternionf q = new Quaternionf().rotateZ((float) Math.toRadians(45));
                objet(new ItemStack(fanions[n % fanions.length]), x, y, zz, q, 0.42F);
            }
        }
    }

    /**
     * Un stand d'armes au bord de l'allée. cote -1 : à l'ouest (il regarde vers l'est), 1 : à l'est. Un mur de
     * planches couvert de fusils, une table chargée d'armes, un auvent rayé sur des poteaux, des caisses et des bidons.
     */
    private void stand(int cote, int z, Block[] couleurs, String[] armes) {
        int xt = cote < 0 ? -7 : 7, xm = cote < 0 ? -11 : 11;
        remplir(xm, 0, z - 2, xm, 3, z + 2, Blocks.OAK_PLANKS.defaultBlockState());
        for (int dz : new int[] {-2, 2}) {
            for (int y = 0; y <= 3; y++) {
                raccord(xt, y, z + dz, Blocks.DARK_OAK_FENCE.defaultBlockState());
            }
        }
        // l'auvent rayé, du poteau jusqu'au mur, qui dépasse d'un bloc côté allée
        for (int x = Math.min(xt - cote, xm); x <= Math.max(xt - cote, xm); x++) {
            for (int dz = -2; dz <= 2; dz++) {
                pose(x, 4, z + dz, Math.floorMod(dz, 2) == 1 ? couleurs[0] : couleurs[1]);
            }
        }
        // la table : un plateau de chêne noir, et les armes posées dessus
        int xa = Math.min(xt, xt + cote), xb = Math.max(xt, xt + cote);
        for (int x = xa; x <= xb; x++) {
            for (int dz = -1; dz <= 1; dz++) {
                pose(x, 0, z + dz, Blocks.DARK_OAK_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.TOP));
            }
        }
        double xc = (xa + xb + 1) / 2.0;
        for (int i = 0; i < armes.length; i++) {
            float yaw = (cote < 0 ? 180F : 0F) + (float) (hache(z, i, 5) * 24 - 12);
            float taille = armes[i].equals("grenade") || armes[i].equals("molotov") ? 0.6F : 1.1F;
            couche(arme(armes[i]), xc, 1.03, z - 0.55 + i * 0.95, yaw, taille);
        }
        // le présentoir : des armes accrochées au mur, côté allée, sur trois rangées
        double xf = xm + (cote < 0 ? 1.03 : -0.03);
        String[] mur = {"ak47", "rpg7", armes[0], "ak47_or", armes[1], "barrett"};
        for (int i = 0; i < mur.length; i++) {
            int ligne = i / 2, col = i % 2;
            murArme(arme(mur[i]), xf, 1.0 + ligne * 1.05, z - 0.4 + col * 1.8, cote < 0 ? -90F : 90F, 1.5F);
        }
        pose(xm - cote, 0, z + 2, Blocks.GREEN_TERRACOTTA);
        pose(xm - cote, 1, z + 2, Blocks.GREEN_TERRACOTTA);
        pose(xm - 2 * cote, 0, z + 2, Blocks.GREEN_TERRACOTTA);
        pose(xm - cote, 0, z - 2, Blocks.CAULDRON);
        pose(xt, 0, z + 3, Blocks.SANDSTONE);
        pose(xm - cote, 3, z, Blocks.LANTERN.defaultBlockState().setValue(LanternBlock.HANGING, true));
    }

    private void palmier(int x, int y, int z, int h) {
        int dx = hache(x, z, 7) < 0.5 ? 1 : -1;
        for (int k = 0; k < h; k++) {
            pose(x + (k > h * 0.6 ? dx : 0), y + k, z, Blocks.JUNGLE_LOG);
        }
        BlockState feuille = Blocks.JUNGLE_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true);
        int tx = x + dx, ty = y + h, tz = z;
        pose(tx, ty, tz, feuille);
        for (int[] a : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {-1, -1}, {1, -1}, {-1, 1}}) {
            int L = a[0] != 0 && a[1] != 0 ? 2 : 3;
            for (int s = 1; s <= L; s++) {
                pose(tx + a[0] * s, ty - (s == L ? 1 : 0), tz + a[1] * s, feuille);
            }
        }
    }

    // ================================================================== la place, la fontaine, les voitures

    private void place() {
        for (int z = -13; z <= -4; z++) {
            for (int x = -15; x <= 15; x++) {
                pose(x, -1, z, Math.floorMod(x + z, 2) == 0 ? Blocks.SMOOTH_SANDSTONE : Blocks.MUD_BRICKS);
            }
        }
        remplir(-3, 0, -10, 3, 0, -6, Blocks.QUARTZ_BLOCK.defaultBlockState());
        remplir(-2, 0, -9, 2, 0, -7, Blocks.WATER.defaultBlockState());
        for (int x = -3; x <= 3; x++) {
            pose(x, 1, -10, Blocks.QUARTZ_SLAB);
            pose(x, 1, -6, Blocks.QUARTZ_SLAB);
        }
        for (int z = -9; z <= -7; z++) {
            pose(-3, 1, z, Blocks.QUARTZ_SLAB);
            pose(3, 1, z, Blocks.QUARTZ_SLAB);
        }
        remplir(0, 0, -8, 0, 3, -8, Blocks.GOLD_BLOCK.defaultBlockState());
        // la statue : une Kalach en or, debout sur la colonne
        objet(arme("ak47_or"), 0.5, 5.4, -7.5, new Quaternionf().rotateY((float) Math.toRadians(30)).rotateZ((float) Math.toRadians(-90)), 2.6F);
        voiture(-11, -9, Blocks.BLACK_CONCRETE, false);
        voiture(9, -10, Blocks.WHITE_CONCRETE, true);
        palmier(-14, 0, -4, 8);
        palmier(14, 0, -4, 7);
    }

    /** Un 4×4 noir (ou le pick-up blanc avec sa mitrailleuse sur le plateau), le capot vers le sud. */
    private void voiture(int x, int z, Block peinture, boolean technique) {
        remplir(x, 0, z, x + 2, 0, z + 4, peinture.defaultBlockState());
        for (int[] r : new int[][] {{x, z + 1}, {x + 2, z + 1}, {x, z + 3}, {x + 2, z + 3}}) {
            pose(r[0], 0, r[1], Blocks.BLACK_CONCRETE);
        }
        pose(x + 1, 0, z + 4, Blocks.IRON_BLOCK);
        int c0 = technique ? z + 3 : z + 1;
        for (int zz = c0; zz <= z + 3; zz++) {
            for (int xx = x; xx <= x + 2; xx++) {
                boolean bord = xx == x || xx == x + 2 || zz == z + 3;
                pose(xx, 1, zz, bord ? Blocks.BLACK_STAINED_GLASS.defaultBlockState() : peinture.defaultBlockState());
            }
        }
        remplir(x, 2, c0, x + 2, 2, z + 3, peinture.defaultBlockState());
        if (technique) {
            // les ridelles du plateau, où se tient le mitrailleur
            pose(x, 1, z, peinture);
            pose(x + 2, 1, z, peinture);
        }
    }

    // ================================================================== la hacienda d'El Patrón

    private void hacienda() {
        final int X0 = -17, X1 = 17, Z0 = -32, ZF = -17, ZA = -14;
        BlockState blanc = Blocks.WHITE_CONCRETE.defaultBlockState();
        remplir(X0 - 1, 0, Z0 - 1, X1 + 1, 0, ZA + 1, Blocks.STONE_BRICKS.defaultBlockState());
        for (int x = -3; x <= 3; x++) {
            pose(x, 0, ZA + 2, Blocks.STONE_BRICK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.NORTH));
        }
        // le rez-de-chaussée : murs blancs sur un soubassement crème
        for (int x = X0; x <= X1; x++) {
            for (int z = Z0; z <= ZF; z++) {
                if (x != X0 && x != X1 && z != Z0 && z != ZF) {
                    continue;
                }
                for (int y = 1; y <= 5; y++) {
                    pose(x, y, z, y == 1 ? Blocks.SMOOTH_SANDSTONE.defaultBlockState() : blanc);
                }
            }
        }
        // la grande porte (une arche ouverte) dans son cadre d'or
        remplir(-1, 1, ZF, 1, 3, ZF, Blocks.AIR.defaultBlockState());
        remplir(-2, 1, ZF, -2, 4, ZF, Blocks.GOLD_BLOCK.defaultBlockState());
        remplir(2, 1, ZF, 2, 4, ZF, Blocks.GOLD_BLOCK.defaultBlockState());
        remplir(-1, 4, ZF, 1, 4, ZF, Blocks.GOLD_BLOCK.defaultBlockState());
        texte(0.5, 5.05, ZF + 1.06, 0F, "{\"text\":\"EL PATRÓN\",\"color\":\"gold\",\"bold\":true}", 1.4F, 0);
        // les fenêtres à volets
        for (int x : new int[] {-13, -8, 8, 13}) {
            for (int y = 2; y <= 4; y++) {
                raccord(x, y, ZF, Blocks.GLASS_PANE.defaultBlockState());
                raccord(x + 1, y, ZF, Blocks.GLASS_PANE.defaultBlockState());
            }
            for (int y = 2; y <= 3; y++) {
                volet(x - 1, y, ZF + 1, Direction.SOUTH);
                volet(x + 2, y, ZF + 1, Direction.SOUTH);
            }
        }
        for (int z : new int[] {-28, -23}) {
            for (int x : new int[] {X0, X1}) {
                for (int y = 2; y <= 4; y++) {
                    raccord(x, y, z, Blocks.GLASS_PANE.defaultBlockState());
                    raccord(x, y, z + 1, Blocks.GLASS_PANE.defaultBlockState());
                }
            }
        }
        // les arcades : sol de tomettes, colonnes blanches, arcs en escaliers retournés
        for (int x = X0; x <= X1; x++) {
            for (int z = ZF + 1; z <= ZA; z++) {
                pose(x, 0, z, Blocks.TERRACOTTA);
            }
            pose(x, 5, ZA, blanc);
        }
        int[] colonnes = {-17, -12, -7, -3, 3, 7, 12, 17};
        for (int i = 0; i < colonnes.length; i++) {
            for (int y = 1; y <= 4; y++) {
                pose(colonnes[i], y, ZA, Blocks.QUARTZ_PILLAR);
            }
            if (i + 1 < colonnes.length && colonnes[i + 1] - colonnes[i] > 2) {
                pose(colonnes[i] + 1, 4, ZA, Blocks.QUARTZ_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.WEST).setValue(StairBlock.HALF, Half.TOP));
                pose(colonnes[i + 1] - 1, 4, ZA, Blocks.QUARTZ_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.EAST).setValue(StairBlock.HALF, Half.TOP));
            }
        }
        // le plancher de l'étage, et les poutres (vigas) qui dépassent au-dessus des arcades
        remplir(X0, 6, Z0, X1, 6, ZA, Blocks.DARK_OAK_PLANKS.defaultBlockState());
        for (int x = X0 + 1; x < X1; x += 3) {
            pose(x, 6, ZA + 1, Blocks.STRIPPED_DARK_OAK_LOG.defaultBlockState().setValue(RotatedPillarBlock.AXIS, Direction.Axis.Z));
        }
        for (int x : new int[] {-10, -5, 5, 10}) {
            pose(x, 5, ZA - 1, Blocks.LANTERN.defaultBlockState().setValue(LanternBlock.HANGING, true));
        }
        // l'étage, en retrait, et son balcon
        for (int x = X0; x <= X1; x++) {
            for (int z = Z0; z <= ZA - 1; z++) {
                if (x != X0 && x != X1 && z != Z0 && z != ZA - 1) {
                    continue;
                }
                for (int y = 7; y <= 10; y++) {
                    pose(x, y, z, blanc);
                }
            }
            raccord(x, 7, ZA, Blocks.DARK_OAK_FENCE.defaultBlockState());
        }
        for (int x = X0 + 2; x <= X1 - 2; x += 4) {
            for (int y = 8; y <= 9; y++) {
                raccord(x, y, ZA - 1, Blocks.GLASS_PANE.defaultBlockState());
                raccord(x + 1, y, ZA - 1, Blocks.GLASS_PANE.defaultBlockState());
                volet(x - 1, y, ZA, Direction.SOUTH);
                volet(x + 2, y, ZA, Direction.SOUTH);
            }
        }
        // le toit de tuiles en croupe, en escaliers de briques
        for (int k = 0; ; k++) {
            int x0 = X0 - 1 + k, x1 = X1 + 1 - k, z0 = Z0 - 1 + k, z1 = ZA - k;
            if (x0 > x1 || z0 > z1) {
                break;
            }
            for (int x = x0; x <= x1; x++) {
                for (int z = z0; z <= z1; z++) {
                    if (x != x0 && x != x1 && z != z0 && z != z1) {
                        continue;
                    }
                    BlockState s;
                    if (z0 == z1 || x0 == x1) {
                        s = Blocks.BRICK_SLAB.defaultBlockState();
                    } else {
                        Direction d = z == z1 ? Direction.NORTH : z == z0 ? Direction.SOUTH : x == x0 ? Direction.EAST : Direction.WEST;
                        s = Blocks.BRICK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, d);
                    }
                    raccord(x, 11 + k, z, s);
                }
            }
        }
        // la tour du coin
        remplir(12, 11, -31, 16, 16, -27, blanc);
        for (int y = 13; y <= 15; y++) {
            for (int x = 13; x <= 15; x++) {
                raccord(x, y, -27, Blocks.GLASS_PANE.defaultBlockState());
            }
        }
        for (int k = 0; k < 3; k++) {
            for (int x = 11 + k; x <= 17 - k; x++) {
                for (int z = -32 + k; z <= -26 - k; z++) {
                    if (x != 11 + k && x != 17 - k && z != -32 + k && z != -26 - k) {
                        continue;
                    }
                    Direction d = z == -26 - k ? Direction.NORTH : z == -32 + k ? Direction.SOUTH : x == 11 + k ? Direction.EAST : Direction.WEST;
                    raccord(x, 17 + k, z, Blocks.BRICK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, d));
                }
            }
        }
        pose(14, 20, -29, Blocks.GOLD_BLOCK);

        // ---- la boutique : damier de marbre, tapis rouge bordé d'or jusqu'au comptoir
        for (int x = X0 + 1; x < X1; x++) {
            for (int z = Z0 + 1; z < ZF; z++) {
                pose(x, 0, z, Math.floorMod(x + z, 2) == 0 ? Blocks.QUARTZ_BLOCK : Blocks.POLISHED_BLACKSTONE);
            }
        }
        for (int z = -25; z < ZF; z++) {
            for (int x = -2; x <= 2; x++) {
                pose(x, 1, z, Math.abs(x) == 2 ? Blocks.YELLOW_CARPET : Blocks.RED_CARPET);
            }
        }
        // l'estrade d'El Patrón derrière le comptoir, pour qu'on le voie de loin
        for (int x = -4; x <= 4; x++) {
            for (int z = -29; z <= -27; z++) {
                pose(x, 1, z, Blocks.DARK_OAK_PLANKS);
            }
            pose(x, 1, -30, Blocks.DARK_OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.SOUTH));
        }
        // le comptoir, bordé d'or
        for (int x = -6; x <= 6; x++) {
            pose(x, 1, -26, Blocks.DARK_OAK_PLANKS);
            pose(x, 2, -26, Blocks.LIGHT_WEIGHTED_PRESSURE_PLATE);
        }
        // le mur des armes derrière le comptoir : lambris et poutre de chêne noir, et douze armes accrochées
        remplir(X0 + 1, 1, Z0, X1 - 1, 1, Z0, Blocks.DARK_OAK_PLANKS.defaultBlockState());
        remplir(X0 + 1, 5, Z0, X1 - 1, 5, Z0, Blocks.DARK_OAK_PLANKS.defaultBlockState());
        String[] mur = {"ak47", "ak47_or", "rpg7", "barrett", "m60", "minigun", "thompson", "uzi", "mac10", "m79", "canon_scie", "desert_eagle_or"};
        for (int i = 0; i < mur.length; i++) {
            int col = i % 6, ligne = i / 6;
            boolean petite = mur[i].equals("desert_eagle_or") || mur[i].equals("mac10") || mur[i].equals("uzi");
            murArme(arme(mur[i]), -12 + col * 5, 3.75 - ligne * 1.55, Z0 + 1.03, 0F, petite ? 1.3F : 2.2F);
        }
        // les vitrines : un meuble, une cloche de verre, une arme dedans
        String[][] vitrines = {{"rpg7", "ak47_or", "barrett"}, {"minigun", "thompson", "m60"}};
        for (int s = 0; s < 2; s++) {
            for (int i = 0; i < 3; i++) {
                int x = (s == 0 ? -13 : 11) + i;
                pose(x, 1, -23, Blocks.DARK_OAK_PLANKS);
                pose(x, 2, -23, Blocks.GLASS);
                couche(arme(vitrines[s][i]), x + 0.5, 2.25, -22.5, s == 0 ? 0F : 180F, 0.85F);
            }
        }
        // l'or, les billets, le coffre, les plantes, les lanternes
        remplir(-15, 1, -30, -14, 2, -29, Blocks.EMERALD_BLOCK.defaultBlockState());
        remplir(-13, 1, -30, -13, 1, -29, Blocks.EMERALD_BLOCK.defaultBlockState());
        remplir(14, 1, -30, 15, 1, -29, Blocks.GOLD_BLOCK.defaultBlockState());
        pose(15, 2, -30, Blocks.GOLD_BLOCK);
        remplir(-15, 1, -20, -15, 2, -19, Blocks.IRON_BLOCK.defaultBlockState());
        for (int[] p : new int[][] {{-16, -31}, {16, -31}, {-16, -18}, {16, -18}}) {
            pose(p[0], 1, p[1], Blocks.POTTED_AZALEA);
        }
        for (int x = -12; x <= 12; x += 6) {
            for (int z : new int[] {-28, -21}) {
                pose(x, 5, z, Blocks.LANTERN.defaultBlockState().setValue(LanternBlock.HANGING, true));
            }
        }
    }

    private void volet(int x, int y, int z, Direction face) {
        pose(x, y, z, Blocks.DARK_OAK_TRAPDOOR.defaultBlockState().setValue(TrapDoorBlock.FACING, face).setValue(TrapDoorBlock.OPEN, true));
    }

    private void piscine() {
        for (int x = 20; x <= 34; x++) {
            for (int z = -30; z <= -18; z++) {
                pose(x, -1, z, Blocks.QUARTZ_BLOCK);
            }
        }
        for (int x = 21; x <= 33; x++) {
            for (int z = -29; z <= -19; z++) {
                pose(x, -2, z, Blocks.QUARTZ_BLOCK);
            }
        }
        remplir(22, -2, -28, 32, -1, -20, Blocks.WATER.defaultBlockState());
        // les transats
        for (int z : new int[] {-27, -24, -21}) {
            for (int x = 34; x <= 35; x++) {
                pose(x, 0, z, Blocks.SMOOTH_QUARTZ_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.TOP));
                pose(x, 1, z, Blocks.WHITE_CARPET);
            }
        }
        palmier(21, 0, -31, 8);
        palmier(35, 0, -17, 7);
        palmier(33, 0, -31, 6);
    }

    // ================================================================== le stand de tir

    private void standDeTir() {
        final int X0 = 18, X1 = 42;
        for (int x = X0; x <= X1; x++) {
            for (int z = 10; z <= 46; z++) {
                pose(x, -1, z, z >= 40 ? Blocks.SMOOTH_STONE : hache(x, z, 33) < 0.3 ? Blocks.PACKED_MUD : Blocks.SAND);
            }
        }
        for (int x = X0 - 1; x <= X1 + 1; x++) {
            remplir(x, 0, 8, x, 4, 8, Blocks.COARSE_DIRT.defaultBlockState());
            pose(x, 5, 8, Blocks.GRASS_BLOCK);
            remplir(x, 0, 9, x, 3, 9, Blocks.SANDSTONE.defaultBlockState());
        }
        for (int z = 10; z <= 46; z++) {
            remplir(X0 - 1, 0, z, X0 - 1, 1, z, Blocks.SANDSTONE.defaultBlockState());
            remplir(X1 + 1, 0, z, X1 + 1, 1, z, Blocks.SANDSTONE.defaultBlockState());
        }
        for (int n = 0; n < 6; n++) {
            int x = X0 + 1 + n * 4;
            if (n < 5) {
                remplir(x + 3, 0, 39, x + 3, 1, 42, Blocks.SANDSTONE.defaultBlockState());
            }
            for (int dx = 0; dx <= 2; dx++) {
                pose(x + dx, 0, 39, Blocks.DARK_OAK_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.TOP));
            }
            pose(x + 1, 0, 12, Blocks.HAY_BLOCK);
            pose(x + 1, 1, 12, Blocks.TARGET);
            if (n % 2 == 0) {
                pose(x + 1, 0, 11, Blocks.HAY_BLOCK);
            }
            int zs = 22 + (n % 3) * 4;
            raccord(x + 1, 0, zs, Blocks.DARK_OAK_FENCE.defaultBlockState());
            pose(x + 1, 1, zs, Blocks.TARGET);
            couloirs.add(n % 2 == 1 ? new double[] {x + 1.5, 1.5, 13.02} : new double[] {x + 1.5, 1.5, zs + 1.02});
        }
        for (int x : new int[] {X0, X0 + 8, X0 + 16, X1}) {
            for (int y = 0; y <= 3; y++) {
                raccord(x, y, 45, Blocks.DARK_OAK_FENCE.defaultBlockState());
            }
        }
        remplir(X0 - 1, 4, 43, X1 + 1, 4, 46, Blocks.WAXED_EXPOSED_CUT_COPPER_SLAB.defaultBlockState());
        for (int x = X0 + 2; x < X1; x += 5) {
            pose(x, 0, 45, Blocks.GREEN_TERRACOTTA);
            pose(x + 1, 0, 45, Blocks.GREEN_TERRACOTTA);
            if (x % 2 == 1) {
                pose(x, 1, 45, Blocks.GREEN_TERRACOTTA);
            }
        }
        pose(X1, 0, 44, Blocks.CAULDRON);
        pose(X1 - 1, 0, 44, Blocks.CAULDRON);
        for (int x = X0 + 4; x < X1; x += 8) {
            pose(x, 3, 44, Blocks.LANTERN.defaultBlockState().setValue(LanternBlock.HANGING, true));
        }
        texte(30.5, 4.6, 46.95, 0F, "{\"text\":\"STAND DE TIR\",\"color\":\"yellow\",\"bold\":true}", 1.6F, 0);
    }

    // ================================================================== l'héliport et le dépôt

    private void heliport() {
        for (int x = -34; x <= -18; x++) {
            for (int z = 24; z <= 42; z++) {
                pose(x, -1, z, Blocks.SMOOTH_STONE);
            }
        }
        for (int x = -33; x <= -19; x++) {
            for (int z = 25; z <= 41; z++) {
                double px = x + 0.5 + 26, pz = z + 0.5 - 33, d = Math.hypot(px, pz);
                boolean h = (Math.abs(px) < 3 && Math.abs(pz) < 0.6) || (Math.abs(Math.abs(px) - 2.5) < 0.6 && Math.abs(pz) < 3.6);
                pose(x, 0, z, d > 5.6 && d < 6.6 ? Blocks.YELLOW_CONCRETE : h ? Blocks.WHITE_CONCRETE : Blocks.SMOOTH_STONE);
            }
        }
        // l'hélico : la carlingue noire, sa bande d'or, le cockpit, la queue, les patins et le rotor
        int x = -26, z = 33;
        remplir(x - 1, 2, z - 2, x + 1, 4, z + 3, Blocks.BLACK_CONCRETE.defaultBlockState());
        for (int zz = z - 2; zz <= z + 3; zz++) {
            pose(x - 1, 3, zz, Blocks.GOLD_BLOCK);
            pose(x + 1, 3, zz, Blocks.GOLD_BLOCK);
        }
        remplir(x - 1, 2, z + 4, x + 1, 4, z + 5, Blocks.GRAY_STAINED_GLASS.defaultBlockState());
        remplir(x, 4, z - 9, x, 4, z - 3, Blocks.BLACK_CONCRETE.defaultBlockState());
        remplir(x, 5, z - 9, x, 6, z - 9, Blocks.BLACK_CONCRETE.defaultBlockState());
        for (int zz = z - 3; zz <= z + 4; zz++) {
            raccord(x - 2, 1, zz, Blocks.IRON_BARS.defaultBlockState());
            raccord(x + 2, 1, zz, Blocks.IRON_BARS.defaultBlockState());
        }
        pose(x, 5, z, Blocks.IRON_BLOCK);
        // les pales : des trappes fermées, minces comme des lames
        BlockState pale = Blocks.DARK_OAK_TRAPDOOR.defaultBlockState().setValue(TrapDoorBlock.HALF, Half.BOTTOM);
        for (int k = -8; k <= 8; k++) {
            if (k != 0) {
                pose(x + k, 6, z, pale);
                pose(x, 6, z + k, pale);
            }
        }
        pose(x, 6, z, Blocks.IRON_BLOCK);
        raccord(x + 1, 6, z - 9, Blocks.IRON_BARS.defaultBlockState());
        raccord(x + 1, 5, z - 9, Blocks.IRON_BARS.defaultBlockState());
    }

    private void depot() {
        for (int x = -35; x <= -16; x++) {
            for (int z = -10; z <= 18; z++) {
                pose(x, -1, z, hache(x, z, 40) < 0.2 ? Blocks.COARSE_DIRT : Blocks.GRAY_CONCRETE);
            }
        }
        conteneur(-34, -8, Blocks.RED_CONCRETE, false, 0);
        conteneur(-34, -8, Blocks.BLUE_CONCRETE, false, 3);
        conteneur(-29, -8, Blocks.GREEN_CONCRETE, true, 0);
        conteneur(-34, 2, Blocks.BLUE_CONCRETE, true, 0);
        for (int n = 0; n < 16; n++) {
            int x = -27 + (n % 4) * 2, z = 2 + (n / 4) * 3, h = 1 + (int) (hache(n, 1, 41) * 3);
            Block b = n % 5 == 0 ? Blocks.BARREL : Blocks.GREEN_TERRACOTTA;
            remplir(x, 0, z, x + (n % 3 == 0 ? 1 : 0), h - 1, z + 1, b.defaultBlockState());
        }
        for (int z = -8; z <= 16; z += 3) {
            pose(-18, 0, z, Math.floorMod(z, 2) == 1 ? Blocks.CAULDRON : Blocks.BARREL);
        }
        voiture(-22, -7, Blocks.GREEN_CONCRETE, false);
    }

    /** Un conteneur maritime (3 × 3 × 7) ; ouvert : sa porte sud est ouverte et il est plein de caisses. */
    private void conteneur(int x, int z, Block couleur, boolean ouvert, int y) {
        for (int dx = 0; dx < 3; dx++) {
            for (int dz = 0; dz < 7; dz++) {
                for (int dy = 0; dy < 3; dy++) {
                    boolean bord = dx == 0 || dx == 2 || dy == 2 || dz == 0 || (dz == 6 && !ouvert);
                    if (bord) {
                        pose(x + dx, y + dy, z + dz, couleur);
                    } else if (ouvert && dy < 2 && dz < 5) {
                        pose(x + dx, y + dy, z + dz, Blocks.GREEN_TERRACOTTA);
                    }
                }
            }
        }
    }

    // ================================================================== la favela et le désert

    private void favela() {
        Block[] couleurs = {Blocks.PINK_CONCRETE, Blocks.YELLOW_CONCRETE, Blocks.LIGHT_BLUE_CONCRETE, Blocks.LIME_CONCRETE, Blocks.ORANGE_CONCRETE,
                Blocks.CYAN_CONCRETE, Blocks.WHITE_TERRACOTTA, Blocks.MAGENTA_TERRACOTTA, Blocks.RED_TERRACOTTA, Blocks.BRICKS, Blocks.RED_TERRACOTTA,
                Blocks.WHITE_CONCRETE};
        Block[] toits = {Blocks.LIGHT_GRAY_CONCRETE, Blocks.LIGHT_GRAY_CONCRETE, Blocks.LIGHT_GRAY_CONCRETE, Blocks.TERRACOTTA, Blocks.BRICKS,
                Blocks.WAXED_EXPOSED_CUT_COPPER};
        Set<BlockPos> murs = new HashSet<>();
        for (int gx = -64; gx <= 63; gx += 5) {
            for (int gz = -64; gz <= 63; gz += 5) {
                int x = gx + (int) Math.floor(hache(gx, gz, 50) * 2), z = gz + (int) Math.floor(hache(gz, gx, 51) * 2);
                int h0 = hauteur(x + 2, z + 2);
                if (h0 < 1 || x > 60 || z > 60) {
                    continue;
                }
                if (x > MX0 - 2 && x < MX1 + 2 && z > MZ0 - 2 && z < MZ1 + 2) {
                    continue;
                }
                if (hache(x, z, 52) < 0.12) {
                    continue;
                }
                int L = 3 + (int) Math.floor(hache(x, z, 53) * 2), P = 3 + (int) Math.floor(hache(x, z, 54) * 2);
                int etages = 1 + (int) Math.floor(hache(x, z, 55) * 2.4);
                Block couleur = couleurs[(int) Math.floor(hache(x, z, 56) * couleurs.length)];
                int sol = Math.min(Math.min(hauteur(x, z), hauteur(x + L, z)), Math.min(hauteur(x, z + P), hauteur(x + L, z + P)));
                int haut = sol + etages * 3;
                for (int dx = 0; dx <= L; dx++) {
                    for (int dz = 0; dz <= P; dz++) {
                        boolean bord = dx == 0 || dx == L || dz == 0 || dz == P;
                        boolean coin = (dx == 0 || dx == L) && (dz == 0 || dz == P);
                        for (int y = sol; y < haut; y++) {
                            BlockPos p = new BlockPos(x + dx, y, z + dz);
                            if (bord) {
                                Block b = (y - sol) % 3 == 2 && etages > 1 && hache(x, y, 57) < 0.5 ? Blocks.BRICKS : couleur;
                                boolean fenetre = !coin && (y - sol) % 3 == 1 && hache(x + dx + (z + dz) * 3, y, 60) < 0.42;
                                if (fenetre) {
                                    raccord(x + dx, y, z + dz, Blocks.BLACK_STAINED_GLASS_PANE.defaultBlockState());
                                } else {
                                    pose(x + dx, y, z + dz, b);
                                }
                                murs.add(p);
                            } else if (!murs.contains(p)) {
                                pose(x + dx, y, z + dz, Blocks.AIR);
                            }
                        }
                    }
                }
                // la porte, au sud, au rez-de-chaussée
                BlockState porte = Blocks.SPRUCE_DOOR.defaultBlockState().setValue(DoorBlock.FACING, Direction.SOUTH);
                pose(x + 1, sol, z + P, porte.setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER));
                pose(x + 1, sol + 1, z + P, porte.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
                remplir(x, haut, z, x + L, haut, z + P, toits[(int) Math.floor(hache(x, z, 61) * toits.length)].defaultBlockState());
                if (hache(x, z, 58) < 0.55) {
                    pose(x + 1, haut + 1, z + 1, Blocks.BLUE_CONCRETE);
                }
                if (hache(x, z, 59) < 0.25) {
                    for (int[] c : new int[][] {{0, 0}, {L, 0}, {0, P}, {L, P}}) {
                        pose(x + c[0], haut + 1, z + c[1], Blocks.BRICKS);
                    }
                }
                favela.add(new int[] {x, z, L, P, sol, haut});
            }
        }
        // le Christ en haut de la colline
        int cx = -55, cz = -55, h = hauteur(cx, cz);
        remplir(cx - 1, h, cz - 1, cx + 1, h + 2, cz + 1, Blocks.STONE_BRICKS.defaultBlockState());
        remplir(cx, h + 3, cz, cx, h + 10, cz, Blocks.QUARTZ_BLOCK.defaultBlockState());
        remplir(cx - 1, h + 3, cz, cx + 1, h + 6, cz, Blocks.QUARTZ_BLOCK.defaultBlockState());
        remplir(cx - 5, h + 9, cz, cx + 5, h + 9, cz, Blocks.QUARTZ_BLOCK.defaultBlockState());
        pose(cx, h + 11, cz, Blocks.QUARTZ_BLOCK);
    }

    private void desert() {
        for (int n = 0; n < 26; n++) {
            int x = 48 + (int) Math.floor(hache(n, 1, 90) * 15), z = -60 + (int) Math.floor(hache(n, 2, 90) * 122);
            if (!level.getBlockState(abs(x, 0, z)).isAir()) {
                continue;
            }
            int h = 2 + (int) Math.floor(hache(n, 3, 90) * 3);
            pose(x, -1, z, Blocks.RED_SAND);
            for (int y = 0; y < h; y++) {
                pose(x, y, z, Blocks.CACTUS);
            }
        }
        for (int n = 0; n < 7; n++) {
            palmier(50 + (int) Math.floor(hache(n, 5, 91) * 12), 0, -50 + n * 16, 6 + n % 3);
        }
    }

    // ================================================================== les gars

    private void gens() {
        // à l'arrivée et au portail
        pnj("garde", -3, 0, 56, 180, CartelPnj.PORTE, "ak47", null, false);
        pnj("sicario", 3.8, 0, 56, 180, CartelPnj.PORTE, "m60", null, false);
        // sur les miradors
        for (int[] c : new int[][] {{MX0, MZ0}, {MX1, MZ0}, {MX0, MZ1}, {MX1, MZ1}}) {
            String skin = hache(c[0], c[1], 1) < 0.5 ? "sicario" : "garde";
            String arme = hache(c[0], c[1], 2) < 0.5 ? "barrett" : "ak47";
            // il regarde dehors, vers le désert
            float yaw = (float) Math.toDegrees(Math.atan2(-c[0], c[1]));
            pnj(skin, c[0] + 0.5, 8, c[1] + 0.5, yaw, CartelPnj.PORTE, arme, null, false);
        }
        // dans l'allée : des gars qui traînent
        Object[][] allee = {{"narco", -2.5, 48.0, 90, CartelPnj.PORTE, "ak47_or"}, {"carioca", 2.6, 44.0, 220, CartelPnj.PORTE, "uzi"},
                {"garde", -3.0, 36.0, 70, CartelPnj.PORTE, "rpg7"}, {"reina", 2.0, 30.0, 250, CartelPnj.REPOS, null},
                {"sicario", -2.0, 24.0, 150, CartelPnj.PORTE, "ak47"}, {"narco", 3.0, 18.0, 200, CartelPnj.CROISE, null},
                {"carioca", -3.2, 12.0, 60, CartelPnj.PORTE, "mac10"}, {"garde", 2.5, 6.0, 230, CartelPnj.PORTE, "thompson"},
                {"sicario", -1.0, 0.0, 180, CartelPnj.PORTE, "ak47"}, {"carioca", 8.0, 46.0, 270, CartelPnj.REPOS, null},
                {"narco", -8.0, 41.0, 90, CartelPnj.CROISE, null}, {"garde", 8.0, 28.0, 270, CartelPnj.PORTE, "ak47"},
                {"carioca", -8.0, 23.0, 90, CartelPnj.REPOS, null}};
        for (Object[] g : allee) {
            pnj((String) g[0], (double) g[1], 0, (double) g[2], (int) g[3], (int) g[4], (String) g[5], null, g[5] == null);
        }
        // sur la place, devant la hacienda, et sur le plateau du pick-up
        pnj("garde", -4.5, 1, -15.5, 0, CartelPnj.PORTE, "ak47", null, false);
        pnj("garde", 5.5, 1, -15.5, 0, CartelPnj.PORTE, "ak47", null, false);
        pnj("reina", -8, 0, -6, 120, CartelPnj.REPOS, null, null, true);
        pnj("sicario", 10.5, 1, -8.5, 180, CartelPnj.VISE, "m60", null, false);
        // dans la boutique : El Patrón derrière son comptoir, ses deux gardes du corps, un client
        CartelPnj patron = pnj("patron", 0.5, 2, -27.5, 0, CartelPnj.CROISE, null, null, false);
        if (patron != null) {
            patron.addTag("cabane_pnj");
            patron.addTag("pnj.patron_cartel");
            patron.addTag("patron_cartel");
            patron.setCustomName(Component.literal("El Patrón").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
            patron.setCustomNameVisible(true);
        }
        pnj("sicario", -8.5, 1, -27, 20, CartelPnj.PORTE, "ak47_or", null, false);
        pnj("sicario", 9.5, 1, -27, -20, CartelPnj.PORTE, "ak47_or", null, false);
        pnj("carioca", -3.5, 1, -22, 160, CartelPnj.REPOS, null, null, false);
        // au stand de tir : six tireurs face aux cibles, l'instructeur derrière eux
        String[][] tireurs = {{"sicario", "ak47"}, {"garde", "m60"}, {"narco", "ak47_or"}, {"carioca", "uzi"}, {"garde", "rpg7"}, {"sicario", "barrett"}};
        for (int n = 0; n < couloirs.size(); n++) {
            double[] c = couloirs.get(n);
            pnj(tireurs[n][0], c[0], 0, 41.2, 180, CartelPnj.VISE, tireurs[n][1], new Vec3(c[0], SOL + c[1], c[2]), false);
        }
        pnj("instructeur", 30, 0, 44, 180, CartelPnj.CROISE, null, null, false);
        // près de l'hélico, au dépôt, à la piscine
        pnj("garde", -21, 1, 37, 120, CartelPnj.PORTE, "minigun", null, false);
        pnj("sicario", -20, 0, 4, 90, CartelPnj.PORTE, "ak47", null, false);
        pnj("narco", -24, 0, 10, 60, CartelPnj.CROISE, null, null, true);
        // le Fourgue (le marché noir), devant le conteneur ouvert plein de caisses : son stock
        CartelPnj fourgue = pnj("pnj/fourgue", -27.5, 0, -0.5, 0, CartelPnj.CROISE, null, null, false);
        if (fourgue != null) {
            fourgue.addTag("cabane_pnj");
            fourgue.addTag("pnj.fourgue");
            fourgue.addTag("fourgue");
            fourgue.setCustomName(Component.literal("Le Fourgue").withStyle(ChatFormatting.DARK_GRAY));
            fourgue.setCustomNameVisible(true);
        }
        pnj("reina", 34.5, 1, -24, 270, CartelPnj.REPOS, null, null, false);
        // dans la favela, sur les toits
        int n = 0;
        for (int i = 2; i < favela.size() && n < 10; i += 6, n++) {
            int[] f = favela.get(i);
            pnj(hache(f[0], f[1], 3) < 0.5 ? "carioca" : "garde", f[0] + 1.5, f[5] + 1, f[1] + f[3] / 2.0, 150,
                    hache(f[0], f[1], 4) < 0.5 ? CartelPnj.PORTE : CartelPnj.REPOS, "ak47", null, false);
        }
    }

    private CartelPnj pnj(String skin, double x, double y, double z, float yaw, int pose, String arme, Vec3 cible, boolean flaneur) {
        CartelPnj p = Cartel.PNJ.get().create(level);
        if (p == null) {
            return null;
        }
        p.moveTo(x, SOL + y, z, yaw, 0F);
        p.setYHeadRot(yaw);
        p.setYBodyRot(yaw);
        p.addTag(TAG);
        p.addTag(construction);
        p.preparer(skin, pose, arme == null ? ItemStack.EMPTY : arme(arme), cible, flaneur);
        level.addFreshEntity(p);
        return p;
    }

    // ================================================================== outils : blocs

    static BlockPos abs(int x, int y, int z) {
        return new BlockPos(x, SOL + y, z);
    }

    private void pose(int x, int y, int z, Block b) {
        pose(x, y, z, b.defaultBlockState());
    }

    private void pose(int x, int y, int z, BlockState s) {
        level.setBlock(abs(x, y, z), s, FLAGS);
    }

    /** Un bloc qui se raccorde à ses voisins (barrière, vitre, barreaux, escalier), raccordé à la fin. */
    private void raccord(int x, int y, int z, BlockState s) {
        pose(x, y, z, s);
        raccords.add(abs(x, y, z));
    }

    private void remplir(int x0, int y0, int z0, int x1, int y1, int z1, BlockState s) {
        for (int x = Math.min(x0, x1); x <= Math.max(x0, x1); x++) {
            for (int y = Math.min(y0, y1); y <= Math.max(y0, y1); y++) {
                for (int z = Math.min(z0, z1); z <= Math.max(z0, z1); z++) {
                    pose(x, y, z, s);
                }
            }
        }
    }

    private void finitions() {
        for (BlockPos p : raccords) {
            BlockState s = level.getBlockState(p);
            BlockState r = Block.updateFromNeighbourShapes(s, level, p);
            if (r != s && !r.isAir()) {
                level.setBlock(p, r, FLAGS);
            }
        }
    }

    // ================================================================== outils : objets et textes

    private static ItemStack arme(String id) {
        return switch (id) {
            case "ak47" -> new ItemStack(Cartel.AK47.get());
            case "ak47_or" -> new ItemStack(Cartel.AK47_OR.get());
            case "uzi" -> new ItemStack(Cartel.UZI.get());
            case "mac10" -> new ItemStack(Cartel.MAC10.get());
            case "thompson" -> new ItemStack(Cartel.THOMPSON.get());
            case "desert_eagle_or" -> new ItemStack(Cartel.DESERT_EAGLE.get());
            case "canon_scie" -> new ItemStack(Cartel.CANON_SCIE.get());
            case "barrett" -> new ItemStack(Cartel.BARRETT.get());
            case "m60" -> new ItemStack(Cartel.M60.get());
            case "minigun" -> new ItemStack(Cartel.MINIGUN.get());
            case "rpg7" -> new ItemStack(Cartel.RPG7.get());
            case "m79" -> new ItemStack(Cartel.M79.get());
            case "grenade" -> new ItemStack(Cartel.GRENADE.get());
            case "molotov" -> new ItemStack(Cartel.MOLOTOV.get());
            default -> new ItemStack(Cartel.MACHETTE.get());
        };
    }

    private CompoundTag base(String id) {
        CompoundTag tag = new CompoundTag();
        tag.putString("id", id);
        ListTag tags = new ListTag();
        tags.add(StringTag.valueOf(TAG));
        tags.add(StringTag.valueOf(construction));
        tag.put("Tags", tags);
        return tag;
    }

    private static ListTag floats(float... v) {
        ListTag l = new ListTag();
        for (float f : v) {
            l.add(FloatTag.valueOf(f));
        }
        return l;
    }

    private void ajouter(CompoundTag tag, double x, double y, double z, float yaw) {
        Entity e = EntityType.loadEntityRecursive(tag, level, ent -> {
            ent.moveTo(x, SOL + y, z, yaw, 0F);
            return ent;
        });
        if (e != null) {
            level.addFreshEntity(e);
        }
    }

    /** Un objet affiché (le modèle 3D de l'arme, sans les réglages de la main) : rotation puis taille. */
    private void objet(ItemStack stack, double x, double y, double z, Quaternionf q, float taille) {
        CompoundTag tag = base("minecraft:item_display");
        tag.put("item", stack.save(level.registryAccess()));
        tag.putString("item_display", "none");
        CompoundTag t = new CompoundTag();
        t.put("left_rotation", floats(q.x, q.y, q.z, q.w));
        t.put("right_rotation", floats(0, 0, 0, 1));
        t.put("translation", floats(0, 0, 0));
        t.put("scale", floats(taille, taille, taille));
        tag.put("transformation", t);
        tag.putFloat("view_range", 0.6F);
        ajouter(tag, x, y, z, 0F);
    }

    /** Une arme couchée à plat (sur une table), le canon tourné de {@code yaw} degrés (0 : vers l'est). */
    private void couche(ItemStack stack, double x, double y, double z, float yaw, float taille) {
        Quaternionf q = new Quaternionf().rotateY((float) Math.toRadians(-yaw)).rotateX((float) Math.toRadians(-90));
        objet(stack, x, y + 0.02 * taille, z, q, taille);
    }

    /** Une arme accrochée à plat contre un mur : yaw 0 = contre un mur au nord (on la voit du sud), canon vers l'est. */
    private void murArme(ItemStack stack, double x, double y, double z, float yaw, float taille) {
        Quaternionf q = new Quaternionf().rotateY((float) Math.toRadians(-yaw));
        objet(stack, x, y, z, q, taille);
    }

    /** Un texte flottant, fixe ; yaw 0 : lisible depuis le sud. */
    private void texte(double x, double y, double z, float yaw, String json, float taille, int fond) {
        CompoundTag tag = base("minecraft:text_display");
        tag.putString("text", json);
        tag.putString("billboard", "fixed");
        tag.putString("alignment", "center");
        tag.putInt("background", fond);
        tag.putBoolean("shadow", true);
        CompoundTag t = new CompoundTag();
        t.put("left_rotation", floats(0, 0, 0, 1));
        t.put("right_rotation", floats(0, 0, 0, 1));
        t.put("translation", floats(0, 0, 0));
        t.put("scale", floats(taille, taille, taille));
        tag.put("transformation", t);
        ajouter(tag, x, y, z, yaw);
    }
}
