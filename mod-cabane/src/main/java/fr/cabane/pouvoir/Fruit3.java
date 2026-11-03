package fr.cabane.pouvoir;

import fr.cabane.Cabane;
import fr.cabane.client.pouvoir.ClientFruit3;
import io.netty.buffer.ByteBuf;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.Direction;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.RelativeMovement;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import org.joml.Vector3f;

/**
 * Le niveau 3 des fruits du démon (listes « lave3 » et « glace3 », docs/fruits/planche-niveau3.png et
 * planche-ultimes.png, validés le 2026-11-03). La lave (ou la glace) prend tout le corps, sous un casque en tête de
 * dragon (voir ClientFruit3) ; le clic gauche, le clic droit court et l'accroupi (une fois l'attaque ultime faite) restent
 * ceux du niveau 2. En plus :
 * <ul>
 * <li>clic droit maintenu : un dragon géant, sans pattes, sort de sa main et suit son regard tant qu'il reste appuyé
 * (5 s au plus, il avance au pas) ; qui le touche en route prend 8 ; quand il relâche (ou contre un mur), il explose :
 * 5 blocs autour, 20 au milieu, 12 au bord, tout brûle 8 s (ou gèle sur place 4 s). Recharge 20 s ;</li>
 * <li>accroupi : l'attaque ultime, une seule fois par éveil 3, puis 5 minutes de recharge. La glace, le Hyoton : sous
 * la cible visée à mi-distance, un pylône de glace la projette en l'air, des jets de glace la prennent dans une grosse
 * boule, la boule devient un dragon qui la claque au sol, et une tempête de glace monte très haut. La lave, le soleil de
 * magma : il bondit très haut, reste suspendu, une sphère de magma de 48 blocs grossit au-dessus de sa main ; il la lance
 * où il regarde, et elle explose sur 20 blocs.</li>
 * </ul>
 * Tout se passe en jeu, sans cinématique : chacun garde sa caméra. Rien ne reste au sol.
 */
@EventBusSubscriber(modid = Cabane.MODID)
public final class Fruit3 {
    // ================================================================ les réglages

    public static final int RECHARGE_DRAGON = 20 * 20;
    /** Combien de ticks il faut garder le clic droit pour que ce soit le dragon (sinon, le tir du niveau 2). */
    public static final int APPUI_DRAGON = 5;
    private static final int DUREE_DRAGON = 5 * 20;
    private static final double VITESSE_DRAGON = 0.9;
    /** De combien la tête peut tourner par tick pour suivre le regard (il ondule, il ne se retourne pas d'un coup). */
    private static final double VIRAGE = Math.toRadians(11);
    private static final double LARGEUR_DRAGON = 1.4;
    private static final float DEGATS_CONTACT = 8;
    private static final float EXPLOSION_MILIEU = 20;
    private static final float EXPLOSION_BORD = 12;
    private static final double RAYON_DRAGON = 5;
    private static final int GEL_DRAGON = 80;

    public static final int RECHARGE_ULTIME = 5 * 60 * 20;
    /** Le Hyoton : la cible entre 4 et 22 blocs ; le pylône, la boule (son centre au-dessus du point visé), la claque. */
    private static final double PORTEE_HYOTON = 22;
    public static final double HAUT_BOULE = 17;
    private static final float DEGATS_PYLONE = 4;
    private static final float DEGATS_CLAQUE = 24;
    private static final float DEGATS_CLAQUE_AUTOUR = 14;
    private static final double RAYON_CLAQUE = 5;
    public static final double RAYON_TEMPETE = 3.2;
    /** Le soleil de magma : jusqu'où il peut viser, et l'explosion. */
    private static final double PORTEE_SOLEIL = 80;
    private static final float SOLEIL_MILIEU = 30;
    private static final float SOLEIL_BORD = 16;
    private static final double RAYON_SOLEIL = 20;

    /** Les temps des ultimes, en ticks depuis le début (les mêmes que les vidéos docs/fruits/hyoton.mp4 et soleil.mp4). */
    public static final int H_PYLONE = 10, H_BOULE = 26, H_DRAGON = 47, H_CLAQUE = 58, H_FIN_TEMPETE = 140, H_FIN = 150;
    public static final int S_SUSPENDU = 12, S_LANCER = 69, S_IMPACT = 87, S_FIN = 200;

