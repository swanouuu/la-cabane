package fr.cabane.client.donjon;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import fr.cabane.Cabane;
import fr.cabane.client.cinema.Effets;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

/**
 * Ugrak, l'Ogre Écarlate, en blocs de couleur sur une texture grainée : environ sept blocs, la peau rouge fendue de
 * lave, les épaules massives, une épaulière de fer noir à pointes, des cornes d'os, des défenses, un chignon noir, et
 * dans la main droite un couperet de fer noir aux runes rouges. Les yeux, les runes et les fissures brillent dans le
 * noir (plus fort quand il est en rage).
 *
 * <p>Repère du modèle : les pieds à l'origine, l'avant vers +z, en blocs.
 */
public final class ModeleOgre {
    /** Ugrak est dessiné une fois et demie plus grand que ce modèle. */
    public static final float ECHELLE = 1.5F;
    private static final ResourceLocation GRAIN = Cabane.id("textures/entity/cheveux.png");
    private static final int PEAU = 0xB02A22, PEAU_SOMBRE = 0x7A1A16, FER = 0x2A2A30, FER_CLAIR = 0x60606C, CUIR = 0x3B2416, OS = 0xE6DCC2,
            OR = 0xD8A53C, CHEVEUX = 0x141012;
    private static final int PLEIN = 0xF000F0;

    /** La pose d'une image (angles en radians, distances en blocs). */
    public record Pose(float pas, float ampleur, float teteLacet, float teteTangage, float abaisse, float penche, float brasD, float brasDZ, float coudeD,
            float brasG, float brasGZ, float coudeG, float cuisseD, float tibiaD, float cuisseG, float tibiaG, float machoire, float arme, float chute) {
    }

    private static VertexConsumer c;
    private static int lumiere;
    private static int overlay;

    /** Un pavé de (x0, y0, z0) à (x1, y1, z1), de cette couleur ; {@code brille} : en pleine lumière. */
    /** Commence un dessin (les sbires utilisent les mêmes pavés). */
    static void debut(MultiBufferSource buffers, int lum, int ov) {
        c = buffers.getBuffer(RenderType.entityCutoutNoCull(GRAIN));
        lumiere = lum;
        overlay = ov;
    }

    static void pave(PoseStack ps, float x0, float y0, float z0, float x1, float y1, float z1, int rgb, boolean brille) {
        PoseStack.Pose p = ps.last();
        Matrix4f m = p.pose();
        float r = (rgb >> 16 & 255) / 255F, g = (rgb >> 8 & 255) / 255F, b = (rgb & 255) / 255F;
        int l = brille ? PLEIN : lumiere;
        float[][][] faces = {
                {{x0, y0, z1}, {x1, y0, z1}, {x1, y1, z1}, {x0, y1, z1}, {0, 0, 1}},
                {{x1, y0, z0}, {x0, y0, z0}, {x0, y1, z0}, {x1, y1, z0}, {0, 0, -1}},
                {{x1, y0, z1}, {x1, y0, z0}, {x1, y1, z0}, {x1, y1, z1}, {1, 0, 0}},
                {{x0, y0, z0}, {x0, y0, z1}, {x0, y1, z1}, {x0, y1, z0}, {-1, 0, 0}},
                {{x0, y1, z1}, {x1, y1, z1}, {x1, y1, z0}, {x0, y1, z0}, {0, 1, 0}},
                {{x0, y0, z0}, {x1, y0, z0}, {x1, y0, z1}, {x0, y0, z1}, {0, -1, 0}},
        };
        // le grain : environ deux carreaux de texture par bloc
        float[][] uv = {{0, 1}, {1, 1}, {1, 0}, {0, 0}};
        for (float[][] f : faces) {
            float du = Math.max(0.25F, dist(f[0], f[1]) * 2);
            float dv = Math.max(0.25F, dist(f[1], f[2]) * 2);
            for (int k = 0; k < 4; k++) {
                c.addVertex(m, f[k][0], f[k][1], f[k][2]).setColor(r, g, b, 1F).setUv(uv[k][0] * du, uv[k][1] * dv).setOverlay(overlay).setLight(l)
                        .setNormal(p, f[4][0], f[4][1], f[4][2]);
            }
        }
    }

    private static float dist(float[] a, float[] b) {
        return (float) Math.sqrt((a[0] - b[0]) * (a[0] - b[0]) + (a[1] - b[1]) * (a[1] - b[1]) + (a[2] - b[2]) * (a[2] - b[2]));
    }

