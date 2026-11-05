package fr.cabane.client.pouvoir;

import com.mojang.blaze3d.vertex.VertexConsumer;
import fr.cabane.Cabane;
import fr.cabane.pouvoir.Orage;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.RenderPlayerEvent;
import org.joml.Matrix4f;

/**
 * L'orage, l'ultime de la Foudre 3 (voir {@link Orage} et docs/foudre3/apercu-ulti.mp4), dessiné en jeu au fil du temps :
 * la zone électrique au sol (un anneau qui s'étend, des fissures qui brillent), le nuage d'orage géant qui apparaît d'un
 * coup très haut au-dessus, les molécules d'orage (des boules de foudre liées par de petits éclairs) qui montent de la zone
 * jusqu'au nuage, les paralysés qui crépitent, son saut dans le nuage (il y disparaît), l'orage qui se charge, puis la
 * chute : la colonne de foudre énorme, une foudre sur chacun, la pluie d'éclairs, le dôme, l'onde, le flash. Tout se
 * dessine dans les deux passes de {@link ClientFoudre} (le translucide, puis la lumière).
 */
public final class ClientOrage {
    private static final RandomSource HASARD = RandomSource.create();
    private static final double FIGE = Orage.FIGE / 20.0, SAUT = Orage.SAUT / 20.0, DANS = Orage.DANS_LE_NUAGE / 20.0;
    private static final double CHUTE = Orage.CHUTE / 20.0, IMPACT = Orage.IMPACT / 20.0;
    /** L'orage gronde encore longtemps après l'atterrissage, puis le nuage se défait en 3 s. */
    private static final double APRES = Orage.APRES / 20.0, FIN = IMPACT + APRES + 3;
    private static final double R = Orage.RAYON;
    private static final float[] NUIT = {0.12F, 0.12F, 0.17F};
    private static final float[] GRIS = {0.26F, 0.27F, 0.34F};
    private static final float[] ALLUME = {0.62F, 0.8F, 1F};
    /** Le bleu profond de la zone électrique au sol (celui de la vidéo). */
    private static final float[] BLEU_ZONE = {0.1F, 0.36F, 0.95F};

    /** Un orage : où (le milieu de la zone, au sol), la hauteur du nuage, depuis quand, qui est figé. */
    private static final class EnCours {
        Vec3 centre;
        double haut;
        /** Le temps du monde au début : l'orage suit les ticks du serveur, même quand il rame. */
        long debut;
        long annule = -1;
        final List<Integer> figes = new ArrayList<>();
        boolean impact;
    }

    private static final Map<UUID, EnCours> ORAGES = new HashMap<>();
    /** Le flash blanc de l'impact, et la teinte bleue quand on est dans la zone (pour soi). */
    private static long flash = -1;

    private static double secondes(long depuis) {
        return (System.nanoTime() - depuis) / 1e9;
    }

/** Depuis combien de secondes cet orage a commencé, au temps du monde (partiel : la fraction de tick). */
    private static double temps(EnCours o, float partiel) {
        Minecraft mc = Minecraft.getInstance();
        return mc.level == null ? 0 : (mc.level.getGameTime() - o.debut + partiel) / 20.0;
    }

    private static double lisse(double x) {
        return ClientFoudre.lisse(x);
    }

    static boolean actif() {
        return !ORAGES.isEmpty();
    }

    // ================================================================ ce que dit le serveur

    public static void recevoir(Orage.Etat p) {
        EnCours o = ORAGES.get(p.joueur());
        switch (p.phase()) {
            case Orage.DEBUT -> {
                o = new EnCours();
                o.centre = new Vec3(p.centre().x(), p.centre().y(), p.centre().z());
                o.haut = p.haut();
                o.debut = Minecraft.getInstance().level == null ? 0 : Minecraft.getInstance().level.getGameTime();
                ORAGES.put(p.joueur(), o);
            }
            case Orage.FIGES -> {
                if (o != null) {
                    o.figes.clear();
                    o.figes.addAll(p.figes());
                }
            }
            default -> {
                if (o != null && !o.impact) {
                    o.annule = System.nanoTime();
                }
            }
        }
    }

    // ================================================================ dessiner

