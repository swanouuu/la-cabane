package fr.cabane.client.pouvoir;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import fr.cabane.Cabane;
import fr.cabane.client.hachibi4.ClientHachibi4;
import fr.cabane.pouvoir.Hachibi4;
import fr.cabane.pouvoir.Manteau;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.PlayerSkin;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.event.RenderPlayerEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.glfw.GLFW;

/**
 * Le manteau de chakra de Hachibi 4 chez chaque joueur (voir {@link Manteau} et docs/hachibi5) : l'aura rouge sur son
 * corps, les cornes du Hachibi, ses huit queues devenues du chakra, qui prennent la pose de chaque attaque (à plat pour
 * la toupie, plantées dans le sol, torsadées en foreuse, qui piquent en rafale, dressées vers la grande boule) ; et dans
 * le monde : le bras de chakra, les petites Bijuu Dama et leurs dômes, les pics, l'onde du rugissement, la grande boule.
 */
public final class ClientManteau {
    /** La grande boule du manteau, et la pluie de Bijuu Dama du géant. */
    public static final KeyMapping GRANDE = new KeyMapping("key.cabane.grande", GLFW.GLFW_KEY_I, "key.categories.cabane");
    private static final RandomSource HASARD = RandomSource.create();
    private static final float[] NOIR = {0.07F, 0.02F, 0.04F}, SANG = {0.6F, 0.08F, 0.12F}, ROUGE = ClientHachibi4.ROUGE, VIF = {1F, 0.36F, 0.2F},
            BRAISE = {1F, 0.8F, 0.55F};

    private record Balle(int id, boolean grande, Vec3 p, Vec3 d, long debut) {
    }

    private record Boum(Vec3 p, boolean grand, long debut) {
    }

    private record Pic(Vec3 p, int n, long debut) {
    }

    private static final class Etat {
        long debut, fin = -1;
        long bras = -1, lariat = -1, toupie = -1, plonge = -1, rugir = -1, foreuse = -1, rafale = -1, charge = -1, dama = -1;
        boolean bouleFaite;
        Vec3 brasBout;
        final List<Balle> balles = new ArrayList<>();
        final List<Boum> boums = new ArrayList<>();
        final List<Pic> pics = new ArrayList<>();
    }

    private static final Map<UUID, Etat> ETATS = new HashMap<>();
    private static boolean accroupiAvant, clicFait, techniqueAvant;
    private static long accroupiDebut, dernierClic;

    private static double secondes(long depuis) {
        return depuis < 0 ? 99 : (System.nanoTime() - depuis) / 1e9;
    }

    private static double lisse(double x) {
        x = Mth.clamp(x, 0, 1);
        return x * x * (3 - 2 * x);
    }

    /** De 0 à 1 : la pose d'une attaque commencée à ce moment-là, qui dure ce temps. */
    private static double pose(long quand, double duree) {
        double t = secondes(quand);
        return lisse(t / 0.15) * (1 - lisse((t - duree) / 0.3));
    }

    public static boolean actif(UUID joueur) {
        Etat e = ETATS.get(joueur);
        return e != null && e.fin < 0;
    }

    private static Etat moi() {
        Minecraft mc = Minecraft.getInstance();
        Etat e = mc.player == null ? null : ETATS.get(mc.player.getUUID());
        return e != null && e.fin < 0 ? e : null;
    }

    // ================================================================ ce que dit le serveur

