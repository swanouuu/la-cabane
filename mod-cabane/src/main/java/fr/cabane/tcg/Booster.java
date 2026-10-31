package fr.cabane.tcg;

import fr.cabane.Cabane;
import fr.cabane.client.ClientCartes;
import java.util.ArrayList;
import java.util.List;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/**
 * Ouvrir un booster : 5 cartes tirées chacune selon sa rareté (pas de rare garantie), rangées de la
 * plus commune à la plus rare : la meilleure arrive toujours en dernier.
 */
public final class Booster {
    public static final int CARTES = 5;

    public record Ouvert(List<String> cartes) implements CustomPacketPayload {
        public static final Type<Ouvert> TYPE = new Type<>(Cabane.id("booster_ouvert"));
        public static final StreamCodec<ByteBuf, Ouvert> CODEC =
                ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list(CARTES)).map(Ouvert::new, Ouvert::cartes);

        @Override
        public Type<Ouvert> type() {
            return TYPE;
        }
    }

    static void register(RegisterPayloadHandlersEvent event) {
        event.registrar("1").playToClient(Ouvert.TYPE, Ouvert.CODEC, (p, ctx) -> ctx.enqueueWork(() -> ClientCartes.booster(p.cartes())));
    }

    /** @return false si le catalogue est vide. */
    public static boolean ouvrir(ServerPlayer joueur) {
        List<String> tirees = new ArrayList<>();
        for (int i = 0; i < CARTES; i++) {
            Cartes.Def def = Cartes.tirer(joueur.getRandom(), false);
            if (def == null) {
                return false;
            }
            tirees.add(def.id());
        }
        tirees.sort(java.util.Comparator.comparingInt(id -> Cartes.def(id) == null ? 0 : Cartes.def(id).rarete()));
        for (String id : tirees) {
            ItemStack carte = Cartes.carte(id);
            if (!joueur.getInventory().add(carte)) {
                joueur.drop(carte, false);
            }
        }
        PacketDistributor.sendToPlayer(joueur, new Ouvert(tirees));
        return true;
    }

    private Booster() {
    }
}
