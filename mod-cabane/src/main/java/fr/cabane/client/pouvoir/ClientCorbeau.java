package fr.cabane.client.pouvoir;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import fr.cabane.Cabane;
import fr.cabane.pouvoir.Corbeau;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderHandEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.event.RenderPlayerEvent;
import org.joml.Matrix4f;

/**
 * Le corbeau à l'écran (voir {@link Corbeau} et docs/susanodev/planche-corbeau.png) : l'oiseau lui-même, dessiné à la place
 * du joueur, et l'explosion d'encre, de plumes et de petits corbeaux au départ et au retour.
 */
@EventBusSubscriber(modid = Cabane.MODID, value = Dist.CLIENT)
public final class ClientCorbeau {
    private static final int NOIR = 0x12121A, NOIR2 = 0x282A3A, BEC = 0x463E34, ROUGE = 0xEB2828;
    /** L'échelle de l'oiseau (ses mesures sont en seizièmes de bloc). */
    private static final float ECHELLE = 0.8F / 16F;
    /** Le corps disparaît dans l'encre après tant de secondes ; l'oiseau (ou le joueur au retour) en sort après tant d'autres. */
    private static final double CORPS_PART = 0.18, OISEAU_SORT = 0.85, RETOUR = 0.7, DUREE_ECLAT = 2.6;
    private static final SoundEvent CROA = SoundEvent.createVariableRangeEvent(ResourceLocation.parse("alexsmobs:crow_idle"));

    private record Transition(boolean entree, long debut) {
    }

    private static final Map<UUID, Transition> TRANSITIONS = new HashMap<>();
    private static final List<Eclat> ECLATS = new ArrayList<>();

    private ClientCorbeau() {
    }

    private static double secondes(long depuis) {
        return (System.nanoTime() - depuis) / 1e9;
    }

    private static double borne(double u) {
        return Math.max(0, Math.min(1, u));
    }

    public static void etat(Corbeau.Etat p) {
        Corbeau.definir(p.joueur(), p.actif());
        Minecraft mc = Minecraft.getInstance();
        Player j = mc.level == null ? null : mc.level.getPlayerByUUID(p.joueur());
        if (j == null) {
            return;
        }
        j.refreshDimensions();
        if (p.annonce()) {
            TRANSITIONS.put(p.joueur(), new Transition(p.actif(), System.nanoTime()));
            Vec3 c = j.position().add(0, p.actif() ? 1.0 : 0.3, 0);
            ECLATS.add(new Eclat(c, p.actif()));
            for (int i = 0; i < 26; i++) {
                double a = mc.level.random.nextDouble() * Math.PI * 2;
                mc.level.addParticle(ParticleTypes.SQUID_INK, c.x, c.y, c.z, Math.cos(a) * 0.25, mc.level.random.nextDouble() * 0.2, Math.sin(a) * 0.25);
            }
            mc.level.playLocalSound(c.x, c.y, c.z, CROA, SoundSource.PLAYERS, 1.3F, 0.85F, false);
            mc.level.playLocalSound(c.x, c.y, c.z, SoundEvents.PHANTOM_FLAP, SoundSource.PLAYERS, 1.2F, 0.7F, false);
        }
    }

