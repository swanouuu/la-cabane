package fr.cabane.client.cinema;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

/**
 * Les effets « dessin animé » : auras qui flambent, rayons (Kaméhaméha, Final Flash), boules d'énergie, éclairs,
 * ondes de choc. Tout est dessiné en lumière additive : ça brille, même en plein jour.
 * Les positions sont relatives à la caméra (le PoseStack est déjà tourné dans le sens de la vue).
 */
public final class Effets {
    private static PlayerModel<LivingEntity> modele;

    /** Les angles d'un personnage (en radians), du plus important au plus fin. */
    public record Posture(float teteX, float teteY, float penche, float brasDX, float brasDY, float brasDZ, float brasGX, float brasGY, float brasGZ,
            float jambeDX, float jambeDZ, float jambeGX, float jambeGZ) {
        public static final Posture DEBOUT = new Posture(0, 0, 0, 0, 0, 0.08F, 0, 0, -0.08F, 0, 0.03F, 0, -0.03F);
        public static final Posture GARDE = new Posture(0.1F, 0, 8, -1.25F, -0.45F, 0.1F, -0.95F, 0.35F, -0.1F, -0.25F, 0.18F, 0.15F, -0.18F);
        public static final Posture VOL = new Posture(-0.9F, 0, 70, 0.35F, 0, 0.25F, 0.35F, 0, -0.25F, 0.25F, 0.05F, 0.15F, -0.05F);
        public static final Posture CRI = new Posture(-0.45F, 0, -6, 0.15F, 0, 0.75F, 0.15F, 0, -0.75F, 0, 0.25F, 0, -0.25F);
        public static final Posture COUP = new Posture(0.05F, 0, 15, -1.6F, 0.05F, 0, -0.9F, 0.4F, -0.1F, -0.5F, 0.1F, 0.4F, -0.1F);
        public static final Posture PIED = new Posture(0, 0, -15, -0.6F, -0.3F, 0.4F, -0.6F, 0.3F, -0.4F, -1.55F, 0.1F, 0.2F, -0.1F);
        /** Final Flash : les bras grands ouverts pour charger, puis tendus ensemble. */
        public static final Posture FLASH_CHARGE = new Posture(-0.2F, 0, -4, 0, 0, 1.5F, 0, 0, -1.5F, -0.1F, 0.25F, 0.1F, -0.25F);
        public static final Posture TIR = new Posture(0, 0, 6, -1.57F, 0.22F, 0, -1.57F, -0.22F, 0, -0.35F, 0.25F, 0.3F, -0.25F);
        /** Kaméhaméha : les mains jointes sur le côté, à hauteur de hanche. */
        public static final Posture KAME_CHARGE = new Posture(0.1F, 0.3F, 10, 0.55F, -0.7F, 0, 0.45F, -0.95F, 0, -0.4F, 0.3F, 0.35F, -0.3F);
        public static final Posture EPUISE = new Posture(0.55F, 0, 22, -0.25F, 0, 0.1F, -0.15F, 0, -0.1F, -0.7F, 0.1F, 0.1F, -0.1F);

        /** Un mélange de deux postures (k = 0 : celle-ci, k = 1 : l'autre). */
        public Posture vers(Posture b, float k) {
            return new Posture(Mth.lerp(k, teteX, b.teteX), Mth.lerp(k, teteY, b.teteY), Mth.lerp(k, penche, b.penche),
                    Mth.lerp(k, brasDX, b.brasDX), Mth.lerp(k, brasDY, b.brasDY), Mth.lerp(k, brasDZ, b.brasDZ),
                    Mth.lerp(k, brasGX, b.brasGX), Mth.lerp(k, brasGY, b.brasGY), Mth.lerp(k, brasGZ, b.brasGZ),
                    Mth.lerp(k, jambeDX, b.jambeDX), Mth.lerp(k, jambeDZ, b.jambeDZ), Mth.lerp(k, jambeGX, b.jambeGX), Mth.lerp(k, jambeGZ, b.jambeGZ));
        }
    }

    private static PlayerModel<LivingEntity> modele() {
        if (modele == null) {
            modele = new PlayerModel<>(net.minecraft.client.Minecraft.getInstance().getEntityModels().bakeLayer(ModelLayers.PLAYER), false);
        }
        return modele;
    }

