package fr.cabane.lutte;

import fr.cabane.Cabane;
import fr.cabane.lutte.Choregraphie.Geste;
import fr.cabane.lutte.Choregraphie.Pose;
import fr.cabane.lutte.LutteData.Ring;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.CombatRules;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Le combat de catch, dans un ring ou à la salle (voir docs/catch/planche-combat) :
 * <ul>
 * <li>les coups de base avec la tenue : le poing (clic gauche, 3), le coup de pied sauté (en sautant, 6, il recule fort),
 * la corde à linge (en courant, 5, il tombe au tapis une seconde) ;</li>
 * <li>la prise du masque (clic droit tout près, 14) et la provoc (accroupi + clic droit) : 5 s de recharge chacune ;</li>
 * <li>l'armure du catcheur : la tenue (14, solidité 4) et le masque (4) ; les autres armures ne comptent pas dans le
 * ring. Sur un catcheur complet : ½ cœur, 1 cœur, 1 cœur, 3 cœurs et demi ;</li>
 * <li>à 0 cœur on ne meurt pas : KO 5 s au tapis, la cloche sonne, puis on se relève avec 5 cœurs ;</li>
 * <li>le match part tout seul quand deux catcheurs en tenue sont dans le même ring : le chrono monte jusqu'au KO.</li>
 * </ul>
 * Dehors, la tenue et le masque ne sont que des habits : rien de tout ça ne marche.
 */
@EventBusSubscriber(modid = Cabane.MODID)
public final class Combat {
    static final float POING = 3, SAUT = 6, COURSE = 5, PRISE = 14;
    /** 5 s entre deux clics droits (la prise, la provoc), même en créatif. */
    public static final int RECHARGE_PRISE = 100, RECHARGE_PROVOC = 100, DUREE_KO = 100;
    static final double PORTEE_PRISE = 4.5;
    private static final ResourceLocation MOD_POING = Cabane.id("catch_poing"), MOD_VITESSE = Cabane.id("catch_vitesse");

    /** Un geste en cours : qui, le repère, et quand il a commencé (tick du serveur). */
    private record EnCours(Geste geste, LivingEntity a, LivingEntity b, Vec3 origine, float lacet, float dB, float[] debutA, float[] debutB, int debut) {
    }

    private static final List<EnCours> GESTES = new ArrayList<>();
    private static final Map<UUID, Integer> PRISE_PRETE = new HashMap<>(), PROVOC_PRETE = new HashMap<>();
    /** Les joueurs KO, et la fin de leur KO. */
    private static final Map<UUID, Integer> KO = new HashMap<>();
    /** Les poteaux qui crachent du feu (le Démon), jusqu'à quand. */
    private record Feu(ServerLevel level, Ring ring, int fin) {
    }

    private static final List<Feu> FEUX = new ArrayList<>();

    /** Un match dans un ring : les catcheurs (et le dernier tick où on les a vus dedans), le chrono, le gagnant. */
    private static final class Match {
        final Ring ring;
        final Map<UUID, Integer> vus = new LinkedHashMap<>();
        final Map<UUID, String> noms = new HashMap<>();
        final int debut;
        boolean fini;
        int fin, duree, seul;
        String gagnant = "";

        Match(Ring ring, int debut) {
            this.ring = ring;
            this.debut = debut;
        }
    }

    private static final Map<String, Match> MATCHS = new HashMap<>();

    static int maintenant(MinecraftServer server) {
        return server.getTickCount();
    }

    // ------------------------------------------------------------------ demander une prise, une provoc

    /** Est-il déjà dans un geste (ou KO) ? */
    static boolean occupe(LivingEntity e) {
        if (KO.containsKey(e.getUUID())) {
            return true;
        }
        for (EnCours g : GESTES) {
            if (g.a() == e || g.b() == e) {
                return true;
            }
        }
        return false;
    }

