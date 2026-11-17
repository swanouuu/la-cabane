package fr.cabane.parc;

import fr.cabane.parc.Trajet.Point;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.decoration.Painting;
import net.minecraft.world.level.block.AmethystClusterBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CandleBlock;
import net.minecraft.world.level.block.LanternBlock;
import net.minecraft.world.level.block.LightBlock;
import net.minecraft.world.level.block.RedstoneWallTorchBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.SlabType;

/**
 * La deuxième partie du parc (docs/parc/maquette.html) : la Mine Hantée dans sa montagne, le dôme du Cosmos, le lac
 * de la Rivière Splash (sa grotte aux cristaux, ses pilotis, l'appareil photo et le kiosque des photos) et la Maison
 * Hantée (couloir aux bougies, galerie des portraits, tunnel des cris, cimetière). Les rails, la goulotte du Splash,
 * les étoiles, les fantômes et les screamers sont dessinés par le client ; ici on ne pose que les blocs.
 */
public final class ParcBuilder2 {
    private static final int SOL = Maneges.SOL;
    private static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;

    /** Le kiosque des photos de la Rivière Splash (le bloc du coin est de son mur). */
    public static final int KIOSQUE_X = -50, KIOSQUE_Z = 38;

    private final ParcBuilder b;
    private final ServerLevel level;

    ParcBuilder2(ParcBuilder b) {
        this.b = b;
        this.level = b.level;
    }

    void tout() {
        chemins();
        mine();
        cosmos();
        splash();
        maison();
        gens();
    }

    // ================================================================== outils

    /** Un bloc aux coordonnées du monde. */
    private void a(double x, double y, double z, BlockState s) {
        level.setBlock(BlockPos.containing(x, y, z), s, FLAGS);
    }

    private void a(BlockPos p, BlockState s) {
        level.setBlock(p, s, FLAGS);
    }

    private BlockState choix(Block... l) {
        return l[(int) (b.alea() * l.length)].defaultBlockState();
    }

    /** Un nombre au hasard qui ne dépend que de la case (pour que la montagne soit pareille à chaque construction). */
    private static double bruit(int x, int z) {
        long h = x * 73856093L ^ z * 19349663L;
        h = (h ^ (h >>> 13)) * 0x5DEECE66DL + 11;
        return ((h >>> 17) & 0xFFFF) / 65536.0;
    }

    /** Le côté gauche du wagon, à plat (le quai est toujours de ce côté). */
    private static double[] gauche(Point p) {
        double gx = p.uy * p.fz - p.uz * p.fy, gz = p.ux * p.fy - p.uy * p.fx, l = Math.max(1e-6, Math.hypot(gx, gz));
        return new double[]{gx / l, gz / l};
    }

    /** Le lacet d'un texte lisible depuis la direction (dx, dz). */
    private static float lisibleDepuis(double dx, double dz) {
        return (float) Math.toDegrees(Math.atan2(-dx, dz));
    }

    /**
     * Le quai d'une gare, à gauche du rail, sur toute la longueur de la gare : dessus au niveau du rail (ou du sol), et
     * une marche en demi-dalle sur le bord pour y monter.
     */
    private void quai(Trajet t, double largeur, Block corps, Block dessus, Block marche) {
        Point p = new Point();
        for (double s = 0; s < t.total; s += 0.4) {
            t.a(s, p);
            if (p.mode != Trajet.GARE) {
                continue;
            }
            double[] g = gauche(p);
            int haut = Math.max((int) Math.floor(p.y + 0.05) - 1, SOL - 1);
            for (double w = 1.25; w <= 1.25 + largeur; w += 0.4) {
                int x = (int) Math.floor(p.x + g[0] * w), z = (int) Math.floor(p.z + g[1] * w);
                for (int y = SOL - 3; y < haut; y++) {
                    a(new BlockPos(x, y, z), corps.defaultBlockState());
                }
                a(new BlockPos(x, haut, z), dessus.defaultBlockState());
            }
            if (haut >= SOL) {
                double w = 1.25 + largeur + 0.6;
                BlockPos q = BlockPos.containing(p.x + g[0] * w, haut, p.z + g[1] * w);
                if (level.getBlockState(q).isAir()) {
                    a(q, marche.defaultBlockState().setValue(SlabBlock.TYPE, haut > SOL ? SlabType.TOP : SlabType.BOTTOM));
                }
            }
            b.occuper(p.x + g[0] * 3, p.z + g[1] * 3, 4);
        }
    }

