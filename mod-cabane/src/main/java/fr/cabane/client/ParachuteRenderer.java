package fr.cabane.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import fr.cabane.parachute.ParachuteEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Matrix4f;

/** Une voile en arc (laine rouge et blanche) au-dessus du joueur, reliée par quatre suspentes. */
public class ParachuteRenderer extends EntityRenderer<ParachuteEntity> {
    private static final BlockState ROUGE = Blocks.RED_WOOL.defaultBlockState();
    private static final BlockState BLANC = Blocks.WHITE_WOOL.defaultBlockState();
    private static final float TAILLE = 0.5F;

    public ParachuteRenderer(EntityRendererProvider.Context context) {
        super(context);
    }

    @Override
    public void render(ParachuteEntity entity, float yaw, float partialTick, PoseStack pose, MultiBufferSource buffers, int light) {
        BlockRenderDispatcher blocs = Minecraft.getInstance().getBlockRenderer();
        pose.pushPose();
        pose.mulPose(Axis.YP.rotationDegrees(-yaw));
        // voile : 7 colonnes en arc, 3 de profondeur
        for (int i = -3; i <= 3; i++) {
            float hauteur = 3.6F - (i * i) * 0.09F;
            for (int k = -1; k <= 1; k++) {
                pose.pushPose();
                pose.translate(i * TAILLE - TAILLE / 2, hauteur, k * TAILLE - TAILLE / 2);
                pose.scale(TAILLE, TAILLE * 0.4F, TAILLE);
                blocs.renderSingleBlock(Math.floorMod(i, 2) == 0 ? ROUGE : BLANC, pose, buffers, light, OverlayTexture.NO_OVERLAY);
                pose.popPose();
            }
        }
        // suspentes
        VertexConsumer lignes = buffers.getBuffer(RenderType.lines());
        Matrix4f m = pose.last().pose();
        float[][] coins = {{-1.6F, 3.0F, -0.6F}, {1.6F, 3.0F, -0.6F}, {-1.6F, 3.0F, 0.6F}, {1.6F, 3.0F, 0.6F}};
        for (float[] c : coins) {
            lignes.addVertex(m, c[0], c[1], c[2]).setColor(40, 40, 40, 255).setNormal(pose.last(), 0, -1, 0);
            lignes.addVertex(m, 0, 1.3F, 0).setColor(40, 40, 40, 255).setNormal(pose.last(), 0, -1, 0);
        }
        pose.popPose();
        super.render(entity, yaw, partialTick, pose, buffers, light);
    }

    @Override
    public ResourceLocation getTextureLocation(ParachuteEntity entity) {
        return ResourceLocation.withDefaultNamespace("textures/block/white_wool.png");
    }
}
