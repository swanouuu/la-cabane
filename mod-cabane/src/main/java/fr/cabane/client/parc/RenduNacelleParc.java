package fr.cabane.client.parc;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import fr.cabane.Cabane;
import fr.cabane.parc.NacelleParc;
import fr.cabane.parc.Parc;
import fr.cabane.parc.Tournants;
import fr.cabane.parc.Trajet;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import org.joml.Vector3f;

/**
 * Les nacelles des manèges qui tournent : l'anneau de sièges de la Tour, une cabine de la Grande Roue, un cheval du
 * Carrousel (avec sa barre dorée), une tasse, le Bateau Pirate (ses bras rouges jusqu'à l'axe, sa tête de mort, son
 * drapeau) et la rangée de sièges de la Toupie. Chacune est dessinée dans son repère (x à gauche, y en haut, z devant).
 */
public class RenduNacelleParc extends EntityRenderer<NacelleParc> {
    private static final int[] COULEURS = {0xFF5C8A, 0xFFD23F, 0x2EC4B6, 0x4DC9FF, 0xC77DFF, 0xFF8C42, 0x7BD389, 0xE8333F};
    private static final int[] TASSES = {0xFF8FB1, 0x7FE3C9, 0xFFE066, 0x8FD3FF, 0xD4A5FF, 0xFFB38A, 0xFF6B6B, 0xA0E57A, 0xFFC6E0};
    private static final int[] ROBES = {0xFFFFFF, 0xF5DEB3, 0x5A3A1E, 0x222222, 0xD9D9D9}, CRINIERES = {0xFFD23F, 0x2B1D12, 0xFF5C8A, 0xFFFFFF};
    private final Trajet.Point p = new Trajet.Point();

    public RenduNacelleParc(EntityRendererProvider.Context context) {
        super(context);
        shadowRadius = 0F;
    }

    @Override
    public boolean shouldRender(NacelleParc n, Frustum frustum, double x, double y, double z) {
        return n.distanceToSqr(x, y, z) < 220 * 220;
    }

    /** Un pavé donné par son centre et sa taille. */
    static void b(VertexConsumer c, PoseStack ps, float x, float y, float z, float w, float h, float d, int rgb, int lum) {
        Boites.pave(c, ps, x - w / 2, y - h / 2, z - d / 2, x + w / 2, y + h / 2, z + d / 2, rgb, lum);
    }

    /** Une barre d'un point à un autre (dans le repère courant). */
    static void barre(VertexConsumer c, PoseStack ps, float ax, float ay, float az, float bx, float by, float bz, float demi, int rgb, int lum) {
        Vector3f k = new Vector3f(bx - ax, by - ay, bz - az);
        float l = k.length();
        k.normalize();
        Vector3f a = new Vector3f(k).cross(Math.abs(k.x) < 0.9F ? new Vector3f(1, 0, 0) : new Vector3f(0, 1, 0)).normalize();
        Vector3f bb = new Vector3f(k).cross(a).normalize();
        Boites.boite(c, ps.last(), (ax + bx) / 2, (ay + by) / 2, (az + bz) / 2, a.x, a.y, a.z, bb.x, bb.y, bb.z, k.x, k.y, k.z, demi, demi, l / 2, rgb, lum);
    }

    @Override
    public void render(NacelleParc n, float lacet, float partiel, PoseStack ps, MultiBufferSource buffers, int lum) {
        n.cadre(partiel, p);
        double ex = Mth.lerp(partiel, n.xo, n.getX()), ey = Mth.lerp(partiel, n.yo, n.getY()), ez = Mth.lerp(partiel, n.zo, n.getZ());
        ps.pushPose();
        ps.translate(p.x - ex, p.y - ey, p.z - ez);
        ps.mulPose(RenduWagonParc.orientation(p));
        VertexConsumer c = buffers.getBuffer(Boites.type());
        switch (n.manege()) {
            case Tournants.TOUR -> tour(c, ps, lum);
            case Tournants.ROUE -> cabine(c, ps, n.index(), lum);
            case Tournants.CARROUSEL -> cheval(c, ps, n.index(), lum);
            case Tournants.TASSES -> tasse(c, ps, n.index(), lum);
            case Tournants.BATEAU -> bateau(c, ps, lum);
            default -> toupie(c, ps, lum);
        }
        ps.popPose();
        super.render(n, lacet, partiel, ps, buffers, lum);
    }