    /** Le toit d'une gare : des poteaux côté quai et côté rail, un toit plat, et le nom lisible depuis le quai. */
    private void toitGare(Trajet t, double largeur, double hauteur, Block poteau, Block toit, String nom, String couleur, int fond) {
        Point p = new Point();
        // la gare : on part de son milieu et on va jusqu'à ses deux bouts
        double milieu = t.arretGare(1, 0), debut = milieu, fin = milieu;
        while (debut > milieu - t.total / 2 && t.a(debut - 0.5, p).mode == Trajet.GARE) {
            debut -= 0.5;
        }
        while (fin < milieu + t.total / 2 && t.a(fin + 0.5, p).mode == Trajet.GARE) {
            fin += 0.5;
        }
        int n = 0;
        for (double s = debut; s <= fin; s += 0.5) {
            t.a(s, p);
            double[] g = gauche(p);
            double h = Math.max(p.y, SOL) + hauteur;
            for (double w = -1.8; w <= 1.25 + largeur + 0.5; w += 0.4) {
                a(p.x + g[0] * w, h, p.z + g[1] * w, toit.defaultBlockState());
            }
            if (n++ % 10 == 0) {
                for (double w : new double[]{-1.8, 1.25 + largeur + 0.3}) {
                    for (double y = w < 0 ? SOL - 2 : Math.max(p.y, SOL) - 0.5; y < h; y += 1) {
                        a(p.x + g[0] * w, y, p.z + g[1] * w, poteau.defaultBlockState());
                    }
                }
            }
        }
        t.a((debut + fin) / 2, p);
        double[] g = gauche(p);
        double w = 1.25 + largeur + 1.05;
        b.texte(p.x + g[0] * w, Math.max(p.y, SOL) + hauteur - 1.2 - SOL, p.z + g[1] * w, lisibleDepuis(g[0], g[1]),
                ParcBuilder.json(nom, couleur), 2.4F, fond);
    }

    // ================================================================== les allées vers les nouveaux manèges

    /** Le point du bord de la place (rayon 18) en direction de (x, z). */
    private void route(double... pts) {
        BlockState sable = Blocks.SMOOTH_SANDSTONE.defaultBlockState();
        for (int i = 0; i + 3 < pts.length; i += 2) {
            // (les lampadaires et leurs guirlandes viennent à la fin : voir Guirlandes et ParcBuilder.garnir)
            b.allee(pts[i], pts[i + 1], pts[i + 2], pts[i + 3], 4.4, sable);
        }
    }

    private void chemins() {
        for (double[] l : Guirlandes.ALLEES_2) {
            route(l);
        }
    }

    // ================================================================== La Mine Hantée

