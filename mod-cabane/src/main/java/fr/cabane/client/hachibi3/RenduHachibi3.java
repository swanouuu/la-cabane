package fr.cabane.client.hachibi3;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import fr.cabane.Cabane;
import fr.cabane.hachibi3.Hachibi3;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ViewportEvent;
import org.joml.Quaternionf;

/**
 * Le dessin du Hachibi géant : le modèle de la maquette (ModeleHachibi), toujours bien éclairé pour qu'on le voie dans le
 * noir, qui respire, regarde un peu autour de lui et ondule des tentacules ; quand il rugit, sa tête se lève, sa gueule
 * s'ouvre, ses tentacules s'agitent, ses chaînes se tendent et la caméra de ceux qui sont autour tremble.
 */
public class RenduHachibi3 extends EntityRenderer<Hachibi3> {
    static final ResourceLocation GRAIN = Cabane.id("textures/entity/hachibi_geant.png");
    private static final int LUMIERE = LightTexture.pack(13, 15);

    public RenduHachibi3(EntityRendererProvider.Context context) {
        super(context);
        shadowRadius = 0;
    }

    @Override
    public ResourceLocation getTextureLocation(Hachibi3 bete) {
        return GRAIN;
    }

    @Override
    public void render(Hachibi3 bete, float lacet, float partiel, PoseStack ps, MultiBufferSource buffers, int lumiere) {
        VertexConsumer c = buffers.getBuffer(RenderType.entityCutoutNoCull(GRAIN));
        double t = (bete.level().getGameTime() + partiel) / 20.0;
        float cri = bete.cri(partiel);
        ps.pushPose();
        ps.mulPose(Axis.YP.rotationDegrees(-lacet));
        // le corps respire, et se soulève un peu quand il rugit
        ps.pushPose();
        ps.translate(0, Math.sin(t * 0.9) * 0.25 + cri * 0.5, 0);
        dessiner(c, ps, ModeleHachibi.CORPS);
        // la tête regarde un peu autour d'elle ; quand il rugit, elle se lève et tremble
        Vec3 pt = ModeleHachibi.PIVOT_TETE;
        ps.translate(pt.x, pt.y, pt.z);
        ps.mulPose(Axis.YP.rotationDegrees((float) (7 * Math.sin(t * 0.23) + 4 * cri * Math.sin(t * 23))));
        ps.mulPose(Axis.XP.rotationDegrees((float) (3 * Math.sin(t * 0.31)) - 24 * cri));
        ps.translate(-pt.x, -pt.y, -pt.z);
        dessiner(c, ps, ModeleHachibi.TETE);
        Vec3 ch = ModeleHachibi.CHARNIERE;
        ps.translate(ch.x, ch.y, ch.z);
        ps.mulPose(Axis.XP.rotationDegrees(4 + 30 * cri));
        ps.translate(-ch.x, -ch.y, -ch.z);
        dessiner(c, ps, ModeleHachibi.MACHOIRE);
        ps.popPose();
        // les bras, posés sur leurs poings
        for (ModeleHachibi.Partie bras : ModeleHachibi.BRAS) {
            dessiner(c, ps, bras);
        }
        // les tentacules ondulent autour de leur racine, et s'agitent quand il rugit
        for (int i = 0; i < ModeleHachibi.TENTACULES.length; i++) {
            Vec3 r = ModeleHachibi.RACINES[i], cote = ModeleHachibi.COTES[i];
            double angle = 2.5 * Math.sin(t * 0.5 + i) + 6 * cri * Math.sin(t * 3 + i * 0.7);
            ps.pushPose();
            ps.translate(r.x, r.y, r.z);
            ps.mulPose(Axis.YP.rotationDegrees((float) (2.5 * Math.sin(t * 0.37 + i * 1.3))));
            ps.mulPose(new Quaternionf().rotateAxis((float) Math.toRadians(angle), (float) cote.x, 0, (float) cote.z));
            ps.translate(-r.x, -r.y, -r.z);
            dessiner(c, ps, ModeleHachibi.TENTACULES[i]);
            ps.popPose();
        }
        // les chaînes : des menottes et du collier jusqu'aux piquets plantés dans le sol (tendues quand il rugit)
        PoseStack.Pose pose = ps.last();
        for (int k = 0; k < ModeleHachibi.PIQUETS.length; k++) {
            Vec3 p = ModeleHachibi.PIQUETS[k];
            boite(c, pose, p.add(0, 1.5, 0), 1.3, 3.0, 1.3, ModeleHachibi.FER);
            boite(c, pose, p.add(0, 4.6, 0), 1.0, 0.3, 1.0, ModeleHachibi.FER_CLAIR);
            Vec3 debut = ModeleHachibi.CHAINES_DEBUT[k].add(0, k >= 2 ? Math.sin(t * 0.9) * 0.25 + cri * 0.5 : 0, 0);
            chaine(c, pose, debut, p.add(0, 4.6, 0), 1 - 0.8 * cri);
        }
        ps.popPose();
        super.render(bete, lacet, partiel, ps, buffers, lumiere);
    }

