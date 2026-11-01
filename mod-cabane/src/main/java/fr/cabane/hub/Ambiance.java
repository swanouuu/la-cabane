package fr.cabane.hub;

import fr.cabane.Cabane;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;

/**
 * Les PNJ qui parlent tout seuls : un PNJ portant le tag « ambiance_<nom> » lâche de temps en temps une de ses répliques
 * (le son cabane:ambiance.<nom>, tirée au hasard parmi les fichiers voix/ambiance/<nom>-N.ogg), quand quelqu'un passe à côté.
 * Les sons viennent du pack de ressources « Beauvais - voix » (outils/voix-privees.mjs) : sans lui, on n'entend rien.
 */
public final class Ambiance {
    private static final String PREFIXE = "ambiance_";
    private static final double PORTEE = 16;
    /** Entre deux répliques d'un même PNJ : de 45 à 90 secondes (certaines durent près de 30 s). */
    private static final int ATTENTE_MIN = 45 * 20;
    private static final int ATTENTE_HASARD = 45 * 20;
    private static final Map<UUID, Long> PROCHAINE = new HashMap<>();

    public static void tick(MinecraftServer server) {
        if (server.getTickCount() % 20 != 0) {
            return;
        }
        for (ServerLevel level : server.getAllLevels()) {
            long maintenant = level.getGameTime();
            for (ServerPlayer joueur : level.players()) {
                for (Entity pnj : level.getEntities(joueur, joueur.getBoundingBox().inflate(PORTEE), e -> nom(e) != null)) {
                    Long prochaine = PROCHAINE.get(pnj.getUUID());
                    if (prochaine == null) {
                        // la première fois qu'on le croise, il attend un peu avant de parler
                        PROCHAINE.put(pnj.getUUID(), maintenant + 60 + level.random.nextInt(ATTENTE_HASARD));
                    } else if (maintenant >= prochaine) {
                        SoundEvent son = SoundEvent.createVariableRangeEvent(Cabane.id("ambiance." + nom(pnj)));
                        level.playSound(null, pnj.getX(), pnj.getEyeY(), pnj.getZ(), son, SoundSource.NEUTRAL, 1.2F, 1F);
                        Cabane.LOGGER.debug("Ambiance : {} parle", nom(pnj));
                        PROCHAINE.put(pnj.getUUID(), maintenant + ATTENTE_MIN + level.random.nextInt(ATTENTE_HASARD));
                    }
                }
            }
        }
    }

    private static String nom(Entity e) {
        for (String tag : e.getTags()) {
            if (tag.startsWith(PREFIXE)) {
                return tag.substring(PREFIXE.length());
            }
        }
        return null;
    }

    private Ambiance() {
    }
}
