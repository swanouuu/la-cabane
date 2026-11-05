package fr.cabane.client.pouvoir;

import fr.cabane.Cabane;
import fr.cabane.client.hachibi4.ClientHachibi4;
import fr.cabane.pouvoir.Geants;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
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
        @SubscribeEvent
        public static void clic(InputEvent.InteractionKeyMappingTriggered event) {
            Minecraft mc = Minecraft.getInstance();
            if (!event.isAttack() || mc.player == null || mc.level == null || mc.hitResult != null && mc.hitResult.getType() == HitResult.Type.ENTITY) {
                return;
            }
            Vec3 oeil = mc.player.getEyePosition();
            double portee = mc.player.entityInteractionRange() + 1.5;
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
                event.setCanceled(true);
                event.setSwingHand(true);
                PacketDistributor.sendToServer(new Geants.Frappe(vise.getId()));
                mc.player.resetAttackStrengthTicker();
            }
        }

        private Evenements() {
        }
    }

    private ClientGeants() {
    }
}
