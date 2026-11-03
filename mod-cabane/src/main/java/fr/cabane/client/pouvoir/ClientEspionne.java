package fr.cabane.client.pouvoir;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.math.Axis;
import fr.cabane.Cabane;
import fr.cabane.pouvoir.Espionne;
import fr.cabane.pouvoir.Pouvoirs;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.resources.PlayerSkin;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.RenderArmEvent;
import net.neoforged.neoforge.client.event.RenderHandEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.event.RenderPlayerEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * Les espionnes chez chaque joueur (voir {@link Espionne}) : la combinaison (leur propre tête sur une peau de
 * combinaison verte, rouge ou jaune), le modèle tout fin perché sur des talons, les lunettes sur le nez, les gadgets en
 * main, les rayons, bulles et câbles, la vue infrarouge, les touches et le panneau.
 */
public final class ClientEspionne {
    /** Les espionnes éveillées, et leur couleur (son rang dans Pouvoirs.Couleur). */
    private static final Map<UUID, Integer> ELUES = new HashMap<>();
    private static final Map<UUID, Integer> CHOIX = new HashMap<>();
    private static final Set<UUID> LUNETTES = new HashSet<>();

    /** Un rayon de rouge à lèvres en train de s'éteindre. */
    private record Rayon(Vec3 de, Vec3 a, long debut) {
    }

    private static final List<Rayon> RAYONS = new ArrayList<>();
    /** Les bulles de chewing-gum : l'entité prisonnière → quand la bulle éclate (System.nanoTime). */
    private static final Map<Integer, Long> BULLES = new HashMap<>();
    /** Les grappins accrochés : l'espionne → le point dans le mur. */
    private static final Map<UUID, Vec3> CABLES = new HashMap<>();

    public static final int ORANGE_INFRAROUGE = 0xFF7A1E;
    private static final float RAYON_VIE = 0.35F;

    // ================================================================ ce que dit le serveur

    public static void recevoir(Pouvoirs.Etat p) {
        if (!p.eveille()) {
            oublier(p.joueur());
            return;
        }
        ELUES.put(p.joueur(), p.couleur());
    }

    public static void oublier(UUID joueur) {
        ELUES.remove(joueur);
        LUNETTES.remove(joueur);
        CABLES.remove(joueur);
    }

    public static void choix(Espionne.Choix p) {
        CHOIX.put(p.joueur(), p.gadget());
        if (p.lunettes()) {
            LUNETTES.add(p.joueur());
        } else {
            LUNETTES.remove(p.joueur());
        }
    }

    public static void rayon(Espionne.Rayon p) {
        RAYONS.add(new Rayon(vec(p.de()), vec(p.a()), System.nanoTime()));
    }

    public static void bulle(Espionne.BulleEtat p) {
        BULLES.put(p.entite(), System.nanoTime() + p.ticks() * 50_000_000L);
    }

    public static void cable(Espionne.Cable p) {
        if (p.accroche()) {
            CABLES.put(p.joueur(), vec(p.point()));
        } else {
            CABLES.remove(p.joueur());
        }
    }

    private static Vec3 vec(Vector3f v) {
        return new Vec3(v.x(), v.y(), v.z());
    }

    private static double secondes(long depuis) {
        return (System.nanoTime() - depuis) / 1e9;
    }

    private static boolean moiEveillee() {
        Minecraft mc = Minecraft.getInstance();
        return mc.player != null && ELUES.containsKey(mc.player.getUUID());
    }

    /** Ce joueur est-il une espionne éveillée (en combinaison) ? */
    public static boolean eveillee(Player joueur) {
        return ELUES.containsKey(joueur.getUUID());
    }

    private static int choix(UUID joueur) {
        return Mth.clamp(CHOIX.getOrDefault(joueur, 0), 0, Espionne.GADGETS.length - 1);
    }

    /** Le prénom et la teinte de chaque couleur de combinaison. */
    private static String prenom(int couleur) {
        return couleur == Pouvoirs.Couleur.VERT.ordinal() ? "SAM" : couleur == Pouvoirs.Couleur.JAUNE.ordinal() ? "ALEX" : "CLOVER";
    }

    private static String tenue(int couleur) {
        return couleur == Pouvoirs.Couleur.VERT.ordinal() ? "verte" : couleur == Pouvoirs.Couleur.JAUNE.ordinal() ? "jaune" : "rouge";
    }

    private static int teinte(int couleur) {
        return couleur == Pouvoirs.Couleur.VERT.ordinal() ? 0xFF3AA846 : couleur == Pouvoirs.Couleur.JAUNE.ordinal() ? 0xFFF0C41C : 0xFFD4222A;
    }

