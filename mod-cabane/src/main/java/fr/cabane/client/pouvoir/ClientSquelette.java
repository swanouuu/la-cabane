package fr.cabane.client.pouvoir;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import fr.cabane.Cabane;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;

/**
 * Le squelette de la bête aux os (Hachibi 2, niveau 3), d'après les maquettes validées (docs/hachibi2) : un crâne qui
 * enferme la tête (percé aux yeux et à la gueule, où brillent les yeux et les dents de la bête), une mâchoire ouverte et
 * ses dents, l'arcade, une crête et des pommettes ; le sternum, trois côtes autour du torse, la colonne et ses pointes,
 * le bassin ; un os le long de chaque bras, une épaulière, le coude et trois longues griffes au bout de la main ; un os
 * sur chaque jambe, le genou et des griffes devant le pied. Chaque os suit sa partie du corps.
 * <p>
 * Les os sont décrits comme dans les maquettes : en pixels du modèle, les pieds à y = 0, l'avant vers +z, la gauche du
 * personnage vers +x, dans la pose de repos.
 */
public final class ClientSquelette {
    private static final ResourceLocation BLANC = Cabane.id("textures/marque/blanc.png");
    private static final int OS = 0xFFB0BED2;
    private static final int DENT = 0xFFECECE4;

    /** Un os : une boîte (de a à b) et sa couleur. */
    private record Os(float x0, float y0, float z0, float x1, float y1, float z1, int couleur) {
    }

    private static Os os(double x0, double y0, double z0, double x1, double y1, double z1) {
        return os(x0, y0, z0, x1, y1, z1, OS);
    }

    private static Os os(double x0, double y0, double z0, double x1, double y1, double z1, int couleur) {
        return new Os((float) x0, (float) y0, (float) z0, (float) x1, (float) y1, (float) z1, couleur);
    }

    /** Une face du crâne, à plat sur le devant de la coque (de x0 à x1, de y0 à y1, en z = 4,6). */
    private record Face(float x0, float y0, float x1, float y1) {
    }

    /** Le devant du crâne, percé aux yeux (x de 0,4 à 3,6 de chaque côté, y de 27 à 30) et à la gueule (|x| < 3,4, y < 26,6). */
    private static final Face[] DEVANT = {
            new Face(-4.6F, 30, 4.6F, 32.8F), new Face(-0.4F, 27, 0.4F, 30), new Face(-4.6F, 27, -3.6F, 30), new Face(3.6F, 27, 4.6F, 30),
            new Face(-4.6F, 26.6F, 4.6F, 27), new Face(-4.6F, 25.4F, -3.4F, 26.6F), new Face(3.4F, 25.4F, 4.6F, 26.6F)};

    private static java.util.List<Os> tete() {
        java.util.List<Os> l = new java.util.ArrayList<>();
        l.add(os(-4.9, 29.8, 4.4, 4.9, 30.6, 5.3));
        for (double z = -3.5; z <= 3.5; z += 1.75) {
            l.add(os(-0.6, 32.8, z - 0.6, 0.6, 33.8 + (3.5 - Math.abs(z)) * 0.25, z + 0.6));
        }
        l.add(os(4.4, 26.6, 1.5, 5.2, 28.2, 4.6));
        l.add(os(-5.2, 26.6, 1.5, -4.4, 28.2, 4.6));
        l.add(os(-4.3, 23.2, -2, 4.3, 24.4, 5.2));
        for (int x = -3; x <= 3; x++) {
            l.add(os(x - 0.35, 24.4, 4.4, x + 0.35, 25.5, 5.1, DENT));
            l.add(os(x - 0.35, 26.0, 4.4, x + 0.35, 27.0, 5.1, DENT));
        }
        return l;
    }

    private static java.util.List<Os> corps() {
        java.util.List<Os> l = new java.util.ArrayList<>();
        l.add(os(-0.6, 14.5, 2.2, 0.6, 23.5, 2.9));
        for (double y : new double[]{22, 19.5, 17}) {
            l.add(os(-4.4, y - 0.5, 2.2, 4.4, y + 0.5, 2.8));
            l.add(os(4.1, y - 0.5, -2.4, 4.7, y + 0.5, 2.8));
            l.add(os(-4.7, y - 0.5, -2.4, -4.1, y + 0.5, 2.8));
        }
        for (double y = 12.5; y < 24; y += 1.7) {
            l.add(os(-0.8, y, -2.9, 0.8, y + 1.2, -2.1));
            l.add(os(-0.35, y + 0.3, -4.1, 0.35, y + 0.9, -2.9));
        }
        l.add(os(-4.5, 12, -2.6, 4.5, 13.2, 2.6));
        return l;
    }

