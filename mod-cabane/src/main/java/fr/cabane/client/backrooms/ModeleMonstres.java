package fr.cabane.client.backrooms;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import fr.cabane.Cabane;
import fr.cabane.backrooms.Monstre;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Les cinq monstres de l'aperçu (docs/backrooms/apercu/monstres.js), boîte par boîte, avec les mêmes pivots, les mêmes
 * animations et les mêmes textures (l'atlas peint par outils/backrooms.mjs). Repère du modèle : pieds en y = 0, il
 * regarde vers +z, comme dans l'aperçu ; les angles sont ceux de three.js (ordre X, Y, Z).
 */
public final class ModeleMonstres {
    public static final ResourceLocation ATLAS = Cabane.id("textures/entity/backrooms/monstres.png");

    /** Un morceau de l'atlas (coordonnées 0..1). */
    record Tuile(float u0, float v0, float u1, float v1) {
    }

    private static Tuile t(int i) {
        float u = (i % 8) * 16 / 128F, v = (i / 8) * 16 / 128F;
        return new Tuile(u, v, u + 0.125F, v + 0.125F);
    }

    // l'ordre de TUILES dans outils/backrooms.mjs
    static final Tuile PEAU_R = t(0), COTES_R = t(1), DOS_R = t(2), VISAGE_R = t(3), MACHOIRE_R = t(4), NOIR = t(5), JAUNE = t(6), SMILEY = t(7),
            DECHIRE = t(8), CHAPEAU = t(9), BALLON = t(10), BLANC = t(11), FICELLE = t(12), PEAU_H = t(13), CHEVEUX = t(14), CHEVEUX_ARRIERE = t(15),
            CHEVEUX_COTE = t(16), CHEMISE = t(17), CHEMISE_DEVANT = t(18), PANTALON = t(19), CHAUSSURE = t(20), SANS_VISAGE = t(21), FENTE = t(22),
            FOURRURE = t(23), VENTRE_P = t(24), AILE = t(25), OEIL_P = t(26), ANTENNE = t(27);
    static final Tuile SOURIRE = new Tuile(0, 0.5F, 0.25F, 0.75F), SOURIRE_HAUT = new Tuile(0.25F, 0.5F, 0.5F, 0.75F),
            MACHOIRE_S = new Tuile(0.5F, 0.5F, 0.75F, 0.625F);

    /** Les six faces d'une boîte, dans l'ordre de three.js : +x, -x, +y, -y, +z (devant), -z. */
    private static Tuile[] f(Tuile tout) {
        return new Tuile[]{tout, tout, tout, tout, tout, tout};
    }

    private static Tuile[] f(Tuile tout, Tuile px, Tuile nx, Tuile py, Tuile ny, Tuile pz, Tuile nz) {
        return new Tuile[]{px != null ? px : tout, nx != null ? nx : tout, py != null ? py : tout, ny != null ? ny : tout, pz != null ? pz : tout,
                nz != null ? nz : tout};
    }

    /** Ce qui anime le monstre à cette image. */
    public static final class Anim {
        /** Le temps (secondes), la vitesse (blocs par seconde), la phase de la marche. */
        public float t, vitesse, cycle;
        /** En chasse : il court, les bras en avant… */
        public boolean chasse;
        /** La gueule qui s'ouvre (0 à 1), pendant le screamer. */
        public float ouvert;
    }

    // ---------------------------------------------------------------- le dessin des boîtes

    private static final class Dessin {
        final PoseStack ps;
        final VertexConsumer c, lueur;
        final int lumiere, overlay;

        Dessin(PoseStack ps, MultiBufferSource buf, int lumiere, int overlay) {
            this.ps = ps;
            this.c = buf.getBuffer(RenderType.entityCutoutNoCull(ATLAS));
            this.lueur = buf.getBuffer(RenderType.eyes(ATLAS));
            this.lumiere = lumiere;
            this.overlay = overlay;
        }

        void push(float x, float y, float z, float rx, float ry, float rz) {
            ps.pushPose();
            ps.translate(x, y, z);
            if (rx != 0) {
                ps.mulPose(Axis.XP.rotation(rx));
            }
            if (ry != 0) {
                ps.mulPose(Axis.YP.rotation(ry));
            }
            if (rz != 0) {
                ps.mulPose(Axis.ZP.rotation(rz));
            }
        }

        void pop() {
            ps.popPose();
        }

        /** Une boîte w × h × d centrée en (x, y, z). */
        void boite(float x, float y, float z, float w, float h, float d, Tuile[] faces) {
            boite(x, y, z, 0, 0, 0, w, h, d, faces);
        }

        void boite(float x, float y, float z, float rx, float ry, float rz, float w, float h, float d, Tuile[] faces) {
            push(x, y, z, rx, ry, rz);
            cube(w / 2, h / 2, d / 2, faces);
            pop();
        }

        /** Une boîte posée entre deux points (un os de patte, une ficelle), son axe y de a vers b. */
        void tendre(Vector3f a, Vector3f b, float w, float h, float d, Tuile[] faces) {
            Vector3f dir = new Vector3f(b).sub(a);
            ps.pushPose();
            ps.translate((a.x + b.x) / 2, (a.y + b.y) / 2, (a.z + b.z) / 2);
            if (dir.lengthSquared() > 1e-8F) {
                ps.mulPose(new Quaternionf().rotationTo(0, 1, 0, dir.x, dir.y, dir.z));
            }
            cube(w / 2, h / 2, d / 2, faces);
            ps.popPose();
        }

        private void cube(float hx, float hy, float hz, Tuile[] f) {
            // chaque face vue de dehors : bas-gauche, bas-droite, haut-droite, haut-gauche (comme BoxGeometry)
            quad(f[0], 1, 0, 0, hx, -hy, hz, hx, -hy, -hz, hx, hy, -hz, hx, hy, hz, c, lumiere);
            quad(f[1], -1, 0, 0, -hx, -hy, -hz, -hx, -hy, hz, -hx, hy, hz, -hx, hy, -hz, c, lumiere);
            quad(f[2], 0, 1, 0, -hx, hy, hz, hx, hy, hz, hx, hy, -hz, -hx, hy, -hz, c, lumiere);
            quad(f[3], 0, -1, 0, -hx, -hy, -hz, hx, -hy, -hz, hx, -hy, hz, -hx, -hy, hz, c, lumiere);
            quad(f[4], 0, 0, 1, -hx, -hy, hz, hx, -hy, hz, hx, hy, hz, -hx, hy, hz, c, lumiere);
            quad(f[5], 0, 0, -1, hx, -hy, -hz, -hx, -hy, -hz, -hx, hy, -hz, hx, hy, -hz, c, lumiere);
        }

        /** Une plaque w × h tournée vers +z, centrée en (x, y, z) : qui brille dans le noir ({@code brille}), ou non. */
        void plaque(float x, float y, float z, float w, float h, Tuile t, boolean brille) {
            float hw = w / 2, hh = h / 2;
            quad(t, 0, 0, 1, x - hw, y - hh, z, x + hw, y - hh, z, x + hw, y + hh, z, x - hw, y + hh, z, brille ? lueur : c,
                    brille ? LightTexture.FULL_BRIGHT : lumiere);
        }

        private void quad(Tuile t, float nx, float ny, float nz, float x0, float y0, float z0, float x1, float y1, float z1, float x2, float y2,
                float z2, float x3, float y3, float z3, VertexConsumer v, int l) {
            PoseStack.Pose p = ps.last();
            v.addVertex(p, x0, y0, z0).setColor(-1).setUv(t.u0, t.v1).setOverlay(overlay).setLight(l).setNormal(p, nx, ny, nz);
            v.addVertex(p, x1, y1, z1).setColor(-1).setUv(t.u1, t.v1).setOverlay(overlay).setLight(l).setNormal(p, nx, ny, nz);
            v.addVertex(p, x2, y2, z2).setColor(-1).setUv(t.u1, t.v0).setOverlay(overlay).setLight(l).setNormal(p, nx, ny, nz);
            v.addVertex(p, x3, y3, z3).setColor(-1).setUv(t.u0, t.v0).setOverlay(overlay).setLight(l).setNormal(p, nx, ny, nz);
        }
    }

    /** Le monstre entier, pieds à l'origine du repère courant, tourné vers +z. */
    public static void dessiner(Monstre.Sorte sorte, PoseStack ps, MultiBufferSource buf, int lumiere, int overlay, Anim a) {
        Dessin d = new Dessin(ps, buf, lumiere, overlay);
        switch (sorte) {
            case RAMPEUR -> rampeur(d, a);
            case SOURIANT -> souriant(d, a);
            case FETARD -> fetard(d, a);
            case SANSVISAGE -> sansvisage(d, a);
            case PHALENE -> phalene(d, a);
        }
    }

    /** Où est son visage (pour le screamer, qui le colle contre l'écran), et à quelle distance il s'arrête. */
    public static float[] visage(Monstre.Sorte sorte) {
        return switch (sorte) {
            case RAMPEUR -> new float[]{0, 0.64F, 1.1F, 0.43F};
            case SOURIANT -> new float[]{0, 2.21F, 0.33F, 0.43F};
            case FETARD -> new float[]{0, 1.88F, 0.3F, 0.43F};
            case SANSVISAGE -> new float[]{0, 1.76F, 0.25F, 0.4F};
            case PHALENE -> new float[]{0, 1.77F, 0.38F, 0.3F};
        };
    }

    private static float sin(double x) {
        return (float) Math.sin(x);
    }

    // ---------------------------------------------------------------- le Rampeur

    /** Le genou (ou le coude) d'une patte de deux os L1, L2 qui va de h à f, plié du côté de « pole » ; et le bout atteint. */
    private static Vector3f[] plier(Vector3f h, Vector3f f, float l1, float l2, Vector3f pole) {
        Vector3f d = new Vector3f(f).sub(h);
        float n = Math.min(d.length(), l1 + l2 - 0.001F);
        Vector3f dir = d.normalize();
        float a = (l1 * l1 - l2 * l2 + n * n) / (2 * n);
        float hh = (float) Math.sqrt(Math.max(0, l1 * l1 - a * a));
        Vector3f p = new Vector3f(pole).sub(new Vector3f(dir).mul(pole.dot(dir))).normalize();
        Vector3f genou = new Vector3f(h).add(new Vector3f(dir).mul(a)).add(p.mul(hh));
        Vector3f bout = new Vector3f(h).add(new Vector3f(dir).mul(n));
        return new Vector3f[]{genou, bout};
    }

    /** Les quatre pattes : x du côté, avant ou non, phase, hanche, pied, os, et le côté où plie le genou. */
    private static final float[][] PATTES = new float[4][];

    static {
        int n = 0;
        for (int sx : new int[]{1, -1}) {
            PATTES[n++] = new float[]{sx, 1, sx > 0 ? 0 : (float) Math.PI, sx * 0.27F, 0.86F, 0.3F, sx * 0.62F, 0, 0.98F, 0.62F, 0.92F, sx, 1.2F, 0.25F};
            PATTES[n++] = new float[]{sx, 0, sx > 0 ? (float) Math.PI : 0, sx * 0.19F, 0.64F, -0.52F, sx * 0.55F, 0, -0.86F, 0.62F, 0.72F, sx * 0.8F, 1, -0.6F};
        }
    }

    private static void rampeur(Dessin d, Anim a) {
        Tuile[] peau = f(PEAU_R);
        boolean ouvert = a.ouvert > 0;
        float k = Math.min(1, a.vitesse / 1.5F);
        float pas = a.chasse ? 0.42F : 0.3F, leve = a.chasse ? 0.24F : 0.16F;
        float bob = Math.abs(sin(a.cycle)) * 0.05F * k;
        // le corps : un bassin bas derrière, une cage thoracique plus haute devant, la colonne qui ressort
        d.push(0, bob, 0, 0, 0, sin(a.cycle) * 0.04F * k);
        d.boite(0, 0.66F, -0.4F, 0.36F, 0.26F, 0.42F, f(PEAU_R, null, null, DOS_R, null, null, null));
        d.boite(0, 0.8F, 0.1F, -0.14F, 0, 0, 0.48F, 0.36F, 0.6F, f(PEAU_R, COTES_R, COTES_R, DOS_R, null, null, null));
        d.boite(0, 0.7F, -0.16F, 0.26F, 0.2F, 0.3F, peau);
        float[][] bosses = {{-0.52F, 0.82F}, {-0.3F, 0.84F}, {-0.08F, 1.0F}, {0.14F, 1.03F}, {0.34F, 1.06F}};
        for (float[] b : bosses) {
            d.boite(0, b[1], b[0], 0.08F, 0.08F, 0.08F, peau);
        }
        d.tendre(new Vector3f(0, 0.86F, 0.38F), new Vector3f(0, 0.66F, 0.7F), 0.15F, 0.32F, 0.15F, peau);
        // la tête qui pend entre les bras, le visage levé vers toi, qui tressaute
        float rz = sin(a.t * 23) * 0.04F + (Math.sin(a.t * 1.7) > 0.93 ? sin(a.t * 60) * 0.25F : 0);
        d.push(0, 0.64F, 0.8F, ouvert ? 0 : a.chasse ? -0.5F : -0.3F, 0, rz);
        d.boite(0, 0, 0.1F, 0.46F, 0.42F, 0.44F, f(PEAU_R, null, null, null, null, VISAGE_R, null));
        d.push(0, -0.2F, -0.1F, 0.18F + a.ouvert * 0.85F, 0, 0);
        d.boite(0, -0.03F, 0.21F, 0.4F, 0.12F, 0.42F, f(PEAU_R, null, null, null, null, MACHOIRE_R, null));
        d.pop();
        d.pop();
        d.pop();
        // les quatre pattes : bras longs devant (coudes en l'air), jambes de grenouille derrière (genoux en l'air)
        for (float[] p : PATTES) {
            boolean avant = p[1] > 0;
            float ph = a.cycle + p[2];
            Vector3f h = new Vector3f(p[3], p[4] + bob, p[5]);
            Vector3f fp = new Vector3f(p[6], Math.max(0, (float) Math.cos(ph)) * leve * k, p[8] + sin(ph) * pas * k);
            Vector3f[] kf = plier(h, fp, p[9], p[10], new Vector3f(p[11], p[12], p[13]));
            d.tendre(h, kf[0], 0.11F, p[9], 0.11F, peau);
            d.tendre(kf[0], kf[1], 0.09F, p[10], 0.09F, peau);
            d.push(kf[1].x, kf[1].y, kf[1].z, 0, 0, 0);
            for (float dx : new float[]{-0.05F, 0, 0.05F}) {
                d.boite(dx, 0.012F, avant ? 0.1F : 0.05F, 0, dx * 3, 0, 0.03F, 0.025F, avant ? 0.26F : 0.16F, peau);
            }
            d.pop();
        }
    }

    // ---------------------------------------------------------------- le Souriant

    private static void souriant(Dessin d, Anim a) {
        Tuile[] peau = f(NOIR);
        float amp = Math.min(1, a.vitesse / 2) * 0.6F;
        for (int i = 0; i < 2; i++) {
            float s = i == 0 ? -1 : 1;
            d.push(s * 0.13F, 1.15F, 0, (i == 0 ? 1 : -1) * sin(a.cycle) * amp, 0, 0);
            d.boite(0, -0.575F, 0, 0.16F, 1.15F, 0.16F, peau);
            d.pop();
        }
        d.boite(0, 1.5F, 0, 0.46F, 0.76F, 0.24F, peau);
        for (int i = 0; i < 2; i++) {
            float s = i == 0 ? -1 : 1;
            float rx = a.chasse ? -1.35F + sin(a.cycle + i * Math.PI) * 0.15F : -sin(a.cycle + i * Math.PI) * amp * 0.5F;
            d.push(s * 0.31F, 1.84F, 0, rx, 0, (i == 1 ? -1 : 1) * 0.06F);
            d.boite(0, -0.6F, 0, 0.13F, 1.2F, 0.13F, peau);
            for (float dx : new float[]{-0.045F, 0, 0.045F}) {
                d.boite(dx, -1.34F, 0, 0.03F, 0.32F, 0.03F, peau);
            }
            d.pop();
        }
        // la tête toute noire : on ne voit que le sourire et les yeux, qui brillent
        d.push(0, 1.9F, 0, a.chasse ? 0.12F : 0, 0, sin(a.t * 0.9) * 0.12F);
        d.boite(0, 0.31F, 0, 0.62F, 0.62F, 0.62F, peau);
        boolean ouvert = a.ouvert > 0.15F;
        d.plaque(0, 0.31F, 0.313F, 0.62F, 0.62F, ouvert ? SOURIRE_HAUT : SOURIRE, true);
        d.push(0, 0.14F, -0.25F, a.ouvert * 0.42F, 0, 0);
        d.boite(0, -0.07F, 0.25F, 0.56F, 0.14F, 0.5F, peau);
        if (ouvert) {
            d.plaque(0, -0.07F, 0.502F, 0.56F, 0.28F, MACHOIRE_S, true);
        }
        d.pop();
        d.pop();
    }

    // ---------------------------------------------------------------- le Fêtard

    private static void fetard(Dessin d, Anim a) {
        Tuile[] peau = f(JAUNE);
        float amp = Math.min(1, a.vitesse / 2) * 0.7F;
        d.push(0, Math.abs(sin(a.cycle)) * 0.06F * amp, 0, 0, 0, 0);
        for (int i = 0; i < 2; i++) {
            float s = i == 0 ? -1 : 1;
            d.push(s * 0.13F, 0.9F, 0, (i == 0 ? 1 : -1) * sin(a.cycle) * amp, 0, 0);
            d.boite(0, -0.45F, 0, 0.22F, 0.9F, 0.22F, peau);
            d.pop();
        }
        d.boite(0, 1.25F, 0, 0.5F, 0.7F, 0.28F, peau);
        for (int i = 0; i < 2; i++) {
            float s = i == 0 ? -1 : 1;
            float rx;
            if (a.chasse) {
                // il « danse » en courant : le bras libre qui bat au-dessus de la tête, l'autre garde son ballon
                rx = i == 0 ? -0.45F + sin(a.t * 14) * 0.2F : -2.6F - sin(a.t * 14) * 0.5F;
            } else {
                rx = i == 0 ? -0.45F + sin(a.cycle) * 0.1F : -sin(a.cycle) * amp * 0.6F;
            }
            d.push(s * 0.36F, 1.56F, 0, rx, 0, 0);
            d.boite(0, -0.33F, 0, 0.18F, 0.72F, 0.18F, peau);
            d.pop();
        }
        d.push(0, 1.6F, 0, -a.ouvert * 0.15F, 0, sin(a.t * 1.3) * 0.3F);
        d.boite(0, 0.28F, 0, 0.56F, 0.56F, 0.56F, f(JAUNE, null, null, null, null, a.ouvert > 0.15F ? DECHIRE : SMILEY, null));
        // le chapeau pointu à rayures
        d.push(0.06F, 0.56F, 0, 0, 0, -0.22F);
        float[][] etages = {{0.34F, 0.06F}, {0.25F, 0.18F}, {0.16F, 0.3F}, {0.08F, 0.41F}};
        for (int i = 0; i < etages.length; i++) {
            float w = etages[i][0];
            d.boite(0, etages[i][1], 0, w, 0.12F, w, f(i == 3 ? BLANC : CHAPEAU));
        }
        d.pop();
        d.pop();
        // le ballon rouge au bout de sa ficelle (sous le plafond, à trois blocs au-dessus des pieds)
        d.push(0, 0, 0, sin(a.t * 0.8) * 0.04F, 0, sin(a.t * 1.1) * 0.05F);
        d.boite(-0.52F, 2.6F, 0.2F, 0.4F, 0.46F, 0.4F, f(BALLON));
        d.tendre(new Vector3f(-0.37F, 0.95F, 0.3F), new Vector3f(-0.5F, 2.37F, 0.2F), 0.02F, 1.43F, 0.02F, f(FICELLE));
        d.pop();
        d.pop();
    }

    // ---------------------------------------------------------------- le Sans-Visage

    private static void sansvisage(Dessin d, Anim a) {
        float amp = Math.min(1, a.vitesse / 2) * 0.65F;
        // en chasse il court penché, les bras raides le long du corps, la tête tombée sur le côté
        d.push(0, 0, 0, a.chasse ? 0.12F : 0, 0, 0);
        for (int i = 0; i < 2; i++) {
            float s = i == 0 ? -1 : 1;
            d.push(s * 0.12F, 0.86F, 0, (i == 0 ? 1 : -1) * sin(a.cycle) * amp, 0, 0);
            d.boite(0, -0.43F, 0, 0.22F, 0.86F, 0.22F, f(PANTALON, null, null, null, CHAUSSURE, null, null));
            d.pop();
        }
        d.boite(0, 1.19F, 0, 0.48F, 0.66F, 0.26F, f(CHEMISE, null, null, null, null, CHEMISE_DEVANT, null));
        for (int i = 0; i < 2; i++) {
            float s = i == 0 ? -1 : 1;
            float rx = a.chasse ? 0 : (i == 0 ? -1 : 1) * sin(a.cycle) * amp * 0.7F;
            d.push(s * 0.33F, 1.48F, 0, rx, 0, 0);
            d.boite(0, -0.3F, 0, 0.18F, 0.66F, 0.18F, f(CHEMISE, null, null, null, PEAU_H, null, null));
            d.pop();
        }
        float rz = a.ouvert > 0 ? 0.18F * (1 - a.ouvert) : a.chasse ? 0.42F : sin(a.t * 0.5) * 0.06F;
        d.push(0, 1.52F, 0, 0, 0, rz);
        d.boite(0, 0.24F, 0, 0.48F, 0.48F, 0.48F,
                f(CHEVEUX_COTE, null, null, CHEVEUX, PEAU_H, a.ouvert > 0.15F ? FENTE : SANS_VISAGE, CHEVEUX_ARRIERE));
        d.pop();
        d.pop();
    }

    // ---------------------------------------------------------------- la Phalène

    private static void phalene(Dessin d, Anim a) {
        d.push(0, 1.75F + sin(a.t * 2.3) * 0.12F, 0, a.chasse ? 0.28F : sin(a.t * 1.1) * 0.06F, 0, 0);
        d.boite(0, 0, 0, 0.32F, 0.3F, 0.38F, f(FOURRURE));
        d.boite(0, -0.1F, -0.45F, -0.32F, 0, 0, 0.27F, 0.25F, 0.62F, f(VENTRE_P));
        // la tête : de gros yeux, des mandibules, deux antennes en plumes
        d.push(0, 0.02F, 0.24F, 0, 0, 0);
        d.boite(0, 0, 0, 0.28F, 0.26F, 0.2F, f(FOURRURE));
        for (int s = -1; s <= 1; s += 2) {
            d.boite(s * 0.13F, 0.03F, 0.06F, 0.14F, 0.17F, 0.14F, f(OEIL_P));
        }
        for (int i = 0; i < 2; i++) {
            float s = i == 0 ? -1 : 1;
            d.push(s * 0.05F, -0.1F, 0.1F, 0, 0, (i == 1 ? -1 : 1) * (0.2F + a.ouvert * 0.9F));
            d.boite(0, -0.06F, 0, 0.04F, 0.12F, 0.05F, f(CHAUSSURE));
            d.pop();
        }
        for (int i = 0; i < 2; i++) {
            float s = i == 0 ? -1 : 1;
            d.push(s * 0.07F, 0.12F, 0.08F, -0.85F, 0, (i == 1 ? -1 : 1) * 0.45F + sin(a.t * 5 + i) * 0.08F);
            d.plaque(0, 0.27F, 0, 0.16F, 0.55F, ANTENNE, false);
            d.pop();
        }
        d.pop();
        // quatre ailes poudrées avec un œil dessiné, qui battent (vite quand elle chasse)
        float v = a.chasse ? 24 : 12;
        for (int s = -1; s <= 1; s += 2) {
            for (int petite = 0; petite < 2; petite++) {
                float retard = petite * 0.5F;
                float rz = s * (a.ouvert > 0 ? 0.05F + sin(a.t * 40) * 0.08F : sin(a.t * v - retard) * 0.65F + 0.1F);
                if (petite == 0) {
                    d.push(s * 0.15F, 0.08F, 0.04F, 0, 0, rz);
                    d.boite(s * 0.52F, 0, -0.1F, 1.05F, 0.02F, 0.64F, f(AILE));
                } else {
                    d.push(s * 0.13F, 0.04F, -0.14F, 0, 0, rz);
                    d.boite(s * 0.34F, 0, -0.24F, 0.68F, 0.02F, 0.5F, f(AILE));
                }
                d.pop();
            }
        }
        float[][] pattes = {{-0.1F, 0.1F}, {0.1F, 0.1F}, {-0.12F, -0.08F}, {0.12F, -0.08F}};
        for (float[] p : pattes) {
            d.boite(p[0], -0.28F, p[1], 0.3F, 0, 0, 0.03F, 0.32F, 0.03F, f(CHAUSSURE));
        }
        d.pop();
    }

    private ModeleMonstres() {
    }
}
