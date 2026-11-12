package fr.cabane.donjon;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.BossEvent;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

/**
 * Ugrak, l'Ogre Écarlate : le boss du donjon de rang S. Dix blocs de muscles rouges fendus de lave, des cornes, un
 * couperet de fer noir aux runes rouges. Il dort agenouillé devant le trône ; quand quelqu'un entre dans l'arène, il se
 * lève et rugit, et sa barre de vie apparaît en haut de l'écran.
 *
 * <p>Ses attaques : le coup de couperet devant lui ; le saut, qui retombe sur un joueur avec une onde de choc ; la charge
 * à travers l'arène (s'il percute un mur, il reste étourdi et prend plus de dégâts) ; à la moitié de sa vie, il entre en
 * rage (plus rapide, plus fort) et fait jaillir la lave sous les pieds de tout le monde. Il s'acharne sur celui qui lui
 * fait le plus mal. Si tout le monde quitte l'arène, il se soigne et retourne dormir.
 *
 * <p>Sa vie : la vie de Minecraft s'arrête à 1 024 ; il en a 1 000 et ne prend qu'un sixième des dégâts, soit 6 000 PV
 * « affichés ».
 */
public class Ogre extends Monster {
    /** Les PV affichés pour 1 PV réel. */
    public static final float ECHELLE_PV = 6;
    public static final float VIE = 1000;
    public static final String NOM = "Ugrak, l'Ogre Écarlate";

    public static final int SOMMEIL = 0, REVEIL = 1, CHASSE = 2, FRAPPE = 3, SAUT = 4, CHARGE = 5, ETOURDI = 6, RAGE = 7, ERUPTION = 8, INVOCATION = 9;
    public static final int DUREE_INVOCATION = 46;
    /** Les seuils (part de sa vie) où il appelle ses sbires. */
    private static final float[] SEUILS = {0.75F, 0.5F, 0.25F};
    public static final int DUREE_REVEIL = 70, DUREE_FRAPPE = 26, IMPACT_FRAPPE = 15, PREPA_CHARGE = 22, DUREE_CHARGE = 62, DUREE_ETOURDI = 70,
            DUREE_RAGE = 60, DUREE_ERUPTION = 44, DUREE_MORT = 60;

