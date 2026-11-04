package fr.cabane.client.musique;

import fr.cabane.client.musique.ClientMusique.Lecture;
import fr.cabane.client.musique.ClientMusique.Morceau;
import fr.cabane.client.ui.Ui;
import fr.cabane.musique.Musique;
import java.util.ArrayList;
import java.util.List;
import java.util.function.DoubleConsumer;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;

/**
 * Le menu Musique (Échap → ♪ Musique) : les catégories en onglets, leurs morceaux (un clic : ça joue), et en bas le
 * choix pour moi / autour de moi, la boucle, les deux volumes de chacun (sa musique, celle des autres) et l'arrêt. Le
 * jeu ne se met pas en pause
 * derrière : on entend le morceau pendant qu'on choisit.
 */
final class MusiqueScreen extends Screen {
    private static final int W = 320;
    private static final int H = 258;
    /** « 10 s » : ce que les boutons font avancer ou reculer (ticks). */
    private static final int SAUT = 200;
    private static final int LIGNES = 8;
    private static final int LIGNE = 14;
    /** La catégorie ouverte, gardée d'une fois sur l'autre. */
    private static String categorie;

    private final List<String> categories = new ArrayList<>();
    private final List<Morceau> morceaux = new ArrayList<>();
    private int left;
    private int top;
    private int defile;
    /** Où on tient le curseur de la barre (de 0 à 1), ou -1 quand on ne le tient pas. */
    private double glisse = -1;
    private Button reculer;
    private Button pauser;
    private Button avancer;
    private Button arreter;

    MusiqueScreen() {
        super(Component.literal("Musique"));
        ClientMusique.recharger();
    }

    @Override
    protected void init() {
        left = (width - W) / 2;
        top = (height - H) / 2;
        categories.clear();
        morceaux.clear();
        for (Morceau m : ClientMusique.catalogue()) {
            for (String c : m.categories()) {
                if (!categories.contains(c)) {
                    categories.add(c);
                }
            }
        }
        if (categorie == null || !categories.contains(categorie)) {
            categorie = categories.isEmpty() ? null : categories.get(0);
        }
        for (Morceau m : ClientMusique.catalogue()) {
            if (m.categories().contains(categorie)) {
                morceaux.add(m);
            }
        }
        defile = Mth.clamp(defile, 0, Math.max(0, morceaux.size() - LIGNES));

        // les onglets
        int n = categories.size();
        if (n > 0) {
            int largeur = (W - 16 - (n - 1) * 4) / n;
            for (int i = 0; i < n; i++) {
                String c = categories.get(i);
                Button onglet = Button.builder(choisi(c, c.equals(categorie)), b -> {
                    categorie = c;
                    defile = 0;
                    rebuildWidgets();
                }).bounds(left + 8 + i * (largeur + 4), top + 22, largeur, 16).build();
                onglet.active = !c.equals(categorie);
                addRenderableWidget(onglet);
            }
        }

        // sous la barre : reculer, pause, avancer, arrêter
        int yt = barreY() + 10;
        reculer = addRenderableWidget(Button.builder(Component.literal("« 10 s"), b -> ClientMusique.decaler(-SAUT)).bounds(left + 8, yt, 60, 20).build());
        pauser = addRenderableWidget(Button.builder(Component.empty(), b -> {
            if (ClientMusique.pause != null) {
                ClientMusique.reprendre();
            } else {
                ClientMusique.mettreEnPause();
            }
        }).bounds(left + 72, yt, 96, 20).build());
        avancer = addRenderableWidget(Button.builder(Component.literal("10 s »"), b -> ClientMusique.decaler(SAUT)).bounds(left + 172, yt, 60, 20).build());
        arreter = addRenderableWidget(Button.builder(Component.literal("■ Arrêter"), b -> ClientMusique.arreter()).bounds(left + 236, yt, 76, 20).build());
        tick();

        // en bas : pour qui, la boucle, les volumes
        int y = top + H - 50;
        Button moi = Button.builder(choisi("Pour moi", !ClientMusique.autour), b -> choisir(false)).bounds(left + 8, y, 80, 20).build();
        moi.active = ClientMusique.autour;
        addRenderableWidget(moi);
        Button autour = Button.builder(choisi("Autour de moi (" + Musique.RAYON + " blocs)", ClientMusique.autour), b -> choisir(true))
                .bounds(left + 92, y, 140, 20).build();
        autour.active = !ClientMusique.autour;
        addRenderableWidget(autour);
        addRenderableWidget(Button.builder(Component.literal("Boucle : " + (ClientMusique.boucle ? "oui" : "non")), b -> {
            ClientMusique.boucle = !ClientMusique.boucle;
            ClientMusique.relancer();
            rebuildWidgets();
        }).bounds(left + 236, y, 76, 20).build());
        ClientMusique.lireVolumes();
        Volume moiVolume = addRenderableWidget(new Volume(left + 8, y + 24, 150, "Ma musique", ClientMusique.volumeMoi, v -> ClientMusique.volumeMoi = v));
        moiVolume.setTooltip(Tooltip.create(Component.literal("Le volume de ta musique, pour toi seulement.")));
        Volume autresVolume = addRenderableWidget(new Volume(left + 162, y + 24, 150, "Les autres", ClientMusique.volumeAutres, v -> ClientMusique.volumeAutres = v));
        autresVolume.setTooltip(Tooltip.create(Component.literal("Le volume de la musique que les autres jouent autour d'eux. "
                + "Tout à gauche : tu ne l'entends plus. Ça ne change rien pour eux.")));
    }

