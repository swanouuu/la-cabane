package fr.cabane.telephone;

import fr.cabane.Cabane;
import fr.cabane.client.ClientPhone;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/** Paquets réseau du téléphone. */
public final class PhoneNetwork {

    /** Client → serveur : une touche du téléphone. */
    public record Action(int action, String arg) implements CustomPacketPayload {
        public static final int CHOISIR_NUMERO = 0;
        public static final int APPELER = 1;
        public static final int DECROCHER = 2;
        public static final int RACCROCHER = 3;
        public static final int ETAT = 4;

        public static final Type<Action> TYPE = new Type<>(Cabane.id("telephone_action"));
        public static final StreamCodec<ByteBuf, Action> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, Action::action,
                ByteBufCodecs.stringUtf8(8), Action::arg,
                Action::new);

        @Override
        public Type<Action> type() {
            return TYPE;
        }
    }

    /** Serveur → client : ce que l'écran du téléphone doit afficher. */
    public record State(String myNumber, int state, String other) implements CustomPacketPayload {
        public static final int LIBRE = 0;
        public static final int APPEL_SORTANT = 1;
        public static final int APPEL_ENTRANT = 2;
        public static final int EN_LIGNE = 3;

        public static final Type<State> TYPE = new Type<>(Cabane.id("telephone_etat"));
        public static final StreamCodec<ByteBuf, State> CODEC = StreamCodec.composite(
                ByteBufCodecs.stringUtf8(8), State::myNumber,
                ByteBufCodecs.VAR_INT, State::state,
                ByteBufCodecs.stringUtf8(8), State::other,
                State::new);

        @Override
        public Type<State> type() {
            return TYPE;
        }
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1");
        registrar.playToServer(Action.TYPE, Action.CODEC, (payload, context) -> context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player) {
                PhoneCalls.handle(player, payload);
            }
        }));
        registrar.playToClient(State.TYPE, State.CODEC, (payload, context) ->
                context.enqueueWork(() -> ClientPhone.onState(payload)));
    }

    private PhoneNetwork() {
    }
}
