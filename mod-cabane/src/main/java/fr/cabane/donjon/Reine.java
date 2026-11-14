package fr.cabane.donjon;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.BossEvent;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.AreaEffectCloud;
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
 * Arachna, la Reine-Araignée : le boss du Nid de soie. Un abdomen noir marqué d'un sablier rouge qui luit, huit pattes
 * qui couvrent dix blocs, huit yeux rouges, des crochets. Elle dort suspendue à un fil au-dessus de son nid ; quand on
 * entre, elle descend lentement le long de son fil, se pose, et hurle.
 *
 * <p>Ses attaques : la morsure (elle se jette en avant, et empoisonne) ; la toile, une boule de soie qui colle sa cible
 * au sol (trois en éventail quand elle est en rage) ; le bond, qui retombe sur un joueur ; le venin, des flaques
 * empoisonnées sous les pieds de tout le monde. Ses enfants sortent des cocons pour l'aider.
 */
public class Reine extends BossDonjon {
    public static final int MORSURE = 10, TOILE = 11, BOND = 12, VENIN = 13;
    public static final int DUREE_REVEIL = 66, DESCENTE = 40, DUREE_MORSURE = 24, DUREE_TOILE = 30, DUREE_VENIN = 40, DUREE_RAGE = 44;
    /** À quelle hauteur elle pend au-dessus de son nid, endormie. */
    public static final double HAUTEUR_FIL = 12;
    private static final int TIR_TOILE = 1;

    private int morsureCd = 20, toileCd = 60, bondCd = 140, veninCd = 120;
    private boolean atterri;
    private int apresAtterrissage;

    public Reine(EntityType<? extends Reine> type, Level level) {
        super(type, level);
    }

    public static AttributeSupplier.Builder attributs() {
        return Monster.createMonsterAttributes().add(Attributes.MAX_HEALTH, VIE).add(Attributes.ATTACK_DAMAGE, 10).add(Attributes.ARMOR, 10)
                .add(Attributes.ARMOR_TOUGHNESS, 4).add(Attributes.MOVEMENT_SPEED, 0.33).add(Attributes.FOLLOW_RANGE, 64)
                .add(Attributes.KNOCKBACK_RESISTANCE, 1).add(Attributes.STEP_HEIGHT, 2.1).add(Attributes.SAFE_FALL_DISTANCE, 64);
    }

    @Override
    public TypeDonjon typeDonjon() {
        return TypeDonjon.ARAIGNEE;
    }

    @Override
    public float echellePv() {
        return 2.8F;
    }

    @Override
    protected BossEvent.BossBarColor couleurBarre() {
        return BossEvent.BossBarColor.GREEN;
    }

    @Override
    protected int dureeReveil() {
        return DUREE_REVEIL;
    }

    @Override
    protected int dureeRage() {
        return DUREE_RAGE;
    }

    private static final DustParticleOptions VERT = new DustParticleOptions(new Vector3f(0.45F, 0.95F, 0.2F), 1.4F);
    private static final BlockParticleOption SOIE = new BlockParticleOption(ParticleTypes.BLOCK, Blocks.COBWEB.defaultBlockState());

    /** Endormie : suspendue à son fil, au-dessus du nid. */
    @Override
    protected void dormir() {
        setNoGravity(true);
        Vec3 r = repos();
        setPos(r.x, r.y + HAUTEUR_FIL, r.z);
        setDeltaMovement(Vec3.ZERO);
        regarder(arene());
    }

