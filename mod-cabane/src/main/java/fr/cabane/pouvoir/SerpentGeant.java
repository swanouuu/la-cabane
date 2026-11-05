package fr.cabane.pouvoir;

import java.util.List;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * Le trajet et les gestes du grand serpent de l'ulti de la marque 3 ({@link Marque3}), validés dans l'aperçu
 * docs/marque3/ulti (ulti.html) : les mêmes calculs côté serveur (les dégâts) et chez chaque joueur (le dessin). Tout ne
 * dépend que du temps depuis le lancement, de l'endroit, de la direction et de la hauteur du sol sous le trajet.
 * <p>
 * Repère local : x à gauche du marqué, y en haut, z devant lui (en blocs), l'origine à ses pieds au lancement. Le serpent
 * est un chemin : sa queue enroulée derrière lui, il se dresse ; à la charge, sa tête file sur le chemin et le corps suit
 * (comme un train). d : la distance de la tête (le cou) le long du chemin, 0 quand il est dressé.
 */
public final class SerpentGeant {
    // le déroulé, en secondes depuis le lancement
    public static final double T_SAUT = 0.15, T_POSE = 1.0, T_POUF = 2.25, T_SIFFLE = 3.0, T_ELAN = 4.3, T_CHARGE = 5.0, DUREE_CHARGE = 4.2;
    public static final double T_IMPACT = T_CHARGE + DUREE_CHARGE, T_DISPARAIT = T_IMPACT + 0.55, T_FIN = T_DISPARAIT + 2.5;
    /** La tête (en blocs) : sa longueur, la charnière de la mâchoire ; le corps : sa longueur. */
    public static final float LH = 17, CHARN = 2.5F, LB = 100;
    /** Où se tient le marqué sur la tête (repère de la tête). */
    public static final float[] SIEGE = {6.6F, 3.2F, 0};

    // ================================================================ la forme (comme serpent.mjs)

    static double clamp(double x, double a, double b) {
        return Math.max(a, Math.min(b, x));
    }

    public static double lisse(double t) {
        t = clamp(t, 0, 1);
        return t * t * (3 - 2 * t);
    }

    static double lerp(double a, double b, double k) {
        return a + (b - a) * k;
    }

    public static double largeur(double x) {
        double k = clamp(x / LH, 0, 1);
        double w = k < 0.3 ? lerp(4.3, 6.3, lisse(k / 0.3)) : lerp(6.3, 2.7, Math.pow((k - 0.3) / 0.7, 1.15));
        if (k > 0.83) {
            w *= Math.sqrt(Math.max(0, 1 - Math.pow((k - 0.83) / 0.175, 2)));
        }
        return w;
    }

    public static double hMach(double x) {
        double k = clamp(x / LH, 0, 1);
        double h = lerp(2.7, 1.1, k);
        if (k > 0.86) {
            h *= 0.45 + 0.55 * Math.sqrt(Math.max(0, 1 - Math.pow((k - 0.86) / 0.13, 2)));
        }
        return h;
    }

    public static double rayon(double s) {
        return s < 12 ? lerp(4.1, 5.2, lisse(s / 12)) : s < 38 ? 5.2 : Math.max(0.3, 5.2 * Math.pow(1 - (s - 38) / (LB - 38), 0.85));
    }

    // ================================================================ le chemin (repère local)

    /** Les points de contrôle : la queue enroulée, il se dresse (le cou, NCOU), puis la charge. */
    private static final double[][] POINTS;
    private static final int NCOU;

    static {
        java.util.ArrayList<double[]> l = new java.util.ArrayList<>();
        int n = 26;
        for (int j = 0; j <= n; j++) {
            double k = j / (double) n, phi = Math.PI / 2 - (1 - k) * 7.6, r = lerp(5.5, 15, k);
            l.add(new double[]{r * Math.cos(phi), lerp(1.0, 3.6, k), -21 + r * Math.sin(phi)});
        }
        l.add(new double[]{-4.5, 5.5, -5.5});
        l.add(new double[]{-3.5, 11, -4.5});
        l.add(new double[]{-1.5, 16.5, -3});
        l.add(new double[]{0, 20.2, 0});
        l.add(new double[]{0, 21.2, 3.8});
        NCOU = l.size();
        double[][] suite = {{0, 21, 7}, {0, 17.5, 15}, {0, 9.5, 24}, {0, 4.2, 34}, {2.5, 3.3, 46}, {-4.5, 3.3, 61}, {4.5, 3.3, 76}, {-4.5, 3.3, 91},
            {3.5, 3.3, 106}, {-1.5, 3.3, 119}, {0, 3.3, 131}, {0, 3.3, 143}, {0, 3.3, 160}, {0, 3.3, 185}};
        l.addAll(List.of(suite));
        POINTS = l.toArray(new double[0][]);
    }

