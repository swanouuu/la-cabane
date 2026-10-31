package fr.cabane;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
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

        dispatcher.register(Commands.literal("cabane")
                .requires(source -> source.hasPermission(3))
                .then(Commands.literal("placer").executes(ctx -> {
                    BlockPos pos = BlockPos.containing(ctx.getSource().getPosition()).below();
                    HubEvents.build(ctx.getSource().getLevel(), pos);
                    ctx.getSource().sendSuccess(() -> Component.literal("Centre de map construit ici, le spawn y est déplacé."), true);
                    return 1;
                }))
                .then(Commands.literal("pnj").executes(ctx -> {
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
