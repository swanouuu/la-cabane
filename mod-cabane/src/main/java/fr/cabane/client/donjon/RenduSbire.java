package fr.cabane.client.donjon;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import fr.cabane.Cabane;
import fr.cabane.donjon.Donjon;
import fr.cabane.donjon.Sbire;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;

/**
 * Les sbires d'Ugrak, en pavés comme lui. Le gobelin : un petit corps vert, de grandes oreilles pointues, une peinture
 * de guerre rouge, des yeux jaunes, une massue d'os. La brute : grise et massive, un casque de fer à cornes, des
 * défenses, des yeux rouges, un marteau de fer. Ils marchent, et frappent (le bras qui s'abat).
 */
public class RenduSbire extends EntityRenderer<Sbire> {
    private static final int VERT = 0x5D8A3A, VERT_SOMBRE = 0x3E5E26, CUIR = 0x4A2E1A, OS = 0xE6DCC2, ROUGE = 0xB02A22,
            GRIS = 0x6E7458, GRIS_SOMBRE = 0x4C5140, FER = 0x2A2A30, FER_CLAIR = 0x60606C;

    public RenduSbire(EntityRendererProvider.Context context) {
        super(context);
        shadowRadius = 0.5F;
    }

    @Override
    public void render(Sbire s, float lacet, float partiel, PoseStack ps, MultiBufferSource buffers, int lumiere) {
        float corps = Mth.rotLerp(partiel, s.yBodyRotO, s.yBodyRot);
        float tete = Mth.clamp(Mth.wrapDegrees(Mth.rotLerp(partiel, s.yHeadRotO, s.yHeadRot) - corps), -60F, 60F) * Mth.DEG_TO_RAD;
        float amp = Math.min(1F, s.walkAnimation.speed(partiel) * 1.5F);
        float pas = s.walkAnimation.position(partiel) * (s.brute() ? 0.5F : 0.8F);
        float sw = Mth.sin(pas) * amp;
        float coup = s.getAttackAnim(partiel);
        // le coup : le bras monte puis s'abat
        float bras = coup > 0 ? -2.6F * Mth.sin(coup * Mth.PI) - 0.4F * coup : -sw * 0.6F;
        int overlay = OverlayTexture.pack(OverlayTexture.u(0F), OverlayTexture.v(s.hurtTime > 0 || s.deathTime > 0));
        ps.pushPose();
        ps.mulPose(Axis.YP.rotationDegrees(-corps));
        if (s.deathTime > 0) {
            ps.mulPose(Axis.ZP.rotationDegrees(Math.min(1F, (s.deathTime + partiel) / 12F) * 85F));
        }
        ModeleOgre.debut(buffers, lumiere, overlay);
        if (s.brute()) {
            brute(ps, sw, bras, tete);
        } else {
            gobelin(ps, sw, bras, tete);
        }
        ps.popPose();
        super.render(s, lacet, partiel, ps, buffers, lumiere);
    }

    private static void jambe(PoseStack ps, float x, float hanche, float w, float h, float angle, int couleur, int pied) {
        ps.pushPose();
        ModeleOgre.tourner(ps, x, hanche, 0, angle, 0, 0);
        ModeleOgre.pave(ps, -w / 2, -h, -w / 2, w / 2, 0, w / 2, couleur);
        ModeleOgre.pave(ps, -w / 2 - 0.02F, -h, -w / 2 - 0.02F, w / 2 + 0.02F, -h + 0.15F, w / 2 + 0.12F, pied);
        ps.popPose();
    }

    private static void gobelin(PoseStack ps, float sw, float bras, float tete) {
        jambe(ps, -0.14F, 0.6F, 0.22F, 0.6F, sw * 0.7F, VERT_SOMBRE, CUIR);
        jambe(ps, 0.14F, 0.6F, 0.22F, 0.6F, -sw * 0.7F, VERT_SOMBRE, CUIR);
        // le corps penché, un pagne de cuir
        ps.pushPose();
        ModeleOgre.tourner(ps, 0, 0.58F, 0, 0.18F, 0, 0);
        ModeleOgre.pave(ps, -0.3F, 0, -0.2F, 0.3F, 0.55F, 0.2F, VERT);
        ModeleOgre.pave(ps, -0.32F, -0.05F, -0.22F, 0.32F, 0.18F, 0.22F, CUIR);
        // la tête : un grand nez, les oreilles pointues, la peinture rouge, les yeux jaunes
        ps.pushPose();
        ModeleOgre.tourner(ps, 0, 0.55F, 0.05F, 0, tete, 0);
        ModeleOgre.pave(ps, -0.25F, 0, -0.22F, 0.25F, 0.45F, 0.26F, VERT);
        ModeleOgre.pave(ps, -0.06F, 0.12F, 0.26F, 0.06F, 0.28F, 0.42F, VERT_SOMBRE);
        ModeleOgre.pave(ps, -0.25F, 0.26F, 0.26F, 0.25F, 0.32F, 0.28F, ROUGE);
        ModeleOgre.pave(ps, -0.19F, 0.3F, 0.26F, -0.08F, 0.37F, 0.29F, 0xFFD23A, true);
        ModeleOgre.pave(ps, 0.08F, 0.3F, 0.26F, 0.19F, 0.37F, 0.29F, 0xFFD23A, true);
        for (int s : new int[] {-1, 1}) {
            ps.pushPose();
            ModeleOgre.tourner(ps, s * 0.25F, 0.3F, 0, 0, 0, s * -0.5F);
            ModeleOgre.pave(ps, s > 0 ? 0 : -0.38F, -0.06F, -0.03F, s > 0 ? 0.38F : 0, 0.08F, 0.05F, VERT);
            ps.popPose();
        }
        ps.popPose();
        // les bras ; la massue d'os dans la main droite
        for (int s : new int[] {-1, 1}) {
            ps.pushPose();
            ModeleOgre.tourner(ps, s * 0.38F, 0.5F, 0, s > 0 ? bras : sw * 0.6F, 0, s * -0.15F);
            ModeleOgre.pave(ps, -0.08F, -0.5F, -0.08F, 0.08F, 0.05F, 0.08F, VERT);
            if (s > 0) {
                ModeleOgre.tourner(ps, 0, -0.48F, 0.05F, 1.2F, 0, 0);
                ModeleOgre.pave(ps, -0.05F, -0.05F, -0.1F, 0.05F, 0.05F, 0.55F, OS);
                ModeleOgre.pave(ps, -0.1F, -0.1F, 0.5F, 0.1F, 0.1F, 0.75F, OS);
            }
            ps.popPose();
        }
        ps.popPose();
    }

