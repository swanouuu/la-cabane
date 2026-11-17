package fr.cabane.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import fr.cabane.Cabane;
import fr.cabane.accessoire.Bulle;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import org.joml.Vector3f;
import top.theillusivec4.curios.api.SlotContext;
import top.theillusivec4.curios.api.client.CuriosRendererRegistry;
import top.theillusivec4.curios.api.client.ICurioRenderer;

/**
 * La bulle de verre (accessoire/Bulle) portée : une sphère de verre bleuté autour de la tête, plus opaque sur les bords
 * (comme un vrai verre vu de biais), un reflet blanc en haut, et un col en métal au cou. Dessinée à la main, sans modèle :
 * tout est en pixels dans le repère de la tête (x de -4 à 4, y de -8 en haut à 0 au cou, z = -4 le visage). Chargé
 * seulement si Curios est là.
 */
final class RenduBulle {
    private static final ResourceLocation BLANC = Cabane.id("textures/entity/bulle.png");
    /** Le centre de la tête, le rayon de la bulle (elle passe juste au-dessus des coins de la tête), la coupe sous le cou. */
    private static final float CY = -4, R = 7.3F, COUPE = 1.1F;
    private static final int MERIDIENS = 28, PARALLELES = 18;

    static void enregistrer() {
        CuriosRendererRegistry.register(BuiltInRegistries.ITEM.get(Bulle.ID), Rendu::new);
    }

    private static final class Rendu implements ICurioRenderer {
        @Override
        public <T extends LivingEntity, M extends EntityModel<T>> void render(ItemStack stack, SlotContext slot, PoseStack ps,
                RenderLayerParent<T, M> parent, MultiBufferSource buffers, int lumiere, float marche, float vitesseMarche,
                float partialTicks, float age, float lacetTete, float tangageTete) {
            if (!(parent.getModel() instanceof HumanoidModel<?> m)) {
                return;
            }
            LivingEntity e = slot.entity();
            int overlay = LivingEntityRenderer.getOverlayCoords(e, 0);
            ps.pushPose();
            m.head.translateAndRotate(ps);
            if (!e.getItemBySlot(EquipmentSlot.HEAD).isEmpty()) {
                // par-dessus un casque : un peu plus grande
                ps.translate(0, CY / 16F, 0);
                ps.scale(1.15F, 1.15F, 1.15F);
                ps.translate(0, -CY / 16F, 0);
            }
            ps.scale(1 / 16F, 1 / 16F, 1 / 16F);
            PoseStack.Pose pose = ps.last();
            col(pose, buffers.getBuffer(RenderType.entityCutoutNoCull(BLANC)), lumiere, overlay);
            VertexConsumer verre = buffers.getBuffer(RenderType.entityTranslucent(BLANC));
            sphere(pose, verre, lumiere, overlay);
            reflet(pose, verre, lumiere, overlay, -125, 24, 46, 11);
            reflet(pose, verre, lumiere, overlay, -78, 54, 60, 5);
            ps.popPose();
        }
    }

    /** Le point de la sphère à l'angle θ depuis le haut et φ autour (φ = -90° : devant le visage). */
    private static Vector3f point(float rayon, float theta, float phi) {
        float s = Mth.sin(theta);
        return new Vector3f(rayon * s * Mth.cos(phi), CY - rayon * Mth.cos(theta), rayon * s * Mth.sin(phi));
    }

    /** Le verre : bleuté et presque transparent de face, plus blanc et plus opaque vers les bords. */
    private static void sphere(PoseStack.Pose pose, VertexConsumer v, int lumiere, int overlay) {
        float max = (float) Math.acos((CY - COUPE) / R);
        for (int i = 0; i < PARALLELES; i++) {
            float t0 = max * i / PARALLELES, t1 = max * (i + 1) / PARALLELES;
            for (int j = 0; j < MERIDIENS; j++) {
                float p0 = Mth.TWO_PI * j / MERIDIENS, p1 = Mth.TWO_PI * (j + 1) / MERIDIENS;
                verre(pose, v, point(R, t0, p0), lumiere, overlay);
                verre(pose, v, point(R, t1, p0), lumiere, overlay);
                verre(pose, v, point(R, t1, p1), lumiere, overlay);
                verre(pose, v, point(R, t0, p1), lumiere, overlay);
            }
        }
    }

    private static void verre(PoseStack.Pose pose, VertexConsumer v, Vector3f p, int lumiere, int overlay) {
        Vector3f n = new Vector3f(p.x, p.y - CY, p.z).normalize();
        // vu de biais, le verre se voit plus : le bord de la bulle est plus marqué que son milieu
        Vector3f vue = pose.pose().transformPosition(p, new Vector3f()).normalize();
        Vector3f nv = pose.normal().transform(n, new Vector3f()).normalize();
        float biais = 1 - Math.abs(vue.dot(nv));
        biais = Float.isNaN(biais) ? 0.5F : biais * biais;
        int a = (int) Mth.clamp(38 + 175 * biais, 38, 200);
        int r = (int) (190 + 55 * biais), g = (int) (228 + 22 * biais);
        v.addVertex(pose, p.x, p.y, p.z).setColor(r, g, 255, a).setUv(0.5F, 0.5F).setOverlay(overlay).setLight(lumiere)
                .setNormal(pose, n.x, n.y, n.z);
    }

