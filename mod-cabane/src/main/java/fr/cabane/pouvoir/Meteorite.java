package fr.cabane.pouvoir;

import fr.cabane.Cabane;
import fr.cabane.client.pouvoir.ClientMeteorite;
import fr.cabane.hub.HubData;
import io.netty.buffer.ByteBuf;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.particles.ParticleTypes;
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
import net.minecraft.tags.BlockTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/**
 * La météorite (l'ultime du Rinnegan, Susano niveau 3, touche J, une fois par éveil puis 5 min de recharge ;
 * docs/sharingan/ulti-meteorite.mp4). Il reste au sol et lève le bras : une sphère noire naît très haut au-dessus de là
 * où il regarde (40 blocs). Elle attire tout : le sol est arraché (de vrais trous sur 9 blocs autour, rebouchés 20 s
 * après la fin ; on ne touche ni au village ni aux coffres), et ce qui vit dans la zone (34 blocs) est aspiré, puis
 * pris dans la roche. Une météorite de 44 blocs de large se forme, ses fissures violettes s'allument, il serre le
 * poing : elle explose. Ceux qui y étaient pris sont éjectés (28 dégâts) ; les blocs pleuvent partout autour (6 par
 * bloc reçu, 18 au plus). Lui ne prend rien. Tout ce qu'on voit est dessiné par {@link ClientMeteorite}.
 */
@EventBusSubscriber(modid = Cabane.MODID)
public final class Meteorite {
    static final ResourceKey<DamageType> DEGATS = ResourceKey.create(Registries.DAMAGE_TYPE, Cabane.id("meteorite"));
    /** Le temps (en ticks, depuis le début) : la sphère naît, l'attraction, l'explosion, la fin de la pluie. */
    public static final int NAISSANCE = 12;
    public static final int FIN_ATTRACTION = 120;
    public static final int FISSURES = 128;
    public static final int EXPLOSION = 160;
    public static final int FIN = 260;
    private static final int REBOUCHER = FIN + 20 * 20;
    public static final int RECHARGE = 5 * 60 * 20;
    /** La hauteur de la sphère au-dessus du sol, son rayon final, la zone d'aspiration, le rayon des trous (en blocs). */
    public static final double HAUT = 40;
    public static final double RAYON = 22;
    private static final double ZONE = 34;
    private static final int TROU = 9;
    private static final float DEGATS_PRIS = 2;
    private static final float DEGATS_EXPLOSION = 28;
    private static final float DEGATS_SOUFFLE = 8;
    private static final float DEGATS_BLOC = 6;
    private static final float DEGATS_BLOCS_MAX = 18;
    /** La pluie : combien de blocs, leur poussée vers le haut, la pesanteur (en blocs par seconde, et par seconde²). */
    public static final int ECLATS = 260;
    private static final double MONTEE = 4;
    private static final double PESANTEUR = 30;

    /** Le rayon de la météorite au tick t (elle se resserre un peu juste avant d'exploser). */
    public static double rayon(double t) {
        if (t < NAISSANCE) {
            return 0;
        }
        double k = lisse((t - NAISSANCE) / (FIN_ATTRACTION - NAISSANCE));
        return (2.5 + (RAYON - 2.5) * Math.pow(k, 0.8)) * (1 - 0.07 * lisse((t - FISSURES) / (EXPLOSION - 4 - FISSURES)));
    }

    static double lisse(double u) {
        u = Math.max(0, Math.min(1, u));
        return u * u * (3 - 2 * u);
    }

    /** Un bloc de la pluie : sa direction (depuis le centre), sa vitesse, sa taille, quand et où il touche le sol (en secondes après l'explosion). */
    public record Eclat(Vec3 dir, double vitesse, double taille, double impact, Vec3 point) {
        /** Où il est, tau secondes après l'explosion (il reste planté au sol après l'impact). */
        public Vec3 position(Vec3 c, double tau) {
            double tt = Math.min(tau, impact);
            double r = RAYON * 0.93 + vitesse * tt;
            return c.add(dir.scale(r)).add(0, MONTEE * tt - PESANTEUR / 2 * tt * tt, 0);
        }
    }

