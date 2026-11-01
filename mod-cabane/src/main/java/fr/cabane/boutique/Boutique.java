package fr.cabane.boutique;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import fr.cabane.Cabane;
import fr.cabane.client.ClientBoutique;
import fr.cabane.economie.Economie;
import io.netty.buffer.ByteBuf;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import net.minecraft.ChatFormatting;
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
import net.minecraft.util.GsonHelper;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/**
 * La boutique du marché. Les offres sont des fichiers data/cabane/boutique/*.json :
 * {"item": "cabane:telephone", "nombre": 1, "prix": 750, "ordre": 10}. /reload les recharge.
 */
public final class Boutique extends SimpleJsonResourceReloadListener {
    public static final Boutique INSTANCE = new Boutique();
    private static List<Offre> offres = List.of();

    /** noir : une offre du marché noir, que seul le Fourgue propose. */
    public record Offre(String id, ItemStack stack, long prix, int ordre, boolean noir) {
    }

    /** Ce que le client affiche. */
    public record Vue(String id, ItemStack stack, long prix) {
        public static final StreamCodec<RegistryFriendlyByteBuf, Vue> CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, Vue::id,
                ItemStack.STREAM_CODEC, Vue::stack,
                ByteBufCodecs.VAR_LONG, Vue::prix,
                Vue::new);
    }

    public record Ouvrir(List<Vue> offres) implements CustomPacketPayload {
        public static final Type<Ouvrir> TYPE = new Type<>(Cabane.id("boutique_ouvrir"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Ouvrir> CODEC =
                Vue.CODEC.apply(ByteBufCodecs.list()).map(Ouvrir::new, Ouvrir::offres);

        @Override
        public Type<Ouvrir> type() {
            return TYPE;
        }
    }

    public record Acheter(String id, int quantite) implements CustomPacketPayload {
        public static final Type<Acheter> TYPE = new Type<>(Cabane.id("boutique_acheter"));
        public static final StreamCodec<ByteBuf, Acheter> CODEC = StreamCodec.composite(
                ByteBufCodecs.stringUtf8(128), Acheter::id,
                ByteBufCodecs.VAR_INT, Acheter::quantite,
                Acheter::new);

        @Override
        public Type<Acheter> type() {
            return TYPE;
        }
    }

    private Boutique() {
        super(new Gson(), "boutique");
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> fichiers, ResourceManager manager, ProfilerFiller profiler) {
        List<Offre> liste = new ArrayList<>();
        fichiers.forEach((id, json) -> {
            try {
                JsonObject o = json.getAsJsonObject();
                Item item = BuiltInRegistries.ITEM.get(ResourceLocation.parse(GsonHelper.getAsString(o, "item")));
                if (item == Items.AIR) {
                    Cabane.LOGGER.warn("Offre {} : objet inconnu, ignorée", id);
                    return;
                }
                liste.add(new Offre(id.toString(), new ItemStack(item, GsonHelper.getAsInt(o, "nombre", 1)),
                        GsonHelper.getAsLong(o, "prix"), GsonHelper.getAsInt(o, "ordre", 100), GsonHelper.getAsBoolean(o, "noir", false)));
            } catch (Exception e) {
                Cabane.LOGGER.warn("Offre {} illisible : {}", id, e.toString());
            }
        });
        liste.sort(Comparator.comparingInt(Offre::ordre).thenComparing(Offre::id));
        offres = List.copyOf(liste);
        Cabane.LOGGER.info("Boutique : {} offres", offres.size());
    }

    /** La vitrine : les articles qui tournent au-dessus du comptoir, envoyés à la connexion. */
    public record Vitrine(List<Vue> offres) implements CustomPacketPayload {
        public static final Type<Vitrine> TYPE = new Type<>(Cabane.id("boutique_vitrine"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Vitrine> CODEC =
                Vue.CODEC.apply(ByteBufCodecs.list()).map(Vitrine::new, Vitrine::offres);

        @Override
        public Type<Vitrine> type() {
            return TYPE;
        }
    }

    public static void envoyerVitrine(ServerPlayer joueur) {
        PacketDistributor.sendToPlayer(joueur, new Vitrine(offres.stream().filter(o -> !o.noir()).map(o -> new Vue(o.id(), o.stack(), o.prix())).toList()));
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        event.registrar("1")
                .playToClient(Vitrine.TYPE, Vitrine.CODEC, (p, ctx) -> ctx.enqueueWork(() -> ClientBoutique.vitrine = p.offres()))
                .playToClient(Ouvrir.TYPE, Ouvrir.CODEC, (p, ctx) -> ctx.enqueueWork(() -> ClientBoutique.ouvrir(p.offres())))
                .playToServer(Acheter.TYPE, Acheter.CODEC, (p, ctx) -> ctx.enqueueWork(() -> {
                    if (ctx.player() instanceof ServerPlayer joueur) {
                        acheter(joueur, p.id(), p.quantite());
                    }
                }));
    }

    public static void ouvrir(ServerPlayer joueur) {
        ouvrir(joueur, false);
    }

    /** La boutique de Mouloud, ou (noir) celle du Fourgue. */
    public static void ouvrir(ServerPlayer joueur, boolean noir) {
        Economie.sync(joueur);
        envoyerVitrine(joueur);
        PacketDistributor.sendToPlayer(joueur, new Ouvrir(offres.stream().filter(o -> o.noir() == noir).map(o -> new Vue(o.id(), o.stack(), o.prix())).toList()));
    }

    private static void acheter(ServerPlayer joueur, String id, int quantite) {
        if (quantite < 1 || quantite > 64) {
            return;
        }
        Offre offre = offres.stream().filter(o -> o.id().equals(id)).findFirst().orElse(null);
        if (offre == null || (offre.noir() && !fr.cabane.drogue.MarcheNoir.presDuFourgue(joueur)) || !Economie.payer(joueur, offre.prix() * quantite)) {
            return;
        }
        for (int i = 0; i < quantite; i++) {
            ItemStack stack = offre.stack().copy();
            if (!joueur.getInventory().add(stack)) {
                joueur.drop(stack, false);
            }
        }
        joueur.displayClientMessage(Component.literal("Acheté : " + quantite + " × ").append(offre.stack().getHoverName())
                .append(" pour ").append(Economie.texte(offre.prix() * quantite)).withStyle(ChatFormatting.GREEN), true);
    }
}
