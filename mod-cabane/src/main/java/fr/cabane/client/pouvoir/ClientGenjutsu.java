package fr.cabane.client.pouvoir;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import fr.cabane.Cabane;
import fr.cabane.pouvoir.Genjutsu;
import java.util.UUID;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.DimensionSpecialEffects;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterDimensionSpecialEffectsEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.event.RenderNameTagEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;
import net.neoforged.neoforge.common.util.TriState;
import org.joml.Matrix4f;

/**
 * Le Tsukuyomi chez les deux joueurs (voir {@link Genjutsu} et docs/sharingan/tsukuyomi.mp4) : le ciel rouge sang et la
 * lune noire cerclée de rouge (le ciel de la dimension), ses douze clones qui sortent du sol tout autour de la croix et
 * pointent leur arme sur elle, la cible les bras en croix, le compte à rebours, et à la fin l'illusion qui se fissure
 * comme du verre avant l'éclair blanc.
 */
public final class ClientGenjutsu {
    private static UUID lanceur;
    private static UUID victime;
    private static int place;
    /** Le temps du genjutsu (en ticks du jeu, côté client) : son début et sa fin. */
    private static long debut = -1;
    private static long fin = -1;
    private static final int CLONES = 12;
    private static final double RAYON_CLONES = 7.5;
    /** Le dessin d'un clone est en cours (pas d'étiquette de nom, sa pose à lui). */
    private static int cloneEnCours = -1;

    public static void recevoir(Genjutsu.Etat p) {
        Minecraft mc = Minecraft.getInstance();
        if (!p.actif() || mc.level == null) {
            lanceur = null;
            victime = null;
            debut = -1;
            return;
        }
        lanceur = p.lanceur();
        victime = p.victime();
        place = p.place();
        debut = mc.level.getGameTime();
        fin = debut + p.reste();
    }

    private static boolean actif() {
        Minecraft mc = Minecraft.getInstance();
        return debut >= 0 && mc.level != null && mc.level.dimension() == Genjutsu.DIMENSION;
    }

    private static double secondes(float partiel) {
        Minecraft mc = Minecraft.getInstance();
        return (mc.level.getGameTime() - debut + partiel) / 20.0;
    }

    private static double reste(float partiel) {
        Minecraft mc = Minecraft.getInstance();
        return (fin - mc.level.getGameTime() - partiel) / 20.0;
    }

    private static double borne(double u) {
        return Math.max(0, Math.min(1, u));
    }

    private static double sortie(double u) {
        return 1 - Math.pow(1 - borne(u), 3);
    }

    // ================================================================ les poses : la cible en croix, les clones l'arme pointée

    public static void poser(PlayerModel<?> modele, Player joueur) {
        if (!actif()) {
            return;
        }
        if (cloneEnCours >= 0) {
            double t = secondes(Minecraft.getInstance().getTimer().getGameTimeDeltaPartialTick(false));
            double leve = sortie((t - 7.0 - cloneEnCours * 0.1) / 0.6);
            double frappe = Math.max(0, Math.sin(Math.PI * borne((t - 22.0) / 0.6)));
            modele.rightArm.xRot = (float) (-1.5 * leve - 0.6 * frappe);
            modele.rightArm.yRot = 0;
            modele.rightArm.zRot = 0;
            modele.rightSleeve.copyFrom(modele.rightArm);
            return;
        }
        if (joueur.getUUID().equals(victime)) {
            float debat = 0.06F * Mth.sin(joueur.tickCount * 0.45F);
            modele.rightArm.xRot = 0;
            modele.rightArm.yRot = 0;
            modele.rightArm.zRot = Mth.HALF_PI - debat;
            modele.leftArm.xRot = 0;
            modele.leftArm.yRot = 0;
            modele.leftArm.zRot = -Mth.HALF_PI + debat;
            modele.rightLeg.xRot = 0;
            modele.leftLeg.xRot = 0;
            modele.rightSleeve.copyFrom(modele.rightArm);
            modele.leftSleeve.copyFrom(modele.leftArm);
            modele.rightPants.copyFrom(modele.rightLeg);
            modele.leftPants.copyFrom(modele.leftLeg);
        }
    }

    // ================================================================ les clones

