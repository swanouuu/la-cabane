package fr.cabane.client.pouvoir;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import fr.cabane.Cabane;
import fr.cabane.pouvoir.Pouvoirs;
import fr.cabane.pouvoir.Sharingan;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.PlayerSkin;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.joml.Matrix4f;
import org.lwjgl.glfw.GLFW;

/**
 * Les yeux du Susano chez chaque joueur (voir {@link Sharingan} et docs/sharingan) :
 * <ul>
 * <li>sur son visage, le motif (16 × 16) se dessine dans le pixel de la pupille de son skin, et le blanc de l'œil à côté
 * prend la couleur de l'œil ; quand il monte de niveau, le motif s'anime (les tomoe tombent, les lames se forment, le
 * Rinnegan monte du bord) ; au niveau 3, seul son œil gauche passe au Rinnegan, le droit garde le Mangekyo ;</li>
 * <li>celui qui le regardait dans les yeux voit, en grand sur son écran noir, ses deux yeux s'ouvrir et changer
 * (l'animation des aperçus validés), avec le son de l'éveil (le vrai, de l'anime, seulement dans le pack privé) ;</li>
 * <li>la touche d'ulti (J) : le Tsukuyomi, ou la météorite au niveau 3.</li>
 * </ul>
 */
public final class ClientSharingan {
    static final ResourceLocation PUPILLES = Cabane.id("textures/sharingan/pupilles.png");
    private static final ResourceLocation IRIS = Cabane.id("textures/sharingan/iris.png");
    private static final ResourceLocation BLANC = Cabane.id("textures/sharingan/oeil_blanc.png");
    private static final ResourceLocation MASQUE = Cabane.id("textures/sharingan/oeil_masque.png");
    private static final ResourceLocation TRAITS = Cabane.id("textures/sharingan/oeil_traits.png");
    private static final ResourceLocation HALO = Cabane.id("textures/sharingan/oeil_halo.png");
    /** Les mêmes calques, retournés, pour l'œil à gauche de l'écran. */
    private static final ResourceLocation BLANC_G = Cabane.id("textures/sharingan/oeil_blanc_g.png");
    private static final ResourceLocation MASQUE_G = Cabane.id("textures/sharingan/oeil_masque_g.png");
    private static final ResourceLocation TRAITS_G = Cabane.id("textures/sharingan/oeil_traits_g.png");
    private static final ResourceLocation HALO_G = Cabane.id("textures/sharingan/oeil_halo_g.png");
    /** Les images de chaque éveil dans les atlas (12 par seconde), comme outils/sharingan.mjs (EVEIL_IMAGES). */
    private static final int[] IMAGES = {62, 73, 78};
    private static final int PAR_SECONDE = 12;
    private static final int COLONNES = 16;
    private static final int ATLAS_PUPILLES_W = 256, ATLAS_PUPILLES_H = 224, ATLAS_IRIS_W = 768, ATLAS_IRIS_H = 672;
    /** La durée de l'animation à l'écran pour chaque éveil (en secondes), comme docs/sharingan/generateur/yeux.mjs. */
    private static final double[] DUREES = {5.2, 6.1, 6.5};
    /** Le Tsukuyomi : on montre le Mangekyo de 1,7 s à 3,7 s (2 s, le temps avant le saut dans le genjutsu). */
    private static final double TSU_DEBUT = 1.7, TSU_DUREE = 2.0;

    private static final SoundEvent SON_EVEIL = SoundEvent.createVariableRangeEvent(Cabane.id("sharingan.eveil"));
    private static final SoundEvent SON_MANGEKYO = SoundEvent.createVariableRangeEvent(Cabane.id("sharingan.mangekyo"));

    /** Les yeux de chacun : le niveau (1 à 3) et depuis quand (pour l'animation du motif sur le visage). */
    private record Yeux(int niveau, long depuis) {
    }

    private static final Map<UUID, Yeux> YEUX = new HashMap<>();
    /** La météorite déjà lancée pendant cet éveil (pour le panneau du joueur local). */
    static boolean meteoriteFaite;

    private static double secondes(long depuis) {
        return (System.nanoTime() - depuis) / 1e9;
    }

    private static double borne(double u) {
        return Math.max(0, Math.min(1, u));
    }

