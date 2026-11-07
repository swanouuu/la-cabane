package fr.cabane.histoire;

import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import fr.cabane.Cabane;
import fr.cabane.client.cinema.ClientMarque;
import fr.cabane.client.cinema.ClientMarqueMaudite;
import io.netty.buffer.ByteBuf;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/**
 * La marque des élus : {@code /animation eludufeu <joueur> [joueur] [joueur]} (OP seulement, trois joueurs au plus). Chez les
 * joueurs choisis, et chez eux seuls, les yeux se ferment, ils regardent leur main, une flamme s'y grave en brûlant,
 * puis le rendez-vous s'affiche. Les autres ne voient rien. Le serveur ne fait qu'envoyer le message aux élus : tout le
 * spectacle se joue sur leur écran ({@link ClientMarque}).
 * <p>
 * La marque maudite : {@code /animation marquemaudite <joueur>} (un seul joueur). Une douleur, il tourne la tête vers
 * son épaule et y découvre trois virgules noires, puis il comprend qu'il ne devrait pas en parler
 * ({@link ClientMarqueMaudite}).
 * <p>
 * L'élu du vent : {@code /animation eluduvent <joueur>} : un coffre s'ouvre devant lui, la fée du vent en sort, et il
 * reçoit le pouvoir Vent niveau 1 ({@link fr.cabane.pouvoir.EluVent}).
 */
@EventBusSubscriber(modid = Cabane.MODID)
public final class Marque {
    /** Le rendez-vous (en grand), puis la consigne (en dessous, plus petit). */
    public static final String RENDEZ_VOUS = "Rendez-vous en haut du bâtiment principal dans 10 minutes.\n"
            + "Garde le silence : personne d'autre ne doit savoir.";

    /** Serveur → client : l'animation commence, avec ce message à la fin. */
    public record Lancer(String message) implements CustomPacketPayload {
        public static final Type<Lancer> TYPE = new Type<>(Cabane.id("marque"));
        public static final StreamCodec<ByteBuf, Lancer> CODEC = ByteBufCodecs.STRING_UTF8.map(Lancer::new, Lancer::message);

        @Override
        public Type<Lancer> type() {
            return TYPE;
        }
    }

    /** Ce que le marqué se dit à la fin (en grand, puis dans son tchat). */
    public static final String SILENCE = "Je ne devrais pas en parler.";

    /** Serveur → client : la marque maudite apparaît, avec ce message à la fin. */
    public record Maudite(String message) implements CustomPacketPayload {
        public static final Type<Maudite> TYPE = new Type<>(Cabane.id("marque_maudite"));
        public static final StreamCodec<ByteBuf, Maudite> CODEC = ByteBufCodecs.STRING_UTF8.map(Maudite::new, Maudite::message);

        @Override
        public Type<Maudite> type() {
            return TYPE;
        }
    }

    @SubscribeEvent
    public static void reseau(RegisterPayloadHandlersEvent event) {
        event.registrar("1")
                .playToClient(Lancer.TYPE, Lancer.CODEC, (p, ctx) -> ctx.enqueueWork(() -> ClientMarque.demarrer(p.message())))
                .playToClient(Maudite.TYPE, Maudite.CODEC, (p, ctx) -> ctx.enqueueWork(() -> ClientMarqueMaudite.demarrer(p.message())));
    }

    @SubscribeEvent
    public static void commandes(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("animation").requires(s -> s.hasPermission(2))
                .then(Commands.literal("eludufeu")
                        .then(Commands.argument("joueur1", EntityArgument.player()).executes(c -> lancer(c, 1))
                                .then(Commands.argument("joueur2", EntityArgument.player()).executes(c -> lancer(c, 2))
                                        .then(Commands.argument("joueur3", EntityArgument.player()).executes(c -> lancer(c, 3))))))
                .then(Commands.literal("marquemaudite")
                        .then(Commands.argument("joueur", EntityArgument.player()).executes(Marque::maudire)))
                .then(Commands.literal("eluduvent")
                        .then(Commands.argument("joueur", EntityArgument.player()).executes(fr.cabane.pouvoir.EluVent::lancer))));
    }

    private static int maudire(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        ServerPlayer joueur = EntityArgument.getPlayer(c, "joueur");
        PacketDistributor.sendToPlayer(joueur, new Maudite(SILENCE));
        c.getSource().sendSuccess(() -> Component.literal("La marque maudite apparaît sur l'épaule de " + joueur.getGameProfile().getName()), false);
        return 1;
    }

    private static int lancer(CommandContext<CommandSourceStack> c, int nombre) throws CommandSyntaxException {
        Set<ServerPlayer> elus = new LinkedHashSet<>();
        for (int i = 1; i <= nombre; i++) {
            elus.add(EntityArgument.getPlayer(c, "joueur" + i));
        }
        for (ServerPlayer joueur : elus) {
            PacketDistributor.sendToPlayer(joueur, new Lancer(RENDEZ_VOUS));
        }
        String noms = elus.stream().map(j -> j.getGameProfile().getName()).collect(Collectors.joining(", "));
        c.getSource().sendSuccess(() -> Component.literal("La marque apparaît sur la main de : " + noms), false);
        return elus.size();
    }

    private Marque() {
    }
}
