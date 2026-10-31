package fr.cabane.client.casino;

import fr.cabane.casino.Casino;
import fr.cabane.client.ui.Ui;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Machine à sous, roulette, dés, roue de la fortune et pile ou face. */
public final class JeuxScreens {

    // ------------------------------------------------------------------ machine à sous

    public static final class Machine extends JeuScreen {
        private static final ItemStack[] SYMBOLES = {
                new ItemStack(Items.SWEET_BERRIES), new ItemStack(Items.APPLE), new ItemStack(Items.BELL),
                new ItemStack(Items.EMERALD), new ItemStack(Items.DIAMOND), new ItemStack(Items.NETHER_STAR)};
        private final int[] affiches = {0, 1, 2};

        public Machine() {
            super("Machine à sous", Casino.MACHINE);
        }

        @Override
        protected int dureeAnimation() {
            return 40;
        }

        @Override
        protected int choix() {
            return 0;
        }

        @Override
        public void tick() {
            if (animation >= 0 && tirage != null) {
                for (int i = 0; i < 3; i++) {
                    boolean arrete = animation >= 14 + i * 10;
                    affiches[i] = arrete ? tirage.get(i) : (affiches[i] + 1 + i) % SYMBOLES.length;
                }
            }
            super.tick();
        }

        @Override
        protected void dessinerJeu(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
            int x0 = left + 20;
            int y0 = top + 40;
            g.fill(x0 - 4, y0 - 4, x0 + 3 * 58 + 2, y0 + 62, 0xFF8A1C1C);
            for (int i = 0; i < 3; i++) {
                int x = x0 + i * 58;
                g.fill(x, y0, x + 54, y0 + 54, 0xFFF4EFE6);
                g.pose().pushPose();
                g.pose().translate(x + 11, y0 + 11, 0);
                g.pose().scale(2F, 2F, 1F);
                g.renderItem(SYMBOLES[affiches[i]], 0, 0);
                g.pose().popPose();
            }
            int tx = left + 214;
            g.drawString(font, "Gains (× la mise)", tx, top + 36, Ui.GRIS, false);
            for (int i = SYMBOLES.length - 1; i >= 0; i--) {
                int y = top + 48 + (SYMBOLES.length - 1 - i) * 18;
                g.pose().pushPose();
                g.pose().translate(tx, y, 0);
                g.pose().scale(0.75F, 0.75F, 1F);
                for (int k = 0; k < 3; k++) {
                    g.renderItem(SYMBOLES[i], k * 16, 0);
                }
                g.pose().popPose();
                g.drawString(font, "×" + Casino.SYMBOLES_GAIN[i], tx + 42, y + 3, Ui.OR, false);
            }
            g.drawString(font, "2 baies : ×2", tx, top + 160, Ui.GRIS, false);
        }
    }

    // ------------------------------------------------------------------ bandeau défilant (roulette et roue)

    /** Un bandeau de cases qui défile puis s'arrête sur la case tirée, sous le repère. */
    abstract static class Bandeau extends JeuScreen {
        protected float decalage;
        private float depart;
        private float arrivee;

        Bandeau(String titre, int jeu) {
            super(titre, jeu);
        }

        protected abstract int nbCases();

        /** Indice dans le bandeau de la case correspondant au tirage. */
        protected abstract int caseDuTirage(int tirage);

        protected abstract void dessinerCase(GuiGraphics g, int indice, int x, int y, int w, int h);

        @Override
        protected int dureeAnimation() {
            return 70;
        }

        @Override
        void resultat(java.util.List<Integer> tirage, long gain) {
            super.resultat(tirage, gain);
            depart = decalage % nbCases();
            arrivee = nbCases() * 4 + caseDuTirage(tirage.get(0));
        }

        protected void dessinerBandeau(GuiGraphics g, int y, float partialTick) {
            if (animation >= 0) {
                decalage = Mth.lerp(adoucir(progression(partialTick)), depart, arrivee);
            }
            int largeur = 30;
            int cx = left + W / 2;
            g.enableScissor(left + 8, y - 4, left + W - 8, y + 40);
            for (int k = -7; k <= 7; k++) {
                float pos = decalage + k;
                int indice = Math.floorMod((int) Math.floor(pos + 0.5F), nbCases());
                float frac = pos - (float) Math.floor(pos + 0.5F);
                int x = (int) (cx + (k - frac) * largeur - largeur / 2F);
                dessinerCase(g, indice, x, y, largeur - 2, 32);
            }
            g.disableScissor();
            g.fill(cx - 1, y - 6, cx + 1, y + 42, Ui.OR);
        }
    }

    // ------------------------------------------------------------------ roulette

