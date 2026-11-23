package fr.cabane.bouffe;

import com.mojang.serialization.MapCodec;
import fr.cabane.CabaneBlocks;
import fr.cabane.CabaneItems;
import java.util.List;
import java.util.function.Supplier;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ItemParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemUtils;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.item.Tiers;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.UseAnim;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.neoforge.common.EffectCures;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;

/**
 * Isma Kebab : sandwich, galette, assiette, tacos, la broche qui tourne devant son grill, le couteau pour la découper, les
 * sauces (blanche, samouraï, algérienne), l'ayran et les baklavas. Les plats comptent pour « Ballonné » comme ceux du
 * McBeauvais. La sauce d'un plat est notée dans ses données (« sauce ») : on la choisit en le fabriquant, ou on la change
 * en mettant le plat et une autre sauce dans la table de craft (recettes dans outils/kebab.mjs). Le kebab lui-même se
 * construit avec /kebab (voir {@link IsmaKebab}).
 */
public final class Kebab {
    private static MobEffectInstance effet(net.minecraft.core.Holder<net.minecraft.world.effect.MobEffect> e, int secondes, int niveau) {
        return new MobEffectInstance(e, 20 * secondes, niveau);
    }

    public static final DeferredItem<PlatKebab> SANDWICH = plat("sandwich_kebab", 8, 0.8F, "Pain turc, viande, salade tomate oignon",
            () -> effet(MobEffects.DAMAGE_BOOST, 40, 0));
    public static final DeferredItem<PlatKebab> GALETTE = plat("galette_kebab", 8, 0.8F, "Roulée dans l'alu, elle se mange en marchant",
            () -> effet(MobEffects.MOVEMENT_SPEED, 40, 0));
    public static final DeferredItem<PlatKebab> ASSIETTE = plat("assiette_kebab", 11, 0.9F, "Viande, frites, salade, et la sauce à côté",
            () -> effet(MobEffects.ABSORPTION, 45, 1));
    public static final DeferredItem<PlatKebab> TACOS = plat("tacos", 12, 0.9F, "Viande, frites et sauce fromagère dans la galette grillée. Ça cale.",
            () -> effet(MobEffects.DAMAGE_RESISTANCE, 45, 0), () -> effet(MobEffects.MOVEMENT_SLOWDOWN, 15, 0));

    public static final DeferredItem<Simple> VIANDE = CabaneItems.ITEMS.register("viande_kebab", () -> new Simple(new Item.Properties().stacksTo(64)
            .food(new FoodProperties.Builder().nutrition(3).saturationModifier(0.4F).fast().build()), "Découpée à la broche, encore chaude"));
    public static final DeferredItem<Simple> PAIN_TURC = CabaneItems.ITEMS.register("pain_turc", () -> new Simple(new Item.Properties().stacksTo(64)
            .food(new FoodProperties.Builder().nutrition(4).saturationModifier(0.5F).build()), "Bien gonflé, sésame dessus"));
    public static final DeferredItem<Simple> BAKLAVA = CabaneItems.ITEMS.register("baklava", () -> new Simple(new Item.Properties().stacksTo(16)
            .food(new FoodProperties.Builder().nutrition(4).saturationModifier(0.4F).fast()
                    .effect(() -> effet(MobEffects.REGENERATION, 10, 0), 1F).build()), "Feuilleté au miel et à la pistache"));

    public static final DeferredItem<Sauce> SAUCE_BLANCHE = sauce("sauce_blanche", "blanche", "La classique",
            "Ajoute Régénération I 8 s au plat");
    public static final DeferredItem<Sauce> SAUCE_SAMOURAI = sauce("sauce_samourai", "samourai", "Ça pique un peu",
            "Ajoute Force I 30 s au plat (Force II dans le sandwich)");
    public static final DeferredItem<Sauce> SAUCE_ALGERIENNE = sauce("sauce_algerienne", "algerienne", "Oignon, poivron, un peu épicée",
            "Ajoute Hâte I 30 s au plat");

    public static final DeferredItem<Ayran> AYRAN = CabaneItems.ITEMS.register("ayran", () -> new Ayran(new Item.Properties().stacksTo(16)));

    public static final DeferredItem<Couteau> COUTEAU = CabaneItems.ITEMS.register("couteau_kebab", () -> new Couteau(new Item.Properties()
            .attributes(SwordItem.createAttributes(Tiers.IRON, 3, -2.4F))));

    // ================================================================ la broche et son grill

