package fr.cabane.client;

import fr.cabane.boutique.Forge;
import fr.cabane.client.ui.Ios;
import fr.cabane.client.ui.Ui;
import fr.cabane.economie.Economie;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

/** La forge de Brokk côté client : les pièces rares, ce qu'il faut rapporter, le prix. */
public final class ClientForge {
    public static void ouvrir(List<Forge.Vue> recettes) {
        if (Minecraft.getInstance().screen instanceof Ecran e) {
            e.recettes = recettes;
            e.rafraichir();
        } else {
            Minecraft.getInstance().setScreen(new Ecran(recettes));
        }
    }

    static final class Ecran extends Screen {
        private static final int W = 340;
        private static final int H = 236;
        private static final int PAR_PAGE = 4;
        List<Forge.Vue> recettes;
        private int page;
        private int left;
        private int top;

        Ecran(List<Forge.Vue> recettes) {
            super(Component.literal("La forge de Brokk"));
            this.recettes = recettes;
        }

        void rafraichir() {
            rebuildWidgets();
        }

        @Override
        protected void init() {
            left = (width - W) / 2;
            top = (height - H) / 2;
            int pages = Math.max(1, (recettes.size() + PAR_PAGE - 1) / PAR_PAGE);
            page = Math.min(page, pages - 1);
            for (int k = 0; k < PAR_PAGE; k++) {
                int i = page * PAR_PAGE + k;
                if (i >= recettes.size()) {
                    break;
                }
                Forge.Vue r = recettes.get(i);
                addRenderableWidget(Ios.Bouton.plein(left + W - 78, top + 34 + k * 46 + 12, 66, 18, "Forger", 0xFFB8862B,
                        () -> PacketDistributor.sendToServer(new Forge.Forger(r.id()))));
            }
            if (pages > 1) {
                addRenderableWidget(Ios.Bouton.plein(left + 12, top + H - 24, 24, 16, "‹", 0xFF3A2A1E, () -> {
                    page = Math.floorMod(page - 1, pages);
                    rebuildWidgets();
                }));
                addRenderableWidget(Ios.Bouton.plein(left + 40, top + H - 24, 24, 16, "›", 0xFF3A2A1E, () -> {
                    page = Math.floorMod(page + 1, pages);
                    rebuildWidgets();
                }));
            }
        }

        private int possede(ItemStack ing) {
            int n = 0;
            var joueur = Minecraft.getInstance().player;
            if (joueur != null) {
                for (ItemStack s : joueur.getInventory().items) {
                    if (s.is(ing.getItem())) {
                        n += s.getCount();
                    }
                }
            }
            return n;
        }

        @Override
        public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
            super.renderBackground(g, mouseX, mouseY, partialTick);
            // fer sombre, liseré de braise
            Ios.rond(g, left - 4, top - 4, W + 8, H + 8, 10, 0xFFE86A1E);
            for (int dy = 0; dy < H; dy++) {
                int c = fr.cabane.client.casino.CasinoUi.melange(0xFF2B2E33, 0xFF15161A, dy / (float) H);
                g.fill(left, top + dy, left + W, top + dy + 1, c);
            }
            Ios.texteCentre(g, font, "⚒ LA FORGE DE BROKK ⚒", left + W / 2F, top + 10, 1.2F, 0xFFFFB347, true);
        }

        @Override
        public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
            super.render(g, mouseX, mouseY, partialTick);
            Ui.solde(g, font, left + W - 10, top + 10);
            if (recettes.isEmpty()) {
                g.drawCenteredString(font, "Brokk n'a rien à forger pour l'instant.", left + W / 2, top + 100, Ui.GRIS);
            }
            for (int k = 0; k < PAR_PAGE; k++) {
                int i = page * PAR_PAGE + k;
                if (i >= recettes.size()) {
                    break;
                }
                Forge.Vue r = recettes.get(i);
                int y = top + 34 + k * 46;
                Ios.rond(g, left + 8, y, W - 16, 42, 6, 0x60000000);
                g.renderItem(r.resultat(), left + 14, y + 4);
                g.drawString(font, r.resultat().getHoverName(), left + 34, y + 4, 0xFFFFE0B0, false);
                g.drawString(font, Economie.format(r.prix()), left + 34, y + 15, Ui.OR, false);
                int x = left + 14;
                for (ItemStack ing : r.ingredients()) {
                    g.renderItem(ing, x, y + 24);
                    boolean ok = possede(ing) >= ing.getCount();
                    g.drawString(font, "×" + ing.getCount(), x + 16, y + 30, ok ? Ui.VERT : Ui.ROUGE, true);
                    x += 40;
                }
                if (mouseX >= left + 14 && mouseX < left + 30 && mouseY >= y + 4 && mouseY < y + 20) {
                    g.renderTooltip(font, r.resultat(), mouseX, mouseY);
                }
            }
            g.drawString(font, "Rapporte les matériaux et paie le travail : lui seul sait faire ça.", left + 72, top + H - 20, Ui.GRIS, false);
        }

        @Override
        public boolean isPauseScreen() {
            return false;
        }
    }

    private ClientForge() {
    }
}
