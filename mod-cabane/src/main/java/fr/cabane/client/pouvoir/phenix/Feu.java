package fr.cabane.client.pouvoir.phenix;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import fr.cabane.Cabane;
import java.io.IOException;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterShadersEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;
import org.joml.Matrix4fStack;
import org.joml.Vector3f;

/**
 * Le moteur des flammes du phénix, comme dans l'aperçu docs/phenix/apercu : une flamme est un ruban (une bande de quads le
 * long d'un chemin) avec du bruit qui défile, une lueur est un carré face à la caméra ; tout est calculé chaque image,
 * rangé ici, puis dessiné d'un coup après les particules avec le shader cabane:phenix_feu (mélange prémultiplié).
 * <p>
 * On dessine dans un {@link #repere(Matrix4f) repère} : une matrice qui va des coordonnées locales (en général des pixels
 * de modèle, 16 par bloc) vers le monde autour de la caméra (la caméra en 0, en blocs). Les largeurs sont en unités
 * locales.
 */
public final class Feu {
    public static final ResourceLocation BRUIT = Cabane.id("textures/effet/phenix_bruit.png");
    private static ShaderInstance shader;

    public static final int SEG = 10;
    /** x, y, z, r, g, b, a, u, v, uv2.x, uv2.y */
    private static final int F = 11;
    private static float[] v = new float[F * 4 * 4096];
    private static int n;
    /** Entre le ciel et les particules : on range ce qui est dessiné dans le monde (pas l'inventaire, pas les menus). */
    static boolean enMonde;
    /** L'heure des flammes, en secondes (elles ondulent avec elle). */
    public static float T;
    private static final long ZERO = System.nanoTime();

    private static final Matrix4f REPERE = new Matrix4f();
    private static float echelle = 1;
    /** La droite et le haut de l'écran, dans le monde. */
    private static final Vector3f CAM_D = new Vector3f(1, 0, 0), CAM_H = new Vector3f(0, 1, 0);

    public static final float[] CYAN = {0.2F, 0.75F, 1F}, OR = {1F, 0.72F, 0.22F}, BLANC = {0.8F, 0.95F, 1F}, BLEU = {0.08F, 0.3F, 1F},
            ORANGE = {1F, 0.5F, 0.15F};

    public static boolean pret() {
        return enMonde && shader != null;
    }

    /** Le repère courant (local → monde autour de la caméra). Rend l'ancien, pour le remettre. */
    public static Matrix4f repere(Matrix4f m) {
        Matrix4f avant = new Matrix4f(REPERE);
        REPERE.set(m);
        Vector3f s = m.getScale(new Vector3f());
        echelle = (s.x + s.y + s.z) / 3;
        return avant;
    }

    public static Matrix4f repere() {
        return new Matrix4f(REPERE);
    }

    /** Le repère « monde en pixels » centré sur p (position absolue), tourné de lacet (radians, 0 = +z). */
    public static Matrix4f monde(double x, double y, double z, double camX, double camY, double camZ, float lacet) {
        return new Matrix4f().translate((float) (x - camX), (float) (y - camY), (float) (z - camZ)).rotateY(lacet).scale(1 / 16F);
    }

    private static void sommet(float x, float y, float z, float r, float g, float b, float a, float u, float w, int u2, int v2) {
        if (n + F > v.length) {
            if (v.length >= F * 4 * 400_000) {
                return;
            }
            float[] plus = new float[v.length * 2];
            System.arraycopy(v, 0, plus, 0, n);
            v = plus;
        }
        v[n++] = x; v[n++] = y; v[n++] = z;
        v[n++] = Math.max(0, Math.min(1, r)); v[n++] = Math.max(0, Math.min(1, g)); v[n++] = Math.max(0, Math.min(1, b)); v[n++] = Math.max(0, Math.min(1, a));
        v[n++] = u; v[n++] = w;
        v[n++] = u2; v[n++] = v2;
    }

    private static float fract(float x) {
        return x - (float) Math.floor(x);
    }

    // ================================================================ les rubans

    public interface Chemin {
        void at(float s, Vector3f out);
    }

