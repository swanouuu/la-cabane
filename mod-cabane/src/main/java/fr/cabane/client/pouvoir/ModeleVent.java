package fr.cabane.client.pouvoir;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import fr.cabane.Cabane;
import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * Un modèle du Vent, calculé d'avance par le générateur des planches (docs/vent/generateur/jeu.mjs) : pour chaque face,
 * ses 4 coins (en pixels), sa normale, sa couleur et sa sorte : pleine (éclairée par le lieu), pleine qui brille, un cœur
 * de vent qui brille (avec sa lueur), ou un voile transparent (les ailes de vitrail de la fée, les plumes du faucon).
 * Un fichier en contient un par partie du joueur (les pièces portées) ou un seul (la fée, le faucon, une lance).
 */
final class ModeleVent {
    static final ResourceLocation BLANC = Cabane.id("textures/marque/blanc.png");
    /** Les lueurs des cœurs : le vent, le vent doux (la fée), les paillettes. */
    private static final float[][] LUEURS = {{80 / 255F, 1F, 150 / 255F}, {110 / 255F, 240 / 255F, 170 / 255F}, {1F, 230 / 255F, 120 / 255F}};

    private final float[] coins;
    private final float[] normales;
    private final byte[] couleurs;

    private ModeleVent(float[] coins, float[] normales, byte[] couleurs) {
        this.coins = coins;
        this.normales = normales;
        this.couleurs = couleurs;
    }

    private static final Map<String, ModeleVent[]> LUS = new HashMap<>();

    /** Les modèles de ce fichier (assets/cabane/vent/nom.bin), lus une fois ; un tableau vide s'il est illisible. */
    static ModeleVent[] lire(String nom) {
        return lire("vent", nom);
    }

    /** Les modèles de assets/cabane/dossier/nom.bin (le Vent, Kaguya), lus une fois. */
    static ModeleVent[] lire(String dossier, String nom) {
        return LUS.computeIfAbsent(dossier + "/" + nom, n -> {
            ResourceLocation fichier = Cabane.id(n + ".bin");
            try (var flux = new DataInputStream(new BufferedInputStream(Minecraft.getInstance().getResourceManager().open(fichier)))) {
                ModeleVent[] l = new ModeleVent[flux.readInt()];
                for (int g = 0; g < l.length; g++) {
                    int f = flux.readInt();
                    float[] c = new float[f * 12], nor = new float[f * 3];
                    byte[] col = new byte[f * 4];
                    for (int i = 0; i < f; i++) {
                        for (int k = 0; k < 12; k++) {
                            c[i * 12 + k] = flux.readFloat();
                        }
                        for (int k = 0; k < 3; k++) {
                            nor[i * 3 + k] = flux.readFloat();
                        }
                        flux.readFully(col, i * 4, 4);
                    }
                    l[g] = new ModeleVent(c, nor, col);
                }
                return l;
            } catch (IOException | RuntimeException e) {
                Cabane.LOGGER.warn("Modèle du vent illisible : {}", fichier, e);
                return new ModeleVent[0];
            }
        });
    }

    static ModeleVent premier(String nom) {
        ModeleVent[] l = lire(nom);
        return l.length == 0 ? null : l[0];
    }

    /**
     * Les faces, à l'échelle e (1/16 pour les pièces faites en pixels du joueur). {@code alpha} : tout s'efface ensemble
     * (il apparaît, il s'en va). Le plein dans un tampon, les voiles et les cœurs dans un autre, leur lueur dans un troisième.
     */
    void dessiner(MultiBufferSource buffers, PoseStack.Pose pose, int lumiere, float e, float alpha) {
        dessiner(buffers, pose, lumiere, e, alpha, null);
    }

    /** Déplace un point du modèle (en pixels) avant de le dessiner : ce qui ondule (la queue de Kaguya). */
    interface Deformation {
        void appliquer(float x, float y, float z, Vector3f sortie);
    }

    private static final Vector3f DEFORME = new Vector3f();

