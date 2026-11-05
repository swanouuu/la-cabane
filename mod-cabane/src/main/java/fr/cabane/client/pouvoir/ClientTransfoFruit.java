package fr.cabane.client.pouvoir;

import com.mojang.blaze3d.vertex.VertexConsumer;
import fr.cabane.pouvoir.Fruit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

/**
 * Les transformations des fruits du démon, plus longues et plus spectaculaires (docs/fruits/planche-transformations-glace.png
 * et -lave.png) : ce qui se passe autour de lui pendant que la matière le gagne (ClientFruit fait monter la matière sur
 * son corps, au même rythme), puis l'éclat quand c'est fini.
 * <ul>
 * <li>Glace 1 (1,5 s) : les flocons s'enroulent et filent dans sa main, des cristaux poussent sur son poing et brillent,
 * puis la coquille éclate.</li>
 * <li>Glace 2 (2,5 s) : un cercle de pics sort du sol et se referme en cocon de glace (la glace le gagne dedans), puis le
 * cocon explose en éclats.</li>
 * <li>Glace 3 (3,5 s) : un blizzard tourne autour de lui, il est pris dans un bloc de glace qui brille ; le bloc se fend,
 * un dragon de glace s'enroule autour et plonge sur sa tête (le casque) ; le bloc explose.</li>
 * <li>Lave 1 (1,5 s) : des cordes de lave sortent du sol fendu et montent dans sa main, puis le poing s'embrase.</li>
 * <li>Lave 2 (2,5 s) : un geyser de lave jaillit autour de lui, puis retombe en pluie.</li>
 * <li>Lave 3 (3,5 s) : la terre monte en rochers et se referme en volcan, qui brille ; un dragon de lave sort du cratère
 * et plonge sur sa tête ; le volcan explose.</li>
 * </ul>
 * Tout le monde le voit. À la première personne, on ne dessine pas ce qui l'enferme (le cocon, le bloc, le volcan) : il
 * ne verrait plus rien.
 */
final class ClientTransfoFruit {
    /** Combien de temps on dessine encore après la fin (les éclats, l'onde). ClientFruit garde la transformation autant. */
    static final double APRES = 1.2;
    private static final RandomSource HASARD = RandomSource.create();

    private static final float[] BLANC = {1F, 1F, 1F};
    private static final float[] GLACE_C = {0.8F, 0.93F, 1F};
    private static final float[] GLACE = {0.52F, 0.78F, 1F};
    private static final float[] GLACE_P = {0.3F, 0.55F, 0.92F};
    private static final float[] JAUNE = {1F, 0.87F, 0.35F};
    private static final float[] ORANGE = {1F, 0.47F, 0.08F};
    private static final float[] ROUGE = {0.85F, 0.2F, 0.05F};
    private static final float[] CROUTE = {0.18F, 0.1F, 0.09F};

    /** Le temps de la transformation à l'image d'avant, par joueur (pour ne jouer un son qu'une fois). */
    private static final Map<UUID, Double> AVANT = new HashMap<>();
    private static Vec3 cam = Vec3.ZERO;

    private ClientTransfoFruit() {
    }

    /** La durée de la montée à ce niveau (la main, la moitié du corps, tout le corps). */
    static double duree(int niveau) {
        return niveau <= 1 ? 1.5 : niveau == 2 ? 2.5 : 3.5;
    }

    // ================================================================ petits outils

    private static double entre(double t, double a, double b) {
        return Mth.clamp((t - a) / (b - a), 0, 1);
    }

    private static double lisse(double x) {
        x = Mth.clamp(x, 0, 1);
        return x * x * (3 - 2 * x);
    }

    private static double hache(int i, int j, int k) {
        return ClientFruit3.hache(i, j, k);
    }

    private static float[] mix(float[] a, float[] b, double k) {
        return new float[]{(float) (a[0] + (b[0] - a[0]) * k), (float) (a[1] + (b[1] - a[1]) * k), (float) (a[2] + (b[2] - a[2]) * k)};
    }

    private static float[] fois(float[] a, double k) {
        return new float[]{(float) Math.min(1, a[0] * k), (float) Math.min(1, a[1] * k), (float) Math.min(1, a[2] * k)};
    }

    private static void sommet(VertexConsumer c, Matrix4f m, Vec3 p, float[] col, double a) {
        c.addVertex(m, (float) (p.x - cam.x), (float) (p.y - cam.y), (float) (p.z - cam.z)).setColor(col[0], col[1], col[2], (float) Mth.clamp(a, 0, 1));
    }

    private static void quad(VertexConsumer c, Matrix4f m, Vec3 p0, Vec3 p1, Vec3 p2, Vec3 p3, float[] col, double a) {
        sommet(c, m, p0, col, a);
        sommet(c, m, p1, col, a);
        sommet(c, m, p2, col, a);
        sommet(c, m, p3, col, a);
    }

    /** v tourné de ax autour de x, puis de ay autour de y. */
    private static Vec3 tourne(Vec3 v, double ax, double ay) {
        double y1 = v.y * Math.cos(ax) - v.z * Math.sin(ax), z1 = v.y * Math.sin(ax) + v.z * Math.cos(ax);
        return new Vec3(v.x * Math.cos(ay) + z1 * Math.sin(ay), y1, -v.x * Math.sin(ay) + z1 * Math.cos(ay));
    }

    /** Un cube tourné, de demi-côté s, ombré face par face. */
    private static void cube(VertexConsumer c, Matrix4f m, Vec3 o, double s, double ax, double ay, float[] col, double a) {
        Vec3 x = tourne(new Vec3(s, 0, 0), ax, ay), y = tourne(new Vec3(0, s, 0), ax, ay), z = tourne(new Vec3(0, 0, s), ax, ay);
        Vec3[] p = new Vec3[8];
        for (int i = 0; i < 8; i++) {
            p[i] = o.add(x.scale((i & 1) == 0 ? -1 : 1)).add(y.scale((i & 2) == 0 ? -1 : 1)).add(z.scale((i & 4) == 0 ? -1 : 1));
        }
        int[][] faces = {{2, 3, 7, 6}, {0, 1, 5, 4}, {0, 1, 3, 2}, {4, 5, 7, 6}, {0, 2, 6, 4}, {1, 3, 7, 5}};
        double[] k = {1, 0.6, 0.85, 0.85, 0.75, 0.75};
        for (int f = 0; f < 6; f++) {
            quad(c, m, p[faces[f][0]], p[faces[f][1]], p[faces[f][2]], p[faces[f][3]], fois(col, k[f]), a);
        }
    }

    /** Une pointe à base carrée, de base vers dir, de la couleur pied (en bas) à bout. */
    private static void pointe(VertexConsumer c, Matrix4f m, Vec3 base, Vec3 dir, double longueur, double largeur, float[] pied, float[] bout, double a) {
        if (longueur < 0.005 || dir.lengthSqr() < 1e-9) {
            return;
        }
        Vec3 d = dir.normalize();
        Vec3 e1 = Math.abs(d.y) < 0.9 ? d.cross(new Vec3(0, 1, 0)).normalize() : d.cross(new Vec3(1, 0, 0)).normalize();
        Vec3 e2 = d.cross(e1).normalize();
        Vec3 haut = base.add(d.scale(longueur));
        Vec3[] coins = {base.add(e1.add(e2).scale(largeur / 2)), base.add(e1.subtract(e2).scale(largeur / 2)),
                base.add(e1.scale(-1).subtract(e2).scale(largeur / 2)), base.add(e2.subtract(e1).scale(largeur / 2))};
        for (int i = 0; i < 4; i++) {
            float[] cote = fois(pied, 0.82 + 0.18 * (i % 2));
            Vec3 p = coins[i], q = coins[(i + 1) % 4];
            sommet(c, m, p, cote, a);
            sommet(c, m, q, cote, a);
            sommet(c, m, haut, bout, a);
            sommet(c, m, haut, bout, a);
        }
    }

