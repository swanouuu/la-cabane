package fr.cabane.client;

import com.mojang.blaze3d.platform.NativeImage;
import fr.cabane.Cabane;
import fr.cabane.client.ui.Ui;
import fr.cabane.economie.Economie;
import fr.cabane.telephone.Piaf;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.components.PlayerFaceRenderer;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.FormattedCharSequence;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/** Le téléphone côté client : écran d'accueil, Piaf, appareil photo, galerie, portefeuille. */
@EventBusSubscriber(modid = Cabane.MODID, value = Dist.CLIENT)
public final class ClientPiaf {
    static String monPseudo = "";
    static List<Piaf.Vue> posts = List.of();
    static List<Long> galerie = List.of();
    private static final Map<Long, ResourceLocation> PHOTOS = new HashMap<>();
    private static final Set<Long> DEMANDEES = new HashSet<>();
    private static final SystemToast.SystemToastId TOAST = new SystemToast.SystemToastId();
    private static int envoi;

    // ---------------------------------------------------------------- réception

    public static void fil(Piaf.Fil fil) {
        monPseudo = fil.monPseudo();
        posts = fil.posts();
        galerie = fil.galerie();
        if (Minecraft.getInstance().screen instanceof Rafraichissable r) {
            r.rafraichir();
        }
    }

    public static void photo(long id, byte[] png) {
        try {
            NativeImage image = NativeImage.read(png);
            ResourceLocation rl = Cabane.id("photo/" + id);
            Minecraft.getInstance().getTextureManager().register(rl, new DynamicTexture(image));
            PHOTOS.put(id, rl);
        } catch (Exception e) {
            Cabane.LOGGER.warn("Photo {} illisible : {}", id, e.toString());
        }
    }

