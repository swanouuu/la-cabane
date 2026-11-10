package fr.cabane.mixin;

import fr.cabane.pouvoir.Fracture;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Tous les changements de blocs passent par ici : la fracture temporelle s'en souvient (seulement si quelqu'un a susanodev). */
@Mixin(LevelChunk.class)
public abstract class LevelChunkMixin {
    @Inject(method = "setBlockState", at = @At("HEAD"))
    private void cabane$souvenir(BlockPos pos, BlockState etat, boolean bouge, CallbackInfoReturnable<BlockState> cir) {
        if (Fracture.enregistre) {
            Fracture.noter((LevelChunk) (Object) this, pos, etat);
        }
    }
}
