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
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/**
 * Les tickets à gratter, trois modèles tirés au hasard :
 * BEAUVAIS MILLIONS (9 montants, trois identiques = gagné), PENALTY ! (5 tirs au but : 3 buts = on gagne le lot)
 * et MORPION D'OR (3 étoiles alignées = on gagne le lot).
 * Le résultat est tiré (et payé) au moment où on gratte, l'écran ne fait que le dévoiler.
 */
public final class Ticket {
    public static final int[] MONTANTS = {5, 10, 20, 50, 100, 500, 1000};
    /** Chances de gain sur 1000 pour chaque montant (5 $ ne gagne jamais seul : c'est un leurre). */
    private static final int[] CHANCES = {0, 200, 150, 50, 20, 4, 1};

    public static final int MILLIONS = 0, PENALTY = 1, MORPION = 2;

    /** modele, les cases (montants, ou 1 = but / étoile, 0 = arrêt / croix), le gain, et le lot affiché. */
    public record Gratte(int modele, List<Integer> cases, long gain, long lot) implements CustomPacketPayload {
        public static final Type<Gratte> TYPE = new Type<>(Cabane.id("ticket"));
        public static final StreamCodec<ByteBuf, Gratte> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, Gratte::modele,
                ByteBufCodecs.VAR_INT.apply(ByteBufCodecs.list(9)), Gratte::cases,
                ByteBufCodecs.VAR_LONG, Gratte::gain,
                ByteBufCodecs.VAR_LONG, Gratte::lot,
                Gratte::new);

        @Override
        public Type<Gratte> type() {
            return TYPE;
        }
    }

    static void register(RegisterPayloadHandlersEvent event) {
        event.registrar("1").playToClient(Gratte.TYPE, Gratte.CODEC, (p, ctx) -> ctx.enqueueWork(() -> ClientCasino.ticket(p)));
    }

    public static void gratter(ServerPlayer joueur) {
        RandomSource rnd = joueur.getRandom();
        int tirage = rnd.nextInt(1000);
        int gagnant = -1;
        for (int i = 0; i < CHANCES.length; i++) {
            if (tirage < CHANCES[i]) {
                gagnant = i;
                break;
            }
            tirage -= CHANCES[i];
        }
        int modele = rnd.nextInt(3);
        if (modele == PENALTY) {
            penalty(joueur, rnd, gagnant);
            return;
        }
        if (modele == MORPION) {
            morpion(joueur, rnd, gagnant);
            return;
        }
        List<Integer> cases = new ArrayList<>();
        Map<Integer, Integer> compte = new HashMap<>();
        if (gagnant >= 0) {
            for (int i = 0; i < 3; i++) {
                cases.add(MONTANTS[gagnant]);
            }
            compte.put(MONTANTS[gagnant], 3);
        }
        while (cases.size() < 9) {
            int m = MONTANTS[rnd.nextInt(MONTANTS.length)];
            if (compte.getOrDefault(m, 0) < 2 && m != (gagnant >= 0 ? MONTANTS[gagnant] : -1)) {
                cases.add(m);
                compte.merge(m, 1, Integer::sum);
            }
        }
        Collections.shuffle(cases, new java.util.Random(rnd.nextLong()));
        long gain = gagnant >= 0 ? MONTANTS[gagnant] : 0;
        if (gain > 0) {
            Economie.crediter(joueur.server, joueur.getUUID(), gain);
        }
        PacketDistributor.sendToPlayer(joueur, new Gratte(MILLIONS, cases, gain, 0));
    }

    /** Le lot affiché quand on perd : souvent alléchant. */
    private static long leurre(RandomSource rnd) {
        return MONTANTS[2 + rnd.nextInt(MONTANTS.length - 2)];
    }

    private static void payer(ServerPlayer joueur, int modele, List<Integer> cases, long gain, long lot) {
        if (gain > 0) {
            Economie.crediter(joueur.server, joueur.getUUID(), gain);
        }
        PacketDistributor.sendToPlayer(joueur, new Gratte(modele, cases, gain, lot));
    }

    /** 5 tirs au but : 3 buts ou plus, on gagne le lot. */
    private static void penalty(ServerPlayer joueur, RandomSource rnd, int gagnant) {
        int buts = gagnant >= 0 ? 3 + (rnd.nextInt(4) == 0 ? 1 : 0) : rnd.nextInt(3);
        List<Integer> tirs = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            tirs.add(i < buts ? 1 : 0);
        }
        Collections.shuffle(tirs, new java.util.Random(rnd.nextLong()));
        long lot = gagnant >= 0 ? MONTANTS[gagnant] : leurre(rnd);
        payer(joueur, PENALTY, tirs, gagnant >= 0 ? lot : 0, lot);
    }

    private static final int[][] LIGNES = {{0, 1, 2}, {3, 4, 5}, {6, 7, 8}, {0, 3, 6}, {1, 4, 7}, {2, 5, 8}, {0, 4, 8}, {2, 4, 6}};

    public static int[] ligneGagnante(List<Integer> grille) {
        for (int[] l : LIGNES) {
            if (grille.get(l[0]) == 1 && grille.get(l[1]) == 1 && grille.get(l[2]) == 1) {
                return l;
            }
        }
        return null;
    }

    /** Une grille de morpion : trois étoiles alignées, on gagne le lot. */
    private static void morpion(ServerPlayer joueur, RandomSource rnd, int gagnant) {
        List<Integer> grille = new ArrayList<>();
        for (int essai = 0; essai < 200; essai++) {
            grille.clear();
            for (int i = 0; i < 9; i++) {
                grille.add(rnd.nextInt(5) < 2 ? 1 : 0);
            }
            if (gagnant >= 0) {
                int[] l = LIGNES[rnd.nextInt(LIGNES.length)];
                for (int i : l) {
                    grille.set(i, 1);
                }
                break;
            }
            if (ligneGagnante(grille) == null) {
                break;
            }
        }
        long lot = gagnant >= 0 ? MONTANTS[gagnant] : leurre(rnd);
        payer(joueur, MORPION, grille, gagnant >= 0 ? lot : 0, lot);
    }

    private Ticket() {
    }
}
