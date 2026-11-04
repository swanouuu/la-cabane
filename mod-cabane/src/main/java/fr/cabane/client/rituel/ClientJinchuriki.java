package fr.cabane.client.rituel;

import com.mojang.blaze3d.vertex.PoseStack;
import fr.cabane.Cabane;
import fr.cabane.client.cinema.Effets;
import fr.cabane.client.cinema.Effets.Posture;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.resources.PlayerSkin;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.DustParticleOptions;
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
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.client.event.RenderHandEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.event.RenderLivingEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;
import org.joml.Vector3f;

/**
 * /jinchuriki : le joueur baisse la tête vers son ventre, et la marque des Jinchûriki y apparaît (d'après l'image de
 * l'utilisateur) : la spirale d'abord, qui s'ouvre en tournant, puis les signes tout autour. Elle brille comme du chakra
 * doré, s'éteint en marque d'encre, et s'efface quand il relève la tête. Chez lui, la caméra suit son regard vers son
 * ventre ; les autres voient son double baisser la tête, la marque sur le ventre.
 */
public final class ClientJinchuriki {
    private static final double DUREE = 6;
    private static final Posture BAISSE = new Posture(0.85F, 0, 4, -0.45F, -0.35F, 0, -0.45F, 0.35F, 0, 0, 0.03F, 0, -0.03F);
    private static final Vector3f OR = new Vector3f(1F, 0.78F, 0.25F);

    private static final Map<UUID, Long> DEBUTS = new HashMap<>();
    private static final Map<UUID, Double> AVANT = new HashMap<>();
    private static boolean dessinEnCours;

    // ---- la vue de celui qui regarde son ventre
    private static boolean regarde;
    private static Entity objectif;
    private static CameraType vueAvant;
    private static boolean guiAvant;

    private static double t(UUID u) {
        Long d = DEBUTS.get(u);
        return d == null ? DUREE : (System.nanoTime() - d) / 1e9;
    }

    private static double entre(double t, double a, double b) {
        return Mth.clamp((t - a) / (b - a), 0, 1);
    }

    private static double lisse(double k) {
        return k * k * (3 - 2 * k);
    }

    /** Combien la tête est baissée (0 à 1). */
    private static double baisse(double t) {
        return lisse(entre(t, 0, 0.6)) * (1 - lisse(entre(t, 5.0, 5.8)));
    }

