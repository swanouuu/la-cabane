package fr.cabane.client.donjon;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import fr.cabane.Cabane;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import org.joml.Matrix4f;

/**
 * Les pavés de couleur des boss des donjons (comme ceux d'Ugrak), avec en plus des pavés translucides (ailes, glace,
 * soie). Le grain de la texture donne de la matière aux grandes faces.
 */
final class Formes {
    static final ResourceLocation GRAIN = Cabane.id("textures/entity/grain_boss.png");
    private static final int PLEIN = 0xF000F0;

    private static MultiBufferSource buffers;
    private static VertexConsumer opaque;
    private static int lumiere;
    private static int overlay;

    static void debut(MultiBufferSource b, int lum, int ov) {
        buffers = b;
        opaque = b.getBuffer(RenderType.entityCutoutNoCull(GRAIN));
        lumiere = lum;
        overlay = ov;
    }

    /** Après un dessin d'un autre genre (une sphère de lumière) : on reprend les pavés là où on en était. */
    static void reprendre() {
        opaque = buffers.getBuffer(RenderType.entityCutoutNoCull(GRAIN));
    }

    static float lisse(float u) {
        u = Mth.clamp(u, 0, 1);
        return u * u * (3 - 2 * u);
    }

    static int melange(int a, int b, float k) {
        k = Mth.clamp(k, 0, 1);
        int r = (int) Mth.lerp(k, a >> 16 & 255, b >> 16 & 255), g = (int) Mth.lerp(k, a >> 8 & 255, b >> 8 & 255), bl = (int) Mth.lerp(k, a & 255, b & 255);
        return r << 16 | g << 8 | bl;
    }

    static void pave(PoseStack ps, float x0, float y0, float z0, float x1, float y1, float z1, int rgb) {
        pave(ps, x0, y0, z0, x1, y1, z1, rgb, false);
    }

    static void pave(PoseStack ps, float x0, float y0, float z0, float x1, float y1, float z1, int rgb, boolean brille) {
        faces(opaque, ps, x0, y0, z0, x1, y1, z1, rgb, 1F, brille ? PLEIN : lumiere);
    }

    /** Un pavé translucide (alpha de 0 à 1). Il doit être dessiné après les pavés opaques du même modèle. */
    static void verre(PoseStack ps, float x0, float y0, float z0, float x1, float y1, float z1, int rgb, float alpha, boolean brille) {
        VertexConsumer c = buffers.getBuffer(RenderType.entityTranslucent(GRAIN));
        faces(c, ps, x0, y0, z0, x1, y1, z1, rgb, alpha, brille ? PLEIN : lumiere);
        opaque = buffers.getBuffer(RenderType.entityCutoutNoCull(GRAIN));
    }

    private static void faces(VertexConsumer c, PoseStack ps, float x0, float y0, float z0, float x1, float y1, float z1, int rgb, float alpha, int l) {
        PoseStack.Pose p = ps.last();
        Matrix4f m = p.pose();
        float r = (rgb >> 16 & 255) / 255F, g = (rgb >> 8 & 255) / 255F, b = (rgb & 255) / 255F;
        float[][][] f6 = {
                {{x0, y0, z1}, {x1, y0, z1}, {x1, y1, z1}, {x0, y1, z1}, {0, 0, 1}},
                {{x1, y0, z0}, {x0, y0, z0}, {x0, y1, z0}, {x1, y1, z0}, {0, 0, -1}},
                {{x1, y0, z1}, {x1, y0, z0}, {x1, y1, z0}, {x1, y1, z1}, {1, 0, 0}},
                {{x0, y0, z0}, {x0, y0, z1}, {x0, y1, z1}, {x0, y1, z0}, {-1, 0, 0}},
                {{x0, y1, z1}, {x1, y1, z1}, {x1, y1, z0}, {x0, y1, z0}, {0, 1, 0}},
                {{x0, y0, z0}, {x1, y0, z0}, {x1, y0, z1}, {x0, y0, z1}, {0, -1, 0}},
        };
        float[][] uv = {{0, 1}, {1, 1}, {1, 0}, {0, 0}};
        for (float[][] f : f6) {
            float du = Math.max(0.25F, dist(f[0], f[1]) * 2);
            float dv = Math.max(0.25F, dist(f[1], f[2]) * 2);
            for (int k = 0; k < 4; k++) {
                c.addVertex(m, f[k][0], f[k][1], f[k][2]).setColor(r, g, b, alpha).setUv(uv[k][0] * du, uv[k][1] * dv).setOverlay(overlay).setLight(l)
                        .setNormal(p, f[4][0], f[4][1], f[4][2]);
            }
        }
    }

    private static float dist(float[] a, float[] b) {
        return (float) Math.sqrt((a[0] - b[0]) * (a[0] - b[0]) + (a[1] - b[1]) * (a[1] - b[1]) + (a[2] - b[2]) * (a[2] - b[2]));
    }

    /** Déplace l'origine ici, puis tourne (lacet, puis tangage, puis roulis), en radians. */
    static void tourner(PoseStack ps, float px, float py, float pz, float rx, float ry, float rz) {
        ps.translate(px, py, pz);
        if (ry != 0) {
            ps.mulPose(Axis.YP.rotation(ry));
        }
        if (rx != 0) {
            ps.mulPose(Axis.XP.rotation(rx));
        }
        if (rz != 0) {
            ps.mulPose(Axis.ZP.rotation(rz));
        }
    }

    private Formes() {
    }
}
