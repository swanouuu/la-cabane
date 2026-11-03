package fr.cabane.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import fr.cabane.Cabane;
import fr.cabane.accessoire.Saiyan;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import top.theillusivec4.curios.api.SlotContext;
import top.theillusivec4.curios.api.client.CuriosRendererRegistry;
import top.theillusivec4.curios.api.client.ICurioRenderer;

/**
 * Les rendus Curios des accessoires Saiyan (accessoire/Saiyan), repris des maquettes docs/saiyan : la queue poilue, une
 * chaîne de 20 pavés qui se balance, et le détecteur sur l'œil gauche, dont le verre affiche des petits trucs qui bougent.
 * Rien ne s'affiche sur l'écran de celui qui le porte : c'est juste un accessoire. Chargé seulement si Curios est là.
 * Coordonnées en pixels du modèle, comme les parties du joueur : y vers le bas, l'avant vers -z, la gauche vers +x.
 */
final class RenduSaiyan {
    private static final ResourceLocation BLANC = Cabane.id("textures/marque/blanc.png");

    static void enregistrer() {
        CuriosRendererRegistry.register(Saiyan.QUEUE.get(), Queue::new);
        for (int i = 0; i < Saiyan.DETECTEURS.size(); i++) {
            int couleur = Saiyan.RVB[i];
            CuriosRendererRegistry.register(Saiyan.DETECTEURS.get(i).get(), () -> new Detecteur(couleur));
        }
    }

    // ------------------------------------------------------------------ la queue
    /**
     * La queue. Au repos, une onde part de la base et finit au bout (la base bouge peu, le bout beaucoup) ; en marchant ou en
     * courant, elle suit derrière et ondule au rythme des pas ; en tombant, elle remonte ; quand il tourne, elle traîne un
     * peu ; accroupi, elle s'enroule autour de la taille. Deux couches : le poil, et un duvet percé un peu plus gros (comme la
     * deuxième couche d'un skin) qui rend le bord poilu.
     */
    private static final class Queue implements ICurioRenderer {
        private static final ResourceLocation POIL = Cabane.id("textures/entity/saiyan/queue.png");
        private static final ResourceLocation DUVET = Cabane.id("textures/entity/saiyan/queue_duvet.png");
        private static final int N = 20;
        private static final float PAS = 1.2F;
        // les angles des maquettes, en degrés, le long de la queue (0 à la base, 1 au bout) : theta monte, phi part à gauche
        private static final float[][] REPOS_THETA = {{0, -15}, {0.18F, -48}, {0.4F, -12}, {0.65F, 35}, {0.88F, 85}, {1, 120}};
        private static final float[][] REPOS_PHI = {{0, 0}, {0.5F, -10}, {1, -34}};
        private static final float[][] COURSE_THETA = {{0, -5}, {0.35F, -8}, {0.7F, 5}, {1, 30}};
        private static final float[][] COURSE_PHI = {{0, 0}, {1, -6}};
        /** Par joueur : où en est l'enroulement (0 libre, 1 enroulée) et l'âge à la dernière image. */
        private final Map<Integer, float[]> etats = new HashMap<>();

