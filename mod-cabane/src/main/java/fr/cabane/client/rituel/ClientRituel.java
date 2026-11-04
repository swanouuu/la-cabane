package fr.cabane.client.rituel;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import fr.cabane.Cabane;
import fr.cabane.client.cinema.Effets;
import fr.cabane.client.cinema.Effets.Posture;
import fr.cabane.hachibi3.Hachibi3;
import fr.cabane.labo.Rituel;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.model.VillagerModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.client.resources.PlayerSkin;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
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
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Le rituel de scellement du Hachibi, chez chaque joueur du labo, d'après le rendu validé (docs/rituel, mêmes temps,
 * mêmes positions). Les quatre vrais joueurs sont cachés : leurs doubles (à leur skin) jouent la scène. Le scientifique
 * tranche la tête d'un bébé villageois (la redstone gicle, la tête roule), le sang coule dans le sceau, le scelleur fait
 * cinq signes, six chaînes sortent du ventre de l'allongé et vont attraper le Hachibi par le trou du mur, deviennent
 * jaunes et l'aspirent : il rapetisse, passe le trou et entre dans le ventre, où reste la marque des Jinchûriki.
 * Les quatre le voient en cinématique (bandes noires, plans de caméra, signes à l'écran) ; les autres, dans le monde.
 * Coordonnées : celles du labo (comme LaboBuilder), le sol de la grotte en y = 1.
 */
public final class ClientRituel {
    public static final double DUREE = 44;
    private static final double COUP = 5.75, POUF = 7.4, FUIN = 17.2, SORTIE = 18.2, ATTRAPE = 22.6, DORE = 26, TIRE = 33, TROU_T = 37, SCELLE = 38;
    private static final double Z = -37.5;
    private static final Vec3 P1 = new Vec3(32.5, 3, Z), P2 = new Vec3(33.05, 3, -39.35), P4 = new Vec3(33.05, 3, -35.65), VIL = new Vec3(34.0, 3, -39.5);
    /** L'allongé : les pieds à l'ouest, sur le dessus de l'autel (11/16 de bloc), la tête vers le trou du mur. */
    private static final Vec3 PIEDS3 = new Vec3(34.1, 3 + 11 / 16.0 + 0.118, Z);
    private static final Vec3 VENTRE = PIEDS3.add(0.95, 0.118, 0);
    private static final Vec3 TROU = new Vec3(48.5, 5.5, Z);
    /** Le cou du bébé (le haut de son corps), et sa tête quand elle est encore dessus. */
    private static final Vec3 COU = new Vec3(VIL.x, 3 + 24 * 0.46875 / 16, VIL.z);
    private static final Vec3 LUMIERE = new Vec3(-0.35, 0.8, 0.45).normalize();
    /** Les six chaînes : où elles passent le trou (hauteur, côté), dans l'ordre des prises du Hachibi. */
    private static final double[][] SORTIES = {{2.3, -1.2}, {0.2, 1.0}, {-2.6, -3.8}, {-2.8, 3.8}, {4.8, -2.4}, {4.6, 2.6}};

    private static final ResourceLocation SCEAU = Cabane.id("textures/item/sceau_rituel.png");
    static final ResourceLocation SPIRALE = Cabane.id("textures/entity/jinchuriki_spirale.png");
    static final ResourceLocation SIGNES_MARQUE = Cabane.id("textures/entity/jinchuriki_signes.png");
    private static final ResourceLocation PEAU_BEBE = ResourceLocation.withDefaultNamespace("textures/entity/villager/villager.png");
    private static final ResourceLocation PLAINE = ResourceLocation.withDefaultNamespace("textures/entity/villager/type/plains.png");
    private static final ResourceLocation POUSSIERE = ResourceLocation.withDefaultNamespace("textures/block/redstone_dust_dot.png");

    // ---- la scène en cours
    private static boolean actif;
    private static UUID[] acteurs = new UUID[4];
    private static Vec3 o = Vec3.ZERO;
    private static long debut;
    private static double avant;
    static boolean dessinEnCours;
    /** Le vol de la tête tranchée (calculé une fois) : x, y, z, angle, au 1/120 de seconde depuis le coup ; puis ses rebonds. */
    private static final List<double[]> VOL = new ArrayList<>();
    private static final List<double[]> CHOCS = new ArrayList<>();
    private static final Vec3 AXE_VOL = new Vec3(1, 0, 0.35).normalize();
    /** Les taches de redstone par terre : t, x, y, z, taille, angle. */
    private static final List<double[]> TACHES = new ArrayList<>();
    /** Les prises des chaînes sur le Hachibi, en coordonnées du monde, posées par son dessin à chaque image. */
    private static final Vec3[] PRISES = new Vec3[6], AXES = new Vec3[6];
    private static final double[] RAYONS = new double[6];
    private static long prisesImage = -1;
    private static Vec3 piedsBete;

    // ---- la cinématique, pour les quatre
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

    /** Du labo vers le monde. */
    private static Vec3 w(Vec3 p) {
        return p.add(o);
    }

    private static Vec3 w(double x, double y, double z) {
        return new Vec3(o.x + x, o.y + y, o.z + z);
    }

    /** Le lacet de Minecraft (en degrés) pour regarder de « de » vers « vers ». */
    private static float lacet(Vec3 de, Vec3 vers) {
        return (float) Math.toDegrees(Math.atan2(-(vers.x - de.x), vers.z - de.z));
    }

    // ================================================================ départ, arrêt

    public static void demarrer(Rituel.Debut d) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || d.acteurs().size() < 4) {
            return;
        }
        if (cinema) {
            sortir();
        }
        for (int i = 0; i < 4; i++) {
            acteurs[i] = d.acteurs().get(i);
        }
        BlockPos b = d.origine();
        o = new Vec3(b.getX(), b.getY(), b.getZ());
        debut = System.nanoTime();
        avant = 0;
        actif = true;
        prisesImage = -1;
        piedsBete = null;
        calculerVol();
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

    /** Pendant le rituel : la scène joue, et ce joueur est l'un des quatre (son vrai corps est caché). */
    public static boolean actif() {
        return actif;
    }

    private static boolean cache(Entity e) {
        if (!actif || dessinEnCours || !(e instanceof Player p)) {
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

    // ================================================================ la tête tranchée et la redstone

    /** La hauteur du sol sous un point : l'estrade (deux marches), l'autel, ou le sol de la grotte. */
    private static double sol(double x, double z) {
        int bx = Mth.floor(x), bz = Mth.floor(z);
        if (bx >= 34 && bx <= 35 && bz == -38) {
            return 3 + 11 / 16.0;
        }
        double d = Math.sqrt((bx - 34) * (bx - 34) + (bz + 38) * (bz + 38));
        return d <= 2.6 ? 3 : d <= 3.6 ? 2 : 1;
    }

    /** Elle part sur le côté en tournant, rebondit, roule et s'arrête. */
    private static void calculerVol() {
        VOL.clear();
        CHOCS.clear();
        TACHES.clear();
        double x = COU.x, y = COU.y + 0.22, z = COU.z, vx = 0.9, vy = 3.1, vz = -2.3, ang = 0, va = 11;
        double dt = 1 / 120.0;
        for (int i = 0; i <= 360; i++) {
            VOL.add(new double[] {x, y, z, ang});
            vy -= 12 * dt;
            x += vx * dt;
            y += vy * dt;
            z += vz * dt;
            ang += va * dt;
            double s = sol(x, z) + 0.21;
            if (y < s) {
                y = s;
                if (vy < -1.5) {
                    CHOCS.add(new double[] {COUP + i / 120.0, x, s - 0.2, z});
                    vx *= 0.6;
                    vz *= 0.6;
                    vy = -vy * 0.32;
                    va *= 0.5;
                } else {
                    vx *= 0.9;
                    vz *= 0.9;
                    vy = 0;
                    va *= 0.9;
                }
            }
        }
        Random r = new Random(61);
        for (int i = 0; i < 7; i++) {
            double tx = VIL.x + (r.nextDouble() - 0.5) * 1.8, tz = VIL.z + (r.nextDouble() - 0.5) * 1.8;
            TACHES.add(new double[] {COUP + 0.3 + r.nextDouble() * 0.6, tx, sol(tx, tz) + 0.012 + i * 0.001, tz, 0.5 + r.nextDouble() * 0.35, r.nextDouble() * 6});
        }
        for (int i = 0; i < CHOCS.size(); i++) {
            double[] c = CHOCS.get(i);
            TACHES.add(new double[] {c[0], c[1], c[2] + 0.02 + i * 0.001, c[3], 0.65, i * 2});
        }
    }

    private static double[] vol(double t) {
        return VOL.get(Mth.clamp((int) Math.round((t - COUP) * 120), 0, VOL.size() - 1));
    }

    private static Vec3 tete(double t) {
        double[] v = vol(t);
        return new Vec3(v[0], v[1], v[2]);
    }

    // ================================================================ les doubles

    private static Posture tete(Posture p, float x, float y) {
        return new Posture(x, y, p.penche(), p.brasDX(), p.brasDY(), p.brasDZ(), p.brasGX(), p.brasGY(), p.brasGZ(), p.jambeDX(), p.jambeDZ(),
                p.jambeGX(), p.jambeGZ());
    }

    /** La posture avec la tête tournée vers un point (sans dépasser ce qu'un cou peut faire). */
    private static Posture regarder(Posture p, Vec3 pieds, float lacetCorps, Vec3 cible, float k) {
        Vec3 d = cible.subtract(pieds.add(0, 1.52, 0));
        float lac = Mth.clamp(Mth.wrapDegrees(lacet(Vec3.ZERO, d) - lacetCorps), -70, 70);
        float tangage = (float) Math.toDegrees(-Math.atan2(d.y, Math.sqrt(d.x * d.x + d.z * d.z)));
        return tete(p, (float) Math.toRadians(Mth.clamp(tangage, -50, 60)) * k, (float) Math.toRadians(lac) * k);
    }

    private static Posture signe(float dx, float dy, float dz, float gx, float gy, float gz) {
        return new Posture(0.25F, 0, 3, dx, dy, dz, gx, gy, gz, 0, 0.03F, 0, -0.03F);
    }

    private static final Posture REPOS = Posture.DEBOUT;
    private static final double[] T_SIGNES = {11.4, 12.6, 13.8, 15.0, 16.2};
    private static final String[] NOMS_SIGNES = {"Sanglier", "Chien", "Oiseau", "Singe", "Bélier"};
    // (un angle Y positif tourne le bras vers la droite du personnage : les mains se joignent avec le droit en négatif, le gauche en positif)
    private static final Posture[] SIGNES = {signe(-1.15F, -0.5F, 0, -1.15F, 0.5F, 0), signe(-1.35F, -0.6F, 0.25F, -1.35F, 0.6F, -0.25F),
            signe(-1.0F, -0.45F, -0.3F, -1.0F, 0.45F, 0.3F), signe(-1.45F, -0.55F, 0.1F, -1.25F, 0.55F, -0.1F), signe(-1.6F, -0.62F, 0, -1.6F, 0.62F, 0)};
    private static final Posture POUSSE = new Posture(0.35F, 0, 8, -1.2F, -0.15F, 0, -1.2F, 0.15F, 0, 0, 0.03F, 0, -0.03F);
    private static final Posture ARME = new Posture(0.45F, 0, 12, -1.3F, 0.9F, 0, -0.2F, 0.1F, -0.1F, 0, 0.03F, 0, -0.03F);
    private static final Posture TRANCHE = new Posture(0.5F, 0, 14, -1.3F, -0.8F, 0, -0.2F, 0.1F, -0.1F, 0, 0.03F, 0, -0.03F);
    private static final Posture PROTEGE = new Posture(0.1F, 0, -6, -1.9F, -0.4F, 0, -0.3F, 0, -0.1F, 0, 0.03F, 0, -0.03F);
    private static final Posture ECARTE = new Posture(-0.35F, 0, 0, 0, 0, 0.45F, 0, 0, -0.45F, 0, 0.03F, 0, -0.03F);

    private static Posture scelleur(double t) {
        if (t < 11) {
            return REPOS;
        }
        if (t < FUIN) {
            int i = SIGNES.length - 1;
            while (i > 0 && t < T_SIGNES[i]) {
                i--;
            }
            Posture de = i == 0 ? REPOS : SIGNES[i - 1];
            float k = (float) (i == 0 ? lisse(entre(t, 11, 11.4)) : lisse(entre(t, T_SIGNES[i], T_SIGNES[i] + 0.14)));
            return de.vers(SIGNES[i], k);
        }
        Posture p = SIGNES[4].vers(POUSSE, (float) lisse(entre(t, FUIN, FUIN + 0.15)));
        if (t > 32 && t < 38) {
            // il tient bon pendant que la bête est tirée
            p = p.vers(SIGNES[3], (float) (0.12 + 0.12 * Math.sin(t * 40)));
        }
        return t > 38.6 ? p.vers(REPOS, (float) lisse(entre(t, 38.6, 39.6))) : p;
    }

    private static Posture docteur(double t) {
        if (t < 4.6) {
            return REPOS;
        }
        if (t < 5.55) {
            return REPOS.vers(ARME, (float) lisse(entre(t, 4.6, 5.3)));
        }
        if (t < 5.95) {
            return ARME.vers(TRANCHE, (float) lisse(entre(t, 5.6, 5.9)));
        }
        return TRANCHE.vers(REPOS, (float) lisse(entre(t, 6.6, 8.2)));
    }

    /** Le lacet du scientifique : vers l'autel, vers le bébé le temps du sacrifice, puis de nouveau vers l'autel. */
    private static float lacetDocteur(double t) {
        float autel = lacet(P2, new Vec3(35.5, 3, Z)), bebe = lacet(P2, VIL);
        float k = (float) (lisse(entre(t, 4, 4.6)) * (1 - lisse(entre(t, 8.4, 9.4))));
        return autel + Mth.wrapDegrees(bebe - autel) * k;
    }

    /** Où regardent ceux qui regardent : l'autel, le bébé (puis sa tête qui vole), le sceau, les signes, le trou, la bête, le ventre. */
    private static Vec3 focus(double t, EtatBete b) {
        if (t < 4) {
            return w(35, 4, Z);
        }
        if (t < 7.5) {
            return w(t < COUP ? COU : tete(t));
        }
        if (t < 11) {
            return w(34.5, 1, -41.5);
        }
        if (t < 18) {
            return w(33.2, 4.2, Z);
        }
        if (t < 22.5) {
            return w(TROU.lerp(new Vec3(115, 6, Z), entre(t, 18.5, 22)));
        }
        if (t < TIRE) {
            return w(112, 4, Z);
        }
        return t < SCELLE && b != null ? b.pos : w(VENTRE);
    }

    /** La lumière du monde à hauteur de torse, pour que les doubles soient éclairés comme les vrais joueurs. */
    private static int lumiere(Vec3 pieds) {
        return LevelRenderer.getLightColor(Minecraft.getInstance().level, BlockPos.containing(pieds.add(0, 1.2, 0)));
    }

    private static ResourceLocation skin(UUID u) {
        Player p = Minecraft.getInstance().level.getPlayerByUUID(u);
        return p instanceof AbstractClientPlayer c ? c.getSkin().texture() : DefaultPlayerSkin.getDefaultTexture();
    }

    private static boolean fin(UUID u) {
        Player p = Minecraft.getInstance().level.getPlayerByUUID(u);
        return p instanceof AbstractClientPlayer c && c.getSkin().model() == PlayerSkin.Model.SLIM;
    }

    /** La main droite d'un double d'{@link Effets} et la direction de son bras (vers la main), dans le monde. */
    private static Vec3[] mainDroite(Vec3 pieds, float lac, Posture p) {
        Matrix4f m = new Matrix4f().translate((float) pieds.x, (float) pieds.y, (float) pieds.z)
                .rotateY((float) Math.toRadians(180 - lac))
                .translate(0, 0.9F, 0).rotateX((float) Math.toRadians(-p.penche())).translate(0, -0.9F, 0)
                .scale(-0.9375F, -0.9375F, 0.9375F).translate(0, -1.501F, 0)
                .translate(-5 / 16F, 2 / 16F, 0).rotateZYX(p.brasDZ(), p.brasDY(), p.brasDX());
        Vector3f main = m.transformPosition(new Vector3f(-1 / 16F, 10 / 16F, 0));
        Vector3f loin = m.transformPosition(new Vector3f(-1 / 16F, 20 / 16F, 0));
        Vec3 a = new Vec3(main.x, main.y, main.z);
        return new Vec3[] {a, new Vec3(loin.x, loin.y, loin.z).subtract(a).normalize()};
    }

    private static void doubles(PoseStack pose, MultiBufferSource buffers, Vec3 cam, double t, EtatBete b) {
        Vec3 f = focus(t, b);
        // le scelleur, au pied de l'autel
        Vec3 p1 = w(P1);
        float l1 = lacet(P1, new Vec3(36, 3, Z));
        Posture s = scelleur(t);
        Doubles.dessiner(pose, buffers, skin(acteurs[0]), fin(acteurs[0]), p1.subtract(cam), l1, s, lumiere(p1));
        // le scientifique : il regarde le bébé, puis sa tête qui vole, puis la scène
        Vec3 p2 = w(P2);
        float l2 = lacetDocteur(t);
        Posture d = docteur(t);
        Vec3 regard2 = t < 4 ? w(35, 4, Z) : t < 6.1 ? w(COU.add(0, 0.2, 0)) : t < 9.5 ? w(tete(t)) : f;
        d = regarder(d, p2, l2, regard2, 0.75F);
        Doubles.dessiner(pose, buffers, skin(acteurs[1]), fin(acteurs[1]), p2.subtract(cam), l2, d, lumiere(p2));
        // le spectateur : il suit tout des yeux, et recule et se protège quand les chaînes jaillissent et au scellement
        double recul = bosse(t, 18.1, 0.25, 1.2, 1.0) * 0.18 + bosse(t, 38.05, 0.2, 1.0, 1.4) * 0.22;
        Vec3 p4 = w(P4).add(-recul * 0.6, 0, recul);
        float l4 = lacet(P4, new Vec3(36, 3, -37));
        float prot = (float) Math.max(bosse(t, 18.1, 0.2, 0.9, 0.6), bosse(t, 38.05, 0.15, 0.9, 0.8));
        Posture q = regarder(REPOS.vers(PROTEGE, prot), p4, l4, f, 1);
        Doubles.dessiner(pose, buffers, skin(acteurs[3]), fin(acteurs[3]), p4.subtract(cam), l4, q, lumiere(p4));
        // l'allongé : couché sur le dos, la tête vers le trou ; il se cambre et écarte les bras quand les chaînes sortent
        pose.pushPose();
        Vec3 p3 = w(PIEDS3).subtract(cam);
        pose.translate(p3.x, p3.y, p3.z);
        pose.mulPose(new Quaternionf().setFromNormalized(new Matrix3f(0, 0, 1, 1, 0, 0, 0, 1, 0)));
        float k = (float) bosse(t, 17.9, 0.3, 19.6, 1.2);
        Doubles.dessiner(pose, buffers, skin(acteurs[2]), fin(acteurs[2]), Vec3.ZERO, 0, REPOS.vers(ECARTE, k), lumiere(w(VENTRE)));
        pose.popPose();
        // la lame du scientifique, dans le prolongement de son bras
        if (t >= 3.6 && t < 10) {
            Vec3[] m = mainDroite(p2, l2, d);
            VertexConsumer c = buffers.getBuffer(RenderType.debugQuads());
            Matrix4f mat = pose.last().pose();
            Vec3 cote = m[1].cross(new Vec3(0, 1, 0));
            cote = cote.lengthSqr() < 1.0E-4 ? new Vec3(1, 0, 0) : cote.normalize();
            Vec3 n = m[1].cross(cote).normalize();
            pave(c, mat, m[0].add(m[1].scale(0.36)).subtract(cam), m[1], cote, n, 0.31, 0.04, 0.01, 0.86F, 0.88F, 0.92F);
            pave(c, mat, m[0].subtract(cam), m[1], cote, n, 0.07, 0.03, 0.03, 0.25F, 0.16F, 0.1F);
            pave(c, mat, m[0].add(m[1].scale(0.05)).subtract(cam), cote, m[1], n, 0.1, 0.02, 0.03, 0.42F, 0.42F, 0.46F);
        }
    }

    // ================================================================ le bébé villageois

    private static VillagerModel<Entity> modeleBebe;

    private static VillagerModel<Entity> modeleBebe() {
        if (modeleBebe == null) {
            modeleBebe = new VillagerModel<>(Minecraft.getInstance().getEntityModels().bakeLayer(ModelLayers.VILLAGER));
        }
        return modeleBebe;
    }

    private static void bebe(PoseStack pose, MultiBufferSource buffers, Vec3 cam, double t) {
        if (t >= POUF) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        VillagerModel<Entity> m = modeleBebe();
        ModelPart tete = m.getHead();
        float lac = lacet(VIL, P2);
        int lumiere = LevelRenderer.getLightColor(mc.level, BlockPos.containing(w(VIL.add(0, 0.5, 0))));
        int marque = t >= COUP ? OverlayTexture.pack(OverlayTexture.u(0), OverlayTexture.v(true)) : OverlayTexture.NO_OVERLAY;
        double mort = entre(t, 6.35, 6.95);
        Vec3 p = w(VIL).subtract(cam);
        pose.pushPose();
        pose.translate(p.x, p.y, p.z);
        pose.mulPose(Axis.YP.rotationDegrees(180 - lac));
        if (mort > 0) {
            pose.mulPose(Axis.ZP.rotationDegrees((float) (Math.sqrt(mort) * 90)));
        }
        pose.scale(-1, -1, 1);
        pose.scale(0.46875F, 0.46875F, 0.46875F);
        pose.translate(0, -1.501, 0);
        tete.visible = t < COUP;
        tete.xScale = tete.yScale = tete.zScale = 1.5F;
        tete.xRot = 0;
        tete.yRot = (float) (Math.sin(t * 0.8) * 0.5 * (1 - lisse(entre(t, 4.4, 4.9))));
        m.renderToBuffer(pose, buffers.getBuffer(RenderType.entityCutoutNoCull(PEAU_BEBE)), lumiere, marque, 0xFFFFFFFF);
        m.renderToBuffer(pose, buffers.getBuffer(RenderType.entityCutoutNoCull(PLAINE)), lumiere, marque, 0xFFFFFFFF);
        pose.popPose();
        tete.visible = true;
        // le cou tranché, rouge, tant que le corps tient debout
        if (t >= COUP && t < 6.35) {
            Vec3 c = w(COU).subtract(cam);
            pave(buffers.getBuffer(RenderType.debugQuads()), pose.last().pose(), c.add(0, 0.01, 0), new Vec3(1, 0, 0), new Vec3(0, 1, 0), new Vec3(0, 0, 1),
                    0.12, 0.012, 0.09, 0.62F, 0.02F, 0.02F);
        }
        // la tête, seule, qui vole et roule
        if (t >= COUP) {
            double[] v = vol(t);
            Vec3 h = w(v[0], v[1], v[2]).subtract(cam);
            pose.pushPose();
            pose.translate(h.x, h.y, h.z);
            pose.mulPose(new Quaternionf().rotateAxis((float) v[3], (float) AXE_VOL.x, (float) AXE_VOL.y, (float) AXE_VOL.z));
            pose.mulPose(Axis.YP.rotationDegrees(180 - lac));
            pose.scale(-1, -1, 1);
            pose.scale(0.46875F, 0.46875F, 0.46875F);
            pose.translate(0, 7.5 / 16, 0);
            tete.yRot = 0;
            int l = LevelRenderer.getLightColor(mc.level, BlockPos.containing(w(v[0], v[1] + 0.3, v[2])));
            tete.render(pose, buffers.getBuffer(RenderType.entityCutoutNoCull(PEAU_BEBE)), l, marque, 0xFFFFFFFF);
            tete.render(pose, buffers.getBuffer(RenderType.entityCutoutNoCull(PLAINE)), l, marque, 0xFFFFFFFF);
            pose.popPose();
        }
        tete.xScale = tete.yScale = tete.zScale = 1;
    }

    /** Les gouttes de redstone : la giclée du cou, la traînée de la tête, puis les filets qui courent dans les anneaux du sceau. */
    private static void redstone(PoseStack pose, MultiBufferSource buffers, Vec3 cam, double t) {
        if (t < COUP || t > 12.2) {
            return;
        }
        VertexConsumer c = buffers.getBuffer(RenderType.debugQuads());
        Matrix4f m = pose.last().pose();
        Random r = new Random(5);
        for (int i = 0; i < 320; i++) {
            double emis = COUP + Math.pow(r.nextDouble(), 1.6) * 0.6, force = 1 - (emis - COUP) / 0.6;
            double vx = (r.nextDouble() - 0.5) * 1.6, vy = 0.6 + 1.6 + r.nextDouble() * 2.8 * force, vz = (r.nextDouble() - 0.5) * 1.6;
            boolean sombre = r.nextDouble() < 0.3;
            double a = t - emis;
            if (a < 0 || a > 1.6) {
                continue;
            }
            double x = COU.x + vx * a, y = COU.y + 0.05 + vy * a - 6 * a * a, z = COU.z + vz * a;
            y = Math.max(y, sol(x, z) + 0.02);
            float k = (float) (a > 1.2 ? (1.6 - a) / 0.4 : 1);
            goutte(c, m, w(x, y, z).subtract(cam), 0.035, (sombre ? 0.55F : 1F) * k, 0.02F * k, 0.02F * k);
        }
        double premier = CHOCS.isEmpty() ? COUP + 1 : CHOCS.get(0)[0];
        for (int k = 0; k < 22; k++) {
            double tt = t - k * 0.035;
            if (tt < COUP || tt > premier + 0.2 || t - tt > 0.7) {
                continue;
            }
            double[] v = vol(tt);
            Random j = new Random(k * 7L + Math.round(tt * 50));
            goutte(c, m, w(v[0] + (j.nextDouble() - 0.5) * 0.15, v[1] - 0.1 + (j.nextDouble() - 0.5) * 0.15 - 2 * (t - tt) * (t - tt),
                    v[2] + (j.nextDouble() - 0.5) * 0.15).subtract(cam), 0.035, 1F, 0.03F, 0.03F);
        }
        // les filets : ils descendent l'estrade, puis courent dans les trois anneaux du sceau
        if (t < 6.6) {
            return;
        }
        double[] anneaux = {4.75, 6.3, 7.95};
        double a0 = Math.atan2(VIL.z - Z, VIL.x - 34.5), fondu = 1 - entre(t, 10.6, 12);
        Random h = new Random(7);
        for (int i = 0; i < 420; i++) {
            double ann = anneaux[i % 3], sens = i % 2 == 1 ? 1 : -1, emis = 6.6 + h.nextDouble() * 2.6, vit = 1.0 + h.nextDouble() * 0.5;
            double v = t - emis;
            if (v < 0 || fondu <= 0) {
                continue;
            }
            double x, y, z;
            if (v < 0.7) {
                double u = v / 0.7, rr = 2 + (ann - 2) * u;
                x = 34.5 + Math.cos(a0) * rr;
                z = Z + Math.sin(a0) * rr;
                y = 3.02 - 2 * Math.min(1, u * 1.6);
            } else {
                double th = a0 + sens * Math.min(Math.PI, (v - 0.7) * vit * 2.6 / ann * 1.5);
                x = 34.5 + Math.cos(th) * ann;
                z = Z + Math.sin(th) * ann;
                y = 1.05;
            }
            float k = (float) (0.6 + 0.4 * fondu);
            goutte(c, m, w(x, y, z).subtract(cam), 0.03, 0.62F * k, 0.02F * k, 0.02F * k);
        }
    }

    // ================================================================ le sceau du sol, la marque du ventre, les taches

    /** Un carré texturé : son centre, ses deux demi-côtés (u vers la droite de l'image, v vers le haut). */
    static void carre(PoseStack pose, VertexConsumer c, Vec3 centre, Vec3 u, Vec3 v, float r, float g, float b, float a, int lumiere) {
        PoseStack.Pose p = pose.last();
        Vec3 n = u.cross(v).normalize();
        Vec3[] coins = {centre.subtract(u).subtract(v), centre.add(u).subtract(v), centre.add(u).add(v), centre.subtract(u).add(v)};
        float[][] uv = {{0, 1}, {1, 1}, {1, 0}, {0, 0}};
        for (int i = 0; i < 4; i++) {
            c.addVertex(p, (float) coins[i].x, (float) coins[i].y, (float) coins[i].z).setColor(r, g, b, a).setUv(uv[i][0], uv[i][1])
                    .setOverlay(OverlayTexture.NO_OVERLAY).setLight(lumiere).setNormal(p, (float) n.x, (float) n.y, (float) n.z);
        }
    }

    /**
     * La marque des Jinchûriki sur le ventre de l'allongé : rouge pendant les signes, dorée quand les chaînes sortent et au
     * scellement, puis une marque d'encre sombre qui reste.
     */
    private static void marques(PoseStack pose, MultiBufferSource buffers, Vec3 cam, double t) {
        Minecraft mc = Minecraft.getInstance();
        // le sceau du sol : le sang l'allume, les signes le font tourner et battre
        double plein = entre(t, 7.2, 10.8) * (1 - 0.75 * entre(t, 39, 43));
        double bat = battement(t);
        float a = (float) Math.min(1, 0.55 * plein + 0.4 * bat + 0.25 * entre(t, 11, 17) * (1 - entre(t, 38.5, 41)));
        if (a > 0.01F) {
            double ang = -0.3 * Math.max(0, Math.min(t, 38.5) - 11);
            Vec3 u = new Vec3(Math.cos(ang), 0, Math.sin(ang)).scale(8.5), v = new Vec3(-Math.sin(ang), 0, Math.cos(ang)).scale(8.5);
            carre(pose, buffers.getBuffer(RenderType.entityTranslucentEmissive(SCEAU)), w(34.5, 1.035, Z).subtract(cam), u, v.reverse(), 1F, 0.32F, 0.28F, a,
                    0xF000F0);
        }
        // les taches de redstone, par terre
        VertexConsumer pous = buffers.getBuffer(RenderType.entityTranslucent(POUSSIERE));
        for (double[] ta : TACHES) {
            float op = (float) (0.95 * entre(t, ta[0], ta[0] + 0.25));
            if (op <= 0.01F) {
                continue;
            }
            Vec3 c = w(ta[1], ta[2], ta[3]);
            double s = ta[4] / 2;
            Vec3 u = new Vec3(Math.cos(ta[5]), 0, Math.sin(ta[5])).scale(s), v = new Vec3(-Math.sin(ta[5]), 0, Math.cos(ta[5])).scale(s);
            carre(pose, pous, c.subtract(cam), u, v.reverse(), 0.85F, 0.02F, 0.02F, op, LevelRenderer.getLightColor(mc.level, BlockPos.containing(c.add(0, 0.3, 0))));
        }
        // la marque du ventre : la tête de l'allongé est à l'est, le haut de la marque aussi
        double vRouge = 0.6 * entre(t, FUIN, FUIN + 0.3), vOr = entre(t, DORE, 31), eclat = bosse(t, SCELLE, 0.05, 0.5, 1.5);
        double vif = Math.min(1, Math.max(vRouge, vOr) * (1 - entre(t, 39.5, 42.5)) + 1.5 * eclat);
        double noir = 0.9 * entre(t, 39.5, 42);
        // au-dessus du t-shirt (la couche extérieure du skin dépasse de 1/64 de bloc)
        Vec3 c = w(VENTRE).add(0, 0.024, 0).subtract(cam);
        Vec3 u = new Vec3(0, 0, -0.22), v = new Vec3(0.22, 0, 0);
        if (vif > 0.01) {
            float g = (float) (t < DORE ? 0.3 + 0.5 * entre(t, SORTIE, DORE) : 0.82), b = t < DORE ? 0.22F : 0.25F;
            for (ResourceLocation tex : new ResourceLocation[] {SPIRALE, SIGNES_MARQUE}) {
                carre(pose, buffers.getBuffer(RenderType.entityTranslucentEmissive(tex)), c, u, v, 1F, g, b, (float) Math.min(1, vif), 0xF000F0);
            }
        }
        if (noir > 0.01) {
            int l = LevelRenderer.getLightColor(mc.level, BlockPos.containing(w(VENTRE).add(0, 0.5, 0)));
            for (ResourceLocation tex : new ResourceLocation[] {SPIRALE, SIGNES_MARQUE}) {
                carre(pose, buffers.getBuffer(RenderType.entityTranslucent(tex)), c.add(0, 0.002, 0), u, v, 0.1F, 0.03F, 0.03F, (float) noir, l);
            }
        }
    }

    private static double battement(double t) {
        double b = 0;
        for (double h : T_SIGNES) {
            b = Math.max(b, bosse(t, h, 0.05, 0.05, 0.5));
        }
        return Math.max(b, Math.max(1.6 * bosse(t, FUIN, 0.05, 0.2, 0.8), 0.8 * bosse(t, 18, 0.1, 0.3, 1)));
    }

    // ================================================================ le Hachibi

    /** Ce que le dessin du Hachibi doit faire pendant le rituel (voir RenduHachibi3). */
    public static final class EtatBete {
        /** Il est dans le ventre : on ne le dessine plus. */
        public boolean cachee;
        /** Il est tiré vers le trou : on le déplace, le rapetisse et le fait tourner autour de son centre. */
        public boolean tire;
        /** Le centre de la bête au repos et là où il est maintenant (dans le monde), sa taille, ses rotations. */
        public Vec3 centreRepos = Vec3.ZERO;
        public Vec3 pos = Vec3.ZERO;
        public float echelle = 1;
        public float vrille;
        public float cri;
        public float agite;
        /** De 0 (rose) à 1 (doré, comme les chaînes). */
        public float dore;
        /** Le temps du rituel (pour casser ses chaînes de fer au bon moment). */
        public double t;
    }

    /** L'état de la bête pour ce dessin, ou null s'il n'y a pas de rituel. */
    public static EtatBete bete(Hachibi3 h) {
        if (!actif) {
            return null;
        }
        double t = t();
        EtatBete b = new EtatBete();
        b.t = t;
        b.centreRepos = h.position().add(9.5, 34, 0);
        b.pos = b.centreRepos;
        Vec3 trou = w(TROU);
        if (t >= SCELLE) {
            b.cachee = true;
        } else if (t >= TROU_T) {
            double v = Math.pow(entre(t, TROU_T, SCELLE), 1.5);
            b.tire = true;
            b.pos = trou.lerp(w(VENTRE).add(0, 0.2, 0), v);
            b.echelle = (float) (0.05 * Math.pow(1 - v, 1.5));
            b.vrille = (float) (1.3 + v);
        } else if (t >= TIRE) {
            double e = Math.pow(entre(t, TIRE, TROU_T), 2.2);
            b.tire = true;
            b.pos = b.centreRepos.lerp(trou, e);
            b.echelle = (float) (0.05 + 0.95 * Math.pow(1 - e, 1.25));
            b.vrille = (float) (e * 1.3);
        }
        b.cri = (float) Math.max(bosse(t, ATTRAPE + 0.1, 0.3, 1.6, 0.9), bosse(t, 31.9, 0.3, 4.6, 0.6));
        b.agite = (float) (t >= 32 ? 1 : 0.35 * entre(t, DORE, 30));
        b.dore = (float) entre(t, 30.5, 34);
        piedsBete = h.position();
        return b;
    }

    /** Le dessin du Hachibi donne où sont ses prises (le collier, le torse, les menottes, deux tentacules) à cette image. */
    public static void prise(int i, Vec3 monde, Vec3 axe, double rayon) {
        PRISES[i] = monde;
        AXES[i] = axe;
        RAYONS[i] = rayon;
        prisesImage = image;
    }

    private static long image;

    // ================================================================ les chaînes

    /** Un pavé plein d'une couleur (ombré selon ses faces) : son centre, ses axes et ses demi-tailles. */
    static void pave(VertexConsumer c, Matrix4f m, Vec3 o, Vec3 x, Vec3 y, Vec3 z, double hx, double hy, double hz, float r, float g, float b) {
        Vec3[] axes = {x, y, z};
        double[] demis = {hx, hy, hz};
        for (int a = 0; a < 3; a++) {
            Vec3 n = axes[a], u = axes[(a + 1) % 3], v = axes[(a + 2) % 3];
            double hn = demis[a], hu = demis[(a + 1) % 3], hv = demis[(a + 2) % 3];
            for (int s = -1; s <= 1; s += 2) {
                float k = (float) (0.6 + 0.4 * Math.abs(n.dot(LUMIERE)) + (s > 0 && n.y > 0.5 ? 0.1 : 0));
                Vec3 cf = o.add(n.scale(s * hn));
                Vec3[] coins = {cf.add(u.scale(-hu)).add(v.scale(-hv)), cf.add(u.scale(hu)).add(v.scale(-hv)), cf.add(u.scale(hu)).add(v.scale(hv)),
                        cf.add(u.scale(-hu)).add(v.scale(hv))};
                for (Vec3 p : coins) {
                    c.addVertex(m, (float) p.x, (float) p.y, (float) p.z).setColor(Math.min(1, r * k), Math.min(1, g * k), Math.min(1, b * k), 1F);
                }
            }
        }
    }

    private static void goutte(VertexConsumer c, Matrix4f m, Vec3 p, double r, float rouge, float vert, float bleu) {
        pave(c, m, p, new Vec3(1, 0, 0), new Vec3(0, 1, 0), new Vec3(0, 0, 1), r, r, r, rouge, vert, bleu);
    }

    /** Une courbe de Catmull-Rom (centripète) par quatre points, découpée en petits bouts, avec la longueur parcourue. */
    private static final class Chemin {
        final List<Vec3> pts = new ArrayList<>();
        final List<Double> longueurs = new ArrayList<>();
        double longueur;

        Chemin(Vec3... p) {
            Vec3[] e = new Vec3[p.length + 2];
            e[0] = p[0].scale(2).subtract(p[1]);
            System.arraycopy(p, 0, e, 1, p.length);
            e[p.length + 1] = p[p.length - 1].scale(2).subtract(p[p.length - 2]);
            for (int k = 0; k + 3 < e.length; k++) {
                for (int i = k == 0 ? 0 : 1; i <= 24; i++) {
                    ajouter(catmull(e[k], e[k + 1], e[k + 2], e[k + 3], i / 24.0));
                }
            }
        }

        private void ajouter(Vec3 p) {
            if (!pts.isEmpty()) {
                longueur += pts.getLast().distanceTo(p);
            }
            pts.add(p);
            longueurs.add(longueur);
        }

        Vec3 a(double s) {
            if (s <= 0) {
                return pts.getFirst();
            }
            for (int i = 1; i < pts.size(); i++) {
                if (longueurs.get(i) >= s) {
                    double l0 = longueurs.get(i - 1), l1 = longueurs.get(i);
                    return pts.get(i - 1).lerp(pts.get(i), l1 - l0 < 1.0E-6 ? 0 : (s - l0) / (l1 - l0));
                }
            }
            return pts.getLast();
        }

        private static Vec3 catmull(Vec3 p0, Vec3 p1, Vec3 p2, Vec3 p3, double u) {
            double t0 = 0, t1 = t0 + Math.sqrt(Math.max(1.0E-4, p0.distanceTo(p1))), t2 = t1 + Math.sqrt(Math.max(1.0E-4, p1.distanceTo(p2)));
            double t3 = t2 + Math.sqrt(Math.max(1.0E-4, p2.distanceTo(p3)));
            double t = t1 + u * (t2 - t1);
            Vec3 a1 = p0.scale((t1 - t) / (t1 - t0)).add(p1.scale((t - t0) / (t1 - t0)));
            Vec3 a2 = p1.scale((t2 - t) / (t2 - t1)).add(p2.scale((t - t1) / (t2 - t1)));
            Vec3 a3 = p2.scale((t3 - t) / (t3 - t2)).add(p3.scale((t - t2) / (t3 - t2)));
            Vec3 b1 = a1.scale((t2 - t) / (t2 - t0)).add(a2.scale((t - t0) / (t2 - t0)));
            Vec3 b2 = a2.scale((t3 - t) / (t3 - t1)).add(a3.scale((t - t1) / (t3 - t1)));
            return b1.scale((t2 - t) / (t2 - t1)).add(b2.scale((t - t1) / (t2 - t1)));
        }
    }

    /**
     * Les six chaînes : du ventre, elles montent, passent le trou du mur et s'enroulent autour de leur prise sur le Hachibi.
     * Elles grossissent en s'éloignant (on les voit de loin), et l'or remonte chacune depuis le ventre.
     */
    private static void chaines(PoseStack pose, MultiBufferSource buffers, Vec3 cam, double t, EtatBete b) {
        if (t < SORTIE || t >= SCELLE || b == null || prisesImage < image - 2) {
            return;
        }
        VertexConsumer c = buffers.getBuffer(RenderType.debugQuads());
        Matrix4f m = pose.last().pose();
        double fusion = entre(t, 36.6, 37.2);
        Vec3 ventre = w(VENTRE);
        for (int i = 0; i < 6; i++) {
            double depart = SORTIE + i * 0.17;
            if (t < depart || PRISES[i] == null) {
                continue;
            }
            Vec3 A = PRISES[i], axe = AXES[i];
            double R = RAYONS[i] * b.echelle;
            Vec3 sortie = w(TROU.add(-1, SORTIES[i][0], SORTIES[i][1]));
            Vec3 e1 = sortie.subtract(A);
            e1 = e1.subtract(axe.scale(e1.dot(axe)));
            e1 = e1.lengthSqr() < 1.0E-6 ? new Vec3(0, 1, 0) : e1.normalize();
            Vec3 e2 = axe.cross(e1);
            Vec3 entree = A.add(e1.scale(R));
            Vec3 milieu = ventre.lerp(entree, 0.5);
            Vec3 haut = ventre.add(0.5, 1.8, (i - 2.5) * 0.25);
            Chemin ch = new Chemin(ventre, haut.lerp(milieu, fusion), sortie.lerp(milieu, fusion), entree);
            double lr = Math.max(0.01, ch.longueur), tours = 1.6, pas = R * 0.42, lc = 2 * Math.PI * Math.max(R, 0.01) * tours, l = lr + lc;
            final double rr = R;
            final Vec3 f1 = e1, f2 = e2;
            java.util.function.DoubleFunction<Vec3> chemin = s -> {
                if (s <= lr) {
                    return ch.a(s);
                }
                double th = (s - lr) / Math.max(rr, 1.0E-3);
                return A.add(f1.scale(rr * Math.cos(th))).add(f2.scale(rr * Math.sin(th))).add(axe.scale(th / (2 * Math.PI) * pas));
            };
            double gros = Math.max(1, 3.2 * Math.min(1, b.echelle * 1.4));
            double front = lisse(entre(t, DORE + i * 0.12, 31 + i * 0.12));
            double jusqua = (t - depart) * 48 * (0.55 + 0.45 * entre(t, depart, depart + 0.5));
            double fin = Math.min(l, jusqua), s = 0;
            int k = 0;
            Vec3 lueurPrec = null;
            while (s < fin) {
                Vec3 a = chemin.apply(s), d = chemin.apply(Math.min(l, s + 0.08)).subtract(a);
                d = d.lengthSqr() < 1.0E-8 ? new Vec3(0, 1, 0) : d.normalize();
                double e = s < lr ? 0.55 + (gros - 0.55) * Math.pow(s / lr, 0.8) : gros;
                Vec3 u = d.cross(Math.abs(d.y) < 0.9 ? new Vec3(0, 1, 0) : new Vec3(1, 0, 0)).normalize();
                if (k % 2 == 1) {
                    u = d.cross(u).normalize();
                }
                Vec3 n = d.cross(u).normalize();
                boolean or = s / l < front;
                float rc = or ? 1F : 0.37F, gc = or ? 0.84F : 0.37F, bc = or ? 0.32F : 0.42F;
                Vec3 p = a.subtract(cam);
                // un maillon : deux barres le long de la chaîne, deux en travers aux bouts
                pave(c, m, p.add(u.scale(0.15 * e)), d, u, n, 0.25 * e, 0.045 * e, 0.045 * e, rc, gc, bc);
                pave(c, m, p.add(u.scale(-0.15 * e)), d, u, n, 0.25 * e, 0.045 * e, 0.045 * e, rc, gc, bc);
                pave(c, m, p.add(d.scale(0.21 * e)), u, d, n, 0.19 * e, 0.045 * e, 0.045 * e, rc, gc, bc);
                pave(c, m, p.add(d.scale(-0.21 * e)), u, d, n, 0.19 * e, 0.045 * e, 0.045 * e, rc, gc, bc);
                if (k % 3 == 0) {
                    if (lueurPrec != null) {
                        Effets.tube(pose, buffers, lueurPrec, p, 0.22 * e, or ? 1F : 0.6F, or ? 0.75F : 0.06F, or ? 0.2F : 0.05F, or ? 0.2F : 0.1F);
                        c = buffers.getBuffer(RenderType.debugQuads());
                    }
                    lueurPrec = p;
                }
                s += 0.37 * e;
                k++;
            }
            Vec3 bout = chemin.apply(fin).subtract(cam);
            if (jusqua < l) {
                Effets.sphere(pose, buffers, bout, 0.35 * gros, 1F, 0.45F, 0.35F, 0.7F);
            }
            if (front > 0 && front < 1) {
                Effets.sphere(pose, buffers, chemin.apply(front * l).subtract(cam), 0.5 * gros, 1F, 0.9F, 0.5F, 0.8F);
            }
            c = buffers.getBuffer(RenderType.debugQuads());
        }
    }

    // ================================================================ le dessin

    private static void dessiner(PoseStack pose, Vec3 cam) {
        Minecraft mc = Minecraft.getInstance();
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        double t = t();
        EtatBete b = null;
        for (Entity e : mc.level.entitiesForRendering()) {
            if (e instanceof Hachibi3 h && h.distanceToSqr(w(130.5, -30, Z)) < 80 * 80) {
                b = bete(h);
            }
        }
        dessinEnCours = true;
        try {
            doubles(pose, buffers, cam, t, b);
            bebe(pose, buffers, cam, t);
        } finally {
            dessinEnCours = false;
        }
        buffers.endBatch();
        redstone(pose, buffers, cam, t);
        buffers.endBatch(RenderType.debugQuads());
        marques(pose, buffers, cam, t);
        buffers.endBatch();
        chaines(pose, buffers, cam, t, b);
        buffers.endBatch(RenderType.debugQuads());
        // la lumière : les signes, la bête qui devient dorée, l'orbe qui entre dans le ventre, l'éclat du scellement
        double bat = battement(t);
        if (bat > 0.05 && t < 18.5) {
            Vec3 mains = mainDroite(w(P1), lacet(P1, new Vec3(36, 3, Z)), scelleur(t))[0];
            Effets.sphere(pose, buffers, mains.subtract(cam), 0.25 * bat, 1F, 0.25F, 0.18F, (float) (0.6 * bat));
        }
        if (b != null && !b.cachee && b.dore > 0) {
            Effets.sphere(pose, buffers, b.pos.subtract(cam), 40 * b.echelle, 1F, 0.75F, 0.3F, 0.25F * b.dore);
        }
        if (b != null && t >= 36.8 && t < SCELLE) {
            Effets.sphere(pose, buffers, b.pos.subtract(cam), 0.6 + 1.6 * (1 - entre(t, 37, SCELLE)), 1F, 0.7F, 0.45F, (float) entre(t, 36.8, 37.3));
        }
        double age = t - SCELLE;
        if (age >= 0 && age < 1.2) {
            Vec3 v = w(VENTRE).subtract(cam);
            Effets.sphere(pose, buffers, v, 0.4 + 3 * age, 1F, 0.8F, 0.35F, (float) (1 - age / 1.2));
            Effets.onde(pose, buffers, w(34.5, 1.1, Z).subtract(cam), 10 * age, 1.2, (float) (1 - age / 1.2));
        }
        buffers.endBatch();
    }

    // ================================================================ la caméra (les plans du rendu)

    private static Vec3 camera = Vec3.ZERO, vise = Vec3.ZERO;

    private static Vec3 vers(Vec3 a, Vec3 b, double u) {
        return a.lerp(b, lisse(u));
    }

    private static Vec3 v(double x, double y, double z) {
        return new Vec3(x, y, z);
    }

    private static void plan(double t) {
        double u;
        if (t < 4) {
            u = entre(t, 0, 4);
            camera = vers(v(21.5, 7.5, -36.4), v(25, 6.8, -36.8), u);
            vise = v(42, 4.5, Z);
            fov = 55;
        } else if (t < 5.5) {
            camera = vers(v(33.5, 4.55, -42.9), v(33.5, 4.5, -42.6), entre(t, 4, 5.5));
            vise = v(33.55, 3.85, -39.45);
            fov = 55;
        } else if (t < 7.6) {
            camera = vers(v(35.6, 4.4, -44.2), v(35.4, 4.25, -43.9), entre(t, 5.5, 7.6));
            vise = COU.add(-0.3, -0.2, 0).lerp(tete(Math.max(t, COUP)), 0.55 * entre(t, COUP, COUP + 0.4));
            fov = 55;
        } else if (t < 11) {
            camera = vers(v(34.5, 11.2, -29.6), v(34.5, 12.4, -31.4), entre(t, 7.6, 11));
            vise = v(34.5, 1, -38.5);
            fov = 62;
        } else if (t < 14.6) {
            camera = vers(v(35.5, 5.2, -37.2), v(35.2, 5.1, -37.3), entre(t, 11, 14.6));
            vise = v(32.5, 4.1, Z);
            fov = 50;
        } else if (t < 18) {
            u = entre(t, 14.6, 18);
            double a = 2.3 - 1.5 * lisse(u);
            camera = v(33.6 + Math.cos(a) * 4.4, 5.4 + u * 1.4, Z + Math.sin(a) * 4.4);
            vise = v(33.8, 3.6, Z);
            fov = 55;
        } else if (t < 20.3) {
            camera = vers(v(35.9, 5.7, -35.3), v(36.1, 6.1, -35.1), entre(t, 18, 20.3));
            vise = v(35.5, 4.2, Z);
            fov = 50;
        } else if (t < 22.6) {
            u = entre(t, 20.3, 22.6);
            camera = vers(v(31.0, 8.8, -33.5), v(32.0, 8.2, -34.0), u);
            vise = vers(v(46, 5.5, Z), v(85, 7, Z), u);
            fov = 55;
        } else if (t < 26) {
            camera = vers(v(70, 18, -84), v(74, 16, -80), entre(t, 22.6, 26));
            vise = v(115, 2, Z);
            fov = 55;
        } else if (t < 29.2) {
            u = entre(t, 26, 29.2);
            camera = vers(v(36.2, 5.6, -35.3), v(37.4, 5.8, -35.5), u);
            vise = vers(v(42, 5, Z), v(62, 5.5, Z), u);
            fov = 55;
        } else if (t < 32) {
            camera = vers(v(35.7, 5.0, -33.8), v(35.6, 4.95, -34.0), entre(t, 29.2, 32));
            vise = v(33.2, 4.3, -36.4);
            fov = 50;
        } else if (t < 36.4) {
            camera = vers(v(66, 36, 30), v(64, 32, 24), entre(t, 32, 36.4));
            vise = null;
            fov = 55;
        } else if (t < 38.3) {
            camera = vers(v(37.9, 6.2, -33.1), v(37.5, 6.0, -33.5), entre(t, 36.4, 38.3));
            vise = v(41, 4.6, Z);
            fov = 55;
        } else if (t < 41) {
            camera = vers(v(36.2, 6.2, -35.4), v(36.0, 6.0, -35.6), entre(t, 38.3, 41));
            vise = v(35.25, 3.95, Z);
            fov = 50;
        } else {
            camera = vers(v(29, 6.6, -31.6), v(28.2, 7.2, -30.8), entre(t, 41, DUREE));
            vise = v(40, 4.2, Z);
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
        Vec3 cible;
        if (vise == null) {
            // le plan de l'aspiration : entre la bête et le trou
            Vec3 bete = piedsBete == null ? w(140, 4, Z) : piedsBete.add(9.5, 34, 0);
            double e = Math.pow(entre(t, TIRE, TROU_T), 2.2);
            cible = bete.lerp(w(TROU), e).lerp(w(TROU), 0.45);
        } else {
            cible = w(vise);
        }
        double k = 0.18 * bosse(t, SORTIE - 0.2, 0.05, 0.2, 0.8) + 0.3 * Math.max(bosse(t, ATTRAPE + 0.1, 0.3, 1.6, 0.9), bosse(t, 31.9, 0.3, 4.6, 0.6))
                + 0.5 * bosse(t, SCELLE, 0.05, 0.2, 1);
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

    // ================================================================ les sons et les particules

    private static boolean passe(double a, double t, double quand) {
        return a < quand && t >= quand;
    }

    private static void son(Vec3 p, SoundEvent e, float volume, float hauteur) {
        Vec3 q = w(p);
        Minecraft.getInstance().level.playLocalSound(q.x, q.y, q.z, e, SoundSource.PLAYERS, volume, hauteur, false);
    }

    private static void gerbe(ParticleOptions type, Vec3 c, int n, double d, double vitesse) {
        Random r = new Random();
        Vec3 q = w(c);
        for (int i = 0; i < n; i++) {
            Minecraft.getInstance().level.addParticle(type, q.x + (r.nextDouble() - 0.5) * 2 * d, q.y + (r.nextDouble() - 0.5) * 2 * d, q.z + (r.nextDouble() - 0.5) * 2 * d,
                    (r.nextDouble() - 0.5) * vitesse, (r.nextDouble() - 0.5) * vitesse, (r.nextDouble() - 0.5) * vitesse);
        }
    }

    private static void evenements(double a, double t) {
        Vec3 autel = new Vec3(35, 4, Z);
        if (passe(a, t, 4.6)) {
            son(P2, SoundEvents.ARMOR_EQUIP_IRON.value(), 1F, 1.2F);
        }
        if (passe(a, t, COUP)) {
            son(VIL, SoundEvents.PLAYER_ATTACK_SWEEP, 1.2F, 1.1F);
            son(VIL, SoundEvents.VILLAGER_HURT, 1.2F, 1.6F);
            gerbe(DustParticleOptions.REDSTONE, COU, 30, 0.2, 0.3);
            Vec3 q = w(COU);
            Minecraft.getInstance().level.addParticle(ParticleTypes.SWEEP_ATTACK, q.x, q.y + 0.1, q.z, 0, 0, 0);
        }
        for (double[] c : CHOCS) {
            if (passe(a, t, c[0])) {
                son(new Vec3(c[1], c[2], c[3]), SoundEvents.WOOD_FALL, 1F, 1.5F);
                gerbe(DustParticleOptions.REDSTONE, new Vec3(c[1], c[2] + 0.2, c[3]), 8, 0.15, 0.2);
            }
        }
        if (passe(a, t, 6.35)) {
            son(VIL, SoundEvents.VILLAGER_DEATH, 1.2F, 1.6F);
        }
        if (passe(a, t, POUF)) {
            gerbe(ParticleTypes.POOF, VIL.add(0, 0.3, 0), 14, 0.3, 0.1);
            gerbe(ParticleTypes.POOF, tete(t), 8, 0.2, 0.1);
        }
        if (passe(a, t, 7.6)) {
            son(new Vec3(34.5, 1.5, Z), SoundEvents.BEACON_ACTIVATE, 1.2F, 0.6F);
        }
        for (double h : T_SIGNES) {
            if (passe(a, t, h)) {
                son(P1, SoundEvents.ARMOR_EQUIP_LEATHER.value(), 1.2F, 1.4F);
                son(P1, SoundEvents.AMETHYST_BLOCK_CHIME, 1F, 0.7F);
                Vec3 mains = mainDroite(w(P1), lacet(P1, new Vec3(36, 3, Z)), SIGNES[0])[0].subtract(o);
                gerbe(new DustParticleOptions(new Vector3f(1F, 0.1F, 0.08F), 0.6F), mains, 6, 0.15, 0.3);
            }
        }
        if (passe(a, t, FUIN)) {
            son(P1, SoundEvents.EVOKER_CAST_SPELL, 1.5F, 0.7F);
            son(autel, SoundEvents.BEACON_POWER_SELECT, 1.2F, 0.6F);
        }
        for (int i = 0; i < 6; i++) {
            if (passe(a, t, SORTIE + i * 0.17)) {
                son(VENTRE, SoundEvents.CHAIN_PLACE, 1.4F, 0.6F + i * 0.05F);
            }
        }
        if (passe(a, t, SORTIE)) {
            son(VENTRE, SoundEvents.TRIDENT_RIPTIDE_1.value(), 1.2F, 0.6F);
            gerbe(ParticleTypes.CRIT, VENTRE.add(0, 0.4, 0), 25, 0.3, 0.6);
        }
        if (passe(a, t, ATTRAPE)) {
            son(TROU, SoundEvents.CHAIN_HIT, 2F, 0.5F);
        }
        if (passe(a, t, DORE)) {
            son(autel, SoundEvents.AMETHYST_BLOCK_RESONATE, 1.5F, 0.5F);
            son(autel, SoundEvents.BELL_RESONATE, 1F, 0.8F);
        }
        if (passe(a, t, TIRE)) {
            son(TROU, SoundEvents.ANVIL_LAND, 1.5F, 0.5F);
            son(TROU, SoundEvents.CHAIN_BREAK, 2F, 0.5F);
            son(autel, SoundEvents.CHAIN_BREAK, 1.5F, 0.7F);
        }
        if (passe(a, t, TIRE + 0.2)) {
            son(autel, SoundEvents.WARDEN_SONIC_CHARGE, 2F, 0.5F);
        }
        if (passe(a, t, SCELLE)) {
            son(VENTRE, SoundEvents.TOTEM_USE, 1.2F, 0.7F);
            son(VENTRE, SoundEvents.GENERIC_EXPLODE.value(), 1.5F, 0.5F);
            son(VENTRE, SoundEvents.BELL_BLOCK, 1.5F, 0.6F);
            gerbe(new DustParticleOptions(new Vector3f(1F, 0.8F, 0.3F), 1F), VENTRE.add(0, 0.4, 0), 30, 0.5, 0.6);
        }
    }

    // ================================================================ l'écran (bandes noires, flashs, signes)

    private static void ecran(GuiGraphics g) {
        Minecraft mc = Minecraft.getInstance();
        if (!cinema || mc.player == null) {
            return;
        }
        double t = t();
        int w = g.guiWidth(), h = g.guiHeight();
        int bande = (int) (h * 0.1 * lisse(entre(t, 0.4, 0.9)) * (1 - lisse(entre(t, 43.0, 43.6))));
        g.fill(0, 0, w, bande, 0xFF000000);
        g.fill(0, h - bande, w, h, 0xFF000000);
        float f = 0;
        int couleur = 0xFFFFFF;
        double[][] flashs = {{COUP, 0.25, 0xC81414, 0.35}, {SORTIE, 0.4, 0xFF785A, 0.45}, {FUIN, 0.3, 0xFF3C28, 0.35}, {TIRE, 0.3, 0xFFAA3C, 0.3},
                {SCELLE, 1.1, 0xFFE28C, 1}};
        for (double[] fl : flashs) {
            double age = t - fl[0];
            if (age >= 0 && age < fl[1] && fl[3] * (1 - age / fl[1]) > f) {
                f = (float) (fl[3] * (1 - age / fl[1]));
                couleur = (int) fl[2];
            }
        }
        if (f > 0.01F) {
            g.fill(0, 0, w, h, ((int) (Math.min(1, f) * 255) << 24) | couleur);
        }
        if (t >= T_SIGNES[0] && t < FUIN) {
            StringBuilder s = new StringBuilder();
            for (int i = 0; i < T_SIGNES.length && t >= T_SIGNES[i]; i++) {
                s.append(i == 0 ? "" : " · ").append(NOMS_SIGNES[i]);
            }
            fr.cabane.client.ui.Ios.texteCentre(g, mc.font, s.toString(), w / 2F, h - bande - 24, 1.6F, 0xFFFFFFFF, true);
        }
        String annonce = "";
        double op = 0;
        if (t > FUIN && t < 18.6) {
            annonce = "Fûin !";
            op = 1 - entre(t, 18.2, 18.6);
        } else if (t > 38.8 && t < 42.6) {
            annonce = "Hachibi scellé";
            op = entre(t, 38.8, 39.2) * (1 - entre(t, 42.2, 42.6));
        }
        int a = (int) (255 * op);
        if (a > 8) {
            fr.cabane.client.ui.Ios.texteCentre(g, mc.font, annonce, w / 2F, h * 0.4F, 2.8F, (a << 24) | 0xFFCF3F, true);
        }
    }

    // ================================================================ branchements

    @EventBusSubscriber(modid = Cabane.MODID, value = Dist.CLIENT)
    public static final class Couches {
        @SubscribeEvent
        public static void enregistrer(RegisterGuiLayersEvent event) {
            event.registerAboveAll(Cabane.id("rituel_cinema"), (g, delta) -> ecran(g));
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
            image++;
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
            if (cinema && !event.getName().equals(Cabane.id("rituel_cinema"))) {
                event.setCanceled(true);
            }
        }

        private Evenements() {
        }
    }

    private ClientRituel() {
    }
}
