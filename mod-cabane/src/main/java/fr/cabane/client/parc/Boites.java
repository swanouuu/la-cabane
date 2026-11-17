package fr.cabane.client.parc;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import fr.cabane.Cabane;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix4f;

/**
 * Des pavés de couleur pour le parc (wagons, rails, anneaux de lumière), sur le grain des boss des donjons : soit
 * alignés dans le repère courant, soit tournés n'importe comment (trois axes donnés).
 */
final class Boites {
    static final ResourceLocation GRAIN = Cabane.id("textures/entity/grain_boss.png");
    static final int PLEIN = 0xF000F0;

    static RenderType type() {
        return RenderType.entityCutoutNoCull(GRAIN);
    }

    /** Un pavé aligné sur le repère courant, de (x0, y0, z0) à (x1, y1, z1). */
    static void pave(VertexConsumer c, PoseStack ps, float x0, float y0, float z0, float x1, float y1, float z1, int rgb, int lumiere) {
        float cx = (x0 + x1) / 2, cy = (y0 + y1) / 2, cz = (z0 + z1) / 2;
        boite(c, ps.last(), cx, cy, cz, 1, 0, 0, 0, 1, 0, 0, 0, 1, (x1 - x0) / 2, (y1 - y0) / 2, (z1 - z0) / 2, rgb, lumiere);
    }

    /** Un pavé de centre (cx, cy, cz), d'axes a, b, c (unitaires) et de demi-côtés ha, hb, hc. */
    static void boite(VertexConsumer v, PoseStack.Pose pose, float cx, float cy, float cz,
            float ax, float ay, float az, float bx, float by, float bz, float kx, float ky, float kz,
            float ha, float hb, float hc, int rgb, int lumiere) {
        Matrix4f m = pose.pose();
        float r = (rgb >> 16 & 255) / 255F, g = (rgb >> 8 & 255) / 255F, b = (rgb & 255) / 255F;
        float[][] coin = new float[8][3];
        for (int i = 0; i < 8; i++) {
            float sa = (i & 1) == 0 ? -ha : ha, sb = (i & 2) == 0 ? -hb : hb, sc = (i & 4) == 0 ? -hc : hc;
            coin[i][0] = cx + ax * sa + bx * sb + kx * sc;
            coin[i][1] = cy + ay * sa + by * sb + ky * sc;
            coin[i][2] = cz + az * sa + bz * sb + kz * sc;
        }
        // chaque face : quatre coins (dans le sens qui la tourne vers l'extérieur) et sa normale
        int[][] faces = {{1, 3, 7, 5}, {0, 4, 6, 2}, {2, 6, 7, 3}, {0, 1, 5, 4}, {4, 5, 7, 6}, {0, 2, 3, 1}};
        float[][] normales = {{ax, ay, az}, {-ax, -ay, -az}, {bx, by, bz}, {-bx, -by, -bz}, {kx, ky, kz}, {-kx, -ky, -kz}};
        float[][] uv = {{0, 1}, {1, 1}, {1, 0}, {0, 0}};
        float[] taille = {ha, hb, hc};
        int[][] axes = {{1, 2}, {1, 2}, {0, 2}, {0, 2}, {0, 1}, {0, 1}};
        for (int f = 0; f < 6; f++) {
            float du = Math.max(0.25F, taille[axes[f][0]] * 4), dv = Math.max(0.25F, taille[axes[f][1]] * 4);
            for (int k = 0; k < 4; k++) {
                float[] p = coin[faces[f][k]];
                v.addVertex(m, p[0], p[1], p[2]).setColor(r, g, b, 1F).setUv(uv[k][0] * du, uv[k][1] * dv).setOverlay(OverlayTexture.NO_OVERLAY)
                        .setLight(lumiere).setNormal(pose, normales[f][0], normales[f][1], normales[f][2]);
            }
        }
    }

    /** De la lumière pure : elle s'ajoute à ce qui est derrière, se voit des deux côtés et ne cache rien. */
    static RenderType lumiere() {
        return Types.LUMIERE;
    }

