package fr.cabane.parc;

/**
 * Les manèges sur rails du parc : leur parcours, leurs wagons et leurs vitesses. Partagé par le serveur (qui fait
 * avancer les trains) et le client (qui dessine les rails et oriente la caméra). Les parcours reprennent ceux de la
 * maquette (docs/parc/maquette.html), mêmes coordonnées : x vers l'est, z vers le sud, y compté depuis le sol.
 */
public final class Maneges {
    /** Le premier bloc d'air au-dessus du sol du parc. */
    public static final int SOL = 64;

    /** Les numéros des manèges (envoyés aux clients avec chaque wagon). */
    public static final byte DRAGON = 0, MINE = 1, COSMOS = 2, SPLASH = 3;
    /** Combien de manèges à trains. */
    public static final int NOMBRE = 4;
    /** Le chemin à pied de la Maison Hantée (un parcours, mais sans train). */
    public static final byte MAISON = 10;

    /** Le niveau de l'eau sous les troncs de la Rivière Splash (le bas du tronc trempe dans le lac). */
    public static final double EAU = SOL - 0.45;
    /** La Maison Hantée : le bloc du milieu (la maison va de -18 à +18 en x, de -15 à +11 en z autour de lui). */
    public static final int MAISON_X = -100, MAISON_Z = 104;

    /**
     * Combien de wagons, l'écart entre eux, les places par wagon, l'attente en gare (ticks), la nausée en sortant, et
     * l'arrêt en gare imposé (négatif : au milieu de la gare).
     */
    public record Fiche(byte id, String nom, int voitures, double ecart, int places, int attente, boolean nausee, double arretImpose) {
        public double arret() {
            return arretImpose >= 0 ? arretImpose : trajet(id).arretGare(voitures, ecart);
        }
    }

    private static final Fiche[] FICHES = {
            new Fiche(DRAGON, "Le Dragon", 4, 2.95, 2, 20 * 10, true, 15),
            new Fiche(MINE, "La Mine Hantée", 3, 2.75, 2, 20 * 8, false, -1),
            new Fiche(COSMOS, "Cosmos", 2, 3.6, 4, 20 * 8, true, -1),
            new Fiche(SPLASH, "Rivière Splash", 2, 5.0, 3, 20 * 8, false, -1),
    };
    private static final Trajet[] TRAJETS = new Trajet[NOMBRE];
    private static Trajet maison;

    public static Fiche fiche(byte id) {
        return FICHES[id >= 0 && id < NOMBRE ? id : 0];
    }

    /** Le manège dont le nom (sans accents ni espaces, en minuscules) commence par {@code mot}, ou -1. */
    public static byte parNom(String mot) {
        return switch (mot) {
            case "dragon" -> DRAGON;
            case "mine" -> MINE;
            case "cosmos" -> COSMOS;
            case "splash", "riviere" -> SPLASH;
            default -> -1;
        };
    }

    public static synchronized Trajet trajet(byte id) {
        if (id == MAISON) {
            if (maison == null) {
                maison = construireMaison();
            }
            return maison;
        }
        int k = id >= 0 && id < NOMBRE ? id : 0;
        if (TRAJETS[k] == null) {
            TRAJETS[k] = switch (k) {
                case MINE -> construireMine();
                case COSMOS -> construireCosmos();
                case SPLASH -> construireSplash();
                default -> construireDragon();
            };
        }
        return TRAJETS[k];
    }

    // ------------------------------------------------------------------ outils (ceux de la maquette)

    private static final double TAU = Math.PI * 2;

    static double lisse(double t) {
        return t <= 0 ? 0 : t >= 1 ? 1 : t * t * (3 - 2 * t);
    }

    /** 0 avant a, 1 après b, une montée douce entre les deux. */
    public static double entre(double a, double b, double t) {
        return lisse((t - a) / (b - a));
    }

    /** 1 entre a et b, 0 ailleurs, avec des bords doux. */
    public static double bosse(double a, double b, double t) {
        return entre(a, a + 0.12, t) * (1 - entre(b - 0.12, b, t));
    }

    // ------------------------------------------------------------------ Le Dragon