    // ================================================================ la combinaison (la peau)

    /** Pendant qu'on lit la vraie peau d'un joueur, on ne la remplace pas. */
    private static boolean brut;
    /** « texture de la vraie peau|couleur » → la peau en combinaison (une texture à nous). */
    private static final Map<String, ResourceLocation> PEAUX = new HashMap<>();
    /** Les peaux qu'on n'a pas pu lire (pas encore chargées) : on réessaie un peu plus tard (System.nanoTime). */
    private static final Map<String, Long> RATEES = new HashMap<>();
    private static final Map<ResourceLocation, PlayerSkin> REMPLACEES = new HashMap<>();
    /** Les combinaisons, lues une fois (64 × 64, en ABGR, sans tête). */
    private static final Map<String, int[]> TENUES = new HashMap<>();

    /** Appelé à chaque {@code getSkin()} : une espionne éveillée porte sa combinaison (corps et bras). */
    public static PlayerSkin peau(AbstractClientPlayer joueur, PlayerSkin skin) {
        if (brut || ELUES.isEmpty()) {
            return skin;
        }
        Integer couleur = ELUES.get(joueur.getUUID());
        ResourceLocation id = couleur == null ? null : PEAUX.get(skin.texture() + "|" + couleur);
        if (id == null) {
            return skin;
        }
        // pas de cape sur la combinaison (celle d'autres mods est enlevée par CapeLayerMixin)
        PlayerSkin s = REMPLACEES.get(id);
        if (s == null || s.model() != skin.model()) {
            s = new PlayerSkin(id, skin.textureUrl(), null, skin.elytraTexture(), skin.model(), skin.secure());
            REMPLACEES.put(id, s);
        }
        return s;
    }

    private static PlayerSkin vraiePeau(AbstractClientPlayer joueur) {
        brut = true;
        try {
            return joueur.getSkin();
        } finally {
            brut = false;
        }
    }

    private static int[] combinaison(String nom) {
        return TENUES.computeIfAbsent(nom, n -> {
            try (var flux = Minecraft.getInstance().getResourceManager().open(Cabane.id("textures/espionnes/tenue_" + n + ".png"));
                    NativeImage image = NativeImage.read(flux)) {
                int[] px = new int[64 * 64];
                for (int i = 0; i < px.length; i++) {
                    px[i] = image.getPixelRGBA(i % 64, i / 64);
                }
                return px;
            } catch (java.io.IOException | RuntimeException e) {
                Cabane.LOGGER.warn("Espionnes : combinaison {} illisible", n, e);
                return null;
            }
        });
    }

    /** La peau en combinaison de ce joueur : sa tête (les deux couches) sur la combinaison de sa couleur. */
    private static void preparer(AbstractClientPlayer joueur, int couleur) {
        PlayerSkin vraie = vraiePeau(joueur);
        String cle = vraie.texture() + "|" + couleur;
        if (PEAUX.containsKey(cle) || System.nanoTime() < RATEES.getOrDefault(cle, 0L)) {
            return;
        }
        int[] tenue = combinaison(tenue(couleur));
        int[] base = ClientMaudit.lire(vraie.texture());
        if (tenue == null || base == null) {
            RATEES.put(cle, System.nanoTime() + 2_000_000_000L);
            return;
        }
        int[] px = tenue.clone();
        // les 16 premières lignes de la peau : la tête et sa deuxième couche
        System.arraycopy(base, 0, px, 0, 64 * 16);
        NativeImage image = new NativeImage(64, 64, true);
        for (int i = 0; i < px.length; i++) {
            image.setPixelRGBA(i % 64, i / 64, px[i]);
        }
        ResourceLocation id = Cabane.id("espionnes/peau_" + PEAUX.size());
        Minecraft.getInstance().getTextureManager().register(id, new DynamicTexture(image));
        PEAUX.put(cle, id);
        RATEES.remove(cle);
    }

    // ================================================================ le modèle tout fin, sur ses talons

    /** Ce qu'on garde de la largeur (x) et de la profondeur (z) du corps, des bras et des jambes (un peu plus épais que les maquettes). */
    private static final float CORPS_X = 0.7F, CORPS_Z = 0.75F, BRAS_X = 0.55F, BRAS_Z = 0.6F, JAMBE_X = 0.6F, JAMBE_Z = 0.7F;
    /** Le milieu des bras et des jambes, en pixels du modèle (au lieu de 6 et 1,9) : contre le corps aminci. */
    private static final float MILIEU_BRAS = 4 * CORPS_X + 2 * BRAS_X, MILIEU_JAMBE = 2 * JAMBE_X + 0.1F;
    /** Les talons soulèvent tout le corps de 2 pixels. */
    private static final float TALON = 2;
    /** Le bras qu'on va dessiner est celui de la première personne (on l'amincit, sans le déplacer). */
    private static boolean premierePersonne;

