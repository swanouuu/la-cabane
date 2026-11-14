package fr.cabane.donjon;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.BossEvent;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
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
 * Kar'Thax, le Roi des Fourmis : le boss de la Fourmilière royale. Un guerrier insecte de quatre blocs et demi, mince
 * et rapide, sa carapace noire veinée d'orange, deux bras qui finissent en faux, quatre ailes, des mandibules. Il dort
 * accroupi, enveloppé dans ses ailes ; au réveil, il se déplie, ses ailes claquent, et il hurle.
 *
 * <p>Il ne s'arrête jamais : le combo de trois coups de faux ; la ruée, qui traverse l'arène (trois de suite en rage) ;
 * le piqué, il s'envole et retombe sur sa cible ; l'acide, trois crachats qui éclaboussent. Ses ouvrières sortent du
 * sol.
 */
public class RoiFourmi extends BossDonjon {
    public static final int COMBO = 10, RUEE = 11, PIQUE = 12, ACIDE = 13;
    public static final int DUREE_REVEIL = 62, DUREE_COMBO = 36, PREPA_RUEE = 10, DUREE_RUEE = 22, MONTEE_PIQUE = 26, DUREE_ACIDE = 34, DUREE_RAGE = 44;
    private static final int TIR_ACIDE = 3;

    private static final DustParticleOptions ORANGE = new DustParticleOptions(new Vector3f(1F, 0.55F, 0.1F), 1.3F);
    private static final DustParticleOptions ACIDE_VERT = new DustParticleOptions(new Vector3f(0.75F, 1F, 0.15F), 1.4F);

    private int comboCd = 10, rueeCd = 80, piqueCd = 160, acideCd = 70;
    private int ruees;
    private Vec3 dirRuee = Vec3.ZERO;
    private final List<UUID> touches = new ArrayList<>();
    private Vec3 cibleePique = Vec3.ZERO;
    private boolean atterri;
    private int apres;

    public RoiFourmi(EntityType<? extends RoiFourmi> type, Level level) {
        super(type, level);
    }

    public static AttributeSupplier.Builder attributs() {
        return Monster.createMonsterAttributes().add(Attributes.MAX_HEALTH, VIE).add(Attributes.ATTACK_DAMAGE, 10).add(Attributes.ARMOR, 10)
                .add(Attributes.ARMOR_TOUGHNESS, 4).add(Attributes.MOVEMENT_SPEED, 0.38).add(Attributes.FOLLOW_RANGE, 64)
                .add(Attributes.KNOCKBACK_RESISTANCE, 1).add(Attributes.STEP_HEIGHT, 1.6).add(Attributes.SAFE_FALL_DISTANCE, 64);
    }

    @Override
    public TypeDonjon typeDonjon() {
        return TypeDonjon.FOURMI;
    }

    @Override
    public float echellePv() {
        return 2.6F;
    }

    @Override
    protected BossEvent.BossBarColor couleurBarre() {
        return BossEvent.BossBarColor.YELLOW;
    }

    @Override
    protected int dureeReveil() {
        return DUREE_REVEIL;
    }

    @Override
    protected int dureeRage() {
        return DUREE_RAGE;
    }

    @Override
    public void tick() {
        super.tick();
        if (level().isClientSide && isAlive() && enrage() && random.nextInt(3) == 0) {
            level().addParticle(ParticleTypes.FLAME, getX() + random.nextGaussian() * 0.4, getY() + 3 + random.nextDouble(), getZ() + random.nextGaussian() * 0.4, 0, 0.02, 0);
        }
    }

    @Override
    protected void reveil(ServerLevel sl, int a) {
        if (a == 24) {
            sl.playSound(null, getX(), getY() + 3, getZ(), SoundEvents.PHANTOM_FLAP, SoundSource.HOSTILE, 4F, 0.5F);
            sl.playSound(null, getX(), getY() + 3, getZ(), SoundEvents.ELYTRA_FLYING, SoundSource.HOSTILE, 0.6F, 1.6F);
            sl.sendParticles(ParticleTypes.CAMPFIRE_COSY_SMOKE, getX(), getY() + 0.3, getZ(), 30, 2.5, 0.2, 2.5, 0.03);
        }
        if (a == 34) {
            sl.playSound(null, getX(), getY() + 3, getZ(), SoundEvents.SILVERFISH_AMBIENT, SoundSource.HOSTILE, 4F, 0.4F);
            sl.playSound(null, getX(), getY() + 3, getZ(), SoundEvents.RAVAGER_ROAR, SoundSource.HOSTILE, 3F, 1.3F);
            sl.sendParticles(ORANGE, getX(), getY() + 3, getZ(), 40, 1, 1, 1, 0);
        }
    }