    /**
     * Un ruban face à la caméra le long de pts, large de « large » (plus fin aux bouts si effile) ; fondu > 0 : il
     * s'efface vers le début (le premier point).
     */
    private static void ruban(VertexConsumer c, Matrix4f m, List<Vec3> pts, double large, float[] col, double a, boolean effile, double fondu) {
        int n = pts.size();
        for (int i = 0; i + 1 < n; i++) {
            Vec3 p = pts.get(i), q = pts.get(i + 1);
            Vec3 cote = q.subtract(p).cross(p.add(q).scale(0.5).subtract(cam));
            if (cote.lengthSqr() < 1e-12) {
                continue;
            }
            cote = cote.normalize();
            double u0 = i / (double) (n - 1), u1 = (i + 1) / (double) (n - 1);
            double w0 = large / 2 * (effile ? Math.pow(Math.sin(Math.PI * u0), 0.7) : 1) + 0.004, w1 = large / 2 * (effile ? Math.pow(Math.sin(Math.PI * u1), 0.7) : 1) + 0.004;
            double al = a * (fondu > 0 ? Math.pow((u0 + u1) / 2, fondu) : 1);
            quad(c, m, p.subtract(cote.scale(w0)), p.add(cote.scale(w0)), q.add(cote.scale(w1)), q.subtract(cote.scale(w1)), col, al);
        }
    }

    /** Un ruban qui brille : un halo large et pâle, puis un cœur fin, presque blanc. */
    private static void lumineux(VertexConsumer c, Matrix4f m, List<Vec3> pts, double large, float[] col, double a, double fondu) {
        ruban(c, m, pts, large * 3.2, col, a * 0.22, true, fondu);
        ruban(c, m, pts, large, mix(col, BLANC, 0.65), a * 0.95, true, fondu);
    }

    private static List<Vec3> ligne(Vec3 a, Vec3 b, int n) {
        List<Vec3> l = new ArrayList<>();
        for (int i = 0; i <= n; i++) {
            l.add(a.lerp(b, i / (double) n));
        }
        return l;
    }

    /** Une hélice autour de c (vers le haut) : de r0 à r1, de la hauteur h0 à h1, tours tours, en partant de l'angle a0. */
    private static List<Vec3> helice(Vec3 c, double r0, double r1, double h0, double h1, double tours, double a0, int n) {
        List<Vec3> l = new ArrayList<>();
        for (int i = 0; i <= n; i++) {
            double u = i / (double) n, ang = a0 + tours * Math.PI * 2 * u, r = r0 + (r1 - r0) * u;
            l.add(c.add(Math.cos(ang) * r, h0 + (h1 - h0) * u, Math.sin(ang) * r));
        }
        return l;
    }

    /** Une onde à plat : un anneau de rayon r, plein au milieu de son épaisseur e, rien aux bords. */
    private static void onde(VertexConsumer c, Matrix4f m, Vec3 centre, double r, double e, float[] col, double a) {
        int n = 48;
        for (int i = 0; i < n; i++) {
            double t0 = i * Math.PI * 2 / n, t1 = (i + 1) * Math.PI * 2 / n;
            for (double s : new double[]{-1, 1}) {
                double r2 = Math.max(0, r + s * e / 2);
                sommet(c, m, centre.add(Math.cos(t0) * r, 0, Math.sin(t0) * r), col, a);
                sommet(c, m, centre.add(Math.cos(t0) * r2, 0, Math.sin(t0) * r2), col, 0);
                sommet(c, m, centre.add(Math.cos(t1) * r2, 0, Math.sin(t1) * r2), col, 0);
                sommet(c, m, centre.add(Math.cos(t1) * r, 0, Math.sin(t1) * r), col, a);
            }
        }
    }

    /** Un disque à plat, plein au centre, qui s'efface au bord (la brume, la lumière au sol). */
    private static void disque(VertexConsumer c, Matrix4f m, Vec3 centre, double r, float[] col, double a) {
        int n = 32;
        for (int i = 0; i < n; i++) {
            double t0 = i * Math.PI * 2 / n, t1 = (i + 1) * Math.PI * 2 / n;
            sommet(c, m, centre, col, a);
            sommet(c, m, centre, col, a);
            sommet(c, m, centre.add(Math.cos(t0) * r, 0, Math.sin(t0) * r), col, 0);
            sommet(c, m, centre.add(Math.cos(t1) * r, 0, Math.sin(t1) * r), col, 0);
        }
    }

    /** Une boîte transparente tournée de lacet autour de son centre (demi-tailles hx, hy, hz) ; arete > 0 : des arêtes qui brillent. */
    private static void boite(VertexConsumer c, Matrix4f m, Vec3 o, double hx, double hy, double hz, double lacet, float[] col, double a, double arete, double aArete) {
        Vec3[] p = new Vec3[8];
        for (int i = 0; i < 8; i++) {
            p[i] = o.add(tourne(new Vec3((i & 1) == 0 ? -hx : hx, (i & 2) == 0 ? -hy : hy, (i & 4) == 0 ? -hz : hz), 0, lacet));
        }
        int[][] faces = {{2, 3, 7, 6}, {0, 1, 5, 4}, {0, 1, 3, 2}, {4, 5, 7, 6}, {0, 2, 6, 4}, {1, 3, 7, 5}};
        for (int[] f : faces) {
            quad(c, m, p[f[0]], p[f[1]], p[f[2]], p[f[3]], col, a);
        }
        if (arete > 0) {
            int[][] ar = {{0, 1}, {2, 3}, {4, 5}, {6, 7}, {0, 2}, {1, 3}, {4, 6}, {5, 7}, {0, 4}, {1, 5}, {2, 6}, {3, 7}};
            for (int[] e : ar) {
                lumineux(c, m, ligne(p[e[0]], p[e[1]], 4), arete, GLACE_C, aArete, 0);
            }
        }
    }

    /** Des rayons de lumière qui partent de o dans tous les sens (entre r0 et r1). */
    private static void rayons(VertexConsumer c, Matrix4f m, Vec3 o, int n, double r0, double r1, double large, float[] col, double a, int graine) {
        for (int q = 0; q < n; q++) {
            double ang = q * Math.PI * 2 / n + hache(q, 1, graine) * 0.4, el = (hache(q, 2, graine) - 0.45) * 1.8;
            Vec3 d = new Vec3(Math.cos(ang), el, Math.sin(ang)).normalize();
            double l = r0 + (r1 - r0) * (0.55 + 0.45 * hache(q, 3, graine));
            lumineux(c, m, ligne(o.add(d.scale(r0)), o.add(d.scale(l)), 6), large, col, a, 0);
        }
    }

    /** Un éclat : un cœur blanc qui grossit puis s'éteint, et des rayons. */
    private static void eclat(VertexConsumer c, Matrix4f m, Vec3 o, double apres, double k, float[] col, int graine) {
        double f = 1 - entre(apres, 0, 0.45);
        if (f <= 0) {
            return;
        }
        cube(c, m, o, k * (0.12 + 0.35 * entre(apres, 0, 0.15)), apres * 3, apres * 5, BLANC, 0.9 * f);
        rayons(c, m, o, 14, 0.15 * k, k * (0.8 + 2.4 * entre(apres, 0, 0.3)), 0.06 * k, col, 0.8 * f, graine);
    }

    // ================================================================ dessiner