    private void mine() {
        Trajet t = Maneges.trajet(Maneges.MINE);
        Point p = new Point();
        // les points du parcours, un par bloc, pour savoir où creuser et où la montagne doit couvrir
        List<double[]> ech = new ArrayList<>();
        for (double s = 0; s < t.total; s += 1) {
            t.a(s, p);
            ech.add(new double[]{p.x, p.y, p.z, p.mode == Trajet.GARE ? 1 : 0, Maneges.mineLargeur(p.u), Maneges.mineHauteur(p.u)});
        }
        // la montagne : une grosse colline de pierre, herbue en bas, creuse là où passe le rail
        double cx = Maneges.MINE_X, cz = Maneges.MINE_Z;
        double rm = 37 * Maneges.MINE_S;
        for (int x = (int) (cx - rm - 3); x <= cx + rm + 3; x++) {
            for (int z = (int) (cz - rm - 3); z <= cz + rm + 3; z++) {
                double d = Math.hypot(x + 0.5 - cx, z + 0.5 - cz) / rm;
                if (d >= 1) {
                    continue;
                }
                // l'allée qui monte à la gare reste libre
                if (distanceSegment(x + 0.5, z + 0.5, -40, -24, -48, -40) < 4) {
                    continue;
                }
                int h = (int) Math.round(36 * Maneges.MINE_S * (1 - Math.pow(d, 1.7)) + (bruit(x >> 1, z >> 1) - 0.5) * 5);
                double proche = 1e9;
                double[] e = null;
                for (double[] q : ech) {
                    double dd = (q[0] - x - 0.5) * (q[0] - x - 0.5) + (q[2] - z - 0.5) * (q[2] - z - 0.5);
                    if (dd < proche) {
                        proche = dd;
                        e = q;
                    }
                }
                proche = Math.sqrt(proche);
                if (e[3] == 1 && proche < 9) {
                    continue;
                }
                if (proche < e[4] / 2 + 2.5) {
                    h = Math.max(h, (int) Math.ceil(e[1] - SOL + e[5]) + 2);
                }
                if (h < 1) {
                    continue;
                }
                boolean herbe = h < 24;
                for (int y = 0; y < h; y++) {
                    double r = bruit(x * 7 + y, z * 3 - y);
                    Block bl = r < 0.55 ? Blocks.STONE : r < 0.75 ? Blocks.ANDESITE : r < 0.88 ? Blocks.COBBLESTONE : r < 0.95 ? Blocks.TUFF : Blocks.GRAVEL;
                    if (y == h - 1 && herbe) {
                        bl = Blocks.GRASS_BLOCK;
                    } else if (y >= h - 3 && herbe) {
                        bl = Blocks.DIRT;
                    }
                    if (bl == Blocks.GRAVEL && y > 0) {
                        bl = Blocks.STONE;
                    }
                    b.bloc(x, y, z, bl);
                }
                b.occuper(x + 0.5, z + 0.5, 1.5);
            }
        }
        // le tunnel : on creuse le long du rail (plus large dans les deux grandes salles), le sol en gravier
        for (double s = 0; s < t.total; s += 0.35) {
            t.a(s, p);
            if (!p.noir) {
                continue;
            }
            double[] g = gauche(p);
            double larg = Maneges.mineLargeur(p.u), haut = Maneges.mineHauteur(p.u);
            int sol = (int) Math.floor(p.y - 0.6);
            for (double w = -larg / 2; w <= larg / 2; w += 0.35) {
                int x = (int) Math.floor(p.x + g[0] * w), z = (int) Math.floor(p.z + g[1] * w);
                for (int y = sol + 1; y <= p.y + haut; y++) {
                    a(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState());
                }
                a(new BlockPos(x, sol, z), bruit(x, z) < 0.6 ? Blocks.GRAVEL.defaultBlockState() : Blocks.COARSE_DIRT.defaultBlockState());
            }
        }
        // les poutres de mine et les lanternes (pas dans la montée aux yeux rouges, ni dans la chute : là c'est le noir complet)
        int k = 0;
        for (double s = 0; s < t.total; s += 2.2) {
            t.a(s, p);
            if (!p.noir || Maneges.mineLargeur(p.u) > 5 || p.mode == Trajet.LENT || p.mode == Trajet.CHUTE) {
                continue;
            }
            k++;
            double[] g = gauche(p);
            double larg = Maneges.mineLargeur(p.u);
            if (k % 2 == 0) {
                // deux poteaux et une traverse sous le plafond
                int plafond = plafond(p.x, p.y, p.z);
                for (int sd = -1; sd <= 1; sd += 2) {
                    double w = sd * (larg / 2 - 0.2);
                    int x = (int) Math.floor(p.x + g[0] * w), z = (int) Math.floor(p.z + g[1] * w);
                    for (int y = (int) Math.floor(p.y - 0.6) + 1; y < plafond; y++) {
                        a(new BlockPos(x, y, z), Blocks.STRIPPED_SPRUCE_LOG.defaultBlockState());
                    }
                }
                for (double w = -larg / 2; w <= larg / 2; w += 0.4) {
                    a(p.x + g[0] * w, plafond - 1, p.z + g[1] * w, Blocks.STRIPPED_SPRUCE_LOG.defaultBlockState());
                }
            }
            if (k % 4 == 1) {
                // une lanterne qui pend du plafond, sur le côté
                double w = (k % 8 == 1 ? 1 : -1) * (larg / 2 - 0.7);
                double x = p.x + g[0] * w, z = p.z + g[1] * w;
                int plafond = plafond(x, p.y, z);
                a(x, plafond - 1, z, Blocks.LANTERN.defaultBlockState().setValue(LanternBlock.HANGING, true));
            }
        }
        // le ballast sous le rail de la gare
        for (double s = 0; s < t.total; s += 0.4) {
            t.a(s, p);
            if (p.mode != Trajet.GARE) {
                continue;
            }
            double[] g = gauche(p);
            for (double w = -1.2; w <= 1.2; w += 0.4) {
                a(p.x + g[0] * w, Math.floor(p.y - 0.6), p.z + g[1] * w, Blocks.GRAVEL.defaultBlockState());
            }
        }
        // la gare : un quai en planches, un toit de mine, le nom
        quai(t, 3.5, Blocks.COBBLESTONE, Blocks.SPRUCE_PLANKS, Blocks.SPRUCE_SLAB);
        toitGare(t, 3.5, 5, Blocks.STRIPPED_DARK_OAK_LOG, Blocks.DARK_OAK_PLANKS, "MINE HANTÉE", "#ff9a3c", 0xEE2B1D12);
    }

    /** Le premier bloc plein au-dessus de ce point du rail (le plafond du tunnel). */
    private int plafond(double x, double y, double z) {
        BlockPos.MutableBlockPos q = new BlockPos.MutableBlockPos((int) Math.floor(x), (int) Math.floor(y) + 1, (int) Math.floor(z));
        for (int k = 0; k < 16 && level.getBlockState(q).isAir(); k++) {
            q.move(Direction.UP);
        }
        return q.getY();
    }

    private static double distanceSegment(double x, double z, double ax, double az, double bx, double bz) {
        double dx = bx - ax, dz = bz - az, l2 = dx * dx + dz * dz;
        double k = Math.max(0, Math.min(1, ((x - ax) * dx + (z - az) * dz) / l2));
        return Math.hypot(x - ax - dx * k, z - az - dz * k);
    }

    // ================================================================== Cosmos

