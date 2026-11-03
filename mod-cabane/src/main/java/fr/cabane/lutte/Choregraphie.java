package fr.cabane.lutte;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * Les gestes du catch, image par image (repris des planches docs/catch/planche-prises et planche-combat) : les cinq
 * prises de finition, les cinq provocs, et le catcheur au tapis ou KO. Ce n'est pas une cinématique : chacun garde sa
 * caméra, les deux corps bougent pour de vrai (le client de chaque joueur le déplace lui-même ; le serveur déplace les
 * créatures), et tout le monde voit les poses.
 *
 * <p>Repère d'un geste : l'origine (les pieds de A au départ, ou la corde pour le 619), l'avant (vers B, ou vers
 * l'extérieur du ring), la droite. Une image donne, pour chacun, la hanche (avant f, droite r, hauteur u en blocs au-dessus
 * de l'origine ; debout, u = 0,75), le lacet (0 : il regarde vers l'avant ; en degrés, comme dans les maquettes), le
 * tangage (penché en avant) et le roulis du corps entier, puis les angles des os en degrés (conventions des maquettes :
 * rx négatif lève le bras devant). Une avance f au-delà de 50 se compte depuis B : f = distance de B + (f - 100).
 */
public final class Choregraphie {
    /** Pour les tests (captures) : CABANE_RALENTI=8 joue chaque geste 8 fois plus lentement. */
    public static final int RALENTI = ralenti();

