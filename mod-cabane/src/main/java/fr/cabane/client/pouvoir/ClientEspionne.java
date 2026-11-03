package fr.cabane.client.pouvoir;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import fr.cabane.Cabane;
import fr.cabane.pouvoir.Espionne;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.client.Minecraft;
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
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.RenderArmEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * Les espionnes chez chaque joueur (voir {@link Espionne}) : celle qui porte une tenue garde sa tête sur une peau de
 * combinaison verte, rouge ou jaune, avec un modèle plus fin ; les bottes la perchent sur des talons d'argent, les
 * lunettes se voient sur son nez (et lui montrent tout ce qui vit, en orange, à travers les murs). Ici aussi : les
 * rayons, les bulles et les câbles, le double saut des bottes et l'appui court du grappin.
 */
public final class ClientEspionne {
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

    /** Ce joueur porte-t-il une tenue d'espionne ? (sa cape s'efface alors, voir CapeLayerMixin) */
    public static boolean enTenue(Player joueur) {
        return Espionne.tenue(joueur) >= 0;
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

    /** Appelé à chaque {@code getSkin()} : celle qui porte une tenue a sa combinaison (corps et bras). */
    public static PlayerSkin peau(AbstractClientPlayer joueur, PlayerSkin skin) {
        int couleur = brut || PEAUX.isEmpty() ? -1 : Espionne.tenue(joueur);
        ResourceLocation id = couleur < 0 ? null : PEAUX.get(skin.texture() + "|" + couleur);
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
        int[] tenue = combinaison(Espionne.TENUES[couleur]);
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

    // ================================================================ le modèle en tenue, sur ses talons

    /**
     * Ce qu'on garde de la largeur (x) et de la profondeur (z) du corps, des bras et des jambes en tenue (plus épais que les
     * maquettes, et encore un peu plus depuis que la tenue s'enfile).
     */
    private static final float CORPS_X = 0.8F, CORPS_Z = 0.85F, BRAS_X = 0.68F, BRAS_Z = 0.72F, JAMBE_X = 0.72F, JAMBE_Z = 0.8F;
    /** Le milieu des bras et des jambes, en pixels du modèle (au lieu de 6 et 1,9) : contre le corps aminci. */
    private static final float MILIEU_BRAS = 4 * CORPS_X + 2 * BRAS_X, MILIEU_JAMBE = 2 * JAMBE_X + 0.1F;
    /** Les talons soulèvent tout le corps de 2 pixels. */
    private static final float TALON = 2;
    /** Le bras qu'on va dessiner est celui de la première personne (on l'amincit, sans le déplacer). */
    private static boolean premierePersonne;

    /** Après la pose habituelle (PlayerModelMixin) : en tenue, le corps s'affine ; avec les bottes, il monte sur ses talons. */
    public static void poser(PlayerModel<?> m, Player joueur) {
        boolean bras = premierePersonne;
        premierePersonne = false;
        ModelPart[] parties = {m.body, m.jacket, m.rightArm, m.rightSleeve, m.leftArm, m.leftSleeve, m.rightLeg, m.rightPants, m.leftLeg, m.leftPants};
        // le même modèle sert à tous les joueurs : on défait d'abord ce qu'une espionne y a changé
        for (ModelPart p : parties) {
            p.xScale = p.yScale = p.zScale = 1;
        }
        boolean tenue = enTenue(joueur);
        if (tenue) {
            boolean fin = joueur instanceof AbstractClientPlayer c && c.getSkin().model() == PlayerSkin.Model.SLIM;
            // le bras (4 pixels de large, ou 3 en modèle fin) n'est pas centré sur son pivot
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
            }
        } else {
            m.rightLeg.x = -1.9F;
            m.leftLeg.x = 1.9F;
        }
        if (!bras && Espionne.porte(joueur, Espionne.BOTTES)) {
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
            if (joueur.isInvisible()) {
                return;
            }
            boolean bottes = Espionne.porte(joueur, Espionne.BOTTES);
            boolean surLeNez = Espionne.porte(joueur, Espionne.LUNETTES);
            if (!bottes && !surLeNez) {
                return;
            }
            modeles();
            PlayerModel<AbstractClientPlayer> m = getParentModel();
            VertexConsumer c = buffers.getBuffer(RenderType.entityCutoutNoCull(ACCESSOIRES));
            int dessous = LivingEntityRenderer.getOverlayCoords(joueur, 0);
            if (bottes) {
                for (ModelPart jambe : new ModelPart[] {m.rightLeg, m.leftLeg}) {
                    ps.pushPose();
                    jambe.translateAndRotate(ps);
                    talon.render(ps, c, lumiere, dessous);
                    ps.popPose();
                }
            }
            if (surLeNez) {
                ps.pushPose();
                m.head.translateAndRotate(ps);
                lunettes.render(ps, c, lumiere, dessous);
                ps.popPose();
            }
        }
    }

    // ================================================================ la vue infrarouge

    /** Ai-je mes lunettes sur le nez ? (relu à chaque tick : les mixins le demandent pour chaque entité, à chaque image) */
    private static boolean lunettesMises;

    /** Avec ses lunettes, une espionne voit briller (en orange, à travers les murs) ce qui vit à moins de 48 blocs. */
    public static boolean infrarouge(Entity e) {
        if (!lunettesMises) {
            return false;
        }
        Player moi = Minecraft.getInstance().player;
        return moi != null && e != moi && e instanceof LivingEntity && !e.isSpectator() && e.level() == moi.level() && e.distanceToSqr(moi) < 48 * 48;
    }

    // ================================================================ les touches (le grappin, le double saut)

    /** Un appui sur accroupi plus court que ça (et sans clic pendant) lance le grappin. */
    private static final long APPUI_COURT = 350_000_000L;
    private static boolean accroupiAvant, clicAccroupi, sautAvant, sautFait;
    private static long debutAccroupi, grappinPret;
    private static int enLair;

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
        /** Les lunettes teintent un peu tout en orange. */
        @SubscribeEvent
        public static void enregistrer(RegisterGuiLayersEvent event) {
            event.registerAboveAll(Cabane.id("espionne"), (g, delta) -> {
                if (lunettesMises && !Minecraft.getInstance().options.hideGui) {
                    g.fill(0, 0, g.guiWidth(), g.guiHeight(), 0x22FF5A14);
                }
            });
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
            if (mc.level == null || mc.player == null) {
                RAYONS.clear();
                BULLES.clear();
                CABLES.clear();
                lunettesMises = false;
                return;
            }
            lunettesMises = Espionne.porte(mc.player, Espionne.LUNETTES);
            // le grappin (à la ceinture) : un appui court sur accroupi, sans clic pendant
            boolean accroupi = mc.options.keyShift.isDown();
            long maintenant = System.nanoTime();
            if (accroupi && !accroupiAvant) {
                debutAccroupi = maintenant;
                clicAccroupi = false;
            }
            if (accroupi && (mc.options.keyUse.isDown() || mc.options.keyAttack.isDown())) {
                clicAccroupi = true;
            }
            if (!accroupi && accroupiAvant && mc.screen == null && !clicAccroupi && maintenant - debutAccroupi < APPUI_COURT
                    && maintenant >= grappinPret && Espionne.porte(mc.player, Espionne.GRAPPIN)) {
                grappinPret = maintenant + Espionne.RECHARGE_GRAPPIN * 50_000_000L;
                PacketDistributor.sendToServer(new Espionne.Grappin());
            }
            accroupiAvant = accroupi;
            // les bottes : sauter une deuxième fois, en l'air (pas au moment même où l'on quitte le sol)
            boolean saut = mc.options.keyJump.isDown();
            if (mc.player.onGround() || mc.player.isInWater() || mc.player.onClimbable()) {
                enLair = 0;
                sautFait = false;
            } else {
                enLair++;
            }
            if (saut && !sautAvant && mc.screen == null && enLair > 1 && !sautFait && !mc.player.getAbilities().flying
                    && !mc.player.isPassenger() && Espionne.porte(mc.player, Espionne.BOTTES)) {
                sautFait = true;
                PacketDistributor.sendToServer(new Espionne.Saut());
            }
            sautAvant = saut;
            // la combinaison de chaque espionne en tenue qu'on voit
            for (Player p : mc.level.players()) {
                int couleur = Espionne.tenue(p);
                if (couleur >= 0 && p instanceof AbstractClientPlayer joueur) {
                    preparer(joueur, couleur);
                }
            }
            RAYONS.removeIf(r -> secondes(r.debut()) > RAYON_VIE);
            BULLES.values().removeIf(fin -> fin < maintenant);
        }

        /** Le bras à la première personne : aminci en tenue, mais pas déplacé ni soulevé (voir poser). */
        @SubscribeEvent
        public static void bras(RenderArmEvent event) {
            premierePersonne = enTenue(event.getPlayer());
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