        @Override
        public <T extends LivingEntity, M extends EntityModel<T>> void render(ItemStack stack, SlotContext slot, PoseStack ps,
                RenderLayerParent<T, M> parent, MultiBufferSource buffers, int lumiere, float marche, float vitesseMarche,
                float partialTicks, float age, float lacetTete, float tangageTete) {
            if (!(parent.getModel() instanceof HumanoidModel<?> modele)) {
                return;
            }
            LivingEntity e = slot.entity();
            if (etats.size() > 200) {
                etats.clear();
            }
            float[] etat = etats.computeIfAbsent(e.getId(), k -> new float[] {0, age});
            float dt = Mth.clamp(age - etat[1], 0, 4);
            etat[1] = age;
            etat[0] = Mth.approach(etat[0], e.isCrouching() ? 1 : 0, dt * 0.15F);
            float enroule = etat[0] * etat[0] * (3 - 2 * etat[0]);

            boolean armure = !e.getItemBySlot(EquipmentSlot.CHEST).isEmpty() || !e.getItemBySlot(EquipmentSlot.LEGS).isEmpty();
            float t = age / 20, course = Mth.clamp(vitesseMarche * 1.4F, 0, 1);
            float tourne = Mth.clamp(Mth.wrapDegrees(e.yBodyRot - e.yBodyRotO) * 0.05F, -0.6F, 0.6F);
            float chute = (float) Mth.clamp(-e.getDeltaMovement().y * 1.5, 0, 1);
            float rx = armure ? 6.1F : 5.1F, rz = armure ? 4.1F : 3.1F;

            // les points : la queue libre, et la même enroulée autour de la taille, mélangées
            Vec3[] p = new Vec3[N + 1];
            float[] w = new float[N + 1];
            Vec3 libre = new Vec3(0, 11, armure ? 2.4 : 1.6);
            p[0] = libre;
            w[0] = Mth.lerp(enroule, 1.8F, 1.6F);
            for (int k = 0; k < N; k++) {
                float s = k / (N - 1F);
                float th = Mth.lerp(course, cles(REPOS_THETA, s), cles(COURSE_THETA, s)) * Mth.DEG_TO_RAD
                        + (1 - course) * 0.13F * s * Mth.sin(Mth.TWO_PI * t / 2 - 3 * s + 1) + chute * 0.9F * s;
                float ph = Mth.lerp(course, cles(REPOS_PHI, s), cles(COURSE_PHI, s)) * Mth.DEG_TO_RAD
                        + (0.25F + 0.9F * s) * ((1 - course) * 0.34F * Mth.sin(Mth.TWO_PI * t / 2 - 2.4F * s)
                        + course * 0.3F * Mth.sin(marche * 0.6662F - 3.2F * s))
                        - tourne * s * 1.5F;
                libre = libre.add(Mth.cos(th) * Mth.sin(ph) * PAS, -Mth.sin(th) * PAS, Mth.cos(th) * Mth.cos(ph) * PAS);
                // enroulée : un tour par la gauche, qui monte un peu, le bout par-dessus la base
                float b = s * Mth.TWO_PI * 1.04F;
                Vec3 ceinture = new Vec3(rx * Mth.sin(b), 24 - (13.1F + 1.5F * s), rz * Mth.cos(b));
                p[k + 1] = libre.lerp(ceinture, enroule);
                w[k + 1] = Mth.lerp(enroule, s < 0.8F ? 1.8F - 0.45F * s : 1.44F - 3 * (s - 0.8F), 1.6F - 0.3F * s);
            }

            // un repère par pavé, qui suit la queue sans tourner sur lui-même (sinon les pavés vrillent)
            Vec3[][] reperes = new Vec3[N][];
            float[] longueurs = new float[N], debuts = new float[N];
            Vec3 e1 = new Vec3(1, 0, 0);
            float le = 2; // la position le long de la queue, en pixels (la texture commence 2 pixels avant la base)
            for (int k = 0; k < N; k++) {
                Vec3 d = p[k + 1].subtract(p[k]);
                float l = (float) d.length();
                d = l > 1e-4F ? d.scale(1 / l) : new Vec3(0, 1, 0);
                Vec3 ortho = e1.subtract(d.scale(e1.dot(d)));
                e1 = ortho.lengthSqr() > 1e-6 ? ortho.normalize() : d.cross(new Vec3(0, 0, 1)).normalize();
                reperes[k] = new Vec3[] {p[k], e1, d.cross(e1), d};
                longueurs[k] = l;
                debuts[k] = le;
                le += l;
            }

            ps.pushPose();
            modele.body.translateAndRotate(ps);
            ps.scale(1 / 16F, 1 / 16F, 1 / 16F);
            PoseStack.Pose pose = ps.last();
            int overlay = LivingEntityRenderer.getOverlayCoords(e, 0);
            VertexConsumer c = buffers.getBuffer(RenderType.entityCutoutNoCull(POIL));
            for (int k = 0; k < N; k++) {
                float ov = w[k] * 0.4F;
                pave(c, pose, reperes[k], w[k] / 2, -ov, longueurs[k] + ov, debuts[k], k == N - 1, lumiere, overlay);
            }
            c = buffers.getBuffer(RenderType.entityCutoutNoCull(DUVET));
            for (int k = 0; k < N; k++) {
                float g = k >= N - 3 ? 0.38F : 0.2F, ov = w[k] * 0.4F;
                pave(c, pose, reperes[k], w[k] / 2 + g, -ov + 0.1F, longueurs[k] + ov - 0.1F + (k == N - 1 ? g : 0), debuts[k],
                        k == N - 1, lumiere, overlay);
            }
            ps.popPose();
        }