    public interface Largeur {
        float at(float s);
    }

    public static float formeLangue(float s) {
        return (float) (Math.pow(1 - s, 0.7) * (0.55 + 0.45 * Math.min(1, s / 0.2)));
    }

    public static float formePlume(float s) {
        return (float) (Math.pow(Math.sin(Math.PI * (0.12 + 0.88 * s)), 0.6) * (1 - 0.35 * s * s));
    }

    private static final Vector3f[] P = new Vector3f[SEG + 1];
    private static final Vector3f TMP = new Vector3f(), TG = new Vector3f(), CO = new Vector3f();

    static {
        for (int i = 0; i <= SEG; i++) {
            P[i] = new Vector3f();
        }
    }

    /**
     * Un ruban de flamme : f donne le point local à s ∈ [0, 1] (0 = la base, 1 = la pointe), w la largeur locale ; cote :
     * un vecteur local pour la largeur (null : le ruban se tourne vers la caméra). or : 0 bleu, 1 pointe dorée, 2 orange.
     */
    public static void ruban(Chemin f, Largeur w, float graine, float alpha, float or, float vit, float rep, float fondu, Vector3f cote) {
        if (!pret() || alpha <= 0.004F) {
            return;
        }
        for (int i = 0; i <= SEG; i++) {
            f.at(i / (float) SEG, TMP);
            REPERE.transformPosition(TMP, P[i]);
        }
        if (cote != null) {
            REPERE.transformDirection(cote, CO).normalize();
        }
        float g = fract(graine), al = Math.min(1, alpha), o = Math.min(2, or) / 2, fo = Math.min(1, fondu);
        int u2 = Math.round(Math.min(500, rep) * 64), v2 = Math.round(Math.min(60, vit) * 64);
        float[] ax = new float[SEG + 1], ay = new float[SEG + 1], az = new float[SEG + 1], bx = new float[SEG + 1], by = new float[SEG + 1], bz = new float[SEG + 1];
        Vector3f side = new Vector3f();
        for (int i = 0; i <= SEG; i++) {
            float s = i / (float) SEG;
            Vector3f p = P[i];
            P[Math.min(SEG, i + 1)].sub(P[Math.max(0, i - 1)], TG);
            if (cote != null) {
                side.set(CO);
            } else {
                TG.cross(-p.x, -p.y, -p.z, side);
            }
            if (side.lengthSquared() < 1e-12F) {
                side.set(1, 0, 0);
            }
            side.normalize();
            float hw = w.at(s) * echelle / 2;
            ax[i] = p.x - side.x * hw; ay[i] = p.y - side.y * hw; az[i] = p.z - side.z * hw;
            bx[i] = p.x + side.x * hw; by[i] = p.y + side.y * hw; bz[i] = p.z + side.z * hw;
        }
        for (int i = 0; i < SEG; i++) {
            float s0 = i / (float) SEG, s1 = (i + 1) / (float) SEG;
            sommet(ax[i], ay[i], az[i], g, al, o, fo, 0, s0, u2, v2);
            sommet(bx[i], by[i], bz[i], g, al, o, fo, 1, s0, u2, v2);
            sommet(bx[i + 1], by[i + 1], bz[i + 1], g, al, o, fo, 1, s1, u2, v2);
            sommet(ax[i + 1], ay[i + 1], az[i + 1], g, al, o, fo, 0, s1, u2, v2);
        }
    }

    public static void ruban(Chemin f, Largeur w, float graine, float alpha, float or, float vit, float rep, float fondu) {
        ruban(f, w, graine, alpha, or, vit, rep, fondu, null);
    }

    /** Une langue de feu qui ondule : base locale, direction locale (normée), longueur, largeur. */
    public static void langue(float bx, float by, float bz, Vector3f dir, float len, float largeur, float graine, float alpha, float or, float sway) {
        langue(bx, by, bz, dir, len, largeur, graine, alpha, or, sway, 1.5F, 1);
    }

