package fr.cabane.telephone;

import fr.cabane.Cabane;
import fr.cabane.CabaneItems;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import net.minecraft.ChatFormatting;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Les appels en cours. La voix passe par Simple Voice Chat (voir {@link CabaneVoicePlugin}) :
 * pendant un appel, chaque joueur entend l'autre où qu'il soit sur la map.
 */
public final class PhoneCalls {
    public static final Pattern NUMBER = Pattern.compile("\\d{3,4}");
    private static final int RING_TICKS = 30 * 20;

    private enum Etat { SONNE, EN_LIGNE }

    private static final class Call {
        final UUID caller;
        final UUID callee;
        volatile Etat etat = Etat.SONNE;
        int ticks;

        Call(UUID caller, UUID callee) {
            this.caller = caller;
            this.callee = callee;
        }

        UUID other(UUID player) {
            return caller.equals(player) ? callee : caller;
        }
    }

    // Chaque participant pointe vers le même appel. Lu aussi par le thread de Simple Voice Chat.
    private static final Map<UUID, Call> CALLS = new ConcurrentHashMap<>();

    /** Avec qui ce joueur est en ligne, ou null. Appelé depuis le thread audio. */
    public static UUID activePartner(UUID player) {
        Call call = CALLS.get(player);
        return call != null && call.etat == Etat.EN_LIGNE ? call.other(player) : null;
    }

    public static void handle(ServerPlayer player, PhoneNetwork.Action action) {
        switch (action.action()) {
            case PhoneNetwork.Action.CHOISIR_NUMERO -> setNumber(player, action.arg());
            case PhoneNetwork.Action.APPELER -> dial(player, action.arg());
            case PhoneNetwork.Action.DECROCHER -> answer(player);
            case PhoneNetwork.Action.RACCROCHER -> hangUp(player);
            case PhoneNetwork.Action.AJOUTER_CONTACT -> {
                String[] parts = action.arg().split("\\|", 2);
                String nom = parts.length > 1 ? parts[1].strip().replace("§", "") : "";
                if (parts.length == 2 && NUMBER.matcher(parts[0]).matches() && !nom.isEmpty() && nom.length() <= 24) {
                    PhoneData.get(player.server).ajouterContact(player.getUUID(), parts[0], nom);
                }
                sendState(player);
            }
            case PhoneNetwork.Action.SUPPRIMER_CONTACT -> {
                PhoneData.get(player.server).supprimerContact(player.getUUID(), action.arg());
                sendState(player);
            }
            default -> sendState(player);
        }
    }

    public static void setNumber(ServerPlayer player, String number) {
        if (!NUMBER.matcher(number).matches()) {
            info(player, "Ton numéro doit faire 3 ou 4 chiffres.");
        } else if (CALLS.containsKey(player.getUUID())) {
            info(player, "Raccroche d'abord avant de changer de numéro.");
        } else if (!PhoneData.get(player.server).assign(player.getUUID(), player.getGameProfile().getName(), number)) {
            info(player, "Le " + number + " est déjà pris, choisis-en un autre.");
        } else {
            player.displayClientMessage(Component.literal("☎ Ton numéro est maintenant le " + number + ".").withStyle(ChatFormatting.GREEN), false);
        }
        sendState(player);
    }

    public static void dial(ServerPlayer caller, String number) {
        MinecraftServer server = caller.server;
        PhoneData data = PhoneData.get(server);
        String myNumber = data.numberOf(caller.getUUID());
        UUID calleeId = data.ownerOf(number);
        ServerPlayer callee = calleeId == null ? null : server.getPlayerList().getPlayer(calleeId);

        if (myNumber == null) {
            info(caller, "Choisis d'abord ton numéro.");
        } else if (!hasPhone(caller)) {
            info(caller, "Il te faut un téléphone dans l'inventaire.");
        } else if (CALLS.containsKey(caller.getUUID())) {
            info(caller, "Tu es déjà en ligne.");
        } else if (calleeId == null) {
            info(caller, "Le " + number + " n'est pas attribué.");
        } else if (calleeId.equals(caller.getUUID())) {
            info(caller, "Tu ne peux pas t'appeler toi-même.");
        } else if (callee == null || !hasPhone(callee)) {
            info(caller, "Le " + number + " est injoignable pour le moment.");
        } else if (CALLS.containsKey(calleeId)) {
            info(caller, "Ça sonne occupé…");
        } else {
            Call call = new Call(caller.getUUID(), calleeId);
            CALLS.put(caller.getUUID(), call);
            CALLS.put(calleeId, call);
            data.ajouterRecent(caller.getUUID(), number, PhoneData.SORTANT);
            data.ajouterRecent(calleeId, myNumber, PhoneData.ENTRANT);
            String qui = data.contacts(calleeId).getOrDefault(myNumber, "du " + myNumber);
            callee.displayClientMessage(Component.literal("☎ Appel entrant " + (qui.startsWith("du ") ? qui : "de " + qui)
                    + " : ouvre ton téléphone (touche N) pour décrocher !").withStyle(ChatFormatting.YELLOW), false);
            sendState(callee);
        }
        sendState(caller);
    }

