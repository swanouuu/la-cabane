package fr.cabane.client.casino;

import fr.cabane.bloc.BlocInteractif;
import fr.cabane.casino.Blackjack;
import fr.cabane.casino.Casino;
import fr.cabane.casino.Ticket;
import net.minecraft.client.Minecraft;

/** Point d'entrée côté client des jeux du casino. */
public final class ClientCasino {

    /** La machine ou la table qu'on a ouverte (pour sa caisse). */
    public static net.minecraft.core.BlockPos machine = net.minecraft.core.BlockPos.ZERO;

    public static void ouvrir(BlocInteractif.Ouverture jeu, net.minecraft.core.BlockPos pos) {
        machine = pos;
        ouvrir(jeu);
    }

    public static void ouvrir(BlocInteractif.Ouverture jeu) {
        Minecraft mc = Minecraft.getInstance();
        switch (jeu) {
            case MACHINE_A_SOUS -> mc.setScreen(new JeuxScreens.Machine());
            case ROULETTE -> mc.setScreen(new JeuxScreens.Roulette());
            case DES -> mc.setScreen(new JeuxScreens.Des());
            case ROUE -> mc.setScreen(new JeuxScreens.Roue());
            case PILE_OU_FACE -> mc.setScreen(new JeuxScreens.PileOuFace());
            case BLACKJACK -> mc.setScreen(new BlackjackScreen(null));
            default -> {
            }
        }
    }

    /** Le résultat à annoncer quand le tirage en 3D se termine (roulette et roue), et quand. */
    private static net.minecraft.network.chat.Component annonce;
    private static boolean annonceGagnee;
    private static long annonceA = -1;

    public static void resultat(Casino.Resultat resultat) {
        Minecraft mc = Minecraft.getInstance();
        boolean grandeTable = resultat.jeu() == Casino.ROULETTE || resultat.jeu() == Casino.ROUE;
        if (grandeTable && mc.level != null && (mc.screen instanceof JeuxScreens.Roulette || mc.screen instanceof JeuxScreens.Roue)) {
            // on ferme l'écran : le tirage se regarde en vrai, sur la table
            mc.setScreen(null);
            annonceGagnee = resultat.gain() > 0;
            annonce = annonceGagnee
                    ? net.minecraft.network.chat.Component.literal("Gagné ! +" + fr.cabane.economie.Economie.format(resultat.gain())).withStyle(net.minecraft.ChatFormatting.GREEN, net.minecraft.ChatFormatting.BOLD)
                    : net.minecraft.network.chat.Component.literal("Perdu… retente ta chance !").withStyle(net.minecraft.ChatFormatting.RED);
            annonceA = mc.level.getGameTime() + (resultat.jeu() == Casino.ROULETTE ? Casino.DUREE_ROULETTE : Casino.DUREE_ROUE);
            return;
        }
        if (mc.screen instanceof JeuScreen ecran) {
            ecran.resultat(resultat.tirage(), resultat.gain());
        }
    }

    @net.neoforged.fml.common.EventBusSubscriber(modid = fr.cabane.Cabane.MODID, value = net.neoforged.api.distmarker.Dist.CLIENT)
    public static final class Annonces {
        @net.neoforged.bus.api.SubscribeEvent
        public static void onTick(net.neoforged.neoforge.client.event.ClientTickEvent.Post event) {
            Minecraft mc = Minecraft.getInstance();
            if (annonceA < 0 || mc.level == null || mc.level.getGameTime() < annonceA) {
                return;
            }
            annonceA = -1;
            mc.gui.setOverlayMessage(annonce, false);
        }

        private Annonces() {
        }
    }

    public static void blackjack(Blackjack.Etat etat) {
        if (Minecraft.getInstance().screen instanceof BlackjackScreen ecran) {
            ecran.etat(etat);
        } else {
            Minecraft.getInstance().setScreen(new BlackjackScreen(etat));
        }
    }

    public static void ticket(Ticket.Gratte gratte) {
        Minecraft.getInstance().setScreen(new TicketScreen(gratte));
    }

    private ClientCasino() {
    }
}
