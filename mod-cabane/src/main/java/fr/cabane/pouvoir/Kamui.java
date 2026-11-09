package fr.cabane.pouvoir;

import fr.cabane.Cabane;
import fr.cabane.client.pouvoir.ClientKamui;
import io.netty.buffer.ByteBuf;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/**
 * Le Kamui (le Mangekyo du Susano, niveau 2 et plus, la touche J maintenue ; d'après les vidéos de l'hôte : le corps
 * aspiré en spirale qui disparaît). S'il regarde quelqu'un (à moins de 20 blocs), une spirale l'aspire en tournant
 * jusqu'à un point, et il disparaît dans la dimension du Kamui (cabane:kamui : de grands blocs de pierre dans le vide
 * gris) ; un joueur y reste 10 s puis revient pile là où il était ; un monstre ou un animal disparaît pour toujours.
 * S'il ne regarde personne, c'est lui qui s'aspire : il se cache dans le Kamui, mais reste dans le monde (30 s au plus) ;
 * personne ne le voit (ni corps, ni nom, ni objet, ni pas), personne ne l'entend en vocal (lui entend tout le monde), rien
 * ne le touche et il ne touche rien. La touche maintenue de nouveau, ou les 30 s passées : il réapparaît en spirale.
 * Les spirales et sa vue grise sont dessinées par {@link ClientKamui}.
 */
@EventBusSubscriber(modid = Cabane.MODID)
public final class Kamui {
    public static final ResourceKey<Level> DIMENSION = ResourceKey.create(Registries.DIMENSION, Cabane.id("kamui"));
    /** Les temps (en ticks) : l'aspiration d'une cible, le séjour d'un joueur, sa propre aspiration et sa réapparition. */
    public static final int ASPIRATION = 24;
    public static final int SEJOUR = 10 * 20;
    public static final int SOI = 16;
    private static final int RECHARGE_CIBLE = 40 * 20;
    private static final int RECHARGE_SOI = 20 * 20;
    /** Caché dans le Kamui : 30 s au plus. */
    public static final int CACHE = 30 * 20;
    /** « susanodev » reste caché tant qu'il ne décide pas de sortir. */
    public static final int SANS_FIN = Integer.MAX_VALUE / 2;
    private static final double PORTEE_CIBLE = 20;
    private static final double PORTEE_SOI = 25;
    /** La dimension : le dessus du bloc du milieu, l'écart entre deux arrivées, la taille (en cases de 6 blocs). */
    public static final int SOL = 64;
    private static final int ECART = 300;
    private static final int CASES = 5;
    /** Les spirales : une cible aspirée, lui qui s'aspire, lui qui réapparaît. */
    public static final int ASPIRE = 0;
    public static final int PART = 1;
    public static final int ARRIVE = 2;

    /** Serveur → ceux qui sont autour : une spirale autour de l'entité {@code entite}, centrée en (x, y, z). */
    public record Spirale(int entite, double x, double y, double z, int mode, int duree) implements CustomPacketPayload {
        public static final Type<Spirale> TYPE = new Type<>(Cabane.id("kamui_spirale"));
        public static final StreamCodec<ByteBuf, Spirale> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, Spirale::entite, ByteBufCodecs.DOUBLE, Spirale::x, ByteBufCodecs.DOUBLE, Spirale::y, ByteBufCodecs.DOUBLE, Spirale::z,
                ByteBufCodecs.VAR_INT, Spirale::mode, ByteBufCodecs.VAR_INT, Spirale::duree, Spirale::new);