    private static void brute(PoseStack ps, float sw, float bras, float tete) {
        jambe(ps, -0.33F, 1.05F, 0.42F, 1.05F, sw * 0.5F, GRIS_SOMBRE, FER);
        jambe(ps, 0.33F, 1.05F, 0.42F, 1.05F, -sw * 0.5F, GRIS_SOMBRE, FER);
        ps.pushPose();
        ModeleOgre.tourner(ps, 0, 1.0F, 0, 0.2F, 0, 0);
        ModeleOgre.pave(ps, -0.6F, 0, -0.4F, 0.6F, 1.15F, 0.42F, GRIS);
        ModeleOgre.pave(ps, -0.52F, 0.45F, 0.42F, 0.52F, 1.1F, 0.5F, FER);
        ModeleOgre.pave(ps, -0.62F, -0.05F, -0.42F, 0.62F, 0.2F, 0.44F, CUIR);
        // la tête : casque de fer à petites cornes, défenses, yeux rouges
        ps.pushPose();
        ModeleOgre.tourner(ps, 0, 1.1F, 0.12F, 0, tete, 0);
        ModeleOgre.pave(ps, -0.3F, 0, -0.28F, 0.3F, 0.55F, 0.32F, GRIS);
        ModeleOgre.pave(ps, -0.33F, 0.38F, -0.31F, 0.33F, 0.62F, 0.35F, FER);
        ModeleOgre.pave(ps, -0.2F, 0.24F, 0.32F, -0.07F, 0.32F, 0.35F, 0xFF3020, true);
        ModeleOgre.pave(ps, 0.07F, 0.24F, 0.32F, 0.2F, 0.32F, 0.35F, 0xFF3020, true);
        ModeleOgre.pave(ps, -0.24F, 0.02F, 0.3F, -0.16F, 0.22F, 0.38F, OS);
        ModeleOgre.pave(ps, 0.16F, 0.02F, 0.3F, 0.24F, 0.22F, 0.38F, OS);
        for (int s : new int[] {-1, 1}) {
            ps.pushPose();
            ModeleOgre.tourner(ps, s * 0.33F, 0.55F, 0, 0, 0, s * -0.7F);
            ModeleOgre.pave(ps, -0.06F, 0, -0.06F, 0.06F, 0.3F, 0.06F, OS);
            ps.popPose();
        }
        ps.popPose();
        // les bras ; le marteau de fer dans la main droite
        for (int s : new int[] {-1, 1}) {
            ps.pushPose();
            ModeleOgre.tourner(ps, s * 0.78F, 0.95F, 0, s > 0 ? bras : sw * 0.5F, 0, s * -0.12F);
            ModeleOgre.pave(ps, -0.18F, -1.0F, -0.18F, 0.18F, 0.15F, 0.18F, GRIS);
            ModeleOgre.pave(ps, -0.2F, -0.85F, -0.2F, 0.2F, -0.6F, 0.2F, FER);
            if (s > 0) {
                ModeleOgre.tourner(ps, 0, -0.95F, 0.05F, 1.1F, 0, 0);
                ModeleOgre.pave(ps, -0.06F, -0.06F, -0.3F, 0.06F, 0.06F, 1.1F, CUIR);
                ModeleOgre.pave(ps, -0.22F, -0.28F, 0.95F, 0.22F, 0.28F, 1.45F, FER);
                ModeleOgre.pave(ps, -0.24F, -0.3F, 1.15F, 0.24F, 0.3F, 1.25F, FER_CLAIR);
            }
            ps.popPose();
        }
        ps.popPose();
    }

    @Override
    public ResourceLocation getTextureLocation(Sbire s) {
        return Cabane.id("textures/entity/cheveux.png");
    }

    @EventBusSubscriber(modid = Cabane.MODID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
    public static final class Enregistrement {
        @SubscribeEvent
        public static void rendus(EntityRenderersEvent.RegisterRenderers event) {
            event.registerEntityRenderer(Donjon.GOBELIN.get(), RenduSbire::new);
            event.registerEntityRenderer(Donjon.BRUTE.get(), RenduSbire::new);
        }
    }
}
