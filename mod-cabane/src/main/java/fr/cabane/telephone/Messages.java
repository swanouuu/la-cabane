package fr.cabane.telephone;

import fr.cabane.Cabane;
import fr.cabane.client.ClientMessages;
import io.netty.buffer.ByteBuf;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import net.minecraft.ChatFormatting;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.saveddata.SavedData;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/**
 * Les SMS : chaque SIM garde ses conversations (comme ses contacts, elles restent dans le téléphone).
 * Un message peut porter une photo de la galerie, qui arrive alors dans la galerie de l'autre.
 */
public final class Messages {
    public static final int TEXTE_MAX = 300;
    private static final int PAR_CONVERSATION = 60;
    private static final int CONVERSATIONS = 40;

    public record Sms(boolean moi, String texte, long photo, long temps) {
        public static final StreamCodec<ByteBuf, Sms> CODEC = StreamCodec.composite(
                ByteBufCodecs.BOOL, Sms::moi,
                ByteBufCodecs.stringUtf8(TEXTE_MAX * 4), Sms::texte,
                ByteBufCodecs.VAR_LONG, Sms::photo,
                ByteBufCodecs.VAR_LONG, Sms::temps,
                Sms::new);
    }

    // ---------------------------------------------------------------- données

    public static class Donnees extends SavedData {
        /** SIM → (numéro de l'autre → messages, du plus ancien au plus récent). */
        final Map<String, LinkedHashMap<String, LinkedList<Sms>>> boites = new HashMap<>();
        /** SIM → (numéro de l'autre → messages pas encore lus). */
        final Map<String, Map<String, Integer>> nonLus = new HashMap<>();

        static Donnees get(MinecraftServer server) {
            return server.overworld().getDataStorage().computeIfAbsent(new SavedData.Factory<>(Donnees::new, Donnees::load, null), "cabane_sms");
        }

        private static Donnees load(CompoundTag tag, HolderLookup.Provider registries) {
            Donnees d = new Donnees();
            for (Tag t : tag.getList("sims", Tag.TAG_COMPOUND)) {
                CompoundTag s = (CompoundTag) t;
                LinkedHashMap<String, LinkedList<Sms>> boite = new LinkedHashMap<>();
                Map<String, Integer> lus = new HashMap<>();
                for (Tag c : s.getList("conversations", Tag.TAG_COMPOUND)) {
                    CompoundTag conv = (CompoundTag) c;
                    LinkedList<Sms> liste = new LinkedList<>();
                    for (Tag m : conv.getList("messages", Tag.TAG_COMPOUND)) {
                        CompoundTag sms = (CompoundTag) m;
                        liste.add(new Sms(sms.getBoolean("moi"), sms.getString("texte"), sms.getLong("photo"), sms.getLong("temps")));
                    }
                    boite.put(conv.getString("numero"), liste);
                    if (conv.getInt("nonLus") > 0) {
                        lus.put(conv.getString("numero"), conv.getInt("nonLus"));
                    }
                }
                d.boites.put(s.getString("sim"), boite);
                d.nonLus.put(s.getString("sim"), lus);
            }
            return d;
        }

        @Override
        public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
            ListTag sims = new ListTag();
            boites.forEach((sim, boite) -> {
                CompoundTag s = new CompoundTag();
                s.putString("sim", sim);
                ListTag convs = new ListTag();
                boite.forEach((numero, liste) -> {
                    CompoundTag conv = new CompoundTag();
                    conv.putString("numero", numero);
                    conv.putInt("nonLus", nonLus.getOrDefault(sim, Map.of()).getOrDefault(numero, 0));
                    ListTag messages = new ListTag();
                    for (Sms m : liste) {
                        CompoundTag sms = new CompoundTag();
                        sms.putBoolean("moi", m.moi());
                        sms.putString("texte", m.texte());
                        sms.putLong("photo", m.photo());
                        sms.putLong("temps", m.temps());
                        messages.add(sms);
                    }
                    conv.put("messages", messages);
                    convs.add(conv);
                });
                s.put("conversations", convs);
                sims.add(s);
            });
            tag.put("sims", sims);
            return tag;
        }