    /**
     * Le Dragon : la gare (allant vers le nord), la montée à la chaîne jusqu'à 50 blocs, le grand plongeon, le looping,
     * un virage penché qui remonte, la descente, la vrille, la bosse, et le tunnel du dragon où le train est relancé
     * d'un coup, puis le virage freiné qui ramène en gare.
     */
    private static Trajet construireDragon() {
        Trajet.Tortue t = new Trajet.Tortue(120, SOL + 4, 60, 0, -1);
        t.droit(30, 0, Trajet.GARE, false);
        t.droit(40, 46, Trajet.LIFT, false);
        t.droit(6, 0);
        t.droit(45, -45);
        t.boucle(14, 5);
        t.droit(12, 0);
        t.virage(22, Math.PI, 13, 0.9, Trajet.LIBRE, false);
        t.droit(20, -10);
        t.vrille(30, 5);
        t.colline(30, 12);
        t.droit(10, -5);
        t.droit(45, 0, Trajet.BOOST, true);
        t.virage(19.5, Math.PI, 1, 0.6, Trajet.FREIN, false);
        return t.fermer(Trajet.GARE);
    }

    // ------------------------------------------------------------------ La Mine Hantée

    public static final double MINE_X = -95, MINE_Z = -40;
    /** La Mine a été agrandie (parcours plus long, plus haut, montagne plus grande). */
    public static final double MINE_S = 1.25;

    /**
     * La Mine Hantée : un wagonnet qui entre dans la montagne. Le noir, des lanternes, une montée au pas sous des yeux
     * rouges, la salle où le wagon tourne deux fois sur lui-même, la salle des fantômes, la chute dans le noir complet et
     * le squelette qui tombe du plafond avant la sortie. La gare est à l'est (u = 0), le train y va vers le sud.
     */
    private static Trajet construireMine() {
        Trajet.Liste l = new Trajet.Liste();
        int n = 1400;
        for (int i = 0; i < n; i++) {
            double u = (double) i / n * TAU, r = MINE_S * (21 + 8 * Math.cos(u) + 3 * Math.sin(3 * u));
            double y = SOL + 1.2 + 9.8 * MINE_S * (entre(1.9, 2.7, u) - entre(3.5, 4.0, u));
            boolean gare = u < 0.24 || u > TAU - 0.24;
            byte mode = gare ? Trajet.GARE : u >= 1.9 && u < 2.75 ? Trajet.LENT : u >= 3.5 && u < 4.4 ? Trajet.CHUTE : Trajet.LIBRE;
            l.point(MINE_X + r * Math.cos(u), y, MINE_Z + r * Math.sin(u), mode, !gare, TAU * 2 * entre(1.05, 1.55, u), u);
        }
        return l.fermer();
    }

    /** La largeur et la hauteur du tunnel de la mine à cet endroit (plus grand dans la salle qui tourne et celle des fantômes). */
    public static double mineLargeur(double u) {
        return 4.4 + 6 * bosse(1.0, 1.6, u) + 5 * bosse(2.8, 3.45, u);
    }

    public static double mineHauteur(double u) {
        return 4.6 + 3.5 * bosse(1.0, 1.6, u) + 3 * bosse(2.8, 3.45, u);
    }

    // ------------------------------------------------------------------ Cosmos

    /** Le Cosmos a été agrandi de 20 % (dôme, rail, hauteurs). */
    public static final double COSMOS_S = 1.2;
    public static final double COSMOS_X = 25, COSMOS_Z = -55, COSMOS_R = 26 * COSMOS_S;

    /**
     * Cosmos : sous un dôme noir étoilé, un rail en néon qui ondule, et des wagons qui tournent sur eux-mêmes tout le
     * long. La gare est au sud du dôme, au point le plus bas ; les wagons n'y tournent pas.
     */
    private static Trajet construireCosmos() {
        Trajet.Liste l = new Trajet.Liste();
        int n = 1200;
        double garde = 0.25;
        for (int i = 0; i < n; i++) {
            double v = (double) i / n * TAU, u = v + Math.PI / 2, loin = Math.min(v, TAU - v);
            double r = COSMOS_S * (13 + 5 * Math.sin(4 * u)), y0 = COSMOS_S * (6.5 + 5 * Math.sin(3 * u));
            double w = 1 - entre(garde, 0.6, loin);
            double y = SOL + y0 + (1.5 - y0) * w;
            double frac = Math.max(0, Math.min(1, (v - garde) / (TAU - 2 * garde)));
            double tour = TAU * 5 * lisse(frac) + 2.2 * Math.sin(3 * TAU * frac) * Math.sin(Math.PI * frac);
            l.point(COSMOS_X + r * Math.cos(u), y, COSMOS_Z + r * Math.sin(u), loin < garde ? Trajet.GARE : Trajet.LIBRE, true, tour, v);
        }
        return l.fermer();
    }

    // ------------------------------------------------------------------ Rivière Splash

