package fr.cabane.client.pouvoir;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.math.Axis;
import fr.cabane.Cabane;
import fr.cabane.pouvoir.Marque3;
import fr.cabane.pouvoir.SerpentGeant;
import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;
import org.joml.Matrix4f;
import org.joml.Matrix4fStack;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * L'ulti de la marque 3, chez chaque joueur ({@link Marque3}, {@link SerpentGeant}) : le sceau qui s'étale, le pouf de
 * fumée, le grand serpent violet en cubes (tête en quarts de bloc, corps en demi-blocs) qui se dresse puis fonce, les
 * copies de blocs qui volent et retombent (le vrai sol n'est jamais touché), le marqué debout sur la tête, la caméra qui
 * tremble. Validé dans l'aperçu docs/marque3/ulti ; le modèle vient de assets/cabane/marque/serpent-geant.bin (jeu.mjs).
 */
public final class ClientSerpentGeant {
    private static final ResourceLocation MODELE = Cabane.id("marque/serpent-geant.bin");
    private static final ResourceLocation SCEAU = Cabane.id("textures/marque/sceau.png");
    private static final ResourceLocation BOUFFEE = Cabane.id("textures/marque/bouffee.png");

    // ================================================================ le modèle

    /** Des faces de cubes : le coin de chaque cube (o + ijk × pas), le sens de la face, sa couleur (déjà éclairée). */
    private record Morceau(float ox, float oy, float oz, float pas, short[] ijk, byte[] infos, int n) {
        static Morceau lire(DataInputStream f) throws IOException {
            float ox = f.readFloat(), oy = f.readFloat(), oz = f.readFloat(), pas = f.readFloat();
            int n = f.readInt();
            short[] ijk = new short[n * 3];
            byte[] infos = new byte[n * 4];
            for (int i = 0; i < n; i++) {
                ijk[i * 3] = f.readShort();
                ijk[i * 3 + 1] = f.readShort();
                ijk[i * 3 + 2] = f.readShort();
                f.readFully(infos, i * 4, 4);
            }
            return new Morceau(ox, oy, oz, pas, ijk, infos, n);
        }
    }

    /** Les quatre coins de chaque face (décalages du coin du cube), dans l'ordre des sens +x, -x, +y, -y, +z, -z. */
    private static final int[][][] COINS = {
        {{1, 0, 0}, {1, 1, 0}, {1, 1, 1}, {1, 0, 1}},
        {{0, 0, 0}, {0, 0, 1}, {0, 1, 1}, {0, 1, 0}},
        {{0, 1, 0}, {0, 1, 1}, {1, 1, 1}, {1, 1, 0}},
        {{0, 0, 0}, {1, 0, 0}, {1, 0, 1}, {0, 0, 1}},
        {{0, 0, 1}, {1, 0, 1}, {1, 1, 1}, {0, 1, 1}},
        {{0, 0, 0}, {0, 1, 0}, {1, 1, 0}, {1, 0, 0}}};

    private static Morceau crane, yeux, machoire, langue, corps;
    private static VertexBuffer[] tampons;
    private static boolean essaye, pret;

    private static boolean charger() {
        if (!essaye) {
            essaye = true;
            try (var f = new DataInputStream(new BufferedInputStream(Minecraft.getInstance().getResourceManager().open(MODELE)))) {
                f.readFloat();
                f.readFloat();
                f.readFloat();
                crane = Morceau.lire(f);
                yeux = Morceau.lire(f);
                machoire = Morceau.lire(f);
                langue = Morceau.lire(f);
                corps = Morceau.lire(f);
                tampons = new VertexBuffer[]{tampon(crane), tampon(yeux), tampon(machoire), tampon(langue)};
                pret = true;
                Cabane.LOGGER.info("Grand serpent : {} faces de tête, {} de corps", crane.n + yeux.n + machoire.n + langue.n, corps.n);
            } catch (IOException | RuntimeException e) {
                Cabane.LOGGER.warn("Grand serpent : modèle illisible", e);
            }
        }
        return pret;
    }

    private static VertexBuffer tampon(Morceau m) {
        try (ByteBufferBuilder octets = new ByteBufferBuilder(m.n * 4 * 16 + 256)) {
            BufferBuilder bb = new BufferBuilder(octets, VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
            for (int f = 0; f < m.n; f++) {
                int i = m.ijk[f * 3], j = m.ijk[f * 3 + 1], k = m.ijk[f * 3 + 2], d = m.infos[f * 4];
                int r = m.infos[f * 4 + 1] & 255, g = m.infos[f * 4 + 2] & 255, b = m.infos[f * 4 + 3] & 255;
                for (int[] o : COINS[d]) {
                    bb.addVertex(m.ox + (i + o[0]) * m.pas, m.oy + (j + o[1]) * m.pas, m.oz + (k + o[2]) * m.pas).setColor(r, g, b, 255);
                }
            }
            MeshData donnees = bb.build();
            VertexBuffer vb = new VertexBuffer(VertexBuffer.Usage.STATIC);
            if (donnees != null) {
                vb.bind();
                vb.upload(donnees);
                VertexBuffer.unbind();
            }
            return vb;
        }
    }

    // ================================================================ les ultis en cours

    /** Une copie de bloc qui vole (le vrai bloc reste où il est). */
    private static final class Debris {
        BlockState etat;
        double x, y, z, xo, yo, zo, vx, vy, vz;
        float rx, ry, rz, rxo, ryo, rzo, wx, wy, wz, taille;
        int repos = -1, age;
    }

    /** Une bouffée de fumée ou de poussière. */
    private static final class Bouffee {
        double x, y, z, xo, yo, zo, vx, vy, vz;
        float age, vie, t0, t1, a0, freine, monte, r, g, b;
    }

    /** Une onde de choc : un anneau plat qui s'élargit. */
    private record Onde(double x, double y, double z, float rmax, float duree, float r, float g, float b, long debut) {
    }

    private static final class Ulti {
        UUID joueur;
        long debut;
        SerpentGeant.Trajet tr;
        double avant = -1;
        final List<Debris> debris = new ArrayList<>();
        final List<Bouffee> bouffees = new ArrayList<>();
        final List<Onde> ondes = new ArrayList<>();
        final Set<Long> souffles = new HashSet<>();
        final java.util.Random hasard = new java.util.Random();
        /** Arrêté avant la fin (le marqué s'est rendormi) : le serpent n'est plus dessiné. */
        boolean arrete;
    }

    private static final Map<UUID, Ulti> ULTIS = new HashMap<>();
    private static final int MAX_DEBRIS = 1800, MAX_BOUFFEES = 2600;
    /** Notre propre recharge (pour le panneau). */
    private static long pretLocal;

    public static void recevoir(Marque3.Ulti3 p) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return;
        }
        if (p.debut() < 0) {
            Ulti u = ULTIS.get(p.joueur());
            if (u != null && !u.arrete) {
                u.arrete = true;
                pouf(u, temps(u, 0));
            }
            return;
        }
        Ulti u = new Ulti();
        u.joueur = p.joueur();
        u.debut = p.debut();
        u.tr = new SerpentGeant.Trajet(new Vec3(p.origine().x, p.origine().y, p.origine().z), p.lacet(), p.sols());
        u.hasard.setSeed(p.debut() * 31 + p.joueur().getLeastSignificantBits());
        ULTIS.put(p.joueur(), u);
        if (mc.player != null && p.joueur().equals(mc.player.getUUID())) {
            pretLocal = System.nanoTime() + (long) ((SerpentGeant.T_FIN + Marque3.RECHARGE_ULTI / 20.0) * 1e9);
        }
    }

    /** Ce qui reste de recharge de notre grand serpent (en secondes), pour le panneau. */
    static double recharge() {
        return Math.max(0, (pretLocal - System.nanoTime()) / 1e9);
    }

    /** Notre grand serpent est-il en cours ? */
    static boolean enCours() {
        Minecraft mc = Minecraft.getInstance();
        return mc.player != null && ULTIS.containsKey(mc.player.getUUID()) && !ULTIS.get(mc.player.getUUID()).arrete;
    }

    private static double temps(Ulti u, float partiel) {
        Minecraft mc = Minecraft.getInstance();
        return mc.level == null ? 0 : (mc.level.getGameTime() - u.debut + partiel) / 20.0;
    }

    private static boolean passe(double avant, double t, double moment) {
        return avant < moment && t >= moment;
    }

    private static double al(Ulti u, double a, double b) {
        return a + (b - a) * u.hasard.nextDouble();
    }

    // ================================================================ chaque tick : les effets

    private static void tick() {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel monde = mc.level;
        if (monde == null) {
            ULTIS.clear();
            return;
        }
        for (Iterator<Ulti> it = ULTIS.values().iterator(); it.hasNext(); ) {
            Ulti u = it.next();
            double t = temps(u, 0), avant = u.avant;
            u.avant = t;
            if (!u.arrete) {
                evenements(monde, u, avant, t);
                placerMarque(mc, u, t);
            }
            physique(monde, u);
            if ((t > SerpentGeant.T_FIN + 4 || u.arrete) && u.debris.isEmpty() && u.bouffees.isEmpty() || t > SerpentGeant.T_FIN + 20) {
                it.remove();
            }
        }
    }

    private static void evenements(ClientLevel monde, Ulti u, double avant, double t) {
        SerpentGeant.Trajet tr = u.tr;
        Vec3 o = tr.origine;
        if (passe(avant, t, SerpentGeant.T_POSE)) {
            Vec3 m = o.add(tr.mx(0, 1), 0.2, tr.mz(0, 1));
            for (int i = 0; i < 26; i++) {
                double a = al(u, 0, Math.PI * 2);
                bouffee(u, m.x + Math.cos(a) * 0.8, m.y, m.z + Math.sin(a) * 0.8, Math.cos(a) * al(u, 5, 9), al(u, 0.5, 2), Math.sin(a) * al(u, 5, 9),
                        al(u, 0.8, 1.4), 0.8F, 2.6F, 0.5F, POUSSIERE, 3, 0);
            }
            onde(u, m, 9, 0.5F, 0.85F, 0.3F, 1F);
        }
        if (passe(avant, t, SerpentGeant.T_POUF)) {
            Vec3 c = o.add(tr.mx(0, -6), 12, tr.mz(0, -6));
            for (int i = 0; i < 700; i++) {
                double a = al(u, 0, Math.PI * 2), b = Math.acos(al(u, -0.3, 1)), r = Math.sqrt(al(u, 0, 1));
                double dx = Math.sin(b) * Math.cos(a), dy = Math.cos(b), dz = Math.sin(b) * Math.sin(a), v = al(u, 12, 34);
                float g = (float) al(u, 0.82, 1);
                bouffee(u, c.x + dx * 16 * r, c.y + dy * 12 * r, c.z + dz * 18 * r, dx * v, dy * v, dz * v, al(u, 0.8, 1.6), (float) al(u, 5, 9),
                        (float) al(u, 12, 19), (float) al(u, 0.7, 0.9), new float[]{FUMEE[0] * g, FUMEE[1] * g, FUMEE[2] * g}, 2.6F, (float) al(u, 0.5, 2));
            }
            Vec3 sol = o.add(tr.mx(0, -6), 0.5, tr.mz(0, -6));
            for (int i = 0; i < 200; i++) {
                double a = al(u, 0, Math.PI * 2), r = al(u, 8, 20);
                bouffee(u, sol.x + Math.cos(a) * r, sol.y, sol.z + Math.sin(a) * r, Math.cos(a) * al(u, 14, 30), al(u, 0, 3), Math.sin(a) * al(u, 14, 30),
                        al(u, 0.9, 1.8), 4, 10, 0.6F, FUMEE, 2.2F, 0);
            }
            // une couronne de blocs qui sautent autour
            for (int i = 0; i < 150; i++) {
                double a = al(u, 0, Math.PI * 2), r = al(u, 9, 22);
                copie(monde, u, sol.x + Math.cos(a) * r, sol.z + Math.sin(a) * r, 0, Math.cos(a) * al(u, 6, 18), al(u, 10, 26), Math.sin(a) * al(u, 6, 18));
            }
            onde(u, sol, 48, 0.9F, 1F, 0.9F, 1.1F);
            onde(u, sol.add(0, 0.05, 0), 30, 0.7F, 1F, 0.35F, 1.9F);
        }
        double[] K = new double[15];
        tr.tete(Math.min(t, SerpentGeant.T_DISPARAIT), K);
        // la charge : sous la tête, les blocs du dessus volent (des copies), la poussière monte
        if (t > SerpentGeant.T_CHARGE && t < SerpentGeant.T_IMPACT + 0.05) {
            double ang = SerpentGeant.angleMachoire(K[12]);
            for (double x = -1; x <= SerpentGeant.LH; x += 0.8) {
                double bas = -SerpentGeant.hMach(x) - Math.max(0, x - SerpentGeant.CHARN) * Math.sin(-ang) * 0.9;
                double cx = K[0] + K[3] * x, cy = K[1] + K[4] * x + bas * K[7], cz = K[2] + K[5] * x;
                double hw = SerpentGeant.largeur(x) + 0.8;
                for (double l = -hw - 2.5; l <= hw + 2.5; l += 0.8) {
                    double wx = o.x + cx + K[9] * l, wz = o.z + cz + K[11] * l;
                    int bx = Mth.floor(wx), bz = Mth.floor(wz);
                    long cle = (long) bx << 32 | bz & 0xffffffffL;
                    if (u.souffles.contains(cle)) {
                        continue;
                    }
                    int haut = monde.getHeight(Heightmap.Types.MOTION_BLOCKING, bx, bz) - 1;
                    if (o.y + cy > haut + 2.6) {
                        continue;
                    }
                    u.souffles.add(cle);
                    double cote = Math.signum(l) == 0 ? 1 : Math.signum(l);
                    if (Math.abs(l) <= hw) {
                        int n = 1 + u.hasard.nextInt(3);
                        for (int k = 0; k < n; k++) {
                            if (u.hasard.nextDouble() < 0.7) {
                                copie(monde, u, wx, wz, -k, K[9] * cote * al(u, 4, 15) + K[3] * K[14] * al(u, 0.25, 0.75), al(u, 9, 24),
                                        K[11] * cote * al(u, 4, 15) + K[5] * K[14] * al(u, 0.25, 0.75));
                            }
                        }
                        if (u.hasard.nextDouble() < 0.35) {
                            bouffee(u, wx, haut + 1.5, wz, K[9] * cote * al(u, 3, 8), al(u, 2, 6), K[11] * cote * al(u, 3, 8) + K[5] * K[14] * 0.2,
                                    al(u, 1.0, 2.0), (float) al(u, 2, 3), (float) al(u, 6, 10), 0.4F, POUSSIERE, 1.6F, 0.8F);
                        }
                    } else if (u.hasard.nextDouble() < 0.25) {
                        copie(monde, u, wx, wz, 0, K[9] * cote * al(u, 3, 8), al(u, 6, 14), K[11] * cote * al(u, 3, 8));
                    }
                }
            }
            if (K[14] > 5) {
                for (int k = 0; k < 4; k++) {
                    double s = u.hasard.nextBoolean() ? -1 : 1, a = al(u, 0, 12), lat = al(u, 4, 9);
                    double bx = o.x + K[0] + K[3] * a + K[9] * s * lat, bz = o.z + K[2] + K[5] * a + K[11] * s * lat;
                    double by = monde.getHeight(Heightmap.Types.MOTION_BLOCKING, Mth.floor(bx), Mth.floor(bz)) + al(u, 0.5, 3);
                    float g = (float) al(u, 0.85, 1.1);
                    bouffee(u, bx, by, bz, K[9] * s * al(u, 3, 9), al(u, 2, 6), K[11] * s * al(u, 3, 9), al(u, 1.1, 2.1), (float) al(u, 3, 5),
                            (float) al(u, 8, 13), 0.45F, new float[]{POUSSIERE[0] * g, POUSSIERE[1] * g, POUSSIERE[2] * g}, 1.4F, 1);
                }
            }
        }
        // l'impact : une gerbe de blocs et de poussière autour du museau
        if (passe(avant, t, SerpentGeant.T_IMPACT)) {
            double[] b = SerpentGeant.Trajet.dansTete(K, SerpentGeant.LH, 0, 0, false);
            double cx = o.x + b[0] - K[3] * 2, cz = o.z + b[2] - K[5] * 2;
            for (int dx = -10; dx <= 10; dx++) {
                for (int dz = -10; dz <= 10; dz++) {
                    double r = Math.hypot(dx, dz);
                    if (r > 10) {
                        continue;
                    }
                    int n = (int) Math.round(3 * (1 - (r / 10) * (r / 10))) + 1;
                    double nx = r < 0.1 ? K[3] : dx / r, nz = r < 0.1 ? K[5] : dz / r;
                    for (int k = 0; k < n; k++) {
                        if (u.hasard.nextDouble() < 0.7) {
                            copie(monde, u, cx + dx, cz + dz, -k, nx * al(u, 4, 15), al(u, 12, 31), nz * al(u, 4, 15));
                        }
                    }
                }
            }
            for (int i = 0; i < 220; i++) {
                double a = al(u, 0, Math.PI * 2), r = al(u, 0, 9);
                double y = monde.getHeight(Heightmap.Types.MOTION_BLOCKING, Mth.floor(cx), Mth.floor(cz)) + al(u, 0.5, 4);
                float g = (float) al(u, 0.9, 1.25);
                bouffee(u, cx + Math.cos(a) * r, y, cz + Math.sin(a) * r, Math.cos(a) * al(u, 10, 26), al(u, 4, 16), Math.sin(a) * al(u, 10, 26),
                        al(u, 1.3, 2.4), (float) al(u, 4, 7), (float) al(u, 11, 17), 0.65F, new float[]{POUSSIERE[0] * g, POUSSIERE[1] * g, POUSSIERE[2] * g}, 2.2F, 1.2F);
            }
            onde(u, new Vec3(cx, monde.getHeight(Heightmap.Types.MOTION_BLOCKING, Mth.floor(cx), Mth.floor(cz)) + 0.1, cz), 40, 0.8F, 1F, 0.85F, 0.7F);
        }
        if (passe(avant, t, SerpentGeant.T_DISPARAIT)) {
            pouf(u, t);
        }
    }

    /** Le serpent disparaît : des nuages tout le long de son corps. */
    private static void pouf(Ulti u, double t) {
        double[] K = new double[15], f = new double[9], dep = new double[3];
        u.tr.tete(Math.min(t, SerpentGeant.T_DISPARAIT), K);
        Vec3 o = u.tr.origine;
        for (double s = -2; s < SerpentGeant.LB + SerpentGeant.LH; s += 0.9) {
            double x, y, z, R;
            if (s < 0) {
                x = K[0] - K[3] * s * 6;
                y = K[1] - K[4] * s * 6;
                z = K[2] - K[5] * s * 6;
                R = 5;
            } else {
                u.tr.chemin(K[13] - s, f);
                x = u.tr.mx(f[0], f[2]);
                y = f[1];
                z = u.tr.mz(f[0], f[2]);
                R = SerpentGeant.rayon(s);
            }
            for (int k = 0; k < 2; k++) {
                float g = (float) al(u, 0.85, 1);
                bouffee(u, o.x + x + al(u, -R, R), o.y + y + al(u, -R * 0.5, R), o.z + z + al(u, -R, R), al(u, -6, 6), al(u, 2, 10), al(u, -6, 6),
                        al(u, 1.0, 1.9), (float) (R * 1.2), (float) (R * 2.4 + 3), 0.75F, new float[]{FUMEE[0] * g, FUMEE[1] * g, FUMEE[2] * g}, 2.4F, 1);
            }
        }
    }

    private static final float[] FUMEE = {0.93F, 0.91F, 0.94F}, POUSSIERE = {0.59F, 0.49F, 0.38F};

    private static void bouffee(Ulti u, double x, double y, double z, double vx, double vy, double vz, double vie, float t0, float t1, float a0, float[] c,
            float freine, float monte) {
        if (u.bouffees.size() >= MAX_BOUFFEES) {
            u.bouffees.remove(0);
        }
        Bouffee b = new Bouffee();
        b.x = b.xo = x;
        b.y = b.yo = y;
        b.z = b.zo = z;
        b.vx = vx;
        b.vy = vy;
        b.vz = vz;
        b.vie = (float) vie;
        b.t0 = t0;
        b.t1 = t1;
        b.a0 = a0;
        b.r = c[0];
        b.g = c[1];
        b.b = c[2];
        b.freine = freine;
        b.monte = monte;
        u.bouffees.add(b);
    }

    /** Une copie du bloc du dessus de la colonne (x, z), prof blocs plus bas, qui s'envole à la vitesse v (blocs/s). */
    private static void copie(ClientLevel monde, Ulti u, double x, double z, int prof, double vx, double vy, double vz) {
        int bx = Mth.floor(x), bz = Mth.floor(z);
        int haut = monde.getHeight(Heightmap.Types.MOTION_BLOCKING, bx, bz) - 1 + prof;
        BlockState etat = monde.getBlockState(new BlockPos(bx, haut, bz));
        if (etat.isAir() || etat.getRenderShape() != RenderShape.MODEL) {
            return;
        }
        if (u.debris.size() >= MAX_DEBRIS) {
            u.debris.remove(0);
        }
        Debris d = new Debris();
        d.etat = etat;
        d.x = d.xo = bx + 0.5 + al(u, -0.3, 0.3);
        d.y = d.yo = haut + 0.5;
        d.z = d.zo = bz + 0.5 + al(u, -0.3, 0.3);
        d.vx = vx / 20;
        d.vy = vy / 20;
        d.vz = vz / 20;
        d.rx = (float) al(u, 0, 6);
        d.ry = (float) al(u, 0, 6);
        d.rz = (float) al(u, 0, 6);
        d.wx = (float) al(u, -0.6, 0.6);
        d.wy = (float) al(u, -0.6, 0.6);
        d.wz = (float) al(u, -0.6, 0.6);
        d.taille = u.hasard.nextDouble() < 0.15 ? (float) al(u, 1.1, 1.4) : (float) al(u, 0.55, 1);
        u.debris.add(d);
    }

    private static void onde(Ulti u, Vec3 p, float rmax, float duree, float r, float g, float b) {
        Minecraft mc = Minecraft.getInstance();
        u.ondes.add(new Onde(p.x, p.y, p.z, rmax, duree, r, g, b, mc.level == null ? 0 : mc.level.getGameTime()));
    }

    /** Les copies volent, rebondissent, se posent puis rapetissent et disparaissent ; la fumée s'étale et s'efface. */
    private static void physique(ClientLevel monde, Ulti u) {
        float dt = 0.05F;
        for (Iterator<Debris> it = u.debris.iterator(); it.hasNext(); ) {
            Debris d = it.next();
            d.xo = d.x;
            d.yo = d.y;
            d.zo = d.z;
            d.rxo = d.rx;
            d.ryo = d.ry;
            d.rzo = d.rz;
            d.age++;
            if (d.repos >= 0) {
                if (++d.repos > 52) {
                    it.remove();
                }
                continue;
            }
            if (d.age > 200) {
                it.remove();
                continue;
            }
            d.vy -= 0.08;
            d.x += d.vx;
            d.y += d.vy;
            d.z += d.vz;
            d.rx += d.wx;
            d.ry += d.wy;
            d.rz += d.wz;
            BlockPos dessous = BlockPos.containing(d.x, d.y - d.taille / 2, d.z);
            if (d.vy < 0 && !monde.getBlockState(dessous).getCollisionShape(monde, dessous).isEmpty()) {
                d.y = dessous.getY() + 1 + d.taille / 2;
                d.vy = -d.vy * 0.28;
                d.vx *= 0.55;
                d.vz *= 0.55;
                d.wx *= 0.5;
                d.wy *= 0.5;
                d.wz *= 0.5;
                if (Math.abs(d.vy) < 0.11 && Math.hypot(d.vx, d.vz) < 0.08) {
                    d.repos = 0;
                    d.rx = Math.round(d.rx / (Mth.PI / 2)) * (Mth.PI / 2);
                    d.rz = Math.round(d.rz / (Mth.PI / 2)) * (Mth.PI / 2);
                }
            }
        }
        for (Iterator<Bouffee> it = u.bouffees.iterator(); it.hasNext(); ) {
            Bouffee b = it.next();
            b.xo = b.x;
            b.yo = b.y;
            b.zo = b.z;
            b.age += dt;
            if (b.age > b.vie) {
                it.remove();
                continue;
            }
            double f = Math.exp(-b.freine * dt);
            b.vx *= f;
            b.vz *= f;
            b.vy = b.vy * f + b.monte * dt;
            b.x += b.vx * dt;
            b.y += b.vy * dt;
            b.z += b.vz * dt;
        }
        u.ondes.removeIf(o -> monde.getGameTime() - o.debut() > o.duree() * 20 + 2);
    }

    // ================================================================ le marqué sur la tête

    /** Tient le marqué debout sur la tête du serpent (chez lui et chez ceux qui le voient), puis le laisse retomber. */
    private static void placerMarque(Minecraft mc, Ulti u, double t) {
        Entity e = mc.level.getPlayerByUUID(u.joueur);
        if (e == null) {
            return;
        }
        if (t >= SerpentGeant.T_POUF + 0.12 && t < SerpentGeant.T_DISPARAIT) {
            Vec3 p = u.tr.siege(t), avant = u.tr.siege(t - 0.05);
            e.setPos(p.x, p.y, p.z);
            e.xo = e.xOld = avant.x;
            e.yo = e.yOld = avant.y;
            e.zo = e.zOld = avant.z;
            e.setDeltaMovement(Vec3.ZERO);
            e.fallDistance = 0;
        }
        // il saute du serpent quand il disparaît
        if (e == mc.player && passe(t - 0.05, t, SerpentGeant.T_DISPARAIT)) {
            double r = u.tr.lacet * Mth.DEG_TO_RAD;
            e.setDeltaMovement(-Math.sin(r) * 0.2, 0.45, Math.cos(r) * 0.2);
        }
    }

    /** Sa pose : bras levés pendant le saut, mains au sol pour le sceau, bras écartés pendant la charge. */
    public static void poser(PlayerModel<?> m, Player joueur) {
        Ulti u = ULTIS.get(joueur.getUUID());
        Minecraft mc = Minecraft.getInstance();
        if (u == null || u.arrete || mc.level == null) {
            return;
        }
        double t = temps(u, mc.getTimer().getGameTimeDeltaPartialTick(false));
        if (t > SerpentGeant.T_SAUT && t < SerpentGeant.T_POSE - 0.1) {
            m.rightArm.xRot = -2.7F;
            m.leftArm.xRot = -2.7F;
            m.rightArm.zRot = 0.3F;
            m.leftArm.zRot = -0.3F;
            m.rightLeg.xRot = 0.45F;
            m.leftLeg.xRot = -0.9F;
        } else if (t >= SerpentGeant.T_POSE - 0.1 && t < SerpentGeant.T_POUF + 0.3) {
            // accroupi, les deux mains plaquées au sol devant lui
            m.body.xRot = 0.6F;
            m.body.y = 3.2F;
            m.head.y = 4.2F;
            m.head.xRot = -0.4F;
            m.rightArm.y = m.leftArm.y = 5.2F;
            m.rightArm.xRot = m.leftArm.xRot = -0.55F;
            m.rightArm.zRot = 0.12F;
            m.leftArm.zRot = -0.12F;
            m.rightLeg.z = m.leftLeg.z = 4.0F;
            m.rightLeg.y = m.leftLeg.y = 12.2F;
            m.rightLeg.xRot = -0.3F;
            m.leftLeg.xRot = 0.2F;
        } else if (t >= SerpentGeant.T_ELAN && t < SerpentGeant.T_DISPARAIT) {
            m.body.xRot = 0.3F;
            m.rightArm.zRot = 1.1F;
            m.leftArm.zRot = -1.1F;
            m.rightArm.xRot = 0.4F;
            m.leftArm.xRot = -0.6F;
            m.rightLeg.xRot = 0.35F;
            m.leftLeg.xRot = -0.35F;
        } else {
            return;
        }
        m.hat.copyFrom(m.head);
        m.jacket.copyFrom(m.body);
        m.rightSleeve.copyFrom(m.rightArm);
        m.leftSleeve.copyFrom(m.leftArm);
        m.rightPants.copyFrom(m.rightLeg);
        m.leftPants.copyFrom(m.leftLeg);
    }

    // ================================================================ le dessin

    private static void dessiner(RenderLevelStageEvent event) {
        Minecraft mc = Minecraft.getInstance();
        if (ULTIS.isEmpty() || mc.level == null) {
            return;
        }
        float partiel = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        Vec3 cam = event.getCamera().getPosition();
        if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_ENTITIES) {
            for (Ulti u : ULTIS.values()) {
                double t = temps(u, partiel);
                if (!u.arrete && t >= SerpentGeant.T_POUF + 0.12 && t < SerpentGeant.T_DISPARAIT + 0.08 && charger()) {
                    serpent(event, u, t, cam);
                }
            }
            MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
            mc.gameRenderer.lightTexture().turnOnLightLayer();
            PoseStack ps = event.getPoseStack();
            ps.pushPose();
            for (Ulti u : ULTIS.values()) {
                debris(mc, ps, buffers, u, partiel, cam);
                double t = temps(u, partiel);
                if (!u.arrete && t >= SerpentGeant.T_POSE && t < SerpentGeant.T_POUF + 2.5) {
                    sceau(ps, buffers, u, t, cam);
                }
            }
            buffers.endBatch();
            ps.popPose();
            mc.gameRenderer.lightTexture().turnOffLightLayer();
        } else if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_PARTICLES) {
            MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
            mc.gameRenderer.lightTexture().turnOnLightLayer();
            PoseStack ps = event.getPoseStack();
            ps.pushPose();
            for (Ulti u : ULTIS.values()) {
                fumee(ps, buffers, u, partiel, cam, event.getCamera());
            }
            buffers.endBatch();
            ps.popPose();
            mc.gameRenderer.lightTexture().turnOffLightLayer();
            for (Ulti u : ULTIS.values()) {
                ondes(event, u, partiel, cam);
            }
        }
    }

    /** La lumière du monde là où est la tête (le jour, la nuit, une grotte), de 0,3 à 1. */
    private static float lumiere(ClientLevel monde, Vec3 p, float partiel) {
        int l = LevelRenderer.getLightColor(monde, BlockPos.containing(p));
        float bloc = LightTexture.block(l) / 15F, ciel = LightTexture.sky(l) / 15F * monde.getSkyDarken(partiel);
        return 0.3F + 0.7F * Math.max(bloc, ciel);
    }

    private static void serpent(RenderLevelStageEvent event, Ulti u, double t, Vec3 cam) {
        ShaderInstance shader = GameRenderer.getPositionColorShader();
        if (shader == null) {
            return;
        }
        SerpentGeant.Trajet tr = u.tr;
        double[] K = new double[15];
        tr.tete(t, K);
        Vec3 o = tr.origine;
        float rx = (float) (o.x - cam.x), ry = (float) (o.y - cam.y), rz = (float) (o.z - cam.z);
        float lum = lumiere(Minecraft.getInstance().level, o.add(K[0] + K[3] * 8, K[1] + 4, K[2] + K[5] * 8), event.getPartialTick().getGameTimeDeltaPartialTick(false));
        Matrix4f vue = event.getModelViewMatrix(), proj = event.getProjectionMatrix();
        // la tête : le repère (avant, dessus, côté), puis la charnière ; le crâne se relève, la mâchoire s'ouvre
        Matrix4f tete = new Matrix4f(vue).translate(rx + (float) K[0], ry + (float) K[1], rz + (float) K[2])
                .mul(new Matrix4f((float) K[3], (float) K[4], (float) K[5], 0, (float) K[6], (float) K[7], (float) K[8], 0,
                        (float) K[9], (float) K[10], (float) K[11], 0, 0, 0, 0, 1))
                .translate(SerpentGeant.CHARN, 0, 0);
        Matrix4f mCrane = new Matrix4f(tete).rotateZ((float) SerpentGeant.angleCrane(K[12]));
        Matrix4f mMach = new Matrix4f(tete).rotateZ((float) SerpentGeant.angleMachoire(K[12]));
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(true);
        RenderSystem.disableBlend();
        RenderSystem.disableCull();
        RenderSystem.setShaderColor(lum, lum, lum, 1);
        dessinerTampon(tampons[0], mCrane, proj, shader);
        dessinerTampon(tampons[2], mMach, proj, shader);
        double ls = SerpentGeant.langue(t);
        if (ls > 0.02) {
            float sort = (float) (-9 + 11 * ls * Math.min(1, K[12] * 3 + 0.4));
            dessinerTampon(tampons[3], new Matrix4f(mMach).translate(3 - SerpentGeant.CHARN + sort, -0.3F, 0), proj, shader);
        }
        // le corps : chaque anneau de la grille suit le chemin, un peu derrière la tête
        Morceau B = corps;
        int ns = 0;
        for (int f = 0; f < B.n; f++) {
            ns = Math.max(ns, B.ijk[f * 3] + 2);
        }
        float[] P = new float[ns * 3], U = new float[ns * 3], C = new float[ns * 3];
        double[] f = new double[9], dep = new double[3];
        for (int i = 0; i < ns; i++) {
            double s = B.ox + i * B.pas;
            tr.chemin(K[13] - s + 1.2, f);
            SerpentGeant.deplacement(Math.max(s, 0), t, dep);
            double px = f[0] + dep[0], py = f[1] + dep[1], pz = f[2] + dep[2];
            P[i * 3] = rx + (float) tr.mx(px, pz);
            P[i * 3 + 1] = ry + (float) py;
            P[i * 3 + 2] = rz + (float) tr.mz(px, pz);
            float tx = (float) tr.mx(f[3], f[5]), ty = (float) f[4], tz = (float) tr.mz(f[3], f[5]);
            float ux = (float) tr.mx(f[6], f[8]), uy = (float) f[7], uz = (float) tr.mz(f[6], f[8]);
            U[i * 3] = ux;
            U[i * 3 + 1] = uy;
            U[i * 3 + 2] = uz;
            C[i * 3] = ty * uz - tz * uy;
            C[i * 3 + 1] = tz * ux - tx * uz;
            C[i * 3 + 2] = tx * uy - ty * ux;
        }
        Matrix4fStack pile = RenderSystem.getModelViewStack();
        pile.pushMatrix();
        pile.set(vue);
        RenderSystem.applyModelViewMatrix();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        BufferBuilder bb = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        for (int q = 0; q < B.n; q++) {
            int i = B.ijk[q * 3], j = B.ijk[q * 3 + 1], k = B.ijk[q * 3 + 2], d = B.infos[q * 4];
            int r = B.infos[q * 4 + 1] & 255, g = B.infos[q * 4 + 2] & 255, b = B.infos[q * 4 + 3] & 255;
            for (int[] c : COINS[d]) {
                int is = i + c[0];
                float a = B.oy + (j + c[1]) * B.pas, l = B.oz + (k + c[2]) * B.pas;
                bb.addVertex(P[is * 3] + U[is * 3] * a + C[is * 3] * l, P[is * 3 + 1] + U[is * 3 + 1] * a + C[is * 3 + 1] * l,
                        P[is * 3 + 2] + U[is * 3 + 2] * a + C[is * 3 + 2] * l).setColor(r, g, b, 255);
            }
        }
        MeshData donnees = bb.build();
        if (donnees != null) {
            BufferUploader.drawWithShader(donnees);
        }
        pile.popMatrix();
        RenderSystem.applyModelViewMatrix();
        // les yeux brillent, même la nuit
        RenderSystem.setShaderColor(1, 1, 1, 1);
        dessinerTampon(tampons[1], mCrane, proj, shader);
        VertexBuffer.unbind();
        RenderSystem.enableCull();
    }

    private static void dessinerTampon(VertexBuffer vb, Matrix4f m, Matrix4f proj, ShaderInstance shader) {
        if (vb == null || vb.getFormat() == null) {
            return;
        }
        vb.bind();
        vb.drawWithShader(m, proj, shader);
    }

    /** Les copies de blocs qui volent (vraies textures de ce qui était dessous). */
    private static void debris(Minecraft mc, PoseStack ps, MultiBufferSource buffers, Ulti u, float partiel, Vec3 cam) {
        var rendu = mc.getBlockRenderer();
        for (Debris d : u.debris) {
            double x = Mth.lerp(partiel, d.xo, d.x), y = Mth.lerp(partiel, d.yo, d.y), z = Mth.lerp(partiel, d.zo, d.z);
            if ((x - cam.x) * (x - cam.x) + (z - cam.z) * (z - cam.z) > 200 * 200) {
                continue;
            }
            float taille = d.taille * (d.repos < 0 ? 1 : Mth.clamp(1 - (d.repos - 40) / 12F, 0, 1));
            if (taille <= 0.01F) {
                continue;
            }
            ps.pushPose();
            ps.translate(x - cam.x, y - cam.y, z - cam.z);
            ps.mulPose(Axis.XP.rotation(Mth.lerp(partiel, d.rxo, d.rx)));
            ps.mulPose(Axis.YP.rotation(Mth.lerp(partiel, d.ryo, d.ry)));
            ps.mulPose(Axis.ZP.rotation(Mth.lerp(partiel, d.rzo, d.rz)));
            ps.scale(taille, taille, taille);
            ps.translate(-0.5, -0.5, -0.5);
            int l = LevelRenderer.getLightColor(mc.level, BlockPos.containing(x, y, z));
            rendu.renderSingleBlock(d.etat, ps, buffers, l, OverlayTexture.NO_OVERLAY);
            ps.popPose();
        }
    }

    /** Le sceau d'invocation : il s'étale depuis ses mains, puis s'efface après le pouf. */
    private static void sceau(PoseStack ps, MultiBufferSource buffers, Ulti u, double t, Vec3 cam) {
        float taille = (float) (17 * SerpentGeant.lisse((t - SerpentGeant.T_POSE) / 0.9));
        float alpha = (float) (t < SerpentGeant.T_POUF ? 1 : Math.max(0, 1 - (t - SerpentGeant.T_POUF) / 2.5));
        if (taille < 0.05F || alpha <= 0) {
            return;
        }
        SerpentGeant.Trajet tr = u.tr;
        Vec3 c = tr.origine.add(tr.mx(0, 1), 0.03, tr.mz(0, 1));
        ps.pushPose();
        ps.translate(c.x - cam.x, c.y - cam.y, c.z - cam.z);
        ps.mulPose(Axis.YP.rotation(-tr.lacet * Mth.DEG_TO_RAD));
        PoseStack.Pose pose = ps.last();
        VertexConsumer v = buffers.getBuffer(RenderType.entityTranslucent(SCEAU));
        int a = (int) (alpha * 255);
        float[][] coins = {{-taille, -taille, 0, 0}, {-taille, taille, 0, 1}, {taille, taille, 1, 1}, {taille, -taille, 1, 0}};
        for (float[] k : coins) {
            v.addVertex(pose, k[0], 0, k[1]).setColor(255, 255, 255, a).setUv(k[2], k[3]).setOverlay(OverlayTexture.NO_OVERLAY)
                    .setLight(LightTexture.FULL_BRIGHT).setNormal(pose, 0, 1, 0);
        }
        ps.popPose();
    }

    /** La fumée : des bouffées tournées vers la caméra, triées de la plus loin à la plus proche. */
    private static void fumee(PoseStack ps, MultiBufferSource buffers, Ulti u, float partiel, Vec3 cam, Camera camera) {
        if (u.bouffees.isEmpty()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        float jour = 0.35F + 0.65F * mc.level.getSkyDarken(partiel);
        List<Bouffee> l = new ArrayList<>(u.bouffees);
        l.sort((a, b) -> Double.compare(dist2(b, cam), dist2(a, cam)));
        Quaternionf q = camera.rotation();
        Vector3f droite = new Vector3f(1, 0, 0).rotate(q), haut = new Vector3f(0, 1, 0).rotate(q);
        VertexConsumer v = buffers.getBuffer(RenderType.entityTranslucent(BOUFFEE));
        PoseStack.Pose pose = ps.last();
        for (Bouffee b : l) {
            float k = Math.min(1, (b.age + partiel * 0.05F) / b.vie);
            float taille = Mth.lerp(1 - (1 - k) * (1 - k), b.t0, b.t1) / 2;
            float alpha = b.a0 * Math.min(1, k / 0.07F) * (float) Math.pow(1 - k, 1.3);
            if (alpha < 0.01F) {
                continue;
            }
            float x = (float) (Mth.lerp(partiel, b.xo, b.x) - cam.x), y = (float) (Mth.lerp(partiel, b.yo, b.y) - cam.y), z = (float) (Mth.lerp(partiel, b.zo, b.z) - cam.z);
            int r = (int) (b.r * jour * 255), g = (int) (b.g * jour * 255), bl = (int) (b.b * jour * 255), a = (int) (alpha * 255);
            float[][] coins = {{-1, -1, 0, 1}, {-1, 1, 0, 0}, {1, 1, 1, 0}, {1, -1, 1, 1}};
            for (float[] c : coins) {
                v.addVertex(pose, x + (droite.x * c[0] + haut.x * c[1]) * taille, y + (droite.y * c[0] + haut.y * c[1]) * taille, z + (droite.z * c[0] + haut.z * c[1]) * taille)
                        .setColor(r, g, bl, a).setUv(c[2], c[3]).setOverlay(OverlayTexture.NO_OVERLAY).setLight(LightTexture.FULL_BRIGHT).setNormal(pose, 0, 1, 0);
            }
        }
    }

    private static double dist2(Bouffee b, Vec3 c) {
        return (b.x - c.x) * (b.x - c.x) + (b.y - c.y) * (b.y - c.y) + (b.z - c.z) * (b.z - c.z);
    }

    /** Les ondes de choc : un anneau plat, clair, qui s'élargit et s'efface. */
    private static void ondes(RenderLevelStageEvent event, Ulti u, float partiel, Vec3 cam) {
        if (u.ondes.isEmpty()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        Matrix4fStack pile = RenderSystem.getModelViewStack();
        pile.pushMatrix();
        pile.set(event.getModelViewMatrix());
        RenderSystem.applyModelViewMatrix();
        RenderSystem.enableBlend();
        RenderSystem.blendFunc(com.mojang.blaze3d.platform.GlStateManager.SourceFactor.SRC_ALPHA, com.mojang.blaze3d.platform.GlStateManager.DestFactor.ONE);
        RenderSystem.depthMask(false);
        RenderSystem.disableCull();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        BufferBuilder bb = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        boolean rien = true;
        for (Onde o : u.ondes) {
            float k = (mc.level.getGameTime() - o.debut() + partiel) / 20F / o.duree();
            if (k >= 1 || k < 0) {
                continue;
            }
            float r1 = 1 + o.rmax() * (1 - (1 - k) * (1 - k) * (1 - k)), r0 = r1 * 0.8F, a = (1 - k) * 0.9F;
            float x = (float) (o.x() - cam.x), y = (float) (o.y() - cam.y), z = (float) (o.z() - cam.z);
            for (int s = 0; s < 64; s++) {
                float a0 = s * Mth.TWO_PI / 64, a1 = (s + 1) * Mth.TWO_PI / 64;
                bb.addVertex(x + Mth.cos(a0) * r0, y, z + Mth.sin(a0) * r0).setColor(o.r(), o.g(), o.b(), 0);
                bb.addVertex(x + Mth.cos(a0) * r1, y, z + Mth.sin(a0) * r1).setColor(o.r(), o.g(), o.b(), a);
                bb.addVertex(x + Mth.cos(a1) * r1, y, z + Mth.sin(a1) * r1).setColor(o.r(), o.g(), o.b(), a);
                bb.addVertex(x + Mth.cos(a1) * r0, y, z + Mth.sin(a1) * r0).setColor(o.r(), o.g(), o.b(), 0);
                rien = false;
            }
        }
        MeshData donnees = bb.build();
        if (donnees != null && !rien) {
            BufferUploader.drawWithShader(donnees);
        } else if (donnees != null) {
            donnees.close();
        }
        RenderSystem.enableCull();
        RenderSystem.depthMask(true);
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableBlend();
        pile.popMatrix();
        RenderSystem.applyModelViewMatrix();
    }

    /** La caméra tremble quand la tête charge près de nous, et fort à l'impact et au pouf. */
    private static void secousse(ViewportEvent.ComputeCameraAngles event) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer moi = mc.player;
        if (moi == null || ULTIS.isEmpty()) {
            return;
        }
        float partiel = (float) event.getPartialTick();
        Vec3 cam = event.getCamera().getPosition();
        double force = 0;
        for (Ulti u : ULTIS.values()) {
            if (u.arrete) {
                continue;
            }
            double t = temps(u, partiel);
            double[] K = new double[15];
            u.tr.tete(Math.min(t, SerpentGeant.T_DISPARAIT), K);
            double dist = cam.distanceTo(u.tr.origine.add(K[0], K[1], K[2]));
            double pres = Mth.clamp(1 - dist / 70, 0, 1);
            double sec = t > SerpentGeant.T_CHARGE && t < SerpentGeant.T_IMPACT ? 0.9 : 0;
            double choc = Math.max(0, 1 - Math.abs(t - SerpentGeant.T_IMPACT) / 0.5) * 3 + Math.max(0, 1 - Math.abs(t - SerpentGeant.T_POUF) / 0.4) * 1.8;
            force = Math.max(force, (sec + choc) * pres);
        }
        if (force <= 0) {
            return;
        }
        double tt = (mc.level.getGameTime() + partiel) / 20.0;
        event.setYaw(event.getYaw() + (float) (Math.sin(tt * 71) * force));
        event.setPitch(event.getPitch() + (float) (Math.sin(tt * 53 + 1) * force));
        event.setRoll(event.getRoll() + (float) (Math.sin(tt * 61 + 2) * force * 0.6));
    }

    @EventBusSubscriber(modid = Cabane.MODID, value = Dist.CLIENT)
    public static final class Evenements {
        @SubscribeEvent
        public static void dessin(RenderLevelStageEvent event) {
            dessiner(event);
        }

        @SubscribeEvent
        public static void tick(ClientTickEvent.Post event) {
            ClientSerpentGeant.tick();
        }

        @SubscribeEvent
        public static void angles(ViewportEvent.ComputeCameraAngles event) {
            secousse(event);
        }

        private Evenements() {
        }
    }

    private ClientSerpentGeant() {
    }
}
