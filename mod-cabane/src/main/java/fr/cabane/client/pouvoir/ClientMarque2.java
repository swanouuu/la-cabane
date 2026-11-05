package fr.cabane.client.pouvoir;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import fr.cabane.Cabane;
import fr.cabane.pouvoir.Maudit;
import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import net.minecraft.client.resources.PlayerSkin;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Ce que le niveau 2 de la marque ajoute au corps, d'après les maquettes validées (docs/marque-forme2, puis
 * docs/marque3/apercu pour les ailes) : deux mains géantes en cubes dans le dos (palmées, griffues, le dos de la main
 * bossu et cornu vers l'avant, la paume creusée vers l'arrière, de la couleur de la peau), la grosse touffe de cheveux
 * gris, et le haut de Sasuke qui pend à la taille. Les ailes battent un peu tout
 * le temps, plus quand il marche, s'ouvrent quand il plane, et donnent un grand coup quand il s'envole.
 * <p>
 * Pour soi : accroupi au sol, le coup d'ailes ; en l'air, on plane (accroupi, on pique) ; le clic droit lance le Chidori.
 * <p>
 * Les coordonnées sont celles des maquettes : en pixels du modèle, les pieds à y = 0, l'avant vers +z, la gauche du
 * personnage vers +x.
 */
public final class ClientMarque2 {
    private static final ResourceLocation MAIN = Cabane.id("marque/main.bin");
    private static final ResourceLocation BLANC = Cabane.id("textures/marque/blanc.png");

    // ================================================================ l'aile : la main en cubes

    /**
     * L'aile : une main géante en cubes, palmée, validée dans l'aperçu docs/marque3/apercu (2026-11-03) et calculée
     * d'avance par docs/marque3/apercu/construire.mjs dans assets/cabane/marque/main.bin. Ses coordonnées : x vers
     * l'extérieur, y vers le haut, z vers l'avant du joueur, en pixels de la main (avant ECHELLE).
     */
    static final float ECHELLE = 0.68F;
    /** Posée comme dans l'aperçu : la main un peu relevée, et reculée derrière le dos (rien ne passe devant le ventre). */
    static final float RELEVE = 0.18F, RECUL = -2.6F;

    /** Par face : 4 coins (x, y, z), leurs 4 couleurs (r, g, b, l'ombre des coins comprise) et la normale de la face. */
    private static float[] coins;
    private static byte[] couleurs;
    private static float[] normales;
    private static boolean essaye;

    private static boolean charger() {
        if (!essaye) {
            essaye = true;
            try (var flux = new DataInputStream(new BufferedInputStream(Minecraft.getInstance().getResourceManager().open(MAIN)))) {
                int n = flux.readInt();
                float[] c = new float[n * 12], nor = new float[n * 3];
                byte[] col = new byte[n * 12];
                for (int f = 0; f < n; f++) {
                    for (int k = 0; k < 12; k++) {
                        c[f * 12 + k] = flux.readFloat();
                    }
                    flux.readFully(col, f * 12, 12);
                    for (int k = 0; k < 3; k++) {
                        nor[f * 3 + k] = flux.readFloat();
                    }
                }
                coins = c;
                couleurs = col;
                normales = nor;
                Cabane.LOGGER.info("Marque maudite : main de {} faces", n);
            } catch (IOException | RuntimeException e) {
                Cabane.LOGGER.warn("Marque maudite : main illisible", e);
            }
        }
        return coins != null;
    }

    // ================================================================ le dessin

    /**
     * Les deux ailes : k de 0 (rien) à 1 (dépliées), battement l'angle dont elles s'ouvrent ou se referment. Au niveau 3
     * (t3 ≥ 0), l'aile gauche est celle de {@link ClientMarque3} : elle se fissure et devient les serpents.
     */
    private static void ailes(PoseStack ps, MultiBufferSource buffers, int lumiere, int recouvre, float k, float battement, AbstractClientPlayer joueur,
            double t3, float partiel) {
        if (k <= 0.01F || !charger()) {
            return;
        }
        VertexConsumer c = buffers.getBuffer(RenderType.entityCutoutNoCull(BLANC));
        for (int s = -1; s <= 1; s += 2) {
            // accrochée en haut du dos, de part et d'autre de la colonne ; ouvertes en V vers l'arrière, un peu penchées en
            // arrière. L'aile droite est la gauche en miroir : ses x changent de signe, et ses rotations autour de y et z
            // aussi (sans échelle négative)
            ps.pushPose();
            ps.translate(s * 2.4F, 21.5F, -3F);
            ps.scale(ECHELLE * k, ECHELLE * k, ECHELLE * k);
            ps.mulPose(Axis.YP.rotation(s * (0.3F + battement)));
            ps.mulPose(Axis.XP.rotation(-0.12F));
            ps.translate(0, 0, RECUL);
            ps.mulPose(Axis.ZP.rotation(s * RELEVE));
            if (s == 1 && t3 >= 0) {
                ClientMarque3.aileGauche(c, ps.last(), joueur, t3, 0.3F + battement, partiel, lumiere, recouvre);
            } else {
                main(c, ps.last(), lumiere, recouvre, s);
            }
            ps.popPose();
        }
    }

