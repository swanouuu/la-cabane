package fr.cabane.mixin;

import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Pour poser un effet d'écran à nous (le temps arrêté de Minato 3, voir ClientMinatoRouge) : loadEffect est privé. */
@Mixin(GameRenderer.class)
public interface GameRendererAccess {
    @Invoker("loadEffect")
    void cabane$chargerEffet(ResourceLocation effet);
}
