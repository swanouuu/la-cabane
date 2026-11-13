package fr.cabane.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Le menotté qu'on porte sur son dos (la matraque de la police) est juste devant le viseur : sans ça, chaque clic droit
 * le viserait lui (et forcerait ses menottes), et on ne pourrait même plus monter dans la voiture. Chez celui qui le porte,
 * le viseur passe à travers lui ; les autres peuvent toujours le viser.
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityViseeMixin {
    @Inject(method = "isPickable", at = @At("HEAD"), cancellable = true)
    private void cabane$porteSurLeDos(CallbackInfoReturnable<Boolean> cir) {
        LivingEntity moi = (LivingEntity) (Object) this;
        if (moi instanceof Player && moi.level().isClientSide() && moi.getVehicle() != null && moi.getVehicle() == Minecraft.getInstance().player) {
            cir.setReturnValue(false);
        }
    }
}
