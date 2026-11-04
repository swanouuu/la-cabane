package fr.cabane.client.hachibi4;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.math.Axis;
import fr.cabane.Cabane;
import fr.cabane.client.pouvoir.Clavier;
import fr.cabane.pouvoir.Hachibi4;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.client.Camera;
import net.minecraft.client.CameraType;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.RenderHandEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.lwjgl.glfw.GLFW;

/**
 * L'ulti de Hachibi 4 chez chaque joueur (voir {@link Hachibi4} et docs/hachibi4) : le chakra rouge qui monte et
 * converge sur lui, la boule rouge qui se referme, gonfle jusqu'à tout avaler, se fissure et éclate ; le démon qui rugit
 * dans la fumée de chakra ; puis la bête (ModeleBete) qui suit son regard, abat ses poings, charge et tire la Bijuu Dama,
 * lève et abat ses huit queues, crache l'encre ; à la fin, elle redevient une boule de chakra qui se résorbe en lui.
 * Chez celui qui l'a, la caméra recule avec la boule et reste loin derrière la bête ; un panneau donne ses touches.
 */
public final class ClientHachibi4 {
    public static final KeyMapping ULTI = new KeyMapping("key.cabane.ulti", GLFW.GLFW_KEY_J, "key.categories.cabane");
    private static final ResourceLocation GRAIN = Cabane.id("textures/entity/hachibi_geant.png");
    private static final int LUMIERE = LightTexture.pack(13, 15);
    private static final RandomSource HASARD = RandomSource.create();
    private static final double G = Hachibi4.G;

    /** La transformation (en secondes), comme docs/hachibi4/generateur/transformation.mjs. */
    private static final double BOULE = 0.9, PLEINE = 1.5, ENORME = 3.0, FISSURES = 2.8, ECLAT = Hachibi4.TRANSFORMATION / 20.0;
    private static final double RAYON_MAX = 31 * G;
    private static final double DUREE = Hachibi4.DUREE / 20.0;
    /** À la fin, la boule revient et se résorbe en lui. */
    private static final double RETOUR = 1.0;
    /**
     * La caméra de celui qui est dans la bête : loin derrière, au-dessus de son épaule droite (pile derrière, sa bosse de
     * gorille cache sa tête et ses bras) ; elle part de 10 blocs, et recule avec la boule.
     */
    private static final double RECUL = 58 * G, HAUTEUR = 34 * G, EPAULE = 22 * G, RECUL_DEPART = 10;
    /** La tête, relevée de ce nombre de blocs. */
    private static final double TETE_HAUTE = 9 * G;

    private static final class Bete {
        long debut;
        long fin = -1;
        final long[] poings = {-1, -1};
        long charge = -1;
        long tir = -1;
        long dernierTir = -1;
        Vec3 depart, direction;
        long boum = -1;
        Vec3 boumPos;
        long queues = -1;
        long encre = -1;
        float lacet, lacetO;
        boolean lacetPret;
        double agePrecedent = -1;
        final double[] poingPrecedent = {9, 9};
        double queuesPrecedent = 9;
        double boumPrecedent = 9;
        /** La marche : où il était au tick d'avant, la phase du pas (un tour = deux poings posés), et à quel point il marche (0 à 1). */
        Vec3 avant;
        double pas, pasO;
        float marche, marcheO;
    }

    /** Un pas de la bête (un poing qui se lève, avance et se repose) couvre ce nombre de blocs. */
    private static final double FOULEE = 7 * G;

    private static final Map<UUID, Bete> BETES = new HashMap<>();
    private static CameraType vueAvant;
    private static long flash = -1;
    private static long secousse = -1;
    private static float forceSecousse;
    private static long dernierClic;
    private static boolean accroupiAvant, encreFaite;
    private static long accroupiDebut;

    private static double secondes(long depuis) {
        return (System.nanoTime() - depuis) / 1e9;
    }

    private static double lisse(double x) {
        x = Mth.clamp(x, 0, 1);
        return x * x * (3 - 2 * x);
    }

    private static double entre(double t, double a, double b) {
        return Mth.clamp((t - a) / (b - a), 0, 1);
    }

    private static double age(Bete b) {
        return secondes(b.debut);
    }

    /** Ce joueur est dans son ulti (boule ou bête) : les autres dessins de Hachibi se taisent. */
    public static boolean enUlti(UUID joueur) {
        return BETES.containsKey(joueur);
    }

    private static Bete moi() {
        Minecraft mc = Minecraft.getInstance();
        return mc.player == null ? null : BETES.get(mc.player.getUUID());
    }

    // ================================================================ ce que dit le serveur

