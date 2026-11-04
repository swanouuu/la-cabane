package fr.cabane.client.hachibi3;

import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;
import net.minecraft.world.phys.Vec3;

/**
 * Le modèle du Hachibi géant, tiré de la maquette validée (docs/hachibi-geant/generateur/rendu.mjs) : les mêmes pavés, les
 * mêmes couleurs case par case. Les cases voisines de même couleur sont fusionnées en un seul quadrilatère, et le grain
 * des pixels vient de la texture (elle se répète tous les 8 blocs). Tout est calculé une fois, en blocs, la bête regardant
 * vers +z et les pieds à y = 0 ; le rendu ne fait que bouger les parties (le corps qui respire, la tête et la mâchoire
 * qui rugissent, les tentacules qui ondulent).
 */
final class ModeleHachibi {
    /** Une unité de la maquette fait 1,5 bloc. */
    static final double G = 1.5;
    private static final double GRAIN = 8;

    private static final int AVANT = 0, ARRIERE = 1, GAUCHE = 2, DROITE = 3, HAUT = 4, BAS = 5;
    private static final int TROU = -1;

    // le rose des tentacules du niveau 4 de Hachibi 2, partout
    private static final int PEAU = rgb(178, 108, 124), PEAU_CLAIRE = rgb(198, 132, 146), VENTRE = rgb(222, 170, 176),
            TRAIT = rgb(118, 60, 76), VENTOUSE = rgb(242, 232, 232), VENTOUSE_CREUX = rgb(214, 192, 196), VENTOUSE_BORD = rgb(160, 90, 108),
            OEIL_TOUR = rgb(232, 150, 168), OEIL = rgb(248, 236, 238), PUPILLE = rgb(46, 22, 28), DENT = rgb(246, 244, 234),
            NEZ = rgb(226, 166, 178), GORGE = rgb(92, 30, 42);
    static final int FER = 0xFF3E3E46, FER_CLAIR = 0xFF6C6C78;

    /** Une partie qui bouge d'un bloc : ses quadrilatères (4 × x, y, z, u, v puis la normale) et leurs couleurs. */
    static final class Partie {
        private final List<float[]> liste = new ArrayList<>();
        private final List<Integer> teintes = new ArrayList<>();
        float[] d;
        int[] c;

        private void fin() {
            d = new float[liste.size() * 23];
            c = new int[liste.size()];
            for (int i = 0; i < c.length; i++) {
                System.arraycopy(liste.get(i), 0, d, i * 23, 23);
                c[i] = teintes.get(i);
            }
            liste.clear();
            teintes.clear();
        }
    }

    /** D'un pavé (en unités de la maquette) vers le modèle (en blocs) : les points, les directions, et l'échelle. */
    private record Repere(UnaryOperator<Vec3> point, UnaryOperator<Vec3> dir, double echelle) {
    }

    @FunctionalInterface
    private interface Peinture {
        /** La couleur d'une case : x, y sa place sur la face, depuis le centre (en unités de la maquette). */
        int couleur(int face, double x, double y);
    }

    private static final Vec3[] DROIT = {new Vec3(1, 0, 0), new Vec3(0, 1, 0), new Vec3(0, 0, 1)};
    private static final Repere CORPS_R = new Repere(p -> p.scale(G), d -> d, G);

    /** La tête : son pivot (la nuque), sa taille, et son penché vers l'avant. */
    private static final Vec3 TETE_PIVOT = new Vec3(0, 17, 7);
    private static final double TETE_K = 1.4;
    private static final Vec3[] TETE_AXES = penche(12);
    private static final Repere TETE_R = new Repere(p -> TETE_PIVOT.add(versTete(p).scale(TETE_K)).scale(G), ModeleHachibi::versTete, TETE_K * G);

