package fr.cabane.boutique;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import fr.cabane.Cabane;
import fr.cabane.client.ClientBoutique;
import fr.cabane.economie.Economie;
import io.netty.buffer.ByteBuf;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import net.minecraft.ChatFormatting;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
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
import net.minecraft.util.GsonHelper;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.saveddata.SavedData;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/**
 * Les contrats du marché : toutes les heures, trois nouvelles commandes (« apporte 32 blés »),
 * chacune payée en SakaiDollars, une fois par joueur et par heure.
 * Le choix se fait dans data/cabane/contrats/*.json : {"item": "minecraft:wheat", "min": 16, "max": 48, "prix_unitaire": 2}.
 */
public final class Contrats extends SimpleJsonResourceReloadListener {
    public static final Contrats INSTANCE = new Contrats();
    public static final int PAR_HEURE = 3;
    private static final long HEURE = 3_600_000L;
    private static List<Modele> pool = List.of();

    private record Modele(Item item, int min, int max, long prixUnitaire) {
    }

    public record Contrat(ItemStack demande, long recompense) {
    }

    public record Vue(ItemStack demande, long recompense, boolean fait) {
        public static final StreamCodec<RegistryFriendlyByteBuf, Vue> CODEC = StreamCodec.composite(
                ItemStack.STREAM_CODEC, Vue::demande,
                ByteBufCodecs.VAR_LONG, Vue::recompense,
                ByteBufCodecs.BOOL, Vue::fait,
                Vue::new);
    }

