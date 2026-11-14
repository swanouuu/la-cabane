package fr.cabane.donjon;

import net.minecraft.world.phys.Vec3;

/**
 * Les donjons que le portail de Jin-woo peut ouvrir. Chacun est construit une fois pour toutes dans la dimension
 * cabane:donjon, à sa place (tous les {@link #ECART} blocs vers l'est) ; un groupe en reçoit un au hasard parmi ceux où
 * il n'y a personne, jamais deux groupes dans le même. Le premier, c'est la grotte d'Ugrak (DonjonBuilder, fait à la
 * main) ; les autres sont tracés par {@link PlanDonjon} et construits par {@link DonjonGenere}.
 */
public enum TypeDonjon {
    OGRE("La Grotte écarlate", "Ugrak, l'Ogre Écarlate", 0xFF3B3B),
    GIVRE("Le Glacier hurlant", "Hrimgar, le Colosse de Givre", 0x7FE8FF),
    ARAIGNEE("Le Nid de soie", "Arachna, la Reine-Araignée", 0x9BFF5A),
    LICHE("La Nécropole", "Morvane, le Roi-Liche", 0xB86CFF),
    FOURMI("La Fourmilière royale", "Kar'Thax, le Roi des Fourmis", 0xFFB040);

    /** L'écart entre deux donjons, d'ouest en est. */
    public static final int ECART = 1024;
    /** Un joueur est « dans » un donjon s'il est à moins de tant de blocs (en x) de son axe. */
    public static final int DEMI_LARGEUR = 400;

    public final String nom;
    public final String boss;
    /** La couleur du nom du boss, dans la fenêtre du Système. */
    public final int couleur;

    TypeDonjon(String nom, String boss, int couleur) {
        this.nom = nom;
        this.boss = boss;
        this.couleur = couleur;
    }

    /** L'axe du donjon (x du milieu de l'arrivée). */
    public int x0() {
        return ordinal() * ECART;
    }

    /** Le donjon où se trouve ce point. */
    public static TypeDonjon de(double x) {
        int k = (int) Math.floor((x + ECART / 2.0) / ECART);
        TypeDonjon[] t = values();
        return t[Math.max(0, Math.min(t.length - 1, k))];
    }

    public boolean contient(double x) {
        return Math.abs(x - x0()) < DEMI_LARGEUR;
    }

    public boolean genere() {
        return this != OGRE;
    }

    /** Où l'on arrive (face au sud, le portail de sortie dans le dos). */
    public Vec3 arrivee() {
        return DonjonBuilder.ARRIVEE.add(x0(), 0, 0);
    }

    /** Le portail de sortie, derrière l'arrivée. */
    public Vec3 sortie() {
        return DonjonBuilder.SORTIE.add(x0(), 0, 0);
    }

    /** Le centre de l'arène, au niveau du sol. */
    public Vec3 arene() {
        return genere() ? PlanDonjon.de(this).arene() : DonjonBuilder.ARENE;
    }

    /** Le portail qui s'ouvre au milieu de l'arène quand le boss est mort. */
    public Vec3 sortieArene() {
        return arene().add(0, 4.5, 0);
    }

    /** Le rayon de l'arène (le boss n'en sort pas, et c'est là qu'on se bat). */
    public double rayonArene() {
        return genere() ? PlanDonjon.de(this).rayonArene() : 27.5;
    }

    public String id() {
        return name().toLowerCase();
    }

    public static TypeDonjon parId(String id) {
        for (TypeDonjon t : values()) {
            if (t.id().equals(id)) {
                return t;
            }
        }
        return null;
    }
}
