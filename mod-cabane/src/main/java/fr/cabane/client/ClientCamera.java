package fr.cabane.client;

import com.mojang.blaze3d.platform.NativeImage;
import fr.cabane.Cabane;
import fr.cabane.client.ui.Ios;
import net.minecraft.client.Camera;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.RenderGuiLayerEvent;
import net.neoforged.neoforge.client.event.RenderHandEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;

/**
 * L'appareil photo : le téléphone se tient devant les yeux, à plat (PHOTO) ou debout (PORTRAIT, SELFIE), son écran
 * laisse voir le monde. Clic gauche prend la photo (exactement le cadre du viseur), la molette change de mode, le clic
 * molette règle le retardateur ; clic droit, N ou Échap rangent le téléphone.
 *
 * Avec le retardateur, le téléphone reste « posé » là où on l'a déclenché : on a le temps d'aller se mettre dans le
 * cadre, et la photo est prise depuis cet endroit.
 */
public final class ClientCamera {
    static boolean actif;
    /** 0 photo (paysage), 1 portrait, 2 selfie (debout, objectif de face). */
    static int mode;
    private static final String[] MODES = {"PHOTO", "PORTRAIT", "SELFIE"};
    /** Le retardateur, en secondes (0 = sans). */
    static int retardateur;
    private static final int[] DELAIS = {0, 3, 5, 10};

    private static int photoDans = -1;
    private static int flash;
    private static int[] cadre = new int[4];
    private static boolean portraitPhoto;
    private static boolean guiAvant;
    /** La vue d'avant le selfie, rendue en rangeant le téléphone. */
    private static CameraType vueAvant;
    /** Le compte à rebours du retardateur, en ticks (-1 : aucun). */
    private static int compte = -1;
    private static Vec3 posePosition;
    private static float poseLacet;
    private static float poseTangage;
    /** L'objectif posé, qui sert de caméra le temps de la photo. */
    private static Entity objectif;
    private static CameraType vuePose;

    static void ouvrir() {
        Minecraft mc = Minecraft.getInstance();
        mc.setScreen(null);
        actif = true;
        appliquerVue();
        mc.player.displayClientMessage(Component.literal("📷 Clic gauche : photo · molette : mode · clic molette : retardateur · clic droit : ranger"), true);
    }

    static void fermer() {
        actif = false;
        compte = -1;
        if (vueAvant != null) {
            Minecraft.getInstance().options.setCameraType(vueAvant);
            vueAvant = null;
        }
    }

    /** En selfie, la vue passe de face ; sinon on revient à la vue d'avant. */
    private static void appliquerVue() {
        var options = Minecraft.getInstance().options;
        if (mode == 2) {
            if (vueAvant == null) {
                vueAvant = options.getCameraType();
            }
            options.setCameraType(CameraType.THIRD_PERSON_FRONT);
        } else if (vueAvant != null) {
            options.setCameraType(vueAvant);
            vueAvant = null;
        }
    }

    private static boolean portrait() {
        return mode != 0;
    }

    /** Le téléphone tenu devant les yeux, à plat ou debout, en coordonnées d'interface : x, y, largeur, hauteur. */
    private static int[] telephone(int largeurEcran, int hauteurEcran) {
        if (portrait()) {
            int h = hauteurEcran - 24;
            int vw = (h - 74) * 9 / 16;
            int w = vw + 16;
            return new int[] {(largeurEcran - w) / 2, 12, w, h};
        }
        int w = Math.min(largeurEcran - 24, (hauteurEcran - 24) * 2);
        int h = w / 2;
        return new int[] {(largeurEcran - w) / 2, (hauteurEcran - h) / 2, w, h};
    }

