package fr.cabane.client;

import de.maxhenkel.corpse.gui.CorpseContainerBase;
import fr.cabane.Cabane;
import fr.cabane.hub.Corps;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/** Le bouton « Supprimer le corps » sous l'écran du corps, pour son propriétaire : deux clics, le stuff est perdu. */
@EventBusSubscriber(modid = Cabane.MODID, value = Dist.CLIENT)
public final class ClientCorps {
    private static final int LARGEUR = 150;
    private static final int HAUTEUR = 20;

    @SubscribeEvent
    public static void ecran(ScreenEvent.Init.Post event) {
        if (!(event.getScreen() instanceof AbstractContainerScreen<?> ecran) || !(ecran.getMenu() instanceof CorpseContainerBase menu) || menu.isHistory()) {
            return;
        }
        var joueur = Minecraft.getInstance().player;
        if (joueur == null || !Corps.proprietaire(menu.getCorpse(), joueur.getUUID())) {
            return;
        }
        int x = ecran.getGuiLeft() + (ecran.getXSize() - LARGEUR) / 2;
        int y = ecran.getGuiTop() + ecran.getYSize() + 4;
        if (y + HAUTEUR > ecran.height) {
            y = Math.max(2, ecran.getGuiTop() - HAUTEUR - 4);
        }
        boolean[] arme = {false};
        event.addListener(Button.builder(Component.literal("Supprimer le corps"), b -> {
            if (arme[0]) {
                PacketDistributor.sendToServer(new Corps.Supprimer());
            } else {
                arme[0] = true;
                b.setMessage(Component.literal("Sûr ? Le stuff sera perdu").withStyle(ChatFormatting.RED));
            }
        }).bounds(x, y, LARGEUR, HAUTEUR).build());
    }

    private ClientCorps() {
    }
}