    /** La pluie, la même pour le serveur et pour chaque joueur (tirée de la même graine). */
    public static List<Eclat> eclats(long graine, Vec3 c, double sol) {
        Random r = new Random(graine);
        List<Eclat> l = new ArrayList<>(ECLATS);
        for (int k = 0; k < ECLATS; k++) {
            double z = r.nextDouble() * 2 - 1, phi = r.nextDouble() * Math.PI * 2, s = Math.sqrt(1 - z * z);
            Vec3 dir = new Vec3(Math.cos(phi) * s, z, Math.sin(phi) * s);
            double v = 6 + 14 * r.nextDouble(), taille = 1 + 2 * Math.pow(r.nextDouble(), 2);
            // c.y + dir.y (r0 + v τ) + MONTEE τ - g τ² / 2 = sol + taille / 2
            double r0 = RAYON * 0.93, a = -PESANTEUR / 2, b = dir.y * v + MONTEE, cc = c.y + dir.y * r0 - sol - taille / 2;
            double tau = (-b - Math.sqrt(b * b - 4 * a * cc)) / (2 * a);
            Vec3 point = c.add(dir.scale(r0 + v * tau));
            l.add(new Eclat(dir, v, taille, tau, new Vec3(point.x, sol, point.z)));
        }
        return l;
    }

    /** Serveur → ceux qui sont autour : une météorite commence. */
    public record Debut(UUID lanceur, double cx, double cy, double cz, double sol, long graine) implements CustomPacketPayload {
        public static final Type<Debut> TYPE = new Type<>(Cabane.id("meteorite_debut"));
        public static final StreamCodec<ByteBuf, Debut> CODEC = StreamCodec.composite(
                UUIDUtil.STREAM_CODEC, Debut::lanceur, ByteBufCodecs.DOUBLE, Debut::cx, ByteBufCodecs.DOUBLE, Debut::cy,
                ByteBufCodecs.DOUBLE, Debut::cz, ByteBufCodecs.DOUBLE, Debut::sol, ByteBufCodecs.VAR_LONG, Debut::graine, Debut::new);

        @Override
        public Type<Debut> type() {
            return TYPE;
        }
    }

    @SubscribeEvent
    public static void reseau(RegisterPayloadHandlersEvent event) {
        event.registrar("1").playToClient(Debut.TYPE, Debut.CODEC, (p, ctx) -> ctx.enqueueWork(() -> ClientMeteorite.recevoir(p)));
    }

    /** Un bloc arraché : où, ce qu'il était, quand l'arracher. */
    private record Trou(BlockPos pos, BlockState etat, int quand) {
    }

    private static final class EnCours {
        final UUID lanceur;
        final ServerLevel monde;
        final Vec3 c;
        final double sol;
        final int debut;
        final List<Eclat> eclats;
        final List<Trou> trous = new ArrayList<>();
        final Map<Integer, Vec3> pris = new HashMap<>();
        final Map<Integer, Float> recus = new HashMap<>();
        boolean explose;
        int prochainEclat;

        EnCours(UUID lanceur, ServerLevel monde, Vec3 c, double sol, int debut, long graine) {
            this.lanceur = lanceur;
            this.monde = monde;
            this.c = c;
            this.sol = sol;
            this.debut = debut;
            this.eclats = new ArrayList<>(eclats(graine, c, sol));
            this.eclats.sort((a, b) -> Double.compare(a.impact(), b.impact()));
        }
    }

    private static final List<EnCours> EN_COURS = new ArrayList<>();
    /** Les trous à reboucher : le tick où les remettre, et lesquels. */
    private record ARemettre(ServerLevel monde, int quand, List<Trou> trous) {
    }

