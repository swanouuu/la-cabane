package fr.cabane.pouvoir;

import fr.cabane.Cabane;
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
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import org.joml.Vector3f;

/**
 * Le Susano niveau 3, le chevalier (listes « susano3 » : niveaux 1, 2 puis 3 ; « susanodev » : droit au niveau 3).
 * Le clic gauche, ce sont les coups d'épée (dans {@link Pouvoirs#frapper}, plus loin et plus fort). Ici, le clic droit :
 * il tend un grand arc, et la flèche part un peu plus tard. Elle vole pour de vrai, droit là où il visait (sans aide :
 * il faut viser), traverse tout ce qu'elle croise, et ce qu'elle touche prend feu : les flammes noires d'Amaterasu, plus
 * longues et plus fortes qu'un feu normal ; l'eau ne les éteint pas. Sous sa ligne, et en flaque là où elle se plante,
 * elle pose de vraies flammes noires au sol ({@link FeuNoir}) : elles restent sur leur bloc une minute, rien ne les
 * éteint, et elles embrasent qui marche dedans. Accroupi : le grand coup ; il lève son épée et l'abat, une vague de
 * flammes noires part tout autour de lui, embrase ce qu'elle touche et laisse des flammes noires partout autour. Les flammes, la flèche et la
 * vague sont dessinées chez chaque joueur (ClientSusano3).
 */
@EventBusSubscriber(modid = Cabane.MODID)
public final class Susano3 {
    public static final String NOM = "susano3";
    public static final String DEV = "susanodev";
    /** L'arc se recharge en 8 secondes ; on le tend en une demi-seconde. */
    public static final int RECHARGE_ARC = 160;
    public static final int TENSION = 10;
    private static final double PORTEE_ARC = 48;
    private static final float DEGATS_FLECHE = 10;
    /**
     * La flèche file à 3 blocs par tick (48 blocs en 16 ticks) : on la voit venir. Elle est grosse : elle touche ce qui
     * passe à 1,3 bloc de sa ligne (une bande de 3 blocs de large, comme la grande flèche de glace).
     */
    public static final double VITESSE_FLECHE = 3;
    private static final double LARGEUR_FLECHE = 1.3;
    /**
     * Ses flammes au sol (des blocs {@link FeuNoir}) : une bande irrégulière sous sa ligne (le milieu toujours, les côtés
     * souvent, un peu plus loin parfois), et une flaque de 3 blocs de rayon là où elle se plante.
     */
    private static final double PAS_SOL = 1;
    private static final double[][] BANDE = {{0, 1}, {-1, 0.8}, {1, 0.8}, {-2, 0.3}, {2, 0.3}};
    private static final int RAYON_FLAQUE = 3;
    /** Le grand coup laisse une quarantaine de flammes dans les 8 blocs autour de lui (et sous ceux qu'il touche). */
    private static final int FLAMMES_GRAND = 40;
    /** Les flammes noires : 2 dégâts par seconde pendant 10 secondes (un feu normal : 1 par seconde, bien moins longtemps). */
    public static final int DUREE_FEU = 200;
    private static final float DEGATS_FEU = 2;

    /** Le grand coup (accroupi) : 15 secondes de recharge ; l'épée touche le sol au bout de 9 ticks. */
    public static final int RECHARGE_GRAND = 300;
    public static final int IMPACT_GRAND = 9;
    private static final float DEGATS_GRAND = 22;
    /** Il touche tout ce qui est à 8 blocs autour de lui, et jusqu'à 12 blocs devant. */
    private static final double RAYON_GRAND = 8;
    private static final double PORTEE_GRAND = 12;

    public static final ResourceKey<DamageType> AMATERASU = ResourceKey.create(Registries.DAMAGE_TYPE, Cabane.id("amaterasu"));

    public static boolean est(String pouvoir) {
        return NOM.equals(pouvoir) || DEV.equals(pouvoir);
    }