    /** La main, face par face, avec la couleur de chaque coin ; sx = -1 : la main en miroir (l'aile droite). */
    static void main(VertexConsumer c, PoseStack.Pose pose, int lumiere, int recouvre, int sx) {
        for (int f = 0, n = normales.length / 3; f < n; f++) {
            float nx = sx * normales[f * 3], ny = normales[f * 3 + 1], nz = normales[f * 3 + 2];
            for (int q = 0; q < 4; q++) {
                int i = f * 12 + q * 3;
                c.addVertex(pose, sx * coins[i], coins[i + 1], coins[i + 2]).setColor(couleurs[i] & 255, couleurs[i + 1] & 255, couleurs[i + 2] & 255, 255)
                        .setUv(0.5F, 0.5F).setOverlay(recouvre).setLight(lumiere).setNormal(pose, nx, ny, nz);
            }
        }
    }

    /** Une mèche en pointe : une pyramide, sa base carrée autour de b (perpendiculaire à d). */
    private static void meche(VertexConsumer c, PoseStack.Pose pose, Vec3 b, Vec3 d, double longueur, double largeur, int rouge, int vert, int bleu,
            int lumiere, int recouvre) {
        d = d.normalize();
        Vec3 e1 = d.cross(new Vec3(0, 1, 0));
        if (e1.length() < 0.2) {
            e1 = d.cross(new Vec3(1, 0, 0));
        }
        e1 = e1.normalize();
        Vec3 e2 = d.cross(e1);
        Vec3[] base = new Vec3[4];
        int[][] signes = {{-1, -1}, {1, -1}, {1, 1}, {-1, 1}};
        for (int k = 0; k < 4; k++) {
            base[k] = b.add(e1.scale(signes[k][0] * largeur / 2)).add(e2.scale(signes[k][1] * largeur / 2));
        }
        Vec3 pointe = b.add(d.scale(longueur));
        for (int k = 0; k < 4; k++) {
            Vec3 p0 = base[k], p1 = base[(k + 1) % 4];
            Vec3 n = p1.subtract(p0).cross(pointe.subtract(p0)).normalize();
            if (n.dot(p0.subtract(b)) < 0) {
                n = n.scale(-1);
            }
            for (Vec3 p : new Vec3[]{p0, p1, pointe, pointe}) {
                c.addVertex(pose, (float) p.x, (float) p.y, (float) p.z).setColor(rouge, vert, bleu, 255).setUv(0.5F, 0.5F).setOverlay(recouvre)
                        .setLight(lumiere).setNormal(pose, (float) n.x, (float) n.y, (float) n.z);
            }
        }
    }

    /** Une mèche des cheveux : sa base sur la tête, sa direction, sa longueur, sa largeur. */
    private record Meche(Vec3 b, Vec3 d, double l, double w) {
    }

    /**
     * La grosse touffe gris-bleu, ébouriffée : de longues mèches en pointe tombent dans le dos, d'autres partent sur les
     * côtés et en arrière, des pointes hérissées sur le dessus, de courtes mèches sur le front.
     */
    private static final List<Meche> MECHES = new ArrayList<>();