    private static void angles(ModelPart p, float x, float y, float z) {
        p.xRot = x;
        p.yRot = y;
        p.zRot = z;
    }

    /** Un personnage au skin de joueur, à la position donnée (pieds), tourné vers lacet, dans une posture, avec sa coiffure en 3D. */
    public static void personnage(PoseStack pose, MultiBufferSource buffers, ResourceLocation skin, Vec3 pieds, float lacet, Posture p, float alpha,
            int coiffure, float[] couleurCheveux, float echelleCheveux) {
        personnage(pose, buffers, skin, pieds, lacet, p, alpha, coiffure, couleurCheveux, echelleCheveux, 0xFFFFFF);
    }

    /** Pareil, avec le skin teinté (0xRRGGBB, multiplié : 0xFFFFFF le laisse tel quel). */
    public static void personnage(PoseStack pose, MultiBufferSource buffers, ResourceLocation skin, Vec3 pieds, float lacet, Posture p, float alpha,
            int coiffure, float[] couleurCheveux, float echelleCheveux, int teinte) {
        dessiner(modele(), pose, buffers, skin, pieds, lacet, p, alpha, coiffure, couleurCheveux, echelleCheveux, teinte);
    }

    /** Un joueur avec son propre skin, aux bras fins (« Alex ») ou larges selon son modèle, sans coiffure en 3D. */
    public static void joueur(PoseStack pose, MultiBufferSource buffers, ResourceLocation skin, boolean fin, Vec3 pieds, float lacet, Posture p, int teinte) {
        dessiner(fin ? modeleFin() : modele(), pose, buffers, skin, pieds, lacet, p, 1F, SANS, NOIR, 1F, teinte);
    }

    private static PlayerModel<LivingEntity> modeleFin() {
        if (modeleFin == null) {
            modeleFin = new PlayerModel<>(net.minecraft.client.Minecraft.getInstance().getEntityModels().bakeLayer(ModelLayers.PLAYER_SLIM), true);
        }
        return modeleFin;
    }

    private static PlayerModel<LivingEntity> modeleFin;

    private static void dessiner(PlayerModel<LivingEntity> m, PoseStack pose, MultiBufferSource buffers, ResourceLocation skin, Vec3 pieds, float lacet,
            Posture p, float alpha, int coiffure, float[] couleurCheveux, float echelleCheveux, int teinte) {
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
        // la penche : en vol on est presque à l'horizontale, autour du bassin
        pose.translate(0, 0.9, 0);
        pose.mulPose(Axis.XP.rotationDegrees(-p.penche()));
        pose.translate(0, -0.9, 0);
        pose.scale(-0.9375F, -0.9375F, 0.9375F);
        pose.translate(0, -1.501, 0);
        int couleur = ((int) (Mth.clamp(alpha, 0, 1) * 255) << 24) | (teinte & 0xFFFFFF);
        m.renderToBuffer(pose, buffers.getBuffer(RenderType.entityTranslucent(skin)), 0xF000F0, OverlayTexture.NO_OVERLAY, couleur);
        m.head.translateAndRotate(pose);
        cheveux(pose, buffers, coiffure, couleurCheveux, echelleCheveux, 0xF000F0);
        pose.popPose();
    }

    // ---------------------------------------------------------------- les coiffures en 3D

    public static final int SANS = 0;
    public static final int VEGETA = 1;
    /** Goku Super Saiyan (1 et 2) : tout se dresse vers le haut. */
    public static final int GOKU_SSJ = 2;
    /** Goku en forme normale : les mèches noires en éventail, trois pointes qui tombent sur le front. */
    public static final int GOKU = 3;
    public static final float[] NOIR = {0.07F, 0.07F, 0.09F};
    public static final float[] DORE = {1F, 0.84F, 0.22F};
    private static final ResourceLocation TEXTURE_CHEVEUX = ResourceLocation.fromNamespaceAndPath("cabane", "textures/entity/cheveux.png");

    /** Une mèche : une pointe à quatre faces, de sa base (sur la tête) jusqu'à son bout, en pixels du modèle. */
    private record Meche(float bx, float by, float bz, float taille, float tx, float ty, float tz) {
    }

