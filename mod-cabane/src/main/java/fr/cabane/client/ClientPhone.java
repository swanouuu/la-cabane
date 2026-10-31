package fr.cabane.client;

import com.mojang.blaze3d.platform.InputConstants;
import fr.cabane.Cabane;
import fr.cabane.CabaneItems;
import fr.cabane.telephone.PhoneNetwork;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

/** Côté client : l'état du téléphone reçu du serveur et la touche N. */
public final class ClientPhone {
    public static final KeyMapping TOUCHE = new KeyMapping("key.cabane.telephone",
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_N, "key.categories.cabane");

    static String myNumber = "";
    static int state = PhoneNetwork.State.LIBRE;
    static String other = "";

    public static void onState(PhoneNetwork.State payload) {
        myNumber = payload.myNumber();
        state = payload.state();
        other = payload.other();
        if (Minecraft.getInstance().screen instanceof PhoneScreen screen) {
            screen.rafraichir();
        }
    }

    public static void openScreen() {
        Minecraft.getInstance().setScreen(new PhoneScreen());
        send(PhoneNetwork.Action.ETAT, "");
    }

    static void send(int action, String arg) {
        PacketDistributor.sendToServer(new PhoneNetwork.Action(action, arg));
    }

    @EventBusSubscriber(modid = Cabane.MODID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
    public static final class ModEvents {
        @SubscribeEvent
        public static void onRegisterKeys(RegisterKeyMappingsEvent event) {
            event.register(TOUCHE);
        }
    }

    @EventBusSubscriber(modid = Cabane.MODID, value = Dist.CLIENT)
    public static final class GameEvents {
        @SubscribeEvent
        public static void onClientTick(ClientTickEvent.Post event) {
            Minecraft mc = Minecraft.getInstance();
            while (TOUCHE.consumeClick()) {
                LocalPlayer player = mc.player;
                if (player == null || mc.screen != null) {
                    continue;
                }
                if (player.getInventory().hasAnyMatching(stack -> stack.is(CabaneItems.TELEPHONE.get()))) {
                    openScreen();
                } else {
                    player.displayClientMessage(net.minecraft.network.chat.Component.literal("☎ Tu n'as pas de téléphone sur toi."), true);
                }
            }
        }
    }

    private ClientPhone() {
    }
}
