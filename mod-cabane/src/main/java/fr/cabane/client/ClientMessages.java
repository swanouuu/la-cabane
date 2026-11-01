package fr.cabane.client;

import fr.cabane.client.ui.Ios;
import fr.cabane.client.ui.Ui;
import fr.cabane.telephone.Messages;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.FormattedCharSequence;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

/** Messages, façon iMessage : la liste des conversations, une conversation en bulles, un nouveau message. */
public final class ClientMessages {
    static List<Messages.Resume> boite = List.of();
    /** La conversation affichée, et ses messages (du plus ancien au plus récent). */
    static String ouverte = "";
    static List<Messages.Sms> fil = List.of();
    private static final SystemToast.SystemToastId TOAST = new SystemToast.SystemToastId();

    static int nonLus() {
        return boite.stream().mapToInt(Messages.Resume::nonLus).sum();
    }

    public static void boite(Messages.Boite b) {
        boite = b.conversations();
        rafraichir();
    }

    public static void conversation(Messages.Conversation c) {
        if (c.numero().equals(ouverte)) {
            fil = c.messages();
        }
        rafraichir();
    }

    public static void recu(Messages.Recu r) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof Discussion d && d.numero.equals(r.numero())) {
            // la conversation est déjà ouverte : le message s'affiche directement (et compte comme lu)
            demander(Messages.Action.OUVRIR, r.numero(), "", 0);
            mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_BELL.value(), 1.8F, 0.4F));
            return;
        }
        SystemToast.addOrUpdate(mc.getToasts(), TOAST, Component.literal("Message — " + ClientPhone.nom(r.numero())),
                Component.literal(r.apercu().length() > 60 ? r.apercu().substring(0, 60) + "…" : r.apercu()));
        mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_BELL.value(), 1.5F, 0.8F));
    }

    static void demander(int action, String numero, String texte, long photo) {
        PacketDistributor.sendToServer(new Messages.Action(action, numero, texte, photo));
    }

    private static void rafraichir() {
        if (Minecraft.getInstance().screen instanceof ClientPiaf.Rafraichissable r) {
            r.rafraichir();
        }
    }

    // ---------------------------------------------------------------- la boîte

    /** Les conversations, la plus récente en haut ; un point bleu pour les non lues. */
    public static final class Boite extends ClientPiaf.Ecran {
        private static final int LIGNE = 34;
        private double defilement;

        public Boite() {
            super("Messages");
        }

        @Override
        protected void init() {
            super.init();
            boutonHaut("✎", () -> minecraft.setScreen(new Nouveau(0)));
            if (premiere) {
                demander(Messages.Action.BOITE, "", "", 0);
                ClientPhone.send(fr.cabane.telephone.PhoneNetwork.Action.ETAT, "");
            }
        }

        @Override
        public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
            defilement = Math.clamp(defilement - scrollY * 20, 0, Math.max(0, boite.size() * LIGNE - hauteur()));
            return true;
        }

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            if (mouseX >= x0() && mouseX < x0() + largeur() && mouseY >= y0() && mouseY < bas()) {
                int i = (int) ((mouseY - y0() + defilement) / LIGNE);
                if (i >= 0 && i < boite.size()) {
                    minecraft.setScreen(new Discussion(boite.get(i).numero(), 0));
                    return true;
                }
            }
            return super.mouseClicked(mouseX, mouseY, button);
        }

        @Override
        public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
            super.render(g, mouseX, mouseY, partialTick);
            if (boite.isEmpty()) {
                Ui.paragraphe(g, font, "Aucun message. Touche ✎ pour écrire à quelqu'un : il suffit de son numéro.", x0(), y0() + 20, largeur(), Ios.GRIS);
                return;
            }
            g.enableScissor(x0(), y0(), x0() + largeur(), bas());
            for (int i = 0; i < boite.size(); i++) {
                Messages.Resume r = boite.get(i);
                int y = y0() + i * LIGNE - (int) defilement;
                if (y + LIGNE < y0() || y > bas()) {
                    continue;
                }
                if (r.nonLus() > 0) {
                    Ios.disque(g, x0() + 3, y + 14, 3, Ios.BLEU);
                }
                String nom = ClientPhone.nom(r.numero());
                ClientPiaf.avatar(g, font, nom, x0() + 20, y + 14, 11);
                Component titre = Component.literal(Ui.couper(font, nom, largeur() - 90));
                g.drawString(font, r.nonLus() > 0 ? titre.copy().withStyle(s -> s.withBold(true)) : titre, x0() + 37, y + 4, Ios.BLANC, false);
                String quand = ClientPiaf.Fil.ilYa(r.temps());
                Ios.texte(g, font, quand, x0() + largeur() - font.width(quand) * 0.75F, y + 5, 0.75F, Ios.GRIS, false);
                Ios.texte(g, font, Ui.couper(font, r.apercu(), (int) ((largeur() - 40) / 0.75F)), x0() + 37, y + 17, 0.75F, Ios.GRIS, false);
                g.fill(x0() + 37, y + LIGNE - 2, x0() + largeur(), y + LIGNE - 1, Ios.SEPARATEUR);
            }
            g.disableScissor();
        }
    }

    // ---------------------------------------------------------------- nouveau message

    /** À qui écrire : un numéro tapé à la main, ou un contact du carnet. Peut porter une photo à envoyer. */
    public static final class Nouveau extends ClientPiaf.Ecran {
        private final long photo;
        private EditBox numero;

        public Nouveau(long photo) {
            super("Nouveau");
            this.photo = photo;
        }

        @Override
        protected void init() {
            super.init();
            if (premiere) {
                ClientPhone.send(fr.cabane.telephone.PhoneNetwork.Action.ETAT, "");
            }
            numero = champ(x0(), y0(), largeur() - 46, "numéro (3 ou 4 chiffres)");
            numero.setFilter(s -> s.matches("\\d{0,4}"));
            setInitialFocus(numero);
            addRenderableWidget(Ios.Bouton.plein(x0() + largeur() - 40, y0() - 2, 40, 22, "OK", Ios.BLEU, () -> {
                if (numero.getValue().length() >= 3) {
                    minecraft.setScreen(new Discussion(numero.getValue(), photo));
                }
            }));
            var liste = ClientPhone.contacts;
            for (int i = 0; i < Math.min(liste.size(), (bas() - y0() - 40) / 26); i++) {
                var c = liste.get(i);
                addRenderableWidget(Ios.Bouton.texte(x0(), y0() + 40 + i * 26, largeur(), 24, "", 0, () -> minecraft.setScreen(new Discussion(c.numero(), photo))));
            }
        }

        @Override
        public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
            super.render(g, mouseX, mouseY, partialTick);
            var liste = ClientPhone.contacts;
            Ios.texte(g, font, photo != 0 ? "📷 Une photo à envoyer — à qui ?" : "Contacts", x0(), y0() + 28, 0.75F, Ios.GRIS, false);
            for (int i = 0; i < Math.min(liste.size(), (bas() - y0() - 40) / 26); i++) {
                var c = liste.get(i);
                int y = y0() + 40 + i * 26;
                ClientPiaf.avatar(g, font, c.nom(), x0() + 10, y + 12, 9);
                g.drawString(font, Ui.couper(font, c.nom(), largeur() - 70), x0() + 25, y + 4, Ios.BLANC, false);
                Ios.texte(g, font, c.numero(), x0() + largeur() - font.width(c.numero()) * 0.75F, y + 6, 0.75F, Ios.GRIS, false);
                g.fill(x0() + 25, y + 24, x0() + largeur(), y + 25, Ios.SEPARATEUR);
            }
        }
    }

    // ---------------------------------------------------------------- une conversation

    /** Les bulles (bleues à droite pour soi, grises à gauche), le champ en bas, et l'appareil pour joindre une photo. */
    public static final class Discussion extends ClientPiaf.Ecran {
        final String numero;
        private long photo;
        private EditBox champ;
        private double defilement;
        private final List<int[]> zonesPhotos = new ArrayList<>();

        public Discussion(String numero, long photo) {
            super("");
            this.numero = numero;
            this.photo = photo;
        }

        private int haut() {
            return top + 60;
        }

        private int basBulles() {
            return bas() - (photo != 0 ? 58 : 28);
        }

        @Override
        protected void init() {
            super.init();
            if (premiere || !numero.equals(ouverte)) {
                ouverte = numero;
                fil = List.of();
                demander(Messages.Action.OUVRIR, numero, "", 0);
            }
            addRenderableWidget(Ios.Bouton.texte(left + 8, top + 18, 60, 14, "‹ Messages", Ios.BLEU, () -> minecraft.setScreen(new Boite())));
            addRenderableWidget(Ios.Bouton.texte(left + W - 30, top + 18, 22, 14, "☎", Ios.BLEU, () -> ClientPhone.appeler(numero)));
            addRenderableWidget(Ios.Bouton.texte(x0(), bas() - 22, 20, 18, "📷", Ios.BLEU,
                    () -> minecraft.setScreen(new ClientPiaf.Galerie(id -> minecraft.setScreen(new Discussion(numero, id))))));
            champ = champ(x0() + 22, bas() - 22, largeur() - 48, "Message");
            champ.setMaxLength(Messages.TEXTE_MAX);
            setInitialFocus(champ);
            addRenderableWidget(Ios.Bouton.plein(x0() + largeur() - 20, bas() - 21, 20, 18, "↑", Ios.BLEU, this::envoyer));
            if (photo != 0) {
                addRenderableWidget(Ios.Bouton.texte(x0() + 56, bas() - 54, 14, 14, "✕", Ios.GRIS, () -> {
                    photo = 0;
                    rebuildWidgets();
                }));
            }
        }

        private void envoyer() {
            String texte = champ.getValue().strip();
            if (texte.isEmpty() && photo == 0) {
                return;
            }
            demander(Messages.Action.ENVOYER, numero, texte, photo);
            champ.setValue("");
            defilement = 0;
            if (photo != 0) {
                photo = 0;
                rebuildWidgets();
            }
            minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.BUBBLE_COLUMN_UPWARDS_INSIDE, 1.6F, 0.5F));
        }

        @Override
        public boolean keyPressed(int key, int scanCode, int modifiers) {
            if ((key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) && getFocused() == champ) {
                envoyer();
                return true;
            }
            return super.keyPressed(key, scanCode, modifiers);
        }

        @Override
        public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
            defilement = Math.max(0, defilement + scrollY * 20);
            return true;
        }

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            for (int[] z : zonesPhotos) {
                if (mouseX >= z[0] && mouseX < z[0] + z[2] && mouseY >= z[1] && mouseY < z[1] + z[3] && mouseY >= haut() && mouseY < basBulles()) {
                    minecraft.setScreen(new ClientPiaf.Visionneuse(fil.get(z[4]).photo()));
                    return true;
                }
            }
            return super.mouseClicked(mouseX, mouseY, button);
        }

        @Override
        public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
            super.render(g, mouseX, mouseY, partialTick);
            String nom = ClientPhone.nom(numero);
            ClientPiaf.avatar(g, font, nom, left + W / 2, top + 32, 10);
            Ios.texteCentre(g, font, nom.equals(numero) ? nom : nom + " · " + numero, left + W / 2F, top + 46, 0.75F, Ios.BLANC, false);
            g.fill(left + 4, top + 57, left + W - 4, top + 58, Ios.SEPARATEUR);
            // les bulles, du bas vers le haut
            zonesPhotos.clear();
            int largeurMax = largeur() * 3 / 4;
            int y = basBulles() + (int) defilement;
            g.enableScissor(left + 2, haut(), left + W - 2, basBulles());
            if (fil.isEmpty()) {
                Ios.texteCentre(g, font, "Écris le premier message !", left + W / 2F, haut() + 30, 0.85F, Ios.GRIS, false);
            }
            for (int i = fil.size() - 1; i >= 0; i--) {
                Messages.Sms m = fil.get(i);
                List<FormattedCharSequence> lignes = m.texte().isEmpty() ? List.of() : font.split(Component.literal(m.texte()), largeurMax - 12);
                int largeurTexte = lignes.stream().mapToInt(font::width).max().orElse(0) + 12;
                int pw = m.photo() != 0 ? largeurMax - 12 : 0;
                int ph = m.photo() != 0 ? ClientPiaf.hauteurPhoto(m.photo(), pw, 110) : 0;
                int w = Math.max(largeurTexte, m.photo() != 0 ? pw + 8 : 0);
                int h = lignes.size() * 10 + (lignes.isEmpty() ? 0 : 8) + (ph > 0 ? ph + (lignes.isEmpty() ? 8 : 4) : 0);
                y -= h + 4;
                if (i == fil.size() - 1) {
                    String quand = ClientPiaf.Fil.ilYa(m.temps());
                    Ios.texte(g, font, quand, m.moi() ? x0() + largeur() - font.width(quand) * 0.6F : x0(), y + h + 1, 0.6F, Ios.GRIS, false);
                }
                if (y + h < haut() - 40) {
                    break;
                }
                int x = m.moi() ? x0() + largeur() - w : x0();
                Ios.rond(g, x, y, w, h, 8, m.moi() ? Ios.BLEU : 0xFF3A3A3C);
                int ty = y + 4;
                if (ph > 0) {
                    ClientPiaf.dessinerPhoto(g, m.photo(), x + 4, y + 4, pw, ph, false);
                    zonesPhotos.add(new int[] {x + 4, y + 4, pw, ph, i});
                    ty += ph + 4;
                }
                for (FormattedCharSequence ligne : lignes) {
                    g.drawString(font, ligne, x + 6, ty, Ios.BLANC, false);
                    ty += 10;
                }
            }
            g.disableScissor();
            // la photo jointe, prête à partir
            if (photo != 0) {
                ClientPiaf.dessinerPhoto(g, photo, x0(), bas() - 54, 52, 28, true);
                Ios.texte(g, font, "photo jointe", x0() + 72, bas() - 50, 0.7F, Ios.GRIS, false);
            }
        }
    }

    private ClientMessages() {
    }
}
