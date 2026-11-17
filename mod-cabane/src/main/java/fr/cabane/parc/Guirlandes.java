package fr.cabane.parc;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Le plan de la fête, partagé par le serveur et le client : les allées du parc, les lampadaires (le serveur les pose,
 * avec leur bannière), les fils tendus entre eux (le client y accroche des ampoules qui clignotent ou des fanions qui
 * flottent), les mâts des drapeaux et les grappes de ballons. Le client ne tend un fil que si les deux lampadaires sont
 * vraiment là (le serveur saute ceux qui tomberaient sur une allée ou un manège).
 */
public final class Guirlandes {
    /** Un lampadaire (le bloc de son pied), le côté de l'allée (vers où pend sa bannière) et la couleur de sa bannière. */
    public record Lampe(int x, int z, int dx, int dz, int couleur) {
    }

    /** Un fil entre deux lampadaires (leurs numéros) : des ampoules ou des fanions, qui pend de {@code creux} blocs au milieu. */
    public record Fil(int a, int b, boolean fanions, double creux) {
    }

    /** La hauteur où les fils s'accrochent (au-dessus du sol), juste sous la lanterne. */
    public static final double ACCROCHE = 3.95;
    /** La hauteur des mâts des drapeaux. */
    public static final int MAT = 9;

    /** Les allées de la Mine, du Cosmos, de la Rivière Splash et de la Maison Hantée (posées par ParcBuilder2). */
    public static final List<double[]> ALLEES_2 = new ArrayList<>();
    /** Les allées des manèges qui tournent et des autos (posées par ParcBuilder3). */
    public static final List<double[]> ALLEES_3 = new ArrayList<>();
    /** L'allée du Dragon, qui passe sous le grand huit (posée par ParcBuilder). */
    public static final double[] ALLEE_DRAGON;
    /** Les lampadaires de la grande avenue, de chaque côté (évite les débouchés des allées et le devant des stands). */
    private static final int[] AVENUE = {24, 30, 41, 53, 61, 71, 84, 90, 101, 113, 118};

    public static final List<Lampe> LAMPES = new ArrayList<>();
    public static final List<Fil> FILS = new ArrayList<>();
    /** Les mâts des drapeaux (x, z du bloc, hauteur du haut du mât au-dessus du sol). */
    public static final List<int[]> DRAPEAUX = new ArrayList<>();
    /** Les grappes de ballons (x, y au-dessus du sol, z de l'attache). */
    public static final List<double[]> BALLONS = new ArrayList<>();
    /** Les stands de l'avenue (x, z, 1 s'il est à l'ouest), comme ParcBuilder les pose. */
    public static final int[][] STANDS = {{-12, 48, 1}, {11, 48, 0}, {-12, 66, 1}, {11, 66, 0}, {-12, 96, 1}, {11, 96, 0}, {-12, 108, 1}, {11, 108, 0}};

    static double[] anneau(double x, double z) {
        double a = Math.atan2(z, x);
        return new double[]{Math.cos(a) * 18, Math.sin(a) * 18};
    }

    private static double[] ligne(double[] depart, double... suite) {
        double[] l = new double[suite.length + 2];
        l[0] = depart[0];
        l[1] = depart[1];
        System.arraycopy(suite, 0, l, 2, suite.length);
        return l;
    }

