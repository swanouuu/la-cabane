package fr.cabane.parc;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.SlabType;

/**
 * La troisième partie du parc (docs/parc/maquette.html) : ce qui est en blocs autour des manèges qui tournent (le
 * fût de la Tour de la Chute, les pieds et le quai de la Grande Roue, les sols du Carrousel et des Tasses avec la
 * théière, le bassin, le portique et la passerelle du Bateau Pirate, les tours de la Toupie) et la piste couverte des
 * autos-tamponneuses. Tout ce qui tourne (la roue, les plateaux, les chevaux, les tasses, le bateau, les bras de la
 * toupie, les autos) est dessiné par le client.
 */
final class ParcBuilder3 {
    private static final int SOL = Maneges.SOL;
    private static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;

    private final ParcBuilder b;
    private final ServerLevel level;

    ParcBuilder3(ParcBuilder b) {
        this.b = b;
        this.level = b.level;
    }

    void tout() {
        // la place du portail gaulois de l'événement, au nord de la place : pas d'arbre
        b.occuper(PortailGaulois.X, PortailGaulois.Z, 9);
        chemins();
        tour();
        roue();
        carrousel();
        tasses();
        bateau();
        toupie();
        autos();
        gens();
    }

    // ================================================================== outils

    private void a(double x, double y, double z, BlockState s) {
        level.setBlock(BlockPos.containing(x, y, z), s, FLAGS);
    }

    /** Une poutre de blocs droite d'un point à un autre (coordonnées du monde, y compté depuis le sol). */
    private void poutre(double x0, double y0, double z0, double x1, double y1, double z1, Block bl) {
        double l = Math.sqrt((x1 - x0) * (x1 - x0) + (y1 - y0) * (y1 - y0) + (z1 - z0) * (z1 - z0));
        for (double t = 0; t <= l; t += 0.3) {
            a(x0 + (x1 - x0) * t / l, SOL + y0 + (y1 - y0) * t / l, z0 + (z1 - z0) * t / l, bl.defaultBlockState());
        }
    }

    /** Un disque de sol (y = -1) : le milieu d'un bloc, le bord d'un autre. */
    private void disque(double cx, double cz, double r, double bord, Block milieu, Block tour) {
        for (int x = (int) Math.floor(cx - r - 1); x <= cx + r + 1; x++) {
            for (int z = (int) Math.floor(cz - r - 1); z <= cz + r + 1; z++) {
                double d = Math.hypot(x + 0.5 - cx, z + 0.5 - cz);
                if (d <= r) {
                    b.bloc(x, -1, z, d > r - bord ? tour : milieu);
                }
            }
        }
        b.occuper(cx, cz, r + 1);
    }

    /** Un panneau sur un poteau, lisible depuis la direction (dx, dz). */
    private void pancarte(int x, int z, double dx, double dz, String nom, String couleur, int fond) {
        b.remplir(x, 0, z, x, 2, z, Blocks.DARK_OAK_FENCE);
        b.bloc(x, 3, z, Blocks.DARK_OAK_PLANKS);
        float lacet = (float) Math.toDegrees(Math.atan2(-dx, dz));
        b.texte(x + 0.5 + dx * 0.56, 3.1, z + 0.5 + dz * 0.56, lacet, ParcBuilder.json(nom, couleur), 1.4F, fond);
    }

    private static double[] anneau(double x, double z) {
        double a = Math.atan2(z, x);
        return new double[]{Math.cos(a) * 18, Math.sin(a) * 18};
    }

    private void route(double... pts) {
        BlockState sable = Blocks.SMOOTH_SANDSTONE.defaultBlockState();
        for (int i = 0; i + 3 < pts.length; i += 2) {
            b.allee(pts[i], pts[i + 1], pts[i + 2], pts[i + 3], 4.4, sable);
            double l = Math.hypot(pts[i + 2] - pts[i], pts[i + 3] - pts[i + 1]);
            if (l < 14) {
                continue;
            }
            double dx = (pts[i + 2] - pts[i]) / l, dz = (pts[i + 3] - pts[i + 1]) / l;
            for (double s = 7; s < l - 4; s += 14) {
                b.lampadaire((int) Math.floor(pts[i] + dx * s - dz * 3.2), (int) Math.floor(pts[i + 1] + dz * s + dx * 3.2));
            }
        }
    }