    public static void geste(Hachibi4.Geste p) {
        long maintenant = System.nanoTime();
        if (p.geste() == Manteau.DEBUT) {
            Etat e = new Etat();
            // valeur 1 : il l'avait déjà quand on est arrivé (pas d'apparition)
            e.debut = maintenant - (p.valeur() == 1 ? 2_000_000_000L : 0);
            ETATS.put(p.joueur(), e);
            return;
        }
        Etat e = ETATS.get(p.joueur());
        if (e == null) {
            return;
        }
        switch (p.geste()) {
            case Manteau.FIN -> e.fin = maintenant;
            case Manteau.G_BRAS -> {
                e.bras = maintenant;
                e.brasBout = new Vec3(p.a());
            }
            case Manteau.G_LARIAT -> e.lariat = maintenant;
            case Manteau.G_TIR -> {
                if (secondes(e.dama) > 1.5) {
                    e.dama = maintenant;
                }
                e.balles.add(new Balle(p.valeur(), false, new Vec3(p.a()), new Vec3(p.b()), maintenant));
            }
            case Manteau.G_GRANDE -> {
                e.charge = -1;
                e.balles.add(new Balle(p.valeur(), true, new Vec3(p.a()), new Vec3(p.b()), maintenant));
            }
            case Manteau.G_BOUM -> {
                int id = p.valeur() & 0xFFFF;
                boolean grand = (p.valeur() >> 16) != 0;
                e.balles.removeIf(b -> b.id == id);
                Vec3 ou = new Vec3(p.a());
                e.boums.add(new Boum(ou, grand, maintenant));
                Minecraft mc = Minecraft.getInstance();
                if (grand && mc.player != null && ou.distanceTo(mc.player.position()) < 60) {
                    ClientHachibi4.secouer(1.3F);
                }
            }
            case Manteau.G_TOUPIE -> e.toupie = maintenant;
            case Manteau.G_PLONGE -> e.plonge = maintenant;
            case Manteau.G_PIC -> e.pics.add(new Pic(new Vec3(p.a()), p.valeur(), maintenant));
            case Manteau.G_RUGIR -> e.rugir = maintenant;
            case Manteau.G_FOREUSE -> e.foreuse = maintenant;
            case Manteau.G_RAFALE -> e.rafale = maintenant;
            case Manteau.G_CHARGE -> {
                e.charge = maintenant;
                e.bouleFaite = true;
            }
            default -> { }
        }
    }

    // ================================================================ sur le joueur : l'aura, les cornes, les huit queues

    /** Une queue dans une pose : sa base, sa direction, son axe d'enroulement, sa longueur, son rayon, ses courbures. */
    private static double[] queue(Vec3 base, Vec3 d, Vec3 axe, double longueur, double rayon, double c0, double c1, double vague) {
        return new double[] {base.x, base.y, base.z, d.x, d.y, d.z, axe.x, axe.y, axe.z, longueur, rayon, c0, c1, vague};
    }

    private static double[] repos(int i, double t) {
        float[] d = ClientPoulpe.TENTACULES[i];
        double f = Math.toRadians(d[0]);
        return queue(ClientPoulpe.base(i), new Vec3(Math.sin(f), Math.cos(f), d[3]).normalize(),
                new Vec3(0.35 * Math.cos(i * 1.7), 0.35 * Math.sin(i * 2.3), d[1]).normalize(), d[2], 4.8, 0.0015 + 0.003 * Math.sin(t * 1.6 + i * 0.8), 0.11, 0.018);
    }

    /** La pose de la queue i dans l'attaque en cours (genre : 1 toupie, 2 dans le sol, 3 foreuse, 4 rafale, 5 vers la boule). */
    private static double[] attaque(int genre, int i, double t) {
        double f = Math.toRadians(ClientPoulpe.TENTACULES[i][0]), a = i * Mth.TWO_PI / 8;
        switch (genre) {
            case 1 -> {
                a += t * 16;
                return queue(new Vec3(Math.sin(a) * 2.4, 13, Math.cos(a) * 2.4), new Vec3(Math.sin(a), 0.1, Math.cos(a)).normalize(), new Vec3(0, 1, 0), 96, 4.8, 0.013, 0, 0);
            }
            case 2 -> {
                Vec3 d = new Vec3(Math.sin(f) * 0.9, 0.75, -0.55).normalize();
                return queue(ClientPoulpe.base(i), d, d.cross(new Vec3(0, -1, 0)).normalize(), 66, 4.2, 0.042, 0, 0);
            }
            case 3 -> {
                a += t * 22;
                return queue(new Vec3(Math.cos(a) * 7, 12, Math.sin(a) * 7), new Vec3(Math.sin(a) * 0.5, 0.86, -Math.cos(a) * 0.5).normalize(), new Vec3(0, 1, 0), 84, 4, 0.071, 0.2, 0);
            }
            case 4 -> {
                // chacune pique à son tour (un coup tous les quarts de seconde)
                double coup = Math.sin(Math.PI * Mth.clamp((t - i * 0.25) / 0.32, 0, 1));
                Vec3 d = new Vec3(Math.sin(f) * 0.45, Math.cos(f) * 0.3 + 0.15, 1).normalize();
                return queue(ClientPoulpe.base(i), d, d.cross(new Vec3(0, 0, 1)).normalize(), 34 + 100 * coup, 4.4, 0.004, 0, 0);
            }
            default -> {
                Vec3 d = new Vec3(Math.sin(a), 0.5, Math.cos(a)).normalize();
                return queue(new Vec3(Math.sin(a) * 2.4, 13, Math.cos(a) * 2.4), d, d.cross(new Vec3(0, 1, 0)).normalize(), 84, 4.6, 0.0265, 0, 0);
            }
        }
    }

