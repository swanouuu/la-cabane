package fr.cabane.cartel;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.serialization.Codec;
import fr.cabane.Cabane;
import fr.cabane.CabaneItems;
import fr.cabane.cartel.ArmeItem.Prise;
import fr.cabane.cartel.ArmeItem.Spec;
import fr.cabane.cartel.ArmeItem.Tir;
import io.netty.buffer.ByteBuf;
import java.util.Collection;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.core.BlockPos;
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
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.item.Tiers;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.living.MobDespawnEvent;
import net.neoforged.neoforge.event.entity.living.MobSpawnEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Le Cartel : une dimension à part (cabane:cartel), un désert de sable rouge sous un soleil de fin d'après-midi, et
 * au milieu le repaire construit par le mod (voir {@link CartelBuilder}) : la grande allée bordée de stands d'armes,
 * la hacienda d'El Patrón qui vend les armes, le stand de tir où les gars tirent pour de vrai, la favela sur la
 * colline. /cartel pour y aller, /cartel &lt;joueurs&gt; pour l'hôte qui y envoie quelqu'un, et le bouton rouge de
 * l'arrivée (ou /cartel retour) ramène pile là d'où l'on venait. On n'y casse rien : on y est en mode aventure.
 */
@EventBusSubscriber(modid = Cabane.MODID)
public final class Cartel {
    public static final ResourceKey<Level> DIMENSION = ResourceKey.create(Registries.DIMENSION, Cabane.id("cartel"));

    // ------------------------------------------------------------------ les sons
    private static final DeferredRegister<SoundEvent> SONS = DeferredRegister.create(Registries.SOUND_EVENT, Cabane.MODID);

    private static DeferredHolder<SoundEvent, SoundEvent> son(String nom) {
        return SONS.register("arme." + nom, () -> SoundEvent.createVariableRangeEvent(Cabane.id("arme." + nom)));
    }

    public static final DeferredHolder<SoundEvent, SoundEvent> SON_AK = son("ak47");
    public static final DeferredHolder<SoundEvent, SoundEvent> SON_AK_OR = son("ak47_or");
    public static final DeferredHolder<SoundEvent, SoundEvent> SON_UZI = son("uzi");
    public static final DeferredHolder<SoundEvent, SoundEvent> SON_MAC10 = son("mac10");
    public static final DeferredHolder<SoundEvent, SoundEvent> SON_THOMPSON = son("thompson");
    public static final DeferredHolder<SoundEvent, SoundEvent> SON_EAGLE = son("desert_eagle_or");
    public static final DeferredHolder<SoundEvent, SoundEvent> SON_CANON = son("canon_scie");
    public static final DeferredHolder<SoundEvent, SoundEvent> SON_BARRETT = son("barrett");
    public static final DeferredHolder<SoundEvent, SoundEvent> SON_M60 = son("m60");
    public static final DeferredHolder<SoundEvent, SoundEvent> SON_MINIGUN = son("minigun");
    public static final DeferredHolder<SoundEvent, SoundEvent> SON_RPG = son("rpg7");
    public static final DeferredHolder<SoundEvent, SoundEvent> SON_M79 = son("m79");
    public static final DeferredHolder<SoundEvent, SoundEvent> SON_EXPLOSION = son("explosion");
    public static final DeferredHolder<SoundEvent, SoundEvent> SON_LOINTAIN = son("lointain");

    // ------------------------------------------------------------------ les munitions dans l'arme
    private static final DeferredRegister.DataComponents COMPOSANTS = DeferredRegister.createDataComponents(Cabane.MODID);
    /** Les balles qui restent dans le chargeur (absent : chargeur plein). */
    public static final DeferredHolder<net.minecraft.core.component.DataComponentType<?>, net.minecraft.core.component.DataComponentType<Integer>> MUNITIONS =
            COMPOSANTS.registerComponentType("munitions", b -> b.persistent(Codec.INT).networkSynchronized(ByteBufCodecs.VAR_INT));

    // ------------------------------------------------------------------ les armes (dans l'onglet du mod)
    private static DeferredItem<ArmeItem> arme(String id, Spec spec) {
        return CabaneItems.ITEMS.register(id, () -> new ArmeItem(new Item.Properties().stacksTo(1), spec));
    }

