package fr.cabane.client.casino;

import fr.cabane.casino.Blackjack;
import fr.cabane.casino.Casino;
import fr.cabane.client.ui.Ios;
import fr.cabane.client.ui.Ui;
import fr.cabane.economie.Economie;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Le blackjack se joue à la grande table : les cartes sont posées sur le tapis, en 3D, devant chaque
 * joueur. Cet écran n'est qu'un bandeau en bas (mise, tirer, rester, doubler) pour garder la table en vue.
 */
public class BlackjackScreen extends Screen {
    private static final int W = 340;
    private static final int H = 74;

    private Blackjack.Etat etat;
    private long mise = 10;
    private int left;
    private int top;
    private Ios.Bouton distribuer;
    private Ios.Bouton tirer;
    private Ios.Bouton rester;
    private Ios.Bouton doubler;

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
        top = height - H - 6;
        int y = top + H - 24;
        addRenderableWidget(Ios.Bouton.plein(left + 8, y, 32, 18, "-100", 0xFF5A1414, () -> changerMise(-100)));
        addRenderableWidget(Ios.Bouton.plein(left + 42, y, 28, 18, "-10", 0xFF5A1414, () -> changerMise(-10)));
        addRenderableWidget(Ios.Bouton.plein(left + 132, y, 28, 18, "+10", 0xFF14402A, () -> changerMise(10)));
        addRenderableWidget(Ios.Bouton.plein(left + 162, y, 32, 18, "+100", 0xFF14402A, () -> changerMise(100)));
        distribuer = addRenderableWidget(Ios.Bouton.plein(left + W - 136, y, 128, 18, "★ DISTRIBUER ★", 0xFFB8862B, () -> action(Blackjack.DISTRIBUER)));
        tirer = addRenderableWidget(Ios.Bouton.plein(left + W - 142, y, 44, 18, "Tirer", 0xFF14402A, () -> action(Blackjack.TIRER)));
        rester = addRenderableWidget(Ios.Bouton.plein(left + W - 96, y, 44, 18, "Rester", 0xFF5A1414, () -> action(Blackjack.RESTER)));
        doubler = addRenderableWidget(Ios.Bouton.plein(left + W - 50, y, 44, 18, "×2", 0xFFB8862B, () -> action(Blackjack.DOUBLER)));
        majBoutons();
    }

    private void changerMise(long delta) {
        mise = Math.max(Casino.MISE_MIN, Math.min(Casino.MISE_MAX, mise + delta));
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

    private static String main(List<Integer> cartes, boolean cache) {
        return cartes.isEmpty() ? "?" : Blackjack.valeur(cartes) + (cache ? " + ?" : "");
    }

    /** Pas de flou ni de voile : la table reste visible derrière le bandeau. */
    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        Ios.rond(g, left - 2, top - 2, W + 4, H + 4, 10, 0xFFB8862B);
        Ios.rond(g, left, top, W, H, 9, 0xF0141A16);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        Ios.texte(g, font, "♠ BLACKJACK", left + 10, top + 8, 1F, 0xFFFFD75E, true);
        Ui.solde(g, font, left + W - 10, top + 7);
        g.drawCenteredString(font, Economie.format(mise), left + 101, top + H - 19, Ui.OR);
        String ligne;
        int couleur;
        if (etat == null || etat.etat() == Blackjack.AUCUNE) {
            ligne = "Choisis ta mise puis « Distribuer ». Tes cartes arrivent sur le tapis.";
            couleur = Ui.GRIS;
        } else {
            String mains = "Toi : " + main(etat.joueur(), false) + "   —   Banque : " + main(etat.croupier(), etat.cache());
            ligne = switch (etat.etat()) {
                case Blackjack.EN_COURS -> mains + "   —   tirer ou rester ?";
                case Blackjack.BLACKJACK -> "BLACKJACK ! +" + Economie.format(etat.gain());
                case Blackjack.GAGNE -> mains + "   —   Gagné ! +" + Economie.format(etat.gain());
                case Blackjack.EGALITE -> mains + "   —   Égalité, mise rendue.";
                default -> mains + "   —   Perdu…";
            };
            couleur = etat.etat() == Blackjack.PERDU ? Ui.ROUGE : etat.etat() == Blackjack.EN_COURS ? 0xFFFFFFFF : Ui.VERT;
        }
        g.drawCenteredString(font, ligne, left + W / 2, top + 26, couleur);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
