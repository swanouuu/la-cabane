package fr.cabane.client.cinema;

import com.mojang.blaze3d.vertex.PoseStack;
import fr.cabane.Cabane;
import fr.cabane.client.cinema.Effets.Posture;
import fr.cabane.client.ui.Ios;
import java.util.Random;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.Blocks;
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
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;

/**
 * « Le Prince contre Kakarot » : la cinématique du combat, jouée sur l'écran de chaque joueur en même temps, au-dessus
 * de Beauvais. C'est l'extrait de Dragon Ball Z Kai où Goku et Majin Vegeta passent Super Saiyan 2, refait plan par plan
 * en Minecraft : un écran noir (« Goku est enfin arrivé… »), l'extrait, puis la question (qui aider ?). La caméra, les
 * deux combattants, les auras et les éclairs sont calculés ici d'après le temps écoulé, sur l'horloge réelle pour rester
 * calés sur le son de l'extrait (cabane:cine.extrait, dans le pack de ressources privé « Beauvais - voix »).
 * Le serveur ne fait que donner le top départ (et creuser les cratères dans l'île).
 */
public final class ClientCinema {
    /** L'écran noir du début, puis l'extrait (80,3 s), puis la question. */
    private static final double INTRO = 4;
    private static final double EXTRAIT = 80.34;
    private static final double FIN = INTRO + EXTRAIT;
    /** Le vote s'ouvre dès que la question est à l'écran (le serveur l'envoie à ce moment-là). */
    public static final double DEBUT_VOTE = FIN + 1.2;
    /** Au plus tard, la cinématique s'arrête ici (si le vote tarde, la question reste affichée). */
    public static final double DUREE = DEBUT_VOTE + 8;
    public static final double DUREE_REELLE = DUREE;
    /** Les moments clés : Goku charge, Goku passe Super Saiyan 2 (le flash), Vegeta libère sa puissance (le flash). */
    private static final double SSJ = INTRO + 29.37;
    private static final double SSJ2 = INTRO + 31.12;
    private static final double MAJIN = INTRO + 39.08;
    private static final ResourceLocation SKIN_VEGETA = Cabane.id("textures/entity/pnj/vegeta.png");
    private static final ResourceLocation SKIN_MAJIN = Cabane.id("textures/entity/pnj/majin_vegeta.png");
    private static final ResourceLocation SKIN_GOKU = Cabane.id("textures/entity/pnj/goku_ssj2.png");
    private static final ResourceLocation SKIN_GOKU_BASE = Cabane.id("textures/entity/pnj/goku_base.png");

    static boolean actif;
    private static Vec3 o;
    private static int ticks;
    /** Quand l'extrait a commencé (System.nanoTime) : le temps de la cinématique suit l'horloge, pas les ticks. */
    private static long debutNanos;
    private static double t;
    /** Le temps réel (en secondes) : t est le temps du scénario. */
    private static double reel;
    private static double tPrecedent;
    /** Le temps au dernier tick : les événements (sons, flashs) se déclenchent entre deux ticks, quoi qu'aient calculé les images. */
    private static double tDernierTick;
    private static Entity objectif;
    private static CameraType vueAvant;
    private static boolean guiAvant;

    // ---- ce que montre l'image (recalculé à chaque image)
    private static Vec3 camera = Vec3.ZERO;
    private static Vec3 vise = Vec3.ZERO;
    private static float fov = 70;
    private static float roulis;
    private static float secousse;
    private static float flash;
    private static int couleurFlash = 0xFFFFFF;
    private static float noir;
    private static String parleur = "";
    private static String replique = "";
    private static String titre = "";
    private static String sousTitre = "";

    /** Un combattant : où il est, où il regarde, sa posture, son aura. */
    private static final class Acteur {
        Vec3 pos = Vec3.ZERO;
        float lacet;
        Posture posture = Posture.DEBOUT;
        boolean visible = true;
        ResourceLocation skin;
        float aura;
        float[] couleur;
        float transparence = 1;
        int coiffure;
        float[] cheveux = Effets.NOIR;
        float echelleCheveux = 1;
    }

    private static final Acteur GOKU = new Acteur();
    private static final Acteur VEGETA = new Acteur();

    // ---- les effets du moment
    private static Vec3 rayonVegetaFin;
    private static Vec3 rayonGokuFin;
    private static Vec3 boule;
    private static double tailleBoule;
    private static double ondeRayon;
    private static float ondeAlpha;
    private static Vec3 ondeCentre = Vec3.ZERO;
    /** Un éclat de coup : où, quand (temps du scénario), sa force (0,5 petit coup … 3 explosion) et sa couleur. */
    private record Eclat(Vec3 pos, double debut, float force, float[] couleur) {
    }

    private static final java.util.List<Eclat> ECLATS = new java.util.ArrayList<>();
    private static final float[] BLANC = {1F, 1F, 1F};
    private static final float[] OR = {1F, 0.85F, 0.3F};
    private static final float[] VIOLET = {0.85F, 0.45F, 1F};
    private static final float[] BLEU = {0.45F, 0.75F, 1F};

