package fr.cabane.client.lutte;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import fr.cabane.Cabane;
import fr.cabane.lutte.Choregraphie.Pose;
import fr.cabane.lutte.Equipement;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.player.Player;
import org.lwjgl.opengl.GL11;

/**
 * Le corps gonflé des catcheurs (le modèle de assets/cabane/catch/modeles.json, exporté des maquettes par
 * outils/catch.mjs) : épaules rondes, pecs, abdos en tablette, biceps, gros mollets, torse nu de la couleur du visage du
 * joueur, et la tenue par-dessus ; le masque sur la tête. Le corps normal du joueur est caché tant qu'il porte la tenue ;
 * ses bras sont un peu plus loin du corps (3,4 pixels) et ses jambes un peu plus écartées (0,5).
 */
public final class CorpsCatcheur {
    private static final String[] OS = {"corps", "brasD", "brasG", "jambeD", "jambeG"};
    /** Par tenue : les parties de chaque os (dans le repère de la partie du joueur). */
    private static final ModelPart[][] TENUES = new ModelPart[Equipement.TENUES.length][];
    private static final ModelPart[] MASQUES = new ModelPart[Equipement.MASQUES.length];
    private static boolean charge;
    /** La pose du geste en cours, lue au début du rendu du joueur (voir ClientLutte) et appliquée dans poser. */
    static float partiel;

    public static boolean enTenue(Player j) {
        return Equipement.tenue(j) >= 0;
    }

    // ------------------------------------------------------------------ le modèle

    private static void charger() {
        if (charge) {
            return;
        }
        charge = true;
        try (var flux = Minecraft.getInstance().getResourceManager().open(Cabane.id("catch/modeles.json"))) {
            JsonObject racine = JsonParser.parseReader(new InputStreamReader(flux, StandardCharsets.UTF_8)).getAsJsonObject();
            JsonObject tenues = racine.getAsJsonObject("tenues"), masques = racine.getAsJsonObject("masques");
            for (int i = 0; i < Equipement.TENUES.length; i++) {
                JsonObject t = tenues.getAsJsonObject(Equipement.TENUES[i]);
                ModelPart r = cuire(t);
                TENUES[i] = new ModelPart[OS.length];
                for (int k = 0; k < OS.length; k++) {
                    TENUES[i][k] = r.hasChild(OS[k]) ? r.getChild(OS[k]) : null;
                }
            }
            for (int i = 0; i < Equipement.MASQUES.length; i++) {
                ModelPart r = cuire(masques.getAsJsonObject(Equipement.MASQUES[i]));
                MASQUES[i] = r.hasChild("tete") ? r.getChild("tete") : null;
            }
        } catch (Exception e) {
            Cabane.LOGGER.warn("Catch : modèle des catcheurs illisible", e);
        }
    }

    /** Les pavés d'un modèle (2 texels par unité : la taille déclarée est la moitié de la vraie texture). */
    private static ModelPart cuire(JsonObject m) {
        MeshDefinition mesh = new MeshDefinition();
        PartDefinition racine = mesh.getRoot();
        Map<String, CubeListBuilder> parOs = new HashMap<>();
        for (JsonElement e : m.getAsJsonArray("cubes")) {
            JsonObject c = e.getAsJsonObject();
            JsonArray de = c.getAsJsonArray("de"), taille = c.getAsJsonArray("taille"), uv = c.getAsJsonArray("uv");
            float gonfle = c.has("gonfle") ? c.get("gonfle").getAsFloat() : 0;
            parOs.computeIfAbsent(c.get("os").getAsString(), k -> CubeListBuilder.create())
                    .texOffs(uv.get(0).getAsInt(), uv.get(1).getAsInt())
                    .addBox(de.get(0).getAsFloat(), de.get(1).getAsFloat(), de.get(2).getAsFloat(),
                            taille.get(0).getAsFloat(), taille.get(1).getAsFloat(), taille.get(2).getAsFloat(), new CubeDeformation(gonfle));
        }
        parOs.forEach((os, b) -> racine.addOrReplaceChild(os, b, PartPose.ZERO));
        JsonArray tex = m.getAsJsonArray("texture");
        return LayerDefinition.create(mesh, tex.get(0).getAsInt() / 2, tex.get(1).getAsInt() / 2).bakeRoot();
    }

