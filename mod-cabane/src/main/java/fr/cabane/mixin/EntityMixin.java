package fr.cabane.mixin;

import fr.cabane.client.pouvoir.ClientEspionne;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Vues aux lunettes infrarouges, les silhouettes sont orange (la couleur de leur contour lumineux). */
@Mixin(Entity.class)
public abstract class EntityMixin {
    @Inject(method = "getTeamColor", at = @At("HEAD"), cancellable = true)
    private void cabane$infrarouge(CallbackInfoReturnable<Integer> cir) {
        if (ClientEspionne.infrarouge((Entity) (Object) this)) {
            cir.setReturnValue(ClientEspionne.ORANGE_INFRAROUGE);
        } else if (fr.cabane.client.pouvoir.ClientSharingan.regardPercant((Entity) (Object) this)) {
            // vus par le Sharingan : rouge
            cir.setReturnValue(fr.cabane.client.pouvoir.ClientSharingan.ROUGE_REGARD);
        }
    }
}
