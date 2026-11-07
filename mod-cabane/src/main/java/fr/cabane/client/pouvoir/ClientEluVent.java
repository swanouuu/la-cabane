package fr.cabane.client.pouvoir;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import fr.cabane.Cabane;
import fr.cabane.pouvoir.EluVent;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.MovementInputUpdateEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.client.event.RenderGuiLayerEvent;
import net.neoforged.neoforge.client.event.RenderHandEvent;
import net.neoforged.neoforge.client.event.RenderNameTagEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;
import net.neoforged.neoforge.common.util.TriState;
import org.joml.Matrix4f;

/**
 * L'élu du vent (voir {@link EluVent}), comme quand on ouvre un coffre dans Zelda. Un grand coffre au trésor arrive dans
 * un coup de vent devant lui ; il pose les mains sur le couvercle et l'ouvre lentement, la lumière fuit par la fente
 * pendant que la musique monte et que la caméra tourne autour du coffre. Un silence, puis la fanfare : la fée du vent
 * jaillit en spirale, il la lève au-dessus de sa tête dans un halo (« Tu as obtenu la Fée du vent ! »), puis elle
 * descend tourner autour de lui comme pour tous ceux qui ont le pouvoir ({@link ClientVent}) ; le coffre s'en va en
 * tourbillon.
 * <p>
 * Tous ceux qui sont autour voient le coffre, la lumière, la fée et ses bras ; la caméra, les bandes noires et le texte
 * ne sont que chez lui. La musique (voix/cinematique/elu_vent.ogg, dans le pack privé) se joue à son oreille, et chez
 * les autres là où il est (on l'entend à une trentaine de blocs) ; sans le pack, une fanfare de Minecraft à la place.
 * Le temps suit l'horloge réelle, calé sur la musique : la montée jusqu'à 7,6 s, un silence, la fanfare de 8,3 à 10,6 s.
 */
public final class ClientEluVent {
    private static final ResourceLocation TEXTURE = Cabane.id("textures/entity/coffre_vent.png");
    private static final ResourceLocation MUSIQUE_ID = Cabane.id("cine.elu_vent");
    private static final ResourceLocation COUCHE = Cabane.id("elu_vent");
    private static final RandomSource HASARD = RandomSource.create();
    private static final double PX = 1 / 16.0;
    private static final Vec3 HAUT = new Vec3(0, 1, 0);
    /** La taille du coffre (1 : celui de Minecraft) : un grand coffre au trésor. */
    private static final float TAILLE = 1.4F;
    private static final float[] OR = {1F, 236 / 255F, 150 / 255F};
    private static final float[] RAYON = {215 / 255F, 1F, 170 / 255F};

    // le scénario, en secondes
    static final double MUSIQUE = 0.5;
    static final double APPARU = 0.55;
    static final double MAINS = 1.3;
    static final double OUVRE = 2.0;
    static final double OUVERT = 7.6;
    static final double SORT = 8.3;
    static final double MONTEE = 0.5;
    static final double TEXTE = 8.6, TEXTE_FIN = 11.6;
    static final double DESCEND = 10.7;
    static final double ORBITE = EluVent.DON;
    static final double DISPARAIT = 11.2, DISPARU = 12.1;
    static final double NOIR = 12.1, RETOUR = 12.45, FIN = 12.95;
    /** L'orbite de la caméra autour du coffre commence là (en degrés, 0 : derrière le coffre, 90 : à sa droite à lui). */
    private static final double ORBITE_DEBUT = 200;
    /** Il se retourne vers la caméra quand la fée sort (comme Link qui montre ce qu'il a trouvé). */
    static final double DEMI_TOUR = SORT - 0.1, DEMI_TOUR_FIN = SORT + 0.3;

    /** Une scène en cours : où il était, où est le coffre, vers où il regardait. */
    static final class Scene {
        final UUID joueur;
        final Vec3 pieds;
        final Vec3 coffre;
        final float lacet;
        final Vec3 devant;
        final Vec3 droite;
        final ClientLevel niveau;
        final long debut = System.nanoTime();
        double tPrecedent = -1;
        float tangageDepart;
        SoundInstance musique;

        Scene(EluVent.Debut p, ClientLevel niveau) {
            joueur = p.joueur();
            pieds = new Vec3(p.pieds());
            coffre = new Vec3(p.coffre());
            lacet = p.lacet();
            double r = Math.toRadians(lacet);
            devant = new Vec3(-Math.sin(r), 0, Math.cos(r));
            droite = new Vec3(-Math.cos(r), 0, -Math.sin(r));
            this.niveau = niveau;
        }

        double t() {
            return (System.nanoTime() - debut) / 1e9;
        }

        boolean passe(double moment, double t) {
            return tPrecedent < moment && t >= moment;
        }

        /** Une direction à plat : 0° devant lui, 90° à sa droite. */
        Vec3 dir(double degres) {
            double a = Math.toRadians(degres);
            return devant.scale(Math.cos(a)).add(droite.scale(Math.sin(a)));
        }
    }

    private static final Map<UUID, Scene> SCENES = new HashMap<>();
    /** La scène du joueur de ce client (il a la caméra jusqu'au RETOUR), ou null. */
    private static Scene mienne;
    private static boolean camera;
    private static Entity objectif;
    private static CameraType vueAvant;
    private static boolean guiAvant;
    private static ModelPart fond;
    private static ModelPart couvercle;
    private static ModelPart serrure;

    static double lisse(double x) {
        x = Mth.clamp(x, 0, 1);
        return x * x * (3 - 2 * x);
    }

