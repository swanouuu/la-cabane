package fr.cabane.client.pouvoir;

import org.joml.Matrix4f;

/**
 * Les mouvements du Susano parfait, les mêmes que dans l'aperçu docs/susano4/apercu (susano.html) : la garde qui respire,
 * la marche et la course (un vrai cycle de pas : le bassin qui descend et pivote, le buste en sens inverse, les bras en
 * léger retard, les genoux qui plient au bon moment, les ailes repliées qui ondulent), le vol (descente rapide des ailes,
 * remontée lente qui replie, le bout en retard) et les coups (il arme, frappe vite en avançant d'un pas, suit, revient).
 * Chaque articulation tourne autour de son pivot (angles x, y, z, dans l'ordre Y puis X puis Z), à la suite de celle
 * au-dessus d'elle. x négatif lève un bras ou une jambe vers l'avant, x positif penche le corps ou plie le genou ; z
 * négatif écarte le bras ou la jambe droite ; y positif tourne vers la gauche. Aile droite : z négatif la lève, y négatif
 * la rabat en arrière.
 */
final class AnimSusano4 {
    static final int BASSIN = 0, TORSE = 1, TETE = 2, BRAS_D = 3, BRAS_G = 4, AVANT_D = 5, AVANT_G = 6, MAIN_D = 7, MAIN_G = 8, CUISSE_D = 9,
            CUISSE_G = 10, TIBIA_D = 11, TIBIA_G = 12, AILE_D = 13, AILE_G = 14, BOUT_D = 15, BOUT_G = 16, N = 17;
    private static final double TOUR = Math.PI * 2;

    /** Une pose : les angles de chaque articulation et le déplacement du bassin. */
    static final class Pose {
        final double[][] a = new double[N][3];
        final double[] t = new double[3];

        Pose m(int i, double x, double y, double z) {
            a[i][0] = x;
            a[i][1] = y;
            a[i][2] = z;
            return this;
        }

        Pose m(int i, double x) {
            return m(i, x, 0, 0);
        }

        Pose m(int i, double[] v) {
            return m(i, v[0], v[1], v[2]);
        }

        /** Va vers b de k (0 à 1). */
        Pose vers(Pose b, double k) {
            if (k <= 0) {
                return this;
            }
            for (int i = 0; i < N; i++) {
                for (int j = 0; j < 3; j++) {
                    a[i][j] += (b.a[i][j] - a[i][j]) * k;
                }
            }
            for (int j = 0; j < 3; j++) {
                t[j] += (b.t[j] - t[j]) * k;
            }
            return this;
        }

        /** Les matrices des articulations (dans le repère du modèle), à la suite des parents. */
        Matrix4f[] matrices() {
            Matrix4f[] M = new Matrix4f[N];
            float[][] pv = ModeleSusano4.pivots;
            int[] pa = ModeleSusano4.parents;
            for (int i = 0; i < N; i++) {
                float[] p = pv[i];
                Matrix4f r = new Matrix4f().translate(p[0], p[1], p[2]).rotateY((float) a[i][1]).rotateX((float) a[i][0]).rotateZ((float) a[i][2])
                        .translate(-p[0], -p[1], -p[2]);
                M[i] = pa[i] < 0 ? new Matrix4f().translate((float) t[0], (float) t[1], (float) t[2]).mul(r) : new Matrix4f(M[pa[i]]).mul(r);
            }
            return M;
        }
    }

    private static double lisse(double t) {
        t = Math.max(0, Math.min(1, t));
        return t * t * (3 - 2 * t);
    }

    private static double pos(double x) {
        return Math.max(0, x);
    }

    // ================================================================ la garde

    static Pose garde(double t) {
        Pose P = new Pose();
        double r = Math.sin(t * 1.5), lent = Math.sin(t * 0.45), tres = Math.sin(t * 0.27);
        P.m(BASSIN, 0, 0.04 * lent, 0.025 * lent);
        P.m(TORSE, 0.03 + 0.025 * r, -0.03 * lent, -0.02 * lent);
        P.m(TETE, -0.05 - 0.02 * r, 0.3 * tres, 0.03 * lent);
        P.m(BRAS_D, -0.06 + 0.03 * r, 0, -0.1 - 0.025 * r).m(BRAS_G, -0.06 + 0.03 * r, 0, 0.1 + 0.025 * r);
        P.m(AVANT_D, -0.28 - 0.03 * r).m(AVANT_G, -0.28 - 0.03 * r);
        P.m(CUISSE_D, -0.04, 0.1, -0.07 - 0.02 * lent).m(CUISSE_G, 0.03, -0.1, 0.07 - 0.02 * lent);
        P.m(TIBIA_D, 0.1 + 0.03 * pos(lent)).m(TIBIA_G, 0.07 + 0.03 * pos(-lent));
        double a = 0.07 * Math.sin(t * 1.1), b = 0.06 * Math.sin(t * 1.1 - 0.8);
        P.m(AILE_D, 0, -0.06, -a).m(AILE_G, 0, 0.06, a);
        P.m(BOUT_D, 0, -0.03, -b).m(BOUT_G, 0, 0.03, b);
        P.t[0] = 0.12 * lent;
        P.t[1] = -0.1 + 0.05 * r;
        return P;
    }

