package fr.cabane.client.parc;

import com.mojang.blaze3d.platform.NativeImage;
import fr.cabane.Cabane;
import fr.cabane.parc.WagonParc;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import net.minecraft.ChatFormatting;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;

/**
 * La photo souvenir de la Rivière Splash, comme dans les vrais parcs : en bas de la grande descente, l'appareil photo
 * au bord de l'eau prend le tronc (avec tous ceux qui sont dedans). Pour de vrai : pendant un dixième de seconde la vue
 * passe par l'objectif de l'appareil, l'image est gardée, puis le flash. En descendant du tronc, la photo s'affiche
 * (une carte en bas à droite), elle passe sur l'écran du kiosque PHOTOS, et elle est enregistrée dans
 * screenshots/parc pour la garder.
 */
@EventBusSubscriber(modid = Cabane.MODID, value = Dist.CLIENT)
public final class ClientPhoto {
    private static final ResourceLocation TEXTURE = Cabane.id("parc/photo_splash");
    private static final int L = 480, H = 360;
    private static final long CARTE = 10_000;

    private static Entity objectif;
    private static WagonParc tronc;
    private static int dans = -1, images;
    private static boolean guiAvant;
    private static CameraType vueAvant;
    private static DynamicTexture texture;
    private static boolean aMontrer;
    private static long montreeDepuis = -1;
    private static int flash;
    private static String fichier;

    /** La texture de la dernière photo (pour l'écran du kiosque), ou rien. */
    static ResourceLocation texture() {
        return texture == null ? null : TEXTURE;
    }

    /** Le tronc de ce joueur passe devant l'appareil : on regarde par l'objectif le temps de prendre l'image. */
    static void declencher(WagonParc w) {
        Minecraft mc = Minecraft.getInstance();
        if (dans >= 0 || mc.level == null) {
            return;
        }
        tronc = w;
        // une entité qui a des yeux à peu près à la hauteur de ceux du joueur (la caméra garde un moment la hauteur des yeux d'avant)
        objectif = EntityType.ARMOR_STAND.create(mc.level);
        if (objectif == null) {
            return;
        }
        guiAvant = mc.options.hideGui;
        vueAvant = mc.options.getCameraType();
        mc.options.hideGui = true;
        mc.options.setCameraType(CameraType.FIRST_PERSON);
        viser(1F);
        mc.setCameraEntity(objectif);
        dans = 0;
        images = 0;
    }

    /** L'objectif vise le milieu du tronc. */
    private static void viser(float partiel) {
        if (objectif == null || tronc == null) {
            return;
        }
        // l'oeil est un peu devant l'objectif, côté rivière (sinon on ne voit que le boîtier de l'appareil)
        Vec3 o = DecorParc.oeil();
        // on vise un peu devant le tronc, sur le rail : il descend vite, et l'image est prise quelques images plus tard
        fr.cabane.parc.Trajet.Point q = tronc.trajet().a(tronc.distance(partiel) + tronc.vitesse() * 0.1, new fr.cabane.parc.Trajet.Point());
        Vec3 c = new Vec3(q.x + q.ux * 1.1, q.y + q.uy * 1.1, q.z + q.uz * 1.1);
        Vec3 d = c.subtract(o);
        float lacet = (float) Math.toDegrees(Math.atan2(-d.x, d.z));
        float tangage = (float) Math.toDegrees(-Math.atan2(d.y, Math.sqrt(d.x * d.x + d.z * d.z)));
        double y = o.y - 1.7;
        objectif.setPos(o.x, y, o.z);
        objectif.xo = objectif.xOld = o.x;
        objectif.yo = objectif.yOld = y;
        objectif.zo = objectif.zOld = o.z;
        objectif.setYRot(lacet);
        objectif.setXRot(tangage);
        objectif.yRotO = lacet;
        objectif.xRotO = tangage;
    }

