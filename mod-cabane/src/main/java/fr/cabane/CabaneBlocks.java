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
    /** Incassable : seul un admin la déplace (par commande), pour qu'on ne vole pas la vente d'un terrain. */
    public static final DeferredBlock<BlocInteractif> BORNE_TERRAIN = BLOCKS.registerBlock("borne_terrain", p -> new BlocInteractif(p, Ouverture.TERRAIN),
            BlockBehaviour.Properties.of().mapColor(MapColor.WOOD).strength(-1F, 3_600_000F).sound(SoundType.WOOD).noOcclusion().noLootTable());
    public static final DeferredBlock<BlocInteractif> MACHINE_A_SOUS = interactif("machine_a_sous", Ouverture.MACHINE_A_SOUS);
    public static final DeferredBlock<BlocInteractif> TABLE_ROULETTE = interactif("table_roulette", Ouverture.ROULETTE);
    public static final DeferredBlock<BlocInteractif> TABLE_BLACKJACK = interactif("table_blackjack", Ouverture.BLACKJACK);
    public static final DeferredBlock<BlocInteractif> TABLE_DES = interactif("table_des", Ouverture.DES);
    public static final DeferredBlock<BlocInteractif> ROUE_FORTUNE = interactif("roue_fortune", Ouverture.ROUE);
    public static final DeferredBlock<BlocInteractif> TABLE_PILE_OU_FACE = interactif("table_pile_ou_face", Ouverture.PILE_OU_FACE);
    /** La borne de commande du McBeauvais : trois blocs de haut, un grand écran tactile (voir fr.cabane.bouffe). */
    public static final DeferredBlock<BlocInteractif> BORNE_COMMANDE = BLOCKS.registerBlock("borne_commande", p -> new BlocInteractif(p, Ouverture.BORNE),
            BlockBehaviour.Properties.of().mapColor(MapColor.METAL).strength(2.5F, 6F).sound(SoundType.METAL).noOcclusion().lightLevel(s -> 6));

    /** Les cases invisibles des grandes tables du casino (pas d'objet : elles viennent avec la table). */
    public static final DeferredBlock<fr.cabane.bloc.GrandesTables.Partie> PARTIE_TABLE = BLOCKS.registerBlock("partie_table", fr.cabane.bloc.GrandesTables.Partie::new,
            BlockBehaviour.Properties.of().mapColor(MapColor.WOOD).strength(2.5F, 6F).sound(SoundType.WOOD).noOcclusion().noLootTable());

    public static final DeferredBlock<fr.cabane.fumee.Fumee.ChichaBlock> CHICHA = BLOCKS.registerBlock("chicha", fr.cabane.fumee.Fumee.ChichaBlock::new,
            BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_PURPLE).strength(1F).sound(SoundType.GLASS).noOcclusion());

    /** Une bibliothèque du bureau du Président, qui pivote d'un clic : le passage vers le laboratoire. */
    public static final DeferredBlock<fr.cabane.histoire.Histoire.BibliothequeSecrete> BIBLIOTHEQUE_SECRETE = BLOCKS.registerBlock("bibliotheque_secrete",
            fr.cabane.histoire.Histoire.BibliothequeSecrete::new,
            BlockBehaviour.Properties.of().mapColor(MapColor.WOOD).strength(-1F, 3_600_000F).sound(SoundType.WOOD).noLootTable());
    /** Le pupitre de la Salle Zéro, au cœur du PLATO 6Z : la Clé y réveille le Président. Incassable. */
    public static final DeferredBlock<fr.cabane.histoire.Histoire.PupitreBlock> PUPITRE_PRESIDENT = BLOCKS.registerBlock("pupitre_president",
            fr.cabane.histoire.Histoire.PupitreBlock::new,
            BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_BLACK).strength(-1F, 3_600_000F).sound(SoundType.NETHERITE_BLOCK).noOcclusion().noLootTable()
                    .lightLevel(s -> 7));

    public static final DeferredBlock<EtalBlock> ETAL = BLOCKS.registerBlock("etal", EtalBlock::new,
            BlockBehaviour.Properties.of().mapColor(MapColor.WOOD).strength(2.5F, 3_600_000F).sound(SoundType.WOOD).noOcclusion());

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<EtalBlockEntity>> ETAL_BE = BLOCK_ENTITIES.register("etal",
            () -> BlockEntityType.Builder.of(EtalBlockEntity::new, ETAL.get()).build(null));

    /** La mémoire des blocs interactifs (propriétaire, caisse du casino), et l'affichage des contrats. */
    @SuppressWarnings("DataFlowIssue")
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<fr.cabane.bloc.InteractifBlockEntity>> INTERACTIF_BE =
            BLOCK_ENTITIES.register("interactif", () -> BlockEntityType.Builder.of(fr.cabane.bloc.InteractifBlockEntity::new,
                    COMPTOIR.get(), TABLEAU_CONTRATS.get(), BORNE_TERRAIN.get(), MACHINE_A_SOUS.get(), TABLE_ROULETTE.get(), TABLE_BLACKJACK.get(),
                    TABLE_DES.get(), ROUE_FORTUNE.get(), TABLE_PILE_OU_FACE.get(), BORNE_COMMANDE.get()).build(null));

    public static final DeferredHolder<MenuType<?>, MenuType<EtalMenu>> ETAL_MENU = MENUS.register("etal",
            () -> IMenuTypeExtension.create(EtalMenu::new));

    public static final List<DeferredBlock<?>> TOUS = List.of(COMPTOIR, TABLEAU_CONTRATS, BORNE_TERRAIN, ETAL, MACHINE_A_SOUS, TABLE_ROULETTE,
            TABLE_BLACKJACK, TABLE_DES, ROUE_FORTUNE, TABLE_PILE_OU_FACE, CHICHA, BIBLIOTHEQUE_SECRETE, PUPITRE_PRESIDENT, BORNE_COMMANDE);

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