    /** Le morceau qu'on joue ou qu'on a mis en pause, ou null. */
    private static Morceau mien() {
        if (ClientMusique.pause != null) {
            return ClientMusique.pause.morceau();
        }
        Lecture l = ClientMusique.maLecture();
        return l == null ? null : ClientMusique.morceau(l.id);
    }

    /** Où on en est dans {@link #mien()}. */
    private static long ou() {
        if (ClientMusique.pause != null) {
            return ClientMusique.pause.position();
        }
        Lecture l = ClientMusique.maLecture();
        return l == null ? 0 : ClientMusique.position(l);
    }

    /** Le bouton choisi est grisé (on ne reclique pas dessus) mais son texte reste en or, pour qu'on voie le choix. */
    private static Component choisi(String texte, boolean oui) {
        return oui ? Component.literal(texte).withStyle(ChatFormatting.GOLD) : Component.literal(texte);
    }

    private void choisir(boolean autour) {
        ClientMusique.autour = autour;
        ClientMusique.relancer();
        rebuildWidgets();
    }

    @Override
    public void tick() {
        boolean quelqueChose = mien() != null;
        reculer.active = quelqueChose;
        avancer.active = quelqueChose;
        pauser.active = quelqueChose;
        arreter.active = quelqueChose;
        pauser.setMessage(Component.literal(ClientMusique.pause != null ? "▶ Reprendre" : "|| Pause"));
    }

    @Override
    public void removed() {
        ClientMusique.garderVolumes();
    }

    /** Un volume du menu, de « coupée » à 100 %. */
    private static final class Volume extends AbstractSliderButton {
        private final String nom;
        private final DoubleConsumer changer;

        Volume(int x, int y, int largeur, String nom, double valeur, DoubleConsumer changer) {
            super(x, y, largeur, 20, Component.empty(), valeur);
            this.nom = nom;
            this.changer = changer;
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            setMessage(Component.literal(nom + " : " + (value <= 0 ? "coupée" : Math.round(value * 100) + " %")));
        }

        @Override
        protected void applyValue() {
            changer.accept(value);
        }
    }

    // ------------------------------------------------------------------ la liste

    private int listeY() {
        return top + 44;
    }

    /** Le morceau sous la souris, ou -1. */
    private int survole(double mx, double my) {
        if (mx < left + 8 || mx >= left + W - 8 || my < listeY() || my >= listeY() + LIGNES * LIGNE) {
            return -1;
        }
        int i = defile + (int) ((my - listeY()) / LIGNE);
        return i < morceaux.size() ? i : -1;
    }

    private int barreY() {
        return top + 174;
    }

    /** L'endroit du morceau sous la souris, sur la barre (de 0 à 1). */
    private double surBarre(double mx) {
        return Mth.clamp((mx - left - 8) / (W - 16), 0, 1);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int bouton) {
        if (super.mouseClicked(mx, my, bouton)) {
            return true;
        }
        int i = survole(mx, my);
        if (i >= 0 && bouton == 0) {
            ClientMusique.jouer(morceaux.get(i), 0);
            clic();
            return true;
        }
        // la barre : on attrape le curseur, le morceau y saute quand on lâche
        if (mien() != null && bouton == 0 && mx >= left + 6 && mx < left + W - 6 && my >= barreY() - 4 && my < barreY() + 7) {
            glisse = surBarre(mx);
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseDragged(double mx, double my, int bouton, double dx, double dy) {
        if (glisse >= 0) {
            glisse = surBarre(mx);
            return true;
        }
        return super.mouseDragged(mx, my, bouton, dx, dy);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int bouton) {
        if (glisse >= 0) {
            Morceau m = mien();
            if (m != null) {
                ClientMusique.aller(m, (long) (surBarre(mx) * m.duree()));
            }
            glisse = -1;
            return true;
        }
        return super.mouseReleased(mx, my, bouton);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double sx, double sy) {
        defile = Mth.clamp(defile - (int) Math.signum(sy), 0, Math.max(0, morceaux.size() - LIGNES));
        return true;
    }

    private static void clic() {
        Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1));
    }

