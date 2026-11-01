package fr.cabane.drogue;

import fr.cabane.CabaneBlocks;
import fr.cabane.CabaneItems;
import java.util.List;
import java.util.function.Supplier;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;

/**
 * Les labos clandestins : deux postes par produit, chacun en 3D (outils/marche-noir.mjs), où l'on voit la marchandise
 * travailler sur la table. Le séchoir puis la presse pour la weed et le hash ; le bac de macération puis la table de
 * raffinage pour la coke ; le réacteur puis la table de découpe pour la meth. On pose les ingrédients d'un clic droit,
 * on attend, on reprend le résultat d'un clic droit. Certains postes peuvent exploser.
 */
public final class Labos {
    // ---- les étapes intermédiaires
    public static final DeferredItem<Item> WEED_SECHEE = CabaneItems.ITEMS.register("weed_sechee", () -> new Item(new Item.Properties()));
    public static final DeferredItem<Item> PATE_COCA = CabaneItems.ITEMS.register("pate_coca", () -> new Item(new Item.Properties()));
    public static final DeferredItem<Item> PLAQUE_METH = CabaneItems.ITEMS.register("plaque_meth", () -> new Item(new Item.Properties()));

    private record Ingredient(Supplier<? extends Item> item, int nombre) {
        String texte() {
            return nombre + " " + new ItemStack(item.get()).getHoverName().getString();
        }
    }

    /** Un poste : ses ingrédients (le premier est celui qu'on tient en main), ce qui sort, le temps, le risque d'explosion. */
    public enum Poste {
        SECHOIR("sechoir", "Séchoir à weed", "séchage", List.of(new Ingredient(MarcheNoir.TETE_WEED, 4)), WEED_SECHEE, 4, 30, 0F),
        PRESSE("presse_hash", "Presse à hash", "pressage", List.of(new Ingredient(WEED_SECHEE, 4)), MarcheNoir.HASH, 2, 20, 0F),
        BAC("bac_maceration", "Bac de macération", "macération",
                List.of(new Ingredient(MarcheNoir.FEUILLE_COCA, 6), new Ingredient(() -> Items.SUGAR, 1)), PATE_COCA, 1, 40, 0F),
        RAFFINAGE("table_raffinage", "Table de raffinage", "raffinage",
                List.of(new Ingredient(PATE_COCA, 1), new Ingredient(() -> Items.GUNPOWDER, 1)), MarcheNoir.COCAINE, 3, 30, 0.05F),
        REACTEUR("reacteur", "Réacteur de cuisson", "cuisson",
                List.of(new Ingredient(() -> Items.REDSTONE, 2), new Ingredient(() -> Items.SUGAR, 2), new Ingredient(() -> Items.BLAZE_POWDER, 1)),
                PLAQUE_METH, 1, 60, 0.15F),
        DECOUPE("table_decoupe", "Table de découpe", "découpe", List.of(new Ingredient(PLAQUE_METH, 1)), MarcheNoir.METH, 4, 15, 0F);

        public final String id;
        public final String nom;
        final String action;
        final List<Ingredient> ingredients;
        final Supplier<? extends Item> resultat;
        final int nombre;
        final int duree;
        final float explosion;

        Poste(String id, String nom, String action, List<Ingredient> ingredients, Supplier<? extends Item> resultat, int nombre, int secondes, float explosion) {
            this.id = id;
            this.nom = nom;
            this.action = action;
            this.ingredients = ingredients;
            this.resultat = resultat;
            this.nombre = nombre;
            this.duree = secondes * 20;
            this.explosion = explosion;
        }

        String recette() {
            return String.join(" + ", ingredients.stream().map(Ingredient::texte).toList()) + " → " + nombre + " "
                    + new ItemStack(resultat.get()).getHoverName().getString() + " (" + duree / 20 + " s"
                    + (explosion > 0 ? ", risque d'explosion " + Math.round(explosion * 100) + " %" : "") + ")";
        }

        /** Ce qui sort du poste pendant qu'il travaille. */
        ParticleOptions fumee() {
            return switch (this) {
                case SECHOIR, PRESSE -> ParticleTypes.WHITE_ASH;
                case BAC -> ParticleTypes.BUBBLE_POP;
                case RAFFINAGE -> ParticleTypes.WHITE_ASH;
                case REACTEUR -> ParticleTypes.SOUL_FIRE_FLAME;
                case DECOUPE -> ParticleTypes.ELECTRIC_SPARK;
            };
        }

        SoundEvent bruit() {
            return switch (this) {
                case SECHOIR -> SoundEvents.AZALEA_LEAVES_HIT;
                case PRESSE -> SoundEvents.PISTON_EXTEND;
                case BAC -> SoundEvents.BUBBLE_COLUMN_BUBBLE_POP;
                case RAFFINAGE -> SoundEvents.SAND_HIT;
                case REACTEUR -> SoundEvents.BREWING_STAND_BREW;
                case DECOUPE -> SoundEvents.AMETHYST_BLOCK_HIT;
            };
        }
    }