    /**
     * Dessine sa transformation : t le temps depuis qu'elle a commencé (jusqu'à duree + APRES), main le bout de la main
     * de son fruit.
     */
    static void dessiner(VertexConsumer c, Matrix4f m, Vec3 camera, Player j, boolean glace, int niveau, double t, int graine, float partiel, Vec3 main) {
        cam = camera;
        Minecraft mc = Minecraft.getInstance();
        boolean soi = j == mc.player && mc.options.getCameraType().isFirstPerson();
        Vec3 pieds = j.getPosition(partiel);
        double lacet = -Mth.lerp(partiel, j.yBodyRotO, j.yBodyRot) * Mth.DEG_TO_RAD;
        double d = duree(niveau);
        if (glace) {
            switch (Math.min(3, Math.max(1, niveau))) {
                case 1 -> glace1(c, m, pieds, main, t, d, graine);
                case 2 -> glace2(c, m, pieds, t, d, graine, soi);
                default -> glace3(c, m, pieds, t, d, graine, soi, lacet);
            }
        } else {
            switch (Math.min(3, Math.max(1, niveau))) {
                case 1 -> lave1(c, m, pieds, main, t, d, graine);
                case 2 -> lave2(c, m, pieds, t, d, graine, soi);
                default -> lave3(c, m, pieds, t, d, graine, soi);
            }
        }
    }

    // ---------------------------------------------------------------- la glace

    private static void brume(VertexConsumer c, Matrix4f m, Vec3 pieds, double r, double a) {
        disque(c, m, pieds.add(0, 0.06, 0), r, GLACE_C, 0.35 * a);
        disque(c, m, pieds.add(0, 0.25, 0), r * 0.8, BLANC, 0.18 * a);
    }

    /** Des éclats de glace qui volent loin de o, lancés depuis apres secondes, et qui retombent. */
    private static void eclatsGlace(VertexConsumer c, Matrix4f m, Vec3 o, int n, double vitesse, double apres, int graine, double taille) {
        double f = 1 - entre(apres, APRES * 0.6, APRES);
        for (int q = 0; q < n; q++) {
            double ang = hache(q, 1, graine) * Math.PI * 2, v = vitesse * (0.5 + hache(q, 2, graine));
            Vec3 vit = new Vec3(Math.cos(ang) * v, (0.3 + hache(q, 3, graine)) * v * 0.8, Math.sin(ang) * v);
            Vec3 p = o.add(vit.scale(apres)).add(0, -4.9 * apres * apres, 0);
            if (p.y < o.y - 1.4) {
                continue;
            }
            Vec3 dir = vit.add(0, -9.8 * apres, 0);
            double l = taille * (0.12 + 0.18 * hache(q, 4, graine));
            if (q % 3 == 0) {
                cube(c, m, p, l * 0.4, apres * 6 + q, apres * 4 + q, mix(GLACE_C, BLANC, hache(q, 5, graine)), 0.9 * f);
            } else {
                pointe(c, m, p, dir, l * 1.8, l * 0.6, GLACE, BLANC, 0.9 * f);
            }
        }
    }

    private static void glace1(VertexConsumer c, Matrix4f m, Vec3 pieds, Vec3 main, double t, double d, int graine) {
        double apres = t - d;
        brume(c, m, pieds, 1.1 + 0.5 * entre(t, 0, d), lisse(entre(t, 0, 0.4)) * (1 - entre(apres, 0, APRES)));
        if (apres < 0) {
            // les flocons s'enroulent et filent dans sa main
            double k = lisse(entre(t, 0, 0.3)) * (1 - entre(t, d - 0.25, d));
            for (int s = 0; s < 4; s++) {
                List<Vec3> pts = helice(main, 1.3 + 0.3 * hache(s, 1, graine), 0.04, -0.7 + 0.3 * hache(s, 2, graine), 0, 1.2, s * Math.PI / 2 + t * 5, 30);
                lumineux(c, m, pts, 0.045, GLACE_C, 0.7 * k, 0.8);
            }
            // des cristaux poussent sur son poing et brillent
            double kc = lisse(entre(t, 0.35, d));
            for (int q = 0; q < 10; q++) {
                Vec3 u = new Vec3(hache(q, 3, graine) - 0.5, hache(q, 4, graine) - 0.65, hache(q, 5, graine) - 0.5).normalize();
                pointe(c, m, main.add(u.scale(0.04)), u, kc * (0.1 + 0.22 * hache(q, 6, graine)), 0.05 + 0.06 * kc, GLACE, BLANC, 0.92);
            }
            double lum = entre(t, 0.7, d);
            cube(c, m, main, 0.05 + 0.07 * lum, t * 3, t * 4, BLANC, 0.85 * lum);
            if (lum > 0) {
                rayons(c, m, main, 8, 0.1, 0.25 + 0.35 * lum, 0.03, GLACE_C, 0.6 * lum, graine);
            }
        } else {
            // la coquille éclate : le gant de glace
            eclat(c, m, main, apres, 0.8, GLACE_C, graine);
            eclatsGlace(c, m, main, 16, 2.6, apres, graine, 0.8);
            double f = 1 - entre(apres, 0, APRES);
            onde(c, m, pieds.add(0, 0.05, 0), 0.4 + 2.8 * entre(apres, 0, 0.8), 0.5, GLACE_C, 0.7 * f);
        }
    }

    /** Le cocon de glace : une coque à facettes transparente, des fissures qui brillent (de plus en plus). */
    private static void cocon(VertexConsumer c, Matrix4f m, Vec3 pieds, double R, double H, int graine, double a, double fissures) {
        int nu = 12, nv = 8;
        Vec3[][] v = new Vec3[nv + 1][nu];
        for (int jj = 0; jj <= nv; jj++) {
            double u = jj / (double) nv, r = R * Math.pow(u < 0.4 ? 1 : Math.cos((u - 0.4) / 0.6 * Math.PI / 2), 0.75);
            for (int i = 0; i < nu; i++) {
                double ang = (i + (jj % 2) * 0.5) * Math.PI * 2 / nu, k = jj == nv ? 0 : 0.88 + 0.24 * hache(i, jj, graine);
                double dy = jj > 0 && jj < nv ? (hache(i, jj, graine + 1) - 0.5) * 0.18 : 0;
                v[jj][i] = pieds.add(Math.cos(ang) * r * k, H * u + dy, Math.sin(ang) * r * k);
            }
        }
        for (int jj = 0; jj < nv; jj++) {
            for (int i = 0; i < nu; i++) {
                Vec3 p0 = v[jj][i], p1 = v[jj][(i + 1) % nu], p2 = v[jj + 1][(i + 1) % nu], p3 = v[jj + 1][i];
                quad(c, m, p0, p1, p2, p3, mix(GLACE_C, BLANC, 0.3 + 0.5 * hache(i, jj, graine + 2)), a * (0.14 + 0.12 * hache(i, jj, graine + 3)));
                if (hache(i, jj, graine + 4) < 0.45 * fissures) {
                    lumineux(c, m, ClientFoudre.zigzag(p0, p3, graine * 100L + i * 10 + jj, 0.12, 2), 0.025, GLACE_C, a, 0);
                }
                if (hache(i, jj, graine + 5) < 0.3 * fissures) {
                    lumineux(c, m, ClientFoudre.zigzag(p0, p1, graine * 100L + i * 10 + jj + 5000, 0.12, 2), 0.022, GLACE_C, a, 0);
                }
            }
        }
    }

