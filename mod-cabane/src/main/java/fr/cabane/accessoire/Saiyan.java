package fr.cabane.accessoire;

import fr.cabane.CabaneItems;
import java.util.List;
import net.minecraft.world.item.Item;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.registries.DeferredItem;

/**
 * Les accessoires Saiyan, juste pour le style (aucune protection, aucun pouvoir) : la queue, qui se balance toute seule, et
 * le détecteur de Vegeta, avec un verre de 8 couleurs. Ils vont dans les slots de Curios : la queue dans son slot à elle, le
 * détecteur dans celui de la tête (on garde son casque). Un clic droit avec l'objet en main l'équipe. Rendu dans
 * client/RenduSaiyan, dessins dans docs/saiyan.
 */
public final class Saiyan {
    public static final List<String> COULEURS = List.of("rouge", "vert", "bleu", "violet", "jaune", "orange", "rose", "cyan");
    /** Les mêmes, en RVB (le verre et la petite lumière de l'oreillette). */
    public static final int[] RVB = {0xEC344E, 0x3CE06E, 0x3C82FF, 0xAA50F0, 0xFFD628, 0xFF8C1E, 0xFF6EC8, 0x28DCE6};

    public static final DeferredItem<Item> QUEUE =
            CabaneItems.ITEMS.register("queue_saiyan", () -> new Item(new Item.Properties().stacksTo(1)));
    public static final List<DeferredItem<Item>> DETECTEURS = COULEURS.stream()
            .map(c -> CabaneItems.ITEMS.register("detecteur_" + c, () -> new Item(new Item.Properties().stacksTo(1))))
            .toList();

    public static void register(IEventBus modBus) {
        // Curios est dans le pack ; sans lui, les objets existent mais ne se portent pas
        if (ModList.get().isLoaded("curios")) {
            modBus.addListener(SaiyanCurios::installer);
        }
    }

    private Saiyan() {
    }
}