        /** Une valeur qui suit des repères [s, v], en ligne droite entre eux. */
        private static float cles(float[][] l, float s) {
            for (int k = 1; k < l.length; k++) {
                if (s <= l[k][0]) {
                    return l[k - 1][1] + (l[k][1] - l[k - 1][1]) * (s - l[k - 1][0]) / (l[k][0] - l[k - 1][0]);
                }
            }
            return l[l.length - 1][1];
        }

        /**
         * Un pavé de la queue (repère r : origine, travers 1, travers 2, le long), de demi-largeur h, de z0 à z1 le long : ses
         * 4 côtés (dans la texture, une bande de 8 colonnes par côté, 4 lignes par pixel le long de la queue) et le bout.
         */
        private static void pave(VertexConsumer c, PoseStack.Pose pose, Vec3[] r, float h, float z0, float z1, float le, boolean bout,
                int lumiere, int overlay) {
            Vec3 o = r[0], x = r[1], y = r[2], z = r[3];
            float v0 = (le + z0) * 4 / 128, v1 = (le + z1) * 4 / 128;
            for (int cote = 0; cote < 4; cote++) {
                Vec3 n = switch (cote) {
                    case 0 -> x;
                    case 1 -> y;
                    case 2 -> x.reverse();
                    default -> y.reverse();
                };
                Vec3 travers = switch (cote) {
                    case 0 -> y;
                    case 1 -> x.reverse();
                    case 2 -> y.reverse();
                    default -> x;
                };
                Vec3 centre = o.add(n.scale(h));
                float u0 = cote * 8 / 32F, u1 = (cote * 8 + 8) / 32F;
                sommet(c, pose, centre.add(travers.scale(-h)).add(z.scale(z0)), u0, v0, n, lumiere, overlay);
                sommet(c, pose, centre.add(travers.scale(h)).add(z.scale(z0)), u1, v0, n, lumiere, overlay);
                sommet(c, pose, centre.add(travers.scale(h)).add(z.scale(z1)), u1, v1, n, lumiere, overlay);
                sommet(c, pose, centre.add(travers.scale(-h)).add(z.scale(z1)), u0, v1, n, lumiere, overlay);
            }
            if (bout) {
                Vec3 centre = o.add(z.scale(z1));
                sommet(c, pose, centre.add(x.scale(-h)).add(y.scale(-h)), 0, 120 / 128F, z, lumiere, overlay);
                sommet(c, pose, centre.add(x.scale(h)).add(y.scale(-h)), 8 / 32F, 120 / 128F, z, lumiere, overlay);
                sommet(c, pose, centre.add(x.scale(h)).add(y.scale(h)), 8 / 32F, 1, z, lumiere, overlay);
                sommet(c, pose, centre.add(x.scale(-h)).add(y.scale(h)), 0, 1, z, lumiere, overlay);
            }
        }

