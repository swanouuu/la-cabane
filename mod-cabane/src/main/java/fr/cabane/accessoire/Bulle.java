package fr.cabane.accessoire;

import fr.cabane.Cabane;
import fr.cabane.CabaneItems;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.registries.RegisterEvent;

/**
 * La bulle de verre, un accessoire de Curios pour la tête (dessin : client/RenduBulle, icône : outils/bulle.mjs). Elle ne
 * fait qu'une chose : qui a mangé un fruit du démon et la porte sur la tête ne perd plus de vie dans l'eau, et s'y soigne
 * comme tout le monde (pouvoir/Fruit, pouvoir/Phenix). Le reste ne change pas : il nage selon ses enchantements, comme un
 * joueur normal.
 */
@EventBusSubscriber(modid = Cabane.MODID)
public final class Bulle {
    public static final ResourceLocation ID = Cabane.id("bulle_verre");
    private static final boolean CURIOS = ModList.get().isLoaded("curios");

    /** La bulle : elle ne fait rien toute seule, Curios la porte. */
    public static final class Objet extends Item {
        Objet() {
            super(new Item.Properties().stacksTo(1));
        }

        @Override
        public void appendHoverText(ItemStack objet, TooltipContext contexte, List<Component> lignes, TooltipFlag options) {
            lignes.add(Component.translatable("item.cabane.bulle_verre.desc").withStyle(ChatFormatting.GRAY));
        }
    }

    @SubscribeEvent
    public static void objets(RegisterEvent event) {
        event.register(Registries.ITEM, ID, Objet::new);
    }

    @SubscribeEvent
    public static void onglet(BuildCreativeModeTabContentsEvent event) {
        if (event.getTab() == CabaneItems.ONGLET.get()) {
            event.accept(BuiltInRegistries.ITEM.get(ID));
        }
    }

    @SubscribeEvent
    public static void installer(FMLCommonSetupEvent event) {
        if (CURIOS) {
            event.enqueueWork(() -> MasquesCurios.portable(BuiltInRegistries.ITEM.get(ID)));
        }
    }

    /** A-t-il la bulle sur la tête ? */
    public static boolean surLaTete(LivingEntity e) {
        return CURIOS && MasquesCurios.bulle(e);
    }

    /** Dans l'eau, sans bulle sur la tête : là seulement, l'eau fait mal à un fruit du démon et l'empêche de guérir. */
    public static boolean noye(LivingEntity e) {
        return e.isInWater() && !surLaTete(e);
    }

    private Bulle() {
    }
}