    private void cosmos() {
        double cx = Maneges.COSMOS_X, cz = Maneges.COSMOS_Z, r = Maneges.COSMOS_R;
        // le dôme : une demi-sphère blanche, bien fermée (dedans, c'est la nuit), et le sol tout noir
        for (int x = (int) (cx - r - 1); x <= cx + r + 1; x++) {
            for (int z = (int) (cz - r - 1); z <= cz + r + 1; z++) {
                double dh = Math.hypot(x + 0.5 - cx, z + 0.5 - cz);
                if (dh <= r) {
                    b.bloc(x, -1, z, Blocks.BLACK_CONCRETE);
                }
                for (int y = 0; y <= r + 1; y++) {
                    double d = Math.sqrt(dh * dh + (y + 0.5) * (y + 0.5));
                    if (d <= r && d > r - 1.6) {
                        b.bloc(x, y, z, Blocks.WHITE_CONCRETE);
                    }
                }
            }
        }
        b.occuper(cx, cz, r + 2);
        // le bandeau rose au pied du dôme
        for (double a = 0; a < Math.PI * 2; a += 0.01) {
            a(cx + Math.cos(a) * (r - 0.1), SOL + 1, cz + Math.sin(a) * (r - 0.1), Blocks.MAGENTA_CONCRETE.defaultBlockState());
        }
        // l'entrée, au sud : un petit bâtiment et un long couloir (la lumière du dehors s'arrête vite)
        int ex = (int) Math.floor(cx), ez = (int) Math.floor(cz + r);
        b.remplir(ex - 4, 0, ez - 4, ex + 4, 5, ez + 3, Blocks.WHITE_CONCRETE);
        b.remplir(ex - 5, 6, ez - 5, ex + 5, 6, ez + 4, Blocks.PURPLE_CONCRETE);
        b.remplir(ex - 1, 0, ez - 6, ex + 1, 2, ez + 3, Blocks.AIR);
        b.remplir(ex - 1, -1, ez - 6, ex + 1, -1, ez + 3, Blocks.PURPLE_CONCRETE);
        b.texte(ex + 0.5, 3.4, ez + 4.05, 0F, ParcBuilder.json("COSMOS", "#00f0ff"), 2.6F, 0xEE120C33);
        // la gare : un quai noir bordé de violet, et juste assez de lumière pour trouver les wagons
        Trajet t = Maneges.trajet(Maneges.COSMOS);
        quai(t, 3.2, Blocks.BLACK_CONCRETE, Blocks.PURPLE_CONCRETE, Blocks.BLACKSTONE_SLAB);
        Point p = new Point();
        t.a(Maneges.fiche(Maneges.COSMOS).arret(), p);
        double[] g = gauche(p);
        a(p.x + g[0] * 3, SOL + 3.5, p.z + g[1] * 3, Blocks.LIGHT.defaultBlockState().setValue(LightBlock.LEVEL, 6));
        // l'allée violette de la porte jusqu'au quai
        b.allee(ex + 0.5, ez - 5, p.x + g[0] * 3, p.z + g[1] * 3, 2.4, Blocks.PURPLE_CONCRETE.defaultBlockState());
        // les poteaux sous le rail, là où il est haut
        for (double s = 0; s < t.total; s += 3) {
            t.a(s, p);
            if (p.y < SOL + 2.6) {
                continue;
            }
            for (int y = SOL; y < p.y - 1.3; y++) {
                a(p.x, y, p.z, Blocks.PURPLE_CONCRETE.defaultBlockState());
            }
        }
    }

    // ================================================================== Rivière Splash

