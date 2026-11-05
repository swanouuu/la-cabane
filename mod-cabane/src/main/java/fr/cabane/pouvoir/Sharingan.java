package fr.cabane.pouvoir;

import fr.cabane.Cabane;
import fr.cabane.client.pouvoir.ClientSharingan;
import io.netty.buffer.ByteBuf;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/**
 * Les yeux du Susano (demandés le 2026-11-04, docs/sharingan) : à chaque niveau, ses yeux changent. Niveau 1 : le
 * Sharingan ; niveau 2 : le Mangekyo ; niveau 3 (et le Susano parfait) : le Rinnegan à l'œil gauche, le Mangekyo à l'œil
 * droit. Le motif se dessine dans le pixel de la pupille de son skin ({@link ClientSharingan}).
 * <p>
 * La touche Éveil n'éveille que ses yeux ; une touche par technique : J le Tsukuyomi (dès le niveau 1), ² le Susano (un
 * niveau de plus à chaque appui, jusqu'au niveau des yeux, puis il se range), L le Kamui (dès le niveau 2), I la
 * météorite (niveau 3, une fois par éveil).
 * <p>
 * Lui ne voit pas l'animation de ses yeux : ce sont ceux qui le regardent au moment où il s'éveille (à moins de 24 blocs,
 * les yeux dans les siens) qui la voient en grand, sur leur écran noir. Voir {@link Genjutsu}, {@link Kamui}, {@link Meteorite}.
 */
@EventBusSubscriber(modid = Cabane.MODID)
public final class Sharingan {
    /** Ceux qui le regardent : à moins de tant de blocs, et presque droit dans les yeux. */
    private static final double PORTEE_REGARD = 24;
    private static final double DANS_LES_YEUX = 0.93;
    /** L'animation à l'écran : l'éveil d'un niveau, ou le regard du Tsukuyomi (le Mangekyo, puis le genjutsu). */
    public static final int EVEIL = 0;
    public static final int TSUKUYOMI = 1;

    /** Les yeux viennent avec le Susano, à tous ses niveaux. */
    public static boolean est(String pouvoir) {
        return pouvoir.startsWith("susano");
    }

    /** Les touches : J le Tsukuyomi, L le Kamui, I la météorite, ² le Susano. */
    public static final int J_TSUKUYOMI = 0;
    public static final int J_KAMUI = 1;
    public static final int J_METEORITE = 2;
    public static final int J_SUSANO = 3;

    /** Serveur → tous : le niveau des yeux de ce joueur (0 : endormis), le plus haut qu'il peut atteindre, et s'il vient de monter. */
    public record Yeux(UUID joueur, int niveau, int max, boolean annonce) implements CustomPacketPayload {
        public static final Type<Yeux> TYPE = new Type<>(Cabane.id("sharingan_yeux"));
        public static final StreamCodec<ByteBuf, Yeux> CODEC = StreamCodec.composite(
                UUIDUtil.STREAM_CODEC, Yeux::joueur, ByteBufCodecs.VAR_INT, Yeux::niveau, ByteBufCodecs.VAR_INT, Yeux::max, ByteBufCodecs.BOOL, Yeux::annonce, Yeux::new);

        @Override
        public Type<Yeux> type() {
            return TYPE;
        }
    }

    /** Ses yeux changent : tout le monde le sait (pour les dessiner) ; ceux qui le regardent dans les yeux voient l'éveil en grand. */
    static void envoyerYeux(ServerPlayer joueur, Pouvoirs.Donnees.Elu elu, int niveau, boolean annonce) {
        // d'abord l'éveil en grand chez ceux qui le regardent : leur son est calé dessus, ils n'entendent pas l'autre
        if (annonce && niveau > 0) {
            eveiller(joueur, niveau);
        }
        PacketDistributor.sendToAllPlayers(new Yeux(joueur.getUUID(), niveau, Pouvoirs.maxYeux(elu), annonce));
        bonus(joueur, niveau);
    }