    private static void tour(VertexConsumer c, PoseStack ps, int lum) {
        int noir = 0x2A2A2A, rouge = 0xE8333F, jaune = 0xFFD23F;
        b(c, ps, 0, 0.3F, 3.4F, 8.4F, 0.6F, 0.8F, noir, lum);
        b(c, ps, 0, 0.3F, -3.4F, 8.4F, 0.6F, 0.8F, noir, lum);
        b(c, ps, 3.4F, 0.3F, 0, 0.8F, 0.6F, 6F, noir, lum);
        b(c, ps, -3.4F, 0.3F, 0, 0.8F, 0.6F, 6F, noir, lum);
        for (int s = 0; s < 4; s++) {
            ps.pushPose();
            ps.mulPose(Axis.YP.rotation(s * Mth.HALF_PI));
            for (int j = -1; j <= 1; j++) {
                b(c, ps, j * 1.9F, 1.3F, 3.6F, 1.2F, 1.8F, 0.2F, rouge, lum);
                b(c, ps, j * 1.9F, 0.6F, 4.0F, 1.2F, 0.2F, 0.7F, rouge, lum);
                b(c, ps, j * 1.9F, 1.25F, 4.25F, 1.0F, 0.16F, 0.3F, jaune, lum);
            }
            ps.popPose();
        }
    }

    private static void cabine(VertexConsumer c, PoseStack ps, int k, int lum) {
        int col = COULEURS[k % COULEURS.length];
        b(c, ps, 0, -0.6F, 0, 0.3F, 1.2F, 0.3F, 0x888888, lum);
        b(c, ps, 0, -1.3F, 0, 2.8F, 0.3F, 2.6F, col, lum);
        for (int sx = -1; sx <= 1; sx += 2) {
            for (int sz = -1; sz <= 1; sz += 2) {
                b(c, ps, sx * 1.2F, -2.6F, sz * 1.1F, 0.14F, 2.4F, 0.14F, 0xDDDDDD, lum);
            }
        }
        b(c, ps, 0, -3.9F, 0, 2.6F, 0.2F, 2.4F, col, lum);
        for (int sz = -1; sz <= 1; sz += 2) {
            b(c, ps, 0, -3.4F, sz * 1.15F, 2.6F, 0.9F, 0.1F, col, lum);
            b(c, ps, 0, -3.5F, sz * 0.75F, 2.2F, 0.3F, 0.5F, 0xF4F4F4, lum);
        }
    }

    private static void cheval(VertexConsumer c, PoseStack ps, int k, int lum) {
        int robe = ROBES[(k * 7 + 3) % ROBES.length], crin = CRINIERES[(k * 5 + 1) % CRINIERES.length];
        b(c, ps, 0, 0, 0, 0.8F, 0.8F, 1.9F, robe, lum);
        ps.pushPose();
        ps.translate(0, 0.55F, 0.95F);
        ps.mulPose(Axis.XP.rotation(-0.6F));
        b(c, ps, 0, 0, 0, 0.5F, 1.0F, 0.55F, robe, lum);
        ps.popPose();
        b(c, ps, 0, 1.05F, 1.35F, 0.5F, 0.5F, 0.95F, robe, lum);
        b(c, ps, 0, 1.25F, 1.0F, 0.18F, 0.6F, 0.5F, crin, lum);
        for (int s = -1; s <= 1; s += 2) {
            b(c, ps, s * 0.27F, 1.2F, 1.7F, 0.08F, 0.1F, 0.08F, 0x111111, lum);
            b(c, ps, s * 0.25F, -0.75F, 0.75F, 0.2F, 0.9F, 0.2F, robe, lum);
            b(c, ps, s * 0.25F, -0.75F, -0.75F, 0.2F, 0.9F, 0.2F, robe, lum);
        }
        b(c, ps, 0, 0.15F, -1.05F, 0.15F, 0.9F, 0.2F, crin, lum);
        b(c, ps, 0, 0.45F, 0.1F, 0.84F, 0.12F, 0.8F, 0xC0392B, lum);
        b(c, ps, 0, 0.42F, 0.1F, 0.86F, 0.3F, 0.1F, 0xFFD23F, lum);
        // la barre dorée, du plateau au toit, devant le cavalier
        b(c, ps, 0, 2.2F, 0.95F, 0.14F, 7.6F, 0.14F, 0xFFD23F, lum);
    }

    private static void tasse(VertexConsumer c, PoseStack ps, int k, int lum) {
        int col = TASSES[k % TASSES.length], sombre = (col >> 1) & 0x7F7F7F | 0x404040;
        for (int i = 0; i < 2; i++) {
            ps.pushPose();
            ps.mulPose(Axis.YP.rotation(i * Mth.PI / 4));
            b(c, ps, 0, 0.06F, 0, 2.1F, 0.12F, 2.1F, col, lum);
            ps.popPose();
        }
        for (int i = 0; i < 8; i++) {
            ps.pushPose();
            ps.mulPose(Axis.YP.rotation(i * Mth.PI / 4));
            b(c, ps, 0, 0.68F, 1.22F, 1.06F, 1.2F, 0.16F, col, lum);
            b(c, ps, 0, 1.3F, 1.27F, 1.12F, 0.12F, 0.2F, 0xFFFFFF, lum);
            ps.popPose();
        }
        b(c, ps, 1.6F, 0.85F, 0, 0.25F, 0.8F, 0.25F, col, lum);
        for (int s = -1; s <= 1; s += 2) {
            b(c, ps, 0, 0.35F, s * 0.72F, 1.5F, 0.7F, 0.5F, sombre, lum);
        }
        b(c, ps, 0, 0.5F, 0, 0.24F, 0.8F, 0.24F, 0xBBBBBB, lum);
        b(c, ps, 0, 0.94F, 0, 0.9F, 0.1F, 0.9F, 0xFFFFFF, lum);
    }