    // ================================================================ la marche et la course

    /** Un pas de marche ou de course ; ph : où on en est du cycle (en tours, la jambe droite avance quand sin > 0). */
    static Pose pas(double ph, boolean course) {
        Pose P = new Pose();
        double a = (ph % 1) * TOUR, s = Math.sin(a), c = Math.cos(a);
        double amp = course ? 0.95 : 0.5, pli = course ? 1.75 : 0.95, appui = course ? 0.4 : 0.14, base = course ? -0.28 : -0.04;
        P.m(CUISSE_D, -amp * s + base, 0, -0.03).m(CUISSE_G, amp * s + base, 0, 0.03);
        P.m(TIBIA_D, 0.1 + pli * Math.pow(pos(c), 1.4) + appui * pos(-c) * pos(s + 0.3));
        P.m(TIBIA_G, 0.1 + pli * Math.pow(pos(-c), 1.4) + appui * pos(c) * pos(-s + 0.3));
        double lacet = (course ? 0.16 : 0.11) * s;
        P.m(BASSIN, course ? 0.18 : 0.04, lacet, (course ? 0.03 : 0.045) * c);
        P.m(TORSE, course ? 0.2 : 0.05, -lacet * 1.7 - (course ? 0.05 : 0.03) * Math.sin(a - 0.4), -(course ? 0.02 : 0.03) * c);
        P.m(TETE, course ? -0.32 : -0.08, lacet * 0.6 + 0.02 * Math.sin(a - 0.8), 0.02 * c);
        double sb = Math.sin(a - 0.3), ampB = course ? 0.85 : 0.45;
        P.m(BRAS_D, ampB * sb - (course ? 0.2 : 0), 0, -0.12 - (course ? 0.08 : 0));
        P.m(BRAS_G, -ampB * sb - (course ? 0.2 : 0), 0, 0.12 + (course ? 0.08 : 0));
        P.m(AVANT_D, course ? -1.25 - 0.35 * pos(-sb) : -0.25 - 0.4 * pos(-sb));
        P.m(AVANT_G, course ? -1.25 - 0.35 * pos(sb) : -0.25 - 0.4 * pos(sb));
        P.t[0] = 0.07 * c;
        P.t[1] = course ? 0.55 - 0.95 * Math.abs(c) : -0.42 * (1 - Math.abs(c));
        double repli = course ? 0.85 : 0.4, ond = (course ? 0.1 : 0.05) * Math.sin(a * 2 - 0.6), ond2 = (course ? 0.12 : 0.06) * Math.sin(a * 2 - 1.5);
        P.m(AILE_D, 0.05, -repli, (course ? 0.22 : 0.1) + ond).m(AILE_G, 0.05, repli, -(course ? 0.22 : 0.1) - ond);
        P.m(BOUT_D, 0, -(course ? 0.35 : 0.15), ond2).m(BOUT_G, 0, course ? 0.35 : 0.15, -ond2);
        return P;
    }

    // ================================================================ le vol

    private static double battement(double ph) {
        ph = ((ph % 1) + 1) % 1;
        return ph < 0.4 ? 1 - 2 * lisse(ph / 0.4) : -1 + 2 * lisse((ph - 0.4) / 0.6);
    }