    public static void demarrer(UUID joueur) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            return;
        }
        DEBUTS.put(joueur, System.nanoTime());
        AVANT.put(joueur, 0.0);
        if (joueur.equals(mc.player.getUUID()) && !regarde && !ClientRituel.actif()) {
            regarde = true;
            mc.setScreen(null);
            guiAvant = mc.options.hideGui;
            mc.options.hideGui = true;
            vueAvant = mc.options.getCameraType();
            mc.options.setCameraType(CameraType.FIRST_PERSON);
            objectif = EntityType.MARKER.create(mc.level);
            placerCamera(0);
            mc.setCameraEntity(objectif);
        }
    }

    private static void finir(UUID joueur) {
        DEBUTS.remove(joueur);
        AVANT.remove(joueur);
        Minecraft mc = Minecraft.getInstance();
        if (regarde && mc.player != null && joueur.equals(mc.player.getUUID())) {
            sortir();
        }
    }

    private static void sortir() {
        Minecraft mc = Minecraft.getInstance();
        regarde = false;
        mc.options.hideGui = guiAvant;
        if (vueAvant != null) {
            mc.options.setCameraType(vueAvant);
        }
        if (mc.player != null) {
            mc.setCameraEntity(mc.player);
        }
        objectif = null;
    }

    private static Vec3 avant(float lacet) {
        double r = Math.toRadians(lacet);
        return new Vec3(-Math.sin(r), 0, Math.cos(r));
    }

    /** Sa caméra : à la place de ses yeux (son double n'a pas de tête chez lui), qui descend vers le ventre quand il baisse la tête. */
    private static void placerCamera(double t) {
        Minecraft mc = Minecraft.getInstance();
        if (objectif == null || mc.player == null) {
            return;
        }
        float lacet = mc.player.yBodyRot;
        Vec3 f = avant(lacet), pieds = mc.player.position();
        double k = baisse(t);
        // à hauteur des yeux, puis la tête baissée : un peu plus bas et en avant, pour voir le ventre de face
        Vec3 c = pieds.add(0, 1.62 - 0.17 * k, 0).add(f.scale(0.05 + 0.4 * k));
        Vec3 vise = pieds.add(0, 1.62, 0).add(f.scale(6)).lerp(pieds.add(0, 0.95, 0).add(f.scale(0.14)), k);
        Vec3 d = vise.subtract(c);
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

    private static void dessiner(PoseStack pose, Vec3 cam, float partiel) {
        Minecraft mc = Minecraft.getInstance();
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        for (Map.Entry<UUID, Long> e : DEBUTS.entrySet()) {
            if (!(mc.level.getPlayerByUUID(e.getKey()) instanceof AbstractClientPlayer joueur)) {
                continue;
            }
            double t = t(e.getKey());
            float lacet = Mth.rotLerp(partiel, joueur.yBodyRotO, joueur.yBodyRot);
            Vec3 pieds = joueur.getPosition(partiel);
            dessinEnCours = true;
            try {
                Doubles.dessiner(pose, buffers, joueur.getSkin().texture(), joueur.getSkin().model() == PlayerSkin.Model.SLIM, pieds.subtract(cam), lacet,
                        Posture.DEBOUT.vers(BAISSE, (float) baisse(t)), LevelRenderer.getLightColor(mc.level, BlockPos.containing(pieds.add(0, 1.2, 0))),
                        regarde && joueur == mc.player);
            } finally {
                dessinEnCours = false;
            }
            buffers.endBatch();
            // la marque, sur le ventre : la spirale s'ouvre en tournant, puis les signes viennent autour
            double r = Math.toRadians(lacet);
            Vec3 f = avant(lacet), gauche = new Vec3(Math.cos(r), 0, Math.sin(r));
            // au-dessus du t-shirt (la couche extérieure du skin dépasse)
            Vec3 centre = pieds.add(0, 0.95, 0).add(f.scale(0.14)).subtract(cam);
            double ouvre = lisse(entre(t, 0.7, 1.5)), ang = (1 - ouvre) * 4;
            double signes = entre(t, 1.3, 2.1);
            double brille = entre(t, 0.7, 1.0) * (1 - entre(t, 3.8, 4.6)) * (0.85 + 0.15 * Math.sin(t * 8));
            double encre = entre(t, 3.8, 4.6) * (1 - entre(t, 5.0, 5.8));
            Vec3 u = gauche.scale(Math.cos(ang)).add(0, Math.sin(ang), 0).scale(0.22 * ouvre), v = new Vec3(0, Math.cos(ang), 0).subtract(gauche.scale(Math.sin(ang))).scale(0.22 * ouvre);
            Vec3 u2 = gauche.scale(0.22), v2 = new Vec3(0, 0.22, 0);
            int lumiere = LevelRenderer.getLightColor(mc.level, BlockPos.containing(pieds.add(0, 1, 0)));
            if (brille > 0.01) {
                if (ouvre > 0.01) {
                    ClientRituel.carre(pose, buffers.getBuffer(RenderType.entityTranslucentEmissive(ClientRituel.SPIRALE)), centre, u, v, OR.x, OR.y, OR.z,
                            (float) brille, 0xF000F0);
                }
                ClientRituel.carre(pose, buffers.getBuffer(RenderType.entityTranslucentEmissive(ClientRituel.SIGNES_MARQUE)), centre, u2, v2, OR.x, OR.y, OR.z,
                        (float) (brille * signes), 0xF000F0);
            }
            if (encre > 0.01) {
                for (ResourceLocation tex : new ResourceLocation[] {ClientRituel.SPIRALE, ClientRituel.SIGNES_MARQUE}) {
                    ClientRituel.carre(pose, buffers.getBuffer(RenderType.entityTranslucent(tex)), centre.add(f.scale(0.002)), u2, v2, 0.08F, 0.03F, 0.03F,
                            (float) encre, lumiere);
                }
            }
            buffers.endBatch();
        }
    }

    private static void son(Vec3 p, SoundEvent e, float volume, float hauteur) {
        Minecraft.getInstance().level.playLocalSound(p.x, p.y, p.z, e, SoundSource.PLAYERS, volume, hauteur, false);
    }

    /** Les sons, et un peu de chakra doré qui s'échappe de la marque pendant qu'elle brille. */
    private static void evenements(Player joueur, double a, double t) {
        Vec3 p = joueur.position().add(0, 0.95, 0).add(avant(joueur.yBodyRot).scale(0.2));
        if (a < 0.7 && t >= 0.7) {
            son(p, SoundEvents.BEACON_ACTIVATE, 0.8F, 1.4F);
            son(p, SoundEvents.AMETHYST_BLOCK_CHIME, 1F, 0.8F);
        }
        if (a < 1.3 && t >= 1.3) {
            son(p, SoundEvents.AMETHYST_BLOCK_RESONATE, 1F, 0.7F);
        }
        if (a < 3.8 && t >= 3.8) {
            son(p, SoundEvents.BEACON_DEACTIVATE, 0.6F, 1.4F);
        }
        // (pas chez celui qui regarde : de si près, les grains de chakra cacheraient sa marque)
        if (t > 0.8 && t < 3.8 && !(regarde && joueur == Minecraft.getInstance().player)) {
            Random r = new Random();
            for (int i = 0; i < 2; i++) {
                joueur.level().addParticle(new DustParticleOptions(new Vector3f(1F, 0.75F, 0.2F), 0.6F), p.x + (r.nextDouble() - 0.5) * 0.4,
                        p.y + (r.nextDouble() - 0.5) * 0.4, p.z + (r.nextDouble() - 0.5) * 0.4, 0, 0.02, 0);
            }
        }
    }

    @EventBusSubscriber(modid = Cabane.MODID, value = Dist.CLIENT)
    public static final class Evenements {
        @SubscribeEvent
        public static void tick(ClientTickEvent.Post event) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.level == null || mc.player == null) {
                DEBUTS.clear();
                AVANT.clear();
                if (regarde) {
                    sortir();
                }
                return;
            }
            for (UUID u : DEBUTS.keySet().toArray(UUID[]::new)) {
                double t = t(u);
                if (mc.level.getPlayerByUUID(u) instanceof Player p) {
                    evenements(p, AVANT.getOrDefault(u, 0.0), t);
                }
                AVANT.put(u, t);
                if (t >= DUREE) {
                    finir(u);
                }
            }
            if (regarde && objectif != null && mc.getCameraEntity() != objectif) {
                mc.setCameraEntity(objectif);
            }
        }

        @SubscribeEvent
        public static void image(RenderFrameEvent.Pre event) {
            Minecraft mc = Minecraft.getInstance();
            if (regarde && mc.player != null) {
                placerCamera(t(mc.player.getUUID()));
            }
        }

        @SubscribeEvent
        public static void dessin(RenderLevelStageEvent event) {
            if (DEBUTS.isEmpty() || event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES || Minecraft.getInstance().level == null) {
                return;
            }
            dessiner(event.getPoseStack(), event.getCamera().getPosition(), event.getPartialTick().getGameTimeDeltaPartialTick(false));
        }

        /** Pendant sa marque, le vrai joueur n'est pas dessiné : son double baisse la tête à sa place. */
        @SubscribeEvent
        public static void cacher(RenderLivingEvent.Pre<?, ?> event) {
            if (!dessinEnCours && event.getEntity() instanceof Player p && DEBUTS.containsKey(p.getUUID())) {
                event.setCanceled(true);
            }
        }

        @SubscribeEvent
        public static void champ(ViewportEvent.ComputeFov event) {
            if (regarde) {
                event.setFOV(70 - 10 * baisse(t(Minecraft.getInstance().player.getUUID())));
            }
        }

        @SubscribeEvent
        public static void immobile(MovementInputUpdateEvent event) {
            if (regarde) {
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
            if (regarde) {
                event.setCanceled(true);
                event.setSwingHand(false);
            }
        }

        @SubscribeEvent
        public static void pasDeMain(RenderHandEvent event) {
            if (regarde) {
                event.setCanceled(true);
            }
        }

        private Evenements() {
        }
    }

    private ClientJinchuriki() {
    }
}