    /** Un reflet blanc collé au verre, un peu devant : une tache allongée entre θ0 et θ1, centrée sur φ (en degrés). */
    private static void reflet(PoseStack.Pose pose, VertexConsumer v, int lumiere, int overlay, float phi, float theta0, float theta1, float largeur) {
        int n = 5;
        for (int i = 0; i < n; i++) {
            float ta = (theta0 + (theta1 - theta0) * i / n) * Mth.DEG_TO_RAD, tb = (theta0 + (theta1 - theta0) * (i + 1) / n) * Mth.DEG_TO_RAD;
            // plus fin aux deux bouts
            float la = largeur * Mth.sin(Mth.PI * i / n) + 1.5F, lb = largeur * Mth.sin(Mth.PI * (i + 1) / n) + 1.5F;
            Vector3f[] q = {point(R + 0.06F, ta, (phi - la / 2) * Mth.DEG_TO_RAD), point(R + 0.06F, tb, (phi - lb / 2) * Mth.DEG_TO_RAD),
                point(R + 0.06F, tb, (phi + lb / 2) * Mth.DEG_TO_RAD), point(R + 0.06F, ta, (phi + la / 2) * Mth.DEG_TO_RAD)};
            for (Vector3f p : q) {
                Vector3f no = new Vector3f(p.x, p.y - CY, p.z).normalize();
                v.addVertex(pose, p.x, p.y, p.z).setColor(255, 255, 255, 185).setUv(0.5F, 0.5F).setOverlay(overlay)
                        .setLight(0xF000F0).setNormal(pose, no.x, no.y, no.z);
            }
        }
    }

    /** Le col en métal, au cou, là où la bulle s'arrête : un anneau gris, une ligne plus sombre au milieu. */
    private static void col(PoseStack.Pose pose, VertexConsumer v, int lumiere, int overlay) {
        float rayon = (float) Math.sqrt(R * R - (COUPE - CY) * (COUPE - CY));
        float ext = rayon + 0.45F, inte = rayon - 0.7F, y0 = COUPE - 0.5F, y1 = COUPE + 1.0F;
        int segments = MERIDIENS;
        for (int j = 0; j < segments; j++) {
            float a0 = Mth.TWO_PI * j / segments, a1 = Mth.TWO_PI * (j + 1) / segments;
            float c0 = Mth.cos(a0), s0 = Mth.sin(a0), c1 = Mth.cos(a1), s1 = Mth.sin(a1);
            float cm = Mth.cos((a0 + a1) / 2), sm = Mth.sin((a0 + a1) / 2);
            // dehors : deux bandes, la claire en haut, la sombre en bas
            float ym = (y0 + y1) / 2;
            quad(pose, v, lumiere, overlay, 0xFFC4C9D2, cm, 0, sm, ext * c0, y0, ext * s0, ext * c0, ym, ext * s0, ext * c1, ym, ext * s1, ext * c1, y0, ext * s1);
            quad(pose, v, lumiere, overlay, 0xFF7C828E, cm, 0, sm, ext * c0, ym, ext * s0, ext * c0, y1, ext * s0, ext * c1, y1, ext * s1, ext * c1, ym, ext * s1);
            // dessus, dessous, dedans
            quad(pose, v, lumiere, overlay, 0xFFDDE1E8, 0, -1, 0, inte * c0, y0, inte * s0, ext * c0, y0, ext * s0, ext * c1, y0, ext * s1, inte * c1, y0, inte * s1);
            quad(pose, v, lumiere, overlay, 0xFF5A606A, 0, 1, 0, inte * c0, y1, inte * s0, inte * c1, y1, inte * s1, ext * c1, y1, ext * s1, ext * c0, y1, ext * s0);
            quad(pose, v, lumiere, overlay, 0xFF4A4F58, -cm, 0, -sm, inte * c0, y0, inte * s0, inte * c1, y0, inte * s1, inte * c1, y1, inte * s1, inte * c0, y1, inte * s0);
        }
    }

    private static void quad(PoseStack.Pose pose, VertexConsumer v, int lumiere, int overlay, int argb, float nx, float ny, float nz, float... xyz) {
        for (int k = 0; k < 4; k++) {
            v.addVertex(pose, xyz[k * 3], xyz[k * 3 + 1], xyz[k * 3 + 2]).setColor(argb).setUv(0.5F, 0.5F).setOverlay(overlay).setLight(lumiere)
                    .setNormal(pose, nx, ny, nz);
        }
    }

    private RenduBulle() {
    }
}
