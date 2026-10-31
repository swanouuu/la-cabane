package fr.cabane.telephone;

import java.util.UUID;
import net.neoforged.fml.ModList;

/** Accès à Simple Voice Chat sans planter si le mod est absent. */
final class VoiceBridge {
    static boolean isConnected(UUID player) {
        return ModList.get().isLoaded("voicechat") && CabaneVoicePlugin.isConnected(player);
    }

    private VoiceBridge() {
    }
}
