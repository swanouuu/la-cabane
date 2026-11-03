package fr.cabane.telephone;

import de.maxhenkel.voicechat.api.VoicechatApi;
import de.maxhenkel.voicechat.api.opus.OpusDecoder;
import de.maxhenkel.voicechat.api.opus.OpusEncoder;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Au vocal, la voix de quelqu'un qui porte un masque ninja sur la tête (accessoire/Masques) : grave, environ 6 demi-tons
 * plus bas, et un peu sourde. Deux têtes de lecture glissent dans un retard et se relaient en fondu (le vieux truc des
 * pédales d'effet) : la voix descend sans ralentir. Le son arrive compressé (Opus) : décodé, abaissé, réencodé.
 */
final class VoixMasque {
    private static final Map<UUID, Traitement> TRAITEMENTS = new ConcurrentHashMap<>();
    private static final int FREQUENCE = 48000;
    /** La hauteur de la voix (0,72 : grave, environ 6 demi-tons plus bas). */
    private static final double HAUTEUR = 0.72;
    /** La fenêtre des deux têtes de lecture (40 ms) : plus courte, ça grésille ; plus longue, ça bégaie. */
    private static final int FENETRE = FREQUENCE / 25;

    private static final class Traitement {
        final OpusDecoder decodeur;
        final OpusEncoder encodeur;
        final float[] retard = new float[4096];
        int ecriture;
        /** Où en est la première tête de lecture, de 0 à FENETRE (la seconde est une demi-fenêtre plus loin). */
        double glisse;
        float sourd;

        Traitement(VoicechatApi api) {
            decodeur = api.createDecoder();
            encodeur = api.createEncoder();
        }
    }

    /** Abaisse un paquet de micro ; renvoie null si rien à faire (paquet vide, ou erreur). */
    static byte[] grave(VoicechatApi api, UUID joueur, byte[] opus) {
        if (opus == null || opus.length == 0) {
            return null;
        }
        Traitement t = TRAITEMENTS.computeIfAbsent(joueur, k -> new Traitement(api));
        short[] pcm = t.decodeur.decode(opus);
        if (pcm == null || pcm.length == 0) {
            return null;
        }
        int n = t.retard.length;
        short[] sortie = new short[pcm.length];
        for (int i = 0; i < pcm.length; i++) {
            t.retard[t.ecriture] = pcm[i];
            // les têtes prennent du retard (elles lisent plus lentement qu'on n'écrit) : la voix descend
            t.glisse += 1 - HAUTEUR;
            if (t.glisse >= FENETRE) {
                t.glisse -= FENETRE;
            }
            double d1 = t.glisse, d2 = (t.glisse + FENETRE / 2.0) % FENETRE;
            // chacune s'efface quand elle saute (sin² + cos² = 1 : le volume reste le même)
            double g1 = Math.sin(Math.PI * d1 / FENETRE);
            g1 *= g1;
            float x = (float) (g1 * lire(t, d1 + 16) + (1 - g1) * lire(t, d2 + 16));
            // un peu sourde : on adoucit les aigus
            t.sourd += 0.45F * (x - t.sourd);
            sortie[i] = (short) Math.max(-32768, Math.min(32767, t.sourd * 1.15F));
            t.ecriture = (t.ecriture + 1) % n;
        }
        return t.encodeur.encode(sortie);
    }

    /** L'échantillon d'il y a {@code retard} échantillons (entre deux, mélangé). */
    private static float lire(Traitement t, double retard) {
        int n = t.retard.length;
        double p = t.ecriture - retard;
        while (p < 0) {
            p += n;
        }
        int a = (int) p;
        float frac = (float) (p - a);
        return t.retard[a % n] * (1 - frac) + t.retard[(a + 1) % n] * frac;
    }

    /** Masque enlevé (ou déconnecté) : on oublie ses filtres et on ferme ses codecs. */
    static void oublier(UUID joueur) {
        Traitement t = TRAITEMENTS.remove(joueur);
        if (t != null) {
            t.decodeur.close();
            t.encodeur.close();
        }
    }

    private VoixMasque() {
    }
}
