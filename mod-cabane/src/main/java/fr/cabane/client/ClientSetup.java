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
        event.registerBlockEntityRenderer(CabaneBlocks.ETAL_BE.get(), EtalRenderer::new);
        event.registerBlockEntityRenderer(CabaneBlocks.INTERACTIF_BE.get(), InteractifRenderer::new);
    }

    @SubscribeEvent
    public static void onMenuScreens(RegisterMenuScreensEvent event) {
        event.register(CabaneBlocks.ETAL_MENU.get(), ClientEtal.GestionScreen::new);
    }

    private ClientSetup() {
    }
}