    public static final class Roulette extends Bandeau {
        private static final int[] ORDRE = {0, 32, 15, 19, 4, 21, 2, 25, 17, 34, 6, 27, 13, 36, 11, 30, 8, 23, 10, 5,
                24, 16, 33, 1, 20, 14, 31, 9, 22, 18, 29, 7, 28, 12, 35, 3, 26};
        private static final String[] PARIS = {"Rouge", "Noir", "Pair", "Impair", "1-18", "19-36", "1-12", "13-24", "25-36"};
        private int pari = -1;
        private EditBox numero;

        public Roulette() {
            super("Roulette", Casino.ROULETTE);
        }

        @Override
        protected void ajouterBoutons() {
            for (int i = 0; i < PARIS.length; i++) {
                int code = Casino.PARI_ROUGE + i;
                addRenderableWidget(Button.builder(Component.literal(PARIS[i]), b -> pari = code)
                        .bounds(left + 10 + (i % 5) * 60, top + 92 + (i / 5) * 22, 56, 18).build());
            }
            numero = addRenderableWidget(new EditBox(font, left + 190, top + 114, 34, 18, Component.literal("N°")));
            numero.setFilter(s -> s.matches("\\d{0,2}"));
            numero.setHint(Component.literal("n°"));
            addRenderableWidget(Button.builder(Component.literal("Plein"), b -> {
                if (!numero.getValue().isEmpty() && Integer.parseInt(numero.getValue()) <= 36) {
                    pari = Integer.parseInt(numero.getValue());
                }
            }).bounds(left + 228, top + 114, 82, 18).build());
        }

        @Override
        protected int choix() {
            return pari;
        }

        @Override
        protected String nomChoix() {
            if (pari < 0) {
                return "";
            }
            return pari <= 36 ? "n° " + pari + " (×36)" : PARIS[pari - Casino.PARI_ROUGE] + (pari >= Casino.PARI_DOUZAINE_1 ? " (×3)" : " (×2)");
        }

        @Override
        protected int nbCases() {
            return ORDRE.length;
        }

        @Override
        protected int caseDuTirage(int n) {
            for (int i = 0; i < ORDRE.length; i++) {
                if (ORDRE[i] == n) {
                    return i;
                }
            }
            return 0;
        }

        @Override
        protected void dessinerCase(GuiGraphics g, int indice, int x, int y, int w, int h) {
            int n = ORDRE[indice];
            int couleur = n == 0 ? 0xFF1E8449 : Casino.rouge(n) ? 0xFFC0392B : 0xFF1C1C1C;
            g.fill(x, y, x + w, y + h, couleur);
            g.drawCenteredString(font, Integer.toString(n), x + w / 2, y + h / 2 - 4, 0xFFFFFFFF);
        }

        @Override
        protected void dessinerJeu(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
            dessinerBandeau(g, top + 40, partialTick);
            g.drawString(font, "Couleur, parité, moitié : ×2  —  douzaine : ×3  —  numéro plein : ×36", left + 10, top + 80, Ui.GRIS, false);
        }
    }

    // ------------------------------------------------------------------ roue de la fortune

    public static final class Roue extends Bandeau {
        public Roue() {
            super("Roue de la fortune", Casino.ROUE);
        }

        @Override
        protected int choix() {
            return 0;
        }

        @Override
        protected int nbCases() {
            return Casino.ROUE_CASES.length;
        }

        @Override
        protected int caseDuTirage(int c) {
            return c;
        }

        private static String texte(int dixiemes) {
            return dixiemes == 0 ? "×0" : dixiemes % 10 == 0 ? "×" + dixiemes / 10 : "×" + dixiemes / 10 + "," + dixiemes % 10;
        }

        @Override
        protected void dessinerCase(GuiGraphics g, int indice, int x, int y, int w, int h) {
            int m = Casino.ROUE_CASES[indice];
            int couleur = switch (m) {
                case 0 -> 0xFF3A3F4A;
                case 15 -> 0xFF2E86C1;
                case 20 -> 0xFF28B463;
                case 30 -> 0xFFD68910;
                default -> 0xFFC0392B;
            };
            g.fill(x, y, x + w, y + h, couleur);
            g.drawCenteredString(font, texte(m), x + w / 2, y + h / 2 - 4, 0xFFFFFFFF);
        }

        @Override
        protected void dessinerJeu(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
            dessinerBandeau(g, top + 50, partialTick);
            g.drawCenteredString(font, "Lance la roue : ta mise est multipliée par la case.", left + W / 2, top + 100, Ui.GRIS);
        }
    }

    // ------------------------------------------------------------------ dés

    public static final class Des extends JeuScreen {
        private int pari = -1;
        private final int[] faces = {6, 1};

        public Des() {
            super("Dés : plus ou moins", Casino.DES);
        }