    /** Appelé par ClientHachibi une fois que le jeu a dessiné le joueur : tout ce que le manteau pose sur lui. */
    static void apres(RenderPlayerEvent.Post event, AbstractClientPlayer joueur) {
        Etat e = ETATS.get(joueur.getUUID());
        if (e == null) {
            return;
        }
        PoseStack ps = event.getPoseStack();
        MultiBufferSource buffers = event.getMultiBufferSource();
        float partiel = event.getPartialTick();
        PlayerModel<AbstractClientPlayer> modele = event.getRenderer().getModel();
        double t = secondes(e.debut), temps = (joueur.tickCount + partiel) / 20.0;
        float force = (float) (lisse(t / 0.5) * (e.fin < 0 ? 1 : 1 - lisse(secondes(e.fin) / 0.4)));
        // l'attaque en cours, et à quel point ses queues en ont pris la pose
        int genre = 0;
        double k = 0, depuis = 0;
        long[] quand = {e.toupie, e.plonge, e.foreuse, e.rafale, e.charge};
        double[] durees = {Manteau.DUREE_TOUPIE / 20.0, Manteau.SORTIE_PICS / 20.0 + 0.7, Manteau.DUREE_FOREUSE / 20.0, Manteau.DUREE_RAFALE / 20.0, Manteau.CHARGE_BOULE / 20.0};
        for (int i = 0; i < quand.length; i++) {
            double p = pose(quand[i], durees[i]);
            if (p > k) {
                k = p;
                genre = i + 1;
                depuis = secondes(quand[i]);
            }
        }
        ps.pushPose();
        ClientHachibi.repereModele(ps, joueur, partiel, 1);
        // dans la foreuse, le jeu le dessine couché et tournant (comme un trident) : l'aura debout ne lui irait pas
        if (genre != 3 || k < 0.3) {
            float vacille = 0.9F + 0.1F * Mth.sin((float) t * 7);
            boolean fin = joueur.getSkin().model() == PlayerSkin.Model.SLIM;
            VertexConsumer c = buffers.getBuffer(RenderType.entityTranslucentEmissive(ClientHachibi.MANTEAU));
            ClientHachibi.enveloppe(modele, ps, c, fin, 1F, (Math.round(150 * force * vacille) << 24) | 0xF0281C);
            ClientHachibi.enveloppe(modele, ps, c, fin, 1.14F, (Math.round(55 * force * vacille) << 24) | 0xFF4A28);
            ps.pushPose();
            modele.head.translateAndRotate(ps);
            int rouge = (Math.round(235 * force) << 24) | 0xF03A20, clair = (Math.round(235 * force) << 24) | 0xFFB070;
            for (int s = -1; s <= 1; s += 2) {
                boite(c, ps.last(), s * 3.6F, -7.4F, -1.2F, s * 6.6F, -5.4F, 1.2F, rouge);
                boite(c, ps.last(), s * 5.6F, -10.6F, -0.9F, s * 7.2F, -7.4F, 0.9F, rouge);
                boite(c, ps.last(), s * 6.2F, -12.8F, -0.6F, s * 7.2F, -10.6F, 0.6F, clair);
            }
            ps.popPose();
        }
        ps.pushPose();
        modele.body.translateAndRotate(ps);
        ps.scale(1 / 16F, 1 / 16F, 1 / 16F);
        ps.scale(1, -1, -1);
        ps.translate(0, -24, 0);
        if (genre == 3) {
            // la foreuse pointe là où il regarde
            ps.translate(0, 16, 0);
            ps.mulPose(Axis.XP.rotationDegrees((float) ((90 + Mth.lerp(partiel, joueur.xRotO, joueur.getXRot())) * k)));
            ps.translate(0, -16, 0);
        }
        VertexConsumer c = ClientPoulpe.tampon(buffers);
        ClientPoulpe.chakra = true;
        for (int i = 0; i < 8; i++) {
            double[] q = repos(i, temps);
            if (genre > 0) {
                double[] z = attaque(genre, i, depuis);
                for (int n = 0; n < q.length; n++) {
                    q[n] = Mth.lerp(k, q[n], z[n]);
                }
            }
            ClientPoulpe.tentacule(c, ps.last(), LightTexture.FULL_BRIGHT, new Vec3(q[0], q[1], q[2]), new Vec3(q[3], q[4], q[5]).normalize(),
                    new Vec3(q[6], q[7], q[8]).normalize(), q[9] * force, q[10] * Math.min(1, 0.3 + force), q[11], q[12], q[13], i * 1.3 + temps * 1.2);
        }
        ClientPoulpe.chakra = false;
        ps.popPose();
        ps.popPose();
    }

