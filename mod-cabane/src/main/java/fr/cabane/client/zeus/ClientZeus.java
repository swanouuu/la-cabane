package fr.cabane.client.zeus;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.math.Axis;
import fr.cabane.Cabane;
import fr.cabane.zeus.Zeus;
import java.io.Reader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.PlayerSkin;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.MovementInputUpdateEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.client.event.RenderGuiLayerEvent;
import net.neoforged.neoforge.client.event.RenderHandEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.event.RenderLivingEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * L'éclair de Zeus (/zeus), chez chaque joueur autour de la cible. C'est l'aperçu validé (docs/zeus), plan par plan :
 * le village, les cieux au coucher du soleil où se tient un Zeus de 128 blocs sur sa mer de nuages, son regard furieux,
 * l'orage qui s'enroule dans sa main, le lancer, la descente de l'éclair qui perce les nuages, l'impact, la remontée de
 * tout le trajet jusqu'à lui, puis les mains de la cible qui crépitent et ses répliques dans la bande du bas.
 * Seule la cible a la cinématique ; les autres voient juste l'éclair descendre du ciel et la frapper. Zeus n'est
 * dessiné que dans les plans du ciel : depuis la map, on ne le voit jamais.
 * Le temps est celui de l'aperçu, en secondes depuis le top départ.
 */
public final class ClientZeus {
    public static final double DUREE = 32;
    /** Le lâcher de l'éclair, l'impact, et le passage à la première personne. */
    private static final double TR = 10.42, THIT = 14.6, MAINS = 20.6;
    private static final ResourceLocation TEXTURE = Cabane.id("textures/entity/zeus.png");
    private static final ResourceLocation MODELE = Cabane.id("modeles/zeus.json");
    /** Un demi-pixel du modèle vaut 2 blocs : Zeus fait 128 blocs de haut. */
    private static final float ECHELLE = 32F;
    /** Les plans de l'aperçu sont en pixels de joueur : un pixel de Zeus vaut 4 blocs. */
    private static final double PX = 4;
    /** Les nuages de Minecraft, au-dessus du village. */
    private static final double NUAGES = 192;
    private static final int PARLEUR = 0xFFB98CFF, TITRE = 0xFFFFC83A;
    private static final Vec3 HAUT = new Vec3(0, 1, 0);

    // ---- la scène en cours
    private static boolean actif, cinema;
    private static UUID cible;
    private static long debut;
    private static double avant;
    /** Les pieds de la cible, vers où elle regarde (son lacet), son devant et sa droite. */
    private static Vec3 pieds = Vec3.ZERO, devant = Vec3.ZERO, droite = Vec3.ZERO;
    private static float lacetCible;
    /** Les pieds de Zeus, son devant (vers la cible) et sa gauche ; le bas de sa mer de nuages. */
    private static Vec3 z0 = Vec3.ZERO, avantZ = Vec3.ZERO, gaucheZ = Vec3.ZERO;
    private static float lacetZeus;
    private static double mer;
    /** L'éclair : de son poing (au lâcher) à la tête de la cible, la ligne brisée et ses longueurs cumulées. */
    private static Vec3 poing = Vec3.ZERO, tete = Vec3.ZERO, sens = Vec3.ZERO, cote = Vec3.ZERO;
    private static Vec3[] trajet = new Vec3[0];
    private static double[] cumul = new double[0];
    private static final List<Branche> branches = new ArrayList<>();
    private static final List<Vec3[]> fond = new ArrayList<>();
    private static final double[] FOND = {2.7, 3.95, 5.3, 6.15, 9.05, 12.8};
    /** Où et quand l'éclair perce la mer de nuages du ciel, puis les nuages de Minecraft (temps négatif : jamais). */
    private static double percee1 = -1, percee2 = -1;
    private static Vec3 point1 = Vec3.ZERO, point2 = Vec3.ZERO;
    /** Les cases de la mer de nuages (avant et après le passage de l'éclair), du plafond d'orage, et les nuages qui tournent. */
    private static final List<int[]> mer1 = new ArrayList<>(), mer2 = new ArrayList<>(), plafond = new ArrayList<>();
    private static final List<double[]> tourbillon = new ArrayList<>(), aspires = new ArrayList<>();
    private static Vec3 temple = Vec3.ZERO;
    private static final double CASE = 32, CASE_PLAFOND = 56;

    private record Branche(double f, Vec3[] pts) {
    }

    // ---- la cinématique
    private static Entity objectif;
    private static CameraType vueAvant;
    private static boolean guiAvant;
    private static float fov = 70;
    /** Le plan courant : au sol (pas de Zeus, pas de ciel), à la première personne, mélange ciel → sol, épaisseur de l'éclair. */
    private static boolean sol = true, premiere;
    private static double versSol = 1, largeur = 1;
    private static Vec3 camera = Vec3.ZERO, vise = Vec3.ZERO;
    private static final Random SECOUSSE = new Random();

    // ---- le modèle
    private static ModelPart racine, torse, teteZ, brasD, brasG, mainD, jambeD, jambeG, capeZ, oeilD, oeilG, gemme;
    private static boolean rate;
    private static PlayerModel<Player> brasLarges, brasFins;

    private static double t() {
        return (System.nanoTime() - debut) / 1e9;
    }

    private static double entre(double t, double a, double b) {
        return Mth.clamp((t - a) / (b - a), 0, 1);
    }

    private static double lisse(double k) {
        return k < 0.5 ? 2 * k * k : 1 - Math.pow(-2 * k + 2, 2) / 2;
    }

    private static double sortie(double k) {
        return 1 - Math.pow(1 - k, 3);
    }

    private static double lp(double a, double b, double k) {
        return a + (b - a) * k;
    }

    /** Un bruit déterministe entre 0 et 1 (pour que les éclairs ne dépendent pas des images). */
    private static double hasard(long n) {
        long x = n * 0x9E3779B97F4A7C15L;
        x ^= x >>> 31;
        x *= 0xBF58476D1CE4E5B9L;
        x ^= x >>> 29;
        return (x >>> 11) / (double) (1L << 53);
    }

    /** Un point du repère de Zeus, en pixels de l'aperçu (sa gauche, le haut, son devant ; les pieds à l'origine). */
    private static Vec3 zl(double x, double y, double z) {
        return z0.add(gaucheZ.scale(x * PX)).add(0, y * PX, 0).add(avantZ.scale(z * PX));
    }

    private static Vec3 horizontal(Vec3 v) {
        Vec3 h = new Vec3(v.x, 0, v.z);
        return h.lengthSqr() < 1.0E-6 ? new Vec3(0, 0, 1) : h.normalize();
    }

    private static float lacet(Vec3 d) {
        return (float) Math.toDegrees(Math.atan2(-d.x, d.z));
    }

    // ================================================================ départ, arrêt