    /** Le vol ; t : le temps (s). Le corps presque à l'horizontale ; il monte un peu à chaque descente des ailes. */
    static Pose vol(double t) {
        Pose P = new Pose();
        double ph = (t / 1.15) % 1, bat = 0.85 * battement(ph), retard = 0.85 * battement(ph - 0.1) - bat;
        double replie = ph > 0.4 ? Math.sin(Math.PI * (ph - 0.4) / 0.6) : 0;
        P.t[1] = 0.85 * Math.sin((ph - 0.15) * TOUR);
        P.m(BASSIN, 0.85 + 0.05 * Math.sin((ph - 0.25) * TOUR));
        P.m(TORSE, 0.08 - 0.04 * Math.sin((ph - 0.3) * TOUR));
        P.m(TETE, -0.85 + 0.04 * Math.sin((ph - 0.35) * TOUR));
        P.m(CUISSE_D, 0.28 + 0.07 * Math.sin((ph - 0.4) * TOUR), 0, -0.05).m(CUISSE_G, 0.18 + 0.07 * Math.sin((ph - 0.55) * TOUR), 0, 0.05);
        P.m(TIBIA_D, 0.45 + 0.1 * Math.sin((ph - 0.5) * TOUR)).m(TIBIA_G, 0.7 + 0.1 * Math.sin((ph - 0.65) * TOUR));
        double br = 0.55 + 0.06 * Math.sin((ph - 0.3) * TOUR);
        P.m(BRAS_D, br, 0, -0.3).m(BRAS_G, br, 0, 0.3).m(AVANT_D, -0.2).m(AVANT_G, -0.2);
        P.m(AILE_D, 0.1, -0.15 - 0.4 * replie, -bat).m(AILE_G, 0.1, 0.15 + 0.4 * replie, bat);
        P.m(BOUT_D, 0, -0.55 * replie, -retard).m(BOUT_G, 0, 0.55 * replie, retard);
        return P;
    }

    // ================================================================ les attaques

    /** Une clé : son temps (s) et la valeur de chaque articulation touchée (null : au repos). */
    private record Cle(double t, double[][] v, double[] tr) {
    }

    /** Interpole les clés au temps k : une pose où les articulations non citées restent à zéro. */
    private static Pose cles(double k, Cle... cles) {
        Pose P = new Pose();
        Cle a = cles[0], b = cles[cles.length - 1];
        double f = 1;
        for (int i = 0; i + 1 < cles.length; i++) {
            if (k <= cles[i + 1].t()) {
                a = cles[i];
                b = cles[i + 1];
                f = lisse((k - a.t()) / (b.t() - a.t()));
                break;
            }
        }
        for (int i = 0; i < N; i++) {
            double[] va = a.v() == null ? null : a.v()[i], vb = b.v() == null ? null : b.v()[i];
            for (int j = 0; j < 3; j++) {
                double x = va == null ? 0 : va[j], y = vb == null ? 0 : vb[j];
                P.a[i][j] = x + (y - x) * f;
            }
        }
        for (int j = 0; j < 3; j++) {
            double x = a.tr() == null ? 0 : a.tr()[j], y = b.tr() == null ? 0 : b.tr()[j];
            P.t[j] = x + (y - x) * f;
        }
        return P;
    }

    private static double[] v(double x, double y, double z) {
        return new double[]{x, y, z};
    }

    /** Une pose (articulation → angles), le reste à zéro. */
    private static double[][] p(Object... paires) {
        double[][] r = new double[N][];
        for (int i = 0; i < paires.length; i += 2) {
            r[(Integer) paires[i]] = (double[]) paires[i + 1];
        }
        return r;
    }

    /** La durée des coups (s), de la pluie, de la perce. */
    static final double COUP = 0.85, PLUIE = 1.3, PERCE = 1.1;
    private static final double E = COUP / 1.05;

