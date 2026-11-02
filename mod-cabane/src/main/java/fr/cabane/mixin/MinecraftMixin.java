package fr.cabane.mixin;

import fr.cabane.client.pouvoir.ClientEspionne;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Avec les lunettes infrarouges d'une espionne, les créatures autour brillent à travers les murs (chez elle seulement). */
@Mixin(Minecraft.class)
public abstract class MinecraftMixin {
    @Inject(method = "shouldEntityAppearGlowing", at = @At("HEAD"), cancellable = true)
    private void cabane$infrarouge(Entity entite, CallbackInfoReturnable<Boolean> cir) {
        if (ClientEspionne.infrarouge(entite)) {
            cir.setReturnValue(true);
        }
    }
}
