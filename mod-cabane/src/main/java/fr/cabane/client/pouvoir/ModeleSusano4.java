package fr.cabane.client.pouvoir;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import fr.cabane.Cabane;
import fr.cabane.pouvoir.Pouvoirs.Couleur;
import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix4f;

/**
 * Le Susano parfait en cubes (un quart de bloc), validé dans l'aperçu docs/susano4/apercu et calculé d'avance par
 * susano-voxel.mjs (MOD=1 PAS=0.25) dans assets/cabane/susano4/susano.bin : les articulations (pivot, parent), puis les
 * faces visibles de chaque cube, groupées par mélange d'articulations (a, b, poids) et à part pour les deux épées. Chaque
 * groupe devient un tampon graphique par couleur de Susano (la teinte, la lumière et la lueur y sont déjà cuites), dessiné
 * avec la matrice de son articulation : un mouvement ne coûte qu'une matrice par groupe.
 */
final class ModeleSusano4 {
    private static final ResourceLocation FICHIER = Cabane.id("susano4/susano.bin");

    /** Les articulations, dans l'ordre du fichier (comme AnimSusano4). */
    static float[][] pivots;
    static int[] parents;

    /** Un groupe de faces : ses articulations, son poids, ses faces (coin en quarts de bloc, sens, teinte, lueur, sorte). */
    record Groupe(int a, int b, float w, boolean epee, short[] coins, byte[] infos) {
        int faces() {
            return coins.length / 3;
        }
    }

    private static final List<Groupe> GROUPES = new ArrayList<>();
    private static float pas;
    private static boolean essaye;
    /** Les tampons, par couleur (corps, contour) puis par groupe. */
    private static final Map<Long, VertexBuffer[]> TAMPONS = new HashMap<>();

    static boolean charger() {
        if (!essaye) {
            essaye = true;
            try (var flux = new DataInputStream(new BufferedInputStream(Minecraft.getInstance().getResourceManager().open(FICHIER)))) {
                pas = flux.readFloat();
                int nOs = flux.readInt();
                float[][] pv = new float[nOs][3];
                int[] pa = new int[nOs];
                for (int i = 0; i < nOs; i++) {
                    for (int k = 0; k < 3; k++) {
                        pv[i][k] = flux.readFloat();
                    }
                    pa[i] = flux.readInt();
                }
                int nG = flux.readInt();
                int total = 0;
                for (int g = 0; g < nG; g++) {
                    int a = flux.readInt(), b = flux.readInt();
                    float w = flux.readFloat();
                    boolean epee = flux.readUnsignedByte() == 1;
                    int n = flux.readInt();
                    short[] coins = new short[n * 3];
                    byte[] infos = new byte[n * 4];
                    for (int f = 0; f < n; f++) {
                        coins[f * 3] = flux.readShort();
                        coins[f * 3 + 1] = flux.readShort();
                        coins[f * 3 + 2] = flux.readShort();
                        flux.readFully(infos, f * 4, 4);
                    }
                    GROUPES.add(new Groupe(a, b, w, epee, coins, infos));
                    total += n;
                }
                pivots = pv;
                parents = pa;
                Cabane.LOGGER.info("Susano parfait : {} groupes, {} faces", nG, total);
            } catch (IOException | RuntimeException e) {
                Cabane.LOGGER.warn("Susano parfait : modèle illisible", e);
                GROUPES.clear();
            }
        }
        return !GROUPES.isEmpty();
    }

    static List<Groupe> groupes() {
        return GROUPES;
    }

    // ================================================================ les tampons

    /** La lumière de l'aperçu : une lampe en haut à gauche devant, une autre derrière. */
    private static final float[] L1 = norm(-0.45F, 0.75F, 0.55F), L2 = norm(0.6F, 0.2F, -0.7F);
    private static final int[][] SENS = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
    /** Les quatre coins de chaque face (décalages du coin du cube), dans l'ordre des sens. */
    private static final int[][][] COINS = {
        {{1, 0, 0}, {1, 1, 0}, {1, 1, 1}, {1, 0, 1}},
        {{0, 0, 0}, {0, 0, 1}, {0, 1, 1}, {0, 1, 0}},
        {{0, 1, 0}, {0, 1, 1}, {1, 1, 1}, {1, 1, 0}},
        {{0, 0, 0}, {1, 0, 0}, {1, 0, 1}, {0, 0, 1}},
        {{0, 0, 1}, {1, 0, 1}, {1, 1, 1}, {0, 1, 1}},
        {{0, 0, 0}, {0, 1, 0}, {1, 1, 0}, {1, 0, 0}}};

    private static float[] norm(float x, float y, float z) {
        float l = (float) Math.sqrt(x * x + y * y + z * z);
        return new float[]{x / l, y / l, z / l};
    }

