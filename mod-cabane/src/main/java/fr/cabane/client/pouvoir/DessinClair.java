package fr.cabane.client.pouvoir;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Les briques de dessin des Ténèbres et de la Lumière ({@link ClientTenebres}, {@link ClientLumiere}) : des traits de
 * lumière (qui s'ajoutent à ce qui est derrière), des faces pleines ou transparentes, des boules, des anneaux, les
 * modèles tirés des planches, et le « pantin » : un joueur fait de six pavés d'une couleur (son ombre, les trois ombres,
 * le géant d'ombre, l'ange de lumière), posé comme le modèle du joueur et vu même en première personne.
 * Tout est en coordonnées du monde ; {@code cam} est la position de la caméra.
 */
final class DessinClair {
    private DessinClair() {
    }

    static VertexConsumer lueur(MultiBufferSource b) {
        return b.getBuffer(RenderType.lightning());
    }

    static VertexConsumer plein(MultiBufferSource b) {
        return b.getBuffer(RenderType.entityCutoutNoCull(ModeleVent.BLANC));
    }

    static VertexConsumer voile(MultiBufferSource b) {
        return b.getBuffer(RenderType.entityTranslucentEmissive(ModeleVent.BLANC));
    }

    // ---------------------------------------------------------------- les traits de lumière

    /** Un trait de lumière de a à b, large de w (blocs), tourné vers la caméra. */
    static void trait(PoseStack ps, VertexConsumer c, Vec3 cam, Vec3 a, Vec3 b, double w, float r, float g, float bl, float al) {
        Vec3 ab = b.subtract(a);
        Vec3 vers = a.add(b).scale(0.5).subtract(cam);
        Vec3 cote = ab.cross(vers);
        if (cote.lengthSqr() < 1e-9) {
            return;
        }
        cote = cote.normalize().scale(w / 2);
        Matrix4f m = ps.last().pose();
        Vec3 p0 = a.subtract(cote).subtract(cam), p1 = a.add(cote).subtract(cam), p2 = b.add(cote).subtract(cam), p3 = b.subtract(cote).subtract(cam);
        c.addVertex(m, (float) p0.x, (float) p0.y, (float) p0.z).setColor(r, g, bl, al);
        c.addVertex(m, (float) p1.x, (float) p1.y, (float) p1.z).setColor(r, g, bl, al);
        c.addVertex(m, (float) p2.x, (float) p2.y, (float) p2.z).setColor(r, g, bl, al);
        c.addVertex(m, (float) p3.x, (float) p3.y, (float) p3.z).setColor(r, g, bl, al);
        // l autre face (le rendu cache les faces vues de dos)
        c.addVertex(m, (float) p3.x, (float) p3.y, (float) p3.z).setColor(r, g, bl, al);
        c.addVertex(m, (float) p2.x, (float) p2.y, (float) p2.z).setColor(r, g, bl, al);
        c.addVertex(m, (float) p1.x, (float) p1.y, (float) p1.z).setColor(r, g, bl, al);
        c.addVertex(m, (float) p0.x, (float) p0.y, (float) p0.z).setColor(r, g, bl, al);
    }

    /** Un trait qui brille, avec son halo : un cœur blanc, puis des couches de plus en plus larges et pâles (ça éclate). */
    static void rayon(PoseStack ps, MultiBufferSource b, Vec3 cam, Vec3 a, Vec3 z, double w, float r, float g, float bl, float al) {
        VertexConsumer c = lueur(b);
        trait(ps, c, cam, a, z, w * 8, r, g, bl, al * 0.07F);
        trait(ps, c, cam, a, z, w * 4.5, r, g, bl, al * 0.14F);
        trait(ps, c, cam, a, z, w * 2.4, r, g, bl, al * 0.3F);
        trait(ps, c, cam, a, z, w * 1.3, Math.min(1, r + 0.3F), Math.min(1, g + 0.3F), Math.min(1, bl + 0.3F), al * 0.75F);
        trait(ps, c, cam, a, z, w * 0.6, 1, 1, 1, al);
    }

