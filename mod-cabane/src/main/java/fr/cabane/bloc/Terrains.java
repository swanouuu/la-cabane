package fr.cabane.bloc;

import fr.cabane.economie.Economie;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;

/** Les terrains et maisons à vendre : un clic pour le prix, un second (dans les 5 s) pour acheter. */
public final class Terrains {
    private record Attente(BlockPos pos, long expire) {
    }

    private static final Map<UUID, Attente> ATTENTES = new HashMap<>();

    public static void cliquer(ServerPlayer joueur, BlockPos pos) {
        if (!(joueur.level().getBlockEntity(pos) instanceof InteractifBlockEntity borne)) {
            return;
        }
        if (borne.proprietaire() != null) {
            joueur.displayClientMessage(Component.literal(borne.estProprietaire(joueur.getUUID()) ? "C'est ton terrain."
                    : "Ce terrain appartient à " + borne.nomProprietaire() + ".").withStyle(ChatFormatting.GRAY), true);
            return;
        }
        long prix = borne.caisse();
        long maintenant = System.currentTimeMillis();
        Attente attente = ATTENTES.get(joueur.getUUID());
        if (attente == null || !attente.pos().equals(pos) || attente.expire() < maintenant) {
            ATTENTES.put(joueur.getUUID(), new Attente(pos.immutable(), maintenant + 5000));
            joueur.displayClientMessage(Component.literal("À vendre : ").append(Economie.texte(prix))
                    .append(". Clique encore pour l'acheter.").withStyle(ChatFormatting.YELLOW), true);
            return;
        }
        ATTENTES.remove(joueur.getUUID());
        if (Economie.solde(joueur) < prix || !Economie.payer(joueur, prix)) {
            joueur.displayClientMessage(Component.literal("Pas assez d'argent : il faut ").append(Economie.texte(prix)).withStyle(ChatFormatting.RED), true);
            return;
        }
        borne.ajouterCaisse(-prix);
        borne.definirProprietaire(joueur.getUUID(), joueur.getGameProfile().getName());
        joueur.level().playSound(null, pos, SoundEvents.PLAYER_LEVELUP, SoundSource.BLOCKS, 0.8F, 1.2F);
        joueur.server.getPlayerList().broadcastSystemMessage(Component.literal("🏠 " + joueur.getGameProfile().getName()
                + " vient d'acheter un terrain à Beauvais !").withStyle(ChatFormatting.GREEN), false);
    }

    private Terrains() {
    }
}