    /** Vegeta : la flamme qui monte tout droit, la pointe au milieu du front. */
    private static final Meche[] MECHES_VEGETA = {
            new Meche(0, -8, 0.5F, 3.2F, 0, -18, 1.5F), new Meche(-2.6F, -8, 0.5F, 2.6F, -3.6F, -16, 2),
            new Meche(2.6F, -8, 0.5F, 2.6F, 3.6F, -16, 2), new Meche(0, -8, -2.2F, 2.2F, 0, -14.5F, -1.5F),
            new Meche(0, -7, 3, 2.8F, 0, -15, 5), new Meche(-3.6F, -7, 1, 2.2F, -5.6F, -13, 2.5F),
            new Meche(3.6F, -7, 1, 2.2F, 5.6F, -13, 2.5F), new Meche(-2.8F, -7.5F, -3, 1.6F, -3.6F, -11.5F, -2.6F),
            new Meche(2.8F, -7.5F, -3, 1.6F, 3.6F, -11.5F, -2.6F), new Meche(-2, -6, 3.4F, 2, -3, -12, 5.5F),
            new Meche(2, -6, 3.4F, 2, 3, -12, 5.5F),
    };

    /** Goku Super Saiyan : une couronne de grandes pointes dressées, plus large que celle de Vegeta, et la mèche du front. */
    private static final Meche[] MECHES_GOKU_SSJ = {
            new Meche(0, -8, 0.5F, 3.3F, 0, -17.5F, 2.5F), new Meche(-2.7F, -8, 0, 2.9F, -5.2F, -16, 1.5F),
            new Meche(2.7F, -8, 0, 2.9F, 5.2F, -16, 1.5F), new Meche(-2.4F, -8, -2.6F, 2.2F, -3.4F, -14.5F, -2.8F),
            new Meche(2.4F, -8, -2.6F, 2.2F, 3.4F, -14.5F, -2.8F), new Meche(0, -8, -3, 2, 0.4F, -14, -3.8F),
            new Meche(0, -7, 3, 3, 0, -15, 7), new Meche(-2.6F, -6.5F, 3, 2.5F, -5, -13, 6.5F),
            new Meche(2.6F, -6.5F, 3, 2.5F, 5, -13, 6.5F), new Meche(-4, -6.5F, 0.5F, 2.2F, -7.5F, -12.5F, 1.5F),
            new Meche(4, -6.5F, 0.5F, 2.2F, 7.5F, -12.5F, 1.5F), new Meche(-4, -4.5F, 1.8F, 1.7F, -6.8F, -8, 3.2F),
            new Meche(4, -4.5F, 1.8F, 1.7F, 6.8F, -8, 3.2F), new Meche(-1, -7.6F, -4.05F, 1, -1.9F, -3.4F, -5.2F),
    };

    /** Goku en forme normale : l'éventail de mèches noires, les trois pointes sur le front, les côtés qui partent à l'horizontale. */
    private static final Meche[] MECHES_GOKU = {
            new Meche(-2.6F, -7.6F, -3.8F, 1.6F, -3.6F, -4.2F, -5.6F), new Meche(0, -8, -3.8F, 1.8F, 0.6F, -4.6F, -6.2F),
            new Meche(2.6F, -7.6F, -3.8F, 1.6F, 3.4F, -4.6F, -5.6F), new Meche(0, -8, 0, 3, 0.5F, -13.5F, 1),
            new Meche(-2.6F, -8, 0.5F, 2.6F, -6.5F, -12, 1), new Meche(2.6F, -8, 0.5F, 2.6F, 6.5F, -12, 1),
            new Meche(-4, -6, 0, 2, -8.5F, -7, 0.5F), new Meche(4, -6, 0, 2, 8.5F, -7, 0.5F),
            new Meche(-4, -4, 1.5F, 1.7F, -7.5F, -2.5F, 2.5F), new Meche(4, -4, 1.5F, 1.7F, 7.5F, -2.5F, 2.5F),
            new Meche(0, -6.5F, 3.6F, 2.6F, 0, -9, 8.5F), new Meche(-2.4F, -5, 3.6F, 2, -4.2F, -5, 8),
            new Meche(2.4F, -5, 3.6F, 2, 4.2F, -5, 8), new Meche(0, -3, 3.8F, 2, 0, -1, 7.5F),
    };

