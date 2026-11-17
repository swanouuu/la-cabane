package fr.cabane.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Le gong de Mowzie's Mobs (3 × 3 blocs) se posait puis disparaissait aussitôt : en posant ses morceaux, Mowzie
 * compare la case du milieu en bas à la position du gong avec « != » (même objet) au lieu de « equals ». Dans notre
 * pack la position n'est plus le même objet, le gong est écrasé par un de ses morceaux, qui ne retrouve plus son gong,
 * et tout s'efface en chaîne. On pose les morceaux nous-mêmes, correctement (voir {@link fr.cabane.compat.GongMowzie}).
 */
@Pseudo
@Mixin(targets = "com.bobmowzie.mowziesmobs.server.block.GongBlock", remap = false)
public abstract class GongMowzieMixin {
    @Inject(method = "setPlacedBy", at = @At("HEAD"), cancellable = true, require = 0)
    private void cabane$poserGong(Level level, BlockPos pos, BlockState state, LivingEntity placer, ItemStack stack, CallbackInfo ci) {
        if (!level.isClientSide && fr.cabane.compat.GongMowzie.poser(level, pos, state)) {
            ci.cancel();
        }
    }
}
