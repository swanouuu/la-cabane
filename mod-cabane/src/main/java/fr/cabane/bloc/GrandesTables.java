package fr.cabane.bloc;

import com.mojang.serialization.MapCodec;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

/**
 * Les grandes tables du casino occupent plusieurs cases : la roulette 2 x 2, le blackjack 3 x 2, la roue
 * de la fortune 5 x 5 (debout). Le bloc posé est le « principal » (devant, à gauche ou au milieu) ; les
 * autres cases sont des parties invisibles qui renvoient vers lui. Tout est dessiné par le rendu 3D.
 */
public final class GrandesTables {

    /** Les cases en plus du principal : {vers la droite, vers le haut, vers l'arrière}, vues par le joueur qui pose. */
    public static List<int[]> cases(BlocInteractif.Ouverture jeu) {
        List<int[]> l = new ArrayList<>();
        switch (jeu) {
            case ROULETTE -> {
                l.add(new int[] {1, 0, 0});
                l.add(new int[] {0, 0, 1});
                l.add(new int[] {1, 0, 1});
            }
            case BLACKJACK -> {
                for (int d = -1; d <= 1; d++) {
                    for (int a = 0; a <= 1; a++) {
                        if (d != 0 || a != 0) {
                            l.add(new int[] {d, 0, a});
                        }
                    }
                }
            }
            case MACHINE_A_SOUS -> l.add(new int[] {0, 1, 0});
            case DES -> l.add(new int[] {1, 0, 0});
            case ROUE -> {
                for (int d = -2; d <= 2; d++) {
                    for (int h = 0; h <= 4; h++) {
                        if (d != 0 || h != 0) {
                            l.add(new int[] {d, h, 0});
                        }
                    }
                }
            }
            default -> {
            }
        }
        return l;
    }

    /** Tous les jeux du casino sont dessinés en 3D (la plupart prennent plusieurs cases). */
    public static boolean grande(BlocInteractif.Ouverture jeu) {
        return jeu.casino();
    }

    /** Les positions des parties d'une table posée en {@code pos}, face au joueur vers {@code face}. */
    public static List<BlockPos> parties(BlockPos pos, Direction face, BlocInteractif.Ouverture jeu) {
        Direction droite = face.getCounterClockWise();
        Direction arriere = face.getOpposite();
        List<BlockPos> l = new ArrayList<>();
        for (int[] c : cases(jeu)) {
            l.add(pos.relative(droite, c[0]).above(c[1]).relative(arriere, c[2]));
        }
        return l;
    }

    public static List<BlockPos> parties(BlockPos pos, BlockState etat) {
        if (etat.getBlock() instanceof fr.cabane.drogue.Labos.PosteBlock) {
            return fr.cabane.drogue.Labos.PosteBlock.parties(pos, etat);
        }
        if (!(etat.getBlock() instanceof BlocInteractif bloc) || !grande(bloc.ouverture())) {
            return List.of();
        }
        return parties(pos, etat.getValue(HorizontalDirectionalBlock.FACING), bloc.ouverture());
    }

    /** La table dont cette case fait partie (cherchée autour), ou null. */
    public static BlockPos principal(BlockGetter level, BlockPos partie) {
        for (BlockPos p : BlockPos.betweenClosed(partie.offset(-2, -4, -2), partie.offset(2, 0, 2))) {
            BlockState etat = level.getBlockState(p);
            if ((etat.getBlock() instanceof BlocInteractif || etat.getBlock() instanceof fr.cabane.drogue.Labos.PosteBlock) && parties(p, etat).contains(partie)) {
                return p.immutable();
            }
        }
        return null;
    }

    /** Une case de grande table : invisible, solide, elle renvoie clics et casse vers la table. */
    public static class Partie extends Block {
        public static final MapCodec<Partie> CODEC = simpleCodec(Partie::new);

        public Partie(Properties properties) {
            super(properties);
        }

        @Override
        protected MapCodec<? extends Block> codec() {
            return CODEC;
        }

        @Override
        protected RenderShape getRenderShape(BlockState state) {
            return RenderShape.INVISIBLE;
        }

        @Override
        protected boolean propagatesSkylightDown(BlockState state, BlockGetter level, BlockPos pos) {
            return true;
        }

        @Override
        protected float getShadeBrightness(BlockState state, BlockGetter level, BlockPos pos) {
            return 1F;
        }

        @Override
        protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player joueur, BlockHitResult hit) {
            BlockPos principal = principal(level, pos);
            if (principal == null) {
                return InteractionResult.PASS;
            }
            return level.getBlockState(principal).useWithoutItem(level, joueur, hit.withPosition(principal));
        }

        @Override
        protected net.minecraft.world.ItemInteractionResult useItemOn(ItemStack tenu, BlockState state, Level level, BlockPos pos, Player joueur,
                net.minecraft.world.InteractionHand main, BlockHitResult hit) {
            BlockPos principal = principal(level, pos);
            if (principal == null) {
                return net.minecraft.world.ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
            }
            return level.getBlockState(principal).useItemOn(tenu, level, joueur, main, hit.withPosition(principal));
        }

        @Override
        public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player joueur) {
            BlockPos principal = principal(level, pos);
            if (principal != null && !level.isClientSide) {
                level.destroyBlock(principal, !joueur.isCreative(), joueur);
            }
            return super.playerWillDestroy(level, pos, state, joueur);
        }

        @Override
        public ItemStack getCloneItemStack(LevelReader level, BlockPos pos, BlockState state) {
            BlockPos principal = principal(level, pos);
            return principal == null ? ItemStack.EMPTY : new ItemStack(level.getBlockState(principal).getBlock());
        }
    }

    private GrandesTables() {
    }
}
