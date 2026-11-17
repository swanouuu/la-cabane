package fr.cabane.parc;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;

/**
 * Ce qui bouge dans le parc, côté serveur : les trains des manèges. Chaque train garde sa distance et sa vitesse,
 * avance à chaque tick, s'arrête en gare (on y descend, on y monte), et place ses wagons. Les wagons sont recréés s'ils
 * manquent (ils ne sont pas sauvegardés) ; les tronçons du parcours restent chargés tant qu'il y a du monde au parc.
 */
final class ParcVie {
    private static final List<Train> TRAINS = new ArrayList<>();
    private static final Set<Long> FORCES = new HashSet<>();
    private static ServerLevel niveauForce;

    /** Un train d'un manège. */
    static final class Train {
        final Maneges.Fiche fiche;
        final Trajet trajet;
        final WagonParc[] wagons;
        double s, v;
        int attente;
        int tic;
        /** La distance du splash (Rivière Splash), -1 ailleurs. */
        final double sSplash;
        /** Où la tête s'arrête en gare. */
        final double arret;
        private final Trajet.Point tete = new Trajet.Point(), milieu = new Trajet.Point();

        Train(Maneges.Fiche fiche) {
            this.fiche = fiche;
            this.trajet = Maneges.trajet(fiche.id());
            this.wagons = new WagonParc[fiche.voitures()];
            this.arret = fiche.arret();
            this.s = arret;
            this.attente = fiche.attente();
            this.sSplash = fiche.id() == Maneges.SPLASH ? trajet.ou(Maneges.U_SPLASH) : -1;
        }

        boolean enGare() {
            return attente > 0;
        }

        void tick(ServerLevel level) {
            tic++;
            // les wagons manquants (premier passage, redémarrage du serveur…)
            for (int k = 0; k < wagons.length; k++) {
                if (wagons[k] == null || wagons[k].isRemoved()) {
                    WagonParc w = WagonParc.creer(level, fiche.id(), k, s - k * fiche.ecart());
                    w.addTag(ParcBuilder.TAG);
                    level.addFreshEntity(w);
                    wagons[k] = w;
                }
            }
            double dt = 0.05;
            trajet.a(s, tete);
            if (attente > 0) {
                v = 0;
                boolean monde = false;
                for (WagonParc w : wagons) {
                    monde |= w.isVehicle();
                }
                // le train ne part pas à vide : le compte à rebours ne commence que quand quelqu'un est monté
                if (!monde) {
                    attente = Math.max(attente, fiche.attente());
                } else {
                    attente--;
                }
                if (monde && attente % 20 == 0 && attente > 0) {
                    for (WagonParc w : wagons) {
                        for (Entity p : w.getPassengers()) {
                            if (p instanceof ServerPlayer j) {
                                j.displayClientMessage(Component.literal("Départ dans " + attente / 20 + " s").withStyle(ChatFormatting.YELLOW), true);
                            }
                        }
                    }
                }
                if (attente == 0) {
                    for (WagonParc w : wagons) {
                        w.setLibre(false);
                    }
                    level.playSound(null, tete.x, tete.y, tete.z, SoundEvents.NOTE_BLOCK_BELL.value(), SoundSource.NEUTRAL, 1.2F, 1.2F);
                }
            } else {
                // la physique se fait au milieu du train (c'est lui qui « tire » dans les descentes et les montées)
                trajet.a(s - (wagons.length - 1) * fiche.ecart() / 2, milieu);
                v = Maneges.vitesseSuivante(fiche.id(), milieu, v, dt);
                double avant = trajet.normaliser(s);
                s = avant + v * dt;
                double apres = trajet.normaliser(s);
                boolean passe = passe(avant, apres, arret);
                // le gros splash en bas de la descente (les gerbes d'eau, chaque joueur les fait chez lui)
                for (int j = 0; j < wagons.length && sSplash >= 0; j++) {
                    double sk = sSplash + j * fiche.ecart();
                    if (passe(avant, apres, trajet.normaliser(sk))) {
                        Trajet.Point q = trajet.a(sSplash, new Trajet.Point());
                        level.playSound(null, q.x, q.y, q.z, SoundEvents.GENERIC_SPLASH, SoundSource.NEUTRAL, 3F, 0.6F);
                        level.playSound(null, q.x, q.y, q.z, SoundEvents.PLAYER_SPLASH_HIGH_SPEED, SoundSource.NEUTRAL, 3F, 0.8F);
                    }
                }
                if (passe) {
                    s = arret;
                    v = 0;
                    arriver(level);
                }
                // la chaîne qui cliquette pendant la montée
                if (tete.mode == Trajet.LIFT && tic % 3 == 0 && fiche.id() == Maneges.DRAGON) {
                    level.playSound(null, tete.x, tete.y, tete.z, SoundEvents.CHAIN_HIT, SoundSource.NEUTRAL, 0.7F, 1.5F);
                }
            }
            for (int k = 0; k < wagons.length; k++) {
                wagons[k].placer(s - k * fiche.ecart(), v);
            }
        }

        /** La tête est-elle passée par {@code cible} en allant de {@code avant} à {@code apres} (en faisant peut-être le tour) ? */
        private static boolean passe(double avant, double apres, double cible) {
            return avant <= apres ? avant < cible && apres >= cible : cible > avant || cible <= apres;
        }