    static void demandePrise(ServerPlayer a, int cible) {
        int masque = Equipement.masque(a), t = maintenant(a.server);
        if (masque < 0 || a.isSpectator() || !a.isAlive() || occupe(a) || !Rings.zone(a)) {
            return;
        }
        if (!(a.level().getEntity(cible) instanceof LivingEntity b) || b == a || !b.isAlive() || occupe(b) || b.distanceTo(a) > PORTEE_PRISE || !Rings.zone(b)
                || (b instanceof Player p && p.isSpectator())) {
            return;
        }
        int prete = PRISE_PRETE.getOrDefault(a.getUUID(), 0);
        if (t < prete) {
            a.displayClientMessage(Component.literal("Ta prise revient dans " + Mth.ceil((prete - t) / 20F) + " s").withStyle(ChatFormatting.GRAY), true);
            return;
        }
        Geste g = Geste.values()[masque];
        Vec3 origine;
        float lacet;
        if (g.cordes) {
            // le 619 : il faut que l'autre soit dans les cordes ; le repère part de la corde, l'avant vers l'extérieur
            Vec3[] corde = cordeProche(a.serverLevel(), b);
            if (corde == null) {
                a.displayClientMessage(Component.literal("Le 619 : il faut qu'il soit dans les cordes !").withStyle(ChatFormatting.YELLOW), true);
                return;
            }
            origine = corde[0];
            lacet = Choregraphie.lacetVers(corde[1].x, corde[1].z);
        } else {
            origine = a.position();
            lacet = Choregraphie.lacetVers(b.getX() - a.getX(), b.getZ() - a.getZ());
        }
        float dB = (float) Math.hypot(b.getX() - a.getX(), b.getZ() - a.getZ());
        PRISE_PRETE.put(a.getUUID(), t + RECHARGE_PRISE);
        lancer(g, a, b, origine, lacet, dB);
    }

    static void demandeProvoc(ServerPlayer a) {
        int masque = Equipement.masque(a), t = maintenant(a.server);
        if (masque < 0 || a.isSpectator() || !a.isAlive() || occupe(a) || !Rings.zone(a) || t < PROVOC_PRETE.getOrDefault(a.getUUID(), 0)) {
            return;
        }
        PROVOC_PRETE.put(a.getUUID(), t + RECHARGE_PROVOC);
        lancer(Geste.values()[Geste.PROVOC_MYSTERIO.ordinal() + masque], a, null, a.position(), a.getYRot(), 0);
        if (masque == 3) {
            Ring r = Rings.trouver(a.level(), a.position());
            if (r != null) {
                FEUX.add(new Feu(a.serverLevel(), r, t + 40));
            }
        }
    }

    /** La corde la plus proche de b (à 1,5 bloc au plus) : le point de la corde en face de lui, et l'extérieur du ring. */
    private static Vec3[] cordeProche(ServerLevel level, LivingEntity b) {
        Vec3[] mieux = null;
        double dMieux = 1.5;
        BlockPos c = b.blockPosition();
        for (BlockPos p : BlockPos.betweenClosed(c.offset(-2, -1, -2), c.offset(2, 1, 2))) {
            BlockState s = level.getBlockState(p);
            if (!s.is(Lutte.CORDE.get())) {
                continue;
            }
            Direction d = s.getValue(HorizontalDirectionalBlock.FACING);
            double e = 5.1 / 16;
            double x, z;
            switch (d) {
                case NORTH -> { z = p.getZ() + e; x = Mth.clamp(b.getX(), p.getX(), p.getX() + 1); }
                case SOUTH -> { z = p.getZ() + 1 - e; x = Mth.clamp(b.getX(), p.getX(), p.getX() + 1); }
                case EAST -> { x = p.getX() + 1 - e; z = Mth.clamp(b.getZ(), p.getZ(), p.getZ() + 1); }
                default -> { x = p.getX() + e; z = Mth.clamp(b.getZ(), p.getZ(), p.getZ() + 1); }
            }
            double dist = Math.hypot(b.getX() - x, b.getZ() - z);
            if (dist < dMieux) {
                dMieux = dist;
                mieux = new Vec3[] {new Vec3(x, p.getY(), z), new Vec3(d.getStepX(), 0, d.getStepZ())};
            }
        }
        return mieux;
    }

