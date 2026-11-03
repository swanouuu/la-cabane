package fr.cabane.mixin;

import fr.cabane.client.pouvoir.ClientSusano;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Après la pose habituelle du joueur : le Susano peut lui faire porter la main à l'œil (pendant l'éveil), et Hachibi
 * le fait rugir, puis marcher à quatre pattes ; une espionne éveillée devient toute fine, perchée sur ses talons ;
 * une arme du cartel se tient à deux mains (ou bras tendu, pour un pistolet).
 */
@Mixin(PlayerModel.class)
public abstract class PlayerModelMixin {
    @Inject(method = "setupAnim(Lnet/minecraft/world/entity/LivingEntity;FFFFF)V", at = @At("TAIL"))
    private void cabane$eveil(LivingEntity entite, float a, float b, float c, float d, float e, CallbackInfo ci) {
        if (entite instanceof Player joueur) {
            ClientSusano.poserBras((PlayerModel<?>) (Object) this, joueur);
            fr.cabane.client.pouvoir.ClientHachibi.poser((PlayerModel<?>) (Object) this, joueur);
            fr.cabane.client.pouvoir.ClientEspionne.poser((PlayerModel<?>) (Object) this, joueur);
            fr.cabane.client.cartel.ClientCartel.poser((PlayerModel<?>) (Object) this, joueur);
        }
    }
}
