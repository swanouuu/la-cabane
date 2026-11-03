package fr.cabane.pouvoir;

import fr.cabane.Cabane;
import fr.cabane.CabaneSons;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
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
import net.minecraft.world.entity.player.Abilities;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.joml.Vector3f;

/**
 * Le mode Ichibi (Shukaku, le démon du sable), d'après docs/ichibi/planche-ichibi.png : liste blanche « ichibi », un
 * pouvoir de niveau 1 (2 minutes d'éveil, puis 2 minutes de repos ; une armure de sable comme du diamant). À l'éveil, le
 * son de la vidéo de l'hôte (dans le pack privé ; sinon un rugissement) et la moitié droite du corps devient Ichibi : un
 * grand bras de sable griffu, l'oreille, l'œil, des piques et la queue (dessinés par
 * {@link fr.cabane.client.pouvoir.ClientIchibi}).
 * <ul>
 * <li>clic gauche : un coup du bras d'Ichibi, 10 dégâts à 5 blocs, qui envoie loin ;</li>
 * <li>clic droit : le bras s'allonge, la main grossit et attrape ce qui est devant (jusqu'à 8 blocs) et le soulève ; le
 * sable l'écrase (1 dégât par seconde, 8 s au plus). Clic droit encore : la main serre fort (12 dégâts) et le lâche ;
 * clic gauche : elle le jette loin (8 dégâts). On ne rattrape qu'au bout de 12 s ;</li>
 * <li>shift : un nuage de sable sous ses pieds, pour voler 30 s (espace monte, shift descend) ; à la fin le nuage le pose
 * doucement (pas de dégâts de chute), et il se reforme en 30 s. Deux appuis rapides sur s'accroupir : il descend du nuage
 * tout de suite (même recharge).</li>
 * </ul>
 * Les clics et le shift arrivent par les paquets du Susano ({@link Pouvoirs.Frappe}, {@link Pouvoirs.Attraper},
 * {@link Pouvoirs.Charge}) ; ce qu'on voit passe par {@link Pouvoirs.Geste}.
 */
@EventBusSubscriber(modid = Cabane.MODID)
public final class Ichibi {
    public static final String NOM = "ichibi";

    /** Le sable monte pendant ce temps à l'éveil (le bras, puis la moitié du corps) : rien ne part avant. */
    public static final int TRANSFORMATION = 20;
    private static final int RECHARGE_COUP = 12;
    private static final double PORTEE_COUP = 5;
    private static final float COUP = 10;
    private static final double PORTEE_PRISE = 8;
    public static final int RECHARGE_PRISE = 12 * 20;
    private static final int DUREE_PRISE = 8 * 20;
    /** La main met ce temps à ramener sa prise jusqu'à elle (le bras s'allonge jusqu'à elle, puis se replie). */
    private static final int ARRACHER = 8;
    /** Clic droit encore : la main se referme sur lui pendant ce temps, puis le lâche. */
    private static final int SERRER = 7;
    private static final float ECRASE = 1;
    private static final float SERRE = 12;
    private static final float LANCE = 8;
    public static final int DUREE_NUAGE = 30 * 20;
    public static final int RECHARGE_NUAGE = 30 * 20;
    /** Le nuage le pose doucement : il tombe au ralenti, au plus ce temps. */
    private static final int ATTERRISSAGE = 15 * 20;
    /** Sur son nuage, il file bien plus vite qu'en vol créatif (0,05). */
    private static final float VITESSE_VOL = 0.11F;

    /** Ce que voient les autres (paquet {@link Pouvoirs.Geste}) : le coup de bras ; la prise (l'entité, -1 : lâchée). */
    public static final int GESTE_COUP = 40;
    public static final int GESTE_PRISE = 41;
    public static final int GESTE_SERRE = 42;
    public static final int GESTE_LANCE = 43;
    /** Le nuage : 1 il se forme, 0 il s'en va. */
    public static final int GESTE_NUAGE = 44;

    public static boolean est(String pouvoir) {
        return NOM.equals(pouvoir);
    }

    /** Où en est un Ichibi éveillé (en ticks du serveur ; -1 : pas en cours). */
    private static final class Etat {
        int debut;
        int coup = -100;
        int prise = -1;
        int priseDebut;
        Vec3 priseDepart;
        int priseRecharge = -1;
        /** Quand la main qui serre le lâchera (-1 : elle ne serre pas). */
        int lacher = -1;
        int nuageFin = -1;
        int nuageRecharge = -1;
    }