    /** Pareil, chaque point passant d'abord par la déformation (null : aucune). Seules les faces pleines et les voiles la suivent. */
    void dessiner(MultiBufferSource buffers, PoseStack.Pose pose, int lumiere, float e, float alpha, Deformation d) {
        // trois tampons, l'un après l'autre : en demander un autre peut fermer celui d'avant
        int n = normales.length / 3;
        if (alpha >= 0.99F) {
            VertexConsumer plein = buffers.getBuffer(RenderType.entityCutoutNoCull(BLANC));
            for (int f = 0; f < n; f++) {
                int sorte = couleurs[f * 4 + 3] & 255;
                if (sorte <= 1) {
                    face(plein, pose, f, e, 255, sorte == 1 ? LightTexture.FULL_BRIGHT : lumiere, d);
                }
            }
        }
        // les voiles (ou ce qui s'efface) : transparents ; les cœurs : pleins et qui brillent
        VertexConsumer voile = buffers.getBuffer(RenderType.entityTranslucentEmissive(BLANC));
        for (int f = 0; f < n; f++) {
            int sorte = couleurs[f * 4 + 3] & 255;
            if (sorte <= 1 && alpha >= 0.99F) {
                continue;
            }
            int a = sorte >= 8 ? Math.round(sorte * alpha) : Math.round(255 * alpha);
            if (a > 2) {
                face(voile, pose, f, e, a, sorte >= 1 ? LightTexture.FULL_BRIGHT : lumiere, d);
            }
        }
        // la lueur des cœurs : le même trait trois fois plus large (dans le sens de sa largeur, du coin 0 au coin 1), de la
        // lumière qui s'ajoute
        VertexConsumer lueur = buffers.getBuffer(RenderType.lightning());
        Matrix4f m = pose.pose();
        Vector3f p = new Vector3f();
        for (int f = 0; f < n; f++) {
            int sorte = couleurs[f * 4 + 3] & 255;
            if (sorte < 2 || sorte > 4) {
                continue;
            }
            float[] c = LUEURS[sorte - 2];
            int i0 = f * 12;
            float wx = coins[i0 + 3] - coins[i0], wy = coins[i0 + 4] - coins[i0 + 1], wz = coins[i0 + 5] - coins[i0 + 2];
            float wl = (float) Math.sqrt(wx * wx + wy * wy + wz * wz);
            if (wl < 1e-5F) {
                continue;
            }
            wx /= wl;
            wy /= wl;
            wz /= wl;
            float cx = 0, cy = 0, cz = 0;
            for (int q = 0; q < 4; q++) {
                cx += coins[i0 + q * 3] / 4;
                cy += coins[i0 + q * 3 + 1] / 4;
                cz += coins[i0 + q * 3 + 2] / 4;
            }
            for (int q = 0; q < 4; q++) {
                int i = i0 + q * 3;
                float ox = coins[i] - cx, oy = coins[i + 1] - cy, oz = coins[i + 2] - cz;
                float le = (ox * wx + oy * wy + oz * wz) * 2.2F;
                m.transformPosition((coins[i] + wx * le) * e, (coins[i + 1] + wy * le) * e, (coins[i + 2] + wz * le) * e, p);
                lueur.addVertex(p.x, p.y, p.z).setColor(c[0], c[1], c[2], 0.5F * alpha);
            }
        }
    }

    private void face(VertexConsumer c, PoseStack.Pose pose, int f, float e, int a, int l, Deformation d) {
        int r = couleurs[f * 4] & 255, v = couleurs[f * 4 + 1] & 255, b = couleurs[f * 4 + 2] & 255;
        float nx = normales[f * 3], ny = normales[f * 3 + 1], nz = normales[f * 3 + 2];
        for (int q = 0; q < 4; q++) {
            int i = f * 12 + q * 3;
            float x = coins[i], y = coins[i + 1], z = coins[i + 2];
            if (d != null) {
                d.appliquer(x, y, z, DEFORME);
                x = DEFORME.x;
                y = DEFORME.y;
                z = DEFORME.z;
            }
            c.addVertex(pose, x * e, y * e, z * e).setColor(r, v, b, a).setUv(0.5F, 0.5F)
                    .setOverlay(OverlayTexture.NO_OVERLAY).setLight(l).setNormal(pose, nx, ny, nz);
        }
    }
}