    public static void demarrer(Zeus.Debut d) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || !charger()) {
            return;
        }
        if (cinema) {
            sortir();
        }
        cible = d.cible();
        pieds = new Vec3(d.x(), d.y(), d.z());
        lacetCible = d.lacet();
        double r = Math.toRadians(lacetCible);
        devant = new Vec3(-Math.sin(r), 0, Math.cos(r));
        droite = new Vec3(-devant.z, 0, devant.x);
        // Zeus, derrière la cible et un peu à droite, tout là-haut au-dessus des nuages
        z0 = pieds.add(devant.scale(-150)).add(droite.scale(70));
        z0 = new Vec3(z0.x, Math.max(pieds.y + 330, 400), z0.z);
        avantZ = horizontal(pieds.subtract(z0));
        gaucheZ = new Vec3(avantZ.z, 0, -avantZ.x);
        lacetZeus = lacet(avantZ);
        mer = z0.y - 2;
        poing = main(TR);
        tete = pieds.add(0, 1.9, 0);
        sens = tete.subtract(poing).normalize();
        cote = sens.cross(HAUT).normalize();
        Random rnd = new Random(cible.getLeastSignificantBits() ^ 77);
        trajet = zig(poing, tete, 84, 5.5, rnd);
        cumul = new double[trajet.length];
        for (int i = 1; i < trajet.length; i++) {
            cumul[i] = cumul[i - 1] + trajet[i].distanceTo(trajet[i - 1]);
        }
        branches.clear();
        for (int k = 0; k < 7; k++) {
            int i = 6 + rnd.nextInt(70);
            Vec3 dir = sens.add(cote.scale((rnd.nextDouble() - 0.5) * 2.2)).add((rnd.nextDouble() - 0.5) * 0.6, -0.2, (rnd.nextDouble() - 0.5) * 0.6).normalize();
            Vec3 a = trajet[i];
            branches.add(new Branche(cumul[i] / total(), zig(a, a.add(dir.scale(20 + rnd.nextDouble() * 45)), 7, 3, rnd)));
        }
        double[] p1 = percee(z0.y + 2), p2 = tete.y < NUAGES ? percee(NUAGES + 2) : null;
        percee1 = p1 != null ? p1[0] : -1;
        point1 = p1 != null ? new Vec3(p1[1], p1[2], p1[3]) : Vec3.ZERO;
        percee2 = p2 != null ? p2[0] : -1;
        point2 = p2 != null ? new Vec3(p2[1], p2[2], p2[3]) : Vec3.ZERO;
        temple = zl(16, 0, 28);
        ciel(rnd);
        debut = System.nanoTime();
        avant = 0;
        actif = true;
        if (mc.player.getUUID().equals(cible)) {
            entrer();
        }
    }

    private static double total() {
        return cumul.length == 0 ? 1 : Math.max(1.0E-3, cumul[cumul.length - 1]);
    }

    /** Une ligne brisée de A à B qui erre de côté comme un éclair (plus au milieu qu'aux bouts). */
    private static Vec3[] zig(Vec3 a, Vec3 b, int n, double amp, Random rnd) {
        Vec3 d = b.subtract(a);
        Vec3 p1 = new Vec3(-d.z, 0, d.x);
        p1 = p1.lengthSqr() < 1.0E-6 ? new Vec3(1, 0, 0) : p1.normalize();
        Vec3 p2 = d.cross(p1).normalize();
        Vec3[] pts = new Vec3[n + 1];
        double o1 = 0, o2 = 0;
        for (int i = 0; i <= n; i++) {
            double f = (double) i / n;
            Vec3 p = a.add(d.scale(f));
            if (i > 0 && i < n) {
                o1 = Mth.clamp(o1 + (rnd.nextDouble() - 0.5) * amp * 1.6, -amp * 1.6, amp * 1.6);
                o2 = Mth.clamp(o2 + (rnd.nextDouble() - 0.5) * amp * 1.6, -amp * 1.6, amp * 1.6);
                double k = Math.pow(Math.sin(Math.PI * f), 0.35);
                p = p.add(p1.scale(o1 * k)).add(p2.scale(o2 * k));
            }
            pts[i] = p;
        }
        return pts;
    }

    /** La part du trajet parcourue par la tête de l'éclair (il accélère en descendant). */
    private static double avance(double t) {
        double s = entre(t, TR, THIT);
        return 0.12 * s + 0.88 * s * s;
    }

    /** Quand et où l'éclair passe à l'altitude y : {temps, x, y, z}. */
    private static double[] percee(double y) {
        for (int i = 1; i < trajet.length; i++) {
            if (trajet[i].y < y) {
                double k = (trajet[i - 1].y - y) / (trajet[i - 1].y - trajet[i].y);
                Vec3 p = trajet[i - 1].lerp(trajet[i], k);
                double f = (cumul[i - 1] + k * (cumul[i] - cumul[i - 1])) / total();
                for (int s = 0; s <= 600; s++) {
                    double tt = TR + (THIT - TR) * s / 600;
                    if (avance(tt) >= f) {
                        return new double[] {tt, p.x, p.y, p.z};
                    }
                }
                return new double[] {THIT, p.x, p.y, p.z};
            }
        }
        return null;
    }

    /** Les points de l'éclair jusqu'à sa tête. */
    private static List<Vec3> jusque(double fr) {
        List<Vec3> out = new ArrayList<>();
        if (trajet.length == 0) {
            return out;
        }
        double l = fr * total();
        out.add(trajet[0]);
        for (int i = 1; i < trajet.length; i++) {
            if (cumul[i] <= l) {
                out.add(trajet[i]);
            } else {
                out.add(trajet[i - 1].lerp(trajet[i], (l - cumul[i - 1]) / (cumul[i] - cumul[i - 1])));
                break;
            }
        }
        return out;
    }

    /** La mer de nuages, le plafond d'orage, les nuages qui tournent autour de Zeus et ceux que sa main aspire. */
    private static void ciel(Random rnd) {
        mer1.clear();
        mer2.clear();
        pleines1.clear();
        pleines2.clear();
        plafond.clear();
        tourbillon.clear();
        aspires.clear();
        int[] cz = caseDe(z0), ct = caseDe(temple), cp = caseDe(point1);
        for (int i = -20; i <= 20; i++) {
            for (int j = -20; j <= 20; j++) {
                double bruit = hasard((long) (i * 73856093L) ^ (j * 19349663L)) * 0.45 + 0.55 * bosses(i * 0.31, j * 0.31);
                boolean force = Math.hypot(i - cz[0], j - cz[1]) < 4.2 || Math.hypot(i - ct[0], j - ct[1]) < 1.6 || Math.hypot(i - cp[0], j - cp[1]) < 2.6;
                if (bruit < 0.5 && !force) {
                    continue;
                }
                mer1.add(new int[] {i, j});
                pleines1.add(cle(i, j));
                if (Math.hypot(i - cp[0], j - cp[1]) >= 1.3) {
                    mer2.add(new int[] {i, j});
                    pleines2.add(cle(i, j));
                }
            }
        }
        for (int i = -12; i <= 12; i++) {
            for (int j = -12; j <= 12; j++) {
                if (bosses(i * 0.5 + 7, j * 0.5 - 3) > 0.52) {
                    plafond.add(new int[] {i, j});
                }
            }
        }
        double k = PX / 1.3;
        for (int i = 0; i < 90; i++) {
            double a = rnd.nextDouble() * Math.PI * 2, r = 7 + rnd.nextDouble() * 38, w = 6 + rnd.nextDouble() * 12;
            tourbillon.add(new double[] {a, r * k, (1.5 + rnd.nextDouble() * rnd.nextDouble() * 6 + (r < 15 ? 3 : 0)) * k, w * k, (2 + rnd.nextDouble() * 3) * k,
                    w * (0.5 + rnd.nextDouble() * 0.6) * k});
        }
        for (int i = 0; i < 70; i++) {
            aspires.add(new double[] {rnd.nextDouble(), rnd.nextDouble() * Math.PI * 2, (14 + rnd.nextDouble() * 20) * k, (rnd.nextDouble() - 0.5) * 16 * k,
                    (1 + rnd.nextDouble() * 2.2) * k});
        }
        fond.clear();
        for (int i = 0; i < FOND.length; i++) {
            Random r = new Random(100 + i);
            double a = r.nextDouble() * Math.PI * 2, dd = (150 + r.nextDouble() * 200) * k;
            Vec3 p = new Vec3(z0.x + Math.cos(a) * dd, z0.y + 6, z0.z + Math.sin(a) * dd);
            fond.add(zig(p.add((r.nextDouble() - 0.5) * 30 * k, 240, (r.nextDouble() - 0.5) * 30 * k), p, 26, 4 * k, r));
        }
    }

    private static int[] caseDe(Vec3 p) {
        return new int[] {(int) Math.floor((p.x - z0.x) / CASE + 0.5), (int) Math.floor((p.z - z0.z) / CASE + 0.5)};
    }

    /** Des bosses douces entre 0 et 1 (un bruit lissé), pour dessiner des nuages. */
    private static double bosses(double x, double z) {
        int xi = Mth.floor(x), zi = Mth.floor(z);
        double xf = x - xi, zf = z - zi;
        xf = xf * xf * (3 - 2 * xf);
        zf = zf * zf * (3 - 2 * zf);
        double a = hasard(xi * 31L + zi * 7919L), b = hasard((xi + 1) * 31L + zi * 7919L);
        double c = hasard(xi * 31L + (zi + 1) * 7919L), e = hasard((xi + 1) * 31L + (zi + 1) * 7919L);
        return lp(lp(a, b, xf), lp(c, e, xf), zf);
    }

    private static void arreter() {
        actif = false;
        if (cinema) {
            sortir();
        }
    }

    private static void entrer() {
        Minecraft mc = Minecraft.getInstance();
        cinema = true;
        mc.setScreen(null);
        guiAvant = mc.options.hideGui;
        mc.options.hideGui = true;
        vueAvant = mc.options.getCameraType();
        mc.options.setCameraType(CameraType.FIRST_PERSON);
        objectif = EntityType.MARKER.create(mc.level);
        placerCamera(t());
        mc.setCameraEntity(objectif);
    }

    private static void sortir() {
        Minecraft mc = Minecraft.getInstance();
        cinema = false;
        mc.options.hideGui = guiAvant;
        if (vueAvant != null) {
            mc.options.setCameraType(vueAvant);
        }
        if (mc.player != null) {
            mc.setCameraEntity(mc.player);
        }
        objectif = null;
    }

    // ================================================================ Zeus

    /** Lit le modèle (une fois), tel que outils/zeus.mjs l'a rangé dans la texture. */
    private static boolean charger() {
        if (racine != null || rate) {
            return racine != null;
        }
        Optional<Resource> res = Minecraft.getInstance().getResourceManager().getResource(MODELE);
        if (res.isEmpty()) {
            rate = true;
            Cabane.LOGGER.warn("Modèle de Zeus introuvable ({})", MODELE);
            return false;
        }
        try (Reader lecteur = res.get().openAsReader()) {
            JsonObject j = JsonParser.parseReader(lecteur).getAsJsonObject();
            MeshDefinition mesh = new MeshDefinition();
            Map<String, PartDefinition> parties = new HashMap<>();
            for (JsonElement el : j.getAsJsonArray("parties")) {
                JsonObject p = el.getAsJsonObject();
                CubeListBuilder cubes = CubeListBuilder.create();
                for (JsonElement eb : p.getAsJsonArray("boites")) {
                    JsonObject b = eb.getAsJsonObject();
                    float[] uv = nombres(b, "uv"), coin = nombres(b, "coin"), taille = nombres(b, "taille"), gonfle = nombres(b, "gonfle");
                    cubes.texOffs((int) uv[0], (int) uv[1]).addBox(coin[0], coin[1], coin[2], taille[0], taille[1], taille[2],
                            new CubeDeformation(gonfle[0], gonfle[1], gonfle[2]));
                }
                float[] pivot = nombres(p, "pivot"), rot = nombres(p, "rotation");
                PartDefinition parent = p.get("parent").isJsonNull() ? mesh.getRoot() : parties.get(p.get("parent").getAsString());
                String nom = p.get("nom").getAsString();
                parties.put(nom, parent.addOrReplaceChild(nom, cubes, PartPose.offsetAndRotation(pivot[0], pivot[1], pivot[2], rot[0], rot[1], rot[2])));
            }
            float[] texture = nombres(j, "texture");
            ModelPart m = LayerDefinition.create(mesh, (int) texture[0], (int) texture[1]).bakeRoot();
            torse = m.getChild("torse");
            teteZ = torse.getChild("tete");
            capeZ = torse.getChild("cape");
            brasD = torse.getChild("bras_d");
            brasG = torse.getChild("bras_g");
            mainD = brasD.getChild("main_d");
            jambeD = m.getChild("jambe_d");
            jambeG = m.getChild("jambe_g");
            oeilD = teteZ.getChild("oeil_d");
            oeilG = teteZ.getChild("oeil_g");
            gemme = teteZ.getChild("gemme");
            racine = m;
            return true;
        } catch (Exception ex) {
            rate = true;
            Cabane.LOGGER.warn("Modèle de Zeus illisible : {}", ex.toString());
            return false;
        }
    }

    private static float[] nombres(JsonObject o, String cle) {
        var a = o.getAsJsonArray(cle);
        float[] f = new float[a.size()];
        for (int i = 0; i < f.length; i++) {
            f[i] = a.get(i).getAsFloat();
        }
        return f;
    }

    /** Sa pose à l'instant t (celle de l'aperçu) : il respire, relève la tête vers nous, lève le bras, lance, le rabaisse. */
    private static void poser(double t) {
        racine.getAllParts().forEach(ModelPart::resetPose);
        double br = Math.sin(t * 1.6), lean = 0.07 + 0.012 * br, hx = 0.3, hy = 0, aRx = -0.25, aLx = -0.3;
        hx = lp(hx, 0.03, lisse(entre(t, 6.55, 6.85)));
        hx = lp(hx, 0.3, lisse(entre(t, 8.4, 9.2)));
        if (t > 6.6 && t < 8.2) {
            hy = Math.sin(t * 41) * 0.03 * (1 - entre(t, 7.4, 8.2));
        }
        if (t >= 7.3) {
            aRx = lp(-0.25, -2.95, lisse(entre(t, 7.3, 8.2)));
        }
        if (t >= 9.9) {
            aRx = lp(-2.95, -3.55, lisse(entre(t, 9.9, 10.1)));
        }
        if (t >= 10.1) {
            aRx = lp(-3.55, -0.85, sortie(entre(t, 10.1, TR)));
        }
        if (t >= TR) {
            aRx = lp(-0.85, -0.5, lisse(entre(t, TR, 11.3)));
        }
        if (t >= 17.6) {
            aRx = lp(-0.5, -0.25, lisse(entre(t, 17.6, 19.2)));
        }
        if (t > 8.2 && t < 9.9) {
            aRx += Math.sin(t * 43) * 0.018;
        }
        if (t >= 10.1 && t < 11.4) {
            lean += 0.2 * Math.sin(Math.PI * entre(t, 10.1, 11.4));
        }
        if (t > 6.6 && t < 10.4) {
            aLx += Math.sin(t * 33) * 0.025;
        }
        torse.xRot += (float) lean;
        torse.y -= (float) (br * 0.24);
        teteZ.xRot += (float) hx;
        teteZ.yRot -= (float) hy;
        // Minecraft lève le bras avant de l'écarter : levé, l'écart change de sens pour que le bras reste dehors
        brasD.xRot += (float) aRx;
        brasD.zRot += (float) (0.22 * Math.cos(aRx));
        brasG.xRot += (float) aLx;
        brasG.zRot -= (float) (0.3 * Math.cos(aLx));
        jambeD.xRot += 0.08F;
        jambeG.xRot -= 0.08F;
        capeZ.xRot += (float) (0.22 + 0.07 * Math.sin(t * 2.1) + 0.04 * Math.sin(t * 5.3));
    }

    /** Comme LivingEntityRenderer : placé à ses pieds (depuis l'origine), tourné, grandi, retourné. */
    private static void repere(PoseStack ps) {
        ps.mulPose(Axis.YP.rotationDegrees(180F - lacetZeus));
        ps.scale(ECHELLE, ECHELLE, ECHELLE);
        ps.scale(-1F, -1F, 1F);
        ps.translate(0F, -1.501F, 0F);
    }

    /** Un point de sa main droite (en pixels du modèle, le long du bras) à l'instant t, dans le monde. */
    private static Vec3 main(double t, float le) {
        poser(t);
        PoseStack ps = new PoseStack();
        repere(ps);
        torse.translateAndRotate(ps);
        brasD.translateAndRotate(ps);
        mainD.translateAndRotate(ps);
        Vector3f v = ps.last().pose().transformPosition(new Vector3f(0, le / 16F, 0));
        return z0.add(v.x, v.y, v.z);
    }

    private static Vec3 main(double t) {
        return main(t, 0);
    }

    private static void dessinerZeus(PoseStack pose, MultiBufferSource buffers, Vec3 cam, double t, float flash) {
        poser(t);
        pose.pushPose();
        pose.translate(z0.x - cam.x, z0.y - cam.y, z0.z - cam.z);
        repere(pose);
        int teinte = couleur(1F, 0.9F + 0.1F * flash, 0.82F + 0.18F * flash);
        oeilD.visible = oeilG.visible = gemme.visible = false;
        racine.render(pose, buffers.getBuffer(RenderType.entityCutoutNoCull(TEXTURE)), LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY, teinte);
        // ses yeux et la pierre de sa couronne, en pleine lumière
        oeilD.visible = oeilG.visible = gemme.visible = true;
        VertexConsumer c = buffers.getBuffer(RenderType.entityCutoutNoCull(TEXTURE));
        torse.translateAndRotate(pose);
        teteZ.translateAndRotate(pose);
        for (ModelPart p : new ModelPart[] {oeilD, oeilG, gemme}) {
            p.render(pose, c, LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY, 0xFFFFFFFF);
        }
        pose.popPose();
    }

    private static int couleur(float r, float g, float b) {
        return 0xFF000000 | ((int) (Mth.clamp(r, 0, 1) * 255) << 16) | ((int) (Mth.clamp(g, 0, 1) * 255) << 8) | (int) (Mth.clamp(b, 0, 1) * 255);
    }

    // ================================================================ le dessin

    /**
     * Le rendu maison de la lueur des éclairs (additive, comme ceux de Minecraft). Elle écrit la profondeur : dans ce pack,
     * le ciel peint peut être dessiné après elle, et sinon il la recouvrirait.
     */
    private static final class Types extends RenderType {
        static final RenderType LUMIERE = create("cabane_zeus_lumiere", DefaultVertexFormat.POSITION_COLOR, VertexFormat.Mode.QUADS, 8192, false, true,
                CompositeState.builder().setShaderState(RENDERTYPE_LIGHTNING_SHADER).setWriteMaskState(COLOR_DEPTH_WRITE).setTransparencyState(LIGHTNING_TRANSPARENCY)
                        .setCullState(NO_CULL).createCompositeState(false));
        private Types() {
            super("cabane_zeus_rien", DefaultVertexFormat.POSITION, VertexFormat.Mode.QUADS, 256, false, false, () -> { }, () -> { });
        }
    }

    private static void sommet(VertexConsumer c, Matrix4f m, Vec3 p, float r, float g, float b, float a) {
        c.addVertex(m, (float) p.x, (float) p.y, (float) p.z).setColor(r, g, b, a);
    }

    private static Vec3[] base(Vec3 d) {
        Vec3 n = d.normalize();
        Vec3 a = Math.abs(n.y) < 0.9 ? HAUT : new Vec3(1, 0, 0);
        Vec3 u = n.cross(a).normalize();
        return new Vec3[] {u, n.cross(u).normalize()};
    }

    /** Un éclair comme celui de Minecraft : des pavés lumineux le long de la ligne brisée, en trois couches. */
    private static void eclair(VertexConsumer c, Matrix4f m, Vec3 cam, List<Vec3> pts, double epaisseur, float force) {
        if (pts.size() < 2 || force <= 0.01F) {
            return;
        }
        double[][] couches = {{0.32, 0.95, 0.97, 1.0, 0.95}, {0.85, 0.42, 0.5, 0.95, 0.5}, {1.9, 0.18, 0.24, 0.7, 0.28}};
        for (double[] k : couches) {
            double w = k[0] * epaisseur / 2;
            float a = (float) Math.min(1, k[4] * force);
            for (int i = 0; i + 1 < pts.size(); i++) {
                Vec3 p = pts.get(i).subtract(cam), q = pts.get(i + 1).subtract(cam);
                if (p.distanceToSqr(q) < 1.0E-6) {
                    continue;
                }
                Vec3[] uw = base(q.subtract(p));
                Vec3 u = uw[0].scale(w), v = uw[1].scale(w);
                Vec3[] o = {u.add(v), u.subtract(v), u.scale(-1).subtract(v), v.subtract(u)};
                for (int s = 0; s < 4; s++) {
                    Vec3 o0 = o[s], o1 = o[(s + 1) % 4];
                    sommet(c, m, p.add(o0), (float) k[1], (float) k[2], (float) k[3], a);
                    sommet(c, m, q.add(o0), (float) k[1], (float) k[2], (float) k[3], a);
                    sommet(c, m, q.add(o1), (float) k[1], (float) k[2], (float) k[3], a);
                    sommet(c, m, p.add(o1), (float) k[1], (float) k[2], (float) k[3], a);
                }
            }
        }
    }

    /** Un pavé plein d'une couleur, ombré comme les nuages de Minecraft (dessus clair, dessous sombre). */
    private static void pave(VertexConsumer c, Matrix4f m, double x0, double y0, double z0, double x1, double y1, double z1, float r, float g, float b, float a,
            boolean cotes) {
        float[] o = {1F, 0.7F, 0.9F, 0.82F};
        quad(c, m, x0, y1, z0, x1, y1, z0, x1, y1, z1, x0, y1, z1, r * o[0], g * o[0], b * o[0], a);
        quad(c, m, x0, y0, z0, x0, y0, z1, x1, y0, z1, x1, y0, z0, r * o[1], g * o[1], b * o[1], a);
        if (cotes) {
            quad(c, m, x0, y0, z0, x1, y0, z0, x1, y1, z0, x0, y1, z0, r * o[3], g * o[3], b * o[3], a);
            quad(c, m, x0, y0, z1, x0, y1, z1, x1, y1, z1, x1, y0, z1, r * o[3], g * o[3], b * o[3], a);
            quad(c, m, x0, y0, z0, x0, y1, z0, x0, y1, z1, x0, y0, z1, r * o[2], g * o[2], b * o[2], a);
            quad(c, m, x1, y0, z0, x1, y0, z1, x1, y1, z1, x1, y1, z0, r * o[2], g * o[2], b * o[2], a);
        }
    }

    private static void quad(VertexConsumer c, Matrix4f m, double ax, double ay, double az, double bx, double by, double bz, double cx, double cy, double cz,
            double dx, double dy, double dz, float r, float g, float b, float a) {
        c.addVertex(m, (float) ax, (float) ay, (float) az).setColor(r, g, b, a);
        c.addVertex(m, (float) bx, (float) by, (float) bz).setColor(r, g, b, a);
        c.addVertex(m, (float) cx, (float) cy, (float) cz).setColor(r, g, b, a);
        c.addVertex(m, (float) dx, (float) dy, (float) dz).setColor(r, g, b, a);
    }

    /**
     * Le ciel du coucher de soleil, peint sur celui de Minecraft : une grande sphère autour de la caméra, presque au bout de
     * la vue, qui ne passe que là où rien d'autre n'a été dessiné (et n'écrit pas la profondeur : tout le reste passe devant).
     */
    private static void dessinerCiel(Matrix4f vue, float opacite) {
        if (opacite <= 0.01F) {
            return;
        }
        // dessiné tout de suite (pas en lot, que le jeu pourrait dessiner après l'éclair et le recouvrir)
        com.mojang.blaze3d.vertex.BufferBuilder c = com.mojang.blaze3d.vertex.Tesselator.getInstance().begin(VertexFormat.Mode.QUADS,
                DefaultVertexFormat.POSITION_COLOR);
        Vec3 soleil = horizontal(avantZ.scale(-1)).yRot(0.75F);
        int lat = 12, lon = 32;
        double r = Math.min(600, Minecraft.getInstance().gameRenderer.getDepthFar() * 0.85);
        for (int i = 0; i < lat; i++) {
            double p0 = -Math.PI / 2 + Math.PI * i / lat, p1 = -Math.PI / 2 + Math.PI * (i + 1) / lat;
            for (int j = 0; j < lon; j++) {
                double q0 = Math.PI * 2 * j / lon, q1 = Math.PI * 2 * (j + 1) / lon;
                double[][] coins = {{p0, q0}, {p1, q0}, {p1, q1}, {p0, q1}};
                for (double[] k : coins) {
                    Vec3 d = new Vec3(Math.cos(k[0]) * Math.cos(k[1]), Math.sin(k[0]), Math.cos(k[0]) * Math.sin(k[1]));
                    float[] col = couleurCiel(d, soleil);
                    c.addVertex(vue, (float) (d.x * r), (float) (d.y * r), (float) (d.z * r)).setColor(col[0], col[1], col[2], opacite);
                }
            }
        }
        // le soleil couchant, bas sur l'horizon
        Vec3 s = soleil.scale(r * 0.88).add(0, r * 0.078, 0);
        Vec3[] uw = base(s);
        for (double k : new double[] {9, 5}) {
            Vec3 u = uw[0].scale(k * r / 90), v = uw[1].scale(k * r / 90);
            float a = (float) (k > 6 ? 0.35 : 1) * opacite;
            quad(c, vue, s.x + u.x + v.x, s.y + u.y + v.y, s.z + u.z + v.z, s.x + u.x - v.x, s.y + u.y - v.y, s.z + u.z - v.z, s.x - u.x - v.x, s.y - u.y - v.y,
                    s.z - u.z - v.z, s.x - u.x + v.x, s.y - u.y + v.y, s.z - u.z + v.z, 1F, k > 6 ? 0.7F : 0.92F, k > 6 ? 0.35F : 0.7F, a);
        }
        var donnees = c.build();
        if (donnees == null) {
            return;
        }
        com.mojang.blaze3d.systems.RenderSystem.setShader(net.minecraft.client.renderer.GameRenderer::getPositionColorShader);
        com.mojang.blaze3d.systems.RenderSystem.setShaderColor(1F, 1F, 1F, 1F);
        com.mojang.blaze3d.systems.RenderSystem.enableBlend();
        com.mojang.blaze3d.systems.RenderSystem.defaultBlendFunc();
        com.mojang.blaze3d.systems.RenderSystem.enableDepthTest();
        com.mojang.blaze3d.systems.RenderSystem.depthFunc(515);
        com.mojang.blaze3d.systems.RenderSystem.depthMask(false);
        com.mojang.blaze3d.systems.RenderSystem.disableCull();
        com.mojang.blaze3d.vertex.BufferUploader.drawWithShader(donnees);
        com.mojang.blaze3d.systems.RenderSystem.enableCull();
        com.mojang.blaze3d.systems.RenderSystem.depthMask(true);
        com.mojang.blaze3d.systems.RenderSystem.disableBlend();
    }

    private static float[] couleurCiel(Vec3 d, Vec3 soleil) {
        double h = Mth.clamp(d.y, 0, 1);
        float[] haut = {0.043F, 0.055F, 0.133F}, hor = {0.878F, 0.47F, 0.227F}, lueur = {1F, 0.54F, 0.227F};
        double k = Math.pow(h, 0.5);
        float[] c = new float[3];
        double face = Math.pow(Math.max(0, horizontal(d).dot(soleil)), 5) * Math.exp(-h * 6);
        for (int i = 0; i < 3; i++) {
            c[i] = (float) Math.min(1, lp(hor[i], haut[i], k) + lueur[i] * face * 0.8);
        }
        if (d.y < 0) {
            for (int i = 0; i < 3; i++) {
                c[i] = (float) lp(hor[i], hor[i] * 0.55, Math.min(1, -d.y * 3));
            }
        }
        return c;
    }

    private static void dessiner(PoseStack pose, Vec3 cam, double t) {
        Minecraft mc = Minecraft.getInstance();
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        float flash = eclat(t);
        if (cinema && !sol) {
            dessinerCiel(pose.last().pose(), (float) (1 - versSol));
            dessinerZeus(pose, buffers, cam, t, flash);
            templeEnBlocs(pose, buffers, cam);
            buffers.endBatch();
            VertexConsumer c = buffers.getBuffer(RenderType.debugQuads());
            Matrix4f m = pose.last().pose();
            nuagesDuCiel(c, m, cam, t, flash);
            buffers.endBatch(RenderType.debugQuads());
        }
        VertexConsumer l = buffers.getBuffer(Types.LUMIERE);
        Matrix4f m = pose.last().pose();
        // l'éclair tenu dans sa main
        if (cinema && !sol && t > 8.35 && t < TR) {
            double g = sortie(entre(t, 8.35, 9.1));
            Vec3 a = main(t, -8F), b = main(t, 38F);
            List<Vec3> pts = new ArrayList<>(List.of(zig(a, a.lerp(b, Math.max(0.05, g)), 9, 1.1 * PX, new Random(4))));
            eclair(l, m, cam, pts, 0.75 * PX, (float) (0.9 + 0.25 * hasard((long) (t * 30))));
        }
        // l'éclair lancé, puis l'éclair entier qui reste et vacille
        if (t >= TR && t < 20.4) {
            double fr = avance(t);
            float a = force(t);
            eclair(l, m, cam, jusque(fr), 1.7 * largeur, a);
            for (Branche b : branches) {
                if (fr >= b.f()) {
                    int n = 1 + (int) Math.ceil(Mth.clamp((fr - b.f()) / 0.05, 0, 1) * (b.pts().length - 1));
                    eclair(l, m, cam, List.of(b.pts()).subList(0, Math.min(b.pts().length, n + 1)), 1.0 * largeur, a * 0.85F);
                }
            }
        }
        if (cinema && !sol) {
            for (int i = 0; i < FOND.length; i++) {
                if (t >= FOND[i] && t < FOND[i] + 0.26) {
                    eclair(l, m, cam, List.of(fond.get(i)), 1.7 * PX / 1.3, (float) (0.9 + 0.3 * hasard((long) (t * 40))));
                }
            }
        }
        arcs(l, m, cam, t);
        buffers.endBatch(Types.LUMIERE);
        if (cinema && premiere) {
            mains(pose, buffers, t);
        }
    }

    /** L'éclair, plus ou moins vif : il vacille, puis s'éteint ; au départ de l'impact, plusieurs coups très forts. */
    private static float force(double t) {
        if (t < THIT) {
            return (float) (0.9 + 0.1 * hasard((long) (t * 40) + 3));
        }
        double d = t - THIT, a = 0.5 + 0.4 * hasard((long) (t * 24) + 9);
        for (double f : new double[] {0, 0.18, 0.42, 0.75, 2.6, 2.85}) {
            if (d >= f && d < f + 0.1) {
                a = 1.35;
            }
        }
        return (float) (a * (1 - entre(t, 20, 20.6)));
    }

    /** Le flash de lumière des éclairs, sur toute la scène. */
    private static float eclat(double t) {
        double[][] coups = {{TR, 0.7}, {THIT, 1.4}, {THIT + 0.18, 0.9}, {THIT + 0.42, 0.8}, {17.2, 0.9}, {17.45, 0.6}, {8.6, 0.25}, {9.15, 0.3}, {9.62, 0.25}};
        double f = 0;
        for (double[] c : coups) {
            if (t >= c[0]) {
                f = Math.max(f, c[1] * Math.exp(-(t - c[0]) * 9));
            }
        }
        for (double b : FOND) {
            if (t >= b) {
                f = Math.max(f, 0.35 * Math.exp(-(t - b) * 9));
            }
        }
        return (float) f;
    }

    /** Les petits éclairs qui courent sur Zeus (et sur sa main quand il tient l'orage), puis sur la cible après l'impact. */
    private static void arcs(VertexConsumer c, Matrix4f m, Vec3 cam, double t) {
        double k = PX / 1.3;
        if (cinema && !sol && (t >= 2 && t < 11.5 || t >= 18.6)) {
            long st = (long) (t / 0.085);
            boolean rage = t > 6.6 && t < 10.5;
            for (int i = 0; i < (rage ? 4 : 2); i++) {
                Random r = new Random(st * 13 + i);
                if (r.nextDouble() < 0.3) {
                    continue;
                }
                double ax = (r.nextDouble() - 0.5) * 12, ay = 6 + r.nextDouble() * 26, az = (r.nextDouble() - 0.5) * 7;
                Vec3 a = zl(ax, ay, az), b = zl(ax + (r.nextDouble() - 0.5) * 9, ay + (r.nextDouble() - 0.5) * 9, az + (r.nextDouble() - 0.5) * 5);
                eclair(c, m, cam, List.of(zig(a, b, 5, 1.1 * k, r)), 0.32 * k, 0.9F);
            }
            if (t > 8.35 && t < TR) {
                Vec3 h = main(t);
                for (int i = 0; i < 2; i++) {
                    Random r = new Random(st * 29 + i + 500);
                    Vec3 b = h.add((r.nextDouble() - 0.5) * 20 * k, (r.nextDouble() - 0.5) * 16 * k, (r.nextDouble() - 0.5) * 20 * k);
                    eclair(c, m, cam, List.of(zig(h, b, 5, 1.2 * k, r)), 0.32 * k, 1F);
                }
            }
        }
        if (t >= THIT && t < 18 && !premiere) {
            long st = (long) (t / 0.07);
            for (int i = 0; i < 3; i++) {
                Random r = new Random(st * 17 + i + 900);
                if (r.nextDouble() < 0.35) {
                    continue;
                }
                Vec3 a = pieds.add((r.nextDouble() - 0.5) * 1.1, 0.2 + r.nextDouble() * 1.7, (r.nextDouble() - 0.5) * 1.1);
                Vec3 b = a.add((r.nextDouble() - 0.5) * 1.2, (r.nextDouble() - 0.5) * 1.2, (r.nextDouble() - 0.5) * 1.2);
                eclair(c, m, cam, List.of(zig(a, b, 4, 0.25, r)), 0.32, (float) (0.8 * (1 - entre(t, 16.5, 18))));
            }
        }
    }

    /** La mer de nuages du ciel, le plafond d'orage, les nuages qui tournent autour de Zeus et ceux que sa main aspire. */
    private static void nuagesDuCiel(VertexConsumer c, Matrix4f m, Vec3 cam, double t, float flash) {
        float f = 1 + flash * 0.6F;
        List<int[]> cases = percee1 >= 0 && t >= percee1 ? mer2 : mer1;
        double y0 = mer - cam.y, y1 = mer + 8 - cam.y;
        for (int[] k : cases) {
            double x0 = z0.x + (k[0] - 0.5) * CASE - cam.x, zz0 = z0.z + (k[1] - 0.5) * CASE - cam.z;
            pave(c, m, x0, y0, zz0, x0 + CASE, y1, zz0 + CASE, Math.min(1, 0.98F * f), Math.min(1, 0.8F * f), Math.min(1, 0.68F * f), 0.92F, bord(cases, k));
        }
        double yp = z0.y + 250 - cam.y;
        for (int[] k : plafond) {
            double x0 = z0.x + (k[0] - 0.5) * CASE_PLAFOND - cam.x, zz0 = z0.z + (k[1] - 0.5) * CASE_PLAFOND - cam.z;
            pave(c, m, x0, yp, zz0, x0 + CASE_PLAFOND, yp + 18, zz0 + CASE_PLAFOND, 0.22F * f, 0.23F * f, 0.33F * f, 0.95F, true);
        }
        double rot = t * 0.12 + entre(t, 6.6, 10.4) * 0.8;
        for (double[] n : tourbillon) {
            double a = n[0] + rot;
            double cx = z0.x + Math.cos(a) * n[1] - cam.x, cy = z0.y + n[2] - cam.y, cz = z0.z + Math.sin(a) * n[1] - cam.z;
            double hw = n[3] / 2, hh = n[4] / 2, hd = n[5] / 2;
            pave(c, m, cx - hw, cy - hh, cz - hd, cx + hw, cy + hh, cz + hd, 0.3F * f, 0.31F * f, 0.37F * f, 0.92F, true);
        }
        Vec3 h = main(t);
        for (double[] g : aspires) {
            double kk = entre(t, 7.5 + g[0] * 0.9, 8.7 + g[0] * 0.6);
            if (kk <= 0 || kk >= 1) {
                continue;
            }
            double r = g[2] * (1 - lisse(kk)), an = g[1] + kk * 5, s = g[4] * (1 - kk * 0.75) / 2;
            double cx = h.x + Math.cos(an) * r - cam.x, cy = h.y + g[3] * (1 - kk) + 2 * (1 - kk) - cam.y, cz = h.z + Math.sin(an) * r - cam.z;
            float clair = (float) (kk * kk);
            pave(c, m, cx - s, cy - s, cz - s, cx + s, cy + s, cz + s, (float) lp(0.32, 1, clair), (float) lp(0.33, 1, clair), (float) lp(0.43, 1, clair), 0.95F, true);
        }
    }

    /** Une case de nuage a-t-elle un bord ouvert (pour ne dessiner les côtés qu'au bord) ? */
    private static boolean bord(List<int[]> cases, int[] k) {
        java.util.Set<Long> pleines = cases == mer1 ? pleines1 : pleines2;
        return !(pleines.contains(cle(k[0] + 1, k[1])) && pleines.contains(cle(k[0] - 1, k[1])) && pleines.contains(cle(k[0], k[1] + 1))
                && pleines.contains(cle(k[0], k[1] - 1)));
    }

    private static final java.util.Set<Long> pleines1 = new java.util.HashSet<>(), pleines2 = new java.util.HashSet<>();

    private static long cle(int i, int j) {
        return ((long) i << 32) ^ (j & 0xFFFFFFFFL);
    }

    /** Le temple de l'Olympe aux pieds de Zeus, en blocs de quartz et d'or étirés (dessinés, jamais posés). */
    private static void templeEnBlocs(PoseStack pose, MultiBufferSource buffers, Vec3 cam) {
        var rendu = Minecraft.getInstance().getBlockRenderer();
        double cx = Math.round(temple.x), cz = Math.round(temple.z), tb = mer + 8;
        int s = 12;
        List<Object[]> blocs = new ArrayList<>();
        for (int i = -s - 2; i <= s + 2; i += 6) {
            for (int j = -s - 2; j <= s + 2; j += 6) {
                blocs.add(new Object[] {Blocks.QUARTZ_BLOCK.defaultBlockState(), cx + i, tb - 1, cz + j, 6.0, 1.0, 6.0});
            }
        }
        for (int i = -s; i <= s; i += 3) {
            for (int[] p : new int[][] {{i, -s}, {i, s}, {-s, i}, {s, i}}) {
                blocs.add(new Object[] {Blocks.QUARTZ_PILLAR.defaultBlockState(), cx + p[0], tb, cz + p[1], 1.0, 19.0, 1.0});
            }
        }
        for (int i = -s; i <= s; i += 5) {
            for (int j = -s; j <= s; j += 5) {
                blocs.add(new Object[] {Blocks.CHISELED_QUARTZ_BLOCK.defaultBlockState(), cx + i, tb + 19, cz + j, 5.0, 1.0, 5.0});
            }
        }
        for (int k = 0; k < 6; k++) {
            double w = 2 * s - 1 - 4 * k;
            blocs.add(new Object[] {Blocks.QUARTZ_BLOCK.defaultBlockState(), cx - w / 2 + 0.5, tb + 20 + k, cz - w / 2 + 0.5, w, 1.0, w});
        }
        for (int sgn : new int[] {-1, 1}) {
            blocs.add(new Object[] {Blocks.GOLD_BLOCK.defaultBlockState(), cx - s, tb + 20, cz + sgn * s, 2.0 * s + 1, 0.6, 1.0});
            blocs.add(new Object[] {Blocks.GOLD_BLOCK.defaultBlockState(), cx + sgn * s, tb + 20, cz - s, 1.0, 0.6, 2.0 * s + 1});
        }
        blocs.add(new Object[] {Blocks.GOLD_BLOCK.defaultBlockState(), cx - 2, tb + 1, cz - 2, 5.0, 2.0, 5.0});
        for (Object[] b : blocs) {
            pose.pushPose();
            pose.translate((double) b[1] - 0.5 - cam.x, (double) b[2] - cam.y, (double) b[3] - 0.5 - cam.z);
            pose.scale((float) (double) b[4], (float) (double) b[5], (float) (double) b[6]);
            rendu.renderSingleBlock((BlockState) b[0], pose, buffers, LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY);
            pose.popPose();
        }
    }

    /** Les deux mains de la cible, levées devant ses yeux, qui crépitent (à la première personne). */
    private static void mains(PoseStack pose, MultiBufferSource.BufferSource buffers, double t) {
        Minecraft mc = Minecraft.getInstance();
        if (!(mc.player instanceof AbstractClientPlayer moi)) {
            return;
        }
        if (brasLarges == null) {
            brasLarges = new PlayerModel<>(mc.getEntityModels().bakeLayer(ModelLayers.PLAYER), false);
            brasFins = new PlayerModel<>(mc.getEntityModels().bakeLayer(ModelLayers.PLAYER_SLIM), true);
        }
        PlayerModel<Player> modele = moi.getSkin().model() == PlayerSkin.Model.SLIM ? brasFins : brasLarges;
        VertexConsumer c = buffers.getBuffer(RenderType.entityTranslucent(moi.getSkin().texture()));
        Quaternionf rot = mc.gameRenderer.getMainCamera().rotation();
        double bas = lisse(entre(t, 27.4, 28.6)) * 0.13, tremble = 0.012 + 0.05 * (1 - entre(t, MAINS, 30));
        for (int s : new int[] {1, -1}) {
            Vector3f epaule = new Vector3f(s * 0.4F, -0.6F, -0.25F), main = new Vector3f(s * 0.14F, (float) (-0.06 - bas), -0.66F);
            Vector3f d = new Vector3f(main).sub(epaule).normalize();
            Random r = new Random((long) (t * 30) * 3 + (s > 0 ? 1 : 2));
            pose.pushPose();
            pose.mulPose(rot);
            pose.translate(epaule.x, epaule.y, epaule.z);
            pose.mulPose(new Quaternionf().rotationTo(new Vector3f(0, 1, 0), d));
            pose.mulPose(Axis.XP.rotation((float) ((r.nextDouble() - 0.5) * tremble)));
            pose.mulPose(Axis.ZP.rotation((float) ((r.nextDouble() - 0.5) * tremble)));
            pose.mulPose(Axis.YP.rotation(s * 0.5F));
            ModelPart bras = s > 0 ? modele.rightArm : modele.leftArm, manche = s > 0 ? modele.rightSleeve : modele.leftSleeve;
            for (ModelPart p : new ModelPart[] {bras, manche}) {
                p.resetPose();
                p.x = s > 0 ? 1F : -1F;
                p.y = 2F;
                p.z = 0F;
                p.render(pose, c, LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY);
            }
            pose.popPose();
        }
        buffers.endBatch();
    }

    // ================================================================ la caméra

    /** Recule la caméra devant un mur : de la tête de la cible vers où on voudrait la mettre. */
    private static Vec3 libre(Vec3 voulu) {
        Minecraft mc = Minecraft.getInstance();
        Vec3 depuis = pieds.add(0, 1.6, 0);
        BlockHitResult hit = mc.level.clip(new ClipContext(depuis, voulu, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, CollisionContext.empty()));
        if (hit.getType() == HitResult.Type.MISS) {
            return voulu;
        }
        Vec3 d = voulu.subtract(depuis);
        return hit.getLocation().subtract(d.normalize().scale(0.4));
    }

    private static void plan(double t) {
        sol = false;
        premiere = false;
        versSol = 0;
        largeur = 1;
        double shk = 0;
        if (t < 2) {
            double k = lisse(t / 2);
            camera = libre(pieds.add(droite.scale(7 - k * 2)).add(0, 26 - k * 12, 0).add(devant.scale(-(36 - k * 12))));
            vise = pieds.add(droite.scale(-2)).add(0, 1 - k, 0).add(devant.scale(14 - k * 8));
            fov = 62;
            sol = true;
            versSol = 1;
        } else if (t < 6.5) {
            double k = lisse(entre(t, 2, 6.5));
            camera = zl(lp(-12, -6, k), lp(7, 8, k), lp(50, 36, k));
            vise = zl(0, lp(20, 24, k), 0);
            fov = (float) lp(64, 56, k);
        } else if (t < 8.3) {
            double k = entre(t, 6.5, 8.3);
            camera = zl(lp(3, 1.5, k), lp(24.5, 25.5, k), lp(36, 28, sortie(k)));
            vise = zl(0, 27.6, 1.6);
            fov = 46;
            shk = t > 6.6 && t < 7.6 ? 1.2 * (1 - entre(t, 6.6, 7.6)) : 0;
        } else if (t < 9.5) {
            double k = entre(t, 8.3, 9.5);
            camera = zl(lp(-17, -19, k), lp(14, 13, k), lp(30, 27, k));
            vise = zl(-5, lp(31, 33, k), 2);
            fov = 56;
            shk = 0.3;
        } else if (t < 10.9) {
            double k = entre(t, 9.5, 10.9);
            camera = zl(-66 + k * 5, 18, 10);
            vise = zl(0, 23, 7);
            fov = 50;
            shk = t > TR ? 4 * (1 - entre(t, TR, 10.9)) : 0;
        } else if (t < 14.35) {
            Vec3 b = poing.add(sens.scale(avance(t) * poing.distanceTo(tete)));
            List<Vec3> h = jusque(avance(t));
            camera = b.add(sens.scale(-6)).add(0, 2, 0).add(cote.scale(18));
            vise = b.add(sens.scale(8)).lerp(h.get(h.size() - 1), 0.6);
            fov = 74;
            shk = 0.15;
            largeur = 0.5;
            versSol = entre(camera.y, 206, 182);
            sol = camera.y < 185;
        } else if (t < 17) {
            camera = libre(pieds.add(devant.scale(6.6)).add(droite.scale(-2.6)).add(0, 1.1, 0));
            Vec3 haut = poing.subtract(pieds).normalize();
            vise = pieds.add(0, 1.7, 0).lerp(pieds.add(haut.scale(45)), lisse(entre(t, 15.6, 17)));
            fov = 76;
            sol = true;
            versSol = 1;
            shk = t > THIT ? 1.6 * Math.pow(1 - entre(t, THIT, 15.5), 2) : 0;
        } else if (t < MAINS) {
            // on remonte tout le trajet de l'éclair, du village jusqu'à Zeus
            double k = lisse(entre(t, 17, 20.2));
            Vec3 depart = libre(pieds.add(cote.scale(22)).add(devant.scale(-4)).add(0, 3, 0));
            camera = depart.lerp(zl(-23, 7, 47), k);
            Vec3 p = tete.lerp(poing, Math.min(1, k + 0.1));
            vise = p.lerp(zl(0, 23, 1), lisse(entre(k, 0.55, 1)));
            fov = (float) lp(74, 56, k);
            versSol = entre(camera.y, 206, 182);
            largeur = lp(0.8, 2.4, k);
            sol = camera.y < 185;
            shk = t > 17.2 && t < 17.6 ? 0.4 : 0;
        } else {
            premiere = true;
            sol = true;
            versSol = 1;
            camera = pieds.add(0, 1.62, 0);
            double p = -0.36;
            vise = camera.add(devant.scale(Math.cos(p))).add(0, Math.sin(p), 0);
            fov = 70;
            shk = t < 22.2 ? 0.05 * (1 - entre(t, MAINS, 22.2)) : 0;
        }
        camera = camera.add((SECOUSSE.nextDouble() - 0.5) * shk, (SECOUSSE.nextDouble() - 0.5) * shk, (SECOUSSE.nextDouble() - 0.5) * shk);
    }

    private static void placerCamera(double t) {
        plan(t);
        if (objectif == null) {
            return;
        }
        Vec3 d = vise.subtract(camera);
        float lac = (float) Math.toDegrees(Math.atan2(-d.x, d.z));
        float tangage = (float) Math.toDegrees(-Math.atan2(d.y, Math.sqrt(d.x * d.x + d.z * d.z)));
        objectif.setPos(camera.x, camera.y, camera.z);
        objectif.xo = objectif.xOld = camera.x;
        objectif.yo = objectif.yOld = camera.y;
        objectif.zo = objectif.zOld = camera.z;
        objectif.setYRot(lac);
        objectif.setXRot(tangage);
        objectif.yRotO = lac;
        objectif.xRotO = tangage;
    }

    // ================================================================ les sons, les particules

    private static boolean passe(double a, double t, double quand) {
        return quand >= 0 && a < quand && t >= quand;
    }

    private static void son(SoundEvent e, float volume, float hauteur) {
        Minecraft mc = Minecraft.getInstance();
        if (cinema) {
            mc.getSoundManager().play(SimpleSoundInstance.forUI(e, hauteur, volume));
        } else {
            mc.level.playLocalSound(pieds.x, pieds.y, pieds.z, e, SoundSource.WEATHER, volume * 4, hauteur, false);
        }
    }

    private static void gerbe(ParticleOptions type, Vec3 p, int n, double d, double vitesse) {
        Minecraft mc = Minecraft.getInstance();
        Random r = new Random();
        for (int i = 0; i < n; i++) {
            mc.level.addAlwaysVisibleParticle(type, true, p.x + (r.nextDouble() - 0.5) * d, p.y + (r.nextDouble() - 0.5) * d, p.z + (r.nextDouble() - 0.5) * d,
                    (r.nextDouble() - 0.5) * vitesse, r.nextDouble() * vitesse, (r.nextDouble() - 0.5) * vitesse);
        }
    }

    private static void evenements(double a, double t) {
        Minecraft mc = Minecraft.getInstance();
        if (cinema) {
            double[][] sons = {{2.05, 0, 0.55, 0.85}, {3.95, 0, 0.45, 1}, {5.3, 0, 0.35, 0.9}, {6.6, 1, 0.9, 0.55}, {6.7, 0, 0.6, 0.7}, {8.4, 2, 0.5, 0.6},
                    {8.9, 3, 0.5, 0.7}, {9.4, 2, 0.5, 0.75}, {10.36, 0, 1, 1.05}, {10.4, 4, 0.4, 1.6}, {percee1, 0, 0.6, 0.85}, {17.2, 0, 0.6, 0.75},
                    {20.7, 3, 0.45, 1.4}, {21.5, 2, 0.35, 1.5}, {22.6, 3, 0.3, 1.6}, {24, 2, 0.25, 1.7}};
            SoundEvent[] quoi = {SoundEvents.LIGHTNING_BOLT_THUNDER, SoundEvents.ENDER_DRAGON_GROWL, SoundEvents.FIREWORK_ROCKET_TWINKLE,
                    SoundEvents.FIREWORK_ROCKET_TWINKLE_FAR, SoundEvents.LIGHTNING_BOLT_IMPACT};
            for (double[] s : sons) {
                if (passe(a, t, s[0])) {
                    son(quoi[(int) s[1]], (float) s[2], (float) s[3]);
                }
            }
        }
        if (passe(a, t, percee2)) {
            son(SoundEvents.LIGHTNING_BOLT_THUNDER, 0.7F, 1.1F);
            gerbe(ParticleTypes.EXPLOSION, point2, 8, 10, 0);
            gerbe(ParticleTypes.CLOUD, point2, 40, 12, 0.5);
        }
        if (cinema && passe(a, t, percee1)) {
            gerbe(ParticleTypes.EXPLOSION, point1, 12, 30, 0);
        }
        if (passe(a, t, THIT)) {
            son(SoundEvents.LIGHTNING_BOLT_IMPACT, 1F, 0.65F);
            son(SoundEvents.LIGHTNING_BOLT_THUNDER, 1F, 0.95F);
            son(SoundEvents.PLAYER_HURT, 0.9F, 1F);
            Vec3 p = pieds.add(0, 0.1, 0);
            gerbe(ParticleTypes.FLASH, pieds.add(0, 1, 0), 2, 0.2, 0);
            gerbe(ParticleTypes.ELECTRIC_SPARK, p.add(0, 0.6, 0), 110, 1.2, 0.9);
            gerbe(ParticleTypes.LARGE_SMOKE, p, 30, 1.5, 0.12);
            gerbe(ParticleTypes.CLOUD, p, 20, 2, 0.2);
            if (mc.level.getPlayerByUUID(cible) instanceof Player j) {
                j.hurtTime = j.hurtDuration = 10;
            }
        }
        for (double f : new double[] {0.18, 0.42}) {
            if (passe(a, t, THIT + f)) {
                gerbe(ParticleTypes.ELECTRIC_SPARK, pieds.add(0, 0.7, 0), 45, 1.2, 0.8);
            }
        }
        if (passe(a, t, THIT + 0.3)) {
            gerbe(ParticleTypes.LARGE_SMOKE, pieds.add(0, 0.2, 0), 20, 1.5, 0.1);
        }
        // les picotements : des étincelles sur les deux mains, de moins en moins
        if (cinema && t >= MAINS && t < DUREE - 0.5) {
            Quaternionf rot = mc.gameRenderer.getMainCamera().rotation();
            Vec3 o = mc.gameRenderer.getMainCamera().getPosition();
            double k = entre(t, MAINS, 31), bas = lisse(entre(t, 27.4, 28.6)) * 0.13;
            Random r = new Random();
            for (int s : new int[] {1, -1}) {
                if (r.nextDouble() < k * 0.75) {
                    continue;
                }
                Vector3f v = rot.transform(new Vector3f((float) (s * (0.16 + (r.nextDouble() - 0.5) * 0.08)), (float) (-0.12 - bas + (r.nextDouble() - 0.5) * 0.06),
                        (float) (-0.62 + (r.nextDouble() - 0.5) * 0.05)));
                mc.level.addAlwaysVisibleParticle(ParticleTypes.ELECTRIC_SPARK, true, o.x + v.x, o.y + v.y, o.z + v.z, (r.nextDouble() - 0.5) * 0.02,
                        r.nextDouble() * 0.02, (r.nextDouble() - 0.5) * 0.02);
            }
        }
    }

    // ================================================================ l'écran (comme les autres cinématiques : bandes, titre, répliques)

    private static void ecran(GuiGraphics g) {
        Minecraft mc = Minecraft.getInstance();
        if (!cinema || mc.player == null) {
            return;
        }
        double t = t();
        int w = g.guiWidth(), h = g.guiHeight();
        double blanc = 0, noir = 0;
        if (t >= THIT && t < THIT + 0.7) {
            blanc = Math.pow(1 - entre(t, THIT, THIT + 0.7), 1.6);
        }
        if (t >= MAINS && t < 21.4) {
            blanc = Math.max(blanc, 1 - entre(t, MAINS, 21.4));
        }
        if (t >= 10.9 && t < 14.35 || t >= 17 && t < MAINS) {
            blanc = Math.max(blanc, Math.max(0.4 * Mth.clamp(1 - Math.abs(camera.y - (NUAGES + 2)) / 3, 0, 1), 0.9 * Mth.clamp(1 - Math.abs(camera.y - (z0.y + 2)) / 14, 0, 1)));
        }
        if (t < 0.4) {
            noir = 1 - t / 0.4;
        }
        if (t >= 2 && t < 2.4) {
            noir = 1 - entre(t, 2, 2.4);
        }
        if (blanc > 0.01) {
            g.fill(0, 0, w, h, ((int) (Math.min(1, blanc) * 255) << 24) | 0xFFFFFF);
        }
        if (noir > 0.01) {
            g.fill(0, 0, w, h, (int) (Math.min(1, noir) * 255) << 24);
        }
        int bande = (int) (h / 9 * lisse(entre(t, 0, 0.5)) * (1 - lisse(entre(t, DUREE - 0.6, DUREE - 0.1))));
        g.fill(0, 0, w, bande, 0xFF000000);
        g.fill(0, h - bande, w, h, 0xFF000000);
        double tt = Math.min(entre(t, 2.9, 3.25), 1 - entre(t, 5.85, 6.2));
        if (tt > 0.03) {
            int a = (int) (tt * 255) << 24;
            fr.cabane.client.ui.Ios.texteCentre(g, mc.font, "ZEUS", w / 2F, h / 2F - 18, 2.6F, a | (TITRE & 0xFFFFFF), true);
            fr.cabane.client.ui.Ios.texteCentre(g, mc.font, "Le roi des dieux est furieux", w / 2F, h / 2F + 14, 1F, a | 0xD0D0D0, true);
        }
        String nom = mc.player.getGameProfile().getName();
        replique(g, w, h, bande, null, "Des picotements dans les mains...", t, 21.3, 25.3);
        replique(g, w, h, bande, nom, "Qu'est-ce qui se passe... ?", t, 25.6, 28.3);
        replique(g, w, h, bande, nom, "Je devrais aller consulter quelqu'un.", t, 28.4, DUREE + 1);
    }

    /** Une réplique dans la bande du bas : le nom en couleur, puis la phrase en blanc ; sans nom, une pensée en italique. */
    private static void replique(GuiGraphics g, int w, int h, int bande, String nom, String texte, double t, double de, double a) {
        double op = Math.min(entre(t, de, de + 0.35), 1 - entre(t, a - 0.35, a));
        if (op <= 0.03 || t < de || t > a) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        int alpha = (int) (op * 255) << 24;
        if (nom == null) {
            int y = h - bande + (bande - 9) / 2;
            g.drawCenteredString(mc.font, Component.literal(texte).withStyle(ChatFormatting.ITALIC), w / 2, y, alpha | 0xEBEBEB);
            return;
        }
        int y = h - bande + (bande - 10 - 10) / 2;
        g.drawCenteredString(mc.font, nom.toUpperCase(), w / 2, y, alpha | (PARLEUR & 0xFFFFFF));
        g.drawCenteredString(mc.font, texte, w / 2, y + 11, alpha | 0xFFFFFF);
    }

    // ================================================================ branchements

    @EventBusSubscriber(modid = Cabane.MODID, value = Dist.CLIENT)
    public static final class Couches {
        @SubscribeEvent
        public static void enregistrer(RegisterGuiLayersEvent event) {
            event.registerAboveAll(Cabane.id("zeus_cinema"), (g, delta) -> ecran(g));
        }

        private Couches() {
        }
    }

    @EventBusSubscriber(modid = Cabane.MODID, value = Dist.CLIENT)
    public static final class Evenements {
        @SubscribeEvent
        public static void tick(ClientTickEvent.Post event) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.level == null || mc.player == null) {
                if (actif) {
                    arreter();
                }
                return;
            }
            if (!actif) {
                return;
            }
            double t = t();
            evenements(avant, t);
            avant = t;
            if (t >= DUREE) {
                arreter();
            } else if (cinema) {
                // rien de l'interface pendant la scène (même les barres de vie des autres mods)
                mc.options.hideGui = true;
                if (objectif != null && mc.getCameraEntity() != objectif) {
                    mc.setCameraEntity(objectif);
                }
            }
        }

        @SubscribeEvent
        public static void image(RenderFrameEvent.Pre event) {
            if (actif) {
                if (cinema) {
                    placerCamera(t());
                } else {
                    sol = true;
                    premiere = false;
                    largeur = 1;
                }
            }
        }

        @SubscribeEvent
        public static void dessin(RenderLevelStageEvent event) {
            if (!actif || Minecraft.getInstance().level == null) {
                return;
            }
            if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_PARTICLES) {
                dessiner(event.getPoseStack(), event.getCamera().getPosition(), t());
            }
        }

        /** À la première personne, son propre corps est caché (la caméra est dans ses yeux). */
        @SubscribeEvent
        public static void cacher(RenderLivingEvent.Pre<?, ?> event) {
            if (cinema && premiere && event.getEntity() == Minecraft.getInstance().player) {
                event.setCanceled(true);
            }
        }

        @SubscribeEvent
        public static void champ(ViewportEvent.ComputeFov event) {
            if (cinema) {
                event.setFOV(fov);
            }
        }

        /** Au-dessus des cieux, on voit loin (le ciel est peint) ; en redescendant, la brume de Minecraft revient. */
        @SubscribeEvent
        public static void brume(ViewportEvent.RenderFog event) {
            if (cinema && !sol) {
                float k = (float) versSol;
                event.setNearPlaneDistance(Mth.lerp(k, 300F, event.getNearPlaneDistance()));
                event.setFarPlaneDistance(Mth.lerp(k, 1400F, event.getFarPlaneDistance()));
                event.setCanceled(true);
            }
        }

        @SubscribeEvent
        public static void couleurBrume(ViewportEvent.ComputeFogColor event) {
            if (cinema && !sol) {
                float k = (float) versSol;
                event.setRed(Mth.lerp(k, 0.557F, event.getRed()));
                event.setGreen(Mth.lerp(k, 0.322F, event.getGreen()));
                event.setBlue(Mth.lerp(k, 0.251F, event.getBlue()));
            }
        }

        @SubscribeEvent
        public static void immobile(MovementInputUpdateEvent event) {
            if (cinema) {
                event.getInput().leftImpulse = 0;
                event.getInput().forwardImpulse = 0;
                event.getInput().jumping = false;
                event.getInput().shiftKeyDown = false;
                event.getInput().up = false;
                event.getInput().down = false;
                event.getInput().left = false;
                event.getInput().right = false;
            }
        }

        @SubscribeEvent
        public static void pasDeClic(InputEvent.InteractionKeyMappingTriggered event) {
            if (cinema) {
                event.setCanceled(true);
                event.setSwingHand(false);
            }
        }

        @SubscribeEvent
        public static void pasDeMain(RenderHandEvent event) {
            if (cinema) {
                event.setCanceled(true);
            }
        }

        @SubscribeEvent
        public static void pasDInterface(RenderGuiLayerEvent.Pre event) {
            if (cinema && !event.getName().equals(Cabane.id("zeus_cinema"))) {
                event.setCanceled(true);
            }
        }

        private Evenements() {
        }
    }

    private ClientZeus() {
    }
}
