package fr.cabane.pnj;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import fr.cabane.Cabane;
import fr.cabane.economie.Economie;
import fr.cabane.hub.HubData;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.TagKey;
import net.minecraft.util.GsonHelper;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.saveddata.SavedData;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/**
 * Les missions des habitants : chaque PNJ propose les siennes dans sa conversation (« ❗ »), on les accepte, on
 * avance (apporter, tuer, parler à quelqu'un, découvrir un lieu…), puis on revient les rendre (« ✔ »).
 * Fichiers data/cabane/missions/&lt;id&gt;.json, écrits par outils/missions.mjs. La progression de chaque joueur
 * est gardée dans le monde ; les missions en cours s'affichent en haut à gauche de l'écran.
 */
public final class Missions extends SimpleJsonResourceReloadListener {
    public static final Missions INSTANCE = new Missions();
    private static Map<String, Mission> missions = Map.of();

    static final int EN_COURS = 1;
    static final int FINIE = 2;

    public enum Genre { APPORTER, AVOIR, TUER, PARLER, SUCCES, EVENEMENT }

    /**
     * cible : l'objet (apporter, avoir), l'entité (tuer, « #tag » possible), le PNJ (parler), le succès, ou le nom de
     * l'événement. Pour « parler » : la question posée, la réponse, l'objet que le PNJ prend ou donne.
     */
    public record Objectif(Genre type, String cible, int nombre, String texte, String question, String reponse, String prend, String donne) {
    }

    /**
     * quand : -1 tant que le Président règne, 1 une fois Beauvais libéré, 0 toujours. repetable : minutes avant de la refaire
     * (0 : une fois). ftb : la quête du livre (identifiant FTB Quests) cochée quand on la rend. partie : la partie de
     * l'histoire qu'il faut avoir débloquée (/histoire partie).
     */
    public record Mission(String id, String qui, String titre, String pitch, String rappel, String merci, List<Objectif> objectifs,
            long argent, int xp, List<ItemStack> objets, List<String> apres, int quand, int repetable, List<ItemStack> donnes, int ordre, String ftb,
            Semaine semaine, int partie) {
    }

    /**
     * Les quêtes de la semaine : chaque lundi, un thème (« La semaine du Nether »), le même pour tout le monde ; chacun fait
     * les siennes. texte : ce que le Hejj en dit ; bonus : la prime de qui les rend toutes.
     */
    public record Semaine(String id, String nom, String texte, long bonus) {
    }

    private static List<Semaine> semaines = List.of();

    private static Semaine semaine(JsonObject o) {
        if (!o.has("semaine")) {
            return null;
        }
        JsonObject w = GsonHelper.getAsJsonObject(o, "semaine");
        return new Semaine(GsonHelper.getAsString(w, "id"), GsonHelper.getAsString(w, "nom"), GsonHelper.getAsString(w, "texte", ""),
                GsonHelper.getAsLong(w, "bonus", 0));
    }

    private Missions() {
        super(new Gson(), "missions");
    }

    private static Item objet(String id) {
        return BuiltInRegistries.ITEM.get(ResourceLocation.parse(id));
    }

