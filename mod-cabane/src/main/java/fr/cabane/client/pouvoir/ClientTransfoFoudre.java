package fr.cabane.client.pouvoir;

import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

/**
 * Les transformations de la Foudre, plus longues et plus spectaculaires (docs/foudre/planche-transformations.png). Dessinées
 * dans les deux passes de ClientFoudre (le translucide, puis la lumière), avec ses éclairs ; les positions sont relatives à
 * la caméra.
 * <ul>
 * <li>Éveil 1 (1,5 s) : l'air se charge (des étincelles filent vers lui, des arcs sautent au sol), un nuage se forme, un
 * fil de foudre monte de lui au nuage, puis la vraie foudre lui tombe dessus.</li>
 * <li>Éveil 2 (2 s) : une cage d'éclairs se forme autour de lui, se resserre (il devient blanc), puis explose en dôme :
 * l'aura bleue apparaît à ce moment-là.</li>
 * <li>Éveil 3 (3,5 s) : un orage tourne au-dessus de lui et la foudre tombe autour ; une colonne de foudre le prend (il est
 * tout blanc) ; la tenue bleue se dessine de la tête aux pieds (ClientTenueFoudre) ; le tonnerre éclate.</li>
 * </ul>
 */
final class ClientTransfoFoudre {
    private static final RandomSource HASARD = RandomSource.create();
    private static final float[] BLANC = ClientFoudre.BLANC;
    private static final float[] CIEL = ClientFoudre.CIEL;
    private static final float[] NUIT = {0.12F, 0.12F, 0.17F};
    private static final float[] GRIS = {0.25F, 0.26F, 0.33F};
    /** Quand la tenue de la Foudre 3 commence à descendre sur lui, et combien de temps elle met (voir ClientTenueFoudre). */
    static final double TENUE = 2.4, TENUE_DUREE = 0.6;
    private static final Map<UUID, Double> AVANT = new HashMap<>();

    private ClientTransfoFoudre() {
    }

    /** La durée de la transformation, et combien de temps on la dessine en tout (avec ce qui suit). */
    static double duree(int niveau) {
        return niveau <= 1 ? 1.5 : niveau == 2 ? 2.0 : 3.5;
    }

    static double fin(int niveau) {
        return niveau <= 1 ? 2.2 : niveau == 2 ? 2.8 : 4.6;
    }

    /** L'aura bleue du niveau 2 (de 0 à 1) : elle n'apparaît que quand la cage explose. */
    static double aura(double t, int niveau) {
        return niveau == 2 ? ClientFoudre.lisse((t - 1.8) / 0.3) : 1;
    }

    private static double entre(double t, double a, double b) {
        return Mth.clamp((t - a) / (b - a), 0, 1);
    }

    private static double hache(int i, int j, int k) {
        return ClientFruit3.hache(i, j, k);
    }

    // ================================================================ petites formes

    private static void quad(VertexConsumer c, Matrix4f m, Vec3 a, Vec3 b, Vec3 d, Vec3 e, float[] col, double al) {
        float x = (float) Mth.clamp(al, 0, 1);
        ClientFoudre.sommet(c, m, a, col, x);
        ClientFoudre.sommet(c, m, b, col, x);
        ClientFoudre.sommet(c, m, d, col, x);
        ClientFoudre.sommet(c, m, e, col, x);
    }

    /** Un cube droit (une étincelle, un morceau de nuage), dessiné dans le translucide seulement s'il n'est pas lumineux. */
    private static void cube(VertexConsumer c, Matrix4f m, Vec3 o, double s, float[] col, double a, boolean lumineux) {
        if (!lumineux && ClientFoudre.passe != 0) {
            return;
        }
        double al = lumineux && ClientFoudre.passe == 1 ? a * 0.6 : a;
        Vec3[] p = new Vec3[8];
        for (int i = 0; i < 8; i++) {
            p[i] = o.add((i & 1) == 0 ? -s : s, (i & 2) == 0 ? -s : s, (i & 4) == 0 ? -s : s);
        }
        int[][] faces = {{2, 3, 7, 6}, {0, 1, 5, 4}, {0, 1, 3, 2}, {4, 5, 7, 6}, {0, 2, 6, 4}, {1, 3, 7, 5}};
        double[] k = {1, 0.55, 0.8, 0.8, 0.7, 0.7};
        for (int f = 0; f < 6; f++) {
            float[] cc = lumineux ? col : new float[]{(float) (col[0] * k[f]), (float) (col[1] * k[f]), (float) (col[2] * k[f])};
            quad(c, m, p[faces[f][0]], p[faces[f][1]], p[faces[f][2]], p[faces[f][3]], cc, al);
        }
    }

