package fr.cabane.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import fr.cabane.police.VoitureEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * La voiture de police, en petits blocs : carrosserie blanche, bande bleue, vitres teintées, gyrophare
 * rouge et bleu qui clignote quand la sirène hurle. L'avant est vers +z.
 */
public class VoitureRenderer extends EntityRenderer<VoitureEntity> {
    private static final BlockState BLANC = Blocks.WHITE_CONCRETE.defaultBlockState();
    private static final BlockState BLEU = Blocks.BLUE_CONCRETE.defaultBlockState();
    private static final BlockState NOIR = Blocks.BLACK_CONCRETE.defaultBlockState();
    private static final BlockState GRIS = Blocks.GRAY_CONCRETE.defaultBlockState();
    private static final BlockState JANTE = Blocks.LIGHT_GRAY_CONCRETE.defaultBlockState();
    private static final BlockState VITRE = Blocks.BLACK_STAINED_GLASS.defaultBlockState();
    private static final BlockState PHARE = Blocks.PEARLESCENT_FROGLIGHT.defaultBlockState();
    private static final BlockState FEU = Blocks.RED_CONCRETE.defaultBlockState();
    private static final BlockState ROUGE_ETEINT = Blocks.RED_TERRACOTTA.defaultBlockState();
    private static final BlockState BLEU_ETEINT = Blocks.BLUE_TERRACOTTA.defaultBlockState();
    private static final BlockState BLEU_ALLUME = Blocks.LIGHT_BLUE_CONCRETE.defaultBlockState();
    private static final float W = 0.95F;

    public VoitureRenderer(EntityRendererProvider.Context context) {
        super(context);
        shadowRadius = 1.2F;
    }

    @Override
    public void render(VoitureEntity voiture, float yaw, float partialTick, PoseStack pose, MultiBufferSource buffers, int lumiere) {
        BlockRenderDispatcher blocs = Minecraft.getInstance().getBlockRenderer();
        pose.pushPose();
        pose.mulPose(Axis.YP.rotationDegrees(-yaw));
        Pave p = (etat, x0, y0, z0, x1, y1, z1, clair) -> {
            pose.pushPose();
            pose.translate(x0, y0, z0);
            pose.scale(x1 - x0, y1 - y0, z1 - z0);
            blocs.renderSingleBlock(etat, pose, buffers, clair ? LightTexture.FULL_BRIGHT : lumiere, OverlayTexture.NO_OVERLAY);
            pose.popPose();
        };
        // roues et jantes
        for (float sx : new float[] {-1, 1}) {
            for (float z : new float[] {-1.2F, 1.15F}) {
                p.pave(NOIR, sx > 0 ? 0.62F : -0.98F, 0, z - 0.32F, sx > 0 ? 0.98F : -0.62F, 0.62F, z + 0.32F, false);
                p.pave(JANTE, sx > 0 ? 0.98F : -1.0F, 0.18F, z - 0.13F, sx > 0 ? 1.0F : -0.98F, 0.44F, z + 0.13F, false);
            }
        }
        // carrosserie, bande bleue, pare-chocs
        p.pave(BLANC, -W, 0.25F, -1.95F, W, 0.9F, 1.95F, false);
        p.pave(BLEU, -W - 0.01F, 0.42F, -1.96F, W + 0.01F, 0.58F, 1.96F, false);
        p.pave(GRIS, -W, 0.2F, 1.95F, W, 0.38F, 2.05F, false);
        p.pave(GRIS, -W, 0.2F, -2.05F, W, 0.38F, -1.95F, false);
        // phares et feux arrière
        p.pave(PHARE, -0.82F, 0.66F, 1.95F, -0.5F, 0.8F, 1.97F, true);
        p.pave(PHARE, 0.5F, 0.66F, 1.95F, 0.82F, 0.8F, 1.97F, true);
        p.pave(FEU, -0.86F, 0.66F, -1.97F, -0.56F, 0.8F, -1.95F, true);
        p.pave(FEU, 0.56F, 0.66F, -1.97F, 0.86F, 0.8F, -1.95F, true);
        // l'habitacle : montants blancs, vitres teintées, toit
        float bas = 0.9F;
        float haut = 1.72F;
        for (float x : new float[] {-0.86F, 0.78F}) {
            for (float z : new float[] {-1.25F, 0.62F}) {
                p.pave(BLANC, x, bas, z, x + 0.08F, haut, z + 0.08F, false);
            }
        }
        p.pave(VITRE, -0.86F, bas, 0.66F, 0.86F, haut, 0.68F, false);
        p.pave(VITRE, -0.86F, bas, -1.25F, 0.86F, haut, -1.23F, false);
        p.pave(VITRE, -0.87F, bas, -1.25F, -0.85F, haut, 0.68F, false);
        p.pave(VITRE, 0.85F, bas, -1.25F, 0.87F, haut, 0.68F, false);
        p.pave(BLANC, -0.9F, haut, -1.3F, 0.9F, haut + 0.1F, 0.72F, false);
        // le gyrophare
        boolean sirene = voiture.sirene();
        boolean phase = (voiture.tickCount / 5) % 2 == 0;
        p.pave(NOIR, -0.55F, haut + 0.1F, -0.42F, 0.55F, haut + 0.14F, -0.18F, false);
        p.pave(sirene && phase ? FEU : ROUGE_ETEINT, 0.04F, haut + 0.14F, -0.4F, 0.52F, haut + 0.28F, -0.2F, sirene && phase);
        p.pave(sirene && !phase ? BLEU_ALLUME : BLEU_ETEINT, -0.52F, haut + 0.14F, -0.4F, -0.04F, haut + 0.28F, -0.2F, sirene && !phase);
        // POLICE sur les portières
        Font font = Minecraft.getInstance().font;
        Component texte = Component.literal("POLICE").withStyle(s -> s.withBold(true));
        for (int cote : new int[] {-1, 1}) {
            pose.pushPose();
            pose.translate(cote * (W + 0.02F), 0.82F, -0.25F);
            pose.mulPose(Axis.YP.rotationDegrees(cote * 90F));
            pose.scale(0.02F, -0.02F, 0.02F);
            font.drawInBatch(texte, -font.width(texte) / 2F, 0, 0xFF1A2F7A, false, pose.last().pose(), buffers, Font.DisplayMode.NORMAL, 0, lumiere);
            pose.popPose();
        }
        pose.popPose();
        super.render(voiture, yaw, partialTick, pose, buffers, lumiere);
    }

    @FunctionalInterface
    private interface Pave {
        void pave(BlockState etat, float x0, float y0, float z0, float x1, float y1, float z1, boolean clair);
    }

    @Override
    public ResourceLocation getTextureLocation(VoitureEntity voiture) {
        return ResourceLocation.withDefaultNamespace("textures/block/white_concrete.png");
    }
}
