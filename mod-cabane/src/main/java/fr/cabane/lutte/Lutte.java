package fr.cabane.lutte;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.serialization.Codec;
import fr.cabane.Cabane;
import fr.cabane.CabaneItems;
import fr.cabane.lutte.RingBlocs.Couleur;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Le catch : les rings qu'on pose avec le kit (n'importe où, de 5 × 5 à 40 × 40), les 5 tenues qui mettent torse nu et
 * gonflé, les 5 masques et leurs prises, et la salle comme à la télé (dimension cabane:catch, construite par
 * {@link SalleBuilder}) : /catch pour y aller (on arrive sur la scène), le bouton rouge ou /catch retour pour repartir
 * pile d'où l'on venait. Le combat lui-même (dégâts, KO, chrono, prises) est dans {@link Combat}.
 */
@EventBusSubscriber(modid = Cabane.MODID)
public final class Lutte {
    public static final ResourceKey<Level> DIMENSION = ResourceKey.create(Registries.DIMENSION, Cabane.id("catch"));
    public static final ResourceKey<DamageType> PRISE = ResourceKey.create(Registries.DAMAGE_TYPE, Cabane.id("prise_catch"));
    /** Ceux qui sont en pleine prise passent à travers les cordes (rempli par le serveur et par le client). */
    public static final java.util.Set<java.util.UUID> PASSE_CORDES = java.util.concurrent.ConcurrentHashMap.newKeySet();

    // ------------------------------------------------------------------ les sons
    private static final DeferredRegister<SoundEvent> SONS = DeferredRegister.create(Registries.SOUND_EVENT, Cabane.MODID);

    private static DeferredHolder<SoundEvent, SoundEvent> son(String nom) {
        return SONS.register("catch." + nom, () -> SoundEvent.createVariableRangeEvent(Cabane.id("catch." + nom)));
    }

    public static final DeferredHolder<SoundEvent, SoundEvent> SON_CLOCHE = son("cloche");
    public static final DeferredHolder<SoundEvent, SoundEvent> SON_CLOCHE3 = son("cloche3");
    public static final DeferredHolder<SoundEvent, SoundEvent> SON_IMPACT = son("impact");

    // ------------------------------------------------------------------ la couleur des cordes, dans le kit
    private static final DeferredRegister.DataComponents COMPOSANTS = DeferredRegister.createDataComponents(Cabane.MODID);
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<Integer>> KIT_COULEUR =
            COMPOSANTS.registerComponentType("kit_couleur", b -> b.persistent(Codec.INT).networkSynchronized(ByteBufCodecs.VAR_INT));

    // ------------------------------------------------------------------ les blocs
    private static final DeferredRegister.Blocks BLOCS = DeferredRegister.createBlocks(Cabane.MODID);
    private static BlockBehaviour.Properties incassable(MapColor couleur, SoundType son) {
        return BlockBehaviour.Properties.of().mapColor(couleur).strength(-1F, 3_600_000F).sound(son).noLootTable();
    }

    private static BlockBehaviour.Properties ring(MapColor couleur, SoundType son) {
        return BlockBehaviour.Properties.of().mapColor(couleur).strength(1.5F, 6F).sound(son).noLootTable();
    }