    /** Une coque de lumière blanche sur son corps (il devient tout blanc), dans le translucide. */
    private static void silhouette(VertexConsumer c, Matrix4f m, Vec3 pieds, double a, double lacet) {
        if (ClientFoudre.passe != 0 || a <= 0) {
            return;
        }
        double[][] pieces = {{-0.25, 1.5, -0.25, 0.25, 2.0, 0.25}, {-0.25, 0.75, -0.13, 0.25, 1.5, 0.13}, {-0.5, 0.75, -0.13, -0.25, 1.5, 0.13},
                {0.25, 0.75, -0.13, 0.5, 1.5, 0.13}, {-0.25, 0, -0.13, 0.25, 0.75, 0.13}};
        int[][] faces = {{2, 3, 7, 6}, {0, 1, 5, 4}, {0, 1, 3, 2}, {4, 5, 7, 6}, {0, 2, 6, 4}, {1, 3, 7, 5}};
        for (double[] b : pieces) {
            Vec3[] p = new Vec3[8];
            for (int i = 0; i < 8; i++) {
                double x = ((i & 1) == 0 ? b[0] : b[3]) * 1.12, y = (i & 2) == 0 ? b[1] - 0.03 : b[4] + 0.03, z = ((i & 4) == 0 ? b[2] : b[5]) * 1.25;
                p[i] = pieds.add(x * Math.cos(lacet) + z * Math.sin(lacet), y, -x * Math.sin(lacet) + z * Math.cos(lacet));
            }
            for (int[] f : faces) {
                quad(c, m, p[f[0]], p[f[1]], p[f[2]], p[f[3]], BLANC, a);
            }
        }
    }

    /** Un cylindre de lumière (la colonne de foudre), de rayon r, de bas à haut. */
    private static void colonne(VertexConsumer c, Matrix4f m, Vec3 bas, double h, double r, float[] col, double a) {
        int n = 20;
        double al = ClientFoudre.passe == 0 ? a : a * 0.5;
        for (int i = 0; i < n; i++) {
            double a0 = i * Math.PI * 2 / n, a1 = (i + 1) * Math.PI * 2 / n;
            quad(c, m, bas.add(Math.cos(a0) * r, 0, Math.sin(a0) * r), bas.add(Math.cos(a1) * r, 0, Math.sin(a1) * r),
                    bas.add(Math.cos(a1) * r, h, Math.sin(a1) * r), bas.add(Math.cos(a0) * r, h, Math.sin(a0) * r), col, al);
        }
    }

    /** Un nuage d'orage : des blocs noirs et gris en disque (qui tourne si tourne != 0), et des éclairs dedans. */
    private static void nuage(VertexConsumer c, Matrix4f m, Vec3 centre, double rayon, int n, double k, double tourne, long graine, double t) {
        Vec3[] pts = new Vec3[n];
        for (int q = 0; q < n; q++) {
            double r = Math.sqrt(hache(q, 1, (int) graine)) * rayon, ang = hache(q, 2, (int) graine) * Math.PI * 2 + tourne * t * (1.4 - r / rayon);
            Vec3 p = centre.add(Math.cos(ang) * r, (hache(q, 3, (int) graine) - 0.5) * rayon * 0.25 * (1.2 - r / rayon), Math.sin(ang) * r);
            pts[q] = p;
            double s = k * (0.35 + 0.45 * hache(q, 4, (int) graine)) * (1.15 - 0.5 * r / rayon) * (rayon / 3);
            float[] col = {(float) Mth.lerp(hache(q, 5, (int) graine), NUIT[0], GRIS[0]), (float) Mth.lerp(hache(q, 5, (int) graine), NUIT[1], GRIS[1]),
                    (float) Mth.lerp(hache(q, 5, (int) graine), NUIT[2], GRIS[2])};
            cube(c, m, p, s, col, 0.92 * k, false);
        }
        long tranche = (long) (t * 10);
        Random r = new Random(graine * 31 + tranche);
        for (int q = 0; q < 4 && k > 0.5; q++) {
            Vec3 a = pts[r.nextInt(n)], b = pts[r.nextInt(n)];
            if (a.distanceTo(b) > rayon * 0.3) {
                ClientFoudre.eclair(c, m, a.add(0, -0.3, 0), b.add(0, -0.3, 0), r.nextLong(), 0.05, (float) k, 2, CIEL);
            }
        }
    }