    @Override
    protected void chasse(ServerLevel sl, List<Player> joueurs) {
        setNoGravity(false);
        comboCd--;
        rueeCd--;
        piqueCd--;
        acideCd--;
        double d = distanceTo(cible);
        getNavigation().moveTo(cible, enrage() ? 1.4 : 1.15);
        getLookControl().setLookAt(cible, 30, 30);
        if (getDeltaMovement().horizontalDistanceSqr() > 0.003 && tickCount % 5 == 0) {
            sl.playSound(null, getX(), getY(), getZ(), SoundEvents.SPIDER_STEP, SoundSource.HOSTILE, 1F, 1.3F);
        }
        if (d < 4.5 && comboCd <= 0) {
            regarder(cible.position());
            passer(COMBO);
        } else if (piqueCd <= 0 && d > 5) {
            cibleePique = cible.position();
            atterri = false;
            apres = 0;
            passer(PIQUE);
        } else if (rueeCd <= 0 && d > 6) {
            regarder(cible.position());
            ruees = enrage() ? 3 : 1;
            touches.clear();
            passer(RUEE);
        } else if (acideCd <= 0 && d > 5) {
            regarder(cible.position());
            passer(ACIDE);
        }
    }

    @Override
    protected void action(ServerLevel sl, List<Player> joueurs, int etat) {
        switch (etat) {
            case COMBO -> combo(sl, joueurs);
            case RUEE -> ruee(sl, joueurs);
            case PIQUE -> pique(sl, joueurs);
            case ACIDE -> acide(sl);
            default -> passer(CHASSE);
        }
    }

