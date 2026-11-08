package fr.cabane.client;

import java.util.Arrays;
import java.util.Set;
import net.minecraft.client.KeyMapping;

/**
 * Les touches des pouvoirs n'apparaissent pas dans Options → Commandes : leurs noms (Kamui, Sharingan, Rinnegan, Susano,
 * Minato…) dévoileraient les pouvoirs aux joueurs avant qu'ils les découvrent. Elles marchent toujours, avec leur touche
 * par défaut, et les panneaux des pouvoirs continuent de les afficher. Pour les remettre dans le menu : ACTIF = false.
 */
public final class TouchesCachees {
    public static final boolean ACTIF = true;

    private static final Set<String> CACHEES = Set.of("key.cabane.eveil", "key.cabane.ulti", "key.cabane.technique",
            "key.cabane.kamui", "key.cabane.meteorite");

    /** Les touches à montrer dans le menu. */
    public static KeyMapping[] filtrer(KeyMapping[] touches) {
        if (!ACTIF) {
            return touches;
        }
        return Arrays.stream(touches).filter(t -> !CACHEES.contains(t.getName())).toArray(KeyMapping[]::new);
    }

    private TouchesCachees() {
    }
}
