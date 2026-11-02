package fr.cabane.client.pouvoir;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import fr.cabane.Cabane;
import fr.cabane.pouvoir.Maudit;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import net.minecraft.client.resources.PlayerSkin;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Ce que le niveau 2 de la marque ajoute au corps, d'après les maquettes validées (docs/marque-forme2) : deux mains
 * géantes palmées dans le dos (l'aile dessinée par l'utilisateur, épaisse comme une main, un peu recourbée vers
 * l'arrière), la grosse touffe de cheveux gris, et le haut de Sasuke qui pend à la taille. Les ailes battent un peu tout
 * le temps, plus quand il marche, s'ouvrent quand il plane, et donnent un grand coup quand il s'envole.
 * <p>
 * Pour soi : accroupi au sol, le coup d'ailes ; en l'air, on plane (accroupi, on pique) ; le clic droit lance le Chidori.
 * <p>
 * Les coordonnées sont celles des maquettes : en pixels du modèle, les pieds à y = 0, l'avant vers +z, la gauche du
 * personnage vers +x.
 */
public final class ClientMarque2 {
    private static final ResourceLocation AILE = Cabane.id("textures/marque/aile.png");
    private static final ResourceLocation BLANC = Cabane.id("textures/marque/blanc.png");

    // ================================================================ l'aile : le maillage

    /** L'aile : 192 × 192 texels, 6 par pixel du modèle, à 40 points de l'image d'origine par pixel. */
    private static final int N = 192;
    private static final int R = 6;
    /** Le maillage est 3 fois plus gros que les texels (la texture découpe le contour au texel près). */
    private static final int C = 3;
    private static final int G = N / C;
    /** Le poignet (accroché au dos) et le creux de la paume, en pixels du modèle dans l'image (x vers l'extérieur, y vers le haut). */
    private static final double[] RACINE = versAile(1150, 720);
    private static final double[] PAUME = versAile(950, 640);
    /**
     * L'épaisseur : une demi-épaisseur de BORD sur le pourtour, qui monte doucement jusqu'à BORD + BOSSE à ARRONDI du
     * bord, la plus forte à la paume et 40 % moindre au bout des doigts ; plus fine contre le dos (pour laisser voir la
     * tête). Les déchirures ne creusent pas le volume.
     */
    private static final double BORD = 0.3, BOSSE = 1.6, ARRONDI = 3.5;
    /** L'aile est 1,3 fois plus grande que l'image, un peu relevée, et se recourbe vers l'arrière loin du poignet. */
    private static final double ECHELLE = 1.3, PENCHE = 0.12, COURBE = 0.005;

    private static double[] versAile(double x, double y) {
        return new double[]{(1194 - x) / 40, (1250 - y) / 40};
    }

    /** Le maillage d'une aile, dans son repère (u vers l'extérieur, v vers le haut, n vers l'avant) : x, y, z, u, v, nx, ny, nz par sommet. */
    private static float[] maillage;
    private static boolean essaye;

    private static float[] maillage() {
        if (!essaye) {
            essaye = true;
            try (var flux = Minecraft.getInstance().getResourceManager().open(AILE); NativeImage image = NativeImage.read(flux)) {
                maillage = construire(image);
            } catch (java.io.IOException | RuntimeException e) {
                Cabane.LOGGER.warn("Marque maudite : aile illisible", e);
            }
        }
        return maillage;
    }

    /** Le maillage, à partir de la texture : ses deux faces bombées, et la tranche tout autour (et autour des trous). */
    private static float[] construire(NativeImage image) {
        if (image.getWidth() != N || image.getHeight() != N) {
            throw new IllegalStateException("aile de " + image.getWidth() + " × " + image.getHeight());
        }
        // plein[x][y], y vers le haut (la ligne 0 de l'image est le haut de l'aile)
        boolean[] plein = new boolean[N * N];
        int sombre = -1;
        double plusSombre = Double.MAX_VALUE;
        for (int y = 0; y < N; y++) {
            for (int x = 0; x < N; x++) {
                int c = image.getPixelRGBA(x, N - 1 - y);
                if ((c >>> 24) > 0) {
                    plein[y * N + x] = true;
                    double l = (c & 255) * 0.3 + (c >> 8 & 255) * 0.59 + (c >> 16 & 255) * 0.11;
                    if (l < plusSombre) {
                        plusSombre = l;
                        sombre = y * N + x;
                    }
                }
            }
        }
        float[] epais = epaisseur(plein);
        // la tranche prend la couleur du texel le plus sombre (le trait)
        float tu = (sombre % N + 0.5F) / N, tv = (N - 1 - sombre / N + 0.5F) / N;
        int[] compte = new int[G * G];
        for (int y = 0; y < N; y++) {
            for (int x = 0; x < N; x++) {
                if (plein[y * N + x]) {
                    compte[(y / C) * G + x / C]++;
                }
            }
        }
        // les coins de la grille, devant (1) et derrière (0), dans le repère de l'aile
        float[][] coins = new float[2][(G + 1) * (G + 1) * 3];
        for (int j = 0; j <= G; j++) {
            for (int i = 0; i <= G; i++) {
                double a = (i * C / (double) R - RACINE[0]) * ECHELLE, b = (j * C / (double) R - RACINE[1]) * ECHELLE;
                double du = a * Math.cos(PENCHE) - b * Math.sin(PENCHE), dv = a * Math.sin(PENCHE) + b * Math.cos(PENCHE);
                double h = -COURBE * (0.6 * du * du + dv * dv);
                double e = epais[(j * C) * (N + 1) + i * C] * ECHELLE;
                int k = (j * (G + 1) + i) * 3;
                for (int f = 0; f < 2; f++) {
                    coins[f][k] = (float) du;
                    coins[f][k + 1] = (float) dv;
                    coins[f][k + 2] = (float) (h + (f == 1 ? e : -e));
                }
            }
        }
        List<float[]> sommets = new ArrayList<>();
        for (int j = 0; j < G; j++) {
            for (int i = 0; i < G; i++) {
                if (compte[j * G + i] == 0) {
                    continue;
                }
                int[] q = {j * (G + 1) + i, j * (G + 1) + i + 1, (j + 1) * (G + 1) + i + 1, (j + 1) * (G + 1) + i};
                float[][] uv = {{i, j}, {i + 1, j}, {i + 1, j + 1}, {i, j + 1}};
                for (int f = 0; f < 2; f++) {
                    float[][] p = new float[4][];
                    for (int c = 0; c < 4; c++) {
                        p[c] = point(coins[f], q[c]);
                    }
                    float[] n = normale(p);
                    if ((n[2] < 0) == (f == 1)) {
                        n = new float[]{-n[0], -n[1], -n[2]};
                    }
                    for (int c = 0; c < 4; c++) {
                        sommets.add(new float[]{p[c][0], p[c][1], p[c][2], uv[c][0] * C / (float) N, 1 - uv[c][1] * C / (float) N, n[0], n[1], n[2]});
                    }
                }
                // la tranche, là où la case voisine est (presque) vide
                if (compte[j * G + i] < 4) {
                    continue;
                }
                int[][] cotes = {{0, -1, 0, 1}, {1, 0, 1, 2}, {0, 1, 2, 3}, {-1, 0, 3, 0}};
                for (int[] cote : cotes) {
                    int ni = i + cote[0], nj = j + cote[1];
                    if (ni >= 0 && nj >= 0 && ni < G && nj < G && compte[nj * G + ni] >= 4) {
                        continue;
                    }
                    float[][] p = {point(coins[1], q[cote[2]]), point(coins[1], q[cote[3]]), point(coins[0], q[cote[3]]), point(coins[0], q[cote[2]])};
                    float[] n = normale(p);
                    if (n[0] * cote[0] + n[1] * cote[1] < 0) {
                        n = new float[]{-n[0], -n[1], -n[2]};
                    }
                    for (float[] s : p) {
                        sommets.add(new float[]{s[0], s[1], s[2], tu, tv, n[0], n[1], n[2]});
                    }
                }
            }
        }
        float[] m = new float[sommets.size() * 8];
        for (int k = 0; k < sommets.size(); k++) {
            System.arraycopy(sommets.get(k), 0, m, k * 8, 8);
        }
        Cabane.LOGGER.info("Marque maudite : aile de {} faces", sommets.size() / 4);
        return m;
    }

    private static float[] point(float[] coins, int k) {
        return new float[]{coins[k * 3], coins[k * 3 + 1], coins[k * 3 + 2]};
    }

    private static float[] normale(float[][] p) {
        float ax = p[2][0] - p[0][0], ay = p[2][1] - p[0][1], az = p[2][2] - p[0][2];
        float bx = p[3][0] - p[1][0], by = p[3][1] - p[1][1], bz = p[3][2] - p[1][2];
        float nx = ay * bz - az * by, ny = az * bx - ax * bz, nz = ax * by - ay * bx;
        float l = Mth.sqrt(nx * nx + ny * ny + nz * nz);
        return l < 1e-6F ? new float[]{0, 0, 1} : new float[]{nx / l, ny / l, nz / l};
    }

    /** La demi-épaisseur de l'aile en chaque coin de texel ((N + 1)², y vers le haut), comme dans les maquettes. */
    private static float[] epaisseur(boolean[] plein) {
        // le vide qu'on atteint depuis le bord : le dehors (le reste du vide, ce sont les déchirures, comptées pleines)
        boolean[] dehors = new boolean[N * N];
        List<int[]> pile = new ArrayList<>();
        for (int i = 0; i < N; i++) {
            pile.add(new int[]{i, 0});
            pile.add(new int[]{i, N - 1});
            pile.add(new int[]{0, i});
            pile.add(new int[]{N - 1, i});
        }
        while (!pile.isEmpty()) {
            int[] p = pile.remove(pile.size() - 1);
            int x = p[0], y = p[1];
            if (x < 0 || y < 0 || x >= N || y >= N || dehors[y * N + x] || plein[y * N + x]) {
                continue;
            }
            dehors[y * N + x] = true;
            pile.add(new int[]{x + 1, y});
            pile.add(new int[]{x - 1, y});
            pile.add(new int[]{x, y + 1});
            pile.add(new int[]{x, y - 1});
        }
        // la distance au dehors, en texels (deux passes)
        float[] d = new float[N * N];
        float r2 = (float) Math.sqrt(2);
        for (int i = 0; i < N * N; i++) {
            d[i] = dehors[i] ? 0 : 1e9F;
        }
        for (int y = 0; y < N; y++) {
            for (int x = 0; x < N; x++) {
                if (d[y * N + x] > 0) {
                    d[y * N + x] = Math.min(d[y * N + x], Math.min(Math.min(dist(d, x - 1, y) + 1, dist(d, x, y - 1) + 1),
                            Math.min(dist(d, x - 1, y - 1) + r2, dist(d, x + 1, y - 1) + r2)));
                }
            }
        }
        for (int y = N - 1; y >= 0; y--) {
            for (int x = N - 1; x >= 0; x--) {
                if (d[y * N + x] > 0) {
                    d[y * N + x] = Math.min(d[y * N + x], Math.min(Math.min(dist(d, x + 1, y) + 1, dist(d, x, y + 1) + 1),
                            Math.min(dist(d, x + 1, y + 1) + r2, dist(d, x - 1, y + 1) + r2)));
                }
            }
        }
        float[] e = new float[N * N];
        for (int y = 0; y < N; y++) {
            for (int x = 0; x < N; x++) {
                int i = y * N + x;
                if (d[i] == 0) {
                    e[i] = (float) BORD;
                    continue;
                }
                double a = (x + 0.5) / R, b = (y + 0.5) / R;
                double k = Math.min(1, d[i] / R / ARRONDI);
                double paume = 1 - 0.4 * Math.min(1, Math.hypot(a - PAUME[0], b - PAUME[1]) / 18);
                double dos = Math.min(1, 0.3 + a / 6.7);
                e[i] = (float) ((BORD + BOSSE * Math.sin(k * Math.PI / 2) * paume) * dos);
            }
        }
        // lissée : trois flous en boîte d'un pixel du modèle de rayon
        for (int passe = 0; passe < 3; passe++) {
            for (int axe = 0; axe < 2; axe++) {
                float[] f = new float[N * N];
                for (int y = 0; y < N; y++) {
                    for (int x = 0; x < N; x++) {
                        float s = 0;
                        for (int j = -R; j <= R; j++) {
                            int nx = x + (axe == 0 ? j : 0), ny = y + (axe == 1 ? j : 0);
                            s += nx < 0 || ny < 0 || nx >= N || ny >= N ? (float) BORD : e[ny * N + nx];
                        }
                        f[y * N + x] = s / (2 * R + 1);
                    }
                }
                e = f;
            }
        }
        for (int i = 0; i < N * N; i++) {
            if (dehors[i]) {
                e[i] = Math.min(e[i], (float) BORD);
            }
        }
        float[] coins = new float[(N + 1) * (N + 1)];
        for (int y = 0; y <= N; y++) {
            for (int x = 0; x <= N; x++) {
                coins[y * (N + 1) + x] = (val(e, x - 1, y - 1) + val(e, x, y - 1) + val(e, x - 1, y) + val(e, x, y)) / 4;
            }
        }
        return coins;
    }

    private static float dist(float[] d, int x, int y) {
        return x < 0 || y < 0 || x >= N || y >= N ? 0 : d[y * N + x];
    }

    private static float val(float[] e, int x, int y) {
        return x < 0 || y < 0 || x >= N || y >= N ? (float) BORD : e[y * N + x];
    }

    // ================================================================ le dessin

    /** Les deux ailes : k de 0 (rien) à 1 (dépliées), battement l'angle dont elles s'ouvrent ou se referment. */
    private static void ailes(PoseStack ps, MultiBufferSource buffers, int lumiere, int recouvre, float k, float battement) {
        float[] m = maillage();
        if (m == null || k <= 0.01F) {
            return;
        }
        VertexConsumer c = buffers.getBuffer(RenderType.entityCutoutNoCull(AILE));
        PoseStack.Pose pose = ps.last();
        for (int s = -1; s <= 1; s += 2) {
            float th = 0.3F + battement;
            Vec3 u = new Vec3(s * Mth.cos(th), 0.04, -Mth.sin(th)).normalize();
            Vec3 v = new Vec3(0, Mth.cos(0.12F), -Mth.sin(0.12F));
            v = v.subtract(u.scale(u.dot(v))).normalize();
            Vec3 n = u.cross(v);
            if (n.z < 0) {
                n = n.scale(-1);
            }
            double rx = s * 2.4, ry = 21.5, rz = -2.8;
            for (int i = 0; i < m.length; i += 8) {
                double x = m[i] * k, y = m[i + 1] * k, z = m[i + 2] * k;
                float px = (float) (rx + u.x * x + v.x * y + n.x * z);
                float py = (float) (ry + u.y * x + v.y * y + n.y * z);
                float pz = (float) (rz + u.z * x + v.z * y + n.z * z);
                float nx = (float) (u.x * m[i + 5] + v.x * m[i + 6] + n.x * m[i + 7]);
                float ny = (float) (u.y * m[i + 5] + v.y * m[i + 6] + n.y * m[i + 7]);
                float nz = (float) (u.z * m[i + 5] + v.z * m[i + 6] + n.z * m[i + 7]);
                c.addVertex(pose, px, py, pz).setColor(255, 255, 255, 255).setUv(m[i + 3], m[i + 4]).setOverlay(recouvre).setLight(lumiere)
                        .setNormal(pose, nx, ny, nz);
            }
        }
    }

    /** Une mèche en pointe : une pyramide, sa base carrée autour de b (perpendiculaire à d). */
    private static void meche(VertexConsumer c, PoseStack.Pose pose, Vec3 b, Vec3 d, double longueur, double largeur, int rouge, int vert, int bleu,
            int lumiere, int recouvre) {
        d = d.normalize();
        Vec3 e1 = d.cross(new Vec3(0, 1, 0));
        if (e1.length() < 0.2) {
            e1 = d.cross(new Vec3(1, 0, 0));
        }
        e1 = e1.normalize();
        Vec3 e2 = d.cross(e1);
        Vec3[] base = new Vec3[4];
        int[][] signes = {{-1, -1}, {1, -1}, {1, 1}, {-1, 1}};
        for (int k = 0; k < 4; k++) {
            base[k] = b.add(e1.scale(signes[k][0] * largeur / 2)).add(e2.scale(signes[k][1] * largeur / 2));
        }
        Vec3 pointe = b.add(d.scale(longueur));
        for (int k = 0; k < 4; k++) {
            Vec3 p0 = base[k], p1 = base[(k + 1) % 4];
            Vec3 n = p1.subtract(p0).cross(pointe.subtract(p0)).normalize();
            if (n.dot(p0.subtract(b)) < 0) {
                n = n.scale(-1);
            }
            for (Vec3 p : new Vec3[]{p0, p1, pointe, pointe}) {
                c.addVertex(pose, (float) p.x, (float) p.y, (float) p.z).setColor(rouge, vert, bleu, 255).setUv(0.5F, 0.5F).setOverlay(recouvre)
                        .setLight(lumiere).setNormal(pose, (float) n.x, (float) n.y, (float) n.z);
            }
        }
    }

    /** Une mèche des cheveux : sa base sur la tête, sa direction, sa longueur, sa largeur. */
    private record Meche(Vec3 b, Vec3 d, double l, double w) {
    }

    /**
     * La grosse touffe gris-bleu, ébouriffée : de longues mèches en pointe tombent dans le dos, d'autres partent sur les
     * côtés et en arrière, des pointes hérissées sur le dessus, de courtes mèches sur le front.
     */
    private static final List<Meche> MECHES = new ArrayList<>();

    static {
        for (double y : new double[]{31.2, 28.8, 26.4}) {
            for (double x : new double[]{-3.5, -1.2, 1.2, 3.5}) {
                MECHES.add(new Meche(new Vec3(x, y, -4.2), new Vec3(x * 0.16, -0.8 - (31.2 - y) * 0.04, -0.6), 9 + (31.2 - y) * 0.5, 3.6));
            }
        }
        for (int sx = -1; sx <= 1; sx += 2) {
            for (double[] yz : new double[][]{{31.2, -2.6}, {31.2, 0.4}, {28.8, -3.2}, {28.8, -0.8}, {26.6, -2.4}, {26.6, 0.6}}) {
                MECHES.add(new Meche(new Vec3(sx * 4.2, yz[0], yz[1]), new Vec3(sx * 0.8, -0.5, -0.5), 6.5, 3.4));
            }
        }
        for (double x : new double[]{-3.2, 0, 3.2}) {
            for (double z : new double[]{-2.8, 0.2, 3}) {
                MECHES.add(new Meche(new Vec3(x, 32.1, z), new Vec3(x * 0.2, 0.5, -0.85), 5.5 + (3 - z) * 0.45, 3.6));
            }
        }
        for (double x : new double[]{-3.2, -1.1, 1.1, 3.2}) {
            MECHES.add(new Meche(new Vec3(x, 31.6, 4.1), new Vec3(x * 0.15, -0.55, 0.8), 2.8, 2.8));
        }
    }

    private static void cheveux(PoseStack ps, MultiBufferSource buffers, int lumiere, int recouvre, float k) {
        VertexConsumer c = buffers.getBuffer(RenderType.entityCutoutNoCull(BLANC));
        PoseStack.Pose pose = ps.last();
        for (int i = 0; i < MECHES.size(); i++) {
            Meche h = MECHES.get(i);
            double hasard = hache(i, 7, 13);
            double ton = 0.82 + 0.28 * hasard;
            meche(c, pose, h.b(), h.d(), h.l() * k * (0.85 + 0.3 * hasard), h.w() * Math.min(1, 0.4 + k), (int) Math.min(255, 150 * ton),
                    (int) Math.min(255, 160 * ton), (int) Math.min(255, 182 * ton), lumiere, recouvre);
        }
    }

    /** Le haut de Sasuke, enlevé : roulé à la taille, il pend dans le dos et sur les côtés, ouvert devant. */
    private static void hautPendant(PoseStack ps, MultiBufferSource buffers, int lumiere, int recouvre) {
        VertexConsumer c = buffers.getBuffer(RenderType.entityCutoutNoCull(BLANC));
        PoseStack.Pose pose = ps.last();
        pan(c, pose, -4.25, 4.25, -2.25, -2.25, 0, -1, 5.2, 1, lumiere, recouvre);
        pan(c, pose, -4.25, -4.25, -2.25, 2.25, -1, 0, 6.2, 2, lumiere, recouvre);
        pan(c, pose, 4.25, 4.25, 2.25, -2.25, 1, 0, 6.2, 3, lumiere, recouvre);
        pan(c, pose, -4.25, -1.6, 2.25, 2.25, 0, 1, 7.8, 4, lumiere, recouvre);
        pan(c, pose, 1.6, 4.25, 2.25, 2.25, 0, 1, 7.8, 5, lumiere, recouvre);
    }

    /** Un pan du haut : des colonnes le long d'une ligne autour des hanches (de (x0, z0) à (x1, z1)), qui s'évasent en descendant. */
    private static void pan(VertexConsumer c, PoseStack.Pose pose, double x0, double x1, double z0, double z1, double sx, double sz, double bas,
            int graine, int lumiere, int recouvre) {
        double pas = 0.5;
        int n = (int) Math.round(Math.hypot(x1 - x0, z1 - z0) / pas);
        for (int i = 0; i < n; i++) {
            double ax = x0 + (x1 - x0) * i / n, az = z0 + (z1 - z0) * i / n;
            double bx = x0 + (x1 - x0) * (i + 1) / n, bz = z0 + (z1 - z0) * (i + 1) / n;
            double fond = bas + 1.4 * hache(i, graine, 4) - (i % 3 == 1 ? 0.6 : 0);
            double pli = 0.8 + 0.28 * (0.5 + 0.5 * Math.sin((i + graine) * 1.9 + 0.7)) + 0.06 * hache(i + graine, 2, 8);
            // le bourrelet, à la ceinture
            for (double y = 14.2; y > 12.4 + 1e-6; y -= pas) {
                double r0 = 0.35 + 0.25 * Math.sin((14.2 - y) / 1.8 * Math.PI), r1 = 0.35 + 0.25 * Math.sin((14.2 - y + pas) / 1.8 * Math.PI);
                tissu(c, pose, ax, az, bx, bz, sx, sz, y, y - pas, r0, r1, pli * (y > 13.6 ? 1.08 : 0.92), lumiere, recouvre);
            }
            // le pan qui pend
            for (double y = 12.4; y > fond + 1e-6; y -= pas) {
                double y1 = Math.max(fond, y - pas);
                tissu(c, pose, ax, az, bx, bz, sx, sz, y, y1, 0.3 + (12.4 - y) * 0.14, 0.3 + (12.4 - y1) * 0.14, pli * (y1 - fond < 0.6 ? 0.82 : 1),
                        lumiere, recouvre);
            }
        }
    }

    private static void tissu(VertexConsumer c, PoseStack.Pose pose, double ax, double az, double bx, double bz, double sx, double sz, double y0,
            double y1, double r0, double r1, double ton, int lumiere, int recouvre) {
        int r = (int) Math.min(255, 178 * ton), v = (int) Math.min(255, 170 * ton), b = (int) Math.min(255, 198 * ton);
        double[][] p = {{ax + sx * r0, y0, az + sz * r0}, {bx + sx * r0, y0, bz + sz * r0}, {bx + sx * r1, y1, bz + sz * r1}, {ax + sx * r1, y1, az + sz * r1}};
        for (double[] q : p) {
            c.addVertex(pose, (float) q[0], (float) q[1], (float) q[2]).setColor(r, v, b, 255).setUv(0.5F, 0.5F).setOverlay(recouvre).setLight(lumiere)
                    .setNormal(pose, (float) sx, 0, (float) sz);
        }
    }

    private static double hache(int i, int j, int k) {
        int h = i * 374761393 + j * 668265263 + k * 2147483647;
        h = (h ^ (h >>> 13)) * 1274126177;
        return ((h ^ (h >>> 16)) & 0xFFFFFFFFL) / 4294967296.0;
    }

    /**
     * L'angle des ailes : un petit battement tout le temps, plus ample et plus vif quand il marche ; ouvertes quand il
     * plane ; et le grand coup d'ailes (elles balaient vers l'avant puis reviennent).
     */
    private static float battement(AbstractClientPlayer joueur, ClientMaudit.Forme2 f, float partiel) {
        float t = (joueur.tickCount + partiel) / 20F;
        float marche = Math.min(1, joueur.walkAnimation.speed(partiel) * 1.5F);
        float a = (0.06F + 0.14F * marche) * Mth.sin(t * Mth.TWO_PI * (1.1F + 0.9F * marche));
        if (!joueur.onGround() && joueur.getDeltaMovement().y < 0 && !joueur.isInWater()) {
            a = -0.22F + 0.05F * Mth.sin(t * Mth.TWO_PI * 0.6F);
        }
        if (f.battement() >= 0) {
            double tau = (System.nanoTime() - f.battement()) / 1e9;
            if (tau < 0.6) {
                a -= 0.75F * Mth.sin((float) (tau / 0.6) * Mth.PI);
            }
        }
        return a;
    }

    /** Les ailes, les cheveux et le haut pendant, collés au corps et à la tête (ils suivent ses mouvements). */
    public static final class Couche extends RenderLayer<AbstractClientPlayer, PlayerModel<AbstractClientPlayer>> {
        public Couche(RenderLayerParent<AbstractClientPlayer, PlayerModel<AbstractClientPlayer>> parent) {
            super(parent);
        }

        @Override
        public void render(PoseStack ps, MultiBufferSource buffers, int lumiere, AbstractClientPlayer joueur, float marche, float vitesse,
                float partiel, float age, float tete, float tangage) {
            ClientMaudit.Forme2 f = ClientMaudit.forme2(joueur.getUUID());
            if (f == null || joueur.isInvisible()) {
                return;
            }
            int recouvre = LivingEntityRenderer.getOverlayCoords(joueur, 0);
            PlayerModel<AbstractClientPlayer> m = getParentModel();
            ps.pushPose();
            m.body.translateAndRotate(ps);
            versMaquette(ps);
            ailes(ps, buffers, lumiere, recouvre, f.ailes(), battement(joueur, f, partiel));
            if (f.torseNu()) {
                hautPendant(ps, buffers, lumiere, recouvre);
            }
            ps.popPose();
            if (f.cheveux() > 0.01F) {
                ps.pushPose();
                m.head.translateAndRotate(ps);
                versMaquette(ps);
                cheveux(ps, buffers, lumiere, recouvre, f.cheveux());
                ps.popPose();
            }
        }

        /** Depuis le pivot d'une partie (au cou) : les coordonnées des maquettes (pieds à y = 0, en pixels, l'avant vers +z). */
        private static void versMaquette(PoseStack ps) {
            ps.scale(1 / 16F, 1 / 16F, 1 / 16F);
            ps.scale(1, -1, -1);
            ps.translate(0, -24, 0);
        }
    }

    // ================================================================ le panneau des touches

    /** Quand son envol sera rechargé (System.nanoTime) : le serveur vient de confirmer un coup d'ailes. */
    private static long envolPret;

    static void envole() {
        envolPret = System.nanoTime() + Maudit.RECHARGE_ENVOL * 50_000_000L;
    }

    private static double rechargeEnvol() {
        return Math.max(0, (envolPret - System.nanoTime()) / 1e9);
    }

    /** À gauche de l'écran, comme pour les autres pouvoirs : ce que fait chaque touche, et ce qui se recharge. */
    private static void panneau(GuiGraphics g) {
        Minecraft mc = Minecraft.getInstance();
        ClientMaudit.Etat e = mc.player == null || mc.options.hideGui ? null : ClientMaudit.etat(mc.player.getUUID());
        if (e == null || !e.pret()) {
            return;
        }
        String eveil = ClientSusano.EVEIL.getTranslatedKeyMessage().getString();
        String[][] lignes;
        boolean[] grises;
        if (e.niveau() >= 2) {
            double chidori = ClientChidori.recharge(), envol = rechargeEnvol();
            lignes = new String[][]{
                    {"Clic droit", chidori > 0 ? String.format("Chidori (%.0f s)", Math.ceil(chidori)) : "Chidori"},
                    {"Accroupi au sol", envol > 0 ? String.format("Envol (%.0f s)", Math.ceil(envol)) : "Envol"},
                    {"En l'air", "Planer"},
                    {"Accroupi en l'air", "Piquer"},
                    {eveil, "Rendormir"}};
            grises = new boolean[]{chidori > 0, envol > 0, false, false, false};
        } else {
            lignes = new String[][]{{eveil, e.deux() ? "Niveau 2" : "Rendormir"}};
            grises = new boolean[]{false};
        }
        Font font = mc.font;
        String titre = e.niveau() >= 2 ? "MARQUE MAUDITE  ·  NIVEAU 2" : "MARQUE MAUDITE";
        int largeurTouche = 0;
        int largeurAction = font.width(titre) - 10;
        for (String[] l : lignes) {
            largeurTouche = Math.max(largeurTouche, font.width(l[0]));
            largeurAction = Math.max(largeurAction, font.width(l[1]));
        }
        int w = largeurTouche + largeurAction + 26;
        int h = 22 + lignes.length * 13;
        int x = 6;
        int y = g.guiHeight() / 2 - h / 2;
        int teinte = 0xFFB070F0;
        g.fill(x, y, x + w, y + h, 0xA0000000);
        g.fill(x, y, x + 2, y + h, teinte);
        g.drawString(font, titre, x + 8, y + 6, teinte, true);
        for (int i = 0; i < lignes.length; i++) {
            int ly = y + 21 + i * 13;
            // la touche, dans un petit cadre
            g.fill(x + 7, ly - 2, x + 11 + font.width(lignes[i][0]), ly + 9, 0x40FFFFFF);
            g.drawString(font, lignes[i][0], x + 9, ly, 0xFFFFFFFF, false);
            g.drawString(font, lignes[i][1], x + 18 + largeurTouche, ly, grises[i] ? 0xFF7A7A7A : 0xFFEDEDED, true);
        }
    }

    // ================================================================ les événements

    @EventBusSubscriber(modid = Cabane.MODID, value = Dist.CLIENT)
    public static final class Couches {
        @SubscribeEvent
        public static void enregistrer(RegisterGuiLayersEvent event) {
            event.registerAboveAll(Cabane.id("pouvoir_marque"), (g, delta) -> panneau(g));
        }

        private Couches() {
        }
    }

    @EventBusSubscriber(modid = Cabane.MODID, value = Dist.CLIENT)
    public static final class Evenements {
        private static boolean accroupiAvant;
        private static long dernierClic;

        @SubscribeEvent
        public static void couches(EntityRenderersEvent.AddLayers event) {
            for (PlayerSkin.Model modele : event.getSkins()) {
                if (event.getSkin(modele) instanceof PlayerRenderer rendu) {
                    rendu.addLayer(new Couche(rendu));
                }
            }
        }

        /** Pour soi, ailes sorties : accroupi au sol, le coup d'ailes ; en l'air, on plane (accroupi, on pique). */
        @SubscribeEvent
        public static void tick(ClientTickEvent.Post event) {
            Minecraft mc = Minecraft.getInstance();
            LocalPlayer moi = mc.player;
            if (moi == null || mc.level == null) {
                return;
            }
            boolean ailes = ClientMaudit.ailesSorties(moi.getUUID()) && !ClientChidori.enCours(moi);
            boolean accroupi = mc.options.keyShift.isDown();
            if (ailes && accroupi && !accroupiAvant && moi.onGround() && mc.screen == null && rechargeEnvol() <= 0) {
                PacketDistributor.sendToServer(new Maudit.Envol());
            }
            accroupiAvant = accroupi;
            if (!ailes || moi.onGround() || moi.isInWater() || moi.isInLava() || moi.getAbilities().flying || moi.isPassenger()
                    || moi.onClimbable() || moi.isFallFlying()) {
                return;
            }
            Vec3 d = moi.getDeltaMovement();
            if (d.y >= 0) {
                return;
            }
            if (accroupi) {
                // en piqué
                moi.setDeltaMovement(d.x, Math.max(d.y - 0.06, -1.2), d.z);
                return;
            }
            // il plane : il descend doucement, et file là où il regarde s'il avance
            double y = Math.max(d.y, -0.11);
            Vec3 h = new Vec3(d.x, 0, d.z);
            if (moi.input.forwardImpulse > 0) {
                Vec3 regard = moi.getLookAngle();
                Vec3 voulu = new Vec3(regard.x, 0, regard.z).normalize().scale(0.42);
                h = h.lerp(voulu, 0.08);
            }
            moi.setDeltaMovement(h.x, y, h.z);
        }

        /** Ailes sorties, le clic droit lance le Chidori (le serveur choisit la cible). */
        @SubscribeEvent
        public static void clic(InputEvent.InteractionKeyMappingTriggered event) {
            Minecraft mc = Minecraft.getInstance();
            if (!event.isUseItem() || mc.player == null || !ClientMaudit.ailesSorties(mc.player.getUUID())) {
                return;
            }
            event.setCanceled(true);
            event.setSwingHand(false);
            if (System.nanoTime() - dernierClic > 500_000_000L && !ClientChidori.enCours(mc.player)) {
                dernierClic = System.nanoTime();
                PacketDistributor.sendToServer(new Maudit.Lancer());
            }
        }

        private Evenements() {
        }
    }

    private ClientMarque2() {
    }
}
