package fr.cabane.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexConsumer;
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
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.item.ItemProperties;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
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
import org.joml.Matrix4f;

/** Côté client du jeu de cartes : dessin d'une carte, ouverture de booster, classeur. */
@EventBusSubscriber(modid = Cabane.MODID, value = Dist.CLIENT)
public final class ClientCartes {

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> ItemProperties.register(CabaneItems.CARTE.get(), Cabane.id("rarete"), (stack, level, entity, seed) -> {
            Cartes.Def def = Cartes.def(stack.getOrDefault(CabaneComponents.CARTE.get(), ""));
            return def == null ? 0F : Math.min(def.rarete(), 3) / 3F;
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

    /** La couleur d'une rareté ; l'arc-en-ciel change sans arrêt. */
    static int teinte(int rarete) {
        return rarete == 5 ? 0xFF000000 | Mth.hsvToRgb((System.currentTimeMillis() % 2400L) / 2400F, 0.7F, 1F) : Cartes.COULEURS_RGB[rarete];
    }

    /** Une image de carte, lissée, en entier dans le rectangle ; ses coins sont arrondis. */
    private static void image(GuiGraphics g, ResourceLocation image, int x, int y, int w, int h) {
        Minecraft.getInstance().getTextureManager().getTexture(image).setFilter(true, false);
        RenderSystem.enableBlend();
        g.blit(image, x, y, w, h, 0F, 0F, 1, 1, 1, 1);
        RenderSystem.disableBlend();
    }

    /** Dessine une carte (w x h) : son image, ou un emplacement sombre avec son numéro si on ne l'a pas encore. */
    static void dessinerCarte(GuiGraphics g, Font font, Cartes.Def def, int x, int y, int w, int h, boolean connue, int nombre) {
        if (!connue || def == null) {
            g.fill(x - 1, y - 1, x + w + 1, y + h + 1, 0xFF0B0C10);
            g.fill(x, y, x + w, y + h, 0xFF2A2E36);
            g.fill(x + 3, y + 3, x + w - 3, y + h - 3, 0xFF20232A);
            g.drawCenteredString(font, "?", x + w / 2, y + h / 2 - 8, 0xFF555B66);
            if (def != null) {
                g.drawCenteredString(font, "n° " + def.numero(), x + w / 2, y + h - 14, 0xFF555B66);
            }
            return;
        }
        // en petit (classeur, rangée du bas), l'image réduite reste nette
        String fichier = (w < 80 ? "petites/" : "") + ResourceLocation.parse(def.id()).getPath();
        image(g, Cabane.id("textures/gui/cartes/" + fichier + ".png"), x, y, w, h);
        if (nombre > 1) {
            String n = "×" + nombre;
            g.fill(x + w - font.width(n) - 6, y + h - 13, x + w - 1, y + h - 2, 0xB0000000);
            g.drawString(font, n, x + w - 4 - font.width(n), y + h - 11, Ui.OR, true);
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
        /** Quand la carte courante a quitté le paquet pour venir au centre (0 : elle y est déjà). */
        private long arrivee;
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
                case 4 -> 3000;
                case 5 -> 3800;
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

        /** Pour les captures : trois cartes déjà rangées, la quatrième en train de se charger. */
        public void demoCharge() {
            demo();
            retournee[3] = 0;
            charge[3] = maintenant();
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
                arrivee = t;
                if (courante >= cartes.size()) {
                    return;
                }
            }
            if (charge[courante] == 0) {
                charge[courante] = t;
                int r = rarete(courante);
                if (r >= 1) {
                    son(SoundEvents.BEACON_POWER_SELECT, r >= 3 ? 0.5F : r == 2 ? 0.75F : 1.1F);
                }
                if (r >= 3) {
                    son(SoundEvents.BEACON_AMBIENT, 1.5F);
                }
            }
        }

        private static void son(net.minecraft.sounds.SoundEvent s, float hauteur, float volume) {
            Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(s, hauteur, volume));
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
                                    -(float) Math.cos(a) * d, -(float) Math.sin(a) * d, teinte(r), t, 600, true));
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

        /** Le paquet des cartes qui restent : à gauche, plus petit, pour que rien ne dépasse derrière la carte du centre. */
        private int[] pile() {
            int[] c = centre();
            int w = c[2] * 55 / 100;
            int h = c[3] * 55 / 100;
            return new int[] {c[0] - c[2] / 2 - w - 28, c[1] - h / 2, w, h};
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
            int nombre = new int[] {14, 50, 110, 220, 280, 360}[r];
            int couleur = teinte(r);
            for (int k = 0; k < nombre; k++) {
                double a = alea.nextDouble() * Math.PI * 2;
                float v = (r + 1.5F) * (0.6F + alea.nextFloat() * 1.8F);
                int cc = r == 5 ? 0xFF000000 | Mth.hsvToRgb(alea.nextFloat(), 0.7F, 1F) : r >= 2 && alea.nextInt(3) == 0 ? 0xFFFFFFFF : couleur;
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
                    couleurEclair = r == 4 ? 0xFF2E63 : r == 5 ? 0xFFFFFF : 0xFFF3C4;
                    if (r == 4) {
                        son(SoundEvents.LIGHTNING_BOLT_THUNDER, 1.1F, 0.5F);
                        son(SoundEvents.WITHER_SPAWN, 1.4F, 0.35F);
                    } else if (r == 5) {
                        son(SoundEvents.END_PORTAL_SPAWN, 1F, 0.6F);
                        son(SoundEvents.BEACON_ACTIVATE, 0.7F, 1F);
                        son(SoundEvents.FIREWORK_ROCKET_LARGE_BLAST, 0.8F, 1F);
                        son(SoundEvents.FIREWORK_ROCKET_TWINKLE, 0.7F, 1F);
                    }
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
                case REVELATION -> {
                    float s = secousse(t);
                    g.pose().pushPose();
                    g.pose().translate(Mth.sin((t % 3600000L) / 23F) * s, Mth.cos((t % 3600000L) / 31F) * s, 0);
                    dessinerRevelation(g, t);
                    g.pose().popPose();
                }
            }
            dessinerEtincelles(g, t);
            if (eclair > 0 && t - eclair < 600) {
                int a = (int) (210 * (1 - (t - eclair) / 600F));
                g.fill(0, 0, width, height, a << 24 | couleurEclair & 0xFFFFFF);
            }
        }

        /** L'écran tremble quand une Mythique ou une Arc-en-ciel arrive. */
        private float secousse(long t) {
            if (courante >= cartes.size() || charge[courante] == 0 || rarete(courante) < 4) {
                return 0F;
            }
            int r = rarete(courante);
            if (retournee[courante] == 0) {
                float k = Mth.clamp((t - charge[courante]) / (float) attente(r), 0F, 1F);
                return k * k * (r == 5 ? 5F : 3F);
            }
            return Math.max(0F, 1F - (t - retournee[courante]) / 1000F) * (r == 5 ? 9F : 6F);
        }

        /** Un nombre de 0 à 1 qui dépend seulement de n : les effets n'ont rien à retenir d'une image à l'autre. */
        private static float hasard(int n) {
            float x = Mth.sin(n * 12.9898F) * 43758.547F;
            return x - Mth.floor(x);
        }

        private static int arc(float teinte, int alpha) {
            return alpha << 24 | Mth.hsvToRgb(teinte - Mth.floor(teinte), 0.75F, 1F) & 0xFFFFFF;
        }

        /** Un triangle, dégradé du sommet A vers les deux autres ; tracé dans les deux sens pour être vu quel que soit son angle. */
        private static void triangle(VertexConsumer vc, Matrix4f m, float ax, float ay, int ca, float bx, float by, float cx, float cy, int cb) {
            vc.addVertex(m, ax, ay, 0).setColor(ca);
            vc.addVertex(m, bx, by, 0).setColor(cb);
            vc.addVertex(m, cx, cy, 0).setColor(cb);
            vc.addVertex(m, ax, ay, 0).setColor(ca);
            vc.addVertex(m, ax, ay, 0).setColor(ca);
            vc.addVertex(m, cx, cy, 0).setColor(cb);
            vc.addVertex(m, bx, by, 0).setColor(cb);
            vc.addVertex(m, ax, ay, 0).setColor(ca);
        }

        private static void point(VertexConsumer vc, Matrix4f m, float x, float y, float s, int c) {
            vc.addVertex(m, x, y, 0).setColor(c);
            vc.addVertex(m, x, y + s, 0).setColor(c);
            vc.addVertex(m, x + s, y + s, 0).setColor(c);
            vc.addVertex(m, x + s, y, 0).setColor(c);
        }

        private static void trait(VertexConsumer vc, Matrix4f m, float x1, float y1, float x2, float y2, float e, int c) {
            float l = Math.max(0.01F, Mth.sqrt((x2 - x1) * (x2 - x1) + (y2 - y1) * (y2 - y1)));
            float nx = -(y2 - y1) / l * e, ny = (x2 - x1) / l * e;
            triangle(vc, m, x1 + nx, y1 + ny, c, x1 - nx, y1 - ny, x2 - nx, y2 - ny, c);
            triangle(vc, m, x1 + nx, y1 + ny, c, x2 - nx, y2 - ny, x2 + nx, y2 + ny, c);
        }

        private static void cercle(VertexConsumer vc, Matrix4f m, float cx, float cy, float r, float e, int c) {
            int n = 72;
            for (int k = 0; k < n; k++) {
                float a = k * Mth.TWO_PI / n, b = (k + 1) * Mth.TWO_PI / n;
                trait(vc, m, cx + Mth.cos(a) * r, cy + Mth.sin(a) * r, cx + Mth.cos(b) * r, cy + Mth.sin(b) * r, e, c);
            }
        }

        /** Des rayons qui partent du centre et s'effacent en s'éloignant. */
        private static void soleil(VertexConsumer vc, Matrix4f m, float cx, float cy, float rayon, int branches, float rotation, float largeur, java.util.function.IntUnaryOperator couleur) {
            float d = largeur * Mth.PI / branches;
            for (int n = 0; n < branches; n++) {
                float a = rotation + n * Mth.TWO_PI / branches;
                int c = couleur.applyAsInt(n);
                triangle(vc, m, cx, cy, c, cx + Mth.cos(a + d) * rayon, cy + Mth.sin(a + d) * rayon, cx + Mth.cos(a - d) * rayon, cy + Mth.sin(a - d) * rayon, c & 0xFFFFFF);
            }
        }

        /** Des éclairs qui partent des bords de l'écran et frappent la carte ; ils changent 14 fois par seconde. */
        private void eclairs(VertexConsumer vc, Matrix4f m, long t, int[] c, int nombre, boolean arcEnCiel) {
            java.util.Random rnd = new java.util.Random(t / 70);
            for (int b = 0; b < nombre; b++) {
                float a = rnd.nextFloat() * Mth.TWO_PI, loin = Math.max(width, height);
                float px = c[0] + Mth.cos(a) * loin, py = c[1] + Mth.sin(a) * loin;
                int couleur = arcEnCiel ? arc(rnd.nextFloat(), 255) : 0xFFFF4A78;
                for (int s = 1; s <= 12; s++) {
                    float k = s / 12F, ecart = (1 - k) * 34;
                    float x = Mth.lerp(k, c[0] + Mth.cos(a) * loin, c[0]) + (s == 12 ? 0 : (rnd.nextFloat() - 0.5F) * ecart);
                    float y = Mth.lerp(k, c[1] + Mth.sin(a) * loin, c[1]) + (s == 12 ? 0 : (rnd.nextFloat() - 0.5F) * ecart);
                    trait(vc, m, px, py, x, y, 2.5F, couleur & 0xFFFFFF | 0x50000000);
                    trait(vc, m, px, py, x, y, 0.8F, 0xFFFFFFFF);
                    px = x;
                    py = y;
                }
            }
        }

        /** Un cadre lumineux qui pulse tout autour de l'écran. */
        private void bordure(VertexConsumer vc, Matrix4f m, int epaisseur, java.util.function.IntUnaryOperator couleur) {
            int n = 40;
            float tour = 2F * (width + height);
            for (int k = 0; k < n; k++) {
                float p = k * tour / n, q = (k + 1) * tour / n;
                int c = couleur.applyAsInt(k);
                float[] a = surBord(p), b = surBord(q);
                float cx = width / 2F, cy = height / 2F;
                float ax2 = Mth.lerp(epaisseur / (float) height, a[0], cx), ay2 = Mth.lerp(epaisseur / (float) height, a[1], cy);
                float bx2 = Mth.lerp(epaisseur / (float) height, b[0], cx), by2 = Mth.lerp(epaisseur / (float) height, b[1], cy);
                triangle(vc, m, a[0], a[1], c, ax2, ay2, bx2, by2, c & 0xFFFFFF);
                triangle(vc, m, b[0], b[1], c, a[0], a[1], bx2, by2, c & 0xFFFFFF);
            }
        }

        /** Le point à la distance p le long du tour de l'écran. */
        private float[] surBord(float p) {
            if (p < width) {
                return new float[] {p, 0};
            }
            p -= width;
            if (p < height) {
                return new float[] {width, p};
            }
            p -= height;
            if (p < width) {
                return new float[] {width - p, height};
            }
            return new float[] {0, height - (p - width)};
        }

        /** Ce qui se passe derrière une Mythique ou une Arc-en-ciel, pendant l'attente puis tant qu'elle est au centre. */
        private void fondRare(GuiGraphics g, long t, int[] c) {
            if (courante >= cartes.size() || charge[courante] == 0 || rarete(courante) < 4) {
                return;
            }
            int r = rarete(courante);
            boolean revelee = retournee[courante] > 0;
            float k = revelee ? 1F : Mth.clamp((t - charge[courante]) / (float) attente(r), 0F, 1F);
            g.fill(0, 0, width, height, (int) (k * k * (r == 5 ? 240 : 215)) << 24);
            VertexConsumer vc = g.bufferSource().getBuffer(RenderType.gui());
            Matrix4f m = g.pose().last().pose();
            // l'heure entière ne tient pas dans un float : les effets comptent sur une heure qui reboucle
            long temps = t % 3600000L;
            if (r == 4) {
                fondMythique(vc, m, temps, c, revelee, k);
            } else {
                fondArc(vc, m, temps, c, revelee, k, t - retournee[courante]);
            }
            g.flush();
        }

        /** Mythique : l'orage rouge et or. */
        private void fondMythique(VertexConsumer vc, Matrix4f m, long t, int[] c, boolean revelee, float k) {
            float loin = (float) Math.hypot(width, height);
            float s = t / 1000F;
            if (!revelee) {
                eclairs(vc, m, t, c, 2 + (int) (k * 9), false);
                soleil(vc, m, c[0], c[1], loin * k, 14, s * 2, 0.5F, n -> (int) (k * 110) << 24 | 0xFF2E63);
                return;
            }
            soleil(vc, m, c[0], c[1], loin, 16, s * 0.5F, 0.6F, n -> 0xB0FF2E63);
            soleil(vc, m, c[0], c[1], loin * 0.8F, 16, -s * 0.8F, 0.3F, n -> 0x90FFC23D);
            for (int n = 0; n < 4; n++) {
                float p = (t / 1500F + n / 4F) % 1F;
                cercle(vc, m, c[0], c[1], 40 + p * loin * 0.7F, 1.5F, (int) ((1 - p) * 220) << 24 | 0xFF6A88);
            }
            for (int n = 0; n < 110; n++) {
                // des braises qui montent
                float v = 0.03F + hasard(n) * 0.06F;
                float y = height - (t * v + hasard(n + 500) * height) % (height + 20);
                float x = hasard(n + 900) * width + Mth.sin(t / 400F + n) * 8;
                point(vc, m, x, y, 1 + hasard(n + 40) * 2.5F, n % 3 == 0 ? 0xFFFFC23D : n % 3 == 1 ? 0xFFFF2E63 : 0xFFFF8A3D);
            }
            if ((t / 70) % 5 == 0) {
                eclairs(vc, m, t, c, 3, false);
            }
            float pouls = 0.6F + 0.4F * Mth.sin(t / 180F);
            bordure(vc, m, 70, n -> (int) (pouls * 200) << 24 | 0xFF2E63);
        }

        /** Arc-en-ciel : soleils qui tournent, galaxie autour de la carte, ondes, feux d'artifice, pluie d'étoiles. */
        private void fondArc(VertexConsumer vc, Matrix4f m, long t, int[] c, boolean revelee, float k, long depuis) {
            float loin = (float) Math.hypot(width, height);
            float s = t / 1000F;
            if (!revelee) {
                // des rubans de couleur aspirés en spirale vers la carte
                for (int bras = 0; bras < 10; bras++) {
                    for (int n = 0; n < 46; n++) {
                        float p = (n / 46F + t / 800F) % 1F;
                        float a = bras * Mth.TWO_PI / 10 + p * 7F + s * 2;
                        float rayon = (1 - p) * loin * 0.6F * (0.4F + 0.6F * k);
                        point(vc, m, c[0] + Mth.cos(a) * rayon, c[1] + Mth.sin(a) * rayon, 1 + (1 - p) * 3, arc(bras / 10F + p + s * 0.5F, (int) (k * 255)));
                    }
                }
                eclairs(vc, m, t, c, 3 + (int) (k * 13), true);
                bordure(vc, m, (int) (40 + k * 60), n -> arc(n / 40F + s, (int) (k * 230)));
                soleil(vc, m, c[0], c[1], loin * k, 24, -s * 3, 0.45F, n -> arc(n / 24F + s, (int) (k * 130)));
                return;
            }
            soleil(vc, m, c[0], c[1], loin, 36, s * 0.7F, 0.75F, n -> arc(n / 36F + s * 0.4F, 0xC8));
            soleil(vc, m, c[0], c[1], loin * 0.85F, 18, -s * 1.1F, 0.45F, n -> arc(n / 18F - s * 0.6F, 0x88));
            soleil(vc, m, c[0], c[1], loin * 0.7F, 9, s * 2.2F, 0.16F, n -> 0x70FFFFFF);
            // les ondes de couleur
            for (int n = 0; n < 8; n++) {
                float p = (t / 1300F + n / 8F) % 1F;
                cercle(vc, m, c[0], c[1], 30 + p * loin * 0.75F, 2F, arc(n / 8F + s, (int) ((1 - p) * 255)));
            }
            // la galaxie qui tourne autour de la carte
            for (int n = 0; n < 340; n++) {
                float f = n / 340F;
                float rayon = c[3] * 0.62F + f * f * loin * 0.42F;
                float a = n * 2.39996F + s * (2.4F - f * 1.7F);
                float y = Mth.sin(a) * rayon * 0.8F;
                point(vc, m, c[0] + Mth.cos(a) * rayon, c[1] + y, 1 + hasard(n) * 2.5F, arc(f * 2 + s * 0.8F, 255));
            }
            // les feux d'artifice
            for (int f = 0; f < 7; f++) {
                long periode = 1300 + f * 90L;
                long cycle = (t + f * 311L) / periode;
                float p = ((t + f * 311L) % periode) / (float) periode;
                int graine = (int) (cycle * 7 + f * 131);
                float bx = hasard(graine) * width, by = hasard(graine + 1) * height * 0.8F;
                float teinte = hasard(graine + 2);
                for (int e = 0; e < 44; e++) {
                    float a = e * Mth.TWO_PI / 44, v = (0.55F + 0.45F * hasard(graine + e)) * 95 * (1 - (1 - p) * (1 - p));
                    point(vc, m, bx + Mth.cos(a) * v, by + Mth.sin(a) * v + p * p * 26, 2F, arc(teinte + e * 0.004F, (int) ((1 - p) * 255)));
                }
            }
            // la pluie d'étoiles
            for (int n = 0; n < 130; n++) {
                float v = 0.06F + hasard(n + 7) * 0.14F;
                float y = (t * v + hasard(n + 300) * height) % (height + 20) - 10;
                float x = (hasard(n + 800) * width + y * 0.25F) % width;
                point(vc, m, x, y, 1 + hasard(n + 60) * 2F, arc(hasard(n) + s, 255));
            }
            bordure(vc, m, 90, n -> arc(n / 40F + s, 0xE0));
            // la couleur de tout l'écran glisse doucement à la révélation, sans clignoter
            if (depuis < 1400) {
                float d = 1F - depuis / 1400F;
                soleil(vc, m, c[0], c[1], loin, 6, 0F, 1F, n -> arc(s * 0.3F, (int) (d * 150)));
            }
        }

        private void dessinerRevelation(GuiGraphics g, long t) {
            int[] c = centre();
            fondRare(g, t, c);
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
                        g.fill(-n * 2, -n * 2, p[2] + n * 2, p[3] + n * 2, a << 24 | teinte(r) & 0xFFFFFF);
                    }
                }
                dessinerCarte(g, font, Cartes.def(cartes.get(i)), 0, 0, p[2], p[3], true, 1);
                if (r > 0) {
                    reflet(g, 0, 0, p[2], p[3], t + i * 400L, r);
                }
                g.pose().popPose();
            }
            // le paquet qui reste, sur le côté
            int[] p = pile();
            int restantes = cartes.size() - courante - 1;
            for (int i = cartes.size() - 1; i > courante; i--) {
                int n = cartes.size() - 1 - i;
                dos(g, p[0] - n * 2, p[1] + n * 2, p[2], p[3], 0, 0F);
            }
            if (restantes > 0) {
                g.drawCenteredString(font, restantes + (restantes > 1 ? " cartes" : " carte"), p[0] + p[2] / 2, p[1] + p[3] + 8, Ui.GRIS);
            }
            // les textes du paquet ne doivent pas passer par-dessus la carte révélée
            g.flush();
            if (courante < cartes.size()) {
                // la carte glisse du paquet jusqu'au centre
                float k = arrivee == 0 ? 1F : Mth.clamp((t - arrivee) / 260F, 0F, 1F);
                k = 1 - (1 - k) * (1 - k);
                g.pose().pushPose();
                if (k < 1F) {
                    float px = p[0] + p[2] / 2F, py = p[1] + p[3] / 2F;
                    float s = Mth.lerp(k, p[2] / (float) c[2], 1F);
                    g.pose().translate(Mth.lerp(k, px, c[0]), Mth.lerp(k, py, c[1]), 0);
                    g.pose().scale(s, s, 1F);
                    g.pose().translate(-c[0], -c[1], 0);
                }
                dessinerCarteCentre(g, courante, t, c);
                g.pose().popPose();
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
            int couleur = teinte(r);
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
            for (int n = 0; r >= 1 && n < Math.max(1, r - 1); n++) {
                long d = depuis - n * 140;
                if (d >= 0 && d < 700) {
                    float o = d / 700F;
                    anneau(g, c[0], c[1], (int) (w * 0.3F + o * w * (1.2F + r * 0.4F)), 3, (int) (255 * (1 - o)) << 24 | couleur & 0xFFFFFF);
                }
            }
            // les rayons qui tournent derrière les épiques et les légendaires
            if (r >= 2 && r < 4 && k >= 0.5F) {
                int rayons = r >= 3 ? 20 : 12;
                float tourne = (t % 9000) / 9000F * 360F;
                g.pose().pushPose();
                g.pose().translate(c[0], c[1], 0);
                for (int n = 0; n < rayons; n++) {
                    g.pose().pushPose();
                    g.pose().mulPose(com.mojang.math.Axis.ZP.rotationDegrees(tourne + n * 360F / rayons));
                    g.fill(-4, 0, 4, (int) (h * 1.3F), (r >= 3 ? 0x40000000 : 0x30000000) | couleur & 0xFFFFFF);
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
                String nom = Cartes.RARETES[r].toUpperCase() + (r >= 3 ? " !!!" : r == 2 ? " !" : "");
                float taille = (r >= 4 ? 2.3F : 1.8F) * rebond;
                g.pose().pushPose();
                g.pose().translate(c[0], y - (r >= 4 ? 26 : 22), 0);
                g.pose().scale(taille, taille, 1F);
                if (r == 5) {
                    // l'arc-en-ciel : chaque lettre a sa couleur et danse
                    int lx = -font.width(nom) / 2;
                    for (int n = 0; n < nom.length(); n++) {
                        String lettre = nom.substring(n, n + 1);
                        g.drawString(font, lettre, lx, Math.round(Mth.sin((t % 3600000L) / 130F + n * 0.7F) * 2.5F), arc(n * 0.07F + (t % 3600000L) / 900F, 255), true);
                        lx += font.width(lettre);
                    }
                } else {
                    if (r == 4) {
                        g.pose().translate(alea.nextFloat() - 0.5F, alea.nextFloat() - 0.5F, 0);
                    }
                    g.drawCenteredString(font, nom, 0, 0, couleur);
                }
                g.pose().popPose();
            }
            // une légendaire fait pleuvoir des étincelles tant qu'elle est là
            if (r >= 3 && alea.nextInt(2) == 0) {
                etincelles.add(new Etincelle(alea.nextInt(width), -5, (alea.nextFloat() - 0.5F) * 0.5F, 1.5F + alea.nextFloat(),
                        alea.nextBoolean() ? (r == 3 ? 0xFFFFE08A : teinte(r)) : 0xFFFFFFFF, t, 2500, false));
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
            image(g, Cabane.id("textures/gui/cartes/dos.png"), x, y, w, h);
        }

        /** Le reflet qui passe en diagonale sur les cartes rares : blanc, doré sur les Gold, arc-en-ciel sur les Arc-en-ciel. */
        private static void reflet(GuiGraphics g, int x, int y, int w, int h, long t, int r) {
            int force = Math.min(r, 3);
            float periode = 2600F - force * 400F;
            float pos = (t % (long) periode) / periode * (w + h) - h * 0.6F;
            int alpha = 0x22 + force * 0x14;
            for (int dy = 0; dy < h; dy++) {
                int bx = (int) (pos + dy * 0.6F);
                int x1 = Math.max(x, x + bx);
                int x2 = Math.min(x + w, x + bx + 10 + force * 3 + (r > 3 ? 10 : 0));
                if (x2 > x1) {
                    int c = r == 5 ? Mth.hsvToRgb((t % 1500L) / 1500F + dy / (float) h * 0.6F, 0.55F, 1F) : r == 4 ? 0xFFE9A8 : 0xFFFFFF;
                    g.fill(x1, y + dy, x2, y + dy + 1, alpha << 24 | c & 0xFFFFFF);
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

    /**
     * Le classeur : un vrai classeur ouvert, couverture en cuir, anneaux au milieu, deux pages de six pochettes.
     * Les cartes qu'on a brillent sous leur pochette en plastique ; les autres restent des emplacements vides numérotés.
     * Une carte choisie s'affiche en grand à côté, avec sa rareté, son texte et combien on en a. Les pages se tournent.
     */
    static final class ClasseurScreen extends Screen {
        private static final int W = 400;
        private static final int H = 236;
        private static final int PAR_PAGE = 6;
        private static final int CW = 46;
        private static final int CH = 64;
        private static final int CUIR = 0xFF4A2E1C;
        private static final int CUIR_CLAIR = 0xFF6B4329;
        private static final int PAGE = 0xFF20242E;
        private static final int POCHETTE = 0xFF2A2F3B;
        private int double_;
        private String choisie;
        private int left;
        private int top;
        /** Le tournage de page : quand, et dans quel sens. */
        private long tourne;
        private int sens;

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

        private int doubles() {
            return Math.max(1, (Cartes.toutes().size() + 2 * PAR_PAGE - 1) / (2 * PAR_PAGE));
        }

        @Override
        protected void init() {
            left = (width - W) / 2;
            top = (height - H) / 2 + 8;
            double_ = Math.min(double_, doubles() - 1);
            addRenderableWidget(Button.builder(Component.literal("Ranger mes cartes"), b ->
                    PacketDistributor.sendToServer(new Classeur.Action(0, ""))).bounds(left, top + H + 6, 110, 18).build());
            addRenderableWidget(Button.builder(Component.literal("◀"), b -> tourner(-1)).bounds(left + W / 2 - 46, top + H + 6, 40, 18).build());
            addRenderableWidget(Button.builder(Component.literal("▶"), b -> tourner(1)).bounds(left + W / 2 + 6, top + H + 6, 40, 18).build());
            if (choisie != null && contenu().getOrDefault(choisie, 0) > 0) {
                addRenderableWidget(Button.builder(Component.literal("Sortir 1 carte"), b ->
                        PacketDistributor.sendToServer(new Classeur.Action(1, choisie))).bounds(left + W - 110, top + H + 6, 110, 18).build());
            }
        }

        private void tourner(int d) {
            double_ = Math.floorMod(double_ + d, doubles());
            tourne = System.currentTimeMillis();
            sens = d;
            Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.BOOK_PAGE_TURN, 1F));
            rebuildWidgets();
        }

        /** Le coin haut-gauche de la pochette k (0-11) de la double page. */
        private int[] pochette(int k) {
            int page = k / PAR_PAGE;
            int j = k % PAR_PAGE;
            int pageX = left + 14 + page * (W / 2 - 4);
            return new int[] {pageX + 12 + (j % 3) * (CW + 10), top + 26 + (j / 3) * (CH + 22)};
        }

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            if (super.mouseClicked(mouseX, mouseY, button)) {
                return true;
            }
            List<Cartes.Def> toutes = Cartes.toutes();
            for (int k = 0; k < 2 * PAR_PAGE; k++) {
                int i = double_ * 2 * PAR_PAGE + k;
                if (i >= toutes.size()) {
                    break;
                }
                int[] p = pochette(k);
                if (mouseX >= p[0] && mouseX < p[0] + CW && mouseY >= p[1] && mouseY < p[1] + CH) {
                    String id = toutes.get(i).id();
                    choisie = id.equals(choisie) ? null : id;
                    Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK.value(), 1.4F, 0.4F));
                    rebuildWidgets();
                    return true;
                }
            }
            return false;
        }

        @Override
        public boolean mouseScrolled(double mouseX, double mouseY, double dx, double dy) {
            if (dy != 0) {
                tourner(dy < 0 ? 1 : -1);
            }
            return true;
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
            // la couverture en cuir, ses coutures, et les deux pages
            g.fill(left - 4, top - 4, left + W + 4, top + H + 2, 0xFF1A0F08);
            g.fill(left - 2, top - 2, left + W + 2, top + H, CUIR);
            for (int x = left + 2; x < left + W - 2; x += 6) {
                g.fill(x, top + 1, x + 3, top + 2, CUIR_CLAIR);
                g.fill(x, top + H - 3, x + 3, top + H - 2, CUIR_CLAIR);
            }
            for (int page = 0; page < 2; page++) {
                int px = left + 10 + page * (W / 2 - 2);
                g.fill(px, top + 6, px + W / 2 - 18, top + H - 6, PAGE);
                g.fill(px, top + 6, px + W / 2 - 18, top + 7, 0xFF2E3442);
            }
            // la reliure et ses trois anneaux
            int cx = left + W / 2;
            g.fill(cx - 7, top + 4, cx + 7, top + H - 4, 0xFF15100C);
            for (int k = 0; k < 3; k++) {
                int ry = top + 40 + k * 70;
                g.fill(cx - 10, ry, cx + 10, ry + 8, 0xFF9AA0AA);
                g.fill(cx - 10, ry + 1, cx + 10, ry + 3, 0xFFD8DCE2);
                g.fill(cx - 10, ry + 6, cx + 10, ry + 8, 0xFF5C626C);
            }
        }

        @Override
        public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
            super.render(g, mouseX, mouseY, partialTick);
            Map<String, Integer> contenu = contenu();
            List<Cartes.Def> toutes = Cartes.toutes();
            long t = System.currentTimeMillis();
            // l'en-tête : titre, progression, et le détail par rareté
            long possedees = toutes.stream().filter(d -> contenu.getOrDefault(d.id(), 0) > 0).count();
            Ui.titre(g, font, "Mon classeur", left, top - 30);
            String progres = possedees + " / " + toutes.size() + " cartes";
            g.drawString(font, progres, left + W - font.width(progres), top - 30, Ui.OR, true);
            int barre = W;
            g.fill(left, top - 18, left + barre, top - 14, 0xFF101218);
            g.fill(left, top - 18, left + (int) (barre * (toutes.isEmpty() ? 0 : possedees / (double) toutes.size())), top - 14, Ui.OR);
            int rx = left;
            for (int r = 0; r < Cartes.RARETES.length; r++) {
                final int rr = r;
                long total = toutes.stream().filter(d -> d.rarete() == rr).count();
                long a = toutes.stream().filter(d -> d.rarete() == rr && contenu.getOrDefault(d.id(), 0) > 0).count();
                String s = Cartes.RARETES[r] + " " + a + "/" + total;
                g.drawString(font, s, rx, top - 11, Cartes.COULEURS_RGB[r], false);
                rx += font.width(s) + 8;
            }
            // les pochettes de la double page
            float apparition = Mth.clamp((t - tourne) / 220F, 0F, 1F);
            Cartes.Def survolee = null;
            for (int k = 0; k < 2 * PAR_PAGE; k++) {
                int i = double_ * 2 * PAR_PAGE + k;
                int[] p = pochette(k);
                g.fill(p[0] - 3, p[1] - 3, p[0] + CW + 3, p[1] + CH + 3, POCHETTE);
                if (i >= toutes.size()) {
                    continue;
                }
                Cartes.Def def = toutes.get(i);
                int n = contenu.getOrDefault(def.id(), 0);
                boolean dessus = mouseX >= p[0] && mouseX < p[0] + CW && mouseY >= p[1] && mouseY < p[1] + CH;
                if (def.id().equals(choisie)) {
                    g.fill(p[0] - 4, p[1] - 4, p[0] + CW + 4, p[1] + CH + 4, Ui.OR);
                } else if (n > 0 && def.rarete() > 0) {
                    int a = (int) (40 + 30 * Math.sin(t / 400.0 + i));
                    g.fill(p[0] - 3, p[1] - 3, p[0] + CW + 3, p[1] + CH + 3, a << 24 | teinte(def.rarete()) & 0xFFFFFF);
                }
                g.pose().pushPose();
                // la page qui vient d'être tournée glisse en place
                float decale = (1 - apparition) * 24 * sens;
                g.pose().translate(decale, dessus ? -2 : 0, 0);
                dessinerCarte(g, font, def, p[0], p[1], CW, CH, n > 0, n);
                if (n > 0 && def.rarete() >= 4) {
                    BoosterScreen.reflet(g, p[0], p[1], CW, CH, t + i * 400L, def.rarete());
                }
                // le plastique de la pochette : un reflet en biais
                g.fill(p[0] + 2, p[1] + 2, p[0] + CW - 2, p[1] + 4, 0x30FFFFFF);
                g.fill(p[0] + CW - 8, p[1] + 2, p[0] + CW - 5, p[1] + CH - 2, 0x18FFFFFF);
                g.pose().popPose();
                String numero = "n° " + def.numero();
                g.drawCenteredString(font, numero, p[0] + CW / 2, p[1] + CH + 5, n > 0 ? Ui.GRIS : 0xFF5A606C);
                if (dessus) {
                    survolee = def;
                }
            }
            if (apparition < 1F) {
                // l'ombre de la page qui tourne
                g.fill(left + 10, top + 6, left + W - 10, top + H - 6, (int) ((1 - apparition) * 120) << 24);
            }
            String pageTxt = "Pages " + (double_ * 2 + 1) + "-" + (double_ * 2 + 2) + " / " + doubles() * 2;
            g.drawCenteredString(font, pageTxt, left + W / 2, top + H - 16, 0xFF8A909C);
            // la carte choisie, en grand, à côté du classeur
            Cartes.Def montree = choisie != null ? Cartes.def(choisie) : survolee;
            if (montree != null) {
                int n = contenu.getOrDefault(montree.id(), 0);
                int bw = 96, bh = 134;
                int bx = left + W + 14 > width - bw - 6 ? left + W - bw - 6 : left + W + 14;
                int by = top + 10;
                if (bx + bw > width) {
                    bx = width - bw - 4;
                }
                int c = teinte(montree.rarete());
                for (int halo = 4; halo > 0; halo--) {
                    g.fill(bx - halo * 2, by - halo * 2, bx + bw + halo * 2, by + bh + halo * 2, (n > 0 ? 18 * halo : 6 * halo) << 24 | c & 0xFFFFFF);
                }
                dessinerCarte(g, font, montree, bx, by, bw, bh, n > 0, n);
                if (n > 0 && montree.rarete() >= 4) {
                    BoosterScreen.reflet(g, bx, by, bw, bh, t, montree.rarete());
                }
                List<FormattedCharSequence> desc = font.split(Component.literal(n > 0 ? montree.description() : "Pas encore trouvée…"), bw + 20);
                int ty = by + bh + 8;
                g.drawCenteredString(font, n > 0 ? "Tu en as " + n : "Manquante", bx + bw / 2, ty, n > 0 ? Ui.VERT : Ui.ROUGE);
                ty += 12;
                for (FormattedCharSequence l : desc.subList(0, Math.min(4, desc.size()))) {
                    g.drawCenteredString(font, l, bx + bw / 2, ty, Ui.GRIS);
                    ty += 10;
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
