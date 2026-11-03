package fr.cabane.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import fr.cabane.Cabane;
import java.io.Reader;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLivingEvent;
import org.joml.Matrix4f;

/**
 * Brokk le Forgeron en orque de guerre. Le PNJ reste celui d'Easy NPC (ses dialogues, sa forge, ses missions) : seul son
 * dessin change. Quand le jeu va le dessiner, on dessine à la place le modèle écrit par outils/brokk.mjs (la maquette
 * validée, docs/brokk), 2,2 fois plus grand qu'un orque, et son nom au-dessus des cornes. Il tourne la tête vers ceux
 * qui passent, respire, marche s'il bouge, et lève le bras droit à chaque coup de marteau sur l'enclume.
 */
@EventBusSubscriber(modid = Cabane.MODID, value = Dist.CLIENT)
public final class ClientBrokk {
    /** Le UUID fixe de Brokk (outils/pnj.mjs, uuid("forgeron")). */
    private static final UUID BROKK = UUIDUtil.uuidFromIntArray(new int[] {572230892, -2072290118, 739222969, -734221735});
    /** La même que l'attribut de taille de son PNJ (outils/pnj.mjs), pour que sa boîte de clic couvre tout son corps. */
    private static final float ECHELLE = 2.2F;
    private static final ResourceLocation TEXTURE = Cabane.id("textures/entity/brokk_orque.png");
    private static final ResourceLocation MODELE = Cabane.id("modeles/brokk.json");

    private static ModelPart racine;
    private static ModelPart tete;
    private static ModelPart brasD;
    private static ModelPart brasG;
    private static ModelPart jambeD;
    private static ModelPart jambeG;
    /** Le haut des cornes, en blocs au-dessus de ses pieds. */
    private static float sommet;
    /** Le modèle n'a pas pu être lu : Brokk garde le dessin d'Easy NPC (on ne réessaie pas à chaque image). */
    private static boolean rate;

    @SubscribeEvent
    public static void dessiner(RenderLivingEvent.Pre<?, ?> event) {
        LivingEntity e = event.getEntity();
        if (!BROKK.equals(e.getUUID()) || !charger()) {
            return;
        }
        event.setCanceled(true);
        float partiel = event.getPartialTick();
        float corps = Mth.rotLerp(partiel, e.yBodyRotO, e.yBodyRot);
        poser(e, partiel, corps);
        PoseStack ps = event.getPoseStack();
        ps.pushPose();
        ps.scale(ECHELLE, ECHELLE, ECHELLE);
        // comme LivingEntityRenderer : tourné dans le sens du corps, retourné, les pieds au sol
        ps.mulPose(Axis.YP.rotationDegrees(180F - corps));
        ps.scale(-1F, -1F, 1F);
        ps.translate(0F, -1.501F, 0F);
        racine.render(ps, event.getMultiBufferSource().getBuffer(RenderType.entityCutoutNoCull(TEXTURE)), event.getPackedLight(),
                LivingEntityRenderer.getOverlayCoords(e, 0F));
        ps.popPose();
        nom(e, ps, event.getMultiBufferSource(), event.getPackedLight());
    }

    /** La pose de cette image : la tête vers ce qu'il regarde, le souffle, la marche, le coup de marteau. */
    private static void poser(LivingEntity e, float partiel, float corps) {
        racine.getAllParts().forEach(ModelPart::resetPose);
        float lacet = Mth.clamp(Mth.wrapDegrees(Mth.rotLerp(partiel, e.yHeadRotO, e.yHeadRot) - corps), -70F, 70F);
        tete.yRot += lacet * Mth.DEG_TO_RAD;
        tete.xRot += Mth.lerp(partiel, e.xRotO, e.getXRot()) * Mth.DEG_TO_RAD;
        float souffle = Mth.cos((e.tickCount + partiel) * 0.09F) * 0.03F + 0.03F;
        brasD.zRot += souffle;
        brasG.zRot -= souffle;
        float pas = Mth.cos(e.walkAnimation.position(partiel) * 0.6662F) * Math.min(1F, e.walkAnimation.speed(partiel));
        jambeD.xRot += pas;
        jambeG.xRot -= pas;
        brasD.xRot -= pas * 0.6F;
        brasG.xRot += pas * 0.6F;
        // le bras se lève devant lui, puis retombe sur l'enclume
        float coup = e.getAttackAnim(partiel);
        if (coup > 0F) {
            ModelPart bras = e.getMainArm() == HumanoidArm.LEFT ? brasG : brasD;
            bras.xRot -= Mth.sin(coup * Mth.PI) * 2.3F;
        }
    }