    private static void glace2(VertexConsumer c, Matrix4f m, Vec3 pieds, double t, double d, int graine, boolean soi) {
        double apres = t - d;
        brume(c, m, pieds, 1.6 + entre(t, 0, d), lisse(entre(t, 0, 0.4)) * (1 - entre(apres, 0, APRES)));
        if (apres < 0) {
            // un cercle de pics sort du sol, puis se penche vers lui
            double pousse = lisse(entre(t, 0, 0.7)), penche = 0.35 + 0.65 * lisse(entre(t, 0.6, 1.3));
            for (int q = 0; q < 10; q++) {
                double ang = q * Math.PI * 2 / 10 + hache(q, 1, graine) * 0.3;
                Vec3 u = new Vec3(Math.cos(ang), 0, Math.sin(ang)), base = pieds.add(u.scale(1.3));
                double l = (1.3 + 0.7 * hache(q, 2, graine)) * pousse;
                pointe(c, m, base, new Vec3(-u.x * penche, 1, -u.z * penche), l, 0.3 * pousse, GLACE, BLANC, soi ? 0.5 : 0.92);
                pointe(c, m, base.add(u.scale(0.25)), new Vec3(u.x * 0.5, 1, u.z * 0.5), l * 0.35, 0.12 * pousse, GLACE, BLANC, 0.92);
            }
            // le cocon se referme, la glace le gagne dedans, ça brille de plus en plus
            double k = lisse(entre(t, 0.9, 1.4)), lum = entre(t, 1.3, d);
            if (k > 0 && !soi) {
                cocon(c, m, pieds, 1.35, 2.8, graine, k, lum);
                boite(c, m, pieds.add(0, 0.95, 0), 0.4, 0.95, 0.3, 0, BLANC, 0.3 * lum, 0, 0);
                rayons(c, m, pieds.add(0, 1.1, 0), 12, 1.35, 1.35 + 1.2 * lum, 0.05, GLACE_C, 0.5 * lum, graine);
            }
        } else {
            eclat(c, m, pieds.add(0, 1.1, 0), apres, 1.6, GLACE_C, graine);
            eclatsGlace(c, m, pieds.add(0, 1.1, 0), 40, 4.5, apres, graine, 1.6);
            double f = 1 - entre(apres, 0, APRES);
            onde(c, m, pieds.add(0, 0.05, 0), 0.5 + 4.8 * entre(apres, 0, 0.9), 0.9, GLACE_C, 0.75 * f);
            onde(c, m, pieds.add(0, 0.07, 0), 0.4 + 2.4 * entre(apres, 0, 0.6), 0.5, BLANC, 0.6 * f);
        }
    }

    private static void glace3(VertexConsumer c, Matrix4f m, Vec3 pieds, double t, double d, int graine, boolean soi, double lacet) {
        double apres = t - d;
        brume(c, m, pieds, 2.2 + entre(t, 0, d), lisse(entre(t, 0, 0.4)) * (1 - entre(apres, 0, APRES)));
        // le blizzard tourne autour de lui, puis se calme quand le bloc se forme
        double kb = lisse(entre(t, 0, 0.4)) * (1 - lisse(entre(t, 1.0, 1.6)));
        if (kb > 0) {
            for (int s = 0; s < 10; s++) {
                double h = hache(s, 1, graine);
                List<Vec3> pts = helice(pieds, 0.6 + 0.3 * h, 2.1 + 0.7 * hache(s, 2, graine), 0.1 + 1.2 * h, 2.4 + 1.6 * hache(s, 3, graine), 1.2, h * Math.PI * 2 + t * 6, 36);
                ruban(c, m, pts, 0.18 + 0.1 * h, s % 3 == 0 ? BLANC : GLACE_C, 0.32 * kb, true, 0.6);
            }
            for (int q = 0; q < 40; q++) {
                double h = hache(q, 4, graine), ang = h * Math.PI * 2 + t * (4 + 2 * hache(q, 5, graine)), r = 0.6 + 2 * hache(q, 6, graine);
                double y = (hache(q, 7, graine) * 4 + t * 1.6) % 4;
                cube(c, m, pieds.add(Math.cos(ang) * r, y, Math.sin(ang) * r), 0.035, q, t * 3, BLANC, 0.9 * kb);
            }
        }
        if (apres < 0) {
            // pris dans un bloc de glace qui brille de l'intérieur
            double kbloc = lisse(entre(t, 1.0, 1.3)), lum = entre(t, 1.2, 1.8) * (1 - 0.35 * entre(t, 2.6, d));
            Vec3 centre = pieds.add(0, 1.1, 0);
            if (kbloc > 0 && !soi) {
                boite(c, m, centre, 0.62, 1.15, 0.48, lacet, GLACE_C, 0.26 * kbloc, 0.04, 0.9 * kbloc);
                boite(c, m, pieds.add(0, 0.95, 0), 0.42, 0.97, 0.3, lacet, BLANC, 0.42 * lum, 0, 0);
                if (t < 2.7) {
                    rayons(c, m, centre, 14, 1.0, 2.6, 0.06, GLACE_C, 0.5 * lum * (1 - entre(t, 2.3, 2.7)), graine);
                }
                // le bloc se fend : des fissures de lumière qui gagnent les faces
                double f = entre(t, 2.3, d);
                int n = (int) (16 * f);
                for (int q = 0; q < n; q++) {
                    int face = q % 4;
                    double sx = face < 2 ? 0.62 : 0, sz = face >= 2 ? 0.48 : 0, sg = face % 2 == 0 ? 1 : -1;
                    Vec3 a = new Vec3(sx * sg + (face >= 2 ? (hache(q, 1, graine) - 0.5) * 1.1 : 0), (hache(q, 2, graine) - 0.5) * 2, sz * sg + (face < 2 ? (hache(q, 3, graine) - 0.5) * 0.8 : 0));
                    Vec3 b = a.add(face >= 2 ? (hache(q, 4, graine) - 0.5) * 0.9 : 0, (hache(q, 5, graine) - 0.5) * 1.1, face < 2 ? (hache(q, 6, graine) - 0.5) * 0.7 : 0);
                    lumineux(c, m, ClientFoudre.zigzag(centre.add(tourne(a, 0, lacet)), centre.add(tourne(b, 0, lacet)), graine * 31L + q, 0.15, 3), 0.03, GLACE_C, 0.9, 0);
                }
            }
            // un dragon de glace s'enroule autour, puis plonge sur sa tête
            if (t > 2.3) {
                serpent(c, m, pieds, t, true, graine);
            }
        } else {
            Vec3 centre = pieds.add(0, 1.1, 0);
            eclat(c, m, pieds.add(0, 1.9, 0), apres, 2.2, GLACE_C, graine);
            eclatsGlace(c, m, centre, 60, 5.5, apres, graine, 2.2);
            double f = 1 - entre(apres, 0, APRES);
            onde(c, m, pieds.add(0, 0.05, 0), 0.6 + 6.5 * entre(apres, 0, 1.0), 1.2, GLACE_C, 0.8 * f);
            onde(c, m, pieds.add(0, 0.07, 0), 0.5 + 3.2 * entre(apres, 0, 0.6), 0.6, BLANC, 0.6 * f);
            // la traînée du dragon, qui finit sur sa tête
            for (int s = 0; s < 3; s++) {
                lumineux(c, m, helice(pieds.add(0, 1.9, 0), 1.5 - s * 0.15, 0.15, 2.4, 0.15, 1.3, s * 2.1 + apres * 3, 40), 0.05, GLACE_C, 0.8 * f, 0.7);
            }
        }
    }

    // ---------------------------------------------------------------- le dragon-esprit

