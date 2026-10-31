package fr.cabane.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/** Petits outils pour écrire et poser des objets dans le monde (rendus des blocs). */
final class Rendu3D {
    /** Un texte centré en (x, y) dans le repère courant, à plat (comme sur un panneau). */
    static void texte(PoseStack ps, MultiBufferSource buf, Component t, float x, float y, float echelle, int couleur, int fond, int lumiere) {
        Font font = Minecraft.getInstance().font;
        ps.pushPose();
        ps.translate(x, y, 0);
        ps.scale(echelle, -echelle, echelle);
        font.drawInBatch(t, -font.width(t) / 2F, 0, couleur, false, ps.last().pose(), buf, Font.DisplayMode.NORMAL, fond, lumiere);
        ps.popPose();
    }

    /** Un texte qui fait toujours face à la caméra, au point (x, y, z) du bloc. */
    static void etiquette(PoseStack ps, MultiBufferSource buf, Component t, double x, double y, double z, float echelle, int couleur, int fond, int lumiere) {
        ps.pushPose();
        ps.translate(x, y, z);
        ps.mulPose(Minecraft.getInstance().getEntityRenderDispatcher().cameraOrientation());
        texte(ps, buf, t, 0, 0, echelle, couleur, fond, lumiere);
        ps.popPose();
    }

    static void objet(PoseStack ps, MultiBufferSource buf, ItemStack stack, Level level, int lumiere) {
        Minecraft.getInstance().getItemRenderer().renderStatic(stack, ItemDisplayContext.FIXED, lumiere, OverlayTexture.NO_OVERLAY, ps, buf, level, 0);
    }

    static void tourner(PoseStack ps, float degres) {
        ps.mulPose(Axis.YP.rotationDegrees(degres));
    }

    private Rendu3D() {
    }
}
