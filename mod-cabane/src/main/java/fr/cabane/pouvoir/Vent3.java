package fr.cabane.pouvoir;

import fr.cabane.Cabane;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Abilities;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.joml.Vector3f;

/**
 * Le niveau 3 du Vent (liste « vent3 »), l'esprit du vent, d'après docs/vent/planche-attaques.png, équilibré comme un
 * niveau 4. Il vole (le double saut), +60 % de vitesse, l'armure du chevalier du Susano 3 (voir {@link Vent}).
 * <ul>
 * <li>clic gauche : la hallebarde +6, et la lame de vent continue 8 blocs devant lui : +5 à tous ;</li>
 * <li>clic droit : les 4 lances du dos partent là où il vise et cherchent leur cible (6 chacune), puis reviennent (6 s) ;</li>
 * <li>clic droit tenu : la tempête, une tornade couchée de 30 blocs sort de la hallebarde et aspire (3 par 1/2 s, 4 s ; 20 s) ;</li>
 * <li>accroupi : la répulsion céleste en grand (14 blocs, 16) ; en vol, il pique d'abord au sol (20 s) ;</li>
 * <li>la touche de l'ulti : la tornade royale. Tout ce qui est à 25 blocs est aspiré et tranché dans une tornade géante,
 * puis il lève la hallebarde et tout explose : 30 au milieu, 16 sur chacun. Une fois par éveil 3, puis 5 min.</li>
 * </ul>
 */
@EventBusSubscriber(modid = Cabane.MODID)
public final class Vent3 {
    public static final int RECHARGE_LAME = 16;
    /** Les lances reviennent en 6 s : elles ne repartent qu'une fois rentrées. */
    public static final int RECHARGE_LANCES = 6 * 20;
    public static final int RECHARGE_TEMPETE = 20 * 20;
    public static final int DUREE_TEMPETE = 4 * 20;
    public static final int RECHARGE_ULTI = 5 * 60 * 20;
    public static final double LONGUEUR_TEMPETE = 30;
    public static final double RAYON_ULTI = 25;
    /** L'ulti (en ticks depuis le début) : il monte, la tornade prend tout, il lève la hallebarde, tout explose, la fin. */
    public static final int ULTI_PRISE = 10;
    public static final int ULTI_LEVE = 80;
    public static final int ULTI_EXPLOSION = 92;
    public static final int ULTI_FIN = 130;
    /** Combien il monte au-dessus du milieu de la tornade. */
    public static final double ULTI_HAUT = 22;

    private static final double PORTEE_LAME = 8;
    private static final float DEGATS_LAME = 5;
    private static final float DEGATS_LANCE = 6;
    private static final double VITESSE_LANCE = 1.5;
    private static final float DEGATS_TEMPETE = 3;
    private static final double RAYON_REPULSION = 14;
    private static final float DEGATS_REPULSION = 16;
    private static final double RAYON_CENTRE = 6;
    private static final float DEGATS_CENTRE = 30;
    private static final float DEGATS_ZONE = 16;

    private static final Map<UUID, Integer> PRET_LAME = new HashMap<>();
    private static final Map<UUID, Integer> PRET_LANCES = new HashMap<>();
    private static final Map<UUID, Integer> PRET_TEMPETE = new HashMap<>();
    private static final Map<UUID, Integer> PRET_REPULSION = new HashMap<>();

    private static boolean trois(ServerPlayer joueur) {
        return Vent.niveau(joueur) >= 3;
    }

    // ================================================================ clic gauche : la lame de vent

