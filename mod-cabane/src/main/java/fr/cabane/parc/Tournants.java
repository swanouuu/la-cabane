package fr.cabane.parc;

/**
 * Les manèges qui tournent (troisième partie du parc, d'après docs/parc/maquette.html) : la Tour de la Chute, la
 * Grande Roue, le Carrousel, les Tasses Folles, le Bateau Pirate et la Toupie. Chaque nacelle (un cheval, une tasse,
 * une cabine, le bateau…) est placée par une formule qui ne dépend que du temps écoulé depuis le départ du tour : le
 * serveur et chaque joueur la calculent pareil, il n'y a que l'heure du départ qui passe par le réseau. Un tour fait
 * toujours un nombre entier de tours de manège, si bien que tout revient à sa place pour les suivants.
 */
public final class Tournants {
    private static final int SOL = Maneges.SOL;
    private static final double TAU = Math.PI * 2;

    public static final byte TOUR = 0, ROUE = 1, CARROUSEL = 2, TASSES = 3, BATEAU = 4, TOUPIE = 5;
    public static final int NOMBRE = 6;

    /** Le nom, le nombre de nacelles, la durée d'un tour et de l'attente en gare (secondes), la nausée en sortant. */
    public record Fiche(byte id, String nom, int nacelles, double duree, int attente, boolean nausee) {
    }

    private static final Fiche[] FICHES = {
            new Fiche(TOUR, "La Tour de la Chute", 1, 22, 12, false),
            new Fiche(ROUE, "La Grande Roue", 16, 80, 0, false),
            new Fiche(CARROUSEL, "Le Carrousel", 14, 40, 12, false),
            new Fiche(TASSES, "Les Tasses Folles", 9, 40, 12, true),
            new Fiche(BATEAU, "Le Bateau Pirate", 1, 39.6, 12, true),
            new Fiche(TOUPIE, "La Toupie", 1, 36, 12, true),
    };

    public static Fiche fiche(byte id) {
        return FICHES[id >= 0 && id < NOMBRE ? id : 0];
    }

    // ------------------------------------------------------------------ où sont les manèges

    public static final double TOUR_X = -30, TOUR_Z = -45;
    /** Tous les manèges qui tournent ont été agrandis de 20 % (rayons, hauteurs). */
    public static final double S = 1.2;
    public static final double ROUE_X = 45, ROUE_Y = SOL + 30.2, ROUE_Z = 85, ROUE_R = 24;
    /** Le carrousel : rayon des chevaux (dehors, dedans) ; les tasses : rayon des petits plateaux et des tasses. */
    public static final double CARROUSEL_R1 = 6.6 * S, CARROUSEL_R2 = 4.1 * S, TASSES_R1 = 6.3 * S, TASSES_R2 = 2.4 * S;
    /** La hauteur de la tour, et celle où la nacelle s'arrête avant de lâcher. */
    public static final int TOUR_H = 86;
    public static final double TOUR_HAUT = 80;
    public static final double CARROUSEL_X = -24, CARROUSEL_Z = 35;
    public static final double TASSES_X = 24, TASSES_Z = 35;
    public static final double BATEAU_X = -40, BATEAU_Y = SOL + 18 * S, BATEAU_Z = 10;
    public static final double TOUPIE_X = 54, TOUPIE_Y = SOL + 12.9, TOUPIE_Z = 25, TOUPIE_L = 9.5 * S;

    /** Le centre (x, z) de chaque manège, pour garder ses tronçons chargés. */
    public static double[][] centres() {
        return new double[][]{{TOUR_X, TOUR_Z}, {ROUE_X, ROUE_Z}, {CARROUSEL_X, CARROUSEL_Z}, {TASSES_X, TASSES_Z}, {BATEAU_X, BATEAU_Z},
                {TOUPIE_X, TOUPIE_Z}};
    }

    // ------------------------------------------------------------------ les mouvements

    static double lisse(double t) {
        return t <= 0 ? 0 : t >= 1 ? 1 : t * t * (3 - 2 * t);
    }

    /** De 0 à 1 en démarrant et en finissant doucement (la vitesse est nulle aux deux bouts, double au milieu). */
    static double rampe(double x) {
        x = Math.max(0, Math.min(1, x));
        return x - Math.sin(TAU * x) / TAU;
    }