    /** Un pavé uni qui brille (en pixels du modèle). */
    private static void boite(VertexConsumer c, PoseStack.Pose p, float xa, float y0, float z0, float xb, float y1, float z1, int couleur) {
        float x0 = Math.min(xa, xb) / 16, x1 = Math.max(xa, xb) / 16;
        y0 /= 16;
        y1 /= 16;
        z0 /= 16;
        z1 /= 16;
        float[][] f = {{0, 0, 1, x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1}, {0, 0, -1, x1, y0, z0, x0, y0, z0, x0, y1, z0, x1, y1, z0},
                {1, 0, 0, x1, y0, z1, x1, y0, z0, x1, y1, z0, x1, y1, z1}, {-1, 0, 0, x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0},
                {0, 1, 0, x0, y1, z1, x1, y1, z1, x1, y1, z0, x0, y1, z0}, {0, -1, 0, x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1}};
        for (float[] q : f) {
            for (int v = 0; v < 4; v++) {
                c.addVertex(p, q[3 + v * 3], q[4 + v * 3], q[5 + v * 3]).setColor(couleur).setUv(0.5F, 0.5F).setOverlay(OverlayTexture.NO_OVERLAY)
                        .setLight(LightTexture.FULL_BRIGHT).setNormal(p, q[0], q[1], q[2]);
            }
        }
    }

    // ================================================================ dans le monde : le bras, les boules, les pics, les ondes

