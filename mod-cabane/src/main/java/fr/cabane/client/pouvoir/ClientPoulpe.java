package fr.cabane.client.pouvoir;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import fr.cabane.Cabane;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * Les huit tentacules de poulpe de Hachibi 2 au niveau 4, d'après les maquettes validées (docs/hachibi2) : elles sortent
 * du bas de son dos en éventail, montent en grandes courbes en S et finissent enroulées ; roses, plus claires dessous,
 * avec deux rangées de ventouses. Tout est en pixels du modèle (pieds à y = 0, l'avant vers +z, sa gauche vers +x) ;
 * c'est à l'appelant de placer le repère (sur le corps du joueur, ou dans le monde).
 */
public final class ClientPoulpe {
    static final ResourceLocation BLANC = Cabane.id("textures/marque/blanc.png");
    private static final float[] PEAU = {178, 108, 124};
    private static final float[] DESSOUS = {222, 170, 176};
    private static final float[] VENTOUSE = {236, 226, 226};
    private static final float[] BORD = {204, 140, 152};

    /** Les huit : l'angle depuis le haut (vers sa gauche), le sens où la pointe s'enroule, la longueur, combien elle part en arrière. */
    static final float[][] TENTACULES = {
            {-16, 1, 88, -0.45F}, {16, -1, 88, -0.45F}, {-48, 1, 94, -0.3F}, {48, -1, 94, -0.3F}, {-86, -1, 90, -0.15F}, {86, 1, 90, -0.15F},
            {-128, -1, 80, -0.35F}, {128, 1, 80, -0.35F}};
    /** Celle qui fouette (clic gauche), celle qui balaie (clic droit). */
    static final int FOUET = 2;
    static final int BALAI = 3;

    private static Vec3 norme(Vec3 v) {
        double l = v.length();
        return l < 1e-9 ? new Vec3(0, 1, 0) : v.scale(1 / l);
    }

    /** v tourné d'un angle autour de l'axe n (Rodrigues). */
    private static Vec3 tourner(Vec3 v, Vec3 n, double a) {
        double c = Math.cos(a), s = Math.sin(a);
        return v.scale(c).add(n.cross(v).scale(s)).add(n.scale(n.dot(v) * (1 - c)));
    }

    /** La base d'une des huit, sur le bas de son dos. */
    static Vec3 base(int k) {
        double f = Math.toRadians(TENTACULES[k][0]);
        return new Vec3(Math.sin(f) * 2.4, 13 + Math.cos(f) * 1.4, -2.4);
    }

    /** Les huit au repos (k : de 0 à 1 pendant qu'elles poussent ; sauf : un bit par tentacule occupée, dessinée à part). */
    static void toutes(VertexConsumer c, PoseStack.Pose p, int lumiere, double t, float k, int sauf) {
        for (int i = 0; i < TENTACULES.length; i++) {
            if ((sauf >> i & 1) != 0) {
                continue;
            }
            float[] d = TENTACULES[i];
            double f = Math.toRadians(d[0]);
            Vec3 d0 = norme(new Vec3(Math.sin(f), Math.cos(f), d[3]));
            Vec3 axe = norme(new Vec3(0.35 * Math.cos(i * 1.7), 0.35 * Math.sin(i * 2.3), d[1]));
            double onde = 0.003 * Math.sin(t * 1.6 + i * 0.8);
            tentacule(c, p, lumiere, base(i), d0, axe, d[2] * k, 4.8 * Math.min(1, 0.3 + k), 0.0015 + onde, 0.11, 0.018, i * 1.3 + t * 1.2);
        }
    }

    /**
     * Une tentacule : un tube qui part de base dans la direction d0 et s'enroule autour de axe, de plus en plus vers la
     * pointe (c0 + c1 × s^2,5, plus une vague), en s'affinant ; son côté intérieur, plus clair, porte les ventouses.
     */
    static void tentacule(VertexConsumer c, PoseStack.Pose p, int lumiere, Vec3 base, Vec3 d0, Vec3 axe, double longueur, double r0, double c0,
            double c1, double vague, double phase) {
        if (longueur < 0.5) {
            return;
        }
        int n = 40, m = 10;
        double pas = longueur / n;
        Vec3[] pt = new Vec3[n + 1], nn = new Vec3[n + 1], bb = new Vec3[n + 1];
        double[] r = new double[n + 1];
        Vec3 pos = base, d = norme(d0);
        for (int i = 0; i <= n; i++) {
            double s = i / (double) n;
            Vec3 cn = axe.cross(d);
            nn[i] = cn.lengthSqr() < 1e-9 ? norme(d.cross(new Vec3(1, 0, 0))) : norme(cn);
            bb[i] = norme(d.cross(nn[i]));
            pt[i] = pos;
            r[i] = r0 * (1 - 0.86 * Math.pow(s, 0.85)) + 0.2;
            d = norme(tourner(d, axe, (c0 + c1 * Math.pow(s, 2.5) + vague * Math.sin(2 * Math.PI * s * 1.2 + phase)) * pas));
            pos = pos.add(d.scale(pas));
        }
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < m; j++) {
                double t0 = 2 * Math.PI * j / m, t1 = 2 * Math.PI * (j + 1) / m, tm = (t0 + t1) / 2;
                Vec3 normale = nn[i].scale(Math.cos(tm)).add(bb[i].scale(Math.sin(tm)));
                // le dessous (vers l'intérieur de l'enroulement) est plus clair
                float dessous = (float) Math.pow(Math.max(0, Math.cos(tm)), 1.5);
                int couleur = couleur(PEAU, DESSOUS, dessous, 1);
                sommet(c, p, sur(pt[i], nn[i], bb[i], r[i], t0), normale, couleur, lumiere);
                sommet(c, p, sur(pt[i + 1], nn[i + 1], bb[i + 1], r[i + 1], t0), normale, couleur, lumiere);
                sommet(c, p, sur(pt[i + 1], nn[i + 1], bb[i + 1], r[i + 1], t1), normale, couleur, lumiere);
                sommet(c, p, sur(pt[i], nn[i], bb[i], r[i], t1), normale, couleur, lumiere);
            }
            // les ventouses : deux rangées sur le dessous
            if (i % 3 == 1 && i < n * 0.94) {
                for (double dt : new double[]{-0.42, 0.42}) {
                    Vec3 nv = norme(nn[i].scale(Math.cos(dt)).add(bb[i].scale(Math.sin(dt))));
                    Vec3 t2 = norme(nv.cross(bb[i])), u2 = norme(nv.cross(t2));
                    double rv = Math.max(0.35, r[i] * 0.5);
                    disque(c, p, lumiere, pt[i].add(nv.scale(r[i] + 0.08)), nv, t2, u2, rv, couleur(BORD, BORD, 0, 1));
                    disque(c, p, lumiere, pt[i].add(nv.scale(r[i] + 0.14)), nv, t2, u2, rv * 0.62, couleur(VENTOUSE, VENTOUSE, 0, 1));
                }
            }
        }
    }

    private static Vec3 sur(Vec3 o, Vec3 n, Vec3 b, double r, double th) {
        return o.add(n.scale(Math.cos(th) * r)).add(b.scale(Math.sin(th) * r));
    }

    private static void disque(VertexConsumer c, PoseStack.Pose p, int lumiere, Vec3 centre, Vec3 n, Vec3 t, Vec3 u, double r, int couleur) {
        for (int k = 0; k < 6; k++) {
            double a0 = 2 * Math.PI * k / 6, a1 = 2 * Math.PI * (k + 1) / 6;
            Vec3 q0 = centre.add(t.scale(Math.cos(a0) * r)).add(u.scale(Math.sin(a0) * r));
            Vec3 q1 = centre.add(t.scale(Math.cos(a1) * r)).add(u.scale(Math.sin(a1) * r));
            sommet(c, p, centre, n, couleur, lumiere);
            sommet(c, p, q0, n, couleur, lumiere);
            sommet(c, p, q1, n, couleur, lumiere);
            sommet(c, p, centre, n, couleur, lumiere);
        }
    }

    private static int couleur(float[] a, float[] b, float k, float lum) {
        int r = Math.round(Mth.lerp(k, a[0], b[0]) * lum), g = Math.round(Mth.lerp(k, a[1], b[1]) * lum), bl = Math.round(Mth.lerp(k, a[2], b[2]) * lum);
        return 0xFF000000 | Math.min(255, r) << 16 | Math.min(255, g) << 8 | Math.min(255, bl);
    }

    private static void sommet(VertexConsumer c, PoseStack.Pose p, Vec3 v, Vec3 n, int couleur, int lumiere) {
        c.addVertex(p, (float) v.x, (float) v.y, (float) v.z).setColor(couleur).setUv(0.5F, 0.5F).setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(lumiere).setNormal(p, (float) n.x, (float) n.y, (float) n.z);
    }

    // ================================================================ le fouet et le balayage, dans le monde

    /** Le fouet dure ce temps (aller et retour), le balayage celui-là. */
    static final double DUREE_FOUET = 0.45;
    static final double DUREE_BALAYAGE = 0.5;

    /**
     * Le fouet : la tentacule part de son dos, file tout droit vers sa cible (ou devant lui, à 9 blocs) et revient. vers
     * est la cible, dans le repère du corps (en pixels du modèle). tau de 0 à 1.
     */
    static void fouet(VertexConsumer c, PoseStack.Pose p, int lumiere, Vec3 vers, double tau) {
        Vec3 base = base(FOUET);
        Vec3 d = vers.subtract(base);
        double loin = d.length();
        double sort = Math.sin(Math.PI * Mth.clamp(tau, 0, 1));
        tentacule(c, p, lumiere, base, norme(d.add(0, loin * 0.06, 0)), norme(new Vec3(1, 0.2, 0)), loin * sort, 4.8, -0.0015, 0.05, 0.004, 0);
    }

    /** Le balayage : une tentacule plus grosse balaie un grand arc devant lui, de sa droite à sa gauche. tau de 0 à 1. */
    static void balayage(VertexConsumer c, PoseStack.Pose p, int lumiere, double tau) {
        double k = Mth.clamp(tau, 0, 1);
        double a = Math.toRadians(-110 + 220 * (k * k * (3 - 2 * k)));
        Vec3 d0 = norme(new Vec3(Math.sin(a), 0.06, Math.cos(a)));
        double sort = Math.min(1, Math.sin(Math.PI * k) * 1.6);
        tentacule(c, p, lumiere, base(BALAI), d0, norme(new Vec3(0, 1, 0.1)), 100 * sort, 5.6, 0.012, 0.05, 0.004, 0);
    }

    /** Celle qui attrape (accroupi + clic droit). */
    static final int PRISE = 4;

    /**
     * La saisie : la tentacule part de son dos, file jusqu'à sa prise en arc, puis s'enroule deux tours et demi autour
     * d'elle, de haut en bas ; elle se resserre quand elle la serre. vers est le centre de la prise (dans le repère du
     * corps), tau de 0 à 1 pendant qu'elle l'attrape, serre de 0 à 1 (un coup de serrage). base : d'où elle part
     * (base(PRISE), sur son dos ; à la première personne, d'en bas à droite de la vue).
     */
    static void saisie(VertexConsumer c, PoseStack.Pose p, int lumiere, Vec3 base, Vec3 vers, double tau, double serre) {
        double k = Mth.clamp(tau, 0, 1);
        double rayon = 8.5 * (1 - 0.35 * serre), haut = 13;
        // l'entrée de la spirale, au-dessus de la prise, côté joueur
        Vec3 versBase = new Vec3(base.x - vers.x, 0, base.z - vers.z);
        double a0 = Math.atan2(versBase.z, versBase.x);
        Vec3 entree = vers.add(Math.cos(a0) * rayon, haut / 2, Math.sin(a0) * rayon);
        java.util.List<Vec3> ligne = new java.util.ArrayList<>();
        Vec3 controle = base.lerp(entree, 0.5).add(0, 18, 0);
        int n1 = 24;
        for (int i = 0; i <= n1; i++) {
            double t = i / (double) n1;
            ligne.add(base.scale((1 - t) * (1 - t)).add(controle.scale(2 * (1 - t) * t)).add(entree.scale(t * t)));
        }
        int n2 = 40;
        for (int i = 1; i <= n2; i++) {
            double t = i / (double) n2;
            double a = a0 + t * 2.5 * 2 * Math.PI;
            ligne.add(vers.add(Math.cos(a) * rayon, haut / 2 - t * haut, Math.sin(a) * rayon));
        }
        // pendant qu'elle part, on n'en voit que le début
        int garde = Math.max(2, (int) Math.round(ligne.size() * k));
        tube(c, p, lumiere, ligne.subList(0, garde), 4.8, 1.6);
    }

    /** Un tube le long d'une ligne quelconque, de rayon r0 au départ à r1 au bout (repère transporté le long de la ligne). */
    private static void tube(VertexConsumer c, PoseStack.Pose p, int lumiere, java.util.List<Vec3> ligne, double r0, double r1) {
        int n = ligne.size() - 1, m = 10;
        Vec3[] nn = new Vec3[n + 1], bb = new Vec3[n + 1];
        Vec3 normal = null;
        for (int i = 0; i <= n; i++) {
            Vec3 t = norme(ligne.get(Math.min(n, i + 1)).subtract(ligne.get(Math.max(0, i - 1))));
            if (normal == null) {
                normal = t.cross(new Vec3(0, 1, 0));
                if (normal.lengthSqr() < 1e-6) {
                    normal = t.cross(new Vec3(1, 0, 0));
                }
            }
            normal = norme(normal.subtract(t.scale(normal.dot(t))));
            nn[i] = normal;
            bb[i] = norme(t.cross(normal));
        }
        for (int i = 0; i < n; i++) {
            double ra = r0 + (r1 - r0) * i / n, rb = r0 + (r1 - r0) * (i + 1) / n;
            for (int j = 0; j < m; j++) {
                double t0 = 2 * Math.PI * j / m, t1 = 2 * Math.PI * (j + 1) / m, tm = (t0 + t1) / 2;
                Vec3 normale = nn[i].scale(Math.cos(tm)).add(bb[i].scale(Math.sin(tm)));
                int couleur = couleur(PEAU, DESSOUS, (float) Math.pow(Math.max(0, Math.cos(tm)), 1.5), 1);
                sommet(c, p, sur(ligne.get(i), nn[i], bb[i], ra, t0), normale, couleur, lumiere);
                sommet(c, p, sur(ligne.get(i + 1), nn[i + 1], bb[i + 1], rb, t0), normale, couleur, lumiere);
                sommet(c, p, sur(ligne.get(i + 1), nn[i + 1], bb[i + 1], rb, t1), normale, couleur, lumiere);
                sommet(c, p, sur(ligne.get(i), nn[i], bb[i], ra, t1), normale, couleur, lumiere);
            }
            if (i % 3 == 1) {
                Vec3 t2 = norme(nn[i].cross(bb[i])), u2 = norme(nn[i].cross(t2));
                disque(c, p, lumiere, ligne.get(i).add(nn[i].scale(ra + 0.08)), nn[i], t2, u2, Math.max(0.35, ra * 0.45), couleur(BORD, BORD, 0, 1));
                disque(c, p, lumiere, ligne.get(i).add(nn[i].scale(ra + 0.14)), nn[i], t2, u2, Math.max(0.22, ra * 0.28), couleur(VENTOUSE, VENTOUSE, 0, 1));
            }
        }
    }

    static RenderType type() {
        return RenderType.entityCutoutNoCull(BLANC);
    }

    static VertexConsumer tampon(MultiBufferSource buffers) {
        return buffers.getBuffer(type());
    }

    private ClientPoulpe() {
    }
}
