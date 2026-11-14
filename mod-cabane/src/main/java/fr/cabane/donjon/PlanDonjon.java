package fr.cabane.donjon;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import net.minecraft.world.phys.Vec3;

/**
 * Le plan d'un donjon tracé par le mod (tous sauf la grotte d'Ugrak). Le même tirage (une graine par donjon) donne
 * toujours le même plan, côté serveur comme côté client.
 *
 * <p>Le chemin est toujours le même, mais les salles bougent d'un donjon à l'autre :
 * <ol>
 * <li>la salle d'arrivée (le portail de sortie au nord), un couloir, puis le carrefour ;</li>
 * <li>deux ailes, à l'ouest et à l'est : dans l'une, la salle de « l'ordre » (quatre feux à allumer dans le bon
 * ordre) ; dans l'autre, la salle du « code » (quatre leviers) ; chaque salle a son sceau ;</li>
 * <li>l'indice de chaque énigme est gravé au fond d'une salle de l'AUTRE aile : il faut se séparer et se parler ;</li>
 * <li>dans chaque aile, un cul-de-sac : un coffre d'un côté, une embuscade de l'autre ; des cristaux un peu partout ;</li>
 * <li>la porte des sceaux (quelqu'un sur chaque sceau en même temps), l'escalier qui descend, la salle des gardes,
 * l'antichambre, la grande porte (qui attend tout le monde) et l'arène du boss.</li>
 * </ol>
 */
public final class PlanDonjon {
    public static final int SOL = DonjonBuilder.SOL;
    public static final int SOL_BAS = DonjonBuilder.SOL_BAS;
    /** Les sceaux sont à tant de blocs au sud du centre de leur salle. */
    public static final int DZ_SCEAU = 6;

    /** Une salle ronde (ou ovale), sous un dôme. */
    public record Salle(String nom, int cx, int cz, double rx, double rz, int hauteur, boolean bas) {
        boolean dans(int x, int z) {
            return q(x, z) <= 1;
        }

        double q(int x, int z) {
            return sq((x - cx) / rx) + sq((z - cz) / rz);
        }

        public Vec3 centre() {
            return new Vec3(cx + 0.5, (bas ? SOL_BAS : SOL) + 1, cz + 0.5);
        }
    }

    /** Un couloir droit : x0..x1, z0..z1, sa hauteur au-dessus du sol. */
    public record Couloir(int x0, int x1, int z0, int z1, int h) {
        boolean dans(int x, int z) {
            return x >= x0 && x <= x1 && z >= z0 && z <= z1;
        }
    }

    public final TypeDonjon type;
    public final int x0;
    public final List<Salle> salles = new ArrayList<>();
    public final List<Couloir> couloirs = new ArrayList<>();
    public final Salle arrivee, carrefour, ordre, code, indiceOrdre, indiceCode, coffre, embuscade, gardes, arene;
    /** La porte des sceaux (z), l'escalier (du haut au bas), l'antichambre (z0..z1), la grande porte (z). */
    public final int porteSceauxZ, escalierZ0, escalierZ1, antichambreZ0, antichambreZ1, grandePorteZ;
    /** La salle de l'ordre est-elle à l'ouest ? (sinon c'est celle du code). */
    public final boolean ordreOuest;

    private static final Map<TypeDonjon, PlanDonjon> PLANS = new EnumMap<>(TypeDonjon.class);

    public static synchronized PlanDonjon de(TypeDonjon type) {
        return PLANS.computeIfAbsent(type, PlanDonjon::new);
    }

    private static double sq(double v) {
        return v * v;
    }

    private PlanDonjon(TypeDonjon type) {
        this.type = type;
        this.x0 = type.x0();
        Random r = new Random(7331L * (type.ordinal() + 1) + 4242);
        // l'arrivée : une grotte ovale, comme celle d'Ugrak (le portail de sortie au nord, à z = -7.5)
        arrivee = salle("arrivee", 0, 3, 11.5, 14.5, 13, false);
        int cz = 38 + r.nextInt(8);
        couloirs.add(new Couloir(x0 - 3, x0 + 3, 14, cz - 8, 7));
        carrefour = salle("carrefour", 0, cz, 11.5, 11.5, 14, false);
        ordreOuest = r.nextBoolean();
        // les deux ailes : la salle de l'énigme, son indice (pour l'autre aile), un cul-de-sac
        Salle[] ouest = aile(r, -1, cz);
        Salle[] est = aile(r, 1, cz);
        ordre = ordreOuest ? ouest[0] : est[0];
        code = ordreOuest ? est[0] : ouest[0];
        // l'indice de l'ordre est dans l'aile du code, et inversement
        indiceOrdre = ordreOuest ? est[1] : ouest[1];
        indiceCode = ordreOuest ? ouest[1] : est[1];
        boolean coffreOuest = r.nextBoolean();
        coffre = coffreOuest ? ouest[2] : est[2];
        embuscade = coffreOuest ? est[2] : ouest[2];
        // la porte des sceaux, au sud du carrefour, puis l'escalier qui descend de huit blocs
        porteSceauxZ = cz + 12;
        escalierZ0 = cz + 20;
        escalierZ1 = escalierZ0 + 16;
        couloirs.add(new Couloir(x0 - 3, x0 + 3, cz + 9, escalierZ1 + 2, 8));
        // la salle des gardes : longue, ou ronde
        int gz = escalierZ1 + 3;
        int longueur = 28 + r.nextInt(8);
        if (r.nextBoolean()) {
            gardes = new Salle("gardes", x0, gz + longueur / 2, 15, longueur / 2.0 + 1, 15, true);
        } else {
            gardes = new Salle("gardes", x0, gz + longueur / 2, 13.5, longueur / 2.0 + 1, 17, true);
        }
        salles.add(gardes);
        antichambreZ0 = gz + longueur - 1;
        antichambreZ1 = antichambreZ0 + 11;
        couloirs.add(new Couloir(x0 - 8, x0 + 8, antichambreZ0, antichambreZ1, 12));
        grandePorteZ = antichambreZ1 + 1;
        double ra = 25.5 + r.nextInt(4);
        arene = new Salle("arene", x0, grandePorteZ + (int) ra + 1, ra, ra, 30, true);
        salles.add(arene);
    }

