package fr.cabane.client.pouvoir.phenix;

import static fr.cabane.client.pouvoir.phenix.Effets.*;
import static fr.cabane.client.pouvoir.phenix.Feu.*;

import com.mojang.blaze3d.vertex.PoseStack;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * Le phénix sur le joueur (l'aperçu docs/phenix/apercu) : le skin reste visible ; une aura fine autour de lui (le modèle
 * gonflé d'un demi-pixel, comme les manteaux des autres pouvoirs, transparent de face et lumineux sur les bords, bleu avec
 * de l'orange qui passe) ; quelques petites flammes qui naissent et s'éteignent ; les avant-bras qui brûlent ; au niveau 2
 * les ailes, les serres ; au niveau 3 les grandes ailes, la crête, les yeux dorés et la queue de flammes.
 */
public final class Plumage {
    /** Les parties : tête, corps, bras droit, bras gauche, jambe droite, jambe gauche. */
    private static final int TETE = 0, CORPS = 1, BRAS_D = 2, BRAS_G = 3, JAMBE_D = 4, JAMBE_G = 5;

    /** Un point du corps où une flamme peut naître : sa partie, sa place (pixels, repère de la partie), sa normale. */
    record Ancre(int partie, float x, float y, float z, float nx, float ny, float nz, float h, float h2, boolean visage, boolean avantBras,
            boolean epaules, boolean tibias) {
    }

    private static float[][] boites(boolean fin) {
        return new float[][]{
                {-4, -8, -4, 4, 0, 4},
                {-4, 0, -2, 4, 12, 2},
                fin ? new float[]{-2, -2, -2, 1, 10, 2} : new float[]{-3, -2, -2, 1, 10, 2},
                fin ? new float[]{-1, -2, -2, 2, 10, 2} : new float[]{-1, -2, -2, 3, 10, 2},
                {-2, 0, -2, 2, 12, 2},
                {-2, 0, -2, 2, 12, 2}};
    }

    private static final List<Ancre> ANCRES_LARGE = ancres(false), ANCRES_FIN = ancres(true);

    private static List<Ancre> ancres(boolean fin) {
        List<Ancre> l = new ArrayList<>();
        float[][] b = boites(fin);
        int n = 0;
        for (int p = 0; p < 6; p++) {
            float[] lo = {b[p][0], b[p][1], b[p][2]}, hi = {b[p][3], b[p][4], b[p][5]};
            boolean bras = p == BRAS_D || p == BRAS_G, jambe = p == JAMBE_D || p == JAMBE_G;
            // les faces : ±x, ±z, le dessus (-y dans le modèle) ; les bras aussi le dessous (les mains)
            List<int[]> faces = new ArrayList<>(List.of(new int[]{0, 1}, new int[]{0, -1}, new int[]{2, 1}, new int[]{2, -1}, new int[]{1, -1}));
            if (bras) {
                faces.add(new int[]{1, 1});
            }
            for (int[] f : faces) {
                int ax = f[0], sg = f[1];
                int b1 = ax == 0 ? 1 : 0, b2 = ax == 2 ? 1 : 2;
                for (float u = lo[b1] + 1; u <= hi[b1] - 0.99F; u += 2) {
                    for (float w = lo[b2] + 1; w <= hi[b2] - 0.99F; w += 2) {
                        float[] q = new float[3], nn = new float[3];
                        q[ax] = sg > 0 ? hi[ax] : lo[ax];
                        q[b1] = u + (hache(n, 1) - 0.5F) * 0.8F;
                        q[b2] = w + (hache(n, 2) - 0.5F) * 0.8F;
                        nn[ax] = sg;
                        boolean visage = p == TETE && ax == 2 && sg < 0;
                        boolean avant = bras && q[1] > 3.5F;
                        boolean epaule = (bras && q[1] <= 3.5F && nn[1] <= 0) || (p == CORPS && (nn[2] > 0 || nn[1] < 0) && q[1] < 7);
                        boolean tibia = jambe && q[1] > 5.5F;
                        l.add(new Ancre(p, q[0], q[1], q[2], nn[0], nn[1], nn[2], hache(n, 3), hache(n, 4), visage, avant, epaule, tibia));
                        n++;
                    }
                }
            }
        }
        return l;
    }

    /** Ce qu'il faut dessiner : le niveau et les intensités (0 → 1) de chaque morceau, et les réglages des ailes. */
    public static final class Tenue {
        public int niv;
        public float k = 1, kAiles = -1, kSerres = -1, kCorps = -1, kCrete = -1, kQueue = -1, kEpaules = -1;
        public Oiseau.Aile ailes;
        /** Les flammes partent en arrière quand il vole (dans le monde, sans unité). */
        public Vector3f vent;

        float ou(float v) {
            return v < 0 ? k : v;
        }
    }

    // les repères de chaque partie (image courante)
    private static final Matrix4f[] M = new Matrix4f[6];
    private static final Matrix3f[] N = new Matrix3f[6];

    static {
        for (int i = 0; i < 6; i++) {
            M[i] = new Matrix4f();
            N[i] = new Matrix3f();
        }
    }

    private static final Matrix4f MONDE_PX = new Matrix4f().scale(1 / 16F);

    /** Repère « comme dans l'aperçu » (pixels, y en haut, z devant) d'une partie, à partir de son repère du modèle. */
    private static Matrix4f apercu(Matrix4f partie) {
        return new Matrix4f(partie).scale(1 / 16F).scale(1, -1, -1);
    }

    /**
     * Le repère du joueur comme dans l'aperçu : pixels, les pieds à y = 0, x à sa gauche, z devant, qui suit son corps
     * (il se penche quand il s'accroupit).
     */
    public static Matrix4f repereJoueur(PlayerModel<?> m, PoseStack ps) {
        ps.pushPose();
        m.body.translateAndRotate(ps);
        Matrix4f r = new Matrix4f(ps.last().pose()).scale(1 / 16F).translate(0, 24, 0).scale(1, -1, -1);
        ps.popPose();
        return r;
    }

    public static void dessiner(PlayerModel<?> m, PoseStack ps, boolean fin, Tenue o) {
        if (!pret() || o.k <= 0.01F) {
            return;
        }
        ModelPart[] parties = {m.head, m.body, m.rightArm, m.leftArm, m.rightLeg, m.leftLeg};
        for (int i = 0; i < 6; i++) {
            ps.pushPose();
            parties[i].translateAndRotate(ps);
            M[i].set(ps.last().pose());
            N[i].set(ps.last().normal());
            ps.popPose();
        }
        Matrix4f joueur = repereJoueur(m, ps);
        Matrix4f avant = repere();
        int niv = o.niv;
        float k = o.k;
        List<Ancre> ancres = fin ? ANCRES_FIN : ANCRES_LARGE;
        // l'aura fine (un demi-pixel)
        float kc = niv >= 3 ? o.ou(o.kCorps) : niv >= 2 ? o.ou(o.kEpaules) : k;
        coque(fin, niv, Math.min(1.2F, kc) * (niv >= 3 ? 1 : niv >= 2 ? 0.8F : 0.65F));
        // les flammes posées sur le corps
        repere(MONDE_PX);
        aura(ancres, Ancre::avantBras, k, niv >= 3 ? 0.32F : niv >= 2 ? 0.42F : 0.6F, 1, o.vent);
        mainsFeu(fin, k);
        Predicate<Ancre> ou = niv >= 3 ? a -> !a.visage() : niv >= 2 ? a -> !a.visage() && a.partie() != TETE : Ancre::avantBras;
        petitesFlammes(ancres, ou, kc, niv >= 3 ? 9 : niv >= 2 ? 6 : 3, o.vent);
        if (niv >= 2) {
            aura(ancres, Ancre::tibias, o.ou(o.kSerres), 0.35F, 1, o.vent);
        }
        repere(joueur);
        nuage(kc, niv);
        if (niv >= 2) {
            serres(o.ou(o.kSerres));
            Oiseau.Aile a = o.ailes != null ? o.ailes : new Oiseau.Aile().ouv(0.22F).bat((float) Math.sin(T * 1.8) * 0.04F);
            a.taille(a.taille == 1 ? (niv >= 3 ? 1.12F : 0.74F) : a.taille).k(o.ou(o.kAiles));
            repere(joueur);
            for (int c = -1; c <= 1; c += 2) {
                Oiseau.aile(c * 2.4F, 21, -2.6F, c, a);
            }
        }
        if (niv >= 3) {
            repere(joueur);
            queue(o.ou(o.kQueue));
            repere(apercu(M[TETE]));
            crete(o.ou(o.kCrete));
            yeux(o.ou(o.kCrete));
        }
        repere(avant);
    }

    // ================================================================ les morceaux

    private static final Vector3f TMP = new Vector3f(), NW = new Vector3f();

    /** Les langues sur les ancres choisies (dans le repère « monde en pixels »). */
    private static void aura(List<Ancre> ancres, Predicate<Ancre> filtre, float k, float dens, float alpha, Vector3f vent) {
        if (k <= 0.02F) {
            return;
        }
        for (int i = 0; i < ancres.size(); i++) {
            Ancre a = ancres.get(i);
            if (a.h() > dens || !filtre.test(a)) {
                continue;
            }
            M[a.partie()].transformPosition(a.x() / 16, a.y() / 16, a.z() / 16, TMP).mul(16);
            N[a.partie()].transform(a.nx(), a.ny(), a.nz(), NW).normalize();
            Vector3f d = new Vector3f(0, 1, 0).add(new Vector3f(NW).mul(0.55F));
            if (vent != null) {
                d.add(vent);
            }
            d.normalize();
            float len = (3.5F + 4.5F * a.h2()) * k, w = (2.6F + 1.6F * a.h()) * Math.min(1, k * 1.4F);
            langue(TMP.x + NW.x * 0.4F, TMP.y + NW.y * 0.4F, TMP.z + NW.z * 0.4F, d, len, w, i * 0.0731F, Math.min(1, k * 1.3F) * alpha,
                    a.h2() > 0.75F ? 0.5F : 0, 0.28F);
        }
    }

    /** n petites flammes à la fois, chacune vit ~1 s à un endroit au hasard puis renaît ailleurs ; une sur deux orange. */
    private static void petitesFlammes(List<Ancre> ancres, Predicate<Ancre> filtre, float k, int n, Vector3f vent) {
        if (k <= 0.02F) {
            return;
        }
        List<Ancre> liste = ancres.stream().filter(filtre).toList();
        if (liste.isEmpty()) {
            return;
        }
        for (int j = 0; j < n; j++) {
            float per = 0.9F + 0.5F * hache(j, 9), x = T / per + hache(j, 8);
            int cyc = (int) Math.floor(x);
            float u = x - cyc;
            Ancre a = liste.get((int) (hache(j, cyc, 77) * liste.size()) % liste.size());
            M[a.partie()].transformPosition(a.x() / 16, a.y() / 16, a.z() / 16, TMP).mul(16);
            N[a.partie()].transform(a.nx(), a.ny(), a.nz(), NW).normalize();
            Vector3f d = new Vector3f(0, 1, 0).add(new Vector3f(NW).mul(0.4F));
            if (vent != null) {
                d.add(vent);
            }
            d.normalize();
            float vie = cloche(u);
            boolean orange = (j + cyc) % 2 == 0;
            langue(TMP.x + NW.x * 0.6F, TMP.y + NW.y * 0.6F, TMP.z + NW.z * 0.6F, d, (4 + 4 * hache(j, cyc, 5)) * vie * k, 3.4F * Math.min(1, vie * 2) * k,
                    j * 0.31F + cyc * 0.07F, k, orange ? 2 : 0.3F, 0.25F);
            if (orange) {
                lueur(TMP.x, TMP.y, TMP.z, 7 * vie * k, ORANGE, 0.35F * vie);
            }
        }
    }

    /** Le nuage de lumière autour de lui (des lueurs douces sur le corps, un peu d'orange), dans le repère du joueur. */
    private static void nuage(float k, int niv) {
        if (k <= 0.02F) {
            return;
        }
        float[][] pts = niv >= 3 ? new float[][]{{0, 18, 0, 34}, {0, 28, 0, 22}, {-7, 15, 0, 16}, {7, 15, 0, 16}, {0, 6, 0, 22}}
                : niv >= 2 ? new float[][]{{0, 19, -1, 26}, {-7, 13, 0, 14}, {7, 13, 0, 14}} : new float[][]{{-7, 12, 0, 12}, {7, 12, 0, 12}};
        for (int i = 0; i < pts.length; i++) {
            float[] p = pts[i];
            float puls = 0.85F + 0.15F * (float) Math.sin(T * 3 + i);
            lueur(p[0], p[1], p[2], p[3] * 1.6F * puls, CYAN, 0.13F * k);
            if (niv >= 2 && i % 2 == 0) {
                lueur(p[0], p[1], p[2], p[3] * 0.9F * puls, ORANGE, 0.05F * k);
            }
        }
    }

    /** Les mains qui flambent : un cœur doré et une lueur (dans le repère « monde en pixels »). */
    private static void mainsFeu(boolean fin, float k) {
        if (k <= 0.02F) {
            return;
        }
        for (int c = 0; c < 2; c++) {
            float x = (c == 0 ? -1 : 1) * (fin ? 0.5F : 1);
            M[c == 0 ? BRAS_D : BRAS_G].transformPosition(x / 16, 10.5F / 16, 0, TMP).mul(16);
            lueur(TMP.x, TMP.y, TMP.z, 13 * k, CYAN, 0.45F * k);
            lueur(TMP.x, TMP.y, TMP.z, 4 * k, OR, 0.6F * k);
        }
    }

    /** Les serres : 3 griffes dorées devant chaque pied, 1 derrière (repère de chaque jambe). */
    private static void serres(float k) {
        if (k <= 0.02F) {
            return;
        }
        for (int j : new int[]{JAMBE_D, JAMBE_G}) {
            repere(apercu(M[j]));
            for (float x : new float[]{-1.4F, 0, 1.4F}) {
                griffe(x, -11.2F, 1.6F, x * 1.25F, -12.2F, 1.6F + 3.6F * k, 0.75F * k);
            }
            griffe(0, -11.2F, -1.6F, 0, -12.2F, -1.6F - 2.4F * k, 0.7F * k);
            lueur(0, -11, 1, 10 * k, CYAN, 0.3F * k);
        }
    }

    /** La crête : 3 grandes langues à pointe dorée vers l'arrière du crâne, 2 petites sur les côtés (repère de la tête). */
    private static void crete(float k) {
        if (k <= 0.02F) {
            return;
        }
        langue(0, 8, -0.5F, v(0, 1, -0.55F).normalize(), 12 * k, 4.6F * k, 0.11F, 1, 1, 0.12F);
        langue(0, 7, -3, v(0, 0.65F, -1).normalize(), 14 * k, 4.2F * k, 0.23F, 1, 1, 0.12F);
        langue(0, 4.5F, -4.2F, v(0, 0.25F, -1).normalize(), 11 * k, 3.6F * k, 0.37F, 1, 1, 0.12F);
        for (float x : new float[]{-3.2F, 3.2F}) {
            langue(x, 6.5F, -2, v(x * 0.15F, 0.6F, -1).normalize(), 7 * k, 3 * k, 0.5F + x, 1, 0.8F, 0.15F);
        }
    }

    /** Les yeux dorés qui brillent (repère de la tête). */
    private static void yeux(float k) {
        if (k <= 0.02F) {
            return;
        }
        for (float x : new float[]{-2, 2}) {
            lueur(x, 3.5F, 4.3F, 4.5F * k, OR, 1);
            lueur(x, 3.5F, 4.3F, 1.6F * k, new float[]{1, 1, 0.85F}, 1);
        }
    }

    /** La queue : 5 longues plumes de flamme qui tombent derrière et remontent au bout (repère du joueur). */
    private static void queue(float k) {
        if (k <= 0.02F) {
            return;
        }
        float len = 30 * k;
        for (int i = 0; i < 5; i++) {
            float a = (i - 2) * 0.24F, g = 0.6F + i * 0.13F;
            int ii = i;
            ruban((s, o) -> {
                float sw = (float) Math.sin(T * 3.2 + ii * 1.3 + s * 3) * s * 3.5F;
                o.set((float) Math.sin(a) * len * s + sw, 12.5F - len * 0.55F * s + len * 0.32F * s * s, -2.4F - (float) Math.cos(a) * len * s);
            }, s -> 6.5F * k * formePlume(s), g, k, 0.95F, 1.6F, len / 20, 0.55F);
        }
        lueur(0, 12.5F, -2.4F, 12 * k, CYAN, 0.3F * k);
    }

    /**
     * L'aura : chaque partie redessinée gonflée d'un demi-pixel (comme les manteaux des autres pouvoirs). Le bord brille
     * (selon l'angle de vue), le bruit qui monte vient de la position dans le monde.
     */
    private static void coque(boolean fin, int niv, float k) {
        if (k <= 0.02F) {
            return;
        }
        Vec3 cam = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        float[][] b = boites(fin);
        float g = 0.5F;
        for (int p = 0; p < 6; p++) {
            boolean bras = p == BRAS_D || p == BRAS_G;
            if (!(niv >= 3 || bras || (niv >= 2 && p != TETE))) {
                continue;
            }
            float x0 = b[p][0] - g, y0 = b[p][1] - g, z0 = b[p][2] - g, x1 = b[p][3] + g, y1 = b[p][4] + g, z1 = b[p][5] + g;
            float[][][] faces = {
                    {{x1, y0, z0}, {x1, y1, z0}, {x1, y1, z1}, {x1, y0, z1}, {1, 0, 0}},
                    {{x0, y0, z1}, {x0, y1, z1}, {x0, y1, z0}, {x0, y0, z0}, {-1, 0, 0}},
                    {{x0, y1, z0}, {x0, y1, z1}, {x1, y1, z1}, {x1, y1, z0}, {0, 1, 0}},
                    {{x0, y0, z1}, {x0, y0, z0}, {x1, y0, z0}, {x1, y0, z1}, {0, -1, 0}},
                    {{x0, y0, z1}, {x1, y0, z1}, {x1, y1, z1}, {x0, y1, z1}, {0, 0, 1}},
                    {{x1, y0, z0}, {x0, y0, z0}, {x0, y1, z0}, {x1, y1, z0}, {0, 0, -1}}};
            for (float[][] f : faces) {
                Vector3f nn = N[p].transform(f[4][0], f[4][1], f[4][2], new Vector3f()).normalize();
                Vector3f[] c = new Vector3f[4];
                float[] al = new float[4];
                float[][] uv = new float[4][];
                for (int q = 0; q < 4; q++) {
                    c[q] = M[p].transformPosition(f[q][0] / 16, f[q][1] / 16, f[q][2] / 16, new Vector3f());
                    Vector3f vue = new Vector3f(c[q]).negate().normalize();
                    float fr = (float) Math.pow(1 - Math.abs(nn.dot(vue)), 1.4);
                    al[q] = (0.05F + 0.95F * fr) * k;
                    double wx = c[q].x + cam.x, wy = c[q].y + cam.y, wz = c[q].z + cam.z;
                    uv[q] = new float[]{(float) ((wx + wz) * 0.56 % 64), (float) (wy * 0.48 % 64)};
                }
                Feu.aura(c, al, uv);
            }
        }
    }

    private Plumage() {
    }
}
