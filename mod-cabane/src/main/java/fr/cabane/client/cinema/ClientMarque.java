package fr.cabane.client.cinema;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import fr.cabane.Cabane;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
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
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
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
 * La marque des élus, sur l'écran d'un joueur choisi (voir {@link fr.cabane.histoire.Marque}) : une dizaine de secondes
 * à la première personne. Les paupières se ferment, se rouvrent en clignant, le regard descend sur la main tendue, une
 * flamme s'y grave en brûlant (lueur, étincelles, bords rouges, battements de cœur), puis le rendez-vous s'affiche. Les
 * yeux se referment et tout revient comme avant ; le message reste dans le tchat. Le temps suit l'horloge réelle.
 */
public final class ClientMarque {
    private static final ResourceLocation MARQUE = Cabane.id("textures/misc/marque.png");
    private static final String PENSEE = "Ta main. En son centre… quelque chose brûle.";
    /** La flamme prend, le rendez-vous s'affiche, la main disparaît (yeux fermés), et c'est fini. */
    private static final double BRULURE = 2.4;
    private static final double MESSAGE = 5.6;
    private static final double RETOUR = 9.55;
    private static final double FIN = 10.3;
    /** Les paupières (temps, fermeture, 1 = fermées) : on s'assoupit, on cligne, on ouvre ; à la fin on referme. */
    private static final double[][] PAUPIERES = {{0, 0}, {0.45, 1}, {0.9, 1}, {1.2, 0.35}, {1.4, 1}, {1.55, 1},
            {2.05, 0}, {8.95, 0}, {9.35, 1}, {9.75, 1}, {10.25, 0}};
    /** Le cœur : lent au réveil, puis rapide pendant que la main brûle. */
    private static final double[] COEUR;
    private static final double[] CREPITEMENTS = {2.7, 4.1, 5.5, 6.9, 8.2};

    static {
        List<Double> moments = new ArrayList<>(List.of(0.0, 0.95, 1.75, 2.2));
        for (double m = 2.95; m < 8.6; m += 0.68) {
            moments.add(m);
        }
        COEUR = moments.stream().mapToDouble(Double::doubleValue).toArray();
    }

    static boolean actif;
    /** Après le retour, il ne reste que les paupières qui se rouvrent : la main, la caméra et les touches sont rendues. */
    private static boolean revenu;
    private static long debutNanos;
    private static double tPrecedent;
    private static String message = "";
    private static float lacet;
    private static float tangage;
    private static CameraType vueAvant;
    private static boolean guiAvant;
    private static final RandomSource HASARD = RandomSource.create();

    /** Le top départ, envoyé par le serveur aux seuls élus. */
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
        // sans interface, Minecraft ne dessine pas la main : on cache les couches une par une à la place
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

    /** Le regard ne bouge pas pendant la scène (la caméra fait le reste) : au retour, on revoit ce qu'on regardait. */
    private static void figer(LocalPlayer joueur) {
        joueur.setYRot(lacet);
        joueur.setXRot(tangage);
        joueur.yRotO = lacet;
        joueur.xRotO = tangage;
    }

    // ================================================================ le scénario

    static double lisse(double x) {
        x = Mth.clamp(x, 0, 1);
        return x * x * (3 - 2 * x);
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

    /** La main monte devant les yeux pendant qu'ils s'ouvrent. */
    private static float levee(double t) {
        return (float) lisse((t - 0.85) / 1.0);
    }

    /** La flamme : 0 avant, 1 une fois gravée, et elle s'éteint quand les yeux se referment. */
    private static float feu(double t) {
        return (float) (lisse((t - BRULURE) / 0.45) * (1 - lisse((t - 8.95) / 0.6)));
    }

    /** Le coup de chaud quand elle prend : un pic qui retombe. */
    private static float choc(double t) {
        return t < BRULURE ? 0 : (float) Math.exp(-(t - BRULURE) * 2.5);
    }

    private static boolean passe(double moment, double t) {
        return tPrecedent < moment && t >= moment;
    }

    private static void son(SoundEvent son, float volume, float hauteur) {
        Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(son, hauteur, volume));
    }