    // ------------------------------------------------------------------ le dessin

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.renderBackground(g, mouseX, mouseY, partialTick);
        Ui.panneau(g, left, top, W, H);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        Ui.titre(g, font, "♪ Musique", left + 10, top + 8);
        int total = ClientMusique.catalogue().size();
        if (total > 0) {
            String compte = total + " morceau" + (total > 1 ? "x" : "");
            g.drawString(font, compte, left + W - 10 - font.width(compte), top + 8, Ui.GRIS, false);
        }

        Lecture mienne = ClientMusique.maLecture();
        Morceau courant = mien();
        int y0 = listeY();
        Ui.encart(g, left + 8, y0 - 2, W - 16, LIGNES * LIGNE + 4, 0xFF15171D);
        if (morceaux.isEmpty()) {
            g.drawCenteredString(font, "Aucune musique installée pour l'instant.", left + W / 2, y0 + 40, Ui.GRIS);
            g.drawCenteredString(font, "Elles arrivent avec le pack « Beauvais - voix ».", left + W / 2, y0 + 54, Ui.GRIS);
        }
        int sous = survole(mouseX, mouseY);
        for (int k = 0; k < LIGNES && defile + k < morceaux.size(); k++) {
            int i = defile + k;
            Morceau m = morceaux.get(i);
            int y = y0 + k * LIGNE;
            boolean joue = courant != null && courant.id().equals(m.id());
            if (joue) {
                g.fill(left + 9, y, left + W - 9, y + LIGNE - 1, 0x4030D158);
            } else if (i == sous) {
                g.fill(left + 9, y, left + W - 9, y + LIGNE - 1, 0x30FFFFFF);
            }
            String duree = temps(m.duree());
            int x = left + 14;
            int fin = left + W - 16 - font.width(duree) - 6;
            if (joue) {
                g.drawString(font, "▶", x, y + 3, Ui.VERT, false);
            }
            x += 10;
            if (!m.anime().isEmpty()) {
                String anime = Ui.couper(font, m.anime(), Math.min(110, fin - x));
                g.drawString(font, anime, x, y + 3, Ui.GRIS, false);
                x += font.width(anime) + 6;
            }
            g.drawString(font, Ui.couper(font, m.titre(), fin - x), x, y + 3, joue ? Ui.VERT : 0xFFFFFFFF, false);
            g.drawString(font, duree, left + W - 16 - font.width(duree), y + 3, Ui.GRIS, false);
        }
        // l'ascenseur
        if (morceaux.size() > LIGNES) {
            int h = LIGNES * LIGNE;
            int curseur = Math.max(10, h * LIGNES / morceaux.size());
            int yc = y0 + (h - curseur) * defile / (morceaux.size() - LIGNES);
            g.fill(left + W - 11, yc, left + W - 9, yc + curseur, 0x80FFFFFF);
        }

        // ce qui joue (ou ce qui est en pause), et la barre avec son curseur
        int yb = barreY();
        Morceau m = mien();
        if (m != null) {
            boolean enPause = ClientMusique.pause != null;
            long position = glisse >= 0 ? (long) (glisse * m.duree()) : ou();
            String etat = enPause ? "|| " : "▶ ";
            String pour = enPause ? "en pause" : mienne != null && mienne.solo ? "pour toi" : "autour de toi";
            String heure = temps(position) + " / " + temps(m.duree());
            int couleur = enPause ? Ui.OR : Ui.VERT;
            g.drawString(font, Ui.couper(font, etat + m.nom() + " (" + pour + ")", W - 30 - font.width(heure)), left + 10, yb - 12, couleur, false);
            g.drawString(font, heure, left + W - 10 - font.width(heure), yb - 12, Ui.GRIS, false);
            int x = left + 8 + (int) ((W - 16) * position / Math.max(1, m.duree()));
            g.fill(left + 8, yb, left + W - 8, yb + 3, 0xFF3A3F4B);
            g.fill(left + 8, yb, x, yb + 3, couleur);
            boolean dessus = glisse >= 0 || (mouseX >= left + 6 && mouseX < left + W - 6 && mouseY >= yb - 4 && mouseY < yb + 7);
            g.fill(x - 2, yb - 2, x + 2, yb + 5, dessus ? 0xFFFFFFFF : 0xFFD0D0D0);
        } else {
            Lecture voisin = ClientMusique.voisin();
            Morceau mv = voisin == null ? null : ClientMusique.morceau(voisin.id);
            if (mv != null) {
                Player p = Minecraft.getInstance().level.getPlayerByUUID(voisin.source);
                String qui = p instanceof AbstractClientPlayer j ? " (" + j.getGameProfile().getName() + ")" : "";
                g.drawString(font, Ui.couper(font, "♪ Tu entends : " + mv.nom() + qui, W - 20), left + 10, yb - 8, Ui.OR, false);
            } else if (!morceaux.isEmpty()) {
                g.drawString(font, "Clique sur un morceau pour le lancer.", left + 10, yb - 8, Ui.GRIS, false);
            }
        }
    }

    private static String temps(long ticks) {
        long s = ticks / 20;
        return s / 60 + ":" + String.format("%02d", s % 60);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