    /** La lame de la hallebarde continue 8 blocs devant lui : tout ce qu'elle traverse prend 5. */
    static void lame(ServerPlayer joueur) {
        if (!trois(joueur) || !Vent.tient(joueur) || !Vent.pret(PRET_LAME, joueur)) {
            return;
        }
        PRET_LAME.put(joueur.getUUID(), joueur.server.getTickCount() + RECHARGE_LAME);
        Vec3 regard = joueur.getLookAngle();
        Vec3 a = joueur.getEyePosition().add(0, -0.4, 0), b = a.add(regard.scale(PORTEE_LAME));
        ServerLevel monde = joueur.serverLevel();
        for (LivingEntity e : monde.getEntitiesOfClass(LivingEntity.class, new AABB(a, b).inflate(2.5), e -> Vent.touchable(joueur, e))) {
            if (Vent.distanceAuSegment(Vent.milieu(e), a, b) > 1.4 + e.getBbWidth() * 0.5) {
                continue;
            }
            Vent.blesser(joueur, e, DEGATS_LAME);
            e.setDeltaMovement(e.getDeltaMovement().add(regard.x * 0.5, 0.15, regard.z * 0.5));
            e.hurtMarked = true;
        }
        Vent.effet(joueur, Vent.E_LAME, 0, a, regard, 0);
        Vent.son(monde, a, SoundEvents.PLAYER_ATTACK_SWEEP, 1.2F, 0.7F);
        Vent.son(monde, a, SoundEvents.BREEZE_SHOOT, 1F, 1.3F);
    }

    // ================================================================ clic droit : les quatre lances

    /** Une lance : où elle est, vers où, sa cible (ou le point visé), si elle revient, ce qu'elle a touché. */
    private static final class Lance {
        Vec3 pos;
        Vec3 dir;
        int cible;
        Vec3 point;
        boolean retour;
        boolean rentree;
        final Set<Integer> touches = new HashSet<>();
    }

    private static final class Volee {
        final ServerLevel monde;
        final int debut;
        final Lance[] lances = new Lance[4];

        Volee(ServerLevel monde, int debut) {
            this.monde = monde;
            this.debut = debut;
        }
    }

    private static final Map<UUID, Volee> VOLEES = new HashMap<>();

    /** Où flotte chaque lance dans son dos (deux X au-dessus des épaules), dans le monde. */
    static Vec3 dansLeDos(ServerPlayer joueur, int k) {
        float lacet = joueur.yBodyRot * (float) Math.PI / 180;
        Vec3 devant = new Vec3(-Math.sin(lacet), 0, Math.cos(lacet)), droite = new Vec3(-Math.cos(lacet), 0, -Math.sin(lacet));
        double[] cote = {-0.7, -0.25, 0.25, 0.7};
        return joueur.position().add(droite.scale(cote[k])).add(devant.scale(-0.45)).add(0, 1.85 + (k == 1 || k == 2 ? 0.3 : 0), 0);
    }

    static void lances(ServerPlayer joueur) {
        UUID id = joueur.getUUID();
        if (!trois(joueur) || !Vent.tient(joueur) || VOLEES.containsKey(id) || !Vent.pret(PRET_LANCES, joueur)) {
            return;
        }
        int maintenant = joueur.server.getTickCount();
        PRET_LANCES.put(id, maintenant + RECHARGE_LANCES);
        ServerLevel monde = joueur.serverLevel();
        Vec3 oeil = joueur.getEyePosition(), regard = joueur.getLookAngle();
        Vec3 loin = oeil.add(regard.scale(48));
        HitResult bloc = monde.clip(new ClipContext(oeil, loin, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, joueur));
        Vec3 vise = bloc.getType() == HitResult.Type.MISS ? loin : bloc.getLocation();
        // les cibles : ce qui est près de la ligne visée, les plus proches de la ligne d'abord
        List<LivingEntity> cibles = new ArrayList<>(monde.getEntitiesOfClass(LivingEntity.class, new AABB(oeil, vise).inflate(5),
                e -> Vent.touchable(joueur, e) && Vent.distanceAuSegment(Vent.milieu(e), oeil, vise) < 5));
        cibles.sort(Comparator.comparingDouble(e -> Vent.distanceAuSegment(Vent.milieu(e), oeil, vise)));
        Volee v = new Volee(monde, maintenant);
        for (int k = 0; k < 4; k++) {
            Lance l = new Lance();
            l.pos = dansLeDos(joueur, k);
            // elles partent en éventail, vers le haut, puis tournent vers leur cible
            l.dir = Vent.tourner(regard, (k - 1.5) * 22).add(0, 0.55, 0).normalize();
            l.cible = cibles.isEmpty() ? -1 : cibles.get(k % cibles.size()).getId();
            l.point = vise.add((k - 1.5) * 0.8, 0, 0);
            v.lances[k] = l;
        }
        VOLEES.put(id, v);
        Vent.son(monde, joueur.position(), SoundEvents.TRIDENT_RIPTIDE_1.value(), 1.4F, 1.3F);
        Vent.son(monde, joueur.position(), SoundEvents.BREEZE_SHOOT, 1.4F, 0.9F);
    }

