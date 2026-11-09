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
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
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
 * Le Susano niveau 4, le Susano parfait : l'ultime (liste « susano4 » : niveaux 1 à 4 ; « susanodev » y va aussi).
 * Au niveau 3, la touche Éveil le fait apparaître, une fois par éveil (puis 5 minutes d'attente) : le chevalier
 * s'embrase, un géant de 38 blocs se matérialise des pieds à la tête, le joueur monte dans le joyau de son front et le
 * pilote pendant 45 secondes, puis il redevient le chevalier. Le géant est dessiné chez chacun (ClientSusano4) ; ici,
 * le joueur est tenu à la hauteur du joyau (les pieds du géant touchent le sol), il vole lentement, et ses attaques :
 * clic gauche, l'épée de lumière puis l'épée aux flammes noires, en alternance, sur tout ce qui est devant ; clic
 * droit, une pluie d'Amaterasu sur une zone ronde là où il vise ; accroupi, la perce, une charge en vrille.
 */
@EventBusSubscriber(modid = Cabane.MODID)
public final class Susano4 {
    public static final String NOM = "susano4";
    /** La transformation (5 s), la forme (45 s), le retour au chevalier (2 s), et l'attente avant la suivante (5 min). */
    public static final int TRANSFO = 100, DUREE = 45 * 20, RETOUR = 40, RECHARGE = 5 * 60 * 20;
    /** Le joueur (derrière la fente des yeux du heaume) est à 33,8 blocs au-dessus des pieds du géant. */
    public static final double HAUT = 21.7;
    /** Le vol : un peu moins vite que le vol créatif (0,05), environ 9 blocs par seconde. */
    private static final float VOL = 0.055F;
    /** Les coups d'épée : un toutes les 0,8 s, sur 13 blocs devant lui ; lumière 14, flammes noires 12 (et le feu au sol). */
    public static final int RECHARGE_COUP = 16;
    private static final double PORTEE_COUP = 13;
    private static final float DEGATS_LUMIERE = 14, DEGATS_NOIR = 12;
    /** La pluie d'Amaterasu : jusqu'à 60 blocs, une zone de 10 blocs, 3 s ; 4 par goutte, 12 au plus par cible ; 5 s de recharge. */
    public static final int RECHARGE_PLUIE = 100;
    private static final int DUREE_PLUIE = 60;
    private static final double PORTEE_PLUIE = 60, RAYON_PLUIE = 10;
    private static final float DEGATS_GOUTTE = 4, DEGATS_PLUIE_MAX = 12;
    /** La perce : 24 blocs en 1 s, 20 de dégâts, tout est projeté ; 12 s de recharge. */
    public static final int RECHARGE_PERCE = 240, DUREE_PERCE = 20;
    private static final float DEGATS_PERCE = 20;

    public static boolean est(String pouvoir) {
        return NOM.equals(pouvoir) || Susano3.DEV.equals(pouvoir);
    }

    /** Qui est en Susano parfait : le joueur → le tick où la transformation a commencé (et celui où il revient, -1 sinon). */
    private record Forme(int debut, int retour) {
    }

    private static final Map<UUID, Forme> FORMES = new HashMap<>();
    /** Ceux qui l'ont déjà fait pendant cet éveil, et quand chacun pourra le refaire. */
    private static final Set<UUID> FAITES = new HashSet<>();
    private static final Map<UUID, Integer> PRET = new HashMap<>();
    private static final Map<UUID, Integer> COUPS = new HashMap<>();
    private static final Map<UUID, Integer> NB_COUPS = new HashMap<>();
    private static final Map<UUID, Integer> PLUIES_PRETES = new HashMap<>();
    private static final Map<UUID, Integer> PERCES_PRETES = new HashMap<>();

    private record Pluie(UUID tireur, ServerLevel monde, Vec3 centre, int debut, Map<Integer, Float> recu) {
    }

    private static final List<Pluie> PLUIES = new ArrayList<>();

    private record Perce(int debut, Vec3 dir, Set<Integer> touches) {
    }

    private static final Map<UUID, Perce> PERCES = new HashMap<>();

    // ================================================================ le réseau

    /** Serveur → tous : la phase de son Susano parfait (0 rien, 1 la transformation, 2 la forme, 3 le retour), et depuis combien de ticks. */
    public record Etat4(UUID joueur, int phase, int ecoule) implements CustomPacketPayload {
        public static final Type<Etat4> TYPE = new Type<>(Cabane.id("susano4_etat"));
        public static final StreamCodec<ByteBuf, Etat4> CODEC = StreamCodec.composite(
                UUIDUtil.STREAM_CODEC, Etat4::joueur, ByteBufCodecs.VAR_INT, Etat4::phase, ByteBufCodecs.VAR_INT, Etat4::ecoule, Etat4::new);

        @Override
        public Type<Etat4> type() {
            return TYPE;
        }
    }

    /** Serveur → ceux qui le voient : une attaque (0 l'épée de lumière, 1 l'épée noire, 2 la pluie, 3 la perce) et son point. */
    public record Attaque4(UUID joueur, int sorte, Vector3f point) implements CustomPacketPayload {
        public static final int LUMIERE = 0, NOIR = 1, PLUIE = 2, PERCE = 3;
        public static final Type<Attaque4> TYPE = new Type<>(Cabane.id("susano4_attaque"));
        public static final StreamCodec<ByteBuf, Attaque4> CODEC = StreamCodec.composite(
                UUIDUtil.STREAM_CODEC, Attaque4::joueur, ByteBufCodecs.VAR_INT, Attaque4::sorte, ByteBufCodecs.VECTOR3F, Attaque4::point, Attaque4::new);

        @Override
        public Type<Attaque4> type() {
            return TYPE;
        }
    }

    @SubscribeEvent
    public static void reseau(RegisterPayloadHandlersEvent event) {
        event.registrar("1")
                .playToClient(Etat4.TYPE, Etat4.CODEC, (p, ctx) -> ctx.enqueueWork(() -> fr.cabane.client.pouvoir.ClientSusano4.etat(p)))
                .playToClient(Attaque4.TYPE, Attaque4.CODEC, (p, ctx) -> ctx.enqueueWork(() -> fr.cabane.client.pouvoir.ClientSusano4.attaque(p)));
    }

    // ================================================================ entrer, sortir

    /** Au niveau 3 : peut-il passer au Susano parfait ? Sinon, il le lui dit. */
    static boolean pret(ServerPlayer joueur) {
        if (FAITES.contains(joueur.getUUID())) {
            joueur.displayClientMessage(Component.literal("Le Susano parfait ne vient qu'une fois par éveil.").withStyle(ChatFormatting.GRAY), true);
            return false;
        }
        int reste = PRET.getOrDefault(joueur.getUUID(), 0) - joueur.server.getTickCount();
        if (reste > 0) {
            joueur.displayClientMessage(Component.literal("Le Susano parfait revient dans " + (reste + 19) / 20 + " s").withStyle(ChatFormatting.GRAY), true);
            return false;
        }
        return true;
    }

    /** Le niveau 4 commence : la transformation. */
    static void eveiller(ServerPlayer joueur) {
        int maintenant = joueur.server.getTickCount();
        FORMES.put(joueur.getUUID(), new Forme(maintenant, -1));
        FAITES.add(joueur.getUUID());
        if (!Susano3.DEV.equals(Pouvoirs.pouvoir(joueur))) {
            PRET.put(joueur.getUUID(), maintenant + RECHARGE);
        }
        PacketDistributor.sendToAllPlayers(new Etat4(joueur.getUUID(), 1, 0));
        ServerLevel monde = joueur.serverLevel();
        monde.playSound(null, joueur.getX(), joueur.getY(), joueur.getZ(), SoundEvents.WITHER_SPAWN, SoundSource.PLAYERS, 1.6F, 0.6F);
        monde.playSound(null, joueur.getX(), joueur.getY(), joueur.getZ(), SoundEvents.BEACON_ACTIVATE, SoundSource.PLAYERS, 2F, 0.5F);
    }

    /** La touche Éveil pendant la forme : il revient au chevalier plus tôt. */
    static void finir(ServerPlayer joueur) {
        Forme f = FORMES.get(joueur.getUUID());
        if (f != null && f.retour() < 0) {
            FORMES.put(joueur.getUUID(), new Forme(f.debut(), joueur.server.getTickCount()));
            PacketDistributor.sendToAllPlayers(new Etat4(joueur.getUUID(), 3, 0));
        }
    }

    /** Le pouvoir s'endort : plus de géant, et il peut le refaire au prochain éveil (après l'attente). */
    static void endormir(ServerPlayer joueur) {
        ranger(joueur);
        FAITES.remove(joueur.getUUID());
    }

    /** Le Susano se range (ses yeux restent éveillés) : plus de géant, mais il reste fait pour cet éveil. */
    static void ranger(ServerPlayer joueur) {
        if (FORMES.remove(joueur.getUUID()) != null) {
            atterrir(joueur);
            PacketDistributor.sendToAllPlayers(new Etat4(joueur.getUUID(), 0, 0));
        }
        PERCES.remove(joueur.getUUID());
    }

    /** En forme (pas pendant la transformation ni le retour) : ses attaques sont celles du géant. */
    public static boolean actif(ServerPlayer joueur) {
        Forme f = FORMES.get(joueur.getUUID());
        return f != null && f.retour() < 0 && joueur.server.getTickCount() - f.debut() >= TRANSFO;
    }

    public static boolean enForme(ServerPlayer joueur) {
        return FORMES.containsKey(joueur.getUUID());
    }

    /** Il redescend : plus de vol (sauf en créatif), une chute lente, pas de dégâts de chute. */
    private static void atterrir(ServerPlayer joueur) {
        var a = joueur.getAbilities();
        boolean libre = joueur.isCreative() || joueur.isSpectator();
        a.mayfly = libre;
        a.flying = libre && a.flying;
        a.setFlyingSpeed(0.05F);
        joueur.onUpdateAbilities();
        joueur.fallDistance = 0;
        joueur.addEffect(new MobEffectInstance(MobEffects.SLOW_FALLING, 200, 0, false, false));
    }

    // ================================================================ les attaques

    private static Vec3 pieds(ServerPlayer joueur) {
        return joueur.position().subtract(0, HAUT, 0);
    }

    /** Ce que le géant touche devant lui : à moins de portee blocs (à plat), dans un cône de 140°, du sol à sa tête. */
    private static List<LivingEntity> devant(ServerPlayer joueur, Vec3 f, double portee, double cosinus) {
        Vec3 p = pieds(joueur);
        List<LivingEntity> l = new ArrayList<>();
        for (LivingEntity e : joueur.serverLevel().getEntitiesOfClass(LivingEntity.class, new AABB(p, p).inflate(portee, 0, portee).expandTowards(0, HAUT + 8, 0).move(0, -3, 0),
                e -> Pouvoirs.cible(joueur, e))) {
            Vec3 v = new Vec3(e.getX() - p.x, 0, e.getZ() - p.z);
            double d = v.length();
            if (d <= portee && (d < 3 || v.normalize().dot(f) >= cosinus)) {
                l.add(e);
            }
        }
        return l;
    }

    /** Clic gauche : un coup sur deux, l'épée de lumière (de gauche à droite) ou l'épée noire (qui laisse les flammes au sol). */
    static void coup(ServerPlayer joueur) {
        int maintenant = joueur.server.getTickCount();
        if (maintenant - COUPS.getOrDefault(joueur.getUUID(), -100) < RECHARGE_COUP) {
            return;
        }
        COUPS.put(joueur.getUUID(), maintenant);
        int n = NB_COUPS.merge(joueur.getUUID(), 1, Integer::sum);
        boolean noir = n % 2 == 0;
        Vec3 f = Pouvoirs.devant(joueur), p = pieds(joueur);
        ServerLevel monde = joueur.serverLevel();
        for (LivingEntity e : devant(joueur, f, PORTEE_COUP, 0.34)) {
            e.hurt(joueur.damageSources().playerAttack(joueur), noir ? DEGATS_NOIR : DEGATS_LUMIERE);
            e.knockback(1.2, -f.x, -f.z);
            e.hurtMarked = true;
            if (noir) {
                Susano3.enflammer(e, joueur);
            }
        }
        if (noir) {
            // les flammes noires restent au sol, dans l'arc du coup
            for (int i = 0; i < 16; i++) {
                double a = Math.atan2(f.x, f.z) + (monde.random.nextDouble() - 0.5) * 2.4, d = 5 + monde.random.nextDouble() * (PORTEE_COUP - 5);
                Vec3 s = sol(monde, p.add(Math.sin(a) * d, 6, Math.cos(a) * d), 14, joueur);
                if (s != null) {
                    FeuNoir.poser(monde, BlockPos.containing(s.x, s.y + 0.05, s.z), joueur.getUUID());
                }
            }
        }
        PacketDistributor.sendToPlayersTrackingEntityAndSelf(joueur, new Attaque4(joueur.getUUID(), noir ? Attaque4.NOIR : Attaque4.LUMIERE, Fruit.v(p)));
        monde.playSound(null, p.x, p.y + 15, p.z, SoundEvents.PLAYER_ATTACK_SWEEP, SoundSource.PLAYERS, 3F, 0.4F);
        monde.playSound(null, p.x, p.y + 15, p.z, noir ? SoundEvents.WITHER_SHOOT : SoundEvents.TRIDENT_RIPTIDE_3.value(), SoundSource.PLAYERS, 1.5F, noir ? 0.5F : 0.7F);
    }

    /** Clic droit : la pluie d'Amaterasu, là où il vise (au sol), sur une zone ronde. */
    static void pluie(ServerPlayer joueur) {
        int maintenant = joueur.server.getTickCount();
        int reste = PLUIES_PRETES.getOrDefault(joueur.getUUID(), 0) - maintenant;
        if (reste > 0) {
            joueur.displayClientMessage(Component.literal("La pluie d'Amaterasu revient… " + (reste + 19) / 20 + " s").withStyle(ChatFormatting.GRAY), true);
            return;
        }
        ServerLevel monde = joueur.serverLevel();
        Vec3 oeil = joueur.getEyePosition(), bout = oeil.add(joueur.getLookAngle().scale(PORTEE_PLUIE));
        HitResult h = monde.clip(new ClipContext(oeil, bout, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, joueur));
        Vec3 centre = h.getType() == HitResult.Type.MISS ? sol(monde, bout, 80, joueur) : h.getLocation();
        if (centre == null) {
            centre = new Vec3(bout.x, pieds(joueur).y, bout.z);
        }
        PLUIES_PRETES.put(joueur.getUUID(), maintenant + RECHARGE_PLUIE);
        PLUIES.add(new Pluie(joueur.getUUID(), monde, centre, maintenant, new HashMap<>()));
        PacketDistributor.sendToPlayersNear(monde, null, centre.x, centre.y, centre.z, 160, new Attaque4(joueur.getUUID(), Attaque4.PLUIE, Fruit.v(centre)));
        monde.playSound(null, centre.x, centre.y + 20, centre.z, SoundEvents.WITHER_SPAWN, SoundSource.PLAYERS, 2F, 0.4F);
    }

    /** Accroupi : la perce ; il fonce droit devant (à plat), les deux épées en avant, en vrille. */
    static void perce(ServerPlayer joueur) {
        int maintenant = joueur.server.getTickCount();
        int reste = PERCES_PRETES.getOrDefault(joueur.getUUID(), 0) - maintenant;
        if (reste > 0) {
            joueur.displayClientMessage(Component.literal("La perce revient… " + (reste + 19) / 20 + " s").withStyle(ChatFormatting.GRAY), true);
            return;
        }
        PERCES_PRETES.put(joueur.getUUID(), maintenant + RECHARGE_PERCE);
        Vec3 f = Pouvoirs.devant(joueur);
        PERCES.put(joueur.getUUID(), new Perce(maintenant, f, new HashSet<>()));
        PacketDistributor.sendToPlayersTrackingEntityAndSelf(joueur, new Attaque4(joueur.getUUID(), Attaque4.PERCE, Fruit.v(f)));
        Vec3 p = pieds(joueur);
        joueur.serverLevel().playSound(null, p.x, p.y + 15, p.z, SoundEvents.TRIDENT_RIPTIDE_3.value(), SoundSource.PLAYERS, 3F, 0.5F);
        joueur.serverLevel().playSound(null, p.x, p.y + 15, p.z, SoundEvents.ENDER_DRAGON_GROWL, SoundSource.PLAYERS, 1.2F, 0.7F);
    }

    /** Le sol sous ce point (le dessus du premier bloc plein, à au plus profondeur blocs dessous), ou null. */
    private static Vec3 sol(ServerLevel monde, Vec3 p, double profondeur, ServerPlayer qui) {
        HitResult h = monde.clip(new ClipContext(p, p.subtract(0, profondeur, 0), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, qui));
        return h.getType() == HitResult.Type.MISS ? null : h.getLocation();
    }

    // ================================================================ chaque tick

    @SubscribeEvent
    public static void tick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        int maintenant = server.getTickCount();
        for (Map.Entry<UUID, Forme> entree : new ArrayList<>(FORMES.entrySet())) {
            ServerPlayer joueur = server.getPlayerList().getPlayer(entree.getKey());
            Forme f = entree.getValue();
            if (joueur == null || !joueur.isAlive()) {
                FORMES.remove(entree.getKey());
                continue;
            }
            int t = maintenant - f.debut();
            // la forme s'achève au bout de 45 s : il revient au chevalier (« susanodev » : quand il le décide)
            boolean sansFin = Susano3.DEV.equals(Pouvoirs.pouvoir(joueur));
            if (f.retour() < 0 && t >= TRANSFO + DUREE && !sansFin) {
                f = new Forme(f.debut(), maintenant);
                FORMES.put(entree.getKey(), f);
                PacketDistributor.sendToAllPlayers(new Etat4(joueur.getUUID(), 3, 0));
            }
            if (f.retour() >= 0 && maintenant - f.retour() >= RETOUR) {
                FORMES.remove(entree.getKey());
                atterrir(joueur);
                PacketDistributor.sendToAllPlayers(new Etat4(joueur.getUUID(), 0, 0));
                Pouvoirs.redescendre(joueur, 3);
                joueur.displayClientMessage(Component.literal("Le Susano parfait se défait : tu redeviens le chevalier.").withStyle(ChatFormatting.GOLD), true);
                continue;
            }
            // la transformation est finie : chacun passe le géant en forme (il vole avec le joueur, et le tient en l'air)
            if (f.retour() < 0 && t == TRANSFO) {
                PacketDistributor.sendToAllPlayers(new Etat4(joueur.getUUID(), 2, t));
            }
            if (f.retour() < 0 && t >= TRANSFO && !sansFin) {
                int reste = TRANSFO + DUREE - t;
                if (reste == 10 * 20 || reste <= 5 * 20 && reste % 20 == 0) {
                    joueur.displayClientMessage(Component.literal("Susano parfait : encore " + reste / 20 + " s").withStyle(reste <= 5 * 20 ? ChatFormatting.RED : ChatFormatting.GOLD), true);
                }
            }
            tenir(joueur, f, t, maintenant);
        }
        // les pluies d'Amaterasu : une goutte de feu noir toutes les 3 ticks, quelque part dans la zone
        for (int i = PLUIES.size() - 1; i >= 0; i--) {
            Pluie p = PLUIES.get(i);
            int t = maintenant - p.debut();
            if (t > DUREE_PLUIE) {
                PLUIES.remove(i);
                continue;
            }
            if (t % 3 != 0 || t < 8) {
                continue;
            }
            ServerPlayer tireur = server.getPlayerList().getPlayer(p.tireur());
            double a = p.monde().random.nextDouble() * Math.PI * 2, r = Math.sqrt(p.monde().random.nextDouble()) * RAYON_PLUIE;
            Vec3 haut = p.centre().add(Math.cos(a) * r, 12, Math.sin(a) * r);
            Vec3 impact = sol(p.monde(), haut, 30, tireur);
            if (impact == null) {
                continue;
            }
            p.monde().sendParticles(ParticleTypes.SQUID_INK, impact.x, impact.y + 0.3, impact.z, 6, 0.4, 0.2, 0.4, 0.06);
            if (t % 6 == 0) {
                FeuNoir.poser(p.monde(), BlockPos.containing(impact.x, impact.y + 0.05, impact.z), p.tireur());
            }
            if (tireur == null) {
                continue;
            }
            for (LivingEntity e : p.monde().getEntitiesOfClass(LivingEntity.class, new AABB(impact, impact).inflate(2.2, 2.5, 2.2), e -> Pouvoirs.cible(tireur, e))) {
                float recu = p.recu().getOrDefault(e.getId(), 0F);
                if (recu >= DEGATS_PLUIE_MAX) {
                    continue;
                }
                p.recu().put(e.getId(), recu + DEGATS_GOUTTE);
                e.invulnerableTime = 0;
                e.hurt(tireur.damageSources().playerAttack(tireur), DEGATS_GOUTTE);
                Susano3.enflammer(e, tireur);
            }
        }
    }

    /**
     * Le joueur est tenu à la hauteur du joyau : jamais plus bas que le sol + 29 (pendant la transformation, il y monte
     * peu à peu ; au retour, il redescend). Il vole lentement ; la perce le pousse droit devant.
     */
    private static void tenir(ServerPlayer joueur, Forme f, int t, int maintenant) {
        var a = joueur.getAbilities();
        if (!a.mayfly || !a.flying || a.getFlyingSpeed() != VOL) {
            a.mayfly = true;
            a.flying = true;
            a.setFlyingSpeed(VOL);
            joueur.onUpdateAbilities();
        }
        joueur.fallDistance = 0;
        double monte = f.retour() >= 0 ? 1 - lisse((maintenant - f.retour()) / (double) RETOUR) : lisse(t / (double) TRANSFO);
        Vec3 sous = sol(joueur.serverLevel(), joueur.position().add(0, 0.5, 0), 90, joueur);
        double solY = sous == null ? joueur.getY() - HAUT : sous.y;
        double cible = solY + HAUT * monte;
        Vec3 v = joueur.getDeltaMovement();
        if (t < TRANSFO || f.retour() >= 0) {
            // pendant la transformation et le retour, il ne bouge pas : il monte (ou descend) dans le joyau
            joueur.setDeltaMovement(0, (cible - joueur.getY()) * 0.25, 0);
            joueur.hurtMarked = true;
        } else if (joueur.getY() < cible - 3) {
            // en forme, c'est son jeu qui le tient à la hauteur du joyau (ClientSusano4.tenirVol) : renvoyer sa vitesse à
            // chaque tick l'arrêtait net en plein vol ; on ne le remonte ici que s'il est vraiment trop bas
            joueur.setDeltaMovement(v.x, Math.max(v.y, (cible - joueur.getY()) * 0.3), v.z);
            joueur.hurtMarked = true;
        }
        Perce p = PERCES.get(joueur.getUUID());
        if (p != null) {
            int tp = maintenant - p.debut();
            if (tp > DUREE_PERCE || f.retour() >= 0) {
                PERCES.remove(joueur.getUUID());
                return;
            }
            joueur.setDeltaMovement(p.dir().x * 1.2, Math.max(0, (cible - joueur.getY()) * 0.3), p.dir().z * 1.2);
            joueur.hurtMarked = true;
            for (LivingEntity e : devant(joueur, p.dir(), 9, 0.2)) {
                if (p.touches().add(e.getId())) {
                    e.invulnerableTime = 0;
                    e.hurt(joueur.damageSources().playerAttack(joueur), DEGATS_PERCE);
                    e.setDeltaMovement(p.dir().x * 1.4, 1.1, p.dir().z * 1.4);
                    e.hurtMarked = true;
                }
            }
        }
    }

    private static double lisse(double t) {
        t = Mth.clamp(t, 0, 1);
        return t * t * (3 - 2 * t);
    }

    private Susano4() {
    }
}