    private void splash() {
        double cx = Maneges.SPLASH_X, cz = Maneges.SPLASH_Z;
        // le lac
        double la = 38 * Maneges.SPLASH_S, lb = 31 * Maneges.SPLASH_S;
        for (int x = (int) (cx - la - 2); x <= cx + la + 2; x++) {
            for (int z = (int) (cz - lb - 2); z <= cz + lb + 2; z++) {
                double e = Math.pow((x + 0.5 - cx) / la, 2) + Math.pow((z + 0.5 - cz) / lb, 2);
                if (e > 1) {
                    continue;
                }
                if (e > 0.9) {
                    b.bloc(x, -1, z, Blocks.SAND);
                    continue;
                }
                b.bloc(x, -3, z, bruit(x, z) < 0.5 ? Blocks.SAND : Blocks.CLAY);
                b.bloc(x, -2, z, Blocks.WATER);
                b.bloc(x, -1, z, Blocks.WATER);
            }
        }
        b.occuper(cx, cz, la);
        Trajet t = Maneges.trajet(Maneges.SPLASH);
        Point p = new Point();
        // les pilotis sous la goulotte, là où elle est en l'air : un poteau au milieu, qui s'arrête sous le bois
        // (dans la grande pente, on regarde un peu avant et après pour qu'il ne la traverse pas)
        Point av = new Point(), apres = new Point();
        for (double s = 0; s < t.total; s += 4) {
            t.a(s, p);
            if (p.y < SOL + 1.6) {
                continue;
            }
            t.a(s - 1.5, av);
            t.a(s + 1.5, apres);
            double haut = Math.min(p.y, Math.min(av.y, apres.y)) - 1.1;
            for (int y = SOL - 2; y < haut; y++) {
                a(p.x, y, p.z, Blocks.STRIPPED_SPRUCE_LOG.defaultBlockState());
            }
        }
        // la grotte aux cristaux : un tunnel de roche moussue au ras de l'eau, des améthystes qui brillent sur les murs
        double debut = -1, fin = -1;
        for (double s = 0; s < t.total; s += 0.5) {
            t.a(s, p);
            if (p.noir) {
                if (debut < 0) {
                    debut = s;
                }
                fin = s;
            }
        }
        for (double s = debut; s <= fin; s += 0.4) {
            t.a(s, p);
            double[] g = gauche(p);
            for (double w = -3.8; w <= 3.8; w += 0.4) {
                for (double h = 0; h <= 7; h += 0.5) {
                    BlockPos q = BlockPos.containing(p.x + g[0] * w, SOL + h, p.z + g[1] * w);
                    boolean dedans = Math.abs(w) < 2.5 && h < 5.2;
                    if (dedans) {
                        a(q, Blocks.AIR.defaultBlockState());
                    } else if (level.getBlockState(q).isAir()) {
                        a(q, choix(Blocks.STONE, Blocks.MOSSY_COBBLESTONE, Blocks.ANDESITE, Blocks.MOSS_BLOCK, Blocks.STONE));
                    }
                }
            }
        }
        int k = 0;
        for (double s = debut + 2; s <= fin - 1; s += 3.2) {
            t.a(s, p);
            double[] g = gauche(p);
            int sd = k++ % 2 == 0 ? 1 : -1;
            double h = 1 + b.alea() * 2.5;
            double wx = p.x + g[0] * 2.7 * sd, wz = p.z + g[1] * 2.7 * sd;
            a(wx, SOL + h, wz, Blocks.AMETHYST_BLOCK.defaultBlockState());
            Direction vers = Direction.getNearest(-g[0] * sd, 0, -g[1] * sd);
            BlockPos q = BlockPos.containing(wx, SOL + h, wz).relative(vers);
            if (level.getBlockState(q).isAir()) {
                a(q, Blocks.AMETHYST_CLUSTER.defaultBlockState().setValue(AmethystClusterBlock.FACING, vers));
            }
        }
        // les rochers sur la grotte
        for (double s = debut; s <= fin; s += 4) {
            t.a(s, p);
            for (int j = 0; j < 2; j++) {
                double ox = (b.alea() - 0.5) * 8, oz = (b.alea() - 0.5) * 8, rr = 1.5 + b.alea() * 1.5;
                for (double dx = -rr; dx <= rr; dx += 1) {
                    for (double dz = -rr; dz <= rr; dz += 1) {
                        for (double dy = 0; dy <= rr; dy += 1) {
                            if (dx * dx + dz * dz + dy * dy <= rr * rr) {
                                BlockPos q = BlockPos.containing(p.x + ox + dx, SOL + 6.5 + dy, p.z + oz + dz);
                                if (level.getBlockState(q).isAir()) {
                                    a(q, choix(Blocks.STONE, Blocks.MOSSY_COBBLESTONE, Blocks.MOSS_BLOCK));
                                }
                            }
                        }
                    }
                }
            }
        }
        // la gare
        quai(t, 4.0, Blocks.SPRUCE_PLANKS, Blocks.SPRUCE_PLANKS, Blocks.SPRUCE_SLAB);
        toitGare(t, 4.0, 6, Blocks.STRIPPED_SPRUCE_LOG, Blocks.CYAN_CONCRETE, "RIVIÈRE SPLASH", "#ffffff", 0xEE1F6FB2);
        // l'appareil photo en bas de la grande descente
        double[] ap = Maneges.appareilPhoto();
        int px = (int) Math.floor(ap[0]), pz = (int) Math.floor(ap[1]);
        for (int y = -2; y <= 3; y++) {
            b.bloc(px, y, pz, Blocks.POLISHED_BLACKSTONE_WALL);
        }
        b.bloc(px, 4, pz, Blocks.BLACK_CONCRETE);
        a(ap[2], ap[3], ap[4], Blocks.GRAY_CONCRETE.defaultBlockState());
        // le kiosque des photos, à la sortie (l'écran est dessiné par le client)
        int kx = KIOSQUE_X, kz = KIOSQUE_Z;
        b.remplir(kx - 2, 0, kz - 3, kx, 4, kz + 3, Blocks.WHITE_TERRACOTTA);
        b.remplir(kx - 3, 5, kz - 4, kx + 1, 5, kz + 4, Blocks.BLUE_CONCRETE);
        b.remplir(kx + 1, 0, kz - 3, kx + 1, 0, kz + 3, Blocks.BLUE_CONCRETE);
        b.texte(kx + 1.1, 5.25, kz + 0.5, -90F, ParcBuilder.json("PHOTOS", "#ffffff"), 1.6F, 0xEE1F6FB2);
        b.texte(kx + 1.04, 2.4, kz + 0.5, -90F, ParcBuilder.json("TES PHOTOS ICI", "#ffffff"), 1.1F, 0xFF123A5A);
        b.occuper(kx, kz, 5);
    }

    // ================================================================== La Maison Hantée

