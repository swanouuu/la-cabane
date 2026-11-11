package fr.cabane.telephone;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.neoforged.fml.ModList;

/** Accès à Simple Voice Chat sans planter si le mod est absent. */
public final class VoiceBridge {
    /** Ceux qui ont mis /vocal echo : ils entendent leur propre voix. */
    private static final Set<UUID> ECHO = ConcurrentHashMap.newKeySet();

    public static boolean isConnected(UUID player) {
        return ModList.get().isLoaded("voicechat") && CabaneVoicePlugin.isConnected(player);
    }

    /** Met ou enlève l'écho ; renvoie true s'il est maintenant actif. */
    public static boolean basculerEcho(UUID player) {
        if (ECHO.remove(player)) {
            return false;
        }
        ECHO.add(player);
        return true;
    }

    static boolean echo(UUID player) {
        return ECHO.contains(player);
    }

    /** Ceux qu'on n'entend plus (caché dans le Kamui) : leur micro ne part plus, eux entendent toujours les autres. */
    private static final Set<UUID> MUETS = ConcurrentHashMap.newKeySet();

    public static void muet(UUID player, boolean muet) {
        if (muet) {
            MUETS.add(player);
        } else {
            MUETS.remove(player);
        }
    }

    static boolean muet(UUID player) {
        return MUETS.contains(player);
    }

    /** Fin de /telepatie pour cet hôte : on lâche son canal et le traitement de sa voix grave. */
    static void oublierTelepathie(UUID hote) {
        if (ModList.get().isLoaded("voicechat")) {
            VoixTelepathie.oublier(hote);
        }
    }

    private VoiceBridge() {
    }
}