    /** Un bras (s = 1 : le gauche, -1 : le droit), plus près du corps pour les bras fins. */
    private static java.util.List<Os> bras(int s, boolean fin) {
        double d = fin ? s * -1 : 0;
        java.util.List<Os> l = new java.util.ArrayList<>();
        l.add(os(s > 0 ? 8.1 + d : -9.5 + d, 13.5, -0.7, s > 0 ? 9.5 + d : -8.1 + d, 23.5, 0.7));
        l.add(os(s > 0 ? 3.6 : -9.2 + d, 23.6, -2.6, s > 0 ? 9.2 + d : -3.6, 25, 2.6));
        l.add(os(s > 0 ? 7.8 + d : -9.8 + d, 17.4, -1.1, s > 0 ? 9.8 + d : -7.8 + d, 19, 1.1));
        for (double dz : new double[]{-1.3, 0, 1.3}) {
            double x = s * 6 + (dz == 0 ? 0 : s * 0.3) + d * 0.5;
            l.add(os(x - 0.4, 8.6, dz - 0.4, x + 0.4, 12, dz + 0.4));
            l.add(os(x - 0.3, 7.2, dz + 0.1, x + 0.3, 8.6, dz + 1.1));
        }
        return l;
    }

    private static java.util.List<Os> jambe(int s) {
        java.util.List<Os> l = new java.util.ArrayList<>();
        l.add(os(s > 0 ? 4.1 : -5.3, 1, -0.6, s > 0 ? 5.3 : -4.1, 11.5, 0.6));
        l.add(os(s * 2 - 1.3, 5.2, 2, s * 2 + 1.3, 7, 2.9));
        for (double dx : new double[]{-1.2, 0, 1.2}) {
            l.add(os(s * 2 + dx - 0.3, 0, 2, s * 2 + dx + 0.3, 0.6, 3.6));
        }
        return l;
    }

    private static final java.util.List<Os> TETE = tete();
    private static final java.util.List<Os> CORPS = corps();
    private static final java.util.List<Os> BRAS_D = bras(-1, false), BRAS_G = bras(1, false);
    private static final java.util.List<Os> BRAS_D_FIN = bras(-1, true), BRAS_G_FIN = bras(1, true);
    private static final java.util.List<Os> JAMBE_D = jambe(-1), JAMBE_G = jambe(1);

    /**
     * Le squelette sur le modèle tel qu'il est posé (la tête déjà grossie si elle l'est), k de 0 à 1 pendant qu'il
     * apparaît (chaque os grandit autour de son centre).
     */
    static void dessiner(PlayerModel<?> m, PoseStack ps, MultiBufferSource buffers, int lumiere, boolean fin, float k) {
        if (k <= 0.01F) {
            return;
        }
        VertexConsumer c = buffers.getBuffer(RenderType.entityCutoutNoCull(BLANC));
        partie(m.head, ps, c, 0, 24, 0, TETE, lumiere, k, true);
        partie(m.body, ps, c, 0, 24, 0, CORPS, lumiere, k, false);
        partie(m.rightArm, ps, c, -5, 22, 0, fin ? BRAS_D_FIN : BRAS_D, lumiere, k, false);
        partie(m.leftArm, ps, c, 5, 22, 0, fin ? BRAS_G_FIN : BRAS_G, lumiere, k, false);
        partie(m.rightLeg, ps, c, -1.9F, 12, 0, JAMBE_D, lumiere, k, false);
        partie(m.leftLeg, ps, c, 1.9F, 12, 0, JAMBE_G, lumiere, k, false);
    }

