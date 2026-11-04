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
 * une arme du cartel se tient à deux mains (ou bras tendu, pour un pistolet) ; un catcheur prend la pose de sa prise, et
 * en tenue son corps normal se cache sous le corps gonflé ; la foudre lève la main pour le Kirin, puis la baisse ; les
 * fruits du niveau 3 collent leurs mains (la glace) ou lèvent la main (la lave) pour l'attaque ultime.
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
            fr.cabane.client.lutte.CorpsCatcheur.poser((PlayerModel<?>) (Object) this, joueur);
            fr.cabane.client.pouvoir.ClientFoudre.poser((PlayerModel<?>) (Object) this, joueur);
            fr.cabane.client.pouvoir.ClientFruit3.poser((PlayerModel<?>) (Object) this, joueur);
            // le Susano : le bras levé vers la météorite ; dans le Tsukuyomi, la cible en croix et les clones l'arme pointée
            fr.cabane.client.pouvoir.ClientMeteorite.poser((PlayerModel<?>) (Object) this, joueur);
            fr.cabane.client.pouvoir.ClientGenjutsu.poser((PlayerModel<?>) (Object) this, joueur);
        }
    }
}
