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
        dispatcher.register(Commands.literal("start").requires(CabaneCommands::hote).executes(ctx -> {
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
                .then(Commands.literal("boutique").executes(ctx -> run(ctx, Boutique::ouvrir))
                        // la boutique d'un autre PNJ du village (Jean-Mi et ses cartes…), seulement à côté de lui
                        .then(Commands.literal("cartes").executes(ctx -> run(ctx, j -> Boutique.ouvrirChez(j, "vendeur_cartes")))))
                .then(Commands.literal("contrats").executes(ctx -> run(ctx, Contrats::ouvrir)))
                // la forge de Brokk, éventuellement ouverte sur une catégorie (« relique » : les reliques des boss)
                .then(Commands.literal("forge").executes(ctx -> run(ctx, j -> fr.cabane.boutique.Forge.ouvrir(j, "")))
                        .then(Commands.argument("onglet", StringArgumentType.word())
                                .executes(ctx -> run(ctx, j -> fr.cabane.boutique.Forge.ouvrir(j, StringArgumentType.getString(ctx, "onglet"))))))
                // les missions des habitants : la liste ; « cacher » masque (ou remet) le suivi à l'écran
                .then(Commands.literal("missions").executes(ctx -> run(ctx, fr.cabane.pnj.Missions::lister))
                        .then(Commands.literal("cacher").executes(ctx -> run(ctx, fr.cabane.pnj.Missions::cacher))))
                // les quêtes de la semaine (chez le Hejj) ; « suivante » : l'hôte passe à la semaine d'après, pour tout le monde
                .then(Commands.literal("semaine").executes(ctx -> run(ctx, fr.cabane.pnj.Missions::infoSemaine))
                        .then(Commands.literal("suivante").requires(CabaneCommands::hote).executes(ctx -> {
                            fr.cabane.pnj.Missions.semaineSuivante(ctx.getSource().getServer());
                            return 1;
                        })))
                // pour l'hôte (et les tests) : parler au PNJ le plus proche portant ce nom de fichier, puis choisir une réponse
                .then(Commands.literal("parler").requires(CabaneCommands::hote)
                        .then(Commands.argument("pnj", StringArgumentType.word()).executes(ctx -> {
                            ServerPlayer joueur = ctx.getSource().getPlayerOrException();
                            String tag = "pnj." + StringArgumentType.getString(ctx, "pnj");
                            var pnj = joueur.level().getEntities(joueur, joueur.getBoundingBox().inflate(64), e -> e.getTags().contains(tag)).stream()
                                    .min(java.util.Comparator.comparingDouble(e -> e.distanceToSqr(joueur))).orElse(null);
                            if (pnj == null) {
                                ctx.getSource().sendFailure(Component.literal("Personne de ce nom à moins de 64 blocs."));
                                return 0;
                            }
                            fr.cabane.pnj.Dialogues.parler(joueur, pnj);
                            return 1;
                        })))
                // pour les tests : exactement ce que fait un clic droit du joueur sur ce PNJ (côté serveur)
                .then(Commands.literal("clic").requires(CabaneCommands::hote)
                        .then(Commands.argument("pnj", StringArgumentType.word()).executes(ctx -> {
                            ServerPlayer joueur = ctx.getSource().getPlayerOrException();
                            String tag = "pnj." + StringArgumentType.getString(ctx, "pnj");
                            var pnj = joueur.level().getEntities(joueur, joueur.getBoundingBox().inflate(8), e -> e.getTags().contains(tag)).stream()
                                    .min(java.util.Comparator.comparingDouble(e -> e.distanceToSqr(joueur))).orElse(null);
                            if (pnj == null) {
                                ctx.getSource().sendFailure(Component.literal("Personne de ce nom à moins de 8 blocs."));
                                return 0;
                            }
                            var resultat = joueur.interactOn(pnj, net.minecraft.world.InteractionHand.MAIN_HAND);
                            ctx.getSource().sendSuccess(() -> Component.literal("Clic droit : " + resultat), false);
                            return 1;
                        })))
                .then(Commands.literal("dialogue").requires(CabaneCommands::hote)
                        .then(Commands.argument("choix", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1, 20)).executes(ctx -> {
                            fr.cabane.pnj.Dialogues.choisirPourTest(ctx.getSource().getPlayerOrException(),
                                    com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "choix") - 1);
                            return 1;
                        })))
                // pour l'hôte (et les tests) : commander à la borne la plus proche, comme si on touchait l'écran
                .then(Commands.literal("commander").requires(CabaneCommands::hote)
                        .then(Commands.argument("article", StringArgumentType.word()).executes(ctx -> {
                            ServerPlayer joueur = ctx.getSource().getPlayerOrException();
                            fr.cabane.bouffe.FastFood.ouvrir(joueur, null);
                            fr.cabane.bouffe.FastFood.commanderPourTest(joueur, StringArgumentType.getString(ctx, "article"));
                            return 1;
                        })))
                // pour l'hôte (et les tests) : commander une pièce à Brokk sans passer par l'écran de la forge
                .then(Commands.literal("forger").requires(CabaneCommands::hote)
                        .then(Commands.argument("recette", StringArgumentType.word()).executes(ctx -> {
                            fr.cabane.boutique.Forge.forgerPourTest(ctx.getSource().getPlayerOrException(), StringArgumentType.getString(ctx, "recette"));
                            return 1;
                        })))
                // la demande du jour du Hejj
                .then(Commands.literal("hejj")
                        .then(Commands.literal("demande").executes(ctx -> run(ctx, fr.cabane.hub.DemandesHejj::demande)))
                        .then(Commands.literal("livrer").executes(ctx -> run(ctx, fr.cabane.hub.DemandesHejj::livrer))))
                // le marché noir : seulement à côté du Fourgue
                .then(Commands.literal("fourgue").executes(ctx -> run(ctx, fr.cabane.drogue.MarcheNoir::ouvrir))
                        .then(Commands.literal("vendre").executes(ctx -> run(ctx, fr.cabane.drogue.MarcheNoir::vendre))))
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
                }))
                // la Mairie et son laboratoire secret, construits dans un village qui existait déjà
                .then(Commands.literal("mairie").requires(source -> source.hasPermission(3)).executes(ctx -> {
                    var level = ctx.getSource().getServer().overworld();
                    BlockPos centre = fr.cabane.hub.HubData.get(ctx.getSource().getServer()).centre();
                    if (centre == null) {
                        ctx.getSource().sendFailure(Component.literal("Beauvais n'est pas encore construit."));
                        return 0;
                    }
                    new fr.cabane.hub.HubBuilder(level, centre).mairieEtSecret();
                    int count = HubEvents.spawnNpcs(level);
                    ctx.getSource().sendSuccess(() -> Component.literal("La Mairie et son secret sont construits, " + count + " PNJ replacés."), true);
                    return 1;
                }))
                // un événement : les Pillards du Sud marchent sur le village
                .then(Commands.literal("raid").requires(source -> source.hasPermission(2))
                        .executes(ctx -> raid(ctx.getSource(), 6))
                        .then(Commands.argument("nombre", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1, 40))
                                .executes(ctx -> raid(ctx.getSource(), com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "nombre")))))
                // les événements que l'hôte prépare : le combat Vegeta contre Goku
                .then(Commands.literal("evenement").requires(CabaneCommands::hote)
                        .then(Commands.literal("vegeta")
                                .then(Commands.argument("action", StringArgumentType.word())
                                        .suggests((ctx, b) -> net.minecraft.commands.SharedSuggestionProvider.suggest(new String[] {"armer", "annuler", "lancer"}, b))
                                        .executes(ctx -> {
                                            var message = fr.cabane.vegeta.EvenementVegeta.commande(ctx.getSource().getServer(),
                                                    StringArgumentType.getString(ctx, "action"), ctx.getSource().getPlayer());
                                            ctx.getSource().sendSuccess(() -> message, true);
                                            return 1;
                                        }))))
                // le PLATO 6Z : tout le monde peut demander où il est ; l'hôte le construit, l'ouvre ou le scelle
                .then(plato("plato"))
                .then(plato("forteresse")));
    }

    /** /cabane plato (ou l'ancien nom, forteresse) : où il est ; l'hôte le construit, l'ouvre, le scelle, y pose les coffres. */
    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> plato(String nom) {
        return Commands.literal(nom).executes(ctx -> forteresse(ctx.getSource()))
                .then(Commands.literal("construire").requires(CabaneCommands::hote).executes(ctx -> {
                    fr.cabane.histoire.Forteresse.lancer(ctx.getSource().getServer().overworld(), true);
                    ctx.getSource().sendSuccess(() -> Component.literal("Construction du PLATO 6Z lancée (une à deux minutes)."), true);
                    return 1;
                }))
                .then(Commands.literal("coffres").requires(CabaneCommands::hote).executes(ctx -> {
                    int n = fr.cabane.histoire.Forteresse.coffres(ctx.getSource().getServer().overworld());
                    ctx.getSource().sendSuccess(() -> Component.literal(n + " coffres au trésor posés dans le PLATO 6Z."), true);
                    return n;
                }))
                .then(Commands.literal("ouvrir").requires(CabaneCommands::hote).executes(ctx -> porteForteresse(ctx.getSource(), true)))
                .then(Commands.literal("fermer").requires(CabaneCommands::hote).executes(ctx -> porteForteresse(ctx.getSource(), false)));
    }

    /** L'hôte : un admin du serveur, ou le joueur qui a ouvert le monde en solo. */
    static boolean hote(CommandSourceStack source) {
        if (source.hasPermission(2)) {
            return true;
        }
        ServerPlayer joueur = source.getPlayer();
        return joueur != null && !source.getServer().isDedicatedServer() && source.getServer().isSingleplayerOwner(joueur.getGameProfile());
    }

    private static int raid(CommandSourceStack source, int nombre) {
        int n = fr.cabane.histoire.Histoire.raid(source.getServer().overworld(), nombre);
        source.sendSuccess(() -> Component.literal(n + " pillards marchent sur Beauvais."), true);
        return n;
    }

    private static int forteresse(CommandSourceStack source) {
        var data = fr.cabane.hub.HubData.get(source.getServer());
        BlockPos f = data.forteresse();
        if (f == null) {
            source.sendSuccess(() -> Component.literal("Le PLATO 6Z n'a pas encore été repéré (il se construit, très loin au sud).").withStyle(ChatFormatting.GRAY), false);
            return 0;
        }
        source.sendSuccess(() -> Component.literal("⚑ Le PLATO 6Z : X " + f.getX() + "  Y " + f.getY() + "  Z " + f.getZ()
                + (data.forteresseOuverte() ? " — la porte est ouverte." : " — scellé, c'est pour plus tard.")).withStyle(ChatFormatting.GOLD), false);
        return 1;
    }

    private static int porteForteresse(CommandSourceStack source, boolean ouverte) {
        if (!fr.cabane.histoire.Forteresse.porte(source.getServer().overworld(), ouverte)) {
            source.sendFailure(Component.literal("Le PLATO 6Z n'est pas encore construit (/cabane plato construire)."));
            return 0;
        }
        source.sendSuccess(() -> Component.literal(ouverte ? "Le PLATO 6Z est ouvert : la grande herse est levée." : "Le PLATO 6Z est à nouveau scellé."), true);
        return 1;
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
