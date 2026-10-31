package fr.cabane.client.ui;

import fr.cabane.Cabane;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/**
 * Le style du téléphone, inspiré d'iOS en mode sombre : coins arrondis, boutons ronds,
 * barre d'état avec l'heure du jeu, icônes d'applis (textures cabane:textures/gui/app/*.png).
 */
public final class Ios {
    public static final int NOIR = 0xFF000000;
    public static final int GROUPE = 0xFF1C1C1E;
    public static final int GROUPE_CLAIR = 0xFF2C2C2E;
    public static final int SEPARATEUR = 0xFF38383A;
    public static final int BLEU = 0xFF0A84FF;
    public static final int VERT = 0xFF30D158;
    public static final int ROUGE = 0xFFFF453A;
    public static final int ORANGE = 0xFFFF9F0A;
    public static final int GRIS = 0xFF8E8E93;
    public static final int BLANC = 0xFFFFFFFF;
    public static final int TOUCHE = 0xFF333333;

    /** Un rectangle aux coins arrondis, ligne par ligne. */
    public static void rond(GuiGraphics g, int x, int y, int w, int h, int r, int c) {
        r = Math.max(0, Math.min(r, Math.min(w, h) / 2));
        for (int dy = 0; dy < h; dy++) {
            int retrait = 0;
            if (dy < r || dy >= h - r) {
                double d = dy < r ? r - dy - 0.5 : dy - (h - r) + 0.5;
                retrait = (int) Math.round(r - Math.sqrt(Math.max(0, r * r - d * d)));
            }
            g.fill(x + retrait, y + dy, x + w - retrait, y + dy + 1, c);
        }
    }

    /** Un disque plein. */
    public static void disque(GuiGraphics g, int cx, int cy, int r, int c) {
        for (int dy = -r; dy < r; dy++) {
            double d = dy + 0.5;
            int demi = (int) Math.round(Math.sqrt(Math.max(0, r * r - d * d)));
            g.fill(cx - demi, cy + dy, cx + demi, cy + dy + 1, c);
        }
    }

    public static int eclaircir(int c, int pas) {
        int a = c >>> 24;
        int r = Math.min(255, (c >> 16 & 0xFF) + pas);
        int v = Math.min(255, (c >> 8 & 0xFF) + pas);
        int b = Math.min(255, (c & 0xFF) + pas);
        return a << 24 | r << 16 | v << 8 | b;
    }

    /** Du texte à une autre taille que celle de la police. */
    public static void texte(GuiGraphics g, Font font, String s, float x, float y, float echelle, int couleur, boolean gras) {
        Component c = gras ? Component.literal(s).withStyle(st -> st.withBold(true)) : Component.literal(s);
        g.pose().pushPose();
        g.pose().translate(x, y, 0);
        g.pose().scale(echelle, echelle, 1F);
        g.drawString(font, c, 0, 0, couleur, false);
        g.pose().popPose();
    }

    public static void texteCentre(GuiGraphics g, Font font, String s, float cx, float y, float echelle, int couleur, boolean gras) {
        Component c = gras ? Component.literal(s).withStyle(st -> st.withBold(true)) : Component.literal(s);
        texte(g, font, s, cx - font.width(c) * echelle / 2F, y, echelle, couleur, gras);
    }

