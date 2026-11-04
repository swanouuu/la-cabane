package fr.cabane.telephone;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.neoforged.fml.ModList;

/** Accès à Simple Voice Chat sans planter si le mod est absent. */
public final class VoiceBridge {
    static boolean isConnected(UUID player) {
        return ModList.get().isLoaded("voicechat") && CabaneVoicePlugin.isConnected(player);
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

    private VoiceBridge() {
    }
}
