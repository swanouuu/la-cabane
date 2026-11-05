package fr.cabane.client.pouvoir;

import com.mojang.blaze3d.vertex.VertexConsumer;
import fr.cabane.pouvoir.Foudre3;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;
import org.joml.Matrix4f;

/**
 * Les bêtes de foudre de la Foudre 3 (voir {@link Foudre3} et docs/foudre3/planche-attaques.png), faites comme le Kirin :
 * des pavés transparents dont les arêtes sont des éclairs. Le guépard part tout droit au ras du sol en courant, plein de
 * gros éclairs autour de lui, et bondit sur sa proie ; l'oiseau-tonnerre se forme au-dessus du joueur (la main levée, la
 * barre), puis vole là où il regarde et explose. Aussi les touches du niveau 3 (un appui ou le clic droit maintenu), les
 * charges du TP et la barre de l'oiseau. Tout se dessine dans les deux passes de {@link ClientFoudre}.
 */
public final class ClientBetes {
    private static final RandomSource HASARD = RandomSource.create();
    /** Un clic droit tenu plus longtemps que ça : l'oiseau ; sinon, le guépard. */
    private static final long APPUI_COURT = 280_000_000L;
    private static final double TAILLE_GUEPARD = 0.085, TAILLE_OISEAU = 0.135;

    /** Une bête : où (et où elle était au tick d'avant), vers où, sa phase ; quand elle a fini ; la charge de l'oiseau. */
    private static final class Bete {
        Vec3 pos;
        Vec3 avant;
        Vec3 dir;
        int phase;
        long debut;
        long fin = -1;
        float charge;
        boolean bondi;
    }

    private static final Map<UUID, Bete> GUEPARDS = new HashMap<>();
    private static final Map<UUID, Bete> OISEAUX = new HashMap<>();

    /** Pour moi : les charges du TP (et quand la prochaine revient), le clic droit tenu, les recharges. */
    private static int charges = Foudre3.CHARGES_TP;
    private static long prochaineCharge;
    private static boolean droitTenu;
    private static long droitDebut;
    private static boolean oiseauDemande;
    private static long pretGuepard;
    private static long pretOiseau;

    private static long tick() {
        Minecraft mc = Minecraft.getInstance();
        return mc.level == null ? 0 : mc.level.getGameTime();
    }

    private static double secondes(long depuis, float partiel) {
        return (tick() - depuis + partiel) / 20.0;
    }

    static boolean actif() {
        return !GUEPARDS.isEmpty() || !OISEAUX.isEmpty();
    }

    // ================================================================ ce que dit le serveur

    public static void bete(Foudre3.Bete p) {
        Map<UUID, Bete> carte = p.sorte() == Foudre3.GUEPARD ? GUEPARDS : OISEAUX;
        Minecraft mc = Minecraft.getInstance();
        boolean moi = mc.player != null && p.joueur().equals(mc.player.getUUID());
        Vec3 pos = new Vec3(p.pos().x(), p.pos().y(), p.pos().z());
        Bete b = carte.get(p.joueur());
        if (b == null || b.fin >= 0 && p.phase() != Foudre3.FIN) {
            b = new Bete();
            b.pos = pos;
            b.avant = pos;
            b.debut = tick();
            carte.put(p.joueur(), b);
        }
        b.avant = b.pos;
        b.pos = pos;
        b.dir = new Vec3(p.dir().x(), p.dir().y(), p.dir().z());
        b.charge = p.charge();
        if (p.phase() == Foudre3.VOL && b.phase == Foudre3.CHARGE) {
            // l'oiseau s'envole : il part de là-haut
            b.avant = pos;
            b.debut = tick();
        }
        b.phase = p.phase();
        if (p.phase() == Foudre3.FIN || p.phase() == Foudre3.ANNULE) {
            b.fin = tick();
            b.bondi = p.sorte() == Foudre3.GUEPARD && p.charge() > 0;
            if (p.phase() == Foudre3.FIN) {
                eclats(pos, p.sorte() == Foudre3.OISEAU);
            }
            if (moi && p.sorte() == Foudre3.OISEAU) {
                oiseauDemande = false;
                droitTenu = false;
                if (p.phase() == Foudre3.ANNULE && b.charge == 0) {
                    pretOiseau = System.nanoTime() + Foudre3.RECHARGE_OISEAU_ANNULE * 50_000_000L;
                }
            }
        }
    }