    private void maison() {
        int cx = Maneges.MAISON_X, cz = Maneges.MAISON_Z;
        // le bloc de la maison : plein, en briques sombres (les couloirs sont creusés dedans)
        for (int x = -18; x <= 18; x++) {
            for (int z = -15; z <= 11; z++) {
                b.bloc(cx + x, -1, cz + z, Blocks.DARK_OAK_PLANKS);
                for (int y = 0; y <= 11; y++) {
                    double r = bruit(x * 5 + y, z * 3 + y * 7);
                    b.bloc(cx + x, y, cz + z, r < 0.6 ? Blocks.DEEPSLATE_BRICKS : r < 0.85 ? Blocks.DEEPSLATE_TILES : Blocks.CRACKED_DEEPSLATE_BRICKS);
                }
            }
        }
        // les coins et les bandeaux
        for (int x : new int[]{-18, 18}) {
            for (int z : new int[]{-15, 11}) {
                b.remplir(cx + x, 0, cz + z, cx + x, 11, cz + z, Blocks.POLISHED_BLACKSTONE);
            }
        }
        b.remplir(cx - 18, 5, cz + 11, cx + 18, 5, cz + 11, Blocks.POLISHED_BLACKSTONE_BRICKS);
        // le toit en gradins
        for (int k = 0; k < 5; k++) {
            b.remplir(cx - 19 + k * 2, 12 + k, cz - 16 + k * 2, cx + 19 - k * 2, 12 + k, cz + 12 - k * 2,
                    k % 2 == 0 ? Blocks.DEEPSLATE_TILES : Blocks.POLISHED_BLACKSTONE_BRICKS);
        }
        // les tours et leurs toits pointus
        for (int[] tr : new int[][]{{-18, -14, 6, 21}, {18, -14, 6, 21}, {-15, 9, 5, 16}}) {
            int x0 = cx + tr[0] - tr[2] / 2, z0 = cz + tr[1] - tr[2] / 2, w = tr[2], h = tr[3];
            b.remplir(x0, 0, z0, x0 + w - 1, h - 1, z0 + w - 1, Blocks.DEEPSLATE_BRICKS);
            for (int k = 0; k <= w / 2; k++) {
                b.remplir(x0 + k, h + k * 2, z0 + k, x0 + w - 1 - k, h + k * 2 + 1, z0 + w - 1 - k, Blocks.DEEPSLATE_TILES);
            }
            b.remplir(x0 + w / 2, h + w + 2, z0 + w / 2, x0 + w / 2, h + w + 3, z0 + w / 2, Blocks.IRON_BARS);
            b.remplir(x0 + w, h - 5, z0 + w / 2, x0 + w, h - 4, z0 + w / 2, b.alea() < 0.5 ? Blocks.VERDANT_FROGLIGHT : Blocks.OCHRE_FROGLIGHT);
        }
        // les fenêtres de la façade : certaines allumées (vert maladif, orange), d'autres noires
        for (int i = 0; i < 9; i++) {
            int x = -15 + (int) Math.round(i * 3.8);
            if (Math.abs(x) < 6) {
                continue;
            }
            double r = b.alea();
            Block f = r < 0.3 ? Blocks.VERDANT_FROGLIGHT : r < 0.55 ? Blocks.OCHRE_FROGLIGHT : Blocks.BLACK_STAINED_GLASS;
            b.remplir(cx + x, 7, cz + 11, cx + x, 8, cz + 11, f);
        }
        for (int sx : new int[]{-18, 18}) {
            for (int i = 0; i < 4; i++) {
                b.remplir(cx + sx, 7, cz - 10 + i * 6, cx + sx, 8, cz - 10 + i * 6, b.alea() < 0.5 ? Blocks.VERDANT_FROGLIGHT : Blocks.BLACK_STAINED_GLASS);
            }
        }
        couloirs();
        // le perron : un plancher sombre, deux colonnes, un auvent, le nom, deux lanternes
        b.remplir(cx - 7, -1, cz + 12, cx + 7, -1, cz + 19, Blocks.POLISHED_BLACKSTONE);
        for (int sx : new int[]{-7, 7}) {
            b.remplir(cx + sx, 0, cz + 19, cx + sx, 4, cz + 19, Blocks.QUARTZ_PILLAR);
        }
        b.remplir(cx - 8, 5, cz + 12, cx + 8, 5, cz + 20, Blocks.DEEPSLATE_TILE_SLAB);
        for (int sx : new int[]{-6, 6}) {
            b.bloc(cx + sx, 4, cz + 19, Blocks.SOUL_LANTERN.defaultBlockState().setValue(LanternBlock.HANGING, true));
        }
        b.texte(cx + 0.5, 6.0, cz + 21.05, 0F, ParcBuilder.json("MAISON HANTÉE", "#9dff6a"), 2.6F, 0xEE1A0F1F);
        // le cimetière à l'ouest, et deux tombes de chaque côté du perron
        for (double z = -12; z <= 10; z += 3.2) {
            for (double x = -27; x <= -21; x += 2.4) {
                tombe(cx + (int) Math.round(x + (b.alea() - 0.5)), cz + (int) Math.round(z));
            }
        }
        for (int sx : new int[]{-1, 1}) {
            for (int i = 0; i < 4; i++) {
                tombe(cx + sx * (10 + (i % 2) * 3), cz + 13 + (i / 2) * 3);
            }
        }
        arbreMort(cx - 24, cz + 16);
        arbreMort(cx + 21, cz - 10);
        arbreMort(cx - 25, cz - 16);
        // la grille noire autour
        for (int x = -29; x <= 21; x++) {
            b.remplir(cx + x, 0, cz - 17, cx + x, 1, cz - 17, Blocks.IRON_BARS);
        }
        for (int z = -17; z <= 19; z++) {
            b.remplir(cx - 29, 0, cz + z, cx - 29, 1, cz + z, Blocks.IRON_BARS);
            if (Math.abs(z - 16) > 3) {
                b.remplir(cx + 21, 0, cz + z, cx + 21, 1, cz + z, Blocks.IRON_BARS);
            }
        }
        b.occuper(cx - 4, cz + 2, 30);
    }