    static {
        for (double y : new double[]{31.2, 28.8, 26.4}) {
            for (double x : new double[]{-3.5, -1.2, 1.2, 3.5}) {
                MECHES.add(new Meche(new Vec3(x, y, -4.2), new Vec3(x * 0.16, -0.8 - (31.2 - y) * 0.04, -0.6), 9 + (31.2 - y) * 0.5, 3.6));
            }
        }
        for (int sx = -1; sx <= 1; sx += 2) {
            for (double[] yz : new double[][]{{31.2, -2.6}, {31.2, 0.4}, {28.8, -3.2}, {28.8, -0.8}, {26.6, -2.4}, {26.6, 0.6}}) {
                MECHES.add(new Meche(new Vec3(sx * 4.2, yz[0], yz[1]), new Vec3(sx * 0.8, -0.5, -0.5), 6.5, 3.4));
            }
        }
        for (double x : new double[]{-3.2, 0, 3.2}) {
            for (double z : new double[]{-2.8, 0.2, 3}) {
                MECHES.add(new Meche(new Vec3(x, 32.1, z), new Vec3(x * 0.2, 0.5, -0.85), 5.5 + (3 - z) * 0.45, 3.6));
            }
        }
        for (double x : new double[]{-3.2, -1.1, 1.1, 3.2}) {
            MECHES.add(new Meche(new Vec3(x, 31.6, 4.1), new Vec3(x * 0.15, -0.55, 0.8), 2.8, 2.8));
        }
    }

    private static void cheveux(PoseStack ps, MultiBufferSource buffers, int lumiere, int recouvre, float k) {
        VertexConsumer c = buffers.getBuffer(RenderType.entityCutoutNoCull(BLANC));
        PoseStack.Pose pose = ps.last();
        for (int i = 0; i < MECHES.size(); i++) {
            Meche h = MECHES.get(i);
            double hasard = hache(i, 7, 13);
            double ton = 0.82 + 0.28 * hasard;
            meche(c, pose, h.b(), h.d(), h.l() * k * (0.85 + 0.3 * hasard), h.w() * Math.min(1, 0.4 + k), (int) Math.min(255, 150 * ton),
                    (int) Math.min(255, 160 * ton), (int) Math.min(255, 182 * ton), lumiere, recouvre);
        }
    }

    /** Le haut de Sasuke, enlevé : roulé à la taille, il pend dans le dos et sur les côtés, ouvert devant. */
    private static void hautPendant(PoseStack ps, MultiBufferSource buffers, int lumiere, int recouvre) {
        VertexConsumer c = buffers.getBuffer(RenderType.entityCutoutNoCull(BLANC));
        PoseStack.Pose pose = ps.last();
        pan(c, pose, -4.25, 4.25, -2.25, -2.25, 0, -1, 5.2, 1, lumiere, recouvre);
        pan(c, pose, -4.25, -4.25, -2.25, 2.25, -1, 0, 6.2, 2, lumiere, recouvre);
        pan(c, pose, 4.25, 4.25, 2.25, -2.25, 1, 0, 6.2, 3, lumiere, recouvre);
        pan(c, pose, -4.25, -1.6, 2.25, 2.25, 0, 1, 7.8, 4, lumiere, recouvre);
        pan(c, pose, 1.6, 4.25, 2.25, 2.25, 0, 1, 7.8, 5, lumiere, recouvre);
    }

    /** Un pan du haut : des colonnes le long d'une ligne autour des hanches (de (x0, z0) à (x1, z1)), qui s'évasent en descendant. */
    private static void pan(VertexConsumer c, PoseStack.Pose pose, double x0, double x1, double z0, double z1, double sx, double sz, double bas,
            int graine, int lumiere, int recouvre) {
        double pas = 0.5;
        int n = (int) Math.round(Math.hypot(x1 - x0, z1 - z0) / pas);
        for (int i = 0; i < n; i++) {
            double ax = x0 + (x1 - x0) * i / n, az = z0 + (z1 - z0) * i / n;
            double bx = x0 + (x1 - x0) * (i + 1) / n, bz = z0 + (z1 - z0) * (i + 1) / n;
            double fond = bas + 1.4 * hache(i, graine, 4) - (i % 3 == 1 ? 0.6 : 0);
            double pli = 0.8 + 0.28 * (0.5 + 0.5 * Math.sin((i + graine) * 1.9 + 0.7)) + 0.06 * hache(i + graine, 2, 8);
            // le bourrelet, à la ceinture
            for (double y = 14.2; y > 12.4 + 1e-6; y -= pas) {
                double r0 = 0.35 + 0.25 * Math.sin((14.2 - y) / 1.8 * Math.PI), r1 = 0.35 + 0.25 * Math.sin((14.2 - y + pas) / 1.8 * Math.PI);
                tissu(c, pose, ax, az, bx, bz, sx, sz, y, y - pas, r0, r1, pli * (y > 13.6 ? 1.08 : 0.92), lumiere, recouvre);
            }
            // le pan qui pend
            for (double y = 12.4; y > fond + 1e-6; y -= pas) {
                double y1 = Math.max(fond, y - pas);
                tissu(c, pose, ax, az, bx, bz, sx, sz, y, y1, 0.3 + (12.4 - y) * 0.14, 0.3 + (12.4 - y1) * 0.14, pli * (y1 - fond < 0.6 ? 0.82 : 1),
                        lumiere, recouvre);
            }
        }
    }