    public static void langue(float bx, float by, float bz, Vector3f dir, float len, float largeur, float graine, float alpha, float or, float sway,
            float vit, float fondu) {
        if (len < 0.3F || largeur <= 0.01F) {
            return;
        }
        float ph = graine * 37.1F, dx = dir.x, dy = dir.y, dz = dir.z;
        ruban((s, o) -> {
            float a = (float) Math.sin(T * 8.5 + ph + s * 5) * s * len * sway, b = (float) Math.cos(T * 7.1 + ph * 1.7 + s * 4.3) * s * len * sway;
            o.set(bx + dx * len * s + a, by + dy * len * s + Math.abs(b) * 0.2F, bz + dz * len * s + b);
        }, s -> largeur * formeLangue(s), graine, alpha, or, vit, Math.max(0.6F, len / 14), fondu);
    }

    // ================================================================ les lueurs et les pièces pleines

    /** Une lueur face caméra : forme 0 ronde, 1 étoile, 2 anneau. */
    public static void lueur(float x, float y, float z, float taille, float[] c, float alpha, int forme) {
        if (!pret() || alpha <= 0.004F || taille <= 0) {
            return;
        }
        REPERE.transformPosition(x, y, z, TMP);
        float r = taille * echelle / 2;
        int v2 = (2 + forme) * 4096;
        float[][] co = {{-1, -1}, {1, -1}, {1, 1}, {-1, 1}};
        for (float[] k : co) {
            sommet(TMP.x + (CAM_D.x * k[0] + CAM_H.x * k[1]) * r, TMP.y + (CAM_D.y * k[0] + CAM_H.y * k[1]) * r, TMP.z + (CAM_D.z * k[0] + CAM_H.z * k[1]) * r,
                    c[0], c[1], c[2], Math.min(1, alpha), k[0], k[1], 0, v2);
        }
    }

    public static void lueur(float x, float y, float z, float taille, float[] c, float alpha) {
        lueur(x, y, z, taille, c, alpha, 0);
    }

    /** Une griffe (ou un bec) dorée pleine, de a vers b, épaisse de ep à la base (unités locales). */
    public static void griffe(float ax, float ay, float az, float bx, float by, float bz, float ep) {
        if (!pret() || ep <= 0.01F) {
            return;
        }
        Vector3f a = REPERE.transformPosition(ax, ay, az, new Vector3f()), b = REPERE.transformPosition(bx, by, bz, new Vector3f());
        Vector3f d = new Vector3f(b).sub(a);
        if (d.lengthSquared() < 1e-10F) {
            return;
        }
        d.normalize();
        Vector3f u = Math.abs(d.y) < 0.9F ? new Vector3f(0, 1, 0).cross(d) : new Vector3f(1, 0, 0).cross(d);
        u.normalize();
        Vector3f w = new Vector3f(d).cross(u).normalize();
        float r = ep * echelle / 2;
        Vector3f[] base = new Vector3f[4];
        for (int k = 0; k < 4; k++) {
            double ang = k * Math.PI / 2 + Math.PI / 4;
            base[k] = new Vector3f(a).add(new Vector3f(u).mul((float) Math.cos(ang) * r)).add(new Vector3f(w).mul((float) Math.sin(ang) * r));
        }
        for (int k = 0; k < 4; k++) {
            Vector3f p = base[k], q = base[(k + 1) % 4];
            float l = 0.75F + 0.25F * (k % 2) + (k == 0 ? 0.1F : 0);
            int v2 = 5 * 4096;
            sommet(p.x, p.y, p.z, OR[0] * l * 1.1F, OR[1] * l * 1.05F, OR[2] * l, 1, 0, 0, 0, v2);
            sommet(q.x, q.y, q.z, OR[0] * l * 1.1F, OR[1] * l * 1.05F, OR[2] * l, 1, 0, 0, 0, v2);
            sommet(b.x, b.y, b.z, 1, 0.95F, 0.7F, 1, 0, 0, 0, v2);
            sommet(b.x, b.y, b.z, 1, 0.95F, 0.7F, 1, 0, 0, 0, v2);
        }
    }