    /** Après la pose habituelle (PlayerModelMixin) : une espionne éveillée devient toute fine, et les autres redeviennent normaux. */
    public static void poser(PlayerModel<?> m, Player joueur) {
        boolean bras = premierePersonne;
        premierePersonne = false;
        ModelPart[] parties = {m.body, m.jacket, m.rightArm, m.rightSleeve, m.leftArm, m.leftSleeve, m.rightLeg, m.rightPants, m.leftLeg, m.leftPants};
        if (!ELUES.containsKey(joueur.getUUID())) {
            // le même modèle sert à tous les joueurs : on défait ce qu'une espionne y a changé
            for (ModelPart p : parties) {
                p.xScale = p.yScale = p.zScale = 1;
            }
            m.rightLeg.x = m.rightPants.x = -1.9F;
            m.leftLeg.x = m.leftPants.x = 1.9F;
            return;
        }
        boolean fin = joueur instanceof AbstractClientPlayer c && c.getSkin().model() == PlayerSkin.Model.SLIM;
        // le bras (4 pixels de large, ou 3 en modèle fin) n'est pas centré sur son pivot : on garde 1,6 pixel de large
        float decale = fin ? 0.5F : 1F;
        float bx = fin ? BRAS_X * 4 / 3 : BRAS_X;
        m.body.xScale = CORPS_X;
        m.body.zScale = CORPS_Z;
        for (ModelPart b : new ModelPart[] {m.rightArm, m.leftArm}) {
            b.xScale = bx;
            b.zScale = BRAS_Z;
        }
        for (ModelPart j : new ModelPart[] {m.rightLeg, m.leftLeg}) {
            j.xScale = JAMBE_X;
            j.zScale = JAMBE_Z;
        }
        if (!bras) {
            m.rightArm.x = m.rightArm.x * MILIEU_BRAS / 5 + decale * bx;
            m.leftArm.x = m.leftArm.x * MILIEU_BRAS / 5 - decale * bx;
            m.rightArm.z *= MILIEU_BRAS / 5;
            m.leftArm.z *= MILIEU_BRAS / 5;
            m.rightLeg.x = -MILIEU_JAMBE;
            m.leftLeg.x = MILIEU_JAMBE;
            for (ModelPart p : new ModelPart[] {m.head, m.hat, m.body, m.rightArm, m.leftArm, m.rightLeg, m.leftLeg}) {
                p.y -= TALON;
            }
        }
        m.jacket.copyFrom(m.body);
        m.rightSleeve.copyFrom(m.rightArm);
        m.leftSleeve.copyFrom(m.leftArm);
        m.rightPants.copyFrom(m.rightLeg);
        m.leftPants.copyFrom(m.leftLeg);
    }

    /** Les talons (sous l'arrière de chaque botte) et les lunettes, sur une petite texture : argent, verres, monture rose. */
    private static final ResourceLocation ACCESSOIRES = Cabane.id("textures/espionnes/accessoires.png");
    private static ModelPart talon;
    private static ModelPart lunettes;

    private static void modeles() {
        if (talon != null) {
            return;
        }
        MeshDefinition t = new MeshDefinition();
        // dans le repère de la jambe : le bas de la botte est à y = 12, l'arrière vers +z
        t.getRoot().addOrReplaceChild("talon", CubeListBuilder.create()
                .texOffs(0, 0).addBox(-1F, 12F, 0.4F, 2F, 1.6F, 1.5F)
                .texOffs(0, 8).addBox(-1.2F, 13.6F, 0.2F, 2.4F, 0.4F, 1.9F), PartPose.ZERO);
        talon = LayerDefinition.create(t, 64, 32).bakeRoot().getChild("talon");
        MeshDefinition l = new MeshDefinition();
        // dans le repère de la tête : le visage est en z = -4, les yeux vers y = -4
        l.getRoot().addOrReplaceChild("lunettes", CubeListBuilder.create()
                .texOffs(16, 0).addBox(-3.8F, -4.7F, -4.9F, 3.3F, 1.9F, 0.4F)
                .texOffs(16, 0).addBox(0.5F, -4.7F, -4.9F, 3.3F, 1.9F, 0.4F)
                .texOffs(32, 0).addBox(-4.1F, -5.1F, -5F, 8.2F, 0.45F, 0.5F)
                .texOffs(32, 4).addBox(-4.45F, -5F, -4.7F, 0.35F, 0.35F, 4.6F)
                .texOffs(32, 4).addBox(4.1F, -5F, -4.7F, 0.35F, 0.35F, 4.6F), PartPose.ZERO);
        lunettes = LayerDefinition.create(l, 64, 32).bakeRoot().getChild("lunettes");
    }

