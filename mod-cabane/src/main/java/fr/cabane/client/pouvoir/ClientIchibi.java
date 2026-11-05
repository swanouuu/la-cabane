package fr.cabane.client.pouvoir;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import fr.cabane.Cabane;
import fr.cabane.pouvoir.Ichibi;
import fr.cabane.pouvoir.Pouvoirs;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.PlayerSkin;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.RenderHandEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.event.RenderPlayerEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.joml.Matrix3f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Le mode Ichibi chez chaque joueur (voir {@link Ichibi} et docs/ichibi/planche-ichibi.png) : la moitié droite du corps
 * en sable (par-dessus l'armure s'il en porte), avec l'œil d'Ichibi ; à la place du bras droit, un grand bras de sable
 * griffu, avec ses piques, qui frappe, s'allonge pour attraper et tient sa prise dans un poing géant ; l'oreille, les
 * piques du dos et la grosse queue ; le nuage de sable sous ses pieds. À l'éveil, le sable monte : le bras d'abord, puis
 * la moitié du corps et la queue. À la première personne, on voit le grand bras (tendu jusqu'à sa prise, quand il
 * tient quelqu'un). Les textures sont faites par
 * docs/ichibi/generateur/textures.mjs.
 */
public final class ClientIchibi {
    /** Le sable du bras, de la queue, de l'oreille et du nuage : 256×256 pour une taille déclarée de 64×64. */
    private static final ResourceLocation SABLE = Cabane.id("textures/entity/ichibi/ichibi.png");
    /** La moitié du corps, sur la disposition d'une peau de joueur, quatre fois plus fine (pour l'œil). */
    private static final ResourceLocation MOITIE = Cabane.id("textures/entity/ichibi/moitie.png");
    private static final RandomSource HASARD = RandomSource.create();
    /** Le temps que la main met à se refermer (clic droit encore) avant de le lâcher. */
    private static final double DUREE_SERRE = 0.35;
    private static final double DUREE_COUP = 0.38;
    private static final double DUREE_LANCE = 0.45;

    /** Un Ichibi éveillé : depuis quand (le sable monte), ce que fait son bras, son nuage (en temps du client). */
    private static final class Ichi {
        final long debut;
        long coup = -1;
        long lance = -1;
        /** Clic droit encore : la main se referme sur lui, puis le lâche. */
        long serre = -1;
        int prise = -1;
        long nuage = -1;
        long nuageFin = -1;

        Ichi(long debut) {
            this.debut = debut;
        }
    }

    private static final Map<UUID, Ichi> ICHIS = new HashMap<>();

    private static double secondes(long depuis) {
        return (System.nanoTime() - depuis) / 1e9;
    }

    // ================================================================ ce que dit le serveur

    public static void recevoir(Pouvoirs.Etat p) {
        if (p.eveille()) {
            ICHIS.computeIfAbsent(p.joueur(), u -> new Ichi(System.nanoTime() - (p.annonce() ? 0 : 10_000_000_000L)));
            return;
        }
        ICHIS.remove(p.joueur());
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && p.joueur().equals(mc.player.getUUID())) {
            pretPrise = 0;
            pretNuage = 0;
            finVol = 0;
        }
    }

    public static void oublier(UUID joueur) {
        ICHIS.remove(joueur);
    }

    public static void geste(Pouvoirs.Geste p) {
        Ichi i = ICHIS.get(p.joueur());
        if (i == null) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        boolean moi = mc.player != null && p.joueur().equals(mc.player.getUUID());
        long maintenant = System.nanoTime();
        switch (p.geste()) {
            case Ichibi.GESTE_COUP -> i.coup = maintenant;
            case Ichibi.GESTE_PRISE -> {
                i.prise = p.cible();
                if (moi && p.cible() >= 0) {
                    pretPrise = maintenant + Ichibi.RECHARGE_PRISE * 50_000_000L;
                }
            }
            case Ichibi.GESTE_SERRE -> i.serre = maintenant;
            case Ichibi.GESTE_LANCE -> {
                i.prise = -1;
                i.lance = maintenant;
            }
            case Ichibi.GESTE_NUAGE -> {
                if (p.cible() == 1) {
                    i.nuage = maintenant;
                    i.nuageFin = -1;
                    if (moi) {
                        finVol = maintenant + Ichibi.DUREE_NUAGE * 50_000_000L;
                    }
                } else {
                    i.nuageFin = maintenant;
                    if (moi) {
                        finVol = 0;
                        pretNuage = maintenant + Ichibi.RECHARGE_NUAGE * 50_000_000L;
                    }
                }
            }
            default -> {
            }
        }
    }

    // ================================================================ les morceaux de sable

    private static ModelPart haut, avant, segment, paume, doigt1, doigt2, griffe, pouce, pique1, pique2, boutQueue, nuage;
    /** La main géante qui tient : la paume, un doigt (le long de son flanc, puis dans son dos, court ou long), la jointure, le pouce. */
    private static ModelPart grandePaume, doigtFlanc, doigtDos, doigtDosLong, jointure, grandPouce;
    private static final ModelPart[] QUEUE = new ModelPart[12];
    private static final ModelPart[] OREILLE = new ModelPart[4];
    /** Les points de la queue (dans le repère du buste, y vers le bas, le dos vers +z), du bas du dos jusqu'au bout. */
    private static final Vector3f[] POINTS_QUEUE = new Vector3f[QUEUE.length + 1];

    /** Un pavé (en pixels du modèle) pris dans le sable de la texture, à la case (u, v). */
    private static ModelPart boite(int u, int v, float x, float y, float z, float w, float h, float d) {
        MeshDefinition m = new MeshDefinition();
        m.getRoot().addOrReplaceChild("b", CubeListBuilder.create().texOffs(u, v).addBox(x, y, z, w, h, d), PartPose.ZERO);
        return LayerDefinition.create(m, 64, 64).bakeRoot().getChild("b");
    }

    /** Un bruit doux, de -1 à 1 (pour le bord du nuage et ses bosses). */
    private static double bruit(double x, double y, int k) {
        int i = Mth.floor(x), j = Mth.floor(y);
        double fx = x - i, fy = y - j, lx = fx * fx * (3 - 2 * fx), ly = fy * fy * (3 - 2 * fy);
        double a = hache(i, j, k), b = hache(i + 1, j, k), c = hache(i, j + 1, k), d = hache(i + 1, j + 1, k);
        return Mth.lerp(ly, Mth.lerp(lx, a, b), Mth.lerp(lx, c, d)) * 2 - 1;
    }

    private static double hache(int i, int j, int k) {
        int h = i * 374761393 + j * 668265263 + k * 1274126177;
        h = (h ^ (h >>> 13)) * 1274126177;
        return ((h ^ (h >>> 16)) >>> 1) / (double) Integer.MAX_VALUE;
    }

    /**
     * Les morceaux, en pixels. Le bras pend le long de +y depuis l'épaule : le haut du bras, l'avant-bras (au coude, à
     * 8 pixels), la main (au poignet, 9 pixels plus bas), sa paume vers l'intérieur (+x), quatre doigts et le pouce.
     */
    private static void modeles() {
        if (haut != null) {
            return;
        }
        haut = boite(0, 0, -3.5F, -3, -3.5F, 7, 11, 7);
        avant = boite(28, 0, -4, -1, -4, 8, 10, 8);
        segment = boite(0, 20, -4, 0, -4, 8, 4.4F, 8);
        paume = boite(0, 34, -2, 0, -4, 4, 5, 8);
        doigt1 = boite(26, 34, -0.9F, 0, -0.9F, 1.8F, 3.4F, 1.8F);
        doigt2 = boite(34, 34, -0.8F, 0, -0.8F, 1.6F, 2.8F, 1.6F);
        griffe = boite(32, 48, -0.6F, 0, -0.6F, 1.2F, 2.6F, 1.2F);
        pouce = boite(42, 34, -0.95F, 0, -0.95F, 1.9F, 3.4F, 1.9F);
        pique1 = boite(50, 34, -0.9F, 0, -0.9F, 1.8F, 2.6F, 1.8F);
        pique2 = boite(58, 34, -0.5F, 0, -0.5F, 1F, 2.4F, 1F);
        // la main qui tient (repère de la prise : x sur le côté, vers le dehors ; y vers lui ; z vers le haut)
        grandePaume = boite(0, 0, 0, 0, 0, 13.2F, 3.8F, 14.8F);
        doigtFlanc = boite(0, 20, 0, 0, 0, 2.8F, 10.8F, 2.8F);
        doigtDos = boite(20, 20, 0, 0, 0, 7.8F, 2.8F, 2.8F);
        doigtDosLong = boite(20, 26, 0, 0, 0, 9F, 2.8F, 2.8F);
        jointure = boite(44, 20, 0, 0, 0, 1.5F, 3, 3.4F);
        grandPouce = boite(12, 34, 0, 0, 0, 2.8F, 7.6F, 3.4F);
        // la queue : de l'épaisseur du buste au départ, deux fois plus fine au bout, en crosse au-dessus de la tête
        Vector3f p0 = new Vector3f(-0.8F, 11, 3.2F), p1 = new Vector3f(-1.2F, 18, 17), p2 = new Vector3f(-2.2F, -9, 25), p3 = new Vector3f(-1, -17, 13);
        for (int s = 0; s <= QUEUE.length; s++) {
            float t = s / (float) QUEUE.length, u = 1 - t;
            POINTS_QUEUE[s] = new Vector3f(p0).mul(u * u * u).add(new Vector3f(p1).mul(3 * u * u * t)).add(new Vector3f(p2).mul(3 * u * t * t))
                    .add(new Vector3f(p3).mul(t * t * t));
        }
        for (int s = 0; s < QUEUE.length; s++) {
            float w = 8.8F - 4.4F * s / (QUEUE.length - 1), l = POINTS_QUEUE[s].distance(POINTS_QUEUE[s + 1]);
            QUEUE[s] = boite(0, 20, -w / 2, -0.7F, -w / 2, w, l + 1.4F, w);
        }
        boutQueue = boite(48, 48, -1.8F, 0, -1.8F, 3.6F, 3.8F, 3.6F);
        // l'oreille : quatre marches de plus en plus étroites, la dernière bleue
        for (int k = 0; k < OREILLE.length; k++) {
            float w = 4.4F - k, e = 1.6F - k * 0.15F;
            OREILLE[k] = k == 3 ? boite(48, 48, -w / 2, 0, -e / 2, w, 2.1F, e) : boite(44, 20, -w / 2, 0, -e / 2, w, 2.1F, e);
        }
        // le nuage : une galette de colonnes de sable (3 blocs sur 2, un demi-bloc d'épaisseur au milieu), bosselée dessus
        CubeListBuilder n = CubeListBuilder.create();
        int nombre = 0;
        for (int x = -24; x < 24; x += 3) {
            for (int z = -18; z < 18; z += 3) {
                double mx = x + 1.5, mz = z + 1.5;
                double r = Math.hypot(mx / 24, mz / 18) + 0.18 * bruit(mx * 0.3 + 50, mz * 0.3 + 50, 1);
                if (r > 1) {
                    continue;
                }
                double dessus = 1.2 + 1.8 * (1 - r) + 1.2 * bruit(mx * 0.45 + 50, mz * 0.45 + 50, 2);
                double dessous = -1.6 - 6.5 * (1 - r * r) + 1.2 * bruit(mx * 0.5 + 70, mz * 0.5 + 70, 3);
                n.texOffs(nombre * 7 % 20, 48).addBox(x, (float) -dessus, z, 3, (float) (dessus - dessous), 3);
                nombre++;
            }
        }
        MeshDefinition m = new MeshDefinition();
        m.getRoot().addOrReplaceChild("n", n, PartPose.ZERO);
        nuage = LayerDefinition.create(m, 64, 64).bakeRoot().getChild("n");
    }

    /** Tourne le repère pour que son axe +y aille vers d. */
    private static void orienter(PoseStack ps, Vector3f d) {
        Vector3f u = new Vector3f(d).normalize();
        ps.mulPose(new Quaternionf().rotationTo(0, 1, 0, u.x, u.y, u.z));
    }

    /** Une pique (deux marches), plantée en (x, y, z) vers dir. */
    private static void pique(PoseStack ps, VertexConsumer c, int l, int o, float x, float y, float z, Vector3f dir, float taille) {
        ps.pushPose();
        ps.translate(x / 16, y / 16, z / 16);
        orienter(ps, dir);
        ps.scale(taille, taille, taille);
        pique1.render(ps, c, l, o);
        ps.translate(0, 2.4F / 16, 0);
        pique2.render(ps, c, l, o);
        ps.popPose();
    }

    /** La pose du grand bras : l'épaule (comme un ModelPart), le coude et le poignet (vers l'avant : négatif), les doigts. */
    private record Pose(float x, float y, float z, float coude, float poignet, float doigts) {
    }

    /** Le bras, dans le repère de l'épaule ; k : sa taille (il pousse à l'éveil). */
    private static void bras(PoseStack ps, VertexConsumer c, int l, int o, Pose p, float k) {
        ps.mulPose(new Quaternionf().rotationZYX(p.z(), p.y(), p.x()));
        ps.scale(k, k, k);
        ps.translate(-2 / 16F, 0, 0);
        haut.render(ps, c, l, o);
        piquesBras(ps, c, l, o);
        ps.translate(0, 8 / 16F, 0);
        ps.mulPose(Axis.XP.rotation(p.coude()));
        avant.render(ps, c, l, o);
        ps.translate(0, 9 / 16F, 0);
        ps.mulPose(Axis.XP.rotation(p.poignet()));
        main(ps, c, l, o, p.doigts());
    }

    /** Des piques le long du dos du haut du bras, vers l'extérieur et l'arrière. */
    private static void piquesBras(PoseStack ps, VertexConsumer c, int l, int o) {
        for (int k = 0; k < 5; k++) {
            pique(ps, c, l, o, -3.1F, -1.8F + k * 2.2F, 2.4F, new Vector3f(-1, -0.45F + 0.1F * k, 0.75F), 1.05F - 0.12F * k);
        }
    }

    /** La main au poignet : la paume, quatre doigts qui se replient vers l'intérieur (doigts : 0 ouverte, 1,55 le poing), le pouce. */
    private static void main(PoseStack ps, VertexConsumer c, int l, int o, float doigts) {
        paume.render(ps, c, l, o);
        for (int k = 0; k < 4; k++) {
            ps.pushPose();
            ps.translate(0.4F / 16, 4.6F / 16, (-3 + 2 * k) / 16F);
            ps.mulPose(Axis.XP.rotation((k - 1.5F) * 0.1F * (1 - Math.min(1, doigts))));
            ps.mulPose(Axis.ZP.rotation(-doigts));
            doigt1.render(ps, c, l, o);
            ps.translate(0, 3.2F / 16, 0);
            ps.mulPose(Axis.ZP.rotation(-doigts * 1.05F));
            doigt2.render(ps, c, l, o);
            ps.translate(0, 2.6F / 16, 0);
            ps.mulPose(Axis.ZP.rotation(-0.35F));
            griffe.render(ps, c, l, o);
            ps.popPose();
        }
        ps.pushPose();
        ps.translate(1.2F / 16, 1.4F / 16, -4.2F / 16);
        ps.mulPose(Axis.XP.rotation(-0.35F));
        ps.mulPose(Axis.ZP.rotation(-0.4F - doigts * 0.6F));
        pouce.render(ps, c, l, o);
        ps.translate(0, 3.2F / 16, 0);
        griffe.render(ps, c, l, o);
        ps.popPose();
    }

    /** Un morceau de la main qui tient, posé à partir de son coin (x, y, z), en pixels. */
    private static void poser(PoseStack ps, VertexConsumer c, int l, int o, ModelPart m, float x, float y, float z) {
        ps.pushPose();
        ps.translate(x / 16, y / 16, z / 16);
        m.render(ps, c, l, o);
        ps.popPose();
    }

    /**
     * Il tient quelqu'un : depuis l'épaule, le bras va droit vers lui (vers : son milieu, depuis l'épaule, en pixels),
     * l'avant-bras s'allonge jusqu'à lui en grossissant, et la main géante l'enserre : la paume sur son ventre, quatre gros
     * doigts le long de son flanc puis dans son dos, griffes plantées, le pouce de l'autre côté ; sa tête et ses jambes
     * dépassent. serre : de 0 (elle le tient) à 1 (elle s'est refermée de toutes ses forces).
     */
    private static void tendre(PoseStack ps, VertexConsumer c, int l, int o, Vector3f vers, float serre) {
        float longueur = Math.max(16, vers.length());
        // le repère de la prise : y vers lui, x à l'horizontale vers le dehors (sa droite), z vers le haut (-y du modèle)
        Vector3f y = new Vector3f(vers).normalize();
        Vector3f x = new Vector3f(y).cross(0, -1, 0);
        if (x.lengthSquared() < 1e-4F) {
            x.set(-1, 0, 0);
        }
        x.normalize();
        Vector3f z = new Vector3f(x).cross(y);
        ps.mulPose(new Quaternionf().setFromNormalized(new Matrix3f(x, y, z)));
        haut.render(ps, c, l, o);
        piquesBras(ps, c, l, o);
        float debut = 6.5F, fin = longueur - 5.8F;
        int n = Math.max(1, Math.round((fin - debut) / 4));
        for (int s = 0; s < n; s++) {
            float y0 = debut + (fin - debut) * s / n, grosseur = 1 + 0.45F * s / Math.max(1, n - 1);
            ps.pushPose();
            ps.translate(0, y0 / 16, 0);
            ps.scale(grosseur, (fin - debut) / n / 4, grosseur);
            segment.render(ps, c, l, o);
            ps.popPose();
        }
        // la main, autour de lui (son ventre à y = longueur ; il fait 9 pixels de large et 4,6 d'épaisseur)
        float L = longueur, s = serre * 1.2F;
        poser(ps, c, l, o, grandePaume, -6.6F, L - 6.1F, -7.6F);
        for (int k = 0; k < 4; k++) {
            float zk = -7.2F + k * 3.65F, bout = 0.3F + s + 1.2F * (k % 2);
            poser(ps, c, l, o, doigtFlanc, 4.7F - s, L - 5.7F, zk);
            poser(ps, c, l, o, jointure, 6.7F - s, L - 1.5F, zk - 0.3F);
            poser(ps, c, l, o, k % 2 == 0 ? doigtDos : doigtDosLong, -bout, L + 2.3F - s, zk);
            ps.pushPose();
            ps.translate(-bout / 16, (L + 3.7F - s) / 16, (zk + 1.4F) / 16);
            orienter(ps, new Vector3f(-1, -0.45F, 0.1F));
            ps.scale(1.6F, 1.6F, 1.6F);
            griffe.render(ps, c, l, o);
            ps.popPose();
        }
        poser(ps, c, l, o, grandPouce, -7.5F + s, L - 5.7F, -1.7F);
        ps.translate((-6.1F + s) / 16, (L + 1.9F - s) / 16, 0);
        orienter(ps, new Vector3f(0.4F, 1, 0));
        ps.scale(1.5F, 1.5F, 1.5F);
        griffe.render(ps, c, l, o);
    }

    /** L'oreille, sur le côté droit du haut de la tête (repère de la tête), qui pointe vers le haut et le dehors. */
    private static void oreille(PoseStack ps, VertexConsumer c, int l, int o) {
        Vector3f d = new Vector3f(-0.45F, -1, 0.1F).normalize();
        for (int k = 0; k < OREILLE.length; k++) {
            ps.pushPose();
            ps.translate((-2.6F + d.x * 1.8F * k) / 16, (-8.2F + d.y * 1.8F * k) / 16, (0.4F + d.z * 1.8F * k) / 16);
            orienter(ps, d);
            OREILLE[k].render(ps, c, l, o);
            ps.popPose();
        }
    }

    /** Des piques sur l'omoplate droite (repère du buste). */
    private static void piquesDos(PoseStack ps, VertexConsumer c, int l, int o) {
        float[][] P = {{-2.6F, 1, 2.3F, 1}, {-1.4F, 4, 2.3F, 0.85F}, {-3.2F, 6, 2.3F, 0.75F}, {-1, 7.8F, 2.3F, 0.6F}};
        for (float[] p : P) {
            pique(ps, c, l, o, p[0], p[1], p[2], new Vector3f(-0.35F, -0.55F, 1), p[3]);
        }
    }

    /** La queue (repère du buste), qui se balance doucement ; k : sa taille (elle pousse à l'éveil). */
    private static void queue(PoseStack ps, VertexConsumer c, int l, int o, float k, float age) {
        Vector3f base = POINTS_QUEUE[0];
        ps.pushPose();
        ps.translate(base.x / 16, base.y / 16, base.z / 16);
        ps.mulPose(Axis.YP.rotation(Mth.sin(age * 0.06F) * 0.12F));
        ps.mulPose(Axis.XP.rotation(Mth.sin(age * 0.045F + 1) * 0.05F));
        ps.scale(k, k, k);
        ps.translate(-base.x / 16, -base.y / 16, -base.z / 16);
        for (int s = 0; s < QUEUE.length; s++) {
            Vector3f a = POINTS_QUEUE[s];
            ps.pushPose();
            ps.translate(a.x / 16, a.y / 16, a.z / 16);
            orienter(ps, new Vector3f(POINTS_QUEUE[s + 1]).sub(a));
            QUEUE[s].render(ps, c, l, o);
            ps.popPose();
        }
        Vector3f bout = POINTS_QUEUE[QUEUE.length];
        ps.translate(bout.x / 16, bout.y / 16, bout.z / 16);
        orienter(ps, new Vector3f(bout).sub(POINTS_QUEUE[QUEUE.length - 1]));
        boutQueue.render(ps, c, l, o);
        ps.popPose();
    }

    /** La pose du bras au repos (il pend, un peu en avant, et suit la marche), pendant un coup ou un lancer. */
    private static Pose pose(Ichi i, ModelPart bras) {
        float x = bras.xRot * 0.6F - 0.18F, y = bras.yRot, z = bras.zRot * 0.5F + 0.06F;
        float coude = -0.55F, poignet = -0.35F, doigts = 0.55F;
        double c = i.coup < 0 ? 9 : secondes(i.coup), l = i.lance < 0 ? 9 : secondes(i.lance);
        if (c < DUREE_COUP) {
            float s = lisse(c < 0.1 ? c / 0.1 : 1 - (c - 0.1) / (DUREE_COUP - 0.1));
            x = Mth.lerp(s, x, -1.65F);
            y = Mth.lerp(s, y, 0.12F);
            coude = Mth.lerp(s, coude, -0.05F);
            poignet = Mth.lerp(s, poignet, 0);
            doigts = Mth.lerp(s, doigts, 1.55F);
        } else if (l < DUREE_LANCE) {
            float s = lisse(l < 0.12 ? l / 0.12 : 1 - (l - 0.12) / (DUREE_LANCE - 0.12));
            x = Mth.lerp(s, x, -2.5F);
            coude = Mth.lerp(s, coude, -0.1F);
            doigts = Mth.lerp(s, doigts, 0);
        }
        return new Pose(x, y, z, coude, poignet, doigts);
    }

    private static float lisse(double t) {
        float k = (float) Mth.clamp(t, 0, 1);
        return k * k * (3 - 2 * k);
    }

    /** Où est le milieu de cette créature, dans le repère du modèle de ce joueur (en pixels : y vers le bas, l'avant vers -z). */
    private static Vector3f versModele(AbstractClientPlayer joueur, Entity cible, float partiel) {
        return versModele(joueur, cible.getPosition(partiel).add(0, cible.getBbHeight() / 2, 0), partiel);
    }

    private static Vector3f versModele(AbstractClientPlayer joueur, Vec3 point, float partiel) {
        Vec3 w = point.subtract(joueur.getPosition(partiel));
        float lacet = Mth.rotLerp(partiel, joueur.yBodyRotO, joueur.yBodyRot);
        // le modèle d'un joueur est dessiné à 0,9375 (PlayerRenderer.scale)
        return new Vector3f((float) w.x, (float) w.y, (float) w.z).rotateY((float) Math.toRadians(lacet - 180)).mul(-1, -1, 1)
                .div(0.9375F).add(0, 1.501F, 0).mul(16);
    }

    // ================================================================ la moitié du corps

    @SuppressWarnings("unchecked")
    private static final PlayerModel<AbstractClientPlayer>[][] MOITIES = new PlayerModel[2][2];

    /** La moitié du corps : [bras fins][épaisse]. Épaisse (1,2 pixel), elle passe par-dessus une armure ; sinon 0,6. */
    private static PlayerModel<AbstractClientPlayer> modeleMoitie(boolean fin, boolean epais) {
        int f = fin ? 1 : 0, e = epais ? 1 : 0;
        if (MOITIES[f][e] == null) {
            MOITIES[f][e] = new PlayerModel<>(LayerDefinition.create(PlayerModel.createMesh(new CubeDeformation(epais ? 1.2F : 0.6F), fin), 64, 64)
                    .bakeRoot(), fin);
        }
        return MOITIES[f][e];
    }

    private static boolean fin(AbstractClientPlayer joueur) {
        return joueur.getSkin().model() == PlayerSkin.Model.SLIM;
    }

    private static void moitie(PoseStack ps, MultiBufferSource buffers, int lumiere, AbstractClientPlayer joueur, PlayerModel<AbstractClientPlayer> parent,
            int dessous) {
        boolean fin = fin(joueur);
        PlayerModel<AbstractClientPlayer> mince = modeleMoitie(fin, false), epais = modeleMoitie(fin, true);
        parent.copyPropertiesTo(mince);
        parent.copyPropertiesTo(epais);
        PlayerModel<AbstractClientPlayer> tete = joueur.getItemBySlot(EquipmentSlot.HEAD).isEmpty() ? mince : epais;
        PlayerModel<AbstractClientPlayer> torse = joueur.getItemBySlot(EquipmentSlot.CHEST).isEmpty() ? mince : epais;
        PlayerModel<AbstractClientPlayer> jambes = joueur.getItemBySlot(EquipmentSlot.LEGS).isEmpty() && joueur.getItemBySlot(EquipmentSlot.FEET).isEmpty()
                ? mince : epais;
        VertexConsumer c = buffers.getBuffer(RenderType.entityCutoutNoCull(MOITIE));
        for (ModelPart p : new ModelPart[] {tete.head, torse.body, torse.leftArm, jambes.rightLeg, jambes.leftLeg}) {
            p.render(ps, c, lumiere, dessous);
        }
    }

    /** Vu de l'extérieur : la moitié du corps, le grand bras (à la place du vrai), l'oreille, les piques et la queue. */
    public static final class Couche extends RenderLayer<AbstractClientPlayer, PlayerModel<AbstractClientPlayer>> {
        public Couche(PlayerRenderer parent) {
            super(parent);
        }

        @Override
        public void render(PoseStack ps, MultiBufferSource buffers, int lumiere, AbstractClientPlayer joueur, float marche, float vitesse,
                float partiel, float age, float tete, float tangage) {
            Ichi i = ICHIS.get(joueur.getUUID());
            if (i == null || joueur.isInvisible()) {
                return;
            }
            modeles();
            double t = secondes(i.debut);
            int dessous = LivingEntityRenderer.getOverlayCoords(joueur, 0);
            PlayerModel<AbstractClientPlayer> parent = getParentModel();
            boolean entier = t > 0.7;
            if (entier) {
                moitie(ps, buffers, lumiere, joueur, parent, dessous);
            }
            VertexConsumer c = buffers.getBuffer(RenderType.entityCutoutNoCull(SABLE));
            ModelPart epaule = parent.rightArm;
            // il tient quelqu'un (et la main se referme sur lui, s'il vient de serrer)
            double serre = i.serre < 0 ? 9 : secondes(i.serre);
            Entity tenu = i.prise >= 0 ? joueur.level().getEntity(i.prise) : null;
            ps.pushPose();
            ps.translate(epaule.x / 16, epaule.y / 16, epaule.z / 16);
            if (tenu != null) {
                tendre(ps, c, lumiere, dessous, versModele(joueur, tenu, partiel).sub(epaule.x, epaule.y, epaule.z),
                        serre < DUREE_SERRE ? lisse(serre / (DUREE_SERRE * 0.6)) : 0);
            } else {
                bras(ps, c, lumiere, dessous, pose(i, epaule), (float) Mth.clamp((t - 0.05) / 0.7, 0.2, 1));
            }
            ps.popPose();
            if (entier) {
                ps.pushPose();
                parent.head.translateAndRotate(ps);
                oreille(ps, c, lumiere, dessous);
                ps.popPose();
                ps.pushPose();
                parent.body.translateAndRotate(ps);
                piquesDos(ps, c, lumiere, dessous);
                queue(ps, c, lumiere, dessous, (float) Mth.clamp((t - 0.7) / 0.8, 0.05, 1), age);
                ps.popPose();
            }
        }
    }

    /**
     * À la première personne, le jeu ne dessine pas son corps (ni la couche) : quand il tient quelqu'un, le grand bras
     * tendu jusqu'à lui et la main qui l'enserre sont dessinés dans le monde, comme les voient les autres. Le bras part
     * d'en bas à droite de la vue (comme une main à la première personne) : depuis l'épaule, juste sous l'œil, il
     * cacherait la moitié de l'écran.
     */
    private static void brasTendu(PoseStack ps, Camera camera, float partiel) {
        Minecraft mc = Minecraft.getInstance();
        AbstractClientPlayer moi = mc.player;
        Ichi i = moi == null ? null : ICHIS.get(moi.getUUID());
        Entity tenu = i == null || i.prise < 0 ? null : moi.level().getEntity(i.prise);
        if (tenu == null || moi.isInvisible() || !mc.options.getCameraType().isFirstPerson()) {
            return;
        }
        modeles();
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        RenderType type = RenderType.entityCutoutNoCull(SABLE);
        Vec3 cam = camera.getPosition();
        Vector3f d = new Vector3f(camera.getLookVector()).mul(0.1F).add(new Vector3f(camera.getLeftVector()).mul(-0.55F))
                .add(new Vector3f(camera.getUpVector()).mul(-0.55F));
        Vector3f epaule = versModele(moi, cam.add(d.x, d.y, d.z), partiel);
        // comme LivingEntityRenderer.render : ses pieds, son corps tourné, le modèle à l'envers et à 0,9375 ; puis le départ du bras
        Vec3 p = moi.getPosition(partiel).subtract(cam);
        ps.pushPose();
        ps.translate(p.x, p.y, p.z);
        ps.mulPose(Axis.YP.rotationDegrees(180 - Mth.rotLerp(partiel, moi.yBodyRotO, moi.yBodyRot)));
        ps.scale(-0.9375F, -0.9375F, 0.9375F);
        ps.translate(epaule.x / 16, epaule.y / 16 - 1.501F, epaule.z / 16);
        double serre = i.serre < 0 ? 9 : secondes(i.serre);
        tendre(ps, buffers.getBuffer(type), mc.getEntityRenderDispatcher().getPackedLightCoords(moi, partiel), LivingEntityRenderer.getOverlayCoords(moi, 0),
                versModele(moi, tenu, partiel).sub(epaule), serre < DUREE_SERRE ? lisse(serre / (DUREE_SERRE * 0.6)) : 0);
        ps.popPose();
        buffers.endBatch(type);
    }

    // ================================================================ le nuage

    private static void nuages(PoseStack ps, Vec3 cam, float partiel) {
        Minecraft mc = Minecraft.getInstance();
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        RenderType type = RenderType.entityCutout(SABLE);
        boolean rien = true;
        for (Map.Entry<UUID, Ichi> e : ICHIS.entrySet()) {
            Ichi i = e.getValue();
            if (i.nuage < 0) {
                continue;
            }
            double fin = i.nuageFin < 0 ? -1 : secondes(i.nuageFin);
            Player j = mc.level.getPlayerByUUID(e.getKey());
            if (fin > 0.6 || j == null) {
                if (fin > 0.6) {
                    i.nuage = -1;
                }
                continue;
            }
            modeles();
            // il se forme en 0,4 s ; à la fin, il s'effrite en 0,6 s
            float k = (float) Math.min(1, secondes(i.nuage) / 0.4) * (fin < 0 ? 1 : (float) (1 - fin / 0.6));
            Vec3 p = j.getPosition(partiel);
            ps.pushPose();
            ps.translate(p.x - cam.x, p.y - cam.y - 0.02, p.z - cam.z);
            ps.mulPose(Axis.YP.rotationDegrees(-Mth.rotLerp(partiel, j.yBodyRotO, j.yBodyRot)));
            ps.mulPose(Axis.XP.rotationDegrees(180));
            ps.scale(k, k, k);
            nuage.render(ps, buffers.getBuffer(type), LevelRenderer.getLightColor(mc.level, BlockPos.containing(p.x, p.y + 0.3, p.z)), OverlayTexture.NO_OVERLAY);
            ps.popPose();
            rien = false;
        }
        if (!rien) {
            buffers.endBatch(type);
        }
    }

    // ================================================================ les touches et le panneau

    private static long pretPrise, pretNuage, finVol, dernierClic;
    private static boolean accroupiAvant;
    /** Le dernier appui sur s'accroupir (sur le nuage, deux appuis rapides le font descendre). */
    private static long dernierAccroupi;
    /** Le clic droit ne compte que s'il a été relâché depuis (le maintenir ne serre pas juste après avoir attrapé). */
    private static boolean utiliserRelache = true;

    /** Le nom de la touche que ce joueur a choisie pour ça (clic gauche, clic droit, s'accroupir, sauter…). */
    private static String touche(net.minecraft.client.KeyMapping k) {
        return k.getTranslatedKeyMessage().getString();
    }

    private static void ecran(GuiGraphics g) {
        Minecraft mc = Minecraft.getInstance();
        Ichi i = mc.player == null ? null : ICHIS.get(mc.player.getUUID());
        if (i == null || mc.options.hideGui) {
            return;
        }
        panneau(g, mc.font, i);
    }

    private static void panneau(GuiGraphics g, Font font, Ichi i) {
        var o = Minecraft.getInstance().options;
        long maintenant = System.nanoTime();
        double prise = Math.max(0, (pretPrise - maintenant) / 1e9), nuage = Math.max(0, (pretNuage - maintenant) / 1e9);
        double vol = Math.max(0, (finVol - maintenant) / 1e9);
        boolean tient = i.prise >= 0;
        // les touches de ce joueur, pas celles par défaut (s'accroupir n'est pas forcément Maj)
        String[][] lignes = {
                {touche(o.keyAttack), tient ? "Jeter" : "Bras d'Ichibi"},
                {touche(o.keyUse), tient ? "Serrer" : PanneauPouvoir.recharge("Attraper", prise)},
                {touche(o.keyShift), vol > 0 ? "Nuage  " + (int) Math.ceil(vol) + " s" : PanneauPouvoir.recharge("Nuage de sable", nuage)},
                {touche(o.keyJump) + " / " + touche(o.keyShift), vol > 0 ? "Monter / descendre (2× : quitter)" : ""},
        };
        boolean[] gris = {false, !tient && prise > 0, vol <= 0 && nuage > 0};
        String titre = "Ichibi";
        int teinte = 0xFFD6BA96;
        PanneauPouvoir.dessiner(g, font, titre, teinte, lignes, gris);
    }

    /** Le sable qui tombe du bras (et qui tourbillonne autour de lui pendant qu'il monte, à l'éveil). */
    private static void particules(Minecraft mc, Player j, Ichi i) {
        BlockParticleOption sable = new BlockParticleOption(ParticleTypes.FALLING_DUST, Blocks.SAND.defaultBlockState());
        float lacet = j.yBodyRot * Mth.DEG_TO_RAD;
        double dx = -Mth.cos(lacet), dz = -Mth.sin(lacet);
        double t = secondes(i.debut);
        if (t < 1.6) {
            for (int k = 0; k < 6; k++) {
                double a = HASARD.nextDouble() * Math.PI * 2, r = 0.7 + HASARD.nextDouble() * 0.5;
                mc.level.addParticle(new BlockParticleOption(ParticleTypes.BLOCK, Blocks.SAND.defaultBlockState()), j.getX() + Math.cos(a) * r,
                        j.getY() + HASARD.nextDouble() * 2, j.getZ() + Math.sin(a) * r, -Math.sin(a) * 0.3, 0.1, Math.cos(a) * 0.3);
            }
        }
        if (HASARD.nextInt(3) == 0) {
            mc.level.addParticle(sable, j.getX() + dx * 0.7 + (HASARD.nextDouble() - 0.5) * 0.5, j.getY() + 0.4 + HASARD.nextDouble() * 1.1,
                    j.getZ() + dz * 0.7 + (HASARD.nextDouble() - 0.5) * 0.5, 0, 0, 0);
        }
    }

    @EventBusSubscriber(modid = Cabane.MODID, value = Dist.CLIENT)
    public static final class Couches {
        @SubscribeEvent
        public static void enregistrer(RegisterGuiLayersEvent event) {
            event.registerAboveAll(Cabane.id("ichibi"), (g, delta) -> ecran(g));
        }

        @SubscribeEvent
        public static void couches(EntityRenderersEvent.AddLayers event) {
            for (PlayerSkin.Model modele : event.getSkins()) {
                if (event.getSkin(modele) instanceof PlayerRenderer rendu) {
                    rendu.addLayer(new Couche(rendu));
                }
            }
        }

        private Couches() {
        }
    }

    @EventBusSubscriber(modid = Cabane.MODID, value = Dist.CLIENT)
    public static final class Evenements {
        /** Le vrai bras droit (et ce qu'il tient) ne se dessine pas : le grand bras le remplace. */
        private static final Map<Player, ItemStack> CACHES = new IdentityHashMap<>();

        @SubscribeEvent
        public static void tick(ClientTickEvent.Post event) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.level == null || mc.player == null) {
                ICHIS.clear();
                return;
            }
            Ichi moi = ICHIS.get(mc.player.getUUID());
            // s'accroupir (l'appui, pas le maintien) : le nuage de sable
            boolean accroupi = mc.options.keyShift.isDown();
            if (accroupi && !accroupiAvant && moi != null && mc.screen == null && secondes(moi.debut) >= Ichibi.TRANSFORMATION / 20.0) {
                long maintenant = System.nanoTime();
                if (moi.nuage < 0) {
                    PacketDistributor.sendToServer(new Pouvoirs.Charge());
                } else if (moi.nuageFin < 0 && maintenant - dernierAccroupi < 350_000_000L && secondes(moi.nuage) > 0.5) {
                    // sur le nuage, un appui le fait descendre (le vol) ; deux appuis rapides, il quitte le nuage
                    PacketDistributor.sendToServer(new Pouvoirs.Charge());
                }
                dernierAccroupi = maintenant;
            }
            accroupiAvant = accroupi;
            if (!mc.options.keyUse.isDown()) {
                utiliserRelache = true;
            }
            for (Map.Entry<UUID, Ichi> e : ICHIS.entrySet()) {
                Player j = mc.level.getPlayerByUUID(e.getKey());
                if (j == null) {
                    continue;
                }
                Ichi i = e.getValue();
                if (i.nuage >= 0 && i.nuageFin < 0) {
                    // sur son nuage, il ne fait pas un pas : il reste droit, stoïque, c'est le nuage qui le porte
                    j.walkAnimation.setSpeed(0);
                    j.walkAnimation.update(0, 1);
                }
                if (!j.isInvisible() && !(j == mc.player && mc.options.getCameraType().isFirstPerson())) {
                    particules(mc, j, i);
                }
            }
        }

        /** Éveillé, les clics sont à Ichibi : le gauche frappe (ou jette), le droit attrape (ou serre). */
        @SubscribeEvent
        public static void clic(InputEvent.InteractionKeyMappingTriggered event) {
            Minecraft mc = Minecraft.getInstance();
            Ichi i = mc.player == null ? null : ICHIS.get(mc.player.getUUID());
            if (i == null || !(event.isAttack() || event.isUseItem())) {
                return;
            }
            event.setCanceled(true);
            event.setSwingHand(event.isAttack());
            long maintenant = System.nanoTime();
            if (secondes(i.debut) < Ichibi.TRANSFORMATION / 20.0 || maintenant - dernierClic < (event.isAttack() ? 300_000_000L : 400_000_000L)
                    || (event.isUseItem() && !utiliserRelache)) {
                return;
            }
            dernierClic = maintenant;
            if (event.isUseItem()) {
                utiliserRelache = false;
            }
            PacketDistributor.sendToServer(event.isAttack() ? new Pouvoirs.Frappe() : new Pouvoirs.Attraper());
        }

        @SubscribeEvent(priority = EventPriority.LOWEST)
        public static void avant(RenderPlayerEvent.Pre event) {
            Player p = event.getEntity();
            if (!ICHIS.containsKey(p.getUUID()) || p.isInvisible() || CACHES.containsKey(p)) {
                return;
            }
            PlayerModel<AbstractClientPlayer> m = event.getRenderer().getModel();
            m.rightArm.visible = false;
            m.rightSleeve.visible = false;
            Inventory inv = p.getInventory();
            if (p.getMainArm() == HumanoidArm.RIGHT) {
                if (inv.selected >= 0 && inv.selected < inv.items.size()) {
                    CACHES.put(p, inv.items.set(inv.selected, ItemStack.EMPTY));
                }
            } else {
                CACHES.put(p, inv.offhand.set(0, ItemStack.EMPTY));
            }
        }

        @SubscribeEvent
        public static void apres(RenderPlayerEvent.Post event) {
            Player p = event.getEntity();
            ItemStack cache = CACHES.remove(p);
            if (cache == null) {
                return;
            }
            Inventory inv = p.getInventory();
            if (p.getMainArm() == HumanoidArm.RIGHT) {
                inv.items.set(inv.selected, cache);
            } else {
                inv.offhand.set(0, cache);
            }
            event.getRenderer().getModel().rightArm.visible = true;
            event.getRenderer().getModel().rightSleeve.visible = true;
        }

        /** À la première personne : le grand bras (vide ou non, la main droite ne tient plus rien), qui frappe avec le coup. */
        @SubscribeEvent
        public static void premiere(RenderHandEvent event) {
            Minecraft mc = Minecraft.getInstance();
            AbstractClientPlayer moi = mc.player;
            Ichi i = moi == null ? null : ICHIS.get(moi.getUUID());
            if (i == null) {
                return;
            }
            HumanoidArm cote = event.getHand() == InteractionHand.MAIN_HAND ? moi.getMainArm() : moi.getMainArm().getOpposite();
            if (cote != HumanoidArm.RIGHT) {
                return;
            }
            event.setCanceled(true);
            if (i.prise >= 0 && moi.level().getEntity(i.prise) != null) {
                // il tient quelqu'un : le bras est tendu jusqu'à lui, dans le monde (voir brasTendu)
                return;
            }
            modeles();
            PoseStack ps = event.getPoseStack();
            float coup = event.getSwingProgress(), equipe = event.getEquipProgress(), racine = Mth.sqrt(coup);
            // comme ItemInHandRenderer.renderPlayerArm pour le bras droit, puis l'épaule du modèle
            ps.pushPose();
            ps.translate(-0.3F * Mth.sin(racine * Mth.PI) + 0.64F, 0.4F * Mth.sin(racine * Mth.TWO_PI) - 0.6F + equipe * -0.6F,
                    -0.4F * Mth.sin(coup * Mth.PI) - 0.72F);
            ps.mulPose(Axis.YP.rotationDegrees(45F));
            ps.mulPose(Axis.YP.rotationDegrees(Mth.sin(racine * Mth.PI) * 70F));
            ps.mulPose(Axis.ZP.rotationDegrees(Mth.sin(coup * coup * Mth.PI) * -20F));
            ps.translate(-1F, 3.6F, 3.5F);
            ps.mulPose(Axis.ZP.rotationDegrees(120F));
            ps.mulPose(Axis.XP.rotationDegrees(200F));
            ps.mulPose(Axis.YP.rotationDegrees(-135F));
            ps.translate(5.6F, 0, 0);
            ps.translate(-5 / 16F, (fin(moi) ? 2.5F : 2F) / 16, 0);
            boolean poing = i.prise >= 0 || coup > 0;
            float k = (float) Mth.clamp((secondes(i.debut) - 0.05) / 0.7, 0.2, 1) * 0.72F * (i.prise >= 0 ? 1.25F : 1);
            VertexConsumer c = event.getMultiBufferSource().getBuffer(RenderType.entityCutoutNoCull(SABLE));
            bras(ps, c, event.getPackedLight(), OverlayTexture.NO_OVERLAY, new Pose(0, 0, 0.05F, -0.08F, 0, poing ? 1.55F : 0.45F), k);
            ps.popPose();
        }

        @SubscribeEvent
        public static void monde(RenderLevelStageEvent event) {
            if (ICHIS.isEmpty() || event.getStage() != RenderLevelStageEvent.Stage.AFTER_ENTITIES || Minecraft.getInstance().level == null) {
                return;
            }
            float partiel = event.getPartialTick().getGameTimeDeltaPartialTick(false);
            nuages(event.getPoseStack(), event.getCamera().getPosition(), partiel);
            brasTendu(event.getPoseStack(), event.getCamera(), partiel);
        }

        private Evenements() {
        }
    }

    private ClientIchibi() {
    }
}