    /** Les os d'une partie, dans son repère : (px, py, pz) est son pivot, en coordonnées des maquettes. */
    private static void partie(ModelPart p, PoseStack ps, VertexConsumer c, float px, float py, float pz, java.util.List<Os> os, int lumiere,
            float k, boolean crane) {
        ps.pushPose();
        p.translateAndRotate(ps);
        ps.scale(1 / 16F, 1 / 16F, 1 / 16F);
        // des maquettes (y vers le haut, l'avant vers +z) au modèle (y vers le bas, l'avant vers -z)
        ps.scale(1, -1, -1);
        ps.translate(-px, -py, -pz);
        PoseStack.Pose pose = ps.last();
        if (crane) {
            crane(c, pose, lumiere, k);
        }
        for (Os o : os) {
            float cx = (o.x0 + o.x1) / 2, cy = (o.y0 + o.y1) / 2, cz = (o.z0 + o.z1) / 2;
            boite(c, pose, cx + (o.x0 - cx) * k, cy + (o.y0 - cy) * k, cz + (o.z0 - cz) * k, cx + (o.x1 - cx) * k, cy + (o.y1 - cy) * k,
                    cz + (o.z1 - cz) * k, o.couleur, lumiere);
        }
        ps.popPose();
    }

    /** La coque du crâne autour de la tête : le dessus, les côtés, l'arrière, et le devant percé. */
    private static void crane(VertexConsumer c, PoseStack.Pose p, int lumiere, float k) {
        float x0 = -4.6F * k, x1 = 4.6F * k, y0 = 28.6F - 3.2F * k, y1 = 28.6F + 4.2F * k, z0 = -4.6F * k, z1 = 4.6F * k;
        face(c, p, lumiere, OS, 0, 1, 0, x0, y1, z0, x0, y1, z1, x1, y1, z1, x1, y1, z0);
        face(c, p, lumiere, OS, 0, 0, -1, x0, y0, z0, x0, y1, z0, x1, y1, z0, x1, y0, z0);
        face(c, p, lumiere, OS, -1, 0, 0, x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0);
        face(c, p, lumiere, OS, 1, 0, 0, x1, y0, z0, x1, y1, z0, x1, y1, z1, x1, y0, z1);
        for (Face f : DEVANT) {
            float a0 = f.x0 * k, a1 = f.x1 * k, b0 = 28.6F + (f.y0 - 28.6F) * k, b1 = 28.6F + (f.y1 - 28.6F) * k;
            face(c, p, lumiere, OS, 0, 0, 1, a0, b0, z1, a1, b0, z1, a1, b1, z1, a0, b1, z1);
        }
    }

    private static void boite(VertexConsumer c, PoseStack.Pose p, float x0, float y0, float z0, float x1, float y1, float z1, int couleur, int lumiere) {
        face(c, p, lumiere, couleur, 0, -1, 0, x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1);
        face(c, p, lumiere, couleur, 0, 1, 0, x0, y1, z0, x0, y1, z1, x1, y1, z1, x1, y1, z0);
        face(c, p, lumiere, couleur, 0, 0, -1, x0, y0, z0, x0, y1, z0, x1, y1, z0, x1, y0, z0);
        face(c, p, lumiere, couleur, 0, 0, 1, x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1);
        face(c, p, lumiere, couleur, -1, 0, 0, x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0);
        face(c, p, lumiere, couleur, 1, 0, 0, x1, y0, z0, x1, y1, z0, x1, y1, z1, x1, y0, z1);
    }

    private static void face(VertexConsumer c, PoseStack.Pose p, int lumiere, int couleur, float nx, float ny, float nz, float... q) {
        // un peu plus sombre en dessous et sur les côtés, comme dans les maquettes
        float ombre = ny > 0 ? 1 : ny < 0 ? 0.7F : nx != 0 ? 0.86F : 0.94F;
        int r = Math.round((couleur >> 16 & 255) * ombre), g = Math.round((couleur >> 8 & 255) * ombre), b = Math.round((couleur & 255) * ombre);
        for (int i = 0; i < 4; i++) {
            c.addVertex(p, q[i * 3], q[i * 3 + 1], q[i * 3 + 2]).setColor(r, g, b, 255).setUv(0.5F, 0.5F).setOverlay(OverlayTexture.NO_OVERLAY)
                    .setLight(lumiere).setNormal(p, nx, ny, nz);
        }
    }

    private ClientSquelette() {
    }
}
