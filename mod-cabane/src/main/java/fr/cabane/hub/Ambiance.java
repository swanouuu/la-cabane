package fr.cabane.hub;

import fr.cabane.Cabane;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.network.protocol.game.ClientboundStopSoundPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;

/**
 * Les PNJ qui parlent tout seuls : tant que quelqu'un est près d'un PNJ portant le tag « ambiance_<nom> » (Vegeta) ou
 * « approche_<nom> » (Eren et son « Tatakae »), il lâche une de ses répliques toutes les 10 à 15 secondes (le son
 * cabane:ambiance.<nom>, tirée au hasard parmi les fichiers voix/ambiance/<nom>-N.ogg). La première part dès qu'on arrive.
 * Les sons viennent du pack de ressources « Beauvais - voix » (outils/voix-privees.mjs) : sans lui, on n'entend rien.
 */
public final class Ambiance {
    private static final String AMBIANCE = "ambiance_";
    private static final String APPROCHE = "approche_";
    private static final double PORTEE_AMBIANCE = 16;
    private static final double PORTEE_APPROCHE = 9;
    private static final float VOLUME_AMBIANCE = 0.55F;
    private static final float VOLUME_APPROCHE = 1F;
    /** Entre deux répliques d'un même PNJ : de 10 à 15 secondes. */
    private static final int ATTENTE_MIN = 10 * 20;
    private static final int ATTENTE_HASARD = 5 * 20 + 1;
    /** Assez loin pour couper la réplique d'avant chez tous ceux qui pouvaient l'entendre. */
    private static final double PORTEE_COUPURE = 48;
    private static final Map<UUID, Long> PROCHAINE = new HashMap<>();

    public static void tick(MinecraftServer server) {
        if (server.getTickCount() % 20 != 0) {
            return;
        }
        for (ServerLevel level : server.getAllLevels()) {
            long maintenant = level.getGameTime();
            Map<UUID, Entity> vus = new HashMap<>();
            for (ServerPlayer joueur : level.players()) {
                if (joueur.isSpectator()) {
                    continue;
                }
                for (Entity pnj : level.getEntities(joueur, joueur.getBoundingBox().inflate(PORTEE_AMBIANCE), e -> nom(e) != null)) {
                    if (pnj.distanceTo(joueur) <= portee(pnj)) {
                        vus.put(pnj.getUUID(), pnj);
                    }
                }
            }
            for (Entity pnj : vus.values()) {
                if (maintenant >= PROCHAINE.getOrDefault(pnj.getUUID(), 0L)) {
                    parler(level, pnj);
                    PROCHAINE.put(pnj.getUUID(), maintenant + ATTENTE_MIN + level.random.nextInt(ATTENTE_HASARD));
                }
            }
        }
    }

    private static void parler(ServerLevel level, Entity pnj) {
        ResourceLocation id = Cabane.id("ambiance." + nom(pnj));
        // certaines répliques durent près de 30 s : on coupe celle d'avant, pour qu'il ne parle pas par-dessus lui-même
        ClientboundStopSoundPacket couper = new ClientboundStopSoundPacket(id, SoundSource.NEUTRAL);
        for (ServerPlayer joueur : level.players()) {
            if (joueur.distanceTo(pnj) <= PORTEE_COUPURE) {
                joueur.connection.send(couper);
            }
        }
        float volume = approche(pnj) ? VOLUME_APPROCHE : VOLUME_AMBIANCE;
        level.playSound(null, pnj.getX(), pnj.getEyeY(), pnj.getZ(), SoundEvent.createVariableRangeEvent(id), SoundSource.NEUTRAL, volume, 1F);
        Cabane.LOGGER.debug("Ambiance : {} parle", nom(pnj));
    }

    private static double portee(Entity e) {
        return approche(e) ? PORTEE_APPROCHE : PORTEE_AMBIANCE;
    }

    private static boolean approche(Entity e) {
        return e.getTags().stream().anyMatch(t -> t.startsWith(APPROCHE));
    }

    private static String nom(Entity e) {
        for (String tag : e.getTags()) {
            if (tag.startsWith(AMBIANCE)) {
                return tag.substring(AMBIANCE.length());
            }
            if (tag.startsWith(APPROCHE)) {
                return tag.substring(APPROCHE.length());
            }
        }
        return null;
    }

    private Ambiance() {
    }
}
