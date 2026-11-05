package fr.cabane.client.pouvoir;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import fr.cabane.Cabane;
import fr.cabane.pouvoir.Marque3;
import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.joml.Vector3f;

/**
 * Le niveau 3 de la marque, côté client ({@link Marque3}) : son aile gauche se fissure, puis éclate en sept serpents en
 * cubes, validés dans l'aperçu docs/marque3/apercu (même calculs que main-voxel.mjs et ailes.html). Un serpent suit une
 * courbe de Bézier (dans le repère de la main) : des anneaux de cubes enfilés depuis la tête vers le dos (ils
 * s'allongent sans s'étirer), la tête au bout. Au repos ils ondulent à la place de l'aile ; le clic droit les envoie
 * mordre loin devant ; accroupi + clic droit, ils ne font plus qu'un, énorme, qui avale ce qui est en face.
 * <p>
 * Les morceaux viennent de assets/cabane/marque/serpent.bin et fissures.bin (docs/marque3/apercu/construire.mjs).
 */
public final class ClientMarque3 {
    private static final ResourceLocation SERPENT = Cabane.id("marque/serpent.bin");
    private static final ResourceLocation FISSURES = Cabane.id("marque/fissures.bin");
    private static final ResourceLocation BLANC = Cabane.id("textures/marque/blanc.png");

    // ================================================================ les morceaux

    /** Des faces en cubes : par face, 4 coins (x, y, z), 4 couleurs (r, g, b) et une normale. */
    private record Faces(float[] coins, byte[] couleurs, float[] normales, int n) {
        static Faces lire(DataInputStream flux) throws IOException {
            int n = flux.readInt();
            float[] c = new float[n * 12], nor = new float[n * 3];
            byte[] col = new byte[n * 12];
            for (int f = 0; f < n; f++) {
                for (int k = 0; k < 12; k++) {
                    c[f * 12 + k] = flux.readFloat();
                }
                flux.readFully(col, f * 12, 12);
                for (int k = 0; k < 3; k++) {
                    nor[f * 3 + k] = flux.readFloat();
                }
            }
            return new Faces(c, col, nor, n);
        }
    }

    private static Faces anneau, tete;
    private static float longAnneau;
    /** Les x distincts des coins de l'anneau (un repère par x suffit), et l'indice de chaque coin dans cette liste. */
    private static float[] xs;
    private static int[] xi;
    /** Les poses « doigt » : 7 serpents × 4 points × (x, y, z). */
    private static final float[][][] DOIGTS = new float[7][4][3];
    private static float[] fissures;
    private static float[] fissuresLoin;
    private static boolean essaye, pret;

    private static boolean charger() {
        if (!essaye) {
            essaye = true;
            var rm = Minecraft.getInstance().getResourceManager();
            try (var flux = new DataInputStream(new BufferedInputStream(rm.open(SERPENT)))) {
                longAnneau = flux.readFloat();
                anneau = Faces.lire(flux);
                tete = Faces.lire(flux);
                for (float[][] pose : DOIGTS) {
                    for (float[] p : pose) {
                        for (int k = 0; k < 3; k++) {
                            p[k] = flux.readFloat();
                        }
                    }
                }
                TreeSet<Integer> distincts = new TreeSet<>();
                for (int v = 0; v < anneau.n * 4; v++) {
                    distincts.add(Math.round(anneau.coins[v * 3] * 1000));
                }
                List<Integer> liste = new ArrayList<>(distincts);
                xs = new float[liste.size()];
                for (int i = 0; i < xs.length; i++) {
                    xs[i] = liste.get(i) / 1000F;
                }
                xi = new int[anneau.n * 4];
                for (int v = 0; v < xi.length; v++) {
                    xi[v] = liste.indexOf(Math.round(anneau.coins[v * 3] * 1000));
                }
            } catch (IOException | RuntimeException e) {
                Cabane.LOGGER.warn("Marque maudite : serpents illisibles", e);
                return false;
            }
            try (var flux = new DataInputStream(new BufferedInputStream(rm.open(FISSURES)))) {
                int n = flux.readInt();
                fissures = new float[n * 12];
                fissuresLoin = new float[n];
                for (int f = 0; f < n; f++) {
                    for (int k = 0; k < 12; k++) {
                        fissures[f * 12 + k] = flux.readFloat();
                    }
                    fissuresLoin[f] = flux.readFloat();
                }
            } catch (IOException | RuntimeException e) {
                Cabane.LOGGER.warn("Marque maudite : fissures illisibles", e);
                fissures = new float[0];
                fissuresLoin = new float[0];
            }
            pret = true;
            Cabane.LOGGER.info("Marque maudite : serpents de {} + {} faces, {} fissures", anneau.n, tete.n, fissuresLoin.length);
        }
        return pret;
    }

    // ================================================================ les courbes

    private static final int NC = 40, MAX_ANNEAUX = 64;

