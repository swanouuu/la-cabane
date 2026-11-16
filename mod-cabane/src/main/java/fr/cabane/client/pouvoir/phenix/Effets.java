package fr.cabane.client.pouvoir.phenix;

import static fr.cabane.client.pouvoir.phenix.Feu.*;

import org.joml.Vector3f;

/**
 * Les effets du phénix (les mêmes que l'aperçu docs/phenix/apercu) : ondes, explosions, éclats, braises, spirales,
 * colonnes de feu, plumes lancées, œuf, rayons, soins. Tout se dessine dans le repère courant de {@link Feu}, en pixels
 * (16 par bloc), le haut vers +y.
 */
public final class Effets {
    public static final float TOUR = (float) (Math.PI * 2);
    public static final Vector3f HAUT = new Vector3f(0, 1, 0);

    public static float seg(float t, float a, float b) {
        return Math.max(0, Math.min(1, (t - a) / (b - a)));
    }

    public static float lisse(float u) {
        u = Math.max(0, Math.min(1, u));
        return u * u * (3 - 2 * u);
    }

    public static float sortie(float u) {
        u = Math.max(0, Math.min(1, u));
        return 1 - (1 - u) * (1 - u) * (1 - u);
    }

    public static float elastique(float u) {
        return u <= 0 ? 0 : u >= 1 ? 1 : (float) (1 - Math.cos(u * Math.PI * 2.5) * Math.exp(-u * 5.5));
    }

    public static float cloche(float u) {
        return u <= 0 || u >= 1 ? 0 : (float) Math.sin(Math.PI * u);
    }

    public static float entre(float a, float b, float u) {
        return a + (b - a) * u;
    }

    public static float hache(int i, int j, int k) {
        int h = i * 374761393 + j * 668265263 + k * 2147483647;
        h = (h ^ (h >>> 13)) * 1274126177;
        return (float) (((h ^ (h >>> 16)) & 0xffffffffL) / 4294967296.0);
    }

    public static float hache(int i, int j) {
        return hache(i, j, 0);
    }

    public static Vector3f v(float x, float y, float z) {
        return new Vector3f(x, y, z);
    }

    /** Une onde au sol : un anneau plat de flamme et des langues dressées. */
    public static void onde(float px, float py, float pz, float r, float k, float haut) {
        if (k <= 0.01F || r <= 0) {
            return;
        }
        int n = 12;
        for (int i = 0; i < n; i++) {
            float a0 = i / (float) n * TOUR, a1 = (i + 1.05F) / n * TOUR;
            ruban((s, o) -> {
                float a = entre(a0, a1, s);
                o.set(px + (float) Math.cos(a) * r, py + 0.6F, pz + (float) Math.sin(a) * r);
            }, s -> 7 + r * 0.05F, i * 0.09F, k, 0, 2, r / 30, 0, HAUT);
        }
        int m = Math.min(48, (int) (8 + r / 4));
        for (int i = 0; i < m; i++) {
            float a = i / (float) m * TOUR;
            Vector3f d = new Vector3f((float) Math.cos(a) * 0.4F, 1, (float) Math.sin(a) * 0.4F).normalize();
            langue(px + (float) Math.cos(a) * r, py, pz + (float) Math.sin(a) * r, d, haut * k * (0.7F + 0.6F * hache(i, 7)), 5, i * 0.07F, k,
                    hache(i, 3) > 0.7F ? 0.7F : 0, 0.22F);
        }
    }

