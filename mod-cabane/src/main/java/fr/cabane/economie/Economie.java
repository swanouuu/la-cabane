package fr.cabane.economie;

import fr.cabane.Cabane;
import fr.cabane.client.ClientEconomie;
import io.netty.buffer.ByteBuf;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/** Raccourcis pour manipuler l'argent et prévenir le joueur de son nouveau solde. */
public final class Economie {
    public static final String MONNAIE = "SakaiDollars";

    /** Serveur → client : le solde à afficher à droite de l'écran. */
    public record Solde(long montant) implements CustomPacketPayload {
        public static final Type<Solde> TYPE = new Type<>(Cabane.id("solde"));
        public static final StreamCodec<ByteBuf, Solde> CODEC = ByteBufCodecs.VAR_LONG.map(Solde::new, Solde::montant);

        @Override
        public Type<Solde> type() {
            return TYPE;
        }
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        event.registrar("1").playToClient(Solde.TYPE, Solde.CODEC,
                (payload, context) -> context.enqueueWork(() -> ClientEconomie.solde = payload.montant()));
    }

    /** 1250 → "1 250 $" */
    public static String format(long montant) {
        String brut = Long.toString(Math.abs(montant));
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < brut.length(); i++) {
            if (i > 0 && (brut.length() - i) % 3 == 0) {
                sb.append(' ');
            }
            sb.append(brut.charAt(i));
        }
        return (montant < 0 ? "-" : "") + sb + " $";
    }

    public static Component texte(long montant) {
        return Component.literal(format(montant)).withStyle(ChatFormatting.GOLD);
    }

    public static long solde(ServerPlayer joueur) {
        return Banque.get(joueur.server).solde(joueur.getUUID());
    }

    /** Débite le joueur s'il a assez ; sinon le prévient. */
    public static boolean payer(ServerPlayer joueur, long montant) {
        if (!Banque.get(joueur.server).debiter(joueur.getUUID(), montant)) {
            joueur.displayClientMessage(Component.literal("Il te manque " + format(montant - solde(joueur)) + ".")
                    .withStyle(ChatFormatting.RED), true);
            return false;
        }
        sync(joueur);
        return true;
    }

    public static void crediter(MinecraftServer server, UUID joueur, long montant) {
        Banque.get(server).crediter(joueur, montant);
        ServerPlayer enLigne = server.getPlayerList().getPlayer(joueur);
        if (enLigne != null) {
            sync(enLigne);
        }
    }

    public static void sync(ServerPlayer joueur) {
        PacketDistributor.sendToPlayer(joueur, new Solde(solde(joueur)));
    }

    /** Ouvre le compte à la première connexion, puis envoie le solde. */
    public static void connexion(ServerPlayer joueur) {
        if (Banque.get(joueur.server).ouvrir(joueur.getUUID(), joueur.getGameProfile().getName())) {
            joueur.sendSystemMessage(Component.literal("Ton compte en " + MONNAIE + " est ouvert : ")
                    .withStyle(ChatFormatting.YELLOW).append(texte(Banque.SOLDE_DEPART)).append(" pour commencer."));
        }
        sync(joueur);
    }

    private Economie() {
    }
}