    /**
     * Un halo : un disque tourné vers la caméra, qui brille fort au milieu et s'efface vers le bord (comme l'éclat d'une
     * lumière). Il s'ajoute à ce qui est derrière.
     */
    static void halo(PoseStack ps, VertexConsumer c, Vec3 cam, Vec3 centre, double rayon, float r, float g, float bl, float al) {
        Vec3 o = cam.subtract(centre);
        if (o.lengthSqr() < 1e-6) {
            return;
        }
        o = o.normalize();
        Vec3 e1 = o.cross(Math.abs(o.y) > 0.95 ? new Vec3(1, 0, 0) : new Vec3(0, 1, 0)).normalize(), e2 = e1.cross(o).normalize();
        Matrix4f m = ps.last().pose();
        int n = 24;
        Vec3 cc = centre.subtract(cam);
        for (int i = 0; i < n; i++) {
            double a0 = i * Mth.TWO_PI / n, a1 = (i + 1) * Mth.TWO_PI / n;
            Vec3 p0 = cc.add(e1.scale(Math.cos(a0) * rayon)).add(e2.scale(Math.sin(a0) * rayon));
            Vec3 p1 = cc.add(e1.scale(Math.cos(a1) * rayon)).add(e2.scale(Math.sin(a1) * rayon));
            for (int k = 0; k < 2; k++) {
                Vec3 x = k == 0 ? p0 : p1, y = k == 0 ? p1 : p0;
                c.addVertex(m, (float) cc.x, (float) cc.y, (float) cc.z).setColor(r, g, bl, al);
                c.addVertex(m, (float) cc.x, (float) cc.y, (float) cc.z).setColor(r, g, bl, al);
                c.addVertex(m, (float) x.x, (float) x.y, (float) x.z).setColor(r, g, bl, 0);
                c.addVertex(m, (float) y.x, (float) y.y, (float) y.z).setColor(r, g, bl, 0);
            }
        }
    }

    /** Un éclat : de la lueur pure, en couches (un grand halo doré très doux, un plus vif, un cœur blanc qui brûle). */
    static void eclat(PoseStack ps, MultiBufferSource b, Vec3 cam, Vec3 centre, double rayon, float r, float g, float bl, float al, double tourne) {
        VertexConsumer c = lueur(b);
        halo(ps, c, cam, centre, rayon * 3.2, r, g, bl, al * 0.22F);
        halo(ps, c, cam, centre, rayon * 1.8, r, g, bl, al * 0.45F);
        halo(ps, c, cam, centre, rayon * 0.9, Math.min(1, r + 0.2F), Math.min(1, g + 0.2F), Math.min(1, bl + 0.3F), al * 0.8F);
        halo(ps, c, cam, centre, rayon * 0.45, 1, 1, 1, al);
    }

