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
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.GsonHelper;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/**
 * La forge de Brokk : des objets rares des mods du pack, que lui seul sait faire. Il faut lui
 * rapporter les matériaux et payer le travail. Les recettes sont des fichiers data/cabane/forge/*.json :
 * {"resultat": "mowziesmobs:wrought_axe", "prix": 15000, "ingredients": [["minecraft:iron_ingot", 12]]}.
 */
public final class Forge extends SimpleJsonResourceReloadListener {
    public static final Forge INSTANCE = new Forge();
    private static List<Recette> recettes = List.of();

    public record Recette(String id, ItemStack resultat, List<ItemStack> ingredients, long prix, int ordre) {
    }

    public record Vue(String id, ItemStack resultat, List<ItemStack> ingredients, long prix) {
        public static final StreamCodec<RegistryFriendlyByteBuf, Vue> CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, Vue::id,
                ItemStack.STREAM_CODEC, Vue::resultat,
                ItemStack.STREAM_CODEC.apply(ByteBufCodecs.list()), Vue::ingredients,
                ByteBufCodecs.VAR_LONG, Vue::prix,
                Vue::new);
    }

    public record Ouvrir(List<Vue> recettes) implements CustomPacketPayload {
        public static final Type<Ouvrir> TYPE = new Type<>(Cabane.id("forge_ouvrir"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Ouvrir> CODEC =
                Vue.CODEC.apply(ByteBufCodecs.list()).map(Ouvrir::new, Ouvrir::recettes);

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
                liste.add(new Recette(id.toString(), new ItemStack(resultat, GsonHelper.getAsInt(o, "nombre", 1)), ingredients,
                        GsonHelper.getAsLong(o, "prix"), GsonHelper.getAsInt(o, "ordre", 100)));
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
                .playToClient(Ouvrir.TYPE, Ouvrir.CODEC, (p, ctx) -> ctx.enqueueWork(() -> ClientForge.ouvrir(p.recettes())))
                .playToServer(Forger.TYPE, Forger.CODEC, (p, ctx) -> ctx.enqueueWork(() -> {
                    if (ctx.player() instanceof ServerPlayer joueur) {
                        forger(joueur, p.id());
                    }
                }));
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

    public static void ouvrir(ServerPlayer joueur) {
        if (!presDeBrokk(joueur)) {
            return;
        }
        Economie.sync(joueur);
        PacketDistributor.sendToPlayer(joueur, new Ouvrir(recettes.stream()
                .map(r -> new Vue(r.id(), r.resultat(), r.ingredients(), r.prix())).toList()));
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
        List<String> manque = new ArrayList<>();
        for (ItemStack ing : r.ingredients()) {
            int a = compter(joueur, ing.getItem());
            if (a < ing.getCount()) {
                manque.add((ing.getCount() - a) + " × " + ing.getHoverName().getString());
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
        ItemStack objet = r.resultat().copy();
        if (!joueur.getInventory().add(objet)) {
            joueur.drop(objet, false);
        }
        joueur.level().playSound(null, joueur.blockPosition(), SoundEvents.ANVIL_USE, SoundSource.PLAYERS, 1F, 0.8F);
        joueur.level().playSound(null, joueur.blockPosition(), SoundEvents.SMITHING_TABLE_USE, SoundSource.PLAYERS, 1F, 1F);
        joueur.displayClientMessage(Component.literal("Brokk : « CLANG. Voilà ton ").append(r.resultat().getHoverName()).append(". Prends-en soin. »")
                .withStyle(ChatFormatting.GOLD), false);
        ouvrir(joueur);
    }
}
