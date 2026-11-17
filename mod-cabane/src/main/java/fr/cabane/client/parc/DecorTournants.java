package fr.cabane.client.parc;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import fr.cabane.parc.Maneges;
import fr.cabane.parc.NacelleParc;
import fr.cabane.parc.Tournants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * Ce qui tourne autour des nacelles, dessiné par le client à partir de la même horloge qu'elles : la Grande Roue
 * (ses deux cercles, ses rayons, ses ampoules qui clignotent), le plateau, le pilier à miroirs et le toit rayé du
 * Carrousel, le grand plateau et les trois petits des Tasses, et les deux bras de la Toupie.
 */
final class DecorTournants {
    private static final int SOL = Maneges.SOL;
    private static final double TAU = Math.PI * 2;

    private DecorTournants() {
    }

    /** Le temps de chaque manège (secondes depuis le départ), lu sur une de ses nacelles ; -1 s'il n'y en a pas ici. */
    private static double[] temps(Minecraft mc, float partiel) {
        double[] t = {-1, -1, -1, -1, -1, -1};
        for (Entity e : mc.level.entitiesForRendering()) {
            if (e instanceof NacelleParc n && t[n.manege()] < 0) {
                t[n.manege()] = n.temps(partiel);
            }
        }
        return t;
    }

    /** Un pavé de centre o + X·lx + Y·ly + Z·lz, d'axes X, Y, Z et de demi-côtés h. */
    private static void boite(VertexConsumer c, PoseStack.Pose pose, Vec3 cam, double ox, double oy, double oz, float[] X, float[] Y, float[] Z,
            double lx, double ly, double lz, float hx, float hy, float hz, int rgb, int lum) {
        Boites.boite(c, pose, (float) (ox + X[0] * lx + Y[0] * ly + Z[0] * lz - cam.x), (float) (oy + X[1] * lx + Y[1] * ly + Z[1] * lz - cam.y),
                (float) (oz + X[2] * lx + Y[2] * ly + Z[2] * lz - cam.z), X[0], X[1], X[2], Y[0], Y[1], Y[2], Z[0], Z[1], Z[2], hx, hy, hz, rgb, lum);
    }

    /** Un repère tourné de a autour de la verticale. */
    private static float[][] lacet(double a) {
        float c = (float) Math.cos(a), s = (float) Math.sin(a);
        return new float[][]{{c, 0, -s}, {0, 1, 0}, {s, 0, c}};
    }

    private static int lum(Minecraft mc, double x, double y, double z) {
        return LevelRenderer.getLightColor(mc.level, BlockPos.containing(x, y, z));
    }

    private static boolean pres(Vec3 cam, double x, double z, double r) {
        return (cam.x - x) * (cam.x - x) + (cam.z - z) * (cam.z - z) < r * r;
    }

    static void dessiner(VertexConsumer c, PoseStack.Pose pose, Vec3 cam, float partiel) {
        Minecraft mc = Minecraft.getInstance();
        double[] t = temps(mc, partiel);
        double monde = (mc.level.getGameTime() + partiel) / 20.0;
        if (pres(cam, Tournants.ROUE_X, Tournants.ROUE_Z, 200)) {
            roue(mc, c, pose, cam, monde);
        }
        if (t[Tournants.CARROUSEL] >= 0 && pres(cam, Tournants.CARROUSEL_X, Tournants.CARROUSEL_Z, 120)) {
            carrousel(mc, c, pose, cam, t[Tournants.CARROUSEL], monde);
        }
        if (t[Tournants.TASSES] >= 0 && pres(cam, Tournants.TASSES_X, Tournants.TASSES_Z, 120)) {
            tasses(mc, c, pose, cam, t[Tournants.TASSES], monde);
        }
        if (t[Tournants.TOUPIE] >= 0 && pres(cam, Tournants.TOUPIE_X, Tournants.TOUPIE_Z, 150)) {
            toupie(mc, c, pose, cam, t[Tournants.TOUPIE]);
        }
    }