    /**
     * Une explosion de lumière, t secondes après l'impact : une boule blanche qui gonfle d'un coup et s'efface, sa lueur
     * dorée, une onde au sol, un pilier bref. taille : à peu près son rayon en blocs.
     */
    static void explosion(PoseStack ps, MultiBufferSource b, Vec3 cam, Vec3 centre, double t, double taille, float r, float g, float bl) {
        if (t < 0 || t > 1.4) {
            return;
        }
        float al = (float) (1 - lisse01((t - 0.12) / 1.2));
        double gonfle = lisse01(t / 0.18);
        VertexConsumer v = voile(b), c = lueur(b);
        boule(ps, v, cam, centre, taille * (0.4 + 0.9 * gonfle), (int) (200 * Math.max(0, 1 - t / 0.5)) << 24 | 0xFFFFF4, 0xF000F0);
        boule(ps, v, cam, centre, taille * (0.6 + 1.6 * gonfle), (int) (90 * Math.max(0, 1 - t / 0.8)) << 24 | 0xFFE08A, 0xF000F0);
        eclat(ps, b, cam, centre, taille * (0.8 + 0.8 * gonfle), r, g, bl, al, 0);
        // l'onde au sol, et une deuxième plus lente
        Vec3 sol = centre.add(0, -Math.min(taille * 0.9, 1.5), 0);
        for (int k = 0; k < 2; k++) {
            double tt = t - k * 0.12;
            if (tt > 0) {
                double ro = taille * (0.6 + 4.5 * lisse01(tt / 0.6));
                float ao = (float) (1 - lisse01(tt / 1.0));
                anneauSol(ps, c, cam, sol.add(0, 0.06, 0), ro, taille * 0.45, r, g, bl, ao * 0.35F);
                anneauSol(ps, c, cam, sol.add(0, 0.07, 0), ro, taille * 0.14, 1, 1, 1, ao * 0.8F);
            }
        }
        // un pilier de lumière bref
        if (t < 0.5) {
            float ap = (float) (1 - t / 0.5);
            rayon(ps, b, cam, sol, sol.add(0, taille * 5 * lisse01(t / 0.1), 0), taille * 0.35, r, g, bl, ap);
        }
    }

    private static double lisse01(double x) {
        x = Mth.clamp(x, 0, 1);
        return x * x * (3 - 2 * x);
    }

    /** Un anneau plat qui brille, dans le plan xz du repère courant (en pixels : centre, rayon, largeur). */
    static void anneauModele(PoseStack ps, VertexConsumer c, float y, float rayon, float w, float r, float g, float bl, float al) {
        Matrix4f m = ps.last().pose();
        int n = 40;
        for (int i = 0; i < n; i++) {
            double a0 = i * Mth.TWO_PI / n, a1 = (i + 1) * Mth.TWO_PI / n;
            float[][] q = new float[4][];
            q[0] = new float[]{(float) Math.cos(a0) * (rayon - w / 2), (float) Math.sin(a0) * (rayon - w / 2)};
            q[1] = new float[]{(float) Math.cos(a0) * (rayon + w / 2), (float) Math.sin(a0) * (rayon + w / 2)};
            q[2] = new float[]{(float) Math.cos(a1) * (rayon + w / 2), (float) Math.sin(a1) * (rayon + w / 2)};
            q[3] = new float[]{(float) Math.cos(a1) * (rayon - w / 2), (float) Math.sin(a1) * (rayon - w / 2)};
            for (int k = 0; k < 8; k++) {
                float[] p = q[k < 4 ? k : 7 - k];
                // le bord intérieur et le bord extérieur s'effacent : seul le milieu de l'anneau brille fort
                float a = (k < 4 ? k : 7 - k) == 0 || (k < 4 ? k : 7 - k) == 3 ? al * 0.6F : al * 0.6F;
                c.addVertex(m, p[0] / 16, y / 16, p[1] / 16).setColor(r, g, bl, a);
            }
        }
    }

    /** Une ligne brisée qui brille (un éclair, une spirale), point par point. */
    static void ligne(PoseStack ps, VertexConsumer c, Vec3 cam, Vec3[] pts, double w, float r, float g, float bl, float al) {
        for (int i = 0; i + 1 < pts.length; i++) {
            trait(ps, c, cam, pts[i], pts[i + 1], w, r, g, bl, al);
        }
    }

    /** Un anneau qui brille, dans le plan (u, v), autour de c. */
    static void anneau(PoseStack ps, VertexConsumer c, Vec3 cam, Vec3 centre, double rayon, double w, Vec3 u, Vec3 v, float r, float g, float bl, float al) {
        int n = Mth.clamp((int) (rayon * 6), 16, 96);
        Vec3 prec = centre.add(u.scale(rayon));
        for (int i = 1; i <= n; i++) {
            double a = i * Mth.TWO_PI / n;
            Vec3 p = centre.add(u.scale(Math.cos(a) * rayon)).add(v.scale(Math.sin(a) * rayon));
            trait(ps, c, cam, prec, p, w, r, g, bl, al);
            prec = p;
        }
    }

