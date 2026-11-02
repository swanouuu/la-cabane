package fr.cabane.labo;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/**
 * Le trajet des tubes du WOOHP : un grand toboggan de verre dans le vide (une plongée, un virage relevé, une bosse
 * par-dessus le toit) qui finit à la verticale dans un des trois tubes d'arrivée. Le serveur s'en sert pour construire
 * les tubes et y faire glisser le chariot de chaque espionne.
 *
 * <p>Les trois tubes sont le même tracé décalé en x (un par espionne). Coordonnées relatives au centre du WOOHP,
 * y = 0 étant le sol de la salle.
 */
public final class WoohpTrajet {
    /** Le WOOHP, dans la dimension du Laboratoire, loin du labo : le centre de la salle, au sol. */
    public static final BlockPos ORIGINE = new BlockPos(0, LaboBuilder.SOL, 600);
    /** Les trois tubes d'arrivée, au fond de la salle (x relatif), et leur z. */
    public static final int[] TUBES_X = {-6, 0, 6};
    public static final int TUBE_Z = -8;
    /** Le chariot roule un peu sous l'axe du tube (l'espionne assise dedans a la tête vers le milieu). */
    public static final double SIEGE = 1.2;

    /** Le tracé du tube du milieu : il part du sud, dans le vide, et descend dans la salle par le toit (le chariot finit au sol). */
    private static final double[][] POINTS = {
            {0, 34, 82}, {0, 33, 70}, {-6, 21, 57}, {-2, 9, 45}, {6, 6, 35}, {6, 13, 27}, {2, 23, 20},
            {0, 21, 9}, {0, 18, -1}, {0, 16.5, -6.5}, {0, 15, -8}, {0, 9, -8}, {0, 1 + SIEGE, -8}};
    /** La glissade : vitesse de départ, « gravité » (on accélère en descendant), vitesses mini et maxi (blocs/s). */
    private static final double V0 = 10, G = 11, VMIN = 6, VMAX = 27;

    private static final List<Vec3> CHEMIN = new ArrayList<>();
    private static final List<Double> TEMPS = new ArrayList<>();
    /** La durée de la glissade, en secondes. */
    public static final double DUREE;

    static {
        List<Vec3> p = new ArrayList<>();
        for (double[] q : POINTS) {
            p.add(new Vec3(q[0], q[1], q[2]));
        }
        // Catmull-Rom, échantillonné finement
        for (int i = 0; i < p.size() - 1; i++) {
            Vec3 a = p.get(Math.max(0, i - 1)), b = p.get(i), c = p.get(i + 1), d = p.get(Math.min(p.size() - 1, i + 2));
            int n = (int) Math.max(4, b.distanceTo(c) * 6);
            for (int k = 0; k < n; k++) {
                CHEMIN.add(catmull(a, b, c, d, (double) k / n));
            }
        }
        CHEMIN.add(p.get(p.size() - 1));
        double t = 0, haut = CHEMIN.get(0).y;
        TEMPS.add(0.0);
        for (int i = 1; i < CHEMIN.size(); i++) {
            double ds = CHEMIN.get(i).distanceTo(CHEMIN.get(i - 1));
            double y = (CHEMIN.get(i).y + CHEMIN.get(i - 1).y) / 2;
            double v = Math.min(VMAX, Math.max(VMIN, Math.sqrt(Math.max(0, V0 * V0 + 2 * G * (haut - y)))));
            t += ds / v;
            TEMPS.add(t);
        }
        DUREE = t;
    }

    private static Vec3 catmull(Vec3 a, Vec3 b, Vec3 c, Vec3 d, double t) {
        double t2 = t * t, t3 = t2 * t;
        return b.scale(2).add(c.subtract(a).scale(t)).add(a.scale(2).subtract(b.scale(5)).add(c.scale(4)).subtract(d).scale(t2))
                .add(b.scale(3).subtract(a).subtract(c.scale(3)).add(d).scale(t3)).scale(0.5);
    }

    /** Le décalage du tube {@code tube} (0, 1 ou 2) par rapport au tracé du milieu. */
    private static Vec3 decalage(int tube) {
        return new Vec3(TUBES_X[Math.floorMod(tube, 3)], 0, 0);
    }

    /** La direction du tube au temps {@code t} (pour orienter le chariot et le regard au départ). */
    public static Vec3 direction(int tube, double t) {
        Vec3 d = relatif(tube, Math.min(DUREE, t + 0.1)).subtract(relatif(tube, Math.max(0, t - 0.1)));
        return d.lengthSqr() < 1e-8 ? new Vec3(0, 0, -1) : d.normalize();
    }

    /** Le point du tube au temps {@code t} (en secondes depuis le départ), en coordonnées relatives au WOOHP. */
    public static Vec3 relatif(int tube, double t) {
        if (t <= 0) {
            return CHEMIN.get(0).add(decalage(tube));
        }
        if (t >= DUREE) {
            return CHEMIN.get(CHEMIN.size() - 1).add(decalage(tube));
        }
        int bas = 0, haut = TEMPS.size() - 1;
        while (haut - bas > 1) {
            int m = (bas + haut) / 2;
            if (TEMPS.get(m) <= t) {
                bas = m;
            } else {
                haut = m;
            }
        }
        double k = (t - TEMPS.get(bas)) / (TEMPS.get(haut) - TEMPS.get(bas));
        return CHEMIN.get(bas).lerp(CHEMIN.get(haut), k).add(decalage(tube));
    }

    /** Pareil, en coordonnées du monde. */
    public static Vec3 position(int tube, double t) {
        return relatif(tube, t).add(ORIGINE.getX() + 0.5, ORIGINE.getY(), ORIGINE.getZ() + 0.5);
    }

    /** Tous les points du tracé (relatifs), pour construire les tubes. */
    static List<Vec3> points(int tube) {
        Vec3 d = decalage(tube);
        return CHEMIN.stream().map(v -> v.add(d)).toList();
    }

    private WoohpTrajet() {
    }
}