    /** 0 au départ et à la fin du tour, 1 au milieu (pour les balancements et les sauts des chevaux). */
    static double ampleur(double t, double duree) {
        return Math.max(0, Math.min(1, Math.min(t / 3, (duree - t) / 3)));
    }

    /** La hauteur de la nacelle de la Tour : la montée, l'attente en haut, la chute libre, le freinage. */
    public static double hauteurTour(double t) {
        if (t < 3) {
            return 2;
        }
        if (t < 14) {
            return 2 + (TOUR_HAUT - 2) * lisse((t - 3) / 11);
        }
        if (t < 17) {
            return TOUR_HAUT;
        }
        if (t < 18.6) {
            double k = t - 17;
            return TOUR_HAUT - 26 * k * k;
        }
        if (t < 20.6) {
            double k = (t - 18.6) / 2;
            double bas = TOUR_HAUT - 26 * 1.6 * 1.6;
            return bas - (bas - 2) * (1 - (1 - k) * (1 - k));
        }
        return 2;
    }

    /** L'angle de la Grande Roue à l'heure du monde (secondes) : un tour en 80 secondes, sans jamais s'arrêter. */
    public static double angleRoue(double secondes) {
        return secondes * TAU / 80;
    }

    public static double plateauCarrousel(double t) {
        return TAU * 3 * rampe(t / fiche(CARROUSEL).duree);
    }

    public static double grandPlateau(double t) {
        return TAU * 2 * rampe(t / fiche(TASSES).duree);
    }

    public static double petitPlateau(double t) {
        return -TAU * 6 * rampe(t / fiche(TASSES).duree);
    }

    public static double balancement(double t) {
        double d = fiche(BATEAU).duree;
        return 1.4 * Math.sin(Math.PI * Math.max(0, Math.min(1, t / d))) * Math.sin(TAU * t / 6.6);
    }

    /** L'angle des bras de la Toupie, et celui de la nacelle (qui se retourne dans l'autre sens). */
    public static double brasToupie(double t) {
        double d = fiche(TOUPIE).duree, env = Math.sin(Math.PI * Math.max(0, Math.min(1, t / d)));
        return TAU * 5 * rampe(t / d) + 1.6 * Math.sin(1.05 * t) * env;
    }

    public static double nacelleToupie(double t) {
        double d = fiche(TOUPIE).duree, env = Math.sin(Math.PI * Math.max(0, Math.min(1, t / d)));
        return -TAU * 4 * rampe(t / d) + (2.2 * Math.sin(0.7 * t) - 1.6 * Math.sin(1.05 * t)) * env;
    }

    private static void poser(Trajet.Point o, double x, double y, double z, double ux, double uy, double uz, double fx, double fy, double fz) {
        o.x = x;
        o.y = y;
        o.z = z;
        o.ux = ux;
        o.uy = uy;
        o.uz = uz;
        o.fx = fx;
        o.fy = fy;
        o.fz = fz;
        o.tour = 0;
        o.mode = Trajet.LIBRE;
        o.noir = false;
    }