    @Override
    protected void reveil(ServerLevel sl, int a) {
        Vec3 r = repos();
        if (a <= DESCENTE) {
            // elle descend le long de son fil, de plus en plus vite
            double k = (double) a / DESCENTE;
            setNoGravity(true);
            setPos(r.x, r.y + HAUTEUR_FIL * (1 - k * k), r.z);
            setDeltaMovement(Vec3.ZERO);
            if (a % 6 == 0) {
                sl.playSound(null, getX(), getY(), getZ(), SoundEvents.SPIDER_STEP, SoundSource.HOSTILE, 1.5F, 0.5F);
            }
        }
        if (a == DESCENTE) {
            setNoGravity(false);
            sl.playSound(null, getX(), getY(), getZ(), SoundEvents.GENERIC_EXPLODE.value(), SoundSource.HOSTILE, 1.2F, 1.4F);
            sl.sendParticles(ParticleTypes.CAMPFIRE_COSY_SMOKE, getX(), getY() + 0.2, getZ(), 30, 3, 0.1, 3, 0.02);
        }
        if (a == DESCENTE + 6) {
            sl.playSound(null, getX(), getY() + 2, getZ(), SoundEvents.SPIDER_AMBIENT, SoundSource.HOSTILE, 4F, 0.4F);
            sl.playSound(null, getX(), getY() + 2, getZ(), SoundEvents.WARDEN_ROAR, SoundSource.HOSTILE, 2.5F, 1.2F);
        }
    }

    @Override
    protected void chasse(ServerLevel sl, List<Player> joueurs) {
        setNoGravity(false);
        morsureCd--;
        toileCd--;
        bondCd--;
        veninCd--;
        double d = distanceTo(cible);
        getNavigation().moveTo(cible, enrage() ? 1.35 : 1.1);
        getLookControl().setLookAt(cible, 30, 30);
        if (getDeltaMovement().horizontalDistanceSqr() > 0.003 && tickCount % 6 == 0) {
            sl.playSound(null, getX(), getY(), getZ(), SoundEvents.SPIDER_STEP, SoundSource.HOSTILE, 1.4F, 0.5F);
        }
        if (d < 5.5 && morsureCd <= 0) {
            regarder(cible.position());
            passer(MORSURE);
        } else if (veninCd <= 0 && d < 20) {
            passer(VENIN);
        } else if (bondCd <= 0 && d > 6 && d < 22) {
            regarder(cible.position());
            atterri = false;
            apresAtterrissage = 0;
            passer(BOND);
        } else if (toileCd <= 0 && d > 4) {
            regarder(cible.position());
            passer(TOILE);
        }
    }

    @Override
    protected void action(ServerLevel sl, List<Player> joueurs, int etat) {
        switch (etat) {
            case MORSURE -> morsure(sl, joueurs);
            case TOILE -> toile(sl);
            case BOND -> bond(sl, joueurs);
            case VENIN -> venin(sl, joueurs);
            default -> passer(CHASSE);
        }
    }

    /** Elle se cabre, puis se jette en avant, crochets ouverts. */
    private void morsure(ServerLevel sl, List<Player> joueurs) {
        int a = age();
        if (a < 10) {
            setDeltaMovement(0, getDeltaMovement().y, 0);
            if (cible != null) {
                regarder(cible.position());
            }
        }
        if (a == 3) {
            sl.playSound(null, getX(), getY() + 2, getZ(), SoundEvents.SPIDER_AMBIENT, SoundSource.HOSTILE, 2.5F, 0.6F);
        }
        if (a == 10) {
            Vec3 av = avant();
            setDeltaMovement(av.x * 0.9, 0.15, av.z * 0.9);
            hasImpulse = true;
        }
        if (a == 13) {
            Vec3 av = avant();
            for (Player p : joueurs) {
                Vec3 vers = p.position().subtract(position());
                if (vers.horizontalDistance() < 6 && vers.normalize().dot(av) > 0.35) {
                    toucher(p, enrage() ? 15 : 12, new Vec3(av.x * 0.8, 0.35, av.z * 0.8));
                    p.addEffect(new MobEffectInstance(MobEffects.POISON, enrage() ? 100 : 70, 1));
                }
            }
            sl.playSound(null, getX(), getY() + 1, getZ(), SoundEvents.PLAYER_ATTACK_STRONG, SoundSource.HOSTILE, 2F, 0.5F);
        }
        if (a > 13) {
            setDeltaMovement(getDeltaMovement().multiply(0.6, 1, 0.6));
        }
        if (a >= DUREE_MORSURE) {
            morsureCd = enrage() ? 16 : 26;
            passer(CHASSE);
        }
    }

