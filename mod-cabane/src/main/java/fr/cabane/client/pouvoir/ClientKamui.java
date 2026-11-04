package fr.cabane.client.pouvoir;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.math.Axis;
import fr.cabane.Cabane;
import fr.cabane.pouvoir.Kamui;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.DimensionSpecialEffects;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterDimensionSpecialEffectsEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.event.RenderLivingEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Le Kamui chez chaque joueur (voir {@link Kamui}, et les vidéos de l'hôte) : une spirale de traits noirs, tournée vers la
 * caméra, s'enroule autour de celui qui est aspiré ; son corps tourne sur lui-même et rapetisse jusqu'à un point noir,
 * puis disparaît. Pour lui-même : il s'aspire, puis la spirale se déroule là où il réapparaît. Dans la dimension du
 * Kamui : un ciel gris, et combien de temps il y reste.
 */
public final class ClientKamui {
    private record Spirale(int entite, Vec3 centre, int mode, long debut, int duree) {
        double avance(float partiel) {
            Minecraft mc = Minecraft.getInstance();
            return (mc.level.getGameTime() - debut + partiel) / duree;
        }
    }

    private static final List<Spirale> SPIRALES = new ArrayList<>();
    /** Ceux qui sont cachés dans le Kamui (on ne les dessine pas), et la fin de leur cachette (en ticks du jeu). */
    private static final java.util.Map<java.util.UUID, Long> CACHES = new java.util.HashMap<>();

    public static void cache(Kamui.Cache p) {
        Minecraft mc = Minecraft.getInstance();
        if (p.actif() && mc.level != null) {
            CACHES.put(p.joueur(), mc.level.getGameTime() + p.reste());
        } else {
            CACHES.remove(p.joueur());
        }
    }

    /** Ce joueur est caché dans le Kamui. */
    public static boolean cache(java.util.UUID joueur) {
        return CACHES.containsKey(joueur);
    }
    /** Le séjour du joueur local dans le Kamui : sa fin (en ticks du jeu), ou -1. */
    private static long finSejour = -1;

    public static void recevoir(Kamui.Spirale p) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return;
        }
        // il réapparaît : il ne reste plus caché à l'endroit d'où il est parti
        if (p.mode() == Kamui.ARRIVE) {
            SPIRALES.removeIf(s -> s.entite() == p.entite() && s.mode() == Kamui.PART);
        }
        SPIRALES.add(new Spirale(p.entite(), new Vec3(p.x(), p.y(), p.z()), p.mode(), mc.level.getGameTime(), Math.max(1, p.duree())));
    }

    public static void sejour(Kamui.Sejour p) {
        Minecraft mc = Minecraft.getInstance();
        finSejour = p.reste() <= 0 || mc.level == null ? -1 : mc.level.getGameTime() + p.reste();
    }

    private static double borne(double u) {
        return Math.max(0, Math.min(1, u));
    }

    private static double lisse(double u) {
        u = borne(u);
        return u * u * (3 - 2 * u);
    }

    /** La taille de l'aspiré à ce moment (1 : normal, 0 : un point), et son tour sur lui-même (en degrés). */
    private static double taille(Spirale s, double u) {
        return s.mode() == Kamui.ARRIVE ? lisse(u) : 1 - lisse(Math.pow(borne(u), 1.4));
    }

    // ================================================================ le corps qui tourne et rapetisse

    private static Spirale sur(int entite, float partiel) {
        for (Spirale s : SPIRALES) {
            double u = s.avance(partiel);
            // l'aspiré reste caché après la spirale, le temps que le serveur le fasse vraiment disparaître
            if (s.entite() == entite && u >= 0 && (u <= 1.05 || s.mode() != Kamui.ARRIVE && u < 5)) {
                return s;
            }
        }
        return null;
    }

    private static void avant(RenderLivingEvent.Pre<?, ?> event) {
        LivingEntity e = event.getEntity();
        Spirale s = sur(e.getId(), event.getPartialTick());
        if (s == null) {
            return;
        }
        double u = borne(s.avance(event.getPartialTick()));
        double k = taille(s, u);
        if (s.mode() != Kamui.ARRIVE && u >= 1) {
            event.setCanceled(true);
            return;
        }
        PoseStack ps = event.getPoseStack();
        ps.pushPose();
        float h = e.getBbHeight() / 2;
        ps.translate(0, h, 0);
        // il tourne de plus en plus vite et s'étire en tournant (comme aspiré par le trou)
        double tour = s.mode() == Kamui.ARRIVE ? (1 - u) * (1 - u) * 540 : u * u * 900;
        ps.mulPose(Axis.YP.rotationDegrees((float) tour));
        float largeur = (float) (k * (1 - 0.35 * Math.sin(Math.PI * u))), hauteur = (float) k;
        ps.scale(Math.max(0.001F, largeur), Math.max(0.001F, hauteur), Math.max(0.001F, largeur));
        ps.translate(0, -h, 0);
    }

    private static void apres(RenderLivingEvent.Post<?, ?> event) {
        Spirale s = sur(event.getEntity().getId(), event.getPartialTick());
        if (s == null) {
            return;
        }
        double u = borne(s.avance(event.getPartialTick()));
        if (s.mode() != Kamui.ARRIVE && u >= 1) {
            return;
        }
        event.getPoseStack().popPose();
    }

    // ================================================================ la spirale

    private static void dessiner(PoseStack ps, Camera camera, float partiel) {
        Minecraft mc = Minecraft.getInstance();
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        VertexConsumer c = buffers.getBuffer(ClientSharingan.Types.VOILE);
        Matrix4f m = ps.last().pose();
        Vec3 cam = camera.getPosition();
        Quaternionf q = camera.rotation();
        Vector3f droite = new Vector3f(1, 0, 0).rotate(q), haut = new Vector3f(0, 1, 0).rotate(q);
        double temps = (mc.level.getGameTime() + partiel) / 20.0;
        for (Spirale s : SPIRALES) {
            double u = s.avance(partiel);
            if (u < 0 || u > 1.3) {
                continue;
            }
            // la spirale grandit d'un coup, s'enroule en se resserrant ; à l'arrivée, elle se déroule et s'efface
            double r = s.mode() == Kamui.ARRIVE ? 2.6 * (1 - lisse(u)) + 0.3 : 2.6 * Math.min(1, u * 4) * (1 - 0.85 * lisse((u - 0.3) / 0.8));
            float a = (float) (0.85 * Math.min(1, u * 5) * (1 - borne((u - 1) / 0.3)));
            Vec3 o = s.centre().subtract(cam);
            int bras = 7, pas = 26;
            double sens = s.mode() == Kamui.ARRIVE ? -1 : 1;
            for (int b = 0; b < bras; b++) {
                double base = b * Math.PI * 2 / bras + sens * temps * 7;
                for (int i = 0; i < pas; i++) {
                    double s0 = i / (double) pas, s1 = (i + 1) / (double) pas;
                    Vec3 p0 = point(o, droite, haut, base + s0 * 3.6 * sens, r * (1 - s0)), p1 = point(o, droite, haut, base + s1 * 3.6 * sens, r * (1 - s1));
                    double ep = 0.07 * (1 - s0) + 0.015;
                    Vec3 d = p1.subtract(p0), n = new Vec3(haut.x(), haut.y(), haut.z()).cross(d).normalize();
                    // la normale dans le plan de la caméra (perpendiculaire au trait)
                    Vec3 plan = new Vec3(droite.x(), droite.y(), droite.z()).cross(new Vec3(haut.x(), haut.y(), haut.z()));
                    n = plan.cross(d).normalize().scale(ep);
                    float gris = (float) (0.05 + 0.25 * s0);
                    sommet(c, m, p0.add(n), gris, a);
                    sommet(c, m, p1.add(n), gris, a);
                    sommet(c, m, p1.subtract(n), gris, a);
                    sommet(c, m, p0.subtract(n), gris, a);
                }
            }
            // le trou noir au milieu, qui se referme
            double trou = s.mode() == Kamui.ARRIVE ? 0.35 * (1 - u) : 0.25 + 0.2 * Math.sin(Math.PI * borne(u));
            if (trou > 0.01) {
                for (int i = 0; i < 16; i++) {
                    double a0 = i * Math.PI * 2 / 16, a1 = (i + 1) * Math.PI * 2 / 16;
                    sommet(c, m, o, 0.01F, a);
                    sommet(c, m, point(o, droite, haut, a0, trou), 0.01F, a);
                    sommet(c, m, point(o, droite, haut, a1, trou), 0.01F, a);
                    sommet(c, m, o, 0.01F, a);
                }
            }
        }
        buffers.endBatch(ClientSharingan.Types.VOILE);
    }

    private static Vec3 point(Vec3 o, Vector3f droite, Vector3f haut, double angle, double r) {
        return o.add(droite.x() * Math.cos(angle) * r + haut.x() * Math.sin(angle) * r, droite.y() * Math.cos(angle) * r + haut.y() * Math.sin(angle) * r,
                droite.z() * Math.cos(angle) * r + haut.z() * Math.sin(angle) * r);
    }

    private static void sommet(VertexConsumer c, Matrix4f m, Vec3 p, float gris, float a) {
        c.addVertex(m, (float) p.x, (float) p.y, (float) p.z).setColor(gris, gris, gris * 1.1F, a);
    }

    // ================================================================ dans le Kamui : le ciel gris, le temps qui reste

    /**
     * Sa vue à lui, caché : le monde voilé de gris, les bords sombres, un tourbillon qui tourne doucement dans chaque coin,
     * et ce qui lui reste.
     */
    private static void vueCachee(GuiGraphics g) {
        Minecraft mc = Minecraft.getInstance();
        Long fin = mc.player == null ? null : CACHES.get(mc.player.getUUID());
        if (fin == null || mc.level == null) {
            return;
        }
        int W = g.guiWidth(), H = g.guiHeight();
        double temps = (mc.level.getGameTime() + mc.getTimer().getGameTimeDeltaPartialTick(false)) / 20.0;
        // le voile gris
        g.fill(0, 0, W, H, 0x58808A94);
        // les bords sombres
        int bord = Math.max(20, H / 5);
        for (int i = 0; i < bord; i++) {
            int a = (int) (150 * Math.pow(1 - i / (double) bord, 2));
            int c = (a << 24) | 0x101418;
            g.fill(0, i, W, i + 1, c);
            g.fill(0, H - 1 - i, W, H - i, c);
            g.fill(i, 0, i + 1, H, c);
            g.fill(W - 1 - i, 0, W - i, H, c);
        }
        // un tourbillon dans chaque coin
        int[][] coins = {{0, 0}, {W, 0}, {0, H}, {W, H}};
        double rmax = H * 0.42;
        for (int[] coin : coins) {
            for (int b = 0; b < 5; b++) {
                double base = b * Math.PI * 2 / 5 + temps * 1.5;
                for (int i = 0; i < 70; i++) {
                    double s = i / 70.0, ang = base + s * 3.2, r = rmax * (1 - s);
                    int x = (int) (coin[0] + Math.cos(ang) * r), y = (int) (coin[1] + Math.sin(ang) * r);
                    int t = Math.max(2, (int) (6 * (1 - s)));
                    g.fill(x, y, x + t, y + t, ((int) (200 * (1 - s)) << 24) | 0x0A0C10);
                }
            }
        }
        double reste = Math.max(0, (fin - mc.level.getGameTime()) / 20.0);
        g.drawCenteredString(mc.font, Component.literal("Kamui : " + (int) Math.ceil(reste) + " s"), W / 2, 12, 0xFFD0D8E0);
        g.drawCenteredString(mc.font, Component.literal("Personne ne te voit ni ne t'entend. Toi, tu entends tout."), W / 2, 24, 0xFFA8B0B8);
    }

    private static void ecran(GuiGraphics g) {
        vueCachee(g);
        Minecraft mc = Minecraft.getInstance();
        if (finSejour < 0 || mc.level == null || mc.level.dimension() != Kamui.DIMENSION) {
            return;
        }
        double reste = (finSejour - mc.level.getGameTime()) / 20.0;
        g.drawCenteredString(mc.font, Component.literal("Kamui : " + (int) Math.ceil(Math.max(0, reste)) + " s"), g.guiWidth() / 2, 12, 0xFFD0D8E0);
    }

    /** Un ciel gris, plus sombre en haut. */
    private static final class Ciel extends DimensionSpecialEffects {
        Ciel() {
            super(Float.NaN, false, SkyType.NONE, false, false);
        }

        @Override
        public Vec3 getBrightnessDependentFogColor(Vec3 couleur, float lumiere) {
            return couleur;
        }

        @Override
        public boolean isFoggyAt(int x, int y) {
            return false;
        }

        @Override
        public boolean renderClouds(ClientLevel level, int ticks, float partiel, PoseStack ps, double x, double y, double z, Matrix4f vue, Matrix4f projection) {
            return true;
        }

        @Override
        public boolean renderSnowAndRain(ClientLevel level, int ticks, float partiel, LightTexture lumieres, double x, double y, double z) {
            return true;
        }

        @Override
        public boolean renderSky(ClientLevel level, int ticks, float partiel, Matrix4f vue, Camera camera, Matrix4f projection, boolean brumeux, Runnable brume) {
            RenderSystem.depthMask(false);
            RenderSystem.disableCull();
            RenderSystem.setShader(GameRenderer::getPositionColorShader);
            BufferBuilder b = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
            int bandes = 12, tranches = 32;
            for (int i = -2; i < bandes; i++) {
                double e0 = i * (Math.PI / 2) / bandes, e1 = (i + 1) * (Math.PI / 2) / bandes;
                for (int j = 0; j < tranches; j++) {
                    double a0 = j * Math.PI * 2 / tranches, a1 = (j + 1) * Math.PI * 2 / tranches;
                    point(b, vue, e0, a0);
                    point(b, vue, e0, a1);
                    point(b, vue, e1, a1);
                    point(b, vue, e1, a0);
                }
            }
            BufferUploader.drawWithShader(b.buildOrThrow());
            RenderSystem.enableCull();
            RenderSystem.depthMask(true);
            return true;
        }

        private static void point(BufferBuilder b, Matrix4f m, double elevation, double azimut) {
            double k = Math.max(0, elevation) / (Math.PI / 2);
            float c = (float) (0.55 - 0.32 * Math.pow(k, 0.8));
            b.addVertex(m, (float) (Math.cos(elevation) * Math.sin(azimut) * 100), (float) (Math.sin(elevation) * 100), (float) (Math.cos(elevation) * Math.cos(azimut) * 100))
                    .setColor(c, c * 1.06F, c * 1.12F, 1F);
        }
    }

    // ================================================================ branchements

    @EventBusSubscriber(modid = Cabane.MODID, value = Dist.CLIENT)
    public static final class Enregistrement {
        @SubscribeEvent
        public static void ciel(RegisterDimensionSpecialEffectsEvent event) {
            event.register(Cabane.id("kamui"), new Ciel());
        }

        @SubscribeEvent
        public static void ecrans(RegisterGuiLayersEvent event) {
            event.registerAboveAll(Cabane.id("kamui"), (g, delta) -> ecran(g));
        }

        private Enregistrement() {
        }
    }

    @EventBusSubscriber(modid = Cabane.MODID, value = Dist.CLIENT)
    public static final class Evenements {
        @SubscribeEvent
        public static void tick(ClientTickEvent.Post event) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.level == null) {
                SPIRALES.clear();
                CACHES.clear();
                finSejour = -1;
                return;
            }
            SPIRALES.removeIf(s -> s.avance(0) > (s.mode() != Kamui.ARRIVE ? 5 : 1.4) || mc.level.getGameTime() < s.debut());
            CACHES.values().removeIf(fin -> mc.level.getGameTime() > fin + 100);
            if (finSejour >= 0 && mc.level.getGameTime() > finSejour + 40) {
                finSejour = -1;
            }
        }

        @SubscribeEvent
        public static void corpsAvant(RenderLivingEvent.Pre<?, ?> event) {
            if (!SPIRALES.isEmpty()) {
                avant(event);
            }
        }

        /** Caché : les autres ne le voient pas du tout (ni corps, ni nom, ni objet, ni armure). */
        @SubscribeEvent
        public static void cacher(net.neoforged.neoforge.client.event.RenderPlayerEvent.Pre event) {
            Minecraft mc = Minecraft.getInstance();
            if (!CACHES.isEmpty() && CACHES.containsKey(event.getEntity().getUUID()) && event.getEntity() != mc.player) {
                event.setCanceled(true);
            }
        }

        @SubscribeEvent
        public static void corpsApres(RenderLivingEvent.Post<?, ?> event) {
            if (!SPIRALES.isEmpty()) {
                apres(event);
            }
        }

        @SubscribeEvent
        public static void dessiner(RenderLevelStageEvent event) {
            if (SPIRALES.isEmpty() || event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES || Minecraft.getInstance().level == null) {
                return;
            }
            ClientKamui.dessiner(event.getPoseStack(), event.getCamera(), event.getPartialTick().getGameTimeDeltaPartialTick(false));
        }

        @SubscribeEvent
        public static void brume(ViewportEvent.ComputeFogColor event) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.level != null && mc.level.dimension() == Kamui.DIMENSION) {
                event.setRed(0.5F);
                event.setGreen(0.54F);
                event.setBlue(0.58F);
            }
        }

        private Evenements() {
        }
    }

    private ClientKamui() {
    }
}
