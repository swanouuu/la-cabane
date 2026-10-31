package fr.cabane.casino;

import fr.cabane.Cabane;
import fr.cabane.client.casino.ClientCasino;
import fr.cabane.economie.Economie;
import io.netty.buffer.ByteBuf;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/**
 * Blackjack contre la banque : 4 jeux de cartes, la banque tire jusqu'à 17,
 * le blackjack paie 3 pour 2, on peut doubler sur les deux premières cartes.
 * Une carte = 0..51 : valeur = c % 13 (0 = As, 10..12 = Valet, Dame, Roi), couleur = c / 13.
 */
public final class Blackjack {
    public static final int DISTRIBUER = 0, TIRER = 1, RESTER = 2, DOUBLER = 3;
    public static final int AUCUNE = 0, EN_COURS = 1, GAGNE = 2, PERDU = 3, EGALITE = 4, BLACKJACK = 5;

    private static final class Partie {
        final List<Integer> sabot = new ArrayList<>();
        final List<Integer> joueur = new ArrayList<>();
        final List<Integer> croupier = new ArrayList<>();
        long mise;
        fr.cabane.bloc.InteractifBlockEntity table;
        int etat = AUCUNE;
        long gain;

        int tirer() {
            if (sabot.isEmpty()) {
                for (int i = 0; i < 4 * 52; i++) {
                    sabot.add(i % 52);
                }
                Collections.shuffle(sabot, new Random());
            }
            return sabot.remove(sabot.size() - 1);
        }
    }

    private static final Map<UUID, Partie> PARTIES = new HashMap<>();

    public record Action(int action, long mise, net.minecraft.core.BlockPos table) implements CustomPacketPayload {
        public static final Type<Action> TYPE = new Type<>(Cabane.id("blackjack_action"));
        public static final StreamCodec<ByteBuf, Action> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, Action::action,
                ByteBufCodecs.VAR_LONG, Action::mise,
                net.minecraft.core.BlockPos.STREAM_CODEC, Action::table,
                Action::new);

        @Override
        public Type<Action> type() {
            return TYPE;
        }
    }

    public record Etat(List<Integer> joueur, List<Integer> croupier, boolean cache, int etat, long mise, long gain) implements CustomPacketPayload {
        public static final Type<Etat> TYPE = new Type<>(Cabane.id("blackjack_etat"));
        public static final StreamCodec<ByteBuf, Etat> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT.apply(ByteBufCodecs.list(24)), Etat::joueur,
                ByteBufCodecs.VAR_INT.apply(ByteBufCodecs.list(24)), Etat::croupier,
                ByteBufCodecs.BOOL, Etat::cache,
                ByteBufCodecs.VAR_INT, Etat::etat,
                ByteBufCodecs.VAR_LONG, Etat::mise,
                ByteBufCodecs.VAR_LONG, Etat::gain,
                Etat::new);

        @Override
        public Type<Etat> type() {
            return TYPE;
        }
    }

    static void register(RegisterPayloadHandlersEvent event) {
        event.registrar("1")
                .playToServer(Action.TYPE, Action.CODEC, (p, ctx) -> ctx.enqueueWork(() -> {
                    if (ctx.player() instanceof ServerPlayer joueur) {
                        agir(joueur, p.action(), p.mise(), p.table());
                    }
                }))
                .playToClient(Etat.TYPE, Etat.CODEC, (p, ctx) -> ctx.enqueueWork(() -> ClientCasino.blackjack(p)));
    }

    /** Valeur d'une main, l'As comptant 11 tant que ça ne fait pas sauter. */
    public static int valeur(List<Integer> main) {
        int total = 0;
        int as = 0;
        for (int c : main) {
            int r = c % 13;
            if (r == 0) {
                as++;
                total += 11;
            } else {
                total += Math.min(10, r + 1);
            }
        }
        while (total > 21 && as > 0) {
            total -= 10;
            as--;
        }
        return total;
    }

    private static void agir(ServerPlayer joueur, int action, long mise, net.minecraft.core.BlockPos pos) {
        Partie p = PARTIES.computeIfAbsent(joueur.getUUID(), id -> new Partie());
        switch (action) {
            case DISTRIBUER -> {
                var table = Casino.machine(joueur, pos);
                // la caisse doit pouvoir payer une main doublée gagnante (4 fois la mise)
                if (p.etat == EN_COURS || !Casino.miser(joueur, table, mise, mise * 4)) {
                    break;
                }
                p.table = table;
                p.joueur.clear();
                p.croupier.clear();
                p.mise = mise;
                p.gain = 0;
                p.joueur.add(p.tirer());
                p.croupier.add(p.tirer());
                p.joueur.add(p.tirer());
                p.croupier.add(p.tirer());
                p.etat = EN_COURS;
                if (valeur(p.joueur) == 21) {
                    finir(joueur, p, valeur(p.croupier) == 21 ? EGALITE : BLACKJACK);
                }
            }
            case TIRER -> {
                if (p.etat != EN_COURS) {
                    break;
                }
                p.joueur.add(p.tirer());
                if (valeur(p.joueur) > 21) {
                    finir(joueur, p, PERDU);
                }
            }
            case RESTER -> {
                if (p.etat == EN_COURS) {
                    banque(joueur, p);
                }
            }
            case DOUBLER -> {
                if (p.etat != EN_COURS || p.joueur.size() != 2 || p.table == null || !Economie.payer(joueur, p.mise)) {
                    break;
                }
                p.table.ajouterCaisse(p.mise);
                p.mise *= 2;
                p.joueur.add(p.tirer());
                if (valeur(p.joueur) > 21) {
                    finir(joueur, p, PERDU);
                } else {
                    banque(joueur, p);
                }
            }
            default -> {
            }
        }
        envoyer(joueur, p);
    }

    private static void banque(ServerPlayer joueur, Partie p) {
        while (valeur(p.croupier) < 17) {
            p.croupier.add(p.tirer());
        }
        int j = valeur(p.joueur);
        int c = valeur(p.croupier);
        finir(joueur, p, c > 21 || j > c ? GAGNE : j == c ? EGALITE : PERDU);
    }

    private static void finir(ServerPlayer joueur, Partie p, int etat) {
        p.etat = etat;
        p.gain = switch (etat) {
            case BLACKJACK -> p.mise * 5 / 2;
            case GAGNE -> p.mise * 2;
            case EGALITE -> p.mise;
            default -> 0;
        };
        if (p.gain > 0 && p.table != null) {
            Casino.payer(joueur, p.table, p.gain);
        }
    }

    private static void envoyer(ServerPlayer joueur, Partie p) {
        boolean cache = p.etat == EN_COURS;
        List<Integer> croupier = cache && !p.croupier.isEmpty() ? List.of(p.croupier.get(0)) : List.copyOf(p.croupier);
        PacketDistributor.sendToPlayer(joueur, new Etat(List.copyOf(p.joueur), croupier, cache, p.etat, p.mise, p.gain));
    }

    /** Quitter le serveur en pleine main, c'est abandonner la mise. */
    public static void deconnexion(ServerPlayer joueur) {
        PARTIES.remove(joueur.getUUID());
    }

    private Blackjack() {
    }
}
