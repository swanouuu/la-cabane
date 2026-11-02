package fr.cabane.client.pouvoir;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import fr.cabane.Cabane;
import fr.cabane.client.cinema.Effets;
import fr.cabane.pouvoir.Maudit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
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
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * Le Chidori de la marque maudite niveau 2 (voir {@link Maudit}), d'après la vidéo de référence : accroupi, il charge
 * le Chidori la main au sol (des éclairs noirs et blancs crépitent) ; gros plan sur son œil ; un coup d'ailes le jette
 * sur sa cible ; gros plan sur son visage, le Chidori à la main ; « Chidori !! », l'impact, une énorme sphère d'éclairs
 * noirs qui enfle autour de la cible ; un flash blanc ; il se relève. Le lanceur et sa cible le voient en cinématique
 * (caméra de cinéma, bandes noires, flashs, sous-titre) ; les autres le voient dans le monde : le vrai lanceur file sur
 * sa cible, les éclairs, la sphère. Les temps sont ceux du serveur.
 */
public final class ClientChidori {
    private static final double RUEE = Maudit.RUEE / 20.0;
    private static final double ARRIVEE = Maudit.ARRIVEE / 20.0;
    private static final double IMPACT = Maudit.IMPACT / 20.0;
    private static final double SPHERE = Maudit.SPHERE / 20.0;
    private static final double FLASH = Maudit.FLASH / 20.0;
    private static final double FIN = Maudit.FIN / 20.0;
    /** Le gros plan sur l'œil, pendant la charge. */
    private static final double OEIL = 1.2;
    /** La sphère d'éclairs noirs, au plus grand. */
    private static final double RAYON = 4.5;
    private static final RandomSource HASARD = RandomSource.create();

    /** Un Chidori : qui, sur qui, d'où, d'où il frappe, la cible ; son axe f (lanceur → cible) et son côté g. */
    private static final class Scene {
        UUID lanceur;
        int cible;
        Vec3 depart;
        Vec3 impact;
        Vec3 centre;
        Vec3 f;
        Vec3 g;
        long debut;
        double avant = -1;
    }

    private static final Map<UUID, Scene> SCENES = new HashMap<>();

    // ---- la cinématique, quand on est le lanceur ou la cible
    private static Scene vue;
    private static Entity objectif;
    private static CameraType vueAvant;
    private static boolean guiAvant;
    private static float fov = 70;

    private static double t(Scene s) {
        return (System.nanoTime() - s.debut) / 1e9;
    }

    private static double entre(double t, double a, double b) {
        return Mth.clamp((t - a) / (b - a), 0, 1);
    }

    private static double lisse(double k) {
        k = Mth.clamp(k, 0, 1);
        return k * k * (3 - 2 * k);
    }

    /** Un point autour de base : a le long de f, h en hauteur, b le long de g. */
    private static Vec3 l(Scene s, Vec3 base, double a, double h, double b) {
        return base.add(s.f.scale(a)).add(0, h, 0).add(s.g.scale(b));
    }

    // ================================================================ départ, arrêt

