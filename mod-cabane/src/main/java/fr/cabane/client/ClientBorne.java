package fr.cabane.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import fr.cabane.bloc.InteractifBlockEntity;
import fr.cabane.bouffe.FastFood;
import fr.cabane.client.ui.Ios;
import fr.cabane.economie.Economie;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * La borne de commande du McBeauvais : son écran de commande (catégories, produits, panier, paiement, ticket) et son
 * dessin en 3D dans le monde (trois blocs de haut, un grand écran tactile qui fait défiler la carte, le lecteur de
 * carte et l'imprimante à tickets, le logo lumineux tout en haut).
 */
public final class ClientBorne {
    private static final int ROUGE = 0xFFDA291C;
    private static final int ROUGE_FONCE = 0xFFA81E14;
    private static final int JAUNE = 0xFFFFC72C;
    private static final int CREME = 0xFFFFF8EC;

    /** La carte reçue à la dernière ouverture (le rendu 3D la fait défiler sur l'écran). */
    private static List<FastFood.Vue> carte = List.of();

    public static void ouvrir(List<FastFood.Vue> c) {
        carte = c;
        Minecraft.getInstance().setScreen(new Ecran(c));
    }

    public static void ticket(int numero, String message) {
        if (Minecraft.getInstance().screen instanceof Ecran e) {
            e.ticket(numero, message);
        } else if (numero == 0) {
            Minecraft.getInstance().gui.setOverlayMessage(Component.literal(message), false);
        }
    }

    // ================================================================ l'écran de commande

    static final class Ecran extends Screen {
        private final List<FastFood.Vue> articles;
        private final Map<String, Integer> panier = new LinkedHashMap<>();
        private String categorie = FastFood.CATEGORIES.get(0);
        private int w;
        private int h;
        private int x0;
        private int y0;
        private int numero = -1;
        private String message = "";
        private long messageJusqua;
        private long ticketDepuis;

        Ecran(List<FastFood.Vue> articles) {
            super(Component.literal("McBeauvais"));
            this.articles = articles;
        }

        @Override
        public boolean isPauseScreen() {
            return false;
        }

        @Override
        protected void init() {
            w = Math.min(width - 10, 400);
            h = Math.min(height - 10, 236);
            x0 = (width - w) / 2;
            y0 = (height - h) / 2;
            rebuild();
        }

        private void rebuild() {
            clearWidgets();
            if (numero > 0) {
                addRenderableWidget(Ios.Bouton.plein(x0 + w / 2 - 50, y0 + h - 34, 100, 20, "Merci !", ROUGE, this::onClose));
                return;
            }
            // les catégories, à gauche
            int cy = y0 + 34;
            for (String c : FastFood.CATEGORIES) {
                boolean choisie = c.equals(categorie);
                addRenderableWidget(Ios.Bouton.plein(x0 + 6, cy, 70, 22, c, choisie ? ROUGE : 0xFF3A3A40, () -> {
                    categorie = c;
                    rebuild();
                }));
                cy += 26;
            }
            // les produits de la catégorie : un « + » sur chaque carte
            List<FastFood.Vue> l = produits();
            for (int i = 0; i < l.size(); i++) {
                FastFood.Vue v = l.get(i);
                int[] c = caseProduit(i);
                addRenderableWidget(Ios.Bouton.plein(c[0] + c[2] - 22, c[1] + c[3] - 20, 18, 16, "+", JAUNE, () -> ajouter(v.id(), 1)));
            }
            // le panier, à droite : - et + par ligne
            int py = y0 + 46;
            for (Map.Entry<String, Integer> e : panier.entrySet()) {
                if (py > y0 + h - 70) {
                    break;
                }
                String id = e.getKey();
                addRenderableWidget(Ios.Bouton.plein(x0 + w - 42, py, 12, 12, "-", 0xFF5A5A62, () -> ajouter(id, -1)));
                addRenderableWidget(Ios.Bouton.plein(x0 + w - 18, py, 12, 12, "+", 0xFF5A5A62, () -> ajouter(id, 1)));
                py += 16;
            }
            if (!panier.isEmpty()) {
                addRenderableWidget(Ios.Bouton.plein(x0 + w - 104, y0 + h - 28, 98, 22, "Payer " + Economie.format(total()), 0xFF2E9E44, this::payer));
                addRenderableWidget(Ios.Bouton.texte(x0 + w - 104, y0 + h - 46, 98, 14, "Vider le panier", 0xFFB4BCC8, () -> {
                    panier.clear();
                    rebuild();
                }));
            }
        }

        private List<FastFood.Vue> produits() {
            return articles.stream().filter(a -> a.categorie().equals(categorie)).toList();
        }

        /** {x, y, largeur, hauteur} de la carte du produit i (deux colonnes). */
        private int[] caseProduit(int i) {
            int zoneX = x0 + 82;
            int zoneW = w - 82 - 116;
            int cw = (zoneW - 6) / 2;
            int ch = 54;
            return new int[] {zoneX + (i % 2) * (cw + 6), y0 + 34 + (i / 2) * (ch + 5), cw, ch};
        }

        private long total() {
            long t = 0;
            for (Map.Entry<String, Integer> e : panier.entrySet()) {
                t += articles.stream().filter(a -> a.id().equals(e.getKey())).mapToLong(FastFood.Vue::prix).findFirst().orElse(0) * e.getValue();
            }
            return t;
        }

        private void ajouter(String id, int n) {
            int q = Mth.clamp(panier.getOrDefault(id, 0) + n, 0, 9);
            if (q == 0) {
                panier.remove(id);
            } else {
                panier.put(id, q);
            }
            minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_HAT.value(), 1.6F, 0.5F));
            rebuild();
        }

        private void payer() {
            List<FastFood.Ligne> l = new ArrayList<>();
            panier.forEach((id, n) -> l.add(new FastFood.Ligne(id, n)));
            PacketDistributor.sendToServer(new FastFood.Commander(l));
        }

        void ticket(int numero, String message) {
            this.message = message;
            this.messageJusqua = System.currentTimeMillis() + 3500;
            if (numero > 0) {
                this.numero = numero;
                this.ticketDepuis = System.currentTimeMillis();
                panier.clear();
                minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_CHIME.value(), 1.4F, 0.7F));
            }
            rebuild();
        }

        @Override
        public void renderBackground(GuiGraphics g, int mx, int my, float delta) {
            g.fill(0, 0, width, height, 0x88000000);
        }

        @Override
        public void render(GuiGraphics g, int mx, int my, float delta) {
            renderBackground(g, mx, my, delta);
            // la borne : un cadre noir, l'écran crème
            Ios.rond(g, x0 - 4, y0 - 4, w + 8, h + 8, 10, 0xFF16161A);
            Ios.rond(g, x0, y0, w, h, 6, CREME);
            // le bandeau rouge et le logo
            Ios.rond(g, x0, y0, w, 26, 6, ROUGE);
            g.fill(x0, y0 + 20, x0 + w, y0 + 26, ROUGE);
            logo(g, x0 + 8, y0 + 3);
            Ios.texte(g, font, "Mc", x0 + 34, y0 + 4, 1F, JAUNE, true);
            Ios.texte(g, font, "Beauvais", x0 + 47, y0 + 4, 1F, 0xFFFFFFFF, true);
            Ios.texte(g, font, "Commandez, payez, régalez-vous", x0 + 34, y0 + 14, 0.7F, 0xFFFFE6B0, false);
            String solde = Economie.format(Math.max(0, ClientEconomie.solde));
            g.drawString(font, solde, x0 + w - 8 - font.width(solde), y0 + 9, JAUNE, true);
            if (numero > 0) {
                ticketFinal(g);
                super.render(g, mx, my, delta);
                return;
            }
            // les produits
            List<FastFood.Vue> l = produits();
            for (int i = 0; i < l.size(); i++) {
                FastFood.Vue v = l.get(i);
                int[] c = caseProduit(i);
                boolean dessus = mx >= c[0] && mx < c[0] + c[2] && my >= c[1] && my < c[1] + c[3];
                Ios.rond(g, c[0], c[1], c[2], c[3], 6, dessus ? 0xFFFFE9B8 : 0xFFFFFFFF);
                g.pose().pushPose();
                g.pose().translate(c[0] + 4, c[1] + 4, 0);
                g.pose().scale(1.6F, 1.6F, 1F);
                g.renderItem(v.icone(), 0, 0);
                g.pose().popPose();
                // le nom en entier : un peu plus petit s'il est long
                float taille = Math.min(0.85F, (c[2] - 38) / (float) Math.max(1, font.width(v.nom()) + 2));
                Ios.texte(g, font, v.nom(), c[0] + 33, c[1] + 6, Math.max(0.6F, taille), 0xFF27251F, true);
                List<net.minecraft.util.FormattedCharSequence> d = font.split(Component.literal(v.detail()), (int) ((c[2] - 36) / 0.7F));
                for (int k = 0; k < Math.min(2, d.size()); k++) {
                    g.pose().pushPose();
                    g.pose().translate(c[0] + 33, c[1] + 17 + k * 7, 0);
                    g.pose().scale(0.7F, 0.7F, 1F);
                    g.drawString(font, d.get(k), 0, 0, 0xFF6E6A60, false);
                    g.pose().popPose();
                }
                g.drawString(font, Economie.format(v.prix()), c[0] + 6, c[1] + c[3] - 14, ROUGE, false);
                int q = panier.getOrDefault(v.id(), 0);
                if (q > 0) {
                    Ios.disque(g, c[0] + c[2] - 8, c[1] + 8, 6, ROUGE);
                    g.drawCenteredString(font, String.valueOf(q), c[0] + c[2] - 8, c[1] + 4, 0xFFFFFFFF);
                }
            }
            // le panier
            int px = x0 + w - 110;
            Ios.rond(g, px, y0 + 32, 106, h - 36, 6, 0xFFF1E8D6);
            Ios.texte(g, font, "Ma commande", px + 6, y0 + 36, 0.85F, 0xFF27251F, true);
            int py = y0 + 46;
            if (panier.isEmpty()) {
                Ios.texte(g, font, "Touchez « + » pour", px + 6, py + 4, 0.7F, 0xFF8A8478, false);
                Ios.texte(g, font, "ajouter un produit.", px + 6, py + 12, 0.7F, 0xFF8A8478, false);
            }
            for (Map.Entry<String, Integer> e : panier.entrySet()) {
                if (py > y0 + h - 70) {
                    Ios.texte(g, font, "…", px + 6, py, 0.8F, 0xFF27251F, false);
                    break;
                }
                FastFood.Vue v = articles.stream().filter(a -> a.id().equals(e.getKey())).findFirst().orElse(null);
                if (v != null) {
                    Ios.texte(g, font, e.getValue() + "×", px + 4, py + 3, 0.7F, ROUGE, true);
                    Ios.texte(g, font, font.plainSubstrByWidth(v.nom(), 66), px + 15, py + 3, 0.7F, 0xFF27251F, false);
                    g.drawString(font, String.valueOf(e.getValue()), px + 76, py + 2, 0xFF27251F, false);
                }
                py += 16;
            }
            if (!panier.isEmpty()) {
                Ios.texte(g, font, "Total : " + Economie.format(total()), px + 6, y0 + h - 58, 0.85F, 0xFF27251F, true);
            }
            if (System.currentTimeMillis() < messageJusqua) {
                int mw = font.width(message) + 16;
                Ios.rond(g, x0 + (w - mw) / 2, y0 + h - 22, mw, 16, 6, 0xEE27251F);
                g.drawCenteredString(font, message, x0 + w / 2, y0 + h - 18, 0xFFFFFFFF);
            }
            super.render(g, mx, my, delta);
        }

        /** Le ticket : le numéro de commande en grand, et la cuisine qui s'active. */
        private void ticketFinal(GuiGraphics g) {
            int tw = 150;
            int tx = x0 + (w - tw) / 2;
            int ty = y0 + 36;
            float sortie = Mth.clamp((System.currentTimeMillis() - ticketDepuis) / 700F, 0F, 1F);
            int th = (int) (h - 80 * 1F) - 10;
            int visible = (int) (th * sortie);
            g.enableScissor(tx - 2, ty, tx + tw + 2, ty + visible);
            Ios.rond(g, tx, ty, tw, th, 3, 0xFFFFFFFF);
            for (int i = 0; i < tw; i += 6) {
                g.fill(tx + i, ty + th - 2, tx + i + 3, ty + th, CREME);
            }
            g.drawCenteredString(font, "McBeauvais", tx + tw / 2, ty + 8, ROUGE);
            Ios.texteCentre(g, font, "Votre numéro", tx + tw / 2F, ty + 24, 0.8F, 0xFF6E6A60, false);
            Ios.texteCentre(g, font, String.valueOf(numero), tx + tw / 2F, ty + 36, 3F, 0xFF27251F, true);
            Ios.texteCentre(g, font, message, tx + tw / 2F, ty + 72, 0.75F, 0xFF2E9E44, false);
            Ios.texteCentre(g, font, "Votre commande arrive", tx + tw / 2F, ty + 88, 0.75F, 0xFF6E6A60, false);
            Ios.texteCentre(g, font, "dans un instant !", tx + tw / 2F, ty + 97, 0.75F, 0xFF6E6A60, false);
            g.disableScissor();
        }

        /** Le logo : deux arches jaunes sur fond rouge. */
        private static void logo(GuiGraphics g, int x, int y) {
            for (int k = 0; k < 2; k++) {
                int ax = x + k * 10;
                g.fill(ax, y + 4, ax + 3, y + 18, JAUNE);
                g.fill(ax + 9, y + 4, ax + 12, y + 18, JAUNE);
                g.fill(ax + 1, y + 1, ax + 11, y + 4, JAUNE);
            }
        }
    }

    // ================================================================ la borne dans le monde (3 blocs de haut)

    /** Des faces de couleur unie (la texture du béton blanc, teintée), comme le pinceau des tables du casino. */
    static final class Pinceau {
        final VertexConsumer vc;
        final PoseStack ps;
        final float u;
        final float v;
        final int lumiere;

        Pinceau(MultiBufferSource buf, PoseStack ps, int lumiere) {
            this.vc = buf.getBuffer(RenderType.entityCutoutNoCull(InventoryMenu.BLOCK_ATLAS));
            this.ps = ps;
            this.lumiere = lumiere;
            TextureAtlasSprite s = Minecraft.getInstance().getTextureAtlas(InventoryMenu.BLOCK_ATLAS)
                    .apply(ResourceLocation.withDefaultNamespace("block/white_concrete"));
            this.u = (s.getU0() + s.getU1()) / 2;
            this.v = (s.getV0() + s.getV1()) / 2;
        }

        void quad(int c, float nx, float ny, float nz, boolean clair, float... p) {
            int l = clair ? LightTexture.FULL_BRIGHT : lumiere;
            PoseStack.Pose pose = ps.last();
            for (int i = 0; i < 4; i++) {
                vc.addVertex(pose, p[i * 3], p[i * 3 + 1], p[i * 3 + 2])
                        .setColor((c >> 16) & 0xFF, (c >> 8) & 0xFF, c & 0xFF, (c >>> 24) & 0xFF)
                        .setUv(u, v).setOverlay(OverlayTexture.NO_OVERLAY).setLight(l).setNormal(pose, nx, ny, nz);
            }
        }

        void pave(int c, float x0, float y0, float z0, float x1, float y1, float z1) {
            pave(c, x0, y0, z0, x1, y1, z1, false);
        }

        void pave(int c, float x0, float y0, float z0, float x1, float y1, float z1, boolean clair) {
            quad(c, 0, 1, 0, clair, x0, y1, z0, x0, y1, z1, x1, y1, z1, x1, y1, z0);
            quad(c, 0, -1, 0, clair, x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1);
            quad(c, 0, 0, -1, clair, x0, y0, z0, x0, y1, z0, x1, y1, z0, x1, y0, z0);
            quad(c, 0, 0, 1, clair, x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1);
            quad(c, -1, 0, 0, clair, x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0);
            quad(c, 1, 0, 0, clair, x1, y0, z0, x1, y1, z0, x1, y1, z1, x1, y0, z1);
        }

        /** Un rectangle à plat sur la face avant (plan z), éclairé ou non. */
        void face(int c, float x0, float y0, float x1, float y1, float z, boolean clair) {
            quad(c, 0, 0, 1, clair, x0, y0, z, x1, y0, z, x1, y1, z, x0, y1, z);
        }
    }

    /**
     * La borne, dans le repère du joueur qui l'a posée (x vers sa droite, z vers lui, y vers le haut ; l'origine au
     * centre du bloc du bas, au sol). L'écran fait défiler la carte, produit par produit.
     */
    static void dessiner(InteractifBlockEntity be, float partialTick, PoseStack ps, MultiBufferSource buf, int lumiere) {
        Pinceau p = new Pinceau(buf, ps, lumiere);
        float temps = be.getLevel().getGameTime() + partialTick;
        int gris = 0xFF2B2D33;
        int blanc = 0xFFECECEF;
        // le socle et le corps : une colonne blanche aux flancs gris, un liseré jaune
        p.pave(0xFF1A1B1F, -0.42F, 0F, -0.3F, 0.42F, 0.06F, 0.26F);
        p.pave(blanc, -0.36F, 0.06F, -0.2F, 0.36F, 2.62F, 0.12F);
        p.pave(gris, -0.38F, 0.06F, -0.22F, -0.34F, 2.66F, 0.14F);
        p.pave(gris, 0.34F, 0.06F, -0.22F, 0.38F, 2.66F, 0.14F);
        p.pave(JAUNE, -0.385F, 0.2F, -0.05F, -0.375F, 2.5F, 0.05F, true);
        p.pave(JAUNE, 0.375F, 0.2F, -0.05F, 0.385F, 2.5F, 0.05F, true);
        // l'imprimante à tickets et le lecteur de carte, sous l'écran
        p.pave(gris, -0.28F, 0.66F, 0.12F, 0.28F, 0.84F, 0.2F);
        p.face(0xFF0B0B0D, -0.22F, 0.7F, -0.04F, 0.72F, 0.201F, false);
        p.face(0xFFF4F0E2, -0.2F, 0.705F, -0.06F, 0.715F, 0.202F, true);
        p.pave(0xFF101114, 0.04F, 0.7F, 0.17F, 0.24F, 0.82F, 0.26F);
        boolean vert = ((int) (temps / 10)) % 3 != 0;
        p.face(vert ? 0xFF35D06A : 0xFF1F5A33, 0.16F, 0.79F, 0.21F, 0.805F, 0.261F, vert);
        p.face(0xFF2C2E35, 0.07F, 0.72F, 0.21F, 0.77F, 0.261F, false);
        // l'écran : un bord noir, puis l'affichage, lumineux
        float bas = 0.92F;
        float haut = 2.5F;
        p.pave(0xFF0B0B0D, -0.33F, bas, 0.12F, 0.33F, haut, 0.135F);
        float z = 0.136F;
        p.face(CREME, -0.3F, bas + 0.03F, 0.3F, haut - 0.03F, z, true);
        // le bandeau rouge en haut de l'écran, le logo et le nom
        p.face(ROUGE, -0.3F, haut - 0.26F, 0.3F, haut - 0.03F, z + 0.001F, true);
        p.face(ROUGE_FONCE, -0.3F, haut - 0.28F, 0.3F, haut - 0.26F, z + 0.001F, true);
        arches(p, -0.255F, haut - 0.22F, 0.11F, z + 0.002F);
        ps.pushPose();
        ps.translate(0F, 0F, z + 0.003F);
        Rendu3D.texte(ps, buf, gras("McBeauvais"), 0.06F, haut - 0.075F, 0.0046F, 0xFFFFFFFF, 0, LightTexture.FULL_BRIGHT);
        Rendu3D.texte(ps, buf, Component.literal("Commandez ici !"), 0.06F, haut - 0.17F, 0.0026F, 0xFFFFE6B0, 0, LightTexture.FULL_BRIGHT);
        ps.popPose();
        // le produit du moment : il tourne, avec son nom et son prix
        List<FastFood.Vue> l = carte.isEmpty() ? null : carte;
        int n = l == null ? FastFood.TOUS.size() : l.size();
        int i = (int) (temps / 60) % n;
        ItemStack stack = l == null ? new ItemStack(FastFood.TOUS.get(i).get()) : l.get(i).icone();
        String nom = l == null ? stack.getHoverName().getString() : l.get(i).nom();
        ps.pushPose();
        ps.translate(0F, 1.72F, z + 0.06F);
        ps.mulPose(com.mojang.math.Axis.YP.rotationDegrees((temps * 3F) % 360F));
        float rebond = 1F + 0.05F * Mth.sin(temps * 0.15F);
        ps.scale(0.42F * rebond, 0.42F * rebond, 0.42F * rebond);
        Rendu3D.objet(ps, buf, stack, be.getLevel(), LightTexture.FULL_BRIGHT);
        ps.popPose();
        ps.pushPose();
        ps.translate(0F, 0F, z + 0.003F);
        Rendu3D.texte(ps, buf, gras(nom), 0F, 1.42F, 0.0034F, 0xFF27251F, 0, LightTexture.FULL_BRIGHT);
        if (l != null) {
            Rendu3D.texte(ps, buf, gras(Economie.format(l.get(i).prix())), 0F, 1.34F, 0.0042F, ROUGE, 0, LightTexture.FULL_BRIGHT);
        }
        ps.popPose();
        // les points du carrousel et le gros bouton « Touchez pour commencer »
        for (int k = 0; k < Math.min(n, 16); k++) {
            float px = -0.0275F * Math.min(n, 16) / 2 + k * 0.0275F;
            p.face(k == i % 16 ? ROUGE : 0xFFD8D0C0, px, 1.25F, px + 0.015F, 1.265F, z + 0.002F, true);
        }
        float pulse = 0.5F + 0.5F * Mth.sin(temps * 0.2F);
        int jaune = Ios.eclaircir(JAUNE, (int) (pulse * 30));
        p.face(jaune, -0.24F, 1.02F, 0.24F, 1.18F, z + 0.002F, true);
        ps.pushPose();
        ps.translate(0F, 0F, z + 0.004F);
        Rendu3D.texte(ps, buf, gras("TOUCHEZ POUR COMMENCER"), 0F, 1.11F, 0.0024F, 0xFF27251F, 0, LightTexture.FULL_BRIGHT);
        ps.popPose();
        // tout en haut : le panneau lumineux et ses arches
        p.pave(ROUGE_FONCE, -0.4F, 2.62F, -0.24F, 0.4F, 2.66F, 0.16F);
        p.pave(ROUGE, -0.38F, 2.66F, -0.2F, 0.38F, 2.96F, 0.12F, true);
        arches(p, -0.13F, 2.7F, 0.13F, 0.121F);
        ps.pushPose();
        ps.translate(0, 0, -0.2F);
        ps.mulPose(com.mojang.math.Axis.YP.rotationDegrees(180F));
        arches(new Pinceau(buf, ps, lumiere), -0.13F, 2.7F, 0.13F, 0.001F);
        ps.popPose();
    }

    /** Les deux arches jaunes du logo, sur la face avant (plan z), à partir de (x, y), de hauteur hauteur. */
    private static void arches(Pinceau p, float x, float y, float hauteur, float z) {
        float e = hauteur * 0.22F;
        float l = hauteur * 0.62F;
        for (int k = 0; k < 2; k++) {
            float ax = x + k * (l - e);
            p.face(JAUNE, ax, y, ax + e, y + hauteur * 0.86F, z, true);
            p.face(JAUNE, ax + l - e, y, ax + l, y + hauteur * 0.86F, z, true);
            p.face(JAUNE, ax + e * 0.5F, y + hauteur * 0.78F, ax + l - e * 0.5F, y + hauteur, z, true);
        }
    }

    private static Component gras(String s) {
        return Component.literal(s).withStyle(st -> st.withBold(true));
    }

    private ClientBorne() {
    }
}
