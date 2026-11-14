package fr.cabane.donjon;

import java.util.ArrayList;
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
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

/**
 * Hrimgar, le Colosse de Givre : le boss du Glacier hurlant. Huit blocs de glace tassée autour d'un cœur de pierre
 * bleue, des épaules hérissées de cristaux, deux poings comme des rochers. Il dort pris dans un bloc de glace ; au
 * réveil, la glace craque, éclate, et il rugit.
 *
 * <p>Ses attaques : le double coup de poings devant lui ; la ligne de pics de glace qui jaillit du sol jusqu'à sa
 * cible (trois lignes en éventail quand il est en rage) ; le souffle de givre, un cône qui gèle ; et, en rage, la grêle
 * : des blocs de glace qui tombent du plafond sur des cercles bleus. En rage, un blizzard l'entoure et ralentit ceux
 * qui l'approchent.
 */
public class Colosse extends BossDonjon {
    public static final int FRAPPE = 10, PICS = 11, SOUFFLE = 12, GRELE = 13;
    public static final int DUREE_REVEIL = 70, DUREE_FRAPPE = 30, IMPACT_FRAPPE = 16, DUREE_PICS = 52, DUREE_SOUFFLE = 64, DUREE_GRELE = 56, DUREE_RAGE = 50;

    private int frappeCd = 20, picsCd = 80, souffleCd = 160, greleCd = 100;
    private final List<Vec3> lignes = new ArrayList<>();
    private final List<Vec3> grelons = new ArrayList<>();
    private Vec3 origine = Vec3.ZERO;

    public Colosse(EntityType<? extends Colosse> type, Level level) {
        super(type, level);
    }

    public static AttributeSupplier.Builder attributs() {
        return Monster.createMonsterAttributes().add(Attributes.MAX_HEALTH, VIE).add(Attributes.ATTACK_DAMAGE, 12).add(Attributes.ARMOR, 14)
                .add(Attributes.ARMOR_TOUGHNESS, 6).add(Attributes.MOVEMENT_SPEED, 0.24).add(Attributes.FOLLOW_RANGE, 64)
                .add(Attributes.KNOCKBACK_RESISTANCE, 1).add(Attributes.STEP_HEIGHT, 2.1).add(Attributes.SAFE_FALL_DISTANCE, 64);
    }

    @Override
    public TypeDonjon typeDonjon() {
        return TypeDonjon.GIVRE;
    }

    @Override
    public float echellePv() {
        return 3.2F;
    }

    @Override
    protected BossEvent.BossBarColor couleurBarre() {
        return BossEvent.BossBarColor.BLUE;
    }

    @Override
    protected int dureeReveil() {
        return DUREE_REVEIL;
    }

    @Override
    protected int dureeRage() {
        return DUREE_RAGE;
    }

    private static final BlockParticleOption GLACE = new BlockParticleOption(ParticleTypes.BLOCK, Blocks.PACKED_ICE.defaultBlockState());
    private static final DustParticleOptions BLEU = new DustParticleOptions(new Vector3f(0.45F, 0.85F, 1F), 1.6F);

    /** Le blizzard qui l'entoure en rage (côté client). */
    @Override
    public void tick() {
        super.tick();
        if (level().isClientSide && isAlive()) {
            if (etat() == SOMMEIL && random.nextInt(4) == 0) {
                level().addParticle(ParticleTypes.SNOWFLAKE, getX() + random.nextGaussian() * 2, getY() + random.nextDouble() * 8, getZ() + random.nextGaussian() * 2,
                        0, -0.02, 0);
            }
            if (enrage()) {
                for (int k = 0; k < 4; k++) {
                    double a = random.nextDouble() * Math.PI * 2, d = 2 + random.nextDouble() * 6;
                    level().addParticle(ParticleTypes.SNOWFLAKE, getX() + Math.cos(a) * d, getY() + random.nextDouble() * 7, getZ() + Math.sin(a) * d,
                            -Math.sin(a) * 0.3, 0.02, Math.cos(a) * 0.3);
                }
            }
        }
    }

