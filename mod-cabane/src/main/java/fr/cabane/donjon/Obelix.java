package fr.cabane.donjon;

import java.util.List;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
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
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

/**
 * Obélix, le grand Gaulois du donjon de l'événement du parc : énorme et lent, pantalon rayé bleu et blanc, tresses
 * rousses, un menhir sur le dos. Il dort contre son tas de menhirs ; réveillé, il s'étire et pose son menhir.
 *
 * <p>Ses attaques : le lancer de menhir (il le soulève au-dessus de sa tête, le lance en cloche sur sa cible : gros
 * dégâts autour du point d'impact, un cercle de poussière prévient) ; la charge du ventre (il fonce tout droit et
 * renverse tout le monde) ; il attrape quelqu'un, le fait tourner et le lance au loin ; et parfois il s'arrête pour
 * manger un sanglier (il se soigne un peu, mais il prend plus de coups pendant ce temps). En rage, il tape du pied et
 * le sol tremble.
 */
public class Obelix extends BossDonjon {
    public static final int MENHIR = 10, CHARGE = 11, ATTRAPE = 12, SANGLIER = 13;
    public static final int DUREE_REVEIL = 50, DUREE_MENHIR = 50, LANCER = 30, IMPACT = 46, DUREE_CHARGE = 44, DUREE_ATTRAPE = 64, DUREE_SANGLIER = 70,
            DUREE_RAGE = 50;
    private static final BlockParticleOption PIERRE = new BlockParticleOption(ParticleTypes.BLOCK, Blocks.STONE.defaultBlockState());
    private static final BlockParticleOption TERRE = new BlockParticleOption(ParticleTypes.BLOCK, Blocks.COARSE_DIRT.defaultBlockState());

    /** Où le menhir va tomber (pour le dessiner en vol chez les joueurs). */
    private static final EntityDataAccessor<Vector3f> CIBLE = SynchedEntityData.defineId(Obelix.class, EntityDataSerializers.VECTOR3);

    private int menhirCd = 70, chargeCd = 160, attrapeCd = 220, sanglierCd = 500;
    private Vec3 direction = Vec3.ZERO;
    private Player tenu;

    public Obelix(EntityType<? extends Obelix> type, Level level) {
        super(type, level);
    }

    public static AttributeSupplier.Builder attributs() {
        return Monster.createMonsterAttributes().add(Attributes.MAX_HEALTH, VIE).add(Attributes.ATTACK_DAMAGE, 14).add(Attributes.ARMOR, 16)
                .add(Attributes.ARMOR_TOUGHNESS, 6).add(Attributes.MOVEMENT_SPEED, 0.2).add(Attributes.FOLLOW_RANGE, 64)
                .add(Attributes.KNOCKBACK_RESISTANCE, 1).add(Attributes.STEP_HEIGHT, 2.1).add(Attributes.SAFE_FALL_DISTANCE, 64);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(CIBLE, new Vector3f());
    }

    /** Le point où le menhir lancé va tomber. */
    public Vec3 cibleMenhir() {
        Vector3f v = entityData.get(CIBLE);
        return new Vec3(v.x, v.y, v.z);
    }

    @Override
    public TypeDonjon typeDonjon() {
        return TypeDonjon.GAULOIS;
    }

    @Override
    public String nomBoss() {
        return "Obélix";
    }

