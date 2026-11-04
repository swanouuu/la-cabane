package fr.cabane.client.pouvoir;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import fr.cabane.Cabane;
import fr.cabane.pouvoir.Fruit;
import fr.cabane.pouvoir.Fruit3;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Le niveau 3 des fruits du démon chez chaque joueur (voir {@link Fruit3} ; docs/fruits/planche-niveau3.png,
 * planche-ultimes.png et les vidéos hyoton.mp4 et soleil.mp4) : sur le joueur, les plaques à pics et le casque en tête
 * de dragon (la texture du corps est dans ClientFruit) ; le dragon guidé au clic droit maintenu, et son explosion ; les
 * deux attaques ultimes (le Hyoton de la glace, le soleil de magma de la lave), dessinées en jeu au fil du temps comme
 * dans les vidéos ; les poses (les mains collées poussées en avant, la main levée), le viseur rouge du soleil, l'éclair
 * blanc de l'impact.
 * <p>
 * Les tailles des maquettes sont en pixels du modèle : ici, on les divise par 16 (des blocs).
 */
public final class ClientFruit3 {
    private static final ResourceLocation BLANC = Cabane.id("textures/marque/blanc.png");
    private static final RandomSource HASARD = RandomSource.create();
    private static final double B = 1 / 16.0;

    // les couleurs des maquettes (rendu.mjs)
    private static final float[] CROUTE = rgb(40, 24, 22), CROUTE_R = rgb(96, 36, 22), LAVE_R = rgb(222, 58, 16), LAVE_O = rgb(255, 116, 20),
            LAVE_J = rgb(255, 216, 80), CHAUD = rgb(255, 250, 214), GLACE = rgb(146, 206, 244), GLACE_C = rgb(214, 240, 255), BLANCHE = rgb(248, 252, 255),
            GLACE_P = rgb(90, 156, 224), GLACE_V = rgb(120, 236, 255), FUMEE = rgb(128, 122, 120);

    private static float[] rgb(int r, int g, int b) {
        return new float[]{r / 255F, g / 255F, b / 255F};
    }

    private static float[] mix(float[] a, float[] b, double k) {
        float t = (float) Mth.clamp(k, 0, 1);
        return new float[]{a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t, a[2] + (b[2] - a[2]) * t};
    }

    private static float[] avec(float[] c, float a) {
        return new float[]{c[0], c[1], c[2], a};
    }

    /** Le même hasard que les maquettes (moteur.mjs) : un nombre de 0 à 1 pour trois entiers. */
    static double hache(int i, int j, int k) {
        int h = i * 374761393 + j * 668265263 + k * 2147483647;
        h = (h ^ (h >>> 13)) * 1274126177;
        return ((h ^ (h >>> 16)) & 0xFFFFFFFFL) / 4294967296.0;
    }

    /** La distance aux bords des cellules (les fissures de la lave) : petite = sur une fissure (rendu.mjs). */
    static double fissure(double i, double j, int k, double taille) {
        int ci = Mth.floor(i / taille), cj = Mth.floor(j / taille);
        double d1 = 1e9, d2 = 1e9;
        for (int a = -1; a <= 1; a++) {
            for (int b = -1; b <= 1; b++) {
                double px = (ci + a + hache(ci + a, cj + b, k)) * taille, py = (cj + b + hache(ci + a, cj + b, k + 50)) * taille;
                double d = Math.hypot(i + 0.5 - px, j + 0.5 - py);
                if (d < d1) {
                    d2 = d1;
                    d1 = d;
                } else if (d < d2) {
                    d2 = d;
                }
            }
        }
        return d2 - d1;
    }

    private static double borne(double u) {
        return Mth.clamp(u, 0, 1);
    }

    private static double entre(double t, double a, double b) {
        return borne((t - a) / (b - a));
    }

    private static double sortie(double u) {
        u = borne(u);
        return 1 - (1 - u) * (1 - u) * (1 - u);
    }

    private static double entree(double u) {
        u = borne(u);
        return u * u;
    }

    private static Vec3 bezier(Vec3 a, Vec3 b, Vec3 c, Vec3 d, double u) {
        double v = 1 - u;
        return a.scale(v * v * v).add(b.scale(3 * v * v * u)).add(c.scale(3 * v * u * u)).add(d.scale(u * u * u));
    }

    private static Vec3 vec(Vector3f v) {
        return new Vec3(v.x, v.y, v.z);
    }

    // ================================================================ les modèles : le casque, les plaques, la tête du dragon

    private static Maillage casqueLave, casqueGlace, teteLave, teteGlace;
    private static final Maillage[][] PLAQUES_LAVE = new Maillage[2][], PLAQUES_GLACE = new Maillage[2][];
    private static boolean charge;

    private static Maillage premier(Maillage[] l) {
        return l == null || l.length == 0 ? null : l[0];
    }

    private static void charger() {
        if (charge) {
            return;
        }
        charge = true;
        casqueLave = premier(Maillage.lire(Cabane.id("fruit3/casque_lave.bin")));
        casqueGlace = premier(Maillage.lire(Cabane.id("fruit3/casque_glace.bin")));
        teteLave = premier(Maillage.lire(Cabane.id("fruit3/tete_lave.bin")));
        teteGlace = premier(Maillage.lire(Cabane.id("fruit3/tete_glace.bin")));
        for (int g = 0; g < 2; g++) {
            PLAQUES_LAVE[g] = Maillage.lire(Cabane.id("fruit3/plaques_lave_" + g + ".bin"));
            PLAQUES_GLACE[g] = Maillage.lire(Cabane.id("fruit3/plaques_glace_" + g + ".bin"));
        }
    }

    /** De 0 à 1 (un peu au-delà au milieu : ça jaillit, puis ça se pose) quand l'avancée va de « de » à « a ». */
    private static double croissance(double avance, double de, double a) {
        double k = borne((avance - de) / (a - de));
        return k * (1 + 0.3 * Math.sin(k * Math.PI));
    }

    /**
     * Sur le joueur au niveau 3 (appelé par ClientFruit.Couche, après la texture du corps) : les plaques à pics poussent
     * quand la matière arrive (avance de 0 à 1), puis le casque en tête de dragon se forme. Par-dessus une armure, les
     * plaques sont celles qui ressortent d'un pixel (comme la matière).
     */
    static void armure(PoseStack ps, MultiBufferSource buffers, int lumiere, AbstractClientPlayer joueur, PlayerModel<AbstractClientPlayer> modele,
            boolean glace, int dessous, double avance) {
        charger();
        VertexConsumer c = buffers.getBuffer(RenderType.entityCutoutNoCull(BLANC));
        boolean casque = !joueur.getItemBySlot(EquipmentSlot.HEAD).isEmpty(), torse = !joueur.getItemBySlot(EquipmentSlot.CHEST).isEmpty();
        boolean jambes = !joueur.getItemBySlot(EquipmentSlot.LEGS).isEmpty() || !joueur.getItemBySlot(EquipmentSlot.FEET).isEmpty();
        ModelPart[] parties = {modele.head, modele.body, modele.rightArm, modele.leftArm, modele.rightLeg, modele.leftLeg};
        boolean[] armees = {casque, torse, torse, torse, jambes, jambes};
        Maillage[][] plaques = glace ? PLAQUES_GLACE : PLAQUES_LAVE;
        float kp = (float) croissance(avance, 0.25, 0.65);
        if (kp > 0) {
            for (int i = 0; i < 6; i++) {
                Maillage[] l = plaques[armees[i] ? 1 : 0];
                if (l == null || i >= l.length) {
                    continue;
                }
                ps.pushPose();
                parties[i].translateAndRotate(ps);
                ps.scale(kp, kp, kp);
                l[i].dessiner(c, ps.last(), lumiere, dessous, 1 / 16F);
                ps.popPose();
            }
        }
        Maillage tete = glace ? casqueGlace : casqueLave;
        float kc = (float) croissance(avance, 0.55, 1);
        if (kc > 0 && tete != null) {
            ps.pushPose();
            modele.head.translateAndRotate(ps);
            if (casque) {
                ps.translate(0, -1 / 16F, 0);
            }
            // il sort du milieu de la tête, un peu trop grand, puis se pose
            ps.translate(0, -4 / 16F, 0);
            ps.scale(kc, kc, kc);
            ps.translate(0, 4 / 16F, 0);
            tete.dessiner(c, ps.last(), lumiere, dessous, 1 / 16F);
            ps.popPose();
        }
    }

    // ================================================================ dessins dans le monde

    private static final class Types extends RenderType {
        /** Ce qui est plein (la sphère, les pics, le pylône, la tempête) : opaque ; ses couleurs portent leur ombre. */
        static final RenderType PLEIN = create("cabane_fruit3_plein", DefaultVertexFormat.POSITION_COLOR, VertexFormat.Mode.QUADS, 262144, false, false,
                CompositeState.builder().setShaderState(POSITION_COLOR_SHADER).setCullState(NO_CULL).setWriteMaskState(COLOR_DEPTH_WRITE)
                        .setDepthTestState(LEQUAL_DEPTH_TEST).createCompositeState(false));
        /** Ce qui est léger (les flammes, la brume, les anneaux, le sol brûlé ou gelé) : translucide. */
        static final RenderType VOILE = create("cabane_fruit3_voile", DefaultVertexFormat.POSITION_COLOR, VertexFormat.Mode.QUADS, 262144, false, true,
                CompositeState.builder().setShaderState(POSITION_COLOR_SHADER).setWriteMaskState(COLOR_WRITE).setTransparencyState(TRANSLUCENT_TRANSPARENCY)
                        .setCullState(NO_CULL).createCompositeState(false));

        private Types() {
            super("cabane_fruit3_rien", DefaultVertexFormat.POSITION, VertexFormat.Mode.QUADS, 256, false, false, () -> { }, () -> { });
        }
    }

    private static void sommet(VertexConsumer c, Matrix4f m, Vec3 p, float[] col, float a) {
        c.addVertex(m, (float) p.x, (float) p.y, (float) p.z).setColor(col[0], col[1], col[2], a);
    }

    private static void quad(VertexConsumer c, Matrix4f m, Vec3 p0, Vec3 p1, Vec3 p2, Vec3 p3, float[] col, float a) {
        sommet(c, m, p0, col, a);
        sommet(c, m, p1, col, a);
        sommet(c, m, p2, col, a);
        sommet(c, m, p3, col, a);
    }

    private static final float[] OMBRES = {1F, 0.55F, 0.82F, 0.82F, 0.7F, 0.7F};

    /** Un cube droit (tourné de « angle » autour de la verticale) de demi-côté s, chaque face un peu plus ou moins claire. */
    private static void cube(VertexConsumer c, Matrix4f m, Vec3 o, double s, double angle, float[] col, float a) {
        Vec3 x = new Vec3(Math.cos(angle), 0, Math.sin(angle)).scale(s), z = new Vec3(-Math.sin(angle), 0, Math.cos(angle)).scale(s), y = new Vec3(0, s, 0);
        boite(c, m, o, x, y, z, (f, i, j) -> col, a, 1);
    }

    /** Ce que vaut chaque case d'une face : la face (0 haut, 1 bas, 2 et 3 côtés x, 4 et 5 côtés z), sa case (i, j). */
    private interface Teinte {
        float[] de(int face, int i, int j);
    }

    /**
     * Une boîte de centre o, de demi-axes x, y, z (des vecteurs), chaque face coupée en n×n cases ; « teinte » donne la
     * couleur de chaque case, l'ombre de la face s'y ajoute.
     */
    private static void boite(VertexConsumer c, Matrix4f m, Vec3 o, Vec3 x, Vec3 y, Vec3 z, Teinte teinte, float a, int n) {
        Vec3[][] faces = {{y, x, z}, {y.scale(-1), x, z}, {x, y, z}, {x.scale(-1), y, z}, {z, x, y}, {z.scale(-1), x, y}};
        for (int f = 0; f < 6; f++) {
            Vec3 cen = o.add(faces[f][0]), u = faces[f][1], v = faces[f][2];
            for (int i = 0; i < n; i++) {
                for (int j = 0; j < n; j++) {
                    double u0 = -1 + 2.0 * i / n, u1 = -1 + 2.0 * (i + 1) / n, v0 = -1 + 2.0 * j / n, v1 = -1 + 2.0 * (j + 1) / n;
                    float[] col = teinte.de(f, i, j), ombre = new float[]{col[0] * OMBRES[f], col[1] * OMBRES[f], col[2] * OMBRES[f]};
                    quad(c, m, cen.add(u.scale(u0)).add(v.scale(v0)), cen.add(u.scale(u1)).add(v.scale(v0)), cen.add(u.scale(u1)).add(v.scale(v1)),
                            cen.add(u.scale(u0)).add(v.scale(v1)), col.length > 3 ? col : ombre, a);
                }
            }
        }
    }

