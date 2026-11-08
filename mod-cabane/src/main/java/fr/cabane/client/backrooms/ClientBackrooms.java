package fr.cabane.client.backrooms;

import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexSorting;
import com.mojang.math.Axis;
import fr.cabane.Cabane;
import fr.cabane.backrooms.Backrooms;
import fr.cabane.backrooms.Cachette;
import fr.cabane.backrooms.Monstre;
import java.util.Random;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.Input;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.MovementInputUpdateEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import org.joml.Matrix4f;
import org.joml.Matrix4fStack;
import org.lwjgl.opengl.GL11;

/**
 * Ce que voit et entend un joueur des backrooms (d'après l'aperçu) :
 * <ul>
 * <li>l'endormissement : les paupières qui clignent, lourdes, puis se ferment ; le noir ; l'arrivée qui grésille,
 * « NIVEAU 0 » ; et à la fin du rêve, les yeux qui se rouvrent chez soi sur « Quel rêve étrange ! » ;</li>
 * <li>le screamer : le monstre qui jaillit contre l'écran dans une lumière qui stroboscope, la neige, l'écran qui
 * tremble, son cri, puis le noir « ATTRAPÉ » ;</li>
 * <li>la porte EXIT qui n'en était pas une, l'évanouissement (mourir), l'alerte rouge quand un monstre te voit ;</li>
 * <li>la vue à travers les fentes du casier, le bourdonnement des néons (étouffé dans le casier), le cœur qui bat quand
 * un monstre te court après.</li>
 * </ul>
 */
@EventBusSubscriber(modid = Cabane.MODID, value = Dist.CLIENT)
public final class ClientBackrooms {
    private static final ResourceLocation CASIER = Cabane.id("textures/gui/backrooms/casier.png");
    private static final ResourceLocation NEIGE = Cabane.id("backrooms_neige");
    private static final Random HASARD = new Random();

    /** La séquence à l'écran (un genre de Backrooms.Ecran), -1 : rien. */
    private static int genre = -1;
    private static long debut;
    private static Monstre.Sorte sorte = Monstre.Sorte.RAMPEUR;
    private static long alerte = -1;
    private static DynamicTexture neige;
    private static final ModeleMonstres.Anim ANIM = new ModeleMonstres.Anim();

    /** Le serveur demande quelque chose à l'écran. */
    public static void ecran(Backrooms.Ecran p) {
        if (p.genre() == Backrooms.REPERE) {
            alerte = System.nanoTime();
            return;
        }
        genre = p.genre();
        sorte = Monstre.Sorte.values()[Mth.clamp(p.sorte(), 0, Monstre.Sorte.values().length - 1)];
        debut = System.nanoTime();
        Minecraft mc = Minecraft.getInstance();
        if (genre == Backrooms.SCREAMER || genre == Backrooms.SCREAMER_TEST) {
            mc.getSoundManager().play(SimpleSoundInstance.forUI(Backrooms.CRIS.get(sorte).get(), 1F, 1F));
        }
    }

    private static float temps() {
        return (System.nanoTime() - debut) / 1e9F;
    }

    public static boolean screamerEnCours() {
        return genre == Backrooms.SCREAMER || genre == Backrooms.SCREAMER_TEST;
    }

    /** Combien de temps dure chaque séquence (après quoi on la retire), en secondes. */
    private static float duree() {
        return switch (genre) {
            case Backrooms.ENDORMIR -> 12;
            case Backrooms.ARRIVEE -> 2.7F;
            case Backrooms.SCREAMER -> (float) sorte.debut() + 4.6F;
            case Backrooms.SCREAMER_TEST -> (float) sorte.debut() + 3.4F;
            case Backrooms.PIEGE -> 2.7F;
            case Backrooms.ASSOMME -> 3.2F;
            case Backrooms.FERMER -> 10;
            case Backrooms.REVEIL -> 6;
            default -> 0;
        };
    }

