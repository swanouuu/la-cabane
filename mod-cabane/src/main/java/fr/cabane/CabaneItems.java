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
