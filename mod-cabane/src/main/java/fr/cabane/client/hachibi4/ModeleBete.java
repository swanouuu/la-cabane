package fr.cabane.client.hachibi4;

import fr.cabane.pouvoir.Hachibi4;
import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;
import net.minecraft.world.phys.Vec3;

/**
 * La bête de l'ulti de Hachibi 4 : le modèle du Hachibi géant (docs/hachibi-geant, voir hachibi3/ModeleHachibi), aux
 * 2/3 (une unité de la maquette fait {@link Hachibi4#G} bloc), sans chaînes ni menottes, et qui peut bouger : chaque bras
 * en deux morceaux (l'épaule et le bras, puis l'avant-bras et le poing) pour frapper, et les huit tentacules dans trois
 * poses (en éventail, levées au-dessus de lui, abattues devant lui ; docs/hachibi4/generateur/bete.mjs) faites des mêmes
 * quadrilatères dans le même ordre, pour qu'on passe de l'une à l'autre point par point.
 */
final class ModeleBete {
    static final double G = Hachibi4.G;
    private static final double GRAIN = 8;

    private static final int AVANT = 0, ARRIERE = 1, GAUCHE = 2, DROITE = 3, HAUT = 4, BAS = 5;
    private static final int TROU = -1;

    private static final int PEAU = rgb(178, 108, 124), PEAU_CLAIRE = rgb(198, 132, 146), VENTRE = rgb(222, 170, 176),
            TRAIT = rgb(118, 60, 76), VENTOUSE = rgb(242, 232, 232), VENTOUSE_CREUX = rgb(214, 192, 196), VENTOUSE_BORD = rgb(160, 90, 108),
            OEIL_TOUR = rgb(232, 150, 168), OEIL = rgb(248, 236, 238), PUPILLE = rgb(46, 22, 28), DENT = rgb(246, 244, 234),
            NEZ = rgb(226, 166, 178), GORGE = rgb(92, 30, 42);

    /** Une partie qui bouge d'un bloc : ses quadrilatères (4 × x, y, z, u, v puis la normale) et leurs couleurs. */
    static final class Partie {
        private final List<float[]> liste = new ArrayList<>();
        private final List<Integer> teintes = new ArrayList<>();
        float[] d;
        int[] c;

        private Partie fin() {
            d = new float[liste.size() * 23];
            c = new int[liste.size()];
            for (int i = 0; i < c.length; i++) {
                System.arraycopy(liste.get(i), 0, d, i * 23, 23);
                c[i] = teintes.get(i);
            }
            liste.clear();
            teintes.clear();
            return this;
        }
    }

    private record Repere(UnaryOperator<Vec3> point, UnaryOperator<Vec3> dir, double echelle) {
    }

    @FunctionalInterface
    private interface Peinture {
        int couleur(int face, double x, double y);
    }

    private static final Vec3[] DROIT = {new Vec3(1, 0, 0), new Vec3(0, 1, 0), new Vec3(0, 0, 1)};
    private static final Repere CORPS_R = new Repere(p -> p.scale(G), d -> d, G);

    private static final Vec3 TETE_PIVOT = new Vec3(0, 17, 7);
    private static final double TETE_K = 1.4;
    private static final Vec3[] TETE_AXES = penche(12);
    private static final Repere TETE_R = new Repere(p -> TETE_PIVOT.add(versTete(p).scale(TETE_K)).scale(G), ModeleBete::versTete, TETE_K * G);