    private static void tissu(VertexConsumer c, PoseStack.Pose pose, double ax, double az, double bx, double bz, double sx, double sz, double y0,
            double y1, double r0, double r1, double ton, int lumiere, int recouvre) {
        int r = (int) Math.min(255, 178 * ton), v = (int) Math.min(255, 170 * ton), b = (int) Math.min(255, 198 * ton);
        double[][] p = {{ax + sx * r0, y0, az + sz * r0}, {bx + sx * r0, y0, bz + sz * r0}, {bx + sx * r1, y1, bz + sz * r1}, {ax + sx * r1, y1, az + sz * r1}};
        for (double[] q : p) {
            c.addVertex(pose, (float) q[0], (float) q[1], (float) q[2]).setColor(r, v, b, 255).setUv(0.5F, 0.5F).setOverlay(recouvre).setLight(lumiere)
                    .setNormal(pose, (float) sx, 0, (float) sz);
        }
    }

    private static double hache(int i, int j, int k) {
        int h = i * 374761393 + j * 668265263 + k * 2147483647;
        h = (h ^ (h >>> 13)) * 1274126177;
        return ((h ^ (h >>> 16)) & 0xFFFFFFFFL) / 4294967296.0;
    }

    /**
     * Pour chaque marqué : où en est son battement (la phase avance à son rythme : quand il se met à marcher ou s'arrête,
     * le rythme change sans que les ailes sautent), l'angle lissé, et le temps du dernier calcul.
     */
    private static final java.util.Map<java.util.UUID, float[]> BATTEMENTS = new java.util.HashMap<>();

    /**
     * L'angle des ailes : un petit battement tout le temps, plus ample et plus vif quand il marche ; ouvertes quand il
     * plane ; et le grand coup d'ailes (elles balaient vers l'avant puis reviennent). Le passage de l'un à l'autre est
     * lissé (sinon les ailes tressautent quand il démarre, s'arrête ou saute).
     */
    static float battement(AbstractClientPlayer joueur, ClientMaudit.Forme2 f, float partiel) {
        float t = (joueur.tickCount + partiel) / 20F;
        float[] b = BATTEMENTS.computeIfAbsent(joueur.getUUID(), k -> new float[]{0, Float.NaN, t});
        float dt = t - b[2];
        if (dt < 0 || dt > 0.5F) {
            dt = 0;
        }
        b[2] = t;
        float marche = Math.min(1, joueur.walkAnimation.speed(partiel) * 1.5F);
        b[0] = (b[0] + dt * Mth.TWO_PI * (1.1F + 0.9F * marche)) % (Mth.TWO_PI * 100);
        float voulu = (0.06F + 0.14F * marche) * Mth.sin(b[0]);
        if (!joueur.onGround() && joueur.getDeltaMovement().y < 0 && !joueur.isInWater() && !joueur.getAbilities().flying) {
            voulu = -0.22F + 0.05F * Mth.sin(t * Mth.TWO_PI * 0.6F);
        }
        // l'angle rejoint doucement l'angle voulu (en un dixième de seconde environ)
        b[1] = Float.isNaN(b[1]) ? voulu : b[1] + (voulu - b[1]) * Math.min(1, dt * 10);
        float a = b[1];
        if (f.battement() >= 0) {
            double tau = (System.nanoTime() - f.battement()) / 1e9;
            if (tau < 0.6) {
                a -= 0.75F * Mth.sin((float) (tau / 0.6) * Mth.PI);
            }
        }
        return a;
    }

    /** Les ailes, les cheveux et le haut pendant, collés au corps et à la tête (ils suivent ses mouvements). */
    public static final class Couche extends RenderLayer<AbstractClientPlayer, PlayerModel<AbstractClientPlayer>> {
        public Couche(RenderLayerParent<AbstractClientPlayer, PlayerModel<AbstractClientPlayer>> parent) {
            super(parent);
        }