        @Override
        protected int dureeAnimation() {
            return 30;
        }

        @Override
        protected void ajouterBoutons() {
            String[] noms = {"Moins de 7 (×2,3)", "Pile 7 (×5,5)", "Plus de 7 (×2,3)"};
            for (int i = 0; i < 3; i++) {
                int code = i;
                addRenderableWidget(Button.builder(Component.literal(noms[i]), b -> pari = code).bounds(left + 10 + i * 102, top + 140, 98, 18).build());
            }
        }

        @Override
        protected int choix() {
            return pari;
        }

        @Override
        protected String nomChoix() {
            return pari < 0 ? "" : new String[] {"moins de 7", "pile 7", "plus de 7"}[pari];
        }

        @Override
        public void tick() {
            if (animation >= 0 && tirage != null) {
                if (animation >= dureeAnimation() - 8) {
                    faces[0] = tirage.get(0);
                    faces[1] = tirage.get(1);
                } else if (animation % 2 == 0) {
                    faces[0] = 1 + (faces[0] + 2) % 6;
                    faces[1] = 1 + (faces[1] + 4) % 6;
                }
            }
            super.tick();
        }

        private void de(GuiGraphics g, int x, int y, int face) {
            int t = 48;
            g.fill(x, y, x + t, y + t, 0xFFF7F3EA);
            g.fill(x, y + t - 3, x + t, y + t, 0xFFCFC8BA);
            int[][] points = switch (face) {
                case 1 -> new int[][] {{1, 1}};
                case 2 -> new int[][] {{0, 0}, {2, 2}};
                case 3 -> new int[][] {{0, 0}, {1, 1}, {2, 2}};
                case 4 -> new int[][] {{0, 0}, {2, 0}, {0, 2}, {2, 2}};
                case 5 -> new int[][] {{0, 0}, {2, 0}, {1, 1}, {0, 2}, {2, 2}};
                default -> new int[][] {{0, 0}, {2, 0}, {0, 1}, {2, 1}, {0, 2}, {2, 2}};
            };
            for (int[] p : points) {
                int px = x + 9 + p[0] * 13;
                int py = y + 9 + p[1] * 13;
                g.fill(px, py, px + 6, py + 6, 0xFF1C1C1C);
            }
        }

        @Override
        protected void dessinerJeu(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
            de(g, left + W / 2 - 56, top + 50, faces[0]);
            de(g, left + W / 2 + 8, top + 50, faces[1]);
            g.drawCenteredString(font, "Total : " + (faces[0] + faces[1]), left + W / 2, top + 112, 0xFFFFFFFF);
        }
    }

    // ------------------------------------------------------------------ pile ou face

    public static final class PileOuFace extends JeuScreen {
        private int pari = -1;
        private int cote = 0;

        public PileOuFace() {
            super("Pile ou face", Casino.PILE_OU_FACE);
        }

        @Override
        protected int dureeAnimation() {
            return 36;
        }

        @Override
        protected void ajouterBoutons() {
            addRenderableWidget(Button.builder(Component.literal("Pile"), b -> pari = 0).bounds(left + W / 2 - 104, top + 150, 100, 18).build());
            addRenderableWidget(Button.builder(Component.literal("Face"), b -> pari = 1).bounds(left + W / 2 + 4, top + 150, 100, 18).build());
        }

        @Override
        protected int choix() {
            return pari;
        }

        @Override
        protected String nomChoix() {
            return pari < 0 ? "" : pari == 0 ? "pile (×1,95)" : "face (×1,95)";
        }

        @Override
        protected void dessinerJeu(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
            float t = progression(partialTick);
            float largeur = 1F;
            if (animation >= 0 && tirage != null) {
                float angle = t * (float) Math.PI * 9;
                largeur = Math.abs(Mth.cos(angle));
                cote = t > 0.92F ? tirage.get(0) : (Mth.cos(angle) > 0 ? 0 : 1);
            }
            int cx = left + W / 2;
            int cy = top + 90;
            int r = 34;
            for (int dy = -r; dy <= r; dy++) {
                int demi = (int) (Math.sqrt(r * r - dy * dy) * largeur);
                g.fill(cx - demi, cy + dy, cx + demi + 1, cy + dy + 1, Math.abs(dy) > r - 3 ? 0xFFB7950B : 0xFFF1C40F);
            }
            if (largeur > 0.35F) {
                g.drawCenteredString(font, cote == 0 ? "PILE" : "FACE", cx, cy - 4, 0xFF7D5A00);
            }
            g.drawCenteredString(font, "Duel entre joueurs : /pileouface <joueur> <mise>", cx, top + 135, Ui.GRIS);
        }
    }

    private JeuxScreens() {
    }
}
