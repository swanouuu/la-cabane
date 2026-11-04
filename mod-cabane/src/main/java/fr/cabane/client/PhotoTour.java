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
    /** « act recul N » : la caméra à la troisième personne recule à N blocs (0 : la distance normale), pour les grands pouvoirs. */
    private static float recul;

    @SubscribeEvent
    public static void distanceCamera(net.neoforged.neoforge.client.event.CalculateDetachedCameraDistanceEvent event) {
        if (recul > 0) {
            event.setDistance(recul);
        }
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.getSingleplayerServer() == null) {
            return;
        }
        if (etapes == null) {
            // CABANE_PHOTOS : un autre fichier de captures (pour que deux tests ne se marchent pas dessus)
            Path fichier = mc.gameDirectory.toPath().resolve(System.getenv().getOrDefault("CABANE_PHOTOS", "cabane-photos.txt"));
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
                ClientCamera.fermer();
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
                    ouvrir(mc, e[3], e);
                    net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                            new fr.cabane.casino.Casino.Jouer(Integer.parseInt(e[4]), 50, Integer.parseInt(e[5]), fr.cabane.client.casino.ClientCasino.machine));
                    attente = Integer.parseInt(e[6]);
                }
                case "rien" -> attente = Integer.parseInt(e[3]);
                case "vote" -> {
                    // « ui <nom> vote <camp> <ticks> » : voter (0 Goku, 1 Vegeta), puis capture
                    net.neoforged.neoforge.network.PacketDistributor.sendToServer(new fr.cabane.vegeta.EvenementVegeta.Choix(Integer.parseInt(e[3])));
                    attente = Integer.parseInt(e[4]);
                }
                case "blackjack" -> {
                    // « ui <nom> blackjack <ticks> x y z » : s'asseoir à la table et se faire distribuer
                    fr.cabane.client.casino.ClientCasino.ouvrir(fr.cabane.bloc.BlocInteractif.Ouverture.BLACKJACK, position(e));
                    net.neoforged.neoforge.network.PacketDistributor.sendToServer(new fr.cabane.casino.Blackjack.Action(fr.cabane.casino.Blackjack.DISTRIBUER, 50, position(e)));
                    attente = Integer.parseInt(e[3]);
                }
                default -> ouvrir(mc, e[3], e);
            }
            return;
        }
        point = e;
        commande(mc, "time set " + (e.length > 6 ? e[6] : "6000"));
        ClientCamera.fermer();
        commande(mc, "tp @s " + e[1] + " " + e[2] + " " + e[3] + " " + e[4] + " " + e[5]);
        attente = ATTENTE_POINT;
    }

    /** Une position donnée en fin de ligne (x y z), pour les machines du casino. */
    private static net.minecraft.core.BlockPos position(String[] e) {
        try {
            int n = e.length;
            return new net.minecraft.core.BlockPos(Integer.parseInt(e[n - 3]), Integer.parseInt(e[n - 2]), Integer.parseInt(e[n - 1]));
        } catch (RuntimeException ex) {
            return fr.cabane.client.casino.ClientCasino.machine;
        }
    }

    private static void ouvrir(Minecraft mc, String nom, String[] e) {
        fr.cabane.client.casino.ClientCasino.machine = position(e);
        switch (nom) {
            case "camera" -> ClientCamera.ouvrir();
            case "reglages" -> mc.setScreen(new ClientPiaf.Reglages());
            case "caisse" -> fr.cabane.client.casino.CaisseScreen.ouvrir(position(e));
            case "ticket" -> {
                var t = new fr.cabane.client.casino.TicketScreen(new fr.cabane.casino.Ticket.Gratte(0, List.of(50, 10, 50, 20, 100, 50, 5, 20, 10), 50, 0));
                mc.setScreen(t);
                t.demo();
            }
            case "booster-scelle" -> {
                var ids = fr.cabane.tcg.Cartes.toutes().stream().map(fr.cabane.tcg.Cartes.Def::id).toList();
                mc.setScreen(new ClientCartes.BoosterScreen(List.of(ids.get(0), ids.get(5), ids.get(9), ids.get(14), ids.get(18))));
            }
            case "telephone" -> mc.setScreen(new ClientPiaf.Accueil());
            case "messages" -> mc.setScreen(new ClientMessages.Boite());
            case "conversation" -> mc.setScreen(new ClientMessages.Discussion(e[4], 0));
            case "galerie" -> mc.setScreen(new ClientPiaf.Galerie(false));
            case "visionneuse" -> mc.setScreen(new ClientPiaf.Visionneuse(ClientPiaf.galerie.getLast()));
            case "imprimer" -> mc.setScreen(new ClientPiaf.Imprimer(ClientPiaf.galerie.getLast()));
            case "contacts" -> mc.setScreen(new ClientPiaf.Contacts());
            case "recents" -> mc.setScreen(new ClientPiaf.Recents());
            case "booster-demo" -> {
                var ids = fr.cabane.tcg.Cartes.toutes().stream().map(fr.cabane.tcg.Cartes.Def::id).toList();
                ClientCartes.BoosterScreen ecranBooster = new ClientCartes.BoosterScreen(List.of(ids.get(0), ids.get(5), ids.get(9), ids.get(14), ids.get(18)));
                mc.setScreen(ecranBooster);
                ecranBooster.demo();
            }
            case "ticket-penalty", "ticket-morpion" -> {
                var gratte = nom.equals("ticket-penalty")
                        ? new fr.cabane.casino.Ticket.Gratte(fr.cabane.casino.Ticket.PENALTY, List.of(1, 0, 1, 1, 0), 100, 100)
                        : new fr.cabane.casino.Ticket.Gratte(fr.cabane.casino.Ticket.MORPION, List.of(1, 0, 0, 1, 1, 0, 1, 0, 1), 500, 500);
                var t = new fr.cabane.client.casino.TicketScreen(gratte);
                mc.setScreen(t);
                t.demo();
            }
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
            // « act eveil » / « act frappe » : la touche Éveil, le clic gauche du pouvoir ; « act vue 0|1|2 » : F5
            // « act avancer 1|0 » : appuyer (ou lâcher) la touche pour avancer
            case "avancer" -> {
                Minecraft.getInstance().options.keyUp.setDown(e[2].equals("1"));
                return 2;
            }
            case "gauche" -> {
                Minecraft.getInstance().options.keyLeft.setDown(e[2].equals("1"));
                return 2;
            }
            // « act accroupi 1|0 » : appuyer (ou lâcher) la touche pour s'accroupir ; « act clic-gauche » : un vrai clic gauche
            case "accroupi" -> {
                Minecraft.getInstance().options.keyShift.setDown(e[2].equals("1"));
                return 2;
            }
            case "clic-gauche" -> {
                net.minecraft.client.KeyMapping.click(Minecraft.getInstance().options.keyAttack.getKey());
                return 2;
            }
            // « act clic-droit » : un vrai clic droit ; « act sauter 1|0 » : appuyer (ou lâcher) la touche pour sauter
            case "clic-droit" -> {
                net.minecraft.client.KeyMapping.click(Minecraft.getInstance().options.keyUse.getKey());
                return 2;
            }
            case "sauter" -> {
                Minecraft.getInstance().options.keyJump.setDown(e[2].equals("1"));
                return 2;
            }
            // « act courir 1|0 » : garder (ou lâcher) la touche pour courir ; dans l'eau, il nage
            case "courir" -> {
                Minecraft.getInstance().options.keySprint.setDown(e[2].equals("1"));
                return 2;
            }
            // « act clic-droit-tenu 1|0 » : garder le clic droit enfoncé (pour manger), ou le lâcher
            case "clic-droit-tenu" -> {
                Minecraft.getInstance().options.keyUse.setDown(e[2].equals("1"));
                return 2;
            }
            case "eveil" -> {
                net.neoforged.neoforge.network.PacketDistributor.sendToServer(new fr.cabane.pouvoir.Pouvoirs.Eveil());
                return 2;
            }
            case "attraper" -> {
                net.neoforged.neoforge.network.PacketDistributor.sendToServer(new fr.cabane.pouvoir.Pouvoirs.Attraper());
                return 2;
            }
            // « act ulti » : la touche de l'ulti (Hachibi 4, l'ultime des fruits niveau 3)
            case "ulti" -> {
                fr.cabane.client.pouvoir.ClientFruit.toucheUlti();
                fr.cabane.client.pouvoir.ClientSharingan.toucheUlti();
                net.neoforged.neoforge.network.PacketDistributor.sendToServer(new fr.cabane.pouvoir.Hachibi4.Touche(fr.cabane.pouvoir.Hachibi4.ULTI));
                return 2;
            }
            case "charge" -> {
                net.neoforged.neoforge.network.PacketDistributor.sendToServer(new fr.cabane.pouvoir.Pouvoirs.Charge());
                return 2;
            }
            case "frappe" -> {
                net.neoforged.neoforge.network.PacketDistributor.sendToServer(new fr.cabane.pouvoir.Pouvoirs.Frappe());
                return 2;
            }
            // « act kunai » : le clic droit de Minato ; « act minato-demo » : son combo vu de l'extérieur, sur la créature la plus proche
            case "kunai" -> {
                net.neoforged.neoforge.network.PacketDistributor.sendToServer(new fr.cabane.pouvoir.Minato.Lancer());
                return 2;
            }
            // « act poser » / « act rejoindre » : accroupi + clic droit (la marque), s'accroupir (y aller)
            case "poser" -> {
                net.neoforged.neoforge.network.PacketDistributor.sendToServer(new fr.cabane.pouvoir.Minato.Poser());
                return 2;
            }
            case "rejoindre" -> {
                net.neoforged.neoforge.network.PacketDistributor.sendToServer(new fr.cabane.pouvoir.Minato.Rejoindre());
                return 2;
            }
            // « act jeter » : accroupi + clic gauche sans marque (un kunai lancé à la main)
            case "jeter" -> {
                net.neoforged.neoforge.network.PacketDistributor.sendToServer(new fr.cabane.pouvoir.Minato.Jeter());
                return 2;
            }
            case "minato-demo" -> {
                fr.cabane.client.pouvoir.ClientMinatoCombo.demo();
                return 2;
            }
            // Minato 2 (mode Kurama) : « act eclair » (accroupi + clic droit : TP où il regarde), « act poser-pieds » (un seul
            // appui accroupi : un kunai à ses pieds), « act bijuu-demo » (la Bijuu Dama vue de l'extérieur, sur la créature la plus proche)
            case "eclair" -> {
                net.neoforged.neoforge.network.PacketDistributor.sendToServer(new fr.cabane.pouvoir.Minato.Eclair());
                return 2;
            }
            case "poser-pieds" -> {
                net.neoforged.neoforge.network.PacketDistributor.sendToServer(new fr.cabane.pouvoir.Minato.PoserPieds());
                return 2;
            }
            case "bijuu-demo" -> {
                fr.cabane.client.pouvoir.ClientBijuuDama.demo();
                return 2;
            }
            // « act chidori » : le clic droit de la marque maudite niveau 2
            case "chidori" -> {
                net.neoforged.neoforge.network.PacketDistributor.sendToServer(new fr.cabane.pouvoir.Maudit.Lancer());
                return 2;
            }
            // « act saisir » : accroupi (un appui court) de Hachibi 2 au niveau 4, une tentacule attrape
            case "saisir" -> {
                net.neoforged.neoforge.network.PacketDistributor.sendToServer(new fr.cabane.pouvoir.Pouvoirs.Charge());
                return 2;
            }
            // « act bombe-noire » : accroupi + clic droit de Hachibi 2 au niveau 4
            case "bombe-noire" -> {
                net.neoforged.neoforge.network.PacketDistributor.sendToServer(new fr.cabane.pouvoir.Hachibi.ClicAccroupi());
                return 2;
            }
            case "vue" -> Minecraft.getInstance().options.setCameraType(net.minecraft.client.CameraType.values()[Integer.parseInt(e[2])]);
            case "recul" -> recul = Float.parseFloat(e[2]);
            case "regard" -> {
                // tourne la tête sans bouger (un point de vue ramènerait le joueur) ; « act regard lacet tangage 1 » :
                // n'attend qu'un tick (pour voir passer un tir sous un autre angle)
                var j = Minecraft.getInstance().player;
                j.setYRot(Float.parseFloat(e[2]));
                j.setXRot(Float.parseFloat(e[3]));
                j.yRotO = j.getYRot();
                j.xRotO = j.getXRot();
                if (e.length > 4) {
                    return Integer.parseInt(e[4]);
                }
            }
            case "numero" -> net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                    new fr.cabane.telephone.PhoneNetwork.Action(fr.cabane.telephone.PhoneNetwork.Action.CHOISIR_NUMERO, reste));
            case "contact" -> net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                    new fr.cabane.telephone.PhoneNetwork.Action(fr.cabane.telephone.PhoneNetwork.Action.AJOUTER_CONTACT,
                            e[2] + "|" + String.join(" ", java.util.Arrays.copyOfRange(e, 3, e.length))));
            case "pseudo" -> ClientPiaf.demander(1, reste, 0);
            case "photo" -> ClientPiaf.prendrePhoto();
            case "publier" -> ClientPiaf.demander(2, reste, ClientPiaf.galerie.isEmpty() ? 0 : ClientPiaf.galerie.getLast());
            case "texte" -> ClientPiaf.demander(2, reste, 0);
            case "sms" -> ClientMessages.demander(fr.cabane.telephone.Messages.Action.ENVOYER, e[2],
                    String.join(" ", java.util.Arrays.copyOfRange(e, 3, e.length)), 0);
            case "mms" -> ClientMessages.demander(fr.cabane.telephone.Messages.Action.ENVOYER, e[2], "", ClientPiaf.galerie.getLast());
            case "mode" -> ClientCamera.regler(Integer.parseInt(e[2]), Integer.parseInt(e[3]));
            case "declencher" -> ClientCamera.declencherPourCaptures();
            case "imprimer" -> {
                // « act imprimer largeur hauteur [n] » : la n-ième photo en partant de la dernière
                // (ou « #id » : cette photo-là)
                long id = e.length > 4 && e[4].startsWith("#") ? Long.parseLong(e[4].substring(1))
                        : ClientPiaf.galerie.get(ClientPiaf.galerie.size() - 1 - (e.length > 4 ? Integer.parseInt(e[4]) : 0));
                net.neoforged.neoforge.network.PacketDistributor.sendToServer(new fr.cabane.photo.Impression.Demande(id, Integer.parseInt(e[2]), Integer.parseInt(e[3])));
            }
            case "tenir-photo" -> {
                // « act tenir-photo largeur hauteur » : met en main une copie de la photo imprimée à cette taille
                Minecraft mc = Minecraft.getInstance();
                var inv = mc.player.getInventory();
                for (int i = 0; i < inv.items.size(); i++) {
                    var p = inv.items.get(i).get(fr.cabane.CabaneComponents.PHOTO.get());
                    if (p != null && p.largeur() == Integer.parseInt(e[2]) && p.hauteur() == Integer.parseInt(e[3])) {
                        commande(mc, "item replace entity @s weapon.mainhand from entity @s container." + i);
                        break;
                    }
                }
            }
            case "accrocher" -> {
                // « act accrocher x y z face » : clic droit avec l'objet en main sur la face d'un bloc
                Minecraft mc = Minecraft.getInstance();
                var bloc = new net.minecraft.core.BlockPos(Integer.parseInt(e[2]), Integer.parseInt(e[3]), Integer.parseInt(e[4]));
                var face = net.minecraft.core.Direction.byName(e[5]);
                mc.gameMode.useItemOn(mc.player, net.minecraft.world.InteractionHand.MAIN_HAND,
                        new net.minecraft.world.phys.BlockHitResult(net.minecraft.world.phys.Vec3.atCenterOf(bloc).relative(face, 0.5), face, bloc, false));
            }
            case "utiliser" -> {
                Minecraft mc = Minecraft.getInstance();
                mc.gameMode.useItem(mc.player, net.minecraft.world.InteractionHand.MAIN_HAND);
            }
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
