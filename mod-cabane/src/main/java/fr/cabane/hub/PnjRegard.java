package fr.cabane.hub;

import fr.cabane.Cabane;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;

/**
 * Les PNJ du mod regardent là où on les a posés. On les pose avec moveTo, qui ne tourne que l'entité : leur corps et leur
 * tête resteraient vers le sud, et leur tête y reviendrait après avoir regardé un joueur. Quand un PNJ arrive dans le
 * monde (tout juste posé, ou rechargé avec son chunk), son corps et sa tête prennent donc sa direction.
 */
@EventBusSubscriber(modid = Cabane.MODID)
public final class PnjRegard {
    @SubscribeEvent
    public static void onArrivee(EntityJoinLevelEvent event) {
        if (!event.getLevel().isClientSide() && event.getEntity() instanceof LivingEntity pnj && pnj.getTags().contains(PnjImmortels.TAG)) {
            pnj.setYBodyRot(pnj.getYRot());
            pnj.setYHeadRot(pnj.getYRot());
        }
    }

    private PnjRegard() {
    }
}
