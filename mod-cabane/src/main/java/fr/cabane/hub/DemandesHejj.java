package fr.cabane.hub;

import fr.cabane.economie.Economie;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * La demande du jour du Hejj : chaque jour (et à chaque redémarrage du serveur), il a besoin de quelque chose pour le
 * village. On lui demande (« Ta demande du jour ? »), on rapporte ce qu'il faut (« J'ai ce qu'il te faut »), il paie.
 * Chaque joueur peut l'aider une fois par demande. Pour changer ou ajouter des demandes : la liste DEMANDES.
 */
public final class DemandesHejj {
    private record Demande(String texte, String objet, int nombre, long prime) {
    }

    private static final List<Demande> DEMANDES = List.of(
            new Demande("Les poules de Monique se sont encore échappées. Ramène-moi 16 œufs, qu'on fasse une omelette pour tout le village.",
                    "minecraft:egg", 16, 300),
            new Demande("Gérard a mangé toutes les réserves. TOUTES. Il me faut 32 pains avant ce soir.", "minecraft:bread", 32, 400),
            new Demande("Les lanternes de la place ont rendu l'âme. 16 lanternes, et Ginette pourra de nouveau sortir le soir.",
                    "minecraft:lantern", 16, 450),
            new Demande("La saison des pluies arrive. Il faut du bois sec : 64 bûches de jungle.", "minecraft:jungle_log", 64, 400),
            new Demande("Nyota n'a plus de quoi soigner les blessés des raids. 8 tranches de pastèque scintillante.",
                    "minecraft:glistering_melon_slice", 8, 500),
            new Demande("La muraille a des trous. 128 pierres taillées, et les Pillards n'ont qu'à bien se tenir.", "minecraft:stone_bricks", 128, 600),
            new Demande("Les gardes réclament des selles neuves. 10 cuirs, et pas de questions sur l'origine des vaches.", "minecraft:leather", 10, 350),
            new Demande("Ce soir, c'est la fête au feu de camp ! 12 bières blondes, et je ne veux pas savoir qui les boira.",
                    "cabane:biere_blonde", 12, 400),
            new Demande("Brokk dit que sa forge a froid. 48 charbons, sinon il va encore bouder.", "minecraft:coal", 48, 400),
            new Demande("Le forgeron manque de fer pour les outils des nouveaux. 32 lingots de fer.", "minecraft:iron_ingot", 32, 700),
            new Demande("Martine veut fleurir la place pour la venue du Président. 24 coquelicots. (Il ne les mérite pas.)",
                    "minecraft:poppy", 24, 250),
            new Demande("Les enfants du village veulent des cerfs-volants. 32 ficelles, et beaucoup de patience.", "minecraft:string", 32, 300),
            new Demande("Le toit de la Grande Cabane fuit. Il me faut 64 planches d'acajou… enfin, d'acacia, c'est pareil.",
                    "minecraft:acacia_planks", 64, 350),
            new Demande("Karim a perdu sa boussole dans la jungle. Encore. Rapporte-m'en une neuve.", "minecraft:compass", 1, 300),
            new Demande("Les champs ont besoin d'engrais. 32 poudres d'os, et ne demande pas d'où elles viennent.", "minecraft:bone_meal", 32, 300),
            new Demande("Mouloud a promis du sucre à toute la place. Il n'en a pas. Évidemment. 48 sucres.", "minecraft:sugar", 48, 300));

    /** Qui a déjà aidé pour la demande en cours (elle change chaque jour, et à chaque redémarrage). */
    private static final Set<UUID> AIDES = new HashSet<>();
    private static int demandeAidee = -1;
    private static int demarrages;

    public static void onServerStarted(MinecraftServer server) {
        demarrages = HubData.get(server).nouveauDemarrage();
        AIDES.clear();
    }

    private static int indexDuJour() {
        long jour = LocalDate.now().toEpochDay();
        return (int) Math.floorMod(jour * 7 + demarrages * 3L, DEMANDES.size());
    }

    private static Item objet(Demande d) {
        return BuiltInRegistries.ITEM.get(ResourceLocation.parse(d.objet()));
    }

    /** « Ta demande du jour ? » */
    public static void demande(ServerPlayer joueur) {
        int i = indexDuJour();
        Demande d = DEMANDES.get(i);
        joueur.sendSystemMessage(Component.literal("Le Hejj : « " + d.texte() + " »").withStyle(ChatFormatting.GOLD));
        if (demandeAidee == i && AIDES.contains(joueur.getUUID())) {
            joueur.sendSystemMessage(Component.literal("Tu m'as déjà aidé aujourd'hui. Reviens demain !").withStyle(ChatFormatting.GRAY));
            return;
        }
        int a = joueur.getInventory().countItem(objet(d));
        joueur.sendSystemMessage(Component.literal("→ " + d.nombre() + " × ").append(new ItemStack(objet(d)).getHoverName())
                .append(" (tu en as " + a + ") · récompense : ").append(Economie.texte(d.prime())).withStyle(ChatFormatting.YELLOW));
    }

    /** « J'ai ce qu'il te faut » */
    public static void livrer(ServerPlayer joueur) {
        int i = indexDuJour();
        Demande d = DEMANDES.get(i);
        if (demandeAidee != i) {
            demandeAidee = i;
            AIDES.clear();
        }
        if (AIDES.contains(joueur.getUUID())) {
            joueur.sendSystemMessage(Component.literal("Le Hejj : « Tu as déjà fait ta part aujourd'hui. Repose-toi ! »").withStyle(ChatFormatting.GOLD));
            return;
        }
        Item item = objet(d);
        if (joueur.getInventory().countItem(item) < d.nombre()) {
            joueur.sendSystemMessage(Component.literal("Le Hejj : « Il m'en faut " + d.nombre() + ", pas un de moins. »").withStyle(ChatFormatting.GOLD));
            return;
        }
        joueur.getInventory().clearOrCountMatchingItems(s -> s.is(item), d.nombre(), joueur.inventoryMenu.getCraftSlots());
        AIDES.add(joueur.getUUID());
        Economie.crediter(joueur.server, joueur.getUUID(), d.prime());
        joueur.level().playSound(null, joueur.blockPosition(), SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 0.7F, 1.2F);
        joueur.sendSystemMessage(Component.literal("Le Hejj : « Beauvais te remercie. » ").withStyle(ChatFormatting.GOLD)
                .append(Component.literal("+").append(Economie.texte(d.prime())).withStyle(ChatFormatting.GREEN)));
    }

    private DemandesHejj() {
    }
}
