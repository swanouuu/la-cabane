package fr.cabane.client.parc;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import fr.cabane.Cabane;
import fr.cabane.parc.Parc;
import fr.cabane.parc.Trajet;
import fr.cabane.parc.WagonParc;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import com.mojang.math.Axis;
import fr.cabane.parc.Maneges;
import org.joml.Matrix3f;
import org.joml.Quaternionf;

/**
 * Le wagon du Dragon : une caisse rouge à liseré doré, un dossier, une barre de sécurité, des roues sous le rail ; le
 * premier wagon porte la tête du dragon (yeux jaunes qui brillent, cornes). Il est dessiné à sa place exacte sur le
 * rail, tourné comme le rail (penché dans les virages, la tête en bas dans le looping).
 */
public class RenduWagonParc extends EntityRenderer<WagonParc> {
    private static final int ROUGE = 0xC0392B, ROUGE_S = 0x8E1F14, OR = 0xF1C40F, NOIR = 0x2B2B2B, GRIS = 0x555555,
            VERT = 0x2F8A3E, VERT_C = 0x3AA04A, OS = 0xF5E6C8, JAUNE = 0xFFE14D, BLANC = 0xFFFFFF;
    private final Trajet.Point p = new Trajet.Point();

    public RenduWagonParc(EntityRendererProvider.Context context) {
        super(context);
        shadowRadius = 0F;
    }

    /** Le repère du wagon (x à gauche, y en haut, z devant) en rotation. */
    static Quaternionf orientation(Trajet.Point p) {
        float gx = (float) (p.uy * p.fz - p.uz * p.fy), gy = (float) (p.uz * p.fx - p.ux * p.fz), gz = (float) (p.ux * p.fy - p.uy * p.fx);
        Matrix3f m = new Matrix3f(gx, gy, gz, (float) p.ux, (float) p.uy, (float) p.uz, (float) p.fx, (float) p.fy, (float) p.fz);
        return new Quaternionf().setFromNormalized(m);
    }

    @Override
    public void render(WagonParc w, float lacet, float partiel, PoseStack ps, MultiBufferSource buffers, int lumiere) {
        w.trajet().a(w.distance(partiel), p).caisse();
        double ex = Mth.lerp(partiel, w.xo, w.getX()), ey = Mth.lerp(partiel, w.yo, w.getY()), ez = Mth.lerp(partiel, w.zo, w.getZ());
        ps.pushPose();
        ps.translate(p.x - ex, p.y - ey, p.z - ez);
        ps.mulPose(orientation(p));
        VertexConsumer c = buffers.getBuffer(Boites.type());
        switch (w.manege()) {
            case Maneges.MINE -> wagonnet(c, ps, lumiere);
            case Maneges.COSMOS -> soucoupe(c, ps, w, lumiere);
            case Maneges.SPLASH -> tronc(c, ps, lumiere);
            default -> dragon(c, ps, w, lumiere);
        }
        ps.popPose();
        super.render(w, lacet, partiel, ps, buffers, lumiere);
    }

