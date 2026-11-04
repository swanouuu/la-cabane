package fr.cabane.pouvoir;

import fr.cabane.Cabane;
import fr.cabane.client.pouvoir.ClientGenjutsu;
import io.netty.buffer.ByteBuf;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.UUIDUtil;
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
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.RelativeMovement;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/**
 * Le Tsukuyomi (le Mangekyo du Susano, touche J, docs/sharingan/tsukuyomi.mp4) : il regarde quelqu'un dans les yeux
 * (à moins de 16 blocs) ; les deux voient ses yeux en grand ({@link Sharingan.Regard}), puis tous les deux sont emmenés
 * 30 s dans le genjutsu : la dimension cabane:tsukuyomi, un monde en négatif (ciel rouge, lune noire, sol noir quadrillé
 * de rouge, dessinés par {@link ClientGenjutsu}). La cible y est attachée à une croix de bois (elle peut regarder
 * autour d'elle, pas bouger) et perd un demi-cœur toutes les 2,5 s ; lui s'y promène librement (30 blocs autour), et
 * ses clones sortent du sol. À la fin, l'illusion se brise et chacun revient pile là où il était. Une fois par minute.
 */
@EventBusSubscriber(modid = Cabane.MODID)
public final class Genjutsu {
    public static final ResourceKey<Level> DIMENSION = ResourceKey.create(Registries.DIMENSION, Cabane.id("tsukuyomi"));
    static final ResourceKey<DamageType> DEGATS = ResourceKey.create(Registries.DAMAGE_TYPE, Cabane.id("tsukuyomi"));
    /** Les yeux, avant le saut dans le genjutsu (en ticks), puis le temps dedans. */
    public static final int REGARD = 40;
    public static final int DUREE = 30 * 20;
    public static final int RECHARGE = 60 * 20;
    private static final double PORTEE = 16;
    private static final int DEGATS_TOUS_LES = 50;
    private static final float POINTS = 1;
    /** La salle : le sol (sa hauteur), son rayon, et l'écart entre deux salles (une par Tsukuyomi en même temps). */
    public static final int SOL = 64;
    public static final int RAYON = 30;
    private static final int ECART = 400;

    private record Retour(ResourceKey<Level> dimension, Vec3 pos, float lacet, float tangage, GameType mode) {
    }

    private static final class Seance {
        final UUID lanceur;
        final UUID victime;
        final int debut;
        final int place;
        Retour retourLanceur;
        Retour retourVictime;
        boolean dedans;

        Seance(UUID lanceur, UUID victime, int debut, int place) {
            this.lanceur = lanceur;
            this.victime = victime;
            this.debut = debut;
            this.place = place;
        }
    }

    /** Les Tsukuyomi en cours (par lanceur), la dernière fois que chacun en a lancé un, les salles déjà construites. */
    private static final Map<UUID, Seance> SEANCES = new HashMap<>();
    private static final Map<UUID, Integer> DERNIER = new HashMap<>();
    private static final Set<Integer> CONSTRUITES = new HashSet<>();

    /** Serveur → les deux : le genjutsu commence (ou finit) ; {@code reste} : les ticks qu'il reste dedans. */
    public record Etat(UUID lanceur, UUID victime, int place, int reste, boolean actif) implements CustomPacketPayload {
        public static final Type<Etat> TYPE = new Type<>(Cabane.id("genjutsu_etat"));
        public static final StreamCodec<ByteBuf, Etat> CODEC = StreamCodec.composite(
                UUIDUtil.STREAM_CODEC, Etat::lanceur, UUIDUtil.STREAM_CODEC, Etat::victime, ByteBufCodecs.VAR_INT, Etat::place,
                ByteBufCodecs.VAR_INT, Etat::reste, ByteBufCodecs.BOOL, Etat::actif, Etat::new);

        @Override
        public Type<Etat> type() {
            return TYPE;
        }
    }

    @SubscribeEvent
    public static void reseau(RegisterPayloadHandlersEvent event) {
        event.registrar("1").playToClient(Etat.TYPE, Etat.CODEC, (p, ctx) -> ctx.enqueueWork(() -> ClientGenjutsu.recevoir(p)));
    }

    /** Le centre de la salle n° place (le pied de la croix). */
    public static BlockPos centre(int place) {
        return new BlockPos(place * ECART, SOL, 0);
    }

    // ================================================================ lancer

