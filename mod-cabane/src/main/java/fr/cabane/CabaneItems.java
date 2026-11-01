package fr.cabane;

import fr.cabane.parachute.ParachuteItem;
import fr.cabane.tcg.Cartes;
import fr.cabane.tcg.TcgItems;
import fr.cabane.telephone.TelephoneItem;
import java.util.List;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Rarity;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class CabaneItems {
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(Cabane.MODID);
    public static final DeferredRegister<CreativeModeTab> TABS = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, Cabane.MODID);

    public static final DeferredItem<TelephoneItem> TELEPHONE =
            ITEMS.register("telephone", () -> new TelephoneItem(new Item.Properties().stacksTo(1)));
    public static final DeferredItem<ParachuteItem> PARACHUTE =
            ITEMS.register("parachute", () -> new ParachuteItem(new Item.Properties().durability(12)));

    // Jeu de cartes
    public static final DeferredItem<TcgItems.CarteItem> CARTE =
            ITEMS.register("carte", () -> new TcgItems.CarteItem(new Item.Properties().stacksTo(64)));
    public static final DeferredItem<TcgItems.BoosterItem> BOOSTER =
            ITEMS.register("booster", () -> new TcgItems.BoosterItem(new Item.Properties().stacksTo(16).rarity(Rarity.UNCOMMON)));
    public static final DeferredItem<TcgItems.ClasseurItem> CLASSEUR =
            ITEMS.register("classeur", () -> new TcgItems.ClasseurItem(new Item.Properties().stacksTo(1)));

    // Casino
    public static final DeferredItem<TcgItems.TicketItem> TICKET_A_GRATTER =
            ITEMS.register("ticket_a_gratter", () -> new TcgItems.TicketItem(new Item.Properties().stacksTo(16)));

    // La puff et le tuyau de la chicha
    public static final DeferredItem<fr.cabane.fumee.Fumee.PuffItem> PUFF_ROSE =
            ITEMS.register("puff_rose", () -> new fr.cabane.fumee.Fumee.PuffItem(new Item.Properties().durability(200), 0xFF8FC8, "fraise"));
    public static final DeferredItem<fr.cabane.fumee.Fumee.PuffItem> PUFF_BLEUE =
            ITEMS.register("puff_bleue", () -> new fr.cabane.fumee.Fumee.PuffItem(new Item.Properties().durability(200), 0x7FC8FF, "myrtille glacée"));
    public static final DeferredItem<fr.cabane.fumee.Fumee.PuffItem> PUFF_MENTHE =
            ITEMS.register("puff_menthe", () -> new fr.cabane.fumee.Fumee.PuffItem(new Item.Properties().durability(200), 0x8FFFC0, "menthe"));
    public static final DeferredItem<fr.cabane.fumee.Fumee.TuyauItem> TUYAU =
            ITEMS.register("tuyau", () -> new fr.cabane.fumee.Fumee.TuyauItem(new Item.Properties().stacksTo(1)));

    // La police de Beauvais
    public static final DeferredItem<fr.cabane.police.Police.MenottesItem> MENOTTES =
            ITEMS.register("menottes", () -> new fr.cabane.police.Police.MenottesItem(new Item.Properties().stacksTo(1)));
    public static final DeferredItem<fr.cabane.police.Police.PistoletItem> PISTOLET =
            ITEMS.register("pistolet", () -> new fr.cabane.police.Police.PistoletItem(new Item.Properties()));
    /** Un gilet qu'on enfile par-dessus ses habits : le skin reste visible. */
    public static final DeferredItem<net.minecraft.world.item.ArmorItem> GILET_POLICE = ITEMS.register("gilet_police",
            () -> new net.minecraft.world.item.ArmorItem(fr.cabane.police.Police.UNIFORME, net.minecraft.world.item.ArmorItem.Type.CHESTPLATE,
                    new Item.Properties().stacksTo(1).durability(net.minecraft.world.item.ArmorItem.Type.CHESTPLATE.getDurability(10))));
    public static final DeferredItem<fr.cabane.police.Police.VoitureItem> VOITURE_POLICE =
            ITEMS.register("voiture_police", () -> new fr.cabane.police.Police.VoitureItem(new Item.Properties().stacksTo(1)));

    // Les sept reliques, cachées dans les coffres de donjons précis (voir data/cabane/loot_modifiers)
    public static final DeferredItem<RelicItem> SCARABEE_DOR = relique("scarabee_dor", Rarity.UNCOMMON);
    public static final DeferredItem<RelicItem> IDOLE_DE_JADE = relique("idole_de_jade", Rarity.UNCOMMON);
    public static final DeferredItem<RelicItem> PERLE_DES_ABYSSES = relique("perle_des_abysses", Rarity.RARE);
    public static final DeferredItem<RelicItem> PLUME_CELESTE = relique("plume_celeste", Rarity.RARE);
    public static final DeferredItem<RelicItem> COURONNE_ILLAGEOISE = relique("couronne_illageoise", Rarity.RARE);
    public static final DeferredItem<RelicItem> OEIL_DU_GARDIEN = relique("oeil_du_gardien", Rarity.EPIC);
    public static final DeferredItem<RelicItem> ENGRENAGE_ANCIEN = relique("engrenage_ancien", Rarity.EPIC);

    public static final List<DeferredItem<RelicItem>> RELIQUES = List.of(
            SCARABEE_DOR, IDOLE_DE_JADE, PERLE_DES_ABYSSES, PLUME_CELESTE,
            COURONNE_ILLAGEOISE, OEIL_DU_GARDIEN, ENGRENAGE_ANCIEN);

    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> ONGLET = TABS.register("cabane", () ->
            CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.cabane"))
                    .icon(() -> new ItemStack(TELEPHONE.get()))
                    .displayItems((params, output) -> {
                        for (var entree : ITEMS.getEntries()) {
                            if (entree.get() != CARTE.get()) {
                                output.accept(entree.get());
                            }
                        }
                        Cartes.toutes().forEach(c -> output.accept(Cartes.carte(c.id())));
                    })
                    .build());

    private static DeferredItem<RelicItem> relique(String name, Rarity rarity) {
        return ITEMS.register(name, () -> new RelicItem(new Item.Properties().stacksTo(1).rarity(rarity).fireResistant()));
    }

    static void register(IEventBus modBus) {
        ITEMS.register(modBus);
        TABS.register(modBus);
    }

    private CabaneItems() {
    }
}
