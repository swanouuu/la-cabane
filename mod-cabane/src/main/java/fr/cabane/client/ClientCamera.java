package fr.cabane.client;

import com.mojang.blaze3d.platform.NativeImage;
import fr.cabane.Cabane;
import fr.cabane.client.ui.Ios;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.RenderGuiLayerEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;

/**
 * L'appareil photo : le téléphone se tient à l'horizontale devant les yeux, son écran laisse voir
 * le monde. On bouge et on vise normalement ; clic gauche prend la photo (exactement le cadre du
 * viseur), clic droit, N ou Échap rangent le téléphone.
 */
public final class ClientCamera {
    static boolean actif;
    private static int photoDans = -1;
    private static int flash;
    private static int[] cadre = new int[4];
    private static boolean guiAvant;

    static void ouvrir() {
        Minecraft mc = Minecraft.getInstance();
        mc.setScreen(null);
        actif = true;
        mc.player.displayClientMessage(Component.literal("📷 Clic gauche : photo · clic droit ou N : ranger"), true);
    }

    static void fermer() {
        actif = false;
    }

    /** Le téléphone à l'horizontale et son viseur 16:9, en coordonnées d'interface. */
    private static int[] telephone(int largeurEcran, int hauteurEcran) {
        int w = Math.min(largeurEcran - 24, (hauteurEcran - 24) * 2);
        int h = w / 2;
        int x = (largeurEcran - w) / 2;
        int y = (hauteurEcran - h) / 2;
        return new int[] {x, y, w, h};
    }

    private static int[] viseur(int[] t) {
        int vw = t[2] - 74;
        int vh = vw * 9 / 16;
        if (vh > t[3] - 16) {
            vh = t[3] - 16;
            vw = vh * 16 / 9;
        }
        return new int[] {t[0] + 14, t[1] + (t[3] - vh) / 2, vw, vh};
    }

    private static void dessiner(GuiGraphics g) {
        Minecraft mc = Minecraft.getInstance();
        int[] t = telephone(g.guiWidth(), g.guiHeight());
        int[] v = viseur(t);
        int x = t[0], y = t[1], w = t[2], h = t[3];
        // le boîtier autour du viseur
        // le contour du téléphone (seulement le bord : le viseur reste transparent)
        int bord = 0xFF2C2C2E;
        g.fill(x - 4, y - 4, x + w + 4, y, bord);
        g.fill(x - 4, y + h, x + w + 4, y + h + 4, bord);
        g.fill(x - 4, y, x, y + h, bord);
        g.fill(x + w, y, x + w + 4, y + h, bord);
        g.fill(x, y, x + w, v[1], Ios.NOIR);
        g.fill(x, v[1] + v[3], x + w, y + h, Ios.NOIR);
        g.fill(x, v[1], v[0], v[1] + v[3], Ios.NOIR);
        g.fill(v[0] + v[2], v[1], x + w, v[1] + v[3], Ios.NOIR);
        // la grille des tiers, le cadre de mise au point
        int grille = 0x40FFFFFF;
        for (int i = 1; i < 3; i++) {
            g.fill(v[0] + v[2] * i / 3, v[1], v[0] + v[2] * i / 3 + 1, v[1] + v[3], grille);
            g.fill(v[0], v[1] + v[3] * i / 3, v[0] + v[2], v[1] + v[3] * i / 3 + 1, grille);
        }
        int cx = v[0] + v[2] / 2, cy = v[1] + v[3] / 2;
        g.renderOutline(cx - 14, cy - 14, 28, 28, 0xFFFFD60A);
        // la colonne de droite : mode, déclencheur, dernière photo
        int colonne = v[0] + v[2] + (x + w - v[0] - v[2]) / 2;
        Ios.texteCentre(g, mc.font, "PHOTO", colonne, y + 12, 0.6F, 0xFFFFD60A, true);
        Ios.disque(g, colonne, y + h / 2, 15, Ios.BLANC);
        Ios.disque(g, colonne, y + h / 2, 13, Ios.NOIR);
        Ios.disque(g, colonne, y + h / 2, 11, Ios.BLANC);
        ResourceLocation derniere = ClientPiaf.galerie.isEmpty() ? null : ClientPiaf.photo(ClientPiaf.galerie.getLast());
        if (derniere != null) {
            g.blit(derniere, colonne - 12, y + h - 26, 24, 14, 0, 0, 320, 180, 320, 180);
        }
        Ios.texteCentre(g, mc.font, "clic gauche", colonne, y + h - 8, 0.5F, Ios.GRIS, false);
        if (flash > 0) {
            g.fill(v[0], v[1], v[0] + v[2], v[1] + v[3], (flash * 40) << 24 | 0xFFFFFF);
        }
    }