    private static int ralenti() {
        try {
            return Math.max(1, Integer.parseInt(System.getenv().getOrDefault("CABANE_RALENTI", "1")));
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    /** La hanche d'un joueur debout, au-dessus de ses pieds (12 pixels). */
    public static final float HANCHE = 0.75F;
    /** La hanche d'un joueur allongé sur le tapis. */
    public static final float AU_SOL = 2.6F / 16F;

    public enum Geste {
        S619(22, 13, true), RKO(24, 15, false), AA(30, 24, false), CHOKESLAM(30, 23, false), SPEAR(22, 14, false),
        PROVOC_MYSTERIO(30, -1, false), PROVOC_VIPERE(34, -1, false), PROVOC_CHAMPION(30, -1, false), PROVOC_DEMON(34, -1, false),
        PROVOC_TAUREAU(30, -1, false),
        AU_TAPIS(24, -1, false), KO(100, -1, false), DEBOUT(1, -1, false);

        public final int duree;
        public final int impact;
        public final boolean cordes;
        final List<Image> a = new ArrayList<>();
        final List<Image> b = new ArrayList<>();

        Geste(int duree, int impact, boolean cordes) {
            this.duree = duree;
            this.impact = impact;
            this.cordes = cordes;
        }

        public boolean avecB() {
            return !b.isEmpty();
        }

        public boolean provoc() {
            return name().startsWith("PROVOC");
        }

        public boolean prise() {
            return ordinal() <= SPEAR.ordinal();
        }
    }

    /** Une pose : la hanche, l'orientation du corps et les angles des os (degrés, convention des maquettes). */
    public static final class Pose {
        public float t, f, r, u = HANCHE, lacet, tangage, roulis;
        public float[] corps = new float[3], tete = new float[3], brasD = new float[3], brasG = new float[3], jambeD = new float[3], jambeG = new float[3];

        Pose copie() {
            Pose p = new Pose();
            p.t = t; p.f = f; p.r = r; p.u = u; p.lacet = lacet; p.tangage = tangage; p.roulis = roulis;
            p.corps = corps.clone(); p.tete = tete.clone(); p.brasD = brasD.clone(); p.brasG = brasG.clone();
            p.jambeD = jambeD.clone(); p.jambeG = jambeG.clone();
            return p;
        }
    }

    /** Une image clé, construite à la chaîne. */
    static final class Image {
        final Pose p = new Pose();

        Image(float t) {
            p.t = t;
        }

        Image a(float f, float r) { p.f = f; p.r = r; return this; }
        Image u(float u) { p.u = u; return this; }
        Image lacet(float l) { p.lacet = l; return this; }
        Image tangage(float t) { p.tangage = t; return this; }
        Image roulis(float r) { p.roulis = r; return this; }
        Image corps(float... a) { p.corps = trois(a); return this; }
        Image tete(float... a) { p.tete = trois(a); return this; }
        Image brasD(float... a) { p.brasD = trois(a); return this; }
        Image brasG(float... a) { p.brasG = trois(a); return this; }
        Image jambeD(float... a) { p.jambeD = trois(a); return this; }
        Image jambeG(float... a) { p.jambeG = trois(a); return this; }
        Image bras(float rx, float rz) { brasD(rx, -rz); return brasG(rx, rz); }

        private static float[] trois(float[] a) {
            return new float[] {a.length > 0 ? a[0] : 0, a.length > 1 ? a[1] : 0, a.length > 2 ? a[2] : 0};
        }
    }

    private static Image i(int t) {
        return new Image(t);
    }

    private static void a(Geste g, Image... images) {
        g.a.addAll(List.of(images));
    }

    private static void b(Geste g, Image... images) {
        g.b.addAll(List.of(images));
    }

    static {
        // ---- le 619 (Mystério) : B est plié sur la corde du milieu, la tête dehors ; A court, passe entre les cordes
        // en tournant (le corps à l'horizontale, dehors) et lui met les deux pieds dans la tête. Repère : la corde, l'avant
        // vers l'extérieur du ring.
        Image drape = i(0).a(-0.53F, 0).corps(72).brasD(-72, -6).brasG(-72, 6).tete(-40);
        b(Geste.S619, drape, copie(drape, 4), copie(drape, 12), i(14).a(-0.53F, 0).corps(80).brasD(-82, -10).brasG(-82, 10).tete(-8),
                i(22).a(-0.6F, 0).corps(70).brasD(-70, -8).brasG(-70, 8).tete(-30));
        a(Geste.S619,
                i(0).a(-2.6F, -1.2F).lacet(0),
                i(7).a(-1.0F, -1.0F).tangage(16).jambeD(45).jambeG(-45).brasD(-50).brasG(40),
                i(10).a(0.62F, -1.4F).u(1.35F).lacet(-142).roulis(90).brasD(-175).brasG(-175).jambeD(-10).jambeG(10),
                i(13).a(0.57F, -0.9F).u(1.3F).lacet(-178).roulis(90).brasD(-175).brasG(-175),
                i(17).a(-0.9F, -1.0F).lacet(-180).brasD(-30, -10).brasG(-30, 10),
                i(22).a(-1.0F, -1.0F).lacet(-180));

        // ---- le RKO (Vipère) : il saute de nulle part, attrape la tête, et les deux tombent, B face au tapis
        a(Geste.RKO,
                i(0).a(0, 0),
                i(4).a(0.6F, 0).u(1.62F).tangage(22).brasD(-150, 8).brasG(-150, -8).jambeD(35).jambeG(12),
                i(8).a(1.0F, 0.1F).u(1.38F).tangage(-40).brasD(-120, 20).brasG(-90, -10).jambeD(-50).jambeG(-40),
                i(12).a(1.0F, 0.1F).u(0.6F).tangage(-55).brasD(-110, 20).brasG(-60).jambeD(-40).jambeG(-35),
                i(15).a(0.9F, 0.1F).u(AU_SOL).tangage(-60).brasD(-80, 20).brasG(-20).jambeD(-30).jambeG(-25),
                i(24).a(0.9F, 0.1F).u(AU_SOL).tangage(-50).brasD(-40, 20).brasG(-20).jambeD(-30).jambeG(-25));
        b(Geste.RKO,
                i(0).a(1.5F, 0).lacet(-180),
                i(6).a(1.55F, 0).lacet(-180).corps(20).brasD(-30, -10).brasG(-30, 10),
                i(8).a(1.6F, 0).lacet(-180).corps(42).brasD(-40).brasG(-40).tete(20),
                i(12).a(1.9F, 0).u(0.6F).lacet(-180).tangage(45).corps(30).brasD(-90).brasG(-90),
                i(15).a(2.2F, 0).u(AU_SOL).lacet(-180).tangage(90).brasD(-160).brasG(-160),
                i(24).a(2.2F, 0).u(AU_SOL).lacet(-180).tangage(90).brasD(-150).brasG(-160));

        // ---- le AA (Champion) : sur les épaules, puis il le fait basculer, à plat dos
        a(Geste.AA,
                i(0).a(0, 0),
                i(5).a(0.1F, 0).corps(35).brasD(-60).brasG(-60),
                i(10).a(0.1F, 0).brasD(-165, -6).brasG(-160, 10).tete(-8),
                i(17).a(0.1F, 0).brasD(-165, -6).brasG(-160, 10).tete(-8),
                i(21).a(0.2F, 0).corps(22).brasD(-80).brasG(-60),
                i(24).a(0.25F, 0).corps(15).brasD(-55, 10).brasG(-10),
                i(30).a(0.25F, 0).brasD(-55, 10).brasG(-10));
        b(Geste.AA,
                i(0).a(1.4F, 0).lacet(-180),
                i(5).a(1.0F, 0).lacet(-180).corps(15),
                i(10).a(0.1F, -0.38F).u(1.81F).lacet(-90).tangage(90).brasD(-90).brasG(-90),
                i(17).a(0.1F, -0.38F).u(1.81F).lacet(-90).tangage(90).brasD(-90).brasG(-90),
                i(21).a(0.55F, -0.2F).u(1.4F).lacet(-90).tangage(90).roulis(70).brasD(-150).brasG(-40),
                i(24).a(1.3F, 0.3F).u(AU_SOL).lacet(120).tangage(-90).brasD(-20, -40).brasG(-20, 40),
                i(30).a(1.3F, 0.3F).u(AU_SOL).lacet(120).tangage(-90).brasD(-20, -40).brasG(-20, 40));

        // ---- le chokeslam (Démon) : la main à la gorge, il le soulève très haut, et le plaque au tapis
        a(Geste.CHOKESLAM,
                i(0).a(0, 0),
                i(5).a(0, 0).brasD(-95, 6).brasG(-15, 10),
                i(14).a(0, 0).brasD(-176, 6).brasG(-30, 15).tete(-25),
                i(18).a(0, 0).brasD(-176, 6).brasG(-30, 15).tete(-25),
                i(23).a(0.1F, 0).corps(38).brasD(-58, 4).brasG(-20, 15),
                i(30).a(0.1F, 0).corps(20).brasD(-30, 4).brasG(-20, 15));
        b(Geste.CHOKESLAM,
                i(0).a(1.3F, 0).lacet(-180),
                i(5).a(1.15F, 0).u(0.84F).lacet(-180).brasD(-80, 12).brasG(-80, -12).tete(-22),
                i(14).a(0.3F, 0.45F).u(1.81F).lacet(-180).brasD(-150, -10).brasG(-60, 20).jambeD(-15).jambeG(10).tete(-15),
                i(18).a(0.3F, 0.45F).u(1.81F).lacet(-180).brasD(-140, -20).brasG(-70, 30).jambeD(10).jambeG(-12).tete(-15),
                i(23).a(1.3F, 0.3F).u(AU_SOL).lacet(-180).tangage(-90).brasD(-10, -40).brasG(-10, 40),
                i(30).a(1.3F, 0.3F).u(AU_SOL).lacet(-180).tangage(-90).brasD(-10, -40).brasG(-10, 40));

        // ---- le spear (Taureau) : en position, il charge à fond et le plie en deux, les deux au tapis
        a(Geste.SPEAR,
                i(0).a(0, 0).u(0.5F).corps(55).jambeD(-35, -12).jambeG(15, 12).brasD(-40).brasG(-75).tete(-45),
                i(3).a(0, 0).u(0.5F).corps(55).jambeD(-35, -12).jambeG(15, 12).brasD(-40).brasG(-75).tete(-45),
                i(6).a(98.8F, 0).u(0.56F).corps(55).jambeD(-50).jambeG(40).brasD(-20).brasG(-20).tete(-45),
                i(9).a(99.25F, 0).u(0.56F).corps(68).jambeD(-30).jambeG(35).brasD(-80, 20).brasG(-80, -20).tete(-45),
                i(14).a(99.8F, 0).u(0.38F).tangage(72).brasD(-100, 20).brasG(-100, -20).jambeD(20).jambeG(10),
                i(22).a(99.8F, 0).u(0.38F).tangage(72).brasD(-100, 20).brasG(-100, -20).jambeD(20).jambeG(10));
        b(Geste.SPEAR,
                i(0).a(100, 0).lacet(-180).corps(10),
                i(6).a(100, 0).lacet(-180).brasD(-50, -20).brasG(-50, 20),
                i(9).a(100.1F, 0).u(1.25F).lacet(-180).corps(70).tangage(-18).brasD(-120).brasG(-120).jambeD(-20).jambeG(-30),
                i(14).a(100.6F, 0).u(AU_SOL).lacet(-180).tangage(-90).brasD(-30, -50).brasG(-30, 50),
                i(22).a(100.6F, 0).u(AU_SOL).lacet(-180).tangage(-90).brasD(-30, -50).brasG(-30, 50));

        // ---- les provocs (A seul, sur place)
        a(Geste.PROVOC_MYSTERIO, i(0), i(6).brasD(-150, 28).brasG(-150, -28), i(14).u(0.8F).brasD(-155, 32).brasG(-155, -32),
                i(22).brasD(-150, 28).brasG(-150, -28), i(30));
        a(Geste.PROVOC_VIPERE,
                i(0),
                i(5).u(0.56F).corps(52).jambeD(-25, -10).jambeG(20, 10).brasD(-22).brasG(-115).tete(-45),
                i(10).u(0.56F).corps(52).jambeD(-25, -10).jambeG(20, 10).brasD(-90).brasG(-30).tete(-45),
                i(15).u(0.56F).corps(52).jambeD(-25, -10).jambeG(20, 10).brasD(-22).brasG(-115).tete(-45),
                i(20).u(0.56F).corps(52).jambeD(-25, -10).jambeG(20, 10).brasD(-90).brasG(-30).tete(-45),
                i(25).u(0.56F).corps(52).jambeD(-25, -10).jambeG(20, 10).brasD(-22).brasG(-115).tete(-45),
                i(34));
        a(Geste.PROVOC_CHAMPION, i(0), i(5).brasD(-148, 34).brasG(-10, 8).tete(0, 0, -10), i(10).brasD(-148, 18).brasG(-10, 8).tete(0, 0, -10),
                i(15).brasD(-148, 40).brasG(-10, 8).tete(0, 0, -10), i(20).brasD(-148, 18).brasG(-10, 8).tete(0, 0, -10),
                i(25).brasD(-148, 34).brasG(-10, 8).tete(0, 0, -10), i(30));
        a(Geste.PROVOC_DEMON, i(0), i(10).bras(-90, 20).tete(10), i(18).bras(-165, 30).tete(-15), i(28).bras(-165, 30).tete(-15), i(34));
        a(Geste.PROVOC_TAUREAU, i(0), i(4).corps(30).bras(-40, 5).jambeD(30), i(8).corps(30).bras(-40, 5).jambeD(-15),
                i(12).corps(30).bras(-40, 5).jambeD(30), i(16).corps(30).bras(-40, 5).jambeD(-15), i(20).corps(30).bras(-40, 5).jambeD(30),
                i(24).corps(30).bras(-40, 5).jambeD(-10), i(30));

        // ---- au tapis (une seconde) et KO (cinq secondes) : sur le dos, les bras en croix
        a(Geste.AU_TAPIS, i(0).u(AU_SOL).tangage(-90).brasD(-10, -50).brasG(-10, 50), i(17).u(AU_SOL).tangage(-90).brasD(-10, -50).brasG(-10, 50), i(24));
        a(Geste.KO, i(0).u(AU_SOL).tangage(-90).brasD(-10, -50).brasG(-10, 50), i(95).u(AU_SOL).tangage(-90).brasD(-10, -50).brasG(-10, 50), i(100));
        a(Geste.DEBOUT, i(0));
    }

    private static Image copie(Image im, int t) {
        Image c = new Image(t);
        Pose p = im.p.copie();
        p.t = t;
        c.p.f = p.f; c.p.r = p.r; c.p.u = p.u; c.p.lacet = p.lacet; c.p.tangage = p.tangage; c.p.roulis = p.roulis;
        c.p.corps = p.corps; c.p.tete = p.tete; c.p.brasD = p.brasD; c.p.brasG = p.brasG; c.p.jambeD = p.jambeD; c.p.jambeG = p.jambeG;
        return c;
    }

    // ------------------------------------------------------------------ lire un geste

    /**
     * La pose de A (b = false) ou de B (b = true) au temps t (en ticks, avec la fraction), pour une distance de B dB ; les
     * trois premiers ticks partent de là où il était vraiment (debut : f, r).
     */
    public static Pose pose(Geste g, boolean b, float t, float dB, float[] debut) {
        List<Image> l = b ? g.b : g.a;
        if (l.isEmpty()) {
            return null;
        }
        Pose p = interpoler(l, Mth.clamp(t, 0, g.duree), dB);
        if (debut != null && t < 3) {
            float k = lisse(t / 3F);
            p.f = Mth.lerp(k, debut[0], p.f);
            p.r = Mth.lerp(k, debut[1], p.r);
        }
        return p;
    }

    private static Pose interpoler(List<Image> l, float t, float dB) {
        Image a = l.get(0), b = a;
        for (Image im : l) {
            if (im.p.t <= t) {
                a = im;
            }
            if (im.p.t >= t) {
                b = im;
                break;
            }
            b = im;
        }
        float k = b.p.t > a.p.t ? lisse((t - a.p.t) / (b.p.t - a.p.t)) : 0;
        Pose p = new Pose(), x = a.p, y = b.p;
        p.t = t;
        p.f = Mth.lerp(k, avance(x.f, dB), avance(y.f, dB));
        p.r = Mth.lerp(k, x.r, y.r);
        p.u = Mth.lerp(k, x.u, y.u);
        p.lacet = Mth.lerp(k, x.lacet, y.lacet);
        p.tangage = Mth.lerp(k, x.tangage, y.tangage);
        p.roulis = Mth.lerp(k, x.roulis, y.roulis);
        p.corps = mix(x.corps, y.corps, k);
        p.tete = mix(x.tete, y.tete, k);
        p.brasD = mix(x.brasD, y.brasD, k);
        p.brasG = mix(x.brasG, y.brasG, k);
        p.jambeD = mix(x.jambeD, y.jambeD, k);
        p.jambeG = mix(x.jambeG, y.jambeG, k);
        return p;
    }

    private static float avance(float f, float dB) {
        return f > 50 ? dB + (f - 100) : f;
    }

    private static float[] mix(float[] a, float[] b, float k) {
        return new float[] {Mth.lerp(k, a[0], b[0]), Mth.lerp(k, a[1], b[1]), Mth.lerp(k, a[2], b[2])};
    }

    private static float lisse(float t) {
        t = Mth.clamp(t, 0, 1);
        return t * t * (3 - 2 * t);
    }

    // ------------------------------------------------------------------ le repère

    /** L'avant du repère (à plat) pour un lacet de Minecraft (en degrés). */
    public static Vec3 avant(float lacet) {
        float a = lacet * Mth.DEG_TO_RAD;
        return new Vec3(-Mth.sin(a), 0, Mth.cos(a));
    }

    /** Le lacet de Minecraft qui regarde dans la direction (x, z). */
    public static float lacetVers(double x, double z) {
        return (float) (Mth.atan2(-x, z) * Mth.RAD_TO_DEG);
    }

    /** La hanche (dans le monde) d'une pose, pour un repère (origine aux pieds, lacet de Minecraft). */
    public static Vec3 hanche(Vec3 origine, float lacet, Pose p) {
        Vec3 f = avant(lacet), d = new Vec3(-f.z, 0, f.x);
        return origine.add(f.scale(p.f)).add(d.scale(p.r)).add(0, p.u, 0);
    }

    /** Où mettre les pieds (la position de l'entité) : sous la hanche, jamais sous l'origine (le tapis). */
    public static Vec3 pieds(Vec3 origine, float lacet, Pose p) {
        Vec3 h = hanche(origine, lacet, p);
        return new Vec3(h.x, origine.y + Math.max(0, p.u - HANCHE), h.z);
    }

    /** Le lacet de Minecraft du corps : celui du repère, tourné du lacet de la pose (sens des maquettes). */
    public static float lacetCorps(float lacetRepere, Pose p) {
        return lacetRepere - p.lacet;
    }

    private Choregraphie() {
    }
}
