package fr.cabane.client.parc;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import fr.cabane.parc.Maneges;
import fr.cabane.parc.Trajet;
import fr.cabane.parc.WagonParc;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

/**
 * Les décors animés des manèges de la deuxième partie, dessinés par le client (comme les rails) : dans la Mine les
 * spots de la salle qui tourne, les yeux rouges, les fantômes qui foncent sur les wagons et le squelette qui tombe du
 * plafond ; sous le dôme du Cosmos les étoiles et les planètes ; à la Rivière Splash le flash de l'appareil photo, les
 * gerbes du splash et l'écran des photos ; autour de la Maison Hantée les chauves-souris, et dans le tunnel les
 * silhouettes des screamers (seulement chez celui qui passe).
 */
final class DecorParc {
    private static final int SOL = Maneges.SOL;

    private static boolean pret;
    private static double sSplash, sPhoto, sSquelette;
    private static final List<float[]> SPOTS = new ArrayList<>(), YEUX = new ArrayList<>(), ETOILES = new ArrayList<>();
    private static final List<Fantome> FANTOMES = new ArrayList<>();
    private static final List<Cri> CRIS = new ArrayList<>();
    private static Trajet.Point squelettePoint;
    private static double[] photo;
    private static long squeletteDepuis = -1, flashDepuis = -1;
    private static double tempsAvant;

    private static final class Fantome {
        Vec3 base, vers;
        double saut, phase;
    }

    private static final class Cri {
        Vec3 cache, sorti, regard;
        long depuis = -1;
    }

    private static void preparer() {
        if (pret) {
            return;
        }
        pret = true;
        Random r = new Random(4242);
        Trajet.Point p = new Trajet.Point();
        // la Mine
        Trajet m = Maneges.trajet(Maneges.MINE);
        m.a(m.ou(1.3), p);
        float[] g = gauche(p);
        for (int k = 0; k < 8; k++) {
            double a = k / 8.0 * Math.PI * 2;
            SPOTS.add(new float[]{(float) (p.x + g[0] * Math.cos(a) * 4.2 + p.fx * Math.sin(a) * 4.2 + p.ux * 6.5),
                    (float) (p.y + g[1] * Math.cos(a) * 4.2 + p.fy * Math.sin(a) * 4.2 + p.uy * 6.5),
                    (float) (p.z + g[2] * Math.cos(a) * 4.2 + p.fz * Math.sin(a) * 4.2 + p.uz * 6.5), k});
        }
        int n = 0;
        for (double s = 0; s < m.total; s += 2.5) {
            m.a(s, p);
            if (p.mode != Trajet.LENT) {
                continue;
            }
            g = gauche(p);
            int sd = n++ % 2 == 0 ? 1 : -1;
            double w = sd * (Maneges.mineLargeur(p.u) / 2 - 0.15), h = 1.6 + r.nextDouble() * 2;
            YEUX.add(new float[]{(float) (p.x + g[0] * w + p.ux * h), (float) (p.y + g[1] * w + p.uy * h), (float) (p.z + g[2] * w + p.uz * h),
                    (float) p.fx, (float) p.fy, (float) p.fz, n});
        }
        double sA = m.ou(2.85), sB = m.ou(3.4);
        for (int k = 0; k < 6; k++) {
            m.a(sA + (sB - sA) * (k + 0.5) / 6, p);
            g = gauche(p);
            int sd = k % 2 == 1 ? 1 : -1;
            Fantome f = new Fantome();
            f.base = new Vec3(p.x + g[0] * sd * 3.6 + p.ux * 2.5, p.y + g[1] * sd * 3.6 + p.uy * 2.5, p.z + g[2] * sd * 3.6 + p.uz * 2.5);
            f.vers = new Vec3(p.x + p.ux * 2.2, p.y + p.uy * 2.2, p.z + p.uz * 2.2);
            f.phase = k * 1.3;
            FANTOMES.add(f);
        }
        sSquelette = m.ou(5.55);
        squelettePoint = m.a(sSquelette, new Trajet.Point());
        // le Cosmos : des étoiles sur la voûte, d'autres qui flottent
        double cx = Maneges.COSMOS_X, cz = Maneges.COSMOS_Z, R = Maneges.COSMOS_R;
        for (int i = 0; i < 1500; i++) {
            double a = r.nextDouble() * Math.PI * 2, h = Math.acos(r.nextDouble()), rr = i < 1150 ? R - 1.75 : 6 + r.nextDouble() * 15;
            double y = Math.max(0.6, rr * Math.cos(h));
            int c = r.nextDouble() < 0.8 ? 0xFFFFFF : r.nextBoolean() ? 0xBFE6FF : 0xFFF1B8;
            ETOILES.add(new float[]{(float) (cx + rr * Math.sin(h) * Math.cos(a)), (float) (SOL + y), (float) (cz + rr * Math.sin(h) * Math.sin(a)),
                    c, 0.05F + r.nextFloat() * 0.06F});
        }
        // la Rivière Splash
        Trajet sp = Maneges.trajet(Maneges.SPLASH);
        sSplash = sp.ou(Maneges.U_SPLASH);
        sPhoto = sp.ou(Maneges.U_PHOTO);
        photo = Maneges.appareilPhoto();
        // la Maison Hantée : les silhouettes cachées dans les niches du tunnel
        Trajet t = Maneges.trajet(Maneges.MAISON);
        double[] cris = Maneges.maisonCris();
        for (int k = 0; k < cris.length; k++) {
            t.a(cris[k] + 1.5, p);
            g = gauche(p);
            int sd = k % 2 == 1 ? 1 : -1;
            Cri c = new Cri();
            c.cache = new Vec3(p.x + g[0] * sd * 2.4, SOL, p.z + g[2] * sd * 2.4);
            c.sorti = new Vec3(p.x + g[0] * sd * 0.4 - p.fx * 0.6, SOL, p.z + g[2] * sd * 0.4 - p.fz * 0.6);
            c.regard = new Vec3(p.x - p.fx * 1.5, SOL, p.z - p.fz * 1.5);
            CRIS.add(c);
        }
    }

