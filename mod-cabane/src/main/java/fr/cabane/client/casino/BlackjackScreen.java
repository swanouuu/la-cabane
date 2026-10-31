package fr.cabane.client.casino;

import fr.cabane.casino.Blackjack;
import fr.cabane.casino.Casino;
import fr.cabane.client.ui.Ui;
import fr.cabane.economie.Economie;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.neoforged.neoforge.network.PacketDistributor;

/** Table de blackjack : la banque en haut, ta main en bas. */
public class BlackjackScreen extends Screen {
    private static final int W = 320;
    private static final int H = 230;
    private static final String[] RANGS = {"A", "2", "3", "4", "5", "6", "7", "8", "9", "10", "V", "D", "R"};
    private static final String[] COULEURS = {"♠", "♥", "♦", "♣"};

    private Blackjack.Etat etat;
    private long mise = 10;
    private int left;
    private int top;
    private Button distribuer;
    private Button tirer;
    private Button rester;
    private Button doubler;

    BlackjackScreen(Blackjack.Etat etat) {
        super(Component.literal("Blackjack"));
        this.etat = etat;
    }

    void etat(Blackjack.Etat nouvel) {
        boolean finie = nouvel.etat() != Blackjack.EN_COURS && nouvel.etat() != Blackjack.AUCUNE;
        if (finie && (etat == null || etat.etat() == Blackjack.EN_COURS)) {
            boolean gagne = nouvel.gain() > nouvel.mise() || nouvel.etat() == Blackjack.BLACKJACK;
            Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(
                    gagne ? SoundEvents.PLAYER_LEVELUP : SoundEvents.NOTE_BLOCK_BASS.value(), 1F));
        }
        etat = nouvel;
        majBoutons();
    }

    @Override
    protected void init() {
        left = (width - W) / 2;
        top = (height - H) / 2;
        int y = top + H - 26;
        addRenderableWidget(Button.builder(Component.literal("-10"), b -> mise = Math.max(Casino.MISE_MIN, mise - 10)).bounds(left + 8, y, 28, 18).build());
        addRenderableWidget(Button.builder(Component.literal("+10"), b -> mise = Math.min(Casino.MISE_MAX, mise + 10)).bounds(left + 100, y, 28, 18).build());
        distribuer = addRenderableWidget(Button.builder(Component.literal("Distribuer"), b -> action(Blackjack.DISTRIBUER)).bounds(left + 136, y, 70, 18).build());
        tirer = addRenderableWidget(Button.builder(Component.literal("Tirer"), b -> action(Blackjack.TIRER)).bounds(left + 136, y, 56, 18).build());
        rester = addRenderableWidget(Button.builder(Component.literal("Rester"), b -> action(Blackjack.RESTER)).bounds(left + 196, y, 56, 18).build());
        doubler = addRenderableWidget(Button.builder(Component.literal("Doubler"), b -> action(Blackjack.DOUBLER)).bounds(left + 256, y, 56, 18).build());
        majBoutons();
    }

    private boolean enCours() {
        return etat != null && etat.etat() == Blackjack.EN_COURS;
    }

    private void majBoutons() {
        if (distribuer == null) {
            return;
        }
        distribuer.visible = !enCours();
        tirer.visible = enCours();
        rester.visible = enCours();
        doubler.visible = enCours();
        doubler.active = enCours() && etat.joueur().size() == 2;
    }

    private void action(int action) {
        PacketDistributor.sendToServer(new Blackjack.Action(action, mise, ClientCasino.machine));
    }

    private void carte(GuiGraphics g, int x, int y, int c, boolean dos) {
        g.fill(x - 1, y - 1, x + 29, y + 41, 0xFF0B0C10);
        if (dos) {
            g.fill(x, y, x + 28, y + 40, 0xFF1F4E8C);
            g.fill(x + 3, y + 3, x + 25, y + 37, 0xFF2E6BB8);
            return;
        }
        g.fill(x, y, x + 28, y + 40, 0xFFFAFAF5);
        int couleur = (c / 13 == 1 || c / 13 == 2) ? 0xFFC0392B : 0xFF1C1C1C;
        g.drawString(font, RANGS[c % 13], x + 3, y + 3, couleur, false);
        g.drawCenteredString(font, COULEURS[c / 13], x + 14, y + 17, couleur);
    }

    private void main(GuiGraphics g, List<Integer> cartes, int y, boolean dosCache, String nom) {
        int total = cartes.size() + (dosCache ? 1 : 0);
        int x0 = left + W / 2 - total * 17;
        for (int i = 0; i < cartes.size(); i++) {
            carte(g, x0 + i * 34, y, cartes.get(i), false);
        }
        if (dosCache) {
            carte(g, x0 + cartes.size() * 34, y, 0, true);
        }
        String valeur = cartes.isEmpty() ? "" : " : " + Blackjack.valeur(cartes) + (dosCache ? " + ?" : "");
        g.drawString(font, nom + valeur, left + 12, y + 16, Ui.GRIS, false);
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.renderBackground(g, mouseX, mouseY, partialTick);
        CasinoUi.table(g, font, "Blackjack", left, top, W, H);
        g.fill(left + 6, top + H - 34, left + W - 6, top + H - 33, 0x40E8C15A);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        Ui.titre(g, font, "Blackjack", left + 10, top + 10);
        Ui.solde(g, font, left + W - 10, top + 10);
        g.drawCenteredString(font, Economie.format(mise), left + 68, top + H - 21, Ui.OR);
        if (etat == null || etat.etat() == Blackjack.AUCUNE) {
            g.drawCenteredString(font, "Choisis ta mise puis « Distribuer ».", left + W / 2, top + 100, 0xFFFFFFFF);
            g.drawCenteredString(font, "La banque tire jusqu'à 17. Blackjack payé 3 pour 2.", left + W / 2, top + 114, Ui.GRIS);
            return;
        }
        main(g, etat.croupier(), top + 36, etat.cache(), "Banque");
        main(g, etat.joueur(), top + 130, false, "Toi");
        String msg = switch (etat.etat()) {
            case Blackjack.EN_COURS -> "Mise : " + Economie.format(etat.mise()) + " — tirer ou rester ?";
            case Blackjack.BLACKJACK -> "BLACKJACK ! +" + Economie.format(etat.gain());
            case Blackjack.GAGNE -> "Gagné ! +" + Economie.format(etat.gain());
            case Blackjack.EGALITE -> "Égalité, mise rendue.";
            default -> "Perdu…";
        };
        int couleur = etat.etat() == Blackjack.PERDU ? Ui.ROUGE : etat.etat() == Blackjack.EN_COURS ? 0xFFFFFFFF : Ui.VERT;
        g.drawCenteredString(font, msg, left + W / 2, top + 108, couleur);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