    /** Pendant les séquences, on ne bouge pas (on dort, on est attrapé…). */
    private static boolean immobile() {
        float t = temps();
        return switch (genre) {
            case Backrooms.ENDORMIR, Backrooms.FERMER, Backrooms.SCREAMER, Backrooms.PIEGE, Backrooms.ASSOMME -> true;
            case Backrooms.ARRIVEE -> t < 1.9F;
            case Backrooms.REVEIL -> t < 1.5F;
            default -> false;
        };
    }

    // ---------------------------------------------------------------- les paupières

    /** L'ouverture des yeux (0 fermés, 1 grands ouverts) selon des repères (temps, ouverture), lissée entre eux. */
    private static float courbe(float t, float... reperes) {
        if (t <= reperes[0]) {
            return reperes[1];
        }
        for (int i = 2; i < reperes.length; i += 2) {
            if (t <= reperes[i]) {
                float k = (t - reperes[i - 2]) / Math.max(1e-4F, reperes[i] - reperes[i - 2]);
                k = k * k * (3 - 2 * k);
                return Mth.lerp(k, reperes[i - 1], reperes[i + 1]);
            }
        }
        return reperes[reperes.length - 1];
    }

    private static float ouverture(float t) {
        return switch (genre) {
            // il s'endort : les yeux lourds, un clignement, il lutte, puis ils se ferment
            case Backrooms.ENDORMIR -> courbe(t, 0, 1, 0.5F, 0.75F, 0.7F, 0, 0.85F, 0.7F, 1.6F, 0.45F, 1.9F, 0, 2.1F, 0, 2.35F, 0.38F, 3.1F, 0.12F,
                    3.25F, 0, 3.4F, 0.2F, 3.9F, 0);
            case Backrooms.ARRIVEE -> courbe(t, 1.9F, 0, 2.15F, 0.5F, 2.3F, 0.1F, 2.65F, 1);
            case Backrooms.FERMER -> courbe(t, 0, 1, 0.5F, 0.6F, 0.65F, 0.15F, 0.8F, 0.5F, 1.5F, 0);
            case Backrooms.REVEIL -> courbe(t, 0.6F, 0, 0.9F, 0.35F, 1.05F, 0.05F, 1.5F, 0.6F, 1.65F, 0.25F, 2.3F, 1);
            case Backrooms.ASSOMME -> courbe(t, 0, 1, 0.35F, 0, 2.2F, 0, 2.5F, 0.4F, 2.65F, 0.1F, 3.1F, 1);
            case Backrooms.SCREAMER -> courbe(t - (float) sorte.debut(), 4.2F, 0, 4.4F, 0.6F, 4.6F, 1);
            default -> 1;
        };
    }

    /** Deux paupières noires qui se rejoignent au milieu de l'écran, le bord adouci, l'ombre qui gagne. */
    private static void paupieres(GuiGraphics g, int w, int h, float ouvert) {
        if (ouvert >= 1) {
            return;
        }
        if (ouvert <= 0.002F) {
            g.fill(0, 0, w, h, 0xFF000000);
            return;
        }
        g.fill(0, 0, w, h, (int) ((1 - ouvert) * 110) << 24);
        float demi = h * 0.62F * ouvert;
        int pas = 2;
        for (int x = 0; x < w; x += pas) {
            float u = (x + pas / 2F - w / 2F) / (w / 2F);
            float o = demi * (float) Math.sqrt(Math.max(0, 1 - u * u * 0.75F));
            int haut = (int) (h / 2F - o), bas = (int) (h / 2F + o);
            g.fill(x, 0, x + pas, haut, 0xFF000000);
            g.fill(x, bas, x + pas, h, 0xFF000000);
            int bord = Math.max(2, (int) (14 * ouvert));
            g.fillGradient(x, haut, x + pas, haut + bord, 0xD0000000, 0x00000000);
            g.fillGradient(x, bas - bord, x + pas, bas, 0x00000000, 0xD0000000);
        }
    }

    // ---------------------------------------------------------------- l'écran