    private static List<ItemStack> objets(JsonObject o, String cle) {
        List<ItemStack> l = new ArrayList<>();
        if (o.has(cle)) {
            for (JsonElement e : GsonHelper.getAsJsonArray(o, cle)) {
                Item item = objet(e.getAsJsonArray().get(0).getAsString());
                if (item == Items.AIR) {
                    throw new IllegalArgumentException("objet absent : " + e);
                }
                l.add(new ItemStack(item, e.getAsJsonArray().get(1).getAsInt()));
            }
        }
        return l;
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> fichiers, ResourceManager manager, ProfilerFiller profiler) {
        List<Mission> liste = new ArrayList<>();
        fichiers.forEach((id, json) -> {
            try {
                JsonObject o = json.getAsJsonObject();
                List<Objectif> objectifs = new ArrayList<>();
                for (JsonElement e : GsonHelper.getAsJsonArray(o, "objectifs")) {
                    JsonObject b = e.getAsJsonObject();
                    Genre type = Genre.valueOf(GsonHelper.getAsString(b, "type").toUpperCase());
                    String cible = GsonHelper.getAsString(b, "cible");
                    if ((type == Genre.APPORTER || type == Genre.AVOIR) && objet(cible) == Items.AIR) {
                        throw new IllegalArgumentException("objet absent : " + cible);
                    }
                    objectifs.add(new Objectif(type, cible, GsonHelper.getAsInt(b, "nombre", 1), GsonHelper.getAsString(b, "texte", ""),
                            GsonHelper.getAsString(b, "question", ""), GsonHelper.getAsString(b, "reponse", ""),
                            GsonHelper.getAsString(b, "prend", ""), GsonHelper.getAsString(b, "donne", "")));
                }
                JsonObject r = GsonHelper.getAsJsonObject(o, "recompense", new JsonObject());
                List<String> apres = new ArrayList<>();
                if (o.has("apres")) {
                    GsonHelper.getAsJsonArray(o, "apres").forEach(a -> apres.add(a.getAsString()));
                }
                liste.add(new Mission(id.getPath(), GsonHelper.getAsString(o, "qui"), GsonHelper.getAsString(o, "titre"),
                        GsonHelper.getAsString(o, "pitch"), GsonHelper.getAsString(o, "rappel", ""), GsonHelper.getAsString(o, "merci", "Merci !"),
                        List.copyOf(objectifs), GsonHelper.getAsLong(r, "argent", 0), GsonHelper.getAsInt(r, "xp", 0), objets(r, "objets"),
                        List.copyOf(apres), GsonHelper.getAsInt(o, "quand", 0), GsonHelper.getAsInt(o, "repetable", 0), objets(o, "donne"),
                        GsonHelper.getAsInt(o, "ordre", 100), GsonHelper.getAsString(o, "ftb", ""), semaine(o),
                        GsonHelper.getAsInt(o, "partie", 1)));
            } catch (Exception e) {
                Cabane.LOGGER.info("Mission {} ignorée : {}", id, e.getMessage());
            }
        });
        liste.sort(Comparator.comparingInt(Mission::ordre).thenComparing(Mission::id));
        Map<String, Mission> m = new LinkedHashMap<>();
        liste.forEach(x -> m.put(x.id(), x));
        missions = m;
        List<Semaine> s = new ArrayList<>();
        for (Mission x : liste) {
            if (x.semaine() != null && s.stream().noneMatch(w -> w.id().equals(x.semaine().id()))) {
                s.add(x.semaine());
            }
        }
        semaines = List.copyOf(s);
        Cabane.LOGGER.info("Missions des habitants : {} (et {} semaines à thème)", missions.size(), semaines.size());
    }

    static Mission mission(String id) {
        return missions.get(id);
    }

    static java.util.Collection<Mission> toutes() {
        return missions.values();
    }

    static List<Mission> de(String qui) {
        return missions.values().stream().filter(m -> m.qui().equals(qui)).toList();
    }

    // ================================================================ la progression de chaque joueur

    static final class Etat {
        int statut;
        int[] compte;
        int fois;
        long fin;
        long semaine;

        Etat(int objectifs) {
            compte = new int[objectifs];
        }
    }

    static final class Donnees extends SavedData {
        final Map<UUID, Map<String, Etat>> joueurs = new HashMap<>();
        final java.util.Set<UUID> caches = new java.util.HashSet<>();
        /** Les semaines passées d'avance par l'hôte (/cabane semaine suivante). */
        long decalage;

        static Donnees get(MinecraftServer server) {
            return server.overworld().getDataStorage().computeIfAbsent(new SavedData.Factory<>(Donnees::new, Donnees::charger, null), "cabane_missions");
        }

