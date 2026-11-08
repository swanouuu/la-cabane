package fr.cabane.client.pouvoir;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import fr.cabane.Cabane;
import fr.cabane.pouvoir.Pouvoirs.Couleur;
import fr.cabane.pouvoir.Susano3;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Le Susano niveau 3, le chevalier, d'après les maquettes validées (docs/susano3). Le squelette du niveau 2 se couvre de
 * fibres puis de chair, l'armure se pose pièce par pièce, le heaume se ferme (pointu, visière en bec, long nez en
 * espadon, fente des yeux, panache de flammes), la cape tombe, et l'épée de flammes et le bouclier se forment. Le
 * joueur rit comme un fou pendant ce temps. Clic gauche : deux coups d'épée qui s'enchaînent (de droite à gauche, puis
 * de gauche à droite), avec une traînée ; clic droit : il tend un grand arc hérissé de flammes et tire une flèche aux
 * flammes noires (Amaterasu), qui restent sur ce qu'elle touche. Tout est en blocs de chakra, comme le reste du Susano.
 * Repère du chevalier : les pieds à l'origine, il regarde vers +Z, sa gauche est en +X (comme {@link ClientSusano}).
 */
public final class ClientSusano3 {
    /** Où en est la transformation (de 0 à 1 chacun), d'après le temps depuis le passage au niveau 3. */
    record Forme(float taille, float fibres, float chair, float armure, float casque, float armes, float cape, float rire) {
    }

    static Forme forme(double t3) {
        return new Forme(l(t3 / 1.2), l((t3 - 0.4) / 1.2), l((t3 - 1.3) / 0.7), l((t3 - 1.9) / 1.1), l((t3 - 2.6) / 0.4), l((t3 - 3.0) / 0.5),
                l((t3 - 2.5) / 0.8), l(t3 / 0.3) * (1 - l((t3 - 3.3) / 0.5)));
    }

    /**
     * Le dernier geste : le coup d'épée (temps relatif de 0 à 1, et de quel côté), le tir (secondes depuis qu'il tend
     * l'arc), le grand coup (secondes depuis qu'il lève l'épée) ; -1 quand il n'y en a pas.
     */
    record Gestes(double coup, int cote, double tir, double grand) {
    }

    private static float l(double x) {
        x = Mth.clamp(x, 0, 1);
        return (float) (x * x * (3 - 2 * x));
    }

    // ================================================================ les vecteurs (en double[3])

    private static double[] v(double x, double y, double z) {
        return new double[] {x, y, z};
    }

    private static double[] add(double[] a, double[] b) {
        return v(a[0] + b[0], a[1] + b[1], a[2] + b[2]);
    }

    private static double[] sub(double[] a, double[] b) {
        return v(a[0] - b[0], a[1] - b[1], a[2] - b[2]);
    }

    private static double[] mul(double[] a, double k) {
        return v(a[0] * k, a[1] * k, a[2] * k);
    }

    private static double dot(double[] a, double[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }

    private static double[] cross(double[] a, double[] b) {
        return v(a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]);
    }

    private static double len(double[] a) {
        return Math.sqrt(dot(a, a));
    }

    private static double[] norm(double[] a) {
        double l = len(a);
        return l < 1e-9 ? v(0, 1, 0) : mul(a, 1 / l);
    }

    private static double[] lerp(double[] a, double[] b, double t) {
        return add(a, mul(sub(b, a), t));
    }

    /** Un bruit fixe entre 0 et 1 (le même que dans les maquettes). */
    private static double hache(int i, int j, int k) {
        int h = i * 374761393 + j * 668265263 + k * 2147483647;
        h = (h ^ (h >>> 13)) * 1274126177;
        return ((h ^ (h >>> 16)) & 0xFFFFFFFFL) / 4294967296.0;
    }

    private static final double[][] DROIT = {{1, 0, 0}, {0, 1, 0}, {0, 0, 1}};

    /** Les axes d'une boîte allongée le long de d. */
    private static double[][] axesLe(double[] d, double[] indice) {
        double[] y = norm(d), x = cross(y, indice);
        if (len(x) < 0.2) {
            x = cross(y, v(1, 0, 0));
        }
        x = norm(x);
        return new double[][] {x, y, cross(x, y)};
    }

    private static double[][] axesLe(double[] d) {
        return axesLe(d, v(0, 0, 1));
    }

    private static double[][] tourneZ(double a) {
        return new double[][] {{Math.cos(a), -Math.sin(a), 0}, {Math.sin(a), Math.cos(a), 0}, {0, 0, 1}};
    }

    private static double[][] tourneY(double a) {
        return new double[][] {{Math.cos(a), 0, -Math.sin(a)}, {0, 1, 0}, {Math.sin(a), 0, Math.cos(a)}};
    }

    // ================================================================ ajouter des boîtes de chakra

    /** Ce qu'on construit : la liste des os (boîtes) du Susano, et le repère courant. */
    private record Chantier(List<ClientSusano.Os> liste, PoseStack ps) {
        /** Une boîte orientée : son centre, ses axes [côté, haut, avant], ses demi-tailles. */
        void boite(double[] c, double[][] axes, double hx, double hy, double hz, int genre, float k) {
            if (k <= 0.02F) {
                return;
            }
            float f = Math.min(1, 0.3F + 0.7F * k);
            ps.pushPose();
            ps.translate(c[0], c[1], c[2]);
            Matrix3f m = new Matrix3f((float) axes[0][0], (float) axes[0][1], (float) axes[0][2], (float) axes[1][0], (float) axes[1][1],
                    (float) axes[1][2], (float) axes[2][0], (float) axes[2][1], (float) axes[2][2]);
            ps.mulPose(new Quaternionf().setFromNormalized(m));
            liste.add(new ClientSusano.Os(ps.last().copy(), (float) -hx * f, (float) -hy * f, (float) -hz * f, (float) hx * f, (float) hy * f,
                    (float) hz * f, k, genre));
            ps.popPose();
        }

        void boite(double[] c, double hx, double hy, double hz, int genre, float k) {
            boite(c, DROIT, hx, hy, hz, genre, k);
        }

        /** Une boîte tendue de a à b (largeur, épaisseur). */
        void seg(double[] a, double[] b, double larg, double ep, int genre, float k, double[] indice) {
            double[] d = sub(b, a);
            double l = len(d);
            if (l < 0.02) {
                return;
            }
            boite(lerp(a, b, 0.5), axesLe(d, indice), larg / 2, l / 2, ep / 2, genre, k);
        }

        void seg(double[] a, double[] b, double larg, double ep, int genre, float k) {
            seg(a, b, larg, ep, genre, k, v(0, 0, 1));
        }

        /** Un ruban plat (une flamme) le long de pts, en fines boîtes ; il fait face à l'indice. */
        void ruban(List<double[]> pts, java.util.function.IntToDoubleFunction larg, double[] indice, int genre, float k) {
            for (int i = 0; i + 1 < pts.size(); i++) {
                double[] a = pts.get(i), b = pts.get(i + 1), d = sub(b, a);
                double l = len(d);
                if (l < 0.01) {
                    continue;
                }
                double[] y = norm(d), x = norm(cross(y, indice)), z = cross(x, y);
                boite(lerp(a, b, 0.5), new double[][] {x, y, z}, (larg.applyAsDouble(i) + larg.applyAsDouble(i + 1)) / 2, l / 2 + 0.02, 0.025, genre, k);
            }
        }
    }