    public static final DeferredItem<ArmeItem> AK47 = arme("ak47", Spec.balle(4, 3, 72, 30, 50, 1.6F, true, Prise.FUSIL, SON_AK));
    public static final DeferredItem<ArmeItem> AK47_OR = arme("ak47_or", Spec.balle(5, 3, 72, 30, 50, 1.2F, true, Prise.FUSIL, SON_AK_OR));
    public static final DeferredItem<ArmeItem> UZI = arme("uzi", Spec.balle(2.5F, 2, 40, 32, 40, 4, true, Prise.FUSIL, SON_UZI));
    public static final DeferredItem<ArmeItem> MAC10 = arme("mac10", Spec.balle(2.5F, 2, 40, 30, 40, 3.5F, true, Prise.MAIN, SON_MAC10));
    public static final DeferredItem<ArmeItem> THOMPSON = arme("thompson", Spec.balle(3, 3, 48, 50, 60, 3, true, Prise.FUSIL, SON_THOMPSON));
    public static final DeferredItem<ArmeItem> DESERT_EAGLE = arme("desert_eagle_or", Spec.balle(9, 10, 64, 7, 35, 0.8F, false, Prise.MAIN, SON_EAGLE));
    public static final DeferredItem<ArmeItem> CANON_SCIE = arme("canon_scie", Spec.balle(3, 14, 20, 2, 40, 5, false, Prise.FUSIL, SON_CANON).plombs(8));
    public static final DeferredItem<ArmeItem> BARRETT = arme("barrett", Spec.balle(22, 30, 160, 5, 70, 0.2F, false, Prise.FUSIL, SON_BARRETT).avecLunette());
    public static final DeferredItem<ArmeItem> M60 = arme("m60", Spec.balle(4, 3, 72, 100, 90, 3, true, Prise.FUSIL, SON_M60).lourde());
    public static final DeferredItem<ArmeItem> MINIGUN = arme("minigun", Spec.balle(3, 1, 56, 200, 120, 5, true, Prise.HANCHE, SON_MINIGUN).lourde());
    public static final DeferredItem<ArmeItem> RPG7 = arme("rpg7",
            new Spec(0, 40, 120, 1, 60, 0, 1, false, false, false, Tir.ROQUETTE, 3.2F, Prise.EPAULE, SON_RPG));
    public static final DeferredItem<ArmeItem> M79 = arme("m79",
            new Spec(0, 20, 60, 1, 40, 0, 1, false, false, false, Tir.OBUS, 2.4F, Prise.FUSIL, SON_M79));
    public static final DeferredItem<ArmeItem> GRENADE = CabaneItems.ITEMS.register("grenade", () -> new ArmeItem(new Item.Properties().stacksTo(16),
            new Spec(0, 15, 0, 0, 0, 0, 1, false, false, false, Tir.GRENADE, 3F, Prise.LANCER, SON_EXPLOSION)));
    public static final DeferredItem<ArmeItem> MOLOTOV = CabaneItems.ITEMS.register("molotov", () -> new ArmeItem(new Item.Properties().stacksTo(16),
            new Spec(0, 15, 0, 0, 0, 0, 1, false, false, false, Tir.MOLOTOV, 0, Prise.LANCER, SON_EXPLOSION)));
    /** La machette : une épée de fer un peu plus lourde. */
    public static final DeferredItem<SwordItem> MACHETTE = CabaneItems.ITEMS.register("machette",
            () -> new SwordItem(Tiers.IRON, new Item.Properties().attributes(SwordItem.createAttributes(Tiers.IRON, 4, -2.6F))));

    /** Ce qui vole (jamais dans l'inventaire) : la roquette et l'obus, dessinés en 3D pendant leur vol. */
    private static final DeferredRegister.Items CACHES = DeferredRegister.createItems(Cabane.MODID);
    public static final DeferredItem<Item> ROQUETTE = CACHES.registerSimpleItem("roquette");
    public static final DeferredItem<Item> OBUS = CACHES.registerSimpleItem("obus");

