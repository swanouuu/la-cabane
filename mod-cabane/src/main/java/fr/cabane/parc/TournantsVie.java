package fr.cabane.parc;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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

/**
 * Les manèges qui tournent, côté serveur : chacun attend en gare (on monte), part, fait son tour, revient, et tout le
 * monde descend (une fois sur deux avec la tête qui tourne pour les Tasses, le Bateau et la Toupie). La Grande Roue,
 * elle, ne s'arrête jamais : on monte dans la cabine qui passe en bas, et on descend quand elle y revient après un tour.
 * Les autos-tamponneuses sont gardées ici aussi (dix, recréées si elles manquent).
 */
final class TournantsVie {
    private static final List<Manege> MANEGES = new ArrayList<>();
    private static final AutoParc[] AUTOS = new AutoParc[10];

    private static final class Manege {
        final Tournants.Fiche fiche;
        final NacelleParc[] nacelles;
        int debut = -1, attente;
        /** La Grande Roue : quand chacun est monté (ticks). */
        final Map<UUID, Long> montes = new HashMap<>();

        Manege(Tournants.Fiche fiche) {
            this.fiche = fiche;
            this.nacelles = new NacelleParc[fiche.nacelles()];
            this.attente = fiche.attente() * 20;
        }

        void tick(ServerLevel level) {
            for (int k = 0; k < nacelles.length; k++) {
                if (nacelles[k] == null || nacelles[k].isRemoved()) {
                    NacelleParc n = NacelleParc.creer(level, fiche.id(), k);
                    n.addTag(ParcBuilder.TAG);
                    n.setDebut(debut);
                    level.addFreshEntity(n);
                    nacelles[k] = n;
                }
            }
            long maintenant = level.getGameTime();
            if (fiche.id() == Tournants.ROUE) {
                roue(maintenant);
            } else if (debut < 0) {
                attendre(level, maintenant);
            } else if ((maintenant - debut) / 20.0 >= fiche.duree()) {
                arriver(level);
            } else {
                pendant(level, (maintenant - debut) / 20.0);
            }
            for (NacelleParc n : nacelles) {
                n.placer();
            }
        }

        private void roue(long maintenant) {
            double s = maintenant / 20.0;
            for (NacelleParc n : nacelles) {
                boolean bas = Tournants.roueEnBas(n.index(), s);
                n.setLibre(bas);
                for (Entity p : List.copyOf(n.getPassengers())) {
                    long depuis = montes.computeIfAbsent(p.getUUID(), u -> maintenant);
                    if (bas && maintenant - depuis > 20 * 60) {
                        montes.remove(p.getUUID());
                        p.stopRiding();
                        if (p instanceof ServerPlayer j) {
                            j.displayClientMessage(Component.literal("Fin du tour de roue ! Belle vue, hein ?").withStyle(ChatFormatting.YELLOW), true);
                        }
                    }
                }
            }
            montes.keySet().removeIf(u -> {
                for (NacelleParc n : nacelles) {
                    for (Entity p : n.getPassengers()) {
                        if (p.getUUID().equals(u)) {
                            return false;
                        }
                    }
                }
                return true;
            });
        }

        private boolean monde() {
            for (NacelleParc n : nacelles) {
                if (n.isVehicle()) {
                    return true;
                }
            }
            return false;
        }

        private void message(Component c) {
            for (NacelleParc n : nacelles) {
                for (Entity p : n.getPassengers()) {
                    if (p instanceof ServerPlayer j) {
                        j.displayClientMessage(c, true);
                    }
                }
            }
        }

        private void attendre(ServerLevel level, long maintenant) {
            for (NacelleParc n : nacelles) {
                n.setLibre(true);
            }
            // le manège ne part pas à vide : le compte à rebours ne commence que quand quelqu'un est monté
            if (!monde()) {
                attente = Math.max(attente, fiche.attente() * 20);
                return;
            }
            attente--;
            if (attente > 0 && attente % 20 == 0) {
                message(Component.literal("Départ dans " + attente / 20 + " s").withStyle(ChatFormatting.YELLOW));
            }
            if (attente <= 0) {
                debut = (int) maintenant;
                for (NacelleParc n : nacelles) {
                    n.setLibre(false);
                    n.setDebut(debut);
                }
                NacelleParc n = nacelles[0];
                level.playSound(null, n.getX(), n.getY(), n.getZ(), SoundEvents.NOTE_BLOCK_BELL.value(), SoundSource.NEUTRAL, 1.4F, 1.2F);
            }
        }

