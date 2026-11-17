package fr.cabane.compat;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

/**
 * La pose du gong de Mowzie's Mobs, refaite proprement (voir {@link fr.cabane.mixin.GongMowzieMixin}) : le gong est la
 * case du bas au milieu, ses morceaux remplissent le reste du carré de 3 × 3 (à gauche, à droite, et les deux rangées
 * du dessus), avec la même direction et la hauteur de chacun, exactement comme Mowzie le fait.
 */
public final class GongMowzie {
    private static final ResourceLocation PARTIE = ResourceLocation.fromNamespaceAndPath("mowziesmobs", "gong_part");

    /** Pose les morceaux ; false si quelque chose manque (Mowzie fait alors comme d'habitude). */
    public static boolean poser(Level level, BlockPos pos, BlockState gong) {
        Block partie = BuiltInRegistries.BLOCK.get(PARTIE);
        Property<?> faceGong = gong.getBlock().getStateDefinition().getProperty("facing");
        if (partie == Blocks.AIR || faceGong == null || !(gong.getValue(faceGong) instanceof Direction face)) {
            return false;
        }
        BlockState base = partie.defaultBlockState();
        if (base.getBlock().getStateDefinition().getProperty("gong_part") == null || base.getBlock().getStateDefinition().getProperty("y_offset") == null) {
            return false;
        }
        BlockPos centre = pos.immutable();
        for (int i = 0; i < 3; i++) {
            BlockPos rangee = centre.above(i);
            level.setBlock(rangee.relative(face.getClockWise()), morceau(base, face, "side_left", i), Block.UPDATE_ALL);
            level.setBlock(rangee.relative(face.getCounterClockWise()), morceau(base, face, "side_right", i), Block.UPDATE_ALL);
            if (i > 0) {
                level.setBlock(rangee, morceau(base, face, "center", i), Block.UPDATE_ALL);
            }
            level.blockUpdated(rangee, Blocks.AIR);
            gong.updateNeighbourShapes(level, rangee, Block.UPDATE_ALL);
        }
        return true;
    }

    private static BlockState morceau(BlockState base, Direction face, String partie, int hauteur) {
        BlockState s = avec(base, "facing", face.getSerializedName());
        s = avec(s, "gong_part", partie);
        return avec(s, "y_offset", Integer.toString(hauteur));
    }

    /** Change une propriété d'après son nom et sa valeur écrite (comme dans les fichiers du jeu). */
    private static BlockState avec(BlockState s, String nom, String valeur) {
        Property<?> p = s.getBlock().getStateDefinition().getProperty(nom);
        return p == null ? s : regler(s, p, valeur);
    }

    private static <T extends Comparable<T>> BlockState regler(BlockState s, Property<T> p, String valeur) {
        return p.getValue(valeur).map(v -> s.setValue(p, v)).orElse(s);
    }

    private GongMowzie() {
    }
}
