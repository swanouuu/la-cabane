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
        BOUTIQUE(false), CONTRATS(false), TERRAIN(false),
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
        if (!level.isClientSide) {
            for (BlockPos p : GrandesTables.parties(pos, state)) {
                level.setBlock(p, fr.cabane.CabaneBlocks.PARTIE_TABLE.get().defaultBlockState(), 3);
            }
        }
        // une borne de terrain posée à la main reste « à vendre » : c'est l'admin qui fixe le prix
        if (!level.isClientSide && ouverture != Ouverture.TERRAIN && placer instanceof ServerPlayer joueur && level.getBlockEntity(pos) instanceof InteractifBlockEntity be) {
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
        BlockState etat = defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
        // une grande table a besoin de toute sa place
        for (BlockPos p : GrandesTables.parties(context.getClickedPos(), etat)) {
            if (!context.getLevel().getBlockState(p).canBeReplaced(context) || !context.getLevel().isInWorldBounds(p)) {
                if (context.getPlayer() != null && context.getLevel().isClientSide) {
                    context.getPlayer().displayClientMessage(net.minecraft.network.chat.Component.literal("Pas assez de place pour cette table.")
                            .withStyle(net.minecraft.ChatFormatting.RED), true);
                }
                return null;
            }
        }
        return etat;
    }

    @Override
    protected net.minecraft.world.level.block.RenderShape getRenderShape(BlockState state) {
        // les grandes tables sont entièrement dessinées par leur rendu 3D
        return GrandesTables.grande(ouverture) ? net.minecraft.world.level.block.RenderShape.ENTITYBLOCK_ANIMATED : super.getRenderShape(state);
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState nouvel, boolean deplace) {
        if (!state.is(nouvel.getBlock())) {
            for (BlockPos p : GrandesTables.parties(pos, state)) {
                if (level.getBlockState(p).is(fr.cabane.CabaneBlocks.PARTIE_TABLE.get())) {
                    level.setBlock(p, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 35);
                }
            }
        }
        super.onRemove(state, level, pos, nouvel, deplace);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (level.isClientSide) {
            if (ouverture.cote_client) {
                // le gérant, accroupi, gère la caisse ; sinon on joue
                if (player.isShiftKeyDown() && level.getBlockEntity(pos) instanceof InteractifBlockEntity be
                        && (be.estProprietaire(player.getUUID()) || player.hasPermissions(2))) {
                    fr.cabane.client.casino.CaisseScreen.ouvrir(pos);
                } else {
                    ClientCasino.ouvrir(ouverture, pos);
                }
            }
        } else if (player instanceof ServerPlayer joueur) {
            switch (ouverture) {
                case BOUTIQUE -> Boutique.ouvrir(joueur);
                // clic droit : livrer ce qu'on a ; accroupi : la liste détaillée
                case TERRAIN -> Terrains.cliquer(joueur, pos);
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