    /** Des éclairs qui partent de ses pieds en étoile, longs de l. */
    private static void etoile(VertexConsumer c, Matrix4f m, Vec3 pieds, int n, double l, long graine, double a) {
        Random r = new Random(graine);
        for (int k = 0; k < n; k++) {
            double ang = k * Math.PI * 2 / n + r.nextDouble() * 0.4, ll = l * (0.6 + 0.4 * r.nextDouble());
            ClientFoudre.eclair(c, m, pieds.add(Math.cos(ang) * 0.3, 0.05, Math.sin(ang) * 0.3), pieds.add(Math.cos(ang) * ll, 0.05, Math.sin(ang) * ll),
                    r.nextLong(), 0.05, (float) a, 2, CIEL);
        }
    }

    // ================================================================ dessiner

    /** Dessine sa transformation : pieds (relatifs à la caméra), t le temps depuis l'éveil. */
    static void dessiner(VertexConsumer c, Matrix4f m, Player j, Vec3 pieds, int niveau, double t, float partiel) {
        double lacet = -Mth.lerp(partiel, j.yBodyRotO, j.yBodyRot) * Mth.DEG_TO_RAD;
        if (niveau >= 3) {
            niveau3(c, m, j, pieds, t, lacet);
        } else if (niveau == 2) {
            niveau2(c, m, j, pieds, t, lacet);
        } else {
            niveau1(c, m, j, pieds, t, lacet);
        }
    }

