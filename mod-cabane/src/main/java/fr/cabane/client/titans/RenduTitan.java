package fr.cabane.client.titans;

import com.mojang.blaze3d.vertex.PoseStack;
import fr.cabane.Cabane;
import fr.cabane.titans.Titan;
import fr.cabane.titans.Titans;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;

/**
 * Un titan en jeu : le modèle du rendu (ModeleTitan), qui marche à grandes enjambées, tourne la tête vers ce qu'il
 * regarde (et la laisse pencher, lentement, d'un côté puis de l'autre), lève le bras et l'abat, rougit quand on le
 * touche, et tombe sur le côté en mourant.
 */
public class RenduTitan extends EntityRenderer<Titan> {
    public RenduTitan(EntityRendererProvider.Context context) {
        super(context);
        shadowRadius = 1.6F;
        shadowStrength = 0.8F;
    }

    @Override
    public void render(Titan t, float lacet, float partiel, PoseStack ps, MultiBufferSource buffers, int lumiere) {
        if (ClientTitans.actif) {
            // pendant la cinématique de l'attaque, seuls ses titans à elle sont à l'image
            return;
        }
        float corps = Mth.rotLerp(partiel, t.yBodyRotO, t.yBodyRot);
        float tete = Mth.clamp(Mth.wrapDegrees(Mth.rotLerp(partiel, t.yHeadRotO, t.yHeadRot) - corps), -60F, 60F) * Mth.DEG_TO_RAD;
        float tangage = Mth.lerp(partiel, t.xRotO, t.getXRot()) * Mth.DEG_TO_RAD;
        float age = t.tickCount + partiel;
        float ampleur = Math.min(1F, t.walkAnimation.speed(partiel) * 1.6F);
        // de très longues jambes : une enjambée lente qui couvre beaucoup de terrain
        float pas = t.walkAnimation.position(partiel) * 0.11F;
        // la tête qui bascule lentement : le regard vide des titans
        float penche = Mth.sin(age * 0.022F + t.getId()) * 0.16F;
        float machoire = Math.max(0, Mth.sin(age * 0.12F + t.getId())) * (ampleur > 0.1F ? 1F : 0.4F);
        ModeleTitan.Pose p = new ModeleTitan.Pose(pas + age * 0.02F, ampleur, tete, tangage * 0.6F, penche, t.coup(partiel), machoire,
                0, t.chute(partiel));
        int overlay = OverlayTexture.pack(OverlayTexture.u(0F), OverlayTexture.v(t.hurtTime > 0 || t.deathTime > 0));
        ModeleTitan.dessiner(t.sorte().id, ps, buffers, Vec3.ZERO, corps, p, lumiere, overlay);
        if (t.deathTime <= 0) {
            super.render(t, lacet, partiel, ps, buffers, lumiere);
        }
    }

    @Override
    public ResourceLocation getTextureLocation(Titan t) {
        return ModeleTitan.texture(t.sorte().id);
    }

    @EventBusSubscriber(modid = Cabane.MODID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
    public static final class Enregistrement {
        @SubscribeEvent
        public static void rendus(EntityRenderersEvent.RegisterRenderers event) {
            event.registerEntityRenderer(Titans.TITAN.get(), RenduTitan::new);
        }
    }
}