    static final Partie CORPS = new Partie(), TETE = new Partie(), MACHOIRE = new Partie();
    /** Le bras droit (côté -x) puis le gauche : le haut (épaule, bras, biceps) et le bas (avant-bras, poing). */
    static final Partie[] BRAS_HAUT = {new Partie(), new Partie()}, BRAS_BAS = {new Partie(), new Partie()};
    /** Les poses des tentacules : en éventail (au repos), levées, abattues. */
    static final int EVENTAIL = 0, LEVEES = 1, ABATTUES = 2;
    /** Les tentacules : leurs couleurs (les mêmes dans les trois poses) et leurs quadrilatères, pose par pose. */
    static final int[][] TENTACULES_C = new int[8][];
    static final float[][][] TENTACULES_D = new float[3][8][];
    static final Vec3 PIVOT_TETE = TETE_PIVOT.scale(G);
    static final Vec3 CHARNIERE = TETE_R.point().apply(new Vec3(0, -1.7, 3.2));
    static final Vec3[] EPAULES = new Vec3[2], COUDES = new Vec3[2];
    static final Vec3[] RACINES = new Vec3[8], COTES = new Vec3[8];

    /** Les huit : l'angle depuis l'arrière, la longueur, le départ, la montée, le redressement, l'enroulement, l'écart. */
    private static final double[][] TABLE = {
            {-16, 70, -8, 0.3, 92, 0.6, 0, -380, -0.9}, {16, 68, -8, 0.3, 94, 0.6, 0, 370, 0.9},
            {-48, 64, -12, 0.3, 96, 0.6, 12, 380, 0.6}, {48, 66, -12, 0.3, 100, 0.58, 8, -390, -0.4},
            {-82, 58, -14, 0.32, 90, 0.6, 4, -380, 0.5}, {82, 60, -14, 0.32, 88, 0.6, 8, 370, -0.6},
            {-118, 50, -10, 0.3, 22, 0.48, 8, 380, 0.6}, {118, 50, -10, 0.3, 24, 0.48, 6, 380, -0.6}};
    private static final int PAS_TENTACULE = 90;
    /**
     * Au repos, l'éventail est plus ouvert que celui du géant : vues de derrière (la caméra de celui qui est dans la bête),
     * les deux du milieu ne cachent plus son dos ni sa tête.
     */
    private static final double[] PHI_REPOS = {-75, 75, -95, 95, -115, 115, -135, 135};

    static {
        torse();
        bras();
        tete();
        CORPS.fin();
        TETE.fin();
        MACHOIRE.fin();
        for (int k = 0; k < 2; k++) {
            BRAS_HAUT[k].fin();
            BRAS_BAS[k].fin();
        }
        for (int i = 0; i < 8; i++) {
            for (int pose = 0; pose < 3; pose++) {
                Partie p = new Partie();
                tentacule(p, i, pose);
                p.fin();
                TENTACULES_D[pose][i] = p.d;
                TENTACULES_C[i] = p.c;
            }
        }
    }

    // ------------------------------------------------------------------ le corps

    private static void torse() {
        Vec3 h = new Vec3(0, 8, -5);
        Vec3[] ax = penche(35);
        cube(CORPS, CORPS_R, v(0, 5.5, -6.5), DROIT, 7.5, 5.5, 6.5, muscle(11, false), 0.5);
        cube(CORPS, CORPS_R, h.add(ax[1].scale(9)), ax, 9.5, 9, 6, ModeleBete::torse, 0.5);
        cube(CORPS, CORPS_R, h.add(ax[1].scale(16.2)).add(ax[2].scale(-1.2)), ax, 11, 3, 5.4, muscle(12, true), 0.5);
        cube(CORPS, CORPS_R, h.add(ax[1].scale(13)).add(ax[2].scale(-5.2)), penche(60), 8.5, 6.5, 3.2, muscle(13, true), 0.5);
        cube(CORPS, CORPS_R, v(0, 22.5, 7), penche(50), 3.4, 3.2, 3.6, muscle(14, true), 0.5);
    }