    private static void roue(Minecraft mc, VertexConsumer c, PoseStack.Pose pose, Vec3 cam, double monde) {
        double cx = Tournants.ROUE_X, cy = Tournants.ROUE_Y, cz = Tournants.ROUE_Z, R = Tournants.ROUE_R, ang = Tournants.angleRoue(monde);
        int l = lum(mc, cx, cy, cz - 3);
        float[] Z = {0, 0, 1};
        boolean on = ((int) Math.floor(monde * 3) & 1) == 0;
        for (int s = -1; s <= 1; s += 2) {
            double z = cz + s * 1.6;
            for (int i = 0; i < 72; i++) {
                double th = ang + i * TAU / 72;
                float[] tg = {(float) -Math.sin(th), (float) Math.cos(th), 0}, rd = {(float) Math.cos(th), (float) Math.sin(th), 0};
                boite(c, pose, cam, cx, cy, z, tg, rd, Z, 0, R, 0, 0.92F, 0.32F, 0.32F, 0xFF5C8A, l);
            }
            for (int i = 0; i < 40; i++) {
                double th = ang + i * TAU / 40;
                float[] tg = {(float) -Math.sin(th), (float) Math.cos(th), 0}, rd = {(float) Math.cos(th), (float) Math.sin(th), 0};
                boite(c, pose, cam, cx, cy, z, tg, rd, Z, 0, 7, 0, 0.6F, 0.22F, 0.22F, 0xFFD23F, l);
            }
            for (int k = 0; k < 16; k++) {
                double th = ang + k * TAU / 16;
                float[] tg = {(float) -Math.sin(th), (float) Math.cos(th), 0}, rd = {(float) Math.cos(th), (float) Math.sin(th), 0};
                boite(c, pose, cam, cx, cy, z, tg, rd, Z, 0, R / 2, 0, 0.11F, (float) R / 2, 0.11F, 0xFFFFFF, l);
            }
            for (int k = 0; k < 64; k++) {
                double th = ang + k * TAU / 64;
                boolean allume = ((k & 1) == 0) == on;
                boite(c, pose, cam, cx + Math.cos(th) * (R + 0.45), cy + Math.sin(th) * (R + 0.45), cz + s * 1.9, new float[]{1, 0, 0}, new float[]{0, 1, 0}, Z,
                        0, 0, 0, 0.18F, 0.18F, 0.18F, allume ? 0xFFFFFF : 0x8A6A2A, allume ? Boites.PLEIN : l);
            }
        }
        float[][] r = lacet(0);
        boite(c, pose, cam, cx, cy, cz, r[0], r[1], r[2], 0, 0, 0, 1.3F, 1.3F, 2.1F, 0xFFD23F, l);
    }

    private static void carrousel(Minecraft mc, VertexConsumer c, PoseStack.Pose pose, Vec3 cam, double t, double monde) {
        double cx = Tournants.CARROUSEL_X, cz = Tournants.CARROUSEL_Z, th = Tournants.plateauCarrousel(t);
        int l = lum(mc, cx + 10, SOL + 1, cz);
        for (int i = 0; i < 4; i++) {
            float[][] r = lacet(th + i * Math.PI / 8);
            boite(c, pose, cam, cx, SOL, cz, r[0], r[1], r[2], 0, 0.22, 0, 8.6F, 0.17F, 8.6F, 0xF6E7C8, l);
            boite(c, pose, cam, cx, SOL, cz, r[0], r[1], r[2], 0, 7.6, 0, 9.4F, 0.4F, 9.4F, 0xE8333F, l);
        }
        for (int i = 0; i < 2; i++) {
            float[][] r = lacet(th + i * Math.PI / 4);
            boite(c, pose, cam, cx, SOL, cz, r[0], r[1], r[2], 0, 3.8, 0, 1.25F, 3.4F, 1.25F, 0xFFD23F, l);
        }
        for (int k = 0; k < 8; k++) {
            float[][] r = lacet(th + k * TAU / 8);
            boite(c, pose, cam, cx, SOL, cz, r[0], r[1], r[2], 0, 4.0, 1.3, 0.3F, 2.8F, 0.08F, 0xCFE8FF, Boites.PLEIN);
        }
        boolean on = ((int) Math.floor(monde * 2) & 1) == 0;
        for (int k = 0; k < 32; k++) {
            float[][] r = lacet(th + k * TAU / 32);
            boolean allume = ((k & 1) == 0) == on;
            boite(c, pose, cam, cx, SOL, cz, r[0], r[1], r[2], 0, 7.6, 9.75, 0.15F, 0.15F, 0.15F, allume ? 0xFFE9A8 : 0xFFFFFF, allume ? Boites.PLEIN : l);
        }
        // le toit rayé rouge et blanc, en gradins
        for (int k = 0; k < 6; k++) {
            double rr = 9.6 * (1 - k / 6.5);
            for (int s = 0; s < 16; s++) {
                float[][] r = lacet(th + s * TAU / 16);
                boite(c, pose, cam, cx, SOL, cz, r[0], r[1], r[2], 0, 8.3 + k * 0.6, rr / 2, (float) (rr * Math.tan(Math.PI / 16) + 0.06), 0.3F, (float) rr / 2,
                        (s & 1) == 0 ? 0xE8333F : 0xFFFFFF, l);
            }
        }
        float[][] r = lacet(th);
        boite(c, pose, cam, cx, SOL, cz, r[0], r[1], r[2], 0, 11.9, 0, 0.6F, 0.6F, 0.6F, 0xFFD23F, l);
        boite(c, pose, cam, cx, SOL, cz, r[0], r[1], r[2], 0, 13.2, 0, 0.05F, 1F, 0.05F, 0xDDDDDD, l);
        boite(c, pose, cam, cx, SOL, cz, r[0], r[1], r[2], 0.6, 13.7, 0, 0.6F, 0.35F, 0.03F, 0x2EC4B6, l);
    }

