package fr.cabane.parc;

import java.util.ArrayList;
import java.util.List;
import org.joml.Vector3d;

/**
 * Un parcours de manège fermé : une suite de points (un tous les demi-blocs au plus), et en chaque point le « haut »
 * du wagon (il penche dans les virages, il est la tête en bas en haut du looping). Le serveur et le client le
 * construisent pareil, à partir des mêmes morceaux : un wagon n'a besoin que de sa distance parcourue pour savoir où il
 * est et comment il est tourné. Les morceaux sont ceux de la maquette (docs/parc/maquette.html) : la « tortue » pour
 * les grands huit, et une liste de points calculés (une courbe en fonction d'un paramètre u) pour les autres.
 * <p>
 * En chaque point, en plus : ce que fait le wagon (gare, chaîne…), si l'on est dans le noir, le paramètre u de la
 * courbe (pour retrouver les salles de la maquette), et de combien la caisse a tourné sur elle-même (les wagons du
 * Cosmos tournent tout le temps, celui de la Mine tourne deux fois dans sa salle).
 */
public final class Trajet {
    /** Ce que fait le wagon à cet endroit : en gare, tiré par la chaîne, en roue libre, freiné, lancé d'un coup, au pas, en chute, sur l'eau. */
    public static final byte GARE = 0, LIFT = 1, LIBRE = 2, FREIN = 3, BOOST = 4, LENT = 5, CHUTE = 6, EAU = 7;

    private final int n;
    private final double[] px, py, pz, ux, uy, uz, fx, fy, fz, cum, tour, param;
    private final byte[] mode;
    private final boolean[] noir;
    public final double total;

    /** Un endroit du parcours : la position, le haut et l'avant du wagon, et ce qui s'y passe. */
    public static final class Point {
        public double x, y, z, ux, uy, uz, fx, fy, fz;
        /** De combien la caisse a tourné sur elle-même (radians, vers la gauche). */
        public double tour;
        /** Le paramètre de la courbe (0 pour les grands huit faits à la tortue). */
        public double u;
        public byte mode;
        public boolean noir;
        public int i;

        /** L'avant devient celui de la caisse (tournée de {@link #tour} autour du haut) : pour dessiner le wagon et asseoir les gens. */
        public Point caisse() {
            if (tour != 0) {
                double c = Math.cos(tour), s = Math.sin(tour);
                double gx = uy * fz - uz * fy, gy = uz * fx - ux * fz, gz = ux * fy - uy * fx;
                fx = fx * c + gx * s;
                fy = fy * c + gy * s;
                fz = fz * c + gz * s;
                tour = 0;
            }
            return this;
        }
    }

    /** Un point posé avant d'être mis en forme. */
    private record Brut(Vector3d p, Vector3d up, byte mode, boolean noir, double tour, double u) {
    }

    private Trajet(List<Brut> bruts) {
        // on enlève les points en double (fin d'un morceau = début du suivant)
        List<Brut> l = new ArrayList<>();
        for (Brut b : bruts) {
            if (!l.isEmpty() && b.p.distance(l.get(l.size() - 1).p) < 0.05) {
                continue;
            }
            l.add(b);
        }
        if (l.get(0).p.distance(l.get(l.size() - 1).p) < 0.05) {
            l.remove(l.size() - 1);
        }
        n = l.size();
        px = new double[n]; py = new double[n]; pz = new double[n];
        ux = new double[n]; uy = new double[n]; uz = new double[n];
        fx = new double[n]; fy = new double[n]; fz = new double[n];
        tour = new double[n]; param = new double[n];
        cum = new double[n + 1];
        mode = new byte[n];
        noir = new boolean[n];
        for (int i = 0; i < n; i++) {
            Brut b = l.get(i);
            px[i] = b.p.x; py[i] = b.p.y; pz[i] = b.p.z;
            mode[i] = b.mode;
            noir[i] = b.noir;
            tour[i] = b.tour;
            param[i] = b.u;
        }
        Vector3d f = new Vector3d(), up = new Vector3d();
        for (int i = 0; i < n; i++) {
            Vector3d suivant = l.get((i + 1) % n).p, avant = l.get((i - 1 + n) % n).p;
            f.set(suivant).sub(avant).normalize();
            fx[i] = f.x; fy[i] = f.y; fz[i] = f.z;
            up.set(l.get(i).up);
            up.fma(-f.dot(up), f);
            if (up.lengthSquared() < 1e-4) {
                up.set(i > 0 ? ux[i - 1] : 0, i > 0 ? uy[i - 1] : 1, i > 0 ? uz[i - 1] : 0);
            }
            up.normalize();
            ux[i] = up.x; uy[i] = up.y; uz[i] = up.z;
        }
        for (int i = 0; i < n; i++) {
            int j = (i + 1) % n;
            double dx = px[j] - px[i], dy = py[j] - py[i], dz = pz[j] - pz[i];
            cum[i + 1] = cum[i] + Math.sqrt(dx * dx + dy * dy + dz * dz);
        }
        total = cum[n];
    }

