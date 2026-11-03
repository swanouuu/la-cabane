package fr.cabane.client;

import fr.cabane.Cabane;
import fr.cabane.CabaneSons;
import fr.cabane.police.VoitureEntity;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import javax.sound.sampled.AudioFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.AudioStream;
import net.minecraft.client.sounds.SoundBufferLibrary;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import org.lwjgl.BufferUtils;

/**
 * Le moteur et la sirène de la voiture de police, synthétisés en direct (pas de fichier son) : le régime
 * du moteur suit la vitesse, la sirène fait le « pin-pon » à deux tons de la police française.
 */
@EventBusSubscriber(modid = Cabane.MODID, value = Dist.CLIENT)
public final class SonsVoiture {
    private static final Map<Integer, Son> MOTEURS = new HashMap<>();
    private static final Map<Integer, Son> SIRENES = new HashMap<>();

    @SubscribeEvent
    public static void onTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            MOTEURS.clear();
            SIRENES.clear();
            return;
        }
        for (Entity e : mc.level.entitiesForRendering()) {
            if (e instanceof VoitureEntity v && v.distanceToSqr(mc.player) < 72 * 72) {
                lancer(mc, MOTEURS, v, v.getControllingPassenger() != null, false);
                lancer(mc, SIRENES, v, v.sirene(), true);
            }
        }
        MOTEURS.values().removeIf(Son::isStopped);
        SIRENES.values().removeIf(Son::isStopped);
    }

    private static void lancer(Minecraft mc, Map<Integer, Son> sons, VoitureEntity v, boolean actif, boolean sirene) {
        Son son = sons.get(v.getId());
        if (actif && (son == null || son.isStopped() || !mc.getSoundManager().isActive(son))) {
            if (son != null) {
                mc.getSoundManager().stop(son);
            }
            son = new Son(v, sirene);
            sons.put(v.getId(), son);
            mc.getSoundManager().play(son);
        }
    }

    static final class Son extends AbstractTickableSoundInstance {
        final VoitureEntity voiture;
        final boolean sirene;
        volatile float regime;

        Son(VoitureEntity voiture, boolean sirene) {
            super(CabaneSons.VOITURE.get(), SoundSource.NEUTRAL, SoundInstance.createUnseededRandom());
            this.voiture = voiture;
            this.sirene = sirene;
            // la sirène s'entend de loin ; le moteur reste discret (il était bien trop fort au volant)
            this.volume = sirene ? 0.7F : 0.15F;
            this.pitch = 1F;
            this.attenuation = SoundInstance.Attenuation.LINEAR;
            this.x = voiture.getX();
            this.y = voiture.getY() + 1;
            this.z = voiture.getZ();
        }

        @Override
        public void tick() {
            if (voiture.isRemoved() || (sirene ? !voiture.sirene() : voiture.getControllingPassenger() == null)) {
                stop();
                return;
            }
            x = voiture.getX();
            y = voiture.getY() + 1;
            z = voiture.getZ();
            regime = voiture.regime();
        }

        @Override
        public CompletableFuture<AudioStream> getStream(SoundBufferLibrary bibliotheque, Sound son, boolean boucle) {
            return CompletableFuture.completedFuture(new Synthe(this));
        }
    }

    /** Un flux audio sans fin, fabriqué au fur et à mesure (un dixième de seconde à la fois, pour réagir vite). */
    static final class Synthe implements AudioStream {
        private static final float TAUX = 22050F;
        private static final int MORCEAU = 2205;
        private final Son son;
        private final Random hasard = new Random();
        private double phase;
        private double lissage;
        private long n;

        Synthe(Son son) {
            this.son = son;
        }

        @Override
        public AudioFormat getFormat() {
            return new AudioFormat(TAUX, 16, 1, true, false);
        }

        @Override
        public ByteBuffer read(int taille) {
            ByteBuffer buf = BufferUtils.createByteBuffer(MORCEAU * 2).order(ByteOrder.LITTLE_ENDIAN);
            for (int i = 0; i < MORCEAU; i++) {
                double t = n / (double) TAUX;
                double v;
                if (son.sirene) {
                    // deux tons, une demi-seconde chacun, timbre de trompe
                    double f = ((long) (t / 0.55)) % 2 == 0 ? 435 : 580;
                    phase += f / TAUX;
                    double a = 2 * Math.PI * phase;
                    v = 0.55 * Math.sin(a) + 0.25 * Math.sin(3 * a) + 0.12 * Math.sin(5 * a) + 0.06 * Math.sin(7 * a);
                    v *= 0.6;
                } else {
                    // le moteur : un ronflement en dents de scie, plus aigu quand on accélère
                    lissage += (son.regime - lissage) * 0.0004;
                    double f = 32 + 85 * lissage;
                    phase += f / TAUX;
                    double scie = 2 * (phase - Math.floor(phase)) - 1;
                    double explosions = 0.65 + 0.35 * Math.sin(2 * Math.PI * phase * 2);
                    v = 0.5 * scie * explosions + 0.2 * Math.sin(2 * Math.PI * phase * 0.5) + 0.06 * (hasard.nextDouble() * 2 - 1);
                    v *= 0.45 + 0.35 * lissage;
                }
                buf.putShort((short) Math.max(-32767, Math.min(32767, v * 26000)));
                n++;
            }
            buf.flip();
            return buf;
        }

        @Override
        public void close() {
        }
    }

    private SonsVoiture() {
    }
}