        @Override
        public void render(PoseStack ps, MultiBufferSource buffers, int lumiere, AbstractClientPlayer joueur, float marche, float vitesse,
                float partiel, float age, float tete, float tangage) {
            ClientMaudit.Forme2 f = ClientMaudit.forme2(joueur.getUUID());
            if (f == null || joueur.isInvisible()) {
                return;
            }
            int recouvre = LivingEntityRenderer.getOverlayCoords(joueur, 0);
            PlayerModel<AbstractClientPlayer> m = getParentModel();
            ps.pushPose();
            m.body.translateAndRotate(ps);
            versMaquette(ps);
            ailes(ps, buffers, lumiere, recouvre, f.ailes(), battement(joueur, f, partiel), joueur, f.t3(), partiel);
            if (f.torseNu()) {
                hautPendant(ps, buffers, lumiere, recouvre);
            }
            ps.popPose();
            if (f.cheveux() > 0.01F) {
                ps.pushPose();
                m.head.translateAndRotate(ps);
                versMaquette(ps);
                cheveux(ps, buffers, lumiere, recouvre, f.cheveux());
                ps.popPose();
            }
        }

        /** Depuis le pivot d'une partie (au cou) : les coordonnées des maquettes (pieds à y = 0, en pixels, l'avant vers +z). */
        private static void versMaquette(PoseStack ps) {
            ps.scale(1 / 16F, 1 / 16F, 1 / 16F);
            ps.scale(1, -1, -1);
            ps.translate(0, -24, 0);
        }
    }

    // ================================================================ le panneau des touches

    /** Quand son envol sera rechargé (System.nanoTime) : le serveur vient de confirmer un coup d'ailes. */
    private static long envolPret;

    static void envole() {
        envolPret = System.nanoTime() + Maudit.RECHARGE_ENVOL * 50_000_000L;
    }

    private static double rechargeEnvol() {
        return Math.max(0, (envolPret - System.nanoTime()) / 1e9);
    }

    /** À gauche de l'écran, comme pour les autres pouvoirs : ce que fait chaque touche, et ce qui se recharge. */
    private static void panneau(GuiGraphics g) {
        Minecraft mc = Minecraft.getInstance();
        ClientMaudit.Etat e = mc.player == null || mc.options.hideGui ? null : ClientMaudit.etat(mc.player.getUUID());
        if (e == null || !e.pret()) {
            return;
        }
        String eveil = ClientSusano.EVEIL.getTranslatedKeyMessage().getString();
        String[][] lignes;
        boolean[] grises;
        if (e.niveau() >= 3) {
            boolean tient = ClientMarque3.tient(mc.player.getUUID());
            double serpents = ClientMarque3.rechargeSerpents(), avale = ClientMarque3.rechargeAvale(), vol = ClientMarque3.vol(),
                    volRecharge = ClientMarque3.rechargeVol(), ulti = ClientSerpentGeant.recharge();
            lignes = new String[][]{
                    {Clavier.gauche(), tient ? "Jeter" : "Coups boostés"},
                    {Clavier.droite(), tient ? "Croquer" : serpents > 0 ? String.format("Serpents (%.0f s)", Math.ceil(serpents)) : "Serpents"},
                    {Clavier.accroupi() + " + " + Clavier.droite(), avale > 0 ? String.format("Avaler (%.0f s)", Math.ceil(avale)) : "Avaler"},
                    {Clavier.accroupi(), vol > 0 ? String.format("Vol (%.0f s)", Math.ceil(vol))
                            : volRecharge > 0 ? String.format("Bond et vol (%.0f s)", Math.ceil(volRecharge)) : "Bond et vol"},
                    {"2× " + Clavier.accroupi(), vol > 0 ? "Retomber" : ""},
                    {Clavier.nom(fr.cabane.client.hachibi4.ClientHachibi4.ULTI), ulti > 0 && !ClientSerpentGeant.enCours() ? String.format("Grand serpent (%.0f s)", Math.ceil(ulti)) : "Grand serpent"},
                    {eveil, "Rendormir"}};
            grises = new boolean[]{false, !tient && serpents > 0, avale > 0, vol <= 0 && volRecharge > 0, false, ulti > 0, false};
        } else if (e.niveau() >= 2) {
            double chidori = ClientChidori.recharge(), envol = rechargeEnvol();
            lignes = new String[][]{
                    {Clavier.droite(), chidori > 0 ? String.format("Chidori (%.0f s)", Math.ceil(chidori)) : "Chidori"},
                    {Clavier.accroupi(), envol > 0 ? String.format("Envol (%.0f s)", Math.ceil(envol)) : "Envol / piqué"},
                    {eveil, e.trois() ? "Niveau 3" : "Rendormir"}};
            grises = new boolean[]{chidori > 0, envol > 0, false};
        } else {
            lignes = new String[][]{{eveil, e.deux() ? "Niveau 2" : "Rendormir"}};
            grises = new boolean[]{false};
        }
        Font font = mc.font;
        String titre = e.niveau() >= 2 ? "MARQUE MAUDITE  ·  NIV. " + e.niveau() : "MARQUE MAUDITE";
        PanneauPouvoir.dessiner(g, font, titre, 0xFFB070F0, lignes, grises);
    }