    public static final List<DeferredBlock<PosteBlock>> BLOCS = java.util.Arrays.stream(Poste.values())
            .map(p -> CabaneBlocks.BLOCKS.registerBlock(p.id, props -> new PosteBlock(props, p),
                    BlockBehaviour.Properties.of().strength(2.5F).sound(p == Poste.SECHOIR || p == Poste.BAC ? SoundType.WOOD : SoundType.METAL)
                            .noOcclusion().lightLevel(s -> s.getValue(PosteBlock.ACTIF) ? 7 : 0)))
            .toList();
    public static final List<DeferredItem<net.minecraft.world.item.BlockItem>> OBJETS = BLOCS.stream()
            .map(b -> CabaneItems.ITEMS.registerSimpleBlockItem(b)).toList();

    @SuppressWarnings("DataFlowIssue")
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<PosteBlockEntity>> POSTE_BE = CabaneBlocks.BLOCK_ENTITIES.register("poste_labo",
            () -> BlockEntityType.Builder.of(PosteBlockEntity::new, BLOCS.stream().map(DeferredBlock::get).toArray(Block[]::new)).build(null));

    // ================================================================ le bloc

    public static class PosteBlock extends HorizontalDirectionalBlock implements EntityBlock {
        public static final BooleanProperty ACTIF = BooleanProperty.create("actif");
        private static final VoxelShape FORME = Block.box(0, 0, 0, 16, 15, 16);
        public final Poste poste;

        PosteBlock(Properties properties, Poste poste) {
            super(properties);
            this.poste = poste;
            registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(ACTIF, false));
        }

        @Override
        protected com.mojang.serialization.MapCodec<? extends HorizontalDirectionalBlock> codec() {
            return simpleCodec(p -> new PosteBlock(p, poste));
        }

