package fr.cabane.client.parc;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import fr.cabane.parc.AutoParc;
import fr.cabane.parc.Guirlandes;
import fr.cabane.parc.Maneges;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * La fête, dessinée par le client : les guirlandes d'ampoules qui clignotent en chenillard et les fils de fanions qui
 * flottent, tendus entre les lampadaires ; les drapeaux qui claquent au vent en haut des mâts ; les grappes de ballons
 * des stands ; les projecteurs de couleur au fond de la fontaine (plus forts pendant son ballet) ; et le spectacle des
 * autos-tamponneuses : une boule à facettes qui tourne, ses rayons et ses taches de lumière, quatre lyres qui balaient
 * la piste, un chenillard sous le toit, le tout sur le temps du son qui passe.
 */
final class DecorFete {
    private static final int SOL = Maneges.SOL;
    private static final int[] AMPOULES = {0xFF3B3B, 0xFFD23F, 0x4CFF6A, 0x3BA7FF, 0xFF5CE1, 0xFF8A00, 0x3BFFF0};
    private static final int[] FANIONS = {0xE8333F, 0xFFD23F, 0x2E86DE, 0x2ECC71, 0xFF6FA8, 0xFF8A00, 0xFFFFFF};
    private static final int[][] DRAPEAUX = {{0xE8333F, 0xFFFFFF}, {0x2E86DE, 0xFFD23F}, {0xFF6FA8, 0xFFFFFF}, {0x2ECC71, 0xFFD23F},
            {0xFF8A00, 0x8E44AD}, {0x3BFFF0, 0xE8333F}, {0xFFD23F, 0x2E86DE}, {0x9B59B6, 0xFFFFFF}, {0xFF5C8A, 0x2EC4B6}, {0xFF5C8A, 0x2EC4B6}};
    private static final int[] BALLONS = {0xFF3B3B, 0xFFD23F, 0x3BA7FF, 0x4CFF6A, 0xFF5CE1, 0xFF8A00, 0xFFFFFF, 0xA06BFF};
    private static final int[] DISCO = {0xFF2D6F, 0x2DE2FF, 0xFFE02D, 0x9D4DFF, 0x3BFF7A, 0xFF7A1F};

    /** Le centre et le haut du hall des autos. */
    private static final double AX = AutoParc.PISTE_X, AZ = AutoParc.PISTE_Z, TOIT = SOL + 8;

    /** Les lampadaires vraiment posés (vérifié de temps en temps : le serveur saute ceux qui n'ont pas la place). */
    private static boolean[] poses;
    private static long verifie = -1000;

    private static int lumiere(Minecraft mc, double x, double y, double z) {
        return LevelRenderer.getLightColor(mc.level, BlockPos.containing(x, y, z));
    }

    private static boolean pres(Vec3 cam, double x, double z, double r) {
        return (cam.x - x) * (cam.x - x) + (cam.z - z) * (cam.z - z) < r * r;
    }

    private static int sombre(int rgb, float k) {
        return (int) ((rgb >> 16 & 255) * k) << 16 | (int) ((rgb >> 8 & 255) * k) << 8 | (int) ((rgb & 255) * k);
    }

    // ------------------------------------------------------------------ le dessin (pavés pleins)

    static void dessiner(VertexConsumer c, PoseStack.Pose pose, Vec3 cam, double temps, float partiel) {
        Minecraft mc = Minecraft.getInstance();
        verifier(mc);
        fils(mc, c, null, pose, cam, temps);
        drapeaux(mc, c, pose, cam, temps);
        ballons(mc, c, pose, cam, temps);
        if (pres(cam, 0.5, 0.5, 90)) {
            for (int k = 0; k < 12; k++) {
                double a = k / 12.0 * Math.PI * 2;
                int rgb = Mth.hsvToRgb((float) ((temps * 0.08 + k / 12.0) % 1.0), 0.9F, 1F);
                Boites.boite(c, pose, (float) (0.5 + Math.cos(a) * 5.1 - cam.x), (float) (SOL + 0.93 - cam.y), (float) (0.5 + Math.sin(a) * 5.1 - cam.z),
                        1, 0, 0, 0, 1, 0, 0, 0, 1, 0.24F, 0.03F, 0.24F, rgb, Boites.PLEIN);
            }
        }
        if (pres(cam, AX, AZ, 80)) {
            disco(mc, c, pose, cam, temps, partiel);
        }
    }