    /** Où est sa tête au temps tau (de 2,3 s à la fin) : il monte en spirale autour de lui, puis plonge sur sa tête. */
    private static Vec3 tete(Vec3 pieds, double tau, boolean glace) {
        if (tau < 3.05) {
            double u = (tau - 2.3) / 0.75;
            if (glace) {
                double ang = u * Math.PI * 2 * 1.3;
                return pieds.add(Math.cos(ang) * 1.7, 0.4 + 3.0 * u, Math.sin(ang) * 1.7);
            }
            // la lave sort du cratère et monte en tournant
            double ang = u * Math.PI * 2 * 1.1, r = 0.3 + 1.4 * u;
            return pieds.add(Math.cos(ang) * r, 3.3 + 2.0 * u, Math.sin(ang) * r);
        }
        double u = Math.min(1, (tau - 3.05) / 0.45);
        Vec3 a = tete(pieds, 3.0499, glace), b = pieds.add(0, glace ? 4.6 : 5.6, 0), z = pieds.add(0, 2.1, 0);
        return a.scale((1 - u) * (1 - u)).add(b.scale(2 * u * (1 - u))).add(z.scale(u * u));
    }

    /** Le dragon : une tête (le museau, deux cornes, les yeux) et un corps de serpent qui suit le même chemin. */
    private static void serpent(VertexConsumer c, Matrix4f m, Vec3 pieds, double t, boolean glace, int graine) {
        List<Vec3> corps = new ArrayList<>();
        for (int k = 0; k < 36; k++) {
            double tau = t - k * 0.022;
            if (tau < 2.3) {
                break;
            }
            corps.add(tete(pieds, tau, glace));
        }
        if (corps.size() < 2) {
            return;
        }
        double ap = lisse(entre(t, 2.3, 2.5));
        for (int k = corps.size() - 1; k >= 1; k--) {
            Vec3 p = corps.get(k);
            double s = 0.16 * (1 - k / 44.0) * ap;
            if (glace) {
                cube(c, m, p, s, k * 0.6, t * 2 + k, mix(GLACE, BLANC, 0.3 + 0.4 * hache(k, 1, graine)), 0.88);
                if (k % 3 == 0) {
                    Vec3 dir = corps.get(k - 1).subtract(p);
                    pointe(c, m, p.add(0, s, 0), new Vec3(0, 1, 0).add(dir.normalize().scale(-0.6)), s * 2, s * 0.8, GLACE, BLANC, 0.9);
                }
            } else {
                cube(c, m, p, s, k * 0.6, t * 2 + k, k % 2 == 0 ? mix(CROUTE, ORANGE, 0.4) : mix(ORANGE, JAUNE, hache(k, 1, graine)), 0.95);
                if (k % 2 == 0) {
                    pointe(c, m, p.add(0, s * 0.8, 0), new Vec3(0, 1, 0), s * 2.2, s * 0.9, JAUNE, ROUGE, 0.9);
                }
            }
        }
        lumineux(c, m, corps, 0.08 * ap, glace ? GLACE_C : ORANGE, 0.55, 0);
        // la tête : vers où il va
        Vec3 o = corps.get(0), f = o.subtract(corps.get(1));
        if (f.lengthSqr() < 1e-9) {
            return;
        }
        f = f.normalize();
        Vec3 x = Math.abs(f.y) < 0.95 ? f.cross(new Vec3(0, 1, 0)).normalize() : new Vec3(1, 0, 0), y = x.cross(f).normalize();
        float[] teinte = glace ? mix(GLACE_P, GLACE, 0.5) : mix(CROUTE, ROUGE, 0.3);
        double k = ap;
        cube(c, m, o, 0.24 * k, Math.asin(-f.y), Math.atan2(f.x, f.z), teinte, 0.95);
        cube(c, m, o.add(f.scale(0.28 * k)).subtract(y.scale(0.05 * k)), 0.15 * k, Math.asin(-f.y), Math.atan2(f.x, f.z), glace ? GLACE : mix(ORANGE, CROUTE, 0.3), 0.95);
        for (int s = -1; s <= 1; s += 2) {
            pointe(c, m, o.add(x.scale(s * 0.14 * k)).add(y.scale(0.18 * k)), y.scale(0.8).subtract(f).add(x.scale(s * 0.3)), 0.4 * k, 0.09 * k,
                    glace ? GLACE : CROUTE, glace ? BLANC : ORANGE, 0.95);
            cube(c, m, o.add(x.scale(s * 0.17 * k)).add(y.scale(0.07 * k)).add(f.scale(0.12 * k)), 0.035 * k, 0, 0, glace ? new float[]{0.6F, 0.97F, 1F} : new float[]{1F, 0.96F, 0.65F}, 1);
            // les moustaches
            lumineux(c, m, ligne(o.add(f.scale(0.4 * k)).add(x.scale(s * 0.1 * k)), o.add(f.scale(0.1 * k)).add(x.scale(s * 0.5 * k)).subtract(y.scale(0.3 * k)), 5), 0.02,
                    glace ? GLACE_C : JAUNE, 0.8, 0);
        }
    }

    // ---------------------------------------------------------------- la lave

    /** Un point d'une courbe de Bézier à trois points. */
    private static Vec3 bezier(Vec3 a, Vec3 b, Vec3 z, double u) {
        return a.scale((1 - u) * (1 - u)).add(b.scale(2 * u * (1 - u))).add(z.scale(u * u));
    }

    private static void flammes(VertexConsumer c, Matrix4f m, Vec3 o, int n, double longueur, double largeur, double a, int graine, double t) {
        for (int q = 0; q < n; q++) {
            Vec3 u = new Vec3(hache(q, 1, graine) - 0.5, hache(q, 2, graine) - 0.35, hache(q, 3, graine) - 0.5).normalize();
            double l = longueur * (0.6 + 0.6 * hache(q, 4, graine)) * (0.85 + 0.15 * Math.sin(t * 22 + q));
            pointe(c, m, o, u, l, largeur, JAUNE, ROUGE, a);
        }
    }

    private static void lave1(VertexConsumer c, Matrix4f m, Vec3 pieds, Vec3 main, double t, double d, int graine) {
        double apres = t - d;
        disque(c, m, pieds.add(0, 0.05, 0), 1.3 + entre(t, 0, d), ORANGE, 0.3 * lisse(entre(t, 0, 0.5)) * (1 - entre(apres, 0, APRES)));
        if (apres < 0) {
            // des cordes de lave sortent du sol fendu et montent dans sa main
            for (int k = 0; k < 3; k++) {
                double ang = k * Math.PI * 2 / 3 + graine * 0.37;
                Vec3 a = pieds.add(Math.cos(ang) * 0.95, 0.03, Math.sin(ang) * 0.95), b = pieds.add(Math.cos(ang + 1.5) * 1.2, 1.1, Math.sin(ang + 1.5) * 1.2);
                double front = lisse(entre(t, 0.2 + 0.12 * k, d - 0.15));
                List<Vec3> pts = new ArrayList<>();
                int n = 26;
                for (int i = 0; i <= n * front; i++) {
                    double u = i / (double) n;
                    Vec3 p = bezier(a, b, main, u).add(Math.sin(u * 9 + t * 7 + k) * 0.03, 0, Math.cos(u * 8 + t * 6 + k) * 0.03);
                    pts.add(p);
                    cube(c, m, p, 0.045 + 0.02 * Math.sin(u * 13 + t * 9), u * 5 + t, t * 2 + k, mix(JAUNE, ORANGE, 0.3 + 0.6 * u), 1);
                }
                if (pts.size() > 1) {
                    ruban(c, m, pts, 0.22, ORANGE, 0.28, true, 0);
                }
            }
            // la lave s'amasse sur son poing
            double kb = entre(t, 0.6, d);
            for (int q = 0; q < 8; q++) {
                double ang = q * 0.8 + t * 3;
                cube(c, m, main.add(Math.cos(ang) * 0.06 * kb, (hache(q, 1, graine) - 0.5) * 0.12 * kb, Math.sin(ang) * 0.06 * kb), 0.03 + 0.05 * kb, q, t * 4, q % 2 == 0 ? JAUNE : ORANGE, kb);
            }
        } else {
            // le poing s'embrase d'un coup
            double f = 1 - entre(apres, 0, 0.6);
            flammes(c, m, main, 12, 0.7 * entre(apres, 0, 0.12) * f + 0.05, 0.16 * f, f, graine, apres);
            eclat(c, m, main, apres, 0.8, ORANGE, graine);
            double g = 1 - entre(apres, 0, APRES);
            onde(c, m, pieds.add(0, 0.05, 0), 0.4 + 2.8 * entre(apres, 0, 0.8), 0.55, ORANGE, 0.7 * g);
        }
    }