    /** Une pointe (un pic de glace, un éclat), de la couleur « pied » à la couleur « bout ». */
    private static void pic(VertexConsumer c, Matrix4f m, Vec3 base, Vec3 dir, double longueur, double largeur, float[] pied, float[] bout, float a) {
        if (longueur > 1e-3 && dir.lengthSqr() > 1e-8) {
            GrandeFleche.pic(c, m, base, dir, longueur, largeur, avec(pied, a), avec(bout, a));
        }
    }

    private static void cristal(VertexConsumer c, Matrix4f m, Vec3 base, Vec3 dir, double longueur, double largeur) {
        pic(c, m, base, dir, longueur, largeur, GLACE, BLANCHE, 1);
    }

    /** Une flamme : jaune au pied, rouge et transparente au bout, un cœur plus clair dedans (à dessiner en VOILE). */
    private static void flamme(VertexConsumer c, Matrix4f m, Vec3 base, Vec3 dir, double longueur, double largeur) {
        if (longueur > 1e-3) {
            GrandeFleche.pic(c, m, base, dir, longueur, largeur, new float[]{1F, 0.78F, 0.22F, 0.95F}, new float[]{1F, 0.25F, 0.04F, 0.12F});
            GrandeFleche.pic(c, m, base, dir, longueur * 0.55, largeur * 0.5, new float[]{1F, 0.98F, 0.75F, 1F}, new float[]{1F, 0.75F, 0.25F, 0.4F});
        }
    }

    /** Un repère : x sur le côté, y vers le haut (autant que possible), pour un nez vers f. */
    private static Vec3[] repere(Vec3 f, Vec3 haut) {
        Vec3 h = haut == null ? new Vec3(0, 1, 0) : haut;
        Vec3 x = h.cross(f);
        if (x.lengthSqr() < 0.04) {
            x = new Vec3(0, 0, 1).cross(f);
        }
        x = x.normalize();
        return new Vec3[]{x, f.cross(x).normalize()};
    }

    /** La croûte de la lave ou la glace du corps du dragon, case par case (comme ecailles dans niveau3.mjs) ; le ventre brille. */
    private static float[] ecaille(boolean glace, int face, int i, int j, int n) {
        if (face == 1) {
            return glace ? mix(BLANCHE, GLACE_C, (j + n) % 2 == 0 ? 0.6 : 0.15) : mix(LAVE_O, LAVE_J, (j + n) % 2 == 0 ? 0.8 : 0.15);
        }
        double h = hache(i + n * 5, j + n * 3, face + 300);
        if (glace) {
            int reflet = Math.floorMod(i - j + n, 4);
            return reflet == 0 ? BLANCHE : mix(mix(GLACE_C, GLACE, 0.5 + 0.4 * h), GLACE_P, 0.3);
        }
        return h < 0.3 ? mix(LAVE_J, LAVE_O, h / 0.3) : h < 0.42 ? LAVE_R : mix(CROUTE, CROUTE_R, 0.2 + 0.5 * hache(i, j, n + 7));
    }

    /** Un anneau du corps : une boîte (de demi-tailles hx, hy, hz) en faces éclairées, la lave et le ventre brillent. */
    private static void anneau(VertexConsumer c, PoseStack.Pose pose, Vec3 o, Vec3 ax, Vec3 ay, Vec3 az, double hx, double hy, double hz, boolean glace, int n) {
        Vec3 x = ax.scale(hx), y = ay.scale(hy), z = az.scale(hz);
        Vec3[][] faces = {{y, x, z}, {y.scale(-1), x, z}, {x, y, z}, {x.scale(-1), y, z}, {z, x, y}, {z.scale(-1), x, y}};
        for (int f = 0; f < 6; f++) {
            Vec3 cen = o.add(faces[f][0]), u = faces[f][1], v = faces[f][2], nor = faces[f][0].normalize();
            for (int i = 0; i < 3; i++) {
                for (int j = 0; j < 3; j++) {
                    float[] col = ecaille(glace, f, i, j, n);
                    Vec3[] p = {cen.add(u.scale(-1 + 2 * i / 3.0)).add(v.scale(-1 + 2 * j / 3.0)), cen.add(u.scale(-1 + 2 * (i + 1) / 3.0)).add(v.scale(-1 + 2 * j / 3.0)),
                            cen.add(u.scale(-1 + 2 * (i + 1) / 3.0)).add(v.scale(-1 + 2 * (j + 1) / 3.0)), cen.add(u.scale(-1 + 2 * i / 3.0)).add(v.scale(-1 + 2 * (j + 1) / 3.0))};
                    for (Vec3 q : p) {
                        c.addVertex(pose, (float) q.x, (float) q.y, (float) q.z).setColor(col[0], col[1], col[2], 1F).setUv(0.5F, 0.5F)
                                .setOverlay(OverlayTexture.NO_OVERLAY).setLight(LightTexture.FULL_BRIGHT).setNormal(pose, (float) nor.x, (float) nor.y, (float) nor.z);
                    }
                }
            }
        }
    }

    /** Le rayon du corps au point u (0 la tête, 1 la queue) : un peu plus fin au cou, puis il s'affine ; « sortie » : pas en pointe. */
    private static double rayon(double R, double u, boolean sortie) {
        if (u < 0.15) {
            return R * (0.82 + 0.18 * (u / 0.15));
        }
        double k = Math.pow((u - 0.15) / 0.85, sortie ? 1.4 : 1.3);
        return R * (1 - (sortie ? 0.72 : 0.93) * k);
    }

    /**
     * Un dragon sans pattes : la tête (le modèle de jeu3.mjs) au premier point de pts, puis le corps d'anneaux qui se
     * chevauchent, jusqu'au dernier ; sa crête de flammes (ou de cristaux). R : le rayon du corps, en blocs ; haut : où
     * regarde le dessus de sa tête (null : le ciel).
     */
    private static void serpent(PoseStack ps, MultiBufferSource.BufferSource buffers, Vec3 cam, List<Vec3> pts, double R, boolean glace, boolean sortie,
            Vec3 haut, double t) {
        int n = pts.size();
        if (n < 2) {
            return;
        }
        charger();
        // après les particules, la lumière des entités est coupée : sans elle, le dragon serait jeté
        Minecraft.getInstance().gameRenderer.lightTexture().turnOnLightLayer();
        RenderType peau = RenderType.entityCutoutNoCull(BLANC);
        VertexConsumer c = buffers.getBuffer(peau);
        PoseStack.Pose pose = ps.last();
        List<Vec3[]> cretes = new ArrayList<>();
        for (int i = 0; i < n - 1; i++) {
            double u = i / (double) (n - 1), r = rayon(R, u, sortie);
            Vec3 a = pts.get(i), b = pts.get(i + 1), f = a.subtract(b);
            double len = f.length();
            if (len < 1e-4) {
                continue;
            }
            f = f.scale(1 / len);
            Vec3[] rep = repere(f, null);
            Vec3 o = a.add(b).scale(0.5).subtract(cam);
            anneau(c, pose, o, rep[0], rep[1], f, r, r * 0.74, len * 0.64, glace, i);
            anneau(c, pose, o, rep[0], rep[1], f, r * 0.74, r, len * 0.6, glace, i + 50);
            cretes.add(new Vec3[]{o.add(rep[1].scale(r * 0.9)), rep[1].add(f.scale(-0.55)), new Vec3(r, i, 0), rep[0]});
        }
        Maillage tete = glace ? teteGlace : teteLave;
        if (tete != null) {
            Vec3 f0 = pts.get(0).subtract(pts.get(1)).normalize();
            double k = R * 0.42;
            Vec3 o = pts.get(0).add(f0.scale(3.4 * k)).subtract(cam);
            Vec3[] rep = repere(f0, haut);
            ps.pushPose();
            ps.translate(o.x, o.y, o.z);
            ps.mulPose(new Quaternionf().setFromNormalized(new Matrix3f((float) rep[0].x, (float) rep[0].y, (float) rep[0].z, (float) rep[1].x, (float) rep[1].y,
                    (float) rep[1].z, (float) f0.x, (float) f0.y, (float) f0.z)));
            ps.scale((float) k, (float) k, (float) k);
            tete.dessiner(c, ps.last(), LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY, 1);
            ps.popPose();
        }
        buffers.endBatch(peau);
        Minecraft.getInstance().gameRenderer.lightTexture().turnOffLightLayer();
        // la crête du dos
        Matrix4f m = ps.last().pose();
        VertexConsumer v = buffers.getBuffer(glace ? Types.PLEIN : Types.VOILE);
        for (Vec3[] cr : cretes) {
            double r = cr[2].x;
            int i = (int) cr[2].y;
            if (glace) {
                cristal(v, m, cr[0], cr[1], r * (i % 2 == 1 ? 0.8 : 1.4), r * 0.5);
                if (i % 3 == 0) {
                    for (int s = -1; s <= 1; s += 2) {
                        cristal(v, m, cr[0].add(cr[3].scale(s * r * 0.8)).subtract(cr[1].scale(r * 0.5)), cr[3].scale(s).add(cr[1].scale(0.6)), r * 0.7, r * 0.32);
                    }
                }
            } else {
                double vie = 0.8 + 0.2 * Math.sin(t * 9 + i * 1.7);
                flamme(v, m, cr[0], cr[1].add(0, 0.3, 0), r * (1.1 + 0.9 * hache(i, 1, 360)) * vie, r * 0.62);
            }
        }
        buffers.endBatch(glace ? Types.PLEIN : Types.VOILE);
    }

    /** Une coquille d'ellipsoïde en cubes (de côté pas), centrée en c ; « teinte » donne la couleur de chaque cube (null : un trou). */
    private interface Remplissage {
        float[] de(Vec3 p, double d);
    }

    private static void coquille(VertexConsumer v, Matrix4f m, Vec3 cam, Vec3 c, double rx, double ry, double rz, double pas, Remplissage teinte) {
        int nx = (int) Math.ceil(rx / pas), ny = (int) Math.ceil(ry / pas), nz = (int) Math.ceil(rz / pas);
        double ep = pas * 1.8 / Math.min(rx, Math.min(ry, rz));
        for (int i = -nx; i <= nx; i++) {
            for (int j = -ny; j <= ny; j++) {
                for (int k = -nz; k <= nz; k++) {
                    double d = Math.sqrt(Mth.square(i * pas / rx) + Mth.square(j * pas / ry) + Mth.square(k * pas / rz));
                    if (d > 1 || d < 1 - ep) {
                        continue;
                    }
                    Vec3 p = c.add(i * pas, j * pas, k * pas);
                    float[] col = teinte.de(p, d);
                    if (col != null) {
                        cube(v, m, p.subtract(cam), pas / 2, 0, col, 1);
                    }
                }
            }
        }
    }

    // ================================================================ le dragon guidé

    private static final double R_DRAGON = 1.0, LONGUEUR_DRAGON = 24;

    /** Un dragon en vol : à qui il est, et le chemin de sa tête (de la main jusqu'à maintenant), tick après tick. */
    private static final class Dragon3 {
        final UUID joueur;
        final boolean glace;
        final List<Vec3> chemin = new ArrayList<>();
        long maj;

        Dragon3(UUID joueur, boolean glace) {
            this.joueur = joueur;
            this.glace = glace;
        }
    }

    private static final Map<Integer, Dragon3> DRAGONS = new HashMap<>();

    /** Là où un dragon a explosé : une boule de feu (ou une gerbe de glace) qui dure deux secondes. */
    private record Eclatement(Vec3 pos, boolean glace, long debut) {
    }

    private static final List<Eclatement> ECLATEMENTS = new ArrayList<>();