    private static float[] gauche(Trajet.Point p) {
        return new float[]{(float) (p.uy * p.fz - p.uz * p.fy), (float) (p.uz * p.fx - p.ux * p.fz), (float) (p.ux * p.fy - p.uy * p.fx)};
    }

    static double sSplash() {
        preparer();
        return sSplash;
    }

    static double sPhoto() {
        preparer();
        return sPhoto;
    }

    static double sSquelette() {
        preparer();
        return sSquelette;
    }

    /** Où regarde l'appareil photo : (x, y, z) de l'objectif. */
    static Vec3 objectif() {
        preparer();
        return new Vec3(photo[2], photo[3], photo[4]);
    }

    /** D'où l'appareil photo regarde : un bloc devant l'objectif, côté rivière. */
    static Vec3 oeil() {
        preparer();
        double dx = photo[2] - photo[0], dz = photo[4] - photo[1], l = Math.max(1e-6, Math.hypot(dx, dz));
        return new Vec3(photo[2] + dx / l * 1.1, SOL + 4.3, photo[4] + dz / l * 1.1);
    }

    private static long maintenant() {
        return System.currentTimeMillis();
    }

    static void squelette() {
        squeletteDepuis = maintenant();
    }

    static void flash() {
        flashDepuis = maintenant();
    }

    /** Le screamer numéro k sort de sa niche (chez ce joueur seulement). */
    static void cri(int k) {
        preparer();
        if (k >= 0 && k < CRIS.size()) {
            CRIS.get(k).depuis = maintenant();
        }
    }

