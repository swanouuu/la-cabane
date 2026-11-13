package fr.cabane.pouvoir;

import static fr.cabane.pouvoir.ClairObscur.*;

import fr.cabane.Cabane;
import fr.cabane.client.pouvoir.ClientTenebres;
import io.netty.buffer.ByteBuf;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import org.joml.Vector3f;

/**
 * Le pouvoir des Ténèbres, d'après les planches validées (docs/tenebres-lumiere). Trois listes : « tenebres »,
 * « tenebres2 », « tenebres3 ». Toujours : plus vite, il voit dans le noir, protégé comme les autres pouvoirs de son niveau.
 * <ul>
 * <li>niveau 1 : la flaque noire monte sur lui (veines, yeux violets). Clic gauche : +2, Obscurité 2 s. Clic droit : la
 * lame d'ombre (6, 20 blocs, 6 s). Accroupi : il plonge dans son ombre et ressort 15 blocs plus loin (12 s) ;</li>
 * <li>niveau 2 : son ombre se relève (un double noir qui le suit). Clic gauche : +4, l'ombre frappe aussi (+2),
 * Obscurité 3 s. Clic droit : les mains d'ombre (la cible tenue 2 s, 10, 15 s). Accroupi : la plongée va à 30 blocs et
 * explose à la sortie (8 sur 4 blocs). Accroupi + clic droit : la prison d'ombre (enfermé 3 s, 8, 20 s) ;</li>
 * <li>niveau 3 : la tenue (le skin de l'utilisateur, adapté), la couronne d'obsidienne, deux orbes. Clic gauche : +6,
 * vole 1 cœur. Clic droit : les lances d'ombre (une ligne de piques, 14, en l'air, 12 s). Accroupi : 3 ombres surgissent
 * autour de la cible (3 × 5, 15 s). Accroupi + clic droit : la prison. Accroupi + clic gauche : les chaînes (3 ennemis
 * tirés à lui, 12, 20 s). Technique : la faux (16 tout autour, 8 blocs, 20 s). Ulti : l'Éclipse (voir {@link #ulti}).</li>
 * </ul>
 * Rien ne casse. Ce qu'on voit est dessiné par {@link ClientTenebres}.
 */
@EventBusSubscriber(modid = Cabane.MODID)
public final class Tenebres {
    public static final String NOM = "tenebres", NOM2 = "tenebres2", NOM3 = "tenebres3";

    public static boolean est(String pouvoir) {
        return NOM.equals(pouvoir) || NOM2.equals(pouvoir) || NOM3.equals(pouvoir);
    }

    // ================================================================ les chiffres (en ticks)

    public static final int RECHARGE_LAME = 6 * 20, RECHARGE_MAINS = 15 * 20, RECHARGE_PIQUES = 12 * 20, RECHARGE_PLONGEE = 12 * 20,
            RECHARGE_OMBRES = 15 * 20, RECHARGE_PRISON = 20 * 20, RECHARGE_CHAINES = 20 * 20, RECHARGE_FAUX = 20 * 20;
    public static final int DUREE_MAINS = 40, DUREE_PRISON = 60, PLONGE = 8;
    private static final float COUP_1 = 2, COUP_2 = 4, COUP_OMBRE = 2, COUP_3 = 6, LAME = 6, MAINS = 10, PIQUES = 14, EXPLOSION = 8,
            OMBRE = 5, PRISON = 8, CHAINES = 12, FAUX = 16, IMPLOSION = 22;
    public static final double PORTEE_LAME = 20, VITESSE_LAME = 0.9, PORTEE_PIQUES = 20, RAYON_FAUX = 8, PORTEE_ULTI = 40, RAYON_ULTI = 15;
    /** L'ulti : l'éclipse (0), le géant fait naître le trou noir (30), il le lance (70), l'implosion (130), la fin (160). */
    public static final int U_GEANT = 30, U_LANCE = 70, U_IMPLOSION = 130, U_FIN = 160;

    // ================================================================ qui est éveillé

    private static final Map<UUID, Integer> EVEILLES = new HashMap<>();
    private static final Recharges R = new Recharges();
    /** Ceux qui ont déjà lancé leur ulti pendant cet éveil. */
    private static final Set<UUID> ULTI_FAITE = new HashSet<>();

    static boolean eveille(ServerPlayer joueur) {
        return EVEILLES.containsKey(joueur.getUUID()) && est(Pouvoirs.pouvoir(joueur));
    }

    public static int niveau(ServerPlayer joueur) {
        return eveille(joueur) ? EVEILLES.get(joueur.getUUID()) : 0;
    }

