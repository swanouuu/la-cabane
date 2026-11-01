package fr.cabane.vegeta;

import java.util.Comparator;
import net.minecraft.ChatFormatting;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.BossEvent;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

/**
 * Goku Super Saiyan 2 ou Majin Vegeta, en vrai, sur l'île au-dessus de Beauvais. En boss : il vole autour des joueurs,
 * fonce sur eux, lance des rafales de boules d'énergie et, de temps en temps, charge son rayon (Final Flash ou
 * Kaméhaméha) : il faut sortir de la ligne. En allié : il reste en retrait et bombarde le boss.
 */
public class CombattantZ extends Monster {
    public static final int GOKU = 0;
    public static final int VEGETA = 1;
    private static final EntityDataAccessor<Integer> VARIANTE = SynchedEntityData.defineId(CombattantZ.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Boolean> ALLIE = SynchedEntityData.defineId(CombattantZ.class, EntityDataSerializers.BOOLEAN);
    /** 0 : se bat, 1 : charge son rayon, 2 : tire son rayon. */
    private static final EntityDataAccessor<Integer> ETAT = SynchedEntityData.defineId(CombattantZ.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Vector3f> VISEE = SynchedEntityData.defineId(CombattantZ.class, EntityDataSerializers.VECTOR3);

    private final ServerBossEvent barre = new ServerBossEvent(Component.empty(), BossEvent.BossBarColor.YELLOW, BossEvent.BossBarOverlay.NOTCHED_10);
    private int attente = 60;
    private int etatTicks;
    private int rafale;
    private boolean enrage;
    private double angleOrbite = Math.random() * Math.PI * 2;

    public CombattantZ(EntityType<? extends CombattantZ> type, Level level) {
        super(type, level);
        setNoGravity(true);
        setPersistenceRequired();
        xpReward = 200;
    }

    public static AttributeSupplier.Builder attributs() {
        return Monster.createMonsterAttributes().add(Attributes.MAX_HEALTH, 500).add(Attributes.ATTACK_DAMAGE, 9).add(Attributes.ARMOR, 8)
                .add(Attributes.MOVEMENT_SPEED, 0.35).add(Attributes.FOLLOW_RANGE, 64).add(Attributes.KNOCKBACK_RESISTANCE, 1);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(VARIANTE, GOKU);
        builder.define(ALLIE, false);
        builder.define(ETAT, 0);
        builder.define(VISEE, new Vector3f());
    }

    public void preparer(int variante, boolean allie, int joueurs) {
        entityData.set(VARIANTE, variante);
        entityData.set(ALLIE, allie);
        String nom = variante == GOKU ? "Son Goku — Super Saiyan 2" : "Majin Vegeta";
        setCustomName(Component.literal(nom).withStyle(variante == GOKU ? ChatFormatting.GOLD : ChatFormatting.DARK_PURPLE, ChatFormatting.BOLD));
        setCustomNameVisible(true);
        barre.setName(getCustomName());
        barre.setColor(variante == GOKU ? BossEvent.BossBarColor.YELLOW : BossEvent.BossBarColor.PURPLE);
        if (!allie) {
            var vie = getAttribute(Attributes.MAX_HEALTH);
            vie.setBaseValue(350 + 220 * Math.max(1, joueurs));
            setHealth(getMaxHealth());
        }
    }

    public int variante() {
        return entityData.get(VARIANTE);
    }

    public boolean allie() {
        return entityData.get(ALLIE);
    }

    public int etat() {
        return entityData.get(ETAT);
    }

    public Vec3 visee() {
        Vector3f v = entityData.get(VISEE);
        return new Vec3(v.x, v.y, v.z);
    }

    // ---------------------------------------------------------------- le vol

    @Override
    public void travel(Vec3 entree) {
        move(MoverType.SELF, getDeltaMovement());
        setDeltaMovement(getDeltaMovement().scale(0.82));
    }

    @Override
    public boolean causeFallDamage(float distance, float multiplicateur, DamageSource source) {
        return false;
    }

    @Override
    public boolean removeWhenFarAway(double distance) {
        return false;
    }

    @Override
    public boolean isInvulnerableTo(DamageSource source) {
        if (source.is(DamageTypes.GENERIC_KILL)) {
            return false;
        }
        // l'allié ne craint rien ; personne ne se blesse en tombant, contre un mur, ou avec ses propres boules
        if (allie() || source.is(DamageTypes.IN_WALL) || source.is(DamageTypes.FALL) || source.getEntity() == this) {
            return true;
        }
        return super.isInvulnerableTo(source);
    }

    private LivingEntity cible() {
        AABB zone = getBoundingBox().inflate(48);
        if (allie()) {
            return level().getEntitiesOfClass(CombattantZ.class, zone, c -> !c.allie() && c.isAlive()).stream().findFirst().orElse(null);
        }
        return level().getEntitiesOfClass(Player.class, zone, p -> p.isAlive() && !p.isSpectator() && !p.isCreative())
                .stream().min(Comparator.comparingDouble(this::distanceToSqr)).orElse(null);
    }

    private void parle(String texte) {
        for (ServerPlayer p : ((ServerLevel) level()).getPlayers(p -> p.distanceToSqr(this) < 80 * 80)) {
            p.sendSystemMessage(Component.literal((variante() == GOKU ? "Goku" : "Vegeta") + " : « " + texte + " »")
                    .withStyle(variante() == GOKU ? ChatFormatting.GOLD : ChatFormatting.LIGHT_PURPLE));
        }
    }

    @Override
    protected void customServerAiStep() {
        super.customServerAiStep();
        barre.setProgress(getHealth() / getMaxHealth());
        LivingEntity cible = cible();
        if (cible == null) {
            setDeltaMovement(getDeltaMovement().add(0, Math.sin(tickCount * 0.1) * 0.01, 0));
            return;
        }
        if (!enrage && !allie() && getHealth() < getMaxHealth() / 2) {
            enrage = true;
            parle(variante() == GOKU ? "Bon… Fini de jouer !" : "Vous croyez pouvoir battre le Prince des Saiyans ?!");
            level().playSound(null, blockPosition(), SoundEvents.WITHER_SPAWN, SoundSource.HOSTILE, 1F, 1.6F);
            ((ServerLevel) level()).sendParticles(ParticleTypes.FLASH, getX(), getY() + 1, getZ(), 1, 0, 0, 0, 0);
        }
        // regarder sa cible
        Vec3 vers = cible.getEyePosition().subtract(getEyePosition());
        float lacet = (float) Math.toDegrees(Math.atan2(-vers.x, vers.z));
        setYRot(lacet);
        yBodyRot = lacet;
        yHeadRot = lacet;
        setXRot((float) Math.toDegrees(-Math.atan2(vers.y, Math.sqrt(vers.x * vers.x + vers.z * vers.z))));
        int etat = etat();
        if (etat == 1) {
            // la charge : on s'arrête, l'énergie s'accumule ; la ligne de tir se fige à la fin
            setDeltaMovement(getDeltaMovement().scale(0.5));
            if (++etatTicks % 10 == 0) {
                level().playSound(null, blockPosition(), SoundEvents.BEACON_POWER_SELECT, SoundSource.HOSTILE, 1.5F, 0.6F + etatTicks * 0.01F);
            }
            if (etatTicks >= 40) {
                Vec3 v = cible.position().add(0, 1, 0);
                entityData.set(VISEE, new Vector3f((float) v.x, (float) v.y, (float) v.z));
                entityData.set(ETAT, 2);
                etatTicks = 0;
                level().playSound(null, blockPosition(), SoundEvents.WARDEN_SONIC_BOOM, SoundSource.HOSTILE, 3F, 0.6F);
            }
            return;
        }
        if (etat == 2) {
            setDeltaMovement(Vec3.ZERO);
            Vec3 depart = getEyePosition();
            Vec3 dir = visee().subtract(depart).normalize();
            Vec3 bout = depart.add(dir.scale(45));
            if (etatTicks % 5 == 0) {
                for (Player p : level().getEntitiesOfClass(Player.class, new AABB(depart, bout).inflate(2))) {
                    if (distanceSegment(p.position().add(0, 1, 0), depart, bout) < 1.8) {
                        p.hurt(damageSources().mobAttack(this), 5);
                    }
                }
            }
            if (++etatTicks >= 30) {
                entityData.set(ETAT, 0);
                etatTicks = 0;
                attente = 40;
            }
            return;
        }
        // se placer : en orbite autour de la cible, à bonne distance, un peu au-dessus
        angleOrbite += allie() ? 0.02 : 0.035;
        double distance = allie() ? 11 : 7;
        Vec3 but = cible.position().add(Math.cos(angleOrbite) * distance, 2.5 + Math.sin(tickCount * 0.05), Math.sin(angleOrbite) * distance);
        Vec3 elan = but.subtract(position()).scale(0.04);
        if (elan.length() > 0.5) {
            elan = elan.normalize().scale(0.5);
        }
        setDeltaMovement(getDeltaMovement().add(elan));
        if (--attente > 0) {
            if (rafale > 0 && attente % 4 == 0) {
                tirer(cible);
                rafale--;
            }
            return;
        }
        double de = random.nextDouble();
        if (allie()) {
            tirer(cible);
            attente = 30 + random.nextInt(20);
        } else if (de < 0.38) {
            ruee(cible);
            attente = enrage ? 45 : 70;
        } else if (de < 0.75) {
            rafale = enrage ? 9 : 6;
            attente = rafale * 4 + (enrage ? 30 : 50);
        } else {
            entityData.set(ETAT, 1);
            etatTicks = 0;
            String nom = variante() == GOKU ? "KAMÉHAMÉHA" : "FINAL FLASH";
            for (ServerPlayer p : ((ServerLevel) level()).getPlayers(p -> p.distanceToSqr(this) < 80 * 80)) {
                p.displayClientMessage(Component.literal("⚠ " + nom + " ! Sortez de la ligne !").withStyle(ChatFormatting.RED, ChatFormatting.BOLD), true);
            }
            parle(variante() == GOKU ? "Kamé… Hamé…" : "Final…");
        }
    }

    private static double distanceSegment(Vec3 p, Vec3 a, Vec3 b) {
        Vec3 ab = b.subtract(a);
        double k = Math.max(0, Math.min(1, p.subtract(a).dot(ab) / ab.lengthSqr()));
        return p.distanceTo(a.add(ab.scale(k)));
    }

    private void tirer(LivingEntity cible) {
        Vec3 depart = getEyePosition().add(getLookAngle().scale(0.8));
        Vec3 dir = cible.getEyePosition().add((random.nextDouble() - 0.5) * 1.5, -0.4, (random.nextDouble() - 0.5) * 1.5).subtract(depart);
        KiBlast boule = new KiBlast(level(), this, dir, variante() == GOKU ? 1 : 0, allie() ? 14 : 6);
        boule.moveTo(depart.x, depart.y, depart.z, getYRot(), getXRot());
        level().addFreshEntity(boule);
        level().playSound(null, blockPosition(), SoundEvents.FIRECHARGE_USE, SoundSource.HOSTILE, 0.8F, 1.6F);
    }

    /** Disparaître et réapparaître juste derrière la cible, pour la frapper. */
    private void ruee(LivingEntity cible) {
        ServerLevel serveur = (ServerLevel) level();
        serveur.sendParticles(ParticleTypes.END_ROD, getX(), getY() + 1, getZ(), 15, 0.4, 0.8, 0.4, 0.1);
        Vec3 derriere = cible.position().subtract(cible.getLookAngle().multiply(1, 0, 1).normalize().scale(1.6)).add(0, 0.2, 0);
        teleportTo(derriere.x, derriere.y, derriere.z);
        serveur.playSound(null, blockPosition(), SoundEvents.ENDERMAN_TELEPORT, SoundSource.HOSTILE, 1F, 1.5F);
        cible.hurt(damageSources().mobAttack(this), enrage ? 12 : 9);
        Vec3 pousse = cible.position().subtract(position()).normalize().scale(1.6);
        cible.push(pousse.x, 0.5, pousse.z);
        cible.hurtMarked = true;
        serveur.sendParticles(ParticleTypes.CRIT, cible.getX(), cible.getY() + 1, cible.getZ(), 20, 0.3, 0.5, 0.3, 0.4);
        serveur.playSound(null, cible.blockPosition(), SoundEvents.PLAYER_ATTACK_KNOCKBACK, SoundSource.HOSTILE, 1.2F, 0.7F);
    }

    // ---------------------------------------------------------------- la barre de vie

    @Override
    public void startSeenByPlayer(ServerPlayer joueur) {
        super.startSeenByPlayer(joueur);
        if (!allie()) {
            barre.addPlayer(joueur);
        }
    }

    @Override
    public void stopSeenByPlayer(ServerPlayer joueur) {
        super.stopSeenByPlayer(joueur);
        barre.removePlayer(joueur);
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putInt("variante", variante());
        tag.putBoolean("allie", allie());
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        entityData.set(VARIANTE, tag.getInt("variante"));
        entityData.set(ALLIE, tag.getBoolean("allie"));
        barre.setName(getDisplayName());
    }
}
