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
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

/**
 * L'équilibrage des combats entre joueurs (2026-11-08 : « tout fait trop mal », « aucun spell ne one-shot »). Deux règles,
 * posées ici pour que rien n'y échappe. Les chiffres de chaque pouvoir restent dans sa classe : ils gardent l'ordre des
 * niveaux, et contre les mobs rien ne change.
 * <ol>
 * <li>Tout ce qu'un joueur inflige à un autre compte {@link #ECHELLE moitié} : sorts, épées, poings, armes du cartel.</li>
 * <li>Un joueur ne perd jamais plus de {@link #PLAFOND 60 %} de sa vie à cause du même joueur en {@link #FENETRE 5 s} :
 * on ne peut plus tuer quelqu'un en pleine forme d'un seul sort, combo compris.</li>
 * </ol>
 */
@EventBusSubscriber(modid = Cabane.MODID)
public final class Equilibrage {
    private static final float ECHELLE = 0.5F;
    private static final float PLAFOND = 0.6F;
    private static final int FENETRE = 5 * 20;

    private record Paire(UUID victime, UUID attaquant) {
    }

    private record Coup(int tick, float degats) {
    }

    /** Ce que chaque joueur a enlevé à chaque autre ces 5 dernières secondes. */
    private static final Map<Paire, ArrayDeque<Coup>> RECENTS = new HashMap<>();

    /** Le joueur qui frappe un autre joueur, ou null (un mob, soi-même, /kill et le vide ne comptent pas). */
    private static ServerPlayer adversaire(LivingEntity victime, DamageSource source) {
        if (victime instanceof ServerPlayer && source.getEntity() instanceof ServerPlayer a && a != victime
                && !source.is(DamageTypeTags.BYPASSES_INVULNERABILITY)) {
            return a;
        }
        return null;
    }

    /** Après les bonus des pouvoirs (priorité basse), avant l'armure. */
    @SubscribeEvent(priority = EventPriority.LOW)
    public static void reduire(LivingIncomingDamageEvent event) {
        if (adversaire(event.getEntity(), event.getSource()) != null) {
            event.setAmount(event.getAmount() * ECHELLE);
        }
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
