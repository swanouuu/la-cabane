package fr.cabane.mission;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.Arrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

/**
 * Les gens des missions : les gardes (qui font leur ronde, te voient dans leur cône, te courent dessus et tapent), les archers
 * (qui tirent de loin), les civils (qui crient et appellent les gardes), les mannequins du Méga Mall (qui ne bougent que quand
 * personne ne les regarde) et l'otage (qui suit celui qui l'a libéré). Un coup dans le dos d'un garde qui ne t'a pas vu le met
 * K.O. ; il ne meurt jamais : à bout de forces, il tombe K.O. aussi. Au-dessus de la tête : « ? » quand il a un doute,
 * « ! » quand il t'a vu, « Zzz » quand il est K.O.
 */
public class MissionGarde extends PathfinderMob {
    public static final int GARDE = 0, ARCHER = 1, CIVIL = 2, MANNEQUIN = 3, OTAGE = 4;
    public static final int CALME = 0, SOUPCON = 1, ALERTE = 2, KO = 3;
    public static final int REPOS = 0, PORTE = 1, CROISE = 2, TEND = 3;
    /** K.O. pendant 45 secondes. */
    static final int DUREE_KO = 900;

    private static final EntityDataAccessor<String> SKIN = SynchedEntityData.defineId(MissionGarde.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<Byte> ETAT = SynchedEntityData.defineId(MissionGarde.class, EntityDataSerializers.BYTE);
    private static final EntityDataAccessor<Byte> TYPE = SynchedEntityData.defineId(MissionGarde.class, EntityDataSerializers.BYTE);
    private static final EntityDataAccessor<Byte> POSE = SynchedEntityData.defineId(MissionGarde.class, EntityDataSerializers.BYTE);
    private static final DustParticleOptions LASER = new DustParticleOptions(new Vector3f(1F, 0.15F, 0.2F), 0.8F);

    // ce qui le règle (sauvegardé)
    String mission = "";
    Vec3 maison = Vec3.ZERO;
    float lacet;
    private final List<Vec3> ronde = new ArrayList<>();
    private boolean fermee = true;
    private double portee = 9, angle = 50;
    private boolean lanterne, lampe, tireur;
    // ce qu'il est en train de faire
    private UUID cible;
    private Vec3 dernierVu;
    private int perdu, attente, fouille, depuis, ko, recharge, cri, pointRonde, sens = 1;
    private boolean versGong;
    private float gel = Float.NaN;
    UUID suit;
    boolean libere;

    public MissionGarde(EntityType<? extends MissionGarde> type, Level level) {
        super(type, level);
        setPersistenceRequired();
        setCanPickUpLoot(false);
    }

    public static AttributeSupplier.Builder attributs() {
        return PathfinderMob.createMobAttributes().add(Attributes.MAX_HEALTH, 30).add(Attributes.MOVEMENT_SPEED, 0.3)
                .add(Attributes.ATTACK_DAMAGE, 4).add(Attributes.FOLLOW_RANGE, 48).add(Attributes.STEP_HEIGHT, 1.0);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(SKIN, "agent");
        builder.define(ETAT, (byte) CALME);
        builder.define(TYPE, (byte) GARDE);
        builder.define(POSE, (byte) REPOS);
    }

    public String skin() {
        return entityData.get(SKIN);
    }

    public int etat() {
        return entityData.get(ETAT);
    }

    public int type() {
        return entityData.get(TYPE);
    }

    public int pose() {
        return entityData.get(POSE);
    }

    private void setEtat(int e) {
        entityData.set(ETAT, (byte) e);
    }

    /** Habille et règle le garde d'après la scène. */
    MissionGarde preparer(String mission, Scene.Pnj p) {
        this.mission = mission;
        entityData.set(SKIN, p.skin());
        int type = switch (p.type()) {
            case "archer" -> ARCHER;
            case "civil" -> CIVIL;
            case "mannequin" -> MANNEQUIN;
            case "otage" -> OTAGE;
            default -> GARDE;
        };
        entityData.set(TYPE, (byte) type);
        maison = p.p();
        lacet = p.lacet();
        ronde.clear();
        ronde.addAll(p.ronde());
        fermee = p.fermee();
        portee = p.portee();
        angle = p.angle();
        lanterne = p.lanterne();
        lampe = p.lampe();
        tireur = p.arme() != null && p.arme().contains("laser");
        ItemStack tenu = p.arme() != null ? Missions.objetTenu(p.arme()) : ItemStack.EMPTY;
        if (lanterne) {
            setItemSlot(tenu.isEmpty() ? EquipmentSlot.MAINHAND : EquipmentSlot.OFFHAND, new ItemStack(Items.LANTERN));
        }
        if (!tenu.isEmpty()) {
            setItemSlot(EquipmentSlot.MAINHAND, tenu);
        }
        for (EquipmentSlot s : EquipmentSlot.values()) {
            setDropChance(s, 0F);
        }
        int pose = lanterne || lampe ? TEND : p.arme() != null ? PORTE : type == MANNEQUIN ? TEND : type == CIVIL ? REPOS
                : type == OTAGE ? REPOS : CROISE;
        entityData.set(POSE, (byte) pose);
        if (type == ARCHER) {
            portee = Math.max(portee, 14);
        }
        AttributeInstance vitesse = getAttribute(Attributes.MOVEMENT_SPEED);
        if (vitesse != null) {
            vitesse.setBaseValue(type == MANNEQUIN ? 0.34 : type == OTAGE ? 0.3 : 0.3);
        }
        AttributeInstance coup = getAttribute(Attributes.ATTACK_DAMAGE);
        if (coup != null) {
            coup.setBaseValue(type == MANNEQUIN ? 3 : 4);
        }
        AttributeInstance taille = getAttribute(Attributes.SCALE);
        if (taille != null) {
            taille.setBaseValue(p.taille());
        }
        if (type == OTAGE) {
            setInvulnerable(true);
        }
        moveTo(maison.x, maison.y, maison.z, lacet, 0F);
        yHeadRot = lacet;
        yBodyRot = lacet;
        return this;
    }

    @Override
    protected void registerGoals() {
        goalSelector.addGoal(0, new FloatGoal(this));
    }

    // ================================================================== le cerveau

    @Override
    protected void customServerAiStep() {
        super.customServerAiStep();
        if (!(level() instanceof ServerLevel level)) {
            return;
        }
        if (recharge > 0) {
            recharge--;
        }
        if (ko > 0) {
            getNavigation().stop();
            if (--ko == 0) {
                setEtat(CALME);
                retour();
                level.playSound(null, getX(), getY(), getZ(), SoundEvents.PLAYER_BREATH, SoundSource.HOSTILE, 0.8F, 0.9F);
            }
            return;
        }
        Session s = Missions.session(mission);
        if (s == null || s.finie()) {
            if (etat() != CALME) {
                retour();
            }
            if (type() != OTAGE || !libere) {
                patrouiller();
            }
            return;
        }
        switch (type()) {
            case OTAGE -> otage(level, s);
            case MANNEQUIN -> mannequin(level, s);
            default -> garde(level, s);
        }
    }

    private void garde(ServerLevel level, Session s) {
        if (tickCount % 4 == 0) {
            ServerPlayer vu = null;
            double mieux = Double.MAX_VALUE;
            for (ServerPlayer h : s.cibles(level)) {
                int v = perception(level, s, h);
                if (v == 2 && distanceToSqr(h) < mieux) {
                    mieux = distanceToSqr(h);
                    vu = h;
                } else if (v == 1 && etat() == CALME) {
                    soupcon(level, h.position());
                }
            }
            if (vu != null) {
                dernierVu = vu.position();
                perdu = 0;
                if (etat() != ALERTE || !vu.getUUID().equals(cible)) {
                    reperer(level, s, vu);
                }
            } else if (etat() == ALERTE) {
                perdu += 4;
            }
        }
        switch (etat()) {
            case ALERTE -> poursuivre(level, s);
            case SOUPCON -> chercher();
            default -> patrouiller();
        }
    }

    /** 0 : rien ; 1 : il a entendu quelque chose ; 2 : il le voit. */
    private int perception(ServerLevel level, Session s, ServerPlayer h) {
        Vec3 oeil = getEyePosition(), yeux = h.getEyePosition();
        double d = oeil.distanceTo(yeux);
        double p = portee;
        if (s.scene.nuit && level.getMaxLocalRawBrightness(BlockPos.containing(yeux)) < 8) {
            p *= lanterne || lampe ? 0.8 : 0.45;
        }
        if (h.isCrouching()) {
            p *= 0.6;
        }
        boolean cache = Missions.cache(level, h);
        if (cache) {
            p = Math.min(p, 1.8);
        }
        if (d <= p && dansLeCone(yeux, angle) && Missions.voit(level, oeil, h, this)) {
            return 2;
        }
        if (!cache && !h.isCrouching() && (d < 3 || (h.isSprinting() && d < 7))) {
            return 1;
        }
        return 0;
    }

    private boolean dansLeCone(Vec3 p, double demiAngle) {
        Vec3 dir = Vec3.directionFromRotation(0, yHeadRot);
        double dx = p.x - getX(), dz = p.z - getZ(), hd = Math.sqrt(dx * dx + dz * dz);
        return hd < 0.8 || (dir.x * dx + dir.z * dz) / hd >= Math.cos(Math.toRadians(demiAngle));
    }

    /** Il t'a vu : « ! », et il prévient (les gardes autour, l'alarme pour les archers, le gong au château). */
    void reperer(ServerLevel level, Session s, ServerPlayer h) {
        if (ko > 0 || type() == OTAGE || type() == MANNEQUIN) {
            return;
        }
        boolean premiere = etat() != ALERTE;
        cible = h.getUUID();
        dernierVu = h.position();
        perdu = 0;
        depuis = 0;
        setEtat(ALERTE);
        if (!premiere) {
            return;
        }
        if (type() == CIVIL) {
            cri = 160;
            level.playSound(null, getX(), getY(), getZ(), SoundEvents.VILLAGER_NO, SoundSource.NEUTRAL, 1.6F, skin().equals("employe") ? 1.5F : 1.1F);
            s.alerterAutour(level, this, h, 16);
            return;
        }
        level.playSound(null, getX(), getY() + 2, getZ(), SoundEvents.NOTE_BLOCK_PLING.value(), SoundSource.HOSTILE, 1.4F, 2F);
        level.playSound(null, getX(), getY() + 2, getZ(), SoundEvents.NOTE_BLOCK_BIT.value(), SoundSource.HOSTILE, 1F, 1.6F);
        if (type() == ARCHER) {
            s.alarme(level, h.position(), h, false);
            return;
        }
        s.alerterAutour(level, this, h, 12);
        if (s.scene.gongPoint != null && !s.alarmeActive(level) && s.coureurGong == null && position().distanceTo(s.scene.gongPoint) < 32) {
            versGong = true;
            s.coureurGong = getUUID();
        }
    }

    /** Un bruit, un doute : « ? », il va voir. */
    void soupcon(ServerLevel level, Vec3 ou) {
        if (ko > 0 || type() == OTAGE || type() == MANNEQUIN) {
            return;
        }
        if (etat() == CALME) {
            level.playSound(null, getX(), getY() + 2, getZ(), SoundEvents.NOTE_BLOCK_BIT.value(), SoundSource.HOSTILE, 0.6F, 0.8F);
        }
        setEtat(SOUPCON);
        dernierVu = ou;
        fouille = 0;
        depuis = 0;
    }

    private void poursuivre(ServerLevel level, Session s) {
        ServerPlayer h = cible == null ? null : s.cible(level, cible);
        if (h == null || maison.distanceTo(position()) > 56) {
            retour();
            return;
        }
        if (perdu > 100) {
            soupcon(level, dernierVu);
            return;
        }
        double d = distanceTo(h);
        switch (type()) {
            case CIVIL -> {
                // il fuit en criant
                Vec3 loin = position().add(position().subtract(h.position()).normalize().scale(6));
                if (tickCount % 10 == 0) {
                    getNavigation().moveTo(loin.x, loin.y, loin.z, 1.2);
                }
                if (--cri <= 0) {
                    retour();
                }
            }
            case ARCHER -> {
                getNavigation().stop();
                getLookControl().setLookAt(h, 30F, 30F);
                if (perdu == 0 && recharge <= 0 && d < portee * 1.3) {
                    tirerFleche(level, h);
                    recharge = 40;
                }
            }
            default -> {
                if (versGong) {
                    Vec3 g = s.scene.gongPoint;
                    if (tickCount % 10 == 0) {
                        getNavigation().moveTo(g.x, g.y, g.z, 1.35);
                    }
                    if (position().distanceTo(g) < 1.8) {
                        versGong = false;
                        s.coureurGong = null;
                        s.sonnerGong(level, this, h);
                    }
                    return;
                }
                if (tickCount % 5 == 0 || getNavigation().isDone()) {
                    getNavigation().moveTo(h, 1.3);
                }
                getLookControl().setLookAt(h, 30F, 30F);
                if (tireur && perdu == 0 && recharge <= 0 && d > 3 && d < 14) {
                    tirerLaser(level, h);
                    recharge = 30;
                } else if (d < 2.2 && recharge <= 0 && hasLineOfSight(h)) {
                    swing(InteractionHand.MAIN_HAND);
                    h.hurt(Missions.coup(this), (float) getAttributeValue(Attributes.ATTACK_DAMAGE));
                    recharge = 20;
                }
                // du haut d'un arbre, d'un toit : il saute sur toi
                double dx = h.getX() - getX(), dz = h.getZ() - getZ(), hd = Math.sqrt(dx * dx + dz * dz);
                if (onGround() && h.getY() < getY() - 2.5 && hd < 10 && hd > 0.5 && tickCount % 20 == 0) {
                    setDeltaMovement(dx / hd * 0.5, 0.38, dz / hd * 0.5);
                    hasImpulse = true;
                }
            }
        }
    }

    private void chercher() {
        if (dernierVu == null) {
            retour();
            return;
        }
        depuis++;
        if (fouille == 0) {
            if (horizontal(dernierVu) < 1.6 || depuis > 200) {
                fouille = 100;
                getNavigation().stop();
            } else if (tickCount % 10 == 0 || getNavigation().isDone()) {
                getNavigation().moveTo(dernierVu.x, dernierVu.y, dernierVu.z, 1.0);
            }
            return;
        }
        // il regarde autour de lui, puis il retourne à sa ronde
        Vec3 dir = Vec3.directionFromRotation(0, lacet + tickCount * 7F);
        getLookControl().setLookAt(getX() + dir.x * 4, getEyeY(), getZ() + dir.z * 4);
        if (--fouille <= 0) {
            retour();
        }
    }

    void retour() {
        if (etat() != KO) {
            setEtat(CALME);
        }
        Session s = Missions.session(mission);
        if (s != null && getUUID().equals(s.coureurGong)) {
            s.coureurGong = null;
        }
        cible = null;
        dernierVu = null;
        perdu = 0;
        fouille = 0;
        versGong = false;
    }

    private void patrouiller() {
        if (ronde.size() >= 2) {
            Vec3 pt = ronde.get(Math.floorMod(pointRonde, ronde.size()));
            if (attente > 0) {
                attente--;
                return;
            }
            if (horizontal(pt) < 1.0) {
                attente = 20 + random.nextInt(30);
                if (fermee) {
                    pointRonde = (pointRonde + 1) % ronde.size();
                } else {
                    if (pointRonde + sens < 0 || pointRonde + sens >= ronde.size()) {
                        sens = -sens;
                    }
                    pointRonde += sens;
                }
            } else if (getNavigation().isDone() || tickCount % 40 == 0) {
                getNavigation().moveTo(pt.x, pt.y, pt.z, 0.75);
            }
            return;
        }
        if (horizontal(maison) > 1.0 || Math.abs(getY() - maison.y) > 1.5) {
            if (getNavigation().isDone() || tickCount % 40 == 0) {
                getNavigation().moveTo(maison.x, maison.y, maison.z, 0.8);
            }
            return;
        }
        getNavigation().stop();
        // à son poste : il regarde devant lui, en balayant un peu
        float balaye = type() == GARDE || type() == ARCHER ? 30F * Mth.sin(tickCount / 50F) : 10F * Mth.sin(tickCount / 80F);
        Vec3 dir = Vec3.directionFromRotation(0, lacet + balaye);
        getLookControl().setLookAt(getX() + dir.x * 4, getEyeY(), getZ() + dir.z * 4);
    }

    private double horizontal(Vec3 p) {
        double dx = p.x - getX(), dz = p.z - getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }

    // ================================================================== les mannequins, l'otage

    /** Il ne bouge que quand personne ne le regarde ; sinon il fonce sur l'espionne la plus proche. */
    private void mannequin(ServerLevel level, Session s) {
        List<ServerPlayer> cibles = s.cibles(level);
        boolean observe = false;
        for (ServerPlayer h : cibles) {
            if (Missions.regarde(level, h, this)) {
                observe = true;
                break;
            }
        }
        if (observe) {
            getNavigation().stop();
            setDeltaMovement(0, Math.min(0, getDeltaMovement().y), 0);
            if (Float.isNaN(gel)) {
                gel = getYRot();
                level.playSound(null, getX(), getY(), getZ(), SoundEvents.ARMOR_STAND_HIT, SoundSource.HOSTILE, 0.25F, 0.6F);
            }
            setYRot(gel);
            yHeadRot = gel;
            yBodyRot = gel;
            return;
        }
        gel = Float.NaN;
        ServerPlayer proche = null;
        double mieux = 22 * 22;
        for (ServerPlayer h : cibles) {
            double d = distanceToSqr(h);
            if (d < mieux && (Math.abs(h.getY() - getY()) < 4 || d < 64)) {
                mieux = d;
                proche = h;
            }
        }
        if (proche == null) {
            return;
        }
        if (tickCount % 5 == 0 || getNavigation().isDone()) {
            getNavigation().moveTo(proche, 1.5);
        }
        getLookControl().setLookAt(proche, 40F, 40F);
        if (distanceToSqr(proche) < 2.6 && recharge <= 0) {
            swing(InteractionHand.MAIN_HAND);
            proche.hurt(Missions.coup(this), (float) getAttributeValue(Attributes.ATTACK_DAMAGE));
            recharge = 25;
        }
    }

    /** Dans la cage tant qu'on ne l'a pas libéré ; puis il suit celui qui l'a libéré. */
    private void otage(ServerLevel level, Session s) {
        if (!libere) {
            patrouiller();
            return;
        }
        ServerPlayer h = suit == null ? null : s.cible(level, suit);
        if (h == null) {
            double mieux = 30 * 30;
            for (ServerPlayer c : s.cibles(level)) {
                if (distanceToSqr(c) < mieux) {
                    mieux = distanceToSqr(c);
                    h = c;
                }
            }
            suit = h == null ? null : h.getUUID();
            if (h == null) {
                getNavigation().stop();
                return;
            }
        }
        double d = distanceTo(h);
        if (d > 24) {
            getNavigation().stop();
            moveTo(h.getX(), h.getY(), h.getZ(), getYRot(), 0F);
        } else if (d > 2.5) {
            if (tickCount % 5 == 0 || getNavigation().isDone()) {
                getNavigation().moveTo(h, d > 8 ? 1.3 : 1.0);
            }
        } else {
            getNavigation().stop();
        }
        getLookControl().setLookAt(h, 30F, 30F);
    }

    /** Les gardes l'ont repris : retour dans la cage. */
    void enfermer() {
        libere = false;
        suit = null;
        getNavigation().stop();
        teleportTo(maison.x, maison.y, maison.z);
    }

    // ================================================================== les tirs

    private void tirerFleche(ServerLevel level, ServerPlayer h) {
        Arrow fleche = new Arrow(level, this, new ItemStack(Items.ARROW), null);
        double dx = h.getX() - getX(), dy = h.getY(0.5) - fleche.getY(), dz = h.getZ() - getZ();
        double hd = Math.sqrt(dx * dx + dz * dz);
        fleche.shoot(dx, dy + hd * 0.16, dz, 1.7F, 5F);
        fleche.setBaseDamage(1.5);
        fleche.pickup = AbstractArrow.Pickup.DISALLOWED;
        fleche.addTag(Missions.FLECHE);
        level.addFreshEntity(fleche);
        swing(InteractionHand.MAIN_HAND);
        level.playSound(null, getX(), getY(), getZ(), SoundEvents.SKELETON_SHOOT, SoundSource.HOSTILE, 1F, 1F / (random.nextFloat() * 0.4F + 0.8F));
    }

    /** Les sbires de l'île : un trait rouge, qui touche s'il te voit. */
    private void tirerLaser(ServerLevel level, ServerPlayer h) {
        Vec3 de = getEyePosition().add(Vec3.directionFromRotation(0, yHeadRot).scale(0.5)).subtract(0, 0.25, 0);
        Vec3 a = h.getEyePosition().subtract(0, 0.4, 0);
        Vec3 d = a.subtract(de);
        double l = d.length();
        for (double k = 0; k < l; k += 0.35) {
            Vec3 p = de.add(d.scale(k / l));
            level.sendParticles(LASER, p.x, p.y, p.z, 1, 0, 0, 0, 0);
        }
        level.playSound(null, getX(), getY(), getZ(), SoundEvents.BEACON_DEACTIVATE, SoundSource.HOSTILE, 0.8F, 2F);
        swing(InteractionHand.MAIN_HAND);
        if (random.nextFloat() < 0.7F) {
            h.hurt(Missions.coup(this), 3F);
        }
    }

    // ================================================================== les coups : K.O., jamais mort

    @Override
    public boolean hurt(DamageSource source, float degats) {
        if (level().isClientSide) {
            return false;
        }
        if (source.is(DamageTypeTags.BYPASSES_INVULNERABILITY)) {
            return super.hurt(source, degats);
        }
        if (type() == OTAGE || source.is(DamageTypeTags.IS_FALL)) {
            return false;
        }
        Entity a = source.getEntity();
        if (ko > 0) {
            if (a instanceof Player) {
                ko = Math.max(ko, DUREE_KO / 2);
            }
            return false;
        }
        if (a instanceof ServerPlayer j && source.getDirectEntity() == j && dansLeDos(j) && !(etat() == ALERTE && j.getUUID().equals(cible))) {
            assommer();
            return true;
        }
        boolean r = super.hurt(source, degats);
        Session s = Missions.session(mission);
        if (r && ko == 0 && a instanceof ServerPlayer j && s != null && s.estHeros(j) && level() instanceof ServerLevel level) {
            reperer(level, s, j);
        }
        return r;
    }

    private boolean dansLeDos(Entity e) {
        Vec3 regard = Vec3.directionFromRotation(0, yBodyRot);
        double dx = e.getX() - getX(), dz = e.getZ() - getZ(), hd = Math.sqrt(dx * dx + dz * dz);
        return hd > 0.01 && (regard.x * dx + regard.z * dz) / hd < -0.1;
    }

    void assommer() {
        fr.cabane.Cabane.LOGGER.debug("Mission {} : {} K.O.", mission, skin());
        ko = DUREE_KO;
        retour();
        setEtat(KO);
        getNavigation().stop();
        setHealth(getMaxHealth());
        if (level() instanceof ServerLevel level) {
            level.playSound(null, getX(), getY(), getZ(), SoundEvents.PLAYER_ATTACK_KNOCKBACK, SoundSource.HOSTILE, 1.2F, 0.7F);
            level.sendParticles(ParticleTypes.CRIT, getX(), getY() + 1.6, getZ(), 12, 0.3, 0.2, 0.3, 0.2);
        }
    }

    public boolean assomme() {
        return ko > 0;
    }

    /** À bout de forces, il tombe K.O. au lieu de mourir. */
    @Override
    public void die(DamageSource source) {
        if (source.is(DamageTypeTags.BYPASSES_INVULNERABILITY)) {
            super.die(source);
            return;
        }
        setHealth(getMaxHealth());
        assommer();
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
    public boolean isPersistenceRequired() {
        return true;
    }

    @Override
    protected boolean shouldDropLoot() {
        return false;
    }

    @Override
    protected SoundEvent getAmbientSound() {
        return null;
    }

    // ================================================================== sauvegarde

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putString("Mission", mission);
        tag.putString("Skin", skin());
        tag.putByte("Type", (byte) type());
        tag.putByte("Pose", (byte) pose());
        tag.putDouble("MX", maison.x);
        tag.putDouble("MY", maison.y);
        tag.putDouble("MZ", maison.z);
        tag.putFloat("Lacet", lacet);
        ListTag r = new ListTag();
        for (Vec3 v : ronde) {
            CompoundTag t = new CompoundTag();
            t.putDouble("x", v.x);
            t.putDouble("y", v.y);
            t.putDouble("z", v.z);
            r.add(t);
        }
        tag.put("Ronde", r);
        tag.putBoolean("Fermee", fermee);
        tag.putDouble("Portee", portee);
        tag.putDouble("Angle", angle);
        tag.putBoolean("Lanterne", lanterne);
        tag.putBoolean("Lampe", lampe);
        tag.putBoolean("Tireur", tireur);
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        mission = tag.getString("Mission");
        entityData.set(SKIN, tag.getString("Skin"));
        entityData.set(TYPE, tag.getByte("Type"));
        entityData.set(POSE, tag.getByte("Pose"));
        maison = new Vec3(tag.getDouble("MX"), tag.getDouble("MY"), tag.getDouble("MZ"));
        lacet = tag.getFloat("Lacet");
        ronde.clear();
        for (Tag t : tag.getList("Ronde", Tag.TAG_COMPOUND)) {
            CompoundTag c = (CompoundTag) t;
            ronde.add(new Vec3(c.getDouble("x"), c.getDouble("y"), c.getDouble("z")));
        }
        fermee = tag.getBoolean("Fermee");
        portee = tag.getDouble("Portee");
        angle = tag.getDouble("Angle");
        lanterne = tag.getBoolean("Lanterne");
        lampe = tag.getBoolean("Lampe");
        tireur = tag.getBoolean("Tireur");
        setEtat(CALME);
        if (type() == OTAGE) {
            setInvulnerable(true);
        }
    }
}