    static void pave(PoseStack ps, float x0, float y0, float z0, float x1, float y1, float z1, int rgb) {
        pave(ps, x0, y0, z0, x1, y1, z1, rgb, false);
    }

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

    /**
     * Dessine Ugrak dans cette pose (le PoseStack est aux pieds, déjà tourné dans sa direction). {@code rage} : les
     * fissures s'embrasent ; {@code temps} fait pulser la lave.
     */
    public static void dessiner(PoseStack ps, MultiBufferSource buffers, Pose p, int lum, int ov, boolean rage, float temps) {
        c = buffers.getBuffer(RenderType.entityCutoutNoCull(GRAIN));
        lumiere = lum;
        overlay = ov;
        float pulse = 0.75F + 0.25F * Mth.sin(temps * (rage ? 0.45F : 0.15F));
        int lave = rage ? melange(0xFF9A20, 0xFFE070, pulse) : melange(0xB0300C, 0xFF7A1A, pulse);
        int yeux = rage ? 0xFF5020 : 0xFFD54A;
        ps.pushPose();
        if (p.chute() > 0) {
            ps.mulPose(Axis.XP.rotation(Mth.clamp(p.chute(), 0, 1) * 1.45F));
        }
        ps.translate(0, -p.abaisse(), 0);

        // les jambes
        jambe(ps, -0.72F, p.cuisseG(), p.tibiaG());
        jambe(ps, 0.72F, p.cuisseD(), p.tibiaD());
        // le bassin, le pagne, la ceinture à boucle d'or
        float bob = Math.abs(Mth.sin(p.pas())) * 0.08F * p.ampleur();
        ps.translate(0, bob, 0);
        pave(ps, -1.15F, 2.1F, -0.72F, 1.15F, 2.95F, 0.72F, CUIR);
        pave(ps, -0.55F, 1.15F, 0.62F, 0.55F, 2.3F, 0.78F, CUIR);
        pave(ps, -1.22F, 2.78F, -0.78F, 1.22F, 3.05F, 0.78F, FER);
        pave(ps, -0.22F, 2.72F, 0.78F, 0.22F, 3.1F, 0.86F, OR);

        // le torse, penché en avant
        ps.pushPose();
        tourner(ps, 0, 2.9F, 0, p.penche(), 0, 0);
        pave(ps, -1.3F, 0, -0.85F, 1.3F, 1.25F, 0.85F, PEAU);
        pave(ps, -1.65F, 1.05F, -0.92F, 1.65F, 2.62F, 1.05F, PEAU);
        pave(ps, -1.3F, 2.2F, -1.05F, 1.3F, 2.95F, 0.35F, PEAU_SOMBRE);
        // les fissures de lave, sur la poitrine et le ventre
        pave(ps, -0.95F, 1.3F, 1.05F, -0.85F, 2.2F, 1.08F, lave, true);
        pave(ps, -0.85F, 2.1F, 1.05F, -0.4F, 2.2F, 1.08F, lave, true);
        pave(ps, 0.35F, 1.2F, 1.05F, 0.45F, 1.95F, 1.08F, lave, true);
        pave(ps, 1.05F, 1.6F, 1.05F, 1.15F, 2.45F, 1.08F, lave, true);
        pave(ps, -0.3F, 0.3F, 0.85F, -0.2F, 0.95F, 0.88F, lave, true);
        pave(ps, 0.6F, 0.15F, 0.85F, 0.7F, 0.7F, 0.88F, lave, true);
        // la chaîne en travers de la poitrine, de l'épaule droite à la hanche gauche
        for (int k = 0; k < 9; k++) {
            float u = k / 8F;
            float x = Mth.lerp(u, 1.45F, -1.2F), y = Mth.lerp(u, 2.5F, 0.15F);
            pave(ps, x - 0.13F, y - 0.1F, 1.03F, x + 0.13F, y + 0.1F, 1.13F, k % 2 == 0 ? FER_CLAIR : FER);
        }
        // la tête, portée bas et en avant
        ps.pushPose();
        tourner(ps, 0, 2.55F, 0.72F, p.teteTangage(), p.teteLacet(), 0);
        tete(ps, p, yeux, buffers);
        ps.popPose();
        // les bras
        bras(ps, false, p.brasG(), p.brasGZ(), p.coudeG(), 0, lave);
        bras(ps, true, p.brasD(), p.brasDZ(), p.coudeD(), p.arme(), lave);
        ps.popPose();
        ps.popPose();
    }