    private static void dessiner(PoseStack ps, Vec3 cam, float partiel) {
        Minecraft mc = Minecraft.getInstance();
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        Matrix4f m = ps.last().pose();
        for (Map.Entry<UUID, Etat> entree : ETATS.entrySet()) {
            Etat e = entree.getValue();
            Player j = mc.level.getPlayerByUUID(entree.getKey());
            if (j == null) {
                continue;
            }
            Vec3 pied = j.getPosition(partiel);
            float lacet = Mth.rotLerp(partiel, j.yBodyRotO, j.yBodyRot);
            double a = Math.toRadians(lacet);
            Vec3 droite = new Vec3(-Math.cos(a), 0, -Math.sin(a));
            VertexConsumer voile = buffers.getBuffer(ClientHachibi4.Types.TRANSLUCIDE), lueur = buffers.getBuffer(ClientHachibi4.Types.LUMIERE);
            // le bras de chakra : il part de son épaule, file jusqu'à sa prise et revient ; sa main, une boule de chakra
            double tb = secondes(e.bras) / 0.45;
            if (tb < 1 && e.brasBout != null) {
                double sort = Math.sin(Math.PI * tb);
                Vec3 epaule = pied.add(0, 1.35, 0).add(droite.scale(0.4)), main = epaule.lerp(e.brasBout, sort);
                List<Vec3> ligne = new ArrayList<>();
                for (int i = 0; i <= 12; i++) {
                    double u = i / 12.0;
                    ligne.add(epaule.lerp(main, u).add(0, Math.sin(Math.PI * u) * 0.7 * sort, 0).subtract(cam));
                }
                for (int i = 0; i < 12; i++) {
                    faisceau(voile, m, ligne.get(i), ligne.get(i + 1), 0.2 + 0.16 * i / 12.0, i % 2 == 0 ? ROUGE : VIF, 0.95F);
                    faisceau(lueur, m, ligne.get(i), ligne.get(i + 1), 0.34 + 0.2 * i / 12.0, VIF, 0.35F);
                }
                ClientHachibi4.boule(voile, m, main.subtract(cam), 0.55, 8, 12, (th, ph) -> Math.sin(ph * 5) > 0.3 ? VIF : ROUGE, 0.95F);
                ClientHachibi4.boule(lueur, m, main.subtract(cam), 0.8, 8, 12, (th, ph) -> BRAISE, 0.4F);
            }
            // le Lariat : son bras gonflé de chakra
            if (secondes(e.lariat) < Manteau.DUREE_LARIAT / 20.0 + 0.15) {
                Vec3 bras = pied.add(0, 1.25, 0).add(droite.scale(0.75)).subtract(cam);
                ClientHachibi4.boule(voile, m, bras, 0.5, 8, 12, (th, ph) -> ROUGE, 0.9F);
                ClientHachibi4.boule(lueur, m, bras, 0.85, 8, 12, (th, ph) -> BRAISE, 0.45F);
            }
            // la toupie : l'onde qui tourne autour de lui
            double tt = secondes(e.toupie) / (Manteau.DUREE_TOUPIE / 20.0);
            if (tt < 1) {
                Vec3 c = pied.add(0, 0.8, 0).subtract(cam);
                ClientHachibi4.anneau(lueur, m, c, 4.2 + 1.6 * tt, 0.5, VIF, (float) (0.6 * (1 - tt)));
                ClientHachibi4.anneau(lueur, m, c.add(0, 0.2, 0), 5.6, 0.3, BRAISE, (float) (0.4 * Math.sin(Math.PI * tt)));
            }
            // le rugissement : une bulle de chakra qui s'élargit
            double tr = secondes(e.rugir) / 0.55;
            if (tr < 1) {
                Vec3 c = pied.add(0, 0.6, 0).subtract(cam);
                double r = 1 + 7.5 * Math.sqrt(tr);
                ClientHachibi4.boule(voile, m, c, r, 14, 22, (th, ph) -> ROUGE, (float) (0.3 * (1 - tr)));
                ClientHachibi4.boule(lueur, m, c, r * 1.05, 12, 18, (th, ph) -> VIF, (float) (0.35 * (1 - tr)));
                ClientHachibi4.anneau(lueur, m, pied.add(0, 0.2, 0).subtract(cam), r, 0.8, BRAISE, (float) (0.7 * (1 - tr)));
            }
            // la grande boule qui se forme au-dessus de lui : elle grossit, le chakra y converge
            if (e.charge >= 0) {
                double t = secondes(e.charge), kk = Math.min(1, t / (Manteau.CHARGE_BOULE / 20.0));
                Vec3 c = pied.add(0, Manteau.HAUT_BOULE, 0).subtract(cam);
                dama(voile, lueur, m, c, 0.3 + (Manteau.RAYON_GRANDE - 0.3) * Math.sqrt(kk), t);
                for (int i = 0; i < 18; i++) {
                    double phase = (t * 1.2 + i / 18.0) % 1, an = i * 2.39996 + t * 3, d = (Manteau.RAYON_GRANDE + 4) * (1 - phase);
                    float[] col = i % 3 == 0 ? VIF : i % 3 == 1 ? ROUGE : BRAISE;
                    ClientHachibi4.cube(lueur, m, c.add(Math.cos(an) * d, Math.sin(an * 1.3) * d * 0.6, Math.sin(an) * d), 0.12, col, 0.9F);
                }
            }
            for (Balle b : e.balles) {
                double t = secondes(b.debut), v = (b.grande ? Manteau.VITESSE_GRANDE : Manteau.VITESSE_DAMA) * 20;
                double r = b.grande ? Manteau.RAYON_GRANDE : Manteau.RAYON_PETITE;
                for (int n = 3; n >= 0; n--) {
                    Vec3 c = b.p.add(b.d.scale(Math.max(0, t - n * 0.035) * v)).subtract(cam);
                    if (n == 0) {
                        dama(voile, lueur, m, c, r, t);
                    } else {
                        ClientHachibi4.boule(lueur, m, c, r * (1 - n * 0.2), 8, 12, (th, ph) -> SANG, 0.3F / n);
                    }
                }
            }
            for (Boum b : e.boums) {
                double u = secondes(b.debut) / (b.grand ? 0.9 : 0.5);
                if (u < 1) {
                    Vec3 c = b.p.subtract(cam);
                    double r = (b.grand ? 2 : 0.6) + (b.grand ? Manteau.DOME_GRAND : Manteau.DOME_PETIT) * Math.sqrt(u);
                    ClientHachibi4.boule(voile, m, c, r, 14, 22, (th, ph) -> Math.cos(th) > 0.25 ? BRAISE : ROUGE, (float) (0.8 * (1 - u)));
                    ClientHachibi4.boule(lueur, m, c, r * 1.08, 10, 16, (th, ph) -> VIF, (float) (0.5 * (1 - u)));
                    ClientHachibi4.anneau(lueur, m, c.add(0, -r * 0.3, 0), r * 1.3, 0.3 + r * 0.12, BRAISE, (float) (0.6 * (1 - u)));
                }
            }
            // les pics : des queues de chakra qui jaillissent du sol, tiennent un instant, puis rentrent
            if (!e.pics.isEmpty()) {
                VertexConsumer c = ClientPoulpe.tampon(buffers);
                ClientPoulpe.chakra = true;
                for (Pic p : e.pics) {
                    double t = secondes(p.debut), sort = lisse(t / 0.12) * (1 - lisse((t - 0.7) / 0.35));
                    ps.pushPose();
                    ps.translate(p.p.x - cam.x, p.p.y - cam.y, p.p.z - cam.z);
                    ps.scale(1 / 16F, 1 / 16F, 1 / 16F);
                    Vec3 d = new Vec3(0.3 * Math.sin(p.n * 2.1), 1, 0.3 * Math.cos(p.n * 1.7)).normalize();
                    ClientPoulpe.tentacule(c, ps.last(), LightTexture.FULL_BRIGHT, new Vec3(0, -6, 0), d, new Vec3(1, 0, 0), (46 + 8 * (p.n % 3)) * sort + 6, 6, 0.004 * (p.n % 2 == 0 ? 1 : -1), 0, 0, 0);
                    ps.popPose();
                }
                ClientPoulpe.chakra = false;
            }
        }
        buffers.endBatch(ClientPoulpe.type());
        buffers.endBatch(ClientHachibi4.Types.TRANSLUCIDE);
        buffers.endBatch(ClientHachibi4.Types.LUMIERE);
    }