        @Override
        public Type<Spirale> type() {
            return TYPE;
        }
    }

    /** Serveur → le joueur dans le Kamui : combien de ticks il y reste (0 : il en sort). */
    public record Sejour(int reste) implements CustomPacketPayload {
        public static final Type<Sejour> TYPE = new Type<>(Cabane.id("kamui_sejour"));
        public static final StreamCodec<ByteBuf, Sejour> CODEC = StreamCodec.composite(ByteBufCodecs.VAR_INT, Sejour::reste, Sejour::new);

        @Override
        public Type<Sejour> type() {
            return TYPE;
        }
    }

    /** Serveur → tous : ce joueur est caché dans le Kamui (ou en ressort) ; {@code reste} : ses ticks restants. */
    public record Cache(UUID joueur, boolean actif, int reste) implements CustomPacketPayload {
        public static final Type<Cache> TYPE = new Type<>(Cabane.id("kamui_cache"));
        public static final StreamCodec<ByteBuf, Cache> CODEC = StreamCodec.composite(
                net.minecraft.core.UUIDUtil.STREAM_CODEC, Cache::joueur, ByteBufCodecs.BOOL, Cache::actif, ByteBufCodecs.VAR_INT, Cache::reste, Cache::new);

        @Override
        public Type<Cache> type() {
            return TYPE;
        }
    }

    @SubscribeEvent
    public static void reseau(RegisterPayloadHandlersEvent event) {
        event.registrar("1")
                .playToClient(Spirale.TYPE, Spirale.CODEC, (p, ctx) -> ctx.enqueueWork(() -> ClientKamui.recevoir(p)))
                .playToClient(Sejour.TYPE, Sejour.CODEC, (p, ctx) -> ctx.enqueueWork(() -> ClientKamui.sejour(p)))
                .playToClient(Cache.TYPE, Cache.CODEC, (p, ctx) -> ctx.enqueueWork(() -> ClientKamui.cache(p)));
    }

    private record Aspiration(UUID lanceur, int entite, ServerLevel monde, Vec3 pos, int debut) {
    }

    private record Retour(UUID joueur, ResourceKey<Level> dimension, Vec3 pos, float lacet, float tangage, GameType mode, int fin, int place) {
    }

    private record Saut(UUID joueur, Vec3 arrivee, int quand) {
    }

    private static final List<Aspiration> ASPIRATIONS = new ArrayList<>();
    private static final List<Retour> SEJOURS = new ArrayList<>();
    private static final List<Saut> SAUTS = new ArrayList<>();
    /** Ceux qui sont cachés dans le Kamui, et jusqu'à quand. */
    private static final Map<UUID, Integer> CACHES = new HashMap<>();

    /** Ce joueur est caché dans le Kamui (on ne le voit pas, il ne touche rien). */
    public static boolean cache(UUID joueur) {
        return CACHES.containsKey(joueur);
    }
    private static final Map<UUID, Integer> PRET_CIBLE = new HashMap<>();
    private static final Map<UUID, Integer> PRET_SOI = new HashMap<>();
    private static final java.util.Set<Integer> CONSTRUITES = new java.util.HashSet<>();

    private static void spirale(ServerLevel monde, Entity e, Vec3 centre, int mode, int duree) {
        Spirale paquet = new Spirale(e.getId(), centre.x, centre.y, centre.z, mode, duree);
        for (ServerPlayer p : monde.players()) {
            if (p.position().distanceToSqr(centre) < 128 * 128) {
                PacketDistributor.sendToPlayer(p, paquet);
            }
        }
    }

    // ================================================================ lancer

    static void lancer(ServerPlayer lanceur) {
        int maintenant = lanceur.server.getTickCount();
        ServerLevel monde = lanceur.serverLevel();
        if (CACHES.containsKey(lanceur.getUUID())) {
            sortir(lanceur);
            return;
        }
        if (monde.dimension() == DIMENSION || monde.dimension() == Genjutsu.DIMENSION || enCours(lanceur.getUUID())) {
            return;
        }
        LivingEntity cible = viser(lanceur);
        if (cible != null) {
            int reste = PRET_CIBLE.getOrDefault(lanceur.getUUID(), 0) - maintenant;
            if (reste > 0) {
                lanceur.displayClientMessage(Component.literal("Kamui : ton œil se repose… encore " + (reste + 19) / 20 + " s").withStyle(ChatFormatting.RED), true);
                return;
            }
            PRET_CIBLE.put(lanceur.getUUID(), maintenant + RECHARGE_CIBLE);
            ASPIRATIONS.add(new Aspiration(lanceur.getUUID(), cible.getId(), monde, cible.position(), maintenant));
            spirale(monde, cible, cible.position().add(0, cible.getBbHeight() / 2, 0), ASPIRE, ASPIRATION);
            if (cible instanceof ServerPlayer p) {
                p.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, ASPIRATION, 6, false, false));
            }
            monde.playSound(null, cible.getX(), cible.getY(), cible.getZ(), SoundEvents.PORTAL_TRIGGER, SoundSource.PLAYERS, 1.2F, 1.8F);
            monde.playSound(null, cible.getX(), cible.getY(), cible.getZ(), SoundEvents.WARDEN_SONIC_CHARGE, SoundSource.PLAYERS, 1F, 1.6F);
            return;
        }
        int reste = PRET_SOI.getOrDefault(lanceur.getUUID(), 0) - maintenant;
        if (reste > 0) {
            lanceur.displayClientMessage(Component.literal("Kamui : encore " + (reste + 19) / 20 + " s").withStyle(ChatFormatting.RED), true);
            return;
        }
        SAUTS.add(new Saut(lanceur.getUUID(), lanceur.position(), maintenant + SOI));
        spirale(monde, lanceur, lanceur.position().add(0, 1, 0), PART, SOI);
        monde.playSound(null, lanceur.getX(), lanceur.getY(), lanceur.getZ(), SoundEvents.PORTAL_TRIGGER, SoundSource.PLAYERS, 0.8F, 2F);
    }

    private static boolean enCours(UUID joueur) {
        return SAUTS.stream().anyMatch(s -> s.joueur().equals(joueur));
    }

    /** Ce qu'il regarde : un joueur (ni créatif, ni spectateur), un monstre ou un animal, à moins de 20 blocs. */
    private static LivingEntity viser(ServerPlayer lanceur) {
        Vec3 oeil = lanceur.getEyePosition(), regard = lanceur.getLookAngle(), fin = oeil.add(regard.scale(PORTEE_CIBLE));
        BlockHitResult mur = lanceur.level().clip(new ClipContext(oeil, fin, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, lanceur));
        if (mur.getType() == HitResult.Type.BLOCK) {
            fin = mur.getLocation();
        }
        AABB zone = lanceur.getBoundingBox().expandTowards(regard.scale(PORTEE_CIBLE)).inflate(1.5);
        EntityHitResult touche = ProjectileUtil.getEntityHitResult(lanceur.level(), lanceur, oeil, fin, zone, e -> e instanceof LivingEntity v && aspirable(lanceur, v), 0.4F);
        return touche != null && touche.getEntity() instanceof LivingEntity v ? v : null;
    }

    private static boolean aspirable(ServerPlayer lanceur, LivingEntity v) {
        if (!v.isAlive() || v == lanceur || ASPIRATIONS.stream().anyMatch(a -> a.entite() == v.getId())) {
            return false;
        }
        if (v instanceof ServerPlayer p) {
            return !p.isCreative() && !p.isSpectator();
        }
        return (v instanceof Enemy || v instanceof Animal) && Pouvoirs.cible(lanceur, v);
    }

    /** Où il réapparaît : là où il regarde (contre le premier bloc, ou à 25 blocs), les pieds sur un endroit libre. */
    private static Vec3 arrivee(ServerPlayer lanceur) {
        Vec3 oeil = lanceur.getEyePosition(), regard = lanceur.getLookAngle();
        BlockHitResult mur = lanceur.level().clip(new ClipContext(oeil, oeil.add(regard.scale(PORTEE_SOI)), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, lanceur));
        Vec3 p = mur.getType() == HitResult.Type.BLOCK ? mur.getLocation().subtract(regard.scale(0.8)) : oeil.add(regard.scale(PORTEE_SOI));
        Vec3 pieds = p.subtract(0, 1.5, 0);
        BlockPos b = BlockPos.containing(pieds);
        for (int i = 0; i < 4 && (!libre(lanceur.level(), b) || !libre(lanceur.level(), b.above())); i++) {
            b = b.above();
        }
        return new Vec3(pieds.x, Math.max(pieds.y, b.getY()), pieds.z);
    }

    private static boolean libre(Level monde, BlockPos b) {
        return monde.getBlockState(b).getCollisionShape(monde, b).isEmpty();
    }

    // ================================================================ le temps

    @SubscribeEvent
    public static void tick(ServerTickEvent.Post event) {
        if (ASPIRATIONS.isEmpty() && SEJOURS.isEmpty() && SAUTS.isEmpty() && CACHES.isEmpty()) {
            return;
        }
        MinecraftServer server = event.getServer();
        int maintenant = server.getTickCount();
        for (Aspiration a : new ArrayList<>(ASPIRATIONS)) {
            Entity e = a.monde().getEntity(a.entite());
            if (!(e instanceof LivingEntity v) || !v.isAlive()) {
                ASPIRATIONS.remove(a);
                continue;
            }
            int age = maintenant - a.debut();
            if (age < ASPIRATION) {
                // il ne peut plus bouger : la spirale le tient
                v.teleportTo(a.pos().x, a.pos().y, a.pos().z);
                v.setDeltaMovement(Vec3.ZERO);
                v.fallDistance = 0;
                continue;
            }
            ASPIRATIONS.remove(a);
            envoyer(server, v);
        }
        for (Retour r : new ArrayList<>(SEJOURS)) {
            if (maintenant >= r.fin()) {
                SEJOURS.remove(r);
                ramener(server, r);
            }
        }
        for (Saut s : new ArrayList<>(SAUTS)) {
            if (maintenant < s.quand()) {
                continue;
            }
            SAUTS.remove(s);
            ServerPlayer p = server.getPlayerList().getPlayer(s.joueur());
            if (p != null) {
                cacher(p);
            }
        }
        for (Map.Entry<UUID, Integer> c : new ArrayList<>(CACHES.entrySet())) {
            ServerPlayer p = server.getPlayerList().getPlayer(c.getKey());
            if (p == null) {
                CACHES.remove(c.getKey());
                fr.cabane.telephone.VoiceBridge.muet(c.getKey(), false);
            } else if (maintenant >= c.getValue()) {
                sortir(p);
            } else {
                // personne ne le voit, ses pas ne font pas de bruit
                p.setSilent(true);
                p.addEffect(new MobEffectInstance(MobEffects.INVISIBILITY, 40, 0, false, false));
            }
        }
    }

    /** La fin de l'aspiration : un joueur part 10 s dans le Kamui ; un monstre ou un animal, pour toujours. */
    private static void envoyer(MinecraftServer server, LivingEntity v) {
        ServerLevel kamui = server.getLevel(DIMENSION);
        if (kamui == null) {
            return;
        }
        if (v instanceof ServerPlayer p) {
            int place = 1;
            while (placePrise(place)) {
                place++;
            }
            construire(kamui, place);
            SEJOURS.add(new Retour(p.getUUID(), p.level().dimension(), p.position(), p.getYRot(), p.getXRot(), p.gameMode.getGameModeForPlayer(),
                    server.getTickCount() + SEJOUR, place));
            p.stopRiding();
            p.teleportTo(kamui, place * ECART + 0.5, SOL + 1, 0.5, p.getYRot(), 0F);
            if (p.gameMode.getGameModeForPlayer() == GameType.SURVIVAL) {
                p.setGameMode(GameType.ADVENTURE);
            }
            p.setDeltaMovement(Vec3.ZERO);
            p.fallDistance = 0;
            PacketDistributor.sendToPlayer(p, new Sejour(SEJOUR));
            kamui.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.PORTAL_TRAVEL, SoundSource.PLAYERS, 0.4F, 1.6F);
            return;
        }
        // les bêtes aspirées ne reviennent jamais : elles disparaissent du monde
        v.discard();
    }

    private static boolean placePrise(int place) {
        return SEJOURS.stream().anyMatch(r -> r.place() == place);
    }

    private static void ramener(MinecraftServer server, Retour r) {
        ServerPlayer p = server.getPlayerList().getPlayer(r.joueur());
        if (p == null || p.level().dimension() != DIMENSION) {
            return;
        }
        ServerLevel monde = server.getLevel(r.dimension());
        if (monde == null) {
            monde = server.overworld();
        }
        p.teleportTo(monde, r.pos().x, r.pos().y, r.pos().z, r.lacet(), r.tangage());
        if (p.gameMode.getGameModeForPlayer() != r.mode()) {
            p.setGameMode(r.mode());
        }
        p.setDeltaMovement(Vec3.ZERO);
        p.fallDistance = 0;
        PacketDistributor.sendToPlayer(p, new Sejour(0));
        spirale(monde, p, r.pos().add(0, 1, 0), ARRIVE, SOI);
        monde.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.ENDERMAN_TELEPORT, SoundSource.PLAYERS, 0.8F, 0.6F);
    }

    static void endormir(ServerPlayer joueur) {
        // ce qui est parti reste parti jusqu'au bout de ses 10 s ; lui, caché, ressort
        SAUTS.removeIf(s -> s.joueur().equals(joueur.getUUID()));
        if (CACHES.containsKey(joueur.getUUID())) {
            sortir(joueur);
        }
    }

    // ================================================================ caché dans le Kamui

    private static void cacher(ServerPlayer p) {
        int duree = Susano3.DEV.equals(Pouvoirs.pouvoir(p)) ? SANS_FIN : CACHE;
        CACHES.put(p.getUUID(), p.server.getTickCount() + duree);
        p.setSilent(true);
        p.addEffect(new MobEffectInstance(MobEffects.INVISIBILITY, 40, 0, false, false));
        fr.cabane.telephone.VoiceBridge.muet(p.getUUID(), true);
        PacketDistributor.sendToAllPlayers(new Cache(p.getUUID(), true, duree));
        p.playNotifySound(SoundEvents.PORTAL_TRAVEL, SoundSource.PLAYERS, 0.25F, 1.8F);
        p.displayClientMessage(Component.literal("Caché dans le Kamui : personne ne te voit ni ne t'entend.").withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC), true);
    }

    /** Il ressort : on le revoit (en spirale), on l'entend de nouveau ; son Kamui sur lui se repose 20 s. */
    private static void sortir(ServerPlayer p) {
        CACHES.remove(p.getUUID());
        p.setSilent(false);
        p.removeEffect(MobEffects.INVISIBILITY);
        fr.cabane.telephone.VoiceBridge.muet(p.getUUID(), false);
        PacketDistributor.sendToAllPlayers(new Cache(p.getUUID(), false, 0));
        PRET_SOI.put(p.getUUID(), p.server.getTickCount() + RECHARGE_SOI);
        spirale(p.serverLevel(), p, p.position().add(0, 1, 0), ARRIVE, SOI);
        p.serverLevel().playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.ENDERMAN_TELEPORT, SoundSource.PLAYERS, 0.8F, 0.6F);
    }

    /** Caché, rien ne le touche, et il ne touche rien. */
    @SubscribeEvent
    public static void intouchable(net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent event) {
        if (CACHES.containsKey(event.getEntity().getUUID())
                || event.getSource().getEntity() != null && CACHES.containsKey(event.getSource().getEntity().getUUID())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void frapper(net.neoforged.neoforge.event.entity.player.AttackEntityEvent event) {
        if (CACHES.containsKey(event.getEntity().getUUID())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void cible(net.neoforged.neoforge.event.entity.living.LivingChangeTargetEvent event) {
        if (event.getNewAboutToBeSetTarget() != null && CACHES.containsKey(event.getNewAboutToBeSetTarget().getUUID())) {
            event.setCanceled(true);
        }
    }

    /** Celui qui arrive apprend qui est caché (pour ne pas le dessiner). */
    @SubscribeEvent
    public static void arrivee(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer p) {
            int maintenant = p.server.getTickCount();
            CACHES.forEach((u, fin) -> PacketDistributor.sendToPlayer(p, new Cache(u, true, fin - maintenant)));
        }
    }

    // ================================================================ la dimension : de grands blocs de pierre dans le vide

    /** Autour de l'arrivée : des piliers de 4 × 4 blocs, de hauteurs différentes, séparés de vide (un sol invisible tout en bas). */
    private static void construire(ServerLevel kamui, int place) {
        BlockPos c = new BlockPos(place * ECART, SOL, 0);
        if (CONSTRUITES.contains(place) && !kamui.getBlockState(c).isAir()) {
            return;
        }
        BlockState[] pierres = {Blocks.SMOOTH_STONE.defaultBlockState(), Blocks.STONE.defaultBlockState(), Blocks.POLISHED_ANDESITE.defaultBlockState(),
                Blocks.SMOOTH_STONE.defaultBlockState(), Blocks.STONE_BRICKS.defaultBlockState()};
        Random r = new Random(place * 7919L + 13);
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        int bas = SOL - 12;
        for (int i = -CASES; i <= CASES; i++) {
            for (int j = -CASES; j <= CASES; j++) {
                // le pilier du milieu, sous l'arrivée, est pile à la hauteur du sol
                int haut = i == 0 && j == 0 ? SOL : SOL + r.nextInt(9) - 5;
                BlockState pierre = pierres[r.nextInt(pierres.length)];
                int x0 = c.getX() + i * 6 - 1, z0 = c.getZ() + j * 6 - 1;
                for (int dx = 0; dx < 4; dx++) {
                    for (int dz = 0; dz < 4; dz++) {
                        for (int y = bas; y <= haut; y++) {
                            kamui.setBlock(p.set(x0 + dx, y, z0 + dz), pierre, 2);
                        }
                    }
                }
            }
        }
        // un sol invisible tout en bas, pour ne pas tomber dans le vide
        for (int x = -CASES * 6 - 3; x <= CASES * 6 + 3; x++) {
            for (int z = -CASES * 6 - 3; z <= CASES * 6 + 3; z++) {
                kamui.setBlock(p.set(c.getX() + x, bas - 1, c.getZ() + z), Blocks.BARRIER.defaultBlockState(), 2);
            }
        }
        CONSTRUITES.add(place);
    }

    // ================================================================ rien à casser là-bas ; départs, morts, arrêt

    @SubscribeEvent
    public static void casser(BlockEvent.BreakEvent event) {
        if (event.getPlayer() != null && (event.getPlayer().level().dimension() == DIMENSION && !event.getPlayer().isCreative()
                || CACHES.containsKey(event.getPlayer().getUUID()))) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void deconnexion(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer p) {
            for (Retour r : new ArrayList<>(SEJOURS)) {
                if (r.joueur().equals(p.getUUID())) {
                    SEJOURS.remove(r);
                    ramener(p.server, r);
                }
            }
            SAUTS.removeIf(s -> s.joueur().equals(p.getUUID()));
            if (CACHES.containsKey(p.getUUID())) {
                sortir(p);
            }
        }
    }

    @SubscribeEvent
    public static void mort(LivingDeathEvent event) {
        if (event.getEntity() instanceof ServerPlayer p) {
            SEJOURS.removeIf(r -> r.joueur().equals(p.getUUID()));
            SAUTS.removeIf(s -> s.joueur().equals(p.getUUID()));
        }
    }

    /** Quelqu'un se reconnecte dans le Kamui sans y être retenu : il revient au point d'apparition. */
    @SubscribeEvent
    public static void connexion(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer p && p.level().dimension() == DIMENSION
                && SEJOURS.stream().noneMatch(r -> r.joueur().equals(p.getUUID()))) {
            ServerLevel monde = p.server.overworld();
            BlockPos s = monde.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, monde.getSharedSpawnPos());
            p.teleportTo(monde, s.getX() + 0.5, s.getY(), s.getZ() + 0.5, p.getYRot(), 0F);
            if (p.gameMode.getGameModeForPlayer() == GameType.ADVENTURE) {
                p.setGameMode(GameType.SURVIVAL);
            }
        }
    }

    @SubscribeEvent
    public static void arret(ServerStoppingEvent event) {
        for (Retour r : new ArrayList<>(SEJOURS)) {
            ramener(event.getServer(), r);
        }
        for (UUID u : new ArrayList<>(CACHES.keySet())) {
            ServerPlayer p = event.getServer().getPlayerList().getPlayer(u);
            if (p != null) {
                p.setSilent(false);
                p.removeEffect(MobEffects.INVISIBILITY);
            }
            fr.cabane.telephone.VoiceBridge.muet(u, false);
        }
        CACHES.clear();
        SEJOURS.clear();
        SAUTS.clear();
        ASPIRATIONS.clear();
        PRET_CIBLE.clear();
        PRET_SOI.clear();
        CONSTRUITES.clear();
    }

    private Kamui() {
    }
}
