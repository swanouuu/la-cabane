package fr.cabane.client.pouvoir;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import fr.cabane.Cabane;
import fr.cabane.client.cinema.Effets;
import fr.cabane.pouvoir.Contre;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.core.particles.DustParticleOptions;
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
import net.neoforged.neoforge.client.event.RenderLivingEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.joml.Vector3f;

/**
 * Le contre de Minato (voir {@link Contre}), chez chaque joueur à portée, d'après l'aperçu validé
 * (docs/minato2/apercu-contre-kurama.mp4). En garde, rien ne se voit (sinon le contre est grillé). Quand un joueur le frappe :
 * <ul>
 * <li>l'attaquant passe en cinématique : la caméra reste dans ses yeux et serre sur ceux de Minato (0 à 2,4 s, « Calme-toi. »),
 * tout le reste s'efface dans le noir ; un éclair orange ; puis Kurama, énorme, accroupi, la tête basse, ouvre la gueule
 * devant lui (« Kurama... ») ; enfin la caméra recule derrière lui : il est tout petit entre ses pattes (5,4 à 8 s) ;</li>
 * <li>les autres voient Kurama sortir de l'ombre derrière Minato, ses pattes de part et d'autre, et tout s'assombrit autour.</li>
 * </ul>
 * Kurama ({@link ModeleKurama#dessinerAccroupi}) se dessine sans brouillard : il reste en pleine lumière dans le noir.
 */
public final class ClientContre {
    private static final double FIN = Contre.DUREE / 20.0;
    /** Kurama : blocs par pixel de son modèle (il fait une douzaine de blocs de haut, accroupi, et ses pattes vont à 24 blocs). */
    private static final double E = 0.6;
    /** Jusqu'où devant Minato il va (le bout de ses pattes ou de son museau) : jamais jusqu'à l'attaquant. */
    private static final double PATTES = 1.0;
    private static final RandomSource HASARD = RandomSource.create();
    private static final DustParticleOptions ROUGE = new DustParticleOptions(new Vector3f(1F, 0.25F, 0.08F), 0.7F);
    private static final DustParticleOptions ORANGE = new DustParticleOptions(new Vector3f(1F, 0.5F, 0.1F), 2.5F);

    /** Les Minato en garde (rien ne se voit ; seul leur panneau affiche « En garde ! »). */
    private static final Set<UUID> GARDES = new HashSet<>();
    /** Quand je pourrai me remettre en garde (System.nanoTime), -1 : tout de suite. */
    private static long pret = -1;

    /** Un contre : Minato, l'attaquant, où était Minato, la direction Minato → attaquant, le départ, les yeux de l'attaquant au départ. */
    private static final class Scene {
        UUID minato;
        /** Pour la démo de l'outil de captures : Minato est une créature (son numéro), -1 sinon. */
        int minatoId = -1;
        int attaquant;
        Vec3 pos;
        Vec3 f;
        long debut;
        Vec3 oeil;
        double avant;
    }

    private static final Map<UUID, Scene> SCENES = new HashMap<>();

    // ---- la cinématique, quand on est l'attaquant
    private static Scene vue;
    private static Entity objectif;
    private static CameraType vueAvant;
    private static boolean guiAvant;
    private static float fov = 70;
    private static long sortie = -1;
    private static Vec3 camera = Vec3.ZERO;
    private static Vec3 vise = Vec3.ZERO;

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

    // ================================================================ ce que dit le serveur

    public static void garde(Contre.Garde p) {
        if (p.active()) {
            GARDES.add(p.joueur());
        } else {
            GARDES.remove(p.joueur());
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && mc.player.getUUID().equals(p.joueur())) {
            pret = System.nanoTime() + p.recharge() * 50_000_000L;
        }
    }