    // ================================================================ les poses des bras

    /** Un bras : coude, poignet ; pour le droit, la direction de la lame. */
    private record Bras(double[] e, double[] w, double[] lame) {
        Bras vers(Bras b, double k) {
            double f = l(k);
            return new Bras(lerp(e, b.e, f), lerp(w, b.w, f), lame == null || b.lame == null ? b.lame : norm(lerp(lame, b.lame, f)));
        }
    }

    static final double[] EPAULE_D = {-2.15, 4.45, -0.2}, EPAULE_G = {2.15, 4.45, -0.2};
    private static final Bras REPOS_D = new Bras(v(-3.1, 2.9, 0.6), v(-3.4, 3.3, 1.9), norm(v(-0.22, 0.96, 0.18)));
    private static final Bras ARME_D = new Bras(v(-3.0, 4.6, -0.4), v(-3.3, 5.5, 0.4), norm(v(0.1, 0.5, -1)));
    private static final Bras COUP_1 = new Bras(v(-2.6, 3.9, 1.6), v(-1.1, 3.6, 2.6), norm(v(1, -0.06, 0.32)));
    private static final Bras SUITE_1 = new Bras(v(-1.3, 3.7, 1.4), v(0.5, 3.4, 2.1), norm(v(0.8, -0.15, -0.55)));
    private static final Bras COUP_2 = new Bras(v(-1.6, 3.75, 1.45), v(-3.0, 3.5, 2.5), norm(v(-1, 0.08, 0.38)));
    private static final Bras SUITE_2 = new Bras(v(-3.2, 3.5, 0.7), v(-4.2, 3.2, 1.2), norm(v(-0.6, -0.2, -0.75)));
    /** Le grand coup : l'épée levée bien haut derrière la tête, puis abattue devant lui jusqu'au sol. */
    private static final Bras LEVE = new Bras(v(-2.2, 6.2, 0.2), v(-0.9, 7.5, 0.7), norm(v(0.15, 0.45, -1)));
    private static final Bras ABATTU = new Bras(v(-1.8, 4.0, 2.4), v(-0.6, 2.8, 3.6), norm(v(0.05, -0.75, 1)));
    static final double LEVER = 0.35, IMPACT = Susano3.IMPACT_GRAND / 20.0, DUREE_GRAND = 1.4;
    private static final Bras ARC_D = new Bras(v(-2.5, 4.9, -1.3), v(-0.55, 4.75, 0.1), null);
    private static final Bras REPOS_G = new Bras(v(2.9, 2.85, 0.35), v(1.55, 2.95, 1.85), null);
    private static final Bras RECUL_G = new Bras(v(2.95, 3.2, -0.6), v(2.7, 2.6, 0.9), null);
    private static final Bras ARC_G = new Bras(v(2.25, 4.55, 1.9), v(0.75, 4.6, 3.7), null);

    /** Le temps de tendre l'arc (comme sur le serveur), puis il tient, puis il range l'arc. */
    static final double TENSION = Susano3.TENSION / 20.0;

    /** Les deux bras à cet instant, et combien l'arc (ou le bouclier) est là. */
    private record Posture(Bras d, Bras g, float arc) {
    }

    private static Posture posture(Gestes g, double t) {
        // au repos, les bras respirent un peu
        double r = Math.sin(t * 1.3) * 0.08;
        Bras d = new Bras(add(REPOS_D.e, v(0, r, 0)), add(REPOS_D.w, v(0, r, 0)), REPOS_D.lame), gauche = new Bras(add(REPOS_G.e, v(0, -r, 0)),
                add(REPOS_G.w, v(0, -r, 0)), null);
        float arc = 0;
        if (g.tir() >= 0 && g.tir() < TENSION + 0.8) {
            double k = g.tir();
            double entre = Math.min(1, k / 0.3), sortie = k < TENSION + 0.4 ? 0 : (k - TENSION - 0.4) / 0.4;
            arc = l(k / 0.2) * (1 - l(sortie));
            d = d.vers(ARC_D, entre * (1 - sortie));
            gauche = gauche.vers(ARC_G, entre * (1 - sortie));
            // la corde lâchée : la main droite revient un peu en avant
            if (k > TENSION && k < TENSION + 0.4) {
                d = new Bras(d.e, add(d.w, mul(norm(sub(ARC_G.w, ARC_D.w)), 0.45 * l((k - TENSION) / 0.06))), d.lame);
            }
        } else if (g.grand() >= 0 && g.grand() < DUREE_GRAND) {
            double k = g.grand();
            d = k < LEVER ? d.vers(LEVE, k / LEVER) : k < IMPACT ? LEVE.vers(ABATTU, (k - LEVER) / (IMPACT - LEVER))
                    : k < 0.9 ? ABATTU : ABATTU.vers(d, (k - 0.9) / (DUREE_GRAND - 0.9));
            gauche = gauche.vers(RECUL_G, k < LEVER ? k / LEVER : k < 0.9 ? 1 : 1 - (k - 0.9) / (DUREE_GRAND - 0.9));
        } else if (g.coup() < 1) {
            double k = g.coup();
            Bras arme = g.cote() == 0 ? ARME_D : SUITE_1, coup = g.cote() == 0 ? COUP_1 : COUP_2, suite = g.cote() == 0 ? SUITE_1 : SUITE_2;
            d = k < 0.2 ? d.vers(arme, k / 0.2) : k < 0.4 ? arme.vers(coup, (k - 0.2) / 0.2) : k < 0.55 ? coup.vers(suite, (k - 0.4) / 0.15)
                    : suite.vers(d, (k - 0.55) / 0.45);
            gauche = gauche.vers(RECUL_G, k < 0.3 ? k / 0.3 : 1 - (k - 0.3) / 0.7);
        }
        return new Posture(d, gauche, arc);
    }

    // ================================================================ le chevalier

