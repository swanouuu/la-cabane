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
     * L'ouverture d'un booster : le paquet scellé et brillant ; on clique, il tremble et se déchire ; les cinq
     * cartes forment un petit paquet au centre. Chaque clic révèle la carte du dessus, en grand : plus elle est
     * rare, plus elle se fait attendre (lueur qui monte, tremblement, lumière qui s'échappe des bords, étincelles
     * aspirées), et plus la révélation est belle (onde de choc, rayons qui tournent, éclair, reflet holographique).
     * Le clic suivant l'envoie rejoindre la rangée du bas. La meilleure carte est toujours la dernière.
     */
    public static final class BoosterScreen extends Screen {
        private enum Phase { SCELLE, DECHIRURE, DISTRIBUTION, REVELATION }

        private record Etincelle(float x, float y, float vx, float vy, int couleur, long naissance, float vie, boolean aspiree) {
        }

        private final List<String> cartes;
        /** Pour chaque carte : quand elle a commencé à se « charger », quand elle s'est retournée, quand elle est partie dans la rangée. */
        private final long[] charge = new long[5];
        private final long[] retournee = new long[5];
        private final long[] rangee = new long[5];
        private final List<Etincelle> etincelles = new java.util.ArrayList<>();
        private final java.util.Random alea = new java.util.Random();
        private Phase phase = Phase.SCELLE;
        private long debutPhase = System.currentTimeMillis();
        private long eclair;
        private int couleurEclair;
        /** La carte du dessus du paquet (ou celle qui est au centre). */
        private int courante;
        private Button fermer;

        public BoosterScreen(List<String> cartes) {
            super(Component.literal("Booster"));
            this.cartes = cartes;
        }

        @Override
        protected void init() {
            fermer = addRenderableWidget(Button.builder(Component.literal("Ranger les cartes"), b -> onClose())
                    .bounds(width / 2 - 60, height - 26, 120, 18).build());
            fermer.visible = fini();
        }

        private static long maintenant() {
            return System.currentTimeMillis();
        }

        private int rarete(int i) {
            Cartes.Def def = Cartes.def(cartes.get(i));
            return def == null ? 0 : def.rarete();
        }

        private boolean fini() {
            return cartes.isEmpty() || rangee[cartes.size() - 1] > 0;
        }

        /** Le temps d'attente avant de retourner une carte de cette rareté. */
        private static long attente(int rarete) {
            return switch (rarete) {
                case 1 -> 700;
                case 2 -> 1400;
                case 3 -> 2300;
                default -> 150;
            };
        }

        /** Pour les captures : trois cartes déjà rangées, la quatrième révélée au centre. */
        public void demo() {
            passer(Phase.REVELATION);
            long t = maintenant();
            for (int i = 0; i < 3; i++) {
                charge[i] = t - 5000;
                retournee[i] = t - 4000;
                rangee[i] = t - 3000;
            }
            charge[3] = t - 2000;
            retournee[3] = t - 300;
            courante = 3;
        }

        /** Tout révéler d'un coup (outil de captures, ou pour les pressés). */
        public void toutRetourner() {
            if (phase != Phase.REVELATION) {
                passer(Phase.REVELATION);
            }
            long t = maintenant() - 5000;
            for (int i = 0; i < cartes.size(); i++) {
                charge[i] = charge[i] == 0 ? t : charge[i];
                retournee[i] = retournee[i] == 0 ? t : retournee[i];
                rangee[i] = rangee[i] == 0 ? t : rangee[i];
            }
            courante = cartes.size();
            if (fermer != null) {
                fermer.visible = true;
            }
        }

        private void passer(Phase p) {
            phase = p;
            debutPhase = maintenant();
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
                case REVELATION -> avancer();
                default -> {
                }
            }
            return true;
        }

        /** Un clic : la carte révélée au centre part dans la rangée, et la suivante se prépare. */
        private void avancer() {
            if (courante >= cartes.size()) {
                return;
            }
            long t = maintenant();
            if (retournee[courante] > 0) {
                if (t - retournee[courante] < 450) {
                    return;
                }
                rangee[courante] = t;
                son(SoundEvents.BOOK_PAGE_TURN, 1.4F);
                courante++;
                if (courante >= cartes.size()) {
                    return;
                }
            }
            if (charge[courante] == 0) {
                charge[courante] = t;
                int r = rarete(courante);
                if (r >= 1) {
                    son(SoundEvents.BEACON_POWER_SELECT, r == 3 ? 0.5F : r == 2 ? 0.75F : 1.1F);
                }
                if (r == 3) {
                    son(SoundEvents.BEACON_AMBIENT, 1.5F);
                }
            }
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
            } else if (phase == Phase.DISTRIBUTION && t - debutPhase > 900) {
                passer(Phase.REVELATION);
            }
            if (phase == Phase.REVELATION && courante < cartes.size()) {
                int i = courante;
                int r = rarete(i);
                if (charge[i] > 0 && retournee[i] == 0) {
                    // pendant l'attente, des étincelles sont aspirées vers la carte
                    if (r >= 2) {
                        int[] c = centre();
                        for (int k = 0; k < r * 2; k++) {
                            double a = alea.nextDouble() * Math.PI * 2;
                            float d = 140 + alea.nextFloat() * 80;
                            etincelles.add(new Etincelle(c[0] + (float) Math.cos(a) * d, c[1] + (float) Math.sin(a) * d,
                                    -(float) Math.cos(a) * d, -(float) Math.sin(a) * d, Cartes.COULEURS_RGB[r], t, 600, true));
                        }
                    }
                    if (t - charge[i] >= attente(r)) {
                        retournee[i] = t;
                        reveler(i);
                    }
                }
            }
            if (fermer != null) {
                fermer.visible = fini();
            }
        }

        /** Le centre de l'écran, où l'on révèle les cartes, et la taille de la grande carte. */
        private int[] centre() {
            int h = Math.min(200, height - 120);
            int w = h * 7 / 10;
            return new int[] {width / 2, height / 2 - 24, w, h};
        }

        /** La place d'une carte dans la rangée du bas. */
        private int[] place(int i) {
            int w = Math.min(46, (width - 60) / 5 - 8);
            int h = w * 10 / 7;
            int total = cartes.size() * w + (cartes.size() - 1) * 8;
            return new int[] {width / 2 - total / 2 + i * (w + 8), height - h - 34, w, h};
        }

        /** La carte se retourne : son, étincelles, onde de choc, éclair, selon la rareté. */
        private void reveler(int i) {
            int r = rarete(i);
            int[] c = centre();
            int nombre = new int[] {14, 50, 110, 220}[r];
            int couleur = Cartes.COULEURS_RGB[r];
            for (int k = 0; k < nombre; k++) {
                double a = alea.nextDouble() * Math.PI * 2;
                float v = (r + 1.5F) * (0.6F + alea.nextFloat() * 1.8F);
                int cc = r >= 2 && alea.nextInt(3) == 0 ? 0xFFFFFFFF : couleur;
                etincelles.add(new Etincelle(c[0], c[1], (float) Math.cos(a) * v, (float) Math.sin(a) * v - 0.6F, cc, maintenant(), 800 + alea.nextInt(900), false));
            }
            switch (r) {
                case 0 -> son(SoundEvents.BOOK_PAGE_TURN, 1F);
                case 1 -> {
                    son(SoundEvents.AMETHYST_BLOCK_CHIME, 1.2F);
                    son(SoundEvents.EXPERIENCE_ORB_PICKUP, 1.4F);
                }
                case 2 -> {
                    son(SoundEvents.PLAYER_LEVELUP, 1.3F);
                    son(SoundEvents.AMETHYST_CLUSTER_BREAK, 1F);
                    eclair = maintenant();
                    couleurEclair = couleur;
                }
                default -> {
                    son(SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, 1F);
                    son(SoundEvents.TOTEM_USE, 1.4F);
                    son(SoundEvents.FIREWORK_ROCKET_TWINKLE, 1F);
                    eclair = maintenant();
                    couleurEclair = 0xFFF3C4;
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
                    // les cartes sortent du paquet et s'empilent au centre
                    int[] c = centre();
                    for (int i = cartes.size() - 1; i >= 0; i--) {
                        float k = Mth.clamp((dt - (cartes.size() - 1 - i) * 0.1F) / 0.4F, 0F, 1F);
                        k = 1 - (1 - k) * (1 - k);
                        int x = (int) (c[0] - c[2] / 2F + i * 2);
                        int y = (int) Mth.lerp(k, -c[3], c[1] - c[3] / 2F - i * 2);
                        dos(g, x, y, c[2], c[3], 0, 0F);
                    }
                }
                case REVELATION -> dessinerRevelation(g, t);
            }
            dessinerEtincelles(g, t);
            if (eclair > 0 && t - eclair < 600) {
                int a = (int) (210 * (1 - (t - eclair) / 600F));
                g.fill(0, 0, width, height, a << 24 | couleurEclair & 0xFFFFFF);
            }
        }

        private void dessinerRevelation(GuiGraphics g, long t) {
            int[] c = centre();
            // la rangée du bas : les cartes déjà vues
            for (int i = 0; i < cartes.size(); i++) {
                if (rangee[i] == 0) {
                    continue;
                }
                int[] p = place(i);
                float k = Mth.clamp((t - rangee[i]) / 380F, 0F, 1F);
                k = 1 - (1 - k) * (1 - k) * (1 - k);
                float x = Mth.lerp(k, c[0] - c[2] / 2F, p[0]);
                float y = Mth.lerp(k, c[1] - c[3] / 2F, p[1]);
                float s = Mth.lerp(k, c[2], p[2]) / p[2];
                g.pose().pushPose();
                g.pose().translate(x, y, 0);
                g.pose().scale(s, s, 1F);
                int r = rarete(i);
                if (r > 0) {
                    for (int n = 3; n > 0; n--) {
                        int a = (int) ((18 + 10 * Math.sin(t / 300.0 + i)) * n / 3);
                        g.fill(-n * 2, -n * 2, p[2] + n * 2, p[3] + n * 2, a << 24 | Cartes.COULEURS_RGB[r] & 0xFFFFFF);
                    }
                }
                dessinerCarte(g, font, Cartes.def(cartes.get(i)), 0, 0, p[2], p[3], true, 1);
                if (r > 0) {
                    reflet(g, 0, 0, p[2], p[3], t + i * 400L, r);
                }
                g.pose().popPose();
            }
            // le paquet qui reste au centre
            for (int i = cartes.size() - 1; i > courante; i--) {
                dos(g, c[0] - c[2] / 2 + (i - courante) * 2, c[1] - c[3] / 2 - (i - courante) * 2, c[2], c[3], 0, 0F);
            }
            // les textes du paquet ne doivent pas passer par-dessus la carte révélée
            g.flush();
            if (courante < cartes.size()) {
                dessinerCarteCentre(g, courante, t, c);
            }
            if (fini()) {
                texteCentre(g, "Tes cartes sont dans ton inventaire !", c[1] - 10, 0xFFFFFFFF);
            } else if (courante < cartes.size() && charge[courante] == 0) {
                texteCentre(g, courante == 0 ? "Clique pour révéler la première carte" : "Clique pour la carte suivante (" + (courante + 1) + "/5)",
                        c[1] + c[3] / 2 + 10, Ui.GRIS);
            } else if (courante < cartes.size() && retournee[courante] > 0) {
                texteCentre(g, "Clique pour continuer", c[1] + c[3] / 2 + 22, Ui.GRIS);
            }
        }

        /** La carte du dessus, au centre : en attente, en train de se charger, ou révélée. */
        private void dessinerCarteCentre(GuiGraphics g, int i, long t, int[] c) {
            int w = c[2], h = c[3];
            int x = c[0] - w / 2, y = c[1] - h / 2;
            int r = rarete(i);
            int couleur = Cartes.COULEURS_RGB[r];
            if (retournee[i] == 0) {
                float k = charge[i] > 0 ? Mth.clamp((t - charge[i]) / (float) attente(r), 0F, 1F) : 0F;
                float tremble = r > 0 ? (float) Math.sin(t / (28.0 - r * 5)) * k * k * (1 + r * 2.5F) : 0F;
                float monte = k * k * (4 + r * 3);
                if (r == 3 && k > 0) {
                    // une légendaire assombrit tout autour d'elle
                    g.fill(0, 0, width, height, (int) (k * 150) << 24);
                }
                g.pose().pushPose();
                g.pose().translate(tremble, -monte + Math.abs(tremble) * 0.3F, 0);
                dos(g, x, y, w, h, couleur, r > 0 ? k * (0.6F + 0.4F * (float) Math.sin(t / 70.0)) : 0F);
                if (r >= 1 && k > 0.3F) {
                    // la lumière s'échappe par les bords
                    int a = (int) (Mth.clamp((k - 0.3F) / 0.7F, 0F, 1F) * 220);
                    int lc = a << 24 | couleur & 0xFFFFFF;
                    g.fill(x - 2, y - 2, x + w + 2, y, lc);
                    g.fill(x - 2, y + h, x + w + 2, y + h + 2, lc);
                    g.fill(x - 2, y, x, y + h, lc);
                    g.fill(x + w, y, x + w + 2, y + h, lc);
                    if (r >= 2) {
                        for (int n = 0; n < 6; n++) {
                            int lx = x + (int) ((t / 7 + n * 37) % w);
                            g.fill(lx, y - 8 - (int) (k * 10), lx + 2, y, lc);
                        }
                    }
                }
                g.pose().popPose();
                return;
            }
            float k = Mth.clamp((t - retournee[i]) / 420F, 0F, 1F);
            long depuis = t - retournee[i];
            // l'onde de choc
            if (r >= 1 && depuis < 700) {
                float o = depuis / 700F;
                anneau(g, c[0], c[1], (int) (w * 0.3F + o * w * (1.2F + r * 0.4F)), 3, (int) (255 * (1 - o)) << 24 | couleur & 0xFFFFFF);
            }
            // les rayons qui tournent derrière les épiques et les légendaires
            if (r >= 2 && k >= 0.5F) {
                int rayons = r == 3 ? 20 : 12;
                float tourne = (t % 9000) / 9000F * 360F;
                g.pose().pushPose();
                g.pose().translate(c[0], c[1], 0);
                for (int n = 0; n < rayons; n++) {
                    g.pose().pushPose();
                    g.pose().mulPose(com.mojang.math.Axis.ZP.rotationDegrees(tourne + n * 360F / rayons));
                    g.fill(-4, 0, 4, (int) (h * 1.3F), (r == 3 ? 0x40000000 : 0x30000000) | couleur & 0xFFFFFF);
                    g.fill(-1, 0, 1, (int) (h * 1.4F), 0x30FFFFFF);
                    g.pose().popPose();
                }
                g.pose().popPose();
            }
            // le retournement en 3D : la carte se soulève, pivote, grossit un peu, puis se repose
            float angle = k * (float) Math.PI;
            float echelleX = Math.abs(Mth.cos(angle));
            float gonfle = 1F + Mth.sin(angle) * 0.12F + (k >= 1 ? 0.02F * Mth.sin(t / 300F) : 0F);
            float saut = Mth.sin(angle) * (10 + r * 6);
            g.pose().pushPose();
            g.pose().translate(c[0], c[1] - saut, 0);
            g.pose().scale(Math.max(0.02F, echelleX) * gonfle, gonfle, 1F);
            g.pose().translate(-c[0], -c[1], 0);
            if (k < 0.5F) {
                dos(g, x, y, w, h, couleur, r > 0 ? 1F : 0F);
            } else {
                if (r > 0) {
                    for (int n = 6; n > 0; n--) {
                        int a = (int) ((22 + 16 * Math.sin(t / 220.0)) * n / 6);
                        g.fill(x - n * 3, y - n * 3, x + w + n * 3, y + h + n * 3, a << 24 | couleur & 0xFFFFFF);
                    }
                }
                dessinerCarte(g, font, Cartes.def(cartes.get(i)), x, y, w, h, true, 1);
                if (r > 0) {
                    reflet(g, x, y, w, h, t, r);
                }
            }
            g.pose().popPose();
            // le nom de la rareté, qui rebondit
            if (k >= 0.5F) {
                float rebond = 1F + 0.35F * (float) Math.exp(-depuis / 180.0) * Mth.sin(depuis / 40F);
                String nom = Cartes.RARETES[r].toUpperCase() + (r == 3 ? " !!!" : r == 2 ? " !" : "");
                g.pose().pushPose();
                g.pose().translate(c[0], y - 22, 0);
                g.pose().scale(1.8F * rebond, 1.8F * rebond, 1F);
                g.drawCenteredString(font, nom, 0, 0, couleur);
                g.pose().popPose();
            }
            // une légendaire fait pleuvoir des étincelles tant qu'elle est là
            if (r == 3 && alea.nextInt(2) == 0) {
                etincelles.add(new Etincelle(alea.nextInt(width), -5, (alea.nextFloat() - 0.5F) * 0.5F, 1.5F + alea.nextFloat(),
                        alea.nextBoolean() ? 0xFFFFE08A : 0xFFFFFFFF, t, 2500, false));
            }
        }

        /** Un cercle (contour) de rayon r et d'épaisseur e. */
        private static void anneau(GuiGraphics g, int cx, int cy, int r, int e, int couleur) {
            int n = Math.max(24, r);
            for (int k = 0; k < n; k++) {
                double a = k * Math.PI * 2 / n;
                int px = cx + (int) (Math.cos(a) * r);
                int py = cy + (int) (Math.sin(a) * r);
                g.fill(px - e / 2, py - e / 2, px + e / 2 + 1, py + e / 2 + 1, couleur);
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
            for (int k = 6; k > 0; k--) {
                int a = (int) (18 + 10 * Math.sin(t / 300.0)) * k / 6;
                g.fill(x - k * 3, y - k * 3, x + w + k * 3, y + h + k * 3, a << 24 | 0xFFC23D);
            }
            for (int dy = 0; dy < h; dy++) {
                float k = dy / (float) h;
                int c = 0xFF000000 | (int) Mth.lerp(k, 0xD8, 0x7A) << 16 | (int) Mth.lerp(k, 0x3A, 0x14) << 8 | (int) Mth.lerp(k, 0x2A, 0x1A);
                g.fill(x, y + dy, x + w, y + dy + 1, c);
            }
            g.fill(x, y, x + w, y + 3, 0xFFFFD75E);
            g.fill(x, y + h - 3, x + w, y + h, 0xFFFFD75E);
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
                for (int k = 10; k > 0; k--) {
                    int a = (int) (aura * 45 * k / 10);
                    g.fill(x - k * 3, y - k * 3, x + w + k * 3, y + h + k * 3, a << 24 | couleurAura & 0xFFFFFF);
                }
            }
            g.fill(x - 1, y - 1, x + w + 1, y + h + 1, 0xFF0B0C10);
            g.fill(x, y, x + w, y + h, 0xFF8A1C1C);
            g.fill(x + 4, y + 4, x + w - 4, y + h - 4, 0xFFB03A2E);
            g.fill(x + 8, y + 8, x + w - 8, y + h - 8, 0xFF8A1C1C);
            for (int k = 0; k < 4; k++) {
                g.fill(x + 12 + k * 4, y + 12 + k * 4, x + w - 12 - k * 4, y + 13 + k * 4, 0x30FFD75E);
            }
            g.drawCenteredString(font, "BEAUVAIS", x + w / 2, y + h / 2 - 4, 0xFFFFD75E);
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
                float x;
                float y;
                if (e.aspiree()) {
                    float k = (t - e.naissance()) / e.vie();
                    x = e.x() + e.vx() * k * k;
                    y = e.y() + e.vy() * k * k;
                } else {
                    float age = (t - e.naissance()) / 16F;
                    x = e.x() + e.vx() * age;
                    y = e.y() + e.vy() * age + 0.02F * age * age;
                }
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