    public static void demarrer(EluVent.Debut p) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            return;
        }
        Scene s = new Scene(p, mc.level);
        Scene avant = SCENES.put(s.joueur, s);
        if (avant != null && avant.musique != null) {
            mc.getSoundManager().stop(avant.musique);
        }
        if (!s.joueur.equals(mc.player.getUUID())) {
            return;
        }
        if (!camera) {
            vueAvant = mc.options.getCameraType();
            guiAvant = mc.options.hideGui;
        }
        s.tangageDepart = mc.player.getXRot();
        mienne = s;
        camera = true;
        mc.setScreen(null);
        mc.options.hideGui = true;
        mc.options.setCameraType(CameraType.FIRST_PERSON);
        objectif = EntityType.MARKER.create(mc.level);
        placerCamera(s, 0);
        mc.setCameraEntity(objectif);
        mc.getMusicManager().stopPlaying();
    }

    /** Une scène se joue quelque part (ClientVent dessine alors même sans autre pouvoir Vent autour). */
    static boolean actif() {
        return !SCENES.isEmpty();
    }

    /** Sa fée est encore dans le coffre ou dans ses mains : ClientVent ne la dessine pas encore autour de lui. */
    static boolean cache(UUID joueur) {
        Scene s = SCENES.get(joueur);
        return s != null && s.t() < ORBITE;
    }

    private static void rendreCamera() {
        Minecraft mc = Minecraft.getInstance();
        if (!camera) {
            return;
        }
        camera = false;
        mc.options.hideGui = guiAvant;
        if (vueAvant != null) {
            mc.options.setCameraType(vueAvant);
        }
        if (mc.player != null) {
            mc.setCameraEntity(mc.player);
        }
        objectif = null;
    }

    // ================================================================ le scénario

    /** Le couvercle : 0 fermé, 1 grand ouvert. Il s'entrouvre (la lumière fuit), puis monte avec la musique. */
    static double ouverture(double t) {
        if (t < OUVRE) {
            return 0;
        }
        double fente = lisse((t - OUVRE) / 0.5) * 0.12;
        double monte = Math.pow(lisse((t - OUVRE - 0.6) / (OUVERT - OUVRE - 0.6)), 1.6) * 0.88;
        return Math.min(1, fente + monte);
    }

    /** La lumière qui sort du coffre : elle fuit, grandit, éclate quand la fée sort, puis retombe. */
    static double lumiere(double t) {
        if (t < OUVRE) {
            return 0;
        }
        if (t < SORT) {
            return Math.min(1, 0.2 * lisse((t - OUVRE) / 0.4) + 0.5 * ouverture(t) + 0.3 * lisse((t - OUVERT + 0.2) / 0.6));
        }
        return (0.35 + 0.65 * Math.exp(-(t - SORT) * 2.5)) * (1 - lisse((t - DESCEND) / 0.6));
    }

    /** La taille du coffre : il arrive d'un coup (en débordant un peu), et repart en tourbillon. */
    private static double apparition(double t) {
        double x = Mth.clamp(t / APPARU, 0, 1) - 1;
        double arrive = 1 + 2.70158 * x * x * x + 1.70158 * x * x;
        return arrive * (1 - lisse((t - DISPARAIT) / (DISPARU - DISPARAIT)));
    }

    /**
     * La fée : où elle est, vers où elle regarde, et (x) sa taille en pixels du joueur ; null tant qu'elle est dans le
     * coffre, et quand ClientVent la fait déjà tourner autour de lui.
     */
    static Vec3[] fee(Scene s, Player j, double t, float partiel) {
        if (t < SORT || j == null && t > DESCEND) {
            return null;
        }
        Vec3 pieds = j != null ? j.getPosition(partiel) : s.pieds;
        // il s'est retourné : « devant lui », c'est maintenant à l'opposé du coffre
        Vec3 leve = pieds.add(0, 2.45, 0).subtract(s.devant.scale(0.08));
        if (t < SORT + MONTEE) {
            // elle jaillit du coffre en spirale et passe par-dessus sa tête jusqu'au-dessus de ses mains
            double k = lisse((t - SORT) / MONTEE), a = k * Math.PI * 3, r = 0.55 * Math.sin(Math.PI * k);
            Vec3 p = s.coffre.add(0, 0.6, 0).lerp(leve, k).add(s.droite.scale(Math.cos(a) * r)).add(0, Math.sin(Math.PI * k) * 0.6, 0);
            return new Vec3[]{p, s.devant.scale(-1), new Vec3(1.6 * (0.5 + 0.5 * k), 0, 0)};
        }
        if (t < DESCEND) {
            // il la tient en l'air : elle flotte face à la caméra, regarde un peu autour d'elle
            double tourne = Math.sin(t * 1.4) * 0.35;
            Vec3 regard = s.devant.scale(-Math.cos(tourne)).add(s.droite.scale(Math.sin(tourne)));
            return new Vec3[]{leve.add(0, 0.05 * Math.sin(t * 4), 0), regard, new Vec3(1.6, 0, 0)};
        }
        if (j == null) {
            return null;
        }
        if (t >= ORBITE) {
            if (ClientVent.porteur(j.getUUID())) {
                return null;
            }
            Vec3[] o = ClientVent.orbite(j, partiel);
            return new Vec3[]{o[0], o[1], new Vec3(0.9, 0, 0)};
        }
        // elle descend en tournoyant jusqu'à sa place autour de lui
        Vec3 p = descente(s, j, leve, t, partiel), apres = descente(s, j, leve, t + 0.04, partiel);
        double k = lisse((t - DESCEND) / (ORBITE - DESCEND));
        return new Vec3[]{p, apres.subtract(p), new Vec3(Mth.lerp(k, 1.6, 0.9), 0, 0)};
    }

    private static Vec3 descente(Scene s, Player j, Vec3 leve, double t, float partiel) {
        double k = lisse((t - DESCEND) / (ORBITE - DESCEND));
        Vec3 o = ClientVent.orbite(j, partiel)[0];
        double a = k * Math.PI * 2 + Math.toRadians(s.lacet);
        double r = 0.7 * Math.sin(Math.PI * k);
        return leve.lerp(o, k).add(Math.cos(a) * r, 0, Math.sin(a) * r);
    }

    /** Sa tête (en degrés) : il regarde le coffre, puis lève les yeux vers la fée, puis devant lui. */
    private static float tangage(Scene s, double t) {
        double[][] cles = {{0, s.tangageDepart}, {0.6, 32}, {OUVRE, 35}, {OUVERT, 18}, {SORT, 6}, {SORT + 0.3, -38}, {DESCEND, -38}, {ORBITE, 0}};
        for (int i = 1; i < cles.length; i++) {
            if (t < cles[i][0]) {
                double[] a = cles[i - 1], b = cles[i];
                return (float) Mth.lerp(lisse((t - a[0]) / (b[0] - a[0])), a[1], b[1]);
            }
        }
        return 0;
    }

    /** Une position autour de lui : à cet angle (0° vers le coffre, 90° à sa droite), à cette distance et cette hauteur. */
    private static Vec3 autourDeLui(Scene s, double angle, double rayon, double hauteur) {
        return s.pieds.add(s.dir(angle).scale(rayon)).add(0, s.coffre.y - s.pieds.y + hauteur, 0);
    }

    /**
     * La caméra (chez lui seul) : elle fait presque tout le tour du coffre pendant que le couvercle s'ouvre, de son épaule
     * gauche jusque derrière lui (la lumière du coffre en contre-jour) ; il se retourne vers elle en levant la fée, elle
     * s'approche ; puis elle passe derrière lui par sa gauche et regarde devant lui. Le premier : où elle est ; le
     * second : ce qu'elle regarde.
     */
    private static Vec3[] plan(Scene s, double t) {
        Vec3 c = s.coffre;
        Vec3 viseCoffre = c.add(0, 0.75, 0), viseLui = s.pieds.add(0, 2.15, 0);
        Vec3 vise = viseCoffre.lerp(viseLui, lisse((t - 6.4) / 1.8));
        // la fin du tour : 3 blocs derrière lui, un peu à sa droite, en angle et distance autour du coffre
        Vec3 fin = autourDeLui(s, 172, 3.0, 1.5).subtract(c);
        double angleFin = Math.toDegrees(Math.atan2(fin.dot(s.droite), fin.dot(s.devant))) + 360;
        double rayonFin = Math.sqrt(fin.x * fin.x + fin.z * fin.z);
        Vec3 pos;
        if (t < OUVERT + 0.1) {
            double k = lisse(t / (OUVERT + 0.1));
            pos = c.add(s.dir(Mth.lerp(k, ORBITE_DEBUT, angleFin)).scale(Mth.lerp(k, 3.3, rayonFin))).add(0, Mth.lerp(k, 2.3, 1.5), 0);
        } else if (t < DESCEND) {
            // il s'est retourné : elle s'approche doucement de lui, de face
            double k = lisse((t - OUVERT - 0.1) / (DESCEND - OUVERT - 0.1));
            Vec3 de = c.add(s.dir(angleFin).scale(rayonFin)).add(0, 1.5, 0);
            pos = de.lerp(autourDeLui(s, 175, 3.4, 1.75), k);
        } else {
            // elle fait le tour de lui par le côté jusque dans son dos, en le gardant dans l'image pendant que la fée
            // descend ; à la fin on regarde là où il regarde maintenant
            double k = lisse((t - DESCEND) / (NOIR - DESCEND));
            pos = autourDeLui(s, Mth.lerp(k, 175, 348), Mth.lerp(k, 3.4, 2.8), Mth.lerp(k, 1.75, 2.35));
            vise = viseLui.lerp(s.pieds.subtract(s.devant.scale(6)).add(0, 1.3, 0), lisse((t - ORBITE) / (NOIR - ORBITE)));
        }
        return new Vec3[]{degager(vise, pos), vise};
    }

    /** Où il regarde (en degrés) : le coffre, puis il fait demi-tour vers la caméra quand la fée sort. */
    private static float lacet(Scene s, double t) {
        return s.lacet + (float) (180 * lisse((t - DEMI_TOUR) / (DEMI_TOUR_FIN - DEMI_TOUR)));
    }

    /** Pas à travers les murs : la caméra s'arrête devant le premier bloc entre ce qu'elle regarde et elle. */
    private static Vec3 degager(Vec3 vise, Vec3 pos) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            return pos;
        }
        HitResult h = mc.level.clip(new ClipContext(vise, pos, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mc.player));
        if (h.getType() != HitResult.Type.BLOCK) {
            return pos;
        }
        Vec3 d = pos.subtract(vise);
        double l = Math.max(0.4, h.getLocation().distanceTo(vise) - 0.25);
        return vise.add(d.scale(l / d.length()));
    }

    private static void placerCamera(Scene s, double t) {
        if (objectif == null) {
            return;
        }
        Vec3[] p = plan(s, t);
        Vec3 c = p[0], d = p[1].subtract(c);
        float lacet = (float) Math.toDegrees(Math.atan2(-d.x, d.z));
        float tangage = (float) Math.toDegrees(-Math.atan2(d.y, Math.sqrt(d.x * d.x + d.z * d.z)));
        objectif.setPos(c.x, c.y, c.z);
        objectif.xo = c.x;
        objectif.yo = c.y;
        objectif.zo = c.z;
        objectif.xOld = c.x;
        objectif.yOld = c.y;
        objectif.zOld = c.z;
        objectif.setYRot(lacet);
        objectif.setXRot(tangage);
        objectif.yRotO = lacet;
        objectif.xRotO = tangage;
    }

    /**
     * Il reste tourné vers le coffre (la souris ne le fait pas tourner), puis fait demi-tour ; sa tête suit la scène, et
     * les autres le voient.
     */
    private static void figer(LocalPlayer joueur, Scene s, double t) {
        // il reste là où le serveur l'a vu (le coffre est posé par rapport à cette place) ; il peut seulement tomber
        joueur.setPos(s.pieds.x, joueur.getY(), s.pieds.z);
        joueur.xo = joueur.xOld = s.pieds.x;
        joueur.zo = joueur.zOld = s.pieds.z;
        joueur.setDeltaMovement(0, joueur.getDeltaMovement().y, 0);
        float tangage = tangage(s, t);
        float lacet = lacet(s, t);
        joueur.setYRot(lacet);
        joueur.yRotO = lacet;
        joueur.setYHeadRot(lacet);
        joueur.yHeadRotO = lacet;
        joueur.yBodyRot = lacet;
        joueur.yBodyRotO = lacet;
        joueur.setXRot(tangage);
        joueur.xRotO = tangage;
    }

    // ================================================================ ses bras (chez tout le monde)

    /**
     * Les mains sur le couvercle, qui montent avec lui ; elles retombent un peu dans le silence ; puis les deux bras
     * levés tout droit, mains rapprochées, la fée au-dessus (la pose de Zelda) ; enfin ils redescendent.
     */
    public static void poser(PlayerModel<?> m, Player joueur) {
        Scene s = SCENES.get(joueur.getUUID());
        if (s == null) {
            return;
        }
        double t = s.t();
        if (t < MAINS || t > DESCEND + 0.7) {
            return;
        }
        double x, z, w;
        if (t < OUVERT) {
            x = Mth.lerp(ouverture(t), -0.95, -1.75);
            z = -0.1;
            w = lisse((t - MAINS) / 0.5);
        } else if (t < SORT) {
            double k = lisse((t - OUVERT) / 0.5);
            x = Mth.lerp(k, -1.75, -0.45);
            z = Mth.lerp(k, -0.1, 0.05);
            w = 1;
        } else {
            double k = lisse((t - SORT) / 0.22);
            x = Mth.lerp(k, -0.45, -2.95);
            z = Mth.lerp(k, 0.05, 0.28);
            w = 1 - lisse((t - DESCEND) / 0.6);
        }
        float k = (float) w;
        m.rightArm.xRot = Mth.lerp(k, m.rightArm.xRot, (float) x);
        m.rightArm.yRot = Mth.lerp(k, m.rightArm.yRot, 0);
        m.rightArm.zRot = Mth.lerp(k, m.rightArm.zRot, (float) z);
        m.leftArm.xRot = Mth.lerp(k, m.leftArm.xRot, (float) x);
        m.leftArm.yRot = Mth.lerp(k, m.leftArm.yRot, 0);
        m.leftArm.zRot = Mth.lerp(k, m.leftArm.zRot, (float) -z);
        m.rightSleeve.copyFrom(m.rightArm);
        m.leftSleeve.copyFrom(m.leftArm);
    }

    // ================================================================ dans le monde (chez tout le monde)

    /** Le coffre et la fée (appelé par ClientVent, lumière du monde allumée). */
    static void modeles(PoseStack pose, MultiBufferSource.BufferSource buffers, Vec3 cam, float partiel) {
        if (SCENES.isEmpty()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        int[] ailes = {0, 1, 2, 1};
        ModeleVent fee = ModeleVent.premier("fee_" + ailes[(int) (System.nanoTime() / 70_000_000L % 4)]);
        boolean coffres = false;
        for (Scene s : SCENES.values()) {
            if (s.niveau != mc.level) {
                continue;
            }
            double t = s.t();
            if (t < DISPARU) {
                coffre(pose, buffers, s, t, cam);
                coffres = true;
            }
            Vec3[] f = fee(s, mc.level.getPlayerByUUID(s.joueur), t, partiel);
            if (f != null) {
                ClientVent.modele(pose, buffers, fee, f[0].subtract(cam), f[1], (float) (f[2].x * PX), 1);
            }
        }
        if (coffres) {
            buffers.endBatch(RenderType.entityCutout(TEXTURE));
        }
    }

    private static void coffre(PoseStack pose, MultiBufferSource buffers, Scene s, double t, Vec3 cam) {
        Minecraft mc = Minecraft.getInstance();
        if (fond == null) {
            ModelPart racine = mc.getEntityModels().bakeLayer(ModelLayers.CHEST);
            fond = racine.getChild("bottom");
            couvercle = racine.getChild("lid");
            serrure = racine.getChild("lock");
        }
        float echelle = (float) (TAILLE * apparition(t));
        if (echelle < 0.01F) {
            return;
        }
        // en partant, il tourne sur lui-même et s'élève un peu
        double part = lisse((t - DISPARAIT) / (DISPARU - DISPARAIT));
        pose.pushPose();
        pose.translate(s.coffre.x - cam.x, s.coffre.y - cam.y + 0.5 * part * part, s.coffre.z - cam.z);
        pose.mulPose(Axis.YP.rotation((float) (Math.PI - Math.toRadians(s.lacet) + part * part * Math.PI * 3)));
        pose.scale(echelle, echelle, echelle);
        pose.translate(-0.5, 0, -0.5);
        float angle = (float) (ouverture(t) * Math.toRadians(100));
        couvercle.xRot = -angle;
        serrure.xRot = -angle;
        // la lumière du dedans éclaire le coffre
        int lumiere = LevelRenderer.getLightColor(mc.level, BlockPos.containing(s.coffre.x, s.coffre.y + 0.5, s.coffre.z));
        int bloc = Math.max(LightTexture.block(lumiere), (int) Math.round(15 * Math.min(1, lumiere(t) * 1.3)));
        lumiere = LightTexture.pack(bloc, LightTexture.sky(lumiere));
        VertexConsumer c = buffers.getBuffer(RenderType.entityCutout(TEXTURE));
        fond.render(pose, c, lumiere, OverlayTexture.NO_OVERLAY);
        couvercle.render(pose, c, lumiere, OverlayTexture.NO_OVERLAY);
        serrure.render(pose, c, lumiere, OverlayTexture.NO_OVERLAY);
        pose.popPose();
    }

    /** Le vent et la lumière (appelé par ClientVent dans ses deux passes : les voiles, puis la lueur qui s'ajoute). */
    static void vent(VertexConsumer c, Matrix4f m, Vec3 cam, float partiel) {
        if (SCENES.isEmpty()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        for (Scene s : SCENES.values()) {
            if (s.niveau != mc.level) {
                continue;
            }
            double t = s.t();
            Vec3 base = s.coffre.subtract(cam);
            // le coup de vent qui apporte le coffre
            if (t < 1.3) {
                float a = (float) (0.4 * lisse(t / 0.12) * (1 - lisse((t - 0.45) / 0.8)));
                ClientVent.tornade(c, m, base, HAUT, 2.0, 0.35, 1.15, 10, 41, 1.6, t * 9, a, 0.35, 0.5 * PX, 12, 0.9 * PX);
            }
            // le vent qui tourne autour pendant qu'il s'ouvre
            double o = ouverture(t);
            if (t > OUVRE - 0.3 && t < DESCEND + 0.6) {
                float a = (float) ((0.1 + 0.22 * o) * lisse((t - OUVRE + 0.3) / 0.6) * (1 - lisse((t - DESCEND) / 0.6)));
                ClientVent.tornade(c, m, base, HAUT, 0.9 + 1.5 * o, 0.95, 1.25, 8, 57, 1.1, t * (2.2 + 3 * o), a, 0.3, 0.4 * PX, 6, 0.8 * PX);
            }
            // la lumière qui sort du coffre
            double l = lumiere(t);
            if (l > 0.01 && t < DISPARAIT) {
                rayons(c, m, s, base, t, l);
            }
            // l'éclat quand la fée sort : un anneau de vent qui file au sol
            if (t >= SORT && t < SORT + 0.8) {
                double k = (t - SORT) / 0.8;
                ClientVent.anneau(c, m, base.add(0, 0.06, 0), 0.6 + 3.8 * lisse(k / 0.7), 0.35, (float) (0.55 * (1 - k)), 10, 0.25, 0.6 * PX);
            }
            // le halo derrière la fée qu'il tient en l'air
            double h = lisse((t - SORT - 0.2) / 0.3) * (1 - lisse((t - DESCEND) / 0.4));
            if (h > 0.01) {
                Vec3[] f = fee(s, mc.level.getPlayerByUUID(s.joueur), t, partiel);
                if (f != null) {
                    halo(c, m, f[0].subtract(cam), t, (float) h);
                }
            }
            // le coffre s'en va dans un tourbillon
            if (t > DISPARAIT - 0.1 && t < DISPARU + 0.5) {
                double k = Mth.clamp((t - DISPARAIT + 0.1) / (DISPARU - DISPARAIT + 0.6), 0, 1);
                ClientVent.tornade(c, m, base, HAUT, 1.0 + 1.6 * k, 0.4, 1.2, 10, 73, 1.5, t * 8, (float) (0.4 * Math.sin(Math.PI * k)), 0.35, 0.5 * PX, 12, 0.9 * PX);
            }
        }
    }

    /** Des rais de lumière qui montent de l'ouverture du coffre, en s'écartant un peu. */
    private static void rayons(VertexConsumer c, Matrix4f m, Scene s, Vec3 base, double t, double l) {
        double haut = 10 * PX * TAILLE * apparition(t), demi = 6 * PX * TAILLE;
        for (int i = 0; i < 9; i++) {
            double ox = ((i * 37) % 9 / 8.0 - 0.5) * 2 * demi, oz = ((i * 53) % 7 / 6.0 - 0.5) * 2 * demi;
            Vec3 pied = base.add(s.droite.scale(ox)).add(s.devant.scale(oz)).add(0, haut, 0);
            double longueur = (0.8 + 2.6 * l) * (0.7 + 0.5 * ((i * 29) % 5) / 4.0);
            Vec3 bout = pied.add(s.droite.scale(ox * 1.3)).add(s.devant.scale(oz * 1.3)).add(0, longueur, 0);
            rayon(c, m, pied, bout, 0.1 + 0.08 * l, (float) (l * (0.75 + 0.25 * Math.sin(t * 5 + i * 1.7))));
        }
    }

    /** Un rai : un cœur pâle (passe 0), et une lueur pleine au milieu, rien aux bords ni au bout (passe 1). */
    private static void rayon(VertexConsumer c, Matrix4f m, Vec3 a, Vec3 b, double large, float alpha) {
        Vec3 cote = ClientVent.cote(a, b, null);
        if (cote == null) {
            return;
        }
        if (ClientVent.passe == 0) {
            Vec3 e = cote.scale(large * 0.22);
            ClientVent.sommet(c, m, a.subtract(e), RAYON, 0.3F * alpha);
            ClientVent.sommet(c, m, a.add(e), RAYON, 0.3F * alpha);
            ClientVent.sommet(c, m, b.add(e), RAYON, 0);
            ClientVent.sommet(c, m, b.subtract(e), RAYON, 0);
            return;
        }
        for (int cotes = -1; cotes <= 1; cotes += 2) {
            ClientVent.sommet(c, m, a, RAYON, 0.55F * alpha);
            ClientVent.sommet(c, m, b, RAYON, 0);
            ClientVent.sommet(c, m, b.add(cote.scale(cotes * large * 1.8)), RAYON, 0);
            ClientVent.sommet(c, m, a.add(cote.scale(cotes * large)), RAYON, 0);
        }
    }

    /** Le halo de Zelda derrière l'objet qu'on vient d'obtenir : des rayons dorés qui tournent, face à l'œil. */
    private static void halo(VertexConsumer c, Matrix4f m, Vec3 fee, double t, float alpha) {
        if (ClientVent.passe != 1 || fee.lengthSqr() < 1e-4) {
            return;
        }
        Vec3 vue = fee.normalize();
        Vec3 e1 = vue.cross(HAUT);
        e1 = e1.lengthSqr() < 1e-4 ? new Vec3(1, 0, 0) : e1.normalize();
        Vec3 e2 = e1.cross(vue).normalize();
        Vec3 centre = fee.add(vue.scale(0.15));
        int n = 12;
        for (int i = 0; i < n; i++) {
            double ang = i * Math.PI * 2 / n + t * 0.6;
            double longueur = (i % 2 == 0 ? 0.95 : 0.6) * (0.9 + 0.1 * Math.sin(t * 6 + i));
            Vec3 d = e1.scale(Math.cos(ang)).add(e2.scale(Math.sin(ang)));
            Vec3 p = e1.scale(-Math.sin(ang)).add(e2.scale(Math.cos(ang)));
            Vec3 a = centre.add(d.scale(0.12)), b = centre.add(d.scale(longueur));
            ClientVent.sommet(c, m, a.add(p.scale(-0.09)), OR, 0.6F * alpha);
            ClientVent.sommet(c, m, a.add(p.scale(0.09)), OR, 0.6F * alpha);
            ClientVent.sommet(c, m, b.add(p.scale(0.01)), OR, 0);
            ClientVent.sommet(c, m, b.add(p.scale(-0.01)), OR, 0);
        }
        // une lueur ronde, pleine au milieu
        int segments = 18;
        for (int i = 0; i < segments; i++) {
            double a0 = i * Math.PI * 2 / segments, a1 = (i + 1) * Math.PI * 2 / segments;
            ClientVent.sommet(c, m, centre, OR, 0.45F * alpha);
            ClientVent.sommet(c, m, centre, OR, 0.45F * alpha);
            ClientVent.sommet(c, m, centre.add(e1.scale(Math.cos(a1) * 0.4)).add(e2.scale(Math.sin(a1) * 0.4)), OR, 0);
            ClientVent.sommet(c, m, centre.add(e1.scale(Math.cos(a0) * 0.4)).add(e2.scale(Math.sin(a0) * 0.4)), OR, 0);
        }
    }

    // ================================================================ les sons et les particules (chez tout le monde)

    private static void son(Scene s, SoundEvent son, float volume, float hauteur) {
        Minecraft.getInstance().level.playLocalSound(s.coffre.x, s.coffre.y + 0.6, s.coffre.z, son, SoundSource.MASTER, volume, hauteur, false);
    }

    private static void sons(Minecraft mc, Scene s, double t) {
        boolean moi = s == mienne;
        boolean pack = mc.getSoundManager().getSoundEvent(MUSIQUE_ID) != null;
        if (s.passe(0.02, t)) {
            son(s, SoundEvents.BREEZE_IDLE_GROUND, 1.2F, 1.3F);
            son(s, SoundEvents.AMETHYST_BLOCK_CHIME, 1F, 0.8F);
        }
        if (s.passe(MUSIQUE, t) && pack) {
            // la musique : à son oreille à lui, et là où il est pour les autres (volume 2 : on l'entend à 32 blocs)
            SoundEvent musique = SoundEvent.createVariableRangeEvent(MUSIQUE_ID);
            s.musique = moi ? SimpleSoundInstance.forUI(musique, 1F, 1F)
                    : new SimpleSoundInstance(musique, SoundSource.MASTER, 2F, 1F, RandomSource.create(), s.pieds.x, s.pieds.y + 1, s.pieds.z);
            mc.getSoundManager().play(s.musique);
        }
        if (s.passe(OUVRE, t)) {
            son(s, SoundEvents.CHEST_OPEN, 1F, 0.55F);
        }
        if (!pack) {
            // sans la musique : des carillons qui montent pendant qu'il ouvre
            for (int i = 0; i < 5; i++) {
                if (s.passe(3 + i, t)) {
                    son(s, SoundEvents.AMETHYST_BLOCK_RESONATE, 0.7F + 0.1F * i, 0.7F + 0.2F * i);
                }
            }
        }
        if (s.passe(SORT, t)) {
            son(s, SoundEvents.BREEZE_IDLE_GROUND, 1.2F, 1.6F);
            son(s, SoundEvents.AMETHYST_BLOCK_CHIME, 1.2F, 1.5F);
            if (!pack) {
                if (moi) {
                    mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, 1F, 1F));
                } else {
                    son(s, SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, 2F, 1F);
                }
            }
        }
        if (s.passe(DISPARAIT, t)) {
            son(s, SoundEvents.BREEZE_IDLE_GROUND, 1F, 0.9F);
        }
    }

    private static void particules(Minecraft mc, Scene s, Player j, double t) {
        ClientLevel monde = mc.level;
        Vec3 c = s.coffre;
        double l = lumiere(t);
        if (s.passe(0.12, t)) {
            for (int i = 0; i < 14; i++) {
                monde.addParticle(ClientVent.paillette(i), c.x + HASARD.nextGaussian() * 0.45, c.y + HASARD.nextDouble() * 1.1, c.z + HASARD.nextGaussian() * 0.45, 0, 0.03, 0);
            }
            monde.addParticle(ParticleTypes.GUST, c.x, c.y + 0.6, c.z, 0, 0, 0);
        }
        // des paillettes qui s'échappent par la fente, de plus en plus
        double ouvertureY = c.y + 10 * PX * TAILLE;
        if (t > OUVRE && t < SORT && HASARD.nextFloat() < 0.25 + 0.75 * l) {
            for (int i = 0; i < 1 + (int) (l * 2.5); i++) {
                Vec3 p = c.add(s.droite.scale((HASARD.nextDouble() - 0.5) * 0.75 * TAILLE)).add(s.devant.scale((HASARD.nextDouble() - 0.5) * 0.75 * TAILLE));
                monde.addParticle(ClientVent.paillette(HASARD.nextInt(3)), p.x, ouvertureY, p.z, 0, 0.04 + 0.06 * l, 0);
            }
        }
        // la fée jaillit : une gerbe de paillettes (pas de baguettes de l'End ni d'éclair de feu d'artifice : ce pack les
        // dessine en noir ou en grand carré)
        if (s.passe(SORT, t)) {
            for (int i = 0; i < 12; i++) {
                monde.addParticle(ClientVent.paillette(i), c.x + HASARD.nextGaussian() * 0.35, ouvertureY + HASARD.nextDouble() * 0.8, c.z + HASARD.nextGaussian() * 0.35, 0, 0.06, 0);
            }
        }
        // des paillettes autour de la fée qu'il tient, puis derrière elle quand elle descend
        Vec3[] f = fee(s, j, t, 1);
        if (f != null && t < ORBITE + 0.3 && HASARD.nextInt(3) == 0) {
            monde.addParticle(ClientVent.paillette(HASARD.nextInt(3)), f[0].x + HASARD.nextGaussian() * 0.25, f[0].y + HASARD.nextGaussian() * 0.2,
                    f[0].z + HASARD.nextGaussian() * 0.25, 0, -0.02, 0);
        }
        if (s.passe(DISPARAIT + 0.3, t)) {
            for (int i = 0; i < 20; i++) {
                monde.addParticle(ClientVent.paillette(i), c.x + HASARD.nextGaussian() * 0.5, c.y + HASARD.nextDouble() * 1.5, c.z + HASARD.nextGaussian() * 0.5, 0, 0.08, 0);
            }
            monde.addParticle(ParticleTypes.GUST, c.x, c.y + 0.7, c.z, 0, 0, 0);
        }
    }

    // ================================================================ l'écran (chez lui seul)

    private record Morceau(String texte, int couleur) {
    }

    /** Les bandes noires, l'éclair quand la fée sort, la boîte de texte de Zelda, le fondu de la fin. */
    private static void ecran(GuiGraphics g) {
        Scene s = mienne;
        if (s == null) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        double t = s.t();
        int w = g.guiWidth(), h = g.guiHeight();
        if (camera) {
            int bande = (int) (h / 10 * lisse(t / 0.5));
            g.fill(0, 0, w, bande, 0xFF000000);
            g.fill(0, h - bande, w, h, 0xFF000000);
            if (t >= SORT && t < SORT + 0.8) {
                int a = (int) (150 * Math.exp(-(t - SORT) * 6));
                g.fill(0, 0, w, h, a << 24 | 0xF2FFE8);
            }
            boite(g, mc.font, w, h, bande, t);
        }
        double noir = t < RETOUR ? lisse((t - NOIR) / (RETOUR - NOIR)) : 1 - lisse((t - RETOUR) / (FIN - RETOUR));
        if (noir > 0.01) {
            g.fill(0, 0, w, h, Mth.clamp((int) (noir * 255), 0, 255) << 24);
        }
    }

    /** « Tu as obtenu la Fée du vent ! », lettre après lettre, dans une boîte noire aux coins arrondis. */
    private static void boite(GuiGraphics g, Font font, int w, int h, int bande, double t) {
        if (t < TEXTE || t > TEXTE_FIN) {
            return;
        }
        double a = lisse((t - TEXTE) / 0.15) * (1 - lisse((t - TEXTE_FIN + 0.3) / 0.3));
        int vert = 0x6CFF9A, blanc = 0xFFFFFF, or = 0xFFD86A;
        List<List<Morceau>> lignes = List.of(
                List.of(new Morceau("Tu as obtenu la ", blanc), new Morceau("Fée du vent", vert), new Morceau(" !", blanc)),
                List.of(new Morceau("Elle t'a choisi : tu as le ", blanc), new Morceau("pouvoir du Vent", vert), new Morceau(" (niveau 1).", blanc)),
                List.of(new Morceau("Appuie sur ", blanc), new Morceau("[" + Clavier.nom(ClientSusano.EVEIL) + "]", or), new Morceau(" pour l'éveiller.", blanc)));
        int large = 200;
        for (List<Morceau> ligne : lignes) {
            large = Math.max(large, ligne.stream().mapToInt(mo -> font.width(mo.texte())).sum());
        }
        int bw = large + 28, bh = lignes.size() * 12 + 14;
        int x = (w - bw) / 2, y = h - bande - bh - 10;
        int fondu = Mth.clamp((int) (a * 190), 0, 255) << 24;
        g.fill(x + 3, y, x + bw - 3, y + bh, fondu);
        g.fill(x + 1, y + 1, x + bw - 1, y + bh - 1, fondu);
        g.fill(x, y + 3, x + bw, y + bh - 3, fondu);
        int alpha = Mth.clamp((int) (a * 255), 8, 255) << 24;
        // la machine à écrire : 45 lettres par seconde
        int reste = (int) ((t - TEXTE - 0.1) * 45);
        int total = 0;
        for (int i = 0; i < lignes.size(); i++) {
            int cx = x + 14, cy = y + 8 + i * 12;
            for (Morceau mo : lignes.get(i)) {
                total += mo.texte().length();
                if (reste <= 0) {
                    continue;
                }
                String vu = mo.texte().substring(0, Math.min(mo.texte().length(), reste));
                reste -= vu.length();
                g.drawString(font, vu, cx, cy, alpha | mo.couleur(), true);
                cx += font.width(vu);
            }
        }
        // tout est écrit : la petite flèche verte qui clignote, en bas à droite
        if ((t - TEXTE - 0.1) * 45 > total && (int) (t * 3) % 2 == 0) {
            g.drawString(font, "▼", x + bw - 14, y + bh - 10, alpha | vert, false);
        }
    }

    // ================================================================ branchements

    @EventBusSubscriber(modid = Cabane.MODID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
    public static final class Enregistrement {
        @SubscribeEvent
        public static void couches(RegisterGuiLayersEvent event) {
            event.registerAboveAll(COUCHE, (g, delta) -> ecran(g));
        }

        private Enregistrement() {
        }
    }

    @EventBusSubscriber(modid = Cabane.MODID, value = Dist.CLIENT)
    public static final class Evenements {
        @SubscribeEvent
        public static void tick(ClientTickEvent.Post event) {
            if (SCENES.isEmpty()) {
                return;
            }
            Minecraft mc = Minecraft.getInstance();
            if (mc.level == null || mc.player == null) {
                SCENES.clear();
                mienne = null;
                rendreCamera();
                return;
            }
            for (Iterator<Scene> it = SCENES.values().iterator(); it.hasNext(); ) {
                Scene s = it.next();
                double t = s.t();
                if (s.niveau != mc.level || t > FIN) {
                    if (s.musique != null && s.niveau != mc.level) {
                        mc.getSoundManager().stop(s.musique);
                    }
                    it.remove();
                    continue;
                }
                sons(mc, s, t);
                particules(mc, s, mc.level.getPlayerByUUID(s.joueur), t);
                s.tPrecedent = t;
            }
            if (mienne != null && !SCENES.containsValue(mienne)) {
                mienne = null;
                rendreCamera();
            }
            if (mienne != null && camera) {
                double t = mienne.t();
                if (t >= RETOUR) {
                    figer(mc.player, mienne, t);
                    rendreCamera();
                } else {
                    figer(mc.player, mienne, t);
                    if (objectif != null && mc.getCameraEntity() != objectif) {
                        mc.setCameraEntity(objectif);
                    }
                }
            }
        }

        /** La caméra et sa tête se placent à chaque image : les mouvements restent fluides. */
        @SubscribeEvent
        public static void image(RenderFrameEvent.Pre event) {
            Minecraft mc = Minecraft.getInstance();
            if (!camera || mienne == null || mc.player == null) {
                return;
            }
            double t = mienne.t();
            placerCamera(mienne, t);
            figer(mc.player, mienne, t);
        }

        /** Pas de pseudo au-dessus des têtes pendant la scène (le sien flotterait au milieu de l'image). */
        @SubscribeEvent
        public static void pasDeNom(RenderNameTagEvent event) {
            if (camera) {
                event.setCanRender(TriState.FALSE);
            }
        }

        @SubscribeEvent
        public static void champ(ViewportEvent.ComputeFov event) {
            if (camera) {
                event.setFOV(62);
            }
        }

        @SubscribeEvent
        public static void immobile(MovementInputUpdateEvent event) {
            if (camera) {
                event.getInput().leftImpulse = 0;
                event.getInput().forwardImpulse = 0;
                event.getInput().jumping = false;
                event.getInput().shiftKeyDown = false;
                event.getInput().up = false;
                event.getInput().down = false;
                event.getInput().left = false;
                event.getInput().right = false;
            }
        }

        @SubscribeEvent
        public static void pasDeClic(InputEvent.InteractionKeyMappingTriggered event) {
            if (camera) {
                event.setCanceled(true);
                event.setSwingHand(false);
            }
        }

        @SubscribeEvent
        public static void pasDeMain(RenderHandEvent event) {
            if (camera) {
                event.setCanceled(true);
            }
        }

        @SubscribeEvent
        public static void pasDInterface(RenderGuiLayerEvent.Pre event) {
            if (camera && !event.getName().equals(COUCHE)) {
                event.setCanceled(true);
            }
        }

        private Evenements() {
        }
    }

    private ClientEluVent() {
    }
}
