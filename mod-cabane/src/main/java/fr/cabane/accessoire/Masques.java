package fr.cabane.accessoire;

import fr.cabane.Cabane;
import fr.cabane.CabaneItems;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.registries.RegisterEvent;

/**
 * Les masques ninja, des accessoires de Curios : le démon Hannya, le renard Kitsune, le masque jaune de Tobi, la spirale
 * orange, le masque de guerre d'Obito et le masque du chasseur (dessins : outils/masques.mjs, rendu : client/RenduMasques).
 * Un clic droit avec le masque en main le met sur la tête ; si la tête est prise, il va à la ceinture, sur le côté. Sur la
 * tête, la voix au vocal devient très grave (telephone/VoixMasque) ; à la ceinture, c'est juste pour le style.
 */
@EventBusSubscriber(modid = Cabane.MODID)
public final class Masques {
    public static final List<String> NOMS = List.of("hannya", "kitsune", "tobi", "spirale", "obito", "chasseur");
    private static final boolean CURIOS = ModList.get().isLoaded("curios");
    /** Ceux qui ont un masque sur la tête, relus 2 fois par seconde : le vocal (sur son propre fil) le lit ici. */
    private static final Set<UUID> SUR_LA_TETE = ConcurrentHashMap.newKeySet();

    public static ResourceLocation id(String nom) {
        return Cabane.id("masque_" + nom);
    }

    /** Un masque : il ne fait rien tout seul, Curios le porte. */
    public static final class Masque extends Item {
        Masque() {
            super(new Item.Properties().stacksTo(1));
        }

        @Override
        public void appendHoverText(ItemStack objet, TooltipContext contexte, List<Component> lignes, TooltipFlag options) {
            lignes.add(Component.translatable("item.cabane.masque.desc").withStyle(ChatFormatting.GRAY));
        }
    }

    @SubscribeEvent
    public static void objets(RegisterEvent event) {
        for (String nom : NOMS) {
            event.register(Registries.ITEM, id(nom), Masque::new);
        }
    }

    @SubscribeEvent
    public static void onglet(BuildCreativeModeTabContentsEvent event) {
        if (event.getTab() == CabaneItems.ONGLET.get()) {
            NOMS.forEach(nom -> event.accept(BuiltInRegistries.ITEM.get(id(nom))));
        }
    }

    @SubscribeEvent
    public static void installer(FMLCommonSetupEvent event) {
        if (CURIOS) {
            event.enqueueWork(() -> NOMS.forEach(nom -> MasquesCurios.portable(BuiltInRegistries.ITEM.get(id(nom)))));
        }
    }

    @SubscribeEvent
    public static void tick(ServerTickEvent.Post event) {
        if (!CURIOS || event.getServer().getTickCount() % 10 != 0) {
            return;
        }
        Set<UUID> vus = new java.util.HashSet<>();
        for (ServerPlayer j : event.getServer().getPlayerList().getPlayers()) {
            if (MasquesCurios.surLaTete(j)) {
                vus.add(j.getUUID());
            }
        }
        SUR_LA_TETE.retainAll(vus);
        SUR_LA_TETE.addAll(vus);
    }

    /** A-t-il un masque sur la tête ? (sa voix devient grave) */
    public static boolean surLaTete(UUID joueur) {
        return SUR_LA_TETE.contains(joueur);
    }

    private Masques() {
    }
}
