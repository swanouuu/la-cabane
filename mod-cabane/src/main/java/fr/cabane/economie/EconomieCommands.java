package fr.cabane.economie;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.GameProfileArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * /argent : ton solde. /payer &lt;joueur&gt; &lt;montant&gt; : donner de l'argent.
 * /argent classement : les plus riches. Admin : /argent donner|retirer|definir &lt;joueurs&gt; &lt;montant&gt;.
 */
public final class EconomieCommands {

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("payer")
                .then(Commands.argument("joueur", EntityArgument.player())
                        .then(Commands.argument("montant", LongArgumentType.longArg(1))
                                .executes(EconomieCommands::payer))));

        dispatcher.register(Commands.literal("argent")
                .executes(ctx -> {
                    ServerPlayer joueur = ctx.getSource().getPlayerOrException();
                    ctx.getSource().sendSuccess(() -> Component.literal("Ton solde : ").append(Economie.texte(Economie.solde(joueur))), false);
                    return 1;
                })
                .then(Commands.literal("classement").executes(EconomieCommands::classement))
                .then(admin("donner", (banque, id, montant) -> banque.crediter(id, montant)))
                .then(admin("retirer", (banque, id, montant) -> banque.definir(id, banque.solde(id) - montant)))
                .then(admin("definir", Banque::definir)));
    }

    private interface Operation {
        void appliquer(Banque banque, UUID joueur, long montant);
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> admin(String nom, Operation operation) {
        return Commands.literal(nom).requires(source -> source.hasPermission(2))
                .then(Commands.argument("joueurs", GameProfileArgument.gameProfile())
                        .then(Commands.argument("montant", LongArgumentType.longArg(0))
                                .executes(ctx -> {
                                    Collection<GameProfile> profils = GameProfileArgument.getGameProfiles(ctx, "joueurs");
                                    long montant = LongArgumentType.getLong(ctx, "montant");
                                    Banque banque = Banque.get(ctx.getSource().getServer());
                                    for (GameProfile profil : profils) {
                                        banque.ouvrir(profil.getId(), profil.getName());
                                        operation.appliquer(banque, profil.getId(), montant);
                                        ServerPlayer enLigne = ctx.getSource().getServer().getPlayerList().getPlayer(profil.getId());
                                        if (enLigne != null) {
                                            Economie.sync(enLigne);
                                        }
                                        ctx.getSource().sendSuccess(() -> Component.literal(profil.getName() + " : ")
                                                .append(Economie.texte(banque.solde(profil.getId()))), true);
                                    }
                                    return profils.size();
                                })));
    }

    private static int payer(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer donneur = ctx.getSource().getPlayerOrException();
        ServerPlayer receveur = EntityArgument.getPlayer(ctx, "joueur");
        long montant = LongArgumentType.getLong(ctx, "montant");
        if (receveur == donneur) {
            ctx.getSource().sendFailure(Component.literal("Tu ne peux pas te payer toi-même."));
            return 0;
        }
        if (!Economie.payer(donneur, montant)) {
            return 0;
        }
        Economie.crediter(ctx.getSource().getServer(), receveur.getUUID(), montant);
        donneur.sendSystemMessage(Component.literal("Tu as envoyé ").append(Economie.texte(montant))
                .append(" à " + receveur.getGameProfile().getName() + ".").withStyle(ChatFormatting.GREEN));
        receveur.sendSystemMessage(Component.literal(donneur.getGameProfile().getName() + " t'a envoyé ")
                .append(Economie.texte(montant)).append(" !").withStyle(ChatFormatting.GREEN));
        return 1;
    }

    private static int classement(CommandContext<CommandSourceStack> ctx) {
        Banque banque = Banque.get(ctx.getSource().getServer());
        List<Map.Entry<UUID, Long>> top = banque.classement(10);
        ctx.getSource().sendSuccess(() -> Component.literal("Les plus riches du serveur :").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD), false);
        for (int i = 0; i < top.size(); i++) {
            Map.Entry<UUID, Long> e = top.get(i);
            int rang = i + 1;
            ctx.getSource().sendSuccess(() -> Component.literal(rang + ". " + banque.nom(e.getKey()) + " — ").append(Economie.texte(e.getValue())), false);
        }
        return top.size();
    }

    private EconomieCommands() {
    }
}
