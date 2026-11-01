package fr.cabane.client;

import com.mojang.blaze3d.platform.NativeImage;
import fr.cabane.Cabane;
import fr.cabane.client.ui.Ios;
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
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.components.PlayerFaceRenderer;
import net.minecraft.client.gui.components.events.GuiEventListener;
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

/** Le téléphone côté client, façon iPhone : écran d'accueil, contacts, récents, Piaf, photos, portefeuille, réglages. */
@EventBusSubscriber(modid = Cabane.MODID, value = Dist.CLIENT)
public final class ClientPiaf {
    static String monPseudo = "";
    static List<Piaf.Vue> posts = List.of();
    static List<Long> galerie = List.of();
    private static final Map<Long, ResourceLocation> PHOTOS = new HashMap<>();
    private static final Map<Long, int[]> TAILLES = new HashMap<>();
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
            // depuis un flux : la version « tableau » passe par la petite pile de LWJGL et échoue au-delà de 64 Ko
            NativeImage image = NativeImage.read(new java.io.ByteArrayInputStream(png));
            ResourceLocation rl = Cabane.id("photo/" + id);
            TAILLES.put(id, new int[] {image.getWidth(), image.getHeight()});
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

    /** Largeur et hauteur d'une photo en pixels (paysage 320 x 180, portrait 180 x 320). */
    static int[] taille(long id) {
        return TAILLES.getOrDefault(id, new int[] {320, 180});
    }

    /** La hauteur d'une photo affichée sur cette largeur, sans dépasser max. */
    static int hauteurPhoto(long id, int largeur, int max) {
        int[] t = taille(id);
        return Math.min(max, largeur * t[1] / t[0]);
    }

    /** Dessine une photo dans un cadre : rognée pour le remplir, ou entière avec des bandes noires. */
    static void dessinerPhoto(GuiGraphics g, long id, int x, int y, int w, int h, boolean remplir) {
        ResourceLocation rl = photo(id);
        if (rl == null) {
            g.fill(x, y, x + w, y + h, 0xFF22262E);
            return;
        }
        int[] t = taille(id);
        float cadre = w / (float) h;
        float image = t[0] / (float) t[1];
        if (remplir) {
            int uw = t[0];
            int vh = t[1];
            if (image > cadre) {
                uw = Math.round(t[1] * cadre);
            } else {
                vh = Math.round(t[0] / cadre);
            }
            g.blit(rl, x, y, w, h, (t[0] - uw) / 2F, (t[1] - vh) / 2F, uw, vh, t[0], t[1]);
        } else {
            g.fill(x, y, x + w, y + h, Ios.NOIR);
            int dw = w;
            int dh = h;
            if (image > cadre) {
                dh = Math.round(w / image);
            } else {
                dw = Math.round(h * image);
            }
            g.blit(rl, x + (w - dw) / 2, y + (h - dh) / 2, dw, dh, 0, 0, t[0], t[1], t[0], t[1]);
        }
    }

    interface Rafraichissable {
        void rafraichir();
    }

    // ---------------------------------------------------------------- photo directe (outil de captures)

    private static int photoDans = -1;
    private static boolean guiAvant;