    private static final java.util.Map<ResourceLocation, float[]> COULEURS = new java.util.HashMap<>();

    /**
     * La couleur des cheveux d'un skin, pour que les mèches prolongent ses vrais cheveux : la moyenne du dessus de la tête
     * et du haut des côtés (lue une fois dans la texture). Si le skin est illisible, la couleur par défaut.
     */
    public static float[] couleurCheveux(ResourceLocation skin, float[] defaut) {
        return COULEURS.computeIfAbsent(skin, s -> {
            try (java.io.InputStream in = net.minecraft.client.Minecraft.getInstance().getResourceManager().open(s);
                    com.mojang.blaze3d.platform.NativeImage image = com.mojang.blaze3d.platform.NativeImage.read(in)) {
                float r = 0, g = 0, b = 0;
                int n = 0;
                for (int y = 0; y < 10; y++) {
                    for (int x = y < 8 ? 8 : 0; x < (y < 8 ? 16 : 32); x++) {
                        int p = image.getPixelRGBA(x, y);
                        if ((p >>> 24) > 128) {
                            r += p & 0xFF;
                            g += p >> 8 & 0xFF;
                            b += p >> 16 & 0xFF;
                            n++;
                        }
                    }
                }
                // un plancher, pour que des cheveux tout noirs gardent un reflet au bout des mèches
                return n == 0 ? defaut : new float[] {Math.max(0.05F, r / n / 255), Math.max(0.05F, g / n / 255), Math.max(0.05F, b / n / 255)};
            } catch (Exception e) {
                return defaut;
            }
        });
    }

    /** Quelle coiffure va avec ce skin (pour les combattants du boss). */
    public static int coiffure(ResourceLocation skin) {
        String p = skin.getPath();
        return p.contains("goku_base") ? GOKU : p.contains("goku") ? GOKU_SSJ : p.contains("vegeta") ? VEGETA : SANS;
    }

    /**
     * Les mèches en volume, à poser sur la tête (le PoseStack doit déjà être sur la tête du modèle), teintes de la couleur
     * donnée (les pointes plus claires). echelle grandit les mèches (Super Saiyan 2 : elles s'allongent).
     */
    public static void cheveux(PoseStack pose, MultiBufferSource buffers, int coiffure, float[] couleur, float echelle, int lumiere) {
        Meche[] meches = switch (coiffure) {
            case VEGETA -> MECHES_VEGETA;
            case GOKU_SSJ -> MECHES_GOKU_SSJ;
            case GOKU -> MECHES_GOKU;
            default -> null;
        };
        if (meches == null) {
            return;
        }
        VertexConsumer c = buffers.getBuffer(RenderType.entityCutoutNoCull(TEXTURE_CHEVEUX));
        PoseStack.Pose p = pose.last();
        for (Meche m : meches) {
            Vec3 base = new Vec3(m.bx(), m.by(), m.bz()).scale(1 / 16.0);
            Vec3 bout = base.add(new Vec3(m.tx() - m.bx(), m.ty() - m.by(), m.tz() - m.bz()).scale(echelle / 16.0));
            Vec3[] uw = base(bout.subtract(base));
            double s = m.taille() / 16.0;
            Vec3[] coins = {base.add(uw[0].scale(s)).add(uw[1].scale(s)), base.add(uw[0].scale(-s)).add(uw[1].scale(s)),
                    base.add(uw[0].scale(-s)).add(uw[1].scale(-s)), base.add(uw[0].scale(s)).add(uw[1].scale(-s))};
            for (int i = 0; i < 4; i++) {
                Vec3 a = coins[i], b = coins[(i + 1) % 4];
                Vec3 n = b.subtract(a).cross(bout.subtract(a)).normalize();
                Vec3[] face = {a, b, bout, bout};
                float[][] uv = {{0, 1}, {1, 1}, {0.5F, 0}, {0.5F, 0}};
                for (int k = 0; k < 4; k++) {
                    float clair = face[k] == bout ? 1.25F : 0.8F;
                    c.addVertex(p, (float) face[k].x, (float) face[k].y, (float) face[k].z)
                            .setColor(Math.min(1, couleur[0] * clair), Math.min(1, couleur[1] * clair), Math.min(1, couleur[2] * clair), 1F)
                            .setUv(uv[k][0], uv[k][1]).setOverlay(OverlayTexture.NO_OVERLAY).setLight(lumiere)
                            .setNormal(p, (float) n.x, (float) n.y, (float) n.z);
                }
            }
        }
    }

