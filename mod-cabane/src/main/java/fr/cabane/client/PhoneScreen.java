package fr.cabane.client;

import fr.cabane.client.ui.Ios;
import fr.cabane.telephone.PhoneNetwork.Action;
import fr.cabane.telephone.PhoneNetwork.State;
import net.minecraft.client.gui.GuiGraphics;
import org.lwjgl.glfw.GLFW;

/**
 * L'appli Téléphone, façon iPhone : le clavier rond, la touche verte pour appeler ; pendant un
 * appel, l'écran d'appel (décrocher, raccrocher). Si la SIM n'est pas encore activée, on choisit
 * ici son numéro, une fois pour toutes. Les chiffres se tapent aussi au clavier.
 */
public class PhoneScreen extends ClientPiaf.Ecran {
    private static final String[] TOUCHES = {"1", "2", "3", "4", "5", "6", "7", "8", "9", "*", "0", "#"};
    private static final String[] LETTRES = {"", "ABC", "DEF", "GHI", "JKL", "MNO", "PQRS", "TUV", "WXYZ", "", "+", ""};

    private String saisie = "";

    public PhoneScreen() {
        super("");
        ClientPhone.send(Action.ETAT, "");
    }

    private boolean activation() {
        return ClientPhone.myNumber.isEmpty();
    }

    private boolean enAppel() {
        return ClientPhone.state != State.LIBRE;
    }

    @Override
    protected void init() {
        super.init();
        int cx = left + W / 2;
        int r = Math.min(17, (W - 40) / 6);
        int pas = r * 2 + 10;
        if (enAppel() && !activation()) {
            int y = top + H - 70;
            if (ClientPhone.state == State.APPEL_ENTRANT) {
                addRenderableWidget(Ios.Bouton.touche(cx - pas, y, r + 2, "✕", "refuser", Ios.ROUGE, () -> ClientPhone.send(Action.RACCROCHER, "")));
                addRenderableWidget(Ios.Bouton.touche(cx + pas, y, r + 2, "☎", "répondre", Ios.VERT, () -> ClientPhone.send(Action.DECROCHER, "")));
            } else {
                addRenderableWidget(Ios.Bouton.touche(cx, y, r + 2, "☎", "raccrocher", Ios.ROUGE, () -> ClientPhone.send(Action.RACCROCHER, "")));
            }
            return;
        }
        int y0 = top + (activation() ? 112 : 92);
        for (int i = 0; i < TOUCHES.length; i++) {
            String touche = TOUCHES[i];
            if (activation() && (touche.equals("*") || touche.equals("#"))) {
                continue;
            }
            addRenderableWidget(Ios.Bouton.touche(cx + (i % 3 - 1) * pas, y0 + (i / 3) * pas, r, touche, LETTRES[i], Ios.TOUCHE, () -> appuyer(touche)));
        }
        int yBas = y0 + 4 * pas;
        if (activation()) {
            addRenderableWidget(Ios.Bouton.plein(left + 16, yBas - r + 2, W - 32, 22, "Activer ce numéro", Ios.VERT, this::vert));
        } else {
            addRenderableWidget(Ios.Bouton.touche(cx, yBas, r, "☎", "", Ios.VERT, this::vert));
        }
        addRenderableWidget(Ios.Bouton.texte(cx + pas - r, yBas - 8, 2 * r, 16, "←", Ios.GRIS, () -> appuyer("←")));
        if (!activation()) {
            addRenderableWidget(Ios.Bouton.texte(left + 10, top + 22, W - 20, 12,
                    ClientPhone.masque ? "☑ Appel en numéro masqué" : "☐ Appel en numéro masqué",
                    ClientPhone.masque ? Ios.ORANGE : Ios.GRIS, () -> {
                        ClientPhone.masque = !ClientPhone.masque;
                        rebuildWidgets();
                    }));
        }
    }

    private void appuyer(String touche) {
        if (touche.equals("←")) {
            saisie = saisie.isEmpty() ? "" : saisie.substring(0, saisie.length() - 1);
        } else if (touche.matches("\\d") && saisie.length() < 4) {
            saisie += touche;
        }
    }

    private void vert() {
        if (activation()) {
            if (saisie.length() >= 3) {
                ClientPhone.send(Action.CHOISIR_NUMERO, saisie);
                saisie = "";
            }
        } else if (ClientPhone.state == State.APPEL_ENTRANT) {
            ClientPhone.send(Action.DECROCHER, "");
        } else if (ClientPhone.state == State.LIBRE && saisie.length() >= 3) {
            ClientPhone.send(Action.APPELER, saisie + (ClientPhone.masque ? "|masque" : ""));
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
    int fond() {
        return enAppel() && !activation() ? 0xFF1A2230 : Ios.NOIR;
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        int cx = left + W / 2;
        if (activation()) {
            Ios.texteCentre(g, font, "Activer la SIM", cx, top + 26, 1.3F, Ios.BLANC, true);
            fr.cabane.client.ui.Ui.paragraphe(g, font, "Choisis ton numéro (3 ou 4 chiffres). Il sera gravé dans ce téléphone pour toujours.",
                    left + 14, top + 44, W - 28, Ios.GRIS);
            Ios.texteCentre(g, font, saisie.isEmpty() ? "_ _ _ _" : saisie, cx, top + 74, 2F, Ios.BLANC, false);
            return;
        }
        if (enAppel()) {
            String nom = ClientPhone.nom(ClientPhone.other);
            ClientPiaf.avatar(g, font, nom, cx, top + 80, 28);
            Ios.texteCentre(g, font, nom, cx, top + 118, 1.5F, Ios.BLANC, false);
            String statut = switch (ClientPhone.state) {
                case State.APPEL_ENTRANT -> "appel entrant…";
                case State.APPEL_SORTANT -> "appel…";
                default -> chrono();
            };
            Ios.texteCentre(g, font, statut, cx, top + 136, 1F, Ios.GRIS, false);
            if (!nom.equals(ClientPhone.other)) {
                Ios.texteCentre(g, font, ClientPhone.other, cx, top + 148, 0.85F, Ios.GRIS, false);
            }
            return;
        }
        String affichage = saisie.isEmpty() ? "" : saisie;
        Ios.texteCentre(g, font, affichage, cx, top + 46, 2.2F, Ios.BLANC, false);
        if (!saisie.isEmpty() && !ClientPhone.nom(saisie).equals(saisie)) {
            Ios.texteCentre(g, font, ClientPhone.nom(saisie), cx, top + 66, 1F, Ios.BLEU, false);
        }
        Ios.texteCentre(g, font, "mon n° " + ClientPhone.myNumber, cx, top + H - 30, 0.75F, Ios.GRIS, false);
    }

    private static String chrono() {
        long s = (System.currentTimeMillis() - ClientPhone.debutAppel) / 1000;
        return String.format("%02d:%02d", s / 60, s % 60);
    }

    /** L'écran change de forme (activation, appel, clavier) quand l'état du téléphone change. */
    @Override
    public void rafraichir() {
        rebuildWidgets();
    }
}
