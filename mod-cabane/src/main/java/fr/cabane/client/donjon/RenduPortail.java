package fr.cabane.client.donjon;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import fr.cabane.client.cinema.Effets;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

/**
 * Le portail de rang S, façon Solo Leveling : un trou sombre vertical, de grands bras bleus qui tournent en spirale vers
 * un cœur blanc, un bord qui crépite d'éclairs, un halo. Avant de s'ouvrir, c'est une fissure de lumière dans l'air.
 * Et le tunnel qu'on voit en plongeant dedans.
 *
 * <p>Toutes les positions sont relatives à la caméra (comme dans Effets).
 */
public final class RenduPortail {
    /** Les couleurs : le bleu du portail, le cyan des bras, le blanc du cœur. */
    private static final float[] BLEU = {0.16F, 0.36F, 1F};
    private static final float[] CYAN = {0.45F, 0.85F, 1F};

    private static final class Types extends RenderType {
        /** De la lumière : additive, des deux côtés, sans écrire la profondeur. */
        static final RenderType LUMIERE = create("cabane_portail_lumiere", DefaultVertexFormat.POSITION_COLOR, VertexFormat.Mode.QUADS, 16384, false, true,
                CompositeState.builder().setShaderState(RENDERTYPE_LIGHTNING_SHADER).setWriteMaskState(COLOR_WRITE)
                        .setTransparencyState(LIGHTNING_TRANSPARENCY).setCullState(NO_CULL).createCompositeState(false));
        /** De l'ombre : un voile sombre et transparent (le fond du trou). */
        static final RenderType OMBRE = create("cabane_portail_ombre", DefaultVertexFormat.POSITION_COLOR, VertexFormat.Mode.QUADS, 16384, false, true,
                CompositeState.builder().setShaderState(RENDERTYPE_LIGHTNING_SHADER).setWriteMaskState(COLOR_WRITE)
                        .setTransparencyState(TRANSLUCENT_TRANSPARENCY).setCullState(NO_CULL).createCompositeState(false));
        /** Les mêmes, par-dessus tout (le tunnel, quand on est dedans : rien du monde ne doit passer à travers). */
        static final RenderType LUMIERE_DEVANT = create("cabane_portail_lumiere_devant", DefaultVertexFormat.POSITION_COLOR, VertexFormat.Mode.QUADS, 16384,
                false, true, CompositeState.builder().setShaderState(RENDERTYPE_LIGHTNING_SHADER).setWriteMaskState(COLOR_WRITE)
                        .setTransparencyState(LIGHTNING_TRANSPARENCY).setCullState(NO_CULL).setDepthTestState(NO_DEPTH_TEST).createCompositeState(false));
        static final RenderType OMBRE_DEVANT = create("cabane_portail_ombre_devant", DefaultVertexFormat.POSITION_COLOR, VertexFormat.Mode.QUADS, 16384,
                false, true, CompositeState.builder().setShaderState(RENDERTYPE_LIGHTNING_SHADER).setWriteMaskState(COLOR_WRITE)
                        .setTransparencyState(TRANSLUCENT_TRANSPARENCY).setCullState(NO_CULL).setDepthTestState(NO_DEPTH_TEST).createCompositeState(false));

        private Types() {
            super("cabane_portail_rien", DefaultVertexFormat.POSITION, VertexFormat.Mode.QUADS, 256, false, false, () -> { }, () -> { });
        }
    }

    private static void sommet(VertexConsumer c, Matrix4f m, Vec3 p, float r, float g, float b, float a) {
        c.addVertex(m, (float) p.x, (float) p.y, (float) p.z).setColor(r, g, b, a);
    }

