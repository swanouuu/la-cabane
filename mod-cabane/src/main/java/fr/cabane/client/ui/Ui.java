package fr.cabane.client.ui;

import fr.cabane.client.ClientEconomie;
import fr.cabane.economie.Economie;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

/** Le style commun des écrans du mod : panneaux sombres, titres, solde en haut à droite. */
public final class Ui {
    public static final int FOND = 0xF01B1E25;
    public static final int BORD = 0xFF0B0C10;
    public static final int CARTE = 0xFF262A33;
    public static final int OR = 0xFFFFD75E;
    public static final int GRIS = 0xFFB4BCC8;
    public static final int VERT = 0xFF7CFF9B;
    public static final int ROUGE = 0xFFFF6B6B;

    public static void panneau(GuiGraphics g, int x, int y, int w, int h) {
        g.fill(x - 2, y - 2, x + w + 2, y + h + 2, BORD);
        g.fill(x, y, x + w, y + h, FOND);
        g.fill(x, y, x + w, y + 1, 0x30FFFFFF);
    }

    public static void encart(GuiGraphics g, int x, int y, int w, int h, int couleur) {
        g.fill(x, y, x + w, y + h, couleur);
        g.fill(x, y + h - 1, x + w, y + h, 0x40000000);
    }

    public static void titre(GuiGraphics g, Font font, String texte, int x, int y) {
        g.drawString(font, Component.literal(texte).withStyle(s -> s.withBold(true)), x, y, 0xFFFFFFFF, true);
    }

    /** Le solde du joueur, aligné à droite. */
    public static void solde(GuiGraphics g, Font font, int droite, int y) {
        if (ClientEconomie.solde >= 0) {
            String s = Economie.format(ClientEconomie.solde);
            g.drawString(font, s, droite - font.width(s), y, OR, true);
        }
    }

    /** Texte sur plusieurs lignes ; renvoie la hauteur utilisée. */
    public static int paragraphe(GuiGraphics g, Font font, String texte, int x, int y, int largeur, int couleur) {
        int dy = 0;
        for (FormattedCharSequence ligne : font.split(Component.literal(texte), largeur)) {
            g.drawString(font, ligne, x, y + dy, couleur, false);
            dy += font.lineHeight + 1;
        }
        return dy;
    }

    public static String couper(Font font, String texte, int largeur) {
        if (font.width(texte) <= largeur) {
            return texte;
        }
        return font.plainSubstrByWidth(texte, largeur - font.width("…")) + "…";
    }

    private Ui() {
    }
}