    /** L'heure du monde, façon « 14:05 ». */
    public static String heure() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return "--:--";
        }
        long t = (mc.level.getDayTime() + 6000) % 24000;
        return String.format("%02d:%02d", t / 1000, t % 1000 * 60 / 1000);
    }

    /** La barre d'état : l'heure à gauche, le réseau et la batterie à droite, l'île au milieu. */
    public static void barreEtat(GuiGraphics g, Font font, int x, int y, int w, int couleur) {
        texte(g, font, heure(), x + 14, y + 5, 0.75F, couleur, true);
        int cx = x + w / 2;
        rond(g, cx - 18, y + 3, 36, 10, 5, NOIR);
        int d = x + w - 14;
        // batterie
        rond(g, d - 12, y + 5, 12, 6, 2, couleur);
        g.fill(d - 11, y + 6, d - 1, y + 10, 0xFF000000 | 0x30D158);
        g.fill(d, y + 7, d + 1, y + 9, couleur);
        // réseau
        for (int i = 0; i < 4; i++) {
            g.fill(d - 26 + i * 3, y + 10 - i * 2 - 1, d - 24 + i * 3, y + 11, couleur);
        }
    }

    public static ResourceLocation icone(String app) {
        return Cabane.id("textures/gui/app/" + app + ".png");
    }

    public static void dessinerIcone(GuiGraphics g, String app, int x, int y, int taille) {
        g.blit(icone(app), x, y, taille, taille, 0, 0, 32, 32, 32, 32);
    }

    /** Un bouton façon iOS : plein, texte bleu, rond (clavier), ou icône d'appli. */
    public static final class Bouton extends AbstractButton {
        public enum Style { PLEIN, TEXTE, ROND, ICONE }

        private final Style style;
        private final int couleur;
        private final Runnable action;
        private String sous = "";
        private String app = "";
        private boolean etiquette = true;

        public Bouton(int x, int y, int w, int h, String texte, Style style, int couleur, Runnable action) {
            super(x, y, w, h, Component.literal(texte));
            this.style = style;
            this.couleur = couleur;
            this.action = action;
        }

        public static Bouton plein(int x, int y, int w, int h, String texte, int couleur, Runnable action) {
            return new Bouton(x, y, w, h, texte, Style.PLEIN, couleur, action);
        }

        public static Bouton texte(int x, int y, int w, int h, String texte, int couleur, Runnable action) {
            return new Bouton(x, y, w, h, texte, Style.TEXTE, couleur, action);
        }

        public static Bouton touche(int cx, int cy, int r, String texte, String sous, int couleur, Runnable action) {
            Bouton b = new Bouton(cx - r, cy - r, 2 * r, 2 * r, texte, Style.ROND, couleur, action);
            b.sous = sous;
            return b;
        }

        public static Bouton appli(int x, int y, int taille, String app, String nom, boolean etiquette, Runnable action) {
            Bouton b = new Bouton(x, y, taille, taille, nom, Style.ICONE, 0, action);
            b.app = app;
            b.etiquette = etiquette;
            return b;
        }

        @Override
        protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
            Font font = Minecraft.getInstance().font;
            boolean survol = isHovered() && active;
            int c = active ? couleur : 0xFF3A3A3C;
            String t = getMessage().getString();
            int x = getX();
            int y = getY();
            switch (style) {
                case PLEIN -> {
                    rond(g, x, y, width, height, Math.min(9, height / 2), survol ? eclaircir(c, 24) : c);
                    texteCentre(g, font, t, x + width / 2F, y + (height - 8) / 2F, 1F, BLANC, false);
                }
                case TEXTE -> {
                    int largeur = font.width(t);
                    g.drawString(font, t, x + (width - largeur) / 2, y + (height - 8) / 2, survol ? eclaircir(c, 50) : c, false);
                }
                case ROND -> {
                    disque(g, x + width / 2, y + height / 2, width / 2, survol ? eclaircir(c, 30) : c);
                    if (sous.isEmpty()) {
                        texteCentre(g, font, t, x + width / 2F, y + height / 2F - 6, 1.5F, BLANC, false);
                    } else {
                        texteCentre(g, font, t, x + width / 2F, y + height / 2F - 9, 1.5F, BLANC, false);
                        texteCentre(g, font, sous, x + width / 2F, y + height / 2F + 5, 0.5F, BLANC, true);
                    }
                }
                case ICONE -> {
                    dessinerIcone(g, app, x, y, width);
                    if (survol) {
                        rond(g, x, y, width, height, width / 4, 0x30000000);
                    }
                    if (etiquette) {
                        texteCentre(g, font, t, x + width / 2F, y + height + 3, 0.62F, BLANC, false);
                    }
                }
            }
        }

        @Override
        public void onPress() {
            action.run();
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
            defaultButtonNarrationText(output);
        }
    }

    private Ios() {
    }
}