    private static final List<ARemettre> A_REBOUCHER = new ArrayList<>();
    /** Ceux qui l'ont déjà lancée pendant cet éveil, et jusqu'à quand elle se recharge. */
    private static final Set<UUID> FAITE = new HashSet<>();
    private static final Map<UUID, Integer> RECHARGE_JUSQUA = new HashMap<>();

    static boolean prete(ServerPlayer joueur) {
        return !FAITE.contains(joueur.getUUID()) && joueur.server.getTickCount() >= RECHARGE_JUSQUA.getOrDefault(joueur.getUUID(), 0)
                && !actif(joueur);
    }

    /** Pourquoi elle n'est pas prête (le message en bas de l'écran). */
    static String pourquoiPasPrete(ServerPlayer joueur) {
        if (actif(joueur)) {
            return "Ta météorite est déjà en cours.";
        }
        if (FAITE.contains(joueur.getUUID())) {
            return "La météorite : déjà faite pendant cet éveil.";
        }
        int reste = RECHARGE_JUSQUA.getOrDefault(joueur.getUUID(), 0) - joueur.server.getTickCount();
        return "La météorite se recharge… encore " + (reste / 20 / 60) + " min " + (reste / 20 % 60) + " s";
    }

    /** Sa météorite est en cours (son éveil ne s'endort pas avant la fin). */
    public static boolean actif(ServerPlayer joueur) {
        return EN_COURS.stream().anyMatch(e -> e.lanceur.equals(joueur.getUUID()));
    }

    static void oublierEveil(ServerPlayer joueur) {
        FAITE.remove(joueur.getUUID());
    }

    // ================================================================ lancer