    /** Un anneau au sol, plat (une bande dans le plan horizontal, qui brille). */
    static void anneauSol(PoseStack ps, VertexConsumer c, Vec3 cam, Vec3 centre, double rayon, double w, float r, float g, float bl, float al) {
        int n = Mth.clamp((int) (rayon * 6), 16, 120);
        Matrix4f m = ps.last().pose();
        for (int i = 0; i < n; i++) {
            double a0 = i * Mth.TWO_PI / n, a1 = (i + 1) * Mth.TWO_PI / n;
            double r0 = rayon - w / 2, r1 = rayon + w / 2;
            Vec3[] q = {
                    centre.add(Math.cos(a0) * r0, 0, Math.sin(a0) * r0), centre.add(Math.cos(a0) * r1, 0, Math.sin(a0) * r1),
                    centre.add(Math.cos(a1) * r1, 0, Math.sin(a1) * r1), centre.add(Math.cos(a1) * r0, 0, Math.sin(a1) * r0)};
            for (int k = 0; k < 8; k++) {
                Vec3 d = q[k < 4 ? k : 7 - k].subtract(cam);
                c.addVertex(m, (float) d.x, (float) d.y, (float) d.z).setColor(r, g, bl, al);
            }
        }
    }

    /** Un disque au sol (plein ou transparent), de couleur argb. */
    static void disque(PoseStack ps, VertexConsumer c, Vec3 cam, Vec3 centre, double rayon, int argb, boolean brille) {
        int n = Mth.clamp((int) (rayon * 8), 16, 96);
        for (int i = 0; i < n; i++) {
            double a0 = i * Mth.TWO_PI / n, a1 = (i + 1) * Mth.TWO_PI / n;
            face(ps, c, cam, centre, centre, centre.add(Math.cos(a1) * rayon, 0, Math.sin(a1) * rayon), centre.add(Math.cos(a0) * rayon, 0, Math.sin(a0) * rayon),
                    new Vec3(0, 1, 0), argb, brille ? LightTexture.FULL_BRIGHT : 0xF000F0);
        }
    }

    // ---------------------------------------------------------------- les faces pleines

    /** Une face de 4 coins (du monde), de couleur argb (l'alpha sert pour les voiles). */
    static void face(PoseStack ps, VertexConsumer c, Vec3 cam, Vec3 a, Vec3 b, Vec3 cc, Vec3 d, Vec3 n, int argb, int lumiere) {
        PoseStack.Pose pose = ps.last();
        int al = argb >>> 24, r = argb >> 16 & 255, g = argb >> 8 & 255, bl = argb & 255;
        for (Vec3 p : new Vec3[]{a, b, cc, d, d, cc, b, a}) {
            Vec3 q = p.subtract(cam);
            c.addVertex(pose, (float) q.x, (float) q.y, (float) q.z).setColor(r, g, bl, al).setUv(0.5F, 0.5F)
                    .setOverlay(OverlayTexture.NO_OVERLAY).setLight(lumiere).setNormal(pose, (float) n.x, (float) n.y, (float) n.z);
        }
    }

