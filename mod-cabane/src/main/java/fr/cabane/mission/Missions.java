package fr.cabane.mission;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import fr.cabane.Cabane;
import fr.cabane.labo.Woohp;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
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
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.animal.Wolf;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.living.MobDespawnEvent;
import net.neoforged.neoforge.event.entity.living.MobSpawnEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Les missions d'infiltration (docs/missions) : /mission whoop 1|2|3 et /mission anbu 1|2|3. L'hôte y envoie les héros (les
 * espionnes partent comme avec /whoop : le son, l'aspiration, le toboggan, et elles atterrissent au départ de la mission ; les
 * ninjas disparaissent dans un nuage de fumée), et peut y envoyer les méchants (lui et son pote) directement dans la salle du
 * méchant : les gardes les laissent passer. Chaque scène est construite par le mod dans une dimension à part (le jour, la nuit),
 * remise à neuf à chaque lancement. Personne n'y meurt : à deux cœurs, un héros est attrapé et jeté en cellule ; ses potes
 * l'ouvrent d'un clic droit sur les barreaux. /mission retour ramène pile là d'où l'on venait, /mission fin ramène tout le monde.
 */
@EventBusSubscriber(modid = Cabane.MODID)
public final class Missions {
    public static final ResourceKey<Level> JOUR = ResourceKey.create(Registries.DIMENSION, Cabane.id("mission_jour"));
    public static final ResourceKey<Level> NUIT = ResourceKey.create(Registries.DIMENSION, Cabane.id("mission_nuit"));

    /** Où est posée chaque scène : sa dimension et son décalage en x (bien loin des autres). */
    private record Lieu(ResourceKey<Level> dimension, int ox) {
    }

    private static final Map<String, Lieu> LIEUX = Map.of(
            "whoop1", new Lieu(JOUR, 0), "whoop3", new Lieu(JOUR, 400), "anbu2", new Lieu(JOUR, 800),
            "whoop2", new Lieu(NUIT, 0), "anbu1", new Lieu(NUIT, 400), "anbu3", new Lieu(NUIT, 800));
    private static final Map<String, Session> SESSIONS = new LinkedHashMap<>();
    /** Les espionnes dans le toboggan du WOOHP (elles passent par le labo : elles ne quittent pas la mission pour autant). */
    private static final java.util.Set<UUID> EN_ROUTE = new java.util.HashSet<>();

    // ------------------------------------------------------------------ les gens, ce qu'ils tiennent, les objectifs
    private static final DeferredRegister<EntityType<?>> ENTITES = DeferredRegister.create(Registries.ENTITY_TYPE, Cabane.MODID);
    public static final DeferredHolder<EntityType<?>, EntityType<MissionGarde>> GARDE = ENTITES.register("mission_garde",
            () -> EntityType.Builder.of(MissionGarde::new, MobCategory.MISC).sized(0.6F, 1.8F).clientTrackingRange(10).build("mission_garde"));
    public static final DeferredHolder<EntityType<?>, EntityType<MissionChien>> CHIEN = ENTITES.register("mission_chien",
            () -> EntityType.Builder.<MissionChien>of(MissionChien::new, MobCategory.MISC).sized(0.6F, 0.85F).clientTrackingRange(10).build("mission_chien"));

    /** Pas dans l'onglet du mod : ce que tiennent les gardes, et les objectifs (on ne les trouve que dans les missions). */
    private static final DeferredRegister.Items OBJETS = DeferredRegister.createItems(Cabane.MODID);
    private static final Map<String, DeferredItem<Item>> ITEMS = new HashMap<>();

    static {
        for (String id : List.of("katana", "kunai", "lance", "arc", "matraque", "pistolet_laser", "fusil_laser", "lampe_torche",
                "formule", "puce", "rouleau", "pacte")) {
            ITEMS.put("mission_" + id, OBJETS.registerSimpleItem("mission_" + id, new Item.Properties().stacksTo(1)));
        }
    }