    private static int torse(int face, double x, double y) {
        double ax = Math.abs(x);
        if (face == AVANT) {
            double pec = 2.4 + 0.055 * x * x;
            if ((ligne(y - pec, 0.28) && ax < 8.6) || (ligne(x, 0.28) && y > -8)) {
                return TRAIT;
            }
            if (ax < 4.2 && y < pec - 0.3 && y > -8.4) {
                return ligne(y + 0.9, 0.2) || ligne(y + 4, 0.2) ? mix(PEAU, TRAIT, 0.45) : mix(PEAU_CLAIRE, PEAU, ax / 5);
            }
            if (ax > 5.2 && ax < 8.8 && y < 1 && y > -7 && ligne(((y + ax * 0.45) % 2.2) - 1.1, 0.22)) {
                return mix(PEAU, TRAIT, 0.5);
            }
            if (y > pec && ax < 8.6) {
                return PEAU_CLAIRE;
            }
        }
        if (face == ARRIERE && (ligne(x, 0.3) || (ligne(ax - 4.5 - 0.2 * y, 0.25) && y > 1))) {
            return mix(PEAU, TRAIT, 0.6);
        }
        return muscle(9, face != AVANT).couleur(face, x, y);
    }

    /** Les bras de gorille, posés sur leurs poings ; ils tournent à l'épaule, puis au coude. */
    private static void bras() {
        for (int k = 0; k < 2; k++) {
            int s = k == 0 ? -1 : 1;
            Partie haut = BRAS_HAUT[k], bas = BRAS_BAS[k];
            Vec3 epaule = v(s * 11.8, 20.5, 3.5), coude = v(s * 15.8, 11.5, 8), poignet = v(s * 13.6, 4.8, 13.5);
            EPAULES[k] = epaule.scale(G);
            COUDES[k] = coude.scale(G);
            cube(haut, CORPS_R, epaule, penche(20), 4.4, 4.2, 4.6, muscle(20 + s, true), 0.5);
            cube(haut, CORPS_R, milieu(epaule, coude), entre(coude, epaule), 3.5, epaule.distanceTo(coude) / 2 + 0.8, 3.7, muscle(22 + s, true), 0.5);
            cube(haut, CORPS_R, milieu(epaule, coude).add(0, -0.5, 1.6), entre(coude, epaule), 2.8, 3.2, 2.4, muscle(24 + s, true), 0.5);
            cube(bas, CORPS_R, milieu(coude, poignet), entre(poignet, coude), 3.4, coude.distanceTo(poignet) / 2 + 0.6, 3.5, muscle(26 + s, true), 0.5);
            cube(bas, CORPS_R, v(s * 13.6, 2.6, 14.6), DROIT, 3.3, 2.6, 3.5, ModeleBete::poing, 0.4);
            cube(bas, CORPS_R, v(s * 9.7, 3.4, 16.2), DROIT, 0.9, 1.1, 1.8, muscle(33, false), 0.4);
        }
    }

    private static int poing(int face, double x, double y) {
        boolean doigts = ligne(x + 1.65, 0.26) || ligne(x, 0.26) || ligne(x - 1.65, 0.26);
        if (face == AVANT && (ligne(y - 1.2, 0.26) || (y > -1.6 && doigts))) {
            return TRAIT;
        }
        return face == HAUT && y > 0 && doigts ? TRAIT : PEAU;
    }

    // ------------------------------------------------------------------ la tête de taureau