    private void tombe(int x, int z) {
        double r = b.alea();
        if (r < 0.35) {
            // une croix
            b.remplir(x, 0, z, x, 2, z, Blocks.STONE_BRICK_WALL);
            b.bloc(x - 1, 1, z, Blocks.STONE_BRICK_WALL);
            b.bloc(x + 1, 1, z, Blocks.STONE_BRICK_WALL);
        } else {
            b.bloc(x, 0, z, r < 0.7 ? Blocks.MOSSY_STONE_BRICKS : Blocks.STONE_BRICKS);
            b.bloc(x, 1, z, r < 0.7 ? Blocks.MOSSY_STONE_BRICK_SLAB : Blocks.STONE_BRICK_SLAB);
        }
        b.bloc(x, -1, z + 1, Blocks.COARSE_DIRT);
        b.bloc(x, -1, z + 2, Blocks.COARSE_DIRT);
    }

    private void arbreMort(int x, int z) {
        b.remplir(x, 0, z, x, 6, z, Blocks.DARK_OAK_LOG);
        for (int k = 0; k < 4; k++) {
            double a = b.alea() * Math.PI * 2, h = 3.5 + k * 0.9;
            for (double t = 0; t <= 1; t += 0.15) {
                a(x + 0.5 + Math.cos(a) * 2.6 * t, SOL + h + 1.6 * t, z + 0.5 + Math.sin(a) * 2.6 * t, Blocks.DARK_OAK_FENCE.defaultBlockState());
            }
        }
        b.occuper(x, z, 3);
    }

    /**
     * Les couloirs de la maison, creusés le long du chemin à pied : le couloir aux bougies (des bougies dans des niches),
     * la galerie des portraits (des tableaux, des lanternes d'âmes), le tunnel des cris (bas de plafond, rouge sang, à
     * peine éclairé, avec trois niches noires d'où sortent les screamers).
     */
    private void couloirs() {
        Trajet t = Maneges.trajet(Maneges.MAISON);
        Point p = new Point();
        int x0 = Maneges.MAISON_X - 17, x1 = Maneges.MAISON_X + 17, z0 = Maneges.MAISON_Z - 14, z1 = Maneges.MAISON_Z + 11;
        // d'abord les murs, le sol et le plafond de chaque partie
        for (double s = 0; s < t.total; s += 0.25) {
            t.a(s, p);
            int zone = Maneges.maisonZone(p.x, p.z);
            if (zone == 0) {
                continue;
            }
            double[] g = gauche(p);
            for (double w = -2.2; w <= 2.2; w += 0.25) {
                int x = (int) Math.floor(p.x + g[0] * w), z = (int) Math.floor(p.z + g[1] * w);
                if (x < x0 || x > x1 || z < z0 || z > z1) {
                    continue;
                }
                for (int y = -1; y <= 4; y++) {
                    BlockState s2 = switch (zone) {
                        case 3 -> y == -1 ? Blocks.BLACKSTONE.defaultBlockState() : choix(Blocks.RED_NETHER_BRICKS, Blocks.NETHER_WART_BLOCK, Blocks.RED_NETHER_BRICKS, Blocks.CRIMSON_HYPHAE);
                        case 2 -> y == -1 ? Blocks.DARK_OAK_PLANKS.defaultBlockState() : y == 4 ? Blocks.DARK_OAK_PLANKS.defaultBlockState() : choix(Blocks.POLISHED_BLACKSTONE_BRICKS, Blocks.POLISHED_BLACKSTONE_BRICKS, Blocks.CRACKED_POLISHED_BLACKSTONE_BRICKS);
                        default -> y == -1 ? Blocks.SPRUCE_PLANKS.defaultBlockState() : y == 4 ? Blocks.DARK_OAK_PLANKS.defaultBlockState() : choix(Blocks.DARK_OAK_PLANKS, Blocks.STRIPPED_DARK_OAK_LOG, Blocks.DARK_OAK_PLANKS);
                    };
                    b.bloc(x, y, z, s2);
                }
            }
        }
        // puis on creuse : trois blocs de large, quatre de haut (trois dans le tunnel)
        for (double s = 0; s < t.total; s += 0.25) {
            t.a(s, p);
            int zone = Maneges.maisonZone(p.x, p.z);
            if (zone == 0) {
                continue;
            }
            double[] g = gauche(p);
            for (double w = -1.2; w <= 1.2; w += 0.25) {
                int x = (int) Math.floor(p.x + g[0] * w), z = (int) Math.floor(p.z + g[1] * w);
                b.remplir(x, 0, z, x, zone == 3 ? 2 : 3, z, Blocks.AIR);
            }
        }
        // la lumière et le décor
        var tableaux = level.registryAccess().registryOrThrow(Registries.PAINTING_VARIANT);
        String[] grands = {"graham", "wanderer"}, petits = {"kebab", "alban", "aztec", "aztec2", "bomb", "plant", "wasteland"};
        int n = 0;
        for (double s = 0.5; s < t.total; s += 1) {
            t.a(s, p);
            int zone = Maneges.maisonZone(p.x, p.z);
            if (zone == 0) {
                continue;
            }
            n++;
            double[] g = gauche(p);
            boolean droit = Math.abs(g[0]) > 0.98 || Math.abs(g[1]) > 0.98;
            int sd = n % 2 == 0 ? 1 : -1;
            BlockPos mur = BlockPos.containing(p.x + g[0] * 2 * sd, SOL + 1, p.z + g[1] * 2 * sd);
            BlockPos devant = BlockPos.containing(p.x + g[0] * sd, SOL + 1, p.z + g[1] * sd);
            Direction face = Direction.getNearest(-g[0] * sd, 0, -g[1] * sd);
            if (zone == 1 && n % 5 == 0 && droit) {
                // une bougie dans une niche du mur
                a(mur, Blocks.CANDLE.defaultBlockState().setValue(CandleBlock.CANDLES, 3).setValue(CandleBlock.LIT, true));
            } else if (zone == 2 && droit) {
                if (n % 3 == 0) {
                    String nom = n % 2 == 0 ? grands[(int) (b.alea() * grands.length)] : petits[(int) (b.alea() * petits.length)];
                    BlockPos ou = n % 2 == 0 ? devant : devant.above();
                    tableaux.getHolder(ResourceKey.create(Registries.PAINTING_VARIANT, ResourceLocation.withDefaultNamespace(nom))).ifPresent(v -> {
                        Painting tab = new Painting(level, ou, face, v);
                        tab.addTag(ParcBuilder.TAG);
                        tab.addTag(b.construction);
                        level.addFreshEntity(tab);
                    });
                }
                if (n % 4 == 1) {
                    a(BlockPos.containing(p.x, SOL + 3, p.z), Blocks.SOUL_LANTERN.defaultBlockState().setValue(LanternBlock.HANGING, true));
                }
            } else if (zone == 3 && n % 6 == 0 && droit) {
                // une torche rouge, à peine de quoi voir
                a(devant, Blocks.REDSTONE_WALL_TORCH.defaultBlockState().setValue(RedstoneWallTorchBlock.FACING, face));
            }
        }
        // les niches noires d'où sortent les screamers
        double[] cris = Maneges.maisonCris();
        for (int k = 0; k < cris.length; k++) {
            t.a(cris[k] + 1.5, p);
            double[] g = gauche(p);
            int sd = k % 2 == 1 ? 1 : -1;
            for (double f = -0.6; f <= 0.6; f += 0.3) {
                double x = p.x + g[0] * 2 * sd + p.fx * f, z = p.z + g[1] * 2 * sd + p.fz * f;
                a(x, SOL, z, Blocks.AIR.defaultBlockState());
                a(x, SOL + 1, z, Blocks.AIR.defaultBlockState());
                a(x + g[0] * sd, SOL, z + g[1] * sd, Blocks.BLACK_CONCRETE.defaultBlockState());
                a(x + g[0] * sd, SOL + 1, z + g[1] * sd, Blocks.BLACK_CONCRETE.defaultBlockState());
            }
        }
        // le chemin de dehors, devant le perron
        for (double s = 0; s < t.total; s += 0.25) {
            t.a(s, p);
            if (Maneges.maisonZone(p.x, p.z) != 0) {
                continue;
            }
            double[] g = gauche(p);
            for (double w = -1.2; w <= 1.2; w += 0.4) {
                BlockPos q = BlockPos.containing(p.x + g[0] * w, SOL - 1, p.z + g[1] * w);
                if (!level.getBlockState(q).is(Blocks.POLISHED_BLACKSTONE)) {
                    a(q, Blocks.GRAVEL.defaultBlockState());
                }
            }
        }
    }