    /** Les coups des gardes (et du chien, des flèches, des lasers) : pas réduits par la difficulté. */
    private static final ResourceKey<net.minecraft.world.damagesource.DamageType> COUP = ResourceKey.create(Registries.DAMAGE_TYPE, Cabane.id("garde"));

    static net.minecraft.world.damagesource.DamageSource coup(Entity qui) {
        return new net.minecraft.world.damagesource.DamageSource(qui.level().registryAccess().registryOrThrow(Registries.DAMAGE_TYPE).getHolderOrThrow(COUP), qui);
    }

    /** Les flèches des archers et des pièges : marquées, pour que leurs dégâts ne dépendent pas de la difficulté. */
    static final String FLECHE = "mission.fleche";

    static ItemStack objetTenu(String arme) {
        DeferredItem<Item> i = ITEMS.get("mission_" + arme);
        return i == null ? ItemStack.EMPTY : new ItemStack(i.get());
    }

    static Item objectif(String item) {
        return ITEMS.get(item).get();
    }

    // ------------------------------------------------------------------ le panneau de la mission (en haut à droite)

    /** Le titre de la mission et ses lignes (vide : on n'est plus en mission) ; {@code brume} : le brouillard du village. */
    public record Hud(String titre, List<String> lignes, boolean brume) implements CustomPacketPayload {
        public static final Type<Hud> TYPE = new Type<>(Cabane.id("mission_hud"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Hud> CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, Hud::titre, ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()), Hud::lignes,
                ByteBufCodecs.BOOL, Hud::brume, Hud::new);

        @Override
        public Type<Hud> type() {
            return TYPE;
        }
    }

    public static void register(IEventBus modBus) {
        ENTITES.register(modBus);
        OBJETS.register(modBus);
        modBus.addListener((EntityAttributeCreationEvent e) -> {
            e.put(GARDE.get(), MissionGarde.attributs().build());
            e.put(CHIEN.get(), Wolf.createAttributes().build());
        });
        modBus.addListener((RegisterPayloadHandlersEvent e) -> e.registrar("1").playToClient(Hud.TYPE, Hud.CODEC,
                (p, ctx) -> ctx.enqueueWork(() -> fr.cabane.client.mission.ClientMissions.hud(p))));
    }

    // ================================================================== les sessions

    static Session session(String id) {
        return SESSIONS.get(id);
    }

    public static boolean dedans(Entity e) {
        return e.level().dimension() == JOUR || e.level().dimension() == NUIT;
    }

    /** La mission où joue ce joueur (héros ou méchant), ou null. */
    private static Session sessionDe(ServerPlayer j) {
        for (Session s : SESSIONS.values()) {
            if (s.level == j.level() && (s.heros.contains(j.getUUID()) || s.mechants.contains(j.getUUID()))) {
                return s;
            }
        }
        return null;
    }

    /** La mission dont la scène contient ce point. */
    private static Session sessionIci(Level level, Vec3 p) {
        for (Session s : SESSIONS.values()) {
            if (s.level == level && s.scene.etendue().contains(p)) {
                return s;
            }
        }
        return null;
    }

    /** Construit la scène à neuf (blocs, gens, objectifs) et ouvre une nouvelle session ; les méchants de l'ancienne y restent. */
    private static Session lancer(MinecraftServer server, String id, Session ancienne) {
        Lieu l = LIEUX.get(id);
        ServerLevel level = server.getLevel(l.dimension());
        if (level == null) {
            return null;
        }
        Scene scene = Scene.charger(server, id, l.ox(), 0);
        int numero = MissionData.get(server).nouvelleConstruction(id);
        long t0 = System.currentTimeMillis();
        scene.construire(level, numero);
        Cabane.LOGGER.info("Mission {} construite en {} ms", id, System.currentTimeMillis() - t0);
        Session s = new Session(scene, level, numero);
        s.peupler();
        SESSIONS.put(id, s);
        if (ancienne != null) {
            for (UUID u : ancienne.mechants) {
                ServerPlayer j = server.getPlayerList().getPlayer(u);
                if (j != null && j.level() == level) {
                    s.mechants.add(u);
                    poserMechant(j, s, s.mechants.size() - 1);
                }
            }
            // les héros d'avant encore dans la scène repartent du départ
            for (UUID u : ancienne.heros) {
                ServerPlayer j = server.getPlayerList().getPlayer(u);
                if (j != null && j.level() == level && scene.etendue().contains(j.position())) {
                    s.heros.add(u);
                    purger(j);
                    arriver(j, s, s.heros.size() - 1);
                }
            }
        }
        return s;
    }

