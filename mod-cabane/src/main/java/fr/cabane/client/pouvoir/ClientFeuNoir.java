package fr.cabane.client.pouvoir;

import fr.cabane.Cabane;
import fr.cabane.pouvoir.FeuNoir;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterColorHandlersEvent;

/**
 * Les flammes noires posées au sol : le feu de Minecraft, teint presque en noir (un soupçon de violet, pour que leurs
 * langues se détachent encore les unes des autres).
 */
@EventBusSubscriber(modid = Cabane.MODID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
public final class ClientFeuNoir {
    private static final int TEINTE = 0x16121C;

    @SubscribeEvent
    public static void couleurs(RegisterColorHandlersEvent.Block event) {
        event.register((etat, monde, pos, indice) -> TEINTE, FeuNoir.FEU.get());
    }

    private ClientFeuNoir() {
    }
}
