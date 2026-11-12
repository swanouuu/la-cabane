package fr.cabane.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import fr.cabane.client.pouvoir.ClientSusano;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Un pouvoir éveillé cache l'armure (seulement son dessin : elle protège toujours), pour qu'on voie la tenue du pouvoir.
 * Les fruits du démon gardent la leur : la lave ou la glace la recouvre déjà. Voir {@link ClientSusano#sansArmure}.
 */
@Mixin(HumanoidArmorLayer.class)
public abstract class HumanoidArmorLayerMixin {
    @Inject(method = "render(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;ILnet/minecraft/world/entity/LivingEntity;FFFFFF)V",
            at = @At("HEAD"), cancellable = true)
    private void cabane$sansArmure(PoseStack ps, MultiBufferSource buffers, int lumiere, LivingEntity entite, float a, float b, float c,
            float d, float e, float f, CallbackInfo ci) {
        if (entite instanceof Player joueur && ClientSusano.sansArmure(joueur)) {
            ci.cancel();
        }
    }
}
