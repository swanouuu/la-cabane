package fr.cabane.client.pouvoir;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import fr.cabane.Cabane;
import fr.cabane.pouvoir.Fracture;
import java.util.Random;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import org.joml.Matrix4f;

/** La fissure sur l'écran de ceux qui sont pris dans {@link Fracture} : la toile de verre violette, puis elle se referme. */
public final class ClientFracture {
    private static final int RAYONS = 18;
    private static final float[] ANNEAUX = {0.14F, 0.3F, 0.5F, 0.72F, 1F};

    private static long debut = -1;
    private static double casse, lecture, fin;
    /** Pour chaque rayon et chaque anneau : {cos, sin, distance}. */
    private static final float[][][] POINTS = new float[RAYONS][ANNEAUX.length][];
    private static final boolean[][] LIENS_RAYON = new boolean[RAYONS][ANNEAUX.length], LIENS_ANNEAU = new boolean[RAYONS][ANNEAUX.length];
    /** {angle, distance, taille} */
    private static final float[][] ECLATS = new float[30][];

    private ClientFracture() {
    }

    public static void effet(Fracture.Effet p) {
        debut = System.nanoTime();
        casse = p.casse() / 20.0;
        lecture = p.lecture() / 20.0;
        fin = p.fin() / 20.0;
        Random r = new Random();
        for (int k = 0; k < RAYONS; k++) {
            float a0 = (float) (k * Math.PI * 2 / RAYONS + (r.nextDouble() - 0.5) * 0.24);
            for (int n = 0; n < ANNEAUX.length; n++) {
                float a = a0 + (float) ((r.nextDouble() - 0.5) * 0.16);
                POINTS[k][n] = new float[]{(float) Math.cos(a), (float) Math.sin(a) * 0.82F, ANNEAUX[n] * (0.82F + 0.3F * r.nextFloat())};
                LIENS_RAYON[k][n] = r.nextFloat() < 0.95F;
                LIENS_ANNEAU[k][n] = r.nextFloat() < 0.78F;
            }
        }
        for (int i = 0; i < ECLATS.length; i++) {
            ECLATS[i] = new float[]{(float) (r.nextDouble() * Math.PI * 2), 0.85F + 0.6F * r.nextFloat(), 3 + 7 * r.nextFloat()};
        }
    }

    private static double borne(double u) {
        return Math.max(0, Math.min(1, u));
    }

