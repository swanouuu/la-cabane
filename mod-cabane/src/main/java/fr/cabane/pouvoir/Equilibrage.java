package fr.cabane.pouvoir;

import fr.cabane.Cabane;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.damagesource.DamageContainer;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

/**
 * L'équilibrage des combats (2026-11-08 : « aucun spell ne one-shot » ; 2026-11-12, avec les armures de l'arène : « on fait
 * pas de dégâts du tout, augmente bien »). Posé ici pour que rien n'y échappe : les chiffres de chaque pouvoir restent dans
 * sa classe et gardent l'ordre des niveaux.
 * <ol>
 * <li>Tout ce qu'un joueur inflige (sorts, ults, épées, poings, armes du cartel), à un joueur comme à un mob, est
 * {@link #multiplicateur multiplié} : ×1,6 pour un petit coup, jusqu'à ×2,4 pour les gros (les ults).</li>
 * <li>Ces coups traversent la moitié de l'armure et de ses enchantements de protection.</li>
 * <li>Un joueur ne perd jamais plus de {@link #PLAFOND 80 %} de sa vie à cause du même joueur en {@link #FENETRE 5 s} :
 * toujours pas de one-shot d'un joueur en pleine forme, mais deux gros sorts suffisent.</li>
 * </ol>
 */
@EventBusSubscriber(modid = Cabane.MODID)
public final class Equilibrage {
    /** Le multiplicateur d'un petit coup, celui d'un gros coup, et entre quels dégâts on passe de l'un à l'autre. */
    private static final float PETIT = 1.6F, GROS = 2.4F, DEBUT_GROS = 6, PLEIN_GROS = 20;
    /** La part de l'armure (et de ses enchantements) qui protège encore d'un coup de joueur. */
    private static final float ARMURE = 0.5F;
    private static final float PLAFOND = 0.8F;
    private static final int FENETRE = 5 * 20;

    private record Paire(UUID victime, UUID attaquant) {
    }

    private record Coup(int tick, float degats) {
    }

    /** Ce que chaque joueur a enlevé à chaque autre ces 5 dernières secondes. */
    private static final Map<Paire, ArrayDeque<Coup>> RECENTS = new HashMap<>();

    /** Le joueur qui frappe, quelle que soit la cible, ou null (un mob, soi-même, /kill et le vide ne comptent pas). */
    private static ServerPlayer frappeur(LivingEntity victime, DamageSource source) {
        if (source.getEntity() instanceof ServerPlayer a && a != victime && !source.is(DamageTypeTags.BYPASSES_INVULNERABILITY)) {
            return a;
        }
        return null;
    }

    /** Le joueur qui frappe un autre joueur, ou null. */
    private static ServerPlayer adversaire(LivingEntity victime, DamageSource source) {
        return victime instanceof ServerPlayer ? frappeur(victime, source) : null;
    }

    /** ×1,6 jusqu'à 6 dégâts, puis de plus en plus fort jusqu'à ×2,4 à partir de 20 (les ults). */
    static float multiplicateur(float degats) {
        float k = Math.max(0, Math.min(1, (degats - DEBUT_GROS) / (PLEIN_GROS - DEBUT_GROS)));
        return PETIT + (GROS - PETIT) * k;
    }

    /** Après les bonus des pouvoirs (priorité basse), avant l'armure. */
    @SubscribeEvent(priority = EventPriority.LOW)
    public static void renforcer(LivingIncomingDamageEvent event) {
        if (frappeur(event.getEntity(), event.getSource()) == null) {
            return;
        }
        event.setAmount(event.getAmount() * multiplicateur(event.getAmount()));
        event.addReductionModifier(DamageContainer.Reduction.ARMOR, (c, r) -> r * ARMURE);
        event.addReductionModifier(DamageContainer.Reduction.ENCHANTMENTS, (c, r) -> r * ARMURE);
    }

    /** Tout à la fin : ce qui va vraiment être retiré de sa vie, armure comprise. */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void plafonner(LivingDamageEvent.Pre event) {
        ServerPlayer a = adversaire(event.getEntity(), event.getContainer().getSource());
        if (a == null) {
            return;
        }
        int maintenant = a.server.getTickCount();
        ArrayDeque<Coup> coups = RECENTS.computeIfAbsent(new Paire(event.getEntity().getUUID(), a.getUUID()), k -> new ArrayDeque<>());
        while (!coups.isEmpty() && maintenant - coups.peekFirst().tick() >= FENETRE) {
            coups.pollFirst();
        }
        float deja = 0;
        for (Coup c : coups) {
            deja += c.degats();
        }
        float degats = Math.min(event.getNewDamage(), Math.max(0, PLAFOND * event.getEntity().getMaxHealth() - deja));
        event.setNewDamage(degats);
        if (degats > 0) {
            coups.addLast(new Coup(maintenant, degats));
        }
    }

    @SubscribeEvent
    public static void deconnexion(PlayerEvent.PlayerLoggedOutEvent event) {
        UUID u = event.getEntity().getUUID();
        RECENTS.keySet().removeIf(p -> p.victime().equals(u) || p.attaquant().equals(u));
    }

    @SubscribeEvent
    public static void arret(ServerStoppedEvent event) {
        RECENTS.clear();
    }

    private Equilibrage() {
    }
}