    /** La lumière (additive) : halos des ampoules, projecteurs, rayons, taches au sol. */
    static void lueurs(VertexConsumer l, PoseStack.Pose pose, Vec3 cam, double temps, float partiel) {
        Minecraft mc = Minecraft.getInstance();
        fils(mc, null, l, pose, cam, temps);
        if (pres(cam, 0.5, 0.5, 90)) {
            double b = AmbianceParc.ballet(mc, partiel);
            float f = b < 0 ? 0.35F : 0.35F + 0.6F * (float) Math.sin(Math.PI * b) * (0.7F + 0.3F * (float) Math.sin(temps * 18));
            for (int k = 0; k < 12; k++) {
                double a = k / 12.0 * Math.PI * 2;
                int rgb = Mth.hsvToRgb((float) ((temps * 0.08 + k / 12.0) % 1.0), 0.9F, 1F);
                float x = (float) (0.5 + Math.cos(a) * 5.1 - cam.x), z = (float) (0.5 + Math.sin(a) * 5.1 - cam.z);
                Boites.lueur(l, pose, x, (float) (SOL + 0.95 - cam.y), z, 0.6F, 0.04F, 0.6F, rgb, f * 0.6F);
                Boites.lueur(l, pose, x, (float) (SOL + 0.45 - cam.y), z, 0.55F, 0.42F, 0.55F, rgb, f * 0.4F);
                if (b >= 0) {
                    // pendant le ballet : un faisceau qui monte dans le jet
                    Boites.lueur(l, pose, x, (float) (SOL + 2.4 - cam.y), z, 0.22F, 1.6F, 0.22F, rgb, f * 0.35F);
                }
            }
        }
        if (pres(cam, AX, AZ, 80)) {
            discoLumiere(mc, l, pose, cam, temps, partiel);
        }
    }

    // ------------------------------------------------------------------ les guirlandes et les fanions

    private static void verifier(Minecraft mc) {
        long t = mc.level.getGameTime();
        if (poses != null && t - verifie < 100 && t >= verifie) {
            return;
        }
        verifie = t;
        if (poses == null) {
            poses = new boolean[Guirlandes.LAMPES.size()];
        }
        for (int i = 0; i < poses.length; i++) {
            Guirlandes.Lampe l = Guirlandes.LAMPES.get(i);
            poses[i] = mc.level.getBlockState(new BlockPos(l.x(), SOL + 4, l.z())).is(Blocks.LANTERN);
        }
    }