    private static final class Types extends RenderType {
        static final RenderType LUMIERE = create("cabane_parc_lumiere", com.mojang.blaze3d.vertex.DefaultVertexFormat.POSITION_COLOR,
                com.mojang.blaze3d.vertex.VertexFormat.Mode.QUADS, 8192, false, true, CompositeState.builder()
                        .setShaderState(RENDERTYPE_LIGHTNING_SHADER).setWriteMaskState(COLOR_WRITE).setTransparencyState(LIGHTNING_TRANSPARENCY)
                        .setCullState(NO_CULL).createCompositeState(false));

        private Types() {
            super("cabane_parc_rien", com.mojang.blaze3d.vertex.DefaultVertexFormat.POSITION, com.mojang.blaze3d.vertex.VertexFormat.Mode.QUADS, 256, false, false, () -> {
            }, () -> {
            });
        }
    }

    /** Un pavé de lumière (dans {@link #lumiere()}) : centre, axes a, b, c, demi-côtés, couleur et force (0 à 1). */
    static void lueur(VertexConsumer v, PoseStack.Pose pose, float cx, float cy, float cz,
            float ax, float ay, float az, float bx, float by, float bz, float kx, float ky, float kz,
            float ha, float hb, float hc, int rgb, float force) {
        if (force <= 0.003F) {
            return;
        }
        Matrix4f m = pose.pose();
        float r = (rgb >> 16 & 255) / 255F, g = (rgb >> 8 & 255) / 255F, b = (rgb & 255) / 255F, a = Math.min(1F, force);
        float[][] coin = new float[8][3];
        for (int i = 0; i < 8; i++) {
            float sa = (i & 1) == 0 ? -ha : ha, sb = (i & 2) == 0 ? -hb : hb, sc = (i & 4) == 0 ? -hc : hc;
            coin[i][0] = cx + ax * sa + bx * sb + kx * sc;
            coin[i][1] = cy + ay * sa + by * sb + ky * sc;
            coin[i][2] = cz + az * sa + bz * sb + kz * sc;
        }
        int[][] faces = {{1, 3, 7, 5}, {0, 4, 6, 2}, {2, 6, 7, 3}, {0, 1, 5, 4}, {4, 5, 7, 6}, {0, 2, 3, 1}};
        for (int[] f : faces) {
            for (int k = 0; k < 4; k++) {
                float[] p = coin[f[k]];
                v.addVertex(m, p[0], p[1], p[2]).setColor(r, g, b, a);
            }
        }
    }

    /** Un pavé de lumière posé à plat (aligné sur le monde). */
    static void lueur(VertexConsumer v, PoseStack.Pose pose, float cx, float cy, float cz, float hx, float hy, float hz, int rgb, float force) {
        lueur(v, pose, cx, cy, cz, 1, 0, 0, 0, 1, 0, 0, 0, 1, hx, hy, hz, rgb, force);
    }

    /** Un pavé (ou un pavé de lumière) le long d'un trait de A à B, de section carrée {@code demi}. */
    static void trait(VertexConsumer v, PoseStack.Pose pose, float ax, float ay, float az, float bx, float by, float bz, float demi, int rgb, int lum, float force) {
        float dx = bx - ax, dy = by - ay, dz = bz - az, l = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (l < 1e-4F) {
            return;
        }
        dx /= l;
        dy /= l;
        dz /= l;
        // une perpendiculaire (horizontale si possible), puis la troisième
        float px = -dz, py = 0, pz = dx, pl = (float) Math.sqrt(px * px + pz * pz);
        if (pl < 1e-3F) {
            px = 1;
            pz = 0;
            pl = 1;
        }
        px /= pl;
        pz /= pl;
        float qx = dy * pz - dz * py, qy = dz * px - dx * pz, qz = dx * py - dy * px;
        float cx = (ax + bx) / 2, cy = (ay + by) / 2, cz = (az + bz) / 2;
        if (force < 0) {
            boite(v, pose, cx, cy, cz, px, py, pz, qx, qy, qz, dx, dy, dz, demi, demi, l / 2, rgb, lum);
        } else {
            lueur(v, pose, cx, cy, cz, px, py, pz, qx, qy, qz, dx, dy, dz, demi, demi, l / 2, rgb, force);
        }
    }

    private Boites() {
    }
}