    static final Partie CORPS = new Partie(), TETE = new Partie(), MACHOIRE = new Partie();
    /** Le bras droit (côté -x) puis le gauche. */
    static final Partie[] BRAS = {new Partie(), new Partie()};
    static final Partie[] TENTACULES = new Partie[8];
    /** Autour de quoi tournent la tête et la mâchoire (en blocs). */
    static final Vec3 PIVOT_TETE = TETE_PIVOT.scale(G);
    static final Vec3 CHARNIERE = TETE_R.point().apply(new Vec3(0, -1.7, 3.2));
    /** La racine de chaque tentacule, et l'axe (horizontal) autour duquel elle ondule. */
    static final Vec3[] RACINES = new Vec3[8], COTES = new Vec3[8];
    /** Les chaînes : des menottes (poignets) et du collier (cou) jusqu'au haut de leur piquet, planté dans le sol. */
    static final Vec3[] CHAINES_DEBUT = new Vec3[4], PIQUETS = new Vec3[4];
    /**
     * Où s'enroulent les chaînes du rituel (voir ClientRituel), en blocs : le collier et le torse (ils bougent avec le
     * corps), les deux menottes (avec les bras), puis les deux grandes tentacules du milieu (avec elles). Chacune a son
     * axe (celui du membre) et le rayon du tour de chaîne.
     */
    public static final int PRISE_COU = 0, PRISE_TORSE = 1, PRISE_POIGNET = 2, PRISE_TENTACULE = 4, NB_PRISES = 6;
    static final Vec3[] PRISES = new Vec3[NB_PRISES], AXES_PRISES = new Vec3[NB_PRISES];
    public static final double[] RAYONS_PRISES = {6.4, 14.5, 6.0, 6.0, 5.2, 5.2};

    /** Les huit : l'angle depuis l'arrière, la longueur, le départ, la montée, le redressement, l'enroulement, l'écart. */
    private static final double[][] TABLE = {
            {-16, 70, -8, 0.3, 92, 0.6, 0, -380, -0.9}, {16, 68, -8, 0.3, 94, 0.6, 0, 370, 0.9},
            {-48, 64, -12, 0.3, 96, 0.6, 12, 380, 0.6}, {48, 66, -12, 0.3, 100, 0.58, 8, -390, -0.4},
            {-82, 58, -14, 0.32, 90, 0.6, 4, -380, 0.5}, {82, 60, -14, 0.32, 88, 0.6, 8, 370, -0.6},
            {-118, 50, -10, 0.3, 22, 0.48, 8, 380, 0.6}, {118, 50, -10, 0.3, 24, 0.48, 6, 380, -0.6}};

    static {
        torse();
        bras();
        tete();
        for (int i = 0; i < 8; i++) {
            TENTACULES[i] = new Partie();
            tentacule(i);
        }
        CORPS.fin();
        TETE.fin();
        MACHOIRE.fin();
        for (Partie p : BRAS) {
            p.fin();
        }
        for (Partie p : TENTACULES) {
            p.fin();
        }
        priseTentacule(0);
        priseTentacule(1);
    }

    // ------------------------------------------------------------------ le corps

    private static void torse() {
        Vec3 h = new Vec3(0, 8, -5);
        Vec3[] ax = penche(35);
        // le bassin, d'où partent les tentacules
        cube(CORPS, CORPS_R, v(0, 5.5, -6.5), DROIT, 7.5, 5.5, 6.5, muscle(11, false), 0.5);
        cube(CORPS, CORPS_R, h.add(ax[1].scale(9)), ax, 9.5, 9, 6, ModeleHachibi::torse, 0.5);
        // les trapèzes, plus larges, et la grosse bosse du haut du dos
        cube(CORPS, CORPS_R, h.add(ax[1].scale(16.2)).add(ax[2].scale(-1.2)), ax, 11, 3, 5.4, muscle(12, true), 0.5);
        cube(CORPS, CORPS_R, h.add(ax[1].scale(13)).add(ax[2].scale(-5.2)), penche(60), 8.5, 6.5, 3.2, muscle(13, true), 0.5);
        // le cou, épais, qui pousse la tête en avant, et le collier de fer
        Vec3[] cou = penche(50);
        cube(CORPS, CORPS_R, v(0, 22.5, 7), cou, 3.4, 3.2, 3.6, muscle(14, true), 0.5);
        Vec3 collier = v(0, 22.5, 7).add(cou[1].scale(-1.6));
        cube(CORPS, CORPS_R, collier, cou, 3.9, 1.0, 4.1, fer(1.0), 0.5);
        PRISES[PRISE_COU] = collier.scale(G);
        AXES_PRISES[PRISE_COU] = cou[1];
        PRISES[PRISE_TORSE] = h.add(ax[1].scale(9)).scale(G);
        AXES_PRISES[PRISE_TORSE] = ax[1];
        for (int k = 0; k < 2; k++) {
            int s = k == 0 ? -1 : 1;
            CHAINES_DEBUT[2 + k] = collier.add(s * 3.9, 0, 0).scale(G);
            PIQUETS[2 + k] = v(s * 16, 0, 26).scale(G);
        }
    }

