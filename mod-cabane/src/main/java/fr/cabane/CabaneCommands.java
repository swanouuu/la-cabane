package fr.cabane;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import fr.cabane.boutique.Boutique;
import fr.cabane.boutique.Contrats;
import fr.cabane.casino.Casino;
import net.minecraft.commands.arguments.EntityArgument;
import com.mojang.brigadier.context.CommandContext;
import fr.cabane.hub.HubEvents;
import fr.cabane.telephone.PhoneCalls;
import fr.cabane.telephone.PhoneData;
import java.util.Map;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * /telephone numero|appeler|decrocher|raccrocher (tout le monde), /telephone annuaire (admin)
 * /cabane placer|pnj (admin) pour reconstruire le centre de map ou faire revenir les PNJ.
 */
public final class CabaneCommands {

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("telephone")
                .then(Commands.literal("numero")
                        .then(Commands.argument("numero", StringArgumentType.word())
                                .executes(ctx -> run(ctx, p -> PhoneCalls.setNumber(p, StringArgumentType.getString(ctx, "numero"))))))
                .then(Commands.literal("appeler")
                        .then(Commands.argument("numero", StringArgumentType.word())
                                .executes(ctx -> run(ctx, p -> PhoneCalls.dial(p, StringArgumentType.getString(ctx, "numero"))))))
                .then(Commands.literal("decrocher").executes(ctx -> run(ctx, PhoneCalls::answer)))
                .then(Commands.literal("raccrocher").executes(ctx -> run(ctx, PhoneCalls::hangUp)))
                .then(Commands.literal("annuaire")
                        .requires(source -> source.hasPermission(2))
                        .executes(CabaneCommands::annuaire)));

        // /start : l'hôte lance la partie (tout le monde passe de la salle d'embarquement à l'avion)
        dispatcher.register(Commands.literal("start").requires(source -> source.hasPermission(2)).executes(ctx -> {
            fr.cabane.hub.HubEvents.demarrer(ctx.getSource().getServer(), ctx.getSource());
            return 1;
        }));

        dispatcher.register(Commands.literal("pileouface")
                .then(Commands.literal("accepter").executes(ctx -> run(ctx, Casino::accepter)))
                .then(Commands.argument("joueur", EntityArgument.player())
                        .then(Commands.argument("mise", LongArgumentType.longArg(1)).executes(ctx -> {
                            Casino.defier(ctx.getSource().getPlayerOrException(), EntityArgument.getPlayer(ctx, "joueur"),
                                    LongArgumentType.getLong(ctx, "mise"));
                            return 1;
                        }))));

        dispatcher.register(Commands.literal("cabane")
                // utilisables par les PNJ (Easy NPC, « exécuter en tant que joueur ») ou à la main
                .then(Commands.literal("boutique").executes(ctx -> run(ctx, Boutique::ouvrir)))
                .then(Commands.literal("contrats").executes(ctx -> run(ctx, Contrats::ouvrir)))
                .then(Commands.literal("forge").executes(ctx -> run(ctx, fr.cabane.boutique.Forge::ouvrir)))
                // utilisée par les quêtes : une carte au trésor vers le donjon le plus proche d'un point lointain
                .then(Commands.literal("carte").requires(source -> source.hasPermission(2))
                        .then(Commands.argument("joueur", EntityArgument.player())
                                .then(Commands.argument("lieu", StringArgumentType.word())
                                        .then(Commands.argument("distance", com.mojang.brigadier.arguments.IntegerArgumentType.integer(0, 30000))
                                                .executes(ctx -> carteAuTresor(ctx.getSource(), EntityArgument.getPlayer(ctx, "joueur"),
                                                        StringArgumentType.getString(ctx, "lieu"),
                                                        com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "distance")))))))
                .then(Commands.literal("placer").requires(source -> source.hasPermission(3)).executes(ctx -> {
                    BlockPos pos = BlockPos.containing(ctx.getSource().getPosition()).below();
                    HubEvents.placer(ctx.getSource().getLevel(), pos);
                    ctx.getSource().sendSuccess(() -> Component.literal("Construction du centre de map lancée ici (une minute environ), le spawn y sera déplacé."), true);
                    return 1;
                }))
                // l'hôte peut lâcher la garde présidentielle sur quelqu'un
                .then(Commands.literal("garde").requires(source -> source.hasPermission(3))
                        .then(Commands.argument("joueur", EntityArgument.player()).executes(ctx -> {
                            fr.cabane.hub.Gardes.lacher(EntityArgument.getPlayer(ctx, "joueur"));
                            return 1;
                        })))
                .then(Commands.literal("pnj").requires(source -> source.hasPermission(3)).executes(ctx -> {
                    int count = HubEvents.spawnNpcs(ctx.getSource().getLevel());
                    ctx.getSource().sendSuccess(() -> Component.literal(count + " PNJ replacés autour de la cabane."), true);
                    return count;
                })));
    }

    private interface PlayerAction {
        void accept(ServerPlayer player);
    }

    private static int run(CommandContext<CommandSourceStack> ctx, PlayerAction action) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        action.accept(ctx.getSource().getPlayerOrException());
        return 1;
    }

    /**
     * Le point de départ de la recherche est loin de la cabane, dans une direction propre à chaque lieu :
     * les donjons des reliques sont donc à des milliers de blocs.
     */
    private static int carteAuTresor(CommandSourceStack source, ServerPlayer joueur, String lieu, int distance) {
        net.minecraft.server.level.ServerLevel level = source.getServer().overworld();
        BlockPos centre = fr.cabane.hub.HubData.get(source.getServer()).centre();
        if (centre == null) {
            centre = level.getSharedSpawnPos();
        }
        double angle = Math.floorMod(lieu.hashCode(), 360) * Math.PI / 180;
        net.minecraft.world.phys.Vec3 origine = new net.minecraft.world.phys.Vec3(
                centre.getX() + Math.cos(angle) * distance, centre.getY(), centre.getZ() + Math.sin(angle) * distance);
        var cle = net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.LOOT_TABLE, Cabane.id("cartes/" + lieu));
        var table = source.getServer().reloadableRegistries().getLootTable(cle);
        var params = new net.minecraft.world.level.storage.loot.LootParams.Builder(level)
                .withParameter(net.minecraft.world.level.storage.loot.parameters.LootContextParams.ORIGIN, origine)
                .create(net.minecraft.world.level.storage.loot.parameters.LootContextParamSets.CHEST);
        for (var stack : table.getRandomItems(params)) {
            if (!joueur.getInventory().add(stack)) {
                joueur.drop(stack, false);
            }
        }
        joueur.sendSystemMessage(Component.literal("Karim le Chouf te tend une carte au trésor : le donjon est à environ "
                + distance + " blocs de la cabane.").withStyle(ChatFormatting.AQUA));
        return 1;
    }

    private static int annuaire(CommandContext<CommandSourceStack> ctx) {
        Map<String, String> directory = PhoneData.get(ctx.getSource().getServer()).directory();
        if (directory.isEmpty()) {
            ctx.getSource().sendSuccess(() -> Component.literal("Personne n'a encore de numéro."), false);
        }
        directory.forEach((number, name) -> ctx.getSource().sendSuccess(
                () -> Component.literal(number + " → " + name).withStyle(ChatFormatting.AQUA), false));
        return directory.size();
    }

    private CabaneCommands() {
    }
}
