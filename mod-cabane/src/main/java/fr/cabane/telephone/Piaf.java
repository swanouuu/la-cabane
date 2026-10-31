package fr.cabane.telephone;

import fr.cabane.Cabane;
import fr.cabane.CabaneItems;
import fr.cabane.client.ClientPiaf;
import io.netty.buffer.ByteBuf;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/**
 * Piaf, le réseau social du téléphone : un compte (pseudo) par joueur, des publications
 * de 280 caractères avec ou sans photo, des « j'aime », et un fil commun en direct.
 * Les photos sont des PNG enregistrés dans le dossier du monde (cabane_photos/).
 */
public final class Piaf {
    public static final Pattern PSEUDO = Pattern.compile("[\\p{L}0-9_.-]{3,16}");
    public static final int TEXTE_MAX = 280;
    private static final int PHOTO_MAX = 600_000;
    private static final int MORCEAU = 30_000;
    private static final int FIL = 40;

    // ---------------------------------------------------------------- données

    public static class Donnees extends SavedData {
        // le compte Piaf et les photos appartiennent au téléphone (sa SIM), pas au joueur :
        // qui vole le téléphone peut se faire passer pour son propriétaire
        final Map<String, String> pseudos = new HashMap<>();
        final LinkedList<Post> posts = new LinkedList<>();
        final Map<String, List<Long>> galeries = new HashMap<>();
        long prochainId = 1;

        static Donnees get(MinecraftServer server) {
            return server.overworld().getDataStorage().computeIfAbsent(new SavedData.Factory<>(Donnees::new, Donnees::load, null), "cabane_piaf");
        }

        private static Donnees load(CompoundTag tag, HolderLookup.Provider registries) {
            Donnees d = new Donnees();
            d.prochainId = tag.getLong("prochain");
            for (Tag t : tag.getList("comptes", Tag.TAG_COMPOUND)) {
                CompoundTag c = (CompoundTag) t;
                String sim = c.getString("sim");
                if (sim.isEmpty()) {
                    continue;
                }
                if (!c.getString("pseudo").isEmpty()) {
                    d.pseudos.put(sim, c.getString("pseudo"));
                }
                List<Long> galerie = new ArrayList<>();
                for (long id : c.getLongArray("galerie")) {
                    galerie.add(id);
                }
                d.galeries.put(sim, galerie);
            }
            for (Tag t : tag.getList("posts", Tag.TAG_COMPOUND)) {
                CompoundTag p = (CompoundTag) t;
                Set<UUID> likes = new HashSet<>();
                for (Tag l : p.getList("likes", Tag.TAG_INT_ARRAY)) {
                    likes.add(NbtUtils.loadUUID(l));
                }
                d.posts.add(new Post(p.getLong("id"), p.getUUID("auteur"), p.getString("pseudo"), p.getString("texte"),
                        p.getLong("photo"), p.getLong("temps"), likes));
            }
            return d;
        }

