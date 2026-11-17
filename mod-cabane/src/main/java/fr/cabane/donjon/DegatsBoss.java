package fr.cabane.donjon;

import fr.cabane.Cabane;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;

/**
 * Les boss des donjons tapent plus fort : tout ce qu'ils font aux joueurs (coups, attaques de zone, projectiles) est
 * multiplié par {@link #FORCE}. Ça vaut pour tous les boss d'un coup (Ugrak et les boss de {@link BossDonjon}), pas
 * pour leurs sbires.
 */
@EventBusSubscriber(modid = Cabane.MODID)
public final class DegatsBoss {
    /** De combien les boss tapent plus fort qu'avant. */
    public static final float FORCE = 1.5F;

    @SubscribeEvent
    public static void coup(LivingIncomingDamageEvent event) {
        if (event.getEntity() instanceof Player && (event.getSource().getEntity() instanceof BossDonjon || event.getSource().getEntity() instanceof Ogre)) {
            event.setAmount(event.getAmount() * FORCE);
        }
    }

    private DegatsBoss() {
    }
}
