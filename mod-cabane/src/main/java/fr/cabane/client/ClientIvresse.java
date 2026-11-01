package fr.cabane.client;

import fr.cabane.Cabane;
import fr.cabane.boisson.Boissons;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ViewportEvent;

/** Quand on a trop bu, la caméra tangue doucement, et de plus en plus fort à chaque verre. */
@EventBusSubscriber(modid = Cabane.MODID, value = Dist.CLIENT)
public final class ClientIvresse {
    private static float force;

    @SubscribeEvent
    public static void onCamera(ViewportEvent.ComputeCameraAngles event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return;
        }
        int niveau = Boissons.niveau(mc.player);
        // la force suit le niveau en douceur : pas de saut brutal quand on boit ou que ça passe
        float cible = niveau < 0 ? 0F : 1.2F + niveau * 1.1F;
        force += (cible - force) * 0.02F;
        if (force < 0.01F) {
            return;
        }
        double t = (mc.player.tickCount + event.getPartialTick()) * 0.045;
        event.setRoll(event.getRoll() + (float) (Math.sin(t) * force * 1.6));
        event.setYaw(event.getYaw() + (float) (Math.sin(t * 0.7 + 1.3) * force * 0.9));
        event.setPitch(event.getPitch() + (float) (Math.cos(t * 0.9) * force * 0.5));
    }

    private ClientIvresse() {
    }
}