    // ---------------------------------------------------------------- les formes lumineuses

    /** De la lumière pure : additive, visible des deux côtés, et qui ne cache rien (pas d'écriture de profondeur). */
    private static final class Types extends RenderType {
        static final RenderType LUMIERE = create("cabane_lumiere", com.mojang.blaze3d.vertex.DefaultVertexFormat.POSITION_COLOR,
                com.mojang.blaze3d.vertex.VertexFormat.Mode.QUADS, 4096, false, true, CompositeState.builder()
                        .setShaderState(RENDERTYPE_LIGHTNING_SHADER).setWriteMaskState(COLOR_WRITE).setTransparencyState(LIGHTNING_TRANSPARENCY)
                        .setCullState(NO_CULL).createCompositeState(false));

        private Types() {
            super("cabane_rien", com.mojang.blaze3d.vertex.DefaultVertexFormat.POSITION, com.mojang.blaze3d.vertex.VertexFormat.Mode.QUADS, 256, false, false, () -> { }, () -> { });
        }
    }

    private static VertexConsumer lumiere(MultiBufferSource buffers) {
        return buffers.getBuffer(Types.LUMIERE);
    }

    private static void sommet(VertexConsumer c, Matrix4f m, Vec3 p, float r, float g, float b, float a) {
        c.addVertex(m, (float) p.x, (float) p.y, (float) p.z).setColor(r, g, b, a);
    }

    /** Deux vecteurs perpendiculaires à une direction (pour dessiner des tubes). */
    private static Vec3[] base(Vec3 d) {
        Vec3 n = d.normalize();
        Vec3 a = Math.abs(n.y) < 0.9 ? new Vec3(0, 1, 0) : new Vec3(1, 0, 0);
        Vec3 u = n.cross(a).normalize();
        Vec3 w = n.cross(u).normalize();
        return new Vec3[] {u, w};
    }

    /** Un tube lumineux de A à B (un rayon), de rayon r, ouvert aux deux bouts. */
    public static void tube(PoseStack pose, MultiBufferSource buffers, Vec3 a, Vec3 b, double r, float rouge, float vert, float bleu, float alpha) {
        if (r <= 0.001 || a.distanceToSqr(b) < 1.0E-4) {
            return;
        }
        VertexConsumer c = lumiere(buffers);
        Matrix4f m = pose.last().pose();
        Vec3[] uw = base(b.subtract(a));
        int n = 12;
        for (int i = 0; i < n; i++) {
            double t0 = i * Math.PI * 2 / n, t1 = (i + 1) * Math.PI * 2 / n;
            Vec3 o0 = uw[0].scale(Math.cos(t0) * r).add(uw[1].scale(Math.sin(t0) * r));
            Vec3 o1 = uw[0].scale(Math.cos(t1) * r).add(uw[1].scale(Math.sin(t1) * r));
            sommet(c, m, a.add(o0), rouge, vert, bleu, alpha);
            sommet(c, m, b.add(o0), rouge, vert, bleu, alpha);
            sommet(c, m, b.add(o1), rouge, vert, bleu, alpha);
            sommet(c, m, a.add(o1), rouge, vert, bleu, alpha);
        }
    }

    /** Un rayon d'énergie : un halo coloré, une couche plus vive, et un cœur blanc qui ondule. */
    public static void rayon(PoseStack pose, MultiBufferSource buffers, Vec3 a, Vec3 b, double largeur, float rouge, float vert, float bleu, float temps) {
        double vibre = 1 + Math.sin(temps * 40) * 0.08;
        tube(pose, buffers, a, b, largeur * 1.6 * vibre, rouge, vert, bleu, 0.22F);
        tube(pose, buffers, a, b, largeur * vibre, rouge, vert, bleu, 0.5F);
        tube(pose, buffers, a, b, largeur * 0.45, 1, 1, 1, 0.9F);
        sphere(pose, buffers, a, largeur * 1.5 * vibre, rouge, vert, bleu, 0.6F);
    }