    /** L'abdomen se lève, vise, et lance une boule de soie (trois en rage). */
    private void toile(ServerLevel sl) {
        setDeltaMovement(0, getDeltaMovement().y, 0);
        int a = age();
        if (cible != null && a < 14) {
            regarder(cible.position());
        }
        if (a == 14 && cible != null) {
            Vec3 depart = position().add(0, 2.6, 0).add(avant().scale(-1.5));
            Vec3 vers = cible.position().add(0, 1, 0).subtract(depart);
            int n = enrage() ? 3 : 1;
            for (int k = 0; k < n; k++) {
                double ang = (k - (n - 1) / 2.0) * 0.28;
                Vec3 v = new Vec3(vers.x * Math.cos(ang) - vers.z * Math.sin(ang), vers.y, vers.x * Math.sin(ang) + vers.z * Math.cos(ang)).normalize().scale(1.1);
                tirer(depart, v.add(0, 0.12, 0), 60, cible, 0.03, TIR_TOILE);
            }
            sl.playSound(null, depart.x, depart.y, depart.z, SoundEvents.LLAMA_SPIT, SoundSource.HOSTILE, 2.5F, 0.5F);
        }
        if (a >= DUREE_TOILE) {
            toileCd = enrage() ? 60 : 90;
            passer(CHASSE);
        }
    }

    @Override
    protected double gravite(int genre) {
        return 0.012;
    }

    @Override
    protected void traine(ServerLevel sl, Vec3 p, int genre) {
        sl.sendParticles(SOIE, p.x, p.y, p.z, 2, 0.1, 0.1, 0.1, 0);
        sl.sendParticles(ParticleTypes.WHITE_ASH, p.x, p.y, p.z, 4, 0.15, 0.15, 0.15, 0);
    }