    /** La Bijuu Dama du manteau : noire, aux reflets rouges qui tournent, et sa lueur. */
    private static void dama(VertexConsumer voile, VertexConsumer lueur, Matrix4f m, Vec3 c, double r, double t) {
        ClientHachibi4.boule(voile, m, c, r, 12, 18, (th, ph) -> Math.sin(ph * 4 + t * 6 + Math.cos(th) * 3) > 0.5 ? SANG : NOIR, 0.97F);
        ClientHachibi4.boule(lueur, m, c, r * 1.15, 10, 14, (th, ph) -> ROUGE, 0.4F);
    }

    /** Un tronçon de cylindre de a à b (déjà ramenés à la caméra), de rayon r. */
    private static void faisceau(VertexConsumer c, Matrix4f m, Vec3 a, Vec3 b, double r, float[] col, float alpha) {
        Vec3 d = b.subtract(a);
        if (d.lengthSqr() < 1e-8) {
            return;
        }
        d = d.normalize();
        Vec3 u = d.cross(Math.abs(d.y) > 0.9 ? new Vec3(1, 0, 0) : new Vec3(0, 1, 0)).normalize(), v = d.cross(u);
        int cotes = 8;
        for (int k = 0; k < cotes; k++) {
            double a0 = Mth.TWO_PI * k / cotes, a1 = Mth.TWO_PI * (k + 1) / cotes;
            Vec3 e0 = u.scale(Math.cos(a0) * r).add(v.scale(Math.sin(a0) * r)), e1 = u.scale(Math.cos(a1) * r).add(v.scale(Math.sin(a1) * r));
            for (Vec3 p : new Vec3[] {a.add(e0), b.add(e0), b.add(e1), a.add(e1)}) {
                c.addVertex(m, (float) p.x, (float) p.y, (float) p.z).setColor(col[0], col[1], col[2], alpha);
            }
        }
    }

    // ================================================================ l'écran : ses touches

