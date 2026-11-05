package fr.cabane.pouvoir;

import fr.cabane.Cabane;
import fr.cabane.client.pouvoir.ClientMarque3;
import io.netty.buffer.ByteBuf;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
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
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Abilities;
import net.minecraft.world.level.ClipContext;
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
 * Le niveau 3 de la marque maudite (liste blanche « marque3 », après les niveaux 1 et 2 de {@link Maudit}) : l'aile gauche
 * se fissure et devient sept serpents (dessin côté client, {@link ClientMarque3}), la main de droite reste. Les dégâts
 * sont ceux du niveau 3 (voir l'équilibrage par niveau) :
 * <ul>
 * <li>clic gauche : ses coups de poing ou d'arme, boostés ({@link Maudit} : +5 dégâts au niveau 3) ;</li>
 * <li>clic droit : les serpents filent jusqu'à 9 blocs devant et mordent tout ce qui est en face (12 dégâts chacun),
 * puis reviennent en aile ; 6 s de recharge (3 s s'ils n'ont mordu personne) ;</li>
 * <li>accroupi + clic droit : les serpents fusionnent en un seul, énorme, qui sort de son dos et avale ce qui est devant
 * lui (jusqu'à 9 blocs). Il le garde dans sa gueule et le broie (2 dégâts par seconde, 8 s au plus) ; clic droit : il
 * croque (14 dégâts) et le recrache ; clic gauche : il le jette loin (10 dégâts). 18 s de recharge ;</li>
 * <li>accroupi : un grand bond vers le haut, et il vole 25 s (saut : monter, accroupi : descendre) ; deux appuis rapides
 * sur s'accroupir : il arrête de voler et tombe (sans dégâts de chute, comme au niveau 2). 25 s de recharge.</li>
 * <li>touche d'ulti (J) : l'invocation du grand serpent ({@link SerpentGeant}, dessin {@code ClientSerpentGeant}). Il
 * saute, plaque les mains au sol, pouf : un serpent violet de 117 blocs se dresse, lui debout sur sa tête, puis fonce
 * droit devant sur 130 blocs, gueule ouverte. La tête : 28 dégâts et envoyé en l'air ; le souffle sur les côtés : 12 et
 * repoussé ; l'impact à la fin : 18 autour. Chacun n'est touché qu'une fois. Le sol n'est jamais cassé : les blocs qui
 * volent sont des copies (un effet chez chaque joueur). Une fois par forme niveau 3, puis 5 min.</li>
 * </ul>
 */
@EventBusSubscriber(modid = Cabane.MODID)
public final class Marque3 {
    /** Les serpents sortent pendant ce temps (les fissures, l'éclair, ils s'allongent) : rien ne part avant. */
    public static final int TRANSFORMATION = 66;
    private static final double PORTEE = 9;
    private static final float MORSURE = 12;
    /** Les serpents mettent ce temps à atteindre leurs cibles : la morsure tombe à ce moment-là. */
    private static final int ALLER = 6;
    public static final int RECHARGE_SERPENTS = 120;
    private static final int RECHARGE_RATE = 60;
    public static final int RECHARGE_AVALE = 18 * 20;
    private static final int DUREE_AVALE = 8 * 20;
    /** Le gros serpent met ce temps à ramener sa proie jusqu'à lui. */
    private static final int ARRACHER = 8;
    private static final int CROQUER = 8;
    private static final float MACHE = 2;
    private static final float CROQUE = 14;
    private static final float JETE = 10;
    public static final int DUREE_VOL = 25 * 20;
    public static final int RECHARGE_VOL = 25 * 20;
    /** En vol, il va 1,5 fois plus vite qu'en vol créatif (0,05). */
    /** En vol : environ 1,35 fois la vitesse de course (7,6 blocs/s). */
    private static final float VITESSE_VOL = 0.035F;

    /** Client → serveur : ce qu'il veut faire. */
    public static final int SERPENTS = 0, AVALER = 1, JETER = 2, VOLER = 3;
    /** Serveur → ceux qui le voient : ce qui se passe (voir {@link Geste3}). */
    public static final int GESTE_MORSURE = 0, GESTE_PRISE = 1, GESTE_CROQUE = 2, GESTE_JET = 3, GESTE_VOL = 4;

    /** L'ulti : une fois par forme niveau 3, puis cette recharge (en ticks). */
    public static final int RECHARGE_ULTI = 5 * 60 * 20;
    private static final float ULTI_TETE = 28, ULTI_SOUFFLE = 12, ULTI_IMPACT = 18;
    private static final double RAYON_IMPACT = 9;
    /** Les joueurs à qui on montre l'ulti (le serpent fait 130 blocs de long). */
    private static final double VU_DE = 256;
    private static final Map<UUID, Long> PRET_ULTI = new HashMap<>();

    /**
     * Serveur → ceux qui sont autour : un grand serpent est lancé ({@code debut} : l'heure du monde au lancement, -1 :
     * il disparaît tout de suite), d'où, vers où, et la hauteur du sol sous chaque point de son chemin.
     */
    public record Ulti3(UUID joueur, long debut, Vector3f origine, float lacet, List<Float> sols) implements CustomPacketPayload {
        public static final Type<Ulti3> TYPE = new Type<>(Cabane.id("marque3_ulti"));
        public static final StreamCodec<ByteBuf, Ulti3> CODEC = StreamCodec.composite(
                UUIDUtil.STREAM_CODEC, Ulti3::joueur, ByteBufCodecs.VAR_LONG, Ulti3::debut, ByteBufCodecs.VECTOR3F, Ulti3::origine,
                ByteBufCodecs.FLOAT, Ulti3::lacet, ByteBufCodecs.FLOAT.apply(ByteBufCodecs.list(64)), Ulti3::sols, Ulti3::new);

        @Override
        public Type<Ulti3> type() {
            return TYPE;
        }
    }

    public record Action(int quoi) implements CustomPacketPayload {
        public static final Type<Action> TYPE = new Type<>(Cabane.id("marque3_action"));
        public static final StreamCodec<ByteBuf, Action> CODEC = StreamCodec.composite(ByteBufCodecs.VAR_INT, Action::quoi, Action::new);

        @Override
        public Type<Action> type() {
            return TYPE;
        }
    }

    /**
     * Serveur → ceux qui le voient : une morsure ({@code points} : où mordent les serpents), la prise du gros serpent
     * ({@code valeur} : l'entité avalée, -1 quand il la lâche), un croc, un jet, le vol ({@code valeur} : 1 il s'envole, 0
     * il retombe).
     */
    public record Geste3(UUID joueur, int geste, int valeur, List<Vector3f> points) implements CustomPacketPayload {
        public static final Type<Geste3> TYPE = new Type<>(Cabane.id("marque3_geste"));
        public static final StreamCodec<ByteBuf, Geste3> CODEC = StreamCodec.composite(
                UUIDUtil.STREAM_CODEC, Geste3::joueur, ByteBufCodecs.VAR_INT, Geste3::geste, ByteBufCodecs.VAR_INT, Geste3::valeur,
                ByteBufCodecs.VECTOR3F.apply(ByteBufCodecs.list(16)), Geste3::points, Geste3::new);

        @Override
        public Type<Geste3> type() {
            return TYPE;
        }
    }

    @SubscribeEvent
    public static void reseau(RegisterPayloadHandlersEvent event) {
        event.registrar("1")
                .playToServer(Action.TYPE, Action.CODEC, (p, ctx) -> ctx.enqueueWork(() -> {
                    if (ctx.player() instanceof ServerPlayer joueur) {
                        agir(joueur, p.quoi());
                    }
                }))
                .playToClient(Geste3.TYPE, Geste3.CODEC, (p, ctx) -> ctx.enqueueWork(() -> ClientMarque3.geste(p)))
                .playToClient(Ulti3.TYPE, Ulti3.CODEC, (p, ctx) -> ctx.enqueueWork(() -> fr.cabane.client.pouvoir.ClientSerpentGeant.recevoir(p)));
    }

    private static void geste(ServerPlayer joueur, int geste, int valeur, List<Vector3f> points) {
        PacketDistributor.sendToPlayersTrackingEntityAndSelf(joueur, new Geste3(joueur.getUUID(), geste, valeur, points));
    }

    /** Où en est un marqué au niveau 3 (en ticks du serveur ; -1 : pas en cours). */
    private static final class Etat {
        int debut;
        int serpentsPrets;
        /** Les morsures en route : quand elles tombent, et sur qui. */
        int morsure = -1;
        final List<Integer> mordus = new ArrayList<>();
        int prise = -1;
        int priseDebut;
        Vec3 priseDepart;
        int priseRecharge = -1;
        int lacher = -1;
        int volFin = -1;
        int volRecharge = -1;
        /** L'ulti en cours : l'heure du monde au lancement (-1 : pas d'ulti), son chemin, qui a déjà été touché. */
        long ultiDebut = -1;
        double ultiAvant;
        SerpentGeant.Trajet trajet;
        final java.util.Set<Integer> touches = new java.util.HashSet<>();
        boolean ultiFait;
    }

    private static final Map<UUID, Etat> ETATS = new HashMap<>();

    static void eveiller(ServerPlayer joueur) {
        Etat e = new Etat();
        e.debut = joueur.server.getTickCount();
        ETATS.put(joueur.getUUID(), e);
        son(joueur, SoundEvents.EVOKER_PREPARE_SUMMON, 1.4F, 0.6F);
        son(joueur, SoundEvents.WITHER_AMBIENT, 0.7F, 1.4F);
    }

    static void endormir(ServerPlayer joueur) {
        Etat e = ETATS.remove(joueur.getUUID());
        if (e == null) {
            return;
        }
        lacher(joueur, e);
        if (e.volFin >= 0) {
            finVol(joueur, e, joueur.server.getTickCount());
        }
        if (e.ultiDebut >= 0) {
            finUlti(joueur, e, true);
        }
    }

    private static boolean pret(ServerPlayer joueur, Etat e) {
        return e != null && joueur.server.getTickCount() - e.debut >= TRANSFORMATION;
    }

    private static void son(ServerPlayer joueur, SoundEvent son, float volume, float hauteur) {
        joueur.serverLevel().playSound(null, joueur.getX(), joueur.getY() + 1, joueur.getZ(), son, SoundSource.PLAYERS, volume, hauteur);
    }

    private static DustParticleOptions violet(float taille) {
        return new DustParticleOptions(new Vector3f(0.55F, 0.15F, 0.85F), taille);
    }

    private static void agir(ServerPlayer joueur, int quoi) {
        Etat e = ETATS.get(joueur.getUUID());
        if (!pret(joueur, e)) {
            return;
        }
        switch (quoi) {
            case SERPENTS -> {
                if (e.prise >= 0) {
                    croquer(joueur, e);
                } else {
                    serpents(joueur, e);
                }
            }
            case AVALER -> {
                if (e.prise >= 0) {
                    croquer(joueur, e);
                } else {
                    avaler(joueur, e);
                }
            }
            case JETER -> {
                if (e.prise >= 0 && e.lacher < 0) {
                    jeter(joueur, e);
                }
            }
            case VOLER -> voler(joueur, e);
            default -> {
            }
        }
    }

    // ================================================================ clic droit : les serpents mordent devant

    /** Ce qui vit devant lui, à vue, jusqu'à la portée : les plus proches d'abord. */
    private static List<LivingEntity> devant(ServerPlayer joueur, double portee, double ouverture) {
        Vec3 regard = joueur.getLookAngle();
        Vec3 plat = new Vec3(regard.x, 0, regard.z);
        Vec3 axe = plat.lengthSqr() < 1e-4 ? Pouvoirs.devant(joueur) : plat.normalize();
        Vec3 oeil = joueur.getEyePosition();
        ServerLevel monde = joueur.serverLevel();
        List<LivingEntity> l = new ArrayList<>();
        for (LivingEntity c : monde.getEntitiesOfClass(LivingEntity.class, joueur.getBoundingBox().inflate(portee, 4, portee),
                c -> Pouvoirs.cible(joueur, c) && !tenu(c) && !c.isSpectator())) {
            Vec3 vers = new Vec3(c.getX() - joueur.getX(), 0, c.getZ() - joueur.getZ());
            double d = vers.length();
            if (d > portee || d > 0.6 && vers.normalize().dot(axe) < ouverture) {
                continue;
            }
            HitResult mur = monde.clip(new ClipContext(oeil, c.getBoundingBox().getCenter(), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, joueur));
            if (mur.getType() == HitResult.Type.MISS) {
                l.add(c);
            }
        }
        l.sort(Comparator.comparingDouble(c -> c.distanceToSqr(joueur)));
        return l;
    }

    private static Vector3f v(Vec3 p) {
        return new Vector3f((float) p.x, (float) p.y, (float) p.z);
    }

    private static void serpents(ServerPlayer joueur, Etat e) {
        int maintenant = joueur.server.getTickCount();
        if (maintenant < e.serpentsPrets || e.morsure >= 0) {
            return;
        }
        List<LivingEntity> cibles = devant(joueur, PORTEE, 0.45);
        List<Vector3f> points = new ArrayList<>();
        e.mordus.clear();
        if (cibles.isEmpty()) {
            // personne : les serpents filent quand même tout droit, dans le vide
            Vec3 loin = joueur.getEyePosition().add(joueur.getLookAngle().scale(PORTEE));
            for (int i = 0; i < 7; i++) {
                points.add(v(loin.add((i - 3) * 0.5, Math.sin(i * 1.7) * 0.6, 0)));
            }
            e.serpentsPrets = maintenant + RECHARGE_RATE;
        } else {
            // chaque serpent prend une cible, chacun son tour (s'il y en a moins de sept, plusieurs mordent la même)
            for (int i = 0; i < 7; i++) {
                LivingEntity c = cibles.get(i % cibles.size());
                points.add(v(c.getBoundingBox().getCenter().add((i - 3) * 0.12, Math.sin(i * 1.7) * 0.25, 0)));
            }
            for (int i = 0; i < Math.min(7, cibles.size()); i++) {
                e.mordus.add(cibles.get(i).getId());
            }
            e.morsure = maintenant + ALLER;
            e.serpentsPrets = maintenant + RECHARGE_SERPENTS;
        }
        // la valeur : sa recharge (pour son panneau)
        geste(joueur, GESTE_MORSURE, e.serpentsPrets - maintenant, points);
        son(joueur, SoundEvents.PHANTOM_SWOOP, 1.2F, 0.6F);
        son(joueur, SoundEvents.CAT_HISS, 1.4F, 0.6F);
    }

    /** Les serpents arrivent : chacun de ceux qu'ils visaient est mordu une fois, et tiré un peu vers lui. */
    private static void mordre(ServerPlayer joueur, Etat e) {
        e.morsure = -1;
        ServerLevel monde = joueur.serverLevel();
        for (int id : e.mordus) {
            if (monde.getEntity(id) instanceof LivingEntity c && c.isAlive()) {
                c.invulnerableTime = 0;
                c.hurt(joueur.damageSources().playerAttack(joueur), MORSURE);
                Vec3 vers = joueur.position().subtract(c.position());
                Vec3 plat = new Vec3(vers.x, 0, vers.z);
                if (plat.lengthSqr() > 1e-4) {
                    c.setDeltaMovement(c.getDeltaMovement().add(plat.normalize().scale(0.35)).add(0, 0.15, 0));
                    c.hurtMarked = true;
                }
                monde.sendParticles(ParticleTypes.DAMAGE_INDICATOR, c.getX(), c.getY(0.6), c.getZ(), 5, 0.3, 0.3, 0.3, 0.1);
                monde.sendParticles(violet(1.2F), c.getX(), c.getY(0.6), c.getZ(), 12, 0.4, 0.4, 0.4, 0.05);
                monde.playSound(null, c.getX(), c.getY() + 1, c.getZ(), SoundEvents.PHANTOM_BITE, SoundSource.PLAYERS, 1.2F, 0.7F);
            }
        }
        e.mordus.clear();
    }

    // ================================================================ accroupi + clic droit : le gros serpent avale

    /** Ce qu'un gros serpent tient déjà. */
    private static boolean tenu(LivingEntity c) {
        return ETATS.values().stream().anyMatch(x -> x.prise == c.getId());
    }

    /**
     * Où le gros serpent garde sa proie (le milieu de son corps) : là où il regarde (pas où son corps est tourné, qui
     * traîne un peu), un peu à sa gauche, à hauteur de ses yeux : en première personne, il la voit devant lui.
     */
    public static Vec3 gueule(LivingEntity joueur) {
        float lacet = joueur.getYRot() * Mth.DEG_TO_RAD;
        Vec3 devant = new Vec3(-Mth.sin(lacet), 0, Mth.cos(lacet));
        Vec3 gauche = new Vec3(Mth.cos(lacet), 0, Mth.sin(lacet));
        return joueur.position().add(devant.scale(3.4)).add(gauche.scale(0.5)).add(0, 1.75, 0);
    }

    private static void avaler(ServerPlayer joueur, Etat e) {
        int maintenant = joueur.server.getTickCount();
        if (e.lacher >= 0) {
            return;
        }
        if (maintenant < e.priseRecharge) {
            joueur.displayClientMessage(Component.literal("Les serpents se séparent… " + (e.priseRecharge - maintenant + 19) / 20 + " s")
                    .withStyle(ChatFormatting.GRAY), true);
            return;
        }
        List<LivingEntity> cibles = devant(joueur, PORTEE, 0.5);
        if (cibles.isEmpty()) {
            joueur.displayClientMessage(Component.literal("Personne à avaler devant toi.").withStyle(ChatFormatting.GRAY), true);
            return;
        }
        LivingEntity proie = cibles.get(0);
        e.prise = proie.getId();
        e.priseDebut = maintenant;
        e.priseDepart = proie.position();
        e.priseRecharge = maintenant + RECHARGE_AVALE;
        geste(joueur, GESTE_PRISE, proie.getId(), List.of());
        ServerLevel monde = joueur.serverLevel();
        monde.playSound(null, proie.getX(), proie.getY() + 1, proie.getZ(), SoundEvents.RAVAGER_ROAR, SoundSource.PLAYERS, 1F, 1.4F);
        monde.playSound(null, proie.getX(), proie.getY() + 1, proie.getZ(), SoundEvents.CAT_HISS, SoundSource.PLAYERS, 1.6F, 0.4F);
        monde.sendParticles(violet(2F), proie.getX(), proie.getY(0.5), proie.getZ(), 30, 0.6, 0.8, 0.6, 0.1);
    }

    private static void lacher(ServerPlayer joueur, Etat e) {
        e.lacher = -1;
        if (e.prise >= 0) {
            e.prise = -1;
            geste(joueur, GESTE_PRISE, -1, List.of());
        }
    }

    /** Avec une proie, clic droit : il croque de toutes ses forces, puis la recrache (voir CROQUER). */
    private static void croquer(ServerPlayer joueur, Etat e) {
        if (e.lacher >= 0) {
            return;
        }
        ServerLevel monde = joueur.serverLevel();
        if (monde.getEntity(e.prise) instanceof LivingEntity proie && proie.isAlive()) {
            proie.invulnerableTime = 0;
            proie.hurt(joueur.damageSources().playerAttack(joueur), CROQUE);
            proie.fallDistance = 0;
            monde.sendParticles(ParticleTypes.DAMAGE_INDICATOR, proie.getX(), proie.getY(0.6), proie.getZ(), 10, 0.4, 0.4, 0.4, 0.1);
            monde.sendParticles(violet(1.6F), proie.getX(), proie.getY(0.6), proie.getZ(), 30, 0.5, 0.6, 0.5, 0.1);
            monde.playSound(null, proie.getX(), proie.getY() + 1, proie.getZ(), SoundEvents.PHANTOM_BITE, SoundSource.PLAYERS, 1.6F, 0.5F);
            monde.playSound(null, proie.getX(), proie.getY() + 1, proie.getZ(), SoundEvents.GENERIC_EAT, SoundSource.PLAYERS, 1.4F, 0.5F);
        }
        geste(joueur, GESTE_CROQUE, e.prise, List.of());
        e.lacher = joueur.server.getTickCount() + CROQUER;
    }

    /** Avec une proie, clic gauche : le gros serpent la jette loin, là où il regarde. */
    private static void jeter(ServerPlayer joueur, Etat e) {
        int prise = e.prise;
        ServerLevel monde = joueur.serverLevel();
        if (monde.getEntity(prise) instanceof LivingEntity proie && proie.isAlive()) {
            proie.invulnerableTime = 0;
            proie.hurt(joueur.damageSources().playerAttack(joueur), JETE);
            proie.setDeltaMovement(joueur.getLookAngle().scale(2.8).add(0, 0.6, 0));
            proie.hurtMarked = true;
            proie.fallDistance = 0;
            monde.playSound(null, proie.getX(), proie.getY() + 1, proie.getZ(), SoundEvents.PLAYER_ATTACK_KNOCKBACK, SoundSource.PLAYERS, 1.4F, 0.6F);
            monde.playSound(null, proie.getX(), proie.getY() + 1, proie.getZ(), SoundEvents.CAT_HISS, SoundSource.PLAYERS, 1.2F, 0.5F);
        }
        geste(joueur, GESTE_JET, prise, List.of());
        e.prise = -1;
    }

    /** La proie est ramenée jusqu'à la gueule, la suit et se fait broyer ; au bout de 8 secondes, il la recrache. */
    private static void tenir(ServerPlayer joueur, Etat e, int maintenant) {
        int age = maintenant - e.priseDebut;
        if (!(joueur.serverLevel().getEntity(e.prise) instanceof LivingEntity proie) || !proie.isAlive() || proie.distanceTo(joueur) > 16
                || age > DUREE_AVALE) {
            lacher(joueur, e);
            return;
        }
        Vec3 ici = gueule(joueur);
        if (age < ARRACHER) {
            float k = age / (float) ARRACHER;
            ici = e.priseDepart.add(0, proie.getBbHeight() / 2, 0).lerp(ici, k * k * (3 - 2 * k));
        }
        proie.teleportTo(ici.x, ici.y - proie.getBbHeight() / 2, ici.z);
        proie.setDeltaMovement(Vec3.ZERO);
        proie.fallDistance = 0;
        if (age > ARRACHER && age % 20 == 0) {
            proie.invulnerableTime = 0;
            proie.hurt(joueur.damageSources().playerAttack(joueur), MACHE);
            joueur.serverLevel().sendParticles(violet(1.2F), proie.getX(), proie.getY(0.6), proie.getZ(), 8, 0.4, 0.4, 0.4, 0.05);
        }
    }

    // ================================================================ accroupi : le bond, puis il vole

    private static void voler(ServerPlayer joueur, Etat e) {
        int maintenant = joueur.server.getTickCount();
        if (e.volFin >= 0) {
            // déjà en vol (deux appuis rapides sur s'accroupir) : il retombe, sauf s'il vient juste de bondir
            if (maintenant > e.volFin - DUREE_VOL + 10) {
                finVol(joueur, e, maintenant);
            }
            return;
        }
        if (maintenant < e.volRecharge) {
            joueur.displayClientMessage(Component.literal("Les serpents reprennent des forces… " + (e.volRecharge - maintenant + 19) / 20 + " s")
                    .withStyle(ChatFormatting.GRAY), true);
            return;
        }
        e.volFin = maintenant + DUREE_VOL;
        Abilities a = joueur.getAbilities();
        if (!joueur.isCreative() && !joueur.isSpectator()) {
            a.mayfly = true;
            a.setFlyingSpeed(VITESSE_VOL);
        }
        a.flying = true;
        joueur.onUpdateAbilities();
        // le grand bond : les serpents frappent le sol et le lancent en l'air
        joueur.setDeltaMovement(joueur.getDeltaMovement().x, 1.3, joueur.getDeltaMovement().z);
        joueur.hurtMarked = true;
        joueur.fallDistance = 0;
        geste(joueur, GESTE_VOL, 1, List.of());
        ServerLevel monde = joueur.serverLevel();
        monde.sendParticles(violet(2.4F), joueur.getX(), joueur.getY() + 0.2, joueur.getZ(), 60, 1.4, 0.3, 1.4, 0.1);
        monde.sendParticles(ParticleTypes.CLOUD, joueur.getX(), joueur.getY() + 0.1, joueur.getZ(), 30, 1.2, 0.1, 1.2, 0.15);
        son(joueur, SoundEvents.ENDER_DRAGON_FLAP, 1.4F, 0.7F);
        son(joueur, SoundEvents.CAT_HISS, 1F, 0.5F);
    }

    /** Il arrête de voler et tombe (la marque, dès le niveau 2, lui évite les dégâts de chute). */
    private static void finVol(ServerPlayer joueur, Etat e, int maintenant) {
        e.volFin = -1;
        e.volRecharge = maintenant + RECHARGE_VOL;
        Abilities a = joueur.getAbilities();
        if (!joueur.isCreative() && !joueur.isSpectator()) {
            a.mayfly = false;
            a.flying = false;
            a.setFlyingSpeed(0.05F);
            joueur.onUpdateAbilities();
        }
        joueur.fallDistance = 0;
        geste(joueur, GESTE_VOL, 0, List.of());
    }

    // ================================================================ J : l'invocation du grand serpent

    /** La touche d'ulti d'un marqué niveau 3 (routée par {@link Hachibi4}). */
    public static void ulti(ServerPlayer joueur) {
        Etat e = ETATS.get(joueur.getUUID());
        if (!pret(joueur, e) || e.ultiDebut >= 0) {
            return;
        }
        long maintenant = joueur.serverLevel().getGameTime();
        if (e.ultiFait) {
            joueur.displayClientMessage(Component.literal("Le grand serpent ne vient qu'une fois par forme.").withStyle(ChatFormatting.GRAY), true);
            return;
        }
        long pretA = PRET_ULTI.getOrDefault(joueur.getUUID(), 0L);
        if (maintenant < pretA) {
            joueur.displayClientMessage(Component.literal("Le grand serpent reprend des forces… " + (pretA - maintenant + 19) / 20 + " s")
                    .withStyle(ChatFormatting.GRAY), true);
            return;
        }
        lacher(joueur, e);
        if (e.volFin >= 0) {
            finVol(joueur, e, joueur.server.getTickCount());
        }
        ServerLevel monde = joueur.serverLevel();
        Vec3 origine = joueur.position();
        float lacet = joueur.getYRot();
        // la hauteur du sol sous chaque point du chemin (le serpent suit le relief)
        List<Float> sols = new ArrayList<>();
        for (int i = 0; i < SerpentGeant.nombrePoints(); i++) {
            Vec3 p = SerpentGeant.pointMonde(origine, lacet, i);
            int x = Mth.floor(p.x), z = Mth.floor(p.z);
            float h = 0;
            if (monde.hasChunkAt(new net.minecraft.core.BlockPos(x, 0, z))) {
                h = (float) Mth.clamp(monde.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - origine.y, -8, 8);
            }
            sols.add(h);
        }
        e.ultiDebut = maintenant;
        e.ultiAvant = -1;
        e.trajet = new SerpentGeant.Trajet(origine, lacet, sols);
        e.touches.clear();
        e.ultiFait = true;
        PacketDistributor.sendToPlayersNear(monde, null, origine.x, origine.y, origine.z, VU_DE,
                new Ulti3(joueur.getUUID(), maintenant, v(origine), lacet, sols));
    }

    private static boolean passe(double avant, double maintenant, double t) {
        return avant < t && maintenant >= t;
    }

    private static void sonA(ServerLevel monde, Vec3 p, SoundEvent son, float volume, float hauteur) {
        monde.playSound(null, p.x, p.y, p.z, son, SoundSource.PLAYERS, volume, hauteur);
    }

    /** L'ulti, à chaque tick : les sons, le vol sur la tête, les dégâts de la charge et de l'impact. */
    private static void tickUlti(ServerPlayer joueur, Etat e) {
        ServerLevel monde = joueur.serverLevel();
        double t = (monde.getGameTime() - e.ultiDebut) / 20.0, avant = e.ultiAvant;
        e.ultiAvant = t;
        SerpentGeant.Trajet tr = e.trajet;
        Vec3 o = tr.origine;
        double[] K = new double[15];
        tr.tete(Math.min(t, SerpentGeant.T_DISPARAIT), K);
        Vec3 tete = o.add(K[0] + K[3] * 8, K[1], K[2] + K[5] * 8);
        if (passe(avant, t, SerpentGeant.T_SAUT)) {
            joueur.setDeltaMovement(0, 0.5, 0);
            joueur.hurtMarked = true;
            son(joueur, SoundEvents.PHANTOM_FLAP, 1.2F, 0.7F);
        }
        if (passe(avant, t, SerpentGeant.T_POSE)) {
            son(joueur, SoundEvents.ANVIL_LAND, 0.7F, 0.5F);
            son(joueur, SoundEvents.EVOKER_PREPARE_SUMMON, 2F, 0.7F);
            monde.sendParticles(violet(2F), o.x, o.y + 0.2, o.z, 50, 2, 0.1, 2, 0.05);
        }
        if (passe(avant, t, SerpentGeant.T_POUF)) {
            Vec3 pouf = o.add(tr.mx(0, -6), 10, tr.mz(0, -6));
            sonA(monde, pouf, SoundEvents.GENERIC_EXPLODE.value(), 5F, 0.55F);
            sonA(monde, pouf, SoundEvents.EVOKER_CAST_SPELL, 5F, 0.5F);
            sonA(monde, pouf, SoundEvents.ILLUSIONER_MIRROR_MOVE, 5F, 0.6F);
            Abilities a = joueur.getAbilities();
            if (!joueur.isCreative() && !joueur.isSpectator()) {
                a.mayfly = true;
            }
            a.flying = true;
            joueur.onUpdateAbilities();
        }
        if (passe(avant, t, SerpentGeant.T_SIFFLE)) {
            sonA(monde, tete, SoundEvents.CAT_HISS, 5F, 0.4F);
            sonA(monde, tete, SoundEvents.ENDER_DRAGON_GROWL, 3F, 1.3F);
        }
        if (passe(avant, t, SerpentGeant.T_ELAN + 0.3)) {
            sonA(monde, tete, SoundEvents.CAT_HISS, 5F, 0.35F);
        }
        if (passe(avant, t, SerpentGeant.T_CHARGE)) {
            sonA(monde, tete, SoundEvents.RAVAGER_ROAR, 6F, 0.5F);
            sonA(monde, tete, SoundEvents.ENDER_DRAGON_GROWL, 5F, 0.7F);
        }
        // sur la tête : pas de chute, il vole (sa position, c'est son jeu qui la tient, sur le serpent)
        if (t >= SerpentGeant.T_POUF && t < SerpentGeant.T_FIN) {
            joueur.fallDistance = 0;
            if (t < SerpentGeant.T_DISPARAIT && !joueur.getAbilities().flying) {
                joueur.getAbilities().flying = true;
                joueur.onUpdateAbilities();
            }
        }
        // la charge : la tête envoie en l'air, le souffle repousse sur les côtés
        if (t > SerpentGeant.T_CHARGE && t < SerpentGeant.T_IMPACT + 0.05) {
            if (monde.getGameTime() % 5 == 0) {
                sonA(monde, tete, SoundEvents.GENERIC_EXPLODE.value(), 2.5F, 0.45F);
                sonA(monde, tete, SoundEvents.ROOTED_DIRT_BREAK, 4F, 0.5F);
            }
            Vec3 cou = o.add(K[0], K[1], K[2]);
            for (LivingEntity c : monde.getEntitiesOfClass(LivingEntity.class, new net.minecraft.world.phys.AABB(tete, tete).inflate(18, 12, 18),
                    c -> c != joueur && Pouvoirs.cible(joueur, c) && !c.isSpectator() && !e.touches.contains(c.getId()))) {
                Vec3 d = c.position().add(0, c.getBbHeight() / 2, 0).subtract(cou);
                double qx = d.x * K[3] + d.y * K[4] + d.z * K[5], qy = d.x * K[6] + d.y * K[7] + d.z * K[8], qz = d.x * K[9] + d.y * K[10] + d.z * K[11];
                if (qx < -3 || qx > SerpentGeant.LH + 1.5 || Math.abs(qy) > 9) {
                    continue;
                }
                double w = SerpentGeant.largeur(Mth.clamp(qx, 0, SerpentGeant.LH)), cote = Math.signum(qz) == 0 ? 1 : Math.signum(qz);
                if (Math.abs(qz) < w + 1.5) {
                    frapper(joueur, c, ULTI_TETE, new Vec3(K[9] * cote * 0.4 + K[3] * K[14] / 20 * 0.5, 1.3, K[11] * cote * 0.4 + K[5] * K[14] / 20 * 0.5));
                    e.touches.add(c.getId());
                } else if (Math.abs(qz) < w + 6) {
                    frapper(joueur, c, ULTI_SOUFFLE, new Vec3(K[9] * cote * 1.1, 0.55, K[11] * cote * 1.1));
                    e.touches.add(c.getId());
                }
            }
        }
        if (passe(avant, t, SerpentGeant.T_IMPACT)) {
            double[] b = SerpentGeant.Trajet.dansTete(K, SerpentGeant.LH, 0, 0, false);
            Vec3 bout = o.add(b[0], b[1], b[2]);
            sonA(monde, bout, SoundEvents.GENERIC_EXPLODE.value(), 6F, 0.6F);
            sonA(monde, bout, SoundEvents.DRAGON_FIREBALL_EXPLODE, 4F, 0.6F);
            for (LivingEntity c : monde.getEntitiesOfClass(LivingEntity.class, new net.minecraft.world.phys.AABB(bout, bout).inflate(RAYON_IMPACT, 8, RAYON_IMPACT),
                    c -> c != joueur && Pouvoirs.cible(joueur, c) && !c.isSpectator())) {
                Vec3 d = c.position().subtract(bout);
                Vec3 plat = new Vec3(d.x, 0, d.z);
                if (plat.length() > RAYON_IMPACT) {
                    continue;
                }
                Vec3 n = plat.lengthSqr() < 1e-4 ? new Vec3(K[3], 0, K[5]) : plat.normalize();
                frapper(joueur, c, ULTI_IMPACT, n.scale(0.8).add(0, 0.9, 0));
            }
        }
        if (passe(avant, t, SerpentGeant.T_DISPARAIT)) {
            sonA(monde, tete, SoundEvents.ILLUSIONER_CAST_SPELL, 5F, 0.5F);
            sonA(monde, tete, SoundEvents.FIRE_EXTINGUISH, 4F, 0.5F);
            Abilities a = joueur.getAbilities();
            if (!joueur.isCreative() && !joueur.isSpectator()) {
                a.mayfly = false;
                a.flying = false;
                joueur.onUpdateAbilities();
            }
        }
        if (t >= SerpentGeant.T_FIN) {
            finUlti(joueur, e, false);
        }
    }

    private static void frapper(ServerPlayer joueur, LivingEntity c, float degats, Vec3 elan) {
        c.invulnerableTime = 0;
        c.hurt(joueur.damageSources().playerAttack(joueur), degats);
        c.setDeltaMovement(elan);
        c.hurtMarked = true;
        joueur.serverLevel().sendParticles(ParticleTypes.DAMAGE_INDICATOR, c.getX(), c.getY(0.6), c.getZ(), 6, 0.3, 0.3, 0.3, 0.1);
    }

    /** Fin de l'ulti (ou arrêt en cours de route : le serpent disparaît chez tout le monde). */
    private static void finUlti(ServerPlayer joueur, Etat e, boolean arret) {
        if (arret) {
            Vec3 o = e.trajet.origine;
            PacketDistributor.sendToPlayersNear(joueur.serverLevel(), null, o.x, o.y, o.z, VU_DE,
                    new Ulti3(joueur.getUUID(), -1, v(o), 0, List.of()));
            Abilities a = joueur.getAbilities();
            if (!joueur.isCreative() && !joueur.isSpectator() && a.mayfly && e.volFin < 0) {
                a.mayfly = false;
                a.flying = false;
                joueur.onUpdateAbilities();
            }
        }
        joueur.fallDistance = 0;
        e.ultiDebut = -1;
        e.trajet = null;
        e.touches.clear();
        PRET_ULTI.put(joueur.getUUID(), joueur.serverLevel().getGameTime() + RECHARGE_ULTI);
    }

    // ================================================================ chaque tick

    @SubscribeEvent
    public static void tick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        int maintenant = server.getTickCount();
        for (Map.Entry<UUID, Etat> entree : new ArrayList<>(ETATS.entrySet())) {
            ServerPlayer joueur = server.getPlayerList().getPlayer(entree.getKey());
            Etat e = entree.getValue();
            if (joueur == null) {
                ETATS.remove(entree.getKey());
                continue;
            }
            if (e.morsure >= 0 && maintenant >= e.morsure) {
                mordre(joueur, e);
            }
            if (e.prise >= 0 && e.lacher >= 0 && maintenant >= e.lacher) {
                lacher(joueur, e);
            }
            if (e.prise >= 0) {
                tenir(joueur, e, maintenant);
            }
            if (e.ultiDebut >= 0) {
                tickUlti(joueur, e);
            }
            if (e.volFin >= 0) {
                if (maintenant >= e.volFin) {
                    finVol(joueur, e, maintenant);
                } else {
                    if (!joueur.getAbilities().flying) {
                        joueur.getAbilities().flying = true;
                        joueur.onUpdateAbilities();
                    }
                    joueur.fallDistance = 0;
                }
            }
        }
    }

    @SubscribeEvent
    public static void deconnexion(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer joueur) {
            endormir(joueur);
        }
    }

    /** Le serveur s'arrête : ceux qui volent ne doivent pas garder le vol à la reconnexion. */
    @SubscribeEvent
    public static void arret(ServerStoppingEvent event) {
        for (UUID uuid : new ArrayList<>(ETATS.keySet())) {
            ServerPlayer joueur = event.getServer().getPlayerList().getPlayer(uuid);
            if (joueur != null) {
                endormir(joueur);
            }
        }
        ETATS.clear();
    }

    private Marque3() {
    }
}