    /** Les fils : les pavés pleins (c non nul) ou les halos (l non nul). */
    private static void fils(Minecraft mc, VertexConsumer c, VertexConsumer l, PoseStack.Pose pose, Vec3 cam, double temps) {
        int chenille = (int) Math.floor(temps * 5);
        for (int f = 0; f < Guirlandes.FILS.size(); f++) {
            Guirlandes.Fil fil = Guirlandes.FILS.get(f);
            if (!poses[fil.a()] || !poses[fil.b()]) {
                continue;
            }
            Guirlandes.Lampe a = Guirlandes.LAMPES.get(fil.a()), b = Guirlandes.LAMPES.get(fil.b());
            double ax = a.x() + 0.5, az = a.z() + 0.5, bx = b.x() + 0.5, bz = b.z() + 0.5, y = SOL + Guirlandes.ACCROCHE;
            double mx = (ax + bx) / 2, mz = (az + bz) / 2;
            if (!pres(cam, mx, mz, 110)) {
                continue;
            }
            double lg = Math.hypot(bx - ax, bz - az);
            int lum = c != null ? lumiere(mc, mx, y, mz) : 0;
            double pas = fil.fanions() ? 0.62 : 0.8;
            int n = Math.max(3, (int) Math.round(lg / pas));
            float px = (float) (ax - cam.x), py = (float) (y - cam.y), pz = (float) (az - cam.z);
            float tx = (float) ((bx - ax) / lg), tz = (float) ((bz - az) / lg);
            for (int i = 1; i <= n; i++) {
                double t = (double) i / n;
                float qx = (float) (ax + (bx - ax) * t - cam.x), qy = (float) (y - fil.creux() * 4 * t * (1 - t) - cam.y), qz = (float) (az + (bz - az) * t - cam.z);
                if (c != null) {
                    // le fil
                    Boites.trait(c, pose, px, py, pz, qx, qy, qz, 0.025F, 0x202020, lum, -1);
                }
                if (i < n) {
                    if (fil.fanions()) {
                        if (c != null) {
                            fanion(c, pose, qx, qy, qz, tx, tz, temps, f * 7 + i, FANIONS[(i + f) % FANIONS.length], lum);
                        }
                    } else {
                        int k = i + f * 3;
                        int rgb = AMPOULES[k % AMPOULES.length];
                        boolean allumee = Math.floorMod(k - chenille, 4) != 0;
                        if (c != null) {
                            Boites.boite(c, pose, qx, qy - 0.13F, qz, 1, 0, 0, 0, 1, 0, 0, 0, 1, 0.075F, 0.1F, 0.075F,
                                    allumee ? rgb : sombre(rgb, 0.35F), allumee ? Boites.PLEIN : lum);
                        } else if (allumee) {
                            Boites.lueur(l, pose, qx, qy - 0.13F, qz, 0.2F, 0.22F, 0.2F, rgb, 0.28F);
                        }
                    }
                }
                px = qx;
                py = qy;
                pz = qz;
            }
        }
    }

    /** Un fanion triangulaire qui pend sous le fil (trois pavés de plus en plus étroits) et qui se balance. */
    private static void fanion(VertexConsumer c, PoseStack.Pose pose, float x, float y, float z, float tx, float tz, double temps, int k, int rgb, int lum) {
        double sw = Math.sin(temps * 2.3 + k * 0.9) * 0.35;
        // l'axe vers le bas, penché autour du fil
        float nx = -tz, nz = tx;
        float dx = (float) (nx * Math.sin(sw)), dy = (float) -Math.cos(sw), dz = (float) (nz * Math.sin(sw));
        float ex = (float) (nx * Math.cos(sw)), ey = (float) Math.sin(sw), ez = (float) (nz * Math.cos(sw));
        float[] larg = {0.2F, 0.13F, 0.06F};
        for (int i = 0; i < 3; i++) {
            float h = 0.07F + i * 0.13F;
            Boites.boite(c, pose, x + dx * h, y + dy * h, z + dz * h, tx, 0, tz, -dx, -dy, -dz, ex, ey, ez, larg[i], 0.066F, 0.015F, rgb, lum);
        }
    }

    // ------------------------------------------------------------------ les drapeaux

    private static void drapeaux(Minecraft mc, VertexConsumer c, PoseStack.Pose pose, Vec3 cam, double temps) {
        // le vent souffle vers l'est, un peu vers le sud
        float vx = 0.94F, vz = 0.34F, nx = -vz, nz = vx;
        for (int d = 0; d < Guirlandes.DRAPEAUX.size(); d++) {
            int[] m = Guirlandes.DRAPEAUX.get(d);
            double x0 = m[0] + 0.5, z0 = m[1] + 0.5, haut = SOL + m[2] + 0.05;
            if (!pres(cam, x0, z0, 120)) {
                continue;
            }
            int lum = lumiere(mc, x0, haut, z0);
            int[] col = DRAPEAUX[d % DRAPEAUX.length];
            float grand = m[2] > Guirlandes.MAT ? 1.5F : 1F;
            int bandes = 10;
            for (int i = 0; i < bandes; i++) {
                double u = (i + 0.5) / bandes;
                double ond = Math.sin(temps * 6.5 - u * 5 + d) * 0.22 * u * grand;
                double along = (0.12 + u * 2.4) * grand;
                float x = (float) (x0 + vx * along + nx * ond - cam.x), z = (float) (z0 + vz * along + nz * ond - cam.z);
                float hb = 0.36F * grand;
                Boites.boite(c, pose, x, (float) (haut - hb - cam.y), z, vx, 0, vz, 0, 1, 0, nx, 0, nz, 0.125F * grand, hb, 0.02F, col[0], lum);
                Boites.boite(c, pose, x, (float) (haut - hb * 3 - cam.y), z, vx, 0, vz, 0, 1, 0, nx, 0, nz, 0.125F * grand, hb, 0.02F, col[1], lum);
            }
        }
    }