    private static void texte(GuiGraphics g, Font font, Component t, float x, float y, float echelle, int couleur, float alpha) {
        if (alpha <= 0.02F) {
            return;
        }
        int a = Mth.clamp((int) (alpha * 255), 4, 255);
        g.pose().pushPose();
        g.pose().translate(x, y, 0);
        g.pose().scale(echelle, echelle, 1);
        g.drawString(font, t, -font.width(t) / 2, 0, a << 24 | couleur & 0xFFFFFF, true);
        g.pose().popPose();
    }

    private static float fondu(float t, float entree, float sortie, float duree) {
        return Mth.clamp((t - entree) / duree, 0, 1) * Mth.clamp((sortie - t) / duree, 0, 1);
    }

    private static void dessiner(GuiGraphics g, float partiel) {
        Minecraft mc = Minecraft.getInstance();
        int w = g.guiWidth(), h = g.guiHeight();
        // dans le casier : l'acier sombre et ses fentes
        if (mc.player != null && mc.player.getVehicle() instanceof Cachette && genre < 0) {
            RenderSystem.enableBlend();
            g.blit(CASIER, 0, 0, w, h, 0, 0, 640, 360, 640, 360);
            RenderSystem.disableBlend();
        }
        if (alerte >= 0) {
            float t = (System.nanoTime() - alerte) / 1e9F;
            if (t > 0.9F) {
                alerte = -1;
            } else {
                int a = (int) (Math.max(0, 1 - t / 0.9F) * 150);
                int bord = h / 3;
                g.fillGradient(0, 0, w, bord, a << 24 | 0x8A0000, 0x008A0000);
                g.fillGradient(0, h - bord, w, h, 0x008A0000, a << 24 | 0x8A0000);
            }
        }
        if (genre < 0) {
            return;
        }
        float t = temps();
        if (t > duree()) {
            genre = -1;
            return;
        }
        Font font = mc.font;
        switch (genre) {
            case Backrooms.ENDORMIR, Backrooms.FERMER -> {
                if (t > 0.6F) {
                    mc.gameRenderer.processBlurEffect(partiel);
                    mc.getMainRenderTarget().bindWrite(false);
                }
                paupieres(g, w, h, ouverture(t));
            }
            case Backrooms.REVEIL -> {
                if (t < 2.2F) {
                    mc.gameRenderer.processBlurEffect(partiel);
                    mc.getMainRenderTarget().bindWrite(false);
                }
                paupieres(g, w, h, ouverture(t));
                texte(g, font, Component.literal("Quel rêve étrange !").withStyle(s -> s.withItalic(true)), w / 2F, h * 0.72F, 2.2F, 0xF4ECD8,
                        fondu(t, 1.0F, 5.6F, 0.6F));
            }
            case Backrooms.ARRIVEE -> {
                if (t < 1.9F) {
                    g.fill(0, 0, w, h, 0xFF000000);
                    neige(g, w, h, t < 0.7F ? 0.5F : 0.12F);
                    float a = fondu(t, 0.25F, 1.9F, 0.2F);
                    texte(g, font, Component.literal("NIVEAU 0"), w / 2F, h / 2F - 26, 4F, 0xE9D88A, a);
                    texte(g, font, Component.literal("Tu es passé à travers le sol."), w / 2F, h / 2F + 16, 1.2F, 0xBFB9A8, a);
                } else {
                    neige(g, w, h, 0.06F);
                    paupieres(g, w, h, ouverture(t));
                }
            }
            case Backrooms.PIEGE -> {
                g.fill(0, 0, w, h, (int) (Mth.clamp(Math.min(t / 0.25F, (2.7F - t) / 0.4F), 0, 1) * 255) << 24);
                float a = fondu(t, 0.25F, 2.4F, 0.25F);
                texte(g, font, Component.literal("EXIT ?"), w / 2F, h / 2F - 34, 4F, 0xFF3C2E, a);
                texte(g, font, Component.literal("Ce n'était pas la sortie."), w / 2F, h / 2F + 10, 1.3F, 0xE6E0D0, fondu(t, 0.8F, 2.4F, 0.25F));
                texte(g, font, Component.literal("Tu te retrouves ailleurs dans les backrooms."), w / 2F, h / 2F + 28, 1F, 0x9A9488,
                        fondu(t, 1.3F, 2.4F, 0.25F));
            }
            case Backrooms.ASSOMME -> {
                paupieres(g, w, h, ouverture(t));
                texte(g, font, Component.literal("Tu t'évanouis…"), w / 2F, h / 2F - 10, 1.6F, 0xE6E0D0, fondu(t, 0.4F, 1.5F, 0.25F));
                texte(g, font, Component.literal("Tu te réveilles ailleurs dans les backrooms."), w / 2F, h / 2F - 4, 1.1F, 0xBFB9A8,
                        fondu(t, 1.4F, 2.3F, 0.2F));
            }
            case Backrooms.SCREAMER, Backrooms.SCREAMER_TEST -> screamer(g, mc, w, h, t, partiel);
            default -> {
            }
        }
    }

