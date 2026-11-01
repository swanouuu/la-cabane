package fr.cabane.client.cinema;

import fr.cabane.Cabane;
import fr.cabane.client.ui.Ios;
import fr.cabane.vegeta.EvenementVegeta;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.PlayerFaceRenderer;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.neoforged.neoforge.network.PacketDistributor;

/** Qui allez-vous aider ? Deux grands panneaux, Goku à gauche, Vegeta à droite ; la majorité l'emporte. */
public class VoteScreen extends Screen {
    private static final ResourceLocation GOKU = Cabane.id("textures/entity/pnj/goku_ssj2.png");
    private static final ResourceLocation VEGETA = Cabane.id("textures/entity/pnj/majin_vegeta.png");
    private static int secondes;
    private static int voixGoku;
    private static int voixVegeta;
    private int monChoix = -1;

    public VoteScreen() {
        super(Component.literal("Qui allez-vous aider ?"));
    }

    public static void recevoir(EvenementVegeta.Vote v) {
        Minecraft mc = Minecraft.getInstance();
        secondes = v.secondes();
        voixGoku = v.goku();
        voixVegeta = v.vegeta();
        if (!v.ouvert()) {
            if (mc.screen instanceof VoteScreen) {
                mc.setScreen(null);
            }
            return;
        }
        ClientCinema.arreter();
        if (!(mc.screen instanceof VoteScreen)) {
            mc.setScreen(new VoteScreen());
        }
    }

    private int[] panneau(int camp) {
        int w = Math.min(180, width / 2 - 30);
        int h = Math.min(200, height - 90);
        int x = camp == 0 ? width / 2 - w - 12 : width / 2 + 12;
        return new int[] {x, (height - h) / 2 + 14, w, h};
    }

    @Override
    public boolean mouseClicked(double mx, double my, int bouton) {
        for (int camp = 0; camp < 2; camp++) {
            int[] p = panneau(camp);
            if (mx >= p[0] && mx < p[0] + p[2] && my >= p[1] && my < p[1] + p[3]) {
                monChoix = camp;
                PacketDistributor.sendToServer(new EvenementVegeta.Choix(camp));
                minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1F));
                return true;
            }
        }
        return super.mouseClicked(mx, my, bouton);
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float partialTick) {
        g.fill(0, 0, width, height, 0xE0000000);
        Ios.texteCentre(g, font, "QUI ALLEZ-VOUS AIDER ?", width / 2F, 20, 2.2F, 0xFFFFC83A, true);
        Ios.texteCentre(g, font, "La majorité l'emporte · " + secondes + " s", width / 2F, 44, 1F, 0xFFCCCCCC, false);
        for (int camp = 0; camp < 2; camp++) {
            int[] p = panneau(camp);
            boolean survol = mx >= p[0] && mx < p[0] + p[2] && my >= p[1] && my < p[1] + p[3];
            int fond = camp == 0 ? 0xFF8A3B00 : 0xFF3A1060;
            int bord = monChoix == camp ? 0xFFFFFFFF : survol ? 0xFFFFD27A : camp == 0 ? 0xFFFF8A2A : 0xFFB070FF;
            g.fill(p[0] - 2, p[1] - 2, p[0] + p[2] + 2, p[1] + p[3] + 2, bord);
            g.fill(p[0], p[1], p[0] + p[2], p[1] + p[3], fond);
            int visage = Math.min(p[2] - 40, p[3] - 90);
            PlayerFaceRenderer.draw(g, camp == 0 ? GOKU : VEGETA, p[0] + (p[2] - visage) / 2, p[1] + 14, visage);
            String nom = camp == 0 ? "AIDER GOKU" : "AIDER VEGETA";
            String detail = camp == 0 ? "Libérer Vegeta du sort de Babidi" : "Aider le Prince à battre Kakarot";
            Ios.texteCentre(g, font, nom, p[0] + p[2] / 2F, p[1] + visage + 24, 1.4F, 0xFFFFFFFF, true);
            Ios.texteCentre(g, font, detail, p[0] + p[2] / 2F, p[1] + visage + 44, 0.75F, 0xFFE0E0E0, false);
            int voix = camp == 0 ? voixGoku : voixVegeta;
            Ios.texteCentre(g, font, voix + " voix", p[0] + p[2] / 2F, p[1] + p[3] - 18, 1F, 0xFFFFE08A, true);
        }
        if (monChoix >= 0) {
            Ios.texteCentre(g, font, "Ton choix est enregistré — tu peux encore changer d'avis", width / 2F, height - 18, 0.85F, 0xFFAAAAAA, false);
        }
        super.render(g, mx, my, partialTick);
    }

    @Override
    public void renderBackground(GuiGraphics g, int mx, int my, float partialTick) {
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