    /**
     * Ajoute le chevalier à la liste, dans le repère courant (les pieds à l'origine). Le squelette du niveau 2 est dessiné
     * à part (il s'efface sous l'armure).
     */
    static void chevalier(List<ClientSusano.Os> liste, PoseStack ps, Forme e, Couleur couleur, Gestes gestes, float lacetTete, float tangageTete,
                          double t) {
        Chantier c = new Chantier(liste, ps);
        Posture p = posture(gestes, t);
        ps.pushPose();
        float k = 0.72F + 0.28F * e.taille();
        ps.scale(k, k, k);
        // ---- la chair (les fibres d'abord)
        List<double[][]> muscles = new ArrayList<>();
        muscles.add(new double[][] {v(0, 3.75, -0.1), {1.75, 0.95, 1.1}});
        muscles.add(new double[][] {v(0, 2.45, -0.12), {1.05, 0.7, 0.85}});
        muscles.add(new double[][] {v(0, 1.55, -0.12), {1.2, 0.35, 0.88}});
        muscles.add(new double[][] {v(0, 4.95, -0.3), {0.45, 0.35, 0.45}});
        List<double[][][]> membres = new ArrayList<>();
        for (int cote = -1; cote <= 1; cote += 2) {
            double[] s = cote < 0 ? EPAULE_D : EPAULE_G;
            Bras b = cote < 0 ? p.d() : p.g();
            muscles.add(new double[][] {add(s, v(cote * 0.08, 0.05, 0)), {0.62, 0.58, 0.62}});
            membres.add(new double[][][] {{lerp(s, b.e(), 0.5)}, axesLe(sub(b.e(), s)), {{0.42, len(sub(b.e(), s)) / 2 + 0.2, 0.42}}});
            membres.add(new double[][][] {{lerp(b.e(), b.w(), 0.5)}, axesLe(sub(b.w(), b.e())), {{0.38, len(sub(b.w(), b.e())) / 2 + 0.12, 0.38}}});
            membres.add(new double[][][] {{add(b.w(), mul(norm(sub(b.w(), b.e())), 0.32))}, axesLe(sub(b.w(), b.e())), {{0.36, 0.36, 0.2}}});
        }
        int n = 0;
        for (double[][] m : muscles) {
            muscle(c, m[0], DROIT, m[1], e, n++);
        }
        for (double[][][] m : membres) {
            muscle(c, m[0][0], m[1], m[2][0], e, n++);
        }
        // ---- l'armure, pièce par pièce
        armure(c, e, p);
        // ---- la cape : enlevée (pas de cape sur les pouvoirs, demandé le 2026-11-08)
        if (false && e.cape() > 0) {
            for (int i = 0; i < 9; i++) {
                double x = -1.8 + i * 0.45, bas = 0.15 + hache(i, 3, 7) * 1.1;
                double[] prec = v(x * 1.05, 4.7, -1.45);
                for (int j = 1; j <= 6; j++) {
                    double[] q = v(x * (1.05 + j * 0.06), 4.7 - (4.7 - bas) * (j / 6.0) * e.cape(), -1.45 - j * 0.16 - 0.25 * Math.sin(j * 0.9 + i * 1.3 + t * 3));
                    c.seg(prec, q, 0.47, 0.08, ClientSusano.CAPE, e.cape());
                    prec = q;
                }
            }
        }
        // ---- le bas du corps : des flammes de chakra qui montent du sol jusqu'à la taille
        if (e.chair() > 0.2F) {
            for (int i = 0; i < 16; i++) {
                double a = i / 16.0 * Math.PI * 2, r = 1.0 + 0.18 * Math.sin(i * 1.7), h = 0.9 + 0.6 * hache(i, 1, 2) + 0.25 * Math.sin(t * 6 + i);
                c.seg(v(Math.sin(a) * r, 0.05, Math.cos(a) * r - 0.12), v(Math.sin(a) * r * 0.85, h, Math.cos(a) * r * 0.85 - 0.12), 0.38, 0.38,
                        ClientSusano.VAPEUR, e.chair());
            }
        }
        // ---- la tête : de chair au début, puis le heaume ; elle suit le regard du joueur
        ps.pushPose();
        ps.translate(0, 4.95, -0.3);
        ps.mulPose(Axis.YP.rotationDegrees(-lacetTete));
        ps.mulPose(Axis.XP.rotationDegrees(tangageTete));
        ps.translate(0, -4.95, 0.3);
        if (e.casque() < 0.6F) {
            muscle(c, v(0, 5.85, -0.15), DROIT, new double[] {0.62, 0.68, 0.62}, e, 99);
            if (e.chair() > 0.3F && e.casque() < 0.95F) {
                float kv = e.chair() * (1 - e.casque());
                for (int s = -1; s <= 1; s += 2) {
                    c.boite(v(s * 0.32, 6.0, 0.62), 0.17, 0.09, 0.05, ClientSusano.OEIL, kv);
                }
                c.boite(v(0, 5.4, 0.62), 0.42, 0.06, 0.05, ClientSusano.SOMBRE, kv);
            }
        }
        if (e.casque() > 0) {
            heaume(c, couleur, e.casque());
        }
        ps.popPose();
        // ---- les armes
        if (e.armes() > 0) {
            float epee = e.armes() * (1 - p.arc());
            if (epee > 0.02F && p.d().lame() != null) {
                epee(c, p.d().w(), p.d().lame(), epee);
            }
            if (e.armes() * (1 - p.arc()) > 0.02F) {
                bouclier(c, p.g(), e.armes() * (1 - p.arc()));
            }
            if (p.arc() > 0.02F) {
                arc(c, p, p.arc(), gestes.tir() < TENSION);
            }
        }
        ps.popPose();
    }

    /** Un muscle : sa chair, et pendant la transformation des fibres qui courent dessus de bas en haut. */
    private static void muscle(Chantier c, double[] centre, double[][] ax, double[] h, Forme e, int n) {
        if (e.chair() > 0) {
            c.boite(centre, ax, h[0], h[1], h[2], ClientSusano.CHAIR, e.chair());
        }
        if (e.fibres() <= 0 || e.chair() >= 0.98F) {
            return;
        }
        for (int k = 0; k < 10; k++) {
            double a = k / 10.0 * Math.PI * 2 + n;
            double ox = Math.cos(a) * h[0] * 1.04, oz = Math.sin(a) * h[2] * 1.04;
            float pousse = l((e.fibres() - hache(n, k, 5) * 0.45) / 0.5);
            if (pousse <= 0) {
                continue;
            }
            double[] bas = add(add(centre, add(mul(ax[0], ox), mul(ax[2], oz))), mul(ax[1], -h[1] * 1.02));
            double[] haut = add(bas, mul(ax[1], 2 * h[1] * 1.02 * pousse));
            c.seg(bas, haut, 0.11, 0.07, ClientSusano.FIBRE, 1 - e.chair() * 0.9F, ax[1]);
        }
    }