    /** Un cube droit de côté s autour de c (un éclat, un barreau, un maillon), un peu ombré sur les côtés. */
    static void cube(PoseStack ps, VertexConsumer c, Vec3 cam, Vec3 centre, double s, int argb, int lumiere) {
        double h = s / 2;
        Vec3 o = centre;
        Vec3[] P = new Vec3[8];
        for (int i = 0; i < 8; i++) {
            P[i] = o.add((i & 1) == 0 ? -h : h, (i & 2) == 0 ? -h : h, (i & 4) == 0 ? -h : h);
        }
        int sombre = ombrer(argb, 0.75F), moyen = ombrer(argb, 0.88F);
        face(ps, c, cam, P[2], P[3], P[7], P[6], new Vec3(0, 1, 0), argb, lumiere);
        face(ps, c, cam, P[0], P[4], P[5], P[1], new Vec3(0, -1, 0), sombre, lumiere);
        face(ps, c, cam, P[0], P[1], P[3], P[2], new Vec3(0, 0, -1), moyen, lumiere);
        face(ps, c, cam, P[4], P[6], P[7], P[5], new Vec3(0, 0, 1), moyen, lumiere);
        face(ps, c, cam, P[0], P[2], P[6], P[4], new Vec3(-1, 0, 0), sombre, lumiere);
        face(ps, c, cam, P[1], P[5], P[7], P[3], new Vec3(1, 0, 0), sombre, lumiere);
    }

    static int ombrer(int argb, float k) {
        return argb & 0xFF000000 | (int) ((argb >> 16 & 255) * k) << 16 | (int) ((argb >> 8 & 255) * k) << 8 | (int) ((argb & 255) * k);
    }

    /** Une boule (en tranches) de rayon r, pleine (alpha 255) ou transparente. */
    static void boule(PoseStack ps, VertexConsumer c, Vec3 cam, Vec3 centre, double rayon, int argb, int lumiere) {
        int n = Mth.clamp((int) (rayon * 4) + 8, 10, 28);
        for (int i = 0; i < n; i++) {
            double t0 = Math.PI * i / n, t1 = Math.PI * (i + 1) / n;
            for (int j = 0; j < n * 2; j++) {
                double p0 = Math.PI * j / n, p1 = Math.PI * (j + 1) / n;
                Vec3 a = sph(centre, rayon, t0, p0), b = sph(centre, rayon, t0, p1), cc = sph(centre, rayon, t1, p1), d = sph(centre, rayon, t1, p0);
                face(ps, c, cam, a, b, cc, d, sph(Vec3.ZERO, 1, (t0 + t1) / 2, (p0 + p1) / 2), argb, lumiere);
            }
        }
    }

    private static Vec3 sph(Vec3 c, double r, double t, double p) {
        return c.add(r * Math.sin(t) * Math.cos(p), r * Math.cos(t), r * Math.sin(t) * Math.sin(p));
    }

    // ---------------------------------------------------------------- les modèles des planches

    /** Un modèle libre (dossier/nom), posé en p, tourné de rot, à l'échelle e (1/16 : la taille des planches). */
    static void modele(PoseStack ps, MultiBufferSource b, Vec3 cam, String dossier, String nom, Vec3 p, Quaternionf rot, float e, int lumiere, float alpha) {
        ModeleVent[] l = ModeleVent.lire(dossier, nom);
        if (l.length == 0 || alpha <= 0.01F) {
            return;
        }
        ps.pushPose();
        ps.translate(p.x - cam.x, p.y - cam.y, p.z - cam.z);
        ps.mulPose(rot);
        l[0].dessiner(b, ps.last(), lumiere, e, Math.min(1, alpha));
        ps.popPose();
    }

    /** La rotation qui tourne +z vers dir (et garde +y vers le haut autant que possible). */
    static Quaternionf vers(Vec3 dir) {
        Vector3f d = new Vector3f((float) dir.x, (float) dir.y, (float) dir.z);
        if (d.lengthSquared() < 1e-8) {
            return new Quaternionf();
        }
        d.normalize();
        float lacet = (float) Math.atan2(d.x, d.z), tangage = (float) -Math.asin(Mth.clamp(d.y, -1, 1));
        return new Quaternionf().rotateY(lacet).rotateX(tangage);
    }

    // ---------------------------------------------------------------- le pantin