    static void eveiller(ServerPlayer joueur, int niveau) {
        EVEILLES.put(joueur.getUUID(), niveau);
        ServerLevel monde = joueur.serverLevel();
        Vec3 p = joueur.position();
        son(monde, p, SoundEvents.SCULK_SHRIEKER_SHRIEK, 0.6F, niveau >= 3 ? 0.5F : 0.8F);
        son(monde, p, SoundEvents.WARDEN_HEARTBEAT, 1.4F, 0.7F);
        if (niveau >= 3) {
            son(monde, p, SoundEvents.WITHER_SPAWN, 0.5F, 1.6F);
            ULTI_FAITE.remove(joueur.getUUID());
        }
        passifs(joueur, niveau);
    }

    static void endormir(ServerPlayer joueur) {
        Integer etait = EVEILLES.remove(joueur.getUUID());
        R.oublier(joueur.getUUID());
        ULTIS.remove(joueur.getUUID());
        PLONGEES.remove(joueur.getUUID());
        if (etait != null) {
            joueur.removeEffect(MobEffects.NIGHT_VISION);
            joueur.removeEffect(MobEffects.MOVEMENT_SPEED);
            joueur.setInvisible(false);
            if (joueur.isAlive()) {
                joueur.serverLevel().sendParticles(ParticleTypes.LARGE_SMOKE, joueur.getX(), joueur.getY() + 1, joueur.getZ(), 30, 0.4, 0.6, 0.4, 0.02);
            }
        }
    }

