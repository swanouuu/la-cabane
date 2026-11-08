package fr.cabane.client.titans;

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
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * Le dessin d'un titan, partagé par le mob (RenduTitan) et la cinématique de l'attaque (ClientTitans) : le modèle écrit
 * par outils/titans.mjs (le rendu validé, docs/titans), lu une fois par titan, dessiné 4 fois plus grand (un pixel de
 * titan = 1/4 de bloc), dans la pose demandée.
 */
public final class ModeleTitan {
    /** Un pixel du modèle vaut 4 pixels de Minecraft. */
    public static final float ECHELLE = 4F;

    /**
     * La pose d'une image : le pas (la phase de la marche) et son ampleur (0 : immobile, 1 : grandes enjambées), la tête
     * (lacet et tangage en radians, penche : la tête qui bascule sur le côté), le coup (0 à 1 : le bras droit se lève puis
     * s'abat), la mâchoire (0 à 1), les deux bras levés (0 à 1, pour courir en battant des bras), la chute (0 à 1 : il
     * tombe sur le côté en mourant).
     */
    public record Pose(float pas, float ampleur, float teteLacet, float teteTangage, float penche, float coup, float machoire, float brasLeves, float chute) {
        public static final Pose REPOS = new Pose(0, 0, 0, 0, 0, 0, 0, 0, 0);
    }

    private record Modele(ModelPart racine, ModelPart tete, ModelPart torse, ModelPart brasD, ModelPart brasG, ModelPart jambeD, ModelPart jambeG,
            ModelPart machoire, float sommet, ResourceLocation texture) {
    }

    private static final Map<String, Modele> MODELES = new HashMap<>();

    /** Le haut de la tête, en blocs au-dessus des pieds (0 si le modèle manque). */
    public static float sommet(String id) {
        Modele m = charger(id);
        return m == null ? 0 : m.sommet();
    }

    public static ResourceLocation texture(String id) {
        return Cabane.id("textures/entity/titan/" + id + ".png");
    }

    /**
     * Dessine le titan {@code id} les pieds en {@code pieds} (relatif à la caméra, ou 0 dans un rendu d'entité), tourné vers
     * {@code lacet} (en degrés, comme le corps d'une entité).
     */
    public static void dessiner(String id, PoseStack ps, MultiBufferSource buffers, Vec3 pieds, float lacet, Pose p, int lumiere, int overlay) {
        Modele m = charger(id);
        if (m == null) {
            return;
        }
        poser(m, p);
        ps.pushPose();
        ps.translate(pieds.x, pieds.y, pieds.z);
        ps.mulPose(Axis.YP.rotationDegrees(180F - lacet));
        if (p.chute() > 0) {
            // il bascule sur le côté, d'un bloc, comme un arbre qu'on abat
            float k = Mth.clamp(p.chute(), 0, 1);
            ps.mulPose(Axis.ZP.rotationDegrees(k * k * 88F));
        }
        ps.scale(ECHELLE, ECHELLE, ECHELLE);
        ps.scale(-1F, -1F, 1F);
        ps.translate(0F, -1.501F, 0F);
        m.racine().render(ps, buffers.getBuffer(RenderType.entityCutoutNoCull(m.texture())), lumiere, overlay);
        ps.popPose();
    }

    private static void poser(Modele m, Pose p) {
        m.racine().getAllParts().forEach(ModelPart::resetPose);
        float pas = Mth.sin(p.pas()) * p.ampleur();
        m.jambeD().xRot += pas * 0.55F;
        m.jambeG().xRot -= pas * 0.55F;
        m.brasD().xRot -= pas * 0.45F;
        m.brasG().xRot += pas * 0.45F;
        // un balancement léger même à l'arrêt : ils ne tiennent jamais tout à fait en place
        float souffle = Mth.sin(p.pas() * 0.37F + 1.3F) * 0.04F;
        m.brasD().zRot += souffle;
        m.brasG().zRot -= souffle;
        m.torse().zRot += pas * 0.05F;
        if (p.brasLeves() > 0) {
            // les bras qui battent en l'air
            float k = p.brasLeves();
            m.brasD().xRot -= k * (2.3F + Mth.sin(p.pas() * 1.7F) * 0.5F);
            m.brasG().xRot -= k * (2.3F - Mth.sin(p.pas() * 1.7F) * 0.5F);
            m.brasD().zRot += k * 0.45F;
            m.brasG().zRot -= k * 0.45F;
        }
        if (p.coup() > 0) {
            // il lève le bras droit au-dessus de la tête, puis l'abat devant lui
            float c = p.coup();
            float leve = c < 0.6F ? c / 0.6F : 1 - (c - 0.6F) / 0.4F * 1.6F;
            m.brasD().xRot -= Mth.clamp(leve, -0.6F, 1F) * 2.9F;
            m.torse().xRot += c > 0.6F ? 0.25F * Mth.sin((c - 0.6F) / 0.4F * Mth.PI) : 0;
        }
        m.tete().yRot += p.teteLacet();
        m.tete().xRot += p.teteTangage();
        m.tete().zRot += p.penche();
        if (m.machoire() != null) {
            m.machoire().xRot += p.machoire() * 0.35F;
        }
    }

    /** Lit le modèle de ce titan (une fois) : chaque partie et ses boîtes, telles que outils/titans.mjs les a rangées dans sa texture. */
    private static Modele charger(String id) {
        if (MODELES.containsKey(id)) {
            return MODELES.get(id);
        }
        Modele m = null;
        ResourceLocation fichier = Cabane.id("modeles/titan_" + id + ".json");
        Optional<Resource> res = Minecraft.getInstance().getResourceManager().getResource(fichier);
        if (res.isEmpty()) {
            Cabane.LOGGER.warn("Modèle de titan introuvable ({})", fichier);
        } else {
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
                ModelPart racine = LayerDefinition.create(mesh, (int) texture[0], (int) texture[1]).bakeRoot();
                ModelPart bassin = racine.getChild("bassin");
                ModelPart torse = bassin.getChild("torse");
                ModelPart tete = torse.getChild("tete");
                m = new Modele(racine, tete, torse, torse.getChild("bras_d"), torse.getChild("bras_g"), racine.getChild("jambe_d"), racine.getChild("jambe_g"),
                        tete.hasChild("machoire") ? tete.getChild("machoire") : null, j.get("sommet").getAsFloat() / 16F * ECHELLE, texture(id));
            } catch (Exception ex) {
                Cabane.LOGGER.warn("Modèle de titan illisible ({}) : {}", fichier, ex.toString());
            }
        }
        MODELES.put(id, m);
        return m;
    }

    private static float[] nombres(JsonObject o, String cle) {
        JsonArray a = o.getAsJsonArray(cle);
        float[] f = new float[a.size()];
        for (int i = 0; i < f.length; i++) {
            f[i] = a.get(i).getAsFloat();
        }
        return f;
    }

    private ModeleTitan() {
    }
}
