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
import net.minecraft.world.damagesource.DamageSource;
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
import net.neoforged.neoforge.event.entity.living.LivingFallEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import org.joml.Vector3f;

/**
 * Le niveau 3 des fruits du démon (listes « lave3 » et « glace3 », docs/fruits/planche-niveau3.png et
 * planche-ultimes.png, validés le 2026-11-03). La lave (ou la glace) prend tout le corps, sous un casque en tête de
 * dragon (voir ClientFruit3) ; le clic gauche et le clic droit court restent ceux du niveau 2. En plus :
 * <ul>
 * <li>clic droit maintenu : un dragon géant, sans pattes, sort de sa main et il monte sur sa nuque (docs/fruits/apercu-dragon).
 * Il le dirige du regard 30 s au plus, à la vitesse du vol du Vent 3 (c'est son jeu qui le fait voler, ClientFruit3) ; la
 * vue passe derrière lui. Clic droit sur quelqu'un : le dragon fonce sur lui et le prend dans sa gueule (6) ; clic droit
 * dans le vide : il fonce là où il regarde et explose dès que sa tête touche le sol, un mur ou quelqu'un ; au bout des 30 s,
 * il explose en l'air. L'explosion : celui qui est dans la gueule prend 24, ceux qui sont à moins de 7 blocs 20 au milieu
 * et 12 au bord ; tout brûle 8 s (ou gèle sur place 4 s). Il est éjecté et redescend sans dégâts de chute. Recharge 20 s ;</li>
 * <li>accroupi : la lave, le chien de l'enfer : une tête de chien en magma jaillit de sa main, file 18 blocs devant lui,
 * emporte qui elle touche (4) et le mord au bout (16, il brûle 6 s). La glace, la capsule : des lignes de glace filent dans
 * le sol jusqu'à ceux qui sont à moins de 12 blocs (8 au plus), des cristaux les enferment (3), et 2 s après tout éclate
 * (14, ralentis). Recharge 15 s ;</li>
 * <li>la touche d'ulti (J par défaut) : l'attaque ultime, une seule fois par éveil 3, puis 5 minutes de recharge. La glace, le Hyoton : sous
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

    /** Le dragon chevauché : sa recharge (après l'explosion), et combien de ticks garder le clic droit (sinon, le tir du niveau 2). */
    public static final int RECHARGE_DRAGON = 20 * 20;
    public static final int APPUI_DRAGON = 5;
    /** 30 s de vol au plus ; la vitesse du vol du Vent 3 (Vent.VOL, 8,5 blocs/s), un peu plus en courant ; quand il fonce. */
    public static final int DUREE_MONTE = 30 * 20;
    public static final double VITESSE_MONTE = 0.45, VITESSE_MONTE_COURSE = 0.7, VITESSE_FONCE = 1.3, VITESSE_PRISE = 1.1;
    /** De combien sa route peut tourner par tick vers le regard (il ondule, il ne se retourne pas d'un coup). */
    public static final double VIRAGE = Math.toRadians(7), VIRAGE_FONCE = Math.toRadians(3.5);
    public static final int DUREE_FONCE = 60, DUREE_PRISE = 40;
    /** Jusqu'où il peut viser quelqu'un pour que la gueule aille le prendre. */
    public static final double PORTEE_PRISE = 32;
    /** La forme : la selle derrière la nuque, la taille de la tête (comme ClientFruit3), la longueur du corps. */
    public static final double SELLE = 3.1, K_TETE = 0.42, LONGUEUR_DRAGON = 34;
    /** Ce qu'il fait (le mode dans le paquet Dragon), et comment il finit. */
    public static final int VOL = 0, PRISE = 1, FONCE = 2, EXPLOSION = 3, IMPACT = 4;
    private static final float DEGATS_PRISE_DRAGON = 6;
    private static final float DEGATS_AVALE = 24;
    private static final float EXPLOSION_MILIEU = 20;
    private static final float EXPLOSION_BORD = 12;
    private static final double RAYON_DRAGON = 7;
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

    /** L'accroupi du niveau 3 : le chien de l'enfer (sa course, en blocs et en ticks) et la capsule de glace. */
    public static final int RECHARGE_ACCROUPI = 15 * 20;
    public static final double PORTEE_CHIEN = 18;
    public static final int DUREE_CHIEN = 14;
    private static final double LARGEUR_CHIEN = 1.8;
    private static final float DEGATS_PRISE = 4;
    private static final float DEGATS_MORSURE = 16;
    private static final double RAYON_CAPSULE = 12;
    private static final int MAX_CAPSULES = 8;
    /** Les lignes de glace avancent de tant de blocs par tick ; tout éclate tant de ticks après le début. */
    public static final double VITESSE_LIGNE = 1.5;
    public static final int ECLAT_CAPSULE = 48;
    private static final float DEGATS_CAPSULE_PRISE = 3;
    private static final float DEGATS_CAPSULE = 14;

    /** Les temps des ultimes, en ticks depuis le début (les mêmes que les vidéos docs/fruits/hyoton.mp4 et soleil.mp4). */
    public static final int H_PYLONE = 10, H_BOULE = 26, H_DRAGON = 47, H_CLAQUE = 58, H_FIN_TEMPETE = 140, H_FIN = 150;
    public static final int S_SUSPENDU = 12, S_LANCER = 69, S_IMPACT = 87, S_FIN = 200;

    // ================================================================ réseau

    /** Client → serveur : il a gardé le clic droit (vrai) : le dragon sort et il monte dessus. Faux : rien. */
    public record Serpent(boolean appuye) implements CustomPacketPayload {
        public static final Type<Serpent> TYPE = new Type<>(Cabane.id("fruit_serpent"));
        public static final StreamCodec<ByteBuf, Serpent> CODEC = StreamCodec.composite(ByteBufCodecs.BOOL, Serpent::appuye, Serpent::new);

        @Override
        public Type<Serpent> type() {
            return TYPE;
        }
    }

    /**
     * Serveur → ceux qui sont autour, à chaque tick : le dragon de ce joueur (sa nuque, sa route), ce qu'il fait (VOL, PRISE,
     * FONCE) et qui il tient dans sa gueule (-1 : personne) ; ou il explose là (EXPLOSION en l'air, IMPACT contre le sol).
     */
    public record Dragon(int id, UUID joueur, boolean glace, int etat, Vector3f tete, Vector3f dir, int victime) implements CustomPacketPayload {
        public static final Type<Dragon> TYPE = new Type<>(Cabane.id("fruit_dragon"));
        public static final StreamCodec<ByteBuf, Dragon> CODEC = StreamCodec.of((b, p) -> {
            ByteBufCodecs.VAR_INT.encode(b, p.id());
            UUIDUtil.STREAM_CODEC.encode(b, p.joueur());
            ByteBufCodecs.BOOL.encode(b, p.glace());
            ByteBufCodecs.VAR_INT.encode(b, p.etat());
            ByteBufCodecs.VECTOR3F.encode(b, p.tete());
            ByteBufCodecs.VECTOR3F.encode(b, p.dir());
            ByteBufCodecs.VAR_INT.encode(b, p.victime() + 1);
        }, b -> new Dragon(ByteBufCodecs.VAR_INT.decode(b), UUIDUtil.STREAM_CODEC.decode(b), ByteBufCodecs.BOOL.decode(b), ByteBufCodecs.VAR_INT.decode(b),
                ByteBufCodecs.VECTOR3F.decode(b), ByteBufCodecs.VECTOR3F.decode(b), ByteBufCodecs.VAR_INT.decode(b) - 1));

        @Override
        public Type<Dragon> type() {
            return TYPE;
        }
    }

    /** Client → serveur, à chaque tick sur le dragon : vers où il le dirige ; et son clic droit (FONCE, ou PRISE sur « cible »). */
    public record Pilote(Vector3f dir, int action, int cible) implements CustomPacketPayload {
        public static final Type<Pilote> TYPE = new Type<>(Cabane.id("fruit_pilote"));
        public static final StreamCodec<ByteBuf, Pilote> CODEC = StreamCodec.composite(
                ByteBufCodecs.VECTOR3F, Pilote::dir, ByteBufCodecs.VAR_INT, Pilote::action, ByteBufCodecs.VAR_INT, Pilote::cible, Pilote::new);

        @Override
        public Type<Pilote> type() {
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

    /** Client → serveur : il appuie sur la touche d'ulti, fruit éveillé (l'attaque ultime, si elle est prête). */
    public record Ulti() implements CustomPacketPayload {
        public static final Type<Ulti> TYPE = new Type<>(Cabane.id("fruit_ulti"));
        public static final StreamCodec<ByteBuf, Ulti> CODEC = StreamCodec.unit(new Ulti());

        @Override
        public Type<Ulti> type() {
            return TYPE;
        }
    }

    /** Serveur → ceux qui sont autour : le chien de l'enfer part de là, vers là (phase 0) ; il mord à ce point (phase 1). */
    public record Chien(UUID joueur, Vector3f point, Vector3f dir, int phase) implements CustomPacketPayload {
        public static final Type<Chien> TYPE = new Type<>(Cabane.id("fruit_chien"));
        public static final StreamCodec<ByteBuf, Chien> CODEC = StreamCodec.composite(
                UUIDUtil.STREAM_CODEC, Chien::joueur, ByteBufCodecs.VECTOR3F, Chien::point, ByteBufCodecs.VECTOR3F, Chien::dir,
                ByteBufCodecs.VAR_INT, Chien::phase, Chien::new);

        @Override
        public Type<Chien> type() {
            return TYPE;
        }
    }

    /** Serveur → ceux qui sont autour : la capsule de glace part de ce centre vers ces entités (phase 0), puis éclate (phase 1). */
    public record Capsule(UUID joueur, Vector3f centre, List<Integer> cibles, int phase) implements CustomPacketPayload {
        public static final Type<Capsule> TYPE = new Type<>(Cabane.id("fruit_capsule"));
        public static final StreamCodec<ByteBuf, Capsule> CODEC = StreamCodec.composite(
                UUIDUtil.STREAM_CODEC, Capsule::joueur, ByteBufCodecs.VECTOR3F, Capsule::centre,
                ByteBufCodecs.VAR_INT.apply(ByteBufCodecs.list()), Capsule::cibles, ByteBufCodecs.VAR_INT, Capsule::phase, Capsule::new);

        @Override
        public Type<Capsule> type() {
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
                .playToServer(Pilote.TYPE, Pilote.CODEC, (p, ctx) -> ctx.enqueueWork(() -> {
                    if (ctx.player() instanceof ServerPlayer joueur) {
                        piloter(joueur, p);
                    }
                }))
                .playToServer(Ulti.TYPE, Ulti.CODEC, (p, ctx) -> ctx.enqueueWork(() -> {
                    if (ctx.player() instanceof ServerPlayer joueur && Fruit.eveille(joueur) && Fruit.niveau(joueur) >= 3) {
                        ultime(joueur, Fruit.glace(Pouvoirs.pouvoir(joueur)));
                    }
                }))
                .playToClient(Dragon.TYPE, Dragon.CODEC, (p, ctx) -> ctx.enqueueWork(() -> ClientFruit3.dragon(p)))
                .playToClient(Ultime.TYPE, Ultime.CODEC, (p, ctx) -> ctx.enqueueWork(() -> ClientFruit3.ultime(p)))
                .playToClient(Chien.TYPE, Chien.CODEC, (p, ctx) -> ctx.enqueueWork(() -> ClientFruit3.chien(p)))
                .playToClient(Capsule.TYPE, Capsule.CODEC, (p, ctx) -> ctx.enqueueWork(() -> ClientFruit3.capsule(p)));
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

    // ================================================================ le dragon chevauché

    /** Un repère pour un nez vers f : x sur le côté, y vers le haut (autant que possible). Le même que ClientFruit3.repere. */
    public static Vec3[] repere(Vec3 f) {
        Vec3 x = new Vec3(0, 1, 0).cross(f);
        if (x.lengthSqr() < 0.04) {
            x = new Vec3(0, 0, 1).cross(f);
        }
        x = x.normalize();
        return new Vec3[]{x, f.cross(x).normalize()};
    }

    /** La nuque du dragon (où commence le cou, la tête est devant) quand celui qui le monte a les pieds là et va vers dir. */
    public static Vec3 nuque(Vec3 pieds, Vec3 dir) {
        return pieds.subtract(repere(dir)[1].scale(0.29)).add(dir.scale(SELLE));
    }

    /** Le centre de la tête, devant la nuque. */
    public static Vec3 centreTete(Vec3 nuque, Vec3 dir) {
        return nuque.add(dir.scale(3.4 * K_TETE));
    }

    /** Dans la gueule ouverte, entre les deux mâchoires du modèle (jeu3.mjs). */
    public static Vec3 gueule(Vec3 nuque, Vec3 dir) {
        return centreTete(nuque, dir).add(repere(dir)[1].scale(-2.4 * K_TETE)).add(dir.scale(5.2 * K_TETE));
    }

    /** Les points de la tête qui touchent le sol en premier quand il plonge : le bout du museau, le bas de la mâchoire, le menton. */
    private static Vec3[] bouts(Vec3 nuque, Vec3 dir) {
        Vec3 o = centreTete(nuque, dir), y = repere(dir)[1];
        return new Vec3[]{o.add(dir.scale(8 * K_TETE)), o.add(dir.scale(5 * K_TETE)).add(y.scale(-7 * K_TETE)), o.add(y.scale(-6 * K_TETE))};
    }

    private static final class Monture {
        final int id;
        final UUID joueur;
        final ServerLevel monde;
        final boolean glace;
        final boolean volait;
        Vec3 dir;
        Vec3 tete;
        Vec3[] avant;
        int age;
        int mode = VOL;
        int debutMode;
        LivingEntity cible;
        LivingEntity victime;

        Monture(int id, ServerPlayer joueur, boolean glace, Vec3 dir) {
            this.id = id;
            this.joueur = joueur.getUUID();
            this.monde = joueur.serverLevel();
            this.glace = glace;
            this.volait = joueur.getAbilities().mayfly;
            this.dir = dir;
            this.tete = nuque(joueur.position(), dir);
        }
    }

    private static final Map<UUID, Monture> MONTURES = new HashMap<>();
    private static final Map<UUID, Integer> PRET_DRAGON = new HashMap<>();
    /** Ceux qui retombent après l'explosion (celui qui le montait, la victime) : pas de dégâts de chute jusqu'au sol. */
    private static final Map<UUID, Integer> RETOMBENT = new HashMap<>();
    private static int prochainDragon;

    /** Le clic droit maintenu : le dragon sort de sa main et il monte dessus (s'il est prêt). */
    private static void serpent(ServerPlayer joueur, boolean appuye) {
        UUID u = joueur.getUUID();
        int maintenant = joueur.server.getTickCount();
        if (!appuye || Fruit.niveau(joueur) < 3 || MONTURES.containsKey(u) || maintenant < PRET_DRAGON.getOrDefault(u, 0)) {
            return;
        }
        boolean glace = Fruit.glace(Pouvoirs.pouvoir(joueur));
        Vec3 r = joueur.getLookAngle();
        Monture s = new Monture(prochainDragon++, joueur, glace, new Vec3(r.x, Math.max(0.45, r.y), r.z).normalize());
        MONTURES.put(u, s);
        // il vole sur son dragon : le serveur ne doit pas le prendre pour un tricheur qui flotte
        if (!s.volait) {
            joueur.getAbilities().mayfly = true;
            joueur.onUpdateAbilities();
        }
        envoyer(s.monde, s.tete, new Dragon(s.id, u, glace, VOL, Fruit.v(s.tete), Fruit.v(s.dir), -1));
        Vec3 p = joueur.getEyePosition();
        son(s.monde, p, SoundEvents.ENDER_DRAGON_GROWL, 1.6F, glace ? 1.5F : 1.2F);
        if (glace) {
            son(s.monde, p, SoundEvents.AMETHYST_BLOCK_RESONATE, 1.6F, 0.5F);
            son(s.monde, p, SoundEvents.POWDER_SNOW_BREAK, 1.4F, 0.5F);
        } else {
            son(s.monde, p, SoundEvents.BLAZE_SHOOT, 1.6F, 0.4F);
            son(s.monde, p, SoundEvents.FIRECHARGE_USE, 1.4F, 0.5F);
        }
    }

    /** Il pilote : vers où va son dragon (c'est son jeu qui le fait voler) ; et le clic droit, sur quelqu'un ou dans le vide. */
    private static void piloter(ServerPlayer j, Pilote p) {
        Monture s = MONTURES.get(j.getUUID());
        if (s == null) {
            return;
        }
        Vec3 d = new Vec3(p.dir().x, p.dir().y, p.dir().z);
        if (d.lengthSqr() > 0.25 && d.lengthSqr() < 4) {
            s.dir = d.normalize();
        }
        int maintenant = j.server.getTickCount();
        if (p.action() == FONCE && s.mode != FONCE) {
            s.mode = FONCE;
            s.debutMode = s.age;
            s.cible = null;
            son(s.monde, s.tete, SoundEvents.ENDER_DRAGON_GROWL, 2.2F, s.glace ? 1.3F : 0.9F);
            son(s.monde, s.tete, SoundEvents.ENDER_DRAGON_FLAP, 2F, 0.6F);
        } else if (p.action() == PRISE && s.mode == VOL && s.victime == null
                && s.monde.getEntity(p.cible()) instanceof LivingEntity e && e.isAlive() && Pouvoirs.cible(j, e) && e.distanceTo(j) < PORTEE_PRISE + 6) {
            s.mode = PRISE;
            s.debutMode = s.age;
            s.cible = e;
            son(s.monde, s.tete, SoundEvents.ENDER_DRAGON_GROWL, 2F, s.glace ? 1.6F : 1.1F);
        }
    }

    private static void avancer(MinecraftServer server, Monture s) {
        ServerPlayer j = server.getPlayerList().getPlayer(s.joueur);
        if (j == null || j.level() != s.monde || !j.isAlive() || Fruit.niveau(j) < 3) {
            if (j != null && j.isAlive()) {
                exploser(j, s, false);
            } else {
                MONTURES.remove(s.joueur);
                envoyer(s.monde, s.tete, new Dragon(s.id, s.joueur, s.glace, EXPLOSION, Fruit.v(s.tete), Fruit.v(s.dir), -1));
            }
            return;
        }
        s.age++;
        j.fallDistance = 0;
        s.tete = nuque(j.position(), s.dir);
        Vec3 g = gueule(s.tete, s.dir);
        // il fonce sur quelqu'un : la gueule l'attrape dès qu'elle arrive sur lui (ou il renonce au bout de 2 s)
        if (s.mode == PRISE) {
            LivingEntity c = s.cible;
            if (c == null || !c.isAlive() || c.level() != s.monde || s.age - s.debutMode > DUREE_PRISE) {
                s.mode = VOL;
                s.cible = null;
            } else if (c.getBoundingBox().inflate(1.4).contains(g) || c.getBoundingBox().getCenter().distanceTo(g) < 2.6) {
                s.victime = c;
                s.mode = VOL;
                s.cible = null;
                Fruit.blesser(c, Fruit.source(j, s.glace ? Fruit.GIVRE : Fruit.MAGMA), DEGATS_PRISE_DRAGON);
                if (s.glace) {
                    Fruit.givrer(c);
                } else {
                    c.igniteForSeconds(3);
                }
                son(s.monde, g, SoundEvents.EVOKER_FANGS_ATTACK, 2.2F, 0.5F);
                son(s.monde, g, s.glace ? SoundEvents.GLASS_BREAK : SoundEvents.BLAZE_HURT, 1.4F, 0.6F);
            }
        }
        // celui qu'il tient reste dans sa gueule
        LivingEntity v = s.victime;
        if (v != null) {
            if (!v.isAlive() || v.level() != s.monde) {
                s.victime = null;
            } else {
                placer(v, g.subtract(0, v.getBbHeight() * 0.5, 0));
                if (s.glace) {
                    Fruit.givrer(v);
                } else if (s.age % 20 == 0) {
                    v.igniteForSeconds(2);
                }
            }
        }
        // il fonce : il explose dès que sa tête touche un bloc (le sol, un mur) ou quelqu'un
        if (s.mode == FONCE) {
            Vec3[] b = bouts(s.tete, s.dir);
            for (int i = 0; i < b.length; i++) {
                Vec3 de = s.avant == null ? s.tete : s.avant[i];
                BlockHitResult choc = s.monde.clip(new ClipContext(de, b[i], ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, j));
                if (choc.getType() != HitResult.Type.MISS && s.age - s.debutMode > 1) {
                    s.tete = choc.getLocation();
                    exploser(j, s, true);
                    return;
                }
            }
            Vec3 o = centreTete(s.tete, s.dir);
            for (LivingEntity e : s.monde.getEntitiesOfClass(LivingEntity.class, new AABB(o, b[0]).inflate(2.2),
                    e -> e != s.victime && Pouvoirs.cible(j, e) && !e.isSpectator())) {
                AABB bb = e.getBoundingBox().inflate(1.2);
                if (bb.contains(o) || bb.contains(b[0]) || bb.clip(o, b[0]).isPresent()) {
                    s.tete = b[0];
                    exploser(j, s, false);
                    return;
                }
            }
            s.avant = b;
            if (s.age - s.debutMode > DUREE_FONCE) {
                s.tete = o;
                exploser(j, s, false);
                return;
            }
        }
        // les 30 s sont passées : il explose en l'air, là où il est
        if (s.age >= DUREE_MONTE) {
            s.tete = centreTete(s.tete, s.dir);
            exploser(j, s, false);
            return;
        }
        envoyer(s.monde, s.tete, new Dragon(s.id, s.joueur, s.glace, s.mode, Fruit.v(s.tete), Fruit.v(s.dir), v == null ? -1 : v.getId()));
        if (s.age % 12 == 1) {
            son(s.monde, s.tete, s.glace ? SoundEvents.PHANTOM_FLAP : SoundEvents.BLAZE_BURN, 1.4F, s.glace ? 0.6F : 0.5F);
        }
        if (s.age % 30 == 7) {
            son(s.monde, s.tete, SoundEvents.ENDER_DRAGON_FLAP, 1.2F, 0.8F);
        }
    }

    /**
     * Le dragon explose (à s.tete) : celui qu'il tient prend tout (24), ceux qui sont autour (7 blocs) 20 au milieu et 12 au
     * bord ; la lave brûle 8 s, la glace gèle sur place 4 s. Celui qui le montait est éjecté et redescend sans dégâts de chute.
     */
    private static void exploser(ServerPlayer j, Monture s, boolean sol) {
        MONTURES.remove(s.joueur);
        int maintenant = j.server.getTickCount();
        PRET_DRAGON.put(s.joueur, maintenant + RECHARGE_DRAGON);
        Vec3 p = s.tete;
        envoyer(s.monde, p, new Dragon(s.id, s.joueur, s.glace, sol ? IMPACT : EXPLOSION, Fruit.v(p), Fruit.v(s.dir), -1));
        if (s.glace) {
            son(s.monde, p, SoundEvents.GLASS_BREAK, 3F, 0.5F);
            son(s.monde, p, SoundEvents.AMETHYST_CLUSTER_BREAK, 2.4F, 0.6F);
            son(s.monde, p, SoundEvents.POWDER_SNOW_BREAK, 2.4F, 0.4F);
            son(s.monde, p, SoundEvents.GENERIC_EXPLODE.value(), 2F, 1.2F);
        } else {
            son(s.monde, p, SoundEvents.GENERIC_EXPLODE.value(), 3.2F, 0.6F);
            son(s.monde, p, SoundEvents.LAVA_EXTINGUISH, 2.4F, 0.5F);
            son(s.monde, p, SoundEvents.BLAZE_SHOOT, 2F, 0.4F);
        }
        DamageSource source = Fruit.source(j, s.glace ? Fruit.GIVRE : Fruit.MAGMA);
        LivingEntity v = s.victime;
        if (v != null && v.isAlive()) {
            Fruit.blesser(v, source, DEGATS_AVALE);
            effet(v, s.glace);
            v.setDeltaMovement(s.dir.x * 0.6, 0.6, s.dir.z * 0.6);
            v.hurtMarked = true;
            RETOMBENT.put(v.getUUID(), maintenant + 15 * 20);
        }
        for (LivingEntity e : autour(j, s.monde, p, RAYON_DRAGON)) {
            if (e == v) {
                continue;
            }
            double d = Math.min(1, e.getBoundingBox().getCenter().distanceTo(p) / RAYON_DRAGON);
            Fruit.blesser(e, source, (float) Mth.lerp(d, EXPLOSION_MILIEU, EXPLOSION_BORD));
            effet(e, s.glace);
            if (!s.glace) {
                Vec3 dehors = e.position().subtract(p).multiply(1, 0, 1);
                Vec3 w = dehors.lengthSqr() < 0.01 ? s.dir.multiply(1, 0, 1) : dehors.normalize();
                e.setDeltaMovement(e.getDeltaMovement().add(w.x * (1.4 - d), 0.5, w.z * (1.4 - d)));
                e.hurtMarked = true;
            }
        }
        // celui qui le montait : projeté un peu en l'air, puis il redescend (sans se faire mal en tombant)
        if (!s.volait && !j.isCreative() && !j.isSpectator()) {
            j.getAbilities().mayfly = false;
            j.getAbilities().flying = false;
            j.onUpdateAbilities();
        }
        Vec3 recul = s.dir.multiply(1, 0, 1);
        recul = recul.lengthSqr() < 0.01 ? Vec3.ZERO : recul.normalize().scale(-0.5);
        j.setDeltaMovement(recul.x, 0.85, recul.z);
        j.hurtMarked = true;
        j.fallDistance = 0;
        j.addEffect(new MobEffectInstance(MobEffects.SLOW_FALLING, 40, 0, false, false));
        RETOMBENT.put(j.getUUID(), maintenant + 15 * 20);
    }

    private static void effet(LivingEntity e, boolean glace) {
        if (glace) {
            Fruit.geler(e, GEL_DRAGON);
        } else {
            e.igniteForSeconds(8);
        }
    }

    /** Ceux qui retombent : pas de dégâts de chute tant qu'ils ne sont pas revenus au sol. */
    private static void retomber(MinecraftServer server, int maintenant) {
        RETOMBENT.entrySet().removeIf(e -> {
            Entity x = null;
            for (ServerLevel l : server.getAllLevels()) {
                x = l.getEntity(e.getKey());
                if (x != null) {
                    break;
                }
            }
            if (x == null || maintenant > e.getValue()) {
                return true;
            }
            x.fallDistance = 0;
            return x.onGround() && maintenant > e.getValue() - 15 * 20 + 5;
        });
    }

    /** Sur son dragon, ou en retombant après l'explosion : la chute ne fait rien. */
    @SubscribeEvent
    public static void chute(LivingFallEvent event) {
        UUID u = event.getEntity().getUUID();
        if (MONTURES.containsKey(u) || RETOMBENT.containsKey(u)) {
            event.setCanceled(true);
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

    /** La touche d'ulti, au niveau 3 : l'attaque ultime, si elle est prête ; vrai si elle part. */
    static boolean ultime(ServerPlayer joueur, boolean glace) {
        UUID u = joueur.getUUID();
        int maintenant = joueur.server.getTickCount();
        String cle = u + (glace ? "/glace" : "/lave");
        if (ULTIMES.stream().anyMatch(x -> x.joueur.equals(u))) {
            return false;
        }
        // il le lui dit (avant, le panneau croyait l'ultime partie alors que le serveur l'avait refusée)
        if (FAITES.contains(u)) {
            joueur.displayClientMessage(net.minecraft.network.chat.Component.literal("L'ultime : une seule fois par éveil.")
                    .withStyle(net.minecraft.ChatFormatting.GRAY), true);
            return false;
        }
        int reste = PRET_ULTIME.getOrDefault(cle, 0) - maintenant;
        if (reste > 0) {
            joueur.displayClientMessage(net.minecraft.network.chat.Component.literal("L'ultime se recharge… encore " + (reste + 19) / 20 + " s")
                    .withStyle(net.minecraft.ChatFormatting.GRAY), true);
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

    // ================================================================ accroupi : le chien de l'enfer, la capsule de glace

    /** Le chien de l'enfer en course : d'où il part, vers où, depuis quand, et ceux qu'il emporte dans sa gueule. */
    private static final class Chien3 {
        final UUID joueur;
        final ServerLevel monde;
        final Vec3 depart;
        final Vec3 dir;
        final int debut;
        final List<LivingEntity> pris = new ArrayList<>();
        Vec3 tete;

        Chien3(ServerPlayer joueur, Vec3 depart, Vec3 dir) {
            this.joueur = joueur.getUUID();
            this.monde = joueur.serverLevel();
            this.depart = depart;
            this.dir = dir;
            this.debut = joueur.server.getTickCount();
            this.tete = depart;
        }
    }

    /** La capsule de glace : d'où partent les lignes, qui elles prennent (et quand la glace arrive), depuis quand. */
    private static final class Capsule3 {
        final UUID joueur;
        final ServerLevel monde;
        final Vec3 centre;
        final List<LivingEntity> cibles;
        final int[] arrivee;
        final int debut;

        Capsule3(ServerPlayer joueur, Vec3 centre, List<LivingEntity> cibles) {
            this.joueur = joueur.getUUID();
            this.monde = joueur.serverLevel();
            this.centre = centre;
            this.cibles = cibles;
            this.debut = joueur.server.getTickCount();
            this.arrivee = new int[cibles.size()];
            for (int i = 0; i < cibles.size(); i++) {
                arrivee[i] = Math.max(2, Mth.ceil(cibles.get(i).position().subtract(centre).horizontalDistance() / VITESSE_LIGNE));
            }
        }
    }

    private static final List<Chien3> CHIENS = new ArrayList<>();
    private static final List<Capsule3> CAPSULES = new ArrayList<>();

    /** Accroupi au niveau 3 (Fruit.choc s'occupe de la recharge) : le chien de l'enfer, ou la capsule de glace. */
    static void accroupi(ServerPlayer joueur, boolean glace) {
        ServerLevel monde = joueur.serverLevel();
        if (glace) {
            Vec3 centre = joueur.position();
            List<LivingEntity> cibles = autour(joueur, monde, centre.add(0, 1, 0), RAYON_CAPSULE).stream()
                    .sorted(java.util.Comparator.comparingDouble(e -> e.distanceToSqr(joueur))).limit(MAX_CAPSULES).toList();
            CAPSULES.add(new Capsule3(joueur, centre, cibles));
            envoyer(monde, centre, new Capsule(joueur.getUUID(), Fruit.v(centre), cibles.stream().map(Entity::getId).toList(), 0));
            son(monde, centre, SoundEvents.GLASS_BREAK, 1.6F, 0.6F);
            son(monde, centre, SoundEvents.AMETHYST_BLOCK_RESONATE, 2F, 0.6F);
            son(monde, centre, SoundEvents.POWDER_SNOW_PLACE, 2F, 0.5F);
            return;
        }
        // il file presque à plat : vers le bas il raserait le sol, vers le haut il partirait dans le ciel
        Vec3 regard = joueur.getLookAngle();
        Vec3 dir = new Vec3(regard.x, Mth.clamp(regard.y, -0.25, 0.35), regard.z).normalize();
        Vec3 depart = Fruit.main(joueur, false);
        CHIENS.add(new Chien3(joueur, depart, dir));
        envoyer(monde, depart, new Chien(joueur.getUUID(), Fruit.v(depart), Fruit.v(dir), 0));
        son(monde, depart, SoundEvents.WOLF_GROWL, 2F, 0.5F);
        son(monde, depart, SoundEvents.BLAZE_SHOOT, 1.6F, 0.5F);
        son(monde, depart, SoundEvents.FIRECHARGE_USE, 1.4F, 0.4F);
    }

    /** Où est la tête du chien, tant de ticks après son départ : vite au début, puis elle ralentit pour mordre. */
    public static double courseChien(double age) {
        double k = Mth.clamp(age / DUREE_CHIEN, 0, 1);
        return PORTEE_CHIEN * (1 - (1 - k) * (1 - k));
    }

    private static boolean avancerChien(ServerPlayer j, Chien3 c, int age) {
        Vec3 avant = c.tete, apres = c.depart.add(c.dir.scale(courseChien(age)));
        HitResult bloc = c.monde.clip(new ClipContext(avant, apres, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, j));
        boolean mur = bloc.getType() != HitResult.Type.MISS && age > 1;
        c.tete = mur ? bloc.getLocation().subtract(c.dir.scale(0.6)) : apres;
        // ceux qu'il croise : il les prend dans sa gueule, une fois chacun
        for (LivingEntity e : c.monde.getEntitiesOfClass(LivingEntity.class, new AABB(avant, c.tete).inflate(LARGEUR_CHIEN + 1),
                e -> Pouvoirs.cible(j, e) && !e.isSpectator())) {
            AABB b = e.getBoundingBox().inflate(LARGEUR_CHIEN);
            if (c.pris.contains(e) || !b.contains(c.tete) && b.clip(avant, c.tete).isEmpty()) {
                continue;
            }
            c.pris.add(e);
            Fruit.blesser(e, Fruit.source(j, Fruit.MAGMA), DEGATS_PRISE);
            e.igniteForSeconds(2);
            son(c.monde, e.position(), SoundEvents.EVOKER_FANGS_ATTACK, 1.6F, 0.6F);
        }
        // il les emporte jusqu'au bout de sa course
        for (LivingEntity e : c.pris) {
            if (e.isAlive() && e.level() == c.monde) {
                placer(e, c.tete.add(c.dir.scale(0.8)).subtract(0, e.getBbHeight() * 0.5, 0));
            }
        }
        if (age % 4 == 0) {
            son(c.monde, c.tete, SoundEvents.BLAZE_BURN, 1.2F, 0.5F);
        }
        if (!mur && age < DUREE_CHIEN) {
            return false;
        }
        // la morsure : la gueule se referme, et tout ce qu'il tient brûle
        envoyer(c.monde, c.tete, new Chien(c.joueur, Fruit.v(c.tete), Fruit.v(c.dir), 1));
        son(c.monde, c.tete, SoundEvents.EVOKER_FANGS_ATTACK, 2.4F, 0.4F);
        son(c.monde, c.tete, SoundEvents.GENERIC_EXPLODE.value(), 1.8F, 0.8F);
        son(c.monde, c.tete, SoundEvents.WOLF_GROWL, 2F, 0.4F);
        son(c.monde, c.tete, SoundEvents.LAVA_EXTINGUISH, 1.6F, 0.5F);
        for (LivingEntity e : c.pris) {
            if (!e.isAlive() || e.level() != c.monde) {
                continue;
            }
            Fruit.blesser(e, Fruit.source(j, Fruit.MAGMA), DEGATS_MORSURE);
            e.igniteForSeconds(6);
            e.setDeltaMovement(c.dir.x * 0.8, 0.55, c.dir.z * 0.8);
            e.hurtMarked = true;
            e.fallDistance = 0;
        }
        return true;
    }

    private static boolean avancerCapsule(ServerPlayer j, Capsule3 x, int age) {
        for (int i = 0; i < x.cibles.size(); i++) {
            LivingEntity e = x.cibles.get(i);
            if (age == x.arrivee[i] && e.isAlive() && e.level() == x.monde) {
                // la glace arrive sous lui : les cristaux l'enferment jusqu'à ce que tout éclate
                Fruit.blesser(e, Fruit.source(j, Fruit.GIVRE), DEGATS_CAPSULE_PRISE);
                Fruit.geler(e, ECLAT_CAPSULE - age + 2);
                son(x.monde, e.position(), SoundEvents.AMETHYST_CLUSTER_PLACE, 1.6F, 0.6F);
                son(x.monde, e.position(), SoundEvents.PLAYER_HURT_FREEZE, 1.2F, 0.7F);
            }
        }
        if (age % 6 == 0 && age < ECLAT_CAPSULE) {
            son(x.monde, x.centre, SoundEvents.POWDER_SNOW_STEP, 1.4F, 0.6F);
        }
        if (age < ECLAT_CAPSULE) {
            return false;
        }
        envoyer(x.monde, x.centre, new Capsule(x.joueur, Fruit.v(x.centre), x.cibles.stream().map(Entity::getId).toList(), 1));
        for (LivingEntity e : x.cibles) {
            if (!e.isAlive() || e.level() != x.monde) {
                continue;
            }
            son(x.monde, e.position(), SoundEvents.GLASS_BREAK, 2F, 0.5F);
            son(x.monde, e.position(), SoundEvents.AMETHYST_CLUSTER_BREAK, 1.8F, 0.5F);
            Fruit.blesser(e, Fruit.source(j, Fruit.GIVRE), DEGATS_CAPSULE);
            e.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 60, 2));
            Fruit.givrer(e);
            Vec3 dehors = e.position().subtract(x.centre).multiply(1, 0, 1);
            Vec3 v = dehors.lengthSqr() < 0.01 ? new Vec3(1, 0, 0) : dehors.normalize();
            e.setDeltaMovement(v.x * 0.5, 0.35, v.z * 0.5);
            e.hurtMarked = true;
        }
        son(x.monde, x.centre, SoundEvents.GENERIC_EXPLODE.value(), 1.2F, 1.4F);
        return true;
    }

    // ================================================================ ce qui dure

    @SubscribeEvent
    public static void tick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        int maintenant = server.getTickCount();
        for (Monture s : new ArrayList<>(MONTURES.values())) {
            avancer(server, s);
        }
        retomber(server, maintenant);
        CHIENS.removeIf(c -> {
            ServerPlayer j = server.getPlayerList().getPlayer(c.joueur);
            return j == null || j.level() != c.monde || avancerChien(j, c, maintenant - c.debut);
        });
        CAPSULES.removeIf(x -> {
            ServerPlayer j = server.getPlayerList().getPlayer(x.joueur);
            return j == null || j.level() != x.monde || avancerCapsule(j, x, maintenant - x.debut);
        });
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

    /** Il s'endort : son dragon explose s'il en montait un ; il pourra refaire l'ultime au prochain éveil (après sa recharge). */
    static void endormir(ServerPlayer joueur) {
        FAITES.remove(joueur.getUUID());
        Monture s = MONTURES.get(joueur.getUUID());
        if (s != null && joueur.isAlive()) {
            s.tete = centreTete(s.tete, s.dir);
            exploser(joueur, s, false);
        }
        MONTURES.remove(joueur.getUUID());
    }

    @SubscribeEvent
    public static void deconnexion(PlayerEvent.PlayerLoggedOutEvent event) {
        UUID u = event.getEntity().getUUID();
        Monture s = MONTURES.remove(u);
        if (s != null && !s.volait && event.getEntity() instanceof ServerPlayer j && !j.isCreative() && !j.isSpectator()) {
            j.getAbilities().mayfly = false;
            j.getAbilities().flying = false;
        }
        RETOMBENT.remove(u);
        FAITES.remove(u);
        ULTIMES.removeIf(x -> x.joueur.equals(u));
        CHIENS.removeIf(c -> c.joueur.equals(u));
        CAPSULES.removeIf(x -> x.joueur.equals(u));
    }

    @SubscribeEvent
    public static void arret(ServerStoppingEvent event) {
        MONTURES.clear();
        PRET_DRAGON.clear();
        RETOMBENT.clear();
        ULTIMES.clear();
        PRET_ULTIME.clear();
        FAITES.clear();
        CHIENS.clear();
        CAPSULES.clear();
    }

    private Fruit3() {
    }
}