    public static void notification(String pseudo, String texte) {
        Minecraft mc = Minecraft.getInstance();
        SystemToast.addOrUpdate(mc.getToasts(), TOAST, Component.literal("Piaf — @" + pseudo),
                Component.literal(texte.length() > 60 ? texte.substring(0, 60) + "…" : texte));
        mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_CHIME.value(), 1.6F, 0.6F));
    }

    static void demander(int action, String texte, long nombre) {
        PacketDistributor.sendToServer(new Piaf.Action(action, texte, nombre));
    }

    /** La texture d'une photo, demandée au serveur la première fois. */
    static ResourceLocation photo(long id) {
        ResourceLocation rl = PHOTOS.get(id);
        if (rl == null && DEMANDEES.add(id)) {
            PacketDistributor.sendToServer(new Piaf.DemandePhoto(id));
        }
        return rl;
    }

    interface Rafraichissable {
        void rafraichir();
    }

    // ---------------------------------------------------------------- appareil photo

    private static int photoDans = -1;
    private static boolean guiAvant;

    /** Ferme le téléphone, masque l'interface, et prend la photo deux images plus tard. */
    static void prendrePhoto() {
        Minecraft mc = Minecraft.getInstance();
        mc.setScreen(null);
        guiAvant = mc.options.hideGui;
        mc.options.hideGui = true;
        photoDans = 3;
    }

    @SubscribeEvent
    public static void onTick(ClientTickEvent.Post event) {
        if (photoDans < 0 || --photoDans > 0) {
            return;
        }
        photoDans = -1;
        Minecraft mc = Minecraft.getInstance();
        try (NativeImage ecran = Screenshot.takeScreenshot(mc.getMainRenderTarget());
             NativeImage photo = new NativeImage(320, 180, false)) {
            int w = ecran.getWidth();
            int h = w * 9 / 16;
            int y = 0;
            if (h > ecran.getHeight()) {
                h = ecran.getHeight();
                w = h * 16 / 9;
            } else {
                y = (ecran.getHeight() - h) / 2;
            }
            ecran.resizeSubRectTo((ecran.getWidth() - w) / 2, y, w, h, photo);
            envoyer(photo.asByteArray());
            mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_CARTOGRAPHY_TABLE_TAKE_RESULT, 1.8F));
        } catch (Exception e) {
            Cabane.LOGGER.warn("Photo ratée : {}", e.toString());
        } finally {
            mc.options.hideGui = guiAvant;
        }
    }

    private static void envoyer(byte[] png) {
        int taille = Piaf.tailleMorceau();
        int total = (png.length + taille - 1) / taille;
        envoi++;
        for (int i = 0; i < total; i++) {
            int debut = i * taille;
            byte[] morceau = java.util.Arrays.copyOfRange(png, debut, Math.min(png.length, debut + taille));
            PacketDistributor.sendToServer(new Piaf.Morceau(envoi, i, total, morceau));
        }
    }

    // ---------------------------------------------------------------- écrans

    /** Le cadre commun : un téléphone en portrait, avec un bouton d'accueil en bas. */
    abstract static class Ecran extends Screen implements Rafraichissable {
        static final int W = 190;
        static final int H = 310;
        int left;
        int top;

        Ecran(String titre) {
            super(Component.literal(titre));
        }

        int x0() {
            return left + 8;
        }

        int y0() {
            return top + 26;
        }

        int largeur() {
            return W - 16;
        }

        int hauteur() {
            return H - 58;
        }

        @Override
        protected void init() {
            left = (width - W) / 2;
            top = (height - H) / 2;
            addRenderableWidget(Button.builder(Component.literal("⌂"), b -> minecraft.setScreen(new Accueil()))
                    .bounds(left + W / 2 - 14, top + H - 26, 28, 18).build());
        }

        @Override
        public void rafraichir() {
            rebuildWidgets();
        }

        @Override
        public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
            super.renderBackground(g, mouseX, mouseY, partialTick);
            g.fill(left - 4, top - 4, left + W + 4, top + H + 4, 0xFF0B0B0F);
            g.fill(left, top, left + W, top + H, 0xFF14161B);
            g.fill(left + W / 2 - 18, top + 6, left + W / 2 + 18, top + 9, 0xFF2A2E36);
            g.drawString(font, title.getString(), x0(), top + 12, 0xFFFFFFFF, false);
            Ui.solde(g, font, left + W - 8, top + 12);
        }

        @Override
        public boolean isPauseScreen() {
            return false;
        }
    }

    /** L'écran d'accueil : une grille d'applis. */
    public static final class Accueil extends Ecran {
        public Accueil() {
            super("Mon téléphone");
        }

        @Override
        protected void init() {
            super.init();
            String[] noms = {"Appels", "Piaf", "Photo", "Galerie", "Argent"};
            Runnable[] actions = {
                    () -> minecraft.setScreen(new PhoneScreen()),
                    () -> minecraft.setScreen(new Fil()),
                    ClientPiaf::prendrePhoto,
                    () -> minecraft.setScreen(new Galerie(false)),
                    () -> minecraft.setScreen(new Portefeuille())};
            for (int i = 0; i < noms.length; i++) {
                int col = i % 3;
                int lig = i / 3;
                Runnable a = actions[i];
                addRenderableWidget(Button.builder(Component.literal(noms[i]), b -> a.run())
                        .bounds(x0() + 4 + col * 56, y0() + 30 + lig * 56, 52, 44).build());
            }
            demander(0, "", 0);
        }

        @Override
        public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
            super.render(g, mouseX, mouseY, partialTick);
            g.drawCenteredString(font, monPseudo.isEmpty() ? "" : "@" + monPseudo, left + W / 2, y0() + 8, 0xFF7FB7FF);
            g.drawCenteredString(font, "Photo : vise, puis clique.", left + W / 2, y0() + 160, Ui.GRIS);
        }
    }

    /** Le fil Piaf, façon X : avatar, pseudo, texte, photo, « j'aime ». */
    static final class Fil extends Ecran {
        private double defilement;
        private final List<int[]> zonesJaime = new ArrayList<>();
        private EditBox champPseudo;

        Fil() {
            super("Piaf");
        }

        @Override
        protected void init() {
            super.init();
            if (monPseudo.isEmpty()) {
                champPseudo = addRenderableWidget(new EditBox(font, x0() + 10, y0() + 70, largeur() - 20, 18, Component.literal("pseudo")));
                champPseudo.setMaxLength(16);
                champPseudo.setFilter(s -> s.matches("[A-Za-z0-9_]*"));
                champPseudo.setHint(Component.literal("ton pseudo"));
                addRenderableWidget(Button.builder(Component.literal("Créer mon compte"), b -> demander(1, champPseudo.getValue(), 0))
                        .bounds(x0() + 10, y0() + 94, largeur() - 20, 18).build());
            } else {
                addRenderableWidget(Button.builder(Component.literal("+ Publier"), b -> minecraft.setScreen(new Publier(0)))
                        .bounds(left + W - 70, top + H - 26, 62, 18).build());
            }
            demander(0, "", 0);
        }

        @Override
        public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
            defilement = Math.max(0, defilement - scrollY * 24);
            return true;
        }

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            for (int[] z : zonesJaime) {
                if (mouseX >= z[0] && mouseX < z[0] + z[2] && mouseY >= z[1] && mouseY < z[1] + 10) {
                    demander(3, "", posts.get(z[3]).id());
                    return true;
                }
            }
            return super.mouseClicked(mouseX, mouseY, button);
        }

        @Override
        public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
            super.render(g, mouseX, mouseY, partialTick);
            if (monPseudo.isEmpty()) {
                Ui.paragraphe(g, font, "Bienvenue sur Piaf ! Choisis ton pseudo pour publier et voir ce que les autres racontent.",
                        x0() + 10, y0() + 20, largeur() - 20, 0xFFFFFFFF);
                return;
            }
            zonesJaime.clear();
            g.enableScissor(x0(), y0(), x0() + largeur(), y0() + hauteur());
            int y = y0() + 2 - (int) defilement;
            if (posts.isEmpty()) {
                g.drawCenteredString(font, "Personne n'a encore publié.", left + W / 2, y0() + 40, Ui.GRIS);
            }
            for (int i = 0; i < posts.size(); i++) {
                y = dessinerPost(g, i, posts.get(i), y);
            }
            g.disableScissor();
            defilement = Math.min(defilement, Math.max(0, y + defilement - (y0() + hauteur())));
        }

        private int dessinerPost(GuiGraphics g, int index, Piaf.Vue p, int y) {
            int x = x0() + 2;
            int w = largeur() - 4;
            PlayerInfo info = minecraft.getConnection() == null ? null : minecraft.getConnection().getPlayerInfo(p.auteur());
            PlayerFaceRenderer.draw(g, info != null ? info.getSkin() : DefaultPlayerSkin.get(p.auteur()), x, y + 2, 16);
            g.drawString(font, Component.literal(p.pseudo()).withStyle(s -> s.withBold(true)), x + 20, y + 2, 0xFFFFFFFF, false);
            g.drawString(font, "@" + p.pseudo() + " · " + ilYa(p.temps()), x + 20, y + 11, 0xFF8899A6, false);
            int ty = y + 22;
            for (FormattedCharSequence ligne : font.split(Component.literal(p.texte()), w)) {
                g.drawString(font, ligne, x, ty, 0xFFE7E9EA, false);
                ty += 10;
            }
            if (p.photo() != 0) {
                int ph = w * 9 / 16;
                ResourceLocation rl = photo(p.photo());
                if (rl != null) {
                    g.blit(rl, x, ty + 2, w, ph, 0, 0, 320, 180, 320, 180);
                } else {
                    g.fill(x, ty + 2, x + w, ty + 2 + ph, 0xFF22262E);
                    g.drawCenteredString(font, "📷 …", x + w / 2, ty + ph / 2, Ui.GRIS);
                }
                ty += ph + 4;
            }
            String jaime = (p.aime() ? "♥ " : "♡ ") + p.likes();
            g.drawString(font, jaime, x, ty + 2, p.aime() ? 0xFFF91880 : 0xFF8899A6, false);
            zonesJaime.add(new int[] {x, ty + 2, font.width(jaime) + 4, index});
            int fin = ty + 16;
            g.fill(x0(), fin - 3, x0() + largeur(), fin - 2, 0xFF2F3336);
            return fin;
        }

        private static String ilYa(long temps) {
            long s = (System.currentTimeMillis() - temps) / 1000;
            if (s < 60) {
                return "à l'instant";
            }
            if (s < 3600) {
                return s / 60 + " min";
            }
            if (s < 86400) {
                return s / 3600 + " h";
            }
            return s / 86400 + " j";
        }
    }

    /** Écrire une publication, éventuellement avec une photo de la galerie. */
    static final class Publier extends Ecran {
        private long photo;
        private MultiLineEditBox champ;
        private String brouillon = "";

        Publier(long photo) {
            super("Nouvelle publication");
            this.photo = photo;
        }

        @Override
        protected void init() {
            super.init();
            champ = addRenderableWidget(new MultiLineEditBox(font, x0(), y0(), largeur(), 90, Component.literal("Quoi de neuf ?"), Component.empty()));
            champ.setCharacterLimit(Piaf.TEXTE_MAX);
            champ.setValue(brouillon);
            addRenderableWidget(Button.builder(Component.literal(photo == 0 ? "📷 Joindre une photo" : "Changer de photo"), b -> {
                brouillon = champ.getValue();
                minecraft.setScreen(new Galerie(true));
            }).bounds(x0(), y0() + 96, largeur(), 18).build());
            addRenderableWidget(Button.builder(Component.literal("Publier"), b -> {
                demander(2, champ.getValue(), photo);
                minecraft.setScreen(new Fil());
            }).bounds(left + W - 70, top + H - 26, 62, 18).build());
        }

        @Override
        public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
            super.render(g, mouseX, mouseY, partialTick);
            if (photo != 0) {
                ResourceLocation rl = photo(photo);
                int w = largeur();
                if (rl != null) {
                    g.blit(rl, x0(), y0() + 120, w, w * 9 / 16, 0, 0, 320, 180, 320, 180);
                }
            }
            g.drawString(font, champ.getValue().length() + "/" + Piaf.TEXTE_MAX, x0(), top + H - 40, Ui.GRIS, false);
        }
    }

    /** Les photos prises. En mode « choisir », un clic renvoie vers la publication. */
    static final class Galerie extends Ecran {
        private final boolean choisir;

        Galerie(boolean choisir) {
            super(choisir ? "Choisis une photo" : "Galerie");
            this.choisir = choisir;
        }

        @Override
        protected void init() {
            super.init();
            demander(0, "", 0);
        }

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            List<Long> liste = galerieInverse();
            for (int i = 0; i < liste.size(); i++) {
                int[] r = vignette(i);
                if (mouseX >= r[0] && mouseX < r[0] + r[2] && mouseY >= r[1] && mouseY < r[1] + r[3]) {
                    minecraft.setScreen(new Publier(liste.get(i)));
                    return true;
                }
            }
            return super.mouseClicked(mouseX, mouseY, button);
        }

        private List<Long> galerieInverse() {
            List<Long> l = new ArrayList<>(galerie);
            java.util.Collections.reverse(l);
            return l.subList(0, Math.min(12, l.size()));
        }

        private int[] vignette(int i) {
            int w = (largeur() - 4) / 2;
            int h = w * 9 / 16;
            return new int[] {x0() + (i % 2) * (w + 4), y0() + (i / 2) * (h + 4), w, h};
        }

        @Override
        public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
            super.render(g, mouseX, mouseY, partialTick);
            List<Long> liste = galerieInverse();
            if (liste.isEmpty()) {
                Ui.paragraphe(g, font, "Aucune photo. Ouvre l'appli Photo, vise, et c'est dans la boîte !", x0() + 4, y0() + 20, largeur() - 8, Ui.GRIS);
            }
            for (int i = 0; i < liste.size(); i++) {
                int[] r = vignette(i);
                ResourceLocation rl = photo(liste.get(i));
                if (rl != null) {
                    g.blit(rl, r[0], r[1], r[2], r[3], 0, 0, 320, 180, 320, 180);
                } else {
                    g.fill(r[0], r[1], r[0] + r[2], r[1] + r[3], 0xFF22262E);
                }
            }
            if (!liste.isEmpty()) {
                g.drawCenteredString(font, "Clique sur une photo pour la publier", left + W / 2, top + H - 40, Ui.GRIS);
            }
        }
    }

    /** Le portefeuille : ton solde, et envoyer de l'argent à quelqu'un. */
    static final class Portefeuille extends Ecran {
        private EditBox joueur;
        private EditBox montant;

        Portefeuille() {
            super("Portefeuille");
        }

        @Override
        protected void init() {
            super.init();
            joueur = addRenderableWidget(new EditBox(font, x0() + 6, y0() + 80, largeur() - 12, 18, Component.literal("joueur")));
            joueur.setHint(Component.literal("nom du joueur"));
            montant = addRenderableWidget(new EditBox(font, x0() + 6, y0() + 104, largeur() - 12, 18, Component.literal("montant")));
            montant.setFilter(s -> s.matches("\\d{0,9}"));
            montant.setHint(Component.literal("montant en $"));
            addRenderableWidget(Button.builder(Component.literal("Envoyer"), b -> {
                if (!joueur.getValue().isBlank() && !montant.getValue().isEmpty()) {
                    minecraft.player.connection.sendCommand("payer " + joueur.getValue().trim() + " " + montant.getValue());
                    montant.setValue("");
                }
            }).bounds(x0() + 6, y0() + 128, largeur() - 12, 18).build());
        }

        @Override
        public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
            super.render(g, mouseX, mouseY, partialTick);
            g.drawCenteredString(font, Economie.MONNAIE, left + W / 2, y0() + 14, Ui.GRIS);
            g.pose().pushPose();
            g.pose().translate(left + W / 2F, y0() + 30, 0);
            g.pose().scale(2F, 2F, 1F);
            g.drawCenteredString(font, ClientEconomie.solde < 0 ? "…" : Economie.format(ClientEconomie.solde), 0, 0, Ui.OR);
            g.pose().popPose();
            g.drawString(font, "Envoyer de l'argent", x0() + 6, y0() + 68, 0xFFFFFFFF, false);
        }
    }

    private ClientPiaf() {
    }
}
