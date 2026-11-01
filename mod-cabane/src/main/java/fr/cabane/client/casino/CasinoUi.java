package fr.cabane.client.casino;

import fr.cabane.client.ui.Ios;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/** L'habillage du casino : cadre de bois et d'or, tapis vert, bandeau de velours, jetons. */
public final class CasinoUi {
    public static final int OR = 0xFFE8C15A;
    public static final int OR_FONCE = 0xFF9C7A2B;
    public static final int VELOURS = 0xFF7A0F1E;
    public static final int TAPIS = 0xFF0E6B35;
    public static final int TAPIS_FONCE = 0xFF07401F;
    public static final int ROUGE = 0xFFC62828;
    public static final int NOIR = 0xFF15171C;

    /** Le fond d'un jeu : bois, liseré doré, tapis en dégradé avec un vignettage, bandeau-titre. */
    public static void table(GuiGraphics g, Font font, String titre, int x, int y, int w, int h) {
        Ios.rond(g, x - 8, y - 8, w + 16, h + 16, 16, 0xFF2B140A);
        Ios.rond(g, x - 5, y - 5, w + 10, h + 10, 13, 0xFF4A2412);
        Ios.rond(g, x - 2, y - 2, w + 4, h + 4, 11, OR);
        for (int dy = 0; dy < h; dy++) {
            float t = Math.abs(dy - h / 2F) / (h / 2F);
            int c = melange(TAPIS, TAPIS_FONCE, t * t);
            int retrait = dy < 9 ? 9 - (int) Math.sqrt(81 - (9 - dy) * (9 - dy)) : dy > h - 10 ? 9 - (int) Math.sqrt(81 - (dy - h + 10) * (dy - h + 10)) : 0;
            g.fill(x + retrait, y + dy, x + w - retrait, y + dy + 1, c);
        }
        // motif discret du tapis
        for (int dy = 6; dy < h - 6; dy += 12) {
            for (int dx = 6 + (dy / 12 % 2) * 6; dx < w - 6; dx += 12) {
                g.fill(x + dx, y + dy, x + dx + 1, y + dy + 1, 0x18FFFFFF);
            }
        }
        // le bandeau de velours avec le titre
        Ios.rond(g, x + w / 2 - 80, y - 12, 160, 22, 8, OR_FONCE);
        Ios.rond(g, x + w / 2 - 78, y - 10, 156, 18, 7, VELOURS);
        Ios.texteCentre(g, font, "★ " + titre.toUpperCase() + " ★", x + w / 2F, y - 5, 1F, OR, true);
    }

    /** Un jeton de casino, avec ses encoches et sa valeur au centre. */
    public static void jeton(GuiGraphics g, Font font, int cx, int cy, int r, int couleur, String valeur) {
        Ios.disque(g, cx, cy, r, couleur);
        for (int k = 0; k < 8; k++) {
            double a = k * Math.PI / 4;
            int ex = cx + (int) Math.round(Math.cos(a) * (r - 2));
            int ey = cy + (int) Math.round(Math.sin(a) * (r - 2));
            g.fill(ex - 1, ey - 1, ex + 1, ey + 1, 0xFFFFFFFF);
        }
        Ios.disque(g, cx, cy, r - 4, Ios.eclaircir(couleur, 20));
        if (!valeur.isEmpty()) {
            Ios.texteCentre(g, font, valeur, cx, cy - 2, r < 12 ? 0.5F : 0.7F, 0xFFFFFFFF, true);
        }
    }

    public static int couleurJeton(long mise) {
        return mise >= 1000 ? 0xFF1F1F1F : mise >= 500 ? 0xFF6A1B9A : mise >= 100 ? 0xFF1565C0 : mise >= 50 ? 0xFF2E7D32 : 0xFFC62828;
    }

    public static int melange(int a, int b, float t) {
        int r = (int) ((a >> 16 & 0xFF) + ((b >> 16 & 0xFF) - (a >> 16 & 0xFF)) * t);
        int v = (int) ((a >> 8 & 0xFF) + ((b >> 8 & 0xFF) - (a >> 8 & 0xFF)) * t);
        int bl = (int) ((a & 0xFF) + ((b & 0xFF) - (a & 0xFF)) * t);
        return 0xFF000000 | r << 16 | v << 8 | bl;
    }

    private CasinoUi() {
    }
}