    // ------------------------------------------------------------------ les ballons

    private static void ballons(Minecraft mc, VertexConsumer c, PoseStack.Pose pose, Vec3 cam, double temps) {
        for (int g = 0; g < Guirlandes.BALLONS.size(); g++) {
            double[] a = Guirlandes.BALLONS.get(g);
            double ax = a[0], ay = SOL + a[1], az = a[2];
            if (!pres(cam, ax, az, 90)) {
                continue;
            }
            int lum = lumiere(mc, ax, ay + 2, az);
            for (int k = 0; k < 6; k++) {
                double an = k * 1.047 + g, rr = 0.35 + (k % 3) * 0.25;
                double bx = ax + Math.cos(an + Math.sin(temps * 0.7 + k) * 0.2) * rr, bz = az + Math.sin(an + Math.sin(temps * 0.7 + k) * 0.2) * rr;
                double by = ay + 2.0 + (k % 3) * 0.45 + Math.sin(temps * 1.3 + k * 1.7 + g) * 0.12;
                float x = (float) (bx - cam.x), y = (float) (by - cam.y), z = (float) (bz - cam.z);
                int rgb = BALLONS[(k + g * 3) % BALLONS.length];
                Boites.trait(c, pose, (float) (ax - cam.x), (float) (ay - cam.y), (float) (az - cam.z), x, y - 0.32F, z, 0.012F, 0xEEEEEE, lum, -1);
                Boites.boite(c, pose, x, y, z, 1, 0, 0, 0, 1, 0, 0, 0, 1, 0.27F, 0.3F, 0.22F, rgb, lum);
                Boites.boite(c, pose, x, y, z, 1, 0, 0, 0, 1, 0, 0, 0, 1, 0.22F, 0.34F, 0.27F, rgb, lum);
                Boites.boite(c, pose, x, y - 0.35F, z, 1, 0, 0, 0, 1, 0, 0, 0, 1, 0.05F, 0.04F, 0.05F, sombre(rgb, 0.7F), lum);
                // un reflet
                Boites.boite(c, pose, x - 0.1F, y + 0.14F, z + 0.2F, 1, 0, 0, 0, 1, 0, 0, 0, 1, 0.05F, 0.06F, 0.08F, 0xFFFFFF, lum);
            }
        }
    }

    // ------------------------------------------------------------------ le spectacle des autos-tamponneuses

    /** Les lyres, dans les coins sous le toit. */
    private static final double[][] LYRES = {{AX - AutoParc.PISTE_W + 1.5, AZ - AutoParc.PISTE_D + 1.5}, {AX + AutoParc.PISTE_W - 1.5, AZ - AutoParc.PISTE_D + 1.5},
            {AX - AutoParc.PISTE_W + 1.5, AZ + AutoParc.PISTE_D - 1.5}, {AX + AutoParc.PISTE_W - 1.5, AZ + AutoParc.PISTE_D - 1.5}};

    private static Vec3 cibleLyre(int i, double temps) {
        double t = temps * (0.45 + i * 0.07) + i * 1.9;
        return new Vec3(AX + Math.sin(t) * (AutoParc.PISTE_W - 4), SOL + 0.02, AZ + Math.sin(t * 1.6 + i) * (AutoParc.PISTE_D - 3));
    }

    private static int couleurMesure(float partiel, int decal) {
        long mesure = Math.floorDiv(AmbianceParc.temps(partiel), 4);
        return DISCO[(int) Math.floorMod(mesure + decal, DISCO.length)];
    }

