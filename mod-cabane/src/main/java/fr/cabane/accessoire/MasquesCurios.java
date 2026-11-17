package fr.cabane.accessoire;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import top.theillusivec4.curios.api.CuriosApi;
import top.theillusivec4.curios.api.SlotContext;
import top.theillusivec4.curios.api.type.capability.ICurioItem;

/**
 * Le lien avec Curios pour les masques, chargé seulement si Curios est là. Les slots (la tête, la ceinture) et les objets
 * qu'ils acceptent sont dans data/cabane/curios et data/curios/tags (outils/masques.mjs et outils/curios.mjs).
 */
final class MasquesCurios {
    /** Un clic droit avec le masque en main le met dans le premier slot libre : la tête, sinon la ceinture. */
    private static final ICurioItem PORTABLE = new ICurioItem() {
        @Override
        public boolean canEquipFromUse(SlotContext slot, ItemStack stack) {
            return true;
        }
    };

    static void portable(Item masque) {
        CuriosApi.registerCurio(masque, PORTABLE);
    }

    static boolean surLaTete(LivingEntity e) {
        return surLaTete(e, Masques.Masque.class);
    }

    /** La bulle de verre (accessoire/Bulle) est-elle sur la tête ? */
    static boolean bulle(LivingEntity e) {
        return surLaTete(e, Bulle.Objet.class);
    }

    /**
     * Un objet de cette sorte dans le slot de tête ? (findFirstCurio(filtre, "head") ne trouve rien sur Curios 9.5 : on
     * passe par findCurios("head"), qui liste bien ce qu'il y a dans le slot.)
     */
    private static boolean surLaTete(LivingEntity e, Class<? extends Item> sorte) {
        return CuriosApi.getCuriosInventory(e).map(inventaire -> inventaire.findCurios("head").stream()
                .anyMatch(r -> sorte.isInstance(r.stack().getItem()))).orElse(false);
    }

    private MasquesCurios() {
    }
}