    public static void vision(Contre.Vision p) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            return;
        }
        Scene s = new Scene();
        s.minato = p.joueur();
        s.attaquant = p.attaquant();
        s.pos = new Vec3(p.pos().x(), p.pos().y(), p.pos().z());
        Vec3 f = new Vec3(p.dir().x(), 0, p.dir().z());
        s.f = f.lengthSqr() < 1.0E-4 ? new Vec3(0, 0, 1) : f.normalize();
        s.debut = System.nanoTime();
        ModeleKurama.Pose pose = ModeleKurama.accroupi(0);
        s.avant = pose == null ? 40 : pose.avant();
        Entity a = mc.level.getEntity(s.attaquant);
        s.oeil = a != null ? a.getEyePosition() : s.pos.add(s.f.scale(2)).add(0, 1.6, 0);
        GARDES.remove(s.minato);
        Scene ancienne = SCENES.put(s.minato, s);
        if (ancienne != null && ancienne == vue) {
            sortir();
        }
        if (mc.player.getId() == s.attaquant) {
            entrer(s);
        }
    }

    /** La touche L, quand on est un Minato éveillé : se mettre en garde. Vrai si la touche était pour lui. */
    public static boolean touche() {
        if (!ClientMinato.eveille()) {
            return false;
        }
        PacketDistributor.sendToServer(new Contre.Activer());
        return true;
    }

    /** La ligne du contre dans le panneau de Minato. */
    public static String ligne() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && GARDES.contains(mc.player.getUUID())) {
            return "En garde !";
        }
        double reste = pret < 0 ? 0 : -secondes(pret);
        return reste > 0 ? String.format("Contre (%.0f s)", Math.ceil(reste)) : "Contre (garde 20 s)";
    }

    public static boolean grise() {
        Minecraft mc = Minecraft.getInstance();
        return pret >= 0 && secondes(pret) < 0 && (mc.player == null || !GARDES.contains(mc.player.getUUID()));
    }

    /** Suis-je pris dans la vision ? */
    public static boolean participant() {
        return vue != null;
    }

    private static LivingEntity minato(Scene s) {
        Minecraft mc = Minecraft.getInstance();
        if (s.minatoId >= 0) {
            return mc.level.getEntity(s.minatoId) instanceof LivingEntity l ? l : null;
        }
        return mc.level.getPlayerByUUID(s.minato);
    }

    /**
     * Pour l'outil de captures (sans second joueur) : le contre joué sur la créature la plus proche, qui fait Minato. moi :
     * c'est moi qui l'attaque (la vision) ; sinon, la deuxième créature la plus proche l'attaque et je regarde de dehors.
     */
    public static void demo(boolean moi) {
        Minecraft mc = Minecraft.getInstance();
        java.util.List<LivingEntity> proches = new ArrayList<>();
        for (Entity e : mc.level.entitiesForRendering()) {
            if (e instanceof LivingEntity v && !(e instanceof Player) && e.distanceTo(mc.player) < 40) {
                proches.add(v);
            }
        }
        proches.sort(java.util.Comparator.comparingDouble(e -> e.distanceTo(mc.player)));
        if (proches.isEmpty() || (!moi && proches.size() < 2)) {
            return;
        }
        LivingEntity m = proches.get(0);
        Entity a = moi ? mc.player : proches.get(1);
        Vec3 d = a.position().subtract(m.position());
        vision(new Contre.Vision(m.getUUID(), a.getId(), new Vector3f((float) m.getX(), (float) m.getY(), (float) m.getZ()),
                new Vector3f((float) d.x, 0, (float) d.z)));
        Scene s = SCENES.get(m.getUUID());
        if (s != null) {
            s.minatoId = m.getId();
        }
    }

    // ================================================================ Kurama

    /** Ses pieds : ce qu'il a de plus en avant arrive PATTES blocs devant Minato, vers l'attaquant. */
    private static Vec3 pieds(Scene s) {
        return s.pos.add(s.f.scale(PATTES - s.avant * E));
    }

    /** Sa taille (0 : pas là) : il sort de l'ombre à 2,4 s, se défait à la fin. */
    private static double taille(double t) {
        if (t < 2.4) {
            return 0;
        }
        if (t < 3.2) {
            return 0.35 + 0.65 * lisse(entre(t, 2.4, 3.2));
        }
        return t < 7.6 ? 1 : 1 - lisse(entre(t, 7.6, FIN));
    }

    private static double machoire(double t) {
        return 0.85 * lisse(entre(t, 2.9, 3.6)) + (t > 3.6 ? 0.06 * Math.sin((t - 3.6) * 5) : 0);
    }

    private static Vec3 point(Scene s, double t, Vector3f local) {
        return ModeleKurama.point(pieds(s), s.f, E * Math.max(0.35, taille(t)), local);
    }

    private static Vec3 gueule(Scene s, double t) {
        ModeleKurama.Pose p = ModeleKurama.accroupi(machoire(t));
        return p == null ? pieds(s).add(s.f.scale(s.avant * E * 0.5)).add(0, 6, 0) : point(s, t, p.gueule());
    }

    private static Vec3 tete(Scene s, double t) {
        ModeleKurama.Pose p = ModeleKurama.accroupi(machoire(t));
        return p == null ? pieds(s).add(s.f.scale(s.avant * E * 0.4)).add(0, 7, 0) : point(s, t, p.tete());
    }

    // ================================================================ la cinématique de l'attaquant

    private static void entrer(Scene s) {
        Minecraft mc = Minecraft.getInstance();
        if (vue != null) {
            sortir();
        }
        vue = s;
        sortie = -1;
        mc.setScreen(null);
        guiAvant = mc.options.hideGui;
        mc.options.hideGui = true;
        vueAvant = mc.options.getCameraType();
        mc.options.setCameraType(CameraType.FIRST_PERSON);
        objectif = EntityType.MARKER.create(mc.level);
        placerCamera(s, t(s), 1F);
        mc.setCameraEntity(objectif);
    }

    private static void sortir() {
        Minecraft mc = Minecraft.getInstance();
        vue = null;
        sortie = System.nanoTime();
        mc.options.hideGui = guiAvant;
        if (vueAvant != null) {
            mc.options.setCameraType(vueAvant);
        }
        if (mc.player != null) {
            mc.setCameraEntity(mc.player);
        }
        objectif = null;
    }

    /** Les plans : dans ses yeux sur ceux de Minato, puis sur la gueule de Kurama, puis derrière lui (il est tout petit). */
    private static void plan(Scene s, double t, float partiel) {
        LivingEntity minato = minato(s);
        if (t < 2.4) {
            camera = s.oeil;
            if (minato != null) {
                Vec3[] y = yeux(minato, partiel);
                vise = y[0].add(y[1]).scale(0.5);
            } else {
                vise = s.pos.add(0, 1.65, 0);
            }
            fov = (float) (70 - 22 * lisse(entre(t, 0.1, 2.0)));
        } else {
            // comme l'aperçu validé : bien en face de sa tête, un peu en dessous (on la voit d'en bas, ses yeux au-dessus
            // du museau), assez loin pour la voir en entier ; puis on recule encore, au ras du sol, l'attaquant devant
            Vec3 h = tete(s, t);
            double sol = s.pos.y + 1.2;
            Vec3 gueule = h.add(s.f.scale(30)).add(0, -4, 0);
            Vec3 domine = h.add(s.f.scale(36));
            gueule = new Vec3(gueule.x, Math.max(sol, gueule.y), gueule.z);
            domine = new Vec3(domine.x, s.pos.y + 2.2, domine.z);
            double u = lisse(entre(t, 5.4, 6.4));
            camera = gueule.lerp(domine, u);
            vise = h.lerp(gueule(s, t), 0.3 * (1 - u)).add(0, -2.5 * u, 0);
            fov = (float) (t < 5.4 ? 50 - 8 * lisse(entre(t, 2.4, 5.4)) : 42 + 28 * u);
        }
    }

    private static double secousse(double t) {
        return t >= 2.4 && t < 3.8 ? 0.06 * (1 - entre(t, 2.4, 3.8)) : 0;
    }

    private static void placerCamera(Scene s, double t, float partiel) {
        if (objectif == null) {
            return;
        }
        plan(s, t, partiel);
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

    // ================================================================ dessin

    /** Ses deux yeux, sur son visage (la tête suit son regard). */
    private static Vec3[] yeux(LivingEntity p, float partiel) {
        Vec3 base = p.getPosition(partiel);
        // un joueur fait 1,8 de haut pour une échelle de rendu de 0,9375 ; les autres (la démo) à l'avenant
        double k = 0.9375 / 16 * p.getDimensions(net.minecraft.world.entity.Pose.STANDING).height() / 1.8;
        double cou = (p.isCrouching() ? 20.5 : 24) * k;
        double lacet = Math.toRadians(Mth.lerp(partiel, p.yHeadRotO, p.yHeadRot));
        double tangage = Math.toRadians(p.getViewXRot(partiel));
        Vec3 avant = new Vec3(-Math.sin(lacet), 0, Math.cos(lacet)), cote = new Vec3(Math.cos(lacet), 0, Math.sin(lacet));
        double u = 4 * k, a = 4.5 * k;
        double y = u * Math.cos(tangage) - a * Math.sin(tangage), z = u * Math.sin(tangage) + a * Math.cos(tangage);
        Vec3 c = base.add(0, cou + y, 0).add(avant.scale(z));
        return new Vec3[] {c.add(cote.scale(2 * k)), c.add(cote.scale(-2 * k))};
    }

    private static void dessiner(PoseStack pose, Vec3 cam, float partiel) {
        Minecraft mc = Minecraft.getInstance();
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        // les yeux rouges : seulement pendant la prise (en garde, rien ne se voit : sinon le contre est grillé)
        Set<LivingEntity> rouges = new HashSet<>();
        for (Scene s : SCENES.values()) {
            LivingEntity m = minato(s);
            if (t(s) < 2.6 && m != null) {
                rouges.add(m);
            }
        }
        boolean dessine = false;
        for (LivingEntity p : rouges) {
            if (p == null || (p == mc.getCameraEntity() && mc.options.getCameraType().isFirstPerson()) || (vue != null && t(vue) >= 2.4)) {
                continue;
            }
            for (Vec3 o : yeux(p, partiel)) {
                Effets.sphere(pose, buffers, o.subtract(cam), 0.038, 1F, 0.12F, 0.05F, 1F);
                Effets.sphere(pose, buffers, o.subtract(cam), 0.085, 1F, 0.1F, 0.05F, 0.35F);
            }
            dessine = true;
        }
        if (dessine) {
            buffers.endBatch();
        }
        // Kurama, en pleine lumière, même dans le noir
        for (Scene s : SCENES.values()) {
            double t = t(s), k = taille(t);
            if (k <= 0.01) {
                continue;
            }
            float brume = RenderSystem.getShaderFogStart();
            RenderSystem.setShaderFogStart(Float.MAX_VALUE);
            ModeleKurama.dessinerAccroupi(pose, buffers, pieds(s).subtract(cam), s.f, E * k, machoire(t));
            buffers.endBatch();
            RenderSystem.setShaderFogStart(brume);
        }
    }

    /** Les bandes de cinéma, l'éclair orange, les répliques, le fondu au noir de la fin. */
    private static void ecran(GuiGraphics g) {
        Minecraft mc = Minecraft.getInstance();
        int w = g.guiWidth(), h = g.guiHeight();
        if (vue == null) {
            // le fondu qui se lève après la vision
            if (sortie >= 0) {
                double a = 1 - entre(secondes(sortie), 0, 0.6);
                if (a > 0.01) {
                    g.fill(0, 0, w, h, ((int) (a * 255) << 24));
                } else {
                    sortie = -1;
                }
            }
            return;
        }
        double t = t(vue);
        int bande = h / 9;
        g.fill(0, 0, w, bande, 0xFF000000);
        g.fill(0, h - bande, w, h, 0xFF000000);
        double flash = t >= 2.2 && t < 2.8 ? Math.sin(entre(t, 2.2, 2.8) * Math.PI) * 0.85 : 0;
        if (flash > 0.01) {
            g.fill(0, 0, w, h, ((int) (flash * 255) << 24) | 0xFFC85A);
        }
        double noir = lisse(entre(t, 7.6, FIN));
        if (noir > 0.01) {
            g.fill(0, 0, w, h, ((int) (noir * 255) << 24));
        }
        if (t > 0.8 && t < 2.3) {
            fr.cabane.client.ui.Ios.texteCentre(g, mc.font, "Calme-toi.", w / 2F, h - bande - 26, 1.8F, 0xFFFFFFFF, true);
        }
        if (t > 3.2 && t < 6.6) {
            fr.cabane.client.ui.Ios.texteCentre(g, mc.font, "Kurama...", w / 2F, h - bande - 26, 1.8F, 0xFFFFFFFF, true);
        }
    }

    /** Le noir : pour l'attaquant, tout s'efface sauf Minato (puis lui-même, vu de derrière) ; autour, tout s'assombrit. */
    private static float brouillard(float loin) {
        Minecraft mc = Minecraft.getInstance();
        if (vue != null) {
            double t = t(vue);
            if (t < 2.4) {
                double d = vue.oeil.distanceTo(vue.pos.add(0, 1.6, 0)) + 2.5;
                return (float) Mth.lerp(lisse(entre(t, 0, 0.8)), loin, d);
            }
            // le noir commence juste derrière lui (Kurama, lui, se dessine sans brouillard)
            return mc.player == null ? 11F : (float) (camera.distanceTo(mc.player.getEyePosition()) + 3);
        }
        float f = loin;
        for (Scene s : SCENES.values()) {
            double t = t(s);
            double k = lisse(entre(t, 2.4, 3.2)) * (1 - lisse(entre(t, 7.6, FIN)));
            if (k > 0 && mc.player != null && mc.player.position().distanceTo(s.pos) < 64) {
                f = (float) Math.min(f, Mth.lerp(k, loin, 26));
            }
        }
        return f;
    }

    private static boolean sombre() {
        return vue != null || SCENES.values().stream().anyMatch(s -> t(s) >= 2.4);
    }

    // ================================================================ branchements

    @EventBusSubscriber(modid = Cabane.MODID, value = Dist.CLIENT)
    public static final class Couches {
        @SubscribeEvent
        public static void enregistrer(RegisterGuiLayersEvent event) {
            event.registerAboveAll(Cabane.id("minato_contre"), (g, delta) -> ecran(g));
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
                GARDES.clear();
                if (vue != null) {
                    sortir();
                }
                return;
            }
            for (Scene s : new ArrayList<>(SCENES.values())) {
                double t = t(s);
                if (t >= FIN) {
                    SCENES.remove(s.minato);
                    if (s == vue) {
                        sortir();
                    }
                    continue;
                }
                // le chakra rouge dans sa main, pendant la prise
                if (t < 2.4) {
                    Vec3 main = s.pos.add(s.f.scale(0.6)).add(0, 1.35, 0);
                    for (int i = 0; i < 2; i++) {
                        mc.level.addParticle(ROUGE, main.x + (HASARD.nextDouble() - 0.5) * 0.4, main.y + (HASARD.nextDouble() - 0.5) * 0.4,
                                main.z + (HASARD.nextDouble() - 0.5) * 0.4, 0, 0.03, 0);
                    }
                }
                // à la fin, Kurama se défait en éclats orange
                ModeleKurama.Pose p = ModeleKurama.accroupi(machoire(t));
                if (t > 7.4 && p != null) {
                    for (int i = 0; i < 30; i++) {
                        Vec3 q = point(s, t, ModeleKurama.auHasard(p, HASARD));
                        mc.level.addParticle(ORANGE, q.x, q.y, q.z, 0, 0.05, 0);
                    }
                }
            }
            if (vue != null && mc.player.isDeadOrDying()) {
                SCENES.remove(vue.minato);
                sortir();
            } else if (vue != null && objectif != null && mc.getCameraEntity() != objectif) {
                mc.setCameraEntity(objectif);
            }
        }

        @SubscribeEvent
        public static void image(RenderFrameEvent.Pre event) {
            if (vue != null) {
                placerCamera(vue, t(vue), event.getPartialTick().getGameTimeDeltaPartialTick(false));
            }
        }

        @SubscribeEvent
        public static void dessin(RenderLevelStageEvent event) {
            if ((SCENES.isEmpty() && GARDES.isEmpty()) || event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES
                    || Minecraft.getInstance().level == null) {
                return;
            }
            dessiner(event.getPoseStack(), event.getCamera().getPosition(), event.getPartialTick().getGameTimeDeltaPartialTick(false));
        }

        @SubscribeEvent
        public static void brume(ViewportEvent.RenderFog event) {
            if (!sombre()) {
                return;
            }
            float loin = brouillard(event.getFarPlaneDistance());
            if (loin < event.getFarPlaneDistance()) {
                event.setNearPlaneDistance(vue != null ? 0 : loin * 0.25F);
                event.setFarPlaneDistance(loin);
                event.setCanceled(true);
            }
        }

        @SubscribeEvent
        public static void couleurBrume(ViewportEvent.ComputeFogColor event) {
            if (!sombre()) {
                return;
            }
            double k = vue != null ? 1 : 0;
            for (Scene s : SCENES.values()) {
                double t = t(s);
                k = Math.max(k, lisse(entre(t, 2.4, 3.2)) * (1 - lisse(entre(t, 7.6, FIN))));
            }
            if (vue != null && t(vue) < 2.4) {
                k = lisse(entre(t(vue), 0, 0.8));
            }
            float a = (float) k;
            event.setRed(Mth.lerp(a, event.getRed(), 0.03F));
            event.setGreen(Mth.lerp(a, event.getGreen(), 0.012F));
            event.setBlue(Mth.lerp(a, event.getBlue(), 0.008F));
        }

        @SubscribeEvent
        public static void champ(ViewportEvent.ComputeFov event) {
            if (vue != null) {
                event.setFOV(fov);
            }
        }

        /** Dans la vision, Minato disparaît : il n'y a plus que Kurama. */
        @SubscribeEvent
        public static void cacher(RenderLivingEvent.Pre<?, ?> event) {
            if (vue != null && t(vue) >= 2.4 && event.getEntity().getUUID().equals(vue.minato)) {
                event.setCanceled(true);
            }
            // la caméra est dans ses propres yeux : on ne dessine pas sa tête de l'intérieur
            if (vue != null && t(vue) < 2.4 && event.getEntity() == Minecraft.getInstance().player) {
                event.setCanceled(true);
            }
        }

        /** Pas de pseudo pendant la vision. */
        @SubscribeEvent
        public static void pasDePseudo(net.neoforged.neoforge.client.event.RenderNameTagEvent event) {
            if (vue != null) {
                event.setCanRender(net.neoforged.neoforge.common.util.TriState.FALSE);
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
            if (vue != null && !event.getName().equals(Cabane.id("minato_contre"))) {
                event.setCanceled(true);
            }
        }

        private Evenements() {
        }
    }

    private ClientContre() {
    }
}
