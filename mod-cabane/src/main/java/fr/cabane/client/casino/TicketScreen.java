package fr.cabane.client.casino;

import fr.cabane.casino.Ticket;
import fr.cabane.client.ui.Ios;
import fr.cabane.client.ui.Ui;
import fr.cabane.economie.Economie;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;

/**
 * Le ticket à gratter : on gratte vraiment, clic maintenu, la couche argentée part sous la souris.
 * Une case grattée aux deux tiers se dévoile entière. Trois montants identiques : gagné.
 */
public class TicketScreen extends Screen {
    private static final int W = 230;
    private static final int H = 250;
    private static final int CASE = 56;
    private static final int PIX = 2;
    private static final int N = CASE / PIX;

    private final Ticket.Gratte ticket;
    private final boolean[][] masque = new boolean[9][N * N];
    private final boolean[] devoilee = new boolean[9];
    private final List<float[]> etincelles = new ArrayList<>();
    private boolean fini;
    private long finiDepuis;
    private double dernierX = -1;
    private double dernierY = -1;
    private int sonDans;
    private int left;
    private int top;

    public TicketScreen(Ticket.Gratte ticket) {
        super(Component.literal("Ticket à gratter"));
        this.ticket = ticket;
    }

    @Override
    protected void init() {
        left = (width - W) / 2;
        top = (height - H) / 2;
        addRenderableWidget(Ios.Bouton.plein(left + W / 2 - 55, top + H - 26, 110, 18, "Tout gratter", 0xFF5B3A8C, () -> {
            for (int i = 0; i < 9; i++) {
                devoiler(i);
            }
        }));
    }

    private int caseX(int i) {
        return left + (W - 3 * CASE - 8) / 2 + (i % 3) * (CASE + 4);
    }

    private int caseY(int i) {
        return top + 46 + (i / 3) * (CASE + 4);
    }

