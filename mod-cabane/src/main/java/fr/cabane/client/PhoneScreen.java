package fr.cabane.client;

import fr.cabane.telephone.PhoneNetwork.Action;
import fr.cabane.telephone.PhoneNetwork.State;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/**
 * L'écran du téléphone : un clavier, ton numéro, et les boutons vert / rouge.
 * On peut aussi taper les chiffres au clavier, Entrée pour appeler, Retour pour effacer.
 */
public class PhoneScreen extends Screen {
    private static final int W = 132;
    private static final int H = 236;

    private String saisie = "";
    private boolean modeNumero;
    private int left;
    private int top;
    private Button vert;
    private Button rouge;
    private Button changer;

    public PhoneScreen() {
        super(Component.translatable("screen.cabane.telephone"));
    }

    @Override
    protected void init() {
        left = (width - W) / 2;
        top = (height - H) / 2;
        String[] touches = {"1", "2", "3", "4", "5", "6", "7", "8", "9", "C", "0", "←"};
        for (int i = 0; i < touches.length; i++) {
            String touche = touches[i];
            int x = left + 14 + (i % 3) * 36;
            int y = top + 82 + (i / 3) * 24;
            addRenderableWidget(Button.builder(Component.literal(touche), b -> appuyer(touche)).bounds(x, y, 32, 20).build());
        }
        vert = addRenderableWidget(Button.builder(Component.empty(), b -> vert()).bounds(left + 14, top + 182, 50, 20).build());
        rouge = addRenderableWidget(Button.builder(Component.empty(), b -> rouge()).bounds(left + 68, top + 182, 50, 20).build());
        changer = addRenderableWidget(Button.builder(Component.literal("Changer de numéro"), b -> {
            modeNumero = !modeNumero;
            saisie = "";
            rafraichir();
        }).bounds(left + 14, top + 208, 104, 16).build());
        rafraichir();
    }

    void rafraichir() {
        if (vert == null) {
            return;
        }
        if (ClientPhone.myNumber.isEmpty()) {
            modeNumero = true;
        }
        boolean libre = ClientPhone.state == State.LIBRE;
        changer.visible = libre && !ClientPhone.myNumber.isEmpty();
        changer.setMessage(Component.literal(modeNumero ? "Annuler" : "Changer de numéro"));
        vert.active = true;
        if (modeNumero) {
            vert.setMessage(Component.literal("Valider").withStyle(ChatFormatting.GREEN));
            rouge.setMessage(Component.literal("Effacer").withStyle(ChatFormatting.RED));
            return;
        }
        switch (ClientPhone.state) {
            case State.APPEL_ENTRANT -> {
                vert.setMessage(Component.literal("Décrocher").withStyle(ChatFormatting.GREEN));
                rouge.setMessage(Component.literal("Refuser").withStyle(ChatFormatting.RED));
            }
            case State.APPEL_SORTANT -> {
                vert.active = false;
                vert.setMessage(Component.literal("Ça sonne…"));
                rouge.setMessage(Component.literal("Annuler").withStyle(ChatFormatting.RED));
            }
            case State.EN_LIGNE -> {
                vert.active = false;
                vert.setMessage(Component.literal("En ligne"));
                rouge.setMessage(Component.literal("Raccrocher").withStyle(ChatFormatting.RED));
            }
            default -> {
                vert.setMessage(Component.literal("Appeler").withStyle(ChatFormatting.GREEN));
                rouge.setMessage(Component.literal("Effacer").withStyle(ChatFormatting.RED));
            }
        }
    }

    private void appuyer(String touche) {
        switch (touche) {
            case "C" -> saisie = "";
            case "←" -> saisie = saisie.isEmpty() ? "" : saisie.substring(0, saisie.length() - 1);
            default -> {
                if (saisie.length() < 4) {
                    saisie += touche;
                }
            }
        }
    }

    private void vert() {
        if (modeNumero) {
            if (saisie.length() >= 3) {
                ClientPhone.send(Action.CHOISIR_NUMERO, saisie);
                modeNumero = false;
                saisie = "";
            }
        } else if (ClientPhone.state == State.APPEL_ENTRANT) {
            ClientPhone.send(Action.DECROCHER, "");
        } else if (ClientPhone.state == State.LIBRE && saisie.length() >= 3) {
            ClientPhone.send(Action.APPELER, saisie);
        }
        rafraichir();
    }

    private void rouge() {
        if (modeNumero || ClientPhone.state == State.LIBRE) {
            saisie = "";
        } else {
            ClientPhone.send(Action.RACCROCHER, "");
        }
    }

    @Override
    public boolean charTyped(char c, int modifiers) {
        if (c >= '0' && c <= '9') {
            appuyer(String.valueOf(c));
            return true;
        }
        return super.charTyped(c, modifiers);
    }

    @Override
    public boolean keyPressed(int key, int scanCode, int modifiers) {
        if (key == GLFW.GLFW_KEY_BACKSPACE) {
            appuyer("←");
            return true;
        }
        if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) {
            vert();
            return true;
        }
        return super.keyPressed(key, scanCode, modifiers);
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.renderBackground(g, mouseX, mouseY, partialTick);
        g.fill(left - 3, top - 3, left + W + 3, top + H + 3, 0xFF0B0B0F);
        g.fill(left, top, left + W, top + H, 0xFF24272E);
        g.fill(left + W / 2 - 14, top + 4, left + W / 2 + 14, top + 6, 0xFF4A4E58);
        g.fill(left + 8, top + 10, left + W - 8, top + 74, 0xFF0E2A2E);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        int cx = left + W / 2;
        String haut = ClientPhone.myNumber.isEmpty() ? "Pas encore de numéro" : "Mon n° : " + ClientPhone.myNumber;
        g.drawCenteredString(font, haut, cx, top + 15, 0xFF9FE8C8);

        String statut;
        String grand;
        int couleur = 0xFFFFFFFF;
        if (modeNumero) {
            statut = "Choisis ton numéro (3-4 chiffres)";
            grand = saisie.isEmpty() ? "____" : saisie;
        } else {
            switch (ClientPhone.state) {
                case State.APPEL_ENTRANT -> {
                    statut = "Appel entrant !";
                    grand = ClientPhone.other;
                    couleur = 0xFFFFD75E;
                }
                case State.APPEL_SORTANT -> {
                    statut = "Appel en cours…";
                    grand = ClientPhone.other;
                }
                case State.EN_LIGNE -> {
                    statut = "En ligne — parle !";
                    grand = ClientPhone.other;
                    couleur = 0xFF7CFF9B;
                }
                default -> {
                    statut = "Compose un numéro";
                    grand = saisie.isEmpty() ? "____" : saisie;
                }
            }
        }
        g.drawCenteredString(font, statut, cx, top + 28, 0xFFB8C4CC);
        g.pose().pushPose();
        g.pose().translate(cx, top + 44, 0);
        g.pose().scale(2F, 2F, 1F);
        g.drawCenteredString(font, grand, 0, 0, couleur);
        g.pose().popPose();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
