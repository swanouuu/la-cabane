package fr.cabane.telephone;

import de.maxhenkel.voicechat.api.ForgeVoicechatPlugin;
import de.maxhenkel.voicechat.api.VoicechatConnection;
import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import de.maxhenkel.voicechat.api.events.EventRegistration;
import de.maxhenkel.voicechat.api.events.MicrophonePacketEvent;
import de.maxhenkel.voicechat.api.events.VoicechatServerStartedEvent;
import fr.cabane.Cabane;
import java.util.UUID;

/**
 * Branche le téléphone sur Simple Voice Chat : pendant un appel, chaque paquet micro
 * est aussi envoyé à l'autre joueur, sans limite de distance.
 */
@ForgeVoicechatPlugin
public class CabaneVoicePlugin implements VoicechatPlugin {
    static volatile VoicechatServerApi serverApi;

    @Override
    public String getPluginId() {
        return Cabane.MODID;
    }

    @Override
    public void registerEvents(EventRegistration registration) {
        registration.registerEvent(VoicechatServerStartedEvent.class, event -> serverApi = event.getVoicechat());
        registration.registerEvent(MicrophonePacketEvent.class, this::onMicrophone);
    }

    private void onMicrophone(MicrophonePacketEvent event) {
        VoicechatConnection sender = event.getSenderConnection();
        if (sender == null) {
            return;
        }
        UUID id = sender.getPlayer().getUuid();
        // caché dans le Kamui : personne ne l'entend (ni de près, ni au téléphone)
        if (VoiceBridge.muet(id)) {
            event.cancel();
            return;
        }
        // bourré : la voix tangue et devient pâteuse, pour ceux qui l'entendent de près comme au téléphone
        int ivresse = VoixBourree.niveau(id);
        if (ivresse >= 0) {
            byte[] deformee = VoixBourree.deformer(event.getVoicechat(), id, event.getPacket().getOpusEncodedData(), ivresse);
            if (deformee != null) {
                event.getPacket().setOpusEncodedData(deformee);
            }
        }
        // un masque ninja sur la tête : la voix devient grave (à la ceinture, rien ne change)
        if (fr.cabane.accessoire.Masques.surLaTete(id)) {
            byte[] grave = VoixMasque.grave(event.getVoicechat(), id, event.getPacket().getOpusEncodedData());
            if (grave != null) {
                event.getPacket().setOpusEncodedData(grave);
            }
        } else {
            VoixMasque.oublier(id);
        }
        UUID partner = PhoneCalls.activePartner(id);
        if (partner == null) {
            return;
        }
        VoicechatServerApi api = event.getVoicechat();
        VoicechatConnection receiver = api.getConnectionOf(partner);
        if (receiver != null) {
            api.sendStaticSoundPacketTo(receiver, event.getPacket().toStaticSoundPacket());
        }
    }

    static boolean isConnected(UUID player) {
        VoicechatServerApi api = serverApi;
        if (api == null) {
            return false;
        }
        VoicechatConnection connection = api.getConnectionOf(player);
        return connection != null && connection.isConnected() && !connection.isDisabled();
    }
}