    @Override
    protected void reveil(ServerLevel sl, int a) {
        if (a == 12 || a == 22) {
            sl.playSound(null, getX(), getY() + 4, getZ(), SoundEvents.GLASS_HIT, SoundSource.HOSTILE, 3F, 0.5F);
            sl.sendParticles(GLACE, getX(), getY() + 4, getZ(), 30, 1.5, 3, 1.5, 0.1);
        }
        if (a == 32) {
            // la glace éclate
            sl.playSound(null, getX(), getY() + 4, getZ(), SoundEvents.GLASS_BREAK, SoundSource.HOSTILE, 4F, 0.5F);
            sl.playSound(null, getX(), getY() + 4, getZ(), SoundEvents.GENERIC_EXPLODE.value(), SoundSource.HOSTILE, 1.5F, 1.3F);
            sl.sendParticles(GLACE, getX(), getY() + 4, getZ(), 200, 2.2, 3.5, 2.2, 0.4);
            sl.sendParticles(ParticleTypes.SNOWFLAKE, getX(), getY() + 4, getZ(), 120, 3, 3, 3, 0.2);
            for (Player p : dansArene()) {
                if (p.distanceTo(this) < 10) {
                    toucher(p, 3, dehors(p, position(), 1.2, 0.5));
                }
            }
        }
        if (a == 44) {
            sl.playSound(null, getX(), getY() + 6, getZ(), SoundEvents.RAVAGER_ROAR, SoundSource.HOSTILE, 4F, 0.4F);
            sl.playSound(null, getX(), getY() + 6, getZ(), SoundEvents.POLAR_BEAR_WARNING, SoundSource.HOSTILE, 4F, 0.4F);
        }
    }

