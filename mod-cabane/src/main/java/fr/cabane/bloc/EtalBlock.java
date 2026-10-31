package fr.cabane.bloc;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.phys.BlockHitResult;

/**
 * L'étal d'un joueur. Le propriétaire le remplit (clic droit) et fixe ses prix ;
 * les autres achètent (clic droit, puis confirmer). Seul le propriétaire peut le casser.
 */
public class EtalBlock extends BaseEntityBlock {
    public static final MapCodec<EtalBlock> CODEC = simpleCodec(EtalBlock::new);

    public EtalBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(HorizontalDirectionalBlock.FACING, Direction.NORTH));
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(HorizontalDirectionalBlock.FACING);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(HorizontalDirectionalBlock.FACING, context.getHorizontalDirection().getOpposite());
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new EtalBlockEntity(pos, state);
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (!level.isClientSide && placer instanceof Player joueur && level.getBlockEntity(pos) instanceof EtalBlockEntity etal) {
            etal.definirProprietaire(joueur.getUUID(), joueur.getGameProfile().getName());
        }
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (level.isClientSide) {
            return InteractionResult.SUCCESS;
        }
        if (!(player instanceof ServerPlayer joueur) || !(level.getBlockEntity(pos) instanceof EtalBlockEntity etal)) {
            return InteractionResult.PASS;
        }
        if (etal.proprietaire() == null) {
            etal.definirProprietaire(joueur.getUUID(), joueur.getGameProfile().getName());
        }
        if (etal.estProprietaire(joueur.getUUID()) && !joueur.isShiftKeyDown()) {
            joueur.openMenu(new SimpleMenuProvider((id, inv, p) -> new EtalMenu(id, inv, etal.stock(), pos, etal.tousLesPrix()),
                    Component.literal("Mon étal")), buf -> {
                buf.writeBlockPos(pos);
                buf.writeLongArray(etal.tousLesPrix());
            });
        } else {
            Etals.montrer(joueur, etal);
        }
        return InteractionResult.CONSUME;
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        if (!state.is(newState.getBlock()) && level.getBlockEntity(pos) instanceof EtalBlockEntity etal) {
            for (int i = 0; i < etal.stock().getSlots(); i++) {
                Block.popResource(level, pos, etal.stock().getStackInSlot(i));
            }
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }
}