    /** Lance un geste : retenu ici (le serveur déplace les créatures), envoyé à tous ceux qui sont autour. */
    static void lancer(Geste g, LivingEntity a, LivingEntity b, Vec3 origine, float lacet, float dB) {
        Vec3 f = Choregraphie.avant(lacet), d = new Vec3(-f.z, 0, f.x);
        Vec3 ra = a.position().subtract(origine);
        float[] debutA = {(float) ra.dot(f), (float) ra.dot(d)};
        float[] debutB = null;
        if (b != null) {
            Vec3 rb = b.position().subtract(origine);
            debutB = new float[] {(float) rb.dot(f), (float) rb.dot(d)};
        }
        // les gestes sur place (au tapis, KO, provocs) ne bougent pas de là où il est
        if (!g.prise()) {
            debutA = null;
        }
        GESTES.add(new EnCours(g, a, b, origine, lacet, dB, debutA, debutB, maintenant(a.getServer())));
        if (g.prise()) {
            Lutte.PASSE_CORDES.add(a.getUUID());
            if (b != null) {
                Lutte.PASSE_CORDES.add(b.getUUID());
            }
        }
        envoyer((ServerLevel) a.level(), origine, new Lutte.Anim(g.ordinal(), a.getId(), b == null ? -1 : b.getId(), origine, lacet, dB,
                debutA == null ? 0 : debutA[0], debutA == null ? 0 : debutA[1], debutB == null ? 0 : debutB[0], debutB == null ? 0 : debutB[1]));
    }

    private static void envoyer(ServerLevel level, Vec3 pos, CustomPacketPayload paquet) {
        PacketDistributor.sendToPlayersNear(level, null, pos.x, pos.y, pos.z, 96, paquet);
    }

    // ------------------------------------------------------------------ le tick

