package fr.cabane.monde;

import fr.cabane.Cabane;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Le monde de Beauvais : les arbres géants de la jungle, et la « zone calme » autour du village, où aucune
 * structure (village, donjon, ruine…) ne s'installe, pour un spawn propre (voir ChunkGeneratorMixin).
 */
public final class CabaneMonde {
    public static final DeferredRegister<Feature<?>> FEATURES = DeferredRegister.create(Registries.FEATURE, Cabane.MODID);
    public static final DeferredHolder<Feature<?>, ArbreGeant> ARBRE_GEANT = FEATURES.register("arbre_geant", ArbreGeant::new);

    /** Pas de structure dont le départ est à moins de RAYON_CALME blocs du village. */
    public static final int RAYON_CALME = 400;
    private static volatile BlockPos centreCalme;

    public static void register(IEventBus modBus) {
        FEATURES.register(modBus);
    }

    public static void zoneCalme(BlockPos centre) {
        centreCalme = centre;
    }

    public static boolean dansLaZoneCalme(ChunkPos chunk) {
        BlockPos c = centreCalme;
        if (c == null) {
            return false;
        }
        long dx = chunk.getMiddleBlockX() - c.getX();
        long dz = chunk.getMiddleBlockZ() - c.getZ();
        return dx * dx + dz * dz < (long) RAYON_CALME * RAYON_CALME;
    }

    private CabaneMonde() {
    }
}