    private void chemins() {
        double[] t = anneau(-30, -34), ba = anneau(-30, 24), tp = anneau(38, 18);
        route(t[0], t[1], -30, -34);
        route(ba[0], ba[1], -30, 24, -40, 23);
        route(tp[0], tp[1], 38, 18, 40, 29);
        route(5, 76, 45, 76, 45, 79);
        route(-6, 35, -14, 35);
        route(5, 35, 12, 35);
        route(-6, 80, -14, 80);
    }

    // ================================================================== la Tour de la Chute

    private void tour() {
        double cx = Tournants.TOUR_X, cz = Tournants.TOUR_Z;
        disque(cx, cz, 11, 0.9, Blocks.SMOOTH_STONE, Blocks.POLISHED_ANDESITE);
        // la barrière jaune tout autour (ouverte au sud)
        for (int k = 0; k < 24; k++) {
            double an = k * Math.PI * 2 / 24;
            if (Math.abs(an - Math.PI / 2) < 0.3) {
                continue;
            }
            a(cx + Math.cos(an) * 10.6, SOL, cz + Math.sin(an) * 10.6, Blocks.YELLOW_CONCRETE.defaultBlockState());
        }
        // le fût rayé rouge et blanc, le chapeau, l'antenne et ses lumières
        int x0 = (int) cx - 2, z0 = (int) cz - 2;
        int H = Tournants.TOUR_H;
        // l'ancien chapeau (la tour faisait 72 blocs) est plus haut que le nettoyage du parc : on l'efface
        b.remplir(x0 - 1, 60, z0 - 1, x0 + 4, H + 8, z0 + 4, Blocks.AIR);
        for (int y = 0; y < H; y++) {
            b.remplir(x0, y, z0, x0 + 3, y, z0 + 3, (y / 4) % 2 == 0 ? Blocks.RED_CONCRETE : Blocks.WHITE_CONCRETE);
        }
        b.remplir(x0 - 1, H, z0 - 1, x0 + 4, H, z0 + 4, Blocks.CYAN_CONCRETE);
        for (int[] c : new int[][]{{x0 - 1, z0 - 1}, {x0 + 4, z0 - 1}, {x0 - 1, z0 + 4}, {x0 + 4, z0 + 4}}) {
            b.bloc(c[0], H + 1, c[1], Blocks.GLOWSTONE);
        }
        b.remplir(x0 + 1, H + 1, z0 + 1, x0 + 1, H + 5, z0 + 1, Blocks.WHITE_CONCRETE);
        b.bloc(x0 + 1, H + 6, z0 + 1, Blocks.SHROOMLIGHT);
        b.texte(cx, 6.5, z0 + 4.05, 0F, ParcBuilder.json("LA CHUTE", "#ffffff"), 2.2F, 0xEEE8333F);
    }

    // ================================================================== la Grande Roue

