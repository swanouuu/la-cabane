package fr.cabane.client.cinema;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import fr.cabane.Cabane;
import net.minecraft.ChatFormatting;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.PlayerSkin;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.PlayerModelPart;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.MovementInputUpdateEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.RenderGuiLayerEvent;
import net.neoforged.neoforge.client.event.RenderHandEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;
import org.joml.Matrix3f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * La marque maudite, sur l'écran du joueur choisi (voir {@link fr.cabane.histoire.Marque}), dans le même esprit que la
 * marque du feu ({@link ClientMarque}) : une dizaine de secondes à la première personne. Une douleur le prend, il
 * souffle, cligne des yeux (« Qu'est-ce que c'est que cette douleur… ? ») ; il lève le bras et regarde sa manche : la
 * marque maudite s'y dessine, ses contours rougeoient (« Qu'est-ce que c'est que ça… ? ») ; son autre main arrive et la
 * cache. Puis il se dit qu'il ne devrait pas en parler ; les yeux se ferment et tout revient comme avant (la phrase
 * reste dans son tchat). Le temps suit l'horloge réelle.
 */
public final class ClientMarqueMaudite {
    private static final ResourceLocation MARQUE = Cabane.id("textures/misc/marque_maudite.png");
    private static final ResourceLocation CONTOUR = Cabane.id("textures/misc/marque_maudite_contour.png");
    private static final String DOULEUR = "Qu'est-ce que c'est que cette douleur… ?";
    private static final String QUOI = "Qu'est-ce que c'est que ça… ?";
    /** La marque apparaît, l'autre main la cache, le message, le retour (yeux fermés), la fin. */
    private static final double MARQUE_APPARAIT = 2.5;
    private static final double CACHE = 5.4;
    private static final double MESSAGE = 6.6;
    private static final double RETOUR = 9.9;
    private static final double FIN = 10.7;
    /** Les paupières (temps, fermeture, 1 = fermées) : la douleur, on cligne, on ouvre ; à la fin on referme. */
    private static final double[][] PAUPIERES = {{0, 0}, {0.4, 1}, {0.8, 1}, {1.1, 0.35}, {1.4, 1}, {1.55, 1}, {2.05, 0},
            {9.3, 0}, {9.7, 1}, {10.1, 1}, {10.6, 0}};
    /** Où la marque est sur l'avant-bras (en pixels de peau, depuis l'épaule) : près du poignet, au bout de la manche. */
    private static final float HAUTEUR_MARQUE = 5.5F;

    private static boolean actif;
    private static boolean revenu;
    private static long debutNanos;
    private static double tPrecedent;
    private static String message = "";
    private static float lacet;
    private static float tangage;
    private static CameraType vueAvant;
    private static boolean guiAvant;
    private static final RandomSource HASARD = RandomSource.create();