    // ================================================================== les gens

    private void gens() {
        Point p = new Point();
        // un employé au bout de chaque quai, tourné vers les wagons
        for (byte id : new byte[]{Maneges.MINE, Maneges.COSMOS, Maneges.SPLASH}) {
            Trajet t = Maneges.trajet(id);
            t.a(Maneges.fiche(id).arret() + 3, p);
            double[] g = gauche(p);
            b.employe(p.x + g[0] * 2.4, Math.max(Math.floor(p.y + 0.05), SOL) - SOL, p.z + g[1] * 2.4, lisibleDepuis(-g[0], -g[1]));
        }
        // la photographe du kiosque, et un guide devant la maison hantée
        b.employe(KIOSQUE_X + 1.5, 0, KIOSQUE_Z - 1.8, -90F);
        b.employe(Maneges.MAISON_X + 0.5, 0, Maneges.MAISON_Z + 13.5, 0F);
        // des promeneurs sur les nouvelles allées
        for (int k = 0; k < 6; k++) {
            b.promeneur(-30 + b.alea() * 22, -18 - b.alea() * 14, 8);
        }
        for (int k = 0; k < 5; k++) {
            b.promeneur(-14 - b.alea() * 36, 58, 8);
        }
        for (int k = 0; k < 6; k++) {
            b.promeneur(-14 - b.alea() * 58, 102, 8);
        }
        for (int k = 0; k < 4; k++) {
            b.promeneur(12 + b.alea() * 10, -25, 6);
        }
        for (int k = 0; k < 3; k++) {
            b.promeneur(KIOSQUE_X + 3 + b.alea() * 3, KIOSQUE_Z - 2 + b.alea() * 5, 4);
        }
    }
}