        private static Donnees charger(CompoundTag tag, HolderLookup.Provider registres) {
            Donnees d = new Donnees();
            d.decalage = tag.getLong("decalage");
            for (String joueur : tag.getAllKeys()) {
                if (joueur.equals("caches") || joueur.equals("decalage")) {
                    continue;
                }
                CompoundTag t = tag.getCompound(joueur);
                Map<String, Etat> etats = new HashMap<>();
                for (String id : t.getAllKeys()) {
                    CompoundTag m = t.getCompound(id);
                    Etat e = new Etat(0);
                    e.statut = m.getByte("s");
                    e.compte = m.getIntArray("c");
                    e.fois = m.getInt("n");
                    e.fin = m.getLong("f");
                    e.semaine = m.getLong("w");
                    etats.put(id, e);
                }
                d.joueurs.put(UUID.fromString(joueur), etats);
            }
            for (int[] u : tag.getList("caches", net.minecraft.nbt.Tag.TAG_INT_ARRAY).stream().map(t -> ((net.minecraft.nbt.IntArrayTag) t).getAsIntArray()).toList()) {
                d.caches.add(net.minecraft.core.UUIDUtil.uuidFromIntArray(u));
            }
            return d;
        }

        @Override
        public CompoundTag save(CompoundTag tag, HolderLookup.Provider registres) {
            joueurs.forEach((joueur, etats) -> {
                CompoundTag t = new CompoundTag();
                etats.forEach((id, e) -> {
                    CompoundTag m = new CompoundTag();
                    m.putByte("s", (byte) e.statut);
                    m.putIntArray("c", e.compte);
                    m.putInt("n", e.fois);
                    m.putLong("f", e.fin);
                    m.putLong("w", e.semaine);
                    t.put(id, m);
                });
                tag.put(joueur.toString(), t);
            });
            net.minecraft.nbt.ListTag l = new net.minecraft.nbt.ListTag();
            caches.forEach(u -> l.add(net.minecraft.nbt.NbtUtils.createUUID(u)));
            tag.put("caches", l);
            tag.putLong("decalage", decalage);
            return tag;
        }

        Map<String, Etat> de(ServerPlayer joueur) {
            return joueurs.computeIfAbsent(joueur.getUUID(), k -> new HashMap<>());
        }
    }

    static Map<String, Etat> etats(ServerPlayer joueur) {
        Donnees d = Donnees.get(joueur.server);
        Map<String, Etat> etats = d.de(joueur);
        // une quête d'une semaine passée ne compte plus, rendue ou pas : elle reviendra avec son thème
        long n = numeroSemaine(joueur.server);
        if (etats.entrySet().removeIf(en -> {
            Mission m = missions.get(en.getKey());
            return m != null && m.semaine() != null && en.getValue().semaine != n;
        })) {
            d.setDirty();
        }
        return etats;
    }

    // ================================================================ les quêtes de la semaine

    private static final java.time.ZoneId PARIS = java.time.ZoneId.of("Europe/Paris");

    /** Le numéro de la semaine (elles commencent le lundi, heure de Paris), plus celles que l'hôte a passées d'avance. */
    static long numeroSemaine(MinecraftServer server) {
        return Math.floorDiv(java.time.LocalDate.now(PARIS).toEpochDay() + 3, 7) + Donnees.get(server).decalage;
    }

    /** Le thème de cette semaine, le même pour tout le monde : les thèmes reviennent chacun leur tour. */
    public static Semaine semaine(MinecraftServer server) {
        return semaines.isEmpty() ? null : semaines.get((int) Math.floorMod(numeroSemaine(server), semaines.size()));
    }

    /** Les quêtes de cette semaine. */
    static List<Mission> deLaSemaine(MinecraftServer server) {
        Semaine s = semaine(server);
        return s == null ? List.of() : missions.values().stream().filter(m -> m.semaine() != null && m.semaine().id().equals(s.id())).toList();
    }

    /** Combien de quêtes de cette semaine ce joueur a déjà rendues. */
    static int rendues(MinecraftServer server, Map<String, Etat> etats) {
        long n = numeroSemaine(server);
        return (int) deLaSemaine(server).stream().filter(m -> {
            Etat e = etats.get(m.id());
            return e != null && e.fois > 0 && e.semaine == n;
        }).count();
    }