    /** Le grand splash : une gerbe qui monte très haut devant le tronc et deux grandes ailes d'eau sur les côtés. */
    static void splash(Minecraft mc) {
        preparer();
        Trajet.Point p = Maneges.trajet(Maneges.SPLASH).a(sSplash, new Trajet.Point());
        Vec3 cam = mc.gameRenderer.getMainCamera().getPosition();
        if (cam.distanceToSqr(p.x, p.y, p.z) > 120 * 120) {
            return;
        }
        double fx = p.fx, fz = p.fz, l = Math.max(1e-3, Math.hypot(fx, fz));
        fx /= l;
        fz /= l;
        double gx = fz, gz = -fx;
        var r = mc.level.random;
        // la gerbe part devant le tronc (sinon ceux qui sont dedans ne voient plus que du blanc)
        double y = SOL + 0.2, ox = p.x + fx * 3.5, oz = p.z + fz * 3.5;
        for (int i = 0; i < 220; i++) {
            int type = i % 3;
            double x = ox + (r.nextDouble() - 0.5) * 3, z = oz + (r.nextDouble() - 0.5) * 3;
            double vx, vy, vz;
            if (type == 0) {
                double a = r.nextDouble() * Math.PI * 2, f = r.nextDouble();
                vx = Math.cos(a) * 0.2 * f + fx * 0.45;
                vy = 0.7 + r.nextDouble() * 0.6;
                vz = Math.sin(a) * 0.2 * f + fz * 0.45;
            } else {
                int sd = type == 1 ? 1 : -1;
                double w = 0.35 + r.nextDouble() * 0.45, av = 0.15 + r.nextDouble() * 0.3;
                vx = gx * sd * w + fx * av;
                vy = 0.35 + r.nextDouble() * 0.5;
                vz = gz * sd * w + fz * av;
            }
            mc.level.addParticle(i % 5 == 0 ? ParticleTypes.CLOUD : ParticleTypes.SNOWFLAKE, x, y, z, vx, vy, vz);
        }
        for (int i = 0; i < 160; i++) {
            mc.level.addParticle(ParticleTypes.SPLASH, ox + (r.nextDouble() - 0.5) * 9, y + 0.3, oz + (r.nextDouble() - 0.5) * 9, 0, 0, 0);
        }
    }

    // ------------------------------------------------------------------ le dessin

    /** Un repère pour dessiner une figure : une origine (par rapport à la caméra) et trois axes, gauche, haut, avant. */
    private record Repere(float ox, float oy, float oz, float[] x, float[] y, float[] z, float echelle) {
        void pave(VertexConsumer c, PoseStack.Pose pose, float x0, float y0, float z0, float x1, float y1, float z1, int rgb, int lum) {
            float cx = (x0 + x1) / 2 * echelle, cy = (y0 + y1) / 2 * echelle, cz = (z0 + z1) / 2 * echelle;
            Boites.boite(c, pose, ox + x[0] * cx + y[0] * cy + z[0] * cz, oy + x[1] * cx + y[1] * cy + z[1] * cz, oz + x[2] * cx + y[2] * cy + z[2] * cz,
                    x[0], x[1], x[2], y[0], y[1], y[2], z[0], z[1], z[2], (x1 - x0) / 2 * echelle, (y1 - y0) / 2 * echelle, (z1 - z0) / 2 * echelle, rgb, lum);
        }
    }

    /** Un repère debout (le haut vers le ciel) en (x, y, z), qui regarde vers (dx, dz). */
    private static Repere debout(Vec3 cam, double x, double y, double z, double dx, double dz, float echelle) {
        double l = Math.max(1e-4, Math.hypot(dx, dz));
        float fx = (float) (dx / l), fz = (float) (dz / l);
        return new Repere((float) (x - cam.x), (float) (y - cam.y), (float) (z - cam.z), new float[]{fz, 0, -fx}, new float[]{0, 1, 0},
                new float[]{fx, 0, fz}, echelle);
    }

    private static boolean pres(Vec3 cam, double x, double z, double r) {
        return (cam.x - x) * (cam.x - x) + (cam.z - z) * (cam.z - z) < r * r;
    }

    private static int lumiere(Minecraft mc, double x, double y, double z) {
        return LevelRenderer.getLightColor(mc.level, BlockPos.containing(x, y, z));
    }

    static void dessiner(VertexConsumer c, PoseStack.Pose pose, Vec3 cam, double temps) {
        preparer();
        Minecraft mc = Minecraft.getInstance();
        double dt = Mth.clamp(temps - tempsAvant, 0, 0.1);
        tempsAvant = temps;
        long now = maintenant();
        if (pres(cam, Maneges.MINE_X, Maneges.MINE_Z, 90)) {
            mine(mc, c, pose, cam, temps, dt, now);
        }
        if (pres(cam, Maneges.COSMOS_X, Maneges.COSMOS_Z, 60)) {
            cosmos(c, pose, cam, temps);
        }
        if (pres(cam, Maneges.SPLASH_X, Maneges.SPLASH_Z, 120)) {
            float k = flashDepuis < 0 ? 0 : Math.max(0, 1 - (now - flashDepuis) / 300F);
            Repere r = debout(cam, Math.floor(photo[0]) + 0.5, SOL + 5.15, Math.floor(photo[1]) + 0.5, 1, 0, 1);
            r.pave(c, pose, -0.35F, 0, -0.25F, 0.35F, 0.25F, 0.25F, k > 0 ? 0xFFFFFF : 0x555555, k > 0 ? Boites.PLEIN : lumiere(mc, photo[0], SOL + 5, photo[1]));
        }
        if (pres(cam, Maneges.MAISON_X, Maneges.MAISON_Z, 90)) {
            maison(mc, c, pose, cam, temps, now);
        }
    }

