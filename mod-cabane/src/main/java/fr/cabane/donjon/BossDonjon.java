package fr.cabane.donjon;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.core.particles.ParticleOptions;
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
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * Ce que les boss des donjons tracés ont en commun (Ugrak, plus ancien, a son propre code) : ils dorment à leur place
 * au fond de l'arène et se réveillent quand quelqu'un s'approche ; leur barre de vie (avec les PV) en haut de l'écran ;
 * ils s'acharnent sur celui qui leur fait le plus mal ; à la moitié de leur vie, une rage qui les change ; des sbires à
 * 75, 50 et 25 % ; s'ils restent seuls 20 s, ils se soignent et se rendorment. Mort : le donjon est terminé.
 *
 * <p>Leur vie : 1000 PV réels, divisés par {@link #echellePv()} à chaque coup (la vie de Minecraft s'arrête à 1 024).
 */
public abstract class BossDonjon extends Monster {
    public static final float VIE = 1000;
    public static final int SOMMEIL = 0, REVEIL = 1, CHASSE = 2, RAGE = 3, INVOCATION = 4;
    public static final int DUREE_INVOCATION = 46, DUREE_MORT = 60;
    private static final float[] SEUILS = {0.75F, 0.5F, 0.25F};

    private static final EntityDataAccessor<Integer> ETAT = SynchedEntityData.defineId(BossDonjon.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Boolean> ENRAGE = SynchedEntityData.defineId(BossDonjon.class, EntityDataSerializers.BOOLEAN);

    /** Côté client : le tick où l'état a changé (le dessin en tire le geste). */
    public int debutClient;
    protected int debut;
    protected Player cible;
    private final Map<UUID, Float> menace = new HashMap<>();
    private int seul;
    protected int invocations;
    private int renfortCd = 600;
    private final ServerBossEvent barre;

    protected BossDonjon(EntityType<? extends BossDonjon> type, Level level) {
        super(type, level);
        setPersistenceRequired();
        xpReward = 500;
        barre = new ServerBossEvent(Component.literal(typeDonjon().boss), couleurBarre(), BossEvent.BossBarOverlay.NOTCHED_10);
        barre.setDarkenScreen(true);
        barre.setPlayBossMusic(true);
    }

    // ---------------------------------------------------------------- ce que chaque boss précise

    public abstract TypeDonjon typeDonjon();

    /** Les PV affichés pour un PV réel. */
    public abstract float echellePv();

    protected abstract BossEvent.BossBarColor couleurBarre();

    protected abstract int dureeReveil();

    protected abstract int dureeRage();

    /** En chasse : marcher vers la cible, et choisir une attaque (passer dans un de ses états, à partir de 10). */
    protected abstract void chasse(ServerLevel sl, List<Player> joueurs);

    /** Un de ses états à lui (à partir de 10). */
    protected abstract void action(ServerLevel sl, List<Player> joueurs, int etat);

    /** Les effets du réveil (à chaque tick, {@code a} : depuis combien de ticks). */
    protected void reveil(ServerLevel sl, int a) {
    }

    /** Les effets de la rage (à chaque tick). */
    protected void rage(ServerLevel sl, List<Player> joueurs, int a) {
    }

    /** Ses sbires sortent (à la 26e tick de l'invocation). */
    protected abstract void invoquer(ServerLevel sl, int joueurs);

    /** Les effets de l'invocation, avant que les sbires sortent. */
    protected void invocation(ServerLevel sl, int a) {
    }

    /** Il dort : où, et comment (côté serveur, à chaque tick). */
    protected void dormir() {
        setDeltaMovement(Vec3.ZERO);
        regarder(arene());
    }

    /** Les dégâts qu'il prend dans cet état (1 : normal). */
    protected float vulnerabilite(int etat) {
        return 1;
    }

    // ---------------------------------------------------------------- les bases

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

    protected void setEnrage(boolean e) {
        entityData.set(ENRAGE, e);
    }

    protected int age() {
        return tickCount - debut;
    }

    protected void passer(int etat) {
        entityData.set(ETAT, etat);
        debut = tickCount;
        getNavigation().stop();
    }

    public Vec3 arene() {
        return typeDonjon().arene();
    }

    public Vec3 repos() {
        return PlanDonjon.de(typeDonjon()).repos();
    }

    protected double rayon() {
        return typeDonjon().rayonArene();
    }

    @Override
    protected void registerGoals() {
    }

    @Override
    public boolean removeWhenFarAway(double distance) {
        return false;
    }

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

    protected DamageSource coup() {
        return Ogre.coup(this);
    }

    /** Touche ce joueur : des dégâts, et une poussée. */
    protected void toucher(Player p, float degats, Vec3 poussee) {
        p.hurt(coup(), degats);
        if (poussee != null) {
            p.push(poussee.x, poussee.y, poussee.z);
            p.hurtMarked = true;
        }
    }

    /** Une poussée qui éloigne de ce point, de cette force, et vers le haut. */
    protected static Vec3 dehors(Player p, Vec3 depuis, double force, double haut) {
        Vec3 d = p.position().subtract(depuis).multiply(1, 0, 1);
        d = d.lengthSqr() < 1e-4 ? new Vec3(1, 0, 0) : d.normalize();
        return new Vec3(d.x * force, haut, d.z * force);
    }

    protected void regarder(Vec3 point) {
        double dx = point.x - getX(), dz = point.z - getZ();
        float lacet = (float) (Mth.atan2(dz, dx) * Mth.RAD_TO_DEG) - 90F;
        setYRot(lacet);
        yBodyRot = lacet;
        yHeadRot = lacet;
    }

    protected Vec3 avant() {
        return Vec3.directionFromRotation(0, getYRot());
    }

    /** Un anneau de particules au sol, qui s'élargit jusqu'à ce rayon. */
    protected void anneau(ServerLevel sl, Vec3 c, double rayon, ParticleOptions p) {
        for (int k = 0; k < 48; k++) {
            double a = k * Math.PI * 2 / 48;
            for (double r = 2; r <= rayon; r += 2.2) {
                sl.sendParticles(p, c.x + Math.cos(a) * r, c.y + 0.2, c.z + Math.sin(a) * r, 1, 0.1, 0.05, 0.1, 0.01);
            }
        }
    }

    protected void barreJoueurs(List<Player> joueurs, String texte, ChatFormatting couleur) {
        for (Player p : joueurs) {
            if (p instanceof ServerPlayer sp) {
                sp.displayClientMessage(Component.literal(texte).withStyle(couleur), true);
            }
        }
    }

    // ---------------------------------------------------------------- les tirs (toiles, orbes, acide)

    /** Un tir simulé par le serveur : une position, une vitesse, une durée ; il peut suivre un joueur. */
    protected static final class Tir {
        Vec3 pos;
        Vec3 vel;
        int vie;
        final Player suivi;
        final double guidage;
        final int genre;

        Tir(Vec3 pos, Vec3 vel, int vie, Player suivi, double guidage, int genre) {
            this.pos = pos;
            this.vel = vel;
            this.vie = vie;
            this.suivi = suivi;
            this.guidage = guidage;
            this.genre = genre;
        }
    }

    private final List<Tir> tirs = new ArrayList<>();

    protected void tirer(Vec3 depuis, Vec3 vel, int vie, Player suivi, double guidage, int genre) {
        tirs.add(new Tir(depuis, vel, vie, suivi, guidage, genre));
    }

    /** La pesanteur d'un tir de ce genre (0 : il file droit). */
    protected double gravite(int genre) {
        return 0;
    }

    /** Ce qu'un tir laisse derrière lui, à chaque tick. */
    protected void traine(ServerLevel sl, Vec3 p, int genre) {
    }

    /** Le tir arrive : sur ce joueur, ou contre un mur ({@code joueur} null). */
    protected void impact(ServerLevel sl, Vec3 p, int genre, Player joueur) {
    }

    private void majTirs(ServerLevel sl) {
        if (tirs.isEmpty()) {
            return;
        }
        List<Tir> finis = new ArrayList<>();
        for (Tir t : tirs) {
            if (t.suivi != null && t.suivi.isAlive() && t.guidage > 0) {
                Vec3 vers = t.suivi.position().add(0, 1, 0).subtract(t.pos).normalize().scale(t.vel.length());
                t.vel = t.vel.lerp(vers, t.guidage);
            }
            t.vel = t.vel.add(0, -gravite(t.genre), 0);
            Vec3 avant = t.pos;
            t.pos = t.pos.add(t.vel);
            traine(sl, t.pos, t.genre);
            Player touche = null;
            for (Player p : dansArene()) {
                Vec3 c = p.position().add(0, 0.9, 0);
                if (c.distanceToSqr(t.pos) < 1.4 * 1.4 || c.distanceToSqr(avant.lerp(t.pos, 0.5)) < 1.2 * 1.2) {
                    touche = p;
                    break;
                }
            }
            boolean mur = !sl.getBlockState(net.minecraft.core.BlockPos.containing(t.pos)).getCollisionShape(sl,
                    net.minecraft.core.BlockPos.containing(t.pos)).isEmpty();
            if (touche != null || mur || --t.vie <= 0) {
                impact(sl, t.pos, t.genre, touche);
                finis.add(t);
            }
        }
        tirs.removeAll(finis);
    }

    @Override
    public void tick() {
        super.tick();
        if (!level().isClientSide && isAlive()) {
            majTirs((ServerLevel) level());
        }
    }

    // ---------------------------------------------------------------- les dégâts

    @Override
    public boolean hurt(DamageSource source, float degats) {
        int e = etat();
        if (e == SOMMEIL || e == REVEIL || e == RAGE) {
            if (source.getEntity() instanceof Player && !level().isClientSide) {
                level().playSound(null, getX(), getY() + 2, getZ(), SoundEvents.ANVIL_PLACE, SoundSource.HOSTILE, 0.6F, 0.6F);
            }
            return source.is(net.minecraft.tags.DamageTypeTags.BYPASSES_INVULNERABILITY) && super.hurt(source, degats);
        }
        float d = degats / echellePv() * vulnerabilite(e);
        if (source.getEntity() instanceof Player p) {
            menace.merge(p.getUUID(), degats, Float::sum);
        }
        return super.hurt(source, d);
    }

    // ---------------------------------------------------------------- le cerveau

    protected boolean valide(Player p) {
        return p.isAlive() && !p.isSpectator() && !p.isCreative() && p.distanceToSqr(arene()) < sq(rayon() + 6);
    }

    private static double sq(double v) {
        return v * v;
    }

    protected List<Player> dansArene() {
        List<Player> l = new ArrayList<>();
        for (Player p : level().players()) {
            if (valide(p)) {
                l.add(p);
            }
        }
        return l;
    }

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

    /** Pour les captures de l'hôte : le boss joue tel geste, sans combattre, à partir de tel tick. */
    private int vitrineEtat = -1;
    private int vitrineDepart;

    protected Vec3 vitrinePos() {
        return repos();
    }

    public void vitrine(int etat, int dans) {
        vitrineEtat = etat;
        vitrineDepart = tickCount + Math.max(1, dans);
    }

    private void jouerVitrine(ServerLevel sl) {
        setDeltaMovement(0, getDeltaMovement().y, 0);
        cible = null;
        if (tickCount == vitrineDepart) {
            Vec3 r = vitrinePos();
            teleportTo(r.x, r.y, r.z);
            regarder(arene());
            passer(vitrineEtat);
        }
        if (tickCount < vitrineDepart) {
            return;
        }
        int e = etat();
        try {
            if (e == REVEIL) {
                reveil(sl, age());
            } else if (e == RAGE) {
                rage(sl, List.of(), age());
            } else if (e == INVOCATION) {
                invocation(sl, age());
            } else if (e >= 10) {
                action(sl, List.of(), e);
            }
        } catch (RuntimeException ignore) {
            // un geste qui a besoin d'une cible : on s'arrête là
        }
    }

    @Override
    protected void customServerAiStep() {
        super.customServerAiStep();
        ServerLevel sl = (ServerLevel) level();
        if (vitrineEtat >= 0) {
            jouerVitrine(sl);
            return;
        }
        List<Player> joueurs = dansArene();
        if (tickCount % 5 == 0) {
            majBarre(sl);
        }
        if (tickCount % 20 == 0) {
            menace.replaceAll((u, m) -> m * 0.85F);
        }
        int e = etat();
        if (e == SOMMEIL) {
            dormir();
            if (joueurs.stream().anyMatch(p -> p.distanceToSqr(arene()) < sq(rayon() - 4))) {
                reveiller(sl);
            }
            return;
        }
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
        if (distanceToSqr(arene()) > sq(rayon() + 3) && e != REVEIL) {
            Vec3 c = arene();
            teleportTo(c.x, c.y, c.z);
        }
        renfortCd--;
        if (e == CHASSE && ((invocations < SEUILS.length && getHealth() < VIE * SEUILS[invocations]) || (enrage() && renfortCd <= 0))) {
            if (invocations < SEUILS.length && getHealth() < VIE * SEUILS[invocations]) {
                invocations++;
            }
            renfortCd = 700;
            passer(INVOCATION);
            e = INVOCATION;
        }
        if (e == CHASSE && !enrage() && getHealth() < VIE / 2) {
            passer(RAGE);
            e = RAGE;
        }
        switch (e) {
            case REVEIL -> {
                reveil(sl, age());
                if (age() >= dureeReveil()) {
                    passer(CHASSE);
                }
            }
            case CHASSE -> chasse(sl, joueurs);
            case RAGE -> {
                setDeltaMovement(0, getDeltaMovement().y, 0);
                if (age() == 1) {
                    Boss.annoncer(sl, typeDonjon(), "ALERTE", new String[] {typeDonjon().boss.split(",")[0] + " entre en rage !"}, "", 0);
                }
                rage(sl, joueurs, age());
                if (age() >= dureeRage()) {
                    setEnrage(true);
                    passer(CHASSE);
                }
            }
            case INVOCATION -> {
                setDeltaMovement(0, getDeltaMovement().y, 0);
                int a = age();
                if (a == 1) {
                    Boss.annoncer(sl, typeDonjon(), "ALERTE", new String[] {typeDonjon().boss.split(",")[0] + " appelle des renforts !"}, "", 0);
                }
                invocation(sl, a);
                if (a == 26) {
                    invoquer(sl, joueurs.size());
                }
                if (a >= DUREE_INVOCATION) {
                    passer(CHASSE);
                }
            }
            default -> action(sl, joueurs, e);
        }
    }

    private void reveiller(ServerLevel sl) {
        passer(REVEIL);
        Epreuves.de(typeDonjon()).debutCombat(sl);
        setHealth(getMaxHealth());
        invocations = 0;
        menace.clear();
        Boss.annoncer(sl, typeDonjon(), "ALERTE", new String[] {"Le boss du donjon s'est réveillé."}, typeDonjon().boss.toUpperCase(),
                0xFF000000 | typeDonjon().couleur);
    }

    protected void rendormir() {
        seul = 0;
        setHealth(getMaxHealth());
        setEnrage(false);
        setNoGravity(false);
        invocations = 0;
        menace.clear();
        Vec3 p = repos();
        teleportTo(p.x, p.y, p.z);
        passer(SOMMEIL);
        barre.removeAllPlayers();
        if (level() instanceof ServerLevel sl) {
            Epreuves.de(typeDonjon()).finCombat(sl, false);
        }
    }

    private void majBarre(ServerLevel sl) {
        if (etat() == SOMMEIL) {
            barre.removeAllPlayers();
            return;
        }
        int pv = Math.round(getHealth() * echellePv());
        int max = Math.round(getMaxHealth() * echellePv());
        barre.setName(Component.literal(typeDonjon().boss.toUpperCase() + "   ").withStyle(ChatFormatting.BOLD)
                .withStyle(s -> s.withColor(typeDonjon().couleur))
                .append(Component.literal(String.format("%,d / %,d PV", pv, max).replace(',', ' ')).withStyle(ChatFormatting.WHITE)));
        barre.setProgress(Mth.clamp(getHealth() / getMaxHealth(), 0, 1));
        barre.setColor(enrage() ? BossEvent.BossBarColor.PURPLE : couleurBarre());
        for (ServerPlayer p : sl.players()) {
            if (p.distanceToSqr(arene()) < sq(rayon() + 18)) {
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

    @Override
    public void die(DamageSource source) {
        super.die(source);
        barre.removeAllPlayers();
        setNoGravity(false);
        if (level() instanceof ServerLevel sl) {
            Boss.victoire(sl, typeDonjon());
        }
    }

    @Override
    protected void tickDeath() {
        deathTime++;
        if (deathTime >= DUREE_MORT && !level().isClientSide && !isRemoved()) {
            ServerLevel sl = (ServerLevel) level();
            sl.sendParticles(ParticleTypes.LARGE_SMOKE, getX(), getY() + 1, getZ(), 80, 2, 1, 2, 0.05);
            sl.sendParticles(ParticleTypes.SOUL, getX(), getY() + 1, getZ(), 30, 1.5, 1, 1.5, 0.05);
            remove(RemovalReason.KILLED);
        }
    }
}
