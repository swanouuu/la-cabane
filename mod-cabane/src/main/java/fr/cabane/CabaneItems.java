package fr.cabane;

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
                        output.accept(TELEPHONE.get());
                        RELIQUES.forEach(r -> output.accept(r.get()));
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
