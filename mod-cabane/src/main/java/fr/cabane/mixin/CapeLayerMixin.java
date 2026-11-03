package fr.cabane.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import fr.cabane.client.pouvoir.ClientEspionne;
import fr.cabane.client.pouvoir.ClientSusano;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.layers.CapeLayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Pas de cape tant qu'un pouvoir est éveillé (Susano, Hachibi, la marque, Minato, la combinaison d'une espionne, un
 * fruit du démon…), même celle que Create Stuff Additions remet dans la peau après nous ; elle revient quand le
 * pouvoir se rendort.
 */
@Mixin(CapeLayer.class)
public abstract class CapeLayerMixin {
    @Inject(method = "render(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;ILnet/minecraft/client/player/AbstractClientPlayer;FFFFFF)V",
            at = @At("HEAD"), cancellable = true)
    private void cabane$transforme(PoseStack ps, MultiBufferSource buffers, int lumiere, AbstractClientPlayer joueur, float a, float b, float c,
            float d, float e, float f, CallbackInfo ci) {
        if (ClientEspionne.eveillee(joueur) || ClientSusano.transforme(joueur)) {
            ci.cancel();
        }
    }
}
