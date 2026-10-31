package fr.cabane.bloc;

import com.mojang.serialization.MapCodec;
import fr.cabane.boutique.Boutique;
import fr.cabane.boutique.Contrats;
import fr.cabane.client.casino.ClientCasino;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.phys.BlockHitResult;

/** Un bloc qui ouvre un écran au clic droit : boutique, contrats, ou un jeu du casino. */
public class BlocInteractif extends HorizontalDirectionalBlock {

    public enum Ouverture {
        BOUTIQUE(false), CONTRATS(false),
        MACHINE_A_SOUS(true), ROULETTE(true), BLACKJACK(true), DES(true), ROUE(true), PILE_OU_FACE(true);

        final boolean cote_client;

        Ouverture(boolean coteClient) {
            this.cote_client = coteClient;
        }
    }

    private final Ouverture ouverture;

    public BlocInteractif(Properties properties, Ouverture ouverture) {
        super(properties);
        this.ouverture = ouverture;
        registerDefaultState(stateDefinition.any().setValue(FACING, net.minecraft.core.Direction.NORTH));
    }

    @Override
    protected MapCodec<? extends HorizontalDirectionalBlock> codec() {
        return simpleCodec(p -> new BlocInteractif(p, ouverture));
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
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (level.isClientSide) {
            if (ouverture.cote_client) {
                ClientCasino.ouvrir(ouverture);
            }
        } else if (player instanceof ServerPlayer joueur) {
            switch (ouverture) {
                case BOUTIQUE -> Boutique.ouvrir(joueur);
                case CONTRATS -> Contrats.ouvrir(joueur);
                default -> fr.cabane.economie.Economie.sync(joueur);
            }
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }
}
