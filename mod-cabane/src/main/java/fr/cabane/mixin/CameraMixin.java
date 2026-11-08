package fr.cabane.mixin;

import fr.cabane.client.hachibi4.ClientHachibi4;
import net.minecraft.client.Camera;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Dans l'ulti de Hachibi 4, la caméra (à la troisième personne) recule loin derrière la bête, à hauteur de sa poitrine. */
@Mixin(Camera.class)
public abstract class CameraMixin {
    @Shadow
    protected abstract void setPosition(Vec3 position);

    @Inject(method = "setup", at = @At("TAIL"))
    private void cabane$hachibi4(BlockGetter niveau, Entity entite, boolean detachee, boolean miroir, float partiel, CallbackInfo ci) {
        Vec3 p = ClientHachibi4.camera((Camera) (Object) this, entite, detachee, partiel);
        if (p == null) {
            // l ulti de Kaguya : loin derrière elle, sur son dinosaure d os
            p = fr.cabane.client.pouvoir.ClientKaguyaDino.camera((Camera) (Object) this, entite, detachee, partiel);
        }
        if (p != null) {
            setPosition(p);
        }
    }
}
