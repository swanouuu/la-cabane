package fr.cabane.donjon;

import java.util.List;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.BossEvent;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

/**
 * Astérix, le petit Gaulois du donjon de l'événement du parc : petit (pour un boss), très rapide, casque ailé, grosse
 * moustache, la gourde de potion magique à la ceinture. Il dort assis contre un tonneau ; réveillé, il se lève d'un bond.
 *
 * <p>Ses attaques : le saut (il bondit sur sa cible et l'onde de choc repousse tout le monde), l'uppercut (celui qui le
 * prend s'envole), et la potion magique : il boit une gorgée, s'entoure d'une lueur dorée, puis devient une tornade
 * qui fonce dans tous les sens en envoyant valser ceux qu'elle touche. En rage (à la moitié de sa vie, ou quand Obélix
 * tombe), il vide la gourde : il reste doré et va encore plus vite.
 */
public class Asterix extends BossDonjon {
    public static final int SAUT = 10, UPPERCUT = 11, POTION = 12, TOURBILLON = 13;
    public static final int DUREE_REVEIL = 40, DUREE_SAUT = 26, DUREE_UPPERCUT = 16, IMPACT_UPPERCUT = 6, DUREE_POTION = 34, DUREE_TOURBILLON = 110,
            DUREE_RAGE = 46;
    private static final DustParticleOptions OR = new DustParticleOptions(new Vector3f(1F, 0.85F, 0.25F), 1.4F);

    private int sautCd = 60, uppercutCd = 10, potionCd = 260;
    private Vec3 depart = Vec3.ZERO, arrivee = Vec3.ZERO;

    public Asterix(EntityType<? extends Asterix> type, Level level) {
        super(type, level);
    }

    public static AttributeSupplier.Builder attributs() {
        return Monster.createMonsterAttributes().add(Attributes.MAX_HEALTH, VIE).add(Attributes.ATTACK_DAMAGE, 10).add(Attributes.ARMOR, 10)
                .add(Attributes.ARMOR_TOUGHNESS, 4).add(Attributes.MOVEMENT_SPEED, 0.36).add(Attributes.FOLLOW_RANGE, 64)
                .add(Attributes.KNOCKBACK_RESISTANCE, 1).add(Attributes.STEP_HEIGHT, 1.1).add(Attributes.SAFE_FALL_DISTANCE, 64);
    }

    @Override
    public TypeDonjon typeDonjon() {
        return TypeDonjon.GAULOIS;
    }

    @Override
    public String nomBoss() {
        return "Astérix";
    }

