package fr.cabane.bouffe;

import fr.cabane.Cabane;
import fr.cabane.CabaneItems;
import fr.cabane.boisson.Boissons;
import fr.cabane.economie.Economie;
import io.netty.buffer.ByteBuf;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.registries.DeferredItem;

/**
 * Le McBeauvais : frites, burgers, Filet-O-Fish, nuggets, sundae… On commande à la borne (trois blocs de haut, un grand
 * écran tactile) ; on paie, et la commande arrive deux secondes plus tard, avec son
 * numéro. Les plats se cuisinent aussi soi-même (recettes dans outils/fastfood.mjs). Trop de fast-food d'un coup, et on
 * se sent un peu lourd…
 */
public final class FastFood {
    public static final DeferredItem<Plat> FRITES = plat("frites", 4, 0.4F, true, "Croustillantes dehors, fondantes dedans",
            () -> new MobEffectInstance(MobEffects.MOVEMENT_SPEED, 20 * 20, 0));
    public static final DeferredItem<Plat> HAMBURGER = plat("hamburger", 6, 0.6F, false, "Le classique : steak, oignons, cornichon");
    public static final DeferredItem<Plat> CHEESEBURGER = plat("cheeseburger", 7, 0.7F, false, "Avec sa tranche de cheddar qui coule",
            () -> new MobEffectInstance(MobEffects.ABSORPTION, 20 * 30, 0));
    public static final DeferredItem<Plat> DOUBLE_CHEESE = plat("double_cheese", 9, 0.8F, false, "Deux steaks, deux cheddars, zéro regret",
            () -> new MobEffectInstance(MobEffects.ABSORPTION, 20 * 45, 1));
    public static final DeferredItem<Plat> BIG_BEAUVAIS = plat("big_beauvais", 11, 0.9F, false, "Trois pains, deux steaks, la sauce secrète",
            () -> new MobEffectInstance(MobEffects.DAMAGE_BOOST, 20 * 40, 0));
    public static final DeferredItem<Plat> FILET_O_FISH = plat("filet_o_fish", 7, 0.7F, false, "Le poisson pané, sa sauce tartare, un demi-cheddar",
            () -> new MobEffectInstance(MobEffects.WATER_BREATHING, 20 * 60, 0));
    public static final DeferredItem<Plat> NUGGETS = plat("nuggets", 6, 0.6F, true, "Six nuggets, sauce barbecue",
            () -> new MobEffectInstance(MobEffects.DIG_SPEED, 20 * 30, 0));
    public static final DeferredItem<Plat> SUNDAE = plat("sundae", 4, 0.3F, true, "Glace vanille, nappage caramel",
            () -> new MobEffectInstance(MobEffects.FIRE_RESISTANCE, 20 * 30, 0));

    public static final List<DeferredItem<Plat>> TOUS = List.of(FRITES, HAMBURGER, CHEESEBURGER, DOUBLE_CHEESE, BIG_BEAUVAIS, FILET_O_FISH, NUGGETS, SUNDAE);

    private static DeferredItem<Plat> plat(String nom, int nourriture, float saturation, boolean rapide, String description,
            Supplier<MobEffectInstance> bonus) {
        return CabaneItems.ITEMS.register(nom, () -> {
            FoodProperties.Builder f = new FoodProperties.Builder().nutrition(nourriture).saturationModifier(saturation);
            if (rapide) {
                f.fast();
            }
            return new Plat(new Item.Properties().stacksTo(16).food(f.build()), description, bonus);
        });
    }

    private static DeferredItem<Plat> plat(String nom, int nourriture, float saturation, boolean rapide, String description) {
        return plat(nom, nourriture, saturation, rapide, description, null);
    }

    public static void init() {
        // charge la classe (et donc l'enregistrement des plats) depuis le constructeur du mod
    }

    /** Combien de plats chacun a mangés ces deux dernières minutes (au-delà de quatre : on est ballonné). */
    private static final Map<UUID, List<Long>> REPAS = new HashMap<>();

    public static class Plat extends Item {
        private final String description;
        private final Supplier<MobEffectInstance> bonus;

        Plat(Properties properties, String description, Supplier<MobEffectInstance> bonus) {
            super(properties);
            this.description = description;
            this.bonus = bonus;
        }