    /** Le top départ, envoyé par le serveur : o est le centre de l'île, au niveau du sol. */
    public static void demarrer(Vec3 origine) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            return;
        }
        o = origine;
        ticks = 0;
        debutNanos = System.nanoTime();
        t = 0;
        tPrecedent = 0;
        tDernierTick = 0;
        ECLATS.clear();
        actif = true;
        mc.setScreen(null);
        guiAvant = mc.options.hideGui;
        mc.options.hideGui = true;
        vueAvant = mc.options.getCameraType();
        mc.options.setCameraType(CameraType.FIRST_PERSON);
        objectif = EntityType.MARKER.create(mc.level);
        calculer(0);
        placerCamera();
        mc.setCameraEntity(objectif);
    }

    public static void arreter() {
        if (!actif) {
            return;
        }
        actif = false;
        Minecraft mc = Minecraft.getInstance();
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

    private static double ecoule() {
        return (System.nanoTime() - debutNanos) / 1e9;
    }

    /** Pendant une transformation, les cheveux clignotent entre deux couleurs, de plus en plus vite. */
    private static boolean clignote(double debut, double fin) {
        if (reel < debut || reel >= fin) {
            return false;
        }
        double k = (reel - debut) / (fin - debut);
        return Math.sin((reel - debut) * (12 + k * 30)) > 0.2 - k * 0.6;
    }

    private static Vec3 l(double x, double y, double z) {
        return o.add(x, y, z);
    }

    private static double entre(double a, double b) {
        return Mth.clamp((t - a) / (b - a), 0, 1);
    }

    private static double lisse(double k) {
        return k * k * (3 - 2 * k);
    }

    private static Vec3 vers(Vec3 a, Vec3 b, double k) {
        return a.add(b.subtract(a).scale(k));
    }

    private static float lacetVers(Vec3 de, Vec3 a) {
        return (float) Math.toDegrees(Math.atan2(-(a.x - de.x), a.z - de.z));
    }

    private static Vec3 devant(Acteur a, double d) {
        double r = Math.toRadians(a.lacet);
        return new Vec3(-Math.sin(r) * d, 0, Math.cos(r) * d);
    }

    /** Sur le côté (vers la droite de l'acteur). */
    private static Vec3 cote(Acteur a, double d) {
        double r = Math.toRadians(a.lacet);
        return new Vec3(-Math.cos(r) * d, 0, -Math.sin(r) * d);
    }

    private static Vec3 yeux(Acteur a) {
        return a.pos.add(0, 1.62, 0);
    }

    /** Une caméra posée près d'un acteur : devant (avant), sur le côté (cote), en hauteur par rapport à ses yeux. */
    private static Vec3 pres(Acteur a, double avant, double cote, double haut) {
        return yeux(a).add(devant(a, avant)).add(cote(a, cote)).add(0, haut, 0);
    }

    private static Vec3 mains(Acteur a) {
        return a.pos.add(0, 1.35, 0).add(devant(a, 0.9));
    }

    /** Le plan en cours de l'extrait (temps de l'extrait, en secondes) : début inclus, fin exclue. */
    private static boolean plan(double c, double debut, double fin) {
        return c >= debut && c < fin;
    }

    /** Tout ce qui est à l'écran à l'instant t (en secondes depuis le début). */
    private static void calculer(double temps) {
        reel = temps;
        t = temps;
        parleur = "";
        replique = "";
        titre = "";
        sousTitre = "";
        flash = 0;
        roulis = 0;
        secousse = 0;
        fov = 70;
        noir = 0;
        ondeAlpha = 0;
        GOKU.visible = true;
        VEGETA.visible = true;
        // Goku arrive en Super Saiyan et passe Super Saiyan 2 au flash ; Vegeta est déjà Majin, le M sur le front
        boolean gokuSsj2 = t >= SSJ2;
        GOKU.skin = SKIN_GOKU;
        GOKU.coiffure = Effets.GOKU_SSJ;
        GOKU.cheveux = Effets.couleurCheveux(SKIN_GOKU, Effets.DORE);
        GOKU.echelleCheveux = gokuSsj2 ? 1.12F : 0.94F;
        GOKU.couleur = new float[] {1F, 0.84F, 0.25F};
        VEGETA.skin = SKIN_MAJIN;
        VEGETA.coiffure = Effets.VEGETA;
        VEGETA.cheveux = Effets.couleurCheveux(SKIN_MAJIN, Effets.DORE);
        VEGETA.echelleCheveux = t >= MAJIN ? 1.16F : 1.08F;
        VEGETA.couleur = new float[] {1F, 0.8F, 0.28F};
        GOKU.transparence = 1;
        VEGETA.transparence = 1;
        GOKU.pos = l(-7, 0, 0);
        VEGETA.pos = l(7, 0, 0);
        GOKU.lacet = lacetVers(GOKU.pos, VEGETA.pos);
        VEGETA.lacet = lacetVers(VEGETA.pos, GOKU.pos);
        GOKU.posture = Posture.GARDE.vers(Posture.DEBOUT, 0.4F);
        VEGETA.posture = Posture.DEBOUT;
        // les auras respirent ; elles explosent aux transformations
        float souffle = (float) (0.12 * Math.sin(t * 7));
        GOKU.aura = (gokuSsj2 ? 1.5F : 0.9F) + souffle;
        VEGETA.aura = (t >= MAJIN ? 1.7F : 1.1F) - souffle;

        double c = t - INTRO;
        if (t < INTRO) {
            // ---- l'écran noir : Goku est enfin arrivé
            noir = (float) (1 - Mth.clamp((t - 3.4) / 0.6, 0, 1));
            if (t > 0.4 && t < 3.6) {
                titre = "GOKU EST ENFIN ARRIVÉ…";
                sousTitre = "Au-dessus de Beauvais, Vegeta l'attendait.";
            }
            camera = pres(GOKU, 1.8, 0, 0);
            vise = yeux(GOKU);
            fov = 50;
        } else if (c < 4.5) {
            // 1. Goku, de face, le regard dur (la caméra s'approche)
            camera = pres(GOKU, 1.8 - 0.5 * lisse(c / 4.5), 0, 0.05);
            vise = yeux(GOKU);
            fov = 46;
            GOKU.posture = Posture.DEBOUT;
        } else if (plan(c, 4.5, 9.29)) {
            // 2. par-dessus l'épaule de Goku, le poing serré ; Vegeta au loin dans son aura
            camera = GOKU.pos.add(devant(GOKU, -0.7)).add(cote(GOKU, 0.95)).add(0, 1.05, 0);
            vise = VEGETA.pos.add(0, 1.2, 0);
            fov = 52;
            GOKU.posture = Posture.GARDE;
        } else if (plan(c, 9.29, 10)) {
            // 3. le dos de Vegeta, tout près
            camera = pres(VEGETA, -1.3, 0.3, -0.25);
            vise = yeux(VEGETA).add(devant(VEGETA, 4));
            fov = 62;
        } else if (plan(c, 10, 11.75)) {
            // 4. Vegeta de côté, en contre-plongée : il crie
            camera = VEGETA.pos.add(cote(VEGETA, 2.3)).add(devant(VEGETA, 0.7)).add(0, 0.55, 0);
            vise = yeux(VEGETA);
            fov = 55;
            VEGETA.posture = Posture.CRI;
            secousse = 0.25F;
        } else if (plan(c, 11.75, 14.04)) {
            // 5. le ciel et les rochers de l'île, la caméra balaie
            double k = lisse((c - 11.75) / 2.29);
            camera = l(0, 2.5, -15);
            vise = vers(l(-26, 7, -30), l(26, 5, -30), k);
            fov = 62;
        } else if (plan(c, 14.04, 20.58)) {
            // 6. le grand plan : Vegeta de dos au premier plan, Goku au loin, la poussière qui monte
            camera = VEGETA.pos.add(devant(VEGETA, -3.2)).add(cote(VEGETA, -1.8)).add(0, 1.5, 0);
            vise = GOKU.pos.add(0, 1.1, 0);
            fov = 48;
            GOKU.aura += 0.4F;
            VEGETA.aura += 0.4F;
        } else if (plan(c, 20.58, 24.12)) {
            // 7. Goku de trois quarts : il parle
            camera = pres(GOKU, 1.5, 0.85, 0);
            vise = yeux(GOKU);
            fov = 44;
        } else if (plan(c, 24.12, 29.37)) {
            // 8. Vegeta de trois quarts, en contre-plongée, le M sur le front : il sourit
            camera = pres(VEGETA, 1.5, -0.8, -0.4);
            vise = yeux(VEGETA).add(0, 0.05, 0);
            fov = 44;
        } else if (plan(c, 29.37, 30.29)) {
            // 9. Goku de profil, les dents serrées
            camera = pres(GOKU, 0.2, 1.4, 0);
            vise = yeux(GOKU);
            fov = 44;
            GOKU.posture = Posture.CRI.vers(Posture.DEBOUT, 0.5F);
            GOKU.aura += 0.5F;
            secousse = 0.3F;
        } else if (plan(c, 30.29, 31.12)) {
            // 10. Goku lève les bras et hurle
            camera = GOKU.pos.add(devant(GOKU, 3.2)).add(0, 0.35, 0);
            vise = GOKU.pos.add(0, 1.7, 0);
            fov = 60;
            GOKU.posture = BRAS_LEVES;
            GOKU.aura = 2F;
            secousse = 0.6F;
        } else if (plan(c, 31.12, 31.4)) {
            // 11. le flash blanc : Super Saiyan 2
            camera = GOKU.pos.add(devant(GOKU, 3.2)).add(0, 0.35, 0);
            vise = GOKU.pos.add(0, 1.7, 0);
            fov = 60;
            GOKU.posture = BRAS_LEVES;
            flash = 1F;
            couleurFlash = 0xFFF8D0;
        } else if (plan(c, 31.4, 33.42)) {
            // 12. Goku Super Saiyan 2, de face, le ciel qui s'assombrit, l'aura en pointes
            camera = GOKU.pos.add(devant(GOKU, 4.6)).add(0, 1, 0);
            vise = GOKU.pos.add(0, 1.1, 0);
            fov = 55;
            GOKU.posture = PUISSANCE;
            GOKU.aura = 2.4F + souffle * 3;
            flash = (float) (0.32 + 0.5 * Math.max(0, 1 - (c - 31.4) / 0.4));
            couleurFlash = 0x101040;
            secousse = 0.7F;
        } else if (plan(c, 33.42, 37.92)) {
            // 13. Vegeta dans la poussière, plan moyen : il sourit
            camera = VEGETA.pos.add(devant(VEGETA, 4.2)).add(0, 1.3, 0);
            vise = VEGETA.pos.add(0, 1.35, 0);
            fov = 50;
        } else if (plan(c, 37.92, 39.08)) {
            // 14. Vegeta, le visage tout près : ça monte
            camera = pres(VEGETA, 1.35 - 0.25 * (c - 37.92), 0, 0.02);
            vise = yeux(VEGETA);
            fov = 46;
            VEGETA.posture = Posture.CRI.vers(Posture.DEBOUT, 0.6F);
            secousse = 0.35F;
        } else if (plan(c, 39.08, 39.83)) {
            // 15. le flash blanc : la puissance de Vegeta
            camera = pres(VEGETA, 1.1, 0, 0.02);
            vise = yeux(VEGETA);
            fov = 46;
            flash = 1F;
            couleurFlash = 0xFFF8D0;
        } else if (plan(c, 39.83, 41.62)) {
            // 16. Vegeta bombe le torse, les poings serrés, le ciel noir
            camera = VEGETA.pos.add(devant(VEGETA, 3)).add(0, 1.1, 0);
            vise = VEGETA.pos.add(0, 1.3, 0);
            fov = 56;
            VEGETA.posture = PUISSANCE;
            VEGETA.aura = 2.6F + souffle * 3;
            flash = 0.32F;
            couleurFlash = 0x101040;
            secousse = 0.8F;
        } else if (plan(c, 41.62, 42.37)) {
            // 17. Goku, le visage calme
            camera = pres(GOKU, 1.2, 0, 0);
            vise = yeux(GOKU);
            fov = 44;
        } else if (plan(c, 42.37, 46.42)) {
            // 18. la poussière explose ; grand plan, Goku au loin, Vegeta au premier plan
            camera = VEGETA.pos.add(devant(VEGETA, -2.6)).add(cote(VEGETA, -2.1)).add(0, 1.7, 0);
            vise = GOKU.pos.add(0, 1, 0);
            fov = 50;
            secousse = (float) (0.9 * Math.max(0, 1 - (c - 42.37) / 1.2));
        } else if (plan(c, 46.42, 52.46)) {
            // 19. Vegeta, le torse en contre-plongée : la force brute
            camera = VEGETA.pos.add(devant(VEGETA, 1.7)).add(cote(VEGETA, 0.7)).add(0, 0.45, 0);
            vise = VEGETA.pos.add(0, 1.55, 0);
            fov = 62;
            VEGETA.posture = PUISSANCE;
        } else if (plan(c, 52.46, 56.33)) {
            // 20. Goku de côté, les éclairs crépitent autour de lui
            camera = pres(GOKU, 0.5, 1.2, -0.3);
            vise = yeux(GOKU).add(0, -0.2, 0);
            fov = 50;
        } else if (plan(c, 56.33, 56.58)) {
            // 21. Vegeta hurle dans un éclair
            camera = pres(VEGETA, 1.1, 0, 0);
            vise = yeux(VEGETA);
            fov = 46;
            VEGETA.posture = Posture.CRI;
            flash = 0.55F;
            couleurFlash = 0xC8E0FF;
        } else if (plan(c, 56.58, 59.83)) {
            // 22. Vegeta pointe le poing vers Goku
            camera = VEGETA.pos.add(devant(VEGETA, 2.5)).add(cote(VEGETA, 1.2)).add(0, 1.3, 0);
            vise = yeux(VEGETA).add(0, -0.2, 0);
            fov = 55;
            VEGETA.posture = POING;
        } else if (plan(c, 59.83, 63.92)) {
            // 23. Goku tout près, les éclairs
            camera = pres(GOKU, 1.4, -0.6, 0);
            vise = yeux(GOKU);
            fov = 48;
        } else if (plan(c, 63.92, 69.04)) {
            // 24. Vegeta hurle, le M bien visible, les éclairs
            camera = pres(VEGETA, 1.25, 0, 0.1);
            vise = yeux(VEGETA);
            fov = 48;
            VEGETA.posture = Posture.CRI;
            secousse = 0.4F;
        } else if (c < EXTRAIT) {
            // 25. vu du ciel : deux petites auras dans le cratère de poussière
            double k = (c - 69.04) / (EXTRAIT - 69.04);
            camera = l(0, 38 + 8 * k, -7 + 3 * k);
            vise = l(0, 0, 0);
            fov = 60;
            GOKU.aura = 2F;
            VEGETA.aura = 2.2F;
            noir = (float) Math.max(0, (c - (EXTRAIT - 0.8)) / 0.8);
        } else {
            // ---- la question
            noir = 1;
            titre = "QUI CHOISISSEZ-VOUS D'AIDER ?";
            sousTitre = "Goku… ou Vegeta ?";
            camera = l(0, 46, -4);
            vise = l(0, 0, 0);
        }
        eclatsEcran();
    }

    /** Les bras levés au ciel, la tête en arrière : il hurle. */
    private static final Posture BRAS_LEVES = new Posture(-0.45F, 0, -8, -2.9F, 0, 0.25F, -2.9F, 0, -0.25F, 0, 0.25F, 0, -0.25F);
    /** Les bras écartés et pliés, les poings serrés : il fait monter sa puissance. */
    private static final Posture PUISSANCE = new Posture(-0.12F, 0, 4, -0.35F, 0, 0.6F, -0.35F, 0, -0.6F, -0.1F, 0.3F, 0.1F, -0.3F);
    /** Le poing tendu vers l'adversaire. */
    private static final Posture POING = new Posture(0, 0, 6, -1.57F, 0.1F, 0, 0.1F, 0, -0.12F, -0.2F, 0.2F, 0.2F, -0.2F);

    /** Les coups qui viennent de tomber : un flash bref à l'écran (de leur couleur) et une secousse. */
    private static void eclatsEcran() {
        float plusFort = 0;
        float[] couleur = BLANC;
        for (Eclat e : ECLATS) {
            double age = t - e.debut();
            if (age < 0 || age > 0.25) {
                continue;
            }
            float f = (float) (Math.min(0.9, 0.22 * e.force()) * (1 - age / 0.25));
            secousse = Math.max(secousse, (float) (e.force() * 0.45 * (1 - age / 0.25)));
            if (f > plusFort) {
                plusFort = f;
                couleur = e.couleur();
            }
        }
        if (plusFort > flash) {
            flash = plusFort;
            // un flash presque blanc, à peine teinté
            couleurFlash = (int) (255 * (0.6 + 0.4 * couleur[0])) << 16 | (int) (255 * (0.6 + 0.4 * couleur[1])) << 8 | (int) (255 * (0.6 + 0.4 * couleur[2]));
        }
    }

    private static void placerCamera() {
        if (objectif == null) {
            return;
        }
        Vec3 c = camera;
        if (secousse > 0) {
            double s = secousse * 0.12;
            c = c.add((Math.random() - 0.5) * s, (Math.random() - 0.5) * s, (Math.random() - 0.5) * s);
        }
        Vec3 d = vise.subtract(c);
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

    // ================================================================ les événements ponctuels (sons, gerbes de particules)

    private static boolean passe(double moment) {
        return tPrecedent < moment && t >= moment;
    }

    private static void son(SoundEvent son, float volume, float hauteur) {
        Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(son, hauteur, volume));
    }

    /** Un son du pack de ressources privé (l'extrait), s'il est là ; sinon, rien. */
    private static void sonPrive(String nom) {
        Minecraft mc = Minecraft.getInstance();
        ResourceLocation id = Cabane.id(nom);
        if (mc.getSoundManager().getSoundEvent(id) != null) {
            mc.getSoundManager().play(new SimpleSoundInstance(id, SoundSource.MASTER, 1F, 1F, RandomSource.create(), false, 0,
                    SoundInstance.Attenuation.NONE, 0, 0, 0, true));
        } else {
            Cabane.LOGGER.warn("Son {} introuvable : le pack de ressources « Beauvais - voix » n'est pas activé", id);
        }
    }

    private static void particules(ParticleOptions type, Vec3 p, int n, double etendue, double vitesse) {
        var level = Minecraft.getInstance().level;
        RandomSource r = level.random;
        for (int i = 0; i < n; i++) {
            level.addParticle(type, true, p.x + (r.nextDouble() - 0.5) * etendue, p.y + (r.nextDouble() - 0.5) * etendue, p.z + (r.nextDouble() - 0.5) * etendue,
                    (r.nextDouble() - 0.5) * vitesse, (r.nextDouble() - 0.5) * vitesse, (r.nextDouble() - 0.5) * vitesse);
        }
    }

    /**
     * Un coup qui porte : un éclat de lumière (dessiné dans le monde et à l'écran) et le bruit d'un coup de manga, en couches —
     * le claquement sec, le choc sourd, le souffle de l'onde, et pour les gros coups le tonnerre.
     */
    private static void impact(Vec3 p, float force, float[] couleur) {
        ECLATS.add(new Eclat(p, t, force, couleur));
        RandomSource r = Minecraft.getInstance().level.random;
        float h = 1F / (0.8F + force * 0.25F);
        son(SoundEvents.PLAYER_ATTACK_CRIT, 1F, 0.7F + r.nextFloat() * 0.3F);
        son(SoundEvents.MACE_SMASH_GROUND_HEAVY, Math.min(1F, 0.5F + force * 0.25F), h + r.nextFloat() * 0.2F);
        son(SoundEvents.BREEZE_WIND_CHARGE_BURST.value(), Math.min(1F, 0.4F + force * 0.2F), 0.9F + r.nextFloat() * 0.4F);
        son(SoundEvents.GENERIC_EXPLODE.value(), Math.min(1F, 0.25F + force * 0.2F), 1.9F - Math.min(1.3F, force * 0.35F));
        if (force >= 1.5F) {
            son(SoundEvents.TRIDENT_THUNDER.value(), Math.min(1F, force * 0.35F), 1.4F + r.nextFloat() * 0.3F);
        }
        particules(ParticleTypes.FLASH, p, 1, 0, 0);
        particules(ParticleTypes.CRIT, p, (int) (14 * force), 0.5, 1.2);
        particules(ParticleTypes.ELECTRIC_SPARK, p, (int) (10 * force), 0.6, 1.4);
        particules(ParticleTypes.GUST, p, 1, 0.2, 0);
        if (force >= 1.2F) {
            particules(ParticleTypes.SONIC_BOOM, p, 1, 0, 0);
        }
    }

    /** Le « zip » d'une téléportation éclair : un sifflement aigu et une trace de lumière. */
    private static void zip(Vec3 p) {
        RandomSource r = Minecraft.getInstance().level.random;
        son(SoundEvents.ENDERMAN_TELEPORT, 0.45F, 1.8F + r.nextFloat() * 0.2F);
        son(SoundEvents.BREEZE_JUMP, 0.6F, 1.6F + r.nextFloat() * 0.3F);
        particules(ParticleTypes.END_ROD, p, 8, 0.8, 0.2);
    }

    private static void evenements() {
        ECLATS.removeIf(e -> t - e.debut() > 1);
        var level = Minecraft.getInstance().level;
        RandomSource r = level.random;
        // l'extrait : son image et son son partent ensemble
        if (passe(INTRO)) {
            sonPrive("cine.extrait");
        }
        if (passe(0.3)) {
            son(SoundEvents.AMBIENT_CAVE.value(), 0.7F, 0.5F);
        }
        // Goku passe Super Saiyan 2
        if (passe(SSJ2)) {
            impact(GOKU.pos.add(0, 1.2, 0), 3F, OR);
            particules(ParticleTypes.EXPLOSION, GOKU.pos.add(0, 1, 0), 6, 3, 0);
            particules(ParticleTypes.FIREWORK, GOKU.pos.add(0, 1, 0), 60, 2, 0.9);
        }
        // Vegeta libère sa puissance
        if (passe(MAJIN)) {
            impact(VEGETA.pos.add(0, 1.2, 0), 3F, OR);
            particules(ParticleTypes.EXPLOSION, VEGETA.pos.add(0, 1, 0), 6, 3, 0);
            particules(ParticleTypes.FIREWORK, VEGETA.pos.add(0, 1, 0), 60, 2, 0.9);
        }
        // la poussière qui explose (plan 18) et l'éclair de Vegeta (plan 21)
        if (passe(INTRO + 42.37)) {
            for (int i = 0; i < 6; i++) {
                ECLATS.add(new Eclat(l((r.nextDouble() - 0.5) * 18, r.nextDouble() * 2, (r.nextDouble() - 0.5) * 18), t + i * 0.05, 1.4F, OR));
            }
            particules(ParticleTypes.EXPLOSION_EMITTER, l(0, 1, 0), 2, 8, 0);
        }
        if (passe(INTRO + 56.33)) {
            ECLATS.add(new Eclat(yeux(VEGETA), t, 2F, BLEU));
        }
        // le ciel qui gronde pendant les transformations
        for (double m : new double[] {SSJ - 0.2, MAJIN - 1.1, INTRO + 52.5, INTRO + 64}) {
            if (passe(m)) {
                ECLATS.add(new Eclat(l((r.nextDouble() - 0.5) * 30, 12 + r.nextDouble() * 6, (r.nextDouble() - 0.5) * 30), t, 1.2F, BLEU));
            }
        }
    }

    /** Les particules continues : étincelles des auras, roches qui s'envolent, poussière, éclairs. */
    private static void ambiance() {
        var level = Minecraft.getInstance().level;
        RandomSource r = level.random;
        if (t < INTRO - 0.6 || t >= FIN) {
            return;
        }
        // les auras : des étincelles dorées qui montent
        for (Acteur a : new Acteur[] {GOKU, VEGETA}) {
            int n = (int) (a.aura * 2);
            for (int i = 0; i < n; i++) {
                Vec3 p = a.pos.add((r.nextDouble() - 0.5) * 1.6, r.nextDouble() * 2.2, (r.nextDouble() - 0.5) * 1.6);
                if (p.distanceTo(camera) > 1.5) {
                    level.addParticle(new DustParticleOptions(new org.joml.Vector3f(a.couleur[0], a.couleur[1], a.couleur[2]), 0.6F), true,
                            p.x, p.y, p.z, 0, 0.25, 0);
                }
            }
            // Super Saiyan 2 : des étincelles électriques
            boolean ssj2 = a == GOKU ? t >= SSJ2 : t >= MAJIN;
            if (ssj2 && r.nextInt(2) == 0) {
                level.addParticle(ParticleTypes.ELECTRIC_SPARK, true, a.pos.x + (r.nextDouble() - 0.5), a.pos.y + r.nextDouble() * 2, a.pos.z + (r.nextDouble() - 0.5), 0, 0, 0);
            }
        }
        // la poussière soulevée par les deux puissances : un nuage jaune sable autour d'eux, des cailloux qui s'envolent
        org.joml.Vector3f sable = new org.joml.Vector3f(0.86F, 0.76F, 0.52F);
        for (int i = 0; i < 5; i++) {
            Acteur a = r.nextBoolean() ? GOKU : VEGETA;
            double ang = r.nextDouble() * Math.PI * 2;
            double d = 1.5 + r.nextDouble() * 4;
            Vec3 p = a.pos.add(Math.cos(ang) * d, 0.15 + r.nextDouble() * 0.7, Math.sin(ang) * d);
            // jamais collée à l'objectif : en gros plan, un grain de poussière cacherait tout l'écran
            if (p.distanceTo(camera) > 4) {
                level.addParticle(new DustParticleOptions(sable, 1.6F), true, p.x, p.y, p.z, -Math.cos(ang) * 0.05, 0.03, -Math.sin(ang) * 0.05);
            }
        }
        if (r.nextInt(2) == 0) {
            level.addParticle(ParticleTypes.CAMPFIRE_COSY_SMOKE, true, o.x + (r.nextDouble() - 0.5) * 22, o.y + 0.3, o.z + (r.nextDouble() - 0.5) * 14, 0, 0.02, 0);
        }
        // aux transformations : l'énergie remonte du sol en spirale, des roches arrachées
        boolean transfo = (t >= SSJ && t < SSJ2 + 2.3) || (t >= MAJIN - 1.2 && t < MAJIN + 2.6);
        if (transfo) {
            Acteur a = t < MAJIN - 2 ? GOKU : VEGETA;
            for (int i = 0; i < 3; i++) {
                double ang = r.nextDouble() * Math.PI * 2;
                double d = 1.5 + r.nextDouble() * 2.5;
                level.addParticle(ParticleTypes.FIREWORK, true, a.pos.x + Math.cos(ang) * d, a.pos.y + 0.1, a.pos.z + Math.sin(ang) * d,
                        -Math.cos(ang) * 0.12, 0.3 + r.nextDouble() * 0.3, -Math.sin(ang) * 0.12);
            }
            for (int i = 0; i < 4; i++) {
                Vec3 p = l((r.nextDouble() - 0.5) * 24, 0.2, (r.nextDouble() - 0.5) * 24);
                if (p.distanceTo(camera) > 5) {
                    level.addParticle(new BlockParticleOption(ParticleTypes.BLOCK, Blocks.STONE.defaultBlockState()), true, p.x, p.y, p.z, 0, 0.6 + r.nextDouble(), 0);
                }
            }
        }
    }

    // ================================================================ le dessin

    private static void dessinerMonde(PoseStack pose, Vec3 cam, MultiBufferSource.BufferSource buffers, float temps) {
        for (Acteur a : new Acteur[] {GOKU, VEGETA}) {
            if (a.visible) {
                Effets.personnage(pose, buffers, a.skin, a.pos.subtract(cam), a.lacet, a.posture, a.transparence, a.coiffure, a.cheveux, a.echelleCheveux);
            }
        }
        buffers.endBatch();
        for (Acteur a : new Acteur[] {GOKU, VEGETA}) {
            if (a.visible) {
                Effets.aura(pose, buffers, a.pos.subtract(cam), a.aura, a.couleur[0], a.couleur[1], a.couleur[2], temps);
            }
        }
        // les éclairs : autour de celui qui se transforme (de plus en plus nombreux), puis en Super Saiyan 2
        for (Acteur a : new Acteur[] {GOKU, VEGETA}) {
            if (!a.visible) {
                continue;
            }
            boolean ssj2 = a == GOKU ? reel >= SSJ2 : reel >= MAJIN;
            boolean charge = a == GOKU ? reel >= SSJ - 1.4 && reel < SSJ2 + 1 : reel >= MAJIN - 1.8 && reel < MAJIN + 1;
            if ((!ssj2 && !charge) || t >= FIN || t < INTRO) {
                continue;
            }
            int n = charge ? 4 : 1;
            long g = (long) (temps * 14) + (a == GOKU ? 0 : 977);
            for (int i = 0; i < n; i++) {
                Random rr = new Random(g * 13 + i);
                if (!charge && g % 3 == 0) {
                    continue;
                }
                Vec3 p = a.pos.subtract(cam);
                Vec3 haut = p.add((rr.nextDouble() - 0.5) * 1.6, 1.6 + rr.nextDouble() * 1.4, (rr.nextDouble() - 0.5) * 1.6);
                Vec3 bas = p.add((rr.nextDouble() - 0.5) * 1.4, rr.nextDouble() * 0.6, (rr.nextDouble() - 0.5) * 1.4);
                Effets.eclair(pose, buffers, haut, bas, g * 7 + i, charge ? 0.035 : 0.02, 0.85F, 0.95F, 1F);
            }
        }
        if (ondeAlpha > 0) {
            Effets.onde(pose, buffers, ondeCentre.subtract(cam), ondeRayon, 2.5, ondeAlpha);
        }
        for (Eclat e : ECLATS) {
            double age = (t - e.debut()) / (0.25 + e.force() * 0.08);
            if (age < 0 || age > 1) {
                continue;
            }
            Vec3 p = e.pos().subtract(cam);
            float[] c = e.couleur();
            float a = (float) (1 - age);
            Effets.sphere(pose, buffers, p, 0.2 + e.force() * 0.6 * Math.sqrt(age), 1F, 1F, 1F, a);
            Effets.sphere(pose, buffers, p, 0.4 + e.force() * 1.1 * Math.sqrt(age), c[0], c[1], c[2], a * 0.5F);
            Random rr = new Random((long) (e.debut() * 1000));
            int branches = 5 + (int) (e.force() * 2);
            for (int i = 0; i < branches; i++) {
                Vec3 d = new Vec3(rr.nextDouble() - 0.5, rr.nextDouble() - 0.5, rr.nextDouble() - 0.5).normalize();
                double longueur = e.force() * (0.8 + rr.nextDouble() * 1.2) * (0.4 + age);
                Effets.tube(pose, buffers, p, p.add(d.scale(longueur)), 0.035 * e.force() * a, c[0], c[1], c[2], a);
            }
            if (e.force() >= 1.2F) {
                Effets.onde(pose, buffers, p, e.force() * 3 * age, 0.6, a * 0.8F);
            }
        }
        buffers.endBatch();
    }

    /** Les bandes noires du cinéma, les sous-titres, les titres, les flashs, le fondu. */
    private static void dessinerEcran(GuiGraphics g) {
        Minecraft mc = Minecraft.getInstance();
        int w = g.guiWidth();
        int h = g.guiHeight();
        int bande = h / 9;
        if (flash > 0) {
            g.fill(0, 0, w, h, (Mth.clamp((int) (flash * 255), 0, 255) << 24) | couleurFlash);
        }
        g.fill(0, 0, w, bande, 0xFF000000);
        g.fill(0, h - bande, w, h, 0xFF000000);
        if (noir > 0) {
            g.fill(0, 0, w, h, (Mth.clamp((int) (noir * 255), 0, 255) << 24));
        }
        if (!titre.isEmpty()) {
            Ios.texteCentre(g, mc.font, titre, w / 2F, h / 2F - 18, 2.6F, 0xFFFFC83A, true);
            if (!sousTitre.isEmpty()) {
                Ios.texteCentre(g, mc.font, sousTitre, w / 2F, h / 2F + 14, 1F, 0xFFD0D0D0, true);
            }
        }
        if (!replique.isEmpty()) {
            int couleur = parleur.equals("Goku") ? 0xFFFF9A3A : 0xFFB98CFF;
            String nom = parleur.toUpperCase();
            var lignes = mc.font.split(net.minecraft.network.chat.Component.literal(replique), Math.min(w - 60, 420));
            int y = h - bande + (bande - lignes.size() * 10 - 10) / 2;
            g.drawCenteredString(mc.font, nom, w / 2, y, couleur);
            for (int i = 0; i < lignes.size(); i++) {
                g.drawCenteredString(mc.font, lignes.get(i), w / 2, y + 11 + i * 10, 0xFFFFFFFF);
            }
        }
    }

    // ================================================================ branchements

    @EventBusSubscriber(modid = Cabane.MODID, value = Dist.CLIENT)
    public static final class Couches {
        @SubscribeEvent
        public static void enregistrer(RegisterGuiLayersEvent event) {
            event.registerAboveAll(Cabane.id("cinema"), (g, delta) -> {
                if (actif) {
                    dessinerEcran(g);
                }
            });
        }
    }

    @EventBusSubscriber(modid = Cabane.MODID, value = Dist.CLIENT)
    public static final class Evenements {
        @SubscribeEvent
        public static void tick(ClientTickEvent.Post event) {
            Minecraft mc = Minecraft.getInstance();
            if (!actif) {
                return;
            }
            if (mc.level == null || mc.player == null) {
                arreter();
                return;
            }
            ticks++;
            calculer(ecoule());
            tPrecedent = tDernierTick;
            evenements();
            tDernierTick = t;
            ambiance();
            if (reel >= DUREE_REELLE) {
                arreter();
            } else if (mc.getCameraEntity() != objectif && objectif != null) {
                mc.setCameraEntity(objectif);
            }
        }

        /** La caméra se place à chaque image (et pas seulement à chaque tick) : les mouvements restent fluides. */
        @SubscribeEvent
        public static void image(RenderFrameEvent.Pre event) {
            if (!actif) {
                return;
            }
            calculer(ecoule());
            placerCamera();
        }

        @SubscribeEvent
        public static void dessiner(RenderLevelStageEvent event) {
            if (!actif || event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) {
                return;
            }
            Vec3 cam = event.getCamera().getPosition();
            dessinerMonde(event.getPoseStack(), cam, Minecraft.getInstance().renderBuffers().bufferSource(), (float) t);
        }

        @SubscribeEvent
        public static void champ(ViewportEvent.ComputeFov event) {
            if (actif) {
                event.setFOV(fov);
            }
        }

        @SubscribeEvent
        public static void angles(ViewportEvent.ComputeCameraAngles event) {
            if (actif && secousse > 0) {
                event.setRoll(event.getRoll() + (float) ((Math.random() - 0.5) * secousse * 3));
            }
        }

        @SubscribeEvent
        public static void immobile(MovementInputUpdateEvent event) {
            if (actif) {
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
            if (actif) {
                event.setCanceled(true);
                event.setSwingHand(false);
            }
        }

        @SubscribeEvent
        public static void pasDeMain(RenderHandEvent event) {
            if (actif) {
                event.setCanceled(true);
            }
        }

        @SubscribeEvent
        public static void pasDInterface(RenderGuiLayerEvent.Pre event) {
            if (actif && !event.getName().equals(Cabane.id("cinema"))) {
                event.setCanceled(true);
            }
        }
    }

    /** Pour l'outil de captures : jouer la cinématique sans serveur, au-dessus de soi. */
    public static void demo(double debut) {
        Minecraft mc = Minecraft.getInstance();
        demarrer(mc.player.position().add(0, 40, 0));
        ticks = (int) (debut * 20);
        debutNanos = System.nanoTime() - (long) (debut * 1e9);
        t = debut;
        tPrecedent = debut;
        tDernierTick = debut;
    }

    private ClientCinema() {
    }
}
