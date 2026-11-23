package fr.cabane.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import fr.cabane.bouffe.Kebab;
import fr.cabane.bouffe.Kebab.BrocheBlock;
import fr.cabane.bouffe.Kebab.BrocheBlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

/**
 * La broche du kebab : la tige en inox et le cône de viande qui tourne doucement. Plus on la découpe, plus il maigrit. Le
 * socle, lui, est le modèle du bloc.
 */
public class RenduBroche implements BlockEntityRenderer<BrocheBlockEntity> {
    private static final BlockState INOX = Blocks.IRON_BLOCK.defaultBlockState();
    /** Tranches de viande, du bas vers le haut. */
    private static final int TRANCHES = 14;
    private static final double BAS = 0.22, HAUT = 1.72;

    public RenduBroche(BlockEntityRendererProvider.Context context) {
    }

    @Override
    public void render(BrocheBlockEntity be, float partiel, PoseStack ps, MultiBufferSource buf, int lum, int overlay) {
        if (be.getLevel() == null) {
            return;
        }
        BlockState etat = be.getBlockState();
        if (!(etat.getBlock() instanceof BrocheBlock)) {
            return;
        }
        boolean poulet = etat.getValue(BrocheBlock.POULET);
        double reste = etat.getValue(BrocheBlock.PARTS) / (double) BrocheBlock.MAX;
        BlockState viande = Kebab.VIANDE_BROCHE.get().defaultBlockState().setValue(BrocheBlock.POULET, poulet);
        // un tour toutes les sept secondes environ, chaque broche avec son propre décalage
        double temps = (be.getLevel().getGameTime() + partiel) / 20.0;
        double angle = temps * 0.9 + (be.getBlockPos().asLong() % 7);

        ps.pushPose();
        ps.translate(0.5, 0, 0.5);
        ps.mulPose(Axis.YP.rotation((float) angle));
        // la tige, et sa petite pointe en haut
        cube(ps, buf, INOX, -0.035, 0.08, -0.035, 0.035, 1.98, 0.035, lum);
        cube(ps, buf, INOX, -0.16, 1.78, -0.16, 0.16, 1.82, 0.16, lum);
        // le cône de viande, plus large en haut ; il en reste toujours un peu sur la tige
        double maigrir = 0.25 + 0.75 * reste;
        double h = (HAUT - BAS) / TRANCHES;
        for (int i = 0; i < TRANCHES; i++) {
            double t = i / (double) (TRANCHES - 1);
            double r = (0.13 + 0.17 * t) * maigrir + 0.05;
            double y0 = BAS + i * h, y1 = y0 + h + 0.002;
            // deux carrés croisés, un peu tordus d'une tranche à l'autre : de loin, ça fait rond
            ps.pushPose();
            ps.mulPose(Axis.YP.rotationDegrees(i * 13));
            cube(ps, buf, viande, -r, y0, -r, r, y1, r, lum);
            ps.mulPose(Axis.YP.rotationDegrees(45));
            double r2 = r * 0.97;
            cube(ps, buf, viande, -r2, y0 + 0.001, -r2, r2, y1 - 0.001, r2, lum);
            ps.popPose();
        }
        ps.popPose();
    }

    private static void cube(PoseStack ps, MultiBufferSource buf, BlockState etat, double x0, double y0, double z0, double x1, double y1, double z1, int lum) {
        ps.pushPose();
        ps.translate(x0, y0, z0);
        ps.scale((float) (x1 - x0), (float) (y1 - y0), (float) (z1 - z0));
        Minecraft.getInstance().getBlockRenderer().renderSingleBlock(etat, ps, buf, lum, OverlayTexture.NO_OVERLAY);
        ps.popPose();
    }

    @Override
    public AABB getRenderBoundingBox(BrocheBlockEntity be) {
        return new AABB(be.getBlockPos()).expandTowards(0, 1, 0).inflate(0.2);
    }
}
