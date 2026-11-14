package fr.cabane.donjon;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.BossEvent;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

/**
 * Morvane, le Roi-Liche : le boss de la Nécropole. Un squelette de cinq blocs dans une robe violette en lambeaux, une
 * couronne d'or aux gemmes qui brûlent, des orbites où dansent deux flammes cyan, un bâton surmonté d'un orbe d'âme. Il
 * ne touche jamais le sol. Il médite au-dessus de son autel ; au réveil, il se dresse, ses yeux s'allument, et une onde
 * d'âmes balaie l'arène.
 *
 * <p>Il se bat de loin : les orbes d'âme, qui suivent leur cible ; les cercles maudits, qui éclatent sous les pieds ; le
 * rayon, qu'il balaie d'un côté à l'autre de l'arène (derrière un pilier, on est à l'abri) ; l'onde, qui repousse ceux
 * qui le collent ; et il se téléporte dans le dos de sa cible. Ses squelettes se relèvent du sol.
 */
public class Liche extends BossDonjon {
    public static final int ORBES = 10, BLINK = 11, CERCLES = 12, RAYON = 13, ONDE = 14;
    public static final int DUREE_REVEIL = 70, DUREE_ORBES = 40, DUREE_BLINK = 22, DUREE_CERCLES = 52, CHARGE_RAYON = 22, DUREE_RAYON = 74, DUREE_ONDE = 26,
            DUREE_RAGE = 50;
    private static final int TIR_ORBE = 2;
    /** Il flotte à tant de blocs du sol. */
    public static final double VOL = 1.2;

    private static final DustParticleOptions VIOLET = new DustParticleOptions(new Vector3f(0.75F, 0.3F, 1F), 1.5F);
    private static final DustParticleOptions CYAN = new DustParticleOptions(new Vector3f(0.5F, 0.95F, 1F), 1.2F);

    private int orbesCd = 30, blinkCd = 160, cerclesCd = 100, rayonCd = 260, ondeCd = 40, colle;
    private final List<Vec3> cercles = new ArrayList<>();
    private float rayonDepart;

    public Liche(EntityType<? extends Liche> type, Level level) {
        super(type, level);
        setNoGravity(true);
    }

    public static AttributeSupplier.Builder attributs() {
        return Monster.createMonsterAttributes().add(Attributes.MAX_HEALTH, VIE).add(Attributes.ATTACK_DAMAGE, 8).add(Attributes.ARMOR, 8)
                .add(Attributes.MOVEMENT_SPEED, 0.28).add(Attributes.FOLLOW_RANGE, 64).add(Attributes.KNOCKBACK_RESISTANCE, 1)
                .add(Attributes.SAFE_FALL_DISTANCE, 64);
    }

    @Override
    public TypeDonjon typeDonjon() {
        return TypeDonjon.LICHE;
    }

    @Override
    public float echellePv() {
        return 2.4F;
    }

    @Override
    protected BossEvent.BossBarColor couleurBarre() {
        return BossEvent.BossBarColor.PURPLE;
    }

    @Override
    protected int dureeReveil() {
        return DUREE_REVEIL;
    }

    @Override
    protected int dureeRage() {
        return DUREE_RAGE;
    }

    /** L'orbe de son bâton (dans le monde). */
    public Vec3 orbe() {
        Vec3 av = avant();
        Vec3 droite = new Vec3(-av.z, 0, av.x);
        return position().add(0, 5.0, 0).add(droite.scale(1.25)).add(av.scale(0.5));
    }

    @Override
    public void tick() {
        super.tick();
        setNoGravity(true);
        if (level().isClientSide && isAlive() && etat() != SOMMEIL) {
            // des âmes s'échappent du bas de sa robe
            level().addParticle(ParticleTypes.SOUL_FIRE_FLAME, getX() + random.nextGaussian() * 0.5, getY() + 0.2, getZ() + random.nextGaussian() * 0.5, 0, -0.02, 0);
            if (enrage() && random.nextBoolean()) {
                level().addParticle(ParticleTypes.SOUL, getX() + random.nextGaussian() * 0.8, getY() + 1 + random.nextDouble() * 4,
                        getZ() + random.nextGaussian() * 0.8, 0, 0.05, 0);
            }
        }
    }

    @Override
    protected Vec3 vitrinePos() {
        return repos().add(0, 2.2, 0);
    }

    /** Endormi : il médite, assis en l'air au-dessus de son autel. */
    @Override
    protected void dormir() {
        Vec3 r = repos();
        setPos(r.x, r.y + 2.2, r.z);
        setDeltaMovement(Vec3.ZERO);
        regarder(arene());
    }