    private Salle salle(String nom, int dx, int z, double rx, double rz, int h, boolean bas) {
        Salle s = new Salle(nom, x0 + dx, z, rx, rz, h, bas);
        salles.add(s);
        return s;
    }

    /**
     * Une aile ({@code s} = -1 à l'ouest, 1 à l'est) : un couloir jusqu'à un coude, puis la salle de l'énigme au sud ;
     * du coude, un couloir vers le nord mène à la salle de l'indice ; de la salle de l'énigme, un long couloir vers
     * l'extérieur finit en cul-de-sac.
     */
    private Salle[] aile(Random r, int s, int cz) {
        int bx = 32 + r.nextInt(7);
        // du carrefour au coude
        int xa = s < 0 ? x0 - bx - 4 : x0 + 10, xb = s < 0 ? x0 - 10 : x0 + bx + 4;
        couloirs.add(new Couloir(xa, xb, cz - 3, cz + 3, 6));
        int ex = x0 + s * bx;
        couloirs.add(new Couloir(ex - 4, ex + 4, cz - 7, cz + 7, 8));
        // la salle de l'énigme, au sud du coude
        int ez = cz + 22 + r.nextInt(4);
        couloirs.add(new Couloir(ex - 3, ex + 3, cz + 6, ez - 8, 6));
        Salle enigme = new Salle(s < 0 ? "enigme_ouest" : "enigme_est", ex, ez, 10.5, 10.5, 12, false);
        salles.add(enigme);
        // la salle de l'indice, au nord du coude (au bout d'un couloir qui tourne vers l'extérieur)
        int iz = cz - 22 - r.nextInt(6);
        couloirs.add(new Couloir(ex - 3, ex + 3, iz + 3, cz - 6, 6));
        int ix = ex + s * (12 + r.nextInt(6));
        couloirs.add(new Couloir(Math.min(ex, ix) - 3, Math.max(ex, ix) + 3, iz - 3, iz + 3, 6));
        Salle indice = new Salle(s < 0 ? "indice_ouest" : "indice_est", ix + s * 4, iz, 6.5, 7.5, 8, false);
        salles.add(indice);
        // le cul-de-sac, vers l'extérieur, depuis la salle de l'énigme
        int dx = ex + s * (20 + r.nextInt(5));
        couloirs.add(new Couloir(Math.min(ex, dx) + (s < 0 ? 0 : 9), Math.max(ex, dx) - (s < 0 ? 9 : 0), ez - 3, ez + 3, 6));
        Salle cul = new Salle(s < 0 ? "cul_ouest" : "cul_est", dx + s * 3, ez, 6.5, 6.5, 8, false);
        salles.add(cul);
        return new Salle[] {enigme, indice, cul};
    }

    // ---------------------------------------------------------------- les mesures

    /** Le sol (le bloc sur lequel on marche) de chaque endroit. */
    public int sol(int x, int z) {
        if (Math.abs(x - x0) <= 3 && z >= escalierZ0 - 2 && z <= escalierZ1 + 2) {
            return z < escalierZ0 ? SOL : Math.max(SOL_BAS, SOL - (z - escalierZ0) / 2);
        }
        return z > escalierZ1 && Math.abs(x - x0) <= 34 ? SOL_BAS : SOL;
    }

    /** Le plafond (le dernier bloc vide) de chaque endroit, ou -1 dans la roche. */
    public int plafond(int x, int z) {
        int h = -1;
        // l'embrasure de la grande porte (la porte elle-même : Epreuves)
        if (z == grandePorteZ && Math.abs(x - x0) <= 3) {
            h = SOL_BAS + 9;
        }
        for (Salle s : salles) {
            double q = s.q(x, z);
            if (q <= 1) {
                int base = s.bas() ? SOL_BAS : SOL;
                int reduit = s.nom().equals("arene") ? 13 : Math.max(3, s.hauteur() / 3);
                h = Math.max(h, base + (int) (s.hauteur() - reduit * q));
            }
        }
        for (Couloir c : couloirs) {
            if (c.dans(x, z)) {
                h = Math.max(h, sol(x, z) + c.h());
            }
        }
        return h;
    }

    public Vec3 arene() {
        return arene.centre();
    }

    public double rayonArene() {
        return arene.rx();
    }

    /** Où dort le boss : au fond de l'arène, face à la porte. */
    public Vec3 repos() {
        return new Vec3(x0 + 0.5, SOL_BAS + 1, arene.cz() + arene.rz() - 9.5);
    }

    /** Le sceau d'une salle d'énigme. */
    public Vec3 sceau(Salle s) {
        return new Vec3(s.cx() + 0.5, SOL + 1, s.cz() + DZ_SCEAU + 0.5);
    }

    public boolean dansAntichambre(Vec3 p) {
        return Math.abs(p.x - (x0 + 0.5)) <= 9 && p.z >= antichambreZ0 && p.z < grandePorteZ && p.y < SOL_BAS + 14;
    }

    /** La boîte qui contient tout le donjon. */
    public int xMin() {
        return x0 - 75;
    }

    public int xMax() {
        return x0 + 75;
    }

    public int zMax() {
        return arene.cz() + (int) arene.rz() + 4;
    }
}
