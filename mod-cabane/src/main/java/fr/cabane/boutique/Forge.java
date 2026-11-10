package fr.cabane.boutique;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import fr.cabane.Cabane;
import fr.cabane.client.ClientForge;
import fr.cabane.economie.Economie;
import fr.cabane.hub.HubData;
import io.netty.buffer.ByteBuf;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.GsonHelper;
import net.minecraft.util.Mth;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import org.joml.Vector3f;

/**
 * La forge de Brokk : des objets rares des mods du pack, que lui seul sait faire. Il faut lui rapporter les matériaux et
 * payer le travail, puis le regarder forger : la pièce chauffe sur l'enclume monumentale, le marteau tombe, la pièce est
 * trempée, et Brokk la tend à son propriétaire. Une seule pièce à la fois sur l'enclume : les autres attendent leur tour.
 * Les recettes sont des fichiers data/cabane/forge/*.json : {"resultat": "mowziesmobs:wrought_axe", "prix": 15000,
 * "ingredients": [["minecraft:iron_ingot", 12]], "categorie": "relique", "indice": "…", "temps": 40}.
 */
public final class Forge extends SimpleJsonResourceReloadListener {
    public static final Forge INSTANCE = new Forge();
    private static List<Recette> recettes = List.of();

    /** Le rythme du marteau (en ticks) : il se lève, retombe au tick FRAPPE du cycle, et rebondit. */
    public static final int CYCLE = 20;
    public static final int FRAPPE = 17;
    /** La part du temps passée à marteler ; ensuite, la pièce refroidit dans la vapeur. */
    public static final float MARTELAGE = 0.82F;
    /** La pièce finie s'élève au-dessus de l'enclume pendant ce temps, avant que Brokk la tende. */
    public static final int FINAL = 50;

    public record Recette(String id, ItemStack resultat, List<ItemStack> ingredients, long prix, int ordre, String categorie,
                          String indice, int temps, String nom, Map<String, Integer> enchantements) {
        /** La pièce telle que Brokk la tend : avec son nom et ses enchantements (il faut les registres du monde). */
        ItemStack objet(HolderLookup.Provider registres) {
            ItemStack s = resultat.copy();
            enchantements.forEach((id, niveau) -> registres.lookupOrThrow(Registries.ENCHANTMENT)
                    .get(ResourceKey.create(Registries.ENCHANTMENT, ResourceLocation.parse(id)))
                    .ifPresent(e -> s.enchant(e, niveau)));
            if (!nom.isEmpty()) {
                s.set(DataComponents.CUSTOM_NAME, Component.literal(nom).withStyle(st -> st.withItalic(false).withColor(ChatFormatting.GOLD)));
            }
            return s;
        }
    }

    public record Vue(String id, ItemStack resultat, List<ItemStack> ingredients, long prix, String categorie, String indice, int temps) {
        public static final StreamCodec<RegistryFriendlyByteBuf, Vue> CODEC = StreamCodec.of((buf, v) -> {
            ByteBufCodecs.STRING_UTF8.encode(buf, v.id());
            ItemStack.STREAM_CODEC.encode(buf, v.resultat());
            ItemStack.STREAM_CODEC.apply(ByteBufCodecs.list()).encode(buf, v.ingredients());
            ByteBufCodecs.VAR_LONG.encode(buf, v.prix());
            ByteBufCodecs.STRING_UTF8.encode(buf, v.categorie());
            ByteBufCodecs.STRING_UTF8.encode(buf, v.indice());
            ByteBufCodecs.VAR_INT.encode(buf, v.temps());
        }, buf -> new Vue(ByteBufCodecs.STRING_UTF8.decode(buf), ItemStack.STREAM_CODEC.decode(buf),
                ItemStack.STREAM_CODEC.apply(ByteBufCodecs.list()).decode(buf), ByteBufCodecs.VAR_LONG.decode(buf),
                ByteBufCodecs.STRING_UTF8.decode(buf), ByteBufCodecs.STRING_UTF8.decode(buf), ByteBufCodecs.VAR_INT.decode(buf)));
    }

