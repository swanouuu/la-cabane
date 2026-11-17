package fr.cabane.muscu;

import net.minecraft.world.phys.Vec3;

/**
 * Les poses du joueur sur les machines (mêmes calculs chez tout le monde, et pour le rendu des barres, poignées et
 * pédales qui suivent ses mains et ses pieds). Angles en degrés : un bras « vers l'avant » de 90 est tendu devant lui,
 * de 180 au-dessus de la tête ; « écarté » l'ouvre sur le côté. Les distances sont celles du modèle du joueur, en blocs.
 */
public final class Poses {
    /** Hauteur de la hanche (pivot des jambes) au-dessus des pieds. */
    public static final double HANCHE = 0.703;
    static final double EPAULE_H = 0.586, EPAULE_X = 0.293, BRAS = 0.62, JAMBE_X = 0.176, JAMBE = 0.70;

    /**
     * Bras gauche et droit {vers l'avant, écarté}, jambes {vers l'avant}, décalage de la hanche (blocs) et inclinaison du
     * corps vers l'arrière (degrés), le tout relatif au corps.
     */
    public record Pose(double gA, double gB, double dA, double dB, double jgA, double jdA, double dx, double dy, double dz, double bascule) {
    }

    static double lerp(double a, double b, double k) {
        return a + (b - a) * k;
    }

    static double lisse(double u) {
        u = Math.max(0, Math.min(1, u));
        return u < 0.5 ? 2 * u * u : 1 - 2 * (1 - u) * (1 - u);
    }

    /** La pose sur une machine : k de 0 (repos) à 1 (contracté) pour la muscu, ph (radians) la phase du cardio. */
    public static Pose pose(Machine m, double k, double ph) {
        double s = Math.sin(ph), c = Math.cos(ph);
        return switch (m) {
            case TAPIS -> new Pose(-38 * s, 6, 38 * s, 6, 38 * s, -38 * s, 0, 0.04 * Math.abs(Math.sin(ph)), 0, -6);
            case ELLIPTIQUE -> new Pose(45 - 22 * s, 8, 45 + 22 * s, 8, 14 + 16 * s, 14 - 16 * s, 0, 0.02 * c, 0, -4);
            case VELO -> new Pose(80, 6, 80, 6, 60 + 18 * s, 60 - 18 * s, 0, 0, 0, -20);
            case RAMEUR -> {
                double u = ((ph / (2 * Math.PI)) % 1 + 1) % 1;
                double kk = u < 0.4 ? lisse(u / 0.4) : 1 - lisse((u - 0.4) / 0.6);
                double b = lerp(-25, 22, kk), dz = lerp(0.15, -0.08, kk);
                // les pieds restent sur les cale-pieds : l'angle des jambes suit le chariot
                double jambe = Math.toDegrees(Math.atan2(0.72 - (m.hanche.z + dz), 0.08));
                double bras = lerp(85, 25, kk);
                yield new Pose(bras - b, 10, bras - b, 10, jambe - b, jambe - b, 0, 0, dz, b);
            }
            case TIRAGE_V -> new Pose(lerp(170, 15, k), lerp(12, 60, k), lerp(170, 15, k), lerp(12, 60, k), 90, 90, 0, 0, 0, 10 * k);
            case TIRAGE_H -> {
                double b = lerp(-15, 8, k);
                yield new Pose(lerp(85, 20, k) - b, lerp(6, 12, k), lerp(85, 20, k) - b, lerp(6, 12, k), 85 - b, 85 - b, 0, 0, 0, b);
            }
            case DEVELOPPE -> new Pose(lerp(25, 88, k), lerp(55, 10, k), lerp(25, 88, k), lerp(55, 10, k), -55, -55, 0, 0, 0, 90);
            case BICEPS -> new Pose(lerp(70, 165, k), 6, lerp(70, 165, k), 6, 105, 105, 0, 0, 0, -15);
            case PRESSE -> new Pose(15, 22, 15, 22, lerp(145, 95, k), lerp(145, 95, k), 0, 0, 0, 50);
            case RACK -> new Pose(0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
        };
    }

    /** Debout, un haltère en main : le bras {@code gauche} (ou droit) monte. */
    public static Pose haltere(double k, boolean gauche) {
        return new Pose(gauche ? 140 * k : 4, 5, gauche ? 4 : 140 * k, 5, 0, 0, 0, 0, 0, 0);
    }

    // ------------------------------------------------------------------ où sont les mains et les pieds (repère de la machine)

    /** Penche un vecteur du corps vers l'arrière de b degrés (la tête part vers -Z). */
    static Vec3 pencher(double x, double y, double z, double b) {
        double r = Math.toRadians(b), c = Math.cos(r), s = Math.sin(r);
        return new Vec3(x, y * c + z * s, -y * s + z * c);
    }

    public static Vec3 hanche(Machine m, Pose p) {
        return m.hanche.add(p.dx(), p.dy(), p.dz());
    }

    /** Un membre qui pend, levé de a degrés vers l'avant puis écarté de e degrés du côté {@code cote} (+1 gauche). */
    static Vec3 membre(double a, double e, int cote, double longueur) {
        double ar = Math.toRadians(a), er = Math.toRadians(e) * cote;
        return new Vec3(Math.cos(ar) * Math.sin(er), -Math.cos(ar) * Math.cos(er), Math.sin(ar)).scale(longueur);
    }

    /** La main {@code cote} (+1 gauche, -1 droite). */
    public static Vec3 main(Machine m, Pose p, int cote) {
        Vec3 bras = membre(cote > 0 ? p.gA() : p.dA(), cote > 0 ? p.gB() : p.dB(), cote, BRAS);
        Vec3 local = new Vec3(cote * EPAULE_X, EPAULE_H, 0).add(bras);
        return hanche(m, p).add(pencher(local.x, local.y, local.z, p.bascule()));
    }

    public static Vec3 pied(Machine m, Pose p, int cote) {
        Vec3 jambe = membre(cote > 0 ? p.jgA() : p.jdA(), 0, cote, JAMBE);
        Vec3 local = new Vec3(cote * JAMBE_X, 0, 0).add(jambe);
        return hanche(m, p).add(pencher(local.x, local.y, local.z, p.bascule()));
    }

    private Poses() {
    }
}