    private static double entre(double t, double a, double b) {
        return borne((t - a) / (b - a));
    }

    private static double sortie(double u) {
        return 1 - Math.pow(1 - borne(u), 3);
    }

    private static double lisse(double u) {
        u = borne(u);
        return u * u * (3 - 2 * u);
    }

    // ================================================================ l'état : les yeux (à part du Susano)

    /** Le Susano passe par ClientSusano ; les yeux ont leur propre message ({@link #yeux}). */
    public static void recevoir(Pouvoirs.Etat p) {
    }

    /** Le plus haut niveau que les yeux du joueur local peuvent atteindre. */
    private static int maxLocal;

    /** Les yeux d'un joueur changent (ou s'endorment, niveau 0). */
    public static void yeux(Sharingan.Yeux p) {
        Minecraft mc = Minecraft.getInstance();
        boolean moi = mc.player != null && p.joueur().equals(mc.player.getUUID());
        if (p.niveau() <= 0) {
            YEUX.remove(p.joueur());
            if (moi) {
                meteoriteFaite = false;
            }
            return;
        }
        if (moi) {
            maxLocal = p.max();
        }
        Yeux avant = YEUX.get(p.joueur());
        if (avant != null && avant.niveau() == p.niveau()) {
            return;
        }
        // une annonce : le motif s'anime depuis le début ; sinon (un joueur qui arrive) : le motif final
        YEUX.put(p.joueur(), new Yeux(p.niveau(), p.annonce() ? System.nanoTime() : System.nanoTime() - 100_000_000_000L));
    }

    public static int niveau(UUID joueur) {
        Yeux y = YEUX.get(joueur);
        return y == null ? 0 : y.niveau();
    }

    /** L'image de l'atlas pour cet éveil à ce moment (la dernière une fois l'animation finie). */
    private static int image(int eveil, double t) {
        int base = 0;
        for (int i = 0; i < eveil - 1; i++) {
            base += IMAGES[i];
        }
        return base + (int) Math.max(0, Math.min(IMAGES[eveil - 1] - 1, Math.floor(t * PAR_SECONDE)));
    }

    /** Les images des deux yeux [droit, gauche] (au niveau 3, le droit garde le Mangekyo final). */
    private static int[] images(int eveil, double t) {
        int g = image(eveil, t);
        return new int[]{eveil == 3 ? image(2, 100) : g, g};
    }

    // ================================================================ sur le visage

    /** Une couche de plus sur chaque joueur : le motif dans ses pupilles, et la lueur sur le blanc de ses yeux. */
    public static final class Couche extends RenderLayer<AbstractClientPlayer, PlayerModel<AbstractClientPlayer>> {
        public Couche(RenderLayerParent<AbstractClientPlayer, PlayerModel<AbstractClientPlayer>> parent) {
            super(parent);
        }

