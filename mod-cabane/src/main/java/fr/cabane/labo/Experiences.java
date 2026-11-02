package fr.cabane.labo;

import fr.cabane.Cabane;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

/**
 * La table d'expériences : on s'y allonge, les sangles se referment, les lampes grésillent, les arcs électriques
 * claquent… et l'on se relève avec une « mutation » tirée au sort pour une minute ou deux : rétréci, géant,
 * en apesanteur, invisible, ou simplement rejeté par le traitement.
 */
public final class Experiences {
    /** La durée d'une expérience (en ticks), puis l'attente avant la suivante pour un même joueur. */
    private static final int DUREE = 140;
    private static final int RECHARGE = 20 * 45;
    private static final ResourceLocation MUTATION = Cabane.id("labo_mutation");

    /** {@code rituel} : sur l'autel de la salle des rituels (même déroulé, autre ambiance). */
    private record Seance(UUID joueur, BlockPos table, int numero, long debut, boolean rituel) {
    }

    /** Une mutation qui touche un attribut (taille, gravité, saut) : on l'enlève à la fin. */
    private record Effet(Holder<Attribute> attribut, double valeur) {
    }

    private record Mutation(String nom, String phrase, int secondes, List<Effet> attributs, List<MobEffectInstance> effets) {
    }

    private static final Map<UUID, Seance> SEANCES = new HashMap<>();
    private static final Map<UUID, Long> RECHARGES = new HashMap<>();
    /** Les mutations d'attributs en cours : le joueur, et le tick du serveur où elles s'arrêtent. */
    private static final Map<UUID, Long> MUTANTS = new HashMap<>();
    /** Le dernier sujet et son résultat, pour les écrans de la salle de contrôle. */
    static String dernierSujet = "";
    static String dernierResultat = "";