    /**
     * L'écran de la forge ; statut dit où en est la pièce du joueur, fermer referme l'écran pour qu'il regarde Brokk, onglet
     * (s'il n'est pas vide) choisit la catégorie affichée.
     */
    public record Ouvrir(List<Vue> recettes, String statut, boolean fermer, String onglet) implements CustomPacketPayload {
        public static final Type<Ouvrir> TYPE = new Type<>(Cabane.id("forge_ouvrir"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Ouvrir> CODEC = StreamCodec.composite(
                Vue.CODEC.apply(ByteBufCodecs.list()), Ouvrir::recettes,
                ByteBufCodecs.STRING_UTF8, Ouvrir::statut,
                ByteBufCodecs.BOOL, Ouvrir::fermer,
                ByteBufCodecs.STRING_UTF8, Ouvrir::onglet,
                Ouvrir::new);

        @Override
        public Type<Ouvrir> type() {
            return TYPE;
        }
    }

    public record Forger(String id) implements CustomPacketPayload {
        public static final Type<Forger> TYPE = new Type<>(Cabane.id("forge_forger"));
        public static final StreamCodec<ByteBuf, Forger> CODEC = ByteBufCodecs.stringUtf8(128).map(Forger::new, Forger::id);

        @Override
        public Type<Forger> type() {
            return TYPE;
        }
    }

    /**
     * Ce qui se passe sur l'enclume, pour ceux qui sont autour : etat 1 = Brokk forge (ecoule ticks sur duree), 2 = la pièce
     * est finie et s'élève. toi : c'est la pièce de celui qui reçoit le paquet.
     */
    public record Chantier(int etat, int numero, Vector3f enclume, Vector3f epaule, ItemStack objet, int ecoule, int duree,
                           boolean toi) implements CustomPacketPayload {
        public static final Type<Chantier> TYPE = new Type<>(Cabane.id("forge_chantier"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Chantier> CODEC = StreamCodec.of((buf, c) -> {
            ByteBufCodecs.VAR_INT.encode(buf, c.etat());
            ByteBufCodecs.VAR_INT.encode(buf, c.numero());
            ByteBufCodecs.VECTOR3F.encode(buf, c.enclume());
            ByteBufCodecs.VECTOR3F.encode(buf, c.epaule());
            ItemStack.OPTIONAL_STREAM_CODEC.encode(buf, c.objet());
            ByteBufCodecs.VAR_INT.encode(buf, c.ecoule());
            ByteBufCodecs.VAR_INT.encode(buf, c.duree());
            ByteBufCodecs.BOOL.encode(buf, c.toi());
        }, buf -> new Chantier(ByteBufCodecs.VAR_INT.decode(buf), ByteBufCodecs.VAR_INT.decode(buf), ByteBufCodecs.VECTOR3F.decode(buf),
                ByteBufCodecs.VECTOR3F.decode(buf), ItemStack.OPTIONAL_STREAM_CODEC.decode(buf), ByteBufCodecs.VAR_INT.decode(buf),
                ByteBufCodecs.VAR_INT.decode(buf), ByteBufCodecs.BOOL.decode(buf)));

        @Override
        public Type<Chantier> type() {
            return TYPE;
        }
    }

    private Forge() {
        super(new Gson(), "forge");
    }

    private static Item objet(String id) {
        return BuiltInRegistries.ITEM.get(ResourceLocation.parse(id));
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> fichiers, ResourceManager manager, ProfilerFiller profiler) {
        List<Recette> liste = new ArrayList<>();
        fichiers.forEach((id, json) -> {
            try {
                JsonObject o = json.getAsJsonObject();
                if (!o.has("resultat")) {
                    return; // un autre mod range autre chose dans un dossier « forge »
                }
                Item resultat = objet(GsonHelper.getAsString(o, "resultat"));
                if (resultat == Items.AIR) {
                    Cabane.LOGGER.info("Recette de forge {} ignorée : objet absent", id);
                    return; // le mod de cet objet n'est pas installé
                }
                List<ItemStack> ingredients = new ArrayList<>();
                for (JsonElement e : GsonHelper.getAsJsonArray(o, "ingredients")) {
                    Item item = objet(e.getAsJsonArray().get(0).getAsString());
                    if (item == Items.AIR) {
                        Cabane.LOGGER.info("Recette de forge {} ignorée : ingrédient absent", id);
                        return;
                    }
                    ingredients.add(new ItemStack(item, e.getAsJsonArray().get(1).getAsInt()));
                }
                long prix = GsonHelper.getAsLong(o, "prix");
                // sans durée donnée : de 18 s pour une petite pièce à une minute pour les plus chères
                int temps = GsonHelper.getAsInt(o, "temps", (int) Math.min(60, 15 + prix / 2500));
                Map<String, Integer> enchantements = new java.util.LinkedHashMap<>();
                if (o.has("enchantements")) {
                    GsonHelper.getAsJsonObject(o, "enchantements").entrySet().forEach(e -> enchantements.put(e.getKey(), e.getValue().getAsInt()));
                }
                liste.add(new Recette(id.toString(), new ItemStack(resultat, GsonHelper.getAsInt(o, "nombre", 1)), ingredients,
                        prix, GsonHelper.getAsInt(o, "ordre", 100), GsonHelper.getAsString(o, "categorie", "arme"),
                        GsonHelper.getAsString(o, "indice", ""), temps * 20, GsonHelper.getAsString(o, "nom", ""), enchantements));
            } catch (Exception e) {
                Cabane.LOGGER.warn("Recette de forge {} illisible : {}", id, e.toString());
            }
        });
        liste.sort(Comparator.comparingInt(Recette::ordre).thenComparing(Recette::id));
        recettes = List.copyOf(liste);
        Cabane.LOGGER.info("Forge de Brokk : {} recettes", recettes.size());
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        event.registrar("1")
                .playToClient(Ouvrir.TYPE, Ouvrir.CODEC, (p, ctx) -> ctx.enqueueWork(() -> ClientForge.ouvrir(p)))
                .playToClient(Chantier.TYPE, Chantier.CODEC, (p, ctx) -> ctx.enqueueWork(() -> ClientForge.chantier(p)))
                .playToServer(Forger.TYPE, Forger.CODEC, (p, ctx) -> ctx.enqueueWork(() -> {
                    if (ctx.player() instanceof ServerPlayer joueur) {
                        forger(joueur, p.id());
                    }
                }));
    }

    // ================================================================ l'atelier : la file des pièces, et celles qui attendent leur maître

    /** Une pièce commandée : sur l'enclume depuis debut (temps du monde), ou -1 tant qu'elle attend son tour. */
    static final class Commande {
        final UUID joueur;
        final ItemStack objet;
        final int duree;
        final int numero;
        long debut = -1;

        Commande(UUID joueur, ItemStack objet, int duree, int numero) {
            this.joueur = joueur;
            this.objet = objet;
            this.duree = duree;
            this.numero = numero;
        }
    }

    /** Une pièce finie, que Brokk tend à son propriétaire dès qu'il est à la forge (pas avant le tick des). */
    record Prete(UUID joueur, ItemStack objet, long des) {
    }

    static final class Atelier extends SavedData {
        final List<Commande> file = new ArrayList<>();
        final List<Prete> pretes = new ArrayList<>();
        int numero;

        static Atelier get(MinecraftServer server) {
            return server.overworld().getDataStorage().computeIfAbsent(new SavedData.Factory<>(Atelier::new, Atelier::charger, null), "cabane_forge");
        }

        private static Atelier charger(CompoundTag tag, HolderLookup.Provider registres) {
            Atelier a = new Atelier();
            a.numero = tag.getInt("numero");
            for (Tag t : tag.getList("file", Tag.TAG_COMPOUND)) {
                CompoundTag c = (CompoundTag) t;
                ItemStack objet = ItemStack.parseOptional(registres, c.getCompound("objet"));
                if (!objet.isEmpty()) {
                    Commande k = new Commande(c.getUUID("joueur"), objet, c.getInt("duree"), c.getInt("numero"));
                    k.debut = c.getLong("debut");
                    a.file.add(k);
                }
            }
            for (Tag t : tag.getList("pretes", Tag.TAG_COMPOUND)) {
                CompoundTag c = (CompoundTag) t;
                ItemStack objet = ItemStack.parseOptional(registres, c.getCompound("objet"));
                if (!objet.isEmpty()) {
                    a.pretes.add(new Prete(c.getUUID("joueur"), objet, 0));
                }
            }
            return a;
        }

        @Override
        public CompoundTag save(CompoundTag tag, HolderLookup.Provider registres) {
            tag.putInt("numero", numero);
            ListTag file = new ListTag();
            for (Commande k : this.file) {
                CompoundTag c = new CompoundTag();
                c.putUUID("joueur", k.joueur);
                c.put("objet", k.objet.save(registres));
                c.putInt("duree", k.duree);
                c.putInt("numero", k.numero);
                c.putLong("debut", k.debut);
                file.add(c);
            }
            tag.put("file", file);
            ListTag pretes = new ListTag();
            for (Prete p : this.pretes) {
                CompoundTag c = new CompoundTag();
                c.putUUID("joueur", p.joueur());
                c.put("objet", p.objet().save(registres));
                pretes.add(c);
            }
            tag.put("pretes", pretes);
            return tag;
        }

        /** Les ticks qu'il reste avant que l'enclume se libère pour la pièce d'indice i de la file. */
        int attente(int i, long maintenant) {
            int n = 0;
            for (int k = 0; k < i && k < file.size(); k++) {
                Commande c = file.get(k);
                n += c.debut < 0 ? c.duree : (int) Math.max(0, c.debut + c.duree - maintenant);
            }
            return n;
        }
    }

    // ================================================================ où Brokk travaille

    /** Le dessus de l'enclume de Brokk, le point d'où son marteau tourne (de son côté), et Brokk (null s'il n'est pas chargé). */
    private record Poste(ServerLevel level, Vec3 enclume, Vec3 epaule, Entity brokk) {
    }

    private static Poste poste;

    /**
     * L'enclume la plus proche de Brokk (l'enclume monumentale de sa forge, ou celle d'une forge plus ancienne) : on la
     * cherche autour de lui plutôt que de supposer où elle est. Sans village (un monde d'essai), devant celui qui commande.
     */
    private static Poste trouverPoste(MinecraftServer server, ServerPlayer maitre) {
        BlockPos centre = HubData.get(server).centre();
        if (centre == null && maitre == null) {
            return null;
        }
        ServerLevel level = centre != null ? server.overworld() : maitre.serverLevel();
        Vec3 autour = centre != null ? Vec3.atLowerCornerOf(centre).add(60.5, 1, 0.5) : maitre.position();
        Entity brokk = level.getEntities((Entity) null, new AABB(autour, autour).inflate(32), e -> e.getTags().contains("pnj.forgeron")).stream()
                .min(Comparator.comparingDouble(e -> e.distanceToSqr(autour))).orElse(null);
        Vec3 enclume;
        if (brokk != null) {
            BlockPos pied = brokk.blockPosition();
            enclume = BlockPos.betweenClosedStream(pied.offset(-6, -2, -6), pied.offset(6, 3, 6))
                    .filter(p -> level.getBlockState(p).is(BlockTags.ANVIL)).map(BlockPos::immutable)
                    .min(Comparator.comparingDouble(p -> p.distToCenterSqr(brokk.position())))
                    .map(p -> Vec3.atBottomCenterOf(p).add(0, 1.02, 0))
                    // pas d'enclume autour de lui : il frappe juste devant lui
                    .orElse(brokk.position().add(Vec3.directionFromRotation(0, brokk.getYRot()).scale(1.6)).add(0, 1, 0));
        } else if (centre != null) {
            enclume = Vec3.atLowerCornerOf(centre).add(58.5, 4.02, 1.5); // l'enclume monumentale
        } else {
            enclume = maitre.position().add(maitre.getLookAngle().multiply(1, 0, 1).normalize().scale(2.2)).add(0, 1, 0);
        }
        // le marteau tourne au-dessus de l'enclume, du côté de Brokk (à son épaule s'il est tout près)
        Vec3 cote = brokk != null ? brokk.position() : centre != null ? autour : maitre.position();
        Vec3 u = new Vec3(cote.x - enclume.x, 0, cote.z - enclume.z);
        double d = u.length();
        u = d < 0.1 ? new Vec3(1, 0, 0) : u.scale(1 / d);
        return new Poste(level, enclume, enclume.add(u.scale(Mth.clamp(d - 0.3, 1.1, 1.8))).add(0, 1.55, 0), brokk);
    }

    /** Où rapporter les pièces finies : l'enclume de Brokk. */
    private static Vec3 enclumeOuJoueur(MinecraftServer server, ServerPlayer joueur) {
        if (poste != null) {
            return poste.enclume();
        }
        BlockPos centre = HubData.get(server).centre();
        return centre == null ? joueur.position() : Vec3.atLowerCornerOf(centre).add(58.5, 2, 0.5);
    }

    /** Brokk ne travaille que dans sa forge, au bout de la rue du village. */
    private static boolean presDeBrokk(ServerPlayer joueur) {
        BlockPos centre = HubData.get(joueur.server).centre();
        if (centre == null || joueur.blockPosition().distSqr(centre.offset(58, 1, 0)) < 14 * 14) {
            return true;
        }
        joueur.displayClientMessage(Component.literal("Brokk travaille dans sa forge, au bout de la rue du village : va le voir.").withStyle(ChatFormatting.GOLD), false);
        return false;
    }

    private static String secondes(int ticks) {
        int s = (ticks + 19) / 20;
        return s >= 60 ? (s / 60) + " min " + (s % 60 < 10 ? "0" : "") + (s % 60) + " s" : s + " s";
    }

    /** Où en est la pièce de ce joueur, en une ligne pour le bas de l'écran de la forge. */
    private static String statut(ServerPlayer joueur) {
        Atelier a = Atelier.get(joueur.server);
        long maintenant = joueur.server.overworld().getGameTime();
        for (int i = 0; i < a.file.size(); i++) {
            Commande c = a.file.get(i);
            if (c.joueur.equals(joueur.getUUID())) {
                return (c.debut < 0 ? "En attente : " : "Sur l'enclume : ") + fr.cabane.Noms.de(c.objet)
                        + " (encore " + secondes(a.attente(i + 1, maintenant)) + ")";
            }
        }
        return "";
    }

    private static void envoyer(ServerPlayer joueur, boolean fermer, String onglet) {
        Economie.sync(joueur);
        PacketDistributor.sendToPlayer(joueur, new Ouvrir(recettes.stream()
                .map(r -> new Vue(r.id(), r.objet(joueur.server.registryAccess()), r.ingredients(), r.prix(), r.categorie(), r.indice(), r.temps() / 20)).toList(),
                statut(joueur), fermer, onglet));
    }

    /** L'écran de la forge, ouvert sur une catégorie (« relique ») ou sur la première. */
    public static void ouvrir(ServerPlayer joueur, String onglet) {
        if (!presDeBrokk(joueur)) {
            return;
        }
        livrer(joueur, true);
        envoyer(joueur, false, onglet);
    }

    private static int compter(ServerPlayer joueur, Item item) {
        int n = 0;
        for (ItemStack s : joueur.getInventory().items) {
            if (s.is(item)) {
                n += s.getCount();
            }
        }
        return n;
    }

    private static void forger(ServerPlayer joueur, String id) {
        Recette r = recettes.stream().filter(x -> x.id().equals(id)).findFirst().orElse(null);
        if (r == null || !presDeBrokk(joueur)) {
            return;
        }
        Atelier a = Atelier.get(joueur.server);
        if (a.file.stream().anyMatch(c -> c.joueur.equals(joueur.getUUID()))) {
            joueur.displayClientMessage(Component.literal("Brokk : « Une pièce à la fois. La tienne est déjà sur le feu. »").withStyle(ChatFormatting.RED), false);
            return;
        }
        List<String> manque = new ArrayList<>();
        for (ItemStack ing : r.ingredients()) {
            int n = compter(joueur, ing.getItem());
            if (n < ing.getCount()) {
                manque.add((ing.getCount() - n) + " × " + fr.cabane.Noms.de(ing));
            }
        }
        if (!manque.isEmpty()) {
            joueur.displayClientMessage(Component.literal("Brokk : « Il me manque " + String.join(", ", manque) + ". »").withStyle(ChatFormatting.RED), false);
            return;
        }
        if (!Economie.payer(joueur, r.prix())) {
            return;
        }
        for (ItemStack ing : r.ingredients()) {
            int reste = ing.getCount();
            for (ItemStack s : joueur.getInventory().items) {
                if (reste > 0 && s.is(ing.getItem())) {
                    int pris = Math.min(reste, s.getCount());
                    s.shrink(pris);
                    reste -= pris;
                }
            }
        }
        a.file.add(new Commande(joueur.getUUID(), r.objet(joueur.server.registryAccess()), r.temps(), ++a.numero));
        a.setDirty();
        int attente = a.attente(a.file.size() - 1, joueur.server.overworld().getGameTime());
        joueur.level().playSound(null, joueur.blockPosition(), SoundEvents.ANVIL_PLACE, SoundSource.PLAYERS, 0.8F, 0.9F);
        if (attente == 0) {
            joueur.displayClientMessage(Component.literal("Brokk : « Bon métal. Toi regarde, et toi touche pas l'enclume. » (" + secondes(r.temps()) + ")")
                    .withStyle(ChatFormatting.GOLD), false);
        } else {
            joueur.displayClientMessage(Component.literal("Brokk : « Brokk finit d'abord la pièce d'avant. La tienne passe après. » (dans "
                    + secondes(attente) + ")").withStyle(ChatFormatting.GOLD), false);
        }
        envoyer(joueur, true, "");
    }

    // ================================================================ au travail

    private static void diffuser(MinecraftServer server, Commande c, int etat, long maintenant) {
        poste = trouverPoste(server, server.getPlayerList().getPlayer(c.joueur));
        if (poste == null) {
            return;
        }
        Vec3 e = poste.enclume();
        Vec3 epaule = poste.epaule();
        int ecoule = c.debut < 0 ? 0 : (int) Math.min(c.duree, maintenant - c.debut);
        if (etat == 1 && ecoule == 0) {
            Cabane.LOGGER.info("Brokk forge {} sur l'enclume en {} (Brokk {})", c.objet.getHoverName().getString(), e,
                    poste.brokk() == null ? "pas chargé" : poste.brokk().position());
        }
        for (ServerPlayer p : poste.level().players()) {
            if (p.position().distanceToSqr(e) < 64 * 64) {
                PacketDistributor.sendToPlayer(p, new Chantier(etat, c.numero, e.toVector3f(), epaule.toVector3f(), c.objet, ecoule, c.duree,
                        p.getUUID().equals(c.joueur)));
            }
        }
    }

    /** Pendant qu'il forge, Brokk regarde son enclume, et lève le bras à chaque coup (si son modèle a un bras). */
    private static void brokkAuTravail(long t) {
        if (poste == null || !(poste.brokk() instanceof LivingEntity brokk) || !brokk.isAlive()) {
            return;
        }
        Vec3 e = poste.enclume();
        float yaw = (float) (Mth.atan2(e.z - brokk.getZ(), e.x - brokk.getX()) * Mth.RAD_TO_DEG) - 90F;
        brokk.setYRot(yaw);
        brokk.setYHeadRot(yaw);
        brokk.setYBodyRot(yaw);
        brokk.setXRot(35F);
        if (t % CYCLE == FRAPPE - 3) {
            brokk.swing(InteractionHand.MAIN_HAND, true);
        }
    }

    private static void tick(MinecraftServer server) {
        Atelier a = Atelier.get(server);
        long maintenant = server.overworld().getGameTime();
        if (!a.file.isEmpty()) {
            Commande c = a.file.get(0);
            ServerPlayer maitre = server.getPlayerList().getPlayer(c.joueur);
            if (c.debut < 0 || c.debut > maintenant) {
                c.debut = maintenant;
                a.setDirty();
                diffuser(server, c, 1, maintenant);
                if (maitre != null && maitre.position().distanceToSqr(enclumeOuJoueur(server, maitre)) > 40 * 40) {
                    maitre.displayClientMessage(Component.literal("Brokk a posé ton " + fr.cabane.Noms.de(c.objet)
                            + " sur l'enclume. Viens le regarder faire, à la forge.").withStyle(ChatFormatting.GOLD), false);
                }
            } else if (maintenant >= c.debut + c.duree) {
                a.file.remove(0);
                a.pretes.add(new Prete(c.joueur, c.objet, maintenant + FINAL));
                a.setDirty();
                diffuser(server, c, 2, maintenant);
                if (maitre != null && maitre.position().distanceToSqr(enclumeOuJoueur(server, maitre)) > 20 * 20) {
                    maitre.displayClientMessage(Component.literal("Brokk : « Ton " + fr.cabane.Noms.de(c.objet)
                            + " est prêt. Viens le chercher à la forge. »").withStyle(ChatFormatting.GOLD), false);
                }
            } else {
                long t = maintenant - c.debut;
                if (t < c.duree * MARTELAGE) {
                    brokkAuTravail(t);
                }
                if (t % 40 == 0) {
                    diffuser(server, c, 1, maintenant); // pour ceux qui viennent d'arriver à la forge
                }
            }
        }
        if (!a.pretes.isEmpty() && server.getTickCount() % 10 == 0) {
            for (ServerPlayer joueur : server.getPlayerList().getPlayers()) {
                livrer(joueur, false);
            }
        }
    }

    /** Brokk tend au joueur ses pièces finies, s'il est à la forge (ou s'il vient d'ouvrir l'écran de la forge). */
    private static void livrer(ServerPlayer joueur, boolean depuisEcran) {
        Atelier a = Atelier.get(joueur.server);
        long maintenant = joueur.server.overworld().getGameTime();
        boolean ici = depuisEcran || (joueur.level() == joueur.server.overworld()
                && joueur.position().distanceToSqr(enclumeOuJoueur(joueur.server, joueur)) < 20 * 20);
        boolean donne = false;
        for (var it = a.pretes.iterator(); it.hasNext(); ) {
            Prete p = it.next();
            if (!ici || !p.joueur().equals(joueur.getUUID()) || p.des() > maintenant) {
                continue;
            }
            it.remove();
            donne = true;
            ItemStack objet = p.objet().copy();
            joueur.displayClientMessage(Component.literal("Brokk te tend ton ").append(objet.getHoverName())
                    .append(". « Prends-en soin. Brokk saura si tu l'abîmes. »").withStyle(ChatFormatting.GOLD), false);
            if (!joueur.getInventory().add(objet)) {
                joueur.drop(objet, false);
            }
        }
        if (donne) {
            a.setDirty();
            joueur.level().playSound(null, joueur.blockPosition(), SoundEvents.ITEM_PICKUP, SoundSource.PLAYERS, 1F, 0.7F);
            joueur.level().playSound(null, joueur.blockPosition(), SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 0.6F, 1.2F);
        }
    }

    /** Pour les essais (l'hôte) : commander une recette sans passer par l'écran. */
    public static void forgerPourTest(ServerPlayer joueur, String id) {
        forger(joueur, recettes.stream().map(Recette::id).filter(r -> r.equals(id) || r.endsWith(":" + id)).findFirst().orElse(id));
    }

    @EventBusSubscriber(modid = Cabane.MODID)
    public static final class Evenements {
        @SubscribeEvent
        public static void tick(ServerTickEvent.Post event) {
            Forge.tick(event.getServer());
        }

        @SubscribeEvent
        public static void connexion(PlayerEvent.PlayerLoggedInEvent event) {
            if (event.getEntity() instanceof ServerPlayer joueur
                    && Atelier.get(joueur.server).pretes.stream().anyMatch(p -> p.joueur().equals(joueur.getUUID()))) {
                joueur.displayClientMessage(Component.literal("Brokk a fini une pièce pour toi : elle t'attend à la forge.").withStyle(ChatFormatting.GOLD), false);
            }
        }

        private Evenements() {
        }
    }
}
