package fr.cabane.client.donjon;

import fr.cabane.Cabane;
import fr.cabane.donjon.Donjon;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;

/** Les dessins des boss des donjons tracés. */
@EventBusSubscriber(modid = Cabane.MODID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
public final class EnregistrementBoss {
    @SubscribeEvent
    public static void rendus(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(Donjon.COLOSSE.get(), RenduColosse::new);
        event.registerEntityRenderer(Donjon.REINE.get(), RenduReine::new);
        event.registerEntityRenderer(Donjon.LICHE.get(), RenduLiche::new);
        event.registerEntityRenderer(Donjon.ROI_FOURMI.get(), RenduRoiFourmi::new);
    }

    private EnregistrementBoss() {
    }
}
