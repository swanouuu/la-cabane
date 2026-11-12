package fr.cabane.arene;

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

/**
 * Les boutons de l'arène (le même dessin que le bouton rouge du labo, la couleur change) : côté bleu, côté rouge et
 * gradins dans la salle d'entrée ; le rouge de la salle ramène là d'où l'on venait ; celui des bases et de la loge ramène
 * à la salle d'entrée.
 */
public class BoutonArene extends Block {
    public enum Role {
        BLEU, ROUGE, GRADINS, SORTIE, SALLE
    }

    private static final VoxelShape FORME = Shapes.or(Block.box(3, 0, 3, 13, 1, 13), Block.box(5, 1, 5, 11, 11, 11),
            Block.box(2, 11, 2, 14, 13, 14), Block.box(4.5, 13, 4.5, 11.5, 15.5, 11.5));

    private final Role role;

    public BoutonArene(Role role, Properties properties) {
        super(properties);
        this.role = role;
    }

    @Override
    protected MapCodec<? extends Block> codec() {
        return simpleCodec(p -> new BoutonArene(role, p));
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return FORME;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player joueur, BlockHitResult hit) {
        level.playSound(joueur, pos, SoundEvents.STONE_BUTTON_CLICK_ON, SoundSource.BLOCKS, 1F, role == Role.SORTIE || role == Role.SALLE ? 0.5F : 1F);
        if (!level.isClientSide && joueur instanceof ServerPlayer j) {
            Arene.bouton(j, role);
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }
}
