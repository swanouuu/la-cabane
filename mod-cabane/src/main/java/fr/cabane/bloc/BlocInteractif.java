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
public class BlocInteractif extends HorizontalDirectionalBlock implements net.minecraft.world.level.block.EntityBlock {

    public enum Ouverture {
        BOUTIQUE(false), CONTRATS(false),
        MACHINE_A_SOUS(true), ROULETTE(true), BLACKJACK(true), DES(true), ROUE(true), PILE_OU_FACE(true);

        final boolean cote_client;

        Ouverture(boolean coteClient) {
            this.cote_client = coteClient;
        }

        public boolean casino() {
            return cote_client;
        }
    }

    public Ouverture ouverture() {
        return ouverture;
    }

    @Override
    public net.minecraft.world.level.block.entity.BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new InteractifBlockEntity(pos, state);
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, net.minecraft.world.entity.LivingEntity placer, net.minecraft.world.item.ItemStack stack) {
        if (!level.isClientSide && placer instanceof ServerPlayer joueur && level.getBlockEntity(pos) instanceof InteractifBlockEntity be) {
            be.definirProprietaire(joueur.getUUID(), joueur.getGameProfile().getName());
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
                // le gérant, accroupi, gère la caisse ; sinon on joue
                if (player.isShiftKeyDown() && level.getBlockEntity(pos) instanceof InteractifBlockEntity be && be.estProprietaire(player.getUUID())) {
                    fr.cabane.client.casino.CaisseScreen.ouvrir(pos);
                } else {
                    ClientCasino.ouvrir(ouverture, pos);
                }
            }
        } else if (player instanceof ServerPlayer joueur) {
            switch (ouverture) {
                case BOUTIQUE -> Boutique.ouvrir(joueur);
                // clic droit : livrer ce qu'on a ; accroupi : la liste détaillée
                case CONTRATS -> {
                    if (joueur.isShiftKeyDown()) {
                        Contrats.ouvrir(joueur);
                    } else {
                        Contrats.livrerTout(joueur);
                    }
                }
                default -> fr.cabane.economie.Economie.sync(joueur);
            }
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }
}
