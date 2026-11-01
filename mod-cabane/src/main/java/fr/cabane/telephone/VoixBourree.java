package fr.cabane.telephone;

import de.maxhenkel.voicechat.api.VoicechatApi;
import de.maxhenkel.voicechat.api.opus.OpusDecoder;
import de.maxhenkel.voicechat.api.opus.OpusEncoder;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Au vocal, la voix de quelqu'un d'ivre : elle tangue (la hauteur ondule), elle est pâteuse (étouffée), elle traîne
 * (un léger écho) et le volume vacille. Plus on a bu, plus c'est fort ; quand l'effet passe, la voix redevient normale.
 * Le son arrive compressé (Opus) : on le décode, on le déforme, on le réencode, sur le fil du vocal.
 */
final class VoixBourree {
    private static final Map<UUID, Traitement> TRAITEMENTS = new ConcurrentHashMap<>();
    private static final int FREQUENCE = 48000;

    /** L'état d'un joueur : ses codecs (ils gardent une mémoire d'un paquet à l'autre) et ses filtres. */
    private static final class Traitement {
        final OpusDecoder decodeur;
        final OpusEncoder encodeur;
        final float[] retard = new float[FREQUENCE / 2];
        int ecriture;
        double phase;
        double phaseVolume;
        float passeBas;

        Traitement(VoicechatApi api) {
            decodeur = api.createDecoder();
            encodeur = api.createEncoder();
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
        float force = Math.min(1F, 0.35F + niveau * 0.11F);
        // la hauteur qui tangue : un retard qui ondule (vibrato lent et profond)
        double profondeur = (0.002 + 0.006 * force) * FREQUENCE;
        double vitesse = 2.6 - force * 1.2;
        double base = profondeur + 64;
        // l'écho pâteux, quelques dizaines de millisecondes derrière
        int echo = (int) (0.11 * FREQUENCE);
        float alpha = 0.55F - force * 0.35F;
        short[] sortie = new short[pcm.length];
        int n = t.retard.length;
        for (int i = 0; i < pcm.length; i++) {
            t.retard[t.ecriture] = pcm[i];
            t.phase += 2 * Math.PI * vitesse / FREQUENCE;
            t.phaseVolume += 2 * Math.PI * 0.7 / FREQUENCE;
            double d = base + profondeur * Math.sin(t.phase);
            double lecture = t.ecriture - d;
            while (lecture < 0) {
                lecture += n;
            }
            int a = (int) lecture;
            float frac = (float) (lecture - a);
            float x = t.retard[a % n] * (1 - frac) + t.retard[(a + 1) % n] * frac;
            float e = t.retard[((t.ecriture - echo - (int) base) % n + n) % n];
            float melange = x + e * 0.32F * force;
            t.passeBas += alpha * (melange - t.passeBas);
            float volume = (float) (1 - 0.25 * force * (0.5 + 0.5 * Math.sin(t.phaseVolume)));
            sortie[i] = (short) Math.max(-32768, Math.min(32767, t.passeBas * volume * 1.1F));
            t.ecriture = (t.ecriture + 1) % n;
        }
        return t.encodeur.encode(sortie);
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