    // ================================================================== les départs, les arrivées

    private static int heros(CommandSourceStack source, String serie, int numero, Collection<ServerPlayer> joueurs) {
        String id = serie + numero;
        MinecraftServer server = source.getServer();
        Session s = SESSIONS.get(id);
        if (s == null || !s.heros.isEmpty() || s.finie()) {
            source.sendSuccess(() -> Component.literal("La mission se prépare…").withStyle(ChatFormatting.GRAY), false);
            s = lancer(server, id, s);
        }
        if (s == null) {
            source.sendFailure(Component.literal("La dimension des missions est introuvable."));
            return 0;
        }
        final Session session = s;
        int i = 0;
        for (ServerPlayer j : joueurs) {
            quitterLesAutres(j, session);
            session.mechants.remove(j.getUUID());
            session.captures.remove(j.getUUID());
            retenir(j);
            purger(j);
            session.heros.add(j.getUUID());
            final int rang = i++;
            EN_ROUTE.add(j.getUUID());
            if (serie.equals("whoop") && Woohp.aspirer(j, rang, x -> {
                EN_ROUTE.remove(x.getUUID());
                Session cur = SESSIONS.get(id);
                if (cur != null && cur.heros.contains(x.getUUID())) {
                    arriver(x, cur, rang);
                } else {
                    revenir(x);
                }
            })) {
                continue;
            }
            EN_ROUTE.remove(j.getUUID());
            fumee(j.serverLevel(), j.position());
            arriver(j, session, rang);
        }
        int n = joueurs.size();
        source.sendSuccess(() -> Component.literal(n + " héros envoyé(s) en mission (" + session.scene.titre + ")."), true);
        return n;
    }

    private static int mechants(CommandSourceStack source, String serie, int numero, Collection<ServerPlayer> joueurs) {
        String id = serie + numero;
        Session s = SESSIONS.get(id);
        if (s == null || s.finie()) {
            s = lancer(source.getServer(), id, s);
        }
        if (s == null) {
            source.sendFailure(Component.literal("La dimension des missions est introuvable."));
            return 0;
        }
        for (ServerPlayer j : joueurs) {
            quitterLesAutres(j, s);
            s.heros.remove(j.getUUID());
            s.captures.remove(j.getUUID());
            retenir(j);
            purger(j);
            s.mechants.add(j.getUUID());
            fumee(j.serverLevel(), j.position());
            poserMechant(j, s, s.mechants.size() - 1);
        }
        Session session = s;
        source.sendSuccess(() -> Component.literal(joueurs.size() + " méchant(s) dans " + session.scene.titre + "."), true);
        return joueurs.size();
    }

    /** On ne joue que dans une mission à la fois. */
    private static void quitterLesAutres(ServerPlayer j, Session garder) {
        for (Session s : SESSIONS.values()) {
            if (s != garder) {
                s.reprendreObjets(j);
                s.quitter(j.getUUID());
            }
        }
    }

