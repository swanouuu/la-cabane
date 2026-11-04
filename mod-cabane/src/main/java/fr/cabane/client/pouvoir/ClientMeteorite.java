package fr.cabane.client.pouvoir;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import fr.cabane.Cabane;
import fr.cabane.pouvoir.Meteorite;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;
import net.neoforged.neoforge.client.model.data.ModelData;
import org.joml.Matrix4f;

/**
 * La météorite chez chaque joueur (voir {@link Meteorite} et docs/sharingan/ulti-meteorite.mp4) : son bras levé, le trait
 * de lumière jusqu'à la sphère noire et ses anneaux violets ; les blocs du terrain (les vrais : herbe, terre, pierre…)
 * arrachés qui montent en spirale, ceux qui tournent autour ; la météorite faite de ces blocs qui grossit, tremble, et
 * dont les fissures s'allument ; l'explosion (la boule de lumière, les ondes), puis la pluie de blocs qui retombent et
 * se plantent dans un nuage de poussière. L'écran blanchit et tremble pour ceux qui sont près.
 */
public final class ClientMeteorite {
    private static final double TICK = 20;

    /** Un bloc qui vole (arraché au sol, ou en orbite) ou qui fait la surface de la météorite. */
    private record Volant(Vec3 depart, BlockState etat, double debut, double duree, double taille, int k) {
    }

    private record Orbite(Vec3 axe, double a0, double ecart, double vitesse, double taille, BlockState etat, double debut) {
    }

    private record Surface(Vec3 dir, double taille, float ax, float ay, double bosse, boolean fissure, BlockState etat, boolean rocher) {
    }

    private static final class EnCours {
        UUID lanceur;
        Vec3 c;
        double sol;
        long debut;
        List<Meteorite.Eclat> eclats;
        boolean[] tombes;
        final List<BlockState> palette = new ArrayList<>();
        final List<Volant> volants = new ArrayList<>();
        final List<Orbite> orbites = new ArrayList<>();
        final List<Surface> surface = new ArrayList<>();

        double t(float partiel) {
            Minecraft mc = Minecraft.getInstance();
            return mc.level.getGameTime() - debut + partiel;
        }
    }

    private static final List<EnCours> EN_COURS = new ArrayList<>();

    private static double borne(double u) {
        return Math.max(0, Math.min(1, u));
    }

    private static double sortie(double u) {
        return 1 - Math.pow(1 - borne(u), 3);
    }

    private static double lisse(double u) {
        u = borne(u);
        return u * u * (3 - 2 * u);
    }

    private static Vec3 rotY(Vec3 v, double a) {
        return new Vec3(v.x * Math.cos(a) + v.z * Math.sin(a), v.y, -v.x * Math.sin(a) + v.z * Math.cos(a));
    }

    // ================================================================ le départ : on regarde le terrain pour prendre ses blocs