    // ================================================================ réseau

    /** Client → serveur : il appuie (vrai) ou relâche (faux) le clic droit maintenu du dragon. */
    public record Serpent(boolean appuye) implements CustomPacketPayload {
        public static final Type<Serpent> TYPE = new Type<>(Cabane.id("fruit_serpent"));
        public static final StreamCodec<ByteBuf, Serpent> CODEC = StreamCodec.composite(ByteBufCodecs.BOOL, Serpent::appuye, Serpent::new);

        @Override
        public Type<Serpent> type() {
            return TYPE;
        }
    }

    /** Serveur → ceux qui sont autour : où est la tête du dragon de ce joueur à ce tick (etat 0), ou il explose là (1). */
    public record Dragon(int id, UUID joueur, boolean glace, Vector3f tete, Vector3f dir, int etat) implements CustomPacketPayload {
        public static final Type<Dragon> TYPE = new Type<>(Cabane.id("fruit_dragon"));
        public static final StreamCodec<ByteBuf, Dragon> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, Dragon::id, UUIDUtil.STREAM_CODEC, Dragon::joueur, ByteBufCodecs.BOOL, Dragon::glace,
                ByteBufCodecs.VECTOR3F, Dragon::tete, ByteBufCodecs.VECTOR3F, Dragon::dir, ByteBufCodecs.VAR_INT, Dragon::etat, Dragon::new);

