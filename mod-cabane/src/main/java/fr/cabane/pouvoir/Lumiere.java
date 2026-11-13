package fr.cabane.pouvoir;

import static fr.cabane.pouvoir.ClairObscur.*;

import fr.cabane.Cabane;
import fr.cabane.client.pouvoir.ClientLumiere;
import io.netty.buffer.ByteBuf;
import java.util.ArrayList;
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
 * Le pouvoir de la Lumière, d'après les planches validées (docs/tenebres-lumiere). Trois listes : « lumiere »,
 * « lumiere2 », « lumiere3 ». Toujours : plus vite, il se soigne doucement, protégé comme les autres pouvoirs de son niveau.
 * <ul>
 * <li>niveau 1 : un rayon du ciel tombe sur lui, l'auréole, les yeux d'or. Clic gauche : +2, la cible en surbrillance
 * 5 s. Clic droit : la flèche de lumière (6, instantanée, 25 blocs, 6 s). Accroupi : l'éclat aveuglant (cécité 2 s à 6
 * blocs, 3, 15 s) ;</li>
 * <li>niveau 2 : deux ailes de lumière, pas de dégâts de chute. Clic gauche : +4, un choc qui repousse. Clic droit : 3
 * lances tombent du ciel sur la cible (10, 40 blocs, 15 s). Accroupi : l'envol (12 blocs en l'air, il plane, 10 s).
 * Accroupi + clic droit : la pluie de plumes (8 en cône, 15 blocs, 15 s) ;</li>
 * <li>niveau 3 : la tenue (le skin de l'utilisateur, adapté), six ailes, le soleil dans le dos. Clic gauche : +6, feu
 * sacré 3 s. Clic droit : le rayon solaire (2 s, 16, repousse, 15 s). Accroupi : la vitesse de la lumière (40 blocs, 8 à
 * ceux qu'il traverse, 12 s). Accroupi + clic droit : les plumes. Accroupi + clic gauche : la croix de lumière (15, en
 * l'air, 20 s). Technique : le chakram (son auréole lancée, 12, elle revient, 12 s). Ulti : le Jugement ({@link #ultis}).</li>
 * </ul>
 * Rien ne casse, rien ne prend feu dans le décor. Ce qu'on voit est dessiné par {@link ClientLumiere}.
 */
@EventBusSubscriber(modid = Cabane.MODID)
public final class Lumiere {
    public static final String NOM = "lumiere", NOM2 = "lumiere2", NOM3 = "lumiere3";

    public static boolean est(String pouvoir) {
        return NOM.equals(pouvoir) || NOM2.equals(pouvoir) || NOM3.equals(pouvoir);
    }

    // ================================================================ les chiffres (en ticks)

    public static final int RECHARGE_FLECHE = 6 * 20, RECHARGE_LANCES = 15 * 20, RECHARGE_RAYON = 15 * 20, RECHARGE_FLASH = 15 * 20,
            RECHARGE_ENVOL = 10 * 20, RECHARGE_VITESSE = 12 * 20, RECHARGE_PLUMES = 15 * 20, RECHARGE_CROIX = 20 * 20, RECHARGE_CHAKRAM = 12 * 20;
    public static final int DUREE_RAYON = 40, CROIX_DELAI = 12;
    private static final float COUP_1 = 2, COUP_2 = 4, COUP_3 = 6, FLECHE = 6, FLASH = 3, LANCE = 10 / 3F, RAYON = 2, VITESSE = 8, PLUMES = 8,
            CROIX = 15, CHAKRAM = 12, JUGEMENT = 22, EPEES = 1.5F;
    public static final double PORTEE_FLECHE = 25, PORTEE_RAYON = 30, PORTEE_CHAKRAM = 25, VITESSE_CHAKRAM = 1.6, PORTEE_ULTI = 40, RAYON_ULTI = 10;
    /** Le Jugement : il s'élève (0), l'ange apparaît (30), la pluie d'épées (60), la colonne (100), la fin (130). */
    public static final int U_ANGE = 30, U_EPEES = 60, U_COLONNE = 100, U_FIN = 130;

    // ================================================================ qui est éveillé

    private static final Map<UUID, Integer> EVEILLES = new HashMap<>();
    private static final Recharges R = new Recharges();
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
        son(monde, p, SoundEvents.BEACON_ACTIVATE, 1.6F, niveau >= 3 ? 0.7F : 1F);
        son(monde, p, SoundEvents.AMETHYST_BLOCK_CHIME, 1.6F, 0.8F);
        if (niveau >= 2) {
            son(monde, p, SoundEvents.ENDER_DRAGON_FLAP, 1F, 1.4F);
        }
        if (niveau >= 3) {
            son(monde, p, SoundEvents.TOTEM_USE, 0.8F, 1.2F);
            ULTI_FAITE.remove(joueur.getUUID());
        }
        passifs(joueur, niveau);
    }

    static void endormir(ServerPlayer joueur) {
        Integer etait = EVEILLES.remove(joueur.getUUID());
        R.oublier(joueur.getUUID());
        ULTIS.remove(joueur.getUUID());
        RAYONS.remove(joueur.getUUID());
        if (etait != null) {
            joueur.removeEffect(MobEffects.REGENERATION);
            joueur.removeEffect(MobEffects.MOVEMENT_SPEED);
            if (joueur.isAlive()) {
                joueur.serverLevel().sendParticles(ParticleTypes.END_ROD, joueur.getX(), joueur.getY() + 1, joueur.getZ(), 30, 0.4, 0.6, 0.4, 0.05);
            }
        }
    }

    /** Plus vite (+20 %, puis +40 %), et il se soigne doucement (Régénération I). */
    private static void passifs(ServerPlayer joueur, int niveau) {
        if (niveau <= 0) {
            return;
        }
        joueur.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SPEED, 50, niveau >= 2 ? 1 : 0, false, false, true));
        joueur.addEffect(new MobEffectInstance(MobEffects.REGENERATION, 100, 0, false, false, true));
    }

    public static boolean ultiActif(ServerPlayer joueur) {
        return ULTIS.containsKey(joueur.getUUID());
    }

    // ================================================================ réseau

    public record Touche(int action) implements CustomPacketPayload {
        public static final Type<Touche> TYPE = new Type<>(Cabane.id("lumiere_touche"));
        public static final StreamCodec<ByteBuf, Touche> CODEC = StreamCodec.composite(ByteBufCodecs.VAR_INT, Touche::action, Touche::new);

        @Override
        public Type<Touche> type() {
            return TYPE;
        }
    }

    public static final int E_COUP = 0, E_FLECHE = 1, E_FLASH = 2, E_LANCE = 3, E_ENVOL = 4, E_RAYON = 5, E_VITESSE = 6, E_PLUMES = 7,
            E_CROIX = 8, E_CHAKRAM = 9, E_ULTI = 10;

    public record Effet(UUID joueur, int sorte, int phase, Vector3f pos, Vector3f dir, int n) implements CustomPacketPayload {
        public static final Type<Effet> TYPE = new Type<>(Cabane.id("lumiere_effet"));
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
                .playToClient(Effet.TYPE, Effet.CODEC, (p, ctx) -> ctx.enqueueWork(() -> ClientLumiere.effet(p)));
    }

    static void effet(ServerPlayer joueur, int sorte, int phase, Vec3 pos, Vec3 dir, int n) {
        envoyer(joueur.serverLevel(), new Effet(joueur.getUUID(), sorte, phase, v(pos), v(dir), n), pos);
    }

    private static final DustParticleOptions OR = new DustParticleOptions(new Vector3f(1F, 0.8F, 0.3F), 1.2F);

    private static void eclat(ServerLevel monde, Vec3 p, int n, double r) {
        monde.sendParticles(ParticleTypes.END_ROD, p.x, p.y, p.z, n, r, r, r, 0.08);
        monde.sendParticles(OR, p.x, p.y, p.z, n, r, r, r, 0.02);
    }

    // ================================================================ les touches

    private static void touche(ServerPlayer joueur, int action) {
        int niveau = niveau(joueur);
        if (niveau == 0 || ultiActif(joueur)) {
            return;
        }
        switch (action) {
            case DROIT -> {
                if (niveau >= 3) {
                    rayonSolaire(joueur);
                } else if (niveau == 2) {
                    lances(joueur);
                } else {
                    fleche(joueur);
                }
            }
            case ACCROUPI -> {
                if (niveau >= 3) {
                    vitesse(joueur);
                } else if (niveau == 2) {
                    envol(joueur);
                } else {
                    flash(joueur);
                }
            }
            case ACC_DROIT -> {
                if (niveau >= 2) {
                    plumes(joueur);
                }
            }
            case ACC_GAUCHE -> {
                if (niveau >= 3) {
                    croix(joueur);
                }
            }
            case TECHNIQUE -> {
                if (niveau >= 3) {
                    chakram(joueur);
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

    /** Le premier vivant sur ce trait (de, a), et où le trait s'arrête (un mur, ou au bout). */
    private static LivingEntity premier(ServerPlayer joueur, Vec3 de, Vec3[] a, double large) {
        HitResult bloc = joueur.serverLevel().clip(new ClipContext(de, a[0], ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, joueur));
        if (bloc.getType() != HitResult.Type.MISS) {
            a[0] = bloc.getLocation();
        }
        LivingEntity touche = null;
        double meilleur = Double.MAX_VALUE;
        for (LivingEntity e : joueur.serverLevel().getEntitiesOfClass(LivingEntity.class, new AABB(de, a[0]).inflate(large + 1), e -> touchable(joueur, e))) {
            var hit = e.getBoundingBox().inflate(large).clip(de, a[0]);
            if (hit.isPresent() && hit.get().distanceToSqr(de) < meilleur) {
                meilleur = hit.get().distanceToSqr(de);
                touche = e;
            }
        }
        return touche;
    }

    // ---------------------------------------------------------------- niveau 1 : la flèche, l'éclat

    private static void fleche(ServerPlayer joueur) {
        if (!pret(R.droit, joueur)) {
            return;
        }
        armer(R.droit, joueur, RECHARGE_FLECHE);
        Vec3 de = joueur.getEyePosition().add(0, -0.3, 0);
        Vec3[] a = {de.add(joueur.getLookAngle().scale(PORTEE_FLECHE))};
        LivingEntity c = premier(joueur, de, a, 0.4);
        Vec3 fin = c != null ? milieu(c) : a[0];
        if (c != null) {
            blesser(joueur, c, FLECHE);
            eclat(joueur.serverLevel(), fin, 14, 0.3);
        }
        effet(joueur, E_FLECHE, 0, de, fin, c == null ? -1 : c.getId());
        son(joueur.serverLevel(), de, SoundEvents.BEACON_DEACTIVATE, 1F, 1.8F);
        son(joueur.serverLevel(), fin, SoundEvents.AMETHYST_BLOCK_BREAK, 1.2F, 1.4F);
    }

    private static void flash(ServerPlayer joueur) {
        if (!pret(R.accroupi, joueur)) {
            return;
        }
        armer(R.accroupi, joueur, RECHARGE_FLASH);
        ServerLevel monde = joueur.serverLevel();
        effet(joueur, E_FLASH, 0, joueur.position(), Vec3.ZERO, 6);
        eclat(monde, joueur.position().add(0, 1, 0), 60, 1.5);
        monde.sendParticles(ParticleTypes.FLASH, joueur.getX(), joueur.getY() + 1, joueur.getZ(), 1, 0, 0, 0, 0);
        son(monde, joueur.position(), SoundEvents.FIREWORK_ROCKET_BLAST, 1.6F, 1.6F);
        son(monde, joueur.position(), SoundEvents.BEACON_POWER_SELECT, 1.4F, 1.8F);
        for (LivingEntity e : monde.getEntitiesOfClass(LivingEntity.class, joueur.getBoundingBox().inflate(6), e -> touchable(joueur, e))) {
            if (e.distanceTo(joueur) > 6) {
                continue;
            }
            blesser(joueur, e, FLASH);
            e.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, 40, 0));
        }
    }

    // ---------------------------------------------------------------- niveau 2 : les lances, l'envol, les plumes

    private record Frappe(ServerPlayer joueur, ServerLevel monde, int cible, Vec3 point, int quand, float degats, double rayon, double monte) {
    }

    private static final List<Frappe> FRAPPES = new ArrayList<>();

    private static void lances(ServerPlayer joueur) {
        if (!pret(R.droit, joueur)) {
            return;
        }
        LivingEntity c = visee(joueur, 40, 0.9);
        Vec3 point = c != null ? c.position() : pointVise(joueur, 40);
        armer(R.droit, joueur, RECHARGE_LANCES);
        int maintenant = joueur.server.getTickCount();
        for (int i = 0; i < 3; i++) {
            FRAPPES.add(new Frappe(joueur, joueur.serverLevel(), c == null ? -1 : c.getId(), point, maintenant + 10 + i * 4, LANCE, 1.6, 0.2));
            effet(joueur, E_LANCE, i, point, Vec3.ZERO, c == null ? -1 : c.getId());
        }
        son(joueur.serverLevel(), joueur.position(), SoundEvents.TRIDENT_RIPTIDE_1.value(), 1.4F, 1.4F);
    }

    private static void frappes(int maintenant) {
        FRAPPES.removeIf(f -> {
            if (maintenant < f.quand()) {
                return false;
            }
            Vec3 p = f.point();
            if (f.cible() >= 0 && f.monde().getEntity(f.cible()) instanceof LivingEntity c && c.isAlive()) {
                p = c.position();
            }
            for (LivingEntity e : f.monde().getEntitiesOfClass(LivingEntity.class, new AABB(p, p).inflate(f.rayon(), 3, f.rayon()), e -> touchable(f.joueur(), e))) {
                blesser(f.joueur(), e, f.degats());
                if (f.monte() > 0) {
                    e.setDeltaMovement(e.getDeltaMovement().add(0, f.monte(), 0));
                    e.hurtMarked = true;
                }
            }
            eclat(f.monde(), p.add(0, 0.5, 0), 20, 0.4);
            son(f.monde(), p, SoundEvents.TRIDENT_HIT, 1.2F, 1.2F);
            return true;
        });
    }

    private static void envol(ServerPlayer joueur) {
        if (!pret(R.accroupi, joueur)) {
            return;
        }
        armer(R.accroupi, joueur, RECHARGE_ENVOL);
        joueur.setDeltaMovement(joueur.getDeltaMovement().x, 1.75, joueur.getDeltaMovement().z);
        joueur.hurtMarked = true;
        joueur.fallDistance = 0;
        joueur.addEffect(new MobEffectInstance(MobEffects.SLOW_FALLING, 160, 0, false, false, true));
        effet(joueur, E_ENVOL, 0, joueur.position(), Vec3.ZERO, 0);
        eclat(joueur.serverLevel(), joueur.position(), 40, 1.5);
        son(joueur.serverLevel(), joueur.position(), SoundEvents.ENDER_DRAGON_FLAP, 2F, 1.2F);
    }

    /** Accroupi + clic droit : ses ailes tirent une rafale de plumes d'or devant lui (un cône, 15 blocs). */
    private static void plumes(ServerPlayer joueur) {
        if (!pret(R.accDroit, joueur)) {
            return;
        }
        armer(R.accDroit, joueur, RECHARGE_PLUMES);
        Vec3 regard = joueur.getLookAngle();
        effet(joueur, E_PLUMES, 0, joueur.getEyePosition(), regard, 0);
        son(joueur.serverLevel(), joueur.position(), SoundEvents.ENDER_DRAGON_FLAP, 1.6F, 1.6F);
        son(joueur.serverLevel(), joueur.position(), SoundEvents.ARROW_SHOOT, 1.4F, 1.4F);
        int maintenant = joueur.server.getTickCount();
        for (LivingEntity e : joueur.serverLevel().getEntitiesOfClass(LivingEntity.class, joueur.getBoundingBox().inflate(15), e -> touchable(joueur, e))) {
            Vec3 vers = milieu(e).subtract(joueur.getEyePosition());
            if (vers.length() > 15 || vers.normalize().dot(regard) < 0.8 || !joueur.hasLineOfSight(e)) {
                continue;
            }
            FRAPPES.add(new Frappe(joueur, joueur.serverLevel(), e.getId(), e.position(), maintenant + 4 + (int) (vers.length() / 2.5), PLUMES, 0.6, 0));
        }
    }

    // ---------------------------------------------------------------- niveau 3 : le rayon solaire, la vitesse, la croix, le chakram

    /** Qui tire son rayon : jusqu'à quand. */
    private static final Map<UUID, Integer> RAYONS = new HashMap<>();

    private static void rayonSolaire(ServerPlayer joueur) {
        if (!pret(R.droit, joueur)) {
            return;
        }
        armer(R.droit, joueur, RECHARGE_RAYON);
        RAYONS.put(joueur.getUUID(), joueur.server.getTickCount() + DUREE_RAYON);
        effet(joueur, E_RAYON, 0, joueur.position(), Vec3.ZERO, DUREE_RAYON);
        son(joueur.serverLevel(), joueur.position(), SoundEvents.BEACON_ACTIVATE, 2F, 1.4F);
        son(joueur.serverLevel(), joueur.position(), SoundEvents.GUARDIAN_ATTACK, 1.4F, 1.6F);
    }

    private static void rayons(MinecraftServer server, int maintenant) {
        for (Map.Entry<UUID, Integer> en : new ArrayList<>(RAYONS.entrySet())) {
            ServerPlayer joueur = server.getPlayerList().getPlayer(en.getKey());
            if (joueur == null || maintenant >= en.getValue()) {
                RAYONS.remove(en.getKey());
                continue;
            }
            joueur.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 5, 2, false, false, false));
            if ((en.getValue() - maintenant) % 5 != 0) {
                continue;
            }
            Vec3 de = joueur.getEyePosition().add(0, -0.4, 0), regard = joueur.getLookAngle();
            Vec3[] a = {de.add(regard.scale(PORTEE_RAYON))};

            HitResult bloc = joueur.serverLevel().clip(new ClipContext(de, a[0], ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, joueur));
            if (bloc.getType() != HitResult.Type.MISS) {
                a[0] = bloc.getLocation();
            }
            for (LivingEntity e : joueur.serverLevel().getEntitiesOfClass(LivingEntity.class, new AABB(de, a[0]).inflate(2), e -> touchable(joueur, e))) {
                if (e.getBoundingBox().inflate(1).clip(de, a[0]).isEmpty()) {
                    continue;
                }
                blesser(joueur, e, RAYON);
                e.setDeltaMovement(e.getDeltaMovement().add(regard.x * 0.5, 0.12, regard.z * 0.5));
                e.hurtMarked = true;
            }
            eclat(joueur.serverLevel(), a[0], 10, 0.4);
        }
    }

    private static void vitesse(ServerPlayer joueur) {
        if (!pret(R.accroupi, joueur)) {
            return;
        }
        armer(R.accroupi, joueur, RECHARGE_VITESSE);
        ServerLevel monde = joueur.serverLevel();
        Vec3 dep = joueur.position(), fin = arrivee(joueur, joueur.getLookAngle(), 40);
        Set<Integer> touches = new HashSet<>();
        for (LivingEntity e : monde.getEntitiesOfClass(LivingEntity.class, new AABB(dep, fin).inflate(1.5, 2, 1.5), e -> touchable(joueur, e))) {
            if (e.getBoundingBox().inflate(1.2).clip(dep.add(0, 1, 0), fin.add(0, 1, 0)).isPresent() && touches.add(e.getId())) {
                blesser(joueur, e, VITESSE);
                Vec3 d = fin.subtract(dep).normalize();
                e.setDeltaMovement(e.getDeltaMovement().add(d.x * 0.6, 0.6, d.z * 0.6));
                e.hurtMarked = true;
                eclat(monde, milieu(e), 14, 0.3);
            }
        }
        joueur.teleportTo(fin.x, fin.y, fin.z);
        joueur.fallDistance = 0;
        effet(joueur, E_VITESSE, 0, dep, fin, 0);
        son(monde, dep, SoundEvents.BEACON_DEACTIVATE, 1.2F, 2F);
        son(monde, fin, SoundEvents.AMETHYST_BLOCK_RESONATE, 1.6F, 1.6F);
    }

    private static void croix(ServerPlayer joueur) {
        if (!pret(R.accGauche, joueur)) {
            return;
        }
        LivingEntity c = visee(joueur, 30, 0.9);
        Vec3 point = c != null ? c.position() : pointVise(joueur, 30);
        armer(R.accGauche, joueur, RECHARGE_CROIX);
        point = new Vec3(point.x, sol(joueur.serverLevel(), point), point.z);
        FRAPPES.add(new Frappe(joueur, joueur.serverLevel(), -1, point, joueur.server.getTickCount() + CROIX_DELAI, CROIX, 2.5, 1.3));
        effet(joueur, E_CROIX, 0, point, Vec3.ZERO, c == null ? -1 : c.getId());
        son(joueur.serverLevel(), point, SoundEvents.BEACON_POWER_SELECT, 2F, 0.8F);
    }

    private record Chakram(ServerPlayer joueur, ServerLevel monde, Vec3[] pos, Vec3 dir, int debut, Set<Integer> touches) {
    }

    private static final List<Chakram> CHAKRAMS = new ArrayList<>();

    private static void chakram(ServerPlayer joueur) {
        if (!pret(R.technique, joueur)) {
            return;
        }
        armer(R.technique, joueur, RECHARGE_CHAKRAM);
        Vec3 dep = joueur.getEyePosition().add(0, 0.3, 0);
        Vec3 dir = joueur.getLookAngle();
        CHAKRAMS.add(new Chakram(joueur, joueur.serverLevel(), new Vec3[]{dep}, dir, joueur.server.getTickCount(), new HashSet<>()));
        effet(joueur, E_CHAKRAM, 0, dep, dir, 0);
        son(joueur.serverLevel(), dep, SoundEvents.TRIDENT_THROW.value(), 1.4F, 1.4F);
    }

    /** Le chakram file tout droit (25 blocs), puis revient dans sa main ; il tranche ce qu'il traverse (une fois chacun). */
    private static void chakrams(int maintenant) {
        CHAKRAMS.removeIf(k -> {
            if (k.joueur().isRemoved()) {
                return true;
            }
            int age = maintenant - k.debut();
            double aller = PORTEE_CHAKRAM / VITESSE_CHAKRAM;
            Vec3 de = k.pos()[0], a;
            if (age <= aller) {
                a = de.add(k.dir().scale(VITESSE_CHAKRAM));
            } else {
                Vec3 main = k.joueur().getEyePosition().add(0, -0.2, 0);
                Vec3 vers = main.subtract(de);
                if (vers.length() < 1.6) {
                    son(k.monde(), main, SoundEvents.AMETHYST_BLOCK_CHIME, 1.2F, 1.4F);
                    return true;
                }
                a = de.add(vers.normalize().scale(Math.min(vers.length(), VITESSE_CHAKRAM * 1.2)));
            }
            k.pos()[0] = a;
            for (LivingEntity e : k.monde().getEntitiesOfClass(LivingEntity.class, new AABB(de, a).inflate(0.9), e -> touchable(k.joueur(), e))) {
                if (k.touches().add(e.getId())) {
                    blesser(k.joueur(), e, CHAKRAM);
                    eclat(k.monde(), milieu(e), 12, 0.3);
                    son(k.monde(), e.position(), SoundEvents.PLAYER_ATTACK_SWEEP, 1.2F, 1.6F);
                }
            }
            return age > aller * 4;
        });
    }

    // ================================================================ l'ulti : le Jugement

    /**
     * Le Jugement, une fois par éveil : les nuages s'ouvrent et il s'élève (5 blocs) ; un ange géant de lumière apparaît
     * derrière lui, l'épée levée ; un cercle d'or marque la zone (20 blocs de large, là où il vise, 40 blocs au plus) et une
     * pluie d'épées s'y abat ; puis l'ange abat son épée : une colonne de lumière, 22 au centre (12 au bord), tout est
     * projeté en l'air. Rien ne casse, rien ne brûle. Il redescend doucement.
     */
    private record Ulti(ServerLevel monde, int debut, double hauteur, Vec3[] zone) {
    }

    private static final Map<UUID, Ulti> ULTIS = new HashMap<>();

    private static void lancerUlti(ServerPlayer joueur) {
        if (ULTI_FAITE.contains(joueur.getUUID())) {
            joueur.displayClientMessage(net.minecraft.network.chat.Component.literal("Le Jugement est déjà tombé pendant cet éveil."), true);
            return;
        }
        ULTI_FAITE.add(joueur.getUUID());
        ULTIS.put(joueur.getUUID(), new Ulti(joueur.serverLevel(), joueur.server.getTickCount(), joueur.getY() + 5, new Vec3[]{null}));
        effet(joueur, E_ULTI, 0, joueur.position(), Pouvoirs.devant(joueur), 0);
        son(joueur.serverLevel(), joueur.position(), SoundEvents.BEACON_ACTIVATE, 3F, 0.6F);
        son(joueur.serverLevel(), joueur.position(), SoundEvents.TOTEM_USE, 1.4F, 0.8F);
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
            // il monte, puis reste en l'air
            double dy = u.hauteur() - joueur.getY();
            joueur.setDeltaMovement(joueur.getDeltaMovement().x * 0.5, Mth2.clamp(dy * 0.25, -0.2, 0.4), joueur.getDeltaMovement().z * 0.5);
            joueur.hurtMarked = true;
            joueur.fallDistance = 0;
            if (age == U_ANGE) {
                effet(joueur, E_ULTI, 1, joueur.position(), Pouvoirs.devant(joueur), 0);
                son(monde, joueur.position(), SoundEvents.ENDER_DRAGON_GROWL, 1.2F, 1.8F);
                son(monde, joueur.position(), SoundEvents.BELL_BLOCK, 2F, 0.6F);
            }
            if (age == U_EPEES) {
                u.zone()[0] = pointVise(joueur, PORTEE_ULTI);
                effet(joueur, E_ULTI, 2, u.zone()[0], joueur.position(), 0);
                son(monde, u.zone()[0], SoundEvents.BEACON_POWER_SELECT, 3F, 0.6F);
            }
            Vec3 z = u.zone()[0];
            if (z != null && age > U_EPEES && age < U_COLONNE && age % 8 == 0) {
                for (LivingEntity e : monde.getEntitiesOfClass(LivingEntity.class, new AABB(z, z).inflate(RAYON_ULTI, 4, RAYON_ULTI), e -> touchable(joueur, e))) {
                    if (e.position().subtract(z).horizontalDistance() <= RAYON_ULTI) {
                        blesser(joueur, e, EPEES);
                    }
                }
                son(monde, z, SoundEvents.TRIDENT_HIT_GROUND, 1.6F, 1.2F);
            }
            if (age == U_COLONNE && z != null) {
                effet(joueur, E_ULTI, 3, z, Vec3.ZERO, 0);
                son(monde, z, SoundEvents.LIGHTNING_BOLT_THUNDER, 3F, 1.2F);
                son(monde, z, SoundEvents.GENERIC_EXPLODE.value(), 2.4F, 0.8F);
                son(monde, z, SoundEvents.BEACON_DEACTIVATE, 3F, 0.5F);
                monde.sendParticles(ParticleTypes.EXPLOSION_EMITTER, z.x, z.y + 1, z.z, 2, 1, 1, 1, 0);
                eclat(monde, z.add(0, 2, 0), 200, 4);
                for (LivingEntity e : monde.getEntitiesOfClass(LivingEntity.class, new AABB(z, z).inflate(RAYON_ULTI + 2, 6, RAYON_ULTI + 2), e -> touchable(joueur, e))) {
                    Vec3 loin = e.position().subtract(z);
                    double d = loin.horizontalDistance();
                    if (d > RAYON_ULTI + 2) {
                        continue;
                    }
                    blesser(joueur, e, (float) (JUGEMENT - (JUGEMENT - 12) * Math.min(1, d / RAYON_ULTI)));
                    Vec3 h = new Vec3(loin.x, 0, loin.z);
                    h = h.lengthSqr() < 1e-3 ? new Vec3(1, 0, 0) : h.normalize();
                    e.setDeltaMovement(h.scale(1.4).add(0, 1.5, 0));
                    e.hurtMarked = true;
                }
            }
            if (age >= U_FIN) {
                ULTIS.remove(en.getKey());
                joueur.addEffect(new MobEffectInstance(MobEffects.SLOW_FALLING, 120, 0, false, false, true));
                effet(joueur, E_ULTI, 4, z == null ? joueur.position() : z, Vec3.ZERO, 0);
            }
        }
    }

    /** Un petit clamp (pour ne pas importer Mth ici). */
    private static final class Mth2 {
        static double clamp(double x, double a, double b) {
            return Math.max(a, Math.min(b, x));
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
        frappes(maintenant);
        rayons(server, maintenant);
        chakrams(maintenant);
        ultis(server, maintenant);
    }

    // ================================================================ le clic gauche, la chute

    /**
     * Ses coups au corps à corps : +2 et la cible en surbrillance 5 s (niveau 1) ; +4 et un choc qui repousse (niveau 2) ;
     * +6 et le feu sacré 3 s (niveau 3 : elle brûle, rien d'autre ne prend feu). Dès le niveau 2, pas de dégâts de chute.
     */
    @SubscribeEvent
    public static void degats(LivingIncomingDamageEvent event) {
        DamageSource s = event.getSource();
        if (event.getEntity() instanceof ServerPlayer moi && s.is(DamageTypes.FALL) && niveau(moi) >= 2) {
            event.setCanceled(true);
            return;
        }
        if (dansPouvoir || !s.is(DamageTypes.PLAYER_ATTACK) || !(s.getEntity() instanceof ServerPlayer j) || s.getDirectEntity() != j) {
            return;
        }
        int niveau = niveau(j);
        if (niveau == 0 || !(event.getEntity() instanceof LivingEntity cible)) {
            return;
        }
        event.setAmount(event.getAmount() + (niveau >= 3 ? COUP_3 : niveau == 2 ? COUP_2 : COUP_1));
        ServerLevel monde = j.serverLevel();
        eclat(monde, milieu(cible), 8, 0.3);
        effet(j, E_COUP, niveau, milieu(cible), Pouvoirs.devant(j), cible.getId());
        if (niveau == 1) {
            cible.addEffect(new MobEffectInstance(MobEffects.GLOWING, 100, 0));
        } else if (niveau == 2) {
            Vec3 d = Pouvoirs.devant(j);
            cible.knockback(1.2, -d.x, -d.z);
        } else {
            cible.igniteForSeconds(3);
        }
    }

    @SubscribeEvent
    public static void arret(ServerStoppingEvent event) {
        EVEILLES.clear();
        R.vider();
        ULTI_FAITE.clear();
        FRAPPES.clear();
        RAYONS.clear();
        CHAKRAMS.clear();
        ULTIS.clear();
    }

    private Lumiere() {
    }
}
