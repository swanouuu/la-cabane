package fr.cabane.parc;

import fr.cabane.Cabane;
import io.netty.buffer.ByteBuf;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/**
 * Le tunnel des cris de la Maison Hantée : trois endroits du tunnel où une silhouette cachée dans le mur saute sur le
 * joueur qui passe, avec un visage en plein écran et un hurlement. Seul celui qui passe le voit et l'entend (le paquet
 * n'est envoyé qu'à lui) ; chaque screamer se recharge au bout de quelques secondes pour le joueur suivant ou le
 * tour suivant.
 */
public final class MaisonHantee {
    /** Le screamer numéro {@code k} saute sur ce joueur. */
    public record Cri(byte k) implements CustomPacketPayload {
        public static final Type<Cri> TYPE = new Type<>(Cabane.id("parc_cri"));
        public static final StreamCodec<ByteBuf, Cri> CODEC = StreamCodec.composite(ByteBufCodecs.BYTE, Cri::k, Cri::new);

        @Override
        public Type<Cri> type() {
            return TYPE;
        }
    }

    private static final int RECHARGE = 20 * 12;
    private static final Map<UUID, long[]> DERNIERS = new HashMap<>();
    private static double[][] points;

    static void paquets(RegisterPayloadHandlersEvent event) {
        event.registrar("1").playToClient(Cri.TYPE, Cri.CODEC, (p, ctx) -> ctx.enqueueWork(() -> fr.cabane.client.parc.ClientMaison.cri(p.k())));
    }

    /** Les trois endroits (x, z) où les screamers sautent. */
    public static double[][] points() {
        if (points == null) {
            Trajet t = Maneges.trajet(Maneges.MAISON);
            Trajet.Point p = new Trajet.Point();
            double[] s = Maneges.maisonCris();
            double[][] l = new double[s.length][];
            for (int k = 0; k < s.length; k++) {
                t.a(s[k], p);
                l[k] = new double[]{p.x, p.z};
            }
            points = l;
        }
        return points;
    }

    static void tick(ServerLevel parc) {
        long maintenant = parc.getGameTime();
        if (maintenant % 2 != 0) {
            return;
        }
        double[][] pts = points();
        for (ServerPlayer j : parc.players()) {
            if (j.isSpectator() || Math.abs(j.getY() - Maneges.SOL) > 3) {
                continue;
            }
            for (int k = 0; k < pts.length; k++) {
                double dx = j.getX() - pts[k][0], dz = j.getZ() - pts[k][1];
                if (dx * dx + dz * dz > 1.6 * 1.6) {
                    continue;
                }
                long[] d = DERNIERS.computeIfAbsent(j.getUUID(), u -> new long[]{-9999, -9999, -9999});
                if (maintenant - d[k] < RECHARGE) {
                    d[k] = maintenant;
                    continue;
                }
                d[k] = maintenant;
                PacketDistributor.sendToPlayer(j, new Cri((byte) k));
                Cabane.LOGGER.info("Maison hantée : screamer {} pour {}", k, j.getGameProfile().getName());
            }
        }
    }

    static void oublier() {
        DERNIERS.clear();
    }

    private MaisonHantee() {
    }
}