    public static final DeferredBlock<BrocheBlock> BROCHE = CabaneBlocks.BLOCKS.registerBlock("broche", BrocheBlock::new,
            BlockBehaviour.Properties.of().mapColor(MapColor.METAL).strength(1.5F, 6F).sound(SoundType.METAL).noOcclusion());
    /** La viande de la broche, dessinée tranche par tranche par le rendu (aucun objet : elle n'existe pas seule). */
    public static final DeferredBlock<ViandeBroche> VIANDE_BROCHE = CabaneBlocks.BLOCKS.registerBlock("viande_broche", ViandeBroche::new,
            BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_BROWN).strength(1F).noOcclusion().noLootTable());
    public static final DeferredBlock<GrillBlock> GRILL = CabaneBlocks.BLOCKS.registerBlock("grill_kebab", GrillBlock::new,
            BlockBehaviour.Properties.of().mapColor(MapColor.METAL).strength(1.5F, 6F).sound(SoundType.METAL).noOcclusion().lightLevel(s -> 12));

    public static final DeferredItem<BlockItem> BROCHE_OBJET = CabaneItems.ITEMS.register("broche", () -> new BlockItem(BROCHE.get(), new Item.Properties().stacksTo(1)) {
        @Override
        public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
            tooltip.add(Component.literal("Elle tourne toute seule devant son grill").withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC));
            tooltip.add(Component.literal("Clic droit avec le couteau à kebab : une viande").withStyle(ChatFormatting.GOLD));
            tooltip.add(Component.literal("Elle se recharge toute seule").withStyle(ChatFormatting.DARK_GRAY));
            tooltip.add(ENSEIGNE);
        }
    });
    public static final DeferredItem<BlockItem> GRILL_OBJET = CabaneItems.ITEMS.registerSimpleBlockItem(GRILL);

    @SuppressWarnings("DataFlowIssue")
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<BrocheBlockEntity>> BROCHE_BE = CabaneBlocks.BLOCK_ENTITIES.register("broche",
            () -> BlockEntityType.Builder.of(BrocheBlockEntity::new, BROCHE.get()).build(null));

    public static final List<DeferredItem<PlatKebab>> PLATS = List.of(SANDWICH, GALETTE, ASSIETTE, TACOS);

    static final Component ENSEIGNE = Component.literal("Isma Kebab — sur place ou à emporter").withStyle(ChatFormatting.YELLOW);

    public static void init() {
        // charge la classe (et donc l'enregistrement) depuis le constructeur du mod
    }

    private Kebab() {
    }

    // ================================================================ les plats

    @SafeVarargs
    private static DeferredItem<PlatKebab> plat(String nom, int nourriture, float saturation, String description,
            Supplier<MobEffectInstance> bonus, Supplier<MobEffectInstance>... autres) {
        return CabaneItems.ITEMS.register(nom, () -> new PlatKebab(new Item.Properties().stacksTo(16)
                .food(new FoodProperties.Builder().nutrition(nourriture).saturationModifier(saturation).build()), description, bonus, List.of(autres)));
    }

    /** La sauce notée dans un plat (« blanche », « samourai », « algerienne »), ou "" sans sauce. */
    public static String sauce(ItemStack stack) {
        CustomData d = stack.get(DataComponents.CUSTOM_DATA);
        return d == null ? "" : d.copyTag().getString("sauce");
    }

    public static ItemStack avecSauce(ItemStack stack, String sauce) {
        CompoundTag t = new CompoundTag();
        t.putString("sauce", sauce);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(t));
        return stack;
    }

    private static String nomSauce(String s) {
        return switch (s) {
            case "blanche" -> "blanche";
            case "samourai" -> "samouraï";
            case "algerienne" -> "algérienne";
            default -> s;
        };
    }

    public static class PlatKebab extends FastFood.Plat {
        private final String description;
        private final Supplier<MobEffectInstance> bonus;
        private final List<Supplier<MobEffectInstance>> autres;

        PlatKebab(Properties p, String description, Supplier<MobEffectInstance> bonus, List<Supplier<MobEffectInstance>> autres) {
            super(p, description, bonus);
            this.description = description;
            this.bonus = bonus;
            this.autres = autres;
        }

        @Override
        public ItemStack finishUsingItem(ItemStack stack, Level level, LivingEntity mangeur) {
            String sauce = sauce(stack);
            if (!level.isClientSide && mangeur instanceof ServerPlayer joueur) {
                autres.forEach(e -> joueur.addEffect(e.get()));
                switch (sauce) {
                    case "blanche" -> joueur.addEffect(effet(MobEffects.REGENERATION, 8, 0));
                    case "samourai" -> joueur.addEffect(bonus.get().is(MobEffects.DAMAGE_BOOST)
                            ? effet(MobEffects.DAMAGE_BOOST, 40, 1) : effet(MobEffects.DAMAGE_BOOST, 30, 0));
                    case "algerienne" -> joueur.addEffect(effet(MobEffects.DIG_SPEED, 30, 0));
                    default -> {
                    }
                }
            }
            return super.finishUsingItem(stack, level, mangeur);
        }

        @Override
        public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
            tooltip.add(Component.literal(description).withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC));
            String s = sauce(stack);
            tooltip.add(Component.literal(s.isEmpty() ? "Sans sauce" : "Sauce " + nomSauce(s)).withStyle(ChatFormatting.WHITE));
            tooltip.add(ENSEIGNE);
        }
    }

    /** Viande, pain turc, baklava : juste une petite phrase en plus. */
    public static class Simple extends Item {
        private final String description;

        Simple(Properties p, String description) {
            super(p);
            this.description = description;
        }

        @Override
        public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
            tooltip.add(Component.literal(description).withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC));
            tooltip.add(ENSEIGNE);
        }
    }

    // ================================================================ les sauces

    private static DeferredItem<Sauce> sauce(String nom, String id, String description, String effet) {
        return CabaneItems.ITEMS.register(nom, () -> new Sauce(new Item.Properties().stacksTo(16), id, description, effet));
    }

    public static class Sauce extends Item {
        public final String id;
        private final String description;
        private final String effet;

        Sauce(Properties p, String id, String description, String effet) {
            super(p);
            this.id = id;
            this.description = description;
            this.effet = effet;
        }

        @Override
        public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
            tooltip.add(Component.literal(description).withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC));
            tooltip.add(Component.literal(effet).withStyle(ChatFormatting.GOLD));
            tooltip.add(Component.literal("Avec un plat dans la table de craft").withStyle(ChatFormatting.DARK_GRAY));
            tooltip.add(ENSEIGNE);
        }
    }

    // ================================================================ l'ayran : comme le lait, et finis les ballonnements

    public static class Ayran extends Item {
        Ayran(Properties p) {
            super(p);
        }

        @Override
        public ItemStack finishUsingItem(ItemStack stack, Level level, LivingEntity buveur) {
            if (!level.isClientSide) {
                buveur.removeEffectsCuredBy(EffectCures.MILK);
                FastFood.digerer(buveur.getUUID());
            }
            if (buveur instanceof Player p && !p.getAbilities().instabuild) {
                stack.shrink(1);
                if (stack.isEmpty()) {
                    return new ItemStack(Items.GLASS_BOTTLE);
                }
                if (!p.getInventory().add(new ItemStack(Items.GLASS_BOTTLE))) {
                    p.drop(new ItemStack(Items.GLASS_BOTTLE), false);
                }
            }
            return stack;
        }

        @Override
        public int getUseDuration(ItemStack stack, LivingEntity entite) {
            return 32;
        }

        @Override
        public UseAnim getUseAnimation(ItemStack stack) {
            return UseAnim.DRINK;
        }

        @Override
        public InteractionResultHolder<ItemStack> use(Level level, Player joueur, InteractionHand main) {
            return ItemUtils.startUsingInstantly(level, joueur, main);
        }

        @Override
        public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
            tooltip.add(Component.literal("Yaourt salé bien frais").withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC));
            tooltip.add(Component.literal("Retire les effets comme le lait, et le ballonnement").withStyle(ChatFormatting.AQUA));
            tooltip.add(ENSEIGNE);
        }
    }

    // ================================================================ le couteau

    public static class Couteau extends SwordItem {
        Couteau(Properties p) {
            super(Tiers.IRON, p);
        }

        @Override
        public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
            tooltip.add(Component.literal("La grande lame du kebabier").withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC));
            tooltip.add(Component.literal("Clic droit sur une broche : une viande de kebab").withStyle(ChatFormatting.GOLD));
            tooltip.add(ENSEIGNE);
        }
    }

    // ================================================================ la broche

    public static class BrocheBlock extends BaseEntityBlock {
        public static final MapCodec<BrocheBlock> CODEC = simpleCodec(BrocheBlock::new);
        /** Combien de viandes il reste à découper. */
        public static final int MAX = 8;
        public static final IntegerProperty PARTS = IntegerProperty.create("parts", 0, MAX);
        public static final BooleanProperty POULET = BooleanProperty.create("poulet");
        private static final VoxelShape FORME = Block.box(3, 0, 3, 13, 16, 13);

        public BrocheBlock(Properties p) {
            super(p);
            registerDefaultState(stateDefinition.any().setValue(PARTS, MAX).setValue(POULET, false));
        }

        @Override
        protected MapCodec<? extends BaseEntityBlock> codec() {
            return CODEC;
        }

        @Override
        protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> b) {
            b.add(PARTS, POULET);
        }

        @Override
        protected RenderShape getRenderShape(BlockState state) {
            return RenderShape.MODEL;
        }

        @Override
        protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
            return FORME;
        }

        @Override
        public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
            return new BrocheBlockEntity(pos, state);
        }

        @Override
        protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player joueur, InteractionHand main,
                BlockHitResult hit) {
            if (!(stack.getItem() instanceof Couteau)) {
                return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
            }
            int parts = state.getValue(PARTS);
            if (parts <= 0) {
                if (!level.isClientSide) {
                    joueur.displayClientMessage(Component.literal("La broche est vide… elle recuit, reviens tout à l'heure.").withStyle(ChatFormatting.GRAY), true);
                }
                return ItemInteractionResult.sidedSuccess(level.isClientSide);
            }
            if (level instanceof ServerLevel sl) {
                sl.setBlock(pos, state.setValue(PARTS, parts - 1), Block.UPDATE_ALL);
                ItemStack viande = new ItemStack(VIANDE.get());
                if (!joueur.getInventory().add(viande)) {
                    joueur.drop(viande, false);
                }
                stack.hurtAndBreak(1, joueur, LivingEntity.getSlotForHand(main));
                sl.playSound(null, pos, SoundEvents.SHEEP_SHEAR, SoundSource.BLOCKS, 0.8F, 1.3F);
                sl.playSound(null, pos, SoundEvents.GENERIC_EAT, SoundSource.BLOCKS, 0.3F, 1.6F);
                sl.sendParticles(new ItemParticleOption(ParticleTypes.ITEM, new ItemStack(VIANDE.get())), pos.getX() + 0.5, pos.getY() + 1.0,
                        pos.getZ() + 0.5, 6, 0.2, 0.3, 0.2, 0.05);
            }
            return ItemInteractionResult.sidedSuccess(level.isClientSide);
        }

        @Override
        protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player joueur, BlockHitResult hit) {
            if (!level.isClientSide) {
                joueur.displayClientMessage(Component.literal(state.getValue(PARTS) > 0
                        ? "Il te faut le couteau à kebab pour la découper." : "La broche est vide… elle recuit, reviens tout à l'heure.")
                        .withStyle(ChatFormatting.GRAY), true);
            }
            return InteractionResult.sidedSuccess(level.isClientSide);
        }

        @Override
        protected boolean isRandomlyTicking(BlockState state) {
            return state.getValue(PARTS) < MAX;
        }

        @Override
        protected void randomTick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
            level.setBlock(pos, state.setValue(PARTS, state.getValue(PARTS) + 1), Block.UPDATE_ALL);
        }

        @Override
        public void animateTick(BlockState state, Level level, BlockPos pos, RandomSource random) {
            if (random.nextInt(6) == 0) {
                level.addParticle(ParticleTypes.SMOKE, pos.getX() + 0.3 + random.nextDouble() * 0.4, pos.getY() + 1.9, pos.getZ() + 0.3 + random.nextDouble() * 0.4,
                        0, 0.02, 0);
            }
        }
    }

    public static class BrocheBlockEntity extends BlockEntity {
        public BrocheBlockEntity(BlockPos pos, BlockState state) {
            super(BROCHE_BE.get(), pos, state);
        }
    }

    public static class ViandeBroche extends Block {
        public ViandeBroche(Properties p) {
            super(p);
            registerDefaultState(stateDefinition.any().setValue(BrocheBlock.POULET, false));
        }

        @Override
        protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> b) {
            b.add(BrocheBlock.POULET);
        }
    }

    // ================================================================ le grill : une plaque rougeoyante derrière la broche

    public static class GrillBlock extends HorizontalDirectionalBlock {
        public static final MapCodec<GrillBlock> CODEC = simpleCodec(GrillBlock::new);
        private static final VoxelShape NORD = Block.box(1, 0, 0, 15, 16, 4);
        private static final VoxelShape SUD = Block.box(1, 0, 12, 15, 16, 16);
        private static final VoxelShape OUEST = Block.box(0, 0, 1, 4, 16, 15);
        private static final VoxelShape EST = Block.box(12, 0, 1, 16, 16, 15);

        public GrillBlock(Properties p) {
            super(p);
            registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
        }

        @Override
        protected MapCodec<? extends HorizontalDirectionalBlock> codec() {
            return CODEC;
        }

        @Override
        protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> b) {
            b.add(FACING);
        }

        /** La plaque rougeoie vers celui qui la pose (la broche va devant). */
        @Override
        public BlockState getStateForPlacement(BlockPlaceContext ctx) {
            return defaultBlockState().setValue(FACING, ctx.getHorizontalDirection().getOpposite());
        }

        @Override
        protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
            return switch (state.getValue(FACING)) {
                case SOUTH -> SUD;
                case WEST -> OUEST;
                case EAST -> EST;
                default -> NORD;
            };
        }
    }
}
