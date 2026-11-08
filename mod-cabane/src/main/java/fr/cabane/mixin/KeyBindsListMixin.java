package fr.cabane.mixin;

import fr.cabane.client.TouchesCachees;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.screens.options.controls.KeyBindsList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** Options → Commandes : la liste se construit sans les touches qui dévoileraient les pouvoirs ({@link TouchesCachees}). */
@Mixin(KeyBindsList.class)
public abstract class KeyBindsListMixin {
    // require = 0 : si la liste change un jour, les touches réapparaissent simplement, le jeu ne plante pas
    @ModifyVariable(method = "<init>", at = @At("STORE"), ordinal = 0, require = 0)
    private KeyMapping[] cabane$cacherTouches(KeyMapping[] touches) {
        return TouchesCachees.filtrer(touches);
    }
}