    /** Une explosion : des langues qui rayonnent, une lueur, une onde au sol si elle est près du sol (sol : y du sol, local). */
    public static void explosion(float px, float py, float pz, float age, float vie, float R, float sol) {
        if (age < 0 || age > vie) {
            return;
        }
        float u = age / vie, s = sortie(Math.min(1, u * 1.6F)), k = 1 - u * u;
        for (int i = 0; i < 30; i++) {
            float y = 1 - i / 29F * 2, r = (float) Math.sqrt(Math.max(0, 1 - y * y)), a = i * 2.39996F;
            Vector3f d = new Vector3f((float) Math.cos(a) * r, Math.abs(y) * 0.8F + 0.15F, (float) Math.sin(a) * r).normalize();
            langue(px + d.x * R * s * 0.25F, py + d.y * R * s * 0.25F, pz + d.z * R * s * 0.25F, d, R * s * 0.75F, R * 0.22F * (1 - u * 0.5F), i * 0.05F, k,
                    hache(i, 5) > 0.6F ? 0.9F : 0.2F, 0.1F, 3, 1);
        }
        lueur(px, py, pz, R * 2.4F * (0.4F + s), CYAN, 0.75F * k);
        lueur(px, py, pz, R * 0.9F * (1 - u), BLANC, k);
        lueur(px, py, pz, R * 3 * s, OR, 0.25F * k, 1);
        if (py - sol < 30) {
            onde(px, sol, pz, R * 1.3F * s, k, 10);
        }
    }

    /** Un éclat (touché, flash) : rond + étoile. */
    public static void eclat(float px, float py, float pz, float age, float vie, float k, float[] col) {
        if (age < 0 || age > vie) {
            return;
        }
        float u = age / vie;
        lueur(px, py, pz, 30 * k * (0.5F + u), col, 1 - u * u);
        lueur(px, py, pz, 60 * k * (1.2F - 0.6F * u), BLANC, (1 - u) * 0.8F, 1);
    }

    /** Des braises qui montent (cyan et or). */
    public static void braises(float px, float py, float pz, float r, float h, int n, float k, int g) {
        if (k <= 0.01F) {
            return;
        }
        for (int i = 0; i < n; i++) {
            float ph = (T * (0.45F + 0.4F * hache(i, 1, g)) + hache(i, 2, g)) % 1;
            float a = hache(i, 3, g) * TOUR, rr = r * (float) Math.sqrt(hache(i, 4, g));
            lueur(px + (float) Math.cos(a) * rr + (float) Math.sin(T * 3 + i) * 2, py + ph * h, pz + (float) Math.sin(a) * rr, 2.6F,
                    hache(i, 5, g) > 0.7F ? OR : CYAN, k * cloche(ph));
        }
    }

    /** Une spirale de flammes qui monte autour de p. */
    public static void spirale(float px, float py, float pz, float r, float h, int n, float k, float vitesse, float g) {
        if (k <= 0.01F) {
            return;
        }
        for (int j = 0; j < n; j++) {
            float a0 = j / (float) n * TOUR + T * vitesse;
            ruban((s, o) -> {
                float a = a0 + s * TOUR * 0.9F;
                o.set(px + (float) Math.cos(a) * r * (1 - 0.35F * s), py + s * h, pz + (float) Math.sin(a) * r * (1 - 0.35F * s));
            }, s -> 7 * k * (float) Math.sin(Math.PI * (0.1 + 0.9 * s)), g + j * 0.3F, k, 0.4F, 2.4F, 2, 0.5F);
        }
    }

    /** Une colonne de feu : des langues serrées qui montent très haut. */
    public static void pilier(float px, float py, float pz, float r, float h, float k) {
        if (k <= 0.01F) {
            return;
        }
        for (int i = 0; i < 16; i++) {
            float a = i / 16F * TOUR + hache(i, 2) * 0.4F, rr = r * (0.35F + 0.65F * hache(i, 1));
            langue(px + (float) Math.cos(a) * rr, py, pz + (float) Math.sin(a) * rr, HAUT, h * (0.6F + 0.5F * hache(i, 3)), r * 0.7F, i * 0.11F, k, 0.5F, 0.08F, 3.2F, 1);
        }
        lueur(px, py + h * 0.4F, pz, h * 0.9F, CYAN, 0.35F * k);
    }