    private static void tete() {
        cube(TETE, TETE_R, v(0, 5.9, 1.2), DROIT, 4.0, 2.5, 3.4, (f, x, y) -> {
            if (f == AVANT) {
                int o = oeil(x - 2.2, y - 0.1);
                if (o == TROU) {
                    o = oeil(x + 2.2, y - 0.1);
                }
                return o != TROU ? o : stries(x, PEAU);
            }
            return stries(f == HAUT ? x : y, PEAU);
        }, 0.3);
        cube(TETE, TETE_R, v(0, 1.9, 1.6), DROIT, 3.0, 1.6, 3.0, (f, x, y) -> stries(f == GAUCHE || f == DROITE ? y : x, PEAU), 0.3);
        for (int c = -1; c <= 1; c += 2) {
            Vec3[] axes = {norme(v(1, -c * 0.32, 0)), norme(v(c * 0.32, 1, 0)), v(0, 0, 1)};
            cube(TETE, TETE_R, v(c * 2.2, 7.65, 4.85), axes, 2.0, 0.45, 0.5, (f, x, y) -> mix(PEAU, TRAIT, 0.35), 0.3);
        }
        cube(TETE, TETE_R, v(0, 8.9, 0.4), DROIT, 2.4, 0.7, 2.6, (f, x, y) -> stries(x, PEAU), 0.3);
        cube(TETE, TETE_R, v(0, 0.6, 2.4), DROIT, 2.8, 1.6, 2.2, (f, x, y) -> stries(f == GAUCHE || f == DROITE ? x : y, PEAU), 0.3);
        cube(TETE, TETE_R, v(0, -2.4, 2.8), DROIT, 2.3, 1.4, 1.9, (f, x, y) -> stries(f == GAUCHE || f == DROITE ? x : y, PEAU), 0.3);
        cube(TETE, TETE_R, v(0, 1.0, 5.6), DROIT, 2.3, 1.9, 1.5, (f, x, y) -> stries(f == HAUT ? x : f == GAUCHE || f == DROITE ? y : x, PEAU), 0.3);
        cube(TETE, TETE_R, v(0, 1.1, 7.35), DROIT, 2.1, 1.5, 0.3, ModeleBete::mufle, 0.3);
        cube(TETE, TETE_R, v(0, -1.35, 5.5), DROIT, 2.15, 0.45, 1.5, ModeleBete::dents, 0.2);
        cube(TETE, TETE_R, v(0, -2.05, 5.0), DROIT, 1.95, 0.4, 1.3, (f, x, y) -> GORGE, 0.4);
        cube(MACHOIRE, TETE_R, v(0, -2.65, 5.3), DROIT, 2.0, 0.35, 1.4, ModeleBete::dents, 0.2);
        cube(MACHOIRE, TETE_R, v(0, -3.6, 4.8), DROIT, 2.1, 0.7, 1.9, (f, x, y) -> stries(f == GAUCHE || f == DROITE ? y : x, PEAU), 0.3);
        // le fond de la gueule, qu'on voit quand elle s'ouvre grand
        cube(MACHOIRE, TETE_R, v(0, -2.9, 4.2), DROIT, 1.9, 0.5, 1.2, (f, x, y) -> GORGE, 0.4);
        for (int c = -1; c <= 1; c += 2) {
            Vec3 p = v(c * 3.8, 7.6, 1.0), d = norme(v(c, 0.35, 0.05));
            int n = 20;
            double pas = 18.0 / n;
            for (int k = 0; k < n; k++) {
                double w = 3.6 * (1 - (double) k / n * 0.8);
                int base = mix(PEAU, PEAU_CLAIRE, (double) k / n);
                cube(TETE, TETE_R, p.add(d.scale(pas / 2)), axesLe(d), w / 2, pas * 0.7, w / 2, (f, x, y) -> stries(x, base), 0.25);
                p = p.add(d.scale(pas));
                d = norme(tourner(d, v(0, 0, 1), -c * 0.13).add(0, 0, 0.04));
            }
            Vec3 q = v(c * 4.3, 5.4, 0.2), e = norme(v(c, -0.25, 0.15));
            for (int k = 0; k < 3; k++) {
                double w = 1.8 * (1 - k * 0.28);
                cube(TETE, TETE_R, q.add(e.scale(0.6)), axesLe(e), w / 2, 0.75, w * 0.22, (f, x, y) -> f == AVANT ? NEZ : PEAU, 0.25);
                q = q.add(e.scale(1.2));
            }
        }
    }

    private static int oeil(double x, double y) {
        double d = Math.hypot(x, y) / 1.2;
        return d < 0.28 ? PUPILLE : d < 0.62 ? OEIL : d < 0.9 ? OEIL_TOUR : d < 1.15 ? TRAIT : TROU;
    }

    private static int mufle(int face, double x, double y) {
        if (face == AVANT) {
            double ax = Math.abs(x), u = (ax - 1.05) * 0.94 - (y + 0.1) * 0.34, w = (ax - 1.05) * 0.34 + (y + 0.1) * 0.94;
            double d = Math.hypot(u / 0.42, w / 0.8);
            if (d < 1) {
                return PUPILLE;
            }
            if (d < 1.35) {
                return mix(NEZ, TRAIT, 0.35);
            }
        }
        return NEZ;
    }

