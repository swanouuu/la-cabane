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

    public static void resultat(Casino.Resultat resultat) {
        if (Minecraft.getInstance().screen instanceof JeuScreen ecran) {
            ecran.resultat(resultat.tirage(), resultat.gain());
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