    @Override
    public float echellePv() {
        return 1.4F;
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
    protected boolean dernierDebout() {
        return autre() == null;
    }

    /** Obélix, s'il est encore debout. */
    BossDonjon autre() {
        if (!(level() instanceof ServerLevel sl)) {
            return null;
        }
        for (Mob m : Boss.boss(sl, typeDonjon())) {
            if (m != this && m.isAlive() && m instanceof BossDonjon b) {
                return b;
            }
        }
        return null;
    }

    @Override
    public void partenaireTombe(ServerLevel sl) {
        if (!enrage() && etat() >= CHASSE) {
            passer(RAGE);
        }
    }

    @Override
    public void tick() {
        super.tick();
        if (level().isClientSide && isAlive() && (enrage() || etat() == TOURBILLON || etat() == POTION && tickCount - debutClient > 16)) {
            for (int k = 0; k < 2; k++) {
                level().addParticle(OR, getX() + random.nextGaussian() * 0.8, getY() + random.nextDouble() * 4, getZ() + random.nextGaussian() * 0.8, 0, 0.05, 0);
            }
        }
    }

    @Override
    protected void reveil(ServerLevel sl, int a) {
        if (a == 8) {
            sl.playSound(null, getX(), getY() + 2, getZ(), SoundEvents.VILLAGER_YES, SoundSource.HOSTILE, 3F, 0.7F);
        }
        if (a == 20) {
            // il se lève d'un bond
            sl.playSound(null, getX(), getY(), getZ(), SoundEvents.PLAYER_ATTACK_KNOCKBACK, SoundSource.HOSTILE, 2F, 0.6F);
            sl.sendParticles(ParticleTypes.POOF, getX(), getY() + 0.3, getZ(), 30, 1, 0.2, 1, 0.05);
        }
    }

    @Override
    protected void rage(ServerLevel sl, List<Player> joueurs, int a) {
        if (a == 6 || a == 14 || a == 22) {
            sl.playSound(null, getX(), getY() + 2, getZ(), SoundEvents.GENERIC_DRINK, SoundSource.HOSTILE, 3F, 0.8F);
        }
        if (a == 30) {
            sl.playSound(null, getX(), getY() + 2, getZ(), SoundEvents.PLAYER_LEVELUP, SoundSource.HOSTILE, 3F, 0.6F);
            sl.sendParticles(OR, getX(), getY() + 2, getZ(), 120, 1.5, 2, 1.5, 0.1);
            anneau(sl, position(), 9, ParticleTypes.CLOUD);
            for (Player p : joueurs) {
                if (p.distanceTo(this) < 8) {
                    toucher(p, 6, dehors(p, position(), 1.6, 0.6));
                }
            }
        }
    }

    @Override
    protected void chasse(ServerLevel sl, List<Player> joueurs) {
        sautCd--;
        uppercutCd--;
        potionCd--;
        double d = distanceTo(cible);
        getNavigation().moveTo(cible, enrage() ? 1.7 : 1.3);
        getLookControl().setLookAt(cible, 30, 30);
        if (getDeltaMovement().horizontalDistanceSqr() > 0.01 && tickCount % 6 == 0) {
            sl.sendParticles(ParticleTypes.POOF, getX(), getY() + 0.1, getZ(), 2, 0.3, 0, 0.3, 0.01);
        }
        if (d < 3.6 && uppercutCd <= 0) {
            regarder(cible.position());
            passer(UPPERCUT);
        } else if (potionCd <= 0 && d < 16) {
            passer(POTION);
        } else if (sautCd <= 0 && d > 6) {
            regarder(cible.position());
            depart = position();
            arrivee = cible.position().subtract(avant().scale(1.6));
            passer(SAUT);
        }
    }

    @Override
    protected void action(ServerLevel sl, List<Player> joueurs, int etat) {
        switch (etat) {
            case SAUT -> saut(sl, joueurs);
            case UPPERCUT -> uppercut(sl, joueurs);
            case POTION -> potion(sl);
            case TOURBILLON -> tourbillon(sl, joueurs);
            default -> passer(CHASSE);
        }
    }

    /** Accroupi, puis un bond en cloche sur sa cible ; à l'atterrissage, une onde de choc. */
    private void saut(ServerLevel sl, List<Player> joueurs) {
        int a = age();
        if (a <= 1 && depart.lengthSqr() < 1) {
            // sans cible (vitrine) : un bond droit devant
            depart = position();
            arrivee = position().add(avant().scale(8));
        }
        setDeltaMovement(Vec3.ZERO);
        if (a == 6) {
            sl.playSound(null, getX(), getY(), getZ(), SoundEvents.GOAT_LONG_JUMP, SoundSource.HOSTILE, 3F, 0.8F);
        }
        if (a > 6 && a <= 20) {
            double k = (a - 6) / 14.0;
            Vec3 p = depart.lerp(arrivee, k).add(0, Math.sin(Math.PI * k) * 7, 0);
            setNoGravity(true);
            teleportTo(p.x, p.y, p.z);
        }
        if (a == 20) {
            setNoGravity(false);
            sl.playSound(null, getX(), getY(), getZ(), SoundEvents.GENERIC_EXPLODE.value(), SoundSource.HOSTILE, 1.5F, 1.4F);
            sl.sendParticles(ParticleTypes.POOF, getX(), getY() + 0.2, getZ(), 60, 2, 0.2, 2, 0.1);
            anneau(sl, position(), 6, ParticleTypes.CLOUD);
            for (Player p : joueurs) {
                if (p.distanceTo(this) < 5) {
                    toucher(p, enrage() ? 12 : 9, dehors(p, position(), 1.3, 0.55));
                }
            }
        }
        if (a >= DUREE_SAUT) {
            setNoGravity(false);
            sautCd = enrage() ? 70 : 110;
            passer(CHASSE);
        }
    }

    /** Le poing qui monte : celui qui le prend part dans les airs. */
    private void uppercut(ServerLevel sl, List<Player> joueurs) {
        int a = age();
        setDeltaMovement(0, getDeltaMovement().y, 0);
        if (a < IMPACT_UPPERCUT && cible != null) {
            regarder(cible.position());
        }
        if (a == IMPACT_UPPERCUT) {
            Vec3 av = avant();
            boolean touche = false;
            for (Player p : joueurs) {
                Vec3 vers = p.position().subtract(position());
                if (vers.horizontalDistance() < 4.2 && vers.normalize().dot(av) > 0.2) {
                    toucher(p, enrage() ? 14 : 11, new Vec3(av.x * 0.6, 1.5, av.z * 0.6));
                    touche = true;
                }
            }
            sl.playSound(null, getX(), getY() + 2, getZ(), touche ? SoundEvents.PLAYER_ATTACK_CRIT : SoundEvents.PLAYER_ATTACK_SWEEP, SoundSource.HOSTILE, 2.5F, 0.7F);
            sl.sendParticles(ParticleTypes.CRIT, getX() + av.x * 2, getY() + 2.5, getZ() + av.z * 2, 20, 0.4, 0.8, 0.4, 0.3);
        }
        if (a >= DUREE_UPPERCUT) {
            uppercutCd = enrage() ? 18 : 30;
            passer(CHASSE);
        }
    }

    /** Une gorgée de potion magique : il s'arrête, boit, et se met à briller. */
    private void potion(ServerLevel sl) {
        int a = age();
        setDeltaMovement(0, getDeltaMovement().y, 0);
        if (a == 6 || a == 12) {
            sl.playSound(null, getX(), getY() + 2, getZ(), SoundEvents.GENERIC_DRINK, SoundSource.HOSTILE, 3F, 0.9F);
        }
        if (a == 20) {
            sl.playSound(null, getX(), getY() + 2, getZ(), SoundEvents.BEACON_POWER_SELECT, SoundSource.HOSTILE, 3F, 1.3F);
            sl.sendParticles(OR, getX(), getY() + 2, getZ(), 80, 1, 1.5, 1, 0.1);
        }
        if (a >= DUREE_POTION) {
            passer(TOURBILLON);
        }
    }

    /** La tornade dorée : il tourne sur lui-même en fonçant d'un joueur à l'autre, ceux qu'il touche s'envolent. */
    private void tourbillon(ServerLevel sl, List<Player> joueurs) {
        int a = age();
        Player vers = cible;
        if (vers != null) {
            Vec3 d = vers.position().subtract(position()).multiply(1, 0, 1);
            double l = d.length();
            if (l > 0.5) {
                double v = enrage() ? 0.62 : 0.5;
                setDeltaMovement(d.x / l * v, getDeltaMovement().y, d.z / l * v);
            }
        }
        setYRot(getYRot() + 45);
        yBodyRot = getYRot();
        if (a % 4 == 0) {
            sl.playSound(null, getX(), getY() + 1, getZ(), SoundEvents.PLAYER_ATTACK_SWEEP, SoundSource.HOSTILE, 1.6F, 0.6F + (a % 12) / 20F);
            sl.sendParticles(ParticleTypes.CLOUD, getX(), getY() + 0.3, getZ(), 6, 1.2, 0.2, 1.2, 0.05);
            sl.sendParticles(OR, getX(), getY() + 1.5, getZ(), 10, 1.4, 1.2, 1.4, 0.05);
        }
        if (a % 8 == 0) {
            for (Player p : joueurs) {
                if (p.distanceTo(this) < 3.6) {
                    toucher(p, enrage() ? 8 : 6, dehors(p, position(), 1.8, 0.9));
                }
            }
        }
        if (a % 30 == 0 && !joueurs.isEmpty()) {
            cible = joueurs.get(random.nextInt(joueurs.size()));
        }
        if (a >= DUREE_TOURBILLON) {
            setDeltaMovement(Vec3.ZERO);
            potionCd = enrage() ? 300 : 420;
            passer(CHASSE);
        }
    }

    @Override
    protected void invoquer(ServerLevel sl, int n) {
        Vec3 c = arene();
        int total = Math.min(Boss.placeArene(sl, c), 3 + n);
        for (int i = 0; i < total; i++) {
            double ang = random.nextDouble() * Math.PI * 2, d = 13 + random.nextDouble() * 5;
            Boss.poserSbire(sl, i == 0 ? Donjon.BRUTE.get() : Donjon.GOBELIN.get(), c.x + Math.cos(ang) * d, c.y, c.z + Math.sin(ang) * d, c);
        }
    }

    @Override
    protected void invocation(ServerLevel sl, int a) {
        if (a == 4) {
            // il siffle les Romains… qui arrivent pour se faire taper
            sl.playSound(null, getX(), getY() + 2, getZ(), SoundEvents.RAID_HORN.value(), SoundSource.HOSTILE, 2F, 1.3F);
        }
    }
}