    private static void dragon(VertexConsumer c, PoseStack ps, WagonParc w, int lumiere) {
        // le châssis et les roues (sous le rail et dessus)
        Boites.pave(c, ps, -0.85F, 0.05F, -1.2F, 0.85F, 0.35F, 1.2F, NOIR, lumiere);
        for (int sx = -1; sx <= 1; sx += 2) {
            for (int sz = -1; sz <= 1; sz += 2) {
                Boites.pave(c, ps, sx * 0.62F - 0.12F, -0.25F, sz * 0.8F - 0.2F, sx * 0.62F + 0.12F, 0.15F, sz * 0.8F + 0.2F, GRIS, lumiere);
            }
        }
        // la caisse
        // la caisse est creuse : un fond, deux flancs, un nez, et le siège avec son dossier ; un liseré doré sur les bords
        Boites.pave(c, ps, -1.0F, 0.35F, -1.3F, 1.0F, 0.55F, 1.3F, ROUGE, lumiere);
        for (int s = -1; s <= 1; s += 2) {
            Boites.pave(c, ps, s * 0.9F - 0.1F, 0.55F, -1.3F, s * 0.9F + 0.1F, 1.12F, 1.3F, ROUGE, lumiere);
            Boites.pave(c, ps, s * 0.92F - 0.11F, 1.12F, -1.33F, s * 0.92F + 0.11F, 1.2F, 1.33F, OR, lumiere);
        }
        Boites.pave(c, ps, -0.8F, 0.55F, 1.1F, 0.8F, 1.12F, 1.3F, ROUGE, lumiere);
        Boites.pave(c, ps, -0.8F, 1.12F, 1.1F, 0.8F, 1.2F, 1.33F, OR, lumiere);
        Boites.pave(c, ps, -0.8F, 0.55F, -1.0F, 0.8F, 0.9F, 0.0F, ROUGE_S, lumiere);
        Boites.pave(c, ps, -0.95F, 0.55F, -1.3F, 0.95F, 2.0F, -1.0F, ROUGE_S, lumiere);
        // la barre de sécurité
        Boites.pave(c, ps, -0.85F, 1.3F, 0.5F, 0.85F, 1.36F, 0.56F, OR, lumiere);
        Boites.pave(c, ps, -0.04F, 1.0F, 0.5F, 0.04F, 1.33F, 0.56F, OR, lumiere);
        if (w.index() == 0) {
            // la tête du dragon, basse, pour qu'on voie par-dessus depuis le premier rang
            Boites.pave(c, ps, -0.5F, 0.6F, 1.3F, 0.5F, 1.25F, 1.9F, VERT, lumiere);
            Boites.pave(c, ps, -0.6F, 0.8F, 1.7F, 0.6F, 1.4F, 2.95F, VERT, lumiere);
            Boites.pave(c, ps, -0.45F, 0.75F, 2.9F, 0.45F, 1.15F, 3.5F, VERT_C, lumiere);
            for (int s = -1; s <= 1; s += 2) {
                Boites.pave(c, ps, s * 0.55F - 0.08F, 1.22F, 2.4F, s * 0.55F + 0.08F, 1.38F, 2.6F, JAUNE, Boites.PLEIN);
                Boites.pave(c, ps, s * 0.4F - 0.07F, 1.4F, 1.85F, s * 0.4F + 0.07F, 1.8F, 1.99F, OS, lumiere);
                Boites.pave(c, ps, s * 0.3F - 0.06F, 0.7F, 3.42F, s * 0.3F + 0.06F, 0.86F, 3.48F, BLANC, lumiere);
            }
        } else {
            // l'attelage vers le wagon de devant
            Boites.pave(c, ps, -0.1F, 0.15F, 1.2F, 0.1F, 0.3F, 1.6F, GRIS, lumiere);
        }
    }

    /** Le wagonnet de la Mine : une caisse en bois sur un châssis de fer, une lampe qui brille à l'avant. */
    private static void wagonnet(VertexConsumer c, PoseStack ps, int lumiere) {
        int fer = 0x3A3A3A, bois = 0x6B4A2B, fond = 0x5B3B22;
        Boites.pave(c, ps, -0.7F, 0.05F, -1.1F, 0.7F, 0.35F, 1.1F, fer, lumiere);
        for (int sx = -1; sx <= 1; sx += 2) {
            for (int sz = -1; sz <= 1; sz += 2) {
                Boites.pave(c, ps, sx * 0.68F - 0.1F, -0.08F, sz * 0.7F - 0.22F, sx * 0.68F + 0.1F, 0.37F, sz * 0.7F + 0.22F, 0x222222, lumiere);
            }
        }
        Boites.pave(c, ps, -0.9F, 0.45F, -1.2F, 0.9F, 0.65F, 1.2F, fond, lumiere);
        for (int s = -1; s <= 1; s += 2) {
            Boites.pave(c, ps, s * 0.85F - 0.06F, 0.55F, -1.2F, s * 0.85F + 0.06F, 1.45F, 1.2F, bois, lumiere);
            Boites.pave(c, ps, -0.9F, 0.55F, s * 1.15F - 0.06F, 0.9F, 1.45F, s * 1.15F + 0.06F, bois, lumiere);
        }
        for (int s = -1; s <= 1; s += 2) {
            Boites.pave(c, ps, -0.93F, 1.41F, s * 1.19F - 0.04F, 0.93F, 1.49F, s * 1.19F + 0.04F, 0x777777, lumiere);
            Boites.pave(c, ps, s * 0.89F - 0.04F, 1.41F, -1.23F, s * 0.89F + 0.04F, 1.49F, 1.23F, 0x777777, lumiere);
        }
        Boites.pave(c, ps, -0.2F, 0.95F, 1.21F, 0.2F, 1.25F, 1.38F, 0xFFE48A, Boites.PLEIN);
    }