    // ------------------------------------------------------------------ les gars du cartel, les projectiles
    private static final DeferredRegister<EntityType<?>> ENTITES = DeferredRegister.create(Registries.ENTITY_TYPE, Cabane.MODID);
    public static final DeferredHolder<EntityType<?>, EntityType<CartelPnj>> PNJ = ENTITES.register("cartel_pnj",
            () -> EntityType.Builder.of(CartelPnj::new, MobCategory.MISC).sized(0.6F, 1.8F).clientTrackingRange(10).build("cartel_pnj"));
    public static final DeferredHolder<EntityType<?>, EntityType<ProjectileArme>> PROJECTILE = ENTITES.register("projectile_arme",
            () -> EntityType.Builder.<ProjectileArme>of(ProjectileArme::new, MobCategory.MISC).sized(0.3F, 0.3F).clientTrackingRange(8)
                    .updateInterval(1).build("projectile_arme"));

    // ------------------------------------------------------------------ le bouton du retour
    private static final DeferredRegister.Blocks BLOCS = DeferredRegister.createBlocks(Cabane.MODID);
    public static final DeferredBlock<BoutonCartel> BOUTON = BLOCS.registerBlock("bouton_cartel", BoutonCartel::new,
            BlockBehaviour.Properties.of().mapColor(MapColor.METAL).strength(-1F, 3_600_000F).sound(SoundType.METAL).noOcclusion()
                    .lightLevel(s -> 7).noLootTable());

    public static void register(IEventBus modBus) {
        CabaneItems.ITEMS.registerSimpleBlockItem(BOUTON);
        SONS.register(modBus);
        COMPOSANTS.register(modBus);
        CACHES.register(modBus);
        ENTITES.register(modBus);
        BLOCS.register(modBus);
        modBus.addListener((EntityAttributeCreationEvent e) -> e.put(PNJ.get(), CartelPnj.attributs().build()));
        modBus.addListener(Cartel::paquets);
    }

    // ------------------------------------------------------------------ les paquets

    /** R : recharger l'arme qu'on a en main. */
    public record Recharger() implements CustomPacketPayload {
        public static final Type<Recharger> TYPE = new Type<>(Cabane.id("cartel_recharger"));
        public static final StreamCodec<ByteBuf, Recharger> CODEC = StreamCodec.unit(new Recharger());

        @Override
        public Type<Recharger> type() {
            return TYPE;
        }
    }

    /** Une balle tirée : le client dessine la traçante, la flamme du canon et l'impact. */
    public record Tracante(Vec3 de, Vec3 a, int calibre, int impact) implements CustomPacketPayload {
        public static final Type<Tracante> TYPE = new Type<>(Cabane.id("cartel_tracante"));
        public static final StreamCodec<FriendlyByteBuf, Tracante> CODEC = StreamCodec.of((buf, p) -> {
            buf.writeVec3(p.de());
            buf.writeVec3(p.a());
            buf.writeByte(p.calibre());
            buf.writeByte(p.impact());
        }, buf -> new Tracante(buf.readVec3(), buf.readVec3(), buf.readByte(), buf.readByte()));

        @Override
        public Type<Tracante> type() {
            return TYPE;
        }
    }

    private static void paquets(RegisterPayloadHandlersEvent event) {
        event.registrar("1")
                .playToServer(Recharger.TYPE, Recharger.CODEC, (p, ctx) -> ctx.enqueueWork(() -> {
                    if (ctx.player() instanceof ServerPlayer joueur && joueur.getMainHandItem().getItem() instanceof ArmeItem arme) {
                        arme.recharger(joueur.serverLevel(), joueur, joueur.getMainHandItem());
                    }
                }))
                .playToClient(Tracante.TYPE, Tracante.CODEC, (p, ctx) -> ctx.enqueueWork(() -> fr.cabane.client.cartel.ClientCartel.tracante(p)));
    }

    // ------------------------------------------------------------------ entrer, sortir

    public static ServerLevel niveau(MinecraftServer server) {
        return server.getLevel(DIMENSION);
    }

    public static boolean dedans(ServerPlayer joueur) {
        return joueur.level().dimension() == DIMENSION;
    }

    /** Une nouvelle construction (les entités des constructions précédentes seront refusées). */
    static void construire(ServerLevel cartel) {
        CartelData data = CartelData.get(cartel.getServer());
        CartelBuilder.construire(cartel, data.nouvelleConstruction());
        data.setVersionConstruite(CartelBuilder.VERSION);
    }