        @Override
        protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
            builder.add(FACING, ACTIF);
        }

        @Override
        public BlockState getStateForPlacement(BlockPlaceContext context) {
            return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
        }

        @Override
        protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
            return FORME;
        }

        @Override
        public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
            return new PosteBlockEntity(pos, state);
        }

        @Override
        public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
            return level.isClientSide || type != POSTE_BE.get() ? null : (l, p, s, be) -> ((PosteBlockEntity) be).tick((ServerLevel) l, p, s);
        }

        @Override
        protected ItemInteractionResult useItemOn(ItemStack tenu, BlockState state, Level level, BlockPos pos, Player joueur, InteractionHand main, BlockHitResult hit) {
            if (!(level.getBlockEntity(pos) instanceof PosteBlockEntity poste)) {
                return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
            }
            if (!poste.sortie.isEmpty() || poste.restant > 0 || !tenu.is(this.poste.ingredients.get(0).item().get())) {
                return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
            }
            if (!level.isClientSide) {
                poste.lancer(joueur);
            }
            return ItemInteractionResult.sidedSuccess(level.isClientSide);
        }

        @Override
        protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player joueur, BlockHitResult hit) {
            if (!level.isClientSide && level.getBlockEntity(pos) instanceof PosteBlockEntity poste) {
                if (!poste.sortie.isEmpty()) {
                    poste.recuperer(joueur);
                } else if (poste.restant > 0) {
                    joueur.displayClientMessage(Component.literal(capitale(this.poste.action) + " en cours… " + poste.restant / 20 + " s").withStyle(ChatFormatting.GOLD), true);
                } else {
                    joueur.displayClientMessage(Component.literal(this.poste.nom + " : " + this.poste.recette()).withStyle(ChatFormatting.GOLD), false);
                    joueur.displayClientMessage(Component.literal("Clic droit en tenant " + this.poste.ingredients.get(0).texte().replaceFirst("^\\d+ ", "")
                            + " pour lancer.").withStyle(ChatFormatting.GRAY), false);
                }
            }
            return InteractionResult.sidedSuccess(level.isClientSide);
        }

        @Override
        public void animateTick(BlockState state, Level level, BlockPos pos, RandomSource r) {
            if (!state.getValue(ACTIF)) {
                return;
            }
            double x = pos.getX() + 0.5, y = pos.getY() + 1, z = pos.getZ() + 0.5;
            level.addParticle(poste.fumee(), x + (r.nextDouble() - 0.5) * 0.6, y, z + (r.nextDouble() - 0.5) * 0.6, 0, 0.03, 0);
            if (r.nextInt(3) == 0) {
                level.addParticle(ParticleTypes.SMOKE, x + (r.nextDouble() - 0.5) * 0.4, y + 0.1, z + (r.nextDouble() - 0.5) * 0.4, 0, 0.04, 0);
            }
            if (r.nextInt(10) == 0) {
                level.playLocalSound(x, y, z, poste.bruit(), SoundSource.BLOCKS, 0.5F, 0.8F + r.nextFloat() * 0.4F, false);
            }
        }

        private static String capitale(String s) {
            return Character.toUpperCase(s.charAt(0)) + s.substring(1);
        }
    }

    // ================================================================ la mémoire du poste : ce qui travaille, ce qui est prêt

    public static class PosteBlockEntity extends BlockEntity {
        /** Ce qui est posé sur la table pendant le travail (pour le dessin). */
        ItemStack enCours = ItemStack.EMPTY;
        /** Le résultat, qui attend qu'on le prenne. */
        ItemStack sortie = ItemStack.EMPTY;
        int restant;
        int duree;

        public PosteBlockEntity(BlockPos pos, BlockState state) {
            super(POSTE_BE.get(), pos, state);
        }

        public Poste poste() {
            return ((PosteBlock) getBlockState().getBlock()).poste;
        }

        public ItemStack enCours() {
            return enCours;
        }

        public ItemStack sortie() {
            return sortie;
        }

        /** De 0 (commence) à 1 (fini). */
        public float avancement() {
            return duree <= 0 ? 0 : 1F - (float) restant / duree;
        }

        void lancer(Player joueur) {
            Poste p = poste();
            if (!joueur.getAbilities().instabuild) {
                for (Ingredient i : p.ingredients) {
                    if (joueur.getInventory().countItem(i.item().get()) < i.nombre()) {
                        joueur.displayClientMessage(Component.literal("Il te faut : " + String.join(" + ", p.ingredients.stream().map(Ingredient::texte).toList()))
                                .withStyle(ChatFormatting.RED), true);
                        return;
                    }
                }
                for (Ingredient i : p.ingredients) {
                    joueur.getInventory().clearOrCountMatchingItems(s -> s.is(i.item().get()), i.nombre(), joueur.inventoryMenu.getCraftSlots());
                }
            }
            enCours = new ItemStack(p.ingredients.get(0).item().get(), p.ingredients.get(0).nombre());
            duree = p.duree;
            restant = p.duree;
            level.setBlock(worldPosition, getBlockState().setValue(PosteBlock.ACTIF, true), Block.UPDATE_ALL);
            level.playSound(null, worldPosition, p.bruit(), SoundSource.BLOCKS, 1F, 0.9F);
            joueur.displayClientMessage(Component.literal(p.nom + " : " + p.action + " lancé(e), " + p.duree / 20 + " s.").withStyle(ChatFormatting.GOLD), true);
            synchroniser();
        }

        void recuperer(Player joueur) {
            if (!joueur.getInventory().add(sortie.copy())) {
                joueur.drop(sortie.copy(), false);
            }
            level.playSound(null, worldPosition, SoundEvents.ITEM_PICKUP, SoundSource.BLOCKS, 0.8F, 1.2F);
            sortie = ItemStack.EMPTY;
            synchroniser();
        }

        void tick(ServerLevel level, BlockPos pos, BlockState state) {
            if (restant <= 0) {
                return;
            }
            restant--;
            if (restant % 20 == 0) {
                synchroniser();
            }
            if (restant > 0) {
                return;
            }
            Poste p = poste();
            enCours = ItemStack.EMPTY;
            level.setBlock(pos, state.setValue(PosteBlock.ACTIF, false), Block.UPDATE_ALL);
            if (level.random.nextFloat() < p.explosion) {
                // la cuisine a mal tourné : tout part en fumée (sans casser les blocs autour), et la marchandise avec
                level.explode(null, pos.getX() + 0.5, pos.getY() + 1, pos.getZ() + 0.5, 2.5F, Level.ExplosionInteraction.NONE);
                level.sendParticles(ParticleTypes.LARGE_SMOKE, pos.getX() + 0.5, pos.getY() + 1, pos.getZ() + 0.5, 40, 1, 1, 1, 0.05);
            } else {
                sortie = new ItemStack(p.resultat.get(), p.nombre);
                level.playSound(null, pos, SoundEvents.NOTE_BLOCK_CHIME.value(), SoundSource.BLOCKS, 0.7F, 1.4F);
            }
            synchroniser();
        }

        private void synchroniser() {
            setChanged();
            if (level != null) {
                level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_CLIENTS);
            }
        }

        @Override
        protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
            super.saveAdditional(tag, registries);
            if (!enCours.isEmpty()) {
                tag.put("enCours", enCours.save(registries));
            }
            if (!sortie.isEmpty()) {
                tag.put("sortie", sortie.save(registries));
            }
            tag.putInt("restant", restant);
            tag.putInt("duree", duree);
        }

        @Override
        protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
            super.loadAdditional(tag, registries);
            enCours = ItemStack.parseOptional(registries, tag.getCompound("enCours"));
            sortie = ItemStack.parseOptional(registries, tag.getCompound("sortie"));
            restant = tag.getInt("restant");
            duree = tag.getInt("duree");
        }

        @Override
        public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
            return saveWithoutMetadata(registries);
        }

        @Override
        public Packet<ClientGamePacketListener> getUpdatePacket() {
            return ClientboundBlockEntityDataPacket.create(this);
        }
    }

    /** Charge la classe (et donc l'enregistrement des postes) au démarrage du mod. */
    static void init() {
    }

    private Labos() {
    }
}
