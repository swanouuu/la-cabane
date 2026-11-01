package fr.cabane;

import fr.cabane.boutique.Boutique;
import fr.cabane.boutique.Contrats;
import fr.cabane.casino.Blackjack;
import fr.cabane.economie.Economie;
import fr.cabane.economie.EconomieCommands;
import fr.cabane.hub.HubEvents;
import fr.cabane.tcg.Cartes;
import fr.cabane.telephone.PhoneCalls;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.AddReloadListenerEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

@EventBusSubscriber(modid = Cabane.MODID)
public final class CabaneEvents {

    @SubscribeEvent
    public static void onReloadListeners(AddReloadListenerEvent event) {
        event.addListener(Boutique.INSTANCE);
        event.addListener(Contrats.INSTANCE);
        event.addListener(Cartes.INSTANCE);
        event.addListener(fr.cabane.boutique.Forge.INSTANCE);
    }

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        HubEvents.onServerStarted(event.getServer());
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        PhoneCalls.clear();
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        PhoneCalls.tick(event.getServer());
        HubEvents.tick(event.getServer());
        fr.cabane.boutique.Contrats.tick(event.getServer());
    }

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof net.minecraft.server.level.ServerPlayer joueur) {
            Boutique.envoyerVitrine(joueur);
        }
        if (event.getEntity() instanceof ServerPlayer player) {
            Economie.connexion(player);
            HubEvents.accueillir(player);
            PhoneCalls.sendState(player);
        }
    }

    @SubscribeEvent
    public static void onRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (event.getEntity() instanceof ServerPlayer player && !event.isEndConquered()) {
            HubEvents.reapparition(player);
        }
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            PhoneCalls.onLogout(player);
            Blackjack.deconnexion(player);
        }
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        CabaneCommands.register(event.getDispatcher());
        EconomieCommands.register(event.getDispatcher());
    }

    private CabaneEvents() {
    }
}