        private static void sommet(VertexConsumer c, PoseStack.Pose pose, Vec3 p, float u, float v, Vec3 n, int lumiere, int overlay) {
            c.addVertex(pose, (float) p.x, (float) p.y, (float) p.z).setColor(255, 255, 255, 255).setUv(u, v).setOverlay(overlay)
                    .setLight(lumiere).setNormal(pose, (float) n.x, (float) n.y, (float) n.z);
        }
    }

    // ------------------------------------------------------------------ le détecteur
    /**
     * Le détecteur, sur l'œil gauche : l'oreillette blanche (son haut-parleur, son bouton, sa petite lumière qui clignote), le
     * bras gris le long de la tempe, le cadre sombre et le verre transparent de sa couleur. Sur le verre, une mire qui se
     * promène, des chiffres qui changent et une ligne qui balaie. Avec un casque, tout est poussé d'un pixel. Les mesures sont
     * celles des maquettes (y vers le haut depuis les pieds, l'avant vers +z) : boite() les passe dans le repère de la tête.
     */
    private static final class Detecteur implements ICurioRenderer {
        private static final ResourceLocation OREILLETTE = Cabane.id("textures/entity/saiyan/oreillette.png");
        private static final int COQUE = 0xEEEEEA, BRAS = 0xACB4C4, SOMBRE = 0x343640;
        private final int couleur, trait;

        Detecteur(int couleur) {
            this.couleur = couleur;
            int r = couleur >> 16 & 255, g = couleur >> 8 & 255, b = couleur & 255;
            this.trait = (r + (255 - r) * 62 / 100) << 16 | (g + (255 - g) * 62 / 100) << 8 | (b + (255 - b) * 62 / 100);
        }