    public static void recevoir(Meteorite.Debut p) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return;
        }
        EnCours e = new EnCours();
        e.lanceur = p.lanceur();
        e.c = new Vec3(p.cx(), p.cy(), p.cz());
        e.sol = p.sol();
        e.debut = mc.level.getGameTime();
        e.eclats = Meteorite.eclats(p.graine(), e.c, e.sol);
        e.tombes = new boolean[e.eclats.size()];
        if (mc.player != null && p.lanceur().equals(mc.player.getUUID())) {
            ClientSharingan.meteoriteFaite = true;
        }
        Random r = new Random(p.graine());
        // les blocs du dessus du sol, tout autour (la météorite sera faite de ce terrain-là)
        List<Vec3> lieux = new ArrayList<>();
        List<BlockState> etats = new ArrayList<>();
        for (int i = 0; i < 420; i++) {
            double d = 30 * Math.sqrt(r.nextDouble()), a = r.nextDouble() * Math.PI * 2;
            int x = (int) Math.floor(e.c.x + Math.cos(a) * d), z = (int) Math.floor(e.c.z + Math.sin(a) * d);
            int y = mc.level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
            BlockState s = mc.level.getBlockState(new net.minecraft.core.BlockPos(x, y, z));
            if (s.isAir() || s.getRenderShape() != RenderShape.MODEL || !s.getFluidState().isEmpty()) {
                continue;
            }
            lieux.add(new Vec3(x + 0.5, y + 0.5, z + 0.5));
            etats.add(s);
            e.palette.add(s);
        }
        for (BlockState s : new BlockState[]{Blocks.STONE.defaultBlockState(), Blocks.DIRT.defaultBlockState(), Blocks.COBBLESTONE.defaultBlockState(),
                Blocks.STONE.defaultBlockState(), Blocks.GRAVEL.defaultBlockState()}) {
            e.palette.add(s);
        }
        // les blocs arrachés qui montent (un sur deux des lieux), du bord vers le milieu
        for (int i = 0; i < lieux.size() && e.volants.size() < 240; i += 2) {
            Vec3 l = lieux.get(i);
            double d = Math.hypot(l.x - e.c.x, l.z - e.c.z) / 30;
            boolean gros = r.nextDouble() < 0.12;
            e.volants.add(new Volant(l, gros ? Blocks.STONE.defaultBlockState() : etats.get(i), Meteorite.NAISSANCE + 60 * Math.pow(d, 0.9) + 10 * r.nextDouble(),
                    20 + 14 * r.nextDouble(), gros ? 2 : 1, e.volants.size()));
        }
        for (int k = 0; k < 90; k++) {
            Vec3 axe = new Vec3(r.nextDouble() - 0.5, 2.2 + r.nextDouble(), r.nextDouble() - 0.5).normalize();
            e.orbites.add(new Orbite(axe, r.nextDouble() * Math.PI * 2, 6 + 18 * r.nextDouble(), 1 + 1.2 * r.nextDouble(),
                    r.nextDouble() < 0.3 ? 2 : 1, palette(e, r.nextInt(1000)), 30 + 60 * r.nextDouble()));
        }
        // la surface : une sphère de Fibonacci, et de gros rochers par-dessus
        int n = 650;
        double or = Math.PI * (3 - Math.sqrt(5));
        for (int k = 0; k < n; k++) {
            double y = 1 - 2.0 * (k + 0.5) / n, rr = Math.sqrt(1 - y * y), a = k * or;
            Vec3 dir = new Vec3(Math.cos(a) * rr, y, Math.sin(a) * rr);
            boolean fissure = Math.abs(dir.dot(new Vec3(1, 0.3, 0).normalize())) < 0.07 || Math.abs(dir.dot(new Vec3(0.2, 1, 0.6).normalize())) < 0.07
                    || Math.abs(dir.dot(new Vec3(-0.5, 0.2, 1).normalize())) < 0.07;
            e.surface.add(new Surface(dir, 2.6 + r.nextDouble(), (float) (r.nextDouble() * 3), (float) (r.nextDouble() * 3), 1 + 0.08 * (r.nextDouble() - 0.5),
                    fissure, palette(e, r.nextInt(1000)), false));
        }
        for (int k = 0; k < 40; k++) {
            Vec3 dir = new Vec3(r.nextDouble() - 0.5, r.nextDouble() - 0.5, r.nextDouble() - 0.5).normalize();
            BlockState s = r.nextBoolean() ? Blocks.STONE.defaultBlockState() : r.nextBoolean() ? Blocks.COBBLESTONE.defaultBlockState() : Blocks.ANDESITE.defaultBlockState();
            e.surface.add(new Surface(dir, 4 + 2.5 * r.nextDouble(), (float) (r.nextDouble() * 3), (float) (r.nextDouble() * 3), 0.96, false, s, true));
        }
        EN_COURS.add(e);
    }

    private static BlockState palette(EnCours e, int i) {
        return e.palette.get(Math.floorMod(i, e.palette.size()));
    }

    /** Son rayon, comme côté serveur. */
    private static double rayon(double t) {
        return Meteorite.rayon(t);
    }

    /** Un bloc arraché monte en spirale autour de la météorite et se colle à sa surface (comme vol() dans meteorite.mjs). */
    private static Vec3 vol(EnCours e, Vec3 p0, double u, double ta, int k) {
        Random r = new Random(k * 7919L);
        double ee = Math.pow(lisse(u), 1.3), tour = 2.4 + 1.2 * r.nextDouble();
        Vec3 d0 = p0.subtract(e.c), dr = rotY(d0, tour * ee);
        Vec3 n = new Vec3(dr.x, dr.y * 0.4 + (r.nextDouble() - 0.5) * 56, dr.z).normalize();
        return e.c.add(dr.scale(1 - ee)).add(n.scale(rayon(ta) * ee)).add(0, 12.5 * Math.sin(Math.PI * u) * (1 - ee), 0);
    }

    // ================================================================ son bras levé

    public static void poser(PlayerModel<?> modele, Player joueur) {
        if (EN_COURS.isEmpty() || Minecraft.getInstance().level == null) {
            return;
        }
        float partiel = Minecraft.getInstance().getTimer().getGameTimeDeltaPartialTick(false);
        for (EnCours e : EN_COURS) {
            if (!e.lanceur.equals(joueur.getUUID())) {
                continue;
            }
            double t = e.t(partiel);
            double leve = sortie(t / 14) * (1 - sortie((t - Meteorite.EXPLOSION - 30) / 15));
            double poing = sortie((t - 136) / 10), recul = t > Meteorite.EXPLOSION ? Math.max(0, 1 - Math.abs(t - Meteorite.EXPLOSION - 2) / 7) : 0;
            if (leve <= 0.001) {
                return;
            }
            modele.rightArm.xRot = (float) (modele.rightArm.xRot * (1 - leve) + (-2.6 - 0.15 * poing + 0.5 * recul) * leve);
            modele.rightArm.yRot = (float) (modele.rightArm.yRot * (1 - leve));
            modele.rightArm.zRot = (float) (modele.rightArm.zRot * (1 - leve) + 0.1 * leve);
            modele.rightSleeve.copyFrom(modele.rightArm);
            return;
        }
    }

    // ================================================================ le dessin

    private static void dessiner(PoseStack ps, Vec3 cam, float partiel) {
        Minecraft mc = Minecraft.getInstance();
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        BlockRenderDispatcher br = mc.getBlockRenderer();
        for (EnCours e : EN_COURS) {
            double t = e.t(partiel);
            Matrix4f m = ps.last().pose();
            double r = rayon(t);
            // le trait de lumière, de sa main à la sphère
            Player l = mc.level.getPlayerByUUID(e.lanceur);
            double trait = Math.max(0, 1 - Math.abs(t - 13) / 9);
            if (l != null && trait > 0) {
                float lacet = (float) Math.toRadians(l.yBodyRot);
                Vec3 droite = new Vec3(-Math.cos(lacet), 0, -Math.sin(lacet));
                Vec3 main = l.getPosition(partiel).add(0, 2.3, 0).add(droite.scale(0.35));
                VertexConsumer v = buffers.getBuffer(ClientSharingan.Types.VOILE);
                poutre(v, m, main.subtract(cam), e.c.subtract(cam), 0.35 * trait, 0.89F, 0.8F, 1F, (float) (0.8 * trait));
            }
            if (t < Meteorite.EXPLOSION) {
                avant(ps, buffers, br, cam, e, t, r);
            } else {
                apres(ps, buffers, br, cam, e, t);
            }
        }
        buffers.endBatch();
    }

    /** Avant l'explosion : la sphère noire et ses anneaux, les blocs qui montent, ceux qui tournent, la surface, les fissures. */
    private static void avant(PoseStack ps, MultiBufferSource.BufferSource buffers, BlockRenderDispatcher br, Vec3 cam, EnCours e, double t, double r) {
        Matrix4f m = ps.last().pose();
        double naissance = sortie((t - 10) / 12);
        if (naissance > 0) {
            VertexConsumer plein = buffers.getBuffer(ClientSharingan.Types.PLEIN);
            double rc = 4.4 * naissance;
            cube(plein, m, e.c.subtract(cam), rc * 0.65, t * 0.035, 0.04F, 0.015F, 0.06F, 1);
            cube(plein, m, e.c.subtract(cam), rc * 0.65, t * 0.035 + 0.8, 0.06F, 0.03F, 0.1F, 1);
            VertexConsumer voile = buffers.getBuffer(ClientSharingan.Types.VOILE);
            for (int q = 0; q < 3; q++) {
                Vec3 axe = rotY(new Vec3(0, Math.cos(0.5 + q * 0.9), Math.sin(0.5 + q * 0.9)), t / 20 * (0.8 + q * 0.3) + q * 2);
                double rr = Math.max(rc * 1.6, r * (1.15 + 0.12 * q)) + 0.6 * Math.sin(t / 4 + q);
                anneau(voile, m, e.c.subtract(cam), axe, rr, 0.4 + 0.25 * naissance, 0.69F, 0.47F, 1F, (float) (0.35 * naissance));
            }
        }
        for (Volant v : e.volants) {
            double u = (t - v.debut()) / v.duree();
            if (u < 0 || u >= 1) {
                continue;
            }
            Vec3 p = vol(e, v.depart(), u, v.debut() + v.duree(), v.k());
            bloc(ps, buffers, br, cam, p, v.taille(), (float) (u * 6 * (v.k() % 2 == 0 ? 1 : -1)), (float) (u * 5 + v.k()), v.etat());
        }
        for (Orbite o : e.orbites) {
            double vie = (t - o.debut()) / 52;
            if (vie < 0 || vie >= 1) {
                continue;
            }
            double ang = o.a0() + o.vitesse() * (t - o.debut()) / TICK * 2;
            double rr = r + o.ecart() * (1 - lisse((vie - 0.6) / 0.4));
            Vec3 e1 = new Vec3(o.axe().y, -o.axe().x, 0).normalize(), e2 = o.axe().cross(e1).normalize();
            Vec3 p = e.c.add(e1.scale(Math.cos(ang) * rr)).add(e2.scale(Math.sin(ang) * rr));
            bloc(ps, buffers, br, cam, p, o.taille(), (float) ang, (float) (ang * 0.7), o.etat());
        }
        if (r <= 0) {
            return;
        }
        double fiss = lisse((t - Meteorite.FISSURES) / (Meteorite.EXPLOSION - 4 - Meteorite.FISSURES));
        double trem = 0.4 * borne((t - 125) / 32);
        VertexConsumer voile = null;
        for (int k = 0; k < e.surface.size(); k++) {
            Surface s = e.surface.get(k);
            if (s.rocher() && t < 80) {
                continue;
            }
            Vec3 tr = new Vec3(trem * Math.sin(t * 3.05 + k), trem * Math.sin(t * 2.65 + k * 2), trem * Math.cos(t * 2.35 + k));
            Vec3 p = e.c.add(s.dir().scale(r * s.bosse() * 0.93)).add(tr);
            double taille = s.taille() * Math.max(0.35, r / Meteorite.RAYON);
            bloc(ps, buffers, br, cam, p, taille, s.ax(), s.ay(), s.etat());
            if (s.fissure() && fiss > 0) {
                if (voile == null) {
                    voile = buffers.getBuffer(ClientSharingan.Types.VOILE);
                }
                float a = (float) (fiss * (0.6 + 0.25 * Math.sin(t * 1.5 + k)));
                cube(voile, ps.last().pose(), p.subtract(cam), taille * 0.56, s.ay(), 0.89F, 0.8F, 1F, a);
            }
        }
    }

    /** Après : la boule de lumière, les ondes, la pluie de blocs. */
    private static void apres(PoseStack ps, MultiBufferSource.BufferSource buffers, BlockRenderDispatcher br, Vec3 cam, EnCours e, double t) {
        Minecraft mc = Minecraft.getInstance();
        double tau = (t - Meteorite.EXPLOSION) / TICK;
        Matrix4f m = ps.last().pose();
        VertexConsumer voile = buffers.getBuffer(ClientSharingan.Types.VOILE);
        double b = borne(tau / 0.9);
        if (b < 1) {
            sphere(voile, m, e.c.subtract(cam), Meteorite.RAYON * (0.7 + 1.8 * sortie(b)), (float) (1 - 0.3 * b), (float) (1 - 0.53 * b), 1F, (float) (0.85 * (1 - b)));
        }
        double[][] ondes = {{0, 0, 1, 0}, {0.12, 0.4, 1, 0.2}, {0.22, -0.3, 0.6, 1}};
        for (double[] o : ondes) {
            double k = borne((tau - o[0]) / 1.6);
            if (k > 0 && k < 1) {
                anneau(voile, m, e.c.subtract(cam), new Vec3(o[1], o[2], o[3]).normalize(), Meteorite.RAYON + 160 * sortie(k), 3 * (1 - k) + 0.6,
                        0.93F, 0.86F, 1F, (float) (0.6 * (1 - k)));
            }
        }
        double fondu = 1 - borne((t - (Meteorite.FIN - 20)) / 20);
        double chaud = 1 - borne(tau / 1.2);
        for (int k = 0; k < e.eclats.size(); k++) {
            Meteorite.Eclat ec = e.eclats.get(k);
            Vec3 p = ec.position(e.c, tau);
            double tt = Math.min(tau, ec.impact());
            Random r = new Random(k * 104729L);
            float ax = (float) (r.nextDouble() * 3 + (r.nextDouble() - 0.5) * 10 * tt), ay = (float) (r.nextDouble() * 3 + (r.nextDouble() - 0.5) * 10 * tt);
            double taille = ec.taille() * fondu;
            if (taille > 0.02) {
                bloc(ps, buffers, br, cam, p, taille, ax, ay, palette(e, k * 37));
                if (chaud > 0.05) {
                    cube(buffers.getBuffer(ClientSharingan.Types.VOILE), ps.last().pose(), p.subtract(cam), taille * 0.56, ay, 0.89F, 0.8F, 1F, (float) (0.55 * chaud));
                }
            }
        }
    }

    /** Les poussières : une traînée derrière ceux qui tombent, un nuage à l'impact. */
    private static void particules(EnCours e) {
        Minecraft mc = Minecraft.getInstance();
        double t = e.t(0);
        if (t < Meteorite.EXPLOSION) {
            // le sol qui se fend sous la sphère : de la poussière qui monte
            if (t > Meteorite.NAISSANCE && t < Meteorite.FIN_ATTRACTION) {
                for (int i = 0; i < 4; i++) {
                    double a = mc.level.random.nextDouble() * Math.PI * 2, d = mc.level.random.nextDouble() * 12;
                    mc.level.addParticle(ParticleTypes.CAMPFIRE_COSY_SMOKE, e.c.x + Math.cos(a) * d, e.sol + 1, e.c.z + Math.sin(a) * d, 0, 0.12, 0);
                }
            }
            return;
        }
        double tau = (t - Meteorite.EXPLOSION) / TICK;
        for (int k = 0; k < e.eclats.size(); k++) {
            Meteorite.Eclat ec = e.eclats.get(k);
            if (tau < ec.impact()) {
                if (k % 3 == 0) {
                    Vec3 p = ec.position(e.c, tau);
                    mc.level.addParticle(ParticleTypes.CLOUD, p.x, p.y, p.z, 0, 0.02, 0);
                }
            } else if (!e.tombes[k]) {
                e.tombes[k] = true;
                Vec3 p = ec.point();
                BlockState s = palette(e, k * 37);
                for (int i = 0; i < 10; i++) {
                    mc.level.addParticle(new BlockParticleOption(ParticleTypes.BLOCK, s), p.x + (mc.level.random.nextDouble() - 0.5) * ec.taille(), p.y + 0.6,
                            p.z + (mc.level.random.nextDouble() - 0.5) * ec.taille(), 0, 0.4, 0);
                }
                if (k % 2 == 0) {
                    mc.level.addParticle(ParticleTypes.EXPLOSION, p.x, p.y + 1, p.z, 0, 0, 0);
                }
                mc.level.addParticle(ParticleTypes.CAMPFIRE_COSY_SMOKE, p.x, p.y + 0.5, p.z, 0, 0.05, 0);
            }
        }
    }

    // ================================================================ des formes simples (dans le repère de la caméra)

    private static void bloc(PoseStack ps, MultiBufferSource buffers, BlockRenderDispatcher br, Vec3 cam, Vec3 p, double taille, float ax, float ay, BlockState etat) {
        ps.pushPose();
        ps.translate(p.x - cam.x, p.y - cam.y, p.z - cam.z);
        ps.mulPose(Axis.YP.rotation(ay));
        ps.mulPose(Axis.XP.rotation(ax));
        ps.scale((float) taille, (float) taille, (float) taille);
        ps.translate(-0.5, -0.5, -0.5);
        br.renderSingleBlock(etat, ps, buffers, LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY, ModelData.EMPTY, null);
        ps.popPose();
    }

    private static void sommet(VertexConsumer c, Matrix4f m, Vec3 p, float r, float g, float b, float a) {
        c.addVertex(m, (float) p.x, (float) p.y, (float) p.z).setColor(r, g, b, a);
    }

    private static final float[] OMBRES = {1F, 0.55F, 0.82F, 0.82F, 0.7F, 0.7F};

    /** Un cube tourné autour de la verticale, de demi-côté s. */
    private static void cube(VertexConsumer c, Matrix4f m, Vec3 o, double s, double angle, float r, float g, float b, float a) {
        Vec3 x = new Vec3(Math.cos(angle), 0, Math.sin(angle)).scale(s), z = new Vec3(-Math.sin(angle), 0, Math.cos(angle)).scale(s), y = new Vec3(0, s, 0);
        Vec3[][] faces = {{y, x, z}, {y.scale(-1), x, z}, {x, y, z}, {x.scale(-1), y, z}, {z, x, y}, {z.scale(-1), x, y}};
        for (int f = 0; f < 6; f++) {
            Vec3 cen = o.add(faces[f][0]), u = faces[f][1], v = faces[f][2];
            float k = OMBRES[f];
            sommet(c, m, cen.subtract(u).subtract(v), r * k, g * k, b * k, a);
            sommet(c, m, cen.add(u).subtract(v), r * k, g * k, b * k, a);
            sommet(c, m, cen.add(u).add(v), r * k, g * k, b * k, a);
            sommet(c, m, cen.subtract(u).add(v), r * k, g * k, b * k, a);
        }
    }

    /** Une poutre (le trait de lumière) de a à b, de demi-épaisseur e. */
    private static void poutre(VertexConsumer c, Matrix4f m, Vec3 a, Vec3 b, double e, float r, float g, float bl, float al) {
        Vec3 d = b.subtract(a).normalize();
        Vec3 u = d.cross(new Vec3(0, 1, 0));
        if (u.lengthSqr() < 1e-4) {
            u = d.cross(new Vec3(1, 0, 0));
        }
        u = u.normalize().scale(e);
        Vec3 v = d.cross(u).normalize().scale(e);
        Vec3[] coins = {u.add(v), u.subtract(v), u.scale(-1).subtract(v), u.scale(-1).add(v)};
        for (int i = 0; i < 4; i++) {
            Vec3 p0 = coins[i], p1 = coins[(i + 1) % 4];
            sommet(c, m, a.add(p0), r, g, bl, al);
            sommet(c, m, a.add(p1), r, g, bl, al);
            sommet(c, m, b.add(p1), r, g, bl, al);
            sommet(c, m, b.add(p0), r, g, bl, al);
        }
    }

    /** Un anneau plat de centre o, de normale n, de rayon rr et de largeur l. */
    private static void anneau(VertexConsumer c, Matrix4f m, Vec3 o, Vec3 n, double rr, double l, float r, float g, float b, float a) {
        Vec3 e1 = Math.abs(n.y) > 0.9 ? new Vec3(1, 0, 0) : new Vec3(n.z, 0, -n.x).normalize();
        Vec3 e2 = n.cross(e1).normalize();
        int segs = 56;
        for (int q = 0; q < segs; q++) {
            double a0 = q * Math.PI * 2 / segs, a1 = (q + 1) * Math.PI * 2 / segs;
            Vec3 d0 = e1.scale(Math.cos(a0)).add(e2.scale(Math.sin(a0))), d1 = e1.scale(Math.cos(a1)).add(e2.scale(Math.sin(a1)));
            sommet(c, m, o.add(d0.scale(rr - l / 2)), r, g, b, a);
            sommet(c, m, o.add(d1.scale(rr - l / 2)), r, g, b, a);
            sommet(c, m, o.add(d1.scale(rr + l / 2)), r, g, b, a);
            sommet(c, m, o.add(d0.scale(rr + l / 2)), r, g, b, a);
        }
    }

    /** Une sphère de lumière (méridiens et parallèles). */
    private static void sphere(VertexConsumer c, Matrix4f m, Vec3 o, double rr, float r, float g, float b, float a) {
        int lat = 10, lon = 20;
        for (int i = 0; i < lat; i++) {
            double t0 = -Math.PI / 2 + i * Math.PI / lat, t1 = t0 + Math.PI / lat;
            for (int j = 0; j < lon; j++) {
                double p0 = j * Math.PI * 2 / lon, p1 = p0 + Math.PI * 2 / lon;
                sommet(c, m, o.add(point(t0, p0, rr)), r, g, b, a);
                sommet(c, m, o.add(point(t0, p1, rr)), r, g, b, a);
                sommet(c, m, o.add(point(t1, p1, rr)), r, g, b, a);
                sommet(c, m, o.add(point(t1, p0, rr)), r, g, b, a);
            }
        }
    }

    private static Vec3 point(double lat, double lon, double r) {
        return new Vec3(Math.cos(lat) * Math.cos(lon) * r, Math.sin(lat) * r, Math.cos(lat) * Math.sin(lon) * r);
    }

    // ================================================================ l'écran : l'éclair blanc, les secousses

    private static double proche(EnCours e) {
        Minecraft mc = Minecraft.getInstance();
        return mc.player == null ? 1e9 : mc.player.position().distanceTo(e.c);
    }

    private static void ecran(GuiGraphics g) {
        Minecraft mc = Minecraft.getInstance();
        if (EN_COURS.isEmpty() || mc.level == null) {
            return;
        }
        float partiel = mc.getTimer().getGameTimeDeltaPartialTick(false);
        for (EnCours e : EN_COURS) {
            double tau = (e.t(partiel) - Meteorite.EXPLOSION) / TICK;
            if (tau < 0 || tau > 0.5 || proche(e) > 260) {
                continue;
            }
            double a = 0.92 * (1 - tau / 0.45);
            if (a > 0) {
                g.fill(0, 0, g.guiWidth(), g.guiHeight(), ((int) (255 * a) << 24) | 0xF4ECFF);
            }
        }
    }

    private static double secousse(float partiel) {
        double s = 0;
        for (EnCours e : EN_COURS) {
            double t = e.t(partiel), d = proche(e);
            if (d > 120) {
                continue;
            }
            double loin = 1 - d / 120;
            if (t > Meteorite.NAISSANCE && t < Meteorite.EXPLOSION) {
                s = Math.max(s, (0.15 + 0.5 * borne((t - 125) / 32)) * loin);
            }
            double tau = (t - Meteorite.EXPLOSION) / TICK;
            if (tau >= 0 && tau < 3) {
                s = Math.max(s, (3.5 * (1 - borne(tau / 0.9)) + 0.6 * (1 - borne(tau / 3))) * loin);
            }
        }
        return s;
    }

    // ================================================================ branchements

    @EventBusSubscriber(modid = Cabane.MODID, value = Dist.CLIENT)
    public static final class Enregistrement {
        @SubscribeEvent
        public static void ecrans(RegisterGuiLayersEvent event) {
            event.registerAboveAll(Cabane.id("meteorite"), (g, delta) -> ecran(g));
        }

        private Enregistrement() {
        }
    }

    @EventBusSubscriber(modid = Cabane.MODID, value = Dist.CLIENT)
    public static final class Evenements {
        @SubscribeEvent
        public static void tick(ClientTickEvent.Post event) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.level == null) {
                EN_COURS.clear();
                return;
            }
            EN_COURS.removeIf(e -> e.t(0) > Meteorite.FIN + 10 || mc.level.getGameTime() < e.debut);
            for (EnCours e : EN_COURS) {
                particules(e);
            }
        }

        @SubscribeEvent
        public static void dessiner(RenderLevelStageEvent event) {
            if (EN_COURS.isEmpty() || event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES || Minecraft.getInstance().level == null) {
                return;
            }
            ClientMeteorite.dessiner(event.getPoseStack(), event.getCamera().getPosition(), event.getPartialTick().getGameTimeDeltaPartialTick(false));
        }

        @SubscribeEvent
        public static void secouer(ViewportEvent.ComputeCameraAngles event) {
            if (EN_COURS.isEmpty()) {
                return;
            }
            double s = secousse((float) event.getPartialTick());
            if (s > 0) {
                event.setYaw((float) (event.getYaw() + (Math.random() - 0.5) * s));
                event.setPitch((float) (event.getPitch() + (Math.random() - 0.5) * s));
            }
        }

        private Evenements() {
        }
    }

    private ClientMeteorite() {
    }
}
