package fr.cabane.client.cinema;

import com.mojang.blaze3d.vertex.PoseStack;
import fr.cabane.Cabane;
import fr.cabane.vegeta.CombattantZ;
import fr.cabane.vegeta.KiBlast;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;

/** Goku et Vegeta en vrai : leur skin, leur aura, et leur rayon quand ils tirent. */
public class CombattantZRenderer extends LivingEntityRenderer<CombattantZ, PlayerModel<CombattantZ>> {
    private static final ResourceLocation GOKU = Cabane.id("textures/entity/pnj/goku_ssj2.png");
    private static final ResourceLocation VEGETA = Cabane.id("textures/entity/pnj/majin_vegeta.png");

    public CombattantZRenderer(EntityRendererProvider.Context context) {
        super(context, new Modele(context.bakeLayer(ModelLayers.PLAYER)), 0.5F);
        addLayer(new Cheveux(this));
    }

    /** Les mèches en 3D, posées sur la tête du modèle. */
    private static final class Cheveux extends net.minecraft.client.renderer.entity.layers.RenderLayer<CombattantZ, PlayerModel<CombattantZ>> {
        Cheveux(net.minecraft.client.renderer.entity.RenderLayerParent<CombattantZ, PlayerModel<CombattantZ>> parent) {
            super(parent);
        }

        @Override
        public void render(PoseStack pose, MultiBufferSource buffers, int lumiere, CombattantZ c, float pas, float ampleur, float partialTick, float age, float tete, float tangage) {
            pose.pushPose();
            getParentModel().head.translateAndRotate(pose);
            ResourceLocation skin = getTextureLocation(c);
            // en combat, tous les deux sont en Super Saiyan : les mèches prennent la couleur des cheveux du skin, et brillent
            Effets.cheveux(pose, buffers, Effets.coiffure(skin), Effets.couleurCheveux(skin, Effets.DORE), c.variante() == CombattantZ.GOKU ? 1.1F : 1F, 0xF000F0);
            pose.popPose();
        }
    }

    /** Le modèle de joueur, mais en vol : bras le long du corps, ou tendus quand ils chargent leur rayon. */
    private static final class Modele extends PlayerModel<CombattantZ> {
        Modele(net.minecraft.client.model.geom.ModelPart racine) {
            super(racine, false);
        }

        @Override
        public void setupAnim(CombattantZ c, float pas, float ampleur, float age, float tete, float tangage) {
            super.setupAnim(c, 0, 0, age, tete, tangage);
            if (c.etat() > 0) {
                rightArm.xRot = -1.57F;
                leftArm.xRot = -1.57F;
                rightArm.yRot = -0.2F;
                leftArm.yRot = 0.2F;
            } else {
                rightArm.xRot = 0.35F + (float) Math.sin(age * 0.1) * 0.08F;
                leftArm.xRot = 0.35F - (float) Math.sin(age * 0.1) * 0.08F;
                rightArm.zRot = 0.25F;
                leftArm.zRot = -0.25F;
                rightLeg.xRot = 0.15F;
                leftLeg.xRot = 0.3F;
            }
            rightSleeve.copyFrom(rightArm);
            leftSleeve.copyFrom(leftArm);
            rightPants.copyFrom(rightLeg);
            leftPants.copyFrom(leftLeg);
        }
    }

    @Override
    public ResourceLocation getTextureLocation(CombattantZ c) {
        return c.variante() == CombattantZ.GOKU ? GOKU : VEGETA;
    }

    @Override
    public void render(CombattantZ c, float lacet, float partialTick, PoseStack pose, MultiBufferSource buffers, int lumiere) {
        super.render(c, lacet, partialTick, pose, buffers, lumiere);
        float temps = (c.tickCount + partialTick) / 20F;
        boolean goku = c.variante() == CombattantZ.GOKU;
        float force = c.etat() > 0 ? 1.5F : c.getHealth() < c.getMaxHealth() / 2 ? 1.2F : 0.8F;
        Effets.aura(pose, buffers, Vec3.ZERO, force, goku ? 1F : 0.75F, goku ? 0.82F : 0.15F, goku ? 0.2F : 0.95F, temps);
        if (goku && (c.tickCount / 3) % 3 != 0) {
            Effets.eclair(pose, buffers, new Vec3(-0.4, 2.1, 0.2), new Vec3(0.5, 0.3, -0.3), c.tickCount / 3, 0.02, 1F, 0.95F, 0.5F);
        }
        Vec3 position = c.getPosition(partialTick);
        Vec3 mains = new Vec3(0, 1.5, 0).add(c.getViewVector(partialTick).scale(0.8));
        if (c.etat() == 1) {
            Effets.sphere(pose, buffers, mains, 0.3 + (c.tickCount % 40) / 40.0 * 0.4, goku ? 0.35F : 1F, goku ? 0.75F : 0.9F, goku ? 1F : 0.25F, 0.9F);
        } else if (c.etat() == 2) {
            Vec3 vers = c.visee().subtract(position);
            Vec3 bout = mains.add(vers.subtract(mains).normalize().scale(45));
            Effets.rayon(pose, buffers, mains, bout, 0.9, goku ? 0.3F : 1F, goku ? 0.7F : 0.9F, goku ? 1F : 0.25F, temps);
        }
    }

    @Override
    protected boolean shouldShowName(CombattantZ c) {
        return true;
    }

    /** Une boule d'énergie : une petite sphère qui brille, jaune ou bleue. */
    public static class Boule extends EntityRenderer<KiBlast> {
        public Boule(EntityRendererProvider.Context context) {
            super(context);
        }

        @Override
        public void render(KiBlast b, float lacet, float partialTick, PoseStack pose, MultiBufferSource buffers, int lumiere) {
            boolean bleue = b.couleur() == 1;
            Effets.sphere(pose, buffers, new Vec3(0, 0.2, 0), 0.35 + Math.sin((b.tickCount + partialTick) * 0.8) * 0.05,
                    bleue ? 0.35F : 1F, bleue ? 0.75F : 0.85F, bleue ? 1F : 0.25F, 0.95F);
        }

        @Override
        public ResourceLocation getTextureLocation(KiBlast b) {
            return TextureAtlas.LOCATION_BLOCKS;
        }
    }
}
