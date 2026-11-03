package fr.cabane.lutte;

import com.mojang.serialization.MapCodec;
import java.util.Locale;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.EntityCollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Les blocs du ring et de la salle de catch. Le ring se pose avec le kit (voir {@link KitRing}) : le tablier, le tapis
 * (avec le liseré rouge et les lettres CATCH au milieu de chaque côté), les quatre poteaux et les cordes. On passe entre
 * les cordes accroupi ; debout, elles retiennent. Casser n'importe quel bloc du ring (en survie aussi) le range tout entier dans un
 * kit ; celui de la salle ne se casse pas.
 */
public final class RingBlocs {
    public enum Lettre implements StringRepresentable {
        VIDE, C, A, T, H;

        @Override
        public String getSerializedName() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    public enum Couleur implements StringRepresentable {
        ROUGE, BLEU, BLANC, OR, NOIR;

        @Override
        public String getSerializedName() {
            return name().toLowerCase(Locale.ROOT);
        }

        public String nom() {
            return switch (this) {
                case ROUGE -> "rouges";
                case BLEU -> "bleues";
                case BLANC -> "blanches";
                case OR -> "dorées";
                case NOIR -> "noires";
            };
        }
    }

    public static final EnumProperty<Lettre> LETTRE = EnumProperty.create("lettre", Lettre.class);
    public static final EnumProperty<Couleur> COULEUR = EnumProperty.create("couleur", Couleur.class);

    /** Une boîte en pixels, tournée d'un quart de tour par direction (le dessin est fait pour le nord). */
    static VoxelShape boite(Direction d, double x0, double y0, double z0, double x1, double y1, double z1) {
        return switch (d) {
            case EAST -> Block.box(16 - z1, y0, x0, 16 - z0, y1, x1);
            case SOUTH -> Block.box(16 - x1, y0, 16 - z1, 16 - x0, y1, 16 - z0);
            case WEST -> Block.box(z0, y0, 16 - x1, z1, y1, 16 - x0);
            default -> Block.box(x0, y0, z0, x1, y1, z1);
        };
    }

    /** Celui qui est accroupi passe entre les cordes (et celui qui est en pleine prise, comme le 619). */
    static boolean passe(CollisionContext ctx) {
        return ctx instanceof EntityCollisionContext e && e.getEntity() != null
                && ((e.getEntity() instanceof Player p && p.isShiftKeyDown()) || Lutte.PASSE_CORDES.contains(e.getEntity().getUUID()));
    }

    // ------------------------------------------------------------------ le plancher : le tapis et le tablier

    /** Casser un bloc du ring le range tout entier dans un kit (de la couleur de ses cordes). */
    static void ranger(Level level, BlockPos pos, Player joueur) {
        if (level instanceof ServerLevel monde && joueur instanceof ServerPlayer j) {
            Rings.ranger(monde, pos, j);
        }
    }

    /** Le tapis (la toile dessus) ou le tablier. */
    public static class Plancher extends Block {
        public static final MapCodec<Plancher> CODEC = simpleCodec(Plancher::new);

        public Plancher(Properties p) {
            super(p);
            registerDefaultState(stateDefinition.any().setValue(LETTRE, Lettre.VIDE));
        }

        @Override
        protected MapCodec<? extends Block> codec() {
            return CODEC;
        }

        @Override
        protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> b) {
            b.add(LETTRE);
        }

