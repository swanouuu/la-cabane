package fr.cabane.zeus;

import fr.cabane.Cabane;
import io.netty.buffer.ByteBuf;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/**
 * L'éclair de Zeus. L'hôte tape {@code /zeus <pseudo>} : au-dessus des cieux, un Zeus géant et furieux prend l'orage
 * dans sa main et le jette ; l'éclair descend jusqu'à la cible, où qu'elle soit, et la frappe. Ensuite, des picotements
 * dans les mains… « Je devrais aller consulter quelqu'un. »
 * Seule la cible a la cinématique (ClientZeus) ; les joueurs autour voient juste l'éclair lui tomber dessus. Zeus
 * lui-même n'est jamais visible depuis la map : il n'apparaît que dans la cinématique.
 * La cible reste sur place, sans prendre de dégâts, le temps de la scène (32 s).
 */
@EventBusSubscriber(modid = Cabane.MODID)
public final class Zeus {
    /** La durée de la scène, en ticks. */
    private static final int DUREE = 32 * 20 + 10;
    /** Jusqu'où on voit l'éclair tomber, autour de la cible. */
    private static final double PORTEE = 160;

    /** Le top départ : la cible, où sont ses pieds, et vers où elle regarde (le lacet de Minecraft). */
    public record Debut(UUID cible, double x, double y, double z, float lacet) implements CustomPacketPayload {
        public static final Type<Debut> TYPE = new Type<>(Cabane.id("zeus_debut"));
        public static final StreamCodec<ByteBuf, Debut> CODEC = StreamCodec.composite(UUIDUtil.STREAM_CODEC, Debut::cible, ByteBufCodecs.DOUBLE,
                Debut::x, ByteBufCodecs.DOUBLE, Debut::y, ByteBufCodecs.DOUBLE, Debut::z, ByteBufCodecs.FLOAT, Debut::lacet, Debut::new);

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
                    (p, ctx) -> ctx.enqueueWork(() -> fr.cabane.client.zeus.ClientZeus.demarrer(p)));
        }

        private Reseau() {
        }
    }

    /** Les cibles en cours, et le tick où leur scène se termine. */
    private static final Map<UUID, Long> EN_COURS = new HashMap<>();

    @SubscribeEvent
    public static void commandes(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("zeus").requires(Zeus::hote)
                .then(Commands.argument("cible", EntityArgument.player()).executes(ctx -> lancer(ctx.getSource(), EntityArgument.getPlayer(ctx, "cible")))));
    }

    /** L'hôte : un admin du serveur, ou le joueur qui a ouvert le monde en solo. */
    private static boolean hote(CommandSourceStack source) {
        if (source.hasPermission(2)) {
            return true;
        }
        ServerPlayer joueur = source.getPlayer();
        return joueur != null && !source.getServer().isDedicatedServer() && source.getServer().isSingleplayerOwner(joueur.getGameProfile());
    }

    private static int lancer(CommandSourceStack source, ServerPlayer cible) {
        long maintenant = cible.serverLevel().getGameTime();
        Long fin = EN_COURS.get(cible.getUUID());
        if (fin != null && fin > maintenant) {
            source.sendFailure(Component.literal("Zeus s'occupe déjà de " + cible.getGameProfile().getName() + "."));
            return 0;
        }
        if (cible.isSleeping()) {
            cible.stopSleeping();
        }
        EN_COURS.put(cible.getUUID(), maintenant + DUREE);
        // pas de dégâts pendant qu'elle est tenue sur place (l'éclair ne fait que la secouer)
        cible.addEffect(new MobEffectInstance(MobEffects.DAMAGE_RESISTANCE, DUREE, 4, false, false, false));
        cible.addEffect(new MobEffectInstance(MobEffects.FIRE_RESISTANCE, DUREE, 0, false, false, false));
        cible.setDeltaMovement(0, Math.min(0, cible.getDeltaMovement().y), 0);
        PacketDistributor.sendToPlayersNear(cible.serverLevel(), null, cible.getX(), cible.getY(), cible.getZ(), PORTEE,
                new Debut(cible.getUUID(), cible.getX(), cible.getY(), cible.getZ(), cible.getYRot()));
        source.sendSuccess(() -> Component.literal("Zeus lève le bras au-dessus de " + cible.getGameProfile().getName() + "…")
                .withStyle(ChatFormatting.GOLD), false);
        return 1;
    }

    @SubscribeEvent
    public static void tick(ServerTickEvent.Post event) {
        if (EN_COURS.isEmpty()) {
            return;
        }
        long t = event.getServer().overworld().getGameTime();
        EN_COURS.values().removeIf(fin -> fin <= t);
    }

    private Zeus() {
    }
}