    static {
        ALLEES_2.add(ligne(anneau(-40, -24), -40, -24, -48, -40));
        ALLEES_2.add(ligne(anneau(10, -22), 10, -22, 25, -19));
        ALLEES_2.add(new double[]{-6, 58, -50, 58, -57, 49});
        ALLEES_2.add(new double[]{-6, 102, -76, 102, -76, 120, -97, 120});
        ALLEES_3.add(ligne(anneau(-30, -34), -30, -34));
        ALLEES_3.add(ligne(anneau(-30, 24), -30, 24, -40, 23));
        ALLEES_3.add(ligne(anneau(38, 18), 38, 18, 40, 29));
        ALLEES_3.add(new double[]{5, 76, 45, 76, 45, 79});
        ALLEES_3.add(new double[]{-6, 35, -14, 35});
        ALLEES_3.add(new double[]{5, 35, 12, 35});
        ALLEES_3.add(new double[]{-6, 80, -14, 80});
        double a = Math.atan2(-4, 60);
        ALLEE_DRAGON = new double[]{Math.cos(a) * 18, Math.sin(a) * 18, 60, -4, 100, -4, 108, 45.5, 111, 45.5};

        // la grande avenue : un fil d'ampoules en travers à chaque paire de lampadaires, des fanions en diagonale
        int couleur = 0;
        List<Integer> ouest = new ArrayList<>(), est = new ArrayList<>();
        for (int z : AVENUE) {
            ouest.add(LAMPES.size());
            LAMPES.add(new Lampe(-8, z, 1, 0, couleur++));
            est.add(LAMPES.size());
            LAMPES.add(new Lampe(7, z, -1, 0, couleur++));
        }
        for (int i = 0; i < AVENUE.length; i++) {
            FILS.add(new Fil(ouest.get(i), est.get(i), false, 1.1));
            if (i + 1 < AVENUE.length) {
                boolean sens = i % 2 == 0;
                FILS.add(new Fil(sens ? ouest.get(i) : est.get(i), sens ? est.get(i + 1) : ouest.get(i + 1), true, 1.5));
                FILS.add(new Fil(ouest.get(i), ouest.get(i + 1), false, 0.7));
                FILS.add(new Fil(est.get(i), est.get(i + 1), false, 0.7));
            }
        }
        // les allées : des lampadaires en quinconce, et un fil en zigzag au-dessus
        List<double[]> toutes = new ArrayList<>(ALLEES_2);
        toutes.addAll(ALLEES_3);
        toutes.add(ALLEE_DRAGON);
        for (double[] l : toutes) {
            for (int i = 0; i + 3 < l.length; i += 2) {
                double lg = Math.hypot(l[i + 2] - l[i], l[i + 3] - l[i + 1]);
                if (lg < 11) {
                    continue;
                }
                double dx = (l[i + 2] - l[i]) / lg, dz = (l[i + 3] - l[i + 1]) / lg;
                int cote = 1, avant = -1, n = 0;
                for (double s = 4.5; s < lg - 3; s += 7) {
                    double x = l[i] + dx * s - dz * 3.2 * cote, z = l[i + 1] + dz * s + dx * 3.2 * cote;
                    // vers l'allée : l'opposé du côté où il est
                    double vx = dz * cote, vz = -dx * cote;
                    int bx = Math.abs(vx) >= Math.abs(vz) ? (int) Math.signum(vx) : 0, bz = bx == 0 ? (int) Math.signum(vz) : 0;
                    int ici = LAMPES.size();
                    LAMPES.add(new Lampe((int) Math.floor(x), (int) Math.floor(z), bx, bz, couleur++));
                    if (avant >= 0) {
                        FILS.add(new Fil(avant, ici, n++ % 2 == 1, 0.75));
                    }
                    avant = ici;
                    cote = -cote;
                }
            }
        }
        // les drapeaux : un mât au milieu de chaque massif de fleurs de la place
        for (int k = 0; k < 8; k++) {
            double an = (k + 0.5) / 8 * Math.PI * 2;
            DRAPEAUX.add(new int[]{(int) Math.round(Math.cos(an) * 14), (int) Math.round(Math.sin(an) * 14), MAT});
        }
        // et sur les deux tours de l'entrée (au bout des tiges lumineuses)
        DRAPEAUX.add(new int[]{-10, 122, 23});
        DRAPEAUX.add(new int[]{10, 122, 23});
        // les ballons : au coin de chaque stand, côté avenue, et aux guichets de l'entrée
        for (int[] st : STANDS) {
            int devant = st[2] == 1 ? st[0] + 2 : st[0] - 2;
            BALLONS.add(new double[]{devant + 0.5, 5, st[1] - 1.5});
        }
        BALLONS.add(new double[]{-14.5, 4, 120.5});
        BALLONS.add(new double[]{17.5, 4, 120.5});
    }

    public static List<Lampe> lampes() {
        return Collections.unmodifiableList(LAMPES);
    }

    private Guirlandes() {
    }
}