    /**
     * Les yeux éveillés donnent un peu de force et de vitesse : moins que la marque maudite (+1,5/+3/+5 de force,
     * +20/+40 % de vitesse) et que les autres pouvoirs, mais ça se sent.
     */
    private static final double[] FORCE_YEUX = {0, 0.5, 1, 1.5};
    private static final double[] VITESSE_YEUX = {0, 0.05, 0.08, 0.1};
    private static final net.minecraft.resources.ResourceLocation FORCE = Cabane.id("sharingan_force");
    private static final net.minecraft.resources.ResourceLocation VITESSE = Cabane.id("sharingan_vitesse");

    static void bonus(ServerPlayer joueur, int niveau) {
        int n = Math.max(0, Math.min(3, niveau));
        modif(joueur, net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_DAMAGE, FORCE, FORCE_YEUX[n],
                net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation.ADD_VALUE);
        modif(joueur, net.minecraft.world.entity.ai.attributes.Attributes.MOVEMENT_SPEED, VITESSE, VITESSE_YEUX[n],
                net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation.ADD_MULTIPLIED_BASE);
    }

    private static void modif(ServerPlayer joueur, net.minecraft.core.Holder<net.minecraft.world.entity.ai.attributes.Attribute> attribut,
                              net.minecraft.resources.ResourceLocation id, double valeur, net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation op) {
        net.minecraft.world.entity.ai.attributes.AttributeInstance a = joueur.getAttribute(attribut);
        if (a == null) {
            return;
        }
        a.removeModifier(id);
        if (valeur != 0) {
            a.addTransientModifier(new net.minecraft.world.entity.ai.attributes.AttributeModifier(id, valeur, op));
        }
    }

    /** Client → serveur : une des touches du Sharingan, et ce qu'elle demande. */
    public record Touche(int action) implements CustomPacketPayload {
        public static final Type<Touche> TYPE = new Type<>(Cabane.id("sharingan_touche"));
        public static final StreamCodec<ByteBuf, Touche> CODEC = StreamCodec.composite(ByteBufCodecs.VAR_INT, Touche::action, Touche::new);

        @Override
        public Type<Touche> type() {
            return TYPE;
        }
    }

    /** Serveur → un joueur : les yeux de {@code lanceur} en grand sur ton écran ({@code eveil} : 1, 2 ou 3 ; {@code mode} : EVEIL ou TSUKUYOMI). */
    public record Regard(UUID lanceur, int eveil, int mode) implements CustomPacketPayload {
        public static final Type<Regard> TYPE = new Type<>(Cabane.id("sharingan_regard"));
        public static final StreamCodec<ByteBuf, Regard> CODEC = StreamCodec.composite(
                UUIDUtil.STREAM_CODEC, Regard::lanceur, ByteBufCodecs.VAR_INT, Regard::eveil, ByteBufCodecs.VAR_INT, Regard::mode, Regard::new);

        @Override
        public Type<Regard> type() {
            return TYPE;
        }
    }

    @SubscribeEvent
    public static void reseau(RegisterPayloadHandlersEvent event) {
        event.registrar("1")
                .playToServer(Touche.TYPE, Touche.CODEC, (p, ctx) -> ctx.enqueueWork(() -> {
                    if (ctx.player() instanceof ServerPlayer joueur) {
                        touche(joueur, p.action());
                    }
                }))
                .playToClient(Regard.TYPE, Regard.CODEC, (p, ctx) -> ctx.enqueueWork(() -> ClientSharingan.regard(p)))
                .playToClient(Yeux.TYPE, Yeux.CODEC, (p, ctx) -> ctx.enqueueWork(() -> ClientSharingan.yeux(p)));
    }

    /** Il vient de monter à ce niveau : ceux qui le regardent dans les yeux voient ses yeux changer, en grand. */
    static void eveiller(ServerPlayer joueur, int niveau) {
        if (niveau < 1 || niveau > 3) {
            return;
        }
        for (ServerPlayer autre : joueur.serverLevel().players()) {
            if (autre != joueur && regarde(autre, joueur)) {
                PacketDistributor.sendToPlayer(autre, new Regard(joueur.getUUID(), niveau, EVEIL));
            }
        }
    }