    private static void declencher() {
        Minecraft mc = Minecraft.getInstance();
        int[] v = viseur(telephone(mc.getWindow().getGuiScaledWidth(), mc.getWindow().getGuiScaledHeight()));
        double s = mc.getWindow().getGuiScale();
        cadre = new int[] {(int) (v[0] * s), (int) (v[1] * s), (int) (v[2] * s), (int) (v[3] * s)};
        guiAvant = mc.options.hideGui;
        mc.options.hideGui = true;
        photoDans = 2;
    }

    @EventBusSubscriber(modid = Cabane.MODID, value = Dist.CLIENT)
    public static final class Couches {
        @SubscribeEvent
        public static void enregistrer(RegisterGuiLayersEvent event) {
            event.registerAboveAll(Cabane.id("appareil_photo"), (g, delta) -> {
                if (actif && Minecraft.getInstance().screen == null) {
                    dessiner(g);
                }
            });
        }
    }

    @EventBusSubscriber(modid = Cabane.MODID, value = Dist.CLIENT)
    public static final class Evenements {
        /** Pendant la visée, seul le réticule reste de l'interface habituelle. */
        @SubscribeEvent
        public static void cacherInterface(RenderGuiLayerEvent.Pre event) {
            if (actif && !event.getName().equals(VanillaGuiLayers.CROSSHAIR) && !event.getName().equals(Cabane.id("appareil_photo"))) {
                event.setCanceled(true);
            }
        }

        @SubscribeEvent(priority = EventPriority.HIGH)
        public static void clic(InputEvent.InteractionKeyMappingTriggered event) {
            if (!actif) {
                return;
            }
            event.setCanceled(true);
            event.setSwingHand(false);
            if (event.isAttack() && photoDans < 0) {
                declencher();
            } else if (event.isUseItem()) {
                fermer();
            }
        }

        @SubscribeEvent
        public static void echap(ScreenEvent.Opening event) {
            if (actif && event.getNewScreen() instanceof PauseScreen) {
                event.setCanceled(true);
                fermer();
            }
        }

        @SubscribeEvent
        public static void tick(ClientTickEvent.Post event) {
            Minecraft mc = Minecraft.getInstance();
            if (flash > 0) {
                flash--;
            }
            if (actif && (mc.player == null || mc.screen != null && !(mc.screen instanceof PauseScreen))) {
                actif = false;
            }
            if (photoDans < 0 || --photoDans > 0) {
                return;
            }
            photoDans = -1;
            try (NativeImage ecran = Screenshot.takeScreenshot(mc.getMainRenderTarget());
                 NativeImage photo = new NativeImage(320, 180, false)) {
                int x = Math.max(0, cadre[0]);
                int y = Math.max(0, cadre[1]);
                int w = Math.min(cadre[2], ecran.getWidth() - x);
                int h = Math.min(cadre[3], ecran.getHeight() - y);
                ecran.resizeSubRectTo(x, y, w, h, photo);
                ClientPiaf.envoyer(photo.asByteArray());
                mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_CARTOGRAPHY_TABLE_TAKE_RESULT, 1.8F));
                flash = 6;
            } catch (Exception e) {
                Cabane.LOGGER.warn("Photo ratée : {}", e.toString());
            } finally {
                mc.options.hideGui = guiAvant;
            }
        }
    }

    private ClientCamera() {
    }
}
