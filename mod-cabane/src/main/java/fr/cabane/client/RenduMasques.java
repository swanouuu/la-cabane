package fr.cabane.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import fr.cabane.Cabane;
import fr.cabane.accessoire.Masques;
import java.io.Reader;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.HumanoidModel;
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
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import top.theillusivec4.curios.api.SlotContext;
import top.theillusivec4.curios.api.client.CuriosRendererRegistry;
import top.theillusivec4.curios.api.client.ICurioRenderer;

/**
 * Les masques ninja (accessoire/Masques) portés : sur le visage, ou accrochés à la ceinture, sur la hanche gauche. Chaque
 * masque est un modèle écrit par outils/masques.mjs (modeles/masque_&lt;nom&gt;.json, quatre fois plus fin qu'un pixel),
 * lu au premier rendu. Chargé seulement si Curios est là.
 */
final class RenduMasques {
    static void enregistrer() {
        for (String nom : Masques.NOMS) {
            CuriosRendererRegistry.register(BuiltInRegistries.ITEM.get(Masques.id(nom)), () -> new Rendu(nom));
        }
    }

    private static final class Rendu implements ICurioRenderer {
        private final ResourceLocation texture, modele;
        private ModelPart racine;
        private float echelle = 4;
        private boolean rate;

        Rendu(String nom) {
            texture = Cabane.id("textures/entity/masque/" + nom + ".png");
            modele = Cabane.id("modeles/masque_" + nom + ".json");
        }

        @Override
        public <T extends LivingEntity, M extends EntityModel<T>> void render(ItemStack stack, SlotContext slot, PoseStack ps,
                RenderLayerParent<T, M> parent, MultiBufferSource buffers, int lumiere, float marche, float vitesseMarche,
                float partialTicks, float age, float lacetTete, float tangageTete) {
            if (!(parent.getModel() instanceof HumanoidModel<?> m) || !charger()) {
                return;
            }
            LivingEntity e = slot.entity();
            ps.pushPose();
            if ("head".equals(slot.identifier())) {
                m.head.translateAndRotate(ps);
                if (!e.getItemBySlot(EquipmentSlot.HEAD).isEmpty()) {
                    // par-dessus un casque : un peu plus grand, autour du milieu de la tête
                    ps.translate(0, -4 / 16F, 0);
                    ps.scale(1.18F, 1.18F, 1.18F);
                    ps.translate(0, 4 / 16F, 0);
                }
            } else {
                // à la ceinture : accroché sur la hanche gauche, tourné vers l'extérieur et un peu vers l'avant, penché
                m.body.translateAndRotate(ps);
                ps.translate(4.05 / 16F, 10.2 / 16F, 0);
                ps.mulPose(Axis.YP.rotationDegrees(-65));
                ps.mulPose(Axis.ZP.rotationDegrees(-12));
                ps.scale(0.62F, 0.62F, 0.62F);
                // le dos du masque contre la hanche
                ps.translate(0, 4 / 16F, 4.3 / 16F);
            }
            ps.scale(1 / echelle, 1 / echelle, 1 / echelle);
            racine.render(ps, buffers.getBuffer(RenderType.entityCutoutNoCull(texture)), lumiere, LivingEntityRenderer.getOverlayCoords(e, 0));
            ps.popPose();
        }

        /** Lit le modèle (une fois) : chaque partie et ses boîtes, telles que outils/masques.mjs les a rangées dans la texture. */
        private boolean charger() {
            if (racine != null || rate) {
                return racine != null;
            }
            Optional<Resource> res = Minecraft.getInstance().getResourceManager().getResource(modele);
            if (res.isEmpty()) {
                rate = true;
                Cabane.LOGGER.warn("Masque introuvable ({})", modele);
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
                float[] tex = nombres(j, "texture");
                echelle = j.get("echelle").getAsFloat();
                racine = LayerDefinition.create(mesh, (int) tex[0], (int) tex[1]).bakeRoot();
                return true;
            } catch (Exception ex) {
                rate = true;
                Cabane.LOGGER.warn("Masque illisible ({}) : {}", modele, ex.toString());
                return false;
            }
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

    private RenduMasques() {
    }
}