    private static int dents(int face, double x, double y) {
        return face == HAUT || face == BAS || !ligne(((x + 9) % 0.62) - 0.31, 0.07) ? DENT : GORGE;
    }

    // ------------------------------------------------------------------ les tentacules

    /** Une tranche de tentacule : son centre, le long (A), le dessous aux ventouses (B). */
    private record Tranche(Vec3 c, Vec3 a, Vec3 b) {
    }

    /**
     * Une tentacule dans une pose : les mêmes tranches (largeur, longueur, peinture), seulement placées autrement. Les
     * ventouses sont sur le dessous et dépassent le long de ses bords.
     */
    private static void tentacule(Partie partie, int n, int pose) {
        double[] t = TABLE[n];
        double l = t[1], ds = l / PAS_TENTACULE;
        List<Double> ventouses = new ArrayList<>();
        for (double s = 3; s < l - 0.6; s += 0.92 * largeur(s / l)) {
            ventouses.add(s);
        }
        Tranche[] tranches = pose == EVENTAIL ? eventail(n) : chemin(n, pose);
        for (int i = 0; i < PAS_TENTACULE; i++) {
            Tranche tr = tranches[i];
            double u = (i + 0.5) / PAS_TENTACULE, w = largeur(u), s0 = i * ds + ds / 2;
            double grain = Math.min(0.55, Math.max(0.16, w / 8));
            cube(partie, CORPS_R, tr.c(), new Vec3[] {tr.a().cross(tr.b()), tr.a(), tr.b()}, w * 0.46, ds * 0.72, w / 2, (face, x, yy) -> {
                double bord = face == GAUCHE ? x + w / 2 : face == DROITE ? x - w / 2 : Double.NaN;
                if (face != AVANT && Double.isNaN(bord)) {
                    return PEAU;
                }
                double travers = face == AVANT ? x : bord * 1.6, s = s0 + yy;
                double meilleur = 9;
                for (double vs : ventouses) {
                    meilleur = Math.min(meilleur, Math.hypot(s - vs, travers) / (0.34 * largeur(vs / l)));
                }
                if (face != AVANT) {
                    return meilleur < 1 ? VENTOUSE : meilleur < 1.2 ? TRAIT : PEAU;
                }
                return meilleur < 0.45 ? VENTOUSE_CREUX : meilleur < 1 ? VENTOUSE : meilleur < 1.2 ? VENTOUSE_BORD : VENTRE;
            }, grain);
        }
    }

    /** Au repos, comme le géant : elle part du bassin vers l'extérieur, frôle le sol, se redresse, puis s'enroule. */
    private static Tranche[] eventail(int n) {
        double[] t = TABLE[n];
        double phi = Math.toRadians(PHI_REPOS[n]), l = t[1], u1 = t[3], a1 = t[4], u2 = t[5], a2 = t[6], cc = t[7], lat = t[8];
        Vec3 o = v(Math.sin(phi), 0, -Math.cos(phi)), cote = v(Math.cos(phi), 0, Math.sin(phi));
        double ds = l / PAS_TENTACULE;
        double r = 3.2, y = 5.8, th = Math.toRadians(t[2]);
        RACINES[n] = v(0, 0, -4.5).add(o.scale(r)).add(0, y, 0).scale(G);
        COTES[n] = cote;
        Tranche[] tranches = new Tranche[PAS_TENTACULE];
        for (int i = 0; i < PAS_TENTACULE; i++) {
            double u = (i + 0.5) / PAS_TENTACULE, k;
            if (u < u1) {
                k = a1 / u1;
            } else if (u < u2) {
                k = a2 / (u2 - u1);
            } else {
                double f = (u - u2) / (1 - u2);
                k = cc * 2.6 * Math.pow(f, 1.6) / (1 - u2);
            }
            th += Math.toRadians(k) / PAS_TENTACULE;
            Vec3 c = v(0, 0, -4.5).add(o.scale(r + Math.cos(th) * ds / 2)).add(0, y + Math.sin(th) * ds / 2, 0).add(cote.scale(lat * u * u * 9));
            r += Math.cos(th) * ds;
            y += Math.sin(th) * ds;
            tranches[i] = new Tranche(c, norme(o.scale(Math.cos(th)).add(0, Math.sin(th), 0)), norme(o.scale(Math.sin(th)).subtract(0, Math.cos(th), 0)));
        }
        return tranches;
    }