    private static void armure(Chantier c, Forme e, Posture p) {
        List<java.util.function.Consumer<Float>> pieces = new ArrayList<>();
        int A = ClientSusano.ARMURE, LAME = ClientSusano.LAME;
        pieces.add(k -> {
            // la cuirasse : large en haut, plus étroite en bas (en V), une arête au milieu
            c.boite(v(0, 4.15, 1.0), 1.8, 0.55, 0.16, A, k);
            c.boite(v(0, 3.35, 0.95), 1.3, 0.3, 0.16, A, k);
            c.boite(v(0, 3.8, 1.18), 0.09, 0.85, 0.06, LAME, k);
        });
        pieces.add(k -> {
            c.boite(v(0, 4.1, -1.25), 1.8, 0.6, 0.16, A, k);
            c.boite(v(0, 3.35, -1.2), 1.3, 0.3, 0.16, A, k);
        });
        for (int cote = -1; cote <= 1; cote += 2) {
            int s = cote;
            pieces.add(k -> {
                double[] ep = s < 0 ? EPAULE_D : EPAULE_G;
                double[][] plaques = {{0.42, 0.85, 0.9, 25}, {0.05, 0.75, 0.8, 42}};
                for (int i = 0; i < 2; i++) {
                    double[] pl = plaques[i];
                    c.boite(add(ep, v(s * (0.15 + i * 0.25), pl[0], 0)), tourneZ(s * pl[3] * Mth.DEG_TO_RAD), pl[1], 0.17, pl[2], A, k);
                }
                c.seg(add(ep, v(s * 0.2, 0.6, 0)), add(ep, v(s * 0.6, 1.35, -0.2)), 0.2, 0.2, A, k);
            });
        }
        pieces.add(k -> {
            for (int a = 0; a < 360; a += 60) {
                double r = a * Mth.DEG_TO_RAD;
                c.boite(v(Math.sin(r) * 0.58, 4.95, -0.3 + Math.cos(r) * 0.58), tourneY(-r), 0.33, 0.26, 0.08, A, k);
            }
        });
        pieces.add(k -> {
            c.boite(v(0, 2.0, -0.12), 1.15, 0.16, 0.9, A, k);
            c.boite(v(0, 2.0, 0.8), 0.24, 0.22, 0.06, LAME, k);
        });
        for (int cote = -1; cote <= 1; cote += 2) {
            Bras b = cote < 0 ? p.d() : p.g();
            double[] ep = cote < 0 ? EPAULE_D : EPAULE_G;
            pieces.add(k -> {
                c.boite(lerp(b.e(), b.w(), 0.55), axesLe(sub(b.w(), b.e())), 0.46, len(sub(b.w(), b.e())) * 0.36, 0.46, A, k);
                c.boite(add(b.w(), mul(norm(sub(b.w(), b.e())), 0.36)), axesLe(sub(b.w(), b.e())), 0.4, 0.38, 0.24, A, k);
                c.boite(lerp(ep, b.e(), 0.55), axesLe(sub(b.e(), ep)), 0.5, len(sub(b.e(), ep)) * 0.3, 0.5, A, k);
            });
        }
        pieces.add(k -> {
            for (int a = -120; a <= 120; a += 60) {
                double r = a * Mth.DEG_TO_RAD, incl = -12 * Mth.DEG_TO_RAD;
                double[] x = v(Math.cos(r), 0, -Math.sin(r)), n = v(Math.sin(r), 0, Math.cos(r));
                double[] y = norm(add(v(0, Math.cos(incl), 0), mul(n, Math.sin(-incl))));
                c.boite(v(Math.sin(r) * 1.02, 1.3, -0.12 + Math.cos(r) * 0.86), new double[][] {x, y, cross(x, y)}, 0.42, 0.48, 0.08, A, k);
            }
        });
        for (int i = 0; i < pieces.size(); i++) {
            pieces.get(i).accept(l(e.armure() * (pieces.size() + 1) - i));
        }
    }

    /**
     * Le heaume (en blocs) : une calotte arrondie par tranches (une croix de deux pavés chacune), pointue en haut, un
     * bandeau, une visière en bec percée de trous, un long nez en espadon, la fente des yeux, une gorgerette et un
     * panache de flammes. Rouge : un bec et un nez plus longs ; bleu : deux cornes.
     */
    private static void heaume(Chantier c, Couleur couleur, float k) {
        double[] h = v(0, 6.0, -0.1);
        double s = 1.2;
        int CASQUE = ClientSusano.CASQUE, A = ClientSusano.ARMURE, F = ClientSusano.FLAMME;
        java.util.function.Function<double[], double[]> P = q -> add(h, mul(q, s));
        for (int i = 0; i < 11; i++) {
            double y = -0.85 + (i + 0.5) * (2.05 / 11);
            double f = y > 0 ? Math.pow(Math.max(0.05, 1 - Math.pow(y / 1.22, 1.5)), 0.75) : 1 - 0.36 * Math.pow(-y, 1.2);
            double r = 0.86 * f, ep = (1.025 / 11 + 0.005) * s;
            c.boite(P.apply(v(0, y, -0.04)), r * s, ep, r * 0.74 * s, CASQUE, k);
            c.boite(P.apply(v(0, y, -0.04)), r * 0.74 * s, ep, r * s, CASQUE, k);
        }
        c.boite(P.apply(v(0, 0.36, -0.04)), 0.9 * s, 0.07 * s, 0.68 * s, A, k);
        c.boite(P.apply(v(0, 0.36, -0.04)), 0.68 * s, 0.07 * s, 0.9 * s, A, k);
        boolean rouge = couleur == Couleur.ROUGE;
        double bec = rouge ? 0.62 : 0.4;
        for (int cote = -1; cote <= 1; cote += 2) {
            double[][] ax = tourneY(-cote * (30 + bec * 35) * Mth.DEG_TO_RAD);
            c.boite(P.apply(v(cote * 0.27, -0.2, 0.74 + bec * 0.5)), ax, 0.34 * s, 0.43 * s, 0.05 * s, A, k);
            for (int i = 0; i < 3; i++) {
                for (int j = 0; j < 2; j++) {
                    c.boite(P.apply(v(cote * (0.16 + j * 0.13), -0.32 - i * 0.12, 0.82 + bec * 0.38 - j * (0.07 + bec * 0.13))), ax, 0.035 * s,
                            0.025 * s, 0.03 * s, ClientSusano.SOMBRE, k);
                }
            }
        }
        c.boite(P.apply(v(0, -0.2, 0.9 + bec * 0.78)), 0.05 * s, 0.43 * s, 0.06 * s, ClientSusano.LAME, k);
        // le long nez, comme un espadon
        int nez = rouge ? 9 : 7;
        double[] q = v(0, -0.08, 0.92 + bec * 0.78), d = norm(v(0, -0.12, 1));
        for (int i = 0; i < nez; i++) {
            double[] r = add(q, mul(d, 0.24));
            c.seg(P.apply(q), P.apply(r), 0.27 * s * (1 - i / (nez + 1.0)), 0.2 * s * (1 - i / (nez + 1.5)), A, k);
            q = r;
            d = norm(add(d, v(0, 0.035, 0)));
        }
        c.boite(P.apply(v(0, 0.36, 0.88)), 0.7 * s, 0.06 * s, 0.05 * s, ClientSusano.SOMBRE, k);
        for (int cote = -1; cote <= 1; cote += 2) {
            c.boite(P.apply(v(cote * 0.3, 0.36, 0.92)), 0.17 * s, 0.04 * s, 0.03 * s, ClientSusano.OEIL, k);
        }
        for (int i = 0; i < 2; i++) {
            double y = -0.86 - i * 0.14, r = 0.62 - i * 0.1;
            c.boite(P.apply(v(0, y, 0.02)), r * s, 0.07 * s, r * 0.72 * s, A, k);
            c.boite(P.apply(v(0, y, 0.02)), r * 0.72 * s, 0.07 * s, r * s, A, k);
        }
        if (couleur == Couleur.BLEU) {
            for (int cote = -1; cote <= 1; cote += 2) {
                double[] a = v(cote * 0.78, 0.45, 0.05), dir = norm(v(cote, 0.55, 0.25));
                for (int i = 0; i < 7; i++) {
                    double[] b = add(a, mul(dir, 0.2));
                    c.seg(P.apply(a), P.apply(b), 0.2 * s * (1 - i / 8.0), 0.14 * s * (1 - i / 9.0), F, k);
                    a = b;
                    dir = norm(add(dir, v(-cote * 0.06, 0.18, -0.02)));
                }
            }
        }
        // le panache : des flammes basses qui partent de la pointe vers l'arrière
        for (int n = 0; n < 5; n++) {
            double[] a = v((n - 2) * 0.1, 1.08 - Math.abs(n - 2) * 0.08, -0.12 - Math.abs(n - 2) * 0.06), dir = norm(v((n - 2) * 0.25, 0.75, -0.7));
            int segs = n == 2 ? 4 : 3;
            for (int i = 0; i < segs; i++) {
                double[] b = add(a, mul(dir, 0.24));
                c.seg(P.apply(a), P.apply(b), 0.22 * s * (1 - i / (segs + 0.5)), 0.12 * s * (1 - i / (segs + 1.0)), F, k);
                a = b;
                dir = norm(add(dir, v(Math.sin(n * 1.9 + i) * 0.12, -0.12, -0.25)));
            }
        }
    }

