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
import net.neoforged.neoforge.event.entity.living.ArmorHurtEvent;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

/**
 * L'équilibrage des combats (2026-11-08 : « aucun spell ne one-shot » ; 2026-11-12, avec les armures de l'arène : « on fait
 * pas de dégâts du tout, augmente bien »). Posé ici pour que rien n'y échappe : les chiffres de chaque pouvoir restent dans
 * sa classe et gardent l'ordre des niveaux.
 * <ol>
 * <li>Tout ce qu'un joueur inflige (sorts, ults, épées, poings, armes du cartel), à un joueur comme à un mob, est
 * {@link #multiplicateur multiplié} : ×1,6 pour un petit coup, jusqu'à ×2 pour les gros (les ults ; ×2,4 avant le
 * 2026-11-16).</li>
 * <li>Ces coups traversent la moitié de l'armure et de ses enchantements de protection.</li>
 * <li>Un seul coup n'enlève jamais plus de {@link #PAR_COUP 30 %} de la vie d'un joueur, et un joueur ne perd jamais plus de
 * {@link #PLAFOND 50 %} de sa vie à cause du même joueur en {@link #FENETRE 2 s} (2026-11-16 : « quasiment one shot avec
 * une seule attaque » sur une Netherite P4, c'était 80 %). La fenêtre était de 5 s jusqu'au 2026-11-18 (« on peut pas se
 * tuer ») : 2 dégâts/s au plus, autant que la régénération d'une barre de faim pleine. Maintenant 5 dégâts/s au plus, un
 * joueur en pleine forme tient au moins 4 s. Les ults ont des plafonds plus hauts (voir {@link #ULT_PAR_COUP}).</li>
 * <li>Entre joueurs, un coup (arme ou pouvoir) n'use l'armure qu'au {@link #USURE quart} de l'usure normale (2026-11-16 :
 * « sinon on détruit trop facilement nos armures ») ; contre les monstres, rien ne change.</li>
 * </ol>
 */
@EventBusSubscriber(modid = Cabane.MODID)
public final class Equilibrage {
    /** Le multiplicateur d'un petit coup, celui d'un gros coup, et entre quels dégâts on passe de l'un à l'autre. */
    private static final float PETIT = 1.6F, GROS = 2.0F, DEBUT_GROS = 6, PLEIN_GROS = 20;
    /** La part de l'armure (et de ses enchantements) qui protège encore d'un coup de joueur. */
    private static final float ARMURE = 0.5F;
    /** La part de sa vie qu'un joueur peut perdre d'un seul coup, puis à cause du même joueur en 2 s. */
    private static final float PAR_COUP = 0.3F, PLAFOND = 0.5F;
    /**
     * Les gros coups (les ults) choquent (2026-11-18 : « les ults faut que ça fasse mal, que ça choque ») : à partir de
     * {@link #ULT_DEBUT} dégâts d'origine, les deux plafonds montent, jusqu'à {@link #ULT_PAR_COUP 75 %} d'un coup et
     * {@link #ULT_PLAFOND 85 %} en 2 s à {@link #ULT_PLEIN} et plus. Une ult seule ne tue donc pas quelqu'un en pleine vie,
     * mais achève un blessé.
     */
    private static final float ULT_DEBUT = 8, ULT_PLEIN = 20, ULT_PAR_COUP = 0.75F, ULT_PLAFOND = 0.85F;
    /** La part de l'usure normale que prend l'armure d'un joueur frappé par un autre joueur. */
    public static final float USURE = 0.25F;
    private static final int FENETRE = 2 * 20;

    private record Paire(UUID victime, UUID attaquant) {
    }

    private record Coup(int tick, float degats) {
    }

    /** Ce que chaque joueur a enlevé à chaque autre ces 2 dernières secondes. */
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

    /** ×1,6 jusqu'à 6 dégâts, puis de plus en plus fort jusqu'à ×2 à partir de 20 (les ults). */
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

    private record Melee(UUID cible, int tick) {
    }

    /** Le dernier coup d'épée/de poing ordinaire de chaque joueur (le clic gauche de Minecraft). */
    private static final Map<UUID, Melee> MELEE = new HashMap<>();

    @SubscribeEvent
    public static void cliqueGauche(AttackEntityEvent event) {
        if (event.getEntity() instanceof ServerPlayer a) {
            MELEE.put(a.getUUID(), new Melee(event.getTarget().getUUID(), a.server.getTickCount()));
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
        float max = event.getEntity().getMaxHealth();
        // un coup d'épée ou de poing ordinaire (même critique) garde les petits plafonds : seuls les pouvoirs choquent
        Melee m = MELEE.get(a.getUUID());
        boolean melee = m != null && m.tick() == maintenant && m.cible().equals(event.getEntity().getUUID());
        float k = melee ? 0 : Math.max(0, Math.min(1, (event.getContainer().getOriginalDamage() - ULT_DEBUT) / (ULT_PLEIN - ULT_DEBUT)));
        float parCoup = PAR_COUP + (ULT_PAR_COUP - PAR_COUP) * k, plafond = PLAFOND + (ULT_PLAFOND - PLAFOND) * k;
        float degats = Math.min(Math.min(event.getNewDamage(), parCoup * max), Math.max(0, plafond * max - deja));
        event.setNewDamage(degats);
        if (degats > 0) {
            coups.addLast(new Coup(maintenant, degats));
        }
    }

    /** L'armure d'un joueur frappé par un joueur s'use quatre fois moins (l'arrondi est tiré au sort : 1 point d'usure = 1 chance sur 4). */
    @SubscribeEvent
    public static void menager(ArmorHurtEvent event) {
        if (adversaire(event.getEntity(), event.getDamageSource()) == null) {
            return;
        }
        var hasard = event.getEntity().getRandom();
        for (ArmorHurtEvent.ArmorEntry piece : event.getArmorMap().values()) {
            float usure = piece.newDamage * USURE;
            int entier = (int) usure;
            piece.newDamage = entier + (hasard.nextFloat() < usure - entier ? 1 : 0);
        }
    }

    @SubscribeEvent
    public static void deconnexion(PlayerEvent.PlayerLoggedOutEvent event) {
        UUID u = event.getEntity().getUUID();
        RECENTS.keySet().removeIf(p -> p.victime().equals(u) || p.attaquant().equals(u));
        MELEE.remove(u);
    }

    @SubscribeEvent
    public static void arret(ServerStoppedEvent event) {
        RECENTS.clear();
        MELEE.clear();
    }

    private Equilibrage() {
    }
}