    private static final Map<UUID, Etat> ETATS = new HashMap<>();
    /** Ceux que le nuage vient de lâcher : ils tombent doucement jusqu'au sol (le tick où ça s'arrête de toute façon). */
    private static final Map<UUID, Integer> POSES = new HashMap<>();

    // ================================================================ s'éveiller, s'endormir

    static void eveiller(ServerPlayer joueur) {
        Etat e = new Etat();
        e.debut = joueur.server.getTickCount();
        ETATS.put(joueur.getUUID(), e);
        ServerLevel monde = joueur.serverLevel();
        monde.playSound(null, joueur.getX(), joueur.getY(), joueur.getZ(), CabaneSons.ICHIBI_EVEIL.get(), SoundSource.PLAYERS, 1.6F, 1F);
        son(joueur, SoundEvents.SAND_BREAK, 1.4F, 0.6F);
        monde.sendParticles(sable(), joueur.getX(), joueur.getY(0.5), joueur.getZ(), 80, 0.6, 0.9, 0.6, 0.15);
        monde.sendParticles(poussiere(), joueur.getX(), joueur.getY(0.6), joueur.getZ(), 40, 0.7, 0.9, 0.7, 0.05);
    }

    static void endormir(ServerPlayer joueur) {
        Etat e = ETATS.remove(joueur.getUUID());
        if (e == null) {
            return;
        }
        lacher(joueur, e);
        if (e.nuageFin >= 0) {
            finNuage(joueur, e, joueur.server.getTickCount());
        }
        joueur.serverLevel().sendParticles(sable(), joueur.getX(), joueur.getY(0.5), joueur.getZ(), 50, 0.6, 0.9, 0.6, 0.1);
        son(joueur, SoundEvents.SAND_BREAK, 1.2F, 0.8F);
    }

    private static boolean pret(ServerPlayer joueur, Etat e) {
        return e != null && joueur.server.getTickCount() - e.debut >= TRANSFORMATION;
    }

    private static ParticleOptions sable() {
        return new BlockParticleOption(ParticleTypes.BLOCK, Blocks.SAND.defaultBlockState());
    }

    private static ParticleOptions poussiere() {
        return new DustParticleOptions(new Vector3f(0.84F, 0.73F, 0.6F), 1.6F);
    }

    private static void son(ServerPlayer joueur, SoundEvent son, float volume, float hauteur) {
        joueur.serverLevel().playSound(null, joueur.getX(), joueur.getY() + 1, joueur.getZ(), son, SoundSource.PLAYERS, volume, hauteur);
    }

    // ================================================================ clic gauche : le coup de bras (ou jeter sa prise)

    static void coup(ServerPlayer joueur) {
        Etat e = ETATS.get(joueur.getUUID());
        if (!pret(joueur, e)) {
            return;
        }
        if (e.lacher >= 0) {
            return;
        }
        if (e.prise >= 0) {
            lancer(joueur, e);
            return;
        }
        int maintenant = joueur.server.getTickCount();
        if (maintenant - e.coup < RECHARGE_COUP) {
            return;
        }
        e.coup = maintenant;
        Pouvoirs.geste(joueur, GESTE_COUP, 0);
        son(joueur, SoundEvents.PLAYER_ATTACK_STRONG, 1F, 0.6F);
        son(joueur, SoundEvents.SAND_HIT, 1.4F, 0.6F);
        Vec3 devant = Pouvoirs.devant(joueur);
        ServerLevel monde = joueur.serverLevel();
        for (LivingEntity cible : monde.getEntitiesOfClass(LivingEntity.class, joueur.getBoundingBox().inflate(PORTEE_COUP, 2.5, PORTEE_COUP),
                c -> Pouvoirs.cible(joueur, c) && !tenu(c))) {
            Vec3 plat = new Vec3(cible.getX() - joueur.getX(), 0, cible.getZ() - joueur.getZ());
            if (plat.length() > PORTEE_COUP || (plat.lengthSqr() > 0.01 && plat.normalize().dot(devant) < 0.35)) {
                continue;
            }
            cible.hurt(joueur.damageSources().playerAttack(joueur), COUP);
            cible.knockback(1.6, -devant.x, -devant.z);
            cible.setDeltaMovement(cible.getDeltaMovement().add(0, 0.25, 0));
            cible.hurtMarked = true;
            monde.sendParticles(sable(), cible.getX(), cible.getY(0.6), cible.getZ(), 26, 0.4, 0.5, 0.4, 0.2);
            monde.sendParticles(poussiere(), cible.getX(), cible.getY(0.6), cible.getZ(), 10, 0.4, 0.4, 0.4, 0.05);
            monde.playSound(null, cible.getX(), cible.getY() + 1, cible.getZ(), SoundEvents.GRAVEL_BREAK, SoundSource.PLAYERS, 1.2F, 0.6F);
        }
    }