    private static void lave2(VertexConsumer c, Matrix4f m, Vec3 pieds, double t, double d, int graine, boolean soi) {
        double apres = t - d;
        disque(c, m, pieds.add(0, 0.05, 0), 2 + entre(t, 0, d), ORANGE, 0.35 * lisse(entre(t, 0, 0.4)) * (1 - entre(apres, 0, APRES)));
        // le geyser : un cercle de flammes qui jaillit autour de lui, une colonne de chaleur, de la lave qui monte
        double kg = lisse(entre(t, 0.3, 0.8)) * (1 - lisse(entre(t, d - 0.35, d + 0.15)));
        if (kg > 0) {
            for (int q = 0; q < 18; q++) {
                double ang = q * Math.PI * 2 / 18 + hache(q, 1, graine) * 0.25;
                Vec3 base = pieds.add(Math.cos(ang) * 0.95, 0, Math.sin(ang) * 0.95);
                double l = (2.0 + 1.4 * hache(q, 2, graine)) * kg * (0.78 + 0.22 * Math.sin(t * 19 + q * 1.7));
                pointe(c, m, base, new Vec3(Math.cos(ang) * 0.06, 1, Math.sin(ang) * 0.06), l, 0.34, JAUNE, ROUGE, soi ? 0.45 : 0.95);
            }
            for (int i = 0; i < 16; i++) {
                double a0 = i * Math.PI * 2 / 16, a1 = (i + 1) * Math.PI * 2 / 16, h = 3.6 * kg;
                quad(c, m, pieds.add(Math.cos(a0) * 0.9, 0, Math.sin(a0) * 0.9), pieds.add(Math.cos(a1) * 0.9, 0, Math.sin(a1) * 0.9),
                        pieds.add(Math.cos(a1) * 1.1, h, Math.sin(a1) * 1.1), pieds.add(Math.cos(a0) * 1.1, h, Math.sin(a0) * 1.1), JAUNE, 0.18 * kg);
            }
            for (int q = 0; q < 30; q++) {
                double h = hache(q, 3, graine), y = ((h + t * 1.3) % 1) * 3.8 * kg, ang = hache(q, 4, graine) * Math.PI * 2 + t, r = 0.25 + 0.75 * hache(q, 5, graine);
                cube(c, m, pieds.add(Math.cos(ang) * r, y, Math.sin(ang) * r), 0.05 + 0.04 * h, q, t * 3, q % 3 == 0 ? JAUNE : ORANGE, kg);
            }
        }
        if (apres >= 0) {
            // il retombe en pluie de gouttes et de braises
            double g = 1 - entre(apres, APRES * 0.7, APRES);
            for (int q = 0; q < 32; q++) {
                double ang = hache(q, 6, graine) * Math.PI * 2, r = 0.5 + 2.6 * Math.sqrt(hache(q, 7, graine)), y0 = 3.2 + 1.4 * hache(q, 8, graine);
                double te = apres + 0.25 * hache(q, 9, graine), y = y0 - 4.9 * te * te;
                Vec3 p = pieds.add(Math.cos(ang) * r, Math.max(0.05, y), Math.sin(ang) * r);
                if (y > 0.05) {
                    cube(c, m, p, 0.05, q, te * 6, q % 3 == 0 ? JAUNE : ORANGE, g);
                    lumineux(c, m, ligne(p, p.add(0, 0.25 + 0.6 * te, 0), 3), 0.03, ORANGE, 0.6 * g, 0);
                } else {
                    pointe(c, m, p, new Vec3(0, 1, 0), 0.25 * g, 0.1, JAUNE, ROUGE, g);
                }
            }
            eclat(c, m, pieds.add(0, 1.1, 0), apres, 1.3, ORANGE, graine);
            onde(c, m, pieds.add(0, 0.05, 0), 0.5 + 4.6 * entre(apres, 0, 0.9), 0.9, ORANGE, 0.75 * (1 - entre(apres, 0, APRES)));
        }
    }

    /** Un rocher du volcan : sa place sur le cône, sa hauteur au-dessus de ses pieds (h). */
    private record Roche(Vec3 pos, double h, double taille, double ax, double ay, int n) {
    }

    /** La couleur de la lave à la case (i, j) : de la croûte noire en plaques, des fissures qui brillent (plus avec lum). */
    private static float[] magma(double i, double j, int k, double lum, double ombre) {
        double f = ClientFruit3.fissure(i, j, k, 2.2);
        if (f < 0.22) {
            return mix(mix(ORANGE, JAUNE, 0.5 + 0.5 * lum), BLANC, 0.25 * lum);
        }
        if (f < 0.48) {
            return mix(ROUGE, ORANGE, 0.35 + 0.6 * lum);
        }
        return fois(mix(CROUTE, new float[]{0.34F, 0.19F, 0.14F}, 0.6 * hache((int) Math.floor(i), (int) Math.floor(j), k)), ombre);
    }

    /** Un rocher de magma : un cube tourné, chaque face en 4 × 4 cases de croûte et de fissures. */
    private static void rocheMagma(VertexConsumer c, Matrix4f m, Vec3 o, double s, double ax, double ay, int n, double lum, double a) {
        Vec3 x = tourne(new Vec3(s, 0, 0), ax, ay), y = tourne(new Vec3(0, s, 0), ax, ay), z = tourne(new Vec3(0, 0, s), ax, ay);
        Vec3[] p = new Vec3[8];
        for (int i = 0; i < 8; i++) {
            p[i] = o.add(x.scale((i & 1) == 0 ? -1 : 1)).add(y.scale((i & 2) == 0 ? -1 : 1)).add(z.scale((i & 4) == 0 ? -1 : 1));
        }
        int[][] faces = {{2, 3, 7, 6}, {0, 1, 5, 4}, {0, 1, 3, 2}, {4, 5, 7, 6}, {0, 2, 6, 4}, {1, 3, 7, 5}};
        double[] ombre = {1, 0.6, 0.85, 0.85, 0.75, 0.75};
        int k = 4;
        for (int f = 0; f < 6; f++) {
            Vec3 p0 = p[faces[f][0]], p1 = p[faces[f][1]], p2 = p[faces[f][2]], p3 = p[faces[f][3]];
            for (int i = 0; i < k; i++) {
                for (int jj = 0; jj < k; jj++) {
                    double u0 = i / (double) k, u1 = (i + 1) / (double) k, v0 = jj / (double) k, v1 = (jj + 1) / (double) k;
                    Vec3 a0 = p0.lerp(p1, u0), a1 = p0.lerp(p1, u1), b0 = p3.lerp(p2, u0), b1 = p3.lerp(p2, u1);
                    quad(c, m, a0.lerp(b0, v0), a1.lerp(b1, v0), a1.lerp(b1, v1), a0.lerp(b0, v1), magma(i + n * 5 + f * 3, jj + n * 3, 900 + f, lum, ombre[f]), a);
                }
            }
        }
    }