    // ---------------------------------------------------------------- le screamer

    private static void screamer(GuiGraphics g, Minecraft mc, int w, int h, float t, float partiel) {
        float d0 = (float) sorte.debut();
        boolean enJeu = genre == Backrooms.SCREAMER;
        if (t > d0 + 1.45F) {
            if (enJeu && t > d0 + 4.2F) {
                // il se réveille ailleurs : les yeux se rouvrent
                paupieres(g, w, h, ouverture(t));
                return;
            }
            // le noir : « ATTRAPÉ par … »
            g.fill(0, 0, w, h, 0xFF000000);
            Font font = mc.font;
            texte(g, font, Component.literal("ATTRAPÉ"), w / 2F, h / 2F - 34, 4.5F, 0xD8261C, 1);
            texte(g, font, Component.literal("par " + sorte.nom), w / 2F, h / 2F + 14, 1.5F, 0xE6E0D0, 1);
            if (enJeu) {
                texte(g, font, Component.literal("Tu te réveilles ailleurs dans les backrooms…"), w / 2F, h / 2F + 34, 1F, 0x9A9488,
                        fondu(t, d0 + 2.0F, 99, 0.3F));
            }
            return;
        }
        g.fill(0, 0, w, h, 0xFF000000);
        g.flush();
        // la secousse de l'écran
        float secoue = t > d0 && t < d0 + 1.5F ? 1 : 0;
        g.pose().pushPose();
        g.pose().translate((HASARD.nextFloat() - 0.5F) * 6 * secoue, (HASARD.nextFloat() - 0.5F) * 4 * secoue, 0);
        monstre3D(mc, t, d0);
        g.pose().popPose();
        // la lumière qui stroboscope : un flash blanc quand il se jette, puis des éclairs
        float flash = t > d0 && t < d0 + 0.07F ? 1 : t > d0 && Math.floorMod((int) (t * 12), 3) == 0 ? 0.35F : 0;
        if (flash > 0) {
            g.fill(0, 0, w, h, (int) (flash * 255) << 24 | 0xFFFFFF);
        }
        // un voile de neige léger qui laisse voir le visage, avec des à-coups
        boolean fort = t >= d0 || HASARD.nextBoolean();
        neige(g, w, h, fort ? (HASARD.nextFloat() < 0.22F ? 0.42F : 0.12F) : 0.06F);
    }

