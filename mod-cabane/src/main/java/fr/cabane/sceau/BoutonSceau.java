package fr.cabane.sceau;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/** Le bouton rouge de l'arrivée de la salle du sceau (le même dessin que celui du labo) : il ramène pile là d'où l'on venait. */
public class BoutonSceau extends Block {
    public static final MapCodec<BoutonSceau> CODEC = simpleCodec(BoutonSceau::new);
    private static final VoxelShape FORME = Shapes.or(Block.box(3, 0, 3, 13, 1, 13), Block.box(5, 1, 5, 11, 11, 11),
            Block.box(2, 11, 2, 14, 13, 14), Block.box(4.5, 13, 4.5, 11.5, 15.5, 11.5));

    public BoutonSceau(Properties properties) {
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
            Sceau.sortir(j);
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }
}
