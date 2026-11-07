package fr.cabane.pouvoir;

import fr.cabane.Cabane;
import fr.cabane.CabaneSons;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * L'ulti d'Ichibi (la touche d'ulti, au niveau 3 de « ichibi3 »), d'après docs/ichibi/apercu/tempete.html : une énorme
 * tempête de sable, sans boost. Il rit et abat ses bras ; tout le sable du coin se soulève en un ouragan géant (40 blocs
 * de rayon, 90 de haut) dont il est l'œil, et qui le suit. Pendant 45 s, à tous ceux qui sont dedans (lui, rien) :
 * <ul>
 * <li>quatre tornades se promènent : ce qu'elles touchent est aspiré en l'air (5) ;</li>
 * <li>neuf lames de sable tournent au ras du sol : 2 à chaque passage ;</li>
 * <li>des éclairs de sable tombent du haut de l'ouragan sur l'un d'eux (6) ;</li>
 * <li>des pics jaillissent sous leurs pieds (4) ;</li>
 * <li>des mains géantes sortent du sable, en attrapent un, le soulèvent et l'écrasent au sol (10) ;</li>
 * <li>et tout le temps : ils ne voient presque rien (le brouillard est chez eux, {@link fr.cabane.client.pouvoir.ClientIchibi}),
 * ils sont lents et le vent les emporte.</li>
 * </ul>
 * Au bout de 45 s, il rit encore, l'ouragan s'effondre sur lui-même en une colonne, puis explose : 12 à tout ce qui est
 * dedans, projeté au loin. Une fois par éveil, puis 5 minutes de recharge.
 */
@EventBusSubscriber(modid = Cabane.MODID)
public final class IchibiTempete {
    /** Le sable se soulève et forme l'ouragan pendant ce temps ; puis il dure ; puis il s'effondre avant d'exploser. */
    public static final int FORME = 64, DUREE = 45 * 20, EFFONDRE = 28;
    public static final double RAYON = 40;
    public static final int RECHARGE = 5 * 60 * 20;
    public static final int TORNADES = 4, LAMES = 9;
    public static final double HAUTEUR_TORNADE = 26;
    /** La main géante : elle sort, se referme, le soulève, le tient, l'abat (en ticks depuis qu'elle sort). */
    public static final int M_SORT = 12, M_FERME = 18, M_HAUT = 32, M_TIENT = 40, M_ABAT = 45;
    public static final double M_LEVER = 6;
    private static final float TORNADE = 5, LAME = 2, ECLAIR = 6, PICS = 4, MAIN = 10, EXPLOSION = 12;

    /** Une tempête : son début (les malheurs se comptent en ticks depuis) et quand la prochaine main, le prochain éclair. */
    private static final class Tempete {
        final int debut;
        int eclair;
        int main;

        Tempete(int debut) {
            this.debut = debut;
            this.eclair = debut + FORME + 30;
            this.main = debut + FORME + 70;
        }
    }

    /** Une main géante sur quelqu'un : qui, depuis quand, où sont ses pieds au départ. */
    private record Main(UUID lanceur, int cible, int debut, Vec3 pieds) {
    }

    private static final Map<UUID, Tempete> TEMPETES = new HashMap<>();
    private static final List<Main> MAINS = new ArrayList<>();
    /** Ceux qui ont déjà fait leur tempête pendant cet éveil. */
    private static final Set<UUID> FAITS = new HashSet<>();
    private static final Map<UUID, Integer> PRETE = new HashMap<>();
    /** Par entité : quand les pics jailliront sous elle, quand une tornade, une lame peut la reprendre. */
    private static final Map<Integer, Integer> PROCHAINS_PICS = new HashMap<>();
    private static final Map<Integer, Integer> REPRIS = new HashMap<>();
    private static final Map<Integer, Integer> COUPEE = new HashMap<>();

    /** La tempête est lancée : l'éveil ne s'endort pas avant la fin. */
    public static boolean actif(ServerPlayer joueur) {
        return TEMPETES.containsKey(joueur.getUUID());
    }

    /** Une main géante tient cette entité. */
    static boolean tenu(int id) {
        return MAINS.stream().anyMatch(m -> m.cible() == id);
    }

    /** Un nombre de 0 à 1, toujours le même pour (i, k) : le serveur et les joueurs placent les lames pareil. */
    public static double hache(int i, int k) {
        int h = i * 374761393 + k * 668265263;
        h = (h ^ (h >>> 13)) * 1274126177;
        return ((h ^ (h >>> 16)) >>> 1) / (double) Integer.MAX_VALUE;
    }

    /** Où est la tornade k au tick age de la tempête, par rapport à lui (à plat) : elles serpentent autour de lui. */
    public static Vec3 tornade(int k, double age) {
        double t = age / 20;
        double a = k * Math.PI / 2 + 0.6 + t * (k % 2 == 1 ? -0.32 : 0.27), r = 16 + 7 * Math.sin(t * 0.55 + k * 1.9);
        return new Vec3(Math.cos(a) * r, 0, Math.sin(a) * r);
    }

    /** La lame k : son rayon, sa hauteur (au-dessus de ses pieds à lui), et son angle au tick age. */
    public static double rayonLame(int k) {
        return 9 + 15 * hache(k, 30);
    }

    public static double hauteurLame(int k) {
        return 0.7 + 1.6 * hache(k, 33);
    }

    public static double angleLame(int k, double age) {
        return hache(k, 32) * Math.PI * 2 + age / 20 * (1.6 + 0.9 * hache(k, 31));
    }

    private static void message(ServerPlayer joueur, String texte) {
        joueur.displayClientMessage(Component.literal(texte).withStyle(ChatFormatting.GOLD), true);
    }

    /** La touche d'ulti. */
    static void ulti(ServerPlayer joueur) {
        if (Ichibi.niveau(joueur) < 3) {
            message(joueur, "La tempête de sable se réveille au niveau 3.");
            return;
        }
        if (actif(joueur)) {
            return;
        }
        if (FAITS.contains(joueur.getUUID())) {
            message(joueur, "La tempête, c'est une fois par éveil.");
            return;
        }
        int maintenant = joueur.server.getTickCount();
        int reste = PRETE.getOrDefault(joueur.getUUID(), 0) - maintenant;
        if (reste > 0) {
            message(joueur, "La tempête se reforme… " + (reste + 19) / 20 + " s");
            return;
        }
        FAITS.add(joueur.getUUID());
        PRETE.put(joueur.getUUID(), maintenant + RECHARGE);
        TEMPETES.put(joueur.getUUID(), new Tempete(maintenant));
        ServerLevel monde = joueur.serverLevel();
        monde.playSound(null, joueur.getX(), joueur.getY(), joueur.getZ(), CabaneSons.ICHIBI_EVEIL.get(), SoundSource.PLAYERS, 2.2F, 1F);
        Ichibi.son(joueur, SoundEvents.GENERIC_EXPLODE.value(), 1.2F, 0.5F);
        Ichibi.son(joueur, SoundEvents.SAND_BREAK, 2F, 0.4F);
        monde.sendParticles(Ichibi.sable(), joueur.getX(), joueur.getY() + 0.2, joueur.getZ(), 120, 2.5, 0.2, 2.5, 0.3);
        Ichibi.effet(joueur, Ichibi.E_TEMPETE, 0, joueur.position(), Ichibi.devant(joueur), DUREE);
    }

    /** La tempête s'arrête ; si elle est allée au bout (exploser), elle explose. */
    private static void finir(ServerPlayer joueur, boolean exploser) {
        if (TEMPETES.remove(joueur.getUUID()) == null) {
            return;
        }
        UUID moi = joueur.getUUID();
        MAINS.removeIf(m -> m.lanceur().equals(moi));
        Ichibi.effet(joueur, Ichibi.E_TEMPETE, 1, joueur.position(), Vec3.ZERO, exploser ? 1 : 0);
        ServerLevel monde = joueur.serverLevel();
        if (!exploser) {
            Ichibi.son(joueur, SoundEvents.SAND_BREAK, 1.6F, 0.7F);
            return;
        }
        Vec3 c = joueur.position();
        for (LivingEntity autre : dedans(joueur)) {
            Vec3 d = autre.position().subtract(c);
            double r = Math.hypot(d.x, d.z);
            blesser(joueur, autre, EXPLOSION);
            if (r > 0.3) {
                autre.knockback(2.4, -d.x / r, -d.z / r);
            }
            autre.setDeltaMovement(autre.getDeltaMovement().add(0, 0.8, 0));
            autre.hurtMarked = true;
        }
        monde.sendParticles(ParticleTypes.EXPLOSION_EMITTER, c.x, c.y + 1, c.z, 3, 3, 1, 3, 0);
        monde.sendParticles(Ichibi.sable(), c.x, c.y + 1, c.z, 600, 12, 4, 12, 0.8);
        monde.playSound(null, c.x, c.y, c.z, SoundEvents.GENERIC_EXPLODE.value(), SoundSource.PLAYERS, 4F, 0.4F);
        monde.playSound(null, c.x, c.y, c.z, SoundEvents.SAND_BREAK, SoundSource.PLAYERS, 4F, 0.3F);
    }

    /** Il s'endort : la tempête retombe, et il pourra en refaire une au prochain éveil (après la recharge). */
    static void endormir(ServerPlayer joueur) {
        finir(joueur, false);
        FAITS.remove(joueur.getUUID());
    }

    /** Un coup de la tempête. */
    private static void blesser(ServerPlayer joueur, LivingEntity cible, float degats) {
        cible.invulnerableTime = 0;
        cible.hurt(joueur.damageSources().playerAttack(joueur), degats);
    }

    /** Ceux qui sont dans sa tempête (pas lui, pas ses alliés). */
    private static List<LivingEntity> dedans(ServerPlayer joueur) {
        Vec3 c = joueur.position();
        return joueur.serverLevel().getEntitiesOfClass(LivingEntity.class, joueur.getBoundingBox().inflate(RAYON, 30, RAYON),
                e -> Pouvoirs.cible(joueur, e) && !e.isSpectator() && Math.hypot(e.getX() - c.x, e.getZ() - c.z) <= RAYON);
    }

    /** Le sol sous ce point (au plus 12 blocs plus bas). */
    private static double sol(ServerLevel monde, Vec3 p) {
        BlockPos.MutableBlockPos pos = BlockPos.containing(p.x, p.y + 0.5, p.z).mutable();
        for (int i = 0; i < 14; i++) {
            var etat = monde.getBlockState(pos);
            if (!etat.getCollisionShape(monde, pos).isEmpty()) {
                return pos.getY() + etat.getCollisionShape(monde, pos).max(net.minecraft.core.Direction.Axis.Y);
            }
            pos.move(0, -1, 0);
        }
        return p.y;
    }

    @SubscribeEvent
    public static void tick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        int maintenant = server.getTickCount();
        for (Map.Entry<UUID, Tempete> en : new ArrayList<>(TEMPETES.entrySet())) {
            ServerPlayer joueur = server.getPlayerList().getPlayer(en.getKey());
            if (joueur == null) {
                TEMPETES.remove(en.getKey());
                continue;
            }
            Tempete t = en.getValue();
            int age = maintenant - t.debut;
            ServerLevel monde = joueur.serverLevel();
            Vec3 c = joueur.position();
            if (age >= FORME + DUREE + EFFONDRE) {
                finir(joueur, true);
                continue;
            }
            if (age == FORME + DUREE) {
                // il rit encore : l'ouragan s'effondre sur lui-même
                Ichibi.effet(joueur, Ichibi.E_TEMPETE, 5, c, Vec3.ZERO, 0);
                monde.playSound(null, c.x, c.y, c.z, CabaneSons.ICHIBI_EVEIL.get(), SoundSource.PLAYERS, 2.2F, 1F);
            }
            if (age < FORME) {
                if (age % 6 == 0) {
                    monde.playSound(null, c.x, c.y, c.z, SoundEvents.SAND_BREAK, SoundSource.WEATHER, 3F, 0.3F + age * 0.005F);
                }
                continue;
            }
            boolean actifs = age < FORME + DUREE;
            if (age % 30 == 0) {
                monde.playSound(null, c.x, c.y + 1, c.z, SoundEvents.ELYTRA_FLYING, SoundSource.WEATHER, 2F, 0.4F);
            }
            List<LivingEntity> victimes = dedans(joueur);
            Vec3[] tornades = new Vec3[TORNADES];
            for (int k = 0; k < TORNADES; k++) {
                tornades[k] = c.add(tornade(k, age));
            }
            for (LivingEntity autre : victimes) {
                int id = autre.getId();
                Vec3 d = autre.position().subtract(c);
                double r = Math.max(0.5, Math.hypot(d.x, d.z));
                // lents ; le vent les emporte en tournant autour de lui
                if (age % 10 == 0) {
                    autre.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 30, 1, false, false), joueur);
                }
                if (age % 5 == 0 && !tenu(id)) {
                    Vec3 tangente = new Vec3(-d.z, 0, d.x).scale(1 / r);
                    // il les aspire vers lui en spirale (ils ne sortent pas de l'ouragan)
                    autre.setDeltaMovement(autre.getDeltaMovement().add(tangente.scale(0.24)).add(-d.x / r * 0.05, 0, -d.z / r * 0.05));
                    autre.hurtMarked = true;
                }
                if (!actifs || tenu(id)) {
                    continue;
                }
                double h = autre.getY() - c.y;
                // une tornade passe sur lui : aspiré en l'air en tournant
                for (Vec3 tor : tornades) {
                    double dt = Math.hypot(autre.getX() - tor.x, autre.getZ() - tor.z);
                    if (dt < 2.6 + Math.max(0, h) * 0.25 && h > -3 && h < HAUTEUR_TORNADE && maintenant >= REPRIS.getOrDefault(id, 0)) {
                        REPRIS.put(id, maintenant + 50);
                        blesser(joueur, autre, TORNADE);
                        Vec3 autour = new Vec3(-(autre.getZ() - tor.z), 0, autre.getX() - tor.x);
                        autour = autour.lengthSqr() < 1e-4 ? Vec3.ZERO : autour.normalize();
                        autre.setDeltaMovement(autour.scale(0.7).add(0, 1.25, 0));
                        autre.hurtMarked = true;
                        monde.sendParticles(Ichibi.sable(), autre.getX(), autre.getY(0.5), autre.getZ(), 40, 0.5, 0.8, 0.5, 0.3);
                        monde.playSound(null, autre.getX(), autre.getY(), autre.getZ(), SoundEvents.SAND_BREAK, SoundSource.PLAYERS, 1.6F, 0.5F);
                    }
                }
                // une lame de sable passe sur lui
                for (int k = 0; k < LAMES; k++) {
                    double a = angleLame(k, age), rl = rayonLame(k);
                    Vec3 lame = c.add(Math.cos(a) * rl, hauteurLame(k), Math.sin(a) * rl);
                    if (Math.hypot(autre.getX() - lame.x, autre.getZ() - lame.z) < 1.9 && Math.abs(autre.getY(0.5) - lame.y) < 1.6
                            && maintenant >= COUPEE.getOrDefault(id, 0)) {
                        COUPEE.put(id, maintenant + 15);
                        blesser(joueur, autre, LAME);
                        autre.setDeltaMovement(autre.getDeltaMovement().add(-Math.sin(a) * 0.5, 0.15, Math.cos(a) * 0.5));
                        autre.hurtMarked = true;
                        monde.sendParticles(ParticleTypes.SWEEP_ATTACK, autre.getX(), autre.getY(0.6), autre.getZ(), 1, 0, 0, 0, 0);
                        monde.playSound(null, autre.getX(), autre.getY(), autre.getZ(), SoundEvents.PLAYER_ATTACK_SWEEP, SoundSource.PLAYERS, 1F, 0.6F);
                    }
                }
                // les pics jaillissent sous lui
                int pics = PROCHAINS_PICS.computeIfAbsent(id, x -> maintenant + 30 + autre.getRandom().nextInt(60));
                if (maintenant >= pics) {
                    PROCHAINS_PICS.put(id, maintenant + 100 + autre.getRandom().nextInt(40));
                    blesser(joueur, autre, PICS);
                    autre.setDeltaMovement(autre.getDeltaMovement().add(0, 0.8, 0));
                    autre.hurtMarked = true;
                    Ichibi.effet(joueur, Ichibi.E_TEMPETE, 2, new Vec3(autre.getX(), sol(monde, autre.position()), autre.getZ()), Vec3.ZERO, id);
                    monde.playSound(null, autre.getX(), autre.getY(), autre.getZ(), SoundEvents.POINTED_DRIPSTONE_LAND, SoundSource.PLAYERS, 1.8F, 0.5F);
                    monde.playSound(null, autre.getX(), autre.getY(), autre.getZ(), SoundEvents.SAND_BREAK, SoundSource.PLAYERS, 1.8F, 0.6F);
                }
            }
            if (!actifs || victimes.isEmpty()) {
                continue;
            }
            // un éclair de sable tombe sur l'un d'eux
            if (maintenant >= t.eclair) {
                t.eclair = maintenant + 40 + joueur.getRandom().nextInt(25);
                LivingEntity cible = victimes.get(joueur.getRandom().nextInt(victimes.size()));
                if (!tenu(cible.getId())) {
                    blesser(joueur, cible, ECLAIR);
                    cible.setDeltaMovement(cible.getDeltaMovement().add(0, 0.35, 0));
                    cible.hurtMarked = true;
                    Ichibi.effet(joueur, Ichibi.E_TEMPETE, 3, cible.position(), Vec3.ZERO, cible.getId());
                    monde.playSound(null, cible.getX(), cible.getY(), cible.getZ(), SoundEvents.LIGHTNING_BOLT_THUNDER, SoundSource.WEATHER, 1.4F, 1.6F);
                    monde.playSound(null, cible.getX(), cible.getY(), cible.getZ(), SoundEvents.LIGHTNING_BOLT_IMPACT, SoundSource.PLAYERS, 1.2F, 1.2F);
                    monde.sendParticles(ParticleTypes.ELECTRIC_SPARK, cible.getX(), cible.getY(0.5), cible.getZ(), 30, 0.4, 0.6, 0.4, 0.4);
                }
            }
            // une main géante sort du sable sous l'un d'eux
            if (maintenant >= t.main) {
                t.main = maintenant + 110 + joueur.getRandom().nextInt(40);
                List<LivingEntity> libres = victimes.stream().filter(v -> !tenu(v.getId())).toList();
                if (!libres.isEmpty()) {
                    LivingEntity cible = libres.get(joueur.getRandom().nextInt(libres.size()));
                    Vec3 pieds = new Vec3(cible.getX(), sol(monde, cible.position()), cible.getZ());
                    MAINS.add(new Main(joueur.getUUID(), cible.getId(), maintenant, pieds));
                    Vec3 face = new Vec3(cible.getX() - c.x, 0, cible.getZ() - c.z);
                    face = face.lengthSqr() < 1e-4 ? Ichibi.devant(joueur) : face.normalize();
                    Ichibi.effet(joueur, Ichibi.E_TEMPETE, 4, pieds, face, cible.getId());
                    monde.sendParticles(Ichibi.sable(), pieds.x, pieds.y + 0.2, pieds.z, 90, 1.6, 0.2, 1.6, 0.25);
                    monde.playSound(null, pieds.x, pieds.y, pieds.z, SoundEvents.SAND_BREAK, SoundSource.PLAYERS, 2.4F, 0.3F);
                }
            }
        }
        mains(server, maintenant);
        if (TEMPETES.isEmpty()) {
            PROCHAINS_PICS.clear();
            REPRIS.clear();
            COUPEE.clear();
        }
    }

    /** Les mains géantes : il ne bouge plus ; la main se referme, le soulève, puis l'abat au sol. */
    private static void mains(MinecraftServer server, int maintenant) {
        for (Iterator<Main> it = MAINS.iterator(); it.hasNext(); ) {
            Main m = it.next();
            ServerPlayer joueur = server.getPlayerList().getPlayer(m.lanceur());
            Entity e = joueur == null ? null : joueur.serverLevel().getEntity(m.cible());
            int u = maintenant - m.debut();
            if (joueur == null || !(e instanceof LivingEntity cible) || !cible.isAlive()) {
                it.remove();
                continue;
            }
            double y = m.pieds().y;
            if (u >= M_FERME && u < M_TIENT) {
                y += M_LEVER * Mth.clamp((u - M_FERME) / (double) (M_HAUT - M_FERME), 0, 1);
            } else if (u >= M_TIENT && u < M_ABAT) {
                double k = (u - M_TIENT) / (double) (M_ABAT - M_TIENT);
                y += M_LEVER * (1 - k * k);
            }
            cible.teleportTo(m.pieds().x, y, m.pieds().z);
            cible.setDeltaMovement(Vec3.ZERO);
            cible.fallDistance = 0;
            if (u >= M_ABAT) {
                ServerLevel monde = joueur.serverLevel();
                blesser(joueur, cible, MAIN);
                cible.setDeltaMovement(0, 0.25, 0);
                cible.hurtMarked = true;
                monde.sendParticles(ParticleTypes.EXPLOSION, m.pieds().x, m.pieds().y + 0.3, m.pieds().z, 2, 0.6, 0.1, 0.6, 0);
                monde.sendParticles(Ichibi.sable(), m.pieds().x, m.pieds().y + 0.3, m.pieds().z, 120, 1.8, 0.3, 1.8, 0.35);
                monde.playSound(null, m.pieds().x, m.pieds().y, m.pieds().z, SoundEvents.GENERIC_EXPLODE.value(), SoundSource.PLAYERS, 1.4F, 0.6F);
                monde.playSound(null, m.pieds().x, m.pieds().y, m.pieds().z, SoundEvents.ANVIL_LAND, SoundSource.PLAYERS, 1F, 0.5F);
                it.remove();
            }
        }
    }

    @SubscribeEvent
    public static void arret(ServerStoppingEvent event) {
        TEMPETES.clear();
        MAINS.clear();
        FAITS.clear();
        PRETE.clear();
        PROCHAINS_PICS.clear();
        REPRIS.clear();
        COUPEE.clear();
    }

    private IchibiTempete() {
    }
}
