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
 * Ticket à gratter : 9 cases, trois montants identiques = on gagne ce montant.
 * Le résultat est tiré (et payé) au moment où on gratte, l'écran ne fait que le dévoiler.
 */
public final class Ticket {
    public static final int[] MONTANTS = {5, 10, 20, 50, 100, 500, 1000};
    /** Chances de gain sur 1000 pour chaque montant (5 $ ne gagne jamais seul : c'est un leurre). */
    private static final int[] CHANCES = {0, 200, 150, 50, 20, 4, 1};

    public record Gratte(List<Integer> cases, long gain) implements CustomPacketPayload {
        public static final Type<Gratte> TYPE = new Type<>(Cabane.id("ticket"));
        public static final StreamCodec<ByteBuf, Gratte> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT.apply(ByteBufCodecs.list(9)), Gratte::cases,
                ByteBufCodecs.VAR_LONG, Gratte::gain,
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
        PacketDistributor.sendToPlayer(joueur, new Gratte(cases, gain));
    }

    private Ticket() {
    }
}
