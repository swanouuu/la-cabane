package fr.cabane.labo;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import fr.cabane.Cabane;
import io.netty.buffer.ByteBuf;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/**
 * La grande expérience, sur la table du milieu de la salle d'expériences du labo. L'hôte tape
 * {@code /experience <spectateur> <savant> <allongé>} : dans le noir, le spectateur regarde, le savant s'acharne sur
 * l'allongé sanglé à la table (perceuse, liquide noir dans le cou, puis il invoque un énorme serpent sombre qui vient le
 * mordre), le cœur s'arrête et repart, et l'allongé, détaché, se relève et peut partir. Les trois sont tenus en place
 * pendant la scène (48 s).
 * Les trois la voient en cinématique (ClientOperation) ; les autres joueurs du labo la voient de l'extérieur, dans le monde ;
 * ailleurs, il ne se passe rien.
 *
 * <p>La commande se greffe sur le {@code /experience} de Minecraft (add, set, query restent les siens).
 */
@EventBusSubscriber(modid = Cabane.MODID)
public final class Operation {
    /** La durée de la scène, en ticks (le jeu de chacun la joue sur 48,5 s). */
    private static final int DUREE = 97 * 10 + 10;
    /** Où se tient chacun, en coordonnées du labo : le spectateur de l'autre côté de la table, le savant contre elle, l'allongé au pied. */
    private static final double[][] PLACES = {{-1.1, 2, -1.5}, {1.0, 2, 1.6}, {-1.7, 2, 0.5}};
    private static final float[] LACETS = {-46, 180, 90};
    /** Jusqu'où on la voit, autour de la table : tout le bâtiment du labo, mais pas le reste de la dimension (le QG du WOOHP…). */
    private static final double PORTEE = 64;

    /** Le top départ : où est le labo, le numéro de l'expérience, et les trois joueurs (spectateur, savant, allongé). */
    public record Debut(BlockPos origine, int numero, List<UUID> acteurs) implements CustomPacketPayload {
        public static final Type<Debut> TYPE = new Type<>(Cabane.id("operation_debut"));
        public static final StreamCodec<ByteBuf, Debut> CODEC = StreamCodec.composite(BlockPos.STREAM_CODEC, Debut::origine, ByteBufCodecs.VAR_INT,
                Debut::numero, UUIDUtil.STREAM_CODEC.apply(ByteBufCodecs.list()), Debut::acteurs, Debut::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    @EventBusSubscriber(modid = Cabane.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Reseau {
        @SubscribeEvent
        public static void register(RegisterPayloadHandlersEvent event) {
            event.registrar("1").playToClient(Debut.TYPE, Debut.CODEC,
                    (p, ctx) -> ctx.enqueueWork(() -> fr.cabane.client.operation.ClientOperation.demarrer(p)));
        }

        private Reseau() {
        }
    }

    /** L'expérience en cours : les acteurs, son numéro, et le tick où la scène commence. */
    private static List<UUID> acteurs = List.of();
    private static int numero;
    private static long depart = -1;
    private static long fin = -1;
    private static boolean envoye;

    /** Une expérience est-elle en cours ? (les écrans du labo affichent le sujet) */
    static boolean enCours() {
        return depart >= 0;
    }

    // ------------------------------------------------------------------ la commande

    @SubscribeEvent
    public static void commandes(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("experience")
                .then(Commands.argument("spectateur", EntityArgument.player()).requires(Labo::hote)
                        .then(Commands.argument("savant", EntityArgument.player())
                                .then(Commands.argument("allonge", EntityArgument.player()).executes(ctx -> lancer(ctx.getSource(), List.of(
                                        EntityArgument.getPlayer(ctx, "spectateur"), EntityArgument.getPlayer(ctx, "savant"),
                                        EntityArgument.getPlayer(ctx, "allonge"))))))));
    }

    private static int lancer(CommandSourceStack source, List<ServerPlayer> joueurs) throws CommandSyntaxException {
        MinecraftServer server = source.getServer();
        ServerLevel labo = Labo.niveau(server);
        if (labo == null) {
            source.sendFailure(Component.literal("La dimension du Laboratoire est introuvable."));
            return 0;
        }
        if (depart >= 0) {
            source.sendFailure(Component.literal("Une expérience est déjà en cours."));
            return 0;
        }
        for (ServerPlayer joueur : joueurs) {
            if (!Labo.dedans(joueur)) {
                Labo.entrer(joueur);
            }
            if (joueur.isSleeping()) {
                joueur.stopSleeping();
            }
        }
        acteurs = joueurs.stream().map(ServerPlayer::getUUID).toList();
        numero = LaboData.get(server).experienceSuivante();
        Experiences.dernierSujet = joueurs.get(2).getGameProfile().getName();
        Experiences.dernierResultat = "EN COURS…";
        placer(labo, true);
        depart = labo.getGameTime() + 30;
        fin = depart + DUREE;
        envoye = false;
        source.sendSuccess(() -> Component.literal("L'expérience n°" + numero + " commence…").withStyle(ChatFormatting.DARK_GREEN), true);
        return 1;
    }

    // ------------------------------------------------------------------ la scène

    /** Remet chacun à sa place s'il en a bougé (au début : tout le monde, et on enlève le vertige de l'arrivée au labo). */
    private static void placer(ServerLevel labo, boolean force) {
        List<UUID> deja = new ArrayList<>();
        for (int i = 0; i < acteurs.size(); i++) {
            if (!(labo.getServer().getPlayerList().getPlayer(acteurs.get(i)) instanceof ServerPlayer joueur) || joueur.level() != labo
                    || deja.contains(joueur.getUUID())) {
                continue;
            }
            // le même joueur dans plusieurs rôles (pour essayer seul) : il reste à la place de son premier rôle
            deja.add(joueur.getUUID());
            BlockPos o = LaboBuilder.abs(0, 0, 0);
            Vec3 p = new Vec3(o.getX() + PLACES[i][0], o.getY() + PLACES[i][1], o.getZ() + PLACES[i][2]);
            if (force || joueur.position().distanceToSqr(p) > 0.25) {
                joueur.teleportTo(labo, p.x, p.y, p.z, LACETS[i], 10);
                joueur.setDeltaMovement(Vec3.ZERO);
            }
            if (force) {
                joueur.removeEffect(MobEffects.CONFUSION);
                joueur.removeEffect(MobEffects.DARKNESS);
            }
        }
    }

    @SubscribeEvent
    public static void tick(ServerTickEvent.Post event) {
        if (depart < 0) {
            return;
        }
        ServerLevel labo = Labo.niveau(event.getServer());
        if (labo == null) {
            depart = -1;
            return;
        }
        long t = labo.getGameTime();
        placer(labo, false);
        if (t < depart) {
            return;
        }
        if (!envoye) {
            envoye = true;
            placer(labo, true);
            // ceux qui sont dans le labo la voient (les trois en cinématique, les autres de l'extérieur) ; plus loin, rien
            BlockPos table = LaboBuilder.abs(1, 3, 0);
            PacketDistributor.sendToPlayersNear(labo, null, table.getX(), table.getY(), table.getZ(), PORTEE,
                    new Debut(LaboBuilder.abs(0, 0, 0), numero, acteurs));
        }
        if (t >= fin) {
            Experiences.dernierResultat = "TERMINÉE";
            depart = -1;
            acteurs = List.of();
        }
    }

    private Operation() {
    }
}