    /** Le coup de l'épée sombre (main gauche) : en diagonale, pas du pied gauche. */
    private static final Cle[] NOIR = {
        new Cle(0, null, null),
        new Cle(0.32 * E, p(BRAS_G, v(-2.6, 0.35, 0.55), AVANT_G, v(-0.95, 0, 0), BRAS_D, v(-0.2, 0, -0.35), AVANT_D, v(-0.4, 0, 0), TORSE, v(-0.08, 0.45, 0.04),
                TETE, v(-0.08, -0.25, 0), BASSIN, v(0, 0.12, 0), CUISSE_G, v(0.05, 0, 0), TIBIA_G, v(0.15, 0, 0), CUISSE_D, v(-0.05, 0, 0), TIBIA_D, v(0.1, 0, 0),
                AILE_D, v(0, -0.15, -0.1), AILE_G, v(0, 0.3, 0.15), BOUT_D, v(0, 0, 0.05), BOUT_G, v(0, 0, -0.05)), v(0, -0.1, -0.2)),
        new Cle(0.46 * E, p(BRAS_G, v(-0.7, -1.0, -0.05), AVANT_G, v(-0.1, 0, 0), BRAS_D, v(-0.35, 0, -0.3), AVANT_D, v(-0.4, 0, 0), TORSE, v(0.2, -0.5, -0.04),
                TETE, v(-0.15, 0.3, 0), BASSIN, v(0.06, -0.18, 0), CUISSE_G, v(-0.62, 0, 0.05), TIBIA_G, v(0.4, 0, 0), CUISSE_D, v(0.38, 0, -0.04), TIBIA_D, v(0.28, 0, 0),
                AILE_D, v(0, 0.15, 0.08), AILE_G, v(0, -0.1, -0.12), BOUT_D, v(0, 0, -0.08), BOUT_G, v(0, 0, 0.08)), v(0, -0.65, 1.5)),
        new Cle(0.66 * E, p(BRAS_G, v(-0.35, -1.25, -0.1), AVANT_G, v(-0.25, 0, 0), BRAS_D, v(-0.3, 0, -0.25), AVANT_D, v(-0.35, 0, 0), TORSE, v(0.16, -0.62, 0),
                TETE, v(-0.12, 0.35, 0), BASSIN, v(0.06, -0.22, 0), CUISSE_G, v(-0.6, 0, 0.05), TIBIA_G, v(0.38, 0, 0), CUISSE_D, v(0.35, 0, -0.04), TIBIA_D, v(0.25, 0, 0),
                AILE_D, v(0, 0.18, 0.1), AILE_G, v(0, -0.15, -0.12), BOUT_D, v(0, 0, -0.04), BOUT_G, v(0, 0, 0.04)), v(0, -0.6, 1.6)),
        new Cle(COUP, null, null)};
    /** Le coup de l'épée de flammes (main droite) : un balayage, pas du pied droit. */
    private static final Cle[] FLAMME = {
        new Cle(0, null, null),
        new Cle(0.32 * E, p(BRAS_D, v(-1.45, -1.15, -0.2), AVANT_D, v(-0.6, 0, 0), BRAS_G, v(-0.2, 0, 0.35), AVANT_G, v(-0.4, 0, 0), TORSE, v(0.02, -0.55, -0.03),
                TETE, v(-0.05, 0.3, 0), BASSIN, v(0, -0.14, 0), CUISSE_D, v(0.05, 0, 0), TIBIA_D, v(0.15, 0, 0), CUISSE_G, v(-0.05, 0, 0), TIBIA_G, v(0.1, 0, 0),
                AILE_D, v(0, -0.3, -0.15), AILE_G, v(0, 0.15, 0.1), BOUT_D, v(0, 0, 0.08), BOUT_G, v(0, 0, -0.08)), v(0, -0.1, -0.2)),
        new Cle(0.46 * E, p(BRAS_D, v(-1.35, 0.95, -0.1), AVANT_D, v(-0.1, 0, 0), BRAS_G, v(-0.35, 0, 0.3), AVANT_G, v(-0.4, 0, 0), TORSE, v(0.12, 0.6, 0.03),
                TETE, v(-0.12, -0.32, 0), BASSIN, v(0.06, 0.2, 0), CUISSE_D, v(-0.62, 0, -0.05), TIBIA_D, v(0.4, 0, 0), CUISSE_G, v(0.38, 0, 0.04), TIBIA_G, v(0.28, 0, 0),
                AILE_D, v(0, 0.1, 0.12), AILE_G, v(0, -0.15, -0.08), BOUT_D, v(0, 0, -0.1), BOUT_G, v(0, 0, 0.1)), v(0, -0.65, 1.5)),
        new Cle(0.66 * E, p(BRAS_D, v(-1.0, 1.25, -0.1), AVANT_D, v(-0.25, 0, 0), BRAS_G, v(-0.3, 0, 0.25), AVANT_G, v(-0.35, 0, 0), TORSE, v(0.1, 0.72, 0),
                TETE, v(-0.1, -0.4, 0), BASSIN, v(0.06, 0.24, 0), CUISSE_D, v(-0.6, 0, -0.05), TIBIA_D, v(0.38, 0, 0), CUISSE_G, v(0.35, 0, 0.04), TIBIA_G, v(0.25, 0, 0),
                AILE_D, v(0, 0.15, 0.12), AILE_G, v(0, -0.18, -0.1), BOUT_D, v(0, 0, -0.05), BOUT_G, v(0, 0, 0.05)), v(0, -0.6, 1.6)),
        new Cle(COUP, null, null)};
    /** La pluie : l'épée sombre levée vers le ciel, les ailes ouvertes vers le haut. */
    private static final Cle[] LEVE = {
        new Cle(0, null, null),
        new Cle(0.35, p(BRAS_G, v(-2.95, 0, 0.25), AVANT_G, v(-0.1, 0, 0), BRAS_D, v(-0.3, 0, -0.35), AVANT_D, v(-0.35, 0, 0), TORSE, v(-0.18, 0.15, 0),
                TETE, v(-0.45, 0, 0), AILE_D, v(0, -0.1, -0.45), AILE_G, v(0, 0.1, 0.45), BOUT_D, v(0, 0, -0.15), BOUT_G, v(0, 0, 0.15)), v(0, 0.2, 0)),
        new Cle(0.9, p(BRAS_G, v(-3.0, 0, 0.2), AVANT_G, v(-0.05, 0, 0), BRAS_D, v(-0.3, 0, -0.35), AVANT_D, v(-0.35, 0, 0), TORSE, v(-0.2, 0.15, 0),
                TETE, v(-0.5, 0, 0), AILE_D, v(0, -0.1, -0.5), AILE_G, v(0, 0.1, 0.5), BOUT_D, v(0, 0, -0.2), BOUT_G, v(0, 0, 0.2)), v(0, 0.2, 0)),
        new Cle(PLUIE, null, null)};
    /** La perce : les deux épées droit devant, le corps penché, les ailes rabattues en arrière. */
    private static final Cle[] PERCE_CLES = {
        new Cle(0, null, null),
        new Cle(0.2, p(BRAS_D, v(-1.5, 0.22, 0), BRAS_G, v(-1.5, -0.22, 0), AVANT_D, v(-0.05, 0, 0), AVANT_G, v(-0.05, 0, 0), TORSE, v(0.3, 0, 0), BASSIN, v(0.2, 0, 0),
                TETE, v(-0.4, 0, 0), AILE_D, v(0, -0.75, 0.15), AILE_G, v(0, 0.75, -0.15), BOUT_D, v(0, -0.4, 0), BOUT_G, v(0, 0.4, 0),
                CUISSE_D, v(0.5, 0, 0), CUISSE_G, v(0.3, 0, 0), TIBIA_D, v(0.6, 0, 0), TIBIA_G, v(0.5, 0, 0)), null),
        new Cle(0.85, p(BRAS_D, v(-1.5, 0.22, 0), BRAS_G, v(-1.5, -0.22, 0), AVANT_D, v(-0.05, 0, 0), AVANT_G, v(-0.05, 0, 0), TORSE, v(0.3, 0, 0), BASSIN, v(0.2, 0, 0),
                TETE, v(-0.4, 0, 0), AILE_D, v(0, -0.75, 0.15), AILE_G, v(0, 0.75, -0.15), BOUT_D, v(0, -0.4, 0), BOUT_G, v(0, 0.4, 0),
                CUISSE_D, v(0.5, 0, 0), CUISSE_G, v(0.3, 0, 0), TIBIA_D, v(0.6, 0, 0), TIBIA_G, v(0.5, 0, 0)), null),
        new Cle(PERCE, null, null)};