    private static int melange(int a, int b, float k) {
        int r = (int) Mth.lerp(k, a >> 16 & 255, b >> 16 & 255), g = (int) Mth.lerp(k, a >> 8 & 255, b >> 8 & 255), bl = (int) Mth.lerp(k, a & 255, b & 255);
        return r << 16 | g << 8 | bl;
    }

    private static void jambe(PoseStack ps, float x, float cuisse, float tibia) {
        ps.pushPose();
        tourner(ps, x, 2.35F, 0, cuisse, 0, 0);
        pave(ps, -0.52F, -1.28F, -0.52F, 0.52F, 0.1F, 0.52F, PEAU);
        tourner(ps, 0, -1.2F, 0, tibia, 0, 0);
        pave(ps, -0.46F, -1.12F, -0.46F, 0.46F, 0.05F, 0.46F, PEAU_SOMBRE);
        // la botte de fer
        pave(ps, -0.56F, -1.18F, -0.56F, 0.56F, -0.72F, 0.78F, FER);
        pave(ps, -0.58F, -0.78F, -0.58F, 0.58F, -0.64F, 0.6F, FER_CLAIR);
        ps.popPose();
    }

    private static void tete(PoseStack ps, Pose p, int yeux, MultiBufferSource buffers) {
        pave(ps, -0.55F, -0.25F, -0.4F, 0.55F, 0.3F, 0.4F, PEAU_SOMBRE);
        pave(ps, -0.62F, 0, -0.45F, 0.62F, 1.15F, 0.75F, PEAU);
        // l'arcade lourde, le nez, les yeux qui brillent
        pave(ps, -0.67F, 0.62F, 0.66F, 0.67F, 0.86F, 0.88F, PEAU_SOMBRE);
        pave(ps, -0.14F, 0.28F, 0.74F, 0.14F, 0.6F, 0.95F, PEAU_SOMBRE);
        pave(ps, -0.44F, 0.45F, 0.75F, -0.18F, 0.6F, 0.79F, yeux, true);
        pave(ps, 0.18F, 0.45F, 0.75F, 0.44F, 0.6F, 0.79F, yeux, true);
        // la mâchoire, qui s'ouvre pour rugir, et ses deux défenses
        ps.pushPose();
        tourner(ps, 0, 0.28F, -0.2F, p.machoire(), 0, 0);
        pave(ps, -0.6F, -0.42F, -0.12F, 0.6F, 0.04F, 0.98F, PEAU);
        pave(ps, -0.5F, 0.0F, 0.86F, 0.5F, 0.08F, 0.94F, 0x2A0806);
        pave(ps, -0.53F, -0.05F, 0.8F, -0.37F, 0.5F, 0.95F, OS);
        pave(ps, 0.37F, -0.05F, 0.8F, 0.53F, 0.5F, 0.95F, OS);
        ps.popPose();
        // les cornes : vers l'extérieur, puis vers le haut et l'avant, la pointe noire
        for (int s : new int[] {-1, 1}) {
            ps.pushPose();
            tourner(ps, s * 0.6F, 0.95F, 0.15F, 0, 0, s * -0.25F);
            pave(ps, s > 0 ? 0 : -0.55F, -0.16F, -0.17F, s > 0 ? 0.55F : 0, 0.18F, 0.17F, OS);
            tourner(ps, s * 0.5F, 0.05F, 0, -0.45F, 0, s * 0.55F);
            pave(ps, -0.14F, 0, -0.14F, 0.14F, 0.65F, 0.14F, OS);
            tourner(ps, 0, 0.6F, 0, -0.5F, 0, 0);
            pave(ps, -0.09F, 0, -0.09F, 0.09F, 0.45F, 0.09F, 0x2C2620);
            ps.popPose();
        }
        // le chignon noir, noué d'or
        pave(ps, -0.17F, 1.12F, -0.35F, 0.17F, 1.6F, -0.02F, CHEVEUX);
        pave(ps, -0.2F, 1.2F, -0.38F, 0.2F, 1.3F, 0.01F, OR);
        pave(ps, -0.1F, 0.6F, -0.65F, 0.1F, 1.35F, -0.35F, CHEVEUX);
        // le halo des yeux
        Effets.sphere(ps, buffers, new Vec3(-0.31, 0.53, 0.82), 0.07, 1F, 0.75F, 0.2F, 0.7F);
        Effets.sphere(ps, buffers, new Vec3(0.31, 0.53, 0.82), 0.07, 1F, 0.75F, 0.2F, 0.7F);
        c = buffers.getBuffer(RenderType.entityCutoutNoCull(GRAIN));
    }

