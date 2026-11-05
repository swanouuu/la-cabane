package fr.cabane.pouvoir;

import fr.cabane.Cabane;
import fr.cabane.CabaneSons;
import fr.cabane.client.pouvoir.ClientOrage;
import io.netty.buffer.ByteBuf;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
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
import net.neoforged.neoforge.event.entity.living.LivingFallEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import org.joml.Vector3f;

/**
 * L'ultime de la Foudre 3 (liste blanche « foudre3 », niveau 3, touche de l'ulti), d'après docs/foudre3/apercu-ulti.mp4.
 * Là où il est, un grand cercle (20 blocs) devient une zone électrique, et d'un coup un nuage d'orage géant apparaît très
 * haut au-dessus (150 blocs). Tous ceux qui sont dans la zone sont paralysés ; des molécules d'orage montent d'eux jusqu'au
 * nuage. Il saute dans le nuage à la vitesse de l'éclair et y disparaît, l'orage se charge, puis il retombe au milieu avec
 * le gros orage : une colonne de foudre énorme au milieu, et une foudre sur chacun. Tout ce qu'on voit est dessiné par
 * {@link ClientOrage} ; ici, le temps, les gens figés, son saut, les dégâts et les sons. Rien ne casse, rien ne brûle.
 * Une fois par éveil 3, puis 5 minutes de recharge (comme les autres ultis).
 */
@EventBusSubscriber(modid = Cabane.MODID)
public final class Orage {
    public static final int RECHARGE = 5 * 60 * 20;
    /** Le rayon de la zone (en blocs), et la hauteur du nuage au-dessus d'elle. */
    public static final double RAYON = 20;
    public static final double HAUT = 150;
    /** Le temps (en ticks, depuis le début) : la zone gagne tout le monde, il saute, il est dans le nuage, il retombe, l'impact. */
    public static final int FIGE = 8;
    public static final int SAUT = 20;
    public static final int DANS_LE_NUAGE = 26;
    public static final int CHUTE = 56;
    public static final int IMPACT = 60;
    /** Après l'atterrissage, l'orage gronde encore longtemps : la foudre tombe tout autour pendant 10 s. */
    public static final int APRES = 10 * 20;
    /** Les dégâts : la colonne au milieu (6 blocs), la foudre sur chacun des autres dans la zone. */
    private static final double RAYON_CENTRE = 6;
    private static final float DEGATS_CENTRE = 30;
    private static final float DEGATS_ZONE = 16;

    /** Un orage en cours : qui, où (ses pieds au sol), depuis quand, le haut du nuage, qui est figé et où. */
    private static final class EnCours {
        final ServerLevel monde;
        final Vec3 centre;
        final int debut;
        final double haut;
        final Map<Integer, Vec3> figes = new HashMap<>();
        boolean impact;

        EnCours(ServerLevel monde, Vec3 centre, int debut, double haut) {
            this.monde = monde;
            this.centre = centre;
            this.debut = debut;
            this.haut = haut;
        }
    }

    private static final Map<UUID, EnCours> ORAGES = new HashMap<>();
    /** L'ulti est déjà fait pendant cet éveil ; quand il sera de nouveau prêt (ticks du serveur). */
    private static final Set<UUID> FAITS = new HashSet<>();
    private static final Map<UUID, Integer> PRET = new HashMap<>();
    /** Retombé du nuage : pas de dégâts de chute avant d'avoir touché le sol. */
    private static final Map<UUID, Integer> SANS_CHUTE = new HashMap<>();

    // ================================================================ réseau

    public static final int DEBUT = 0;
    public static final int FIGES = 1;
    public static final int ANNULE = 2;

    /** Serveur → ceux qui sont autour : l'orage de ce joueur commence (DEBUT), ceux qu'il a figés (FIGES), ou il s'arrête (ANNULE). */
    public record Etat(UUID joueur, int phase, Vector3f centre, float haut, List<Integer> figes) implements CustomPacketPayload {
        public static final Type<Etat> TYPE = new Type<>(Cabane.id("foudre_orage"));
        public static final StreamCodec<ByteBuf, Etat> CODEC = StreamCodec.composite(
                UUIDUtil.STREAM_CODEC, Etat::joueur, ByteBufCodecs.VAR_INT, Etat::phase, ByteBufCodecs.VECTOR3F, Etat::centre,
                ByteBufCodecs.FLOAT, Etat::haut, ByteBufCodecs.VAR_INT.apply(ByteBufCodecs.list()), Etat::figes, Etat::new);

        @Override
        public Type<Etat> type() {
            return TYPE;
        }
    }

    @SubscribeEvent
    public static void reseau(RegisterPayloadHandlersEvent event) {
        event.registrar("1").playToClient(Etat.TYPE, Etat.CODEC, (p, ctx) -> ctx.enqueueWork(() -> ClientOrage.recevoir(p)));
    }