    // ================================================================ clic droit : la main attrape, puis serre

    /** Ce qu'une main d'Ichibi tient déjà (on ne l'attrape pas deux fois, on ne le frappe pas dans la main). */
    private static boolean tenu(LivingEntity c) {
        return ETATS.values().stream().anyMatch(x -> x.prise == c.getId());
    }

    static void attraper(ServerPlayer joueur) {
        Etat e = ETATS.get(joueur.getUUID());
        if (!pret(joueur, e)) {
            return;
        }
        if (e.lacher >= 0) {
            return;
        }
        if (e.prise >= 0) {
            serrer(joueur, e);
            return;
        }
        int maintenant = joueur.server.getTickCount();
        if (maintenant < e.priseRecharge) {
            joueur.displayClientMessage(Component.literal("La main de sable se reforme… " + (e.priseRecharge - maintenant + 19) / 20 + " s")
                    .withStyle(ChatFormatting.GRAY), true);
            return;
        }
        // la créature la plus près dans son regard, jusqu'à 8 blocs
        Vec3 regard = joueur.getLookAngle();
        Vec3 plat = new Vec3(regard.x, 0, regard.z);
        Vec3 devant = plat.lengthSqr() < 1e-4 ? Pouvoirs.devant(joueur) : plat.normalize();
        LivingEntity proie = null;
        double meilleure = Double.MAX_VALUE;
        for (LivingEntity c : joueur.serverLevel().getEntitiesOfClass(LivingEntity.class, joueur.getBoundingBox().inflate(PORTEE_PRISE, 4, PORTEE_PRISE),
                c -> Pouvoirs.cible(joueur, c) && !tenu(c) && !c.isSpectator())) {
            Vec3 vers = new Vec3(c.getX() - joueur.getX(), 0, c.getZ() - joueur.getZ());
            double d = vers.length();
            if (d <= PORTEE_PRISE && d < meilleure && (d < 0.6 || vers.normalize().dot(devant) > 0.5)) {
                meilleure = d;
                proie = c;
            }
        }
        if (proie == null) {
            joueur.displayClientMessage(Component.literal("Personne à attraper devant toi.").withStyle(ChatFormatting.GRAY), true);
            return;
        }
        e.prise = proie.getId();
        e.priseDebut = maintenant;
        e.priseDepart = proie.position();
        e.priseRecharge = maintenant + RECHARGE_PRISE;
        Pouvoirs.geste(joueur, GESTE_PRISE, proie.getId());
        ServerLevel monde = joueur.serverLevel();
        monde.playSound(null, proie.getX(), proie.getY() + 1, proie.getZ(), SoundEvents.SAND_PLACE, SoundSource.PLAYERS, 1.6F, 0.5F);
        monde.playSound(null, proie.getX(), proie.getY() + 1, proie.getZ(), SoundEvents.SLIME_ATTACK, SoundSource.PLAYERS, 1.2F, 0.5F);
        monde.sendParticles(sable(), proie.getX(), proie.getY(0.5), proie.getZ(), 30, 0.5, 0.6, 0.5, 0.15);
    }

    /** Où la main tient sa prise (le milieu de son corps) : devant lui, un peu à droite, soulevée. */
    public static Vec3 main(LivingEntity joueur) {
        float lacet = joueur.yBodyRot * Mth.DEG_TO_RAD;
        Vec3 devant = new Vec3(-Mth.sin(lacet), 0, Mth.cos(lacet));
        Vec3 droite = new Vec3(-Mth.cos(lacet), 0, -Mth.sin(lacet));
        return joueur.position().add(devant.scale(2.6)).add(droite.scale(0.45)).add(0, 1.85, 0);
    }

    private static void lacher(ServerPlayer joueur, Etat e) {
        e.lacher = -1;
        if (e.prise >= 0) {
            e.prise = -1;
            Pouvoirs.geste(joueur, GESTE_PRISE, -1);
        }
    }