    /** Au départ de la mission (les héros côte à côte). */
    private static void arriver(ServerPlayer j, Session s, int rang) {
        Vec3 cote = Vec3.directionFromRotation(0, s.scene.departLacet + 90F);
        double dec = rang % 3 == 0 ? 0 : rang % 3 == 1 ? 1.2 : -1.2;
        Vec3 p = s.scene.depart.add(cote.scale(dec));
        if (!s.level.noCollision(EntityType.PLAYER.getDimensions().makeBoundingBox(p))) {
            p = s.scene.depart;
        }
        j.stopRiding();
        j.teleportTo(s.level, p.x, p.y, p.z, s.scene.departLacet, 0F);
        j.fallDistance = 0;
        j.setHealth(j.getMaxHealth());
        verifierMode(j);
        fumee(s.level, p);
        boolean whoop = s.scene.id.startsWith("whoop");
        j.connection.send(new ClientboundSetTitlesAnimationPacket(10, 70, 25));
        j.connection.send(new ClientboundSetSubtitleTextPacket(Component.literal(s.scene.titre).withStyle(ChatFormatting.YELLOW)));
        j.connection.send(new ClientboundSetTitleTextPacket(Component.literal((whoop ? "MISSION WOOHP " : "MISSION ANBU ") + s.scene.id.charAt(s.scene.id.length() - 1))
                .withStyle(whoop ? ChatFormatting.LIGHT_PURPLE : ChatFormatting.DARK_RED, ChatFormatting.BOLD)));
        j.sendSystemMessage(Component.literal("Ne te fais pas voir : les gardes foncent dès qu'ils te voient. Un coup dans le dos les met K.O. "
                + "À deux cœurs, c'est la cellule. /mission retour pour rentrer.").withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC));
    }

    private static void poserMechant(ServerPlayer j, Session s, int rang) {
        Vec3 cote = Vec3.directionFromRotation(0, s.scene.mechantLacet + 90F);
        Vec3 p = s.scene.mechant.add(cote.scale(rang % 2 == 0 ? 0 : 1.3));
        if (!s.level.noCollision(EntityType.PLAYER.getDimensions().makeBoundingBox(p))) {
            p = s.scene.mechant;
        }
        j.stopRiding();
        j.teleportTo(s.level, p.x, p.y, p.z, s.scene.mechantLacet, 0F);
        j.fallDistance = 0;
        verifierMode(j);
        fumee(s.level, p);
        j.connection.send(new ClientboundSetTitlesAnimationPacket(10, 70, 25));
        j.connection.send(new ClientboundSetSubtitleTextPacket(Component.literal("Les gardes te laissent passer. À toi le RP !").withStyle(ChatFormatting.YELLOW)));
        j.connection.send(new ClientboundSetTitleTextPacket(Component.literal("TU ES LE MÉCHANT").withStyle(ChatFormatting.DARK_RED, ChatFormatting.BOLD)));
        j.sendSystemMessage(Component.literal("/mission alerte : tous les gardes en chasse. /mission liberer <joueur> : ouvrir la cellule. "
                + "/mission retour pour rentrer.").withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC));
    }

    /** Le nuage de fumée des ninjas (et de toute arrivée). */
    static void fumee(ServerLevel l, Vec3 p) {
        l.sendParticles(ParticleTypes.CAMPFIRE_COSY_SMOKE, p.x, p.y + 1, p.z, 20, 0.4, 0.7, 0.4, 0.02);
        l.sendParticles(ParticleTypes.POOF, p.x, p.y + 1, p.z, 30, 0.5, 0.8, 0.5, 0.06);
        l.playSound(null, p.x, p.y, p.z, SoundEvents.FIRE_EXTINGUISH, SoundSource.PLAYERS, 1F, 1.5F);
    }

    // ================================================================== retenir d'où l'on vient, rentrer

    private static void retenir(ServerPlayer j) {
        if (!dedans(j)) {
            MissionData.get(j.server).setRetour(j.getUUID(), new MissionData.Retour(j.level().dimension().location(), j.getX(), j.getY(), j.getZ(),
                    j.getYRot(), j.getXRot()));
        }
    }

    /** Les objectifs ne quittent pas les missions. */
    static void purger(ServerPlayer j) {
        for (int i = 0; i < j.getInventory().getContainerSize(); i++) {
            CustomData c = j.getInventory().getItem(i).get(DataComponents.CUSTOM_DATA);
            if (c != null && c.contains(Session.CLE)) {
                j.getInventory().setItem(i, ItemStack.EMPTY);
            }
        }
    }

    /** Ramène le joueur exactement là d'où il est venu (ou, à défaut, à son point de réapparition). */
    static void revenir(ServerPlayer j) {
        for (Session s : SESSIONS.values()) {
            s.reprendreObjets(j);
            s.quitter(j.getUUID());
        }
        purger(j);
        PacketDistributor.sendToPlayer(j, new Hud("", List.of(), false));
        MinecraftServer server = j.server;
        MissionData data = MissionData.get(server);
        MissionData.Retour r = data.retour(j.getUUID());
        ServerLevel cible = r == null ? null : server.getLevel(ResourceKey.create(Registries.DIMENSION, r.dimension()));
        if (cible != null && cible.dimension() != JOUR && cible.dimension() != NUIT) {
            j.teleportTo(cible, r.x(), r.y(), r.z(), r.yaw(), r.pitch());
        } else {
            ServerLevel monde = server.getLevel(j.getRespawnDimension());
            BlockPos p = j.getRespawnPosition();
            if (monde == null || p == null || monde.dimension() == JOUR || monde.dimension() == NUIT) {
                monde = server.overworld();
                p = monde.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, monde.getSharedSpawnPos());
            } else {
                p = p.above();
            }
            j.teleportTo(monde, p.getX() + 0.5, p.getY(), p.getZ() + 0.5, j.getYRot(), 0F);
        }
        data.setRetour(j.getUUID(), null);
        j.fallDistance = 0;
        j.removeEffect(MobEffects.MOVEMENT_SLOWDOWN);
        j.removeEffect(MobEffects.WEAKNESS);
        verifierMode(j);
    }

    /** En mission, un joueur en survie passe en aventure (on n'y casse rien) ; dehors, il retrouve son mode. */
    static void verifierMode(ServerPlayer j) {
        MissionData data = MissionData.get(j.server);
        GameType avant = data.mode(j.getUUID());
        if (dedans(j)) {
            if (j.gameMode.getGameModeForPlayer() == GameType.SURVIVAL) {
                data.setMode(j.getUUID(), GameType.SURVIVAL);
                j.setGameMode(GameType.ADVENTURE);
            }
        } else if (avant != null) {
            data.setMode(j.getUUID(), null);
            if (j.gameMode.getGameModeForPlayer() == GameType.ADVENTURE) {
                j.setGameMode(avant);
            }
        }
    }

    static boolean hote(CommandSourceStack source) {
        if (source.hasPermission(2)) {
            return true;
        }
        ServerPlayer j = source.getPlayer();
        return j != null && !source.getServer().isDedicatedServer() && source.getServer().isSingleplayerOwner(j.getGameProfile());
    }

    // ================================================================== voir, être vu

    /** Caché : sous l'eau, ou accroupi dans les hautes herbes, les roseaux, les feuilles. */
    static boolean cache(ServerLevel level, ServerPlayer h) {
        if (h.isEyeInFluid(FluidTags.WATER)) {
            return true;
        }
        if (!h.isCrouching()) {
            return false;
        }
        return feuillage(level.getBlockState(BlockPos.containing(h.getEyePosition()))) || feuillage(level.getBlockState(h.blockPosition()));
    }

    private static boolean feuillage(BlockState s) {
        return s.is(Blocks.TALL_GRASS) || s.is(Blocks.SHORT_GRASS) || s.is(Blocks.LARGE_FERN) || s.is(Blocks.FERN) || s.is(BlockTags.LEAVES)
                || s.is(Blocks.SUGAR_CANE) || s.is(Blocks.BAMBOO);
    }

    /** Rien entre {@code de} et la tête (ou le ventre) du joueur : le verre et les barreaux laissent voir, pas les murs ni les feuilles. */
    static boolean voit(Level level, Vec3 de, ServerPlayer h, Entity qui) {
        for (Vec3 a : new Vec3[] {h.getEyePosition(), h.position().add(0, h.getBbHeight() * 0.5, 0)}) {
            ClipContext c = new ClipContext(de, a, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, CollisionContext.empty());
            if (level.clip(c).getType() == HitResult.Type.MISS) {
                return true;
            }
        }
        return false;
    }

    /** Le joueur regarde-t-il cette entité (devant lui, rien entre eux) ? */
    static boolean regarde(Level level, ServerPlayer h, Entity e) {
        Vec3 yeux = h.getEyePosition(), c = e.position().add(0, e.getBbHeight() * 0.6, 0), d = c.subtract(yeux);
        double l = d.length();
        if (l > 40 || l < 0.01 || h.hasEffect(MobEffects.BLINDNESS)) {
            return false;
        }
        if (h.getViewVector(1F).dot(d.scale(1 / l)) < 0.6) {
            return false;
        }
        ClipContext ctx = new ClipContext(yeux, c, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, CollisionContext.empty());
        return level.clip(ctx).getType() == HitResult.Type.MISS;
    }

    // ================================================================== la commande

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        LiteralArgumentBuilder<CommandSourceStack> racine = Commands.literal("mission")
                .then(Commands.literal("retour").executes(ctx -> {
                    ServerPlayer j = ctx.getSource().getPlayerOrException();
                    if (!dedans(j)) {
                        ctx.getSource().sendFailure(Component.literal("Tu n'es pas en mission."));
                        return 0;
                    }
                    revenir(j);
                    return 1;
                }))
                .then(Commands.literal("alerte").executes(ctx -> alerte(ctx.getSource())))
                .then(Commands.literal("liberer").then(Commands.argument("joueurs", EntityArgument.players()).executes(ctx -> {
                    int n = 0;
                    for (ServerPlayer j : EntityArgument.getPlayers(ctx, "joueurs")) {
                        for (Session s : SESSIONS.values()) {
                            if (s.captures.contains(j.getUUID()) && (hote(ctx.getSource()) || estMechant(ctx.getSource(), s))) {
                                s.liberer(null);
                                n++;
                            }
                        }
                    }
                    int libres = n;
                    ctx.getSource().sendSuccess(() -> Component.literal(libres == 0 ? "Personne à libérer." : "Cellule ouverte."), false);
                    return n;
                })))
                .then(Commands.literal("fin").requires(Missions::hote).executes(ctx -> {
                    int n = 0;
                    for (ServerPlayer j : List.copyOf(ctx.getSource().getServer().getPlayerList().getPlayers())) {
                        if (dedans(j)) {
                            revenir(j);
                            j.displayClientMessage(Component.literal("Fin de la mission.").withStyle(ChatFormatting.GOLD), true);
                            n++;
                        }
                    }
                    SESSIONS.clear();
                    int rentres = n;
                    ctx.getSource().sendSuccess(() -> Component.literal("Fin des missions : " + rentres + " joueur(s) rentré(s)."), true);
                    return n;
                }));
        for (String serie : List.of("whoop", "anbu")) {
            racine.then(Commands.literal(serie).requires(Missions::hote)
                    .then(Commands.argument("numero", IntegerArgumentType.integer(1, 3))
                            .executes(ctx -> heros(ctx.getSource(), serie, IntegerArgumentType.getInteger(ctx, "numero"),
                                    List.of(ctx.getSource().getPlayerOrException())))
                            .then(Commands.literal("mechant").then(Commands.argument("mechants", EntityArgument.players())
                                    .executes(ctx -> mechants(ctx.getSource(), serie, IntegerArgumentType.getInteger(ctx, "numero"),
                                            EntityArgument.getPlayers(ctx, "mechants")))))
                            .then(Commands.argument("joueurs", EntityArgument.players())
                                    .executes(ctx -> heros(ctx.getSource(), serie, IntegerArgumentType.getInteger(ctx, "numero"),
                                            EntityArgument.getPlayers(ctx, "joueurs"))))));
        }
        event.getDispatcher().register(racine);
    }

    private static boolean estMechant(CommandSourceStack source, Session s) {
        ServerPlayer j = source.getPlayer();
        return j != null && s.mechants.contains(j.getUUID());
    }

    /** Tous les gardes en chasse, chacun après le héros le plus proche. */
    private static int alerte(CommandSourceStack source) {
        int n = 0;
        for (Session s : SESSIONS.values()) {
            if (!hote(source) && !estMechant(source, s)) {
                continue;
            }
            s.poursuite();
            n++;
        }
        if (n == 0) {
            source.sendFailure(Component.literal("Aucune mission à alerter (il faut être l'hôte, ou le méchant de la mission)."));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("Tous les gardes sont en alerte !").withStyle(ChatFormatting.RED), false);
        return n;
    }

    // ================================================================== les événements

    @SubscribeEvent
    public static void onTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        for (Session s : List.copyOf(SESSIONS.values())) {
            if (!s.level.players().isEmpty()) {
                s.tick();
            }
        }
        for (ServerLevel level : new ServerLevel[] {server.getLevel(JOUR), server.getLevel(NUIT)}) {
            if (level == null || level.players().isEmpty()) {
                continue;
            }
            for (ServerPlayer j : List.copyOf(level.players())) {
                // tombé dans le vide : retour au départ de la scène la plus proche
                if (j.getY() < Scene.SOL - 30) {
                    Session s = sessionDe(j);
                    if (s == null) {
                        s = SESSIONS.values().stream().filter(x -> x.level == level)
                                .min((a, b) -> Double.compare(Math.abs(a.scene.depart.x - j.getX()), Math.abs(b.scene.depart.x - j.getX()))).orElse(null);
                    }
                    if (s != null) {
                        j.teleportTo(level, s.scene.depart.x, s.scene.depart.y, s.scene.depart.z, s.scene.departLacet, 0F);
                    } else {
                        revenir(j);
                    }
                    j.fallDistance = 0;
                }
                if (server.getTickCount() % 40 == 0) {
                    verifierMode(j);
                }
            }
        }
    }

    /** Personne ne meurt : un héros à deux cœurs est attrapé ; un méchant à un cœur est K.O. quelques secondes. */
    @SubscribeEvent
    public static void onDegats(LivingDamageEvent.Pre event) {
        if (!(event.getEntity() instanceof ServerPlayer j) || !dedans(j)) {
            return;
        }
        Session s = sessionDe(j);
        if (s == null) {
            return;
        }
        float apres = j.getHealth() - event.getNewDamage();
        if (s.captures.contains(j.getUUID())) {
            event.setNewDamage(0);
        } else if (s.heros.contains(j.getUUID())) {
            if (apres <= 4F) {
                event.setNewDamage(0);
                s.capturer(j);
            }
        } else if (s.mechants.contains(j.getUUID()) && apres <= 2F) {
            event.setNewDamage(0);
            j.setHealth(10F);
            j.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 100, 4, false, false));
            j.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, 100, 4, false, false));
            j.connection.send(new ClientboundSetTitlesAnimationPacket(5, 50, 15));
            j.connection.send(new ClientboundSetSubtitleTextPacket(Component.literal("Tu te relèves dans 5 secondes.").withStyle(ChatFormatting.GRAY)));
            j.connection.send(new ClientboundSetTitleTextPacket(Component.literal("K.O. !").withStyle(ChatFormatting.RED, ChatFormatting.BOLD)));
        }
    }

    /** Un clic droit sur les barreaux de la cellule, sur un réservoir de la fusée, sur le gong. */
    @SubscribeEvent
    public static void onClic(PlayerInteractEvent.RightClickBlock event) {
        if (event.getLevel().isClientSide() || event.getHand() != InteractionHand.MAIN_HAND || !(event.getEntity() instanceof ServerPlayer j) || !dedans(j)) {
            return;
        }
        BlockPos p = event.getPos();
        Session s = sessionIci(j.level(), Vec3.atCenterOf(p));
        if (s == null) {
            return;
        }
        if (s.dansLaCellule(p)) {
            event.setCanceled(true);
            if (s.captures.contains(j.getUUID())) {
                j.displayClientMessage(Component.literal("Tu ne peux pas t'ouvrir tout seul : il faut qu'un pote vienne.").withStyle(ChatFormatting.GRAY), true);
            } else {
                s.liberer(j);
            }
            return;
        }
        int r = s.reservoir(p);
        if (r >= 0 && s.estCible(j)) {
            event.setCanceled(true);
            s.commencerSabotage(j, r);
            return;
        }
        if (s.scene.gong != null && s.scene.gong.contains(Vec3.atCenterOf(p))) {
            event.setCanceled(true);
            s.sonnerGong(j.serverLevel(), j, s.estCible(j) ? j : null);
        }
    }

    @SubscribeEvent
    public static void onFleche(net.neoforged.neoforge.event.entity.ProjectileImpactEvent event) {
        if (event.getProjectile().getTags().contains(FLECHE) && event.getRayTraceResult() instanceof net.minecraft.world.phys.EntityHitResult e
                && e.getEntity() instanceof ServerPlayer j && !event.getProjectile().level().isClientSide()) {
            event.setCanceled(true);
            j.hurt(coup(event.getProjectile().getOwner() != null ? event.getProjectile().getOwner() : event.getProjectile()), 3F);
            event.getProjectile().discard();
        }
    }

    @SubscribeEvent
    public static void onChangeDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer j)) {
            return;
        }
        boolean depuis = event.getFrom() == JOUR || event.getFrom() == NUIT;
        if (depuis && !dedans(j) && !EN_ROUTE.contains(j.getUUID())) {
            for (Session s : SESSIONS.values()) {
                s.reprendreObjets(j);
                s.quitter(j.getUUID());
            }
            purger(j);
            PacketDistributor.sendToPlayer(j, new Hud("", List.of(), false));
        }
        verifierMode(j);
    }

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer j) {
            verifierMode(j);
            if (dedans(j) && sessionDe(j) == null) {
                j.sendSystemMessage(Component.literal("Tu es dans une mission terminée : /mission retour pour rentrer.").withStyle(ChatFormatting.GOLD));
            }
        }
    }

    @SubscribeEvent
    public static void onRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (event.getEntity() instanceof ServerPlayer j) {
            verifierMode(j);
        }
    }

    @SubscribeEvent
    public static void onDisparition(MobDespawnEvent event) {
        if (event.getEntity().getTags().stream().anyMatch(t -> t.startsWith("mission."))) {
            event.setResult(MobDespawnEvent.Result.DENY);
        }
    }

    @SubscribeEvent
    public static void onApparition(MobSpawnEvent.SpawnPlacementCheck event) {
        var dim = event.getLevel().getLevel().dimension();
        if ((dim == JOUR || dim == NUIT) && (event.getSpawnType() == MobSpawnType.NATURAL || event.getSpawnType() == MobSpawnType.CHUNK_GENERATION)) {
            event.setResult(MobSpawnEvent.SpawnPlacementCheck.Result.FAIL);
        }
    }

    /** Les restes d'une construction précédente (leur chunk n'était pas chargé quand on a tout effacé) sont refusés. */
    @SubscribeEvent
    public static void onArrivee(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide() || event.getLevel().getServer() == null) {
            return;
        }
        var dim = event.getLevel().dimension();
        if (dim != JOUR && dim != NUIT) {
            return;
        }
        for (String id : LIEUX.keySet()) {
            String tag = "mission." + id;
            if (event.getEntity().getTags().contains(tag)
                    && !event.getEntity().getTags().contains(tag + ".n" + MissionData.get(event.getLevel().getServer()).construction(id))) {
                event.setCanceled(true);
                return;
            }
        }
    }

    private Missions() {
    }
}
