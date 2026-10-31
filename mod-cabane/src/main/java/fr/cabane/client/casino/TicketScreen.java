package fr.cabane.client.casino;

import fr.cabane.casino.Ticket;
import fr.cabane.client.ui.Ui;
import fr.cabane.economie.Economie;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;

/** Le ticket à gratter : clique sur les cases (ou « Tout gratter »). Trois montants identiques = gagné. */
public class TicketScreen extends Screen {
    private static final int W = 220;
    private static final int H = 210;
    private static final int CASE = 56;

    private final Ticket.Gratte ticket;
    private final boolean[] grattee = new boolean[9];
    private boolean fini;
    private int left;
    private int top;

    TicketScreen(Ticket.Gratte ticket) {
        super(Component.literal("Ticket à gratter"));
        this.ticket = ticket;
    }

    @Override
    protected void init() {
        left = (width - W) / 2;
        top = (height - H) / 2;
        addRenderableWidget(Button.builder(Component.literal("Tout gratter"), b -> {
            for (int i = 0; i < 9; i++) {
                gratter(i);
            }
        }).bounds(left + W / 2 - 50, top + H - 24, 100, 18).build());
    }

    private void gratter(int i) {
        if (grattee[i]) {
            return;
        }
        grattee[i] = true;
        Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.BRUSH_GENERIC, 1.4F));
        boolean tout = true;
        for (boolean b : grattee) {
            tout &= b;
        }
        if (tout && !fini) {
            fini = true;
            Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(
                    ticket.gain() > 0 ? SoundEvents.PLAYER_LEVELUP : SoundEvents.NOTE_BLOCK_BASS.value(), 1F));
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        for (int i = 0; i < 9; i++) {
            int x = left + 20 + (i % 3) * (CASE + 4);
            int y = top + 30 + (i / 3) * (CASE + 4);
            if (mouseX >= x && mouseX < x + CASE && mouseY >= y && mouseY < y + CASE) {
                gratter(i);
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        mouseClicked(mouseX, mouseY, button);
        return true;
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.renderBackground(g, mouseX, mouseY, partialTick);
        Ui.panneau(g, left, top, W, H);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        Ui.titre(g, font, "Ticket à gratter", left + 10, top + 10);
        for (int i = 0; i < 9; i++) {
            int x = left + 20 + (i % 3) * (CASE + 4);
            int y = top + 30 + (i / 3) * (CASE + 4);
            if (grattee[i]) {
                g.fill(x, y, x + CASE, y + CASE, 0xFFF4EFE6);
                g.drawCenteredString(font, Economie.format(ticket.cases().get(i)), x + CASE / 2, y + CASE / 2 - 4, 0xFF7D5A00);
            } else {
                g.fill(x, y, x + CASE, y + CASE, 0xFFB8BCC4);
                for (int k = 0; k < CASE; k += 6) {
                    g.fill(x + k, y, x + k + 3, y + CASE, 0xFFA5AAB3);
                }
                g.drawCenteredString(font, "?", x + CASE / 2, y + CASE / 2 - 4, 0xFF6B7280);
            }
        }
        if (fini) {
            String msg = ticket.gain() > 0 ? "Gagné : " + Economie.format(ticket.gain()) + " !" : "Pas de chance…";
            g.drawCenteredString(font, msg, left + W / 2, top + H - 38, ticket.gain() > 0 ? Ui.VERT : Ui.ROUGE);
        } else {
            g.drawCenteredString(font, "3 montants identiques = gagné", left + W / 2, top + H - 38, Ui.GRIS);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