    /**
     * Où est la nacelle k de ce manège, et comment elle est tournée (le haut, l'avant), {@code t} secondes après le
     * départ du tour (la Grande Roue : t est l'heure du monde, en secondes).
     */
    public static Trajet.Point cadre(byte id, int k, double t, Trajet.Point o) {
        switch (id) {
            case TOUR -> poser(o, TOUR_X, SOL + hauteurTour(t), TOUR_Z, 0, 1, 0, 0, 0, 1);
            case ROUE -> {
                double a = -Math.PI / 2 + k * TAU / 16 + angleRoue(t);
                poser(o, ROUE_X + Math.cos(a) * ROUE_R, ROUE_Y + Math.sin(a) * ROUE_R, ROUE_Z, 0, 1, 0, 0, 0, 1);
            }
            case CARROUSEL -> {
                double r = k < 8 ? CARROUSEL_R1 : CARROUSEL_R2, a = k < 8 ? k * TAU / 8 : (k - 8 + 0.5) * TAU / 6, ph = k * 0.9;
                double b = a + plateauCarrousel(t);
                double saut = 0.55 * Math.sin(2.2 * t + ph) * ampleur(t, fiche(CARROUSEL).duree);
                poser(o, CARROUSEL_X + Math.sin(b) * r, SOL + 1.3 + saut, CARROUSEL_Z + Math.cos(b) * r, 0, 1, 0, Math.cos(b), 0, -Math.sin(b));
            }
            case TASSES -> {
                int i = k / 3, j = k % 3;
                double g = grandPlateau(t), p = g + petitPlateau(t);
                double ai = i * TAU / 3 + g, bj = j * TAU / 3 + p;
                double x = TASSES_X + Math.cos(ai) * TASSES_R1 + Math.cos(bj) * TASSES_R2, z = TASSES_Z + Math.sin(ai) * TASSES_R1 + Math.sin(bj) * TASSES_R2;
                double psi = p + TAU * (8 + k % 4) * rampe(t / fiche(TASSES).duree) + k * 0.7;
                poser(o, x, SOL + 0.9, z, 0, 1, 0, Math.sin(psi), 0, Math.cos(psi));
            }
            case BATEAU -> {
                double f = balancement(t), c = Math.cos(f), s = Math.sin(f);
                poser(o, BATEAU_X, BATEAU_Y, BATEAU_Z, -s, c, 0, c, s, 0);
            }
            case TOUPIE -> {
                double a = brasToupie(t), n = a + nacelleToupie(t);
                double cy = -TOUPIE_L * Math.cos(a), cz = -TOUPIE_L * Math.sin(a);
                poser(o, TOUPIE_X, TOUPIE_Y + cy, TOUPIE_Z + cz, 0, Math.cos(n), Math.sin(n), 0, -Math.sin(n), Math.cos(n));
            }
            default -> poser(o, 0, SOL, 0, 0, 1, 0, 0, 0, 1);
        }
        return o;
    }

    // ------------------------------------------------------------------ les places

    private static final double[][] PLACES_TOUR, PLACES_BATEAU, PLACES_TOUPIE;
    private static final double[][] PLACES_ROUE = {{-0.5, -3.35, 0.75, Math.PI}, {0.5, -3.35, -0.75, 0}};
    private static final double[][] PLACES_CHEVAL = {{0, 0.55, -0.05, 0}};
    private static final double[][] PLACES_TASSE = {{0, 0.72, -0.7, 0}, {0, 0.72, 0.7, Math.PI}};

    static {
        PLACES_TOUR = new double[12][];
        for (int s = 0; s < 4; s++) {
            double a = s * Math.PI / 2, c = Math.cos(a), sn = Math.sin(a);
            for (int j = -1; j <= 1; j++) {
                double x = j * 1.9, z = 3.9;
                PLACES_TOUR[s * 3 + j + 1] = new double[]{x * c + z * sn, 0.75, -x * sn + z * c, a};
            }
        }
        PLACES_BATEAU = new double[16][];
        int n = 0;
        for (int sx = -1; sx <= 1; sx += 2) {
            for (int r = 1; r <= 4; r++) {
                for (int cote = -1; cote <= 1; cote += 2) {
                    PLACES_BATEAU[n++] = new double[]{cote * 0.75 * S, -11.7 * S, sx * (r * 2 - 0.6) * S, sx > 0 ? Math.PI : 0};
                }
            }
        }
        PLACES_TOUPIE = new double[8][];
        for (int i = 0; i < 8; i++) {
            PLACES_TOUPIE[i] = new double[]{-4.4 + i * 1.26, 0.5, 0, 0};
        }
    }

    /** Les places d'une nacelle, dans son repère (x à gauche, y en haut, z devant) : position du siège et vers où l'on regarde. */
    public static double[][] places(byte id) {
        return switch (id) {
            case TOUR -> PLACES_TOUR;
            case ROUE -> PLACES_ROUE;
            case CARROUSEL -> PLACES_CHEVAL;
            case TASSES -> PLACES_TASSE;
            case BATEAU -> PLACES_BATEAU;
            default -> PLACES_TOUPIE;
        };
    }

    /** La Grande Roue : la cabine k est-elle en bas, devant le quai (on y monte et on en descend) ? */
    public static boolean roueEnBas(int k, double secondes) {
        double a = k * TAU / 16 + angleRoue(secondes);
        a = a - Math.floor((a + Math.PI) / TAU) * TAU;
        return Math.abs(a) < 0.11;
    }

    private Tournants() {
    }
}