    /**
     * L'épée de flammes, droite : la poignée et son pommeau, une âme dont les bords ondulent (avec un cœur clair), des
     * flammes serrées le long des deux tranchants qui remontent vers la pointe, une pointe de flamme, un tourbillon à la
     * garde.
     */
    private static void epee(Chantier c, double[] w, double[] d, float k) {
        double[] plat = norm(cross(d, Math.abs(d[1]) > 0.9 ? v(0, 0, 1) : v(0, 1, 0))), ind = cross(plat, d);
        int F = ClientSusano.FLAMME;
        c.seg(add(w, mul(d, -0.6)), add(w, mul(d, 0.45)), 0.18, 0.18, ClientSusano.ARMURE, k, ind);
        for (int i = 0; i < 4; i++) {
            c.boite(add(w, mul(d, -0.45 + i * 0.27)), new double[][] {plat, d, ind}, 0.12, 0.05, 0.12, ClientSusano.LAME, k);
        }
        c.boite(add(w, mul(d, -0.72)), new double[][] {plat, d, ind}, 0.17, 0.13, 0.17, ClientSusano.ARMURE, k);
        double longueur = 5.3 * l(k * 1.2);
        if (longueur < 0.1) {
            return;
        }
        java.util.function.DoubleFunction<double[]> ame = x -> add(w, mul(d, 0.55 + longueur * x));
        java.util.function.DoubleUnaryOperator largeur = x -> (0.27 * Math.pow(1 - x, 0.5) + 0.01) * (1 + 0.2 * Math.sin(x * 37));
        List<double[]> ligne = new ArrayList<>(), coeur = new ArrayList<>();
        for (int i = 0; i <= 24; i++) {
            ligne.add(ame.apply(i / 24.0));
            coeur.add(ame.apply(i / 24.0 * 0.92));
        }
        c.ruban(ligne, i -> largeur.applyAsDouble(i / 24.0), ind, F, k);
        c.ruban(coeur, i -> largeur.applyAsDouble(i / 24.0 * 0.92) * 0.35, ind, ClientSusano.LAME, k);
        for (int cote = -1; cote <= 1; cote += 2) {
            for (int n = 0; n < 16; n++) {
                double s0 = 0.02 + n * 0.058 + hache(n, cote + 5, 1) * 0.025;
                if (s0 > 0.95) {
                    continue;
                }
                double[] base = add(ame.apply(s0), mul(plat, cote * largeur.applyAsDouble(s0) * 0.7));
                double l = (0.55 + 0.75 * hache(n, cote + 3, 2)) * (1 - 0.5 * s0);
                double[] dir = norm(add(mul(d, 0.8), mul(plat, cote * (0.42 + 0.3 * hache(n, 7, cote + 2)))));
                List<double[]> pts = new ArrayList<>();
                for (int j = 0; j <= 5; j++) {
                    double u = j / 5.0;
                    pts.add(add(add(base, mul(dir, l * u)), mul(plat, -cote * 0.12 * l * u * u)));
                }
                double w0 = 0.17 + 0.09 * hache(n, 9, cote + 4);
                c.ruban(pts, j -> w0 * Math.pow(1 - j / 5.0, 0.8) + 0.005, ind, F, k);
            }
        }
        List<double[]> bout = new ArrayList<>();
        for (int i = 0; i <= 6; i++) {
            bout.add(add(ame.apply(0.94 + 0.06 * i / 6.0), mul(d, 0.7 * i / 6.0)));
        }
        c.ruban(bout, i -> 0.1 * (1 - i / 6.0) + 0.005, ind, F, k);
        for (int n = 0; n < 7; n++) {
            double a = n / 7.0 * Math.PI * 2;
            double[] r = add(mul(plat, Math.cos(a)), mul(ind, Math.sin(a))), base = add(w, mul(d, 0.5)), autour = norm(cross(d, r));
            List<double[]> pts = new ArrayList<>();
            for (int i = 0; i <= 6; i++) {
                double u = i / 6.0;
                double[] tourne = add(mul(r, Math.cos(u * 1.6)), mul(autour, Math.sin(u * 1.6)));
                pts.add(add(add(base, mul(tourne, 0.55 * u + 0.1)), mul(d, 0.7 * u * u - 0.1 * u)));
            }
            c.ruban(pts, i -> 0.16 * (1 - i / 6.0) + 0.005, d, F, k);
        }
    }

    /** Le bouclier sur l'avant-bras gauche : un écu (large en haut, en pointe en bas), bordé de lumière, avec un œil. */
    private static void bouclier(Chantier c, Bras g, float k) {
        double[] face = norm(v(0.65, 0, 1)), x = norm(cross(v(0, 1, 0), face)), y = cross(face, x);
        double[][] ax = {x, y, face};
        double[] centre = add(lerp(g.e(), g.w(), 0.6), v(1.15, 0.05, 0.45));
        for (int i = 0; i < 9; i++) {
            double h = 1.6 - i * 0.42, w = i < 4 ? 1.3 : 1.3 * (1 - (i - 3.5) / 5.3);
            c.boite(add(centre, mul(y, h)), ax, w, 0.22, 0.1, ClientSusano.ARMURE, k);
            for (int s = -1; s <= 1; s += 2) {
                c.boite(add(add(centre, mul(y, h)), mul(x, s * w)), ax, 0.09, 0.23, 0.14, ClientSusano.LAME, k);
            }
        }
        c.boite(add(centre, mul(y, 1.82)), ax, 1.38, 0.08, 0.14, ClientSusano.LAME, k);
        double[] em = add(add(centre, mul(y, 0.45)), mul(face, 0.14));
        for (int i = 0; i < 12; i++) {
            double a = i / 12.0 * Math.PI * 2;
            c.boite(add(em, add(mul(x, Math.cos(a) * 0.55), mul(y, Math.sin(a) * 0.55))), ax, 0.1, 0.1, 0.04, ClientSusano.LAME, k);
        }
        for (int i = 0; i < 3; i++) {
            double a = i / 3.0 * Math.PI * 2 + 0.5;
            c.boite(add(em, add(mul(x, Math.cos(a) * 0.3), mul(y, Math.sin(a) * 0.3))), ax, 0.09, 0.09, 0.05, ClientSusano.SOMBRE, k);
        }
        c.boite(add(em, mul(face, 0.02)), ax, 0.13, 0.13, 0.05, ClientSusano.OEIL, k);
    }