    @Override
    public float echellePv() {
        return 2.1F;
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

    @Override
    protected boolean dernierDebout() {
        if (!(level() instanceof ServerLevel sl)) {
            return true;
        }
        for (Mob m : Boss.boss(sl, typeDonjon())) {
            if (m != this && m.isAlive()) {
                return false;
            }
        }
        return true;
    }

    @Override
    public void partenaireTombe(ServerLevel sl) {
        if (!enrage() && etat() >= CHASSE) {
            lacher();
            passer(RAGE);
        }
    }

    @Override
    protected float vulnerabilite(int etat) {
        return etat == SANGLIER ? 1.6F : 1;
    }

    @Override
    protected void reveil(ServerLevel sl, int a) {
        if (a == 10) {
            sl.playSound(null, getX(), getY() + 5, getZ(), SoundEvents.PLAYER_BURP, SoundSource.HOSTILE, 3F, 0.5F);
        }
        if (a == 30) {
            // il se relève et tape du pied
            sl.playSound(null, getX(), getY(), getZ(), SoundEvents.RAVAGER_STEP, SoundSource.HOSTILE, 3F, 0.5F);
            sl.sendParticles(TERRE, getX(), getY() + 0.2, getZ(), 60, 2, 0.2, 2, 0.1);
        }
    }

    @Override
    protected void rage(ServerLevel sl, List<Player> joueurs, int a) {
        if (a == 4) {
            sl.playSound(null, getX(), getY() + 6, getZ(), SoundEvents.RAVAGER_ROAR, SoundSource.HOSTILE, 4F, 0.7F);
        }
        if (a == 20 || a == 34) {
            // il tape du pied : le sol tremble
            sl.playSound(null, getX(), getY(), getZ(), SoundEvents.GENERIC_EXPLODE.value(), SoundSource.HOSTILE, 2F, 0.5F);
            sl.sendParticles(TERRE, getX(), getY() + 0.2, getZ(), 120, 3, 0.2, 3, 0.2);
            anneau(sl, position(), 12, ParticleTypes.CAMPFIRE_COSY_SMOKE);
            for (Player p : joueurs) {
                if (p.distanceTo(this) < 11 && p.onGround()) {
                    toucher(p, 7, dehors(p, position(), 1.0, 0.7));
                }
            }
        }
    }

    @Override
    protected void chasse(ServerLevel sl, List<Player> joueurs) {
        menhirCd--;
        chargeCd--;
        attrapeCd--;
        sanglierCd--;
        double d = distanceTo(cible);
        getNavigation().moveTo(cible, enrage() ? 1.35 : 1.0);
        getLookControl().setLookAt(cible, 30, 30);
        if (getDeltaMovement().horizontalDistanceSqr() > 0.003 && tickCount % 14 == 0) {
            sl.playSound(null, getX(), getY(), getZ(), SoundEvents.RAVAGER_STEP, SoundSource.HOSTILE, 2F, 0.6F);
        }
        if (d < 4.8 && attrapeCd <= 0) {
            regarder(cible.position());
            tenu = cible;
            passer(ATTRAPE);
        } else if (sanglierCd <= 0 && getHealth() < VIE * 0.85F) {
            passer(SANGLIER);
        } else if (chargeCd <= 0 && d > 6 && d < 22) {
            regarder(cible.position());
            direction = cible.position().subtract(position()).multiply(1, 0, 1).normalize();
            passer(CHARGE);
        } else if (menhirCd <= 0 && d > 5) {
            regarder(cible.position());
            passer(MENHIR);
        }
    }

    @Override
    protected void action(ServerLevel sl, List<Player> joueurs, int etat) {
        switch (etat) {
            case MENHIR -> menhir(sl, joueurs);
            case CHARGE -> charge(sl, joueurs);
            case ATTRAPE -> attrape(sl, joueurs);
            case SANGLIER -> sanglier(sl);
            default -> passer(CHASSE);
        }
    }

    /** Il soulève le menhir au-dessus de sa tête, vise, le lance en cloche ; un cercle de poussière marque l'impact. */
    private void menhir(ServerLevel sl, List<Player> joueurs) {
        int a = age();
        setDeltaMovement(0, getDeltaMovement().y, 0);
        if (a <= 1 && cible == null) {
            Vec3 c = position().add(avant().scale(12));
            entityData.set(CIBLE, new Vector3f((float) c.x, (float) getY(), (float) c.z));
        }
        if (a <= LANCER && cible != null) {
            regarder(cible.position());
            // il vise un peu devant celui qui court
            Vec3 c = cible.position().add(cible.getDeltaMovement().multiply(8, 0, 8));
            entityData.set(CIBLE, new Vector3f((float) c.x, (float) getY(), (float) c.z));
        }
        if (a == 8) {
            sl.playSound(null, getX(), getY() + 6, getZ(), SoundEvents.STONE_BREAK, SoundSource.HOSTILE, 3F, 0.5F);
        }
        Vec3 c = cibleMenhir();
        if (a > LANCER - 12 && a < IMPACT && a % 3 == 0) {
            anneau(sl, c, 4.5, ParticleTypes.SMOKE);
        }
        if (a == LANCER) {
            sl.playSound(null, getX(), getY() + 6, getZ(), SoundEvents.WITCH_THROW, SoundSource.HOSTILE, 3F, 0.4F);
        }
        if (a == IMPACT) {
            sl.playSound(null, c.x, c.y, c.z, SoundEvents.GENERIC_EXPLODE.value(), SoundSource.HOSTILE, 2.5F, 0.6F);
            sl.playSound(null, c.x, c.y, c.z, SoundEvents.ANVIL_LAND, SoundSource.HOSTILE, 2F, 0.4F);
            sl.sendParticles(PIERRE, c.x, c.y + 0.5, c.z, 120, 1.5, 0.5, 1.5, 0.3);
            sl.sendParticles(ParticleTypes.CAMPFIRE_COSY_SMOKE, c.x, c.y + 0.5, c.z, 30, 2, 0.5, 2, 0.02);
            for (Player p : joueurs) {
                double dd = p.position().distanceTo(c);
                if (dd < 4.5) {
                    toucher(p, enrage() ? 18 : 14, dehors(p, c, 1.2, 0.6));
                }
            }
        }
        if (a >= DUREE_MENHIR) {
            menhirCd = enrage() ? 80 : 120;
            passer(CHASSE);
        }
    }

    /** La charge du ventre : il prend son élan et fonce tout droit, ceux qu'il croise sont renversés. */
    private void charge(ServerLevel sl, List<Player> joueurs) {
        int a = age();
        if (a < 10) {
            setDeltaMovement(0, getDeltaMovement().y, 0);
            if (a == 2) {
                sl.playSound(null, getX(), getY() + 5, getZ(), SoundEvents.RAVAGER_ROAR, SoundSource.HOSTILE, 2.5F, 1.1F);
            }
            return;
        }
        double v = enrage() ? 0.95 : 0.8;
        setDeltaMovement(direction.x * v, getDeltaMovement().y, direction.z * v);
        if (a % 3 == 0) {
            sl.sendParticles(TERRE, getX(), getY() + 0.2, getZ(), 8, 1.2, 0.1, 1.2, 0.1);
        }
        if (a % 7 == 0) {
            sl.playSound(null, getX(), getY(), getZ(), SoundEvents.RAVAGER_STEP, SoundSource.HOSTILE, 2.5F, 0.8F);
        }
        for (Player p : joueurs) {
            if (p.distanceTo(this) < 3.2 && p.invulnerableTime == 0) {
                toucher(p, enrage() ? 14 : 11, new Vec3(direction.x * 2.0, 0.8, direction.z * 2.0));
            }
        }
        boolean bord = position().add(direction.scale(3)).distanceTo(arene()) > rayon() - 2;
        if (a >= DUREE_CHARGE || horizontalCollision || bord) {
            setDeltaMovement(0, getDeltaMovement().y, 0);
            if (horizontalCollision || bord) {
                sl.playSound(null, getX(), getY() + 3, getZ(), SoundEvents.ZOMBIE_ATTACK_WOODEN_DOOR, SoundSource.HOSTILE, 2.5F, 0.5F);
            }
            chargeCd = enrage() ? 130 : 190;
            passer(CHASSE);
        }
    }

    /** Il attrape quelqu'un, le fait tourner au bout de son bras, et le lance au loin. */
    private void attrape(ServerLevel sl, List<Player> joueurs) {
        int a = age();
        setDeltaMovement(0, getDeltaMovement().y, 0);
        if (tenu == null || !valide(tenu) || a == 6 && tenu.distanceTo(this) > 5.5) {
            lacher();
            attrapeCd = 80;
            passer(CHASSE);
            return;
        }
        if (a < 6) {
            regarder(tenu.position());
            return;
        }
        if (a == 6) {
            sl.playSound(null, getX(), getY() + 4, getZ(), SoundEvents.PLAYER_ATTACK_NODAMAGE, SoundSource.HOSTILE, 2.5F, 0.5F);
            if (tenu instanceof ServerPlayer j) {
                j.displayClientMessage(net.minecraft.network.chat.Component.literal("Obélix t'attrape !").withStyle(net.minecraft.ChatFormatting.RED), true);
            }
        }
        // il tourne de plus en plus vite, le joueur au bout du bras droit
        float tour = 8 + (a - 6) * 0.6F;
        setYRot(getYRot() + tour);
        yBodyRot = getYRot();
        yHeadRot = getYRot();
        double r = Math.toRadians(getYRot());
        Vec3 main = position().add(-Math.sin(r) * 2.6 - Math.cos(r) * 2.4, 3.0, Math.cos(r) * 2.6 - Math.sin(r) * 2.4);
        tenu.teleportTo(main.x, main.y, main.z);
        tenu.setDeltaMovement(Vec3.ZERO);
        tenu.fallDistance = 0;
        if (a % 10 == 0) {
            sl.playSound(null, getX(), getY() + 3, getZ(), SoundEvents.PLAYER_ATTACK_SWEEP, SoundSource.HOSTILE, 1.5F, 0.5F);
        }
        if (a >= DUREE_ATTRAPE - 4) {
            Player p = tenu;
            lacher();
            Vec3 dehors = p.position().subtract(position()).multiply(1, 0, 1).normalize();
            toucher(p, enrage() ? 12 : 9, new Vec3(dehors.x * 2.6, 1.0, dehors.z * 2.6));
            sl.playSound(null, getX(), getY() + 3, getZ(), SoundEvents.PLAYER_ATTACK_STRONG, SoundSource.HOSTILE, 2.5F, 0.5F);
            attrapeCd = enrage() ? 160 : 240;
            passer(CHASSE);
        }
    }

    private void lacher() {
        tenu = null;
    }

    /** Une petite faim : il sort un sanglier rôti et le dévore (il se soigne un peu, mais il ne se protège plus). */
    private void sanglier(ServerLevel sl) {
        int a = age();
        setDeltaMovement(0, getDeltaMovement().y, 0);
        if (a % 8 == 0 && a > 6 && a < 60) {
            sl.playSound(null, getX(), getY() + 5, getZ(), SoundEvents.GENERIC_EAT, SoundSource.HOSTILE, 3F, 0.6F);
            sl.sendParticles(new net.minecraft.core.particles.ItemParticleOption(ParticleTypes.ITEM, new net.minecraft.world.item.ItemStack(
                    net.minecraft.world.item.Items.COOKED_PORKCHOP)), getX(), getY() + 5.4, getZ(), 6, 0.4, 0.2, 0.4, 0.08);
            heal(VIE * 0.006F);
        }
        if (a == 62) {
            sl.playSound(null, getX(), getY() + 5, getZ(), SoundEvents.PLAYER_BURP, SoundSource.HOSTILE, 3F, 0.5F);
        }
        if (a >= DUREE_SANGLIER) {
            sanglierCd = 700;
            passer(CHASSE);
        }
    }

    @Override
    protected void rendormir() {
        lacher();
        super.rendormir();
    }

    @Override
    protected void invoquer(ServerLevel sl, int n) {
        Vec3 c = arene();
        int total = Math.min(Boss.placeArene(sl, c), 2 + n);
        for (int i = 0; i < total; i++) {
            double ang = random.nextDouble() * Math.PI * 2, d = 13 + random.nextDouble() * 5;
            Boss.poserSbire(sl, i < 2 ? Donjon.BRUTE.get() : Donjon.GOBELIN.get(), c.x + Math.cos(ang) * d, c.y, c.z + Math.sin(ang) * d, c);
        }
    }

    @Override
    protected void invocation(ServerLevel sl, int a) {
        if (a == 4) {
            sl.playSound(null, getX(), getY() + 5, getZ(), SoundEvents.RAID_HORN.value(), SoundSource.HOSTILE, 2F, 0.9F);
        }
    }
}
