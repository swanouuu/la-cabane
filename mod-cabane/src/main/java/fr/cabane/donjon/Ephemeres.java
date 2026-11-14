package fr.cabane.donjon;

import fr.cabane.Cabane;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Les blocs qu'un boss pose pour un instant (les pics de glace du colosse, les toiles de la reine) : ils ne se posent
 * que dans l'air, et disparaissent tout seuls (ou à l'arrêt du serveur).
 */
@EventBusSubscriber(modid = Cabane.MODID)
public final class Ephemeres {
    private record Bloc(ServerLevel niveau, BlockPos pos, BlockState etat, long fin) {
    }

    private static final List<Bloc> BLOCS = new ArrayList<>();

    /** Pose ce bloc ici pour tant de ticks, si l'endroit est vide. */
    public static boolean poser(ServerLevel l, BlockPos p, BlockState s, int duree) {
        if (!l.getBlockState(p).isAir()) {
            return false;
        }
        l.setBlock(p, s, Block.UPDATE_CLIENTS);
        BLOCS.add(new Bloc(l, p.immutable(), s, l.getGameTime() + duree));
        return true;
    }

    private static void retirer(Bloc b) {
        if (b.niveau().getBlockState(b.pos()) == b.etat()) {
            b.niveau().setBlock(b.pos(), Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
            b.niveau().sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, b.etat()), b.pos().getX() + 0.5, b.pos().getY() + 0.5,
                    b.pos().getZ() + 0.5, 6, 0.3, 0.3, 0.3, 0.05);
        }
    }

    @SubscribeEvent
    public static void tick(ServerTickEvent.Post event) {
        if (BLOCS.isEmpty()) {
            return;
        }
        for (Iterator<Bloc> it = BLOCS.iterator(); it.hasNext();) {
            Bloc b = it.next();
            if (b.niveau().getGameTime() >= b.fin()) {
                retirer(b);
                it.remove();
            }
        }
    }

    @SubscribeEvent
    public static void arret(ServerStoppingEvent event) {
        for (Bloc b : BLOCS) {
            retirer(b);
        }
        BLOCS.clear();
    }

    private Ephemeres() {
    }
}
