package fr.cabane.client.pouvoir;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import fr.cabane.Cabane;
import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.resources.ResourceLocation;

/**
 * Un modèle fait de faces colorées, calculé d'avance par les générateurs des planches (docs/fruits/generateur/jeu3.mjs) :
 * pour chaque face, ses 4 coins, sa normale, sa couleur, et si elle brille (la lave : de jour comme de nuit). Un fichier
 * en contient plusieurs (un par partie du joueur, pour les plaques).
 */
final class Maillage {
    private final float[] coins;
    private final float[] normales;
    private final byte[] couleurs;

    private Maillage(float[] coins, float[] normales, byte[] couleurs) {
        this.coins = coins;
        this.normales = normales;
        this.couleurs = couleurs;
    }

    /** Les modèles de ce fichier, ou null s'il est illisible. */
    static Maillage[] lire(ResourceLocation fichier) {
        try (var flux = new DataInputStream(new BufferedInputStream(Minecraft.getInstance().getResourceManager().open(fichier)))) {
            Maillage[] l = new Maillage[flux.readInt()];
            for (int g = 0; g < l.length; g++) {
                int n = flux.readInt();
                float[] c = new float[n * 12], nor = new float[n * 3];
                byte[] col = new byte[n * 4];
                for (int f = 0; f < n; f++) {
                    for (int k = 0; k < 12; k++) {
                        c[f * 12 + k] = flux.readFloat();
                    }
                    for (int k = 0; k < 3; k++) {
                        nor[f * 3 + k] = flux.readFloat();
                    }
                    flux.readFully(col, f * 4, 4);
                }
                l[g] = new Maillage(c, nor, col);
            }
            return l;
        } catch (IOException | RuntimeException e) {
            Cabane.LOGGER.warn("Modèle illisible : {}", fichier, e);
            return null;
        }
    }

    /** Les faces, à l'échelle e (1/16 pour les modèles faits en pixels du joueur) ; ce qui brille ignore l'ombre du lieu. */
    void dessiner(VertexConsumer c, PoseStack.Pose pose, int lumiere, int recouvre, float e) {
        for (int f = 0, n = normales.length / 3; f < n; f++) {
            int r = couleurs[f * 4] & 255, v = couleurs[f * 4 + 1] & 255, b = couleurs[f * 4 + 2] & 255;
            int l = couleurs[f * 4 + 3] != 0 ? LightTexture.FULL_BRIGHT : lumiere;
            float nx = normales[f * 3], ny = normales[f * 3 + 1], nz = normales[f * 3 + 2];
            for (int q = 0; q < 4; q++) {
                int i = f * 12 + q * 3;
                c.addVertex(pose, coins[i] * e, coins[i + 1] * e, coins[i + 2] * e).setColor(r, v, b, 255).setUv(0.5F, 0.5F).setOverlay(recouvre)
                        .setLight(l).setNormal(pose, nx, ny, nz);
            }
        }
    }
}
