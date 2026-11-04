package fr.cabane.pouvoir;

import com.mojang.serialization.MapCodec;
import fr.cabane.Cabane;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LiquidBlockContainer;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Les flammes noires d'Amaterasu posées au sol par les attaques du Susano 3 (la flèche, sa flaque, le grand coup) : un
 * vrai bloc, qui reste sur sa case et ne s'étend jamais. Rien ne l'éteint (ni l'eau, ni un coup de poing, ni un piston) :
 * il s'éteint tout seul au bout d'une minute environ. Il a la forme et la texture du feu de Minecraft, teintes en noir
 * (ClientFeuNoir), et lâche de la fumée noire. Qui marche dedans prend les flammes noires ({@link Susano3#bruler}),
 * sauf celui qui les a posées et ses alliés.
 */
public class FeuNoir extends Block implements LiquidBlockContainer {
    public static final MapCodec<FeuNoir> CODEC = simpleCodec(FeuNoir::new);
    /** Combien de temps elles brûlent : une minute, plus ou moins 10 secondes (pour qu'elles ne s'éteignent pas toutes d'un coup). */
    public static final int DUREE = 1200;
    private static final int ECART = 200;

    private static final DeferredRegister.Blocks BLOCS = DeferredRegister.createBlocks(Cabane.MODID);
    public static final DeferredBlock<FeuNoir> FEU = BLOCS.registerBlock("feu_noir", FeuNoir::new,
            BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_BLACK).strength(-1F, 3_600_000F).noCollission().noOcclusion()
                    .noLootTable().lightLevel(s -> 3).sound(SoundType.WOOL).pushReaction(PushReaction.BLOCK));

    /** Qui a posé chaque flamme (perdu au redémarrage du serveur : elles brûlent alors tout le monde, jusqu'à s'éteindre). */
    private static final Map<GlobalPos, UUID> POSEURS = new HashMap<>();
    /**
     * Quand chaque flamme doit s'éteindre (temps du monde) : une nouvelle attaque qui repasse dessus la rallume pour une
     * minute. Perdu au redémarrage : elles s'éteignent alors à leur heure d'avant.
     */
    private static final Map<GlobalPos, Long> FINS = new HashMap<>();

    public static void register(IEventBus modBus) {
        BLOCS.register(modBus);
    }

    public FeuNoir(Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<? extends Block> codec() {
        return CODEC;
    }

    /** Pose une flamme noire sur cette case, si elle est libre (de l'air, de l'herbe…) et qu'il y a un sol plein dessous. */
    public static boolean poser(ServerLevel monde, BlockPos pos, @Nullable UUID tireur) {
        BlockState ici = monde.getBlockState(pos);
        GlobalPos ou = GlobalPos.of(monde.dimension(), pos.immutable());
        if (ici.is(FEU.get())) {
            FINS.put(ou, monde.getGameTime() + duree(monde.random));
            if (tireur != null) {
                POSEURS.put(ou, tireur);
            }
            return true;
        }
        if (!ici.isAir() && !(ici.canBeReplaced() && ici.getFluidState().isEmpty())) {
            return false;
        }
        BlockState feu = FEU.get().defaultBlockState();
        if (!feu.canSurvive(monde, pos)) {
            return false;
        }
        if (tireur != null) {
            POSEURS.put(ou, tireur);
        }
        return monde.setBlock(pos, feu, Block.UPDATE_ALL);
    }

    private static int duree(RandomSource hasard) {
        return DUREE - ECART + hasard.nextInt(2 * ECART);
    }

    @Override
    protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState avant, boolean piston) {
        if (!avant.is(this)) {
            int d = duree(level.random);
            FINS.put(GlobalPos.of(level.dimension(), pos.immutable()), level.getGameTime() + d);
            level.scheduleTick(pos, this, d);
        }
    }

    /** Le temps est passé : elle s'éteint, dans un peu de fumée (sauf si une attaque l'a rallumée entre-temps). */
    @Override
    protected void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        Long fin = FINS.get(GlobalPos.of(level.dimension(), pos));
        if (fin != null && fin > level.getGameTime() + 1) {
            level.scheduleTick(pos, this, (int) (fin - level.getGameTime()));
            return;
        }
        level.removeBlock(pos, false);
        level.sendParticles(ParticleTypes.LARGE_SMOKE, pos.getX() + 0.5, pos.getY() + 0.3, pos.getZ() + 0.5, 3, 0.25, 0.1, 0.25, 0.01);
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState apres, boolean piston) {
        if (!apres.is(this)) {
            POSEURS.remove(GlobalPos.of(level.dimension(), pos));
            FINS.remove(GlobalPos.of(level.dimension(), pos));
        }
        super.onRemove(state, level, pos, apres, piston);
    }

    @Override
    protected boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
        return level.getBlockState(pos.below()).isFaceSturdy(level, pos.below(), Direction.UP);
    }

    /** Si le sol dessous disparaît, elle disparaît avec. */
    @Override
    protected BlockState updateShape(BlockState state, Direction dir, BlockState voisin, LevelAccessor level, BlockPos pos, BlockPos posVoisin) {
        return dir == Direction.DOWN && !canSurvive(state, level, pos) ? net.minecraft.world.level.block.Blocks.AIR.defaultBlockState() : state;
    }

    /** On ne peut ni la viser ni la frapper : les clics passent au travers. */
    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return Shapes.empty();
    }

    @Override
    protected void entityInside(BlockState state, Level level, BlockPos pos, Entity entite) {
        if (!level.isClientSide && entite instanceof LivingEntity vivant) {
            Susano3.bruler(vivant, POSEURS.get(GlobalPos.of(level.dimension(), pos)));
        }
    }

    // l'eau ne l'éteint pas : elle ne peut ni la remplacer ni couler dedans
    @Override
    public boolean canPlaceLiquid(@Nullable Player joueur, BlockGetter level, BlockPos pos, BlockState state, Fluid fluide) {
        return false;
    }

    @Override
    public boolean placeLiquid(LevelAccessor level, BlockPos pos, BlockState state, FluidState fluide) {
        return false;
    }

    @Override
    protected boolean canBeReplaced(BlockState state, Fluid fluide) {
        return false;
    }

    /** Un peu de fumée noire qui monte, et le crépitement du feu. */
    @Override
    public void animateTick(BlockState state, Level level, BlockPos pos, RandomSource random) {
        if (random.nextInt(3) == 0) {
            level.addParticle(random.nextBoolean() ? ParticleTypes.SQUID_INK : ParticleTypes.LARGE_SMOKE,
                    pos.getX() + random.nextDouble(), pos.getY() + 0.3 + random.nextDouble() * 0.5, pos.getZ() + random.nextDouble(), 0, 0.04, 0);
        }
        if (random.nextInt(40) == 0) {
            level.playLocalSound(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, SoundEvents.FIRE_AMBIENT, SoundSource.BLOCKS,
                    0.6F + random.nextFloat() * 0.3F, 0.5F + random.nextFloat() * 0.2F, false);
        }
    }
}