    private static String ligne(long quand, int i, String nom, boolean[] gris, int n) {
        double reste = Manteau.RECHARGE[i] / 20.0 - secondes(quand);
        gris[n] = reste > 0;
        return reste > 0 ? String.format("%s (%.0f s)", nom, Math.ceil(reste)) : nom;
    }

    private static void ecran(GuiGraphics g) {
        Minecraft mc = Minecraft.getInstance();
        Etat e = moi();
        if (e == null || mc.options.hideGui || ClientHachibi4.enUlti(mc.player.getUUID())) {
            return;
        }
        int w = g.guiWidth(), hh = g.guiHeight(), n = 12;
        for (int i = 0; i < n; i++) {
            float k = 1 - i / (float) n;
            int c = (Math.round(0.2F * k * k * 255) << 24) | 0x9A0A04;
            int lx = w / 5 * i / n, lx1 = w / 5 * (i + 1) / n, ly = hh / 5 * i / n, ly1 = hh / 5 * (i + 1) / n;
            g.fill(lx, 0, lx1, hh, c);
            g.fill(w - lx1, 0, w - lx, hh, c);
            g.fill(0, ly, w, ly1, c);
            g.fill(0, hh - ly1, w, hh - ly, c);
        }
        boolean[] gris = new boolean[11];
        String air = Clavier.saut() + ", puis ";
        gris[8] = e.bouleFaite;
        String[][] lignes = {{Clavier.gauche(), "Bras de chakra"},
                {Clavier.droite(), ligne(e.lariat, 1, "Lariat", gris, 1)},
                {Clavier.accroupi(), ligne(e.dama, 2, "Mini Bijuu Dama ×3", gris, 2)},
                {Clavier.accroupi() + " + " + Clavier.droite(), ligne(e.toupie, 3, "Toupie des 8 queues", gris, 3)},
                {Clavier.accroupi() + " + " + Clavier.gauche(), ligne(e.plonge, 4, "Pics de chakra", gris, 4)},
                {Clavier.nom(ClientSharingan.SUSANO), ligne(e.rugir, 5, "Rugissement", gris, 5)},
                {air + Clavier.droite(), ligne(e.foreuse, 6, "Foreuse", gris, 6)},
                {air + Clavier.gauche(), ligne(e.rafale, 7, "Rafale de queues", gris, 7)},
                {Clavier.nom(GRANDE), e.charge >= 0 ? "Grande boule (charge…)" : e.bouleFaite ? "Grande boule (déjà lancée)" : "Grande boule"},
                {Clavier.nom(ClientHachibi4.ULTI), "Hachibi géant"},
                {ClientSusano.EVEIL.getTranslatedKeyMessage().getString(), "Rendormir"}};
        PanneauPouvoir.dessiner(g, mc.font, "HACHIBI 4  ·  MANTEAU DE CHAKRA", 0xFFFF5A3C, lignes, gris);
    }

    // ================================================================ chaque image du jeu : ses touches, les étincelles

    private static void envoyer(int action) {
        PacketDistributor.sendToServer(new Hachibi4.Touche(action));
    }

