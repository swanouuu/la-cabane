package fr.cabane.telephone;

import fr.cabane.Cabane;
import fr.cabane.CabaneComponents;
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
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Les appels en cours. La voix passe par Simple Voice Chat (voir {@link CabaneVoicePlugin}) :
 * pendant un appel, chaque joueur entend l'autre où qu'il soit sur la map.
 */
public final class PhoneCalls {
    public static final Pattern NUMBER = Pattern.compile("\\d{3,4}");
    /** Ce que voit celui qui reçoit un appel masqué. */
    public static final String MASQUE = "Masqué";
    private static final int RING_TICKS = 30 * 20;

    private enum Etat { SONNE, EN_LIGNE }

    private static final class Call {
        final UUID caller;
        final UUID callee;
        final String numeroAppelant;
        final String numeroAppele;
        final boolean masque;
        volatile Etat etat = Etat.SONNE;
        int ticks;

        Call(UUID caller, UUID callee, String numeroAppelant, String numeroAppele, boolean masque) {
            this.caller = caller;
            this.callee = callee;
            this.numeroAppelant = numeroAppelant;
            this.numeroAppele = numeroAppele;
            this.masque = masque;
        }

        /** Le numéro de ce participant, vu par l'autre (masqué si l'appelant l'a demandé). */
        String numeroDe(UUID player) {
            return caller.equals(player) ? (masque ? MASQUE : numeroAppelant) : numeroAppele;
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
            case PhoneNetwork.Action.APPELER -> {
                String[] parts = action.arg().split("\\|");
                dial(player, parts[0], parts.length > 1 && parts[1].equals("masque"));
            }
            case PhoneNetwork.Action.DECROCHER -> answer(player);
            case PhoneNetwork.Action.RACCROCHER -> hangUp(player);
            case PhoneNetwork.Action.AJOUTER_CONTACT -> {
                String[] parts = action.arg().split("\\|", 2);
                String nom = parts.length > 1 ? parts[1].strip().replace("§", "") : "";
                String sim = numero(player);
                if (sim != null && parts.length == 2 && NUMBER.matcher(parts[0]).matches() && !nom.isEmpty() && nom.length() <= 24) {
                    PhoneData.get(player.server).ajouterContact(sim, parts[0], nom);
                }
                sendState(player);
            }
            case PhoneNetwork.Action.SUPPRIMER_CONTACT -> {
                String sim = numero(player);
                if (sim != null) {
                    PhoneData.get(player.server).supprimerContact(sim, action.arg());
                }
                sendState(player);
            }
            default -> sendState(player);
        }
    }

    /** Active la SIM du téléphone utilisé : une seule fois, le numéro ne change plus jamais. */
    public static void setNumber(ServerPlayer player, String number) {
        ItemStack telephone = telephoneActif(player);
        PhoneData data = PhoneData.get(player.server);
        if (telephone == null) {
            info(player, "Il te faut un téléphone.");
        } else if (telephone.has(CabaneComponents.NUMERO)) {
            info(player, "Ce téléphone a déjà sa SIM : le " + telephone.get(CabaneComponents.NUMERO)
                    + ". Pour un autre numéro, il faut un autre téléphone.");
        } else if (!NUMBER.matcher(number).matches()) {
            info(player, "Ton numéro doit faire 3 ou 4 chiffres.");
        } else if (!data.activer(number, player.getGameProfile().getName())) {
            info(player, "Le " + number + " est déjà pris, choisis-en un autre.");
        } else {
            telephone.set(CabaneComponents.NUMERO, number);
            player.displayClientMessage(Component.literal("☎ SIM activée : ce téléphone a le numéro " + number + ", pour toujours.")
                    .withStyle(ChatFormatting.GREEN), false);
        }
        sendState(player);
    }

    public static void dial(ServerPlayer caller, String number) {
        dial(caller, number, false);
    }