    private static void sons(double t) {
        for (double m : COEUR) {
            if (passe(m, t)) {
                son(SoundEvents.WARDEN_HEARTBEAT, m < BRULURE ? 1F : 0.7F, m < BRULURE ? 0.9F : 1.15F);
            }
        }
        for (double m : CREPITEMENTS) {
            if (passe(m, t)) {
                son(SoundEvents.FIRE_AMBIENT, 1F, 0.8F);
            }
        }
        if (passe(0.1, t)) {
            son(SoundEvents.AMBIENT_CAVE.value(), 0.7F, 1F);
        }
        if (passe(BRULURE, t)) {
            son(SoundEvents.FIRECHARGE_USE, 1F, 0.6F);
            son(SoundEvents.RESPAWN_ANCHOR_CHARGE, 0.9F, 0.7F);
        }
        if (passe(BRULURE + 0.15, t)) {
            son(SoundEvents.BLAZE_SHOOT, 0.6F, 0.5F);
        }
        if (passe(MESSAGE, t)) {
            son(SoundEvents.BELL_BLOCK, 0.9F, 0.7F);
            son(SoundEvents.BELL_RESONATE, 0.8F, 0.8F);
        }
        if (passe(8.95, t)) {
            son(SoundEvents.FIRE_EXTINGUISH, 0.5F, 0.7F);
        }
    }

    // ================================================================ la main et la flamme

    private static void dessinerMain(PoseStack pose, MultiBufferSource buffers, int lumiere) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer joueur = mc.player;
        double t = ecoule();
        float levee = levee(t);
        if (joueur == null || levee <= 0 || !(mc.getEntityRenderDispatcher().getRenderer(joueur) instanceof PlayerRenderer rendu)) {
            return;
        }
        boolean droite = joueur.getMainArm() == HumanoidArm.RIGHT;
        PlayerModel<AbstractClientPlayer> modele = rendu.getModel();
        ModelPart bras = droite ? modele.rightArm : modele.leftArm;
        ModelPart manche = droite ? modele.rightSleeve : modele.leftSleeve;
        // le milieu du bras en largeur (les bras fins font 3 pixels au lieu de 4)
        float milieu = (droite ? -1 : 1) * (joueur.getSkin().model() == PlayerSkin.Model.SLIM ? 0.5F : 1F) / 16;

        // où est la main (devant les yeux, un peu à droite) et dans quel sens va le bras (de l'épaule vers les doigts)
        float tremble = choc(t) * 0.012F + feu(t) * 0.0015F;
        Vector3f main = new Vector3f(0.12F + 0.18F * (1 - levee), -0.12F - 0.45F * (1 - levee) + (float) Math.sin(t * 1.8) * 0.006F,
                -0.62F + 0.06F * (1 - levee)).add((HASARD.nextFloat() - 0.5F) * tremble, (HASARD.nextFloat() - 0.5F) * tremble, 0);
        Vector3f axe = new Vector3f(-0.75F, 0.5F, -0.5F).normalize();
        // le dos de la main se tourne vers les yeux à mesure qu'elle monte
        Vector3f face = new Vector3f(main).negate();
        face.sub(new Vector3f(axe).mul(face.dot(axe))).normalize();
        float tour = (1 - levee) * 0.9F;
        Vector3f cote = new Vector3f(axe).cross(face);
        face.mul(Mth.cos(tour)).add(cote.mul(Mth.sin(tour)));
        Vector3f z = new Vector3f(face).negate();
        Vector3f x = new Vector3f(axe).cross(z);

        pose.pushPose();
        if (!droite) {
            pose.scale(-1, 1, 1);
        }
        pose.translate(main.x, main.y, main.z);
        pose.mulPose(new Quaternionf().setFromNormalized(new Matrix3f(x, axe, z)));
        pose.translate(-milieu, -8 / 16F, 0);
        PartPose avantBras = bras.storePose();
        PartPose avantManche = manche.storePose();
        boolean brasVu = bras.visible;
        boolean mancheVue = manche.visible;
        bras.loadPose(PartPose.ZERO);
        manche.loadPose(PartPose.ZERO);
        bras.visible = true;
        manche.visible = joueur.isModelPartShown(droite ? PlayerModelPart.RIGHT_SLEEVE : PlayerModelPart.LEFT_SLEEVE);
        ResourceLocation peau = joueur.getSkin().texture();
        bras.render(pose, buffers.getBuffer(RenderType.entityCutoutNoCull(peau)), lumiere, OverlayTexture.NO_OVERLAY);
        manche.render(pose, buffers.getBuffer(RenderType.entityTranslucent(peau)), lumiere, OverlayTexture.NO_OVERLAY);
        bras.loadPose(avantBras);
        manche.loadPose(avantManche);
        bras.visible = brasVu;
        manche.visible = mancheVue;