    private static void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            ETATS.clear();
            return;
        }
        Etat moi = moi();
        boolean geant = ClientHachibi4.enUlti(mc.player.getUUID());
        // la touche de la grande boule ; dans le géant, la même lance la pluie de Bijuu Dama
        while (GRANDE.consumeClick()) {
            if (mc.screen == null && geant) {
                envoyer(Hachibi4.PLUIE_T);
            } else if (mc.screen == null && moi != null) {
                envoyer(Manteau.BOULE);
            }
        }
        boolean accroupi = mc.options.keyShift.isDown(), technique = ClientSharingan.SUSANO.isDown();
        if (moi != null && !geant && mc.screen == null) {
            // s'accroupir (un appui court, sans clic entre-temps) : les trois petites Bijuu Dama
            if (accroupi && !accroupiAvant) {
                accroupiDebut = System.nanoTime();
                clicFait = false;
            }
            if (!accroupi && accroupiAvant && !clicFait && System.nanoTime() - accroupiDebut < 700_000_000L) {
                envoyer(Manteau.DAMA);
            }
            if (technique && !techniqueAvant) {
                envoyer(Manteau.RUGIR);
            }
        }
        accroupiAvant = accroupi;
        techniqueAvant = technique;
        ETATS.entrySet().removeIf(en -> en.getValue().fin >= 0 && secondes(en.getValue().fin) > 0.5);
        for (Map.Entry<UUID, Etat> entree : ETATS.entrySet()) {
            Etat e = entree.getValue();
            e.balles.removeIf(b -> secondes(b.debut) > 5);
            e.boums.removeIf(b -> secondes(b.debut) > 1);
            e.pics.removeIf(p -> secondes(p.debut) > 1.1);
            Player j = mc.level.getPlayerByUUID(entree.getKey());
            if (j == null || j.isInvisible()) {
                continue;
            }
            // le chakra qui bouillonne autour de lui
            for (int i = 0; i < 2; i++) {
                if (HASARD.nextInt(3) != 0) {
                    mc.level.addParticle(new DustParticleOptions(new Vector3f(1F, 0.2F + HASARD.nextFloat() * 0.25F, 0.08F), 0.9F + HASARD.nextFloat()),
                            j.getX() + (HASARD.nextDouble() - 0.5) * 1.2, j.getY() + HASARD.nextDouble() * 2, j.getZ() + (HASARD.nextDouble() - 0.5) * 1.2, 0, 0.04, 0);
                }
            }
            if (secondes(e.lariat) < Manteau.DUREE_LARIAT / 20.0 || secondes(e.foreuse) < Manteau.DUREE_FOREUSE / 20.0) {
                for (int i = 0; i < 6; i++) {
                    mc.level.addParticle(new DustParticleOptions(new Vector3f(1F, 0.3F, 0.1F), 2F), j.getX() + (HASARD.nextDouble() - 0.5) * 0.8,
                            j.getY() + 0.3 + HASARD.nextDouble() * 1.4, j.getZ() + (HASARD.nextDouble() - 0.5) * 0.8, 0, 0, 0);
                }
            }
        }
    }

    // ================================================================ branchements

    @EventBusSubscriber(modid = Cabane.MODID, value = Dist.CLIENT)
    public static final class Touches {
        @SubscribeEvent
        public static void enregistrer(RegisterKeyMappingsEvent event) {
            event.register(GRANDE);
        }

        @SubscribeEvent
        public static void couches(RegisterGuiLayersEvent event) {
            event.registerAboveAll(Cabane.id("manteau"), (g, delta) -> ecran(g));
        }

        private Touches() {
        }
    }

    @EventBusSubscriber(modid = Cabane.MODID, value = Dist.CLIENT)
    public static final class Evenements {
        @SubscribeEvent
        public static void tick(ClientTickEvent.Post event) {
            ClientManteau.tick();
        }

        /** Dans le manteau, les clics sont à lui (avant ceux de Hachibi 2) ; accroupi ou en l'air, ils changent d'attaque. */
        @SubscribeEvent(priority = EventPriority.HIGH)
        public static void clic(InputEvent.InteractionKeyMappingTriggered event) {
            Minecraft mc = Minecraft.getInstance();
            if (!(event.isAttack() || event.isUseItem()) || moi() == null || ClientHachibi4.enUlti(mc.player.getUUID())) {
                return;
            }
            event.setCanceled(true);
            event.setSwingHand(event.isAttack());
            if (mc.screen != null || System.nanoTime() - dernierClic < 250_000_000L) {
                return;
            }
            dernierClic = System.nanoTime();
            boolean accroupi = mc.options.keyShift.isDown(), air = !mc.player.onGround();
            if (accroupi) {
                clicFait = true;
            }
            if (event.isAttack()) {
                envoyer(accroupi ? Manteau.PICS : air ? Manteau.RAFALE : Manteau.BRAS);
            } else {
                envoyer(accroupi ? Manteau.TOUPIE : air ? Manteau.FOREUSE : Manteau.LARIAT);
            }
        }

        @SubscribeEvent
        public static void dessiner(RenderLevelStageEvent event) {
            if (ETATS.isEmpty() || event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES || Minecraft.getInstance().level == null) {
                return;
            }
            ClientManteau.dessiner(event.getPoseStack(), event.getCamera().getPosition(), event.getPartialTick().getGameTimeDeltaPartialTick(false));
        }

        private Evenements() {
        }
    }

    private ClientManteau() {
    }
}
