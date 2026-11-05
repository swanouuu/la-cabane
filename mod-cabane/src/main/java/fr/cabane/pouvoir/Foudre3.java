package fr.cabane.pouvoir;

import fr.cabane.Cabane;
import fr.cabane.CabaneSons;
import fr.cabane.client.pouvoir.ClientBetes;
import io.netty.buffer.ByteBuf;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import org.joml.Vector3f;

/**
 * Les attaques du niveau 3 de la Foudre (liste « foudre3 »), d'après docs/foudre3/planche-attaques.png, équilibrées comme
 * un niveau 4 (l'ultime, l'orage, est dans {@link Orage}) :
 * <ul>
 * <li>accroupi : le TP en foudre, avec trois charges (une revient toutes les 7 s) ; un gros trait de foudre reste là où il
 * est passé et électrise ceux qu'il traverse (le TP lui-même est dans {@link Foudre}) ;</li>
 * <li>clic gauche : le choc saute de la cible sur une autre (voir {@link Foudre}) ;</li>
 * <li>clic droit (un appui) : le guépard de foudre part tout droit au ras du sol, et bondit sur le premier qu'il croise ;</li>
 * <li>clic droit maintenu : l'oiseau-tonnerre se charge au-dessus de lui (il ne bouge pas, un coup reçu annule tout) ; il
 * lâche, l'oiseau s'envole et il le dirige du regard, jusqu'à ce qu'il touche quelque chose (ou au bout de 5 s).</li>
 * </ul>
 * Ce qu'on voit est dessiné par {@link ClientBetes} ; rien ne casse, rien ne brûle.
 */
@EventBusSubscriber(modid = Cabane.MODID)
public final class Foudre3 {
    public static final int CHARGES_TP = 3;
    /** Une charge de TP revient toutes les 7 s. */
    public static final int RETOUR_CHARGE = 7 * 20;
    public static final int RECHARGE_GUEPARD = 10 * 20;
    public static final int RECHARGE_OISEAU = 25 * 20;
    public static final int RECHARGE_OISEAU_ANNULE = 8 * 20;
    /** La barre de l'oiseau se remplit en 3 s ; au bout de 5 s, il lâche tout seul ; il vole 5 s au plus. */
    public static final int CHARGE_OISEAU = 3 * 20;
    private static final int CHARGE_MAX = 5 * 20;
    private static final int VOL_MAX = 5 * 20;
    private static final double VITESSE_OISEAU = 1.3;
    private static final double VITESSE_GUEPARD = 1.3;
    private static final int COURSE_GUEPARD = 31;

    private static final float DEGATS_TRAIT = 5;
    private static final float DEGATS_SAUT_CHOC = 4;
    private static final double SAUT_CHOC = 5;
    private static final float DEGATS_GUEPARD = 16;
    private static final float DEGATS_GUEPARD_AUTOUR = 8;
    private static final double RAYON_GUEPARD = 4;
    private static final float OISEAU_MIN = 10;
    private static final float OISEAU_MAX = 26;
    private static final double RAYON_OISEAU = 8;

    // ================================================================ réseau

    /** Client → serveur : un appui sur le clic droit (le guépard). */
    public record Guepard() implements CustomPacketPayload {
        public static final Type<Guepard> TYPE = new Type<>(Cabane.id("foudre3_guepard"));
        public static final StreamCodec<ByteBuf, Guepard> CODEC = StreamCodec.unit(new Guepard());

        @Override
        public Type<Guepard> type() {
            return TYPE;
        }
    }

    /** Client → serveur : le clic droit maintenu (l'oiseau se charge), puis lâché (il s'envole). */
    public record Oiseau(boolean tenu) implements CustomPacketPayload {
        public static final Type<Oiseau> TYPE = new Type<>(Cabane.id("foudre3_oiseau"));
        public static final StreamCodec<ByteBuf, Oiseau> CODEC = StreamCodec.composite(ByteBufCodecs.BOOL, Oiseau::tenu, Oiseau::new);

