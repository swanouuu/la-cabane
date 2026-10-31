package fr.cabane;

import fr.cabane.bloc.BlocInteractif;
import fr.cabane.bloc.BlocInteractif.Ouverture;
import fr.cabane.bloc.EtalBlock;
import fr.cabane.bloc.EtalBlockEntity;
import fr.cabane.bloc.EtalMenu;
import java.util.List;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Les blocs du marché et du casino, l'étal des joueurs et son menu. */
public final class CabaneBlocks {
    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(Cabane.MODID);
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES = DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, Cabane.MODID);
    public static final DeferredRegister<MenuType<?>> MENUS = DeferredRegister.create(Registries.MENU, Cabane.MODID);

    public static final DeferredBlock<BlocInteractif> COMPTOIR = interactif("comptoir", Ouverture.BOUTIQUE);
    public static final DeferredBlock<BlocInteractif> TABLEAU_CONTRATS = interactif("tableau_contrats", Ouverture.CONTRATS);
    public static final DeferredBlock<BlocInteractif> MACHINE_A_SOUS = interactif("machine_a_sous", Ouverture.MACHINE_A_SOUS);
    public static final DeferredBlock<BlocInteractif> TABLE_ROULETTE = interactif("table_roulette", Ouverture.ROULETTE);
    public static final DeferredBlock<BlocInteractif> TABLE_BLACKJACK = interactif("table_blackjack", Ouverture.BLACKJACK);
    public static final DeferredBlock<BlocInteractif> TABLE_DES = interactif("table_des", Ouverture.DES);
    public static final DeferredBlock<BlocInteractif> ROUE_FORTUNE = interactif("roue_fortune", Ouverture.ROUE);
    public static final DeferredBlock<BlocInteractif> TABLE_PILE_OU_FACE = interactif("table_pile_ou_face", Ouverture.PILE_OU_FACE);

    public static final DeferredBlock<EtalBlock> ETAL = BLOCKS.registerBlock("etal", EtalBlock::new,
            BlockBehaviour.Properties.of().mapColor(MapColor.WOOD).strength(2.5F, 3_600_000F).sound(SoundType.WOOD).noOcclusion());

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<EtalBlockEntity>> ETAL_BE = BLOCK_ENTITIES.register("etal",
            () -> BlockEntityType.Builder.of(EtalBlockEntity::new, ETAL.get()).build(null));

    public static final DeferredHolder<MenuType<?>, MenuType<EtalMenu>> ETAL_MENU = MENUS.register("etal",
            () -> IMenuTypeExtension.create(EtalMenu::new));

    public static final List<DeferredBlock<?>> TOUS = List.of(COMPTOIR, TABLEAU_CONTRATS, ETAL, MACHINE_A_SOUS, TABLE_ROULETTE,
            TABLE_BLACKJACK, TABLE_DES, ROUE_FORTUNE, TABLE_PILE_OU_FACE);

    private static DeferredBlock<BlocInteractif> interactif(String nom, Ouverture ouverture) {
        return BLOCKS.registerBlock(nom, p -> new BlocInteractif(p, ouverture),
                BlockBehaviour.Properties.of().mapColor(MapColor.WOOD).strength(2.5F, 6F).sound(SoundType.WOOD).noOcclusion());
    }

    static void register(IEventBus modBus) {
        for (DeferredBlock<?> bloc : TOUS) {
            CabaneItems.ITEMS.registerSimpleBlockItem(bloc);
        }
        BLOCKS.register(modBus);
        BLOCK_ENTITIES.register(modBus);
        MENUS.register(modBus);
    }

    private CabaneBlocks() {
    }
}