    /** Son nom, comme au-dessus de n'importe quel PNJ, mais au-dessus de ses cornes. */
    private static void nom(LivingEntity e, PoseStack ps, MultiBufferSource buffers, int lumiere) {
        Minecraft mc = Minecraft.getInstance();
        Component nom = e.getCustomName();
        boolean vise = mc.crosshairPickEntity == e;
        if (nom == null || !Minecraft.renderNames() || !(e.isCustomNameVisible() || vise) || mc.getEntityRenderDispatcher().distanceToSqr(e) > 4096) {
            return;
        }
        ps.pushPose();
        ps.translate(0F, sommet + 0.4F, 0F);
        ps.mulPose(mc.getEntityRenderDispatcher().cameraOrientation());
        ps.scale(0.025F, -0.025F, 0.025F);
        Matrix4f m = ps.last().pose();
        Font font = mc.font;
        float x = -font.width(nom) / 2F;
        int fond = (int) (mc.options.getBackgroundOpacity(0.25F) * 255F) << 24;
        boolean debout = !e.isDiscrete();
        font.drawInBatch(nom, x, 0, 0x20FFFFFF, false, m, buffers, debout ? Font.DisplayMode.SEE_THROUGH : Font.DisplayMode.NORMAL, fond, lumiere);
        if (debout) {
            font.drawInBatch(nom, x, 0, -1, false, m, buffers, Font.DisplayMode.NORMAL, 0, lumiere);
        }
        ps.popPose();
    }

    /** Lit le modèle (une fois) : chaque partie et ses boîtes, telles que outils/brokk.mjs les a rangées dans la texture. */
    private static boolean charger() {
        if (racine != null || rate) {
            return racine != null;
        }
        Optional<Resource> res = Minecraft.getInstance().getResourceManager().getResource(MODELE);
        if (res.isEmpty()) {
            rate = true;
            Cabane.LOGGER.warn("Modèle de Brokk introuvable ({})", MODELE);
            return false;
        }
        try (Reader lecteur = res.get().openAsReader()) {
            JsonObject j = JsonParser.parseReader(lecteur).getAsJsonObject();
            MeshDefinition mesh = new MeshDefinition();
            Map<String, PartDefinition> parties = new HashMap<>();
            for (JsonElement el : j.getAsJsonArray("parties")) {
                JsonObject p = el.getAsJsonObject();
                CubeListBuilder cubes = CubeListBuilder.create();
                for (JsonElement eb : p.getAsJsonArray("boites")) {
                    JsonObject b = eb.getAsJsonObject();
                    float[] uv = nombres(b, "uv"), coin = nombres(b, "coin"), taille = nombres(b, "taille"), gonfle = nombres(b, "gonfle");
                    cubes.texOffs((int) uv[0], (int) uv[1]).addBox(coin[0], coin[1], coin[2], taille[0], taille[1], taille[2],
                            new CubeDeformation(gonfle[0], gonfle[1], gonfle[2]));
                }
                float[] pivot = nombres(p, "pivot"), rot = nombres(p, "rotation");
                PartDefinition parent = p.get("parent").isJsonNull() ? mesh.getRoot() : parties.get(p.get("parent").getAsString());
                String nom = p.get("nom").getAsString();
                parties.put(nom, parent.addOrReplaceChild(nom, cubes, PartPose.offsetAndRotation(pivot[0], pivot[1], pivot[2], rot[0], rot[1], rot[2])));
            }
            float[] texture = nombres(j, "texture");
            ModelPart modele = LayerDefinition.create(mesh, (int) texture[0], (int) texture[1]).bakeRoot();
            tete = modele.getChild("tete");
            brasD = modele.getChild("bras_d");
            brasG = modele.getChild("bras_g");
            jambeD = modele.getChild("jambe_d");
            jambeG = modele.getChild("jambe_g");
            sommet = j.get("sommet").getAsFloat() / 16F * ECHELLE;
            racine = modele;
            return true;
        } catch (Exception ex) {
            rate = true;
            Cabane.LOGGER.warn("Modèle de Brokk illisible : {}", ex.toString());
            return false;
        }
    }

    private static float[] nombres(JsonObject o, String cle) {
        JsonArray a = o.getAsJsonArray(cle);
        float[] f = new float[a.size()];
        for (int i = 0; i < f.length; i++) {
            f[i] = a.get(i).getAsFloat();
        }
        return f;
    }

    private ClientBrokk() {
    }
}