    /** Combien de hauteurs de sol il faut (une par point de contrôle). */
    public static int nombrePoints() {
        return POINTS.length;
    }

    /** Le point de contrôle i dans le monde (pour mesurer le sol dessous). */
    public static Vec3 pointMonde(Vec3 origine, float lacet, int i) {
        double[] p = POINTS[i];
        return versMonde(origine, lacet, p[0], 0, p[2]);
    }

    /** Ce point compte-t-il le sol ? (les points au ras du sol le suivent, ceux en l'air restent où ils sont) */
    public static double poidsSol(int i) {
        return clamp(1 - (POINTS[i][1] - 4.5) / 5, 0, 1);
    }

    static Vec3 versMonde(Vec3 o, float lacet, double x, double y, double z) {
        double r = lacet * Mth.DEG_TO_RAD, c = Math.cos(r), s = Math.sin(r);
        // gauche = (cos, 0, sin), devant = (-sin, 0, cos)
        return new Vec3(o.x + x * c - z * s, o.y + y, o.z + x * s + z * c);
    }

    private static final double PAS = 0.2;

    /** Un lancement : le chemin échantillonné (repère local), et de quoi passer dans le monde. */
    public static final class Trajet {
        public final Vec3 origine;
        public final float lacet;
        final int n;
        final double dMin;
        public final double dFin;
        final double[] P, T, U;
        final double vmax;
        private final double cos, sin;

        public Trajet(Vec3 origine, float lacet, List<Float> sols) {
            this.origine = origine;
            this.lacet = lacet;
            double r = lacet * Mth.DEG_TO_RAD;
            cos = Math.cos(r);
            sin = Math.sin(r);
            double[][] pts = new double[POINTS.length][];
            for (int i = 0; i < POINTS.length; i++) {
                double h = i < sols.size() ? sols.get(i) : 0;
                pts[i] = new double[]{POINTS[i][0], POINTS[i][1] + h * poidsSol(i), POINTS[i][2]};
            }
            // Catmull-Rom, puis à pas régulier
            int sub = 30;
            java.util.ArrayList<double[]> dense = new java.util.ArrayList<>();
            for (int i = 0; i + 1 < pts.length; i++) {
                double[] p0 = pts[Math.max(0, i - 1)], p1 = pts[i], p2 = pts[i + 1], p3 = pts[Math.min(pts.length - 1, i + 2)];
                for (int j = 0; j < sub; j++) {
                    double t = j / (double) sub, t2 = t * t, t3 = t2 * t;
                    double[] q = new double[3];
                    for (int c = 0; c < 3; c++) {
                        q[c] = 0.5 * (2 * p1[c] + (-p0[c] + p2[c]) * t + (2 * p0[c] - 5 * p1[c] + 4 * p2[c] - p3[c]) * t2 + (-p0[c] + 3 * p1[c] - 3 * p2[c] + p3[c]) * t3);
                    }
                    dense.add(q);
                }
            }
            dense.add(pts[pts.length - 1]);
            double[] cum = new double[dense.size()];
            for (int i = 1; i < cum.length; i++) {
                double[] a = dense.get(i), b = dense.get(i - 1);
                cum[i] = cum[i - 1] + Math.sqrt((a[0] - b[0]) * (a[0] - b[0]) + (a[1] - b[1]) * (a[1] - b[1]) + (a[2] - b[2]) * (a[2] - b[2]));
            }
            double dCou = cum[NCOU * sub], total = cum[cum.length - 1];
            n = (int) Math.floor(total / PAS) + 1;
            P = new double[n * 3];
            T = new double[n * 3];
            U = new double[n * 3];
            int j = 0;
            for (int i = 0; i < n; i++) {
                double d = i * PAS;
                while (j + 2 < cum.length && cum[j + 1] < d) {
                    j++;
                }
                double k = clamp((d - cum[j]) / (cum[j + 1] - cum[j]), 0, 1);
                double[] a = dense.get(j), b = dense.get(j + 1);
                for (int c = 0; c < 3; c++) {
                    P[i * 3 + c] = lerp(a[c], b[c], k);
                }
            }
            double[] U0 = new double[n * 3];
            for (int i = 0; i < n; i++) {
                int a = Math.max(0, i - 2), b = Math.min(n - 1, i + 2);
                double[] t = norme(P[b * 3] - P[a * 3], P[b * 3 + 1] - P[a * 3 + 1], P[b * 3 + 2] - P[a * 3 + 2]);
                System.arraycopy(t, 0, T, i * 3, 3);
                // le dos vers le ciel ; quand il se dresse, le ventre regarde devant
                double vx = 0, vy = 1, vz = -0.08, dt = vx * t[0] + vy * t[1] + vz * t[2];
                double[] u = norme(vx - t[0] * dt, vy - t[1] * dt, vz - t[2] * dt);
                System.arraycopy(u, 0, U0, i * 3, 3);
            }
            for (int i = 0; i < n; i++) {
                double sx = 0, sy = 0, sz = 0;
                for (int o = -6; o <= 6; o++) {
                    int q = Math.max(0, Math.min(n - 1, i + o));
                    sx += U0[q * 3];
                    sy += U0[q * 3 + 1];
                    sz += U0[q * 3 + 2];
                }
                double tx = T[i * 3], ty = T[i * 3 + 1], tz = T[i * 3 + 2], dt = sx * tx + sy * ty + sz * tz;
                double[] u = norme(sx - tx * dt, sy - ty * dt, sz - tz * dt);
                System.arraycopy(u, 0, U, i * 3, 3);
            }
            dMin = -dCou;
            double fin = 0;
            for (int i = (int) Math.round(dCou / PAS); i < n; i++) {
                if (P[i * 3 + 2] >= 131) {
                    fin = i * PAS + dMin;
                    break;
                }
            }
            dFin = fin;
            vmax = dFin / (DUREE_CHARGE - RAMPE / 2 - FREIN / 2);
        }

