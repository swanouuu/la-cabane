package fr.cabane.mixin;

import fr.cabane.client.ClientPseudos;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Le pseudo d'un joueur caché derrière un mur ne s'affiche pas (client/ClientPseudos). Ici et pas seulement dans
 * l'événement du pseudo : EntityCulling dessine lui-même les pseudos des joueurs qu'il cache, mais il demande d'abord
 * ce shouldShowName.
 */
@Mixin(LivingEntityRenderer.class)
public abstract class LivingEntityRendererMixin {
    @Inject(method = "shouldShowName(Lnet/minecraft/world/entity/LivingEntity;)Z", at = @At("HEAD"), cancellable = true)
    private void cabane$derriereUnMur(LivingEntity entite, CallbackInfoReturnable<Boolean> cir) {
        if (entite instanceof Player joueur && !ClientPseudos.enVue(joueur)) {
            cir.setReturnValue(false);
        }
    }
}
