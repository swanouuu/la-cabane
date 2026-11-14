package fr.cabane.pouvoir;

import fr.cabane.Cabane;
import fr.cabane.client.pouvoir.ClientFruit3Coups;
import io.netty.buffer.ByteBuf;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
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
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
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
 * Les nouvelles attaques du niveau 3 des fruits (demandées le 2026-11-14, « ils sont trop en dessous » ;
 * docs/fruits/planche-attaques3.png). Le dragon (clic droit maintenu), l'accroupi (le chien de l'enfer, la capsule) et
 * l'ultime restent dans {@link Fruit3}. Ici :
 * <ul>
 * <li>clic gauche (Fruit.degats) : +6, la cible brûle 5 s (ou ralentit 3 s) ; au 3e coup de suite, un pilier de magma
 * jaillit sous elle (8 à 2 blocs, en l'air) ou elle est prise dans la glace 1,5 s puis éclate (6 à 2 blocs) ;</li>
 * <li>clic droit court : la vague de magma (20 blocs, 14, elle emporte 6 blocs, brûle 6 s) ou la vague gelée (un cône de
 * 15 blocs, 12, coincés 2 s) ; 10 s ;</li>
 * <li>accroupi + clic droit : le volcan (là où il vise, 25 blocs : 12 au milieu et en l'air, puis 3 s de bombes, 5
 * chacune, 15 au plus) ou les partisans (12 lances qui tombent, 5 chacune, 15 au plus, gelé 1 s) ; 20 s ;</li>
 * <li>accroupi + clic gauche : la comète (15 blocs en ligne, 10 à qui il traverse, 6 à l'arrivée) ou la glissade (18
 * blocs, 8 et gelé 1,5 s, 6 à l'arrivée) ; 12 s. C'est son jeu qui le fait avancer (comme le dragon), le serveur
 * regarde où il passe.</li>
 * </ul>
 * Rien ne reste au sol : tout est dessiné (ClientFruit3Coups).
 */
@EventBusSubscriber(modid = Cabane.MODID)
public final class Fruit3Coups {
    // ================================================================ les réglages

    public static final float BONUS_ARME_3 = 6;
    private static final int COMBO = 3, FENETRE_COMBO = 50;
    private static final float DEGATS_PILIER = 8, DEGATS_ECLAT = 6;
    private static final double RAYON_COMBO = 2.2;
    public static final int GEL_COMBO = 30;

    public static final int RECHARGE_VAGUE = 10 * 20;
    /** La vague de magma : jusqu'où, à quelle vitesse (blocs par tick), sa demi-largeur, et ce qu'elle emporte. */
    public static final double PORTEE_VAGUE = 20, VITESSE_VAGUE = 0.8, LARGE_VAGUE = 2.6, HAUT_VAGUE = 4;
    private static final double EMPORTE = 6;
    private static final float DEGATS_VAGUE = 14;
    /** La vague gelée : un cône de 15 blocs (30° de chaque côté), qui gèle en 10 ticks. */
    public static final double PORTEE_CONE = 15, VITESSE_CONE = 1.6, DEMI_ANGLE = Math.toRadians(32);
    private static final float DEGATS_CONE = 12;
    public static final int GEL_CONE = 40;

    public static final int RECHARGE_ZONE = 20 * 20;
    public static final double PORTEE_ZONE = 25;
    /** Le volcan : la fissure arrive (V_CONE), le cône monte, il entre en éruption (V_ERUPTION), les bombes, la fin. */
    public static final int V_CONE = 12, V_ERUPTION = 22, V_BOMBES = 84, V_FIN = 100;
    private static final float DEGATS_VOLCAN = 12, DEGATS_BOMBE = 5;
    private static final double RAYON_VOLCAN = 3, RAYON_BOMBES = 7, RAYON_BOMBE = 1.6;
    public static final int VOL_BOMBE = 14;
    /** Les partisans : 12 lances ; la k-ième tombe au tick P_DEBUT + 3k et touche le sol P_CHUTE ticks plus tard. */
    public static final int LANCES = 12, P_DEBUT = 16, P_ECART = 3, P_CHUTE = 6, P_FIN = 110;
    private static final float DEGATS_LANCE = 5;
    private static final double RAYON_LANCE = 1.4, RAYON_PARTISANS = 6.5;
    private static final int MAX_COUPS = 3;

    public static final int RECHARGE_RUEE = 12 * 20;
    /** La comète : 15 blocs en 10 ticks ; la glissade : 18 blocs en 18 ticks. */
    public static final int DUREE_COMETE = 10, DUREE_GLISSADE = 18;
    public static final double VITESSE_COMETE = 1.5, VITESSE_GLISSADE = 1.0;
    private static final float DEGATS_COMETE = 10, DEGATS_GLISSADE = 8, DEGATS_ARRIVEE = 6;
    private static final double RAYON_ARRIVEE = 3;

    /** Les sortes d'effets (paquet Effet). */
    public static final int VAGUE = 0, CONE = 1, VOLCAN = 2, BOMBE = 3, PARTISANS = 4, COMETE = 5, GLISSADE = 6, PILIER = 7, ECLAT = 8, TAILLE = 9,
            ARRIVEE = 10, LANCE = 11;
    /** Les touches (paquet Touche) : accroupi + clic droit, accroupi + clic gauche. */
    public static final int ACC_DROIT = 1, ACC_GAUCHE = 2;

    // ================================================================ réseau

    /** Client → serveur : accroupi + clic droit (ACC_DROIT) ou accroupi + clic gauche (ACC_GAUCHE), fruit éveillé au niveau 3. */
    public record Touche(int action) implements CustomPacketPayload {
        public static final Type<Touche> TYPE = new Type<>(Cabane.id("fruit3_touche"));
        public static final StreamCodec<ByteBuf, Touche> CODEC = StreamCodec.composite(ByteBufCodecs.VAR_INT, Touche::action, Touche::new);

        @Override
        public Type<Touche> type() {
            return TYPE;
        }
    }

    /** Serveur → ceux qui sont autour : un effet (sa sorte), de qui, deux points (ou un point et une direction), une graine. */
    public record Effet(UUID joueur, boolean glace, int sorte, Vector3f a, Vector3f b, int graine) implements CustomPacketPayload {
        public static final Type<Effet> TYPE = new Type<>(Cabane.id("fruit3_effet"));
        public static final StreamCodec<ByteBuf, Effet> CODEC = StreamCodec.composite(
                UUIDUtil.STREAM_CODEC, Effet::joueur, ByteBufCodecs.BOOL, Effet::glace, ByteBufCodecs.VAR_INT, Effet::sorte,
                ByteBufCodecs.VECTOR3F, Effet::a, ByteBufCodecs.VECTOR3F, Effet::b, ByteBufCodecs.VAR_INT, Effet::graine, Effet::new);

        @Override
        public Type<Effet> type() {
            return TYPE;
        }
    }

    @SubscribeEvent
    public static void reseau(RegisterPayloadHandlersEvent event) {
        event.registrar("1")
                .playToServer(Touche.TYPE, Touche.CODEC, (p, ctx) -> ctx.enqueueWork(() -> {
                    if (ctx.player() instanceof ServerPlayer joueur && Fruit.niveau(joueur) >= 3) {
                        touche(joueur, p.action());
                    }
                }))
                .playToClient(Effet.TYPE, Effet.CODEC, (p, ctx) -> ctx.enqueueWork(() -> ClientFruit3Coups.effet(p)));
    }

    private static void envoyer(ServerLevel monde, Vec3 autour, Effet e) {
        for (ServerPlayer p : monde.players()) {
            if (p.distanceToSqr(autour) < 160 * 160) {
                PacketDistributor.sendToPlayer(p, e);
            }
        }
    }

    private static void effet(ServerPlayer j, int sorte, Vec3 a, Vec3 b, int graine) {
        envoyer(j.serverLevel(), a, new Effet(j.getUUID(), Fruit.glace(Pouvoirs.pouvoir(j)), sorte, Fruit.v(a), Fruit.v(b), graine));
    }

    private static void son(ServerLevel monde, Vec3 p, SoundEvent son, float volume, float hauteur) {
        monde.playSound(null, p.x, p.y, p.z, son, SoundSource.PLAYERS, volume, hauteur);
    }

    private static List<LivingEntity> autour(ServerPlayer j, Vec3 p, double r, double bas, double haut) {
        return j.serverLevel().getEntitiesOfClass(LivingEntity.class, new AABB(p, p).inflate(r + 1, 0, r + 1).expandTowards(0, haut, 0).expandTowards(0, -bas, 0),
                e -> Pouvoirs.cible(j, e) && !e.isSpectator() && e.position().subtract(p).horizontalDistance() < r + e.getBbWidth() / 2);
    }

    // ================================================================ partagé avec le client

    /** Le même hasard des deux côtés (comme ClientFruit3.hache). */
    public static double hache(int i, int j, int k) {
        int h = i * 374761393 + j * 668265263 + k * 2147483647;
        h = (h ^ (h >>> 13)) * 1274126177;
        return ((h ^ (h >>> 16)) & 0xFFFFFFFFL) / 4294967296.0;
    }

    private static boolean plein(Level monde, BlockPos p) {
        return !monde.getBlockState(p).getCollisionShape(monde, p).isEmpty();
    }

    /**
     * Le sol sous ce point, en partant de la hauteur d'avant (les vagues suivent le terrain) : on cherche, de 2 blocs
     * au-dessus à 4 en dessous, un bloc plein avec de l'air au-dessus. NaN : un mur (plein sur 3 blocs de haut).
     */
    public static double sol(Level monde, double x, double z, double yAvant) {
        int bx = Mth.floor(x), bz = Mth.floor(z), y0 = Mth.floor(yAvant + 0.01);
        if (plein(monde, new BlockPos(bx, y0, bz)) && plein(monde, new BlockPos(bx, y0 + 1, bz)) && plein(monde, new BlockPos(bx, y0 + 2, bz))) {
            return Double.NaN;
        }
        for (int y = y0 + 2; y >= y0 - 4; y--) {
            if (plein(monde, new BlockPos(bx, y - 1, bz)) && !plein(monde, new BlockPos(bx, y, bz))) {
                return y;
            }
        }
        return yAvant;
    }

    /** Une lance des partisans : où elle tombe (autour du centre), selon la graine. */
    public static Vec3 lance(Vec3 centre, int graine, int k) {
        double a = k * 2.39996 + graine * 0.37, r = 0.6 + 3.6 * Math.sqrt(hache(k, graine, 701));
        return centre.add(Math.cos(a) * r, 0, Math.sin(a) * r);
    }

    /** La vague de magma : jusqu'où elle est arrivée, tant de ticks après son départ. */
    public static double frontVague(double age) {
        return Math.min(PORTEE_VAGUE, age * VITESSE_VAGUE);
    }

    // ================================================================ les recharges

    private static final Map<UUID, Integer> PRET_ZONE = new HashMap<>(), PRET_RUEE = new HashMap<>();

    private static boolean armer(Map<UUID, Integer> pret, ServerPlayer j, int recharge) {
        int maintenant = j.server.getTickCount();
        if (maintenant < pret.getOrDefault(j.getUUID(), 0)) {
            return false;
        }
        pret.put(j.getUUID(), maintenant + recharge);
        return true;
    }

    /** Il s'endort : tout est prêt au prochain éveil (le repos dure plus que les recharges). */
    static void endormir(ServerPlayer j) {
        PRET_ZONE.remove(j.getUUID());
        PRET_RUEE.remove(j.getUUID());
        COMBOS.remove(j.getUUID());
    }

    private static void touche(ServerPlayer j, int action) {
        boolean glace = Fruit.glace(Pouvoirs.pouvoir(j));
        if (action == ACC_DROIT && armer(PRET_ZONE, j, RECHARGE_ZONE)) {
            zone(j, glace);
        } else if (action == ACC_GAUCHE && !RUEES.containsKey(j.getUUID()) && armer(PRET_RUEE, j, RECHARGE_RUEE)) {
            ruee(j, glace);
        }
    }

    // ================================================================ clic gauche : le poing volcanique, l'épée de glace

    private static final Map<UUID, int[]> COMBOS = new HashMap<>();

    /** Ceux que le pilier lance en l'air (au tick d'après : le coup en cours les repousserait). */
    private record Lancer(LivingEntity e, Vec3 vitesse) {
    }

    private static final List<Lancer> LANCERS = new ArrayList<>();

    /** Ceux que la glace du 3e coup tient : ils éclatent à ce tick (et ceux d'à côté avec eux). */
    private record Eclat(UUID joueur, LivingEntity cible, int quand) {
    }

    private static final List<Eclat> ECLATS = new ArrayList<>();

    /**
     * Le coup d'arme d'un fruit éveillé au niveau 3 (Fruit.degats a déjà ajouté le bonus) : ce qu'on voit, et le 3e coup
     * de suite (moins de 2,5 s entre deux). Rend ce qu'il faut encore ajouter au coup en cours (le pilier, pour la cible).
     */
    static float frapper(ServerPlayer j, LivingEntity cible, boolean glace) {
        int maintenant = j.server.getTickCount();
        int[] c = COMBOS.computeIfAbsent(j.getUUID(), u -> new int[]{0, 0});
        c[0] = maintenant - c[1] > FENETRE_COMBO ? 1 : c[0] + 1;
        c[1] = maintenant;
        Vec3 milieu = cible.getBoundingBox().getCenter();
        effet(j, TAILLE, j.getEyePosition(), milieu, c[0]);
        if (c[0] < COMBO) {
            return 0;
        }
        c[0] = 0;
        ServerLevel monde = j.serverLevel();
        Vec3 pieds = cible.position();
        if (glace) {
            Fruit.geler(cible, GEL_COMBO);
            ECLATS.add(new Eclat(j.getUUID(), cible, maintenant + GEL_COMBO));
            son(monde, pieds, SoundEvents.AMETHYST_CLUSTER_PLACE, 1.6F, 0.6F);
            son(monde, pieds, SoundEvents.PLAYER_HURT_FREEZE, 1.4F, 0.5F);
            return 0;
        }
        effet(j, PILIER, pieds, pieds, 0);
        son(monde, pieds, SoundEvents.GENERIC_EXPLODE.value(), 1.2F, 1.1F);
        son(monde, pieds, SoundEvents.LAVA_EXTINGUISH, 1.4F, 0.6F);
        son(monde, pieds, SoundEvents.BLAZE_SHOOT, 1.2F, 0.5F);
        for (LivingEntity e : autour(j, pieds, RAYON_COMBO, 1, 3)) {
            if (e != cible) {
                Fruit.blesser(e, Fruit.source(j, Fruit.MAGMA), DEGATS_PILIER);
            }
            e.igniteForSeconds(5);
            LANCERS.add(new Lancer(e, new Vec3(0, 0.95, 0)));
        }
        LANCERS.add(new Lancer(cible, new Vec3(0, 0.95, 0)));
        return DEGATS_PILIER;
    }

    // ================================================================ clic droit court : la vague de magma, la vague gelée

    private static final class Vague {
        final UUID joueur;
        final ServerLevel monde;
        final boolean glace;
        final Vec3 depart;
        final Vec3 dir;
        final int debut;
        final Set<Integer> touches = new HashSet<>();
        /** Ceux que la vague de magma emporte : jusqu'à quelle avancée du front. */
        final Map<LivingEntity, Double> emportes = new HashMap<>();
        double front;
        double y;

        Vague(ServerPlayer j, boolean glace, Vec3 dir) {
            this.joueur = j.getUUID();
            this.monde = j.serverLevel();
            this.glace = glace;
            this.dir = dir;
            this.depart = j.position();
            this.debut = j.server.getTickCount();
            this.y = depart.y;
        }
    }

    private static final List<Vague> VAGUES = new ArrayList<>();

    /** Le clic droit court au niveau 3 (Fruit.jet) : la vague de magma, ou la vague gelée. */
    static void vague(ServerPlayer j, boolean glace) {
        Vec3 r = j.getLookAngle(), dir = new Vec3(r.x, 0, r.z);
        dir = dir.lengthSqr() < 1e-4 ? Vec3.directionFromRotation(0, j.getYRot()) : dir.normalize();
        Vague v = new Vague(j, glace, dir);
        VAGUES.add(v);
        effet(j, glace ? CONE : VAGUE, v.depart, dir, 0);
        ServerLevel monde = j.serverLevel();
        if (glace) {
            son(monde, v.depart, SoundEvents.GLASS_BREAK, 1.8F, 0.5F);
            son(monde, v.depart, SoundEvents.POWDER_SNOW_PLACE, 2F, 0.5F);
            son(monde, v.depart, SoundEvents.AMETHYST_BLOCK_RESONATE, 2F, 0.6F);
            son(monde, v.depart, SoundEvents.PLAYER_HURT_FREEZE, 1.6F, 0.5F);
        } else {
            son(monde, v.depart, SoundEvents.LAVA_AMBIENT, 2F, 0.5F);
            son(monde, v.depart, SoundEvents.BLAZE_SHOOT, 1.8F, 0.4F);
            son(monde, v.depart, SoundEvents.GENERIC_SPLASH, 1.6F, 0.4F);
        }
    }

    private static boolean avancerVague(ServerPlayer j, Vague v, int age) {
        if (v.glace) {
            double avant = v.front;
            v.front = Math.min(PORTEE_CONE, age * VITESSE_CONE);
            for (LivingEntity e : autour(j, v.depart, v.front + 0.5, 3, 5)) {
                Vec3 d = e.position().subtract(v.depart);
                double h = d.horizontalDistance();
                if (v.touches.contains(e.getId()) || h < avant - 0.6 || h > v.front + 0.5) {
                    continue;
                }
                double cos = (d.x * v.dir.x + d.z * v.dir.z) / Math.max(1e-4, h);
                if (h > 1 && cos < Math.cos(DEMI_ANGLE)) {
                    continue;
                }
                v.touches.add(e.getId());
                Fruit.blesser(e, Fruit.source(j, Fruit.GIVRE), DEGATS_CONE);
                Fruit.geler(e, GEL_CONE);
            }
            if (age % 3 == 0) {
                son(v.monde, v.depart.add(v.dir.scale(v.front)), SoundEvents.POWDER_SNOW_BREAK, 1.6F, 0.6F);
            }
            if (age == 50) {
                son(v.monde, v.depart.add(v.dir.scale(8)), SoundEvents.GLASS_BREAK, 2.2F, 0.6F);
                son(v.monde, v.depart.add(v.dir.scale(8)), SoundEvents.AMETHYST_CLUSTER_BREAK, 2F, 0.5F);
            }
            return age >= 52;
        }
        double avant = v.front, front = frontVague(age);
        Vec3 bout = v.depart.add(v.dir.scale(front));
        double y = sol(v.monde, bout.x, bout.z, v.y);
        boolean mur = Double.isNaN(y);
        if (!mur) {
            v.front = front;
            v.y = y;
        }
        Vec3 base = new Vec3(v.depart.x + v.dir.x * v.front, v.y, v.depart.z + v.dir.z * v.front);
        Vec3 cote = new Vec3(-v.dir.z, 0, v.dir.x);
        for (LivingEntity e : v.monde.getEntitiesOfClass(LivingEntity.class, new AABB(base, base).inflate(LARGE_VAGUE + 2, 3, LARGE_VAGUE + 2).expandTowards(0, HAUT_VAGUE, 0),
                e -> Pouvoirs.cible(j, e) && !e.isSpectator())) {
            Vec3 d = e.position().subtract(v.depart);
            double le = d.x * v.dir.x + d.z * v.dir.z, lat = Math.abs(d.x * cote.x + d.z * cote.z);
            if (v.touches.contains(e.getId()) || le < avant - 1.5 || le > v.front + 0.8 || lat > LARGE_VAGUE + e.getBbWidth() / 2
                    || e.getY() < v.y - 1.5 || e.getY() > v.y + HAUT_VAGUE) {
                continue;
            }
            v.touches.add(e.getId());
            Fruit.blesser(e, Fruit.source(j, Fruit.MAGMA), DEGATS_VAGUE);
            e.igniteForSeconds(6);
            v.emportes.put(e, v.front + EMPORTE);
            son(v.monde, e.position(), SoundEvents.LAVA_POP, 1.6F, 0.6F);
        }
        // ceux qu'elle emporte avancent avec elle, sur sa crête
        v.emportes.entrySet().removeIf(en -> {
            LivingEntity e = en.getKey();
            if (!e.isAlive() || e.level() != v.monde || v.front >= en.getValue() || mur) {
                return true;
            }
            e.setDeltaMovement(v.dir.x * VITESSE_VAGUE * 1.05, Math.max(0.12, (v.y + 1.6 - e.getY()) * 0.35), v.dir.z * VITESSE_VAGUE * 1.05);
            e.hurtMarked = true;
            e.fallDistance = 0;
            return false;
        });
        if (age % 4 == 0) {
            son(v.monde, base, SoundEvents.LAVA_AMBIENT, 1.6F, 0.5F);
            son(v.monde, base, SoundEvents.BLAZE_BURN, 1.2F, 0.5F);
        }
        if (mur || front >= PORTEE_VAGUE) {
            effet(j, ARRIVEE, base, v.dir, mur ? 1 : 0);
            son(v.monde, base, SoundEvents.LAVA_EXTINGUISH, 1.8F, 0.5F);
            return true;
        }
        return false;
    }

    // ================================================================ accroupi + clic droit : le volcan, les partisans

    private static final class Zone {
        final UUID joueur;
        final ServerLevel monde;
        final boolean glace;
        final Vec3 centre;
        final int debut;
        final int graine;
        final Map<Integer, Integer> coups = new HashMap<>();
        final List<Vec3[]> bombes = new ArrayList<>();
        final List<Integer> quandBombes = new ArrayList<>();
        /** Les partisans : où tombe chaque lance (choisi quand elle part), et combien de lances visent déjà chaque cible. */
        final Vec3[] lances = new Vec3[LANCES];
        final Map<Integer, Integer> vises = new HashMap<>();

        Zone(ServerPlayer j, boolean glace, Vec3 centre, int graine) {
            this.joueur = j.getUUID();
            this.monde = j.serverLevel();
            this.glace = glace;
            this.centre = centre;
            this.debut = j.server.getTickCount();
            this.graine = graine;
        }
    }

    private static final List<Zone> ZONES = new ArrayList<>();

    /** Là où il vise, jusqu'à 25 blocs, posé au sol. */
    private static Vec3 vise(ServerPlayer j) {
        ServerLevel monde = j.serverLevel();
        Vec3 oeil = j.getEyePosition(), loin = oeil.add(j.getLookAngle().scale(PORTEE_ZONE));
        HitResult bloc = monde.clip(new ClipContext(oeil, loin, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, j));
        Vec3 p = bloc.getType() == HitResult.Type.MISS ? loin : bloc.getLocation();
        double y = sol(monde, p.x, p.z, p.y + 0.5);
        if (Double.isNaN(y)) {
            y = p.y;
        }
        // en l'air (il vise le ciel) : on descend jusqu'au sol, 30 blocs au plus
        for (int k = 0; k < 30 && !plein(monde, BlockPos.containing(p.x, y - 1, p.z)); k++) {
            y--;
        }
        return new Vec3(p.x, y, p.z);
    }

    private static void zone(ServerPlayer j, boolean glace) {
        Vec3 c = vise(j);
        Zone z = new Zone(j, glace, c, j.getRandom().nextInt(10000));
        ZONES.add(z);
        effet(j, glace ? PARTISANS : VOLCAN, c, j.position(), z.graine);
        ServerLevel monde = j.serverLevel();
        if (glace) {
            son(monde, j.getEyePosition(), SoundEvents.AMETHYST_BLOCK_RESONATE, 2F, 0.5F);
            son(monde, c, SoundEvents.BEACON_ACTIVATE, 1.6F, 1.8F);
            son(monde, c, SoundEvents.POWDER_SNOW_PLACE, 2F, 0.6F);
        } else {
            son(monde, j.position(), SoundEvents.GENERIC_EXPLODE.value(), 1.2F, 0.6F);
            son(monde, j.position(), SoundEvents.LAVA_EXTINGUISH, 1.6F, 0.5F);
            son(monde, c, SoundEvents.WARDEN_SONIC_CHARGE, 1.4F, 0.5F);
        }
    }

    /** Une cible ne prend que 3 coups (bombes ou lances) par zone. */
    private static boolean encore(Zone z, LivingEntity e) {
        int n = z.coups.getOrDefault(e.getId(), 0);
        if (n >= MAX_COUPS) {
            return false;
        }
        z.coups.put(e.getId(), n + 1);
        return true;
    }

    /**
     * Un coup d'une pluie (lances, bombes) : ils tombent plus vite que l'invincibilité d'après un coup (1 s), qui les
     * annulerait ; MAX_COUPS par cible et par zone les borne déjà.
     */
    private static void pluie(ServerPlayer j, LivingEntity e, net.minecraft.resources.ResourceKey<net.minecraft.world.damagesource.DamageType> type, float degats) {
        e.invulnerableTime = 0;
        Fruit.blesser(e, Fruit.source(j, type), degats);
    }

    private static boolean avancerZone(ServerPlayer j, Zone z, int age) {
        if (z.glace) {
            if (age == 2) {
                son(z.monde, z.centre.add(0, 9, 0), SoundEvents.AMETHYST_CLUSTER_PLACE, 2F, 0.5F);
            }
            for (int k = 0; k < LANCES; k++) {
                int tombe = P_DEBUT + P_ECART * k;
                if (age == tombe) {
                    // elle vise un ennemi de la zone (deux lances chacun au plus), sinon un point au hasard
                    LivingEntity vise = null;
                    for (LivingEntity e : autour(j, z.centre, RAYON_PARTISANS, 2, 4)) {
                        int n = z.vises.getOrDefault(e.getId(), 0);
                        if (n < 2 && (vise == null || n < z.vises.getOrDefault(vise.getId(), 0))) {
                            vise = e;
                        }
                    }
                    Vec3 l;
                    if (vise != null) {
                        z.vises.merge(vise.getId(), 1, Integer::sum);
                        l = vise.position().add((hache(k, z.graine, 704) - 0.5) * 0.6, 0, (hache(k, z.graine, 705) - 0.5) * 0.6);
                    } else {
                        l = lance(z.centre, z.graine, k);
                    }
                    double y = sol(z.monde, l.x, l.z, l.y + 1);
                    z.lances[k] = new Vec3(l.x, Double.isNaN(y) ? z.centre.y : y, l.z);
                    effet(j, LANCE, z.centre, z.lances[k], k);
                    son(z.monde, z.centre.add(0, 8, 0), SoundEvents.TRIDENT_THROW.value(), 1.4F, 0.6F);
                }
                Vec3 p = z.lances[k];
                if (age != tombe + P_CHUTE || p == null) {
                    continue;
                }
                son(z.monde, p, SoundEvents.GLASS_BREAK, 1.6F, 0.6F + 0.05F * (k % 4));
                son(z.monde, p, SoundEvents.TRIDENT_HIT_GROUND, 1.6F, 0.6F);
                for (LivingEntity e : autour(j, p, RAYON_LANCE, 1, 3)) {
                    if (!encore(z, e)) {
                        continue;
                    }
                    pluie(j, e, Fruit.GIVRE, DEGATS_LANCE);
                    Fruit.geler(e, 20);
                }
            }
            return age >= P_FIN;
        }
        Vec3 c = z.centre;
        if (age < V_CONE && age % 3 == 0) {
            son(z.monde, c, SoundEvents.LAVA_POP, 1.6F, 0.5F);
        }
        if (age == V_CONE) {
            son(z.monde, c, SoundEvents.GENERIC_EXPLODE.value(), 1.6F, 0.5F);
            son(z.monde, c, SoundEvents.ANVIL_LAND, 0.8F, 0.4F);
        }
        if (age == V_ERUPTION) {
            son(z.monde, c, SoundEvents.GENERIC_EXPLODE.value(), 2.4F, 0.6F);
            son(z.monde, c, SoundEvents.BLAZE_SHOOT, 2F, 0.4F);
            son(z.monde, c, SoundEvents.LAVA_EXTINGUISH, 2F, 0.5F);
            for (LivingEntity e : autour(j, c, RAYON_VOLCAN, 1, 5)) {
                Fruit.blesser(e, Fruit.source(j, Fruit.MAGMA), DEGATS_VOLCAN);
                e.igniteForSeconds(6);
                Vec3 d = e.position().subtract(c).multiply(1, 0, 1);
                Vec3 v = d.lengthSqr() < 0.01 ? Vec3.ZERO : d.normalize().scale(0.3);
                e.setDeltaMovement(v.x, 1.15, v.z);
                e.hurtMarked = true;
                Fruit3.retombe(e);
            }
        }
        if (age > V_ERUPTION && age <= V_BOMBES && age % 3 == 0) {
            int n = age / 3;
            double a = hache(n, z.graine, 702) * Math.PI * 2, r = 1.5 + (RAYON_BOMBES - 1.5) * Math.sqrt(hache(n, z.graine, 703));
            double x = c.x + Math.cos(a) * r, zz = c.z + Math.sin(a) * r, y = sol(z.monde, x, zz, c.y + 1);
            Vec3 chute = new Vec3(x, Double.isNaN(y) ? c.y : y, zz);
            z.bombes.add(new Vec3[]{chute});
            z.quandBombes.add(age + VOL_BOMBE);
            effet(j, BOMBE, c.add(0, 4.5, 0), chute, n);
            if (n % 2 == 0) {
                son(z.monde, c.add(0, 4, 0), SoundEvents.BLAZE_SHOOT, 1.6F, 0.5F);
            }
        }
        for (int i = 0; i < z.bombes.size(); i++) {
            if (z.quandBombes.get(i) != age) {
                continue;
            }
            Vec3 p = z.bombes.get(i)[0];
            son(z.monde, p, SoundEvents.GENERIC_EXPLODE.value(), 0.9F, 1.3F);
            for (LivingEntity e : autour(j, p, RAYON_BOMBE, 1, 3)) {
                if (encore(z, e)) {
                    pluie(j, e, Fruit.MAGMA, DEGATS_BOMBE);
                    e.igniteForSeconds(3);
                }
            }
        }
        if (age % 10 == 0 && age > V_ERUPTION && age < V_BOMBES) {
            son(z.monde, c.add(0, 4, 0), SoundEvents.LAVA_AMBIENT, 2F, 0.5F);
        }
        return age >= V_FIN;
    }

    // ================================================================ accroupi + clic gauche : la comète, la glissade

    private static final class Ruee {
        final UUID joueur;
        final ServerLevel monde;
        final boolean glace;
        final Vec3 dir;
        final int debut;
        final Set<Integer> touches = new HashSet<>();
        Vec3 avant;

        Ruee(ServerPlayer j, boolean glace, Vec3 dir) {
            this.joueur = j.getUUID();
            this.monde = j.serverLevel();
            this.glace = glace;
            this.dir = dir;
            this.debut = j.server.getTickCount();
            this.avant = j.position();
        }
    }

    private static final Map<UUID, Ruee> RUEES = new HashMap<>();

    private static void ruee(ServerPlayer j, boolean glace) {
        Vec3 r = j.getLookAngle(), dir = new Vec3(r.x, 0, r.z);
        dir = dir.lengthSqr() < 1e-4 ? Vec3.directionFromRotation(0, j.getYRot()) : dir.normalize();
        Ruee x = new Ruee(j, glace, dir);
        RUEES.put(j.getUUID(), x);
        effet(j, glace ? GLISSADE : COMETE, j.position(), dir, 0);
        ServerLevel monde = j.serverLevel();
        if (glace) {
            son(monde, j.position(), SoundEvents.POWDER_SNOW_PLACE, 2F, 0.6F);
            son(monde, j.position(), SoundEvents.GLASS_PLACE, 1.6F, 0.6F);
            son(monde, j.position(), SoundEvents.ELYTRA_FLYING, 0.8F, 1.6F);
        } else {
            son(monde, j.position(), SoundEvents.BLAZE_SHOOT, 2F, 0.4F);
            son(monde, j.position(), SoundEvents.FIRECHARGE_USE, 1.8F, 0.5F);
            son(monde, j.position(), SoundEvents.GENERIC_EXPLODE.value(), 0.8F, 1.4F);
        }
    }

    private static boolean avancerRuee(ServerPlayer j, Ruee x, int age) {
        Vec3 ici = j.position();
        j.fallDistance = 0;
        // ceux qu'il traverse (entre sa place au tick d'avant et maintenant)
        Vec3 a = x.avant.add(0, 0.9, 0), b = ici.add(0, 0.9, 0);
        for (LivingEntity e : x.monde.getEntitiesOfClass(LivingEntity.class, new AABB(a, b).inflate(1.6), e -> e != j && Pouvoirs.cible(j, e) && !e.isSpectator())) {
            AABB bo = e.getBoundingBox().inflate(0.9);
            if (x.touches.contains(e.getId()) || !bo.contains(b) && bo.clip(a, b).isEmpty()) {
                continue;
            }
            x.touches.add(e.getId());
            Vec3 cote = new Vec3(-x.dir.z, 0, x.dir.x);
            double signe = Math.signum(e.position().subtract(ici).dot(cote));
            if (x.glace) {
                Fruit.blesser(e, Fruit.source(j, Fruit.GIVRE), DEGATS_GLISSADE);
                Fruit.geler(e, GEL_COMBO);
                son(x.monde, e.position(), SoundEvents.GLASS_BREAK, 1.4F, 0.8F);
            } else {
                Fruit.blesser(e, Fruit.source(j, Fruit.MAGMA), DEGATS_COMETE);
                e.igniteForSeconds(5);
                Vec3 v = cote.scale(signe == 0 ? 1 : signe).scale(0.9);
                e.setDeltaMovement(v.x, 0.5, v.z);
                e.hurtMarked = true;
                son(x.monde, e.position(), SoundEvents.FIRECHARGE_USE, 1.4F, 0.7F);
            }
        }
        x.avant = ici;
        if (age % 3 == 0) {
            son(x.monde, ici, x.glace ? SoundEvents.POWDER_SNOW_STEP : SoundEvents.BLAZE_BURN, 1.4F, x.glace ? 1.2F : 0.6F);
        }
        if (age < (x.glace ? DUREE_GLISSADE : DUREE_COMETE) + 1) {
            return false;
        }
        // l'arrivée : une explosion de magma, ou une gerbe de pics de glace
        effet(j, ARRIVEE, ici, x.dir, 2);
        if (x.glace) {
            son(x.monde, ici, SoundEvents.GLASS_BREAK, 2F, 0.5F);
            son(x.monde, ici, SoundEvents.AMETHYST_CLUSTER_BREAK, 2F, 0.6F);
        } else {
            son(x.monde, ici, SoundEvents.GENERIC_EXPLODE.value(), 1.6F, 0.9F);
            son(x.monde, ici, SoundEvents.LAVA_EXTINGUISH, 1.6F, 0.6F);
        }
        for (LivingEntity e : autour(j, ici, RAYON_ARRIVEE, 2, 3)) {
            if (e == j) {
                continue;
            }
            Fruit.blesser(e, Fruit.source(j, x.glace ? Fruit.GIVRE : Fruit.MAGMA), DEGATS_ARRIVEE);
            if (x.glace) {
                e.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 60, 2));
                Fruit.givrer(e);
            } else {
                e.igniteForSeconds(3);
            }
        }
        Fruit3.retombe(j);
        return true;
    }

    /** Pendant la comète ou la glissade : il ne prend pas de dégâts de chute. */
    @SubscribeEvent
    public static void chute(LivingFallEvent event) {
        if (RUEES.containsKey(event.getEntity().getUUID())) {
            event.setCanceled(true);
        }
    }

    // ================================================================ ce qui dure

    @SubscribeEvent
    public static void tick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        int maintenant = server.getTickCount();
        for (Lancer l : LANCERS) {
            if (l.e().isAlive()) {
                Vec3 m = l.e().getDeltaMovement();
                l.e().setDeltaMovement(m.x * 0.3 + l.vitesse().x, l.vitesse().y, m.z * 0.3 + l.vitesse().z);
                l.e().hurtMarked = true;
                Fruit3.retombe(l.e());
            }
        }
        LANCERS.clear();
        ECLATS.removeIf(x -> {
            if (maintenant < x.quand()) {
                return false;
            }
            ServerPlayer j = server.getPlayerList().getPlayer(x.joueur());
            LivingEntity c = x.cible();
            if (j == null || c.level() != j.level()) {
                return true;
            }
            Vec3 p = c.position();
            effet(j, ECLAT, p, p, 0);
            son(j.serverLevel(), p, SoundEvents.GLASS_BREAK, 2F, 0.6F);
            son(j.serverLevel(), p, SoundEvents.AMETHYST_CLUSTER_BREAK, 1.8F, 0.6F);
            for (LivingEntity e : autour(j, p, RAYON_COMBO, 1, 3)) {
                Fruit.blesser(e, Fruit.source(j, Fruit.GIVRE), DEGATS_ECLAT);
                e.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 60, 1));
                Fruit.givrer(e);
            }
            return true;
        });
        VAGUES.removeIf(v -> {
            ServerPlayer j = server.getPlayerList().getPlayer(v.joueur);
            return j == null || j.level() != v.monde || avancerVague(j, v, maintenant - v.debut);
        });
        ZONES.removeIf(z -> {
            ServerPlayer j = server.getPlayerList().getPlayer(z.joueur);
            return j == null || j.level() != z.monde || avancerZone(j, z, maintenant - z.debut);
        });
        RUEES.values().removeIf(x -> {
            ServerPlayer j = server.getPlayerList().getPlayer(x.joueur);
            return j == null || j.level() != x.monde || !j.isAlive() || avancerRuee(j, x, maintenant - x.debut);
        });
    }

    @SubscribeEvent
    public static void deconnexion(PlayerEvent.PlayerLoggedOutEvent event) {
        UUID u = event.getEntity().getUUID();
        RUEES.remove(u);
        COMBOS.remove(u);
        VAGUES.removeIf(v -> v.joueur.equals(u));
        ZONES.removeIf(z -> z.joueur.equals(u));
        ECLATS.removeIf(x -> x.joueur().equals(u));
    }

    @SubscribeEvent
    public static void arret(ServerStoppingEvent event) {
        PRET_ZONE.clear();
        PRET_RUEE.clear();
        COMBOS.clear();
        LANCERS.clear();
        ECLATS.clear();
        VAGUES.clear();
        ZONES.clear();
        RUEES.clear();
    }

    private Fruit3Coups() {
    }
}