        @Override
        public void render(PoseStack ps, MultiBufferSource buffers, int lumiere, AbstractClientPlayer joueur, float marche, float vitesse,
                float partiel, float age, float tete, float tangage) {
            Yeux y = YEUX.get(joueur.getUUID());
            PlayerModel<AbstractClientPlayer> modele = getParentModel();
            if (y == null || joueur.isInvisible() || !modele.head.visible) {
                return;
            }
            int[] im = images(y.niveau(), secondes(y.depuis()));
            ps.pushPose();
            modele.head.translateAndRotate(ps);
            PoseStack.Pose pose = ps.last();
            VertexConsumer c = buffers.getBuffer(RenderType.entityCutoutNoCull(PUPILLES));
            // la rangée des yeux du skin : y de -4 à -3 sur la tête ; la pupille droite en x -2..-1, la gauche en 1..2
            float z = -4.03F / 16;
            for (int k = 0; k < 2; k++) {
                float x0 = k == 0 ? -2 : 1;
                int i = im[k];
                float u0 = (i % COLONNES) * 16F / ATLAS_PUPILLES_W, v0 = (i / COLONNES) * 16F / ATLAS_PUPILLES_H;
                float u1 = u0 + 16F / ATLAS_PUPILLES_W, v1 = v0 + 16F / ATLAS_PUPILLES_H;
                sommet(c, pose, x0 / 16, -4F / 16, z, u0, v0);
                sommet(c, pose, (x0 + 1) / 16, -4F / 16, z, u1, v0);
                sommet(c, pose, (x0 + 1) / 16, -3F / 16, z, u1, v1);
                sommet(c, pose, x0 / 16, -3F / 16, z, u0, v1);
            }
            // la lueur sur le blanc, plus forte contre la pupille (le blanc est à l'extérieur : x -3..-2 et 2..3)
            VertexConsumer l = buffers.getBuffer(Types.VOILE);
            Matrix4f m = pose.pose();
            float[][] couleurs = {{1F, 0.24F, 0.24F}, y.niveau() >= 3 ? new float[]{0.75F, 0.59F, 1F} : new float[]{1F, 0.24F, 0.24F}};
            for (int k = 0; k < 2; k++) {
                float[] col = couleurs[k];
                for (int q = 0; q < 4; q++) {
                    // de la pupille vers le coin de l'œil, la lueur s'éteint
                    float xa = k == 0 ? -2 - (q + 1) / 4F : 2 + q / 4F, a = 0.55F - 0.13F * q;
                    l.addVertex(m, xa / 16, -4F / 16, z - 0.0005F).setColor(col[0], col[1], col[2], a);
                    l.addVertex(m, (xa + 0.25F) / 16, -4F / 16, z - 0.0005F).setColor(col[0], col[1], col[2], a);
                    l.addVertex(m, (xa + 0.25F) / 16, -3F / 16, z - 0.0005F).setColor(col[0], col[1], col[2], a);
                    l.addVertex(m, xa / 16, -3F / 16, z - 0.0005F).setColor(col[0], col[1], col[2], a);
                }
            }
            ps.popPose();
        }

        private static void sommet(VertexConsumer c, PoseStack.Pose pose, float x, float y, float z, float u, float v) {
            c.addVertex(pose, x, y, z).setColor(255, 255, 255, 255).setUv(u, v).setOverlay(OverlayTexture.NO_OVERLAY)
                    .setLight(LightTexture.FULL_BRIGHT).setNormal(pose, 0, 0, -1);
        }
    }

    /** Les dessins translucides, sans texture (la lueur des yeux, les anneaux, la brume). */
    static final class Types extends RenderType {
        static final RenderType VOILE = create("cabane_sharingan_voile", DefaultVertexFormat.POSITION_COLOR, VertexFormat.Mode.QUADS, 262144, false, true,
                CompositeState.builder().setShaderState(POSITION_COLOR_SHADER).setWriteMaskState(COLOR_WRITE).setTransparencyState(TRANSLUCENT_TRANSPARENCY)
                        .setCullState(NO_CULL).createCompositeState(false));
        static final RenderType PLEIN = create("cabane_sharingan_plein", DefaultVertexFormat.POSITION_COLOR, VertexFormat.Mode.QUADS, 262144, false, false,
                CompositeState.builder().setShaderState(POSITION_COLOR_SHADER).setCullState(NO_CULL).setWriteMaskState(COLOR_DEPTH_WRITE)
                        .setDepthTestState(LEQUAL_DEPTH_TEST).createCompositeState(false));

        private Types() {
            super("cabane_sharingan_rien", DefaultVertexFormat.POSITION, VertexFormat.Mode.QUADS, 256, false, false, () -> { }, () -> { });
        }
    }

    // ================================================================ en grand, sur l'écran de celui qui regarde

    /** L'animation en cours sur mon écran : de qui, quel éveil, laquelle (éveil ou Tsukuyomi), depuis quand ; les sons joués. */
    private static UUID regardLanceur;
    private static int regardEveil;
    private static int regardMode = -1;
    private static long regardDebut;
    private static boolean sonJoue;

    public static void regard(Sharingan.Regard p) {
        regardLanceur = p.lanceur();
        regardEveil = Math.max(1, Math.min(3, p.eveil()));
        regardMode = p.mode();
        regardDebut = System.nanoTime();
        sonJoue = false;
        if (p.mode() == Sharingan.TSUKUYOMI) {
            jouer(SON_MANGEKYO);
            sonJoue = true;
        }
    }

