package fr.cabane.casino;

import fr.cabane.Cabane;
import fr.cabane.client.casino.ClientCasino;
import fr.cabane.economie.Economie;
import io.netty.buffer.ByteBuf;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/**
 * Les jeux du casino. Tout se décide ici, côté serveur : le client ne fait qu'envoyer sa mise
 * et animer le résultat. Les gains renvoyés comprennent la mise.
 */
public final class Casino {
    public static final long MISE_MIN = 1;
    public static final long MISE_MAX = 5_000;

    public static final int MACHINE = 0;
    public static final int ROULETTE = 1;
    public static final int DES = 2;
    public static final int ROUE = 3;
    public static final int PILE_OU_FACE = 4;

    // ---------------------------------------------------------------- règles

    /** Machine à sous : 6 symboles, du plus courant au jackpot. */
    public static final int[] SYMBOLES_POIDS = {35, 25, 18, 12, 7, 3};
    public static final int[] SYMBOLES_GAIN = {5, 8, 12, 20, 50, 150};

    /** Roulette européenne : numéros rouges. */
    public static final int[] ROUGES = {1, 3, 5, 7, 9, 12, 14, 16, 18, 19, 21, 23, 25, 27, 30, 32, 34, 36};
    public static final int PARI_ROUGE = 37, PARI_NOIR = 38, PARI_PAIR = 39, PARI_IMPAIR = 40,
            PARI_MANQUE = 41, PARI_PASSE = 42, PARI_DOUZAINE_1 = 43, PARI_DOUZAINE_2 = 44, PARI_DOUZAINE_3 = 45;

    /** Roue de la fortune : 20 cases, multiplicateurs ×10 (pour garder des entiers). */
    public static final int[] ROUE_CASES = {0, 15, 0, 20, 0, 15, 0, 30, 0, 15, 0, 20, 0, 40, 0, 15, 0, 20, 0, 0};

    public static boolean rouge(int n) {
        for (int r : ROUGES) {
            if (r == n) {
                return true;
            }
        }
        return false;
    }

    // ---------------------------------------------------------------- réseau

    public record Jouer(int jeu, long mise, int choix, net.minecraft.core.BlockPos machine) implements CustomPacketPayload {
        public static final Type<Jouer> TYPE = new Type<>(Cabane.id("casino_jouer"));
        public static final StreamCodec<ByteBuf, Jouer> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, Jouer::jeu,
                ByteBufCodecs.VAR_LONG, Jouer::mise,
                ByteBufCodecs.VAR_INT, Jouer::choix,
                net.minecraft.core.BlockPos.STREAM_CODEC, Jouer::machine,
                Jouer::new);

