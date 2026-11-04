package fr.cabane.client.rituel;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import fr.cabane.client.cinema.Effets.Posture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

/**
 * Les doubles des joueurs pendant le rituel et la marque : leur propre skin, aux bras fins (« Alex ») ou larges selon
 * leur modèle, dans une posture d'{@link fr.cabane.client.cinema.Effets} (mêmes angles, même façon de les poser), éclairés
 * par la lumière de l'endroit où ils sont.
 */
final class Doubles {
    private static PlayerModel<LivingEntity> large, fin;

    private static PlayerModel<LivingEntity> modele(boolean bras) {
        if (large == null) {
            large = new PlayerModel<>(Minecraft.getInstance().getEntityModels().bakeLayer(ModelLayers.PLAYER), false);
            fin = new PlayerModel<>(Minecraft.getInstance().getEntityModels().bakeLayer(ModelLayers.PLAYER_SLIM), true);
        }
        return bras ? fin : large;
    }

    private static void angles(ModelPart p, float x, float y, float z) {
        p.xRot = x;
        p.yRot = y;
        p.zRot = z;
    }

    /** Un joueur à son skin, les pieds en {@code pieds} (relatif à la caméra), tourné vers {@code lacet}, dans une posture. */
    static void dessiner(PoseStack pose, MultiBufferSource buffers, ResourceLocation skin, boolean brasFins, Vec3 pieds, float lacet, Posture p, int lumiere) {
        dessiner(pose, buffers, skin, brasFins, pieds, lacet, p, lumiere, false);
    }

    /** Pareil, sans la tête (pour celui dont la caméra est à la place de ses yeux). */
    static void dessiner(PoseStack pose, MultiBufferSource buffers, ResourceLocation skin, boolean brasFins, Vec3 pieds, float lacet, Posture p, int lumiere,
            boolean sansTete) {
        PlayerModel<LivingEntity> m = modele(brasFins);
        m.young = false;
        m.crouching = false;
        m.riding = false;
        angles(m.head, p.teteX(), p.teteY(), 0);
        angles(m.body, 0, 0, 0);
        angles(m.rightArm, p.brasDX(), p.brasDY(), p.brasDZ());
        angles(m.leftArm, p.brasGX(), p.brasGY(), p.brasGZ());
        angles(m.rightLeg, p.jambeDX(), 0, p.jambeDZ());
        angles(m.leftLeg, p.jambeGX(), 0, p.jambeGZ());
        m.hat.copyFrom(m.head);
        m.jacket.copyFrom(m.body);
        m.rightSleeve.copyFrom(m.rightArm);
        m.leftSleeve.copyFrom(m.leftArm);
        m.rightPants.copyFrom(m.rightLeg);
        m.leftPants.copyFrom(m.leftLeg);
        pose.pushPose();
        pose.translate(pieds.x, pieds.y, pieds.z);
        pose.mulPose(Axis.YP.rotationDegrees(180F - lacet));
        pose.translate(0, 0.9, 0);
        pose.mulPose(Axis.XP.rotationDegrees(-p.penche()));
        pose.translate(0, -0.9, 0);
        pose.scale(-0.9375F, -0.9375F, 0.9375F);
        pose.translate(0, -1.501, 0);
        m.head.visible = m.hat.visible = !sansTete;
        m.renderToBuffer(pose, buffers.getBuffer(RenderType.entityTranslucent(skin)), lumiere, OverlayTexture.NO_OVERLAY, 0xFFFFFFFF);
        m.head.visible = m.hat.visible = true;
        pose.popPose();
    }

    private Doubles() {
    }
}