    static void lancer(ServerPlayer lanceur) {
        MinecraftServer server = lanceur.server;
        int maintenant = server.getTickCount();
        if (dans(lanceur.getUUID()) != null) {
            return;
        }
        int reste = DERNIER.getOrDefault(lanceur.getUUID(), -RECHARGE) + RECHARGE - maintenant;
        if (reste > 0) {
            lanceur.displayClientMessage(Component.literal("Ton Mangekyo se repose… encore " + (reste + 19) / 20 + " s").withStyle(ChatFormatting.RED), true);
            return;
        }
        if (server.getLevel(DIMENSION) == null) {
            lanceur.displayClientMessage(Component.literal("Le Tsukuyomi n'existe pas dans ce monde.").withStyle(ChatFormatting.RED), true);
            return;
        }
        ServerPlayer cible = viser(lanceur);
        if (cible == null) {
            lanceur.displayClientMessage(Component.literal("Tsukuyomi : regarde quelqu'un dans les yeux (16 blocs).").withStyle(ChatFormatting.GRAY), true);
            return;
        }
        if (dans(cible.getUUID()) != null) {
            lanceur.displayClientMessage(Component.literal("Son esprit est déjà prisonnier d'un genjutsu.").withStyle(ChatFormatting.GRAY), true);
            return;
        }
        int place = 0;
        while (placePrise(place)) {
            place++;
        }
        SEANCES.put(lanceur.getUUID(), new Seance(lanceur.getUUID(), cible.getUUID(), maintenant, place));
        DERNIER.put(lanceur.getUUID(), maintenant);
        // les deux voient ses yeux : le Mangekyo s'allume, puis le saut
        for (ServerPlayer p : new ServerPlayer[]{lanceur, cible}) {
            PacketDistributor.sendToPlayer(p, new Sharingan.Regard(lanceur.getUUID(), 2, Sharingan.TSUKUYOMI));
        }
        cible.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, REGARD, 6, false, false));
    }

    /** Pour l'hôte, seul (/sharingan tsukuyomi) : il est à la fois celui qui le lance et celui qui est sur la croix. */
    static void tester(ServerPlayer moi) {
        if (dans(moi.getUUID()) != null || moi.server.getLevel(DIMENSION) == null) {
            return;
        }
        int place = 0;
        while (placePrise(place)) {
            place++;
        }
        SEANCES.put(moi.getUUID(), new Seance(moi.getUUID(), moi.getUUID(), moi.server.getTickCount(), place));
        PacketDistributor.sendToPlayer(moi, new Sharingan.Regard(moi.getUUID(), 2, Sharingan.TSUKUYOMI));
    }

    /** Le joueur qu'il regarde, à moins de 16 blocs (ni en spectateur, ni en créatif). */
    private static ServerPlayer viser(ServerPlayer lanceur) {
        Vec3 oeil = lanceur.getEyePosition(), fin = oeil.add(lanceur.getLookAngle().scale(PORTEE));
        AABB zone = lanceur.getBoundingBox().expandTowards(lanceur.getLookAngle().scale(PORTEE)).inflate(1.5);
        EntityHitResult touche = ProjectileUtil.getEntityHitResult(lanceur.level(), lanceur, oeil, fin, zone,
                e -> e instanceof ServerPlayer p && p != lanceur && !p.isSpectator() && !p.isCreative() && p.isAlive(), 0.6F);
        if (touche != null && touche.getEntity() instanceof ServerPlayer p && lanceur.hasLineOfSight(p)) {
            return p;
        }
        // à défaut, le plus droit dans son regard
        ServerPlayer mieux = null;
        double meilleur = 0.96;
        for (ServerPlayer p : lanceur.serverLevel().players()) {
            if (p == lanceur || p.isSpectator() || p.isCreative() || !p.isAlive()) {
                continue;
            }
            Vec3 vers = p.getEyePosition().subtract(oeil);
            double d = vers.length(), dot = vers.normalize().dot(lanceur.getLookAngle());
            if (d < PORTEE && dot > meilleur && lanceur.hasLineOfSight(p)) {
                meilleur = dot;
                mieux = p;
            }
        }
        return mieux;
    }

    private static boolean placePrise(int place) {
        return SEANCES.values().stream().anyMatch(s -> s.place == place);
    }

    /** La séance où ce joueur est (lanceur ou cible), ou null. */
    private static Seance dans(UUID joueur) {
        for (Seance s : SEANCES.values()) {
            if (s.lanceur.equals(joueur) || s.victime.equals(joueur)) {
                return s;
            }
        }
        return null;
    }

    /** Ce joueur est attaché à la croix d'un Tsukuyomi. */
    public static boolean attache(UUID joueur) {
        Seance s = dans(joueur);
        return s != null && s.dedans && s.victime.equals(joueur);
    }

    // ================================================================ entrer, rester, sortir

    private static Retour retour(ServerPlayer p) {
        return new Retour(p.level().dimension(), p.position(), p.getYRot(), p.getXRot(), p.gameMode.getGameModeForPlayer());
    }

    private static void entrer(MinecraftServer server, Seance s) {
        ServerPlayer lanceur = server.getPlayerList().getPlayer(s.lanceur), victime = server.getPlayerList().getPlayer(s.victime);
        ServerLevel tsu = server.getLevel(DIMENSION);
        if (lanceur == null || victime == null || tsu == null) {
            finir(server, s);
            return;
        }
        construire(tsu, s.place);
        s.retourLanceur = retour(lanceur);
        s.retourVictime = retour(victime);
        BlockPos c = centre(s.place);
        for (ServerPlayer p : new ServerPlayer[]{lanceur, victime}) {
            if (p.isSleeping()) {
                p.stopSleepInBed(true, true);
            }
            p.stopRiding();
        }
        Vec3 croix = croix(s.place);
        victime.teleportTo(tsu, croix.x, croix.y, croix.z, 0F, 0F);
        lanceur.teleportTo(tsu, c.getX() + 0.5, SOL + 1, c.getZ() + 9.5, 180F, 0F);
        for (ServerPlayer p : new ServerPlayer[]{lanceur, victime}) {
            if (p.gameMode.getGameModeForPlayer() == GameType.SURVIVAL) {
                p.setGameMode(GameType.ADVENTURE);
            }
            p.setDeltaMovement(Vec3.ZERO);
            p.fallDistance = 0;
        }
        s.dedans = true;
        tsu.playSound(null, c.getX(), SOL + 2, c.getZ(), SoundEvents.WARDEN_HEARTBEAT, SoundSource.PLAYERS, 2F, 0.6F);
        tsu.playSound(null, c.getX(), SOL + 2, c.getZ(), SoundEvents.BEACON_DEACTIVATE, SoundSource.PLAYERS, 1.5F, 0.5F);
        envoyer(server, s, new Etat(s.lanceur, s.victime, s.place, DUREE, true));
    }

    /** Où la cible est attachée : contre le poteau, un bloc au-dessus du sol, face au sud. */
    static Vec3 croix(int place) {
        BlockPos c = centre(place);
        return new Vec3(c.getX() + 0.5, SOL + 2, c.getZ() + 1.35);
    }

    private static void envoyer(MinecraftServer server, Seance s, Etat paquet) {
        for (UUID u : new UUID[]{s.lanceur, s.victime}) {
            ServerPlayer p = server.getPlayerList().getPlayer(u);
            if (p != null) {
                PacketDistributor.sendToPlayer(p, paquet);
            }
        }
    }

    private static void finir(MinecraftServer server, Seance s) {
        SEANCES.remove(s.lanceur);
        envoyer(server, s, new Etat(s.lanceur, s.victime, s.place, 0, false));
        if (!s.dedans) {
            return;
        }
        ramener(server, s.lanceur, s.retourLanceur);
        ramener(server, s.victime, s.retourVictime);
    }

    /** Ramène ce joueur là où il était avant le genjutsu (s'il est encore dedans), dans son mode de jeu. */
    private static void ramener(MinecraftServer server, UUID u, Retour r) {
        ServerPlayer p = server.getPlayerList().getPlayer(u);
        if (p == null || r == null || p.level().dimension() != DIMENSION) {
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
        p.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, 20, 0, false, false));
        p.addEffect(new MobEffectInstance(MobEffects.CONFUSION, 80, 0, false, false));
        monde.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.GLASS_BREAK, SoundSource.PLAYERS, 1.2F, 0.6F);
    }

    /** Son pouvoir s'endort, il se déconnecte ou meurt (ou sa cible) : le genjutsu se défait. */
    static void finirPour(ServerPlayer joueur) {
        Seance s = dans(joueur.getUUID());
        if (s != null) {
            finir(joueur.server, s);
        }
    }

    @SubscribeEvent
    public static void tick(ServerTickEvent.Post event) {
        if (SEANCES.isEmpty()) {
            return;
        }
        MinecraftServer server = event.getServer();
        int maintenant = server.getTickCount();
        for (Seance s : new ArrayList<>(SEANCES.values())) {
            ServerPlayer lanceur = server.getPlayerList().getPlayer(s.lanceur), victime = server.getPlayerList().getPlayer(s.victime);
            if (lanceur == null || victime == null) {
                finir(server, s);
                continue;
            }
            int age = maintenant - s.debut;
            if (!s.dedans) {
                if (age >= REGARD) {
                    entrer(server, s);
                }
                continue;
            }
            if (age >= REGARD + DUREE || victime.level().dimension() != DIMENSION || lanceur.level().dimension() != DIMENSION) {
                finir(server, s);
                continue;
            }
            // la cible reste attachée à la croix (elle peut tourner la tête)
            Vec3 croix = croix(s.place);
            if (victime.position().distanceToSqr(croix) > 0.01) {
                victime.connection.teleport(croix.x, croix.y, croix.z, 0F, 0F, EnumSet.of(RelativeMovement.X_ROT, RelativeMovement.Y_ROT));
            }
            victime.setDeltaMovement(Vec3.ZERO);
            victime.fallDistance = 0;
            // lui ne sort pas de la salle
            BlockPos c = centre(s.place);
            double dx = lanceur.getX() - (c.getX() + 0.5), dz = lanceur.getZ() - (c.getZ() + 0.5);
            if (Math.max(Math.abs(dx), Math.abs(dz)) > RAYON - 1.5 || lanceur.getY() < SOL - 2) {
                lanceur.teleportTo(c.getX() + 0.5, SOL + 1, c.getZ() + 9.5);
            }
            if (age > REGARD && (age - REGARD) % DEGATS_TOUS_LES == 0) {
                DamageSource source = new DamageSource(server.registryAccess().registryOrThrow(Registries.DAMAGE_TYPE).getHolderOrThrow(DEGATS), lanceur);
                victime.hurt(source, POINTS);
            }
        }
    }

    // ================================================================ la salle

    /** Le sol noir quadrillé de rouge (une ligne tous les 6 blocs), des murs invisibles, la croix de bois au milieu. */
    private static void construire(ServerLevel tsu, int place) {
        BlockPos c = centre(place);
        if (CONSTRUITES.contains(place) && tsu.getBlockState(c.above(5)).is(Blocks.DARK_OAK_LOG)) {
            return;
        }
        BlockState noir = Blocks.BLACK_CONCRETE.defaultBlockState(), ligne = Blocks.NETHER_WART_BLOCK.defaultBlockState();
        BlockState mur = Blocks.BARRIER.defaultBlockState(), air = Blocks.AIR.defaultBlockState();
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int dx = -RAYON; dx <= RAYON; dx++) {
            for (int dz = -RAYON; dz <= RAYON; dz++) {
                boolean bord = Math.abs(dx) == RAYON || Math.abs(dz) == RAYON;
                p.set(c.getX() + dx, SOL, c.getZ() + dz);
                tsu.setBlock(p, Math.floorMod(dx, 6) == 0 || Math.floorMod(dz, 6) == 0 ? ligne : noir, 2);
                for (int y = 1; y <= 6; y++) {
                    p.set(c.getX() + dx, SOL + y, c.getZ() + dz);
                    tsu.setBlock(p, bord && y <= 4 ? mur : air, 2);
                }
            }
        }
        BlockState poteau = Blocks.DARK_OAK_LOG.defaultBlockState();
        for (int y = 1; y <= 5; y++) {
            tsu.setBlock(c.above(y), poteau, 2);
        }
        BlockState traverse = Blocks.DARK_OAK_LOG.defaultBlockState().setValue(RotatedPillarBlock.AXIS, Direction.Axis.X);
        for (int dx = -3; dx <= 3; dx++) {
            if (dx != 0) {
                tsu.setBlock(c.offset(dx, 3, 0), traverse, 2);
            }
        }
        CONSTRUITES.add(place);
    }

    // ================================================================ dans le genjutsu : rien à casser, la cible ne frappe pas

    @SubscribeEvent
    public static void casser(BlockEvent.BreakEvent event) {
        if (event.getPlayer() != null && event.getPlayer().level().dimension() == DIMENSION && !event.getPlayer().isCreative()) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void frapper(AttackEntityEvent event) {
        if (attache(event.getEntity().getUUID())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void objet(PlayerInteractEvent.RightClickItem event) {
        if (attache(event.getEntity().getUUID())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void deconnexion(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer joueur) {
            finirPour(joueur);
        }
    }

    @SubscribeEvent
    public static void mort(LivingDeathEvent event) {
        if (event.getEntity() instanceof ServerPlayer joueur) {
            finirPour(joueur);
        }
    }

    /** Quelqu'un se reconnecte dans le genjutsu (le serveur s'est arrêté pendant) : il revient au point d'apparition. */
    @SubscribeEvent
    public static void connexion(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer p && p.level().dimension() == DIMENSION && dans(p.getUUID()) == null) {
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
        for (Seance s : new ArrayList<>(SEANCES.values())) {
            finir(event.getServer(), s);
        }
        SEANCES.clear();
        DERNIER.clear();
        CONSTRUITES.clear();
    }

    private Genjutsu() {
    }
}
