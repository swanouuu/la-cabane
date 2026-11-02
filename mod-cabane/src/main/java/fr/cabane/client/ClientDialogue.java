package fr.cabane.client;

import fr.cabane.Cabane;
import fr.cabane.client.ui.Ios;
import fr.cabane.pnj.Dialogues;
import fr.cabane.pnj.Missions;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

/**
 * La conversation avec un habitant, façon RPG : son portrait, son nom, le texte qui s'écrit, et les réponses
 * numérotées (1 à 9 au clavier). Les missions ont leur pastille : « ! » à prendre, « ? » en cours, « ✔ » à rendre.
 * Et en haut à droite de l'écran, le suivi des missions en cours.
 */
public final class ClientDialogue {

    public static void page(Dialogues.Afficher p) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof Ecran e && e.entite == p.entite()) {
            e.changer(p);
        } else {
            mc.setScreen(new Ecran(p));
        }
    }

    public static void fermer() {
        if (Minecraft.getInstance().screen instanceof Ecran) {
            Minecraft.getInstance().setScreen(null);
        }
    }

    // ================================================================ la fenêtre de conversation

    static final class Ecran extends Screen {
        private static final int PORTRAIT = 74;
        private static final int LIGNE = 11;
        final int entite;
        private Dialogues.Afficher page;
        private List<FormattedCharSequence> lignes = List.of();
        private int lettres;
        private float ecrit;
        private boolean fini;
        private int survol = -1;
        // la géométrie, recalculée à chaque page
        private int px;
        private int py;
        private int pw;
        private int ph;
        private int cx;
        private int cy;
        private int cw;
        private static final int CH = 17;

        Ecran(Dialogues.Afficher p) {
            super(Component.literal(p.nom()));
            this.entite = p.entite();
            this.page = p;
        }

        void changer(Dialogues.Afficher p) {
            this.page = p;
            mettreEnPage();
        }

        @Override
        protected void init() {
            mettreEnPage();
        }

        @Override
        public boolean isPauseScreen() {
            return false;
        }

        private void mettreEnPage() {
            pw = Math.min(width - 16, 470);
            px = (width - pw) / 2;
            int texteL = pw - PORTRAIT - 22;
            lignes = new ArrayList<>();
            for (String para : page.texte().split("\n", -1)) {
                if (para.isEmpty()) {
                    lignes.add(FormattedCharSequence.EMPTY);
                } else {
                    lignes.addAll(font.split(FormattedText.of(para), texteL));
                }
            }
            lettres = page.texte().replace("\n", "").length();
            ecrit = 0;
            // pendant les captures automatiques (outil PhotoTour), le texte s'affiche d'un coup
            fini = System.getenv("CABANE_PHOTOS") != null;
            ph = Math.max(PORTRAIT + 14, 28 + lignes.size() * LIGNE + 10);
            ph = Math.min(ph, height - 40);
            py = height - ph - 8;
            // les réponses : une colonne à droite, au-dessus du panneau (deux colonnes s'il y en a beaucoup)
            cw = 0;
            for (Dialogues.Choix c : page.choix()) {
                cw = Math.max(cw, font.width(c.texte() + (c.style() == Dialogues.EN_COURS ? " (en cours)" : "")) + 48);
            }
            cw = Mth.clamp(cw, 150, Math.max(150, pw - 40));
            int place = py - 10;
            int parColonne = Math.max(1, place / (CH + 3));
            if (page.choix().size() > parColonne && cw * 2 + 6 <= pw) {
                cx = px + pw - cw * 2 - 6;
            } else {
                cx = px + pw - cw;
            }
            cy = py - 6;
        }

        /** La case du choix i : {x, y} (haut-gauche). */
        private int[] caseChoix(int i) {
            int parColonne = Math.max(1, (py - 10) / (CH + 3));
            int colonne = i / parColonne;
            int rang = i % parColonne;
            int n = Math.min(parColonne, page.choix().size() - colonne * parColonne);
            return new int[] {cx + colonne * (cw + 6), cy - (n - rang) * (CH + 3)};
        }

        @Override
        public void tick() {
            if (!fini) {
                ecrit += 3.5F;
                if (ecrit >= lettres) {
                    fini = true;
                }
            }
            Entity e = minecraft.level == null ? null : minecraft.level.getEntity(entite);
            if (e == null || minecraft.player == null || e.distanceToSqr(minecraft.player) > 14 * 14) {
                onClose();
            }
        }

        @Override
        public void renderBackground(GuiGraphics g, int mx, int my, float delta) {
            // le monde reste visible : juste un voile sombre vers le bas, sous le panneau
            g.fillGradient(0, height / 2, width, height, 0x00000000, 0x90000000);
        }

        @Override
        public void render(GuiGraphics g, int mx, int my, float delta) {
            super.render(g, mx, my, delta);
            int couleur = 0xFF000000 | page.couleur();
            // le panneau
            Ios.rond(g, px - 1, py - 1, pw + 2, ph + 2, 8, 0xFF0B0C10);
            Ios.rond(g, px, py, pw, ph, 7, 0xF01B1E25);
            g.fill(px + 8, py + 1, px + pw - 8, py + 2, (couleur & 0x00FFFFFF) | 0x90000000);
            // le portrait
            int fx = px + 8;
            int fy = py + 8;
            Ios.rond(g, fx, fy, PORTRAIT, PORTRAIT, 6, 0xFF101218);
            g.fillGradient(fx + 2, fy + 2, fx + PORTRAIT - 2, fy + PORTRAIT - 2, (couleur & 0x00FFFFFF) | 0x50000000, 0x10000000);
            Entity e = minecraft.level == null ? null : minecraft.level.getEntity(entite);
            if (e instanceof LivingEntity vivant) {
                float h = Math.max(1.4F, vivant.getBbHeight());
                int echelle = (int) (PORTRAIT * 1.05F / h);
                InventoryScreen.renderEntityInInventoryFollowsMouse(g, fx + 2, fy + 2, fx + PORTRAIT - 2, fy + PORTRAIT - 2, echelle, 0.0625F,
                        mx, my, vivant);
            }
            // le nom, dans un onglet au-dessus du panneau, à gauche
            String nom = page.nom();
            int nw = font.width(nom) + 16;
            Ios.rond(g, px + 8, py - 13, nw, 15, 5, 0xFF0B0C10);
            Ios.rond(g, px + 9, py - 12, nw - 2, 13, 4, 0xFF262A33);
            g.drawString(font, nom, px + 16, py - 9, couleur, true);
            // le titre de la page et le texte qui s'écrit
            int tx = fx + PORTRAIT + 12;
            g.drawString(font, page.titre(), tx, py + 9, 0xFFFFD75E, false);
            int reste = fini ? Integer.MAX_VALUE : (int) ecrit;
            int y = py + 23;
            for (FormattedCharSequence l : lignes) {
                if (reste <= 0 || y > py + ph - LIGNE) {
                    break;
                }
                int n = longueur(l);
                g.drawString(font, n <= reste ? l : tronquer(l, reste), tx, y, 0xFFE8EAF0, false);
                reste -= n;
                y += LIGNE;
            }
            if (!fini) {
                if ((System.currentTimeMillis() / 300) % 2 == 0) {
                    g.drawString(font, "▼", px + pw - 14, py + ph - 12, 0xFF8E8E93, false);
                }
                return;
            }
            // les réponses
            survol = -1;
            for (int i = 0; i < page.choix().size(); i++) {
                int[] c = caseChoix(i);
                boolean dessus = mx >= c[0] && mx < c[0] + cw && my >= c[1] && my < c[1] + CH;
                if (dessus) {
                    survol = i;
                }
                choix(g, page.choix().get(i), i, c[0], c[1], dessus);
            }
        }

        private void choix(GuiGraphics g, Dialogues.Choix c, int i, int x, int y, boolean dessus) {
            int fond = dessus ? 0xF03A3F4C : 0xE0202329;
            int accent = switch (c.style()) {
                case Dialogues.DISPO -> 0xFFFFC23D;
                case Dialogues.EN_COURS -> 0xFF9AA0AA;
                case Dialogues.RENDRE -> 0xFF4CD964;
                case Dialogues.QUESTION -> 0xFF4FC3F7;
                case Dialogues.BOUTIQUE -> 0xFFC77DFF;
                case Dialogues.AU_REVOIR -> 0xFF5A5F6A;
                default -> 0xFF8E8E93;
            };
            Ios.rond(g, x, y, cw, CH, 5, dessus ? accent : 0xFF0B0C10);
            Ios.rond(g, x + 1, y + 1, cw - 2, CH - 2, 4, fond);
            // la pastille (et le numéro au clavier)
            String icone = switch (c.style()) {
                case Dialogues.DISPO -> "!";
                case Dialogues.EN_COURS -> "?";
                case Dialogues.RENDRE -> "✔";
                case Dialogues.QUESTION -> "?";
                case Dialogues.BOUTIQUE -> "$";
                case Dialogues.AU_REVOIR -> "×";
                default -> "▸";
            };
            if (c.style() == Dialogues.NORMAL) {
                g.drawString(font, icone, x + 8, y + 5, dessus ? 0xFFFFFFFF : accent, false);
            } else {
                Ios.disque(g, x + 10, y + CH / 2, 6, accent);
                g.drawCenteredString(font, icone, x + 10, y + 5, c.style() == Dialogues.AU_REVOIR ? 0xFFFFFFFF : 0xFF14161B);
            }
            int couleur = switch (c.style()) {
                case Dialogues.DISPO -> 0xFFFFE08A;
                case Dialogues.RENDRE -> 0xFFB8F5C4;
                case Dialogues.EN_COURS, Dialogues.AU_REVOIR -> 0xFFB4BCC8;
                case Dialogues.QUESTION -> 0xFFB3E5FC;
                default -> 0xFFFFFFFF;
            };
            String texte = c.texte();
            if (c.style() == Dialogues.EN_COURS) {
                texte += " (en cours)";
            }
            texte = font.plainSubstrByWidth(texte, cw - 34);
            g.drawString(font, texte, x + 21, y + 5, couleur, false);
            if (i < 9) {
                g.drawString(font, String.valueOf(i + 1), x + cw - 10, y + 5, 0xFF5A5F6A, false);
            }
        }

        private static int longueur(FormattedCharSequence l) {
            int[] n = {0};
            l.accept((index, style, code) -> {
                n[0]++;
                return true;
            });
            return n[0];
        }

        private static FormattedCharSequence tronquer(FormattedCharSequence l, int max) {
            return sink -> {
                int[] n = {0};
                return l.accept((index, style, code) -> n[0]++ >= max || sink.accept(index, style, code));
            };
        }

        private void choisir(int i) {
            if (i < 0 || i >= page.choix().size()) {
                return;
            }
            minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK.value(), 1F, 0.6F));
            PacketDistributor.sendToServer(new Dialogues.Choisir(i));
            if (page.choix().get(i).style() == Dialogues.AU_REVOIR) {
                onClose();
            }
        }

        @Override
        public boolean mouseClicked(double mx, double my, int bouton) {
            if (!fini) {
                fini = true;
                return true;
            }
            if (survol >= 0) {
                choisir(survol);
                return true;
            }
            return super.mouseClicked(mx, my, bouton);
        }

        @Override
        public boolean keyPressed(int touche, int scan, int mods) {
            if (touche == GLFW.GLFW_KEY_SPACE || touche == GLFW.GLFW_KEY_ENTER) {
                if (!fini) {
                    fini = true;
                } else if (page.choix().size() == 1) {
                    choisir(0);
                }
                return true;
            }
            if (fini && touche >= GLFW.GLFW_KEY_1 && touche <= GLFW.GLFW_KEY_9) {
                choisir(touche - GLFW.GLFW_KEY_1);
                return true;
            }
            if (fini && touche >= GLFW.GLFW_KEY_KP_1 && touche <= GLFW.GLFW_KEY_KP_9) {
                choisir(touche - GLFW.GLFW_KEY_KP_1);
                return true;
            }
            return super.keyPressed(touche, scan, mods);
        }
    }

    // ================================================================ le suivi des missions à l'écran

    private static List<Missions.Ligne> suivi = List.of();

    public static void suivi(List<Missions.Ligne> lignes) {
        suivi = lignes;
    }

    private static void dessinerSuivi(GuiGraphics g) {
        Minecraft mc = Minecraft.getInstance();
        if (suivi.isEmpty() || mc.options.hideGui || mc.player == null || mc.screen instanceof Ecran) {
            return;
        }
        Font font = mc.font;
        int largeur = 156;
        int x = g.guiWidth() - largeur - 4;
        int y = g.guiHeight() / 4;
        int montrees = Math.min(3, suivi.size());
        int h = 14;
        List<List<String>> blocs = new ArrayList<>();
        for (int i = 0; i < montrees; i++) {
            List<String> b = new ArrayList<>();
            for (String o : suivi.get(i).objectifs()) {
                b.addAll(font.getSplitter().splitLines(o, largeur - 12, net.minecraft.network.chat.Style.EMPTY).stream().map(FormattedText::getString).toList());
            }
            blocs.add(b);
            h += 11 + b.size() * 9 + 3;
        }
        if (suivi.size() > montrees) {
            h += 10;
        }
        g.fill(x, y, x + largeur, y + h, 0x70000000);
        g.fill(x, y, x + 2, y + h, 0xFFFFC23D);
        g.drawString(font, "MISSIONS", x + 7, y + 4, 0xFFFFC23D, false);
        int yy = y + 15;
        for (int i = 0; i < montrees; i++) {
            Missions.Ligne l = suivi.get(i);
            g.drawString(font, font.plainSubstrByWidth(l.titre(), largeur - 10), x + 7, yy, l.prete() ? 0xFF7CFF9B : 0xFFFFFFFF, true);
            yy += 11;
            for (String o : blocs.get(i)) {
                int c = o.startsWith("✔") ? 0xFF7CFF9B : o.startsWith("→") ? 0xFF7CFF9B : 0xFFB4BCC8;
                g.pose().pushPose();
                g.pose().translate(x + 9, yy, 0);
                g.pose().scale(0.9F, 0.9F, 1F);
                g.drawString(font, o, 0, 0, c, false);
                g.pose().popPose();
                yy += 9;
            }
            yy += 3;
        }
        if (suivi.size() > montrees) {
            g.drawString(font, "+" + (suivi.size() - montrees) + " autre(s) : /cabane missions", x + 7, yy, 0xFF8E8E93, false);
        }
    }

    @EventBusSubscriber(modid = Cabane.MODID, value = Dist.CLIENT)
    public static final class Couches {
        @SubscribeEvent
        public static void enregistrer(RegisterGuiLayersEvent event) {
            event.registerAboveAll(Cabane.id("missions"), (g, delta) -> dessinerSuivi(g));
        }

        private Couches() {
        }
    }

    private ClientDialogue() {
    }
}
