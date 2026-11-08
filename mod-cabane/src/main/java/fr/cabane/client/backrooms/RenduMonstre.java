package fr.cabane.client.backrooms;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import fr.cabane.Cabane;
import fr.cabane.backrooms.Backrooms;
import fr.cabane.backrooms.Monstre;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.NoopRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;

/** Un monstre des backrooms en jeu : le modèle de l'aperçu, éclairé par le lieu (le sourire du Souriant brille dans le noir). */
public class RenduMonstre extends EntityRenderer<Monstre> {
    private final ModeleMonstres.Anim anim = new ModeleMonstres.Anim();

    public RenduMonstre(EntityRendererProvider.Context context) {
        super(context);
        shadowRadius = 0.4F;
        shadowStrength = 0.6F;
    }

    @Override
    public void render(Monstre m, float lacet, float partiel, PoseStack ps, MultiBufferSource buffers, int lumiere) {
        if (ClientBackrooms.screamerEnCours()) {
            // pendant son screamer, celui qui l'a attrapé n'est plus qu'en face de toi
            return;
        }
        float corps = Mth.rotLerp(partiel, m.yBodyRotO, m.yBodyRot);
        anim.t = (m.tickCount + partiel) / 20F + m.getId() * 0.37F;
        anim.vitesse = m.vitesse;
        anim.cycle = Mth.lerp(partiel, m.cycleO, m.cycle);
        anim.chasse = m.etat() == Monstre.CHASSE;
        anim.ouvert = 0;
        ps.pushPose();
        ps.mulPose(Axis.YP.rotationDegrees(-corps));
        ModeleMonstres.dessiner(m.sorte(), ps, buffers, lumiere, OverlayTexture.NO_OVERLAY, anim);
        ps.popPose();
        super.render(m, lacet, partiel, ps, buffers, lumiere);
    }

    @Override
    public ResourceLocation getTextureLocation(Monstre m) {
        return ModeleMonstres.ATLAS;
    }

    @EventBusSubscriber(modid = Cabane.MODID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
    public static final class Enregistrement {
        @SubscribeEvent
        public static void rendus(EntityRenderersEvent.RegisterRenderers event) {
            Backrooms.MONSTRES.values().forEach(t -> event.registerEntityRenderer(t.get(), RenduMonstre::new));
            event.registerEntityRenderer(Backrooms.CACHETTE.get(), NoopRenderer::new);
        }
    }
}
