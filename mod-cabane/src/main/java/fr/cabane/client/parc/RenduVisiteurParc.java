package fr.cabane.client.parc;

import com.mojang.blaze3d.vertex.PoseStack;
import fr.cabane.Cabane;
import fr.cabane.parc.Parc;
import fr.cabane.parc.VisiteurParc;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.client.resources.PlayerSkin;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;

/**
 * Les visiteurs du parc : un des skins de base de Minecraft (Steve, Alex, Ari, Efe, Kai, Makena, Noor, Sunny, Zuri),
 * tiré d'après leur identifiant, avec les bras fins quand le skin en a.
 */
public class RenduVisiteurParc extends HumanoidMobRenderer<VisiteurParc, PlayerModel<VisiteurParc>> {
    private final PlayerModel<VisiteurParc> large, fin;

    public RenduVisiteurParc(EntityRendererProvider.Context ctx) {
        super(ctx, new PlayerModel<>(ctx.bakeLayer(ModelLayers.PLAYER), false), 0.5F);
        large = model;
        fin = new PlayerModel<>(ctx.bakeLayer(ModelLayers.PLAYER_SLIM), true);
    }

    /** Le skin de Rachida (bras fins). */
    private static final PlayerSkin RACHIDA = new PlayerSkin(Cabane.id("textures/entity/parc/rachida.png"), null, null, null, PlayerSkin.Model.SLIM, true);

    private static PlayerSkin skin(VisiteurParc v) {
        return v.role() == VisiteurParc.RACHIDA ? RACHIDA : DefaultPlayerSkin.get(v.getUUID());
    }

    @Override
    public void render(VisiteurParc v, float lacet, float partiel, PoseStack ps, MultiBufferSource buffers, int lumiere) {
        model = skin(v).model() == PlayerSkin.Model.SLIM ? fin : large;
        model.rightArmPose = v.getMainHandItem().isEmpty() ? HumanoidModel.ArmPose.EMPTY : HumanoidModel.ArmPose.ITEM;
        super.render(v, lacet, partiel, ps, buffers, lumiere);
    }

    @Override
    public ResourceLocation getTextureLocation(VisiteurParc v) {
        return skin(v).texture();
    }

    @Override
    protected boolean shouldShowName(VisiteurParc v) {
        return v.role() == VisiteurParc.RACHIDA;
    }

    @EventBusSubscriber(modid = Cabane.MODID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
    public static final class Enregistrement {
        @SubscribeEvent
        public static void rendus(EntityRenderersEvent.RegisterRenderers event) {
            event.registerEntityRenderer(Parc.VISITEUR.get(), RenduVisiteurParc::new);
        }
    }
}