        @Override
        public Type<Oiseau> type() {
            return TYPE;
        }
    }

    public static final int GUEPARD = 0, OISEAU = 1;
    public static final int CHARGE = 0, VOL = 1, FIN = 2, ANNULE = 3;

    /** Serveur → ceux qui sont autour : où en est la bête de ce joueur (sa position et sa direction, à chaque tick du vol). */
    public record Bete(UUID joueur, int sorte, int phase, Vector3f pos, Vector3f dir, float charge) implements CustomPacketPayload {
        public static final Type<Bete> TYPE = new Type<>(Cabane.id("foudre3_bete"));
        public static final StreamCodec<ByteBuf, Bete> CODEC = StreamCodec.composite(
                UUIDUtil.STREAM_CODEC, Bete::joueur, ByteBufCodecs.VAR_INT, Bete::sorte, ByteBufCodecs.VAR_INT, Bete::phase,
                ByteBufCodecs.VECTOR3F, Bete::pos, ByteBufCodecs.VECTOR3F, Bete::dir, ByteBufCodecs.FLOAT, Bete::charge, Bete::new);

        @Override
        public Type<Bete> type() {
            return TYPE;
        }
    }

    /** Serveur → le joueur : combien de charges de TP il lui reste, et dans combien de ticks la prochaine revient. */
    public record Charges(int n, int dans) implements CustomPacketPayload {
        public static final Type<Charges> TYPE = new Type<>(Cabane.id("foudre3_charges"));
        public static final StreamCodec<ByteBuf, Charges> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, Charges::n, ByteBufCodecs.VAR_INT, Charges::dans, Charges::new);

