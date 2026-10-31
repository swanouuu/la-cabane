package fr.cabane.client;

import fr.cabane.bloc.EtalBlockEntity;
import fr.cabane.bloc.EtalMenu;
import fr.cabane.bloc.Etals;
import fr.cabane.client.ui.Ui;
import fr.cabane.economie.Economie;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.neoforged.neoforge.network.PacketDistributor;

/** Les deux écrans de l'étal : la vitrine des clients et la gestion du propriétaire. */
public final class ClientEtal {

    public static void vitrine(Etals.Vitrine vitrine) {
        Minecraft.getInstance().setScreen(new VitrineScreen(vitrine));
    }

    /** Le client voit les lots, clique, puis confirme. */
    static final class VitrineScreen extends Screen {
        private static final int W = 250;
        private static final int H = 190;
        private final Etals.Vitrine vitrine;
        private Etals.Lot choisi;
        private int left;
        private int top;

        VitrineScreen(Etals.Vitrine vitrine) {
            super(Component.literal("Étal"));
            this.vitrine = vitrine;
        }

        @Override
        protected void init() {
            left = (width - W) / 2;
            top = (height - H) / 2;
            for (int i = 0; i < vitrine.lots().size(); i++) {
                Etals.Lot lot = vitrine.lots().get(i);
                int x = left + 10 + (i % 3) * 78;
                int y = top + 32 + (i / 3) * 40;
                addRenderableWidget(Button.builder(Component.empty(), b -> {
                    choisi = lot;
                    rebuildWidgets();
                }).bounds(x, y, 74, 36).build()).setAlpha(0.15F);
            }
            if (choisi != null) {
                addRenderableWidget(Button.builder(Component.literal("Confirmer l'achat"), b -> {
                    PacketDistributor.sendToServer(new Etals.Acheter(vitrine.pos(), choisi.index(), choisi.prix()));
                    onClose();
                }).bounds(left + 10, top + H - 26, 110, 18).build());
                addRenderableWidget(Button.builder(Component.literal("Annuler"), b -> {
                    choisi = null;
                    rebuildWidgets();
                }).bounds(left + W - 80, top + H - 26, 70, 18).build());
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
            Ui.titre(g, font, "Étal de " + vitrine.vendeur(), left + 10, top + 10);
            Ui.solde(g, font, left + W - 10, top + 10);
            for (int i = 0; i < vitrine.lots().size(); i++) {
                Etals.Lot lot = vitrine.lots().get(i);
                int x = left + 10 + (i % 3) * 78;
                int y = top + 32 + (i / 3) * 40;
                if (lot == choisi) {
                    g.fill(x - 1, y - 1, x + 75, y + 37, Ui.OR);
                }
                Ui.encart(g, x, y, 74, 36, Ui.CARTE);
                g.renderItem(lot.stack(), x + 4, y + 4);
                g.renderItemDecorations(font, lot.stack(), x + 4, y + 4);
                g.drawString(font, Economie.format(lot.prix()), x + 4, y + 24, Ui.OR, false);
                if (mouseX >= x && mouseX < x + 74 && mouseY >= y && mouseY < y + 36) {
                    g.renderTooltip(font, lot.stack(), mouseX, mouseY);
                }
            }
            if (vitrine.lots().isEmpty()) {
                g.drawCenteredString(font, "Rien à vendre pour le moment.", left + W / 2, top + H / 2, Ui.GRIS);
            } else if (choisi == null) {
                g.drawCenteredString(font, "Clique sur un lot pour l'acheter.", left + W / 2, top + H - 20, Ui.GRIS);
            } else {
                String q = choisi.stack().getCount() + " × " + choisi.stack().getHoverName().getString();
                g.drawCenteredString(font, Ui.couper(font, q, W - 20) + " — " + Economie.format(choisi.prix()), left + W / 2, top + H - 40, 0xFFFFFFFF);
            }
        }

        @Override
        public boolean isPauseScreen() {
            return false;
        }
    }

    /** Le propriétaire range ses lots et écrit un prix à côté de chacun. */
    public static final class GestionScreen extends AbstractContainerScreen<EtalMenu> {
        private final List<EditBox> champs = new ArrayList<>();

        public GestionScreen(EtalMenu menu, Inventory inventaire, Component titre) {
            super(menu, inventaire, titre);
            imageWidth = 232;
            imageHeight = 184;
            inventoryLabelX = EtalMenu.INV_X;
            inventoryLabelY = EtalMenu.INV_Y - 11;
        }

        @Override
        protected void init() {
            super.init();
            champs.clear();
            for (int i = 0; i < EtalBlockEntity.LOTS; i++) {
                EditBox champ = new EditBox(font, leftPos + EtalMenu.LOT_X + (i % 3) * EtalMenu.LOT_DX + 20,
                        topPos + EtalMenu.LOT_Y + (i / 3) * EtalMenu.LOT_DY, 48, 16, Component.literal("Prix"));
                champ.setFilter(s -> s.matches("\\d{0,9}"));
                champ.setMaxLength(9);
                long p = menu.prix()[i];
                champ.setValue(p > 0 ? Long.toString(p) : "");
                champ.setHint(Component.literal("prix $"));
                champs.add(addRenderableWidget(champ));
            }
            addRenderableWidget(Button.builder(Component.literal("Enregistrer les prix"), b -> enregistrer())
                    .bounds(leftPos + imageWidth - 118, topPos + 4, 112, 14).build());
        }

        private void enregistrer() {
            List<Long> prix = new ArrayList<>();
            for (EditBox champ : champs) {
                prix.add(champ.getValue().isEmpty() ? 0L : Long.parseLong(champ.getValue()));
            }
            PacketDistributor.sendToServer(new Etals.Prix(menu.pos(), prix));
        }

        @Override
        public boolean keyPressed(int key, int scanCode, int modifiers) {
            for (EditBox champ : champs) {
                if (champ.isFocused() && key != 256) {
                    return champ.keyPressed(key, scanCode, modifiers) || champ.canConsumeInput();
                }
            }
            return super.keyPressed(key, scanCode, modifiers);
        }

        @Override
        protected void renderBg(GuiGraphics g, float partialTick, int mouseX, int mouseY) {
            Ui.panneau(g, leftPos, topPos, imageWidth, imageHeight);
            for (Slot slot : menu.slots) {
                g.fill(leftPos + slot.x - 1, topPos + slot.y - 1, leftPos + slot.x + 17, topPos + slot.y + 17, 0xFF3A3F4A);
                g.fill(leftPos + slot.x, topPos + slot.y, leftPos + slot.x + 16, topPos + slot.y + 16, 0xFF15171C);
            }
        }

        @Override
        protected void renderLabels(GuiGraphics g, int mouseX, int mouseY) {
            g.drawString(font, "Mon étal — un prix par lot", 8, 6, 0xFFFFFFFF, false);
            g.drawString(font, playerInventoryTitle, inventoryLabelX, inventoryLabelY, Ui.GRIS, false);
        }

        @Override
        public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
            super.render(g, mouseX, mouseY, partialTick);
            renderTooltip(g, mouseX, mouseY);
        }
    }

    private ClientEtal() {
    }
}
