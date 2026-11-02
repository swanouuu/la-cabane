package fr.cabane.client.pouvoir;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import fr.cabane.Cabane;
import fr.cabane.client.cinema.Effets;
import fr.cabane.client.cinema.Effets.Posture;
import fr.cabane.pouvoir.Minato;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
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
import net.minecraft.world.entity.LivingEntity;
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
import net.neoforged.neoforge.client.event.ViewportEvent;
import org.joml.Matrix4f;
import org.joml.Quaternionf;

/**
 * Le combo de Minato, chez chaque joueur à portée, d'après le rendu validé (même déroulé, mêmes temps). Les vrais
 * lanceur et cible restent figés et cachés ; leurs doubles jouent la scène : le lanceur en personnage à son skin (doré
 * en mode Kyubi), la cible avec son vrai modèle. Le lanceur et la cible le voient en cinématique (caméra de cinéma,
 * bandes noires, ciel étoilé pendant le clone, sous-titres, flashs) ; les autres le voient dans le monde.
 * Le temps t est celui du rendu : le kunai touche à 0,95 s, tout finit à 16,5 s.
 */
public final class ClientMinatoCombo {
    private static final double DEBUT = 0.95;
    private static final double FIN = 16.5;
    /** Le zigzag : neuf sauts d'un kunai à l'autre, très vite, chacun laissant un trait jaune bref. */
    private static final double PAS = 0.16;
    private static final int[] SEQ = {0, 3, 1, 4, 2, 5, 0, 3, 1};
    private static final double TRAIT = 0.18;
    private static final double[] DY = {-1.4, 1.2, -0.6, 1.6, -1.2, 0.8};
    private static final Vec3 HAUT = new Vec3(0, 1, 0);
    private static final RandomSource HASARD = RandomSource.create();

    private static final Posture SCEAU = new Posture(0.15F, 0, 0, -1.3F, 0.55F, 0, -1.3F, -0.55F, 0, 0, 0.03F, 0, -0.03F);
    private static final Posture TIENT = new Posture(0.3F, 0, 4, -1.0F, 0.1F, 0, -0.55F, -0.7F, 0, 0, 0.05F, 0, -0.05F);
    private static final Posture LEVE = new Posture(-0.2F, 0, 0, -3.0F, 0, 0.1F, 0, 0, -0.08F, 0, 0.03F, 0, -0.03F);

    /** Un combo : qui, sur qui, ses points (voir {@link Minato}), son axe f (lanceur → cible) et son côté g. */
    private static final class Scene {
        UUID lanceur;
        int cible;
        Vec3[] p;
        Vec3 f;
        Vec3 g;
        Vec3[] k = new Vec3[6];
        Vec3 clone;
        long debut;
        double avant = DEBUT;
        final List<double[]> sauts = new ArrayList<>();
        final List<Vec3[]> traits = new ArrayList<>();
    }

    private static final Map<UUID, Scene> SCENES = new HashMap<>();
    static boolean dessinEnCours;

    // ---- la cinématique, quand on est le lanceur ou la cible
    private static Scene vue;
    private static Entity objectif;
    private static CameraType vueAvant;
    private static boolean guiAvant;
    private static float fov = 70;

    private static double secondes(long depuis) {
        return (System.nanoTime() - depuis) / 1e9;
    }

    private static double t(Scene s) {
        return DEBUT + secondes(s.debut);
    }

    private static double entre(double t, double a, double b) {
        return Mth.clamp((t - a) / (b - a), 0, 1);
    }

    private static double lisse(double k) {
        return k * k * (3 - 2 * k);
    }

    /** Un point autour de base : a le long de f, h en hauteur, b le long de g. */
    private static Vec3 l(Scene s, Vec3 base, double a, double h, double b) {
        return base.add(s.f.scale(a)).add(0, h, 0).add(s.g.scale(b));
    }

    // ================================================================ départ, arrêt