    /** Avec une prise, clic droit : la main serre de toutes ses forces, puis la laisse tomber (voir SERRER). */
    private static void serrer(ServerPlayer joueur, Etat e) {
        int prise = e.prise;
        ServerLevel monde = joueur.serverLevel();
        if (monde.getEntity(prise) instanceof LivingEntity proie && proie.isAlive()) {
            proie.invulnerableTime = 0;
            proie.hurt(joueur.damageSources().playerAttack(joueur), SERRE);
            proie.fallDistance = 0;
            monde.sendParticles(sable(), proie.getX(), proie.getY(0.6), proie.getZ(), 50, 0.5, 0.6, 0.5, 0.25);
            monde.sendParticles(ParticleTypes.DAMAGE_INDICATOR, proie.getX(), proie.getY(0.6), proie.getZ(), 8, 0.4, 0.4, 0.4, 0.1);
            monde.playSound(null, proie.getX(), proie.getY() + 1, proie.getZ(), SoundEvents.SAND_BREAK, SoundSource.PLAYERS, 1.6F, 0.5F);
            monde.playSound(null, proie.getX(), proie.getY() + 1, proie.getZ(), SoundEvents.SKELETON_HURT, SoundSource.PLAYERS, 1F, 0.5F);
        }
        Pouvoirs.geste(joueur, GESTE_SERRE, prise);
        e.lacher = joueur.server.getTickCount() + SERRER;
    }

    /** Avec une prise, clic gauche : la main la jette loin, là où il regarde. */
    private static void lancer(ServerPlayer joueur, Etat e) {
        int prise = e.prise;
        ServerLevel monde = joueur.serverLevel();
        if (monde.getEntity(prise) instanceof LivingEntity proie && proie.isAlive()) {
            proie.invulnerableTime = 0;
            proie.hurt(joueur.damageSources().playerAttack(joueur), LANCE);
            proie.setDeltaMovement(joueur.getLookAngle().scale(2.6).add(0, 0.55, 0));
            proie.hurtMarked = true;
            proie.fallDistance = 0;
            monde.sendParticles(sable(), proie.getX(), proie.getY(0.5), proie.getZ(), 30, 0.4, 0.5, 0.4, 0.2);
            monde.playSound(null, proie.getX(), proie.getY() + 1, proie.getZ(), SoundEvents.PLAYER_ATTACK_KNOCKBACK, SoundSource.PLAYERS, 1.4F, 0.6F);
            monde.playSound(null, proie.getX(), proie.getY() + 1, proie.getZ(), SoundEvents.SAND_BREAK, SoundSource.PLAYERS, 1.2F, 0.7F);
        }
        Pouvoirs.geste(joueur, GESTE_LANCE, prise);
        e.prise = -1;
    }

    /** La prise est ramenée jusqu'à la main, la suit et se fait écraser ; au bout de 8 secondes, la main la lâche. */
    private static void tenir(ServerPlayer joueur, Etat e, int maintenant) {
        int age = maintenant - e.priseDebut;
        if (!(joueur.serverLevel().getEntity(e.prise) instanceof LivingEntity proie) || !proie.isAlive() || proie.distanceTo(joueur) > 16
                || age > DUREE_PRISE) {
            lacher(joueur, e);
            return;
        }
        Vec3 ici = main(joueur);
        if (age < ARRACHER) {
            float k = age / (float) ARRACHER;
            ici = e.priseDepart.add(0, proie.getBbHeight() / 2, 0).lerp(ici, k * k * (3 - 2 * k));
        }
        proie.teleportTo(ici.x, ici.y - proie.getBbHeight() / 2, ici.z);
        proie.setDeltaMovement(Vec3.ZERO);
        proie.fallDistance = 0;
        if (age > ARRACHER && age % 20 == 0) {
            proie.invulnerableTime = 0;
            proie.hurt(joueur.damageSources().playerAttack(joueur), ECRASE);
            joueur.serverLevel().sendParticles(sable(), proie.getX(), proie.getY(0.6), proie.getZ(), 6, 0.4, 0.4, 0.4, 0.05);
        }
    }

    // ================================================================ shift : le nuage de sable