    /** {@code qui} regarde {@code lui} droit dans les yeux, de près, sans rien entre eux. */
    static boolean regarde(ServerPlayer qui, ServerPlayer lui) {
        if (qui.isSpectator() || !qui.isAlive()) {
            return false;
        }
        Vec3 vers = lui.getEyePosition().subtract(qui.getEyePosition());
        double d = vers.length();
        return d > 0.5 && d < PORTEE_REGARD && vers.normalize().dot(qui.getLookAngle()) > DANS_LES_YEUX && qui.hasLineOfSight(lui);
    }

    /** La touche J : le Tsukuyomi (niveau 1 et plus), le Kamui (niveau 2 et plus), la météorite (niveau 3, une fois par éveil). */
    private static void touche(ServerPlayer joueur, int action) {
        if (!est(Pouvoirs.pouvoir(joueur))) {
            return;
        }
        if (action == J_SUSANO) {
            if (!Kamui.cache(joueur.getUUID())) {
                Pouvoirs.susano(joueur);
            }
            return;
        }
        int niveau = Pouvoirs.yeux(joueur);
        // dans le Susano parfait, ses techniques sont celles du géant
        if (niveau < 1 || Pouvoirs.niveau(joueur) >= 4) {
            if (niveau < 1) {
                joueur.displayClientMessage(Component.literal("Éveille d'abord tes yeux.").withStyle(ChatFormatting.GRAY), true);
            }
            return;
        }
        // caché dans le Kamui : il ne peut que ressortir (la touche du Kamui)
        if (Kamui.cache(joueur.getUUID()) && action != J_KAMUI) {
            return;
        }
        if (action == J_METEORITE) {
            if (niveau < 3) {
                joueur.displayClientMessage(Component.literal("La météorite s'éveille avec le Rinnegan (niveau 3).").withStyle(ChatFormatting.GRAY), true);
                return;
            }
            if (Meteorite.prete(joueur)) {
                Meteorite.lancer(joueur);
            } else {
                joueur.displayClientMessage(Component.literal(Meteorite.pourquoiPasPrete(joueur)).withStyle(ChatFormatting.GRAY), true);
            }
            return;
        }
        if (action == J_KAMUI) {
            if (niveau >= 2) {
                Kamui.lancer(joueur);
            } else {
                joueur.displayClientMessage(Component.literal("Le Kamui s'éveille avec le Mangekyo (niveau 2).").withStyle(ChatFormatting.GRAY), true);
            }
            return;
        }
        Genjutsu.lancer(joueur);
    }

    /**
     * Pour l'hôte (OP), seul : {@code /sharingan yeux 1|2|3} montre l'animation des yeux sur son propre écran (d'habitude,
     * seuls ceux qui le regardent la voient) ; {@code /sharingan tsukuyomi} l'emmène seul dans le genjutsu, sur la croix.
     */
    @SubscribeEvent
    public static void commandes(net.neoforged.neoforge.event.RegisterCommandsEvent event) {
        event.getDispatcher().register(net.minecraft.commands.Commands.literal("sharingan").requires(s -> s.hasPermission(2))
                .then(net.minecraft.commands.Commands.literal("yeux").then(net.minecraft.commands.Commands.argument("eveil",
                        com.mojang.brigadier.arguments.IntegerArgumentType.integer(1, 3)).executes(c -> {
                            ServerPlayer moi = c.getSource().getPlayerOrException();
                            PacketDistributor.sendToPlayer(moi, new Regard(moi.getUUID(), com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(c, "eveil"), EVEIL));
                            return 1;
                        })))
                .then(net.minecraft.commands.Commands.literal("tsukuyomi").executes(c -> {
                    Genjutsu.tester(c.getSource().getPlayerOrException());
                    return 1;
                }))
                .then(net.minecraft.commands.Commands.literal("kamui").executes(c -> {
                    Kamui.lancer(c.getSource().getPlayerOrException());
                    return 1;
                })));
    }

    /** Son pouvoir s'endort : le Tsukuyomi qu'il tient se défait (la météorite, elle, va jusqu'au bout). */
    static void endormir(ServerPlayer joueur) {
        bonus(joueur, 0);
        Genjutsu.finirPour(joueur);
        Kamui.endormir(joueur);
        Meteorite.oublierEveil(joueur);
    }

    private Sharingan() {
    }
}