    private static int[] viseur(int[] t) {
        if (portrait()) {
            int vw = t[2] - 16;
            return new int[] {t[0] + 8, t[1] + 14, vw, vw * 16 / 9};
        }
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
        if (compte >= 0) {
            // le téléphone est posé : il ne reste à l'écran que le compte à rebours
            int s = (compte + 19) / 20;
            Ios.texteCentre(g, mc.font, String.valueOf(s), g.guiWidth() / 2F, g.guiHeight() / 2F - 30, 5F, 0xFFFFD60A, true);
            Ios.texteCentre(g, mc.font, "Mets-toi dans le cadre !", g.guiWidth() / 2F, g.guiHeight() / 2F + 14, 1F, Ios.BLANC, true);
            Ios.texteCentre(g, mc.font, "clic droit : annuler", g.guiWidth() / 2F, g.guiHeight() / 2F + 28, 0.6F, Ios.GRIS, true);
            return;
        }
        int[] t = telephone(g.guiWidth(), g.guiHeight());
        int[] v = viseur(t);
        int x = t[0], y = t[1], w = t[2], h = t[3];
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
        String delai = retardateur == 0 ? "⏱ non" : "⏱ " + retardateur + " s";
        Long derniere = ClientPiaf.galerie.isEmpty() ? null : ClientPiaf.galerie.getLast();
        if (portrait()) {
            // en bas : les modes, le déclencheur au milieu, la dernière photo à gauche, le retardateur à droite
            int bas = v[1] + v[3];
            for (int i = 0; i < MODES.length; i++) {
                Ios.texteCentre(g, mc.font, MODES[i], x + w / 2F + (i - mode) * 34, bas + 4, 0.55F, i == mode ? 0xFFFFD60A : Ios.GRIS, false);
            }
            int ry = bas + (y + h - bas) / 2 + 4;
            Ios.disque(g, x + w / 2, ry, 13, Ios.BLANC);
            Ios.disque(g, x + w / 2, ry, 11, Ios.NOIR);
            Ios.disque(g, x + w / 2, ry, 9, Ios.BLANC);
            if (derniere != null) {
                ClientPiaf.dessinerPhoto(g, derniere, x + 10, ry - 9, 18, 18, true);
            }
            Ios.texteCentre(g, mc.font, delai, x + w - 20, ry - 3, 0.6F, retardateur == 0 ? Ios.GRIS : 0xFFFFD60A, false);
        } else {
            // à droite : les modes, le retardateur, le déclencheur, la dernière photo
            int colonne = v[0] + v[2] + (x + w - v[0] - v[2]) / 2;
            for (int i = 0; i < MODES.length; i++) {
                Ios.texteCentre(g, mc.font, MODES[i], colonne, y + 8 + i * 9, 0.55F, i == mode ? 0xFFFFD60A : Ios.GRIS, false);
            }
            Ios.texteCentre(g, mc.font, delai, colonne, y + h / 2 - 26, 0.6F, retardateur == 0 ? Ios.GRIS : 0xFFFFD60A, false);
            Ios.disque(g, colonne, y + h / 2, 15, Ios.BLANC);
            Ios.disque(g, colonne, y + h / 2, 13, Ios.NOIR);
            Ios.disque(g, colonne, y + h / 2, 11, Ios.BLANC);
            if (derniere != null) {
                ClientPiaf.dessinerPhoto(g, derniere, colonne - 12, y + h - 26, 24, 14, true);
            }
            Ios.texteCentre(g, mc.font, "clic gauche", colonne, y + h - 8, 0.5F, Ios.GRIS, false);
        }
        if (flash > 0) {
            g.fill(v[0], v[1], v[0] + v[2], v[1] + v[3], (flash * 40) << 24 | 0xFFFFFF);
        }
    }

    private static void declencher() {
        Minecraft mc = Minecraft.getInstance();
        if (retardateur > 0) {
            // le téléphone reste là où il est : on garde la position et l'orientation de l'objectif
            Camera camera = mc.gameRenderer.getMainCamera();
            posePosition = camera.getPosition();
            poseLacet = camera.getYRot();
            poseTangage = camera.getXRot();
            compte = retardateur * 20;
            return;
        }
        prendre(false);
    }

