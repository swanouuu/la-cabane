package fr.cabane.client.entrainement;

import fr.cabane.Cabane;
import fr.cabane.entrainement.Entrainement;
import fr.cabane.entrainement.PnjEntrainement;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;

/** Les élèves de la salle d'entraînement : le modèle du joueur, avec le skin qu'ils ont tiré au hasard. */
@EventBusSubscriber(modid = Cabane.MODID, value = Dist.CLIENT)
public final class ClientEntrainement {
    @SubscribeEvent
    public static void onRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(Entrainement.PNJ.get(), RenduEleve::new);
    }

    public static final class RenduEleve extends HumanoidMobRenderer<PnjEntrainement, PlayerModel<PnjEntrainement>> {
        public RenduEleve(EntityRendererProvider.Context ctx) {
            super(ctx, new PlayerModel<>(ctx.bakeLayer(ModelLayers.PLAYER), false), 0.5F);
        }

        @Override
        public ResourceLocation getTextureLocation(PnjEntrainement eleve) {
            ResourceLocation r = ResourceLocation.tryParse(eleve.skin());
            return r != null ? r : ResourceLocation.withDefaultNamespace("textures/entity/player/wide/steve.png");
        }
    }

    private ClientEntrainement() {
    }
}
