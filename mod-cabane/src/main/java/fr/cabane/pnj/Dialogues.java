package fr.cabane.pnj;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import fr.cabane.Cabane;
import fr.cabane.economie.Economie;
import io.netty.buffer.ByteBuf;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.GsonHelper;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/**
 * Les conversations avec les habitants de Beauvais. Un clic droit sur un PNJ (tag « pnj.&lt;fichier&gt; ») ouvre notre
 * propre fenêtre de dialogue, à la place de celle d'Easy NPC : les boutons ouvrent directement les boutiques, la forge,
 * les contrats, le livre de quêtes, et chacun propose ses missions (voir {@link Missions}). Les textes sont dans
 * data/cabane/dialogues/&lt;fichier&gt;.json, écrits par outils/pnj.mjs.
 */
public final class Dialogues extends SimpleJsonResourceReloadListener {
    public static final Dialogues INSTANCE = new Dialogues();
    private static Map<String, Dialogue> dialogues = Map.of();

    /** Un bouton : vers une autre page, une action (boutique, forge…), ou rien (on s'en va). */
    record Bouton(String texte, String vers, String action) {
    }

    /** ftb : la quête du livre (identifiant FTB Quests) cochée quand on arrive sur cette page. */
    record Page(String titre, List<String> textes, List<Bouton> boutons, String ftb) {
    }

    /** qui : le nom du PNJ pour les missions (le même pour toutes ses versions : vegeta, vegeta_arme…). */
    record Dialogue(String id, String qui, String nom, int couleur, String voix, Map<String, Page> pages) {
    }

    // les styles des boutons, pour leur couleur et leur icône côté client
    public static final int NORMAL = 0;
    public static final int DISPO = 1;
    public static final int EN_COURS = 2;
    public static final int RENDRE = 3;
    public static final int QUESTION = 4;
    public static final int BOUTIQUE = 5;
    public static final int AU_REVOIR = 6;

    private Dialogues() {
        super(new Gson(), "dialogues");
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> fichiers, ResourceManager manager, ProfilerFiller profiler) {
        Map<String, Dialogue> m = new HashMap<>();
        fichiers.forEach((id, json) -> {
            try {
                JsonObject o = json.getAsJsonObject();
                Map<String, Page> pages = new LinkedHashMap<>();
                for (var e : GsonHelper.getAsJsonObject(o, "pages").entrySet()) {
                    JsonObject p = e.getValue().getAsJsonObject();
                    List<String> textes = new ArrayList<>();
                    GsonHelper.getAsJsonArray(p, "textes").forEach(t -> textes.add(t.getAsString()));
                    List<Bouton> boutons = new ArrayList<>();
                    for (JsonElement b : GsonHelper.getAsJsonArray(p, "boutons")) {
                        JsonObject bo = b.getAsJsonObject();
                        boutons.add(new Bouton(GsonHelper.getAsString(bo, "texte"), GsonHelper.getAsString(bo, "vers", ""), GsonHelper.getAsString(bo, "action", "")));
                    }
                    pages.put(e.getKey(), new Page(GsonHelper.getAsString(p, "titre", ""), List.copyOf(textes), List.copyOf(boutons), GsonHelper.getAsString(p, "ftb", "")));
                }
                String nom = id.getPath();
                ChatFormatting couleur = ChatFormatting.getByName(GsonHelper.getAsString(o, "couleur", "white"));
                m.put(nom, new Dialogue(nom, GsonHelper.getAsString(o, "qui", nom), GsonHelper.getAsString(o, "nom"),
                        couleur != null && couleur.getColor() != null ? couleur.getColor() : 0xFFFFFF, GsonHelper.getAsString(o, "voix", nom), pages));
            } catch (Exception e) {
                Cabane.LOGGER.warn("Dialogue {} illisible : {}", id, e.toString());
            }
        });
        dialogues = m;
        Cabane.LOGGER.info("Dialogues des habitants : {}", dialogues.size());
    }

    /** Le prénom d'un habitant pour les missions (« Jean », « Sylvie »…) : son nom sans le titre. */
    static String nomCourt(String qui) {
        Dialogue d = dialogues.get(qui);
        if (d == null) {
            d = dialogues.values().stream().filter(x -> x.qui().equals(qui)).findFirst().orElse(null);
        }
        if (d == null) {
            return qui;
        }
        String nom = d.nom();
        int virgule = nom.indexOf(',');
        return virgule > 0 ? nom.substring(0, virgule) : nom;
    }

