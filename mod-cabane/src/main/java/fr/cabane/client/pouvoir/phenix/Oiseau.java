package fr.cabane.client.pouvoir.phenix;

import static fr.cabane.client.pouvoir.phenix.Effets.*;
import static fr.cabane.client.pouvoir.phenix.Feu.*;

import org.joml.Vector3f;

/**
 * Les ailes de flammes et le phénix complet (l'oiseau), comme dans l'aperçu docs/phenix/apercu. Tout est en pixels dans
 * le repère courant de {@link Feu} : x à gauche, y en haut, z devant.
 */
public final class Oiseau {
    // trois os (épaule → coude → poignet → bout), entre trois poses : repliées vers le haut dans le dos, ouvertes en V,
    // enroulées devant lui
    private static final float[][] OUV = {{13, 8, -4}, {27, 19, -7}, {45, 27, -11}};
    private static final float[][] REP = {{5, 5, -4}, {7, 16, -6}, {5, 27, -8}};
    private static final float[][] ENR = {{8, -1, 5}, {3, -4, 13}, {-5, -9, 15}};

    /** Une plume de l'aile : sur quel os (0, 1, 2), où le long, sa longueur, sa largeur, son or, combien elle suit l'os. */
    private record Pl(int os, float u, float len, float w, float or, float mix) {
    }

    private static final Pl[] PL;

    static {
        Pl[] l = new Pl[23];
        int k = 0;
        for (int i = 0; i < 9; i++) {
            l[k++] = new Pl(2, i / 8F, 24 + 16 * (i / 8F), 11, 0.9F, 1 - 0.72F * (i / 8F));
        }
        for (int i = 0; i < 8; i++) {
            l[k++] = new Pl(1, (i + 0.5F) / 8, 22 - 3 * (i / 8F), 12, 0.45F, 1);
        }
        for (int i = 0; i < 6; i++) {
            l[k++] = new Pl(0, (i + 0.5F) / 6, 13, 10, 0.12F, 1);
        }
        PL = l;
    }

    /** Les réglages d'une aile. */
    public static final class Aile {
        public float taille = 1, ouv = 1, enr = 0, bat = 0, avance = 0, k = 1, plume = 1, lng = 1;

        public Aile taille(float v) {
            taille = v;
            return this;
        }

        public Aile ouv(float v) {
            ouv = v;
            return this;
        }

        public Aile enr(float v) {
            enr = v;
            return this;
        }

        public Aile bat(float v) {
            bat = v;
            return this;
        }

        public Aile avance(float v) {
            avance = v;
            return this;
        }

        public Aile k(float v) {
            k = v;
            return this;
        }

        public Aile plume(float v) {
            plume = v;
            return this;
        }

        public Aile lng(float v) {
            lng = v;
            return this;
        }
    }

    private static float[] mix3(float[] a, float[] b, float u) {
        return new float[]{a[0] + (b[0] - a[0]) * u, a[1] + (b[1] - a[1]) * u, a[2] + (b[2] - a[2]) * u};
    }

    private static Vector3f tr(float x, float y, float z, int cote, float bat, float av) {
        x *= cote;
        float c = (float) Math.cos(cote * bat), s = (float) Math.sin(cote * bat);
        float x1 = x * c - y * s, y1 = x * s + y * c;
        float a = -cote * av, ca = (float) Math.cos(a), sa = (float) Math.sin(a);
        return new Vector3f(x1 * ca + z * sa, y1, -x1 * sa + z * ca);
    }