    public static void recevoir(Maudit.Chidori p) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            return;
        }
        if (p.arret()) {
            arreter(p.lanceur());
            return;
        }
        Scene s = new Scene();
        s.lanceur = p.lanceur();
        s.cible = p.cible();
        s.depart = new Vec3(p.depart());
        s.impact = new Vec3(p.impact());
        s.centre = new Vec3(p.centre());
        Vec3 f = new Vec3(s.centre.x - s.depart.x, 0, s.centre.z - s.depart.z);
        s.f = f.lengthSqr() < 1.0E-4 ? new Vec3(0, 0, 1) : f.normalize();
        s.g = new Vec3(s.f.z, 0, -s.f.x);
        s.debut = System.nanoTime();
        Scene ancienne = SCENES.put(s.lanceur, s);
        if (ancienne != null && ancienne == vue) {
            sortir();
        }
        if (mc.player.getUUID().equals(s.lanceur)) {
            // il se tourne vers sa cible ; le Chidori se recharge (comme sur le serveur, à partir du flash)
            mc.player.setYRot((float) Math.toDegrees(Math.atan2(-s.f.x, s.f.z)));
            mc.player.setXRot(10);
            pret = s.debut + (long) ((FLASH + Maudit.RECHARGE_CHIDORI / 20.0) * 1e9);
        }
        if (mc.player.getUUID().equals(s.lanceur) || mc.player.getId() == s.cible) {
            entrer(s);
        }
    }

    private static void arreter(UUID lanceur) {
        Scene s = SCENES.remove(lanceur);
        if (s != null && s == vue) {
            sortir();
        }
    }

    /** Quand son propre Chidori sera rechargé (System.nanoTime). */
    private static long pret;

    /** Les secondes avant que son Chidori soit rechargé (0 : prêt). */
    static double recharge() {
        return Math.max(0, (pret - System.nanoTime()) / 1e9);
    }

    /** Ce joueur est-il pris dans un Chidori (lanceur ou cible) ? */
    static boolean enCours(Player joueur) {
        for (Scene s : SCENES.values()) {
            if (s.lanceur.equals(joueur.getUUID()) || s.cible == joueur.getId()) {
                return true;
            }
        }
        return false;
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

    // ================================================================ le lanceur et sa main

    private static Player lanceur(Scene s) {
        Minecraft mc = Minecraft.getInstance();
        return mc.level == null ? null : mc.level.getPlayerByUUID(s.lanceur);
    }

    /** Où il est : là où le serveur le tient (on le suit en vrai s'il est là, sinon on le calcule). */
    private static Vec3 pieds(Scene s, double t, float partiel) {
        Player p = lanceur(s);
        if (p != null) {
            return p.getPosition(partiel);
        }
        double k = entre(t, RUEE, ARRIVEE);
        return s.depart.lerp(s.impact, k * k);
    }

    /** Sa main droite, qui tient le Chidori (plus bas et un peu en avant quand il est accroupi pour charger). */
    private static Vec3 main(Scene s, double t, float partiel) {
        Vec3 p = pieds(s, t, partiel);
        Player qui = lanceur(s);
        Vec3 devant = s.f, droite = s.g.scale(-1);
        if (qui != null) {
            float lacet = Mth.rotLerp(partiel, qui.yBodyRotO, qui.yBodyRot) * Mth.DEG_TO_RAD;
            devant = new Vec3(-Mth.sin(lacet), 0, Mth.cos(lacet));
            droite = new Vec3(-Mth.cos(lacet), 0, -Mth.sin(lacet));
        }
        boolean accroupi = qui != null ? qui.isCrouching() : t < RUEE;
        return p.add(droite.scale(0.36)).add(devant.scale(accroupi ? 0.3 : 0.08)).add(0, accroupi ? 0.5 : 0.62, 0);
    }

    // ================================================================ la caméra

    private static Vec3 camera = Vec3.ZERO;
    private static Vec3 vise = Vec3.ZERO;

    private static Vec3 vers(Vec3 a, Vec3 b, double u) {
        return a.lerp(b, lisse(u));
    }

    private static void plan(Scene s, double t, float partiel) {
        Vec3 p = pieds(s, t, partiel), c = s.centre, i = s.impact;
        if (t < OEIL) {
            // au ras du sol, sur la main qui charge
            Vec3 m = main(s, t, partiel);
            camera = vers(l(s, m, 2.6, 0.6, -1.7), l(s, m, 2.1, 0.55, -1.35), entre(t, 0, OEIL));
            vise = m.add(0, 0.45, 0);
            fov = 55;
        } else if (t < RUEE) {
            // son visage, l'œil rouge : de près
            Player qui = lanceur(s);
            Vec3 oeil = qui != null ? qui.getEyePosition(partiel) : p.add(0, 1.27, 0);
            camera = vers(l(s, oeil, 1.15, 0.05, 0.15), l(s, oeil, 0.95, 0.04, 0.12), entre(t, OEIL, RUEE));
            vise = oeil;
            fov = 35;
        } else if (t < ARRIVEE) {
            // la ruée, de côté
            Vec3 milieu = s.depart.lerp(i, 0.5);
            camera = l(s, milieu, 0, 1.6, 6.5).add(s.f.scale(-1 + 2 * entre(t, RUEE, ARRIVEE)));
            vise = p.add(0, 1, 0);
            fov = 60;
        } else if (t < IMPACT) {
            // son visage, le Chidori à la main
            camera = vers(l(s, p, 1.4, 1.5, -0.4), l(s, p, 1.1, 1.5, -0.35), entre(t, ARRIVEE, IMPACT));
            vise = p.add(0, 1.4, 0);
            fov = 40;
        } else if (t < SPHERE) {
            // l'impact, de trois quarts (hors de la sphère qui enfle)
            camera = vers(l(s, i, -2.0, 1.9, 4.6), l(s, i, -2.6, 2.3, 5.4), entre(t, IMPACT, SPHERE));
            vise = c.add(0, 1, 0);
            fov = 60;
        } else if (t < FLASH) {
            // la sphère, de loin
            camera = vers(l(s, c, -8, 3.5, 6), l(s, c, -9.5, 4.5, 7.5), entre(t, SPHERE, FLASH));
            vise = c.add(0, 1.5, 0);
            fov = 65;
        } else {
            // il se relève : en contre-plongée
            camera = vers(l(s, p, 2.2, 0.35, 1.2), l(s, p, 2.0, 0.3, 1.0), entre(t, FLASH, FIN));
            vise = p.add(0, 1.5, 0);
            fov = 50;
        }
    }

    private static void placerCamera(Scene s, double t) {
        if (objectif == null) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        float partiel = mc.getTimer().getGameTimeDeltaPartialTick(false);
        plan(s, t, partiel);
        // la caméra ne passe pas à travers les murs : elle s'arrête devant
        HitResult mur = mc.level.clip(new ClipContext(vise, camera, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, objectif));
        if (mur.getType() != HitResult.Type.MISS) {
            camera = mur.getLocation().add(vise.subtract(mur.getLocation()).normalize().scale(0.3));
        }
        double k = t >= IMPACT && t < IMPACT + 0.6 ? 0.25 * (1 - (t - IMPACT) / 0.6) : t >= SPHERE && t < SPHERE + 0.5 ? 0.15 : 0;
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

    // ================================================================ le dessin dans le monde

    /** Le noir des éclairs maudits : translucide (la lumière additive ne sait pas faire du noir). */
    private static final class Types extends RenderType {
        static final RenderType VOILE = create("cabane_chidori_voile", DefaultVertexFormat.POSITION_COLOR, VertexFormat.Mode.QUADS, 65536,
                false, true, CompositeState.builder().setShaderState(POSITION_COLOR_SHADER).setWriteMaskState(COLOR_WRITE)
                        .setTransparencyState(TRANSLUCENT_TRANSPARENCY).setCullState(NO_CULL).createCompositeState(false));

        private Types() {
            super("cabane_chidori_rien", DefaultVertexFormat.POSITION, VertexFormat.Mode.QUADS, 256, false, false, () -> { }, () -> { });
        }
    }

    /** Une boule sombre et translucide. */
    private static void boule(VertexConsumer c, Matrix4f m, Vec3 o, double r, float rouge, float vert, float bleu, float alpha) {
        int lat = 12, lon = 18;
        for (int i = 0; i < lat; i++) {
            double p0 = Math.PI * i / lat - Math.PI / 2, p1 = Math.PI * (i + 1) / lat - Math.PI / 2;
            for (int j = 0; j < lon; j++) {
                double q0 = Math.PI * 2 * j / lon, q1 = Math.PI * 2 * (j + 1) / lon;
                for (double[] pq : new double[][]{{p0, q0}, {p1, q0}, {p1, q1}, {p0, q1}}) {
                    Vec3 v = o.add(Math.cos(pq[0]) * Math.cos(pq[1]) * r, Math.sin(pq[0]) * r, Math.cos(pq[0]) * Math.sin(pq[1]) * r);
                    c.addVertex(m, (float) v.x, (float) v.y, (float) v.z).setColor(rouge, vert, bleu, alpha);
                }
            }
        }
    }

    /** Des éclairs qui partent de o dans tous les sens (ils changent dix fois par seconde). */
    private static void eclairs(PoseStack pose, MultiBufferSource buffers, Vec3 o, double longueur, int nombre, double epaisseur, double t, int graine) {
        long tranche = (long) (t * 10);
        java.util.Random rnd = new java.util.Random(tranche * 31 + graine);
        for (int i = 0; i < nombre; i++) {
            Vec3 d = new Vec3(rnd.nextGaussian(), rnd.nextGaussian() * 0.7, rnd.nextGaussian()).normalize();
            Effets.eclair(pose, buffers, o, o.add(d.scale(longueur * (0.6 + 0.4 * rnd.nextDouble()))), tranche * 7 + i + graine, epaisseur,
                    0.75F, 0.85F, 1F);
        }
    }

    private static void dessiner(PoseStack pose, Vec3 cam, float partiel) {
        MultiBufferSource.BufferSource buffers = Minecraft.getInstance().renderBuffers().bufferSource();
        Matrix4f m = pose.last().pose();
        VertexConsumer voile = buffers.getBuffer(Types.VOILE);
        for (Scene s : SCENES.values()) {
            double t = t(s);
            Vec3 c = s.centre.add(0, 1, 0).subtract(cam);
            // le Chidori dans sa main : un cœur blanc, un halo sombre, et des éclairs qui crépitent (jusqu'au sol pendant la charge)
            if (t < IMPACT) {
                Vec3 h = main(s, t, partiel).subtract(cam);
                double gros = 0.6 + 0.4 * entre(t, 0, 0.8);
                boule(voile, m, h, 0.42 * gros, 0.05F, 0.03F, 0.1F, 0.45F);
                Effets.sphere(pose, buffers, h, 0.2 * gros, 0.8F, 0.9F, 1F, 1F);
                eclairs(pose, buffers, h, t < RUEE ? 1.3 : 0.9, t < RUEE ? 6 : 4, 0.035, t, 1);
            }
            // la ruée : une traînée de lumière derrière lui
            if (t >= RUEE && t < IMPACT + 0.3) {
                double k = entre(t, RUEE, ARRIVEE);
                Vec3 a = s.depart.add(0, 1, 0).subtract(cam), b = s.depart.lerp(s.impact, k * k).add(0, 1, 0).subtract(cam);
                float fond = (float) (1 - entre(t, ARRIVEE, IMPACT + 0.3));
                Effets.tube(pose, buffers, a, b, 0.35, 0.6F, 0.7F, 1F, 0.35F * fond);
                Effets.tube(pose, buffers, a, b, 0.1, 1F, 1F, 1F, 0.8F * fond);
            }
            // l'impact : la sphère d'éclairs noirs qui enfle autour de la cible, son cœur blanc, les éclairs qui en jaillissent
            if (t >= IMPACT && t < FLASH + 0.6) {
                double r = RAYON * (1 - Math.pow(1 - entre(t, IMPACT, SPHERE + 0.4), 3));
                float reste = (float) (1 - entre(t, FLASH, FLASH + 0.6));
                boule(voile, m, c, r, 0.04F, 0.03F, 0.08F, 0.62F * reste);
                boule(voile, m, c, r * 1.08, 0.2F, 0.18F, 0.3F, 0.18F * reste);
                Effets.sphere(pose, buffers, c, 0.6 + 0.5 * entre(t, IMPACT, SPHERE), 0.85F, 0.9F, 1F, reste);
                eclairs(pose, buffers, c, r * 1.05, 14, 0.06, t, 2);
                Effets.onde(pose, buffers, s.centre.add(0, 0.05, 0).subtract(cam), r * 1.1, 1.2, 0.7F * reste);
            }
            // le flash blanc, pour ceux qui regardent du dehors
            if (t >= FLASH && t < FLASH + 0.5) {
                Effets.sphere(pose, buffers, c, RAYON * (1 + entre(t, FLASH, FLASH + 0.5)), 1F, 1F, 1F, (float) (1 - entre(t, FLASH, FLASH + 0.5)));
            }
        }
        buffers.endBatch(Types.VOILE);
        buffers.endBatch();
    }

    // ================================================================ l'écran, en cinématique

    private static void ecran(GuiGraphics g) {
        if (vue == null) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        int w = g.guiWidth(), h = g.guiHeight();
        double t = t(vue);
        int bande = h / 9;
        g.fill(0, 0, w, bande, 0xFF000000);
        g.fill(0, h - bande, w, h, 0xFF000000);
        // la ruée : des traits de vitesse qui convergent vers le centre
        if (t >= RUEE && t < ARRIVEE + 0.1) {
            java.util.Random rnd = new java.util.Random((long) (t * 30));
            for (int i = 0; i < 40; i++) {
                double a = rnd.nextDouble() * Math.PI * 2;
                double r0 = Math.max(w, h) * (0.35 + rnd.nextDouble() * 0.3), r1 = r0 + 40 + rnd.nextDouble() * 120;
                trait(g, w / 2 + Math.cos(a) * r0, h / 2 + Math.sin(a) * r0, w / 2 + Math.cos(a) * r1, h / 2 + Math.sin(a) * r1, 0xB0FFFFFF);
            }
        }
        // le cri, à l'impact
        if (t >= IMPACT - 0.15 && t < IMPACT + 1.2) {
            g.drawCenteredString(mc.font, Component.literal("Chidori !!"), w / 2, h - bande - 14, 0xFFFFFFFF);
        }
        // les flashs : un éclair blanc à l'impact, puis le grand flash
        int blanc = 0;
        if (t >= IMPACT && t < IMPACT + 0.2) {
            blanc = (int) (200 * (1 - (t - IMPACT) / 0.2));
        }
        if (t >= FLASH) {
            double k = t < FLASH + 0.25 ? entre(t, FLASH, FLASH + 0.25) : 1 - entre(t, FLASH + 0.25, FLASH + 0.9);
            blanc = Math.max(blanc, (int) (255 * k));
        }
        if (blanc > 0) {
            g.fill(0, 0, w, h, Math.min(255, blanc) << 24 | 0xFFFFFF);
        }
    }

    /** Un trait d'un pixel, d'un point à un autre (en petits carrés). */
    private static void trait(GuiGraphics g, double x0, double y0, double x1, double y1, int couleur) {
        int n = (int) Math.max(1, Math.hypot(x1 - x0, y1 - y0) / 2);
        for (int i = 0; i <= n; i++) {
            int x = (int) (x0 + (x1 - x0) * i / n), y = (int) (y0 + (y1 - y0) * i / n);
            g.fill(x, y, x + 2, y + 1, couleur);
        }
    }

    // ================================================================ les sons et les étincelles

    private static void son(Vec3 p, SoundEvent son, float volume, float hauteur) {
        Minecraft mc = Minecraft.getInstance();
        mc.level.playLocalSound(p.x, p.y, p.z, son, SoundSource.PLAYERS, volume, hauteur, false);
    }

    private static void evenements(Scene s, double avant, double t) {
        Minecraft mc = Minecraft.getInstance();
        Vec3 h = main(s, t, 1);
        // le crépitement du Chidori, qui piaille de plus en plus fort
        if (t < IMPACT && (int) (t * 8) != (int) (avant * 8)) {
            son(h, SoundEvents.FIREWORK_ROCKET_TWINKLE, 0.5F + (float) entre(t, 0, RUEE) * 0.6F, 1.6F + HASARD.nextFloat() * 0.4F);
            for (int i = 0; i < 4; i++) {
                mc.level.addParticle(ParticleTypes.ELECTRIC_SPARK, h.x, h.y, h.z, (HASARD.nextDouble() - 0.5) * 0.6, HASARD.nextDouble() * 0.3,
                        (HASARD.nextDouble() - 0.5) * 0.6);
            }
        }
        if (avant < 0 && t >= 0) {
            son(h, SoundEvents.BEACON_POWER_SELECT, 1F, 1.8F);
        }
        if (avant < RUEE && t >= RUEE) {
            son(h, SoundEvents.ENDER_DRAGON_FLAP, 1.4F, 0.8F);
            son(h, SoundEvents.ELYTRA_FLYING, 0.6F, 2F);
            // un grand coup d'ailes le jette sur sa cible
            ClientMaudit.coupDAiles(s.lanceur);
        }
        if (avant < IMPACT && t >= IMPACT) {
            son(s.centre, SoundEvents.TRIDENT_THUNDER.value(), 1.5F, 1.2F);
            for (int i = 0; i < 60; i++) {
                mc.level.addParticle(ParticleTypes.ELECTRIC_SPARK, s.centre.x, s.centre.y + 1, s.centre.z, (HASARD.nextDouble() - 0.5) * 2,
                        (HASARD.nextDouble() - 0.3) * 1.5, (HASARD.nextDouble() - 0.5) * 2);
            }
        }
        if (avant < FLASH && t >= FLASH) {
            son(s.centre, SoundEvents.GENERIC_EXPLODE.value(), 1.5F, 0.6F);
            DustParticleOptions cendre = new DustParticleOptions(new Vector3f(0.6F, 0.85F, 0.9F), 1.5F);
            for (int i = 0; i < 80; i++) {
                double a = HASARD.nextDouble() * Math.PI * 2, r = HASARD.nextDouble() * RAYON;
                mc.level.addParticle(cendre, s.centre.x + Math.cos(a) * r, s.centre.y + 0.2 + HASARD.nextDouble() * 3, s.centre.z + Math.sin(a) * r,
                        0, 0.03, 0);
            }
        }
    }

    // ================================================================ branchements

    @EventBusSubscriber(modid = Cabane.MODID, value = Dist.CLIENT)
    public static final class Couches {
        @SubscribeEvent
        public static void enregistrer(RegisterGuiLayersEvent event) {
            event.registerAboveAll(Cabane.id("chidori_cinema"), (g, delta) -> ecran(g));
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

        /** Le lanceur s'accroupit pour charger ; sinon, ni lui ni sa cible ne bougent. */
        @SubscribeEvent
        public static void immobile(MovementInputUpdateEvent event) {
            if (vue == null) {
                return;
            }
            Minecraft mc = Minecraft.getInstance();
            boolean charge = mc.player != null && mc.player.getUUID().equals(vue.lanceur) && t(vue) < RUEE;
            event.getInput().leftImpulse = 0;
            event.getInput().forwardImpulse = 0;
            event.getInput().jumping = false;
            event.getInput().shiftKeyDown = charge;
            event.getInput().up = false;
            event.getInput().down = false;
            event.getInput().left = false;
            event.getInput().right = false;
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
            if (vue != null && !event.getName().equals(Cabane.id("chidori_cinema"))) {
                event.setCanceled(true);
            }
        }

        private Evenements() {
        }
    }

    private ClientChidori() {
    }
}