    public static final class Couche extends RenderLayer<AbstractClientPlayer, PlayerModel<AbstractClientPlayer>> {
        public Couche(RenderLayerParent<AbstractClientPlayer, PlayerModel<AbstractClientPlayer>> parent) {
            super(parent);
        }

        @Override
        public void render(PoseStack ps, MultiBufferSource buffers, int lumiere, AbstractClientPlayer joueur, float marche, float vitesse,
                float partiel, float age, float tete, float tangage) {
            if (!ELUES.containsKey(joueur.getUUID()) || joueur.isInvisible()) {
                return;
            }
            modeles();
            PlayerModel<AbstractClientPlayer> m = getParentModel();
            VertexConsumer c = buffers.getBuffer(RenderType.entityCutoutNoCull(ACCESSOIRES));
            int dessous = LivingEntityRenderer.getOverlayCoords(joueur, 0);
            for (ModelPart jambe : new ModelPart[] {m.rightLeg, m.leftLeg}) {
                ps.pushPose();
                jambe.translateAndRotate(ps);
                talon.render(ps, c, lumiere, dessous);
                ps.popPose();
            }
            if (LUNETTES.contains(joueur.getUUID())) {
                ps.pushPose();
                m.head.translateAndRotate(ps);
                lunettes.render(ps, c, lumiere, dessous);
                ps.popPose();
            }
        }
    }

    // ================================================================ la vue infrarouge

    /** Avec ses lunettes, une espionne voit briller (en orange, à travers les murs) ce qui vit à moins de 48 blocs. */
    public static boolean infrarouge(Entity e) {
        if (LUNETTES.isEmpty()) {
            return false;
        }
        Player moi = Minecraft.getInstance().player;
        return moi != null && e != moi && e instanceof LivingEntity && !e.isSpectator() && LUNETTES.contains(moi.getUUID())
                && ELUES.containsKey(moi.getUUID()) && e.level() == moi.level() && e.distanceToSqr(moi) < 48 * 48;
    }

    // ================================================================ les gadgets en main

    private static final Map<Player, ItemStack[]> TENUS = new HashMap<>();

    private static ItemStack gadgetEnMain(UUID joueur) {
        return new ItemStack(Espionne.gadget(choix(joueur)));
    }

    private static void rendre(Player p) {
        ItemStack[] avant = TENUS.remove(p);
        if (avant != null) {
            p.getInventory().items.set(p.getInventory().selected, avant[0]);
            p.getInventory().offhand.set(0, avant[1]);
        }
    }

    private static void rendre() {
        for (Player p : TENUS.keySet().toArray(new Player[0])) {
            rendre(p);
        }
    }

    // ================================================================ les touches

    /** Quand chaque gadget sera de nouveau prêt chez moi (System.nanoTime) : les 4 du clic droit, le laser, le grappin. */
    private static final long[] PRETS = new long[6];
    private static final int LASER = 4, GRAPPIN = 5;
    /** Un appui sur accroupi plus court que ça (et sans clic pendant) lance le grappin. */
    private static final long APPUI_COURT = 350_000_000L;
    private static boolean gaucheTenu, droitTenu, accroupiAvant, clicAccroupi, sautAvant, sautFait;
    private static long debutAccroupi;
    private static int enLair;

    private static boolean pret(int gadget) {
        return System.nanoTime() >= PRETS[gadget];
    }

    private static void armer(int gadget, int ticks) {
        PRETS[gadget] = System.nanoTime() + ticks * 50_000_000L;
    }

    private static double reste(int gadget) {
        return Math.max(0, (PRETS[gadget] - System.nanoTime()) / 1e9);
    }

    // ================================================================ le panneau et la barre des gadgets

