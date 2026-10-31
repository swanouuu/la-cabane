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
        if (index >= 0 && !ecran) {
            bloquerCamera(mc, etapes.get(index));
        }
        if (--attente > 0) {
            return;
        }
        if (index >= 0) {
            String[] e = etapes.get(index);
            Screenshot.grab(mc.gameDirectory, (ecran ? e[1] : e[0]) + ".png", mc.getMainRenderTarget(), message -> { });
            if (ecran) {
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
        if (e[0].equals("ui")) {
            // « ui <nom> cmd <commande…> » ou « ui <nom> ecran <écran> » : capture avec l'interface
            mc.options.hideGui = false;
            if (e[2].equals("cmd")) {
                commande(mc, String.join(" ", java.util.Arrays.copyOfRange(e, 3, e.length)));
            } else {
                ouvrir(mc, e[3]);
            }
            attente = 50;
            return;
        }
        commande(mc, "time set " + (e.length > 6 ? e[6] : "6000"));
        commande(mc, "tp @s " + e[1] + " " + e[2] + " " + e[3] + " " + e[4] + " " + e[5]);
        attente = ATTENTE_POINT;
    }

    private static void ouvrir(Minecraft mc, String nom) {
        switch (nom) {
            case "telephone" -> mc.setScreen(new ClientPiaf.Accueil());
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

    private static void commande(Minecraft mc, String commande) {
        mc.player.connection.sendCommand(commande);
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