    private static VertexBuffer[] tampons(Couleur couleur) {
        long cle = (long) couleur.corps << 32 | couleur.contour & 0xffffffffL;
        VertexBuffer[] t = TAMPONS.get(cle);
        if (t != null) {
            return t;
        }
        float[] C = rgb(couleur.corps), K = rgb(couleur.contour);
        float[] sombre = {C[0] * 0.1F, C[1] * 0.1F, C[2] * 0.1F}, moyen = {C[0] * 0.75F, C[1] * 0.75F, C[2] * 0.75F};
        float[] clair = new float[3], lueur = new float[3];
        for (int k = 0; k < 3; k++) {
            clair[k] = C[k] + (K[k] - C[k]) * 0.65F;
            lueur[k] = K[k] + (1 - K[k]) * 0.15F;
        }
        t = new VertexBuffer[GROUPES.size()];
        for (int g = 0; g < GROUPES.size(); g++) {
            Groupe gr = GROUPES.get(g);
            int n = gr.faces();
            try (ByteBufferBuilder octets = new ByteBufferBuilder(n * 4 * 16 + 256)) {
                BufferBuilder bb = new BufferBuilder(octets, VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
                for (int f = 0; f < n; f++) {
                    int x = gr.coins[f * 3], y = gr.coins[f * 3 + 1], z = gr.coins[f * 3 + 2];
                    int d = gr.infos[f * 4] & 255;
                    float te = (gr.infos[f * 4 + 1] & 255) / 255F, e = (gr.infos[f * 4 + 2] & 255) / 255F * 1.5F;
                    int sorte = gr.infos[f * 4 + 3] & 255;
                    int[] s = SENS[d];
                    float diff = Math.max(0, s[0] * L1[0] + s[1] * L1[1] + s[2] * L1[2]), dos = Math.max(0, s[0] * L2[0] + s[1] * L2[1] + s[2] * L2[2]) * 0.35F;
                    float lum = 0.22F + 0.9F * diff + dos;
                    float r, v, b;
                    if (sorte == 1) {
                        r = 1;
                        v = 0.9F;
                        b = 0.3F;
                    } else {
                        float[] c = new float[3];
                        for (int k = 0; k < 3; k++) {
                            c[k] = te < 0.5F ? sombre[k] + (moyen[k] - sombre[k]) * te * 2 : moyen[k] + (clair[k] - moyen[k]) * (te - 0.5F) * 2;
                        }
                        if (sorte == 2) {
                            c[0] = 0.03F;
                            c[1] = 0.022F;
                            c[2] = 0.04F;
                        }
                        r = Math.min(1, c[0] * lum + lueur[0] * e * 0.75F);
                        v = Math.min(1, c[1] * lum + lueur[1] * e * 0.75F);
                        b = Math.min(1, c[2] * lum + lueur[2] * e * 0.75F);
                    }
                    for (int[] o : COINS[d]) {
                        bb.addVertex((x + o[0]) * pas, (y + o[1]) * pas, (z + o[2]) * pas).setColor(r, v, b, 1F);
                    }
                }
                MeshData donnees = bb.build();
                VertexBuffer vb = new VertexBuffer(VertexBuffer.Usage.STATIC);
                if (donnees != null) {
                    vb.bind();
                    vb.upload(donnees);
                    VertexBuffer.unbind();
                }
                t[g] = vb;
            }
        }
        TAMPONS.put(cle, t);
        return t;
    }

    private static float[] rgb(int c) {
        return new float[]{(c >> 16 & 255) / 255F, (c >> 8 & 255) / 255F, (c & 255) / 255F};
    }

    // ================================================================ dessiner

    /** Ce qu'on montre de chaque groupe : son échelle d'apparition (0 = caché), 1 normalement. */
    interface Apparition {
        float de(Groupe g);
    }

    /**
     * Dessine le géant : base place le modèle dans la vue (caméra, position, rotation, taille), os les matrices des
     * articulations ; un groupe mêle deux articulations selon son poids, et grandit autour du pivot de la sienne quand il
     * apparaît.
     */
    static void dessiner(Matrix4f base, Matrix4f projection, Matrix4f[] os, Couleur couleur, Apparition apparition) {
        ShaderInstance shader = GameRenderer.getPositionColorShader();
        if (shader == null) {
            return;
        }
        VertexBuffer[] t = tampons(couleur);
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(true);
        RenderSystem.disableBlend();
        RenderSystem.disableCull();
        Matrix4f m = new Matrix4f(), mel = new Matrix4f();
        for (int g = 0; g < GROUPES.size(); g++) {
            Groupe gr = GROUPES.get(g);
            float k = apparition.de(gr);
            if (k <= 0.01F || t[g] == null || t[g].getFormat() == null) {
                continue;
            }
            Matrix4f M = gr.w() > 0 ? os[gr.a()].lerp(os[gr.b()], gr.w(), mel) : os[gr.a()];
            m.set(base).mul(M);
            if (k < 0.999F) {
                float[] p = pivots[gr.a()];
                m.translate(p[0], p[1], p[2]).scale(k).translate(-p[0], -p[1], -p[2]);
            }
            t[g].bind();
            t[g].drawWithShader(m, projection, shader);
        }
        VertexBuffer.unbind();
        RenderSystem.enableCull();
    }

    private ModeleSusano4() {
    }
}
