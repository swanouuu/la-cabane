package fr.cabane.client.operation;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import fr.cabane.Cabane;
import fr.cabane.client.cinema.Effets;
import fr.cabane.labo.Operation;
import java.util.Random;
import java.util.UUID;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.client.resources.PlayerSkin;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ItemParticleOption;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
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
import net.neoforged.neoforge.client.event.RenderLivingEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * La grande expérience (/experience), chez chaque joueur du labo. Les vrais corps des trois acteurs sont cachés : leurs
 * doubles (à leur skin) jouent la scène sur la table du milieu de la salle d'expériences. Les trois la voient en
 * cinématique, dans le noir (lumières qui vacillent, brouillard noir) ; les autres la voient de l'extérieur, dans le monde,
 * avec les mêmes bruits (bourdon, cœur qui bat). L'allongé est sanglé à la table : il ne peut que tirer
 * sur ses sangles. Le savant enfile ses gants, lui perce le ventre à la perceuse, lui injecte un liquide noir dans le cou,
 * puis invoque comme Orochimaru (il se mord le pouce, fait les signes, frappe le sol) un énorme serpent sombre qui sort de
 * la fumée, domine la table et vient le mordre au cou, longuement. La marque se répand, le cœur s'arrête… et repart.
 * Le savant défait les sangles ; l'allongé se relève et descend. Le spectateur, lui, regarde sans bouger.
 * Coordonnées : celles du labo (comme LaboBuilder), le dessus de l'estrade en y = 2.
 */
public final class ClientOperation {
    public static final double DUREE = 48.5;
    private static final double GANTS = 0, PERCEUSE_T = 4.0, PERCE = 5.6, PERCE_FIN = 12.5, SERINGUE = 14.0, PIQURE = 15.6, POUCE = 18.0, SIGNES_T = 18.7,
            SOL_T = 20.0, FUMEE = 21.0, SORT = 21.2, DOMINE = 23.6, DESCEND = 26.0, MORSURE = 27.2, LACHE = 31.0, RENTRE = 31.8, PARTI = 32.9,
            MARQUE = 33.0, PLAT = 36.5, SOUFFLE = 40.0, BOUCLES = 40.6, ASSIS = 43.5, DESCENTE = 45.2, DEBOUT = 46.2;
    /** Les cinq signes de l'invocation (sanglier, chien, oiseau, singe, bélier), un tous les quarts de seconde. */
    private static final float[][] SIGNES = {{-1.15F, -0.5F, 0, -1.15F, 0.5F, 0}, {-1.35F, -0.6F, 0.25F, -1.35F, 0.6F, -0.25F},
            {-1.0F, -0.45F, -0.3F, -1.0F, 0.45F, 0.3F}, {-1.45F, -0.55F, 0.1F, -1.25F, 0.55F, -0.1F}, {-1.6F, -0.62F, 0, -1.6F, 0.62F, 0}};

    private static final double TABLE = 2 + 11 / 16.0;
    /** La hanche est à 12 pixels du sol (×0,9375) : on penche autour d'elle (−90 : couché sur le dos). */
    private static final float HANCHE = 0.704F;
    private static final Vec3 SPEC = new Vec3(-1.1, 2, -1.5), SAV = new Vec3(1.0, 2, 1.6), SORTIE = new Vec3(-1.7, 2, 0.5);
    /** L'allongé : couché, la tête à l'est ; son ventre, sa tête, le côté de son cou (vers le savant). */
    private static final Vec3 VENTRE = new Vec3(1.05, TABLE + 0.236, 0.5), TETE = new Vec3(1.74, TABLE + 0.33, 0.5), COU = new Vec3(1.47, TABLE + 0.14, 0.72);
    /** Les trois sangles (en x) : la poitrine et les bras, les poignets, les chevilles ; et quand le savant les défait. */
    private static final double[] SANGLES = {1.25, 0.72, 0.22};
    private static final double[] DEFAITES = {41.0, 41.7, 42.4};
    /** D'où sort le serpent (au pied de la tête de la table), et le moniteur. */
    private static final Vec3 ANTRE = new Vec3(3.45, 2, 0.5), MONITEUR = new Vec3(3.5, 3.4, -0.5);
    private static final Vec3 HAUT = new Vec3(0, 1, 0), X = new Vec3(1, 0, 0), Z = new Vec3(0, 0, 1);

    private static final ResourceLocation SCEAU = Cabane.id("textures/item/sceau_rituel.png");
    private static final ResourceLocation VIGNETTE = ResourceLocation.withDefaultNamespace("textures/misc/vignette.png");

    // ---- les sons fabriqués pour l'expérience (outils/operation.mjs)
    private static SoundEvent son(String nom) {
        return SoundEvent.createVariableRangeEvent(Cabane.id("operation." + nom));
    }

    private static final SoundEvent PERCEUSE = son("perceuse"), PERCEUSE_VIDE = son("perceuse_vide"), BIP = son("bip"), BIP_LONG = son("bip_long"),
            GRESILLE = son("zap"), BOURDON = son("bourdon");

    // ---- la scène en cours
    private static boolean actif;
    private static final UUID[] acteurs = new UUID[3];
    private static Vec3 o = Vec3.ZERO;
    private static int numero;
    private static long debut;
    private static double avant;
    private static double phaseBip;

    // ---- la cinématique
    private static boolean cinema;
    private static Entity objectif;
    private static CameraType vueAvant;
    private static boolean guiAvant;
    private static float fov = 55;

    private static double t() {
        return (System.nanoTime() - debut) / 1e9;
    }

    private static double entre(double t, double a, double b) {
        return Mth.clamp((t - a) / (b - a), 0, 1);
    }

    private static double lisse(double k) {
        return k * k * (3 - 2 * k);
    }

    private static double bosse(double t, double a, double monte, double tient, double descend) {
        return entre(t, a, a + monte) * (1 - entre(t, a + monte + tient, a + monte + tient + descend));
    }

    private static float mix(float a, double b, double k) {
        return (float) (a + (b - a) * k);
    }

    /** Du labo vers le monde. */
    private static Vec3 w(Vec3 p) {
        return p.add(o);
    }

    private static Vec3 w(double x, double y, double z) {
        return new Vec3(o.x + x, o.y + y, o.z + z);
    }

    private static Vec3 dir(Vec3 v) {
        double l = v.length();
        return l < 1.0E-6 ? new Vec3(0, 1, 0) : v.scale(1 / l);
    }

    private static Vec3 tourne(Vec3 a, Vec3 b, double k) {
        return dir(a.lerp(b, k));
    }

    /** Le lacet de Minecraft (en degrés) pour regarder de « de » vers « vers ». */
    private static float lacet(Vec3 de, Vec3 vers) {
        return (float) Math.toDegrees(Math.atan2(-(vers.x - de.x), vers.z - de.z));
    }

    /** Un bruit déterministe entre 0 et 1 (pour que le vacillement des lampes ne dépende pas des images). */
    private static double hasard(long n) {
        long x = n * 0x9E3779B97F4A7C15L;
        x ^= x >>> 31;
        x *= 0xBF58476D1CE4E5B9L;
        x ^= x >>> 29;
        return (x >>> 11) / (double) (1L << 53);
    }

    // ================================================================ départ, arrêt