    // ------------------------------------------------------------------ la texture : la peau du joueur, les muscles, la tenue

    /** « peau du joueur|tenue » → la texture du corps de ce joueur dans cette tenue. */
    private static final Map<String, ResourceLocation> TEXTURES = new HashMap<>();
    private static final Map<String, Long> RATEES = new HashMap<>();
    private static final Map<String, NativeImage> IMAGES = new HashMap<>();
    private static int compteur;

    /** La texture du corps de ce joueur en tenue (null tant qu'elle n'est pas prête). */
    static ResourceLocation texture(AbstractClientPlayer j, int tenue) {
        return TEXTURES.get(j.getSkin().texture() + "|" + tenue);
    }

    /** Au tick : prépare les textures des joueurs en tenue qu'on voit (on lit leur peau sur la carte graphique). */
    static void preparer(AbstractClientPlayer j, int tenue) {
        ResourceLocation skin = j.getSkin().texture();
        String cle = skin + "|" + tenue;
        if (TEXTURES.containsKey(cle) || System.nanoTime() < RATEES.getOrDefault(cle, 0L)) {
            return;
        }
        String id = Equipement.TENUES[tenue];
        NativeImage ombre = image("textures/entity/catch/corps_" + id + "_ombre.png"), tissu = image("textures/entity/catch/corps_" + id + "_tissu.png");
        int[] peau = lire(skin);
        if (ombre == null || tissu == null) {
            RATEES.put(cle, System.nanoTime() + 5_000_000_000L);
            return;
        }
        float[] teinte = peau == null ? new float[] {200, 150, 112} : teinte(peau);
        if (peau == null) {
            RATEES.put(cle, System.nanoTime() + 2_000_000_000L);
            if (TEXTURES.containsKey(cle + "|attente")) {
                return;
            }
        }
        NativeImage im = new NativeImage(ombre.getWidth(), ombre.getHeight(), true);
        for (int y = 0; y < im.getHeight(); y++) {
            for (int x = 0; x < im.getWidth(); x++) {
                int t = tissu.getPixelRGBA(x, y), o = ombre.getPixelRGBA(x, y);
                if ((t >>> 24) > 0) {
                    im.setPixelRGBA(x, y, t | 0xFF000000);
                } else if ((o >>> 24) > 0) {
                    float k = 1.25F / 255F;
                    int r = octet(teinte[0] * (o & 0xFF) * k), g = octet(teinte[1] * ((o >> 8) & 0xFF) * k), b = octet(teinte[2] * ((o >> 16) & 0xFF) * k);
                    im.setPixelRGBA(x, y, 0xFF000000 | b << 16 | g << 8 | r);
                } else {
                    im.setPixelRGBA(x, y, 0);
                }
            }
        }
        ResourceLocation tex = Cabane.id("catch/corps_" + compteur++);
        Minecraft.getInstance().getTextureManager().register(tex, new DynamicTexture(im));
        if (peau != null) {
            TEXTURES.put(cle, tex);
            RATEES.remove(cle);
            ResourceLocation attente = TEXTURES.remove(cle + "|attente");
            if (attente != null) {
                Minecraft.getInstance().getTextureManager().release(attente);
            }
        } else {
            // en attendant la vraie peau : la couleur par défaut, qu'on refera
            TEXTURES.put(cle + "|attente", tex);
        }
    }

    static ResourceLocation textureOuAttente(AbstractClientPlayer j, int tenue) {
        String cle = j.getSkin().texture() + "|" + tenue;
        ResourceLocation t = TEXTURES.get(cle);
        return t != null ? t : TEXTURES.get(cle + "|attente");
    }

    private static int octet(float v) {
        return Mth.clamp(Math.round(v), 0, 255);
    }

    private static NativeImage image(String chemin) {
        return IMAGES.computeIfAbsent(chemin, c -> {
            try (var flux = Minecraft.getInstance().getResourceManager().open(Cabane.id(c))) {
                return NativeImage.read(flux);
            } catch (Exception e) {
                Cabane.LOGGER.warn("Catch : texture {} illisible", c, e);
                return null;
            }
        });
    }