    // ================================================================ les événements

    @EventBusSubscriber(modid = Cabane.MODID, value = Dist.CLIENT)
    public static final class Couches {
        @SubscribeEvent
        public static void enregistrer(RegisterGuiLayersEvent event) {
            event.registerAboveAll(Cabane.id("pouvoir_marque"), (g, delta) -> panneau(g));
        }

        private Couches() {
        }
    }

    @EventBusSubscriber(modid = Cabane.MODID, value = Dist.CLIENT)
    public static final class Evenements {
        private static boolean accroupiAvant;
        private static long dernierClic;

        @SubscribeEvent
        public static void couches(EntityRenderersEvent.AddLayers event) {
            for (PlayerSkin.Model modele : event.getSkins()) {
                if (event.getSkin(modele) instanceof PlayerRenderer rendu) {
                    rendu.addLayer(new Couche(rendu));
                }
            }
        }

        /** Pour soi, ailes sorties : accroupi au sol, le coup d'ailes ; en l'air, on plane (accroupi, on pique). */
        @SubscribeEvent
        public static void tick(ClientTickEvent.Post event) {
            Minecraft mc = Minecraft.getInstance();
            LocalPlayer moi = mc.player;
            if (moi == null || mc.level == null) {
                return;
            }
            boolean ailes = ClientMaudit.ailesSorties(moi.getUUID()) && !ClientChidori.enCours(moi) && !ClientMaudit.niveau3(moi.getUUID());
            boolean accroupi = mc.options.keyShift.isDown();
            if (ailes && accroupi && !accroupiAvant && moi.onGround() && mc.screen == null && rechargeEnvol() <= 0) {
                PacketDistributor.sendToServer(new Maudit.Envol());
            }
            accroupiAvant = accroupi;
            if (!ailes || moi.onGround() || moi.isInWater() || moi.isInLava() || moi.getAbilities().flying || moi.isPassenger()
                    || moi.onClimbable() || moi.isFallFlying()) {
                return;
            }
            Vec3 d = moi.getDeltaMovement();
            if (d.y >= 0) {
                return;
            }
            if (accroupi) {
                // en piqué
                moi.setDeltaMovement(d.x, Math.max(d.y - 0.06, -1.2), d.z);
                return;
            }
            // il plane : il descend doucement, et file là où il regarde s'il avance
            double y = Math.max(d.y, -0.11);
            Vec3 h = new Vec3(d.x, 0, d.z);
            if (moi.input.forwardImpulse > 0) {
                Vec3 regard = moi.getLookAngle();
                Vec3 voulu = new Vec3(regard.x, 0, regard.z).normalize().scale(0.42);
                h = h.lerp(voulu, 0.08);
            }
            moi.setDeltaMovement(h.x, y, h.z);
        }

        /** Ailes sorties, le clic droit lance le Chidori (le serveur choisit la cible). */
        @SubscribeEvent
        public static void clic(InputEvent.InteractionKeyMappingTriggered event) {
            Minecraft mc = Minecraft.getInstance();
            if (!event.isUseItem() || mc.player == null || !ClientMaudit.ailesSorties(mc.player.getUUID()) || ClientMaudit.niveau3(mc.player.getUUID())) {
                return;
            }
            event.setCanceled(true);
            event.setSwingHand(false);
            if (System.nanoTime() - dernierClic > 500_000_000L && !ClientChidori.enCours(mc.player)) {
                dernierClic = System.nanoTime();
                PacketDistributor.sendToServer(new Maudit.Lancer());
            }
        }

        private Evenements() {
        }
    }

    private ClientMarque2() {
    }
}