    public record Ouvrir(List<Vue> contrats, long msRestantes) implements CustomPacketPayload {
        public static final Type<Ouvrir> TYPE = new Type<>(Cabane.id("contrats_ouvrir"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Ouvrir> CODEC = StreamCodec.composite(
                Vue.CODEC.apply(ByteBufCodecs.list()), Ouvrir::contrats,
                ByteBufCodecs.VAR_LONG, Ouvrir::msRestantes,
                Ouvrir::new);

        @Override
        public Type<Ouvrir> type() {
            return TYPE;
        }
    }

    public record Livrer(int index) implements CustomPacketPayload {
        public static final Type<Livrer> TYPE = new Type<>(Cabane.id("contrats_livrer"));
        public static final StreamCodec<ByteBuf, Livrer> CODEC = ByteBufCodecs.VAR_INT.map(Livrer::new, Livrer::index);

        @Override
        public Type<Livrer> type() {
            return TYPE;
        }
    }

    private Contrats() {
        super(new Gson(), "contrats");
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> fichiers, ResourceManager manager, ProfilerFiller profiler) {
        List<Modele> liste = new ArrayList<>();
        fichiers.forEach((id, json) -> {
            try {
                JsonObject o = json.getAsJsonObject();
                Item item = BuiltInRegistries.ITEM.get(ResourceLocation.parse(GsonHelper.getAsString(o, "item")));
                if (item != Items.AIR) {
                    liste.add(new Modele(item, GsonHelper.getAsInt(o, "min"), GsonHelper.getAsInt(o, "max"), GsonHelper.getAsLong(o, "prix_unitaire")));
                }
            } catch (Exception e) {
                Cabane.LOGGER.warn("Contrat {} illisible : {}", id, e.toString());
            }
        });
        pool = List.copyOf(liste);
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        event.registrar("1")
                .playToClient(Ouvrir.TYPE, Ouvrir.CODEC, (p, ctx) -> ctx.enqueueWork(() -> ClientBoutique.contrats(p.contrats(), p.msRestantes())))
                .playToServer(Livrer.TYPE, Livrer.CODEC, (p, ctx) -> ctx.enqueueWork(() -> {
                    if (ctx.player() instanceof ServerPlayer joueur) {
                        livrer(joueur, p.index());
                    }
                }));
    }

    private static long periode() {
        return System.currentTimeMillis() / HEURE;
    }

    /** Les contrats de l'heure en cours : les mêmes pour tout le monde. */
    public static List<Contrat> actuels() {
        List<Contrat> liste = new ArrayList<>();
        if (pool.isEmpty()) {
            return liste;
        }
        Random rnd = new Random(periode() * 7919L);
        List<Modele> melange = new ArrayList<>(pool);
        java.util.Collections.shuffle(melange, rnd);
        for (int i = 0; i < Math.min(PAR_HEURE, melange.size()); i++) {
            Modele m = melange.get(i);
            int n = m.min() + rnd.nextInt(Math.max(1, m.max() - m.min() + 1));
            liste.add(new Contrat(new ItemStack(m.item(), n), n * m.prixUnitaire()));
        }
        return liste;
    }

    public static void ouvrir(ServerPlayer joueur) {
        Faits faits = Faits.get(joueur.server);
        List<Contrat> actuels = actuels();
        List<Vue> vues = new ArrayList<>();
        for (int i = 0; i < actuels.size(); i++) {
            vues.add(new Vue(actuels.get(i).demande(), actuels.get(i).recompense(), faits.fait(joueur, i)));
        }
        Economie.sync(joueur);
        PacketDistributor.sendToPlayer(joueur, new Ouvrir(vues, HEURE - System.currentTimeMillis() % HEURE));
    }

    private static void livrer(ServerPlayer joueur, int index) {
        List<Contrat> actuels = actuels();
        Faits faits = Faits.get(joueur.server);
        if (index < 0 || index >= actuels.size() || faits.fait(joueur, index)) {
            return;
        }
        Contrat contrat = actuels.get(index);
        Item item = contrat.demande().getItem();
        int besoin = contrat.demande().getCount();
        int possede = 0;
        var inv = joueur.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            if (inv.getItem(i).is(item)) {
                possede += inv.getItem(i).getCount();
            }
        }
        if (possede < besoin) {
            joueur.displayClientMessage(Component.literal("Il te manque " + (besoin - possede) + " × ")
                    .append(contrat.demande().getHoverName()).withStyle(ChatFormatting.RED), true);
            return;
        }
        for (int i = 0; i < inv.getContainerSize() && besoin > 0; i++) {
            ItemStack s = inv.getItem(i);
            if (s.is(item)) {
                int pris = Math.min(besoin, s.getCount());
                s.shrink(pris);
                besoin -= pris;
            }
        }
        faits.marquer(joueur, index);
        Economie.crediter(joueur.server, joueur.getUUID(), contrat.recompense());
        var succes = joueur.server.getAdvancements().get(Cabane.id("premier_contrat"));
        if (succes != null) {
            joueur.getAdvancements().award(succes, "contrat");
        }
        joueur.displayClientMessage(Component.literal("Contrat rempli ! +").append(Economie.texte(contrat.recompense()))
                .withStyle(ChatFormatting.GREEN), true);
        ouvrir(joueur);
    }

    /** Qui a déjà rempli quoi pendant l'heure en cours. */
    public static class Faits extends SavedData {
        private long periode;
        private final Set<String> faits = new HashSet<>();

        static Faits get(MinecraftServer server) {
            Faits f = server.overworld().getDataStorage().computeIfAbsent(new SavedData.Factory<>(Faits::new, Faits::load, null), "cabane_contrats");
            if (f.periode != periode()) {
                f.periode = periode();
                f.faits.clear();
                f.setDirty();
            }
            return f;
        }

        private static Faits load(CompoundTag tag, HolderLookup.Provider registries) {
            Faits f = new Faits();
            f.periode = tag.getLong("periode");
            for (Tag t : tag.getList("faits", Tag.TAG_STRING)) {
                f.faits.add(t.getAsString());
            }
            return f;
        }

        @Override
        public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
            tag.putLong("periode", periode);
            ListTag liste = new ListTag();
            faits.forEach(s -> liste.add(StringTag.valueOf(s)));
            tag.put("faits", liste);
            return tag;
        }

        boolean fait(ServerPlayer joueur, int index) {
            return faits.contains(joueur.getUUID() + "#" + index);
        }

        void marquer(ServerPlayer joueur, int index) {
            faits.add(joueur.getUUID() + "#" + index);
            setDirty();
        }
    }
}