        @Override
        public Type<Jouer> type() {
            return TYPE;
        }
    }

    public record Resultat(int jeu, List<Integer> tirage, long gain) implements CustomPacketPayload {
        public static final Type<Resultat> TYPE = new Type<>(Cabane.id("casino_resultat"));
        public static final StreamCodec<ByteBuf, Resultat> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, Resultat::jeu,
                ByteBufCodecs.VAR_INT.apply(ByteBufCodecs.list(16)), Resultat::tirage,
                ByteBufCodecs.VAR_LONG, Resultat::gain,
                Resultat::new);

        @Override
        public Type<Resultat> type() {
            return TYPE;
        }
    }

    /** Le gérant remplit (montant positif) ou vide (négatif) la caisse de sa machine. */
    public record Caisse(net.minecraft.core.BlockPos machine, long montant) implements CustomPacketPayload {
        public static final Type<Caisse> TYPE = new Type<>(Cabane.id("casino_caisse"));
        public static final StreamCodec<ByteBuf, Caisse> CODEC = StreamCodec.composite(
                net.minecraft.core.BlockPos.STREAM_CODEC, Caisse::machine,
                ByteBufCodecs.VAR_LONG, Caisse::montant,
                Caisse::new);

        @Override
        public Type<Caisse> type() {
            return TYPE;
        }
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        event.registrar("1")
                .playToServer(Jouer.TYPE, Jouer.CODEC, (p, ctx) -> ctx.enqueueWork(() -> {
                    if (ctx.player() instanceof ServerPlayer joueur) {
                        jouer(joueur, p.jeu(), p.mise(), p.choix(), p.machine());
                    }
                }))
                .playToServer(Caisse.TYPE, Caisse.CODEC, (p, ctx) -> ctx.enqueueWork(() -> {
                    if (ctx.player() instanceof ServerPlayer joueur) {
                        gererCaisse(joueur, p.machine(), p.montant());
                    }
                }))
                .playToClient(Resultat.TYPE, Resultat.CODEC, (p, ctx) -> ctx.enqueueWork(() -> ClientCasino.resultat(p)));
        Blackjack.register(event);
        Ticket.register(event);
    }

    // ---------------------------------------------------------------- la caisse de chaque machine

    /** La machine visée, si c'est bien un jeu du casino à portée de main. */
    static fr.cabane.bloc.InteractifBlockEntity machine(ServerPlayer joueur, net.minecraft.core.BlockPos pos) {
        if (pos == null || joueur.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) > 64
                || !(joueur.level().getBlockState(pos).getBlock() instanceof fr.cabane.bloc.BlocInteractif bloc) || !bloc.ouverture().casino()
                || !(joueur.level().getBlockEntity(pos) instanceof fr.cabane.bloc.InteractifBlockEntity be)) {
            return null;
        }
        return be;
    }

    /**
     * Prendre la mise : refusée si la caisse ne peut pas payer le pire cas (gainMax, mise comprise).
     * La mise va dans la caisse.
     */
    static boolean miser(ServerPlayer joueur, fr.cabane.bloc.InteractifBlockEntity machine, long mise, long gainMax) {
        if (machine == null) {
            return false;
        }
        if (mise < MISE_MIN || mise > MISE_MAX) {
            joueur.displayClientMessage(Component.literal("Mise entre " + Economie.format(MISE_MIN) + " et " + Economie.format(MISE_MAX) + ".")
                    .withStyle(ChatFormatting.RED), true);
            return false;
        }
        if (machine.caisse() + mise < gainMax) {
            String gerant = machine.nomProprietaire().isEmpty() ? "le gérant" : machine.nomProprietaire();
            joueur.displayClientMessage(Component.literal("La caisse ne couvre pas cette mise (il faudrait " + Economie.format(gainMax - mise)
                    + " dedans). " + gerant + " doit la remplir.").withStyle(ChatFormatting.RED), true);
            return false;
        }
        if (!Economie.payer(joueur, mise)) {
            return false;
        }
        machine.ajouterCaisse(mise);
        return true;
    }

    /** Payer un gain depuis la caisse de la machine. */
    static void payer(ServerPlayer joueur, fr.cabane.bloc.InteractifBlockEntity machine, long gain) {
        if (gain <= 0) {
            return;
        }
        machine.ajouterCaisse(-gain);
        Economie.crediter(joueur.server, joueur.getUUID(), gain);
    }

    private static void gererCaisse(ServerPlayer joueur, net.minecraft.core.BlockPos pos, long montant) {
        fr.cabane.bloc.InteractifBlockEntity m = machine(joueur, pos);
        if (m == null || !m.estProprietaire(joueur.getUUID()) || montant == 0) {
            return;
        }
        if (montant > 0) {
            if (Economie.payer(joueur, montant)) {
                m.ajouterCaisse(montant);
                joueur.displayClientMessage(Component.literal("Caisse remplie : ").append(Economie.texte(m.caisse())).withStyle(ChatFormatting.GREEN), true);
            }
        } else {
            long retrait = Math.min(-montant, m.caisse());
            m.ajouterCaisse(-retrait);
            Economie.crediter(joueur.server, joueur.getUUID(), retrait);
            joueur.displayClientMessage(Component.literal("Tu récupères ").append(Economie.texte(retrait)).withStyle(ChatFormatting.GREEN), true);
        }
        Economie.sync(joueur);
    }

    /** Le plus gros gain possible pour une mise (mise comprise), selon le jeu et le pari. */
    static long gainMax(int jeu, long mise, int choix) {
        return switch (jeu) {
            case MACHINE -> mise * SYMBOLES_GAIN[SYMBOLES_GAIN.length - 1];
            case ROULETTE -> choix >= 0 && choix <= 36 ? mise * 36 : choix >= PARI_DOUZAINE_1 ? mise * 3 : mise * 2;
            case DES -> choix == 1 ? mise * 11 / 2 : mise * 23 / 10;
            case ROUE -> mise * 4;
            default -> mise * 195 / 100;
        };
    }

    private static void jouer(ServerPlayer joueur, int jeu, long mise, int choix, net.minecraft.core.BlockPos pos) {
        fr.cabane.bloc.InteractifBlockEntity machine = machine(joueur, pos);
        if (jeu < MACHINE || jeu > PILE_OU_FACE || !miser(joueur, machine, mise, gainMax(jeu, mise, choix))) {
            return;
        }
        RandomSource rnd = joueur.getRandom();
        List<Integer> tirage = new ArrayList<>();
        long gain = switch (jeu) {
            case MACHINE -> machine(rnd, mise, tirage);
            case ROULETTE -> roulette(rnd, mise, choix, tirage);
            case DES -> des(rnd, mise, choix, tirage);
            case ROUE -> roue(rnd, mise, tirage);
            default -> pileOuFace(rnd, mise, choix, tirage);
        };
        payer(joueur, machine, gain);
        PacketDistributor.sendToPlayer(joueur, new Resultat(jeu, tirage, gain));
    }

    private static long machine(RandomSource rnd, long mise, List<Integer> tirage) {
        for (int i = 0; i < 3; i++) {
            int r = rnd.nextInt(100);
            int s = 0;
            while (r >= SYMBOLES_POIDS[s]) {
                r -= SYMBOLES_POIDS[s];
                s++;
            }
            tirage.add(s);
        }
        int a = tirage.get(0), b = tirage.get(1), c = tirage.get(2);
        if (a == b && b == c) {
            return mise * SYMBOLES_GAIN[a];
        }
        long cerises = tirage.stream().filter(s -> s == 0).count();
        return cerises == 2 ? mise * 2 : 0;
    }

    private static long roulette(RandomSource rnd, long mise, int pari, List<Integer> tirage) {
        int n = rnd.nextInt(37);
        tirage.add(n);
        if (pari >= 0 && pari <= 36) {
            return n == pari ? mise * 36 : 0;
        }
        if (n == 0) {
            return 0;
        }
        boolean gagne = switch (pari) {
            case PARI_ROUGE -> rouge(n);
            case PARI_NOIR -> !rouge(n);
            case PARI_PAIR -> n % 2 == 0;
            case PARI_IMPAIR -> n % 2 == 1;
            case PARI_MANQUE -> n <= 18;
            case PARI_PASSE -> n >= 19;
            case PARI_DOUZAINE_1 -> n <= 12;
            case PARI_DOUZAINE_2 -> n >= 13 && n <= 24;
            case PARI_DOUZAINE_3 -> n >= 25;
            default -> false;
        };
        boolean douzaine = pari >= PARI_DOUZAINE_1 && pari <= PARI_DOUZAINE_3;
        return gagne ? mise * (douzaine ? 3 : 2) : 0;
    }

    /** choix : 0 = moins de 7, 1 = pile 7, 2 = plus de 7. */
    private static long des(RandomSource rnd, long mise, int choix, List<Integer> tirage) {
        int d1 = 1 + rnd.nextInt(6);
        int d2 = 1 + rnd.nextInt(6);
        tirage.add(d1);
        tirage.add(d2);
        int total = d1 + d2;
        return switch (choix) {
            case 0 -> total < 7 ? mise * 23 / 10 : 0;
            case 1 -> total == 7 ? mise * 11 / 2 : 0;
            default -> total > 7 ? mise * 23 / 10 : 0;
        };
    }

    private static long roue(RandomSource rnd, long mise, List<Integer> tirage) {
        int c = rnd.nextInt(ROUE_CASES.length);
        tirage.add(c);
        return mise * ROUE_CASES[c] / 10;
    }

    /** choix : 0 = pile, 1 = face. Contre la banque, le gain fait ×1,95. */
    private static long pileOuFace(RandomSource rnd, long mise, int choix, List<Integer> tirage) {
        int cote = rnd.nextInt(2);
        tirage.add(cote);
        return cote == choix ? mise * 195 / 100 : 0;
    }

    // ---------------------------------------------------------------- duel pile ou face entre joueurs

    private record Defi(UUID lanceur, long mise, long expire) {
    }

    private static final Map<UUID, Defi> DEFIS = new HashMap<>();

    /** /pileouface &lt;joueur&gt; &lt;mise&gt; : lancer un duel. */
    public static void defier(ServerPlayer lanceur, ServerPlayer adversaire, long mise) {
        if (lanceur == adversaire || mise < MISE_MIN || mise > MISE_MAX || Economie.solde(lanceur) < mise) {
            lanceur.displayClientMessage(Component.literal("Duel impossible (mise ou solde).").withStyle(ChatFormatting.RED), true);
            return;
        }
        DEFIS.put(adversaire.getUUID(), new Defi(lanceur.getUUID(), mise, System.currentTimeMillis() + 60_000));
        lanceur.sendSystemMessage(Component.literal("Duel envoyé à " + adversaire.getGameProfile().getName() + " pour ")
                .append(Economie.texte(mise)).withStyle(ChatFormatting.YELLOW));
        adversaire.sendSystemMessage(Component.literal(lanceur.getGameProfile().getName() + " te défie à pile ou face pour ")
                .append(Economie.texte(mise)).append(" ! Tape /pileouface accepter (60 s).").withStyle(ChatFormatting.YELLOW));
    }

    public static void accepter(ServerPlayer adversaire) {
        Defi defi = DEFIS.remove(adversaire.getUUID());
        ServerPlayer lanceur = defi == null ? null : adversaire.server.getPlayerList().getPlayer(defi.lanceur());
        if (defi == null || defi.expire() < System.currentTimeMillis() || lanceur == null) {
            adversaire.displayClientMessage(Component.literal("Aucun duel en attente.").withStyle(ChatFormatting.RED), true);
            return;
        }
        if (Economie.solde(lanceur) < defi.mise() || Economie.solde(adversaire) < defi.mise()) {
            adversaire.displayClientMessage(Component.literal("Un des deux n'a plus assez d'argent.").withStyle(ChatFormatting.RED), true);
            return;
        }
        Economie.payer(lanceur, defi.mise());
        Economie.payer(adversaire, defi.mise());
        ServerPlayer gagnant = adversaire.getRandom().nextBoolean() ? lanceur : adversaire;
        Economie.crediter(adversaire.server, gagnant.getUUID(), defi.mise() * 2);
        Component msg = Component.literal("Pile ou face : " + gagnant.getGameProfile().getName() + " remporte ")
                .append(Economie.texte(defi.mise() * 2)).append(" !").withStyle(ChatFormatting.GOLD);
        lanceur.sendSystemMessage(msg);
        adversaire.sendSystemMessage(msg);
    }

    private Casino() {
    }
}