    static void nuage(ServerPlayer joueur) {
        Etat e = ETATS.get(joueur.getUUID());
        if (!pret(joueur, e)) {
            return;
        }
        int maintenant = joueur.server.getTickCount();
        if (e.nuageFin >= 0) {
            // déjà dessus (deux appuis rapides sur s'accroupir) : il en descend, sauf s'il vient juste de se former
            if (maintenant > e.nuageFin - DUREE_NUAGE + 10) {
                finNuage(joueur, e, maintenant);
            }
            return;
        }
        if (maintenant < e.nuageRecharge) {
            joueur.displayClientMessage(Component.literal("Le nuage de sable se reforme… " + (e.nuageRecharge - maintenant + 19) / 20 + " s")
                    .withStyle(ChatFormatting.GRAY), true);
            return;
        }
        e.nuageFin = maintenant + DUREE_NUAGE;
        POSES.remove(joueur.getUUID());
        joueur.removeEffect(MobEffects.SLOW_FALLING);
        Abilities a = joueur.getAbilities();
        if (!joueur.isCreative() && !joueur.isSpectator()) {
            a.mayfly = true;
            a.setFlyingSpeed(VITESSE_VOL);
        }
        a.flying = true;
        joueur.onUpdateAbilities();
        // le sable monte sous ses pieds et le soulève
        joueur.setDeltaMovement(joueur.getDeltaMovement().x, 0.55, joueur.getDeltaMovement().z);
        joueur.hurtMarked = true;
        joueur.fallDistance = 0;
        Pouvoirs.geste(joueur, GESTE_NUAGE, 1);
        ServerLevel monde = joueur.serverLevel();
        monde.sendParticles(sable(), joueur.getX(), joueur.getY() + 0.2, joueur.getZ(), 70, 1.2, 0.3, 1, 0.12);
        son(joueur, SoundEvents.SAND_PLACE, 1.6F, 0.6F);
        son(joueur, SoundEvents.SAND_BREAK, 1.4F, 0.5F);
    }

    /** Le nuage s'effrite : il le pose doucement au sol, et se reforme en 30 s. */
    private static void finNuage(ServerPlayer joueur, Etat e, int maintenant) {
        e.nuageFin = -1;
        e.nuageRecharge = maintenant + RECHARGE_NUAGE;
        Abilities a = joueur.getAbilities();
        if (!joueur.isCreative() && !joueur.isSpectator()) {
            a.mayfly = false;
            a.flying = false;
            a.setFlyingSpeed(0.05F);
            joueur.onUpdateAbilities();
            if (!joueur.onGround()) {
                joueur.addEffect(new MobEffectInstance(MobEffects.SLOW_FALLING, ATTERRISSAGE, 0, false, false));
                POSES.put(joueur.getUUID(), maintenant + ATTERRISSAGE);
            }
        }
        joueur.fallDistance = 0;
        Pouvoirs.geste(joueur, GESTE_NUAGE, 0);
        joueur.serverLevel().sendParticles(sable(), joueur.getX(), joueur.getY(), joueur.getZ(), 60, 1.2, 0.3, 1, 0.08);
        son(joueur, SoundEvents.SAND_BREAK, 1.4F, 0.7F);
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
            if (e.prise >= 0 && e.lacher >= 0 && maintenant >= e.lacher) {
                lacher(joueur, e);
            }
            if (e.prise >= 0) {
                tenir(joueur, e, maintenant);
            }
            if (e.nuageFin >= 0) {
                if (maintenant >= e.nuageFin) {
                    finNuage(joueur, e, maintenant);
                } else {
                    // le nuage le porte : il vole tant qu'il dure (même s'il touche le sol), et du sable en tombe
                    if (!joueur.getAbilities().flying) {
                        joueur.getAbilities().flying = true;
                        joueur.onUpdateAbilities();
                    }
                    joueur.fallDistance = 0;
                    if (maintenant % 3 == 0) {
                        joueur.serverLevel().sendParticles(new BlockParticleOption(ParticleTypes.FALLING_DUST, Blocks.SAND.defaultBlockState()),
                                joueur.getX(), joueur.getY() - 0.35, joueur.getZ(), 5, 1.1, 0.05, 0.8, 0);
                    }
                }
            }
            if (maintenant % 8 == 0) {
                joueur.serverLevel().sendParticles(poussiere(), joueur.getX(), joueur.getY(0.55), joueur.getZ(), 2, 0.5, 0.6, 0.5, 0.01);
            }
        }
        // ceux que le nuage a lâchés : la chute lente s'arrête quand ils touchent le sol
        POSES.entrySet().removeIf(en -> {
            ServerPlayer joueur = server.getPlayerList().getPlayer(en.getKey());
            if (joueur == null) {
                return true;
            }
            joueur.fallDistance = 0;
            if (joueur.onGround() || joueur.isInWater() || maintenant >= en.getValue()) {
                joueur.removeEffect(MobEffects.SLOW_FALLING);
                return true;
            }
            return false;
        });
    }

    /** Le serveur s'arrête : ceux qui volent sur leur nuage ne doivent pas garder le vol à la reconnexion. */
    @SubscribeEvent
    public static void arret(ServerStoppingEvent event) {
        for (UUID uuid : new ArrayList<>(ETATS.keySet())) {
            ServerPlayer joueur = event.getServer().getPlayerList().getPlayer(uuid);
            if (joueur != null) {
                endormir(joueur);
            }
        }
        ETATS.clear();
        POSES.clear();
    }

    private Ichibi() {
    }
}