    /**
     * Levée ou abattue : la tentacule suit une suite de courbes de Bézier (celles de docs/hachibi4/generateur/bete.mjs),
     * ramenée à sa propre longueur ; le dessous regarde vers `dessous`.
     */
    private static Tranche[] chemin(int n, int pose) {
        double phi = TABLE[n][0], l = TABLE[n][1];
        int s = phi < 0 ? -1 : 1;
        double rang = Math.abs(phi) / 118;
        Vec3[][] morceaux;
        Vec3 dessous;
        if (pose == LEVEES) {
            // levées toutes ensemble, très haut au-dessus de sa tête, les bouts recourbés vers l'avant
            double r = s * (0.5 + Math.round((Math.abs(phi) - 16) / 34.0)), h = 64 - Math.abs(r) * 3 + (Math.round(Math.abs(r) + 0.5) % 2 == 1 ? 4 : 0);
            morceaux = new Vec3[][] {{v(r * 1.7, 6, -9), v(r * 3.4, 24, -24), v(r * 7.6, h - 14, -22), v(r * 8.5, h, -8)},
                    {v(r * 8.5, h, -8), v(r * 8.8, h + 4, 0), v(r * 8.8, h - 1, 8), v(r * 8.3, h - 7, 7)}};
            dessous = v(0, 0, 1);
        } else {
            // abattues devant lui comme une main qui se referme : celles du milieu par-dessus sa tête, celles des côtés
            // autour de ses flancs, et toutes à plat sur le sol, loin devant
            Vec3 o = v(Math.sin(Math.toRadians(phi)), 0, -Math.cos(Math.toRadians(phi)));
            Vec3 base = v(0, 5.8, -4.5).add(o.scale(3.2));
            double haut = 54 - rang * 42, cote = s * (4 + rang * 30), sol = s * (3 + rang * 26);
            morceaux = new Vec3[][] {{base, base.add(o.scale(12)).add(0, 20, 0), v(cote, haut, 10 + rang * 6), v(sol, 2.4, 40)},
                    {v(sol, 2.4, 40), v(sol * 1.05, 0.6, 48), v(sol * 1.15, 0.6, 60), v(sol * 1.25, 0.6, 72 - rang * 8)}};
            dessous = v(-s * rang, -1, 0.3);
        }
        // les points de la courbe, puis la longueur parcourue à chacun
        List<Vec3> pts = new ArrayList<>();
        for (int m = 0; m < morceaux.length; m++) {
            for (int i = m == 0 ? 0 : 1; i <= 60; i++) {
                pts.add(bezier(morceaux[m], i / 60.0));
            }
        }
        double[] longueurs = new double[pts.size()];
        for (int i = 1; i < pts.size(); i++) {
            longueurs[i] = longueurs[i - 1] + pts.get(i).distanceTo(pts.get(i - 1));
        }
        // ramenée à la longueur de la tentacule : la courbe garde sa forme, en plus petit (ou plus grand) depuis sa base
        double k = l / longueurs[pts.size() - 1];
        Vec3 depart = pts.get(0);
        for (int i = 0; i < pts.size(); i++) {
            pts.set(i, depart.add(pts.get(i).subtract(depart).scale(k)));
            longueurs[i] *= k;
        }
        double ds = l / PAS_TENTACULE;
        Tranche[] tranches = new Tranche[PAS_TENTACULE];
        Vec3 bas = norme(dessous);
        for (int i = 0; i < PAS_TENTACULE; i++) {
            Vec3 a = en(pts, longueurs, i * ds), b = en(pts, longueurs, (i + 1) * ds);
            Vec3 le = norme(b.subtract(a));
            Vec3 dos = bas.subtract(le.scale(le.dot(bas)));
            if (dos.lengthSqr() < 0.02) {
                dos = v(0, 0, -1).subtract(le.scale(le.z * -1));
            }
            tranches[i] = new Tranche(milieu(a, b), le, norme(dos));
        }
        return tranches;
    }