    /** Un pavé translucide (un bloc du nuage), centré en p, de côté s ; seulement dans la passe du translucide. */
    private static void pave(VertexConsumer c, Matrix4f m, Vec3 p, double s, float[] col, float a) {
        if (ClientFoudre.passe != 0) {
            return;
        }
        double h = s / 2;
        double x0 = p.x - h, x1 = p.x + h, y0 = p.y - h * 0.6, y1 = p.y + h * 0.6, z0 = p.z - h, z1 = p.z + h;
        double[][] f = {
                {x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1}, {x0, y0, z0, x0, y1, z0, x1, y1, z0, x1, y0, z0},
                {x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0}, {x1, y0, z0, x1, y1, z0, x1, y1, z1, x1, y0, z1},
                {x0, y1, z0, x0, y1, z1, x1, y1, z1, x1, y1, z0}, {x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1},
        };
        float[] dessous = {col[0] * 0.75F, col[1] * 0.75F, col[2] * 0.8F};
        for (int i = 0; i < f.length; i++) {
            float[] cc = i == 5 ? dessous : col;
            for (int k = 0; k < 4; k++) {
                ClientFoudre.sommet(c, m, new Vec3(f[i][k * 3], f[i][k * 3 + 1], f[i][k * 3 + 2]), cc, a);
            }
        }
    }

    /** Un point de lumière face à l'œil (un atome des molécules) : un cœur blanc, puis un halo de couleur. */
    private static void point(VertexConsumer c, Matrix4f m, Vec3 p, double s, float a) {
        Vec3 vers = p.normalize();
        Vec3 x = vers.cross(new Vec3(0, 1, 0));
        if (x.lengthSqr() < 1e-6) {
            x = new Vec3(1, 0, 0);
        }
        x = x.normalize();
        Vec3 y = x.cross(vers).normalize();
        boolean coeur = ClientFoudre.passe == 0;
        double t = coeur ? s : s * 1.8;
        float[] col = coeur ? ClientFoudre.BLANC : ClientFoudre.CIEL;
        float al = coeur ? a : a * 0.35F;
        ClientFoudre.sommet(c, m, p.add(x.scale(-t)).add(y.scale(-t)), col, al);
        ClientFoudre.sommet(c, m, p.add(x.scale(t)).add(y.scale(-t)), col, coeur ? al : 0);
        ClientFoudre.sommet(c, m, p.add(x.scale(t)).add(y.scale(t)), col, al);
        ClientFoudre.sommet(c, m, p.add(x.scale(-t)).add(y.scale(t)), col, coeur ? al : 0);
    }

    /** Un disque translucide au sol (la zone), de rayon r. */
    private static void disque(VertexConsumer c, Matrix4f m, Vec3 centre, double r, float[] col, float a) {
        if (ClientFoudre.passe != 0) {
            return;
        }
        int n = 48;
        for (int i = 0; i < n; i++) {
            double t0 = i * Math.PI * 2 / n, t1 = (i + 1) * Math.PI * 2 / n;
            ClientFoudre.sommet(c, m, centre, col, a);
            ClientFoudre.sommet(c, m, centre.add(Math.cos(t0) * r, 0, Math.sin(t0) * r), col, a * 0.6F);
            ClientFoudre.sommet(c, m, centre.add(Math.cos(t1) * r, 0, Math.sin(t1) * r), col, a * 0.6F);
            ClientFoudre.sommet(c, m, centre, col, a);
        }
    }

    /** Une colonne translucide (l'aura qui monte d'un paralysé, la lumière de la colonne de foudre). */
    private static void colonne(VertexConsumer c, Matrix4f m, Vec3 pied, double r, double h, float[] col, float a) {
        if (ClientFoudre.passe != 0) {
            return;
        }
        int n = 12;
        for (int i = 0; i < n; i++) {
            double t0 = i * Math.PI * 2 / n, t1 = (i + 1) * Math.PI * 2 / n;
            Vec3 a0 = pied.add(Math.cos(t0) * r, 0, Math.sin(t0) * r), a1 = pied.add(Math.cos(t1) * r, 0, Math.sin(t1) * r);
            ClientFoudre.sommet(c, m, a0, col, a);
            ClientFoudre.sommet(c, m, a1, col, a);
            ClientFoudre.sommet(c, m, a1.add(0, h, 0), col, 0);
            ClientFoudre.sommet(c, m, a0.add(0, h, 0), col, 0);
        }
    }