    private static List<Roche> roches(Vec3 pieds, double R, double H, int graine) {
        List<Roche> l = new ArrayList<>();
        int n = 0;
        for (int jj = 0; jj * 0.32 < H; jj++) {
            double y = 0.16 + jj * 0.32, r = R * (1 - 0.78 * y / H);
            int k = Math.max(6, (int) Math.round(Math.PI * 2 * r / 0.34));
            for (int i = 0; i < k; i++) {
                double ang = (i + (jj % 2) * 0.5) * Math.PI * 2 / k + 0.12 * hache(i, jj, graine);
                l.add(new Roche(pieds.add(Math.cos(ang) * r, y, Math.sin(ang) * r), y, 0.16 + 0.04 * hache(i, jj, graine + 1), hache(i, jj, graine + 2), -ang, n++));
            }
        }
        return l;
    }

    private static void lave3(VertexConsumer c, Matrix4f m, Vec3 pieds, double t, double d, int graine, boolean soi) {
        double apres = t - d, R = 1.45, H = 3.4;
        disque(c, m, pieds.add(0, 0.05, 0), 2.6 + entre(t, 0, d), ORANGE, 0.4 * lisse(entre(t, 0, 0.4)) * (1 - entre(apres, 0, APRES)));
        List<Roche> roches = roches(pieds, R, H, graine);
        if (apres < 0) {
            double monte = lisse(entre(t, 0, 1.0)) * H, lum = entre(t, 0.9, 1.6);
            if (!soi) {
                // dedans, de la lave qui coule et brille (on la voit entre les rochers)
                if (lum > 0) {
                    int nh = 18, na = 32;
                    for (int jj = 0; jj < nh; jj++) {
                        double y0 = jj * H / nh, y1 = Math.min(monte, (jj + 1) * H / nh);
                        if (y0 >= monte) {
                            break;
                        }
                        double r0 = R * (1 - 0.78 * y0 / H) * 0.82, r1 = R * (1 - 0.78 * y1 / H) * 0.82;
                        for (int i = 0; i < na; i++) {
                            double a0 = i * Math.PI * 2 / na, a1 = (i + 1) * Math.PI * 2 / na;
                            quad(c, m, pieds.add(Math.cos(a0) * r0, y0, Math.sin(a0) * r0), pieds.add(Math.cos(a1) * r0, y0, Math.sin(a1) * r0),
                                    pieds.add(Math.cos(a1) * r1, y1, Math.sin(a1) * r1), pieds.add(Math.cos(a0) * r1, y1, Math.sin(a0) * r1),
                                    magma(i * 0.8, jj - t * 2.5, 950, 0.6 + 0.4 * lum, 1), 0.95 * lum);
                        }
                    }
                }
                // la terre monte en rochers de magma et se referme autour de lui
                for (Roche r : roches) {
                    double dessous = r.h() - monte;
                    if (dessous > 0) {
                        continue;
                    }
                    double sort = lisse(Math.min(1, -dessous / 0.5));
                    double braise = lum * (0.5 + 0.5 * Math.sin(t * 7 + r.n()));
                    rocheMagma(c, m, r.pos().add(0, -0.3 * (1 - sort), 0), r.taille() * (0.6 + 0.4 * sort), r.ax(), r.ay(), r.n(), braise, 1);
                }
                // le cratère crache
                if (t > 1.0) {
                    Vec3 haut = pieds.add(0, H - 0.1, 0);
                    flammes(c, m, haut, 8, 0.6 + 0.4 * lum, 0.22, 0.9, graine, t);
                    for (int q = 0; q < 12; q++) {
                        double h = (hache(q, 5, graine) + t * 1.5) % 1;
                        cube(c, m, haut.add((hache(q, 6, graine) - 0.5) * 0.6 * (1 + h), h * 1.6, (hache(q, 7, graine) - 0.5) * 0.6 * (1 + h)), 0.05, q, t, q % 2 == 0 ? JAUNE : ORANGE, 1 - h);
                    }
                }
            }
            // un dragon de lave sort du cratère et plonge sur sa tête
            if (t > 2.3) {
                serpent(c, m, pieds, t, false, graine);
            }
        } else {
            // le volcan explose : les rochers volent
            double g = 1 - entre(apres, APRES * 0.6, APRES);
            for (Roche r : roches) {
                if (r.n() % 2 == 1) {
                    continue;
                }
                Vec3 dehors = r.pos().subtract(pieds).multiply(1, 0, 1).normalize();
                double v = 3 + 3.5 * hache(r.n(), 8, graine);
                Vec3 vit = dehors.scale(v).add(0, 2 + 3 * hache(r.n(), 9, graine), 0);
                Vec3 p = r.pos().add(vit.scale(apres)).add(0, -4.9 * apres * apres, 0);
                if (p.y < pieds.y - 0.5) {
                    continue;
                }
                rocheMagma(c, m, p, r.taille(), r.ax() + apres * 7, r.ay() + apres * 5, r.n(), 0.7, g);
                Vec3 dv = vit.add(0, -9.8 * apres, 0).normalize();
                ruban(c, m, ligne(p.subtract(dv.scale(0.7)), p, 4), 0.2, ORANGE, 0.45 * g, true, 1);
            }
            eclat(c, m, pieds.add(0, 1.9, 0), apres, 2.2, ORANGE, graine);
            double f = 1 - entre(apres, 0, APRES);
            onde(c, m, pieds.add(0, 0.05, 0), 0.6 + 6.5 * entre(apres, 0, 1.0), 1.2, ORANGE, 0.8 * f);
            onde(c, m, pieds.add(0, 0.07, 0), 0.5 + 3.2 * entre(apres, 0, 0.6), 0.6, JAUNE, 0.6 * f);
            for (int s = 0; s < 3; s++) {
                lumineux(c, m, helice(pieds.add(0, 1.9, 0), 1.5 - s * 0.15, 0.15, 2.8, 0.15, 1.3, s * 2.1 + apres * 3, 40), 0.05, ORANGE, 0.8 * f, 0.7);
            }
        }
    }

    // ================================================================ les sons et les particules

    private static void son(Player j, SoundEvent son, float volume, float hauteur) {
        j.level().playLocalSound(j.getX(), j.getY() + 1, j.getZ(), son, SoundSource.PLAYERS, volume, hauteur, false);
    }

    private static void particule(Minecraft mc, ParticleOptions p, Vec3 o, double vx, double vy, double vz) {
        mc.level.addParticle(p, o.x, o.y, o.z, vx, vy, vz);
    }

    private static double aleat(double k) {
        return (HASARD.nextDouble() - 0.5) * 2 * k;
    }

