package fr.cabane.backrooms;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.BlockHitResult;

/** Les blocs qui font quelque chose : le casier (on s'y cache) et la fausse porte EXIT (un piège), sur deux blocs de haut. */
public final class BlocsBackrooms {
    public static final EnumProperty<DoubleBlockHalf> MOITIE = BlockStateProperties.DOUBLE_BLOCK_HALF;

    /** Un bloc de deux de haut, tourné vers la pièce (FACING : le côté de la porte). */
    public abstract static class Double extends HorizontalDirectionalBlock {
        protected Double(Properties properties) {
            super(properties);
            registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(MOITIE, DoubleBlockHalf.LOWER));
        }

        @Override
        protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
            builder.add(FACING, MOITIE);
        }

        /** Le bas du bloc double. */
        public static BlockPos bas(BlockState state, BlockPos pos) {
            return state.getValue(MOITIE) == DoubleBlockHalf.UPPER ? pos.below() : pos;
        }

        @Override
        protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player joueur, BlockHitResult hit) {
            if (!level.isClientSide && joueur instanceof ServerPlayer j) {
                utiliser(j, bas(state, pos), state.getValue(FACING));
            }
            return InteractionResult.sidedSuccess(level.isClientSide);
        }

        protected abstract void utiliser(ServerPlayer joueur, BlockPos bas, Direction devant);
    }

    /** Le casier : clic droit, et l'on s'y cache. */
    public static class Casier extends Double {
        public static final MapCodec<Casier> CODEC = simpleCodec(Casier::new);

        public Casier(Properties properties) {
            super(properties);
        }

        @Override
        protected MapCodec<? extends HorizontalDirectionalBlock> codec() {
            return CODEC;
        }

        @Override
        protected void utiliser(ServerPlayer joueur, BlockPos bas, Direction devant) {
            Backrooms.cacher(joueur, bas, devant);
        }
    }

    /** La porte EXIT : on l'ouvre, et l'on se retrouve ailleurs dans le labyrinthe. */
    public static class Porte extends Double {
        public static final MapCodec<Porte> CODEC = simpleCodec(Porte::new);

        public Porte(Properties properties) {
            super(properties);
        }

        @Override
        protected MapCodec<? extends HorizontalDirectionalBlock> codec() {
            return CODEC;
        }

        @Override
        protected void utiliser(ServerPlayer joueur, BlockPos bas, Direction devant) {
            Backrooms.piege(joueur, bas);
        }
    }

    /** Le panneau EXIT au-dessus de la porte, côté pièce. */
    public static class Panneau extends HorizontalDirectionalBlock {
        public static final MapCodec<Panneau> CODEC = simpleCodec(Panneau::new);

        public Panneau(Properties properties) {
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
    }

    private BlocsBackrooms() {
    }
}
