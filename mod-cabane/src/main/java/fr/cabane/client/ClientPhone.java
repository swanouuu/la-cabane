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

    static java.util.List<PhoneNetwork.Contact> contacts = java.util.List.of();
    static java.util.List<PhoneNetwork.Appel> recents = java.util.List.of();

    public static void onCarnet(PhoneNetwork.Carnet carnet) {
        contacts = carnet.contacts();
        recents = carnet.recents();
        if (Minecraft.getInstance().screen instanceof ClientPiaf.Rafraichissable r) {
            r.rafraichir();
        }
    }

    /** Le nom enregistré pour ce numéro, ou le numéro lui-même. */
    static String nom(String numero) {
        return contacts.stream().filter(c -> c.numero().equals(numero)).map(PhoneNetwork.Contact::nom).findFirst().orElse(numero);
    }

    /** Lance un appel et ouvre l'écran des appels. */
    static void appeler(String numero) {
        send(PhoneNetwork.Action.APPELER, numero);
        Minecraft.getInstance().setScreen(new PhoneScreen());
    }

    public static void onState(PhoneNetwork.State payload) {
        myNumber = payload.myNumber();
        state = payload.state();
        other = payload.other();
        if (Minecraft.getInstance().screen instanceof PhoneScreen screen) {
            screen.rafraichir();
        }
    }

    /** Ouvre l'accueil du téléphone, ou directement les appels si ça sonne ou si on est en ligne. */
    public static void openScreen() {
        boolean appel = state == PhoneNetwork.State.APPEL_ENTRANT || state == PhoneNetwork.State.EN_LIGNE;
        Minecraft.getInstance().setScreen(appel ? new PhoneScreen() : new ClientPiaf.Accueil());
        send(PhoneNetwork.Action.ETAT, "");
    }

    static void send(int action, String arg) {
        PacketDistributor.sendToServer(new PhoneNetwork.Action(action, arg));
    }

    @EventBusSubscriber(modid = Cabane.MODID, value = Dist.CLIENT)
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