        @Override
        public Type<Charges> type() {
            return TYPE;
        }
    }

    @SubscribeEvent
    public static void reseau(RegisterPayloadHandlersEvent event) {
        event.registrar("1")
                .playToServer(Guepard.TYPE, Guepard.CODEC, (p, ctx) -> ctx.enqueueWork(() -> {
                    if (ctx.player() instanceof ServerPlayer joueur) {
                        guepard(joueur);
                    }
                }))
                .playToServer(Oiseau.TYPE, Oiseau.CODEC, (p, ctx) -> ctx.enqueueWork(() -> {
                    if (ctx.player() instanceof ServerPlayer joueur) {
                        if (p.tenu()) {
                            charger(joueur);
                        } else {
                            lacher(joueur);
                        }
                    }
                }))
                .playToClient(Bete.TYPE, Bete.CODEC, (p, ctx) -> ctx.enqueueWork(() -> ClientBetes.bete(p)))
                .playToClient(Charges.TYPE, Charges.CODEC, (p, ctx) -> ctx.enqueueWork(() -> ClientBetes.charges(p)));
    }

    private static void envoyer(ServerLevel monde, CustomPacketPayload paquet, Vec3 autour) {
        for (ServerPlayer p : monde.players()) {
            if (p.distanceToSqr(autour) < 160 * 160) {
                PacketDistributor.sendToPlayer(p, paquet);
            }
        }
    }

    private static Vector3f v(Vec3 p) {
        return new Vector3f((float) p.x, (float) p.y, (float) p.z);
    }

    private static void son(ServerLevel monde, Vec3 ou, net.minecraft.sounds.SoundEvent son, float volume, float hauteur) {
        monde.playSound(null, ou.x, ou.y, ou.z, son, SoundSource.PLAYERS, volume, hauteur);
    }

    private static boolean trois(ServerPlayer joueur) {
        return Foudre.niveau(joueur) >= 3;
    }

    private static boolean pret(Map<UUID, Integer> recharges, ServerPlayer joueur) {
        return joueur.server.getTickCount() >= recharges.getOrDefault(joueur.getUUID(), 0);
    }

    // ================================================================ accroupi : les trois charges du TP

    /** Combien de charges il a, et quand la prochaine revient (ticks du serveur). */
    private static final Map<UUID, Integer> CHARGES = new HashMap<>();
    private static final Map<UUID, Integer> RETOUR = new HashMap<>();

    static boolean chargeTp(ServerPlayer joueur) {
        return CHARGES.getOrDefault(joueur.getUUID(), CHARGES_TP) > 0;
    }

    static void utiliserCharge(ServerPlayer joueur) {
        UUID id = joueur.getUUID();
        int n = CHARGES.getOrDefault(id, CHARGES_TP) - 1;
        CHARGES.put(id, n);
        if (!RETOUR.containsKey(id)) {
            RETOUR.put(id, joueur.server.getTickCount() + RETOUR_CHARGE);
        }
        PacketDistributor.sendToPlayer(joueur, new Charges(n, RETOUR.get(id) - joueur.server.getTickCount()));
    }

    /** Le gros trait du TP : ceux qu'il a traversés (à 1,3 bloc de la ligne) prennent un choc. */
    static void trait(ServerPlayer joueur, Vec3 de, Vec3 a) {
        ServerLevel monde = joueur.serverLevel();
        Vec3 d = a.subtract(de);
        double l2 = d.lengthSqr();
        if (l2 < 1) {
            return;
        }
        for (LivingEntity e : monde.getEntitiesOfClass(LivingEntity.class, new AABB(de, a).inflate(2), e -> e != joueur && Pouvoirs.cible(joueur, e) && !e.isSpectator())) {
            Vec3 c = e.position().add(0, e.getBbHeight() * 0.5, 0);
            double u = Math.max(0, Math.min(1, c.subtract(de.add(0, 1, 0)).dot(d) / l2));
            Vec3 proche = de.add(0, 1, 0).add(d.scale(u));
            if (proche.distanceTo(c) < 1.3 + e.getBbWidth() * 0.5 && e.distanceToSqr(a) > 9) {
                Foudre.blesser(e, Foudre.source(joueur, Foudre.FOUDRE), DEGATS_TRAIT);
                e.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 40, 1));
                PacketDistributor.sendToPlayersTrackingEntityAndSelf(e, new Foudre.Choc(e.getId(), true));
            }
        }
    }

    // ================================================================ clic gauche : le choc qui saute sur un autre

    /** Le choc d'un coup saute de la cible sur la plus proche à 5 blocs (un éclair de l'une à l'autre). */
    static void sauter(ServerPlayer joueur, LivingEntity cible) {
        ServerLevel monde = joueur.serverLevel();
        LivingEntity autre = monde.getEntitiesOfClass(LivingEntity.class, cible.getBoundingBox().inflate(SAUT_CHOC),
                        e -> e != cible && e != joueur && Pouvoirs.cible(joueur, e) && !e.isSpectator() && e.distanceTo(cible) <= SAUT_CHOC)
                .stream().min((a, b) -> Double.compare(a.distanceToSqr(cible), b.distanceToSqr(cible))).orElse(null);
        if (autre == null) {
            return;
        }
        Foudre.blesser(autre, Foudre.source(joueur, Foudre.FOUDRE), DEGATS_SAUT_CHOC);
        autre.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 60, 1));
        List<Vector3f> points = List.of(v(cible.position().add(0, cible.getBbHeight() * 0.55, 0)), v(autre.position().add(0, autre.getBbHeight() * 0.55, 0)));
        envoyer(monde, new Foudre.Chaine(joueur.getUUID(), points), cible.position());
        PacketDistributor.sendToPlayersTrackingEntityAndSelf(autre, new Foudre.Choc(autre.getId(), true));
    }

    // ================================================================ clic droit : le guépard

    /** Une bête lâchée : où, vers où, depuis quand ; pour l'oiseau, combien chargé. */
    private static final class Lachee {
        final ServerLevel monde;
        Vec3 pos;
        Vec3 dir;
        final int debut;
        final float charge;

        Lachee(ServerLevel monde, Vec3 pos, Vec3 dir, int debut, float charge) {
            this.monde = monde;
            this.pos = pos;
            this.dir = dir;
            this.debut = debut;
            this.charge = charge;
        }
    }

    private static final Map<UUID, Lachee> GUEPARDS = new HashMap<>();
    private static final Map<UUID, Integer> PRET_GUEPARD = new HashMap<>();
    /** Ceux que le guépard a plaqués : paralysés 1 s, là où il les a pris. */
    private static final Map<Integer, Vec3> PLAQUES = new HashMap<>();
    private static final Map<Integer, Integer> FIN_PLAQUE = new HashMap<>();

    private static void guepard(ServerPlayer joueur) {
        UUID id = joueur.getUUID();
        if (!trois(joueur) || GUEPARDS.containsKey(id) || CHARGES_OISEAU.containsKey(id) || !pret(PRET_GUEPARD, joueur)) {
            return;
        }
        Vec3 regard = joueur.getLookAngle();
        Vec3 dir = new Vec3(regard.x, 0, regard.z);
        dir = dir.lengthSqr() < 1e-4 ? new Vec3(0, 0, 1) : dir.normalize();
        int maintenant = joueur.server.getTickCount();
        PRET_GUEPARD.put(id, maintenant + RECHARGE_GUEPARD);
        Lachee g = new Lachee(joueur.serverLevel(), joueur.position().add(dir.scale(1.2)), dir, maintenant, 0);
        GUEPARDS.put(id, g);
        envoyer(g.monde, new Bete(id, GUEPARD, VOL, v(g.pos), v(dir), 0), g.pos);
        son(g.monde, g.pos, SoundEvents.RAVAGER_ROAR, 1.6F, 1.6F);
        son(g.monde, g.pos, CabaneSons.FOUDRE_ZAP.get(), 1.6F, 0.7F);
        son(g.monde, g.pos, SoundEvents.LIGHTNING_BOLT_IMPACT, 1F, 1.6F);
    }

    /** Un tick de course : 1,3 bloc tout droit, au ras du sol ; il bondit sur le premier qu'il croise, ou s'arrête contre un mur. */
    private static boolean courir(ServerPlayer joueur, Lachee g, int maintenant) {
        Vec3 suivant = g.pos.add(g.dir.scale(VITESSE_GUEPARD));
        // un mur devant (à hauteur de poitrine) : il s'arrête
        HitResult mur = g.monde.clip(new ClipContext(g.pos.add(0, 1, 0), suivant.add(0, 1, 0), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, joueur));
        boolean fin = mur.getType() != HitResult.Type.MISS || maintenant - g.debut >= COURSE_GUEPARD;
        if (mur.getType() == HitResult.Type.MISS) {
            // au ras du sol : il suit les pentes
            HitResult sol = g.monde.clip(new ClipContext(suivant.add(0, 2.5, 0), suivant.add(0, -6, 0), ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY, joueur));
            g.pos = sol.getType() == HitResult.Type.MISS ? suivant : sol.getLocation();
        }
        LivingEntity proie = g.monde.getEntitiesOfClass(LivingEntity.class, new AABB(g.pos, g.pos).inflate(1.8, 2.5, 1.8),
                e -> e != joueur && Pouvoirs.cible(joueur, e) && !e.isSpectator()).stream().findFirst().orElse(null);
        if (proie != null) {
            g.pos = proie.position();
            bondir(joueur, g, proie);
            return true;
        }
        envoyer(g.monde, new Bete(joueur.getUUID(), GUEPARD, fin ? FIN : VOL, v(g.pos), v(g.dir), 0), g.pos);
        if (fin) {
            son(g.monde, g.pos, SoundEvents.LIGHTNING_BOLT_IMPACT, 1.4F, 1.3F);
        }
        return fin;
    }

    /** Il bondit sur sa proie : 16, paralysée 1 s ; 8 à ceux qui sont à 4 blocs ; la foudre tombe là. */
    private static void bondir(ServerPlayer joueur, Lachee g, LivingEntity proie) {
        envoyer(g.monde, new Bete(joueur.getUUID(), GUEPARD, FIN, v(g.pos), v(g.dir), 1), g.pos);
        son(g.monde, g.pos, SoundEvents.RAVAGER_ROAR, 2.2F, 1.3F);
        son(g.monde, g.pos, SoundEvents.LIGHTNING_BOLT_THUNDER, 2F, 1.2F);
        son(g.monde, g.pos, SoundEvents.LIGHTNING_BOLT_IMPACT, 2.4F, 0.9F);
        Foudre.blesser(proie, Foudre.source(joueur, Foudre.KIRIN), DEGATS_GUEPARD);
        PLAQUES.put(proie.getId(), proie.position());
        FIN_PLAQUE.put(proie.getId(), joueur.server.getTickCount() + 20);
        PacketDistributor.sendToPlayersTrackingEntityAndSelf(proie, new Foudre.Choc(proie.getId(), true));
        for (LivingEntity e : g.monde.getEntitiesOfClass(LivingEntity.class, proie.getBoundingBox().inflate(RAYON_GUEPARD),
                e -> e != proie && e != joueur && Pouvoirs.cible(joueur, e) && !e.isSpectator() && e.distanceTo(proie) <= RAYON_GUEPARD)) {
            Foudre.blesser(e, Foudre.source(joueur, Foudre.FOUDRE), DEGATS_GUEPARD_AUTOUR);
            Vec3 dehors = e.position().subtract(proie.position());
            dehors = dehors.horizontalDistanceSqr() < 0.01 ? Vec3.ZERO : new Vec3(dehors.x, 0, dehors.z).normalize();
            e.setDeltaMovement(dehors.x * 0.7, 0.35, dehors.z * 0.7);
            e.hurtMarked = true;
            PacketDistributor.sendToPlayersTrackingEntityAndSelf(e, new Foudre.Choc(e.getId(), true));
        }
    }

    // ================================================================ clic droit maintenu : l'oiseau-tonnerre

    /** Un oiseau qui se charge : depuis quand, et où le lanceur doit rester. */
    private record Charge(ServerLevel monde, int debut, Vec3 pos) {
    }

    private static final Map<UUID, Charge> CHARGES_OISEAU = new HashMap<>();
    private static final Map<UUID, Lachee> OISEAUX = new HashMap<>();
    private static final Map<UUID, Integer> PRET_OISEAU = new HashMap<>();

    /** Là où l'oiseau se forme : bien au-dessus de sa tête. */
    private static Vec3 audessus(ServerPlayer joueur) {
        return joueur.position().add(0, 12, 0);
    }

    private static void charger(ServerPlayer joueur) {
        UUID id = joueur.getUUID();
        if (!trois(joueur) || CHARGES_OISEAU.containsKey(id) || OISEAUX.containsKey(id) || !pret(PRET_OISEAU, joueur)) {
            return;
        }
        ServerLevel monde = joueur.serverLevel();
        CHARGES_OISEAU.put(id, new Charge(monde, joueur.server.getTickCount(), joueur.position()));
        envoyer(monde, new Bete(id, OISEAU, CHARGE, v(audessus(joueur)), v(joueur.getLookAngle()), 0), joueur.position());
        son(monde, joueur.position(), CabaneSons.FOUDRE_CHARGE.get(), 2F, 0.8F);
        monde.playSound(null, joueur.getX(), joueur.getY() + 14, joueur.getZ(), SoundEvents.LIGHTNING_BOLT_THUNDER, SoundSource.WEATHER, 2F, 0.7F);
    }

    private static float charge(Charge c, int maintenant) {
        return Math.min(1F, (maintenant - c.debut()) / (float) CHARGE_OISEAU);
    }

    /** Il lâche : l'oiseau s'envole de là-haut, droit vers où il regarde ; ensuite il le dirige du regard. */
    private static void lacher(ServerPlayer joueur) {
        Charge c = CHARGES_OISEAU.remove(joueur.getUUID());
        if (c == null) {
            return;
        }
        int maintenant = joueur.server.getTickCount();
        float charge = charge(c, maintenant);
        PRET_OISEAU.put(joueur.getUUID(), maintenant + RECHARGE_OISEAU);
        Vec3 depart = audessus(joueur);
        Lachee o = new Lachee(c.monde(), depart, vise(joueur, depart), maintenant, charge);
        OISEAUX.put(joueur.getUUID(), o);
        envoyer(c.monde(), new Bete(joueur.getUUID(), OISEAU, VOL, v(depart), v(o.dir), charge), depart);
        son(c.monde(), depart, SoundEvents.PHANTOM_SWOOP, 3F, 0.6F);
        son(c.monde(), depart, SoundEvents.LIGHTNING_BOLT_THUNDER, 2F, 1.1F);
    }

    /** Vers où il regarde, vu de p : le point qu'il vise (le bloc, ou à 60 blocs devant). */
    private static Vec3 vise(ServerPlayer joueur, Vec3 p) {
        Vec3 oeil = joueur.getEyePosition();
        Vec3 loin = oeil.add(joueur.getLookAngle().scale(60));
        HitResult bloc = joueur.serverLevel().clip(new ClipContext(oeil, loin, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, joueur));
        Vec3 cible = bloc.getType() == HitResult.Type.MISS ? loin : bloc.getLocation();
        Vec3 d = cible.subtract(p);
        return d.lengthSqr() < 1e-4 ? joueur.getLookAngle() : d.normalize();
    }

    /** Un coup reçu pendant la charge : l'oiseau se défait. */
    private static void annuler(ServerPlayer joueur, boolean coup) {
        Charge c = CHARGES_OISEAU.remove(joueur.getUUID());
        if (c == null) {
            return;
        }
        if (coup) {
            PRET_OISEAU.put(joueur.getUUID(), joueur.server.getTickCount() + RECHARGE_OISEAU_ANNULE);
        }
        envoyer(c.monde(), new Bete(joueur.getUUID(), OISEAU, ANNULE, v(audessus(joueur)), v(joueur.getLookAngle()), 0), joueur.position());
        son(c.monde(), joueur.position(), SoundEvents.BEACON_DEACTIVATE, 1.2F, 0.7F);
    }

    /** Un tick de vol : il tourne vers où le joueur regarde, et explose sur ce qu'il touche (ou au bout de 5 s). */
    private static boolean voler(ServerPlayer joueur, Lachee o, int maintenant) {
        Vec3 voulu = vise(joueur, o.pos);
        o.dir = o.dir.add(voulu.subtract(o.dir).scale(0.16)).normalize();
        Vec3 suivant = o.pos.add(o.dir.scale(VITESSE_OISEAU));
        HitResult bloc = o.monde.clip(new ClipContext(o.pos, suivant, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, joueur));
        boolean touche = bloc.getType() != HitResult.Type.MISS;
        o.pos = touche ? bloc.getLocation() : suivant;
        if (!touche) {
            touche = !o.monde.getEntitiesOfClass(LivingEntity.class, new AABB(o.pos, o.pos).inflate(2.5),
                    e -> e != joueur && Pouvoirs.cible(joueur, e) && !e.isSpectator()).isEmpty();
        }
        if (touche || maintenant - o.debut >= VOL_MAX) {
            exploser(joueur, o);
            return true;
        }
        envoyer(o.monde, new Bete(joueur.getUUID(), OISEAU, VOL, v(o.pos), v(o.dir), o.charge), o.pos);
        if ((maintenant - o.debut) % 10 == 0) {
            son(o.monde, o.pos, SoundEvents.PHANTOM_FLAP, 3F, 0.5F);
        }
        return false;
    }

    /** L'oiseau explose : de 10 à 26 au milieu selon la barre, la moitié au bord (8 blocs). */
    private static void exploser(ServerPlayer joueur, Lachee o) {
        envoyer(o.monde, new Bete(joueur.getUUID(), OISEAU, FIN, v(o.pos), v(o.dir), o.charge), o.pos);
        son(o.monde, o.pos, SoundEvents.LIGHTNING_BOLT_THUNDER, 5F, 0.7F);
        son(o.monde, o.pos, SoundEvents.LIGHTNING_BOLT_IMPACT, 4F, 0.7F);
        son(o.monde, o.pos, SoundEvents.GENERIC_EXPLODE.value(), 2F, 0.8F);
        son(o.monde, o.pos, SoundEvents.PHANTOM_DEATH, 3F, 0.5F);
        float centre = OISEAU_MIN + (OISEAU_MAX - OISEAU_MIN) * o.charge;
        for (LivingEntity e : o.monde.getEntitiesOfClass(LivingEntity.class, new AABB(o.pos, o.pos).inflate(RAYON_OISEAU),
                e -> e != joueur && Pouvoirs.cible(joueur, e) && !e.isSpectator())) {
            Vec3 loin = e.position().add(0, e.getBbHeight() * 0.5, 0).subtract(o.pos);
            double d = loin.length();
            if (d > RAYON_OISEAU) {
                continue;
            }
            Foudre.blesser(e, Foudre.source(joueur, Foudre.KIRIN), centre * (float) (1 - 0.5 * d / RAYON_OISEAU));
            e.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 60, 1));
            Vec3 dehors = loin.horizontalDistanceSqr() < 0.01 ? Vec3.ZERO : new Vec3(loin.x, 0, loin.z).normalize();
            e.setDeltaMovement(dehors.x * 0.9, 0.6, dehors.z * 0.9);
            e.hurtMarked = true;
        }
    }

    // ================================================================ ce qui dure

    @SubscribeEvent
    public static void tick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        int maintenant = server.getTickCount();
        // les charges du TP : une revient toutes les 7 s
        for (UUID id : new ArrayList<>(RETOUR.keySet())) {
            if (maintenant < RETOUR.get(id)) {
                continue;
            }
            int n = Math.min(CHARGES_TP, CHARGES.getOrDefault(id, CHARGES_TP) + 1);
            CHARGES.put(id, n);
            if (n < CHARGES_TP) {
                RETOUR.put(id, maintenant + RETOUR_CHARGE);
            } else {
                RETOUR.remove(id);
            }
            ServerPlayer joueur = server.getPlayerList().getPlayer(id);
            if (joueur != null) {
                PacketDistributor.sendToPlayer(joueur, new Charges(n, n < CHARGES_TP ? RETOUR_CHARGE : 0));
            }
        }
        GUEPARDS.entrySet().removeIf(e -> {
            ServerPlayer joueur = server.getPlayerList().getPlayer(e.getKey());
            return joueur == null || joueur.level() != e.getValue().monde || courir(joueur, e.getValue(), maintenant);
        });
        // les plaqués par le guépard : ils ne bougent plus pendant 1 s
        PLAQUES.entrySet().removeIf(p -> {
            Integer fin = FIN_PLAQUE.get(p.getKey());
            if (fin == null || maintenant >= fin) {
                FIN_PLAQUE.remove(p.getKey());
                return true;
            }
            for (ServerLevel monde : server.getAllLevels()) {
                Entity e = monde.getEntity(p.getKey());
                if (e instanceof LivingEntity l && l.isAlive()) {
                    Vec3 q = p.getValue();
                    if (l.position().distanceToSqr(q) > 0.01) {
                        l.teleportTo(q.x, q.y, q.z);
                    }
                    l.setDeltaMovement(0, Math.min(0, l.getDeltaMovement().y), 0);
                    l.hurtMarked = true;
                }
            }
            return false;
        });
        // l'oiseau qui se charge : il ne bouge pas ; au bout de 5 s, il lâche tout seul
        for (Map.Entry<UUID, Charge> e : new ArrayList<>(CHARGES_OISEAU.entrySet())) {
            ServerPlayer joueur = server.getPlayerList().getPlayer(e.getKey());
            Charge c = e.getValue();
            if (joueur == null || !joueur.isAlive() || joueur.level() != c.monde() || !trois(joueur)) {
                CHARGES_OISEAU.remove(e.getKey());
                continue;
            }
            if (maintenant - c.debut() >= CHARGE_MAX) {
                lacher(joueur);
                continue;
            }
            Vec3 p = c.pos();
            if (joueur.position().distanceToSqr(p) > 0.01) {
                joueur.teleportTo(p.x, Math.min(p.y, joueur.getY()), p.z);
            }
            joueur.setDeltaMovement(0, Math.min(0, joueur.getDeltaMovement().y), 0);
            joueur.hurtMarked = true;
        }
        // l'oiseau en vol : il le dirige du regard, au pas
        OISEAUX.entrySet().removeIf(e -> {
            ServerPlayer joueur = server.getPlayerList().getPlayer(e.getKey());
            if (joueur == null || !joueur.isAlive() || joueur.level() != e.getValue().monde) {
                return true;
            }
            joueur.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 3, 2, false, false));
            return voler(joueur, e.getValue(), maintenant);
        });
    }

    /** Un coup reçu pendant la charge de l'oiseau : tout s'annule. */
    @SubscribeEvent
    public static void touche(LivingDamageEvent.Post event) {
        if (event.getEntity() instanceof ServerPlayer joueur && event.getNewDamage() > 0 && CHARGES_OISEAU.containsKey(joueur.getUUID())) {
            annuler(joueur, true);
        }
    }

    /** Il s'endort : tout est rendu (les charges pleines, les bêtes défaites). */
    static void endormir(ServerPlayer joueur) {
        UUID id = joueur.getUUID();
        annuler(joueur, false);
        Lachee o = OISEAUX.remove(id);
        if (o != null) {
            envoyer(o.monde, new Bete(id, OISEAU, ANNULE, v(o.pos), v(o.dir), 0), o.pos);
        }
        Lachee g = GUEPARDS.remove(id);
        if (g != null) {
            envoyer(g.monde, new Bete(id, GUEPARD, ANNULE, v(g.pos), v(g.dir), 0), g.pos);
        }
        CHARGES.remove(id);
        RETOUR.remove(id);
        PRET_GUEPARD.remove(id);
        PRET_OISEAU.remove(id);
        PacketDistributor.sendToPlayer(joueur, new Charges(CHARGES_TP, 0));
    }

    @SubscribeEvent
    public static void deconnexion(PlayerEvent.PlayerLoggedOutEvent event) {
        UUID id = event.getEntity().getUUID();
        CHARGES_OISEAU.remove(id);
        OISEAUX.remove(id);
        GUEPARDS.remove(id);
        CHARGES.remove(id);
        RETOUR.remove(id);
    }

    @SubscribeEvent
    public static void arret(ServerStoppingEvent event) {
        CHARGES.clear();
        RETOUR.clear();
        GUEPARDS.clear();
        PRET_GUEPARD.clear();
        PLAQUES.clear();
        FIN_PLAQUE.clear();
        CHARGES_OISEAU.clear();
        OISEAUX.clear();
        PRET_OISEAU.clear();
    }

    private Foudre3() {
    }
}