    /** Qui tend son arc : le joueur → le tick où il a commencé. Et quand chacun a tiré pour la dernière fois. */
    private static final Map<UUID, Integer> TIRS = new HashMap<>();
    private static final Map<UUID, Integer> DERNIER_TIR = new HashMap<>();
    /** Qui abat son épée : le joueur → le tick du coup. Et quand chacun l'a fait pour la dernière fois. */
    private static final Map<UUID, Integer> GRANDS = new HashMap<>();
    private static final Map<UUID, Integer> DERNIER_GRAND = new HashMap<>();

    /** Ce qui brûle : l'entité → jusqu'à quand, et qui a tiré. */
    private record Feu(ServerLevel monde, int fin, UUID tireur) {
    }

    private static final Map<Integer, Feu> FEUX = new HashMap<>();

    /** Un foyer de flammes noires au sol, et à quelle distance sur la ligne de la flèche il prend. */
    private record Foyer(Vec3 pos, double distance) {
    }

    /** Une flèche en vol : qui l'a tirée, sa ligne (départ, direction, longueur), si elle se plante au bout, quand elle est partie, qui elle a déjà touché, ses foyers. */
    private record Vol(UUID tireur, ServerLevel monde, Vec3 depart, Vec3 dir, double longueur, boolean plantee, int debut, Set<Integer> touches, List<Foyer> foyers) {
    }

    private static final List<Vol> VOLS = new ArrayList<>();

    /** Serveur → ceux qui voient le tireur : la ligne de la flèche (de son œil jusqu'où elle s'arrête) et ses foyers au sol. */
    public record Trait(UUID joueur, Vector3f depart, Vector3f fin, List<Vector3f> sol) implements CustomPacketPayload {
        public static final Type<Trait> TYPE = new Type<>(Cabane.id("trait_noir"));
        public static final StreamCodec<ByteBuf, Trait> CODEC = StreamCodec.composite(
                UUIDUtil.STREAM_CODEC, Trait::joueur, ByteBufCodecs.VECTOR3F, Trait::depart, ByteBufCodecs.VECTOR3F, Trait::fin,
                ByteBufCodecs.VECTOR3F.apply(ByteBufCodecs.list()), Trait::sol, Trait::new);

        @Override
        public Type<Trait> type() {
            return TYPE;
        }
    }

    /** Serveur → ceux qui voient cette entité : elle brûle de flammes noires pendant ce nombre de ticks (0 : éteinte). */
    public record Flammes(int entite, int ticks) implements CustomPacketPayload {
        public static final Type<Flammes> TYPE = new Type<>(Cabane.id("amaterasu"));
        public static final StreamCodec<ByteBuf, Flammes> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, Flammes::entite, ByteBufCodecs.VAR_INT, Flammes::ticks, Flammes::new);