    @Override
    protected void chasse(ServerLevel sl, List<Player> joueurs) {
        frappeCd--;
        picsCd--;
        souffleCd--;
        greleCd--;
        double d = distanceTo(cible);
        getNavigation().moveTo(cible, enrage() ? 1.25 : 1.0);
        getLookControl().setLookAt(cible, 30, 30);
        if (getDeltaMovement().horizontalDistanceSqr() > 0.003 && tickCount % 13 == 0) {
            sl.playSound(null, getX(), getY(), getZ(), SoundEvents.IRON_GOLEM_STEP, SoundSource.HOSTILE, 2.5F, 0.5F);
            sl.sendParticles(ParticleTypes.SNOWFLAKE, getX(), getY() + 0.2, getZ(), 10, 1, 0.1, 1, 0.05);
        }
        if (enrage() && tickCount % 20 == 0) {
            for (Player p : joueurs) {
                if (p.distanceTo(this) < 7) {
                    p.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 30, 1, false, true));
                    p.setTicksFrozen(Math.min(p.getTicksRequiredToFreeze() + 40, p.getTicksFrozen() + 30));
                }
            }
        }
        if (d < 6.5 && frappeCd <= 0) {
            regarder(cible.position());
            passer(FRAPPE);
        } else if (enrage() && greleCd <= 0) {
            passer(GRELE);
        } else if (souffleCd <= 0 && d < 11) {
            regarder(cible.position());
            passer(SOUFFLE);
        } else if (picsCd <= 0 && d > 5) {
            regarder(cible.position());
            passer(PICS);
        }
    }

    @Override
    protected void action(ServerLevel sl, List<Player> joueurs, int etat) {
        setDeltaMovement(0, getDeltaMovement().y, 0);
        switch (etat) {
            case FRAPPE -> frappe(sl, joueurs);
            case PICS -> pics(sl, joueurs);
            case SOUFFLE -> souffle(sl, joueurs);
            case GRELE -> grele(sl, joueurs);
            default -> passer(CHASSE);
        }
    }

    /** Les deux poings levés au-dessus de la tête, puis abattus devant lui. */
    private void frappe(ServerLevel sl, List<Player> joueurs) {
        int a = age();
        if (a < IMPACT_FRAPPE && cible != null) {
            regarder(cible.position());
        }
        if (a == 4) {
            sl.playSound(null, getX(), getY() + 5, getZ(), SoundEvents.IRON_GOLEM_ATTACK, SoundSource.HOSTILE, 3F, 0.5F);
        }
        if (a == IMPACT_FRAPPE) {
            Vec3 av = avant();
            Vec3 point = position().add(av.scale(4.5));
            for (Player p : joueurs) {
                Vec3 vers = p.position().subtract(position());
                if (vers.horizontalDistance() < 8 && vers.normalize().dot(av) > 0.3) {
                    toucher(p, enrage() ? 17 : 13, new Vec3(av.x * 1.2, 0.5, av.z * 1.2));
                    p.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 60, 1));
                }
            }
            sl.playSound(null, point.x, point.y, point.z, SoundEvents.GENERIC_EXPLODE.value(), SoundSource.HOSTILE, 1.8F, 0.7F);
            sl.playSound(null, point.x, point.y, point.z, SoundEvents.GLASS_BREAK, SoundSource.HOSTILE, 2F, 0.6F);
            sl.sendParticles(GLACE, point.x, point.y + 0.3, point.z, 60, 1.4, 0.3, 1.4, 0.3);
            sl.sendParticles(ParticleTypes.SNOWFLAKE, point.x, point.y + 0.5, point.z, 40, 2, 0.5, 2, 0.1);
            anneau(sl, point, 5, ParticleTypes.SNOWFLAKE);
        }
        if (a >= DUREE_FRAPPE) {
            frappeCd = enrage() ? 20 : 30;
            passer(CHASSE);
        }
    }

    /**
     * La ligne de pics : il plonge le poing dans le sol, et une rangée de pics de glace jaillit, du colosse jusqu'à sa
     * cible et au-delà (trois rangées en éventail en rage). Les pics restent deux secondes.
     */
    private void pics(ServerLevel sl, List<Player> joueurs) {
        int a = age();
        if (a < 14 && cible != null) {
            regarder(cible.position());
        }
        if (a == 2) {
            sl.playSound(null, getX(), getY() + 5, getZ(), SoundEvents.RAVAGER_ROAR, SoundSource.HOSTILE, 2.5F, 0.6F);
        }
        if (a == 14) {
            lignes.clear();
            origine = position();
            Vec3 av = avant();
            int n = enrage() ? 3 : 1;
            for (int k = 0; k < n; k++) {
                double ang = (k - (n - 1) / 2.0) * 0.45;
                lignes.add(new Vec3(av.x * Math.cos(ang) - av.z * Math.sin(ang), 0, av.x * Math.sin(ang) + av.z * Math.cos(ang)));
            }
            sl.playSound(null, getX(), getY(), getZ(), SoundEvents.GENERIC_EXPLODE.value(), SoundSource.HOSTILE, 1.5F, 1.2F);
        }
        if (a >= 14 && a < 14 + 32 && (a - 14) % 2 == 0) {
            int k = (a - 14) / 2;
            double dist = 2.5 + k * 1.5;
            for (Vec3 dir : lignes) {
                Vec3 p = origine.add(dir.scale(dist));
                BlockPos sol = BlockPos.containing(p.x, origine.y, p.z);
                int h = 1 + (k % 3 == 1 ? 2 : k % 2);
                for (int j = 0; j < h; j++) {
                    BlockState s = j == h - 1 ? Blocks.ICE.defaultBlockState() : Blocks.PACKED_ICE.defaultBlockState();
                    Ephemeres.poser(sl, sol.above(j), s, 40 - j * 4);
                }
                sl.sendParticles(GLACE, p.x, origine.y + 0.5, p.z, 12, 0.4, 0.6, 0.4, 0.15);
                sl.playSound(null, p.x, p.y, p.z, SoundEvents.GLASS_PLACE, SoundSource.HOSTILE, 1.2F, 0.6F + k * 0.03F);
                for (Player pl : joueurs) {
                    if (Math.abs(pl.getX() - p.x) < 1.3 && Math.abs(pl.getZ() - p.z) < 1.3 && pl.getY() < origine.y + 3) {
                        toucher(pl, enrage() ? 12 : 10, new Vec3(0, 0.9, 0));
                        pl.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 50, 2));
                    }
                }
            }
        }
        if (a >= DUREE_PICS) {
            picsCd = enrage() ? 90 : 130;
            passer(CHASSE);
        }
    }

    /** Le souffle de givre : il gonfle la poitrine, puis souffle un cône de neige qui gèle tout ce qu'il touche. */
    private void souffle(ServerLevel sl, List<Player> joueurs) {
        int a = age();
        if (a < 16 && cible != null) {
            regarder(cible.position());
        }
        if (a == 4) {
            sl.playSound(null, getX(), getY() + 6, getZ(), SoundEvents.POLAR_BEAR_WARNING, SoundSource.HOSTILE, 3F, 0.4F);
        }
        if (a >= 16 && a < 54) {
            // il balaie lentement de gauche à droite
            if (cible != null && a % 4 == 0) {
                Vec3 v = cible.position().subtract(position());
                float voulu = (float) (Math.atan2(v.z, v.x) * 180 / Math.PI) - 90F;
                float lacet = getYRot() + net.minecraft.util.Mth.clamp(net.minecraft.util.Mth.wrapDegrees(voulu - getYRot()), -6F, 6F);
                setYRot(lacet);
                yBodyRot = lacet;
                yHeadRot = lacet;
            }
            Vec3 av = avant();
            Vec3 bouche = position().add(0, 6.2, 0).add(av.scale(1.6));
            if (a % 2 == 0) {
                sl.playSound(null, bouche.x, bouche.y, bouche.z, SoundEvents.SNOW_BREAK, SoundSource.HOSTILE, 1.5F, 0.5F);
            }
            for (int k = 0; k < 6; k++) {
                Vec3 dir = av.add(random.nextGaussian() * 0.2, -0.45 + random.nextGaussian() * 0.12, random.nextGaussian() * 0.2).normalize();
                sl.sendParticles(ParticleTypes.SNOWFLAKE, bouche.x, bouche.y, bouche.z, 0, dir.x, dir.y, dir.z, 0.9);
                sl.sendParticles(ParticleTypes.CLOUD, bouche.x, bouche.y, bouche.z, 0, dir.x, dir.y, dir.z, 0.6);
            }
            if (a % 5 == 0) {
                for (Player p : joueurs) {
                    Vec3 vers = p.position().subtract(position());
                    double dist = vers.horizontalDistance();
                    if (dist < 13 && vers.multiply(1, 0, 1).normalize().dot(av) > 0.75) {
                        p.hurt(coup(), enrage() ? 3 : 2);
                        p.setTicksFrozen(Math.min(p.getTicksRequiredToFreeze() + 60, p.getTicksFrozen() + 35));
                        p.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 40, 2));
                    }
                }
            }
        }
        if (a >= DUREE_SOUFFLE) {
            souffleCd = enrage() ? 170 : 220;
            passer(CHASSE);
        }
    }

    /** La grêle (en rage) : bras levés, des cercles bleus sous chacun… puis la glace tombe du plafond. */
    private void grele(ServerLevel sl, List<Player> joueurs) {
        int a = age();
        if (a == 2) {
            grelons.clear();
            for (Player p : joueurs) {
                grelons.add(p.position());
                for (int k = 0; k < 2; k++) {
                    grelons.add(p.position().add(random.nextGaussian() * 4, 0, random.nextGaussian() * 4));
                }
            }
            sl.playSound(null, getX(), getY() + 6, getZ(), SoundEvents.RAVAGER_ROAR, SoundSource.HOSTILE, 3F, 0.5F);
        }
        if (a > 2 && a < 36 && a % 3 == 0) {
            for (Vec3 g : grelons) {
                for (int k = 0; k < 14; k++) {
                    double ang = k * Math.PI / 7 + a * 0.1;
                    sl.sendParticles(BLEU, g.x + Math.cos(ang) * 2, g.y + 0.15, g.z + Math.sin(ang) * 2, 1, 0, 0, 0, 0);
                }
                sl.sendParticles(ParticleTypes.SNOWFLAKE, g.x, g.y + 9, g.z, 3, 0.6, 0.2, 0.6, 0.01);
            }
        }
        if (a >= 26 && a < 36) {
            // ils tombent : une traînée de glace qui descend
            for (Vec3 g : grelons) {
                double h = 10 * (1 - (a - 26) / 10.0);
                sl.sendParticles(GLACE, g.x, g.y + h, g.z, 6, 0.3, 0.3, 0.3, 0);
            }
        }
        if (a == 36) {
            for (Vec3 g : grelons) {
                sl.playSound(null, g.x, g.y, g.z, SoundEvents.GLASS_BREAK, SoundSource.HOSTILE, 1.6F, 0.7F);
                sl.sendParticles(GLACE, g.x, g.y + 0.5, g.z, 40, 1, 0.4, 1, 0.3);
                sl.sendParticles(ParticleTypes.EXPLOSION, g.x, g.y + 0.5, g.z, 1, 0, 0, 0, 0);
                for (Player p : joueurs) {
                    if (p.position().distanceToSqr(g) < 2.3 * 2.3) {
                        toucher(p, 12, new Vec3(0, 0.4, 0));
                        p.setTicksFrozen(p.getTicksRequiredToFreeze() + 40);
                    }
                }
            }
        }
        if (a >= DUREE_GRELE) {
            greleCd = 220;
            passer(CHASSE);
        }
    }

    @Override
    protected void rage(ServerLevel sl, List<Player> joueurs, int a) {
        if (a == 18) {
            // la carapace de glace éclate : tout le monde est repoussé
            sl.playSound(null, getX(), getY() + 4, getZ(), SoundEvents.GLASS_BREAK, SoundSource.HOSTILE, 4F, 0.4F);
            sl.playSound(null, getX(), getY() + 4, getZ(), SoundEvents.RAVAGER_ROAR, SoundSource.HOSTILE, 4F, 0.4F);
            sl.sendParticles(GLACE, getX(), getY() + 4, getZ(), 150, 2.5, 3, 2.5, 0.5);
            anneau(sl, position(), 14, ParticleTypes.SNOWFLAKE);
            for (Player p : joueurs) {
                if (p.distanceTo(this) < 14) {
                    toucher(p, 6, dehors(p, position(), 2, 0.6));
                }
            }
            Boss.annoncer(sl, typeDonjon(), "ALERTE", new String[] {"Un blizzard se lève autour de Hrimgar.", "Ne restez pas collés à lui !"}, "", 0);
        }
    }

    @Override
    protected void invocation(ServerLevel sl, int a) {
        if (a == 10) {
            sl.playSound(null, getX(), getY() + 6, getZ(), SoundEvents.RAVAGER_ROAR, SoundSource.HOSTILE, 4F, 0.6F);
            sl.playSound(null, getX(), getY() + 6, getZ(), SoundEvents.EVOKER_PREPARE_SUMMON, SoundSource.HOSTILE, 3F, 0.6F);
        }
    }

    /** Des vagabonds (des squelettes du froid) sortent de la neige autour de l'arène. */
    @Override
    protected void invoquer(ServerLevel sl, int n) {
        Vec3 c = arene();
        int total = Math.min(Boss.placeArene(sl, c), 2 + n);
        for (int i = 0; i < total; i++) {
            double ang = random.nextDouble() * Math.PI * 2, d = 12 + random.nextDouble() * 6;
            Vec3 p = c.add(Math.cos(ang) * d, 0, Math.sin(ang) * d);
            Boss.poserMob(sl, EntityType.STRAY, p.x, p.y, p.z);
            sl.sendParticles(ParticleTypes.SNOWFLAKE, p.x, p.y + 1, p.z, 30, 0.4, 0.8, 0.4, 0.05);
        }
    }
}