    private static void niveau1(VertexConsumer c, Matrix4f m, Player j, Vec3 pieds, double t, double lacet) {
        long graine = j.getId() * 7919L;
        long tranche = (long) (t * 12);
        Vec3 tete = pieds.add(0, j.getBbHeight(), 0), haut = pieds.add(0.4, 8.5, -0.3);
        // l'air se charge : des étincelles filent vers lui, des arcs sautent au sol
        double kc = entre(t, 0, 0.3) * (1 - entre(t, 1.15, 1.3));
        for (int q = 0; q < 26 && kc > 0; q++) {
            double h = hache(q, 1, (int) graine), u = (t * 1.4 + h) % 1, ang = hache(q, 2, (int) graine) * Math.PI * 2, y = 0.2 + 2.2 * hache(q, 3, (int) graine);
            double r = 2.3 * (1 - u) + 0.3;
            Vec3 p = pieds.add(Math.cos(ang) * r, y * (1 - 0.3 * u) + 0.3 * u, Math.sin(ang) * r);
            cube(c, m, p, 0.025 + 0.02 * h, q % 3 == 0 ? BLANC : CIEL, kc * (0.4 + 0.6 * u), true);
            ClientFoudre.trait(c, m, List.of(p, p.add(Math.cos(ang) * 0.25, 0, Math.sin(ang) * 0.25)), 0.012, (float) (kc * 0.6), CIEL);
        }
        Random r = new Random(graine + tranche);
        for (int q = 0; q < 3 && kc > 0; q++) {
            double a0 = r.nextDouble() * Math.PI * 2, rr = 0.6 + r.nextDouble() * 1.1, a1 = a0 + 0.4 + r.nextDouble() * 0.6;
            ClientFoudre.eclair(c, m, pieds.add(Math.cos(a0) * rr, 0.05, Math.sin(a0) * rr), pieds.add(Math.cos(a1) * rr, 0.05 + r.nextDouble() * 0.3, Math.sin(a1) * rr), r.nextLong(), 0.025, (float) kc, 0, CIEL);
        }
        // un nuage se forme au-dessus de lui
        double kn = ClientFoudre.lisse(entre(t, 0.25, 0.85)) * (1 - entre(t, 1.6, 2.2));
        if (kn > 0) {
            nuage(c, m, haut, 2.6, 22, kn, 0.4, graine, t);
        }
        // un fil de foudre monte de lui au nuage
        if (t > 0.75 && t < 1.25) {
            List<Vec3> fil = ClientFoudre.zigzag(tete.add(0, 0.2, 0), haut.add(0, -0.6, 0), graine + 5, 0.22, 4);
            int n = (int) Math.max(2, fil.size() * entre(t, 0.75, 1.15));
            ClientFoudre.trait(c, m, fil.subList(0, Math.min(fil.size(), n)), 0.025, 0.8F, CIEL);
        }
        // puis la vraie foudre lui tombe dessus
        if (t >= 1.2) {
            double te = t - 1.2, f = 1 - entre(te, 0, 0.75);
            if (f > 0) {
                ClientFoudre.eclair(c, m, haut.add(0, -0.6, 0), tete, graine + 9 + (long) (te * 14), 0.17, (float) f, 7, CIEL);
                ClientFoudre.anneau(c, m, pieds.add(0, 0.05, 0), 0.3 + te * 6, 1.1, BLANC, (float) (0.7 * f));
                etoile(c, m, pieds, 8, 2.6, graine + (long) (te * 12), f);
                silhouette(c, m, pieds, 0.55 * (1 - entre(te, 0, 0.3)), lacet);
                ClientFoudre.dome(c, m, pieds, 0.6 + 1.6 * entre(te, 0, 0.3), (float) (0.6 * (1 - entre(te, 0, 0.5))));
            }
        }
    }

    /** La cage d'éclairs : des méridiens qui montent du sol (jusqu'à monte, de 0 à 1) sur une boule de rayon R. */
    private static void cage(VertexConsumer c, Matrix4f m, Vec3 pieds, double R, double monte, long graine, double a) {
        Vec3 centre = pieds.add(0, 1.0, 0);
        for (int k = 0; k < 8; k++) {
            double p = k * Math.PI * 2 / 8 + 0.2 * hache(k, 1, (int) graine);
            int n = 10;
            Vec3 avant = null;
            for (int i = 0; i <= n * monte; i++) {
                double th = Math.PI - Math.PI * i / n;
                Vec3 q = centre.add(R * Math.sin(th) * Math.cos(p), Math.max(-0.95, R * Math.cos(th)), R * Math.sin(th) * Math.sin(p));
                if (avant != null) {
                    ClientFoudre.trait(c, m, ClientFoudre.zigzag(avant, q, graine * 100 + k * 13 + i, 0.25, 2), 0.03, (float) a, CIEL);
                }
                avant = q;
            }
        }
        if (monte > 0.6) {
            for (double th : new double[]{0.9, 1.9}) {
                Vec3 avant = null;
                for (int i = 0; i <= 16; i++) {
                    double p = i * Math.PI * 2 / 16;
                    Vec3 q = centre.add(R * Math.sin(th) * Math.cos(p), R * Math.cos(th), R * Math.sin(th) * Math.sin(p));
                    if (avant != null) {
                        ClientFoudre.trait(c, m, ClientFoudre.zigzag(avant, q, graine * 300 + i + (long) (th * 50), 0.25, 2), 0.022, (float) (a * (monte - 0.6) / 0.4), CIEL);
                    }
                    avant = q;
                }
            }
        }
    }

