package fr.cabane.client.pouvoir;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import fr.cabane.Cabane;
import fr.cabane.client.cinema.Effets;
import fr.cabane.pouvoir.Pouvoirs.Couleur;
import fr.cabane.pouvoir.Susano4;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * Le Susano parfait (niveau 4), dessiné chez chacun : le géant en cubes de l'aperçu docs/susano4/apercu (ModeleSusano4),
 * d'après le Susano parfait de Sasuke : masque d'oni aux yeux jaunes et long nez, crinière en pointes, kimono, épaulières,
 * petite jupe de plaques, immenses ailes de tuiles dont les mains tiennent des katanas, l'épée de flammes (main droite) et
 * l'épée sombre d'Amaterasu (main gauche). Il bouge comme dans l'aperçu (AnimSusano4) : garde, marche, course, vol,
 * coups. Le joueur se tient dans sa tête et le pilote ; vu de dehors, il est caché. Les effets : la colonne de chakra de
 * la transformation, les croissants des coups, la pluie d'Amaterasu, la vrille de la perce.
 */
@EventBusSubscriber(modid = Cabane.MODID, value = Dist.CLIENT)
public final class ClientSusano4 {
    private static final double TRANSFO = Susano4.TRANSFO / 20.0, RETOUR = Susano4.RETOUR / 20.0;

    // ================================================================ l'état

    /** La phase (1 transformation, 2 forme, 3 retour) et depuis quand (temps réel). */
    private record Etat(int phase, long debut) {
    }

    private static final Map<UUID, Etat> ETATS = new HashMap<>();

    /** Une attaque reçue : sa sorte, quand, son point (les pieds au moment du coup, le centre de la pluie, la direction de la perce). */
    private record Coup(UUID joueur, int sorte, long debut, Vec3 point) {
    }

    private static final List<Coup> COUPS = new ArrayList<>();
    /** Les recharges du joueur local (pour le panneau) : la dernière pluie et la dernière perce. */
    private static long pluieLocale = -1, perceLocale = -1;

    public static void etat(Susano4.Etat4 p) {
        if (p.phase() == 0) {
            ETATS.remove(p.joueur());
        } else {
            ETATS.put(p.joueur(), new Etat(p.phase(), System.nanoTime() - p.ecoule() * 50_000_000L));
        }
    }