    /** Plus vite (+20 %, puis +40 %), et il voit dans le noir. */
    private static void passifs(ServerPlayer joueur, int niveau) {
        if (niveau <= 0) {
            return;
        }
        joueur.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SPEED, 50, niveau >= 2 ? 1 : 0, false, false, true));
        joueur.addEffect(new MobEffectInstance(MobEffects.NIGHT_VISION, 260, 0, false, false, false));
    }

    public static boolean ultiActif(ServerPlayer joueur) {
        return ULTIS.containsKey(joueur.getUUID());
    }

    // ================================================================ réseau

    public record Touche(int action) implements CustomPacketPayload {
        public static final Type<Touche> TYPE = new Type<>(Cabane.id("tenebres_touche"));
        public static final StreamCodec<ByteBuf, Touche> CODEC = StreamCodec.composite(ByteBufCodecs.VAR_INT, Touche::action, Touche::new);

        @Override
        public Type<Touche> type() {
            return TYPE;
        }
    }

    /** Ce que montre un {@link Effet}. */
    public static final int E_COUP = 0, E_LAME = 1, E_LAME_FIN = 2, E_PLONGE = 3, E_MAINS = 4, E_PIQUES = 5, E_OMBRES = 6, E_PRISON = 7,
            E_CHAINES = 8, E_FAUX = 9, E_ULTI = 10, E_VOL = 11;

    /** Serveur → ceux qui sont autour : un coup d'ombre. {@code pos}, {@code dir} : où et vers où ; {@code n} : un nombre (l'entité visée…). */
    public record Effet(UUID joueur, int sorte, int phase, Vector3f pos, Vector3f dir, int n) implements CustomPacketPayload {
        public static final Type<Effet> TYPE = new Type<>(Cabane.id("tenebres_effet"));
        public static final StreamCodec<ByteBuf, Effet> CODEC = StreamCodec.composite(
                UUIDUtil.STREAM_CODEC, Effet::joueur, ByteBufCodecs.VAR_INT, Effet::sorte, ByteBufCodecs.VAR_INT, Effet::phase,
                ByteBufCodecs.VECTOR3F, Effet::pos, ByteBufCodecs.VECTOR3F, Effet::dir, ByteBufCodecs.VAR_INT, Effet::n, Effet::new);

        @Override
        public Type<Effet> type() {
            return TYPE;
        }
    }

    @SubscribeEvent
    public static void reseau(RegisterPayloadHandlersEvent event) {
        event.registrar("1")
                .playToServer(Touche.TYPE, Touche.CODEC, (p, ctx) -> ctx.enqueueWork(() -> {
                    if (ctx.player() instanceof ServerPlayer joueur) {
                        touche(joueur, p.action());
                    }
                }))
                .playToClient(Effet.TYPE, Effet.CODEC, (p, ctx) -> ctx.enqueueWork(() -> ClientTenebres.effet(p)));
    }

    static void effet(ServerPlayer joueur, int sorte, int phase, Vec3 pos, Vec3 dir, int n) {
        envoyer(joueur.serverLevel(), new Effet(joueur.getUUID(), sorte, phase, v(pos), v(dir), n), pos);
    }

    private static final DustParticleOptions VIOLET = new DustParticleOptions(new Vector3f(0.6F, 0.25F, 1F), 1.4F);
    private static final DustParticleOptions NOIRE = new DustParticleOptions(new Vector3f(0.05F, 0.02F, 0.08F), 2F);

    private static void fumee(ServerLevel monde, Vec3 p, int n, double r) {
        monde.sendParticles(ParticleTypes.LARGE_SMOKE, p.x, p.y, p.z, n, r, r * 0.6, r, 0.02);
        monde.sendParticles(NOIRE, p.x, p.y, p.z, n, r, r * 0.6, r, 0.02);
        monde.sendParticles(VIOLET, p.x, p.y, p.z, n / 2, r, r * 0.6, r, 0.02);
    }

    // ================================================================ les touches

    private static void touche(ServerPlayer joueur, int action) {
        int niveau = niveau(joueur);
        if (niveau == 0 || ultiActif(joueur) || PLONGEES.containsKey(joueur.getUUID())) {
            return;
        }
        switch (action) {
            case DROIT -> {
                if (niveau >= 3) {
                    piques(joueur);
                } else if (niveau == 2) {
                    mains(joueur);
                } else {
                    lame(joueur);
                }
            }
            case ACCROUPI -> {
                if (niveau >= 3) {
                    troisOmbres(joueur);
                } else {
                    plonger(joueur, niveau >= 2);
                }
            }
            case ACC_DROIT -> {
                if (niveau >= 2) {
                    prison(joueur);
                }
            }
            case ACC_GAUCHE -> {
                if (niveau >= 3) {
                    chaines(joueur);
                }
            }
            case TECHNIQUE -> {
                if (niveau >= 3) {
                    faux(joueur);
                }
            }
            case ULTI -> {
                if (niveau >= 3) {
                    lancerUlti(joueur);
                }
            }
            default -> { }
        }
    }

    // ---------------------------------------------------------------- niveau 1 : la lame d'ombre

    private record Lame(ServerPlayer tireur, ServerLevel monde, int id, Vec3[] pos, Vec3 dir, double[] parcouru) {
    }

    private static final List<Lame> LAMES = new ArrayList<>();
    private static int prochaine;

    private static void lame(ServerPlayer joueur) {
        if (!pret(R.droit, joueur)) {
            return;
        }
        armer(R.droit, joueur, RECHARGE_LAME);
        Vec3 dep = joueur.getEyePosition().add(0, -0.4, 0).add(joueur.getLookAngle().scale(0.8));
        Vec3 dir = joueur.getLookAngle();
        int id = ++prochaine;
        LAMES.add(new Lame(joueur, joueur.serverLevel(), id, new Vec3[]{dep}, dir, new double[]{0}));
        effet(joueur, E_LAME, 0, dep, dir, id);
        son(joueur.serverLevel(), dep, SoundEvents.PLAYER_ATTACK_SWEEP, 1.4F, 0.5F);
        son(joueur.serverLevel(), dep, SoundEvents.PHANTOM_FLAP, 1.2F, 0.6F);
    }

    private static void lames() {
        LAMES.removeIf(b -> {
            if (b.tireur().isRemoved()) {
                return true;
            }
            Vec3 de = b.pos()[0];
            Vec3 a = de.add(b.dir().scale(VITESSE_LAME));
            HitResult bloc = b.monde().clip(new ClipContext(de, a, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, b.tireur()));
            if (bloc.getType() != HitResult.Type.MISS) {
                a = bloc.getLocation();
            }
            LivingEntity touche = null;
            for (LivingEntity e : b.monde().getEntitiesOfClass(LivingEntity.class, new AABB(de, a).inflate(1.1), e -> touchable(b.tireur(), e))) {
                if (e.getBoundingBox().inflate(0.8).clip(de, a).isPresent()) {
                    touche = e;
                    break;
                }
            }
            b.parcouru()[0] += de.distanceTo(a);
            b.pos()[0] = a;
            if (touche != null) {
                blesser(b.tireur(), touche, LAME);
                touche.addEffect(new MobEffectInstance(MobEffects.DARKNESS, 60, 0));
                fumee(b.monde(), milieu(touche), 16, 0.4);
                son(b.monde(), a, SoundEvents.PLAYER_ATTACK_STRONG, 1.2F, 0.6F);
                envoyer(b.monde(), new Effet(b.tireur().getUUID(), E_LAME_FIN, 0, v(milieu(touche)), v(b.dir()), b.id()), a);
                return true;
            }
            if (bloc.getType() != HitResult.Type.MISS || b.parcouru()[0] >= PORTEE_LAME) {
                fumee(b.monde(), a, 8, 0.3);
                envoyer(b.monde(), new Effet(b.tireur().getUUID(), E_LAME_FIN, 0, v(a), v(b.dir()), b.id()), a);
                return true;
            }
            return false;
        });
    }

    // ---------------------------------------------------------------- niveaux 1 et 2 : la plongée dans l'ombre

    /** Qui plonge : le tick de la sortie, où il ressort, et s'il explose (niveau 2). */
    private record Plongee(int sortie, Vec3 arrivee, boolean explose) {
    }

    private static final Map<UUID, Plongee> PLONGEES = new HashMap<>();

    private static void plonger(ServerPlayer joueur, boolean loin) {
        if (!pret(R.accroupi, joueur)) {
            return;
        }
        armer(R.accroupi, joueur, RECHARGE_PLONGEE);
        Vec3 arrivee = arrivee(joueur, joueur.getLookAngle(), loin ? 30 : 15);
        PLONGEES.put(joueur.getUUID(), new Plongee(joueur.server.getTickCount() + PLONGE, arrivee, loin));
        joueur.setInvisible(true);
        effet(joueur, E_PLONGE, 0, joueur.position(), arrivee, loin ? 1 : 0);
        fumee(joueur.serverLevel(), joueur.position().add(0, 0.5, 0), 30, 0.6);
        son(joueur.serverLevel(), joueur.position(), SoundEvents.SCULK_BLOCK_SPREAD, 1.6F, 0.5F);
        son(joueur.serverLevel(), joueur.position(), SoundEvents.ENDERMAN_TELEPORT, 0.8F, 0.4F);
    }

    private static void plongees(MinecraftServer server, int maintenant) {
        for (Map.Entry<UUID, Plongee> en : new ArrayList<>(PLONGEES.entrySet())) {
            ServerPlayer joueur = server.getPlayerList().getPlayer(en.getKey());
            Plongee p = en.getValue();
            if (joueur == null) {
                PLONGEES.remove(en.getKey());
                continue;
            }
            joueur.setDeltaMovement(Vec3.ZERO);
            joueur.hurtMarked = true;
            if (maintenant < p.sortie()) {
                continue;
            }
            PLONGEES.remove(en.getKey());
            joueur.setInvisible(false);
            joueur.teleportTo(p.arrivee().x, p.arrivee().y, p.arrivee().z);
            joueur.fallDistance = 0;
            ServerLevel monde = joueur.serverLevel();
            effet(joueur, E_PLONGE, 1, p.arrivee(), Vec3.ZERO, p.explose() ? 1 : 0);
            fumee(monde, p.arrivee().add(0, 0.6, 0), 30, 0.6);
            son(monde, p.arrivee(), SoundEvents.ENDERMAN_TELEPORT, 1F, 0.5F);
            if (p.explose()) {
                son(monde, p.arrivee(), SoundEvents.GENERIC_EXPLODE.value(), 1.2F, 0.6F);
                fumee(monde, p.arrivee().add(0, 1, 0), 80, 2.2);
                for (LivingEntity e : monde.getEntitiesOfClass(LivingEntity.class, joueur.getBoundingBox().inflate(4, 2, 4), e -> touchable(joueur, e))) {
                    Vec3 loin = e.position().subtract(p.arrivee());
                    if (loin.horizontalDistance() > 4) {
                        continue;
                    }
                    blesser(joueur, e, EXPLOSION);
                    e.knockback(1.4, -loin.x, -loin.z);
                    e.setDeltaMovement(e.getDeltaMovement().add(0, 0.5, 0));
                    e.addEffect(new MobEffectInstance(MobEffects.DARKNESS, 60, 0));
                }
            }
        }
    }

    // ---------------------------------------------------------------- niveau 2 : les mains d'ombre

    /** Qui est tenu : la cible, où, jusqu'à quand (la même chose sert à la prison et aux chaînes). */
    private record Tenu(ServerPlayer joueur, ServerLevel monde, int cible, Vec3 ou, int fin, float degatsFin, int sorte) {
    }

    private static final List<Tenu> TENUS = new ArrayList<>();

    private static void mains(ServerPlayer joueur) {
        if (!pret(R.droit, joueur)) {
            return;
        }
        LivingEntity c = visee(joueur, 20, 0.9);
        if (c == null) {
            joueur.displayClientMessage(net.minecraft.network.chat.Component.literal("Vise quelqu'un (20 blocs)."), true);
            return;
        }
        armer(R.droit, joueur, RECHARGE_MAINS);
        int maintenant = joueur.server.getTickCount();
        Vec3 ou = new Vec3(c.getX(), sol(joueur.serverLevel(), c.position()), c.getZ());
        TENUS.add(new Tenu(joueur, joueur.serverLevel(), c.getId(), ou, maintenant + DUREE_MAINS, MAINS / 2, E_MAINS));
        blesser(joueur, c, MAINS / 2);
        c.addEffect(new MobEffectInstance(MobEffects.DARKNESS, DUREE_MAINS + 20, 0));
        effet(joueur, E_MAINS, 0, ou, Vec3.ZERO, c.getId());
        son(joueur.serverLevel(), ou, SoundEvents.SCULK_SHRIEKER_SHRIEK, 0.8F, 0.6F);
        son(joueur.serverLevel(), ou, SoundEvents.WARDEN_DIG, 1F, 1.2F);
    }

    /** Accroupi + clic droit (niveaux 2 et 3) : une cage de barreaux noirs sort de la flaque autour de la cible. */
    private static void prison(ServerPlayer joueur) {
        if (!pret(R.accDroit, joueur)) {
            return;
        }
        LivingEntity c = visee(joueur, 20, 0.9);
        if (c == null) {
            joueur.displayClientMessage(net.minecraft.network.chat.Component.literal("Vise quelqu'un (20 blocs)."), true);
            return;
        }
        armer(R.accDroit, joueur, RECHARGE_PRISON);
        int maintenant = joueur.server.getTickCount();
        Vec3 ou = new Vec3(c.getX(), sol(joueur.serverLevel(), c.position()), c.getZ());
        TENUS.add(new Tenu(joueur, joueur.serverLevel(), c.getId(), ou, maintenant + DUREE_PRISON, PRISON, E_PRISON));
        c.addEffect(new MobEffectInstance(MobEffects.DARKNESS, DUREE_PRISON + 20, 0));
        effet(joueur, E_PRISON, 0, ou, Vec3.ZERO, c.getId());
        son(joueur.serverLevel(), ou, SoundEvents.IRON_DOOR_CLOSE, 1.4F, 0.4F);
        son(joueur.serverLevel(), ou, SoundEvents.CHAIN_PLACE, 1.4F, 0.5F);
    }

    private static void tenus(int maintenant) {
        TENUS.removeIf(t -> {
            if (!(t.monde().getEntity(t.cible()) instanceof LivingEntity c) || !c.isAlive() || t.joueur().isRemoved()) {
                return true;
            }
            if (maintenant >= t.fin()) {
                blesser(t.joueur(), c, t.degatsFin());
                fumee(t.monde(), milieu(c), 24, 0.5);
                son(t.monde(), c.position(), SoundEvents.PLAYER_ATTACK_STRONG, 1.2F, 0.5F);
                effet(t.joueur(), t.sorte(), 1, t.ou(), Vec3.ZERO, t.cible());
                return true;
            }
            // il ne bouge plus : on le remet au milieu
            if (c.position().distanceToSqr(t.ou()) > 0.09) {
                c.teleportTo(t.ou().x, Math.max(t.ou().y, c.getY()), t.ou().z);
            }
            c.setDeltaMovement(0, Math.min(0, c.getDeltaMovement().y), 0);
            c.hurtMarked = true;
            return false;
        });
    }

    // ---------------------------------------------------------------- niveau 3 : les lances d'ombre (une ligne de piques)

    private record Ligne(ServerPlayer joueur, ServerLevel monde, Vec3 depart, Vec3 dir, int debut, Set<Integer> touches) {
    }

    private static final List<Ligne> LIGNES = new ArrayList<>();

    private static void piques(ServerPlayer joueur) {
        if (!pret(R.droit, joueur)) {
            return;
        }
        armer(R.droit, joueur, RECHARGE_PIQUES);
        LivingEntity cible = visee(joueur, PORTEE_PIQUES + 2, 0.85);
        Vec3 dir = cible != null ? cible.position().subtract(joueur.position()) : joueur.getLookAngle();
        dir = new Vec3(dir.x, 0, dir.z);
        if (dir.lengthSqr() < 1e-4) {
            dir = Pouvoirs.devant(joueur);
        }
        dir = dir.normalize();
        Vec3 dep = joueur.position().add(dir.scale(1.2));
        LIGNES.add(new Ligne(joueur, joueur.serverLevel(), dep, dir, joueur.server.getTickCount(), new HashSet<>()));
        effet(joueur, E_PIQUES, 0, dep, dir, (int) PORTEE_PIQUES);
        son(joueur.serverLevel(), dep, SoundEvents.SCULK_SHRIEKER_SHRIEK, 1F, 0.5F);
    }

    private static void lignes(int maintenant) {
        LIGNES.removeIf(l -> {
            int age = maintenant - l.debut();
            for (int k = Math.max(0, age * 2 - 1); k <= age * 2 && k <= PORTEE_PIQUES; k++) {
                Vec3 p = l.depart().add(l.dir().scale(k));
                double y = sol(l.monde(), p);
                p = new Vec3(p.x, y, p.z);
                for (LivingEntity e : l.monde().getEntitiesOfClass(LivingEntity.class, new AABB(p.x - 1.4, y - 1, p.z - 1.4, p.x + 1.4, y + 3, p.z + 1.4),
                        e -> touchable(l.joueur(), e))) {
                    if (l.touches().add(e.getId())) {
                        blesser(l.joueur(), e, PIQUES);
                        e.setDeltaMovement(e.getDeltaMovement().add(l.dir().scale(0.3)).add(0, 1.1, 0));
                        e.hurtMarked = true;
                        e.addEffect(new MobEffectInstance(MobEffects.DARKNESS, 60, 0));
                    }
                }
                if (k % 2 == 0) {
                    son(l.monde(), p, SoundEvents.POINTED_DRIPSTONE_LAND, 0.9F, 0.5F + k * 0.03F);
                }
            }
            return age * 2 >= PORTEE_PIQUES;
        });
    }

    // ---------------------------------------------------------------- niveau 3 : trois ombres surgissent autour de la cible

    private record Coup(ServerPlayer joueur, ServerLevel monde, int cible, int quand, float degats) {
    }

    private static final List<Coup> COUPS = new ArrayList<>();

    private static void troisOmbres(ServerPlayer joueur) {
        if (!pret(R.accroupi, joueur)) {
            return;
        }
        LivingEntity c = visee(joueur, 20, 0.9);
        if (c == null) {
            joueur.displayClientMessage(net.minecraft.network.chat.Component.literal("Vise quelqu'un (20 blocs)."), true);
            return;
        }
        armer(R.accroupi, joueur, RECHARGE_OMBRES);
        int maintenant = joueur.server.getTickCount();
        for (int i = 0; i < 3; i++) {
            COUPS.add(new Coup(joueur, joueur.serverLevel(), c.getId(), maintenant + 10 + i * 5, OMBRE));
        }
        effet(joueur, E_OMBRES, 0, c.position(), Vec3.ZERO, c.getId());
        fumee(joueur.serverLevel(), c.position().add(0, 0.5, 0), 40, 1.6);
        son(joueur.serverLevel(), c.position(), SoundEvents.SCULK_SHRIEKER_SHRIEK, 1F, 0.7F);
    }

    private static void coups(int maintenant) {
        COUPS.removeIf(k -> {
            if (maintenant < k.quand()) {
                return false;
            }
            if (k.monde().getEntity(k.cible()) instanceof LivingEntity c && c.isAlive() && !k.joueur().isRemoved()) {
                blesser(k.joueur(), c, k.degats());
                c.addEffect(new MobEffectInstance(MobEffects.DARKNESS, 60, 0));
                k.monde().sendParticles(ParticleTypes.SWEEP_ATTACK, c.getX(), c.getY(0.6), c.getZ(), 1, 0, 0, 0, 0);
                son(k.monde(), c.position(), SoundEvents.PLAYER_ATTACK_SWEEP, 1.2F, 0.6F);
            }
            return true;
        });
    }

    // ---------------------------------------------------------------- niveau 3 : les chaînes et la faux

    private record Chaine(ServerPlayer joueur, ServerLevel monde, int cible, int fin) {
    }

    private static final List<Chaine> CHAINES_L = new ArrayList<>();

    private static void chaines(ServerPlayer joueur) {
        if (!pret(R.accGauche, joueur)) {
            return;
        }
        Vec3 regard = joueur.getLookAngle();
        List<LivingEntity> l = new ArrayList<>(joueur.serverLevel().getEntitiesOfClass(LivingEntity.class, joueur.getBoundingBox().inflate(20), e -> {
            Vec3 vers = milieu(e).subtract(joueur.getEyePosition());
            return touchable(joueur, e) && vers.length() <= 20 && vers.normalize().dot(regard) > 0.55 && joueur.hasLineOfSight(e);
        }));
        if (l.isEmpty()) {
            joueur.displayClientMessage(net.minecraft.network.chat.Component.literal("Personne devant toi (20 blocs)."), true);
            return;
        }
        armer(R.accGauche, joueur, RECHARGE_CHAINES);
        l.sort(Comparator.comparingDouble(e -> e.distanceToSqr(joueur)));
        int maintenant = joueur.server.getTickCount();
        for (int i = 0; i < Math.min(3, l.size()); i++) {
            LivingEntity c = l.get(i);
            CHAINES_L.add(new Chaine(joueur, joueur.serverLevel(), c.getId(), maintenant + 20));
            blesser(joueur, c, CHAINES);
            effet(joueur, E_CHAINES, i, c.position(), Vec3.ZERO, c.getId());
        }
        son(joueur.serverLevel(), joueur.position(), SoundEvents.CHAIN_BREAK, 1.6F, 0.5F);
        son(joueur.serverLevel(), joueur.position(), SoundEvents.CHAIN_PLACE, 1.6F, 0.6F);
    }

    /** Les chaînes tirent leurs prises jusqu'à lui (en une seconde). */
    private static void chainesTick(int maintenant) {
        CHAINES_L.removeIf(ch -> {
            if (maintenant >= ch.fin() || ch.joueur().isRemoved() || !(ch.monde().getEntity(ch.cible()) instanceof LivingEntity c) || !c.isAlive()) {
                return true;
            }
            Vec3 vers = ch.joueur().position().subtract(c.position());
            if (vers.length() < 2) {
                c.setDeltaMovement(Vec3.ZERO);
            } else {
                c.setDeltaMovement(vers.normalize().scale(Math.min(1.4, vers.length() * 0.18)).add(0, 0.12, 0));
            }
            c.hurtMarked = true;
            c.fallDistance = 0;
            return false;
        });
    }

    private static void faux(ServerPlayer joueur) {
        if (!pret(R.technique, joueur)) {
            return;
        }
        armer(R.technique, joueur, RECHARGE_FAUX);
        ServerLevel monde = joueur.serverLevel();
        effet(joueur, E_FAUX, 0, joueur.position(), Pouvoirs.devant(joueur), (int) RAYON_FAUX);
        son(monde, joueur.position(), SoundEvents.PLAYER_ATTACK_SWEEP, 2F, 0.4F);
        son(monde, joueur.position(), SoundEvents.WARDEN_ATTACK_IMPACT, 1.4F, 0.6F);
        for (LivingEntity e : monde.getEntitiesOfClass(LivingEntity.class, joueur.getBoundingBox().inflate(RAYON_FAUX, 3, RAYON_FAUX), e -> touchable(joueur, e))) {
            Vec3 loin = e.position().subtract(joueur.position());
            if (loin.horizontalDistance() > RAYON_FAUX) {
                continue;
            }
            blesser(joueur, e, FAUX);
            e.knockback(1.8, -loin.x, -loin.z);
            e.setDeltaMovement(e.getDeltaMovement().add(0, 0.5, 0));
            e.addEffect(new MobEffectInstance(MobEffects.DARKNESS, 60, 0));
            fumee(monde, milieu(e), 10, 0.3);
        }
    }

    // ================================================================ l'ulti : l'Éclipse

    /**
     * L'Éclipse, une fois par éveil : le soleil s'éclipse et le ciel devient noir (chez ceux qui sont autour) ; son ombre
     * devient un géant qui fait naître un trou noir entre ses mains (1,5 s) ; il le lance là où le joueur vise (40 blocs) ;
     * le trou noir aspire tout à 15 blocs pendant 3 s ; puis il implose : 22 au centre (moins au bord), tout est projeté.
     * Les blocs « arrachés » ne sont que dessinés : rien ne casse. Il ne bouge pas tant que le géant n'a pas lancé.
     */
    private record Ulti(ServerLevel monde, int debut, Vec3[] zone) {
    }

    private static final Map<UUID, Ulti> ULTIS = new HashMap<>();

    private static void lancerUlti(ServerPlayer joueur) {
        if (ULTI_FAITE.contains(joueur.getUUID())) {
            joueur.displayClientMessage(net.minecraft.network.chat.Component.literal("L'Éclipse est déjà passée pendant cet éveil."), true);
            return;
        }
        ULTI_FAITE.add(joueur.getUUID());
        ULTIS.put(joueur.getUUID(), new Ulti(joueur.serverLevel(), joueur.server.getTickCount(), new Vec3[]{null}));
        effet(joueur, E_ULTI, 0, joueur.position(), Pouvoirs.devant(joueur), 0);
        son(joueur.serverLevel(), joueur.position(), SoundEvents.WITHER_SPAWN, 1.2F, 0.5F);
        son(joueur.serverLevel(), joueur.position(), SoundEvents.WARDEN_EMERGE, 1.6F, 0.7F);
    }

    private static void ultis(MinecraftServer server, int maintenant) {
        for (Map.Entry<UUID, Ulti> en : new ArrayList<>(ULTIS.entrySet())) {
            ServerPlayer joueur = server.getPlayerList().getPlayer(en.getKey());
            Ulti u = en.getValue();
            if (joueur == null) {
                ULTIS.remove(en.getKey());
                continue;
            }
            int age = maintenant - u.debut();
            ServerLevel monde = u.monde();
            if (age < U_LANCE) {
                joueur.setDeltaMovement(0, Math.min(0, joueur.getDeltaMovement().y), 0);
                joueur.hurtMarked = true;
            }
            if (age == U_GEANT) {
                effet(joueur, E_ULTI, 1, joueur.position(), Pouvoirs.devant(joueur), 0);
                son(monde, joueur.position(), SoundEvents.WARDEN_ROAR, 2F, 0.6F);
            }
            if (age == U_LANCE) {
                u.zone()[0] = pointVise(joueur, PORTEE_ULTI);
                effet(joueur, E_ULTI, 2, u.zone()[0], joueur.position(), 0);
                son(monde, u.zone()[0], SoundEvents.WARDEN_SONIC_CHARGE, 3F, 0.5F);
                son(monde, joueur.position(), SoundEvents.WARDEN_SONIC_BOOM, 1.6F, 0.6F);
            }
            Vec3 z = u.zone()[0];
            if (z != null && age > U_LANCE && age < U_IMPLOSION) {
                Vec3 c = z.add(0, 4, 0);
                for (LivingEntity e : monde.getEntitiesOfClass(LivingEntity.class, new AABB(c, c).inflate(RAYON_ULTI), e -> touchable(joueur, e))) {
                    Vec3 vers = c.subtract(milieu(e));
                    double d = vers.length();
                    if (d > RAYON_ULTI || d < 0.5) {
                        continue;
                    }
                    e.setDeltaMovement(e.getDeltaMovement().scale(0.6).add(vers.normalize().scale(0.25 + 0.35 * (1 - d / RAYON_ULTI))));
                    e.hurtMarked = true;
                    e.fallDistance = 0;
                    if (age % 20 == 0) {
                        blesser(joueur, e, 1);
                    }
                }
                if (age % 10 == 0) {
                    son(monde, c, SoundEvents.PORTAL_AMBIENT, 2F, 0.4F);
                }
            }
            if (age == U_IMPLOSION && z != null) {
                Vec3 c = z.add(0, 4, 0);
                effet(joueur, E_ULTI, 3, z, Vec3.ZERO, 0);
                son(monde, c, SoundEvents.GENERIC_EXPLODE.value(), 3F, 0.4F);
                son(monde, c, SoundEvents.WARDEN_SONIC_BOOM, 3F, 0.4F);
                son(monde, c, SoundEvents.END_PORTAL_SPAWN, 1.4F, 0.8F);
                monde.sendParticles(ParticleTypes.EXPLOSION_EMITTER, c.x, c.y, c.z, 2, 1, 1, 1, 0);
                fumee(monde, c, 200, 5);
                for (LivingEntity e : monde.getEntitiesOfClass(LivingEntity.class, new AABB(c, c).inflate(RAYON_ULTI + 2), e -> touchable(joueur, e))) {
                    Vec3 loin = milieu(e).subtract(c);
                    double d = loin.length();
                    if (d > RAYON_ULTI + 2) {
                        continue;
                    }
                    blesser(joueur, e, (float) (IMPLOSION - (IMPLOSION - 10) * Math.min(1, d / RAYON_ULTI)));
                    Vec3 h = new Vec3(loin.x, 0, loin.z);
                    h = h.lengthSqr() < 1e-3 ? new Vec3(1, 0, 0) : h.normalize();
                    e.setDeltaMovement(h.scale(2.2).add(0, 1.2, 0));
                    e.hurtMarked = true;
                    e.addEffect(new MobEffectInstance(MobEffects.DARKNESS, 100, 0));
                }
            }
            if (age >= U_FIN) {
                ULTIS.remove(en.getKey());
                effet(joueur, E_ULTI, 4, z == null ? joueur.position() : z, Vec3.ZERO, 0);
            }
        }
    }

    // ================================================================ chaque tick

    @SubscribeEvent
    public static void tick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        int maintenant = server.getTickCount();
        for (Map.Entry<UUID, Integer> en : new ArrayList<>(EVEILLES.entrySet())) {
            ServerPlayer joueur = server.getPlayerList().getPlayer(en.getKey());
            if (joueur == null) {
                EVEILLES.remove(en.getKey());
                continue;
            }
            if (maintenant % 20 == 0) {
                passifs(joueur, en.getValue());
            }
        }
        lames();
        plongees(server, maintenant);
        tenus(maintenant);
        lignes(maintenant);
        coups(maintenant);
        chainesTick(maintenant);
        ultis(server, maintenant);
    }

    // ================================================================ le clic gauche

    /**
     * Ses coups au corps à corps (n'importe quelle arme, ou les mains) : +2 et l'Obscurité 2 s au niveau 1 ; +4, son ombre
     * frappe aussi (+2) et 3 s au niveau 2 ; +6, 3 s, et il vole 1 cœur au niveau 3.
     */
    @SubscribeEvent
    public static void degats(LivingIncomingDamageEvent event) {
        DamageSource s = event.getSource();
        if (dansPouvoir || !s.is(DamageTypes.PLAYER_ATTACK) || !(s.getEntity() instanceof ServerPlayer j) || s.getDirectEntity() != j) {
            return;
        }
        int niveau = niveau(j);
        if (niveau == 0 || !(event.getEntity() instanceof LivingEntity cible)) {
            return;
        }
        float bonus = niveau >= 3 ? COUP_3 : niveau == 2 ? COUP_2 + COUP_OMBRE : COUP_1;
        event.setAmount(event.getAmount() + bonus);
        cible.addEffect(new MobEffectInstance(MobEffects.DARKNESS, niveau >= 2 ? 60 : 40, 0));
        ServerLevel monde = j.serverLevel();
        fumee(monde, cible.getEyePosition(), 10, 0.3);
        effet(j, E_COUP, niveau, milieu(cible), Pouvoirs.devant(j), cible.getId());
        if (niveau >= 3) {
            j.heal(2);
            effet(j, E_VOL, 0, milieu(cible), milieu(j), cible.getId());
        }
    }

    @SubscribeEvent
    public static void arret(ServerStoppingEvent event) {
        EVEILLES.clear();
        R.vider();
        ULTI_FAITE.clear();
        LAMES.clear();
        PLONGEES.clear();
        TENUS.clear();
        LIGNES.clear();
        COUPS.clear();
        CHAINES_L.clear();
        ULTIS.clear();
    }

    private Tenebres() {
    }
}