    private static void dessiner(VertexConsumer c, PoseStack ps, ModeleHachibi.Partie partie) {
        PoseStack.Pose pose = ps.last();
        float[] d = partie.d;
        int[] couleurs = partie.c;
        for (int q = 0; q < couleurs.length; q++) {
            int o = q * 23;
            for (int k = 0; k < 4; k++) {
                int s = o + k * 5;
                c.addVertex(pose, d[s], d[s + 1], d[s + 2]).setColor(couleurs[q]).setUv(d[s + 3], d[s + 4]).setOverlay(OverlayTexture.NO_OVERLAY)
                        .setLight(LUMIERE).setNormal(pose, d[o + 20], d[o + 21], d[o + 22]);
            }
        }
    }

    /** Une chaîne de gros maillons de fer, de a à b, qui pend un peu (creux de 0 à 1). */
    private static void chaine(VertexConsumer c, PoseStack.Pose pose, Vec3 a, Vec3 b, double creux) {
        double longueur = a.distanceTo(b), fleche = longueur * 0.07 * creux;
        int n = Math.max(2, (int) Math.ceil(longueur / 2.1));
        for (int k = 0; k < n; k++) {
            double t = (k + 0.5) / n;
            Vec3 p = sur(a, b, fleche, t), d = sur(a, b, fleche, t + 0.01).subtract(sur(a, b, fleche, t - 0.01)).normalize();
            Vec3 e1 = d.cross(new Vec3(0, 1, 0));
            if (e1.lengthSqr() < 1e-4) {
                e1 = d.cross(new Vec3(1, 0, 0));
            }
            e1 = e1.normalize();
            // un maillon sur deux est tourné d'un quart de tour
            Vec3 e = k % 2 == 0 ? e1 : d.cross(e1).normalize();
            double demi = longueur / n * 0.66, large = 0.75, barre = 0.2;
            for (int s = -1; s <= 1; s += 2) {
                maillon(c, pose, p.add(e.scale(s * (large - barre))), d, e, demi, barre);
                maillon(c, pose, p.add(d.scale(s * (demi - barre))), e, d, large, barre);
            }
        }
    }

    private static Vec3 sur(Vec3 a, Vec3 b, double fleche, double t) {
        return a.lerp(b, t).subtract(0, fleche * 4 * t * (1 - t), 0);
    }

    /** Un barreau de maillon, long de 2 × demi le long de d. */
    private static void maillon(VertexConsumer c, PoseStack.Pose pose, Vec3 centre, Vec3 d, Vec3 e, double demi, double barre) {
        Vec3 f = d.cross(e).normalize();
        boiteOrientee(c, pose, centre, e, d, f, barre, demi, barre, 0xFF55555F);
    }

    private static void boite(VertexConsumer c, PoseStack.Pose pose, Vec3 centre, double hx, double hy, double hz, int couleur) {
        boiteOrientee(c, pose, centre, new Vec3(1, 0, 0), new Vec3(0, 1, 0), new Vec3(0, 0, 1), hx, hy, hz, couleur);
    }

    /** Un pavé plein, d'une seule couleur : son centre, ses trois axes et ses demi-tailles. */
    private static void boiteOrientee(VertexConsumer c, PoseStack.Pose pose, Vec3 o, Vec3 x, Vec3 y, Vec3 z, double hx, double hy, double hz, int couleur) {
        Vec3[] axes = {x, y, z};
        double[] demis = {hx, hy, hz};
        for (int a = 0; a < 3; a++) {
            Vec3 n = axes[a], u = axes[(a + 1) % 3], v = axes[(a + 2) % 3];
            double hn = demis[a], hu = demis[(a + 1) % 3], hv = demis[(a + 2) % 3];
            for (int s = -1; s <= 1; s += 2) {
                Vec3 cf = o.add(n.scale(s * hn));
                Vec3[] coins = {cf.add(u.scale(-hu)).add(v.scale(-hv)), cf.add(u.scale(hu)).add(v.scale(-hv)), cf.add(u.scale(hu)).add(v.scale(hv)),
                        cf.add(u.scale(-hu)).add(v.scale(hv))};
                for (Vec3 p : coins) {
                    c.addVertex(pose, (float) p.x, (float) p.y, (float) p.z).setColor(couleur).setUv(0.1F, 0.1F).setOverlay(OverlayTexture.NO_OVERLAY)
                            .setLight(LUMIERE).setNormal(pose, (float) (n.x * s), (float) (n.y * s), (float) (n.z * s));
                }
            }
        }
    }

    /** La secousse de son cri, pour ceux qui sont autour (plus forte de près). */
    @EventBusSubscriber(modid = Cabane.MODID, value = Dist.CLIENT)
    public static final class Secousse {
        @SubscribeEvent
        public static void angles(ViewportEvent.ComputeCameraAngles event) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.level == null || mc.player == null) {
                return;
            }
            float partiel = (float) event.getPartialTick();
            double force = 0;
            for (Hachibi3 bete : mc.level.getEntitiesOfClass(Hachibi3.class, mc.player.getBoundingBox().inflate(Hachibi3.PORTEE_CRI))) {
                force = Math.max(force, bete.cri(partiel) * Math.max(0, 1 - bete.distanceTo(mc.player) / Hachibi3.PORTEE_CRI));
            }
            if (force <= 0) {
                return;
            }
            double t = (mc.level.getGameTime() + partiel) / 20.0;
            event.setPitch((float) (event.getPitch() + Math.sin(t * 47) * 1.6 * force));
            event.setYaw((float) (event.getYaw() + Math.cos(t * 39) * 1.6 * force));
            event.setRoll((float) (event.getRoll() + Math.sin(t * 31) * 1.2 * force));
        }

        private Secousse() {
        }
    }
}
