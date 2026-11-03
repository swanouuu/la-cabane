package fr.cabane.client.lutte;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import fr.cabane.Cabane;
import fr.cabane.lutte.RingBlocs;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Le public de la salle : sur chaque siège, quelqu'un (16 têtes différentes) assis qui bouge un peu, ou debout qui crie
 * les bras en l'air ; après une prise ou un KO, tout le monde se lève.
 */
public final class RenduPublic implements BlockEntityRenderer<RingBlocs.PublicBE> {
    private static final ResourceLocation[] PEAUX = new ResourceLocation[16];

    static {
        for (int i = 0; i < PEAUX.length; i++) {
            PEAUX[i] = Cabane.id("textures/entity/catch/public_" + i + ".png");
        }
    }

    private final PlayerModel<?> modele;

    public RenduPublic(BlockEntityRendererProvider.Context ctx) {
        modele = new PlayerModel<>(LayerDefinition.create(PlayerModel.createMesh(CubeDeformation.NONE, false), 64, 64).bakeRoot(), false);
    }

    @Override
    public void render(RingBlocs.PublicBE be, float partiel, PoseStack ps, MultiBufferSource buffers, int lumiere, int dessous) {
        BlockPos pos = be.getBlockPos();
        BlockState s = be.getBlockState();
        Direction regard = s.hasProperty(HorizontalDirectionalBlock.FACING) ? s.getValue(HorizontalDirectionalBlock.FACING) : Direction.NORTH;
        long graine = Mth.getSeed(pos);
        int peau = (int) Math.floorMod(graine, 16L), humeur = (int) Math.floorMod(graine >> 8, 100L);
        float t = (be.getLevel() == null ? 0 : be.getLevel().getGameTime()) + partiel, ph = (graine & 255) / 40F;
        float excite = ClientLutte.excitation(partiel);
        boolean debout = humeur >= 62 || (excite > 0 && humeur % 3 != 0);
        var m = modele;
        m.setAllVisible(true);
        m.young = false;
        m.crouching = false;
        m.riding = false;
        // la tête suit un peu le ring, les bras bougent selon l'humeur
        m.head.xRot = 0.15F * Mth.sin(t * 0.05F + ph) - (debout ? 0.25F : 0.05F);
        m.head.yRot = 0.3F * Mth.sin(t * 0.03F + ph * 2);
        m.head.zRot = 0;
        m.body.xRot = m.body.yRot = m.body.zRot = 0;
        if (debout) {
            float saut = Math.max(excite, humeur >= 85 ? 1 : 0.5F);
            m.rightArm.xRot = -2.8F + 0.25F * Mth.sin(t * 0.4F + ph) * saut;
            m.leftArm.xRot = -2.8F + 0.25F * Mth.sin(t * 0.4F + ph + 1.5F) * saut;
            m.rightArm.zRot = 0.25F;
            m.leftArm.zRot = -0.25F;
            m.rightLeg.xRot = m.leftLeg.xRot = 0;
        } else {
            m.rightArm.xRot = -0.5F + 0.1F * Mth.sin(t * 0.06F + ph);
            m.leftArm.xRot = -0.5F;
            m.rightArm.zRot = m.leftArm.zRot = 0;
            m.rightLeg.xRot = m.leftLeg.xRot = -Mth.HALF_PI;
        }
        m.rightArm.yRot = m.leftArm.yRot = 0;
        m.rightLeg.yRot = m.leftLeg.yRot = m.rightLeg.zRot = m.leftLeg.zRot = 0;
        m.hat.copyFrom(m.head);
        m.jacket.copyFrom(m.body);
        m.rightSleeve.copyFrom(m.rightArm);
        m.leftSleeve.copyFrom(m.leftArm);
        m.rightPants.copyFrom(m.rightLeg);
        m.leftPants.copyFrom(m.leftLeg);

        ps.pushPose();
        // assis sur le siège (le dessus est à 7/16), ou debout dessus ; et quand le public crie, il saute un peu
        float y = debout ? 0.44F + Math.max(0, Mth.sin(t * 0.5F + ph)) * 0.12F * excite : 0.44F - 0.75F + 0.16F;
        ps.translate(0.5, y, 0.5);
        ps.mulPose(Axis.YP.rotationDegrees(180 - regard.toYRot()));
        ps.scale(-1, -1, 1);
        ps.translate(0, -1.501F, 0);
        m.renderToBuffer(ps, buffers.getBuffer(RenderType.entityCutoutNoCull(PEAUX[peau])), lumiere, OverlayTexture.NO_OVERLAY);
        ps.popPose();
    }

    @Override
    public int getViewDistance() {
        return 48;
    }
}