    private static void jouer(SoundEvent son) {
        Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(son, 1F, 1F));
    }

    /** Les yeux sont en grand sur mon écran (le panneau du Susano se cache pendant ce temps). */
    public static boolean ecranActif() {
        return tempsRegard() >= 0;
    }

    /** Le temps dans l'animation de l'éveil (en secondes, comme yeux.mjs), ou -1 si rien n'est à l'écran. */
    private static double tempsRegard() {
        if (regardMode < 0) {
            return -1;
        }
        double s = secondes(regardDebut);
        if (regardMode == Sharingan.TSUKUYOMI) {
            return s > TSU_DUREE ? -1 : TSU_DEBUT + s;
        }
        return s > DUREES[regardEveil - 1] ? -1 : s;
    }

    /** Les sons de l'éveil, calés sur les flashs (comme dans les vidéos validées). */
    private static void sons(double t) {
        if (sonJoue || regardMode != Sharingan.EVEIL) {
            return;
        }
        double quand = regardEveil == 1 ? 2.8 : regardEveil == 2 ? 1.1 : 3.45;
        if (t >= quand) {
            jouer(regardEveil == 2 ? SON_MANGEKYO : SON_EVEIL);
            sonJoue = true;
        }
    }

    /** Ce que l'œil fait à ce moment (comme etat() dans yeux.mjs) : ouverture, lueur, éclair, fondu, secousse, larme, violet. */
    private record Moment(double ouvert, double lueur, double eclair, double fin, double secousse, double larme, double violet) {
    }

    private static Moment moment(int n, double t, boolean tsukuyomi) {
        if (n == 1) {
            double ouvert = sortie(entre(t, 0.45, 1.05)) * (1 - 0.08 * Math.sin(entre(t, 3.3, 3.6) * Math.PI));
            double eclair = Math.max(0, 1 - Math.abs(t - 3.42) / 0.12) * 0.75;
            return new Moment(ouvert, 0.25 + 0.75 * entre(t, 3.3, 3.6), eclair, entre(t, 4.7, 5.2), 0, 0, 0);
        }
        if (n == 2) {
            double ouvert = tsukuyomi ? 1 : sortie(entre(t, 0.2, 0.7));
            double eclair = Math.max(0, 1 - Math.abs(t - 3.12) / 0.13) * 0.8;
            double fin = tsukuyomi ? entre(t, TSU_DEBUT + TSU_DUREE - 0.3, TSU_DEBUT + TSU_DUREE) : entre(t, 5.6, 6.1);
            return new Moment(ouvert, 0.4 + 0.6 * entre(t, 3.0, 3.3), eclair, fin, Math.max(0, 1 - Math.abs(t - 3.12) / 0.3), entre(t, 3.3, 5.3), 0);
        }
        double eclair = Math.max(0, 1 - Math.abs(t - 4.05) / 0.14) * 0.8;
        return new Moment(sortie(entre(t, 0.2, 0.7)), 0.35 + 0.65 * entre(t, 3.9, 4.2), eclair, entre(t, 5.9, 6.5),
                Math.max(0, 1 - Math.abs(t - 4.05) / 0.3) * 0.7, 0, lisse(entre(t, 0.9, 2.5)));
    }

    private static final int ROUGE = 0xFF4A46, VIOLET = 0xCEB2FA;

    private static int melange(int a, int b, double k) {
        int r = (int) ((a >> 16 & 255) + ((b >> 16 & 255) - (a >> 16 & 255)) * k);
        int g = (int) ((a >> 8 & 255) + ((b >> 8 & 255) - (a >> 8 & 255)) * k);
        int bl = (int) ((a & 255) + ((b & 255) - (a & 255)) * k);
        return r << 16 | g << 8 | bl;
    }

    private static void ecran(GuiGraphics g) {
        double t = tempsRegard();
        if (t < 0) {
            regardMode = -1;
            return;
        }
        sons(t);
        boolean tsu = regardMode == Sharingan.TSUKUYOMI;
        int n = tsu ? 2 : regardEveil;
        Moment m = moment(n, t, tsu);
        int W = g.guiWidth(), H = g.guiHeight();
        double k = H / 540.0;
        // le noir qui tombe sur l'écran (et qui se retire à la fin, sauf pour le Tsukuyomi : on part dans le genjutsu)
        double entree = tsu ? 1 : Math.min(1, secondes(regardDebut) / 0.25);
        double fond = entree * (tsu ? 1 : 1 - m.fin());
        g.fill(0, 0, W, H, ((int) (255 * fond) << 24) | 0x030204);
        // le noir d'abord (les remplissages attendent sinon la fin de la couche, après les yeux)
        g.flush();
        int lueurDroite = n == 3 ? melange(ROUGE, VIOLET, m.violet()) : ROUGE;
        double sec = m.secousse() * 6 * k;
        double dx = sec * Math.sin(t * 97), dy = sec * Math.cos(t * 71);
        int[] im = images(n, t);
        double visible = tsu ? 1 - m.fin() : 1 - m.fin();
        for (int e = 0; e < 2; e++) {
            // e = 0 : l'œil à gauche de l'écran (son œil droit, le coin extérieur à gauche) ; e = 1 : à droite (son œil gauche)
            double cx = W / 2.0 + (e == 0 ? -180 : 180) * k + dx, cy = H / 2.0 - 8 * k + dy;
            int lueur = e == 0 ? ROUGE : lueurDroite;
            oeil(g, cx, cy, k, e == 0, m.ouvert(), im[e], lueur, m.lueur(), visible * entree);
            if (m.larme() > 0) {
                // la larme de sang du Mangekyo, sous l'iris
                double x = cx + (e == 0 ? 6 : -6) * k, y0 = cy + 37 * k, lg = 150 * k * sortie(m.larme());
                int a = (int) (235 * visible);
                g.fill((int) (x - 1.5 * k), (int) y0, (int) (x + 1.5 * k), (int) (y0 + lg), (a << 24) | 0x960006);
                g.fill((int) (x - 2.6 * k), (int) (y0 + lg - 4 * k), (int) (x + 2.6 * k), (int) (y0 + lg + 2 * k), (a << 24) | 0x960006);
                g.flush();
            }
        }
        braises(g, t, k, W, H, m, lueurDroite, visible * entree);
        // l'éclair de l'éveil
        if (m.eclair() > 0) {
            int c = melange(lueurDroite, 0xFFFFFF, 0.4);
            g.fill(0, 0, W, H, ((int) (255 * m.eclair() * visible) << 24) | c);
        }
        // le Tsukuyomi : tout vire au rouge juste avant le saut
        if (tsu) {
            double rouge = entre(t, TSU_DEBUT + TSU_DUREE - 0.35, TSU_DEBUT + TSU_DUREE);
            if (rouge > 0) {
                g.fill(0, 0, W, H, ((int) (255 * rouge) << 24) | 0xFF3232);
            }
        }
    }

    /** Un grand œil : le blanc, l'iris (une image de l'atlas), le masque (on coupe ce qui dépasse), le halo teinté, les traits. */
    private static void oeil(GuiGraphics g, double cx, double cy, double k, boolean miroir, double ouvert, int image, int lueur, double force, double alpha) {
        if (ouvert <= 0.01 || alpha <= 0.01) {
            return;
        }
        PoseStack ps = g.pose();
        ps.pushPose();
        ps.translate(cx, cy, 0);
        ps.scale((float) k, (float) (k * ouvert), 1);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShaderColor(1, 1, 1, (float) alpha);
        g.blit(miroir ? BLANC_G : BLANC, -180, -110, 0, 0, 360, 220, 360, 220);
        g.flush();
        // l'iris : 108 de large, un peu vers le bas, coupé par les paupières (le masque)
        int iu = (image % COLONNES) * 48, iv = (image / COLONNES) * 48;
        float teinte = (float) (0.08 * force);
        RenderSystem.setShaderColor(1, 1, 1, (float) alpha);
        g.blit(IRIS, -54, -51, 108, 108, iu, iv, 48, 48, ATLAS_IRIS_W, ATLAS_IRIS_H);
        g.flush();
        if (teinte > 0) {
            // l'iris brille un peu de sa couleur
            RenderSystem.setShaderColor((lueur >> 16 & 255) / 255F, (lueur >> 8 & 255) / 255F, (lueur & 255) / 255F, teinte * (float) alpha);
            g.blit(IRIS, -54, -51, 108, 108, iu, iv, 48, 48, ATLAS_IRIS_W, ATLAS_IRIS_H);
            g.flush();
        }
        RenderSystem.setShaderColor(1, 1, 1, (float) alpha);
        g.blit(miroir ? MASQUE_G : MASQUE, -180, -110, 0, 0, 360, 220, 360, 220);
        g.flush();
        float r = (lueur >> 16 & 255) / 255F, v = (lueur >> 8 & 255) / 255F, b = (lueur & 255) / 255F;
        float melange = (float) (0.35 + 0.4 * force);
        RenderSystem.setShaderColor(1 + (r - 1) * melange, 1 + (v - 1) * melange, 1 + (b - 1) * melange, (float) alpha);
        g.blit(miroir ? HALO_G : HALO, -180, -110, 0, 0, 360, 220, 360, 220);
        g.flush();
        RenderSystem.setShaderColor(1, 1, 1, (float) alpha);
        g.blit(miroir ? TRAITS_G : TRAITS, -180, -110, 0, 0, 360, 220, 360, 220);
        g.flush();
        RenderSystem.setShaderColor(1, 1, 1, 1);
        RenderSystem.disableBlend();
        ps.popPose();
    }

    private static double hache(int i, int j, int kk) {
        int h = i * 374761393 + j * 668265263 + kk * 2147483647;
        h = (h ^ (h >>> 13)) * 1274126177;
        return ((h ^ (h >>> 16)) >>> 0 & 0xFFFFFFFFL) / 4294967296.0;
    }

    /** Des braises carrées de la couleur de l'œil, qui montent doucement autour des yeux. */
    private static void braises(GuiGraphics g, double t, double k, int W, int H, Moment m, int couleur, double alpha) {
        int nb = (int) Math.round(70 * m.lueur());
        for (int q = 0; q < nb; q++) {
            double vie = 2.2 + 1.5 * Math.abs(hache(q, 1, 900)), age = (t + vie * Math.abs(hache(q, 2, 900))) % vie;
            double x = W / 2.0 + (-300 + 600 * Math.abs(hache(q, 3, 900))) * k + 18 * k * Math.sin(age * 2 + q);
            double y = H / 2.0 + (138 - age * 70 - 60 * Math.abs(hache(q, 4, 900))) * k;
            double a = Math.sin(Math.PI * age / vie) * 0.8 * m.ouvert() * alpha;
            int s = (int) Math.max(1, (2 + Math.floor(3 * Math.abs(hache(q, 5, 900)))) * k * 0.6);
            g.fill((int) x, (int) y, (int) x + s, (int) y + s, ((int) (255 * a) << 24) | couleur);
        }
    }

    // ================================================================ la touche J, le panneau

    /** Les touches du Sharingan (J, la touche d'ulti, est celle des autres pouvoirs : elle fait ici le Tsukuyomi). */
    /** ² (la touche sous Échap) : la technique du pouvoir (le Susano ; pour Minato, son kunai : y aller, ou en planter un). */
    public static final KeyMapping SUSANO = new KeyMapping("key.cabane.technique", GLFW.GLFW_KEY_GRAVE_ACCENT, "key.categories.cabane");
    public static final KeyMapping KAMUI = new KeyMapping("key.cabane.kamui", GLFW.GLFW_KEY_L, "key.categories.cabane");
    public static final KeyMapping METEORITE = new KeyMapping("key.cabane.meteorite", GLFW.GLFW_KEY_I, "key.categories.cabane");

    /** La touche d'ulti (J, lue avec les autres pouvoirs dans ClientHachibi4) : le Tsukuyomi, dès que ses yeux sont éveillés. */
    public static void toucheUlti() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && mc.screen == null && niveau(mc.player.getUUID()) >= 1) {
            PacketDistributor.sendToServer(new Sharingan.Touche(Sharingan.J_TSUKUYOMI));
        }
    }

    /** ², L, I : le Susano, le Kamui, la météorite (le serveur dit si c'est possible). */
    private static void touches(Minecraft mc) {
        while (SUSANO.consumeClick()) {
            if (mc.screen == null && !ClientMinato.technique()) {
                PacketDistributor.sendToServer(new Sharingan.Touche(Sharingan.J_SUSANO));
            }
        }
        while (KAMUI.consumeClick()) {
            if (mc.screen == null && mc.player != null && niveau(mc.player.getUUID()) >= 1) {
                PacketDistributor.sendToServer(new Sharingan.Touche(Sharingan.J_KAMUI));
            }
        }
        while (METEORITE.consumeClick()) {
            if (mc.screen == null && mc.player != null && niveau(mc.player.getUUID()) >= 1) {
                PacketDistributor.sendToServer(new Sharingan.Touche(Sharingan.J_METEORITE));
            }
        }
    }

    private static void ancienneToucheJ(Minecraft mc) {
        boolean jBas = false, jMaintenueEnvoyee = false, jDouble = false, jEnAttente = false;
        long jAppui = 0, jRelache = 0;
        int n = mc.player == null ? 0 : niveau(mc.player.getUUID());
        boolean bas = n >= 1 && mc.screen == null && ClientSusano.niveauLocal() <= 3 && fr.cabane.client.hachibi4.ClientHachibi4.ULTI.isDown();
        long maintenant = System.nanoTime();
        boolean meteorite = n >= 3 && !meteoriteFaite;
        if (bas && !jBas) {
            jAppui = maintenant;
            if (jEnAttente && meteorite && maintenant - jRelache < 350_000_000L) {
                jEnAttente = false;
                jDouble = true;
                PacketDistributor.sendToServer(new Sharingan.Touche(Sharingan.J_METEORITE));
            }
        }
        if (bas && !jDouble && !jMaintenueEnvoyee && n >= 2 && maintenant - jAppui > 500_000_000L) {
            jMaintenueEnvoyee = true;
            jEnAttente = false;
            PacketDistributor.sendToServer(new Sharingan.Touche(Sharingan.J_KAMUI));
        }
        if (!bas && jBas) {
            if (!jMaintenueEnvoyee && !jDouble) {
                if (meteorite) {
                    jEnAttente = true;
                    jRelache = maintenant;
                } else {
                    PacketDistributor.sendToServer(new Sharingan.Touche(Sharingan.J_TSUKUYOMI));
                }
            }
            jMaintenueEnvoyee = false;
            jDouble = false;
        }
        if (jEnAttente && !bas && maintenant - jRelache > 350_000_000L) {
            jEnAttente = false;
            PacketDistributor.sendToServer(new Sharingan.Touche(Sharingan.J_TSUKUYOMI));
        }
        jBas = bas;
    }

    private static final String[] NOMS_YEUX = {"", "SHARINGAN", "MANGEKYO", "RINNEGAN"};

    /** Les lignes des techniques des yeux (Tsukuyomi, Kamui, météorite) pour le joueur local (vide : yeux endormis). */
    public static String[][] lignes(int niveauSusano) {
        Minecraft mc = Minecraft.getInstance();
        int y = mc.player == null ? 0 : niveau(mc.player.getUUID());
        if (y < 1 || niveauSusano > 3) {
            return new String[0][];
        }
        List<String[]> l = new java.util.ArrayList<>();
        l.add(new String[]{Clavier.nom(fr.cabane.client.hachibi4.ClientHachibi4.ULTI), "Tsukuyomi (regarde-le dans les yeux)"});
        if (y >= 2) {
            l.add(new String[]{Clavier.nom(KAMUI), ClientKamui.cache(mc.player.getUUID()) ? "Ressortir du Kamui" : "Kamui (sur lui ; sur toi : te cacher)"});
        }
        if (y >= 3) {
            l.add(new String[]{Clavier.nom(METEORITE), meteoriteFaite ? "Météorite : déjà faite pendant cet éveil" : "Météorite (une fois)"});
        }
        return l.toArray(new String[0][]);
    }

    /** La ligne de la touche du Susano (²) : le niveau suivant, ou le ranger. */
    public static String[] ligneSusano(int niveauSusano, boolean parfaitPossible) {
        Minecraft mc = Minecraft.getInstance();
        int y = mc.player == null ? 0 : niveau(mc.player.getUUID());
        int max = Math.min(y, maxLocal >= 3 && parfaitPossible ? 4 : Math.max(y, 1));
        String texte = niveauSusano >= 4 ? "Redevenir le chevalier" : niveauSusano >= max ? (niveauSusano == 0 ? "Susano" : "Ranger le Susano")
                : niveauSusano == 0 ? "Susano niveau 1" : niveauSusano == 3 ? "Susano parfait (une fois)" : "Susano niveau " + (niveauSusano + 1);
        return new String[]{Clavier.nom(SUSANO), texte};
    }

    /** La ligne de la touche Éveil : les yeux suivants, ou rendormir. */
    public static String[] ligneEveil() {
        Minecraft mc = Minecraft.getInstance();
        int y = mc.player == null ? 0 : niveau(mc.player.getUUID());
        String texte = y >= maxLocal ? "Rendormir" : y + 1 <= 3 ? NOMS_YEUX[y + 1].charAt(0) + NOMS_YEUX[y + 1].substring(1).toLowerCase() : "Rendormir";
        return new String[]{ClientSusano.EVEIL.getTranslatedKeyMessage().getString(), texte};
    }

    /** Le panneau quand il n'a que ses yeux (pas de Susano) : ses techniques, le Susano, l'éveil. */
    private static void panneau(GuiGraphics g) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.options.hideGui || ecranActif() || ClientSusano.niveauLocal() > 0) {
            return;
        }
        int y = niveau(mc.player.getUUID());
        if (y < 1) {
            return;
        }
        List<String[]> l = new java.util.ArrayList<>(java.util.Arrays.asList(lignes(0)));
        l.add(ligneSusano(0, false));
        l.add(ligneEveil());
        String titre = NOMS_YEUX[Math.min(3, y)] + "  ·  NIVEAU " + y;
        net.minecraft.client.gui.Font font = mc.font;
        int largeurTouche = 0, largeurAction = font.width(titre) - 10;
        for (String[] ligne : l) {
            largeurTouche = Math.max(largeurTouche, font.width(ligne[0]));
            largeurAction = Math.max(largeurAction, font.width(ligne[1]));
        }
        int w = largeurTouche + largeurAction + 26, h = 22 + l.size() * 13, x = 6, y0 = g.guiHeight() / 2 - h / 2;
        int teinte = y >= 3 ? 0xFFC8B0FF : 0xFFFF5A50;
        g.fill(x, y0, x + w, y0 + h, 0xA0000000);
        g.fill(x, y0, x + 2, y0 + h, teinte);
        g.drawString(font, titre, x + 8, y0 + 6, teinte, true);
        for (int i = 0; i < l.size(); i++) {
            int ly = y0 + 21 + i * 13;
            g.fill(x + 7, ly - 2, x + 11 + font.width(l.get(i)[0]), ly + 9, 0x40FFFFFF);
            g.drawString(font, l.get(i)[0], x + 9, ly, 0xFFFFFFFF, false);
            g.drawString(font, l.get(i)[1], x + 18 + largeurTouche, ly, 0xFFEDEDED, true);
        }
    }

    // ================================================================ branchements

    @EventBusSubscriber(modid = Cabane.MODID, value = Dist.CLIENT)
    public static final class Couches {
        @SubscribeEvent
        public static void ecrans(RegisterGuiLayersEvent event) {
            event.registerAboveAll(Cabane.id("sharingan"), (g, delta) -> {
                panneau(g);
                ecran(g);
            });
        }

        @SubscribeEvent
        public static void touchesClavier(net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent event) {
            event.register(SUSANO);
            event.register(KAMUI);
            event.register(METEORITE);
        }

        @SubscribeEvent
        public static void couches(EntityRenderersEvent.AddLayers event) {
            for (PlayerSkin.Model modele : event.getSkins()) {
                if (event.getSkin(modele) instanceof PlayerRenderer rendu) {
                    rendu.addLayer(new Couche(rendu));
                }
            }
        }

        private Couches() {
        }
    }

    @EventBusSubscriber(modid = Cabane.MODID, value = Dist.CLIENT)
    public static final class Evenements {
        @SubscribeEvent
        public static void tick(ClientTickEvent.Post event) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.level == null) {
                YEUX.clear();
                regardMode = -1;
                meteoriteFaite = false;
                return;
            }
            touches(mc);
        }

        private Evenements() {
        }
    }

    private ClientSharingan() {
    }
}
