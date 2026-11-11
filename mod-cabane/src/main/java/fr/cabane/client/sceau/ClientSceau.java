package fr.cabane.client.sceau;

import fr.cabane.Cabane;
import fr.cabane.sceau.Sceau;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.Input;
import net.minecraft.util.Mth;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.MovementInputUpdateEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;

/**
 * Ce que voit un joueur envoyé dans la salle du sceau par l'hôte (/sceau envoyer) : ses yeux s'alourdissent, clignent,
 * se ferment ; le noir (le serveur l'emmène pendant ce temps, voir {@link Sceau#ENDORMISSEMENT}) ; puis ils se rouvrent,
 * en clignant, dans la salle, face à la bête. Il ne bouge pas tant que ses yeux sont fermés.
 */
@EventBusSubscriber(modid = Cabane.MODID, value = Dist.CLIENT)
public final class ClientSceau {
    /** Quand la séquence a commencé (System.nanoTime), -1 : rien à l'écran. */
    private static long debut = -1;
    private static final float DUREE = 5.2F;

    public static void yeux() {
        debut = System.nanoTime();
    }

    private static float temps() {
        return (System.nanoTime() - debut) / 1e9F;
    }

    /** L'ouverture des yeux (0 fermés, 1 grands ouverts) selon des repères (temps, ouverture), lissée entre eux. */
    private static float courbe(float t, float... reperes) {
        if (t <= reperes[0]) {
            return reperes[1];
        }
        for (int i = 2; i < reperes.length; i += 2) {
            if (t <= reperes[i]) {
                float k = (t - reperes[i - 2]) / Math.max(1e-4F, reperes[i] - reperes[i - 2]);
                k = k * k * (3 - 2 * k);
                return Mth.lerp(k, reperes[i - 1], reperes[i + 1]);
            }
        }
        return reperes[reperes.length - 1];
    }

    private static float ouverture(float t) {
        // lourds, un premier clignement, il lutte, ils retombent… le noir… puis ils se rouvrent en clignant
        return courbe(t, 0, 1, 0.5F, 0.7F, 0.65F, 0.08F, 0.85F, 0.55F, 1.5F, 0.25F, 1.65F, 0, 1.85F, 0.14F, 2.15F, 0,
                3.5F, 0, 3.8F, 0.35F, 3.95F, 0.05F, 4.4F, 0.6F, 4.55F, 0.3F, 5.2F, 1);
    }

    /** Deux paupières noires qui se rejoignent au milieu de l'écran, le bord adouci, l'ombre qui gagne. */
    private static void paupieres(GuiGraphics g, int w, int h, float ouvert) {
        if (ouvert >= 1) {
            return;
        }
        if (ouvert <= 0.002F) {
            g.fill(0, 0, w, h, 0xFF000000);
            return;
        }
        g.fill(0, 0, w, h, (int) ((1 - ouvert) * 110) << 24);
        float demi = h * 0.62F * ouvert;
        int pas = 2;
        for (int x = 0; x < w; x += pas) {
            float u = (x + pas / 2F - w / 2F) / (w / 2F);
            float o = demi * (float) Math.sqrt(Math.max(0, 1 - u * u * 0.75F));
            int haut = (int) (h / 2F - o), bas = (int) (h / 2F + o);
            g.fill(x, 0, x + pas, haut, 0xFF000000);
            g.fill(x, bas, x + pas, h, 0xFF000000);
            int bord = Math.max(2, (int) (14 * ouvert));
            g.fillGradient(x, haut, x + pas, haut + bord, 0xD0000000, 0x00000000);
            g.fillGradient(x, bas - bord, x + pas, bas, 0x00000000, 0xD0000000);
        }
    }

    private static void dessiner(GuiGraphics g, float partiel) {
        if (debut < 0) {
            return;
        }
        float t = temps();
        if (t > DUREE) {
            debut = -1;
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        // la vue se brouille pendant qu'ils se ferment et juste après qu'ils se rouvrent
        if (t > 0.5F && t < 4.6F) {
            mc.gameRenderer.processBlurEffect(partiel);
            mc.getMainRenderTarget().bindWrite(false);
        }
        paupieres(g, g.guiWidth(), g.guiHeight(), ouverture(t));
    }

    /** Les yeux fermés, on ne bouge pas. */
    @SubscribeEvent
    public static void immobiliser(MovementInputUpdateEvent event) {
        if (debut >= 0 && temps() < 3.8F) {
            Input in = event.getInput();
            in.forwardImpulse = 0;
            in.leftImpulse = 0;
            in.up = in.down = in.left = in.right = false;
            in.jumping = false;
            in.shiftKeyDown = false;
        }
    }

    @EventBusSubscriber(modid = Cabane.MODID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
    public static final class Couches {
        @SubscribeEvent
        public static void enregistrer(RegisterGuiLayersEvent event) {
            event.registerAboveAll(Cabane.id("sceau_yeux"), (g, delta) -> dessiner(g, delta.getGameTimeDeltaPartialTick(true)));
        }
    }

    private ClientSceau() {
    }
}
