package fr.cabane.mixin;

import fr.cabane.client.pouvoir.ClientMaudit;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.resources.PlayerSkin;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * La peau du joueur : celui que la marque maudite a éveillé porte la tenue de Sasuke. Avant les autres mods
 * (Create Stuff Additions remplace la peau de certains pseudos pour leur cape, et coupe alors les suivants), et sans
 * rien toucher pour les autres joueurs.
 */
@Mixin(value = AbstractClientPlayer.class, priority = 500)
public abstract class AbstractClientPlayerMixin {
    @Inject(method = "getSkin", at = @At("RETURN"), cancellable = true)
    private void cabane$marque(CallbackInfoReturnable<PlayerSkin> cir) {
        PlayerSkin skin = cir.getReturnValue();
        PlayerSkin nue = ClientMaudit.peau((AbstractClientPlayer) (Object) this, skin);
        if (nue != skin) {
            cir.setReturnValue(nue);
        }
    }
}