        @Override
        public ItemStack finishUsingItem(ItemStack stack, Level level, LivingEntity mangeur) {
            if (!level.isClientSide && mangeur instanceof ServerPlayer joueur) {
                if (bonus != null) {
                    joueur.addEffect(bonus.get());
                }
                long maintenant = level.getGameTime();
                List<Long> repas = REPAS.computeIfAbsent(joueur.getUUID(), k -> new ArrayList<>());
                repas.removeIf(t -> maintenant - t > 20 * 120);
                repas.add(maintenant);
                if (repas.size() >= 5) {
                    joueur.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 20 * 30, 0));
                    joueur.addEffect(new MobEffectInstance(MobEffects.CONFUSION, 20 * 6, 0));
                    level.playSound(null, joueur.blockPosition(), SoundEvents.PLAYER_BURP, SoundSource.PLAYERS, 1F, 0.6F);
                    joueur.displayClientMessage(Component.literal("Tu as un peu trop mangé… Ballonné !").withStyle(ChatFormatting.GOLD), true);
                    repas.clear();
                }
            }
            return super.finishUsingItem(stack, level, mangeur);
        }

        @Override
        public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
            tooltip.add(Component.literal(description).withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC));
            tooltip.add(Component.literal("McBeauvais — venez comme vous êtes").withStyle(ChatFormatting.RED));
        }
    }

    // ================================================================ la carte

    /** Un article de la carte : un plat seul ou un menu (plusieurs objets) ; surprise : une carte à collectionner en plus. */
    public record Article(String id, String nom, String categorie, long prix, List<Supplier<ItemStack>> contenu, boolean surprise, String detail) {
    }

    private static Supplier<ItemStack> o(Supplier<? extends Item> item) {
        return () -> new ItemStack(item.get());
    }

    public static final List<String> CATEGORIES = List.of("Menus", "Burgers", "À côté", "Boissons", "Desserts");

    public static final List<Article> CARTE = List.of(
            new Article("menu_big", "Menu Big Beauvais", "Menus", 55, List.of(o(BIG_BEAUVAIS), o(FRITES), o(Boissons.COCA)), false, "Big Beauvais, frites, Coca"),
            new Article("menu_cheese", "Menu Cheese", "Menus", 38, List.of(o(CHEESEBURGER), o(FRITES), o(Boissons.COCA)), false, "Cheeseburger, frites, Coca"),
            new Article("menu_fish", "Menu Filet-O-Fish", "Menus", 42, List.of(o(FILET_O_FISH), o(FRITES), o(Boissons.ICE_TEA)), false, "Filet-O-Fish, frites, Ice Tea"),
            new Article("menu_nuggets", "Menu Nuggets", "Menus", 40, List.of(o(NUGGETS), o(FRITES), o(Boissons.FANTA)), false, "Nuggets, frites, Fanta"),
            new Article("ptit_menu", "P'tit Menu", "Menus", 30, List.of(o(HAMBURGER), o(FRITES), o(Boissons.ICE_TEA)), true, "Hamburger, frites, Ice Tea + une carte surprise !"),
            new Article("hamburger", "Hamburger", "Burgers", 14, List.of(o(HAMBURGER)), false, "Steak, oignons, cornichon"),
            new Article("cheeseburger", "Cheeseburger", "Burgers", 17, List.of(o(CHEESEBURGER)), false, "Le cheddar qui coule"),
            new Article("double_cheese", "Double Cheese", "Burgers", 24, List.of(o(DOUBLE_CHEESE)), false, "Deux steaks, deux cheddars"),
            new Article("big_beauvais", "Big Beauvais", "Burgers", 30, List.of(o(BIG_BEAUVAIS)), false, "La sauce secrète"),
            new Article("filet_o_fish", "Filet-O-Fish", "Burgers", 20, List.of(o(FILET_O_FISH)), false, "Poisson pané, sauce tartare"),
            new Article("frites", "Frites", "À côté", 10, List.of(o(FRITES)), false, "Bien salées"),
            new Article("nuggets", "Nuggets x6", "À côté", 18, List.of(o(NUGGETS)), false, "Sauce barbecue"),
            new Article("coca", "Coca", "Boissons", 8, List.of(o(Boissons.COCA)), false, "Bien frais"),
            new Article("fanta", "Fanta", "Boissons", 8, List.of(o(Boissons.FANTA)), false, "Goût orange"),
            new Article("ice_tea", "Ice Tea pêche", "Boissons", 8, List.of(o(Boissons.ICE_TEA)), false, "À la pêche"),
            new Article("sundae", "Sundae caramel", "Desserts", 12, List.of(o(SUNDAE)), false, "Vanille, nappage caramel"));

    // ================================================================ les paquets

    public record Vue(String id, String nom, String categorie, long prix, ItemStack icone, String detail) {
        public static final StreamCodec<RegistryFriendlyByteBuf, Vue> CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, Vue::id,
                ByteBufCodecs.STRING_UTF8, Vue::nom,
                ByteBufCodecs.STRING_UTF8, Vue::categorie,
                ByteBufCodecs.VAR_LONG, Vue::prix,
                ItemStack.STREAM_CODEC, Vue::icone,
                ByteBufCodecs.STRING_UTF8, Vue::detail,
                Vue::new);
    }

    /** Serveur → client : la borne s'allume (la carte du jour). */
    public record Ouvrir(List<Vue> carte) implements CustomPacketPayload {
        public static final Type<Ouvrir> TYPE = new Type<>(Cabane.id("mcbeauvais_ouvrir"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Ouvrir> CODEC = Vue.CODEC.apply(ByteBufCodecs.list()).map(Ouvrir::new, Ouvrir::carte);

        @Override
        public Type<Ouvrir> type() {
            return TYPE;
        }
    }

    public record Ligne(String id, int nombre) {
        static final StreamCodec<ByteBuf, Ligne> CODEC = StreamCodec.composite(
                ByteBufCodecs.stringUtf8(64), Ligne::id,
                ByteBufCodecs.VAR_INT, Ligne::nombre,
                Ligne::new);
    }

    /** Client → serveur : le panier, à payer. */
    public record Commander(List<Ligne> panier) implements CustomPacketPayload {
        public static final Type<Commander> TYPE = new Type<>(Cabane.id("mcbeauvais_commander"));
        public static final StreamCodec<ByteBuf, Commander> CODEC = Ligne.CODEC.apply(ByteBufCodecs.list(32)).map(Commander::new, Commander::panier);

        @Override
        public Type<Commander> type() {
            return TYPE;
        }
    }

    /** Serveur → client : le ticket (numéro de commande), ou ce qui ne va pas. */
    public record Ticket(int numero, String message) implements CustomPacketPayload {
        public static final Type<Ticket> TYPE = new Type<>(Cabane.id("mcbeauvais_ticket"));
        public static final StreamCodec<ByteBuf, Ticket> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, Ticket::numero,
                ByteBufCodecs.STRING_UTF8, Ticket::message,
                Ticket::new);

        @Override
        public Type<Ticket> type() {
            return TYPE;
        }
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        event.registrar("1")
                .playToClient(Ouvrir.TYPE, Ouvrir.CODEC, (p, ctx) -> ctx.enqueueWork(() -> fr.cabane.client.ClientBorne.ouvrir(p.carte())))
                .playToClient(Ticket.TYPE, Ticket.CODEC, (p, ctx) -> ctx.enqueueWork(() -> fr.cabane.client.ClientBorne.ticket(p.numero(), p.message())))
                .playToServer(Commander.TYPE, Commander.CODEC, (p, ctx) -> ctx.enqueueWork(() -> {
                    if (ctx.player() instanceof ServerPlayer joueur) {
                        commander(joueur, p.panier());
                    }
                }));
    }

    // ================================================================ commander

    /** Où chacun a ouvert la borne  : il faut y être encore pour commander. */
    private static final Map<UUID, BlockPos> OUVERTE = new HashMap<>();

    public static void ouvrir(ServerPlayer joueur, BlockPos borne) {
        OUVERTE.put(joueur.getUUID(), borne == null ? joueur.blockPosition() : borne);
        Economie.sync(joueur);
        PacketDistributor.sendToPlayer(joueur, new Ouvrir(CARTE.stream()
                .map(a -> new Vue(a.id(), a.nom(), a.categorie(), a.prix(), a.contenu().get(0).get(), a.detail())).toList()));
    }

    private static int numero = 100 + (int) (Math.random() * 300);

    private record Attente(UUID joueur, List<ItemStack> plats, int numero, long quand) {
    }

    private static final List<Attente> EN_CUISINE = new ArrayList<>();

    /** /cabane commander : un article, comme si on l'avait choisi à la borne. */
    public static void commanderPourTest(ServerPlayer joueur, String article) {
        commander(joueur, List.of(new Ligne(article, 1)));
    }

    private static void commander(ServerPlayer joueur, List<Ligne> panier) {
        BlockPos ou = OUVERTE.get(joueur.getUUID());
        if (ou == null || joueur.blockPosition().distSqr(ou) > 10 * 10) {
            PacketDistributor.sendToPlayer(joueur, new Ticket(0, "Approche-toi de la borne pour commander."));
            return;
        }
        long total = 0;
        List<ItemStack> plats = new ArrayList<>();
        int articles = 0;
        for (Ligne l : panier) {
            Article a = CARTE.stream().filter(x -> x.id().equals(l.id())).findFirst().orElse(null);
            if (a == null || l.nombre() < 1 || l.nombre() > 16) {
                continue;
            }
            articles += l.nombre();
            total += a.prix() * l.nombre();
            for (int i = 0; i < l.nombre(); i++) {
                a.contenu().forEach(s -> plats.add(s.get()));
                if (a.surprise()) {
                    plats.add(fr.cabane.tcg.Cartes.carte(fr.cabane.tcg.Cartes.tirer(joueur.getRandom(), false).id()));
                }
            }
        }
        if (articles == 0 || articles > 32) {
            PacketDistributor.sendToPlayer(joueur, new Ticket(0, articles == 0 ? "Ton panier est vide." : "C'est beaucoup trop pour une seule commande !"));
            return;
        }
        if (!Economie.payer(joueur, total)) {
            PacketDistributor.sendToPlayer(joueur, new Ticket(0, "Paiement refusé : il te manque " + Economie.format(total - Economie.solde(joueur)) + "."));
            return;
        }
        numero = numero >= 999 ? 100 : numero + 1;
        EN_CUISINE.add(new Attente(joueur.getUUID(), plats, numero, joueur.server.getTickCount() + 50));
        joueur.level().playSound(null, joueur.blockPosition(), SoundEvents.NOTE_BLOCK_PLING.value(), SoundSource.BLOCKS, 0.6F, 1.6F);
        PacketDistributor.sendToPlayer(joueur, new Ticket(numero, "Paiement accepté : " + Economie.format(total)));
        fr.cabane.pnj.Missions.evenement(joueur, "mcbeauvais");
    }

    /** La cuisine : deux secondes et demie plus tard, la commande est prête. */
    private static void cuisine(MinecraftServer server) {
        for (Iterator<Attente> it = EN_CUISINE.iterator(); it.hasNext(); ) {
            Attente a = it.next();
            if (server.getTickCount() < a.quand()) {
                continue;
            }
            ServerPlayer joueur = server.getPlayerList().getPlayer(a.joueur());
            if (joueur == null) {
                continue;
            }
            it.remove();
            for (ItemStack s : a.plats()) {
                if (!joueur.getInventory().add(s)) {
                    joueur.drop(s, false);
                }
            }
            joueur.level().playSound(null, joueur.blockPosition(), SoundEvents.NOTE_BLOCK_BELL.value(), SoundSource.BLOCKS, 1F, 1.2F);
            joueur.sendSystemMessage(Component.literal("McBeauvais : ").withStyle(ChatFormatting.RED, ChatFormatting.BOLD)
                    .append(Component.literal("commande n°" + a.numero() + " prête ! Bon appétit.").withStyle(ChatFormatting.YELLOW)
                            .withStyle(s -> s.withBold(false))));
        }
    }

    @EventBusSubscriber(modid = Cabane.MODID)
    public static final class Evenements {
        @SubscribeEvent
        public static void tick(ServerTickEvent.Post event) {
            if (!EN_CUISINE.isEmpty()) {
                cuisine(event.getServer());
            }
        }

        private Evenements() {
        }
    }

    private FastFood() {
    }
}