    /** Gratte un petit disque autour du point, dans la case qui le contient. */
    private void gratterAutour(double mx, double my) {
        for (int i = 0; i < 9; i++) {
            if (devoilee[i]) {
                continue;
            }
            int x0 = caseX(i);
            int y0 = caseY(i);
            if (mx < x0 - 6 || mx > x0 + CASE + 6 || my < y0 - 6 || my > y0 + CASE + 6) {
                continue;
            }
            int cx = (int) ((mx - x0) / PIX);
            int cy = (int) ((my - y0) / PIX);
            for (int dy = -3; dy <= 3; dy++) {
                for (int dx = -3; dx <= 3; dx++) {
                    int px = cx + dx;
                    int py = cy + dy;
                    if (dx * dx + dy * dy <= 10 && px >= 0 && py >= 0 && px < N && py < N) {
                        masque[i][py * N + px] = true;
                    }
                }
            }
            int gratte = 0;
            for (boolean b : masque[i]) {
                gratte += b ? 1 : 0;
            }
            if (gratte > N * N * 0.6) {
                devoiler(i);
            }
            if (--sonDans <= 0) {
                sonDans = 4;
                Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.BRUSH_GENERIC, 1.6F, 0.35F));
            }
        }
    }

    /** Pour les captures : quelques cases grattées, une à moitié. */
    public void demo() {
        init(Minecraft.getInstance(), width, height);
        for (int i : new int[] {0, 2, 4}) {
            devoiler(i);
        }
        int x = caseX(5);
        int y = caseY(5);
        for (int k = 0; k < 40; k++) {
            gratterAutour(x + 4 + k, y + 8 + (k % 7) * 3);
            gratterAutour(x + 4 + k, y + 30 + (k % 5) * 2);
        }
    }

    private void devoiler(int i) {
        if (devoilee[i]) {
            return;
        }
        devoilee[i] = true;
        boolean tout = true;
        for (boolean b : devoilee) {
            tout &= b;
        }
        if (tout && !fini) {
            fini = true;
            finiDepuis = System.currentTimeMillis();
            Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(
                    ticket.gain() > 0 ? SoundEvents.PLAYER_LEVELUP : SoundEvents.NOTE_BLOCK_BASS.value(), 1F));
            if (ticket.gain() > 0) {
                java.util.Random r = new java.util.Random();
                for (int k = 0; k < 90; k++) {
                    double a = r.nextDouble() * Math.PI * 2;
                    float v = 0.8F + r.nextFloat() * 2.4F;
                    etincelles.add(new float[] {left + W / 2F, top + H / 2F, (float) Math.cos(a) * v, (float) Math.sin(a) * v - 1F, r.nextBoolean() ? 1 : 0});
                }
            }
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (super.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }
        dernierX = mouseX;
        dernierY = mouseY;
        gratterAutour(mouseX, mouseY);
        return true;
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        // on gratte aussi entre deux positions de la souris, pour un trait continu
        double dx = mouseX - dernierX;
        double dy = mouseY - dernierY;
        int pas = Math.max(1, (int) (Math.hypot(dx, dy) / 2));
        for (int k = 1; k <= pas; k++) {
            gratterAutour(dernierX + dx * k / pas, dernierY + dy * k / pas);
        }
        dernierX = mouseX;
        dernierY = mouseY;
        return true;
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.renderBackground(g, mouseX, mouseY, partialTick);
        // le ticket : carton bleu nuit et violet, bordure dorée
        Ios.rond(g, left - 3, top - 3, W + 6, H + 6, 10, CasinoUi.OR);
        for (int dy = 0; dy < H; dy++) {
            int c = CasinoUi.melange(0xFF1B1F6B, 0xFF5B1F7A, dy / (float) H);
            int r = dy < 8 ? 8 - (int) Math.sqrt(64 - (8 - dy) * (8 - dy)) : dy > H - 9 ? 8 - (int) Math.sqrt(64 - (dy - H + 9) * (dy - H + 9)) : 0;
            g.fill(left + r, top + dy, left + W - r, top + dy + 1, c);
        }
        for (int k = 0; k < 14; k++) {
            int sx = left + 8 + (k * 53) % (W - 16);
            int sy = top + 8 + (k * 37) % 30;
            g.fill(sx, sy, sx + 1, sy + 1, 0xA0FFFFFF);
        }
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        Ios.texteCentre(g, font, "BEAUVAIS MILLIONS", left + W / 2F, top + 10, 1.4F, CasinoUi.OR, true);
        Ios.texteCentre(g, font, "3 montants identiques = gagné", left + W / 2F, top + 28, 0.75F, 0xFFD8CCFF, false);
        for (int i = 0; i < 9; i++) {
            int x = caseX(i);
            int y = caseY(i);
            // dessous : le montant
            int montant = ticket.cases().get(i);
            boolean gagnant = fini && ticket.gain() > 0 && montant == ticket.gain();
            Ios.rond(g, x, y, CASE, CASE, 6, gagnant ? 0xFFFFE07A : 0xFFFFF6DA);
            CasinoUi.jeton(g, font, x + CASE / 2, y + 20, 11, montant >= 500 ? 0xFF1F1F1F : montant >= 100 ? 0xFF6A1B9A : 0xFFC62828, "$");
            Ios.texteCentre(g, font, Economie.format(montant), x + CASE / 2F, y + CASE - 18, montant >= 1000 ? 0.9F : 1.1F, 0xFF7D5A00, true);
            if (devoilee[i]) {
                continue;
            }
            // dessus : la couche argentée, avec son grain, sauf là où on a gratté
            boolean[] m = masque[i];
            for (int py = 0; py < N; py++) {
                for (int px = 0; px < N; px++) {
                    if (m[py * N + px]) {
                        continue;
                    }
                    int h = (px * 73856093 ^ py * 19349663 ^ i * 83492791) & 0x1F;
                    int c = 0xFF000000 | (0xA8 + h) << 16 | (0xAC + h) << 8 | (0xB6 + h);
                    g.fill(x + px * PIX, y + py * PIX, x + px * PIX + PIX, y + py * PIX + PIX, c);
                }
            }
            if (!m[(N / 2) * N + N / 2]) {
                Ios.texteCentre(g, font, "?", x + CASE / 2F, y + CASE / 2F - 5, 1.4F, 0x806B7280, true);
            }
        }
        long t = System.currentTimeMillis();
        if (t - finiDepuis > 1600) {
            etincelles.clear();
        }
        for (float[] e : etincelles) {
            float age = (t - finiDepuis) / 16F;
            int x = (int) (e[0] + e[2] * age);
            int y = (int) (e[1] + e[3] * age + 0.03F * age * age);
            g.fill(x, y, x + 2, y + 2, e[4] > 0 ? 0xFFFFD75E : 0xFFFFFFFF);
        }
        if (fini) {
            String msg = ticket.gain() > 0 ? "Gagné : " + Economie.format(ticket.gain()) + " !" : "Pas de chance…";
            Ios.texteCentre(g, font, msg, left + W / 2F, top + H - 44, 1.3F, ticket.gain() > 0 ? Ui.VERT : 0xFFFF8A8A, true);
        } else {
            Ios.texteCentre(g, font, "Maintiens le clic et gratte !", left + W / 2F, top + H - 42, 0.8F, 0xFFD8CCFF, false);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