    /**
     * L'arc : de grandes branches épaisses qui reviennent vers lui puis se recourbent en avant, hérissées de flammes vers
     * l'arrière, une lame à chaque bout, un anneau et un œil à la poignée, la corde tirée jusqu'à sa main droite, et tant
     * qu'il n'a pas tiré, la flèche à la pointe noire.
     */
    private static void arc(Chantier c, Posture p, float k, boolean fleche) {
        double[] w = p.g().w(), main = p.d().w(), avant = norm(sub(w, main)), x = norm(cross(v(0, 1, 0), avant)), haut = cross(avant, x);
        List<double[]> bouts = new ArrayList<>();
        for (int signe = -1; signe <= 1; signe += 2) {
            int sg = signe;
            java.util.function.DoubleFunction<double[]> branche = s -> add(add(w, mul(haut, sg * 3.4 * s)),
                    mul(avant, -1.15 * s * s + 1.1 * Math.pow(Math.max(0, s - 0.78), 2) / 0.05));
            double[] prec = branche.apply(0);
            for (int i = 1; i <= 12; i++) {
                double t = i / 12.0;
                double[] q = branche.apply(t);
                c.seg(prec, q, 0.42 * (1 - t * 0.55), 0.26 * (1 - t * 0.4), ClientSusano.LAME, k, x);
                if (i % 2 == 0 && i < 12) {
                    double[] dehors = norm(add(mul(haut, sg * 0.8), mul(avant, -0.7)));
                    List<double[]> pts = new ArrayList<>();
                    double lf = 0.85 - t * 0.35;
                    for (int j = 0; j <= 5; j++) {
                        double u = j / 5.0;
                        pts.add(add(add(q, mul(dehors, lf * u)), mul(avant, -0.35 * lf * u * u)));
                    }
                    c.ruban(pts, j -> 0.2 * Math.pow(1 - j / 5.0, 0.8) + 0.005, x, ClientSusano.FLAMME, k);
                }
                prec = q;
            }
            double[] bout = branche.apply(1);
            bouts.add(bout);
            c.seg(bout, add(bout, mul(norm(add(avant, mul(haut, sg * 0.5))), 0.9)), 0.22, 0.1, ClientSusano.LAME, k, x);
        }
        double[][] ax = {x, haut, avant};
        for (int i = 0; i < 12; i++) {
            double a = i / 12.0 * Math.PI * 2;
            c.boite(add(add(w, mul(haut, Math.cos(a) * 0.5)), mul(x, Math.sin(a) * 0.5)), ax, 0.09, 0.09, 0.09, ClientSusano.LAME, k);
        }
        c.boite(add(w, mul(avant, 0.1)), ax, 0.16, 0.16, 0.06, ClientSusano.OEIL, k);
        for (double[] b : bouts) {
            c.seg(b, main, 0.05, 0.05, ClientSusano.LAME, k);
        }
        if (fleche) {
            double[] pointe = add(w, mul(avant, 2.0));
            c.seg(main, pointe, 0.14, 0.14, ClientSusano.LAME, k);
            c.seg(pointe, add(pointe, mul(avant, 0.75)), 0.42, 0.12, ClientSusano.SOMBRE, k, x);
        }
    }

    /** La traînée d'un coup d'épée : un ruban en arc devant lui, plus vif vers la lame (dans le repère du chevalier). */
    static void trainee(VertexConsumer c, Matrix4f m, Couleur couleur, Gestes g, float taille) {
        if (g.grand() >= LEVER - 0.02 && g.grand() < IMPACT + 0.35) {
            double fin = Math.min(1, (g.grand() - LEVER + 0.02) / (IMPACT - LEVER + 0.02)), fondu = g.grand() < IMPACT ? 1 : 1 - (g.grand() - IMPACT) / 0.35;
            float[] pale = melange(couleur, 0.5F), vif = melange(couleur, 0.85F);
            float s = 0.72F + 0.28F * taille;
            for (int i = 0; i < 24; i++) {
                double t0 = i / 24.0 * fin, t1 = (i + 1) / 24.0 * fin;
                double b0 = (-35 + 150 * t0) * Mth.DEG_TO_RAD, b1 = (-35 + 150 * t1) * Mth.DEG_TO_RAD;
                float a = (float) ((0.05 + 0.3 * (t1 / fin) * (t1 / fin)) * fondu), av = (float) ((0.15 + 0.75 * (t1 / fin) * (t1 / fin)) * fondu);
                quadVertical(c, m, b0, b1, 3.0, 7.0, pale, a, s);
                quadVertical(c, m, b0, b1, 7.0, 7.6, vif, av, s);
            }
            return;
        }
        double k = g.coup();
        if (k < 0.18 || k > 0.62) {
            return;
        }
        double a0 = g.cote() == 0 ? -100 : 70, a1 = g.cote() == 0 ? 25 : -35, sens = g.cote() == 0 ? 1 : -1;
        double fin = Math.min(1, (k - 0.18) / 0.25), fondu = k < 0.45 ? 1 : 1 - (k - 0.45) / 0.17;
        float[] pale = melange(couleur, 0.5F), vif = melange(couleur, 0.85F);
        float s = 0.72F + 0.28F * taille;
        for (int i = 0; i < 24; i++) {
            double t0 = i / 24.0 * fin, t1 = (i + 1) / 24.0 * fin;
            double b0 = (a0 + (a1 - a0) * t0) * Mth.DEG_TO_RAD, b1 = (a0 + (a1 - a0) * t1) * Mth.DEG_TO_RAD;
            float a = (float) ((0.04 + 0.3 * (t1 / fin) * (t1 / fin)) * fondu), av = (float) ((0.1 + 0.7 * (t1 / fin) * (t1 / fin)) * fondu);
            quadArc(c, m, b0, b1, 3.2, 6.9, 3.7, sens, t0, t1, pale, a, s);
            quadArc(c, m, b0, b1, 6.9, 7.4, 3.7, sens, t0, t1, vif, av, s);
        }
    }

    private static void quadArc(VertexConsumer c, Matrix4f m, double b0, double b1, double r0, double r1, double y, double sens, double t0, double t1,
                                float[] rgb, float a, float s) {
        double[][] pts = {{b0, r0, t0}, {b1, r0, t1}, {b1, r1, t1}, {b0, r1, t0}};
        for (double[] p : pts) {
            double yy = y + 1.4 * (0.5 - p[2]) * sens * (p[1] / 7);
            c.addVertex(m, (float) (Math.sin(p[0]) * p[1] * s), (float) (yy * s), (float) (Math.cos(p[0]) * p[1] * s)).setColor(rgb[0], rgb[1], rgb[2], a);
        }
    }

    /** Un morceau de croissant vertical (dans le plan de son bras droit) : l'angle part du dessus de la tête vers l'avant. */
    private static void quadVertical(VertexConsumer c, Matrix4f m, double b0, double b1, double r0, double r1, float[] rgb, float a, float s) {
        double[][] pts = {{b0, r0}, {b1, r0}, {b1, r1}, {b0, r1}};
        for (double[] p : pts) {
            c.addVertex(m, (float) (-0.9 * s), (float) ((4.2 + Math.cos(p[0]) * p[1]) * s), (float) (Math.sin(p[0]) * p[1] * s)).setColor(rgb[0], rgb[1], rgb[2], a);
        }
    }