    private static void ecran(GuiGraphics g) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.options.hideGui || !ELUES.containsKey(mc.player.getUUID())) {
            return;
        }
        UUID moi = mc.player.getUUID();
        int couleur = ELUES.get(moi);
        int c = choix(moi);
        boolean lunettesMises = LUNETTES.contains(moi);
        // les lunettes teintent un peu tout en orange
        if (lunettesMises) {
            g.fill(0, 0, g.guiWidth(), g.guiHeight(), 0x22FF5A14);
        }
        String gadget = Espionne.NOMS[c] + (c == Espionne.LUNETTES ? lunettesMises ? " (enlever)" : " (mettre)" : "");
        if (reste(c) > 0) {
            gadget += String.format(" (%.1f s)", reste(c));
        }
        String[][] lignes = {{"Clic gauche", "Laser (rouge à lèvres)"}, {"Clic droit", gadget}, {"Accroupi + clic droit", "Gadget suivant"},
                {"Accroupi (appui court)", "Grappin"}, {"Saut en l'air", "Double saut (talons)"},
                {ClientSusano.EVEIL.getTranslatedKeyMessage().getString(), "Rendormir"}};
        boolean[] gris = {reste(LASER) > 0, reste(c) > 0, false, reste(GRAPPIN) > 0, sautFait, false};
        String titre = "WOOHP  ·  " + prenom(couleur);
        Font font = mc.font;
        int largeurTouche = 0;
        int largeurAction = font.width(titre) - 10;
        for (String[] l : lignes) {
            largeurTouche = Math.max(largeurTouche, font.width(l[0]));
            largeurAction = Math.max(largeurAction, font.width(l[1]));
        }
        int pw = largeurTouche + largeurAction + 26;
        int ph = 22 + lignes.length * 13;
        int x = 6;
        int y = g.guiHeight() / 2 - ph / 2;
        int teinte = teinte(couleur);
        g.fill(x, y, x + pw, y + ph, 0xA0000000);
        g.fill(x, y, x + 2, y + ph, teinte);
        g.drawString(font, titre, x + 8, y + 6, teinte, true);
        for (int i = 0; i < lignes.length; i++) {
            int ly = y + 21 + i * 13;
            g.fill(x + 7, ly - 2, x + 11 + font.width(lignes[i][0]), ly + 9, 0x40FFFFFF);
            g.drawString(font, lignes[i][0], x + 9, ly, 0xFFFFFFFF, false);
            g.drawString(font, lignes[i][1], x + 18 + largeurTouche, ly, gris[i] ? 0xFF7A7A7A : 0xFFEDEDED, true);
        }
        // à droite de la barre d'objets : le rouge à lèvres, puis les quatre gadgets du clic droit (celui choisi entouré)
        int bx = g.guiWidth() / 2 + 98;
        int by = g.guiHeight() - 22;
        caseGadget(g, bx, by, new ItemStack(Espionne.rougeALevres()), false, reste(LASER) / (Espionne.RECHARGE_LASER / 20.0));
        for (int i = 0; i < Espionne.GADGETS.length; i++) {
            caseGadget(g, bx + 26 + i * 21, by, new ItemStack(Espionne.gadget(i)), i == c, reste(i) / (Espionne.RECHARGES[i] / 20.0));
        }
    }

    private static void caseGadget(GuiGraphics g, int x, int y, ItemStack objet, boolean choisi, double recharge) {
        g.fill(x, y, x + 20, y + 20, choisi ? 0xFFFFFFFF : 0xFF5A5866);
        g.fill(x + 1, y + 1, x + 19, y + 19, 0xC0202028);
        g.renderItem(objet, x + 2, y + 2);
        if (recharge > 0) {
            int h = (int) Math.ceil(18 * Math.min(1, recharge));
            g.fill(x + 1, y + 19 - h, x + 19, y + 19, 0x90000000);
        }
    }

    // ================================================================ dessins dans le monde

    private static final class Types extends RenderType {
        /** Les bulles et les rayons : translucides. */
        static final RenderType VOILE = create("cabane_espionne_voile", DefaultVertexFormat.POSITION_COLOR, VertexFormat.Mode.QUADS, 65536,
                false, true, CompositeState.builder().setShaderState(POSITION_COLOR_SHADER).setWriteMaskState(COLOR_WRITE)
                        .setTransparencyState(TRANSLUCENT_TRANSPARENCY).setCullState(NO_CULL).createCompositeState(false));

        private Types() {
            super("cabane_espionne_rien", DefaultVertexFormat.POSITION, VertexFormat.Mode.QUADS, 256, false, false, () -> { }, () -> { });
        }
    }

    /** Un tube de section carrée de a à b (demi-épaisseur e), d'une couleur. */
    private static void tube(VertexConsumer c, Matrix4f m, Vec3 a, Vec3 b, double e, float r, float g, float bl, float al) {
        Vec3 d = b.subtract(a);
        if (d.lengthSqr() < 1.0E-8) {
            return;
        }
        Vec3 u = d.normalize();
        Vec3 x = Math.abs(u.y) < 0.95 ? u.cross(new Vec3(0, 1, 0)).normalize() : u.cross(new Vec3(1, 0, 0)).normalize();
        Vec3 y = u.cross(x).normalize();
        Vec3[] coins = {x.add(y).scale(e), x.subtract(y).scale(e), x.scale(-1).subtract(y).scale(e), y.subtract(x).scale(e)};
        for (int i = 0; i < 4; i++) {
            Vec3 p = coins[i], q = coins[(i + 1) % 4];
            float k = 0.75F + 0.25F * (i % 2);
            sommet(c, m, a.add(p), r * k, g * k, bl * k, al);
            sommet(c, m, a.add(q), r * k, g * k, bl * k, al);
            sommet(c, m, b.add(q), r * k, g * k, bl * k, al);
            sommet(c, m, b.add(p), r * k, g * k, bl * k, al);
        }
    }

    private static void sommet(VertexConsumer c, Matrix4f m, Vec3 p, float r, float g, float b, float a) {
        c.addVertex(m, (float) p.x, (float) p.y, (float) p.z).setColor(r, g, b, a);
    }

    /** Une bulle rose autour de centre (relatif à la caméra), plus opaque sur son bord. */
    private static void sphere(VertexConsumer c, Matrix4f m, Vec3 centre, double rayon) {
        int lat = 14, lon = 22;
        Vec3 versCamera = centre.scale(-1).normalize();
        for (int i = 0; i < lat; i++) {
            for (int j = 0; j < lon; j++) {
                Vec3[] n = {point(i, j, lat, lon), point(i + 1, j, lat, lon), point(i + 1, j + 1, lat, lon), point(i, j + 1, lat, lon)};
                for (Vec3 v : n) {
                    float bord = (float) Math.pow(1 - Math.abs(v.dot(versCamera)), 2.5);
                    sommet(c, m, centre.add(v.scale(rayon)), 1F, 0.45F + 0.3F * bord, 0.75F + 0.1F * bord, 0.18F + 0.45F * bord);
                }
            }
        }
    }

    private static Vec3 point(int i, int j, int lat, int lon) {
        double p = -Math.PI / 2 + Math.PI * i / lat, q = 2 * Math.PI * j / lon;
        return new Vec3(Math.cos(p) * Math.cos(q), Math.sin(p), Math.cos(p) * Math.sin(q));
    }

    // ================================================================ branchements

    @EventBusSubscriber(modid = Cabane.MODID, value = Dist.CLIENT)
    public static final class Couches {
        @SubscribeEvent
        public static void enregistrer(RegisterGuiLayersEvent event) {
            event.registerAboveAll(Cabane.id("espionne"), (g, delta) -> ecran(g));
        }

        /** Les talons et les lunettes se posent sur tous les joueurs (bras larges comme fins). */
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
            rendre();
            if (mc.level == null || mc.player == null) {
                ELUES.clear();
                CHOIX.clear();
                LUNETTES.clear();
                RAYONS.clear();
                BULLES.clear();
                CABLES.clear();
                return;
            }
            UUID moi = mc.player.getUUID();
            boolean eveillee = ELUES.containsKey(moi);
            if (!mc.options.keyAttack.isDown()) {
                gaucheTenu = false;
            }
            if (!mc.options.keyUse.isDown()) {
                droitTenu = false;
            }
            // le grappin : un appui court sur accroupi, sans clic pendant
            boolean accroupi = mc.options.keyShift.isDown();
            long maintenant = System.nanoTime();
            if (accroupi && !accroupiAvant) {
                debutAccroupi = maintenant;
                clicAccroupi = false;
            }
            if (!accroupi && accroupiAvant && eveillee && mc.screen == null && !clicAccroupi && maintenant - debutAccroupi < APPUI_COURT
                    && pret(GRAPPIN)) {
                armer(GRAPPIN, Espionne.RECHARGE_GRAPPIN);
                PacketDistributor.sendToServer(new Espionne.Grappin());
            }
            accroupiAvant = accroupi;
            // les talons : sauter une deuxième fois, en l'air (pas au moment même où l'on quitte le sol)
            boolean saut = mc.options.keyJump.isDown();
            if (mc.player.onGround() || mc.player.isInWater() || mc.player.onClimbable()) {
                enLair = 0;
                sautFait = false;
            } else {
                enLair++;
            }
            if (saut && !sautAvant && eveillee && mc.screen == null && enLair > 1 && !sautFait && !mc.player.getAbilities().flying
                    && !mc.player.isPassenger()) {
                sautFait = true;
                PacketDistributor.sendToServer(new Espionne.Saut());
            }
            sautAvant = saut;
            // la combinaison de chaque espionne qu'on voit
            for (Map.Entry<UUID, Integer> e : ELUES.entrySet()) {
                if (mc.level.getPlayerByUUID(e.getKey()) instanceof AbstractClientPlayer joueur) {
                    preparer(joueur, e.getValue());
                }
            }
            RAYONS.removeIf(r -> secondes(r.debut()) > RAYON_VIE);
            BULLES.values().removeIf(fin -> fin < maintenant);
        }

        /**
         * Éveillée : le clic gauche tire au rouge à lèvres (toujours) ; le clic droit sert le gadget choisi, et accroupi
         * il passe au suivant. Rien d'autre ne passe (ni coup de poing, ni objet utilisé).
         */
        @SubscribeEvent
        public static void clic(InputEvent.InteractionKeyMappingTriggered event) {
            Minecraft mc = Minecraft.getInstance();
            if (!moiEveillee() || !(event.isAttack() || event.isUseItem())) {
                return;
            }
            event.setCanceled(true);
            event.setSwingHand(false);
            boolean accroupi = mc.options.keyShift.isDown();
            if (accroupi) {
                clicAccroupi = true;
            }
            if (event.isAttack()) {
                if (!gaucheTenu && pret(LASER)) {
                    armer(LASER, Espionne.RECHARGE_LASER);
                    event.setSwingHand(true);
                    PacketDistributor.sendToServer(new Espionne.Laser());
                }
                gaucheTenu = true;
                return;
            }
            if (droitTenu) {
                return;
            }
            droitTenu = true;
            UUID moi = mc.player.getUUID();
            if (accroupi) {
                CHOIX.put(moi, (choix(moi) + 1) % Espionne.GADGETS.length);
                mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK.value(), 1.8F, 0.35F));
                PacketDistributor.sendToServer(new Espionne.Suivant());
                return;
            }
            int c = choix(moi);
            if (pret(c)) {
                armer(c, Espionne.RECHARGES[c]);
                event.setSwingHand(true);
                PacketDistributor.sendToServer(new Espionne.Gadget());
            }
        }

        /** Le bras à la première personne : aminci, mais pas déplacé ni soulevé (voir poser). */
        @SubscribeEvent
        public static void bras(RenderArmEvent event) {
            premierePersonne = ELUES.containsKey(event.getPlayer().getUUID());
        }

        /** À la première personne, on tient le gadget choisi, quoi qu'on ait vraiment en main. */
        @SubscribeEvent(priority = EventPriority.LOWEST)
        public static void main(RenderHandEvent event) {
            Minecraft mc = Minecraft.getInstance();
            if (event.getHand() != InteractionHand.MAIN_HAND || mc.player == null || !ELUES.containsKey(mc.player.getUUID())) {
                return;
            }
            event.setCanceled(true);
            // tenu comme n'importe quel objet (ItemInHandRenderer.renderArmWithItem)
            HumanoidArm bras = mc.player.getMainArm();
            int i = bras == HumanoidArm.RIGHT ? 1 : -1;
            float coup = event.getSwingProgress();
            float racine = Mth.sqrt(coup);
            float s = Mth.sin(racine * Mth.PI);
            PoseStack pose = event.getPoseStack();
            pose.pushPose();
            pose.translate(i * -0.4F * s, 0.2F * Mth.sin(racine * Mth.TWO_PI), -0.2F * Mth.sin(coup * Mth.PI));
            pose.translate(i * 0.56F, -0.52F + event.getEquipProgress() * -0.6F, -0.72F);
            pose.mulPose(Axis.YP.rotationDegrees(i * (45F - 20F * Mth.sin(coup * coup * Mth.PI))));
            pose.mulPose(Axis.ZP.rotationDegrees(i * s * -20F));
            pose.mulPose(Axis.XP.rotationDegrees(s * -80F));
            pose.mulPose(Axis.YP.rotationDegrees(i * -45F));
            mc.getEntityRenderDispatcher().getItemInHandRenderer().renderItem(mc.player, gadgetEnMain(mc.player.getUUID()),
                    bras == HumanoidArm.RIGHT ? ItemDisplayContext.FIRST_PERSON_RIGHT_HAND : ItemDisplayContext.FIRST_PERSON_LEFT_HAND,
                    bras == HumanoidArm.LEFT, pose, event.getMultiBufferSource(), event.getPackedLight());
            pose.popPose();
        }

        /** Vue de l'extérieur : le temps de la dessiner, une espionne tient son gadget (main) et son rouge à lèvres (l'autre main). */
        @SubscribeEvent(priority = EventPriority.LOWEST)
        public static void prendre(RenderPlayerEvent.Pre event) {
            Player p = event.getEntity();
            Inventory inv = p.getInventory();
            if (ELUES.containsKey(p.getUUID()) && !TENUS.containsKey(p) && inv.selected >= 0 && inv.selected < inv.items.size()) {
                TENUS.put(p, new ItemStack[] {inv.items.set(inv.selected, gadgetEnMain(p.getUUID())),
                        inv.offhand.set(0, new ItemStack(Espionne.rougeALevres()))});
            }
        }

        @SubscribeEvent
        public static void reposer(RenderPlayerEvent.Post event) {
            rendre(event.getEntity());
        }

        @SubscribeEvent
        public static void dessiner(RenderLevelStageEvent event) {
            if ((RAYONS.isEmpty() && BULLES.isEmpty() && CABLES.isEmpty()) || event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) {
                return;
            }
            Minecraft mc = Minecraft.getInstance();
            Vec3 cam = event.getCamera().getPosition();
            float partiel = event.getPartialTick().getGameTimeDeltaPartialTick(false);
            Matrix4f m = event.getPoseStack().last().pose();
            MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
            // les rayons : un cœur rose clair et un halo rose vif, qui s'éteignent (translucides : en lumière ajoutée, le rose
            // virait au beige sur l'herbe)
            if (!RAYONS.isEmpty()) {
                VertexConsumer c = buffers.getBuffer(Types.VOILE);
                for (Rayon r : RAYONS) {
                    float a = 1 - (float) (secondes(r.debut()) / RAYON_VIE);
                    tube(c, m, r.de().subtract(cam), r.a().subtract(cam), 0.075, 1F, 0.12F, 0.55F, a * 0.45F);
                    tube(c, m, r.de().subtract(cam), r.a().subtract(cam), 0.028, 1F, 0.62F, 0.85F, a);
                }
                buffers.endBatch(Types.VOILE);
            }
            // les bulles de chewing-gum, autour de leur prisonnier
            if (!BULLES.isEmpty()) {
                VertexConsumer c = buffers.getBuffer(Types.VOILE);
                for (Map.Entry<Integer, Long> b : BULLES.entrySet()) {
                    Entity e = mc.level.getEntity(b.getKey());
                    if (e == null) {
                        continue;
                    }
                    double reste = (b.getValue() - System.nanoTime()) / 1e9;
                    double rayon = Math.max(e.getBbWidth(), e.getBbHeight()) * 0.62 + 0.2;
                    // elle gonfle à l'arrivée et tremble juste avant d'éclater
                    rayon *= Math.min(1, (Espionne.DUREE_BULLE / 20.0 - reste) / 0.15 + 0.3) * (1 + (reste < 0.4 ? 0.04 * Math.sin(reste * 60) : 0));
                    Vec3 centre = e.getPosition(partiel).add(0, e.getBbHeight() / 2, 0).subtract(cam);
                    sphere(c, m, centre, rayon);
                }
                buffers.endBatch(Types.VOILE);
            }
            // les câbles des grappins : de la ceinture au crochet planté dans le mur
            if (!CABLES.isEmpty()) {
                VertexConsumer c = buffers.getBuffer(RenderType.debugQuads());
                for (Map.Entry<UUID, Vec3> e : CABLES.entrySet()) {
                    Player joueur = mc.level.getPlayerByUUID(e.getKey());
                    if (joueur == null) {
                        continue;
                    }
                    boolean yeux = joueur == mc.player && mc.options.getCameraType().isFirstPerson();
                    Vec3 ceinture = joueur.getPosition(partiel).add(0, yeux ? 1.1 : 0.88, 0).add(joueur.getLookAngle().multiply(1, 0, 1).scale(0.12));
                    Vec3 point = e.getValue();
                    tube(c, m, ceinture.subtract(cam), point.subtract(cam), 0.02, 0.22F, 0.22F, 0.27F, 1F);
                    Vec3 dir = point.subtract(ceinture).normalize();
                    Vec3 cote = dir.cross(new Vec3(0, 1, 0)).lengthSqr() < 0.01 ? new Vec3(1, 0, 0) : dir.cross(new Vec3(0, 1, 0)).normalize();
                    Vec3 haut = cote.cross(dir).normalize();
                    // le crochet : trois griffes d'argent
                    for (Vec3 griffe : new Vec3[] {cote, cote.scale(-1), haut}) {
                        tube(c, m, point.subtract(dir.scale(0.12)).subtract(cam), point.add(griffe.scale(0.16)).add(dir.scale(0.04)).subtract(cam),
                                0.022, 0.8F, 0.82F, 0.88F, 1F);
                    }
                }
                buffers.endBatch(RenderType.debugQuads());
            }
        }

        private Evenements() {
        }
    }

    private ClientEspionne() {
    }
}