        /** Range un message dans une conversation, qui remonte en tête de la boîte. */
        void ranger(String sim, String autre, Sms sms, boolean lu) {
            LinkedHashMap<String, LinkedList<Sms>> boite = boites.computeIfAbsent(sim, k -> new LinkedHashMap<>());
            LinkedList<Sms> liste = boite.remove(autre);
            if (liste == null) {
                liste = new LinkedList<>();
            }
            liste.add(sms);
            while (liste.size() > PAR_CONVERSATION) {
                liste.removeFirst();
            }
            boite.put(autre, liste);
            while (boite.size() > CONVERSATIONS) {
                String plusVieille = boite.keySet().iterator().next();
                boite.remove(plusVieille);
                nonLus.getOrDefault(sim, new HashMap<>()).remove(plusVieille);
            }
            if (!lu) {
                nonLus.computeIfAbsent(sim, k -> new HashMap<>()).merge(autre, 1, Integer::sum);
            }
            setDirty();
        }
    }

    // ---------------------------------------------------------------- réseau

    /** Client → serveur : 0 la boîte, 1 ouvrir une conversation, 2 envoyer (texte et/ou photo), 3 supprimer une conversation. */
    public record Action(int action, String numero, String texte, long photo) implements CustomPacketPayload {
        public static final int BOITE = 0;
        public static final int OUVRIR = 1;
        public static final int ENVOYER = 2;
        public static final int SUPPRIMER = 3;
        public static final Type<Action> TYPE = new Type<>(Cabane.id("sms_action"));
        public static final StreamCodec<ByteBuf, Action> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, Action::action,
                ByteBufCodecs.stringUtf8(8), Action::numero,
                ByteBufCodecs.stringUtf8(TEXTE_MAX * 4), Action::texte,
                ByteBufCodecs.VAR_LONG, Action::photo,
                Action::new);

        @Override
        public Type<Action> type() {
            return TYPE;
        }
    }

    /** Une ligne de la boîte : avec qui, le dernier message, quand, combien de non lus. */
    public record Resume(String numero, String apercu, long temps, int nonLus) {
        public static final StreamCodec<ByteBuf, Resume> CODEC = StreamCodec.composite(
                ByteBufCodecs.stringUtf8(8), Resume::numero,
                ByteBufCodecs.stringUtf8(256), Resume::apercu,
                ByteBufCodecs.VAR_LONG, Resume::temps,
                ByteBufCodecs.VAR_INT, Resume::nonLus,
                Resume::new);
    }

    public record Boite(List<Resume> conversations) implements CustomPacketPayload {
        public static final Type<Boite> TYPE = new Type<>(Cabane.id("sms_boite"));
        public static final StreamCodec<ByteBuf, Boite> CODEC = Resume.CODEC.apply(ByteBufCodecs.list(CONVERSATIONS)).map(Boite::new, Boite::conversations);

        @Override
        public Type<Boite> type() {
            return TYPE;
        }
    }

    public record Conversation(String numero, List<Sms> messages) implements CustomPacketPayload {
        public static final Type<Conversation> TYPE = new Type<>(Cabane.id("sms_conversation"));
        public static final StreamCodec<ByteBuf, Conversation> CODEC = StreamCodec.composite(
                ByteBufCodecs.stringUtf8(8), Conversation::numero,
                Sms.CODEC.apply(ByteBufCodecs.list(PAR_CONVERSATION)), Conversation::messages,
                Conversation::new);

        @Override
        public Type<Conversation> type() {
            return TYPE;
        }
    }

    /** Serveur → client : un SMS vient d'arriver (pour la notification). */
    public record Recu(String numero, String apercu) implements CustomPacketPayload {
        public static final Type<Recu> TYPE = new Type<>(Cabane.id("sms_recu"));
        public static final StreamCodec<ByteBuf, Recu> CODEC = StreamCodec.composite(
                ByteBufCodecs.stringUtf8(8), Recu::numero,
                ByteBufCodecs.stringUtf8(256), Recu::apercu,
                Recu::new);

        @Override
        public Type<Recu> type() {
            return TYPE;
        }
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        event.registrar("1")
                .playToClient(Boite.TYPE, Boite.CODEC, (p, ctx) -> ctx.enqueueWork(() -> ClientMessages.boite(p)))
                .playToClient(Conversation.TYPE, Conversation.CODEC, (p, ctx) -> ctx.enqueueWork(() -> ClientMessages.conversation(p)))
                .playToClient(Recu.TYPE, Recu.CODEC, (p, ctx) -> ctx.enqueueWork(() -> ClientMessages.recu(p)))
                .playToServer(Action.TYPE, Action.CODEC, (p, ctx) -> ctx.enqueueWork(() -> {
                    if (ctx.player() instanceof ServerPlayer joueur) {
                        agir(joueur, p);
                    }
                }));
    }

    // ---------------------------------------------------------------- logique

    private static final Map<String, Long> DERNIER = new HashMap<>();

    private static void agir(ServerPlayer joueur, Action a) {
        String sim = PhoneCalls.numero(joueur);
        if (sim == null) {
            joueur.displayClientMessage(Component.literal("Active d'abord la SIM de ton téléphone.").withStyle(ChatFormatting.RED), true);
            return;
        }
        Donnees d = Donnees.get(joueur.server);
        switch (a.action()) {
            case Action.OUVRIR -> {
                if (d.nonLus.getOrDefault(sim, new HashMap<>()).remove(a.numero()) != null) {
                    d.setDirty();
                }
                envoyerConversation(joueur, sim, a.numero());
            }
            case Action.ENVOYER -> envoyer(joueur, sim, d, a);
            case Action.SUPPRIMER -> {
                if (d.boites.getOrDefault(sim, new LinkedHashMap<>()).remove(a.numero()) != null) {
                    d.nonLus.getOrDefault(sim, new HashMap<>()).remove(a.numero());
                    d.setDirty();
                }
            }
            default -> {
            }
        }
        envoyerBoite(joueur, sim);
    }

    private static void envoyer(ServerPlayer joueur, String sim, Donnees d, Action a) {
        String numero = a.numero();
        String texte = a.texte().replace("§", "").strip();
        if (texte.length() > TEXTE_MAX) {
            texte = texte.substring(0, TEXTE_MAX);
        }
        long photo = a.photo() != 0 && Piaf.dansGalerie(joueur.server, sim, a.photo()) ? a.photo() : 0;
        if (texte.isEmpty() && photo == 0) {
            return;
        }
        if (!PhoneData.get(joueur.server).existe(numero)) {
            joueur.displayClientMessage(Component.literal("Le " + numero + " n'est attribué à personne.").withStyle(ChatFormatting.RED), true);
            return;
        }
        long maintenant = System.currentTimeMillis();
        if (maintenant - DERNIER.getOrDefault(sim, 0L) < 800) {
            return;
        }
        DERNIER.put(sim, maintenant);
        d.ranger(sim, numero, new Sms(true, texte, photo, maintenant), true);
        if (!numero.equals(sim)) {
            d.ranger(numero, sim, new Sms(false, texte, photo, maintenant), false);
            if (photo != 0) {
                Piaf.ajouterGalerie(joueur.server, numero, photo);
            }
            ServerPlayer destinataire = PhoneCalls.porteur(joueur.server, numero);
            if (destinataire != null) {
                PacketDistributor.sendToPlayer(destinataire, new Recu(sim, texte.isEmpty() ? "📷 Photo" : texte));
                envoyerBoite(destinataire, numero);
            }
        }
        envoyerConversation(joueur, sim, numero);
    }

    static void envoyerBoite(ServerPlayer joueur, String sim) {
        Donnees d = Donnees.get(joueur.server);
        Map<String, Integer> lus = d.nonLus.getOrDefault(sim, Map.of());
        List<Resume> liste = new ArrayList<>();
        d.boites.getOrDefault(sim, new LinkedHashMap<>()).forEach((numero, messages) -> {
            Sms dernier = messages.isEmpty() ? null : messages.getLast();
            String apercu = dernier == null ? "" : (dernier.moi() ? "Toi : " : "") + (dernier.texte().isEmpty() ? "📷 Photo" : dernier.texte());
            liste.add(new Resume(numero, apercu.length() > 80 ? apercu.substring(0, 80) : apercu, dernier == null ? 0 : dernier.temps(), lus.getOrDefault(numero, 0)));
        });
        liste.sort(Comparator.comparingLong(Resume::temps).reversed());
        PacketDistributor.sendToPlayer(joueur, new Boite(liste));
    }

    private static void envoyerConversation(ServerPlayer joueur, String sim, String numero) {
        LinkedList<Sms> liste = Donnees.get(joueur.server).boites.getOrDefault(sim, new LinkedHashMap<>()).getOrDefault(numero, new LinkedList<>());
        PacketDistributor.sendToPlayer(joueur, new Conversation(numero, new ArrayList<>(liste)));
    }

    private Messages() {
    }
}