    private static float[] melange(Couleur couleur, float vers) {
        int a = couleur == Couleur.NOIR ? 0x303038 : couleur.corps, b = couleur.contour;
        float[] r = new float[3];
        for (int i = 0; i < 3; i++) {
            int sh = 16 - 8 * i;
            r[i] = ((a >> sh & 255) + ((b >> sh & 255) - (a >> sh & 255)) * vers) / 255F;
        }
        return r;
    }

    // ================================================================ la flèche qui vole, et les flammes noires

    /**
     * Une flèche en vol : d'où elle part (l'arc, dans le monde), où elle s'arrête (le bout de la ligne du serveur), quand
     * elle est partie et combien de secondes elle vole, sa taille (celle du chevalier) et ses couleurs (la grande flèche en
     * flammes noires, bordée de la couleur du Susano).
     */
    private record Fleche(Vec3 depart, Vec3 fin, long debut, double duree, double taille, GrandeFleche.Teintes teintes) {
    }

    private static final List<Fleche> FLECHES = new ArrayList<>();
    /** Les flammes noires que la flèche laisse au sol : où, et quand elles prennent (quand la flèche passe au-dessus). */
    private record Foyer(Vec3 pos, long debut, int graine) {
    }

    private static final List<Foyer> FOYERS = new ArrayList<>();
    /** Les vagues de flammes noires du grand coup : autour de qui (sa place au moment du coup), et quand il a levé l'épée. */
    private record Vague(Vec3 centre, long debut) {
    }

    private static final List<Vague> VAGUES = new ArrayList<>();
    /** La vague va jusqu'à 8 blocs ; le feu reste au sol un moment. */
    private static final double RAYON_VAGUE = 8, DUREE_FEU_SOL = 2.4;

    /** Le grand coup de ce joueur : la vague partira quand l'épée touchera le sol. */
    static void vague(Player joueur) {
        VAGUES.add(new Vague(joueur.position(), System.nanoTime()));
    }
    /** Ce qui brûle de flammes noires : l'entité → jusqu'à quand (temps réel). */
    private static final Map<Integer, Long> FEUX = new HashMap<>();
    /** Une gerbe de flammes noires jaillit quand la flèche passe (le vrai feu reste ensuite au sol : FeuNoir). */
    private static final double DUREE_FOYER = 1.2;

    /** Le serveur dit qu'une entité brûle (ou ne brûle plus). */
    public static void flammes(Susano3.Flammes p) {
        if (p.ticks() <= 0) {
            FEUX.remove(p.entite());
        } else {
            FEUX.put(p.entite(), System.nanoTime() + p.ticks() * 50_000_000L);
        }
    }

    /**
     * La flèche part de l'arc du chevalier de ce joueur, jusqu'au bout de la ligne du serveur, à sa vitesse ; chaque foyer
     * au sol prend quand elle passe au-dessus (ceux de la flaque, quand elle se plante).
     */
    static void lancer(Player joueur, float corpsLacet, float taille, Couleur couleur, Susano3.Trait trait) {
        float k = 0.72F + 0.28F * taille;
        double[] avant = norm(sub(ARC_G.w(), ARC_D.w()));
        double[] local = mul(add(ARC_G.w(), mul(avant, 2.4)), k);
        double a = -corpsLacet * Mth.DEG_TO_RAD;
        Vec3 depart = joueur.position().add(local[0] * Math.cos(a) + local[2] * Math.sin(a), local[1], -local[0] * Math.sin(a) + local[2] * Math.cos(a));
        Vec3 ligne = new Vec3(trait.depart()), fin = new Vec3(trait.fin());
        double longueur = Math.max(0.1, fin.distanceTo(ligne)), vitesse = Susano3.VITESSE_FLECHE * 20;
        long debut = System.nanoTime();
        FLECHES.add(new Fleche(depart, fin, debut, longueur / vitesse, 2.0 * k, GrandeFleche.feuNoir(melange(couleur, 0.35F))));
        Vec3 dir = fin.subtract(ligne).scale(1 / longueur);
        int graine = (int) (debut >> 10);
        for (Vector3f q : trait.sol()) {
            Vec3 pos = new Vec3(q);
            double d = Mth.clamp(pos.subtract(ligne).dot(dir), 0, longueur);
            FOYERS.add(new Foyer(pos, debut + (long) (d / vitesse * 1e9), graine++));
        }
    }

