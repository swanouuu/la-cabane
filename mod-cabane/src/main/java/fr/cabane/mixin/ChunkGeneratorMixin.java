package fr.cabane.mixin;

import fr.cabane.monde.CabaneMonde;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Autour du village, aucune structure ne démarre : le spawn reste propre. */
@Mixin(ChunkGenerator.class)
public abstract class ChunkGeneratorMixin {
    @Inject(method = "createStructures", at = @At("HEAD"), cancellable = true)
    private void cabane$zoneCalme(RegistryAccess acces, ChunkGeneratorStructureState etat, StructureManager structures, ChunkAccess chunk,
                                  StructureTemplateManager modeles, CallbackInfo ci) {
        if (CabaneMonde.dansLaZoneCalme(chunk.getPos())) {
            ci.cancel();
        }
    }
}
