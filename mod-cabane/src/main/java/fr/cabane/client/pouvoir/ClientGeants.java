package fr.cabane.client.pouvoir;

import fr.cabane.Cabane;
import fr.cabane.client.ClientCamera;
import fr.cabane.client.hachibi4.ClientHachibi4;
import fr.cabane.pouvoir.Geants;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Viser un géant (le Hachibi entier, le Susano parfait) : un clic gauche qui tombe dans sa boîte frappe le joueur qui
 * est dedans (voir {@link Geants}), même s'il est tout petit au milieu.
 */
public final class ClientGeants {
    static int sorte(Player p) {
        if (ClientHachibi4.enUlti(p.getUUID())) {
            return Geants.HACHIBI;
        }
        return ClientSusano4.enForme(p.getUUID()) ? Geants.SUSANO : Geants.AUCUN;
    }

    @EventBusSubscriber(modid = Cabane.MODID, value = Dist.CLIENT)
    public static final class Evenements {
        /**
         * En dernier, et même quand un pouvoir a déjà pris le clic (le Susano, Hachibi, les fruits… annulent l'événement
         * pour frapper à leur façon) : leurs coups visent autour du petit joueur caché dans le géant et ne le trouvent
         * pas, alors c'est ici que le coup part sur le géant. Le serveur décide de la force (normale, ou celle du pouvoir).
         */
        @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
        public static void clic(InputEvent.InteractionKeyMappingTriggered event) {
            Minecraft mc = Minecraft.getInstance();
            if (!event.isAttack() || mc.player == null || mc.level == null || mc.screen != null || ClientCamera.sorti()
                    || mc.hitResult != null && mc.hitResult.getType() == HitResult.Type.ENTITY || sorte(mc.player) != Geants.AUCUN) {
                return;
            }
            boolean pouvoir = event.isCanceled();
            Vec3 oeil = mc.player.getEyePosition();
            double portee = mc.player.entityInteractionRange() + 1.5 + (pouvoir ? 5 : 0);
            Vec3 bout = oeil.add(mc.player.getViewVector(1F).scale(portee));
            double bloc = mc.hitResult != null && mc.hitResult.getType() == HitResult.Type.BLOCK ? mc.hitResult.getLocation().distanceTo(oeil) : Double.MAX_VALUE;
            Player vise = null;
            double plusPres = bloc;
            for (Player p : mc.level.players()) {
                int sorte = p == mc.player ? Geants.AUCUN : sorte(p);
                if (sorte == Geants.AUCUN) {
                    continue;
                }
                AABB b = Geants.boite(p.position(), sorte);
                double d;
                if (b.contains(oeil)) {
                    d = 0;
                } else {
                    Optional<Vec3> touche = b.clip(oeil, bout);
                    if (touche.isEmpty()) {
                        continue;
                    }
                    d = touche.get().distanceTo(oeil);
                }
                if (d < plusPres) {
                    plusPres = d;
                    vise = p;
                }
            }
            if (vise != null) {
                if (!pouvoir) {
                    // un coup normal : on le prend (le jeu, lui, ne voit que du vide ou un bloc)
                    event.setCanceled(true);
                    event.setSwingHand(true);
                    mc.player.resetAttackStrengthTicker();
                }
                PacketDistributor.sendToServer(new Geants.Frappe(vise.getId()));
            }
        }

        private Evenements() {
        }
    }

    private ClientGeants() {
    }
}