    /** Posé au sol, la touche de saut le fait repartir (pas besoin de l'appuyer deux fois). */
    @SubscribeEvent
    public static void decoller(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && mc.screen == null && Corbeau.actif(mc.player.getUUID()) && !mc.player.getAbilities().flying && mc.options.keyJump.isDown()) {
            mc.player.getAbilities().flying = true;
            mc.player.onUpdateAbilities();
        }
    }

    /** Le joueur local est en corbeau, ou entre deux formes : pas de main à l'écran. */
    private static boolean sansCorps(UUID joueur) {
        Transition t = TRANSITIONS.get(joueur);
        return Corbeau.actif(joueur) || (t != null && !t.entree() && secondes(t.debut()) < RETOUR);
    }

    // ================================================================ l'oiseau

    @SubscribeEvent
    public static void dessinerJoueur(RenderPlayerEvent.Pre event) {
        Player j = event.getEntity();
        if (Corbeau.aucun() && TRANSITIONS.isEmpty()) {
            return;
        }
        Transition t = TRANSITIONS.get(j.getUUID());
        double age = t == null ? 1e9 : secondes(t.debut());
        if (Corbeau.actif(j.getUUID())) {
            if (t != null && t.entree() && age < CORPS_PART) {
                return; // le corps reste une fraction de seconde, le temps que l'encre le recouvre
            }
            event.setCanceled(true);
            if (t == null || !t.entree() || age >= OISEAU_SORT) {
                oiseau(j, event.getPoseStack(), event.getMultiBufferSource(), event.getPartialTick());
            }
        } else if (t != null && !t.entree() && age < RETOUR) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void pasDeMain(RenderHandEvent event) {
        Player moi = Minecraft.getInstance().player;
        if (moi != null && sansCorps(moi.getUUID())) {
            event.setCanceled(true);
        }
    }

    private static void oiseau(Player j, PoseStack ps, MultiBufferSource buffers, float partiel) {
        float lacet = Mth.rotLerp(partiel, j.yRotO, j.getYRot());
        float tangage = Mth.lerp(partiel, j.xRotO, j.getXRot());
        boolean vol = !j.onGround();
        double vitesse = j.getDeltaMovement().length();
        float temps = (j.tickCount + partiel) * (vol ? 0.55F : 0.1F);
        float aile = vol ? 0.2F + (float) Math.min(0.55, 0.28 + vitesse * 0.4) * Mth.sin(temps) : -1.1F;
        ps.pushPose();
        ps.translate(0, vol ? 0.25 : 0.14, 0);
        ps.mulPose(Axis.YP.rotationDegrees(-lacet));
        ps.mulPose(Axis.XP.rotationDegrees(vol ? tangage : 0));
        ps.scale(ECHELLE, ECHELLE, ECHELLE);
        corbeau(ps, buffers.getBuffer(RenderType.debugQuads()), aile);
        ps.popPose();
    }

    /** Un corbeau, le bec vers +z, en seizièmes de bloc (les mêmes mesures que le croquis). */
    private static void corbeau(PoseStack ps, VertexConsumer c, float aile) {
        Matrix4f m = ps.last().pose();
        boite(c, m, -2.2F, -2, -5, 2.2F, 2.2F, 4.5F, NOIR);
        boite(c, m, -1.7F, -0.8F, 4.5F, 1.7F, 2.8F, 8, NOIR);
        boite(c, m, -0.6F, 0.2F, 8, 0.6F, 1.4F, 11, BEC);
        boite(c, m, -1.6F, -0.6F, -11, 1.6F, 0.6F, -5, NOIR2);
        boite(c, m, -1.72F, 1, 6.1F, -1.45F, 2, 7.1F, ROUGE);
        boite(c, m, 1.45F, 1, 6.1F, 1.72F, 2, 7.1F, ROUGE);
        for (int k = -1; k <= 1; k += 2) {
            ps.pushPose();
            ps.translate(k * 2.1F, 1.4F, 0);
            ps.mulPose(Axis.ZP.rotation(k * aile));
            Matrix4f a = ps.last().pose();
            boite(c, a, k > 0 ? 0 : -10, -0.4F, -4, k > 0 ? 10 : 0, 0.4F, 4, NOIR);
            ps.translate(k * 10, 0, 0);
            ps.mulPose(Axis.ZP.rotation(k * aile * 0.8F));
            a = ps.last().pose();
            boite(c, a, k > 0 ? 0 : -9, -0.3F, -3.5F, k > 0 ? 9 : 0, 0.3F, 2.5F, NOIR2);
            ps.popPose();
        }
    }

    private static void boite(VertexConsumer c, Matrix4f m, float x0, float y0, float z0, float x1, float y1, float z1, int couleur) {
        face(c, m, couleur, 1F, x0, y1, z0, x0, y1, z1, x1, y1, z1, x1, y1, z0);
        face(c, m, couleur, 0.55F, x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1);
        face(c, m, couleur, 0.8F, x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1);
        face(c, m, couleur, 0.8F, x1, y0, z0, x0, y0, z0, x0, y1, z0, x1, y1, z0);
        face(c, m, couleur, 0.68F, x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0);
        face(c, m, couleur, 0.68F, x1, y0, z1, x1, y0, z0, x1, y1, z0, x1, y1, z1);
    }

    private static void face(VertexConsumer c, Matrix4f m, int couleur, float lum, float... p) {
        float r = (couleur >> 16 & 255) / 255F * lum, g = (couleur >> 8 & 255) / 255F * lum, b = (couleur & 255) / 255F * lum;
        for (int i = 0; i < 12; i += 3) {
            c.addVertex(m, p[i], p[i + 1], p[i + 2]).setColor(r, g, b, 1F);
        }
    }

    // ================================================================ l'explosion d'encre et de plumes

    private static final class Eclat {
        final Vec3 centre;
        final boolean entree;
        final long debut = System.nanoTime();
        /** {dx, dy, dz, distance, lacet, tangage} */
        final double[][] plumes = new double[150][];
        /** {angle, rayon, hauteur, battement} */
        final double[][] nuee = new double[11][];
        /** {dx, dy, dz, taille} */
        final double[][] encre = new double[170][];

        Eclat(Vec3 centre, boolean entree) {
            this.centre = centre;
            this.entree = entree;
            Random r = new Random();
            for (int i = 0; i < plumes.length; i++) {
                double a = r.nextDouble() * Math.PI * 2, b = (r.nextDouble() - 0.35) * 1.2;
                plumes[i] = new double[]{Math.cos(a) * Math.cos(b), Math.sin(b), Math.sin(a) * Math.cos(b), 1.2 + r.nextDouble() * 2.6, r.nextDouble() * 360, r.nextDouble() * 180};
            }
            for (int i = 0; i < nuee.length; i++) {
                nuee[i] = new double[]{r.nextDouble() * Math.PI * 2, 2.2 + r.nextDouble() * 1.6, r.nextDouble() * 1.8, r.nextDouble() * 6};
            }
            for (int i = 0; i < encre.length; i++) {
                double a = r.nextDouble() * Math.PI * 2, b = (r.nextDouble() - 0.5) * Math.PI;
                double d = Math.sqrt(r.nextDouble());
                encre[i] = new double[]{Math.cos(a) * Math.cos(b) * d, Math.sin(b) * d * 1.25, Math.sin(a) * Math.cos(b) * d, 0.06 + r.nextDouble() * 0.11};
            }
        }
    }

    private static double sortie(double u) {
        return 1 - Math.pow(1 - borne(u), 3);
    }

    @SubscribeEvent
    public static void dessinerEclats(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) {
            return;
        }
        TRANSITIONS.values().removeIf(t -> secondes(t.debut()) > DUREE_ECLAT);
        ECLATS.removeIf(e -> secondes(e.debut) > DUREE_ECLAT);
        if (ECLATS.isEmpty()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        VertexConsumer c = buffers.getBuffer(RenderType.debugQuads());
        PoseStack ps = event.getPoseStack();
        Vec3 cam = event.getCamera().getPosition();
        for (Eclat e : ECLATS) {
            double t = secondes(e.debut);
            ps.pushPose();
            ps.translate(e.centre.x - cam.x, e.centre.y - cam.y, e.centre.z - cam.z);
            encre(ps, c, e, t);
            plumes(ps, c, e, t);
            nuee(ps, c, e, t);
            ps.popPose();
        }
        buffers.endBatch(RenderType.debugQuads());
    }

    /** La boule d'encre : elle gonfle sur lui puis se défait ; au retour, elle se rassemble là où il va apparaître. */
    private static void encre(PoseStack ps, VertexConsumer c, Eclat e, double t) {
        double duree = e.entree ? 1.0 : RETOUR + 0.2;
        if (t > duree) {
            return;
        }
        double u = t / duree;
        double taille = e.entree ? sortie(u / 0.35) * (1 - borne((u - 0.55) / 0.45)) : borne(u / 0.5) * (1 - borne((u - 0.8) / 0.2));
        double ecart = e.entree ? 0.9 + u * 1.2 : 1.8 - u * 1.1;
        for (double[] p : e.encre) {
            float s = (float) (p[3] * taille);
            if (s <= 0.005F) {
                continue;
            }
            ps.pushPose();
            ps.translate(p[0] * ecart, p[1] * ecart, p[2] * ecart);
            boite(c, ps.last().pose(), -s, -s, -s, s, s, s, NOIR);
            ps.popPose();
        }
    }

    /** Les plumes partent d'un coup, freinent, puis tombent doucement. */
    private static void plumes(PoseStack ps, VertexConsumer c, Eclat e, double t) {
        for (double[] p : e.plumes) {
            double d = p[3] * sortie(t / 0.9);
            double chute = Math.max(0, t - 0.7) * 0.75;
            float s = (float) (1 - borne((t - DUREE_ECLAT + 0.5) / 0.5));
            if (s <= 0) {
                continue;
            }
            ps.pushPose();
            ps.translate(p[0] * d, Math.max(-e.centre.y + e.centre.y - 0.95, p[1] * d - chute), p[2] * d);
            ps.mulPose(Axis.YP.rotationDegrees((float) (p[4] + t * 90)));
            ps.mulPose(Axis.XP.rotationDegrees((float) (p[5] + t * 140)));
            boite(c, ps.last().pose(), -0.055F * s, -0.008F, -0.14F * s, 0.055F * s, 0.008F, 0.14F * s, NOIR);
            ps.popPose();
        }
    }

    /** Les petits corbeaux : ils s'échappent en tournant ; au retour, ils arrivent de loin et plongent dans l'encre. */
    private static void nuee(PoseStack ps, VertexConsumer c, Eclat e, double t) {
        double duree = e.entree ? 1.2 : RETOUR;
        if (t > duree) {
            return;
        }
        double u = t / duree;
        double avance = e.entree ? sortie(u) : 1 - u * u;
        float s = (float) (ECHELLE * (e.entree ? 0.75 * (1 - borne((u - 0.75) / 0.25)) : 0.75));
        for (double[] p : e.nuee) {
            double a = p[0] + t * 1.6;
            ps.pushPose();
            ps.translate(Math.cos(a) * p[1] * avance, p[2] * avance - 0.2, Math.sin(a) * p[1] * avance);
            ps.mulPose(Axis.YP.rotation((float) (-a)));
            ps.scale(s, s, s);
            corbeau(ps, c, 0.15F + 0.6F * (float) Math.sin(t * 22 + p[3]));
            ps.popPose();
        }
    }
}