    /** À tous ceux qui sont à moins de 256 blocs de la zone (le nuage se voit de loin). */
    private static void envoyer(EnCours o, CustomPacketPayload paquet) {
        for (ServerPlayer p : o.monde.players()) {
            if (p.position().distanceToSqr(o.centre) < 256 * 256) {
                PacketDistributor.sendToPlayer(p, paquet);
            }
        }
    }

    private static Vector3f v(Vec3 p) {
        return new Vector3f((float) p.x, (float) p.y, (float) p.z);
    }

    private static void son(ServerLevel monde, Vec3 ou, SoundEvent son, float volume, float hauteur) {
        monde.playSound(null, ou.x, ou.y, ou.z, son, SoundSource.PLAYERS, volume, hauteur);
    }

    private static void message(ServerPlayer joueur, String texte) {
        joueur.displayClientMessage(Component.literal(texte).withStyle(ChatFormatting.AQUA), true);
    }

    // ================================================================ la touche de l'ulti

    /** La touche de l'ulti (celle de Hachibi 4) : seulement au niveau 3, une fois par éveil, et rechargée. */
    static void lancer(ServerPlayer joueur) {
        if (Pouvoirs.niveau(joueur) < 3) {
            message(joueur, "L'orage se réveille au niveau 3.");
            return;
        }
        UUID id = joueur.getUUID();
        if (ORAGES.containsKey(id)) {
            return;
        }
        if (FAITS.contains(id)) {
            message(joueur, "L'orage, c'est une fois par éveil.");
            return;
        }
        int maintenant = joueur.server.getTickCount();
        int reste = PRET.getOrDefault(id, 0) - maintenant;
        if (reste > 0) {
            message(joueur, "L'orage se recharge… encore " + (reste + 19) / 20 + " s");
            return;
        }
        ServerLevel monde = joueur.serverLevel();
        // le milieu de la zone : sous ses pieds (s'il est en l'air, le sol en dessous)
        Vec3 pieds = joueur.position();
        HitResult sol = monde.clip(new ClipContext(pieds.add(0, 0.5, 0), pieds.add(0, -40, 0), ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY, joueur));
        Vec3 centre = sol.getType() == HitResult.Type.MISS ? pieds : sol.getLocation();
        // le nuage reste sous le plafond du monde (de quoi garder de la place au-dessus pour la tête du joueur)
        double haut = Math.max(40, Math.min(HAUT, monde.getMaxBuildHeight() + 60 - centre.y));
        FAITS.add(id);
        PRET.put(id, maintenant + IMPACT + RECHARGE);
        EnCours o = new EnCours(monde, centre, maintenant, haut);
        ORAGES.put(id, o);
        joueur.stopRiding();
        envoyer(o, new Etat(id, DEBUT, v(centre), (float) haut, List.of()));
        son(monde, centre, SoundEvents.BEACON_POWER_SELECT, 3F, 0.5F);
        son(monde, centre, CabaneSons.FOUDRE_CHARGE.get(), 3F, 0.6F);
        monde.playSound(null, centre.x, centre.y + haut, centre.z, SoundEvents.LIGHTNING_BOLT_THUNDER, SoundSource.WEATHER, 12F, 0.5F);
    }

    // ================================================================ ce qui dure