        @Override
        public <T extends LivingEntity, M extends EntityModel<T>> void render(ItemStack stack, SlotContext slot, PoseStack ps,
                RenderLayerParent<T, M> parent, MultiBufferSource buffers, int lumiere, float marche, float vitesseMarche,
                float partialTicks, float age, float lacetTete, float tangageTete) {
            if (!(parent.getModel() instanceof HumanoidModel<?> modele)) {
                return;
            }
            LivingEntity e = slot.entity();
            float g = e.getItemBySlot(EquipmentSlot.HEAD).isEmpty() ? 0 : 1, x = 4 + g, zv = 4.72F + g, t = age / 20;
            ps.pushPose();
            modele.head.translateAndRotate(ps);
            ps.scale(1 / 16F, 1 / 16F, 1 / 16F);
            PoseStack.Pose pose = ps.last();
            int ov = LivingEntityRenderer.getOverlayCoords(e, 0);

            VertexConsumer c = buffers.getBuffer(RenderType.entityCutoutNoCull(BLANC));
            // l'oreillette (son côté dehors, avec le haut-parleur, est dessiné à part), ses deux biseaux, le bouton, la fente
            boite(c, pose, x, 25.6F, -1.8F, x + 1.5F, 29.6F, 1.6F, COQUE, 255, lumiere, ov, false);
            boite(c, pose, x + 0.15F, 29.6F, -1.4F, x + 1.35F, 29.95F, 1.2F, COQUE, 255, lumiere, ov, true);
            boite(c, pose, x + 0.15F, 25.25F, -1.4F, x + 1.35F, 25.6F, 1.2F, COQUE, 255, lumiere, ov, true);
            boite(c, pose, x + 0.45F, 29.95F, 0.1F, x + 1.05F, 30.35F, 0.9F, SOMBRE, 255, lumiere, ov, true);
            boite(c, pose, x + 0.3F, 26.2F, 1.6F, x + 1.2F, 26.5F, 1.64F, SOMBRE, 255, lumiere, ov, true);
            // la petite lumière : elle clignote
            boolean allumee = t % 1.2F < 0.7F;
            boite(c, pose, x + 1.5F, 28.55F, 0.85F, x + 1.62F, 28.95F, 1.25F, allumee ? couleur : sombre(couleur), 255,
                    allumee ? LightTexture.FULL_BRIGHT : lumiere, ov, true);
            // le bras le long de la tempe, puis le cadre : une barre en haut du verre et une sur le côté
            boite(c, pose, x + 0.2F, 28.3F, 1.6F, x + 0.9F, 29.0F, zv - 0.1F, BRAS, 255, lumiere, ov, true);
            boite(c, pose, 0.2F, 29.0F, zv - 0.25F, x + 0.9F, 29.42F, zv + 0.22F, SOMBRE, 255, lumiere, ov, true);
            boite(c, pose, x + 0.45F, 26.1F, zv - 0.25F, x + 0.9F, 29.42F, zv + 0.22F, SOMBRE, 255, lumiere, ov, true);

            // le haut-parleur, sur le côté dehors de l'oreillette (u de l'avant vers l'arrière, v du haut vers le bas)
            c = buffers.getBuffer(RenderType.entityCutoutNoCull(OREILLETTE));
            float xo = x + 1.5F;
            sommet(c, pose, xo, 24 - 29.6F, -1.6F, 0, 0, 0xFFFFFF, 255, lumiere, ov, 1, 0, 0);
            sommet(c, pose, xo, 24 - 29.6F, 1.8F, 1, 0, 0xFFFFFF, 255, lumiere, ov, 1, 0, 0);
            sommet(c, pose, xo, 24 - 25.6F, 1.8F, 1, 1, 0xFFFFFF, 255, lumiere, ov, 1, 0, 0);
            sommet(c, pose, xo, 24 - 25.6F, -1.6F, 0, 1, 0xFFFFFF, 255, lumiere, ov, 1, 0, 0);

            // le verre, puis ce qu'il affiche, un poil devant lui et qui brille
            c = buffers.getBuffer(RenderType.entityTranslucent(BLANC));
            plaque(c, pose, 0.2F, 26.1F, x + 0.45F, 29.0F, zv, couleur, 112, lumiere, ov);
            float z = zv + 0.04F, cx = 2.3F + 0.8F * Mth.sin(0.9F * t), cy = 27.75F + 0.35F * Mth.sin(1.3F * t + 1);
            // la mire : deux coins qui se promènent
            plaque(c, pose, cx - 0.55F, cy + 0.43F, cx - 0.15F, cy + 0.55F, z, trait, 235, LightTexture.FULL_BRIGHT, ov);
            plaque(c, pose, cx - 0.55F, cy + 0.15F, cx - 0.43F, cy + 0.55F, z, trait, 235, LightTexture.FULL_BRIGHT, ov);
            plaque(c, pose, cx + 0.15F, cy - 0.55F, cx + 0.55F, cy - 0.43F, z, trait, 235, LightTexture.FULL_BRIGHT, ov);
            plaque(c, pose, cx + 0.43F, cy - 0.55F, cx + 0.55F, cy - 0.15F, z, trait, 235, LightTexture.FULL_BRIGHT, ov);
            // des chiffres qui changent, et une barre qui grandit et rétrécit
            int pas = Mth.floor(t * 4);
            for (int i = 0; i < 4; i++) {
                if (hache(pas, i) > 0.3F) {
                    plaque(c, pose, 0.6F + 0.45F * i, 26.4F, 0.9F + 0.45F * i, 26.62F, z, trait, 235, LightTexture.FULL_BRIGHT, ov);
                }
            }
            float barre = 0.5F + 1.2F * hache(Mth.floor(t * 2.5F), 7);
            plaque(c, pose, 2.6F, 26.45F, Math.min(2.6F + barre, x + 0.3F), 26.57F, z, trait, 235, LightTexture.FULL_BRIGHT, ov);
            // la ligne qui balaie le verre de haut en bas
            float yb = 28.95F - 2.8F * Mth.frac(t / 1.6F);
            plaque(c, pose, 0.25F, yb - 0.04F, x + 0.4F, yb + 0.04F, z, trait, 110, LightTexture.FULL_BRIGHT, ov);
            ps.popPose();
        }