    static void demarrer(Minato.Combo c) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            return;
        }
        Scene s = new Scene();
        s.lanceur = c.joueur();
        s.cible = c.cible();
        s.p = c.points().stream().map(v -> new Vec3(v.x(), v.y(), v.z())).toArray(Vec3[]::new);
        Vec3 o = s.p[Minato.O], d = s.p[Minato.DEPART];
        Vec3 f = new Vec3(o.x - d.x, 0, o.z - d.z);
        s.f = f.lengthSqr() < 1.0E-4 ? new Vec3(0, 0, 1) : f.normalize();
        s.g = new Vec3(s.f.z, 0, -s.f.x);
        for (int i = 0; i < 6; i++) {
            double a = i * Math.PI / 3 + 0.3;
            s.k[i] = l(s, s.p[Minato.AIR], 3 * Math.cos(a), DY[i], -3 * Math.sin(a));
        }
        s.clone = l(s, s.p[Minato.P1], 0, 0, 1.3);
        // les téléportations : [moment] → [départ, arrivée]
        ajouterSaut(s, 1.0, s.p[Minato.DEPART], s.p[Minato.P1]);
        for (int j = 0; j < SEQ.length; j++) {
            ajouterSaut(s, 3.8 + j * PAS, j == 0 ? s.p[Minato.P1] : s.k[SEQ[j - 1]], s.k[SEQ[j]]);
        }
        ajouterSaut(s, 5.35, s.k[SEQ[SEQ.length - 1]], s.p[Minato.P2]);
        ajouterSaut(s, 7.6, s.p[Minato.P2], s.p[Minato.P4]);
        ajouterSaut(s, 9.7, s.p[Minato.P4], s.p[Minato.P3]);
        s.debut = System.nanoTime();
        Scene ancienne = SCENES.put(s.lanceur, s);
        if (ancienne != null && ancienne == vue) {
            sortir();
        }
        if (mc.player.getUUID().equals(s.lanceur) || mc.player.getId() == s.cible) {
            entrer(s);
        }
    }

    /**
     * Pour l'outil de captures : le combo joué sans serveur sur la créature la plus proche, lancé par un inconnu vers
     * l'est, pour voir ce que voient les autres joueurs.
     */
    public static void demo() {
        Minecraft mc = Minecraft.getInstance();
        // de préférence la momie que le script de test a fait apparaître
        LivingEntity cible = null;
        double meilleur = Double.MAX_VALUE;
        for (Entity e : mc.level.entitiesForRendering()) {
            double score = e.distanceTo(mc.player) + (e.getType() == EntityType.HUSK ? 0 : 1000);
            if (e instanceof LivingEntity v && !(e instanceof Player) && e.distanceTo(mc.player) < 40 && score < meilleur) {
                cible = v;
                meilleur = score;
            }
        }
        if (cible == null) {
            return;
        }
        Vec3 o = cible.position(), f = new Vec3(1, 0, 0), g = new Vec3(0, 0, -1);
        Vec3[] p = {o, o.subtract(f.scale(9)), o.subtract(f.scale(1.2)), o.add(f.scale(3)).add(0, 3.2, 0), o.subtract(f.scale(0.8)).add(g.scale(1.6)),
                o.add(f.scale(3.5)), o.add(f.scale(2.5)), o.add(f.scale(10)), o.subtract(f.scale(3))};
        List<org.joml.Vector3f> points = new ArrayList<>();
        for (Vec3 q : p) {
            points.add(new org.joml.Vector3f((float) q.x, (float) q.y, (float) q.z));
        }
        demarrer(new Minato.Combo(UUID.randomUUID(), cible.getId(), points));
    }

    private static void ajouterSaut(Scene s, double quand, Vec3 a, Vec3 b) {
        s.sauts.add(new double[] {quand});
        s.traits.add(new Vec3[] {a, b});
    }

    static void arreter(UUID lanceur) {
        Scene s = SCENES.remove(lanceur);
        if (s != null && s == vue) {
            sortir();
        }
    }

    private static void entrer(Scene s) {
        Minecraft mc = Minecraft.getInstance();
        if (vue != null) {
            sortir();
        }
        vue = s;
        mc.setScreen(null);
        guiAvant = mc.options.hideGui;
        mc.options.hideGui = true;
        vueAvant = mc.options.getCameraType();
        mc.options.setCameraType(CameraType.FIRST_PERSON);
        objectif = EntityType.MARKER.create(mc.level);
        placerCamera(s, t(s));
        mc.setCameraEntity(objectif);
    }

    private static void sortir() {
        Minecraft mc = Minecraft.getInstance();
        vue = null;
        mc.options.hideGui = guiAvant;
        if (vueAvant != null) {
            mc.options.setCameraType(vueAvant);
        }
        if (mc.player != null) {
            mc.setCameraEntity(mc.player);
        }
        objectif = null;
    }

    static boolean cinematique() {
        return vue != null;
    }

    static boolean participant() {
        return vue != null;
    }

    /** Ce vrai lanceur ou cette vraie cible est caché pendant son combo (sauf quand on dessine son double). */
    static boolean cache(Entity e) {
        if (dessinEnCours || SCENES.isEmpty()) {
            return false;
        }
        for (Scene s : SCENES.values()) {
            if (e.getId() == s.cible || (e instanceof Player p && p.getUUID().equals(s.lanceur))) {
                return true;
            }
        }
        return false;
    }

    // ================================================================ le scénario (les mêmes fonctions que le rendu)

    private static Vec3 posLanceur(Scene s, double t) {
        if (t < 1.0) {
            return s.p[Minato.DEPART];
        }
        if (t < 3.8) {
            return s.p[Minato.P1];
        }
        if (t < 5.35) {
            return s.k[SEQ[Math.min(SEQ.length - 1, (int) ((t - 3.8) / PAS))]];
        }
        if (t < 7.6) {
            return s.p[Minato.P2];
        }
        return t < 9.7 ? s.p[Minato.P4] : s.p[Minato.P3];
    }

    private static Vec3 posCible(Scene s, double t) {
        Vec3 o = s.p[Minato.O], a = s.p[Minato.AIR], e2 = s.p[Minato.E2], e3 = s.p[Minato.E3];
        if (t < 1.2) {
            return o;
        }
        if (t < 2.0) {
            double u = lisse(entre(t, 1.2, 2.0));
            return new Vec3(Mth.lerp(u, o.x, a.x), o.y + (a.y - o.y) * Math.sin(u * Math.PI / 2), Mth.lerp(u, o.z, a.z));
        }
        if (t < 5.4) {
            Vec3 p = a.add(0, Math.sin(t * 2) * 0.08, 0);
            for (int j = 0; j < SEQ.length; j++) {
                double h = 3.8 + j * PAS + 0.08, age = t - h;
                if (age >= 0 && age < 0.15) {
                    p = p.add(new Vec3(Math.sin(h * 50) * 0.35, Math.cos(h * 30) * 0.25, Math.sin(h * 17) * 0.3).scale(1 - age / 0.15));
                }
            }
            return p;
        }
        if (t < 5.9) {
            double u = entre(t, 5.4, 5.9);
            return new Vec3(Mth.lerp(u, a.x, e2.x), e2.y + (a.y - e2.y) * (1 - u * u), Mth.lerp(u, a.z, e2.z));
        }
        if (t < 8.6) {
            return e2;
        }
        if (t < 9.4) {
            double u = entre(t, 8.6, 9.4), e = 1 - (1 - u) * (1 - u);
            return new Vec3(Mth.lerp(e, e2.x, e3.x), Mth.lerp(e, e2.y, e3.y) + 1.4 * Math.sin(u * Math.PI) + 0.13 * u, Mth.lerp(e, e2.z, e3.z));
        }
        return e3.add(0, 0.13, 0);
    }

    /** De combien la cible bascule en arrière (radians, négatif : vers l'arrière). */
    private static double bascule(double t) {
        if (t < 1.2) {
            return 0;
        }
        if (t < 2.0) {
            return -1.1 * lisse(entre(t, 1.2, 2.0));
        }
        if (t < 5.4) {
            return -1.1 + Math.sin(t * 1.3) * 0.15;
        }
        if (t < 5.9) {
            return -1.1 * (1 - entre(t, 5.4, 5.9));
        }
        if (t < 8.6) {
            return 0;
        }
        return t < 9.4 ? -Math.PI * 2.5 * entre(t, 8.6, 9.4) : -Math.PI / 2;
    }

    private static Posture posture(double t) {
        if (t < 1.6) {
            float k = (float) (lisse(entre(t, 1.05, 1.15)) * (1 - lisse(entre(t, 1.35, 1.55))));
            return Posture.DEBOUT.vers(Posture.PIED, k);
        }
        if (t < 2.6) {
            return SCEAU;
        }
        if (t < 3.6) {
            return TIENT;
        }
        if (t < 3.8) {
            return Posture.FLASH_CHARGE;
        }
        if (t < 5.35) {
            return ((int) ((t - 3.8) / PAS)) % 2 == 0 ? Posture.COUP : Posture.PIED;
        }
        if (t < 7.6) {
            float k = (float) (entre(t, 6.4, 6.6) * (1 - entre(t, 7.0, 7.4)));
            return TIENT.vers(Posture.FLASH_CHARGE, k * 0.7F);
        }
        if (t < 9.7) {
            float k = (float) lisse(entre(t, 7.65, 7.9)) * (1 - (float) lisse(entre(t, 9.0, 9.5)));
            return TIENT.vers(Posture.COUP, k);
        }
        if (t < 12.9) {
            return Posture.DEBOUT.vers(LEVE, (float) lisse(entre(t, 9.75, 10.2)));
        }
        return Posture.DEBOUT;
    }

    private static double kyubi(double t) {
        return entre(t, 6.5, 7.0) * (1 - entre(t, 13.2, 14.4));
    }

    private static double cosmos(double t) {
        return entre(t, 1.62, 1.75) * (1 - entre(t, 3.5, 3.65));
    }

    private static float lacet(Vec3 de, Vec3 vers) {
        return (float) Math.toDegrees(Math.atan2(-(vers.x - de.x), vers.z - de.z));
    }

    /** La main droite du double (là où se tient le Rasengan), selon la posture du moment. */
    private static Vec3 main(Vec3 pieds, float lacet, double t) {
        double r = Math.toRadians(lacet);
        Vec3 devant = new Vec3(-Math.sin(r), 0, Math.cos(r));
        Vec3 droite = new Vec3(-devant.z, 0, devant.x);
        boolean tendu = t >= 7.75 && t < 9.0;
        return pieds.add(0, tendu ? 1.32 : 0.95, 0).add(devant.scale(tendu ? 1.05 : 0.85)).add(droite.scale(0.3));
    }

    // ================================================================ la caméra (les mêmes plans que le rendu)

    private static Vec3 camera = Vec3.ZERO;
    private static Vec3 vise = Vec3.ZERO;

    private static Vec3 vers(Vec3 a, Vec3 b, double u) {
        return a.lerp(b, lisse(u));
    }

    private static void plan(Scene s, double t) {
        Vec3 o = s.p[Minato.O], p1 = s.p[Minato.P1], p2 = s.p[Minato.P2], e2 = s.p[Minato.E2], e3 = s.p[Minato.E3], p3 = s.p[Minato.P3];
        Vec3 air = s.p[Minato.AIR];
        if (t < 1.65) {
            double u = entre(t, 0.95, 1.65);
            camera = vers(l(s, o, 0, 1.4, -4.2), l(s, o, 0.6, 1.6, -3.8), u);
            vise = vers(l(s, o, -0.4, 1.1, 0), l(s, o, 1.2, 1.8, 0), u);
            fov = 50;
        } else if (t < 3.6) {
            camera = vers(l(s, p1, 2.8, 1.5, 0.65), l(s, p1, 2.2, 1.55, 0.65), entre(t, 1.65, 3.6));
            vise = l(s, p1, 0, 1.35, 0.65);
            fov = 50;
        } else if (t < 5.45) {
            double a = -0.5 + 1.4 * entre(t, 3.6, 5.45);
            camera = new Vec3(air.x - (s.f.x * Math.cos(a) + s.g.x * Math.sin(a)) * 8.5, o.y + 2.0, air.z - (s.f.z * Math.cos(a) + s.g.z * Math.sin(a)) * 8.5);
            vise = air.add(0, -0.4, 0);
            fov = 55;
        } else if (t < 6.4) {
            camera = vers(l(s, p2, 1.8, 1.5, 1.4), l(s, p2, 1.6, 1.45, 1.2), entre(t, 5.45, 6.4));
            vise = l(s, p2, 0, 1.2, 0);
            fov = 45;
        } else if (t < 7.6) {
            camera = vers(l(s, p2, 2.3, 0.7, 1.2), l(s, p2, 2.0, 0.8, 1.0), entre(t, 6.4, 7.6));
            vise = l(s, p2, 0, 1.5, 0);
            fov = 55;
        } else if (t < 8.6) {
            camera = vers(l(s, e2, -1.1, 1.3, -3.2), l(s, e2, -0.6, 1.2, -2.8), entre(t, 7.6, 8.6));
            vise = l(s, e2, -0.5, 1.1, 0);
            fov = 50;
        } else if (t < 9.7) {
            camera = l(s, e2, -3.5, 3, -7.6);
            vise = vers(l(s, e2, 0.5, 1, 0), l(s, e3, -2, 0.8, 0), entre(t, 8.6, 9.7));
            fov = 55;
        } else if (t < 10.6) {
            camera = vers(l(s, p3, -2.4, 1.9, 1.8), l(s, p3, -2.1, 1.8, 1.6), entre(t, 9.7, 10.6));
            vise = l(s, e3, 0, 1.4, 0);
            fov = 50;
        } else if (t < 12.9) {
            camera = vers(l(s, e3, -9.5, 3.2, -10.1), l(s, e3, -10.2, 3.6, -11.1), entre(t, 10.6, 12.9));
            vise = l(s, e3, -3, 2.2, 0);
            fov = 60;
        } else if (t < 14.7) {
            camera = vers(l(s, p3, 1.15, 1.68, -0.25), l(s, p3, 1.0, 1.66, -0.2), entre(t, 12.9, 14.7));
            vise = l(s, p3, 0, 1.6, 0);
            fov = 40;
        } else {
            camera = vers(l(s, p3, -3, 2.2, 3.6), l(s, p3, -3.4, 2.6, 4.2), entre(t, 14.7, FIN));
            vise = l(s, p3, 6.5, 0.6, 0);
            fov = 55;
        }
    }

    private static double secousse(double t) {
        double s = 0;
        for (double q : new double[] {8.6, 10.6}) {
            if (t >= q && t < q + 0.8) {
                s += 0.2 * (1 - (t - q) / 0.8);
            }
        }
        return s;
    }

    private static void placerCamera(Scene s, double t) {
        if (objectif == null) {
            return;
        }
        plan(s, t);
        double k = secousse(t);
        Vec3 c = camera.add((HASARD.nextDouble() - 0.5) * k, (HASARD.nextDouble() - 0.5) * k, (HASARD.nextDouble() - 0.5) * k);
        Vec3 d = vise.subtract(c);
        float lacet = (float) Math.toDegrees(Math.atan2(-d.x, d.z));
        float tangage = (float) Math.toDegrees(-Math.atan2(d.y, Math.sqrt(d.x * d.x + d.z * d.z)));
        objectif.setPos(c.x, c.y, c.z);
        objectif.xo = objectif.xOld = c.x;
        objectif.yo = objectif.yOld = c.y;
        objectif.zo = objectif.zOld = c.z;
        objectif.setYRot(lacet);
        objectif.setXRot(tangage);
        objectif.yRotO = lacet;
        objectif.xRotO = tangage;
    }

    // ================================================================ le dessin

    /** De la lumière pure (comme dans {@link Effets}) : additive, sans écrire la profondeur. */
    private static final class Types extends RenderType {
        static final RenderType LUMIERE = create("cabane_minato_lumiere", com.mojang.blaze3d.vertex.DefaultVertexFormat.POSITION_COLOR,
                com.mojang.blaze3d.vertex.VertexFormat.Mode.QUADS, 4096, false, true, CompositeState.builder()
                        .setShaderState(RENDERTYPE_LIGHTNING_SHADER).setWriteMaskState(COLOR_WRITE).setTransparencyState(LIGHTNING_TRANSPARENCY)
                        .setCullState(NO_CULL).createCompositeState(false));

        private Types() {
            super("cabane_minato_rien", com.mojang.blaze3d.vertex.DefaultVertexFormat.POSITION, com.mojang.blaze3d.vertex.VertexFormat.Mode.QUADS, 256, false, false, () -> { }, () -> { });
        }
    }

    private static Vec3[] base(Vec3 d) {
        Vec3 n = d.normalize();
        Vec3 a = Math.abs(n.y) < 0.9 ? HAUT : new Vec3(1, 0, 0);
        Vec3 u = n.cross(a).normalize();
        return new Vec3[] {u, n.cross(u).normalize()};
    }

    private static void sommet(VertexConsumer c, Matrix4f m, Vec3 p, float r, float g, float b, float a) {
        c.addVertex(m, (float) p.x, (float) p.y, (float) p.z).setColor(r, g, b, a);
    }

    /** Le ciel de l'espace, pour le lanceur et sa cible pendant le clone : un dôme violet loin derrière le décor, et des étoiles. */
    private static void ciel(PoseStack pose, MultiBufferSource.BufferSource buffers, double force) {
        if (force <= 0.01) {
            return;
        }
        Matrix4f m = pose.last().pose();
        VertexConsumer q = buffers.getBuffer(RenderType.debugQuads());
        double r = 60;
        int lat = 10, lon = 20;
        for (int i = 0; i < lat; i++) {
            double a0 = Math.PI * i / lat - Math.PI / 2, a1 = Math.PI * (i + 1) / lat - Math.PI / 2;
            for (int j = 0; j < lon; j++) {
                double b0 = Math.PI * 2 * j / lon, b1 = Math.PI * 2 * (j + 1) / lon;
                for (double[] ab : new double[][] {{a0, b0}, {a1, b0}, {a1, b1}, {a0, b1}}) {
                    Vec3 p = new Vec3(Math.cos(ab[0]) * Math.cos(ab[1]) * r, Math.sin(ab[0]) * r, Math.cos(ab[0]) * Math.sin(ab[1]) * r);
                    sommet(q, m, p, 0.13F, 0.05F, 0.24F, (float) force);
                }
            }
        }
        buffers.endBatch(RenderType.debugQuads());
        VertexConsumer e = buffers.getBuffer(Types.LUMIERE);
        java.util.Random rnd = new java.util.Random(7);
        for (int i = 0; i < 350; i++) {
            Vec3 d = new Vec3(rnd.nextGaussian(), Math.abs(rnd.nextGaussian()) * 0.8 + 0.05, rnd.nextGaussian()).normalize();
            double taille = 0.08 + rnd.nextDouble() * 0.18;
            float a = (float) (force * (0.5 + rnd.nextDouble() * 0.5));
            Vec3 p = d.scale(57);
            Vec3[] uw = base(d);
            Vec3 u = uw[0].scale(taille), w = uw[1].scale(taille);
            sommet(e, m, p.subtract(u).subtract(w), 1, 1, 1, a);
            sommet(e, m, p.add(u).subtract(w), 1, 1, 1, a);
            sommet(e, m, p.add(u).add(w), 1, 1, 1, a);
            sommet(e, m, p.subtract(u).add(w), 1, 1, 1, a);
        }
        float n = (float) force * 0.12F;
        Effets.sphere(pose, buffers, new Vec3(20, 14, -40), 14, 0.7F, 0.25F, 1F, n);
        Effets.sphere(pose, buffers, new Vec3(-30, 20, 25), 12, 1F, 0.25F, 0.6F, n);
        Effets.sphere(pose, buffers, new Vec3(42, 8, 22), 11, 0.25F, 0.4F, 1F, n);
        buffers.endBatch();
    }

    /** Un anneau lumineux de rayon r autour de c, dans le plan perpendiculaire à n. */
    private static void anneau(PoseStack pose, MultiBufferSource buffers, Vec3 c, double r, Vec3 n, float rouge, float vert, float bleu, float a) {
        Vec3[] uw = base(n);
        Vec3 prec = null;
        for (int i = 0; i <= 20; i++) {
            double q = i * Math.PI * 2 / 20;
            Vec3 p = c.add(uw[0].scale(Math.cos(q) * r)).add(uw[1].scale(Math.sin(q) * r));
            if (prec != null) {
                Effets.tube(pose, buffers, prec, p, r * 0.06, rouge, vert, bleu, a);
            }
            prec = p;
        }
    }

    /** Le Rasengan : une boule bleue au cœur blanc, deux anneaux qui tournent ; en mode Kyubi, une couronne de feu. */
    private static void rasengan(PoseStack pose, MultiBufferSource buffers, Vec3 c, double r, double k, float a, double t) {
        Effets.sphere(pose, buffers, c, r, 0.35F, 0.7F, 1F, 0.9F * a);
        Effets.sphere(pose, buffers, c, r * 0.5, 1F, 1F, 1F, a);
        anneau(pose, buffers, c, r * 0.95, new Vec3(Math.sin(t * 9), Math.cos(t * 5), Math.sin(t * 3)), 0.75F, 0.92F, 1F, 0.8F * a);
        anneau(pose, buffers, c, r * 0.8, new Vec3(Math.cos(t * 4), Math.sin(t * 11), Math.cos(t * 7)), 0.6F, 0.85F, 1F, 0.8F * a);
        if (k > 0) {
            Effets.sphere(pose, buffers, c, r * 1.55, 1F, 0.5F, 0.1F, (float) (0.5 * k) * a);
            anneau(pose, buffers, c, r * 1.45, new Vec3(Math.sin(t * 7) * 0.3, 1, Math.cos(t * 6) * 0.3), 1F, 0.55F, 0.1F, (float) (0.9 * k) * a);
        }
    }

    private static ResourceLocation skin(Scene s) {
        Minecraft mc = Minecraft.getInstance();
        Player p = mc.level.getPlayerByUUID(s.lanceur);
        return p instanceof AbstractClientPlayer c ? c.getSkin().texture() : DefaultPlayerSkin.getDefaultTexture();
    }

    /** La cible, avec son vrai modèle, déplacée et basculée par le combo. */
    private static void cible(PoseStack pose, MultiBufferSource buffers, Scene s, LivingEntity v, double t, Vec3 cam, float partiel) {
        Minecraft mc = Minecraft.getInstance();
        Vec3 pc = posCible(s, t);
        float lac = lacet(Vec3.ZERO, s.f.reverse());
        float[] avant = {v.yBodyRot, v.yBodyRotO, v.yHeadRot, v.yHeadRotO, v.getYRot(), v.yRotO, v.getXRot(), v.xRotO};
        v.yBodyRot = v.yBodyRotO = v.yHeadRot = v.yHeadRotO = v.yRotO = lac;
        v.setYRot(lac);
        v.setXRot(0);
        v.xRotO = 0;
        pose.pushPose();
        pose.translate(pc.x - cam.x, pc.y - cam.y, pc.z - cam.z);
        pose.mulPose(new Quaternionf().rotateAxis((float) -bascule(t), (float) s.g.x, 0F, (float) s.g.z));
        dessinEnCours = true;
        try {
            mc.getEntityRenderDispatcher().render(v, 0, 0, 0, lac, partiel, pose, buffers, LevelRenderer.getLightColor(mc.level, BlockPos.containing(pc.add(0, 0.5, 0))));
        } finally {
            dessinEnCours = false;
            pose.popPose();
            v.yBodyRot = avant[0];
            v.yBodyRotO = avant[1];
            v.yHeadRot = avant[2];
            v.yHeadRotO = avant[3];
            v.setYRot(avant[4]);
            v.yRotO = avant[5];
            v.setXRot(avant[6]);
            v.xRotO = avant[7];
        }
    }

    private static void dessiner(PoseStack pose, Vec3 cam, float partiel) {
        Minecraft mc = Minecraft.getInstance();
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        for (Scene s : SCENES.values()) {
            double t = t(s);
            if (s == vue) {
                ciel(pose, buffers, cosmos(t));
            }
            if (mc.level.getEntity(s.cible) instanceof LivingEntity v && v.isAlive()) {
                cible(pose, buffers, s, v, t, cam, partiel);
            }
            ResourceLocation skin = skin(s);
            Vec3 pm = posLanceur(s, t);
            float lac = lacet(pm, posCible(s, t));
            double k = kyubi(t);
            int teinte = 0xFF0000 | ((int) (255 * (1 - 0.18 * k)) << 8) | (int) (255 * (1 - 0.55 * k));
            Effets.personnage(pose, buffers, skin, pm.subtract(cam), lac, posture(t), 1F, Effets.SANS, Effets.NOIR, 1F, teinte);
            if (t > 1.75 && t < 3.5) {
                Effets.personnage(pose, buffers, skin, s.clone.subtract(cam), lacet(s.clone, s.p[Minato.AIR]), SCEAU, 1F, Effets.SANS, Effets.NOIR, 1F);
            }
            buffers.endBatch();
            // les kunais : les six autour de lui, puis celui qu'il lève
            if (t >= 3.6 && t < 5.4) {
                Vec3 de = l(s, s.p[Minato.P1], 0, 1.5, 0);
                for (int i = 0; i < 6; i++) {
                    ClientMinato.dessinerKunai(pose, buffers, de.lerp(s.k[i], entre(t, 3.6, 3.75)).subtract(cam), s.k[i].subtract(s.p[Minato.AIR]));
                }
            }
            if (t > 10.0 && t < 12.9) {
                double r = Math.toRadians(lac);
                Vec3 droite = new Vec3(-Math.cos(r), 0, -Math.sin(r));
                ClientMinato.dessinerKunai(pose, buffers, pm.add(0, 2.2, 0).add(droite.scale(0.3)).subtract(cam), HAUT);
            }
            buffers.endBatch(RenderType.debugQuads());
            // la lumière : l'aura du mode Kyubi, le Rasengan, les traits jaunes, l'impact, le dôme
            if (k > 0) {
                Effets.aura(pose, buffers, pm.subtract(cam), (float) (0.55 * k), 1F, 0.55F, 0.12F, (float) t);
            }
            double rayon = 0;
            float alpha = 1;
            Vec3 c = main(pm, lac, t);
            if (t >= 2.6 && t < 3.55) {
                rayon = 0.32 * lisse(entre(t, 2.6, 3.2));
            } else if (t >= 5.35 && t < 7.6) {
                rayon = 0.32;
            } else if (t >= 7.6 && t < 8.6) {
                rayon = 0.32 + 0.45 * lisse(entre(t, 7.7, 8.4));
            } else if (t >= 8.6 && t < 9.0) {
                rayon = 0.77 + 2.4 * entre(t, 8.6, 9.0);
                c = l(s, s.p[Minato.E2], -0.3, 1.2, 0);
                alpha = (float) (1 - entre(t, 8.6, 9.0));
            }
            if (rayon > 0.01) {
                rasengan(pose, buffers, c.subtract(cam), rayon, k, alpha, t);
            }
            for (int i = 0; i < s.sauts.size(); i++) {
                double age = t - s.sauts.get(i)[0];
                if (age < 0 || age >= TRAIT) {
                    continue;
                }
                float f = (float) (1 - age / TRAIT);
                Vec3 a = s.traits.get(i)[0].add(0, 1.1, 0).subtract(cam), b = s.traits.get(i)[1].add(0, 1.1, 0).subtract(cam);
                Effets.tube(pose, buffers, a, b, 0.25 * (0.4 + 0.6 * f), 1F, 0.8F, 0.15F, 0.55F * f);
                Effets.tube(pose, buffers, a, b, 0.07 * (0.4 + 0.6 * f), 1F, 1F, 1F, f);
            }
            if (t >= 8.6 && t < 9.2) {
                Effets.onde(pose, buffers, l(s, s.p[Minato.E2], 0, 0.1, 0).subtract(cam), 5 * entre(t, 8.6, 9.2), 0.8, (float) (1 - entre(t, 8.6, 9.2)));
            }
            if (t >= 10.6 && t < 12.9) {
                double u = entre(t, 10.6, 11.3);
                double rd = 7.5 * (1 - Math.pow(1 - u, 3));
                float op = (float) (1 - entre(t, 11.8, 12.9));
                Vec3 centre = s.p[Minato.E3].subtract(cam);
                Effets.sphere(pose, buffers, centre, rd, 0.7F, 0.9F, 1F, 0.6F * op);
                Effets.onde(pose, buffers, centre.add(0, 0.05, 0), rd * 1.02, 1.5, op);
            }
            buffers.endBatch();
        }
    }

    // ================================================================ les sons et les éclats

    private static boolean passe(double avant, double t, double quand) {
        return avant < quand && t >= quand;
    }

    private static double aleat(double d) {
        return (HASARD.nextDouble() - 0.5) * 2 * d;
    }

    /** Le coup de tonnerre de chaque téléportation (voir {@link fr.cabane.CabaneSons#MINATO_FLASH}). */
    private static SoundEvent flash() {
        return fr.cabane.CabaneSons.MINATO_FLASH.get();
    }

    private static void son(Vec3 p, SoundEvent e, float volume, float hauteur) {
        Minecraft.getInstance().level.playLocalSound(p.x, p.y, p.z, e, SoundSource.PLAYERS, volume, hauteur, false);
    }

    private static void gerbe(ParticleOptions type, Vec3 c, int n, double d) {
        for (int i = 0; i < n; i++) {
            Minecraft.getInstance().level.addParticle(type, c.x + aleat(d), c.y + aleat(d), c.z + aleat(d), aleat(0.25), aleat(0.25), aleat(0.25));
        }
    }

    private static void evenements(Scene s, double a, double t) {
        Vec3 p1 = s.p[Minato.P1], air = s.p[Minato.AIR];
        if (passe(a, t, 1.0)) {
            son(p1, flash(), 1F, 1F);
            gerbe(ParticleTypes.ELECTRIC_SPARK, p1.add(0, 1, 0), 20, 0.4);
            gerbe(ParticleTypes.ELECTRIC_SPARK, s.p[Minato.DEPART].add(0, 1, 0), 12, 0.4);
        }
        if (passe(a, t, 1.2)) {
            son(s.p[Minato.O], SoundEvents.PLAYER_ATTACK_KNOCKBACK, 1F, 0.9F);
            gerbe(ParticleTypes.CRIT, s.p[Minato.O].add(0, 1.2, 0), 20, 0.6);
        }
        for (double q : new double[] {1.7, 3.48}) {
            if (passe(a, t, q)) {
                son(s.clone, SoundEvents.ILLUSIONER_MIRROR_MOVE, 1F, 1.2F);
                gerbe(ParticleTypes.POOF, s.clone.add(0, 1, 0), 25, 0.35);
            }
        }
        if (passe(a, t, 2.6)) {
            son(p1, SoundEvents.BEACON_ACTIVATE, 0.8F, 1.8F);
        }
        if (passe(a, t, 3.6)) {
            son(p1, SoundEvents.TRIDENT_THROW.value(), 1F, 1.8F);
        }
        for (int j = 0; j < SEQ.length; j++) {
            double q = 3.8 + j * PAS;
            if (passe(a, t, q)) {
                // neuf sauts en une seconde et demie : des coups de tonnerre plus discrets, qui se fondent en un grondement
                son(s.k[SEQ[j]], flash(), 0.4F, 1.15F + j * 0.05F);
            }
            if (passe(a, t, q + 0.08)) {
                son(air, SoundEvents.PLAYER_ATTACK_SWEEP, 1F, 1.3F);
                gerbe(ParticleTypes.ELECTRIC_SPARK, air.add(0, 1, 0), 10, 0.5);
                Minecraft.getInstance().level.addParticle(ParticleTypes.SWEEP_ATTACK, air.x, air.y + 1, air.z, 0, 0, 0);
            }
        }
        if (passe(a, t, 5.35)) {
            son(s.p[Minato.P2], flash(), 1F, 1F);
        }
        if (passe(a, t, 6.45)) {
            Vec3 p2 = s.p[Minato.P2];
            son(p2, SoundEvents.BLAZE_SHOOT, 1F, 0.7F);
            son(p2, SoundEvents.FIRECHARGE_USE, 1F, 0.6F);
            for (int i = 0; i < 40; i++) {
                double q = i * Math.PI * 2 / 40;
                Minecraft.getInstance().level.addParticle(ParticleTypes.FLAME, p2.x + Math.cos(q) * 0.8, p2.y + 0.2, p2.z + Math.sin(q) * 0.8,
                        Math.cos(q) * 0.15, 0.08, Math.sin(q) * 0.15);
            }
        }
        if (passe(a, t, 7.6)) {
            son(s.p[Minato.P4], flash(), 1F, 1F);
        }
        if (passe(a, t, 8.6)) {
            Vec3 c = l(s, s.p[Minato.E2], -0.3, 1.2, 0);
            son(c, SoundEvents.GENERIC_EXPLODE.value(), 1.2F, 1.4F);
            gerbe(ParticleTypes.EXPLOSION, c, 3, 0.6);
            gerbe(ParticleTypes.ELECTRIC_SPARK, c, 30, 0.8);
        }
        if (passe(a, t, 9.7)) {
            son(s.p[Minato.P3], flash(), 1F, 1F);
        }
        if (passe(a, t, 10.6)) {
            Vec3 c = s.p[Minato.E3];
            son(c, SoundEvents.GENERIC_EXPLODE.value(), 2F, 0.6F);
            son(c, SoundEvents.WARDEN_SONIC_BOOM, 1.5F, 1.2F);
            var monde = Minecraft.getInstance().level;
            monde.addParticle(ParticleTypes.EXPLOSION_EMITTER, c.x, c.y + 1, c.z, 0, 0, 0);
            var sous = monde.getBlockState(BlockPos.containing(c).below());
            if (!sous.isAir()) {
                for (int i = 0; i < 80; i++) {
                    monde.addParticle(new BlockParticleOption(ParticleTypes.BLOCK, sous), c.x + aleat(2), c.y + 0.3, c.z + aleat(2),
                            aleat(0.8), 0.4 + HASARD.nextDouble() * 0.8, aleat(0.8));
                }
            }
            gerbe(ParticleTypes.END_ROD, c.add(0, 1, 0), 60, 2.5);
        }
    }

    // ================================================================ l'écran (bandes noires, flashs, sous-titres)

    private static String sousTitre(double t) {
        if (t > 5.6 && t < 6.5) {
            return "Ça s'appelle…";
        }
        if (t >= 6.5 && t < 8.4) {
            return "Dieu du Tonnerre Volant : Rasengan Éclair !";
        }
        return t > 13.4 && t < 15.2 ? "Rien de spécial…" : "";
    }

    private static void ecran(GuiGraphics g) {
        Minecraft mc = Minecraft.getInstance();
        if (vue == null || mc.player == null) {
            return;
        }
        double t = t(vue);
        int w = g.guiWidth(), h = g.guiHeight();
        int bande = (int) (h * 0.11 * lisse(entre(t, 0.95, 1.4)) * (1 - lisse(entre(t, 15.9, 16.4))));
        g.fill(0, 0, w, bande, 0xFF000000);
        g.fill(0, h - bande, w, h, 0xFF000000);
        float f = 0;
        int couleur = 0xFFFFFF;
        double[][] flashs = {{1.0, 0.35, 0xFFD63C, 0.55}, {6.45, 0.6, 0xFF8C28, 0.8}, {8.6, 0.7, 0xFFFFFF, 1}, {10.6, 1.0, 0xEBF8FF, 1}};
        for (double[] fl : flashs) {
            double age = t - fl[0];
            if (age >= 0 && age < fl[1] && fl[3] * (1 - age / fl[1]) > f) {
                f = (float) (fl[3] * (1 - age / fl[1]));
                couleur = (int) fl[2];
            }
        }
        for (int j = 0; j < SEQ.length; j++) {
            double age = t - (3.8 + j * PAS);
            if (age >= 0 && age < 0.12 && 0.22 * (1 - age / 0.12) > f) {
                f = (float) (0.22 * (1 - age / 0.12));
                couleur = 0xFFD63C;
            }
        }
        if (f > 0.01F) {
            g.fill(0, 0, w, h, ((int) (Math.min(1, f) * 255) << 24) | couleur);
        }
        String st = sousTitre(t);
        if (!st.isEmpty()) {
            fr.cabane.client.ui.Ios.texteCentre(g, mc.font, st, w / 2F, h - bande - 24, 1.6F, 0xFFFFFFFF, true);
        }
        if (mc.player.getUUID().equals(vue.lanceur) && t < 1.8) {
            int a = (int) (255 * (1 - entre(t, 1.5, 1.8)));
            if (a > 8) {
                fr.cabane.client.ui.Ios.texteCentre(g, mc.font, "Touché !", w / 2F, h * 0.4F, 2.6F, (a << 24) | 0xFFD23A, true);
            }
        }
    }

    // ================================================================ branchements

    @EventBusSubscriber(modid = Cabane.MODID, value = Dist.CLIENT)
    public static final class Couches {
        @SubscribeEvent
        public static void enregistrer(RegisterGuiLayersEvent event) {
            event.registerAboveAll(Cabane.id("minato_cinema"), (g, delta) -> ecran(g));
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
                SCENES.clear();
                if (vue != null) {
                    sortir();
                }
                return;
            }
            for (Scene s : new ArrayList<>(SCENES.values())) {
                double t = t(s);
                evenements(s, s.avant, t);
                s.avant = t;
                if (t >= FIN) {
                    arreter(s.lanceur);
                }
            }
            if (vue != null && mc.player.isDeadOrDying()) {
                sortir();
            } else if (vue != null && objectif != null && mc.getCameraEntity() != objectif) {
                mc.setCameraEntity(objectif);
            }
        }

        @SubscribeEvent
        public static void image(RenderFrameEvent.Pre event) {
            if (vue != null) {
                placerCamera(vue, t(vue));
            }
        }

        @SubscribeEvent
        public static void dessin(RenderLevelStageEvent event) {
            if (SCENES.isEmpty() || event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES || Minecraft.getInstance().level == null) {
                return;
            }
            dessiner(event.getPoseStack(), event.getCamera().getPosition(), event.getPartialTick().getGameTimeDeltaPartialTick(false));
        }

        @SubscribeEvent
        public static void brouillard(ViewportEvent.ComputeFogColor event) {
            if (vue == null) {
                return;
            }
            float c = (float) cosmos(t(vue));
            event.setRed(Mth.lerp(c, event.getRed(), 0.13F));
            event.setGreen(Mth.lerp(c, event.getGreen(), 0.05F));
            event.setBlue(Mth.lerp(c, event.getBlue(), 0.24F));
        }

        @SubscribeEvent
        public static void champ(ViewportEvent.ComputeFov event) {
            if (vue != null) {
                event.setFOV(fov);
            }
        }

        @SubscribeEvent
        public static void immobile(MovementInputUpdateEvent event) {
            if (vue != null) {
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
            if (vue != null) {
                event.setCanceled(true);
                event.setSwingHand(false);
            }
        }

        @SubscribeEvent
        public static void pasDeMain(RenderHandEvent event) {
            if (vue != null) {
                event.setCanceled(true);
            }
        }

        @SubscribeEvent
        public static void pasDInterface(RenderGuiLayerEvent.Pre event) {
            if (vue != null && !event.getName().equals(Cabane.id("minato_cinema"))) {
                event.setCanceled(true);
            }
        }

        private Evenements() {
        }
    }

    private ClientMinatoCombo() {
    }
}
