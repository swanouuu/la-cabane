package fr.cabane.client;

import fr.cabane.Cabane;
import fr.cabane.CabaneBlocks;
import fr.cabane.CabaneEntities;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;

/** Branche les rendus et écrans côté client. */
@EventBusSubscriber(modid = Cabane.MODID, value = Dist.CLIENT)
public final class ClientSetup {

    @SubscribeEvent
    public static void onRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(CabaneEntities.PARACHUTE.get(), ParachuteRenderer::new);
        event.registerEntityRenderer(CabaneEntities.VOITURE_POLICE.get(), VoitureRenderer::new);
        event.registerEntityRenderer(CabaneEntities.TABLEAU_PHOTO.get(), TableauPhotoRenderer::new);
        event.registerBlockEntityRenderer(CabaneBlocks.ETAL_BE.get(), EtalRenderer::new);
        event.registerBlockEntityRenderer(CabaneBlocks.INTERACTIF_BE.get(), InteractifRenderer::new);
    }

    @SubscribeEvent
    public static void onMenuScreens(RegisterMenuScreensEvent event) {
        event.register(CabaneBlocks.ETAL_MENU.get(), ClientEtal.GestionScreen::new);
    }

    /** H au volant de la voiture de police : la sirène. */
    public static final net.minecraft.client.KeyMapping SIRENE = new net.minecraft.client.KeyMapping("key.cabane.sirene",
            org.lwjgl.glfw.GLFW.GLFW_KEY_H, "key.categories.cabane");

    @SubscribeEvent
    public static void onKeys(net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent event) {
        event.register(SIRENE);
    }

    @EventBusSubscriber(modid = Cabane.MODID, value = Dist.CLIENT)
    public static final class Touches {
        @SubscribeEvent
        public static void onTick(net.neoforged.neoforge.client.event.ClientTickEvent.Post event) {
            var joueur = net.minecraft.client.Minecraft.getInstance().player;
            while (SIRENE.consumeClick()) {
                if (joueur != null && joueur.getVehicle() instanceof fr.cabane.police.VoitureEntity voiture && voiture.getControllingPassenger() == joueur) {
                    net.neoforged.neoforge.network.PacketDistributor.sendToServer(new fr.cabane.police.Police.Sirene());
                }
            }
        }

        private Touches() {
        }
    }

    private ClientSetup() {
    }
}