        private static int sombre(int couleur) {
            return (couleur >> 16 & 255) * 2 / 5 << 16 | (couleur >> 8 & 255) * 2 / 5 << 8 | (couleur & 255) * 2 / 5;
        }

        private static float hache(int a, int b) {
            int h = a * 374761393 + b * 668265263;
            h = (h ^ (h >>> 13)) * 1274126177;
            return ((h ^ (h >>> 16)) >>> 8) / 16777216F;
        }

        /** Un rectangle face à l'avant, de (x0, y0) à (x1, y1), à z (mesures des maquettes). */
        private static void plaque(VertexConsumer c, PoseStack.Pose pose, float x0, float y0, float x1, float y1, float z, int rvb,
                int a, int lumiere, int ov) {
            sommet(c, pose, x0, 24 - y1, -z, 0.5F, 0.5F, rvb, a, lumiere, ov, 0, 0, -1);
            sommet(c, pose, x1, 24 - y1, -z, 0.5F, 0.5F, rvb, a, lumiere, ov, 0, 0, -1);
            sommet(c, pose, x1, 24 - y0, -z, 0.5F, 0.5F, rvb, a, lumiere, ov, 0, 0, -1);
            sommet(c, pose, x0, 24 - y0, -z, 0.5F, 0.5F, rvb, a, lumiere, ov, 0, 0, -1);
        }

        /** Une boîte d'une couleur (mesures des maquettes), avec ou sans son côté gauche (+x). */
        private static void boite(VertexConsumer c, PoseStack.Pose pose, float x0, float y0, float z0, float x1, float y1, float z1,
                int rvb, int a, int lumiere, int ov, boolean gauche) {
            float ya = 24 - y1, yb = 24 - y0, za = -z1, zb = -z0;
            face(c, pose, rvb, a, lumiere, ov, 0, -1, 0, x0, ya, za, x1, ya, za, x1, ya, zb, x0, ya, zb);
            face(c, pose, rvb, a, lumiere, ov, 0, 1, 0, x0, yb, za, x0, yb, zb, x1, yb, zb, x1, yb, za);
            face(c, pose, rvb, a, lumiere, ov, 0, 0, -1, x0, ya, za, x0, yb, za, x1, yb, za, x1, ya, za);
            face(c, pose, rvb, a, lumiere, ov, 0, 0, 1, x0, ya, zb, x1, ya, zb, x1, yb, zb, x0, yb, zb);
            face(c, pose, rvb, a, lumiere, ov, -1, 0, 0, x0, ya, za, x0, ya, zb, x0, yb, zb, x0, yb, za);
            if (gauche) {
                face(c, pose, rvb, a, lumiere, ov, 1, 0, 0, x1, ya, za, x1, yb, za, x1, yb, zb, x1, ya, zb);
            }
        }

        private static void face(VertexConsumer c, PoseStack.Pose pose, int rvb, int a, int lumiere, int ov, float nx, float ny,
                float nz, float... q) {
            for (int i = 0; i < 4; i++) {
                sommet(c, pose, q[i * 3], q[i * 3 + 1], q[i * 3 + 2], 0.5F, 0.5F, rvb, a, lumiere, ov, nx, ny, nz);
            }
        }

        private static void sommet(VertexConsumer c, PoseStack.Pose pose, float x, float y, float z, float u, float v, int rvb, int a,
                int lumiere, int ov, float nx, float ny, float nz) {
            c.addVertex(pose, x, y, z).setColor(rvb >> 16 & 255, rvb >> 8 & 255, rvb & 255, a).setUv(u, v).setOverlay(ov)
                    .setLight(lumiere).setNormal(pose, nx, ny, nz);
        }
    }

    private RenduSaiyan() {
    }
}