    public static void geste(Hachibi4.Geste p) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return;
        }
        long maintenant = System.nanoTime();
        Bete b = BETES.get(p.joueur());
        if (p.geste() == Hachibi4.DEBUT) {
            b = new Bete();
            b.debut = maintenant - p.valeur() * 50_000_000L;
            BETES.put(p.joueur(), b);
            return;
        }
        if (b == null) {
            return;
        }
        switch (p.geste()) {
            case Hachibi4.FIN -> b.fin = maintenant;
            case Hachibi4.GESTE_POING -> b.poings[p.valeur()] = maintenant;
            case Hachibi4.GESTE_CHARGE -> b.charge = maintenant;
            case Hachibi4.GESTE_TIR -> {
                b.charge = -1;
                b.tir = maintenant;
                b.dernierTir = maintenant;
                b.depart = new Vec3(p.a());
                b.direction = new Vec3(p.b());
            }
            case Hachibi4.GESTE_BOUM -> {
                b.tir = -1;
                b.boum = maintenant;
                b.boumPos = new Vec3(p.a());
            }
            case Hachibi4.GESTE_QUEUES -> b.queues = maintenant;
            case Hachibi4.GESTE_ENCRE -> b.encre = maintenant;
            default -> { }
        }
    }

    // ================================================================ la caméra (CameraMixin)

    /** Où mettre la caméra de celui qui est dans son ulti (à la troisième personne), ou null : la caméra normale. */
    public static Vec3 camera(Camera camera, Entity entite, boolean detachee, float partiel) {
        Minecraft mc = Minecraft.getInstance();
        Bete b = moi();
        if (b == null || !detachee || entite != mc.player) {
            return null;
        }
        double s = age(b), k;
        if (b.fin >= 0) {
            k = 1 - lisse(secondes(b.fin) / RETOUR);
        } else if (s < ECLAT) {
            k = rayon(s) / RAYON_MAX;
        } else {
            k = 1;
        }
        double recul = Mth.lerp(k, RECUL_DEPART, RECUL), haut = Mth.lerp(k, 1.6, HAUTEUR), epaule = k * EPAULE;
        Vector3f f = camera.getLookVector(), gauche = camera.getLeftVector();
        Vec3 pied = entite.getPosition(partiel);
        Vec3 p = pied.add(0, haut, 0).subtract(f.x() * recul, f.y() * recul, f.z() * recul)
                .subtract(gauche.x() * epaule, 0, gauche.z() * epaule);
        return new Vec3(p.x, Math.max(p.y, pied.y + 2), p.z);
    }

    // ================================================================ le dessin

    /** Le rayon de la boule à l'instant s : elle naît autour de lui, puis gonfle de plus en plus vite. */
    private static double rayon(double s) {
        if (s < BOULE) {
            return 0;
        }
        if (s < PLEINE) {
            return 1.4 + 1.8 * lisse(entre(s, BOULE, PLEINE));
        }
        return 3.2 + (RAYON_MAX - 3.2) * Math.pow(entre(s, PLEINE, ENORME), 2.2);
    }

    /** Du repère de la bête (x à sa gauche, z devant) vers le monde, depuis ses pieds. */
    private static Vec3 monde(Vec3 pied, float lacet, Vec3 local) {
        float a = lacet * Mth.DEG_TO_RAD;
        return pied.add(Mth.cos(a) * local.x - Mth.sin(a) * local.z, local.y, Mth.sin(a) * local.x + Mth.cos(a) * local.z);
    }

    private static void dessiner(PoseStack ps, Vec3 cam, float partiel) {
        Minecraft mc = Minecraft.getInstance();
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        // après les particules, la lumière des entités est coupée : sans elle, la bête serait toute noire
        mc.gameRenderer.lightTexture().turnOnLightLayer();
        RenderType peau = RenderType.entityCutoutNoCull(GRAIN);
        for (Map.Entry<UUID, Bete> e : BETES.entrySet()) {
            Bete b = e.getValue();
            Player j = mc.level.getPlayerByUUID(e.getKey());
            if (j == null || !b.lacetPret) {
                continue;
            }
            double s = age(b);
            boolean visible = s >= ECLAT && (b.fin < 0 || secondes(b.fin) < 0.12);
            if (visible) {
                bete(ps, buffers, peau, cam, b, j, partiel, s - ECLAT);
            }
        }
        buffers.endBatch(peau);
        Matrix4f m = ps.last().pose();
        for (Map.Entry<UUID, Bete> e : BETES.entrySet()) {
            Bete b = e.getValue();
            Player j = mc.level.getPlayerByUUID(e.getKey());
            if (j == null || !b.lacetPret) {
                continue;
            }
            effets(buffers, m, cam, b, j, partiel);
        }
        buffers.endBatch(Types.TRANSLUCIDE);
        buffers.endBatch(Types.LUMIERE);
    }

    /** La bête, dans son repère : le corps qui respire, la tête, la mâchoire, les bras qui frappent, les queues. */
    private static void bete(PoseStack ps, MultiBufferSource.BufferSource buffers, RenderType peau, Vec3 cam, Bete b, Player j, float partiel, double t) {
        VertexConsumer c = buffers.getBuffer(peau);
        float lacet = Mth.rotLerp(partiel, b.lacetO, b.lacet);
        Vec3 pied = j.getPosition(partiel);
        // le rugissement en sortant de la boule
        double cri = lisse(t / 0.25) * (1 - lisse((t - 1.1) / 0.4));
        double charge = b.charge < 0 ? b.tir >= 0 && secondes(b.tir) < 0.4 ? 1 - secondes(b.tir) / 0.4 : 0 : lisse(secondes(b.charge) / 0.3);
        double crache = b.encre < 0 ? 0 : lisse(secondes(b.encre) / 0.15) * (1 - lisse((secondes(b.encre) - 0.6) / 0.3));
        double gueule = Math.max(cri, Math.max(charge, crache));
        double temps = (j.level().getGameTime() + partiel) / 20.0;
        // la marche à quatre pattes, en gorille : les poings se lèvent l'un après l'autre, le corps tangue et se soulève
        double pas = Mth.lerp(partiel, b.pasO, b.pas);
        double marche = Mth.lerp(partiel, b.marcheO, b.marche);
        ps.pushPose();
        ps.translate(pied.x - cam.x, pied.y - cam.y, pied.z - cam.z);
        ps.mulPose(Axis.YP.rotationDegrees(-lacet));
        ps.translate(0, marche * 0.7 * G * Math.abs(Math.sin(pas)), 0);
        ps.mulPose(Axis.ZP.rotationDegrees((float) (4 * marche * Math.sin(pas))));
        ps.mulPose(Axis.XP.rotationDegrees((float) (3 * marche + 2 * marche * Math.sin(2 * pas))));
        ps.mulPose(Axis.YP.rotationDegrees((float) (5 * marche * Math.sin(pas))));
        // il se tourne un peu vers le poing qui frappe
        float tourne = 0;
        for (int k = 0; k < 2; k++) {
            tourne += (float) ((k == 0 ? -1 : 1) * 8 * poidsPoing(b.poings[k]));
        }
        ps.mulPose(Axis.YP.rotationDegrees(tourne));
        ps.pushPose();
        ps.translate(0, Math.sin(temps * 0.9) * 0.2 * G + cri * 0.4 * G, 0);
        dessiner(c, ps, ModeleBete.CORPS);
        // la tête plus haute que celle du géant (qui regarde par terre) : sinon, vue de derrière, sa bosse de gorille la cache
        ps.translate(0, TETE_HAUTE, 1.5 * G);
        Vec3 pt = ModeleBete.PIVOT_TETE;
        ps.translate(pt.x, pt.y, pt.z);
        ps.mulPose(Axis.YP.rotationDegrees((float) (6 * Math.sin(temps * 0.23) + 4 * cri * Math.sin(temps * 23))));
        ps.mulPose(Axis.XP.rotationDegrees((float) (3 * Math.sin(temps * 0.31) - 18 - 24 * cri - 10 * charge - 8 * crache + 4 * marche * Math.sin(2 * pas + 0.6))));
        ps.translate(-pt.x, -pt.y, -pt.z);
        dessiner(c, ps, ModeleBete.TETE);
        Vec3 ch = ModeleBete.CHARNIERE;
        ps.translate(ch.x, ch.y, ch.z);
        ps.mulPose(Axis.XP.rotationDegrees((float) (4 + 32 * gueule)));
        ps.translate(-ch.x, -ch.y, -ch.z);
        dessiner(c, ps, ModeleBete.MACHOIRE);
        ps.popPose();
        // les bras : ils se lèvent (épaule et coude), puis s'abattent ; le poing touche le sol à 0,3 s
        for (int k = 0; k < 2; k++) {
            double tp = b.poings[k] < 0 ? 9 : secondes(b.poings[k]);
            double leve = tp < 0.2 ? lisse(tp / 0.2) : tp < 0.3 ? 1 - Math.pow((tp - 0.2) / 0.1, 2) : 0;
            // en marchant : ce poing se lève et avance pendant la moitié du pas, puis se repose (l'autre fait l'inverse)
            double foulee = marche * Math.max(0, Math.sin(pas + k * Math.PI)) * (1 - leve);
            Vec3 e = ModeleBete.EPAULES[k], co = ModeleBete.COUDES[k];
            ps.pushPose();
            ps.translate(e.x, e.y, e.z);
            ps.mulPose(Axis.XP.rotationDegrees((float) (-72 * leve - 32 * foulee)));
            ps.translate(-e.x, -e.y, -e.z);
            dessiner(c, ps, ModeleBete.BRAS_HAUT[k]);
            ps.translate(co.x, co.y, co.z);
            ps.mulPose(Axis.XP.rotationDegrees((float) (-45 * leve - 24 * foulee)));
            ps.translate(-co.x, -co.y, -co.z);
            dessiner(c, ps, ModeleBete.BRAS_BAS[k]);
            ps.popPose();
        }
        // les queues : en éventail (qui ondulent), levées, abattues, puis qui reviennent
        double tq = b.queues < 0 ? 9 : secondes(b.queues);
        int a = ModeleBete.EVENTAIL, z = ModeleBete.EVENTAIL;
        double k = 0;
        if (tq < 0.45) {
            z = ModeleBete.LEVEES;
            k = lisse(tq / 0.45);
        } else if (tq < 0.75) {
            a = z = ModeleBete.LEVEES;
        } else if (tq < 0.95) {
            a = ModeleBete.LEVEES;
            z = ModeleBete.ABATTUES;
            k = Math.pow((tq - 0.75) / 0.2, 2);
        } else if (tq < 1.6) {
            a = z = ModeleBete.ABATTUES;
        } else if (tq < 2.3) {
            a = ModeleBete.ABATTUES;
            k = lisse((tq - 1.6) / 0.7);
        }
        double calme = (a == ModeleBete.EVENTAIL ? 1 - k : 0) + (z == ModeleBete.EVENTAIL ? k : 0);
        if (a == z) {
            calme = a == ModeleBete.EVENTAIL ? 1 : 0;
        }
        for (int i = 0; i < 8; i++) {
            Vec3 r = ModeleBete.RACINES[i], cote = ModeleBete.COTES[i];
            // en marchant, elles ondulent plus fort, chacune avec son retard, et balancent de côté avec le pas
            double angle = (2.5 * Math.sin(temps * 0.5 + i) + 6 * cri * Math.sin(temps * 3 + i * 0.7) + 7 * marche * Math.sin(pas * 2 + i * 0.9)) * calme;
            ps.pushPose();
            ps.translate(r.x, r.y, r.z);
            ps.mulPose(Axis.YP.rotationDegrees((float) ((2.5 * Math.sin(temps * 0.37 + i * 1.3) + 6 * marche * Math.sin(pas + i * 0.5)) * calme)));
            ps.mulPose(new Quaternionf().rotateAxis((float) Math.toRadians(angle), (float) cote.x, 0, (float) cote.z));
            ps.translate(-r.x, -r.y, -r.z);
            // une fournée toutes les deux tentacules : en une seule (plus de 65 000 sommets), une partie de la bête disparaît
            if (i % 2 == 0) {
                buffers.endBatch(peau);
                c = buffers.getBuffer(peau);
            }
            melange(c, ps.last(), ModeleBete.TENTACULES_D[a][i], ModeleBete.TENTACULES_D[z][i], (float) k, ModeleBete.TENTACULES_C[i]);
            ps.popPose();
        }
        ps.popPose();
    }

    /** De 0 à 1 : à quel point ce poing est en train de frapper (pour que le corps se tourne vers lui). */
    private static double poidsPoing(long quand) {
        if (quand < 0) {
            return 0;
        }
        double t = secondes(quand);
        return lisse(t / 0.25) * (1 - lisse((t - 0.4) / 0.3));
    }

    private static void dessiner(VertexConsumer c, PoseStack ps, ModeleBete.Partie partie) {
        PoseStack.Pose pose = ps.last();
        float[] d = partie.d;
        int[] couleurs = partie.c;
        for (int q = 0; q < couleurs.length; q++) {
            int o = q * 23;
            for (int v = 0; v < 4; v++) {
                int s = o + v * 5;
                c.addVertex(pose, d[s], d[s + 1], d[s + 2]).setColor(couleurs[q]).setUv(d[s + 3], d[s + 4]).setOverlay(OverlayTexture.NO_OVERLAY)
                        .setLight(LUMIERE).setNormal(pose, d[o + 20], d[o + 21], d[o + 22]);
            }
        }
    }

    /** Une tentacule entre deux poses (k : 0 la première, 1 la seconde), point par point. */
    private static void melange(VertexConsumer c, PoseStack.Pose pose, float[] a, float[] b, float k, int[] couleurs) {
        for (int q = 0; q < couleurs.length; q++) {
            int o = q * 23;
            float nx = Mth.lerp(k, a[o + 20], b[o + 20]), ny = Mth.lerp(k, a[o + 21], b[o + 21]), nz = Mth.lerp(k, a[o + 22], b[o + 22]);
            for (int v = 0; v < 4; v++) {
                int s = o + v * 5;
                c.addVertex(pose, Mth.lerp(k, a[s], b[s]), Mth.lerp(k, a[s + 1], b[s + 1]), Mth.lerp(k, a[s + 2], b[s + 2])).setColor(couleurs[q])
                        .setUv(a[s + 3], a[s + 4]).setOverlay(OverlayTexture.NO_OVERLAY).setLight(LUMIERE).setNormal(pose, nx, ny, nz);
            }
        }
    }

    // ================================================================ les effets : la boule, la Bijuu Dama, l'éclat

    private static final float[] ROUGE_SOMBRE = {0.43F, 0.03F, 0.04F}, ROUGE = {0.89F, 0.12F, 0.09F}, ROUGE_VIF = {1F, 0.36F, 0.2F},
            BRAISE = {1F, 0.84F, 0.6F}, NOIR = {0.04F, 0.02F, 0.06F}, VIOLET = {0.3F, 0.17F, 0.45F}, VIOLET_CLAIR = {0.7F, 0.5F, 1F};

    private interface Teinte {
        float[] couleur(double theta, double phi);
    }

    private static void effets(MultiBufferSource buffers, Matrix4f m, Vec3 cam, Bete b, Player j, float partiel) {
        double s = age(b);
        Vec3 pied = j.getPosition(partiel);
        float lacet = Mth.rotLerp(partiel, b.lacetO, b.lacet);
        VertexConsumer voile = buffers.getBuffer(Types.TRANSLUCIDE), lueur = buffers.getBuffer(Types.LUMIERE);
        if (b.fin < 0 && s < ECLAT) {
            transformation(voile, lueur, m, cam, pied, s);
        }
        if (b.fin < 0 && s >= ECLAT && s < ECLAT + 1.2) {
            eclat(voile, lueur, m, cam, pied, s - ECLAT);
        }
        if (b.fin >= 0) {
            // la boule revient d'un coup, puis se résorbe en lui
            double u = secondes(b.fin) / RETOUR;
            if (u < 1) {
                double r = RAYON_MAX * (u < 0.12 ? lisse(u / 0.12) : 1 - lisse((u - 0.12) / 0.88)) + 0.5;
                Vec3 c = pied.add(0, Math.max(1, r * 0.82), 0).subtract(cam);
                boule(voile, m, c, r, 18, 28, (th, ph) -> bande(th, ph, s), 0.9F);
                boule(lueur, m, c, r * 1.06, 12, 20, (th, ph) -> ROUGE, 0.3F);
            }
        }
        // la Bijuu Dama qui se charge devant la gueule, puis qui file, puis le dôme
        if (b.charge >= 0) {
            double k = Math.min(1, secondes(b.charge) / (Hachibi4.CHARGE_DAMA / 20.0));
            Vec3 c = monde(pied, lacet, Hachibi4.BOUCHE.add(0, 0, 1 + Hachibi4.RAYON_DAMA * Math.sqrt(k))).subtract(cam);
            dama(voile, lueur, m, c, Hachibi4.RAYON_DAMA * Math.sqrt(k), secondes(b.charge));
            double t = secondes(b.charge);
            for (int i = 0; i < 16; i++) {
                double phase = (t * 1.3 + i / 16.0) % 1, a = i * 2.39996 + t * 3, d = (Hachibi4.RAYON_DAMA + 10) * (1 - phase);
                float[] col = i % 3 == 0 ? ROUGE_VIF : i % 3 == 1 ? new float[] {0.25F, 0.5F, 1F} : VIOLET_CLAIR;
                boule(lueur, m, c.add(Math.cos(a) * d, Math.sin(a * 1.3) * d * 0.6, Math.sin(a) * d), 0.6, 4, 6, (th, ph) -> col, 0.8F);
            }
        }
        if (b.tir >= 0 && b.depart != null) {
            double t = secondes(b.tir);
            if (t < 3.5) {
                for (int k = 4; k >= 0; k--) {
                    double tk = Math.max(0, t - k * 0.04);
                    Vec3 c = b.depart.add(b.direction.scale(tk * Hachibi4.VITESSE_DAMA * 20)).subtract(cam);
                    if (k == 0) {
                        dama(voile, lueur, m, c, Hachibi4.RAYON_DAMA, t);
                    } else {
                        boule(lueur, m, c, Hachibi4.RAYON_DAMA * (1 - k * 0.15), 10, 16, (th, ph) -> VIOLET, 0.25F / k);
                    }
                }
            }
        }
        if (b.boum >= 0 && b.boumPos != null) {
            double u = secondes(b.boum) / 0.9;
            if (u < 1) {
                Vec3 c = b.boumPos.subtract(cam);
                double r = 4 + (Hachibi4.RAYON_EXPLOSION + 4) * Math.sqrt(u);
                boule(voile, m, c, r, 16, 26, (th, ph) -> Math.cos(th) > 0.2 ? VIOLET : NOIR, (float) (0.85 * (1 - u)));
                boule(lueur, m, c, r * 1.08, 12, 20, (th, ph) -> VIOLET_CLAIR, (float) (0.55 * (1 - u)));
                anneau(lueur, m, c.add(0, -r * 0.3, 0), r * 1.4, 2.5, VIOLET_CLAIR, (float) (0.6 * (1 - u)));
            }
        }
    }

    /** Les bandes de chakra qui tournent sur la boule rouge. */
    private static float[] bande(double th, double ph, double t) {
        double k = 0.5 + 0.5 * Math.sin(ph * 5 + Math.cos(th) * 7 + t * 9) * Math.sin(ph * 3 - Math.cos(th) * 4 - t * 5);
        return mix(ROUGE_SOMBRE, ROUGE_VIF, (float) (0.25 + 0.6 * k));
    }

    /** La transformation : le chakra qui converge, puis la boule qui naît, gonfle et se fissure. */
    private static void transformation(VertexConsumer voile, VertexConsumer lueur, Matrix4f m, Vec3 cam, Vec3 pied, double s) {
        // les filets de chakra rouge qui montent du sol et convergent en spirale sur lui
        for (int q = 0; q < 90; q++) {
            double debut = hache(q, 1) * 0.7, u = entre(s, debut, debut + 0.9);
            if (u <= 0 || u >= 1) {
                continue;
            }
            double r0 = 1.5 + 4 * hache(q, 3), h = (0.2 + 4 * hache(q, 4)) * (1 - u) + 1.1 * u + Math.sin(u * 7 + q) * 0.6 * (1 - u);
            for (int k = 0; k < 4; k++) {
                double v = Math.max(0, u - k * 0.03), a = hache(q, 2) * Mth.TWO_PI + v * 5, r = r0 * (1 - lisse(v));
                float[] col = mix(hache(q, 5) < 0.3 ? ROUGE_VIF : ROUGE, ROUGE_SOMBRE, k * 0.2F);
                cube(lueur, m, pied.add(Math.cos(a) * r, h + k * 0.12, Math.sin(a) * r).subtract(cam), 0.16 - k * 0.025, col, 0.9F);
            }
        }
        double r = rayon(s);
        if (r <= 0) {
            return;
        }
        Vec3 c = pied.add(0, Math.max(1, r * 0.82), 0).subtract(cam);
        // transparente quand elle naît (on le voit dedans), puis pleine
        float plein = (float) Mth.clamp((r - 3) / 5, 0, 1);
        boule(voile, m, c, r, 18 + (int) (r / 3), 28 + (int) (r / 2), (th, ph) -> bande(th, ph, s), 0.45F + 0.5F * plein);
        boule(lueur, m, c, r * 1.07, 14, 22, (th, ph) -> ROUGE, 0.3F);
        double fissure = entre(s, FISSURES, ECLAT);
        if (fissure > 0) {
            // de grands cercles de lumière qui s'élargissent jusqu'à l'éclat
            double[][] axes = {{0.3, 0.9, 0.2}, {0.8, -0.2, 0.55}, {-0.5, 0.3, 0.8}, {0.1, -0.6, -0.8}, {-0.9, 0.1, -0.3}};
            for (int i = 0; i < axes.length; i++) {
                if (i >= 2 + fissure * 4) {
                    break;
                }
                Vec3 n = new Vec3(axes[i][0], axes[i][1], axes[i][2]).normalize();
                fissure(lueur, m, c, r * 1.01, n, 0.012 + 0.06 * fissure, mix(BRAISE, new float[] {1, 1, 1}, (float) fissure), 0.95F);
            }
        }
    }

    /** L'éclat : les morceaux de la coquille qui volent, l'onde au sol. */
    private static void eclat(VertexConsumer voile, VertexConsumer lueur, Matrix4f m, Vec3 cam, Vec3 pied, double u) {
        Vec3 c = pied.add(0, RAYON_MAX * 0.82, 0);
        for (int q = 0; q < 160; q++) {
            double th = Math.acos(1 - 2 * hache(q, 7)), ph = hache(q, 8) * Mth.TWO_PI;
            Vec3 d = new Vec3(Math.sin(th) * Math.cos(ph), Math.cos(th), Math.sin(th) * Math.sin(ph));
            double v = 22 + 30 * hache(q, 9), vie = 1 - u / 0.9;
            if (vie <= 0) {
                break;
            }
            Vec3 p = c.add(d.scale(RAYON_MAX + v * u)).add(0, -9 * u * u, 0);
            cube(voile, m, p.subtract(cam), 1.1 * vie * G, mix(ROUGE_VIF, ROUGE_SOMBRE, (float) (1 - vie)), 0.9F);
        }
        if (u < 0.7) {
            anneau(lueur, m, pied.add(0, 0.4, 0).subtract(cam), RAYON_MAX + u * 70, 3, new float[] {1F, 0.47F, 0.35F}, (float) (0.6 * (1 - u / 0.7)));
        }
    }

    /** La Bijuu Dama : une boule noire aux reflets violets, qui tourne, et sa lueur. */
    private static void dama(VertexConsumer voile, VertexConsumer lueur, Matrix4f m, Vec3 c, double r, double t) {
        boule(voile, m, c, r, 16, 26, (th, ph) -> Math.sin(ph * 4 + t * 6 + Math.cos(th) * 3) > 0.55 ? VIOLET : NOIR, 0.97F);
        boule(lueur, m, c, r * 1.12, 12, 20, (th, ph) -> VIOLET, 0.35F);
    }

    // ================================================================ les formes de base

    private static float[] mix(float[] a, float[] b, float k) {
        return new float[] {a[0] + (b[0] - a[0]) * k, a[1] + (b[1] - a[1]) * k, a[2] + (b[2] - a[2]) * k};
    }

    private static double hache(int i, int j) {
        int h = i * 374761393 + j * 668265263 + 900 * 2147483647;
        h = (h ^ (h >>> 13)) * 1274126177;
        return ((h ^ (h >>> 16)) >>> 0 & 0xFFFFFFFFL) / 4294967296.0;
    }

    /** Une sphère en quadrilatères (latitude, longitude), colorée case par case. */
    private static void boule(VertexConsumer c, Matrix4f m, Vec3 o, double r, int lat, int lon, Teinte teinte, float alpha) {
        for (int i = 0; i < lat; i++) {
            double t0 = Math.PI * i / lat, t1 = Math.PI * (i + 1) / lat;
            for (int jj = 0; jj < lon; jj++) {
                double p0 = Mth.TWO_PI * jj / lon, p1 = Mth.TWO_PI * (jj + 1) / lon;
                float[] col = teinte.couleur((t0 + t1) / 2, (p0 + p1) / 2);
                sommet(c, m, o, r, t0, p0, col, alpha);
                sommet(c, m, o, r, t1, p0, col, alpha);
                sommet(c, m, o, r, t1, p1, col, alpha);
                sommet(c, m, o, r, t0, p1, col, alpha);
            }
        }
    }

    private static void sommet(VertexConsumer c, Matrix4f m, Vec3 o, double r, double th, double ph, float[] col, float a) {
        double s = Math.sin(th);
        c.addVertex(m, (float) (o.x + r * s * Math.cos(ph)), (float) (o.y + r * Math.cos(th)), (float) (o.z + r * s * Math.sin(ph))).setColor(col[0], col[1], col[2], a);
    }

    /** Un grand cercle de lumière sur la boule (sa normale n), large de w (en radians). */
    private static void fissure(VertexConsumer c, Matrix4f m, Vec3 o, double r, Vec3 n, double w, float[] col, float a) {
        Vec3 u = n.cross(Math.abs(n.y) > 0.9 ? new Vec3(1, 0, 0) : new Vec3(0, 1, 0)).normalize(), v = n.cross(u);
        int pas = 64;
        for (int k = 0; k < pas; k++) {
            double a0 = Mth.TWO_PI * k / pas, a1 = Mth.TWO_PI * (k + 1) / pas, ondule0 = 0.04 * Math.sin(a0 * 9), ondule1 = 0.04 * Math.sin(a1 * 9);
            Vec3[] q = {cercle(u, v, n, a0, ondule0 - w), cercle(u, v, n, a1, ondule1 - w), cercle(u, v, n, a1, ondule1 + w), cercle(u, v, n, a0, ondule0 + w)};
            for (Vec3 p : q) {
                c.addVertex(m, (float) (o.x + p.x * r), (float) (o.y + p.y * r), (float) (o.z + p.z * r)).setColor(col[0], col[1], col[2], a);
            }
        }
    }

    private static Vec3 cercle(Vec3 u, Vec3 v, Vec3 n, double a, double hors) {
        return u.scale(Math.cos(a)).add(v.scale(Math.sin(a))).add(n.scale(hors)).normalize();
    }

    /** Un anneau à plat (une onde au sol), de rayon r et de largeur l. */
    private static void anneau(VertexConsumer c, Matrix4f m, Vec3 o, double r, double l, float[] col, float a) {
        int pas = 64;
        for (int k = 0; k < pas; k++) {
            double a0 = Mth.TWO_PI * k / pas, a1 = Mth.TWO_PI * (k + 1) / pas;
            double[][] q = {{a0, r}, {a1, r}, {a1, r + l}, {a0, r + l}};
            for (double[] p : q) {
                c.addVertex(m, (float) (o.x + Math.cos(p[0]) * p[1]), (float) o.y, (float) (o.z + Math.sin(p[0]) * p[1])).setColor(col[0], col[1], col[2], a);
            }
        }
    }

    /** Un petit cube plein (un filet de chakra, un morceau de coquille). */
    private static void cube(VertexConsumer c, Matrix4f m, Vec3 o, double h, float[] col, float a) {
        float x0 = (float) (o.x - h), x1 = (float) (o.x + h), y0 = (float) (o.y - h), y1 = (float) (o.y + h), z0 = (float) (o.z - h), z1 = (float) (o.z + h);
        float[][] f = {{x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1}, {x1, y0, z0, x0, y0, z0, x0, y1, z0, x1, y1, z0},
                {x1, y0, z1, x1, y0, z0, x1, y1, z0, x1, y1, z1}, {x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0},
                {x0, y1, z1, x1, y1, z1, x1, y1, z0, x0, y1, z0}, {x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1}};
        for (float[] q : f) {
            for (int k = 0; k < 4; k++) {
                c.addVertex(m, q[k * 3], q[k * 3 + 1], q[k * 3 + 2]).setColor(col[0], col[1], col[2], a);
            }
        }
    }

    private static final class Types extends RenderType {
        static final RenderType TRANSLUCIDE = create("cabane_hachibi4_translucide", DefaultVertexFormat.POSITION_COLOR, VertexFormat.Mode.QUADS, 262144,
                false, true, CompositeState.builder().setShaderState(POSITION_COLOR_SHADER).setWriteMaskState(COLOR_DEPTH_WRITE)
                        .setTransparencyState(TRANSLUCENT_TRANSPARENCY).setCullState(NO_CULL).createCompositeState(false));
        static final RenderType LUMIERE = create("cabane_hachibi4_lumiere", DefaultVertexFormat.POSITION_COLOR, VertexFormat.Mode.QUADS, 262144,
                false, true, CompositeState.builder().setShaderState(RENDERTYPE_LIGHTNING_SHADER).setWriteMaskState(COLOR_WRITE)
                        .setTransparencyState(LIGHTNING_TRANSPARENCY).setCullState(NO_CULL).createCompositeState(false));

        private Types() {
            super("cabane_hachibi4_rien", DefaultVertexFormat.POSITION, VertexFormat.Mode.QUADS, 256, false, false, () -> { }, () -> { });
        }
    }

    // ================================================================ l'écran : le flash, le voile rouge, les touches

    private static void ecran(GuiGraphics g) {
        Minecraft mc = Minecraft.getInstance();
        int w = g.guiWidth(), hh = g.guiHeight();
        if (flash >= 0) {
            double u = secondes(flash) / 0.35;
            if (u < 1) {
                g.fill(0, 0, w, hh, ((int) (230 * (1 - u)) << 24) | 0xFFECE6);
            }
        }
        Bete b = moi();
        if (b == null || mc.options.hideGui) {
            return;
        }
        double s = age(b);
        int n = 12;
        for (int i = 0; i < n; i++) {
            float k = 1 - i / (float) n;
            int c = (Math.round(0.3F * k * k * 255) << 24) | 0x9A0A04;
            int lx = w / 5 * i / n, lx1 = w / 5 * (i + 1) / n, ly = hh / 5 * i / n, ly1 = hh / 5 * (i + 1) / n;
            g.fill(lx, 0, lx1, hh, c);
            g.fill(w - lx1, 0, w - lx, hh, c);
            g.fill(0, ly, w, ly1, c);
            g.fill(0, hh - ly1, w, hh - ly, c);
        }
        String titre;
        String[][] lignes;
        boolean[] gris = new boolean[4];
        if (b.fin >= 0) {
            return;
        }
        if (s < ECLAT) {
            titre = "HACHIBI 4  ·  LE CHAKRA SE RASSEMBLE…";
            lignes = new String[0][];
        } else {
            double reste = Math.max(0, ECLAT + DUREE - s);
            titre = String.format("HACHIBI 4  ·  LE DÉMON (%d s)", (int) Math.ceil(reste));
            double dama = recharge(b.dernierTir, Hachibi4.RECHARGE_DAMA);
            double queues = recharge(b.queues, Hachibi4.RECHARGE_QUEUES), encre = recharge(b.encre, Hachibi4.RECHARGE_ENCRE);
            gris[1] = b.charge >= 0 || dama > 0;
            gris[2] = queues > 0;
            gris[3] = encre > 0;
            lignes = new String[][] {{Clavier.gauche(), "Poings (droite, gauche…)"},
                    {Clavier.droite(), b.charge >= 0 ? "Bijuu Dama (charge…)" : dama > 0 ? String.format("Bijuu Dama (%.0f s)", Math.ceil(dama)) : "Bijuu Dama"},
                    {Clavier.accroupi(), queues > 0 ? String.format("Coup des 8 queues (%.0f s)", Math.ceil(queues)) : "Coup des 8 queues"},
                    {Clavier.accroupi() + " + " + Clavier.droite(), encre > 0 ? String.format("Encre (%.0f s)", Math.ceil(encre)) : "Encre"}};
        }
        Font font = mc.font;
        int largeurTouche = 0, largeurAction = font.width(titre) - 10;
        for (String[] l : lignes) {
            largeurTouche = Math.max(largeurTouche, font.width(l[0]));
            largeurAction = Math.max(largeurAction, font.width(l[1]));
        }
        int pw = largeurTouche + largeurAction + 26, ph = 22 + lignes.length * 13, x = 6, y = hh / 2 - ph / 2, teinte = 0xFFFF5A3C;
        g.fill(x, y, x + pw, y + ph, 0xA0000000);
        g.fill(x, y, x + 2, y + ph, teinte);
        g.drawString(font, titre, x + 8, y + 6, teinte, true);
        for (int i = 0; i < lignes.length; i++) {
            int ly = y + 21 + i * 13;
            g.fill(x + 7, ly - 2, x + 11 + font.width(lignes[i][0]), ly + 9, 0x40FFFFFF);
            g.drawString(font, lignes[i][0], x + 9, ly, 0xFFFFFFFF, false);
            g.drawString(font, lignes[i][1], x + 18 + largeurTouche, ly, gris[i] ? 0xFF7A7A7A : 0xFFEDEDED, true);
        }
    }

    /** Ce qui reste de la recharge (en secondes) d'une attaque faite à ce moment-là. */
    private static double recharge(long quand, int ticks) {
        return quand < 0 ? 0 : ticks / 20.0 - secondes(quand);
    }

    // ================================================================ chaque image du jeu

    private static void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            BETES.clear();
            return;
        }
        while (ULTI.consumeClick()) {
            if (mc.screen == null) {
                PacketDistributor.sendToServer(new Hachibi4.Touche(Hachibi4.ULTI));
            }
        }
        Bete moi = moi();
        // s'accroupir (un appui court, sans clic droit entre-temps) : le coup des huit queues
        boolean accroupi = mc.options.keyShift.isDown();
        if (moi != null && moi.fin < 0 && age(moi) >= ECLAT) {
            if (accroupi && !accroupiAvant) {
                accroupiDebut = System.nanoTime();
                encreFaite = false;
            }
            if (!accroupi && accroupiAvant && !encreFaite && mc.screen == null && secondes(accroupiDebut) < 0.7) {
                PacketDistributor.sendToServer(new Hachibi4.Touche(Hachibi4.QUEUES_T));
            }
        }
        accroupiAvant = accroupi;
        // dans la bête, la vue est à la troisième personne (la caméra recule) ; on rend la sienne à la fin
        if (moi != null && (moi.fin < 0 || secondes(moi.fin) < RETOUR)) {
            if (vueAvant == null) {
                vueAvant = mc.options.getCameraType();
            }
            if (mc.options.getCameraType() != CameraType.THIRD_PERSON_BACK) {
                mc.options.setCameraType(CameraType.THIRD_PERSON_BACK);
            }
        } else if (vueAvant != null) {
            mc.options.setCameraType(vueAvant);
            vueAvant = null;
        }
        BETES.entrySet().removeIf(e -> e.getValue().fin >= 0 && secondes(e.getValue().fin) > RETOUR + 0.2);
        for (Map.Entry<UUID, Bete> e : BETES.entrySet()) {
            Bete b = e.getValue();
            Player j = mc.level.getPlayerByUUID(e.getKey());
            if (j == null) {
                continue;
            }
            // la bête suit son regard, lourdement
            if (!b.lacetPret) {
                b.lacet = b.lacetO = j.getYRot();
                b.lacetPret = true;
            }
            b.lacetO = b.lacet;
            b.lacet = Mth.rotLerp(0.2F, b.lacet, j.getYRot());
            double s = age(b), loin = j.distanceTo(mc.player);
            Vec3 pied = j.position();
            marcher(mc, b, j, pied, loin, s);
            // l'éclat : un flash et une secousse pour ceux qui sont autour, et la fumée de chakra qui monte
            if (b.agePrecedent < ECLAT && s >= ECLAT && b.fin < 0) {
                if (loin < 120) {
                    flash = System.nanoTime();
                    secouer(1.6F * (float) Math.max(0.3, 1 - loin / 120));
                }
            }
            if (s >= ECLAT && s < ECLAT + 1.8 && b.fin < 0) {
                DustParticleOptions rouge = new DustParticleOptions(new Vector3f(0.95F, 0.15F, 0.08F), 4F);
                for (int i = 0; i < 14; i++) {
                    double a = HASARD.nextDouble() * Mth.TWO_PI, r = 4 + HASARD.nextDouble() * 22 * G;
                    mc.level.addParticle(rouge, pied.x + Math.cos(a) * r, pied.y + HASARD.nextDouble() * 34 * G, pied.z + Math.sin(a) * r, 0, 0.25, 0);
                }
            }
            // la transformation : quelques étincelles rouges autour de lui
            if (s < ECLAT && b.fin < 0 && HASARD.nextInt(2) == 0) {
                double r = Math.max(1.5, rayon(s));
                double a = HASARD.nextDouble() * Mth.TWO_PI;
                mc.level.addParticle(new DustParticleOptions(new Vector3f(1F, 0.2F, 0.1F), 2.5F), pied.x + Math.cos(a) * r * 1.1, pied.y + HASARD.nextDouble() * r * 1.6,
                        pied.z + Math.sin(a) * r * 1.1, 0, 0.1, 0);
            }
            b.agePrecedent = s;
            // les coups qui touchent le sol font trembler ceux qui sont près
            for (int k = 0; k < 2; k++) {
                double tp = b.poings[k] < 0 ? 9 : secondes(b.poings[k]);
                if (b.poingPrecedent[k] < 0.3 && tp >= 0.3 && loin < 60) {
                    secouer(0.6F * (float) Math.max(0.2, 1 - loin / 60));
                }
                b.poingPrecedent[k] = tp;
            }
            double tq = b.queues < 0 ? 9 : secondes(b.queues);
            if (b.queuesPrecedent < 0.95 && tq >= 0.95 && loin < 100) {
                secouer(1.4F * (float) Math.max(0.3, 1 - loin / 100));
            }
            b.queuesPrecedent = tq;
            double tb = b.boum < 0 ? 9 : secondes(b.boum);
            if (b.boumPrecedent > tb && b.boumPos != null && b.boumPos.distanceTo(mc.player.position()) < 80) {
                secouer(1.2F);
            }
            b.boumPrecedent = tb;
        }
    }

    /**
     * La marche : le pas avance avec la distance parcourue ; chaque fois qu'un poing se repose, un bruit sourd, de la
     * terre qui saute, et le sol qui tremble un peu pour ceux qui sont tout près.
     */
    private static void marcher(Minecraft mc, Bete b, Player j, Vec3 pied, double loin, double s) {
        double d = b.avant == null ? 0 : Math.hypot(pied.x - b.avant.x, pied.z - b.avant.z);
        b.avant = pied;
        if (d > 3) {
            // une téléportation, pas un pas
            d = 0;
        }
        b.marcheO = b.marche;
        b.marche = Mth.lerp(0.15F, b.marche, (float) Mth.clamp(d / 0.18, 0, 1));
        b.pasO = b.pas;
        // même arrêté, il finit son pas (le poing en l'air se repose)
        double avance = d / FOULEE * Math.PI;
        if (avance < 0.02 && Math.sin(b.pas) * Math.sin(b.pas + 0.05) > 0 && Math.abs(Math.sin(b.pas)) > 0.05) {
            avance = 0.06;
        }
        b.pas += avance;
        if (s < ECLAT || b.fin >= 0 || b.marche < 0.25) {
            return;
        }
        for (int k = 0; k < 2; k++) {
            double avantPas = Math.sin(b.pasO + k * Math.PI), maintenant = Math.sin(b.pas + k * Math.PI);
            if (avantPas > 0 && maintenant <= 0) {
                Vec3 poing = monde(pied, b.lacet, new Vec3((k == 0 ? -13.6 : 13.6) * G, 0, 15 * G));
                net.minecraft.core.BlockPos sous = net.minecraft.core.BlockPos.containing(poing.x, pied.y - 0.5, poing.z);
                net.minecraft.world.level.block.state.BlockState sol = mc.level.getBlockState(sous);
                if (!sol.isAir()) {
                    net.minecraft.core.particles.BlockParticleOption terre = new net.minecraft.core.particles.BlockParticleOption(net.minecraft.core.particles.ParticleTypes.BLOCK, sol);
                    for (int i = 0; i < 40; i++) {
                        mc.level.addParticle(terre, poing.x + (HASARD.nextDouble() - 0.5) * 6, pied.y + 0.2, poing.z + (HASARD.nextDouble() - 0.5) * 6,
                                (HASARD.nextDouble() - 0.5) * 0.4, 0.3 + HASARD.nextDouble() * 0.4, (HASARD.nextDouble() - 0.5) * 0.4);
                    }
                }
                for (int i = 0; i < 8; i++) {
                    mc.level.addParticle(net.minecraft.core.particles.ParticleTypes.CLOUD, poing.x + (HASARD.nextDouble() - 0.5) * 5, pied.y + 0.3,
                            poing.z + (HASARD.nextDouble() - 0.5) * 5, 0, 0.05, 0);
                }
                mc.level.playLocalSound(poing.x, pied.y, poing.z, net.minecraft.sounds.SoundEvents.RAVAGER_STEP, net.minecraft.sounds.SoundSource.PLAYERS,
                        3F, 0.45F, false);
                mc.level.playLocalSound(poing.x, pied.y, poing.z, net.minecraft.sounds.SoundEvents.GENERIC_EXPLODE.value(), net.minecraft.sounds.SoundSource.PLAYERS,
                        0.5F, 0.4F, false);
                if (loin < 40) {
                    secouer(0.35F * (float) (1 - loin / 40));
                }
            }
        }
    }

    private static void secouer(float force) {
        if (secousse < 0 || secondes(secousse) > 0.3 || force > forceSecousse) {
            secousse = System.nanoTime();
            forceSecousse = force;
        }
    }

    // ================================================================ branchements

    @EventBusSubscriber(modid = Cabane.MODID, value = Dist.CLIENT)
    public static final class Touches {
        @SubscribeEvent
        public static void enregistrer(RegisterKeyMappingsEvent event) {
            event.register(ULTI);
        }

        @SubscribeEvent
        public static void couches(RegisterGuiLayersEvent event) {
            event.registerAboveAll(Cabane.id("hachibi4"), (g, delta) -> ecran(g));
        }

        private Touches() {
        }
    }

    @EventBusSubscriber(modid = Cabane.MODID, value = Dist.CLIENT)
    public static final class Evenements {
        @SubscribeEvent
        public static void tick(ClientTickEvent.Post event) {
            ClientHachibi4.tick();
        }

        /** Dans son ulti, les clics sont à la bête (avant ceux de Hachibi 2). */
        @SubscribeEvent(priority = EventPriority.HIGH)
        public static void clic(InputEvent.InteractionKeyMappingTriggered event) {
            Minecraft mc = Minecraft.getInstance();
            Bete b = moi();
            if (!(event.isAttack() || event.isUseItem()) || b == null || b.fin >= 0) {
                return;
            }
            event.setCanceled(true);
            event.setSwingHand(false);
            if (age(b) < ECLAT || mc.screen != null) {
                return;
            }
            long recharge = event.isAttack() ? 300_000_000L : 500_000_000L;
            if (System.nanoTime() - dernierClic < recharge) {
                return;
            }
            dernierClic = System.nanoTime();
            if (event.isAttack()) {
                PacketDistributor.sendToServer(new Hachibi4.Touche(Hachibi4.POING_T));
            } else if (mc.options.keyShift.isDown()) {
                encreFaite = true;
                PacketDistributor.sendToServer(new Hachibi4.Touche(Hachibi4.ENCRE_T));
            } else {
                PacketDistributor.sendToServer(new Hachibi4.Touche(Hachibi4.DAMA));
            }
        }

        @SubscribeEvent
        public static void mains(RenderHandEvent event) {
            if (moi() != null) {
                event.setCanceled(true);
            }
        }

        @SubscribeEvent
        public static void tremblement(ViewportEvent.ComputeCameraAngles event) {
            if (secousse < 0) {
                return;
            }
            double apres = secondes(secousse);
            if (apres > 0.9) {
                secousse = -1;
                return;
            }
            float force = forceSecousse * (float) (1 - apres / 0.9);
            event.setRoll(event.getRoll() + (HASARD.nextFloat() - 0.5F) * 6 * force);
            event.setPitch(event.getPitch() + (HASARD.nextFloat() - 0.5F) * 3 * force);
            event.setYaw(event.getYaw() + (HASARD.nextFloat() - 0.5F) * 3 * force);
        }

        @SubscribeEvent
        public static void dessiner(RenderLevelStageEvent event) {
            if (BETES.isEmpty() || event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES || Minecraft.getInstance().level == null) {
                return;
            }
            ClientHachibi4.dessiner(event.getPoseStack(), event.getCamera().getPosition(), event.getPartialTick().getGameTimeDeltaPartialTick(false));
        }

        private Evenements() {
        }
    }

    private ClientHachibi4() {
    }
}
