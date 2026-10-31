package fr.cabane.client;

import fr.cabane.Cabane;
import fr.cabane.economie.Economie;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;

/** Le solde en SakaiDollars, affiché à droite de l'écran. */
@EventBusSubscriber(modid = Cabane.MODID, value = Dist.CLIENT)
public final class ClientEconomie {
    private static final ResourceLocation PIECE = Cabane.id("textures/gui/piece.png");
    public static long solde = -1;

    @SubscribeEvent
    public static void onRegisterLayers(RegisterGuiLayersEvent event) {
        event.registerAboveAll(Cabane.id("argent"), (g, delta) -> dessiner(g));
    }

    private static void dessiner(GuiGraphics g) {
        Minecraft mc = Minecraft.getInstance();
        if (solde < 0 || mc.options.hideGui || mc.getDebugOverlay().showDebugScreen() || mc.player == null) {
            return;
        }
        String texte = Economie.format(solde);
        int largeur = mc.font.width(texte) + 16;
        int x = g.guiWidth() - largeur - 4;
        int y = g.guiHeight() / 2 - 6;
        g.fill(x - 3, y - 3, x + largeur + 2, y + 12, 0x88000000);
        g.blit(PIECE, x, y, 0, 0, 10, 10, 10, 10);
        g.drawString(mc.font, texte, x + 13, y + 1, 0xFFFFD75E, true);
    }

    private ClientEconomie() {
    }
}
