package fr.cabane.client.parc;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import fr.cabane.parc.AutoParc;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;

/**
 * Une auto-tamponneuse : un gros pare-chocs noir, une caisse de couleur, un dossier, un volant, et la perche qui monte
 * jusqu'au plafond (son bout fait des étincelles quand ça cogne). 1,4 fois la taille de la maquette.
 */
public class RenduAutoParc extends EntityRenderer<AutoParc> {
    private static final int[] COULEURS = {0xE8333F, 0x3498DB, 0x2ECC71, 0xF1C40F, 0x9B59B6, 0xE67E22, 0x1ABC9C, 0xFF6FA8, 0x34495E, 0xECF0F1};

    public RenduAutoParc(EntityRendererProvider.Context context) {
        super(context);
        shadowRadius = 1.4F;
    }

    @Override
    public void render(AutoParc a, float lacet, float partiel, PoseStack ps, MultiBufferSource buffers, int lum) {
        ps.pushPose();
        ps.mulPose(Axis.YP.rotationDegrees(-Mth.rotLerp(partiel, a.yRotO, a.getYRot())));
        ps.scale(1.4F, 1.4F, 1.4F);
        VertexConsumer c = buffers.getBuffer(Boites.type());
        int col = COULEURS[Math.floorMod(a.couleur(), COULEURS.length)];
        RenduNacelleParc.b(c, ps, 0, 0.27F, 0, 2.25F, 0.36F, 2.95F, 0x1E1E1E, lum);
        RenduNacelleParc.b(c, ps, 0, 0.75F, 0, 1.7F, 0.6F, 2.3F, col, lum);
        RenduNacelleParc.b(c, ps, 0, 1.25F, -0.9F, 1.6F, 0.9F, 0.35F, col, lum);
        RenduNacelleParc.b(c, ps, 0, 1.25F, 0.65F, 0.12F, 0.5F, 0.12F, 0x333333, lum);
        RenduNacelleParc.b(c, ps, 0, 1.5F, 0.6F, 0.6F, 0.08F, 0.6F, 0x222222, lum);
        RenduNacelleParc.b(c, ps, 0, 3.4F, -1.15F, 0.08F, 4.5F, 0.08F, 0x999999, lum);
        boolean etincelle = a.choc > 0 || (a.tickCount + a.getId()) % 37 == 0;
        RenduNacelleParc.b(c, ps, 0, 5.7F, -1.15F, 0.35F, 0.2F, 0.35F, etincelle ? 0xFFF27A : 0x777777, etincelle ? Boites.PLEIN : lum);
        // un néon tout autour du pare-chocs, et la lumière de la caisse sous l'auto, qui battent avec le son
        float puls = AmbianceParc.pulsation(partiel);
        RenduNacelleParc.b(c, ps, 0, 0.47F, 1.485F, 2.15F, 0.07F, 0.05F, col, Boites.PLEIN);
        RenduNacelleParc.b(c, ps, 0, 0.47F, -1.485F, 2.15F, 0.07F, 0.05F, col, Boites.PLEIN);
        RenduNacelleParc.b(c, ps, 1.135F, 0.47F, 0, 0.05F, 0.07F, 2.9F, col, Boites.PLEIN);
        RenduNacelleParc.b(c, ps, -1.135F, 0.47F, 0, 0.05F, 0.07F, 2.9F, col, Boites.PLEIN);
        VertexConsumer l = buffers.getBuffer(Boites.lumiere());
        Boites.lueur(l, ps.last(), 0, 0.03F, 0, 1.75F, 0.02F, 2.1F, col, 0.55F * puls);
        Boites.lueur(l, ps.last(), 0, 0.47F, 0, 1.2F, 0.1F, 1.55F, col, 0.12F * puls);
        ps.popPose();
        super.render(a, lacet, partiel, ps, buffers, lum);
    }

    @Override
    public ResourceLocation getTextureLocation(AutoParc a) {
        return Boites.GRAIN;
    }
}
