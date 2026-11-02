package fr.cabane.labo;

import com.mojang.serialization.MapCodec;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/** Les blocs du Laboratoire : le terminal qui y emmène, le bouton rouge qui en ramène, la table d'expériences. */
public final class LaboBlocs {

    /** Un clic droit, et l'on part pour le Laboratoire. L'écran s'oriente vers celui qui le pose. */
    public static class Terminal extends HorizontalDirectionalBlock {
        public static final MapCodec<Terminal> CODEC = simpleCodec(Terminal::new);

        public Terminal(Properties properties) {
            super(properties);
            registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
        }

        @Override
        protected MapCodec<? extends HorizontalDirectionalBlock> codec() {
            return CODEC;
        }

        @Override
        protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
            builder.add(FACING);
        }

        @Override
        public BlockState getStateForPlacement(BlockPlaceContext context) {
            return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
        }

        @Override
        protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player joueur, BlockHitResult hit) {
            if (!level.isClientSide && joueur instanceof ServerPlayer j) {
                if (Labo.dedans(j)) {
                    j.displayClientMessage(Component.literal("L'écran reste noir. Ici, seul le bouton rouge ramène.").withStyle(ChatFormatting.DARK_GRAY), true);
                } else {
                    level.playSound(null, pos, SoundEvents.BEACON_ACTIVATE, SoundSource.BLOCKS, 1F, 0.6F);
                    Labo.entrer(j);
                }
            }
            return InteractionResult.sidedSuccess(level.isClientSide);
        }

        @Override
        public void animateTick(BlockState state, Level level, BlockPos pos, RandomSource random) {
            if (random.nextInt(3) == 0) {
                Direction face = state.getValue(FACING);
                double x = pos.getX() + 0.5 + face.getStepX() * 0.55 + (random.nextDouble() - 0.5) * 0.6;
                double z = pos.getZ() + 0.5 + face.getStepZ() * 0.55 + (random.nextDouble() - 0.5) * 0.6;
                level.addParticle(ParticleTypes.ELECTRIC_SPARK, x, pos.getY() + 0.4 + random.nextDouble() * 0.5, z, 0, 0, 0);
            }
        }
    }

    /** Le gros bouton rouge sur son pupitre : il ramène pile là où l'on était dans le vrai monde. */
    public static class BoutonRetour extends Block {
        public static final MapCodec<BoutonRetour> CODEC = simpleCodec(BoutonRetour::new);
        private static final VoxelShape FORME = Shapes.or(Block.box(3, 0, 3, 13, 1, 13), Block.box(5, 1, 5, 11, 11, 11),
                Block.box(2, 11, 2, 14, 13, 14), Block.box(4.5, 13, 4.5, 11.5, 15.5, 11.5));

        public BoutonRetour(Properties properties) {
            super(properties);
        }

        @Override
        protected MapCodec<? extends Block> codec() {
            return CODEC;
        }

        @Override
        protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
            return FORME;
        }

        @Override
        protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player joueur, BlockHitResult hit) {
            level.playSound(joueur, pos, SoundEvents.STONE_BUTTON_CLICK_ON, SoundSource.BLOCKS, 1F, 0.5F);
            if (!level.isClientSide && joueur instanceof ServerPlayer j) {
                Labo.sortir(j);
            }
            return InteractionResult.sidedSuccess(level.isClientSide);
        }
    }

    /**
     * Un tapis roulant à hauteur de comptoir : tout ce qui est posé dessus (objets, joueurs, animaux) avance vers FACING,
     * en restant au milieu. Accroupi, on ne se fait pas emporter. Celui du WOOHP apporte les gadgets de Jerry.
     */
    public static class TapisRoulant extends HorizontalDirectionalBlock {
        public static final MapCodec<TapisRoulant> CODEC = simpleCodec(TapisRoulant::new);
        /** La vitesse donnée à chaque tick (le frottement en mange à peu près la moitié). */
        private static final double VITESSE = 0.2;

        public TapisRoulant(Properties properties) {
            super(properties);
            registerDefaultState(stateDefinition.any().setValue(FACING, Direction.EAST));
        }

        @Override
        protected MapCodec<? extends HorizontalDirectionalBlock> codec() {
            return CODEC;
        }

        @Override
        protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
            builder.add(FACING);
        }

        @Override
        public BlockState getStateForPlacement(BlockPlaceContext context) {
            return defaultBlockState().setValue(FACING, context.getHorizontalDirection());
        }

        @Override
        public void stepOn(Level level, BlockPos pos, BlockState state, net.minecraft.world.entity.Entity entite) {
            if (entite.isShiftKeyDown()) {
                return;
            }
            Direction sens = state.getValue(FACING);
            // on ramène doucement au milieu du tapis, pour que rien ne tombe sur le côté
            double milieu = sens.getAxis() == Direction.Axis.X ? pos.getZ() + 0.5 - entite.getZ() : pos.getX() + 0.5 - entite.getX();
            double vx = sens.getStepX() * VITESSE + (sens.getAxis() == Direction.Axis.Z ? milieu * 0.15 : 0);
            double vz = sens.getStepZ() * VITESSE + (sens.getAxis() == Direction.Axis.X ? milieu * 0.15 : 0);
            var v = entite.getDeltaMovement();
            if (entite instanceof Player) {
                // le joueur garde un peu de son élan (il peut marcher à contre-courant)
                entite.setDeltaMovement(v.x * 0.6 + vx * 0.5, v.y, v.z * 0.6 + vz * 0.5);
            } else {
                entite.setDeltaMovement(vx, v.y, vz);
            }
        }
    }

    /**
     * La table d'expériences : deux blocs comme un lit (la tête du côté de FACING). On s'y allonge d'un clic droit,
     * les sangles se referment, et l'expérience commence (voir {@link Experiences}). Elle passe pour un lit aux yeux
     * du jeu, ce qui permet aussi d'y coucher les corps de la morgue.
     */
    public static class TableExperiences extends HorizontalDirectionalBlock {
        public static final MapCodec<TableExperiences> CODEC = simpleCodec(TableExperiences::new);
        public static final EnumProperty<BedPart> PART = BedBlock.PART;
        private static final VoxelShape FORME = Shapes.or(Block.box(3, 0, 3, 13, 1, 13), Block.box(6, 1, 6, 10, 8, 10), Block.box(0, 8, 0, 16, 11, 16));

        public TableExperiences(Properties properties) {
            super(properties);
            registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(PART, BedPart.FOOT));
        }

        @Override
        protected MapCodec<? extends HorizontalDirectionalBlock> codec() {
            return CODEC;
        }

        @Override
        protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
            builder.add(FACING, PART);
        }

        @Override
        protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
            return FORME;
        }

        /** La tête de la table, quelle que soit la moitié cliquée. */
        public static BlockPos tete(BlockState state, BlockPos pos) {
            return state.getValue(PART) == BedPart.HEAD ? pos : pos.relative(state.getValue(FACING));
        }

        @Override
        public BlockState getStateForPlacement(BlockPlaceContext context) {
            Direction sens = context.getHorizontalDirection();
            BlockPos tete = context.getClickedPos().relative(sens);
            Level level = context.getLevel();
            return level.getBlockState(tete).canBeReplaced(context) && level.getWorldBorder().isWithinBounds(tete)
                    ? defaultBlockState().setValue(FACING, sens) : null;
        }

        @Override
        public void setPlacedBy(Level level, BlockPos pos, BlockState state, LivingEntity placer, ItemStack stack) {
            if (!level.isClientSide) {
                level.setBlock(pos.relative(state.getValue(FACING)), state.setValue(PART, BedPart.HEAD), 3);
            }
        }

        @Override
        protected BlockState updateShape(BlockState state, Direction cote, BlockState voisin, LevelAccessor level, BlockPos pos, BlockPos voisinPos) {
            Direction versAutre = state.getValue(PART) == BedPart.FOOT ? state.getValue(FACING) : state.getValue(FACING).getOpposite();
            if (cote == versAutre) {
                return voisin.is(this) && voisin.getValue(PART) != state.getValue(PART) ? state : Blocks.AIR.defaultBlockState();
            }
            return super.updateShape(state, cote, voisin, level, pos, voisinPos);
        }

        @Override
        protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player joueur, BlockHitResult hit) {
            if (!level.isClientSide && joueur instanceof ServerPlayer j) {
                Experiences.allonger(j, (ServerLevel) level, tete(state, pos));
            }
            return InteractionResult.sidedSuccess(level.isClientSide);
        }

        @Override
        public boolean isBed(BlockState state, BlockGetter level, BlockPos pos, LivingEntity sleeper) {
            return true;
        }

        @Override
        public Direction getBedDirection(BlockState state, LevelReader level, BlockPos pos) {
            return state.getValue(FACING);
        }

        @Override
        public void setBedOccupied(BlockState state, Level level, BlockPos pos, LivingEntity sleeper, boolean occupied) {
            // pas d'état « occupé » : on garde la table telle quelle
        }
    }

    private LaboBlocs() {
    }
}
