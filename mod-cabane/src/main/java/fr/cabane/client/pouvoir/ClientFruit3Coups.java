package fr.cabane.client.pouvoir;

import static fr.cabane.client.pouvoir.ClientFruit3.BLANCHE;
import static fr.cabane.client.pouvoir.ClientFruit3.CHAUD;
import static fr.cabane.client.pouvoir.ClientFruit3.CROUTE;
import static fr.cabane.client.pouvoir.ClientFruit3.CROUTE_R;
import static fr.cabane.client.pouvoir.ClientFruit3.GLACE;
import static fr.cabane.client.pouvoir.ClientFruit3.GLACE_C;
import static fr.cabane.client.pouvoir.ClientFruit3.GLACE_P;
import static fr.cabane.client.pouvoir.ClientFruit3.LAVE_J;
import static fr.cabane.client.pouvoir.ClientFruit3.LAVE_O;
import static fr.cabane.client.pouvoir.ClientFruit3.LAVE_R;
import static fr.cabane.client.pouvoir.ClientFruit3.aleat;
import static fr.cabane.client.pouvoir.ClientFruit3.avec;
import static fr.cabane.client.pouvoir.ClientFruit3.boite;
import static fr.cabane.client.pouvoir.ClientFruit3.croissance;
import static fr.cabane.client.pouvoir.ClientFruit3.cristal;
import static fr.cabane.client.pouvoir.ClientFruit3.cube;
import static fr.cabane.client.pouvoir.ClientFruit3.entre;
import static fr.cabane.client.pouvoir.ClientFruit3.feu;
import static fr.cabane.client.pouvoir.ClientFruit3.fissure;
import static fr.cabane.client.pouvoir.ClientFruit3.flamme;
import static fr.cabane.client.pouvoir.ClientFruit3.hache;
import static fr.cabane.client.pouvoir.ClientFruit3.mix;
import static fr.cabane.client.pouvoir.ClientFruit3.particule;
import static fr.cabane.client.pouvoir.ClientFruit3.pic;
import static fr.cabane.client.pouvoir.ClientFruit3.repere;
import static fr.cabane.client.pouvoir.ClientFruit3.vec;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import fr.cabane.Cabane;
import fr.cabane.pouvoir.Fruit3Coups;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.MovementInputUpdateEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.joml.Matrix4f;

/**
 * Les nouvelles attaques du niveau 3 des fruits chez chaque joueur (voir {@link Fruit3Coups} ;
 * docs/fruits/planche-attaques3.png) : la vague de magma et la vague gelée, le volcan et ses bombes, les partisans, la
 * comète et la glissade (c'est son jeu qui le fait avancer), le pilier de magma et les éclats du 3e coup, et le coup
 * (le poing en feu, la taillade de l'épée de glace). Tout est dessiné, rien n'est posé dans le monde.
 */
public final class ClientFruit3Coups {
    /** Un effet en cours : sa sorte, de qui, ses deux points, sa graine, depuis quand (ticks du monde) ; tFin : quand il a fini. */
    private static final class Effet {
        final UUID joueur;
        final boolean glace;
        final int sorte;
        final Vec3 a;
        final Vec3 b;
        final int graine;
        final long debut;
        double tFin = -1;
        Vec3 bout;
        /** Le sol sous chaque case (la vague gelée, le volcan), calculé une fois ; le chemin (la glissade, la comète). */
        double[] sols;
        final List<Vec3> chemin = new ArrayList<>();
        /** Les partisans : où tombe chaque lance (le serveur le dit quand elle part). */
        final Vec3[] lances = new Vec3[Fruit3Coups.LANCES];

        Effet(Fruit3Coups.Effet p, long debut) {
            this.joueur = p.joueur();
            this.glace = p.glace();
            this.sorte = p.sorte();
            this.a = vec(p.a());
            this.b = vec(p.b());
            this.graine = p.graine();
            this.debut = debut;
        }

        double t(Minecraft mc, float partiel) {
            return (mc.level.getGameTime() - debut + partiel) / 20.0;
        }
    }

    private static final List<Effet> EFFETS = new ArrayList<>();
    /** Les recharges (pour le panneau) : la zone (accroupi + clic droit) et la ruée (accroupi + clic gauche). */
    static long pretZone, pretRuee;
    /** Sa propre comète ou glissade : c'est son jeu qui le fait avancer. */
    private static Effet maRuee;

    // ================================================================ ce que dit le serveur

