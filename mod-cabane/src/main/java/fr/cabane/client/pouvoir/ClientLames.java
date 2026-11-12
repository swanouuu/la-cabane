package fr.cabane.client.pouvoir;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import fr.cabane.Cabane;
import fr.cabane.lames.LameChakra;
import fr.cabane.lames.LameLancee;
import fr.cabane.lames.Lames;
import fr.cabane.lames.Lames.Sorte;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import net.minecraft.client.resources.PlayerSkin;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.event.RenderLivingEvent;
import net.neoforged.neoforge.client.extensions.common.IClientItemExtensions;
import net.neoforged.neoforge.client.extensions.common.RegisterClientExtensionsEvent;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * Les lames de la Brume côté client : leurs modèles (assets/cabane/lames/*.bin, faits par docs/lames-brume/generateur/
 * jeu.mjs depuis les mêmes dessins que la planche), la lame en main (dessinée sur le bras, pour suivre les gestes), les
 * lames rangées dans le dos ou sur le côté quand on ne les tient pas, la Kubikiribôchô qui vole, la grande lame de
 * chakra, et ceux qu'une Samehada a avalés (on ne les voit plus).
 * <p>
 * Les combos : chaque coup qui suit le précédent (à moins de 0,75 s de sa fin) est le coup suivant de la lame (trois,
 * quatre pour Kiba). Chaque coup bouge tout le corps (le buste tourne, une jambe part en avant) et laisse une traînée de
 * lumière de la couleur de la lame, de la taille de la lame. Les coups se comptent chez chaque client, au début de
 * chaque geste du joueur (que le serveur envoie à tous).
 * <p>
 * Les places viennent des croquis (repère des croquis : en pixels, y vers le haut, l'avant vers +z, la gauche vers +x).
 */
@EventBusSubscriber(modid = Cabane.MODID, value = Dist.CLIENT)
public final class ClientLames {
    // ------------------------------------------------------------------ ce que le serveur dit

    private static final Map<Integer, Integer> PORTEES = new HashMap<>();
    private static final Set<Integer> AVALES = new HashSet<>();

    public static void portees(Lames.Portees p) {
        PORTEES.put(p.entite(), p.masque());
    }

    /** Ce joueur tient une lame, ou en porte une dans le dos (pas de cape, alors). */
    public static boolean aUneLame(Player j) {
        return Lames.sorte(j.getMainHandItem()) != null || Lames.sorte(j.getOffhandItem()) != null || PORTEES.getOrDefault(j.getId(), 0) != 0;
    }

    public static void avale(Lames.Avale p) {
        if (p.dedans()) {
            AVALES.add(p.entite());
        } else {
            AVALES.remove(p.entite());
        }
    }

    /** Quelqu'un dans une Samehada : on ne le voit plus. */
    @SubscribeEvent
    public static void cacher(RenderLivingEvent.Pre<?, ?> event) {
        if (AVALES.contains(event.getEntity().getId())) {
            event.setCanceled(true);
        }
    }

    // ------------------------------------------------------------------ les modèles

    private static ModeleVent modele(Sorte s, int groupe) {
        ModeleVent[] l = ModeleVent.lire("lames", s.id);
        return groupe < l.length ? l[groupe] : null;
    }

    /** Le modèle de cette lame tenue : la Samehada pleine ou la gueule ouverte, Hiramekarei qui charge. */
    private static int groupe(Sorte s, ItemStack stack, Player j) {
        if (s == Sorte.SAMEHADA) {
            Integer depuis = stack.get(Lames.AVALE.get());
            if (depuis == null) {
                return 0;
            }
            long age = j == null ? 99 : j.level().getGameTime() - depuis;
            return age >= 0 && age < 12 ? 2 : 1;
        }
        if (s == Sorte.HIRAMEKAREI && j != null && j.isUsingItem() && j.getUseItem() == stack) {
            return 1;
        }
        return 0;
    }


    /**
     * Pose une lame : sa prise en o (pixels du modèle, depuis le pivot de la partie), sa pointe vers L et sa largeur vers W
     * (directions du repère des croquis), puis la dessine.
     */
    private static void poser(PoseStack ps, MultiBufferSource buffers, int lumiere, ModeleVent m, float ox, float oy, float oz,
                              Vector3f L, Vector3f W, float alpha) {
        if (m == null) {
            return;
        }
        Vector3f l = new Vector3f(L.x, -L.y, -L.z).normalize();
        Vector3f w = new Vector3f(W.x, -W.y, -W.z);
        w.sub(new Vector3f(l).mul(w.dot(l))).normalize();
        Vector3f n = new Vector3f(w).cross(l);
        ps.pushPose();
        ps.translate(ox / 16F, oy / 16F, oz / 16F);
        ps.mulPose(new Matrix4f(w.x, w.y, w.z, 0, l.x, l.y, l.z, 0, n.x, n.y, n.z, 0, 0, 0, 0, 1));
        m.dessiner(buffers, ps.last(), lumiere, 1 / 16F, alpha);
        ps.popPose();
    }

    private static Vector3f v(float x, float y, float z) {
        return new Vector3f(x, y, z);
    }

    /** Une place dans le dos (ou sur le côté), en pixels du croquis : la prise, la pointe, la largeur. */
    private record Place(int groupe, float x, float y, float z, Vector3f L, Vector3f W) {
    }

    private static final Map<Sorte, Place[]> DOS = new HashMap<>();

    static {
        DOS.put(Sorte.KUBIKIRIBOCHO, new Place[]{new Place(0, -2.6F, 38.5F, -4.9F, v(0.2F, -1, 0), v(-1, -0.22F, 0))});
        DOS.put(Sorte.SAMEHADA, new Place[]{new Place(0, 5.8F, 33, -6, v(-0.4F, -1, 0), v(1, -0.4F, 0))});
        DOS.put(Sorte.KIBA, new Place[]{new Place(0, -5.2F, 29, -2.8F, v(0.5F, -1, 0), v(1, 0.5F, 0)),
                new Place(0, 5.2F, 29, -3.4F, v(-0.5F, -1, 0), v(1, -0.5F, 0))});
        DOS.put(Sorte.NUIBARI, new Place[]{new Place(0, 5, 14.2F, 3.5F, v(0.05F, -0.12F, -1), v(0, 1, -0.1F))});
        DOS.put(Sorte.SHIBUKI, new Place[]{new Place(0, -12.4F, 23, -7.4F, v(1, 0.08F, 0), v(0, 1, 0))});
        DOS.put(Sorte.KABUTOWARI, new Place[]{new Place(0, -5.5F, 9, -4.2F, v(0.45F, 1, 0), v(1, -0.45F, 0)),
                new Place(1, 5.5F, 9, -9.8F, v(-0.45F, 1, 0), v(1, 0.45F, 0))});
        DOS.put(Sorte.HIRAMEKAREI, new Place[]{new Place(0, -2.5F, 35, -3.6F, v(0.1F, -1, 0), v(1, 0.1F, 0))});
    }

    // ------------------------------------------------------------------ les combos et les clics droits

    /**
     * Ce que fait chaque joueur avec sa lame : le coup en cours (un coup du combo, le clic droit, ou la charge de
     * Hiramekarei), depuis quand. Les coups du combo se comptent chez chaque client, au début de chaque geste du joueur
     * (que le serveur envoie à tous) ; le clic droit, c'est le serveur qui le dit ({@link #special}).
     */
    private static final class Etat {
        Sorte sorte;
        CombosLames.Coup coup;
        int index = -1;
        boolean special, charge;
        long debut = -1000;
        int finCharge = -1;
    }

    private static final Map<Integer, Etat> ETATS = new HashMap<>();
    /** La charge de Hiramekarei : le clic droit jusqu'à la lame levée (tenue tant qu'on charge). */
    private static final Map<CombosLames.Coup, CombosLames.Coup> CHARGES = new HashMap<>();

    /** Le serveur : ce joueur lance le clic droit de sa lame (depuis ce tick de l'animation). */
    public static void special(Lames.Special p) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || !(mc.level.getEntity(p.entite()) instanceof Player j)) {
            return;
        }
        Sorte s = Sorte.values()[p.sorte()];
        CombosLames.Lame l = CombosLames.de(s);
        if (l == null) {
            return;
        }
        Etat e = ETATS.computeIfAbsent(j.getId(), k -> new Etat());
        e.sorte = s;
        e.coup = l.special();
        e.index = -1;
        e.special = true;
        e.charge = false;
        e.debut = mc.level.getGameTime() - p.depuis();
        if (AnimLames.dispo()) {
            AnimLames.jouer(j, l.special(), p.depuis(), false);
        }
    }

    /** Chaque geste qui commence (chez soi comme chez les autres) : le coup suivant du combo, ou le premier. La charge de Hiramekarei. */
    @SubscribeEvent
    public static void gestes(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            ETATS.clear();
            return;
        }
        long maintenant = mc.level.getGameTime();
        for (Player j : mc.level.players()) {
            ItemStack main = j.getMainHandItem();
            Sorte s = Lames.sorte(main);
            Etat e = ETATS.get(j.getId());
            // Hiramekarei : tant qu'il charge, la lame reste levée
            boolean charge = s == Sorte.HIRAMEKAREI && j.isUsingItem() && j.getUseItem() == main;
            if (charge && (e == null || !e.charge) && !(e != null && e.special && maintenant - e.debut < e.coup.duree())) {
                CombosLames.Lame l = CombosLames.de(s);
                if (l != null) {
                    e = ETATS.computeIfAbsent(j.getId(), k -> new Etat());
                    e.sorte = s;
                    e.coup = CHARGES.computeIfAbsent(l.special(), ClientLames::charge);
                    e.special = false;
                    e.charge = true;
                    e.index = -1;
                    e.debut = maintenant;
                    e.finCharge = -1;
                    if (AnimLames.dispo()) {
                        AnimLames.jouer(j, e.coup, 0, true);
                    }
                }
            } else if (e != null && e.charge && !charge) {
                // relâchée trop tôt (pas de lame de chakra) : on rend la pose doucement
                if (e.finCharge < 0) {
                    e.finCharge = 3;
                } else if (--e.finCharge == 0) {
                    e.charge = false;
                    e.coup = null;
                    if (AnimLames.dispo()) {
                        AnimLames.arreter(j);
                    }
                }
            }
            if (!j.swinging || j.swingTime != 0 || s == null || main.has(Lames.LANCEE.get())) {
                continue;
            }
            CombosLames.Lame l = CombosLames.de(s);
            if (l == null || e != null && (e.charge || e.special && maintenant - e.debut < e.coup.duree())) {
                continue;
            }
            e = ETATS.computeIfAbsent(j.getId(), k -> new Etat());
            boolean suite = e.sorte == s && !e.special && e.index >= 0 && maintenant - e.debut <= e.coup.duree() + 15;
            e.index = suite ? (e.index + 1) % l.coups().length : 0;
            e.sorte = s;
            e.coup = l.coups()[e.index];
            e.special = false;
            e.debut = maintenant;
            if (AnimLames.dispo()) {
                AnimLames.jouer(j, e.coup, 0, false);
            }
            // le serveur frappe aussi ce qui est autour, si le coup est large (le tourbillon, les balayages…)
            if (j == mc.player) {
                net.neoforged.neoforge.network.PacketDistributor.sendToServer(new Lames.Coup(e.index));
            }
        }
    }

    /** Le clic droit de Hiramekarei jusqu'à la lame levée (la charge). */
    private static CombosLames.Coup charge(CombosLames.Coup sp) {
        java.util.List<CombosLames.Cle> l = new java.util.ArrayList<>();
        for (CombosLames.Cle k : sp.cles()) {
            if (k.t() <= 18) {
                l.add(k);
            }
        }
        CombosLames.Cle[] c = l.toArray(new CombosLames.Cle[0]);
        return new CombosLames.Coup("charge", c[c.length - 1].t(), 999, 999, c, false);
    }

    /** Le coup en cours de ce joueur et où il en est (en ticks) ; null s'il ne frappe pas. */
    private static CombosLames.Coup enCours(Player j, float partiel, float[] t) {
        Etat e = ETATS.get(j.getId());
        if (e == null || e.coup == null || e.sorte != Lames.sorte(j.getMainHandItem())) {
            return null;
        }
        float u = j.level().getGameTime() - e.debut + partiel;
        if (e.charge) {
            u = Math.min(u, e.coup.duree());
        }
        if (u < 0 || u >= e.coup.duree()) {
            return null;
        }
        t[0] = u;
        return e.coup;
    }

    // ------------------------------------------------------------------ la couche du joueur : en main, dans le dos

    public static final class Couche extends RenderLayer<AbstractClientPlayer, PlayerModel<AbstractClientPlayer>> {
        public Couche(RenderLayerParent<AbstractClientPlayer, PlayerModel<AbstractClientPlayer>> parent) {
            super(parent);
        }

        @Override
        public void render(PoseStack ps, MultiBufferSource buffers, int lumiere, AbstractClientPlayer j, float marche, float vitesse,
                           float partiel, float age, float tete, float regard) {
            if (j.isInvisible() || AVALES.contains(j.getId())) {
                return;
            }
            PlayerModel<AbstractClientPlayer> modele = getParentModel();
            ItemStack main = j.getMainHandItem(), autre = j.getOffhandItem();
            Sorte enMain = Lames.sorte(main), enAutre = Lames.sorte(autre);
            // dans le dos : ce qu'il a sur lui, sauf ce qu'il tient
            int masque = PORTEES.getOrDefault(j.getId(), 0);
            for (Sorte s : Sorte.values()) {
                if ((masque & s.bit()) == 0 || s == enMain || s == enAutre) {
                    continue;
                }
                ps.pushPose();
                modele.body.translateAndRotate(ps);
                for (Place p : DOS.get(s)) {
                    poser(ps, buffers, lumiere, modele(s, p.groupe()), p.x(), 24 - p.y(), -p.z(), p.L(), p.W(), 1);
                }
                ps.popPose();
            }
            // en main : sur le bras, pour suivre les gestes, tournée dans le poing comme dans l'aperçu
            boolean droitier = j.getMainArm() == HumanoidArm.RIGHT;
            float[] t = new float[1];
            CombosLames.Coup coup = enCours(j, partiel, t);
            float[] p = coup == null ? null : CombosLames.pose(coup, t[0]);
            float poignet = p == null ? 0 : p[CombosLames.POIGNET], roulis = p == null ? 0 : p[CombosLames.ROULIS];
            if (enMain != null && !main.has(Lames.LANCEE.get())) {
                tenir(ps, buffers, lumiere, modele, j, enMain, groupe(enMain, main, j), droitier, poignet, roulis, enMain == Sorte.KABUTOWARI);
                // Kiba : la deuxième lame dans l'autre main ; Kabutowari : le marteau
                if (autre.isEmpty() && (enMain == Sorte.KIBA || enMain == Sorte.KABUTOWARI)) {
                    tenir(ps, buffers, lumiere, modele, j, enMain, enMain == Sorte.KABUTOWARI ? 1 : 0, !droitier, poignet, roulis, false);
                }
            }
            if (enAutre != null && enAutre != enMain) {
                tenir(ps, buffers, lumiere, modele, j, enAutre, groupe(enAutre, autre, j), !droitier, 0, 0, enAutre == Sorte.KABUTOWARI);
            }
        }
    }

    /** La lame tenue dans la main droite (ou gauche) : la prise dans le poing, tournée par le poignet et le roulis du coup. */
    private static void tenir(PoseStack ps, MultiBufferSource buffers, int lumiere, PlayerModel<?> modele, Player j, Sorte s, int groupe,
                              boolean droite, float poignet, float roulis, boolean hache) {
        ModelPart bras = droite ? modele.rightArm : modele.leftArm;
        float sg = droite ? 1 : -1;
        Vector3f[] lw = CombosLames.orienter(poignet, roulis, hache && groupe == 0);
        Vector3f L = lw[0], W = lw[1];
        if (!droite) {
            // l'autre main : le même geste, en miroir
            L = v(-L.x, L.y, L.z);
            W = v(-W.x, W.y, W.z);
        }
        ps.pushPose();
        bras.translateAndRotate(ps);
        // le coude plié (l'animation articulée) : l'avant-bras, donc la lame, tourne autour du milieu du bras
        float[] coude = AnimLames.dispo() ? AnimLames.coude(j, droite ? "rightArm" : "leftArm") : null;
        if (coude != null && Math.abs(coude[1]) > 1e-3F) {
            ps.translate(0, 0.25F, 0);
            ps.mulPose(new org.joml.Quaternionf().rotationAxis(coude[1], Mth.cos(coude[0]), 0, Mth.sin(coude[0])));
            ps.translate(0, -0.25F, 0);
        }
        poser(ps, buffers, lumiere, modele(s, groupe), -1 * sg, 9, 0, L, W, 1);
        ps.popPose();
    }

    // ------------------------------------------------------------------ les gestes

    private static float doux(float t) {
        t = Mth.clamp(t, 0, 1);
        return t * t * (3 - 2 * t);
    }

    /**
     * Après la pose habituelle, quand aucune animation de lame ne joue : la façon de tenir chaque lame (à deux mains pour
     * Kubikiribôchô et Hiramekarei, une lame dans chaque main pour Kiba, la hache et le marteau pour Kabutowari).
     */
    public static void poser(PlayerModel<?> m, Player j) {
        ItemStack main = j.getMainHandItem();
        Sorte s = Lames.sorte(main);
        if (s == null || main.has(Lames.LANCEE.get()) || AnimLames.dispo() && AnimLames.actif(j)) {
            return;
        }
        boolean droitier = j.getMainArm() == HumanoidArm.RIGHT;
        ModelPart bras = droitier ? m.rightArm : m.leftArm, autre = droitier ? m.leftArm : m.rightArm;
        float sg = droitier ? 1 : -1;
        boolean charge = j.isUsingItem() && j.getUseItem() == main;
        if (s == Sorte.KUBIKIRIBOCHO || s == Sorte.HIRAMEKAREI) {
            float x = charge ? -2.9F : -0.75F + m.head.xRot * 0.3F;
            bras.xRot = x;
            bras.yRot = -0.15F * sg;
            bras.zRot = 0;
            autre.xRot = x - 0.1F;
            autre.yRot = 0.6F * sg;
            autre.zRot = 0;
        } else if ((s == Sorte.KIBA || s == Sorte.KABUTOWARI) && j.getOffhandItem().isEmpty()) {
            autre.xRot = bras.xRot;
            autre.yRot = -bras.yRot;
        }
        m.rightSleeve.copyFrom(m.rightArm);
        m.leftSleeve.copyFrom(m.leftArm);
    }

    // ------------------------------------------------------------------ les traînées

    /**
     * Les traînées de lumière des coups en cours, de chaque joueur (dessinées dans le monde, même à la première personne) :
     * le chemin exact de la pointe de la lame pendant les derniers ticks, calculé sur le squelette de l'aperçu.
     */
    @SubscribeEvent
    public static void trainees(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES || ETATS.isEmpty()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return;
        }
        float partiel = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        Vec3 c0 = event.getCamera().getPosition();
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        VertexConsumer vc = buffers.getBuffer(RenderType.lightning());
        Matrix4f mat = event.getPoseStack().last().pose();
        boolean rien = true;
        float[] tt = new float[1];
        for (Player j : mc.level.players()) {
            CombosLames.Coup coup = enCours(j, partiel, tt);
            if (coup == null || AVALES.contains(j.getId()) || tt[0] <= coup.trace0()) {
                continue;
            }
            Sorte s = Lames.sorte(j.getMainHandItem());
            CombosLames.Lame l = CombosLames.de(s);
            if (l == null) {
                continue;
            }
            rien = false;
            dessinerTrainee(vc, mat, j, s, l, coup, tt[0], partiel, c0);
        }
        if (!rien) {
            buffers.endBatch(RenderType.lightning());
        }
    }

    private static void dessinerTrainee(VertexConsumer vc, Matrix4f mat, Player j, Sorte s, CombosLames.Lame l, CombosLames.Coup coup,
                                        float t, float partiel, Vec3 c0) {
        float fin = Math.min(t, coup.trace1() + 0.5F), debut = Math.max(coup.trace0(), fin - 4.5F);
        float eteint = Math.max(0, t - coup.trace1() - 0.5F), force = Math.max(0, 1 - eteint / 3);
        if (fin <= debut || force <= 0) {
            return;
        }
        boolean gaucher = j.getMainArm() == HumanoidArm.LEFT;
        float lacet = Mth.rotLerp(partiel, j.yBodyRotO, j.yBodyRot) * Mth.DEG_TO_RAD;
        float cos = Mth.cos(-lacet), sin = Mth.sin(-lacet);
        Vec3 pieds = j.getPosition(partiel).subtract(c0);
        int n = Math.max(2, (int) Math.ceil((fin - debut) / 0.15F));
        Vec3[] bouts = new Vec3[n + 1], milieux = new Vec3[n + 1];
        for (int i = 0; i <= n; i++) {
            Vector3f[] b = CombosLames.bout(CombosLames.pose(coup, debut + (fin - debut) * i / n), l.pointe(), s == Sorte.KABUTOWARI);
            for (int q = 0; q < 2; q++) {
                Vector3f v = b[q];
                float x = gaucher ? -v.x : v.x;
                // le repère du joueur (l'avant vers +z, la gauche vers +x) tourné comme son corps
                Vec3 w = pieds.add(x * cos + v.z * sin, v.y, -x * sin + v.z * cos);
                if (q == 0) {
                    bouts[i] = w;
                } else {
                    milieux[i] = w;
                }
            }
        }
        int c = l.couleur();
        float r = (c >> 16 & 255) / 255F, g = (c >> 8 & 255) / 255F, bl = (c & 255) / 255F;
        for (int i = 0; i < n; i++) {
            float a0 = (float) Math.pow((float) i / n, 1.6) * force, a1 = (float) Math.pow((float) (i + 1) / n, 1.6) * force;
            quad(vc, mat, milieux[i], milieux[i + 1], bouts[i + 1], bouts[i], r, g, bl, a0 * 0.12F, a1 * 0.12F, a1, a0);
        }
    }

    /** Un quadrilatère de lumière (les deux faces), chaque coin avec son intensité. */
    private static void quad(VertexConsumer vc, Matrix4f m, Vec3 a, Vec3 b, Vec3 c, Vec3 d, float r, float g, float bl,
                             float aa, float ab, float ac, float ad) {
        Vec3[] p = {a, b, c, d};
        float[] al = {aa, ab, ac, ad};
        for (int k = 0; k < 4; k++) {
            vc.addVertex(m, (float) p[k].x, (float) p[k].y, (float) p[k].z).setColor(r, g, bl, al[k]);
        }
        for (int k = 3; k >= 0; k--) {
            vc.addVertex(m, (float) p[k].x, (float) p[k].y, (float) p[k].z).setColor(r, g, bl, al[k]);
        }
    }

    // ------------------------------------------------------------------ l'objet : à la première personne, l'icône, par terre

    static final class Rendu extends BlockEntityWithoutLevelRenderer {
        Rendu() {
            super(Minecraft.getInstance().getBlockEntityRenderDispatcher(), Minecraft.getInstance().getEntityModels());
        }

        @Override
        public void renderByItem(ItemStack stack, ItemDisplayContext ctx, PoseStack ps, MultiBufferSource buffers, int lumiere, int recouvre) {
            Sorte s = Lames.sorte(stack);
            // à la troisième personne, c'est la couche du joueur qui la dessine (sur le bras)
            if (s == null || ctx == ItemDisplayContext.THIRD_PERSON_LEFT_HAND || ctx == ItemDisplayContext.THIRD_PERSON_RIGHT_HAND
                    || ctx.firstPerson() && stack.has(Lames.LANCEE.get())) {
                return;
            }
            Minecraft mc = Minecraft.getInstance();
            CombosLames.Lame l = CombosLames.de(s);
            float[] t = {l == null ? 30 : l.pointe(), l == null ? 13 : l.pointe() * 0.45F};
            ps.pushPose();
            ps.translate(0.5F, 0.5F, 0.5F);
            float k;
            if (ctx == ItemDisplayContext.GUI) {
                ps.mulPose(Axis.ZP.rotationDegrees(-45));
                k = 1.32F / (t[0] + 4);
                ps.scale(k, k, k);
                ps.translate(0, -t[1], 0);
            } else if (ctx.firstPerson()) {
                // à la première personne : le coup du combo fait aussi bouger la lame à l'écran
                float[] tt = new float[1];
                CombosLames.Coup coup = mc.player == null ? null : enCours(mc.player, mc.getTimer().getGameTimeDeltaPartialTick(false), tt);
                if (coup != null && (ctx == ItemDisplayContext.FIRST_PERSON_RIGHT_HAND) == (mc.player.getMainArm() == HumanoidArm.RIGHT)) {
                    premierePersonne(ps, coup, tt[0]);
                }
                // grande à l'écran : toutes les lames prennent à peu près la même place, la prise dans la main
                ps.mulPose(Axis.ZP.rotationDegrees(-45));
                ps.translate(0, -0.42F, 0);
                k = 1.3F / (t[0] + 4);
                ps.scale(k, k, k);
            } else {
                ps.mulPose(Axis.ZP.rotationDegrees(-45));
                k = 0.8F / (t[0] + 4);
                ps.scale(k, k, k);
                ps.translate(0, -t[1], 0);
            }
            int g = groupe(s, stack, ctx.firstPerson() ? mc.player : null);
            ModeleVent m = modele(s, g);
            if (m != null) {
                m.dessiner(buffers, ps.last(), lumiere, 1, 1);
            }
            if (s == Sorte.KABUTOWARI && ctx == ItemDisplayContext.GUI && modele(s, 1) != null) {
                ps.translate(6, -2, 0);
                modele(s, 1).dessiner(buffers, ps.last(), lumiere, 1, 1);
            }
            ps.popPose();
        }
    }

    /**
     * Le geste du coup à l'écran : la lame suit le bras du coup (de combien il monte, tourne, s'écarte par rapport à la
     * garde), le corps qui tourne et le poignet qui la tend.
     */
    private static void premierePersonne(PoseStack ps, CombosLames.Coup coup, float t) {
        float[] p = CombosLames.pose(coup, t), g = coup.cles()[0].v();
        float monte = p[CombosLames.BD] - g[CombosLames.BD], tourne = p[CombosLames.BD + 1] - g[CombosLames.BD + 1] + p[CombosLames.TOUR];
        // un tour complet : on ne garde que le balayage (entre -pi et pi)
        tourne = Mth.wrapDegrees(tourne * Mth.RAD_TO_DEG) * Mth.DEG_TO_RAD;
        ps.translate(0, 0, -0.35F * p[CombosLames.POIGNET]);
        ps.mulPose(Axis.YP.rotation(Mth.clamp(tourne, -1.4F, 1.4F) * 0.6F));
        ps.mulPose(Axis.XP.rotation(Mth.clamp(-monte, -1.6F, 1.6F) * 0.45F));
        ps.mulPose(Axis.ZP.rotation(p[CombosLames.ROULIS] * 0.25F));
    }

    // ------------------------------------------------------------------ ce qui vole

    /** La Kubikiribôchô lancée : à plat, elle tournoie. */
    static final class RenduLancee extends EntityRenderer<LameLancee> {
        RenduLancee(EntityRendererProvider.Context ctx) {
            super(ctx);
        }

        @Override
        public void render(LameLancee e, float yaw, float partiel, PoseStack ps, MultiBufferSource buffers, int lumiere) {
            ModeleVent m = modele(Sorte.KUBIKIRIBOCHO, 0);
            if (m == null) {
                return;
            }
            ps.pushPose();
            ps.translate(0, 0.2F, 0);
            ps.mulPose(Axis.YP.rotationDegrees((e.tickCount + partiel) * 47));
            ps.mulPose(Axis.XP.rotationDegrees(90));
            ps.scale(1 / 16F, 1 / 16F, 1 / 16F);
            ps.translate(0, -17.5F, 0);
            m.dessiner(buffers, ps.last(), lumiere, 1, 1);
            ps.popPose();
        }

        @Override
        public ResourceLocation getTextureLocation(LameLancee e) {
            return ModeleVent.BLANC;
        }
    }

    /** La grande lame de chakra : dressée, elle s'abat devant, puis s'efface. */
    static final class RenduChakra extends EntityRenderer<LameChakra> {
        RenduChakra(EntityRendererProvider.Context ctx) {
            super(ctx);
        }

        @Override
        public void render(LameChakra e, float yaw, float partiel, PoseStack ps, MultiBufferSource buffers, int lumiere) {
            ModeleVent m = modele(Sorte.HIRAMEKAREI, 2);
            if (m == null) {
                return;
            }
            float t = e.tickCount + partiel;
            float angle = t < LameChakra.FRAPPE ? Mth.lerp(doux(t / LameChakra.FRAPPE), -10, 96) : 96;
            float alpha = t < 10 ? 1 : Mth.clamp(1 - (t - 10) / (LameChakra.VIE - 10), 0, 1);
            CombosLames.Lame l = CombosLames.de(Sorte.HIRAMEKAREI);
            float k = e.longueur() / ((l == null ? 29.2F : l.pointe()) / 16) / 16;
            ps.pushPose();
            ps.mulPose(Axis.YP.rotationDegrees(-e.getYRot()));
            ps.mulPose(Axis.XP.rotationDegrees(angle));
            ps.scale(k, k, k);
            m.dessiner(buffers, ps.last(), 0xF000F0, 1, alpha * 0.999F);
            ps.popPose();
        }

        @Override
        public boolean shouldRender(LameChakra e, net.minecraft.client.renderer.culling.Frustum f, double x, double y, double z) {
            return true;
        }

        @Override
        public ResourceLocation getTextureLocation(LameChakra e) {
            return ModeleVent.BLANC;
        }
    }

    // ------------------------------------------------------------------ l'enregistrement

    @EventBusSubscriber(modid = Cabane.MODID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
    public static final class Enregistrement {
        @SubscribeEvent
        public static void couches(EntityRenderersEvent.AddLayers event) {
            for (PlayerSkin.Model modele : event.getSkins()) {
                if (event.getSkin(modele) instanceof PlayerRenderer rendu) {
                    rendu.addLayer(new Couche(rendu));
                }
            }
        }

        /** Les coups articulés (si Emotecraft et sa bibliothèque d'animation sont là). */
        @SubscribeEvent
        public static void preparer(net.neoforged.fml.event.lifecycle.FMLClientSetupEvent event) {
            if (AnimLames.dispo()) {
                event.enqueueWork(AnimLames::init);
            }
        }

        @SubscribeEvent
        public static void entites(EntityRenderersEvent.RegisterRenderers event) {
            event.registerEntityRenderer(Lames.LANCER.get(), RenduLancee::new);
            event.registerEntityRenderer(Lames.CHAKRA.get(), RenduChakra::new);
        }

        @SubscribeEvent
        public static void extensions(RegisterClientExtensionsEvent event) {
            IClientItemExtensions ext = new IClientItemExtensions() {
                private Rendu rendu;

                @Override
                public BlockEntityWithoutLevelRenderer getCustomRenderer() {
                    if (rendu == null) {
                        rendu = new Rendu();
                    }
                    return rendu;
                }
            };
            for (Sorte s : Sorte.values()) {
                event.registerItem(ext, Lames.objet(s));
            }
        }

        private Enregistrement() {
        }
    }

    private ClientLames() {
    }
}