    /** L'attaque d'une sorte (Susano4.Attaque4) au temps tc (s), et la force avec laquelle elle remplace la pose (0 à 1). */
    static Pose attaque(int sorte, double tc) {
        return switch (sorte) {
            case fr.cabane.pouvoir.Susano4.Attaque4.NOIR -> cles(tc, NOIR);
            case fr.cabane.pouvoir.Susano4.Attaque4.LUMIERE -> cles(tc, FLAMME);
            case fr.cabane.pouvoir.Susano4.Attaque4.PLUIE -> cles(tc, LEVE);
            default -> cles(tc, PERCE_CLES);
        };
    }

    static double duree(int sorte) {
        return switch (sorte) {
            case fr.cabane.pouvoir.Susano4.Attaque4.NOIR, fr.cabane.pouvoir.Susano4.Attaque4.LUMIERE -> COUP;
            case fr.cabane.pouvoir.Susano4.Attaque4.PLUIE -> PLUIE;
            default -> PERCE;
        };
    }

    /** La force d'une attaque : elle prend la main en 0,12 s et la rend à la fin. */
    static double force(int sorte, double tc) {
        double d = duree(sorte);
        return lisse(tc / 0.12) * (1 - lisse((tc - (d - 0.18)) / 0.18));
    }

    private AnimSusano4() {
    }
}