    @EventBusSubscriber(modid = Cabane.MODID, value = Dist.CLIENT)
    public static final class Dessin {
        @SubscribeEvent
        public static void dessiner(RenderLevelStageEvent event) {
            Minecraft mc = Minecraft.getInstance();
            if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES || mc.level == null
                    || FLECHES.isEmpty() && FEUX.isEmpty() && VAGUES.isEmpty() && FOYERS.isEmpty()) {
                return;
            }
            Camera camera = event.getCamera();
            Vec3 cam = camera.getPosition();
            PoseStack ps = event.getPoseStack();
            MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
            VertexConsumer c = buffers.getBuffer(ClientSusano.Types.CORPS);
            Matrix4f m = ps.last().pose();
            float partiel = event.getPartialTick().getGameTimeDeltaPartialTick(false);
            double temps = System.nanoTime() / 1e9;
            Vector3f droite = new Vector3f(1, 0, 0).rotate(camera.rotation()), haut = new Vector3f(0, 1, 0);
            long maintenant = System.nanoTime();
            // les flèches en vol : la grande flèche (docs/fruits/planche-attaques2.png), en flammes noires bordées de la
            // couleur du Susano, et des langues de feu noir qui lèchent sa hampe vers l'arrière
            for (Iterator<Fleche> it = FLECHES.iterator(); it.hasNext(); ) {
                Fleche f = it.next();
                double k = (maintenant - f.debut()) / 1e9 / f.duree();
                if (k >= 1) {
                    it.remove();
                    continue;
                }
                Vec3 tete = f.depart().lerp(f.fin(), k), dir = f.fin().subtract(f.depart()).normalize();
                double visible = 1 + tete.distanceTo(f.depart());
                GrandeFleche.dessiner(c, m, tete.subtract(cam), dir, visible, f.taille(), temps, f.teintes());
                double l = Math.min(visible, GrandeFleche.LONGUEUR * f.taille());
                for (int i = 0; i < 9; i++) {
                    double z = (0.3 + i * 0.55) * f.taille();
                    if (z > l) {
                        break;
                    }
                    Vec3 p = tete.subtract(dir.scale(z)).subtract(cam);
                    langue(c, m, p, droite, haut, (0.5 - i * 0.035) * f.taille(), temps * 9 + i);
                }
            }
            // les vagues du grand coup : un anneau de flammes noires qui s'élargit autour de lui, puis un champ de feu noir
            for (Iterator<Vague> it = VAGUES.iterator(); it.hasNext(); ) {
                Vague v = it.next();
                double t = (maintenant - v.debut()) / 1e9 - IMPACT;
                if (t > DUREE_FEU_SOL) {
                    it.remove();
                    continue;
                }
                if (t < 0) {
                    continue;
                }
                Vec3 centre = v.centre().subtract(cam);
                double r = 1.5 + (RAYON_VAGUE - 1.5) * l(t / 0.45), fondu = 1 - l((t - DUREE_FEU_SOL + 0.8) / 0.8);
                if (t < 0.6) {
                    for (int i = 0; i < 40; i++) {
                        double a = i / 40.0 * Math.PI * 2;
                        langue(c, m, centre.add(Math.cos(a) * r, 0, Math.sin(a) * r), droite, haut, 1.3 * (1 - t / 0.6) + 0.4, temps * 10 + i);
                    }
                }
                int n = (int) (70 * Math.min(1, t / 0.45));
                for (int i = 0; i < n; i++) {
                    double a = hache(i, 11, 3) * Math.PI * 2, d = Math.sqrt(hache(i, 12, 3)) * r;
                    double taille = (0.4 + 0.7 * hache(i, 13, 3)) * fondu * (0.8 + 0.2 * Math.sin(temps * 7 + i));
                    if (taille > 0.05) {
                        langue(c, m, centre.add(Math.cos(a) * d, 0, Math.sin(a) * d), droite, haut, taille, temps * 8 + i * 1.3);
                    }
                }
            }
            // les flammes au sol, sous la ligne de la flèche et en flaque au bout : une touffe de langues noires qui
            // jaillit quand la flèche passe, ondule, puis retombe
            for (Iterator<Foyer> it = FOYERS.iterator(); it.hasNext(); ) {
                Foyer f = it.next();
                double t = (maintenant - f.debut()) / 1e9;
                if (t > DUREE_FOYER) {
                    it.remove();
                    continue;
                }
                if (t < 0) {
                    continue;
                }
                double force = l(t / 0.2) * (1 - l((t - DUREE_FOYER + 0.6) / 0.6)), jaillit = 1 + 0.7 * Math.max(0, 1 - t / 0.35);
                Vec3 base = f.pos().subtract(cam);
                for (int i = 0; i < 6; i++) {
                    double a = hache(i, 21, f.graine()) * Math.PI * 2, d = Math.sqrt(hache(i, 22, f.graine())) * 1.05;
                    double taille = (0.45 + 0.6 * hache(i, 23, f.graine())) * force * jaillit * (0.8 + 0.2 * Math.sin(temps * 7 + i * 1.9 + f.graine()));
                    if (taille > 0.04) {
                        langue(c, m, base.add(Math.cos(a) * d, 0, Math.sin(a) * d), droite, haut, taille, temps * 8 + i * 1.3 + f.graine());
                    }
                }
                if (force > 0.5 && mc.level.random.nextInt(14) == 0) {
                    mc.level.addParticle(mc.level.random.nextBoolean() ? net.minecraft.core.particles.ParticleTypes.SQUID_INK
                                    : net.minecraft.core.particles.ParticleTypes.LARGE_SMOKE,
                            f.pos().x + (mc.level.random.nextDouble() - 0.5) * 1.6, f.pos().y + 0.3, f.pos().z + (mc.level.random.nextDouble() - 0.5) * 1.6, 0, 0.05, 0);
                }
            }
            // ce qui brûle : des langues de flammes vraiment noires, sur tout le corps
            FEUX.entrySet().removeIf(en -> en.getValue() < maintenant);
            for (Map.Entry<Integer, Long> en : FEUX.entrySet()) {
                Entity e = mc.level.getEntity(en.getKey());
                if (e == null || !e.isAlive()) {
                    continue;
                }
                Vec3 pos = e.getPosition(partiel).subtract(cam);
                double l = e.getBbWidth(), h = e.getBbHeight();
                int n = 10 + (int) (h * 7);
                for (int i = 0; i < n; i++) {
                    double ox = (hache(i, 1, en.getKey()) - 0.5) * l * 1.1, oz = (hache(i, 3, en.getKey()) - 0.5) * l * 1.1;
                    double oy = hache(i, 2, en.getKey()) * h * 0.95;
                    double taille = (0.28 + 0.3 * hache(i, 4, en.getKey())) * (0.75 + 0.25 * Math.sin(temps * 7 + i));
                    langue(c, m, pos.add(ox, oy, oz), droite, haut, taille, temps * 8 + i * 1.7);
                }
                if (mc.level.random.nextInt(3) == 0) {
                    mc.level.addParticle(net.minecraft.core.particles.ParticleTypes.SQUID_INK, e.getX() + (mc.level.random.nextDouble() - 0.5) * l,
                            e.getY() + mc.level.random.nextDouble() * h, e.getZ() + (mc.level.random.nextDouble() - 0.5) * l, 0, 0.04, 0);
                }
            }
            buffers.endBatch(ClientSusano.Types.CORPS);
        }

        /** Une langue de flamme noire, face à la caméra : large en bas, qui s'affine en ondulant. */
        private static void langue(VertexConsumer c, Matrix4f m, Vec3 base, Vector3f droite, Vector3f haut, double taille, double phase) {
            double[] larg = {0.5, 0.46, 0.32, 0.16, 0.02};
            for (int i = 0; i < 4; i++) {
                double y0 = i / 4.0 * taille * 1.6, y1 = (i + 1) / 4.0 * taille * 1.6;
                double o0 = Math.sin(phase + i * 0.9) * 0.08 * i * taille, o1 = Math.sin(phase + (i + 1) * 0.9) * 0.08 * (i + 1) * taille;
                double w0 = larg[i] * taille, w1 = larg[i + 1] * taille;
                sommet(c, m, base, droite, haut, -w0 + o0, y0);
                sommet(c, m, base, droite, haut, w0 + o0, y0);
                sommet(c, m, base, droite, haut, w1 + o1, y1);
                sommet(c, m, base, droite, haut, -w1 + o1, y1);
            }
        }

        private static void sommet(VertexConsumer c, Matrix4f m, Vec3 base, Vector3f droite, Vector3f haut, double x, double y) {
            c.addVertex(m, (float) (base.x + droite.x() * x + haut.x() * y), (float) (base.y + droite.y() * x + haut.y() * y),
                    (float) (base.z + droite.z() * x + haut.z() * y)).setColor(0.02F, 0.015F, 0.025F, 0.95F);
        }

        /** Une hampe de lumière entre deux points, face à la caméra. */
        private static void tige(VertexConsumer c, Matrix4f m, Vec3 a, Vec3 b, Vector3f droite, float[] rgb, float alpha) {
            Vec3 d = b.subtract(a);
            Vec3 cote = d.cross(new Vec3(droite.x(), droite.y(), droite.z())).normalize().scale(0.06);
            if (cote.lengthSqr() < 1e-6) {
                cote = new Vec3(0, 0.06, 0);
            }
            Vec3[] p = {a.subtract(cote), a.add(cote), b.add(cote), b.subtract(cote)};
            for (Vec3 q : p) {
                c.addVertex(m, (float) q.x, (float) q.y, (float) q.z).setColor(rgb[0], rgb[1], rgb[2], alpha);
            }
        }

        private Dessin() {
        }
    }

    private ClientSusano3() {
    }
}