    @SubscribeEvent
    public static void onTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        int t = maintenant(server);
        for (Iterator<EnCours> it = GESTES.iterator(); it.hasNext();) {
            EnCours g = it.next();
            int brut = t - g.debut();
            float dt = brut / (float) Choregraphie.RALENTI;
            if (!g.a().isAlive() || g.a().isRemoved() || (g.b() != null && (g.b().isRemoved()))) {
                it.remove();
                Lutte.PASSE_CORDES.remove(g.a().getUUID());
                continue;
            }
            deplacer(g, g.a(), false, dt);
            if (g.b() != null) {
                deplacer(g, g.b(), true, dt);
            }
            if (brut == g.geste().impact * Choregraphie.RALENTI) {
                impact(g);
            }
            if (brut % Choregraphie.RALENTI == 0) {
                effetsProvoc(g, brut / Choregraphie.RALENTI);
            }
            if (dt >= g.geste().duree) {
                it.remove();
                if (g.geste().prise()) {
                    Lutte.PASSE_CORDES.remove(g.a().getUUID());
                    if (g.b() != null) {
                        Lutte.PASSE_CORDES.remove(g.b().getUUID());
                    }
                }
                if (g.geste().prise() && g.b() instanceof ServerPlayer j && j.isAlive() && !KO.containsKey(j.getUUID())) {
                    lancer(Geste.AU_TAPIS, j, null, j.position(), j.getYRot(), 0);
                }
            }
        }
        for (Iterator<Map.Entry<UUID, Integer>> it = KO.entrySet().iterator(); it.hasNext();) {
            Map.Entry<UUID, Integer> e = it.next();
            if (e.getValue() <= t) {
                it.remove();
                ServerPlayer j = server.getPlayerList().getPlayer(e.getKey());
                if (j != null && j.isAlive()) {
                    j.setHealth(Math.max(j.getHealth(), 10F));
                    j.displayClientMessage(Component.literal("Tu te relèves…").withStyle(ChatFormatting.GRAY), true);
                }
            }
        }
        if (t % 2 == 0) {
            feux(t);
        }
        if (t % 10 == 0) {
            for (ServerPlayer j : server.getPlayerList().getPlayers()) {
                attributs(j);
                KitRing.montrer(j, t);
            }
            matchs(server, t);
        }
        Lutte.tickSalle(server);
    }

    /** Le serveur déplace les créatures selon le geste (les joueurs se déplacent eux-mêmes, chez eux). */
    private static void deplacer(EnCours g, LivingEntity e, boolean b, float dt) {
        if (e instanceof Player) {
            return;
        }
        Pose p = Choregraphie.pose(g.geste(), b, dt, g.dB(), b ? g.debutB() : g.debutA());
        if (p == null) {
            return;
        }
        Vec3 pieds = Choregraphie.pieds(g.origine(), g.lacet(), p);
        float lacet = Choregraphie.lacetCorps(g.lacet(), p);
        e.teleportTo(pieds.x, pieds.y, pieds.z);
        e.setYRot(lacet);
        e.setYBodyRot(lacet);
        e.setYHeadRot(lacet);
        e.setDeltaMovement(Vec3.ZERO);
        e.fallDistance = 0;
    }

    /** Le moment du choc : les dégâts de la prise, le bruit, la poussière. */
    private static void impact(EnCours g) {
        LivingEntity a = g.a(), b = g.b();
        if (b == null || !(a.level() instanceof ServerLevel level)) {
            return;
        }
        Vec3 ici = b.position();
        level.playSound(null, ici.x, ici.y, ici.z, Lutte.SON_IMPACT.get(), SoundSource.PLAYERS, 1F, 1F);
        double y = g.geste() == Geste.S619 ? b.getEyeY() : ici.y + 0.2;
        level.sendParticles(ParticleTypes.POOF, ici.x, y, ici.z, 24, 0.8, 0.1, 0.8, 0.04);
        level.sendParticles(ParticleTypes.CRIT, ici.x, y + 0.4, ici.z, 20, 0.4, 0.4, 0.4, 0.3);
        level.sendParticles(ParticleTypes.EXPLOSION, ici.x, y + 0.2, ici.z, 1, 0, 0, 0, 0);
        DamageSource source = new DamageSource(level.registryAccess().registryOrThrow(Registries.DAMAGE_TYPE).getHolderOrThrow(Lutte.PRISE), a);
        b.invulnerableTime = 0;
        b.hurt(source, PRISE);
        if (g.geste() == Geste.CHOKESLAM) {
            Ring r = Rings.trouver(level, a.position());
            if (r != null) {
                FEUX.add(new Feu(level, r, maintenant(level.getServer()) + 40));
            }
        }
    }

    /** La poussière de la Vipère qui tape le tapis, et du Taureau qui gratte le sol. */
    private static void effetsProvoc(EnCours g, int dt) {
        if (!(g.a().level() instanceof ServerLevel level)) {
            return;
        }
        LivingEntity a = g.a();
        BlockState sol = level.getBlockState(a.blockPosition().below());
        if (g.geste() == Geste.PROVOC_VIPERE && dt % 5 == 0 && dt > 3 && dt < 28) {
            Vec3 main = a.position().add(Choregraphie.avant(a.getYRot()).scale(0.6));
            level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, sol), main.x, main.y + 0.1, main.z, 8, 0.15, 0.05, 0.15, 0.1);
        }
        if (g.geste() == Geste.PROVOC_TAUREAU && dt % 4 == 0 && dt > 2 && dt < 26) {
            Vec3 pied = a.position().subtract(Choregraphie.avant(a.getYRot()).scale(0.4));
            level.sendParticles(ParticleTypes.CAMPFIRE_COSY_SMOKE, pied.x, pied.y + 0.1, pied.z, 2, 0.15, 0.02, 0.15, 0.01);
            level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, sol), pied.x, pied.y + 0.1, pied.z, 6, 0.15, 0.05, 0.15, 0.1);
        }
    }

    /** Les quatre poteaux du ring crachent du feu (le chokeslam, la provoc du Démon). */
    private static void feux(int t) {
        FEUX.removeIf(f -> f.fin() < t);
        for (Feu f : FEUX) {
            Ring r = f.ring();
            double e = 5.1 / 16;
            for (double[] c : new double[][] {{r.x0() + e, r.z0() + e}, {r.x0() + r.l() - e, r.z0() + e}, {r.x0() + r.l() - e, r.z0() + r.p() - e},
                    {r.x0() + e, r.z0() + r.p() - e}}) {
                f.level().sendParticles(ParticleTypes.FLAME, c[0], r.dessus() + 1.8, c[1], 10, 0.08, 0.35, 0.08, 0.03);
                if (t % 6 == 0) {
                    f.level().sendParticles(ParticleTypes.LAVA, c[0], r.dessus() + 1.9, c[1], 1, 0.05, 0.1, 0.05, 0);
                }
            }
            if ((f.fin() - t) % 20 == 0) {
                f.level().playSound(null, r.centre().x, r.centre().y, r.centre().z, net.minecraft.sounds.SoundEvents.FIRECHARGE_USE, SoundSource.BLOCKS, 0.6F, 0.8F);
            }
        }
    }

    // ------------------------------------------------------------------ les poings de la tenue, dans le ring

    /** Dans un ring avec la tenue et les mains vides : le poing fait 3 (au lieu de 1), un coup toutes les 0,7 s. */
    private static void attributs(ServerPlayer j) {
        boolean actif = Equipement.tenue(j) >= 0 && j.getMainHandItem().isEmpty() && Rings.zone(j);
        AttributeInstance degats = j.getAttribute(Attributes.ATTACK_DAMAGE), vitesse = j.getAttribute(Attributes.ATTACK_SPEED);
        if (degats == null || vitesse == null) {
            return;
        }
        if (actif) {
            if (!degats.hasModifier(MOD_POING)) {
                degats.addTransientModifier(new AttributeModifier(MOD_POING, POING - 1, AttributeModifier.Operation.ADD_VALUE));
            }
            if (!vitesse.hasModifier(MOD_VITESSE)) {
                vitesse.addTransientModifier(new AttributeModifier(MOD_VITESSE, -2.57, AttributeModifier.Operation.ADD_VALUE));
            }
        } else {
            degats.removeModifier(MOD_POING);
            vitesse.removeModifier(MOD_VITESSE);
        }
    }

    // ------------------------------------------------------------------ les dégâts

    /** KO : on ne prend plus rien (sauf /kill et le vide). */
    @SubscribeEvent
    public static void onDegatsArrivent(LivingIncomingDamageEvent event) {
        if (KO.containsKey(event.getEntity().getUUID()) && !event.getSource().is(DamageTypeTags.BYPASSES_INVULNERABILITY)) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onDegats(LivingDamageEvent.Pre event) {
        LivingEntity v = event.getEntity();
        DamageSource s = event.getContainer().getSource();
        if (v.level().isClientSide || s.is(DamageTypeTags.BYPASSES_INVULNERABILITY) || !Rings.zone(v)) {
            return;
        }
        float brut = event.getContainer().getOriginalDamage();
        Entity attaquant = s.getEntity();
        if (!s.is(Lutte.PRISE) && attaquant instanceof Player p && s.getDirectEntity() == p && Equipement.tenue(p) >= 0 && p.getMainHandItem().isEmpty()
                && Rings.zone(p)) {
            float k = p.getAttackStrengthScale(0.5F);
            k = 0.2F + k * k * 0.8F;
            boolean course = p.isSprinting();
            boolean saut = !course && p.fallDistance > 0 && !p.onGround() && !p.onClimbable() && !p.isInWater();
            brut = (saut ? SAUT : course ? COURSE : POING) * k;
            if (saut) {
                v.knockback(0.9, p.getX() - v.getX(), p.getZ() - v.getZ());
                v.hurtMarked = true;
            }
            if (course && k > 0.8F && v instanceof ServerPlayer j && !occupe(j)) {
                lancer(Geste.AU_TAPIS, j, null, j.position(), j.getYRot(), 0);
            }
        }
        // l'armure du catcheur : la tenue et le masque, et rien d'autre
        boolean tenue = Equipement.tenue(v) >= 0;
        float armure = (tenue ? Equipement.ARMURE_TENUE : 0) + (Equipement.masque(v) >= 0 ? Equipement.ARMURE_MASQUE : 0);
        float fin = armure > 0 ? CombatRules.getDamageAfterAbsorb(v, brut, s, armure, tenue ? Equipement.SOLIDITE_TENUE : 0) : brut;
        if (v instanceof ServerPlayer j && fin >= j.getHealth()) {
            fin = Math.max(0, j.getHealth() - 1);
            ko(j, attaquant);
        }
        event.setNewDamage(fin);
    }

    /**
     * Dans un ring, à 0 cœur un joueur ne meurt pas : KO (le filet de sécurité, pour ce qui passe à travers). Une créature
     * en tenue (un mannequin d'entraînement) meurt, elle : le match est fini.
     */
    @SubscribeEvent
    public static void onMort(LivingDeathEvent event) {
        LivingEntity v = event.getEntity();
        if (v.level().isClientSide) {
            return;
        }
        if (v instanceof ServerPlayer j && !event.getSource().is(DamageTypeTags.BYPASSES_INVULNERABILITY) && Rings.zone(j)) {
            event.setCanceled(true);
            j.setHealth(1F);
            ko(j, event.getSource().getEntity());
        } else if (!(v instanceof Player) && v.getServer() != null) {
            finirMatch(v.getServer(), v, event.getSource().getEntity());
        }
    }

    /** KO : 5 s au tapis, la cloche ; le match (s'il y en a un) est fini. */
    private static void ko(ServerPlayer j, Entity attaquant) {
        if (KO.containsKey(j.getUUID())) {
            return;
        }
        MinecraftServer server = j.server;
        int t = maintenant(server);
        GESTES.removeIf(g -> g.a() == j && !g.geste().prise());
        KO.put(j.getUUID(), t + DUREE_KO);
        lancer(Geste.KO, j, null, j.position(), j.getYRot(), 0);
        ServerLevel level = j.serverLevel();
        level.playSound(null, j.getX(), j.getY(), j.getZ(), Lutte.SON_CLOCHE3.get(), SoundSource.PLAYERS, 1F, 1F);
        level.sendParticles(ParticleTypes.WAX_ON, j.getX(), j.getY() + 0.6, j.getZ(), 12, 0.4, 0.2, 0.4, 0.5);
        j.displayClientMessage(Component.literal("KO !").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD), true);
        finirMatch(server, j, attaquant);
    }

    /** Le perdant est KO (ou mort, pour un mannequin) : son match est fini, le chrono s'arrête, le gagnant s'affiche. */
    private static void finirMatch(MinecraftServer server, LivingEntity perdant, Entity attaquant) {
        int t = maintenant(server);
        for (Match m : MATCHS.values()) {
            if (!m.fini && m.vus.containsKey(perdant.getUUID())) {
                m.fini = true;
                m.fin = t;
                m.duree = t - m.debut;
                m.gagnant = gagnant(server, m, perdant, attaquant);
                envoyerMatch(server, m, t);
                if (!(perdant instanceof Player) && perdant.level() instanceof ServerLevel level) {
                    level.playSound(null, perdant.getX(), perdant.getY(), perdant.getZ(), Lutte.SON_CLOCHE3.get(), SoundSource.PLAYERS, 1F, 1F);
                }
            }
        }
    }

    private static String gagnant(MinecraftServer server, Match m, LivingEntity perdant, Entity attaquant) {
        if (attaquant != null && m.vus.containsKey(attaquant.getUUID())) {
            return m.noms.getOrDefault(attaquant.getUUID(), attaquant.getName().getString());
        }
        String mieux = "";
        float pv = -1;
        for (UUID id : m.vus.keySet()) {
            LivingEntity e = vivant(server, m, id);
            if (e != null && e != perdant && e.getHealth() > pv) {
                pv = e.getHealth();
                mieux = m.noms.getOrDefault(id, e.getName().getString());
            }
        }
        return mieux;
    }

    /** Un catcheur du match (joueur ou mannequin), s'il est encore là. */
    private static LivingEntity vivant(MinecraftServer server, Match m, UUID id) {
        ServerPlayer p = server.getPlayerList().getPlayer(id);
        if (p != null) {
            return p;
        }
        ServerLevel level = server.getLevel(dimension(m.ring));
        return level != null && level.getEntity(id) instanceof LivingEntity e && e.isAlive() ? e : null;
    }

    // ------------------------------------------------------------------ les matchs

    /** Toutes les demi-secondes : qui est dans quel ring, les matchs qui commencent, qui continuent, qui finissent. */
    private static void matchs(MinecraftServer server, int t) {
        LutteData data = LutteData.get(server);
        for (Ring r : data.rings()) {
            String cle = r.cle();
            ServerLevel monde = server.getLevel(dimension(r));
            if (monde == null) {
                continue;
            }
            // les catcheurs en tenue sur le tapis : les joueurs, et les mannequins d'entraînement
            List<LivingEntity> l = monde.getEntitiesOfClass(LivingEntity.class,
                    new AABB(r.x0(), r.dessus() - 0.6, r.z0(), r.x0() + r.l(), r.dessus() + 4, r.z0() + r.p()),
                    e -> e.isAlive() && Equipement.tenue(e) >= 0 && !(e instanceof Player p && p.isSpectator()));
            Match m = MATCHS.get(cle);
            if (m == null) {
                if (l.size() >= 2 && l.stream().anyMatch(e -> e instanceof Player)) {
                    m = new Match(r, t);
                    for (LivingEntity j : l) {
                        m.vus.put(j.getUUID(), t);
                        m.noms.put(j.getUUID(), j.getName().getString());
                    }
                    MATCHS.put(cle, m);
                    ServerLevel level = server.getLevel(dimension(r));
                    if (level != null) {
                        Vec3 c = r.centre();
                        level.playSound(null, c.x, c.y, c.z, Lutte.SON_CLOCHE.get(), SoundSource.PLAYERS, 1.5F, 1F);
                    }
                    envoyerMatch(server, m, t);
                }
                continue;
            }
            if (m.fini) {
                if (t - m.fin > 140) {
                    MATCHS.remove(cle);
                } else {
                    envoyerMatch(server, m, t);
                }
                continue;
            }
            for (LivingEntity j : l) {
                m.vus.put(j.getUUID(), t);
                m.noms.put(j.getUUID(), j.getName().getString());
            }
            Match mm = m;
            m.vus.entrySet().removeIf(e -> t - e.getValue() > 100 || vivant(server, mm, e.getKey()) == null);
            m.seul = m.vus.size() < 2 ? m.seul + 10 : 0;
            if (m.seul > 60) {
                MATCHS.remove(cle);
                envoyerFin(server, m);
                continue;
            }
            envoyerMatch(server, m, t);
        }
        MATCHS.values().removeIf(m -> !data.rings().contains(m.ring));
    }

    private static ResourceKey<Level> dimension(Ring r) {
        return ResourceKey.create(Registries.DIMENSION, r.dim());
    }

    private static Lutte.EtatMatch etat(MinecraftServer server, Match m, int t, boolean arret) {
        List<String> noms = new ArrayList<>();
        List<Float> pv = new ArrayList<>(), pvMax = new ArrayList<>();
        for (UUID id : m.vus.keySet()) {
            if (noms.size() >= 4) {
                break;
            }
            LivingEntity j = vivant(server, m, id);
            noms.add(m.noms.getOrDefault(id, "?"));
            pv.add(j == null ? 0F : (KO.containsKey(id) ? 0F : j.getHealth()));
            pvMax.add(j == null ? 20F : j.getMaxHealth());
        }
        return new Lutte.EtatMatch(m.ring.cle(), m.fini, arret ? "-" : m.gagnant, m.fini ? m.duree : t - m.debut, noms, pv, pvMax, m.ring.centre(), m.ring.salle());
    }

    private static void envoyerMatch(MinecraftServer server, Match m, int t) {
        diffuser(server, m, etat(server, m, t, false));
    }

    private static void envoyerFin(MinecraftServer server, Match m) {
        diffuser(server, m, etat(server, m, 0, true));
    }

    /** Le haut de l'écran : pour ceux qui sont autour du ring (et, à la salle, pour tout le monde). */
    private static void diffuser(MinecraftServer server, Match m, Lutte.EtatMatch paquet) {
        ServerLevel level = server.getLevel(dimension(m.ring));
        if (level == null) {
            return;
        }
        if (m.ring.salle()) {
            PacketDistributor.sendToPlayersInDimension(level, paquet);
        } else {
            Vec3 c = m.ring.centre();
            PacketDistributor.sendToPlayersNear(level, null, c.x, c.y, c.z, 40, paquet);
        }
    }

    // ------------------------------------------------------------------ départs

    @SubscribeEvent
    public static void onDepart(PlayerEvent.PlayerLoggedOutEvent event) {
        UUID id = event.getEntity().getUUID();
        GESTES.removeIf(g -> g.a() == event.getEntity() || g.b() == event.getEntity());
        KO.remove(id);
        KitRing.oublier(id);
    }

    private Combat() {
    }
}
