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
import org.joml.Vector3f;

/**
 * La Bijuu Dama de Minato 2 (le clic droit du mode Kurama), chez chaque joueur à portée, d'après l'animation validée
 * (docs/minato2/apercu-bijuu-dama.mp4, mêmes plans, mêmes temps). Les vrais lanceur et cible restent figés et cachés ;
 * leurs doubles jouent la scène : le lanceur dans sa peau du mode Kurama, la cible avec son vrai modèle. Le lanceur et
 * la cible la voient en cinématique (caméra de cinéma, bandes noires, flashs, sous-titre) ; les autres dans le monde.
 * Kurama (voir {@link ModeleKurama}) apparaît derrière le lanceur, rugit, forme la boule noire devant sa gueule, la
 * comprime, la tire ; elle touche la cible et le dôme s'élève (10 blocs). Le temps t part du coup (0 s) et finit à 13,3 s.
 */
public final class ClientBijuuDama {
    private static final double FIN = Minato.DUREE_BIJUU / 20.0;
    /** Kurama : 12 blocs de haut. */
    private static final double HAUTEUR = 12;
    private static final RandomSource HASARD = RandomSource.create();
    private static final DustParticleOptions NOIR = new DustParticleOptions(new Vector3f(0.12F, 0.05F, 0.18F), 1.6F);
    private static final DustParticleOptions ROUGE = new DustParticleOptions(new Vector3f(0.9F, 0.15F, 0.1F), 1.4F);
    private static final DustParticleOptions BLEU = new DustParticleOptions(new Vector3f(0.25F, 0.4F, 1F), 1.2F);

    private static final Posture BRAS_OUVERTS = new Posture(-0.4F, 0, -6, -0.3F, 0, 0.9F, -0.3F, 0, -0.9F, 0, 0.15F, 0, -0.15F);

    /** Une Bijuu Dama : qui, sur qui, ses points (voir {@link Minato#B_CIBLE}), son axe f (lanceur → cible) et son côté g. */
    private static final class Scene {
        UUID lanceur;
        int cible;
        Vec3[] p;
        Vec3 f;
        Vec3 g;
        long debut;
        double avant;
    }

    private static final Map<UUID, Scene> SCENES = new HashMap<>();
    static boolean dessinEnCours;

    // ---- la cinématique, quand on est le lanceur ou la cible
    private static Scene vue;
    private static Entity objectif;
    private static CameraType vueAvant;
    private static boolean guiAvant;
    private static float fov = 60;

    private static double secondes(long depuis) {
        return (System.nanoTime() - depuis) / 1e9;
    }