    /** La couleur de peau : la teinte la plus fréquente du bas du visage (comme les maquettes). */
    private static float[] teinte(int[] px) {
        Map<Integer, float[]> seaux = new HashMap<>();
        for (int y = 11; y < 16; y++) {
            for (int x = 8; x < 16; x++) {
                int c = px[y * 64 + x];
                if ((c >>> 24) < 128) {
                    continue;
                }
                int r = c & 0xFF, g = (c >> 8) & 0xFF, b = (c >> 16) & 0xFF;
                if (0.3 * r + 0.59 * g + 0.11 * b < 50) {
                    continue;
                }
                float[] s = seaux.computeIfAbsent((r >> 4) << 8 | (g >> 4) << 4 | (b >> 4), k -> new float[4]);
                s[0]++;
                s[1] += r;
                s[2] += g;
                s[3] += b;
            }
        }
        float[] mieux = null;
        for (float[] s : seaux.values()) {
            if (mieux == null || s[0] > mieux[0]) {
                mieux = s;
            }
        }
        return mieux == null ? new float[] {200, 150, 112} : new float[] {mieux[1] / mieux[0], mieux[2] / mieux[0], mieux[3] / mieux[0]};
    }

    /** Les pixels d'une peau déjà chargée sur la carte graphique (64 × 64, en ABGR), ou null. */
    private static int[] lire(ResourceLocation texture) {
        try {
            RenderSystem.bindTexture(Minecraft.getInstance().getTextureManager().getTexture(texture).getId());
            if (GlStateManager._getTexLevelParameter(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH) != 64
                    || GlStateManager._getTexLevelParameter(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_HEIGHT) != 64) {
                return null;
            }
            try (NativeImage image = new NativeImage(64, 64, false)) {
                image.downloadTexture(0, false);
                int[] px = new int[64 * 64];
                for (int i = 0; i < px.length; i++) {
                    px[i] = image.getPixelRGBA(i % 64, i / 64);
                }
                return px;
            }
        } catch (RuntimeException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ la pose (PlayerModelMixin)

    private static final float DEG = Mth.DEG_TO_RAD;

    /**
     * Après la pose habituelle : un geste de catch en cours (prise, provoc, au tapis) pose tout le corps ; en tenue, le
     * corps normal se cache (le nôtre est dessiné par {@link Couche}) et les bras s'écartent ; masqué, la deuxième couche
     * de la tête disparaît sous le masque.
     */
    public static void poser(PlayerModel<?> m, Player j) {
        boolean tenue = enTenue(j);
        Pose p = ClientLutte.pose(j, partiel);
        float bx = tenue ? 8.4F : 5F, by = tenue ? 1F : 2F, jx = tenue ? 2.4F : 1.9F;
        if (p != null) {
            float a = p.corps[0] * DEG;
            m.body.xRot = a;
            m.body.yRot = -p.corps[2] * DEG;
            m.body.zRot = -p.corps[1] * DEG;
            m.body.x = 0;
            m.body.y = 12 - 12 * Mth.cos(a);
            m.body.z = -12 * Mth.sin(a);
            os(m.head, 0, m.body.y, m.body.z, a, p.tete);
            os(m.rightArm, -bx, 12 + (by - 12) * Mth.cos(a), (by - 12) * Mth.sin(a), a, p.brasD);
            os(m.leftArm, bx, 12 + (by - 12) * Mth.cos(a), (by - 12) * Mth.sin(a), a, p.brasG);
            os(m.rightLeg, -jx, 12, 0, 0, p.jambeD);
            os(m.leftLeg, jx, 12, 0, 0, p.jambeG);
        } else if (tenue) {
            m.rightArm.x -= 3.4F;
            m.leftArm.x += 3.4F;
            m.rightArm.y -= 1;
            m.leftArm.y -= 1;
            m.rightLeg.x -= 0.5F;
            m.leftLeg.x += 0.5F;
        }
        if (tenue) {
            for (ModelPart part : new ModelPart[] {m.body, m.jacket, m.rightArm, m.rightSleeve, m.leftArm, m.leftSleeve, m.rightLeg, m.rightPants, m.leftLeg, m.leftPants}) {
                part.visible = false;
            }
        }
        if (Equipement.masque(j) >= 0) {
            m.hat.visible = false;
        }
        m.hat.copyFrom(m.head);
        m.jacket.copyFrom(m.body);
        m.rightSleeve.copyFrom(m.rightArm);
        m.leftSleeve.copyFrom(m.leftArm);
        m.rightPants.copyFrom(m.rightLeg);
        m.leftPants.copyFrom(m.leftLeg);
    }

    /** Un os : sa place, la flexion du buste (a, pour la tête et les bras), et ses angles (degrés, sens des maquettes). */
    private static void os(ModelPart o, float x, float y, float z, float a, float[] ang) {
        o.x = x;
        o.y = y;
        o.z = z;
        o.xRot = a + ang[0] * DEG;
        o.zRot = -ang[1] * DEG;
        o.yRot = -ang[2] * DEG;
    }

    // ------------------------------------------------------------------ le dessin

    /** Le corps gonflé (si tenue) et le masque, posés sur les parties du joueur. */
    public static final class Couche extends RenderLayer<AbstractClientPlayer, PlayerModel<AbstractClientPlayer>> {
        public Couche(RenderLayerParent<AbstractClientPlayer, PlayerModel<AbstractClientPlayer>> parent) {
            super(parent);
        }

        @Override
        public void render(PoseStack ps, MultiBufferSource buffers, int lumiere, AbstractClientPlayer j, float marche, float vitesse, float partiel,
                float age, float tete, float tangage) {
            if (j.isInvisible()) {
                return;
            }
            int tenue = Equipement.tenue(j), masque = Equipement.masque(j);
            if (tenue < 0 && masque < 0) {
                return;
            }
            charger();
            PlayerModel<AbstractClientPlayer> m = getParentModel();
            int dessous = LivingEntityRenderer.getOverlayCoords(j, 0);
            if (tenue >= 0 && TENUES[tenue] != null) {
                ResourceLocation tex = textureOuAttente(j, tenue);
                if (tex != null) {
                    VertexConsumer c = buffers.getBuffer(RenderType.entityCutoutNoCull(tex));
                    ModelPart[] parts = {m.body, m.rightArm, m.leftArm, m.rightLeg, m.leftLeg};
                    for (int k = 0; k < OS.length; k++) {
                        if (TENUES[tenue][k] == null) {
                            continue;
                        }
                        ps.pushPose();
                        parts[k].translateAndRotate(ps);
                        TENUES[tenue][k].render(ps, c, lumiere, dessous);
                        ps.popPose();
                    }
                }
            }
            if (masque >= 0 && MASQUES[masque] != null) {
                VertexConsumer c = buffers.getBuffer(RenderType.entityCutoutNoCull(Cabane.id("textures/entity/catch/masque_" + Equipement.MASQUES[masque] + ".png")));
                ps.pushPose();
                m.head.translateAndRotate(ps);
                MASQUES[masque].render(ps, c, lumiere, dessous);
                ps.popPose();
            }
        }
    }

    /** Le bras à la première personne, en tenue : le bras gonflé, torse nu, avec ses poignets. */
    static boolean bras(PoseStack ps, MultiBufferSource buffers, int lumiere, AbstractClientPlayer j, HumanoidArm cote) {
        int tenue = Equipement.tenue(j);
        if (tenue < 0) {
            return false;
        }
        charger();
        ResourceLocation tex = textureOuAttente(j, tenue);
        int k = cote == HumanoidArm.RIGHT ? 1 : 2;
        if (tex == null || TENUES[tenue] == null || TENUES[tenue][k] == null) {
            return false;
        }
        ps.pushPose();
        ps.translate((cote == HumanoidArm.RIGHT ? -5.5F : 5.5F) / 16F, 2 / 16F, 0);
        TENUES[tenue][k].xRot = 0;
        TENUES[tenue][k].render(ps, buffers.getBuffer(RenderType.entityCutoutNoCull(tex)), lumiere, OverlayTexture.NO_OVERLAY);
        ps.popPose();
        return true;
    }

    private CorpsCatcheur() {
    }
}