    /** La Rivière Splash a été agrandie de 20 % (lac, parcours, grande descente) et décalée vers l'ouest. */
    public static final double SPLASH_S = 1.2;
    public static final double SPLASH_X = -105, SPLASH_Z = 48;
    /** Le paramètre u du splash (en bas de la grande descente) et de la photo (juste avant). */
    public static final double U_SPLASH = 4.89, U_PHOTO = 4.8;

    /**
     * La Rivière Splash : un tronc qui flotte sur le lac, passe dans la grotte aux cristaux, remonte au pas jusqu'à 26
     * blocs, puis la grande descente et le splash. La gare est à l'est du lac (u = 0), le tronc y va vers le sud.
     */
    private static Trajet construireSplash() {
        Trajet.Liste l = new Trajet.Liste();
        int n = 1400;
        for (int i = 0; i < n; i++) {
            double u = (double) i / n * TAU;
            double x = SPLASH_X + SPLASH_S * (30 * Math.cos(u) + 4 * Math.cos(3 * u)), z = SPLASH_Z + SPLASH_S * (24 * Math.sin(u) + 3 * Math.sin(2 * u));
            double y = EAU + 26 * SPLASH_S * (entre(3.2, 4.15, u) - entre(4.45, 4.88, u));
            boolean gare = u < 0.22 || u > TAU - 0.22;
            byte mode = gare ? Trajet.GARE : u >= 3.2 && u < 4.25 ? Trajet.LIFT : u >= 4.42 && u < 5.0 ? Trajet.CHUTE : Trajet.EAU;
            l.point(x, y, z, mode, u > 0.45 && u < 1.15, 0, u);
        }
        return l.fermer();
    }

    /**
     * Où est l'appareil photo de la Rivière Splash (en bas de la grande descente, à 7 blocs sur la gauche du tronc) :
     * x, z du poteau, puis x, y, z de l'objectif. Partagé par la construction et le client.
     */
    public static double[] appareilPhoto() {
        Trajet t = trajet(SPLASH);
        Trajet.Point p = t.a(t.ou(U_SPLASH), new Trajet.Point());
        double gx = p.uy * p.fz - p.uz * p.fy, gz = p.ux * p.fy - p.uy * p.fx, l = Math.max(1e-6, Math.hypot(gx, gz));
        gx /= l;
        gz /= l;
        double x = p.x + gx * 7 + p.fx * 1.5, z = p.z + gz * 7 + p.fz * 1.5;
        return new double[]{x, z, x - gx * 0.8, SOL + 4.6, z - gz * 0.8};
    }

    // ------------------------------------------------------------------ La Maison Hantée (à pied)

    /**
     * Le chemin à pied de la Maison Hantée : on entre par la porte de gauche, le couloir aux bougies, la galerie des
     * portraits (à l'ouest), le tunnel des cris (au fond), puis on ressort par la porte de droite et on revient devant le
     * perron. Les couloirs sont creusés le long de ce chemin.
     */
    private static Trajet construireMaison() {
        double cx = MAISON_X + 0.5, cz = MAISON_Z + 0.5, h = Math.PI / 2;
        Trajet.Tortue t = new Trajet.Tortue(cx - 3, SOL, cz + 15, 0, -1);
        t.droit(8, 0).virage(3, h, 0, 0, Trajet.LIBRE, false).droit(3, 0).virage(3, -h, 0, 0, Trajet.LIBRE, false).droit(6, 0)
                .virage(3, -h, 0, 0, Trajet.LIBRE, false).droit(18, 0).virage(3, -h, 0, 0, Trajet.LIBRE, false).droit(6, 0)
                .virage(3, -h, 0, 0, Trajet.LIBRE, false).droit(3, 0).virage(3, h, 0, 0, Trajet.LIBRE, false).droit(8, 0)
                .virage(3, -Math.PI, 0, 0, Trajet.LIBRE, false);
        return t.fermer(Trajet.LIBRE);
    }

    /** Ce qu'il y a à cet endroit de la maison : 0 dehors, 1 un couloir, 2 la galerie des portraits, 3 le tunnel des cris. */
    public static int maisonZone(double x, double z) {
        double lx = x - (MAISON_X + 0.5), lz = z - (MAISON_Z + 0.5);
        if (lz >= 12) {
            return 0;
        }
        if (lz < -6.5 && lx > -9.5 && lx < 9.5) {
            return 3;
        }
        return lx < -10 ? 2 : 1;
    }

    /** Les trois screamers du tunnel des cris : à 3, 9 et 15 blocs de l'entrée du tunnel (distances sur le chemin). */
    public static double[] maisonCris() {
        Trajet t = trajet(MAISON);
        Trajet.Point p = new Trajet.Point();
        double debut = 0;
        for (double s = 0; s < t.total; s += 0.25) {
            t.a(s, p);
            if (maisonZone(p.x, p.z) == 3) {
                debut = s;
                break;
            }
        }
        return new double[]{debut + 3, debut + 9, debut + 15};
    }

