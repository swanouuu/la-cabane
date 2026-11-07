package fr.cabane.pouvoir;

import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import fr.cabane.Cabane;
import fr.cabane.client.pouvoir.ClientEluVent;
import io.netty.buffer.ByteBuf;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import org.joml.Vector3f;

/**
 * L'élu du vent : {@code /animation eluduvent <joueur>} (OP). Un coffre au trésor apparaît devant lui dans un coup de
 * vent ; il l'ouvre lentement pendant que la musique monte (la caméra tourne autour du coffre), et sur la fanfare la fée
 * du vent en jaillit : il la lève au-dessus de sa tête, puis elle se met à tourner autour de lui. À ce moment-là il
 * reçoit le pouvoir Vent niveau 1 (« vent » ; s'il avait déjà un pouvoir Vent, il le garde).
 * <p>
 * Ceux qui sont autour voient tout dans le monde (le coffre, la fée, ses bras levés) et entendent la musique près de
 * lui ; lui seul a la caméra et le texte. La musique n'est que dans le pack de sons privé (voix/cinematique/elu_vent.ogg) ;
 * sans lui, une fanfare de Minecraft. Ce qu'on voit est dessiné par {@link ClientEluVent}.
 */
@EventBusSubscriber(modid = Cabane.MODID)
public final class EluVent {
    /** Le coffre est posé à cette distance devant lui. */
    public static final double DISTANCE = 1.7;
    /** Quand la fée a rejoint son orbite (en secondes) : le pouvoir est donné. */
    public static final double DON = 11.4;
    /** Ceux qui sont plus loin ne reçoivent pas la scène. */
    private static final double PORTEE = 128;

    /** Serveur → ceux qui sont autour : la scène commence (ses pieds, le bas du coffre, vers où il regarde). */
    public record Debut(UUID joueur, Vector3f pieds, Vector3f coffre, float lacet) implements CustomPacketPayload {
        public static final Type<Debut> TYPE = new Type<>(Cabane.id("elu_vent"));
        public static final StreamCodec<ByteBuf, Debut> CODEC = StreamCodec.composite(UUIDUtil.STREAM_CODEC, Debut::joueur,
                ByteBufCodecs.VECTOR3F, Debut::pieds, ByteBufCodecs.VECTOR3F, Debut::coffre, ByteBufCodecs.FLOAT, Debut::lacet, Debut::new);

        @Override
        public Type<Debut> type() {
            return TYPE;
        }
    }

    /**
     * Les élus en pleine scène, et quand leur pouvoir leur sera donné (l'horloge réelle, comme la scène chez eux : un
     * serveur qui rame ne le donne pas en retard).
     */
    private static final Map<UUID, Long> A_DONNER = new HashMap<>();
    /** Ceux qui sont partis avant la fin : ils l'auront en revenant. */
    private static final Set<UUID> EN_ATTENTE = new HashSet<>();

    @SubscribeEvent
    public static void reseau(RegisterPayloadHandlersEvent event) {
        event.registrar("1").playToClient(Debut.TYPE, Debut.CODEC, (p, ctx) -> ctx.enqueueWork(() -> ClientEluVent.demarrer(p)));
    }

    public static int lancer(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        ServerPlayer joueur = EntityArgument.getPlayer(c, "joueur");
        String nom = joueur.getGameProfile().getName();
        if (A_DONNER.containsKey(joueur.getUUID())) {
            c.getSource().sendFailure(Component.literal("La scène de " + nom + " est déjà en cours."));
            return 0;
        }
        ServerLevel monde = joueur.serverLevel();
        float lacet = Mth.wrapDegrees(joueur.getYRot());
        double r = Math.toRadians(lacet);
        Vec3 pieds = joueur.position();
        double x = pieds.x - Math.sin(r) * DISTANCE, z = pieds.z + Math.cos(r) * DISTANCE;
        Vec3 coffre = new Vec3(x, sol(monde, x, pieds.y, z), z);
        Debut debut = new Debut(joueur.getUUID(), pieds.toVector3f(), coffre.toVector3f(), lacet);
        for (ServerPlayer p : monde.players()) {
            if (p == joueur || p.distanceToSqr(pieds) < PORTEE * PORTEE) {
                PacketDistributor.sendToPlayer(p, debut);
            }
        }
        A_DONNER.put(joueur.getUUID(), System.nanoTime() + (long) (DON * 1e9));
        c.getSource().sendSuccess(() -> Component.literal("La fée du vent attend " + nom + " dans son coffre."), false);
        return 1;
    }

    /** Le dessus du sol sous ce point (de ses pieds à 4 blocs plus bas) ; à défaut, la hauteur de ses pieds. */
    private static double sol(ServerLevel monde, double x, double y, double z) {
        for (int dy = 0; dy >= -4; dy--) {
            BlockPos b = BlockPos.containing(x, y + 0.4 + dy, z);
            VoxelShape forme = monde.getBlockState(b).getCollisionShape(monde, b);
            if (!forme.isEmpty()) {
                return b.getY() + forme.max(Direction.Axis.Y);
            }
        }
        return y;
    }

    /** La fée tourne autour de lui : il a le pouvoir Vent (ou garde celui qu'il avait déjà). */
    private static void donner(ServerPlayer joueur) {
        if (!Vent.est(Pouvoirs.pouvoir(joueur))) {
            Pouvoirs.donner(joueur, Vent.NOM, Pouvoirs.Couleur.VERT);
        }
        Vent.redire(joueur.server);
        joueur.sendSystemMessage(Component.literal("La fée du vent t'a choisi : tu as le pouvoir du Vent (niveau 1). Touche ")
                .append(Component.keybind("key.cabane.eveil").withStyle(ChatFormatting.WHITE))
                .append(" pour l'éveiller.").withStyle(ChatFormatting.GREEN));
    }

    @SubscribeEvent
    public static void tick(ServerTickEvent.Post event) {
        if (A_DONNER.isEmpty()) {
            return;
        }
        MinecraftServer server = event.getServer();
        long maintenant = System.nanoTime();
        for (Iterator<Map.Entry<UUID, Long>> it = A_DONNER.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, Long> e = it.next();
            if (maintenant - e.getValue() < 0) {
                continue;
            }
            it.remove();
            ServerPlayer joueur = server.getPlayerList().getPlayer(e.getKey());
            if (joueur != null) {
                donner(joueur);
            } else {
                EN_ATTENTE.add(e.getKey());
            }
        }
    }

    @SubscribeEvent
    public static void connexion(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer joueur && EN_ATTENTE.remove(joueur.getUUID())) {
            donner(joueur);
        }
    }

    @SubscribeEvent
    public static void arret(ServerStoppingEvent event) {
        A_DONNER.clear();
        EN_ATTENTE.clear();
    }

    private EluVent() {
    }
}