    public static void answer(ServerPlayer player) {
        Call call = CALLS.get(player.getUUID());
        if (call == null || call.etat != Etat.SONNE || !call.callee.equals(player.getUUID())) {
            sendState(player);
            return;
        }
        call.etat = Etat.EN_LIGNE;
        call.ticks = 0;
        for (UUID id : List.of(call.caller, call.callee)) {
            ServerPlayer p = player.server.getPlayerList().getPlayer(id);
            if (p == null) {
                continue;
            }
            p.displayClientMessage(Component.literal("☎ En ligne ! Parle avec ta touche de Voice Chat.").withStyle(ChatFormatting.GREEN), true);
            if (!VoiceBridge.isConnected(id)) {
                info(p, "Ton Voice Chat n'est pas connecté : l'autre ne t'entendra pas.");
            }
            premierAppel(p);
            sendState(p);
        }
    }

    public static void hangUp(ServerPlayer player) {
        Call call = CALLS.get(player.getUUID());
        if (call == null) {
            sendState(player);
            return;
        }
        end(player.server, call, call.etat == Etat.SONNE && call.callee.equals(player.getUUID()) ? "Appel refusé." : "Appel terminé.");
    }

    public static void onLogout(ServerPlayer player) {
        Call call = CALLS.get(player.getUUID());
        if (call != null) {
            end(player.server, call, "La communication a été coupée.");
        }
    }

    public static void clear() {
        CALLS.clear();
    }

    public static void tick(MinecraftServer server) {
        if (CALLS.isEmpty()) {
            return;
        }
        for (Call call : new HashSet<>(CALLS.values())) {
            call.ticks++;
            ServerPlayer caller = server.getPlayerList().getPlayer(call.caller);
            ServerPlayer callee = server.getPlayerList().getPlayer(call.callee);
            if (caller == null || callee == null) {
                end(server, call, "La communication a été coupée.");
            } else if (call.etat == Etat.SONNE) {
                if (call.ticks >= RING_TICKS) {
                    end(server, call, "Pas de réponse.");
                } else {
                    ring(caller, callee, call.ticks % 40);
                }
            } else if (!hasPhone(caller) || !hasPhone(callee)) {
                end(server, call, "Un téléphone a quitté l'inventaire : appel coupé.");
            }
        }
    }

    /** La sonnerie s'entend autour de celui qu'on appelle ; l'appelant entend la tonalité. */
    private static void ring(ServerPlayer caller, ServerPlayer callee, int t) {
        if (t == 0 || t == 5) {
            callee.level().playSound(null, callee.getX(), callee.getY(), callee.getZ(),
                    SoundEvents.NOTE_BLOCK_BELL.value(), SoundSource.PLAYERS, 1.0F, t == 0 ? 1.5F : 1.2F);
        }
        if (t == 0) {
            caller.playNotifySound(SoundEvents.NOTE_BLOCK_BIT.value(), SoundSource.PLAYERS, 0.4F, 0.7F);
        }
        if (t == 0) {
            callee.displayClientMessage(Component.literal("☎ Ça sonne… (touche N)").withStyle(ChatFormatting.YELLOW), true);
        }
    }

    private static void end(MinecraftServer server, Call call, String reason) {
        CALLS.remove(call.caller, call);
        CALLS.remove(call.callee, call);
        if (call.etat == Etat.SONNE) {
            PhoneData data = PhoneData.get(server);
            String numeroAppelant = data.numberOf(call.caller);
            if (numeroAppelant != null) {
                data.marquerManque(call.callee, numeroAppelant);
            }
        }
        for (UUID id : List.of(call.caller, call.callee)) {
            ServerPlayer p = server.getPlayerList().getPlayer(id);
            if (p != null) {
                p.displayClientMessage(Component.literal("☎ " + reason).withStyle(ChatFormatting.GRAY), true);
                sendState(p);
            }
        }
    }

    public static void sendState(ServerPlayer player) {
        PhoneData data = PhoneData.get(player.server);
        String myNumber = data.numberOf(player.getUUID());
        Call call = CALLS.get(player.getUUID());
        int state = PhoneNetwork.State.LIBRE;
        String other = "";
        if (call != null) {
            UUID otherId = call.other(player.getUUID());
            other = data.numberOf(otherId) == null ? "???" : data.numberOf(otherId);
            if (call.etat == Etat.EN_LIGNE) {
                state = PhoneNetwork.State.EN_LIGNE;
            } else {
                state = call.caller.equals(player.getUUID()) ? PhoneNetwork.State.APPEL_SORTANT : PhoneNetwork.State.APPEL_ENTRANT;
            }
        }
        PacketDistributor.sendToPlayer(player, new PhoneNetwork.State(myNumber == null ? "" : myNumber, state, other));
        List<PhoneNetwork.Contact> contacts = data.contacts(player.getUUID()).entrySet().stream()
                .map(e -> new PhoneNetwork.Contact(e.getKey(), e.getValue())).toList();
        List<PhoneNetwork.Appel> recents = data.recents(player.getUUID()).stream()
                .map(r -> new PhoneNetwork.Appel(r.numero(), r.type(), r.temps())).toList();
        PacketDistributor.sendToPlayer(player, new PhoneNetwork.Carnet(contacts, recents));
    }

    static boolean hasPhone(ServerPlayer player) {
        return player.getInventory().hasAnyMatching(stack -> stack.is(CabaneItems.TELEPHONE.get()));
    }

    private static void premierAppel(ServerPlayer player) {
        AdvancementHolder advancement = player.server.getAdvancements().get(Cabane.id("premier_appel"));
        if (advancement != null) {
            player.getAdvancements().award(advancement, "appel");
        }
    }

    private static void info(ServerPlayer player, String message) {
        player.displayClientMessage(Component.literal("☎ " + message).withStyle(ChatFormatting.RED), false);
    }

    private PhoneCalls() {
    }
}