    /** Le monstre en 3D, devant la caméra : il attend au loin dans le noir, puis se jette contre l'écran, gueule ouverte. */
    private static void monstre3D(Minecraft mc, float t, float d0) {
        float loin = sorte == Monstre.Sorte.FETARD ? 2.7F : sorte == Monstre.Sorte.SANSVISAGE ? 2.4F : 3.4F;
        float[] v = ModeleMonstres.visage(sorte);
        float k = t < d0 ? 0 : Math.min(1, (t - d0) / 0.11F);
        float dist = Mth.lerp(1 - (1 - k) * (1 - k) * (1 - k), loin, v[3]);
        ANIM.ouvert = t < d0 ? 0 : Math.min(1, (t - d0) / 0.16F);
        ANIM.t = t;
        ANIM.vitesse = 0;
        ANIM.cycle = t * 6;
        ANIM.chasse = (sorte == Monstre.Sorte.FETARD && t < d0) || sorte == Monstre.Sorte.PHALENE;
        float a = t < d0 ? 0.004F : 0.03F;
        float allume = t < d0 ? (sorte == Monstre.Sorte.SOURIANT ? 0.06F : 0.55F) : (Math.floorMod((int) (t * 24), 2) == 1 ? 1F : 0.32F);
        int lumiere = LightTexture.pack(Math.round(allume * 15), Math.round(allume * 15));
        float fov = t < d0 ? 70 : 62 + Mth.sin(t * 40) * 3;

        int w = mc.getWindow().getWidth(), h = mc.getWindow().getHeight();
        Matrix4f ancienne = new Matrix4f(RenderSystem.getProjectionMatrix());
        VertexSorting tri = RenderSystem.getVertexSorting();
        float brouillard = RenderSystem.getShaderFogStart();
        RenderSystem.setProjectionMatrix(new Matrix4f().setPerspective(fov * Mth.DEG_TO_RAD, (float) w / Math.max(1, h), 0.05F, 60F),
                VertexSorting.DISTANCE_TO_ORIGIN);
        Matrix4fStack mv = RenderSystem.getModelViewStack();
        mv.pushMatrix();
        mv.identity();
        RenderSystem.applyModelViewMatrix();
        RenderSystem.setShaderFogStart(Float.MAX_VALUE);
        RenderSystem.clear(GL11.GL_DEPTH_BUFFER_BIT, Minecraft.ON_OSX);
        Lighting.setupForEntityInInventory();
        RenderSystem.enableDepthTest();

        PoseStack ps = new PoseStack();
        ps.mulPose(Axis.ZP.rotation(t < d0 ? 0 : (HASARD.nextFloat() - 0.5F) * 0.06F));
        ps.translate(-v[0] + (HASARD.nextFloat() - 0.5F) * a, -v[1] + (HASARD.nextFloat() - 0.5F) * a, -dist - v[2]);
        ps.mulPose(Axis.YP.rotation((HASARD.nextFloat() - 0.5F) * a * 2));
        ps.mulPose(Axis.ZP.rotation((HASARD.nextFloat() - 0.5F) * a * 3));
        MultiBufferSource.BufferSource buf = mc.renderBuffers().bufferSource();
        ModeleMonstres.dessiner(sorte, ps, buf, lumiere, OverlayTexture.NO_OVERLAY, ANIM);
        buf.endBatch();

        mv.popMatrix();
        RenderSystem.applyModelViewMatrix();
        RenderSystem.setProjectionMatrix(ancienne, tri);
        RenderSystem.setShaderFogStart(brouillard);
        Lighting.setupFor3DItems();
        RenderSystem.clear(GL11.GL_DEPTH_BUFFER_BIT, Minecraft.ON_OSX);
    }

    /** La neige de l'écran : une image de bruit refaite à chaque fois, étirée sur tout l'écran. */
    private static void neige(GuiGraphics g, int w, int h, float alpha) {
        Minecraft mc = Minecraft.getInstance();
        if (neige == null) {
            neige = new DynamicTexture(new NativeImage(160, 90, false));
            mc.getTextureManager().register(NEIGE, neige);
        }
        NativeImage img = neige.getPixels();
        if (img == null) {
            return;
        }
        for (int y = 0; y < 90; y++) {
            for (int x = 0; x < 160; x++) {
                int v = HASARD.nextInt(256);
                img.setPixelRGBA(x, y, 0xFF000000 | v << 16 | v << 8 | v);
            }
        }
        neige.upload();
        RenderSystem.enableBlend();
        g.setColor(1, 1, 1, alpha);
        g.blit(NEIGE, 0, 0, w, h, 0, 0, 160, 90, 160, 90);
        g.setColor(1, 1, 1, 1);
        RenderSystem.disableBlend();
    }