    public double normaliser(double s) {
        s %= total;
        return s < 0 ? s + total : s;
    }

    /** Où l'on est après {@code s} blocs (le parcours tourne en boucle). */
    public Point a(double s, Point o) {
        s = normaliser(s);
        int a = 0, b = n;
        while (b - a > 1) {
            int m = (a + b) >>> 1;
            if (cum[m] <= s) {
                a = m;
            } else {
                b = m;
            }
        }
        int j = (a + 1) % n;
        double l = cum[a + 1] - cum[a], k = l > 0 ? (s - cum[a]) / l : 0;
        o.x = px[a] + (px[j] - px[a]) * k;
        o.y = py[a] + (py[j] - py[a]) * k;
        o.z = pz[a] + (pz[j] - pz[a]) * k;
        double gx = fx[a] + (fx[j] - fx[a]) * k, gy = fy[a] + (fy[j] - fy[a]) * k, gz = fz[a] + (fz[j] - fz[a]) * k;
        double lf = Math.sqrt(gx * gx + gy * gy + gz * gz);
        o.fx = gx / lf; o.fy = gy / lf; o.fz = gz / lf;
        double hx = ux[a] + (ux[j] - ux[a]) * k, hy = uy[a] + (uy[j] - uy[a]) * k, hz = uz[a] + (uz[j] - uz[a]) * k;
        double d = hx * o.fx + hy * o.fy + hz * o.fz;
        hx -= d * o.fx; hy -= d * o.fy; hz -= d * o.fz;
        double lh = Math.sqrt(hx * hx + hy * hy + hz * hz);
        o.ux = hx / lh; o.uy = hy / lh; o.uz = hz / lh;
        double dt = tour[j] - tour[a];
        dt = dt - Math.floor((dt + Math.PI) / (2 * Math.PI)) * 2 * Math.PI;
        o.tour = tour[a] + dt * k;
        o.u = param[a];
        o.mode = mode[a];
        o.noir = noir[a];
        o.i = a;
        return o;
    }

    /** La distance du premier point qui vérifie la condition (pour placer les arrêts, les déclencheurs…). */
    public double premier(java.util.function.IntPredicate test) {
        for (int i = 0; i < n; i++) {
            if (test.test(i)) {
                return cum[i];
            }
        }
        return 0;
    }

    /** La distance du premier point où le paramètre de la courbe dépasse {@code u}. */
    public double ou(double u) {
        return premier(i -> param[i] > u);
    }

    /**
     * Où la tête d'un train de {@code voitures} wagons (espacés de {@code ecart}) s'arrête pour que le train soit au
     * milieu de la gare (la plus longue suite de points en mode GARE, qui peut passer par le début du parcours).
     */
    public double arretGare(int voitures, double ecart) {
        int i0 = -1;
        for (int i = 0; i < n && i0 < 0; i++) {
            if (mode[i] != GARE) {
                i0 = i;
            }
        }
        if (i0 < 0) {
            return 0;
        }
        // on fait le tour en partant d'un point hors gare : chaque suite de points GARE est vue en entier
        double meilleur = -1, debutMeilleur = 0, debut = 0, longueur = 0;
        boolean dedans = false;
        for (int k = 1; k <= n; k++) {
            int i = (i0 + k) % n, avant = (i - 1 + n) % n;
            double pas = cum[avant + 1] - cum[avant];
            if (mode[i] == GARE) {
                if (!dedans) {
                    dedans = true;
                    debut = cum[i];
                    longueur = 0;
                } else {
                    longueur += pas;
                }
                if (longueur > meilleur) {
                    meilleur = longueur;
                    debutMeilleur = debut;
                }
            } else {
                dedans = false;
            }
        }
        return normaliser(debutMeilleur + meilleur / 2 + (voitures - 1) * ecart / 2);
    }

    public int nombre() {
        return n;
    }

    public byte mode(int i) {
        return mode[i];
    }

    public boolean noir(int i) {
        return noir[i];
    }

    public double param(int i) {
        return param[i];
    }

    // ================================================================== une courbe point par point

    /** Une suite de points calculés (une courbe fermée), comme les parcours « Parcours(pts, null, inf) » de la maquette. */
    public static final class Liste {
        private final List<Brut> pts = new ArrayList<>();

        public Liste point(double x, double y, double z, byte mode, boolean noir, double tour, double u) {
            pts.add(new Brut(new Vector3d(x, y, z), new Vector3d(0, 1, 0), mode, noir, tour, u));
            return this;
        }

        public Trajet fermer() {
            return new Trajet(pts);
        }
    }

    // ================================================================== la tortue qui pose les morceaux

    /** Pose les morceaux de grand huit les uns après les autres (comme dans la maquette). */
    public static final class Tortue {
        private static final Vector3d Y = new Vector3d(0, 1, 0);
        private final List<Brut> pts = new ArrayList<>();
        private final Vector3d p0, d0;
        private Vector3d p, d;

