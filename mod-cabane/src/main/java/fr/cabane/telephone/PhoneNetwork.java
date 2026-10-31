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
        /** arg = « numéro|nom » */
        public static final int AJOUTER_CONTACT = 5;
        /** arg = numéro */
        public static final int SUPPRIMER_CONTACT = 6;

        public static final Type<Action> TYPE = new Type<>(Cabane.id("telephone_action"));
        public static final StreamCodec<ByteBuf, Action> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, Action::action,
                ByteBufCodecs.stringUtf8(64), Action::arg,
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

    public record Contact(String numero, String nom) {
        public static final StreamCodec<ByteBuf, Contact> CODEC = StreamCodec.composite(
                ByteBufCodecs.stringUtf8(8), Contact::numero,
                ByteBufCodecs.stringUtf8(32), Contact::nom,
                Contact::new);
    }

    public record Appel(String numero, int type, long temps) {
        public static final StreamCodec<ByteBuf, Appel> CODEC = StreamCodec.composite(
                ByteBufCodecs.stringUtf8(8), Appel::numero,
                ByteBufCodecs.VAR_INT, Appel::type,
                ByteBufCodecs.VAR_LONG, Appel::temps,
                Appel::new);
    }

    /** Serveur → client : le carnet de contacts et les appels récents. */
    public record Carnet(java.util.List<Contact> contacts, java.util.List<Appel> recents) implements CustomPacketPayload {
        public static final Type<Carnet> TYPE = new Type<>(Cabane.id("telephone_carnet"));
        public static final StreamCodec<ByteBuf, Carnet> CODEC = StreamCodec.composite(
                Contact.CODEC.apply(ByteBufCodecs.list(64)), Carnet::contacts,
                Appel.CODEC.apply(ByteBufCodecs.list(32)), Carnet::recents,
                Carnet::new);

        @Override
        public Type<Carnet> type() {
            return TYPE;
        }
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1");
        registrar.playToClient(Carnet.TYPE, Carnet.CODEC, (payload, context) ->
                context.enqueueWork(() -> ClientPhone.onCarnet(payload)));
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
