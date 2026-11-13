package fr.cabane.client.pouvoir;

import static fr.cabane.pouvoir.MinatoRouge.*;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import fr.cabane.Cabane;
import fr.cabane.client.cinema.Effets;
import fr.cabane.pouvoir.MinatoRouge;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.PlayerSkin;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.joml.Vector3f;

/**
 * Le mode rouge de Minato 3 (voir {@link MinatoRouge}), chez chacun, d'après la maquette validée (docs/minato3) :
 * <ul>
 * <li>sa peau : la tenue du mode Kurama passée en rouge (l'orange reste orange, les traits noirs aussi), son visage de
 * base avec les yeux rouges, les cornes rouges qui font le contour des yeux et remontent aux coins du haut de la tête,
 * un capuchon rouge sur les côtés ;</li>
 * <li>par-dessus : un manteau de chakra (col haut, spirale sur la poitrine, ouvert en V, bas en pointes) et dix langues
 * de chakra en 3D qui partent dans tous les sens et ondulent (rouge plein, large bord crème sur la silhouette, traits
 * noirs), et les cornes en pavés au-dessus de la tête ;</li>
 * <li>les effets des attaques, le sceau et le temps arrêté (tout gris sauf le rouge) de Mille Flashs, le panneau.</li>
 * </ul>
 */
public final class ClientMinatoRouge {
    private static final RandomSource HASARD = RandomSource.create();
    private static final DustParticleOptions BRAISE = new DustParticleOptions(new Vector3f(1F, 0.45F, 0.2F), 0.7F);

    /** En mode rouge : jusqu'à quand (System.nanoTime), depuis quand (pour la transformation). Éteints : depuis quand. */
    private static final Map<UUID, Long> FINS = new HashMap<>();
    private static final Map<UUID, Long> DEBUTS = new HashMap<>();
    private static final Map<UUID, Long> ETEINTS = new HashMap<>();
    /** Mes recharges (System.nanoTime), par touche. */
    private static final long[] PRETS = new long[4];
    private static long dernierClic;
    private static boolean gaucheTenu;
    /** Quand le mode m'a pris (le flash rouge à l'écran), quand le temps est reparti près de moi (le flash blanc). */
    private static long monFlash = -1, flashBlanc = -1;

    private static double secondes(long depuis) {
        return (System.nanoTime() - depuis) / 1e9;
    }

    public static boolean actif(UUID joueur) {
        return FINS.containsKey(joueur);
    }

    private static boolean moi(UUID joueur) {
        Minecraft mc = Minecraft.getInstance();
        return mc.player != null && mc.player.getUUID().equals(joueur);
    }

    public static void etat(MinatoRouge.Etat p) {
        if (p.reste() <= 0) {
            if (FINS.remove(p.joueur()) != null) {
                ETEINTS.put(p.joueur(), System.nanoTime());
            }
            DEBUTS.remove(p.joueur());
            return;
        }
        boolean nouveau = !FINS.containsKey(p.joueur());
        FINS.put(p.joueur(), System.nanoTime() + p.reste() * 50_000_000L);
        ETEINTS.remove(p.joueur());
        if (nouveau) {
            DEBUTS.put(p.joueur(), p.annonce() ? System.nanoTime() : System.nanoTime() - 3_000_000_000L);
            if (p.annonce() && moi(p.joueur())) {
                monFlash = System.nanoTime();
                java.util.Arrays.fill(PRETS, 0);
            }
        }
    }

    /** La présence du chakra (0 à 1) et la poussée des langues (avec un rebond) pour ce joueur, ou null s'il n'en a pas. */
    private static float[] presence(UUID id) {
        Long d = DEBUTS.get(id);
        if (FINS.containsKey(id) && d != null) {
            double t = secondes(d);
            double u = Mth.clamp(t / 1.1, 0, 1);
            double pousse = u >= 1 ? 1 : 1 - Math.cos(u * Math.PI * 2.5) * Math.exp(-u * 5.5);
            return new float[] {(float) Mth.clamp(t / 0.6, 0, 1), (float) Math.max(0.001, pousse)};
        }
        Long e = ETEINTS.get(id);
        if (e != null) {
            double t = secondes(e);
            if (t < 1.0) {
                float k = (float) (1 - t);
                return new float[] {k, k};
            }
            ETEINTS.remove(id);
        }
        return null;
    }

    // ================================================================ la peau rouge

    private static final Map<String, ResourceLocation> PEAUX = new HashMap<>();
    private static final Map<String, Long> RATEES = new HashMap<>();
    private static final Map<ResourceLocation, PlayerSkin> REMPLACEES = new HashMap<>();

    /** Appelé par {@link ClientKurama#peau} : en mode rouge, la peau rouge (sinon null). */
    public static PlayerSkin peau(AbstractClientPlayer joueur, PlayerSkin skin) {
        if (FINS.isEmpty() || !FINS.containsKey(joueur.getUUID())) {
            return null;
        }
        String cle = skin.texture() + "|rouge";
        ResourceLocation id = PEAUX.get(cle);
        if (id == null) {
            if (System.nanoTime() < RATEES.getOrDefault(cle, 0L)) {
                return null;
            }
            int[] base = ClientMaudit.lire(skin.texture());
            int[] kcm = ClientKurama.tenue();
            if (base == null || kcm == null) {
                RATEES.put(cle, System.nanoTime() + 2_000_000_000L);
                return null;
            }
            int[] px = composer(base, kcm);
            NativeImage image = new NativeImage(64, 64, true);
            for (int i = 0; i < px.length; i++) {
                image.setPixelRGBA(i % 64, i / 64, px[i]);
            }
            id = Cabane.id("minato/rouge_" + PEAUX.size());
            Minecraft.getInstance().getTextureManager().register(id, new DynamicTexture(image));
            PEAUX.put(cle, id);
        }
        PlayerSkin s = REMPLACEES.get(id);
        if (s == null || s.model() != skin.model()) {
            s = new PlayerSkin(id, skin.textureUrl(), null, skin.elytraTexture(), skin.model(), skin.secure());
            REMPLACEES.put(id, s);
        }
        return s;
    }