    /** Une plume de flamme lancée : la plume (pointe dorée) et sa traînée. dir : vers où elle va (local). */
    public static void plume(float px, float py, float pz, Vector3f dir, float k, float g, float tl) {
        if (k <= 0.01F) {
            return;
        }
        Vector3f d = new Vector3f(dir).normalize();
        ruban((s, o) -> o.set(px - d.x * 12 * tl * s, py - d.y * 12 * tl * s, pz - d.z * 12 * tl * s), s -> 5.5F * tl * formePlume(1 - s), g, k, 0.9F, 2.5F, 0.6F, 0.2F);
        ruban((s, o) -> o.set(px - d.x * (6 + 40 * s) * tl, py - d.y * (6 + 40 * s) * tl, pz - d.z * (6 + 40 * s) * tl), s -> 3.2F * tl * (1 - s), g + 0.5F,
                k * 0.8F, 0, 3, 2, 0.9F);
        lueur(px, py, pz, 12 * tl, CYAN, 0.5F * k);
    }

    /** Un œuf de flammes (la renaissance) ; fele : les fêlures dorées. */
    public static void oeuf(float px, float py, float pz, float r, float k, float fele) {
        if (k <= 0.01F || r <= 0.1F) {
            return;
        }
        for (int i = 0; i < 14; i++) {
            float a = i / 14F * TOUR + T * 0.8F;
            ruban((s, o) -> {
                double th = Math.PI * (1 - s);
                o.set(px + (float) (Math.cos(a) * Math.sin(th)) * r, py + r * 1.35F * (float) (1 - Math.cos(th)), pz + (float) (Math.sin(a) * Math.sin(th)) * r);
            }, s -> r * 0.42F, i * 0.07F, k * 0.85F, 0.15F, 1.4F, 1.4F, 0.25F);
        }
        lueur(px, py + r * 1.3F, pz, r * 3.4F, CYAN, 0.5F * k);
        lueur(px, py + r * 1.3F, pz, r * 1.4F, BLANC, 0.5F * k);
        if (fele > 0) {
            for (int i = 0; i < 7; i++) {
                float a = i * 0.9F + 0.3F, y = py + r * (0.7F + hache(i, 2) * 1.3F);
                Vector3f d = new Vector3f((float) Math.cos(a), (hache(i, 4) - 0.5F) * 0.8F, (float) Math.sin(a)).normalize();
                ruban((s, o) -> o.set(px + d.x * (r + s * r * 3 * fele), y + d.y * s * r * 3 * fele, pz + d.z * (r + s * r * 3 * fele)), s -> 2.4F * (1 - s),
                        i, fele, 1, 4, 1, 0.5F);
            }
            lueur(px, py + r * 1.3F, pz, r * 6 * fele, OR, 0.5F * fele, 1);
        }
    }

    /** Des rayons de lumière (or et cyan) qui partent de p dans toutes les directions. */
    public static void rayons(float px, float py, float pz, float R, float k, int n, int g) {
        if (k <= 0.01F) {
            return;
        }
        for (int i = 0; i < n; i++) {
            float y = (hache(i, 1, g) - 0.3F) * 1.2F, a = i / (float) n * TOUR + hache(i, 2, g) * 0.3F + T * 0.15F;
            Vector3f d = new Vector3f((float) Math.cos(a), y, (float) Math.sin(a)).normalize();
            float L = R * (0.6F + 0.6F * hache(i, 3, g));
            ruban((s, o) -> o.set(px + d.x * L * s, py + d.y * L * s, pz + d.z * L * s), s -> R * 0.06F * (1 - s) + 2, i * 0.1F + g, k * 0.7F, i % 2 == 1 ? 2 : 0.6F, 4, 3, 0.6F);
        }
        lueur(px, py, pz, R * 1.4F, OR, 0.35F * k, 1);
    }

    /** Les soins : des étoiles dorées qui montent. */
    public static void soins(float px, float py, float pz, float age, float vie, int n) {
        if (age < 0 || age > vie) {
            return;
        }
        for (int i = 0; i < n; i++) {
            float u = seg(age, hache(i, 1) * 0.4F * vie, vie);
            if (u <= 0 || u >= 1) {
                continue;
            }
            float a = hache(i, 2) * TOUR, r = 6 + 6 * hache(i, 3);
            lueur(px + (float) Math.cos(a) * r, py + 4 + u * 28, pz + (float) Math.sin(a) * r, 5, OR, cloche(u), 1);
        }
    }

    private Effets() {
    }
}