        @Override
        public Type<Flammes> type() {
            return TYPE;
        }
    }

    @SubscribeEvent
    public static void reseau(RegisterPayloadHandlersEvent event) {
        event.registrar("1").playToClient(Flammes.TYPE, Flammes.CODEC,
                (p, ctx) -> ctx.enqueueWork(() -> fr.cabane.client.pouvoir.ClientSusano3.flammes(p)))
                .playToClient(Trait.TYPE, Trait.CODEC, (p, ctx) -> ctx.enqueueWork(() -> fr.cabane.client.pouvoir.ClientSusano.trait(p)));
    }

    /** Le clic droit au niveau 3 : il tend l'arc (s'il est rechargé). */
    static void tirer(ServerPlayer joueur) {
        int maintenant = joueur.server.getTickCount();
        if (TIRS.containsKey(joueur.getUUID())) {
            return;
        }
        int reste = DERNIER_TIR.getOrDefault(joueur.getUUID(), -10_000) + RECHARGE_ARC - maintenant;
        if (reste > 0) {
            joueur.displayClientMessage(Component.literal("L'arc se reforme… " + (reste + 19) / 20 + " s").withStyle(ChatFormatting.GRAY), true);
            return;
        }
        TIRS.put(joueur.getUUID(), maintenant);
        DERNIER_TIR.put(joueur.getUUID(), maintenant);
        Pouvoirs.geste(joueur, Pouvoirs.ARC, -1);
        joueur.serverLevel().playSound(null, joueur.getX(), joueur.getY() + 2, joueur.getZ(), SoundEvents.CROSSBOW_LOADING_MIDDLE.value(), SoundSource.PLAYERS, 1.4F, 0.5F);
    }

    static void endormir(ServerPlayer joueur) {
        TIRS.remove(joueur.getUUID());
        GRANDS.remove(joueur.getUUID());
    }

    /** Accroupi au niveau 3 : il lève son épée (s'il le peut). */
    static void grandCoup(ServerPlayer joueur) {
        int maintenant = joueur.server.getTickCount();
        if (GRANDS.containsKey(joueur.getUUID())) {
            return;
        }
        int reste = DERNIER_GRAND.getOrDefault(joueur.getUUID(), -10_000) + RECHARGE_GRAND - maintenant;
        if (reste > 0) {
            joueur.displayClientMessage(Component.literal("Le grand coup se recharge… " + (reste + 19) / 20 + " s").withStyle(ChatFormatting.GRAY), true);
            return;
        }
        GRANDS.put(joueur.getUUID(), maintenant);
        DERNIER_GRAND.put(joueur.getUUID(), maintenant);
        Pouvoirs.geste(joueur, Pouvoirs.GRAND_COUP, -1);
    }

    /** L'épée touche le sol : tout ce qui est autour (et loin devant) prend le coup et s'embrase de flammes noires. */
    private static void abattre(ServerPlayer joueur) {
        ServerLevel monde = joueur.serverLevel();
        Vec3 devant = Pouvoirs.devant(joueur), ici = joueur.position();
        for (LivingEntity e : monde.getEntitiesOfClass(LivingEntity.class, joueur.getBoundingBox().inflate(PORTEE_GRAND, 4, PORTEE_GRAND),
                e -> Pouvoirs.cible(joueur, e))) {
            Vec3 vers = e.position().subtract(ici), plat = new Vec3(vers.x, 0, vers.z);
            double d = plat.length();
            if (d > RAYON_GRAND && (d > PORTEE_GRAND || plat.normalize().dot(devant) < 0.6)) {
                continue;
            }
            e.hurt(joueur.damageSources().playerAttack(joueur), DEGATS_GRAND);
            if (d > 0.1) {
                e.knockback(1.6, -plat.x / d, -plat.z / d);
            }
            e.setDeltaMovement(e.getDeltaMovement().add(0, 0.45, 0));
            e.hurtMarked = true;
            enflammer(e, joueur);
            poserSous(e, joueur.getUUID());
        }
        // des flammes noires posées partout autour, qui restent (FeuNoir)
        for (int i = 0; i < FLAMMES_GRAND; i++) {
            double a = monde.random.nextDouble() * Math.PI * 2, d = 1.5 + Math.sqrt(monde.random.nextDouble()) * (RAYON_GRAND - 1.5);
            Vec3 p = sol(monde, ici.add(Math.cos(a) * d, 3, Math.sin(a) * d), 7, joueur);
            if (p != null) {
                FeuNoir.poser(monde, BlockPos.containing(p.x, p.y + 0.05, p.z), joueur.getUUID());
            }
        }
        Vec3 impact = ici.add(devant.scale(5));
        monde.sendParticles(ParticleTypes.EXPLOSION_EMITTER, impact.x, impact.y + 0.3, impact.z, 1, 0, 0, 0, 0);
        for (int i = 0; i < 48; i++) {
            double a = i / 48.0 * Math.PI * 2;
            monde.sendParticles(ParticleTypes.SQUID_INK, ici.x + Math.cos(a) * RAYON_GRAND * 0.7, ici.y + 0.3, ici.z + Math.sin(a) * RAYON_GRAND * 0.7,
                    3, 0.6, 0.3, 0.6, 0.08);
            monde.sendParticles(ParticleTypes.LARGE_SMOKE, ici.x + Math.cos(a) * RAYON_GRAND * 0.5, ici.y + 0.2, ici.z + Math.sin(a) * RAYON_GRAND * 0.5,
                    1, 0.4, 0.2, 0.4, 0.04);
        }
        monde.playSound(null, impact.x, impact.y, impact.z, SoundEvents.GENERIC_EXPLODE.value(), SoundSource.PLAYERS, 2.5F, 0.5F);
        monde.playSound(null, impact.x, impact.y, impact.z, SoundEvents.FIRECHARGE_USE, SoundSource.PLAYERS, 2F, 0.4F);
        monde.playSound(null, impact.x, impact.y, impact.z, SoundEvents.WITHER_SHOOT, SoundSource.PLAYERS, 1.2F, 0.5F);
    }

    /**
     * La flèche part, droit là où il regarde (jusqu'à 48 blocs, ou jusqu'au premier mur) : elle vole ensuite tick après
     * tick (voir {@link #tick}). Ses foyers au sol sont choisis tout de suite : sous sa ligne, et en flaque au bout.
     */
    private static void lacher(ServerPlayer joueur) {
        ServerLevel monde = joueur.serverLevel();
        Vec3 oeil = joueur.getEyePosition(), regard = joueur.getLookAngle();
        Vec3 bout = oeil.add(regard.scale(PORTEE_ARC));
        HitResult mur = monde.clip(new ClipContext(oeil, bout, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, joueur));
        boolean plantee = mur.getType() != HitResult.Type.MISS;
        if (plantee) {
            bout = mur.getLocation();
        }
        double longueur = bout.distanceTo(oeil);
        List<Foyer> foyers = new ArrayList<>();
        Vec3 cote = new Vec3(-regard.z, 0, regard.x);
        cote = cote.lengthSqr() < 1e-4 ? new Vec3(1, 0, 0) : cote.normalize();
        for (double s = 2; s < longueur; s += PAS_SOL) {
            for (double[] b : BANDE) {
                if (monde.random.nextDouble() >= b[1]) {
                    continue;
                }
                Vec3 p = sol(monde, oeil.add(regard.scale(s)).add(cote.scale(b[0] + (monde.random.nextDouble() - 0.5) * 0.6)), 7, joueur);
                if (p != null) {
                    foyers.add(new Foyer(p, s));
                }
            }
        }
        if (plantee) {
            // la flaque : chaque bloc à 3 blocs (ou moins) de là où elle se plante
            Vec3 centre = bout.subtract(regard.scale(0.6));
            for (int dx = -RAYON_FLAQUE; dx <= RAYON_FLAQUE; dx++) {
                for (int dz = -RAYON_FLAQUE; dz <= RAYON_FLAQUE; dz++) {
                    if (dx * dx + dz * dz > RAYON_FLAQUE * RAYON_FLAQUE + 1) {
                        continue;
                    }
                    Vec3 p = sol(monde, centre.add(dx, 1.5, dz), 5, joueur);
                    if (p != null) {
                        foyers.add(new Foyer(p, longueur));
                    }
                }
            }
        }
        VOLS.add(new Vol(joueur.getUUID(), monde, oeil, regard, longueur, plantee, joueur.server.getTickCount(), new HashSet<>(), foyers));
        Pouvoirs.geste(joueur, Pouvoirs.FLECHE, -1);
        PacketDistributor.sendToPlayersTrackingEntityAndSelf(joueur, new Trait(joueur.getUUID(), v(oeil), v(bout), foyers.stream().map(f -> v(f.pos())).toList()));
        monde.playSound(null, joueur.getX(), joueur.getY() + 2, joueur.getZ(), SoundEvents.CROSSBOW_SHOOT, SoundSource.PLAYERS, 2F, 0.5F);
        monde.playSound(null, joueur.getX(), joueur.getY() + 2, joueur.getZ(), SoundEvents.BLAZE_SHOOT, SoundSource.PLAYERS, 1F, 0.4F);
    }

    /** Le sol sous ce point : le dessus du premier bloc plein, à au plus {@code profondeur} blocs dessous (null sinon). */
    private static Vec3 sol(ServerLevel monde, Vec3 p, double profondeur, Entity qui) {
        HitResult h = monde.clip(new ClipContext(p, p.subtract(0, profondeur, 0), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, qui));
        return h.getType() == HitResult.Type.MISS ? null : h.getLocation();
    }

    private static Vector3f v(Vec3 p) {
        return new Vector3f((float) p.x, (float) p.y, (float) p.z);
    }

    /** Une flèche avance d'un tick : ce qu'elle traverse prend le coup et les flammes noires, ses foyers prennent derrière elle. */
    private static boolean voler(Vol vol, int maintenant, MinecraftServer server) {
        ServerPlayer tireur = server.getPlayerList().getPlayer(vol.tireur());
        int t = maintenant - vol.debut();
        double d0 = Math.max(0, (t - 1) * VITESSE_FLECHE), d1 = Math.min(vol.longueur(), t * VITESSE_FLECHE);
        if (d1 <= d0) {
            return false;
        }
        Vec3 a = vol.depart().add(vol.dir().scale(d0)), b = vol.depart().add(vol.dir().scale(d1));
        if (tireur != null) {
            for (LivingEntity e : vol.monde().getEntitiesOfClass(LivingEntity.class, new AABB(a, b).inflate(LARGEUR_FLECHE + 1),
                    e -> Pouvoirs.cible(tireur, e) && !vol.touches().contains(e.getId()))) {
                AABB boite = e.getBoundingBox().inflate(LARGEUR_FLECHE);
                if (boite.contains(a) || boite.clip(a, b).isPresent()) {
                    vol.touches().add(e.getId());
                    e.hurt(tireur.damageSources().playerAttack(tireur), DEGATS_FLECHE);
                    enflammer(e, tireur);
                    poserSous(e, tireur.getUUID());
                    vol.monde().sendParticles(ParticleTypes.SQUID_INK, e.getX(), e.getY(0.6), e.getZ(), 16, 0.3, 0.4, 0.3, 0.08);
                }
            }
        }
        for (Foyer f : vol.foyers()) {
            if (f.distance() > d0 && f.distance() <= d1) {
                FeuNoir.poser(vol.monde(), BlockPos.containing(f.pos().x, f.pos().y + 0.05, f.pos().z), vol.tireur());
            }
        }
        if (d1 < vol.longueur()) {
            return false;
        }
        // au bout : elle se plante (une gerbe de flammes noires) ou s'éteint en fumée
        Vec3 bout = b;
        if (vol.plantee()) {
            vol.monde().sendParticles(ParticleTypes.SQUID_INK, bout.x, bout.y, bout.z, 40, 0.8, 0.5, 0.8, 0.12);
            vol.monde().sendParticles(ParticleTypes.LARGE_SMOKE, bout.x, bout.y, bout.z, 14, 0.7, 0.4, 0.7, 0.05);
            vol.monde().playSound(null, bout.x, bout.y, bout.z, SoundEvents.FIRECHARGE_USE, SoundSource.PLAYERS, 2F, 0.4F);
            vol.monde().playSound(null, bout.x, bout.y, bout.z, SoundEvents.WITHER_SHOOT, SoundSource.PLAYERS, 0.8F, 0.5F);
        } else {
            vol.monde().sendParticles(ParticleTypes.SQUID_INK, bout.x, bout.y, bout.z, 20, 0.3, 0.3, 0.3, 0.05);
        }
        return true;
    }

    /** Les flammes noires prennent sur cette entité (ou repartent pour 10 secondes si elle brûlait déjà). */
    public static void enflammer(LivingEntity cible, ServerPlayer tireur) {
        enflammer(cible, tireur.getUUID());
    }

    private static void enflammer(LivingEntity cible, UUID tireur) {
        if (!(cible.level() instanceof ServerLevel monde)) {
            return;
        }
        FEUX.put(cible.getId(), new Feu(monde, monde.getServer().getTickCount() + DUREE_FEU, tireur));
        cible.clearFire();
        PacketDistributor.sendToPlayersTrackingEntityAndSelf(cible, new Flammes(cible.getId(), DUREE_FEU));
        monde.playSound(null, cible.getX(), cible.getY(), cible.getZ(), SoundEvents.FIRECHARGE_USE, SoundSource.PLAYERS, 1.2F, 0.4F);
    }

    /**
     * Qui est dans des flammes noires posées au sol prend feu (au plus une fois toutes les 2 secondes, tant qu'il y reste) ;
     * jamais celui qui les a posées, ni ses alliés.
     */
    static void bruler(LivingEntity e, UUID tireur) {
        if (!(e.level() instanceof ServerLevel monde) || !e.isAlive() || e.getUUID().equals(tireur)) {
            return;
        }
        ServerPlayer joueur = tireur == null ? null : monde.getServer().getPlayerList().getPlayer(tireur);
        if (joueur != null && !Pouvoirs.cible(joueur, e)) {
            return;
        }
        Feu feu = FEUX.get(e.getId());
        if (feu == null || feu.fin() - monde.getServer().getTickCount() < DUREE_FEU - 40) {
            enflammer(e, tireur);
        }
    }

    /** Une flamme noire sous les pieds de ce qu'une attaque vient de toucher. */
    private static void poserSous(LivingEntity e, UUID tireur) {
        if (e.level() instanceof ServerLevel monde && e.onGround()) {
            FeuNoir.poser(monde, e.blockPosition(), tireur);
        }
    }

    @SubscribeEvent
    public static void tick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        int maintenant = server.getTickCount();
        // les arcs tendus : la flèche part au bout de la tension
        for (Map.Entry<UUID, Integer> tir : new ArrayList<>(TIRS.entrySet())) {
            ServerPlayer joueur = server.getPlayerList().getPlayer(tir.getKey());
            if (joueur == null) {
                TIRS.remove(tir.getKey());
            } else if (maintenant - tir.getValue() >= TENSION) {
                TIRS.remove(tir.getKey());
                lacher(joueur);
            }
        }
        // les grands coups : l'épée touche le sol au bout de l'élan
        for (Map.Entry<UUID, Integer> coup : new ArrayList<>(GRANDS.entrySet())) {
            ServerPlayer joueur = server.getPlayerList().getPlayer(coup.getKey());
            if (joueur == null) {
                GRANDS.remove(coup.getKey());
            } else if (maintenant - coup.getValue() >= IMPACT_GRAND) {
                GRANDS.remove(coup.getKey());
                abattre(joueur);
            }
        }
        // les flèches en vol
        VOLS.removeIf(vol -> voler(vol, maintenant, server));
        // les flammes noires brûlent : des dégâts chaque seconde, de la fumée noire ; l'eau n'y fait rien
        for (Map.Entry<Integer, Feu> entree : new ArrayList<>(FEUX.entrySet())) {
            Feu feu = entree.getValue();
            Entity e = feu.monde().getEntity(entree.getKey());
            if (!(e instanceof LivingEntity cible) || !cible.isAlive() || maintenant >= feu.fin()) {
                FEUX.remove(entree.getKey());
                if (e != null) {
                    PacketDistributor.sendToPlayersTrackingEntityAndSelf(e, new Flammes(entree.getKey(), 0));
                }
                continue;
            }
            cible.clearFire();
            if ((feu.fin() - maintenant) % 20 == 0) {
                ServerPlayer tireur = server.getPlayerList().getPlayer(feu.tireur());
                cible.invulnerableTime = 0;
                cible.hurt(new DamageSource(feu.monde().registryAccess().registryOrThrow(Registries.DAMAGE_TYPE).getHolderOrThrow(AMATERASU), tireur), DEGATS_FEU);
            }
            if (maintenant % 3 == 0) {
                feu.monde().sendParticles(ParticleTypes.SQUID_INK, cible.getX(), cible.getY(0.6), cible.getZ(), 2, 0.3, 0.4, 0.3, 0.02);
            }
        }
    }

    private Susano3() {
    }
}