    static void lancer(ServerPlayer lanceur) {
        ServerLevel monde = lanceur.serverLevel();
        if (monde.dimension() == Genjutsu.DIMENSION) {
            return;
        }
        Vec3 oeil = lanceur.getEyePosition(), regard = lanceur.getLookAngle();
        BlockHitResult vise = monde.clip(new ClipContext(oeil, oeil.add(regard.scale(64)), ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, lanceur));
        Vec3 point = vise.getType() == HitResult.Type.BLOCK ? vise.getLocation() : oeil.add(regard.scale(40));
        // pas sur lui : au moins 20 blocs devant
        Vec3 plat = new Vec3(point.x - lanceur.getX(), 0, point.z - lanceur.getZ());
        if (plat.length() < 20) {
            Vec3 devant = new Vec3(regard.x, 0, regard.z);
            devant = devant.lengthSqr() < 1e-4 ? Pouvoirs.devant(lanceur) : devant.normalize();
            point = lanceur.position().add(devant.scale(20));
        }
        int gx = (int) Math.floor(point.x), gz = (int) Math.floor(point.z);
        int gy = monde.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, gx, gz);
        Vec3 c = new Vec3(gx + 0.5, gy + HAUT, gz + 0.5);
        int maintenant = lanceur.server.getTickCount();
        long graine = monde.getRandom().nextLong();
        EnCours e = new EnCours(lanceur.getUUID(), monde, c, gy, maintenant, graine);
        preparerTrous(e, gx, gy, gz);
        EN_COURS.add(e);
        FAITE.add(lanceur.getUUID());
        RECHARGE_JUSQUA.put(lanceur.getUUID(), maintenant + RECHARGE);
        Debut paquet = new Debut(lanceur.getUUID(), c.x, c.y, c.z, gy, graine);
        for (ServerPlayer p : monde.players()) {
            if (p.position().distanceToSqr(c) < 300 * 300) {
                PacketDistributor.sendToPlayer(p, paquet);
            }
        }
        monde.playSound(null, lanceur.getX(), lanceur.getY(), lanceur.getZ(), SoundEvents.BEACON_ACTIVATE, SoundSource.PLAYERS, 2F, 0.5F);
        monde.playSound(null, c.x, c.y, c.z, SoundEvents.WARDEN_SONIC_CHARGE, SoundSource.PLAYERS, 6F, 0.5F);
        lanceur.displayClientMessage(Component.literal("Chibaku Tensei…").withStyle(ChatFormatting.LIGHT_PURPLE, ChatFormatting.ITALIC), true);
    }

    /** Ce que la météorite arrache vraiment : le dessus du sol naturel (terre, herbe, pierre, sable, gravier, feuilles), pas le village, pas les coffres. */
    private static boolean arrachable(ServerLevel monde, BlockPos pos, BlockState etat) {
        if (etat.isAir() || monde.getBlockEntity(pos) != null || village(monde, pos) || etat.getDestroySpeed(monde, pos) < 0) {
            return false;
        }
        return etat.is(BlockTags.DIRT) || etat.is(BlockTags.SAND) || etat.is(Blocks.GRAVEL) || etat.is(BlockTags.BASE_STONE_OVERWORLD)
                || etat.is(BlockTags.LEAVES) || etat.is(Blocks.SNOW) || etat.is(Blocks.SNOW_BLOCK) || etat.is(Blocks.CLAY)
                || etat.canBeReplaced();
    }

    /** Le village (160 blocs autour de son centre, dans le monde normal) : la météorite n'y arrache rien. */
    private static boolean village(ServerLevel monde, BlockPos pos) {
        BlockPos c = HubData.get(monde.getServer()).centre();
        return c != null && monde.dimension() == net.minecraft.world.level.Level.OVERWORLD
                && Math.abs(pos.getX() - c.getX()) < 160 && Math.abs(pos.getZ() - c.getZ()) < 160;
    }

    private static void preparerTrous(EnCours e, int gx, int gy, int gz) {
        Random r = new Random(gx * 31L + gz);
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int dx = -TROU; dx <= TROU; dx++) {
            for (int dz = -TROU; dz <= TROU; dz++) {
                double d = Math.hypot(dx, dz);
                if (d > TROU || r.nextDouble() > 0.85 - 0.4 * (d / TROU)) {
                    continue;
                }
                int haut = e.monde.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, gx + dx, gz + dz);
                // du bord vers le milieu, petit à petit
                int quand = NAISSANCE + (int) ((1 - d / TROU) * 70) + r.nextInt(25);
                int profondeur = d < TROU * 0.5 ? 3 : 2;
                for (int y = haut + 1; y >= haut - profondeur; y--) {
                    p.set(gx + dx, y, gz + dz);
                    BlockState etat = e.monde.getBlockState(p);
                    if (y > haut - 1 && etat.isAir()) {
                        continue;
                    }
                    if (!arrachable(e.monde, p, etat)) {
                        break;
                    }
                    e.trous.add(new Trou(p.immutable(), etat, quand + (haut - y) * 3));
                }
            }
        }
    }

    // ================================================================ le temps

    @SubscribeEvent
    public static void tick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        int maintenant = server.getTickCount();
        A_REBOUCHER.removeIf(en -> {
            if (en.quand() > maintenant) {
                return false;
            }
            reboucher(en.monde(), en.trous());
            return true;
        });
        if (EN_COURS.isEmpty()) {
            return;
        }
        for (EnCours e : new ArrayList<>(EN_COURS)) {
            int t = maintenant - e.debut;
            ServerPlayer lanceur = server.getPlayerList().getPlayer(e.lanceur);
            // le sol s'arrache
            for (Trou trou : e.trous) {
                if (trou.quand() == t && e.monde.getBlockState(trou.pos()).equals(trou.etat())) {
                    e.monde.setBlock(trou.pos(), Blocks.AIR.defaultBlockState(), 2 | 16);
                }
            }
            if (t >= NAISSANCE && t < EXPLOSION) {
                aspirer(e, lanceur, t);
            }
            if (t == FISSURES) {
                e.monde.playSound(null, e.c.x, e.c.y, e.c.z, SoundEvents.WARDEN_SONIC_CHARGE, SoundSource.PLAYERS, 8F, 0.7F);
            }
            if (t >= EXPLOSION && !e.explose) {
                e.explose = true;
                exploser(e, lanceur);
            }
            if (e.explose) {
                pleuvoir(e, lanceur, (t - EXPLOSION) / 20.0);
            }
            if (t >= FIN) {
                EN_COURS.remove(e);
                A_REBOUCHER.add(new ARemettre(e.monde, maintenant + (REBOUCHER - FIN), e.trous));
            }
        }
    }

    private static DamageSource source(EnCours e, ServerPlayer lanceur) {
        var type = e.monde.registryAccess().registryOrThrow(Registries.DAMAGE_TYPE).getHolderOrThrow(DEGATS);
        return lanceur == null ? new DamageSource(type) : new DamageSource(type, lanceur);
    }

    private static boolean touchable(EnCours e, ServerPlayer lanceur, LivingEntity v) {
        if (v.getUUID().equals(e.lanceur) || !v.isAlive() || v instanceof Player p && (p.isCreative() || p.isSpectator())) {
            return false;
        }
        return lanceur == null || Pouvoirs.cible(lanceur, v);
    }

    /** Ce qui vit dans la zone est aspiré vers la sphère ; arrivé à sa surface, c'est pris dans la roche. */
    private static void aspirer(EnCours e, ServerPlayer lanceur, int t) {
        double r = rayon(t);
        AABB zone = new AABB(e.c.x - ZONE, e.sol - 4, e.c.z - ZONE, e.c.x + ZONE, e.c.y + RAYON + 4, e.c.z + ZONE);
        for (LivingEntity v : e.monde.getEntitiesOfClass(LivingEntity.class, zone, x -> touchable(e, lanceur, x))) {
            Vec3 dir = e.pris.get(v.getId());
            if (dir != null) {
                Vec3 p = e.c.add(dir.scale(r + 0.6)).subtract(0, v.getBbHeight() / 2, 0);
                v.teleportTo(p.x, p.y, p.z);
                v.setDeltaMovement(Vec3.ZERO);
                v.fallDistance = 0;
                if (t % 20 == 0) {
                    v.hurt(source(e, lanceur), DEGATS_PRIS);
                }
                continue;
            }
            if (t < NAISSANCE + 15 || new Vec3(v.getX() - e.c.x, 0, v.getZ() - e.c.z).length() > ZONE) {
                continue;
            }
            Vec3 vers = e.c.subtract(v.position().add(0, v.getBbHeight() / 2, 0));
            if (vers.length() < r + 1.5) {
                e.pris.put(v.getId(), vers.scale(-1).normalize());
                e.monde.playSound(null, v.getX(), v.getY(), v.getZ(), SoundEvents.STONE_PLACE, SoundSource.PLAYERS, 2F, 0.5F);
                continue;
            }
            Vec3 vit = v.getDeltaMovement().add(vers.normalize().scale(0.11));
            if (vit.length() > 1.3) {
                vit = vit.normalize().scale(1.3);
            }
            v.setDeltaMovement(vit);
            v.hurtMarked = true;
            v.fallDistance = 0;
        }
    }

    /** BOUM : ceux qui étaient pris sont éjectés, ceux qui étaient près sont soufflés. */
    private static void exploser(EnCours e, ServerPlayer lanceur) {
        DamageSource source = source(e, lanceur);
        for (Map.Entry<Integer, Vec3> pris : e.pris.entrySet()) {
            if (e.monde.getEntity(pris.getKey()) instanceof LivingEntity v && v.isAlive()) {
                v.hurt(source, DEGATS_EXPLOSION);
                v.setDeltaMovement(pris.getValue().scale(2.2).add(0, 0.9, 0));
                v.hurtMarked = true;
                v.fallDistance = 0;
            }
        }
        AABB pres = new AABB(e.c, e.c).inflate(RAYON + 8);
        for (LivingEntity v : e.monde.getEntitiesOfClass(LivingEntity.class, pres, x -> touchable(e, lanceur, x) && !e.pris.containsKey(x.getId()))) {
            Vec3 loin = v.position().subtract(e.c);
            if (loin.length() < RAYON + 8) {
                v.hurt(source, DEGATS_SOUFFLE);
                v.setDeltaMovement(v.getDeltaMovement().add(loin.normalize().scale(1.4)));
                v.hurtMarked = true;
            }
        }
        e.pris.clear();
        e.monde.playSound(null, e.c.x, e.c.y, e.c.z, SoundEvents.GENERIC_EXPLODE.value(), SoundSource.PLAYERS, 16F, 0.5F);
        e.monde.playSound(null, e.c.x, e.c.y, e.c.z, SoundEvents.LIGHTNING_BOLT_THUNDER, SoundSource.PLAYERS, 16F, 0.6F);
        e.monde.sendParticles(ParticleTypes.EXPLOSION_EMITTER, e.c.x, e.c.y, e.c.z, 6, RAYON * 0.4, RAYON * 0.4, RAYON * 0.4, 0);
    }

    /** Les blocs retombent : chacun, à son impact, fait mal à ce qui est dessous. */
    private static void pleuvoir(EnCours e, ServerPlayer lanceur, double tau) {
        while (e.prochainEclat < e.eclats.size() && e.eclats.get(e.prochainEclat).impact() <= tau) {
            Eclat ec = e.eclats.get(e.prochainEclat++);
            Vec3 p = ec.point();
            double portee = ec.taille() / 2 + 1.2;
            AABB zone = new AABB(p.x - portee, p.y - 1, p.z - portee, p.x + portee, p.y + 3, p.z + portee);
            for (LivingEntity v : e.monde.getEntitiesOfClass(LivingEntity.class, zone, x -> touchable(e, lanceur, x))) {
                float deja = e.recus.getOrDefault(v.getId(), 0F);
                if (deja >= DEGATS_BLOCS_MAX) {
                    continue;
                }
                float coup = Math.min(DEGATS_BLOC, DEGATS_BLOCS_MAX - deja);
                v.invulnerableTime = 0;
                v.hurt(source(e, lanceur), coup);
                e.recus.put(v.getId(), deja + coup);
            }
            if (e.prochainEclat % 4 == 0) {
                e.monde.playSound(null, p.x, p.y, p.z, SoundEvents.STONE_BREAK, SoundSource.PLAYERS, 2.5F, 0.6F);
            }
            if (e.prochainEclat % 9 == 0) {
                e.monde.playSound(null, p.x, p.y, p.z, SoundEvents.GENERIC_EXPLODE.value(), SoundSource.PLAYERS, 1.5F, 0.9F);
            }
        }
    }

    /** Les trous se rebouchent (là où personne n'a rien posé entre-temps). */
    private static void reboucher(ServerLevel monde, List<Trou> trous) {
        // du fond vers le haut : la terre d'abord, l'herbe et les fleurs par-dessus
        for (int i = trous.size() - 1; i >= 0; i--) {
            Trou t = trous.get(i);
            if (monde.getBlockState(t.pos()).isAir()) {
                monde.setBlock(t.pos(), t.etat(), 2);
            }
        }
    }

    @SubscribeEvent
    public static void arret(ServerStoppingEvent event) {
        for (EnCours e : EN_COURS) {
            reboucher(e.monde, e.trous);
        }
        for (ARemettre en : A_REBOUCHER) {
            reboucher(en.monde(), en.trous());
        }
        EN_COURS.clear();
        A_REBOUCHER.clear();
        FAITE.clear();
        RECHARGE_JUSQUA.clear();
    }

    private Meteorite() {
    }
}
