package fr.cabane.backrooms;

import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Un monstre des backrooms, tel que l'aperçu le fait vivre (jeu.js) : il erre de pièce en pièce ; dès que tu entres dans
 * son champ de vision (jamais à travers un mur), un grincement part et il te fonce dessus ; s'il te perd, il va voir là
 * où il t'a vu pour la dernière fois et renifle, puis repart. Si tu cours près de lui, il t'entend et se retourne. Si tu
 * es caché dans un casier, il ne te voit pas. S'il t'attrape : le screamer (Backrooms.attraper), rien que pour toi.
 *
 * <p>Il ne prend aucun coup (seul /kill l'enlève) et n'est jamais sauvegardé : la population est refaite autour des joueurs.
 */
public class Monstre extends PathfinderMob {
    /**
     * Les cinq monstres. Vitesses en blocs par seconde (le joueur marche à 4,3 et court à 5,6), portée et angle du regard,
     * distance de prise, temps avant d'oublier une proie perdue de vue, longueur d'un pas pour l'animation.
     */
    public enum Sorte {
        RAMPEUR("rampeur", "le Rampeur", 1.9, 6.1, 20, 150, 1.35, 2.5, 3.4, 0.45, false, 0.9F, 1.15F),
        SOURIANT("souriant", "le Souriant", 1.3, 5.5, 16, 130, 1.0, 3.2, 2.3, 0.8, false, 0.6F, 2.5F),
        FETARD("fetard", "le Fêtard", 1.5, 5.0, 18, 170, 1.0, 7, 2.6, 0.8, false, 0.6F, 2.2F),
        SANSVISAGE("sansvisage", "le Sans-Visage", 1.6, 5.3, 18, 140, 1.0, 4, 2.6, 0.8, true, 0.6F, 2.0F),
        PHALENE("phalene", "la Phalène", 2.2, 5.8, 22, 160, 1.15, 2, 1, 0.22, false, 0.8F, 2.1F);

        public final String id, nom;
        public final double erre, chasse, portee, cone, prise, oubli, pas, ecartPas;
        public final boolean muet;
        public final float largeur, hauteur;

        Sorte(String id, String nom, double erre, double chasse, double portee, double cone, double prise, double oubli, double pas,
                double ecartPas, boolean muet, float largeur, float hauteur) {
            this.id = id;
            this.nom = nom;
            this.erre = erre;
            this.chasse = chasse;
            this.portee = portee;
            this.cone = cone;
            this.prise = prise;
            this.oubli = oubli;
            this.pas = pas;
            this.ecartPas = ecartPas;
            this.muet = muet;
            this.largeur = largeur;
            this.hauteur = hauteur;
        }

        /**
         * La vitesse de base (attribut) qui donne {@code chasse} blocs par seconde : au sol, un mob avance de
         * 0,98 × v² / (1 − 0,546) bloc par tick pour une vitesse v.
         */
        public double attribut() {
            return Math.sqrt(chasse / 43.2);
        }

        /** Quand il se jette sur toi après le début du screamer (secondes) : le cri est calé dessus (outils/backrooms.mjs). */
        public double debut() {
            return switch (this) {
                case RAMPEUR, PHALENE -> 0.05;
                case SOURIANT -> 0.42;
                case FETARD -> 0.62;
                case SANSVISAGE -> 0.45;
            };
        }

        /** Le multiplicateur pour aller à {@code bps} blocs par seconde. */
        double facteur(double bps) {
            return Math.sqrt(bps / chasse);
        }
    }

    public static final byte ERRE = 0, CHASSE = 1, CHERCHE = 2, RENIFLE = 3;
    private static final EntityDataAccessor<Byte> ETAT = SynchedEntityData.defineId(Monstre.class, EntityDataSerializers.BYTE);

    private final Sorte sorte;
    private UUID proie;
    private Vec3 dernier;
    private int perdu, renifle, attente, recalcul, balade, coince, cherche;
    private BlockPos but;
    private double pasCumul;
    private Vec3 avant;

    // l'animation, côté client
    public float cycle, cycleO, vitesse;

    public Monstre(EntityType<? extends Monstre> type, Level level, Sorte sorte) {
        super(type, level);
        this.sorte = sorte;
        setPersistenceRequired();
    }

    public static AttributeSupplier.Builder attributs(Sorte sorte) {
        return PathfinderMob.createMobAttributes().add(Attributes.MAX_HEALTH, 40).add(Attributes.MOVEMENT_SPEED, sorte.attribut())
                .add(Attributes.FOLLOW_RANGE, 56).add(Attributes.KNOCKBACK_RESISTANCE, 1).add(Attributes.STEP_HEIGHT, 0.6);
    }

    public Sorte sorte() {
        return sorte;
    }

    public byte etat() {
        return entityData.get(ETAT);
    }

    private void etat(byte e) {
        entityData.set(ETAT, e);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(ETAT, ERRE);
    }

    // ---------------------------------------------------------------- ce qu'il ne fait pas

    @Override
    public boolean isInvulnerableTo(DamageSource source) {
        return !source.is(DamageTypeTags.BYPASSES_INVULNERABILITY);
    }

    @Override
    public boolean removeWhenFarAway(double distance) {
        return false;
    }

    @Override
    public boolean shouldBeSaved() {
        return false;
    }

    @Override
    protected void playStepSound(BlockPos pos, BlockState state) {
    }

    @Override
    public boolean canBeLeashed() {
        return false;
    }

    // ---------------------------------------------------------------- l'animation (client)

    @Override
    public void tick() {
        super.tick();
        if (level().isClientSide) {
            double d = Math.hypot(getX() - xo, getZ() - zo);
            cycleO = cycle;
            cycle += (float) (d * sorte.pas);
            vitesse += ((float) d * 20 - vitesse) * 0.35F;
        }
    }

    // ---------------------------------------------------------------- la tête

    /** Le joueur qu'il voit : dans sa portée, dans son champ de vision, sans mur entre eux, pas caché. */
    private ServerPlayer voit() {
        ServerPlayer mieux = null;
        double dMieux = Double.MAX_VALUE;
        double cosCone = Math.cos(Math.toRadians(sorte.cone / 2));
        double yaw = Math.toRadians(getYRot());
        double fx = -Math.sin(yaw), fz = Math.cos(yaw);
        for (Player p : level().players()) {
            if (!(p instanceof ServerPlayer j) || !Backrooms.chassable(j)) {
                continue;
            }
            double dx = j.getX() - getX(), dz = j.getZ() - getZ(), d = Math.hypot(dx, dz);
            if (d > sorte.portee || d >= dMieux || Math.abs(j.getY() - getY()) > 3) {
                continue;
            }
            if (d > 2.2 && (dx * fx + dz * fz) / Math.max(d, 1e-3) < cosCone) {
                continue;
            }
            if (!hasLineOfSight(j)) {
                continue;
            }
            mieux = j;
            dMieux = d;
        }
        return mieux;
    }

    private ServerPlayer proie() {
        return proie != null && level() instanceof ServerLevel s && s.getPlayerByUUID(proie) instanceof ServerPlayer j ? j : null;
    }

    /** Remis à zéro (après un screamer, ou quand sa proie a disparu). */
    public void oublier() {
        etat(ERRE);
        proie = null;
        but = null;
        attente = 20;
        getNavigation().stop();
    }

    private void tourner(double versX, double versZ, float max) {
        float voulu = (float) (Mth.atan2(versZ - getZ(), versX - getX()) * Mth.RAD_TO_DEG) - 90F;
        float y = Mth.approachDegrees(getYRot(), voulu, max);
        setYRot(y);
        yBodyRot = y;
        yHeadRot = y;
    }

    @Override
    protected void customServerAiStep() {
        super.customServerAiStep();
        if (!(level() instanceof ServerLevel monde)) {
            return;
        }
        double bouge = avant == null ? 0 : Math.hypot(getX() - avant.x, getZ() - avant.z);
        ServerPlayer vu = tickCount % 2 == 0 || etat() == CHASSE ? voit() : null;
        if (vu != null) {
            if (etat() != CHASSE || !vu.getUUID().equals(proie)) {
                Backrooms.repere(vu, this);
            }
            etat(CHASSE);
            proie = vu.getUUID();
            perdu = 0;
            dernier = vu.position();
        }
        byte e = etat();
        ServerPlayer cible = proie();
        // il t'entend courir et se retourne
        if (vu == null && e == ERRE) {
            for (Player p : monde.players()) {
                if (p instanceof ServerPlayer j && Backrooms.chassable(j) && j.isSprinting() && j.distanceToSqr(this) < 81) {
                    getNavigation().stop();
                    tourner(j.getX(), j.getZ(), 18);
                    attente = Math.max(attente, 15);
                    break;
                }
            }
        }

        if (e == CHASSE) {
            if (cible == null || !Backrooms.chassable(cible)) {
                // caché, attrapé par un autre, parti : il va voir là où il l'a vu en dernier
                e = dernier != null ? CHERCHE : ERRE;
                etat(e);
                cherche = 0;
            } else {
                if (vu == null) {
                    perdu++;
                    if (perdu > sorte.oubli * 20) {
                        e = CHERCHE;
                        etat(e);
                        cherche = 0;
                    }
                }
                if (e == CHASSE) {
                    double d = Math.hypot(cible.getX() - getX(), cible.getZ() - getZ());
                    if (vu != null && d < 3) {
                        // tout près : droit sur lui
                        getNavigation().stop();
                        getMoveControl().setWantedPosition(cible.getX(), cible.getY(), cible.getZ(), 1.0);
                    } else if (--recalcul <= 0) {
                        recalcul = 4;
                        Vec3 vers = vu != null ? cible.position() : dernier;
                        getNavigation().moveTo(vers.x, vers.y, vers.z, 1.0);
                    }
                    if (vu != null && d < sorte.prise) {
                        Backrooms.attraper(cible, this);
                        return;
                    }
                    pas(monde, bouge);
                }
            }
        }
        if (e == CHERCHE) {
            double d = dernier == null ? 0 : Math.hypot(dernier.x - getX(), dernier.z - getZ());
            if (dernier == null || d < 1.1 || ++cherche > 20 * 10) {
                etat(RENIFLE);
                renifle = 72;
                getNavigation().stop();
                e = RENIFLE;
                Backrooms.renifle(monde, this);
            } else if (--recalcul <= 0 || getNavigation().isDone()) {
                recalcul = 10;
                getNavigation().moveTo(dernier.x, dernier.y, dernier.z, sorte.facteur(sorte.chasse * 0.55));
            }
        }
        if (e == RENIFLE) {
            renifle--;
            // il tourne la tête d'un côté et de l'autre, devant le casier
            float y = getYRot() + Mth.sin(tickCount * 0.12F) * 6F;
            setYRot(y);
            yBodyRot = y;
            yHeadRot = y;
            if (renifle % 16 == 0 && renifle > 0) {
                Backrooms.renifle(monde, this);
            }
            if (renifle <= 0) {
                etat(ERRE);
                proie = null;
                but = null;
                attente = 10;
                e = ERRE;
            }
        }
        if (e == ERRE) {
            if (attente > 0) {
                attente--;
            } else {
                if (but == null) {
                    but = Backrooms.balade(this);
                    balade = 0;
                    if (but == null) {
                        attente = 40;
                        return;
                    }
                    getNavigation().moveTo(but.getX() + 0.5, but.getY(), but.getZ() + 0.5, sorte.facteur(sorte.erre));
                }
                balade++;
                double d = Math.hypot(but.getX() + 0.5 - getX(), but.getZ() + 0.5 - getZ());
                if (d < 0.9 || balade > 20 * 25 || getNavigation().isDone()) {
                    but = null;
                    attente = 12 + getRandom().nextInt(50);
                }
            }
        }
        // coincé contre un coin : on refait le chemin
        if (e != RENIFLE && avant != null && bouge < 1e-3 && !getNavigation().isDone()) {
            if (++coince > 30) {
                coince = 0;
                getNavigation().recomputePath();
            }
        } else {
            coince = 0;
        }
        avant = position();
    }

    /** Les pas qu'on entend arriver, quand il te court après. */
    private void pas(ServerLevel monde, double bouge) {
        if (sorte.muet) {
            return;
        }
        pasCumul += bouge;
        if (pasCumul > sorte.ecartPas) {
            pasCumul = 0;
            var son = sorte == Sorte.RAMPEUR ? Backrooms.SON_GRIFFE.get() : Backrooms.SON_PAS.get();
            monde.playSound(null, getX(), getY(), getZ(), son, SoundSource.HOSTILE, sorte == Sorte.PHALENE ? 0.35F : 0.9F,
                    0.9F + getRandom().nextFloat() * 0.2F);
        }
    }

    /** Sa proie vient de se cacher dans un casier : il ira renifler juste devant. */
    void cacheIci(UUID joueur, Vec3 devant) {
        if (joueur.equals(proie) && (etat() == CHASSE || etat() == CHERCHE)) {
            dernier = devant;
            etat(CHERCHE);
            cherche = 0;
            recalcul = 0;
        }
    }
}
