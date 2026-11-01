package fr.cabane.hub;

import fr.cabane.Cabane;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent;

/**
 * Les PNJ du village : on peut les frapper (ils rougissent, crient, reculent), mais ils ne meurent jamais.
 * Easy NPC les garde invulnérables : c'est le mod qui joue le coup encaissé.
 */
@EventBusSubscriber(modid = Cabane.MODID)
public final class PnjImmortels {
    public static final String TAG = "cabane_pnj";

    @SubscribeEvent
    public static void onCoup(AttackEntityEvent event) {
        Player joueur = event.getEntity();
        if (!(event.getTarget() instanceof LivingEntity pnj) || !pnj.getTags().contains(TAG) || !(pnj.level() instanceof ServerLevel level)) {
            return;
        }
        if (joueur.getAttackStrengthScale(0.5F) < 0.3F) {
            return; // on ne spamme pas le clic
        }
        level.broadcastDamageEvent(pnj, joueur.damageSources().playerAttack(joueur));
        pnj.knockback(0.45, joueur.getX() - pnj.getX(), joueur.getZ() - pnj.getZ());
        pnj.hurtMarked = true;
    }

    @SubscribeEvent
    public static void onMort(LivingDeathEvent event) {
        LivingEntity pnj = event.getEntity();
        if (pnj.getTags().contains(TAG)) {
            event.setCanceled(true);
            pnj.setHealth(pnj.getMaxHealth());
        }
    }

    private PnjImmortels() {
    }
}