        @Override
        public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
            tag.putLong("prochain", prochainId);
            ListTag comptes = new ListTag();
            Set<String> sims = new HashSet<>(pseudos.keySet());
            sims.addAll(galeries.keySet());
            for (String sim : sims) {
                CompoundTag c = new CompoundTag();
                c.putString("sim", sim);
                c.putString("pseudo", pseudos.getOrDefault(sim, ""));
                c.putLongArray("galerie", galeries.getOrDefault(sim, List.of()).stream().mapToLong(Long::longValue).toArray());
                comptes.add(c);
            }
            tag.put("comptes", comptes);
            ListTag liste = new ListTag();
            for (Post p : posts) {
                CompoundTag c = new CompoundTag();
                c.putLong("id", p.id());
                c.putUUID("auteur", p.auteur());
                c.putString("pseudo", p.pseudo());
                c.putString("texte", p.texte());
                c.putLong("photo", p.photo());
                c.putLong("temps", p.temps());
                ListTag likes = new ListTag();
                p.likes().forEach(u -> likes.add(NbtUtils.createUUID(u)));
                c.put("likes", likes);
                liste.add(c);
            }
            tag.put("posts", liste);
            return tag;
        }
    }

    public record Post(long id, UUID auteur, String pseudo, String texte, long photo, long temps, Set<UUID> likes) {
    }

    // ---------------------------------------------------------------- réseau

    public record Vue(long id, UUID auteur, String pseudo, String texte, long photo, long temps, int likes, boolean aime) {
        public static final StreamCodec<ByteBuf, Vue> CODEC = new StreamCodec<>() {
            @Override
            public Vue decode(ByteBuf buf) {
                return new Vue(buf.readLong(), new UUID(buf.readLong(), buf.readLong()), ByteBufCodecs.STRING_UTF8.decode(buf),
                        ByteBufCodecs.STRING_UTF8.decode(buf), buf.readLong(), buf.readLong(), buf.readInt(), buf.readBoolean());
            }

            @Override
            public void encode(ByteBuf buf, Vue v) {
                buf.writeLong(v.id());
                buf.writeLong(v.auteur().getMostSignificantBits());
                buf.writeLong(v.auteur().getLeastSignificantBits());
                ByteBufCodecs.STRING_UTF8.encode(buf, v.pseudo());
                ByteBufCodecs.STRING_UTF8.encode(buf, v.texte());
                buf.writeLong(v.photo());
                buf.writeLong(v.temps());
                buf.writeInt(v.likes());
                buf.writeBoolean(v.aime());
            }
        };
    }

    /** Serveur → client : le fil, ton pseudo (vide si pas de compte) et ta galerie. */
    public record Fil(String monPseudo, List<Vue> posts, List<Long> galerie) implements CustomPacketPayload {
        public static final Type<Fil> TYPE = new Type<>(Cabane.id("piaf_fil"));
        public static final StreamCodec<ByteBuf, Fil> CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, Fil::monPseudo,
                Vue.CODEC.apply(ByteBufCodecs.list(FIL)), Fil::posts,
                ByteBufCodecs.VAR_LONG.apply(ByteBufCodecs.list(64)), Fil::galerie,
                Fil::new);

        @Override
        public Type<Fil> type() {
            return TYPE;
        }
    }

    /** Client → serveur : action = 0 voir le fil, 1 créer le compte (texte = pseudo), 2 publier, 3 aimer (nombre = post). */
    public record Action(int action, String texte, long nombre) implements CustomPacketPayload {
        public static final Type<Action> TYPE = new Type<>(Cabane.id("piaf_action"));
        public static final StreamCodec<ByteBuf, Action> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, Action::action,
                ByteBufCodecs.stringUtf8(1024), Action::texte,
                ByteBufCodecs.VAR_LONG, Action::nombre,
                Action::new);

        @Override
        public Type<Action> type() {
            return TYPE;
        }
    }

    /** Client → serveur : un morceau de photo. */
    public record Morceau(int envoi, int index, int total, byte[] donnees) implements CustomPacketPayload {
        public static final Type<Morceau> TYPE = new Type<>(Cabane.id("piaf_morceau"));
        public static final StreamCodec<ByteBuf, Morceau> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, Morceau::envoi,
                ByteBufCodecs.VAR_INT, Morceau::index,
                ByteBufCodecs.VAR_INT, Morceau::total,
                ByteBufCodecs.byteArray(MORCEAU + 64), Morceau::donnees,
                Morceau::new);

        @Override
        public Type<Morceau> type() {
            return TYPE;
        }
    }

    /** Client → serveur : je veux voir la photo n°… ; serveur → client : la voici. */
    public record DemandePhoto(long id) implements CustomPacketPayload {
        public static final Type<DemandePhoto> TYPE = new Type<>(Cabane.id("piaf_demande_photo"));
        public static final StreamCodec<ByteBuf, DemandePhoto> CODEC = ByteBufCodecs.VAR_LONG.map(DemandePhoto::new, DemandePhoto::id);

        @Override
        public Type<DemandePhoto> type() {
            return TYPE;
        }
    }

    public record Photo(long id, byte[] png) implements CustomPacketPayload {
        public static final Type<Photo> TYPE = new Type<>(Cabane.id("piaf_photo"));
        public static final StreamCodec<ByteBuf, Photo> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_LONG, Photo::id,
                ByteBufCodecs.byteArray(PHOTO_MAX), Photo::png,
                Photo::new);

        @Override
        public Type<Photo> type() {
            return TYPE;
        }
    }

    /** Serveur → client : nouvelle publication (pour la notification). */
    public record Nouveau(String pseudo, String texte) implements CustomPacketPayload {
        public static final Type<Nouveau> TYPE = new Type<>(Cabane.id("piaf_nouveau"));
        public static final StreamCodec<ByteBuf, Nouveau> CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, Nouveau::pseudo,
                ByteBufCodecs.STRING_UTF8, Nouveau::texte,
                Nouveau::new);

        @Override
        public Type<Nouveau> type() {
            return TYPE;
        }
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        event.registrar("1")
                .playToClient(Fil.TYPE, Fil.CODEC, (p, ctx) -> ctx.enqueueWork(() -> ClientPiaf.fil(p)))
                .playToClient(Photo.TYPE, Photo.CODEC, (p, ctx) -> ctx.enqueueWork(() -> ClientPiaf.photo(p.id(), p.png())))
                .playToClient(Nouveau.TYPE, Nouveau.CODEC, (p, ctx) -> ctx.enqueueWork(() -> ClientPiaf.notification(p.pseudo(), p.texte())))
                .playToServer(Action.TYPE, Action.CODEC, (p, ctx) -> ctx.enqueueWork(() -> {
                    if (ctx.player() instanceof ServerPlayer joueur) {
                        agir(joueur, p);
                    }
                }))
                .playToServer(Morceau.TYPE, Morceau.CODEC, (p, ctx) -> ctx.enqueueWork(() -> {
                    if (ctx.player() instanceof ServerPlayer joueur) {
                        recevoirMorceau(joueur, p);
                    }
                }))
                .playToServer(DemandePhoto.TYPE, DemandePhoto.CODEC, (p, ctx) -> ctx.enqueueWork(() -> {
                    if (ctx.player() instanceof ServerPlayer joueur) {
                        envoyerPhoto(joueur, p.id());
                    }
                }));
    }

    // ---------------------------------------------------------------- logique

    private static final Map<String, Long> DERNIER_POST = new HashMap<>();

    /** L'identité Piaf d'une SIM (sert d'auteur et pour les « j'aime ») : rien ne trahit le vrai joueur. */
    static UUID compte(String sim) {
        return UUID.nameUUIDFromBytes(("piaf:" + sim).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static void agir(ServerPlayer joueur, Action a) {
        Donnees d = Donnees.get(joueur.server);
        String sim = PhoneCalls.numero(joueur);
        if (sim == null) {
            if (a.action() != 0) {
                joueur.displayClientMessage(net.minecraft.network.chat.Component.literal("Active d'abord la SIM de ton téléphone.")
                        .withStyle(net.minecraft.ChatFormatting.RED), true);
            }
            envoyerFil(joueur);
            return;
        }
        UUID compte = compte(sim);
        switch (a.action()) {
            case 1 -> {
                String pseudo = a.texte().trim();
                boolean pris = d.pseudos.entrySet().stream()
                        .anyMatch(e -> e.getValue().equalsIgnoreCase(pseudo) && !e.getKey().equals(sim));
                if (PSEUDO.matcher(pseudo).matches() && !pris) {
                    d.pseudos.put(sim, pseudo);
                    d.setDirty();
                } else {
                    joueur.displayClientMessage(net.minecraft.network.chat.Component.literal(
                            pris ? "Ce pseudo est déjà pris." : "Pseudo : 3 à 16 lettres, chiffres, _ . ou -")
                            .withStyle(net.minecraft.ChatFormatting.RED), true);
                }
            }
            case 2 -> publier(joueur, sim, d, a.texte(), a.nombre());
            case 3 -> d.posts.stream().filter(p -> p.id() == a.nombre()).findFirst().ifPresent(p -> {
                if (!p.likes().remove(compte)) {
                    p.likes().add(compte);
                }
                d.setDirty();
            });
            default -> {
            }
        }
        envoyerFil(joueur);
    }

    private static void publier(ServerPlayer joueur, String sim, Donnees d, String brut, long photo) {
        String pseudo = d.pseudos.get(sim);
        String texte = brut.replace("§", "").strip();
        if (pseudo == null || (texte.isEmpty() && photo == 0)) {
            return;
        }
        long maintenant = System.currentTimeMillis();
        if (maintenant - DERNIER_POST.getOrDefault(sim, 0L) < 15_000) {
            joueur.displayClientMessage(net.minecraft.network.chat.Component.literal("Doucement ! Attends un peu avant de republier.")
                    .withStyle(net.minecraft.ChatFormatting.RED), true);
            return;
        }
        if (photo != 0 && !d.galeries.getOrDefault(sim, List.of()).contains(photo)) {
            photo = 0;
        }
        DERNIER_POST.put(sim, maintenant);
        if (texte.length() > TEXTE_MAX) {
            texte = texte.substring(0, TEXTE_MAX);
        }
        d.posts.addFirst(new Post(d.prochainId++, compte(sim), pseudo, texte, photo, maintenant, new HashSet<>()));
        while (d.posts.size() > 300) {
            d.posts.removeLast();
        }
        d.setDirty();
        Nouveau notif = new Nouveau(pseudo, texte.isEmpty() ? "📷 une photo" : texte);
        for (ServerPlayer autre : joueur.server.getPlayerList().getPlayers()) {
            if (autre != joueur && autre.getInventory().hasAnyMatching(s -> s.is(CabaneItems.TELEPHONE.get()))) {
                PacketDistributor.sendToPlayer(autre, notif);
            }
        }
    }

    public static void envoyerFil(ServerPlayer joueur) {
        Donnees d = Donnees.get(joueur.server);
        String sim = PhoneCalls.numero(joueur);
        UUID compte = sim == null ? null : compte(sim);
        List<Vue> vues = d.posts.stream().limit(FIL).map(p -> new Vue(p.id(), p.auteur(), p.pseudo(), p.texte(), p.photo(), p.temps(),
                p.likes().size(), compte != null && p.likes().contains(compte))).toList();
        List<Long> galerie = sim == null ? List.of() : d.galeries.getOrDefault(sim, List.of());
        PacketDistributor.sendToPlayer(joueur, new Fil(sim == null ? "" : d.pseudos.getOrDefault(sim, ""), vues,
                galerie.subList(Math.max(0, galerie.size() - 64), galerie.size())));
    }

    // ---------------------------------------------------------------- photos

    private record Envoi(int numero, byte[][] morceaux) {
    }

    private static final Map<UUID, Envoi> ENVOIS = new HashMap<>();

    private static Path dossier(MinecraftServer server) throws IOException {
        Path p = server.getWorldPath(LevelResource.ROOT).resolve("cabane_photos");
        Files.createDirectories(p);
        return p;
    }

    private static void recevoirMorceau(ServerPlayer joueur, Morceau m) {
        if (m.total() < 1 || m.total() > PHOTO_MAX / MORCEAU + 1 || m.index() < 0 || m.index() >= m.total()) {
            return;
        }
        Envoi envoi = ENVOIS.get(joueur.getUUID());
        if (envoi == null || envoi.numero() != m.envoi() || envoi.morceaux().length != m.total()) {
            envoi = new Envoi(m.envoi(), new byte[m.total()][]);
            ENVOIS.put(joueur.getUUID(), envoi);
        }
        envoi.morceaux()[m.index()] = m.donnees();
        for (byte[] b : envoi.morceaux()) {
            if (b == null) {
                return;
            }
        }
        ENVOIS.remove(joueur.getUUID());
        int taille = 0;
        for (byte[] b : envoi.morceaux()) {
            taille += b.length;
        }
        byte[] png = new byte[taille];
        int pos = 0;
        for (byte[] b : envoi.morceaux()) {
            System.arraycopy(b, 0, png, pos, b.length);
            pos += b.length;
        }
        boolean estPng = png.length > 8 && png[0] == (byte) 0x89 && png[1] == 'P' && png[2] == 'N' && png[3] == 'G';
        if (!estPng || png.length > PHOTO_MAX) {
            return;
        }
        String sim = PhoneCalls.numero(joueur);
        if (sim == null) {
            return;
        }
        Donnees d = Donnees.get(joueur.server);
        long id = d.prochainId++;
        try {
            Files.write(dossier(joueur.server).resolve(id + ".png"), png);
        } catch (IOException e) {
            Cabane.LOGGER.warn("Photo non enregistrée : {}", e.toString());
            return;
        }
        List<Long> galerie = d.galeries.computeIfAbsent(sim, k -> new ArrayList<>());
        galerie.add(id);
        while (galerie.size() > 64) {
            galerie.remove(0);
        }
        d.setDirty();
        joueur.displayClientMessage(net.minecraft.network.chat.Component.literal("📷 Photo enregistrée dans le téléphone.")
                .withStyle(net.minecraft.ChatFormatting.GREEN), true);
        envoyerFil(joueur);
    }

    private static void envoyerPhoto(ServerPlayer joueur, long id) {
        try {
            Path f = dossier(joueur.server).resolve(id + ".png");
            if (Files.exists(f) && Files.size(f) <= PHOTO_MAX) {
                PacketDistributor.sendToPlayer(joueur, new Photo(id, Files.readAllBytes(f)));
            }
        } catch (IOException e) {
            Cabane.LOGGER.warn("Photo {} illisible : {}", id, e.toString());
        }
    }

    public static int tailleMorceau() {
        return MORCEAU;
    }

    private Piaf() {
    }
}