    /** Appelé par ClientFoudre, dans chacune de ses deux passes. */
    static void dessiner(VertexConsumer c, Matrix4f m, Vec3 cam, float partiel) {
        Minecraft mc = Minecraft.getInstance();
        for (Map.Entry<UUID, EnCours> en : ORAGES.entrySet()) {
            EnCours o = en.getValue();
            double t = temps(o, partiel);
            float fin = o.annule >= 0 ? (float) Math.max(0, 1 - secondes(o.annule) / 0.6) : 1;
            if (fin <= 0) {
                continue;
            }
            long graine = en.getKey().getLeastSignificantBits();
            long tranche = (long) (t * 12);
            Vec3 C = o.centre.subtract(cam);
            double apres = t - IMPACT;

            // la zone : un disque bleuté, l'anneau qui s'étend, les fissures en étoile et deux anneaux cassés
            float az = fin * (float) (apres < 0 ? 1 : apres < APRES ? Math.max(0.35, 1 - apres / 2.5) : Math.max(0, 0.35 * (1 - (apres - APRES) / 2)));
            if (az > 0) {
                double r = R * lisse(t / 0.5);
                Vec3 sol = C.add(0, 0.06, 0);
                disque(c, m, sol, r, BLEU_ZONE, 0.72F * az);
                disque(c, m, sol.add(0, 0.01, 0), r * 0.97, ClientFoudre.CIEL, 0.18F * az);
                ClientFoudre.anneau(c, m, sol, r - 1.2, 2.6, ClientFoudre.BLANC, az);
                ClientFoudre.anneau(c, m, sol, r - 0.3, 1.2, ClientFoudre.CIEL, az);
                ClientFoudre.anneau(c, m, sol, r * 0.62, 0.9, ClientFoudre.CIEL, 0.85F * az);
                ClientFoudre.anneau(c, m, sol, r * 0.32, 0.7, ClientFoudre.CIEL, 0.75F * az);
                Random rnd = new Random(graine);
                for (int i = 0; i < 22; i++) {
                    double a = i * Math.PI * 2 / 22 + rnd.nextDouble() * 0.25;
                    Vec3 bout = sol.add(Math.cos(a) * r, 0.02, Math.sin(a) * r);
                    ClientFoudre.eclair(c, m, sol.add(Math.cos(a) * 1.2, 0.02, Math.sin(a) * 1.2), bout, graine + i * 31L + tranche / 2, 0.13, az, 3, ClientFoudre.CIEL);
                }
                // des fissures en travers, d'une étoile à l'autre (le quadrillage cassé de la vidéo)
                for (int i = 0; i < 16; i++) {
                    double a = rnd.nextDouble() * Math.PI * 2, d = r * (0.2 + 0.7 * rnd.nextDouble()), b = a + 0.25 + 0.3 * rnd.nextDouble();
                    ClientFoudre.eclair(c, m, sol.add(Math.cos(a) * d, 0.02, Math.sin(a) * d), sol.add(Math.cos(b) * d, 0.02, Math.sin(b) * d), graine + 900 + i * 7L + tranche / 3, 0.09, 0.8F * az, 1, ClientFoudre.CIEL);
                }
            }

            // le nuage : il apparaît d'un coup, très haut, et s'allume de l'intérieur quand l'orage se charge
            double kn = lisse(t / 0.4);
            float an = fin * (float) (apres < APRES ? 1 : Math.max(0, 1 - (apres - APRES) / 3));
            if (an > 0) {
                float charge = (float) Mth.clamp((t - DANS) / (IMPACT - DANS), 0, 1);
                if (apres > 0) {
                    // il reste chargé, et s'allume d'un coup à chaque éclair qui part
                    float eclaire = new Random(graine + (long) (t * 7)).nextInt(3) == 0 ? 1 : 0;
                    charge = (float) Math.max(Math.max(0, 1 - apres / 1.5), apres < APRES ? 0.3 + 0.7 * eclaire : 0);
                }
                Vec3 ciel = C.add(0, o.haut + 8, 0);
                Random rnd = new Random(graine * 3 + 1);
                List<Vec3> blocs = new ArrayList<>();
                for (int i = 0; i < 130; i++) {
                    double a = rnd.nextDouble() * Math.PI * 2, d = Math.sqrt(rnd.nextDouble());
                    Vec3 p = ciel.add(Math.cos(a) * d * 62 * kn, (rnd.nextDouble() - 0.5) * 10 * (1.2 - d), Math.sin(a) * d * 48 * kn);
                    double s = (5 + 11 * rnd.nextDouble()) * (1.15 - 0.5 * d) * kn;
                    float mel = rnd.nextFloat();
                    float[] col = new float[3];
                    for (int k = 0; k < 3; k++) {
                        float sombre = NUIT[k] + (GRIS[k] - NUIT[k]) * mel;
                        col[k] = sombre + (ALLUME[k] - sombre) * charge * (0.4F + 0.6F * mel);
                    }
                    pave(c, m, p, s, col, 0.94F * an);
                    blocs.add(p.add(0, -s * 0.3, 0));
                }
                // des éclairs qui courent entre les blocs, et sous le nuage
                int n = 8 + (int) (charge * 18);
                Random rr = new Random(graine + tranche * 7);
                for (int i = 0; i < n; i++) {
                    Vec3 a = blocs.get(rr.nextInt(blocs.size())), b = blocs.get(rr.nextInt(blocs.size()));
                    if (a.distanceTo(b) > 8) {
                        ClientFoudre.eclair(c, m, a, b, rr.nextLong(), 0.35 + 0.35 * charge, an, 2, ClientFoudre.CIEL);
                    }
                }
            }

            // les molécules d'orage : elles montent de la zone au nuage en s'écartant ; l'impact les souffle au loin
            float am = fin * (float) Mth.clamp((t - 0.3) / 0.6, 0, 1) * (float) (apres < 0.8 ? 1 : Math.max(0, 1 - (apres - 0.8) / 1.5));
            if (apres > 2 && apres < APRES + 2) {
                // pendant que l'orage gronde : elles remontent de la zone brûlée vers le nuage
                float ar = fin * (float) Math.min(lisse((apres - 2) / 1.5), 1 - lisse((apres - APRES) / 2));
                Random rnd = new Random(graine * 9 + 4);
                for (int i = 0; i < 90; i++) {
                    double vit = (0.2 + 0.3 * rnd.nextDouble()) * o.haut, phase = rnd.nextDouble(), r0 = R * 1.4 * Math.sqrt(rnd.nextDouble());
                    double ang = rnd.nextDouble() * Math.PI * 2, tour = 0.3 + 0.5 * rnd.nextDouble(), taille = 0.3 + 0.3 * rnd.nextDouble();
                    double y = (apres * vit + phase * o.haut) % o.haut, d = r0 * (1 + 1.6 * y / o.haut), a = ang + t * tour;
                    Vec3 p = C.add(Math.cos(a) * d, y, Math.sin(a) * d);
                    point(c, m, p, taille, ar);
                    Vec3 q2 = p.add(Math.cos(a * 3) * taille * 4, taille * 2, Math.sin(a * 3) * taille * 4);
                    point(c, m, q2, taille * 0.7, ar);
                    ClientFoudre.trait(c, m, ClientFoudre.zigzag(p, q2, graine + i * 17L + tranche, 0.3, 2), 0.025, ar, ClientFoudre.CIEL);
                }
            }
            if (am > 0) {
                Random rnd = new Random(graine * 5 + 2);
                int n = 150;
                for (int i = 0; i < n; i++) {
                    double vit = (0.25 + 0.35 * rnd.nextDouble()) * o.haut, phase = rnd.nextDouble(), r0 = R * Math.sqrt(rnd.nextDouble());
                    double ang = rnd.nextDouble() * Math.PI * 2, tour = 0.4 + 0.6 * rnd.nextDouble(), taille = 0.25 + 0.3 * rnd.nextDouble();
                    int atomes = 2 + rnd.nextInt(3);
                    double[] liens = new double[atomes * 2];
                    for (int k = 0; k < liens.length; k++) {
                        liens[k] = rnd.nextDouble();
                    }
                    double y, d;
                    if (apres < 0) {
                        y = (t * vit + phase * o.haut) % o.haut;
                        d = r0 * (1 + 2.2 * y / o.haut);
                    } else {
                        double y0 = (IMPACT * vit + phase * o.haut) % o.haut;
                        y = Math.max(1, y0 - apres * 20);
                        d = r0 * (1 + 2.2 * y0 / o.haut) + apres * (30 + 40 * phase);
                    }
                    double a = ang + t * tour;
                    Vec3 p = C.add(Math.cos(a) * d, y, Math.sin(a) * d);
                    point(c, m, p, taille, am);
                    for (int k = 1; k < atomes; k++) {
                        double b = liens[k * 2] * Math.PI * 2 + t * 3, e = (liens[k * 2 + 1] - 0.5) * 2;
                        Vec3 q = p.add(Math.cos(b) * taille * 4, e * taille * 3, Math.sin(b) * taille * 4);
                        point(c, m, q, taille * 0.7, am);
                        ClientFoudre.trait(c, m, ClientFoudre.zigzag(p, q, graine + i * 13L + k + tranche, 0.3, 2), 0.025, am, ClientFoudre.CIEL);
                    }
                }
            }

            // la poussière d'orage : des centaines de petits points bleus qui montent de toute la zone vers le ciel
            float ap = fin * (float) Mth.clamp((t - 0.2) / 0.5, 0, 1) * (float) (apres < APRES ? 1 : Math.max(0, 1 - (apres - APRES) / 2.5));
            if (ap > 0) {
                Random rp = new Random(graine * 11 + 6);
                int n = apres > 0 && apres < 2 ? 700 : 500;
                for (int i = 0; i < n; i++) {
                    double vit = 4 + 14 * rp.nextDouble(), haut = 25 + 60 * rp.nextDouble(), phase = rp.nextDouble();
                    double ang = rp.nextDouble() * Math.PI * 2, d = R * 1.05 * Math.sqrt(rp.nextDouble()), tour = (rp.nextDouble() - 0.5) * 0.6;
                    double y = (t * vit + phase * haut) % haut;
                    // à l'impact, elles giclent plus haut et plus vite
                    if (apres > 0 && apres < 2) {
                        y = (y + apres * 20) % (haut * 1.6);
                    }
                    double a = ang + t * tour, dd = d * (1 + 0.25 * y / haut);
                    float fondu = (float) Math.min(1, Math.min(y / 3, (haut - y) / 6 + 0.2));
                    point(c, m, C.add(Math.cos(a) * dd, y, Math.sin(a) * dd), 0.09 + 0.12 * rp.nextDouble(), ap * Math.max(0, fondu));
                }
            }

            // les paralysés : ils crépitent, une colonne d'aura monte d'eux ; à l'impact, une foudre tombe sur chacun
            for (int id : o.figes) {
                Entity e = mc.level == null ? null : mc.level.getEntity(id);
                if (e == null) {
                    continue;
                }
                Vec3 pieds = e.getPosition(partiel).subtract(cam);
                if (apres < 0) {
                    ClientFoudre.crepite(c, m, pieds, e.getBbWidth() * 0.7 + 0.1, e.getBbHeight(), 10, 4, 3, id * 17L, 0.035, fin);
                    colonne(c, m, pieds, 1.1, 30 * lisse((t - FIGE) / 0.6), ClientFoudre.CIEL, 0.12F * fin);
                    ClientFoudre.eclair(c, m, pieds.add(0, e.getBbHeight(), 0), pieds.add(0, e.getBbHeight() + 6 + 4 * Math.sin(t * 9 + id), 0), id * 7L + tranche, 0.05, 0.7F * fin, 1, ClientFoudre.CIEL);
                } else if (apres < 0.6) {
                    ClientFoudre.eclair(c, m, pieds.add(4, o.haut, -3), pieds.add(0, 1, 0), id * 11L + tranche, 0.35, (float) (1 - apres / 0.6), 6, ClientFoudre.CIEL);
                }
            }

            // en garde, avant le saut : de gros éclairs qui montent de lui et courent au sol, l'aura qui grossit
            Entity lanceur = mc.level == null ? null : mc.level.getPlayerByUUID(en.getKey());
            if (t < SAUT && lanceur != null) {
                Vec3 pieds = lanceur.getPosition(partiel).subtract(cam);
                float k = (float) lisse(t / 0.3);
                ClientFoudre.crepite(c, m, pieds, 0.6, 1.4, 16, 8, 10, graine + 5, 0.05, k);
                colonne(c, m, pieds, 0.9 + 0.6 * k, 6 + 10 * t, ClientFoudre.CIEL, 0.35F * k);
                Random rr = new Random(graine + tranche * 3);
                for (int i = 0; i < 6; i++) {
                    double a = rr.nextDouble() * Math.PI * 2;
                    ClientFoudre.eclair(c, m, pieds.add(0, 0.6, 0), pieds.add(Math.cos(a) * 2.5, 6 + rr.nextDouble() * 8, Math.sin(a) * 2.5), rr.nextLong(), 0.09, k, 3, ClientFoudre.CIEL);
                    ClientFoudre.eclair(c, m, pieds.add(0, 0.05, 0), pieds.add(Math.cos(a + 1) * (4 + 4 * k), 0.05, Math.sin(a + 1) * (4 + 4 * k)), rr.nextLong(), 0.06, k, 2, ClientFoudre.CIEL);
                }
                ClientFoudre.anneau(c, m, pieds.add(0, 0.05, 0), 1 + 3 * t, 0.8, ClientFoudre.BLANC, k);
            }

            // le saut : il file dans le nuage, un gros éclair derrière lui
            if (t >= SAUT && t < DANS + 0.3) {
                double u = Mth.clamp((t - SAUT) / (DANS - SAUT), 0, 1);
                float a = fin * (float) (t < DANS ? 1 : 1 - (t - DANS) / 0.3);
                ClientFoudre.eclair(c, m, C.add(0, 0.2, 0), C.add(0, Math.max(3, o.haut * u * u), 0), graine + tranche, 0.25 + 0.3 * u, a, 8, ClientFoudre.CIEL);
            }

            // la chute : la colonne du gros orage, la lumière autour, la pluie d'éclairs, le dôme, l'onde
            if (t >= CHUTE && apres < 2.4) {
                float a = fin * (float) (apres < 0.9 ? 1 : Math.max(0, 1 - (apres - 0.9) / 1.5));
                double k = lisse((t - CHUTE) / 0.25);
                // l'éclair du milieu : énorme (plusieurs blocs de large), avec un cœur blanc encore plus gros
                ClientFoudre.eclair(c, m, C.add(0, o.haut + 6, 0), C, graine * 31 + tranche, 7 * k, a, 26, ClientFoudre.CIEL);
                ClientFoudre.eclair(c, m, C.add(3, o.haut, -2), C.add(0.5, 0, 0.3), graine * 37 + tranche, 4 * k, a, 12, ClientFoudre.BLANC);
                for (int i = 0; i < 12; i++) {
                    double ang = i * Math.PI * 2 / 12, d = (6 + 6 * (i % 3)) * k;
                    ClientFoudre.eclair(c, m, C.add(Math.cos(ang) * d * 2, o.haut, Math.sin(ang) * d * 2), C.add(Math.cos(ang) * d * 0.3, 0, Math.sin(ang) * d * 0.3), graine * 31 + i * 3 + tranche, 1.1, a, 6, ClientFoudre.CIEL);
                }
                colonne(c, m, C, 16 * k, o.haut, ClientFoudre.BLANC, 0.42F * a);
                colonne(c, m, C, 28 * k, o.haut * 0.7, ClientFoudre.CIEL, 0.22F * a);
                if (apres >= 0) {
                    Random rr = new Random(graine + (long) (t * 9));
                    for (int i = 0; i < 10; i++) {
                        double ang = rr.nextDouble() * Math.PI * 2, d = R * 2.5 * Math.sqrt(rr.nextDouble());
                        Vec3 p = C.add(Math.cos(ang) * d, 0, Math.sin(ang) * d);
                        ClientFoudre.eclair(c, m, p.add(8, o.haut, -4), p, rr.nextLong(), 0.5 + rr.nextDouble() * 0.5, a, 4, ClientFoudre.CIEL);
                    }
                }
            }
            if (apres >= 1.4 && apres < APRES + 1) {
                // l'orage gronde : par tranches de 3 ticks, un à trois éclairs claquent quelque part autour de la zone
                long creneau = (long) (apres * 20 / 3);
                double dans = apres * 20 / 3 - creneau;
                float a = fin * (float) (1 - dans) * (float) (apres < APRES ? 1 : 1 - (apres - APRES));
                Random rr = new Random(graine * 13 + creneau);
                int n = 1 + rr.nextInt(3);
                for (int i = 0; i < n; i++) {
                    double ang = rr.nextDouble() * Math.PI * 2, d = R * 2.6 * Math.sqrt(rr.nextDouble());
                    Vec3 p = C.add(Math.cos(ang) * d, 0, Math.sin(ang) * d);
                    double large = rr.nextInt(6) == 0 ? 1.4 : 0.4 + rr.nextDouble() * 0.5;
                    ClientFoudre.eclair(c, m, p.add(rr.nextGaussian() * 20, o.haut, rr.nextGaussian() * 20), p, rr.nextLong(), large, a, 5, ClientFoudre.CIEL);
                    ClientFoudre.anneau(c, m, p.add(0, 0.08, 0), 1 + 4 * dans, 0.8, ClientFoudre.BLANC, a * 0.7F);
                }
                // et des éclairs qui courent sous le nuage, d'un bout à l'autre
                for (int i = 0; i < 4; i++) {
                    double a1 = rr.nextDouble() * Math.PI * 2, a2 = a1 + 1 + rr.nextDouble() * 2;
                    ClientFoudre.eclair(c, m, C.add(Math.cos(a1) * 50, o.haut - 2, Math.sin(a1) * 40), C.add(Math.cos(a2) * 50, o.haut - 4, Math.sin(a2) * 40), rr.nextLong(), 0.5, a, 4, ClientFoudre.CIEL);
                }
            }
            if (apres >= 0 && apres < 2.5) {
                float a = fin * (float) Math.max(0, 1 - apres / 2.5);
                ClientFoudre.dome(c, m, C, 4 + 28 * lisse(apres / 0.8), a);
                ClientFoudre.anneau(c, m, C.add(0, 0.1, 0), 30 + 70 * lisse(apres / 1.6), 4, ClientFoudre.BLANC, a);
            }
        }
    }

