package fr.cabane.accessoire;

import fr.cabane.Cabane;
import fr.cabane.CabaneItems;
import java.util.EnumMap;
import java.util.List;
import net.minecraft.Util;
import net.minecraft.core.registries.Registries;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ArmorMaterial;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.crafting.Ingredient;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Les accessoires marrants, à porter sur la tête (à la place du casque) : lunettes de soleil, lunettes cœur, lunettes 3D,
 * les lunettes pixel « deal with it », la moustache et le nez de clown. Aucune protection : c'est pour le style.
 * Les dessins sont dans outils/accessoires.mjs.
 */
public final class Accessoires {
    public static final DeferredRegister<ArmorMaterial> MATERIAUX = DeferredRegister.create(Registries.ARMOR_MATERIAL, Cabane.MODID);
    public static final List<String> NOMS = List.of("lunettes_soleil", "lunettes_coeur", "lunettes_3d", "lunettes_pixel", "moustache", "nez_clown");
    public static final List<DeferredItem<ArmorItem>> TOUS = NOMS.stream().map(Accessoires::accessoire).toList();

    private static DeferredItem<ArmorItem> accessoire(String nom) {
        DeferredHolder<ArmorMaterial, ArmorMaterial> materiau = MATERIAUX.register(nom, () -> new ArmorMaterial(
                Util.make(new EnumMap<>(ArmorItem.Type.class), m -> {
                    for (ArmorItem.Type type : ArmorItem.Type.values()) {
                        m.put(type, 0);
                    }
                }),
                5, SoundEvents.ARMOR_EQUIP_LEATHER, () -> Ingredient.EMPTY, List.of(new ArmorMaterial.Layer(Cabane.id(nom))), 0F, 0F));
        return CabaneItems.ITEMS.register(nom, () -> new ArmorItem(materiau, ArmorItem.Type.HELMET, new Item.Properties().stacksTo(1)));
    }

    public static void register(IEventBus modBus) {
        MATERIAUX.register(modBus);
    }

    private Accessoires() {
    }
}