    /** Le torse : les pectoraux et les abdos devant, la colonne et les omoplates dans le dos. */
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
            // les côtes sur les flancs
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

    /** Les bras de gorille : l'épaule, le bras, l'avant-bras énorme, le poing posé par terre, et la menotte. */
    private static void bras() {
        for (int k = 0; k < 2; k++) {
            int s = k == 0 ? -1 : 1;
            Partie p = BRAS[k];
            Vec3 epaule = v(s * 11.8, 20.5, 3.5), coude = v(s * 15.8, 11.5, 8), poignet = v(s * 13.6, 4.8, 13.5);
            cube(p, CORPS_R, epaule, penche(20), 4.4, 4.2, 4.6, muscle(20 + s, true), 0.5);
            cube(p, CORPS_R, milieu(epaule, coude), entre(coude, epaule), 3.5, epaule.distanceTo(coude) / 2 + 0.8, 3.7, muscle(22 + s, true), 0.5);
            // le biceps, qui gonfle devant
            cube(p, CORPS_R, milieu(epaule, coude).add(0, -0.5, 1.6), entre(coude, epaule), 2.8, 3.2, 2.4, muscle(24 + s, true), 0.5);
            cube(p, CORPS_R, milieu(coude, poignet), entre(poignet, coude), 3.4, coude.distanceTo(poignet) / 2 + 0.6, 3.5, muscle(26 + s, true), 0.5);
            // le poing : les jointures devant, les doigts repliés dessus, le pouce à l'intérieur
            cube(p, CORPS_R, v(s * 13.6, 2.6, 14.6), DROIT, 3.3, 2.6, 3.5, ModeleHachibi::poing, 0.4);
            cube(p, CORPS_R, v(s * 9.7, 3.4, 16.2), DROIT, 0.9, 1.1, 1.8, muscle(33, false), 0.4);
            // la menotte de fer, juste au-dessus du poing
            Vec3 menotte = poignet.lerp(coude, 0.2);
            Vec3[] axes = entre(poignet, coude);
            cube(p, CORPS_R, menotte, axes, 3.9, 1.1, 4.0, fer(1.1), 0.5);
            Vec3 dehors = axes[0].x * s > 0 ? axes[0] : axes[0].scale(-1);
            CHAINES_DEBUT[k] = menotte.add(dehors.scale(3.9)).scale(G);
            PIQUETS[k] = v(s * 22, 0, 19).scale(G);
            PRISES[PRISE_POIGNET + k] = menotte.scale(G);
            AXES_PRISES[PRISE_POIGNET + k] = axes[1];
        }
    }

    /** La prise d'une tentacule : le milieu de ses quadrilatères vers le tiers de sa longueur, et la direction qu'elle y prend. */
    private static void priseTentacule(int i) {
        Partie p = TENTACULES[i];
        PRISES[PRISE_TENTACULE + i] = moyenne(p, 0.36, 0.40);
        AXES_PRISES[PRISE_TENTACULE + i] = norme(moyenne(p, 0.40, 0.43).subtract(moyenne(p, 0.33, 0.36)));
    }

    private static Vec3 moyenne(Partie p, double de, double a) {
        int n = p.c.length, i0 = (int) (n * de), i1 = Math.max(i0 + 1, (int) (n * a));
        double x = 0, y = 0, z = 0;
        for (int q = i0; q < i1; q++) {
            for (int k = 0; k < 4; k++) {
                int s = q * 23 + k * 5;
                x += p.d[s];
                y += p.d[s + 1];
                z += p.d[s + 2];
            }
        }
        double m = 4.0 * (i1 - i0);
        return new Vec3(x / m, y / m, z / m);
    }