    /** Une boule lumineuse (une sphère en bandes), avec son halo. */
    public static void sphere(PoseStack pose, MultiBufferSource buffers, Vec3 c0, double r, float rouge, float vert, float bleu, float alpha) {
        if (r <= 0.001) {
            return;
        }
        VertexConsumer c = lumiere(buffers);
        Matrix4f m = pose.last().pose();
        int lat = 8, lon = 12;
        for (double k : new double[] {1, 0.6}) {
            float a = (float) (k == 1 ? alpha * 0.45 : Math.min(1, alpha * 1.2));
            float rr = k == 1 ? rouge : Math.min(1, rouge * 0.4F + 0.6F);
            float vv = k == 1 ? vert : Math.min(1, vert * 0.4F + 0.6F);
            float bb = k == 1 ? bleu : Math.min(1, bleu * 0.4F + 0.6F);
            for (int i = 0; i < lat; i++) {
                double p0 = Math.PI * i / lat - Math.PI / 2, p1 = Math.PI * (i + 1) / lat - Math.PI / 2;
                for (int j = 0; j < lon; j++) {
                    double q0 = Math.PI * 2 * j / lon, q1 = Math.PI * 2 * (j + 1) / lon;
                    sommet(c, m, point(c0, r * k, p0, q0), rr, vv, bb, a);
                    sommet(c, m, point(c0, r * k, p1, q0), rr, vv, bb, a);
                    sommet(c, m, point(c0, r * k, p1, q1), rr, vv, bb, a);
                    sommet(c, m, point(c0, r * k, p0, q1), rr, vv, bb, a);
                }
            }
        }
    }

    private static Vec3 point(Vec3 c, double r, double lat, double lon) {
        return c.add(Math.cos(lat) * Math.cos(lon) * r, Math.sin(lat) * r, Math.cos(lat) * Math.sin(lon) * r);
    }

    /**
     * L'aura : une flamme d'énergie autour du personnage, qui ondule et monte en pointes. force de 0 (rien) à 1 (énorme).
     */
    public static void aura(PoseStack pose, MultiBufferSource buffers, Vec3 pieds, float force, float rouge, float vert, float bleu, float temps) {
        if (force <= 0.01F) {
            return;
        }
        VertexConsumer c = lumiere(buffers);
        Matrix4f m = pose.last().pose();
        int n = 16;
        int etages = 7;
        for (int couche = 0; couche < 2; couche++) {
            double largeur = (0.75 + couche * 0.35) * (0.7 + force * 0.6);
            double hauteur = (2.4 + couche * 0.7) * (0.8 + force * 0.5);
            float alpha = (couche == 0 ? 0.2F : 0.1F) * Math.min(1, force * 1.4F);
            for (int e = 0; e < etages; e++) {
                double h0 = (double) e / etages, h1 = (double) (e + 1) / etages;
                for (int i = 0; i < n; i++) {
                    double t0 = i * Math.PI * 2 / n, t1 = (i + 1) * Math.PI * 2 / n;
                    Vec3 v00 = flamme(pieds, largeur, hauteur, h0, t0, temps, i, couche);
                    Vec3 v10 = flamme(pieds, largeur, hauteur, h1, t0, temps, i, couche);
                    Vec3 v11 = flamme(pieds, largeur, hauteur, h1, t1, temps, i + 1, couche);
                    Vec3 v01 = flamme(pieds, largeur, hauteur, h0, t1, temps, i + 1, couche);
                    float a0 = alpha * (float) (1 - h0 * 0.8), a1 = alpha * (float) (1 - h1 * 0.8);
                    sommet(c, m, v00, rouge, vert, bleu, a0);
                    sommet(c, m, v10, rouge, vert, bleu, a1);
                    sommet(c, m, v11, rouge, vert, bleu, a1);
                    sommet(c, m, v01, rouge, vert, bleu, a0);
                }
            }
        }
    }

