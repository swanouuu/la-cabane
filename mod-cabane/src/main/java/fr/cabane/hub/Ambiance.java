package fr.cabane.hub;

import fr.cabane.Cabane;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
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
 *
 * <p>Un PNJ portant le tag « approche_<nom> » réagit, lui, dès qu'on arrive près de lui : sa réplique part tout de suite
 * (le même son cabane:ambiance.<nom>), puis il se repose un peu avant de recommencer. Eren et son « Tatakae ».
 */
public final class Ambiance {
    private static final String PREFIXE = "ambiance_";
    private static final double PORTEE = 16;
    /** Entre deux répliques d'un même PNJ : de 45 à 90 secondes (certaines durent près de 30 s). */
    private static final int ATTENTE_MIN = 45 * 20;
    private static final int ATTENTE_HASARD = 45 * 20;
    private static final Map<UUID, Long> PROCHAINE = new HashMap<>();
    private static final String APPROCHE = "approche_";
    private static final double PORTEE_APPROCHE = 9;
    /** Après sa réplique, il attend 25 secondes avant de réagir à quelqu'un d'autre. */
    private static final int REPOS_APPROCHE = 25 * 20;
    /** Pour chaque PNJ « approche_ » : les joueurs déjà près de lui (il ne réagit qu'à ceux qui arrivent). */
    private static final Map<UUID, Set<UUID>> PROCHES = new HashMap<>();
    private static final Map<UUID, Long> REPOS = new HashMap<>();

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
                        level.playSound(null, pnj.getX(), pnj.getEyeY(), pnj.getZ(), son, SoundSource.NEUTRAL, 0.55F, 1F);
                        Cabane.LOGGER.debug("Ambiance : {} parle", nom(pnj));
                        PROCHAINE.put(pnj.getUUID(), maintenant + ATTENTE_MIN + level.random.nextInt(ATTENTE_HASARD));
                    }
                }
            }
            approches(level, maintenant);
        }
    }

    /** Les PNJ qui réagissent à l'arrivée de quelqu'un : un joueur qui vient d'arriver près d'eux, et la réplique part. */
    private static void approches(ServerLevel level, long maintenant) {
        Map<UUID, Entity> vus = new HashMap<>();
        Map<UUID, Set<UUID>> presents = new HashMap<>();
        for (ServerPlayer joueur : level.players()) {
            if (joueur.isSpectator()) {
                continue;
            }
            for (Entity pnj : level.getEntities(joueur, joueur.getBoundingBox().inflate(PORTEE_APPROCHE), e -> approche(e) != null)) {
                if (pnj.distanceTo(joueur) <= PORTEE_APPROCHE) {
                    vus.put(pnj.getUUID(), pnj);
                    presents.computeIfAbsent(pnj.getUUID(), k -> new HashSet<>()).add(joueur.getUUID());
                }
            }
        }
        for (Map.Entry<UUID, Entity> e : vus.entrySet()) {
            Set<UUID> avant = PROCHES.getOrDefault(e.getKey(), Set.of());
            boolean arrivee = presents.get(e.getKey()).stream().anyMatch(j -> !avant.contains(j));
            if (arrivee && maintenant >= REPOS.getOrDefault(e.getKey(), 0L)) {
                Entity pnj = e.getValue();
                SoundEvent son = SoundEvent.createVariableRangeEvent(Cabane.id("ambiance." + approche(pnj)));
                level.playSound(null, pnj.getX(), pnj.getEyeY(), pnj.getZ(), son, SoundSource.NEUTRAL, 1F, 1F);
                Cabane.LOGGER.debug("Ambiance : {} réagit à une arrivée", approche(pnj));
                REPOS.put(e.getKey(), maintenant + REPOS_APPROCHE);
            }
        }
        // ceux qui n'ont plus personne autour : on les oublie (la prochaine arrivée les fera parler)
        PROCHES.keySet().removeIf(u -> level.getEntity(u) != null && !presents.containsKey(u));
        PROCHES.putAll(presents);
    }

    private static String approche(Entity e) {
        for (String tag : e.getTags()) {
            if (tag.startsWith(APPROCHE)) {
                return tag.substring(APPROCHE.length());
            }
        }
        return null;
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