    private static void disco(Minecraft mc, VertexConsumer c, PoseStack.Pose pose, Vec3 cam, double temps, float partiel) {
        float bx = (float) (AX - cam.x), bz = (float) (AZ - cam.z), by = (float) (SOL + 6.2 - cam.y);
        // la chaîne et la boule à facettes
        Boites.trait(c, pose, bx, (float) (TOIT - cam.y), bz, bx, by + 1.05F, bz, 0.03F, 0x555555, 0xA000A0, -1);
        Boites.boite(c, pose, bx, by, bz, 1, 0, 0, 0, 1, 0, 0, 0, 1, 0.58F, 0.58F, 0.58F, 0x30343A, 0xA000A0);
        // les pôles
        Boites.boite(c, pose, bx, by + 0.98F, bz, 1, 0, 0, 0, 1, 0, 0, 0, 1, 0.3F, 0.06F, 0.3F, 0x9AA6B4, 0xB000B0);
        Boites.boite(c, pose, bx, by - 0.98F, bz, 1, 0, 0, 0, 1, 0, 0, 0, 1, 0.3F, 0.06F, 0.3F, 0x9AA6B4, 0xB000B0);
        double rot = temps * 0.9;
        int eclat = (int) Math.floor(temps * 12);
        for (int j = -4; j <= 4; j++) {
            double lat = j * Math.PI / 10;
            int n = Math.max(6, (int) Math.round(20 * Math.cos(lat)));
            for (int k = 0; k < n; k++) {
                double phi = k * Math.PI * 2 / n + rot;
                float ux = (float) (Math.cos(lat) * Math.cos(phi)), uy = (float) Math.sin(lat), uz = (float) (Math.cos(lat) * Math.sin(phi));
                float tx = (float) -Math.sin(phi), tz = (float) Math.cos(phi);
                float wx = uy * tz, wy = uz * tx - ux * tz, wz = -uy * tx;
                boolean brille = Math.floorMod((j * 31 + k * 17 + eclat) * 2654435761L, 9) == 0;
                Boites.boite(c, pose, bx + ux * 1.02F, by + uy * 1.02F, bz + uz * 1.02F, tx, 0, tz, wx, wy, wz, ux, uy, uz, 0.17F, 0.17F, 0.03F,
                        brille ? 0xFFFFFF : 0x9AA6B4, brille ? Boites.PLEIN : 0xB000B0);
            }
        }
        // les lyres : un boîtier noir, une lentille de la couleur du moment
        for (int i = 0; i < LYRES.length; i++) {
            double[] ly = LYRES[i];
            float lx = (float) (ly[0] - cam.x), lyy = (float) (TOIT - 0.45 - cam.y), lz = (float) (ly[1] - cam.z);
            Boites.boite(c, pose, lx, lyy, lz, 1, 0, 0, 0, 1, 0, 0, 0, 1, 0.3F, 0.3F, 0.3F, 0x151515, 0x600060);
            Vec3 cible = cibleLyre(i, temps);
            Vec3 d = cible.subtract(ly[0], TOIT - 0.45, ly[1]).normalize();
            Boites.boite(c, pose, (float) (lx + d.x * 0.32), (float) (lyy + d.y * 0.32), (float) (lz + d.z * 0.32), 1, 0, 0, 0, 1, 0, 0, 0, 1, 0.16F, 0.16F, 0.16F,
                    couleurMesure(partiel, i), Boites.PLEIN);
        }
        // le chenillard sous le bord du toit, dedans et dehors
        long tps = AmbianceParc.temps(partiel);
        double x0 = AX - AutoParc.PISTE_W - 2, x1 = AX + AutoParc.PISTE_W + 2, z0 = AZ - AutoParc.PISTE_D - 2, z1 = AZ + AutoParc.PISTE_D + 2;
        int k = 0;
        for (int cote = 0; cote < 4; cote++) {
            boolean selonX = cote < 2;
            double debut = selonX ? x0 + 0.5 : z0 + 0.5, fin = selonX ? x1 + 0.5 : z1 + 0.5;
            for (double s = debut; s <= fin; s += 1) {
                for (int dedans = 0; dedans < 2; dedans++) {
                    double x, z;
                    if (selonX) {
                        x = s;
                        z = cote == 0 ? (dedans == 1 ? z0 + 1.1 : z0 - 0.1) : (dedans == 1 ? z1 - 0.1 : z1 + 1.1);
                    } else {
                        z = s;
                        x = cote == 2 ? (dedans == 1 ? x0 + 1.1 : x0 - 0.1) : (dedans == 1 ? x1 - 0.1 : x1 + 1.1);
                    }
                    boolean allumee = Math.floorMod(k - tps, 3) == 0;
                    int rgb = DISCO[Math.floorMod(k / 3 + (int) (tps / 8), DISCO.length)];
                    Boites.boite(c, pose, (float) (x - cam.x), (float) (SOL + 6.85 - cam.y), (float) (z - cam.z), 1, 0, 0, 0, 1, 0, 0, 0, 1, 0.09F, 0.09F, 0.09F,
                            allumee ? rgb : sombre(rgb, 0.3F), allumee ? Boites.PLEIN : 0x600060);
                }
                k++;
            }
        }
    }