    private static void niveau2(VertexConsumer c, Matrix4f m, Player j, Vec3 pieds, double t, double lacet) {
        long graine = j.getId() * 104729L, tranche = (long) (t * 12);
        if (t < 1.85) {
            // la cage se forme, puis se resserre ; il devient tout blanc
            double R = 1.75 - 0.85 * ClientFoudre.lisse(entre(t, 1.2, 1.75));
            cage(c, m, pieds, R, ClientFoudre.lisse(entre(t, 0, 1.0)), graine + tranche, 1);
            Random r = new Random(graine + tranche);
            for (int q = 0; q < 6 && t < 1.2; q++) {
                double a = q * Math.PI * 2 / 6 + r.nextDouble() * 0.5;
                ClientFoudre.eclair(c, m, pieds.add(Math.cos(a) * 2.4, 0.05, Math.sin(a) * 2.4), pieds.add(Math.cos(a) * R * 0.9, 0.4, Math.sin(a) * R * 0.9), r.nextLong(), 0.03, 0.9F, 0, CIEL);
            }
            silhouette(c, m, pieds, 0.6 * entre(t, 1.15, 1.7), lacet);
        } else {
            // elle explose en dôme, des éclairs filent au sol en étoile
            double te = t - 1.85, f = 1 - entre(te, 0, 0.9);
            ClientFoudre.dome(c, m, pieds, 0.6 + 4.6 * ClientFoudre.lisse(entre(te, 0, 0.35)), (float) (0.8 * f));
            ClientFoudre.anneau(c, m, pieds.add(0, 0.05, 0), 0.5 + 6 * entre(te, 0, 0.6), 1.4, BLANC, (float) (0.8 * f));
            etoile(c, m, pieds, 12, 4.2, graine + (long) (te * 12), f);
            silhouette(c, m, pieds, 0.6 * (1 - entre(te, 0, 0.25)), lacet);
        }
    }