        /** Le chemin à la distance d (repère local) : position, avant, dessus, dans out[0..8]. */
        public void chemin(double d, double[] out) {
            double f = clamp((d - dMin) / PAS, 0, n - 1.001);
            int i = (int) Math.floor(f);
            double k = f - i;
            for (int c = 0; c < 3; c++) {
                out[c] = lerp(P[i * 3 + c], P[i * 3 + 3 + c], k);
                out[3 + c] = lerp(T[i * 3 + c], T[i * 3 + 3 + c], k);
                out[6 + c] = lerp(U[i * 3 + c], U[i * 3 + 3 + c], k);
            }
            normer(out, 3);
            normer(out, 6);
        }

        // passer du repère local au monde (relatif à l'origine) : une rotation autour de y
        public double mx(double x, double z) {
            return x * cos - z * sin;
        }

        public double mz(double x, double z) {
            return x * sin + z * cos;
        }

        public double distTete(double t) {
            double u = t - T_CHARGE, D = DUREE_CHARGE;
            if (u <= 0) {
                return 0;
            }
            if (u >= D) {
                return dFin;
            }
            if (u < RAMPE) {
                return vmax * u * u / (2 * RAMPE);
            }
            double d = vmax * RAMPE / 2;
            if (u < D - FREIN) {
                return d + vmax * (u - RAMPE);
            }
            d += vmax * (D - FREIN - RAMPE);
            double w = u - (D - FREIN);
            return d + vmax * (w - w * w / (2 * FREIN));
        }

        /**
         * Le repère de la tête à l'instant t, dans le monde (relatif à l'origine) : out = position du cou (0..2), avant
         * (3..5), dessus (6..8), côté (9..11), et en 12 l'ouverture de la gueule, en 13 la distance de la tête, en 14 sa
         * vitesse (blocs/s).
         */
        public void tete(double t, double[] out) {
            double[] f = new double[9];
            double d = distTete(t);
            chemin(d, f);
            double a = incline(t), ca = Math.cos(a), sa = Math.sin(a);
            double[] tl = norme(f[3] * ca + f[6] * sa, f[4] * ca + f[7] * sa, f[5] * ca + f[8] * sa);
            double[] ul = norme(f[6] * ca - f[3] * sa, f[7] * ca - f[4] * sa, f[8] * ca - f[5] * sa);
            double[] dep = new double[3];
            deplacement(0, t, dep);
            double px = f[0] + dep[0], py = f[1] + dep[1], pz = f[2] + dep[2];
            out[0] = mx(px, pz);
            out[1] = py;
            out[2] = mz(px, pz);
            out[3] = mx(tl[0], tl[2]);
            out[4] = tl[1];
            out[5] = mz(tl[0], tl[2]);
            out[6] = mx(ul[0], ul[2]);
            out[7] = ul[1];
            out[8] = mz(ul[0], ul[2]);
            // côté = avant × dessus
            out[9] = out[4] * out[8] - out[5] * out[7];
            out[10] = out[5] * out[6] - out[3] * out[8];
            out[11] = out[3] * out[7] - out[4] * out[6];
            out[12] = ouverture(t);
            out[13] = d;
            out[14] = (distTete(t + 0.01) - distTete(t - 0.01)) / 0.02;
        }

