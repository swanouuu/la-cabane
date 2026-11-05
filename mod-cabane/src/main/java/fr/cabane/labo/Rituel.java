package fr.cabane.labo;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import fr.cabane.Cabane;
import fr.cabane.hachibi3.Hachibi3;
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
 * Le rituel de scellement du Hachibi, dans la salle des rituels du labo (d'après le rendu validé, docs/rituel).
 * L'hôte tape {@code /rituel <scelleur> <scientifique> <allongé> <spectateur>} : les quatre sont emmenés à leur place
 * autour de l'autel et ne bougent plus pendant la scène (44 s). Le scientifique tranche la tête d'un bébé villageois, le
 * scelleur fait les signes, des chaînes sortent du ventre de l'allongé, attrapent le Hachibi par le trou du mur, deviennent
 * jaunes et l'aspirent dans son ventre. Si le mur est fermé (ou le Hachibi absent), la commande le fait d'abord sauter,
 * comme le bouton rouge. Tout ce qu'on voit est joué par le jeu de chacun (ClientRituel) ; le serveur donne le top
 * départ, tient les acteurs en place, fait rugir le Hachibi aux bons moments et le retire une fois scellé.
 *
 * <p>Et {@code /jinchuriki} : le joueur baisse la tête et la marque des Jinchûriki apparaît sur son ventre (ClientJinchuriki).
 */
@EventBusSubscriber(modid = Cabane.MODID)
public final class Rituel {
    /** La durée de la scène, en ticks (le jeu de chacun la joue sur 44 s). */
    private static final int DUREE = 44 * 20 + 10;
    /** Les moments où le Hachibi rugit (attrapé, puis tiré), et où il disparaît dans le ventre (en ticks depuis le début). */
    private static final int CRI_ATTRAPE = 454, CRI_TIRE = 638, SCELLE = 768;
    /** Où se tient chacun, en coordonnées du labo : le scelleur au pied de l'autel, le scientifique et le spectateur de chaque côté. */
    private static final double[][] PLACES = {{32.5, 3, -37.5}, {33.05, 3, -39.35}, {35.0, 3.69, -37.5}, {33.05, 3, -35.65}};
    private static final float[] LACETS = {-90, -55, -90, -110};

    /** Le top départ : où est le labo (son centre), et les quatre joueurs (scelleur, scientifique, allongé, spectateur). */
    public record Debut(BlockPos origine, List<UUID> acteurs) implements CustomPacketPayload {
        public static final Type<Debut> TYPE = new Type<>(Cabane.id("rituel_debut"));
        public static final StreamCodec<ByteBuf, Debut> CODEC = StreamCodec.composite(BlockPos.STREAM_CODEC, Debut::origine,
                UUIDUtil.STREAM_CODEC.apply(ByteBufCodecs.list()), Debut::acteurs, Debut::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Ce joueur regarde la marque des Jinchûriki sur son ventre. */
    public record Marque(UUID joueur) implements CustomPacketPayload {
        public static final Type<Marque> TYPE = new Type<>(Cabane.id("rituel_marque"));
        public static final StreamCodec<ByteBuf, Marque> CODEC = StreamCodec.composite(UUIDUtil.STREAM_CODEC, Marque::joueur, Marque::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    @EventBusSubscriber(modid = Cabane.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Reseau {
        @SubscribeEvent
        public static void register(RegisterPayloadHandlersEvent event) {
            event.registrar("1")
                    .playToClient(Debut.TYPE, Debut.CODEC, (p, ctx) -> ctx.enqueueWork(() -> fr.cabane.client.rituel.ClientRituel.demarrer(p)))
                    .playToClient(Marque.TYPE, Marque.CODEC, (p, ctx) -> ctx.enqueueWork(() -> fr.cabane.client.rituel.ClientJinchuriki.demarrer(p.joueur())));
        }

        private Reseau() {
        }
    }

    /** Le rituel en cours : les acteurs, et le tick où la scène commence (après l'explosion du mur s'il le faut). */
    private static List<UUID> acteurs = List.of();
    private static long depart = -1;
    private static long fin = -1;
    private static boolean envoye;

    // ------------------------------------------------------------------ les commandes

    @SubscribeEvent
    public static void commandes(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("rituel").requires(Labo::hote)
                .then(Commands.argument("scelleur", EntityArgument.player())
                        .then(Commands.argument("scientifique", EntityArgument.player())
                                .then(Commands.argument("allonge", EntityArgument.player())
                                        .then(Commands.argument("spectateur", EntityArgument.player()).executes(ctx -> lancer(ctx.getSource(), List.of(
                                                EntityArgument.getPlayer(ctx, "scelleur"), EntityArgument.getPlayer(ctx, "scientifique"),
                                                EntityArgument.getPlayer(ctx, "allonge"), EntityArgument.getPlayer(ctx, "spectateur")))))))));
        // la marque sur soi : seulement ceux qui portent une bête (Hachibi, Ichibi, Minato) ; sur un autre joueur : l'hôte
        event.getDispatcher().register(Commands.literal("jinchuriki")
                .requires(s -> Labo.hote(s) || s.getPlayer() != null && fr.cabane.pouvoir.Jinchuriki.porteUneBete(s.getPlayer()))
                .executes(ctx -> marque(ctx.getSource().getPlayerOrException()))
                .then(Commands.argument("joueur", EntityArgument.player()).requires(Labo::hote)
                        .executes(ctx -> marque(EntityArgument.getPlayer(ctx, "joueur")))));
    }

    private static int lancer(CommandSourceStack source, List<ServerPlayer> joueurs) throws CommandSyntaxException {
        MinecraftServer server = source.getServer();
        ServerLevel labo = Labo.niveau(server);
        if (labo == null) {
            source.sendFailure(Component.literal("La dimension du Laboratoire est introuvable."));
            return 0;
        }
        if (depart >= 0) {
            source.sendFailure(Component.literal("Un rituel est déjà en cours."));
            return 0;
        }
        for (ServerPlayer joueur : joueurs) {
            if (!Labo.dedans(joueur)) {
                Labo.entrer(joueur);
            }
        }
        acteurs = joueurs.stream().map(ServerPlayer::getUUID).toList();
        placer(labo, true);
        // le mur doit être ouvert et le Hachibi sur son rocher : sinon, on le libère d'abord (ça prend 4 secondes)
        boolean pret = Scellement.ouvert(labo) && Scellement.bete(labo) != null;
        if (!pret) {
            Scellement.liberer(labo);
        }
        depart = labo.getGameTime() + (pret ? 30 : 95);
        fin = depart + DUREE;
        envoye = false;
        source.sendSuccess(() -> Component.literal("Le rituel commence…").withStyle(ChatFormatting.DARK_RED), true);
        return 1;
    }

    private static int marque(ServerPlayer joueur) {
        PacketDistributor.sendToPlayersNear(joueur.serverLevel(), null, joueur.getX(), joueur.getY(), joueur.getZ(), 96, new Marque(joueur.getUUID()));
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
            // ceux qui sont dans le labo le voient, jusqu'au rocher du Hachibi ; plus loin (le QG du WOOHP…), rien
            BlockPos autel = LaboBuilder.abs(LaboBuilder.RX, 3, LaboBuilder.RZ);
            PacketDistributor.sendToPlayersNear(labo, null, autel.getX(), autel.getY(), autel.getZ(), 160, new Debut(LaboBuilder.abs(0, 0, 0), acteurs));
        }
        long ici = t - depart;
        Hachibi3 bete = ici == CRI_ATTRAPE || ici == CRI_TIRE || ici == SCELLE ? Scellement.bete(labo) : null;
        if (bete != null && ici == SCELLE) {
            // il est dans le ventre de l'allongé : il n'est plus sur son rocher (le bouton rouge le fera revenir)
            bete.discard();
        } else if (bete != null) {
            bete.crier();
        }
        if (t >= fin) {
            depart = -1;
            acteurs = List.of();
        }
    }

    private Rituel() {
    }
}