        @Override
        public Type<Dragon> type() {
            return TYPE;
        }
    }

    /**
     * Serveur → ceux qui sont autour : l'attaque ultime de ce joueur. Phase 0, elle commence (le Hyoton : le point visé au
     * sol et la cible, -1 sans cible ; le soleil : là où il bondit) ; phase 1, le soleil part vers ce point.
     */
    public record Ultime(UUID joueur, boolean glace, int phase, Vector3f point, int cible) implements CustomPacketPayload {
        public static final Type<Ultime> TYPE = new Type<>(Cabane.id("fruit_ultime"));
        public static final StreamCodec<ByteBuf, Ultime> CODEC = StreamCodec.composite(
                UUIDUtil.STREAM_CODEC, Ultime::joueur, ByteBufCodecs.BOOL, Ultime::glace, ByteBufCodecs.VAR_INT, Ultime::phase,
                ByteBufCodecs.VECTOR3F, Ultime::point, ByteBufCodecs.VAR_INT, Ultime::cible, Ultime::new);

        @Override
        public Type<Ultime> type() {
            return TYPE;
        }
    }

    @SubscribeEvent
    public static void reseau(RegisterPayloadHandlersEvent event) {
        event.registrar("1")
                .playToServer(Serpent.TYPE, Serpent.CODEC, (p, ctx) -> ctx.enqueueWork(() -> {
                    if (ctx.player() instanceof ServerPlayer joueur) {
                        serpent(joueur, p.appuye());
                    }
                }))
                .playToClient(Dragon.TYPE, Dragon.CODEC, (p, ctx) -> ctx.enqueueWork(() -> ClientFruit3.dragon(p)))
                .playToClient(Ultime.TYPE, Ultime.CODEC, (p, ctx) -> ctx.enqueueWork(() -> ClientFruit3.ultime(p)));
    }

    /** À tous ceux qui sont à moins de 200 blocs (la tempête et le champignon se voient de loin). */
    private static void envoyer(ServerLevel monde, Vec3 autour, CustomPacketPayload paquet) {
        for (ServerPlayer p : monde.players()) {
            if (p.distanceToSqr(autour) < 200 * 200) {
                PacketDistributor.sendToPlayer(p, paquet);
            }
        }
    }

    private static void son(ServerLevel monde, Vec3 p, SoundEvent son, float volume, float hauteur) {
        monde.playSound(null, p.x, p.y, p.z, son, SoundSource.PLAYERS, volume, hauteur);
    }

    /**
     * Déplace une entité là, sans toucher à son regard : le serveur garde la rotation qu'on lui donne et n'envoie au
     * joueur que l'écart (relatif) ; on lui donne donc la sienne.
     */
    private static void placer(Entity e, Vec3 p) {
        if (e instanceof ServerPlayer j) {
            j.connection.teleport(p.x, p.y, p.z, j.getYRot(), j.getXRot(), EnumSet.of(RelativeMovement.X_ROT, RelativeMovement.Y_ROT));
        } else {
            e.teleportTo(p.x, p.y, p.z);
        }
        e.setDeltaMovement(Vec3.ZERO);
        e.fallDistance = 0;
    }

    /** Les cibles vivantes à moins de r de ce point (à qui ce joueur peut faire mal). */
    private static List<LivingEntity> autour(ServerPlayer joueur, ServerLevel monde, Vec3 p, double r) {
        return monde.getEntitiesOfClass(LivingEntity.class, new AABB(p, p).inflate(r + 1),
                e -> Pouvoirs.cible(joueur, e) && !e.isSpectator() && e.getBoundingBox().getCenter().distanceTo(p) < r + 0.5);
    }

    // ================================================================ le dragon guidé

    private static final class Serpent3 {
        final int id;
        final UUID joueur;
        final ServerLevel monde;
        final boolean glace;
        final Set<Integer> touches = new HashSet<>();
        Vec3 tete;
        Vec3 dir;
        int age;
        boolean lache;

        Serpent3(int id, ServerPlayer joueur, boolean glace, Vec3 tete, Vec3 dir) {
            this.id = id;
            this.joueur = joueur.getUUID();
            this.monde = joueur.serverLevel();
            this.glace = glace;
            this.tete = tete;
            this.dir = dir.normalize();
        }
    }

    private static final Map<UUID, Serpent3> SERPENTS = new HashMap<>();
    private static final Map<UUID, Integer> PRET_DRAGON = new HashMap<>();
    private static int prochainDragon;

    /** Il appuie : le dragon sort de sa main (s'il est prêt) ; il relâche : il explose où il est. */
    private static void serpent(ServerPlayer joueur, boolean appuye) {
        UUID u = joueur.getUUID();
        if (!appuye) {
            Serpent3 s = SERPENTS.get(u);
            if (s != null) {
                s.lache = true;
            }
            return;
        }
        int maintenant = joueur.server.getTickCount();
        if (Fruit.niveau(joueur) < 3 || SERPENTS.containsKey(u) || maintenant < PRET_DRAGON.getOrDefault(u, 0)) {
            return;
        }
        boolean glace = Fruit.glace(Pouvoirs.pouvoir(joueur));
        Vec3 regard = joueur.getLookAngle();
        SERPENTS.put(u, new Serpent3(prochainDragon++, joueur, glace, Fruit.main(joueur, glace).add(regard.scale(0.5)), regard));
        Vec3 p = joueur.getEyePosition();
        son(joueur.serverLevel(), p, SoundEvents.ENDER_DRAGON_GROWL, 1.6F, glace ? 1.5F : 1.2F);
        if (glace) {
            son(joueur.serverLevel(), p, SoundEvents.AMETHYST_BLOCK_RESONATE, 1.6F, 0.5F);
            son(joueur.serverLevel(), p, SoundEvents.POWDER_SNOW_BREAK, 1.4F, 0.5F);
        } else {
            son(joueur.serverLevel(), p, SoundEvents.BLAZE_SHOOT, 1.6F, 0.4F);
            son(joueur.serverLevel(), p, SoundEvents.FIRECHARGE_USE, 1.4F, 0.5F);
        }
    }

    /** Tourne a vers b d'au plus max (en radians), autour de l'axe a × b. */
    private static Vec3 tourner(Vec3 a, Vec3 b, double max) {
        double angle = Math.acos(Mth.clamp(a.dot(b), -1, 1));
        if (angle <= max) {
            return b;
        }
        Vec3 axe = a.cross(b);
        if (axe.lengthSqr() < 1e-8) {
            axe = Math.abs(a.y) < 0.9 ? a.cross(new Vec3(0, 1, 0)) : a.cross(new Vec3(1, 0, 0));
        }
        axe = axe.normalize();
        double c = Math.cos(max), s = Math.sin(max);
        return a.scale(c).add(axe.cross(a).scale(s)).add(axe.scale(axe.dot(a) * (1 - c))).normalize();
    }

    private static void avancer(MinecraftServer server, Serpent3 s) {
        ServerPlayer j = server.getPlayerList().getPlayer(s.joueur);
        if (j == null || j.level() != s.monde || !j.isAlive() || Fruit.niveau(j) < 3) {
            SERPENTS.remove(s.joueur);
            envoyer(s.monde, s.tete, new Dragon(s.id, s.joueur, s.glace, Fruit.v(s.tete), Fruit.v(s.dir), 1));
            return;
        }
        s.age++;
        // la tête vire vers là où il regarde, sans se retourner d'un coup
        s.dir = tourner(s.dir, j.getLookAngle(), VIRAGE);
        Vec3 suivant = s.tete.add(s.dir.scale(VITESSE_DRAGON));
        HitResult bloc = s.monde.clip(new ClipContext(s.tete, suivant, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, j));
        if (bloc instanceof BlockHitResult b && b.getType() != HitResult.Type.MISS && b.getDirection() == Direction.UP && s.dir.y > -0.6) {
            // il frôle le sol : il remonte en rasant, il n'explose pas
            s.dir = new Vec3(s.dir.x, Math.max(0.2, -s.dir.y * 0.5), s.dir.z).normalize();
            suivant = s.tete.add(s.dir.scale(VITESSE_DRAGON));
            bloc = s.monde.clip(new ClipContext(s.tete, suivant, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, j));
        }
        boolean mur = bloc.getType() != HitResult.Type.MISS && s.age > 2;
        Vec3 bout = mur ? bloc.getLocation() : suivant;
        // qui se le prend en route : 8, une fois, et il brûle (ou gèle)
        for (LivingEntity e : s.monde.getEntitiesOfClass(LivingEntity.class, new AABB(s.tete, bout).inflate(LARGEUR_DRAGON + 1),
                e -> Pouvoirs.cible(j, e) && !e.isSpectator())) {
            AABB b = e.getBoundingBox().inflate(LARGEUR_DRAGON);
            if (s.touches.contains(e.getId()) || !b.contains(s.tete) && b.clip(s.tete, bout).isEmpty()) {
                continue;
            }
            s.touches.add(e.getId());
            Fruit.blesser(e, Fruit.source(j, s.glace ? Fruit.GIVRE : Fruit.MAGMA), DEGATS_CONTACT);
            if (s.glace) {
                e.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 60, 2));
                Fruit.givrer(e);
            } else {
                e.igniteForSeconds(4);
            }
        }
        s.tete = bout;
        // il avance au pas tant qu'il le guide
        j.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 6, 2, false, false));
        if (s.lache || mur || s.age >= DUREE_DRAGON) {
            exploser(j, s);
        } else {
            envoyer(s.monde, s.tete, new Dragon(s.id, s.joueur, s.glace, Fruit.v(s.tete), Fruit.v(s.dir), 0));
            if (s.age % 12 == 1) {
                son(s.monde, s.tete, s.glace ? SoundEvents.PHANTOM_FLAP : SoundEvents.BLAZE_BURN, 1.4F, s.glace ? 0.6F : 0.5F);
            }
        }
    }

    /** Le dragon explose : 5 blocs autour, 20 au milieu, 12 au bord ; tout brûle 8 s, ou gèle sur place 4 s. */
    private static void exploser(ServerPlayer j, Serpent3 s) {
        SERPENTS.remove(s.joueur);
        PRET_DRAGON.put(s.joueur, j.server.getTickCount() + RECHARGE_DRAGON);
        Vec3 p = s.tete;
        envoyer(s.monde, p, new Dragon(s.id, s.joueur, s.glace, Fruit.v(p), Fruit.v(s.dir), 1));
        if (s.glace) {
            son(s.monde, p, SoundEvents.GLASS_BREAK, 2.4F, 0.5F);
            son(s.monde, p, SoundEvents.AMETHYST_CLUSTER_BREAK, 2F, 0.6F);
            son(s.monde, p, SoundEvents.POWDER_SNOW_BREAK, 2F, 0.4F);
            son(s.monde, p, SoundEvents.GENERIC_EXPLODE.value(), 1.4F, 1.3F);
        } else {
            son(s.monde, p, SoundEvents.GENERIC_EXPLODE.value(), 2.6F, 0.7F);
            son(s.monde, p, SoundEvents.LAVA_EXTINGUISH, 2F, 0.5F);
            son(s.monde, p, SoundEvents.BLAZE_SHOOT, 1.6F, 0.4F);
        }
        for (LivingEntity e : autour(j, s.monde, p, RAYON_DRAGON)) {
            double d = Math.min(1, e.getBoundingBox().getCenter().distanceTo(p) / RAYON_DRAGON);
            Fruit.blesser(e, Fruit.source(j, s.glace ? Fruit.GIVRE : Fruit.MAGMA), (float) Mth.lerp(d, EXPLOSION_MILIEU, EXPLOSION_BORD));
            if (s.glace) {
                Fruit.geler(e, GEL_DRAGON);
            } else {
                e.igniteForSeconds(8);
                Vec3 dehors = e.position().subtract(p).multiply(1, 0, 1);
                Vec3 v = dehors.lengthSqr() < 0.01 ? s.dir.multiply(1, 0, 1) : dehors.normalize();
                e.setDeltaMovement(e.getDeltaMovement().add(v.x * (1.4 - d), 0.5, v.z * (1.4 - d)));
                e.hurtMarked = true;
            }
        }
    }

    // ================================================================ les attaques ultimes

    private static final class Ultime3 {
        final UUID joueur;
        final ServerLevel monde;
        final boolean glace;
        final int debut;
        /** Le Hyoton : le point au sol sous la cible (ou visé) ; le soleil : où il est suspendu, puis où la sphère tombe. */
        Vec3 point;
        Vec3 zone;
        LivingEntity cible;
        Vec3 depart;

        Ultime3(ServerPlayer joueur, boolean glace, Vec3 point) {
            this.joueur = joueur.getUUID();
            this.monde = joueur.serverLevel();
            this.glace = glace;
            this.debut = joueur.server.getTickCount();
            this.point = point;
        }
    }

    private static final List<Ultime3> ULTIMES = new ArrayList<>();
    /** Quand l'ultime de ce joueur sera prête, pour chaque fruit (« uuid/glace » ou « uuid/lave ») : changer de fruit n'attend pas l'autre. */
    private static final Map<String, Integer> PRET_ULTIME = new HashMap<>();
    /** Ceux qui ont déjà fait leur attaque ultime pendant cet éveil (une seule fois par forme). */
    private static final Set<UUID> FAITES = new HashSet<>();

    /** Accroupi, au niveau 3 : l'attaque ultime, si elle est prête ; vrai si elle part (sinon, c'est l'accroupi du niveau 2). */
    static boolean ultime(ServerPlayer joueur, boolean glace) {
        UUID u = joueur.getUUID();
        int maintenant = joueur.server.getTickCount();
        String cle = u + (glace ? "/glace" : "/lave");
        if (FAITES.contains(u) || maintenant < PRET_ULTIME.getOrDefault(cle, 0) || ULTIMES.stream().anyMatch(x -> x.joueur.equals(u))) {
            return false;
        }
        FAITES.add(u);
        PRET_ULTIME.put(cle, maintenant + RECHARGE_ULTIME);
        ServerLevel monde = joueur.serverLevel();
        if (glace) {
            hyoton(joueur, monde);
        } else {
            Ultime3 x = new Ultime3(joueur, false, joueur.position());
            ULTIMES.add(x);
            envoyer(monde, joueur.position(), new Ultime(u, false, 0, Fruit.v(joueur.position()), -1));
            Vec3 m = joueur.getDeltaMovement();
            joueur.setDeltaMovement(m.x * 0.2, 1.75, m.z * 0.2);
            joueur.hurtMarked = true;
            son(monde, joueur.position(), SoundEvents.GENERIC_EXPLODE.value(), 1.4F, 1.4F);
            son(monde, joueur.position(), SoundEvents.BLAZE_SHOOT, 1.6F, 0.5F);
            son(monde, joueur.position(), SoundEvents.FIRECHARGE_USE, 1.4F, 0.6F);
        }
        return true;
    }

    /** Le Hyoton commence : il colle ses mains et les pousse ; la cible visée (ou le sol à mi-distance) est marquée. */
    private static void hyoton(ServerPlayer joueur, ServerLevel monde) {
        Vec3 oeil = joueur.getEyePosition(), regard = joueur.getLookAngle(), loin = oeil.add(regard.scale(PORTEE_HYOTON));
        HitResult bloc = monde.clip(new ClipContext(oeil, loin, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, joueur));
        Vec3 fin = bloc.getType() == HitResult.Type.MISS ? loin : bloc.getLocation();
        EntityHitResult touche = ProjectileUtil.getEntityHitResult(monde, joueur, oeil, fin, new AABB(oeil, fin).inflate(2),
                e -> e instanceof LivingEntity l && Pouvoirs.cible(joueur, l) && !l.isSpectator() && l.distanceTo(joueur) > 3, 1.5F);
        LivingEntity cible = touche != null && touche.getEntity() instanceof LivingEntity l ? l : null;
        Vec3 point;
        if (cible != null) {
            point = cible.position();
        } else {
            // personne : le sol à mi-distance devant lui (ou là où il vise, si c'est plus près qu'un mur)
            Vec3 devant = bloc.getType() == HitResult.Type.MISS ? oeil.add(regard.scale(14)) : fin;
            if (devant.distanceTo(oeil) < 6) {
                devant = oeil.add(new Vec3(regard.x, 0, regard.z).normalize().scale(12));
            }
            double y = monde.getHeight(Heightmap.Types.MOTION_BLOCKING, Mth.floor(devant.x), Mth.floor(devant.z));
            point = new Vec3(devant.x, Math.abs(y - devant.y) < 24 ? y : devant.y, devant.z);
        }
        Ultime3 x = new Ultime3(joueur, true, point);
        x.cible = cible;
        ULTIMES.add(x);
        envoyer(monde, point, new Ultime(joueur.getUUID(), true, 0, Fruit.v(point), cible == null ? -1 : cible.getId()));
        son(monde, joueur.getEyePosition(), SoundEvents.AMETHYST_BLOCK_RESONATE, 2F, 0.5F);
        son(monde, point, SoundEvents.BEACON_ACTIVATE, 1.6F, 1.6F);
        son(monde, point, SoundEvents.POWDER_SNOW_PLACE, 1.6F, 0.6F);
    }

    private static boolean avancerHyoton(ServerPlayer j, Ultime3 x, int age) {
        LivingEntity c = x.cible != null && x.cible.isAlive() && x.cible.level() == x.monde ? x.cible : null;
        Vec3 boule = x.point.add(0, HAUT_BOULE, 0), dedans = boule.add(0, -0.9, 0);
        if (age == H_PYLONE) {
            son(x.monde, x.point, SoundEvents.GLASS_BREAK, 2.2F, 0.5F);
            son(x.monde, x.point, SoundEvents.PLAYER_HURT_FREEZE, 1.8F, 0.5F);
            son(x.monde, x.point, SoundEvents.POINTED_DRIPSTONE_LAND, 2F, 0.6F);
            if (c != null) {
                Fruit.blesser(c, Fruit.source(j, Fruit.GIVRE), DEGATS_PYLONE);
                c.setDeltaMovement(0, 1.45, 0);
                c.hurtMarked = true;
            }
        }
        if (age == H_PYLONE + 10) {
            son(x.monde, x.point, SoundEvents.AMETHYST_CLUSTER_BREAK, 2F, 0.5F);
            son(x.monde, x.point, SoundEvents.TRIDENT_RIPTIDE_3.value(), 1.8F, 0.7F);
        }
        if (c != null && age > H_PYLONE && age <= H_CLAQUE + 12) {
            c.fallDistance = 0;
        }
        // tirée dans la boule, elle n'en bouge plus ; puis le dragon l'emporte jusqu'au sol
        if (c != null && age >= H_BOULE && age < H_DRAGON) {
            double k = Math.min(1, (age - H_BOULE + 1) / 6.0);
            placer(c, k >= 1 ? dedans : c.position().lerp(dedans, k));
        }
        if (age == H_BOULE + 6) {
            son(x.monde, boule, SoundEvents.AMETHYST_BLOCK_RESONATE, 2.2F, 0.6F);
            son(x.monde, boule, SoundEvents.POWDER_SNOW_BREAK, 2F, 0.5F);
        }
        if (age == H_DRAGON) {
            son(x.monde, boule, SoundEvents.ENDER_DRAGON_GROWL, 2.4F, 1.3F);
        }
        if (c != null && age >= H_DRAGON && age < H_CLAQUE) {
            double k = Math.pow((age - H_DRAGON + 1) / (double) (H_CLAQUE - H_DRAGON), 2);
            placer(c, dedans.lerp(x.point, k));
        }
        if (age == H_CLAQUE) {
            son(x.monde, x.point, SoundEvents.GENERIC_EXPLODE.value(), 3F, 0.6F);
            son(x.monde, x.point, SoundEvents.GLASS_BREAK, 3F, 0.4F);
            son(x.monde, x.point, SoundEvents.AMETHYST_CLUSTER_BREAK, 3F, 0.4F);
            son(x.monde, x.point, SoundEvents.PLAYER_HURT_FREEZE, 2.4F, 0.4F);
            if (c != null) {
                placer(c, x.point);
                Fruit.blesser(c, Fruit.source(j, Fruit.GIVRE), DEGATS_CLAQUE);
                Fruit.geler(c, 60);
            }
            for (LivingEntity e : autour(j, x.monde, x.point.add(0, 1, 0), RAYON_CLAQUE)) {
                if (e != c) {
                    Fruit.blesser(e, Fruit.source(j, Fruit.GIVRE), DEGATS_CLAQUE_AUTOUR);
                    Fruit.geler(e, 60);
                }
            }
        }
        // la tempête : 1 toutes les demi-secondes à qui est dedans, et le givre
        if (age > H_CLAQUE && age <= H_FIN_TEMPETE && age % 10 == 0) {
            for (LivingEntity e : x.monde.getEntitiesOfClass(LivingEntity.class, new AABB(x.point, x.point).inflate(RAYON_TEMPETE, 0, RAYON_TEMPETE)
                    .expandTowards(0, 40, 0).expandTowards(0, -1, 0), e -> Pouvoirs.cible(j, e) && !e.isSpectator())) {
                if (e.position().subtract(x.point).horizontalDistance() < RAYON_TEMPETE) {
                    Fruit.blesser(e, Fruit.source(j, Fruit.GIVRE), 1);
                    e.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 30, 3));
                    Fruit.givrer(e);
                }
            }
            if (age % 20 == 0) {
                son(x.monde, x.point.add(0, 4, 0), SoundEvents.ELYTRA_FLYING, 1.6F, 0.6F);
            }
        }
        return age >= H_FIN;
    }

    private static boolean avancerSoleil(ServerPlayer j, Ultime3 x, int age) {
        if (age <= S_LANCER + 4) {
            j.fallDistance = 0;
        }
        if (age == S_SUSPENDU) {
            x.depart = j.position();
        }
        // il reste suspendu en l'air, main levée, pendant que la sphère grossit : une vitesse qui le ramène à sa place
        // (pas de téléportation : elle ferait attendre ses mouvements, et il doit pouvoir viser)
        if (age >= S_SUSPENDU && age < S_LANCER && x.depart != null) {
            Vec3 d = x.depart.subtract(j.position());
            j.setDeltaMovement(d.x * 0.4, d.y * 0.4, d.z * 0.4);
            j.hurtMarked = true;
            j.fallDistance = 0;
            if (age % 10 == 0) {
                Vec3 haut = x.depart.add(0, 12, 0);
                son(x.monde, haut, SoundEvents.LAVA_AMBIENT, 2F, 0.5F);
                son(x.monde, haut, SoundEvents.BLAZE_BURN, 1.6F, 0.4F);
                son(x.monde, x.point, SoundEvents.LAVA_POP, 1.6F, 0.6F);
            }
        }
        if (age == S_LANCER) {
            // il la lance là où il regarde
            Vec3 oeil = j.getEyePosition(), loin = oeil.add(j.getLookAngle().scale(PORTEE_SOLEIL));
            HitResult bloc = x.monde.clip(new ClipContext(oeil, loin, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, j));
            Vec3 z = bloc.getType() == HitResult.Type.MISS ? loin : bloc.getLocation();
            if (bloc.getType() == HitResult.Type.MISS) {
                double y = x.monde.getHeight(Heightmap.Types.MOTION_BLOCKING, Mth.floor(z.x), Mth.floor(z.z));
                if (Math.abs(y - z.y) < 30) {
                    z = new Vec3(z.x, y, z.z);
                }
            }
            x.zone = z;
            envoyer(x.monde, z, new Ultime(x.joueur, false, 1, Fruit.v(z), -1));
            j.addEffect(new MobEffectInstance(MobEffects.SLOW_FALLING, 200, 0, false, false));
            son(x.monde, j.position(), SoundEvents.BLAZE_SHOOT, 2.4F, 0.3F);
            son(x.monde, j.position(), SoundEvents.ENDER_DRAGON_SHOOT, 2F, 0.5F);
        }
        if (age == S_IMPACT && x.zone != null) {
            Vec3 z = x.zone;
            son(x.monde, z, SoundEvents.GENERIC_EXPLODE.value(), 8F, 0.5F);
            son(x.monde, z, SoundEvents.GENERIC_EXPLODE.value(), 8F, 0.7F);
            son(x.monde, z, SoundEvents.LIGHTNING_BOLT_THUNDER, 6F, 0.6F);
            son(x.monde, z, SoundEvents.LAVA_EXTINGUISH, 4F, 0.5F);
            for (LivingEntity e : autour(j, x.monde, z, RAYON_SOLEIL)) {
                double d = Math.min(1, e.getBoundingBox().getCenter().distanceTo(z) / RAYON_SOLEIL);
                Fruit.blesser(e, Fruit.source(j, Fruit.MAGMA), (float) Mth.lerp(d, SOLEIL_MILIEU, SOLEIL_BORD));
                e.igniteForSeconds(10);
                Vec3 dehors = e.position().subtract(z).multiply(1, 0, 1);
                Vec3 v = dehors.lengthSqr() < 0.01 ? new Vec3(1, 0, 0) : dehors.normalize();
                double f = 1.8 * (1 - d) + 0.4;
                e.setDeltaMovement(e.getDeltaMovement().add(v.x * f, 0.4 + 0.8 * (1 - d), v.z * f));
                e.hurtMarked = true;
            }
        }
        return age >= S_FIN;
    }

    // ================================================================ ce qui dure

    @SubscribeEvent
    public static void tick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        int maintenant = server.getTickCount();
        for (Serpent3 s : new ArrayList<>(SERPENTS.values())) {
            avancer(server, s);
        }
        for (Ultime3 x : new ArrayList<>(ULTIMES)) {
            ServerPlayer j = server.getPlayerList().getPlayer(x.joueur);
            if (j == null || j.level() != x.monde) {
                ULTIMES.remove(x);
                continue;
            }
            int age = maintenant - x.debut;
            if (x.glace ? avancerHyoton(j, x, age) : avancerSoleil(j, x, age)) {
                ULTIMES.remove(x);
            }
        }
    }

    /** L'éveil 3 : tout le corps se change, sous le casque en tête de dragon (ClientFruit3 le dessine). */
    static void eveiller(ServerPlayer joueur, boolean glace) {
        FAITES.remove(joueur.getUUID());
        Vec3 p = joueur.getEyePosition();
        son(joueur.serverLevel(), p, SoundEvents.ENDER_DRAGON_GROWL, 1.2F, glace ? 1.6F : 1.3F);
        if (glace) {
            son(joueur.serverLevel(), p, SoundEvents.AMETHYST_BLOCK_RESONATE, 1.6F, 0.4F);
            son(joueur.serverLevel(), p, SoundEvents.GLASS_BREAK, 1.2F, 0.5F);
        } else {
            son(joueur.serverLevel(), p, SoundEvents.LAVA_AMBIENT, 1.6F, 0.5F);
            son(joueur.serverLevel(), p, SoundEvents.BLAZE_SHOOT, 1.2F, 0.3F);
        }
    }

    /** Il s'endort : son dragon explose s'il en guidait un ; il pourra refaire l'ultime au prochain éveil (après sa recharge). */
    static void endormir(ServerPlayer joueur) {
        FAITES.remove(joueur.getUUID());
        Serpent3 s = SERPENTS.get(joueur.getUUID());
        if (s != null && joueur.isAlive()) {
            exploser(joueur, s);
        }
        SERPENTS.remove(joueur.getUUID());
    }

    @SubscribeEvent
    public static void deconnexion(PlayerEvent.PlayerLoggedOutEvent event) {
        UUID u = event.getEntity().getUUID();
        SERPENTS.remove(u);
        FAITES.remove(u);
        ULTIMES.removeIf(x -> x.joueur.equals(u));
    }

    @SubscribeEvent
    public static void arret(ServerStoppingEvent event) {
        SERPENTS.clear();
        PRET_DRAGON.clear();
        ULTIMES.clear();
        PRET_ULTIME.clear();
        FAITES.clear();
    }

    private Fruit3() {
    }
}