    private static void clones(PoseStack ps, Camera camera, float partiel) {
        Minecraft mc = Minecraft.getInstance();
        Player l = lanceur == null ? null : mc.level.getPlayerByUUID(lanceur);
        if (!(l instanceof AbstractClientPlayer original)) {
            return;
        }
        double t = secondes(partiel);
        BlockPos c = Genjutsu.centre(place);
        EntityRenderDispatcher d = mc.getEntityRenderDispatcher();
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        Vec3 cam = camera.getPosition();
        float yb = original.yBodyRot, ybo = original.yBodyRotO, yh = original.yHeadRot, yho = original.yHeadRotO, xr = original.getXRot(), xro = original.xRotO;
        boolean ombre = mc.options.entityShadows().get();
        for (int k = 0; k < CLONES; k++) {
            double t0 = 3.0 + k * 0.3;
            if (t < t0) {
                continue;
            }
            double a = (k / (double) CLONES) * Math.PI * 2 + 0.26;
            double x = c.getX() + 0.5 + Math.sin(a) * RAYON_CLONES, z = c.getZ() + 1.35 + Math.cos(a) * RAYON_CLONES;
            double monte = sortie((t - t0) / 0.5);
            double y = Genjutsu.SOL + 1 - 2.0 * (1 - monte);
            // il regarde la croix
            float lacet = (float) Math.toDegrees(Math.atan2(-(c.getX() + 0.5 - x), c.getZ() + 1.35 - z));
            original.yBodyRot = original.yBodyRotO = original.yHeadRot = original.yHeadRotO = lacet;
            original.setXRot(10);
            original.xRotO = 10;
            cloneEnCours = k;
            d.setRenderShadow(false);
            try {
                d.render(original, x - cam.x, y - cam.y, z - cam.z, lacet, partiel, ps, buffers, LightTexture.FULL_BRIGHT);
            } finally {
                cloneEnCours = -1;
                d.setRenderShadow(ombre);
            }
            // la fumée noire d'où il sort
            if (monte < 1 && mc.level.random.nextFloat() < 0.5F) {
                mc.level.addParticle(net.minecraft.core.particles.ParticleTypes.LARGE_SMOKE, x, Genjutsu.SOL + 1.1, z, 0, 0.06, 0);
            }
        }
        original.yBodyRot = yb;
        original.yBodyRotO = ybo;
        original.yHeadRot = yh;
        original.yHeadRotO = yho;
        original.setXRot(xr);
        original.xRotO = xro;
        buffers.endBatch();
    }

    // ================================================================ l'écran : le compte à rebours, l'arrivée, les fissures, l'éclair

    private static void ecran(GuiGraphics g) {
        if (!actif()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        float partiel = mc.getTimer().getGameTimeDeltaPartialTick(false);
        double t = secondes(partiel), r = reste(partiel);
        int W = g.guiWidth(), H = g.guiHeight();
        // l'arrivée : un éclair rouge qui s'éteint
        double arrivee = 1 - borne(t / 0.6);
        if (arrivee > 0) {
            g.fill(0, 0, W, H, ((int) (230 * arrivee) << 24) | 0xFF3232);
        }
        String texte = "Tsukuyomi : " + (int) Math.ceil(Math.max(0, r)) + " s";
        g.drawCenteredString(mc.font, Component.literal(texte), W / 2, 12, 0xFFFFB0B0);
        // les fissures blanches, puis l'éclair blanc
        double fissure = borne((1.3 - r) / 1.0);
        if (fissure > 0) {
            for (int q = 0; q < 14; q++) {
                double x = W / 2.0, y = H / 2.0, a = q / 14.0 * Math.PI * 2 + hache(q, 1);
                int n = (int) (110 * fissure);
                for (int s = 0; s < n; s++) {
                    a += (hache(q, s + 7) - 0.5) * (s % 12 == 0 ? 1.2 : 0.12);
                    x += Math.cos(a) * 2.5 * H / 540;
                    y += Math.sin(a) * 2.5 * H / 540;
                    g.fill((int) x - 1, (int) y - 1, (int) x + 1, (int) y + 1, 0xFFFFF0F0);
                }
            }
        }
        double blanc = borne((0.35 - r) / 0.3);
        if (blanc > 0) {
            g.fill(0, 0, W, H, ((int) (255 * blanc) << 24) | 0xFFFFFF);
        }
    }

    private static double hache(int i, int j) {
        long h = (i * 374761393L + j * 668265263L) & 0xFFFFFFFFL;
        h = ((h ^ (h >>> 13)) * 1274126177L) & 0xFFFFFFFFL;
        return ((h ^ (h >>> 16)) & 0xFFFFFFFFL) / 4294967296.0;
    }

    // ================================================================ le ciel du genjutsu

    /** Pas de ciel de Minecraft : un dôme rouge sang (noir tout en haut) et une énorme lune noire cerclée de rouge, au nord. */
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
            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
            RenderSystem.disableCull();
            RenderSystem.setShader(GameRenderer::getPositionColorShader);
            BufferBuilder b = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
            // le dôme : des bandes de l'horizon (rouge) jusqu'en haut (presque noir), et rouge sombre sous l'horizon
            int bandes = 12, tranches = 32;
            for (int i = -2; i < bandes; i++) {
                double e0 = i * (Math.PI / 2) / bandes, e1 = (i + 1) * (Math.PI / 2) / bandes;
                float[] c0 = ciel(e0), c1 = ciel(e1);
                for (int j = 0; j < tranches; j++) {
                    double a0 = j * Math.PI * 2 / tranches, a1 = (j + 1) * Math.PI * 2 / tranches;
                    point(b, vue, e0, a0, 100, c0);
                    point(b, vue, e0, a1, 100, c0);
                    point(b, vue, e1, a1, 100, c1);
                    point(b, vue, e1, a0, 100, c1);
                }
            }
            // la lune : au nord (vers -z), à 22° au-dessus de l'horizon, large de 30°
            Vec3 centre = direction(Math.toRadians(22), Math.PI);
            Vec3 droite = new Vec3(1, 0, 0), haut = droite.cross(centre).normalize().scale(-1);
            double r = Math.tan(Math.toRadians(15)) * 90;
            for (int j = 0; j < 48; j++) {
                double a0 = j * Math.PI * 2 / 48, a1 = (j + 1) * Math.PI * 2 / 48;
                disque(b, vue, centre.scale(90), droite, haut, a0, a1, 0, r * 0.93, new float[]{0.02F, 0.01F, 0.015F, 1});
                disque(b, vue, centre.scale(90), droite, haut, a0, a1, r * 0.93, r, new float[]{1F, 0.27F, 0.24F, 1});
                disque(b, vue, centre.scale(90.5), droite, haut, a0, a1, r, r * 1.12, new float[]{1F, 0.2F, 0.2F, 0.35F});
            }
            BufferUploader.drawWithShader(b.buildOrThrow());
            RenderSystem.enableCull();
            RenderSystem.disableBlend();
            RenderSystem.depthMask(true);
            return true;
        }