    public static void dial(ServerPlayer caller, String number, boolean masque) {
        MinecraftServer server = caller.server;
        PhoneData data = PhoneData.get(server);
        String myNumber = numero(caller);
        ServerPlayer callee = porteur(server, number);

        if (telephoneActif(caller) == null) {
            info(caller, "Il te faut un téléphone dans l'inventaire.");
        } else if (myNumber == null) {
            info(caller, "Active d'abord la SIM de ton téléphone.");
        } else if (CALLS.containsKey(caller.getUUID())) {
            info(caller, "Tu es déjà en ligne.");
        } else if (!data.existe(number)) {
            info(caller, "Le " + number + " n'est pas attribué.");
        } else if (number.equals(myNumber) || callee == caller) {
            info(caller, "Tu ne peux pas t'appeler toi-même.");
        } else if (callee == null) {
            info(caller, "Le " + number + " est injoignable pour le moment.");
        } else if (CALLS.containsKey(callee.getUUID())) {
            info(caller, "Ça sonne occupé…");
        } else {
            UUID calleeId = callee.getUUID();
            Call call = new Call(caller.getUUID(), calleeId, myNumber, number, masque);
            CALLS.put(caller.getUUID(), call);
            CALLS.put(calleeId, call);
            data.ajouterRecent(myNumber, number, PhoneData.SORTANT);
            data.ajouterRecent(number, masque ? MASQUE : myNumber, PhoneData.ENTRANT);
            String qui = masque ? "d'un numéro masqué" : data.contacts(number).getOrDefault(myNumber, "du " + myNumber);
            callee.displayClientMessage(Component.literal("☎ Appel entrant " + (qui.startsWith("du ") || qui.startsWith("d'un") ? qui : "de " + qui)
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
            } else if (!aLeNumero(caller, call.numeroAppelant) || !aLeNumero(callee, call.numeroAppele)) {
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
            PhoneData.get(server).marquerManque(call.numeroAppele, call.masque ? MASQUE : call.numeroAppelant);
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
        String myNumber = numero(player);
        Call call = CALLS.get(player.getUUID());
        int state = PhoneNetwork.State.LIBRE;
        String other = "";
        if (call != null) {
            other = call.numeroDe(call.other(player.getUUID()));
            if (call.etat == Etat.EN_LIGNE) {
                state = PhoneNetwork.State.EN_LIGNE;
            } else {
                state = call.caller.equals(player.getUUID()) ? PhoneNetwork.State.APPEL_SORTANT : PhoneNetwork.State.APPEL_ENTRANT;
            }
        }
        PacketDistributor.sendToPlayer(player, new PhoneNetwork.State(myNumber == null ? "" : myNumber, state, other));
        String sim = myNumber == null ? "" : myNumber;
        List<PhoneNetwork.Contact> contacts = data.contacts(sim).entrySet().stream()
                .map(e -> new PhoneNetwork.Contact(e.getKey(), e.getValue())).toList();
        List<PhoneNetwork.Appel> recents = data.recents(sim).stream()
                .map(r -> new PhoneNetwork.Appel(r.numero(), r.type(), r.temps())).toList();
        PacketDistributor.sendToPlayer(player, new PhoneNetwork.Carnet(contacts, recents));
    }

    /** Le téléphone qu'on utilise : celui en main, sinon le premier de l'inventaire. */
    public static ItemStack telephoneActif(net.minecraft.world.entity.player.Player player) {
        for (InteractionHand main : InteractionHand.values()) {
            if (player.getItemInHand(main).is(CabaneItems.TELEPHONE.get())) {
                return player.getItemInHand(main);
            }
        }
        for (ItemStack stack : player.getInventory().items) {
            if (stack.is(CabaneItems.TELEPHONE.get())) {
                return stack;
            }
        }
        return null;
    }

    /** Le numéro de la SIM du téléphone utilisé, ou null. */
    public static String numero(ServerPlayer player) {
        ItemStack telephone = telephoneActif(player);
        return telephone == null ? null : telephone.get(CabaneComponents.NUMERO);
    }

    static boolean aLeNumero(ServerPlayer player, String numero) {
        return player.getInventory().hasAnyMatching(stack -> stack.is(CabaneItems.TELEPHONE.get())
                && numero.equals(stack.get(CabaneComponents.NUMERO)));
    }

    /** Le joueur connecté qui a sur lui le téléphone de ce numéro. */
    static ServerPlayer porteur(MinecraftServer server, String numero) {
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (aLeNumero(p, numero)) {
                return p;
            }
        }
        return null;
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