    private static void quad(VertexConsumer c, Matrix4f m, Vec3 a, Vec3 b, Vec3 cc, Vec3 d, float[] ca, float aa, float[] cb, float ab) {
        sommet(c, m, a, ca[0], ca[1], ca[2], aa);
        sommet(c, m, b, ca[0], ca[1], ca[2], aa);
        sommet(c, m, cc, cb[0], cb[1], cb[2], ab);
        sommet(c, m, d, cb[0], cb[1], cb[2], ab);
    }

    private static float[] mel(float[] a, float[] b, double k) {
        float t = (float) Math.max(0, Math.min(1, k));
        return new float[] {a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t, a[2] + (b[2] - a[2]) * t};
    }

    /**
     * Le portail. {@code centre} : relatif à la caméra ; {@code normale} : la direction vers laquelle il regarde
     * (horizontale) ; {@code ouverture} de 0 (fermé) à 1 (grand ouvert, un peu plus pendant le rebond) ;
     * {@code fissure} de 0 à 1 : la fissure de lumière qui précède l'ouverture.
     */
    public static void dessiner(PoseStack pose, MultiBufferSource.BufferSource buffers, Vec3 centre, Vec3 normale, double rayon, float ouverture,
            float fissure, float temps) {
        Vec3 n = new Vec3(normale.x, 0, normale.z).normalize();
        Vec3 u = new Vec3(-n.z, 0, n.x);
        Vec3 v = new Vec3(0, 1, 0);
        Matrix4f m = pose.last().pose();
        double r = rayon * ouverture;

        // la fissure : un trait de lumière vertical, déchiré, qui grandit puis s'élargit
        if (fissure > 0.01F && ouverture < 0.35F) {
            double h = rayon * 1.05 * Math.min(1, fissure * 1.4);
            float al = (float) Math.min(1, fissure * 2) * (1 - ouverture / 0.35F);
            long graine = (long) (temps * 14);
            Effets.eclair(pose, buffers, centre.add(v.scale(-h)), centre.add(v.scale(h)), graine, 0.05 + fissure * 0.08, CYAN[0], CYAN[1], CYAN[2]);
            Effets.eclair(pose, buffers, centre.add(v.scale(-h * 0.8)), centre.add(v.scale(h * 0.9)), graine + 7, 0.03, BLEU[0], BLEU[1], BLEU[2]);
            VertexConsumer c = buffers.getBuffer(Types.LUMIERE);
            double w = 0.15 + fissure * 0.5;
            for (double k : new double[] {3, 1}) {
                float a = (float) (k > 1 ? 0.25 : 0.7) * al;
                Vec3 o = u.scale(w * k * 0.5);
                quad(c, m, centre.add(v.scale(-h)), centre.add(v.scale(-h)), centre.add(o), centre.subtract(o), CYAN, 0, CYAN, a);
                quad(c, m, centre.add(o), centre.subtract(o), centre.add(v.scale(h)), centre.add(v.scale(h)), CYAN, a, CYAN, 0);
            }
            buffers.endBatch(Types.LUMIERE);
        }
        if (r < 0.05) {
            return;
        }

        // le fond du trou : un disque sombre, presque noir au milieu, bleu nuit vers le bord
        VertexConsumer o = buffers.getBuffer(Types.OMBRE);
        int seg = 48;
        int anneaux = 6;
        for (int a = 0; a < anneaux; a++) {
            double r0 = r * a / anneaux, r1 = r * (a + 1) / anneaux;
            float[] c0 = mel(new float[] {0.01F, 0.01F, 0.05F}, new float[] {0.03F, 0.06F, 0.22F}, (double) a / anneaux);
            float[] c1 = mel(new float[] {0.01F, 0.01F, 0.05F}, new float[] {0.03F, 0.06F, 0.22F}, (double) (a + 1) / anneaux);
            for (int s = 0; s < seg; s++) {
                double t0 = s * Math.PI * 2 / seg, t1 = (s + 1) * Math.PI * 2 / seg;
                quad(o, m, point(centre, u, v, r0, t0), point(centre, u, v, r0, t1), point(centre, u, v, r1, t1), point(centre, u, v, r1, t0),
                        c0, 0.94F, c1, 0.9F);
            }
        }
        buffers.endBatch(Types.OMBRE);

        VertexConsumer c = buffers.getBuffer(Types.LUMIERE);
        Vec3 avant = n.scale(0.04);
        // les bras de la spirale, qui tournent vers le centre
        int bras = 7;
        int pas = 28;
        double tour = temps * 1.9;
        for (int b = 0; b < bras; b++) {
            double base = b * Math.PI * 2 / bras + tour;
            for (int k = 0; k < pas; k++) {
                double s0 = 0.06 + 0.94 * k / pas, s1 = 0.06 + 0.94 * (k + 1) / pas;
                double a0 = base - 4.2 * s0, a1 = base - 4.2 * s1;
                double l0 = 0.22 * (0.25 + s0), l1 = 0.22 * (0.25 + s1);
                float[] col0 = mel(new float[] {0.9F, 0.97F, 1F}, CYAN, s0 * 1.6);
                float[] col1 = mel(new float[] {0.9F, 0.97F, 1F}, CYAN, s1 * 1.6);
                col0 = mel(col0, BLEU, (s0 - 0.5) * 2);
                col1 = mel(col1, BLEU, (s1 - 0.5) * 2);
                float al0 = (float) (0.55 * Math.sin(Math.PI * Math.min(1, s0 * 1.15))) + 0.08F;
                float al1 = (float) (0.55 * Math.sin(Math.PI * Math.min(1, s1 * 1.15))) + 0.08F;
                quad(c, m, point(centre.add(avant), u, v, s0 * r, a0 - l0), point(centre.add(avant), u, v, s0 * r, a0 + l0),
                        point(centre.add(avant), u, v, s1 * r, a1 + l1), point(centre.add(avant), u, v, s1 * r, a1 - l1), col0, al0, col1, al1);
            }
        }
        // des filaments plus fins, qui tournent plus vite dans l'autre sens
        for (int b = 0; b < 12; b++) {
            double base = b * Math.PI * 2 / 12 - temps * 3.1;
            for (int k = 0; k < 16; k++) {
                double s0 = 0.2 + 0.8 * k / 16, s1 = 0.2 + 0.8 * (k + 1) / 16;
                double a0 = base - 2.6 * s0, a1 = base - 2.6 * s1;
                float al = (float) (0.22 * Math.sin(Math.PI * s0));
                quad(c, m, point(centre.add(avant.scale(1.5)), u, v, s0 * r, a0 - 0.035), point(centre.add(avant.scale(1.5)), u, v, s0 * r, a0 + 0.035),
                        point(centre.add(avant.scale(1.5)), u, v, s1 * r, a1 + 0.035), point(centre.add(avant.scale(1.5)), u, v, s1 * r, a1 - 0.035),
                        CYAN, al, CYAN, al);
            }
        }
        // le cœur : une lueur blanche qui pulse
        double coeur = r * (0.16 + 0.03 * Math.sin(temps * 5));
        for (int k = 0; k < 3; k++) {
            double rr = coeur * (1 + k * 0.9);
            float al = k == 0 ? 0.85F : k == 1 ? 0.35F : 0.15F;
            for (int s = 0; s < seg; s++) {
                double t0 = s * Math.PI * 2 / seg, t1 = (s + 1) * Math.PI * 2 / seg;
                quad(c, m, centre.add(avant), centre.add(avant), point(centre.add(avant), u, v, rr, t1), point(centre.add(avant), u, v, rr, t0),
                        new float[] {1, 1, 1}, al, CYAN, 0);
            }
        }
        // le bord : un anneau vif qui scintille, et un grand halo bleu autour
        double flick = 0.85 + 0.15 * Math.sin(temps * 23) * Math.sin(temps * 7.3);
        for (int s = 0; s < seg * 2; s++) {
            double t0 = s * Math.PI / seg, t1 = (s + 1) * Math.PI / seg;
            double vague0 = 1 + 0.025 * Math.sin(t0 * 9 + temps * 6), vague1 = 1 + 0.025 * Math.sin(t1 * 9 + temps * 6);
            Vec3 i0 = point(centre.add(avant), u, v, r * 0.9 * vague0, t0), i1 = point(centre.add(avant), u, v, r * 0.9 * vague1, t1);
            Vec3 m0 = point(centre.add(avant), u, v, r * 1.0 * vague0, t0), m1 = point(centre.add(avant), u, v, r * 1.0 * vague1, t1);
            Vec3 e0 = point(centre.add(avant), u, v, r * 1.07 * vague0, t0), e1 = point(centre.add(avant), u, v, r * 1.07 * vague1, t1);
            Vec3 h0 = point(centre, u, v, r * 1.45, t0), h1 = point(centre, u, v, r * 1.45, t1);
            float fa = (float) flick;
            quad(c, m, i0, i1, m1, m0, BLEU, 0, CYAN, 0.9F * fa);
            quad(c, m, m0, m1, e1, e0, new float[] {0.85F, 0.95F, 1F}, 0.9F * fa, BLEU, 0.5F * fa);
            quad(c, m, e0, e1, h1, h0, BLEU, 0.32F * fa, BLEU, 0);
        }
        buffers.endBatch(Types.LUMIERE);
        // des éclairs qui courent sur le bord
        long graine = (long) (temps * 9);
        java.util.Random rnd = new java.util.Random(graine);
        for (int k = 0; k < 4; k++) {
            double a = rnd.nextDouble() * Math.PI * 2;
            double b = a + 0.3 + rnd.nextDouble() * 0.5;
            Effets.eclair(pose, buffers, point(centre.add(avant), u, v, r * 1.02, a), point(centre.add(avant), u, v, r * (1.0 + rnd.nextDouble() * 0.25), b),
                    graine * 31 + k, 0.035, CYAN[0], CYAN[1], CYAN[2]);
        }
        buffers.endBatch();
    }