    private static void mine(Minecraft mc, VertexConsumer c, PoseStack.Pose pose, Vec3 cam, double temps, double dt, long now) {
        // les spots de couleur de la salle qui tourne
        for (float[] s : SPOTS) {
            int rgb = Mth.hsvToRgb((float) ((temps * 0.25 + s[3] / 8) % 1.0), 1F, 1F);
            Boites.boite(c, pose, (float) (s[0] - cam.x), (float) (s[1] - cam.y), (float) (s[2] - cam.z), 1, 0, 0, 0, 1, 0, 0, 0, 1, 0.3F, 0.15F, 0.3F, rgb, Boites.PLEIN);
        }
        // les yeux rouges qui clignent
        for (float[] y : YEUX) {
            if ((temps * 0.7 + y[6] * 0.37) % 4 > 3.75) {
                continue;
            }
            for (int e = -1; e <= 1; e += 2) {
                float ox = y[3] * 0.22F * e, oy = y[4] * 0.22F * e, oz = y[5] * 0.22F * e;
                Boites.boite(c, pose, (float) (y[0] + ox - cam.x), (float) (y[1] + oy - cam.y), (float) (y[2] + oz - cam.z), 1, 0, 0, 0, 1, 0, 0, 0, 1,
                        0.1F, 0.07F, 0.1F, 0xFF2A2A, Boites.PLEIN);
            }
        }
        // les fantômes : ils foncent sur les wagons qui passent
        List<Vec3> wagons = new ArrayList<>();
        for (Entity e : mc.level.entitiesForRendering()) {
            if (e instanceof WagonParc w && w.manege() == Maneges.MINE) {
                wagons.add(e.position());
            }
        }
        for (Fantome f : FANTOMES) {
            double pres = 99;
            for (Vec3 w : wagons) {
                pres = Math.min(pres, w.distanceTo(f.base));
            }
            boolean proche = pres < 7;
            f.saut += ((proche ? 1 : 0) - f.saut) * Math.min(1, dt * (proche ? 5 : 1.2));
            Vec3 p = f.base.lerp(f.vers, f.saut * 0.75).add(0, Math.sin(temps * 2 + f.phase) * 0.3, 0);
            Repere r = debout(cam, p.x, p.y - 0.85, p.z, f.vers.x - p.x, f.vers.z - p.z, (float) (1 + f.saut * 0.4));
            int blanc = 0xEEF8FF, noir = 0x111111;
            r.pave(c, pose, -0.65F, 0, -0.45F, 0.65F, 1.7F, 0.45F, blanc, Boites.PLEIN);
            for (int j = 0; j < 3; j++) {
                r.pave(c, pose, -0.62F + j * 0.45F, -0.4F, -0.4F, -0.28F + j * 0.45F, 0.05F, 0.4F, blanc, Boites.PLEIN);
            }
            for (int e = -1; e <= 1; e += 2) {
                r.pave(c, pose, e * 0.28F - 0.12F, 0.95F, 0.44F, e * 0.28F + 0.12F, 1.3F, 0.5F, noir, Boites.PLEIN);
            }
            r.pave(c, pose, -0.18F, 0.4F, 0.44F, 0.18F, 0.8F, 0.5F, noir, Boites.PLEIN);
        }
        // le squelette qui tombe du plafond juste devant le wagon
        double k = squeletteDepuis < 0 ? 0 : (now - squeletteDepuis) / 1000.0 + 1;
        if (k > 5.5) {
            squeletteDepuis = -1;
            k = 0;
        }
        double kk = k > 0 ? Math.min(1, (k - 1) * 6) * (1 - Maneges.entre(2.5, 4.4, k)) : 0;
        Trajet.Point q = squelettePoint;
        float[] g = gauche(q);
        Vec3 haut = new Vec3(q.x + q.ux * 3.6, q.y + q.uy * 3.6, q.z + q.uz * 3.6);
        Vec3 bas = new Vec3(q.x + q.ux * 0.6 + g[0] * 1.4, q.y + q.uy * 0.6 + g[1] * 1.4, q.z + q.uz * 0.6 + g[2] * 1.4);
        Vec3 p = haut.lerp(bas, kk);
        Repere r = debout(cam, p.x, p.y, p.z, -q.fx, -q.fz, 1);
        int os = 0xEFEADC, lum = kk > 0.2 ? Boites.PLEIN : lumiere(mc, p.x, p.y + 1, p.z);
        r.pave(c, pose, -0.27F, 1.48F, -0.27F, 0.27F, 2.03F, 0.27F, os, lum);
        for (int e = -1; e <= 1; e += 2) {
            r.pave(c, pose, e * 0.12F - 0.06F, 1.72F, 0.26F, e * 0.12F + 0.06F, 1.84F, 0.29F, 0x000000, lum);
            r.pave(c, pose, e * 0.38F - 0.05F, 0.7F, -0.05F, e * 0.38F + 0.05F, 1.5F, 0.05F, os, lum);
            r.pave(c, pose, e * 0.14F - 0.05F, -0.05F, -0.05F, e * 0.14F + 0.05F, 0.75F, 0.05F, os, lum);
        }
        r.pave(c, pose, -0.06F, 0.7F, -0.06F, 0.06F, 1.5F, 0.06F, os, lum);
        for (int j = 0; j < 4; j++) {
            r.pave(c, pose, -0.3F, 1.32F - j * 0.17F, -0.15F, 0.3F, 1.38F - j * 0.17F, 0.15F, os, lum);
        }
        if (kk > 0.2) {
            // une lueur rouge sur lui quand il tombe
            Boites.boite(c, pose, (float) (bas.x - cam.x), (float) (bas.y + 2.6 - cam.y), (float) (bas.z - cam.z), 1, 0, 0, 0, 1, 0, 0, 0, 1,
                    0.15F, 0.15F, 0.15F, 0xFF3030, Boites.PLEIN);
        }
    }