    /** Le top départ, envoyé par le serveur au seul marqué. */
    public static void demarrer(String texte) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            return;
        }
        if (!scene()) {
            vueAvant = mc.options.getCameraType();
            guiAvant = mc.options.hideGui;
            lacet = mc.player.getYRot();
            tangage = mc.player.getXRot();
        }
        message = texte;
        actif = true;
        revenu = false;
        debutNanos = System.nanoTime();
        tPrecedent = -1;
        mc.setScreen(null);
        // sans interface, Minecraft ne dessine pas les mains : on cache les couches une par une à la place
        mc.options.hideGui = false;
        mc.options.setCameraType(CameraType.FIRST_PERSON);
    }

    private static boolean scene() {
        return actif && !revenu;
    }

    private static double ecoule() {
        return (System.nanoTime() - debutNanos) / 1e9;
    }

    private static void revenir(Minecraft mc) {
        revenu = true;
        mc.options.hideGui = guiAvant;
        if (vueAvant != null) {
            mc.options.setCameraType(vueAvant);
        }
        figer(mc.player);
    }

    private static void figer(LocalPlayer joueur) {
        joueur.setYRot(lacet);
        joueur.setXRot(tangage);
        joueur.yRotO = lacet;
        joueur.xRotO = tangage;
    }

    // ================================================================ le scénario

    private static double lisse(double x) {
        return ClientMarque.lisse(x);
    }

    private static float fermeture(double t) {
        for (int i = 1; i < PAUPIERES.length; i++) {
            if (t < PAUPIERES[i][0]) {
                double[] a = PAUPIERES[i - 1];
                double[] b = PAUPIERES[i];
                return (float) Mth.lerp(lisse((t - a[0]) / (b[0] - a[0])), a[1], b[1]);
            }
        }
        return 0;
    }

    /** Le bras monte devant les yeux pendant qu'ils s'ouvrent. */
    private static float levee(double t) {
        return (float) lisse((t - 0.85) / 1.0);
    }

    /** La marque se dessine sur la peau. */
    private static float marque(double t) {
        return (float) lisse((t - MARQUE_APPARAIT) / 0.8);
    }

    /** L'autre main arrive par la gauche et se pose sur la marque. */
    private static float cache(double t) {
        return (float) lisse((t - CACHE) / 0.8);
    }

    /** La douleur : un pic au début, un autre quand la marque apparaît. */
    private static float douleur(double t) {
        float d = t >= 0.05 ? (float) Math.exp(-(t - 0.05) * 2.2) : 0;
        return Math.max(d, t >= MARQUE_APPARAIT ? 0.7F * (float) Math.exp(-(t - MARQUE_APPARAIT) * 2) : 0);
    }

    private static boolean passe(double moment, double t) {
        return tPrecedent < moment && t >= moment;
    }

    private static void son(SoundEvent son, float volume, float hauteur) {
        Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(son, hauteur, volume));
    }

    /** Peu de sons : la douleur, deux souffles, la marque qui apparaît. */
    private static void sons(double t) {
        if (passe(0.05, t)) {
            son(SoundEvents.PLAYER_HURT, 0.5F, 0.75F);
        }
        if (passe(0.7, t) || passe(4.2, t)) {
            son(SoundEvents.PLAYER_BREATH, 0.5F, 0.85F);
        }
        if (passe(MARQUE_APPARAIT, t)) {
            son(SoundEvents.WARDEN_HEARTBEAT, 0.5F, 0.8F);
            son(SoundEvents.FIRE_EXTINGUISH, 0.3F, 0.5F);
        }
    }

    // ================================================================ les bras et la marque

    /**
     * Le bras droit tendu devant les yeux, comme pour la marque du feu, avec la marque maudite près du poignet. Puis le
     * bras gauche, qui arrive par la gauche, et dont la main vient se poser sur la marque.
     */
    private static void dessinerBras(PoseStack pose, MultiBufferSource buffers, int lumiere) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer joueur = mc.player;
        double t = ecoule();
        float levee = levee(t);
        if (joueur == null || levee <= 0 || !(mc.getEntityRenderDispatcher().getRenderer(joueur) instanceof PlayerRenderer rendu)) {
            return;
        }
        PlayerModel<AbstractClientPlayer> modele = rendu.getModel();
        float tremble = douleur(t) * 0.01F;
        Vector3f main = new Vector3f(0.12F + 0.18F * (1 - levee), -0.12F - 0.45F * (1 - levee) + (float) Math.sin(t * 2.6) * 0.006F,
                -0.62F + 0.06F * (1 - levee)).add((HASARD.nextFloat() - 0.5F) * tremble, (HASARD.nextFloat() - 0.5F) * tremble, 0);
        Vector3f axe = new Vector3f(-0.75F, 0.5F, -0.5F).normalize();
        Vector3f face = vers(main, axe);
        bras(pose, buffers, lumiere, joueur, modele, false, main, axe);

        // la marque, sur la manche, près du poignet
        float m = marque(t);
        if (m > 0.01F) {
            float milieu = milieu(joueur, false);
            pose.pushPose();
            placer(pose, main, axe, face, milieu);
            pose.translate(milieu, HAUTEUR_MARQUE / 16, -2.3F / 16);
            // petite : elle tient largement sur la largeur du bras (anneau compris)
            float s = (0.7F + 0.3F * m) * 0.9F / 16;
            float palpite = 0.75F + 0.25F * (float) Math.sin(t * 6);
            carre(buffers.getBuffer(RenderType.entityTranslucentEmissive(CONTOUR)), pose.last(), s, 0, m * palpite, LightTexture.FULL_BRIGHT);
            carre(buffers.getBuffer(RenderType.entityTranslucent(MARQUE)), pose.last(), s, -0.002F, m, lumiere);
            pose.popPose();
        }

        // l'autre main vient la cacher : sa main se pose juste devant la marque
        float k = cache(t);
        if (k > 0.01F) {
            Vector3f marque = new Vector3f(main).add(new Vector3f(axe).mul(-(8 - HAUTEUR_MARQUE) / 16)).add(new Vector3f(face).mul(0.2F));
            Vector3f dehors = new Vector3f(-0.75F, -0.6F, -0.45F);
            Vector3f mainGauche = new Vector3f(dehors).lerp(marque, k);
            Vector3f axeGauche = new Vector3f(0.7F, 0.42F, -0.55F).normalize();
            bras(pose, buffers, lumiere, joueur, modele, true, mainGauche, axeGauche);
        }
    }

    /** Le milieu du bras en largeur, depuis son pivot (les bras fins font 3 pixels au lieu de 4), en blocs. */
    private static float milieu(LocalPlayer joueur, boolean gauche) {
        return (gauche ? 1 : -1) * (joueur.getSkin().model() == PlayerSkin.Model.SLIM ? 0.5F : 1F) / 16;
    }

    /** La face du bras qui regarde les yeux : de la main vers la caméra, à angle droit avec le bras. */
    private static Vector3f vers(Vector3f main, Vector3f axe) {
        Vector3f face = new Vector3f(main).negate();
        return face.sub(new Vector3f(axe).mul(face.dot(axe))).normalize();
    }

    /** Se place dans le repère du bras : la main en {@code main}, le bras le long d'{@code axe}, une face vers les yeux. */
    private static void placer(PoseStack pose, Vector3f main, Vector3f axe, Vector3f face, float milieu) {
        Vector3f z = new Vector3f(face).negate();
        Vector3f x = new Vector3f(axe).cross(z);
        pose.translate(main.x, main.y, main.z);
        pose.mulPose(new Quaternionf().setFromNormalized(new Matrix3f(x, axe, z)));
        pose.translate(-milieu, -8 / 16F, 0);
    }

    private static void bras(PoseStack pose, MultiBufferSource buffers, int lumiere, LocalPlayer joueur, PlayerModel<AbstractClientPlayer> modele,
                             boolean gauche, Vector3f main, Vector3f axe) {
        ModelPart bras = gauche ? modele.leftArm : modele.rightArm;
        ModelPart manche = gauche ? modele.leftSleeve : modele.rightSleeve;
        float milieu = milieu(joueur, gauche);
        pose.pushPose();
        placer(pose, main, axe, vers(main, axe), milieu);
        PartPose avantBras = bras.storePose();
        PartPose avantManche = manche.storePose();
        boolean brasVu = bras.visible;
        boolean mancheVue = manche.visible;
        bras.loadPose(PartPose.ZERO);
        manche.loadPose(PartPose.ZERO);
        bras.visible = true;
        manche.visible = joueur.isModelPartShown(gauche ? PlayerModelPart.LEFT_SLEEVE : PlayerModelPart.RIGHT_SLEEVE);
        ResourceLocation peau = joueur.getSkin().texture();
        bras.render(pose, buffers.getBuffer(RenderType.entityCutoutNoCull(peau)), lumiere, OverlayTexture.NO_OVERLAY);
        manche.render(pose, buffers.getBuffer(RenderType.entityTranslucent(peau)), lumiere, OverlayTexture.NO_OVERLAY);
        bras.loadPose(avantBras);
        manche.loadPose(avantManche);
        bras.visible = brasVu;
        manche.visible = mancheVue;
        pose.popPose();
    }

    private static void carre(VertexConsumer c, PoseStack.Pose p, float s, float z, float a, int lumiere) {
        sommet(c, p, s, s, z, 0, 0, a, lumiere);
        sommet(c, p, -s, s, z, 1, 0, a, lumiere);
        sommet(c, p, -s, -s, z, 1, 1, a, lumiere);
        sommet(c, p, s, -s, z, 0, 1, a, lumiere);
    }

    private static void sommet(VertexConsumer c, PoseStack.Pose p, float x, float y, float z, float u, float v, float a, int lumiere) {
        c.addVertex(p, x, y, z).setColor(1F, 1F, 1F, a).setUv(u, v).setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(lumiere).setNormal(p, 0, 0, -1);
    }

    // ================================================================ l'écran

    private static void dessinerEcran(GuiGraphics g) {
        Minecraft mc = Minecraft.getInstance();
        int w = g.guiWidth();
        int h = g.guiHeight();
        double t = ecoule();
        float rouge = douleur(t) * 0.55F;
        if (scene() && rouge > 0.01F) {
            ClientMarque.bords(g, w, h, rouge, 120, 6, 10);
        }
        ClientMarque.paupieres(g, w, h, fermeture(t));
        float douleur = (float) (lisse((t - 0.5) / 0.5) * (1 - lisse((t - 2.4) / 0.4)));
        ClientMarque.texte(g, mc.font, DOULEUR, w, h - 46, 1.3F, douleur, ClientMarque.couleur(1, 240, 230, 230));
        float quoi = (float) (lisse((t - 3.5) / 0.5) * (1 - lisse((t - 5.6) / 0.5)));
        ClientMarque.texte(g, mc.font, QUOI, w, h - 46, 1.3F, quoi, ClientMarque.couleur(1, 240, 230, 230));
        float silence = (float) (lisse((t - MESSAGE) / 0.8) * (1 - lisse((t - 9.6) / 0.45)));
        ClientMarque.texte(g, mc.font, message, w, h * 0.16F, w >= 300 ? 2F : 1.5F, silence, ClientMarque.couleur(1, 235, 225, 235));
    }

    // ================================================================ branchements

    @EventBusSubscriber(modid = Cabane.MODID, value = Dist.CLIENT)
    public static final class Couches {
        @SubscribeEvent
        public static void enregistrer(RegisterGuiLayersEvent event) {
            event.registerAboveAll(Cabane.id("marque_maudite"), (g, delta) -> {
                if (actif) {
                    dessinerEcran(g);
                }
            });
        }

        private Couches() {
        }
    }

    @EventBusSubscriber(modid = Cabane.MODID, value = Dist.CLIENT)
    public static final class Evenements {
        @SubscribeEvent
        public static void tick(ClientTickEvent.Post event) {
            if (!actif) {
                return;
            }
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null || mc.level == null) {
                actif = false;
                return;
            }
            double t = ecoule();
            sons(t);
            tPrecedent = t;
            if (!revenu) {
                if (t >= RETOUR || !mc.player.isAlive()) {
                    revenir(mc);
                } else {
                    mc.options.hideGui = false;
                    mc.options.setCameraType(CameraType.FIRST_PERSON);
                    figer(mc.player);
                }
            }
            if (t >= FIN) {
                actif = false;
                mc.gui.getChat().addMessage(Component.literal(message).withStyle(ChatFormatting.DARK_RED, ChatFormatting.ITALIC));
            }
        }

        /** Il souffle fort, le regard descend sur son bras ; l'image tremble avec la douleur. */
        @SubscribeEvent
        public static void angles(ViewportEvent.ComputeCameraAngles event) {
            if (!scene()) {
                return;
            }
            double t = ecoule();
            event.setYaw(lacet);
            event.setPitch(Mth.lerp((float) lisse((t - 0.9) / 1.3), tangage, Math.max(tangage, 50F)) + (float) Math.sin(t * 2.6) * 1.4F);
            event.setRoll(event.getRoll() + (HASARD.nextFloat() - 0.5F) * douleur(t) * 2.2F);
        }

        @SubscribeEvent
        public static void immobile(MovementInputUpdateEvent event) {
            if (scene()) {
                event.getInput().leftImpulse = 0;
                event.getInput().forwardImpulse = 0;
                event.getInput().jumping = false;
                event.getInput().shiftKeyDown = false;
                event.getInput().up = false;
                event.getInput().down = false;
                event.getInput().left = false;
                event.getInput().right = false;
            }
        }

        @SubscribeEvent
        public static void pasDeClic(InputEvent.InteractionKeyMappingTriggered event) {
            if (scene()) {
                event.setCanceled(true);
                event.setSwingHand(false);
            }
        }

        /** À la place des mains habituelles (et de ce qu'elles tiennent) : le bras marqué, puis l'autre main qui le cache. */
        @SubscribeEvent
        public static void mains(RenderHandEvent event) {
            if (scene()) {
                event.setCanceled(true);
                if (event.getHand() == InteractionHand.MAIN_HAND) {
                    dessinerBras(event.getPoseStack(), event.getMultiBufferSource(), event.getPackedLight());
                }
            }
        }

        @SubscribeEvent
        public static void pasDInterface(RenderGuiLayerEvent.Pre event) {
            if (scene() && !event.getName().equals(Cabane.id("marque_maudite"))) {
                event.setCanceled(true);
            }
        }

        private Evenements() {
        }
    }

    private ClientMarqueMaudite() {
    }
}