    /** La soucoupe du Cosmos : un disque sombre cerclé de néon, un pilier au milieu, quatre dossiers dos à dos. */
    private static void soucoupe(VertexConsumer c, PoseStack ps, WagonParc w, int lumiere) {
        int[] neons = {0x00F0FF, 0xFF3DF2, 0xFFE14D, 0x7BFF9B};
        int neon = neons[(w.getId() & 0x7FFFFFFF) % neons.length];
        for (int k = 0; k < 2; k++) {
            ps.pushPose();
            ps.mulPose(Axis.YP.rotationDegrees(k * 45F));
            Boites.pave(c, ps, -1.25F, 0.2F, -1.25F, 1.25F, 0.68F, 1.25F, 0x2A2140, lumiere);
            Boites.pave(c, ps, -1.33F, 0.66F, -1.33F, 1.33F, 0.78F, 1.33F, neon, Boites.PLEIN);
            ps.popPose();
        }
        Boites.pave(c, ps, -0.25F, 0.7F, -0.25F, 0.25F, 1.7F, 0.25F, 0x3A3060, lumiere);
        for (int j = 0; j < 4; j++) {
            ps.pushPose();
            ps.mulPose(Axis.YP.rotationDegrees(j * 90F));
            Boites.pave(c, ps, -0.45F, 0.78F, 0.12F, 0.45F, 1.75F, 0.27F, 0x5A4A9A, lumiere);
            ps.popPose();
        }
    }

    /** Le tronc de la Rivière Splash : un rondin creusé, le nez arrondi, des cercles d'écorce. */
    private static void tronc(VertexConsumer c, PoseStack ps, int lumiere) {
        int ecorce = 0x9A6232, sombre = 0x74482A, dedans = 0xB5834F;
        Boites.pave(c, ps, -0.95F, -0.3F, -2.1F, 0.95F, 0.6F, 2.1F, ecorce, lumiere);
        Boites.pave(c, ps, -0.7F, 0.0F, 2.0F, 0.7F, 0.7F, 2.6F, ecorce, lumiere);
        Boites.pave(c, ps, -0.75F, 0.45F, -1.8F, 0.75F, 0.62F, 1.8F, dedans, lumiere);
        for (int j = 0; j < 4; j++) {
            float z = -1.6F + j * 1.1F;
            Boites.pave(c, ps, -0.98F, -0.33F, z - 0.06F, 0.98F, 0.63F, z + 0.06F, sombre, lumiere);
        }
        Boites.pave(c, ps, -0.75F, 0.4F, -2.4F, 0.75F, 1.2F, -2.1F, sombre, lumiere);
    }

    @Override
    public ResourceLocation getTextureLocation(WagonParc w) {
        return Boites.GRAIN;
    }

    @EventBusSubscriber(modid = Cabane.MODID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
    public static final class Enregistrement {
        @SubscribeEvent
        public static void rendus(EntityRenderersEvent.RegisterRenderers event) {
            event.registerEntityRenderer(Parc.WAGON.get(), RenduWagonParc::new);
        }
    }
}