        /** En gare : tout le monde descend (avec, une fois sur deux, la tête qui tourne), puis on attend les suivants. */
        private void arriver(ServerLevel level) {
            attente = fiche.attente();
            RandomSource r = level.getRandom();
            for (WagonParc w : wagons) {
                w.setLibre(true);
                for (Entity p : List.copyOf(w.getPassengers())) {
                    p.stopRiding();
                    if (p instanceof ServerPlayer j) {
                        if (fiche.nausee() && r.nextBoolean()) {
                            j.addEffect(new MobEffectInstance(MobEffects.CONFUSION, 20 * 10, 0, false, false, true));
                            j.displayClientMessage(Component.literal("Tu as un peu la nausée…").withStyle(ChatFormatting.GREEN), true);
                        } else {
                            j.displayClientMessage(Component.literal("Encore un tour ? Remonte dans un wagon !").withStyle(ChatFormatting.YELLOW), true);
                        }
                    }
                }
            }
        }
    }

    static void tick(ServerLevel parc) {
        boolean monde = !parc.players().isEmpty();
        forcer(parc, monde);
        if (!monde) {
            return;
        }
        creer();
        for (Train t : TRAINS) {
            t.tick(parc);
        }
        TournantsVie.tick(parc);
    }

    private static void creer() {
        if (TRAINS.isEmpty()) {
            for (byte id = 0; id < Maneges.NOMBRE; id++) {
                TRAINS.add(new Train(Maneges.fiche(id)));
            }
        }
    }

    /**
     * L'hôte (et les tests) : le train de ce manège revient en gare, part dans 3 secondes, et le joueur est assis au
     * premier rang.
     */
    static boolean monter(ServerLevel parc, ServerPlayer joueur, byte manege) {
        forcer(parc, true);
        creer();
        Train t = TRAINS.get(manege);
        t.tick(parc);
        for (WagonParc w : t.wagons) {
            for (Entity p : List.copyOf(w.getPassengers())) {
                p.stopRiding();
            }
        }
        t.s = t.arret;
        t.v = 0;
        t.attente = 60;
        for (int k = 0; k < t.wagons.length; k++) {
            t.wagons[k].setLibre(true);
            t.wagons[k].placer(t.s - k * t.fiche.ecart(), 0);
        }
        joueur.stopRiding();
        return joueur.startRiding(t.wagons[0], true);
    }

    /** Après une reconstruction : les wagons sont recréés au prochain tick. */
    static void oublier() {
        for (Train t : TRAINS) {
            for (WagonParc w : t.wagons) {
                if (w != null) {
                    w.ejectPassengers();
                    w.discard();
                }
            }
        }
        TRAINS.clear();
        TournantsVie.oublier();
    }

    /** Le train de ce wagon laisse-t-il descendre ? (sinon on reste assis jusqu'au retour en gare) */
    static boolean descenteLibre(WagonParc w) {
        return w.libre();
    }

    /** Les tronçons sous les rails restent chargés tant qu'il y a du monde au parc (les wagons doivent pouvoir rouler). */
    private static void forcer(ServerLevel parc, boolean oui) {
        if (oui && FORCES.isEmpty()) {
            Trajet.Point p = new Trajet.Point();
            for (byte id = 0; id < Maneges.NOMBRE; id++) {
                Trajet t = Maneges.trajet(id);
                for (double s = 0; s < t.total; s += 4) {
                    t.a(s, p);
                    FORCES.add(ChunkPos.asLong((int) Math.floor(p.x) >> 4, (int) Math.floor(p.z) >> 4));
                }
            }
            // les manèges qui tournent et la piste des autos
            for (double[] c : Tournants.centres()) {
                for (int dx = -24; dx <= 24; dx += 8) {
                    for (int dz = -24; dz <= 24; dz += 8) {
                        FORCES.add(ChunkPos.asLong((int) Math.floor(c[0] + dx) >> 4, (int) Math.floor(c[1] + dz) >> 4));
                    }
                }
            }
            for (int dx = -24; dx <= 24; dx += 8) {
                for (int dz = -16; dz <= 16; dz += 8) {
                    FORCES.add(ChunkPos.asLong((int) Math.floor(AutoParc.PISTE_X + dx) >> 4, (int) Math.floor(AutoParc.PISTE_Z + dz) >> 4));
                }
            }
            for (long c : FORCES) {
                parc.setChunkForced(ChunkPos.getX(c), ChunkPos.getZ(c), true);
            }
            niveauForce = parc;
        } else if (!oui && !FORCES.isEmpty()) {
            for (long c : FORCES) {
                parc.setChunkForced(ChunkPos.getX(c), ChunkPos.getZ(c), false);
            }
            FORCES.clear();
            niveauForce = null;
        }
    }

    /** À l'arrêt du serveur : on relâche les tronçons forcés et on oublie les trains. */
    static void arret() {
        if (niveauForce != null) {
            for (long c : FORCES) {
                niveauForce.setChunkForced(ChunkPos.getX(c), ChunkPos.getZ(c), false);
            }
        }
        FORCES.clear();
        niveauForce = null;
        TRAINS.clear();
        TournantsVie.arret();
    }

    private ParcVie() {
    }
}