    // ================================================================ la pose : à genoux, une main au sol, avant le saut

    /**
     * La pose du super-héros (l'image de l'utilisateur) : tout bas, la jambe gauche tendue loin sur le côté, la droite
     * pliée sous lui, la main droite posée au sol devant, le bras gauche levé loin en arrière, la tête vers l'avant. Il la
     * prend pendant la seconde avant le saut (il prend son impulsion), la quitte d'un coup en sautant, et retombe dedans à
     * l'atterrissage (PlayerModelMixin, après tout le reste).
     */
    public static void poser(net.minecraft.client.model.PlayerModel<?> modele, net.minecraft.world.entity.player.Player joueur) {
        EnCours o = ORAGES.get(joueur.getUUID());
        if (o == null || o.annule >= 0) {
            return;
        }
        double t = temps(o, Minecraft.getInstance().getTimer().getGameTimeDeltaPartialTick(false));
        float k;
        if (t < SAUT) {
            k = (float) lisse(t / 0.25);
        } else if (t < SAUT + 0.1) {
            k = (float) (1 - (t - SAUT) / 0.1);
        } else if (t >= IMPACT - 0.05 && t < IMPACT + 1.5) {
            k = (float) (t < IMPACT + 1.2 ? 1 : 1 - lisse((t - IMPACT - 1.2) / 0.3));
        } else {
            return;
        }
        float bas = 8 * k;
        modele.head.y += bas;
        modele.body.y += bas;
        modele.rightArm.y += bas + 3 * k;
        modele.leftArm.y += bas;
        modele.rightLeg.y += bas;
        modele.leftLeg.y += bas;
        // la jambe gauche tendue loin sur le côté, la droite pliée devant sous lui
        modele.leftLeg.xRot = Mth.lerp(k, modele.leftLeg.xRot, 0.15F);
        modele.leftLeg.zRot = Mth.lerp(k, modele.leftLeg.zRot, -1.25F);
        modele.rightLeg.xRot = Mth.lerp(k, modele.rightLeg.xRot, -1.2F);
        modele.rightLeg.zRot = Mth.lerp(k, modele.rightLeg.zRot, 0.25F);
        // la main droite au sol, devant, entre les jambes ; le bras gauche levé loin en arrière
        modele.rightArm.xRot = Mth.lerp(k, modele.rightArm.xRot, -0.3F);
        modele.rightArm.zRot = Mth.lerp(k, modele.rightArm.zRot, -0.2F);
        modele.leftArm.xRot = Mth.lerp(k, modele.leftArm.xRot, 2.3F);
        modele.leftArm.zRot = Mth.lerp(k, modele.leftArm.zRot, -0.55F);
        modele.head.xRot = Mth.lerp(k, modele.head.xRot, -0.15F);
        modele.hat.copyFrom(modele.head);
        modele.jacket.copyFrom(modele.body);
        modele.rightSleeve.copyFrom(modele.rightArm);
        modele.leftSleeve.copyFrom(modele.leftArm);
        modele.rightPants.copyFrom(modele.rightLeg);
        modele.leftPants.copyFrom(modele.leftLeg);
    }

