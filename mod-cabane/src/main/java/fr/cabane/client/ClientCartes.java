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

    /** Les 5 cartes du booster, face cachée ; on clique pour les retourner une à une. */
    static final class BoosterScreen extends Screen {
        private final List<String> cartes;
        private final int[] retournement = new int[5];
        private int prochaine;

        BoosterScreen(List<String> cartes) {
            super(Component.literal("Booster"));
            this.cartes = cartes;
        }

        @Override
        protected void init() {
            addRenderableWidget(Button.builder(Component.literal("Tout retourner"), b -> {
                while (prochaine < cartes.size()) {
                    retourner();
                }
            }).bounds(width / 2 - 60, height / 2 + 80, 120, 18).build());
        }

        void toutRetourner() {
            while (prochaine < cartes.size()) {
                retourner();
            }
        }

        private void retourner() {
            if (prochaine >= cartes.size()) {
                return;
            }
            retournement[prochaine] = 1;
            Cartes.Def def = Cartes.def(cartes.get(prochaine));
            boolean rare = def != null && def.rarete() >= 2;
            Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(
                    rare ? SoundEvents.PLAYER_LEVELUP : SoundEvents.BOOK_PAGE_TURN, rare ? 1.4F : 1F));
            prochaine++;
        }

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            if (super.mouseClicked(mouseX, mouseY, button)) {
                return true;
            }
            retourner();
            return true;
        }

        @Override
        public void tick() {
            for (int i = 0; i < retournement.length; i++) {
                if (retournement[i] > 0 && retournement[i] < 10) {
                    retournement[i]++;
                }
            }
        }

        @Override
        public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
            super.render(g, mouseX, mouseY, partialTick);
            int w = 70;
            int h = 100;
            int x0 = width / 2 - (cartes.size() * (w + 8)) / 2;
            int y = height / 2 - h / 2 - 10;
            Ui.titre(g, font, "Ton booster", width / 2 - font.width("Ton booster") / 2, y - 24);
            for (int i = 0; i < cartes.size(); i++) {
                int x = x0 + i * (w + 8);
                float t = retournement[i] == 0 ? 0F : Math.min(1F, (retournement[i] + partialTick) / 10F);
                float echelle = Math.abs(Mth.cos(t * (float) Math.PI));
                boolean face = t >= 0.5F;
                g.pose().pushPose();
                g.pose().translate(x + w / 2F, 0, 0);
                g.pose().scale(Math.max(0.02F, echelle), 1F, 1F);
                g.pose().translate(-(x + w / 2F), 0, 0);
                if (face) {
                    dessinerCarte(g, font, Cartes.def(cartes.get(i)), x, y, w, h, true, 1);
                } else {
                    g.fill(x - 1, y - 1, x + w + 1, y + h + 1, 0xFF0B0C10);
                    g.fill(x, y, x + w, y + h, 0xFF8A1C1C);
                    g.fill(x + 4, y + 4, x + w - 4, y + h - 4, 0xFFB03A2E);
                    g.drawCenteredString(font, "LA", x + w / 2, y + h / 2 - 10, 0xFFFFD75E);
                    g.drawCenteredString(font, "CABANE", x + w / 2, y + h / 2, 0xFFFFD75E);
                }
                g.pose().popPose();
            }
            if (prochaine < cartes.size()) {
                g.drawCenteredString(font, "Clique pour retourner une carte", width / 2, y + h + 12, Ui.GRIS);
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