        public Tortue(double x, double y, double z, double dx, double dz) {
            p = new Vector3d(x, y, z);
            d = new Vector3d(dx, 0, dz).normalize();
            p0 = new Vector3d(p);
            d0 = new Vector3d(d);
            poser(new Vector3d(p), new Vector3d(Y), GARE, false);
        }

        private void poser(Vector3d pt, Vector3d up, byte m, boolean sombre) {
            pts.add(new Brut(pt, up, m, sombre, 0, 0));
            p = new Vector3d(pt);
        }

        private static Vector3d gauche(Vector3d dir) {
            return new Vector3d(Y).cross(dir).normalize();
        }

        private static double lisse(double t) {
            return t <= 0 ? 0 : t >= 1 ? 1 : t * t * (3 - 2 * t);
        }

        public Tortue droit(double l, double dy, byte m, boolean sombre) {
            Vector3d a = new Vector3d(p);
            int nb = (int) Math.ceil(l / 0.5);
            for (int k = 1; k <= nb; k++) {
                double t = (double) k / nb;
                poser(new Vector3d(a).fma(l * t, d).add(0, dy * lisse(t), 0), new Vector3d(Y), m, sombre);
            }
            return this;
        }

        public Tortue droit(double l, double dy) {
            return droit(l, dy, LIBRE, false);
        }

        /** Un virage de rayon r et d'angle ang (positif : à gauche), en montant de dy, penché de banque radians au milieu. */
        public Tortue virage(double r, double ang, double dy, double banque, byte m, boolean sombre) {
            double s = Math.signum(ang);
            Vector3d a = new Vector3d(p), da = new Vector3d(d);
            Vector3d centre = new Vector3d(a).fma(s * r, gauche(da));
            int nb = (int) Math.ceil(Math.abs(ang) * r / 0.5);
            for (int k = 1; k <= nb; k++) {
                double t = (double) k / nb;
                Vector3d dir = new Vector3d(da).rotateY(ang * t), g = gauche(dir);
                Vector3d pt = new Vector3d(centre).fma(-s * r, g);
                pt.y = a.y + dy * lisse(t);
                double b = banque * Math.sin(Math.PI * t);
                poser(pt, new Vector3d(Y).mul(Math.cos(b)).fma(s * Math.sin(b), g), m, sombre);
            }
            d = new Vector3d(da).rotateY(ang);
            return this;
        }

        /** Un looping de rayon r, décalé de decal vers la droite pour que le rail ne se croise pas. */
        public Tortue boucle(double r, double decal) {
            Vector3d a = new Vector3d(p), dir = new Vector3d(d), droite = new Vector3d(dir).cross(Y).normalize();
            int nb = (int) Math.ceil(2 * Math.PI * r / 0.5);
            for (int k = 1; k <= nb; k++) {
                double th = 2 * Math.PI * k / nb;
                Vector3d pt = new Vector3d(a).fma(r * Math.sin(th), dir).add(0, r * (1 - Math.cos(th)), 0).fma(decal * lisse((double) k / nb), droite);
                poser(pt, new Vector3d(Y).mul(Math.cos(th)).fma(-Math.sin(th), dir), LIBRE, false);
            }
            return this;
        }

        /** Une vrille : on fait un tour complet autour de l'axe en avançant de l. */
        public Tortue vrille(double l, double r) {
            Vector3d a = new Vector3d(p), dir = new Vector3d(d);
            int nb = (int) Math.ceil(l / 0.4);
            for (int k = 1; k <= nb; k++) {
                double t = (double) k / nb;
                Vector3d off = new Vector3d(0, -r, 0).rotateAxis(2 * Math.PI * lisse(t), dir.x, dir.y, dir.z);
                Vector3d pt = new Vector3d(a).add(0, r, 0).fma(l * t, dir).add(off);
                poser(pt, new Vector3d(off).mul(-1 / r), LIBRE, false);
            }
            return this;
        }

        /** Une bosse où l'on décolle du siège. */
        public Tortue colline(double l, double h) {
            Vector3d a = new Vector3d(p);
            int nb = (int) Math.ceil(l / 0.5);
            for (int k = 1; k <= nb; k++) {
                double t = (double) k / nb;
                poser(new Vector3d(a).fma(l * t, d).add(0, h * (1 - Math.cos(2 * Math.PI * t)) / 2, 0), new Vector3d(Y), LIBRE, false);
            }
            return this;
        }

        /** Raccorde la fin au début (une courbe douce qui arrive dans le bon sens). */
        public Trajet fermer(byte m) {
            Vector3d a = new Vector3d(p);
            double l = a.distance(p0);
            int nb = (int) Math.ceil(l / 0.5);
            for (int k = 1; k < nb; k++) {
                double t = (double) k / nb, t2 = t * t, t3 = t2 * t;
                Vector3d pt = new Vector3d(a).mul(2 * t3 - 3 * t2 + 1).fma(l * (t3 - 2 * t2 + t), d).fma(-2 * t3 + 3 * t2, p0).fma(l * (t3 - t2), d0);
                poser(pt, new Vector3d(Y), m, false);
            }
            return new Trajet(pts);
        }
    }
}