    private static List<Mutation> mutations() {
        return List.of(
                new Mutation("MINIATURISATION", "Tout est devenu immense… ou alors c'est toi.", 90,
                        List.of(new Effet(Attributes.SCALE, -0.65), new Effet(Attributes.STEP_HEIGHT, -0.4)), List.of()),
                new Mutation("GIGANTISME", "Tes os craquent. Tu grandis. Encore.", 60,
                        List.of(new Effet(Attributes.SCALE, 1.0), new Effet(Attributes.STEP_HEIGHT, 0.6)), List.of()),
                new Mutation("APESANTEUR", "Tu ne pèses presque plus rien.", 60,
                        List.of(new Effet(Attributes.GRAVITY, -0.8), new Effet(Attributes.SAFE_FALL_DISTANCE, 40)), List.of()),
                new Mutation("JAMBES DE SAUTERELLE", "Tes jambes ne t'appartiennent plus.", 60,
                        List.of(new Effet(Attributes.JUMP_STRENGTH, 1.2), new Effet(Attributes.SAFE_FALL_DISTANCE, 20)),
                        List.of(new MobEffectInstance(MobEffects.MOVEMENT_SPEED, 20 * 60, 1))),
                new Mutation("ŒIL DU SUJET", "Tu vois dans le noir… et le noir te voit.", 120, List.of(),
                        List.of(new MobEffectInstance(MobEffects.NIGHT_VISION, 20 * 120, 0), new MobEffectInstance(MobEffects.GLOWING, 20 * 120, 0))),
                new Mutation("PEAU D'ACIER", "Ta peau a durci. Elle ne sent plus rien.", 90, List.of(),
                        List.of(new MobEffectInstance(MobEffects.DAMAGE_RESISTANCE, 20 * 90, 1), new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 20 * 90, 0))),
                new Mutation("TRANSPARENCE", "On voit à travers toi. Enfin, presque.", 60, List.of(),
                        List.of(new MobEffectInstance(MobEffects.INVISIBILITY, 20 * 60, 0))),
                new Mutation("SURCHARGE", "Ton cœur bat à 220. Tu te sens invincible.", 60, List.of(),
                        List.of(new MobEffectInstance(MobEffects.DAMAGE_BOOST, 20 * 60, 1), new MobEffectInstance(MobEffects.MOVEMENT_SPEED, 20 * 60, 1),
                                new MobEffectInstance(MobEffects.HUNGER, 20 * 60, 1))),
                new Mutation("LÉVITATION INSTABLE", "Quelque chose t'aspire vers le plafond.", 30, List.of(),
                        List.of(new MobEffectInstance(MobEffects.LEVITATION, 20 * 4, 1), new MobEffectInstance(MobEffects.SLOW_FALLING, 20 * 30, 0))),
                new Mutation("REJET", "Ton corps refuse le traitement. Échec.", 30, List.of(),
                        List.of(new MobEffectInstance(MobEffects.POISON, 20 * 8, 0), new MobEffectInstance(MobEffects.CONFUSION, 20 * 15, 0),
                                new MobEffectInstance(MobEffects.WEAKNESS, 20 * 60, 0))));
    }

    /** Clic droit sur une table : on s'allonge, si elle est libre. */
    static void allonger(ServerPlayer joueur, ServerLevel level, BlockPos tete) {
        allonger(joueur, level, tete, false);
    }

    /** {@code deLoin} : pour la commande de test, sans vérifier que le joueur est à côté de la table. */
    static void allonger(ServerPlayer joueur, ServerLevel level, BlockPos tete, boolean deLoin) {
        if (SEANCES.containsKey(joueur.getUUID()) || joueur.isSleeping()) {
            return;
        }
        long maintenant = level.getServer().getTickCount();
        Long recharge = RECHARGES.get(joueur.getUUID());
        if (recharge != null && recharge > maintenant) {
            joueur.displayClientMessage(Component.literal("Ton corps n'est pas prêt pour une autre expérience (" + (recharge - maintenant) / 20 + " s).")
                    .withStyle(ChatFormatting.DARK_RED), true);
            return;
        }
        boolean occupee = SEANCES.values().stream().anyMatch(s -> s.table().equals(tete))
                || !level.getEntitiesOfClass(LivingEntity.class, new AABB(tete).inflate(1.2), e -> e.isSleeping()).isEmpty();
        if (occupee) {
            joueur.displayClientMessage(Component.literal("Quelqu'un est déjà allongé là.").withStyle(ChatFormatting.GRAY), true);
            return;
        }
        if (!deLoin && joueur.distanceToSqr(Vec3.atCenterOf(tete)) > 36) {
            return;
        }
        joueur.stopRiding();
        joueur.startSleeping(tete);
        int numero = LaboData.get(level.getServer()).experienceSuivante();
        boolean rituel = level.getBlockState(tete).is(Labo.AUTEL.get());
        SEANCES.put(joueur.getUUID(), new Seance(joueur.getUUID(), tete, numero, maintenant, rituel));
        if (rituel) {
            level.playSound(null, tete, SoundEvents.SOUL_ESCAPE.value(), SoundSource.BLOCKS, 1.5F, 0.5F);
            level.playSound(null, tete, SoundEvents.WARDEN_HEARTBEAT, SoundSource.BLOCKS, 1F, 0.7F);
            joueur.displayClientMessage(Component.literal("La pierre est froide. Le sceau s'éveille sous toi…").withStyle(ChatFormatting.DARK_RED), true);
            return;
        }
        dernierSujet = joueur.getGameProfile().getName();
        dernierResultat = "EN COURS…";
        level.playSound(null, tete, SoundEvents.IRON_DOOR_CLOSE, SoundSource.BLOCKS, 1F, 0.6F);
        level.playSound(null, tete, SoundEvents.CHAIN_PLACE, SoundSource.BLOCKS, 1F, 0.7F);
        joueur.displayClientMessage(Component.literal("Les sangles se referment sur tes poignets…").withStyle(ChatFormatting.DARK_RED), true);
    }

    /** Est-ce que quelqu'un est sur la table en ce moment ? (pour les écrans) */
    static boolean enCours() {
        return SEANCES.values().stream().anyMatch(s -> !s.rituel());
    }

    /** Un rituel est-il en cours sur l'autel ? (le sceau s'emballe) */
    static boolean rituelEnCours() {
        return SEANCES.values().stream().anyMatch(Seance::rituel);
    }

    static void tick(ServerLevel labo) {
        MinecraftServer server = labo.getServer();
        long maintenant = server.getTickCount();
        Iterator<Seance> it = SEANCES.values().iterator();
        while (it.hasNext()) {
            Seance s = it.next();
            ServerPlayer joueur = server.getPlayerList().getPlayer(s.joueur());
            if (joueur == null || joueur.level() != labo) {
                it.remove();
                continue;
            }
            int t = (int) (maintenant - s.debut());
            if (!joueur.isSleeping()) {
                it.remove();
                if (s.rituel()) {
                    joueur.displayClientMessage(Component.literal("Tu quittes l'autel. Le sceau s'éteint… pour l'instant.").withStyle(ChatFormatting.GRAY), true);
                } else {
                    dernierResultat = "SUJET ÉCHAPPÉ";
                    joueur.displayClientMessage(Component.literal("Tu t'es arraché aux sangles. L'expérience est annulée.").withStyle(ChatFormatting.GRAY), true);
                }
                continue;
            }
            deroule(labo, joueur, s, t);
            if (t >= DUREE) {
                it.remove();
                finir(labo, joueur, s);
            }
        }
        tickHors(server);
    }

    /** Ce qui se passe pendant l'expérience, seconde après seconde. */
    private static void deroule(ServerLevel level, ServerPlayer joueur, Seance s, int t) {
        if (s.rituel()) {
            rituel(level, joueur, s, t);
            return;
        }
        Vec3 p = joueur.position().add(0, 0.4, 0);
        BlockPos table = s.table();
        if (t == 20) {
            level.playSound(null, table, SoundEvents.BEACON_ACTIVATE, SoundSource.BLOCKS, 1.2F, 0.5F);
            joueur.displayClientMessage(Component.literal("EXPÉRIENCE N°" + s.numero() + " — analyse du sujet…").withStyle(ChatFormatting.RED), true);
        }
        if (t >= 30 && t < DUREE && t % 4 == 0) {
            level.sendParticles(ParticleTypes.ELECTRIC_SPARK, p.x, p.y, p.z, 8, 0.6, 0.3, 0.6, 0.4);
        }
        if (t >= 30 && t < DUREE && t % 16 == 0) {
            level.playSound(null, table, SoundEvents.LIGHTNING_BOLT_IMPACT, SoundSource.BLOCKS, 0.5F, 1.6F + level.random.nextFloat() * 0.3F);
            LaboVie.arc(level, level.random.nextInt(4), Vec3.atCenterOf(table).add(0, 0.3, 0));
        }
        if (t == 50) {
            joueur.addEffect(new MobEffectInstance(MobEffects.CONFUSION, 160, 0, false, false));
            level.playSound(null, table, SoundEvents.RESPAWN_ANCHOR_CHARGE, SoundSource.BLOCKS, 1F, 0.6F);
            joueur.displayClientMessage(Component.literal("Injection du sérum…").withStyle(ChatFormatting.DARK_GREEN), true);
        }
        if (t == 80) {
            level.playSound(null, table, SoundEvents.ZOMBIE_VILLAGER_CURE, SoundSource.BLOCKS, 0.8F, 0.7F);
            level.sendParticles(new DustParticleOptions(new Vector3f(0.55F, 0F, 0F), 1.6F), p.x, p.y, p.z, 30, 0.5, 0.2, 0.5, 0);
        }
        if (t == 105) {
            joueur.addEffect(new MobEffectInstance(MobEffects.DARKNESS, 50, 0, false, false));
            level.playSound(null, table, SoundEvents.TRIDENT_THUNDER.value(), SoundSource.BLOCKS, 0.6F, 1.2F);
            level.sendParticles(ParticleTypes.FLASH, p.x, p.y + 1.5, p.z, 1, 0, 0, 0, 0);
            joueur.displayClientMessage(Component.literal("DÉCHARGE.").withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD), true);
        }
    }

    /** Sur l'autel : le sceau rougeoie, des voix psalmodient, le cœur bat de plus en plus fort. */
    private static void rituel(ServerLevel level, ServerPlayer joueur, Seance s, int t) {
        BlockPos autel = s.table();
        Vec3 centre = Vec3.atBottomCenterOf(LaboBuilder.abs(LaboBuilder.RX, 1, LaboBuilder.RZ)).add(0, 0.05, 0);
        DustParticleOptions rouge = new DustParticleOptions(new Vector3f(0.95F, 0.05F, 0.1F), 1.4F);
        if (t >= 10 && t < DUREE && t % 3 == 0) {
            // des volutes rouges qui montent du sceau vers l'autel
            for (int k = 0; k < 6; k++) {
                double a = level.random.nextDouble() * Math.PI * 2, r = 3 + level.random.nextDouble() * (LaboBuilder.RAYON_SCEAU - 3);
                level.sendParticles(rouge, centre.x + Math.cos(a) * r, centre.y + 0.1, centre.z + Math.sin(a) * r, 1, 0, 0.4, 0, 0.02);
            }
        }
        if (t < DUREE && t % Math.max(6, 20 - t / 8) == 0) {
            level.playSound(null, autel, SoundEvents.WARDEN_HEARTBEAT, SoundSource.BLOCKS, 1F + t / 120F, 0.7F + t / 400F);
        }
        if (t == 30) {
            level.playSound(null, autel, SoundEvents.EVOKER_PREPARE_SUMMON, SoundSource.HOSTILE, 1F, 0.5F);
            joueur.displayClientMessage(Component.literal("Des voix psalmodient ton nom dans l'ombre…").withStyle(ChatFormatting.RED), true);
        }
        if (t == 60) {
            joueur.addEffect(new MobEffectInstance(MobEffects.CONFUSION, 160, 0, false, false));
            level.playSound(null, autel, SoundEvents.EVOKER_CAST_SPELL, SoundSource.HOSTILE, 1F, 0.4F);
            level.playSound(null, autel, SoundEvents.AMBIENT_SOUL_SAND_VALLEY_MOOD.value(), SoundSource.AMBIENT, 1.5F, 0.6F);
            joueur.displayClientMessage(Component.literal("Le sceau brûle sous ta peau.").withStyle(ChatFormatting.DARK_RED), true);
        }
        if (t == 90) {
            joueur.addEffect(new MobEffectInstance(MobEffects.DARKNESS, 70, 0, false, false));
            level.playSound(null, autel, SoundEvents.ELDER_GUARDIAN_CURSE, SoundSource.HOSTILE, 0.8F, 0.6F);
        }
        if (t == 115) {
            level.sendParticles(ParticleTypes.SOUL_FIRE_FLAME, centre.x, centre.y + 2.5, centre.z, 80, 2.5, 1.2, 2.5, 0.05);
            level.sendParticles(rouge, centre.x, centre.y + 1, centre.z, 120, LaboBuilder.RAYON_SCEAU / 2, 0.3, LaboBuilder.RAYON_SCEAU / 2, 0);
            level.playSound(null, autel, SoundEvents.TRIDENT_THUNDER.value(), SoundSource.BLOCKS, 0.7F, 0.5F);
            joueur.displayClientMessage(Component.literal("QUELQUE CHOSE ENTRE EN TOI.").withStyle(ChatFormatting.DARK_RED, ChatFormatting.BOLD), true);
        }
    }

    private static void finir(ServerLevel level, ServerPlayer joueur, Seance s) {
        joueur.stopSleepInBed(true, true);
        if (s.rituel()) {
            // pas de mutation sur l'autel : c'est ici que se lancera la cinématique du rituel (à faire)
            RECHARGES.put(joueur.getUUID(), level.getServer().getTickCount() + (long) RECHARGE);
            level.playSound(null, s.table(), SoundEvents.SOUL_ESCAPE.value(), SoundSource.BLOCKS, 1F, 0.4F);
            joueur.displayClientMessage(Component.literal("Le sceau s'éteint lentement…").withStyle(ChatFormatting.DARK_RED), true);
            return;
        }
        List<Mutation> toutes = mutations();
        Mutation m = toutes.get(level.random.nextInt(toutes.size()));
        enleverMutation(joueur);
        for (Effet e : m.attributs()) {
            AttributeInstance a = joueur.getAttribute(e.attribut());
            if (a != null) {
                a.addTransientModifier(new AttributeModifier(MUTATION, e.valeur(), e.attribut() == Attributes.SCALE
                        ? AttributeModifier.Operation.ADD_MULTIPLIED_BASE : AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
            }
        }
        if (!m.attributs().isEmpty()) {
            MUTANTS.put(joueur.getUUID(), level.getServer().getTickCount() + 20L * m.secondes());
        }
        for (MobEffectInstance e : m.effets()) {
            joueur.addEffect(new MobEffectInstance(e));
        }
        RECHARGES.put(joueur.getUUID(), level.getServer().getTickCount() + (long) RECHARGE);
        dernierResultat = m.nom();
        Labo.titre(joueur, Component.literal(m.nom()).withStyle(ChatFormatting.DARK_RED, ChatFormatting.BOLD),
                Component.literal(m.phrase()).withStyle(ChatFormatting.GRAY));
        joueur.sendSystemMessage(Component.literal("[Labo] Expérience n°" + s.numero() + " terminée : " + m.nom().toLowerCase()
                + " (" + m.secondes() + " s).").withStyle(ChatFormatting.DARK_GRAY));
        level.playSound(null, s.table(), SoundEvents.ZOMBIE_VILLAGER_CONVERTED, SoundSource.BLOCKS, 1F, 0.6F);
        level.sendParticles(ParticleTypes.SCULK_SOUL, joueur.getX(), joueur.getY() + 1, joueur.getZ(), 20, 0.4, 0.6, 0.4, 0.03);
    }

    /** Les mutations qui arrivent à leur fin, même pour ceux qui sont déjà rentrés dans le vrai monde. */
    static void tickHors(MinecraftServer server) {
        if (MUTANTS.isEmpty() || server.getTickCount() % 10 != 0) {
            return;
        }
        long maintenant = server.getTickCount();
        Iterator<Map.Entry<UUID, Long>> it = MUTANTS.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Long> e = it.next();
            ServerPlayer joueur = server.getPlayerList().getPlayer(e.getKey());
            if (joueur == null) {
                // déconnecté : les modificateurs « transitoires » ne sont pas sauvegardés, il n'y a plus rien à enlever
                it.remove();
            } else if (e.getValue() <= maintenant) {
                it.remove();
                enleverMutation(joueur);
                joueur.displayClientMessage(Component.literal("La mutation s'estompe… pour cette fois.").withStyle(ChatFormatting.GRAY), true);
                joueur.serverLevel().playSound(null, joueur.getX(), joueur.getY(), joueur.getZ(), SoundEvents.ZOMBIE_VILLAGER_CURE, SoundSource.PLAYERS, 0.4F, 1.6F);
            }
        }
    }

    private static void enleverMutation(ServerPlayer joueur) {
        for (Holder<Attribute> a : List.of(Attributes.SCALE, Attributes.STEP_HEIGHT, Attributes.GRAVITY, Attributes.SAFE_FALL_DISTANCE, Attributes.JUMP_STRENGTH)) {
            AttributeInstance i = joueur.getAttribute(a);
            if (i != null) {
                i.removeModifier(MUTATION);
            }
        }
    }

    private Experiences() {
    }
}
