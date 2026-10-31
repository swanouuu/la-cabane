package fr.cabane.client;

import fr.cabane.Cabane;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * Outil de test : si le dossier du jeu contient {@code cabane-photos.txt}, le jeu fait le tour
 * des points de vue listés (une ligne : {@code nom x y z yaw pitch [heure]}), prend une capture
 * sans interface à chacun, puis se ferme. Sans ce fichier, rien ne se passe.
 */
@EventBusSubscriber(modid = Cabane.MODID, value = Dist.CLIENT)
public final class PhotoTour {
    private static final int ATTENTE_DEBUT = 300;
    private static final int ATTENTE_POINT = 140;

    private static List<String[]> etapes;
    private static int index = -1;
    private static int attente;
    private static String[] point;

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.getSingleplayerServer() == null) {
            return;
        }
        if (etapes == null) {
            Path fichier = mc.gameDirectory.toPath().resolve("cabane-photos.txt");
            if (!Files.exists(fichier)) {
                etapes = List.of();
                return;
            }
            etapes = lire(fichier);
            attente = ATTENTE_DEBUT;
            mc.options.hideGui = true;
            commande(mc, "gamemode spectator");
            commande(mc, "weather clear 100000");
            commande(mc, "gamerule doDaylightCycle false");
            commande(mc, "time set 6000");
        }
        if (etapes.isEmpty()) {
            return;
        }
        boolean ecran = index >= 0 && etapes.get(index)[0].equals("ui");
        if (point != null && !ecran) {
            bloquerCamera(mc, point);
        }
        if (--attente > 0) {
            return;
        }
        if (index >= 0 && !etapes.get(index)[0].equals("cmd") && !etapes.get(index)[0].equals("act")) {
            String[] e = etapes.get(index);
            Screenshot.grab(mc.gameDirectory, (ecran ? e[1] : e[0]) + ".png", mc.getMainRenderTarget(), message -> { });
            if (ecran && !(e.length > 4 && e[e.length - 1].equals("garder"))) {
                mc.setScreen(null);
                mc.options.hideGui = true;
            }
        }
        index++;
        if (index >= etapes.size()) {
            etapes = List.of();
            mc.stop();
            return;
        }
        String[] e = etapes.get(index);
        if (e[0].equals("cmd")) {
            // « cmd <commande…> » : une commande, sans capture
            commande(mc, String.join(" ", java.util.Arrays.copyOfRange(e, 1, e.length)));
            attente = 15;
            return;
        }
        if (e[0].equals("act")) {
            // « act <action> <arguments…> » : ce que ferait le joueur avec son téléphone, sans capture
            attente = agir(e);
            return;
        }
        if (e[0].equals("ui")) {
            // « ui <nom> cmd <commande…> », « ui <nom> ecran <écran> [garder] »,
            // « ui <nom> jouer <écran> <jeu> <choix> <ticks> » : capture avec l'interface
            mc.options.hideGui = false;
            attente = 50;
            switch (e[2]) {
                case "cmd" -> commande(mc, String.join(" ", java.util.Arrays.copyOfRange(e, 3, e.length)));
                case "jouer" -> {
                    ouvrir(mc, e[3]);
                    net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                            new fr.cabane.casino.Casino.Jouer(Integer.parseInt(e[4]), 50, Integer.parseInt(e[5])));
                    attente = Integer.parseInt(e[6]);
                }
                case "rien" -> attente = Integer.parseInt(e[3]);
                default -> ouvrir(mc, e[3]);
            }
            return;
        }
        point = e;
        commande(mc, "time set " + (e.length > 6 ? e[6] : "6000"));
        commande(mc, "tp @s " + e[1] + " " + e[2] + " " + e[3] + " " + e[4] + " " + e[5]);
        attente = ATTENTE_POINT;
    }

    private static void ouvrir(Minecraft mc, String nom) {
        switch (nom) {
            case "telephone" -> mc.setScreen(new ClientPiaf.Accueil());
            case "contacts" -> mc.setScreen(new ClientPiaf.Contacts());
            case "recents" -> mc.setScreen(new ClientPiaf.Recents());
            case "booster", "booster-ouvert" -> {
                var ids = fr.cabane.tcg.Cartes.toutes().stream().map(fr.cabane.tcg.Cartes.Def::id).toList();
                var tirage = List.of(ids.get(0), ids.get(5), ids.get(9), ids.get(14), ids.get(18));
                ClientCartes.BoosterScreen ecranBooster = new ClientCartes.BoosterScreen(tirage);
                mc.setScreen(ecranBooster);
                if (nom.equals("booster-ouvert")) {
                    ecranBooster.toutRetourner();
                }
            }
            case "appels" -> mc.setScreen(new PhoneScreen());
            case "piaf" -> mc.setScreen(new ClientPiaf.Fil());
            case "portefeuille" -> mc.setScreen(new ClientPiaf.Portefeuille());
            case "classeur" -> mc.setScreen(new ClientCartes.ClasseurScreen());
            case "machine" -> fr.cabane.client.casino.ClientCasino.ouvrir(fr.cabane.bloc.BlocInteractif.Ouverture.MACHINE_A_SOUS);
            case "roulette" -> fr.cabane.client.casino.ClientCasino.ouvrir(fr.cabane.bloc.BlocInteractif.Ouverture.ROULETTE);
            case "blackjack" -> fr.cabane.client.casino.ClientCasino.ouvrir(fr.cabane.bloc.BlocInteractif.Ouverture.BLACKJACK);
            case "des" -> fr.cabane.client.casino.ClientCasino.ouvrir(fr.cabane.bloc.BlocInteractif.Ouverture.DES);
            case "roue" -> fr.cabane.client.casino.ClientCasino.ouvrir(fr.cabane.bloc.BlocInteractif.Ouverture.ROUE);
            case "pileouface" -> fr.cabane.client.casino.ClientCasino.ouvrir(fr.cabane.bloc.BlocInteractif.Ouverture.PILE_OU_FACE);
            default -> {
            }
        }
    }

    private static int agir(String[] e) {
        String reste = String.join(" ", java.util.Arrays.copyOfRange(e, 2, e.length));
        switch (e[1]) {
            case "numero" -> net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                    new fr.cabane.telephone.PhoneNetwork.Action(fr.cabane.telephone.PhoneNetwork.Action.CHOISIR_NUMERO, reste));
            case "contact" -> net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                    new fr.cabane.telephone.PhoneNetwork.Action(fr.cabane.telephone.PhoneNetwork.Action.AJOUTER_CONTACT,
                            e[2] + "|" + String.join(" ", java.util.Arrays.copyOfRange(e, 3, e.length))));
            case "pseudo" -> ClientPiaf.demander(1, reste, 0);
            case "photo" -> ClientPiaf.prendrePhoto();
            case "publier" -> ClientPiaf.demander(2, reste, ClientPiaf.galerie.isEmpty() ? 0 : ClientPiaf.galerie.getLast());
            case "texte" -> ClientPiaf.demander(2, reste, 0);
            case "attendre" -> {
                return Integer.parseInt(e[2]);
            }
            default -> {
            }
        }
        return 30;
    }

    /** La souris ou le clavier ne doivent pas faire bouger la vue pendant le tour. */
    private static void bloquerCamera(Minecraft mc, String[] e) {
        float yaw = Float.parseFloat(e[4]);
        float pitch = Float.parseFloat(e[5]);
        mc.player.setPos(Double.parseDouble(e[1]), Double.parseDouble(e[2]), Double.parseDouble(e[3]));
        mc.player.setDeltaMovement(0, 0, 0);
        mc.player.setYRot(yaw);
        mc.player.setXRot(pitch);
        mc.player.yRotO = yaw;
        mc.player.xRotO = pitch;
        mc.player.setYHeadRot(yaw);
    }

    /** Exécutée directement par le serveur intégré, même si le monde n'autorise pas les commandes. */
    private static void commande(Minecraft mc, String commande) {
        var serveur = mc.getSingleplayerServer();
        java.util.UUID id = mc.player.getUUID();
        serveur.execute(() -> {
            var joueur = serveur.getPlayerList().getPlayer(id);
            if (joueur != null) {
                serveur.getCommands().performPrefixedCommand(joueur.createCommandSourceStack().withPermission(4), commande);
            }
        });
    }

    private static List<String[]> lire(Path fichier) {
        List<String[]> liste = new ArrayList<>();
        try {
            for (String ligne : Files.readAllLines(fichier)) {
                if (!ligne.isBlank() && !ligne.startsWith("#")) {
                    liste.add(ligne.trim().split("\\s+"));
                }
            }
        } catch (IOException ex) {
            Cabane.LOGGER.warn("cabane-photos.txt illisible : {}", ex.toString());
        }
        return liste;
    }

    private PhotoTour() {
    }
}