    public static final DeferredBlock<RingBlocs.Plancher> TAPIS = BLOCS.registerBlock("tapis_ring", RingBlocs.Plancher::new, ring(MapColor.SNOW, SoundType.WOOL));
    public static final DeferredBlock<RingBlocs.Plancher> TABLIER = BLOCS.registerBlock("tablier_ring", RingBlocs.Plancher::new, ring(MapColor.COLOR_BLACK, SoundType.WOOL));
    public static final DeferredBlock<RingBlocs.Corde> CORDE = BLOCS.registerBlock("corde_ring", RingBlocs.Corde::new,
            ring(MapColor.COLOR_RED, SoundType.WOOL).noOcclusion().dynamicShape());
    public static final DeferredBlock<RingBlocs.Poteau> POTEAU = BLOCS.registerBlock("poteau_ring", RingBlocs.Poteau::new,
            BlockBehaviour.Properties.of().mapColor(MapColor.METAL).strength(1.5F, 6F).sound(SoundType.METAL).noOcclusion().noLootTable().dynamicShape());
    public static final DeferredBlock<RingBlocs.Marches> MARCHES = BLOCS.registerBlock("marches_ring", RingBlocs.Marches::new,
            ring(MapColor.METAL, SoundType.METAL).noOcclusion());
    public static final DeferredBlock<RingBlocs.Cloche> CLOCHE = BLOCS.registerBlock("cloche_catch", RingBlocs.Cloche::new,
            BlockBehaviour.Properties.of().mapColor(MapColor.GOLD).strength(1F).sound(SoundType.METAL).noOcclusion());
    public static final DeferredBlock<RingBlocs.Public> PUBLIC = BLOCS.registerBlock("public_catch", RingBlocs.Public::new,
            BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_RED).strength(1F).sound(SoundType.WOOD).noOcclusion());
    public static final DeferredBlock<Block> LED = BLOCS.registerSimpleBlock("led_catch",
            BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_PURPLE).strength(1F).sound(SoundType.GLASS).lightLevel(s -> 10));
    public static final DeferredBlock<Block> PROJECTEUR = BLOCS.registerSimpleBlock("projecteur_catch",
            BlockBehaviour.Properties.of().mapColor(MapColor.SNOW).strength(1F).sound(SoundType.GLASS).lightLevel(s -> 15));
    public static final DeferredBlock<Block> ECRAN = BLOCS.registerSimpleBlock("ecran_catch",
            BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_PURPLE).strength(1F).sound(SoundType.GLASS).lightLevel(s -> 6));
    public static final DeferredBlock<RingBlocs.EcranLogo> ECRAN_LOGO = BLOCS.registerBlock("ecran_logo_catch", RingBlocs.EcranLogo::new,
            BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_PURPLE).strength(-1F, 3_600_000F).sound(SoundType.GLASS).lightLevel(s -> 6).noLootTable());
    public static final DeferredBlock<RingBlocs.Bouton> BOUTON = BLOCS.registerBlock("bouton_catch", RingBlocs.Bouton::new,
            incassable(MapColor.METAL, SoundType.METAL).noOcclusion().lightLevel(s -> 7));

    private static final DeferredRegister<BlockEntityType<?>> TYPES_BE = DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, Cabane.MODID);
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<RingBlocs.PublicBE>> BE_PUBLIC = TYPES_BE.register("public_catch",
            () -> BlockEntityType.Builder.of(RingBlocs.PublicBE::new, PUBLIC.get()).build(null));
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<RingBlocs.EcranBE>> BE_ECRAN = TYPES_BE.register("ecran_logo_catch",
            () -> BlockEntityType.Builder.of(RingBlocs.EcranBE::new, ECRAN_LOGO.get()).build(null));

    // ------------------------------------------------------------------ les objets (dans l'onglet du mod)
    public static final DeferredItem<KitRing> KIT = CabaneItems.ITEMS.register("kit_ring", () -> new KitRing(new Item.Properties().stacksTo(1)));
    public static final List<DeferredItem<Equipement.Tenue>> TENUES = new ArrayList<>();
    public static final List<DeferredItem<Equipement.Masque>> MASQUES = new ArrayList<>();

    static {
        for (int i = 0; i < Equipement.TENUES.length; i++) {
            int k = i;
            TENUES.add(CabaneItems.ITEMS.register("tenue_catch_" + Equipement.TENUES[i], () -> new Equipement.Tenue(k)));
        }
        for (int i = 0; i < Equipement.MASQUES.length; i++) {
            int k = i;
            MASQUES.add(CabaneItems.ITEMS.register("masque_catch_" + Equipement.MASQUES[i], () -> new Equipement.Masque(k)));
        }
    }

    public static void register(IEventBus modBus) {
        for (DeferredBlock<?> b : List.of(CLOCHE, PUBLIC, LED, PROJECTEUR, ECRAN)) {
            CabaneItems.ITEMS.registerSimpleBlockItem(b);
        }
        SONS.register(modBus);
        COMPOSANTS.register(modBus);
        BLOCS.register(modBus);
        TYPES_BE.register(modBus);
        modBus.addListener(Lutte::paquets);
    }

    /** La panoplie complète : le kit, les 5 tenues, les 5 masques. */
    public static List<ItemStack> panoplie() {
        List<ItemStack> l = new ArrayList<>();
        l.add(new ItemStack(KIT.get()));
        TENUES.forEach(t -> l.add(new ItemStack(t.get())));
        MASQUES.forEach(m -> l.add(new ItemStack(m.get())));
        return l;
    }

    // ------------------------------------------------------------------ les paquets

    /** Client → serveur : clic droit sur quelqu'un tout près, avec un masque : la prise. */
    public record DemandePrise(int cible) implements CustomPacketPayload {
        public static final Type<DemandePrise> TYPE = new Type<>(Cabane.id("catch_prise"));
        public static final StreamCodec<FriendlyByteBuf, DemandePrise> CODEC = StreamCodec.of((b, p) -> b.writeVarInt(p.cible()), b -> new DemandePrise(b.readVarInt()));

        @Override
        public Type<DemandePrise> type() {
            return TYPE;
        }
    }

    /** Client → serveur : accroupi + clic droit, avec un masque : la provoc. */
    public record DemandeProvoc() implements CustomPacketPayload {
        public static final Type<DemandeProvoc> TYPE = new Type<>(Cabane.id("catch_provoc"));
        public static final StreamCodec<FriendlyByteBuf, DemandeProvoc> CODEC = StreamCodec.unit(new DemandeProvoc());

        @Override
        public Type<DemandeProvoc> type() {
            return TYPE;
        }
    }

    /**
     * Serveur → clients : un geste commence (voir {@link Choregraphie}) : A et B (b = -1 s'il n'y a que A), le repère
     * (origine, lacet), la distance de B, et là où chacun était vraiment au départ (dans le repère).
     */
    public record Anim(int geste, int a, int b, Vec3 origine, float lacet, float dB, float aF, float aR, float bF, float bR) implements CustomPacketPayload {
        public static final Type<Anim> TYPE = new Type<>(Cabane.id("catch_anim"));
        public static final StreamCodec<FriendlyByteBuf, Anim> CODEC = StreamCodec.of((buf, p) -> {
            buf.writeVarInt(p.geste());
            buf.writeVarInt(p.a());
            buf.writeVarInt(p.b());
            buf.writeVec3(p.origine());
            buf.writeFloat(p.lacet());
            buf.writeFloat(p.dB());
            buf.writeFloat(p.aF());
            buf.writeFloat(p.aR());
            buf.writeFloat(p.bF());
            buf.writeFloat(p.bR());
        }, buf -> new Anim(buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVec3(), buf.readFloat(), buf.readFloat(), buf.readFloat(),
                buf.readFloat(), buf.readFloat(), buf.readFloat()));

        @Override
        public Type<Anim> type() {
            return TYPE;
        }
    }

    /**
     * Serveur → clients : où en est un match (le haut de l'écran des catcheurs et de ceux qui regardent) : les noms, les
     * vies, le chrono qui monte (en ticks), et à la fin le gagnant.
     */
    public record EtatMatch(String id, boolean fini, String gagnant, int ecoule, List<String> noms, List<Float> pv, List<Float> pvMax,
            Vec3 centre, boolean salle) implements CustomPacketPayload {
        public static final Type<EtatMatch> TYPE = new Type<>(Cabane.id("catch_match"));
        public static final StreamCodec<FriendlyByteBuf, EtatMatch> CODEC = StreamCodec.of((buf, p) -> {
            buf.writeUtf(p.id());
            buf.writeBoolean(p.fini());
            buf.writeUtf(p.gagnant());
            buf.writeVarInt(p.ecoule());
            buf.writeCollection(p.noms(), FriendlyByteBuf::writeUtf);
            buf.writeCollection(p.pv(), FriendlyByteBuf::writeFloat);
            buf.writeCollection(p.pvMax(), FriendlyByteBuf::writeFloat);
            buf.writeVec3(p.centre());
            buf.writeBoolean(p.salle());
        }, buf -> new EtatMatch(buf.readUtf(), buf.readBoolean(), buf.readUtf(), buf.readVarInt(), buf.readList(FriendlyByteBuf::readUtf),
                buf.readList(FriendlyByteBuf::readFloat), buf.readList(FriendlyByteBuf::readFloat), buf.readVec3(), buf.readBoolean()));

        @Override
        public Type<EtatMatch> type() {
            return TYPE;
        }
    }

    private static void paquets(RegisterPayloadHandlersEvent event) {
        event.registrar("1")
                .playToServer(DemandePrise.TYPE, DemandePrise.CODEC, (p, ctx) -> ctx.enqueueWork(() -> {
                    if (ctx.player() instanceof ServerPlayer j) {
                        Combat.demandePrise(j, p.cible());
                    }
                }))
                .playToServer(DemandeProvoc.TYPE, DemandeProvoc.CODEC, (p, ctx) -> ctx.enqueueWork(() -> {
                    if (ctx.player() instanceof ServerPlayer j) {
                        Combat.demandeProvoc(j);
                    }
                }))
                .playToClient(Anim.TYPE, Anim.CODEC, (p, ctx) -> ctx.enqueueWork(() -> fr.cabane.client.lutte.ClientLutte.anim(p)))
                .playToClient(EtatMatch.TYPE, EtatMatch.CODEC, (p, ctx) -> ctx.enqueueWork(() -> fr.cabane.client.lutte.ClientLutte.match(p)));
    }

    // ------------------------------------------------------------------ entrer, sortir de la salle

    public static ServerLevel niveau(MinecraftServer server) {
        return server.getLevel(DIMENSION);
    }

    public static boolean dedans(ServerPlayer joueur) {
        return joueur.level().dimension() == DIMENSION;
    }

    static void construire(ServerLevel salle) {
        SalleBuilder.construire(salle);
        LutteData.get(salle.getServer()).setVersionConstruite(SalleBuilder.VERSION);
    }

    /** Emmène le joueur à la salle (construite d'abord s'il le faut), sur la scène, en retenant d'où il part. */
    public static void entrer(ServerPlayer joueur) {
        MinecraftServer server = joueur.server;
        ServerLevel salle = niveau(server);
        if (salle == null) {
            joueur.sendSystemMessage(Component.literal("La salle de catch n'existe pas dans ce monde.").withStyle(ChatFormatting.RED));
            return;
        }
        LutteData data = LutteData.get(server);
        if (data.versionConstruite() != SalleBuilder.VERSION) {
            joueur.displayClientMessage(Component.literal("La salle se prépare…").withStyle(ChatFormatting.GOLD), true);
            construire(salle);
        }
        Vec3 a = SalleBuilder.ARRIVEE;
        if (dedans(joueur)) {
            joueur.teleportTo(salle, a.x, a.y, a.z, 0F, 0F);
            return;
        }
        if (joueur.isSleeping()) {
            joueur.stopSleepInBed(true, true);
        }
        ServerLevel depart = joueur.serverLevel();
        data.setRetour(joueur.getUUID(), new LutteData.Retour(depart.dimension().location(), joueur.getX(), joueur.getY(), joueur.getZ(),
                joueur.getYRot(), joueur.getXRot()));
        depart.playSound(null, joueur.getX(), joueur.getY(), joueur.getZ(), SoundEvents.ENDERMAN_TELEPORT, SoundSource.PLAYERS, 1F, 0.8F);
        joueur.teleportTo(salle, a.x, a.y, a.z, 0F, 0F);
        verifierMode(joueur);
        joueur.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, 20, 0, false, false));
        // l'entrée d'une star : les flammes de la scène
        SalleBuilder.pyro(salle);
        joueur.connection.send(new ClientboundSetTitlesAnimationPacket(10, 50, 20));
        joueur.connection.send(new ClientboundSetSubtitleTextPacket(Component.literal("Les tenues et les masques sont dans l'onglet du mod").withStyle(ChatFormatting.YELLOW)));
        joueur.connection.send(new ClientboundSetTitleTextPacket(Component.literal("LE CATCH").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD)));
        joueur.sendSystemMessage(Component.literal("Pour repartir : le bouton rouge au fond de la scène (ou /catch retour). Il te ramène pile là où tu étais.")
                .withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC));
    }

    /** Ramène le joueur exactement là d'où il est venu (ou, à défaut, à son point de réapparition). */
    public static void sortir(ServerPlayer joueur) {
        if (!dedans(joueur)) {
            joueur.displayClientMessage(Component.literal("Tu n'es pas à la salle de catch.").withStyle(ChatFormatting.GRAY), true);
            return;
        }
        MinecraftServer server = joueur.server;
        LutteData data = LutteData.get(server);
        LutteData.Retour retour = data.retour(joueur.getUUID());
        ServerLevel cible = retour == null ? null : server.getLevel(ResourceKey.create(Registries.DIMENSION, retour.dimension()));
        ServerLevel ici = joueur.serverLevel();
        ici.playSound(null, joueur.getX(), joueur.getY(), joueur.getZ(), SoundEvents.ENDERMAN_TELEPORT, SoundSource.PLAYERS, 1F, 1.2F);
        if (cible != null && cible != ici) {
            joueur.teleportTo(cible, retour.x(), retour.y(), retour.z(), retour.yaw(), retour.pitch());
        } else {
            ServerLevel monde = server.getLevel(joueur.getRespawnDimension());
            BlockPos p = joueur.getRespawnPosition();
            if (monde == null || p == null || monde == ici) {
                monde = server.overworld();
                p = monde.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, monde.getSharedSpawnPos());
            } else {
                p = p.above();
            }
            joueur.teleportTo(monde, p.getX() + 0.5, p.getY(), p.getZ() + 0.5, joueur.getYRot(), 0F);
        }
        data.setRetour(joueur.getUUID(), null);
        verifierMode(joueur);
        joueur.displayClientMessage(Component.literal("À la prochaine, champion.").withStyle(ChatFormatting.GOLD), true);
    }

    /** À la salle, un joueur en survie passe en aventure (on n'y casse rien) ; dehors, il retrouve son mode. */
    static void verifierMode(ServerPlayer joueur) {
        LutteData data = LutteData.get(joueur.server);
        GameType avant = data.mode(joueur.getUUID());
        if (dedans(joueur)) {
            if (joueur.gameMode.getGameModeForPlayer() == GameType.SURVIVAL) {
                data.setMode(joueur.getUUID(), GameType.SURVIVAL);
                joueur.setGameMode(GameType.ADVENTURE);
            }
        } else if (avant != null) {
            data.setMode(joueur.getUUID(), null);
            if (joueur.gameMode.getGameModeForPlayer() == GameType.ADVENTURE) {
                joueur.setGameMode(avant);
            }
        }
    }

    /** L'hôte : un admin du serveur, ou le joueur qui a ouvert le monde en solo. */
    static boolean hote(CommandSourceStack source) {
        if (source.hasPermission(2)) {
            return true;
        }
        ServerPlayer joueur = source.getPlayer();
        return joueur != null && !source.getServer().isDedicatedServer() && source.getServer().isSingleplayerOwner(joueur.getGameProfile());
    }

    // ------------------------------------------------------------------ commandes

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        dispatcher.register(Commands.literal("catch")
                .executes(ctx -> {
                    entrer(ctx.getSource().getPlayerOrException());
                    return 1;
                })
                .then(Commands.literal("retour").executes(ctx -> {
                    sortir(ctx.getSource().getPlayerOrException());
                    return 1;
                }))
                .then(Commands.literal("reconstruire").requires(Lutte::hote).executes(ctx -> {
                    ServerLevel salle = niveau(ctx.getSource().getServer());
                    if (salle == null) {
                        ctx.getSource().sendFailure(Component.literal("La dimension de la salle de catch est introuvable."));
                        return 0;
                    }
                    construire(salle);
                    ctx.getSource().sendSuccess(() -> Component.literal("La salle de catch est reconstruite."), true);
                    return 1;
                }))
                // l'hôte donne la panoplie (le kit, les 5 tenues, les 5 masques) : à lui-même ou à d'autres
                .then(Commands.literal("donner").requires(Lutte::hote)
                        .executes(ctx -> donner(List.of(ctx.getSource().getPlayerOrException())))
                        .then(Commands.argument("joueurs", EntityArgument.players()).executes(ctx -> donner(EntityArgument.getPlayers(ctx, "joueurs")))))
                // l'hôte pose un ring de cette taille là où il est (pour aller vite, ou pour tester)
                .then(Commands.literal("ring").requires(Lutte::hote)
                        .then(Commands.argument("taille", IntegerArgumentType.integer(Rings.MIN, Rings.MAX)).executes(ctx -> {
                            ServerPlayer j = ctx.getSource().getPlayerOrException();
                            int t = IntegerArgumentType.getInteger(ctx, "taille");
                            int x0 = j.getBlockX() - t / 2, z0 = j.getBlockZ() - t / 2, y0 = j.getBlockY();
                            Rings.construire(j.serverLevel(), x0, y0, z0, t, t, Couleur.ROUGE, false);
                            j.teleportTo(j.getX(), y0 + 2.1, j.getZ());
                            return 1;
                        })))
                // l'hôte y envoie quelqu'un (ou plusieurs) : /catch @joueur
                .then(Commands.argument("joueurs", EntityArgument.players()).requires(Lutte::hote).executes(ctx -> {
                    Collection<ServerPlayer> joueurs = EntityArgument.getPlayers(ctx, "joueurs");
                    joueurs.forEach(Lutte::entrer);
                    ctx.getSource().sendSuccess(() -> Component.literal(joueurs.size() + " joueur(s) envoyé(s) à la salle de catch."), true);
                    return joueurs.size();
                })));
    }

    private static int donner(Collection<ServerPlayer> joueurs) {
        for (ServerPlayer j : joueurs) {
            for (ItemStack s : panoplie()) {
                if (!j.getInventory().add(s)) {
                    j.drop(s, false);
                }
            }
        }
        return joueurs.size();
    }

    // ------------------------------------------------------------------ événements

    /** Le ring de la salle ne se casse pas (même en créatif) : seuls ceux posés avec un kit se rangent. */
    @SubscribeEvent
    public static void onCasse(net.neoforged.neoforge.event.level.BlockEvent.BreakEvent event) {
        if (event.getLevel() instanceof Level level && !level.isClientSide()) {
            LutteData.Ring r = Rings.duBloc(level, event.getPos());
            if (r != null && r.salle() && (event.getState().is(TAPIS.get()) || event.getState().is(TABLIER.get()) || event.getState().is(CORDE.get())
                    || event.getState().is(POTEAU.get()) || event.getState().is(MARCHES.get()))) {
                event.setCanceled(true);
            }
        }
    }

    @SubscribeEvent
    public static void onChangeDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer joueur) {
            verifierMode(joueur);
        }
    }

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer joueur) {
            verifierMode(joueur);
        }
    }

    @SubscribeEvent
    public static void onRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (event.getEntity() instanceof ServerPlayer joueur) {
            verifierMode(joueur);
        }
    }

    /** Tombé du monde à la salle : retour sur la scène. */
    static void tickSalle(MinecraftServer server) {
        ServerLevel salle = niveau(server);
        if (salle == null || salle.players().isEmpty()) {
            return;
        }
        for (ServerPlayer joueur : List.copyOf(salle.players())) {
            if (joueur.getY() < SalleBuilder.SOL - 30) {
                Vec3 a = SalleBuilder.ARRIVEE;
                joueur.teleportTo(salle, a.x, a.y, a.z, 0F, 0F);
                joueur.fallDistance = 0;
            }
            if (server.getTickCount() % 40 == 0) {
                verifierMode(joueur);
            }
        }
    }

    private Lutte() {
    }
}