    private static void ecran(GuiGraphics g) {
        if (debut < 0) {
            return;
        }
        double s = (System.nanoTime() - debut) / 1e9;
        if (s > casse + lecture + fin) {
            debut = -1;
            return;
        }
        int W = g.guiWidth(), H = g.guiHeight();
        double u = 1 - borne((s - casse - lecture) / fin);
        double grandit = s < casse ? 0.18 + 0.82 * (1 - Math.pow(1 - s / casse, 3)) : 1;
        float R = (float) (Math.hypot(W, H) * 0.56 * grandit * u);
        // la teinte violette, plus forte pendant que tout remonte le temps
        double violet = s < casse ? 0.2 * s / casse : (0.3 + 0.05 * Math.sin(s * 14)) * u;
        g.fill(0, 0, W, H, ((int) (255 * violet) << 24) | 0x5424A0);
        if (s >= casse && u > 0) {
            // des lignes de bande qui défilent à l'envers
            for (int i = 0; i < 16; i++) {
                int y = (int) (((i * 0.0625 + s * 0.55) % 1) * H);
                g.fill(0, y, W, y + 1, ((int) (46 * u) << 24) | 0xE2C4FF);
            }
        }
        // un éclair blanc quand la fissure se referme
        if (s > casse + lecture) {
            g.fill(0, 0, W, H, ((int) (200 * u * u) << 24) | 0xFFFFFF);
        }
        g.flush();
        if (R > 1) {
            RenderSystem.enableBlend();
            RenderSystem.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE);
            RenderSystem.disableDepthTest();
            RenderSystem.disableCull();
            RenderSystem.setShader(GameRenderer::getPositionColorShader);
            Matrix4f m = g.pose().last().pose();
            BufferBuilder b = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
            float cx = W / 2F, cy = H / 2F;
            toile(b, m, cx, cy, R, 5F, 0.66F, 0.35F, 1F, 0.10F);
            toile(b, m, cx, cy, R, 2.6F, 0.66F, 0.35F, 1F, 0.28F);
            toile(b, m, cx, cy, R, 1F, 0.9F, 0.8F, 1F, 0.85F);
            toile(b, m, cx, cy, R, 0.45F, 1F, 1F, 1F, 1F);
            for (float[] e : ECLATS) {
                float x = cx + (float) Math.cos(e[0]) * R * e[1], y = cy + (float) Math.sin(e[0]) * R * e[1] * 0.82F, t = e[2];
                sommet(b, m, x, y - t, 0.9F, 0.8F, 1F, 0.7F);
                sommet(b, m, x - t, y, 0.9F, 0.8F, 1F, 0.7F);
                sommet(b, m, x, y + t, 0.9F, 0.8F, 1F, 0.7F);
                sommet(b, m, x + t, y, 0.9F, 0.8F, 1F, 0.7F);
            }
            BufferUploader.drawWithShader(b.buildOrThrow());
            RenderSystem.enableCull();
            RenderSystem.defaultBlendFunc();
            RenderSystem.enableDepthTest();
        }
    }

    private static void toile(BufferBuilder b, Matrix4f m, float cx, float cy, float R, float e, float r, float v, float bl, float a) {
        for (int k = 0; k < RAYONS; k++) {
            float[] p0 = point(k, 0, cx, cy, R);
            trait(b, m, cx, cy, p0[0], p0[1], e, r, v, bl, a);
            for (int n = 0; n < ANNEAUX.length; n++) {
                float[] p = point(k, n, cx, cy, R);
                if (n + 1 < ANNEAUX.length && LIENS_RAYON[k][n]) {
                    float[] q = point(k, n + 1, cx, cy, R);
                    trait(b, m, p[0], p[1], q[0], q[1], e, r, v, bl, a);
                }
                if (LIENS_ANNEAU[k][n]) {
                    float[] q = point((k + 1) % RAYONS, n, cx, cy, R);
                    trait(b, m, p[0], p[1], q[0], q[1], e, r, v, bl, a);
                }
            }
        }
    }

    private static float[] point(int k, int n, float cx, float cy, float R) {
        float[] p = POINTS[k][n];
        return new float[]{cx + p[0] * p[2] * R, cy + p[1] * p[2] * R};
    }

    private static void trait(BufferBuilder b, Matrix4f m, float x0, float y0, float x1, float y1, float e, float r, float v, float bl, float a) {
        float dx = x1 - x0, dy = y1 - y0, l = (float) Math.hypot(dx, dy);
        if (l < 0.01F) {
            return;
        }
        float nx = -dy / l * e, ny = dx / l * e;
        sommet(b, m, x0 + nx, y0 + ny, r, v, bl, a);
        sommet(b, m, x0 - nx, y0 - ny, r, v, bl, a);
        sommet(b, m, x1 - nx, y1 - ny, r, v, bl, a);
        sommet(b, m, x1 + nx, y1 + ny, r, v, bl, a);
    }

    private static void sommet(BufferBuilder b, Matrix4f m, float x, float y, float r, float v, float bl, float a) {
        b.addVertex(m, x, y, 0).setColor(r, v, bl, a);
    }

    @EventBusSubscriber(modid = Cabane.MODID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
    public static final class Couches {
        @SubscribeEvent
        public static void ecrans(RegisterGuiLayersEvent event) {
            event.registerAboveAll(Cabane.id("fracture"), (g, delta) -> ecran(g));
        }

        private Couches() {
        }
    }
}