    /** La soie colle : une toile sous la cible, et elle ne peut presque plus bouger. */
    @Override
    protected void impact(ServerLevel sl, Vec3 p, int genre, Player joueur) {
        sl.sendParticles(SOIE, p.x, p.y, p.z, 20, 0.5, 0.5, 0.5, 0.1);
        sl.playSound(null, p.x, p.y, p.z, SoundEvents.SLIME_SQUISH, SoundSource.HOSTILE, 1.5F, 0.6F);
        if (joueur != null) {
            joueur.hurt(coup(), 6);
            joueur.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 60, 3));
            Ephemeres.poser(sl, joueur.blockPosition(), Blocks.COBWEB.defaultBlockState(), 60);
            if (joueur instanceof net.minecraft.server.level.ServerPlayer sp) {
                sp.displayClientMessage(net.minecraft.network.chat.Component.literal("Pris dans la toile !")
                        .withStyle(net.minecraft.ChatFormatting.WHITE), true);
            }
        } else {
            Ephemeres.poser(sl, BlockPos.containing(p), Blocks.COBWEB.defaultBlockState(), 80);
        }
    }

    /** Elle se ramasse sur ses pattes, bondit, et retombe sur sa cible. */
    private void bond(ServerLevel sl, List<Player> joueurs) {
        int a = age();
        if (a < 12) {
            setDeltaMovement(0, getDeltaMovement().y, 0);
            if (cible != null) {
                regarder(cible.position());
            }
            return;
        }
        if (a == 12) {
            Vec3 but = cible != null ? cible.position() : arene();
            Vec3 d = but.subtract(position());
            setDeltaMovement(d.x / 16, 0.95, d.z / 16);
            hasImpulse = true;
            sl.playSound(null, getX(), getY(), getZ(), SoundEvents.SPIDER_AMBIENT, SoundSource.HOSTILE, 3F, 0.8F);
            return;
        }
        if (!atterri && a > 16 && (onGround() || a > 50)) {
            atterri = true;
            double rayon = enrage() ? 7 : 6;
            for (Player p : joueurs) {
                double dist = p.distanceTo(this);
                if (dist < rayon) {
                    toucher(p, (enrage() ? 14 : 11) * (float) (1 - dist / rayon * 0.5), dehors(p, position(), 1, 0.6));
                }
            }
            sl.playSound(null, getX(), getY(), getZ(), SoundEvents.GENERIC_EXPLODE.value(), SoundSource.HOSTILE, 1.8F, 0.8F);
            sl.sendParticles(ParticleTypes.CAMPFIRE_COSY_SMOKE, getX(), getY() + 0.2, getZ(), 30, 2.5, 0.1, 2.5, 0.03);
            anneau(sl, position(), rayon, ParticleTypes.CAMPFIRE_COSY_SMOKE);
        }
        if (atterri && ++apresAtterrissage > 14) {
            bondCd = enrage() ? 110 : 160;
            passer(CHASSE);
        }
    }

    /** Elle se dresse et crache son venin : une flaque verte sous chacun, qui empoisonne. */
    private void venin(ServerLevel sl, List<Player> joueurs) {
        setDeltaMovement(0, getDeltaMovement().y, 0);
        int a = age();
        if (a == 2) {
            sl.playSound(null, getX(), getY() + 2, getZ(), SoundEvents.SPIDER_AMBIENT, SoundSource.HOSTILE, 3F, 0.5F);
        }
        if (a > 4 && a < 20) {
            sl.sendParticles(VERT, getX(), getY() + 3, getZ(), 6, 0.6, 0.4, 0.6, 0);
        }
        if (a == 20) {
            sl.playSound(null, getX(), getY() + 2, getZ(), SoundEvents.LLAMA_SPIT, SoundSource.HOSTILE, 3F, 0.4F);
            for (Player p : joueurs) {
                AreaEffectCloud nuage = new AreaEffectCloud(sl, p.getX(), p.getY(), p.getZ());
                nuage.setRadius(enrage() ? 3.5F : 2.8F);
                nuage.setRadiusPerTick(-0.01F);
                nuage.setDuration(140);
                nuage.setWaitTime(8);
                nuage.setOwner(this);
                nuage.setParticle(ParticleTypes.ITEM_SLIME);
                nuage.addEffect(new MobEffectInstance(MobEffects.POISON, 80, 1));
                sl.addFreshEntity(nuage);
                sl.sendParticles(VERT, p.getX(), p.getY() + 0.3, p.getZ(), 30, 1.2, 0.2, 1.2, 0);
            }
        }
        if (a >= DUREE_VENIN) {
            veninCd = enrage() ? 160 : 230;
            passer(CHASSE);
        }
    }

    @Override
    protected void rage(ServerLevel sl, List<Player> joueurs, int a) {
        if (a == 12) {
            sl.playSound(null, getX(), getY() + 2, getZ(), SoundEvents.SPIDER_AMBIENT, SoundSource.HOSTILE, 4F, 0.3F);
            sl.playSound(null, getX(), getY() + 2, getZ(), SoundEvents.WARDEN_ROAR, SoundSource.HOSTILE, 3F, 1.0F);
            anneau(sl, position(), 12, VERT);
            for (Player p : joueurs) {
                if (p.distanceTo(this) < 12) {
                    toucher(p, 5, dehors(p, position(), 1.6, 0.5));
                }
            }
        }
    }

    @Override
    protected void invocation(ServerLevel sl, int a) {
        if (a == 8) {
            sl.playSound(null, getX(), getY() + 2, getZ(), SoundEvents.SPIDER_AMBIENT, SoundSource.HOSTILE, 4F, 1.4F);
        }
    }

    /** Ses enfants : des araignées venimeuses qui sortent des cocons, autour de l'arène. */
    @Override
    protected void invoquer(ServerLevel sl, int n) {
        Vec3 c = arene();
        int total = Math.min(Boss.placeArene(sl, c), 3 + n);
        for (int i = 0; i < total; i++) {
            double ang = random.nextDouble() * Math.PI * 2, d = 10 + random.nextDouble() * 8;
            Vec3 p = c.add(Math.cos(ang) * d, 0, Math.sin(ang) * d);
            Boss.poserMob(sl, EntityType.CAVE_SPIDER, p.x, p.y, p.z);
            sl.sendParticles(SOIE, p.x, p.y + 0.5, p.z, 20, 0.4, 0.4, 0.4, 0.1);
        }
    }

    @Override
    protected void rendormir() {
        super.rendormir();
        dormir();
    }
}