    private static void discoLumiere(Minecraft mc, VertexConsumer l, PoseStack.Pose pose, Vec3 cam, double temps, float partiel) {
        float puls = AmbianceParc.pulsation(partiel);
        float bx = (float) (AX - cam.x), bz = (float) (AZ - cam.z), by = (float) (SOL + 6.2 - cam.y);
        // les rayons de la boule et leurs taches qui tournent sur la piste
        double rot = temps * 0.9;
        for (int k = 0; k < 18; k++) {
            double a = k * 2.3999 + rot, r = 4 + (k * 7 % 11) * 1.3;
            float fx = (float) (AX + Math.cos(a) * r - cam.x), fz = (float) (AZ + Math.sin(a) * r * 0.7 - cam.z), fy = (float) (SOL + 0.03 - cam.y);
            int rgb = k % 3 == 0 ? 0xFFFFFF : DISCO[k % DISCO.length];
            Boites.trait(l, pose, bx, by, bz, fx, fy, fz, 0.035F, rgb, 0, 0.22F * puls);
            Boites.lueur(l, pose, fx, fy, fz, 0.32F, 0.02F, 0.32F, rgb, 0.7F * puls);
        }
        // les faisceaux des lyres (un cône fait de tronçons de plus en plus larges) et leur tache au sol
        for (int i = 0; i < LYRES.length; i++) {
            double[] ly = LYRES[i];
            Vec3 a = new Vec3(ly[0], TOIT - 0.45, ly[1]), cible = cibleLyre(i, temps);
            int rgb = couleurMesure(partiel, i);
            int n = 7;
            for (int q = 0; q < n; q++) {
                Vec3 p0 = a.lerp(cible, (double) q / n), p1 = a.lerp(cible, (double) (q + 1) / n);
                float w = 0.12F + 0.85F * (q + 0.5F) / n;
                Boites.trait(l, pose, (float) (p0.x - cam.x), (float) (p0.y - cam.y), (float) (p0.z - cam.z), (float) (p1.x - cam.x), (float) (p1.y - cam.y),
                        (float) (p1.z - cam.z), w, rgb, 0, 0.11F * puls);
            }
            Boites.lueur(l, pose, (float) (cible.x - cam.x), (float) (cible.y - cam.y), (float) (cible.z - cam.z), 1.5F, 0.02F, 1.5F, rgb, 0.5F * puls);
            Boites.lueur(l, pose, (float) (cible.x - cam.x), (float) (cible.y - cam.y), (float) (cible.z - cam.z), 0.8F, 0.03F, 0.8F, 0xFFFFFF, 0.25F * puls);
        }
        // de grandes nappes de couleur qui glissent sur la piste
        for (int k = 0; k < 5; k++) {
            double t = temps * 0.3 + k * 1.3;
            float x = (float) (AX + Math.sin(t * 1.1 + k) * (AutoParc.PISTE_W - 6) - cam.x), z = (float) (AZ + Math.cos(t * 0.8 + k * 2) * (AutoParc.PISTE_D - 5) - cam.z);
            Boites.lueur(l, pose, x, (float) (SOL + 0.015 - cam.y), z, 3.2F, 0.01F, 3.2F, DISCO[Math.floorMod(k + Math.floorDiv(AmbianceParc.temps(partiel), 4), DISCO.length)], 0.22F * puls);
        }
    }

    private DecorFete() {
    }
}