    /** Une aile attachée en (ex, ey, ez), cote = +1 (gauche) ou -1 (droite). */
    public static void aile(float ex, float ey, float ez, int cote, Aile o) {
        if (o.k < 0.02F) {
            return;
        }
        float ouv = o.ouv, enr = o.enr, bat = o.bat, av = o.avance, k = o.k;
        // repliées, elles sont plus petites (elles dépassent d'un bloc au-dessus des épaules)
        float tl = o.taille * (0.58F + 0.42F * Math.max(ouv, enr));
        float[][] os = new float[4][];
        os[0] = new float[]{0, 0, 0};
        for (int j = 0; j < 3; j++) {
            os[j + 1] = mix3(mix3(REP[j], OUV[j], ouv), ENR[j], enr);
        }
        float[] trail = mix3(mix3(new float[]{0, -1, -0.3F}, new float[]{0, -0.92F, -0.42F}, ouv), new float[]{-0.5F, -1, 0.15F}, enr);
        float[] nrm = mix3(mix3(new float[]{0, 0, 1}, new float[]{0, 1, 0.1F}, ouv), new float[]{0.75F, 0, 0.6F}, enr);
        Vector3f[] osM = new Vector3f[4];
        for (int j = 0; j < 4; j++) {
            osM[j] = tr(os[j][0], os[j][1], os[j][2], cote, bat, av).mul(tl).add(ex, ey, ez);
        }
        Vector3f nM = tr(nrm[0], nrm[1], nrm[2], cote, bat, av).normalize(), tM = tr(trail[0], trail[1], trail[2], cote, bat, av).normalize();
        float deploie = o.plume;
        for (int i = 0; i < PL.length; i++) {
            Pl f = PL[i];
            Vector3f a = osM[f.os()], b = osM[f.os() + 1];
            Vector3f base = new Vector3f(a).lerp(b, f.u());
            Vector3f le = new Vector3f(b).sub(a).normalize();
            Vector3f d = new Vector3f(le).lerp(tM, f.mix()).normalize();
            float len = f.len() * tl * deploie * o.lng;
            if (len < 0.5F) {
                continue;
            }
            float g = i * 0.137F + (cote > 0 ? 0.5F : 0);
            // la largeur suit l'os : les plumes voisines se chevauchent et forment une surface continue
            Vector3f side = new Vector3f(le).sub(new Vector3f(d).mul(le.dot(d)));
            if (side.lengthSquared() < 0.01F) {
                d.cross(nM, side);
            }
            side.normalize();
            float w = f.w() * tl * deploie;
            ruban((s, out) -> {
                float fl = (float) Math.sin(T * 9 + g * 40 + s * 4) * s * 1.6F * tl;
                out.set(base.x + d.x * len * s + nM.x * fl, base.y + d.y * len * s + nM.y * fl, base.z + d.z * len * s + nM.z * fl);
            }, s -> w * formePlume(s), g, k * 0.85F, f.or(), 1.7F, len / 22, 0.75F, side);
        }
        // des petites flammes orange qui naissent et s'éteignent sur l'aile
        for (int j = 0; j < 7; j++) {
            float x = T / (1.1F + 0.4F * hache(j, 3, cote + 5)) + hache(j, 4, cote + 5);
            int cyc = (int) Math.floor(x);
            float u = x - cyc;
            Pl f = PL[(int) (hache(j, cyc, cote + 9) * PL.length) % PL.length];
            Vector3f a = osM[f.os()], b = osM[f.os() + 1];
            Vector3f base = new Vector3f(a).lerp(b, f.u());
            Vector3f d = new Vector3f(b).sub(a).normalize().lerp(tM, f.mix()).normalize();
            float s = 0.25F + 0.6F * hache(j, cyc, 3);
            Vector3f p = new Vector3f(base).add(new Vector3f(d).mul(f.len() * tl * s));
            langue(p.x, p.y, p.z, HAUT, 6 * tl * cloche(u) * k, 4 * tl * k, j * 0.4F + cyc * 0.1F, k, 2, 0.2F);
            lueur(p.x, p.y, p.z, 8 * tl * cloche(u), ORANGE, 0.4F * k * cloche(u));
        }
        // le bord d'attaque brûle (des langues qui montent) et une lueur le long des os
        for (int j = 0; j < 9; j++) {
            float u = j / 8F * 2.999F;
            int oi = (int) Math.floor(u);
            Vector3f p = new Vector3f(osM[oi]).lerp(osM[oi + 1], u - oi);
            Vector3f dir = new Vector3f(HAUT).add(new Vector3f(nM).mul(0.35F)).normalize();
            langue(p.x, p.y, p.z, dir, 6.5F * tl * deploie, 3.6F * tl, j * 0.21F + (cote > 0 ? 0.3F : 0), k * 0.9F, 0, 0.22F);
            if (j % 2 == 0) {
                lueur(p.x, p.y, p.z, 16 * tl * deploie, CYAN, 0.16F * k);
            }
        }
    }

    private static final float[][] DOS = {{0, 6, 16}, {0, 3.5F, 9}, {0, 1.5F, 2}, {0, 0.5F, -5}, {0, 1, -12}};