    /** Ceux qui ont déjà rendu toutes les quêtes de la semaine. */
    static List<String> champions(MinecraftServer server) {
        int total = deLaSemaine(server).size();
        List<String> noms = new ArrayList<>();
        Donnees.get(server).joueurs.forEach((id, etats) -> {
            if (total > 0 && rendues(server, etats) == total) {
                var cache = server.getProfileCache();
                noms.add(cache == null ? "?" : cache.get(id).map(com.mojang.authlib.GameProfile::getName).orElse("?"));
            }
        });
        return noms;
    }

    /** Toutes rendues : la prime, et tout le serveur l'apprend. */
    private static void prime(ServerPlayer joueur, Semaine s) {
        if (s.bonus() > 0) {
            Economie.crediter(joueur.server, joueur.getUUID(), s.bonus());
        }
        joueur.level().playSound(null, joueur.blockPosition(), SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, SoundSource.PLAYERS, 0.8F, 1F);
        joueur.server.getPlayerList().broadcastSystemMessage(Component.literal("★ " + joueur.getGameProfile().getName()
                + " a rendu toutes les quêtes de « " + s.nom() + " » !").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD)
                .append(Component.literal(s.bonus() > 0 ? "  +" + Economie.format(s.bonus()) : "").withStyle(ChatFormatting.YELLOW)), false);
    }

    /** Ce que le Hejj a pour ce joueur cette semaine, en une ligne (à la connexion, au changement de semaine). */
    private static void annoncer(ServerPlayer joueur) {
        Semaine s = semaine(joueur.server);
        if (s == null) {
            return;
        }
        int total = deLaSemaine(joueur.server).size();
        int faites = rendues(joueur.server, etats(joueur));
        if (faites < total) {
            joueur.sendSystemMessage(Component.literal("★ " + s.nom() + " : le Hejj a " + total + " quêtes pour toi cette semaine ("
                    + faites + "/" + total + " rendues). Va le voir, à la Grande Cabane.").withStyle(ChatFormatting.GOLD));
        }
    }

    /** /cabane semaine : le thème, les quêtes et où on en est, et ceux qui ont déjà tout fini. */
    public static void infoSemaine(ServerPlayer joueur) {
        Semaine s = semaine(joueur.server);
        if (s == null) {
            joueur.sendSystemMessage(Component.literal("Pas de quêtes de la semaine.").withStyle(ChatFormatting.GRAY));
            return;
        }
        joueur.sendSystemMessage(Component.literal("★ " + s.nom() + " (chez le Hejj, jusqu'à dimanche soir)").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
        long n = numeroSemaine(joueur.server);
        for (Mission m : deLaSemaine(joueur.server)) {
            Etat e = etats(joueur).get(m.id());
            String ou = e != null && e.fois > 0 && e.semaine == n ? "✔ rendue" : e != null && e.statut == EN_COURS ? "en cours" : "à prendre";
            joueur.sendSystemMessage(Component.literal("   " + m.titre() + " — " + ou).withStyle(ChatFormatting.GRAY));
        }
        List<String> champions = champions(joueur.server);
        if (!champions.isEmpty()) {
            joueur.sendSystemMessage(Component.literal("   Déjà tout fini : " + String.join(", ", champions)).withStyle(ChatFormatting.YELLOW));
        }
    }

    /** Pour l'hôte (et les essais) : passer tout de suite à la semaine suivante, pour tout le monde. */
    public static void semaineSuivante(MinecraftServer server) {
        Donnees.get(server).decalage++;
        Donnees.get(server).setDirty();
        changementDeSemaine(server);
    }

    private static long derniereSemaine = Long.MIN_VALUE;

    private static void changementDeSemaine(MinecraftServer server) {
        derniereSemaine = numeroSemaine(server);
        Semaine s = semaine(server);
        for (ServerPlayer joueur : server.getPlayerList().getPlayers()) {
            if (s != null) {
                joueur.sendSystemMessage(Component.literal("★ Nouvelle semaine : " + s.nom() + " !").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
            }
            annoncer(joueur);
            suivre(joueur, true);
        }
    }

    private static Etat etat(ServerPlayer joueur, Mission m) {
        Etat e = etats(joueur).get(m.id());
        if (e != null && e.compte.length != m.objectifs().size()) {
            e.compte = java.util.Arrays.copyOf(e.compte, m.objectifs().size());
        }
        return e;
    }

    static boolean enCours(ServerPlayer joueur, Mission m) {
        Etat e = etat(joueur, m);
        return e != null && e.statut == EN_COURS;
    }

    static boolean disponible(ServerPlayer joueur, Mission m) {
        Map<String, Etat> etats = etats(joueur);
        Etat e = etats.get(m.id());
        if (e != null && e.statut == EN_COURS) {
            return false;
        }
        if (m.semaine() != null && (semaine(joueur.server) == null || !semaine(joueur.server).id().equals(m.semaine().id()))) {
            return false;
        }
        if (e != null && e.fois > 0 && (m.repetable() <= 0 || System.currentTimeMillis() < e.fin + m.repetable() * 60_000L)) {
            return false;
        }
        for (String a : m.apres()) {
            Etat p = etats.get(a);
            if (p == null || p.fois == 0) {
                return false;
            }
        }
        HubData hub = HubData.get(joueur.server);
        if (hub.partie() < m.partie()) {
            return false;
        }
        return m.quand() == 0 || (m.quand() > 0) == hub.libere();
    }

    private static int compter(ServerPlayer joueur, String id) {
        Item item = objet(id);
        return joueur.getInventory().countItem(item);
    }

    /** Où en est cet objectif (0 à nombre). */
    static int progres(ServerPlayer joueur, Mission m, int i) {
        Objectif o = m.objectifs().get(i);
        Etat e = etat(joueur, m);
        int fait = switch (o.type()) {
            case APPORTER, AVOIR -> compter(joueur, o.cible());
            case SUCCES -> {
                var succes = joueur.server.getAdvancements().get(ResourceLocation.parse(o.cible()));
                yield succes != null && joueur.getAdvancements().getOrStartProgress(succes).isDone() ? o.nombre() : 0;
            }
            default -> e == null ? 0 : e.compte[i];
        };
        return Math.min(o.nombre(), fait);
    }

    static boolean complete(ServerPlayer joueur, Mission m) {
        for (int i = 0; i < m.objectifs().size(); i++) {
            if (progres(joueur, m, i) < m.objectifs().get(i).nombre()) {
                return false;
            }
        }
        return true;
    }

    /** Le texte d'un objectif : « Apporter 16 × Morue cuite », « Parler à Jean »… */
    static String texte(Objectif o) {
        if (!o.texte().isEmpty()) {
            return o.texte();
        }
        return switch (o.type()) {
            case APPORTER -> "Apporter " + o.nombre() + " × " + new ItemStack(objet(o.cible())).getHoverName().getString();
            case AVOIR -> "Avoir " + (o.nombre() > 1 ? o.nombre() + " × " : "") + new ItemStack(objet(o.cible())).getHoverName().getString();
            case TUER -> "Vaincre " + o.nombre() + " × " + nomEntite(o.cible());
            case PARLER -> "Parler à " + Dialogues.nomCourt(o.cible());
            case SUCCES -> "Trouver ce qui se cache…";
            case EVENEMENT -> o.cible() + " × " + o.nombre();
        };
    }

    private static String nomEntite(String id) {
        if (id.startsWith("#")) {
            return id.substring(id.indexOf(':') + 1).replace('_', ' ');
        }
        return BuiltInRegistries.ENTITY_TYPE.get(ResourceLocation.parse(id)).getDescription().getString();
    }

    /** Une ligne d'objectif avec sa progression : « ✔ Parler à Jean », « • Apporter 16 × Morue cuite (8/16) ». */
    static String ligne(ServerPlayer joueur, Mission m, int i) {
        Objectif o = m.objectifs().get(i);
        int p = progres(joueur, m, i);
        boolean fait = p >= o.nombre();
        String compte = o.nombre() > 1 && !fait ? " (" + p + "/" + o.nombre() + ")" : "";
        return (fait ? "✔ " : "• ") + texte(o) + compte;
    }

    static String recompense(Mission m) {
        List<String> l = new ArrayList<>();
        if (m.argent() > 0) {
            l.add(Economie.format(m.argent()));
        }
        for (ItemStack s : m.objets()) {
            l.add(s.getCount() + " × " + s.getHoverName().getString());
        }
        if (m.xp() > 0) {
            l.add(m.xp() + " niveaux d'expérience");
        }
        return l.isEmpty() ? "la reconnaissance éternelle" : String.join(", ", l);
    }

    // ================================================================ accepter, avancer, rendre

    static void accepter(ServerPlayer joueur, Mission m) {
        if (!disponible(joueur, m)) {
            return;
        }
        Etat e = etats(joueur).computeIfAbsent(m.id(), k -> new Etat(m.objectifs().size()));
        e.statut = EN_COURS;
        e.semaine = numeroSemaine(joueur.server);
        e.compte = new int[m.objectifs().size()];
        for (ItemStack s : m.donnes()) {
            donner(joueur, s.copy());
        }
        Donnees.get(joueur.server).setDirty();
        joueur.level().playSound(null, joueur.blockPosition(), SoundEvents.BOOK_PAGE_TURN, SoundSource.PLAYERS, 1F, 1F);
        joueur.displayClientMessage(Component.literal("✦ Nouvelle mission : " + m.titre()).withStyle(ChatFormatting.GOLD), true);
        suivre(joueur, true);
    }

    static void abandonner(ServerPlayer joueur, Mission m) {
        Etat e = etat(joueur, m);
        if (e == null || e.statut != EN_COURS) {
            return;
        }
        // ce que le PNJ avait confié repart avec la mission (pas de burger gratuit en acceptant puis en abandonnant)
        for (ItemStack s : m.donnes()) {
            joueur.getInventory().clearOrCountMatchingItems(st -> st.is(s.getItem()), s.getCount(), joueur.inventoryMenu.getCraftSlots());
        }
        if (e.fois == 0) {
            etats(joueur).remove(m.id());
        } else {
            e.statut = FINIE;
        }
        Donnees.get(joueur.server).setDirty();
        suivre(joueur, true);
    }

    /** Rend la mission : prend ce qu'il faut apporter, donne la récompense. */
    static boolean rendre(ServerPlayer joueur, Mission m) {
        Etat e = etat(joueur, m);
        if (e == null || e.statut != EN_COURS || !complete(joueur, m)) {
            return false;
        }
        for (Objectif o : m.objectifs()) {
            if (o.type() == Genre.APPORTER) {
                Item item = objet(o.cible());
                joueur.getInventory().clearOrCountMatchingItems(s -> s.is(item), o.nombre(), joueur.inventoryMenu.getCraftSlots());
            }
        }
        e.statut = FINIE;
        e.fois++;
        e.fin = System.currentTimeMillis();
        Donnees.get(joueur.server).setDirty();
        if (m.argent() > 0) {
            Economie.crediter(joueur.server, joueur.getUUID(), m.argent());
        }
        for (ItemStack s : m.objets()) {
            donner(joueur, s.copy());
        }
        if (m.xp() > 0) {
            joueur.giveExperienceLevels(m.xp());
        }
        if (!m.ftb().isEmpty()) {
            joueur.server.getCommands().performPrefixedCommand(joueur.server.createCommandSourceStack().withSuppressedOutput().withPermission(4),
                    "ftbquests change_progress " + joueur.getGameProfile().getName() + " complete " + m.ftb());
        }
        joueur.level().playSound(null, joueur.blockPosition(), SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 0.8F, 1.1F);
        joueur.sendSystemMessage(Component.literal("✔ Mission accomplie : " + m.titre()).withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD)
                .append(Component.literal("  +" + recompense(m)).withStyle(ChatFormatting.YELLOW).withStyle(s -> s.withBold(false))));
        if (m.semaine() != null && rendues(joueur.server, etats(joueur)) == deLaSemaine(joueur.server).size()) {
            prime(joueur, m.semaine());
        }
        suivre(joueur, true);
        return true;
    }

    static void donner(ServerPlayer joueur, ItemStack s) {
        if (!joueur.getInventory().add(s)) {
            joueur.drop(s, false);
        }
    }

    /** Un objectif « parler » de cette mission est rempli (la question a été posée au bon PNJ). */
    static void parle(ServerPlayer joueur, Mission m, int i) {
        Etat e = etat(joueur, m);
        if (e == null || e.statut != EN_COURS) {
            return;
        }
        e.compte[i] = 1;
        Donnees.get(joueur.server).setDirty();
        avancee(joueur, m);
    }

    private static void avancee(ServerPlayer joueur, Mission m) {
        if (complete(joueur, m)) {
            joueur.displayClientMessage(Component.literal("✔ " + m.titre() + " : retourne voir " + Dialogues.nomCourt(m.qui()) + " !")
                    .withStyle(ChatFormatting.GREEN), true);
            joueur.level().playSound(null, joueur.blockPosition(), SoundEvents.NOTE_BLOCK_CHIME.value(), SoundSource.PLAYERS, 0.8F, 1.4F);
        }
        suivre(joueur, true);
    }

    /** Pour le reste du mod : un événement compté par les missions (« contrat » : un contrat de Mehdi rempli…). */
    public static void evenement(ServerPlayer joueur, String nom) {
        compter(joueur, Genre.EVENEMENT, nom::equals);
    }

    private static void compter(ServerPlayer joueur, Genre type, java.util.function.Predicate<String> cible) {
        for (Mission m : missions.values()) {
            Etat e = etat(joueur, m);
            if (e == null || e.statut != EN_COURS) {
                continue;
            }
            for (int i = 0; i < m.objectifs().size(); i++) {
                Objectif o = m.objectifs().get(i);
                if (o.type() == type && e.compte[i] < o.nombre() && cible.test(o.cible())) {
                    e.compte[i]++;
                    Donnees.get(joueur.server).setDirty();
                    if (e.compte[i] < o.nombre()) {
                        joueur.displayClientMessage(Component.literal(m.titre() + " : " + e.compte[i] + "/" + o.nombre()).withStyle(ChatFormatting.YELLOW), true);
                        suivre(joueur, true);
                    } else {
                        avancee(joueur, m);
                    }
                }
            }
        }
    }

    private static boolean correspond(EntityType<?> type, String cible) {
        if (cible.startsWith("#")) {
            return type.is(TagKey.create(Registries.ENTITY_TYPE, ResourceLocation.parse(cible.substring(1))));
        }
        for (String c : cible.split("\\|")) {
            if (BuiltInRegistries.ENTITY_TYPE.getKey(type).toString().equals(c)) {
                return true;
            }
        }
        return false;
    }

    // ================================================================ le suivi à l'écran

    public record Ligne(String titre, List<String> objectifs, boolean prete) {
        static final StreamCodec<RegistryFriendlyByteBuf, Ligne> CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, Ligne::titre,
                ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()), Ligne::objectifs,
                ByteBufCodecs.BOOL, Ligne::prete,
                Ligne::new);
    }

    public record Suivi(List<Ligne> lignes) implements CustomPacketPayload {
        public static final Type<Suivi> TYPE = new Type<>(Cabane.id("missions_suivi"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Suivi> CODEC = Ligne.CODEC.apply(ByteBufCodecs.list()).map(Suivi::new, Suivi::lignes);

        @Override
        public Type<Suivi> type() {
            return TYPE;
        }
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        event.registrar("1").playToClient(Suivi.TYPE, Suivi.CODEC, (p, ctx) -> ctx.enqueueWork(() -> fr.cabane.client.ClientDialogue.suivi(p.lignes())));
    }

    private static final Map<UUID, List<Ligne>> ENVOYE = new HashMap<>();

    static List<Ligne> lignes(ServerPlayer joueur) {
        List<Ligne> l = new ArrayList<>();
        if (Donnees.get(joueur.server).caches.contains(joueur.getUUID())) {
            return l;
        }
        for (Mission m : missions.values()) {
            if (!enCours(joueur, m)) {
                continue;
            }
            boolean prete = complete(joueur, m);
            List<String> objectifs = new ArrayList<>();
            if (prete) {
                objectifs.add("→ Retourne voir " + Dialogues.nomCourt(m.qui()));
            } else {
                for (int i = 0; i < m.objectifs().size(); i++) {
                    objectifs.add(ligne(joueur, m, i));
                }
            }
            l.add(new Ligne(m.titre(), objectifs, prete));
        }
        return l;
    }

    /** Envoie le suivi au joueur s'il a changé (ou toujours, si forcer). */
    static void suivre(ServerPlayer joueur, boolean forcer) {
        List<Ligne> l = lignes(joueur);
        if (forcer || !l.equals(ENVOYE.get(joueur.getUUID()))) {
            ENVOYE.put(joueur.getUUID(), l);
            PacketDistributor.sendToPlayer(joueur, new Suivi(l));
        }
    }

    /** /cabane missions : la liste dans le chat ; « cacher » masque le suivi à l'écran (ou le remet). */
    public static void lister(ServerPlayer joueur) {
        List<Ligne> l = lignes(joueur);
        if (Donnees.get(joueur.server).caches.contains(joueur.getUUID())) {
            joueur.sendSystemMessage(Component.literal("Le suivi des missions est masqué (/cabane missions cacher pour le remettre).").withStyle(ChatFormatting.GRAY));
        }
        long enCours = missions.values().stream().filter(m -> enCours(joueur, m)).count();
        if (enCours == 0) {
            joueur.sendSystemMessage(Component.literal("Aucune mission en cours. Parle aux habitants de Beauvais : ceux qui ont du travail pour toi ont un « ❗ » dans leur conversation.")
                    .withStyle(ChatFormatting.GRAY));
            return;
        }
        for (Mission m : missions.values()) {
            if (!enCours(joueur, m)) {
                continue;
            }
            joueur.sendSystemMessage(Component.literal("✦ " + m.titre() + " — " + Dialogues.nomCourt(m.qui())).withStyle(ChatFormatting.GOLD));
            for (int i = 0; i < m.objectifs().size(); i++) {
                joueur.sendSystemMessage(Component.literal("   " + ligne(joueur, m, i)).withStyle(ChatFormatting.GRAY));
            }
        }
    }

    public static void cacher(ServerPlayer joueur) {
        var caches = Donnees.get(joueur.server).caches;
        if (!caches.remove(joueur.getUUID())) {
            caches.add(joueur.getUUID());
        }
        Donnees.get(joueur.server).setDirty();
        suivre(joueur, true);
    }

    @EventBusSubscriber(modid = Cabane.MODID)
    public static final class Evenements {
        @SubscribeEvent
        public static void mort(LivingDeathEvent event) {
            if (event.getSource().getEntity() instanceof ServerPlayer joueur) {
                EntityType<?> type = event.getEntity().getType();
                compter(joueur, Genre.TUER, c -> correspond(type, c));
            }
        }

        @SubscribeEvent
        public static void connexion(PlayerEvent.PlayerLoggedInEvent event) {
            if (event.getEntity() instanceof ServerPlayer joueur) {
                suivre(joueur, true);
                annoncer(joueur);
            }
        }

        @SubscribeEvent
        public static void deconnexion(PlayerEvent.PlayerLoggedOutEvent event) {
            ENVOYE.remove(event.getEntity().getUUID());
        }

        /** Les objets à apporter et les lieux à découvrir : on regarde chaque seconde où en est chacun. */
        @SubscribeEvent
        public static void tick(ServerTickEvent.Post event) {
            if (event.getServer().getTickCount() % 20 != 7) {
                return;
            }
            // lundi, minuit : les quêtes de la semaine changent, même serveur allumé
            if (event.getServer().getTickCount() % 1200 == 7) {
                long n = numeroSemaine(event.getServer());
                if (derniereSemaine == Long.MIN_VALUE) {
                    derniereSemaine = n;
                } else if (n != derniereSemaine) {
                    changementDeSemaine(event.getServer());
                }
            }
            for (ServerPlayer joueur : event.getServer().getPlayerList().getPlayers()) {
                if (ENVOYE.containsKey(joueur.getUUID()) && !ENVOYE.get(joueur.getUUID()).isEmpty()) {
                    suivre(joueur, false);
                }
            }
        }

        private Evenements() {
        }
    }
}
