package fr.cabane.client;

import fr.cabane.Cabane;
import fr.cabane.CabaneComponents;
import fr.cabane.CabaneItems;
import fr.cabane.client.ui.Ui;
import fr.cabane.tcg.Cartes;
import fr.cabane.tcg.Classeur;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.item.ItemProperties;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/** Côté client du jeu de cartes : dessin d'une carte, ouverture de booster, classeur. */
@EventBusSubscriber(modid = Cabane.MODID, value = Dist.CLIENT)
public final class ClientCartes {

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> ItemProperties.register(CabaneItems.CARTE.get(), Cabane.id("rarete"), (stack, level, entity, seed) -> {
            Cartes.Def def = Cartes.def(stack.getOrDefault(CabaneComponents.CARTE.get(), ""));
            return def == null ? 0F : def.rarete() / 3F;
        }));
    }

    public static void catalogueRecu() {
        if (Minecraft.getInstance().screen instanceof ClasseurScreen ecran) {
            ecran.rafraichir();
        }
    }

    public static void booster(List<String> cartes) {
        Minecraft.getInstance().setScreen(new BoosterScreen(cartes));
    }

    public static void ouvrirClasseur() {
        Minecraft.getInstance().setScreen(new ClasseurScreen());
    }

    /** Dessine une carte (w x h). Inconnue = dos sombre avec son numéro. */
    static void dessinerCarte(GuiGraphics g, Font font, Cartes.Def def, int x, int y, int w, int h, boolean connue, int nombre) {
        int cadre = def == null ? 0xFF3A3F4A : Cartes.COULEURS_RGB[def.rarete()];
        g.fill(x - 1, y - 1, x + w + 1, y + h + 1, 0xFF0B0C10);
        g.fill(x, y, x + w, y + h, connue ? cadre : 0xFF2A2E36);
        g.fill(x + 3, y + 3, x + w - 3, y + h - 3, connue ? 0xFF1B1E25 : 0xFF20232A);
        if (!connue || def == null) {
            g.drawCenteredString(font, "?", x + w / 2, y + h / 2 - 8, 0xFF555B66);
            if (def != null) {
                g.drawCenteredString(font, "n° " + def.numero(), x + w / 2, y + h - 14, 0xFF555B66);
            }
            return;
        }
        // illustration provisoire : un médaillon de la couleur de la rareté avec l'initiale
        int cx = x + w / 2;
        int cy = y + h / 2 - 6;
        int r = Math.min(w, h) / 4;
        for (int dy = -r; dy <= r; dy++) {
            int demi = (int) Math.sqrt(r * r - dy * dy);
            g.fill(cx - demi, cy + dy, cx + demi, cy + dy + 1, (cadre & 0x00FFFFFF) | 0x66000000);
        }
        g.pose().pushPose();
        g.pose().translate(cx, cy - 6, 0);
        g.pose().scale(1.6F, 1.6F, 1F);
        g.drawCenteredString(font, def.nom().substring(0, 1).toUpperCase(), 0, 0, 0xFFFFFFFF);
        g.pose().popPose();
        List<FormattedCharSequence> lignes = font.split(Component.literal(def.nom()), w - 8);
        int ly = y + 5;
        for (FormattedCharSequence l : lignes.subList(0, Math.min(2, lignes.size()))) {
            g.drawCenteredString(font, l, x + w / 2, ly, 0xFFFFFFFF);
            ly += 9;
        }
        g.drawCenteredString(font, Cartes.RARETES[def.rarete()], x + w / 2, y + h - 14, cadre);
        if (nombre > 1) {
            g.drawString(font, "×" + nombre, x + w - 4 - font.width("×" + nombre), y + h - 24, Ui.OR, true);
        }
    }

    /**
     * L'ouverture d'un booster, en plusieurs temps : le paquet scellé et brillant ; on clique, il tremble
     * et se déchire ; les cartes sont distribuées face cachée ; on les retourne une à une. Plus une carte
     * est rare, plus elle se fait attendre (aura, tremblement), et plus la révélation est belle : rayons,
     * étincelles, reflet holographique, éclair pour les légendaires. La meilleure carte est toujours la dernière.
     */
    public static final class BoosterScreen extends Screen {
        private enum Phase { SCELLE, DECHIRURE, DISTRIBUTION, REVELATION }

        private record Etincelle(float x, float y, float vx, float vy, int couleur, long naissance, float vie) {
        }

        private final List<String> cartes;
        private final long[] retournee = new long[5];
        private final long[] charge = new long[5];
        private final List<Etincelle> etincelles = new java.util.ArrayList<>();
        private final java.util.Random alea = new java.util.Random();
        private Phase phase = Phase.SCELLE;
        private long debutPhase = System.currentTimeMillis();
        private long eclair;
        private int prochaine;
        private Button toutBouton;

        public BoosterScreen(List<String> cartes) {
            super(Component.literal("Booster"));
            this.cartes = cartes;
        }

        @Override
        protected void init() {
            toutBouton = addRenderableWidget(Button.builder(Component.literal("Tout retourner"), b -> toutRetourner())
                    .bounds(width / 2 - 60, height - 28, 120, 18).build());
            toutBouton.visible = phase == Phase.REVELATION;
        }

        private static long maintenant() {
            return System.currentTimeMillis();
        }

        private int rarete(int i) {
            Cartes.Def def = Cartes.def(cartes.get(i));
            return def == null ? 0 : def.rarete();
        }

        /** Le temps d'attente avant de retourner une carte de cette rareté. */
        private static long attente(int rarete) {
            return switch (rarete) {
                case 1 -> 450;
                case 2 -> 900;
                case 3 -> 1500;
                default -> 0;
            };
        }

        public void toutRetourner() {
            if (phase == Phase.SCELLE || phase == Phase.DECHIRURE) {
                passer(Phase.DISTRIBUTION);
                debutPhase -= 2000;
                passer(Phase.REVELATION);
            }
            long t = maintenant();
            for (int i = prochaine; i < cartes.size(); i++) {
                charge[i] = t - 5000;
                retournee[i] = t - 5000;
            }
            prochaine = cartes.size();
        }

        private void passer(Phase p) {
            phase = p;
            debutPhase = maintenant();
            if (toutBouton != null) {
                toutBouton.visible = p == Phase.REVELATION;
            }
        }

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            if (super.mouseClicked(mouseX, mouseY, button)) {
                return true;
            }
            switch (phase) {
                case SCELLE -> {
                    passer(Phase.DECHIRURE);
                    son(SoundEvents.ARMOR_EQUIP_LEATHER.value(), 0.8F);
                }
                case REVELATION -> {
                    if (prochaine < cartes.size() && charge[prochaine] == 0 && (prochaine == 0 || retournee[prochaine - 1] > 0)) {
                        charge[prochaine] = maintenant();
                        if (rarete(prochaine) >= 2) {
                            son(SoundEvents.BEACON_POWER_SELECT, rarete(prochaine) == 3 ? 0.6F : 0.9F);
                        }
                    }
                }
                default -> {
                }
            }
            return true;
        }

        private static void son(net.minecraft.sounds.SoundEvent s, float hauteur) {
            Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(s, hauteur));
        }

        @Override
        public void tick() {
            long t = maintenant();
            if (phase == Phase.DECHIRURE && t - debutPhase > 900) {
                passer(Phase.DISTRIBUTION);
                son(SoundEvents.BOOK_PAGE_TURN, 1.2F);
            } else if (phase == Phase.DISTRIBUTION && t - debutPhase > 1100) {
                passer(Phase.REVELATION);
            }
            // une carte « chargée » se retourne après son temps d'attente
            for (int i = 0; i < cartes.size(); i++) {
                if (charge[i] > 0 && retournee[i] == 0 && t - charge[i] >= attente(rarete(i))) {
                    retournee[i] = t;
                    prochaine = Math.max(prochaine, i + 1);
                    reveler(i);
                }
            }
        }

        private int[] place(int i) {
            int w = carteW();
            int h = w * 10 / 7;
            int total = cartes.size() * w + (cartes.size() - 1) * 10;
            return new int[] {width / 2 - total / 2 + i * (w + 10), height / 2 - h / 2 + 4, w, h};
        }

        private int carteW() {
            return Math.min(70, (width - 60) / cartes.size() - 10);
        }

        /** La carte se retourne : son, étincelles, rayons, éclair, selon la rareté. */
        private void reveler(int i) {
            int r = rarete(i);
            int[] p = place(i);
            float cx = p[0] + p[2] / 2F;
            float cy = p[1] + p[3] / 2F;
            int nombre = new int[] {8, 30, 70, 140}[r];
            int couleur = Cartes.COULEURS_RGB[r];
            for (int k = 0; k < nombre; k++) {
                double a = alea.nextDouble() * Math.PI * 2;
                float v = (r + 1) * (0.6F + alea.nextFloat() * 1.6F);
                int c = r == 3 && alea.nextBoolean() ? 0xFFFFFFFF : couleur;
                etincelles.add(new Etincelle(cx, cy, (float) Math.cos(a) * v, (float) Math.sin(a) * v - 0.5F, c, maintenant(), 700 + alea.nextInt(700)));
            }
            switch (r) {
                case 0 -> son(SoundEvents.BOOK_PAGE_TURN, 1F);
                case 1 -> son(SoundEvents.AMETHYST_BLOCK_CHIME, 1.2F);
                case 2 -> {
                    son(SoundEvents.PLAYER_LEVELUP, 1.3F);
                    son(SoundEvents.AMETHYST_CLUSTER_BREAK, 1F);
                }
                default -> {
                    son(SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, 1F);
                    son(SoundEvents.TOTEM_USE, 1.4F);
                    eclair = maintenant();
                }
            }
        }

        @Override
        public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
            super.render(g, mouseX, mouseY, partialTick);
            long t = maintenant();
            float dt = (t - debutPhase) / 1000F;
            switch (phase) {
                case SCELLE -> {
                    dessinerPaquet(g, t, 0F);
                    texteCentre(g, "Clique pour ouvrir le booster", height / 2 + 82, Ui.GRIS);
                }
                case DECHIRURE -> dessinerPaquet(g, t, Math.min(1F, dt / 0.9F));
                case DISTRIBUTION -> {
                    for (int i = 0; i < cartes.size(); i++) {
                        float k = Mth.clamp((dt - i * 0.12F) / 0.45F, 0F, 1F);
                        k = 1 - (1 - k) * (1 - k) * (1 - k);
                        int[] p = place(i);
                        int x = (int) Mth.lerp(k, width / 2F - p[2] / 2F, p[0]);
                        int y = (int) Mth.lerp(k, height / 2F - p[3] / 2F, p[1]);
                        dos(g, x, y, p[2], p[3], 0, 0F);
                    }
                }
                case REVELATION -> {
                    for (int i = 0; i < cartes.size(); i++) {
                        dessinerCarteBooster(g, i, t);
                    }
                    if (prochaine < cartes.size()) {
                        boolean attend = charge[prochaine] > 0;
                        texteCentre(g, attend ? "…" : "Clique pour retourner la carte suivante", place(0)[1] + place(0)[3] + 14, Ui.GRIS);
                    } else if (t - retournee[cartes.size() - 1] > 600) {
                        texteCentre(g, "Les cartes sont dans ton inventaire. Échap pour fermer.", place(0)[1] + place(0)[3] + 14, Ui.GRIS);
                    }
                }
            }
            dessinerEtincelles(g, t);
            if (eclair > 0 && t - eclair < 500) {
                int a = (int) (200 * (1 - (t - eclair) / 500F));
                g.fill(0, 0, width, height, a << 24 | 0xFFF3C4);
            }
        }

        private void texteCentre(GuiGraphics g, String s, int y, int couleur) {
            g.drawCenteredString(font, s, width / 2, y, couleur);
        }

        /** Le paquet scellé : papier métallisé, reflet qui passe ; à l'ouverture il tremble et se déchire. */
        private void dessinerPaquet(GuiGraphics g, long t, float ouverture) {
            int w = 92;
            int h = 132;
            int x = width / 2 - w / 2;
            int y = height / 2 - h / 2 - 6;
            float tremble = ouverture > 0 && ouverture < 0.55F ? (float) Math.sin(t / 18.0) * 3F * ouverture * 2 : 0F;
            float flotte = (float) Math.sin(t / 400.0) * 2F;
            g.pose().pushPose();
            g.pose().translate(tremble, flotte, 0);
            // halo derrière le paquet
            for (int k = 6; k > 0; k--) {
                int a = (int) (18 + 10 * Math.sin(t / 300.0)) * k / 6;
                g.fill(x - k * 3, y - k * 3, x + w + k * 3, y + h + k * 3, a << 24 | 0xFFC23D);
            }
            // le corps du paquet : un dégradé rouge et or
            for (int dy = 0; dy < h; dy++) {
                float k = dy / (float) h;
                int c = 0xFF000000 | (int) Mth.lerp(k, 0xD8, 0x7A) << 16 | (int) Mth.lerp(k, 0x3A, 0x14) << 8 | (int) Mth.lerp(k, 0x2A, 0x1A);
                g.fill(x, y + dy, x + w, y + dy + 1, c);
            }
            g.fill(x, y, x + w, y + 3, 0xFFFFD75E);
            g.fill(x, y + h - 3, x + w, y + h, 0xFFFFD75E);
            // le reflet qui balaie le papier
            float balayage = (t % 2200) / 2200F * (w + h) - h;
            for (int dy = 0; dy < h; dy++) {
                int bx = (int) (balayage + dy * 0.6F);
                int x1 = Math.max(x, x + bx);
                int x2 = Math.min(x + w, x + bx + 14);
                if (x2 > x1) {
                    g.fill(x1, y + dy, x2, y + dy + 1, 0x40FFFFFF);
                }
            }
            g.pose().pushPose();
            g.pose().translate(width / 2F, y + 34, 0);
            g.pose().scale(1.6F, 1.6F, 1F);
            g.drawCenteredString(font, "BEAUVAIS", 0, 0, 0xFFFFE8A3);
            g.pose().popPose();
            g.drawCenteredString(font, "Série 1", width / 2, y + 56, 0xFFFFD0C0);
            g.drawCenteredString(font, "5 cartes", width / 2, y + h - 22, 0xFFFFD0C0);
            // à l'ouverture, la languette du haut s'envole en tournant
            if (ouverture > 0.45F) {
                float k = (ouverture - 0.45F) / 0.55F;
                g.pose().pushPose();
                g.pose().translate(x + w / 2F + k * 40, y - k * 60, 0);
                g.pose().mulPose(com.mojang.math.Axis.ZP.rotationDegrees(k * 40));
                g.fill(-w / 2, 0, w / 2, 14, 0xFFFFD75E);
                g.pose().popPose();
                int lumiere = (int) (230 * k);
                g.fill(x + 2, y + 10, x + w - 2, y + 14, lumiere << 24 | 0xFFFFFF);
            } else {
                g.fill(x, y + 10, x + w, y + 14, 0xFFFFD75E);
                for (int dx = 0; dx < w; dx += 4) {
                    g.fill(x + dx, y + 14, x + dx + 2, y + 15, 0xFFB8862B);
                }
            }
            g.pose().popPose();
        }

        /** Le dos d'une carte ; « aura » : la lueur de rareté qui monte pendant l'attente. */
        private void dos(GuiGraphics g, int x, int y, int w, int h, int couleurAura, float aura) {
            if (aura > 0) {
                for (int k = 8; k > 0; k--) {
                    int a = (int) (aura * 40 * k / 8);
                    g.fill(x - k * 2, y - k * 2, x + w + k * 2, y + h + k * 2, a << 24 | couleurAura & 0xFFFFFF);
                }
            }
            g.fill(x - 1, y - 1, x + w + 1, y + h + 1, 0xFF0B0C10);
            g.fill(x, y, x + w, y + h, 0xFF8A1C1C);
            g.fill(x + 4, y + 4, x + w - 4, y + h - 4, 0xFFB03A2E);
            g.fill(x + 8, y + 8, x + w - 8, y + h - 8, 0xFF8A1C1C);
            g.drawCenteredString(font, "BEAUVAIS", x + w / 2, y + h / 2 - 4, 0xFFFFD75E);
        }

        private void dessinerCarteBooster(GuiGraphics g, int i, long t) {
            int[] p = place(i);
            int x = p[0], y = p[1], w = p[2], h = p[3];
            int r = rarete(i);
            int couleur = Cartes.COULEURS_RGB[r];
            if (retournee[i] == 0) {
                float aura = 0F;
                float tremble = 0F;
                if (charge[i] > 0 && r > 0) {
                    float k = Mth.clamp((t - charge[i]) / (float) attente(r), 0F, 1F);
                    aura = k * (0.5F + 0.5F * (float) Math.sin(t / 60.0));
                    tremble = (float) Math.sin(t / 25.0) * k * r * 1.2F;
                }
                g.pose().pushPose();
                g.pose().translate(tremble, -Math.abs(tremble) * 0.5F, 0);
                dos(g, x, y, w, h, couleur, aura);
                g.pose().popPose();
                return;
            }
            float k = Mth.clamp((t - retournee[i]) / 350F, 0F, 1F);
            // les rayons derrière les cartes épiques et légendaires
            if (r >= 2 && k >= 0.5F) {
                int rayons = r == 3 ? 16 : 10;
                float tourne = (t % 8000) / 8000F * 360F;
                g.pose().pushPose();
                g.pose().translate(x + w / 2F, y + h / 2F, -1);
                for (int n = 0; n < rayons; n++) {
                    g.pose().pushPose();
                    g.pose().mulPose(com.mojang.math.Axis.ZP.rotationDegrees(tourne + n * 360F / rayons));
                    g.fill(-3, 0, 3, (int) (h * 1.1F), 0x30000000 | couleur & 0xFFFFFF);
                    g.pose().popPose();
                }
                g.pose().popPose();
            }
            // le retournement : la carte s'aplatit puis se redéploie, face visible
            float echelle = Math.abs(Mth.cos(k * (float) Math.PI));
            float saut = (float) Math.sin(k * Math.PI) * (6 + r * 4);
            g.pose().pushPose();
            g.pose().translate(x + w / 2F, -saut, 0);
            g.pose().scale(Math.max(0.02F, echelle), 1F, 1F);
            g.pose().translate(-(x + w / 2F), 0, 0);
            if (k < 0.5F) {
                dos(g, x, y, w, h, couleur, r > 0 ? 1F : 0F);
            } else {
                if (r > 0) {
                    for (int n = 4; n > 0; n--) {
                        int a = (int) ((25 + 15 * Math.sin(t / 250.0)) * n / 4);
                        g.fill(x - n * 2, y - n * 2, x + w + n * 2, y + h + n * 2, a << 24 | couleur & 0xFFFFFF);
                    }
                }
                dessinerCarte(g, font, Cartes.def(cartes.get(i)), x, y, w, h, true, 1);
                if (r > 0) {
                    reflet(g, x, y, w, h, t + i * 400L, r);
                }
            }
            g.pose().popPose();
        }

        /** Le reflet holographique qui passe en diagonale sur les cartes rares. */
        private static void reflet(GuiGraphics g, int x, int y, int w, int h, long t, int r) {
            float periode = 2600F - r * 400F;
            float pos = (t % (long) periode) / periode * (w + h) - h * 0.6F;
            int alpha = 0x22 + r * 0x14;
            for (int dy = 0; dy < h; dy++) {
                int bx = (int) (pos + dy * 0.6F);
                int x1 = Math.max(x, x + bx);
                int x2 = Math.min(x + w, x + bx + 10 + r * 3);
                if (x2 > x1) {
                    g.fill(x1, y + dy, x2, y + dy + 1, alpha << 24 | 0xFFFFFF);
                }
            }
        }

        private void dessinerEtincelles(GuiGraphics g, long t) {
            etincelles.removeIf(e -> t - e.naissance() > e.vie());
            for (Etincelle e : etincelles) {
                float age = (t - e.naissance()) / 16F;
                float x = e.x() + e.vx() * age;
                float y = e.y() + e.vy() * age + 0.02F * age * age;
                int a = (int) (255 * (1 - (t - e.naissance()) / e.vie()));
                int taille = (t / 90 + (long) e.x()) % 3 == 0 ? 2 : 1;
                g.fill((int) x, (int) y, (int) x + taille, (int) y + taille, Math.max(0, a) << 24 | e.couleur() & 0xFFFFFF);
            }
        }

        @Override
        public boolean isPauseScreen() {
            return false;
        }
    }

    /** Le classeur : toutes les cartes de la série, celles qu'on a en couleur. */
    static final class ClasseurScreen extends Screen {
        private static final int W = 300;
        private static final int H = 236;
        private static final int PAR_PAGE = 8;
        private int page;
        private String choisie;
        private int left;
        private int top;

        ClasseurScreen() {
            super(Component.literal("Classeur"));
        }

        void rafraichir() {
            rebuildWidgets();
        }

        private Map<String, Integer> contenu() {
            var joueur = Minecraft.getInstance().player;
            if (joueur == null) {
                return Map.of();
            }
            for (InteractionHand main : InteractionHand.values()) {
                ItemStack s = joueur.getItemInHand(main);
                if (s.is(CabaneItems.CLASSEUR.get())) {
                    return Classeur.contenu(s);
                }
            }
            return Map.of();
        }

        @Override
        protected void init() {
            left = (width - W) / 2;
            top = (height - H) / 2;
            List<Cartes.Def> toutes = Cartes.toutes();
            int pages = Math.max(1, (toutes.size() + PAR_PAGE - 1) / PAR_PAGE);
            page = Math.min(page, pages - 1);
            addRenderableWidget(Button.builder(Component.literal("Ranger mes cartes"), b ->
                    PacketDistributor.sendToServer(new Classeur.Action(0, ""))).bounds(left + 8, top + H - 24, 110, 18).build());
            addRenderableWidget(Button.builder(Component.literal("<"), b -> {
                page = Math.floorMod(page - 1, pages);
                rebuildWidgets();
            }).bounds(left + W / 2 - 10, top + H - 24, 20, 18).build());
            addRenderableWidget(Button.builder(Component.literal(">"), b -> {
                page = Math.floorMod(page + 1, pages);
                rebuildWidgets();
            }).bounds(left + W / 2 + 14, top + H - 24, 20, 18).build());
            if (choisie != null && contenu().getOrDefault(choisie, 0) > 0) {
                addRenderableWidget(Button.builder(Component.literal("Sortir 1 carte"), b ->
                        PacketDistributor.sendToServer(new Classeur.Action(1, choisie))).bounds(left + W - 98, top + H - 24, 90, 18).build());
            }
        }

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            if (super.mouseClicked(mouseX, mouseY, button)) {
                return true;
            }
            List<Cartes.Def> toutes = Cartes.toutes();
            for (int k = 0; k < PAR_PAGE; k++) {
                int i = page * PAR_PAGE + k;
                if (i >= toutes.size()) {
                    break;
                }
                int x = left + 10 + (k % 4) * 72;
                int y = top + 26 + (k / 4) * 96;
                if (mouseX >= x && mouseX < x + 66 && mouseY >= y && mouseY < y + 90) {
                    choisie = toutes.get(i).id();
                    rebuildWidgets();
                    return true;
                }
            }
            return false;
        }

        @Override
        public void tick() {
            // le serveur met à jour le classeur en main : on rafraîchit le bouton « Sortir »
            if (minecraft != null && minecraft.player != null && minecraft.player.tickCount % 10 == 0) {
                rebuildWidgets();
            }
        }

        @Override
        public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
            super.renderBackground(g, mouseX, mouseY, partialTick);
            Ui.panneau(g, left, top, W, H);
        }

        @Override
        public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
            super.render(g, mouseX, mouseY, partialTick);
            Map<String, Integer> contenu = contenu();
            List<Cartes.Def> toutes = Cartes.toutes();
            long possedees = toutes.stream().filter(d -> contenu.getOrDefault(d.id(), 0) > 0).count();
            Ui.titre(g, font, "Mon classeur", left + 10, top + 10);
            String progres = possedees + " / " + toutes.size();
            g.drawString(font, progres, left + W - 10 - font.width(progres), top + 10, Ui.OR, true);
            for (int k = 0; k < PAR_PAGE; k++) {
                int i = page * PAR_PAGE + k;
                if (i >= toutes.size()) {
                    break;
                }
                Cartes.Def def = toutes.get(i);
                int x = left + 10 + (k % 4) * 72;
                int y = top + 26 + (k / 4) * 96;
                int n = contenu.getOrDefault(def.id(), 0);
                if (def.id().equals(choisie)) {
                    g.fill(x - 3, y - 3, x + 69, y + 93, Ui.OR);
                }
                dessinerCarte(g, font, def, x, y, 66, 90, n > 0, n);
                if (n > 0 && mouseX >= x && mouseX < x + 66 && mouseY >= y && mouseY < y + 90) {
                    g.renderComponentTooltip(font, List.of(Component.literal(def.nom()), Component.literal(def.description())), mouseX, mouseY);
                }
            }
        }

        @Override
        public boolean isPauseScreen() {
            return false;
        }
    }

    private ClientCartes() {
    }
}
