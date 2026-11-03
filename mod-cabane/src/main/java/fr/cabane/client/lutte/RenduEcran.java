package fr.cabane.client.lutte;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import fr.cabane.Cabane;
import fr.cabane.lutte.Lutte;
import fr.cabane.lutte.RingBlocs;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.AABB;
import org.joml.Matrix4f;

/**
 * L'écran géant de la salle (22 × 11 blocs, au-dessus de la scène) : le logo CATCH, et pendant un match sur le ring de
 * la salle, les noms des deux catcheurs et le chrono qui monte jusqu'au KO (puis le gagnant).
 */
public final class RenduEcran implements BlockEntityRenderer<RingBlocs.EcranBE> {
    private static final ResourceLocation LOGO = Cabane.id("textures/entity/catch/ecran.png");
    private static final int L = 22, H = 11;
    private final Font police;

    public RenduEcran(BlockEntityRendererProvider.Context ctx) {
        police = ctx.getFont();
    }

    @Override
    public void render(RingBlocs.EcranBE be, float partiel, PoseStack ps, MultiBufferSource buffers, int lumiere, int dessous) {
        ps.pushPose();
        Matrix4f m = ps.last().pose();
        VertexConsumer c = buffers.getBuffer(RenderType.entityCutoutNoCull(LOGO));
        float z = 1.01F;
        sommet(c, ps, m, 0, 0, z, 0, 1);
        sommet(c, ps, m, L, 0, z, 1, 1);
        sommet(c, ps, m, L, H, z, 1, 0);
        sommet(c, ps, m, 0, H, z, 0, 0);
        Lutte.EtatMatch e = ClientLutte.matchSalle();
        if (e != null && !e.noms().isEmpty()) {
            // la bande du bas : les deux noms et le chrono, en gros
            ps.translate(L / 2F, 2.6F, z + 0.01F);
            ps.scale(0.09F, -0.09F, 0.09F);
            String t = ClientLutte.temps(ClientLutte.chrono(e));
            Matrix4f mt = ps.last().pose();
            String a = e.noms().get(0), b = e.noms().size() > 1 ? e.noms().get(1) : "";
            police.drawInBatch(a, -110 - police.width(a) / 2F, -4, 0xFFFFD25A, false, mt, buffers, Font.DisplayMode.POLYGON_OFFSET, 0xC0100C18,
                    LightTexture.FULL_BRIGHT);
            police.drawInBatch(b, 110 - police.width(b) / 2F, -4, 0xFFFFD25A, false, mt, buffers, Font.DisplayMode.POLYGON_OFFSET, 0xC0100C18,
                    LightTexture.FULL_BRIGHT);
            ps.scale(2.4F, 2.4F, 1);
            police.drawInBatch(t, -police.width(t) / 2F, -4, e.fini() ? 0xFFFFD25A : 0xFFFFFFFF, false, ps.last().pose(), buffers,
                    Font.DisplayMode.POLYGON_OFFSET, 0xC0100C18, LightTexture.FULL_BRIGHT);
            if (e.fini() && !e.gagnant().isEmpty()) {
                String g = e.gagnant().toUpperCase() + " GAGNE PAR KO";
                ps.scale(0.5F, 0.5F, 1);
                police.drawInBatch(g, -police.width(g) / 2F, 14, 0xFFFFFFFF, false, ps.last().pose(), buffers, Font.DisplayMode.POLYGON_OFFSET,
                        0xC0100C18, LightTexture.FULL_BRIGHT);
            }
        }
        ps.popPose();
    }

    private static void sommet(VertexConsumer c, PoseStack ps, Matrix4f m, float x, float y, float z, float u, float v) {
        c.addVertex(m, x, y, z).setColor(255, 255, 255, 255).setUv(u, v).setOverlay(OverlayTexture.NO_OVERLAY).setLight(LightTexture.FULL_BRIGHT)
                .setNormal(ps.last(), 0, 0, 1);
    }

    @Override
    public boolean shouldRenderOffScreen(RingBlocs.EcranBE be) {
        return true;
    }

    @Override
    public int getViewDistance() {
        return 128;
    }

    @Override
    public AABB getRenderBoundingBox(RingBlocs.EcranBE be) {
        return new AABB(be.getBlockPos()).expandTowards(L, H, 1);
    }

    static Minecraft mc() {
        return Minecraft.getInstance();
    }
}