    /** On est descendu du tronc : la photo arrive. */
    static void sortie() {
        if (!aMontrer) {
            return;
        }
        aMontrer = false;
        montreeDepuis = System.currentTimeMillis();
        Minecraft mc = Minecraft.getInstance();
        mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.BOOK_PAGE_TURN, 1F, 1F));
        if (mc.player != null && fichier != null) {
            mc.player.displayClientMessage(Component.literal("Ta photo souvenir de la Rivière Splash est gardée dans screenshots/parc (" + fichier + ").")
                    .withStyle(ChatFormatting.AQUA), false);
        }
    }

    /** Un zoom, comme un vrai appareil photo : le tronc remplit l'image. */
    @SubscribeEvent
    public static void zoom(net.neoforged.neoforge.client.event.ViewportEvent.ComputeFov event) {
        if (objectif != null && Minecraft.getInstance().getCameraEntity() == objectif) {
            event.setFOV(44);
        }
    }

    @SubscribeEvent
    public static void image(RenderFrameEvent.Pre event) {
        if (objectif != null) {
            viser(event.getPartialTick().getGameTimeDeltaPartialTick(false));
        }
    }

    /** Le temps de rendre quelques images par l'objectif (sur un PC lent, il peut ne pas y en avoir une par tick). */
    @SubscribeEvent
    public static void tick(ClientTickEvent.Post event) {
        if (flash > 0) {
            flash--;
        }
        if (dans >= 0 && ++dans > 60) {
            // aucune image en 3 secondes : on laisse tomber
            images = 0;
            capturer(false);
        }
    }

    @SubscribeEvent
    public static void apres(RenderFrameEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (objectif != null && mc.getCameraEntity() == objectif && ++images >= 3) {
            capturer(true);
        }
    }

    private static void capturer(boolean prendre) {
        dans = -1;
        images = 0;
        Minecraft mc = Minecraft.getInstance();
        if (!prendre) {
            remettre(mc);
            return;
        }
        try (NativeImage ecran = Screenshot.takeScreenshot(mc.getMainRenderTarget())) {
            int w = ecran.getWidth(), h = ecran.getHeight(), sw = w, sh = w * 3 / 4;
            if (sh > h) {
                sh = h;
                sw = h * 4 / 3;
            }
            NativeImage grande = new NativeImage(L * 2, H * 2, false);
            ecran.resizeSubRectTo((w - sw) / 2, (h - sh) / 2, sw, sh, grande);
            try {
                Path dossier = mc.gameDirectory.toPath().resolve("screenshots").resolve("parc");
                Files.createDirectories(dossier);
                fichier = "splash_" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH.mm.ss")) + ".png";
                grande.writeToFile(dossier.resolve(fichier));
            } catch (Exception e) {
                fichier = null;
                Cabane.LOGGER.warn("Photo du Splash non enregistrée : {}", e.toString());
            }
            NativeImage petite = new NativeImage(L, H, false);
            grande.resizeSubRectTo(0, 0, L * 2, H * 2, petite);
            grande.close();
            if (texture == null) {
                texture = new DynamicTexture(petite);
                mc.getTextureManager().register(TEXTURE, texture);
            } else {
                texture.setPixels(petite);
                texture.upload();
            }
            aMontrer = true;
            mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_CARTOGRAPHY_TABLE_TAKE_RESULT, 1.8F));
            flash = 6;
        } catch (Exception e) {
            Cabane.LOGGER.warn("Photo du Splash ratée : {}", e.toString());
        } finally {
            remettre(mc);
        }
    }

    private static void remettre(Minecraft mc) {
        mc.options.hideGui = guiAvant;
        if (vueAvant != null) {
            mc.options.setCameraType(vueAvant);
        }
        if (mc.player != null) {
            mc.setCameraEntity(mc.player);
        }
        objectif = null;
        tronc = null;
    }

    @SubscribeEvent
    public static void dessiner(RenderGuiEvent.Post event) {
        GuiGraphics g = event.getGuiGraphics();
        int w = g.guiWidth(), h = g.guiHeight();
        if (flash > 0) {
            g.fill(0, 0, w, h, (int) (255 * flash / 6F) << 24 | 0xFFFFFF);
        }
        if (montreeDepuis < 0 || texture == null) {
            return;
        }
        long t = System.currentTimeMillis() - montreeDepuis;
        if (t > CARTE) {
            montreeDepuis = -1;
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        // une carte blanche avec la photo, qui glisse depuis la droite
        int lp = Math.min(200, w / 3), hp = lp * 3 / 4, lc = lp + 12, hc = hp + 26;
        double entree = Math.min(1, t / 350.0), sortie = Math.max(0, (t - (CARTE - 400)) / 400.0);
        int x = (int) (w - 12 - lc + (1 - entree * entree + sortie) * (lc + 20)), y = h - hc - 48;
        g.pose().pushPose();
        g.pose().translate(0, 0, 400);
        g.fill(x - 1, y - 1, x + lc + 1, y + hc + 1, 0x66000000);
        g.fill(x, y, x + lc, y + hc, 0xFFFBF8F0);
        g.blit(TEXTURE, x + 6, y + 6, lp, hp, 0, 0, L, H, L, H);
        g.drawString(mc.font, "Ta photo souvenir", x + 7, y + hp + 12, 0x1C1830, false);
        g.drawString(mc.font, "SPLASH", x + lc - 7 - mc.font.width("SPLASH"), y + hp + 12, 0x1F6FB2, false);
        g.pose().popPose();
    }

    private ClientPhoto() {
    }
}