    private static void niveau3(VertexConsumer c, Matrix4f m, Player j, Vec3 pieds, double t, double lacet) {
        long graine = j.getId() * 15485863L, tranche = (long) (t * 12);
        Vec3 tete = pieds.add(0, j.getBbHeight(), 0), ciel = pieds.add(0, 10, 0);
        // l'orage qui tourne au-dessus de lui
        double ko = ClientFoudre.lisse(entre(t, 0, 0.6)) * (1 - entre(t, 3.1, 4.3));
        if (ko > 0) {
            nuage(c, m, ciel, 5.5, 60, ko, 0.9, graine, t);
            ClientFoudre.anneau(c, m, ciel.add(0, -0.8, 0), 0.8 + 0.3 * Math.sin(t * 6), 1.4, CIEL, (float) (0.6 * ko));
        }
        // la foudre tombe autour de lui
        double[][] coups = {{0.3, 0.6, 4.2}, {0.6, 2.8, 3.6}, {0.9, 4.6, 4.8}, {1.15, 1.6, 3.2}};
        for (double[] k : coups) {
            double te = t - k[0];
            if (te >= 0 && te < 0.4) {
                Vec3 sol = pieds.add(Math.cos(k[1]) * k[2], 0.05, Math.sin(k[1]) * k[2]);
                ClientFoudre.eclair(c, m, ciel.add(Math.cos(k[1]) * 2, -0.5, Math.sin(k[1]) * 2), sol, graine + (long) (k[1] * 100) + (long) (te * 14), 0.12, (float) (1 - te / 0.4), 5, CIEL);
                ClientFoudre.anneau(c, m, sol, 0.2 + te * 4, 0.8, BLANC, (float) (0.7 * (1 - te / 0.4)));
            }
        }
        // la colonne de foudre le prend : il est tout blanc dans la lumière
        double kc = entre(t, 1.0, 1.25) * (1 - entre(t, 2.9, 3.15));
        if (kc > 0) {
            Random r = new Random(graine + tranche);
            for (int q = 0; q < 4; q++) {
                ClientFoudre.eclair(c, m, ciel.add((q - 1.5) * 0.25, -0.8, (q % 2) * 0.3 - 0.15), tete, r.nextLong(), q == 0 ? 0.2 : 0.08, (float) kc, 3, CIEL);
            }
            double serre = 1 - 0.5 * entre(t, 2.4, 3.0);
            colonne(c, m, pieds, 9.3, 0.95 * serre, BLANC, 0.16 * kc);
            colonne(c, m, pieds, 9.3, 0.5 * serre, BLANC, 0.28 * kc);
            for (int k = 0; k < 4; k++) {
                double h = (k * 0.7 + t * 1.2) % 2.8;
                ClientFoudre.anneau(c, m, pieds.add(0, h, 0), 0.75, 0.35, CIEL, (float) (0.6 * kc));
            }
            silhouette(c, m, pieds, 0.7 * kc * (1 - entre(t, 2.4, 2.7)), lacet);
        }
        // la tenue se dessine de la tête aux pieds : un anneau d'éclairs à la hauteur où elle en est
        if (t > TENUE && t < TENUE + TENUE_DUREE + 0.1) {
            double h = j.getBbHeight() * (1 - entre(t, TENUE, TENUE + TENUE_DUREE));
            Random r = new Random(graine * 3 + tranche);
            Vec3 avant = null, premier = null;
            for (int q = 0; q <= 16; q++) {
                double a = q * Math.PI * 2 / 16;
                Vec3 p = q == 16 ? premier : pieds.add(Math.cos(a) * 0.62 * (0.9 + 0.2 * r.nextDouble()), h + (r.nextDouble() - 0.5) * 0.15, Math.sin(a) * 0.5 * (0.9 + 0.2 * r.nextDouble()));
                if (premier == null) {
                    premier = p;
                }
                if (avant != null) {
                    ClientFoudre.trait(c, m, ClientFoudre.zigzag(avant, p, r.nextLong(), 0.3, 2), 0.03, 1F, CIEL);
                }
                avant = p;
            }
            ClientFoudre.anneau(c, m, pieds.add(0, h, 0), 0.45, 0.6, CIEL, 0.6F);
        }
        // le tonnerre éclate
        if (t >= 3.0) {
            double te = t - 3.0, f = 1 - entre(te, 0, 1.2);
            if (te < 0.45) {
                ClientFoudre.eclair(c, m, ciel.add(0.3, -0.5, -0.2), tete, graine + 77 + (long) (te * 14), 0.24, (float) (1 - te / 0.45), 9, CIEL);
            }
            ClientFoudre.dome(c, m, pieds, 0.8 + 3.6 * ClientFoudre.lisse(entre(te, 0, 0.35)), (float) (0.7 * (1 - entre(te, 0, 0.8))));
            ClientFoudre.anneau(c, m, pieds.add(0, 0.05, 0), 0.6 + 8 * entre(te, 0, 0.9), 1.8, BLANC, (float) (0.85 * f));
            ClientFoudre.anneau(c, m, pieds.add(0, 0.07, 0), 0.4 + 4 * entre(te, 0, 0.6), 0.9, CIEL, (float) (0.7 * f));
            etoile(c, m, pieds, 16, 5.5, graine + (long) (te * 12), f);
            for (int k = 0; k < 3; k++) {
                double tk = te - k * 0.08;
                if (tk >= 0 && tk < 0.4) {
                    double a = k * 2.1 + 0.5;
                    Vec3 sol = pieds.add(Math.cos(a) * 5.2, 0.05, Math.sin(a) * 5.2);
                    ClientFoudre.eclair(c, m, ciel.add(Math.cos(a) * 3, -0.5, Math.sin(a) * 3), sol, graine + 300 + k + (long) (tk * 14), 0.12, (float) (1 - tk / 0.4), 5, CIEL);
                }
            }
        }
    }

    // ================================================================ les sons et les particules

    private static void son(Player j, SoundEvent son, float volume, float hauteur) {
        j.level().playLocalSound(j.getX(), j.getY() + 1, j.getZ(), son, SoundSource.PLAYERS, volume, hauteur, false);
    }

    private static boolean passe(double avant, double t, double x) {
        return avant < x && t >= x;
    }

    private static void etincelles(Minecraft mc, Vec3 o, int n, double v) {
        for (int i = 0; i < n; i++) {
            mc.level.addParticle(ParticleTypes.ELECTRIC_SPARK, o.x + HASARD.nextGaussian() * 0.3, o.y + HASARD.nextDouble() * 1.8, o.z + HASARD.nextGaussian() * 0.3,
                    HASARD.nextGaussian() * v, HASARD.nextDouble() * v, HASARD.nextGaussian() * v);
        }
    }