    public static void charges(Foudre3.Charges p) {
        charges = p.n();
        prochaineCharge = p.dans() > 0 ? System.nanoTime() + p.dans() * 50_000_000L : 0;
    }

    /** L'impact : des étincelles, la secousse si on est tout près. */
    private static void eclats(Vec3 z, boolean oiseau) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            return;
        }
        int n = oiseau ? 160 : 70;
        for (int i = 0; i < n; i++) {
            mc.level.addParticle(ParticleTypes.ELECTRIC_SPARK, z.x + HASARD.nextGaussian() * 2, z.y + HASARD.nextDouble() * 3, z.z + HASARD.nextGaussian() * 2,
                    HASARD.nextGaussian() * 0.5, 0.2 + HASARD.nextDouble() * 0.5, HASARD.nextGaussian() * 0.5);
        }
        if (oiseau) {
            mc.level.addParticle(ParticleTypes.EXPLOSION_EMITTER, z.x, z.y + 0.5, z.z, 0, 0, 0);
        }
        double loin = mc.player.position().distanceTo(z);
        if (loin < (oiseau ? 30 : 14)) {
            ClientFoudre.secouer(oiseau ? (loin < 10 ? 1.4F : 0.7F) : 0.6F);
        }
    }

    // ================================================================ mes touches (appelées par ClientFoudre)

    /** Le clic droit, au niveau 3 : on ne sait pas encore si c'est un appui (le guépard) ou s'il le tient (l'oiseau). */
    static void appui() {
        if (!droitTenu) {
            droitTenu = true;
            droitDebut = System.nanoTime();
        }
    }

    /** Chaque tick, au niveau 3 : un appui court lâché, le guépard ; tenu, l'oiseau commence à se charger ; lâché, il s'envole. */
    static void tick(Minecraft mc) {
        long maintenant = System.nanoTime();
        if (!droitTenu) {
            return;
        }
        if (!mc.options.keyUse.isDown()) {
            if (oiseauDemande) {
                PacketDistributor.sendToServer(new Foudre3.Oiseau(false));
                pretOiseau = maintenant + Foudre3.RECHARGE_OISEAU * 50_000_000L;
            } else if (maintenant - droitDebut < APPUI_COURT * 2 && maintenant >= pretGuepard) {
                pretGuepard = maintenant + Foudre3.RECHARGE_GUEPARD * 50_000_000L;
                mc.player.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
                PacketDistributor.sendToServer(new Foudre3.Guepard());
            }
            droitTenu = false;
            oiseauDemande = false;
            return;
        }
        if (!oiseauDemande && maintenant - droitDebut >= APPUI_COURT && maintenant >= pretOiseau) {
            oiseauDemande = true;
            PacketDistributor.sendToServer(new Foudre3.Oiseau(true));
        }
    }

    /** Accroupi, au niveau 3 : une charge de TP (le serveur décide ; on la compte tout de suite). */
    static boolean prendreCharge() {
        if (charges <= 0) {
            return false;
        }
        charges--;
        if (prochaineCharge == 0) {
            prochaineCharge = System.nanoTime() + Foudre3.RETOUR_CHARGE * 50_000_000L;
        }
        return true;
    }

    /** Pendant la charge de l'oiseau, il ne bouge pas. */
    static boolean enCharge() {
        Minecraft mc = Minecraft.getInstance();
        Bete b = mc.player == null ? null : OISEAUX.get(mc.player.getUUID());
        return b != null && b.fin < 0 && b.phase == Foudre3.CHARGE;
    }

    static void endormi() {
        charges = Foudre3.CHARGES_TP;
        prochaineCharge = 0;
        droitTenu = false;
        oiseauDemande = false;
        pretGuepard = 0;
        pretOiseau = 0;
    }

    /** Les lignes du panneau, au niveau 3. */
    static String[][] lignes() {
        long maintenant = System.nanoTime();
        double g = Math.max(0, (pretGuepard - maintenant) / 1e9), o = Math.max(0, (pretOiseau - maintenant) / 1e9);
        double c = prochaineCharge > 0 ? Math.max(0, (prochaineCharge - maintenant) / 1e9) : 0;
        String tp = "TP éclair " + "■".repeat(Math.max(0, charges)) + "□".repeat(Math.max(0, Foudre3.CHARGES_TP - charges)) + (charges < Foudre3.CHARGES_TP && c > 0 ? "  " + (int) Math.ceil(c) + " s" : "");
        return new String[][]{
                {Clavier.gauche(), "Choc en chaîne"},
                {Clavier.droite(), PanneauPouvoir.recharge("Guépard", g)},
                {Clavier.droite() + " tenu", PanneauPouvoir.recharge("Oiseau-tonnerre", o)},
                {Clavier.accroupi(), tp},
        };
    }

    /** La barre de l'oiseau, au-dessus de la barre d'objets : plus elle est pleine, plus il fera mal. */
    static void barre(GuiGraphics g, Font font) {
        Minecraft mc = Minecraft.getInstance();
        Bete b = mc.player == null ? null : OISEAUX.get(mc.player.getUUID());
        if (b == null || b.fin >= 0 || b.phase != Foudre3.CHARGE) {
            return;
        }
        float charge = (float) Math.min(1, secondes(b.debut, 0) / (Foudre3.CHARGE_OISEAU / 20.0));
        int w = 182, h = 9;
        int x = g.guiWidth() / 2 - w / 2, y = g.guiHeight() - 72;
        boolean plein = charge >= 1;
        int degats = Math.round(10 + 16 * charge);
        String droite = plein ? "MAX · " + degats + " dégâts" : degats + " dégâts";
        g.drawString(font, "OISEAU-TONNERRE", x, y - 11, 0xFF8CD8FF, true);
        g.drawString(font, droite, x + w - font.width(droite), y - 11, plein ? 0xFFFFFFFF : 0xFFBFE8FF, true);
        g.fill(x - 1, y - 1, x + w + 1, y + h + 1, 0xC0000000);
        boolean clignote = plein && (System.nanoTime() / 150_000_000L) % 2 == 0;
        g.fillGradient(x, y, x + Math.round(w * charge), y + h, clignote ? 0xFFFFFFFF : 0xFF3FA8FF, clignote ? 0xFFDFF4FF : 0xFFB8E8FF);
        String aide = "Lâche " + Clavier.droite() + " : il s'envole, dirige-le du regard · un coup reçu annule tout";
        g.drawString(font, aide, g.guiWidth() / 2 - font.width(aide) / 2, y + h + 4, 0xFFD0D0D0, true);
    }

    // ================================================================ la pose

    /** L'oiseau qui se charge : la main droite levée vers lui ; en vol : le bras tendu devant, il le guide. */
    static void poser(PlayerModel<?> modele, Player joueur) {
        Bete b = OISEAUX.get(joueur.getUUID());
        if (b == null || b.fin >= 0 && secondes(b.fin, 0) > 0.3) {
            return;
        }
        float k = b.fin >= 0 ? (float) (1 - secondes(b.fin, 0) / 0.3) : 1;
        float bras = b.phase == Foudre3.CHARGE ? -2.95F : -1.55F;
        modele.rightArm.xRot = Mth.lerp(k, modele.rightArm.xRot, bras);
        modele.rightArm.yRot = Mth.lerp(k, modele.rightArm.yRot, b.phase == Foudre3.CHARGE ? 0 : modele.head.yRot);
        modele.rightArm.zRot = Mth.lerp(k, modele.rightArm.zRot, 0.1F);
        modele.rightSleeve.copyFrom(modele.rightArm);
    }

    // ================================================================ dessiner les bêtes

    private static Vec3 rotX(Vec3 v, double a) {
        return new Vec3(v.x, v.y * Math.cos(a) - v.z * Math.sin(a), v.y * Math.sin(a) + v.z * Math.cos(a));
    }

    private static Vec3 rotY(Vec3 v, double a) {
        return new Vec3(v.x * Math.cos(a) + v.z * Math.sin(a), v.y, -v.x * Math.sin(a) + v.z * Math.cos(a));
    }

    private static Vec3 rotZ(Vec3 v, double a) {
        return new Vec3(v.x * Math.cos(a) - v.y * Math.sin(a), v.x * Math.sin(a) + v.y * Math.cos(a), v.z);
    }

    /** Une pièce : son pavé (x0, y0, z0, x1, y1, z1) dans le repère de la bête, son pivot, ses rotations (rx, rz, ry). */
    private record Piece(double[] b, Vec3 pivot, double rx, double rz, double ry) {
    }

    private static final int[][] ARETES = {{0, 1}, {1, 2}, {2, 3}, {3, 0}, {4, 5}, {5, 6}, {6, 7}, {7, 4}, {0, 4}, {1, 5}, {2, 6}, {3, 7}};
    private static final int[][] FACES = {{0, 1, 2, 3}, {4, 5, 6, 7}, {0, 1, 5, 4}, {3, 2, 6, 7}, {0, 3, 7, 4}, {1, 2, 6, 5}};
    private static final float[] VOILE = {0.72F, 0.9F, 1F};

    /**
     * Les pièces posées en o (relatif à la caméra), tournées de lacet (et de tangage, le nez vers le bas si positif), à
     * l'échelle s (blocs par unité) : chaque face un voile bleu clair, chaque arête un éclair.
     */
    private static void dessinerBete(VertexConsumer c, Matrix4f m, List<Piece> pieces, Vec3 o, double lacet, double tangage, double s, long graine, float alpha) {
        int i = 0;
        for (Piece p : pieces) {
            double[] b = p.b();
            Vec3[] coins = new Vec3[8];
            double[][] l = {{b[0], b[1], b[2]}, {b[3], b[1], b[2]}, {b[3], b[4], b[2]}, {b[0], b[4], b[2]}, {b[0], b[1], b[5]}, {b[3], b[1], b[5]}, {b[3], b[4], b[5]}, {b[0], b[4], b[5]}};
            for (int k = 0; k < 8; k++) {
                Vec3 v = new Vec3(l[k][0], l[k][1], l[k][2]).subtract(p.pivot());
                v = rotY(rotX(rotZ(v, p.rz()), p.rx()), p.ry()).add(p.pivot());
                coins[k] = o.add(rotY(rotX(v, tangage), lacet).scale(s));
            }
            if (ClientFoudre.passe == 0) {
                for (int[] f : FACES) {
                    for (int k : f) {
                        ClientFoudre.sommet(c, m, coins[k], VOILE, 0.24F * alpha);
                    }
                }
            }
            for (int[] a : ARETES) {
                ClientFoudre.trait(c, m, ClientFoudre.zigzag(coins[a[0]], coins[a[1]], graine * 50 + i * 12L + a[0] * 3 + a[1], 0.07, 3), 0.6 * s, alpha, ClientFoudre.CIEL);
            }
            i++;
        }
    }

    private static Piece piece(double x0, double y0, double z0, double x1, double y1, double z1, double px, double py, double pz, double rx, double rz, double ry) {
        return new Piece(new double[]{x0, y0, z0, x1, y1, z1}, new Vec3(px, py, pz), rx, rz, ry);
    }

    /** Le guépard en pleine course (pas : la phase de sa foulée), ou en plein bond. Comme sur la planche. */
    private static List<Piece> guepard(double pas, double bond) {
        double a = pas * Math.PI * 2, dos = -0.06 * Math.sin(a) - 0.25 * bond;
        List<Piece> P = new ArrayList<>();
        P.add(piece(-5.5, 14, 6, 5.5, 25, 22, 0, 18, 6, dos - 0.06, 0, 0));
        P.add(piece(-6, 19, 12, 6, 26, 20, 0, 18, 6, dos - 0.06, 0, 0));
        P.add(piece(-3.6, 17, -8, 3.6, 23, 7, 0, 20, 0, dos, 0, 0));
        P.add(piece(-4.8, 16, -22, 4.8, 24, -7, 0, 20, -7, dos + 0.05, 0, 0));
        P.add(piece(-2.8, 21, 20, 2.8, 27, 30, 0, 23, 20, -0.55 - 0.3 * bond, 0, 0));
        P.add(piece(-3.4, 23, 28, 3.4, 29.5, 35.5, 0, 26, 31, 0.12, 0, 0));
        P.add(piece(-2, 23.2, 35, 2, 26.6, 39, 0, 26, 31, 0.12, 0, 0));
        double[][] pattes = {{-3.6, 16, 18, 0, 1}, {3.6, 16, 18, 0.9, 1}, {-3.6, -15, 19, Math.PI, 0}, {3.6, -15, 19, Math.PI + 0.9, 0}};
        for (double[] pt : pattes) {
            double x = pt[0], z = pt[1], y0 = pt[2], s = Math.sin(a + pt[3]);
            boolean avant = pt[4] > 0;
            double l1 = 9, l2 = 10;
            double a1 = (avant ? 0.7 : -0.7) * s - (avant ? 0.8 : -0.9) * bond;
            double a2 = avant ? -0.6 - 0.5 * Math.max(0, -s) - 0.6 * bond : 0.7 + 0.5 * Math.max(0, s) + 0.5 * bond;
            double gy = y0 - l1 * Math.cos(a1), gz = z - l1 * Math.sin(a1);
            P.add(piece(x - 1.8, y0 - l1, z - 2, x + 1.8, y0, z + 2, x, y0, z, a1, 0, 0));
            P.add(piece(x - 1.3, gy - l2, gz - 1.3, x + 1.3, gy, gz + 1.3, x, gy, gz, a1 + a2, 0, 0));
        }
        double bx = 0, by = 21, bz = -21;
        double[] angles = {-0.45, -0.25, 0.15, 0.75};
        for (int q = 0; q < 4; q++) {
            double l = 11, angle = angles[q] + 0.1 * Math.sin(a + q);
            P.add(piece(bx - 1.2 + 0.1 * q, by - 1.1, bz - l, bx + 1.2 - 0.1 * q, by + 1.1, bz, bx, by, bz, angle, 0, 0));
            by += l * Math.sin(angle);
            bz -= l * Math.cos(angle);
        }
        return P;
    }

    /** L'oiseau-tonnerre, les ailes qui battent (battement : 0 à 1). */
    private static List<Piece> oiseau(double battement) {
        double b = Math.sin(battement * Math.PI * 2) * 0.5;
        List<Piece> P = new ArrayList<>();
        P.add(piece(-5, -4, -14, 5, 5, 14, 0, 0, 0, 0, 0, 0));
        P.add(piece(-3.6, 0, 12, 3.6, 7, 22, 0, 0, 0, 0, 0, 0));
        P.add(piece(-1.6, 1, 22, 1.6, 4, 30, 0, 0, 0, 0, 0, 0));
        for (int sx : new int[]{-1, 1}) {
            double rz = sx * (b + 0.1);
            P.add(piece(sx > 0 ? 4 : -26, 0, -8, sx > 0 ? 26 : -4, 2, 8, sx * 4, 1, 0, 0, rz, 0));
            P.add(piece(sx > 0 ? 26 : -50, 0.5, -10, sx > 0 ? 50 : -26, 2, 4, sx * 4, 1, 0, 0, rz, 0));
            P.add(piece(sx > 0 ? 50 : -74, 1, -14, sx > 0 ? 74 : -50, 2, -2, sx * 4, 1, 0, 0, rz, 0));
        }
        for (double[] q : new double[][]{{-6, 0.35}, {0, 0}, {6, -0.35}}) {
            P.add(piece(q[0] - 3, -1, -36, q[0] + 3, 1, -14, q[0], 0, -14, 0, 0, q[1]));
        }
        return P;
    }

    /** Deux points de lumière (les yeux), sans lueur autour : seulement dans la passe du translucide. */
    private static void yeux(VertexConsumer c, Matrix4f m, Vec3 a, Vec3 b, double s) {
        if (ClientFoudre.passe != 0) {
            return;
        }
        float[] col = {1F, 0.98F, 0.8F};
        for (Vec3 p : new Vec3[]{a, b}) {
            Vec3 vers = p.normalize();
            Vec3 x = vers.cross(new Vec3(0, 1, 0)).normalize().scale(s), y = x.cross(vers).normalize().scale(s);
            ClientFoudre.sommet(c, m, p.subtract(x).subtract(y), col, 1);
            ClientFoudre.sommet(c, m, p.add(x).subtract(y), col, 1);
            ClientFoudre.sommet(c, m, p.add(x).add(y), col, 1);
            ClientFoudre.sommet(c, m, p.subtract(x).add(y), col, 1);
        }
    }

    /** Un point du repère de la bête, dans le monde (relatif à la caméra). */
    private static Vec3 point(Vec3 o, double lacet, double tangage, double s, double x, double y, double z) {
        return o.add(rotY(rotX(new Vec3(x, y, z), tangage), lacet).scale(s));
    }

    /** Appelé par ClientFoudre, dans chacune de ses deux passes. */
    static void dessiner(VertexConsumer c, Matrix4f m, Vec3 cam, float partiel) {
        Minecraft mc = Minecraft.getInstance();
        long tranche = System.nanoTime() / 70_000_000L;
        for (Map.Entry<UUID, Bete> en : GUEPARDS.entrySet()) {
            Bete b = en.getValue();
            long graine = en.getKey().getLeastSignificantBits();
            Vec3 o = (b.fin >= 0 ? b.pos : b.avant.lerp(b.pos, partiel)).subtract(cam);
            double lacet = Math.atan2(b.dir.x, b.dir.z), t = secondes(b.debut, partiel);
            if (b.fin < 0) {
                dessinerBete(c, m, guepard(t * 2.2, 0), o, lacet, 0, TAILLE_GUEPARD, graine, 1);
                Vec3 oeil1 = point(o, lacet, 0, TAILLE_GUEPARD, -2.1, 27.3, 35.6), oeil2 = point(o, lacet, 0, TAILLE_GUEPARD, 2.1, 27.3, 35.6);
                yeux(c, m, oeil1, oeil2, 0.07);
                // de gros éclairs tout autour de lui, au sol sous ses pattes, et derrière lui
                ClientFoudre.crepite(c, m, o.add(b.dir.scale(-0.5)), 1.4, 2.4, 18, 10, 10, graine + tranche, 0.07, 1);
                Random r = new Random(graine + tranche);
                for (int i = 0; i < 4; i++) {
                    Vec3 de = o.add(0, 0.8 + i * 0.4, 0).subtract(b.dir.scale(1.5));
                    ClientFoudre.eclair(c, m, de, de.subtract(b.dir.scale(5 + 4 * r.nextDouble())).add(r.nextGaussian() * 0.6, r.nextGaussian() * 0.4, r.nextGaussian() * 0.6),
                            r.nextLong(), i == 1 ? 0.09 : 0.05, 1, 2, ClientFoudre.CIEL);
                }
            } else {
                double tf = secondes(b.fin, partiel);
                float a = (float) Math.max(0, 1 - tf / 0.8);
                if (a <= 0) {
                    continue;
                }
                if (b.bondi && tf < 0.35) {
                    // en plein bond sur sa proie
                    dessinerBete(c, m, guepard(0.6, 1), o.add(0, 1.2 * (1 - tf / 0.35), 0).subtract(b.dir.scale(1.5 * (1 - tf / 0.35))), lacet, 0, TAILLE_GUEPARD, graine, a);
                }
                ClientFoudre.eclair(c, m, o.add(2, 30, -1.5), o.add(0, 0.2, 0), graine * 3 + tranche, b.bondi ? 0.2 : 0.12, a, 6, ClientFoudre.CIEL);
                ClientFoudre.anneau(c, m, o.add(0, 0.06, 0), 0.5 + 5 * Math.min(1, tf / 0.3), 1.2, ClientFoudre.BLANC, a);
                ClientFoudre.crepite(c, m, o, 2.5, 2, 12, 8, 10, graine + tranche, 0.05, a);
            }
        }
        for (Map.Entry<UUID, Bete> en : OISEAUX.entrySet()) {
            Bete b = en.getValue();
            long graine = en.getKey().getLeastSignificantBits() + 99;
            double t = secondes(b.debut, partiel);
            if (b.fin < 0) {
                Player lanceur = mc.level.getPlayerByUUID(en.getKey());
                Vec3 o;
                double lacet, tangage = 0, taille;
                if (b.phase == Foudre3.CHARGE) {
                    // il se forme au-dessus de lui, de plus en plus grand, et la foudre part de sa main levée
                    Vec3 pied = lanceur == null ? b.pos.subtract(0, 12, 0) : lanceur.getPosition(partiel);
                    o = pied.add(0, 12 + Math.sin(t * 3) * 0.4, 0).subtract(cam);
                    lacet = lanceur == null ? 0 : -lanceur.getViewYRot(partiel) * Math.PI / 180;
                    double charge = Math.min(1, t / (Foudre3.CHARGE_OISEAU / 20.0));
                    taille = TAILLE_OISEAU * (0.35 + 0.65 * charge);
                    Vec3 main = pied.add(0, 2.3, 0).subtract(cam);
                    ClientFoudre.eclair(c, m, main, o, graine + tranche, 0.06 + 0.06 * charge, 1, 3, ClientFoudre.CIEL);
                    ClientFoudre.crepite(c, m, pied.subtract(cam), 0.6, 2, 8, 4, 4, graine + tranche + 1, 0.035, 1);
                    dessinerBete(c, m, oiseau(t * 1.2), o, lacet, 0, taille, graine, (float) (0.5 + 0.5 * charge));
                } else {
                    o = b.avant.lerp(b.pos, partiel).subtract(cam);
                    lacet = Math.atan2(b.dir.x, b.dir.z);
                    tangage = -Math.asin(Mth.clamp(b.dir.y, -1, 1));
                    taille = TAILLE_OISEAU;
                    dessinerBete(c, m, oiseau(t * 1.6), o, lacet, tangage, taille, graine, 1);
                    // la traînée de foudre derrière lui, et les plumes d'éclairs
                    Random r = new Random(graine + tranche);
                    for (int i = 0; i < 3; i++) {
                        Vec3 de = o.subtract(b.dir.scale(3));
                        ClientFoudre.eclair(c, m, de, de.subtract(b.dir.scale(10 + 6 * r.nextDouble())).add(r.nextGaussian(), r.nextGaussian() * 0.5, r.nextGaussian()), r.nextLong(), i == 0 ? 0.12 : 0.06, 1, 2, ClientFoudre.CIEL);
                    }
                }
                Vec3 oeil1 = point(o, lacet, tangage, taille, -3.7, 4.6, 19), oeil2 = point(o, lacet, tangage, taille, 3.7, 4.6, 19);
                yeux(c, m, oeil1, oeil2, 0.09);
            } else {
                double tf = secondes(b.fin, partiel);
                float a = (float) Math.max(0, 1 - tf / 1.4);
                if (a <= 0 || b.phase == Foudre3.ANNULE && tf > 0.4) {
                    continue;
                }
                Vec3 o = b.pos.subtract(cam);
                if (b.phase == Foudre3.ANNULE) {
                    dessinerBete(c, m, oiseau(0.25), o, 0, 0, TAILLE_OISEAU * 0.5, graine, (float) (1 - tf / 0.4));
                    continue;
                }
                // l'explosion : le dôme, la foudre qui tombe, l'onde, des éclairs dans tous les sens
                ClientFoudre.dome(c, m, o, 2 + 8 * Math.min(1, tf / 0.4), a);
                ClientFoudre.eclair(c, m, o.add(3, 40, -2), o, graine * 3 + tranche, 0.3, a, 8, ClientFoudre.CIEL);
                ClientFoudre.anneau(c, m, o.add(0, 0.1, 0), 1 + 12 * Math.min(1, tf / 0.8), 2, ClientFoudre.BLANC, a);
                Random r = new Random(graine + tranche);
                for (int i = 0; i < 10; i++) {
                    Vec3 d = new Vec3(r.nextGaussian(), r.nextDouble() * 0.8, r.nextGaussian()).normalize();
                    ClientFoudre.eclair(c, m, o, o.add(d.scale(5 + 5 * r.nextDouble())), r.nextLong(), 0.06, a, 2, ClientFoudre.CIEL);
                }
            }
        }
    }

    /** Chaque tick : les bêtes finies s'effacent ; une bête en vol trop longtemps sans nouvelles aussi. */
    static void nettoyer() {
        long maintenant = tick();
        GUEPARDS.values().removeIf(b -> b.fin >= 0 && maintenant - b.fin > 20 || b.fin < 0 && maintenant - b.debut > 200);
        OISEAUX.values().removeIf(b -> b.fin >= 0 && maintenant - b.fin > 30 || b.fin < 0 && maintenant - b.debut > 400);
    }

    static void vider() {
        GUEPARDS.clear();
        OISEAUX.clear();
    }

    private ClientBetes() {
    }
}