    /** Un petit cube plein (la cendre), de côté c, gris. */
    public static void cube(float x, float y, float z, float c, float gris) {
        if (!pret() || c <= 0) {
            return;
        }
        float h = c / 2;
        float[][] f = {{0, 0, 1}, {0, 0, -1}, {1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}};
        for (float[] nrm : f) {
            Vector3f nn = new Vector3f(nrm[0], nrm[1], nrm[2]);
            Vector3f t1 = Math.abs(nn.y) > 0.5F ? new Vector3f(1, 0, 0) : new Vector3f(0, 1, 0);
            Vector3f t2 = new Vector3f(nn).cross(t1);
            float l = gris * (0.7F + 0.3F * Math.max(0, nn.y) + 0.1F * nn.x);
            float[][] co = {{-1, -1}, {1, -1}, {1, 1}, {-1, 1}};
            for (float[] k : co) {
                Vector3f p = new Vector3f(x + nn.x * h + (t1.x * k[0] + t2.x * k[1]) * h, y + nn.y * h + (t1.y * k[0] + t2.y * k[1]) * h, z + nn.z * h + (t1.z * k[0] + t2.z * k[1]) * h);
                REPERE.transformPosition(p);
                sommet(p.x, p.y, p.z, l, l, l * 1.05F, 1, 0, 0, 0, 5 * 4096);
            }
        }
    }

    /** Un quad d'aura (déjà dans le monde) : 4 coins, l'alpha de chaque coin, le bruit (u, v) de chaque coin. */
    static void aura(Vector3f[] c, float[] alpha, float[][] uv) {
        if (!pret()) {
            return;
        }
        for (int k = 0; k < 4; k++) {
            sommet(c[k].x, c[k].y, c[k].z, 0, Math.min(1, alpha[k]), 0, 0, uv[k][0], uv[k][1], 0, 4096);
        }
    }

    // ================================================================ dessiner

    @EventBusSubscriber(modid = Cabane.MODID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
    public static final class Shaders {
        @SubscribeEvent
        public static void enregistrer(RegisterShadersEvent event) throws IOException {
            event.registerShader(new ShaderInstance(event.getResourceProvider(), Cabane.id("phenix_feu"), DefaultVertexFormat.POSITION_COLOR_TEX_LIGHTMAP),
                    s -> shader = s);
        }

        private Shaders() {
        }
    }

    /** Au début de l'image (après le ciel) : on vide, on note la caméra. */
    public static void debut(Camera camera) {
        n = 0;
        enMonde = true;
        T = (System.nanoTime() - ZERO) / 1e9F % 3600;
        camera.getLeftVector().negate(CAM_D);
        CAM_H.set(camera.getUpVector());
    }

    /** Après les particules : on dessine tout. */
    public static void dessiner(RenderLevelStageEvent event) {
        enMonde = false;
        if (n == 0 || shader == null) {
            n = 0;
            return;
        }
        Matrix4fStack pile = RenderSystem.getModelViewStack();
        pile.pushMatrix();
        pile.set(event.getModelViewMatrix());
        RenderSystem.applyModelViewMatrix();
        RenderSystem.setShader(() -> shader);
        RenderSystem.setShaderTexture(0, BRUIT);
        shader.safeGetUniform("Temps").set(T);
        RenderSystem.enableBlend();
        RenderSystem.blendFunc(GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA);
        RenderSystem.depthMask(false);
        RenderSystem.disableCull();
        RenderSystem.enableDepthTest();
        BufferBuilder bb = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR_TEX_LIGHTMAP);
        for (int i = 0; i < n; i += F) {
            bb.addVertex(v[i], v[i + 1], v[i + 2]).setColor(v[i + 3], v[i + 4], v[i + 5], v[i + 6]).setUv(v[i + 7], v[i + 8]).setUv2((int) v[i + 9], (int) v[i + 10]);
        }
        BufferUploader.drawWithShader(bb.buildOrThrow());
        n = 0;
        RenderSystem.depthMask(true);
        RenderSystem.enableCull();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableBlend();
        pile.popMatrix();
        RenderSystem.applyModelViewMatrix();
    }

    private Feu() {
    }
}