    private static double t(Scene s) {
        return secondes(s.debut);
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

    static void demarrer(Minato.BijuuDama c) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            return;
        }
        Scene s = new Scene();
        s.lanceur = c.joueur();
        s.cible = c.cible();
        s.p = c.points().stream().map(v -> new Vec3(v.x(), v.y(), v.z())).toArray(Vec3[]::new);
        Vec3 o = s.p[Minato.B_CIBLE], d = s.p[Minato.B_LUI];
        Vec3 f = new Vec3(o.x - d.x, 0, o.z - d.z);
        s.f = f.lengthSqr() < 1.0E-4 ? new Vec3(0, 0, 1) : f.normalize();
        s.g = new Vec3(s.f.z, 0, -s.f.x);
        s.debut = System.nanoTime();
        Scene ancienne = SCENES.put(s.lanceur, s);
        if (ancienne != null && ancienne == vue) {
            sortir();
        }
        if (mc.player.getUUID().equals(s.lanceur) || mc.player.getId() == s.cible) {
            entrer(s);
        }
    }

    /** Pour l'outil de captures : la Bijuu Dama jouée sans serveur sur la créature la plus proche, par le joueur, vers l'est. */
    public static void demo() {
        Minecraft mc = Minecraft.getInstance();
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
        Vec3 o = cible.position(), f = new Vec3(1, 0, 0);
        Vec3 lui = o.subtract(f.scale(1.6));
        Vec3[] p = {o, lui, o.add(f.scale(4.5)), lui.subtract(f.scale(7))};
        List<Vector3f> points = new ArrayList<>();
        for (Vec3 q : p) {
            points.add(new Vector3f((float) q.x, (float) q.y, (float) q.z));
        }
        demarrer(new Minato.BijuuDama(mc.player.getUUID(), cible.getId(), points));
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

    static boolean participant() {
        return vue != null;
    }

    /** Ce vrai lanceur ou cette vraie cible est caché pendant la scène (sauf quand on dessine son double). */
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

    // ================================================================ le scénario (les mêmes temps que l'animation)

    /** La cible : projetée du coup de poing (0,4 à 1,3 s) jusqu'où elle restera. */
    private static Vec3 posCible(Scene s, double t) {
        Vec3 o = s.p[Minato.B_CIBLE], e = s.p[Minato.B_PROJETEE];
        if (t < 0.4) {
            return o;
        }
        if (t < 1.3) {
            double u = entre(t, 0.4, 1.3);
            return o.lerp(e, 1 - (1 - u) * (1 - u)).add(0, 1.2 * Math.sin(u * Math.PI), 0);
        }
        return e;
    }

    /** De combien la cible bascule en arrière (radians, négatif : vers l'arrière) : en vol, puis au sol après l'impact. */
    private static double bascule(double t) {
        if (t < 0.4) {
            return 0;
        }
        if (t < 1.3) {
            return -1.0 * Math.sin(entre(t, 0.4, 1.3) * Math.PI);
        }
        if (t < 8.4) {
            return 0;
        }
        return -Math.PI / 2 * lisse(entre(t, 8.4, 9.0));
    }

    private static Posture posture(double t) {
        if (t < 1.4) {
            float k = (float) (lisse(entre(t, 0.05, 0.3)) * (1 - lisse(entre(t, 0.7, 1.3))));
            return Posture.DEBOUT.vers(Posture.COUP, k);
        }
        if (t < 2.2) {
            return Posture.DEBOUT.vers(BRAS_OUVERTS, (float) lisse(entre(t, 1.4, 1.6)));
        }
        if (t < 4.2) {
            return BRAS_OUVERTS.vers(Posture.DEBOUT, (float) lisse(entre(t, 2.2, 2.6)));
        }
        if (t < 7.6) {
            return Posture.DEBOUT.vers(Posture.TIR, (float) lisse(entre(t, 4.2, 4.6)));
        }
        return Posture.TIR.vers(Posture.DEBOUT, (float) lisse(entre(t, 7.6, 8.4)));
    }

    /** Kurama apparaît (2,2 à 3,2 s), reste, puis s'efface en rapetissant (12 à 12,6 s). */
    private static double kurama(double t) {
        return lisse(entre(t, 2.2, 3.2)) * (1 - lisse(entre(t, 12.0, 12.6)));
    }

    private static Vec3 gueule(Scene s, double t) {
        return ModeleKurama.gueule(s.p[Minato.B_KURAMA], s.f, HAUTEUR, Math.max(0.2, kurama(t)));
    }

    /** Où est la boule (qui se forme devant la gueule, puis file jusqu'à la cible). */
    private static Vec3 boule(Scene s, double t) {
        Vec3 devant = gueule(s, t).add(s.f.scale(1.8));
        if (t < 7.4) {
            return devant;
        }
        return devant.lerp(s.p[Minato.B_PROJETEE].add(0, 1, 0), lisse(entre(t, 7.4, 8.4)));
    }

    private static float lacet(Vec3 de, Vec3 vers) {
        return (float) Math.toDegrees(Math.atan2(-(vers.x - de.x), vers.z - de.z));
    }

    // ================================================================ la caméra

    private static Vec3 camera = Vec3.ZERO;
    private static Vec3 vise = Vec3.ZERO;

    private static Vec3 vers(Vec3 a, Vec3 b, double u) {
        return a.lerp(b, lisse(u));
    }

    private static void plan(Scene s, double t) {
        Vec3 lui = s.p[Minato.B_LUI], e = s.p[Minato.B_PROJETEE], k = s.p[Minato.B_KURAMA];
        Vec3 milieu = lui.lerp(e, 0.5);
        if (t < 1.4) {
            double u = entre(t, 0, 1.4);
            camera = vers(l(s, milieu, -0.5, 1.8, -6), l(s, milieu, 0.3, 1.9, -5.5), u);
            vise = l(s, milieu, 0, 1.0, 0);
            fov = 55;
        } else if (t < 2.2) {
            camera = vers(l(s, lui, 3.4, 0.9, 0.4), l(s, lui, 2.8, 1.0, 0.3), entre(t, 1.4, 2.2));
            vise = l(s, lui, 0, 1.3, 0);
            fov = 50;
        } else if (t < 3.4) {
            double u = entre(t, 2.2, 3.4);
            camera = vers(l(s, lui, 5, 2, -1.5), l(s, lui, 16, 5, -7), u);
            vise = vers(l(s, lui, 0, 1.5, 0), l(s, k, 0, 6, 0), u);
            fov = 60;
        } else if (t < 4.2) {
            Vec3 tete = l(s, k, 3.0, 7.0, 0);
            camera = vers(l(s, tete, 6, 0.2, -2.5), l(s, tete, 5.2, 0.4, -2.2), entre(t, 3.4, 4.2));
            vise = tete;
            fov = 50;
        } else if (t < 5.0) {
            camera = vers(l(s, lui, 1.8, 1.5, -0.5), l(s, lui, 1.5, 1.45, -0.4), entre(t, 4.2, 5.0));
            vise = l(s, lui, 0, 1.3, 0);
            fov = 45;
        } else if (t < 6.2) {
            Vec3 b = boule(s, t);
            camera = vers(l(s, b, 3, 1, -10), l(s, b, 2, 1, -9), entre(t, 5.0, 6.2));
            vise = l(s, b, -1, 0, 0);
            fov = 55;
        } else if (t < 7.4) {
            Vec3 b = boule(s, t);
            camera = vers(l(s, b, 4.5, 0.4, -0.8), l(s, b, 3.5, 0.3, -0.6), entre(t, 6.2, 7.4));
            vise = b;
            fov = 50;
        } else if (t < 8.4) {
            Vec3 m = k.lerp(e, 0.5);
            camera = l(s, m, 0, 6, -22);
            vise = l(s, m, 0, 3, 0);
            fov = 60;
        } else if (t < 9.6) {
            camera = l(s, e, -6, 2.5, -4);
            vise = l(s, e, 0, 1, 0);
            fov = 55;
        } else if (t < 12.3) {
            double a = 0.5 + 0.4 * entre(t, 9.6, 12.3);
            camera = e.add(s.f.scale(-16 * Math.cos(a))).add(s.g.scale(-16 * Math.sin(a))).add(0, 24, 0);
            vise = e;
            fov = 65;
        } else {
            camera = vers(l(s, e, 3, 3, -5), l(s, e, 2.6, 2.6, -4.4), entre(t, 12.3, FIN));
            vise = l(s, e, 0, 0.3, 0);
            fov = 55;
        }
    }

    private static double secousse(double t) {
        double s = 0;
        for (double[] q : new double[][] {{3.4, 0.8, 0.25}, {8.4, 0.9, 0.3}, {9.6, 1.4, 0.5}}) {
            if (t >= q[0] && t < q[0] + q[1]) {
                s += q[2] * (1 - (t - q[0]) / q[1]);
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

    private static void sommet(VertexConsumer c, Matrix4f m, Vec3 p, float r, float g, float b, float a) {
        c.addVertex(m, (float) p.x, (float) p.y, (float) p.z).setColor(r, g, b, a);
    }

    /** La boule noire, pleine (pas de la lumière : elle doit être noire), aux reflets violets sur le bord. */
    private static void bouleNoire(PoseStack pose, MultiBufferSource buffers, Vec3 c0, double r) {
        if (r <= 0.01) {
            return;
        }
        VertexConsumer c = buffers.getBuffer(RenderType.debugQuads());
        Matrix4f m = pose.last().pose();
        int lat = 10, lon = 18;
        Vec3 versCam = c0.reverse().normalize();
        for (int i = 0; i < lat; i++) {
            double p0 = Math.PI * i / lat - Math.PI / 2, p1 = Math.PI * (i + 1) / lat - Math.PI / 2;
            for (int j = 0; j < lon; j++) {
                double q0 = Math.PI * 2 * j / lon, q1 = Math.PI * 2 * (j + 1) / lon;
                Vec3 n = new Vec3(Math.cos((p0 + p1) / 2) * Math.cos((q0 + q1) / 2), Math.sin((p0 + p1) / 2), Math.cos((p0 + p1) / 2) * Math.sin((q0 + q1) / 2));
                float bord = (float) Math.pow(1 - Math.abs(n.dot(versCam)), 3);
                float rr = 0.06F + 0.3F * bord, gg = 0.03F + 0.14F * bord, bb = 0.1F + 0.42F * bord;
                for (double[] ab : new double[][] {{p0, q0}, {p1, q0}, {p1, q1}, {p0, q1}}) {
                    Vec3 p = c0.add(Math.cos(ab[0]) * Math.cos(ab[1]) * r, Math.sin(ab[0]) * r, Math.cos(ab[0]) * Math.sin(ab[1]) * r);
                    sommet(c, m, p, rr, gg, bb, 1F);
                }
            }
        }
    }

    private static ResourceLocation skin(Scene s) {
        Minecraft mc = Minecraft.getInstance();
        Player p = mc.level.getPlayerByUUID(s.lanceur);
        return p instanceof AbstractClientPlayer c ? c.getSkin().texture() : DefaultPlayerSkin.getDefaultTexture();
    }

    /** La cible, avec son vrai modèle, déplacée et basculée par la scène. */
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
            Vec3 lui = s.p[Minato.B_LUI], e = s.p[Minato.B_PROJETEE];
            if (mc.level.getEntity(s.cible) instanceof LivingEntity v && v.isAlive()) {
                cible(pose, buffers, s, v, t, cam, partiel);
            }
            float lac = lacet(lui, s.p[Minato.B_CIBLE]);
            Effets.personnage(pose, buffers, skin(s), lui.subtract(cam), lac, posture(t), 1F, Effets.SANS, Effets.NOIR, 1F);
            buffers.endBatch();
            // Kurama, derrière lui
            double k = kurama(t);
            if (k > 0.01) {
                ModeleKurama.dessiner(pose, buffers, s.p[Minato.B_KURAMA].subtract(cam), s.f, HAUTEUR, k);
                buffers.endBatch();
            }
            // la boule noire, pleine : elle se forme (5 à 6,2 s), se comprime (jusqu'à 7,4 s)
            double rayon = t < 5.0 ? 0 : t < 6.2 ? 1.5 * lisse(entre(t, 5.0, 6.0)) : t < 7.4 ? 1.5 - 0.5 * lisse(entre(t, 6.2, 7.2)) : 0;
            Vec3 b = boule(s, t);
            if (rayon > 0.01) {
                bouleNoire(pose, buffers, b.subtract(cam), rayon);
                buffers.endBatch(RenderType.debugQuads());
            }
            // la lumière : le pilier, l'aura, le cœur rouge, la boule tirée, l'impact, le dôme
            if (t >= 1.4 && t < 2.4) {
                float a = (float) (Math.sin(entre(t, 1.4, 2.4) * Math.PI));
                Effets.rayon(pose, buffers, lui.subtract(cam), lui.add(0, 40, 0).subtract(cam), 0.9 * a, 1F, 0.95F, 0.7F, (float) t);
            }
            Effets.aura(pose, buffers, lui.subtract(cam), t >= 1.4 && t < 2.4 ? 0.9F : 0.3F, 1F, 0.78F, 0.25F, (float) t);
            if (t >= 4.2 && t < 5.2) {
                // la lumière qui naît entre ses mains
                double r = Math.toRadians(lac);
                Vec3 mains = lui.add(-Math.sin(r) * 0.75, 1.3, Math.cos(r) * 0.75);
                Effets.sphere(pose, buffers, mains.subtract(cam), 0.06 + 0.1 * entre(t, 4.2, 5.0), 1F, 0.95F, 0.75F, 0.8F);
            }
            if (t >= 5.6 && t < 7.4) {
                double c = entre(t, 5.6, 7.2);
                Effets.sphere(pose, buffers, b.add(s.f.scale(rayon * 0.6)).subtract(cam), rayon * (0.25 + 0.4 * c), 1F, 0.2F + 0.4F * (float) c, 0.08F, (float) (0.5 + 0.5 * c));
            }
            if (t >= 7.4 && t < 8.5) {
                Vec3 de = gueule(s, t).add(s.f.scale(1.8));
                Effets.tube(pose, buffers, de.subtract(cam), b.subtract(cam), 0.5, 1F, 0.55F, 0.12F, 0.6F);
                Effets.sphere(pose, buffers, b.subtract(cam), 1.1, 1F, 0.75F, 0.3F, 1F);
            }
            if (t >= 8.4 && t < 9.8) {
                double u = entre(t, 8.4, 9.6);
                Effets.sphere(pose, buffers, e.add(0, 1, 0).subtract(cam), 0.6 + 3.2 * lisse(u), 1F, 0.55F, 0.12F, (float) (1 - 0.6 * u));
            }
            if (t >= 9.6 && t < 12.4) {
                double u = entre(t, 9.6, 11.0);
                double rd = 10 * (1 - Math.pow(1 - u, 3));
                float op = (float) (1 - entre(t, 11.4, 12.4));
                Effets.sphere(pose, buffers, e.subtract(cam), rd, 1F, 0.82F, 0.45F, 0.75F * op);
                Effets.sphere(pose, buffers, e.subtract(cam), rd * 0.7, 1F, 0.97F, 0.85F, 0.6F * op);
                Effets.onde(pose, buffers, e.add(0, 0.1, 0).subtract(cam), rd * 1.05, 2.5, op);
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

    private static void son(Vec3 p, SoundEvent e, float volume, float hauteur) {
        Minecraft.getInstance().level.playLocalSound(p.x, p.y, p.z, e, SoundSource.PLAYERS, volume, hauteur, false);
    }

    private static void gerbe(ParticleOptions type, Vec3 c, int n, double d, double vitesse) {
        for (int i = 0; i < n; i++) {
            Minecraft.getInstance().level.addParticle(type, c.x + aleat(d), c.y + aleat(d), c.z + aleat(d), aleat(vitesse), aleat(vitesse), aleat(vitesse));
        }
    }

    private static void evenements(Scene s, double a, double t) {
        Vec3 lui = s.p[Minato.B_LUI], e = s.p[Minato.B_PROJETEE], k = s.p[Minato.B_KURAMA];
        if (passe(a, t, 0.05)) {
            son(lui, fr.cabane.CabaneSons.MINATO_FLASH.get(), 1F, 1F);
            gerbe(ParticleTypes.ELECTRIC_SPARK, lui.add(0, 1, 0), 20, 0.4, 0.3);
        }
        if (passe(a, t, 0.3)) {
            son(s.p[Minato.B_CIBLE], SoundEvents.PLAYER_ATTACK_KNOCKBACK, 1.2F, 0.8F);
            gerbe(ParticleTypes.CRIT, s.p[Minato.B_CIBLE].add(0, 1.2, 0), 25, 0.6, 0.4);
        }
        if (passe(a, t, 1.4)) {
            son(lui, SoundEvents.BEACON_ACTIVATE, 1.2F, 0.8F);
            gerbe(ParticleTypes.END_ROD, lui.add(0, 1.5, 0), 40, 0.8, 0.25);
        }
        if (passe(a, t, 2.2)) {
            son(k, SoundEvents.RAVAGER_ROAR, 2F, 0.5F);
            gerbe(ParticleTypes.FLAME, k.add(0, 4, 0), 60, 4, 0.2);
        }
        if (passe(a, t, 3.4)) {
            son(k, SoundEvents.ENDER_DRAGON_GROWL, 2F, 0.8F);
        }
        if (passe(a, t, 5.0)) {
            son(boule(s, t), SoundEvents.WARDEN_SONIC_CHARGE, 2F, 0.6F);
        }
        if (t >= 5.0 && t < 7.4 && Minecraft.getInstance().level.getGameTime() % 2 == 0) {
            // les éclats de chakra (bleus, rouges, noirs) qui convergent vers la boule
            Vec3 b = boule(s, t);
            for (int i = 0; i < 6; i++) {
                Vec3 d = new Vec3(aleat(1), aleat(1), aleat(1)).normalize().scale(4 + HASARD.nextDouble() * 3);
                Vec3 p = b.add(d);
                Minecraft.getInstance().level.addParticle(i % 3 == 0 ? NOIR : i % 3 == 1 ? ROUGE : BLEU, p.x, p.y, p.z, -d.x * 0.15, -d.y * 0.15, -d.z * 0.15);
            }
        }
        if (passe(a, t, 6.2)) {
            son(boule(s, t), SoundEvents.RESPAWN_ANCHOR_CHARGE, 2F, 0.5F);
        }
        if (passe(a, t, 7.4)) {
            son(gueule(s, t), SoundEvents.WARDEN_SONIC_BOOM, 3F, 0.7F);
        }
        if (passe(a, t, 8.4)) {
            son(e, SoundEvents.GENERIC_EXPLODE.value(), 3F, 0.7F);
            son(e, SoundEvents.LIGHTNING_BOLT_IMPACT, 2F, 0.7F);
            gerbe(ParticleTypes.EXPLOSION, e.add(0, 1, 0), 6, 1.2, 0);
        }
        if (passe(a, t, 9.6)) {
            son(e, SoundEvents.GENERIC_EXPLODE.value(), 4F, 0.5F);
            son(e, SoundEvents.LIGHTNING_BOLT_THUNDER, 3F, 0.6F);
            var monde = Minecraft.getInstance().level;
            monde.addParticle(ParticleTypes.EXPLOSION_EMITTER, e.x, e.y + 1, e.z, 0, 0, 0);
            var sous = monde.getBlockState(BlockPos.containing(e).below());
            if (!sous.isAir()) {
                for (int i = 0; i < 120; i++) {
                    monde.addParticle(new BlockParticleOption(ParticleTypes.BLOCK, sous), e.x + aleat(4), e.y + 0.3, e.z + aleat(4),
                            aleat(1), 0.4 + HASARD.nextDouble() * 1.2, aleat(1));
                }
            }
            gerbe(ParticleTypes.END_ROD, e.add(0, 2, 0), 80, 4, 0.5);
        }
    }

    // ================================================================ l'écran (bandes noires, flashs, sous-titre)

    private static void ecran(GuiGraphics g) {
        Minecraft mc = Minecraft.getInstance();
        if (vue == null || mc.player == null) {
            return;
        }
        double t = t(vue);
        int w = g.guiWidth(), h = g.guiHeight();
        int bande = (int) (h * 0.11 * lisse(entre(t, 0, 0.4)) * (1 - lisse(entre(t, FIN - 0.5, FIN))));
        g.fill(0, 0, w, bande, 0xFF000000);
        g.fill(0, h - bande, w, h, 0xFF000000);
        float f = 0;
        int couleur = 0xFFFFFF;
        double[][] flashs = {{0.05, 0.3, 0xFFD63C, 0.5}, {1.4, 0.5, 0xFFF4C8, 0.7}, {8.4, 0.6, 0xFF8C28, 0.8}, {9.6, 0.5, 0xFFFFFF, 0.9}};
        for (double[] fl : flashs) {
            double age = t - fl[0];
            if (age >= 0 && age < fl[1] && fl[3] * (1 - age / fl[1]) > f) {
                f = (float) (fl[3] * (1 - age / fl[1]));
                couleur = (int) fl[2];
            }
        }
        // le grand flash blanc de la fin (le dôme), qui retombe sur la cible au sol
        double blanc = t < 12.3 ? lisse(entre(t, 11.8, 12.3)) : 1 - lisse(entre(t, 12.3, 12.8));
        if (blanc > f) {
            f = (float) blanc;
            couleur = 0xFFFFFF;
        }
        if (f > 0.01F) {
            g.fill(0, 0, w, h, ((int) (Math.min(1, f) * 255) << 24) | couleur);
        }
        if (t > 6.6 && t < 8.2) {
            fr.cabane.client.ui.Ios.texteCentre(g, mc.font, "Bijuu Dama !", w / 2F, h - bande - 24, 1.8F, 0xFFFFE08A, true);
        }
        if (mc.player.getUUID().equals(vue.lanceur) && t < 1.0) {
            int a = (int) (255 * (1 - entre(t, 0.7, 1.0)));
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
            event.registerAboveAll(Cabane.id("minato_bijuu"), (g, delta) -> ecran(g));
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
            if (vue != null && !event.getName().equals(Cabane.id("minato_bijuu"))) {
                event.setCanceled(true);
            }
        }

        private Evenements() {
        }
    }

    private ClientBijuuDama() {
    }
}