    private static void cosmos(VertexConsumer c, PoseStack.Pose pose, Vec3 cam, double temps) {
        for (float[] e : ETOILES) {
            float t = e[4] * (0.75F + 0.25F * (float) Math.sin(temps * 3 + e[0] * 7));
            Boites.boite(c, pose, (float) (e[0] - cam.x), (float) (e[1] - cam.y), (float) (e[2] - cam.z), 1, 0, 0, 0, 1, 0, 0, 0, 1, t, t, t, (int) e[3], Boites.PLEIN);
        }
        double cx = Maneges.COSMOS_X, cz = Maneges.COSMOS_Z;
        double[][] planetes = {{8, 17, 4, 2.6, 0xFF9A3C, 0xFFE2A8}, {-10, 14, -6, 1.4, 0x4DC9FF, 0}, {2, 20, -10, 0.9, 0xC77DFF, 0}, {-6, 18, 9, 1.8, 0x7BFF9B, 0x2A8A5A}};
        for (double[] pl : planetes) {
            Repere r = debout(cam, cx + pl[0], SOL + pl[1], cz + pl[2], Math.cos(temps * 0.1 + pl[0]), Math.sin(temps * 0.1 + pl[0]), 1);
            float h = (float) pl[3];
            r.pave(c, pose, -h, -h * 0.8F, -h * 0.8F, h, h * 0.8F, h * 0.8F, (int) pl[4], Boites.PLEIN);
            r.pave(c, pose, -h * 0.8F, -h, -h * 0.8F, h * 0.8F, h, h * 0.8F, (int) pl[4], Boites.PLEIN);
            r.pave(c, pose, -h * 0.8F, -h * 0.8F, -h, h * 0.8F, h * 0.8F, h, (int) pl[4], Boites.PLEIN);
            if (pl[5] != 0) {
                r.pave(c, pose, -h * 1.8F, -0.06F, -h * 1.8F, h * 1.8F, 0.06F, h * 1.8F, (int) pl[5], Boites.PLEIN);
            }
        }
    }