    /**
     * La pose d'un pantin (en radians, comme PlayerModel : xRot négatif = le bras vers l'avant, puis vers le haut ; zRot du
     * bras droit négatif = vers l'extérieur).
     */
    static final class Pose {
        float teteX, brasDX, brasDZ, brasGX, brasGZ, jambeDX, jambeGX, penche;

        Pose bras(float dx, float dz, float gx, float gz) {
            brasDX = dx;
            brasDZ = dz;
            brasGX = gx;
            brasGZ = gz;
            return this;
        }

        Pose jambes(float d, float g) {
            jambeDX = d;
            jambeGX = g;
            return this;
        }

        Pose tete(float x) {
            teteX = x;
            return this;
        }

        Pose penche(float x) {
            penche = x;
            return this;
        }
    }

    /** Les pavés du joueur (comme PlayerModel, bras larges) : le pivot, puis le coin et la taille, en pixels. */
    private static final float[][] PARTIES = {
            {0, 0, 0, -4, -8, -4, 8, 8, 8},
            {0, 0, 0, -4, 0, -2, 8, 12, 4},
            {-5, 2, 0, -3, -2, -2, 4, 12, 4},
            {5, 2, 0, -1, -2, -2, 4, 12, 4},
            {-1.9F, 12, 0, -2, 0, -2, 4, 12, 4},
            {1.9F, 12, 0, -2, 0, -2, 4, 12, 4}};

    /**
     * Prépare le repère du pantin dans ps (pieds en {@code pieds}, tourné vers lacet en degrés comme yBodyRot, à la taille
     * k) : ensuite les coordonnées sont celles de PlayerModel, en pixels (y vers le bas, l'avant vers -z).
     */
    static void repere(PoseStack ps, Vec3 cam, Vec3 pieds, float lacet, float k, float penche) {
        ps.translate(pieds.x - cam.x, pieds.y - cam.y, pieds.z - cam.z);
        ps.mulPose(Axis.YP.rotationDegrees(180 - lacet));
        ps.scale(-k * 0.9375F, -k * 0.9375F, k * 0.9375F);
        ps.translate(0, -1.501F, 0);
        if (penche != 0) {
            ps.translate(0, 0.75F, 0);
            ps.mulPose(Axis.XP.rotation(penche));
            ps.translate(0, -0.75F, 0);
        }
    }

    /** Une partie du pantin : on se place à son pivot, tourné (le repère du pantin déjà dans ps). */
    static void partie(PoseStack ps, int i, Pose p) {
        float[] q = PARTIES[i];
        ps.translate(q[0] / 16F, q[1] / 16F, q[2] / 16F);
        switch (i) {
            case 0 -> ps.mulPose(Axis.XP.rotation(p.teteX));
            case 2 -> {
                ps.mulPose(Axis.ZP.rotation(p.brasDZ));
                ps.mulPose(Axis.XP.rotation(p.brasDX));
            }
            case 3 -> {
                ps.mulPose(Axis.ZP.rotation(p.brasGZ));
                ps.mulPose(Axis.XP.rotation(p.brasGX));
            }
            case 4 -> ps.mulPose(Axis.XP.rotation(p.jambeDX));
            case 5 -> ps.mulPose(Axis.XP.rotation(p.jambeGX));
            default -> { }
        }
    }

    /**
     * Le pantin : six pavés d'une couleur (argb ; transparent si alpha < 255), les yeux qui brillent (yeux : rgb, ou -1),
     * dans le repère que {@link #repere} a mis dans ps.
     */
    static void pantin(PoseStack ps, MultiBufferSource b, Pose p, int argb, int yeux, int lumiere) {
        boolean transparent = (argb >>> 24) < 250;
        VertexConsumer c = transparent ? voile(b) : plein(b);
        for (int i = 0; i < 6; i++) {
            float[] q = PARTIES[i];
            ps.pushPose();
            partie(ps, i, p);
            pave(ps, c, q[3], q[4], q[5], q[6], q[7], q[8], argb, lumiere);
            if (i == 0 && yeux != -1) {
                VertexConsumer e = plein(b);
                int y = 0xFF000000 | yeux;
                pave(ps, e, -3, -4.6F, -4.15F, 2, 1, 0.1F, y, LightTexture.FULL_BRIGHT);
                pave(ps, e, 1, -4.6F, -4.15F, 2, 1, 0.1F, y, LightTexture.FULL_BRIGHT);
                c = transparent ? voile(b) : plein(b);
            }
            ps.popPose();
        }
    }