        private static float[] ciel(double elevation) {
            if (elevation <= 0) {
                return new float[]{0.42F, 0.03F, 0.05F, 1};
            }
            double k = Math.min(1, elevation / (Math.PI / 2));
            float s = (float) Math.pow(k, 0.7);
            return new float[]{0.69F + (0.06F - 0.69F) * s, 0.05F + (0.01F - 0.05F) * s, 0.08F + (0.02F - 0.08F) * s, 1};
        }

        private static Vec3 direction(double elevation, double azimut) {
            return new Vec3(Math.cos(elevation) * Math.sin(azimut), Math.sin(elevation), Math.cos(elevation) * Math.cos(azimut));
        }

        private static void point(BufferBuilder b, Matrix4f m, double elevation, double azimut, double r, float[] c) {
            Vec3 p = direction(elevation, azimut).scale(r);
            b.addVertex(m, (float) p.x, (float) p.y, (float) p.z).setColor(c[0], c[1], c[2], c[3]);
        }

        private static void disque(BufferBuilder b, Matrix4f m, Vec3 c, Vec3 x, Vec3 y, double a0, double a1, double r0, double r1, float[] col) {
            Vec3[] p = {
                    c.add(x.scale(Math.cos(a0) * r0)).add(y.scale(Math.sin(a0) * r0)), c.add(x.scale(Math.cos(a1) * r0)).add(y.scale(Math.sin(a1) * r0)),
                    c.add(x.scale(Math.cos(a1) * r1)).add(y.scale(Math.sin(a1) * r1)), c.add(x.scale(Math.cos(a0) * r1)).add(y.scale(Math.sin(a0) * r1))};
            for (Vec3 v : p) {
                b.addVertex(m, (float) v.x, (float) v.y, (float) v.z).setColor(col[0], col[1], col[2], col[3]);
            }
        }
    }

    // ================================================================ branchements

    @EventBusSubscriber(modid = Cabane.MODID, value = Dist.CLIENT)
    public static final class Enregistrement {
        @SubscribeEvent
        public static void ciel(RegisterDimensionSpecialEffectsEvent event) {
            event.register(Cabane.id("tsukuyomi"), new Ciel());
        }

        @SubscribeEvent
        public static void ecrans(RegisterGuiLayersEvent event) {
            event.registerAboveAll(Cabane.id("tsukuyomi"), (g, delta) -> ecran(g));
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
                debut = -1;
                lanceur = null;
                victime = null;
            }
        }

        @SubscribeEvent
        public static void dessiner(RenderLevelStageEvent event) {
            if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_ENTITIES || !actif()) {
                return;
            }
            clones(event.getPoseStack(), event.getCamera(), event.getPartialTick().getGameTimeDeltaPartialTick(false));
        }

        /** Les clones n'ont pas d'étiquette de nom. */
        @SubscribeEvent
        public static void nom(RenderNameTagEvent event) {
            if (cloneEnCours >= 0) {
                event.setCanRender(TriState.FALSE);
            }
        }

        @SubscribeEvent
        public static void brume(ViewportEvent.ComputeFogColor event) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.level != null && mc.level.dimension() == Genjutsu.DIMENSION) {
                event.setRed(0.55F);
                event.setGreen(0.04F);
                event.setBlue(0.06F);
            }
        }

        private Evenements() {
        }
    }

    private ClientGenjutsu() {
    }
}