    public static void attaque(Susano4.Attaque4 p) {
        long maintenant = System.nanoTime();
        COUPS.add(new Coup(p.joueur(), p.sorte(), maintenant, new Vec3(p.point())));
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && mc.player.getUUID().equals(p.joueur())) {
            if (p.sorte() == Susano4.Attaque4.PLUIE) {
                pluieLocale = maintenant;
            } else if (p.sorte() == Susano4.Attaque4.PERCE) {
                perceLocale = maintenant;
            }
        }
    }

    /** Ce joueur est-il en Susano parfait (alors on ne dessine pas son chevalier) ? */
    static boolean enForme(UUID joueur) {
        return ETATS.containsKey(joueur);
    }

    /** Les secondes avant la prochaine pluie et la prochaine perce du joueur local. */
    static double rechargePluie() {
        return pluieLocale < 0 ? 0 : Susano4.RECHARGE_PLUIE / 20.0 - (System.nanoTime() - pluieLocale) / 1e9;
    }

    static double rechargePerce() {
        return perceLocale < 0 ? 0 : Susano4.RECHARGE_PERCE / 20.0 - (System.nanoTime() - perceLocale) / 1e9;
    }

    /** Les secondes de forme qui restent au joueur local (0 sinon). */
    static double resteLocal() {
        Minecraft mc = Minecraft.getInstance();
        Etat e = mc.player == null ? null : ETATS.get(mc.player.getUUID());
        if (e == null || e.phase() != 2) {
            return e == null ? 0 : e.phase() == 1 ? Susano4.DUREE / 20.0 : 0;
        }
        return Math.max(0, Susano4.DUREE / 20.0 - (System.nanoTime() - e.debut()) / 1e9 + TRANSFO);
    }

    // ================================================================ un géant posé, dans le monde

    /** Le repère du géant pour les effets : ses pieds (relatifs à la caméra), son avant, sa gauche. */
    private record Repere(Vec3 pieds, Vec3 f, Vec3 gauche) {
    }

    /**
     * Ce qui bouge chez chacun, lissé : la marche, la course, le vol (0 à 1), la foulée (en tours), le temps de la dernière
     * image, la vitesse (blocs/s).
     */
    private static final Map<UUID, double[]> ANIM = new HashMap<>();
    /** L'œil du géant, dans sa tête juste derrière le masque : c'est là que se tient le joueur. */
    private static final Vector3f OEIL = new Vector3f(0, 23.3F, 1.3F);
    /** Quand chaque articulation apparaît pendant la transformation (s) : les jambes, le corps, les bras, la tête, les ailes. */
    private static final double[] APPARAIT = {0.5, 1.0, 2.0, 1.4, 1.4, 1.6, 1.6, 1.8, 1.8, 0.5, 0.5, 0.2, 0.2, 2.5, 2.5, 2.9, 2.9};
    /** Puis les épées : celle de flammes (main droite), puis la sombre (main gauche). */
    private static final double EPEE_FLAMME = 3.5, EPEE_NOIRE = 3.9;
    /** Où est chaque géant (pour les effets, dessinés plus tard) : ses pieds dans le monde, son avant, sa gauche. */
    private static final Map<UUID, Repere> REPERES = new HashMap<>();

    /** Un géant : sa pose (garde, marche, course, vol, attaques), sa place (le joueur dans sa tête), puis ses cubes. */
    private static void geant(Minecraft mc, RenderLevelStageEvent event, Player joueur, Etat etat, float partiel, Vec3 cam, long maintenant, double temps) {
        if (!ModeleSusano4.charger()) {
            return;
        }
        double t = (maintenant - etat.debut()) / 1e9;
        Couleur couleur = ClientSusano.couleur(joueur.getUUID());
        float lacet = Mth.rotLerp(partiel, joueur.yBodyRotO, joueur.yBodyRot);
        float theta = -lacet * Mth.DEG_TO_RAD;
        Vec3 pos = joueur.getPosition(partiel);
        HitResult sous = mc.level.clip(new ClipContext(pos.add(0, 0.5, 0), pos.subtract(0, 90, 0), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, joueur));
        double sol = sous.getType() == HitResult.Type.MISS ? pos.y - Susano4.HAUT : sous.getLocation().y;
        // tant que le joueur monte (ou redescend), le géant est plus petit
        float s = (float) Mth.clamp((pos.y - sol) / Susano4.HAUT, 0.22, 1);
        boolean vole = etat.phase() == 2 && pos.y - sol > Susano4.HAUT + 2.5;
        // tout passe en douceur ; la foulée suit la distance parcourue
        double[] a = ANIM.computeIfAbsent(joueur.getUUID(), k -> new double[]{0, 0, 0, 0, temps, 0});
        double dt = Mth.clamp(temps - a[4], 0, 0.1);
        a[4] = temps;
        double vitesseTic = Math.sqrt(Mth.square(joueur.getX() - joueur.xo) + Mth.square(joueur.getZ() - joueur.zo)) * 20;
        a[5] += (vitesseTic - a[5]) * Math.min(1, dt * 8);
        double vitesse = a[5];
        a[0] += ((vole ? 0 : Mth.clamp(vitesse / 5, 0, 1)) - a[0]) * Math.min(1, dt * 5);
        a[1] += ((vole ? 0 : Mth.clamp((vitesse - 11) / 6, 0, 1)) - a[1]) * Math.min(1, dt * 4);
        a[2] += ((vole ? 1 : 0) - a[2]) * Math.min(1, dt * 2.5);
        a[3] += vitesse * dt / (9 + 5.7 * a[1]) + dt * 0.1 * a[0];
        AnimSusano4.Pose P = AnimSusano4.garde(temps).vers(AnimSusano4.pas(a[3], false), a[0]).vers(AnimSusano4.pas(a[3], true), a[1])
                .vers(AnimSusano4.vol(temps), a[2]);
        // en vol sur place, il se tient plus droit
        P.a[AnimSusano4.BASSIN][0] -= 0.55 * a[2] * (1 - Mth.clamp(vitesse / 8, 0, 1));
        // les attaques prennent la main le temps du geste
        for (Coup c : COUPS) {
            if (c.joueur().equals(joueur.getUUID())) {
                double tc = (maintenant - c.debut()) / 1e9;
                if (tc < AnimSusano4.duree(c.sorte())) {
                    P.vers(AnimSusano4.attaque(c.sorte(), tc), AnimSusano4.force(c.sorte(), tc));
                }
            }
        }
        // la tête suit le regard
        float lacetTete = Mth.clamp(Mth.wrapDegrees(Mth.rotLerp(partiel, joueur.yHeadRotO, joueur.yHeadRot) - lacet), -50, 50);
        float tangageTete = Mth.clamp(joueur.getViewXRot(partiel) * 0.5F, -25, 25);
        P.a[AnimSusano4.TETE][1] += lacetTete * Mth.DEG_TO_RAD * 0.7;
        P.a[AnimSusano4.TETE][0] += tangageTete * Mth.DEG_TO_RAD;
        Matrix4f[] M = P.matrices();
        // où il est : au sol, ses pieds au sol et le joueur à la place de son œil (au repos) ; en vol, le joueur reste dans sa
        // tête même quand le corps se penche
        Matrix4f tourne = new Matrix4f().rotateY(theta).scale(s);
        Vector3f oeilRepos = tourne.transformPosition(new Vector3f(OEIL));
        Vector3f oeilAnime = tourne.transformPosition(M[AnimSusano4.TETE].transformPosition(new Vector3f(OEIL)));
        Vec3 oeilJoueur = pos.add(0, joueur.getEyeHeight(), 0);
        Vec3 auSol = new Vec3(oeilJoueur.x - oeilRepos.x, sol, oeilJoueur.z - oeilRepos.z);
        Vec3 enVol = oeilJoueur.subtract(oeilAnime.x, oeilAnime.y, oeilAnime.z);
        Vec3 origine = auSol.lerp(enVol, a[2]);
        // la transformation : les morceaux apparaissent un à un ; le retour : ils s'effacent dans l'autre sens. Vu de
        // l'intérieur (première personne), pas de tête devant les yeux
        boolean premiere = joueur == mc.player && mc.options.getCameraType().isFirstPerson();
        ModeleSusano4.Apparition apparition = g -> {
            if (premiere && g.a() == AnimSusano4.TETE) {
                return 0;
            }
            double debut = g.epee() ? (g.a() == AnimSusano4.MAIN_D ? EPEE_FLAMME : EPEE_NOIRE) : APPARAIT[g.a()];
            if (etat.phase() == 1) {
                return (float) rebond((t - debut) / 0.6);
            }
            if (etat.phase() == 3) {
                return (float) (1 - lisse((t - (4.0 - debut) * 0.3) / 0.7));
            }
            return 1;
        };
        Vec3 rel = origine.subtract(cam);
        Matrix4f base = new Matrix4f(event.getModelViewMatrix()).translate((float) rel.x, (float) rel.y, (float) rel.z).mul(tourne);
        ModeleSusano4.dessiner(base, event.getProjectionMatrix(), M, couleur, apparition);
        double r = lacet * Mth.DEG_TO_RAD;
        Vec3 f = new Vec3(-Math.sin(r), 0, Math.cos(r));
        REPERES.put(joueur.getUUID(), new Repere(origine, f, new Vec3(f.z, 0, -f.x)));
    }

    /** Une apparition qui dépasse un peu puis se pose (0 avant, 1 après). */
    private static double rebond(double k) {
        if (k <= 0) {
            return 0;
        }
        if (k >= 1) {
            return 1;
        }
        double c = 1.70158, x = k - 1;
        return 1 + (c + 1) * x * x * x + c * x * x;
    }

    /** Les effets d'un géant : la colonne de la transformation, les croissants des coups, la vrille de la perce. */
    private static void effets(MultiBufferSource.BufferSource buffers, Player joueur, Etat etat, Vec3 cam, long maintenant, double temps) {
        Repere monde = REPERES.get(joueur.getUUID());
        if (monde == null) {
            return;
        }
        double t = (maintenant - etat.debut()) / 1e9;
        float colonne = etat.phase() == 1 ? (float) (lisse(t / 0.8) * (1 - lisse((t - 3.4) / 0.8))) : 0;
        Couleur couleur = ClientSusano.couleur(joueur.getUUID());
        float[] corps = rgb(couleur.corps), contour = rgb(couleur.contour);
        Repere R = new Repere(monde.pieds().subtract(cam), monde.f(), monde.gauche());
        lueur = buffers.getBuffer(ClientSusano.Types.LUEUR);
        voile = buffers.getBuffer(ClientSusano.Types.CORPS);
        if (colonne > 0) {
            colonne(R.pieds(), colonne, mix(corps, contour, 0.55), temps);
        }
        for (Coup c : COUPS) {
            if (!c.joueur().equals(joueur.getUUID())) {
                continue;
            }
            double tc2 = (maintenant - c.debut()) / 1e9;
            if ((c.sorte() == Susano4.Attaque4.LUMIERE || c.sorte() == Susano4.Attaque4.NOIR) && tc2 > 0.15 && tc2 < 0.6) {
                croissant(R, c.sorte() == Susano4.Attaque4.LUMIERE, (tc2 - 0.15) / 0.45, mix(contour, BLANC, 0.6));
            } else if (c.sorte() == Susano4.Attaque4.PERCE && tc2 < 1.1) {
                vrille(R.pieds().add(0, 15, 0).add(R.f().scale(9)), R.f(), 1 - tc2 / 1.1, mix(contour, BLANC, 0.6), temps);
            }
        }
        buffers.endBatch(ClientSusano.Types.CORPS);
        buffers.endBatch(ClientSusano.Types.LUEUR);
    }

    // ================================================================ dessiner

    private static VertexConsumer voile, lueur;
    private static Matrix4f m;

    private static void sommet(VertexConsumer c, Vec3 p, float r, float g, float b, float a) {
        c.addVertex(m, (float) p.x, (float) p.y, (float) p.z).setColor(r, g, b, a);
    }

    private static void quad(VertexConsumer c, Vec3 a, Vec3 b, Vec3 d, Vec3 e, float[] rgb, float alpha) {
        sommet(c, a, rgb[0], rgb[1], rgb[2], alpha);
        sommet(c, b, rgb[0], rgb[1], rgb[2], alpha);
        sommet(c, d, rgb[0], rgb[1], rgb[2], alpha);
        sommet(c, e, rgb[0], rgb[1], rgb[2], alpha);
    }

    /** Une bande le long de points (demi-largeur larg(i)), tournée face à l'indice. */
    private static void bande(VertexConsumer c, List<Vec3> pts, java.util.function.IntToDoubleFunction larg, Vec3 indice, float[] rgb, float alpha) {
        int n = pts.size();
        Vec3[] g = new Vec3[n], d = new Vec3[n];
        for (int i = 0; i < n; i++) {
            Vec3 t = pts.get(Math.min(n - 1, i + 1)).subtract(pts.get(Math.max(0, i - 1)));
            Vec3 cote = t.cross(indice);
            cote = cote.lengthSqr() < 1e-9 ? new Vec3(1, 0, 0) : cote.normalize();
            double w = larg.applyAsDouble(i);
            g[i] = pts.get(i).subtract(cote.scale(w));
            d[i] = pts.get(i).add(cote.scale(w));
        }
        for (int i = 0; i + 1 < n; i++) {
            quad(c, g[i], d[i], d[i + 1], g[i + 1], rgb, alpha);
        }
    }

    private static float[] rgb(int c) {
        return new float[]{(c >> 16 & 255) / 255F, (c >> 8 & 255) / 255F, (c & 255) / 255F};
    }

    private static float[] mix(float[] a, float[] b, double k) {
        return new float[]{(float) (a[0] + (b[0] - a[0]) * k), (float) (a[1] + (b[1] - a[1]) * k), (float) (a[2] + (b[2] - a[2]) * k)};
    }

    private static double lisse(double t) {
        t = Mth.clamp(t, 0, 1);
        return t * t * (3 - 2 * t);
    }

    private static double hache(int i, int j, int k) {
        int h = i * 374761393 + j * 668265263 + k * 2147483647;
        h = (h ^ (h >>> 13)) * 1274126177;
        return ((h ^ (h >>> 16)) >>> 0 & 0x7fffffff) / (double) 0x7fffffff;
    }

    private static final float[] NOIR_ENCRE = {0.02F, 0.012F, 0.03F}, BLANC = {1, 1, 1};

    @SubscribeEvent
    public static void dessiner(RenderLevelStageEvent event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || ETATS.isEmpty() && COUPS.isEmpty()) {
            return;
        }
        if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_ENTITIES) {
            // les géants, opaques, avec les autres entités
            long maintenant = System.nanoTime();
            float partiel = event.getPartialTick().getGameTimeDeltaPartialTick(false);
            Vec3 cam = event.getCamera().getPosition();
            for (Map.Entry<UUID, Etat> en : ETATS.entrySet()) {
                Player joueur = mc.level.getPlayerByUUID(en.getKey());
                if (joueur != null) {
                    geant(mc, event, joueur, en.getValue(), partiel, cam, maintenant, maintenant / 1e9);
                }
            }
            return;
        }
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) {
            return;
        }
        long maintenant = System.nanoTime();
        COUPS.removeIf(c -> (maintenant - c.debut()) / 1e9 > 4);
        Vec3 cam = event.getCamera().getPosition();
        PoseStack ps = event.getPoseStack();
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        m = ps.last().pose();
        float partiel = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        double temps = maintenant / 1e9;
        for (Map.Entry<UUID, Etat> en : ETATS.entrySet()) {
            Player joueur = mc.level.getPlayerByUUID(en.getKey());
            if (joueur != null) {
                effets(buffers, joueur, en.getValue(), cam, maintenant, temps);
            }
        }
        // les effets des attaques
        voile = buffers.getBuffer(ClientSusano.Types.CORPS);
        for (Coup c : COUPS) {
            if (c.sorte() == Susano4.Attaque4.PLUIE) {
                pluie(c.point().subtract(cam), (maintenant - c.debut()) / 1e9, temps);
            }
        }
        buffers.endBatch(ClientSusano.Types.CORPS);
        lueur = buffers.getBuffer(ClientSusano.Types.LUEUR);
        for (Coup c : COUPS) {
            if (c.sorte() == Susano4.Attaque4.PLUIE) {
                cercle(c.point().subtract(cam), 10, (maintenant - c.debut()) / 1e9);
            }
        }
        buffers.endBatch(ClientSusano.Types.LUEUR);
    }

    /** La colonne de chakra de la transformation : des spirales qui montent tout autour. */
    private static void colonne(Vec3 pieds, double k, float[] c, double temps) {
        double h = 70 * lisse(k * 1.5);
        for (int n = 0; n < 12; n++) {
            List<Vec3> pts = new ArrayList<>();
            for (int i = 0; i <= 26; i++) {
                double y = i / 26.0 * h, a = n * (Math.PI * 2 / 12) + y * 0.1 - temps * 3.2, r = 9 + 4 * Math.sin(y * 0.15 + n) - 4 * (1 - k);
                pts.add(pieds.add(Math.cos(a) * r, y, Math.sin(a) * r));
            }
            bande(lueur, pts, i -> (2.2 - i / 16.0) * k, new Vec3(0, 1, 0), c, (float) (0.4 * k));
            bande(lueur, pts, i -> 0.3 * k, new Vec3(0, 1, 0), BLANC, (float) (0.6 * k));
        }
    }

    /** Le croissant d'un coup d'épée, à plat devant lui : de lumière (de gauche à droite) ou noir (de droite à gauche). */
    private static void croissant(Repere R, boolean lumiere, double k, float[] vif) {
        double a0 = lumiere ? 80 : -80, a1 = lumiere ? -70 : 70, r0 = 6, r1 = 15, fin = Math.min(1, k * 1.6), fondu = 1 - Math.max(0, (k - 0.6) / 0.4);
        Vec3 centre = R.pieds().add(0, 17, 0);
        VertexConsumer c = lumiere ? lueur : voile;
        for (int i = 0; i < 30 * fin; i++) {
            double s0 = i / 30.0, s1 = (i + 1) / 30.0;
            Vec3 d0 = dirA(R, a0 + (a1 - a0) * s0), d1 = dirA(R, a0 + (a1 - a0) * s1);
            double e0 = (r1 - r0) * Math.pow(Math.sin(Math.PI * s0), 0.6), e1 = (r1 - r0) * Math.pow(Math.sin(Math.PI * s1), 0.6);
            Vec3 y0 = new Vec3(0, (s0 - 0.5) * 3, 0), y1 = new Vec3(0, (s1 - 0.5) * 3, 0);
            quad(c, centre.add(d0.scale(r1 - e0)).add(y0), centre.add(d1.scale(r1 - e1)).add(y1), centre.add(d1.scale(r1)).add(y1), centre.add(d0.scale(r1)).add(y0),
                    lumiere ? vif : NOIR_ENCRE, (float) ((lumiere ? 0.3 + 0.6 * s1 : 0.6 + 0.35 * s1) * fondu));
        }
    }

    private static Vec3 dirA(Repere R, double degres) {
        double a = Math.toRadians(degres);
        return R.f().scale(Math.cos(a)).add(R.gauche().scale(Math.sin(a)));
    }

    /** La vrille de la perce : des spirales de lumière autour des deux épées, larges aux mains, en pointe au-delà des lames. */
    private static void vrille(Vec3 O, Vec3 d, double k, float[] vif, double temps) {
        Vec3 X = d.cross(new Vec3(0, 1, 0)).normalize(), Y = X.cross(d);
        for (int n = 0; n < 12; n += 2) {
            List<Vec3> pts = new ArrayList<>();
            for (int i = 0; i <= 30; i++) {
                double s = i / 30.0, rr = 8 * Math.pow(1 - s, 1.2) + 0.6, a = n * (Math.PI * 2 / 12) + s * 11 - temps * 14;
                pts.add(O.add(d.scale(34 * s)).add(X.scale(Math.cos(a) * rr)).add(Y.scale(Math.sin(a) * rr)));
            }
            bande(lueur, pts, i -> 0.2 + (1 - i / 30.0), d, vif, (float) (0.6 * k));
        }
    }

    /** La pluie d'Amaterasu : le nuage noir au-dessus de la zone, et les gouttes de feu noir qui tombent pendant 3 s. */
    private static void pluie(Vec3 centre, double t, double temps) {
        double fondu = 1 - lisse((t - 3) / 0.8);
        if (fondu <= 0) {
            return;
        }
        for (int i = 0; i < 30; i++) {
            double a = hache(i, 1, 9) * Math.PI * 2, r = Math.sqrt(hache(i, 2, 9)) * 11, s = 3 + 3 * hache(i, 4, 9);
            Vec3 p = centre.add(Math.cos(a) * r, 40 + hache(i, 3, 9) * 4, Math.sin(a) * r);
            quad(voile, p.add(-s, 0, -s), p.add(s, 0, -s), p.add(s, 0, s), p.add(-s, 0, s), new float[]{0.05F, 0.035F, 0.055F}, (float) (0.55 * fondu * lisse(t / 0.5)));
        }
        if (t > 3) {
            return;
        }
        for (int i = 0; i < 24; i++) {
            double a = hache(i, 5, 9) * Math.PI * 2, r = Math.sqrt(hache(i, 6, 9)) * 10;
            double y = 40 - ((t * 1.6 + hache(i, 7, 9)) % 1) * 40;
            Vec3 p = centre.add(Math.cos(a) * r, y, Math.sin(a) * r);
            bande(voile, List.of(p.add(0, 6, 0), p), i2 -> i2 == 0 ? 0.05 : 0.55, new Vec3(0, 0, 1), NOIR_ENCRE, 0.85F);
            bande(voile, List.of(p.add(0, 6, 0), p), i2 -> i2 == 0 ? 0.05 : 0.55, new Vec3(1, 0, 0), NOIR_ENCRE, 0.85F);
        }
    }

    /** Le cercle de la zone de la pluie, au sol. */
    private static void cercle(Vec3 c, double r, double t) {
        double k = lisse(t / 0.3) * (1 - lisse((t - 3) / 0.8));
        if (k <= 0) {
            return;
        }
        List<Vec3> pts = new ArrayList<>();
        for (int i = 0; i <= 48; i++) {
            double a = i / 48.0 * Math.PI * 2;
            pts.add(c.add(Math.cos(a) * r, 0.25, Math.sin(a) * r));
        }
        bande(lueur, pts, i -> 0.4, new Vec3(0, 1, 0), new float[]{1, 0.35F, 0.4F}, (float) (0.8 * k));
    }

    /** Le joueur ne se voit pas dans le joyau (ni sa main en première personne) : on ne voit que le géant. */
    @SubscribeEvent
    public static void cacherJoueur(net.neoforged.neoforge.client.event.RenderPlayerEvent.Pre event) {
        if (ETATS.containsKey(event.getEntity().getUUID())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void cacherMain(net.neoforged.neoforge.client.event.RenderHandEvent event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && ETATS.containsKey(mc.player.getUUID())) {
            event.setCanceled(true);
        }
    }

    /** En troisième personne, la caméra recule assez pour voir tout le géant. */
    @SubscribeEvent
    public static void recul(net.neoforged.neoforge.client.event.CalculateDetachedCameraDistanceEvent event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && ETATS.containsKey(mc.player.getUUID())) {
            event.setDistance(Math.max(event.getDistance(), 46));
        }
    }

    private ClientSusano4() {
    }
}