    private static void tasses(Minecraft mc, VertexConsumer c, PoseStack.Pose pose, Vec3 cam, double t, double monde) {
        double cx = Tournants.TASSES_X, cz = Tournants.TASSES_Z, g = Tournants.grandPlateau(t), p = g + Tournants.petitPlateau(t);
        int l = lum(mc, cx + 12, SOL + 1, cz);
        for (int i = 0; i < 4; i++) {
            float[][] r = lacet(g + i * Math.PI / 8);
            boite(c, pose, cam, cx, SOL, cz, r[0], r[1], r[2], 0, 0.47, 0, 10.8F, 0.08F, 10.8F, 0xC9B6F2, l);
        }
        boolean on = ((int) Math.floor(monde * 2) & 1) == 0;
        for (int k = 0; k < 24; k++) {
            float[][] r = lacet(g + k * TAU / 24);
            boolean allume = ((k & 1) == 0) == on;
            boite(c, pose, cam, cx, SOL, cz, r[0], r[1], r[2], 0, 0.62, 10.7, 0.25F, 0.08F, 0.25F, allume ? 0xFF8FB1 : 0xFFFFFF, allume ? Boites.PLEIN : l);
        }
        int[] couleurs = {0xFFD1E3, 0xD1FFF0, 0xFFF4C2};
        for (int i = 0; i < 3; i++) {
            double a = i * TAU / 3 + g, px = cx + Math.cos(a) * 6.3, pz = cz + Math.sin(a) * 6.3;
            for (int j = 0; j < 4; j++) {
                float[][] r = lacet(p + j * Math.PI / 8);
                boite(c, pose, cam, px, SOL, pz, r[0], r[1], r[2], 0, 0.68, 0, 4.1F, 0.1F, 4.1F, couleurs[i], l);
            }
        }
    }

    private static void toupie(Minecraft mc, VertexConsumer c, PoseStack.Pose pose, Vec3 cam, double t) {
        double a = Tournants.brasToupie(t);
        float co = (float) Math.cos(a), si = (float) Math.sin(a);
        float[] X = {1, 0, 0}, Y = {0, co, si}, Z = {0, -si, co};
        double ox = Tournants.TOUPIE_X, oy = Tournants.TOUPIE_Y, oz = Tournants.TOUPIE_Z, L = Tournants.TOUPIE_L;
        int l = lum(mc, ox, SOL + 3, oz + 6);
        for (int s = -1; s <= 1; s += 2) {
            boite(c, pose, cam, ox, oy, oz, X, Y, Z, s * 6.3, -(L - 1) / 2 + 0.5, 0, 0.45F, (float) (L + 1) / 2, 0.5F, 0xE8333F, l);
            boite(c, pose, cam, ox, oy, oz, X, Y, Z, s * 6.3, 2.6, 0, 0.8F, 1.1F, 0.8F, 0x3A3A4A, l);
            boite(c, pose, cam, ox, oy, oz, X, Y, Z, s * 6.78, -(L - 1) / 2 + 0.5, 0, 0.03F, (float) L / 2, 0.15F, 0x00F0FF, Boites.PLEIN);
            boite(c, pose, cam, ox, oy, oz, X, Y, Z, s * 6.3, 0, 0, 0.7F, 0.7F, 0.7F, 0x555555, l);
            boite(c, pose, cam, ox, oy, oz, X, Y, Z, s * 6.3, -L, 0, 0.5F, 0.5F, 0.5F, 0x555555, l);
        }
    }
}
