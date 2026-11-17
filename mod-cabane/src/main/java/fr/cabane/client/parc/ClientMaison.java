package fr.cabane.client.parc;

import com.mojang.blaze3d.platform.NativeImage;
import fr.cabane.Cabane;
import java.util.Random;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderGuiEvent;

/**
 * Les screamers du tunnel des cris (Maison Hantée), chez le joueur qui passe : un visage blafard aux orbites noires
 * plein écran pendant une fraction de seconde, qui tremble, avec un hurlement (les cris des backrooms), et la
 * silhouette qui sort de sa niche. Les trois visages sont dessinés ici, en gros pixels, comme dans la maquette.
 */
@EventBusSubscriber(modid = Cabane.MODID, value = Dist.CLIENT)
public final class ClientMaison {
    private static final int TAILLE = 96;
    private static final long DUREE = 850;
    private static final String[] SONS = {"backrooms.cri.rampeur", "backrooms.cri.phalene", "backrooms.cri.rampeur"};
    private static final float[] HAUTEURS = {1F, 1F, 0.8F};
    private static ResourceLocation[] visages;
    private static long depuis = -1;
    private static int quel;

    /** Le serveur : le screamer numéro k saute sur ce joueur. */
    public static void cri(byte k) {
        Minecraft mc = Minecraft.getInstance();
        preparer(mc);
        quel = Math.floorMod(k, 3);
        depuis = System.currentTimeMillis();
        DecorParc.cri(k);
        mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvent.createVariableRangeEvent(Cabane.id(SONS[quel])), HAUTEURS[quel], 1F));
        mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.GHAST_SCREAM, 0.6F, 0.5F));
    }

    private static void preparer(Minecraft mc) {
        if (visages != null) {
            return;
        }
        visages = new ResourceLocation[3];
        for (int k = 0; k < 3; k++) {
            visages[k] = Cabane.id("parc/visage_" + k);
            mc.getTextureManager().register(visages[k], new DynamicTexture(visage(k)));
        }
    }

    // ------------------------------------------------------------------ le dessin des visages

    private static void point(NativeImage img, int x, int y, int rgb) {
        if (x >= 0 && y >= 0 && x < TAILLE && y < TAILLE) {
            img.setPixelRGBA(x, y, 0xFF000000 | (rgb & 0xFF) << 16 | (rgb >> 8 & 0xFF) << 8 | rgb >> 16 & 0xFF);
        }
    }

    private static boolean dansEllipse(double x, double y, double cx, double cy, double rx, double ry, double angle) {
        double dx = x - cx, dy = y - cy, c = Math.cos(angle), s = Math.sin(angle);
        double u = dx * c + dy * s, v = -dx * s + dy * c;
        return u * u / (rx * rx) + v * v / (ry * ry) <= 1;
    }

    private static void ellipse(NativeImage img, double cx, double cy, double rx, double ry, double angle, int rgb) {
        for (int y = 0; y < TAILLE; y++) {
            for (int x = 0; x < TAILLE; x++) {
                if (dansEllipse(x + 0.5, y + 0.5, cx, cy, rx, ry, angle)) {
                    point(img, x, y, rgb);
                }
            }
        }
    }

    private static void trait(NativeImage img, double x0, double y0, double x1, double y1, int rgb) {
        int n = (int) Math.ceil(Math.hypot(x1 - x0, y1 - y0) * 2);
        for (int i = 0; i <= n; i++) {
            double t = (double) i / Math.max(1, n);
            point(img, (int) (x0 + (x1 - x0) * t), (int) (y0 + (y1 - y0) * t), rgb);
        }
    }

    private static NativeImage visage(int k) {
        NativeImage img = new NativeImage(TAILLE, TAILLE, false);
        Random r = new Random(77 + k);
        double e = TAILLE / 400.0;
        int[] fonds = {0x2A0505, 0x3A0000, 0x06140C}, peaux = {0xD8DDD0, 0xC9BFB2, 0xB9CFC2};
        // le fond : une lueur sombre au milieu
        for (int y = 0; y < TAILLE; y++) {
            for (int x = 0; x < TAILLE; x++) {
                double d = Math.min(1, Math.hypot(x - 48, y - 48) / 67);
                int c = fonds[k];
                int rr = (int) ((c >> 16 & 255) * (1 - d)), gg = (int) ((c >> 8 & 255) * (1 - d)), bb = (int) ((c & 255) * (1 - d));
                point(img, x, y, rr << 16 | gg << 8 | bb);
            }
        }
        ellipse(img, 200 * e, 215 * e, (118 - k * 10) * e, 168 * e, 0, peaux[k]);
        int ombre = peaux[k] >> 1 & 0x7F7F7F;
        ellipse(img, 200 * e, 300 * e, 100 * e, 80 * e, 0, (peaux[k] & 0xFEFEFE) / 2 + (ombre & 0xFEFEFE) / 2);
        for (int s = -1; s <= 1; s += 2) {
            ellipse(img, (200 + s * 48) * e, 160 * e, (32 + k * 4) * e, (44 + k * 7) * e, s * 0.25, 0x000000);
            ellipse(img, (200 + s * 46) * e, 168 * e, 5 * e + 0.6, 5 * e + 0.6, 0, k == 2 ? 0xFF2020 : 0xFFFFFF);
            for (int w = -1; w <= 1; w++) {
                trait(img, (200 + s * 48) * e + w * 0.5, 200 * e, (200 + s * 52) * e + w * 0.5, (250 + k * 15) * e, 0x8A0000);
            }
        }
        ellipse(img, 200 * e, 305 * e, (48 + k * 8) * e, (72 + k * 10) * e, 0, 0x000000);
        for (int i = -3; i <= 3; i++) {
            double cx = (200 + i * 13) * e, haut = (245 - k * 6) * e, bas = 268 * e;
            for (double y = haut; y <= bas; y += 0.5) {
                double demi = 6 * e * (bas - y) / (bas - haut);
                for (double x = cx - demi; x <= cx + demi; x += 0.5) {
                    point(img, (int) x, (int) y, 0xE8E2C8);
                }
            }
        }
        for (int i = 0; i < 6; i++) {
            double x = (120 + r.nextDouble() * 160) * e, y = (70 + r.nextDouble() * 60) * e;
            for (int j = 0; j < 4; j++) {
                double nx = x + (r.nextDouble() - 0.5) * 40 * e, ny = y + (15 + r.nextDouble() * 15) * e;
                trait(img, x, y, nx, ny, 0x2A1A1A);
                x = nx;
                y = ny;
            }
        }
        return img;
    }

    // ------------------------------------------------------------------ à l'écran

    @SubscribeEvent
    public static void dessiner(RenderGuiEvent.Post event) {
        if (depuis < 0 || visages == null) {
            return;
        }
        long t = System.currentTimeMillis() - depuis;
        if (t > DUREE) {
            depuis = -1;
            return;
        }
        GuiGraphics g = event.getGuiGraphics();
        int w = g.guiWidth(), h = g.guiHeight();
        g.fill(0, 0, w, h, 0xFF000000);
        // il bondit vers l'écran, puis tremble
        double k = Math.min(1, t / 140.0);
        double echelle = 0.55 + 0.6 * (1 - (1 - k) * (1 - k));
        int taille = (int) (Math.min(w, h) * echelle);
        Random r = new Random(t / 40);
        int dx = (int) ((r.nextDouble() - 0.5) * 14), dy = (int) ((r.nextDouble() - 0.5) * 14);
        int x = (w - taille) / 2 + dx, y = (h - taille) / 2 + dy;
        g.blit(visages[quel], x, y, taille, taille, 0, 0, TAILLE, TAILLE, TAILLE, TAILLE);
        if (t > DUREE - 120) {
            g.fill(0, 0, w, h, (int) (255 * (t - (DUREE - 120)) / 120.0) << 24);
        }
    }

    private ClientMaison() {
    }
}