    public static void demarrer(Operation.Debut d) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || d.acteurs().size() < 3) {
            return;
        }
        if (cinema) {
            sortir();
        }
        for (int i = 0; i < 3; i++) {
            acteurs[i] = d.acteurs().get(i);
        }
        BlockPos b = d.origine();
        o = new Vec3(b.getX(), b.getY(), b.getZ());
        numero = d.numero();
        debut = System.nanoTime();
        avant = 0;
        phaseBip = 0;
        actif = true;
        if (d.acteurs().contains(mc.player.getUUID())) {
            entrer();
        }
    }

    private static void arreter() {
        actif = false;
        if (cinema) {
            sortir();
        }
    }

    private static boolean cache(Entity e) {
        if (!actif || !(e instanceof Player p)) {
            return false;
        }
        for (UUID u : acteurs) {
            if (p.getUUID().equals(u)) {
                return true;
            }
        }
        return false;
    }

    private static void entrer() {
        Minecraft mc = Minecraft.getInstance();
        cinema = true;
        mc.setScreen(null);
        guiAvant = mc.options.hideGui;
        mc.options.hideGui = true;
        vueAvant = mc.options.getCameraType();
        mc.options.setCameraType(CameraType.FIRST_PERSON);
        objectif = EntityType.MARKER.create(mc.level);
        placerCamera(t());
        mc.setCameraEntity(objectif);
    }

    private static void sortir() {
        Minecraft mc = Minecraft.getInstance();
        cinema = false;
        mc.options.hideGui = guiAvant;
        if (vueAvant != null) {
            mc.options.setCameraType(vueAvant);
        }
        if (mc.player != null) {
            mc.setCameraEntity(mc.player);
        }
        objectif = null;
    }

    // ================================================================ les corps

    /** Un personnage : où sont ses pieds, son lacet, sa posture (radians ; penche en degrés autour de la hanche), sa teinte. */
    private static final class Fig {
        Vec3 pieds = Vec3.ZERO;
        float lacet, penche, teteX, teteY;
        float bdX, bdY, bdZ = 0.08F, bgX, bgY, bgZ = -0.08F, jdX, jdZ = 0.03F, jgX, jgZ = -0.03F;
        int couleur = 0xFFFFFFFF;

        /** Les angles d'une autre posture, mélangés à ceux-ci (la position reste la sienne). */
        void vers(Fig b, double k) {
            penche = mix(penche, b.penche, k);
            teteX = mix(teteX, b.teteX, k);
            teteY = mix(teteY, b.teteY, k);
            bdX = mix(bdX, b.bdX, k);
            bdY = mix(bdY, b.bdY, k);
            bdZ = mix(bdZ, b.bdZ, k);
            bgX = mix(bgX, b.bgX, k);
            bgY = mix(bgY, b.bgY, k);
            bgZ = mix(bgZ, b.bgZ, k);
            jdX = mix(jdX, b.jdX, k);
            jdZ = mix(jdZ, b.jdZ, k);
            jgX = mix(jgX, b.jgX, k);
            jgZ = mix(jgZ, b.jgZ, k);
        }
    }

    private static Matrix4f racine(Fig f) {
        return new Matrix4f().translate((float) f.pieds.x, (float) f.pieds.y, (float) f.pieds.z).rotateY((float) Math.toRadians(180 - f.lacet))
                .translate(0, HANCHE, 0).rotateX((float) Math.toRadians(-f.penche)).translate(0, -HANCHE, 0)
                .scale(-0.9375F, -0.9375F, 0.9375F).translate(0, -1.501F, 0);
    }

    /** Pareil que {@link #racine}, sur la pile de dessin (pour que les normales restent justes). */
    private static void appliquer(PoseStack pose, Fig f) {
        pose.translate(f.pieds.x, f.pieds.y, f.pieds.z);
        pose.mulPose(Axis.YP.rotationDegrees(180F - f.lacet));
        pose.translate(0, HANCHE, 0);
        pose.mulPose(Axis.XP.rotationDegrees(-f.penche));
        pose.translate(0, -HANCHE, 0);
        pose.scale(-0.9375F, -0.9375F, 0.9375F);
        pose.translate(0, -1.501, 0);
    }

    private static final Vector3f EPAULE_D = new Vector3f(-5 / 16F, 2 / 16F, 0), EPAULE_G = new Vector3f(5 / 16F, 2 / 16F, 0);

    private static Vector3f local(Fig f, Vec3 monde) {
        return racine(f).invert().transformPosition(new Vector3f((float) monde.x, (float) monde.y, (float) monde.z));
    }

    /** Tourne un bras pour qu'il pointe vers un point du monde. */
    private static void viser(Fig f, boolean droit, Vec3 cible) {
        Vector3f q = local(f, cible).sub(droit ? EPAULE_D : EPAULE_G).normalize();
        float x = (float) -Math.acos(Mth.clamp(q.y, -1, 1));
        float s = Mth.sin(x);
        float y = Math.abs(s) < 1.0E-3F ? 0 : (float) Math.atan2(q.x / s, q.z / s);
        if (droit) {
            f.bdX = x;
            f.bdY = y;
            f.bdZ = 0;
        } else {
            f.bgX = x;
            f.bgY = y;
            f.bgZ = 0;
        }
    }

    private static void bras(Fig f, Vec3 droite, Vec3 gauche) {
        if (droite != null) {
            viser(f, true, droite);
        }
        if (gauche != null) {
            viser(f, false, gauche);
        }
    }

    /** Tourne la tête vers un point (sans dépasser ce qu'un cou peut faire). */
    private static void regarder(Fig f, Vec3 cible, double k) {
        Vector3f q = local(f, cible).add(0, 4 / 16F, 0).normalize();
        float x = (float) Mth.clamp(Math.asin(Mth.clamp(q.y, -1, 1)), -0.9, 1.0);
        float y = (float) Mth.clamp(Math.atan2(-q.x, -q.z), -1.2, 1.2);
        f.teteX = mix(f.teteX, x, k);
        f.teteY = mix(f.teteY, y, k);
    }

    /** La main (droite ou gauche) dans le monde. */
    private static Vec3 main(Fig f, boolean droit) {
        Matrix4f m = racine(f).translate(droit ? EPAULE_D : EPAULE_G)
                .rotateZYX(droit ? f.bdZ : f.bgZ, droit ? f.bdY : f.bgY, droit ? f.bdX : f.bgX);
        Vector3f p = m.transformPosition(new Vector3f(droit ? -1 / 16F : 1 / 16F, 10 / 16F, 0));
        return new Vec3(p.x, p.y, p.z);
    }

    /** La bouche, dans le monde (sans compter les mouvements de tête). */
    private static Vec3 bouche(Fig f) {
        Vector3f p = racine(f).transformPosition(new Vector3f(0, -2 / 16F, -4.5F / 16));
        return new Vec3(p.x, p.y, p.z);
    }

    /** Un point devant soi (avant), à une hauteur au-dessus des pieds, et sur le côté (vers sa droite). */
    private static Vec3 devant(Fig f, double avant, double haut, double cote) {
        double r = Math.toRadians(f.lacet);
        return f.pieds.add(-Math.sin(r) * avant - Math.cos(r) * cote, haut, Math.cos(r) * avant - Math.sin(r) * cote);
    }

    /** Les mains dans le dos. */
    private static void dansLeDos(Fig f, boolean droite, boolean gauche) {
        bras(f, droite ? devant(f, -0.35, 0.85, 0.06) : null, gauche ? devant(f, -0.35, 0.85, -0.06) : null);
    }

    private static int argb(double r, double g, double b) {
        return 0xFF000000 | ((int) (Mth.clamp(r, 0, 1) * 255) << 16) | ((int) (Mth.clamp(g, 0, 1) * 255) << 8) | (int) (Mth.clamp(b, 0, 1) * 255);
    }

    /** Fait passer en douceur d'une phase à la suivante : les angles de la fin de la précédente, vers ceux de celle-ci. */
    private interface Brut {
        Fig a(double t, int phase);
    }

    private static Fig enchainer(double[][] phases, double t, Brut brut) {
        int i = 0;
        while (i + 1 < phases.length && t >= phases[i + 1][0]) {
            i++;
        }
        Fig f = brut.a(t, i);
        double k = i == 0 ? 1 : lisse(entre(t, phases[i][0], phases[i][0] + phases[i][1]));
        if (k < 1) {
            Fig a = brut.a(phases[i][0] - 0.001, i - 1);
            a.vers(f, k);
            f.vers(a, 1);
        }
        return f;
    }

    // ================================================================ le spectateur : il regarde, sans bouger

    private static Fig spectateur(double t) {
        Fig f = new Fig();
        f.pieds = w(SPEC);
        f.lacet = lacet(SPEC, new Vec3(1.0, 2, 0.5));
        // il se penche à peine quand le serpent mord
        f.penche = (float) (1.5 * Math.sin(t * 1.3) + 6 * bosse(t, MORSURE, 0.6, 3.0, 1.0));
        dansLeDos(f, true, true);
        regarder(f, focus(t), 1);
        return f;
    }

    /** Ce que regardent ceux qui regardent. */
    private static Vec3 focus(double t) {
        if (t < PERCE) {
            return t < 3 ? w(SAV.add(0, 1.4, 0)) : w(TETE);
        }
        if (t < PERCE_FIN) {
            return w(VENTRE);
        }
        if (t < SERINGUE) {
            return w(TETE);
        }
        if (t < POUCE) {
            return w(COU);
        }
        if (t < FUMEE) {
            return t < SOL_T ? w(1.75, 3.3, 1.75) : w(ANTRE);
        }
        if (t < PARTI) {
            Tete h = serpent(t);
            return h != null ? w(h.p()) : w(ANTRE.add(0, 1, 0));
        }
        if (t < ASSIS) {
            return w(TETE);
        }
        return t < DESCENTE ? w(TETE.add(-0.9, 0.6, 0)) : w(SORTIE.add(0, 1.5, 0));
    }

    // ================================================================ le savant

    private enum Outil { AUCUN, PERCEUSE, SERINGUE }

    /** Le savant : son corps, l'outil qu'il tient et où en est le bout (la mèche, l'aiguille), et le piston de la seringue. */
    private static final class Savant {
        Fig f;
        Outil outil = Outil.AUCUN;
        Vec3 bout;
        double piston;
    }

    private record Clef(double debut, double fin, Vec3 p, float lacet) {
    }

    private static final Clef[] PAS = {new Clef(5.2, PERCE, new Vec3(1.05, 2, 1.55), 180), new Clef(14.6, 15.2, new Vec3(1.45, 2, 1.6), 180),
            new Clef(17.2, POUCE, new Vec3(1.75, 2, 1.75), lacet(new Vec3(1.75, 2, 1.75), ANTRE)),
            new Clef(21.6, 22.4, new Vec3(1.2, 2, 1.95), lacet(new Vec3(1.2, 2, 1.95), new Vec3(2.2, 2, 0.5))),
            new Clef(36.6, 37.2, new Vec3(1.4, 2, 1.55), 180), new Clef(BOUCLES, 41.0, new Vec3(1.25, 2, 1.55), 180),
            new Clef(41.25, 41.55, new Vec3(0.8, 2, 1.55), 180), new Clef(41.95, 42.25, new Vec3(0.3, 2, 1.55), 180),
            new Clef(43.3, 44.0, new Vec3(1.0, 2, 1.85), lacet(new Vec3(1.0, 2, 1.85), SORTIE))};

    /** Les moments où il change de geste, et en combien de temps il passe au suivant. */
    private static final double[][] PHASES = {{GANTS, 0}, {PERCEUSE_T, 0.3}, {PERCE, 0.25}, {PERCE_FIN, 0.3}, {SERINGUE, 0.3}, {15.2, 0.2},
            {PIQURE, 0.1}, {17.0, 0.3}, {POUCE, 0.25}, {SIGNES_T, 0.12}, {SOL_T, 0.18}, {21.3, 0.4}, {25.8, 0.3}, {26.4, 0.4}, {PARTI, 0.4},
            {36.6, 0.5}, {SOUFFLE, 0.3}, {BOUCLES, 0.3}, {43.3, 0.4}};

    private static Savant savant(double t) {
        Savant[] s = new Savant[1];
        Fig f = enchainer(PHASES, t, (tt, i) -> {
            Savant r = savantBrut(tt, i);
            if (tt == t) {
                s[0] = r;
            }
            return r.f;
        });
        s[0].f = f;
        return s[0];
    }

    private static Vec3 pas(double t, float[] lac) {
        Vec3 p = SAV;
        float l = 180;
        for (Clef c : PAS) {
            if (t < c.debut()) {
                break;
            }
            double k = lisse(entre(t, c.debut(), c.fin()));
            p = p.lerp(c.p(), k);
            l += Mth.wrapDegrees(c.lacet() - l) * (float) k;
        }
        lac[0] = l;
        return p;
    }

    /** Le côté d'une sangle, là où est la boucle (vers le savant). */
    private static Vec3 boucle(int i) {
        return new Vec3(SANGLES[i], TABLE + 0.27, 0.9);
    }

    private static Savant savantBrut(double t, int phase) {
        Savant s = new Savant();
        Fig f = new Fig();
        s.f = f;
        float[] lac = new float[1];
        Vec3 p = pas(t, lac);
        f.lacet = lac[0];
        if (t >= PERCE && t < PERCE_FIN) {
            p = p.add(0.012 * Math.sin(t * 97), 0, 0.012 * Math.sin(t * 83));
        }
        f.pieds = w(p);
        Vec3 d = null, g = null, regard = focus(t);
        boolean dos = false;
        double ph = PHASES[phase][0];
        if (ph < PERCEUSE_T) {
            // il enfile ses gants, lentement
            double tir = bosse(t, 0.9, 0.2, 0.15, 0.35) + bosse(t, 1.8, 0.2, 0.15, 0.35);
            d = devant(f, 0.38, 1.12, 0.04 + 0.14 * tir);
            g = devant(f, 0.38, 1.08, -0.04);
            regard = t < 2.6 ? devant(f, 0.4, 1.1, 0) : w(TETE);
        } else if (ph < PERCE) {
            // il prend la perceuse et la fait tourner une fois
            f.penche = 2;
            s.outil = Outil.PERCEUSE;
            double v = bosse(t, 4.6, 0.05, 0.4, 0.15);
            s.bout = devant(f, 0.75, 1.95, 0.08).add(0.015 * v * Math.sin(t * 90), 0.015 * v * Math.sin(t * 77), 0);
            regard = w(TETE);
        } else if (ph < PERCE_FIN) {
            // il perce
            f.penche = (float) (22 + 2 * Math.sin(t * 40));
            s.outil = Outil.PERCEUSE;
            s.bout = w(VENTRE).add(0.012 * Math.sin(t * 71), -0.03 - 0.07 * entre(t, PERCE, 7.5) + 0.012 * Math.sin(t * 89), 0.012 * Math.sin(t * 61));
            regard = s.bout;
        } else if (ph < SERINGUE) {
            f.penche = 5;
            s.outil = t < 13.3 ? Outil.PERCEUSE : Outil.AUCUN;
            s.bout = w(VENTRE).lerp(devant(f, 0.5, 1.9, 0.4), lisse(entre(t, PERCE_FIN, 13.3)));
            g = devant(f, 0.3, 1.0, -0.2);
            regard = w(TETE);
        } else if (ph < POUCE) {
            // la seringue de liquide noir : levée, tapotée, plantée dans le cou, vidée
            s.outil = Outil.SERINGUE;
            Vec3 levee = devant(f, 0.5, 2.45, 0.05), cou = w(COU).add(0, 0.04, 0.02), rangee = devant(f, 0.5, 1.9, 0.4);
            if (ph < 15.2) {
                f.penche = -2;
                s.bout = levee;
                g = devant(f, 0.45, 1.8, -0.05 + 0.06 * Math.max(0, Math.sin(t * 18)));
            } else if (ph < PIQURE) {
                f.penche = (float) (25 * lisse(entre(t, 15.2, PIQURE)));
                s.bout = levee.lerp(cou, lisse(entre(t, 15.2, PIQURE)));
            } else if (ph < 17.0) {
                f.penche = 25;
                s.bout = cou;
                s.piston = entre(t, 15.8, 16.8);
            } else {
                f.penche = (float) (25 * (1 - entre(t, 17.0, 17.5)));
                s.bout = cou.lerp(rangee, lisse(entre(t, 17.0, 17.5)));
                s.piston = 1;
                if (t >= 17.5) {
                    s.outil = Outil.AUCUN;
                }
            }
            regard = s.outil == Outil.AUCUN ? w(TETE) : s.bout;
        } else if (ph < SIGNES_T) {
            // il se mord le pouce
            d = bouche(f);
            regard = null;
            f.teteX = 0.25F;
        } else if (ph < SOL_T) {
            // les cinq signes de l'invocation
            int i = Mth.clamp((int) ((t - 18.8) / 0.25), 0, 4);
            float[] a = SIGNES[i], b0 = i == 0 ? SIGNES[0] : SIGNES[i - 1];
            float k = (float) lisse(entre(t, 18.8 + i * 0.25, 18.8 + i * 0.25 + 0.08));
            f.penche = 3;
            f.bdX = mix(b0[0], a[0], k);
            f.bdY = mix(b0[1], a[1], k);
            f.bdZ = mix(b0[2], a[2], k);
            f.bgX = mix(b0[3], a[3], k);
            f.bgY = mix(b0[4], a[4], k);
            f.bgZ = mix(b0[5], a[5], k);
            regard = devant(f, 2, 1.2, 0);
        } else if (ph < 21.3) {
            // il frappe le sol de sa paume : le sceau se répand jusqu'à la tête de la table
            f.penche = (float) (55 * lisse(entre(t, SOL_T, SOL_T + 0.25)));
            d = devant(f, 0.62, 0.02, 0.12);
            g = devant(f, 0.3, 0.9, -0.25);
            regard = w(ANTRE);
        } else if (ph < 25.8) {
            // debout, une main levée devant lui (deux doigts), l'autre dans le dos : il tient son serpent
            d = devant(f, 0.42, 1.45, 0.02);
            dos = true;
        } else if (ph < 26.4) {
            // il désigne l'allongé
            d = w(COU);
            dos = true;
        } else if (ph < PARTI) {
            // les bras croisés
            d = devant(f, 0.32, 1.0, -0.2);
            g = devant(f, 0.32, 1.05, 0.2);
        } else if (ph < 36.6) {
            f.penche = 8;
            dos = true;
            regard = w(TETE);
        } else if (ph < SOUFFLE) {
            // penché sur lui, les mains sur le bord de la table
            f.penche = 30;
            d = w(1.6, TABLE + 0.02, 1.0);
            g = w(1.0, TABLE + 0.02, 1.0);
            regard = w(TETE);
        } else if (ph < BOUCLES) {
            dos = true;
            regard = w(TETE);
        } else if (ph < 43.3) {
            // il défait les sangles, une à une
            f.penche = 28;
            int i = t < 41.25 ? 0 : t < 41.95 ? 1 : 2;
            d = w(boucle(i)).add(0, 0.04 * Math.max(0, Math.sin((t - DEFAITES[i]) * 20)), 0);
            g = w(SANGLES[i] + 0.25, TABLE + 0.02, 1.0);
            regard = d;
        } else {
            dos = true;
        }
        if (s.outil != Outil.AUCUN) {
            viser(f, true, s.bout);
            if (g == null) {
                // l'autre main : sur la poignée avant, ou sur le piston de la seringue
                Vec3 h = main(f, true);
                g = s.outil == Outil.SERINGUE ? h.add(dir(h.subtract(s.bout)).scale(0.32 + 0.22 * (1 - s.piston))) : h.lerp(s.bout, 0.35);
            }
            bras(f, null, g);
        } else {
            bras(f, d, g);
            if (dos) {
                dansLeDos(f, d == null, g == null);
            }
        }
        if (regard != null) {
            regarder(f, regard, 0.85);
        }
        return s;
    }

    // ================================================================ l'allongé, sanglé à la table

    /** La marque qui le noircit : un peu après l'injection, plus après la morsure, et qui bat avec son cœur. */
    private static double voile(double t) {
        double v = 0.3 * entre(t, 16.0, 18.0);
        if (t >= MORSURE) {
            v = Math.max(v, 0.3 + 0.2 * entre(t, MORSURE + 0.2, LACHE));
        }
        if (t >= MARQUE && t < PLAT) {
            v = 0.55 + 0.25 * battement(t) + 0.2 * entre(t, MARQUE, PLAT);
        } else if (t >= PLAT) {
            v = 0.8 * (1 - entre(t, 40.5, 47.5));
        }
        return v;
    }

    /** Les battements du cœur pendant que la marque se répand (de plus en plus vite). */
    private static double battement(double t) {
        if (t < MARQUE || t >= PLAT) {
            return 0;
        }
        double periode = 0.62 - 0.25 * entre(t, MARQUE, PLAT), ph = ((t - MARQUE) / periode) % 1;
        return ph < 0.22 ? 1 - ph / 0.22 : 0;
    }

    private static final double[][] PHASES_A = {{0, 0}, {PERCEUSE_T, 0.3}, {PERCE, 0.2}, {PERCE_FIN, 0.4}, {SERINGUE, 0.4}, {15.2, 0.3},
            {16.0, 0.2}, {POUCE, 0.5}, {SORT, 0.3}, {MORSURE, 0.08}, {LACHE, 0.5}, {MARQUE, 0.3}, {PLAT, 0.6}, {SOUFFLE, 0.06}, {BOUCLES, 0.4},
            {ASSIS, 0.05}, {DESCENTE, 0.1}, {DEBOUT, 0.4}};

    private static Fig allonge(double t) {
        return enchainer(PHASES_A, t, ClientOperation::allongeBrut);
    }

    /** Il tire sur ses sangles : les poings, les jambes, la tête, à peine plus (il est attaché). */
    private static void tirer(Fig f, double t, double k) {
        f.bdX = mix(f.bdX, 0.12 * Math.sin(t * 19), k);
        f.bgX = mix(f.bgX, 0.12 * Math.sin(t * 17 + 1), k);
        f.bdZ = mix(f.bdZ, 0.08 + 0.05 * Math.sin(t * 23), k);
        f.bgZ = mix(f.bgZ, -0.08 - 0.05 * Math.sin(t * 21), k);
        f.jdX = mix(f.jdX, -0.06 + 0.06 * Math.sin(t * 15), k);
        f.jgX = mix(f.jgX, -0.06 + 0.06 * Math.sin(t * 13 + 2), k);
        f.teteY = mix(f.teteY, 0.7 * Math.sin(t * 11), k);
        f.teteX = mix(f.teteX, -0.35 + 0.25 * Math.sin(t * 7), k);
        f.penche = mix(f.penche, -90 + 3 * Math.sin(t * 13), k);
    }

    private static Fig allongeBrut(double t, int phase) {
        Fig f = new Fig();
        f.lacet = 90;
        f.penche = -90;
        double ph = PHASES_A[phase][0];
        // il se cambre contre les sangles quand il souffre
        double cambre = ph >= PERCE && ph < PERCE_FIN ? 0.03 * Math.abs(Math.sin(t * 17))
                : ph >= MORSURE && ph < LACHE ? 0.04 * Math.abs(Math.sin(t * 13)) : ph >= MARQUE && ph < PLAT ? 0.05 * battement(t)
                : ph >= SOUFFLE && ph < BOUCLES ? 0.08 * bosse(t, SOUFFLE, 0.06, 0.2, 0.3) : 0.008 * Math.sin(t * 5);
        Vec3 hanche = new Vec3(0.803, TABLE + 0.118 + cambre, 0.5);
        f.pieds = w(hanche.subtract(0, HANCHE, 0));
        if (ph < PERCEUSE_T) {
            // il respire fort, la tête tournée vers le savant, puis vers le plafond
            f.teteY = (float) (0.4 * Math.sin(t * 0.9));
            f.bdX = (float) (0.04 * Math.sin(t * 30));
        } else if (ph < PERCE) {
            regarder(f, devant(savantBrut(t, 1).f, 0.75, 1.95, 0.08), 1);
            tirer(f, t, 0.4 * bosse(t, 4.8, 0.1, 0.4, 0.3));
        } else if (ph < PERCE_FIN) {
            tirer(f, t, 1);
        } else if (ph < SERINGUE) {
            // il halète, la tête tournée de côté
            regarder(f, w(SPEC.add(0, 1.6, 0)), 0.8);
        } else if (ph < 15.2) {
            regarder(f, w(SAV.add(0.45, 2.3, 0)), 1);
        } else if (ph < 16.0) {
            // il détourne la tête : le cou est offert à l'aiguille
            regarder(f, w(1.6, TABLE + 0.3, -1.0), 1);
            tirer(f, t, 0.3);
        } else if (ph < POUCE) {
            // le liquide noir : il convulse
            tirer(f, t, 0.9 * (1 - entre(t, 17.3, 18.0)));
        } else if (ph < SORT) {
            regarder(f, w(1.75, 3.3, 1.75), 0.8);
        } else if (ph < MORSURE) {
            // il voit le serpent et tire de toutes ses forces
            Tete h = serpent(t);
            regarder(f, h != null ? w(h.p()) : w(ANTRE.add(0, 1, 0)), 1);
            double k = 0.35 + 0.4 * entre(t, DESCEND, MORSURE);
            f.bdX = mix(f.bdX, 0.12 * Math.sin(t * 19), k);
            f.bgX = mix(f.bgX, 0.12 * Math.sin(t * 17 + 1), k);
            f.jdX = mix(f.jdX, -0.06 + 0.06 * Math.sin(t * 15), k);
            f.jgX = mix(f.jgX, -0.06 + 0.06 * Math.sin(t * 13 + 2), k);
        } else if (ph < LACHE) {
            // mordu : il hurle, se débat, puis faiblit
            tirer(f, t, 1 - 0.7 * entre(t, 29.0, LACHE));
            f.teteY = mix(f.teteY, -0.8, 0.6);
        } else if (ph < MARQUE) {
            f.teteY = -0.8F;
            f.bdX = (float) (0.05 * bosse(t, 32.0, 0.05, 0.1, 0.2));
        } else if (ph < PLAT) {
            // la marque se répand : il convulse à chaque battement
            tirer(f, t, 0.25 + 0.75 * battement(t));
        } else if (ph < SOUFFLE) {
            // plus rien
            f.teteY = 0.7F;
        } else if (ph < BOUCLES) {
            // il reprend son souffle d'un coup, cambré, la tête rejetée en arrière
            f.teteX = -0.6F;
            f.bdZ = 0.25F;
            f.bgZ = -0.25F;
        } else if (ph < ASSIS) {
            regarder(f, w(SAV.add(-0.3, 1.5, 0)), 0.7);
            f.teteX += (float) (0.05 * Math.sin(t * 6));
        } else if (ph < DESCENTE) {
            // il se redresse, lentement
            double k = lisse(entre(t, ASSIS, ASSIS + 1.0));
            f.penche = (float) (-90 * (1 - k));
            f.jdX = f.jgX = (float) (-Math.PI / 2 * k);
            f.bdX = f.bgX = (float) (-0.35 * k);
            regarder(f, f.pieds.add(-2, 1.4, 0), k);
            f.teteX += (float) (0.4 * k);
        } else if (ph < DEBOUT) {
            // il glisse au bout de la table et pose les pieds par terre
            f.penche = 0;
            double glisse = lisse(entre(t, DESCENTE, 45.7)), pied = entre(t, 45.7, DEBOUT);
            Vec3 assis = new Vec3(0.803 - 0.65 * glisse, TABLE + 0.118, 0.5).subtract(0, HANCHE, 0);
            f.pieds = w(assis.lerp(SORTIE, lisse(pied)).add(0, 0.12 * Math.sin(Math.PI * pied), 0));
            f.jdX = f.jgX = (float) (-Math.PI / 2 * (1 - entre(pied, 0, 0.8)));
            f.bdX = f.bgX = -0.35F;
            f.teteX = 0.4F;
        } else {
            // debout, tête basse : il regarde ses mains
            f.penche = 0;
            f.pieds = w(SORTIE);
            double k = bosse(t, 46.6, 0.5, 1.0, 0.4);
            f.bdX = f.bgX = (float) (-0.7 * k);
            f.bdY = (float) (-0.2 * k);
            f.bgY = (float) (0.2 * k);
            f.teteX = (float) (0.35 + 0.25 * k);
        }
        double v = voile(t);
        f.couleur = argb(1 - 0.7 * v, 1 - 0.85 * v, 1 - 0.6 * v);
        return f;
    }

    // ================================================================ le serpent

    /** La tête du serpent (coordonnées du labo) : où elle est, où elle regarde, la gueule ouverte (radians), la langue. */
    private record Tete(Vec3 p, Vec3 f, double gueule, double langue) {
    }

    /** Il est énorme : sa tête est dessinée à cette échelle, son corps est gros comme un tronc. */
    private static final double K = 2.0;
    private static final Vec3 DOMINE_P = ANTRE.add(-0.25, 4.0, 0);
    private static final Vec3 APPROCHE = COU.add(0.8, 1.05, 0.35);

    private static double langue(double t) {
        double ph = (t * 0.9) % 1;
        return ph < 0.22 ? Math.sin(ph / 0.22 * Math.PI) : 0;
    }

    private static Vec3 domine(double t) {
        return DOMINE_P.add(0, 0.12 * Math.sin(t * 1.6), 0.28 * Math.sin(t * 1.1));
    }

    private static Vec3 versTete(Vec3 p) {
        return dir(TETE.add(0, 0.5, 0).subtract(p));
    }

    private static Tete serpent(double t) {
        if (t < SORT || t >= PARTI) {
            return null;
        }
        Vec3 fm = dir(COU.subtract(APPROCHE));
        Vec3 haut = dir(HAUT.subtract(fm.scale(fm.y)));
        Vec3 mord = COU.subtract(fm.scale(0.36 * K)).add(haut.scale(0.11 * K));
        if (t < DOMINE) {
            // il sort de la fumée en se dressant
            double u = entre(t, SORT, DOMINE);
            Vec3 p = ANTRE.add(-0.25 * u, 0.2 + 3.8 * lisse(u), 0.3 * Math.sin(Math.PI * u)).lerp(domine(t), entre(t, 23.0, DOMINE));
            return new Tete(p, tourne(HAUT, versTete(p), lisse(entre(t, 22.4, DOMINE))), 0, langue(t));
        }
        if (t < DESCEND) {
            // il domine la table, se balance lentement, siffle
            Vec3 p = domine(t);
            return new Tete(p, versTete(p), 0.5 * bosse(t, 24.8, 0.25, 0.4, 0.3), langue(t));
        }
        if (t < MORSURE) {
            // il descend, lentement, la gueule qui s'ouvre
            Vec3 a = domine(DESCEND);
            double k = lisse(entre(t, DESCEND, 27.0));
            Vec3 p = a.lerp(APPROCHE, k);
            return new Tete(p, tourne(versTete(a), fm, k), 1.15 * entre(t, 26.5, 27.1), 0);
        }
        if (t < 27.42) {
            double u = entre(t, MORSURE, 27.42);
            return new Tete(APPROCHE.lerp(mord, u * u), fm, 1.15 * (1 - entre(t, 27.36, 27.42)) + 0.2 * entre(t, 27.36, 27.42), 0);
        }
        Vec3 cote = dir(fm.cross(HAUT));
        if (t < LACHE) {
            // il ne lâche pas : il serre, secoue et mâche
            Vec3 p = mord.add(cote.scale(0.05 * Math.sin(t * 2 * Math.PI * 5))).add(0, 0.04 * Math.sin(t * 2 * Math.PI * 3), 0)
                    .add(fm.scale(-0.06 * Math.sin(t * 2 * Math.PI * 1.2)));
            return new Tete(p, dir(fm.add(cote.scale(0.08 * Math.sin(t * 2 * Math.PI * 5)))), 0.2 + 0.12 * Math.max(0, Math.sin(t * 2 * Math.PI * 2.5)), 0);
        }
        Vec3 leve = APPROCHE.add(0.4, 0.6, 0);
        if (t < RENTRE) {
            double k = lisse(entre(t, LACHE, RENTRE));
            return new Tete(mord.lerp(leve, k), tourne(fm, versTete(leve), k), 0.55 * bosse(t, LACHE, 0.1, 0.3, 0.3), langue(t));
        }
        // et il redescend dans la fumée, d'où il est venu
        double u = entre(t, RENTRE, PARTI);
        return new Tete(leve.lerp(ANTRE.add(0, -0.4, 0), Math.pow(u, 1.5)), tourne(versTete(leve), new Vec3(0, -1, 0), lisse(entre(t, RENTRE, RENTRE + 0.4))),
                0, 0);
    }

    private static Vec3 bezier(Vec3 a, Vec3 b, Vec3 c, Vec3 d, double u) {
        double v = 1 - u;
        return a.scale(v * v * v).add(b.scale(3 * v * v * u)).add(c.scale(3 * v * u * u)).add(d.scale(u * u * u));
    }

    /** Le corps : une courbe de la nuque jusque sous le sol, en anneaux d'écailles sombres, violet-noir. */
    private static void serpentCorps(VertexConsumer c, Matrix4f m, Vec3 cam, Tete h, double t) {
        Vec3 nuque = h.p().subtract(h.f().scale(0.3 * K));
        Vec3 fond = ANTRE.add(0, -0.9, 0);
        double l = nuque.distanceTo(fond);
        Vec3 c1 = nuque.subtract(h.f().scale(l * 0.4)).add(0, 0, 0.25 * Math.sin(t * 1.1 + 1));
        Vec3 c2 = fond.add(0, l * 0.65, 0);
        int n = 64;
        Vec3[] pts = new Vec3[n + 1];
        double[] lg = new double[n + 1];
        for (int i = 0; i <= n; i++) {
            pts[i] = bezier(nuque, c1, c2, fond, i / (double) n);
            if (i > 0) {
                lg[i] = lg[i - 1] + pts[i].distanceTo(pts[i - 1]);
            }
        }
        int k = 0;
        for (double s = 0; s <= lg[n]; s += 0.13, k++) {
            int j = 1;
            while (j < n && lg[j] < s) {
                j++;
            }
            double a = (s - lg[j - 1]) / Math.max(1.0E-6, lg[j] - lg[j - 1]);
            Vec3 p = pts[j - 1].lerp(pts[j], a);
            Vec3 d = dir(pts[j - 1].subtract(pts[j]));
            Vec3 u = HAUT.subtract(d.scale(d.y));
            u = u.lengthSqr() < 0.04 ? dir(X.subtract(d.scale(d.x))) : dir(u);
            Vec3 nn = d.cross(u);
            double r = 0.28 + 0.27 * Math.min(1, s / 1.0);
            int b = k % 6;
            float[] col = b <= 1 ? new float[] {0.09F, 0.07F, 0.11F} : new float[] {0.2F, 0.15F, 0.24F};
            Vec3 q = w(p).subtract(cam);
            boite(c, m, q, d, u, nn, 0.09, r, r, col[0], col[1], col[2]);
            boite(c, m, q, d, dir(u.add(nn)), dir(u.subtract(nn)), 0.09, r * 0.97, r * 0.97, col[0], col[1], col[2]);
            if (b == 3) {
                boite(c, m, q.add(u.scale(r * 0.93)), d, u, nn, 0.07, 0.04, r * 0.45, 0.33F, 0.26F, 0.38F);
            }
        }
    }

    /** La base d'une tête : sa direction, son dessus (au plus près du haut du monde), son côté. */
    private static Vec3[] repere(Vec3 f) {
        Vec3 u = HAUT.subtract(f.scale(f.y));
        u = u.lengthSqr() < 0.04 ? dir(X.subtract(f.scale(f.x))) : dir(u);
        return new Vec3[] {f, u, f.cross(u)};
    }

    /** La tête : le crâne, les arcades, le museau, la mâchoire, la gueule rouge, les crocs, les yeux jaunes, la langue. */
    private static void serpentTete(VertexConsumer c, Matrix4f m, Vec3 cam, Tete h) {
        Vec3[] r = repere(h.f());
        Vec3 f = r[0], u = r[1], s = r[2];
        Vec3 base = w(h.p()).subtract(cam);
        float[] peau = {0.17F, 0.13F, 0.21F};
        boite(c, m, pt(base, r, 0, 0, 0.05), f, s, u, 0.24 * K, 0.19 * K, 0.09 * K, peau[0], peau[1], peau[2]);
        boite(c, m, pt(base, r, 0.27, 0, 0.03), f, s, u, 0.11 * K, 0.13 * K, 0.07 * K, peau[0], peau[1], peau[2]);
        for (int i = -1; i <= 1; i += 2) {
            // les arcades, qui lui donnent l'air mauvais
            boite(c, m, pt(base, r, 0.1, i * 0.16, 0.15), f, s, u, 0.11 * K, 0.05 * K, 0.025 * K, 0.07F, 0.05F, 0.09F);
            boite(c, m, pt(base, r, 0.36, i * 0.06, 0.07), f, s, u, 0.012 * K, 0.015 * K, 0.012 * K, 0.03F, 0.02F, 0.03F);
        }
        double a = h.gueule();
        Vec3 fj = f.scale(Math.cos(a)).subtract(u.scale(Math.sin(a))), uj = u.scale(Math.cos(a)).add(f.scale(Math.sin(a)));
        Vec3 charniere = pt(base, r, -0.18, 0, -0.05);
        boite(c, m, charniere.add(fj.scale(0.25 * K)).subtract(uj.scale(0.03 * K)), fj, s, uj, 0.25 * K, 0.15 * K, 0.04 * K, 0.4F, 0.34F, 0.38F);
        if (a > 0.12) {
            Vec3 fm = dir(f.add(fj)), um = dir(u.add(uj));
            boite(c, m, charniere.add(fm.scale(0.22 * K)), fm, s, um, 0.2 * K, 0.13 * K, 0.015 * K, 0.42F, 0.05F, 0.08F);
        }
        for (int i = -1; i <= 1; i += 2) {
            boite(c, m, pt(base, r, 0.3, i * 0.08, -0.08), f, s, u, 0.02 * K, 0.02 * K, 0.07 * K, 0.93F, 0.92F, 0.86F);
            Vec3 oeil = pt(base, r, 0.08, i * 0.19, 0.08);
            boite(c, m, oeil, f, s, u, 0.05 * K, 0.02 * K, 0.04 * K, 1F, 0.82F, 0.15F);
            boite(c, m, oeil.add(s.scale(i * 0.015 * K)), f, s, u, 0.012 * K, 0.008 * K, 0.035 * K, 0.03F, 0.02F, 0.02F);
        }
        if (h.langue() > 0.02 && a < 0.6) {
            double l = 0.3 * h.langue();
            Vec3 b = pt(base, r, 0.38, 0, -0.03);
            boite(c, m, b.add(f.scale(l / 2 * K)), f, s, u, l / 2 * K, 0.012 * K, 0.007 * K, 0.5F, 0.06F, 0.1F);
            for (int i = -1; i <= 1; i += 2) {
                Vec3 df = dir(f.add(s.scale(i * 0.5)));
                boite(c, m, b.add(f.scale(l * K)).add(df.scale(0.04 * K)), df, s, u, 0.04 * K, 0.01 * K, 0.007 * K, 0.5F, 0.06F, 0.1F);
            }
        }
    }

    /** Un point de la tête : en avant, sur le côté, vers le haut (à l'échelle de la tête). */
    private static Vec3 pt(Vec3 base, Vec3[] r, double av, double co, double ha) {
        return base.add(r[0].scale(av * K)).add(r[2].scale(co * K)).add(r[1].scale(ha * K));
    }

    // ================================================================ les sangles, les outils, le sceau

    private static void sangles(VertexConsumer c, Matrix4f m, Vec3 cam, double t) {
        for (int i = 0; i < SANGLES.length; i++) {
            if (t >= DEFAITES[i]) {
                continue;
            }
            double x = SANGLES[i], haut = TABLE + 0.255, cote = 0.44;
            Vec3 centre = w(x, haut, 0.5).subtract(cam);
            boite(c, m, centre, X, HAUT, Z, 0.065, 0.02, cote, 0.2F, 0.12F, 0.07F);
            for (int k = -1; k <= 1; k += 2) {
                double hm = (haut - TABLE + 0.05) / 2;
                boite(c, m, w(x, TABLE - 0.05 + hm, 0.5 + k * cote).subtract(cam), X, HAUT, Z, 0.065, hm, 0.02, 0.18F, 0.11F, 0.06F);
            }
            boite(c, m, w(boucle(i)).subtract(cam), X, HAUT, Z, 0.05, 0.018, 0.045, 0.55F, 0.55F, 0.6F);
        }
    }

    /** Une base autour d'une direction : le côté, et le dessus. */
    private static Vec3[] base(Vec3 a) {
        Vec3 u = HAUT.subtract(a.scale(a.y));
        u = u.lengthSqr() < 1.0E-4 ? X : dir(u);
        return new Vec3[] {a.cross(u), u};
    }

    private static void outil(VertexConsumer c, Matrix4f m, Vec3 cam, Savant s, double t) {
        if (s.outil == Outil.AUCUN || s.bout == null) {
            return;
        }
        Vec3 h = main(s.f, true);
        if (s.outil == Outil.PERCEUSE) {
            // une grosse perceuse noire et grise : poignée et batterie dans la main, le corps au-dessus, la mèche qui tourne
            Vec3 u = base(dir(s.bout.subtract(h)))[1];
            Vec3 corps0 = h.add(u.scale(0.13));
            Vec3 a2 = dir(s.bout.subtract(corps0));
            Vec3[] b2 = base(a2);
            boite(c, m, h.subtract(cam).add(u.scale(-0.04)), a2, b2[0], b2[1], 0.05, 0.05, 0.12, 0.1F, 0.1F, 0.1F);
            boite(c, m, h.subtract(cam).add(u.scale(-0.19)), a2, b2[0], b2[1], 0.1, 0.09, 0.05, 0.13F, 0.13F, 0.14F);
            boite(c, m, corps0.add(a2.scale(0.08)).subtract(cam), a2, b2[0], b2[1], 0.2, 0.08, 0.09, 0.32F, 0.33F, 0.35F);
            boite(c, m, corps0.add(a2.scale(0.32)).subtract(cam), a2, b2[0], b2[1], 0.05, 0.055, 0.055, 0.18F, 0.18F, 0.2F);
            Vec3 meche0 = corps0.add(a2.scale(0.37));
            double lm = Mth.clamp(s.bout.distanceTo(meche0), 0.15, 0.9);
            double tour = t >= PERCE && t < PERCE_FIN || bosse(t, 4.6, 0.05, 0.4, 0.15) > 0 ? t * 70 : 0.3;
            Vec3 r1 = b2[0].scale(Math.cos(tour)).add(b2[1].scale(Math.sin(tour))), r2 = a2.cross(r1);
            float sang = t >= 6.2 ? 0.6F : 1F;
            boite(c, m, meche0.add(a2.scale(lm / 2)).subtract(cam), a2, r1, r2, lm / 2, 0.028, 0.012, 0.75F, 0.77F * sang, 0.8F * sang);
            boite(c, m, meche0.add(a2.scale(lm / 2)).subtract(cam), a2, r2, r1, lm / 2, 0.02, 0.012, 0.6F, 0.62F * sang, 0.66F * sang);
        } else {
            // la seringue : le piston, le corps (plein de noir, qui se vide), l'aiguille
            Vec3 ax = dir(s.bout.subtract(h));
            Vec3[] b = base(ax);
            Vec3 avant = h.add(ax.scale(0.45)), arriere = h.add(ax.scale(-0.2));
            double plein = 0.65 * (1 - s.piston);
            if (plein > 0.01) {
                boite(c, m, avant.subtract(ax.scale(plein / 2)).subtract(cam), ax, b[0], b[1], plein / 2, 0.07, 0.07, 0.05F, 0.03F, 0.07F);
            }
            double vide = 0.65 - plein;
            if (vide > 0.01) {
                boite(c, m, arriere.add(ax.scale(vide / 2)).subtract(cam), ax, b[0], b[1], vide / 2, 0.07, 0.07, 0.7F, 0.74F, 0.78F);
            }
            for (double g = 0.08; g < 0.65; g += 0.12) {
                boite(c, m, arriere.add(ax.scale(g)).subtract(cam), ax, b[0], b[1], 0.005, 0.075, 0.075, 0.2F, 0.2F, 0.22F);
            }
            double tige = 0.2 + 0.18 * (1 - s.piston);
            boite(c, m, arriere.subtract(ax.scale(tige / 2)).subtract(cam), ax, b[0], b[1], tige / 2, 0.02, 0.02, 0.55F, 0.55F, 0.58F);
            boite(c, m, arriere.subtract(ax.scale(tige)).subtract(cam), ax, b[0], b[1], 0.015, 0.09, 0.09, 0.7F, 0.7F, 0.72F);
            double la = Mth.clamp(s.bout.distanceTo(avant), 0.1, 0.5);
            boite(c, m, avant.add(ax.scale(la / 2)).subtract(cam), ax, b[0], b[1], la / 2, 0.008, 0.008, 0.75F, 0.77F, 0.8F);
        }
    }

    /** L'encre de l'invocation : des traits noirs qui courent de la main du savant à la tête de la table, puis le sceau. */
    private static void encre(VertexConsumer c, Matrix4f m, Vec3 cam, double t) {
        if (t < 20.3 || t > 24) {
            return;
        }
        Vec3 main = new Vec3(2.15, 2.012, 1.42);
        double pousse = entre(t, 20.3, 20.9), fondu = 1 - entre(t, 22.5, 24);
        float k = (float) (0.06 * fondu);
        for (int i = -2; i <= 2; i++) {
            Vec3 but = ANTRE.add(0, 0.012, i * 0.35);
            Vec3 d = but.subtract(main);
            double l = d.length() * pousse;
            Vec3 ax = d.normalize();
            Vec3 cote = ax.cross(HAUT).normalize();
            boite(c, m, w(main.add(ax.scale(l / 2))).subtract(cam), ax, HAUT, cote, l / 2, 0.003, 0.025, k, k * 0.6F, k * 1.2F);
        }
    }

    private static void sceau(PoseStack pose, MultiBufferSource buffers, Vec3 cam, double t) {
        double r = 1.7 * lisse(entre(t, 20.7, 21.0));
        float a = (float) (0.9 * entre(t, 20.7, 20.8) * (1 - entre(t, 22.5, 24)));
        if (r <= 0.01 || a <= 0.01) {
            return;
        }
        double ang = 0.4 * (t - 20.7);
        Vec3 u = new Vec3(Math.cos(ang), 0, Math.sin(ang)).scale(r), v = new Vec3(-Math.sin(ang), 0, Math.cos(ang)).scale(r);
        Vec3 c0 = w(ANTRE.add(0, 0.015, 0)).subtract(cam);
        PoseStack.Pose p = pose.last();
        VertexConsumer c = buffers.getBuffer(RenderType.entityTranslucent(SCEAU));
        Vec3[] coins = {c0.subtract(u).add(v), c0.add(u).add(v), c0.add(u).subtract(v), c0.subtract(u).subtract(v)};
        float[][] uv = {{0, 1}, {1, 1}, {1, 0}, {0, 0}};
        int l = LevelRenderer.getLightColor(Minecraft.getInstance().level, BlockPos.containing(w(ANTRE.add(0, 0.5, 0))));
        for (int i = 0; i < 4; i++) {
            c.addVertex(p, (float) coins[i].x, (float) coins[i].y, (float) coins[i].z).setColor(0.08F, 0.02F, 0.1F, a).setUv(uv[i][0], uv[i][1])
                    .setOverlay(OverlayTexture.NO_OVERLAY).setLight(l).setNormal(p, 0, 1, 0);
        }
    }

    // ================================================================ les choses dessinées

    private static final Vector3f LUMIERE = new Vector3f(-0.35F, 0.8F, 0.45F).normalize();

    /** Un pavé plein d'une couleur (ombré selon ses faces) : son centre, ses axes et ses demi-tailles. */
    private static void boite(VertexConsumer c, Matrix4f m, Vec3 o, Vec3 x, Vec3 y, Vec3 z, double hx, double hy, double hz, float r, float g, float b) {
        Vec3[] axes = {x, y, z};
        double[] demis = {hx, hy, hz};
        for (int a = 0; a < 3; a++) {
            Vec3 n = axes[a], u = axes[(a + 1) % 3], v = axes[(a + 2) % 3];
            double hn = demis[a], hu = demis[(a + 1) % 3], hv = demis[(a + 2) % 3];
            for (int s = -1; s <= 1; s += 2) {
                float k = (float) (0.6 + 0.4 * Math.abs(n.x * LUMIERE.x + n.y * LUMIERE.y + n.z * LUMIERE.z) + (s * n.y > 0.5 ? 0.1 : 0));
                Vec3 cf = o.add(n.scale(s * hn));
                Vec3[] coins = {cf.add(u.scale(-hu)).add(v.scale(-hv)), cf.add(u.scale(hu)).add(v.scale(-hv)), cf.add(u.scale(hu)).add(v.scale(hv)),
                        cf.add(u.scale(-hu)).add(v.scale(hv))};
                for (Vec3 p : coins) {
                    c.addVertex(m, (float) p.x, (float) p.y, (float) p.z).setColor(Math.min(1, r * k), Math.min(1, g * k), Math.min(1, b * k), 1F);
                }
            }
        }
    }

    private static PlayerModel<LivingEntity> large, fin;

    private static ResourceLocation skin(UUID u) {
        Player p = Minecraft.getInstance().level.getPlayerByUUID(u);
        return p instanceof AbstractClientPlayer c ? c.getSkin().texture() : DefaultPlayerSkin.getDefaultTexture();
    }

    private static boolean brasFins(UUID u) {
        Player p = Minecraft.getInstance().level.getPlayerByUUID(u);
        return p instanceof AbstractClientPlayer c && c.getSkin().model() == PlayerSkin.Model.SLIM;
    }

    private static String nom(UUID u) {
        Player p = Minecraft.getInstance().level.getPlayerByUUID(u);
        return p != null ? p.getGameProfile().getName() : "???";
    }

    /** Un double : à son skin, dans sa posture, teinté s'il le faut. */
    private static void corps(PoseStack pose, MultiBufferSource buffers, Vec3 cam, Fig f, UUID u) {
        Minecraft mc = Minecraft.getInstance();
        if (large == null) {
            large = new PlayerModel<>(mc.getEntityModels().bakeLayer(ModelLayers.PLAYER), false);
            fin = new PlayerModel<>(mc.getEntityModels().bakeLayer(ModelLayers.PLAYER_SLIM), true);
        }
        PlayerModel<LivingEntity> m = brasFins(u) ? fin : large;
        m.young = false;
        m.crouching = false;
        m.riding = false;
        m.head.xRot = f.teteX;
        m.head.yRot = f.teteY;
        m.head.zRot = 0;
        m.body.xRot = m.body.yRot = m.body.zRot = 0;
        m.rightArm.xRot = f.bdX;
        m.rightArm.yRot = f.bdY;
        m.rightArm.zRot = f.bdZ;
        m.leftArm.xRot = f.bgX;
        m.leftArm.yRot = f.bgY;
        m.leftArm.zRot = f.bgZ;
        m.rightLeg.xRot = f.jdX;
        m.rightLeg.yRot = 0;
        m.rightLeg.zRot = f.jdZ;
        m.leftLeg.xRot = f.jgX;
        m.leftLeg.yRot = 0;
        m.leftLeg.zRot = f.jgZ;
        m.hat.copyFrom(m.head);
        m.jacket.copyFrom(m.body);
        m.rightSleeve.copyFrom(m.rightArm);
        m.leftSleeve.copyFrom(m.leftArm);
        m.rightPants.copyFrom(m.rightLeg);
        m.leftPants.copyFrom(m.leftLeg);
        pose.pushPose();
        pose.translate(-cam.x, -cam.y, -cam.z);
        appliquer(pose, f);
        int l = LevelRenderer.getLightColor(mc.level, BlockPos.containing(f.pieds.add(0, 1.2, 0)));
        m.renderToBuffer(pose, buffers.getBuffer(RenderType.entityTranslucent(skin(u))), l, OverlayTexture.NO_OVERLAY, f.couleur);
        pose.popPose();
    }

    // ================================================================ le dessin

    private static void dessiner(PoseStack pose, Vec3 cam) {
        Minecraft mc = Minecraft.getInstance();
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        double t = t();
        Savant sv = savant(t);
        corps(pose, buffers, cam, spectateur(t), acteurs[0]);
        corps(pose, buffers, cam, sv.f, acteurs[1]);
        corps(pose, buffers, cam, allonge(t), acteurs[2]);
        sceau(pose, buffers, cam, t);
        buffers.endBatch();
        VertexConsumer c = buffers.getBuffer(RenderType.debugQuads());
        Matrix4f m = pose.last().pose();
        sangles(c, m, cam, t);
        encre(c, m, cam, t);
        Tete h = serpent(t);
        if (h != null) {
            serpentCorps(c, m, cam, h, t);
            serpentTete(c, m, cam, h);
        }
        outil(c, m, cam, sv, t);
        buffers.endBatch(RenderType.debugQuads());
        if (h != null) {
            // ses yeux luisent dans le noir
            Vec3[] r = repere(h.f());
            Vec3 b = w(h.p()).subtract(cam);
            for (int i = -1; i <= 1; i += 2) {
                Effets.sphere(pose, buffers, pt(b, r, 0.08, i * 0.21, 0.08), 0.055 * K, 1F, 0.75F, 0.1F, 0.45F);
            }
        }
        buffers.endBatch();
    }

    // ================================================================ la caméra

    private static Vec3 camera = Vec3.ZERO, vise = Vec3.ZERO;

    private static Vec3 v(double x, double y, double z) {
        return new Vec3(x, y, z);
    }

    private static Vec3 vers(Vec3 a, Vec3 b, double u) {
        return a.lerp(b, lisse(u));
    }

    private static void plan(double t) {
        Tete h = serpent(t);
        if (t < PERCEUSE_T) {
            camera = vers(v(-4.6, 5.0, 4.6), v(-3.8, 4.6, 3.9), entre(t, 0, PERCEUSE_T));
            vise = v(1.0, 2.9, 0.3);
            fov = 55;
        } else if (t < 4.9) {
            camera = vers(v(2.0, 4.1, 1.15), v(1.95, 4.0, 1.05), entre(t, PERCEUSE_T, 4.9));
            vise = TETE;
            fov = 45;
        } else if (t < PERCE) {
            camera = vers(v(1.3, 3.75, -0.85), v(1.25, 3.7, -0.7), entre(t, 4.9, PERCE));
            vise = v(1.0, 3.5, 1.6);
            fov = 50;
        } else if (t < 9.0) {
            camera = vers(v(2.9, 3.75, -1.55), v(2.6, 3.6, -1.35), entre(t, PERCE, 9.0));
            vise = VENTRE.add(0, 0.1, 0);
            fov = 50;
        } else if (t < 10.2) {
            camera = vers(v(0.55, 3.75, -0.3), v(0.5, 3.7, -0.35), entre(t, 9.0, 10.2));
            vise = v(-1.1, 3.55, -1.5);
            fov = 45;
        } else if (t < 11.6) {
            camera = vers(v(0.4, 6.0, 1.4), v(0.6, 5.7, 1.2), entre(t, 10.2, 11.6));
            vise = VENTRE;
            fov = 55;
        } else if (t < PERCE_FIN) {
            camera = vers(v(2.3, 4.0, 0.6), v(2.2, 3.9, 0.55), entre(t, 11.6, PERCE_FIN));
            vise = TETE;
            fov = 45;
        } else if (t < SERINGUE) {
            camera = vers(v(-1.4, 3.4, 3.0), v(-1.2, 3.3, 2.8), entre(t, PERCE_FIN, SERINGUE));
            vise = v(0.8, 3.0, 0.8);
            fov = 55;
        } else if (t < PIQURE) {
            camera = vers(v(1.6, 3.9, -1.1), v(1.5, 3.85, -1.0), entre(t, SERINGUE, PIQURE));
            vise = v(1.45, 3.5, 1.6);
            fov = 50;
        } else if (t < POUCE) {
            camera = vers(v(2.2, 4.6, -0.7), v(2.15, 4.5, -0.6), entre(t, PIQURE, POUCE));
            vise = COU.add(0, 0.2, 0.2);
            fov = 50;
        } else if (t < SOL_T) {
            camera = vers(v(2.7, 4.4, -0.8), v(2.6, 4.3, -0.7), entre(t, POUCE, SOL_T));
            vise = v(1.75, 3.4, 1.75);
            fov = 50;
        } else if (t < DOMINE) {
            // un seul plan : la paume sur le sol, l'encre, la fumée, et le serpent qui se dresse
            camera = vers(v(4.3, 3.5, 2.4), v(4.7, 2.9, 2.9), entre(t, SOL_T, DOMINE));
            Vec3 b = v(2.7, 2.2, 1.0);
            vise = h != null ? b.lerp(h.p().lerp(ANTRE.add(0, 1, 0), 0.35), lisse(entre(t, SORT, 22.4))) : b;
            fov = 62;
        } else if (t < DESCEND) {
            camera = vers(v(-2.6, 4.5, -1.9), v(-2.4, 4.4, -1.8), entre(t, DOMINE, DESCEND));
            vise = v(2.0, 4.2, 0.4);
            fov = 55;
        } else if (t < MORSURE) {
            camera = vers(v(1.6, 3.9, 3.6), v(1.65, 3.85, 3.4), entre(t, DESCEND, MORSURE));
            vise = v(2.1, 3.6, 0.6);
            fov = 60;
        } else if (t < LACHE) {
            camera = vers(v(-0.8, 4.8, -0.9), v(-0.4, 5.0, -1.3), entre(t, MORSURE, LACHE));
            vise = v(1.8, 3.3, 0.6);
            fov = 55;
        } else if (t < MARQUE) {
            camera = vers(v(-2.8, 4.2, 2.6), v(-2.6, 4.0, 2.4), entre(t, LACHE, MARQUE));
            vise = v(2.6, 3.4, 0.4);
            fov = 55;
        } else if (t < PLAT) {
            camera = vers(v(0.7, 4.6, 0.9), v(0.9, 4.3, 0.8), entre(t, MARQUE, PLAT));
            vise = v(1.2, 2.9, 0.5);
            fov = 55;
        } else if (t < BOUCLES) {
            camera = vers(v(1.9, 4.6, -1.0), v(1.8, 4.3, -0.75), entre(t, PLAT, BOUCLES));
            vise = TETE;
            fov = 45;
        } else if (t < ASSIS) {
            camera = vers(v(-1.0, 4.6, -0.6), v(-0.9, 4.5, -0.5), entre(t, BOUCLES, ASSIS));
            vise = v(0.7, 3.0, 1.0);
            fov = 60;
        } else if (t < DESCENTE) {
            camera = vers(v(-1.4, 4.6, 0.9), v(-1.25, 4.5, 0.85), entre(t, ASSIS, DESCENTE));
            vise = v(0.75, 3.6, 0.5);
            fov = 55;
        } else {
            camera = vers(v(-4.4, 3.9, 2.6), v(-5.0, 4.1, 3.0), entre(t, DESCENTE, DUREE));
            vise = v(0.3, 3.0, 0.5).lerp(v(-1.5, 3.3, 0.4), lisse(entre(t, DESCENTE, 46.4)));
            fov = 55;
        }
    }

    private static final Random SECOUSSE = new Random();

    private static void placerCamera(double t) {
        if (objectif == null) {
            return;
        }
        plan(t);
        Vec3 c = w(camera);
        Vec3 cible = w(vise);
        double k = (t >= PERCE && t < PERCE_FIN ? 0.045 : 0) + 0.12 * bosse(t, FUMEE, 0.03, 0.2, 0.5) + 0.18 * bosse(t, MORSURE + 0.2, 0.03, 0.2, 0.6)
                + 0.04 * (t >= 27.4 && t < LACHE ? 1 : 0) + 0.1 * bosse(t, SOUFFLE, 0.03, 0.15, 0.4);
        c = c.add((SECOUSSE.nextDouble() - 0.5) * k, (SECOUSSE.nextDouble() - 0.5) * k, (SECOUSSE.nextDouble() - 0.5) * k);
        Vec3 d = cible.subtract(c);
        float lac = (float) Math.toDegrees(Math.atan2(-d.x, d.z));
        float tangage = (float) Math.toDegrees(-Math.atan2(d.y, Math.sqrt(d.x * d.x + d.z * d.z)));
        objectif.setPos(c.x, c.y, c.z);
        objectif.xo = objectif.xOld = c.x;
        objectif.yo = objectif.yOld = c.y;
        objectif.zo = objectif.zOld = c.z;
        objectif.setYRot(lac);
        objectif.setXRot(tangage);
        objectif.yRotO = lac;
        objectif.xRotO = tangage;
    }

    // ================================================================ la nuit dans la salle

    /** Les lampes qui vacillent : un noir de plus, par à-coups (fort pendant la perceuse, à l'invocation, au réveil). */
    private static double vacille(double t) {
        long creneau = (long) (t * 14);
        double p = t >= PERCE && t < PERCE_FIN ? 0.14 : t >= FUMEE && t < 22 ? 0.35 : t >= SOUFFLE && t < 40.8 ? 0.5 : t >= MARQUE && t < PLAT ? 0.06 : 0.02;
        return hasard(creneau) < p ? 0.2 + 0.3 * hasard(creneau + 977) : 0;
    }

    /** Le noir posé sur l'image (en plus de la vignette) : entrée dans le noir, vacillements, et presque rien quand le cœur s'arrête. */
    private static double obscurite(double t) {
        double base = 0.32 + 0.3 * entre(t, PLAT, 39.5) * (1 - entre(t, SOUFFLE, SOUFFLE + 0.3)) - 0.08 * entre(t, ASSIS, 47);
        return Mth.clamp(Math.max(1 - entre(t, 0, 1.4), base + vacille(t)), 0, 0.95);
    }

    private static float brouillard(double t) {
        return (float) (15 - 5 * entre(t, PLAT, 39.5) * (1 - entre(t, SOUFFLE, SOUFFLE + 0.5)));
    }

    // ================================================================ les sons et les particules

    private static boolean passe(double a, double t, double quand) {
        return a < quand && t >= quand;
    }

    private static void son(Vec3 p, SoundEvent e, float volume, float hauteur) {
        Vec3 q = w(p);
        Minecraft.getInstance().level.playLocalSound(q.x, q.y, q.z, e, SoundSource.PLAYERS, volume, hauteur, false);
    }

    private static void son(Vec3 p, Holder<SoundEvent> e, float volume, float hauteur) {
        son(p, e.value(), volume, hauteur);
    }

    private static void gerbe(ParticleOptions type, Vec3 monde, int n, double d, double vitesse) {
        Random r = new Random();
        for (int i = 0; i < n; i++) {
            Minecraft.getInstance().level.addParticle(type, monde.x + (r.nextDouble() - 0.5) * 2 * d, monde.y + (r.nextDouble() - 0.5) * 2 * d,
                    monde.z + (r.nextDouble() - 0.5) * 2 * d, (r.nextDouble() - 0.5) * vitesse, (r.nextDouble() - 0.5) * vitesse, (r.nextDouble() - 0.5) * vitesse);
        }
    }

    private static final Random HASARD = new Random();
    private static final DustParticleOptions NOIR = new DustParticleOptions(new Vector3f(0.08F, 0.02F, 0.1F), 1.2F);

    private static float cri() {
        return 0.7F + HASARD.nextFloat() * 0.4F;
    }

    private static double periodeBip(double t) {
        if (t >= PLAT && t < SOUFFLE + 0.2) {
            return 0;
        }
        return t < PERCE ? 0.8 : t < SERINGUE ? 0.33 : t < SORT ? 0.55 : t < MORSURE ? 0.4 : t < MARQUE ? 0.3 : t < PLAT ? 0.2 + 0.08 * HASARD.nextDouble()
                : t < 44 ? 0.45 : 0.7;
    }

    private static void evenements(double a, double t) {
        Vec3 tete = TETE, ventre = VENTRE, antre = ANTRE.add(0, 1, 0);
        // le bourdon de la salle, tout le long
        for (double h = 0; h < 40; h += 9) {
            if (passe(a, t, h)) {
                son(new Vec3(1, 4, 0.5), BOURDON, 1.2F, 1F);
            }
        }
        // le moniteur cardiaque
        double p = periodeBip(t);
        if (p > 0) {
            double avantBip = phaseBip;
            phaseBip += (t - a) / p;
            if (Math.floor(phaseBip) > Math.floor(avantBip)) {
                son(MONITEUR, BIP, 0.5F, 1F);
            }
        }
        // les lampes qui grésillent
        for (double h : new double[] {0.6, 6.2, 7.6, 9.1, 10.8, FUMEE, 36.7, SOUFFLE}) {
            if (passe(a, t, h)) {
                son(new Vec3(1, 9, 0), GRESILLE, 0.6F, 0.8F + HASARD.nextFloat() * 0.3F);
            }
        }
        // les gants
        if (passe(a, t, 1.1) || passe(a, t, 2.0)) {
            son(SAV.add(0, 1.2, 0), SoundEvents.ARMOR_EQUIP_LEATHER, 1F, 1.1F);
        }
        // il tire sur ses sangles (le cuir et les boucles qui grincent)
        boolean lutte = t >= PERCE && t < PERCE_FIN || t >= 16 && t < 17.5 || t >= DESCEND && t < LACHE || t >= MARQUE && t < PLAT;
        if (lutte && Math.floor(t / 0.85) > Math.floor(a / 0.85) || passe(a, t, 4.8)) {
            son(ventre, SoundEvents.ARMOR_EQUIP_LEATHER, 1F, 0.55F);
            son(ventre, SoundEvents.CHAIN_STEP, 0.8F, 0.5F);
        }
        // la perceuse
        if (passe(a, t, 4.6)) {
            son(SAV.add(0, 2, 0), PERCEUSE_VIDE, 1.5F, 0.9F);
        }
        if (passe(a, t, PERCE)) {
            son(ventre, PERCEUSE, 2F, 1F);
            son(tete, SoundEvents.PLAYER_HURT, 1.5F, 1F);
        }
        if (t >= PERCE && t < PERCE_FIN) {
            for (double h = PERCE + 0.2; h < PERCE_FIN; h += 0.45) {
                if (passe(a, t, h)) {
                    son(ventre, SoundEvents.GRINDSTONE_USE, 1.2F, 0.6F + HASARD.nextFloat() * 0.25F);
                }
            }
            for (double h : new double[] {6.0, 6.8, 7.7, 8.7, 10.0, 11.3}) {
                if (passe(a, t, h)) {
                    son(tete, SoundEvents.PLAYER_HURT, 1.5F, cri());
                }
            }
            if (passe(a, t, 7.2) || passe(a, t, 9.4)) {
                son(ventre, SoundEvents.ZOMBIE_BREAK_WOODEN_DOOR, 1.2F, 0.9F);
            }
            if (passe(a, t, 8.0) || passe(a, t, 10.6)) {
                son(ventre, SoundEvents.SKELETON_HURT, 1.5F, 0.5F);
            }
            if (passe(a, t, 9.8)) {
                son(tete, SoundEvents.GHAST_HURT, 0.5F, 0.5F);
            }
            Savant s = savant(t);
            if (s.bout != null) {
                gerbe(ParticleTypes.CRIT, s.bout, 1, 0.05, 0.5);
                gerbe(DustParticleOptions.REDSTONE, s.bout.add(0, 0.1, 0), 3, 0.08, 0.4);
                if (HASARD.nextInt(3) == 0) {
                    gerbe(new ItemParticleOption(ParticleTypes.ITEM, new ItemStack(Items.BONE)), s.bout.add(0, 0.1, 0), 1, 0.05, 0.4);
                }
                gerbe(ParticleTypes.SMOKE, s.bout, 1, 0.05, 0.05);
            }
        }
        if (passe(a, t, PERCE_FIN)) {
            son(tete, SoundEvents.PLAYER_HURT_DROWN, 1F, 0.8F);
        }
        if (passe(a, t, 13.3)) {
            son(SAV.add(0.5, 1.5, 0.5), SoundEvents.ARMOR_EQUIP_IRON, 1F, 0.6F);
        }
        // la seringue de liquide noir
        if (passe(a, t, SERINGUE)) {
            son(SAV.add(0.4, 2, 0), SoundEvents.BOTTLE_FILL, 1F, 0.5F);
        }
        if (passe(a, t, 14.6) || passe(a, t, 14.85)) {
            son(SAV.add(0.4, 2.4, 0), SoundEvents.GLASS_HIT, 0.8F, 1.6F);
        }
        if (passe(a, t, PIQURE)) {
            son(COU, SoundEvents.BEE_STING, 1.2F, 0.6F);
            son(tete, SoundEvents.PLAYER_HURT, 1.2F, 0.9F);
        }
        if (passe(a, t, 15.8)) {
            son(COU, SoundEvents.HONEY_DRINK, 1.2F, 0.5F);
            son(COU, SoundEvents.SCULK_BLOCK_SPREAD, 1.5F, 0.7F);
        }
        if (passe(a, t, 16.4)) {
            son(COU, SoundEvents.SCULK_CATALYST_BLOOM, 1.5F, 0.6F);
            son(tete, SoundEvents.PLAYER_HURT, 1.2F, 0.7F);
        }
        if (t >= 15.8 && t < 17) {
            gerbe(ParticleTypes.SQUID_INK, w(COU), 1, 0.05, 0.02);
        }
        if (t >= 16 && t < 18 && HASARD.nextInt(2) == 0) {
            gerbe(NOIR, w(VENTRE).add(0, 0.2, 0), 1, 0.5, 0.05);
        }
        // l'invocation : le pouce, les signes, la paume sur le sol, la fumée
        if (passe(a, t, 18.35)) {
            gerbe(DustParticleOptions.REDSTONE, bouche(savant(t).f), 3, 0.03, 0.1);
        }
        for (int i = 0; i < 5; i++) {
            if (passe(a, t, 18.8 + i * 0.25)) {
                son(SAV.add(0.7, 1.4, 0.15), SoundEvents.ARMOR_EQUIP_LEATHER, 1F, 1.3F);
            }
        }
        if (passe(a, t, SOL_T + 0.2)) {
            son(new Vec3(2.15, 2, 1.42), SoundEvents.MACE_SMASH_GROUND_HEAVY, 1F, 0.7F);
            son(new Vec3(2.15, 2, 1.42), SoundEvents.EVOKER_PREPARE_SUMMON, 1.2F, 0.6F);
        }
        if (passe(a, t, 20.5)) {
            son(ANTRE, SoundEvents.SOUL_ESCAPE, 1.5F, 0.5F);
            son(ANTRE, SoundEvents.SCULK_CATALYST_BLOOM, 1.5F, 0.5F);
        }
        if (passe(a, t, FUMEE)) {
            son(antre, SoundEvents.GENERIC_EXPLODE, 1.5F, 0.5F);
            son(antre, SoundEvents.ILLUSIONER_CAST_SPELL, 1.5F, 0.6F);
            Vec3 q = w(ANTRE.add(0, 0.8, 0));
            gerbe(ParticleTypes.POOF, q, 70, 1.1, 0.25);
            gerbe(ParticleTypes.LARGE_SMOKE, q, 45, 1.0, 0.1);
            gerbe(ParticleTypes.CAMPFIRE_COSY_SMOKE, w(ANTRE.add(0, 0.3, 0)), 14, 0.7, 0.03);
        }
        if (t >= FUMEE && t < 23.5) {
            gerbe(ParticleTypes.LARGE_SMOKE, w(ANTRE.add(0, 0.5, 0)), 2, 0.8, 0.05);
        }
        // le serpent
        if (passe(a, t, 21.6) || passe(a, t, 22.6)) {
            son(antre, SoundEvents.HONEY_BLOCK_SLIDE, 1.5F, 0.45F);
        }
        if (passe(a, t, 21.8) || passe(a, t, 23.2) || passe(a, t, 25.0)) {
            son(antre.add(0, 2.5, 0), SoundEvents.CAT_HISS, 2F, 0.35F);
        }
        if (passe(a, t, DOMINE)) {
            son(antre.add(0, 3, 0), SoundEvents.ENDER_DRAGON_GROWL, 0.6F, 0.6F);
        }
        if (passe(a, t, 25.5)) {
            son(tete, SoundEvents.PLAYER_HURT, 0.6F, 0.9F);
        }
        if (passe(a, t, 26.6)) {
            son(COU.add(0.8, 1, 0.3), SoundEvents.CAT_HISS, 2F, 0.45F);
            son(COU.add(0.8, 1, 0.3), SoundEvents.CREEPER_PRIMED, 1F, 0.4F);
        }
        if (passe(a, t, 27.4)) {
            son(COU, SoundEvents.EVOKER_FANGS_ATTACK, 2F, 0.7F);
            son(COU, SoundEvents.PLAYER_ATTACK_CRIT, 1.5F, 0.7F);
            son(COU, SoundEvents.SKELETON_HURT, 1.5F, 0.5F);
            son(COU, SoundEvents.ZOMBIE_BREAK_WOODEN_DOOR, 1F, 0.7F);
            son(tete, SoundEvents.PLAYER_HURT, 1.5F, 1F);
            gerbe(DustParticleOptions.REDSTONE, w(COU), 30, 0.2, 0.6);
        }
        if (t >= 27.5 && t < LACHE) {
            for (double h = 27.8; h < LACHE; h += 0.4) {
                if (passe(a, t, h)) {
                    son(COU, SoundEvents.GENERIC_EAT, 1.3F, 0.45F);
                }
            }
            for (double h = 28.1; h < LACHE; h += 0.7) {
                if (passe(a, t, h)) {
                    son(tete, SoundEvents.PLAYER_HURT, (float) (1.4 - 0.25 * (h - 28)), cri());
                }
            }
            gerbe(DustParticleOptions.REDSTONE, w(COU).add(0, 0.1, 0), 1, 0.1, 0.2);
        }
        if (passe(a, t, LACHE)) {
            son(COU.add(0.5, 1, 0.3), SoundEvents.CAT_HISS, 1.5F, 0.4F);
        }
        if (t >= LACHE && t < 32.5) {
            gerbe(DustParticleOptions.REDSTONE, w(COU), 1, 0.05, 0.05);
        }
        if (passe(a, t, 32.0)) {
            son(antre, SoundEvents.GENERIC_EXPLODE, 0.6F, 0.8F);
            son(antre, SoundEvents.EVOKER_CAST_SPELL, 1F, 0.6F);
            gerbe(ParticleTypes.POOF, w(ANTRE.add(0, 0.8, 0)), 45, 1.0, 0.2);
            gerbe(ParticleTypes.LARGE_SMOKE, w(ANTRE.add(0, 0.8, 0)), 30, 0.9, 0.08);
        }
        // la marque se répand : le cœur cogne de plus en plus vite, puis s'arrête
        if (t >= MARQUE && t < PLAT) {
            if (battement(t) > 0.9 && battement(a) <= 0.9) {
                son(ventre, SoundEvents.WARDEN_HEARTBEAT, 1.5F, 1F);
            }
            if (HASARD.nextInt(2) == 0) {
                gerbe(NOIR, w(COU), 1, 0.25, 0.03);
            }
            if (HASARD.nextInt(6) == 0) {
                gerbe(ParticleTypes.SCULK_SOUL, w(VENTRE).add(0, 0.3, 0), 1, 0.4, 0.02);
            }
        }
        if (passe(a, t, 33.5)) {
            son(tete, SoundEvents.WITHER_AMBIENT, 0.4F, 0.5F);
        }
        if (passe(a, t, 34.6) || passe(a, t, 35.7)) {
            son(tete, SoundEvents.PLAYER_HURT, 1F, 0.6F);
        }
        if (passe(a, t, PLAT)) {
            son(MONITEUR, BIP_LONG, 1F, 1F);
        }
        // il reprend son souffle
        if (passe(a, t, SOUFFLE)) {
            son(tete, SoundEvents.PLAYER_HURT_DROWN, 1.5F, 0.9F);
            son(ventre, SoundEvents.WARDEN_HEARTBEAT, 2F, 1F);
            son(ventre, SoundEvents.WARDEN_ROAR, 0.35F, 0.8F);
            gerbe(NOIR, w(VENTRE).add(0, 0.3, 0), 40, 0.6, 0.3);
            gerbe(ParticleTypes.SCULK_SOUL, w(VENTRE).add(0, 0.3, 0), 10, 0.5, 0.05);
        }
        if (passe(a, t, SOUFFLE + 0.4)) {
            son(ventre, SoundEvents.WARDEN_HEARTBEAT, 1.5F, 1F);
        }
        // les sangles qu'on défait
        for (double h : DEFAITES) {
            if (passe(a, t, h)) {
                son(ventre, SoundEvents.CHAIN_BREAK, 0.6F, 1.4F);
                son(ventre, SoundEvents.ARMOR_EQUIP_LEATHER, 1F, 0.8F);
            }
        }
        if (passe(a, t, 46.0)) {
            son(SORTIE, SoundEvents.PLAYER_SMALL_FALL, 1F, 1F);
        }
    }

    // ================================================================ l'écran (le noir, les bandes, les titres)

    private static void ecran(GuiGraphics g) {
        Minecraft mc = Minecraft.getInstance();
        if (!cinema || mc.player == null) {
            return;
        }
        double t = t();
        int w = g.guiWidth(), h = g.guiHeight();
        // la vignette, très marquée, puis le noir posé sur tout
        g.flush();
        RenderSystem.enableBlend();
        RenderSystem.blendFunc(GlStateManager.SourceFactor.ZERO, GlStateManager.DestFactor.ONE_MINUS_SRC_COLOR);
        g.setColor(0.85F, 0.85F, 0.85F, 1F);
        g.blit(VIGNETTE, 0, 0, -90, 0, 0, w, h, w, h);
        g.blit(VIGNETTE, 0, 0, -90, 0, 0, w, h, w, h);
        g.setColor(1F, 1F, 1F, 1F);
        RenderSystem.defaultBlendFunc();
        g.fill(0, 0, w, h, ((int) (obscurite(t) * 255) << 24));
        // un voile rouge quand le serpent mord, et qui bat avec la marque
        double rouge = 0.45 * bosse(t, 27.4, 0.03, 0.1, 0.5) + 0.12 * entre(t, 27.4, LACHE) * (1 - entre(t, PLAT, PLAT + 1)) + 0.12 * battement(t);
        if (rouge > 0.01) {
            g.fill(0, 0, w, h, ((int) (Math.min(1, rouge) * 255) << 24) | 0x5A0008);
        }
        double fumee = 0.35 * bosse(t, FUMEE, 0.02, 0.1, 0.6);
        if (fumee > 0.01) {
            g.fill(0, 0, w, h, ((int) (fumee * 255) << 24) | 0x9A9AA4);
        }
        int bande = (int) (h * 0.11 * lisse(entre(t, 0.2, 0.9)) * (1 - lisse(entre(t, DUREE - 0.8, DUREE - 0.2))));
        g.fill(0, 0, w, bande, 0xFF000000);
        g.fill(0, h - bande, w, h, 0xFF000000);
        texte(g, "EXPÉRIENCE N°" + numero, w / 2F, h * 0.4F, 2.2F, 0xE8E8E8, entre(t, 1.0, 1.6) * (1 - entre(t, 3.4, 3.9)));
        texte(g, "Sujet : " + nom(acteurs[2]), w / 2F, h * 0.4F + 28, 1.2F, 0x9A9A9A, entre(t, 1.5, 2.0) * (1 - entre(t, 3.4, 3.9)));
        texte(g, "Kuchiyose no jutsu", w / 2F, h - bande - 26, 1.3F, 0xB8B0C0, entre(t, 20.3, 20.6) * (1 - entre(t, 21.6, 22.0)));
        texte(g, "EXPÉRIENCE N°" + numero + " — TERMINÉE", w / 2F, bande + 22, 1.8F, 0xB01818, entre(t, 46.2, 46.8) * (1 - entre(t, 47.7, 48.2)));
    }

    private static void texte(GuiGraphics g, String s, float x, float y, float taille, int rgb, double op) {
        int a = (int) (255 * op);
        if (a > 8) {
            fr.cabane.client.ui.Ios.texteCentre(g, Minecraft.getInstance().font, s, x, y, taille, (a << 24) | rgb, true);
        }
    }

    // ================================================================ branchements

    @EventBusSubscriber(modid = Cabane.MODID, value = Dist.CLIENT)
    public static final class Couches {
        @SubscribeEvent
        public static void enregistrer(RegisterGuiLayersEvent event) {
            event.registerAboveAll(Cabane.id("operation_cinema"), (g, delta) -> ecran(g));
        }

        private Couches() {
        }
    }

    @EventBusSubscriber(modid = Cabane.MODID, value = Dist.CLIENT)
    public static final class Evenements {
        @SubscribeEvent
        public static void tick(ClientTickEvent.Post event) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.level == null || mc.player == null) {
                if (actif) {
                    arreter();
                }
                return;
            }
            if (!actif) {
                return;
            }
            double t = t();
            evenements(avant, t);
            avant = t;
            if (t >= DUREE || !mc.level.dimension().equals(fr.cabane.labo.Labo.DIMENSION)) {
                arreter();
            } else if (cinema && objectif != null && mc.getCameraEntity() != objectif) {
                mc.setCameraEntity(objectif);
            }
        }

        @SubscribeEvent
        public static void image(RenderFrameEvent.Pre event) {
            if (cinema) {
                placerCamera(t());
            }
        }

        @SubscribeEvent
        public static void dessin(RenderLevelStageEvent event) {
            if (!actif || event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES || Minecraft.getInstance().level == null) {
                return;
            }
            dessiner(event.getPoseStack(), event.getCamera().getPosition());
        }

        @SubscribeEvent
        public static void cacher(RenderLivingEvent.Pre<?, ?> event) {
            if (cache(event.getEntity())) {
                event.setCanceled(true);
            }
        }

        @SubscribeEvent
        public static void champ(ViewportEvent.ComputeFov event) {
            if (cinema) {
                event.setFOV(fov);
            }
        }

        /** Le brouillard noir : la salle s'efface autour de la table. */
        @SubscribeEvent
        public static void brume(ViewportEvent.RenderFog event) {
            if (cinema) {
                event.setNearPlaneDistance(0.5F);
                event.setFarPlaneDistance(brouillard(t()));
                event.setCanceled(true);
            }
        }

        @SubscribeEvent
        public static void couleurBrume(ViewportEvent.ComputeFogColor event) {
            if (cinema) {
                event.setRed(0.02F);
                event.setGreen(0.0F);
                event.setBlue(0.015F);
            }
        }

        @SubscribeEvent
        public static void immobile(MovementInputUpdateEvent event) {
            if (cinema) {
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
            if (cinema) {
                event.setCanceled(true);
                event.setSwingHand(false);
            }
        }

        @SubscribeEvent
        public static void pasDeMain(RenderHandEvent event) {
            if (cinema) {
                event.setCanceled(true);
            }
        }

        @SubscribeEvent
        public static void pasDInterface(RenderGuiLayerEvent.Pre event) {
            if (cinema && !event.getName().equals(Cabane.id("operation_cinema"))) {
                event.setCanceled(true);
            }
        }

        private Evenements() {
        }
    }

    private ClientOperation() {
    }
}