    private static void prendre(boolean depuisLaPose) {
        Minecraft mc = Minecraft.getInstance();
        int[] v = viseur(telephone(mc.getWindow().getGuiScaledWidth(), mc.getWindow().getGuiScaledHeight()));
        double s = mc.getWindow().getGuiScale();
        cadre = new int[] {(int) (v[0] * s), (int) (v[1] * s), (int) (v[2] * s), (int) (v[3] * s)};
        portraitPhoto = portrait();
        guiAvant = mc.options.hideGui;
        mc.options.hideGui = true;
        photoDans = 2;
        if (depuisLaPose && mc.level != null) {
            // l'objectif posé devient la caméra un court instant (le temps que la vue s'y installe)
            objectif = EntityType.MARKER.create(mc.level);
            if (objectif != null) {
                objectif.moveTo(posePosition.x, posePosition.y, posePosition.z, poseLacet, poseTangage);
                vuePose = mc.options.getCameraType();
                mc.options.setCameraType(CameraType.FIRST_PERSON);
                mc.setCameraEntity(objectif);
                photoDans = 8;
            }
        }
    }

    @EventBusSubscriber(modid = Cabane.MODID, value = Dist.CLIENT)
    public static final class Couches {
        @SubscribeEvent
        public static void enregistrer(RegisterGuiLayersEvent event) {
            event.registerAboveAll(Cabane.id("appareil_photo"), (g, delta) -> {
                // pendant la prise de vue, rien ne doit se dessiner par-dessus le monde (sinon la grille finit sur la photo)
                if (actif && Minecraft.getInstance().screen == null && photoDans < 0) {
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

        /** Pendant la visée et la photo, pas de main devant l'objectif. */
        @SubscribeEvent
        public static void cacherMain(RenderHandEvent event) {
            if (actif || objectif != null) {
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
            if (event.isUseItem()) {
                fermer();
            } else if (compte >= 0 || photoDans >= 0) {
                return;
            } else if (event.isAttack()) {
                declencher();
            } else if (event.isPickBlock()) {
                int suivant = DELAIS[0];
                for (int i = 0; i < DELAIS.length; i++) {
                    if (DELAIS[i] == retardateur) {
                        suivant = DELAIS[(i + 1) % DELAIS.length];
                    }
                }
                retardateur = suivant;
                Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK.value(), 1.4F, 0.4F));
            }
        }

        /** La molette fait défiler les modes, comme sur un vrai téléphone. */
        @SubscribeEvent
        public static void molette(InputEvent.MouseScrollingEvent event) {
            Minecraft mc = Minecraft.getInstance();
            if (!actif || mc.screen != null) {
                return;
            }
            event.setCanceled(true);
            if (compte >= 0 || photoDans >= 0) {
                return;
            }
            mode = Math.floorMod(mode + (event.getScrollDeltaY() < 0 ? 1 : -1), MODES.length);
            appliquerVue();
            mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK.value(), 1.8F, 0.3F));
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
                fermer();
            }
            if (compte >= 0 && actif) {
                // le compte à rebours : un bip par seconde, de plus en plus pressé à la fin, et le téléphone qui clignote
                if (compte % 20 == 0 || compte < 20 && compte % 5 == 0) {
                    mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_HAT.value(), compte < 20 ? 2F : 1.4F, 0.6F));
                }
                if (compte % 4 == 0 && mc.level != null) {
                    mc.level.addParticle(ParticleTypes.END_ROD, posePosition.x, posePosition.y - 0.1, posePosition.z, 0, 0.01, 0);
                }
                if (--compte < 0) {
                    prendre(true);
                }
            }
            if (photoDans < 0 || --photoDans > 0) {
                return;
            }
            photoDans = -1;
            int largeur = portraitPhoto ? 180 : 320;
            int hauteur = portraitPhoto ? 320 : 180;
            try (NativeImage ecran = Screenshot.takeScreenshot(mc.getMainRenderTarget());
                 NativeImage photo = new NativeImage(largeur, hauteur, false)) {
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
                if (objectif != null) {
                    mc.setCameraEntity(mc.player);
                    mc.options.setCameraType(vuePose);
                    objectif = null;
                }
            }
        }
    }

    /** Pour l'outil de captures : choisir le mode et le retardateur, puis déclencher. */
    static void regler(int nouveauMode, int secondes) {
        mode = Math.floorMod(nouveauMode, MODES.length);
        retardateur = secondes;
        if (actif) {
            appliquerVue();
        }
    }

    static void declencherPourCaptures() {
        if (actif && compte < 0 && photoDans < 0) {
            declencher();
        }
    }

    private ClientCamera() {
    }
}