    private static void maison(Minecraft mc, VertexConsumer c, PoseStack.Pose pose, Vec3 cam, double temps, long now) {
        double mx = Maneges.MAISON_X + 0.5, mz = Maneges.MAISON_Z + 0.5;
        // les chauves-souris autour des tours
        for (int k = 0; k < 7; k++) {
            double rr = 9 + (k * 37 % 9), h = 18 + (k * 53 % 9), v = 0.5 + (k * 29 % 5) / 10.0, a = temps * v + k * 0.9;
            double x = mx + Math.cos(a) * rr, z = mz + Math.sin(a) * rr, y = SOL + h + Math.sin(temps * 3 + k) * 1.2;
            Repere r = debout(cam, x, y, z, -Math.sin(a), Math.cos(a), 1);
            int lum = lumiere(mc, x, y, z);
            r.pave(c, pose, -0.17F, -0.15F, -0.25F, 0.17F, 0.15F, 0.25F, 0x111111, lum);
            float b = (float) Math.sin(temps * 14 + k) * 0.35F;
            for (int s = -1; s <= 1; s += 2) {
                r.pave(c, pose, s > 0 ? 0.15F : -0.95F, b, -0.22F, s > 0 ? 0.95F : -0.15F, b + 0.06F, 0.22F, 0x1A1A1A, lum);
            }
        }
        // les screamers : une silhouette noire au visage blafard qui saute hors de sa niche
        for (Cri cr : CRIS) {
            double k = cr.depuis < 0 ? 0 : (now - cr.depuis) / 1000.0;
            if (k > 2.2) {
                cr.depuis = -1;
                k = 0;
            }
            double a = k > 0 ? Math.min(1, k * 9) * (1 - Maneges.entre(1.2, 2.1, k)) : 0;
            Vec3 p = cr.cache.lerp(cr.sorti, a);
            Repere r = debout(cam, p.x, p.y, p.z, cr.regard.x - p.x, cr.regard.z - p.z, 1);
            int lum = a > 0.1 ? 0xA000A0 : lumiere(mc, p.x, p.y + 1, p.z), pale = 0xD8DDD0, noir = 0x111111;
            r.pave(c, pose, -0.35F, 0.3F, -0.2F, 0.35F, 1.9F, 0.2F, noir, lum);
            r.pave(c, pose, -0.3F, 1.8F, -0.27F, 0.3F, 2.5F, 0.27F, pale, lum);
            for (int e = -1; e <= 1; e += 2) {
                r.pave(c, pose, e * 0.14F - 0.08F, 2.11F, 0.27F, e * 0.14F + 0.08F, 2.33F, 0.29F, 0x000000, lum);
                r.pave(c, pose, e * 0.45F - 0.08F, 1.55F, 0.0F, e * 0.45F + 0.08F, 1.7F, 0.95F, pale, lum);
            }
            r.pave(c, pose, -0.1F, 1.82F, 0.27F, 0.1F, 2.08F, 0.29F, 0x000000, lum);
        }
    }

    // ------------------------------------------------------------------ l'écran des photos, au kiosque

    static void ecran(PoseStack ps, Vec3 cam, MultiBufferSource.BufferSource buf) {
        ResourceLocation tex = ClientPhoto.texture();
        if (tex == null || !pres(cam, fr.cabane.parc.ParcBuilder2.KIOSQUE_X, fr.cabane.parc.ParcBuilder2.KIOSQUE_Z, 64)) {
            return;
        }
        VertexConsumer v = buf.getBuffer(RenderType.entityCutoutNoCull(tex));
        PoseStack.Pose pose = ps.last();
        Matrix4f m = pose.pose();
        int kx = fr.cabane.parc.ParcBuilder2.KIOSQUE_X, kz = fr.cabane.parc.ParcBuilder2.KIOSQUE_Z;
        float x = (float) (kx + 1.07 - cam.x), y0 = (float) (SOL + 1.2 - cam.y), y1 = (float) (SOL + 4.5 - cam.y);
        float z0 = (float) (kz - 1.7 - cam.z), z1 = (float) (kz + 2.7 - cam.z);
        float[][] coins = {{z1, y0, 0, 1}, {z0, y0, 1, 1}, {z0, y1, 1, 0}, {z1, y1, 0, 0}};
        for (float[] k : coins) {
            v.addVertex(m, x, k[1], k[0]).setColor(1F, 1F, 1F, 1F).setUv(k[2], k[3]).setOverlay(OverlayTexture.NO_OVERLAY).setLight(Boites.PLEIN)
                    .setNormal(pose, 1, 0, 0);
        }
        buf.endBatch(RenderType.entityCutoutNoCull(tex));
    }

    private DecorParc() {
    }
}