    /** Prend tout de suite une photo de l'écran entier (utilisé par l'outil de captures). */
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
        } catch (Exception e) {
            Cabane.LOGGER.warn("Photo ratée : {}", e.toString());
        } finally {
            mc.options.hideGui = guiAvant;
        }
    }

    static void envoyer(byte[] png) {
        int taille = Piaf.tailleMorceau();
        int total = (png.length + taille - 1) / taille;
        envoi++;
        for (int i = 0; i < total; i++) {
            int debut = i * taille;
            byte[] morceau = java.util.Arrays.copyOfRange(png, debut, Math.min(png.length, debut + taille));
            PacketDistributor.sendToServer(new Piaf.Morceau(envoi, i, total, morceau));
        }
    }

    // ---------------------------------------------------------------- le cadre commun

    /** Un iPhone en portrait : barre d'état, grand titre, contenu, barre d'accueil en bas. */
    public abstract static class Ecran extends Screen implements Rafraichissable {
        int W;
        int H;
        int left;
        int top;
        /** Les champs de saisie, pour leur dessiner un fond arrondi. */
        private final List<EditBox> champs = new ArrayList<>();
        /**
         * Vrai seulement à l'ouverture : les demandes au serveur se font une fois, pas à chaque mise à jour
         * (sinon chaque réponse redemande, et ça tourne en boucle).
         */
        boolean premiere;
        private boolean ouvert;

        Ecran(String titre) {
            super(Component.literal(titre));
        }

        int x0() {
            return left + 12;
        }

        int y0() {
            return top + (title.getString().isEmpty() ? 22 : 48);
        }

        int largeur() {
            return W - 24;
        }

        int bas() {
            return top + H - 18;
        }

        int hauteur() {
            return bas() - y0();
        }

        @Override
        protected void init() {
            premiere = !ouvert;
            ouvert = true;
            champs.clear();
            H = Math.min(340, height - 10);
            W = H / 2 + 6;
            left = (width - W) / 2;
            top = (height - H) / 2;
            // la barre d'accueil, en bas : retour à l'écran d'accueil
            addRenderableWidget(Ios.Bouton.texte(left + W / 2 - 34, top + H - 14, 68, 12, "", 0, () -> minecraft.setScreen(new Accueil())));
        }

        /** Un champ de saisie sans bordure, sur fond arrondi. */
        EditBox champ(int x, int y, int w, String indice) {
            EditBox e = addRenderableWidget(new EditBox(font, x + 7, y + 6, w - 14, 12, Component.literal(indice)));
            e.setBordered(false);
            e.setHint(Component.literal(indice).withColor(Ios.GRIS));
            e.setTextColor(Ios.BLANC);
            champs.add(e);
            return e;
        }

        /** Un lien bleu en haut à droite, à la hauteur du grand titre (« + », « Modifier »…). */
        void boutonHaut(String texte, Runnable action) {
            int w = font.width(texte) + 8;
            addRenderableWidget(Ios.Bouton.texte(left + W - 10 - w, top + 24, w, 12, texte, Ios.BLEU, action));
        }

        /** Garde ce qui est tapé quand l'écran se met à jour (un paquet du serveur arrive). */
        @Override
        public void rafraichir() {
            List<String> valeurs = champs.stream().map(EditBox::getValue).toList();
            int focus = champs.indexOf(getFocused());
            rebuildWidgets();
            if (valeurs.size() == champs.size()) {
                for (int i = 0; i < valeurs.size(); i++) {
                    champs.get(i).setValue(valeurs.get(i));
                }
                if (focus >= 0) {
                    setFocused(champs.get(focus));
                }
            }
        }

        int fond() {
            return Ios.NOIR;
        }

        void dessinerFond(GuiGraphics g) {
            Ios.rond(g, left, top, W, H, 24, fond());
        }

        @Override
        public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
            super.renderBackground(g, mouseX, mouseY, partialTick);
            Ios.rond(g, left - 6, top - 6, W + 12, H + 12, 30, 0xFF4A4A4E);
            Ios.rond(g, left - 4, top - 4, W + 8, H + 8, 28, 0xFF0A0A0A);
            dessinerFond(g);
            Ios.barreEtat(g, font, left, top + 2, W, Ios.BLANC);
            if (!title.getString().isEmpty()) {
                Ios.texte(g, font, title.getString(), x0(), top + 24, 1.6F, Ios.BLANC, true);
            }
            for (EditBox e : champs) {
                if (e.visible) {
                    Ios.rond(g, e.getX() - 7, e.getY() - 6, e.getWidth() + 14, 24, 8, Ios.GROUPE_CLAIR);
                }
            }
            Ios.rond(g, left + W / 2 - 28, top + H - 8, 56, 3, 2, Ios.BLANC);
        }

        @Override
        public boolean isPauseScreen() {
            return false;
        }
    }

    // ---------------------------------------------------------------- l'écran d'accueil

    public static final class Accueil extends Ecran {
        public Accueil() {
            super("");
        }

        @Override
        protected void init() {
            super.init();
            int taille = Math.min(32, (W - 24 - 3 * 10) / 4);
            int ecart = (W - 4 * taille) / 5;
            String[][] grille = {{"contacts", "Contacts"}, {"recents", "Récents"}, {"galerie", "Photos"}, {"reglages", "Réglages"},
                    {"argent", "Portefeuille"}};
            Runnable[] actions = {() -> minecraft.setScreen(new Contacts()), () -> minecraft.setScreen(new Recents()),
                    () -> minecraft.setScreen(new Galerie(false)), () -> minecraft.setScreen(new Reglages()),
                    () -> minecraft.setScreen(new Portefeuille())};
            int yGrille = top + 96;
            for (int i = 0; i < grille.length; i++) {
                addRenderableWidget(Ios.Bouton.appli(left + ecart + (i % 4) * (taille + ecart), yGrille + (i / 4) * (taille + 18), taille,
                        grille[i][0], grille[i][1], true, actions[i]));
            }
            String[] dock = {"telephone", "messages", "photo", "piaf"};
            String[] noms = {"Téléphone", "Messages", "Appareil photo", "Piaf"};
            Runnable[] actionsDock = {() -> minecraft.setScreen(new PhoneScreen()), () -> minecraft.setScreen(new ClientMessages.Boite()),
                    ClientCamera::ouvrir, () -> minecraft.setScreen(new Fil())};
            int yDock = top + H - 20 - taille - 6;
            for (int i = 0; i < dock.length; i++) {
                addRenderableWidget(Ios.Bouton.appli(left + ecart + i * (taille + ecart), yDock, taille, dock[i], noms[i], false, actionsDock[i]));
            }
            if (premiere) {
                demander(0, "", 0);
            }
            if (premiere) {
                ClientPhone.send(fr.cabane.telephone.PhoneNetwork.Action.ETAT, "");
                ClientMessages.demander(fr.cabane.telephone.Messages.Action.BOITE, "", "", 0);
            }
        }

        @Override
        public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
            super.render(g, mouseX, mouseY, partialTick);
            // la pastille rouge des messages pas encore lus, sur l'icône du dock
            int nonLus = ClientMessages.nonLus();
            if (nonLus > 0) {
                int taille = Math.min(32, (W - 24 - 3 * 10) / 4);
                int ecart = (W - 4 * taille) / 5;
                int x = left + ecart + (taille + ecart) + taille - 2;
                int y = top + H - 20 - taille - 6 + 1;
                Ios.disque(g, x, y, 6, Ios.ROUGE);
                Ios.texteCentre(g, font, nonLus > 9 ? "9+" : String.valueOf(nonLus), x, y - 3, 0.7F, Ios.BLANC, true);
            }
        }

        @Override
        void dessinerFond(GuiGraphics g) {
            // fond d'écran : un dégradé violet → bleu nuit, avec deux halos
            for (int dy = 0; dy < H; dy++) {
                float t = dy / (float) H;
                int c = 0xFF000000 | melange(0x5B2A86, 0x0F2A5C, t);
                int retrait = 0;
                int r = 24;
                if (dy < r || dy >= H - r) {
                    double d = dy < r ? r - dy - 0.5 : dy - (H - r) + 0.5;
                    retrait = (int) Math.round(r - Math.sqrt(Math.max(0, r * r - d * d)));
                }
                g.fill(left + retrait, top + dy, left + W - retrait, top + dy + 1, c);
            }
            Ios.disque(g, left + W - 30, top + 70, 38, 0x30FF7AD9);
            Ios.disque(g, left + 26, top + H - 110, 44, 0x2840C8FF);
            // les deux widgets : l'heure et le jour, le solde
            int ww = (W - 30) / 2;
            Ios.rond(g, left + 10, top + 22, ww, 62, 14, 0x55000000);
            Ios.texte(g, font, Ios.heure(), left + 18, top + 32, 2F, Ios.BLANC, true);
            long jour = minecraft.level == null ? 1 : minecraft.level.getDayTime() / 24000 + 1;
            Ios.texte(g, font, "Jour " + jour, left + 18, top + 56, 0.75F, 0xFFD0D0FF, false);
            Ios.texte(g, font, "Beauvais", left + 18, top + 66, 0.75F, Ios.GRIS, false);
            int x2 = left + 20 + ww;
            Ios.rond(g, x2, top + 22, ww, 62, 14, 0x55000000);
            Ios.texte(g, font, "Solde", x2 + 8, top + 30, 0.75F, Ios.GRIS, false);
            String solde = ClientEconomie.solde < 0 ? "…" : Economie.format(ClientEconomie.solde);
            Ios.texte(g, font, solde, x2 + 8, top + 44, solde.length() > 8 ? 0.85F : 1.2F, Ui.OR, true);
            String numero = ClientPhone.myNumber.isEmpty() ? "SIM à activer" : "☎ " + ClientPhone.myNumber;
            Ios.texte(g, font, numero, x2 + 8, top + 66, 0.75F, ClientPhone.myNumber.isEmpty() ? Ios.ORANGE : 0xFFD0D0FF, false);
            // le dock, en verre dépoli
            int taille = Math.min(32, (W - 24 - 3 * 10) / 4);
            Ios.rond(g, left + 6, top + H - 20 - taille - 12, W - 12, taille + 12, 18, 0x40FFFFFF);
        }

        private static int melange(int a, int b, float t) {
            int r = (int) ((a >> 16 & 0xFF) + ((b >> 16 & 0xFF) - (a >> 16 & 0xFF)) * t);
            int v = (int) ((a >> 8 & 0xFF) + ((b >> 8 & 0xFF) - (a >> 8 & 0xFF)) * t);
            int bl = (int) ((a & 0xFF) + ((b & 0xFF) - (a & 0xFF)) * t);
            return r << 16 | v << 8 | bl;
        }
    }

    // ---------------------------------------------------------------- contacts

    static final int[] AVATARS = {0xFF5E5CE6, 0xFFFF9F0A, 0xFF30D158, 0xFFFF375F, 0xFF64D2FF, 0xFFBF5AF2};

    static void avatar(GuiGraphics g, net.minecraft.client.gui.Font font, String nom, int cx, int cy, int r) {
        Ios.disque(g, cx, cy, r, AVATARS[Math.floorMod(nom.hashCode(), AVATARS.length)]);
        String initiale = nom.isEmpty() ? "?" : nom.substring(0, 1).toUpperCase();
        Ios.texteCentre(g, font, initiale, cx, cy - r * 0.45F, r / 7F, Ios.BLANC, true);
    }

    /** Le carnet : toucher un contact l'appelle ; « + » pour en ajouter. */
    static final class Contacts extends Ecran {
        private boolean ajout;
        private int page;
        private EditBox nom;
        private EditBox numero;

        Contacts() {
            super("Contacts");
        }

        private int parPage() {
            return Math.max(1, (hauteur() - 20) / 30);
        }

        @Override
        protected void init() {
            super.init();
            if (premiere) {
                ClientPhone.send(fr.cabane.telephone.PhoneNetwork.Action.ETAT, "");
            }
            if (ajout) {
                nom = champ(x0(), y0() + 20, largeur(), "nom du contact");
                nom.setMaxLength(24);
                numero = champ(x0(), y0() + 50, largeur(), "numéro (3 ou 4 chiffres)");
                numero.setFilter(s -> s.matches("\\d{0,4}"));
                addRenderableWidget(Ios.Bouton.plein(x0(), y0() + 84, largeur(), 22, "Enregistrer", Ios.BLEU, () -> {
                    if (!nom.getValue().isBlank() && numero.getValue().length() >= 3) {
                        ClientPhone.send(fr.cabane.telephone.PhoneNetwork.Action.AJOUTER_CONTACT, numero.getValue() + "|" + nom.getValue().strip());
                        ajout = false;
                        rebuildWidgets();
                    }
                }));
                addRenderableWidget(Ios.Bouton.texte(x0(), y0() + 112, largeur(), 14, "Annuler", Ios.BLEU, () -> {
                    ajout = false;
                    rebuildWidgets();
                }));
                setInitialFocus(nom);
                return;
            }
            boutonHaut("+", () -> {
                ajout = true;
                rebuildWidgets();
            });
            var liste = ClientPhone.contacts;
            int pages = Math.max(1, (liste.size() + parPage() - 1) / parPage());
            page = Math.min(page, pages - 1);
            for (int k = 0; k < parPage(); k++) {
                int i = page * parPage() + k;
                if (i >= liste.size()) {
                    break;
                }
                var c = liste.get(i);
                int y = y0() + k * 30;
                addRenderableWidget(Ios.Bouton.texte(x0(), y, largeur() - 22, 28, "", 0, () -> ClientPhone.appeler(c.numero())));
                addRenderableWidget(Ios.Bouton.texte(x0() + largeur() - 20, y + 6, 18, 16, "✕", Ios.GRIS,
                        () -> ClientPhone.send(fr.cabane.telephone.PhoneNetwork.Action.SUPPRIMER_CONTACT, c.numero())));
            }
            if (pages > 1) {
                addRenderableWidget(Ios.Bouton.texte(x0(), bas() - 14, 30, 12, "‹", Ios.BLEU, () -> {
                    page = Math.floorMod(page - 1, pages);
                    rebuildWidgets();
                }));
                addRenderableWidget(Ios.Bouton.texte(x0() + largeur() - 30, bas() - 14, 30, 12, "›", Ios.BLEU, () -> {
                    page = Math.floorMod(page + 1, pages);
                    rebuildWidgets();
                }));
            }
        }

        @Override
        public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
            super.render(g, mouseX, mouseY, partialTick);
            if (ajout) {
                Ios.texte(g, font, "Nouveau contact", x0(), y0() + 4, 1F, Ios.GRIS, false);
                return;
            }
            var liste = ClientPhone.contacts;
            if (liste.isEmpty()) {
                Ui.paragraphe(g, font, "Aucun contact. Touche « + » pour ajouter le numéro de tes potes.", x0(), y0() + 20, largeur(), Ios.GRIS);
            }
            for (int k = 0; k < parPage(); k++) {
                int i = page * parPage() + k;
                if (i >= liste.size()) {
                    break;
                }
                var c = liste.get(i);
                int y = y0() + k * 30;
                avatar(g, font, c.nom(), x0() + 12, y + 14, 11);
                g.drawString(font, Ui.couper(font, c.nom(), largeur() - 60), x0() + 30, y + 5, Ios.BLANC, false);
                Ios.texte(g, font, c.numero(), x0() + 30, y + 16, 0.75F, Ios.GRIS, false);
                g.fill(x0() + 30, y + 28, x0() + largeur(), y + 29, Ios.SEPARATEUR);
            }
        }
    }

    /** Les appels récents : toucher une ligne rappelle. */
    static final class Recents extends Ecran {
        Recents() {
            super("Récents");
        }

        private int lignes() {
            return Math.max(1, hauteur() / 28);
        }

        @Override
        protected void init() {
            super.init();
            if (premiere) {
                ClientPhone.send(fr.cabane.telephone.PhoneNetwork.Action.ETAT, "");
            }
            var liste = ClientPhone.recents;
            for (int i = 0; i < Math.min(lignes(), liste.size()); i++) {
                var a = liste.get(i);
                addRenderableWidget(Ios.Bouton.texte(x0(), y0() + i * 28, largeur(), 26, "", 0, () -> ClientPhone.appeler(a.numero())));
            }
        }

        @Override
        public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
            super.render(g, mouseX, mouseY, partialTick);
            var liste = ClientPhone.recents;
            if (liste.isEmpty()) {
                Ios.texteCentre(g, font, "Aucun appel pour l'instant.", left + W / 2F, y0() + 30, 1F, Ios.GRIS, false);
            }
            for (int i = 0; i < Math.min(lignes(), liste.size()); i++) {
                var a = liste.get(i);
                int y = y0() + i * 28;
                String nom = ClientPhone.nom(a.numero());
                g.drawString(font, Ui.couper(font, nom, largeur() - 50), x0(), y + 4, a.type() == 2 ? Ios.ROUGE : Ios.BLANC, false);
                String detail = (a.type() == 0 ? "↗ sortant" : a.type() == 1 ? "↙ entrant" : "✗ manqué");
                Ios.texte(g, font, detail, x0(), y + 15, 0.75F, Ios.GRIS, false);
                String quand = Fil.ilYa(a.temps());
                Ios.texte(g, font, quand, x0() + largeur() - font.width(quand) * 0.75F, y + 6, 0.75F, Ios.GRIS, false);
                g.fill(x0(), y + 26, x0() + largeur(), y + 27, Ios.SEPARATEUR);
            }
        }
    }

    // ---------------------------------------------------------------- Piaf

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
                champPseudo = champ(x0(), y0() + 70, largeur(), "ton pseudo");
                champPseudo.setMaxLength(16);
                champPseudo.setFilter(s -> s.matches("[\\p{L}0-9_.\\- ]*"));
                addRenderableWidget(Ios.Bouton.plein(x0(), y0() + 100, largeur(), 22, "Créer mon compte", Ios.BLEU,
                        () -> demander(1, champPseudo.getValue().strip().replace(' ', '_'), 0)));
                setInitialFocus(champPseudo);
            } else {
                boutonHaut("+ Publier", () -> minecraft.setScreen(new Publier(0)));
            }
            if (premiere) {
                demander(0, "", 0);
            }
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
                        x0(), y0() + 10, largeur(), Ios.BLANC);
                Ios.texte(g, font, "lettres, chiffres, _ . - (3 à 16)", x0(), y0() + 58, 0.7F, Ios.GRIS, false);
                return;
            }
            zonesJaime.clear();
            g.enableScissor(x0(), y0(), x0() + largeur(), bas());
            int y = y0() + 2 - (int) defilement;
            if (posts.isEmpty()) {
                Ios.texteCentre(g, font, "Personne n'a encore publié.", left + W / 2F, y0() + 40, 1F, Ios.GRIS, false);
            }
            for (int i = 0; i < posts.size(); i++) {
                y = dessinerPost(g, i, posts.get(i), y);
            }
            g.disableScissor();
            defilement = Math.min(defilement, Math.max(0, y + defilement - bas()));
        }

        private int dessinerPost(GuiGraphics g, int index, Piaf.Vue p, int y) {
            int x = x0();
            int w = largeur();
            PlayerInfo info = minecraft.getConnection() == null ? null : minecraft.getConnection().getPlayerInfo(p.auteur());
            PlayerFaceRenderer.draw(g, info != null ? info.getSkin() : DefaultPlayerSkin.get(p.auteur()), x, y + 2, 16);
            g.drawString(font, Component.literal(p.pseudo()).withStyle(s -> s.withBold(true)), x + 20, y + 2, Ios.BLANC, false);
            Ios.texte(g, font, "@" + p.pseudo() + " · " + ilYa(p.temps()), x + 20, y + 12, 0.75F, 0xFF8899A6, false);
            int ty = y + 22;
            for (FormattedCharSequence ligne : font.split(Component.literal(p.texte()), w)) {
                g.drawString(font, ligne, x, ty, 0xFFE7E9EA, false);
                ty += 10;
            }
            if (p.photo() != 0) {
                int ph = hauteurPhoto(p.photo(), w, w);
                ResourceLocation rl = photo(p.photo());
                if (rl != null) {
                    dessinerPhoto(g, p.photo(), x, ty + 2, w, ph, false);
                } else {
                    Ios.rond(g, x, ty + 2, w, ph, 8, 0xFF22262E);
                    Ios.texteCentre(g, font, "📷 …", x + w / 2F, ty + ph / 2F, 1F, Ios.GRIS, false);
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

        static String ilYa(long temps) {
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
        private final long photo;
        private MultiLineEditBox champ;
        private String brouillon = "";

        Publier(long photo) {
            super("Publier");
            this.photo = photo;
        }

        @Override
        protected void init() {
            super.init();
            champ = addRenderableWidget(new MultiLineEditBox(font, x0(), y0(), largeur(), 80, Component.literal("Quoi de neuf ?"), Component.empty()));
            champ.setCharacterLimit(Piaf.TEXTE_MAX);
            champ.setValue(brouillon);
            setInitialFocus(champ);
            addRenderableWidget(Ios.Bouton.texte(x0(), y0() + 84, largeur(), 14, photo == 0 ? "📷 Joindre une photo" : "Changer de photo", Ios.BLEU, () -> {
                brouillon = champ.getValue();
                minecraft.setScreen(new Galerie(true));
            }));
            boutonHaut("Publier", () -> {
                demander(2, champ.getValue(), photo);
                minecraft.setScreen(new Fil());
            });
        }

        @Override
        public void rafraichir() {
            brouillon = champ == null ? brouillon : champ.getValue();
            super.rafraichir();
        }

        @Override
        public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
            super.render(g, mouseX, mouseY, partialTick);
            if (photo != 0) {
                dessinerPhoto(g, photo, x0(), y0() + 102, largeur(), hauteurPhoto(photo, largeur(), bas() - y0() - 116), false);
            }
            Ios.texte(g, font, champ.getValue().length() + "/" + Piaf.TEXTE_MAX, x0(), bas() - 10, 0.75F, Ios.GRIS, false);
        }
    }

    // ---------------------------------------------------------------- photos

    /** Les photos prises (et reçues). Toucher une photo l'ouvre ; en mode « choisir », la renvoie à l'écran qui l'a demandée. */
    static final class Galerie extends Ecran {
        private final boolean choisir;
        private final java.util.function.LongConsumer choix;

        Galerie(boolean choisir) {
            super(choisir ? "Choisir" : "Photos");
            this.choisir = choisir;
            this.choix = choisir ? id -> Minecraft.getInstance().setScreen(new Publier(id)) : null;
        }

        Galerie(java.util.function.LongConsumer choix) {
            super("Choisir");
            this.choisir = true;
            this.choix = choix;
        }

        @Override
        protected void init() {
            super.init();
            boutonHaut("📷", ClientCamera::ouvrir);
            if (premiere) {
                demander(0, "", 0);
            }
        }

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            List<Long> liste = galerieInverse();
            for (int i = 0; i < liste.size(); i++) {
                int[] r = vignette(i);
                if (mouseX >= r[0] && mouseX < r[0] + r[2] && mouseY >= r[1] && mouseY < r[1] + r[3]) {
                    if (choix != null) {
                        choix.accept(liste.get(i));
                    } else {
                        minecraft.setScreen(new Visionneuse(liste.get(i)));
                    }
                    return true;
                }
            }
            return super.mouseClicked(mouseX, mouseY, button);
        }

        private List<Long> galerieInverse() {
            List<Long> l = new ArrayList<>(galerie);
            java.util.Collections.reverse(l);
            int max = Math.max(2, 2 * (hauteur() - 14) / (vignette(0)[3] + 3));
            return l.subList(0, Math.min(max, l.size()));
        }

        private int[] vignette(int i) {
            int w = (largeur() - 3) / 2;
            int h = w * 9 / 16;
            return new int[] {x0() + (i % 2) * (w + 3), y0() + (i / 2) * (h + 3), w, h};
        }

        @Override
        public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
            super.render(g, mouseX, mouseY, partialTick);
            List<Long> liste = galerieInverse();
            if (liste.isEmpty()) {
                Ui.paragraphe(g, font, "Aucune photo. Ouvre l'appareil photo (📷), vise, clic gauche : c'est dans la boîte !", x0(), y0() + 20, largeur(), Ios.GRIS);
            }
            for (int i = 0; i < liste.size(); i++) {
                int[] r = vignette(i);
                dessinerPhoto(g, liste.get(i), r[0], r[1], r[2], r[3], true);
                int[] t = taille(liste.get(i));
                if (t[1] > t[0]) {
                    Ios.texte(g, font, "▯", r[0] + 3, r[1] + 2, 0.7F, Ios.BLANC, true);
                }
            }
            if (!liste.isEmpty()) {
                Ios.texteCentre(g, font, choisir ? "Touche la photo à joindre" : "Touche une photo pour l'ouvrir", left + W / 2F, bas() - 10, 0.75F, Ios.GRIS, false);
            }
        }
    }

    /** Une photo en grand : la publier, l'envoyer par message, ou l'imprimer pour l'accrocher au mur. */
    static final class Visionneuse extends Ecran {
        private final long id;

        Visionneuse(long id) {
            super("");
            this.id = id;
        }

        @Override
        protected void init() {
            super.init();
            addRenderableWidget(Ios.Bouton.texte(left + 8, top + 18, 50, 14, "‹ Photos", Ios.BLEU, () -> minecraft.setScreen(new Galerie(false))));
            int y = bas() - 78;
            addRenderableWidget(Ios.Bouton.plein(x0(), y, largeur(), 20, "Imprimer", Ios.BLEU, () -> minecraft.setScreen(new Imprimer(id))));
            addRenderableWidget(Ios.Bouton.plein(x0(), y + 26, largeur(), 20, "Envoyer par message", Ios.VERT,
                    () -> minecraft.setScreen(new ClientMessages.Nouveau(id))));
            addRenderableWidget(Ios.Bouton.plein(x0(), y + 52, largeur(), 20, "Publier sur Piaf", 0xFF3A3A3C, () -> minecraft.setScreen(new Publier(id))));
        }

        @Override
        public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
            super.render(g, mouseX, mouseY, partialTick);
            int y = top + 36;
            dessinerPhoto(g, id, x0(), y, largeur(), bas() - 84 - y, false);
        }
    }

    /** Imprimer une photo : on choisit sa taille sur le mur, bloc par bloc. */
    static final class Imprimer extends Ecran {
        private final long id;
        private int largeurBlocs;
        private int hauteurBlocs;

        Imprimer(long id) {
            super("Imprimer");
            this.id = id;
            int[] t = taille(id);
            largeurBlocs = t[0] >= t[1] ? 2 : 1;
            hauteurBlocs = t[0] >= t[1] ? 1 : 2;
        }

        @Override
        protected void init() {
            super.init();
            int y = bas() - 92;
            reglage(y, "Largeur", d -> largeurBlocs = Math.clamp(largeurBlocs + d, 1, fr.cabane.photo.Impression.MAX));
            reglage(y + 22, "Hauteur", d -> hauteurBlocs = Math.clamp(hauteurBlocs + d, 1, fr.cabane.photo.Impression.MAX));
            addRenderableWidget(Ios.Bouton.texte(x0(), y + 44, largeur(), 12, "Garder les proportions de la photo", Ios.BLEU, () -> {
                int[] t = taille(id);
                hauteurBlocs = Math.clamp(Math.round(largeurBlocs * t[1] / (float) t[0]), 1, fr.cabane.photo.Impression.MAX);
            }));
            addRenderableWidget(Ios.Bouton.plein(x0(), bas() - 24, largeur(), 20, "Imprimer", Ios.BLEU, () -> {
                PacketDistributor.sendToServer(new fr.cabane.photo.Impression.Demande(id, largeurBlocs, hauteurBlocs));
                minecraft.setScreen(null);
            }));
        }

        private void reglage(int y, String nom, java.util.function.IntConsumer changer) {
            addRenderableWidget(Ios.Bouton.plein(x0() + largeur() - 70, y, 22, 18, "−", 0xFF3A3A3C, () -> changer.accept(-1)));
            addRenderableWidget(Ios.Bouton.plein(x0() + largeur() - 22, y, 22, 18, "+", 0xFF3A3A3C, () -> changer.accept(1)));
        }

        @Override
        public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
            super.render(g, mouseX, mouseY, partialTick);
            int y = bas() - 92;
            g.drawString(font, "Largeur", x0(), y + 5, Ios.BLANC, false);
            Ios.texteCentre(g, font, largeurBlocs + " bloc" + (largeurBlocs > 1 ? "s" : ""), x0() + largeur() - 35, y + 5, 0.75F, Ios.BLANC, false);
            g.drawString(font, "Hauteur", x0(), y + 27, Ios.BLANC, false);
            Ios.texteCentre(g, font, hauteurBlocs + " bloc" + (hauteurBlocs > 1 ? "s" : ""), x0() + largeur() - 35, y + 27, 0.75F, Ios.BLANC, false);
            // l'aperçu : le cadre sur le mur, à l'échelle, avec la photo rognée comme elle sera accrochée
            int zoneH = y - y0() - 22;
            int bloc = Math.min(largeur() / largeurBlocs, zoneH / hauteurBlocs);
            int w = bloc * largeurBlocs;
            int h = bloc * hauteurBlocs;
            int x = left + (W - w) / 2;
            int py = y0() + (zoneH - h) / 2;
            g.fill(x - 2, py - 2, x + w + 2, py + h + 2, 0xFF4A3524);
            dessinerPhoto(g, id, x, py, w, h, true);
            for (int i = 1; i < largeurBlocs; i++) {
                g.fill(x + i * bloc, py, x + i * bloc + 1, py + h, 0x30FFFFFF);
            }
            for (int j = 1; j < hauteurBlocs; j++) {
                g.fill(x, py + j * bloc, x + w, py + j * bloc + 1, 0x30FFFFFF);
            }
            int papier = fr.cabane.photo.Impression.papier(largeurBlocs, hauteurBlocs);
            Ios.texteCentre(g, font, largeurBlocs + " × " + hauteurBlocs + " blocs · " + papier + " feuille" + (papier > 1 ? "s" : "") + " de papier",
                    left + W / 2F, y - 16, 0.75F, Ios.GRIS, false);
        }
    }

    // ---------------------------------------------------------------- portefeuille et réglages

    /** Le portefeuille : la carte SakaiDollars, et envoyer de l'argent à quelqu'un. */
    static final class Portefeuille extends Ecran {
        private EditBox joueur;
        private EditBox montant;

        Portefeuille() {
            super("Portefeuille");
        }

        @Override
        protected void init() {
            super.init();
            int y = y0() + 92;
            joueur = champ(x0(), y + 14, largeur(), "nom du joueur");
            montant = champ(x0(), y + 42, largeur(), "montant en $");
            montant.setFilter(s -> s.matches("\\d{0,9}"));
            addRenderableWidget(Ios.Bouton.plein(x0(), y + 72, largeur(), 22, "Envoyer", Ios.BLEU, () -> {
                if (!joueur.getValue().isBlank() && !montant.getValue().isEmpty()) {
                    minecraft.player.connection.sendCommand("payer " + joueur.getValue().trim() + " " + montant.getValue());
                    montant.setValue("");
                }
            }));
        }

        @Override
        public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
            super.render(g, mouseX, mouseY, partialTick);
            int x = x0();
            int y = y0();
            int w = largeur();
            // la carte, avec un dégradé vert sapin et une puce dorée
            for (int dy = 0; dy < 80; dy++) {
                int c = 0xFF000000 | (int) (0x0E + dy * 0.2) << 16 | (int) (0x5C - dy * 0.3) << 8 | (int) (0x3A - dy * 0.2);
                g.fill(x + (dy < 6 ? 6 - dy : dy > 73 ? dy - 73 : 0), y + dy, x + w - (dy < 6 ? 6 - dy : dy > 73 ? dy - 73 : 0), y + dy + 1, c);
            }
            Ios.rond(g, x + 10, y + 12, 18, 13, 3, 0xFFD4AF37);
            Ios.texte(g, font, Economie.MONNAIE, x + 34, y + 15, 0.8F, 0xFFE6FFE9, true);
            String solde = ClientEconomie.solde < 0 ? "…" : Economie.format(ClientEconomie.solde);
            Ios.texte(g, font, solde, x + 10, y + 40, 1.8F, Ui.OR, true);
            Ios.texte(g, font, minecraft.player.getGameProfile().getName().toUpperCase(), x + 10, y + 66, 0.7F, 0xFFBFE8C8, false);
            Ios.texte(g, font, "Envoyer de l'argent", x, y + 92, 0.85F, Ios.GRIS, false);
        }
    }

    /** Les réglages : la SIM (liée au téléphone pour toujours), le pseudo Piaf, le solde. */
    static final class Reglages extends Ecran {
        Reglages() {
            super("Réglages");
        }

        @Override
        protected void init() {
            super.init();
            if (premiere) {
                ClientPhone.send(fr.cabane.telephone.PhoneNetwork.Action.ETAT, "");
            }
            if (ClientPhone.myNumber.isEmpty()) {
                addRenderableWidget(Ios.Bouton.plein(x0(), y0() + 100, largeur(), 22, "Activer la SIM", Ios.VERT,
                        () -> minecraft.setScreen(new PhoneScreen())));
            }
        }

        @Override
        public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
            super.render(g, mouseX, mouseY, partialTick);
            int x = x0();
            int y = y0();
            int w = largeur();
            Ios.rond(g, x, y, w, 84, 10, Ios.GROUPE);
            String[][] lignes = {
                    {"Mon numéro", ClientPhone.myNumber.isEmpty() ? "non activée" : ClientPhone.myNumber},
                    {"Pseudo Piaf", monPseudo.isEmpty() ? "—" : "@" + monPseudo},
                    {"Solde", ClientEconomie.solde < 0 ? "…" : Economie.format(ClientEconomie.solde)}};
            for (int i = 0; i < lignes.length; i++) {
                int ly = y + 8 + i * 26;
                g.drawString(font, lignes[i][0], x + 8, ly + 2, Ios.BLANC, false);
                String v = lignes[i][1];
                g.drawString(font, v, x + w - 8 - font.width(v), ly + 2, Ios.GRIS, false);
                if (i < lignes.length - 1) {
                    g.fill(x + 8, ly + 18, x + w, ly + 19, Ios.SEPARATEUR);
                }
            }
            Ui.paragraphe(g, font, "La SIM est gravée dans ce téléphone pour toujours. Pour un autre numéro, il faut un autre téléphone.",
                    x, y + (ClientPhone.myNumber.isEmpty() ? 128 : 96), w, Ios.GRIS);
        }
    }

    private ClientPiaf() {
    }
}