    /** Un tick des lances : elles cherchent leur cible, la touchent, puis reviennent dans son dos. */
    private static boolean volee(ServerPlayer joueur, Volee v, int maintenant) {
        int t = maintenant - v.debut;
        List<Vector3f> points = new ArrayList<>();
        boolean toutes = true;
        for (int k = 0; k < 4; k++) {
            Lance l = v.lances[k];
            if (l.rentree) {
                continue;
            }
            Vec3 but;
            if (l.retour || t > 60) {
                l.retour = true;
                but = dansLeDos(joueur, k);
            } else {
                Entity c = l.cible >= 0 ? v.monde.getEntity(l.cible) : null;
                but = c instanceof LivingEntity vivant && vivant.isAlive() ? Vent.milieu(vivant) : l.point;
            }
            Vec3 vers = but.subtract(l.pos);
            double d = vers.length();
            // elles tournent de plus en plus vite vers leur but
            double vire = Math.min(1, 0.12 + t * 0.02 + (l.retour ? 0.3 : 0));
            if (d > 1e-3) {
                l.dir = l.dir.add(vers.scale(1 / d).subtract(l.dir).scale(vire)).normalize();
            }
            double pas = l.retour ? VITESSE_LANCE * 1.3 : VITESSE_LANCE;
            l.pos = d < pas ? but : l.pos.add(l.dir.scale(pas));
            if (l.retour) {
                if (l.pos.distanceTo(dansLeDos(joueur, k)) < 1.2) {
                    l.rentree = true;
                    continue;
                }
            } else {
                // elles touchent ce qu'elles croisent (et rebroussent chemin sur leur cible, ou arrivées au point visé)
                for (LivingEntity e : v.monde.getEntitiesOfClass(LivingEntity.class, new AABB(l.pos, l.pos).inflate(1.2),
                        e -> Vent.touchable(joueur, e) && !l.touches.contains(e.getId()))) {
                    l.touches.add(e.getId());
                    Vent.blesser(joueur, e, DEGATS_LANCE);
                    e.setDeltaMovement(e.getDeltaMovement().add(l.dir.x * 0.4, 0.15, l.dir.z * 0.4));
                    e.hurtMarked = true;
                    Vent.son(v.monde, e.position(), SoundEvents.TRIDENT_HIT, 1F, 1.2F);
                    if (e.getId() == l.cible) {
                        l.retour = true;
                    }
                }
                if (l.pos.distanceTo(but) < 0.6) {
                    l.retour = true;
                }
            }
            toutes = false;
            points.add(Vent.v(l.pos));
            points.add(Vent.v(l.dir));
        }
        Vent.envoyer(v.monde, new Vent.Lances(joueur.getUUID(), points), joueur.position());
        return toutes;
    }

    // ================================================================ clic droit tenu : la tempête

    private static final Map<UUID, Integer> TEMPETES = new HashMap<>();

    static void tempete(ServerPlayer joueur) {
        UUID id = joueur.getUUID();
        if (!trois(joueur) || !Vent.tient(joueur) || TEMPETES.containsKey(id) || !Vent.pret(PRET_TEMPETE, joueur)) {
            return;
        }
        int maintenant = joueur.server.getTickCount();
        PRET_TEMPETE.put(id, maintenant + RECHARGE_TEMPETE);
        TEMPETES.put(id, maintenant);
        Vent.effet(joueur, Vent.E_TEMPETE, 0, joueur.getEyePosition(), joueur.getLookAngle(), DUREE_TEMPETE);
        ServerLevel monde = joueur.serverLevel();
        Vent.son(monde, joueur.position(), SoundEvents.BREEZE_WHIRL, 3F, 0.5F);
        Vent.son(monde, joueur.position(), SoundEvents.BREEZE_WIND_CHARGE_BURST.value(), 2F, 0.6F);
    }