    /** Une couleur de la tenue : le jaune passe en rouge, l'orange reste orange, le noir et les gris ne bougent pas. */
    private static int rougir(int c) {
        double r = ClientKurama.r(c) / 255.0, g = ClientKurama.g(c) / 255.0, b = ClientKurama.b(c) / 255.0;
        double max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b)), d = max - min;
        double v = max, s = max == 0 ? 0 : d / max;
        if (v < 0.3 || s < 0.12) {
            return c;
        }
        double h = d == 0 ? 0 : max == r ? 60 * (((g - b) / d) % 6) : max == g ? 60 * ((b - r) / d + 2) : 60 * ((r - g) / d + 4);
        h = (h + 360) % 360;
        double nh, ns, nv;
        if (h >= 38 && h <= 75) {
            nh = (358 + 3 * (1 - s)) % 360;
            ns = Math.min(1, 0.8 + s * 0.17);
            nv = Math.min(1, 0.6 + v * 0.3);
        } else if (h < 38) {
            nh = h < 12 ? 6 : 24;
            ns = Math.min(1, s * 1.05);
            nv = v;
        } else {
            return c;
        }
        double cc = nv * ns, x = cc * (1 - Math.abs((nh / 60) % 2 - 1)), m = nv - cc;
        double[] rgb = nh < 60 ? new double[] {cc, x, 0} : nh < 120 ? new double[] {x, cc, 0} : nh < 180 ? new double[] {0, cc, x}
                : nh < 240 ? new double[] {0, x, cc} : nh < 300 ? new double[] {x, 0, cc} : new double[] {cc, 0, x};
        return ClientKurama.abgr((rgb[0] + m) * 255, (rgb[1] + m) * 255, (rgb[2] + m) * 255);
    }

    /** De la peau : une couleur chaude (plus de rouge que de bleu), ni noire ni blanche. Les cheveux gris ou sombres n'en sont pas. */
    private static boolean chaud(int c) {
        int r = ClientKurama.r(c), b = ClientKurama.b(c);
        double l = ClientKurama.lum(c), max = Math.max(r, Math.max(ClientKurama.g(c), b)), min = Math.min(r, Math.min(ClientKurama.g(c), b));
        return r - b > 25 && max > 0 && (max - min) / max > 0.15 && l > 60 && l < 235;
    }

    /** Le blanc d'un œil : très clair et sans couleur. */
    private static boolean blanc(int c) {
        int r = ClientKurama.r(c), g = ClientKurama.g(c), b = ClientKurama.b(c);
        return ClientKurama.lum(c) >= 245 && Math.max(r, Math.max(g, b)) - Math.min(r, Math.min(g, b)) < 20;
    }

    private static int[] composer(int[] base, int[] kcm) {
        int[] px = ClientKurama.composer(base, kcm, 33);
        for (int i = 0; i < px.length; i++) {
            if (Float.isNaN(ClientKurama.HAUTEUR[i])) {
                continue;
            }
            int x = i % 64, y = i / 64;
            if (ClientKurama.PARTIE[i] != ClientKurama.TETE) {
                if (ClientKurama.a(px[i]) >= 128) {
                    px[i] = rougir(px[i]);
                }
                continue;
            }
            // le visage reprend ses couleurs de base (la peau, le blanc des yeux) ; les cheveux restent jaunes
            if (ClientKurama.COUCHE_1[i] && ClientKurama.a(base[i]) > 128) {
                boolean devant = x >= 8 && x < 16 && y >= 10 && y < 16;
                // tout le reste (les mèches, même sur le front) prend le jaune des cheveux du mode
                px[i] = (chaud(base[i]) || (devant && blanc(base[i])) ? base[i] : ClientKurama.dore(base[i])) | 0xFF000000;
            }
        }
        // les yeux, d'après le skin d'origine : le blanc, et la pupille à côté (tout ce qui n'est ni blanc ni peau), qui passe en
        // rouge ; des yeux de 2 × 2 donnent une pupille de 2 pixels (une colonne), des yeux d'une ligne, une seule
        int rouge = ClientKurama.abgr(255, 52, 36);
        int haut = 99, bas = -1;
        int[] gauche = {99, -1}, droite = {99, -1};
        for (int y = 10; y <= 15; y++) {
            for (int x = 8; x <= 15; x++) {
                if (!blanc(base[y * 64 + x]) || ClientKurama.a(base[y * 64 + x]) < 128) {
                    continue;
                }
                haut = Math.min(haut, y);
                bas = Math.max(bas, y);
                int[] cote = x < 12 ? gauche : droite;
                cote[0] = Math.min(cote[0], x);
                cote[1] = Math.max(cote[1], x);
                for (int dx = -1; dx <= 1; dx += 2) {
                    int xx = x + dx;
                    int c = base[y * 64 + Math.max(8, Math.min(15, xx))];
                    if (xx >= 8 && xx <= 15 && (xx < 12) == (x < 12) && !blanc(c) && (!chaud(c) || ClientKurama.lum(c) < 100)) {
                        px[y * 64 + xx] = rouge;
                        cote[0] = Math.min(cote[0], xx);
                        cote[1] = Math.max(cote[1], xx);
                    }
                }
            }
        }
        if (bas < 0) {
            // pas de blanc des yeux : la place habituelle
            haut = bas = 12;
            px[12 * 64 + 10] = rouge;
            px[12 * 64 + 13] = rouge;
            gauche = new int[] {9, 10};
            droite = new int[] {13, 14};
        }
        if (gauche[1] < 0) {
            gauche = new int[] {9, 10};
        }
        if (droite[1] < 0) {
            droite = new int[] {13, 14};
        }
        // les cornes peintes : elles font le contour des yeux (au-dessus, sur le côté, dessous) et remontent par les
        // bords du visage jusqu'aux coins du haut, d'où partent les cornes en 3D
        int clair = ClientKurama.abgr(218, 24, 14), sombre = ClientKurama.abgr(170, 12, 8), trait = ClientKurama.abgr(118, 8, 4);
        List<int[]> cornes = new ArrayList<>();
        for (int y = 8; y <= Math.min(15, bas + 1); y++) {
            cornes.add(new int[] {8, y, y >= haut ? sombre : clair});
            cornes.add(new int[] {15, y, y >= haut ? sombre : clair});
        }
        if (haut - 2 >= 8) {
            cornes.add(new int[] {gauche[0], haut - 2, clair});
            cornes.add(new int[] {droite[1], haut - 2, clair});
        }
        if (haut - 1 >= 8) {
            for (int x = gauche[0]; x <= gauche[1]; x++) {
                cornes.add(new int[] {x, haut - 1, trait});
            }
            for (int x = droite[0]; x <= droite[1]; x++) {
                cornes.add(new int[] {x, haut - 1, trait});
            }
        }
        if (bas + 1 <= 15) {
            cornes.add(new int[] {gauche[0], bas + 1, sombre});
            cornes.add(new int[] {droite[1], bas + 1, sombre});
        }
        for (int[] c : cornes) {
            px[c[1] * 64 + c[0]] = c[2];
            // rien de la seconde couche par-dessus (des mèches cacheraient les cornes)
            px[c[1] * 64 + c[0] + 32] = 0;
        }
        // le capuchon rouge sur les côtés de la tête (seconde couche), et son bord sur le dessus
        for (int y = 8; y <= 13; y++) {
            for (int x = 0; x < 8; x++) {
                int c = y >= 12 ? sombre : clair;
                px[y * 64 + 32 + x] = c;
                px[y * 64 + 48 + x] = c;
            }
        }
        for (int y = 0; y < 8; y++) {
            px[y * 64 + 40] = clair;
            px[y * 64 + 47] = clair;
        }
        return px;
    }

    // ================================================================ le manteau et les langues (en pixels du modèle)

    /**
     * Une surface de chakra, en pixels du modèle (+x : sa gauche, +y : en haut, +z : devant ; les pieds en 0) : ses
     * sommets en grille (nu + 1 par nv + 1), leurs normales, le bord (0 dedans, 1 au bord), l'ondulation (0 à la racine,
     * 1 au bout) et sa direction. Les traits noirs sont des surfaces à part (sombre).
     */
    private static final class Surface {
        final int nu, nv;
        final float[] pos, nrm, bord, onde, dir;
        final float phase, ampleur;
        final boolean sombre;
        /** Pour les langues : le point d'où elles poussent (elles grandissent depuis là). */
        final float[] racine;

        Surface(int nu, int nv, float phase, float ampleur, boolean sombre, float[] racine) {
            this.nu = nu;
            this.nv = nv;
            int n = (nu + 1) * (nv + 1);
            pos = new float[n * 3];
            nrm = new float[n * 3];
            bord = new float[n];
            onde = new float[n];
            dir = new float[n * 3];
            this.phase = phase;
            this.ampleur = ampleur;
            this.sombre = sombre;
            this.racine = racine;
        }

        void normales() {
            for (int i = 0; i <= nu; i++) {
                for (int j = 0; j <= nv; j++) {
                    int a = Math.max(0, i - 1), b = Math.min(nu, i + 1), c = Math.max(0, j - 1), d = Math.min(nv, j + 1);
                    float[] du = sous(idx(b, j), idx(a, j)), dv = sous(idx(i, d), idx(i, c));
                    float nx = du[1] * dv[2] - du[2] * dv[1], ny = du[2] * dv[0] - du[0] * dv[2], nz = du[0] * dv[1] - du[1] * dv[0];
                    float l = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
                    int k = idx(i, j) * 3;
                    if (l > 1e-6F) {
                        nrm[k] = nx / l;
                        nrm[k + 1] = ny / l;
                        nrm[k + 2] = nz / l;
                    } else {
                        nrm[k + 1] = 1;
                    }
                }
            }
        }

        private float[] sous(int a, int b) {
            return new float[] {pos[a * 3] - pos[b * 3], pos[a * 3 + 1] - pos[b * 3 + 1], pos[a * 3 + 2] - pos[b * 3 + 2]};
        }

        int idx(int i, int j) {
            return i * (nv + 1) + j;
        }

        void mettre(int i, int j, double[] p, double bd, double on, double[] d) {
            int k = idx(i, j);
            pos[k * 3] = (float) p[0];
            pos[k * 3 + 1] = (float) p[1];
            pos[k * 3 + 2] = (float) p[2];
            bord[k] = (float) Mth.clamp(bd, 0, 1);
            onde[k] = (float) on;
            if (d != null) {
                dir[k * 3] = (float) d[0];
                dir[k * 3 + 1] = (float) d[1];
                dir[k * 3 + 2] = (float) d[2];
            }
        }
    }

    // ---- petites aides de vecteurs (double[3])

    private static double[] v(double x, double y, double z) {
        return new double[] {x, y, z};
    }

    private static double[] plus(double[] a, double[] b, double k) {
        return v(a[0] + b[0] * k, a[1] + b[1] * k, a[2] + b[2] * k);
    }

    private static double[] croix(double[] a, double[] b) {
        return v(a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]);
    }

    private static double[] norme(double[] a) {
        double l = Math.sqrt(a[0] * a[0] + a[1] * a[1] + a[2] * a[2]);
        return l < 1e-9 ? v(0, 1, 0) : v(a[0] / l, a[1] / l, a[2] / l);
    }

    /** a tourné autour de l'axe (unitaire) de « angle » radians. */
    private static double[] tourner(double[] a, double[] axe, double angle) {
        double c = Math.cos(angle), s = Math.sin(angle), d = a[0] * axe[0] + a[1] * axe[1] + a[2] * axe[2];
        double[] k = croix(axe, a);
        return v(a[0] * c + k[0] * s + axe[0] * d * (1 - c), a[1] * c + k[1] * s + axe[1] * d * (1 - c), a[2] * c + k[2] * s + axe[2] * d * (1 - c));
    }

    /** Une courbe lisse (Catmull-Rom) par ces points, parcourue à vitesse constante. */
    private static final class Courbe {
        final double[][] pts = new double[201][];
        final double[] longueurs = new double[201];

        Courbe(double[][] ctl) {
            int n = ctl.length;
            for (int i = 0; i <= 200; i++) {
                double t = i / 200.0 * (n - 1);
                int k = Math.min(n - 2, (int) t);
                double u = t - k;
                double[] p0 = ctl[Math.max(0, k - 1)], p1 = ctl[k], p2 = ctl[k + 1], p3 = ctl[Math.min(n - 1, k + 2)];
                double[] p = new double[3];
                for (int c = 0; c < 3; c++) {
                    p[c] = 0.5 * (2 * p1[c] + (-p0[c] + p2[c]) * u + (2 * p0[c] - 5 * p1[c] + 4 * p2[c] - p3[c]) * u * u
                            + (-p0[c] + 3 * p1[c] - 3 * p2[c] + p3[c]) * u * u * u);
                }
                pts[i] = p;
                if (i > 0) {
                    double[] q = pts[i - 1];
                    longueurs[i] = longueurs[i - 1] + Math.sqrt((p[0] - q[0]) * (p[0] - q[0]) + (p[1] - q[1]) * (p[1] - q[1]) + (p[2] - q[2]) * (p[2] - q[2]));
                }
            }
        }

        double[] point(double u) {
            double cible = u * longueurs[200];
            int i = 1;
            while (i < 200 && longueurs[i] < cible) {
                i++;
            }
            double l0 = longueurs[i - 1], l1 = longueurs[i], k = l1 > l0 ? (cible - l0) / (l1 - l0) : 0;
            return plus(pts[i - 1], v(pts[i][0] - pts[i - 1][0], pts[i][1] - pts[i - 1][1], pts[i][2] - pts[i - 1][2]), k);
        }

        double[] tangente(double u) {
            double[] a = point(Math.max(0, u - 0.01)), b = point(Math.min(1, u + 0.01));
            return norme(v(b[0] - a[0], b[1] - a[1], b[2] - a[2]));
        }
    }

    /**
     * Les langues du côté gauche (+x) ; la droite en miroir. Elles partent dans tous les sens en 3D : vers le haut et
     * l'arrière, sur le côté, en s'enroulant vers l'avant autour de l'épaule, à l'horizontale vers l'arrière, et en bas en
     * balayant le sol derrière lui. [points…], largeur, face au départ, trait noir (en travers, NaN : aucun), torsion.
     */
    private static final double[][][] LANGUES_PTS = {
            {{1.5, 23, -4.6}, {3, 32, -9}, {4.5, 41, -16}, {3.5, 49, -24}, {7, 53, -33}},
            {{3, 22, -4.6}, {10, 29, -11}, {17, 37, -18}, {22, 45, -25}, {30, 48, -29}},
            {{5, 21, -3.5}, {12, 24, 1}, {18, 28, 5}, {23, 35, 4}, {27, 42, -2}},
            {{4, 18, -5}, {13, 19, -13}, {21, 21, -22}, {27, 25, -31}, {35, 30, -35}},
            {{4, 13, -6}, {10, 9, -15}, {15, 5, -25}, {19, 4, -35}, {26, 8, -44}}};
    private static final double[][] LANGUES = {
            {12, 0.6, 0, 0.8, -0.25, 1.0}, {13, 0.4, 0, 1, 0.2, 0.9}, {10, -0.6, 0.3, 0.7, Double.NaN, 0.8},
            {11, 0.5, 0.6, 0.6, -0.2, 1.2}, {12, 1, 0.4, 0, 0.15, 1.0}};

    private static List<Surface> langues;
    private static List<Surface> manteau;

    /** Une langue de chakra en volume : une section ovale le long de la courbe, étroite à la racine, large aux deux cinquièmes, en pointe. */
    private static void langue(List<Surface> l, double[][] pts, double w, double[] n0, double raie, double tordre, float phase) {
        Courbe c = new Courbe(pts);
        int nu = 40, nv = 16;
        Surface s = new Surface(nu, nv, phase, 2.4F, false, new float[] {(float) pts[0][0], (float) pts[0][1], (float) pts[0][2]});
        // la face large tourne en avançant ; on garde le repère de chaque tranche pour les traits noirs
        double[][] cotes = new double[nu + 1][], nrms = new double[nu + 1][];
        double[] largeurs = new double[nu + 1], epaisseurs = new double[nu + 1];
        for (int i = 0; i <= nu; i++) {
            double u = (double) i / nu;
            double[] T = c.tangente(u);
            double[] N = tourner(norme(n0), T, tordre * u);
            double[] cote = norme(croix(T, N));
            cotes[i] = cote;
            nrms[i] = norme(croix(cote, T));
            largeurs[i] = w * (u < 0.4 ? 0.4 + 0.6 * Math.sin(u / 0.4 * Math.PI / 2) : Math.pow(1 - (u - 0.4) / 0.6, 0.8));
            epaisseurs[i] = 0.2 * largeurs[i] * (0.35 + 0.65 * Math.sin(Math.min(1, u * 1.4) * Math.PI / 2));
            double[] centre = c.point(u);
            for (int j = 0; j <= nv; j++) {
                double phi = (double) j / nv * Math.PI * 2, cx = Math.cos(phi), sy = Math.sin(phi);
                double[] p = plus(plus(centre, cote, cx * largeurs[i] / 2), nrms[i], sy * epaisseurs[i] + 0.12 * largeurs[i] * (1 - cx * cx));
                double bord = Math.max(cx * cx, u > 0.93 ? (u - 0.93) * 14 : 0) * Math.min(1, u / 0.12);
                s.mettre(i, j, p, bord, u, nrms[i]);
            }
        }
        s.normales();
        l.add(s);
        if (Double.isNaN(raie)) {
            return;
        }
        // le trait noir : une bande fine qui suit la langue sur ses deux faces
        int a = (int) (0.14 * nu), b = (int) (0.82 * nu);
        for (int face = -1; face <= 1; face += 2) {
            Surface t = new Surface(b - a, 1, phase, 2.4F, true, s.racine);
            for (int i = a; i <= b; i++) {
                double u = (double) i / nu;
                double cx0 = Mth.clamp(raie + 0.18 * Math.sin(u * 4), -0.9, 0.9);
                double[] centre = c.point(u);
                for (int j = 0; j <= 1; j++) {
                    double cx = cx0 + (j == 0 ? -0.045 : 0.045), sy = face * Math.sqrt(Math.max(0, 1 - cx * cx));
                    double[] p = plus(plus(centre, cotes[i], cx * largeurs[i] / 2), nrms[i], sy * (epaisseurs[i] + 0.06) + 0.12 * largeurs[i] * (1 - cx * cx));
                    t.mettre(i - a, j, p, 0, u, nrms[i]);
                }
            }
            t.normales();
            l.add(t);
        }
    }

    private static double tmManteau(double s) {
        return s < 0 ? Math.PI - 0.08 : Math.PI - (0.08 + 1.35 * Math.pow(s, 0.8));
    }

    /** Un point du manteau : s de -0,25 (le cou) à 1 (le bas), th autour (0 : le dos). */
    private static double[] pointManteau(double s, double th) {
        double rx, rz, y;
        if (s < 0) {
            double u = (s + 0.25) / 0.25;
            rx = Mth.lerp(u, 4.4, 8.9);
            rz = Mth.lerp(u, 2.6, 3.9);
            y = Mth.lerp(u, 25.8, 24.6);
        } else {
            rx = 8.9 + 4.6 * s;
            rz = 3.9 + 5.6 * s;
            y = 24.6 - 17.6 * s;
        }
        if (s > 0.6) {
            y -= 3.2 * Math.pow(Math.max(0, Math.sin(th * 3.5 + 0.6)), 3) * Math.pow((s - 0.6) / 0.4, 2);
        }
        return v(rx * Math.sin(th), y, -rz * Math.cos(th));
    }

    private static void construire() {
        if (langues != null) {
            return;
        }
        langues = new ArrayList<>();
        for (int sx = 1; sx >= -1; sx -= 2) {
            for (int k = 0; k < LANGUES_PTS.length; k++) {
                double[][] pts = new double[LANGUES_PTS[k].length][];
                for (int i = 0; i < pts.length; i++) {
                    pts[i] = v(LANGUES_PTS[k][i][0] * sx, LANGUES_PTS[k][i][1], LANGUES_PTS[k][i][2]);
                }
                double[] d = LANGUES[k];
                langue(langues, pts, d[0], v(d[1] * sx, d[2], d[3]), Double.isNaN(d[4]) ? Double.NaN : d[4] * sx, d[5] * sx, (float) (k * 1.3 + (sx > 0 ? 0 : 2.1)));
            }
        }
        manteau = new ArrayList<>();
        // le manteau
        int nu = 24, nv = 36;
        Surface m = new Surface(nu, nv, 0, 0.8F, false, null);
        for (int i = 0; i <= nu; i++) {
            double s = -0.25 + 1.25 * i / nu, tm = tmManteau(s), rx = s < 0 ? 8.9 : 8.9 + 4.6 * s;
            for (int j = 0; j <= nv; j++) {
                double th = -tm + 2 * tm * j / nv;
                double bordV = 1 - (tm - Math.abs(th)) * rx / 7, bordH = s > 0 ? 1 - (1 - s) * 17.6 / 7 : 0;
                m.mettre(i, j, pointManteau(s, th), Math.max(bordV, bordH), Math.max(0, s) * 0.6, v(Math.sin(th), 0, -Math.cos(th)));
            }
        }
        m.normales();
        manteau.add(m);
        // le col haut
        int cu = 6, cv = 28;
        double tc = Math.PI - 0.5;
        Surface col = new Surface(cu, cv, 0, 0, false, null);
        for (int i = 0; i <= cu; i++) {
            double a = (double) i / cu;
            for (int j = 0; j <= cv; j++) {
                double th = -tc + 2 * tc * j / cv, rx = 4.9 + 1.9 * a * a, rz = 3.0 + 1.9 * a * a;
                double hc = 6.2 + 1.2 * Math.cos(th) + 2.6 * Math.pow(Math.abs(th) / tc, 6);
                double bord = Math.max(1 - (1 - a) * hc / 6, 1 - (tc - Math.abs(th)) * rx / 6);
                col.mettre(i, j, v(rx * Math.sin(th), 24.9 + a * hc, -rz * Math.cos(th)), bord, 0, null);
            }
        }
        col.normales();
        manteau.add(col);
        // les traits noirs du manteau : deux courbes qui font le tour, un peu au-dessus de la surface
        for (int k = 0; k < 2; k++) {
            int n = 48;
            Surface t = new Surface(n, 1, 0, 0.8F, true, null);
            for (int i = 0; i <= n; i++) {
                double th0 = -Math.PI + 2 * Math.PI * i / n;
                double s0 = k == 0 ? 0.1 + 0.2 * Math.cos(th0) * Math.cos(th0) : 0.45 + 0.14 * Math.sin(2 * th0);
                double th = Mth.clamp(th0, -tmManteau(s0) + 0.05, tmManteau(s0) - 0.05);
                for (int j = 0; j <= 1; j++) {
                    double s = s0 + (j == 0 ? -0.012 : 0.012);
                    double[] p = pointManteau(s, th);
                    double[] dehors = v(Math.sin(th), 0, -Math.cos(th));
                    t.mettre(i, j, plus(p, dehors, 0.12), 0, Math.max(0, s) * 0.6, dehors);
                }
            }
            t.normales();
            manteau.add(t);
        }
        // la spirale du fermoir, sur la poitrine
        int n = 60;
        Surface sp = new Surface(n, 1, 0, 0, true, null);
        for (int i = 0; i <= n; i++) {
            double a = (double) i / n * Math.PI * 2 * 1.6, r = (0.3 + (double) i / n * 1.9) * 0.75;
            for (int j = 0; j <= 1; j++) {
                double rr = r + (j == 0 ? -0.22 : 0.22);
                sp.mettre(i, j, v(Math.cos(a) * rr, 21.8 + Math.sin(a) * rr, 4.25), 0, 0, null);
            }
        }
        sp.normales();
        manteau.add(sp);
    }

    // ---- le dessin d'une surface, chaque image

    private static final float[] COEUR = {196 / 255F, 14 / 255F, 8 / 255F}, VIF = {1F, 60 / 255F, 30 / 255F}, BORD = {1F, 228 / 255F, 210 / 255F};
    private static final float[] NOIR = {0.09F, 0.012F, 0.008F};

    /** Le repère d'un joueur : ses pieds, sa gauche, le haut, l'avant (à la taille du modèle : un pixel = 0,9375/16 bloc). */
    private record Repere(Vec3 pieds, Vec3 gauche, Vec3 avant) {
        static final double PX = 0.9375 / 16;

        Vec3 monde(double x, double y, double z) {
            return pieds.add(gauche.scale(x * PX)).add(0, y * PX, 0).add(avant.scale(z * PX));
        }

        Vec3 dir(double x, double y, double z) {
            return gauche.scale(x).add(0, y, 0).add(avant.scale(z));
        }
    }

    private static Repere repere(Player p, float partiel) {
        float lacet = Mth.rotLerp(partiel, p.yBodyRotO, p.yBodyRot) * Mth.DEG_TO_RAD;
        Vec3 avant = new Vec3(-Mth.sin(lacet), 0, Mth.cos(lacet));
        Vec3 gauche = new Vec3(Mth.cos(lacet), 0, Mth.sin(lacet));
        Vec3 pieds = p.getPosition(partiel);
        if (p.isCrouching()) {
            pieds = pieds.add(0, -0.2, 0).add(avant.scale(-0.12));
        }
        return new Repere(pieds, gauche, avant);
    }

    private static float lisse(float a, float b, float x) {
        float t = Mth.clamp((x - a) / (b - a), 0, 1);
        return t * t * (3 - 2 * t);
    }

    private static void dessiner(PoseStack ps, VertexConsumer c, Vec3 cam, Repere r, Surface s, float temps, float alpha, float pousse, float contour) {
        int n = (s.nu + 1) * (s.nv + 1);
        Vec3[] w = new Vec3[n];
        Vec3[] nm = new Vec3[n];
        int[] couleurs = new int[n];
        for (int k = 0; k < n; k++) {
            float o = s.onde[k];
            double dep = Math.sin(temps * 2.6 + o * 5.5 + s.phase) * s.ampleur * o * o;
            double x = s.pos[k * 3] + s.dir[k * 3] * dep, y = s.pos[k * 3 + 1] + s.dir[k * 3 + 1] * dep, z = s.pos[k * 3 + 2] + s.dir[k * 3 + 2] * dep;
            if (s.racine != null && pousse < 0.999F) {
                x = s.racine[0] + (x - s.racine[0]) * pousse;
                y = s.racine[1] + (y - s.racine[1]) * pousse;
                z = s.racine[2] + (z - s.racine[2]) * pousse;
            }
            w[k] = r.monde(x, y, z);
            nm[k] = r.dir(s.nrm[k * 3], s.nrm[k * 3 + 1], s.nrm[k * 3 + 2]);
            float rr, gg, bb, aa;
            if (s.sombre) {
                rr = NOIR[0];
                gg = NOIR[1];
                bb = NOIR[2];
                aa = alpha;
            } else {
                Vec3 vue = cam.subtract(w[k]).normalize();
                float f = (float) Math.pow(1 - Math.abs(vue.dot(nm[k])), 2);
                float vif = Mth.clamp(lisse(0.15F, 0.75F, s.bord[k]) + 0.3F * f, 0, 1);
                float bd = lisse(0.7F, 0.88F, Math.max(s.bord[k], f * contour));
                rr = Mth.lerp(bd, Mth.lerp(vif, COEUR[0], VIF[0]), BORD[0]);
                gg = Mth.lerp(bd, Mth.lerp(vif, COEUR[1], VIF[1]), BORD[1]);
                bb = Mth.lerp(bd, Mth.lerp(vif, COEUR[2], VIF[2]), BORD[2]);
                aa = alpha * Mth.lerp(bd, 0.88F, 1F);
            }
            couleurs[k] = (int) (aa * 255) << 24 | (int) (rr * 255) << 16 | (int) (gg * 255) << 8 | (int) (bb * 255);
        }
        PoseStack.Pose pose = ps.last();
        for (int i = 0; i < s.nu; i++) {
            for (int j = 0; j < s.nv; j++) {
                int[] q = {s.idx(i, j), s.idx(i + 1, j), s.idx(i + 1, j + 1), s.idx(i, j + 1)};
                for (int a : q) {
                    Vec3 p = w[a].subtract(cam);
                    int col = couleurs[a];
                    c.addVertex(pose, (float) p.x, (float) p.y, (float) p.z).setColor(col >> 16 & 255, col >> 8 & 255, col & 255, col >>> 24)
                            .setUv(0.5F, 0.5F).setOverlay(OverlayTexture.NO_OVERLAY).setLight(LightTexture.FULL_BRIGHT)
                            .setNormal(pose, (float) nm[a].x, (float) nm[a].y, (float) nm[a].z);
                }
            }
        }
    }

    /** Les cornes en pavés au-dessus de la tête : elles continuent le contour rouge des yeux depuis les coins du haut du visage. */
    private static void cornes(PoseStack ps, MultiBufferSource b, Vec3 cam, Player p, float partiel, float k) {
        float lacet = Mth.rotLerp(partiel, p.yHeadRotO, p.yHeadRot) * Mth.DEG_TO_RAD;
        float tangage = Mth.lerp(partiel, p.xRotO, p.getXRot()) * Mth.DEG_TO_RAD;
        Vec3 avant = new Vec3(-Mth.sin(lacet), 0, Mth.cos(lacet)), gauche = new Vec3(Mth.cos(lacet), 0, Mth.sin(lacet));
        double px = Repere.PX;
        Vec3 cou = p.getPosition(partiel).add(0, (p.isCrouching() ? 20.5 : 24) * px, 0);
        // la tête penche avec le regard (vers le bas : le haut de la tête part vers l'avant)
        Vec3 haut = new Vec3(0, Math.cos(tangage), 0).add(avant.scale(Math.sin(tangage)));
        Vec3 devant = avant.scale(Math.cos(tangage)).add(0, -Math.sin(tangage), 0);
        VertexConsumer c = DessinClair.plein(b);
        int lumiere = net.minecraft.client.renderer.LevelRenderer.getLightColor(p.level(), p.blockPosition().above());
        double[] tailles = {2.2, 1.8, 1.4, 1.0, 0.6};
        for (int sx = -1; sx <= 1; sx += 2) {
            for (int i = 0; i < 5; i++) {
                double t = tailles[i] * k;
                double x = sx * (3.5 - tailles[i] / 2 + 0.55 + 0.3 * i + 0.07 * i * i), y = 8.5 + 1.45 * i, z = 3.4 - tailles[i] / 2 - 0.2 * i * i;
                Vec3 centre = cou.add(gauche.scale(x * px)).add(haut.scale(y * px * k + (1 - k) * 4 * px)).add(devant.scale(z * px));
                // un peu penchées vers l'extérieur en montant
                double a = -sx * 0.18 * i;
                Vec3 ux = gauche.scale(Math.cos(a)).add(haut.scale(Math.sin(a))), uy = haut.scale(Math.cos(a)).subtract(gauche.scale(Math.sin(a)));
                boite(ps, c, cam, centre, ux.scale(t / 2 * px), uy.scale(1.7 / 2 * px * k), devant.scale(t / 2 * px),
                        i == 4 ? 0xFF3A0604 : 0xFFD4160C, lumiere);
            }
        }
    }

    private static void boite(PoseStack ps, VertexConsumer c, Vec3 cam, Vec3 o, Vec3 x, Vec3 y, Vec3 z, int argb, int lumiere) {
        Vec3[] axes = {x, y, z};
        for (int f = 0; f < 6; f++) {
            Vec3 n = axes[f / 2].scale(f % 2 == 0 ? 1 : -1), u = axes[(f / 2 + 1) % 3], w = axes[(f / 2 + 2) % 3];
            Vec3 m = o.add(n);
            int col = f == 2 ? argb : DessinClair.ombrer(argb, f == 3 ? 0.6F : 0.8F);
            DessinClair.face(ps, c, cam, m.subtract(u).subtract(w), m.add(u).subtract(w), m.add(u).add(w), m.subtract(u).add(w), n.normalize(), col, lumiere);
        }
    }

    private static void dessinerMode(PoseStack ps, MultiBufferSource b, Vec3 cam, float partiel, float temps) {
        Minecraft mc = Minecraft.getInstance();
        construire();
        List<UUID> qui = new ArrayList<>(FINS.keySet());
        qui.addAll(ETEINTS.keySet());
        for (UUID id : qui) {
            float[] pr = presence(id);
            Player p = mc.level.getPlayerByUUID(id);
            if (pr == null || p == null || p.isInvisible() || ClientMinatoCombo.cache(p) || ClientBijuuDama.cache(p)
                    || (p == mc.player && mc.options.getCameraType().isFirstPerson())) {
                continue;
            }
            Repere r = repere(p, partiel);
            VertexConsumer c = b.getBuffer(RenderType.entityTranslucent(ModeleVent.BLANC));
            // soi-même vu de dos (F5) : les langues sont entre la caméra et ce qu'on vise, on les voit à travers
            float voile = p == mc.player && mc.options.getCameraType() == net.minecraft.client.CameraType.THIRD_PERSON_BACK ? 0.35F : 1F;
            for (Surface s : langues) {
                dessiner(ps, c, cam, r, s, temps, 0.92F * pr[0] * voile, pr[1], 1.15F);
            }
            for (Surface s : manteau) {
                dessiner(ps, c, cam, r, s, temps, 0.92F * pr[0], 1, 0.7F);
            }
            if (FINS.containsKey(id)) {
                cornes(ps, b, cam, p, partiel, pr[1] > 1 ? 1 : pr[1]);
            }
        }
    }

    // ================================================================ les effets des attaques

    private static final class Fx {
        UUID joueur;
        int sorte;
        List<Vec3> pts = new ArrayList<>();
        List<Integer> ids = new ArrayList<>();
        long debut;
        /** Pour Mille Flashs : ce que dit le temps arrêté (les places de ceux qui sont pris, les arrêts de Minato). */
        List<Vec3> places = new ArrayList<>(), arrets = new ArrayList<>();
        boolean arrete;

        double ticks() {
            return secondes(debut) * 20;
        }
    }

    private static final List<Fx> EFFETS = new ArrayList<>();

    private static Vec3 vec(Vector3f p) {
        return new Vec3(p.x(), p.y(), p.z());
    }

    public static void effet(MinatoRouge.Effet p) {
        Fx f = new Fx();
        f.joueur = p.joueur();
        f.sorte = p.sorte();
        for (Vector3f v : p.points()) {
            f.pts.add(vec(v));
        }
        f.ids.addAll(p.ids());
        f.debut = System.nanoTime();
        if (moi(p.joueur())) {
            int touche = switch (p.sorte()) {
                case E_RONDE -> DROIT;
                case E_LIGNE -> LIGNE;
                case E_CHARGE -> RASENGAN;
                case E_DOUBLES -> DOUBLES;
                default -> -1;
            };
            if (touche >= 0) {
                PRETS[touche] = System.nanoTime() + RECHARGES[touche] * 50_000_000L;
            }
        }
        if (p.sorte() == E_ARRET) {
            // le temps s'arrête : on complète le Mille Flashs en cours de ce Minato (ou on le crée, arrivé en retard)
            Fx m = EFFETS.stream().filter(e -> e.sorte == E_MILLE && e.joueur.equals(p.joueur())).findFirst().orElse(null);
            if (m == null) {
                m = new Fx();
                m.joueur = p.joueur();
                m.sorte = E_MILLE;
                m.pts.add(f.pts.get(0));
                m.debut = System.nanoTime() - MILLE_ARRET * 50_000_000L;
                EFFETS.add(m);
            }
            int n = p.ids().size();
            m.ids.clear();
            m.ids.addAll(p.ids());
            m.places = new ArrayList<>(f.pts.subList(1, 1 + n));
            m.arrets = new ArrayList<>(f.pts.subList(1 + n, f.pts.size()));
            m.arrete = true;
            return;
        }
        EFFETS.add(f);
    }

    private static double duree(Fx f) {
        return switch (f.sorte) {
            case E_MODE -> 24;
            case E_DOUBLE -> 12;
            case E_RONDE -> RONDE_FIN;
            case E_TALON -> 24;
            case E_LIGNE -> LIGNE_EXPLOSION + 10;
            case E_EXPLOSE -> 20;
            case E_CHARGE -> RASENGAN_TP + 1;
            case E_IMPACT -> RASENGAN_IMPACT - RASENGAN_TP + 2;
            case E_DOUBLES -> DOUBLES_FIN + 10;
            case E_MILLE -> MILLE_FIN;
            default -> 20;
        };
    }

    /** La main droite d'un joueur (à peu près), dans le monde. */
    private static Vec3 main(Player p, float partiel) {
        Repere r = repere(p, partiel);
        return r.pieds().add(0, 1.2, 0).add(r.avant().scale(0.5)).add(r.gauche().scale(-0.3));
    }

    private static float lacetVers(Vec3 de, Vec3 vers) {
        Vec3 d = vers.subtract(de);
        return (float) Math.toDegrees(Math.atan2(-d.x, d.z));
    }

    /** Un double rouge transparent, dans une pose (le geste du coup de kunai si coup est entre 0 et 1). */
    private static void double_(PoseStack ps, MultiBufferSource b, Vec3 cam, Vec3 pieds, float lacet, double coup, float alpha) {
        if (alpha <= 0.02F) {
            return;
        }
        DessinClair.Pose pose = new DessinClair.Pose();
        double u = Mth.clamp(coup, 0, 1);
        pose.bras((float) Mth.lerp(u, -2.6, -0.6), (float) Mth.lerp(u, -0.9, 0.7), 0.4F, 0.3F).jambes(0.35F, -0.35F).penche(0.1F);
        ps.pushPose();
        DessinClair.repere(ps, cam, pieds, lacet, 1, pose.penche);
        DessinClair.pantin(ps, b, pose, (int) (alpha * 140) << 24 | 0xFF2A14, -1, LightTexture.FULL_BRIGHT);
        ps.popPose();
    }

    private static void flash(PoseStack ps, MultiBufferSource b, Vec3 cam, Vec3 p, double age, double vie, double taille) {
        if (age < 0 || age > vie) {
            return;
        }
        double u = age / vie;
        DessinClair.eclat(ps, b, cam, p, taille * (0.5 + u), 1F, 0.3F, 0.18F, (float) (1 - u * u), u * 2);
    }

    /** Un trait rouge qui brille de a à z ; on n'en dessine rien à moins de 2,5 blocs de la caméra (sinon il couvre l'écran). */
    private static void trait(PoseStack ps, MultiBufferSource b, Vec3 cam, Vec3 a, Vec3 z, double w, float alpha) {
        if (alpha <= 0.01F) {
            return;
        }
        Vec3 d = z.subtract(a);
        double l = d.length();
        if (l < 1.0E-3) {
            return;
        }
        Vec3 u = d.scale(1 / l);
        // la partie du segment trop près de la caméra : [t0, t1] le long de a → z
        double proj = cam.subtract(a).dot(u), dist2 = cam.subtract(a.add(u.scale(proj))).lengthSqr(), r = 2.5;
        if (dist2 < r * r) {
            double demi = Math.sqrt(r * r - dist2), t0 = proj - demi, t1 = proj + demi;
            if (t0 > 0.2) {
                DessinClair.rayon(ps, b, cam, a, a.add(u.scale(Math.min(l, t0))), w, 1F, 0.2F, 0.1F, alpha);
            }
            if (t1 < l - 0.2) {
                DessinClair.rayon(ps, b, cam, a.add(u.scale(Math.max(0, t1))), z, w, 1F, 0.2F, 0.1F, alpha);
            }
            return;
        }
        DessinClair.rayon(ps, b, cam, a, z, w, 1F, 0.2F, 0.1F, alpha);
    }

    private static void dessinerEffets(PoseStack ps, MultiBufferSource b, Vec3 cam, float partiel, float temps) {
        Minecraft mc = Minecraft.getInstance();
        for (Fx f : EFFETS) {
            double t = f.ticks();
            Player lui = mc.level.getPlayerByUUID(f.joueur);
            VertexConsumer lueur = DessinClair.lueur(b);
            switch (f.sorte) {
                case E_MODE -> {
                    Vec3 o = f.pts.get(0);
                    flash(ps, b, cam, o.add(0, 1.1, 0), t, 12, 2.4);
                    double u = t / 24;
                    DessinClair.anneauSol(ps, lueur, cam, o.add(0, 0.05, 0), 1 + 13 * (1 - Math.pow(1 - u, 3)), 0.6, 1F, 0.25F, 0.12F, (float) (1 - u));
                }
                case E_DOUBLE -> {
                    Vec3 de = f.pts.get(0), vers = f.pts.get(1);
                    double u = t / 12;
                    double_(ps, b, cam, de, lacetVers(de, vers), t / 4, (float) (0.9 * (1 - u)));
                    flash(ps, b, cam, de.add(0, 1, 0), t, 5, 0.7);
                    if (t > 1 && t < 6) {
                        Vec3 c = vers.add(0, 1.1, 0), d = c.subtract(de.add(0, 1.1, 0)).normalize();
                        Vec3 cote = d.cross(new Vec3(0, 1, 0)).normalize();
                        trait(ps, b, cam, c.add(cote.scale(-0.9)).add(0, 0.7, 0), c.add(cote.scale(0.9)).add(0, -0.7, 0), 0.06, (float) (1 - (t - 1) / 5));
                    }
                }
                case E_RONDE -> dessinerRonde(ps, b, cam, f, t);
                case E_TALON -> {
                    Vec3 o = f.pts.get(0);
                    flash(ps, b, cam, o.add(0, 0.4, 0), t, 10, 3);
                    double u = t / 24;
                    DessinClair.anneauSol(ps, lueur, cam, o.add(0, 0.05, 0), 0.5 + 7 * (1 - Math.pow(1 - u, 3)), 0.5, 1F, 0.3F, 0.15F, (float) (1 - u));
                    DessinClair.disque(ps, DessinClair.voile(b), cam, o.add(0, 0.03, 0), 1.8, (int) (150 * (1 - u)) << 24 | 0x2A1610, false);
                }
                case E_LIGNE -> {
                    Vec3 a = f.pts.get(0), z = f.pts.get(1);
                    double u = t / (LIGNE_EXPLOSION + 10);
                    trait(ps, b, cam, a.add(0, 1, 0), z.add(0, 1, 0), 0.12, (float) (1 - u));
                    for (int q = 0; q < 6; q++) {
                        Vec3 g = a.add(z.subtract(a).scale((q + 1) / 7.0));
                        double_(ps, b, cam, g, lacetVers(a, z), 0, (float) (0.6 * (1 - Mth.clamp((t - q * 0.6) / 8, 0, 1))));
                    }
                    for (int q = 2; q < f.pts.size(); q++) {
                        if (t < LIGNE_EXPLOSION) {
                            Vec3 m = f.pts.get(q);
                            DessinClair.anneau(ps, lueur, cam, m, 0.7 + 0.2 * Math.sin(t * 1.3), 0.08, new Vec3(1, 0, 0), new Vec3(0, 0, 1), 1F, 0.2F, 0.1F, 0.9F);
                        }
                    }
                }
                case E_EXPLOSE -> {
                    Vec3 o = f.pts.get(0);
                    double gros = f.ids.isEmpty() ? 1 : 1.8;
                    flash(ps, b, cam, o, t, 12, 1.4 * gros);
                    double u = t / 20;
                    DessinClair.anneau(ps, lueur, cam, o, 0.3 + 2.5 * gros * u, 0.12, new Vec3(1, 0, 0), new Vec3(0, 0, 1), 1F, 0.3F, 0.15F, (float) (1 - u));
                }
                case E_CHARGE -> {
                    if (lui != null) {
                        double r = 0.35 * Mth.clamp(t / RASENGAN_TP, 0, 1);
                        rasengan(ps, b, cam, main(lui, partiel), r, temps);
                    }
                }
                case E_IMPACT -> {
                    Vec3 o = f.pts.get(0), d = f.pts.get(1).lengthSqr() < 1.0E-4 ? Vec3.ZERO : f.pts.get(1).normalize();
                    double r = 0.4 + 0.35 * Mth.clamp(t / (RASENGAN_IMPACT - RASENGAN_TP), 0, 1);
                    rasengan(ps, b, cam, o.subtract(d.scale(0.5)), r, temps);
                    flash(ps, b, cam, o, t, 6, 1);
                }
                case E_DOUBLES -> dessinerDoubles(ps, b, cam, f, t);
                case E_MILLE -> dessinerMille(ps, b, cam, f, t, lui, partiel, temps);
                default -> {
                }
            }
        }
    }

    /** Le Rasengan rouge : un cœur blanc, une boule de chakra rouge, trois anneaux qui tournent vite. */
    private static void rasengan(PoseStack ps, MultiBufferSource b, Vec3 cam, Vec3 c, double r, float temps) {
        if (r <= 0.02) {
            return;
        }
        Effets.sphere(ps, b, c.subtract(cam), r * 0.45, 1F, 0.95F, 0.92F, 1F);
        Effets.sphere(ps, b, c.subtract(cam), r, 1F, 0.18F, 0.08F, 0.75F);
        VertexConsumer l = DessinClair.lueur(b);
        for (int k = 0; k < 3; k++) {
            double a = temps * (9 + k * 4) + k * 2.1, e = temps * (6 - k * 2) + k;
            Vec3 u = new Vec3(Math.cos(a), Math.sin(e) * 0.5, Math.sin(a)).normalize();
            Vec3 w = u.cross(new Vec3(Math.sin(e), 1, Math.cos(e))).normalize();
            DessinClair.anneau(ps, l, cam, c, r * (1.12 + 0.08 * k), r * 0.06, u, w, 1F, 0.75F, 0.65F, 0.85F);
        }
        DessinClair.eclat(ps, b, cam, c, r * 1.4, 1F, 0.3F, 0.18F, 0.5F, temps);
    }

    private static void dessinerRonde(PoseStack ps, MultiBufferSource b, Vec3 cam, Fx f, double t) {
        Vec3 c = f.pts.get(0), depart = f.pts.get(7);
        List<Vec3> k = f.pts.subList(1, 7);
        VertexConsumer lueur = DessinClair.lueur(b);
        double fin = RONDE_TALON + 8;
        // les kunai : ils volent en arc depuis sa main, puis restent plantés jusqu'au talon
        for (int i = 0; i < 6; i++) {
            double u = Mth.clamp((t - i * 0.6) / (RONDE_SAUT - 4), 0, 1);
            if (t > fin) {
                flash(ps, b, cam, k.get(i).add(0, 0.3, 0), t - fin, 6, 0.6);
                continue;
            }
            if (u <= 0) {
                continue;
            }
            Vec3 p = depart.add(k.get(i).subtract(depart).scale(u)).add(0, 1.6 * Math.sin(u * Math.PI), 0);
            Vec3 dir = u < 1 ? k.get(i).subtract(depart).add(0, -1 - 3 * u, 0) : new Vec3(0.1, -1, 0.1);
            ClientMinato.dessinerKunai(ps, b, (u < 1 ? p : k.get(i).add(0, 0.36, 0)).subtract(cam), dir);
        }
        // le chemin : un trait au sol d'un kunai au suivant à chaque saut (il dessine l'étoile), qui s'allume à la fin
        for (int s = 1; s < RONDE_ORDRE.length; s++) {
            double ts = RONDE_SAUT + s * RONDE_PAS;
            if (t < ts || RONDE_ORDRE[s] == RONDE_ORDRE[s - 1]) {
                continue;
            }
            float a = (float) (t > RONDE_LEVE ? 1 : 0.75) * (float) (1 - Mth.clamp((t - fin) / 8, 0, 1));
            trait(ps, b, cam, k.get(RONDE_ORDRE[s - 1]).add(0, 0.08, 0), k.get(RONDE_ORDRE[s]).add(0, 0.08, 0), t > RONDE_LEVE ? 0.1 : 0.06, a);
        }
        for (int s = 0; s < RONDE_ORDRE.length; s++) {
            double ts = RONDE_SAUT + s * RONDE_PAS;
            Vec3 p = k.get(RONDE_ORDRE[s]);
            flash(ps, b, cam, p.add(0, 1, 0), t - ts, 6, 0.9);
            double_(ps, b, cam, p, lacetVers(p, c), (t - ts) / 3, (float) (0.5 * (1 - Mth.clamp((t - ts) / 8, 0, 1))) * (t >= ts ? 1 : 0));
        }
        if (t > RONDE_LEVE && t < RONDE_TALON) {
            double u = (t - RONDE_LEVE) / (RONDE_TALON - RONDE_LEVE);
            Effets.tube(ps, b, c.subtract(cam), c.add(0, 6, 0).subtract(cam), 0.9, 1F, 0.2F, 0.1F, (float) (0.35 * Math.sin(u * Math.PI)));
        }
        DessinClair.anneauSol(ps, lueur, cam, c.add(0, 0.06, 0), RONDE_RAYON, 0.05, 1F, 0.25F, 0.12F, t < fin ? 0.5F : 0);
    }

    private static void dessinerDoubles(PoseStack ps, MultiBufferSource b, Vec3 cam, Fx f, double t) {
        Vec3 o = f.pts.get(0);
        flash(ps, b, cam, o.add(0, 1, 0), t, 8, 1.6);
        for (int i = 1; i < f.pts.size(); i++) {
            Vec3 c = f.pts.get(i);
            Vec3 d = c.subtract(o).multiply(1, 0, 1);
            d = d.lengthSqr() < 1.0E-4 ? new Vec3(1, 0, 0) : d.normalize();
            Vec3 proche = c.subtract(d.scale(1.4)), derriere = c.add(d.scale(1.4));
            if (t < DOUBLES_COUP2 - 1) {
                double u = Mth.clamp(t / DOUBLES_COUP1, 0, 1);
                Vec3 p = o.add(proche.subtract(o).scale(u));
                double_(ps, b, cam, p, lacetVers(p, c), t < DOUBLES_COUP1 ? 0 : (t - DOUBLES_COUP1) / 3, 0.75F);
                if (u < 1) {
                    trait(ps, b, cam, o.add(0, 1, 0), p.add(0, 1, 0), 0.05, 0.8F);
                }
            } else if (t < DOUBLES_FIN) {
                double_(ps, b, cam, derriere, lacetVers(derriere, c), (t - DOUBLES_COUP2) / 3, 0.75F);
            }
            flash(ps, b, cam, derriere.add(0, 1, 0), t - (DOUBLES_COUP2 - 1), 5, 0.8);
            flash(ps, b, cam, derriere.add(0, 1, 0), t - DOUBLES_FIN, 8, 1.4);
        }
    }

    // ---------------------------------------------------------------- Mille Flashs

    private static double tempsArret(int s) {
        return MILLE_FLASH + (double) s * (MILLE_FLASH_FIN - MILLE_FLASH) / MILLE_ARRETS;
    }

    private static void dessinerMille(PoseStack ps, MultiBufferSource b, Vec3 cam, Fx f, double t, Player lui, float partiel, float temps) {
        Vec3 c = f.pts.get(0);
        VertexConsumer lueur = DessinClair.lueur(b);
        // le kunai planté au centre
        if (t > 4 && t < MILLE_REPRISE + 20) {
            ClientMinato.dessinerKunai(ps, b, c.add(0.2, 0.36, 0.3).subtract(cam), new Vec3(0.05, -1, 0.1));
        }
        flash(ps, b, cam, c.add(0, 0.5, 0), t - 4, 10, 2);
        // le sceau : il s'étend, puis s'efface après la reprise
        double ouvert = 1 - Math.pow(1 - Mth.clamp((t - MILLE_SCEAU) / (MILLE_ARRET - MILLE_SCEAU), 0, 1), 3);
        float alpha = (float) (t < MILLE_REPRISE ? 1 : 1 - Mth.clamp((t - MILLE_REPRISE) / 40, 0, 1));
        if (ouvert > 0.01 && alpha > 0.01) {
            sceau(ps, lueur, cam, c.add(0, 0.06, 0), MILLE_RAYON * ouvert, alpha, temps);
        }
        if (t > MILLE_SCEAU && t < MILLE_ARRET + 4) {
            DessinClair.anneauSol(ps, lueur, cam, c.add(0, 0.08, 0), MILLE_RAYON * 1.05 * ouvert, 0.8, 1F, 0.4F, 0.2F, (float) (1 - ouvert * 0.6));
        }
        if (f.arrete) {
            // les kunai figés au-dessus de ceux qui sont pris
            for (Vec3 p : f.places) {
                if (t >= MILLE_ARRET + 2 && t < MILLE_REPRISE) {
                    ClientMinato.dessinerKunai(ps, b, p.add(0, 3, 0).subtract(cam), new Vec3(0, -1, 0));
                    DessinClair.anneauSol(ps, lueur, cam, p.add(0, 0.07, 0), 1.1, 0.08, 1F, 0.25F, 0.12F, 0.8F);
                }
                flash(ps, b, cam, p.add(0, 3, 0), t - MILLE_ARRET - 2, 6, 0.6);
                flash(ps, b, cam, p.add(0, 1, 0), t - MILLE_REPRISE, 12, 2.2);
            }
            // mille flashs : un trait d'un arrêt au suivant, qui reste jusqu'à la reprise (et s'allume juste avant)
            for (int s = 0; s < f.arrets.size(); s++) {
                double ts = tempsArret(s);
                if (t < ts) {
                    break;
                }
                Vec3 p = f.arrets.get(s);
                flash(ps, b, cam, p.add(0, 1, 0), t - ts, 6, 1.1);
                Vec3 vers = f.places.isEmpty() ? c : f.places.get(s % f.places.size());
                double_(ps, b, cam, p, lacetVers(p, vers), (t - ts) / 2, (float) (0.5 * (1 - Mth.clamp((t - ts) / 10, 0, 1))));
                if (s > 0 && t < MILLE_REPRISE + 4) {
                    float a = t > MILLE_REPRISE - 6 ? 1F : 0.7F;
                    trait(ps, b, cam, f.arrets.get(s - 1).add(0, 1, 0), p.add(0, 1, 0), t > MILLE_REPRISE - 6 ? 0.12 : 0.07, a);
                }
            }
        }
        // le Rasengan géant : il se forme au-dessus de lui dans le ciel, puis il l'écrase au centre
        if (lui != null && t >= MILLE_CIEL && t < MILLE_DESCENTE) {
            double r = 5.5 * lisse(0, 1, (float) ((t - MILLE_CIEL) / (MILLE_DESCENTE - MILLE_CIEL - 8)));
            Vec3 h = lui.getPosition(partiel).add(0, 2 + r, 0);
            rasengan(ps, b, cam, h, r, temps);
            for (int q = 0; q < 3; q++) {
                mc().level.addParticle(BRAISE, h.x + (HASARD.nextDouble() - 0.5) * r * 3, h.y + (HASARD.nextDouble() - 0.5) * r * 3, h.z + (HASARD.nextDouble() - 0.5) * r * 3, 0, 0, 0);
            }
        }
        if (t >= MILLE_DESCENTE && t < MILLE_REPRISE + 2) {
            rasengan(ps, b, cam, c.add(0, 4.5, 0), 5.5 - 1.5 * (t - MILLE_DESCENTE) / (MILLE_REPRISE - MILLE_DESCENTE), temps);
            if (t < MILLE_DESCENTE + 6) {
                trait(ps, b, cam, c.add(0, MILLE_HAUT + 4, 0), c.add(0, 1, 0), 0.6, (float) (1 - (t - MILLE_DESCENTE) / 6));
            }
        }
        // la reprise : le dôme rouge, les ondes
        if (t >= MILLE_REPRISE) {
            double e = t - MILLE_REPRISE;
            double r = MILLE_RAYON * 1.15 * (1 - Math.pow(1 - Mth.clamp(e / 24, 0, 1), 3));
            float a = (float) (0.55 * (1 - Mth.clamp((e - 24) / 22, 0, 1)));
            if (a > 0.01) {
                Effets.sphere(ps, b, c.subtract(cam), r, 1F, 0.2F, 0.08F, a);
            }
            DessinClair.anneauSol(ps, lueur, cam, c.add(0, 0.1, 0), MILLE_RAYON * 1.6 * Mth.clamp(e / 28, 0, 1), 1.2, 1F, 0.3F, 0.12F, (float) (1 - Mth.clamp(e / 28, 0, 1)));
            flash(ps, b, cam, c.add(0, 2, 0), e, 16, 7);
        }
    }

    private static Minecraft mc() {
        return Minecraft.getInstance();
    }

    /** Le sceau au sol : des cercles, l'hexagramme, des formules en anneau, la spirale au centre. */
    private static void sceau(PoseStack ps, VertexConsumer c, Vec3 cam, Vec3 o, double r, float a, float temps) {
        for (double[] k : new double[][] {{1, 0.5}, {0.94, 0.2}, {0.66, 0.3}, {0.6, 0.15}, {0.24, 0.3}}) {
            DessinClair.anneauSol(ps, c, cam, o, r * k[0], k[1], 1F, 0.24F, 0.1F, a);
        }
        for (int d = 0; d < 2; d++) {
            for (int q = 0; q < 3; q++) {
                double a0 = d * Math.PI / 3 + q * Math.PI * 2 / 3 - Math.PI / 2, a1 = a0 + Math.PI * 2 / 3;
                DessinClair.trait(ps, c, cam, o.add(Math.cos(a0) * r * 0.94, 0, Math.sin(a0) * r * 0.94), o.add(Math.cos(a1) * r * 0.94, 0, Math.sin(a1) * r * 0.94),
                        0.25, 1F, 0.24F, 0.1F, a);
            }
        }
        // les formules : des petits traits en anneau, qui tournent lentement
        for (double[] k : new double[][] {{0.97, 90}, {0.63, 60}, {0.27, 24}}) {
            int n = (int) k[1];
            for (int q = 0; q < n; q++) {
                double an = q * Math.PI * 2 / n + temps * 0.05;
                Vec3 p = o.add(Math.cos(an) * r * k[0], 0, Math.sin(an) * r * k[0]);
                Vec3 radial = new Vec3(Math.cos(an), 0, Math.sin(an));
                DessinClair.trait(ps, c, cam, p.subtract(radial.scale(r * 0.012)), p.add(radial.scale(r * 0.012)), 0.12, 1F, 0.3F, 0.15F, a * 0.8F);
            }
        }
        Vec3 prec = o;
        for (int q = 1; q <= 60; q++) {
            double an = q / 60.0 * Math.PI * 2 * 2.2, rr = r * 0.2 * q / 60.0;
            Vec3 p = o.add(Math.cos(an) * rr, 0, Math.sin(an) * rr);
            DessinClair.trait(ps, c, cam, prec, p, 0.22, 1F, 0.24F, 0.1F, a);
            prec = p;
        }
    }

    // ================================================================ le temps arrêté (l'écran gris)

    private static final ResourceLocation GRIS = Cabane.id("shaders/post/gris.json");

    /** Suis-je près d'un Mille Flashs dont le temps est arrêté ? */
    private static boolean tempsArrete() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return false;
        }
        for (Fx f : EFFETS) {
            double t = f.ticks();
            if (f.sorte == E_MILLE && t >= MILLE_ARRET && t < MILLE_REPRISE && f.pts.get(0).distanceTo(mc.player.position()) < MILLE_RAYON + 20) {
                return true;
            }
        }
        return false;
    }

    private static void gris() {
        Minecraft mc = Minecraft.getInstance();
        PostChain e = mc.gameRenderer.currentEffect();
        boolean pose = e != null && GRIS.toString().equals(e.getName());
        boolean voulu = tempsArrete();
        if (voulu && !pose) {
            ((fr.cabane.mixin.GameRendererAccess) mc.gameRenderer).cabane$chargerEffet(GRIS);
        } else if (!voulu && pose) {
            mc.gameRenderer.shutdownEffect();
        }
    }

    // ================================================================ l'écran : le flash rouge, le flash blanc

    private static void ecran(GuiGraphics g) {
        int w = g.guiWidth(), h = g.guiHeight();
        if (monFlash >= 0) {
            double t = secondes(monFlash);
            if (t > 0.8) {
                monFlash = -1;
            } else {
                g.fill(0, 0, w, h, (int) (170 * (1 - t / 0.8)) << 24 | 0xFF2A10);
            }
        }
        if (flashBlanc >= 0) {
            double t = secondes(flashBlanc);
            if (t > 0.5) {
                flashBlanc = -1;
            } else {
                g.fill(0, 0, w, h, (int) (220 * (1 - t / 0.5)) << 24 | 0xFFF0E8);
            }
        }
    }

    // ================================================================ le panneau (à la place de celui de Minato)

    /** Le panneau du mode rouge : vrai s'il s'est dessiné (alors celui de Minato ne se dessine pas). */
    static boolean panneau(GuiGraphics g) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || !actif(mc.player.getUUID())) {
            return false;
        }
        long fin = FINS.get(mc.player.getUUID());
        int reste = (int) Math.max(0, Math.ceil((fin - System.nanoTime()) / 1e9));
        double[] r = new double[4];
        for (int i = 0; i < 4; i++) {
            r[i] = Math.max(0, (PRETS[i] - System.nanoTime()) / 1e9);
        }
        String ulti = Clavier.nom(fr.cabane.client.hachibi4.ClientHachibi4.ULTI);
        String[][] lignes = {
                {Clavier.gauche(), "Coup éclair (+ double rouge)"},
                {Clavier.droite(), PanneauPouvoir.recharge("Ronde des flashs", r[DROIT])},
                {Clavier.accroupi() + " + " + Clavier.droite(), PanneauPouvoir.recharge("Ligne rouge", r[LIGNE])},
                {Clavier.accroupi() + " + " + Clavier.gauche(), PanneauPouvoir.recharge("Rasengan éclair", r[RASENGAN])},
                {Clavier.nom(ClientSharingan.SUSANO), PanneauPouvoir.recharge("Doubles éclair", r[DOUBLES])},
                {Clavier.nom(ClientSharingan.KAMUI), ClientContre.ligne()},
                {ulti, ultiFait ? "Mille Flashs (fait)" : "Mille Flashs"}};
        boolean[] gris = {false, r[DROIT] > 0, r[LIGNE] > 0, r[RASENGAN] > 0, r[DOUBLES] > 0, ClientContre.grise(), ultiFait};
        PanneauPouvoir.dessiner(g, mc.font, String.format("MINATO  ·  MODE ROUGE  %d:%02d", reste / 60, reste % 60), 0xFFFF3A2A, lignes, gris);
        return true;
    }

    /** Mille Flashs déjà lancé pendant ce mode (pour griser la ligne). */
    private static boolean ultiFait;

    // ================================================================ les touches

    /** La touche Technique en mode rouge : les doubles éclair. Vrai si c'était pour le mode rouge. */
    static boolean technique() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || !actif(mc.player.getUUID())) {
            return false;
        }
        PacketDistributor.sendToServer(new MinatoRouge.Touche(DOUBLES));
        return true;
    }

    // ================================================================ branchements

    @EventBusSubscriber(modid = Cabane.MODID, value = Dist.CLIENT)
    public static final class Couches {
        @SubscribeEvent
        public static void enregistrer(RegisterGuiLayersEvent event) {
            event.registerAboveAll(Cabane.id("minato_rouge"), (g, delta) -> ecran(g));
        }

        private Couches() {
        }
    }

    @EventBusSubscriber(modid = Cabane.MODID, value = Dist.CLIENT)
    public static final class Evenements {
        @SubscribeEvent
        public static void tick(ClientTickEvent.Post event) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.level == null || mc.player == null) {
                FINS.clear();
                DEBUTS.clear();
                ETEINTS.clear();
                EFFETS.clear();
                return;
            }
            FINS.entrySet().removeIf(e -> {
                if (System.nanoTime() > e.getValue() + 3_000_000_000L) {
                    ETEINTS.put(e.getKey(), System.nanoTime());
                    DEBUTS.remove(e.getKey());
                    return true;
                }
                return false;
            });
            UUID moi = mc.player.getUUID();
            if (!actif(moi)) {
                ultiFait = false;
            }
            for (Fx f : EFFETS) {
                if (f.sorte == E_MILLE && f.joueur.equals(moi)) {
                    ultiFait = true;
                }
                // le flash blanc quand le temps repart, pour ceux qui sont près
                if (f.sorte == E_MILLE && Math.abs(f.ticks() - MILLE_REPRISE) < 0.6 && flashBlanc < 0
                        && f.pts.get(0).distanceTo(mc.player.position()) < MILLE_RAYON + 20) {
                    flashBlanc = System.nanoTime();
                }
            }
            EFFETS.removeIf(f -> f.ticks() > duree(f) + 2);
            if (!mc.options.keyAttack.isDown()) {
                gaucheTenu = false;
            }
            // des braises qui montent autour de ceux qui sont en mode rouge
            for (UUID id : FINS.keySet()) {
                Player p = mc.level.getPlayerByUUID(id);
                boolean yeux = p == mc.player && mc.options.getCameraType().isFirstPerson();
                if (p != null && !yeux && !p.isInvisible()) {
                    for (int i = 0; i < 2; i++) {
                        double a = HASARD.nextDouble() * Math.PI * 2, r = 0.4 + HASARD.nextDouble() * 1.4;
                        mc.level.addParticle(BRAISE, p.getX() + Math.cos(a) * r, p.getY() + 0.2 + HASARD.nextDouble() * 2.2, p.getZ() + Math.sin(a) * r, 0, 0.04, 0);
                    }
                }
            }
            gris();
        }

        /**
         * En mode rouge, les clics changent : clic droit, la ronde des flashs ; accroupi + clic droit, la ligne rouge ;
         * accroupi + clic gauche, le Rasengan éclair. Le clic gauche debout reste le coup du mode Kurama.
         */
        @SubscribeEvent(priority = EventPriority.HIGH)
        public static void clic(InputEvent.InteractionKeyMappingTriggered event) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null || !actif(mc.player.getUUID())) {
                return;
            }
            boolean accroupi = mc.options.keyShift.isDown();
            if (event.isUseItem()) {
                event.setCanceled(true);
                event.setSwingHand(false);
                if (System.nanoTime() - dernierClic > 300_000_000L) {
                    dernierClic = System.nanoTime();
                    PacketDistributor.sendToServer(new MinatoRouge.Touche(accroupi ? LIGNE : DROIT));
                }
                return;
            }
            if (event.isAttack() && accroupi) {
                event.setCanceled(true);
                event.setSwingHand(false);
                if (!gaucheTenu) {
                    PacketDistributor.sendToServer(new MinatoRouge.Touche(RASENGAN));
                }
                gaucheTenu = true;
            }
        }

        @SubscribeEvent
        public static void dessin(RenderLevelStageEvent event) {
            if ((FINS.isEmpty() && ETEINTS.isEmpty() && EFFETS.isEmpty()) || event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) {
                return;
            }
            Minecraft mc = Minecraft.getInstance();
            if (mc.level == null) {
                return;
            }
            Vec3 cam = event.getCamera().getPosition();
            MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
            float partiel = event.getPartialTick().getGameTimeDeltaPartialTick(false);
            float temps = (mc.level.getGameTime() + partiel) / 20F;
            PoseStack ps = event.getPoseStack();
            dessinerMode(ps, buffers, cam, partiel, temps);
            buffers.endBatch();
            dessinerEffets(ps, buffers, cam, partiel, temps);
            buffers.endBatch();
        }

        private Evenements() {
        }
    }

    private ClientMinatoRouge() {
    }
}
