package fr.cabane.tcg;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import fr.cabane.Cabane;
import fr.cabane.CabaneComponents;
import fr.cabane.CabaneItems;
import fr.cabane.client.ClientCartes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.GsonHelper;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.OnDatapackSyncEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/**
 * Le catalogue des cartes, lu dans data/cabane/cartes/*.json :
 * {"nom": "...", "description": "...", "rarete": "commune|rare|epique|legendaire|mythique|arc", "numero": 1, "serie": "La Cabane"}.
 * Le serveur l'envoie aux joueurs à la connexion et après chaque /reload.
 */
@EventBusSubscriber(modid = Cabane.MODID)
public final class Cartes extends SimpleJsonResourceReloadListener {
    public static final Cartes INSTANCE = new Cartes();
    public static final String[] RARETES = {"Commune", "Rare", "Épique", "Légendaire", "Mythique", "Arc-en-ciel"};
    public static final ChatFormatting[] COULEURS = {ChatFormatting.GREEN, ChatFormatting.BLUE, ChatFormatting.DARK_PURPLE, ChatFormatting.GOLD, ChatFormatting.RED, ChatFormatting.WHITE};
    public static final int[] COULEURS_RGB = {0xFF5FD068, 0xFF4F8DFF, 0xFFA855F7, 0xFFFFC23D, 0xFFFF2E63, 0xFF7CF29C};

    private static volatile Map<String, Def> catalogue = Map.of();

    public record Def(String id, String nom, String description, int rarete, int numero, String serie) {
        public static final StreamCodec<RegistryFriendlyByteBuf, Def> CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, Def::id,
                ByteBufCodecs.STRING_UTF8, Def::nom,
                ByteBufCodecs.STRING_UTF8, Def::description,
                ByteBufCodecs.VAR_INT, Def::rarete,
                ByteBufCodecs.VAR_INT, Def::numero,
                ByteBufCodecs.STRING_UTF8, Def::serie,
                Def::new);
    }

    public record Catalogue(List<Def> cartes) implements CustomPacketPayload {
        public static final Type<Catalogue> TYPE = new Type<>(Cabane.id("cartes_catalogue"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Catalogue> CODEC =
                Def.CODEC.apply(ByteBufCodecs.list()).map(Catalogue::new, Catalogue::cartes);

        @Override
        public Type<Catalogue> type() {
            return TYPE;
        }
    }

    private Cartes() {
        super(new Gson(), "cartes");
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> fichiers, ResourceManager manager, ProfilerFiller profiler) {
        List<Def> liste = new ArrayList<>();
        fichiers.forEach((id, json) -> {
            try {
                JsonObject o = json.getAsJsonObject();
                String r = GsonHelper.getAsString(o, "rarete", "commune");
                int rarete = switch (r) {
                    case "rare" -> 1;
                    case "epique" -> 2;
                    case "legendaire" -> 3;
                    case "mythique" -> 4;
                    case "arc" -> 5;
                    default -> 0;
                };
                liste.add(new Def(id.toString(), GsonHelper.getAsString(o, "nom"), GsonHelper.getAsString(o, "description", ""),
                        rarete, GsonHelper.getAsInt(o, "numero", 999), GsonHelper.getAsString(o, "serie", "")));
            } catch (Exception e) {
                Cabane.LOGGER.warn("Carte {} illisible : {}", id, e.toString());
            }
        });
        definir(liste);
        Cabane.LOGGER.info("Cartes : {} dans le catalogue", liste.size());
    }

    /** Un texte aux couleurs de sa rareté ; l'arc-en-ciel ondule lettre par lettre. */
    public static Component colore(String texte, int rarete) {
        if (rarete != 5) {
            return Component.literal(texte).withStyle(COULEURS[rarete]);
        }
        MutableComponent c = Component.empty();
        float decale = (System.currentTimeMillis() % 2000L) / 2000F;
        for (int i = 0; i < texte.length(); i++) {
            c.append(Component.literal(texte.substring(i, i + 1)).withStyle(Style.EMPTY.withColor(Mth.hsvToRgb(decale + i * 0.07F, 0.6F, 1F) & 0xFFFFFF)));
        }
        return c;
    }

    public static void definir(List<Def> liste) {
        Map<String, Def> m = new LinkedHashMap<>();
        liste.stream().sorted(Comparator.comparingInt(Def::numero)).forEach(d -> m.put(d.id(), d));
        catalogue = m;
    }

    public static Def def(String id) {
        return catalogue.get(id);
    }

    public static List<Def> toutes() {
        return List.copyOf(catalogue.values());
    }

    public static ItemStack carte(String id) {
        ItemStack stack = new ItemStack(CabaneItems.CARTE.get());
        stack.set(CabaneComponents.CARTE.get(), id);
        return stack;
    }

    /** Tire une carte : rareté d'abord, puis une carte de cette rareté. */
    public static Def tirer(RandomSource rnd, boolean garantieRare) {
        // sur 1000 tirages
        int[] poids = garantieRare ? new int[] {0, 700, 220, 60, 15, 5} : new int[] {610, 240, 100, 35, 12, 3};
        int r = rnd.nextInt(1000);
        int rarete = 0;
        while (r >= poids[rarete]) {
            r -= poids[rarete];
            rarete++;
        }
        for (int essai = rarete; essai >= 0; essai--) {
            int cible = essai;
            List<Def> candidates = catalogue.values().stream().filter(d -> d.rarete() == cible).toList();
            if (!candidates.isEmpty()) {
                return candidates.get(rnd.nextInt(candidates.size()));
            }
        }
        List<Def> toutes = toutes();
        return toutes.isEmpty() ? null : toutes.get(rnd.nextInt(toutes.size()));
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        event.registrar("1").playToClient(Catalogue.TYPE, Catalogue.CODEC, (p, ctx) -> ctx.enqueueWork(() -> {
            definir(p.cartes());
            ClientCartes.catalogueRecu();
        }));
        Booster.register(event);
        Classeur.register(event);
    }

    @SubscribeEvent
    public static void onDatapackSync(OnDatapackSyncEvent event) {
        Catalogue payload = new Catalogue(toutes());
        if (event.getPlayer() != null) {
            PacketDistributor.sendToPlayer(event.getPlayer(), payload);
        } else {
            for (ServerPlayer joueur : event.getPlayerList().getPlayers()) {
                PacketDistributor.sendToPlayer(joueur, payload);
            }
        }
    }
}