    /** Il flotte : il garde ses distances avec sa cible, à hauteur d'homme, en ondulant. */
    private void flotter(Vec3 vers, double vitesse) {
        double sol = arene().y + VOL + Math.sin(tickCount * 0.08) * 0.25;
        Vec3 v = vers == null ? Vec3.ZERO : vers.multiply(1, 0, 1);
        if (v.lengthSqr() > vitesse * vitesse) {
            v = v.normalize().scale(vitesse);
        }
        setDeltaMovement(v.x, (sol - getY()) * 0.15, v.z);
    }

    @Override
    protected void reveil(ServerLevel sl, int a) {
        Vec3 r = repos();
        if (a < 30) {
            setPos(r.x, r.y + 2.2 + a * 0.03, r.z);
        }
        if (a == 22) {
            sl.playSound(null, getX(), getY() + 4, getZ(), SoundEvents.SOUL_ESCAPE.value(), SoundSource.HOSTILE, 4F, 0.5F);
            sl.playSound(null, getX(), getY() + 4, getZ(), SoundEvents.BEACON_ACTIVATE, SoundSource.HOSTILE, 3F, 0.5F);
        }
        if (a == 44) {
            sl.playSound(null, getX(), getY() + 4, getZ(), SoundEvents.WITHER_SPAWN, SoundSource.HOSTILE, 2F, 0.7F);
            sl.sendParticles(ParticleTypes.SOUL, getX(), getY() + 3, getZ(), 60, 1.5, 2, 1.5, 0.15);
            anneau(sl, arene(), rayon() - 2, ParticleTypes.SOUL_FIRE_FLAME);
            for (Player p : dansArene()) {
                if (p.distanceTo(this) < 10) {
                    toucher(p, 4, dehors(p, position(), 1.4, 0.4));
                }
            }
        }
        if (a > 30) {
            flotter(arene().subtract(position()).scale(0.02), 0.1);
        }
    }

    @Override
    protected void chasse(ServerLevel sl, List<Player> joueurs) {
        orbesCd--;
        blinkCd--;
        cerclesCd--;
        rayonCd--;
        ondeCd--;
        double d = distanceTo(cible);
        // il recule si on le colle, il avance si on s'éloigne
        Vec3 vers = cible.position().subtract(position());
        Vec3 dep = d < 8 ? vers.scale(-1) : d > 14 ? vers : new Vec3(-vers.z, 0, vers.x).scale(Math.sin(tickCount * 0.02));
        flotter(dep, enrage() ? 0.3 : 0.22);
        regarder(cible.position());
        long proches = joueurs.stream().filter(p -> p.distanceTo(this) < 4.5).count();
        colle = proches > 0 ? colle + 1 : 0;
        if (colle > 30 && ondeCd <= 0) {
            passer(ONDE);
        } else if ((enrage() || getHealth() < VIE * 0.75F) && rayonCd <= 0) {
            rayonDepart = getYRot();
            passer(RAYON);
        } else if (blinkCd <= 0 && (d < 6 || random.nextInt(4) == 0)) {
            passer(BLINK);
        } else if (cerclesCd <= 0) {
            passer(CERCLES);
        } else if (orbesCd <= 0) {
            passer(ORBES);
        }
    }

    @Override
    protected void action(ServerLevel sl, List<Player> joueurs, int etat) {
        switch (etat) {
            case ORBES -> orbes(sl, joueurs);
            case BLINK -> blink(sl);
            case CERCLES -> cercles(sl, joueurs);
            case RAYON -> rayon(sl, joueurs);
            case ONDE -> onde(sl, joueurs);
            default -> passer(CHASSE);
        }
    }

    /** Le bâton levé : des orbes d'âme qui suivent leur cible (à chacun la sienne, en rage). */
    private void orbes(ServerLevel sl, List<Player> joueurs) {
        flotter(null, 0);
        int a = age();
        if (cible != null) {
            regarder(cible.position());
        }
        if (a == 2) {
            sl.playSound(null, getX(), getY() + 4, getZ(), SoundEvents.EVOKER_CAST_SPELL, SoundSource.HOSTILE, 2.5F, 0.6F);
        }
        Vec3 o = orbe();
        if (a < 12) {
            sl.sendParticles(ParticleTypes.SOUL_FIRE_FLAME, o.x, o.y, o.z, 3, 0.2, 0.2, 0.2, 0.02);
        }
        if (a == 12 || a == 19 || a == 26 || (enrage() && a == 33)) {
            List<Player> cibles = enrage() ? joueurs : (cible != null ? List.of(cible) : joueurs);
            for (Player p : cibles) {
                Vec3 v = p.position().add(0, 1, 0).subtract(o).normalize().scale(0.55).add(random.nextGaussian() * 0.15, 0.25, random.nextGaussian() * 0.15);
                tirer(o, v, 100, p, 0.07, TIR_ORBE);
            }
            sl.playSound(null, o.x, o.y, o.z, SoundEvents.BLAZE_SHOOT, SoundSource.HOSTILE, 1.5F, 0.6F);
        }
        if (a >= DUREE_ORBES) {
            orbesCd = enrage() ? 40 : 60;
            passer(CHASSE);
        }
    }