    // ------------------------------------------------------------------ vitesses et places

    /** La pesanteur des manèges (blocs/s²) : un peu plus que sur Terre, pour que les descentes soient franches. */
    private static final double G = 16;

    /** Rapproche v de la vitesse voulue, d'au plus {@code a} blocs/s² (la chaîne, les freins, les roues motrices…). */
    private static double vers(double v, double voulu, double a, double dt) {
        return v < voulu ? Math.min(voulu, v + a * dt) : Math.max(voulu, v - a * dt);
    }

    /** La roue libre : la pente accélère ou ralentit le train, l'air le freine un peu ; jamais moins que le plancher. */
    private static double libre(Trajet.Point p, double v, double dt, double frottement, double plancher) {
        v += (-G * p.fy - frottement * v * v) * dt;
        return Math.max(plancher, v);
    }

    /**
     * La vitesse du train au tick suivant (blocs par seconde), {@code p} étant le milieu du train. En gare, à la chaîne,
     * au frein et au lancement, le manège impose sa vitesse (avec une accélération qu'on sent) ; ailleurs c'est la
     * physique : plus ça descend, plus ça va vite, et le train ralentit vraiment en remontant (en haut du looping, sur les
     * bosses). Le Cosmos et la Mine ont des roues motrices : jamais en dessous de leur plancher.
     */
    public static double vitesseSuivante(byte id, Trajet.Point p, double v, double dt) {
        return switch (id) {
            case MINE -> switch (p.mode) {
                case Trajet.GARE -> vers(v, 3, 9, dt);
                // la montée au pas, sous les yeux rouges
                case Trajet.LENT -> vers(v, 2.6, 9, dt);
                case Trajet.CHUTE -> libre(p, v, dt, 0.002, 4);
                // dans les galeries, le wagonnet est poussé : il fonce, et les virages le secouent
                default -> libre(p, vers(v, 11, 3, dt), dt, 0.004, 9);
            };
            case COSMOS -> switch (p.mode) {
                case Trajet.GARE -> vers(v, 4, 14, dt);
                default -> libre(p, v, dt, 0.003, 7.5);
            };
            case SPLASH -> switch (p.mode) {
                case Trajet.GARE -> vers(v, 3, 4, dt);
                case Trajet.LIFT -> vers(v, 4.8, 4, dt);
                case Trajet.CHUTE -> libre(p, v, dt, 0.0008, 4.8);
                // l'eau freine fort après la descente (le splash), puis le tronc se laisse porter
                default -> vers(v, 6.5, v > 9 ? 14 : 2, dt);
            };
            default -> switch (p.mode) {
                case Trajet.GARE -> vers(v, 4, 6, dt);
                case Trajet.LIFT -> vers(v, 5.5, 5, dt);
                case Trajet.FREIN -> vers(v, 7, 16, dt);
                // le lancement dans le tunnel du dragon : on est plaqué au siège
                case Trajet.BOOST -> vers(v, 48, 26, dt);
                default -> libre(p, v, dt, 0.0011, 6);
            };
        };
    }

    private static final double[][] PLACES_DRAGON = {{0.45, 0.95, -0.3, 0}, {-0.45, 0.95, -0.3, 0}};
    private static final double[][] PLACES_MINE = {{0.4, 0.7, 0, 0}, {-0.4, 0.7, 0, 0}};
    private static final double[][] PLACES_COSMOS = {{0, 0.75, 0.6, 0}, {0.6, 0.75, 0, Math.PI / 2}, {0, 0.75, -0.6, Math.PI}, {-0.6, 0.75, 0, -Math.PI / 2}};
    private static final double[][] PLACES_SPLASH = {{0, 0.45, 1.15, 0}, {0, 0.45, 0, 0}, {0, 0.45, -1.15, 0}};

    /**
     * Les places d'un wagon, dans son repère (x vers la gauche, y vers le haut, z vers l'avant), au niveau du siège,
     * et vers où l'on regarde (radians, vers la gauche : les sièges du Cosmos regardent dehors, dos à dos).
     */
    public static double[][] places(byte id) {
        return switch (id) {
            case MINE -> PLACES_MINE;
            case COSMOS -> PLACES_COSMOS;
            case SPLASH -> PLACES_SPLASH;
            default -> PLACES_DRAGON;
        };
    }

    private Maneges() {
    }
}
