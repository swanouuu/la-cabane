package fr.cabane.accessoire;

import net.minecraft.world.item.ItemStack;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import top.theillusivec4.curios.api.CuriosApi;
import top.theillusivec4.curios.api.SlotContext;
import top.theillusivec4.curios.api.type.capability.ICurioItem;

/**
 * Le lien avec Curios, chargé seulement si Curios est là. Les slots (la queue, la tête) et les objets qu'ils acceptent sont
 * dans data/cabane/curios et data/curios/tags (écrits par outils/saiyan.mjs).
 */
final class SaiyanCurios {
    /** Un clic droit avec l'objet en main le met dans le premier slot libre qui l'accepte. */
    private static final ICurioItem PORTABLE = new ICurioItem() {
        @Override
        public boolean canEquipFromUse(SlotContext slot, ItemStack stack) {
            return true;
        }
    };

    static void installer(FMLCommonSetupEvent event) {
        event.enqueueWork(() -> {
            CuriosApi.registerCurio(Saiyan.QUEUE.get(), PORTABLE);
            Saiyan.DETECTEURS.forEach(d -> CuriosApi.registerCurio(d.get(), PORTABLE));
        });
    }

    private SaiyanCurios() {
    }
}
