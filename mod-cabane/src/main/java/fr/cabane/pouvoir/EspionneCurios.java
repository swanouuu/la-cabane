package fr.cabane.pouvoir;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import top.theillusivec4.curios.api.CuriosApi;
import top.theillusivec4.curios.api.SlotContext;
import top.theillusivec4.curios.api.type.capability.ICurioItem;

/**
 * Le lien avec Curios pour les accessoires des espionnes, chargé seulement si Curios est là. Les slots (tête, pieds,
 * ceinture) et les objets qu'ils acceptent sont dans data/cabane/curios et data/curios/tags (outils/curios.mjs).
 */
final class EspionneCurios {
    /** Un clic droit avec l'accessoire en main le met dans son slot. */
    private static final ICurioItem PORTABLE = new ICurioItem() {
        @Override
        public boolean canEquipFromUse(SlotContext slot, ItemStack stack) {
            return true;
        }
    };

    static void portable(Item objet) {
        CuriosApi.registerCurio(objet, PORTABLE);
    }

    static boolean porte(LivingEntity e, Item objet) {
        return CuriosApi.getCuriosInventory(e).map(inventaire -> inventaire.isEquipped(objet)).orElse(false);
    }

    private EspionneCurios() {
    }
}
