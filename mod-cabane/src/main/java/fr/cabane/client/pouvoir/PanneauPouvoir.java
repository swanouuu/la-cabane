package fr.cabane.client.pouvoir;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/**
 * Le panneau des touches d'un pouvoir, le même pour tous : à gauche de l'écran, compact. Une ligne par sort : la touche
 * (celle que le joueur a choisie, voir Clavier), le nom du sort et sa recharge ; gris tant qu'il se recharge. Il ne
 * descend jamais sur la barre d'objets : s'il manque de place (une grande interface), il rapetisse.
 */
public final class PanneauPouvoir {
    private static final int RANG = 11;
    /** La barre d'objets et la vie au-dessus : le panneau s'arrête avant. */
    private static final int MARGE_BAS = 42;

    /**
     * lignes : {touche, sort} (une ligne au texte vide n'est pas dessinée) ; gris : la même longueur, ou plus courte
     * (le reste n'est pas gris), ou null.
     */
    public static void dessiner(GuiGraphics g, Font font, String titre, int teinte, String[][] lignes, boolean[] gris) {
        List<String[]> l = new ArrayList<>();
        List<Boolean> grises = new ArrayList<>();
        for (int i = 0; i < lignes.length; i++) {
            if (lignes[i] == null || lignes[i].length < 2 || lignes[i][1] == null || lignes[i][1].isEmpty()) {
                continue;
            }
            l.add(lignes[i]);
            grises.add(gris != null && i < gris.length && gris[i]);
        }
        int largeurTouche = 0, largeurSort = font.width(titre) - 8;
        for (String[] ligne : l) {
            largeurTouche = Math.max(largeurTouche, font.width(ligne[0]));
            largeurSort = Math.max(largeurSort, font.width(ligne[1]));
        }
        int pw = largeurTouche + largeurSort + 20, ph = 16 + l.size() * RANG + 2;
        int haut = g.guiHeight(), bas = haut - MARGE_BAS;
        // trop haut pour la place qu'il y a : il rapetisse (il ne passe jamais sur la barre d'objets)
        float k = Math.min(1F, (bas - 4) / (float) ph);
        g.pose().pushPose();
        g.pose().scale(k, k, 1);
        int x = Math.round(4 / k);
        int milieu = Math.round(haut / 2F / k) - ph / 2;
        int y = Math.max(Math.round(4 / k), Math.min(milieu, Math.round(bas / k) - ph));
        g.fill(x, y, x + pw, y + ph, 0xA0000000);
        g.fill(x, y, x + 2, y + ph, teinte);
        g.drawString(font, titre, x + 6, y + 4, teinte, true);
        for (int i = 0; i < l.size(); i++) {
            int ly = y + 16 + i * RANG;
            String touche = l.get(i)[0];
            if (!touche.isEmpty()) {
                g.fill(x + 5, ly - 1, x + 9 + font.width(touche), ly + 9, 0x40FFFFFF);
                g.drawString(font, touche, x + 7, ly, 0xFFFFFFFF, false);
            }
            g.drawString(font, l.get(i)[1], x + 14 + largeurTouche, ly, grises.get(i) ? 0xFF7A7A7A : 0xFFEDEDED, true);
        }
        g.pose().popPose();
    }

    /** « 12 s » après le nom du sort quand il se recharge, rien sinon. */
    public static String recharge(String sort, double secondes) {
        return secondes > 0 ? sort + "  " + (int) Math.ceil(secondes) + " s" : sort;
    }

    private PanneauPouvoir() {
    }
}