    private static Vec3 en(List<Vec3> pts, double[] longueurs, double s) {
        int i = 1;
        while (i < pts.size() - 1 && longueurs[i] < s) {
            i++;
        }
        double t = (s - longueurs[i - 1]) / Math.max(1e-6, longueurs[i] - longueurs[i - 1]);
        return pts.get(i - 1).lerp(pts.get(i), Math.min(1, t));
    }

    private static Vec3 bezier(Vec3[] p, double t) {
        double s = 1 - t;
        return p[0].scale(s * s * s).add(p[1].scale(3 * s * s * t)).add(p[2].scale(3 * s * t * t)).add(p[3].scale(t * t * t));
    }

    private static double largeur(double u) {
        return 0.9 + 9 * Math.pow(1 - u, 0.75);
    }

    // ------------------------------------------------------------------ les pavés, case par case

    private static void cube(Partie partie, Repere r, Vec3 c, Vec3[] axes, double hx, double hy, double hz, Peinture peinture, double pas) {
        Vec3 x = axes[0], y = axes[1], z = axes[2];
        face(partie, r, c, AVANT, z, hz, x, hx, y, hy, peinture, pas);
        face(partie, r, c, ARRIERE, z.scale(-1), hz, x.scale(-1), hx, y, hy, peinture, pas);
        face(partie, r, c, GAUCHE, x, hx, z.scale(-1), hz, y, hy, peinture, pas);
        face(partie, r, c, DROITE, x.scale(-1), hx, z, hz, y, hy, peinture, pas);
        face(partie, r, c, HAUT, y, hy, x, hx, z.scale(-1), hz, peinture, pas);
        face(partie, r, c, BAS, y.scale(-1), hy, x, hx, z, hz, peinture, pas);
    }

    /** Une face : ses cases peintes, puis regroupées en rectangles de même couleur. */
    private static void face(Partie partie, Repere r, Vec3 c, int nom, Vec3 n, double hn, Vec3 u, double hu, Vec3 v, double hv, Peinture peinture,
            double pas) {
        int nu = Math.max(1, (int) Math.round(2 * hu / pas)), nv = Math.max(1, (int) Math.round(2 * hv / pas));
        int[] cases = new int[nu * nv];
        for (int j = 0; j < nv; j++) {
            for (int i = 0; i < nu; i++) {
                cases[j * nu + i] = peinture.couleur(nom, -hu + (i + 0.5) * 2 * hu / nu, -hv + (j + 0.5) * 2 * hv / nv);
            }
        }
        Vec3 cf = c.add(n.scale(hn)), normale = r.dir().apply(n);
        boolean[] fait = new boolean[nu * nv];
        for (int j = 0; j < nv; j++) {
            for (int i = 0; i < nu; i++) {
                int teinte = cases[j * nu + i];
                if (fait[j * nu + i] || teinte == TROU) {
                    continue;
                }
                int i1 = i + 1;
                while (i1 < nu && !fait[j * nu + i1] && cases[j * nu + i1] == teinte) {
                    i1++;
                }
                int j1 = j + 1;
                grandir:
                while (j1 < nv) {
                    for (int k = i; k < i1; k++) {
                        if (fait[j1 * nu + k] || cases[j1 * nu + k] != teinte) {
                            break grandir;
                        }
                    }
                    j1++;
                }
                for (int jj = j; jj < j1; jj++) {
                    for (int k = i; k < i1; k++) {
                        fait[jj * nu + k] = true;
                    }
                }
                double[] a = {-hu + 2 * hu * i / nu, -hu + 2 * hu * i1 / nu}, b = {-hv + 2 * hv * j / nv, -hv + 2 * hv * j1 / nv};
                float[] q = new float[23];
                int[][] coins = {{0, 0}, {1, 0}, {1, 1}, {0, 1}};
                for (int k = 0; k < 4; k++) {
                    double ca = a[coins[k][0]], cb = b[coins[k][1]];
                    Vec3 p = r.point().apply(cf.add(u.scale(ca)).add(v.scale(cb)));
                    q[k * 5] = (float) p.x;
                    q[k * 5 + 1] = (float) p.y;
                    q[k * 5 + 2] = (float) p.z;
                    q[k * 5 + 3] = (float) (ca * r.echelle() / GRAIN + nom * 0.37);
                    q[k * 5 + 4] = (float) (cb * r.echelle() / GRAIN + nom * 0.61);
                }
                q[20] = (float) normale.x;
                q[21] = (float) normale.y;
                q[22] = (float) normale.z;
                partie.liste.add(q);
                partie.teintes.add(0xFF000000 | teinte);
            }
        }
    }