    @Override
    protected void traine(ServerLevel sl, Vec3 p, int genre) {
        sl.sendParticles(ParticleTypes.SOUL_FIRE_FLAME, p.x, p.y, p.z, 2, 0.08, 0.08, 0.08, 0.01);
        sl.sendParticles(VIOLET, p.x, p.y, p.z, 2, 0.12, 0.12, 0.12, 0);
    }

    @Override
    protected void impact(ServerLevel sl, Vec3 p, int genre, Player joueur) {
        sl.sendParticles(ParticleTypes.SOUL, p.x, p.y, p.z, 10, 0.3, 0.3, 0.3, 0.05);
        sl.sendParticles(VIOLET, p.x, p.y, p.z, 14, 0.4, 0.4, 0.4, 0);
        sl.playSound(null, p.x, p.y, p.z, SoundEvents.SOUL_ESCAPE.value(), SoundSource.HOSTILE, 1.5F, 1.2F);
        if (joueur != null) {
            joueur.hurt(coup(), enrage() ? 10 : 8);
            joueur.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, 60, 0));
        }
    }

    /** Il se dissout en âmes, et réapparaît dans le dos de sa cible. */
    private void blink(ServerLevel sl) {
        flotter(null, 0);
        int a = age();
        if (a == 1) {
            sl.playSound(null, getX(), getY() + 3, getZ(), SoundEvents.ENDERMAN_TELEPORT, SoundSource.HOSTILE, 2F, 0.5F);
        }
        if (a < 10) {
            sl.sendParticles(ParticleTypes.SOUL, getX(), getY() + 2.5, getZ(), 6, 0.5, 1.5, 0.5, 0.05);
            sl.sendParticles(ParticleTypes.REVERSE_PORTAL, getX(), getY() + 2.5, getZ(), 8, 0.5, 1.5, 0.5, 0.05);
        }
        if (a == 6) {
            setInvisible(true);
        }
        if (a == 10 && cible != null) {
            Vec3 dos = Vec3.directionFromRotation(0, cible.getYRot()).scale(-4.5);
            Vec3 p = cible.position().add(dos);
            Vec3 c = arene();
            if (p.subtract(c).horizontalDistance() > rayon() - 4) {
                p = c.add(p.subtract(c).multiply(1, 0, 1).normalize().scale(rayon() - 5));
            }
            teleportTo(p.x, c.y + VOL, p.z);
            regarder(cible.position());
        }
        if (a == 12) {
            setInvisible(false);
            sl.playSound(null, getX(), getY() + 3, getZ(), SoundEvents.ENDERMAN_TELEPORT, SoundSource.HOSTILE, 2F, 0.7F);
            sl.sendParticles(ParticleTypes.SOUL, getX(), getY() + 2.5, getZ(), 30, 0.5, 1.5, 0.5, 0.05);
        }
        if (a >= DUREE_BLINK) {
            blinkCd = enrage() ? 120 : 180;
            orbesCd = 0;
            passer(CHASSE);
        }
    }

    /** Il plante son bâton dans le vide : sous chacun, un cercle maudit… qui éclate en flammes d'âme. */
    private void cercles(ServerLevel sl, List<Player> joueurs) {
        flotter(null, 0);
        int a = age();
        if (a == 2) {
            cercles.clear();
            for (Player p : joueurs) {
                cercles.add(p.position());
                if (enrage()) {
                    cercles.add(p.position().add(random.nextGaussian() * 3, 0, random.nextGaussian() * 3));
                }
            }
            sl.playSound(null, getX(), getY() + 4, getZ(), SoundEvents.EVOKER_PREPARE_ATTACK, SoundSource.HOSTILE, 2.5F, 0.6F);
        }
        if (a > 2 && a < 32 && a % 3 == 0) {
            for (Vec3 c : cercles) {
                for (int k = 0; k < 18; k++) {
                    double ang = k * Math.PI / 9 - a * 0.12;
                    sl.sendParticles(VIOLET, c.x + Math.cos(ang) * 2.6, c.y + 0.15, c.z + Math.sin(ang) * 2.6, 1, 0, 0, 0, 0);
                }
                sl.sendParticles(ParticleTypes.SOUL_FIRE_FLAME, c.x, c.y + 0.1, c.z, 2, 0.8, 0, 0.8, 0.01);
            }
        }
        if (a == 32) {
            for (Vec3 c : cercles) {
                sl.playSound(null, c.x, c.y, c.z, SoundEvents.SOUL_ESCAPE.value(), SoundSource.HOSTILE, 2F, 0.6F);
                sl.playSound(null, c.x, c.y, c.z, SoundEvents.BLAZE_SHOOT, SoundSource.HOSTILE, 1.5F, 0.5F);
                sl.sendParticles(ParticleTypes.SOUL_FIRE_FLAME, c.x, c.y + 1.5, c.z, 70, 0.7, 1.8, 0.7, 0.06);
                sl.sendParticles(ParticleTypes.SOUL, c.x, c.y + 1, c.z, 20, 0.6, 1, 0.6, 0.05);
                for (Player p : joueurs) {
                    if (p.position().distanceToSqr(c) < 2.7 * 2.7) {
                        toucher(p, enrage() ? 14 : 12, new Vec3(0, 0.8, 0));
                    }
                }
            }
        }
        if (a >= DUREE_CERCLES) {
            cerclesCd = enrage() ? 120 : 170;
            passer(CHASSE);
        }
    }

    /**
     * Le rayon : il charge l'orbe de son bâton, puis balaie l'arène d'un trait violet, de gauche à droite. Les piliers
     * arrêtent le rayon.
     */
    private void rayon(ServerLevel sl, List<Player> joueurs) {
        flotter(null, 0);
        int a = age();
        Vec3 o = orbe();
        if (a == 1) {
            sl.playSound(null, getX(), getY() + 4, getZ(), SoundEvents.BEACON_POWER_SELECT, SoundSource.HOSTILE, 3F, 0.5F);
            Boss.annoncer(sl, typeDonjon(), "ALERTE", new String[] {"Morvane charge son rayon !", "Cachez-vous derrière un pilier."}, "", 0);
            if (cible != null) {
                Vec3 v = cible.position().subtract(position());
                rayonDepart = (float) (Math.atan2(v.z, v.x) * 180 / Math.PI) - 90F;
            }
        }
        if (a < CHARGE_RAYON) {
            float lacet = rayonDepart - 60F * a / CHARGE_RAYON;
            setYRot(lacet);
            yBodyRot = lacet;
            yHeadRot = lacet;
            for (int k = 0; k < 4; k++) {
                Vec3 de = o.add(random.nextGaussian() * 1.5, random.nextGaussian() * 1.5, random.nextGaussian() * 1.5);
                Vec3 v = o.subtract(de).scale(0.15);
                sl.sendParticles(ParticleTypes.SOUL_FIRE_FLAME, de.x, de.y, de.z, 0, v.x, v.y, v.z, 1);
            }
            return;
        }
        int b = a - CHARGE_RAYON;
        int balayage = DUREE_RAYON - CHARGE_RAYON - 8;
        if (b <= balayage) {
            float lacet = rayonDepart - 60F + 120F * b / balayage;
            setYRot(lacet);
            yBodyRot = lacet;
            yHeadRot = lacet;
            Vec3 dir = Vec3.directionFromRotation(10, lacet);
            Vec3 fin = o.add(dir.scale(32));
            HitResult h = sl.clip(new ClipContext(o, fin, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, this));
            Vec3 bout = h.getLocation();
            double l = bout.distanceTo(o);
            for (double s = 0; s < l; s += 0.7) {
                Vec3 p = o.add(dir.scale(s));
                sl.sendParticles(VIOLET, p.x, p.y, p.z, 1, 0.05, 0.05, 0.05, 0);
                if (((int) (s / 0.7) + b) % 3 == 0) {
                    sl.sendParticles(ParticleTypes.END_ROD, p.x, p.y, p.z, 1, 0.02, 0.02, 0.02, 0);
                }
            }
            sl.sendParticles(ParticleTypes.SOUL_FIRE_FLAME, bout.x, bout.y, bout.z, 4, 0.2, 0.2, 0.2, 0.05);
            if (b % 3 == 0) {
                sl.playSound(null, o.x, o.y, o.z, SoundEvents.BEACON_AMBIENT, SoundSource.HOSTILE, 2F, 1.6F);
            }
            if (b % 4 == 0) {
                for (Player p : joueurs) {
                    Vec3 c = p.position().add(0, 0.9, 0);
                    double t = Mth.clamp(c.subtract(o).dot(dir), 0, l);
                    if (o.add(dir.scale(t)).distanceToSqr(c) < 1.3 * 1.3) {
                        toucher(p, enrage() ? 6 : 5, dir.multiply(0.4, 0, 0.4).add(0, 0.2, 0));
                        p.igniteForSeconds(2);
                    }
                }
            }
        }
        if (a >= DUREE_RAYON) {
            rayonCd = enrage() ? 240 : 320;
            passer(CHASSE);
        }
    }

    /** Ceux qui le collent : une onde d'âmes les repousse. */
    private void onde(ServerLevel sl, List<Player> joueurs) {
        flotter(null, 0);
        int a = age();
        if (a < 12) {
            sl.sendParticles(ParticleTypes.SOUL, getX(), getY() + 2.5, getZ(), 4, 1, 1.5, 1, 0.02);
        }
        if (a == 12) {
            sl.playSound(null, getX(), getY() + 3, getZ(), SoundEvents.WARDEN_SONIC_BOOM, SoundSource.HOSTILE, 2F, 0.8F);
            anneau(sl, position(), 9, ParticleTypes.SOUL_FIRE_FLAME);
            for (Player p : joueurs) {
                if (p.distanceTo(this) < 9) {
                    toucher(p, enrage() ? 10 : 8, dehors(p, position(), 2.2, 0.7));
                }
            }
        }
        if (a >= DUREE_ONDE) {
            ondeCd = 120;
            colle = 0;
            passer(CHASSE);
        }
    }

    @Override
    protected void rage(ServerLevel sl, List<Player> joueurs, int a) {
        flotter(null, 0);
        if (a == 4) {
            sl.playSound(null, getX(), getY() + 4, getZ(), SoundEvents.WITHER_AMBIENT, SoundSource.HOSTILE, 3F, 0.5F);
        }
        if (a < 30) {
            sl.sendParticles(ParticleTypes.SOUL, getX(), getY() + 3, getZ(), 5, 0.8, 1.5, 0.8, 0.1);
        }
        if (a == 30) {
            sl.playSound(null, getX(), getY() + 4, getZ(), SoundEvents.WITHER_SPAWN, SoundSource.HOSTILE, 2F, 0.9F);
            anneau(sl, position(), 12, CYAN);
            for (Player p : joueurs) {
                if (p.distanceTo(this) < 12) {
                    toucher(p, 5, dehors(p, position(), 1.6, 0.5));
                }
            }
        }
    }

    @Override
    protected void invocation(ServerLevel sl, int a) {
        flotter(null, 0);
        if (a == 6) {
            sl.playSound(null, getX(), getY() + 4, getZ(), SoundEvents.EVOKER_PREPARE_SUMMON, SoundSource.HOSTILE, 3F, 0.5F);
        }
    }

    /** Des squelettes se relèvent du sol de l'arène. */
    @Override
    protected void invoquer(ServerLevel sl, int n) {
        Vec3 c = arene();
        int total = Math.min(Boss.placeArene(sl, c), 2 + n);
        for (int i = 0; i < total; i++) {
            double ang = random.nextDouble() * Math.PI * 2, d = 9 + random.nextDouble() * 8;
            Vec3 p = c.add(Math.cos(ang) * d, 0, Math.sin(ang) * d);
            Boss.poserMob(sl, EntityType.SKELETON, p.x, p.y, p.z);
            sl.sendParticles(ParticleTypes.SOUL, p.x, p.y + 0.5, p.z, 20, 0.4, 0.6, 0.4, 0.05);
            sl.sendParticles(new net.minecraft.core.particles.BlockParticleOption(ParticleTypes.BLOCK,
                    net.minecraft.world.level.block.Blocks.SOUL_SOIL.defaultBlockState()), p.x, p.y + 0.2, p.z, 20, 0.4, 0.1, 0.4, 0.1);
        }
    }

    @Override
    protected void tickDeath() {
        if (deathTime == 1 && level() instanceof ServerLevel sl) {
            sl.playSound(null, getX(), getY() + 3, getZ(), SoundEvents.WITHER_DEATH, SoundSource.HOSTILE, 2F, 0.8F);
        }
        if (level() instanceof ServerLevel sl && deathTime % 3 == 0) {
            sl.sendParticles(ParticleTypes.SOUL, getX(), getY() + 2.5, getZ(), 6, 0.6, 1.5, 0.6, 0.06);
        }
        super.tickDeath();
    }
}