        /** Pendant le tour : la Tour fait « clac » en haut avant de lâcher, et ses freins sifflent en bas. */
        private void pendant(ServerLevel level, double t) {
            if (fiche.id() != Tournants.TOUR) {
                return;
            }
            double avant = t - 0.05;
            NacelleParc n = nacelles[0];
            if (avant < Tournants.TOUR_LACHE - 0.6 && t >= Tournants.TOUR_LACHE - 0.6) {
                level.playSound(null, n.getX(), n.getY(), n.getZ(), SoundEvents.IRON_DOOR_CLOSE, SoundSource.NEUTRAL, 2F, 0.6F);
                message(Component.literal("…").withStyle(ChatFormatting.GRAY));
            }
            if (avant < Tournants.TOUR_FREIN && t >= Tournants.TOUR_FREIN) {
                level.playSound(null, n.getX(), n.getY(), n.getZ(), SoundEvents.PISTON_CONTRACT, SoundSource.NEUTRAL, 2F, 0.5F);
            }
        }

        private void arriver(ServerLevel level) {
            debut = -1;
            attente = fiche.attente() * 20;
            RandomSource r = level.getRandom();
            for (NacelleParc n : nacelles) {
                n.setDebut(-1);
                n.setLibre(true);
                for (Entity p : List.copyOf(n.getPassengers())) {
                    p.stopRiding();
                    if (p instanceof ServerPlayer j) {
                        if (fiche.nausee() && r.nextBoolean()) {
                            j.addEffect(new MobEffectInstance(MobEffects.CONFUSION, 20 * 10, 0, false, false, true));
                            j.displayClientMessage(Component.literal("Tu as un peu la nausée…").withStyle(ChatFormatting.GREEN), true);
                        } else {
                            j.displayClientMessage(Component.literal("Encore un tour ? Remonte !").withStyle(ChatFormatting.YELLOW), true);
                        }
                    }
                }
            }
        }
    }

    private static void creer() {
        if (MANEGES.isEmpty()) {
            for (byte id = 0; id < Tournants.NOMBRE; id++) {
                MANEGES.add(new Manege(Tournants.fiche(id)));
            }
        }
    }

    static void tick(ServerLevel parc) {
        creer();
        for (Manege m : MANEGES) {
            m.tick(parc);
        }
        // les autos-tamponneuses, rangées sur deux lignes au départ
        for (int k = 0; k < AUTOS.length; k++) {
            if (AUTOS[k] == null || AUTOS[k].isRemoved()) {
                double x = AutoParc.PISTE_X - 14 + (k % 5) * 7, z = AutoParc.PISTE_Z + (k < 5 ? -6 : 6);
                AutoParc a = AutoParc.creer(parc, k, x, z, k < 5 ? 0F : 180F);
                a.addTag(ParcBuilder.TAG);
                parc.addFreshEntity(a);
                AUTOS[k] = a;
            }
        }
    }

    /**
     * L'hôte (et les tests) : s'asseoir dans ce manège, qui part dans 3 secondes (la Grande Roue : dans la cabine qui
     * arrive en bas). Les autos : dans la première auto.
     */
    static boolean monter(ServerLevel parc, ServerPlayer joueur, String nom) {
        tick(parc);
        if (nom.equals("autos")) {
            joueur.stopRiding();
            return joueur.startRiding(AUTOS[0], true);
        }
        byte id = switch (nom) {
            case "tour" -> Tournants.TOUR;
            case "roue" -> Tournants.ROUE;
            case "carrousel" -> Tournants.CARROUSEL;
            case "tasses" -> Tournants.TASSES;
            case "bateau" -> Tournants.BATEAU;
            case "toupie" -> Tournants.TOUPIE;
            default -> -1;
        };
        if (id < 0) {
            return false;
        }
        Manege m = MANEGES.get(id);
        NacelleParc n = m.nacelles[0];
        if (id == Tournants.ROUE) {
            // la cabine la plus proche du bas
            double s = parc.getGameTime() / 20.0, mieux = 9;
            for (NacelleParc c : m.nacelles) {
                double a = c.index() * Math.PI * 2 / 16 + Tournants.angleRoue(s);
                a = Math.abs(a - Math.floor((a + Math.PI) / (Math.PI * 2)) * Math.PI * 2);
                if (a < mieux) {
                    mieux = a;
                    n = c;
                }
            }
        } else {
            for (NacelleParc c : m.nacelles) {
                c.setDebut(-1);
                c.setLibre(true);
                c.placer();
            }
            m.debut = -1;
            m.attente = 60;
        }
        joueur.stopRiding();
        return joueur.startRiding(n, true);
    }

    static void oublier() {
        for (Manege m : MANEGES) {
            for (NacelleParc n : m.nacelles) {
                if (n != null) {
                    n.ejectPassengers();
                    n.discard();
                }
            }
        }
        MANEGES.clear();
        for (int k = 0; k < AUTOS.length; k++) {
            if (AUTOS[k] != null) {
                AUTOS[k].ejectPassengers();
                AUTOS[k].discard();
                AUTOS[k] = null;
            }
        }
    }

    static void arret() {
        MANEGES.clear();
        java.util.Arrays.fill(AUTOS, null);
    }

    private TournantsVie() {
    }
}