    // ---------------------------------------------------------------- le son : le bourdon des néons, le cœur

    private static Bourdon bourdon;
    private static int coeur;

    /** Le bourdonnement des néons, partout dans les backrooms (plus sourd dans un casier, coupé pendant le screamer). */
    private static final class Bourdon extends AbstractTickableSoundInstance {
        Bourdon() {
            super(Backrooms.SON_BOURDON.get(), SoundSource.AMBIENT, SoundInstance.createUnseededRandom());
            looping = true;
            delay = 0;
            volume = 0.01F;
            relative = true;
            attenuation = Attenuation.NONE;
        }

        @Override
        public void tick() {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null || mc.level == null || mc.level.dimension() != Backrooms.DIMENSION) {
                stop();
                return;
            }
            float cible = screamerEnCours() || genre == Backrooms.FERMER ? 0 : mc.player.getVehicle() instanceof Cachette ? 0.22F : 0.6F;
            volume += (cible - volume) * 0.08F;
        }
    }

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            genre = -1;
            return;
        }
        if (mc.level.dimension() != Backrooms.DIMENSION) {
            return;
        }
        // pas de musique du jeu : seulement les néons
        mc.getMusicManager().stopPlaying();
        if (bourdon == null || bourdon.isStopped() || !mc.getSoundManager().isActive(bourdon)) {
            if (bourdon != null) {
                mc.getSoundManager().stop(bourdon);
            }
            bourdon = new Bourdon();
            mc.getSoundManager().play(bourdon);
        }
        // le cœur qui bat : un monstre te court après, ou il rôde devant ton casier
        if (--coeur <= 0 && !screamerEnCours()) {
            boolean cache = mc.player.getVehicle() instanceof Cachette;
            double proche = Double.MAX_VALUE;
            boolean chasse = false;
            for (Entity e : mc.level.entitiesForRendering()) {
                if (e instanceof Monstre m) {
                    double d = m.distanceTo(mc.player);
                    proche = Math.min(proche, d);
                    chasse |= m.etat() == Monstre.CHASSE && d < 30;
                }
            }
            if (chasse || (cache && proche < 7)) {
                mc.getSoundManager().play(SimpleSoundInstance.forUI(Backrooms.SON_COEUR.get(), 1F, cache ? 0.55F : 0.4F));
                coeur = proche < 4 ? 10 : 16;
            }
        }
    }

    /** Pendant qu'on change de monde, l'écran de chargement du jeu ne doit pas rouvrir les yeux : il reste noir. */
    @SubscribeEvent
    public static void chargement(net.neoforged.neoforge.client.event.ScreenEvent.Render.Post event) {
        if (event.getScreen() instanceof net.minecraft.client.gui.screens.ReceivingLevelScreen && genre >= 0 && immobile()) {
            GuiGraphics g = event.getGuiGraphics();
            g.fill(0, 0, g.guiWidth(), g.guiHeight(), 0xFF000000);
        }
    }

    @SubscribeEvent
    public static void immobiliser(MovementInputUpdateEvent event) {
        if (genre >= 0 && immobile() && temps() < duree()) {
            Input in = event.getInput();
            in.forwardImpulse = 0;
            in.leftImpulse = 0;
            in.up = in.down = in.left = in.right = false;
            in.jumping = false;
            in.shiftKeyDown = false;
        }
    }

    @EventBusSubscriber(modid = Cabane.MODID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
    public static final class Couches {
        @SubscribeEvent
        public static void enregistrer(RegisterGuiLayersEvent event) {
            event.registerAboveAll(Cabane.id("backrooms"), (g, delta) -> dessiner(g, delta.getGameTimeDeltaPartialTick(true)));
        }
    }

    private ClientBackrooms() {
    }
}
