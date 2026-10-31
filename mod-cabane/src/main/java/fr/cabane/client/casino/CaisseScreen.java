package fr.cabane.client.casino;

import fr.cabane.bloc.InteractifBlockEntity;
import fr.cabane.casino.Casino;
import fr.cabane.client.ui.Ios;
import fr.cabane.economie.Economie;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

/** La caisse d'une machine, pour son gérant : la remplir, récupérer l'argent. */
public class CaisseScreen extends Screen {
    private static final int W = 220;
    private static final int H = 150;
    private final BlockPos pos;
    private EditBox montant;
    private int left;
    private int top;

    public CaisseScreen(BlockPos pos) {
        super(Component.literal("Caisse"));
        this.pos = pos;
    }

    public static void ouvrir(BlockPos pos) {
        Minecraft.getInstance().setScreen(new CaisseScreen(pos));
    }

    private long caisse() {
        return minecraft.level != null && minecraft.level.getBlockEntity(pos) instanceof InteractifBlockEntity be ? be.caisse() : 0;
    }

    @Override
    protected void init() {
        left = (width - W) / 2;
        top = (height - H) / 2;
        montant = addRenderableWidget(new EditBox(font, left + 40, top + 70, W - 80, 16, Component.literal("montant")));
        montant.setFilter(s -> s.matches("\\d{0,9}"));
        montant.setHint(Component.literal("montant en $").withColor(Ios.GRIS));
        setInitialFocus(montant);
        addRenderableWidget(Ios.Bouton.plein(left + 20, top + 96, (W - 50) / 2, 22, "Remplir", 0xFF2E7D32, () -> envoyer(1)));
        addRenderableWidget(Ios.Bouton.plein(left + 30 + (W - 50) / 2, top + 96, (W - 50) / 2, 22, "Récupérer", CasinoUi.ROUGE, () -> envoyer(-1)));
        addRenderableWidget(Ios.Bouton.texte(left + 20, top + 124, W - 40, 12, "Tout récupérer", CasinoUi.OR, () -> {
            PacketDistributor.sendToServer(new Casino.Caisse(pos, -caisse()));
        }));
    }

    private void envoyer(int signe) {
        if (!montant.getValue().isEmpty()) {
            PacketDistributor.sendToServer(new Casino.Caisse(pos, signe * Long.parseLong(montant.getValue())));
            montant.setValue("");
        }
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.renderBackground(g, mouseX, mouseY, partialTick);
        CasinoUi.table(g, font, "Caisse de la machine", left, top, W, H);
        Ios.rond(g, left + 37, top + 66, W - 74, 24, 6, 0xFF0A2E18);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        Ios.texteCentre(g, font, "Dans la caisse", left + W / 2F, top + 18, 0.85F, 0xFFBFE8C8, false);
        Ios.texteCentre(g, font, Economie.format(caisse()), left + W / 2F, top + 32, 2F, CasinoUi.OR, true);
        Ios.texteCentre(g, font, "Les mises y entrent, les gains en sortent.", left + W / 2F, top + 54, 0.7F, 0xFFBFE8C8, false);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
