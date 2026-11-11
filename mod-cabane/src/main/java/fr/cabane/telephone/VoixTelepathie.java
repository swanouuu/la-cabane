package fr.cabane.telephone;

import de.maxhenkel.voicechat.api.VoicechatConnection;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import de.maxhenkel.voicechat.api.audiochannel.StaticAudioChannel;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Le côté vocal de /telepatie (Telepathie) : le micro de l'hôte part, rendu grave comme sous un masque ninja, au seul
 * joueur visé, par un canal « statique » (pas de distance, pas de dimension). Le canal a son propre identifiant au
 * hasard : chez le joueur, rien ne dit que c'est l'hôte qui parle (pas d'icône au-dessus de sa tête, pas de réglage de
 * volume à son nom).
 */
final class VoixTelepathie {
    /** Un canal par hôte, gardé tant que la télépathie dure. */
    private static final Map<UUID, StaticAudioChannel> CANAUX = new ConcurrentHashMap<>();
    /** La voix grave a son propre traitement (le masque porté pour de vrai garde le sien). */
    private static final long CLE = 0x7E1E9A7E1E9A7E1EL;

    /**
     * Envoie le paquet de l'hôte dans la tête de son joueur ; renvoie false s'il n'est pas en télépathie (le vocal suit
     * alors son cours normal). {@code dejaGrave} : l'hôte porte déjà un masque, la voix est déjà abaissée.
     */
    static boolean envoyer(VoicechatServerApi api, VoicechatConnection hote, byte[] opus, boolean dejaGrave) {
        UUID id = hote.getPlayer().getUuid();
        UUID cible = Telepathie.cible(id);
        if (cible == null) {
            return false;
        }
        if (opus == null || opus.length == 0) {
            // l'hôte a fini sa phrase : le joueur clôt le son tout de suite au lieu d'attendre
            StaticAudioChannel canal = CANAUX.get(id);
            if (canal != null) {
                canal.flush();
            }
            return true;
        }
        byte[] voix = dejaGrave ? opus : VoixMasque.grave(api, cle(id), opus);
        if (voix == null) {
            return true;
        }
        StaticAudioChannel canal = CANAUX.computeIfAbsent(id, k -> {
            StaticAudioChannel c = api.createStaticAudioChannel(UUID.randomUUID());
            if (c != null) {
                // même si le joueur est dans un groupe isolé, la voix lui parvient
                c.setBypassGroupIsolation(true);
            }
            return c;
        });
        if (canal == null) {
            return true;
        }
        // les connexions changent quand on se reconnecte : on vise à chaque paquet la connexion du moment
        canal.clearTargets();
        VoicechatConnection joueur = api.getConnectionOf(cible);
        if (joueur != null) {
            canal.addTarget(joueur);
        }
        // /vocal echo : l'hôte s'entend tel que le joueur l'entend (pour essayer tout seul)
        if (VoiceBridge.echo(id) && !id.equals(cible)) {
            canal.addTarget(hote);
        }
        canal.send(voix);
        return true;
    }

    static void oublier(UUID hote) {
        StaticAudioChannel canal = CANAUX.remove(hote);
        if (canal != null) {
            canal.flush();
            canal.clearTargets();
        }
        VoixMasque.oublier(cle(hote));
    }

    private static UUID cle(UUID hote) {
        return new UUID(hote.getMostSignificantBits() ^ CLE, hote.getLeastSignificantBits() ^ CLE);
    }

    private VoixTelepathie() {
    }
}