    // ================================================================ les paquets

    public record Choix(String texte, int style) {
        static final StreamCodec<ByteBuf, Choix> CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, Choix::texte,
                ByteBufCodecs.VAR_INT, Choix::style,
                Choix::new);
    }

    /** Serveur → client : une page de conversation. */
    public record Afficher(int entite, String nom, int couleur, String titre, String texte, List<Choix> choix) implements CustomPacketPayload {
        public static final Type<Afficher> TYPE = new Type<>(Cabane.id("dialogue_page"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Afficher> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, Afficher::entite,
                ByteBufCodecs.STRING_UTF8, Afficher::nom,
                ByteBufCodecs.INT, Afficher::couleur,
                ByteBufCodecs.STRING_UTF8, Afficher::titre,
                ByteBufCodecs.STRING_UTF8, Afficher::texte,
                Choix.CODEC.apply(ByteBufCodecs.list()), Afficher::choix,
                Afficher::new);

        @Override
        public Type<Afficher> type() {
            return TYPE;
        }
    }

    /** Client → serveur : le bouton choisi. */
    public record Choisir(int index) implements CustomPacketPayload {
        public static final Type<Choisir> TYPE = new Type<>(Cabane.id("dialogue_choix"));
        public static final StreamCodec<ByteBuf, Choisir> CODEC = ByteBufCodecs.VAR_INT.map(Choisir::new, Choisir::index);

        @Override
        public Type<Choisir> type() {
            return TYPE;
        }
    }

    /** Serveur → client : fermer la conversation (une boutique ou un autre écran va s'ouvrir). */
    public record Fermer(int rien) implements CustomPacketPayload {
        public static final Type<Fermer> TYPE = new Type<>(Cabane.id("dialogue_fermer"));
        public static final StreamCodec<ByteBuf, Fermer> CODEC = ByteBufCodecs.VAR_INT.map(Fermer::new, Fermer::rien);

        @Override
        public Type<Fermer> type() {
            return TYPE;
        }
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        event.registrar("1")
                .playToClient(Afficher.TYPE, Afficher.CODEC, (p, ctx) -> ctx.enqueueWork(() -> fr.cabane.client.ClientDialogue.page(p)))
                .playToClient(Fermer.TYPE, Fermer.CODEC, (p, ctx) -> ctx.enqueueWork(fr.cabane.client.ClientDialogue::fermer))
                .playToServer(Choisir.TYPE, Choisir.CODEC, (p, ctx) -> ctx.enqueueWork(() -> {
                    if (ctx.player() instanceof ServerPlayer joueur) {
                        choisir(joueur, p.index());
                    }
                }));
    }

    // ================================================================ la conversation en cours de chaque joueur

    private interface Suite {
        void faire(ServerPlayer joueur, Session s);
    }

    private static final class Session {
        final int entite;
        final Dialogue dialogue;
        List<Suite> suites = List.of();

        Session(int entite, Dialogue dialogue) {
            this.entite = entite;
            this.dialogue = dialogue;
        }
    }

    private static final Map<UUID, Session> SESSIONS = new HashMap<>();

    /** Le dialogue d'un PNJ du village (son tag « pnj.&lt;fichier&gt; »), ou null. */
    static Dialogue dialogueDe(Entity e) {
        for (String tag : e.getTags()) {
            if (tag.startsWith("pnj.")) {
                return dialogues.get(tag.substring(4));
            }
        }
        return null;
    }

    public static void ouvrir(ServerPlayer joueur, Entity pnj, Dialogue d) {
        Session s = new Session(pnj.getId(), d);
        SESSIONS.put(joueur.getUUID(), s);
        // sa voix (s'il en a une d'enregistrée), rien que pour celui qui lui parle
        if (!d.voix().isEmpty()) {
            SoundEvent voix = SoundEvent.createVariableRangeEvent(Cabane.id("voix." + d.voix()));
            joueur.connection.send(new ClientboundSoundPacket(Holder.direct(voix), SoundSource.NEUTRAL, pnj.getX(), pnj.getEyeY(), pnj.getZ(),
                    1F, 1F, joueur.getRandom().nextLong()));
        }
        page(joueur, s, "default");
    }

    /** /cabane parler : comme un clic droit sur ce PNJ. */
    public static void parler(ServerPlayer joueur, Entity pnj) {
        Dialogue d = dialogueDe(pnj);
        if (d != null) {
            ouvrir(joueur, pnj, d);
        }
    }

    /** /cabane dialogue : comme un clic sur cette réponse. */
    public static void choisirPourTest(ServerPlayer joueur, int index) {
        choisir(joueur, index);
    }

    private static void choisir(ServerPlayer joueur, int index) {
        Session s = SESSIONS.get(joueur.getUUID());
        if (s == null || index < 0 || index >= s.suites.size()) {
            return;
        }
        Entity pnj = joueur.level().getEntity(s.entite);
        if (pnj == null || pnj.distanceToSqr(joueur) > 12 * 12) {
            SESSIONS.remove(joueur.getUUID());
            PacketDistributor.sendToPlayer(joueur, new Fermer(0));
            return;
        }
        s.suites.get(index).faire(joueur, s);
    }

    private static void fermer(ServerPlayer joueur) {
        SESSIONS.remove(joueur.getUUID());
        PacketDistributor.sendToPlayer(joueur, new Fermer(0));
    }

    private static void envoyer(ServerPlayer joueur, Session s, String titre, String texte, List<Choix> choix, List<Suite> suites) {
        s.suites = List.copyOf(suites);
        PacketDistributor.sendToPlayer(joueur, new Afficher(s.entite, s.dialogue.nom(), s.dialogue.couleur(), titre,
                texte.replace("{joueur}", joueur.getGameProfile().getName()), choix));
    }

    /** Une page du dialogue ; sur la première (« default »), les missions et les questions du moment s'ajoutent en haut. */
    private static void page(ServerPlayer joueur, Session s, String nom) {
        Page p = s.dialogue.pages().getOrDefault(nom, s.dialogue.pages().get("default"));
        if (p == null) {
            fermer(joueur);
            return;
        }
        List<Choix> choix = new ArrayList<>();
        List<Suite> suites = new ArrayList<>();
        if (nom.equals("default")) {
            missions(joueur, s, choix, suites);
        }
        for (Bouton b : p.boutons()) {
            if (!b.vers().isEmpty()) {
                choix.add(new Choix(b.texte(), NORMAL));
                suites.add((j, ss) -> page(j, ss, b.vers()));
            } else if (b.action().startsWith("tresor:")) {
                // les cartes des reliques de Karim : le prix du moment, et celles déjà vendues
                String lieu = b.action().split(":")[1];
                boolean vendue = fr.cabane.hub.HubData.get(joueur.server).carteDejaVendue(lieu);
                choix.add(new Choix(b.texte() + " · " + Economie.format(prixCarte(joueur, lieu)) + (vendue ? ", déjà achetée" : ""), BOUTIQUE));
                suites.add((j, ss) -> action(j, ss, b.action()));
            } else if (!b.action().isEmpty()) {
                choix.add(new Choix(b.texte(), ecran(b.action()) ? BOUTIQUE : NORMAL));
                suites.add((j, ss) -> action(j, ss, b.action()));
            } else {
                choix.add(new Choix(b.texte(), AU_REVOIR));
                suites.add((j, ss) -> SESSIONS.remove(j.getUUID()));
            }
        }
        if (!p.ftb().isEmpty()) {
            ftb(joueur, "ftbquests change_progress " + joueur.getGameProfile().getName() + " complete " + p.ftb(), true);
        }
        String texte = p.textes().isEmpty() ? "…" : p.textes().get(joueur.getRandom().nextInt(p.textes().size()));
        envoyer(joueur, s, p.titre().isEmpty() ? nomCourt(s.dialogue.id()) : p.titre(), texte, choix, suites);
    }

    /** Les boutons des missions : questions à poser pour celles des autres, puis les siennes (à rendre, en cours, à prendre). */
    private static void missions(ServerPlayer joueur, Session s, List<Choix> choix, List<Suite> suites) {
        String qui = s.dialogue.qui();
        for (Missions.Mission m : missionsEnCours(joueur)) {
            for (int i = 0; i < m.objectifs().size(); i++) {
                Missions.Objectif o = m.objectifs().get(i);
                if (o.type() == Missions.Genre.PARLER && o.cible().equals(qui) && Missions.progres(joueur, m, i) < 1) {
                    int index = i;
                    choix.add(new Choix(o.question(), QUESTION));
                    suites.add((j, ss) -> question(j, ss, m, index));
                }
            }
        }
        // les quêtes de la semaine sont regroupées derrière un seul bouton
        List<Missions.Mission> semaine = Missions.deLaSemaine(joueur.server).stream().filter(m -> m.qui().equals(qui)).toList();
        if (!semaine.isEmpty()) {
            int style = NORMAL;
            for (Missions.Mission m : semaine) {
                int st = Missions.enCours(joueur, m) ? (Missions.complete(joueur, m) ? RENDRE : EN_COURS) : Missions.disponible(joueur, m) ? DISPO : NORMAL;
                style = st == RENDRE || style == RENDRE ? RENDRE : st == DISPO || style == DISPO ? DISPO : Math.max(style, st);
            }
            choix.add(new Choix("Les quêtes de la semaine", style));
            suites.add(Dialogues::semaine);
        }
        for (Missions.Mission m : Missions.de(qui)) {
            if (m.semaine() != null) {
                continue;
            }
            if (Missions.enCours(joueur, m)) {
                if (Missions.complete(joueur, m)) {
                    choix.add(new Choix(m.titre(), RENDRE));
                    suites.add((j, ss) -> rendre(j, ss, m));
                } else {
                    choix.add(new Choix(m.titre(), EN_COURS));
                    suites.add((j, ss) -> suivi(j, ss, m, false));
                }
            } else if (Missions.disponible(joueur, m)) {
                choix.add(new Choix(m.titre(), DISPO));
                suites.add((j, ss) -> offre(j, ss, m));
            }
        }
    }

    /** Les quêtes de la semaine : le thème, ce qu'il reste à faire, la prime, et ceux qui ont déjà tout fini. */
    private static void semaine(ServerPlayer joueur, Session s) {
        Missions.Semaine w = Missions.semaine(joueur.server);
        if (w == null) {
            page(joueur, s, "default");
            return;
        }
        List<Choix> choix = new ArrayList<>();
        List<Suite> suites = new ArrayList<>();
        List<Missions.Mission> liste = Missions.deLaSemaine(joueur.server).stream().filter(m -> m.qui().equals(s.dialogue.qui())).toList();
        for (Missions.Mission m : liste) {
            if (Missions.enCours(joueur, m)) {
                boolean prete = Missions.complete(joueur, m);
                choix.add(new Choix(m.titre(), prete ? RENDRE : EN_COURS));
                suites.add(prete ? (j, ss) -> rendre(j, ss, m) : (j, ss) -> suivi(j, ss, m, false));
            } else if (Missions.disponible(joueur, m)) {
                choix.add(new Choix(m.titre(), DISPO));
                suites.add((j, ss) -> offre(j, ss, m));
            }
        }
        retourEtAuRevoir(choix, suites);
        int faites = Missions.rendues(joueur.server, Missions.etats(joueur));
        StringBuilder t = new StringBuilder(w.texte());
        t.append("\n\n");
        if (faites >= liste.size()) {
            t.append("Tu as tout rendu cette semaine. Reviens lundi : il y aura autre chose.");
        } else {
            t.append("Rendues : ").append(faites).append("/").append(liste.size());
            if (w.bonus() > 0) {
                t.append(" — toutes rendues : +").append(Economie.format(w.bonus()));
            }
        }
        List<String> champions = Missions.champions(joueur.server);
        if (!champions.isEmpty()) {
            t.append("\nDéjà tout fini : ").append(String.join(", ", champions));
        }
        envoyer(joueur, s, w.nom(), t.toString(), choix, suites);
    }

    private static List<Missions.Mission> missionsEnCours(ServerPlayer joueur) {
        return Missions.toutes().stream().filter(m -> Missions.enCours(joueur, m)).toList();
    }

    private static String objectifs(ServerPlayer joueur, Missions.Mission m) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < m.objectifs().size(); i++) {
            sb.append('\n').append(Missions.ligne(joueur, m, i));
        }
        return sb.toString();
    }

    private static void retourEtAuRevoir(List<Choix> choix, List<Suite> suites) {
        choix.add(new Choix("Retour", NORMAL));
        suites.add((j, ss) -> page(j, ss, "default"));
        choix.add(new Choix("Au revoir", AU_REVOIR));
        suites.add((j, ss) -> SESSIONS.remove(j.getUUID()));
    }

    /** « ❗ » : ce que le PNJ demande, ce qu'il faut faire, ce qu'on y gagne. */
    private static void offre(ServerPlayer joueur, Session s, Missions.Mission m) {
        List<Choix> choix = new ArrayList<>();
        List<Suite> suites = new ArrayList<>();
        choix.add(new Choix("J'accepte !", DISPO));
        suites.add((j, ss) -> {
            Missions.accepter(j, m);
            suivi(j, ss, m, true);
        });
        choix.add(new Choix("Pas maintenant", NORMAL));
        suites.add((j, ss) -> page(j, ss, "default"));
        envoyer(joueur, s, m.titre(), m.pitch() + "\n\nÀ faire :" + objectifs(joueur, m) + "\n\nRécompense : " + Missions.recompense(m), choix, suites);
    }

    /** Une mission en cours : où on en est. */
    private static void suivi(ServerPlayer joueur, Session s, Missions.Mission m, boolean accepte) {
        List<Choix> choix = new ArrayList<>();
        List<Suite> suites = new ArrayList<>();
        retourEtAuRevoir(choix, suites);
        if (!accepte) {
            choix.add(new Choix("Abandonner cette mission", NORMAL));
            suites.add((j, ss) -> {
                Missions.abandonner(j, m);
                page(j, ss, "default");
            });
        }
        String intro = accepte ? (m.rappel().isEmpty() ? "Merci, je compte sur toi !" : m.rappel())
                : (m.rappel().isEmpty() ? "Alors, ça avance ?" : m.rappel());
        envoyer(joueur, s, m.titre(), intro + "\n" + objectifs(joueur, m), choix, suites);
    }

    /** « ✔ » : on rend la mission ; les suivantes du même PNJ s'enchaînent tout de suite. */
    private static void rendre(ServerPlayer joueur, Session s, Missions.Mission m) {
        if (!Missions.rendre(joueur, m)) {
            suivi(joueur, s, m, false);
            return;
        }
        List<Choix> choix = new ArrayList<>();
        List<Suite> suites = new ArrayList<>();
        for (Missions.Mission suivante : Missions.de(s.dialogue.qui())) {
            if (Missions.disponible(joueur, suivante)) {
                choix.add(new Choix(suivante.titre(), DISPO));
                suites.add((j, ss) -> offre(j, ss, suivante));
            }
        }
        retourEtAuRevoir(choix, suites);
        envoyer(joueur, s, m.titre(), m.merci() + "\n\n✔ Récompense : " + Missions.recompense(m), choix, suites);
    }

    /** « 💬 » : la question d'une mission posée au bon PNJ ; il répond (et parfois prend ou donne quelque chose). */
    private static void question(ServerPlayer joueur, Session s, Missions.Mission m, int i) {
        Missions.Objectif o = m.objectifs().get(i);
        List<Choix> choix = new ArrayList<>();
        List<Suite> suites = new ArrayList<>();
        retourEtAuRevoir(choix, suites);
        if (!o.prend().isEmpty()) {
            Item item = BuiltInRegistries.ITEM.get(ResourceLocation.parse(o.prend()));
            if (joueur.getInventory().countItem(item) < 1) {
                envoyer(joueur, s, m.titre(), "(Il te faut : " + fr.cabane.Noms.de(new ItemStack(item)) + ". Tu ne l'as plus sur toi…)", choix, suites);
                return;
            }
            joueur.getInventory().clearOrCountMatchingItems(st -> st.is(item), 1, joueur.inventoryMenu.getCraftSlots());
        }
        if (!o.donne().isEmpty()) {
            Missions.donner(joueur, new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.parse(o.donne()))));
        }
        Missions.parle(joueur, m, i);
        envoyer(joueur, s, nomCourt(s.dialogue.id()), o.reponse(), choix, suites);
    }

    // ================================================================ les actions des boutons

    /** Ces actions ouvrent un autre écran (la conversation se ferme) ; les autres répondent par une page. */
    private static boolean ecran(String action) {
        return !action.startsWith("hejj_") && !action.equals("soigner") && !action.startsWith("payer:");
    }

    private static void action(ServerPlayer joueur, Session s, String action) {
        String[] a = action.split(":");
        switch (a[0]) {
            case "hejj_demande" -> {
                List<Choix> choix = new ArrayList<>();
                List<Suite> suites = new ArrayList<>();
                choix.add(new Choix("J'ai ce qu'il te faut", NORMAL));
                suites.add((j, ss) -> action(j, ss, "hejj_livrer"));
                retourEtAuRevoir(choix, suites);
                envoyer(joueur, s, "La demande du jour", fr.cabane.hub.DemandesHejj.demandeTexte(joueur), choix, suites);
            }
            case "hejj_livrer" -> {
                List<Choix> choix = new ArrayList<>();
                List<Suite> suites = new ArrayList<>();
                retourEtAuRevoir(choix, suites);
                envoyer(joueur, s, "La demande du jour", fr.cabane.hub.DemandesHejj.livrerTexte(joueur), choix, suites);
            }
            case "soigner" -> soigner(joueur, s);
            // payer:<prix>:<page> : un service payant (un secret de Gribbs…), puis la page qui va avec
            case "payer" -> {
                long prix = Long.parseLong(a[1]);
                if (!Economie.payer(joueur, prix)) {
                    List<Choix> choix = new ArrayList<>();
                    List<Suite> suites = new ArrayList<>();
                    retourEtAuRevoir(choix, suites);
                    envoyer(joueur, s, nomCourt(s.dialogue.id()), "Pas d'argent, pas de service. Il faut " + Economie.format(prix) + ".", choix, suites);
                    return;
                }
                page(joueur, s, a[2]);
            }
            default -> {
                fermer(joueur);
                ecranAction(joueur, a);
            }
        }
    }

    private static void ecranAction(ServerPlayer joueur, String[] a) {
        switch (a[0]) {
            case "boutique" -> {
                if (a.length > 1) {
                    fr.cabane.boutique.Boutique.ouvrirChez(joueur, a[1]);
                } else {
                    fr.cabane.boutique.Boutique.ouvrir(joueur);
                }
            }
            case "forge" -> fr.cabane.boutique.Forge.ouvrir(joueur, a.length > 1 ? a[1] : "");
            case "contrats" -> fr.cabane.boutique.Contrats.ouvrir(joueur);
            case "fourgue" -> fr.cabane.drogue.MarcheNoir.ouvrir(joueur);
            case "fourgue_vendre" -> fr.cabane.drogue.MarcheNoir.vendre(joueur);
            case "mcbeauvais" -> fr.cabane.bouffe.FastFood.ouvrir(joueur, null);
            // le livre de quêtes, ouvert au bon chapitre (ou là où on l'avait laissé, si ce chapitre n'existe pas)
            case "livre" -> {
                if (a.length < 2 || !ftb(joueur, "ftbquests open_book " + a[1], false)) {
                    ftb(joueur, "ftbquests open_book", false);
                }
            }
            // tresor:<lieu>:<distance>:<prix> : Karim vend la carte d'un donjon à relique (le prix de départ ; voir prixCarte)
            case "tresor" -> {
                long prix = prixCarte(joueur, a[1]);
                if (Economie.payer(joueur, prix)) {
                    fr.cabane.hub.HubData.get(joueur.server).carteVendue(a[1]);
                    ftb(joueur, "cabane carte " + joueur.getGameProfile().getName() + " " + a[1] + " " + a[2], true);
                } else {
                    joueur.sendSystemMessage(Component.literal("Karim : « Chouf, cette carte coûte " + Economie.format(prix) + ". Reviens quand t'auras les sous. »")
                            .withStyle(ChatFormatting.AQUA));
                }
            }
            default -> Cabane.LOGGER.warn("Action de dialogue inconnue : {}", String.join(":", a));
        }
    }

    /**
     * Le prix d'une carte de relique chez Karim : 300 $ la première vendue sur le serveur, puis trois fois plus à chaque
     * nouvelle. Une carte rachetée (perdue…) coûte le prix qu'elle avait la première fois.
     */
    static long prixCarte(ServerPlayer joueur, String lieu) {
        long prix = 300;
        for (int i = fr.cabane.hub.HubData.get(joueur.server).rangCarte(lieu); i > 0; i--) {
            prix *= 3;
        }
        return prix;
    }

    /** Une commande d'un autre mod : en tant que serveur, ou au nom du joueur (avec les droits qu'il faut). Vrai si elle a marché. */
    private static boolean ftb(ServerPlayer joueur, String commande, boolean serveur) {
        var source = (serveur ? joueur.server.createCommandSourceStack() : joueur.createCommandSourceStack()).withSuppressedOutput().withPermission(4);
        try {
            return joueur.server.getCommands().getDispatcher().execute(commande, source) > 0;
        } catch (Exception e) {
            Cabane.LOGGER.debug("Commande « {} » refusée : {}", commande, e.getMessage());
            return false;
        }
    }

    /** Nyota soigne : la vie, la faim, et tout ce qui fait du mal (même la gueule de bois). */
    private static void soigner(ServerPlayer joueur, Session s) {
        long prix = 40;
        List<Choix> choix = new ArrayList<>();
        List<Suite> suites = new ArrayList<>();
        retourEtAuRevoir(choix, suites);
        boolean malade = joueur.getHealth() < joueur.getMaxHealth()
                || joueur.getActiveEffects().stream().anyMatch(e -> e.getEffect().value().getCategory() == MobEffectCategory.HARMFUL);
        if (!malade) {
            envoyer(joueur, s, "Les soins de Nyota", "Tu es en pleine forme, voyageur. Garde ton argent pour les jours de raid.", choix, suites);
            return;
        }
        if (!Economie.payer(joueur, prix)) {
            envoyer(joueur, s, "Les soins de Nyota", "Les herbes ne poussent pas toutes seules : il me faut " + Economie.format(prix) + ".", choix, suites);
            return;
        }
        joueur.setHealth(joueur.getMaxHealth());
        joueur.getFoodData().eat(6, 0.6F);
        for (MobEffectInstance e : List.copyOf(joueur.getActiveEffects())) {
            if (e.getEffect().value().getCategory() == MobEffectCategory.HARMFUL) {
                joueur.removeEffect(e.getEffect());
            }
        }
        joueur.level().playSound(null, joueur.blockPosition(), SoundEvents.BREWING_STAND_BREW, SoundSource.PLAYERS, 0.8F, 1.3F);
        envoyer(joueur, s, "Les soins de Nyota", "Bois ça. Non, ne sens pas, bois. … Voilà. Tes blessures se referment, et ta tête ne tourne plus. "
                + "(" + Economie.format(prix) + ")", choix, suites);
    }

    // ================================================================ le clic droit sur un habitant

    @EventBusSubscriber(modid = Cabane.MODID)
    public static final class Evenements {
        @SubscribeEvent(priority = EventPriority.HIGHEST)
        public static void clic(PlayerInteractEvent.EntityInteract event) {
            Entity cible = event.getTarget();
            if (!cible.getTags().contains("cabane_pnj") || !cible.isAlive()) {
                return;
            }
            // l'événement de Vegeta, armé par l'hôte, se lance d'un clic droit sur lui (fr.cabane.vegeta)
            if (fr.cabane.vegeta.EvenementVegeta.attendUnClic(cible)) {
                return;
            }
            // la baguette d'Easy NPC (pour l'hôte qui veut modifier un PNJ) passe toujours
            ItemStack tenu = event.getEntity().getItemInHand(event.getHand());
            if (!tenu.isEmpty() && BuiltInRegistries.ITEM.getKey(tenu.getItem()).getNamespace().startsWith("easy_npc")) {
                return;
            }
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.SUCCESS);
            if (event.getHand() != InteractionHand.MAIN_HAND || !(event.getEntity() instanceof ServerPlayer joueur)) {
                return;
            }
            Dialogue d = dialogueDe(cible);
            if (d == null) {
                joueur.displayClientMessage(Component.literal("…").withStyle(ChatFormatting.GRAY), true);
                return;
            }
            ouvrir(joueur, cible, d);
        }

        /** Le clic « précis » passe avant le clic normal : on l'empêche d'arriver à Easy NPC, sans bloquer le clic normal. */
        @SubscribeEvent(priority = EventPriority.HIGHEST)
        public static void clicPrecis(PlayerInteractEvent.EntityInteractSpecific event) {
            if (event.getTarget().getTags().contains("cabane_pnj")) {
                ItemStack tenu = event.getEntity().getItemInHand(event.getHand());
                if (tenu.isEmpty() || !BuiltInRegistries.ITEM.getKey(tenu.getItem()).getNamespace().startsWith("easy_npc")) {
                    event.setCanceled(true);
                    event.setCancellationResult(InteractionResult.PASS);
                }
            }
        }

        @SubscribeEvent
        public static void deconnexion(PlayerEvent.PlayerLoggedOutEvent event) {
            SESSIONS.remove(event.getEntity().getUUID());
        }

        private Evenements() {
        }
    }
}