    // ================================================================ l'écran : la teinte de la zone, le flash

    private static void ecran(GuiGraphics g) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return;
        }
        for (EnCours o : ORAGES.values()) {
            double t = temps(o, 0);
            if (o.annule < 0 && t < IMPACT && mc.player.position().distanceTo(o.centre) < R + 4) {
                int a = (int) (60 * lisse(t / 0.5));
                g.fill(0, 0, g.guiWidth(), g.guiHeight(), a << 24 | 0x40B8FF);
            }
        }
        if (flash >= 0) {
            double t = secondes(flash);
            if (t > 0.9) {
                flash = -1;
                return;
            }
            int a = (int) (235 * (1 - t / 0.9));
            g.fill(0, 0, g.guiWidth(), g.guiHeight(), a << 24 | 0xEAF6FF);
        }
    }

    // ================================================================ branchements

    @EventBusSubscriber(modid = Cabane.MODID, value = Dist.CLIENT)
    public static final class Evenements {
        @SubscribeEvent
        public static void enregistrer(RegisterGuiLayersEvent event) {
            event.registerAboveAll(Cabane.id("foudre_orage"), (g, delta) -> ecran(g));
        }

        @SubscribeEvent
        public static void tick(ClientTickEvent.Post event) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.level == null || mc.player == null) {
                ORAGES.clear();
                return;
            }
            for (EnCours o : ORAGES.values()) {
                double t = temps(o, 0);
                if (o.annule >= 0) {
                    continue;
                }
                // la zone fume de particules bleues qui montent, pendant tout l'orage
                if (t > 0.2 && t < IMPACT + APRES && mc.player.position().distanceTo(o.centre) < 90) {
                    double r = R * Math.min(1, t / 0.5);
                    for (int i = 0; i < 40; i++) {
                        double a = HASARD.nextDouble() * Math.PI * 2, d = r * Math.sqrt(HASARD.nextDouble());
                        double x = o.centre.x + Math.cos(a) * d, z = o.centre.z + Math.sin(a) * d;
                        mc.level.addParticle(i % 3 == 0 ? ParticleTypes.SOUL_FIRE_FLAME : i % 3 == 1 ? ParticleTypes.ELECTRIC_SPARK : ParticleTypes.GLOW,
                                x, o.centre.y + 0.2 + HASARD.nextDouble() * 2, z, HASARD.nextGaussian() * 0.02, 0.15 + HASARD.nextDouble() * 0.45, HASARD.nextGaussian() * 0.02);
                    }
                }
                // des étincelles qui montent des paralysés
                if (t < IMPACT) {
                    for (int id : o.figes) {
                        Entity e = mc.level.getEntity(id);
                        if (e != null) {
                            mc.level.addParticle(ParticleTypes.ELECTRIC_SPARK, e.getX() + HASARD.nextGaussian() * 0.4, e.getY() + HASARD.nextDouble() * e.getBbHeight(),
                                    e.getZ() + HASARD.nextGaussian() * 0.4, 0, 0.3 + HASARD.nextDouble() * 0.3, 0);
                        }
                    }
                }
                if (!o.impact && t >= IMPACT) {
                    o.impact = true;
                    double loin = mc.player.position().distanceTo(o.centre);
                    if (loin < 80) {
                        ClientFoudre.secouer(loin < R + 5 ? 2F : 1F);
                        flash = System.nanoTime();
                    }
                    Vec3 z = o.centre;
                    mc.level.addParticle(ParticleTypes.EXPLOSION_EMITTER, z.x, z.y + 1, z.z, 0, 0, 0);
                    for (int i = 0; i < 900; i++) {
                        mc.level.addParticle(i % 2 == 0 ? ParticleTypes.ELECTRIC_SPARK : ParticleTypes.SOUL_FIRE_FLAME, z.x + HASARD.nextGaussian() * 6, z.y + HASARD.nextDouble() * 8, z.z + HASARD.nextGaussian() * 6,
                                HASARD.nextGaussian() * 0.9, 0.5 + HASARD.nextDouble() * 1.4, HASARD.nextGaussian() * 0.9);
                    }
                    for (int i = 0; i < 60; i++) {
                        double a = HASARD.nextDouble() * Math.PI * 2;
                        mc.level.addParticle(ParticleTypes.CLOUD, z.x, z.y + 0.4, z.z, Math.cos(a) * 1.4, 0.05, Math.sin(a) * 1.4);
                    }
                    mc.level.playLocalSound(z.x, z.y, z.z, SoundEvents.LIGHTNING_BOLT_THUNDER, SoundSource.WEATHER, 6F, 0.6F, false);
                }
            }
            ORAGES.values().removeIf(o -> o.annule >= 0 && secondes(o.annule) > 0.6 || temps(o, 0) > FIN);
        }

        /** Dans le nuage, il a disparu : on ne le dessine pas. */
        @SubscribeEvent
        public static void cache(RenderPlayerEvent.Pre event) {
            EnCours o = ORAGES.get(event.getEntity().getUUID());
            if (o == null || o.annule >= 0) {
                return;
            }
            double t = temps(o, event.getPartialTick());
            if (t > DANS - 0.05 && t < CHUTE) {
                event.setCanceled(true);
            }
        }

        private Evenements() {
        }
    }

    private ClientOrage() {
    }
}