        // la flamme, sur le dos de la main (juste au-dessus de la manche)
        pose.translate(milieu, 8 / 16F, -2.3F / 16);
        dessinerFlamme(pose.last(), buffers, t);
        pose.popPose();
    }

    private static void dessinerFlamme(PoseStack.Pose p, MultiBufferSource buffers, double t) {
        float feu = feu(t);
        if (feu <= 0.001F) {
            return;
        }
        float choc = choc(t);
        float vacille = 0.88F + 0.12F * (float) (Math.sin(t * 21) * Math.sin(t * 7.7));
        float s = (0.55F + 0.45F * feu) * 1.7F / 16;
        halo(buffers.getBuffer(Types.LUEUR), p, (2.6F + 3F * choc) / 16, Math.min(1, feu * vacille * 0.5F + choc * 0.6F));
        carre(buffers.getBuffer(RenderType.entityTranslucentEmissive(MARQUE)), p, s, 1, 1, 1, feu);
        // chauffée à blanc quand elle prend, puis elle palpite
        float braise = Math.min(1, choc * 1.4F + feu * 0.3F * vacille);
        carre(buffers.getBuffer(Types.BRAISE), p, s, braise, braise * 0.85F, braise * 0.6F, 1);
        etincelles(buffers.getBuffer(Types.LUEUR), p, t, feu);
    }

    private static void carre(VertexConsumer c, PoseStack.Pose p, float s, float r, float g, float b, float a) {
        sommet(c, p, s, s, 0, 0, r, g, b, a);
        sommet(c, p, -s, s, 1, 0, r, g, b, a);
        sommet(c, p, -s, -s, 1, 1, r, g, b, a);
        sommet(c, p, s, -s, 0, 1, r, g, b, a);
    }

    private static void sommet(VertexConsumer c, PoseStack.Pose p, float x, float y, float u, float v, float r, float g, float b, float a) {
        c.addVertex(p, x, y, 0).setColor(r, g, b, a).setUv(u, v).setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(LightTexture.FULL_BRIGHT).setNormal(p, 0, 0, -1);
    }

    /** Une lueur ronde, forte au centre, qui s'efface vers le bord. */
    private static void halo(VertexConsumer c, PoseStack.Pose p, float rayon, float a) {
        int n = 20;
        for (int i = 0; i < n; i++) {
            double a0 = Math.PI * 2 * i / n;
            double a1 = Math.PI * 2 * (i + 1) / n;
            c.addVertex(p, 0, 0, 0).setColor(1F, 0.35F, 0.08F, a);
            c.addVertex(p, 0, 0, 0).setColor(1F, 0.35F, 0.08F, a);
            c.addVertex(p, (float) Math.cos(a0) * rayon, (float) Math.sin(a0) * rayon, 0).setColor(1F, 0.2F, 0.05F, 0F);
            c.addVertex(p, (float) Math.cos(a1) * rayon, (float) Math.sin(a1) * rayon, 0).setColor(1F, 0.2F, 0.05F, 0F);
        }
    }

    /** Des braises qui s'envolent de la marque. */
    private static void etincelles(VertexConsumer c, PoseStack.Pose p, double t, float feu) {
        for (int i = 0; i < 9; i++) {
            double cycle = t * 0.9 + i / 9.0;
            long graine = (long) Math.floor(cycle) * 31 + i;
            float k = (float) (cycle - Math.floor(cycle));
            float x = (hasard(graine, 1) - 0.5F) * 3F / 16 + (float) Math.sin(k * 6 + i) * 0.01F;
            float y = (hasard(graine, 2) - 0.5F) * 3F / 16 + k * 0.1F;
            float z = -k * 0.08F;
            float d = (1 - k) * 0.35F / 16;
            float a = (1 - k) * feu;
            float g = 0.55F + 0.35F * hasard(graine, 3);
            c.addVertex(p, x - d, y - d, z).setColor(1F, g, 0.2F, a);
            c.addVertex(p, x + d, y - d, z).setColor(1F, g, 0.2F, a);
            c.addVertex(p, x + d, y + d, z).setColor(1F, g, 0.2F, a);
            c.addVertex(p, x - d, y + d, z).setColor(1F, g, 0.2F, a);
        }
    }

    private static float hasard(long graine, int k) {
        long n = graine * 0x9E3779B97F4A7C15L + k * 0xC2B2AE3D27D4EB4FL;
        n = (n ^ n >>> 31) * 0xBF58476D1CE4E5B9L;
        return ((n ^ n >>> 29) >>> 40) / (float) (1 << 24);
    }

    /** De la lumière pure : additive, visible des deux côtés, qui ne cache rien (pas d'écriture de profondeur). */
    private static final class Types extends RenderType {
        static final RenderType LUEUR = create("cabane_marque_lueur", DefaultVertexFormat.POSITION_COLOR, VertexFormat.Mode.QUADS, 1024,
                false, true, CompositeState.builder().setShaderState(RENDERTYPE_LIGHTNING_SHADER).setWriteMaskState(COLOR_WRITE)
                        .setTransparencyState(LIGHTNING_TRANSPARENCY).setCullState(NO_CULL).createCompositeState(false));
        /** La flamme ajoutée par-dessus elle-même : ce qui la fait rougeoyer. */
        static final RenderType BRAISE = create("cabane_marque_braise", DefaultVertexFormat.NEW_ENTITY, VertexFormat.Mode.QUADS, 256,
                false, true, CompositeState.builder().setShaderState(RENDERTYPE_EYES_SHADER).setTextureState(new TextureStateShard(MARQUE, false, false))
                        .setTransparencyState(ADDITIVE_TRANSPARENCY).setWriteMaskState(COLOR_WRITE).setCullState(NO_CULL).createCompositeState(false));

        private Types() {
            super("cabane_marque_rien", DefaultVertexFormat.POSITION, VertexFormat.Mode.QUADS, 256, false, false, () -> { }, () -> { });
        }
    }

    // ================================================================ l'écran

    /** Les paupières (0 : ouvertes, 1 : fermées), et l'ombre qu'elles font avant de se fermer. */
    static void paupieres(GuiGraphics g, int w, int h, float fermeture) {
        if (fermeture <= 0.001F) {
            return;
        }
        bords(g, w, h, fermeture * 0.7F, 0, 0, 0);
        int plume = h / 6;
        int bord = Math.round(fermeture * (h / 2F + plume));
        int plein = bord - plume;
        if (plein > 0) {
            g.fill(0, 0, w, plein, 0xFF000000);
            g.fill(0, h - plein, w, h, 0xFF000000);
        }
        g.fillGradient(0, plein, w, bord, 0xFF000000, 0x00000000);
        g.fillGradient(0, h - bord, w, h - plein, 0x00000000, 0xFF000000);
    }

    private static void dessinerEcran(GuiGraphics g) {
        Minecraft mc = Minecraft.getInstance();
        int w = g.guiWidth();
        int h = g.guiHeight();
        double t = ecoule();
        // les bords rougissent quand la main brûle, au rythme du cœur
        float rouge = choc(t) * 0.55F + feu(t) * (0.28F + 0.1F * (float) Math.sin(t * 9.2));
        if (rouge > 0.01F) {
            bords(g, w, h, rouge, 150, 8, 4);
        }
        // l'éclair de chaleur quand elle prend
        float eclair = choc(t);
        if (eclair > 0.02F) {
            g.fill(0, 0, w, h, couleur(eclair * eclair * 0.5F, 255, 190, 120));
        }
        paupieres(g, w, h, fermeture(t));
        float pensee = (float) (lisse((t - 0.25) / 0.5) * (1 - lisse((t - 4.9) / 0.5)));
        if (pensee > 0.02F) {
            g.drawCenteredString(mc.font, Component.literal(PENSEE).withStyle(ChatFormatting.ITALIC), w / 2, h - 34, couleur(pensee, 235, 235, 235));
        }
        float rdv = (float) (lisse((t - MESSAGE) / 0.8) * (1 - lisse((t - 9.8) / 0.45)));
        if (rdv > 0.02F) {
            dessinerMessage(g, mc.font, w, h, rdv);
        }
    }

    /** Le rendez-vous, en grand, en haut de l'écran (la main est en bas) ; la consigne en dessous, plus petite. */
    private static void dessinerMessage(GuiGraphics g, Font font, int w, int h, float a) {
        String[] parties = message.split("\n", 2);
        float echelle = w >= 300 ? 2F : 1.5F;
        float y = h * 0.16F;
        y = texte(g, font, parties[0], w, y, echelle, a, couleur(a, 255, 236, 214));
        if (parties.length > 1) {
            // la consigne arrive un peu après le rendez-vous
            texte(g, font, parties[1], w, y + 6, echelle * 0.65F,a * (float) lisse((ecoule() - MESSAGE - 1.2) / 0.6), couleur(1, 255, 120, 90));
        }
    }

    /** Un texte centré et cerclé de rouge sombre ; renvoie où il s'arrête. */
    static float texte(GuiGraphics g, Font font, String texte, int w, float y, float echelle, float a, int couleur) {
        if (a <= 0.02F) {
            return y;
        }
        List<FormattedCharSequence> lignes = font.split(Component.literal(texte), (int) ((w - 40) / echelle));
        int ombre = couleur(a * 0.55F, 140, 10, 0);
        int teinte = couleur(a, couleur >> 16 & 255, couleur >> 8 & 255, couleur & 255);
        g.pose().pushPose();
        g.pose().translate(w / 2F, y, 0);
        g.pose().scale(echelle, echelle, 1);
        for (int i = 0; i < lignes.size(); i++) {
            FormattedCharSequence ligne = lignes.get(i);
            int x = -font.width(ligne) / 2;
            int yl = i * 11;
            g.drawString(font, ligne, x - 1, yl, ombre, false);
            g.drawString(font, ligne, x + 1, yl, ombre, false);
            g.drawString(font, ligne, x, yl - 1, ombre, false);
            g.drawString(font, ligne, x, yl + 1, ombre, false);
            g.drawString(font, ligne, x, yl, teinte, false);
        }
        g.pose().popPose();
        return y + lignes.size() * 11 * echelle;
    }

    /** Un dégradé sur les quatre bords de l'écran, fort au bord, nul vers le milieu. */
    static void bords(GuiGraphics g, int w, int h, float force, int r, int v, int b) {
        int n = 14;
        int lx = w / 4;
        int ly = h / 4;
        for (int i = 0; i < n; i++) {
            float k = 1 - i / (float) n;
            int c = couleur(Math.min(1, force) * k * k, r, v, b);
            int x0 = lx * i / n;
            int x1 = lx * (i + 1) / n;
            int y0 = ly * i / n;
            int y1 = ly * (i + 1) / n;
            g.fill(x0, 0, x1, h, c);
            g.fill(w - x1, 0, w - x0, h, c);
            g.fill(0, y0, w, y1, c);
            g.fill(0, h - y1, w, h - y0, c);
        }
    }

    static int couleur(float a, int r, int v, int b) {
        return (Math.round(Mth.clamp(a, 0, 1) * 255) << 24) | r << 16 | v << 8 | b;
    }

    // ================================================================ branchements

    @EventBusSubscriber(modid = Cabane.MODID, value = Dist.CLIENT)
    public static final class Couches {
        @SubscribeEvent
        public static void enregistrer(RegisterGuiLayersEvent event) {
            event.registerAboveAll(Cabane.id("marque"), (g, delta) -> {
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
                // dans le tchat de l'élu seulement : le message n'existe que chez ceux qui ont reçu l'animation
                String[] parties = message.split("\n", 2);
                mc.gui.getChat().addMessage(Component.literal(parties[0]).withStyle(ChatFormatting.GOLD, ChatFormatting.ITALIC));
                if (parties.length > 1) {
                    mc.gui.getChat().addMessage(Component.literal(parties[1]).withStyle(ChatFormatting.RED, ChatFormatting.ITALIC));
                }
            }
        }

        /** Le regard descend doucement sur la main ; il tremble quand elle prend feu. */
        @SubscribeEvent
        public static void angles(ViewportEvent.ComputeCameraAngles event) {
            if (!scene()) {
                return;
            }
            double t = ecoule();
            event.setYaw(lacet);
            event.setPitch(Mth.lerp((float) lisse((t - 0.9) / 1.3), tangage, Math.max(tangage, 50F)));
            float secousse = choc(t) * 1.6F;
            if (secousse > 0.01F) {
                event.setRoll(event.getRoll() + (HASARD.nextFloat() - 0.5F) * secousse);
            }
        }

        /** Le monde se resserre un peu autour de la main (pas la main elle-même, qui a son propre champ de vision). */
        @SubscribeEvent
        public static void champ(ViewportEvent.ComputeFov event) {
            if (scene() && event.usedConfiguredFov()) {
                event.setFOV(event.getFOV() * (1 - 0.1 * levee(ecoule())));
            }
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

        /** À la place des mains habituelles (et de ce qu'elles tiennent) : la main tendue, et la marque. */
        @SubscribeEvent
        public static void main(RenderHandEvent event) {
            if (scene()) {
                event.setCanceled(true);
                if (event.getHand() == InteractionHand.MAIN_HAND) {
                    dessinerMain(event.getPoseStack(), event.getMultiBufferSource(), event.getPackedLight());
                }
            }
        }

        @SubscribeEvent
        public static void pasDInterface(RenderGuiLayerEvent.Pre event) {
            if (scene() && !event.getName().equals(Cabane.id("marque"))) {
                event.setCanceled(true);
            }
        }

        private Evenements() {
        }
    }

    private ClientMarque() {
    }
}