    /** Un tick de la tempête : la tornade couchée suit son regard, aspire ce qu'elle touche et le fait tourner. */
    private static void tempeter(ServerPlayer joueur, int t) {
        ServerLevel monde = joueur.serverLevel();
        Vec3 regard = joueur.getLookAngle();
        Vec3 droite = new Vec3(-regard.z, 0, regard.x).normalize();
        Vec3 a = joueur.getEyePosition().add(regard.scale(1.6)).add(droite.scale(0.45)).add(0, -0.35, 0), b = a.add(regard.scale(LONGUEUR_TEMPETE));
        joueur.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 3, 2, false, false));
        for (LivingEntity e : monde.getEntitiesOfClass(LivingEntity.class, new AABB(a, b).inflate(6), e -> Vent.touchable(joueur, e))) {
            Vec3 m = Vent.milieu(e);
            double u = Math.max(0, Math.min(1, m.subtract(a).dot(regard) / LONGUEUR_TEMPETE));
            Vec3 axe = a.add(regard.scale(u * LONGUEUR_TEMPETE));
            double r = 1.5 + 4 * u;
            Vec3 vers = axe.subtract(m);
            double d = vers.length();
            if (d > r + 1.5) {
                continue;
            }
            // aspiré vers le cœur de la tornade, il tourne autour et file le long
            Vec3 tour = regard.cross(vers.lengthSqr() < 1e-4 ? new Vec3(0, 1, 0) : vers.normalize());
            Vec3 elan = (d > 1e-3 ? vers.scale(0.22 / Math.max(1, d) * Math.min(d, 2)) : Vec3.ZERO).add(tour.scale(0.35)).add(regard.scale(0.12)).add(0, 0.09, 0);
            e.setDeltaMovement(elan);
            e.hurtMarked = true;
            e.fallDistance = 0;
            if (t % 10 == 0) {
                Vent.blesser(joueur, e, DEGATS_TEMPETE);
            }
        }
        if (t % 12 == 0) {
            Vent.son(monde, b, SoundEvents.BREEZE_WHIRL, 2F, 0.6F);
        }
    }

    // ================================================================ accroupi : la répulsion en grand (et le piqué)

    private static final Map<UUID, Integer> PIQUES = new HashMap<>();

    static void repulsion(ServerPlayer joueur) {
        UUID id = joueur.getUUID();
        if (!trois(joueur) || PIQUES.containsKey(id) || !Vent.pret(PRET_REPULSION, joueur)) {
            return;
        }
        int maintenant = joueur.server.getTickCount();
        PRET_REPULSION.put(id, maintenant + Vent.RECHARGE_REPULSION);
        ServerLevel monde = joueur.serverLevel();
        HitResult sol = monde.clip(new ClipContext(joueur.position(), joueur.position().add(0, -3, 0), ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY, joueur));
        if (joueur.onGround() || sol.getType() != HitResult.Type.MISS) {
            // au sol : bras en T, le vent se ramasse, comme au niveau 2
            Vent.repulsion(joueur, RAYON_REPULSION, DEGATS_REPULSION, false, true);
            return;
        }
        // en vol : il pique d'abord au sol
        Abilities a = joueur.getAbilities();
        a.flying = false;
        joueur.onUpdateAbilities();
        PIQUES.put(id, maintenant);
        Vent.effet(joueur, Vent.E_PIQUE, 0, joueur.position(), new Vec3(0, -1, 0), 0);
        Vent.son(monde, joueur.position(), SoundEvents.PHANTOM_SWOOP, 2F, 0.7F);
    }

    // ================================================================ l'ulti : la tornade royale

    /** Une tornade royale : où (le milieu au sol), depuis quand, d'où il est parti ; ce qu'elle a pris (et d'où). */
    private static final class Royale {
        final ServerLevel monde;
        final Vec3 centre;
        final int debut;
        final Vec3 depart;
        final Map<Integer, double[]> pris = new HashMap<>();
        boolean explose;

        Royale(ServerLevel monde, Vec3 centre, int debut, Vec3 depart) {
            this.monde = monde;
            this.centre = centre;
            this.debut = debut;
            this.depart = depart;
        }
    }

    private static final Map<UUID, Royale> ROYALES = new HashMap<>();
    private static final Set<UUID> FAITS = new HashSet<>();
    private static final Map<UUID, Integer> PRET_ULTI = new HashMap<>();

    public static boolean ultiActif(ServerPlayer joueur) {
        return ROYALES.containsKey(joueur.getUUID());
    }

    private static void message(ServerPlayer joueur, String texte) {
        joueur.displayClientMessage(Component.literal(texte).withStyle(ChatFormatting.GREEN), true);
    }

    /** La touche de l'ulti (celle de Hachibi 4) : seulement au niveau 3, une fois par éveil, et rechargée. */
    static void ulti(ServerPlayer joueur) {
        if (!trois(joueur)) {
            message(joueur, "La tornade royale se réveille au niveau 3.");
            return;
        }
        UUID id = joueur.getUUID();
        if (ROYALES.containsKey(id)) {
            return;
        }
        if (FAITS.contains(id)) {
            message(joueur, "La tornade royale, c'est une fois par éveil.");
            return;
        }
        int maintenant = joueur.server.getTickCount();
        int reste = PRET_ULTI.getOrDefault(id, 0) - maintenant;
        if (reste > 0) {
            message(joueur, "La tornade royale se recharge… encore " + (reste + 19) / 20 + " s");
            return;
        }
        ServerLevel monde = joueur.serverLevel();
        HitResult sol = monde.clip(new ClipContext(joueur.position().add(0, 0.5, 0), joueur.position().add(0, -40, 0), ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY, joueur));
        Vec3 centre = sol.getType() == HitResult.Type.MISS ? joueur.position() : sol.getLocation();
        FAITS.add(id);
        PRET_ULTI.put(id, maintenant + ULTI_FIN + RECHARGE_ULTI);
        Royale r = new Royale(monde, centre, maintenant, joueur.position());
        ROYALES.put(id, r);
        joueur.stopRiding();
        Vent.effet(joueur, Vent.E_ULTI, 0, centre, Vec3.ZERO, (int) RAYON_ULTI);
        monde.playSound(null, centre.x, centre.y, centre.z, SoundEvents.BREEZE_WHIRL, SoundSource.PLAYERS, 6F, 0.4F);
        monde.playSound(null, centre.x, centre.y, centre.z, SoundEvents.BREEZE_WIND_CHARGE_BURST.value(), SoundSource.PLAYERS, 6F, 0.5F);
    }

    /** Un tick de la tornade royale. */
    private static boolean royale(ServerPlayer joueur, Royale r, int maintenant) {
        int t = maintenant - r.debut;
        // lui : il monte au-dessus de la tornade et y reste
        double k = Math.min(1, t / 20.0);
        Vec3 haut = r.centre.add(0, ULTI_HAUT * k * k * (3 - 2 * k), 0);
        joueur.teleportTo(haut.x, haut.y, haut.z);
        joueur.setDeltaMovement(Vec3.ZERO);
        joueur.hurtMarked = true;
        joueur.fallDistance = 0;
        if (t == ULTI_PRISE) {
            for (LivingEntity e : r.monde.getEntitiesOfClass(LivingEntity.class, new AABB(r.centre, r.centre).inflate(RAYON_ULTI, 20, RAYON_ULTI),
                    e -> e != joueur && Vent.touchable(joueur, e))) {
                Vec3 loin = e.position().subtract(r.centre);
                double d = loin.horizontalDistance();
                if (d <= RAYON_ULTI) {
                    r.pris.put(e.getId(), new double[]{Math.atan2(loin.z, loin.x), d, e.getY() - r.centre.y});
                }
            }
        }
        if (t >= ULTI_PRISE && t < ULTI_EXPLOSION) {
            // aspirés : ils tournent dans la tornade géante en montant, de plus en plus près du milieu
            double u = Math.min(1, (t - ULTI_PRISE) / (double) (ULTI_LEVE - ULTI_PRISE));
            r.pris.entrySet().removeIf(en -> {
                Entity e = r.monde.getEntity(en.getKey());
                if (!(e instanceof LivingEntity l) || !l.isAlive()) {
                    return true;
                }
                double[] p = en.getValue();
                double a = p[0] + (t - ULTI_PRISE) * (0.16 + 0.1 * u);
                double rr = p[1] + (5 + 4 * (en.getKey() % 3) - p[1]) * Math.min(1, u * 1.6);
                double y = p[2] + (7 + (en.getKey() % 5) * 4 - p[2]) * Math.min(1, u * 1.3);
                l.teleportTo(r.centre.x + Math.cos(a) * rr, r.centre.y + Math.max(0, y), r.centre.z + Math.sin(a) * rr);
                l.setDeltaMovement(Vec3.ZERO);
                l.fallDistance = 0;
                l.hurtMarked = true;
                return false;
            });
            if (t % 6 == 0) {
                r.monde.playSound(null, r.centre.x, r.centre.y + 8, r.centre.z, SoundEvents.PLAYER_ATTACK_SWEEP, SoundSource.PLAYERS, 3F, 0.6F + r.monde.random.nextFloat() * 0.6F);
            }
            if (t % 16 == 0) {
                r.monde.playSound(null, r.centre.x, r.centre.y + 10, r.centre.z, SoundEvents.BREEZE_WHIRL, SoundSource.PLAYERS, 5F, 0.4F + t / 300F);
            }
        }
        if (t == ULTI_LEVE) {
            Vent.effet(joueur, Vent.E_ULTI, 1, r.centre, Vec3.ZERO, (int) RAYON_ULTI);
            r.monde.playSound(null, haut.x, haut.y, haut.z, SoundEvents.BREEZE_INHALE, SoundSource.PLAYERS, 5F, 0.5F);
            r.monde.playSound(null, haut.x, haut.y, haut.z, SoundEvents.BEACON_POWER_SELECT, SoundSource.PLAYERS, 3F, 1.4F);
        }
        if (t >= ULTI_EXPLOSION && !r.explose) {
            r.explose = true;
            exploser(joueur, r);
        }
        if (t >= ULTI_FIN) {
            // il redescend doucement : il vole toujours
            Vent.voler(joueur, true);
            return true;
        }
        return false;
    }

    /** Il lève la hallebarde : tout explose. 30 à ceux qui étaient au milieu, 16 à chacun des autres ; tout le monde vole. */
    private static void exploser(ServerPlayer joueur, Royale r) {
        Vent.effet(joueur, Vent.E_ULTI, 2, r.centre, Vec3.ZERO, (int) RAYON_ULTI);
        ServerLevel monde = r.monde;
        monde.playSound(null, r.centre.x, r.centre.y, r.centre.z, SoundEvents.GENERIC_EXPLODE.value(), SoundSource.PLAYERS, 8F, 0.5F);
        monde.playSound(null, r.centre.x, r.centre.y, r.centre.z, SoundEvents.BREEZE_WIND_CHARGE_BURST.value(), SoundSource.PLAYERS, 8F, 0.4F);
        monde.playSound(null, r.centre.x, r.centre.y, r.centre.z, SoundEvents.WIND_CHARGE_BURST.value(), SoundSource.PLAYERS, 8F, 0.4F);
        monde.playSound(null, r.centre.x, r.centre.y, r.centre.z, SoundEvents.WARDEN_SONIC_BOOM, SoundSource.PLAYERS, 4F, 0.8F);
        for (Map.Entry<Integer, double[]> en : r.pris.entrySet()) {
            if (!(monde.getEntity(en.getKey()) instanceof LivingEntity e) || !e.isAlive()) {
                continue;
            }
            Vent.blesser(joueur, e, en.getValue()[1] <= RAYON_CENTRE ? DEGATS_CENTRE : DEGATS_ZONE);
            Vec3 loin = e.position().subtract(r.centre);
            Vec3 dehors = loin.horizontalDistanceSqr() < 0.01 ? new Vec3(1, 0, 0) : new Vec3(loin.x, 0, loin.z).normalize();
            e.setDeltaMovement(dehors.x * 2.2, 1.1, dehors.z * 2.2);
            e.hurtMarked = true;
            e.fallDistance = 0;
        }
        r.pris.clear();
        monde.sendParticles(ParticleTypes.GUST_EMITTER_LARGE, r.centre.x, r.centre.y + 4, r.centre.z, 3, 4, 3, 4, 0);
    }

    // ================================================================ ce qui dure

    @SubscribeEvent
    public static void tick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        int maintenant = server.getTickCount();
        VOLEES.entrySet().removeIf(e -> {
            ServerPlayer joueur = server.getPlayerList().getPlayer(e.getKey());
            if (joueur == null || joueur.level() != e.getValue().monde) {
                return true;
            }
            if (volee(joueur, e.getValue(), maintenant)) {
                Vent.envoyer(e.getValue().monde, new Vent.Lances(joueur.getUUID(), List.of()), joueur.position());
                return true;
            }
            return false;
        });
        TEMPETES.entrySet().removeIf(e -> {
            ServerPlayer joueur = server.getPlayerList().getPlayer(e.getKey());
            int t = maintenant - e.getValue();
            if (joueur == null || !joueur.isAlive() || !trois(joueur) || t >= DUREE_TEMPETE) {
                if (joueur != null) {
                    Vent.effet(joueur, Vent.E_TEMPETE, 1, joueur.getEyePosition(), joueur.getLookAngle(), 0);
                }
                return true;
            }
            tempeter(joueur, t);
            return false;
        });
        PIQUES.entrySet().removeIf(e -> {
            ServerPlayer joueur = server.getPlayerList().getPlayer(e.getKey());
            if (joueur == null || !joueur.isAlive()) {
                return true;
            }
            // il fonce droit vers le sol ; au contact (ou au bout de 2 s), la répulsion éclate
            joueur.setDeltaMovement(0, -2.4, 0);
            joueur.hurtMarked = true;
            joueur.fallDistance = 0;
            if (joueur.onGround() || maintenant - e.getValue() > 40) {
                Vent.repulsion(joueur, RAYON_REPULSION, DEGATS_REPULSION, false, false);
                Vent.son(joueur.serverLevel(), joueur.position(), SoundEvents.MACE_SMASH_GROUND_HEAVY, 2F, 0.8F);
                return true;
            }
            return false;
        });
        ROYALES.entrySet().removeIf(e -> {
            ServerPlayer joueur = server.getPlayerList().getPlayer(e.getKey());
            if (joueur == null || !joueur.isAlive() || joueur.level() != e.getValue().monde) {
                if (joueur != null) {
                    Vent.effet(joueur, Vent.E_ULTI, 3, e.getValue().centre, Vec3.ZERO, 0);
                }
                return true;
            }
            return royale(joueur, e.getValue(), maintenant);
        });
    }

    /** Il s'endort : les lances rentrent, la tempête et la tornade royale s'arrêtent ; au prochain éveil l'ulti pourra repartir. */
    static void endormir(ServerPlayer joueur) {
        UUID id = joueur.getUUID();
        if (VOLEES.remove(id) != null) {
            Vent.envoyer(joueur.serverLevel(), new Vent.Lances(id, List.of()), joueur.position());
        }
        if (TEMPETES.remove(id) != null) {
            Vent.effet(joueur, Vent.E_TEMPETE, 1, joueur.getEyePosition(), joueur.getLookAngle(), 0);
        }
        Royale r = ROYALES.remove(id);
        if (r != null) {
            Vent.effet(joueur, Vent.E_ULTI, 3, r.centre, Vec3.ZERO, 0);
        }
        PIQUES.remove(id);
        FAITS.remove(id);
        PRET_LAME.remove(id);
        PRET_LANCES.remove(id);
        PRET_TEMPETE.remove(id);
        PRET_REPULSION.remove(id);
    }

    @SubscribeEvent
    public static void deconnexion(PlayerEvent.PlayerLoggedOutEvent event) {
        UUID id = event.getEntity().getUUID();
        VOLEES.remove(id);
        TEMPETES.remove(id);
        PIQUES.remove(id);
        ROYALES.remove(id);
    }

    @SubscribeEvent
    public static void arret(ServerStoppingEvent event) {
        VOLEES.clear();
        TEMPETES.clear();
        PIQUES.clear();
        ROYALES.clear();
        FAITS.clear();
        PRET_LAME.clear();
        PRET_LANCES.clear();
        PRET_TEMPETE.clear();
        PRET_REPULSION.clear();
        PRET_ULTI.clear();
    }

    private Vent3() {
    }
}
