package fr.cabane.client;

import fr.cabane.boutique.Boutique;
import fr.cabane.boutique.Contrats;
import fr.cabane.client.ui.Ui;
import fr.cabane.economie.Economie;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

/** Écrans de la boutique du marché et des contrats de l'heure. */
public final class ClientBoutique {

    public static void ouvrir(List<Boutique.Vue> offres) {
        Minecraft.getInstance().setScreen(new BoutiqueScreen(offres));
    }

    /** Les contrats affichés au-dessus des tableaux, et quand ils changent (heure locale du client). */
    /** Les articles de la boutique, pour la vitrine qui tourne au-dessus du comptoir. */
    public static List<fr.cabane.boutique.Boutique.Vue> vitrine = List.of();
    public static List<Contrats.Vue> contratsAffiches = List.of();
    public static long finContrats;

    public static void affiche(List<Contrats.Vue> contrats, long msRestantes) {
        contratsAffiches = contrats;
        finContrats = System.currentTimeMillis() + msRestantes;
    }

    public static void contrats(List<Contrats.Vue> contrats, long msRestantes) {
        Minecraft.getInstance().setScreen(new ContratsScreen(contrats, msRestantes));
    }

    static final class BoutiqueScreen extends Screen {
        private static final int W = 320;
        private static final int H = 220;
        private static final int PAR_PAGE = 8;
        private final List<Boutique.Vue> offres;
        private int page;
        private int left;
        private int top;

        BoutiqueScreen(List<Boutique.Vue> offres) {
            super(Component.literal("Boutique"));
            this.offres = offres;
        }

        @Override
        protected void init() {
            left = (width - W) / 2;
            top = (height - H) / 2;
            int debut = page * PAR_PAGE;
            for (int i = debut; i < Math.min(offres.size(), debut + PAR_PAGE); i++) {
                Boutique.Vue o = offres.get(i);
                int col = (i - debut) % 2;
                int ligne = (i - debut) / 2;
                int x = left + 10 + col * 152;
                int y = top + 30 + ligne * 42;
                addRenderableWidget(Button.builder(Component.literal("×1"), b -> acheter(o, 1)).bounds(x + 96, y + 20, 24, 16).build());
                addRenderableWidget(Button.builder(Component.literal("×5"), b -> acheter(o, 5)).bounds(x + 122, y + 20, 24, 16).build());
            }
            int pages = Math.max(1, (offres.size() + PAR_PAGE - 1) / PAR_PAGE);
            if (pages > 1) {
                addRenderableWidget(Button.builder(Component.literal("<"), b -> changerPage(-1, pages)).bounds(left + W / 2 - 40, top + H - 22, 20, 16).build());
                addRenderableWidget(Button.builder(Component.literal(">"), b -> changerPage(1, pages)).bounds(left + W / 2 + 20, top + H - 22, 20, 16).build());
            }
        }

        private void changerPage(int sens, int pages) {
            page = Math.floorMod(page + sens, pages);
            rebuildWidgets();
        }

        private void acheter(Boutique.Vue offre, int quantite) {
            PacketDistributor.sendToServer(new Boutique.Acheter(offre.id(), quantite));
        }

        @Override
        public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
            super.renderBackground(g, mouseX, mouseY, partialTick);
            Ui.panneau(g, left, top, W, H);
        }

        @Override
        public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
            super.render(g, mouseX, mouseY, partialTick);
            // les offres des autres vendeurs ont un préfixe : « noir_… » (le Fourgue), « cartes_… » (Jean-Mi)
            String premiere = offres.isEmpty() ? "" : offres.get(0).id();
            String titre = premiere.contains(":noir_") ? "Marché noir" : premiere.contains(":cartes_") ? "Jean-Mi Collector" : "Boutique du marché";
            Ui.titre(g, font, titre, left + 10, top + 10);
            Ui.solde(g, font, left + W - 10, top + 10);
            int debut = page * PAR_PAGE;
            for (int i = debut; i < Math.min(offres.size(), debut + PAR_PAGE); i++) {
                Boutique.Vue o = offres.get(i);
                int x = left + 10 + ((i - debut) % 2) * 152;
                int y = top + 30 + ((i - debut) / 2) * 42;
                Ui.encart(g, x, y, 148, 38, Ui.CARTE);
                g.renderItem(o.stack(), x + 4, y + 4);
                g.renderItemDecorations(font, o.stack(), x + 4, y + 4);
                g.drawString(font, Ui.couper(font, o.stack().getHoverName().getString(), 120), x + 24, y + 5, 0xFFFFFFFF, false);
                g.drawString(font, Economie.format(o.prix()), x + 6, y + 24, Ui.OR, false);
                if (mouseX >= x + 4 && mouseX < x + 20 && mouseY >= y + 4 && mouseY < y + 20) {
                    g.renderTooltip(font, o.stack(), mouseX, mouseY);
                }
            }
            if (offres.isEmpty()) {
                g.drawCenteredString(font, "La boutique est vide pour l'instant.", left + W / 2, top + H / 2, Ui.GRIS);
            }
        }

        @Override
        public boolean isPauseScreen() {
            return false;
        }
    }

    static final class ContratsScreen extends Screen {
        private static final int W = 300;
        private static final int H = 170;
        private final List<Contrats.Vue> contrats;
        private final long finAt;
        private int left;
        private int top;

        ContratsScreen(List<Contrats.Vue> contrats, long msRestantes) {
            super(Component.literal("Contrats"));
            this.contrats = contrats;
            this.finAt = System.currentTimeMillis() + msRestantes;
        }

        @Override
        protected void init() {
            left = (width - W) / 2;
            top = (height - H) / 2;
            for (int i = 0; i < contrats.size(); i++) {
                int index = i;
                Button b = Button.builder(Component.literal(contrats.get(i).fait() ? "✔ Fait" : "Livrer"),
                        bt -> PacketDistributor.sendToServer(new Contrats.Livrer(index))).bounds(left + W - 70, top + 40 + i * 40, 58, 20).build();
                b.active = !contrats.get(i).fait();
                addRenderableWidget(b);
            }
        }

        @Override
        public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
            super.renderBackground(g, mouseX, mouseY, partialTick);
            Ui.panneau(g, left, top, W, H);
        }

        @Override
        public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
            super.render(g, mouseX, mouseY, partialTick);
            Ui.titre(g, font, "Contrats de l'heure", left + 10, top + 10);
            Ui.solde(g, font, left + W - 10, top + 10);
            long min = Math.max(0, (finAt - System.currentTimeMillis()) / 60000);
            g.drawString(font, "Nouveaux contrats dans " + min + " min", left + 10, top + 22, Ui.GRIS, false);
            for (int i = 0; i < contrats.size(); i++) {
                Contrats.Vue c = contrats.get(i);
                int y = top + 34 + i * 40;
                Ui.encart(g, left + 8, y, W - 16, 34, Ui.CARTE);
                g.renderItem(c.demande(), left + 14, y + 9);
                g.drawString(font, c.demande().getCount() + " × " + c.demande().getHoverName().getString(), left + 36, y + 7, c.fait() ? Ui.GRIS : 0xFFFFFFFF, false);
                g.drawString(font, "Récompense : " + Economie.format(c.recompense()), left + 36, y + 20, Ui.OR, false);
            }
            if (contrats.isEmpty()) {
                g.drawCenteredString(font, "Aucun contrat pour le moment.", left + W / 2, top + H / 2, Ui.GRIS);
            }
        }

        @Override
        public boolean isPauseScreen() {
            return false;
        }
    }

    private ClientBoutique() {
    }
}