    private void roue() {
        double cx = Tournants.ROUE_X, cz = Tournants.ROUE_Z, cy = Tournants.ROUE_Y - SOL;
        for (int sz = -1; sz <= 1; sz += 2) {
            for (int sx = -1; sx <= 1; sx += 2) {
                poutre(cx + sx * 13, 0, cz + sz * 6.6, cx + sx * 0.7, cy, cz + sz * 3.2, Blocks.WHITE_CONCRETE);
            }
            poutre(cx - 7, 14, cz + sz * 4.7, cx + 7, 14, cz + sz * 4.7, Blocks.WHITE_CONCRETE);
        }
        poutre(cx, cy - 0.6, cz - 3.4, cx, cy - 0.6, cz + 3.4, Blocks.IRON_BLOCK);
        // le quai (deux blocs de haut) et ses marches, côté allée
        int x0 = (int) cx - 6, z0 = (int) cz - 3;
        b.remplir(x0, 0, z0, x0 + 11, 1, z0 + 6, Blocks.SMOOTH_SANDSTONE);
        b.remplir(x0, 1, z0, x0 + 11, 1, z0, Blocks.CUT_SANDSTONE);
        for (int x = (int) cx - 2; x <= cx + 1; x++) {
            b.bloc(x, 0, z0 - 1, Blocks.SMOOTH_SANDSTONE);
            b.bloc(x, 1, z0 - 1, Blocks.SMOOTH_SANDSTONE_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.BOTTOM));
            b.bloc(x, 0, z0 - 2, Blocks.SMOOTH_SANDSTONE_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.BOTTOM));
        }
        b.texte(cx + 4.5, 0.55, z0 - 0.05, 180F, ParcBuilder.json("GRANDE ROUE", "#ffffff"), 1.3F, 0xEEFF5C8A);
        b.occuper(cx, cz, 16);
    }

    // ================================================================== le Carrousel et les Tasses

    private void carrousel() {
        double cx = Tournants.CARROUSEL_X, cz = Tournants.CARROUSEL_Z;
        disque(cx, cz, 11.5, 0.8, Blocks.SMOOTH_SANDSTONE, Blocks.POLISHED_DIORITE);
        pancarte((int) cx + 13, (int) cz - 4, 1, 0, "LE CARROUSEL", "#ffffff", 0xEEE8B800);
    }

    private void tasses() {
        double cx = Tournants.TASSES_X, cz = Tournants.TASSES_Z;
        disque(cx, cz, 14.2, 0.8, Blocks.WHITE_CONCRETE, Blocks.PINK_TERRACOTTA);
        // la grosse théière au milieu : un ventre blanc, un couvercle rose, un bec et une anse
        for (int x = -3; x <= 3; x++) {
            for (int z = -3; z <= 3; z++) {
                for (int y = 0; y <= 4; y++) {
                    double dx = x + 0.5 - (cx - Math.floor(cx)), dz = z + 0.5 - (cz - Math.floor(cz)), dy = (y + 0.5 - 2.4) * 1.2;
                    if (dx * dx + dz * dz + dy * dy <= 2.2 * 2.2) {
                        b.bloc((int) Math.floor(cx) + x, y, (int) Math.floor(cz) + z, Blocks.WHITE_CONCRETE);
                    }
                }
            }
        }
        b.remplir((int) cx - 1, 4, (int) cz - 1, (int) cx + 1, 4, (int) cz + 1, Blocks.PINK_CONCRETE);
        b.bloc((int) cx, 5, (int) cz, Blocks.YELLOW_CONCRETE);
        poutre(cx + 2.2, 2.6, cz + 0.5, cx + 3.9, 4.2, cz + 0.5, Blocks.WHITE_CONCRETE);
        b.remplir((int) cx - 3, 3, (int) cz, (int) cx - 3, 3, (int) cz, Blocks.WHITE_CONCRETE);
        pancarte((int) cx - 16, (int) cz - 4, -1, 0, "LES TASSES FOLLES", "#ffffff", 0xEEFF8FB1);
    }

    // ================================================================== le Bateau Pirate

    private void bateau() {
        double cx = Tournants.BATEAU_X, cz = Tournants.BATEAU_Z, cy = Tournants.BATEAU_Y - SOL;
        // le bassin
        for (int x = (int) cx - 14; x <= cx + 14; x++) {
            for (int z = (int) cz - 8; z <= cz + 8; z++) {
                b.bloc(x, -2, z, Blocks.SAND);
                b.bloc(x, -1, z, Blocks.WATER);
            }
        }
        // le portique en bois et l'axe
        for (int sz = -1; sz <= 1; sz += 2) {
            for (int sx = -1; sx <= 1; sx += 2) {
                poutre(cx + sx * 10.8, 0, cz + sz * 7.0, cx, cy + 0.4, cz + sz * 5.5, Blocks.STRIPPED_SPRUCE_LOG);
            }
            poutre(cx - 6, 9.6, cz + sz * 6.4, cx + 6, 9.6, cz + sz * 6.4, Blocks.STRIPPED_SPRUCE_LOG);
        }
        poutre(cx, cy - 0.5, cz - 5.5, cx, cy - 0.5, cz + 5.5, Blocks.IRON_BLOCK);
        // la passerelle pour monter à bord, et son escalier
        int px0 = (int) cx - 5, pz = (int) cz + 5;
        b.remplir(px0, 0, pz, px0 + 10, 5, pz + 1, Blocks.SPRUCE_PLANKS);
        for (int x = px0; x <= px0 + 10; x++) {
            b.bloc(x, 6, pz + 1, Blocks.SPRUCE_FENCE);
        }
        for (int k = 0; k < 6; k++) {
            for (int x = (int) cx - 1; x <= cx + 1; x++) {
                if (k < 5) {
                    b.remplir(x, 0, pz + 2 + k, x, 4 - k, pz + 2 + k, Blocks.SPRUCE_PLANKS);
                }
                b.bloc(x, 5 - k, pz + 2 + k, Blocks.SPRUCE_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.NORTH));
            }
        }
        b.remplir((int) cx - 1, 6, pz + 1, (int) cx + 1, 6, pz + 1, Blocks.AIR);
        b.texte(cx + 0.5, cy + 3, cz + 7.6, 0F, ParcBuilder.json("LE BATEAU PIRATE", "#ffd23f"), 2.4F, 0xEE5A3418);
        b.occuper(cx, cz, 19);
    }

    // ================================================================== la Toupie

    private void toupie() {
        int cx = (int) Tournants.TOUPIE_X, cz = (int) Tournants.TOUPIE_Z;
        int pivot = (int) (Tournants.TOUPIE_Y - SOL);
        b.remplir(cx - 11, -1, cz - 11, cx + 11, -1, cz + 11, Blocks.POLISHED_BLACKSTONE);
        for (int x = cx - 11; x <= cx + 11; x++) {
            b.bloc(x, -1, cz + 11, (x & 1) == 0 ? Blocks.YELLOW_CONCRETE : Blocks.BLACK_CONCRETE);
        }
        // les deux tours, le néon en haut, l'axe entre elles
        for (int sx : new int[]{-1, 1}) {
            int x0 = sx < 0 ? cx - 9 : cx + 7;
            b.remplir(x0, 0, cz - 1, x0 + 1, pivot - 1, cz + 1, Blocks.POLISHED_DEEPSLATE);
            b.remplir(x0 - (sx < 0 ? 1 : 0), 0, cz - 2, x0 + 1 + (sx > 0 ? 1 : 0), 0, cz + 2, Blocks.DEEPSLATE_TILES);
            b.remplir(x0, pivot, cz - 1, x0 + 1, pivot, cz + 1, Blocks.MAGENTA_CONCRETE);
        }
        b.texte(cx - 7.5, pivot + 1.4, cz + 2.05, 0F, ParcBuilder.json("LA TOUPIE", "#00f0ff"), 1.6F, 0xEE2A2A3A);
        b.texte(cx + 8.5, pivot + 1.4, cz + 2.05, 0F, ParcBuilder.json("LA TOUPIE", "#00f0ff"), 1.6F, 0xEE2A2A3A);
        // la grille autour (ouverte au sud)
        for (int x = cx - 12; x <= cx + 12; x++) {
            b.bloc(x, 0, cz - 12, Blocks.IRON_BARS);
            if (Math.abs(x - cx) > 3) {
                b.bloc(x, 0, cz + 12, Blocks.IRON_BARS);
            }
        }
        for (int z = cz - 12; z <= cz + 12; z++) {
            // une porte côté ouest, là où arrive l'allée
            if (Math.abs(z - (cz + 4)) > 2) {
                b.bloc(cx - 12, 0, z, Blocks.IRON_BARS);
            }
            b.bloc(cx + 12, 0, z, Blocks.IRON_BARS);
        }
        b.occuper(cx, cz, 14);
    }

    // ================================================================== les autos-tamponneuses

    private void autos() {
        int cx = (int) AutoParc.PISTE_X, cz = (int) AutoParc.PISTE_Z, w = (int) AutoParc.PISTE_W, d = (int) AutoParc.PISTE_D;
        int x0 = cx - w, x1 = cx + w - 1, z0 = cz - d, z1 = cz + d - 1;
        // la piste en damier gris
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                b.bloc(x, -1, z, (((x - x0) / 2 + (z - z0) / 2) & 1) == 0 ? Blocks.GRAY_CONCRETE : Blocks.LIGHT_GRAY_CONCRETE);
            }
        }
        // la bordure jaune et noire (on l'enjambe ; les autos rebondissent dessus)
        for (int x = x0 - 1; x <= x1 + 1; x++) {
            b.bloc(x, -1, z0 - 1, Blocks.GRAY_CONCRETE);
            b.bloc(x, -1, z1 + 1, Blocks.GRAY_CONCRETE);
            b.bloc(x, 0, z0 - 1, (x & 1) == 0 ? Blocks.YELLOW_CONCRETE : Blocks.BLACK_CONCRETE);
            b.bloc(x, 0, z1 + 1, (x & 1) == 0 ? Blocks.YELLOW_CONCRETE : Blocks.BLACK_CONCRETE);
        }
        for (int z = z0 - 1; z <= z1 + 1; z++) {
            b.bloc(x0 - 1, -1, z, Blocks.GRAY_CONCRETE);
            b.bloc(x1 + 1, -1, z, Blocks.GRAY_CONCRETE);
            b.bloc(x0 - 1, 0, z, (z & 1) == 0 ? Blocks.YELLOW_CONCRETE : Blocks.BLACK_CONCRETE);
            b.bloc(x1 + 1, 0, z, (z & 1) == 0 ? Blocks.YELLOW_CONCRETE : Blocks.BLACK_CONCRETE);
        }
        // les piliers rouges, le toit, ses rayures et ses lumières de couleur
        for (int x : new int[]{x0 - 1, cx - 7, cx + 7, x1 + 1}) {
            for (int z : new int[]{z0 - 1, z1 + 1}) {
                b.remplir(x, 0, z, x, 7, z, Blocks.RED_CONCRETE);
            }
        }
        b.remplir(x0 - 2, 8, z0 - 2, x1 + 2, 8, z1 + 2, Blocks.GRAY_CONCRETE);
        for (int x = x0 - 2; x <= x1 + 2; x++) {
            b.bloc(x, 7, z0 - 2, ((x - x0) / 2 & 1) == 0 ? Blocks.RED_WOOL : Blocks.WHITE_WOOL);
            b.bloc(x, 7, z1 + 2, ((x - x0) / 2 & 1) == 0 ? Blocks.RED_WOOL : Blocks.WHITE_WOOL);
        }
        for (int z = z0 - 2; z <= z1 + 2; z++) {
            b.bloc(x0 - 2, 7, z, ((z - z0) / 2 & 1) == 0 ? Blocks.RED_WOOL : Blocks.WHITE_WOOL);
            b.bloc(x1 + 2, 7, z, ((z - z0) / 2 & 1) == 0 ? Blocks.RED_WOOL : Blocks.WHITE_WOOL);
        }
        Block[] lumieres = {Blocks.OCHRE_FROGLIGHT, Blocks.PEARLESCENT_FROGLIGHT, Blocks.SEA_LANTERN, Blocks.VERDANT_FROGLIGHT};
        for (int x = x0 + 1; x < x1; x += 3) {
            for (int z = z0 + 1; z < z1; z += 3) {
                b.bloc(x, 8, z, lumieres[(int) (b.alea() * lumieres.length)]);
            }
        }
        // le grand panneau jaune côté allée
        b.remplir(x1 + 3, 8, cz - 7, x1 + 3, 10, cz + 6, Blocks.YELLOW_CONCRETE);
        b.texte(x1 + 4.05, 8.6, cz, -90F, ParcBuilder.json("AUTOS-TAMPONNEUSES", "#e8333f"), 2.6F, 0x00000000);
        b.occuper(cx, cz, 22);
    }

    // ================================================================== les gens

    private void gens() {
        b.employe(Tournants.TOUR_X + 6.5, 0, Tournants.TOUR_Z + 10.5, 160F);
        b.employe(Tournants.ROUE_X + 4.5, 2, Tournants.ROUE_Z - 2.5, 90F);
        b.employe(Tournants.CARROUSEL_X + 12.5, 0, Tournants.CARROUSEL_Z - 2.5, 90F);
        b.employe(Tournants.TASSES_X - 15, 0, Tournants.TASSES_Z - 2.5, -90F);
        b.employe(Tournants.BATEAU_X - 3.5, 6, Tournants.BATEAU_Z + 5.5, 180F);
        b.employe(Tournants.TOUPIE_X - 10.5, 0, Tournants.TOUPIE_Z + 9.5, -90F);
        b.employe(AutoParc.PISTE_X + AutoParc.PISTE_W + 3.5, 0, AutoParc.PISTE_Z - 3.5, 90F);
        for (int k = 0; k < 5; k++) {
            b.promeneur(-14 - b.alea() * 6, 30 + b.alea() * 10, 6);
            b.promeneur(14 + b.alea() * 6, 30 + b.alea() * 10, 6);
        }
        for (int k = 0; k < 6; k++) {
            b.promeneur(8 + b.alea() * 36, 76, 8);
        }
        for (int k = 0; k < 4; k++) {
            b.promeneur(-10 - b.alea() * 4, 78 + b.alea() * 4, 4);
            b.promeneur(-22 - b.alea() * 8, -28 - b.alea() * 4, 5);
            b.promeneur(-22 - b.alea() * 14, 20 + b.alea() * 3, 5);
            b.promeneur(24 + b.alea() * 12, 12 + b.alea() * 4, 6);
        }
    }
}
