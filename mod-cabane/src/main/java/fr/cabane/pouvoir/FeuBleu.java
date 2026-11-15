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
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Les flammes bleues du phénix, posées au sol par ses attaques (le grand phénix, le piqué, le brasier, l'ulti) : un vrai
 * bloc, avec la forme du feu de Minecraft et la texture du feu des âmes (un peu plus cyan, voir ClientFeuNoir). Comme
 * un feu normal, elles ne restent pas : 3 à 5 s, puis elles s'éteignent ; elles ne s'étendent jamais, et l'eau les
 * éteint. Celui qui les a posées y reprend des forces (+1 cœur par seconde dedans) ; les autres y brûlent comme dans du
 * feu normal (sauf ses alliés).
 */
public class FeuBleu extends Block {
    public static final MapCodec<FeuBleu> CODEC = simpleCodec(FeuBleu::new);

    private static final DeferredRegister.Blocks BLOCS = DeferredRegister.createBlocks(Cabane.MODID);
    public static final DeferredBlock<FeuBleu> FEU = BLOCS.registerBlock("feu_bleu", FeuBleu::new,
            BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_LIGHT_BLUE).replaceable().noCollission().instabreak().noLootTable()
                    .lightLevel(s -> 12).sound(SoundType.WOOL).pushReaction(PushReaction.DESTROY));

    /** Qui a posé chaque flamme (perdu au redémarrage du serveur : elles brûlent alors tout le monde, le temps de s'éteindre). */
    private static final Map<GlobalPos, UUID> POSEURS = new HashMap<>();
    /** Quand chaque flamme s'éteint (temps du monde) : une attaque qui repasse dessus la rallume. */
    private static final Map<GlobalPos, Long> FINS = new HashMap<>();
    /** Le dernier soin de chaque phénix dans ses flammes (temps du monde) : un cœur par seconde, pas plus. */
    private static final Map<UUID, Long> SOINS = new HashMap<>();

    public static void register(IEventBus modBus) {
        BLOCS.register(modBus);
    }

    public FeuBleu(Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<? extends Block> codec() {
        return CODEC;
    }

    /** 3 à 5 s, comme un feu normal qui n'a rien à brûler. */
    static int duree(RandomSource hasard) {
        return 60 + hasard.nextInt(41);
    }

    /** Pose une flamme bleue ici (3 à 5 s), si la case est libre et qu'il y a un sol plein dessous. */
    public static boolean poser(ServerLevel monde, BlockPos pos, @Nullable UUID tireur) {
        return poser(monde, pos, tireur, duree(monde.random));
    }

    /** Pareil, pour {@code ticks} (le brasier : 5 s pile). */
    public static boolean poser(ServerLevel monde, BlockPos pos, @Nullable UUID tireur, int ticks) {
        BlockState ici = monde.getBlockState(pos);
        GlobalPos ou = GlobalPos.of(monde.dimension(), pos.immutable());
        if (ici.is(FEU.get())) {
            FINS.put(ou, Math.max(FINS.getOrDefault(ou, 0L), monde.getGameTime() + ticks));
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
        FINS.put(ou, monde.getGameTime() + ticks);
        if (!monde.setBlock(pos, feu, Block.UPDATE_ALL)) {
            POSEURS.remove(ou);
            FINS.remove(ou);
            return false;
        }
        monde.scheduleTick(pos, FEU.get(), ticks);
        return true;
    }

    /** Pose des flammes au sol tout autour de ce point (rayon r, une case sur {@code densite} environ). */
    public static void semer(ServerLevel monde, net.minecraft.world.phys.Vec3 centre, double r, double densite, @Nullable UUID tireur) {
        int n = (int) Math.ceil(r);
        for (int x = -n; x <= n; x++) {
            for (int z = -n; z <= n; z++) {
                if (x * x + z * z > r * r || monde.random.nextDouble() > densite) {
                    continue;
                }
                BlockPos p = sol(monde, BlockPos.containing(centre.x + x, centre.y + 0.2, centre.z + z));
                if (p != null) {
                    poser(monde, p, tireur);
                }
            }
        }
    }

    /** La case libre juste au-dessus du sol, près de p (3 cases plus haut ou plus bas au plus), ou null. */
    @Nullable
    static BlockPos sol(ServerLevel monde, BlockPos p) {
        BlockPos.MutableBlockPos m = p.above(3).mutable();
        for (int i = 0; i < 7; i++) {
            BlockPos dessous = m.below();
            BlockState ici = monde.getBlockState(m);
            if ((ici.isAir() || ici.canBeReplaced()) && monde.getBlockState(dessous).isFaceSturdy(monde, dessous, Direction.UP)) {
                return m.immutable();
            }
            m.move(0, -1, 0);
        }
        return null;
    }

    @Override
    protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState avant, boolean piston) {
        if (!avant.is(this)) {
            GlobalPos ou = GlobalPos.of(level.dimension(), pos.immutable());
            if (!FINS.containsKey(ou)) {
                int d = duree(level.random);
                FINS.put(ou, level.getGameTime() + d);
                level.scheduleTick(pos, this, d);
            }
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
        level.sendParticles(ParticleTypes.SMOKE, pos.getX() + 0.5, pos.getY() + 0.3, pos.getZ() + 0.5, 3, 0.25, 0.1, 0.25, 0.01);
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

    @Override
    protected BlockState updateShape(BlockState state, Direction dir, BlockState voisin, LevelAccessor level, BlockPos pos, BlockPos posVoisin) {
        return dir == Direction.DOWN && !canSurvive(state, level, pos) ? Blocks.AIR.defaultBlockState() : state;
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return Shapes.empty();
    }

    /**
     * Lui (celui qui les a posées) : +1 cœur par seconde, sans brûler. Ses alliés : rien. Les autres : comme dans du feu
     * normal (ils prennent feu 8 s, et 1 de dégâts à chaque fois qu'ils peuvent en reprendre).
     */
    @Override
    protected void entityInside(BlockState state, Level level, BlockPos pos, Entity entite) {
        if (level.isClientSide || !(entite instanceof LivingEntity vivant) || !vivant.isAlive()) {
            return;
        }
        UUID poseur = POSEURS.get(GlobalPos.of(level.dimension(), pos));
        if (poseur != null && poseur.equals(vivant.getUUID())) {
            vivant.clearFire();
            long maintenant = level.getGameTime();
            if (maintenant - SOINS.getOrDefault(poseur, -100L) >= 20) {
                SOINS.put(poseur, maintenant);
                vivant.heal(2);
                if (level instanceof ServerLevel s) {
                    s.sendParticles(ParticleTypes.HEART, vivant.getX(), vivant.getY() + 1.9, vivant.getZ(), 1, 0.2, 0.1, 0.2, 0);
                }
            }
            return;
        }
        if (poseur != null && level instanceof ServerLevel s && s.getPlayerByUUID(poseur) instanceof Player lui && vivant.isAlliedTo(lui)) {
            return;
        }
        if (!vivant.fireImmune()) {
            vivant.setRemainingFireTicks(vivant.getRemainingFireTicks() + 1);
            if (vivant.getRemainingFireTicks() == 0) {
                vivant.igniteForSeconds(8);
            }
        }
        vivant.hurt(level.damageSources().inFire(), 1);
    }

    /** Des flammèches bleues qui montent, et le crépitement du feu. */
    @Override
    public void animateTick(BlockState state, Level level, BlockPos pos, RandomSource random) {
        if (random.nextInt(4) == 0) {
            level.addParticle(ParticleTypes.SOUL_FIRE_FLAME, pos.getX() + random.nextDouble(), pos.getY() + 0.2 + random.nextDouble() * 0.5,
                    pos.getZ() + random.nextDouble(), 0, 0.03, 0);
        }
        if (random.nextInt(24) == 0) {
            level.playLocalSound(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, SoundEvents.FIRE_AMBIENT, SoundSource.BLOCKS,
                    0.6F + random.nextFloat() * 0.3F, 0.9F + random.nextFloat() * 0.3F, false);
        }
    }

    /** Arrêt du serveur : on oublie qui a posé quoi. */
    static void vider() {
        POSEURS.clear();
        FINS.clear();
        SOINS.clear();
    }
}