    // ------------------------------------------------------------------ les peintures et les outils

    private static Peinture muscle(int graine, boolean stries) {
        return (face, x, y) -> {
            if (stries && face != HAUT && face != BAS) {
                double t = x / 2.1 + 0.18 * Math.sin(y * 0.6 + graine);
                if (Math.abs(t - Math.round(t)) < 0.07) {
                    return mix(PEAU, TRAIT, 0.55);
                }
            }
            return PEAU;
        };
    }

    private static int stries(double x, int base) {
        double t = x / 0.9 + 0.5;
        return Math.abs(t - Math.round(t)) < 0.12 ? mix(base, TRAIT, 0.45) : base;
    }

    private static boolean ligne(double d, double epais) {
        return Math.abs(d) < epais;
    }

    private static int rgb(int r, int g, int b) {
        return r << 16 | g << 8 | b;
    }

    private static int mix(int a, int b, double k) {
        int r = (int) Math.round((a >> 16 & 255) + ((b >> 16 & 255) - (a >> 16 & 255)) * k);
        int g = (int) Math.round((a >> 8 & 255) + ((b >> 8 & 255) - (a >> 8 & 255)) * k);
        int bl = (int) Math.round((a & 255) + ((b & 255) - (a & 255)) * k);
        return rgb(r, g, bl);
    }

    private static Vec3 v(double x, double y, double z) {
        return new Vec3(x, y, z);
    }

    private static Vec3 norme(Vec3 v) {
        return v.normalize();
    }

    private static Vec3 milieu(Vec3 a, Vec3 b) {
        return a.add(b).scale(0.5);
    }

    private static Vec3 tourner(Vec3 v, Vec3 n, double a) {
        double c = Math.cos(a), s = Math.sin(a);
        return v.scale(c).add(n.cross(v).scale(s)).add(n.scale(n.dot(v) * (1 - c)));
    }

    private static Vec3[] penche(double degres) {
        double a = Math.toRadians(degres);
        return new Vec3[] {v(1, 0, 0), v(0, Math.cos(a), Math.sin(a)), v(0, -Math.sin(a), Math.cos(a))};
    }

    private static Vec3[] entre(Vec3 bas, Vec3 haut) {
        Vec3 y = norme(haut.subtract(bas)), x = norme(y.cross(v(0, 0, 1)));
        return new Vec3[] {x, y, x.cross(y)};
    }

    private static Vec3[] axesLe(Vec3 d) {
        Vec3 y = norme(d), x = y.cross(v(0, 0, 1));
        if (x.length() < 0.2) {
            x = y.cross(v(1, 0, 0));
        }
        x = norme(x);
        return new Vec3[] {x, y, x.cross(y)};
    }

    private static Vec3 versTete(Vec3 v) {
        return TETE_AXES[0].scale(v.x).add(TETE_AXES[1].scale(v.y)).add(TETE_AXES[2].scale(v.z));
    }

    private ModeleBete() {
    }
}