    /** À chaque tick, pendant sa transformation : les sons (une fois chacun) et des particules en plus. */
    static void tick(Minecraft mc, Player j, boolean glace, int niveau, double t) {
        UUID id = j.getUUID();
        double avant = AVANT.getOrDefault(id, -1.0);
        if (avant > t) {
            avant = -1;
        }
        AVANT.put(id, t);
        double d = duree(niveau);
        Vec3 pieds = j.position();
        boolean loin = mc.player == null || mc.player.distanceTo(j) > 48;
        BlockParticleOption glaceBloc = new BlockParticleOption(ParticleTypes.BLOCK, Blocks.PACKED_ICE.defaultBlockState());
        if (niveau >= 3) {
            if (glace) {
                if (passe(avant, t, 0)) {
                    son(j, SoundEvents.POWDER_SNOW_BREAK, 1.4F, 0.5F);
                    son(j, SoundEvents.AMETHYST_BLOCK_RESONATE, 1.2F, 0.6F);
                }
                if (passe(avant, t, 1.0)) {
                    son(j, SoundEvents.GLASS_PLACE, 1.4F, 0.6F);
                    son(j, SoundEvents.AMETHYST_BLOCK_CHIME, 1.6F, 0.5F);
                }
                if (passe(avant, t, 1.5)) {
                    son(j, SoundEvents.BEACON_ACTIVATE, 1F, 1.6F);
                }
                if (passe(avant, t, 2.3)) {
                    son(j, SoundEvents.AMETHYST_CLUSTER_BREAK, 1.4F, 0.6F);
                    son(j, SoundEvents.ENDER_DRAGON_GROWL, 0.5F, 1.7F);
                }
                if (passe(avant, t, d)) {
                    son(j, SoundEvents.GLASS_BREAK, 1.6F, 0.5F);
                    son(j, SoundEvents.GENERIC_EXPLODE.value(), 0.6F, 1.4F);
                    for (int i = 0; i < 70 && !loin; i++) {
                        double a = HASARD.nextDouble() * Math.PI * 2, v = 0.2 + HASARD.nextDouble() * 0.35;
                        particule(mc, i % 2 == 0 ? glaceBloc : ParticleTypes.SNOWFLAKE, pieds.add(0, 0.3 + HASARD.nextDouble() * 1.8, 0), Math.cos(a) * v, aleat(0.15) + 0.1, Math.sin(a) * v);
                    }
                    secouer(mc, j, 1F);
                }
                if (t < 1.5 && !loin) {
                    for (int i = 0; i < 4; i++) {
                        double a = t * 6 + HASARD.nextDouble() * Math.PI * 2, r = 0.8 + HASARD.nextDouble() * 1.8;
                        particule(mc, ParticleTypes.SNOWFLAKE, pieds.add(Math.cos(a) * r, HASARD.nextDouble() * 3.5, Math.sin(a) * r), -Math.sin(a) * 0.2, 0.03, Math.cos(a) * 0.2);
                    }
                }
            } else {
                for (double x : new double[]{0, 0.3, 0.6, 0.9}) {
                    if (passe(avant, t, x)) {
                        son(j, SoundEvents.STONE_PLACE, 1.4F, 0.5F + (float) x * 0.4F);
                        son(j, SoundEvents.GRAVEL_BREAK, 1F, 0.6F);
                    }
                }
                if (passe(avant, t, 1.0)) {
                    son(j, SoundEvents.LAVA_AMBIENT, 1.6F, 0.5F);
                    son(j, SoundEvents.BLAZE_AMBIENT, 0.8F, 0.5F);
                }
                if (passe(avant, t, 1.6)) {
                    son(j, SoundEvents.BEACON_ACTIVATE, 1F, 0.8F);
                }
                if (passe(avant, t, 2.3)) {
                    son(j, SoundEvents.ENDER_DRAGON_GROWL, 0.5F, 1.1F);
                    son(j, SoundEvents.BLAZE_SHOOT, 1.2F, 0.5F);
                }
                if (passe(avant, t, d)) {
                    son(j, SoundEvents.GENERIC_EXPLODE.value(), 1.2F, 0.8F);
                    son(j, SoundEvents.LAVA_POP, 1.4F, 0.6F);
                    for (int i = 0; i < 6 && !loin; i++) {
                        particule(mc, ParticleTypes.EXPLOSION, pieds.add(aleat(1.2), 0.5 + HASARD.nextDouble() * 2, aleat(1.2)), 0, 0, 0);
                    }
                    for (int i = 0; i < 80 && !loin; i++) {
                        double a = HASARD.nextDouble() * Math.PI * 2, v = 0.2 + HASARD.nextDouble() * 0.35;
                        particule(mc, i % 3 == 0 ? ParticleTypes.LAVA : ParticleTypes.FLAME, pieds.add(0, 0.5 + HASARD.nextDouble() * 2, 0), Math.cos(a) * v, HASARD.nextDouble() * 0.3, Math.sin(a) * v);
                    }
                    secouer(mc, j, 1.1F);
                }
                if (t > 1.0 && t < d && !loin) {
                    particule(mc, HASARD.nextInt(3) == 0 ? ParticleTypes.LARGE_SMOKE : ParticleTypes.SMOKE, pieds.add(aleat(0.3), 3.5, aleat(0.3)), aleat(0.02), 0.12, aleat(0.02));
                    if (HASARD.nextInt(2) == 0) {
                        particule(mc, ParticleTypes.LAVA, pieds.add(aleat(0.3), 3.4, aleat(0.3)), 0, 0, 0);
                    }
                }
                if (t < 1.0 && !loin) {
                    double a = HASARD.nextDouble() * Math.PI * 2;
                    particule(mc, ParticleTypes.SMOKE, pieds.add(Math.cos(a) * 1.4, 0.2, Math.sin(a) * 1.4), 0, 0.05, 0);
                }
            }
        } else if (niveau == 2) {
            if (glace) {
                if (passe(avant, t, 0.05)) {
                    son(j, SoundEvents.AMETHYST_CLUSTER_PLACE, 1.4F, 0.6F);
                    son(j, SoundEvents.GLASS_PLACE, 1.2F, 0.7F);
                }
                if (passe(avant, t, 0.9)) {
                    son(j, SoundEvents.AMETHYST_BLOCK_RESONATE, 1.4F, 0.7F);
                }
            } else {
                if (passe(avant, t, 0.3)) {
                    son(j, SoundEvents.BLAZE_SHOOT, 1.2F, 0.6F);
                    son(j, SoundEvents.LAVA_POP, 1.4F, 0.7F);
                }
                if (passe(avant, t, 0.8)) {
                    son(j, SoundEvents.LAVA_AMBIENT, 1.6F, 0.6F);
                }
                if (t > 0.3 && t < d && !loin) {
                    for (int i = 0; i < 3; i++) {
                        double a = HASARD.nextDouble() * Math.PI * 2;
                        particule(mc, i == 0 ? ParticleTypes.LAVA : ParticleTypes.FLAME, pieds.add(Math.cos(a) * 0.95, 0.2, Math.sin(a) * 0.95), 0, 0.2 + HASARD.nextDouble() * 0.2, 0);
                    }
                }
            }
        } else {
            if (passe(avant, t, 0)) {
                son(j, glace ? SoundEvents.AMETHYST_BLOCK_CHIME : SoundEvents.LAVA_AMBIENT, 1.2F, glace ? 1.2F : 0.8F);
            }
            if (passe(avant, t, 0.5)) {
                son(j, glace ? SoundEvents.AMETHYST_CLUSTER_PLACE : SoundEvents.LAVA_POP, 1F, glace ? 1.1F : 0.8F);
            }
            if (!glace && t < d && !loin && HASARD.nextInt(2) == 0) {
                double a = HASARD.nextDouble() * Math.PI * 2;
                particule(mc, ParticleTypes.SMOKE, pieds.add(Math.cos(a) * 0.9, 0.1, Math.sin(a) * 0.9), 0, 0.06, 0);
                particule(mc, Fruit.poussiere(false, 0.5F), pieds.add(Math.cos(a) * 0.6, 0.2 + HASARD.nextDouble(), Math.sin(a) * 0.6), 0, 0.04, 0);
            }
        }
    }

    private static boolean passe(double avant, double t, double x) {
        return avant < x && t >= x;
    }

    private static void secouer(Minecraft mc, Player j, float force) {
        if (j == mc.player) {
            ClientFruit.secouer(j.position(), force, 18);
        }
    }

    static void oublier(UUID joueur) {
        AVANT.remove(joueur);
    }

    static void vider() {
        AVANT.clear();
    }
}