        @Override
        public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player joueur) {
            ranger(level, pos, joueur);
            return super.playerWillDestroy(level, pos, state, joueur);
        }
    }

    // ------------------------------------------------------------------ les cordes et les poteaux

    /** Trois cordes le long du bord du ring (FACING : le côté du ring, vers l'extérieur). */
    public static class Corde extends HorizontalDirectionalBlock {
        public static final MapCodec<Corde> CODEC = simpleCodec(Corde::new);

        public Corde(Properties p) {
            super(p);
            registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(COULEUR, Couleur.ROUGE));
        }

        @Override
        protected MapCodec<? extends HorizontalDirectionalBlock> codec() {
            return CODEC;
        }

        @Override
        protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> b) {
            b.add(FACING, COULEUR);
        }

        @Override
        protected VoxelShape getShape(BlockState s, BlockGetter l, BlockPos p, CollisionContext c) {
            return boite(s.getValue(FACING), 0, 7.4, 4.3, 16, 24, 5.9);
        }

        @Override
        protected VoxelShape getCollisionShape(BlockState s, BlockGetter l, BlockPos p, CollisionContext c) {
            return passe(c) ? Shapes.empty() : boite(s.getValue(FACING), 0, 0, 4.3, 16, 24, 5.9);
        }

        @Override
        protected boolean propagatesSkylightDown(BlockState s, BlockGetter l, BlockPos p) {
            return true;
        }

        @Override
        public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player joueur) {
            ranger(level, pos, joueur);
            return super.playerWillDestroy(level, pos, state, joueur);
        }
    }

    /** Un poteau d'acier et ses trois coussins, au coin (FACING : nord = coin nord-ouest, puis dans le sens des aiguilles). */
    public static class Poteau extends HorizontalDirectionalBlock {
        public static final MapCodec<Poteau> CODEC = simpleCodec(Poteau::new);

        public Poteau(Properties p) {
            super(p);
            registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(COULEUR, Couleur.ROUGE));
        }

        @Override
        protected MapCodec<? extends HorizontalDirectionalBlock> codec() {
            return CODEC;
        }

        @Override
        protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> b) {
            b.add(FACING, COULEUR);
        }

        @Override
        protected VoxelShape getShape(BlockState s, BlockGetter l, BlockPos p, CollisionContext c) {
            return boite(s.getValue(FACING), 1.9, 0, 1.9, 8.3, 28, 8.3);
        }

        @Override
        protected VoxelShape getCollisionShape(BlockState s, BlockGetter l, BlockPos p, CollisionContext c) {
            Direction d = s.getValue(FACING);
            VoxelShape poteau = boite(d, 1.9, 0, 1.9, 8.3, 24, 8.3);
            return passe(c) ? poteau : Shapes.or(poteau, boite(d, 8.3, 0, 4.3, 16, 24, 5.9), boite(d, 4.3, 0, 8.3, 5.9, 24, 16));
        }

        @Override
        public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player joueur) {
            ranger(level, pos, joueur);
            return super.playerWillDestroy(level, pos, state, joueur);
        }
    }

    /** Deux marches d'acier, contre le ring (elles montent vers FACING). */
    public static class Marches extends HorizontalDirectionalBlock {
        public static final MapCodec<Marches> CODEC = simpleCodec(Marches::new);

        public Marches(Properties p) {
            super(p);
            registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
        }

        @Override
        protected MapCodec<? extends HorizontalDirectionalBlock> codec() {
            return CODEC;
        }

        @Override
        protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> b) {
            b.add(FACING);
        }

        @Override
        protected VoxelShape getShape(BlockState s, BlockGetter l, BlockPos p, CollisionContext c) {
            Direction d = s.getValue(FACING);
            return Shapes.or(boite(d, 0, 0, 0, 16, 16, 8), boite(d, 0, 0, 8, 16, 8, 16));
        }

        @Override
        public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player joueur) {
            ranger(level, pos, joueur);
            return super.playerWillDestroy(level, pos, state, joueur);
        }
    }

    // ------------------------------------------------------------------ la salle

    /** La cloche de la table des commentateurs : clic droit, ding. */
    public static class Cloche extends Block {
        public static final MapCodec<Cloche> CODEC = simpleCodec(Cloche::new);
        private static final VoxelShape FORME = Shapes.or(Block.box(3, 0, 3, 13, 2, 13), Block.box(5, 2, 5, 11, 12, 11));

        public Cloche(Properties p) {
            super(p);
        }

        @Override
        protected MapCodec<? extends Block> codec() {
            return CODEC;
        }

        @Override
        protected VoxelShape getShape(BlockState s, BlockGetter l, BlockPos p, CollisionContext c) {
            return FORME;
        }

        @Override
        protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player joueur, BlockHitResult hit) {
            level.playSound(joueur, pos, Lutte.SON_CLOCHE.get(), SoundSource.BLOCKS, 1F, 1F);
            return InteractionResult.sidedSuccess(level.isClientSide);
        }
    }

    /** Un siège du public : quelqu'un y est assis (ou debout, qui crie), dessiné par le client (client/lutte/RenduPublic). */
    public static class Public extends BaseEntityBlock {
        public static final MapCodec<Public> CODEC = simpleCodec(Public::new);

        public Public(Properties p) {
            super(p);
            registerDefaultState(stateDefinition.any().setValue(HorizontalDirectionalBlock.FACING, Direction.NORTH));
        }

        @Override
        protected MapCodec<? extends BaseEntityBlock> codec() {
            return CODEC;
        }

        @Override
        protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> b) {
            b.add(HorizontalDirectionalBlock.FACING);
        }

        @Override
        public BlockState getStateForPlacement(BlockPlaceContext ctx) {
            return defaultBlockState().setValue(HorizontalDirectionalBlock.FACING, ctx.getHorizontalDirection().getOpposite());
        }

        @Override
        protected RenderShape getRenderShape(BlockState s) {
            return RenderShape.MODEL;
        }

        @Override
        protected VoxelShape getShape(BlockState s, BlockGetter l, BlockPos p, CollisionContext c) {
            return Block.box(2, 0, 2, 14, 15, 14);
        }

        @Override
        public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
            return new PublicBE(pos, state);
        }
    }

    public static class PublicBE extends BlockEntity {
        public PublicBE(BlockPos pos, BlockState state) {
            super(Lutte.BE_PUBLIC.get(), pos, state);
        }
    }

    /** Le coin en bas à gauche de l'écran géant : le client y dessine le logo (et le chrono d'un match à la salle). */
    public static class EcranLogo extends BaseEntityBlock {
        public static final MapCodec<EcranLogo> CODEC = simpleCodec(EcranLogo::new);

        public EcranLogo(Properties p) {
            super(p);
        }

        @Override
        protected MapCodec<? extends BaseEntityBlock> codec() {
            return CODEC;
        }

        @Override
        protected RenderShape getRenderShape(BlockState s) {
            return RenderShape.MODEL;
        }

        @Override
        public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
            return new EcranBE(pos, state);
        }
    }

    public static class EcranBE extends BlockEntity {
        public EcranBE(BlockPos pos, BlockState state) {
            super(Lutte.BE_ECRAN.get(), pos, state);
        }
    }

    /** Le bouton rouge au fond de la scène : il ramène pile là d'où l'on venait (le même dessin que celui du labo). */
    public static class Bouton extends Block {
        public static final MapCodec<Bouton> CODEC = simpleCodec(Bouton::new);
        private static final VoxelShape FORME = Shapes.or(Block.box(3, 0, 3, 13, 1, 13), Block.box(5, 1, 5, 11, 11, 11),
                Block.box(2, 11, 2, 14, 13, 14), Block.box(4.5, 13, 4.5, 11.5, 15.5, 11.5));

        public Bouton(Properties p) {
            super(p);
        }

        @Override
        protected MapCodec<? extends Block> codec() {
            return CODEC;
        }

        @Override
        protected VoxelShape getShape(BlockState s, BlockGetter l, BlockPos p, CollisionContext c) {
            return FORME;
        }

        @Override
        protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player joueur, BlockHitResult hit) {
            level.playSound(joueur, pos, SoundEvents.STONE_BUTTON_CLICK_ON, SoundSource.BLOCKS, 1F, 0.5F);
            if (!level.isClientSide && joueur instanceof ServerPlayer j) {
                Lutte.sortir(j);
            }
            return InteractionResult.sidedSuccess(level.isClientSide);
        }
    }

    private RingBlocs() {
    }
}
