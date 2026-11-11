package fr.cabane.telephone;

import fr.cabane.Cabane;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

/**
 * /telepatie &lt;pseudo&gt; : l'hôte parle dans la tête d'un joueur (la bête scellée, une voix venue de nulle part…).
 * Tant que c'est actif, le micro de l'hôte ne part plus qu'à ce joueur, où qu'ils soient tous les deux, avec la voix
 * grave des masques ninja ; personne d'autre ne l'entend, même juste à côté. Le joueur n'est prévenu de rien : ni
 * message, ni son, ni icône (la voix arrive par un canal anonyme, pas au nom de l'hôte). L'hôte non plus : la commande
 * ne répond rien du tout (voulu). /telepatie stop arrête. Rien ici ne touche au vocal lui-même (VoixTelepathie s'en charge).
 */
@EventBusSubscriber(modid = Cabane.MODID)
public final class Telepathie {
    /** L'hôte → le joueur dans la tête duquel il parle. */
    private static final Map<UUID, UUID> LIENS = new ConcurrentHashMap<>();

    /** Le joueur dans la tête duquel parle cet hôte, ou null. */
    static UUID cible(UUID hote) {
        return LIENS.get(hote);
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        // aucune réponse nulle part : ni chat, ni barre d'action, ni chez les autres ops
        event.getDispatcher().register(Commands.literal("telepatie").requires(Telepathie::hote)
                .then(Commands.literal("stop").executes(ctx -> arreter(ctx.getSource().getPlayerOrException())))
                .then(Commands.argument("joueur", EntityArgument.player()).executes(ctx ->
                        commencer(ctx.getSource().getPlayerOrException(), EntityArgument.getPlayer(ctx, "joueur")))));
    }

    private static int commencer(ServerPlayer hote, ServerPlayer joueur) {
        UUID avant = LIENS.put(hote.getUUID(), joueur.getUUID());
        if (avant != null && !avant.equals(joueur.getUUID())) {
            VoiceBridge.oublierTelepathie(hote.getUUID());
        }
        return 1;
    }

    private static int arreter(ServerPlayer hote) {
        UUID cible = LIENS.remove(hote.getUUID());
        VoiceBridge.oublierTelepathie(hote.getUUID());
        return cible == null ? 0 : 1;
    }

    /** L'hôte qui se déconnecte coupe sa télépathie (le joueur, lui, peut revenir : le lien l'attend). */
    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (LIENS.remove(event.getEntity().getUUID()) != null) {
            VoiceBridge.oublierTelepathie(event.getEntity().getUUID());
        }
    }

    /** L'hôte : un admin du serveur, ou le joueur qui a ouvert le monde en solo. */
    static boolean hote(CommandSourceStack source) {
        if (source.hasPermission(2)) {
            return true;
        }
        ServerPlayer joueur = source.getPlayer();
        return joueur != null && !source.getServer().isDedicatedServer() && source.getServer().isSingleplayerOwner(joueur.getGameProfile());
    }

    private Telepathie() {
    }
}