    /** Trois coups de faux : de droite, de gauche, puis les deux d'en haut ; un pas en avant à chaque coup. */
    private void combo(ServerLevel sl, List<Player> joueurs) {
        int a = age();
        if (cible != null && (a < 8 || (a > 12 && a < 16) || (a > 20 && a < 25))) {
            regarder(cible.position());
        }
        setDeltaMovement(getDeltaMovement().multiply(0.5, 1, 0.5));
        int[] coups = {8, 16, 26};
        for (int k = 0; k < 3; k++) {
            if (a == coups[k] - 3) {
                Vec3 av = avant();
                setDeltaMovement(av.x * 0.45, getDeltaMovement().y, av.z * 0.45);
            }
            if (a == coups[k]) {
                Vec3 av = avant();
                boolean dernier = k == 2;
                for (Player p : joueurs) {
                    Vec3 vers = p.position().subtract(position());
                    if (vers.horizontalDistance() < (dernier ? 5.5 : 4.8) && vers.normalize().dot(av) > 0.2) {
                        toucher(p, (dernier ? 11 : 7) * (enrage() ? 1.25F : 1F), new Vec3(av.x * (dernier ? 1.2 : 0.4), dernier ? 0.5 : 0.15,
                                av.z * (dernier ? 1.2 : 0.4)));
                    }
                }
                Vec3 pt = position().add(0, 2, 0).add(av.scale(2.5));
                sl.sendParticles(ParticleTypes.SWEEP_ATTACK, pt.x, pt.y, pt.z, dernier ? 3 : 1, 0.6, 0.3, 0.6, 0);
                sl.playSound(null, pt.x, pt.y, pt.z, SoundEvents.PLAYER_ATTACK_SWEEP, SoundSource.HOSTILE, 2F, dernier ? 0.5F : 0.8F);
                if (dernier) {
                    sl.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, Blocks.PACKED_MUD.defaultBlockState()), pt.x, getY() + 0.2, pt.z, 30, 1, 0.1,
                            1, 0.2);
                }
            }
        }
        if (a >= DUREE_COMBO) {
            comboCd = enrage() ? 10 : 22;
            passer(CHASSE);
        }
    }

    /** La ruée : il se ramasse, puis traverse l'arène comme une flèche ; tout ce qu'il croise est fauché. */
    private void ruee(ServerLevel sl, List<Player> joueurs) {
        int a = age();
        if (a < PREPA_RUEE) {
            setDeltaMovement(0, getDeltaMovement().y, 0);
            if (cible != null) {
                regarder(cible.position());
            }
            if (a == 1) {
                sl.playSound(null, getX(), getY() + 2, getZ(), SoundEvents.SILVERFISH_AMBIENT, SoundSource.HOSTILE, 3F, 0.6F);
            }
            if (a == PREPA_RUEE - 1) {
                dirRuee = avant().normalize();
            }
            return;
        }
        if (a < DUREE_RUEE) {
            double v = enrage() ? 1.45 : 1.25;
            setDeltaMovement(dirRuee.x * v, getDeltaMovement().y, dirRuee.z * v);
            regarder(position().add(dirRuee));
            sl.sendParticles(ORANGE, getX(), getY() + 2, getZ(), 6, 0.3, 1, 0.3, 0);
            sl.sendParticles(ParticleTypes.CAMPFIRE_COSY_SMOKE, getX(), getY() + 0.2, getZ(), 2, 0.4, 0.05, 0.4, 0.01);
            if (a == PREPA_RUEE) {
                sl.playSound(null, getX(), getY() + 2, getZ(), SoundEvents.PHANTOM_SWOOP, SoundSource.HOSTILE, 3F, 0.8F);
            }
            for (Player p : joueurs) {
                if (!touches.contains(p.getUUID()) && p.distanceTo(this) < 2.8) {
                    touches.add(p.getUUID());
                    toucher(p, enrage() ? 13 : 11, new Vec3(dirRuee.x * 0.9, 0.5, dirRuee.z * 0.9));
                    sl.sendParticles(ParticleTypes.SWEEP_ATTACK, p.getX(), p.getY() + 1, p.getZ(), 2, 0.3, 0.3, 0.3, 0);
                }
            }
            if (horizontalCollision) {
                setDeltaMovement(0, getDeltaMovement().y, 0);
            }
            return;
        }
        setDeltaMovement(getDeltaMovement().multiply(0.4, 1, 0.4));
        if (--ruees > 0 && cible != null) {
            touches.clear();
            regarder(cible.position());
            passer(RUEE);
            debut = tickCount - PREPA_RUEE / 2;
            return;
        }
        rueeCd = enrage() ? 90 : 140;
        passer(CHASSE);
    }

    /** Le piqué : il s'envole à grands battements, plane au-dessus de sa cible, puis tombe sur elle, faux en avant. */
    private void pique(ServerLevel sl, List<Player> joueurs) {
        int a = age();
        if (a < MONTEE_PIQUE) {
            setNoGravity(true);
            if (cible != null) {
                cibleePique = cible.position();
                regarder(cibleePique);
            }
            Vec3 haut = cibleePique.add(0, 9, 0);
            Vec3 v = haut.subtract(position()).scale(0.09);
            setDeltaMovement(v.x, Math.min(0.6, v.y), v.z);
            if (a % 4 == 0) {
                sl.playSound(null, getX(), getY() + 2, getZ(), SoundEvents.PHANTOM_FLAP, SoundSource.HOSTILE, 2.5F, 0.6F);
            }
            return;
        }
        if (a < MONTEE_PIQUE + 8) {
            setDeltaMovement(0, 0, 0);
            if (a == MONTEE_PIQUE + 1) {
                sl.playSound(null, getX(), getY() + 2, getZ(), SoundEvents.SILVERFISH_AMBIENT, SoundSource.HOSTILE, 3F, 0.4F);
            }
            // un cercle orange sous lui : là où il va tomber
            for (int k = 0; k < 12; k++) {
                double ang = k * Math.PI / 6 + a * 0.2;
                sl.sendParticles(ORANGE, cibleePique.x + Math.cos(ang) * 3, cibleePique.y + 0.2, cibleePique.z + Math.sin(ang) * 3, 1, 0, 0, 0, 0);
            }
            return;
        }
        if (!atterri) {
            setNoGravity(false);
            Vec3 v = cibleePique.subtract(position()).normalize().scale(1.6);
            setDeltaMovement(v);
            sl.sendParticles(ORANGE, getX(), getY() + 2, getZ(), 4, 0.3, 0.6, 0.3, 0);
            if (onGround() || a > MONTEE_PIQUE + 30 || position().distanceToSqr(cibleePique) < 1.5) {
                atterri = true;
                setDeltaMovement(0, 0, 0);
                double rayon = 5;
                for (Player p : joueurs) {
                    double dist = p.distanceTo(this);
                    if (dist < rayon) {
                        toucher(p, (enrage() ? 16 : 13) * (float) (1 - dist / rayon * 0.5), dehors(p, position(), 1.1, 0.6));
                    }
                }
                sl.playSound(null, getX(), getY(), getZ(), SoundEvents.GENERIC_EXPLODE.value(), SoundSource.HOSTILE, 2F, 0.8F);
                sl.sendParticles(ParticleTypes.EXPLOSION, getX(), getY() + 0.5, getZ(), 2, 0.5, 0.1, 0.5, 0);
                anneau(sl, position(), rayon, ParticleTypes.CAMPFIRE_COSY_SMOKE);
            }
            return;
        }
        if (++apres > 12) {
            piqueCd = enrage() ? 130 : 190;
            passer(CHASSE);
        }
    }

    /** Il se cabre et crache trois boules d'acide en cloche ; elles éclaboussent à l'arrivée. */
    private void acide(ServerLevel sl) {
        setDeltaMovement(0, getDeltaMovement().y, 0);
        int a = age();
        if (cible != null) {
            regarder(cible.position());
        }
        if ((a == 10 || a == 16 || a == 22) && cible != null) {
            Vec3 o = position().add(0, 3.6, 0).add(avant().scale(0.8));
            Vec3 but = cible.position().add(random.nextGaussian() * 1.5, 0, random.nextGaussian() * 1.5);
            Vec3 d = but.subtract(o);
            double t = 22;
            tirer(o, new Vec3(d.x / t, d.y / t + 0.03 * t / 2, d.z / t), 60, null, 0, TIR_ACIDE);
            sl.playSound(null, o.x, o.y, o.z, SoundEvents.LLAMA_SPIT, SoundSource.HOSTILE, 2F, 0.8F);
        }
        if (a >= DUREE_ACIDE) {
            acideCd = enrage() ? 70 : 110;
            passer(CHASSE);
        }
    }

    @Override
    protected double gravite(int genre) {
        return 0.03;
    }

    @Override
    protected void traine(ServerLevel sl, Vec3 p, int genre) {
        sl.sendParticles(ACIDE_VERT, p.x, p.y, p.z, 3, 0.1, 0.1, 0.1, 0);
        sl.sendParticles(ParticleTypes.FALLING_HONEY, p.x, p.y, p.z, 1, 0.1, 0.1, 0.1, 0);
    }

    @Override
    protected void impact(ServerLevel sl, Vec3 p, int genre, Player joueur) {
        sl.playSound(null, p.x, p.y, p.z, SoundEvents.SLIME_SQUISH, SoundSource.HOSTILE, 1.5F, 0.6F);
        sl.playSound(null, p.x, p.y, p.z, SoundEvents.FIRE_EXTINGUISH, SoundSource.HOSTILE, 1F, 1.5F);
        sl.sendParticles(ACIDE_VERT, p.x, p.y + 0.2, p.z, 30, 1.2, 0.2, 1.2, 0);
        for (Player pl : dansArene()) {
            if (pl.position().distanceToSqr(p) < 2.4 * 2.4) {
                pl.hurt(coup(), enrage() ? 9 : 7);
                pl.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, 80, 0));
            }
        }
    }

    @Override
    protected void rage(ServerLevel sl, List<Player> joueurs, int a) {
        if (a == 2) {
            sl.playSound(null, getX(), getY() + 3, getZ(), SoundEvents.PHANTOM_FLAP, SoundSource.HOSTILE, 4F, 0.4F);
        }
        if (a == 16) {
            sl.playSound(null, getX(), getY() + 3, getZ(), SoundEvents.SILVERFISH_AMBIENT, SoundSource.HOSTILE, 4F, 0.3F);
            sl.playSound(null, getX(), getY() + 3, getZ(), SoundEvents.RAVAGER_ROAR, SoundSource.HOSTILE, 3F, 1.4F);
            anneau(sl, position(), 12, ORANGE);
            for (Player p : joueurs) {
                if (p.distanceTo(this) < 12) {
                    toucher(p, 5, dehors(p, position(), 1.8, 0.5));
                }
            }
        }
    }

    @Override
    protected void invocation(ServerLevel sl, int a) {
        if (a == 8) {
            sl.playSound(null, getX(), getY() + 3, getZ(), SoundEvents.SILVERFISH_AMBIENT, SoundSource.HOSTILE, 4F, 0.8F);
        }
    }

    /** Ses ouvrières sortent de terre, autour de l'arène. */
    @Override
    protected void invoquer(ServerLevel sl, int n) {
        Vec3 c = arene();
        int total = Math.min(Boss.placeArene(sl, c), 4 + n * 2);
        for (int i = 0; i < total; i++) {
            double ang = random.nextDouble() * Math.PI * 2, d = 9 + random.nextDouble() * 9;
            Vec3 p = c.add(Math.cos(ang) * d, 0, Math.sin(ang) * d);
            Boss.poserMob(sl, EntityType.SILVERFISH, p.x, p.y, p.z);
            sl.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, Blocks.COARSE_DIRT.defaultBlockState()), p.x, p.y + 0.2, p.z, 20, 0.4, 0.1, 0.4, 0.1);
        }
    }
}