    /** Une courbe échantillonnée : ses points, ses tangentes, le dos (le repère tourne le long), les longueurs cumulées. */
    private static final class Courbe {
        final float[][] p = new float[NC + 1][3], t = new float[NC + 1][3], u = new float[NC + 1][3];
        final float[] cum = new float[NC + 1];
        float longueur;
    }

    private static float[] norme(float[] v) {
        float l = Mth.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
        return l < 1e-6F ? v : new float[]{v[0] / l, v[1] / l, v[2] / l};
    }

    private static float[] croix(float[] a, float[] b) {
        return new float[]{a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]};
    }

    private static float scal(float[] a, float[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }

    /** v sans sa part le long de t, normé (null s'il ne reste rien). */
    private static float[] projeter(float[] v, float[] t) {
        float d = scal(v, t);
        float[] p = {v[0] - t[0] * d, v[1] - t[1] * d, v[2] - t[2] * d};
        return p[0] * p[0] + p[1] * p[1] + p[2] * p[2] < 1e-8F ? null : norme(p);
    }

    private static float[] tourner(float[] v, float[] n, float a) {
        float c = Mth.cos(a), s = Mth.sin(a), d = scal(n, v);
        float[] k = croix(n, v);
        return new float[]{v[0] * c + k[0] * s + n[0] * d * (1 - c), v[1] * c + k[1] * s + n[1] * d * (1 - c), v[2] * c + k[2] * s + n[2] * d * (1 - c)};
    }

    /** La courbe de Bézier de points de contrôle P ; le dos part vers l'avant, puis tourne pour que la tête ait le dessus en haut. */
    private static Courbe courbe(float[][] P) {
        Courbe C = new Courbe();
        for (int i = 0; i <= NC; i++) {
            float t = i / (float) NC, m = 1 - t;
            for (int k = 0; k < 3; k++) {
                C.p[i][k] = m * m * m * P[0][k] + 3 * m * m * t * P[1][k] + 3 * m * t * t * P[2][k] + t * t * t * P[3][k];
                C.t[i][k] = 3 * m * m * (P[1][k] - P[0][k]) + 6 * m * t * (P[2][k] - P[1][k]) + 3 * t * t * (P[3][k] - P[2][k]);
            }
            C.t[i] = norme(C.t[i]);
            if (i > 0) {
                float dx = C.p[i][0] - C.p[i - 1][0], dy = C.p[i][1] - C.p[i - 1][1], dz = C.p[i][2] - C.p[i - 1][2];
                C.cum[i] = C.cum[i - 1] + Mth.sqrt(dx * dx + dy * dy + dz * dz);
            }
        }
        C.longueur = C.cum[NC];
        float[] u = projeter(new float[]{0, 0, 1}, C.t[0]);
        if (u == null) {
            u = projeter(new float[]{0, 1, 0}, C.t[0]);
        }
        for (int i = 0; i <= NC; i++) {
            if (i > 0) {
                float[] w = projeter(u, C.t[i]);
                u = w == null ? u : w;
            }
            C.u[i] = u;
        }
        float[] voulu = projeter(new float[]{0, 1, 0}, C.t[NC]);
        if (voulu != null && C.longueur > 1e-3F) {
            float[] fin = C.u[NC];
            float angle = (float) Math.atan2(scal(croix(fin, voulu), C.t[NC]), scal(fin, voulu));
            for (int i = 0; i <= NC; i++) {
                C.u[i] = tourner(C.u[i], C.t[i], angle * C.cum[i] / C.longueur);
            }
        }
        return C;
    }

    /** Le repère de la courbe à la distance a de la racine (au-delà des bouts, dans le prolongement) : p, t, u, côté. */
    private static float[][] reperes(Courbe C, float a) {
        float[] p, t, u;
        if (a <= 0 || C.longueur < 1e-4F) {
            t = C.t[0];
            u = C.u[0];
            p = new float[]{C.p[0][0] + t[0] * a, C.p[0][1] + t[1] * a, C.p[0][2] + t[2] * a};
        } else if (a >= C.longueur) {
            t = C.t[NC];
            u = C.u[NC];
            float d = a - C.longueur;
            p = new float[]{C.p[NC][0] + t[0] * d, C.p[NC][1] + t[1] * d, C.p[NC][2] + t[2] * d};
        } else {
            int lo = 0, hi = NC;
            while (hi - lo > 1) {
                int mi = (lo + hi) >> 1;
                if (C.cum[mi] <= a) {
                    lo = mi;
                } else {
                    hi = mi;
                }
            }
            float k = (a - C.cum[lo]) / Math.max(1e-6F, C.cum[hi] - C.cum[lo]);
            p = new float[3];
            float[] tt = new float[3], uu = new float[3];
            for (int i = 0; i < 3; i++) {
                p[i] = Mth.lerp(k, C.p[lo][i], C.p[hi][i]);
                tt[i] = Mth.lerp(k, C.t[lo][i], C.t[hi][i]);
                uu[i] = Mth.lerp(k, C.u[lo][i], C.u[hi][i]);
            }
            t = norme(tt);
            u = norme(uu);
        }
        return new float[][]{p, t, u, croix(t, u)};
    }

    // ================================================================ dessiner un serpent

    /** La charnière de la mâchoire, dans le repère de la tête : ce qui est sous la bouche et devant s'ouvre vers le bas. */
    private static final float CHARNIERE_X = 2.4F, BOUCHE_Y = -0.45F;

    /**
     * Le serpent le long de la courbe de points de contrôle P (repère de la main). g : son échelle (1 pour les sept, plus
     * pour le gros) ; et : l'échelle de la tête en plus ; gueule : l'ouverture de la mâchoire (radians).
     */
    private static void serpent(VertexConsumer c, PoseStack.Pose pose, float[][] P, float g, float et, float gueule, int lumiere, int recouvre) {
        serpent(c, pose, P, g, et, gueule, lumiere, recouvre, null, 0);
    }

    /** p est-il à moins de rayon de oeil (repère de la main) ? En première personne, le serpent s'arrête là. */
    private static boolean pres(float[] p, float[] oeil, float rayon) {
        if (oeil == null) {
            return false;
        }
        float dx = p[0] - oeil[0], dy = p[1] - oeil[1], dz = p[2] - oeil[2];
        return dx * dx + dy * dy + dz * dz < rayon * rayon;
    }

    private static void serpent(VertexConsumer c, PoseStack.Pose pose, float[][] P, float g, float et, float gueule, int lumiere, int recouvre,
            float[] oeil, float rayon) {
        Courbe C = courbe(P);
        float la = longAnneau * g;
        int n = Math.min(MAX_ANNEAUX, Mth.ceil(C.longueur / la) + 1);
        // la tête, rigide au bout de la courbe
        float[][] f = reperes(C, C.longueur);
        float sTete = g * et, cg = Mth.cos(gueule), sgu = Mth.sin(gueule);
        for (int face = 0; face < (pres(f[0], oeil, rayon) ? 0 : tete.n); face++) {
            float nx = tete.normales[face * 3], ny = tete.normales[face * 3 + 1], nz = tete.normales[face * 3 + 2];
            for (int q = 0; q < 4; q++) {
                int i = face * 12 + q * 3;
                float x = tete.coins[i], y = tete.coins[i + 1], z = tete.coins[i + 2];
                if (gueule > 0.01F && y < BOUCHE_Y && x > CHARNIERE_X) {
                    float dx = x - CHARNIERE_X, dy = y - BOUCHE_Y;
                    x = CHARNIERE_X + dx * cg + dy * sgu;
                    y = BOUCHE_Y - dx * sgu + dy * cg;
                }
                x *= sTete;
                y *= sTete;
                z *= sTete;
                sommet(c, pose, f, x, y, z, nx, ny, nz, tete.couleurs, i, lumiere, recouvre);
            }
        }
        // les anneaux, de la tête vers la racine (le dernier rentre dans le dos)
        float[][][] r = new float[xs.length][][];
        for (int a = 0; a < n; a++) {
            float debut = C.longueur - (a + 1) * la;
            // en première personne : d'un seul tenant depuis la tête ; on s'arrête au premier anneau qui arrive près des
            // yeux (le reste, vers le dos, est sous la caméra et ne se voit pas), au lieu d'y faire des trous
            if (oeil != null && pres(reperes(C, debut + la / 2)[0], oeil, rayon)) {
                break;
            }
            for (int k = 0; k < xs.length; k++) {
                r[k] = reperes(C, debut + xs[k] * g);
            }
            for (int face = 0; face < anneau.n; face++) {
                float nx = anneau.normales[face * 3], ny = anneau.normales[face * 3 + 1], nz = anneau.normales[face * 3 + 2];
                for (int q = 0; q < 4; q++) {
                    int i = face * 12 + q * 3;
                    float[][] rk = r[xi[face * 4 + q]];
                    // x est déjà dans le repère (la position le long de la courbe) : seuls y et z s'ajoutent
                    sommet(c, pose, rk, 0, anneau.coins[i + 1] * g, anneau.coins[i + 2] * g, nx, ny, nz, anneau.couleurs, i, lumiere, recouvre);
                }
            }
        }
    }

    private static void sommet(VertexConsumer c, PoseStack.Pose pose, float[][] r, float x, float y, float z, float nx, float ny, float nz,
            byte[] couleurs, int i, int lumiere, int recouvre) {
        float[] p = r[0], t = r[1], u = r[2], s = r[3];
        c.addVertex(pose, p[0] + t[0] * x + u[0] * y + s[0] * z, p[1] + t[1] * x + u[1] * y + s[1] * z, p[2] + t[2] * x + u[2] * y + s[2] * z)
                .setColor(couleurs[i] & 255, couleurs[i + 1] & 255, couleurs[i + 2] & 255, 255).setUv(0.5F, 0.5F).setOverlay(recouvre)
                .setLight(lumiere).setNormal(pose, t[0] * nx + u[0] * ny + s[0] * nz, t[1] * nx + u[1] * ny + s[1] * nz, t[2] * nx + u[2] * ny + s[2] * nz);
    }

    // ================================================================ les poses (comme main-voxel.mjs)

    /** Pour chacun des 7 serpents : l'angle (degrés, depuis l'extérieur vers le haut) et la longueur relative. */
    private static final float[][] SERPENTS = {{62, 0.86F}, {42, 0.95F}, {22, 1}, {2, 1.02F}, {-18, 0.98F}, {-38, 0.92F}, {-58, 0.84F}};

    private static float[] d(int i) {
        float f = SERPENTS[i][0] * Mth.DEG_TO_RAD;
        return new float[]{Mth.cos(f), Mth.sin(f), 0};
    }

    private static float[] e(int i) {
        float f = SERPENTS[i][0] * Mth.DEG_TO_RAD;
        return new float[]{-Mth.sin(f), Mth.cos(f), 0};
    }

    /** La racine : serrées les unes contre les autres, là où l'aile s'accroche au dos. */
    private static float[] racine(int i) {
        return new float[]{-2.5F, 1.6F + (3 - i) * 0.55F, i % 2 == 1 ? -0.7F : 0.7F};
    }

    private static float longueur(int i) {
        return 30 * SERPENTS[i][1] + 16;
    }

    /** a + somme des v[k] · k[k]. */
    private static float[] plus(float[] a, float[][] v, float[] k) {
        float[] r = a.clone();
        for (int j = 0; j < v.length; j++) {
            for (int c = 0; c < 3; c++) {
                r[c] += v[j][c] * k[j];
            }
        }
        return r;
    }

    private static final float[] Y = {0, 1, 0}, Z = {0, 0, 1};

    /** Au repos : en éventail à la place de l'aile ; ils ondulent chacun à son rythme, les têtes regardent devant. */
    private static float[][] repos(int i, float t) {
        float[] d = d(i), e = e(i), R = racine(i);
        float L = longueur(i), w = 1.6F * t + i * 0.9F;
        float[] P3 = plus(R, new float[][]{d, e, Z, e, Y, Z},
                new float[]{L, 1.5F, 7, 2.6F * Mth.sin(w - 2.2F), 2 * Mth.sin(1.1F * t + 1.3F * i), Mth.sin(1.4F * t + 0.7F * i)});
        float[] C1 = plus(R, new float[][]{d, e, Z, e}, new float[]{L * 0.33F, 4, -1.5F, 2.2F * Mth.sin(w)});
        float[] regard = norme(plus(new float[3], new float[][]{d, Z, Y}, new float[]{0.55F, 0.75F, 0.25F}));
        float[] C2 = plus(P3, new float[][]{regard, e, Z}, new float[]{-L * 0.35F, 3 * Mth.sin(w - 1.1F), 1.5F * Mth.sin(1.2F * t + i)});
        return new float[][]{R, C1, C2, P3};
    }

    /** La pose « attaque » : le serpent file vers W (repère de la main) et le mord du bout du museau. */
    private static float[][] attaque(int i, float[] W) {
        return attaque(i, W, false);
    }

    /**
     * premiere : vu par ses propres yeux, les serpents plongent d'abord sous la caméra puis remontent vers leurs cibles :
     * on les voit partir d'en bas à gauche de l'écran et filer devant, sans qu'ils cachent la vue.
     */
    private static float[][] attaque(int i, float[] W, boolean premiere) {
        float[] R = racine(i), d = d(i);
        float[] vers = norme(new float[]{W[0] - R[0], W[1] - R[1], W[2] - R[2]});
        float loin = Mth.sqrt((W[0] - R[0]) * (W[0] - R[0]) + (W[1] - R[1]) * (W[1] - R[1]) + (W[2] - R[2]) * (W[2] - R[2]));
        float[] P3 = plus(W, new float[][]{vers}, new float[]{-10});
        if (premiere) {
            return new float[][]{R, plus(R, new float[][]{d, Z, Y}, new float[]{4, 20, -18}), plus(P3, new float[][]{vers, Y}, new float[]{-loin * 0.45F, -6}), P3};
        }
        return new float[][]{R, plus(R, new float[][]{d, Z, Y}, new float[]{8, 10, 4}), plus(P3, new float[][]{vers, Y}, new float[]{-loin * 0.4F, 8}), P3};
    }

    private static float[][] melange(float[][] a, float[][] b, float k) {
        float[][] r = new float[4][3];
        for (int j = 0; j < 4; j++) {
            for (int c = 0; c < 3; c++) {
                r[j][c] = Mth.lerp(k, a[j][c], b[j][c]);
            }
        }
        return r;
    }

    private static float lisse(double x) {
        x = Mth.clamp(x, 0, 1);
        return (float) (x * x * (3 - 2 * x));
    }

    // ================================================================ ce que le serveur annonce

    /** Ce qui arrive aux serpents d'un joueur (System.nanoTime ; -1 : rien). */
    private static final class Serpents {
        long morsure = -1;
        final List<Vec3> cibles = new ArrayList<>();
        int prise = -1;
        long priseDebut = -1;
        long croque = -1;
    }

    private static final Map<UUID, Serpents> SERPENTS_DE = new HashMap<>();
    /** Ceux qui volent avec la marque 3 en ce moment (ClientVent les couche à plat quand ils avancent). */
    private static final java.util.Set<UUID> VOLANTS = new java.util.HashSet<>();

    public static boolean vole(UUID joueur) {
        return VOLANTS.contains(joueur);
    }
    /** Pour soi : quand chaque chose sera rechargée, et la fin du vol en cours. */
    private static long serpentsPrets, avalePret, volPret, volFin = -1;

    private static double secondes(long depuis) {
        return (System.nanoTime() - depuis) / 1e9;
    }

    private static Serpents de(UUID joueur) {
        return SERPENTS_DE.computeIfAbsent(joueur, k -> new Serpents());
    }

    public static void geste(Marque3.Geste3 p) {
        Minecraft mc = Minecraft.getInstance();
        boolean moi = mc.player != null && mc.player.getUUID().equals(p.joueur());
        long maintenant = System.nanoTime();
        Serpents s = de(p.joueur());
        switch (p.geste()) {
            case Marque3.GESTE_MORSURE -> {
                s.morsure = maintenant;
                s.cibles.clear();
                for (Vector3f v : p.points()) {
                    s.cibles.add(new Vec3(v.x, v.y, v.z));
                }
                if (moi) {
                    serpentsPrets = maintenant + p.valeur() * 50_000_000L;
                }
            }
            case Marque3.GESTE_PRISE -> {
                s.prise = p.valeur();
                s.priseDebut = p.valeur() >= 0 ? maintenant : -1;
                s.croque = -1;
                if (moi && p.valeur() >= 0) {
                    avalePret = maintenant + Marque3.RECHARGE_AVALE * 50_000_000L;
                }
            }
            case Marque3.GESTE_CROQUE -> s.croque = maintenant;
            case Marque3.GESTE_JET -> {
                s.prise = -1;
                s.priseDebut = -1;
            }
            case Marque3.GESTE_VOL -> {
                if (p.valeur() == 1) {
                    VOLANTS.add(p.joueur());
                } else {
                    VOLANTS.remove(p.joueur());
                }
                if (moi) {
                    if (p.valeur() == 1) {
                        volFin = maintenant + Marque3.DUREE_VOL * 50_000_000L;
                    } else {
                        volFin = -1;
                        volPret = maintenant + Marque3.RECHARGE_VOL * 50_000_000L;
                    }
                }
            }
            default -> {
            }
        }
    }

    // ================================================================ l'aile gauche au niveau 3

    /** La pose de l'aile gauche (comme ClientMarque2.ailes) : pour ramener un point du monde dans le repère de la main. */
    private static float[] versMain(AbstractClientPlayer joueur, Vec3 w, float partiel, float ouverture) {
        Vec3 pos = joueur.getPosition(partiel);
        float lacet = Mth.rotLerp(partiel, joueur.yBodyRotO, joueur.yBodyRot) * Mth.DEG_TO_RAD;
        double dx = w.x - pos.x, dy = w.y - pos.y, dz = w.z - pos.z;
        // la maquette : x à sa gauche, y en haut depuis ses pieds, z devant lui, en pixels
        float x = (float) ((dx * Mth.cos(lacet) + dz * Mth.sin(lacet)) * 16) - 2.4F;
        float y = (float) (dy * 16) - 21.5F;
        float z = (float) ((-dx * Mth.sin(lacet) + dz * Mth.cos(lacet)) * 16) + 3;
        x /= ClientMarque2.ECHELLE;
        y /= ClientMarque2.ECHELLE;
        z /= ClientMarque2.ECHELLE;
        float c = Mth.cos(ouverture), s = Mth.sin(ouverture);
        float x1 = x * c - z * s, z1 = x * s + z * c;
        float c2 = Mth.cos(0.12F), s2 = Mth.sin(0.12F);
        float y2 = y * c2 - z1 * s2, z2 = y * s2 + z1 * c2 - ClientMarque2.RECUL;
        float c3 = Mth.cos(ClientMarque2.RELEVE), s3 = Mth.sin(ClientMarque2.RELEVE);
        return new float[]{x1 * c3 + y2 * s3, -x1 * s3 + y2 * c3, z2};
    }

    /**
     * L'aile gauche d'un marqué au niveau 3, dans le repère de la main (la pose de l'aile est déjà faite) ; t3 : secondes
     * depuis le passage. D'abord la main ; elle se fissure (de 0,6 à 1,7 s) ; puis les serpents en sortent, couchés dans
     * les doigts, et s'allongent jusqu'à leur place (de 1,7 à 3,2 s).
     */
    static void aileGauche(VertexConsumer c, PoseStack.Pose pose, AbstractClientPlayer joueur, double t3, float ouverture, float partiel,
            int lumiere, int recouvre) {
        if (!charger() || t3 < 1.7) {
            ClientMarque2.main(c, pose, lumiere, recouvre, 1);
            if (pret && t3 >= 0.6) {
                fissures(c, pose, (float) ((t3 - 0.6) / 1.1 * 60), recouvre);
            }
            return;
        }
        float t = (joueur.tickCount + partiel) / 20F;
        float pousse = lisse((t3 - 1.7) / 1.5), et = 0.55F + 0.45F * lisse((t3 - 1.7) / 0.8);
        Serpents s = SERPENTS_DE.get(joueur.getUUID());
        Minecraft mc = Minecraft.getInstance();
        // le gros serpent : les sept n'en font plus qu'un, qui tient sa proie dans sa gueule
        if (s != null && s.prise >= 0 && mc.level != null) {
            Entity proie = mc.level.getEntity(s.prise);
            if (proie != null) {
                gros(c, pose, joueur, s, proie, ouverture, partiel, t, lumiere, recouvre);
                return;
            }
        }
        double ta = s == null || s.morsure < 0 ? -1 : secondes(s.morsure);
        if (ta > 1.6) {
            s.morsure = -1;
            ta = -1;
        }
        for (int i = 0; i < 7; i++) {
            float[][] P = repos(i, t);
            if (pousse < 1) {
                P = melange(DOIGTS[i], P, pousse);
            }
            float k = ta >= 0 && i < s.cibles.size() ? elan(ta, i) : 0;
            if (k > 0) {
                P = melange(P, attaque(i, versMain(joueur, s.cibles.get(i), partiel, ouverture)), k);
            }
            serpent(c, pose, P, 1, et, 0.75F * k, lumiere, recouvre);
        }
    }

    /** Où en est le serpent i d'une morsure lancée il y a ta secondes : 0 au repos, 1 sur sa cible (ils partent l'un après l'autre). */
    private static float elan(double ta, int i) {
        double x = ta - 0.06 * i;
        return x < 0 ? 0 : x < 0.28 ? 1 - (float) Math.pow(1 - x / 0.28, 3) : x < 0.5 ? 1 : 1 - lisse((x - 0.5) / 0.5);
    }

    /**
     * En première personne, le corps du joueur n'est pas dessiné (ni ses ailes) : on dessine quand même, dans le monde,
     * le gros serpent qui tient sa proie, et les serpents qui filent mordre, pour qu'il voie ce qu'il attrape.
     */
    private static void premierePersonne(RenderLevelStageEvent event) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer moi = mc.player;
        if (moi == null || mc.level == null || !mc.options.getCameraType().isFirstPerson() || !ClientMaudit.serpents(moi.getUUID())) {
            return;
        }
        Serpents s = SERPENTS_DE.get(moi.getUUID());
        Entity proie = s == null || s.prise < 0 ? null : mc.level.getEntity(s.prise);
        double ta = s == null || s.morsure < 0 ? -1 : secondes(s.morsure);
        if (proie == null && (ta < 0 || ta > 1.6) || !charger()) {
            return;
        }
        float partiel = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        Vec3 cam = event.getCamera().getPosition(), pos = moi.getPosition(partiel);
        PoseStack ps = event.getPoseStack();
        ps.pushPose();
        // la maquette (x à sa gauche, y en haut depuis ses pieds, z devant lui, en pixels), puis la pose de l'aile gauche
        ps.translate(pos.x - cam.x, pos.y - cam.y, pos.z - cam.z);
        ps.mulPose(Axis.YP.rotation(-Mth.rotLerp(partiel, moi.yBodyRotO, moi.yBodyRot) * Mth.DEG_TO_RAD));
        ps.scale(1 / 16F, 1 / 16F, 1 / 16F);
        float ouverture = 0.3F;
        ps.translate(2.4F, 21.5F, -3F);
        ps.scale(ClientMarque2.ECHELLE, ClientMarque2.ECHELLE, ClientMarque2.ECHELLE);
        ps.mulPose(Axis.YP.rotation(ouverture));
        ps.mulPose(Axis.XP.rotation(-0.12F));
        ps.translate(0, 0, ClientMarque2.RECUL);
        ps.mulPose(Axis.ZP.rotation(ClientMarque2.RELEVE));
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        RenderType type = RenderType.entityCutoutNoCull(BLANC);
        VertexConsumer c = buffers.getBuffer(type);
        int lumiere = mc.getEntityRenderDispatcher().getPackedLightCoords(moi, partiel);
        float t = (moi.tickCount + partiel) / 20F;
        // les serpents s'arrêtent là où ils arrivent à moins de 0,7 bloc de ses yeux : on les voit partir d'en bas de l'écran
        float[] oeil = versMain(moi, cam, partiel, ouverture);
        float rayon = 0.7F * 16 / ClientMarque2.ECHELLE;
        if (proie != null) {
            gros(c, ps.last(), moi, s, proie, ouverture, partiel, t, lumiere, OverlayTexture.NO_OVERLAY, oeil, rayon);
        } else {
            // seulement ceux qui sont partis : au repos, rien ne gêne la vue
            for (int i = 0; i < Math.min(7, s.cibles.size()); i++) {
                float k = elan(ta, i);
                if (k > 0.05F) {
                    float[][] P = melange(repos(i, t), attaque(i, versMain(moi, s.cibles.get(i), partiel, ouverture), true), k);
                    serpent(c, ps.last(), P, 1, 1, 0.75F * k, lumiere, OverlayTexture.NO_OVERLAY, oeil, rayon);
                }
            }
        }
        ps.popPose();
        buffers.endBatch(type);
    }

    /** L'échelle du gros serpent (par rapport aux sept). */
    private static final float GROS = 3.2F;

    /** Le gros serpent : du dos, il passe par-dessus l'épaule et tient la proie dans sa gueule grande ouverte. */
    private static void gros(VertexConsumer c, PoseStack.Pose pose, AbstractClientPlayer joueur, Serpents s, Entity proie, float ouverture,
            float partiel, float t, int lumiere, int recouvre) {
        gros(c, pose, joueur, s, proie, ouverture, partiel, t, lumiere, recouvre, null, 0);
    }

    private static void gros(VertexConsumer c, PoseStack.Pose pose, AbstractClientPlayer joueur, Serpents s, Entity proie, float ouverture,
            float partiel, float t, int lumiere, int recouvre, float[] oeil, float rayon) {
        Vec3 centre = proie.getPosition(partiel).add(0, proie.getBbHeight() / 2, 0);
        float[] W = versMain(joueur, centre, partiel, ouverture);
        float[] R = {-2.5F, 2.2F, 0};
        float[] vers = norme(new float[]{W[0] - R[0], W[1] - R[1], W[2] - R[2]});
        float loin = Mth.sqrt((W[0] - R[0]) * (W[0] - R[0]) + (W[1] - R[1]) * (W[1] - R[1]) + (W[2] - R[2]) * (W[2] - R[2]));
        // la gueule (vers le milieu de la tête) sur la proie
        float[] P3 = plus(W, new float[][]{vers}, new float[]{-6 * GROS});
        float[] C1 = plus(R, new float[][]{new float[]{0.35F, 0.85F, -0.4F}}, new float[]{loin * 0.35F});
        float[] C2 = plus(P3, new float[][]{vers, Y, Z}, new float[]{-loin * 0.35F, loin * 0.15F + 3 * Mth.sin(t * 1.3F), 2 * Mth.sin(t * 1.7F)});
        double age = secondes(s.priseDebut);
        float gueule = age < 0.4 ? 0.95F : 0.6F + 0.08F * Mth.sin(t * 3);
        if (s.croque >= 0) {
            double k = secondes(s.croque);
            if (k < 0.15) {
                gueule = 0.05F;
            } else if (k < 0.4) {
                gueule = Mth.lerp(lisse((k - 0.15) / 0.25), 0.05F, 0.6F);
            }
        }
        serpent(c, pose, new float[][]{R, C1, C2, P3}, GROS, 1, gueule, lumiere, recouvre, oeil, rayon);
    }

    /** Les fissures de la main : noires et violettes, elles palpitent et s'étendent depuis le poignet jusqu'à portee. */
    private static void fissures(VertexConsumer c, PoseStack.Pose pose, float portee, int recouvre) {
        float t = (System.nanoTime() % 1_000_000_000_000L) / 1e9F;
        for (int f = 0; f < fissuresLoin.length; f++) {
            if (fissuresLoin[f] > portee) {
                continue;
            }
            float p = 0.55F + 0.45F * Mth.sin(t * 11 + fissuresLoin[f] * 0.7F);
            int r = (int) Mth.lerp(p, 16, 230), g = (int) Mth.lerp(p, 0, 100), b = (int) Mth.lerp(p, 26, 255);
            int i = f * 12;
            // un peu devant la face de la main, pour ne pas se battre avec elle
            float ax = fissures[i + 6] - fissures[i], ay = fissures[i + 7] - fissures[i + 1], az = fissures[i + 8] - fissures[i + 2];
            float bx = fissures[i + 9] - fissures[i + 3], by = fissures[i + 10] - fissures[i + 4], bz = fissures[i + 11] - fissures[i + 5];
            float[] n = norme(new float[]{ay * bz - az * by, az * bx - ax * bz, ax * by - ay * bx});
            for (int q = 0; q < 4; q++) {
                int j = i + q * 3;
                c.addVertex(pose, fissures[j] + n[0] * 0.04F, fissures[j + 1] + n[1] * 0.04F, fissures[j + 2] + n[2] * 0.04F).setColor(r, g, b, 255)
                        .setUv(0.5F, 0.5F).setOverlay(recouvre).setLight(LightTexture.FULL_BRIGHT).setNormal(pose, n[0], n[1], n[2]);
            }
        }
    }

    // ================================================================ pour le panneau des touches

    static double rechargeSerpents() {
        return Math.max(0, (serpentsPrets - System.nanoTime()) / 1e9);
    }

    static double rechargeAvale() {
        return Math.max(0, (avalePret - System.nanoTime()) / 1e9);
    }

    static double rechargeVol() {
        return Math.max(0, (volPret - System.nanoTime()) / 1e9);
    }

    /** Ce qui lui reste de vol (0 s'il ne vole pas). */
    static double vol() {
        return volFin < 0 ? 0 : Math.max(0, (volFin - System.nanoTime()) / 1e9);
    }

    /** Son gros serpent tient-il quelqu'un ? */
    static boolean tient(UUID joueur) {
        Serpents s = SERPENTS_DE.get(joueur);
        return s != null && s.prise >= 0;
    }

    // ================================================================ les touches

    @EventBusSubscriber(modid = Cabane.MODID, value = Dist.CLIENT)
    public static final class Evenements {
        private static boolean accroupiAvant;
        /** L'appui en cours sur s'accroupir : quand il a commencé, et si un clic droit l'a utilisé (pour avaler). */
        private static long accroupiDebut;
        private static boolean accroupiUtilise;
        private static long dernierRelache;
        private static long dernierClic;
        private static boolean utiliserRelache = true;

        @SubscribeEvent
        public static void dessin(RenderLevelStageEvent event) {
            if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_PARTICLES) {
                premierePersonne(event);
            }
        }

        private static void envoyer(int quoi) {
            PacketDistributor.sendToServer(new Marque3.Action(quoi));
        }

        /**
         * S'accroupir un court instant (sans clic droit) : le bond et le vol. En vol, deux petits appuis rapides : il
         * retombe (maintenu, s'accroupir le fait descendre).
         */
        @SubscribeEvent
        public static void tick(ClientTickEvent.Post event) {
            Minecraft mc = Minecraft.getInstance();
            LocalPlayer moi = mc.player;
            if (moi == null || mc.level == null) {
                SERPENTS_DE.clear();
                VOLANTS.clear();
                return;
            }
            if (!mc.options.keyUse.isDown()) {
                utiliserRelache = true;
            }
            boolean accroupi = mc.options.keyShift.isDown();
            boolean serpents = ClientMaudit.serpents(moi.getUUID());
            long maintenant = System.nanoTime();
            if (accroupi && !accroupiAvant) {
                accroupiDebut = maintenant;
                accroupiUtilise = false;
            }
            if (!accroupi && accroupiAvant && serpents && mc.screen == null && !accroupiUtilise && maintenant - accroupiDebut < 400_000_000L) {
                if (!moi.getAbilities().flying || vol() <= 0) {
                    envoyer(Marque3.VOLER);
                } else if (maintenant - dernierRelache < 450_000_000L && Marque3.DUREE_VOL / 20.0 - vol() > 0.5) {
                    envoyer(Marque3.VOLER);
                }
                dernierRelache = maintenant;
            }
            accroupiAvant = accroupi;
        }

        /**
         * Serpents sortis : le clic droit les envoie mordre (accroupi : le gros serpent avale ; s'il tient quelqu'un : il
         * croque) ; le clic gauche frappe normalement (boosté), sauf s'il tient quelqu'un : il le jette.
         */
        @SubscribeEvent
        public static void clic(InputEvent.InteractionKeyMappingTriggered event) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null || !ClientMaudit.serpents(mc.player.getUUID())) {
                return;
            }
            boolean tient = tient(mc.player.getUUID());
            if (event.isAttack()) {
                if (tient) {
                    event.setCanceled(true);
                    event.setSwingHand(true);
                    envoyer(Marque3.JETER);
                }
                return;
            }
            if (!event.isUseItem()) {
                return;
            }
            event.setCanceled(true);
            event.setSwingHand(false);
            long maintenant = System.nanoTime();
            if (!utiliserRelache || maintenant - dernierClic < 350_000_000L) {
                return;
            }
            utiliserRelache = false;
            dernierClic = maintenant;
            if (mc.options.keyShift.isDown()) {
                accroupiUtilise = true;
                envoyer(Marque3.AVALER);
            } else {
                envoyer(Marque3.SERPENTS);
            }
        }

        private Evenements() {
        }
    }

    private ClientMarque3() {
    }
}
