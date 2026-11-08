package fr.cabane.pouvoir;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * Le squelette du dinosaure d'os (l'ulti de Kaguya, docs/kaguya/ulti), le même que l'aperçu : seize morceaux articulés
 * autour de leurs pivots (en blocs, l'avant vers +z, comme assets/cabane/kaguya/dino.bin), et leur pose en fonction du
 * temps (secondes depuis le début de l'ulti), de la distance déjà courue et de la morsure. Partagé par le serveur (où est
 * le crâne, la gueule, le ventre) et par le dessin ({@code ClientKaguyaDino}).
 */
public final class DinoPose {
    /** Le déroulé, en secondes (celui de l'aperçu). */
    public static final double T_MAIN = 0.3, T_FISSURE = 0.6, T_SORT = 1.1, T_DEBOUT = 2.5, T_CHARGE = 4.1, T_STOP = 10.1, T_SAUTE = 10.3,
            T_EXPLO = 10.9, T_REPARE = 14.2, T_FIN = 17.2;
    /** Ses pieds sont à cette distance derrière elle quand il sort (son crâne arrive alors sous elle). */
    public static final double RECUL = 18;
    public static final double D_CHARGE = 130, RAMPE = 0.5, FREIN = 0.8, DUREE = T_STOP - T_CHARGE;
    public static final double VMAX = D_CHARGE / (DUREE - RAMPE / 2 - FREIN / 2);
    public static final double FOULEE = 22;
    public static final float ECHELLE = 1.22F;

    public static final int HANCHES = 0, TRONC = 1, COU = 2, TETE = 3, YEUX = 4, MACHOIRE = 5, CUISSE_G = 6, TIBIA_G = 7, PIED_G = 8,
            CUISSE_D = 9, TIBIA_D = 10, PIED_D = 11, QUEUE0 = 12;
    public static final int N = 16;
    private static final int[] PARENT = {-1, 0, 0, 2, 3, 3, 0, 6, 7, 0, 9, 10, 0, 12, 13, 14};
    private static final float[][] PIVOT = {
            {0, 16, 0}, {0, 16, 0}, {0, 19, 9}, {0, 21.6F, 12}, {0, 21.6F, 12}, {0, 21.46F, 13.82F},
            {3.3F, 15.8F, 0.3F}, {3.6F, 9.2F, 3.4F}, {3.3F, 3.4F, -1.6F},
            {-3.3F, 15.8F, 0.3F}, {-3.6F, 9.2F, 3.4F}, {-3.3F, 3.4F, -1.6F},
            {0, 16.8F, -2.6F}, {0, 16.6F, -10}, {0, 15.6F, -17.5F}, {0, 14, -25}};

    /** Des points utiles, dans le repère de leur morceau (autour de son pivot) : le dessus du crâne, la gueule. */
    public static final Vector3f CRANE = new Vector3f(0, 7.0F, 3.22F);
    public static final Vector3f GUEULE = new Vector3f(0, -1.68F, 9.1F);

    private static double lisse(double x) {
        x = Mth.clamp(x, 0, 1);
        return x * x * (3 - 2 * x);
    }

    /** La distance courue (blocs) à ce moment de la charge : elle accélère, file, puis freine. */
    public static double distance(double t) {
        double u = t - T_CHARGE;
        if (u <= 0) {
            return 0;
        }
        if (u >= DUREE) {
            return D_CHARGE;
        }
        if (u < RAMPE) {
            return VMAX * u * u / (2 * RAMPE);
        }
        double d = VMAX * RAMPE / 2;
        if (u < DUREE - FREIN) {
            return d + VMAX * (u - RAMPE);
        }
        d += VMAX * (DUREE - FREIN - RAMPE);
        double w = u - (DUREE - FREIN);
        return d + VMAX * (w - w * w / (2 * FREIN));
    }

    /** Il sort de terre d un coup (0 → 1) : il jaillit, puis ralentit en arrivant debout. */
    public static double monte(double t) {
        double x = Mth.clamp((t - T_SORT) / (T_DEBOUT - T_SORT), 0, 1);
        return 1 - (1 - x) * (1 - x) * (1 - x);
    }

    /** Il marche (0 → 1). */
    public static double marche(double t) {
        return lisse((t - T_CHARGE) / 0.5) * (1 - lisse((t - T_STOP + 0.3) / 0.7));
    }

    /** Il rugit (0 → 1). */
    public static double rugit(double t) {
        if (t < T_DEBOUT) {
            return 0;
        }
        if (t < T_DEBOUT + 0.4) {
            return lisse((t - T_DEBOUT) / 0.4);
        }
        if (t < T_CHARGE - 0.3) {
            return 1;
        }
        return 1 - lisse((t - (T_CHARGE - 0.3)) / 0.4);
    }

    /**
     * Les seize morceaux posés, dans le repère du dinosaure (ses pieds au départ en (0, 0, 0), l'avant vers +z, avant de
     * le tourner vers son cap) : chaque matrice mène des coordonnées du morceau (autour de son pivot, en blocs) à ce repère.
     * {@code d} : la distance courue (pour les pas), {@code mord} : il baisse la tête pour avaler (0 → 1).
     */
    public static Matrix4f[] poser(double t, double d, double mord) {
        double monte = monte(t), m = marche(t), rugit = rugit(t), phi = d / FOULEE * Mth.TWO_PI;
        mord *= m;
        float[][] rot = new float[N][2];
        rot[HANCHES][0] = (float) (0.3 * m - 0.14 * rugit + 0.22 * mord);
        rot[COU][0] = (float) (-0.25 * (1 - monte) - 0.4 * rugit + 0.28 * m + 0.5 * mord + 0.06 * Math.sin(t * 1.3) * (1 - m));
        rot[TETE][0] = (float) (-0.1 * (1 - monte) - 0.3 * rugit + 0.12 * m + 0.25 * mord);
        double gueule = 0.15 + 0.1 * Math.sin(t * 2.1) * monte;
        gueule = gueule + (0.95 - gueule) * rugit;
        if (m > 0) {
            gueule = gueule + (Math.max(0.25 + 0.35 * Math.max(0, Math.sin(t * 8.5)), mord) - gueule) * m;
        }
        rot[MACHOIRE][0] = (float) gueule;
        for (int c = 0; c < 2; c++) {
            double dec = c == 0 ? 0 : Math.PI;
            double cuisse = -0.5 * Math.sin(phi + dec) * m, tibia = 0.6 * Math.max(0, -Math.cos(phi + dec)) * m;
            int base = c == 0 ? CUISSE_G : CUISSE_D;
            rot[base][0] = (float) (cuisse + 0.12 * rugit);
            rot[base + 1][0] = (float) tibia;
            rot[base + 2][0] = (float) (-(cuisse + tibia) * 0.8 - (0.32 * m - 0.14 * rugit));
        }
        for (int i = 0; i < 4; i++) {
            rot[QUEUE0 + i][1] = (float) (0.12 * Math.sin(phi * 0.5 - i * 0.8) * m + 0.05 * Math.sin(t * 1.4 - i * 0.7) * (1 - m));
            rot[QUEUE0 + i][0] = (float) (-0.04 + (i == 0 ? 0.1 * rugit : 0) - 0.05 * m);
        }
        double y = -44 + 44 * monte - 0.5 * m + 0.9 * Math.abs(Math.sin(phi)) * m;
        Matrix4f racine = new Matrix4f().translate(0, (float) y, 0).rotateX((float) (-0.5 + 0.5 * monte)).scale(ECHELLE);
        Matrix4f[] mat = new Matrix4f[N];
        for (int i = 0; i < N; i++) {
            int p = PARENT[i];
            float[] pv = PIVOT[i], pp = p < 0 ? new float[]{0, 0, 0} : PIVOT[p];
            mat[i] = new Matrix4f(p < 0 ? racine : mat[p]).translate(pv[0] - pp[0], pv[1] - pp[1], pv[2] - pp[2])
                    .rotateX(rot[i][0]).rotateY(rot[i][1]);
        }
        return mat;
    }

    /** Un point d'un morceau, dans le monde : le dinosaure est en {@code pos} (ses pieds), tourné vers {@code lacet} (degrés, comme un joueur). */
    public static Vec3 monde(Matrix4f[] mat, int morceau, Vector3f local, Vec3 pos, float lacet) {
        Vector3f p = mat[morceau].transformPosition(new Vector3f(local));
        float a = -lacet * Mth.DEG_TO_RAD, c = Mth.cos(a), s = Mth.sin(a);
        return new Vec3(pos.x + p.x * c + p.z * s, pos.y + p.y, pos.z - p.x * s + p.z * c);
    }

    /** La place du i-ème avalé dans son ventre (repère des hanches, comme l'aperçu). */
    public static Vector3f ventre(int i) {
        return new Vector3f((i % 2 == 0 ? 1.1F : -1.1F), -3.9F + (i % 3) * 0.5F, 1.6F + (i % 8) * 0.95F);
    }

    private DinoPose() {
    }
}