    /** À chaque tick pendant sa transformation : les sons (une fois chacun), les étincelles, l'écran qui tremble pour lui. */
    static void tick(Minecraft mc, Player j, int niveau, double t) {
        UUID id = j.getUUID();
        double avant = AVANT.getOrDefault(id, -1.0);
        if (avant > t) {
            avant = -1;
        }
        AVANT.put(id, t);
        boolean moi = j == mc.player;
        Vec3 p = j.position();
        if (niveau >= 3) {
            if (passe(avant, t, 0)) {
                son(j, SoundEvents.LIGHTNING_BOLT_THUNDER, 1.6F, 0.5F);
            }
            for (double x : new double[]{0.3, 0.6, 0.9, 1.15}) {
                if (passe(avant, t, x)) {
                    son(j, SoundEvents.LIGHTNING_BOLT_IMPACT, 1F, 1.1F + (float) x * 0.3F);
                }
            }
            if (passe(avant, t, 1.0)) {
                son(j, SoundEvents.BEACON_POWER_SELECT, 1.2F, 1.4F);
                son(j, SoundEvents.LIGHTNING_BOLT_IMPACT, 1.6F, 0.7F);
                if (moi) {
                    ClientFoudre.secouer(0.6F);
                }
            }
            if (passe(avant, t, TENUE)) {
                son(j, SoundEvents.BEACON_ACTIVATE, 1.2F, 1.8F);
            }
            if (passe(avant, t, 3.0)) {
                son(j, SoundEvents.LIGHTNING_BOLT_THUNDER, 2F, 0.8F);
                son(j, SoundEvents.LIGHTNING_BOLT_IMPACT, 2F, 0.6F);
                son(j, SoundEvents.GENERIC_EXPLODE.value(), 0.6F, 1.6F);
                etincelles(mc, p, 80, 0.5);
                if (moi) {
                    ClientFoudre.secouer(1.3F);
                }
            }
            if (t > 1.0 && t < 3.0 && HASARD.nextInt(2) == 0) {
                mc.level.addParticle(ParticleTypes.END_ROD, p.x + HASARD.nextGaussian() * 0.4, p.y + HASARD.nextDouble() * 2.5, p.z + HASARD.nextGaussian() * 0.4, 0, 0.15, 0);
            }
        } else if (niveau == 2) {
            if (passe(avant, t, 0.1)) {
                son(j, SoundEvents.BEACON_POWER_SELECT, 1F, 1.6F);
            }
            if (passe(avant, t, 1.2)) {
                son(j, SoundEvents.RESPAWN_ANCHOR_CHARGE, 1.2F, 1.5F);
            }
            if (passe(avant, t, 1.85)) {
                son(j, SoundEvents.LIGHTNING_BOLT_IMPACT, 1.4F, 0.7F);
                son(j, SoundEvents.LIGHTNING_BOLT_THUNDER, 1.2F, 1.3F);
                etincelles(mc, p, 60, 0.4);
                if (moi) {
                    ClientFoudre.secouer(1F);
                }
            }
            if (t < 1.85) {
                etincelles(mc, p, 1, 0.05);
            }
        } else {
            if (t < 1.2 && HASARD.nextInt(2) == 0) {
                etincelles(mc, p, 1, 0.05);
            }
            if (passe(avant, t, 0.8)) {
                son(j, SoundEvents.BEACON_POWER_SELECT, 0.8F, 1.8F);
            }
            if (passe(avant, t, 1.2)) {
                son(j, SoundEvents.LIGHTNING_BOLT_IMPACT, 1.4F, 1F);
                son(j, SoundEvents.LIGHTNING_BOLT_THUNDER, 0.8F, 1.3F);
                etincelles(mc, p, 40, 0.3);
                if (moi) {
                    ClientFoudre.secouer(0.6F);
                }
            }
        }
    }

    static void oublier(UUID joueur) {
        AVANT.remove(joueur);
    }
}
