package fr.cabane.musique;

import fr.cabane.Cabane;
import io.netty.buffer.ByteBuf;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/**
 * La musique du menu Échap (les OST du pack privé). « Pour moi » reste dans le jeu du joueur (ClientMusique) ; « autour
 * de moi » passe par ici : le serveur retient qui joue quel morceau depuis quand et le dit à tout le monde, et chaque jeu
 * ne le fait entendre qu'à moins de {@link #RAYON} blocs de celui qui le joue, au même moment du morceau (il le suit
 * quand il bouge, et celui qui s'approche en cours de route tombe au bon endroit). Les morceaux eux-mêmes sont dans le
 * pack « Beauvais - voix » : celui qui ne l'a pas n'entend rien.
 */
@EventBusSubscriber(modid = Cabane.MODID)
public final class Musique {
    /** On l'entend à fond jusqu'à {@link #PRES} blocs, puis de moins en moins jusqu'à {@link #RAYON}. */
    public static final int RAYON = 10;
    public static final int PRES = 4;
    /** Un morceau plus long (30 min) n'est pas un morceau. */
    private static final int DUREE_MAX = 20 * 60 * 30;

    private static final Map<UUID, Etat> EN_COURS = new HashMap<>();

    // ------------------------------------------------------------------ les paquets

    /** Jouer {@code id} (cabane:ost.{@code id}, {@code duree} ticks) autour de soi, à partir de {@code decalage} ticks. */
    public record Jouer(String id, int duree, int decalage, boolean boucle) implements CustomPacketPayload {
        public static final Type<Jouer> TYPE = new Type<>(Cabane.id("musique_jouer"));
        public static final StreamCodec<ByteBuf, Jouer> CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, Jouer::id, ByteBufCodecs.VAR_INT, Jouer::duree, ByteBufCodecs.VAR_INT, Jouer::decalage,
                ByteBufCodecs.BOOL, Jouer::boucle, Jouer::new);

        @Override
        public Type<Jouer> type() {
            return TYPE;
        }
    }

    /** Couper sa musique. */
    public record Arreter() implements CustomPacketPayload {
        public static final Type<Arreter> TYPE = new Type<>(Cabane.id("musique_arreter"));
        public static final StreamCodec<ByteBuf, Arreter> CODEC = StreamCodec.unit(new Arreter());

        @Override
        public Type<Arreter> type() {
            return TYPE;
        }
    }

    /** {@code source} joue {@code id} autour de lui depuis le tick {@code debut} du monde ({@code id} vide : plus rien). */
    public record Etat(UUID source, String id, long debut, int duree, boolean boucle) implements CustomPacketPayload {
        public static final Type<Etat> TYPE = new Type<>(Cabane.id("musique_etat"));
        public static final StreamCodec<ByteBuf, Etat> CODEC = StreamCodec.composite(
                UUIDUtil.STREAM_CODEC, Etat::source, ByteBufCodecs.STRING_UTF8, Etat::id, ByteBufCodecs.VAR_LONG, Etat::debut,
                ByteBufCodecs.VAR_INT, Etat::duree, ByteBufCodecs.BOOL, Etat::boucle, Etat::new);

        @Override
        public Type<Etat> type() {
            return TYPE;
        }

        boolean fini(long maintenant) {
            return !boucle && maintenant - debut >= duree;
        }
    }

    @EventBusSubscriber(modid = Cabane.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Reseau {
        @SubscribeEvent
        public static void register(RegisterPayloadHandlersEvent event) {
            event.registrar("1")
                    .playToServer(Jouer.TYPE, Jouer.CODEC, (p, ctx) -> ctx.enqueueWork(() -> {
                        if (ctx.player() instanceof ServerPlayer joueur) {
                            jouer(joueur, p);
                        }
                    }))
                    .playToServer(Arreter.TYPE, Arreter.CODEC, (p, ctx) -> ctx.enqueueWork(() -> {
                        if (ctx.player() instanceof ServerPlayer joueur) {
                            arreter(joueur);
                        }
                    }))
                    .playToClient(Etat.TYPE, Etat.CODEC, (p, ctx) -> ctx.enqueueWork(() -> fr.cabane.client.musique.ClientMusique.recevoir(p)));
        }
    }

    // ------------------------------------------------------------------ qui joue quoi

    /** L'horloge commune : les ticks du monde, que chaque jeu connaît aussi (et qui s'arrêtent quand le solo est en pause). */
    private static long maintenant(MinecraftServer server) {
        return server.overworld().getGameTime();
    }

    private static void jouer(ServerPlayer joueur, Jouer p) {
        if (p.id().isEmpty() || p.id().length() > 64 || p.duree() <= 0 || p.duree() > DUREE_MAX) {
            return;
        }
        long debut = maintenant(joueur.server) - Mth.clamp(p.decalage(), 0, p.duree() - 1);
        Etat etat = new Etat(joueur.getUUID(), p.id(), debut, p.duree(), p.boucle());
        EN_COURS.put(joueur.getUUID(), etat);
        PacketDistributor.sendToAllPlayers(etat);
    }

    private static void arreter(ServerPlayer joueur) {
        if (EN_COURS.remove(joueur.getUUID()) != null) {
            PacketDistributor.sendToAllPlayers(new Etat(joueur.getUUID(), "", 0, 0, false));
        }
    }

    /** Celui qui arrive entend tout de suite les musiques déjà lancées autour de lui. */
    @SubscribeEvent
    public static void connexion(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer joueur) {
            long t = maintenant(joueur.server);
            EN_COURS.values().removeIf(e -> e.fini(t));
            EN_COURS.values().forEach(e -> PacketDistributor.sendToPlayer(joueur, e));
        }
    }

    @SubscribeEvent
    public static void deconnexion(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer joueur) {
            arreter(joueur);
        }
    }

    @SubscribeEvent
    public static void arret(ServerStoppedEvent event) {
        EN_COURS.clear();
    }

    private Musique() {
    }
}