    public static void effet(Fruit3Coups.Effet p) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return;
        }
        long maintenant = mc.level.getGameTime();
        if (p.sorte() == Fruit3Coups.ARRIVEE) {
            Vec3 ici = vec(p.a());
            for (Effet e : EFFETS) {
                boolean vague = p.graine() < 2 && (e.sorte == Fruit3Coups.VAGUE);
                boolean ruee = p.graine() == 2 && (e.sorte == Fruit3Coups.COMETE || e.sorte == Fruit3Coups.GLISSADE);
                if (e.joueur.equals(p.joueur()) && e.tFin < 0 && (vague || ruee)) {
                    e.tFin = e.t(mc, 0);
                    e.bout = ici;
                }
            }
            if (p.graine() == 2) {
                ClientFruit3.eclater(ici.add(0, 0.6, 0), p.glace(), true, 0.55);
                ClientFruit.secouer(ici, 1F, 20);
                if (maRuee != null && maRuee.joueur.equals(p.joueur())) {
                    maRuee = null;
                }
            } else {
                ClientFruit3.eclater(ici.add(0, 1.2, 0), false, false, 0.45);
            }
            return;
        }
        if (p.sorte() == Fruit3Coups.LANCE) {
            for (int i = EFFETS.size() - 1; i >= 0; i--) {
                Effet z = EFFETS.get(i);
                if (z.sorte == Fruit3Coups.PARTISANS && z.joueur.equals(p.joueur()) && p.graine() >= 0 && p.graine() < Fruit3Coups.LANCES) {
                    z.lances[p.graine()] = vec(p.b());
                    break;
                }
            }
            return;
        }
        Effet e = new Effet(p, maintenant);
        EFFETS.add(e);
        switch (p.sorte()) {
            case Fruit3Coups.PILIER -> {
                ClientFruit.secouer(e.a, 0.8F, 14);
                for (int q = 0; q < 30; q++) {
                    particule(mc, q % 3 == 0 ? ParticleTypes.LAVA : ParticleTypes.FLAME, e.a.add(aleat(1.2), HASARD.nextDouble() * 3, aleat(1.2)), aleat(0.2), 0.3, aleat(0.2));
                }
            }
            case Fruit3Coups.ECLAT -> {
                BlockParticleOption glace = new BlockParticleOption(ParticleTypes.BLOCK, Blocks.ICE.defaultBlockState());
                for (int q = 0; q < 50; q++) {
                    particule(mc, glace, e.a.add(aleat(0.8), 0.3 + HASARD.nextDouble() * 1.8, aleat(0.8)), aleat(0.5), HASARD.nextDouble() * 0.4, aleat(0.5));
                }
                for (int q = 0; q < 24; q++) {
                    particule(mc, ParticleTypes.SNOWFLAKE, e.a.add(aleat(1.5), 0.5 + HASARD.nextDouble() * 2, aleat(1.5)), aleat(0.4), 0.1, aleat(0.4));
                }
            }
            case Fruit3Coups.COMETE, Fruit3Coups.GLISSADE -> {
                if (mc.player != null && p.joueur().equals(mc.player.getUUID())) {
                    maRuee = e;
                }
            }
            case Fruit3Coups.VOLCAN, Fruit3Coups.PARTISANS -> {
                if (mc.player != null && p.joueur().equals(mc.player.getUUID())) {
                    pretZone = System.nanoTime() + Fruit3Coups.RECHARGE_ZONE * 50_000_000L;
                }
            }
            default -> {
            }
        }
        if ((p.sorte() == Fruit3Coups.COMETE || p.sorte() == Fruit3Coups.GLISSADE) && mc.player != null && p.joueur().equals(mc.player.getUUID())) {
            pretRuee = System.nanoTime() + Fruit3Coups.RECHARGE_RUEE * 50_000_000L;
        }
    }

    /** Combien de temps dure chaque effet (en secondes), pour l'oublier ensuite. */
    private static double duree(Effet e) {
        return switch (e.sorte) {
            case Fruit3Coups.VAGUE -> e.tFin < 0 ? 4 : e.tFin + 2.6;
            case Fruit3Coups.CONE -> 3.2;
            case Fruit3Coups.VOLCAN -> Fruit3Coups.V_FIN / 20.0 + 1.2;
            case Fruit3Coups.BOMBE -> Fruit3Coups.VOL_BOMBE / 20.0 + 0.7;
            case Fruit3Coups.PARTISANS -> Fruit3Coups.P_FIN / 20.0 + 1;
            case Fruit3Coups.COMETE, Fruit3Coups.GLISSADE -> e.tFin < 0 ? 3 : e.tFin + 3;
            case Fruit3Coups.PILIER -> 1.2;
            case Fruit3Coups.ECLAT -> 1.1;
            default -> 0.45;
        };
    }

    private static final net.minecraft.util.RandomSource HASARD = net.minecraft.util.RandomSource.create();

    // ================================================================ les couleurs

    /** Le magma : croûte noire fendue de lave qui brille (« ouvert » : plus de lave). */
    private static float[] magma(double i, double j, int k, double ouvert) {
        double f = fissure(i, j, k, 3);
        if (f < 0.5 * ouvert) {
            return avec(mix(LAVE_J, LAVE_O, f / (0.5 * ouvert)), 1);
        }
        if (f < ouvert) {
            return avec(mix(LAVE_O, LAVE_R, (f - 0.5 * ouvert) / (0.5 * ouvert)), 1);
        }
        return mix(CROUTE, CROUTE_R, 0.2 + 0.5 * hache((int) i, (int) j, k + 3));
    }

    /** La glace : claire, des reflets blancs en biais, un peu plus profonde par endroits. */
    private static float[] glace(int i, int j, int k, int face) {
        if (face == 0) {
            return mix(BLANCHE, GLACE_C, 0.3 * hache(i, j, k));
        }
        int reflet = Math.floorMod(i - j + k, 5);
        return reflet == 0 ? BLANCHE : mix(mix(GLACE_C, GLACE, 0.3 + 0.5 * hache(i, j, k + 1)), GLACE_P, 0.18);
    }

    /** Une colonne posée au sol en p (son pied), de demi-largeur w et de hauteur h. */
    private static void colonne(VertexConsumer v, Matrix4f m, Vec3 cam, Vec3 p, double w, double h, ClientFruit3.Teinte t) {
        if (h < 0.03) {
            return;
        }
        boite(v, m, p.add(0, h / 2, 0).subtract(cam), new Vec3(w, 0, 0), new Vec3(0, h / 2, 0), new Vec3(0, 0, w), t, 1, 2);
    }

    // ================================================================ clic droit : la vague de magma

    /** Le sol le long de la vague, tous les demi-blocs (NaN au-delà d'un mur) ; calculé une fois. */
    private static double[] solsVague(Minecraft mc, Effet e) {
        if (e.sols == null) {
            int n = (int) (Fruit3Coups.PORTEE_VAGUE / 0.5) + 2;
            e.sols = new double[n];
            double y = e.a.y;
            for (int k = 0; k < n; k++) {
                Vec3 p = e.a.add(e.b.scale(k * 0.5));
                double s = Double.isNaN(y) ? Double.NaN : Fruit3Coups.sol(mc.level, p.x, p.z, y);
                e.sols[k] = s;
                y = s;
            }
        }
        return e.sols;
    }

    private static double solA(double[] sols, double d, double defaut) {
        int k = Mth.clamp((int) Math.round(d / 0.5), 0, sols.length - 1);
        for (; k > 0 && Double.isNaN(sols[k]); k--) {
        }
        return Double.isNaN(sols[k]) ? defaut : sols[k];
    }

    private static void vague(MultiBufferSource.BufferSource buffers, Matrix4f m, Vec3 cam, Minecraft mc, Effet e, double t) {
        double[] sols = solsVague(mc, e);
        double front = e.tFin >= 0 ? e.a.distanceTo(new Vec3(e.bout.x, e.a.y, e.bout.z)) : Fruit3Coups.frontVague(t * 20);
        if (e.tFin < 0) {
            // le client arrête aussi la vague au mur (le serveur le confirmera)
            for (int k = 0; k < sols.length && k * 0.5 <= front; k++) {
                if (Double.isNaN(sols[k])) {
                    front = Math.max(0, (k - 1) * 0.5);
                    break;
                }
            }
        }
        double taille = croissance(t, 0, 0.3) * (e.tFin < 0 ? 1 : 1 - entre(t, e.tFin, e.tFin + 0.45));
        Vec3 dir = e.b, cote = new Vec3(-dir.z, 0, dir.x);
        double L = Fruit3Coups.LARGE_VAGUE, H = Fruit3Coups.HAUT_VAGUE + 0.4;
        VertexConsumer v = buffers.getBuffer(ClientFruit3.Types.PLEIN);
        // derrière, la coulée qui refroidit (de la lave, puis de la croûte, puis plus rien)
        for (double s = 0.5; s < front - 2.6; s += 0.5) {
            double age = t - s / (Fruit3Coups.VITESSE_VAGUE * 20), reste = 1 - entre(age, 0.8, 2.4);
            if (reste <= 0) {
                continue;
            }
            double y = solA(sols, s, e.a.y);
            for (double l = -L + 0.25; l < L; l += 0.5) {
                Vec3 p = e.a.add(dir.scale(s)).add(cote.scale(l));
                double ouvert = 1.6 * (1 - entre(age, 0.2, 1.6));
                float[] c = magma(s * 4 + 300, l * 4 + 300, 620, Math.max(0.05, ouvert));
                colonne(v, m, cam, new Vec3(p.x, y, p.z), 0.26, 0.12 * reste, (f, i, j) -> c);
            }
        }
        if (taille > 0.01) {
            // la vague : des colonnes de magma qui montent jusqu'à la crête
            for (double s = Math.max(0, front - 2.75); s <= front + 0.01; s += 0.5) {
                double u = (s - (front - 2.75)) / 2.75, y = solA(sols, s, e.a.y);
                for (double l = -L + 0.25; l < L; l += 0.5) {
                    double bord = 1 - Math.pow(Math.abs(l) / (L + 0.4), 3);
                    double h = (0.35 + (H - 0.35) * Math.pow(u, 1.5)) * bord * (0.9 + 0.2 * hache((int) (s * 2), (int) (l * 2 + 20), 621)) * taille;
                    Vec3 p = e.a.add(dir.scale(s)).add(cote.scale(l));
                    int ki = (int) (s * 4), kj = (int) (l * 4 + 40);
                    colonne(v, m, cam, new Vec3(p.x, y, p.z), 0.26, h, (f, i, j) -> magma(ki * 3 + i * 2 + t * 6, kj * 3 + j * 2, 622 + f, f == 4 || f == 5 ? 1.6 : 0.8));
                }
            }
            // la crête qui s'enroule vers l'avant
            double y = solA(sols, front, e.a.y);
            for (double l = -L + 0.3; l < L; l += 0.6) {
                double bord = 1 - Math.pow(Math.abs(l) / (L + 0.4), 3), R = 0.9 * bord * taille, cy = (H - 0.6) * bord * taille;
                for (int q = 0; q < 8; q++) {
                    double th = -0.3 + q * 0.36 + 0.1 * Math.sin(t * 8 + l);
                    Vec3 p = e.a.add(dir.scale(front - 0.2 + R * Math.sin(th))).add(cote.scale(l)).add(0, y + cy + R * Math.cos(th), 0);
                    float[] c = q < 5 ? avec(mix(LAVE_J, CHAUD, hache(q, (int) (l * 3), 623)), 1) : mix(CROUTE_R, LAVE_R, 0.5);
                    cube(v, m, p.subtract(cam), (0.32 - q * 0.02) * taille, q + l, c, 1);
                }
            }
        }
        buffers.endBatch(ClientFruit3.Types.PLEIN);
        if (taille > 0.01) {
            VertexConsumer f = buffers.getBuffer(ClientFruit3.Types.VOILE);
            double y = solA(sols, front, e.a.y);
            for (double l = -L + 0.2; l < L; l += 0.4) {
                double bord = 1 - Math.pow(Math.abs(l) / (L + 0.4), 3), vie = 0.75 + 0.25 * Math.sin(t * 12 + l * 3);
                Vec3 p = e.a.add(dir.scale(front - 0.4)).add(cote.scale(l)).add(0, y + H * bord * taille, 0);
                flamme(f, m, p.subtract(cam), new Vec3(0, 1, 0).add(dir.scale(0.45)), (0.8 + 0.9 * hache((int) (l * 5), 1, 624)) * vie * taille, 0.35);
            }
            buffers.endBatch(ClientFruit3.Types.VOILE);
        }
    }

    // ================================================================ clic droit : la vague gelée

    private static final double PAS_CONE = 0.7;

    private static void cone(VertexConsumer v, Matrix4f m, Vec3 cam, Minecraft mc, Effet e, double t) {
        Vec3 dir = e.b, cote = new Vec3(-dir.z, 0, dir.x);
        double R = Fruit3Coups.PORTEE_CONE, front = t * 20 * Fruit3Coups.VITESSE_CONE, eclat = 2.5;
        int n = (int) Math.ceil(R / PAS_CONE);
        if (e.sols == null) {
            e.sols = new double[(n + 1) * (2 * n + 1)];
            for (int i = 0; i <= n; i++) {
                for (int j = -n; j <= n; j++) {
                    Vec3 p = e.a.add(dir.scale(i * PAS_CONE)).add(cote.scale(j * PAS_CONE));
                    e.sols[i * (2 * n + 1) + j + n] = Fruit3Coups.sol(mc.level, p.x, p.z, e.a.y + 0.5);
                }
            }
        }
        double fonte = 1 - entre(t, eclat, eclat + 0.3);
        if (fonte <= 0) {
            return;
        }
        double cos = Math.cos(Fruit3Coups.DEMI_ANGLE - 0.03);
        for (int i = 1; i <= n; i++) {
            for (int j = -n; j <= n; j++) {
                double le = i * PAS_CONE, la = j * PAS_CONE, r = Math.hypot(le, la);
                if (r > R || le / r < cos) {
                    continue;
                }
                double y = e.sols[i * (2 * n + 1) + j + n];
                if (Double.isNaN(y) || r > front + 0.3) {
                    continue;
                }
                double pousse = croissance(t - r / (20 * Fruit3Coups.VITESSE_CONE), 0, 0.15);
                double bord = 1 - Math.pow(Math.abs(la) / (r * Math.tan(Fruit3Coups.DEMI_ANGLE) + 0.5), 4);
                double h = (0.7 + 0.5 * hache(i, j, 630)) * entre(r, 0.5, 2.5);
                if (r > R - 4.2) {
                    h += 5 * Math.pow(entre(r, R - 4.2, R - 0.6), 1.3) * Math.max(0.25, bord);
                }
                h *= pousse * fonte;
                Vec3 p = e.a.add(dir.scale(le)).add(cote.scale(la));
                int gi = i, gj = j;
                colonne(v, m, cam, new Vec3(p.x, y, p.z), PAS_CONE * 0.53, h, (f, a, b) -> glace(gi * 3 + a, gj * 3 + b, 631, f));
                // la crête : des pics vers l'avant et vers le ciel, des stalactites dessous
                if (r > R - 1.4 && pousse > 0.5) {
                    Vec3 haut = new Vec3(p.x, y + h, p.z).subtract(cam);
                    Vec3 avant = dir.scale(le / r).add(cote.scale(la / r));
                    cristal(v, m, haut, avant.add(0, 0.9, 0), (0.8 + 1.2 * hache(i, j, 632)) * fonte, 0.4);
                    if (hache(i, j, 633) < 0.5) {
                        cristal(v, m, haut.add(avant.scale(0.4)).add(0, -0.3, 0), avant.add(0, -0.8, 0), (0.6 + 0.8 * hache(i, j, 634)) * fonte, 0.28);
                    }
                }
            }
        }
    }

    // ================================================================ accroupi + clic droit : le volcan et ses bombes

    private static final double PAS_VOLCAN = 0.6, R_VOLCAN = 4.4, H_VOLCAN = 5.2, CRATERE = 1.1;

    private static void volcan(MultiBufferSource.BufferSource buffers, Matrix4f m, Vec3 cam, Minecraft mc, Effet e, double t) {
        double ticks = t * 20;
        VertexConsumer v = buffers.getBuffer(ClientFruit3.Types.PLEIN);
        // la fissure qui file de lui jusqu'au volcan
        Vec3 de = e.b, d = e.a.subtract(de).multiply(1, 0, 1);
        double L = d.length(), arrive = Math.min(1, ticks / Fruit3Coups.V_CONE), reste = 1 - entre(t, Fruit3Coups.V_FIN / 20.0, Fruit3Coups.V_FIN / 20.0 + 1);
        if (L > 1) {
            Vec3 u = d.scale(1 / L), cote = new Vec3(-u.z, 0, u.x);
            for (double s = 1; s < L * arrive - 0.2; s += 0.35) {
                double zig = 0.3 * Math.sin(s * 1.3) + 0.18 * Math.sin(s * 3.4);
                Vec3 p = de.add(u.scale(s)).add(cote.scale(zig));
                double y = Mth.lerp(s / L, de.y, e.a.y);
                float[] c = avec(mix(LAVE_J, LAVE_O, hache((int) (s * 3), 1, 640)), 1);
                colonne(v, m, cam, new Vec3(p.x, y, p.z), 0.16, 0.06 * reste, (f, i, j) -> c);
            }
        }
        // le cône : il monte, entre en éruption, puis redescend
        double monte = croissance(ticks, Fruit3Coups.V_CONE, Fruit3Coups.V_ERUPTION) * reste;
        if (monte > 0.01) {
            if (e.sols == null) {
                int n = (int) Math.ceil(R_VOLCAN / PAS_VOLCAN);
                e.sols = new double[(2 * n + 1) * (2 * n + 1)];
                for (int i = -n; i <= n; i++) {
                    for (int j = -n; j <= n; j++) {
                        e.sols[(i + n) * (2 * n + 1) + j + n] = Fruit3Coups.sol(mc.level, e.a.x + i * PAS_VOLCAN, e.a.z + j * PAS_VOLCAN, e.a.y + 1);
                    }
                }
            }
            int n = (int) Math.ceil(R_VOLCAN / PAS_VOLCAN);
            boolean eruption = ticks >= Fruit3Coups.V_ERUPTION && ticks < Fruit3Coups.V_BOMBES + 10;
            for (int i = -n; i <= n; i++) {
                for (int j = -n; j <= n; j++) {
                    double x = i * PAS_VOLCAN, z = j * PAS_VOLCAN, r = Math.hypot(x, z);
                    if (r > R_VOLCAN) {
                        continue;
                    }
                    double y = e.sols[(i + n) * (2 * n + 1) + j + n];
                    if (Double.isNaN(y)) {
                        y = e.a.y;
                    }
                    double h = r < CRATERE ? H_VOLCAN - 0.6 : H_VOLCAN * Math.pow(1 - (r - CRATERE) / (R_VOLCAN - CRATERE), 0.9) * (0.92 + 0.16 * hache(i, j, 643));
                    h *= monte;
                    double a = Math.atan2(z, x);
                    boolean coulee = Math.abs(Math.sin(a * 3.5 + e.graine)) > 0.9 && ticks >= Fruit3Coups.V_ERUPTION;
                    int gi = i, gj = j;
                    ClientFruit3.Teinte teinte = r < CRATERE ? (f, p, q) -> avec(mix(LAVE_J, CHAUD, hache(gi + p, gj + q, 644 + (int) (t * 6))), 1)
                            : coulee ? (f, p, q) -> magma(gi * 3 + p + 300, gj * 3 + q + t * 8, 645, 1.8)
                            : (f, p, q) -> magma(gi * 3 + p + 300, gj * 3 + q + 300, 646 + f, f == 0 ? 0.5 : 0.28);
                    colonne(v, m, cam, new Vec3(e.a.x + x, y, e.a.z + z), PAS_VOLCAN * 0.53, h, teinte);
                }
            }
            buffers.endBatch(ClientFruit3.Types.PLEIN);
            if (eruption) {
                VertexConsumer f = buffers.getBuffer(ClientFruit3.Types.VOILE);
                double fort = 1 - entre(ticks, Fruit3Coups.V_BOMBES - 6, Fruit3Coups.V_BOMBES + 10);
                Vec3 haut = e.a.add(0, H_VOLCAN * monte - 0.4, 0);
                for (int q = 0; q < 18; q++) {
                    double a = q / 18.0 * Math.PI * 2, r = 0.2 + 0.7 * hache(q, 1, 647), vie = 0.7 + 0.3 * Math.sin(t * 13 + q * 1.7);
                    Vec3 p = haut.add(Math.cos(a) * r, 0, Math.sin(a) * r);
                    flamme(f, m, p.subtract(cam), new Vec3(Math.cos(a) * 0.15, 1, Math.sin(a) * 0.15), (2.2 + 3.5 * hache(q, 2, 647)) * vie * fort
                            * (1 + 0.6 * (1 - entre(ticks, Fruit3Coups.V_ERUPTION, Fruit3Coups.V_ERUPTION + 8))), 0.7);
                }
                buffers.endBatch(ClientFruit3.Types.VOILE);
            }
            return;
        }
        buffers.endBatch(ClientFruit3.Types.PLEIN);
    }

    /** Une bombe de lave : un bloc de croûte qui file en cloche du cratère jusqu'au sol, sa traînée de feu ; puis elle éclate. */
    private static void bombe(MultiBufferSource.BufferSource buffers, Matrix4f m, Vec3 cam, Effet e, double t) {
        double u = t * 20 / Fruit3Coups.VOL_BOMBE;
        if (u < 1) {
            VertexConsumer v = buffers.getBuffer(ClientFruit3.Types.PLEIN);
            Vec3 p = cloche(e.a, e.b, u);
            cube(v, m, p.subtract(cam), 0.3, t * 9 + e.graine, avec(feu(0.55 + 0.1 * Math.sin(t * 20)), 1), 1);
            cube(v, m, p.subtract(cam), 0.36, t * 5 + e.graine, CROUTE, 1);
            buffers.endBatch(ClientFruit3.Types.PLEIN);
            VertexConsumer f = buffers.getBuffer(ClientFruit3.Types.VOILE);
            Vec3 avant = cloche(e.a, e.b, Math.max(0, u - 0.12));
            Vec3 arriere = avant.subtract(p);
            if (arriere.lengthSqr() > 1e-4) {
                flamme(f, m, p.subtract(cam), arriere.normalize(), 1.2 + arriere.length(), 0.5);
            }
            buffers.endBatch(ClientFruit3.Types.VOILE);
            return;
        }
        double s = t - Fruit3Coups.VOL_BOMBE / 20.0, reste = 1 - entre(s, 0.15, 0.6);
        VertexConsumer f = buffers.getBuffer(ClientFruit3.Types.VOILE);
        for (int q = 0; q < 10; q++) {
            double a = q / 10.0 * Math.PI * 2;
            Vec3 p = e.b.add(Math.cos(a) * 0.6, 0, Math.sin(a) * 0.6);
            flamme(f, m, p.subtract(cam), new Vec3(Math.cos(a) * 0.5, 1, Math.sin(a) * 0.5), (0.6 + 0.8 * hache(q, e.graine, 648)) * reste * croissance(s, 0, 0.1), 0.35);
        }
        buffers.endBatch(ClientFruit3.Types.VOILE);
    }

    private static Vec3 cloche(Vec3 a, Vec3 b, double u) {
        return a.lerp(b, u).add(0, 4.5 * 4 * u * (1 - u) * 0.5, 0);
    }

    // ================================================================ accroupi + clic droit : les partisans

    /** Où est la lance k (sa pointe), et vers où elle pointe, à ce tick. */
    private static Vec3[] lance(Minecraft mc, Effet e, int k, double ticks) {
        double a = k / (double) Fruit3Coups.LANCES * Math.PI * 2 + e.graine * 0.1;
        Vec3 anneau = e.a.add(Math.cos(a) * 2.8, 9, Math.sin(a) * 2.8);
        double tombe = Fruit3Coups.P_DEBUT + Fruit3Coups.P_ECART * k;
        if (e.lances[k] == null) {
            // dans l'anneau, elle pointe vers le bas, un peu vers le milieu, en attendant de partir
            return new Vec3[]{anneau, new Vec3(-Math.cos(a) * 0.15, -1, -Math.sin(a) * 0.15).normalize()};
        }
        Vec3 sol = e.lances[k].add(0, -0.7, 0);
        Vec3 dir = sol.subtract(anneau).normalize();
        double u = entre(ticks, tombe, tombe + Fruit3Coups.P_CHUTE);
        return new Vec3[]{anneau.lerp(sol, u * u), dir};
    }

    private static void partisans(MultiBufferSource.BufferSource buffers, Matrix4f m, Vec3 cam, Minecraft mc, Effet e, double t) {
        double ticks = t * 20, fonte = 1 - entre(ticks, Fruit3Coups.P_FIN - 14, Fruit3Coups.P_FIN + 6);
        VertexConsumer v = buffers.getBuffer(ClientFruit3.Types.PLEIN);
        for (int k = 0; k < Fruit3Coups.LANCES; k++) {
            double forme = croissance(ticks, k * 1.1, k * 1.1 + 6) * fonte;
            if (forme < 0.01) {
                continue;
            }
            Vec3[] l = lance(mc, e, k, ticks);
            Vec3 bout = l[0], dir = l[1];
            Vec3[] r = repere(dir, null);
            double L = 3.2 * forme;
            Vec3 c = bout.subtract(dir.scale(1.1 + L / 2)).subtract(cam);
            int kk = k;
            boite(v, m, c, r[0].scale(0.11), r[1].scale(0.11), dir.scale(L / 2), (f, i, j) -> glace(kk * 5 + i, j, 650, f), 1, 2);
            Vec3 base = bout.subtract(dir.scale(1.2)).subtract(cam);
            pic(v, m, base, dir, 1.2 * forme, 0.42 * forme, GLACE, BLANCHE, 1);
            for (int q = 0; q < 4; q++) {
                double a = q * Math.PI / 2 + 0.4;
                Vec3 sens = r[0].scale(Math.cos(a)).add(r[1].scale(Math.sin(a))).add(dir.scale(-0.6));
                cristal(v, m, base.add(dir.scale(0.2)), sens, 0.45 * forme, 0.14);
            }
            // l'impact : une étoile de petits cristaux autour de la lance plantée
            double tombe = Fruit3Coups.P_DEBUT + Fruit3Coups.P_ECART * k + Fruit3Coups.P_CHUTE;
            double g = croissance(ticks, tombe, tombe + 3) * fonte;
            if (g > 0.01) {
                Vec3 pied = bout.add(dir.scale(-0.7));
                for (int q = 0; q < 7; q++) {
                    double a = q / 7.0 * Math.PI * 2 + k;
                    cristal(v, m, pied.add(Math.cos(a) * 0.3, 0, Math.sin(a) * 0.3).subtract(cam), new Vec3(Math.cos(a), 0.7, Math.sin(a)), (0.35 + 0.4 * hache(q, k, 651)) * g, 0.16);
                }
            }
        }
        buffers.endBatch(ClientFruit3.Types.PLEIN);
        // le sol qui gèle sous la zone : un disque de givre et son anneau blanc
        VertexConsumer f = buffers.getBuffer(ClientFruit3.Types.VOILE);
        double g = croissance(ticks, 0, 8) * fonte, R = 4.8 * g;
        Vec3 o = e.a.add(0, 0.03, 0).subtract(cam);
        for (int q = 0; q < 32; q++) {
            double a0 = q / 32.0 * Math.PI * 2, a1 = (q + 1) / 32.0 * Math.PI * 2;
            Vec3 p0 = o.add(Math.cos(a0) * R, 0, Math.sin(a0) * R), p1 = o.add(Math.cos(a1) * R, 0, Math.sin(a1) * R);
            ClientFruit3.quad(f, m, o, o, p1, p0, GLACE_C, 0.35F);
            Vec3 q0 = o.add(Math.cos(a0) * (R + 0.25), 0.01, Math.sin(a0) * (R + 0.25)), q1 = o.add(Math.cos(a1) * (R + 0.25), 0.01, Math.sin(a1) * (R + 0.25));
            ClientFruit3.quad(f, m, p0.add(0, 0.01, 0), p1.add(0, 0.01, 0), q1, q0, BLANCHE, 0.8F);
        }
        buffers.endBatch(ClientFruit3.Types.VOILE);
    }

    // ================================================================ accroupi + clic gauche : la comète, la glissade

    private static void comete(MultiBufferSource.BufferSource buffers, Matrix4f m, Vec3 cam, Minecraft mc, Effet e, double t, float partiel) {
        Player j = mc.level.getPlayerByUUID(e.joueur);
        boolean enCours = e.tFin < 0 && j != null && t < Fruit3Coups.DUREE_COMETE / 20.0 + 0.4;
        // le sol brûlé sur son passage
        VertexConsumer v = buffers.getBuffer(ClientFruit3.Types.PLEIN);
        double reste = e.tFin < 0 ? 1 : 1 - entre(t, e.tFin + 1, e.tFin + 2.6);
        for (int i = 0; i < e.chemin.size() - 1 && reste > 0; i++) {
            Vec3 a = e.chemin.get(i), b = e.chemin.get(i + 1);
            int n = Math.max(1, (int) (a.distanceTo(b) / 0.4));
            for (int q = 0; q < n; q++) {
                Vec3 p = a.lerp(b, q / (double) n);
                double y = Fruit3Coups.sol(mc.level, p.x, p.z, p.y + 0.5);
                if (Double.isNaN(y) || p.y - y > 3) {
                    continue;
                }
                int k = i * 10 + q;
                float[] c = magma(k * 2 + 300, 300 + k, 660, 1.2);
                colonne(v, m, cam, new Vec3(p.x, y, p.z), 0.35 + 0.15 * Math.sin(k), 0.05 * reste, (f, ii, jj) -> c);
            }
        }
        if (!enCours) {
            buffers.endBatch(ClientFruit3.Types.PLEIN);
            return;
        }
        Vec3 dir = e.b, corps = j.getPosition(partiel).add(0, 0.9, 0), nez = corps.add(dir.scale(1.1)).add(0, 0.2, 0);
        double g = croissance(t, 0, 0.12);
        // devant lui, un cœur blanc-jaune
        for (int q = 0; q < 16; q++) {
            Vec3 u = new Vec3(hache(q, 1, 662) - 0.5, hache(q, 2, 662) - 0.5, hache(q, 3, 662) - 0.5).normalize();
            cube(v, m, nez.add(u.scale(0.4 * hache(q, 4, 662))).subtract(cam), (0.15 + 0.12 * hache(q, 5, 662)) * g, q + t * 9, avec(mix(CHAUD, LAVE_J, hache(q, 6, 662)), 1), 1);
        }
        // derrière lui, les braises de la traînée
        for (int q = 0; q < 14; q++) {
            double s = q * 0.45 + (t * 30 % 0.45);
            Vec3 p = corps.subtract(dir.scale(0.8 + s)).add(aleatFixe(q, 0.4 * (1 + s * 0.3)));
            cube(v, m, p.subtract(cam), 0.22 * (1 - q / 16.0) * g, q + t * 7, avec(feu(0.3 + q * 0.04), 1), 1);
        }
        buffers.endBatch(ClientFruit3.Types.PLEIN);
        VertexConsumer f = buffers.getBuffer(ClientFruit3.Types.VOILE);
        Vec3 arriere = dir.scale(-1);
        Vec3[] r = repere(dir, null);
        for (int q = 0; q < 20; q++) {
            double a = q / 20.0 * Math.PI * 2, rr = 0.55 + 0.25 * hache(q, 1, 664), vie = 0.75 + 0.25 * Math.sin(t * 15 + q);
            Vec3 p = corps.add(r[0].scale(Math.cos(a) * rr)).add(r[1].scale(Math.sin(a) * rr * 1.3)).add(dir.scale(0.6 - 0.8 * hache(q, 2, 664)));
            flamme(f, m, p.subtract(cam), arriere.add(r[0].scale(Math.cos(a) * 0.25)).add(r[1].scale(Math.sin(a) * 0.25)), (1.4 + 1.6 * hache(q, 3, 664)) * vie * g, 0.4);
        }
        buffers.endBatch(ClientFruit3.Types.VOILE);
    }

    private static Vec3 aleatFixe(int q, double k) {
        return new Vec3((hache(q, 1, 665) - 0.5) * 2 * k, (hache(q, 2, 665) - 0.5) * 2 * k, (hache(q, 3, 665) - 0.5) * 2 * k);
    }

    private static void glissade(VertexConsumer v, Matrix4f m, Vec3 cam, Minecraft mc, Effet e, double t) {
        double reste = e.tFin < 0 ? 1 : 1 - entre(t, e.tFin + 2, e.tFin + 2.8);
        if (reste <= 0) {
            return;
        }
        for (int i = 0; i < e.chemin.size() - 1; i++) {
            Vec3 a = e.chemin.get(i), b = e.chemin.get(i + 1), d = b.subtract(a).multiply(1, 0, 1);
            double L = d.length();
            if (L < 1e-3) {
                continue;
            }
            Vec3 u = d.scale(1 / L), cote = new Vec3(-u.z, 0, u.x);
            int n = Math.max(1, (int) Math.ceil(L / 0.5));
            for (int q = 0; q < n; q++) {
                Vec3 p = a.lerp(b, (q + 0.5) / n);
                int k = i * 8 + q;
                for (int l = -2; l <= 2; l++) {
                    double h = (Math.abs(l) == 2 ? 0.32 : 0.2) * reste;
                    int kl = l;
                    colonne(v, m, cam, p.add(cote.scale(l * 0.48)).add(0, -0.12, 0), 0.25, h, (f, ii, jj) -> glace(k * 2 + ii, kl * 2 + jj, 670, f));
                }
                if (k % 3 == 0) {
                    for (int s = -1; s <= 1; s += 2) {
                        cristal(v, m, p.add(cote.scale(s * 1.15)).add(0, 0.1, 0).subtract(cam), cote.scale(s * 0.8).add(0, 1, 0).add(u.scale(-0.2)),
                                (0.35 + 0.4 * hache(k, s + 2, 671)) * reste, 0.18);
                    }
                }
            }
        }
    }

    // ================================================================ le 3e coup, et le coup lui-même

    private static void pilier(MultiBufferSource.BufferSource buffers, Matrix4f m, Vec3 cam, Effet e, double t) {
        double g = croissance(t, 0, 0.15) * (1 - entre(t, 0.6, 1.1));
        if (g <= 0.01) {
            return;
        }
        VertexConsumer v = buffers.getBuffer(ClientFruit3.Types.PLEIN);
        for (int i = -2; i <= 2; i++) {
            for (int j = -2; j <= 2; j++) {
                double r = Math.hypot(i, j) * 0.42;
                if (r > 1) {
                    continue;
                }
                double h = 4 * (1 - r * r * 0.6) * (0.85 + 0.3 * hache(i, j, 609)) * g;
                int gi = i, gj = j;
                colonne(v, m, cam, e.a.add(i * 0.42, 0, j * 0.42), 0.22, h, (f, a, b) -> magma(gi * 3 + a + 300, gj * 3 + b + t * 10, 610 + f, 1.6));
            }
        }
        buffers.endBatch(ClientFruit3.Types.PLEIN);
        VertexConsumer f = buffers.getBuffer(ClientFruit3.Types.VOILE);
        for (int q = 0; q < 16; q++) {
            double a = q / 16.0 * Math.PI * 2;
            Vec3 p = e.a.add(Math.cos(a) * 0.9, 0.3 + 3 * hache(q, 1, 611) * g, Math.sin(a) * 0.9);
            flamme(f, m, p.subtract(cam), new Vec3(Math.cos(a) * 0.4, 1, Math.sin(a) * 0.4), (0.7 + 0.9 * hache(q, 2, 611)) * g, 0.32);
        }
        buffers.endBatch(ClientFruit3.Types.VOILE);
    }

    private static void eclats(VertexConsumer v, Matrix4f m, Vec3 cam, Effet e, double t) {
        double reste = 1 - entre(t, 0.5, 1.0);
        for (int q = 0; q < 26; q++) {
            double a = q / 26.0 * Math.PI * 2 + 0.2;
            Vec3 d = new Vec3(Math.cos(a), 0.15 + 0.5 * hache(q, 2, 618), Math.sin(a)).normalize();
            double vit = 6 + 4 * hache(q, 3, 618);
            Vec3 p = e.a.add(0, 0.3 + 1.4 * hache(q, 1, 618), 0).add(d.scale(0.5 + vit * t)).add(0, -6 * t * t, 0);
            cristal(v, m, p.subtract(cam), d, (0.4 + 0.5 * hache(q, 4, 618)) * reste, 0.16);
        }
    }

    /** Le coup : la lave, une gerbe de flammes sur la cible ; la glace, la lame de glace qui taillade devant lui. */
    private static void taille(MultiBufferSource.BufferSource buffers, Matrix4f m, Vec3 cam, Effet e, double t) {
        Vec3 d = e.b.subtract(e.a);
        if (d.lengthSqr() < 1e-4) {
            return;
        }
        Vec3 dir = d.normalize();
        if (!e.glace) {
            double g = croissance(t, 0, 0.06) * (1 - entre(t, 0.15, 0.4));
            VertexConsumer f = buffers.getBuffer(ClientFruit3.Types.VOILE);
            Vec3[] r = repere(dir, null);
            for (int q = 0; q < 14; q++) {
                double a = q / 14.0 * Math.PI * 2;
                Vec3 sens = r[0].scale(Math.cos(a) * 0.9).add(r[1].scale(Math.sin(a) * 0.9)).add(dir.scale(0.55));
                flamme(f, m, e.b.subtract(dir.scale(0.4)).subtract(cam), sens, (0.6 + 0.6 * hache(q, e.graine, 605)) * g * (e.graine >= 3 ? 1.6 : 1), 0.3);
            }
            buffers.endBatch(ClientFruit3.Types.VOILE);
            return;
        }
        // la lame balaie un arc devant lui (de droite à gauche, et dans l'autre sens au coup d'après)
        double balaye = entre(t, 0, 0.14), reste = 1 - entre(t, 0.18, 0.4);
        if (reste <= 0) {
            return;
        }
        Vec3 plat = new Vec3(dir.x, 0, dir.z).normalize(), cote = new Vec3(-plat.z, 0, plat.x);
        Vec3 epaule = e.a.add(0, -0.35, 0).add(cote.scale(0.3));
        double sens = e.graine % 2 == 0 ? 1 : -1;
        VertexConsumer v = buffers.getBuffer(ClientFruit3.Types.PLEIN);
        int n = 12;
        for (int q = 0; q <= n * balaye; q++) {
            double a = sens * (-1.1 + 2.2 * q / n), k = Math.sin(Math.PI * q / n);
            Vec3 p = epaule.add(plat.scale(Math.cos(a) * 2.1)).add(cote.scale(Math.sin(a) * 2.1)).add(0, -0.25 + 0.5 * q / n, 0);
            Vec3 tangente = plat.scale(-Math.sin(a) * sens).add(cote.scale(Math.cos(a) * sens));
            cristal(v, m, p.subtract(cam), tangente.scale(-1).add(0, 0.15, 0), (0.5 + 0.5 * k) * reste, 0.12 + 0.1 * k);
        }
        // la lame elle-même, de sa main au bout de l'arc
        double a = sens * (-1.1 + 2.2 * balaye);
        Vec3 bout = epaule.add(plat.scale(Math.cos(a) * 2.3)).add(cote.scale(Math.sin(a) * 2.3)).add(0, 0.25 * balaye, 0);
        Vec3 main = epaule.add(plat.scale(0.5));
        Vec3 lame = bout.subtract(main);
        Vec3[] r = repere(lame.normalize(), null);
        boite(v, m, main.add(lame.scale(0.5)).subtract(cam), r[0].scale(0.16 * reste), r[1].scale(0.04), lame.scale(0.5), (f, i, j) -> glace(i, j, 614, f), 1, 2);
        pic(v, m, bout.subtract(cam), lame.normalize(), 0.45 * reste, 0.3 * reste, GLACE, BLANCHE, 1);
        buffers.endBatch(ClientFruit3.Types.PLEIN);
    }

    // ================================================================ tout dessiner

    private static void dessiner(PoseStack ps, Vec3 cam, float partiel) {
        Minecraft mc = Minecraft.getInstance();
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        Matrix4f m = ps.last().pose();
        for (Effet e : EFFETS) {
            double t = e.t(mc, partiel);
            switch (e.sorte) {
                case Fruit3Coups.VAGUE -> vague(buffers, m, cam, mc, e, t);
                case Fruit3Coups.CONE -> {
                    cone(buffers.getBuffer(ClientFruit3.Types.PLEIN), m, cam, mc, e, t);
                    buffers.endBatch(ClientFruit3.Types.PLEIN);
                }
                case Fruit3Coups.VOLCAN -> volcan(buffers, m, cam, mc, e, t);
                case Fruit3Coups.BOMBE -> bombe(buffers, m, cam, e, t);
                case Fruit3Coups.PARTISANS -> partisans(buffers, m, cam, mc, e, t);
                case Fruit3Coups.COMETE -> comete(buffers, m, cam, mc, e, t, partiel);
                case Fruit3Coups.GLISSADE -> {
                    glissade(buffers.getBuffer(ClientFruit3.Types.PLEIN), m, cam, mc, e, t);
                    buffers.endBatch(ClientFruit3.Types.PLEIN);
                }
                case Fruit3Coups.PILIER -> pilier(buffers, m, cam, e, t);
                case Fruit3Coups.ECLAT -> {
                    eclats(buffers.getBuffer(ClientFruit3.Types.PLEIN), m, cam, e, t);
                    buffers.endBatch(ClientFruit3.Types.PLEIN);
                }
                case Fruit3Coups.TAILLE -> taille(buffers, m, cam, e, t);
                default -> {
                }
            }
        }
    }

    // ================================================================ chaque tick : les particules, les chemins, sa ruée

    private static void avancer(Minecraft mc) {
        for (Effet e : EFFETS) {
            double t = e.t(mc, 0);
            switch (e.sorte) {
                case Fruit3Coups.VAGUE -> {
                    if (e.tFin < 0) {
                        Vec3 p = e.a.add(e.b.scale(Fruit3Coups.frontVague(t * 20)));
                        for (int q = 0; q < 6; q++) {
                            Vec3 c = p.add(new Vec3(-e.b.z, 0, e.b.x).scale(aleat(2.6))).add(0, 1 + HASARD.nextDouble() * 3, 0);
                            particule(mc, q % 3 == 0 ? ParticleTypes.LARGE_SMOKE : ParticleTypes.FLAME, c, aleat(0.05), 0.08, aleat(0.05));
                        }
                        particule(mc, ParticleTypes.LAVA, p.add(aleat(2), 2, aleat(2)), 0, 0, 0);
                    }
                }
                case Fruit3Coups.CONE -> {
                    double front = Math.min(Fruit3Coups.PORTEE_CONE, t * 20 * Fruit3Coups.VITESSE_CONE);
                    if (t < 0.6) {
                        for (int q = 0; q < 8; q++) {
                            double a = aleat(Fruit3Coups.DEMI_ANGLE), r = front * (0.6 + 0.4 * HASARD.nextDouble());
                            Vec3 d = new Vec3(e.b.x * Math.cos(a) - e.b.z * Math.sin(a), 0, e.b.x * Math.sin(a) + e.b.z * Math.cos(a));
                            particule(mc, ParticleTypes.SNOWFLAKE, e.a.add(d.scale(r)).add(0, 0.5 + HASARD.nextDouble() * 3, 0), aleat(0.1), 0.05, aleat(0.1));
                        }
                    }
                    if (t >= 2.5 && t < 2.55) {
                        BlockParticleOption glace = new BlockParticleOption(ParticleTypes.BLOCK, Blocks.ICE.defaultBlockState());
                        for (int q = 0; q < 120; q++) {
                            double a = aleat(Fruit3Coups.DEMI_ANGLE), r = Fruit3Coups.PORTEE_CONE * Math.sqrt(HASARD.nextDouble());
                            Vec3 d = new Vec3(e.b.x * Math.cos(a) - e.b.z * Math.sin(a), 0, e.b.x * Math.sin(a) + e.b.z * Math.cos(a));
                            particule(mc, glace, e.a.add(d.scale(r)).add(0, HASARD.nextDouble() * (r > 11 ? 5 : 1.2), 0), aleat(0.3), HASARD.nextDouble() * 0.3, aleat(0.3));
                        }
                        ClientFruit.secouer(e.a.add(e.b.scale(8)), 0.8F, 24);
                    }
                }
                case Fruit3Coups.VOLCAN -> {
                    double ticks = t * 20;
                    Vec3 haut = e.a.add(0, H_VOLCAN, 0);
                    if (ticks >= Fruit3Coups.V_ERUPTION && ticks < Fruit3Coups.V_BOMBES) {
                        for (int q = 0; q < 4; q++) {
                            particule(mc, ParticleTypes.LARGE_SMOKE, haut.add(aleat(0.8), 1 + HASARD.nextDouble() * 3, aleat(0.8)), aleat(0.06), 0.2, aleat(0.06));
                            particule(mc, ParticleTypes.LAVA, haut.add(aleat(0.6), 0.5, aleat(0.6)), 0, 0, 0);
                        }
                        particule(mc, ParticleTypes.CAMPFIRE_COSY_SMOKE, haut.add(aleat(0.6), 2, aleat(0.6)), aleat(0.02), 0.12, aleat(0.02));
                    }
                    if (Math.abs(ticks - Fruit3Coups.V_ERUPTION) < 0.5) {
                        ClientFruit3.eclater(haut, false, false, 0.7);
                        ClientFruit.secouer(e.a, 1.6F, 30);
                    }
                    if (ticks < Fruit3Coups.V_CONE && e.b.distanceTo(e.a) > 1) {
                        Vec3 p = e.b.lerp(e.a, ticks / Fruit3Coups.V_CONE);
                        particule(mc, ParticleTypes.FLAME, new Vec3(p.x, Mth.lerp(ticks / Fruit3Coups.V_CONE, e.b.y, e.a.y) + 0.2, p.z), aleat(0.05), 0.1, aleat(0.05));
                    }
                }
                case Fruit3Coups.BOMBE -> {
                    double u = t * 20 / Fruit3Coups.VOL_BOMBE;
                    if (u < 1) {
                        particule(mc, ParticleTypes.SMOKE, cloche(e.a, e.b, u), aleat(0.02), 0.02, aleat(0.02));
                    } else if (u < 1.08) {
                        for (int q = 0; q < 10; q++) {
                            particule(mc, q % 2 == 0 ? ParticleTypes.LAVA : ParticleTypes.FLAME, e.b.add(aleat(0.6), 0.3, aleat(0.6)), aleat(0.2), 0.2, aleat(0.2));
                        }
                    }
                }
                case Fruit3Coups.PARTISANS -> {
                    double ticks = t * 20;
                    for (int k = 0; k < Fruit3Coups.LANCES; k++) {
                        double tombe = Fruit3Coups.P_DEBUT + Fruit3Coups.P_ECART * k + Fruit3Coups.P_CHUTE;
                        if (Math.abs(ticks - tombe) < 0.5) {
                            Vec3 p = lance(mc, e, k, ticks)[0].add(0, 0.8, 0);
                            for (int q = 0; q < 14; q++) {
                                particule(mc, ParticleTypes.SNOWFLAKE, p.add(aleat(0.6), HASARD.nextDouble() * 0.6, aleat(0.6)), aleat(0.25), 0.15, aleat(0.25));
                            }
                        }
                    }
                }
                case Fruit3Coups.COMETE, Fruit3Coups.GLISSADE -> {
                    Player j = mc.level.getPlayerByUUID(e.joueur);
                    if (e.tFin < 0 && j != null) {
                        Vec3 ici = j.position();
                        if (e.chemin.isEmpty()) {
                            e.chemin.add(e.a);
                        }
                        if (ici.distanceToSqr(e.chemin.get(e.chemin.size() - 1)) > 0.04) {
                            e.chemin.add(ici);
                        }
                        if (e.sorte == Fruit3Coups.COMETE) {
                            for (int q = 0; q < 6; q++) {
                                particule(mc, q % 2 == 0 ? ParticleTypes.FLAME : ParticleTypes.LARGE_SMOKE, ici.add(aleat(0.6), 0.4 + HASARD.nextDouble() * 1.4, aleat(0.6)), aleat(0.05), 0.05, aleat(0.05));
                            }
                        } else {
                            for (int q = 0; q < 6; q++) {
                                Vec3 cote = new Vec3(-e.b.z, 0, e.b.x).scale(q % 2 == 0 ? 1 : -1);
                                particule(mc, ParticleTypes.SNOWFLAKE, ici.add(cote.scale(0.5)).add(0, 0.2, 0), cote.x * 0.3 - e.b.x * 0.2, 0.25, cote.z * 0.3 - e.b.z * 0.2);
                            }
                        }
                    }
                }
                default -> {
                }
            }
        }
        EFFETS.removeIf(e -> e.t(mc, 0) > duree(e));
    }

    /** Sa propre comète ou glissade : il file tout droit (son jeu le fait avancer, le serveur regarde où il passe). */
    private static void ruee(Minecraft mc) {
        Effet e = maRuee;
        if (e == null) {
            return;
        }
        double age = mc.level.getGameTime() - e.debut;
        boolean comete = e.sorte == Fruit3Coups.COMETE;
        int duree = comete ? Fruit3Coups.DUREE_COMETE : Fruit3Coups.DUREE_GLISSADE;
        if (age > duree + 4 || !mc.player.isAlive()) {
            maRuee = null;
            return;
        }
        if (age >= duree) {
            return;
        }
        double v = comete ? Fruit3Coups.VITESSE_COMETE : Fruit3Coups.VITESSE_GLISSADE;
        Vec3 m = mc.player.getDeltaMovement();
        mc.player.setDeltaMovement(e.b.x * v, comete ? 0 : Math.min(m.y, 0.1), e.b.z * v);
        mc.player.fallDistance = 0;
    }

    @EventBusSubscriber(modid = Cabane.MODID, value = Dist.CLIENT)
    public static final class Evenements {
        @SubscribeEvent
        public static void avantTick(ClientTickEvent.Pre event) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.level != null && mc.player != null && !mc.isPaused()) {
                ruee(mc);
            }
        }

        @SubscribeEvent
        public static void tick(ClientTickEvent.Post event) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.level == null || mc.player == null) {
                EFFETS.clear();
                maRuee = null;
                return;
            }
            avancer(mc);
        }

        /** Pendant sa ruée, les touches pour marcher ne font rien (il file tout droit). */
        @SubscribeEvent
        public static void touches(MovementInputUpdateEvent event) {
            if (maRuee != null) {
                event.getInput().leftImpulse = 0;
                event.getInput().forwardImpulse = 0;
                event.getInput().jumping = false;
                event.getInput().shiftKeyDown = false;
            }
        }

        @SubscribeEvent
        public static void monde(RenderLevelStageEvent event) {
            if (EFFETS.isEmpty() || event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES || Minecraft.getInstance().level == null) {
                return;
            }
            dessiner(event.getPoseStack(), event.getCamera().getPosition(), event.getPartialTick().getGameTimeDeltaPartialTick(false));
        }

        private Evenements() {
        }
    }

    /** Accroupi + clic droit (ou gauche), au niveau 3 : si c'est prêt, on le demande au serveur. */
    static void toucher(Minecraft mc, int action) {
        long maintenant = System.nanoTime();
        if (action == Fruit3Coups.ACC_DROIT ? maintenant < pretZone : maintenant < pretRuee || maRuee != null) {
            return;
        }
        if (action == Fruit3Coups.ACC_DROIT) {
            pretZone = maintenant + Fruit3Coups.RECHARGE_ZONE * 50_000_000L;
        } else {
            pretRuee = maintenant + Fruit3Coups.RECHARGE_RUEE * 50_000_000L;
        }
        String moi = ClientFruit.sorte(mc.player.getUUID());
        mc.player.swing(ClientFruit.mainDuFruit(mc.player, fr.cabane.pouvoir.Fruit.glace(moi)));
        PacketDistributor.sendToServer(new Fruit3Coups.Touche(action));
    }

    /** Il s'endort : tout sera prêt au réveil. */
    static void endormi() {
        pretZone = 0;
        pretRuee = 0;
        maRuee = null;
    }

    private ClientFruit3Coups() {
    }
}