    /** Un point de la flamme : un ovoïde qui se resserre en haut, déformé par des langues qui montent. */
    private static Vec3 flamme(Vec3 pieds, double largeur, double hauteur, double h, double angle, float temps, int i, int couche) {
        double profil = Math.sin(Math.PI * Math.min(1, h * 1.15)) * (1 - h * 0.55);
        double langue = 1 + 0.22 * Math.sin(temps * 9 + i * 1.7 + couche) * h + 0.12 * Math.sin(temps * 17 + i * 3.1);
        double r = largeur * Math.max(0.05, profil) * langue;
        double y = hauteur * h * (1 + 0.15 * Math.sin(temps * 6 + i * 2.3) * h) - 0.15;
        return pieds.add(Math.cos(angle) * r, y, Math.sin(angle) * r);
    }

    /** Un éclair : une ligne brisée lumineuse, toujours tournée vers la caméra (qui est à l'origine). */
    public static void eclair(PoseStack pose, MultiBufferSource buffers, Vec3 a, Vec3 b, long graine, double epaisseur, float rouge, float vert, float bleu) {
        VertexConsumer c = lumiere(buffers);
        Matrix4f m = pose.last().pose();
        java.util.Random rnd = new java.util.Random(graine);
        int n = 7;
        Vec3 prec = a;
        double longueur = a.distanceTo(b);
        for (int i = 1; i <= n; i++) {
            Vec3 cible = a.add(b.subtract(a).scale((double) i / n));
            if (i < n) {
                cible = cible.add((rnd.nextDouble() - 0.5) * longueur * 0.25, (rnd.nextDouble() - 0.5) * longueur * 0.25, (rnd.nextDouble() - 0.5) * longueur * 0.25);
            }
            Vec3 d = cible.subtract(prec);
            Vec3 vers = prec.add(cible).scale(0.5);
            Vec3 cote = d.cross(vers).normalize().scale(epaisseur);
            for (double k : new double[] {2.5, 1}) {
                float al = k > 1 ? 0.25F : 0.95F;
                Vec3 o = cote.scale(k);
                sommet(c, m, prec.add(o), k > 1 ? rouge : 1, k > 1 ? vert : 1, k > 1 ? bleu : 1, al);
                sommet(c, m, cible.add(o), k > 1 ? rouge : 1, k > 1 ? vert : 1, k > 1 ? bleu : 1, al);
                sommet(c, m, cible.subtract(o), k > 1 ? rouge : 1, k > 1 ? vert : 1, k > 1 ? bleu : 1, al);
                sommet(c, m, prec.subtract(o), k > 1 ? rouge : 1, k > 1 ? vert : 1, k > 1 ? bleu : 1, al);
            }
            prec = cible;
        }
    }

    /** L'onde de choc : un anneau horizontal qui s'élargit en s'effaçant. */
    public static void onde(PoseStack pose, MultiBufferSource buffers, Vec3 centre, double rayon, double epaisseur, float alpha) {
        if (alpha <= 0.01F) {
            return;
        }
        VertexConsumer c = lumiere(buffers);
        Matrix4f m = pose.last().pose();
        int n = 48;
        for (int i = 0; i < n; i++) {
            double t0 = i * Math.PI * 2 / n, t1 = (i + 1) * Math.PI * 2 / n;
            Vec3 i0 = centre.add(Math.cos(t0) * rayon, 0, Math.sin(t0) * rayon);
            Vec3 i1 = centre.add(Math.cos(t1) * rayon, 0, Math.sin(t1) * rayon);
            Vec3 e0 = centre.add(Math.cos(t0) * (rayon + epaisseur), epaisseur * 0.2, Math.sin(t0) * (rayon + epaisseur));
            Vec3 e1 = centre.add(Math.cos(t1) * (rayon + epaisseur), epaisseur * 0.2, Math.sin(t1) * (rayon + epaisseur));
            sommet(c, m, i0, 1, 1, 1, alpha);
            sommet(c, m, e0, 1, 1, 1, 0);
            sommet(c, m, e1, 1, 1, 1, 0);
            sommet(c, m, i1, 1, 1, 1, alpha);
        }
    }

    private Effets() {
    }
}
