package fr.cabane.costume;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import fr.cabane.Cabane;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.saveddata.SavedData;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/**
 * Les skins des méchants pour le RP : /skin whoop1…anbu3 (les boss des missions), /skin docteur, /skin jerry, /skin boss.
 * Le skin change pour tout le monde, et le pseudo aussi (au-dessus de la tête, dans le chat, dans la liste des joueurs).
 * /skin enlever : on redevient soi. Gardé après une déconnexion. L'hôte peut habiller les autres : /skin jerry @joueur.
 */
@EventBusSubscriber(modid = Cabane.MODID)
public final class Costumes {
    /** {@code fin} : les bras fins (le modèle « Alex »). */
    public record Costume(String id, String pseudo, int couleur, boolean fin) {
    }

    public static final Map<String, Costume> TOUS = new LinkedHashMap<>();

    private static void costume(String id, String pseudo, int couleur) {
        TOUS.put(id, new Costume(id, pseudo, couleur, false));
    }

    static {
        costume("whoop1", "La Patronne", 0xFF5AB4);
        costume("whoop2", "Le Styliste", 0xB46EFF);
        costume("whoop3", "Baron Magma", 0xFF7A1E);
        costume("anbu1", "Le Mizukage", 0x78AAFF);
        costume("anbu2", "Le Déserteur", 0x9DB46E);
        costume("anbu3", "Le Seigneur", 0xE04040);
        costume("docteur", "Dr Gero", 0xC8C8D2);
        costume("jerry", "Jerry", 0x5AD2C8);
        costume("boss", "???", 0xAA0000);
    }

    /** Ce que porte chaque joueur, tel que le serveur l'a dit à ce jeu-ci. */
    public static final Map<UUID, String> CLIENT = new ConcurrentHashMap<>();

    // ------------------------------------------------------------------ ce que le serveur retient

    static final class Donnees extends SavedData {
        final Map<UUID, String> portes = new HashMap<>();

        static Donnees get(MinecraftServer server) {
            return server.overworld().getDataStorage().computeIfAbsent(new SavedData.Factory<>(Donnees::new, Donnees::charger, null), "cabane_costumes");
        }

        private static Donnees charger(CompoundTag tag, HolderLookup.Provider registres) {
            Donnees d = new Donnees();
            for (String cle : tag.getAllKeys()) {
                d.portes.put(UUID.fromString(cle), tag.getString(cle));
            }
            return d;
        }

        @Override
        public CompoundTag save(CompoundTag tag, HolderLookup.Provider registres) {
            portes.forEach((u, id) -> tag.putString(u.toString(), id));
            return tag;
        }
    }

    // ------------------------------------------------------------------ le paquet : tous les costumes portés

    public record Sync(Map<UUID, String> portes) implements CustomPacketPayload {
        public static final Type<Sync> TYPE = new Type<>(Cabane.id("costumes"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Sync> CODEC = StreamCodec.composite(
                ByteBufCodecs.map(HashMap::new, UUIDUtil.STREAM_CODEC, ByteBufCodecs.STRING_UTF8), Sync::portes, Sync::new);

        @Override
        public Type<Sync> type() {
            return TYPE;
        }
    }

    public static void register(IEventBus modBus) {
        modBus.addListener((RegisterPayloadHandlersEvent e) -> e.registrar("1").playToClient(Sync.TYPE, Sync.CODEC,
                (p, ctx) -> ctx.enqueueWork(() -> fr.cabane.client.costume.ClientCostumes.recevoir(p.portes()))));
    }

    /** Le costume porté (null : aucun), côté serveur comme côté jeu. */
    public static Costume porte(Player p) {
        String id = p.level().isClientSide() ? CLIENT.get(p.getUUID())
                : p.getServer() == null ? null : Donnees.get(p.getServer()).portes.get(p.getUUID());
        return id == null ? null : TOUS.get(id);
    }

    private static void habiller(ServerPlayer j, Costume c) {
        Donnees d = Donnees.get(j.server);
        if (c == null) {
            d.portes.remove(j.getUUID());
        } else {
            d.portes.put(j.getUUID(), c.id());
        }
        d.setDirty();
        PacketDistributor.sendToAllPlayers(new Sync(Map.copyOf(d.portes)));
        j.refreshDisplayName();
        j.refreshTabListName();
        j.serverLevel().sendParticles(ParticleTypes.POOF, j.getX(), j.getY() + 1, j.getZ(), 25, 0.4, 0.8, 0.4, 0.04);
        j.serverLevel().playSound(null, j.getX(), j.getY(), j.getZ(), SoundEvents.ARMOR_EQUIP_LEATHER.value(), SoundSource.PLAYERS, 1F, 0.9F);
        j.displayClientMessage(c == null ? Component.literal("Tu redeviens toi.").withStyle(ChatFormatting.GRAY)
                : Component.literal("Tu es maintenant ").withStyle(ChatFormatting.GRAY).append(nom(c)), true);
    }

    private static Component nom(Costume c) {
        return Component.literal(c.pseudo()).withStyle(s -> s.withColor(c.couleur()).withBold(true));
    }

    private static boolean hote(CommandSourceStack source) {
        if (source.hasPermission(2)) {
            return true;
        }
        ServerPlayer j = source.getPlayer();
        return j != null && !source.getServer().isDedicatedServer() && source.getServer().isSingleplayerOwner(j.getGameProfile());
    }

    // ------------------------------------------------------------------ la commande

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        LiteralArgumentBuilder<CommandSourceStack> racine = Commands.literal("skin");
        for (Costume c : TOUS.values()) {
            racine.then(Commands.literal(c.id())
                    .executes(ctx -> {
                        habiller(ctx.getSource().getPlayerOrException(), c);
                        return 1;
                    })
                    .then(Commands.argument("joueurs", EntityArgument.players()).requires(Costumes::hote).executes(ctx -> {
                        Collection<ServerPlayer> js = EntityArgument.getPlayers(ctx, "joueurs");
                        js.forEach(j -> habiller(j, c));
                        ctx.getSource().sendSuccess(() -> Component.literal(js.size() + " joueur(s) en ").append(nom(c)), true);
                        return js.size();
                    })));
        }
        racine.then(Commands.literal("enlever")
                .executes(ctx -> {
                    habiller(ctx.getSource().getPlayerOrException(), null);
                    return 1;
                })
                .then(Commands.argument("joueurs", EntityArgument.players()).requires(Costumes::hote).executes(ctx -> {
                    Collection<ServerPlayer> js = EntityArgument.getPlayers(ctx, "joueurs");
                    js.forEach(j -> habiller(j, null));
                    return js.size();
                })));
        event.getDispatcher().register(racine);
    }

    // ------------------------------------------------------------------ le pseudo

    @SubscribeEvent
    public static void onNom(PlayerEvent.NameFormat event) {
        Costume c = porte(event.getEntity());
        if (c != null) {
            event.setDisplayname(nom(c));
        }
    }

    @SubscribeEvent
    public static void onNomListe(PlayerEvent.TabListNameFormat event) {
        Costume c = porte(event.getEntity());
        if (c != null) {
            event.setDisplayName(nom(c));
        }
    }

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer j) {
            PacketDistributor.sendToPlayer(j, new Sync(Map.copyOf(Donnees.get(j.server).portes)));
            j.refreshDisplayName();
            j.refreshTabListName();
        }
    }

    public static List<String> ids() {
        return List.copyOf(TOUS.keySet());
    }

    private Costumes() {
    }
}
