package fr.cabane.client.parc;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import fr.cabane.parc.PortailGaulois;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import net.minecraft.client.Minecraft;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * Le portail gaulois de l'événement (/parc event), chez le joueur : un grand anneau doré debout au nord de la place, un
 * tourbillon jaune et orange sur fond vert sombre, douze pierres vertes autour, et le ciel fissuré tout autour. Il
 * s'ouvre en grandissant d'un coup la première fois qu'on le voit.
 */
final class DecorEvenement {
    private static final double TAU = Math.PI * 2;
    private static final List<float[]> FISSURES = new ArrayList<>();
    private static long vuDepuis = -1;

    private DecorEvenement() {
    }

    private static void fissures() {
        if (!FISSURES.isEmpty()) {
            return;
        }
        Random r = new Random(1789);
        for (int k = 0; k < 9; k++) {
            double x = 0, y = 0, a = k / 9.0 * TAU;
            for (int j = 0; j < 5; j++) {
                double nx = x + Math.cos(a) * (2.6 + r.nextDouble() * 2), ny = y + Math.sin(a) * (2.6 + r.nextDouble() * 2);
                if (j > 1) {
                    FISSURES.add(new float[]{(float) ((x + nx) / 2), (float) ((y + ny) / 2), (float) Math.hypot(nx - x, ny - y), (float) Math.atan2(ny - y, nx - x)});
                }
                x = nx;
                y = ny;
                a += (r.nextDouble() - 0.5) * 0.9;
            }
        }
    }

    /** Un pavé dans le plan du portail (x vers l'est, y vers le haut), tourné de {@code ang} dans ce plan. */
    private static void plat(VertexConsumer c, PoseStack.Pose pose, Vec3 cam, double e, double x, double y, double z, double ang, float l, float h, float ep,
            int rgb) {
        float co = (float) Math.cos(ang), si = (float) Math.sin(ang);
        Boites.boite(c, pose, (float) (PortailGaulois.X + x * e - cam.x), (float) (PortailGaulois.Y + y * e - cam.y), (float) (PortailGaulois.Z + z - cam.z),
                co, si, 0, -si, co, 0, 0, 0, 1, (float) (l * e), (float) (h * e), ep, rgb, Boites.PLEIN);
    }

    static void dessiner(VertexConsumer c, PoseStack.Pose pose, Vec3 cam, double temps) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || !PortailGaulois.ouvert(mc.level)) {
            vuDepuis = -1;
            return;
        }
        if ((cam.x - PortailGaulois.X) * (cam.x - PortailGaulois.X) + (cam.z - PortailGaulois.Z) * (cam.z - PortailGaulois.Z) > 200 * 200) {
            return;
        }
        fissures();
        long maintenant = System.currentTimeMillis();
        if (vuDepuis < 0) {
            vuDepuis = maintenant;
        }
        double k = Math.min(1, (maintenant - vuDepuis) / 2400.0);
        double e = Math.max(0.001, 1 - Math.pow(1 - k, 3) + Math.sin(k * Math.PI) * 0.12);
        // le fond vert sombre (un disque fait de carrés tournés) et l'anneau doré
        for (int i = 0; i < 4; i++) {
            plat(c, pose, cam, e, 0, 0, 0, i * Math.PI / 8 + temps * 0.2, 4.75F, 4.75F, 0.04F, 0x0D3A1C);
        }
        int or = Mth.hsvToRgb(0.12F, 0.85F, (float) (0.92 + Math.sin(temps * 4) * 0.08));
        for (int i = 0; i < 56; i++) {
            double a = i * TAU / 56;
            plat(c, pose, cam, e, Math.cos(a) * 7, Math.sin(a) * 7, 0, a + Math.PI / 2, 0.42F, 0.75F, 0.75F, or);
        }
        // le tourbillon : cinq bras de points jaunes et orange qui tournent
        for (int b = 0; b < 5; b++) {
            for (int i = 0; i < 26; i++) {
                double t = i / 26.0, a = t * 9 + b * TAU / 5 - temps * 1.4, rr = t * 6.3;
                int col = Mth.hsvToRgb((float) (0.15 - 0.08 * t), 0.9F, 1F);
                float s = (float) (0.1 + t * 0.28);
                plat(c, pose, cam, e, Math.cos(a) * rr, Math.sin(a) * rr, 0.08, a, s, s, 0.06F, col);
                plat(c, pose, cam, e, Math.cos(a) * rr, Math.sin(a) * rr, -0.08, a, s, s, 0.06F, col);
            }
        }
        // les douze pierres vertes
        for (int i = 0; i < 12; i++) {
            double a = i * TAU / 12;
            plat(c, pose, cam, e, Math.cos(a) * 8.3, Math.sin(a) * 8.3, 0, a, 0.3F, 0.3F, 0.15F, 0x9DFF6A);
        }
        // le ciel fissuré
        for (int i = 0; i < FISSURES.size(); i++) {
            float[] f = FISSURES.get(i);
            int col = Mth.hsvToRgb(0.13F, 0.55F, (float) (0.85 + Math.sin(temps * 6 + i) * 0.15));
            plat(c, pose, cam, e, f[0], f[1], -0.2, f[3], f[2] / 2, 0.09F, 0.05F, col);
        }
        if (mc.level.random.nextInt(3) == 0) {
            double a = mc.level.random.nextDouble() * TAU, rr = mc.level.random.nextDouble() * 6.5 * e;
            mc.level.addParticle(ParticleTypes.END_ROD, PortailGaulois.X + Math.cos(a) * rr, PortailGaulois.Y + Math.sin(a) * rr, PortailGaulois.Z,
                    0, 0, (mc.level.random.nextDouble() - 0.5) * 0.1);
        }
    }
}
