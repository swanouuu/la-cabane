package fr.cabane.muscu;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.Holder;
import net.minecraft.core.Direction;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.phys.Vec3;

/**
 * Les machines de la salle de muscu (voir docs/muscu). Chacune est construite « face à +Z » : le joueur qui monte
 * dessus regarde +Z, sa gauche est +X, le bloc principal est centré sur l'origine (en blocs). Posée, la machine
 * tourne le dos à celui qui la pose (il monte dessus et part dans la même direction que lui).
 */
public enum Machine {
    // ---- le cardio : on garde le rythme (les touches défilent), le bonus est la vitesse
    TAPIS("tapis_course", "Tapis de course", Mode.RYTHME, "QD", 0.48, 16, 1.4, 0, 0.28 + Poses.HANCHE, 0.05, 0, Gain.VITESSE, 1, 90, true, -1, 1),
    ELLIPTIQUE("velo_elliptique", "Vélo elliptique", Mode.RYTHME, "QDQDZ", 0.55, 15, 0.9, 0, 0.3 + Poses.HANCHE, 0.45, 0, Gain.VITESSE, 1, 90, true, 1),
    VELO("velo_appartement", "Vélo", Mode.RYTHME, "QD", 0.42, 18, 1.1, 0, 0.98 + 0.12, -0.05, 0, Gain.VITESSE, 1, 90, false, 1),
    RAMEUR("rameur", "Rameur", Mode.RYTHME, "ZS", 0.68, 14, 0.45, 0, 0.34 + 0.12, 0, 0, Gain.VITESSE, 1, 90, false, -1, 1),
    // ---- la muscu : une touche au hasard à chaque répétition, le bonus est la force
    TIRAGE_V("tirage_vertical", "Tirage vertical", Mode.TOUCHE, null, 1.2, 8, 0, 0, 0.5 + 0.12, 0.12, 0, Gain.FORCE, 1, 90, false, 1),
    TIRAGE_H("tirage_horizontal", "Tirage horizontal", Mode.TOUCHE, null, 1.2, 8, 0, 0, 0.42 + 0.12, -0.6, 0, Gain.FORCE, 1, 90, false, -1, 1),
    DEVELOPPE("developpe_couche", "Développé couché", Mode.TOUCHE, null, 0.95, 8, 0, 0, 0.46 + 0.12, 0.15, 90, Gain.FORCE, 2, 60, false, -1, 1),
    BICEPS("machine_biceps", "Machine à biceps", Mode.TOUCHE, null, 1.5, 8, 0, 0, 0.65 + 0.12, -0.03, 0, Gain.FORCE, 1, 60, false, 1),
    PRESSE("presse_cuisses", "Presse à cuisses", Mode.TOUCHE, null, 0.95, 8, 0, 0, 0.5, -0.55, 50, Gain.FORCE, 2, 60, false, -1, 1),
    // ---- le rack : on y prend ses haltères (pas de siège)
    RACK("rack_halteres", "Rack d'haltères", Mode.AUCUN, null, 0, 0, 0, 0, 0, 0, 0, Gain.FORCE, 0, 0, true);

    public enum Mode { RYTHME, TOUCHE, AUCUN }

    public enum Gain {
        FORCE("Force"), VITESSE("Vitesse");

        public final String nom;

        Gain(String nom) {
            this.nom = nom;
        }

        public Holder<MobEffect> effet() {
            return this == FORCE ? MobEffects.DAMAGE_BOOST : MobEffects.MOVEMENT_SPEED;
        }
    }

    public final String id;
    public final String nom;
    public final Mode mode;
    /** Rythme : les touches dans l'ordre (Z avant, Q gauche, S arrière, D droite), qu'on répète. */
    public final String motif;
    /** Rythme : une touche toutes les {@code intervalle} secondes ; touche : le temps pour appuyer. */
    public final double intervalle;
    /** Le nombre de touches (rythme) ou de répétitions (touche) d'une série. */
    public final int total;
    /** Cardio : tours de pédale (ou foulées) par seconde, au rythme normal. */
    public final double cadence;
    /** La hanche du joueur (en blocs, repère de la machine) et son inclinaison vers l'arrière (90 = couché sur le dos). */
    public final Vec3 hanche;
    public final double bascule;
    public final Gain gain;
    public final int niveau;
    public final int duree;
    /** Le joueur est debout (tapis, elliptique) plutôt qu'assis. */
    public final boolean debout;
    /** Les cases en plus du bloc principal, vers l'arrière (+Z) ; le rack, lui, s'étale à gauche et à droite. */
    public final int[] arriere;

    Machine(String id, String nom, Mode mode, String motif, double intervalle, int total, double cadence, double hx, double hy, double hz,
            double bascule, Gain gain, int niveau, int duree, boolean debout, int... arriere) {
        this.id = id;
        this.nom = nom;
        this.mode = mode;
        this.motif = motif;
        this.intervalle = intervalle;
        this.total = total;
        this.cadence = cadence;
        this.hanche = new Vec3(hx, hy, hz);
        this.bascule = bascule;
        this.gain = gain;
        this.niveau = niveau;
        this.duree = duree;
        this.debout = debout;
        this.arriere = arriere;
    }

    public boolean cardio() {
        return mode == Mode.RYTHME;
    }

    /** Les charges lourdes demandent parfois deux touches à la suite. */
    public double combo() {
        return intervalle < 1.0 && mode == Mode.TOUCHE ? 0.35 : 0;
    }

    public String charge() {
        return intervalle >= 1.5 ? "légère" : intervalle >= 1.1 ? "moyenne" : "lourde";
    }

    /** Les cases d'une machine posée : {vers la droite, vers le haut, vers l'arrière} vues par celui qui pose. */
    public List<int[]> cases() {
        List<int[]> l = new ArrayList<>();
        if (this == RACK) {
            l.add(new int[] {-1, 0, 0});
            l.add(new int[] {1, 0, 0});
        } else {
            for (int a : arriere) {
                l.add(new int[] {0, 0, a});
            }
        }
        return l;
    }

    public static Machine parId(String id) {
        for (Machine m : values()) {
            if (m.id.equals(id)) {
                return m;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ du repère de la machine au monde

    /** L'angle (radians) dont le modèle tourne autour de l'axe vertical pour une machine posée vers {@code face}. */
    public static double angle(Direction face) {
        return Math.toRadians(-face.toYRot() + 180F);
    }

    /** Un point du repère de la machine (blocs, origine au milieu du bloc principal, au sol) vers un décalage dans le monde. */
    public static Vec3 versMonde(Direction face, double x, double y, double z) {
        double a = angle(face), c = Math.cos(a), s = Math.sin(a);
        return new Vec3(x * c + z * s, y, -x * s + z * c);
    }

    /** Le lacet (yaw) du joueur assis : il regarde +Z de la machine. */
    public static float lacet(Direction face) {
        return face.toYRot() - 180F;
    }

    /** Les haltères du rack et leurs poids. */
    public static final int[] KILOS = {5, 10, 15, 20, 25, 30};

    public static double fenetreHaltere(int kg) {
        return 1.6 - kg * 0.03;
    }

    public static double comboHaltere(int kg) {
        return kg >= 20 ? 0.3 : 0;
    }

    /** {niveau, durée en secondes} du bonus d'une série d'haltères. */
    public static int[] gainHaltere(int kg) {
        return kg <= 10 ? new int[] {1, 45} : kg <= 20 ? new int[] {1, 90} : new int[] {2, 60};
    }
}