    @SubscribeEvent
    public static void tick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        int maintenant = server.getTickCount();
        for (Map.Entry<UUID, EnCours> e : new ArrayList<>(ORAGES.entrySet())) {
            ServerPlayer joueur = server.getPlayerList().getPlayer(e.getKey());
            EnCours o = e.getValue();
            if (joueur == null || !joueur.isAlive() || joueur.level() != o.monde) {
                arreter(e.getKey(), joueur, o);
                continue;
            }
            int t = maintenant - o.debut;
            if (t == FIGE) {
                figer(joueur, o);
            }
            if (t >= FIGE && t < IMPACT) {
                tenirLesFiges(o);
            }
            if (t <= IMPACT) {
                placer(joueur, o, t);
            }
            sons(o, t);
            if (t >= IMPACT && !o.impact) {
                o.impact = true;
                impact(joueur, o);
            }
            if (t >= IMPACT + APRES) {
                ORAGES.remove(e.getKey());
            }
        }
        SANS_CHUTE.entrySet().removeIf(e -> {
            ServerPlayer joueur = server.getPlayerList().getPlayer(e.getKey());
            return joueur == null || maintenant - e.getValue() > 10 * 20 || maintenant - e.getValue() > 3 && joueur.onGround();
        });
    }

    /** La zone gagne tout le monde : chacun reste là où il est jusqu'à l'impact. */
    private static void figer(ServerPlayer joueur, EnCours o) {
        for (LivingEntity e : o.monde.getEntitiesOfClass(LivingEntity.class, new AABB(o.centre, o.centre).inflate(RAYON, 12, RAYON),
                e -> e != joueur && Pouvoirs.cible(joueur, e) && !e.isSpectator())) {
            if (e.position().subtract(o.centre).horizontalDistance() <= RAYON) {
                o.figes.put(e.getId(), e.position());
                e.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, IMPACT - FIGE + 10, 6, false, false));
                e.addEffect(new MobEffectInstance(MobEffects.JUMP, IMPACT - FIGE + 10, 128, false, false));
                son(o.monde, e.position(), CabaneSons.FOUDRE_ZAP.get(), 1F, 0.8F);
            }
        }
        envoyer(o, new Etat(joueur.getUUID(), FIGES, v(o.centre), (float) o.haut, new ArrayList<>(o.figes.keySet())));
    }

    /** Paralysés : ni marcher ni sauter, ils restent où la zone les a pris (ils peuvent tourner la tête). */
    private static void tenirLesFiges(EnCours o) {
        o.figes.entrySet().removeIf(f -> {
            Entity e = o.monde.getEntity(f.getKey());
            if (!(e instanceof LivingEntity l) || !l.isAlive()) {
                return true;
            }
            Vec3 p = f.getValue();
            if (l.position().distanceToSqr(p) > 0.01) {
                l.teleportTo(p.x, p.y, p.z);
            }
            l.setDeltaMovement(0, Math.min(0, l.getDeltaMovement().y), 0);
            l.hurtMarked = true;
            return false;
        });
    }

    /** Lui : au sol jusqu'au saut ; il file dans le nuage (6 ticks), y reste caché, puis retombe au milieu (4 ticks). */
    private static void placer(ServerPlayer joueur, EnCours o, int t) {
        if (t < SAUT) {
            return;
        }
        double y;
        if (t < DANS_LE_NUAGE) {
            double u = (t - SAUT + 1) / (double) (DANS_LE_NUAGE - SAUT);
            y = o.haut * u * u;
        } else if (t < CHUTE) {
            y = o.haut + 4;
        } else {
            double u = Math.min(1, (t - CHUTE + 1) / (double) (IMPACT - CHUTE));
            y = (o.haut + 4) * (1 - u);
        }
        joueur.teleportTo(o.centre.x, o.centre.y + y, o.centre.z);
        joueur.setDeltaMovement(Vec3.ZERO);
        joueur.hurtMarked = true;
        joueur.fallDistance = 0;
        SANS_CHUTE.put(joueur.getUUID(), joueur.server.getTickCount());
    }

    /** L'orage qui gronde : le saut, le nuage qui se charge, la chute. */
    private static void sons(EnCours o, int t) {
        Vec3 ciel = o.centre.add(0, o.haut, 0);
        if (t == SAUT) {
            son(o.monde, o.centre, SoundEvents.LIGHTNING_BOLT_IMPACT, 4F, 0.8F);
            son(o.monde, o.centre, CabaneSons.MINATO_FLASH.get(), 2F, 0.8F);
            o.monde.playSound(null, ciel.x, ciel.y, ciel.z, SoundEvents.LIGHTNING_BOLT_THUNDER, SoundSource.WEATHER, 12F, 0.7F);
        }
        if (t > DANS_LE_NUAGE && t < CHUTE && (t - DANS_LE_NUAGE) % 8 == 0) {
            o.monde.playSound(null, ciel.x, ciel.y, ciel.z, SoundEvents.LIGHTNING_BOLT_THUNDER, SoundSource.WEATHER, 10F, 0.5F + (t - DANS_LE_NUAGE) / 60F);
        }
        if (t == CHUTE) {
            o.monde.playSound(null, ciel.x, ciel.y, ciel.z, SoundEvents.ENDER_DRAGON_GROWL, SoundSource.WEATHER, 8F, 1.4F);
        }
        // après l'atterrissage : l'orage gronde longtemps, la foudre claque un peu partout autour
        if (t > IMPACT && t < IMPACT + APRES && o.monde.random.nextInt(t < IMPACT + APRES - 60 ? 4 : 9) == 0) {
            double a = o.monde.random.nextDouble() * Math.PI * 2, d = RAYON * 2.5 * Math.sqrt(o.monde.random.nextDouble());
            Vec3 p = o.centre.add(Math.cos(a) * d, 0, Math.sin(a) * d);
            o.monde.playSound(null, p.x, p.y, p.z, SoundEvents.LIGHTNING_BOLT_IMPACT, SoundSource.WEATHER, 3F, 0.6F + o.monde.random.nextFloat() * 0.5F);
            if (o.monde.random.nextInt(3) == 0) {
                o.monde.playSound(null, ciel.x, ciel.y, ciel.z, SoundEvents.LIGHTNING_BOLT_THUNDER, SoundSource.WEATHER, 10F, 0.45F + o.monde.random.nextFloat() * 0.4F);
            }
        }
    }

    /** Il touche le sol : la colonne au milieu, une foudre sur chacun, tout le monde projeté ; les figés sont libres. */
    private static void impact(ServerPlayer joueur, EnCours o) {
        son(o.monde, o.centre, SoundEvents.LIGHTNING_BOLT_THUNDER, 16F, 0.5F);
        son(o.monde, o.centre, SoundEvents.LIGHTNING_BOLT_IMPACT, 8F, 0.5F);
        son(o.monde, o.centre, SoundEvents.GENERIC_EXPLODE.value(), 6F, 0.5F);
        son(o.monde, o.centre, SoundEvents.WARDEN_SONIC_BOOM, 4F, 0.7F);
        joueur.teleportTo(o.centre.x, o.centre.y, o.centre.z);
        for (LivingEntity e : o.monde.getEntitiesOfClass(LivingEntity.class, new AABB(o.centre, o.centre).inflate(RAYON, 14, RAYON),
                e -> e != joueur && Pouvoirs.cible(joueur, e) && !e.isSpectator())) {
            Vec3 loin = e.position().subtract(o.centre);
            double d = loin.horizontalDistance();
            if (d > RAYON && !o.figes.containsKey(e.getId())) {
                continue;
            }
            Foudre.blesser(e, Foudre.source(joueur, Foudre.KIRIN), d <= RAYON_CENTRE ? DEGATS_CENTRE : DEGATS_ZONE);
            e.removeEffect(MobEffects.JUMP);
            e.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 60, 1));
            Vec3 dehors = d < 0.1 ? new Vec3(1, 0, 0) : new Vec3(loin.x, 0, loin.z).normalize();
            double force = 1.6 - 0.8 * Math.min(1, d / RAYON);
            e.setDeltaMovement(dehors.x * force, 0.9, dehors.z * force);
            e.hurtMarked = true;
            son(o.monde, e.position(), SoundEvents.LIGHTNING_BOLT_IMPACT, 2F, 1.1F);
        }
        o.figes.clear();
    }

    // ================================================================ s'arrêter

    /** Il meurt, part ou change de monde pendant l'orage : tout s'arrête (et il redescend s'il était en l'air). */
    private static void arreter(UUID id, ServerPlayer joueur, EnCours o) {
        ORAGES.remove(id);
        for (Map.Entry<Integer, Vec3> f : o.figes.entrySet()) {
            if (o.monde.getEntity(f.getKey()) instanceof LivingEntity l) {
                l.removeEffect(MobEffects.JUMP);
                l.removeEffect(MobEffects.MOVEMENT_SLOWDOWN);
            }
        }
        o.figes.clear();
        if (joueur != null && joueur.isAlive() && joueur.level() == o.monde && joueur.getY() > o.centre.y + 2) {
            joueur.teleportTo(o.centre.x, o.centre.y, o.centre.z);
            joueur.fallDistance = 0;
        }
        envoyer(o, new Etat(id, ANNULE, v(o.centre), (float) o.haut, List.of()));
    }

    /** Il s'endort (la fin de l'éveil, la touche) : un orage en cours s'arrête, et au prochain éveil l'ulti pourra repartir. */
    static void endormir(ServerPlayer joueur) {
        EnCours o = ORAGES.get(joueur.getUUID());
        if (o != null) {
            arreter(joueur.getUUID(), joueur, o);
        }
        FAITS.remove(joueur.getUUID());
    }

    /** Pendant l'orage, l'éveil ne s'endort pas (il ne dure que 3 secondes). */
    public static boolean actif(ServerPlayer joueur) {
        return ORAGES.containsKey(joueur.getUUID());
    }

    @SubscribeEvent
    public static void chute(LivingFallEvent event) {
        if (event.getEntity() instanceof ServerPlayer joueur && (SANS_CHUTE.containsKey(joueur.getUUID()) || ORAGES.containsKey(joueur.getUUID()))) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void deconnexion(PlayerEvent.PlayerLoggedOutEvent event) {
        UUID id = event.getEntity().getUUID();
        EnCours o = ORAGES.get(id);
        if (o != null && event.getEntity() instanceof ServerPlayer joueur) {
            arreter(id, joueur, o);
        }
        SANS_CHUTE.remove(id);
    }

    @SubscribeEvent
    public static void arret(ServerStoppingEvent event) {
        ORAGES.clear();
        FAITS.clear();
        PRET.clear();
        SANS_CHUTE.clear();
    }

    private Orage() {
    }
}