    private static int poing(int face, double x, double y) {
        boolean doigts = ligne(x + 1.65, 0.26) || ligne(x, 0.26) || ligne(x - 1.65, 0.26);
        if (face == AVANT && (ligne(y - 1.2, 0.26) || (y > -1.6 && doigts))) {
            return TRAIT;
        }
        return face == HAUT && y > 0 && doigts ? TRAIT : PEAU;
    }

    // ------------------------------------------------------------------ la tête de taureau

    /**
     * Le long visage qui s'affine vers le bas, en V : le front large avec les yeux ronds sous une arcade en colère, l'arête
     * du nez, le mufle étroit et ses naseaux, la gueule entrouverte pleine de dents, les cornes épaisses qui se recourbent
     * vers le bas autour du visage, et les oreilles à l'horizontale.
     */
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
        // les joues, de plus en plus étroites jusqu'à la mâchoire
        cube(TETE, TETE_R, v(0, 0.6, 2.4), DROIT, 2.8, 1.6, 2.2, (f, x, y) -> stries(f == GAUCHE || f == DROITE ? x : y, PEAU), 0.3);
        cube(TETE, TETE_R, v(0, -2.4, 2.8), DROIT, 2.3, 1.4, 1.9, (f, x, y) -> stries(f == GAUCHE || f == DROITE ? x : y, PEAU), 0.3);
        // le mufle, étroit et court, avec ses gros naseaux
        cube(TETE, TETE_R, v(0, 1.0, 5.6), DROIT, 2.3, 1.9, 1.5, (f, x, y) -> stries(f == HAUT ? x : f == GAUCHE || f == DROITE ? y : x, PEAU), 0.3);
        cube(TETE, TETE_R, v(0, 1.1, 7.35), DROIT, 2.1, 1.5, 0.3, ModeleHachibi::mufle, 0.3);
        // la gueule : les dents du haut et le fond restent avec la tête, les dents du bas et la mâchoire s'ouvrent
        cube(TETE, TETE_R, v(0, -1.35, 5.5), DROIT, 2.15, 0.45, 1.5, ModeleHachibi::dents, 0.2);
        cube(TETE, TETE_R, v(0, -2.05, 5.0), DROIT, 1.95, 0.4, 1.3, (f, x, y) -> GORGE, 0.4);
        cube(MACHOIRE, TETE_R, v(0, -2.65, 5.3), DROIT, 2.0, 0.35, 1.4, ModeleHachibi::dents, 0.2);
        cube(MACHOIRE, TETE_R, v(0, -3.6, 4.8), DROIT, 2.1, 0.7, 1.9, (f, x, y) -> stries(f == GAUCHE || f == DROITE ? y : x, PEAU), 0.3);
        for (int c = -1; c <= 1; c += 2) {
            // la corne : épaisse à la base, sur le haut du crâne ; elle part sur le côté, puis se recourbe vers le bas
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
            // l'oreille, à l'horizontale sous la corne, rose clair dedans
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

    /** Le bout du mufle : deux grosses virgules penchées vers l'extérieur. */
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

    /** Des dents : blanches, séparées par de fins traits sombres. */
    private static int dents(int face, double x, double y) {
        return face == HAUT || face == BAS || !ligne(((x + 9) % 0.62) - 0.31, 0.07) ? DENT : GORGE;
    }

    // ------------------------------------------------------------------ les tentacules

    /**
     * Une tentacule : elle part du bassin vers l'extérieur, frôle le sol, se redresse vers le ciel, puis s'enroule au bout.
     * Les ventouses sont sur le dessous (qui devient l'intérieur de la spirale) et dépassent le long de ses bords.
     */
    private static void tentacule(int n) {
        double[] t = TABLE[n];
        double phi = Math.toRadians(t[0]), l = t[1], u1 = t[3], a1 = t[4], u2 = t[5], a2 = t[6], cc = t[7], lat = t[8];
        Vec3 o = v(Math.sin(phi), 0, -Math.cos(phi)), cote = v(Math.cos(phi), 0, Math.sin(phi));
        int pas = 90;
        double ds = l / pas;
        List<Double> ventouses = new ArrayList<>();
        for (double s = 3; s < l - 0.6; s += 0.92 * largeur(s / l)) {
            ventouses.add(s);
        }
        double r = 3.2, y = 5.8, th = Math.toRadians(t[2]);
        RACINES[n] = v(0, 0, -4.5).add(o.scale(r)).add(0, y, 0).scale(G);
        COTES[n] = cote;
        for (int i = 0; i < pas; i++) {
            double u = (i + 0.5) / pas, k;
            if (u < u1) {
                k = a1 / u1;
            } else if (u < u2) {
                k = a2 / (u2 - u1);
            } else {
                double f = (u - u2) / (1 - u2);
                k = cc * 2.6 * Math.pow(f, 1.6) / (1 - u2);
            }
            th += Math.toRadians(k) / pas;
            Vec3 c = v(0, 0, -4.5).add(o.scale(r + Math.cos(th) * ds / 2)).add(0, y + Math.sin(th) * ds / 2, 0).add(cote.scale(lat * u * u * 9));
            r += Math.cos(th) * ds;
            y += Math.sin(th) * ds;
            Vec3 a = norme(o.scale(Math.cos(th)).add(0, Math.sin(th), 0)), b = norme(o.scale(Math.sin(th)).subtract(0, Math.cos(th), 0));
            double w = largeur(u), s0 = i * ds + ds / 2;
            double grain = Math.min(0.55, Math.max(0.16, w / 8));
            cube(TENTACULES[n], CORPS_R, c, new Vec3[] {a.cross(b), a, b}, w * 0.46, ds * 0.72, w / 2, (face, x, yy) -> {
                // sur le dessous, en plein ; sur les côtés, la moitié qui dépasse du bord
                double bord = face == GAUCHE ? x + w / 2 : face == DROITE ? x - w / 2 : Double.NaN;
                if (face != AVANT && Double.isNaN(bord)) {
                    return PEAU;
                }
                double travers = face == AVANT ? x : bord * 1.6, s = s0 + yy;
                double meilleur = 9;
                for (double v : ventouses) {
                    meilleur = Math.min(meilleur, Math.hypot(s - v, travers) / (0.34 * largeur(v / l)));
                }
                if (face != AVANT) {
                    return meilleur < 1 ? VENTOUSE : meilleur < 1.2 ? TRAIT : PEAU;
                }
                return meilleur < 0.45 ? VENTOUSE_CREUX : meilleur < 1 ? VENTOUSE : meilleur < 1.2 ? VENTOUSE_BORD : VENTRE;
            }, grain);
        }
    }

    private static double largeur(double u) {
        return 0.9 + 9 * Math.pow(1 - u, 0.75);
    }

    // ------------------------------------------------------------------ les pavés, case par case

    /** Un pavé orienté : son centre, ses axes [côté, haut, avant] et ses demi-tailles, peint par cases de `pas`. */
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

    /** La peau, avec ses stries de muscles le long du membre. */
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

    /** Le fer des menottes et du collier, plus clair sur les bords. */
    private static Peinture fer(double hy) {
        return (face, x, y) -> (face == HAUT || face == BAS || Math.abs(y) > hy - 0.3 ? FER_CLAIR : FER) & 0xFFFFFF;
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

    /** v tourné d'un angle autour de l'axe n (Rodrigues). */
    private static Vec3 tourner(Vec3 v, Vec3 n, double a) {
        double c = Math.cos(a), s = Math.sin(a);
        return v.scale(c).add(n.cross(v).scale(s)).add(n.scale(n.dot(v) * (1 - c)));
    }

    /** Les axes d'un pavé penché en avant (le haut part vers +z), en degrés. */
    private static Vec3[] penche(double degres) {
        double a = Math.toRadians(degres);
        return new Vec3[] {v(1, 0, 0), v(0, Math.cos(a), Math.sin(a)), v(0, -Math.sin(a), Math.cos(a))};
    }

    /** Les axes d'un membre, du bas vers le haut. */
    private static Vec3[] entre(Vec3 bas, Vec3 haut) {
        Vec3 y = norme(haut.subtract(bas)), x = norme(y.cross(v(0, 0, 1)));
        return new Vec3[] {x, y, x.cross(y)};
    }

    /** Les axes d'un pavé allongé le long de d. */
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

    private ModeleHachibi() {
    }
}