        /** Un point de la tête (repère de la tête, x devant depuis le cou) dans le monde, relatif à l'origine. */
        public static double[] dansTete(double[] K, double x, double y, double z, boolean crane) {
            if (crane) {
                double a = angleCrane(K[12]), xr = x - CHARN;
                double x2 = CHARN + xr * Math.cos(a) - y * Math.sin(a), y2 = xr * Math.sin(a) + y * Math.cos(a);
                x = x2;
                y = y2;
            }
            return new double[]{K[0] + K[3] * x + K[6] * y + K[9] * z, K[1] + K[4] * x + K[7] * y + K[10] * z, K[2] + K[5] * x + K[8] * y + K[11] * z};
        }

        /** Où se tient le marqué à l'instant t (dans le monde). */
        public Vec3 siege(double t) {
            double[] K = new double[15];
            tete(t, K);
            double[] p = dansTete(K, SIEGE[0], SIEGE[1], SIEGE[2], true);
            return origine.add(p[0], p[1], p[2]);
        }
    }

    private static final double RAMPE = 0.35, FREIN = 0.7;

    static double[] norme(double x, double y, double z) {
        double l = Math.sqrt(x * x + y * y + z * z);
        if (l < 1e-9) {
            return new double[]{0, 1, 0};
        }
        return new double[]{x / l, y / l, z / l};
    }

    static void normer(double[] v, int o) {
        double l = Math.sqrt(v[o] * v[o] + v[o + 1] * v[o + 1] + v[o + 2] * v[o + 2]);
        if (l > 1e-9) {
            v[o] /= l;
            v[o + 1] /= l;
            v[o + 2] /= l;
        }
    }

    /** Une valeur qui suit des clés {temps, valeur}, adoucie entre deux clés. */
    static double cles(double t, double[][] L) {
        if (t <= L[0][0]) {
            return L[0][1];
        }
        for (int i = 1; i < L.length; i++) {
            if (t < L[i][0]) {
                return lerp(L[i - 1][1], L[i][1], lisse((t - L[i - 1][0]) / (L[i][0] - L[i - 1][0])));
            }
        }
        return L[L.length - 1][1];
    }

    private static final double[][] ELAN = {{T_ELAN, 0}, {T_CHARGE - 0.05, 1}, {T_CHARGE + 0.22, 0}};
    private static final double[][] BALANCE = {{T_POUF, 1}, {T_ELAN, 1}, {T_CHARGE, 0}};
    private static final double[][] OUVERTURE = {{T_SIFFLE, 0}, {T_SIFFLE + 0.35, 1}, {T_SIFFLE + 0.9, 0.85}, {T_SIFFLE + 1.2, 0.12}, {T_ELAN, 0.12},
        {T_ELAN + 0.45, 0.8}, {T_CHARGE, 1}, {T_IMPACT - 0.12, 1}, {T_IMPACT + 0.06, 0}, {99, 0}};
    private static final double[][] INCLINE = {{T_POUF, 0.1}, {T_SIFFLE, -0.22}, {T_ELAN, -0.1}, {T_CHARGE, 0.45}, {T_CHARGE + 0.35, 0}, {T_IMPACT, -0.04},
        {T_IMPACT + 0.1, -0.14}, {99, -0.14}};

    public static double ouverture(double t) {
        return cles(t, OUVERTURE);
    }

    static double incline(double t) {
        return cles(t, INCLINE);
    }

    public static double angleCrane(double o) {
        return 0.32 * o;
    }

    public static double angleMachoire(double o) {
        return -1.0 * o;
    }

    /** La langue sort par petits coups pendant qu'il siffle (0 rentrée, 1 sortie). */
    public static double langue(double t) {
        return t > T_SIFFLE + 0.1 && t < T_ELAN ? Math.pow(Math.max(0, Math.sin((t - T_SIFFLE) * 7.5)), 3) : 0;
    }

    /** Le décalage (repère local) de la partie dressée : il se balance en sifflant, puis recule la tête pour l'élan. */
    public static void deplacement(double s, double t, double[] out) {
        out[0] = out[1] = out[2] = 0;
        double e = cles(t, ELAN);
        if (e > 0 && s < 32) {
            double k = e * Math.pow(1 - s / 32, 2);
            out[1] += 3.5 * k;
            out[2] += -8 * k;
        }
        double b = cles(t, BALANCE);
        if (b > 0 && s < 40) {
            double g = b * Math.pow(1 - s / 40, 1.5);
            out[0] += Math.sin(t * 1.7 - s * 0.12) * 1.6 * g;
            out[1] += Math.sin(t * 1.1 - s * 0.1) * 0.6 * g;
        }
    }

    private SerpentGeant() {
    }
}