    /** Un pavé (coin x, y, z, taille w, h, d en pixels) dans le repère courant. */
    static void pave(PoseStack ps, VertexConsumer c, float x, float y, float z, float w, float h, float d, int argb, int lumiere) {
        PoseStack.Pose pose = ps.last();
        float x0 = x / 16, y0 = y / 16, z0 = z / 16, x1 = (x + w) / 16, y1 = (y + h) / 16, z1 = (z + d) / 16;
        int al = argb >>> 24, r = argb >> 16 & 255, g = argb >> 8 & 255, bl = argb & 255;
        float[][][] f = {
                {{x0, y0, z0}, {x1, y0, z0}, {x1, y1, z0}, {x0, y1, z0}, {0, 0, -1}},
                {{x0, y0, z1}, {x0, y1, z1}, {x1, y1, z1}, {x1, y0, z1}, {0, 0, 1}},
                {{x0, y0, z0}, {x0, y1, z0}, {x0, y1, z1}, {x0, y0, z1}, {-1, 0, 0}},
                {{x1, y0, z0}, {x1, y0, z1}, {x1, y1, z1}, {x1, y1, z0}, {1, 0, 0}},
                {{x0, y0, z0}, {x0, y0, z1}, {x1, y0, z1}, {x1, y0, z0}, {0, -1, 0}},
                {{x0, y1, z0}, {x1, y1, z0}, {x1, y1, z1}, {x0, y1, z1}, {0, 1, 0}}};
        for (int k = 0; k < 6; k++) {
            float ombre = k == 4 ? 1 : k == 5 ? 0.6F : k < 2 ? 0.85F : 0.72F;
            for (int qq = 0; qq < 8; qq++) {
                int q = qq < 4 ? qq : 7 - qq;
                c.addVertex(pose, f[k][q][0], f[k][q][1], f[k][q][2]).setColor((int) (r * ombre), (int) (g * ombre), (int) (bl * ombre), al)
                        .setUv(0.5F, 0.5F).setOverlay(OverlayTexture.NO_OVERLAY).setLight(lumiere).setNormal(pose, f[k][4][0], f[k][4][1], f[k][4][2]);
            }
        }
    }

    /** La main droite du pantin (le bout du bras), dans le monde. */
    static Vec3 mainPantin(Vec3 pieds, float lacet, float k, Pose p, boolean gauche) {
        PoseStack ps = new PoseStack();
        repere(ps, Vec3.ZERO, pieds, lacet, k, p.penche);
        partie(ps, gauche ? 3 : 2, p);
        Vector3f m = ps.last().pose().transformPosition(new Vector3f((gauche ? 1 : -1) / 16F, 10.5F / 16F, 0), new Vector3f());
        return new Vec3(m.x, m.y, m.z);
    }

    /** Un point du corps du pantin (en pixels de PlayerModel, dans le repère du corps), dans le monde. */
    static Vec3 pointPantin(Vec3 pieds, float lacet, float k, Pose p, float x, float y, float z) {
        PoseStack ps = new PoseStack();
        repere(ps, Vec3.ZERO, pieds, lacet, k, p.penche);
        Vector3f m = ps.last().pose().transformPosition(new Vector3f(x / 16F, y / 16F, z / 16F), new Vector3f());
        return new Vec3(m.x, m.y, m.z);
    }
}