    private static final EntityDataAccessor<Integer> ETAT = SynchedEntityData.defineId(Ogre.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Boolean> ENRAGE = SynchedEntityData.defineId(Ogre.class, EntityDataSerializers.BOOLEAN);

    /** Côté client : le tick où l'état a changé (le dessin en tire le geste). */
    public int debutClient;
    private int debut;
    private int frappeCd = 20, sautCd = 120, chargeCd = 200, eruptionCd = 120;
    private int seul;
    private Vec3 dirCharge = Vec3.ZERO;
    private final List<UUID> touchesCharge = new ArrayList<>();
    private final List<Vec3> eruptions = new ArrayList<>();
    private final Map<UUID, Float> menace = new HashMap<>();
    private Player cible;
    /** Combien de seuils d'invocation sont déjà passés. */
    private int invocations;
    private int renfortCd = 600;
    private boolean atterri;
    private final ServerBossEvent barre = new ServerBossEvent(Component.literal(NOM), BossEvent.BossBarColor.RED, BossEvent.BossBarOverlay.NOTCHED_10);

    public Ogre(EntityType<? extends Ogre> type, Level level) {
        super(type, level);
        setPersistenceRequired();
        xpReward = 500;
        barre.setDarkenScreen(true);
        barre.setPlayBossMusic(true);
    }

    public static AttributeSupplier.Builder attributs() {
        return Monster.createMonsterAttributes().add(Attributes.MAX_HEALTH, VIE).add(Attributes.ATTACK_DAMAGE, 12).add(Attributes.ARMOR, 12)
                .add(Attributes.ARMOR_TOUGHNESS, 6).add(Attributes.MOVEMENT_SPEED, 0.3).add(Attributes.FOLLOW_RANGE, 64)
                .add(Attributes.KNOCKBACK_RESISTANCE, 1).add(Attributes.STEP_HEIGHT, 2.1).add(Attributes.SAFE_FALL_DISTANCE, 64);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(ETAT, SOMMEIL);
        builder.define(ENRAGE, false);
    }

    @Override
    public void onSyncedDataUpdated(EntityDataAccessor<?> cle) {
        super.onSyncedDataUpdated(cle);
        if (ETAT.equals(cle)) {
            debutClient = tickCount;
        }
    }

    public int etat() {
        return entityData.get(ETAT);
    }

    public boolean enrage() {
        return entityData.get(ENRAGE);
    }

    /** Depuis combien de ticks il est dans cet état (côté serveur). */
    private int age() {
        return tickCount - debut;
    }

    private void passer(int etat) {
        entityData.set(ETAT, etat);
        debut = tickCount;
        getNavigation().stop();
    }

    @Override
    protected void registerGoals() {
        // pas de buts : son comportement est écrit à la main dans customServerAiStep
    }

    @Override
    public boolean removeWhenFarAway(double distance) {
        return false;
    }

    /** Le donjon marche aussi en Paisible : ses monstres y restent. */
    @Override
    protected boolean shouldDespawnInPeaceful() {
        return false;
    }

    @Override
    public boolean causeFallDamage(float distance, float multiplicateur, DamageSource source) {
        return false;
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    /** En rage, des flammes montent de ses épaules et de ses fissures (côté client). */
    @Override
    public void tick() {
        super.tick();
        if (level().isClientSide && enrage() && isAlive()) {
            for (int k = 0; k < 2; k++) {
                double ang = Math.toRadians(yBodyRot) + (random.nextBoolean() ? 1 : -1) * 1.5;
                double x = getX() - Math.sin(ang) * 2.9 * (random.nextDouble() * 0.5 + 0.6), z = getZ() + Math.cos(ang) * 2.9 * (random.nextDouble() * 0.5 + 0.6);
                level().addParticle(ParticleTypes.FLAME, x, getY() + 6.3 + random.nextDouble() * 2, z, 0, 0.06, 0);
            }
            if (random.nextInt(3) == 0) {
                level().addParticle(ParticleTypes.LAVA, getX() + random.nextGaussian() * 1.2, getY() + 5 + random.nextDouble() * 3, getZ() + random.nextGaussian() * 1.2, 0, 0, 0);
            }
        }
    }

    /** Ses coups : un type de dégâts à lui, qui ne baisse pas avec la difficulté (sinon il ne fait rien en Paisible). */
    static final net.minecraft.resources.ResourceKey<net.minecraft.world.damagesource.DamageType> COUP =
            net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.DAMAGE_TYPE, fr.cabane.Cabane.id("coup_donjon"));

    static DamageSource coup(net.minecraft.world.entity.LivingEntity auteur) {
        // si le type manque (un pack de données pas à jour), un coup de monstre ordinaire plutôt qu'un plantage
        return auteur.level().registryAccess().registryOrThrow(net.minecraft.core.registries.Registries.DAMAGE_TYPE).getHolder(COUP)
                .map(t -> new DamageSource(t, auteur)).orElseGet(() -> auteur.damageSources().mobAttack(auteur));
    }

    private DamageSource coup() {
        return coup(this);
    }

    // ---------------------------------------------------------------- les dégâts

    @Override
    public boolean hurt(DamageSource source, float degats) {
        int e = etat();
        if (e == SOMMEIL || e == REVEIL || e == RAGE) {
            if (source.getEntity() instanceof Player && !level().isClientSide) {
                level().playSound(null, getX(), getY() + 3, getZ(), SoundEvents.ANVIL_PLACE, SoundSource.HOSTILE, 0.6F, 0.6F);
            }
            return source.is(net.minecraft.tags.DamageTypeTags.BYPASSES_INVULNERABILITY) && super.hurt(source, degats);
        }
        float d = degats / ECHELLE_PV;
        if (e == ETOURDI) {
            d *= 1.5F;
        }
        if (source.getEntity() instanceof Player p) {
            menace.merge(p.getUUID(), degats, Float::sum);
        }
        return super.hurt(source, d);
    }

    // ---------------------------------------------------------------- le cerveau

    private boolean valide(Player p) {
        return p.isAlive() && !p.isSpectator() && !p.isCreative() && p.distanceToSqr(DonjonBuilder.ARENE) < 34 * 34;
    }

    private List<Player> dansArene() {
        List<Player> l = new ArrayList<>();
        for (Player p : level().players()) {
            if (valide(p)) {
                l.add(p);
            }
        }
        return l;
    }

    /** Celui qui lui a fait le plus mal ces derniers temps, sinon le plus proche. */
    private Player choisirCible(List<Player> joueurs) {
        Player meilleur = null;
        float max = 0;
        for (Player p : joueurs) {
            float m = menace.getOrDefault(p.getUUID(), 0F);
            if (m > max) {
                max = m;
                meilleur = p;
            }
        }
        if (meilleur != null && max > 30) {
            return meilleur;
        }
        Player proche = null;
        double d = Double.MAX_VALUE;
        for (Player p : joueurs) {
            double dd = p.distanceToSqr(this);
            if (dd < d) {
                d = dd;
                proche = p;
            }
        }
        return proche;
    }

    private void regarder(Vec3 point) {
        double dx = point.x - getX(), dz = point.z - getZ();
        float lacet = (float) (Mth.atan2(dz, dx) * Mth.RAD_TO_DEG) - 90F;
        setYRot(lacet);
        yBodyRot = lacet;
        yHeadRot = lacet;
    }

    @Override
    protected void customServerAiStep() {
        super.customServerAiStep();
        ServerLevel sl = (ServerLevel) level();
        List<Player> joueurs = dansArene();
        if (tickCount % 5 == 0) {
            majBarre(sl);
        }
        // la menace s'oublie peu à peu
        if (tickCount % 20 == 0) {
            menace.replaceAll((u, m) -> m * 0.85F);
        }
        int e = etat();
        if (e == SOMMEIL) {
            setDeltaMovement(Vec3.ZERO);
            regarder(DonjonBuilder.ARENE);
            if (!joueurs.isEmpty() && joueurs.stream().anyMatch(p -> p.distanceToSqr(DonjonBuilder.ARENE) < 23 * 23)) {
                reveiller(sl, joueurs);
            }
            return;
        }
        // personne dans l'arène depuis 20 s : il se soigne et retourne dormir
        if (joueurs.isEmpty()) {
            if (++seul > 400) {
                rendormir();
            }
            return;
        }
        seul = 0;
        if (tickCount % 20 == 0 || cible == null || !valide(cible)) {
            cible = choisirCible(joueurs);
        }
        if (cible == null) {
            return;
        }
        // il ne sort pas de l'arène
        if (distanceToSqr(DonjonBuilder.ARENE) > 30 * 30) {
            teleportTo(DonjonBuilder.ARENE.x, DonjonBuilder.ARENE.y, DonjonBuilder.ARENE.z);
        }
        frappeCd--;
        sautCd--;
        chargeCd--;
        eruptionCd--;
        renfortCd--;
        // ses sbires : à chaque seuil de vie, et de temps en temps quand il est en rage
        if ((e == CHASSE) && ((invocations < SEUILS.length && getHealth() < VIE * SEUILS[invocations]) || (enrage() && renfortCd <= 0))) {
            if (invocations < SEUILS.length && getHealth() < VIE * SEUILS[invocations]) {
                invocations++;
            }
            renfortCd = 700;
            passer(INVOCATION);
            e = INVOCATION;
        }
        // la rage, à la moitié de sa vie (après les sbires du seuil de la moitié)
        if (e != INVOCATION && !enrage() && getHealth() < VIE / 2 && e != SAUT && e != CHARGE && e != RAGE) {
            passer(RAGE);
            e = RAGE;
        }
        switch (e) {
            case REVEIL -> reveil(sl);
            case CHASSE -> chasse(sl);
            case FRAPPE -> frappe(sl);
            case SAUT -> saut(sl, joueurs);
            case CHARGE -> charge(sl, joueurs);
            case ETOURDI -> etourdi(sl);
            case RAGE -> rage(sl, joueurs);
            case ERUPTION -> eruption(sl, joueurs);
            case INVOCATION -> invocation(sl, joueurs);
            default -> passer(CHASSE);
        }
    }

    private void reveiller(ServerLevel sl, List<Player> joueurs) {
        passer(REVEIL);
        Enigmes.debutCombat(sl);
        setHealth(getMaxHealth());
        invocations = 0;
        menace.clear();
        Boss.annoncer(sl, "ALERTE", new String[] {"Le boss du donjon s'est réveillé."}, NOM.toUpperCase(), 0xFFFF3B3B);
    }

    private void reveil(ServerLevel sl) {
        if (age() == 34) {
            sl.playSound(null, getX(), getY() + 4, getZ(), SoundEvents.RAVAGER_ROAR, SoundSource.HOSTILE, 4F, 0.5F);
            sl.playSound(null, getX(), getY() + 4, getZ(), SoundEvents.WARDEN_ROAR, SoundSource.HOSTILE, 2.5F, 0.7F);
            sl.sendParticles(ParticleTypes.LAVA, getX(), getY() + 5, getZ(), 30, 1.5, 1, 1.5, 0.2);
        }
        if (age() >= DUREE_REVEIL) {
            passer(CHASSE);
        }
    }

    private void chasse(ServerLevel sl) {
        double d = distanceTo(cible);
        double vitesse = enrage() ? 1.3 : 1.0;
        getNavigation().moveTo(cible, vitesse);
        getLookControl().setLookAt(cible, 30, 30);
        if (d < 6.6 && frappeCd <= 0) {
            regarder(cible.position());
            passer(FRAPPE);
            return;
        }
        if (enrage() && eruptionCd <= 0) {
            passer(ERUPTION);
            return;
        }
        if (sautCd <= 0 && d > 6 && d < 22) {
            regarder(cible.position());
            passer(SAUT);
            return;
        }
        if (chargeCd <= 0 && d > 8) {
            regarder(cible.position());
            passer(CHARGE);
            touchesCharge.clear();
            return;
        }
        // de lourds pas qui font trembler le sol
        if (getDeltaMovement().horizontalDistanceSqr() > 0.003 && tickCount % 12 == 0) {
            sl.playSound(null, getX(), getY(), getZ(), SoundEvents.RAVAGER_STEP, SoundSource.HOSTILE, 1.6F, 0.5F);
        }
    }

    /** Le coup de couperet : le bras se lève, s'abat devant lui ; tout ce qui est dans l'arc prend cher. */
    private void frappe(ServerLevel sl) {
        int a = age();
        if (a < IMPACT_FRAPPE && cible != null) {
            regarder(cible.position());
        }
        if (a == 4) {
            sl.playSound(null, getX(), getY() + 4, getZ(), SoundEvents.RAVAGER_ATTACK, SoundSource.HOSTILE, 2F, 0.6F);
        }
        if (a == IMPACT_FRAPPE) {
            Vec3 avant = Vec3.directionFromRotation(0, getYRot());
            Vec3 point = position().add(avant.scale(5.2));
            for (Player p : dansArene()) {
                Vec3 vers = p.position().subtract(position());
                double dist = vers.horizontalDistance();
                if (dist < 8 && vers.normalize().dot(avant) > 0.25) {
                    p.hurt(coup(), enrage() ? 18 : 14);
                    p.push(avant.x * 1.3, 0.45, avant.z * 1.3);
                    p.hurtMarked = true;
                }
            }
            sl.playSound(null, point.x, point.y, point.z, SoundEvents.ANVIL_LAND, SoundSource.HOSTILE, 1.6F, 0.5F);
            sl.playSound(null, point.x, point.y, point.z, SoundEvents.GENERIC_EXPLODE.value(), SoundSource.HOSTILE, 0.9F, 1.2F);
            sl.sendParticles(ParticleTypes.EXPLOSION, point.x, point.y + 0.3, point.z, 2, 0.4, 0.1, 0.4, 0);
            sl.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, Blocks.POLISHED_BLACKSTONE.defaultBlockState()), point.x, point.y + 0.2,
                    point.z, 40, 1, 0.2, 1, 0.3);
        }
        if (a >= DUREE_FRAPPE) {
            frappeCd = enrage() ? 18 : 28;
            passer(CHASSE);
        }
    }

    /** Le saut : il s'accroupit, bondit vers sa cible, et retombe de tout son poids (onde de choc). */
    private void saut(ServerLevel sl, List<Player> joueurs) {
        int a = age();
        if (a < 12) {
            setDeltaMovement(0, getDeltaMovement().y, 0);
            if (cible != null) {
                regarder(cible.position());
            }
            if (a == 2) {
                sl.playSound(null, getX(), getY() + 4, getZ(), SoundEvents.RAVAGER_ROAR, SoundSource.HOSTILE, 2.5F, 0.75F);
            }
            atterri = false;
            return;
        }
        if (a == 12) {
            Vec3 but = cible != null ? cible.position() : DonjonBuilder.ARENE;
            Vec3 d = but.subtract(position());
            double temps = 18;
            setDeltaMovement(d.x / temps, 1.05, d.z / temps);
            hasImpulse = true;
            sl.sendParticles(ParticleTypes.CAMPFIRE_COSY_SMOKE, getX(), getY() + 0.2, getZ(), 20, 1.2, 0.1, 1.2, 0.02);
            return;
        }
        if (!atterri && a > 16 && (onGround() || a > 50)) {
            atterri = true;
            double rayon = enrage() ? 12 : 10;
            for (Player p : joueurs) {
                double dist = p.distanceTo(this);
                if (dist < rayon) {
                    float k = (float) (1 - dist / rayon * 0.6);
                    p.hurt(coup(), (enrage() ? 15 : 12) * k);
                    Vec3 dehors = p.position().subtract(position()).multiply(1, 0, 1).normalize();
                    p.push(dehors.x * 1.1, 0.75, dehors.z * 1.1);
                    p.hurtMarked = true;
                }
            }
            onde(sl, rayon);
            sl.playSound(null, getX(), getY(), getZ(), SoundEvents.GENERIC_EXPLODE.value(), SoundSource.HOSTILE, 2.5F, 0.6F);
            sl.playSound(null, getX(), getY(), getZ(), SoundEvents.WARDEN_SONIC_BOOM, SoundSource.HOSTILE, 1.5F, 0.5F);
        }
        if (atterri && a > 16 && age() > 16) {
            // un temps pour se relever
            if (++seulAtterri > 18) {
                seulAtterri = 0;
                sautCd = enrage() ? 110 : 170;
                passer(CHASSE);
            }
        }
    }

    private int seulAtterri;

    /** L'onde de choc : un anneau de poussière et de flammes qui s'élargit au sol. */
    private void onde(ServerLevel sl, double rayon) {
        sl.sendParticles(ParticleTypes.EXPLOSION_EMITTER, getX(), getY() + 0.5, getZ(), 1, 0, 0, 0, 0);
        for (int k = 0; k < 48; k++) {
            double a = k * Math.PI * 2 / 48;
            for (double r = 2; r <= rayon; r += 2.2) {
                double x = getX() + Math.cos(a) * r, z = getZ() + Math.sin(a) * r;
                sl.sendParticles(ParticleTypes.CAMPFIRE_COSY_SMOKE, x, getY() + 0.2, z, 1, 0.1, 0.05, 0.1, 0.01);
                if (enrage()) {
                    sl.sendParticles(ParticleTypes.FLAME, x, getY() + 0.2, z, 1, 0.1, 0.1, 0.1, 0.02);
                }
            }
            sl.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, Blocks.POLISHED_BLACKSTONE.defaultBlockState()),
                    getX() + Math.cos(a) * rayon, getY() + 0.3, getZ() + Math.sin(a) * rayon, 3, 0.2, 0.2, 0.2, 0.3);
        }
    }

    /** La charge : un rugissement, puis il fonce tout droit. Contre un mur, il reste sonné. */
    private void charge(ServerLevel sl, List<Player> joueurs) {
        int a = age();
        if (a < PREPA_CHARGE) {
            setDeltaMovement(0, getDeltaMovement().y, 0);
            if (cible != null) {
                regarder(cible.position());
            }
            if (a == 1) {
                sl.playSound(null, getX(), getY() + 4, getZ(), SoundEvents.RAVAGER_ROAR, SoundSource.HOSTILE, 3F, 0.55F);
            }
            if (a % 3 == 0) {
                sl.sendParticles(ParticleTypes.CAMPFIRE_COSY_SMOKE, getX(), getY() + 0.2, getZ(), 4, 1, 0.05, 1, 0.02);
            }
            if (a == PREPA_CHARGE - 1) {
                dirCharge = Vec3.directionFromRotation(0, getYRot()).normalize();
            }
            return;
        }
        double vitesse = enrage() ? 1.0 : 0.85;
        setDeltaMovement(dirCharge.x * vitesse, getDeltaMovement().y, dirCharge.z * vitesse);
        regarder(position().add(dirCharge));
        if (a % 4 == 0) {
            sl.playSound(null, getX(), getY(), getZ(), SoundEvents.RAVAGER_STEP, SoundSource.HOSTILE, 2F, 0.6F);
        }
        sl.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, Blocks.POLISHED_BLACKSTONE.defaultBlockState()), getX(), getY() + 0.1, getZ(),
                6, 0.8, 0.1, 0.8, 0.1);
        for (Player p : joueurs) {
            if (!touchesCharge.contains(p.getUUID()) && p.distanceTo(this) < 4) {
                touchesCharge.add(p.getUUID());
                p.hurt(coup(), enrage() ? 20 : 17);
                Vec3 cote = p.position().subtract(position()).multiply(1, 0, 1).normalize();
                p.push(dirCharge.x * 1.6 + cote.x * 0.6, 0.8, dirCharge.z * 1.6 + cote.z * 0.6);
                p.hurtMarked = true;
            }
        }
        if (horizontalCollision && a > PREPA_CHARGE + 3) {
            // le mur : il est sonné
            sl.playSound(null, getX(), getY() + 3, getZ(), SoundEvents.ZOMBIE_ATTACK_IRON_DOOR, SoundSource.HOSTILE, 2.5F, 0.5F);
            sl.playSound(null, getX(), getY() + 3, getZ(), SoundEvents.GENERIC_EXPLODE.value(), SoundSource.HOSTILE, 1.5F, 0.8F);
            Vec3 choc = position().add(dirCharge.scale(2.2)).add(0, 4.5, 0);
            sl.sendParticles(ParticleTypes.EXPLOSION, choc.x, choc.y, choc.z, 3, 0.6, 0.6, 0.6, 0);
            setDeltaMovement(dirCharge.scale(-0.3).add(0, 0.2, 0));
            chargeCd = enrage() ? 150 : 220;
            passer(ETOURDI);
            for (Player p : joueurs) {
                if (p instanceof ServerPlayer sp) {
                    sp.displayClientMessage(Component.literal("Ugrak est sonné ! Il prend plus de dégâts.").withStyle(ChatFormatting.GOLD), true);
                }
            }
            return;
        }
        if (a >= DUREE_CHARGE) {
            chargeCd = enrage() ? 150 : 220;
            passer(CHASSE);
        }
    }

    private void etourdi(ServerLevel sl) {
        setDeltaMovement(0, getDeltaMovement().y, 0);
        if (age() % 5 == 0) {
            sl.sendParticles(ParticleTypes.CRIT, getX(), getY() + 9.9, getZ(), 8, 0.9, 0.2, 0.9, 0.1);
        }
        if (age() >= DUREE_ETOURDI) {
            passer(CHASSE);
        }
    }

    /** La rage : il rugit, repousse tout le monde, et ses fissures de lave s'embrasent. */
    private void rage(ServerLevel sl, List<Player> joueurs) {
        setDeltaMovement(0, getDeltaMovement().y, 0);
        int a = age();
        if (a == 1) {
            Boss.annoncer(sl, "ALERTE", new String[] {"Ugrak entre en rage !", "Attention à la lave sous vos pieds."}, "", 0);
        }
        if (a == 20) {
            entityData.set(ENRAGE, true);
            sl.playSound(null, getX(), getY() + 4, getZ(), SoundEvents.RAVAGER_ROAR, SoundSource.HOSTILE, 4F, 0.4F);
            sl.playSound(null, getX(), getY() + 4, getZ(), SoundEvents.BLAZE_SHOOT, SoundSource.HOSTILE, 2F, 0.5F);
            for (Player p : joueurs) {
                if (p.distanceTo(this) < 14) {
                    p.hurt(coup(), 6);
                    Vec3 dehors = p.position().subtract(position()).multiply(1, 0, 1).normalize();
                    p.push(dehors.x * 2, 0.6, dehors.z * 2);
                    p.hurtMarked = true;
                }
            }
            onde(sl, 14);
            sl.sendParticles(ParticleTypes.LAVA, getX(), getY() + 4, getZ(), 60, 1.5, 1.5, 1.5, 0.3);
        }
        if (a >= DUREE_RAGE) {
            eruptionCd = 60;
            passer(CHASSE);
        }
    }

    /** L'éruption : bras levés, un cercle rouge sous chaque joueur… puis la lave jaillit. */
    private void eruption(ServerLevel sl, List<Player> joueurs) {
        setDeltaMovement(0, getDeltaMovement().y, 0);
        int a = age();
        if (a == 2) {
            eruptions.clear();
            for (Player p : joueurs) {
                eruptions.add(p.position());
            }
            sl.playSound(null, getX(), getY() + 4, getZ(), SoundEvents.RAVAGER_ROAR, SoundSource.HOSTILE, 2F, 0.8F);
        }
        DustParticleOptions rouge = new DustParticleOptions(new Vector3f(1F, 0.15F, 0.05F), 1.6F);
        if (a > 2 && a < 32 && a % 3 == 0) {
            for (Vec3 e : eruptions) {
                for (int k = 0; k < 16; k++) {
                    double ang = k * Math.PI / 8 + a * 0.1;
                    sl.sendParticles(rouge, e.x + Math.cos(ang) * 1.8, e.y + 0.15, e.z + Math.sin(ang) * 1.8, 1, 0, 0, 0, 0);
                }
                sl.sendParticles(ParticleTypes.SMALL_FLAME, e.x, e.y + 0.1, e.z, 2, 0.6, 0, 0.6, 0.01);
            }
        }
        if (a == 32) {
            for (Vec3 e : eruptions) {
                sl.playSound(null, e.x, e.y, e.z, SoundEvents.GENERIC_EXPLODE.value(), SoundSource.HOSTILE, 1.4F, 0.9F);
                sl.playSound(null, e.x, e.y, e.z, SoundEvents.BLAZE_SHOOT, SoundSource.HOSTILE, 1.4F, 0.6F);
                sl.sendParticles(ParticleTypes.LAVA, e.x, e.y + 0.5, e.z, 25, 0.8, 0.5, 0.8, 0.3);
                sl.sendParticles(ParticleTypes.FLAME, e.x, e.y + 1.5, e.z, 60, 0.5, 1.6, 0.5, 0.08);
                sl.sendParticles(ParticleTypes.EXPLOSION, e.x, e.y + 0.5, e.z, 1, 0, 0, 0, 0);
                for (Player p : joueurs) {
                    if (p.position().distanceToSqr(e) < 2.2 * 2.2) {
                        p.hurt(coup(), 13);
                        p.igniteForSeconds(4);
                        p.push(0, 0.9, 0);
                        p.hurtMarked = true;
                    }
                }
            }
        }
        if (a >= DUREE_ERUPTION) {
            eruptionCd = 200;
            passer(CHASSE);
        }
    }

    /** L'invocation : il lève le poing, rugit, frappe le sol ; ses sbires sortent de terre autour de l'arène. */
    private void invocation(ServerLevel sl, List<Player> joueurs) {
        setDeltaMovement(0, getDeltaMovement().y, 0);
        int a = age();
        if (a == 1) {
            Boss.annoncer(sl, "ALERTE", new String[] {"Ugrak appelle ses sbires !"}, "", 0);
        }
        if (a == 10) {
            sl.playSound(null, getX(), getY() + 6, getZ(), SoundEvents.RAVAGER_ROAR, SoundSource.HOSTILE, 4F, 0.65F);
            sl.playSound(null, getX(), getY() + 6, getZ(), SoundEvents.EVOKER_PREPARE_SUMMON, SoundSource.HOSTILE, 3F, 0.6F);
        }
        if (a == 26) {
            sl.playSound(null, getX(), getY(), getZ(), SoundEvents.GENERIC_EXPLODE.value(), SoundSource.HOSTILE, 2F, 0.6F);
            onde(sl, 6);
            int n = joueurs.size();
            Boss.invoquer(sl, 3 + n, invocations >= 2 ? 1 + n / 2 : (n >= 4 ? 1 : 0));
        }
        if (a >= DUREE_INVOCATION) {
            passer(CHASSE);
        }
    }

    /** Tout le monde est parti : il se soigne et retourne s'agenouiller devant le trône. */
    private void rendormir() {
        seul = 0;
        setHealth(getMaxHealth());
        entityData.set(ENRAGE, false);
        invocations = 0;
        menace.clear();
        Vec3 p = Boss.REPOS;
        teleportTo(p.x, p.y, p.z);
        passer(SOMMEIL);
        barre.removeAllPlayers();
        if (level() instanceof ServerLevel sl) {
            Enigmes.finCombat(sl, false);
        }
    }

    // ---------------------------------------------------------------- la barre de vie

    private void majBarre(ServerLevel sl) {
        if (etat() == SOMMEIL) {
            barre.removeAllPlayers();
            return;
        }
        int pv = Math.round(getHealth() * ECHELLE_PV);
        int max = Math.round(getMaxHealth() * ECHELLE_PV);
        barre.setName(Component.literal(NOM.toUpperCase() + "   ").withStyle(ChatFormatting.RED, ChatFormatting.BOLD)
                .append(Component.literal(String.format("%,d / %,d PV", pv, max).replace(',', ' ')).withStyle(ChatFormatting.WHITE)));
        barre.setProgress(Mth.clamp(getHealth() / getMaxHealth(), 0, 1));
        barre.setColor(enrage() ? BossEvent.BossBarColor.PURPLE : BossEvent.BossBarColor.RED);
        for (ServerPlayer p : sl.players()) {
            if (p.distanceToSqr(DonjonBuilder.ARENE) < 45 * 45) {
                barre.addPlayer(p);
            } else {
                barre.removePlayer(p);
            }
        }
    }

    @Override
    public void stopSeenByPlayer(ServerPlayer p) {
        super.stopSeenByPlayer(p);
        barre.removePlayer(p);
    }

    // ---------------------------------------------------------------- la mort

    @Override
    public void die(DamageSource source) {
        super.die(source);
        barre.removeAllPlayers();
        if (level() instanceof ServerLevel sl) {
            sl.playSound(null, getX(), getY() + 4, getZ(), SoundEvents.RAVAGER_DEATH, SoundSource.HOSTILE, 4F, 0.4F);
            Boss.victoire(sl);
        }
    }

    @Override
    protected void tickDeath() {
        deathTime++;
        if (deathTime == 34 && level() instanceof ServerLevel sl) {
            // il s'écrase face contre terre
            Vec3 avant = Vec3.directionFromRotation(0, getYRot());
            Vec3 p = position().add(avant.scale(6));
            sl.playSound(null, p.x, p.y, p.z, SoundEvents.GENERIC_EXPLODE.value(), SoundSource.HOSTILE, 2F, 0.5F);
            sl.sendParticles(ParticleTypes.CAMPFIRE_COSY_SMOKE, p.x, p.y + 0.3, p.z, 40, 2.5, 0.3, 2.5, 0.03);
        }
        if (deathTime >= DUREE_MORT && !level().isClientSide && !isRemoved()) {
            ServerLevel sl = (ServerLevel) level();
            sl.sendParticles(ParticleTypes.LAVA, getX(), getY() + 1, getZ(), 50, 2, 1, 2, 0.3);
            sl.sendParticles(ParticleTypes.LARGE_SMOKE, getX(), getY() + 1, getZ(), 80, 2.5, 1, 2.5, 0.05);
            remove(RemovalReason.KILLED);
        }
    }
}