    /** Un bras : l'épaule, le coude, le brassard de fer, le poing ; à droite le couperet, à gauche l'épaulière à pointes. */
    private static void bras(PoseStack ps, boolean droit, float bras, float brasZ, float coude, float arme, int lave) {
        float s = droit ? 1 : -1;
        ps.pushPose();
        tourner(ps, s * 1.98F, 2.25F, 0.05F, bras, 0, s * -brasZ);
        pave(ps, -0.52F, -1.7F, -0.52F, 0.52F, 0.3F, 0.52F, PEAU);
        pave(ps, s > 0 ? 0.5F : -0.54F, -1.3F, -0.1F, s > 0 ? 0.54F : -0.5F, -0.4F, 0F, lave, true);
        if (!droit) {
            // l'épaulière de fer noir, trois pointes
            pave(ps, -0.82F, -0.2F, -0.8F, 0.82F, 0.55F, 0.8F, FER);
            pave(ps, -0.86F, -0.28F, -0.84F, 0.86F, -0.14F, 0.84F, FER_CLAIR);
            for (int k = -1; k <= 1; k++) {
                ps.pushPose();
                tourner(ps, -0.3F, 0.5F, k * 0.48F, 0, 0, 0.45F);
                pave(ps, -0.1F, 0, -0.1F, 0.1F, 0.75F, 0.1F, FER_CLAIR);
                pave(ps, -0.05F, 0.7F, -0.05F, 0.05F, 0.95F, 0.05F, 0xC8C8D0);
                ps.popPose();
            }
        } else {
            // des anneaux de chaîne autour de l'épaule
            pave(ps, -0.56F, -0.15F, -0.56F, 0.56F, 0.05F, 0.56F, FER_CLAIR);
        }
        tourner(ps, 0, -1.6F, 0, coude, 0, 0);
        pave(ps, -0.56F, -1.55F, -0.56F, 0.56F, 0.05F, 0.56F, PEAU);
        pave(ps, -0.62F, -1.25F, -0.62F, 0.62F, -0.45F, 0.62F, FER);
        pave(ps, -0.63F, -0.9F, -0.63F, 0.63F, -0.8F, 0.63F, lave, true);
        pave(ps, -0.56F, -2.3F, -0.5F, 0.56F, -1.45F, 0.62F, PEAU_SOMBRE);
        if (droit) {
            couperet(ps, arme);
        }
        ps.popPose();
    }

    /** Le couperet : un long manche de cuir, une lame de fer noir large comme une porte, des runes rouges qui brûlent. */
    private static void couperet(PoseStack ps, float arme) {
        ps.pushPose();
        tourner(ps, 0, -1.85F, 0.05F, arme, 0, 0);
        pave(ps, -0.14F, -0.14F, -0.7F, 0.14F, 0.14F, 1.05F, CUIR);
        pave(ps, -0.2F, -0.2F, -0.85F, 0.2F, 0.2F, -0.65F, OR);
        pave(ps, -0.22F, -0.3F, 0.95F, 0.22F, 0.3F, 1.1F, FER_CLAIR);
        // la lame
        pave(ps, -0.1F, -1.35F, 1.05F, 0.1F, 0.3F, 4.1F, FER);
        pave(ps, -0.08F, -1.5F, 1.2F, 0.08F, -1.3F, 4.1F, 0x9A9AA6);
        pave(ps, -0.1F, -1.5F, 3.7F, 0.1F, 0.3F, 4.25F, FER);
        // les runes : une rainure rouge qui brûle des deux côtés
        for (int k = 0; k < 5; k++) {
            float z = 1.4F + k * 0.52F;
            pave(ps, -0.12F, -0.7F, z, 0.12F, -0.35F, z + 0.32F, 0xFF2A18, true);
        }
        pave(ps, -0.115F, -0.56F, 1.35F, 0.115F, -0.49F, 3.95F, 0xC0180C, true);
        ps.popPose();
    }

    private ModeleOgre() {
    }
}