    private static Vector3f dos(float s) {
        float u = s * 4;
        int i = Math.min(3, (int) Math.floor(u));
        float[] p = mix3(DOS[i], DOS[i + 1], u - i);
        return new Vector3f(p[0], p[1], p[2]);
    }

    /**
     * Le phénix complet, au centre du repère (avant +z) : e = échelle (1 ≈ un joueur), bat = l'angle des ailes, k =
     * l'intensité, ouv = l'ouverture des ailes, lng = la longueur des plumes (plus longues en piqué).
     */
    public static void oiseau(float e, float bat, float k, float ouv, float avance, float lng) {
        if (k <= 0.01F) {
            return;
        }
        // le corps : 5 rubans le long du dos, qui coulent vers la queue
        float[][] corps = {{0, 0, 10}, {2.4F, 0.4F, 7.5F}, {-2.4F, 0.4F, 7.5F}, {0, -2, 7.5F}, {0, 2.2F, 6.5F}};
        for (int i = 0; i < corps.length; i++) {
            float ox = corps[i][0], oy = corps[i][1], W = corps[i][2];
            int ii = i;
            ruban((s, o) -> {
                Vector3f p = dos(s);
                float c = (float) Math.sin(Math.PI * s);
                o.set((p.x + ox * c + (float) Math.sin(T * 6 + ii + s * 6) * 0.5F) * e, (p.y + oy * c) * e, p.z * e);
            }, s -> W * e * (float) Math.pow(Math.sin(Math.PI * (0.12 + 0.88 * s)), 0.5), 0.3F + i * 0.17F, k, i == 0 ? 0.25F : 0, 2.2F, 1.6F, 0.35F);
        }
        for (int i = 0; i < 4; i++) {
            Vector3f p = dos(0.15F + i * 0.22F).mul(e);
            lueur(p.x, p.y, p.z, 22 * e, CYAN, 0.3F * k);
        }
        lueur(0, 6 * e, 16 * e, 12 * e, BLANC, 0.4F * k);
        // la tête : la crête, le bec et les yeux dorés
        langue(0, 8 * e, 14 * e, v(0, 0.75F, -1).normalize(), 12 * e, 4.2F * e, 0.11F, k, 1, 0.12F);
        langue(0, 7.5F * e, 12.5F * e, v(0, 1, -0.5F).normalize(), 14 * e, 4.6F * e, 0.27F, k, 1, 0.12F);
        langue(0, 6.5F * e, 12 * e, v(0, 0.3F, -1).normalize(), 10 * e, 3.6F * e, 0.41F, k, 1, 0.12F);
        griffe(0, 5.6F * e, 18 * e, 0, 3.8F * e, 23.5F * e, 1.5F * e * Math.min(1, k * 2));
        for (int c = -1; c <= 1; c += 2) {
            lueur(c * 1.9F * e, 6.6F * e, 17.2F * e, 3.4F * e, OR, k);
            lueur(c * 1.9F * e, 6.6F * e, 17.2F * e, 1.2F * e, new float[]{1, 1, 0.85F}, k);
        }
        // les ailes
        for (int c = -1; c <= 1; c += 2) {
            aile(c * 3.2F * e, 3.5F * e, 5 * e, c, new Aile().taille(1.25F * e).ouv(ouv).bat(bat).avance(avance).k(k).plume(1.15F).lng(lng));
        }
        // la queue : 5 longues plumes en éventail
        for (int i = 0; i < 5; i++) {
            float a = (i - 2) * 0.2F;
            int ii = i;
            ruban((s, o) -> o.set(((float) Math.sin(a) * s * 38 + (float) Math.sin(T * 4 + ii + s * 4) * s * 3) * e, (1 - 3 * s + 5 * s * s) * e,
                    (-12 - (float) Math.cos(a) * s * 38) * e), s -> 6.5F * e * formePlume(s), 0.7F + i * 0.11F, k, 0.95F, 1.8F, 2, 0.55F);
        }
        // les serres repliées
        for (int c = -1; c <= 1; c += 2) {
            griffe(c * 2 * e, -2.5F * e, -2 * e, c * 2 * e, -6 * e, -7 * e, 0.9F * e * Math.min(1, k * 2));
        }
    }

    private Oiseau() {
    }
}