    /** Le bateau : ses mesures sont celles de la maquette (x le long du bateau, z en travers) ; ici z est l'avant, x la gauche. */
    private static void bw(VertexConsumer c, PoseStack ps, float x, float y, float z, float w, float h, float d, int rgb, int lum) {
        b(c, ps, -z, y, x, d, h, w, rgb, lum);
    }

    private static void bateau(VertexConsumer c, PoseStack ps, int lum) {
        int bois = 0x7A4A24, bois2 = 0x5A3418, rouge = 0xC0392B;
        for (int sz = -1; sz <= 1; sz += 2) {
            for (int sx = -1; sx <= 1; sx += 2) {
                barre(c, ps, -sz * 3.6F, 0, 0, -sz * 3.6F, -12.6F, sx * 4.5F, 0.28F, rouge, lum);
            }
        }
        bw(c, ps, 0, -16.2F, 0, 12, 1, 2.4F, bois2, lum);
        bw(c, ps, 0, -15.2F, 0, 16, 1, 4.6F, bois, lum);
        bw(c, ps, 0, -14.0F, 0, 19, 1.4F, 6.2F, bois, lum);
        bw(c, ps, 0, -13.0F, 0, 20, 0.6F, 6.8F, rouge, lum);
        bw(c, ps, 0, -12.55F, 0, 20.2F, 0.3F, 7, 0xFFD23F, lum);
        bw(c, ps, 10.6F, -11.4F, 0, 2.6F, 2.2F, 5.6F, bois, lum);
        bw(c, ps, -10.6F, -10.8F, 0, 2.8F, 3.4F, 6, bois, lum);
        bw(c, ps, 12.4F, -11.0F, 0, 1.4F, 1.2F, 1.2F, 0xEFEADC, lum);
        bw(c, ps, 12.9F, -11.1F, 0.35F, 0.3F, 0.3F, 0.1F, 0x111111, lum);
        bw(c, ps, 12.9F, -11.1F, -0.35F, 0.3F, 0.3F, 0.1F, 0x111111, lum);
        bw(c, ps, -10.6F, -6.2F, 0, 0.18F, 6, 0.18F, 0xDDDDDD, lum);
        bw(c, ps, -10.6F, -4.2F, 1.2F, 0.1F, 1.6F, 2.4F, 0x1B1B1B, lum);
        bw(c, ps, -10.6F, -4.1F, 1.2F, 0.12F, 0.6F, 0.6F, 0xEFEADC, lum);
        for (int sx = -1; sx <= 1; sx += 2) {
            for (int r = 1; r <= 4; r++) {
                float x = sx * (r * 2 - 0.6F);
                bw(c, ps, x, -12.1F, 0, 0.8F, 0.5F, 5.8F, 0x3D2A14, lum);
                bw(c, ps, x + sx * 0.4F, -11.4F, 0, 0.2F, 1.2F, 5.8F, 0x3D2A14, lum);
            }
        }
    }

    private static void toupie(VertexConsumer c, PoseStack ps, int lum) {
        b(c, ps, 0, 0, -0.4F, 12, 0.6F, 0.8F, 0x3A3A4A, lum);
        b(c, ps, 0, -0.45F, -0.4F, 12.2F, 0.12F, 1.0F, 0xFFD23F, Boites.PLEIN);
        for (int i = 0; i < 8; i++) {
            float x = -4.4F + i * 1.26F;
            b(c, ps, x, 1.0F, -0.45F, 1.1F, 2.2F, 0.25F, 0x2A2A3A, lum);
            b(c, ps, x, 0.35F, 0, 1.1F, 0.2F, 0.8F, 0x2A2A3A, lum);
            for (int s = -1; s <= 1; s += 2) {
                b(c, ps, x + s * 0.45F, 1.6F, 0.05F, 0.16F, 0.9F, 0.3F, 0xFFD23F, lum);
            }
            b(c, ps, x, 1.05F, 0.25F, 0.9F, 0.16F, 0.16F, 0xFFD23F, lum);
        }
    }

    @Override
    public ResourceLocation getTextureLocation(NacelleParc n) {
        return Boites.GRAIN;
    }

    @EventBusSubscriber(modid = Cabane.MODID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
    public static final class Enregistrement {
        @SubscribeEvent
        public static void rendus(EntityRenderersEvent.RegisterRenderers event) {
            event.registerEntityRenderer(Parc.NACELLE.get(), RenduNacelleParc::new);
            event.registerEntityRenderer(Parc.AUTO.get(), RenduAutoParc::new);
        }
    }
}
