package fr.cabane.pouvoir;

import fr.cabane.Cabane;
import fr.cabane.client.pouvoir.ClientKaguyaDino;
import fr.cabane.hub.Protection;
import io.netty.buffer.ByteBuf;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Abilities;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingFallEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * L'ulti de Kaguya (niveau 3, touche J), validé sur l'aperçu docs/kaguya/ulti : elle plaque la main au sol, un énorme
 * T-rex en os sort de terre sous elle (elle debout sur son crâne), rugit (8 et ralenti 3 s à 12 blocs), puis charge
 * pendant 6 s là où elle regarde. Il avale tout ce qui bouge devant lui (6, coincés dans son ventre) et piétine ce qui est
 * à côté (14 et recul). Elle saute de son crâne et il explose : 24 à ceux qui sont dans son ventre, 12 autour (10 blocs).
 * Rien n'est cassé pour de vrai : les blocs sur son passage s'envolent, puis tout revient à sa place (même si le serveur
 * s'arrête entre-temps). Une fois par éveil 3, puis 5 minutes de recharge.
 */
@EventBusSubscriber(modid = Cabane.MODID)
public final class KaguyaDino {
    public static final int RECHARGE = 5 * 60 * 20;
    private static final float ERUPTION = 6, RUGISSEMENT = 8, AVALE = 6, PIETINE = 14, EXPLO_DEDANS = 24, EXPLO_AUTOUR = 12;
    private static final double RAYON_RUGISSEMENT = 12, RAYON_EXPLO = 10;
    /** Elle tourne la tête, il suit (au plus tant de degrés par tick). */
    private static final float VIRAGE = 3.5F;
    private static final int MAX_AVALES = 16;
    /** Les blocs se remettent en place un peu après T_REPARE (le temps qu'ils reviennent en volant chez chacun). */
    public static final double REMIS = DinoPose.T_REPARE + 1.2;

    private static final class Dino {
        final ServerPlayer kaguya;
        final ServerLevel monde;
        final int debut;
        Vec3 pos;
        float lacet;
        double distance;
        double sol;
        int mord = -1;
        final List<Integer> avales = new ArrayList<>();
        final Set<Integer> pietines = new HashSet<>();
        boolean jailli, rugi, saute, explose, remis;

        Dino(ServerPlayer kaguya) {
            this.kaguya = kaguya;
            this.monde = kaguya.serverLevel();
            this.debut = kaguya.server.getTickCount();
            this.lacet = kaguya.getYRot();
            Vec3 devant = Vec3.directionFromRotation(0, lacet);
            Vec3 p = kaguya.position().subtract(devant.scale(DinoPose.RECUL));
            this.sol = kaguya.getY();
            this.pos = new Vec3(p.x, sol, p.z);
        }

        double t() {
            return (kaguya.server.getTickCount() - debut) / 20.0;
        }
    }

    private static final Map<UUID, Dino> DINOS = new HashMap<>();
    private static final Map<UUID, Integer> PRET = new HashMap<>();
    /** Ceux qui ont déjà lancé leur ulti pendant cet éveil 3. */
    private static final Set<UUID> FAIT = new HashSet<>();
    /** Ceux qui retombent après l'ulti (pas de dégâts de chute avant d'avoir touché le sol). */
    private static final Set<UUID> SANS_CHUTE = new HashSet<>();

    public static boolean actif(ServerPlayer joueur) {
        return DINOS.containsKey(joueur.getUUID());
    }

    /** Cette entité est coincée dans le ventre d'un dinosaure. */
    public static boolean avale(Entity e) {
        for (Dino d : DINOS.values()) {
            if (d.avales.contains(e.getId())) {
                return true;
            }
        }
        return false;
    }

    static void nouvelEveil(ServerPlayer joueur) {
        FAIT.remove(joueur.getUUID());
    }

    // ================================================================ réseau

    /** Serveur → ceux qui sont autour : où en est le dinosaure (âge en ticks, -1 : il disparaît d'un coup). */
    public record Etat(UUID joueur, int age, Vector3f pos, float lacet, float distance, int mord) implements CustomPacketPayload {
        public static final Type<Etat> TYPE = new Type<>(Cabane.id("kaguya_dino"));
        public static final StreamCodec<ByteBuf, Etat> CODEC = StreamCodec.composite(
                UUIDUtil.STREAM_CODEC, Etat::joueur, ByteBufCodecs.VAR_INT, Etat::age, ByteBufCodecs.VECTOR3F, Etat::pos, ByteBufCodecs.FLOAT, Etat::lacet,
                ByteBufCodecs.FLOAT, Etat::distance, ByteBufCodecs.VAR_INT, Etat::mord, Etat::new);

        @Override
        public Type<Etat> type() {
            return TYPE;
        }
    }

    /** Serveur → ceux qui sont autour : des blocs qui s'envolent (ils reviendront à leur place). */
    public record Blocs(UUID joueur, List<BlockPos> pos, List<Integer> etats) implements CustomPacketPayload {
        public static final Type<Blocs> TYPE = new Type<>(Cabane.id("kaguya_blocs"));
        public static final StreamCodec<ByteBuf, Blocs> CODEC = StreamCodec.composite(
                UUIDUtil.STREAM_CODEC, Blocs::joueur, BlockPos.STREAM_CODEC.apply(ByteBufCodecs.list()), Blocs::pos,
                ByteBufCodecs.VAR_INT.apply(ByteBufCodecs.list()), Blocs::etats, Blocs::new);

        @Override
        public Type<Blocs> type() {
            return TYPE;
        }
    }

    @SubscribeEvent
    public static void reseau(RegisterPayloadHandlersEvent event) {
        event.registrar("1")
                .playToClient(Etat.TYPE, Etat.CODEC, (p, ctx) -> ctx.enqueueWork(() -> ClientKaguyaDino.etat(p)))
                .playToClient(Blocs.TYPE, Blocs.CODEC, (p, ctx) -> ctx.enqueueWork(() -> ClientKaguyaDino.blocs(p)));
    }

    private static void envoyer(ServerLevel monde, CustomPacketPayload paquet, Vec3 autour) {
        for (ServerPlayer p : monde.players()) {
            if (p.distanceToSqr(autour) < 220 * 220) {
                PacketDistributor.sendToPlayer(p, paquet);
            }
        }
    }

    // ================================================================ lancer, arrêter

    static void lancer(ServerPlayer joueur) {
        if (actif(joueur)) {
            return;
        }
        if (FAIT.contains(joueur.getUUID())) {
            joueur.displayClientMessage(Component.literal("Le dinosaure d'os est déjà sorti pendant cet éveil.").withStyle(ChatFormatting.GRAY), true);
            return;
        }
        int reste = PRET.getOrDefault(joueur.getUUID(), 0) - joueur.server.getTickCount();
        if (reste > 0) {
            joueur.displayClientMessage(Component.literal("Le dinosaure d'os se reforme… " + (reste + 19) / 20 + " s").withStyle(ChatFormatting.GRAY), true);
            return;
        }
        if (!joueur.onGround()) {
            joueur.displayClientMessage(Component.literal("Pose-toi au sol pour faire sortir le dinosaure.").withStyle(ChatFormatting.GRAY), true);
            return;
        }
        FAIT.add(joueur.getUUID());
        PRET.put(joueur.getUUID(), joueur.server.getTickCount() + RECHARGE);
        Dino d = new Dino(joueur);
        DINOS.put(joueur.getUUID(), d);
        Kaguya.son(d.monde, joueur.position(), SoundEvents.BONE_BLOCK_BREAK, 2F, 0.4F);
        envoyerEtat(d, 0);
    }

    /** Elle s'endort, meurt ou s'en va pendant l'ulti : tout s'arrête net, ceux qui étaient dedans sont lâchés, les blocs reviennent. */
    static void arreter(ServerPlayer joueur) {
        Dino d = DINOS.remove(joueur.getUUID());
        if (d == null) {
            return;
        }
        relacher(d, false);
        voler(joueur, false);
        SANS_CHUTE.add(joueur.getUUID());
        envoyer(d.monde, new Etat(joueur.getUUID(), -1, Kaguya.v(d.pos), d.lacet, (float) d.distance, -1), d.pos);
        remettre(joueur.server, joueur.getUUID());
    }

    private static void envoyerEtat(Dino d, int age) {
        envoyer(d.monde, new Etat(d.kaguya.getUUID(), age, Kaguya.v(d.pos), d.lacet, (float) d.distance, d.mord), d.pos);
    }

    private static void voler(ServerPlayer joueur, boolean oui) {
        if (joueur.isCreative() || joueur.isSpectator()) {
            return;
        }
        Abilities a = joueur.getAbilities();
        a.mayfly = oui;
        a.flying = oui;
        joueur.onUpdateAbilities();
        joueur.fallDistance = 0;
    }

    // ================================================================ chaque tick

    @SubscribeEvent
    public static void tick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        for (Dino d : new ArrayList<>(DINOS.values())) {
            if (d.kaguya.isRemoved() || !d.kaguya.isAlive() || d.kaguya.serverLevel() != d.monde) {
                arreter(d.kaguya);
                continue;
            }
            avancer(d);
        }
        SANS_CHUTE.removeIf(u -> {
            ServerPlayer p = server.getPlayerList().getPlayer(u);
            if (p == null) {
                return true;
            }
            p.fallDistance = 0;
            return p.onGround() && !DINOS.containsKey(u);
        });
    }

    private static void avancer(Dino d) {
        double t = d.t();
        int age = d.kaguya.server.getTickCount() - d.debut;
        ServerPlayer k = d.kaguya;
        // elle le dirige du regard : il tourne vers là où elle regarde, lourdement
        if (t >= DinoPose.T_CHARGE && t < DinoPose.T_STOP) {
            d.lacet += Mth.clamp(Mth.wrapDegrees(k.getYRot() - d.lacet), -VIRAGE, VIRAGE);
            double nd = DinoPose.distance(t);
            d.pos = d.pos.add(Vec3.directionFromRotation(0, d.lacet).scale(nd - d.distance));
            d.distance = nd;
        }
        // il suit le sol (doucement)
        double sol = sol(d.monde, d.pos, d.sol);
        d.sol += (sol - d.sol) * 0.25;
        d.pos = new Vec3(d.pos.x, d.sol, d.pos.z);
        Matrix4f[] mat = DinoPose.poser(t, d.distance, 0);
        Vec3 hanches = DinoPose.monde(mat, DinoPose.HANCHES, new Vector3f(), d.pos, d.lacet);
        // elle monte sur son crâne quand il sort, et y reste jusqu'au saut
        if (t >= DinoPose.T_SORT - 0.2 && t < DinoPose.T_SAUTE) {
            voler(k, true);
            Vec3 crane = DinoPose.monde(mat, DinoPose.TETE, DinoPose.CRANE, d.pos, d.lacet);
            if (crane.y > d.sol && t > DinoPose.T_SORT + 0.8 && k.position().distanceTo(crane) > 8) {
                k.teleportTo(crane.x, crane.y, crane.z);
            }
            k.fallDistance = 0;
        }
        // les blocs sur son passage s'envolent (ils reviendront)
        if (t >= DinoPose.T_SORT && t < DinoPose.T_STOP && !d.remis) {
            Vec3 tete = DinoPose.monde(mat, DinoPose.TETE, new Vector3f(0, 0, 4), d.pos, d.lacet);
            arracher(d, hanches, 6.5);
            arracher(d, new Vec3(tete.x, hanches.y, tete.z), 4.5);
        }
        // il jaillit du sol dans une éruption d'os : ceux qui sont autour sont projetés en l'air
        if (!d.jailli && t >= DinoPose.T_SORT) {
            d.jailli = true;
            Vec3 h = DinoPose.monde(DinoPose.poser(DinoPose.T_DEBOUT, 0, 0), DinoPose.HANCHES, new Vector3f(), d.pos, d.lacet);
            Kaguya.son(d.monde, h, SoundEvents.GENERIC_EXPLODE.value(), 3F, 0.5F);
            for (LivingEntity e : d.monde.getEntitiesOfClass(LivingEntity.class, new AABB(h.x - 24, d.sol - 3, h.z - 24, h.x + 24, d.sol + 10, h.z + 24),
                    e -> Kaguya.touchable(d.kaguya, e))) {
                Vec3 loin = e.position().subtract(h.x, d.sol, h.z);
                if (loin.horizontalDistance() > 24) {
                    continue;
                }
                Kaguya.blesser(d.kaguya, e, ERUPTION);
                e.setDeltaMovement(loin.normalize().scale(0.6).add(0, 1.1, 0));
                e.hurtMarked = true;
            }
        }
        if (!d.rugi && t >= DinoPose.T_DEBOUT) {
            d.rugi = true;
            rugir(d, hanches);
        }
        if (t >= DinoPose.T_CHARGE && t < DinoPose.T_STOP) {
            avaler(d, age);
            pietiner(d, hanches);
        }
        // ceux qui sont dans son ventre y restent
        if (!d.explose) {
            for (int i = 0; i < d.avales.size(); i++) {
                Entity e = d.monde.getEntity(d.avales.get(i));
                if (!(e instanceof LivingEntity v) || !v.isAlive()) {
                    continue;
                }
                Vec3 place = DinoPose.monde(mat, DinoPose.HANCHES, DinoPose.ventre(i), d.pos, d.lacet);
                v.teleportTo(place.x, place.y - v.getBbHeight() / 2, place.z);
                v.setDeltaMovement(Vec3.ZERO);
                v.fallDistance = 0;
                if (age % 20 == 0) {
                    v.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, 30, 9, false, false));
                    v.addEffect(new MobEffectInstance(MobEffects.DIG_SLOWDOWN, 30, 4, false, false));
                }
            }
        }
        if (!d.saute && t >= DinoPose.T_SAUTE) {
            d.saute = true;
            voler(k, false);
            SANS_CHUTE.add(k.getUUID());
            k.addEffect(new MobEffectInstance(MobEffects.SLOW_FALLING, 8 * 20, 0, false, false));
            k.setDeltaMovement(Vec3.directionFromRotation(0, d.lacet).scale(-0.5).add(0, 0.7, 0));
            k.hurtMarked = true;
        }
        if (!d.explose && t >= DinoPose.T_EXPLO) {
            d.explose = true;
            exploser(d, mat);
        }
        if (!d.remis && t >= REMIS) {
            d.remis = true;
            remettre(k.server, k.getUUID());
        }
        envoyerEtat(d, age);
        if (t >= DinoPose.T_FIN) {
            DINOS.remove(k.getUUID());
        }
    }

    /** Le sol sous ce point : le premier bloc de terrain en descendant (pas les troncs, ni les feuilles, ni les plantes). */
    private static double sol(ServerLevel monde, Vec3 p, double avant) {
        BlockPos.MutableBlockPos pos = BlockPos.containing(p.x, avant + 12, p.z).mutable();
        for (int i = 0; i < 40; i++) {
            BlockState e = monde.getBlockState(pos);
            if (!e.isAir() && !e.is(BlockTags.LOGS) && !e.is(BlockTags.LEAVES) && !e.canBeReplaced() && !e.getCollisionShape(monde, pos).isEmpty()) {
                return pos.getY() + 1;
            }
            pos.move(0, -1, 0);
        }
        return avant;
    }

    private static void rugir(Dino d, Vec3 hanches) {
        Kaguya.son(d.monde, hanches, SoundEvents.ENDER_DRAGON_GROWL, 3F, 0.6F);
        Kaguya.son(d.monde, hanches, SoundEvents.RAVAGER_ROAR, 3F, 0.5F);
        d.monde.sendParticles(ParticleTypes.EXPLOSION, hanches.x, d.sol + 0.5, hanches.z, 12, 6, 0.3, 6, 0);
        for (LivingEntity e : d.monde.getEntitiesOfClass(LivingEntity.class, new AABB(hanches, hanches).inflate(RAYON_RUGISSEMENT, 30, RAYON_RUGISSEMENT),
                e -> Kaguya.touchable(d.kaguya, e))) {
            Vec3 loin = e.position().subtract(hanches);
            if (loin.horizontalDistance() > RAYON_RUGISSEMENT) {
                continue;
            }
            Kaguya.blesser(d.kaguya, e, RUGISSEMENT);
            e.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 60, 1));
            e.knockback(1.2, -loin.x, -loin.z);
        }
    }

    /** Ce qui bouge devant lui est avalé (il baisse la tête, la gueule grande ouverte). */
    private static void avaler(Dino d, int age) {
        Vec3 devant = Vec3.directionFromRotation(0, d.lacet);
        Vec3 f = d.pos.add(devant.scale(14 * DinoPose.ECHELLE));
        for (LivingEntity e : d.monde.getEntitiesOfClass(LivingEntity.class, new AABB(f.x - 7, d.sol - 2, f.z - 7, f.x + 7, d.sol + 12, f.z + 7),
                e -> Kaguya.touchable(d.kaguya, e))) {
            if (d.avales.size() >= MAX_AVALES) {
                return;
            }
            Vec3 loin = e.position().subtract(f);
            if (loin.horizontalDistance() > 6.5) {
                continue;
            }
            d.avales.add(e.getId());
            d.mord = age;
            Kaguya.blesser(d.kaguya, e, AVALE);
            if (e instanceof Mob m) {
                m.getNavigation().stop();
            }
            if (e instanceof ServerPlayer p) {
                p.displayClientMessage(Component.literal("Tu es coincé dans le ventre du dinosaure d'os !").withStyle(ChatFormatting.RED), true);
            }
            Kaguya.son(d.monde, e.position(), SoundEvents.BONE_BLOCK_BREAK, 2F, 0.5F);
            Kaguya.son(d.monde, e.position(), SoundEvents.PLAYER_ATTACK_CRIT, 1.5F, 0.5F);
        }
    }

    /** Ce qui est à côté de lui est piétiné (une seule fois chacun). */
    private static void pietiner(Dino d, Vec3 hanches) {
        for (LivingEntity e : d.monde.getEntitiesOfClass(LivingEntity.class, new AABB(hanches, hanches).inflate(7.5, 30, 7.5), e -> Kaguya.touchable(d.kaguya, e))) {
            Vec3 loin = e.position().subtract(hanches);
            if (loin.horizontalDistance() > 7.5 || e.getY() > d.sol + 8 || !d.pietines.add(e.getId())) {
                continue;
            }
            Kaguya.blesser(d.kaguya, e, PIETINE);
            e.knockback(2.0, -loin.x, -loin.z);
            e.setDeltaMovement(e.getDeltaMovement().add(0, 0.6, 0));
            e.hurtMarked = true;
        }
    }

    private static void relacher(Dino d, boolean projeter) {
        for (int id : d.avales) {
            if (d.monde.getEntity(id) instanceof LivingEntity v && v.isAlive()) {
                v.removeEffect(MobEffects.WEAKNESS);
                v.removeEffect(MobEffects.DIG_SLOWDOWN);
                v.fallDistance = 0;
                if (projeter) {
                    double a = v.getRandom().nextDouble() * Mth.TWO_PI;
                    v.setDeltaMovement(Math.cos(a) * 1.3, 1.0, Math.sin(a) * 1.3);
                    v.hurtMarked = true;
                }
            }
        }
        d.avales.clear();
    }

    /** BOUM : 24 à ceux qui sont dans son ventre (projetés), 12 autour. Il n'en reste que des os. */
    private static void exploser(Dino d, Matrix4f[] mat) {
        Vec3 ventre = DinoPose.monde(mat, DinoPose.HANCHES, new Vector3f(0, -3.5F, 4), d.pos, d.lacet);
        List<Integer> dedans = new ArrayList<>(d.avales);
        for (int id : dedans) {
            if (d.monde.getEntity(id) instanceof LivingEntity v && v.isAlive()) {
                Kaguya.blesser(d.kaguya, v, EXPLO_DEDANS);
            }
        }
        relacher(d, true);
        for (LivingEntity e : d.monde.getEntitiesOfClass(LivingEntity.class, new AABB(ventre, ventre).inflate(RAYON_EXPLO, 20, RAYON_EXPLO),
                e -> Kaguya.touchable(d.kaguya, e) && !dedans.contains(e.getId()))) {
            Vec3 loin = e.position().subtract(ventre);
            if (loin.length() > RAYON_EXPLO + 6) {
                continue;
            }
            Kaguya.blesser(d.kaguya, e, EXPLO_AUTOUR);
            e.knockback(1.8, -loin.x, -loin.z);
            e.setDeltaMovement(e.getDeltaMovement().add(0, 0.7, 0));
            e.hurtMarked = true;
        }
        d.monde.sendParticles(ParticleTypes.EXPLOSION_EMITTER, ventre.x, ventre.y, ventre.z, 3, 3, 3, 3, 0);
        d.monde.sendParticles(Kaguya.OS_PART, ventre.x, ventre.y, ventre.z, 300, 6, 6, 6, 0.6);
        Kaguya.son(d.monde, ventre, SoundEvents.GENERIC_EXPLODE.value(), 4F, 0.6F);
        Kaguya.son(d.monde, ventre, SoundEvents.BONE_BLOCK_BREAK, 4F, 0.5F);
    }

    // ================================================================ les blocs qui s'envolent, puis reviennent

    /** Les blocs enlevés, à remettre : par Kaguya, dans l'ordre où ils sont partis (gardés dans le monde jusqu'à leur retour). */
    private static final int PAR_TICK = 700;

    private static boolean enlevable(ServerLevel monde, BlockPos pos, BlockState e) {
        if (e.isAir() || !e.getFluidState().isEmpty() || e.hasBlockEntity() || e.is(Blocks.BEDROCK) || e.is(BlockTags.WITHER_IMMUNE)) {
            return false;
        }
        float dur = e.getDestroySpeed(monde, pos);
        return dur >= 0 && dur < 50 && !Protection.protege(monde, pos, e);
    }

    private static void arracher(Dino d, Vec3 centre, double rayon) {
        Reparer rep = Reparer.get(d.monde.getServer());
        List<BlockPos> partis = new ArrayList<>();
        List<Integer> etats = new ArrayList<>();
        int r = (int) Math.ceil(rayon), bas = (int) Math.floor(d.sol), haut = bas + 18;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int x = -r; x <= r && partis.size() < PAR_TICK; x++) {
            for (int z = -r; z <= r && partis.size() < PAR_TICK; z++) {
                if (x * x + z * z > rayon * rayon) {
                    continue;
                }
                for (int y = bas; y < haut && partis.size() < PAR_TICK; y++) {
                    pos.set(Mth.floor(centre.x) + x, y, Mth.floor(centre.z) + z);
                    BlockState e = d.monde.getBlockState(pos);
                    if (!enlevable(d.monde, pos, e)) {
                        continue;
                    }
                    BlockPos fixe = pos.immutable();
                    rep.ajouter(d.kaguya.getUUID(), d.monde.dimension(), fixe, e);
                    d.monde.setBlock(fixe, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
                    partis.add(fixe);
                    etats.add(Block.getId(e));
                }
            }
        }
        if (!partis.isEmpty()) {
            envoyer(d.monde, new Blocs(d.kaguya.getUUID(), partis, etats), centre);
            if (d.monde.getRandom().nextInt(3) == 0) {
                Kaguya.son(d.monde, centre, SoundEvents.GENERIC_EXPLODE.value(), 1.2F, 1.4F);
            }
        }
    }

    /** Tous les blocs partis à cause de ce Kaguya reviennent à leur place. */
    private static void remettre(MinecraftServer server, UUID qui) {
        Reparer rep = Reparer.get(server);
        rep.remettre(server, qui);
    }

    /** Les blocs à remettre, gardés dans le monde : si le serveur s'arrête avant leur retour, ils reviennent au démarrage. */
    public static final class Reparer extends SavedData {
        private static final String NOM = "cabane_kaguya_blocs";

        private record Bloc(UUID qui, ResourceKey<Level> monde, BlockPos pos, BlockState etat) {
        }

        private final List<Bloc> blocs = new ArrayList<>();

        static Reparer get(MinecraftServer server) {
            return server.overworld().getDataStorage().computeIfAbsent(new SavedData.Factory<>(Reparer::new, Reparer::charger, null), NOM);
        }

        private static Reparer charger(CompoundTag tag, HolderLookup.Provider registres) {
            Reparer r = new Reparer();
            for (Tag t : tag.getList("blocs", Tag.TAG_COMPOUND)) {
                CompoundTag b = (CompoundTag) t;
                ResourceKey<Level> monde = ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(b.getString("monde")));
                BlockState etat = NbtUtils.readBlockState(registres.lookupOrThrow(Registries.BLOCK), b.getCompound("etat"));
                r.blocs.add(new Bloc(b.getUUID("qui"), monde, BlockPos.of(b.getLong("pos")), etat));
            }
            return r;
        }

        @Override
        public CompoundTag save(CompoundTag tag, HolderLookup.Provider registres) {
            ListTag l = new ListTag();
            for (Bloc b : blocs) {
                CompoundTag t = new CompoundTag();
                t.putUUID("qui", b.qui());
                t.putString("monde", b.monde().location().toString());
                t.putLong("pos", b.pos().asLong());
                t.put("etat", NbtUtils.writeBlockState(b.etat()));
                l.add(t);
            }
            tag.put("blocs", l);
            return tag;
        }

        void ajouter(UUID qui, ResourceKey<Level> monde, BlockPos pos, BlockState etat) {
            blocs.add(new Bloc(qui, monde, pos, etat));
            setDirty();
        }

        /** Remet les blocs de ce Kaguya (ou tous, qui = null), sans réveiller les voisins (rien ne coule, rien ne tombe). */
        void remettre(MinecraftServer server, UUID qui) {
            boolean change = false;
            for (int i = blocs.size() - 1; i >= 0; i--) {
                Bloc b = blocs.get(i);
                if (qui != null && !qui.equals(b.qui())) {
                    continue;
                }
                ServerLevel monde = server.getLevel(b.monde());
                if (monde != null && monde.getBlockState(b.pos()).canBeReplaced()) {
                    monde.setBlock(b.pos(), b.etat(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
                }
                blocs.remove(i);
                change = true;
            }
            if (change) {
                setDirty();
            }
        }
    }

    // ================================================================ chutes, démarrage, arrêt

    @SubscribeEvent
    public static void chute(LivingFallEvent event) {
        if (event.getEntity() instanceof ServerPlayer p && (SANS_CHUTE.contains(p.getUUID()) || DINOS.containsKey(p.getUUID()))) {
            event.setCanceled(true);
        } else if (event.getEntity() instanceof LivingEntity e && avale(e)) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void demarrage(ServerStartedEvent event) {
        Reparer.get(event.getServer()).remettre(event.getServer(), null);
    }

    @SubscribeEvent
    public static void arret(ServerStoppingEvent event) {
        for (Dino d : new ArrayList<>(DINOS.values())) {
            relacher(d, false);
        }
        DINOS.clear();
        Reparer.get(event.getServer()).remettre(event.getServer(), null);
        PRET.clear();
        FAIT.clear();
        SANS_CHUTE.clear();
    }

    private KaguyaDino() {
    }
}