    public static void dragon(Fruit3.Dragon p) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return;
        }
        Vec3 tete = vec(p.tete());
        if (p.etat() == 1) {
            DRAGONS.remove(p.id());
            ECLATEMENTS.add(new Eclatement(tete, p.glace(), System.nanoTime()));
            ClientFruit.secouer(tete, 1.3F, 30);
            gerbeParticules(mc, tete, p.glace());
            return;
        }
        Dragon3 d = DRAGONS.get(p.id());
        if (d == null) {
            d = new Dragon3(p.joueur(), p.glace());
            Player j = mc.level.getPlayerByUUID(p.joueur());
            // il sort de sa main : le bout de la queue y reste accroché tant que le corps n'est pas sorti
            d.chemin.add(j != null ? j.getEyePosition().add(j.getLookAngle().scale(0.4)).add(0, -0.45, 0) : tete);
            DRAGONS.put(p.id(), d);
        }
        d.chemin.add(tete);
        d.maj = mc.level.getGameTime();
        // on ne garde que ce qu'il faut pour le corps
        double l = 0;
        for (int i = d.chemin.size() - 1; i > 0; i--) {
            l += d.chemin.get(i).distanceTo(d.chemin.get(i - 1));
            if (l > LONGUEUR_DRAGON + 4 && i > 1) {
                d.chemin.subList(0, i - 1).clear();
                break;
            }
        }
    }

    /** Les points du corps, de la tête (un peu en avance, le paquet suivant arrive) vers la queue, tous les « pas ». */
    private static List<Vec3> colonne(List<Vec3> chemin, Vec3 tete, double pas, double max) {
        List<Vec3> brut = new ArrayList<>();
        brut.add(tete);
        for (int i = chemin.size() - 1; i >= 0; i--) {
            brut.add(chemin.get(i));
        }
        List<Vec3> pts = new ArrayList<>();
        pts.add(tete);
        double reste = pas, fait = 0;
        for (int i = 1; i < brut.size() && fait < max; i++) {
            Vec3 a = brut.get(i - 1), b = brut.get(i);
            double l = a.distanceTo(b), s = 0;
            while (l - s >= reste && fait < max) {
                s += reste;
                fait += pas;
                pts.add(a.lerp(b, s / l));
                reste = pas;
            }
            reste -= l - s;
        }
        if (pts.size() < 2) {
            pts.add(brut.get(brut.size() - 1));
        }
        return pts;
    }

    // ================================================================ les attaques ultimes

    /** Une attaque ultime en cours : à qui, laquelle, depuis quand (temps du monde), le point visé, et pour le soleil, où et quand il est lancé. */
    private static final class Ultime3 {
        final UUID joueur;
        final boolean glace;
        final long debut;
        final Vec3 point;
        final int cible;
        Vec3 zone;
        Vec3 depart;
        double lance = -1;
        boolean flash;
        Vec3 cote;
        Vec3 avant;

        Ultime3(UUID joueur, boolean glace, long debut, Vec3 point, int cible) {
            this.joueur = joueur;
            this.glace = glace;
            this.debut = debut;
            this.point = point;
            this.cible = cible;
        }

        double t(Minecraft mc, float partiel) {
            return (mc.level.getGameTime() - debut + partiel) / 20.0;
        }
    }

    private static final List<Ultime3> ULTIMES = new ArrayList<>();
    /** Pour soi : l'ultime est-il déjà fait pendant cet éveil, et quand sera-t-il prêt (System.nanoTime) ; et le dragon. */
    private static boolean ultimeFaite;
    private static long pretDragon;
    /** La recharge de l'ultime, pour chaque fruit (la glace ne bloque pas la lave). */
    private static final Map<Boolean, Long> PRET_ULTIME = new HashMap<>();
    private static boolean glaceMoi;
    private static long eclairBlanc = -1;
    private static float forceEclair;

    public static void ultime(Fruit3.Ultime p) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return;
        }
        if (p.phase() == 0) {
            Ultime3 u = new Ultime3(p.joueur(), p.glace(), mc.level.getGameTime(), vec(p.point()), p.cible());
            Player j = mc.level.getPlayerByUUID(p.joueur());
            // le dragon du Hyoton plonge sur le côté, de profil pour celui qui le lance
            Vec3 d = j == null ? new Vec3(0, 0, 1) : u.point.subtract(j.position()).multiply(1, 0, 1);
            u.avant = d.lengthSqr() < 0.01 ? new Vec3(0, 0, 1) : d.normalize();
            u.cote = new Vec3(u.avant.z, 0, -u.avant.x);
            ULTIMES.removeIf(x -> x.joueur.equals(p.joueur()));
            ULTIMES.add(u);
            if (mc.player != null && p.joueur().equals(mc.player.getUUID())) {
                ultimeFaite = true;
                glaceMoi = p.glace();
                PRET_ULTIME.put(p.glace(), System.nanoTime() + Fruit3.RECHARGE_ULTIME * 50_000_000L);
            }
            return;
        }
        for (Ultime3 u : ULTIMES) {
            if (u.joueur.equals(p.joueur()) && !u.glace) {
                u.zone = vec(p.point());
                u.lance = u.t(mc, 0);
                u.depart = centreTenu(mc, u, 0);
            }
        }
    }

    /** Pour le panneau : l'ultime est-il prêt pour soi ? */
    static boolean ultimePrete() {
        return !ultimeFaite && System.nanoTime() >= PRET_ULTIME.getOrDefault(moiGlace(), 0L);
    }

    static double attenteUltime() {
        return Math.max(0, (PRET_ULTIME.getOrDefault(moiGlace(), 0L) - System.nanoTime()) / 1e9);
    }

    /** Le fruit du joueur de ce client (la glace ?). */
    private static boolean moiGlace() {
        Minecraft mc = Minecraft.getInstance();
        String s = mc.player == null ? null : ClientFruit.sorte(mc.player.getUUID());
        return s != null ? Fruit.glace(s) : glaceMoi;
    }

    static boolean ultimeFaite() {
        return ultimeFaite;
    }

    static double attenteDragon() {
        return Math.max(0, (pretDragon - System.nanoTime()) / 1e9);
    }

    /** Il s'endort : au prochain éveil, l'ultime pourra repartir (après sa recharge). */
    static void endormi() {
        ultimeFaite = false;
        if (envoye) {
            PacketDistributor.sendToServer(new Fruit3.Serpent(false));
            envoye = false;
        }
    }

    // ---------------------------------------------------------------- la glace : le Hyoton

    private static final double HAUT_PYLONE = 13;

    private static Vec3 boule(Ultime3 u) {
        return u.point.add(0, Fruit3.HAUT_BOULE, 0);
    }

    /** Le pylône de glace : une colonne à huit pans, des cristaux à sa base, une couronne en haut. */
    private static void pylone(VertexConsumer v, Matrix4f m, Vec3 cam, Vec3 p, double h) {
        if (h < 0.1) {
            return;
        }
        Vec3 o = p.add(0, h / 2, 0).subtract(cam);
        for (int k = 0; k < 2; k++) {
            double a = k * Math.PI / 4;
            Vec3 x = new Vec3(Math.cos(a), 0, Math.sin(a)), z = new Vec3(-Math.sin(a), 0, Math.cos(a));
            int graine = k;
            boite(v, m, o, x, new Vec3(0, h / 2, 0), z, (f, i, j) -> glaceCase(i + graine * 7, j, f + graine), 1, 4);
        }
        double base = Math.min(1, h / 5);
        for (int q = 0; q < 10; q++) {
            double a = q / 10.0 * Math.PI * 2 + 0.3;
            cristal(v, m, p.add(Math.cos(a) * 1.25, -0.1, Math.sin(a) * 1.25).subtract(cam), new Vec3(Math.cos(a) * 0.5, 1, Math.sin(a) * 0.5),
                    (1.6 + 1.6 * hache(q, 1, 501)) * base, 0.62);
        }
        for (int q = 0; q < 6; q++) {
            double a = q / 6.0 * Math.PI * 2;
            cristal(v, m, p.add(Math.cos(a) * 0.75, h - 0.5, Math.sin(a) * 0.75).subtract(cam), new Vec3(Math.cos(a) * 0.45, 1, Math.sin(a) * 0.45), 1.25, 0.44);
        }
    }

    /** La glace, case par case : des reflets en biais, du bleu plus profond par endroits. */
    private static float[] glaceCase(int i, int j, int k) {
        int reflet = Math.floorMod(i - j + 60, 7);
        if (reflet == 0) {
            return BLANCHE;
        }
        float[] c = mix(mix(GLACE_C, GLACE, 0.5), BLANCHE, 0.35 * hache(i >> 1, j >> 1, k + 4));
        return mix(c, GLACE_P, 0.26 + 0.22 * hache(i / 3, j / 3, k + 431));
    }

    private static void hyoton(PoseStack ps, MultiBufferSource.BufferSource buffers, Vec3 cam, Minecraft mc, Ultime3 u, float partiel) {
        double t = u.t(mc, partiel);
        Matrix4f m = ps.last().pose();
        Vec3 p = u.point, bo = boule(u);
        double fin = 1 - entre(t, 7.2, 8.6);
        // le sol gèle autour du point, puis dégèle
        VertexConsumer voile = buffers.getBuffer(Types.VOILE);
        double rf = 18.75 * sortie(entre(t, 2.88, 3.8));
        if (rf > 0.2) {
            for (int a = 0; a < 40; a++) {
                for (int r = 0; r < 8; r++) {
                    double a0 = a / 40.0 * Math.PI * 2, a1 = (a + 1) / 40.0 * Math.PI * 2, r0 = rf * r / 8, r1 = rf * (r + 1) / 8;
                    float[] col = hache(a, r, 514) < 0.3 ? GLACE : mix(GLACE_C, BLANCHE, 0.2 + 0.6 * hache(a, r, 515));
                    Vec3 c0 = p.add(0, 0.03, 0).subtract(cam);
                    quad(voile, m, c0.add(Math.cos(a0) * r0, 0, Math.sin(a0) * r0), c0.add(Math.cos(a1) * r0, 0, Math.sin(a1) * r0),
                            c0.add(Math.cos(a1) * r1, 0, Math.sin(a1) * r1), c0.add(Math.cos(a0) * r1, 0, Math.sin(a0) * r1), col, (float) (0.8 * fin));
                }
            }
        }
        // l'anneau de givre sous la cible, et sa colonne de lumière
        double ra = 2.9 * sortie(entre(t, 0.12, 0.42)), fa = 1 - entre(t, 2.6, 3);
        if (ra > 0.05 && fa > 0) {
            double[][] anneaux = {{ra, 40, 1.5}, {ra * 0.72, 30, -2}, {ra * 1.18, 48, 1.5}};
            for (double[] an : anneaux) {
                for (int q = 0; q < an[1]; q++) {
                    double a = q / an[1] * Math.PI * 2 + t * an[2];
                    cube(voile, m, p.add(Math.cos(a) * an[0], 0.06, Math.sin(a) * an[0]).subtract(cam), 0.1 * fa, a, q % 4 == 0 ? BLANCHE : GLACE_V, 0.95F);
                }
            }
            double lum = entre(t, 0.25, 0.4) * (1 - entre(t, 0.6, 0.9));
            for (double y = 0; lum > 0 && y < 32; y += 0.6) {
                cube(voile, m, p.add(0, y, 0).subtract(cam), 0.22 * lum * (1 - y / 44), y, mix(GLACE_V, BLANCHE, y / 32), 0.9F);
            }
        }
        // la brume de la tempête
        double hb = 212 * sortie(entre(t, 2.95, 4.4));
        for (double y = 0; y < Math.min(100, hb); y += 10) {
            double r = (5.6 + 2.5 * sortie(entre(t, 2.95, 3.7))) * (1.6 + y / 81) * fin;
            for (int q = 0; q < 14; q++) {
                double a0 = q / 14.0 * Math.PI * 2, a1 = (q + 1) / 14.0 * Math.PI * 2;
                Vec3 c0 = p.subtract(cam);
                quad(voile, m, c0.add(Math.cos(a0) * r, y + 10, Math.sin(a0) * r), c0.add(Math.cos(a1) * r, y + 10, Math.sin(a1) * r),
                        c0.add(Math.cos(a1) * r, y, Math.sin(a1) * r), c0.add(Math.cos(a0) * r, y, Math.sin(a0) * r), BLANCHE, 0.1F * (float) fin);
            }
        }
        // l'onde blanche au sol, à l'impact
        double ro = 32.5 * sortie(entre(t, 2.88, 3.6)), ao = 1 - entre(t, 3.2, 3.8);
        if (t >= 2.88 && ao > 0) {
            for (int q = 0; q < 70; q++) {
                double a = q / 70.0 * Math.PI * 2;
                cube(voile, m, p.add(Math.cos(a) * ro, 0.25, Math.sin(a) * ro).subtract(cam), 0.38 * ao, a, BLANCHE, 0.9F);
            }
        }
        buffers.endBatch(Types.VOILE);
        VertexConsumer v = buffers.getBuffer(Types.PLEIN);
        // le pylône, puis ses morceaux quand le dragon passe
        double hp = HAUT_PYLONE * sortie(entre(t, 0.5, 0.78));
        if (t < 2.5) {
            pylone(v, m, cam, p, hp);
        } else if (t < 3.3) {
            double c = entre(t, 2.5, 3.3);
            for (int q = 0; q < 18; q++) {
                double y = q / 18.0 * HAUT_PYLONE * (1 - c * 0.7) - 12.5 * c * c;
                if (y > 0) {
                    cube(v, m, p.add((hache(q, 1, 516) - 0.5) * 5 * c, y, (hache(q, 2, 516) - 0.5) * 5 * c).subtract(cam), 0.4, q, glaceCase(q, 0, 3), 1);
                }
            }
        }
        jets(v, m, cam, u, t);
        // la grosse boule : de la glace qui tourne en bandes, des pics qui en sortent
        double rb = 8.25 * sortie(entre(t, 1.5, 2.05)) * (1 - entre(t, 2.4, 2.85));
        if (rb > 0.25) {
            double tb = t;
            coquille(v, m, cam, bo, rb, rb, rb, Math.max(0.25, rb / 8), (q, d) -> {
                double x = q.x - bo.x, y = q.y - bo.y, z = q.z - bo.z, bande = Math.sin((Math.atan2(z, x) + y * 0.32 + tb * 5) * 3 + y * 0.64);
                return bande > 0.55 ? BLANCHE : bande > 0.05 ? mix(GLACE_C, GLACE_V, 0.4) : bande > -0.5 ? mix(GLACE, GLACE_C, 0.5) : mix(GLACE, GLACE_P, 0.45);
            });
            for (int q = 0; q < 16; q++) {
                Vec3 d = new Vec3(hache(q, 1, 505) - 0.5, hache(q, 2, 505) - 0.5, hache(q, 3, 505) - 0.5).normalize();
                cristal(v, m, bo.add(d.scale(rb * 0.85)).subtract(cam), d, rb * (0.4 + 0.3 * hache(q, 4, 505)), rb * 0.12);
            }
            for (int s = 0; s < 2; s++) {
                double inc = s == 0 ? 0.45 : -0.7;
                for (int q = 0; q < 40; q++) {
                    double a = q / 40.0 * Math.PI * 2 + t * 3 + s * 1.7, rr = rb * 1.4;
                    double x = Math.cos(a) * rr, y = Math.sin(a) * rr * Math.sin(inc), z = Math.sin(a) * rr * Math.cos(inc);
                    cube(v, m, bo.add(x, y * 0.5 + z * 0.2, z).subtract(cam), rb * 0.03, a, q % 3 == 0 ? GLACE_V : BLANCHE, 1);
                }
            }
        }
        // l'impact : une gerbe d'éclats énormes
        if (t >= 2.88) {
            double g = sortie(entre(t, 2.88, 3.15)), f2 = (1 - 0.5 * entre(t, 4, 6)) * fin;
            for (int q = 0; q < 46; q++) {
                double a = q / 46.0 * Math.PI * 2 + hache(q, 1, 517), k = 0.35 + 0.65 * hache(q, 2, 517);
                Vec3 d = new Vec3(Math.cos(a) * k, 0.3 + hache(q, 3, 517), Math.sin(a) * k).normalize();
                cristal(v, m, p.add(d.scale(0.75)).subtract(cam), d, (5.6 + 11.9 * hache(q, 4, 517)) * g * f2, 1 + 0.75 * hache(q, 5, 517));
            }
            tempete(v, m, cam, p, t, fin);
        }
        buffers.endBatch(Types.PLEIN);
        // le dragon : il sort de la boule par le côté, fait une boucle et plonge en diagonale sur la cible
        if (t >= 2.36 && t < 3.05) {
            double w = sortie(entre(t, 2.36, 2.88));
            Vec3 a0 = bo, a1 = bo.add(u.cote.scale(14.4)).add(0, 4.4, 0).add(u.avant.scale(-2.5)), a2 = p.add(u.cote.scale(11.9)).add(0, 22.5, 0).add(u.avant.scale(-1.25)),
                    a3 = p.add(u.cote.scale(3.9)).add(0, 7.4, 0);
            List<Vec3> pts = new ArrayList<>();
            for (int q = 0; q <= 8; q++) {
                pts.add(bezier(a0, a1, a2, a3, Math.max(0, w - q / 8.0 * Math.min(w, 0.85))));
            }
            if (w > 0.04) {
                serpent(ps, buffers, cam, pts, 1.75, true, false, null, t);
            }
        }
    }

    /** Les douze jets de glace : ils sortent du sol autour de la cible et montent en courbe jusqu'à la boule. */
    private static void jets(VertexConsumer v, Matrix4f m, Vec3 cam, Ultime3 u, double t) {
        double epais = 1 - entre(t, 2.15, 2.5);
        if (t < 1 || epais <= 0) {
            return;
        }
        Vec3 bo = boule(u);
        for (int q = 0; q < 12; q++) {
            double a = q / 12.0 * Math.PI * 2 + 0.26;
            Vec3 s = u.point.add(Math.cos(a) * 8.1, 0, Math.sin(a) * 8.1);
            Vec3 c1 = s.add(Math.cos(a) * 1.9, 11.9, Math.sin(a) * 1.9), c2 = bo.add(Math.cos(a) * 7.5, 2.5, Math.sin(a) * 7.5);
            double debut = 1.0 + 0.035 * q, w = sortie(entre(t, debut, debut + 0.6));
            if (w <= 0) {
                continue;
            }
            int n = Math.max(2, (int) Math.round(30 * w));
            for (int k = 0; k < n; k++) {
                Vec3 p0 = bezier(s, c1, c2, bo, w * k / n), p1 = bezier(s, c1, c2, bo, w * (k + 1) / n);
                double r = (3 + 4 * ((k + 1.0) / n)) * B * epais;
                Vec3 f = p1.subtract(p0);
                if (f.lengthSqr() < 1e-8) {
                    continue;
                }
                double len = f.length();
                f = f.scale(1 / len);
                Vec3[] rep = repere(f, null);
                int kk = k, qq = q;
                boite(v, m, p0.add(p1).scale(0.5).subtract(cam), rep[0].scale(r), rep[1].scale(r), f.scale(len * 0.62), (fa, i, j) -> glaceCase(i + qq * 7, j + kk, fa), 1, 1);
                if (k % 3 == 1) {
                    cube(v, m, p0.add((hache(q, k, 502) - 0.5) * 0.9, (hache(q, k, 503) - 0.5) * 0.9, (hache(q, k, 504) - 0.5) * 0.9).subtract(cam), 0.08, k,
                            BLANCHE, 1);
                }
            }
            if (w < 1) {
                Vec3 bout = bezier(s, c1, c2, bo, w);
                cristal(v, m, bout.subtract(cam), bout.subtract(bezier(s, c1, c2, bo, w * 0.97)), 1.25, 0.62);
            }
            double g = entre(t, debut, debut + 0.25) * (1 - entre(t, debut + 0.4, debut + 0.8));
            for (int k = 0; g > 0 && k < 6; k++) {
                double b = k / 6.0 * Math.PI * 2;
                cristal(v, m, s.subtract(cam), new Vec3(Math.cos(b) * 0.7, 1, Math.sin(b) * 0.7), g, 0.38);
            }
        }
    }

    /** La tempête de glace : une colonne qui tourne, faite d'éclats, d'un cœur blanc et de neige en spirale, à plus de 200 blocs. */
    private static void tempete(VertexConsumer v, Matrix4f m, Vec3 cam, Vec3 p, double t, double fin) {
        double H = 212 * sortie(entre(t, 2.95, 4.4)) * (0.3 + 0.7 * fin), r0 = (4.5 + 2.9 * sortie(entre(t, 2.95, 3.7))) * fin;
        if (H < 0.6 || r0 < 0.05) {
            return;
        }
        int n = 0;
        for (double y = 0.4; y < H; y += 1.625, n++) {
            double rr = r0 * (1 + y / 81.25), haut = y > H - 7.5 ? (H - y) / 7.5 : 1, g = 1 + y / 93.75;
            for (int q = 0; q < 9; q++) {
                double phi = q / 9.0 * Math.PI * 2 + y * 0.176 - t * 4 + hache(n, q, 506), k = 0.75 + 0.35 * hache(n, q, 507);
                cristal(v, m, p.add(Math.cos(phi) * rr * k, y, Math.sin(phi) * rr * k).subtract(cam), new Vec3(-Math.sin(phi), 0.8, Math.cos(phi)),
                        (2.1 + 1.9 * hache(n, q, 508)) * haut * g, (0.69 + 0.31 * hache(n, q, 509)) * g);
            }
            for (int q = 0; q < 3; q++) {
                double phi = q / 3.0 * Math.PI * 2 - y * 0.32 + t * 6;
                cube(v, m, p.add(Math.cos(phi) * rr * 0.32, y, Math.sin(phi) * rr * 0.32).subtract(cam), rr * 0.21, phi,
                        mix(BLANCHE, GLACE_V, hache(n, q, 510) * 0.5), 1);
                double psi = q / 3.0 * Math.PI * 2 + y * 0.48 - t * 7;
                cube(v, m, p.add(Math.cos(psi) * rr * 1.25, y, Math.sin(psi) * rr * 1.25).subtract(cam), 0.16 * g, psi, BLANCHE, 1);
            }
        }
        // en haut, elle s'étale comme un nuage
        if (H > 150) {
            double e = entre(H, 150, 212);
            coquille(v, m, cam, p.add(0, H, 0), 18.75 + 31.25 * e, 5.6 + 3.75 * e, 18.75 + 31.25 * e, 3.1,
                    (q, d) -> hache(Mth.floor(q.x), Mth.floor(q.z), 511) < 0.3 ? null : mix(BLANCHE, GLACE_C, 0.3 * d));
        }
        // au pied, des blocs de glace qui tournent
        for (int q = 0; q < 16; q++) {
            double phi = q / 16.0 * Math.PI * 2 - t * 3, rr = r0 * (1.5 + 0.5 * hache(q, 1, 512));
            cube(v, m, p.add(Math.cos(phi) * rr, 0.6 + 3.75 * hache(q, 2, 512), Math.sin(phi) * rr).subtract(cam), (0.15 + 0.2 * hache(q, 3, 512)) * fin, phi * 2,
                    glaceCase(q, 1, 9), 1);
        }
    }

    // ---------------------------------------------------------------- la lave : le soleil de magma

    /** Le rayon de la sphère : elle grossit petit à petit, tant que le magma arrive (48 blocs de large au bout). */
    private static double rayonSoleil(double t) {
        return 23.75 * Math.pow(entre(t, 0.75, 3.2), 0.85);
    }

    /** Le centre de la sphère au-dessus de sa main levée (avant qu'il la lance). */
    private static Vec3 centreTenu(Minecraft mc, Ultime3 u, float partiel) {
        Player j = mc.level.getPlayerByUUID(u.joueur);
        Vec3 pied = j == null ? u.point.add(0, 15, 0) : j.getPosition(partiel);
        return pied.add(0, 2.25 + rayonSoleil(u.t(mc, partiel)) + 0.9, 0);
    }

    /** Le centre de la sphère : tenue, puis lancée en cloche vers la zone visée (0,9 s, comme sur le serveur). */
    private static Vec3 centreSoleil(Minecraft mc, Ultime3 u, double t, float partiel) {
        if (u.zone == null || u.depart == null) {
            return centreTenu(mc, u, partiel);
        }
        double w = entree(entre(t, u.lance, u.lance + 0.9));
        Vec3 fin = u.zone.add(0, 3.75, 0), haut = u.depart.add(fin).scale(0.5).add(0, 8.75, 0);
        return bezier(u.depart, haut, haut, fin, w);
    }

    /** La surface de la sphère : de grandes plaques de croûte sombre qui glissent, des fissures qui brillent. */
    private static float[] surfaceSoleil(Vec3 c, double R, double t, Vec3 p) {
        double x = p.x - c.x, y = p.y - c.y, z = p.z - c.z, l = Math.max(1e-6, Math.sqrt(x * x + y * y + z * z));
        double u = (Math.atan2(z, x) + t * 0.5) * (R / 1.375), w = Math.acos(Mth.clamp(y / l, -1, 1)) * (R / 1.375);
        double f = fissure(u + 100, w + 100, 520, 4);
        if (f < 0.5) {
            return mix(CHAUD, LAVE_J, f / 0.5);
        }
        if (f < 0.95) {
            return mix(LAVE_J, LAVE_O, (f - 0.5) / 0.45);
        }
        if (f < 1.35) {
            return mix(LAVE_O, LAVE_R, (f - 0.95) / 0.4);
        }
        return mix(CROUTE, CROUTE_R, 0.3 + 0.5 * hache(Mth.floor(u * 2), Mth.floor(w * 2), 521));
    }

    /** La couleur du feu de l'explosion : blanc au cœur, puis jaune, orange, rouge, et de la fumée en refroidissant. */
    private static float[] feu(double k) {
        k = borne(k);
        if (k < 0.22) {
            return rgb(255, 252, 228);
        }
        if (k < 0.42) {
            return mix(rgb(255, 246, 190), LAVE_J, (k - 0.22) / 0.2);
        }
        if (k < 0.6) {
            return mix(LAVE_J, LAVE_O, (k - 0.42) / 0.18);
        }
        if (k < 0.74) {
            return mix(LAVE_O, LAVE_R, (k - 0.6) / 0.14);
        }
        if (k < 0.86) {
            return mix(rgb(150, 50, 26), rgb(84, 50, 42), (k - 0.74) / 0.12);
        }
        return mix(rgb(84, 66, 60), rgb(52, 46, 46), (k - 0.86) / 0.14);
    }

    private static double bruitV(Vec3 p, int n) {
        return hache(Mth.floor(p.x / 1.875), Mth.floor(p.y / 1.875) * 7 + Mth.floor(p.z / 1.875), n) - 0.5;
    }

    private static void soleil(PoseStack ps, MultiBufferSource.BufferSource buffers, Vec3 cam, Minecraft mc, Ultime3 u, float partiel) {
        double t = u.t(mc, partiel);
        Matrix4f m = ps.last().pose();
        double impact = u.lance < 0 ? 1e9 : u.lance + 0.9, R = rayonSoleil(t);
        Vec3 c = centreSoleil(mc, u, t, partiel);
        Vec3 sol = u.point;
        // le sol : il se fend sous lui pendant que le magma monte ; après l'impact, brûlé autour de la zone
        VertexConsumer voile = buffers.getBuffer(Types.VOILE);
        if (t > 0.75 && t < impact + 0.2) {
            double rc = 35 * entre(t, 0.75, 2.6);
            disque(voile, m, cam, sol, rc, 48, 10, (a, r) -> fissure(a * 1.3 + 600, r * 3 + 600, 534, 3) < 0.4 ? avec(LAVE_O, 0.9F) : avec(rgb(62, 46, 34), 0.45F));
        }
        if (u.zone != null && t >= impact) {
            double rb = 32.5 * sortie(entre(t, impact, impact + 1.05)), f = 1 - entre(t, impact + 6, impact + 7.5);
            disque(voile, m, cam, u.zone, rb, 56, 12, (a, r) -> r < 6 ? avec(mix(LAVE_O, LAVE_J, hache(a, r, 531)), (float) (0.95 * f))
                    : fissure(a * 1.6 + 300, r * 3 + 300, 532, 3) < 0.6 ? avec(LAVE_O, (float) (0.9 * f)) : avec(rgb(56, 42, 34), (float) (0.8 * f)));
        }
        // le viseur : pour celui qui lance, le cercle rouge là où la sphère tombera s'il la lâche maintenant
        if (mc.player != null && u.joueur.equals(mc.player.getUUID()) && u.zone == null && t > 1.2) {
            Vec3 z = visee(mc, partiel);
            if (z != null) {
                for (int q = 0; q < 64; q++) {
                    double a0 = q / 64.0 * Math.PI * 2, a1 = (q + 1) / 64.0 * Math.PI * 2;
                    Vec3 o = z.add(0, 0.08, 0).subtract(cam);
                    quad(voile, m, o.add(Math.cos(a0) * 11.3, 0, Math.sin(a0) * 11.3), o.add(Math.cos(a1) * 11.3, 0, Math.sin(a1) * 11.3),
                            o.add(Math.cos(a1) * 12.3, 0, Math.sin(a1) * 12.3), o.add(Math.cos(a0) * 12.3, 0, Math.sin(a0) * 12.3), rgb(255, 70, 40), 0.85F);
                }
            }
        }
        // la couronne de flammes et les braises autour de la sphère
        if (t < impact && R > 0.2) {
            for (int q = 0; q < 70; q++) {
                Vec3 d = new Vec3(hache(q, 1, 522) - 0.5, hache(q, 2, 522) - 0.5, hache(q, 3, 522) - 0.5).normalize();
                double vie = 0.8 + 0.2 * Math.sin(t * 8 + q);
                flamme(voile, m, c.add(d.scale(R * 0.93)).subtract(cam), d.add(0, 0.6, 0), R * (0.18 + 0.32 * hache(q, 4, 522)) * vie, R * 0.1);
            }
            for (int q = 0; q < 50; q++) {
                double a = q * 2.4 + t * 2, rr = R * (1.2 + 0.5 * hache(q, 1, 523)), y = (hache(q, 2, 523) - 0.5) * R * 2.2;
                cube(voile, m, c.add(Math.cos(a) * rr, y, Math.sin(a) * rr).subtract(cam), (R * 0.025 + 0.09) / 2, a, q % 3 == 0 ? LAVE_J : LAVE_O, 1);
            }
        }
        affluents(voile, m, cam, u, t, c);
        explosionVoile(voile, m, cam, u, t, impact);
        buffers.endBatch(Types.VOILE);
        VertexConsumer v = buffers.getBuffer(Types.PLEIN);
        if (t < impact && R > 0.2) {
            coquille(v, m, cam, c, R, R, R, Math.max(0.25, R / 15), (q, d) -> surfaceSoleil(c, R, t, q));
            // la traînée de feu et de fumée derrière la sphère lancée
            if (u.zone != null && t > u.lance) {
                for (int q = 1; q < 14; q++) {
                    Vec3 p = centreSoleil(mc, u, t - q * 0.04, partiel);
                    cube(v, m, p.subtract(cam), R * (0.5 - q * 0.03) / 2, q, q < 4 ? LAVE_J : q < 8 ? LAVE_O : mix(FUMEE, rgb(60, 50, 46), 0.4), 1);
                }
            }
        }
        explosionPleine(v, m, cam, u, t, impact);
        buffers.endBatch(Types.PLEIN);
    }

    private interface Sol {
        float[] de(int a, int r);
    }

    /** Un disque au sol (en secteurs × anneaux), chaque case de sa couleur (r, g, b, a). */
    private static void disque(VertexConsumer v, Matrix4f m, Vec3 cam, Vec3 c, double rayon, int secteurs, int anneaux, Sol sol) {
        if (rayon < 0.2) {
            return;
        }
        Vec3 o = c.add(0, 0.04, 0).subtract(cam);
        for (int a = 0; a < secteurs; a++) {
            for (int r = 0; r < anneaux; r++) {
                double a0 = a * Math.PI * 2 / secteurs, a1 = (a + 1) * Math.PI * 2 / secteurs, r0 = rayon * r / anneaux, r1 = rayon * (r + 1) / anneaux;
                float[] col = sol.de(a, r);
                quad(v, m, o.add(Math.cos(a0) * r0, 0, Math.sin(a0) * r0), o.add(Math.cos(a1) * r0, 0, Math.sin(a1) * r0),
                        o.add(Math.cos(a1) * r1, 0, Math.sin(a1) * r1), o.add(Math.cos(a0) * r1, 0, Math.sin(a0) * r1), col, col[3]);
            }
        }
    }

    /** Le magma qui monte du sol vers la sphère : des coulées en arc, l'une après l'autre, et des geysers là où elles partent. */
    private static void affluents(VertexConsumer v, Matrix4f m, Vec3 cam, Ultime3 u, double t, Vec3 c) {
        if (t < 0.75 || t > 3.45) {
            return;
        }
        for (int q = 0; q < 40; q++) {
            double debut = 0.75 + q / 40.0 * 2.2, w = entre(t, debut, debut + 0.9);
            if (w <= 0 || w >= 1) {
                continue;
            }
            double a = hache(q, 1, 524) * Math.PI * 2, d = 19 + 43 * hache(q, 2, 524);
            Vec3 s = u.point.add(Math.cos(a) * d, 0, Math.sin(a) * d);
            Vec3 c1 = s.add(0, 15 + 13.75 * hache(q, 3, 524), 0), c2 = c.add((s.x - c.x) * 0.4, 16.25, (s.z - c.z) * 0.3);
            double tete = sortie(w), queue = Math.max(0, tete - 0.32);
            for (int k = 0; k <= 14; k++) {
                double sk = queue + (tete - queue) * k / 14, r = (3 + 6 * k / 14.0) * B;
                Vec3 p = bezier(s, c1, c2, c, sk);
                cube(v, m, p.subtract(cam), r * 0.6, k + q, k % 5 == 2 ? mix(CROUTE, CROUTE_R, 0.6) : mix(LAVE_R, LAVE_J, k / 14.0), 1);
                if (k % 3 == 0) {
                    flamme(v, m, p.subtract(cam), new Vec3(0, 1, 0), r * 2.4, r * 1.1);
                }
            }
            double g = 1 - entre(w, 0.15, 0.55);
            for (int k = 0; g > 0 && k < 6; k++) {
                flamme(v, m, s.add((hache(q, k, 525) - 0.5) * 2.1, 0, (hache(q, k, 526) - 0.5) * 2.1).subtract(cam), new Vec3(0, 1, 0),
                        (2.25 + 2.5 * hache(q, k, 527)) * g, 0.8);
            }
        }
    }

    /** Ce qui est léger dans l'explosion : les flammes de la boule de feu, l'onde de poussière, les flammes au sol. */
    private static void explosionVoile(VertexConsumer v, Matrix4f m, Vec3 cam, Ultime3 u, double t, double impact) {
        if (u.zone == null || t < impact) {
            return;
        }
        double e = entre(t, impact, impact + 0.65), ch = entre(t, impact + 0.25, impact + 3.05), fin = 1 - entre(t, impact + 5.5, impact + 7.5);
        double rx = 43.75 * sortie(e), ry = rx * (0.85 - 0.35 * ch);
        Vec3 cb = u.zone.add(0, 2.5 + rx * 0.55 * (1 - 0.5 * ch), 0);
        for (int q = 0; q < 50; q++) {
            Vec3 d = new Vec3(hache(q, 1, 542) - 0.5, Math.abs(hache(q, 2, 542) - 0.3), hache(q, 3, 542) - 0.5).normalize();
            flamme(v, m, cb.add(d.x * rx * 0.9, d.y * ry * 0.9, d.z * rx * 0.9).subtract(cam), d, rx * (0.18 + 0.25 * hache(q, 4, 542)) * (1 - 0.6 * ch) * fin,
                    rx * 0.08);
        }
        double ro = 125 * sortie(entre(t, impact, impact + 1.35)), al = 1 - entre(t, impact + 0.95, impact + 2.25);
        for (int q = 0; al > 0 && q < 110; q++) {
            double a = q / 110.0 * Math.PI * 2, h = hache(q, 1, 546);
            cube(v, m, u.zone.add(Math.cos(a) * ro * (0.95 + 0.1 * h), 1.25 * h, Math.sin(a) * ro * (0.95 + 0.1 * h)).subtract(cam), (2.1 + 1.9 * h) * al / 2, a,
                    h < 0.3 ? LAVE_O : mix(rgb(160, 132, 108), rgb(118, 98, 84), h), (float) (0.85 * al));
        }
        for (int q = 0; q < 50; q++) {
            double a = hache(q, 1, 528) * Math.PI * 2, d = rx * (0.5 + 0.9 * hache(q, 2, 528)), vie = 0.85 + 0.15 * Math.sin(t * 7 + q);
            flamme(v, m, u.zone.add(Math.cos(a) * d, 0, Math.sin(a) * d).subtract(cam), new Vec3(0, 1, 0), (3.1 + 5 * hache(q, 3, 528)) * vie * fin, 1.6);
        }
    }

    /** La boule de feu, puis le champignon qui monte (le dessous rougeoie, le dessus fume), et les blocs de magma qui volent. */
    private static void explosionPleine(VertexConsumer v, Matrix4f m, Vec3 cam, Ultime3 u, double t, double impact) {
        if (u.zone == null || t < impact) {
            return;
        }
        double e = entre(t, impact, impact + 0.65), ch = entre(t, impact + 0.25, impact + 3.05), fin = 1 - entre(t, impact + 6, impact + 8);
        double rx = 43.75 * sortie(e) * (0.4 + 0.6 * fin), ry = rx * (0.85 - 0.35 * ch);
        Vec3 z = u.zone, cb = z.add(0, 2.5 + rx * 0.55 * (1 - 0.5 * ch), 0);
        if (rx > 0.5) {
            coquille(v, m, cam, cb, rx * 0.62, ry * 0.62, rx * 0.62, 2.5, (p, d) -> p.y < z.y - 0.5 ? null : feu(0.1 + 0.5 * ch));
            coquille(v, m, cam, cb, rx, ry, rx, 2.25, (p, d) -> p.y < z.y - 0.5 || bruitV(p, 540) > 0.3 ? null
                    : feu(0.3 + 0.35 * ch + (p.y - cb.y) / 137.5 + bruitV(p, 541) * 0.4));
        }
        if (ch > 0 && fin > 0) {
            double yc = (400 + 1700 * sortie(ch)) * B, rc = rx * (0.7 + 0.7 * sortie(ch));
            Vec3 cap = z.add(0, yc, 0);
            coquille(v, m, cam, cap, rc, rc * 0.42, rc, 2.75, (p, d) -> bruitV(p, 543) > 0.38 ? null : feu(0.45 + 0.4 * ch + (p.y - cap.y) / (rc * 0.6) + bruitV(p, 544) * 0.3));
            for (double y = cb.y - z.y; y < yc; y += 2.9) {
                double r = rx * (0.28 + 0.06 * Math.sin(y * 0.32)), yy = y;
                coquille(v, m, cam, z.add(0, y, 0), r, 1.75, r, 1.9, (p, d) -> feu(0.35 + 0.35 * ch + yy / yc * 0.25 + bruitV(p, 545) * 0.3));
            }
        }
        // des blocs de magma qui volent en cloche
        double s = (t - impact) * 1.3;
        for (int q = 0; q < 50; q++) {
            double a = hache(q, 1, 527) * Math.PI * 2, vit = 31 + 50 * hache(q, 2, 527), up = 31 + 44 * hache(q, 3, 527);
            Vec3 p = z.add(Math.cos(a) * vit * s, 1.9 + up * s - 32.5 * s * s, Math.sin(a) * vit * s);
            if (p.y > z.y) {
                double taille = (0.75 + hache(q, 4, 527)) / 2;
                cube(v, m, p.subtract(cam), taille, q + t * 3, hache(q, 5, 527) < 0.4 ? LAVE_O : mix(CROUTE, CROUTE_R, 0.5), 1);
            }
        }
    }

    /** Là où il vise (pour le viseur du soleil) : le bloc visé, ou le sol au bout de 80 blocs. */
    private static Vec3 visee(Minecraft mc, float partiel) {
        Player j = mc.player;
        Vec3 oeil = j.getEyePosition(partiel), loin = oeil.add(j.getViewVector(partiel).scale(80));
        HitResult r = mc.level.clip(new ClipContext(oeil, loin, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, j));
        if (r.getType() != HitResult.Type.MISS) {
            return r.getLocation();
        }
        int y = mc.level.getHeight(Heightmap.Types.MOTION_BLOCKING, Mth.floor(loin.x), Mth.floor(loin.z));
        return Math.abs(y - loin.y) < 30 ? new Vec3(loin.x, y, loin.z) : null;
    }

    // ---------------------------------------------------------------- l'explosion du dragon guidé

    private static void eclatement(MultiBufferSource.BufferSource buffers, Matrix4f m, Vec3 cam, Eclatement e) {
        double t = (System.nanoTime() - e.debut()) / 1e9, g = sortie(entre(t, 0, 0.25)), fin = 1 - entre(t, 1.2, 2);
        Vec3 p = e.pos();
        VertexConsumer v = buffers.getBuffer(Types.PLEIN);
        if (e.glace()) {
            for (int q = 0; q < 30; q++) {
                Vec3 d = new Vec3(hache(q, 1, 392) - 0.5, Math.abs(hache(q, 2, 392) - 0.35), hache(q, 3, 392) - 0.5).normalize();
                cristal(v, m, p.add(d.scale(0.6)).subtract(cam), d, (2.9 + 2.3 * hache(q, 4, 392)) * g * fin, 0.45 + 0.25 * hache(q, 5, 392));
            }
            for (int q = 0; q < 22; q++) {
                double a = q / 22.0 * Math.PI * 2;
                cristal(v, m, p.add(Math.cos(a) * 4.6, -1.2, Math.sin(a) * 4.6).subtract(cam), new Vec3(Math.cos(a) * 0.6, 1, Math.sin(a) * 0.6),
                        (0.9 + hache(q, 1, 390)) * 1.6 * g * fin, 0.4);
            }
        } else {
            for (int q = 0; q < 140; q++) {
                Vec3 d = new Vec3(hache(q, 1, 391) - 0.5, hache(q, 2, 391) - 0.5, hache(q, 3, 391) - 0.5).normalize();
                double k = Math.cbrt(hache(q, 4, 391));
                cube(v, m, p.add(d.scale(2.9 * k * g)).subtract(cam), (0.22 + 0.15 * hache(q, 5, 391)) * fin, q, feu(k * 0.8 + 0.4 * entre(t, 0.3, 1.8)), 1);
            }
            double s = t * 1.2;
            for (int q = 0; q < 24; q++) {
                double a = hache(q, 1, 393) * Math.PI * 2, vit = 6 + 6 * hache(q, 2, 393), up = 5 + 6 * hache(q, 3, 393);
                Vec3 c = p.add(Math.cos(a) * vit * s, up * s - 9 * s * s, Math.sin(a) * vit * s);
                cube(v, m, c.subtract(cam), 0.2 * fin, q + t * 4, hache(q, 4, 393) < 0.5 ? LAVE_O : CROUTE_R, 1);
            }
        }
        buffers.endBatch(Types.PLEIN);
        if (!e.glace()) {
            VertexConsumer f = buffers.getBuffer(Types.VOILE);
            for (int q = 0; q < 24; q++) {
                double a = q / 24.0 * Math.PI * 2;
                flamme(f, m, p.add(Math.cos(a) * 4.6, -1.2, Math.sin(a) * 4.6).subtract(cam), new Vec3(Math.cos(a) * 0.35, 1, Math.sin(a) * 0.35),
                        (0.6 + 0.8 * hache(q, 1, 394)) * 1.5 * g * fin, 0.35);
                Vec3 d = new Vec3(hache(q, 2, 394) - 0.5, Math.abs(hache(q, 3, 394) - 0.3), hache(q, 4, 394) - 0.5).normalize();
                flamme(f, m, p.add(d.scale(2.4 * g)).subtract(cam), d, (1 + 1.2 * hache(q, 5, 394)) * fin * g, 0.45);
            }
            buffers.endBatch(Types.VOILE);
        }
    }

    // ================================================================ accroupi : le chien de l'enfer, la capsule de glace

    /** Un chien de l'enfer : d'où il part, vers où, depuis quand ; où et quand il a mordu (tMorsure < 0 tant qu'il court). */
    private static final class Chien3 {
        final UUID joueur;
        final Vec3 depart;
        final Vec3 dir;
        final long debut;
        Vec3 morsure;
        double tMorsure = -1;

        Chien3(UUID joueur, Vec3 depart, Vec3 dir, long debut) {
            this.joueur = joueur;
            this.depart = depart;
            this.dir = dir.normalize();
            this.debut = debut;
        }

        double t(Minecraft mc, float partiel) {
            return (mc.level.getGameTime() - debut + partiel) / 20.0;
        }

        Vec3 tete(double t) {
            return morsure != null && t >= tMorsure ? morsure : depart.add(dir.scale(Fruit3.courseChien(t * 20)));
        }
    }

    /** Une capsule de glace : d'où partent les lignes, vers qui, depuis quand ; quand tout a éclaté (tEclat < 0 avant). */
    private static final class Capsule3 {
        final UUID joueur;
        final Vec3 centre;
        final List<Integer> cibles;
        final long debut;
        /** Les pieds de chaque cible, au dernier dessin (pour les éclats, même si elle est morte entre-temps). */
        final Map<Integer, Vec3> pieds = new HashMap<>();
        double tEclat = -1;

        Capsule3(UUID joueur, Vec3 centre, List<Integer> cibles, long debut) {
            this.joueur = joueur;
            this.centre = centre;
            this.cibles = cibles;
            this.debut = debut;
        }

        double t(Minecraft mc, float partiel) {
            return (mc.level.getGameTime() - debut + partiel) / 20.0;
        }

        /** Vers où vont les lignes : les cibles ; personne à prendre, huit lignes en étoile qui finissent en cristaux. */
        List<Vec3> buts(Minecraft mc, float partiel) {
            List<Vec3> l = new ArrayList<>();
            for (int id : cibles) {
                net.minecraft.world.entity.Entity e = mc.level.getEntity(id);
                if (e != null && tEclat < 0) {
                    pieds.put(id, e.getPosition(partiel));
                }
                Vec3 p = pieds.get(id);
                if (p != null) {
                    l.add(p);
                }
            }
            if (cibles.isEmpty()) {
                for (int q = 0; q < 8; q++) {
                    double a = q * Math.PI / 4 + 0.3;
                    l.add(centre.add(Math.cos(a) * 7, 0, Math.sin(a) * 7));
                }
            }
            return l;
        }
    }

    private static final List<Chien3> CHIENS = new ArrayList<>();
    private static final List<Capsule3> CAPSULES = new ArrayList<>();

    public static void chien(Fruit3.Chien p) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return;
        }
        if (p.phase() == 0) {
            CHIENS.removeIf(c -> c.joueur.equals(p.joueur()) && c.tMorsure < 0);
            CHIENS.add(new Chien3(p.joueur(), vec(p.point()), vec(p.dir()), mc.level.getGameTime()));
            return;
        }
        for (Chien3 c : CHIENS) {
            if (c.joueur.equals(p.joueur()) && c.tMorsure < 0) {
                c.morsure = vec(p.point());
                c.tMorsure = c.t(mc, 0);
                // la morsure : une gerbe de feu là où la gueule se referme
                ECLATEMENTS.add(new Eclatement(c.morsure, false, System.nanoTime()));
                gerbeParticules(mc, c.morsure, false);
                ClientFruit.secouer(c.morsure, 1.2F, 30);
            }
        }
    }

    public static void capsule(Fruit3.Capsule p) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return;
        }
        if (p.phase() == 0) {
            CAPSULES.removeIf(x -> x.joueur.equals(p.joueur()) && x.tEclat < 0);
            CAPSULES.add(new Capsule3(p.joueur(), vec(p.centre()), p.cibles(), mc.level.getGameTime()));
            return;
        }
        for (Capsule3 x : CAPSULES) {
            if (x.joueur.equals(p.joueur()) && x.tEclat < 0) {
                x.tEclat = x.t(mc, 0);
                for (Vec3 b : x.buts(mc, 0)) {
                    var glace = new net.minecraft.core.particles.BlockParticleOption(ParticleTypes.BLOCK, net.minecraft.world.level.block.Blocks.ICE.defaultBlockState());
                    for (int q = 0; q < 40; q++) {
                        particule(mc, glace, b.add(aleat(0.8), 0.3 + HASARD.nextDouble() * 2.2, aleat(0.8)), aleat(0.4), HASARD.nextDouble() * 0.4, aleat(0.4));
                    }
                    for (int q = 0; q < 20; q++) {
                        particule(mc, ParticleTypes.SNOWFLAKE, b.add(aleat(1), 0.5 + HASARD.nextDouble() * 2, aleat(1)), aleat(0.5), HASARD.nextDouble() * 0.4, aleat(0.5));
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------------- le chien de l'enfer

    /** La taille d'une unité de la tête du chien, en blocs : la tête fait à peu près 2,5 blocs de large et 4 de long. */
    private static final double K_CHIEN = 0.6;

    /**
     * Un morceau de la tête, dans le repère du chien (x sur le côté, y en haut, f vers l'avant ; en unités de k) : la boîte
     * de centre (cx, cy, cz) et de demi-tailles (dx, dy, dz), tournée de « levee » (vers le haut si positif) autour de la
     * charnière à la hauteur hy et à l'avancée hz (pour les mâchoires).
     */
    private static void piece(VertexConsumer v, Matrix4f m, Vec3 o, Vec3[] r, double k, double levee, double hy, double hz,
            double cx, double cy, double cz, double dx, double dy, double dz, Teinte teinte) {
        Vec3 x = r[0], y = r[1], f = r[2];
        double ca = Math.cos(levee), sa = Math.sin(levee);
        Vec3 ff = f.scale(ca).add(y.scale(sa)), yy = y.scale(ca).subtract(f.scale(sa));
        Vec3 charniere = o.add(y.scale(hy * k)).add(f.scale(hz * k));
        Vec3 c = charniere.add(x.scale(cx * k)).add(yy.scale((cy - hy) * k)).add(ff.scale((cz - hz) * k));
        boite(v, m, c, x.scale(dx * k), yy.scale(dy * k), ff.scale(dz * k), teinte, 1, 3);
    }

    /** Un point de la tête (mêmes repère et charnière que piece), pour y planter une dent ou une oreille. */
    private static Vec3 point(Vec3 o, Vec3[] r, double k, double levee, double hy, double hz, double px, double py, double pz) {
        Vec3 x = r[0], y = r[1], f = r[2];
        double ca = Math.cos(levee), sa = Math.sin(levee);
        Vec3 ff = f.scale(ca).add(y.scale(sa)), yy = y.scale(ca).subtract(f.scale(sa));
        return o.add(y.scale(hy * k)).add(f.scale(hz * k)).add(x.scale(px * k)).add(yy.scale((py - hy) * k)).add(ff.scale((pz - hz) * k));
    }

    private static Vec3 haut(Vec3[] r, double levee) {
        return r[1].scale(Math.cos(levee)).subtract(r[2].scale(Math.sin(levee)));
    }

    /**
     * Le chien de l'enfer : une tête de chien en magma (croûte noire fendue de lave, yeux blancs de chaleur, crinière de
     * flammes), sa gueule s'ouvre en courant et claque à la morsure ; derrière elle, un cou de magma jusqu'à sa main.
     * Après la morsure, tout s'effondre en une demi-seconde.
     */
    private static void dessinerChien(PoseStack ps, MultiBufferSource.BufferSource buffers, Vec3 cam, Chien3 c, double t) {
        double tm = c.tMorsure < 0 ? 1e9 : c.tMorsure;
        double fin = 1 - entre(t, tm + 0.2, tm + 0.7);
        if (fin <= 0.01) {
            return;
        }
        double k = K_CHIEN * croissance(t, 0, 0.2) * fin;
        if (k < 0.02) {
            return;
        }
        Vec3 tete = c.tete(t), f = c.dir;
        Vec3[] rp = repere(f, null);
        Vec3[] r = {rp[0], rp[1], f};
        Vec3 o = tete.subtract(cam);
        // la gueule s'ouvre en courant, et se referme d'un coup en mordant
        double ouvert = t < tm ? 0.6 * entre(t, 0.05, 0.35) : 0.6 * (1 - entre(t, tm, tm + 0.07));
        double haute = ouvert * 0.45, basse = -ouvert;
        Matrix4f m = ps.last().pose();
        VertexConsumer v = buffers.getBuffer(Types.PLEIN);
        Teinte croute = (face, i, j) -> ecaille(false, face, i, j, 11);
        Teinte noire = (face, i, j) -> mix(CROUTE, CROUTE_R, hache(i, j, face + 610) * 0.5);
        Teinte braise = (face, i, j) -> mix(LAVE_J, CHAUD, hache(i, j, face + 620));
        // le cou : des anneaux de magma de plus en plus fins jusqu'à sa main
        double couru = tete.distanceTo(c.depart);
        for (int s = 1; s <= 7; s++) {
            double recul = 1.1 + s * 0.85;
            if (recul > couru + 0.5) {
                break;
            }
            double rr = (0.95 - s * 0.08) * k / K_CHIEN;
            Vec3 cen = tete.subtract(f.scale(recul)).subtract(cam).add(r[1].scale(Math.sin(t * 14 - s * 0.9) * 0.12));
            int graine = s;
            boite(v, m, cen, r[0].scale(rr), r[1].scale(rr * 0.85), f.scale(0.55 * k / K_CHIEN), (face, i, j) -> ecaille(false, face, i, j, 30 + graine), 1, 3);
        }
        // le crâne, l'arcade, la truffe
        piece(v, m, o, r, k, 0, 0, 0, 0, 0.35, -0.5, 1.15, 1.0, 1.05, croute);
        piece(v, m, o, r, k, 0, 0, 0, 0, 1.0, 0.42, 1.02, 0.28, 0.28, noire);
        // l'intérieur de la gueule, qui brûle
        piece(v, m, o, r, k, 0, 0, 0, 0, 0.05, 1.1, 0.5, 0.18, 0.8, braise);
        // la mâchoire du haut (le museau) et celle du bas
        piece(v, m, o, r, k, haute, 0.2, 0.45, 0, 0.38, 1.45, 0.62, 0.3, 0.95, croute);
        piece(v, m, o, r, k, haute, 0.2, 0.45, 0, 0.64, 2.3, 0.3, 0.16, 0.16, noire);
        piece(v, m, o, r, k, basse, -0.1, 0.4, 0, -0.27, 1.32, 0.55, 0.2, 0.88, croute);
        // les yeux blancs de chaleur
        for (int s = -1; s <= 1; s += 2) {
            piece(v, m, o, r, k, 0, 0, 0, s * 0.6, 0.62, 0.56, 0.2, 0.13, 0.06, (face, i, j) -> CHAUD);
        }
        // les crocs : en haut ils pointent vers le bas, en bas vers le haut ; les deux grands devant
        Vec3 basH = haut(r, haute).scale(-1), hautB = haut(r, basse);
        for (int q = 0; q < 6; q++) {
            double z = 0.75 + q * 0.28;
            for (int s = -1; s <= 1; s += 2) {
                boolean croc = q == 5;
                Vec3 pH = point(o, r, k, haute, 0.2, 0.45, s * (croc ? 0.42 : 0.5), 0.08, z);
                pic(v, m, pH, basH, (croc ? 0.62 : 0.3) * k, 0.1 * k, BLANCHE, CHAUD, 1);
                Vec3 pB = point(o, r, k, basse, -0.1, 0.4, s * 0.45, -0.07, z - 0.05);
                pic(v, m, pB, hautB, (croc ? 0.5 : 0.26) * k, 0.09 * k, BLANCHE, CHAUD, 1);
            }
        }
        // les oreilles, couchées en arrière
        for (int s = -1; s <= 1; s += 2) {
            Vec3 base = point(o, r, k, 0, 0, 0, s * 0.75, 1.25, -0.75);
            Vec3 dir = r[0].scale(s * 0.3).add(r[1]).add(f.scale(-0.55));
            pic(v, m, base, dir, 1.4 * k, 0.48 * k, CROUTE, LAVE_O, 1);
        }
        buffers.endBatch(Types.PLEIN);
        // la crinière de flammes sur la nuque, et quelques flammes sur le cou
        VertexConsumer fl = buffers.getBuffer(Types.VOILE);
        for (int q = 0; q < 9; q++) {
            Vec3 base = point(o, r, k, 0, 0, 0, -0.95 + q * 0.24, 1.2, -1.15 + 0.1 * Math.sin(q));
            double vie = 0.75 + 0.25 * Math.sin(t * 11 + q * 1.9);
            flamme(fl, m, base, r[1].add(f.scale(-0.7)), (1.1 + 0.5 * hache(q, 2, 630)) * 1.5 * k * vie, 0.5 * k);
        }
        for (int s = 2; s <= 6; s += 2) {
            double recul = 1.1 + s * 0.85;
            if (recul > couru) {
                break;
            }
            Vec3 cen = tete.subtract(f.scale(recul)).subtract(cam).add(r[1].scale(0.7 * k / K_CHIEN));
            flamme(fl, m, cen, r[1].add(f.scale(-0.6)), (0.9 + 0.3 * Math.sin(t * 9 + s)) * k * 1.6, 0.45 * k);
        }
        buffers.endBatch(Types.VOILE);
    }

    // ---------------------------------------------------------------- la capsule de glace

    /**
     * La capsule de glace : de ses pieds, une ligne de petits pics de glace file dans le sol jusqu'à chaque cible ; à
     * l'arrivée, sept grands cristaux poussent autour d'elle et se referment au-dessus (le bloc de glace du gel est dessiné
     * par ClientFruit) ; à l'éclatement, les cristaux volent en éclats.
     */
    private static void dessinerCapsule(VertexConsumer v, Matrix4f m, Vec3 cam, Minecraft mc, Capsule3 x, double t, float partiel) {
        double te = x.tEclat < 0 ? 1e9 : x.tEclat;
        double vitesse = Fruit3.VITESSE_LIGNE * 20, fonte = 1 - entre(t, te + 0.05, te + 0.3);
        List<Vec3> buts = x.buts(mc, partiel);
        for (int i = 0; i < buts.size(); i++) {
            Vec3 b = buts.get(i), d = b.subtract(x.centre).multiply(1, 0, 1);
            double L = d.length();
            if (L < 0.5) {
                continue;
            }
            Vec3 dir = d.scale(1 / L), cote = new Vec3(-dir.z, 0, dir.x);
            double front = t * vitesse;
            for (int s = 0; s * 0.7 + 0.8 < Math.min(front, L); s++) {
                double u = s * 0.7 + 0.8;
                double age = t - u / vitesse;
                double h = (0.45 + 0.55 * hache(i, s, 400)) * croissance(age, 0, 0.12) * (1 - entre(t, te, te + 0.4));
                if (h > 0.02) {
                    Vec3 p = x.centre.add(dir.scale(u)).add(cote.scale((hache(i, s, 401) - 0.5) * 0.7)).add(0, Mth.lerp(u / L, 0, b.y - x.centre.y), 0);
                    Vec3 vers = new Vec3(0, 1, 0).add(cote.scale((hache(i, s, 402) - 0.5) * 0.8)).add(dir.scale(0.25));
                    cristal(v, m, p.subtract(cam), vers, h, 0.2 + 0.08 * hache(i, s, 403));
                }
            }
            // la cage de cristaux, quand la glace arrive
            double arrive = L / vitesse, g = croissance(t, arrive, arrive + 0.25) * fonte;
            if (g > 0.01) {
                for (int q = 0; q < 7; q++) {
                    double a = q / 7.0 * Math.PI * 2 + i;
                    Vec3 base = b.add(Math.cos(a) * 1.15, 0, Math.sin(a) * 1.15);
                    Vec3 vers = new Vec3(-Math.cos(a) * 0.38, 1, -Math.sin(a) * 0.38);
                    cristal(v, m, base.subtract(cam), vers, (2.5 + 0.7 * hache(q, i, 404)) * g, 0.42 + 0.12 * hache(q, i, 405));
                    Vec3 dehors = new Vec3(Math.cos(a) * 0.9, 0.5, Math.sin(a) * 0.9);
                    cristal(v, m, base.subtract(cam), dehors, (0.8 + 0.5 * hache(q, i, 406)) * g, 0.25);
                }
            }
            // les éclats
            if (t >= te) {
                double s = t - te;
                double reste = 1 - entre(s, 0.3, 1.0);
                for (int q = 0; q < 16; q++) {
                    Vec3 e = new Vec3(hache(q, i, 407) - 0.5, 0.3 + hache(q, i, 408), hache(q, i, 409) - 0.5).normalize();
                    double vit = 5 + 5 * hache(q, i, 410);
                    Vec3 p = b.add(0, 1.2, 0).add(e.scale(vit * s)).add(0, -9 * s * s, 0);
                    cristal(v, m, p.subtract(cam), e, (0.5 + 0.5 * hache(q, i, 411)) * reste, 0.22);
                }
            }
        }
    }

    // ================================================================ tout dessiner

    private static void dessiner(PoseStack ps, Vec3 cam, float partiel) {
        Minecraft mc = Minecraft.getInstance();
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        long maintenant = mc.level.getGameTime();
        double t = maintenant + partiel;
        for (Map.Entry<Integer, Dragon3> e : DRAGONS.entrySet()) {
            Dragon3 d = e.getValue();
            int n = d.chemin.size();
            Vec3 last = d.chemin.get(n - 1), prev = n > 1 ? d.chemin.get(n - 2) : last;
            Vec3 tete = last.add(last.subtract(prev).scale(Math.min(1, maintenant - d.maj + partiel)));
            serpent(ps, buffers, cam, colonne(d.chemin, tete, R_DRAGON * 0.55, LONGUEUR_DRAGON), R_DRAGON, d.glace, true, null, t / 20);
        }
        for (Eclatement e : ECLATEMENTS) {
            eclatement(buffers, ps.last().pose(), cam, e);
        }
        for (Ultime3 u : ULTIMES) {
            if (u.glace) {
                hyoton(ps, buffers, cam, mc, u, partiel);
            } else {
                soleil(ps, buffers, cam, mc, u, partiel);
            }
        }
        for (Chien3 c : CHIENS) {
            dessinerChien(ps, buffers, cam, c, c.t(mc, partiel));
        }
        if (!CAPSULES.isEmpty()) {
            VertexConsumer v = buffers.getBuffer(Types.PLEIN);
            for (Capsule3 x : CAPSULES) {
                dessinerCapsule(v, ps.last().pose(), cam, mc, x, x.t(mc, partiel), partiel);
            }
            buffers.endBatch(Types.PLEIN);
        }
    }

    // ================================================================ les particules, les touches, l'éclair blanc

    private static void particule(Minecraft mc, ParticleOptions p, Vec3 o, double vx, double vy, double vz) {
        mc.level.addParticle(p, true, o.x, o.y, o.z, vx, vy, vz);
    }

    private static double aleat(double k) {
        return (HASARD.nextDouble() - 0.5) * 2 * k;
    }

    private static void gerbeParticules(Minecraft mc, Vec3 p, boolean glace) {
        if (glace) {
            for (int q = 0; q < 60; q++) {
                particule(mc, ParticleTypes.SNOWFLAKE, p.add(aleat(1), aleat(1), aleat(1)), aleat(0.6), aleat(0.5) + 0.2, aleat(0.6));
            }
            particule(mc, ParticleTypes.EXPLOSION, p, 0, 0, 0);
        } else {
            particule(mc, ParticleTypes.EXPLOSION_EMITTER, p, 0, 0, 0);
            for (int q = 0; q < 40; q++) {
                particule(mc, q % 3 == 0 ? ParticleTypes.LAVA : ParticleTypes.FLAME, p.add(aleat(1.5), aleat(1), aleat(1.5)), aleat(0.4), aleat(0.3) + 0.2, aleat(0.4));
            }
            for (int q = 0; q < 12; q++) {
                particule(mc, ParticleTypes.LARGE_SMOKE, p.add(aleat(2), aleat(1), aleat(2)), aleat(0.05), 0.08, aleat(0.05));
            }
        }
    }

    /** Le clic droit au niveau 3 : maintenu (le dragon) ou court (le tir du niveau 2) ; envoye : le serveur guide un dragon. */
    private static boolean appuyeAvant, envoye;
    private static long debutAppui;

    private static void clicDroit(Minecraft mc) {
        String moi = ClientFruit.sorte(mc.player.getUUID());
        if (moi == null || ClientFruit.niveau(mc.player.getUUID()) < 3 || mc.screen != null) {
            if (envoye) {
                PacketDistributor.sendToServer(new Fruit3.Serpent(false));
                envoye = false;
            }
            appuyeAvant = false;
            return;
        }
        boolean appuye = mc.options.keyUse.isDown();
        long maintenant = mc.level.getGameTime();
        if (appuye && !appuyeAvant) {
            debutAppui = maintenant;
        }
        if (appuye && !envoye && maintenant - debutAppui >= Fruit3.APPUI_DRAGON && System.nanoTime() >= pretDragon) {
            PacketDistributor.sendToServer(new Fruit3.Serpent(true));
            envoye = true;
            pretDragon = System.nanoTime() + (Fruit3.RECHARGE_DRAGON + 100) * 50_000_000L;
            mc.player.swing(ClientFruit.mainDuFruit(mc.player, Fruit.glace(moi)));
        }
        if (!appuye && appuyeAvant) {
            if (envoye) {
                PacketDistributor.sendToServer(new Fruit3.Serpent(false));
                envoye = false;
            } else if (maintenant - debutAppui < Fruit3.APPUI_DRAGON && System.nanoTime() >= ClientFruit.pretJet) {
                ClientFruit.pretJet = System.nanoTime() + Fruit.RECHARGE_JET_2 * 50_000_000L;
                mc.player.swing(ClientFruit.mainDuFruit(mc.player, Fruit.glace(moi)));
                PacketDistributor.sendToServer(new Fruit.Jet());
            }
        }
        appuyeAvant = appuye;
    }

    private static void particules(Minecraft mc) {
        for (Dragon3 d : DRAGONS.values()) {
            Vec3 tete = d.chemin.get(d.chemin.size() - 1);
            for (int q = 0; q < 3; q++) {
                Vec3 p = tete.add(aleat(1.2), aleat(1.2), aleat(1.2));
                if (d.glace) {
                    particule(mc, ParticleTypes.SNOWFLAKE, p, aleat(0.05), -0.02, aleat(0.05));
                } else {
                    particule(mc, q == 0 ? ParticleTypes.LARGE_SMOKE : ParticleTypes.FLAME, p, aleat(0.03), 0.04, aleat(0.03));
                    if (HASARD.nextInt(3) == 0) {
                        particule(mc, ParticleTypes.LAVA, p, 0, 0, 0);
                    }
                }
            }
        }
        for (Ultime3 u : ULTIMES) {
            double t = u.t(mc, 0);
            if (u.glace) {
                // la neige de la tempête, qui monte en tournant
                if (t > 2.9 && t < 7.5) {
                    for (int q = 0; q < 10; q++) {
                        double a = HASARD.nextDouble() * Math.PI * 2, r = 3 + HASARD.nextDouble() * 6;
                        Vec3 p = u.point.add(Math.cos(a) * r, HASARD.nextDouble() * 30, Math.sin(a) * r);
                        particule(mc, ParticleTypes.SNOWFLAKE, p, -Math.sin(a) * 0.5, 0.4, Math.cos(a) * 0.5);
                    }
                }
                if (t > 2.88 && !u.flash) {
                    u.flash = true;
                    impact(mc, u.point, 0.55F);
                    for (int q = 0; q < 80; q++) {
                        particule(mc, ParticleTypes.SNOWFLAKE, u.point.add(aleat(2), HASARD.nextDouble() * 2, aleat(2)), aleat(1.2), HASARD.nextDouble() * 0.9, aleat(1.2));
                    }
                }
            } else {
                double impactT = u.lance < 0 ? 1e9 : u.lance + 0.9;
                if (t < impactT && t > 0.75) {
                    Vec3 c = centreSoleil(mc, u, t, 0);
                    double R = rayonSoleil(t);
                    for (int q = 0; q < 6; q++) {
                        Vec3 d = new Vec3(aleat(1), aleat(1), aleat(1)).normalize();
                        particule(mc, q % 2 == 0 ? ParticleTypes.LAVA : ParticleTypes.FLAME, c.add(d.scale(R * 1.02)), d.x * 0.2, d.y * 0.2, d.z * 0.2);
                    }
                    particule(mc, ParticleTypes.CAMPFIRE_COSY_SMOKE, c.add(aleat(R), R * 0.9, aleat(R)), 0, 0.1, 0);
                }
                if (u.zone != null && t >= impactT && !u.flash) {
                    u.flash = true;
                    impact(mc, u.zone, 0.85F);
                    for (int q = 0; q < 14; q++) {
                        particule(mc, ParticleTypes.EXPLOSION_EMITTER, u.zone.add(aleat(16), HASARD.nextDouble() * 10, aleat(16)), 0, 0, 0);
                    }
                }
                if (u.zone != null && t >= impactT && t < impactT + 6) {
                    for (int q = 0; q < 8; q++) {
                        double a = HASARD.nextDouble() * Math.PI * 2, r = HASARD.nextDouble() * 30;
                        particule(mc, q % 2 == 0 ? ParticleTypes.FLAME : ParticleTypes.LARGE_SMOKE, u.zone.add(Math.cos(a) * r, 0.3, Math.sin(a) * r), 0, 0.1, 0);
                    }
                }
            }
        }
    }

    /** Les particules des attaques accroupi : le feu et la lave du chien qui court ; la neige au bout des lignes de glace. */
    private static void particulesAccroupi(Minecraft mc) {
        for (Chien3 c : CHIENS) {
            double t = c.t(mc, 0);
            if (c.tMorsure >= 0 || t < 0.05) {
                continue;
            }
            Vec3 tete = c.tete(t);
            for (int q = 0; q < 5; q++) {
                Vec3 p = tete.add(aleat(1.2), aleat(0.9), aleat(1.2)).subtract(c.dir.scale(HASARD.nextDouble() * 3));
                particule(mc, q == 0 ? ParticleTypes.LARGE_SMOKE : ParticleTypes.FLAME, p, aleat(0.04), 0.05, aleat(0.04));
            }
            particule(mc, ParticleTypes.LAVA, tete.add(aleat(1), aleat(0.5), aleat(1)), 0, 0, 0);
        }
        for (Capsule3 x : CAPSULES) {
            double t = x.t(mc, 0);
            if (x.tEclat >= 0) {
                continue;
            }
            double front = t * Fruit3.VITESSE_LIGNE * 20;
            for (Vec3 b : x.buts(mc, 0)) {
                Vec3 d = b.subtract(x.centre).multiply(1, 0, 1);
                double L = d.length();
                if (L < 0.5) {
                    continue;
                }
                Vec3 p = x.centre.add(d.scale(Math.min(front, L) / L)).add(0, 0.3, 0);
                particule(mc, ParticleTypes.SNOWFLAKE, p.add(aleat(0.4), 0, aleat(0.4)), aleat(0.08), 0.12, aleat(0.08));
            }
        }
    }

    /** L'impact : l'écran tremble pour ceux qui sont près, et un éclair blanc (ou orangé) les aveugle un instant. */
    private static void impact(Minecraft mc, Vec3 p, float force) {
        ClientFruit.secouer(p, 2.2F, 90);
        if (mc.player != null && mc.player.position().distanceTo(p) < 120) {
            eclairBlanc = System.nanoTime();
            forceEclair = force * (float) (1 - mc.player.position().distanceTo(p) / 160);
        }
    }

    private static void eclair(GuiGraphics g) {
        if (eclairBlanc < 0) {
            return;
        }
        double t = (System.nanoTime() - eclairBlanc) / 1e9;
        if (t > 0.6) {
            eclairBlanc = -1;
            return;
        }
        float a = forceEclair * (float) (1 - t / 0.6);
        RenderSystem.enableBlend();
        g.fill(0, 0, g.guiWidth(), g.guiHeight(), ((int) (a * 255) << 24) | 0xFFF4E6);
        RenderSystem.disableBlend();
    }

    // ================================================================ la pose (appelé par PlayerModelMixin)

    /**
     * Pendant l'ultime : la glace colle ses deux mains et les pousse en avant ; la lave lève sa main (la gauche), puis
     * l'abat vers l'avant quand il lance. Pendant qu'il guide son dragon, le bras du fruit reste tendu vers où il regarde.
     */
    public static void poser(PlayerModel<?> m, Player joueur) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return;
        }
        for (Ultime3 u : ULTIMES) {
            if (!u.joueur.equals(joueur.getUUID())) {
                continue;
            }
            double t = u.t(mc, mc.getTimer().getGameTimeDeltaPartialTick(false));
            if (u.glace) {
                float k = (float) (sortie(entre(t, 0, 0.28)) * (1 - entre(t, 1.1, 1.6)));
                if (k > 0) {
                    m.rightArm.xRot = Mth.lerp(k, m.rightArm.xRot, -1.5F);
                    m.rightArm.yRot = Mth.lerp(k, m.rightArm.yRot, -0.32F);
                    m.rightArm.zRot = Mth.lerp(k, m.rightArm.zRot, 0);
                    m.leftArm.xRot = Mth.lerp(k, m.leftArm.xRot, -1.5F);
                    m.leftArm.yRot = Mth.lerp(k, m.leftArm.yRot, 0.32F);
                    m.leftArm.zRot = Mth.lerp(k, m.leftArm.zRot, 0);
                }
            } else {
                double fini = u.lance < 0 ? 1e9 : u.lance;
                float leve = (float) (sortie(entre(t, 0.45, 0.8)) * (1 - entre(t, fini + 0.6, fini + 1.2)));
                float lance = (float) sortie(entre(t, fini, fini + 0.3));
                if (leve > 0) {
                    m.leftArm.xRot = Mth.lerp(leve, m.leftArm.xRot, -2.95F + 1.6F * lance);
                    m.leftArm.zRot = Mth.lerp(leve, m.leftArm.zRot, -0.15F);
                    m.leftArm.yRot = Mth.lerp(leve, m.leftArm.yRot, 0);
                }
            }
            copierManches(m);
            return;
        }
        for (Dragon3 d : DRAGONS.values()) {
            if (d.joueur.equals(joueur.getUUID())) {
                ModelPart bras = d.glace ? m.rightArm : m.leftArm;
                bras.xRot = -Mth.HALF_PI + m.head.xRot;
                bras.yRot = m.head.yRot;
                bras.zRot = 0;
                copierManches(m);
                return;
            }
        }
    }

    /** Les manches (la seconde couche de la peau) suivent les bras. */
    private static void copierManches(PlayerModel<?> m) {
        m.leftSleeve.copyFrom(m.leftArm);
        m.rightSleeve.copyFrom(m.rightArm);
    }

    // ================================================================ branchements

    @EventBusSubscriber(modid = Cabane.MODID, value = Dist.CLIENT)
    public static final class Evenements {
        @SubscribeEvent
        public static void couche(RegisterGuiLayersEvent event) {
            event.registerAboveAll(Cabane.id("fruit3_eclair"), (g, delta) -> eclair(g));
        }

        @SubscribeEvent
        public static void tick(ClientTickEvent.Post event) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.level == null || mc.player == null) {
                DRAGONS.clear();
                ECLATEMENTS.clear();
                ULTIMES.clear();
                CHIENS.clear();
                CAPSULES.clear();
                appuyeAvant = false;
                envoye = false;
                return;
            }
            clicDroit(mc);
            long maintenant = mc.level.getGameTime();
            // un dragon dont on n'a plus de nouvelles (hors de portée) : on l'oublie
            DRAGONS.values().removeIf(d -> maintenant - d.maj > 40);
            ECLATEMENTS.removeIf(e -> (System.nanoTime() - e.debut()) / 1e9 > 2.2);
            ULTIMES.removeIf(u -> u.t(mc, 0) > (u.glace ? 9 : (u.lance < 0 ? 14 : u.lance + 9.5)));
            CHIENS.removeIf(c -> c.t(mc, 0) > (c.tMorsure < 0 ? 3 : c.tMorsure + 1));
            CAPSULES.removeIf(x -> x.t(mc, 0) > (x.tEclat < 0 ? 5 : x.tEclat + 1.2));
            particules(mc);
            particulesAccroupi(mc);
        }

        @SubscribeEvent
        public static void monde(RenderLevelStageEvent event) {
            if ((DRAGONS.isEmpty() && ECLATEMENTS.isEmpty() && ULTIMES.isEmpty() && CHIENS.isEmpty() && CAPSULES.isEmpty())
                    || event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES
                    || Minecraft.getInstance().level == null) {
                return;
            }
            dessiner(event.getPoseStack(), event.getCamera().getPosition(), event.getPartialTick().getGameTimeDeltaPartialTick(false));
        }

        private Evenements() {
        }
    }

    private ClientFruit3() {
    }
}