    /** Emmène le joueur au cartel (en le construisant d'abord s'il le faut), en retenant d'où il part. */
    public static void entrer(ServerPlayer joueur) {
        MinecraftServer server = joueur.server;
        ServerLevel cartel = niveau(server);
        if (cartel == null) {
            joueur.sendSystemMessage(Component.literal("Le Cartel n'existe pas dans ce monde.").withStyle(ChatFormatting.RED));
            return;
        }
        CartelData data = CartelData.get(server);
        if (data.versionConstruite() != CartelBuilder.VERSION) {
            joueur.displayClientMessage(Component.literal("Le Cartel se prépare…").withStyle(ChatFormatting.GOLD), true);
            construire(cartel);
        }
        if (dedans(joueur)) {
            joueur.teleportTo(cartel, CartelBuilder.ARRIVEE.x, CartelBuilder.ARRIVEE.y, CartelBuilder.ARRIVEE.z, 180F, 0F);
            return;
        }
        if (joueur.isSleeping()) {
            joueur.stopSleepInBed(true, true);
        }
        ServerLevel depart = joueur.serverLevel();
        data.setRetour(joueur.getUUID(), new CartelData.Retour(depart.dimension().location(), joueur.getX(), joueur.getY(), joueur.getZ(),
                joueur.getYRot(), joueur.getXRot()));
        depart.playSound(null, joueur.getX(), joueur.getY(), joueur.getZ(), SoundEvents.ENDERMAN_TELEPORT, SoundSource.PLAYERS, 1F, 0.8F);
        depart.sendParticles(ParticleTypes.CAMPFIRE_COSY_SMOKE, joueur.getX(), joueur.getY() + 1, joueur.getZ(), 30, 0.4, 0.8, 0.4, 0.02);

        joueur.teleportTo(cartel, CartelBuilder.ARRIVEE.x, CartelBuilder.ARRIVEE.y, CartelBuilder.ARRIVEE.z, 180F, 0F);
        verifierMode(joueur);
        joueur.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, 20, 0, false, false));
        cartel.playSound(null, joueur.getX(), joueur.getY(), joueur.getZ(), SON_LOINTAIN.get(), SoundSource.AMBIENT, 1F, 1F);
        joueur.connection.send(new ClientboundSetTitlesAnimationPacket(10, 60, 25));
        joueur.connection.send(new ClientboundSetSubtitleTextPacket(Component.literal("Ici, pas de police. Les armes, c'est chez El Patrón.")
                .withStyle(ChatFormatting.YELLOW)));
        joueur.connection.send(new ClientboundSetTitleTextPacket(Component.literal("EL CARTEL").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD)));
        joueur.sendSystemMessage(Component.literal("Pour repartir : le bouton rouge, juste à côté de l'arrivée (ou /cartel retour). Il te ramène pile là où tu étais.")
                .withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC));
    }

    /** Ramène le joueur exactement là d'où il est venu (ou, à défaut, à son point de réapparition). */
    public static void sortir(ServerPlayer joueur) {
        if (!dedans(joueur)) {
            joueur.displayClientMessage(Component.literal("Tu n'es pas au Cartel.").withStyle(ChatFormatting.GRAY), true);
            return;
        }
        MinecraftServer server = joueur.server;
        CartelData data = CartelData.get(server);
        CartelData.Retour retour = data.retour(joueur.getUUID());
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
        joueur.displayClientMessage(Component.literal("Adiós, amigo.").withStyle(ChatFormatting.GOLD), true);
    }

    /**
     * Au cartel, un joueur en survie passe en aventure (on n'y casse rien, on n'y pose rien) ; dehors, il retrouve son
     * mode. Les joueurs en créatif ou spectateur ne sont pas touchés.
     */
    static void verifierMode(ServerPlayer joueur) {
        CartelData data = CartelData.get(joueur.server);
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
        dispatcher.register(Commands.literal("cartel")
                .executes(ctx -> {
                    entrer(ctx.getSource().getPlayerOrException());
                    return 1;
                })
                .then(Commands.literal("retour").executes(ctx -> {
                    sortir(ctx.getSource().getPlayerOrException());
                    return 1;
                }))
                .then(Commands.literal("reconstruire").requires(Cartel::hote).executes(ctx -> {
                    ServerLevel cartel = niveau(ctx.getSource().getServer());
                    if (cartel == null) {
                        ctx.getSource().sendFailure(Component.literal("La dimension du Cartel est introuvable."));
                        return 0;
                    }
                    construire(cartel);
                    ctx.getSource().sendSuccess(() -> Component.literal("Le Cartel est reconstruit."), true);
                    return 1;
                }))
                // pour l'hôte (et les tests) : toutes les armes d'un coup
                .then(Commands.literal("armes").requires(Cartel::hote).executes(ctx -> {
                    ServerPlayer joueur = ctx.getSource().getPlayerOrException();
                    for (var arme : List.of(AK47, AK47_OR, UZI, MAC10, THOMPSON, DESERT_EAGLE, CANON_SCIE, BARRETT, M60, MINIGUN, RPG7, M79)) {
                        joueur.getInventory().add(new ItemStack(arme.get()));
                    }
                    joueur.getInventory().add(new ItemStack(GRENADE.get(), 16));
                    joueur.getInventory().add(new ItemStack(MOLOTOV.get(), 16));
                    joueur.getInventory().add(new ItemStack(MACHETTE.get()));
                    return 1;
                }))
                // l'hôte y envoie quelqu'un (ou plusieurs) : /cartel @joueur
                .then(Commands.argument("joueurs", EntityArgument.players()).requires(Cartel::hote).executes(ctx -> {
                    Collection<ServerPlayer> joueurs = EntityArgument.getPlayers(ctx, "joueurs");
                    joueurs.forEach(Cartel::entrer);
                    ctx.getSource().sendSuccess(() -> Component.literal(joueurs.size() + " joueur(s) envoyé(s) au Cartel."), true);
                    return joueurs.size();
                })));
    }

    // ------------------------------------------------------------------ événements

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

    @SubscribeEvent
    public static void onTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        ProjectileArme.tickFeux(server);
        ServerLevel cartel = niveau(server);
        if (cartel == null || cartel.players().isEmpty()) {
            return;
        }
        for (ServerPlayer joueur : List.copyOf(cartel.players())) {
            // tombé sous le monde : retour à l'arrivée
            if (joueur.getY() < CartelBuilder.SOL - 40) {
                joueur.teleportTo(cartel, CartelBuilder.ARRIVEE.x, CartelBuilder.ARRIVEE.y, CartelBuilder.ARRIVEE.z, 180F, 0F);
                joueur.fallDistance = 0;
            }
            if (server.getTickCount() % 40 == 0) {
                verifierMode(joueur);
            }
        }
    }

    /** Les gars du cartel ne disparaissent jamais, même en paisible. */
    @SubscribeEvent
    public static void onDisparition(MobDespawnEvent event) {
        if (event.getEntity().getTags().contains(CartelBuilder.TAG)) {
            event.setResult(MobDespawnEvent.Result.DENY);
        }
    }

    /** Aucune créature n'apparaît d'elle-même au cartel. */
    @SubscribeEvent
    public static void onApparition(MobSpawnEvent.SpawnPlacementCheck event) {
        if (event.getLevel().getLevel().dimension() == DIMENSION
                && (event.getSpawnType() == MobSpawnType.NATURAL || event.getSpawnType() == MobSpawnType.CHUNK_GENERATION)) {
            event.setResult(MobSpawnEvent.SpawnPlacementCheck.Result.FAIL);
        }
    }

    /** Les restes d'une construction précédente (leur chunk n'était pas chargé quand on a tout effacé) sont refusés. */
    @SubscribeEvent
    public static void onArrivee(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide() || event.getLevel().dimension() != DIMENSION || event.getLevel().getServer() == null) {
            return;
        }
        var tags = event.getEntity().getTags();
        if (tags.contains(CartelBuilder.TAG) && !tags.contains(CartelBuilder.tagConstruction(CartelData.get(event.getLevel().getServer()).construction()))) {
            event.setCanceled(true);
        }
    }

    private Cartel() {
    }
}
