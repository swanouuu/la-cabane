package fr.cabane.client.casino;

import fr.cabane.casino.Casino;
import fr.cabane.client.ClientEconomie;
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

/**
 * Base des écrans de jeu : choix de la mise, bouton « Jouer », animation puis résultat.
 * Les sous-classes dessinent leur jeu et animent le tirage reçu du serveur.
 */
public abstract class JeuScreen extends Screen {
    protected static final int W = 320;
    protected static final int H = 230;
    private static long derniereMise = 10;

    protected final int jeu;
    protected long mise = derniereMise;
    protected int left;
    protected int top;
    protected List<Integer> tirage;
    protected long gain;
    protected int animation = -1;
    protected String message = "";
    protected int couleurMessage = Ui.GRIS;
    private Button jouer;

    protected JeuScreen(String titre, int jeu) {
        super(Component.literal(titre));
        this.jeu = jeu;
    }

    /** Durée de l'animation en ticks. */
    protected abstract int dureeAnimation();

    /** Le choix envoyé au serveur (pari, côté de la pièce...). -1 si rien n'est choisi. */
    protected abstract int choix();

    protected abstract void dessinerJeu(GuiGraphics g, int mouseX, int mouseY, float partialTick);

    protected void ajouterBoutons() {
    }

    protected String nomChoix() {
        return "";
    }

    @Override
    protected void init() {
        left = (width - W) / 2;
        top = (height - H) / 2;
        int y = top + H - 26;
        addRenderableWidget(Button.builder(Component.literal("-100"), b -> changerMise(-100)).bounds(left + 8, y, 32, 18).build());
        addRenderableWidget(Button.builder(Component.literal("-10"), b -> changerMise(-10)).bounds(left + 42, y, 28, 18).build());
        addRenderableWidget(Button.builder(Component.literal("+10"), b -> changerMise(10)).bounds(left + 136, y, 28, 18).build());
        addRenderableWidget(Button.builder(Component.literal("+100"), b -> changerMise(100)).bounds(left + 166, y, 32, 18).build());
        jouer = addRenderableWidget(Button.builder(Component.literal("Jouer"), b -> jouer()).bounds(left + W - 110, y, 102, 18).build());
        ajouterBoutons();
    }

    private void changerMise(long delta) {
        mise = Math.max(Casino.MISE_MIN, Math.min(Casino.MISE_MAX, mise + delta));
        derniereMise = mise;
    }

    protected void jouer() {
        if (animation >= 0) {
            return;
        }
        if (choix() < 0) {
            message = "Choisis d'abord sur quoi tu paries.";
            couleurMessage = Ui.ROUGE;
            return;
        }
        if (ClientEconomie.solde >= 0 && ClientEconomie.solde < mise) {
            message = "Pas assez d'argent pour cette mise.";
            couleurMessage = Ui.ROUGE;
            return;
        }
        message = "";
        PacketDistributor.sendToServer(new Casino.Jouer(jeu, mise, choix()));
    }

    void resultat(List<Integer> tirage, long gain) {
        this.tirage = tirage;
        this.gain = gain;
        animation = 0;
        message = "…";
        couleurMessage = Ui.GRIS;
    }

    @Override
    public void tick() {
        super.tick();
        if (jouer != null) {
            jouer.active = animation < 0;
        }
        if (animation >= 0 && ++animation >= dureeAnimation()) {
            animation = -1;
            if (gain > 0) {
                message = "Gagné ! +" + Economie.format(gain);
                couleurMessage = Ui.VERT;
                son(true);
            } else {
                message = "Perdu… retente ta chance !";
                couleurMessage = Ui.ROUGE;
                son(false);
            }
        }
    }

    protected void son(boolean gagne) {
        Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(
                gagne ? SoundEvents.PLAYER_LEVELUP : SoundEvents.NOTE_BLOCK_BASS.value(), gagne ? 1.2F : 0.6F));
    }

    protected float progression(float partialTick) {
        return animation < 0 ? 1F : Math.min(1F, (animation + partialTick) / dureeAnimation());
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.renderBackground(g, mouseX, mouseY, partialTick);
        Ui.panneau(g, left, top, W, H);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        Ui.titre(g, font, title.getString(), left + 10, top + 10);
        Ui.solde(g, font, left + W - 10, top + 10);
        dessinerJeu(g, mouseX, mouseY, partialTick);
        g.drawCenteredString(font, "Mise", left + 103, top + H - 36, Ui.GRIS);
        g.drawCenteredString(font, Economie.format(mise), left + 103, top + H - 21, Ui.OR);
        String pari = nomChoix();
        if (!pari.isEmpty()) {
            g.drawString(font, "Pari : " + pari, left + W - 110, top + H - 36, Ui.GRIS, false);
        }
        g.drawCenteredString(font, message, left + W / 2, top + H - 50, couleurMessage);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    protected static float adoucir(float t) {
        float u = 1 - t;
        return 1 - u * u * u;
    }
}
