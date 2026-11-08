package fr.cabane.telephone;

import de.maxhenkel.voicechat.api.VoicechatApi;
import de.maxhenkel.voicechat.api.opus.OpusDecoder;
import de.maxhenkel.voicechat.api.opus.OpusEncoder;
import java.util.Arrays;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Au vocal, la voix de quelqu'un d'ivre, en version goofy (réglage « Ivre mort » du banc d'essai docs/voix-bourree) :
 * la hauteur tangue et dérive, la voix glougloute, la cassette ralentit puis accélère pour rattraper, il bégaie
 * (« je-je-je »), sa voix déraille dans l'aigu (hic !), il rote des bouts de phrase, il parle la bouche pleine (wa-wa)
 * et sa voix se dédouble. À « Pompette » ça tangue à peine ; plus on a bu, plus il y en a ; à « Ivre mort », tout à fond.
 * Le son arrive compressé (Opus) : on le décode, on le déforme, on le réencode, sur le fil du vocal.
 */
final class VoixBourree {
    private static final Map<UUID, Traitement> TRAITEMENTS = new ConcurrentHashMap<>();
    private static final int F = 48000;
    /** La bande (2,7 s) : assez pour la cassette qui traîne et quatre bégaiements d'affilée. */
    private static final int TAILLE = 1 << 17;
    private static final int MASQUE = TAILLE - 1;
    /** Les deux têtes de lecture qui changent la hauteur (40 ms). */
    private static final int FENETRE = F / 25;
    /** 5 ms de fondu à chaque saut de lecture, pour ne pas claquer. */
    private static final int FONDU = F / 200;
    /** La cassette traîne d'au plus 0,3 s : la vitesse varie alors d'au plus ±60 %. */
    private static final double TRAINE_MAX = 0.3;
    /** Plus de 300 ms sans paquet : il s'est tu, on repart d'une bande vierge (sinon on rejouerait sa fin de phrase). */
    private static final long PAUSE_MS = 300;
    /** Les réglages de chaque niveau d'ivresse, de 0 (Pompette) à 6 (Ivre mort). */
    private static final Reglages[] NIVEAUX = new Reglages[7];

    static {
        for (int n = 0; n < NIVEAUX.length; n++) {
            NIVEAUX[n] = new Reglages(Math.min(1, 0.25 + 0.15 * n));
        }
    }

    /** Ce que fait la voix à un niveau donné (force de 0,25 à 1 ; certains effets n'arrivent qu'au-delà d'un seuil). */
    private static final class Reglages {
        final double tangage, derive, gargouille, traine, wah, begaiement, hoquet, rot, double_, pateux, vacille;

        Reglages(double f) {
            tangage = 5 * f;                          // demi-tons, la grande houle
            derive = 3 * f;                           // demi-tons, au hasard
            gargouille = 0.7 * f;                     // demi-tons, le glouglou
            traine = TRAINE_MAX * seuil(f, 0.35);     // secondes, la cassette
            wah = 0.5 * seuil(f, 0.4);                // la bouche pleine
            begaiement = 0.6 * seuil(f, 0.3);         // par seconde
            hoquet = 0.35 * seuil(f, 0.2);            // par seconde
            rot = 0.15 * seuil(f, 0.5);               // par seconde
            double_ = 0.4 * seuil(f, 0.3);            // la voix dédoublée
            pateux = 0.3 * f;
            vacille = 0.3 * f;
        }

        /** 0 jusqu'au seuil, puis monte jusqu'à 1 à pleine force. */
        private static double seuil(double f, double s) {
            return Math.max(0, (f - s) / (1 - s));
        }
    }

    /** Un hasard lisse entre 0 et 1 : on glisse d'un point tiré au sort au suivant (en cosinus). */
    private static final class Derive {
        final Random rnd;
        final double min, max, pente;
        double a, b, t, duree;

        /** pente : la vitesse maximale (unités par seconde) ; 0 = pas de limite. */
        Derive(Random rnd, double min, double max, double pente) {
            this.rnd = rnd;
            this.min = min;
            this.max = max;
            this.pente = pente;
            a = rnd.nextDouble();
            b = rnd.nextDouble();
            duree = tirer();
        }

        double tirer() {
            double d = min + (max - min) * rnd.nextDouble();
            // une pente en cosinus va jusqu'à (b - a) * π / 2 / durée : on rallonge si ça va trop vite
            return pente > 0 ? Math.max(d, Math.abs(b - a) * Math.PI / 2 / pente) : d;
        }

        double suivant() {
            t += 1.0 / F;
            if (t >= duree) {
                t -= duree;
                a = b;
                b = rnd.nextDouble();
                duree = tirer();
            }
            return a + (b - a) * (0.5 - 0.5 * Math.cos(Math.PI * Math.min(1, t / duree)));
        }
    }

    /** L'état d'un joueur : ses codecs (ils gardent une mémoire d'un paquet à l'autre), sa bande et ses filtres. */
    private static final class Traitement {
        final OpusDecoder decodeur;
        final OpusEncoder encodeur;
        final Random rnd = new Random();
        final float[] ruban = new float[TAILLE];
        long ecrit;
        long dernierPaquet;
        /** Où on lit sur la bande, et le volume du fondu. */
        double lecture, fondu = 1;
        final Derive traine, derive, wahHasard;
        double phTangage, phGargouille, phWah, phVolume, phRot;
        double glisse, glisse2 = FENETRE / 3.0;
        /** Bégaiement : 0 rien, 1 on s'efface avant de sauter, 2 on répète la syllabe. */
        int etat, prep, grain, reps;
        double boucleDebut, tete;
        /** Ce qui reste avant le prochain bégaiement / hoquet / rot (en « événements » : on retire leur fréquence). */
        double jaugeBegaiement, jaugeHoquet, jaugeRot;
        int hoquetReste, hoquetDuree, rotReste, rotDuree;
        double hoquetHauteur;
        double filtreBas, filtreBande, sourd;

        Traitement(VoicechatApi api) {
            decodeur = api.createDecoder();
            encodeur = api.createEncoder();
            traine = new Derive(rnd, 0.45, 1.3, 0.6 / TRAINE_MAX);
            derive = new Derive(rnd, 0.3, 0.9, 0);
            wahHasard = new Derive(rnd, 0.2, 0.6, 0);
            jaugeBegaiement = tirage();
            jaugeHoquet = tirage();
            jaugeRot = tirage();
        }

        /** Le temps avant le prochain événement, au hasard (mais pas deux collés). */
        double tirage() {
            return 0.15 - Math.log(1 - rnd.nextDouble());
        }

        /** Il s'est tu : bande vierge et plus de bégaiement en cours. */
        void repartir() {
            Arrays.fill(ruban, 0);
            etat = 0;
            fondu = 1;
            filtreBas = filtreBande = sourd = 0;
        }

        float lire(double pos) {
            long a = (long) Math.floor(pos);
            float frac = (float) (pos - a);
            return ruban[(int) (a & MASQUE)] * (1 - frac) + ruban[(int) ((a + 1) & MASQUE)] * frac;
        }
    }

    /** Le niveau d'ivresse (-1 : sobre) : l'effet le note chaque seconde ; au-delà de 2,5 s sans nouvelles, c'est passé. */
    static int niveau(UUID joueur) {
        long[] v = fr.cabane.boisson.Boissons.IVRESSE_VOIX.get(joueur);
        if (v == null || System.currentTimeMillis() - v[1] > 2500) {
            oublier(joueur);
            return -1;
        }
        return (int) v[0];
    }

    /** Déforme un paquet de micro ; renvoie null si rien à faire (sobre, ou erreur). */
    static byte[] deformer(VoicechatApi api, UUID joueur, byte[] opus, int niveau) {
        if (opus == null || opus.length == 0) {
            return null;
        }
        Traitement t = TRAITEMENTS.computeIfAbsent(joueur, k -> new Traitement(api));
        short[] pcm = t.decodeur.decode(opus);
        if (pcm == null || pcm.length == 0) {
            return null;
        }
        long maintenant = System.currentTimeMillis();
        if (maintenant - t.dernierPaquet > PAUSE_MS) {
            t.repartir();
        }
        t.dernierPaquet = maintenant;
        Reglages r = NIVEAUX[Math.max(0, Math.min(NIVEAUX.length - 1, niveau))];
        short[] sortie = new short[pcm.length];
        for (int i = 0; i < pcm.length; i++) {
            sortie[i] = (short) Math.round(echantillon(t, r, pcm[i] / 32768F) * 32767);
        }
        return t.encodeur.encode(sortie);
    }

    private static double echantillon(Traitement t, Reglages r, float entree) {
        t.ruban[(int) (t.ecrit & MASQUE)] = entree;
        t.ecrit++;

        // 1. où on lit : la cassette qui traîne (en retard, plus ou moins), ou une syllabe qui bégaie
        double normal = t.ecrit - (0.02 + r.traine * t.traine.suivant()) * F;
        double avant = t.lecture;
        boolean saut = false;
        if (t.etat == 0) {
            t.lecture = normal;
            t.fondu = Math.min(1, t.fondu + 1.0 / FONDU);
            if ((t.jaugeBegaiement -= r.begaiement / F) <= 0) {
                t.etat = 1;
                t.prep = FONDU;
                t.jaugeBegaiement = t.tirage();
            }
        } else if (t.etat == 1) {
            // on s'efface en 5 ms avant de sauter en arrière
            t.lecture = normal;
            t.prep--;
            t.fondu = Math.min(t.fondu, t.prep / (double) FONDU);
            if (t.prep <= 0) {
                t.etat = 2;
                t.grain = (int) ((0.07 + 0.08 * t.rnd.nextDouble()) * F);
                t.boucleDebut = t.lecture - t.grain;
                t.tete = 0;
                t.reps = 2 + t.rnd.nextInt(3);
                saut = true;
            }
        } else {
            // la même syllabe 2 à 4 fois, puis on rattrape d'un coup
            t.tete += 1;
            if (t.tete >= t.grain) {
                t.tete -= t.grain;
                saut = true;
                if (--t.reps <= 0) {
                    t.etat = 0;
                    t.fondu = 0;
                }
            }
            if (t.etat == 2) {
                t.lecture = t.boucleDebut + t.tete;
                t.fondu = Math.min(1, Math.min(t.tete, t.grain - t.tete) / FONDU);
            } else {
                t.lecture = normal;
            }
        }
        // la vitesse de la cassette (ralentie : grave et molle ; accélérée : voix de souris)
        double vitesse = saut || t.etat == 2 ? 1 : Math.max(0.3, Math.min(1.8, t.lecture - avant));

        // 2. la hauteur : houle + dérive + glouglou, et les hoquets (vers l'aigu) et les rots (grave, grésillant)
        double demiTons = r.tangage * Math.sin(t.phTangage) + r.derive * (2 * t.derive.suivant() - 1)
                + r.gargouille * Math.sin(t.phGargouille);
        t.phTangage += 2 * Math.PI * 0.6 / F;
        t.phGargouille += 2 * Math.PI * 9.5 / F;
        double volEvenement = 1, grince = 1;
        if (t.hoquetReste > 0) {
            double k = 1 - t.hoquetReste / (double) t.hoquetDuree;
            double forme = k < 0.12 ? k / 0.12 : k > 0.55 ? (1 - k) / 0.45 : 1;
            demiTons += t.hoquetHauteur * forme;
            volEvenement = 1 + 0.6 * forme;
            t.hoquetReste--;
        } else if ((t.jaugeHoquet -= r.hoquet / F) <= 0) {
            t.hoquetDuree = t.hoquetReste = (int) (0.17 * F);
            t.hoquetHauteur = 9 + 6 * t.rnd.nextDouble();
            t.jaugeHoquet = t.tirage();
        }
        if (t.rotReste > 0) {
            double k = 1 - t.rotReste / (double) t.rotDuree;
            double forme = Math.min(1, 2.5 * Math.sin(Math.PI * k));
            demiTons -= 10 * forme;
            t.phRot += 2 * Math.PI * 31 / F;
            grince = 1 - 0.85 * forme * (0.5 + 0.5 * Math.sin(t.phRot));
            volEvenement *= 1 + 0.4 * forme;
            t.rotReste--;
        } else if ((t.jaugeRot -= r.rot / F) <= 0) {
            t.rotDuree = t.rotReste = (int) ((0.45 + 0.3 * t.rnd.nextDouble()) * F);
            t.jaugeRot = t.tirage();
        }
        double hauteur = Math.pow(2, Math.max(-24, Math.min(24, demiTons)) / 12);

        // 3. deux têtes de lecture qui se relaient en fondu : la hauteur change sans changer le débit
        t.glisse = boucler(t.glisse + vitesse * (1 - hauteur));
        double d1 = t.glisse, d2 = boucler(t.glisse + FENETRE / 2.0);
        double g1 = Math.sin(Math.PI * d1 / FENETRE);
        g1 *= g1;
        double x = g1 * t.lire(t.lecture - d1 - 16) + (1 - g1) * t.lire(t.lecture - d2 - 16);
        if (r.double_ > 0) {
            // la voix dédoublée : une seconde voix un poil plus aiguë et 25 ms en retard
            double hauteur2 = hauteur * 1.03;
            t.glisse2 = boucler(t.glisse2 + vitesse * (1 - hauteur2));
            double e1 = t.glisse2, e2 = boucler(t.glisse2 + FENETRE / 2.0);
            double h1 = Math.sin(Math.PI * e1 / FENETRE);
            h1 *= h1;
            double retard = 16 + 0.025 * F;
            double y = h1 * t.lire(t.lecture - e1 - retard) + (1 - h1) * t.lire(t.lecture - e2 - retard);
            x = x * (1 - 0.35 * r.double_) + y * 0.75 * r.double_;
        }
        // glouglou : le volume tremble avec le vibrato
        x *= 1 - 0.35 * Math.min(1, r.gargouille) * (0.5 + 0.5 * Math.sin(t.phGargouille + 1));

        // 4. la bouche pleine : un filtre « wa-wa » qui s'ouvre et se ferme
        if (r.wah > 0) {
            t.phWah += 2 * Math.PI * 3.5 * (0.6 + 0.8 * t.wahHasard.suivant()) / F;
            double fc = 300 * Math.pow(2, 2.7 * (0.5 + 0.5 * Math.sin(t.phWah)));
            double f = 2 * Math.sin(Math.PI * fc / F);
            double haut = x - t.filtreBas - 0.3 * t.filtreBande;
            t.filtreBande += f * haut;
            t.filtreBas += f * t.filtreBande;
            double wahwah = t.filtreBande * 0.9 + t.filtreBas * 0.25;
            x = x * (1 - r.wah) + wahwah * r.wah * 1.3;
        }
        // 5. pâteux : on étouffe les aigus
        t.sourd += (1 - 0.8 * r.pateux) * (x - t.sourd);
        // 6. le volume qui vacille, et on arrondit les crêtes (jamais de saturation)
        double vol = 1 - r.vacille * (0.5 + 0.5 * Math.sin(t.phVolume));
        t.phVolume += 2 * Math.PI * 0.8 / F;
        return Math.tanh(t.sourd * vol * volEvenement * grince * t.fondu * 1.4) * 0.92;
    }

    private static double boucler(double g) {
        while (g >= FENETRE) {
            g -= FENETRE;
        }
        while (g < 0) {
            g += FENETRE;
        }
        return g;
    }

    /** Dégrisé ou déconnecté : on oublie ses filtres (et on ferme ses codecs). */
    static void oublier(UUID joueur) {
        Traitement t = TRAITEMENTS.remove(joueur);
        if (t != null) {
            t.decodeur.close();
            t.encodeur.close();
        }
    }

    private VoixBourree() {
    }
}