    private static Vec3 point(Vec3 c, Vec3 u, Vec3 v, double r, double a) {
        return c.add(u.scale(Math.cos(a) * r)).add(v.scale(Math.sin(a) * r));
    }

    /**
     * Le tunnel derrière le portail : un long cylindre sombre, des bandes de lumière qui y tournent en hélice, et tout au
     * bout une lumière blanche. {@code entree} : le centre du portail (relatif à la caméra) ; {@code dir} : la direction
     * du tunnel (vers l'intérieur). Dessiné par-dessus tout.
     */
    public static void tunnel(PoseStack pose, MultiBufferSource.BufferSource buffers, Vec3 entree, Vec3 dir, double rayon, double longueur, float temps,
            float bout) {
        Vec3 n = dir.normalize();
        Vec3 u = new Vec3(-n.z, 0, n.x).normalize();
        Vec3 v = n.cross(u).normalize();
        Matrix4f m = pose.last().pose();
        int seg = 32;
        int tranches = 40;
        VertexConsumer o = buffers.getBuffer(Types.OMBRE_DEVANT);
        for (int k = 0; k < tranches; k++) {
            double z0 = longueur * k / tranches, z1 = longueur * (k + 1) / tranches;
            for (int s = 0; s < seg; s++) {
                double t0 = s * Math.PI * 2 / seg, t1 = (s + 1) * Math.PI * 2 / seg;
                quad(o, m, anneau(entree, n, u, v, rayon, z0, t0), anneau(entree, n, u, v, rayon, z0, t1), anneau(entree, n, u, v, rayon, z1, t1),
                        anneau(entree, n, u, v, rayon, z1, t0), new float[] {0.01F, 0.02F, 0.08F}, 0.97F, new float[] {0.01F, 0.02F, 0.08F}, 0.97F);
            }
        }
        // le fond, fermé
        for (int s = 0; s < seg; s++) {
            double t0 = s * Math.PI * 2 / seg, t1 = (s + 1) * Math.PI * 2 / seg;
            Vec3 f = entree.add(n.scale(longueur));
            quad(o, m, f, f, anneau(entree, n, u, v, rayon, longueur, t1), anneau(entree, n, u, v, rayon, longueur, t0),
                    new float[] {0.02F, 0.03F, 0.1F}, 1, new float[] {0.02F, 0.03F, 0.1F}, 1);
        }
        buffers.endBatch(Types.OMBRE_DEVANT);
        VertexConsumer c = buffers.getBuffer(Types.LUMIERE_DEVANT);
        // les bandes en hélice
        int bandes = 9;
        for (int b = 0; b < bandes; b++) {
            double base = b * Math.PI * 2 / bandes + temps * 4.5;
            float[] col = b % 3 == 0 ? new float[] {0.85F, 0.95F, 1F} : b % 3 == 1 ? CYAN : BLEU;
            for (int k = 0; k < tranches; k++) {
                double z0 = longueur * k / tranches, z1 = longueur * (k + 1) / tranches;
                double a0 = base + z0 * 0.32, a1 = base + z1 * 0.32;
                double l = 0.22;
                float al = (float) (0.85 * (1 - (double) k / tranches) + 0.25);
                double rr = rayon * 0.97;
                quad(c, m, anneau(entree, n, u, v, rr, z0, a0 - l), anneau(entree, n, u, v, rr, z0, a0 + l), anneau(entree, n, u, v, rr, z1, a1 + l),
                        anneau(entree, n, u, v, rr, z1, a1 - l), col, al, col, al);
            }
        }
        // des traits de vitesse, qui défilent vers la caméra
        java.util.Random rnd = new java.util.Random(42);
        for (int k = 0; k < 60; k++) {
            double a = rnd.nextDouble() * Math.PI * 2;
            double rr = rayon * (0.35 + rnd.nextDouble() * 0.55);
            double z = ((rnd.nextDouble() * longueur) - temps * 38) % longueur;
            if (z < 0) {
                z += longueur;
            }
            double l = 2.5 + rnd.nextDouble() * 3;
            Vec3 a0 = anneau(entree, n, u, v, rr, z, a), a1 = anneau(entree, n, u, v, rr, z + l, a);
            Vec3 cote = u.scale(Math.sin(a)).subtract(v.scale(Math.cos(a))).scale(0.025);
            quad(c, m, a0.add(cote), a0.subtract(cote), a1.subtract(cote), a1.add(cote), new float[] {0.9F, 0.97F, 1F}, 0.6F, CYAN, 0);
        }
        // la lumière au bout, qui grandit
        if (bout > 0) {
            Vec3 f = entree.add(n.scale(longueur * 0.98));
            for (int k = 0; k < 3; k++) {
                double rr = rayon * (0.3 + bout * 0.7) * (1 + k * 0.6);
                float al = Math.min(1, bout * 1.3F) * (k == 0 ? 1F : k == 1 ? 0.5F : 0.25F);
                for (int s = 0; s < seg; s++) {
                    double t0 = s * Math.PI * 2 / seg, t1 = (s + 1) * Math.PI * 2 / seg;
                    quad(c, m, f, f, anneau(f, n, u, v, rr, 0, t1), anneau(f, n, u, v, rr, 0, t0), new float[] {1, 1, 1}, al, CYAN, 0);
                }
            }
        }
        buffers.endBatch(Types.LUMIERE_DEVANT);
    }

    private static Vec3 anneau(Vec3 entree, Vec3 n, Vec3 u, Vec3 v, double r, double z, double a) {
        return entree.add(n.scale(z)).add(u.scale(Math.cos(a) * r)).add(v.scale(Math.sin(a) * r));
    }

    private RenduPortail() {
    }
}
