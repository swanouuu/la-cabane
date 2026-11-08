package fr.cabane.client.titans;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import fr.cabane.Cabane;
import fr.cabane.client.cinema.ClientAccueil;
import fr.cabane.client.cinema.ClientCinema;
import fr.cabane.client.cinema.ClientDecollage;
import fr.cabane.client.cinema.Effets;
import fr.cabane.client.cinema.Effets.Posture;
import fr.cabane.client.ui.Ios;
import fr.cabane.hub.Decollage;
import fr.cabane.titans.Titan;
import fr.cabane.titans.Titans;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.ReceivingLevelScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.client.resources.PlayerSkin;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.horse.Horse;
import net.minecraft.world.entity.animal.horse.Variant;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
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
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * L'attaque des titans (133 s), jouée sur l'écran de chacun en même temps, dans le vrai village.
 *
 * <p>Le début (115,6 s) reprend plan par plan le discours d'Erwin que l'utilisateur a fourni (« Le speech dErwin.mp4 »),
 * avec sa bande-son : voix/cinematique/erwin.ogg, seulement dans le pack de sons privé « Beauvais - voix »
 * (cabane:cine.erwin) ; sans elle, quelques bruitages de Minecraft la remplacent. Sur l'avenue du sud, Erwin (son skin
 * fourni), Eren à côté de lui, parle à la foule : les joueurs au premier rang, chacun avec son skin, des soldats du
 * Bataillon derrière. Deux joueurs lui répondent. La Mâchoire lance un rocher au loin, des rochers traversent le ciel,
 * les camarades morts apparaissent en bleu derrière Erwin. Puis la charge à cheval sous les fusées vertes, les rochers qui
 * tombent, des cavaliers projetés, et Erwin éjecté de son cheval à la grande porte.
 *
 * <p>Ensuite : le Barbu enjambe la palissade près de la forge ; derrière la forge, Eren : « On disait rien… On savait. » ;
 * et pour finir, tous les titans autour de Beauvais, vus d'en bas puis d'en haut, et le titre.
 *
 * <p>Les acteurs sont des doubles dessinés ici ; les vrais joueurs, les vrais titans et quelques PNJ sont cachés pendant
 * la scène. Coordonnées relatives au centre de la place (x vers l'est, z vers le sud), comme ClientAccueil ; hors des
 * rues, les acteurs suivent le relief. Angles : 0 = regarde le sud, π/2 = l'est.
 */
public final class ClientTitans {
    private static final double DUREE = Titans.DUREE;
    /** La fin du discours (et de sa bande-son). */
    private static final double CLIP = 115.6;
    private static final double NORD = Math.PI;
    private static final double SUD = 0;
    private static final double EST = Math.PI / 2;

    private static final int OR = 0xFFE8C766;
    private static final int VERT = 0xFF7FD68A;

    static boolean actif;
    private static ClientLevel niveau;
    private static long debutNanos;
    private static double t;
    private static double tPrecedent;
    private static Entity objectif;
    private static CameraType vueAvant;
    private static boolean guiAvant;
    private static net.minecraft.core.BlockPos centre = net.minecraft.core.BlockPos.ZERO;
    private static SoundInstance bandeSon;
    private static boolean avecBandeSon;

    private static Vec3 camera = Vec3.ZERO;
    private static Vec3 vise = Vec3.ZERO;
    private static float fov = 50;
    private static float noir;
    /** Un voile de couleur sur l'image (ARGB) : le bleu des camarades morts. */
    private static int voile;
    private static String titre = "";
    private static String sousTitre = "";
    private static float titreAlpha;

    /** Un personnage de la scène : sa place (pieds) et sa posture, recalculées à chaque image. */
    private static final class Acteur {
        final String nom;
        final int couleurNom;
        final ResourceLocation skin;
        final boolean fin;
        Vec3 pos = Vec3.ZERO;
        double ang;
        boolean visible;
        Posture posture = Posture.DEBOUT;
        float teteX;
        float teteY;
        float alpha = 1;
        int teinte = 0xFFFFFF;
        /** Tout le corps qui bascule autour de la hanche (un cavalier projeté) : l'axe, et l'angle. */
        Vec3 axe = v(0, 0, 1);
        float roulis;

        Acteur(String nom, int couleurNom, ResourceLocation skin, boolean fin) {
            this.nom = nom;
            this.couleurNom = couleurNom;
            this.skin = skin;
            this.fin = fin;
        }
    }

    /** Un soldat : à pied dans la foule pendant le discours, puis à cheval dans la charge. */
    private static final class Soldat {
        Acteur a;
        Horse cheval;
        boolean joueur;
        Vec3 posCheval = Vec3.ZERO;
        double ang;
        boolean monte;
        boolean galop;
        double phase;
        double xPied;
        double zPied;
        double xCharge;
        double zCharge;
        double depart;
        /** Projeté par un rocher : quand, et dans quelle direction. */
        double ejecte = Double.NaN;
        Vec3 dirEjecte = Vec3.ZERO;
        float roulisCheval;
    }

    /** Un titan de la scène. */
    private static final class Geant {
        final String id;
        Vec3 pos = Vec3.ZERO;
        double ang;
        boolean visible;
        float pas;
        float ampleur;
        float teteLacet;
        float teteTangage;
        float penche;
        float coup;
        float machoire;
        float brasLeves;

        Geant(String id) {
            this.id = id;
        }
    }

    /** Un rocher lancé : il part de, arrive à, en suivant une cloche de cette hauteur ; à l'arrivée, il explose (ou pas). */
    private record Rocher(double depart, double arrivee, Vec3 de, Vec3 a, double hauteur, float taille, boolean impact) {
        Vec3 position(double t) {
            double u = Mth.clamp((t - depart) / (arrivee - depart), 0, 1);
            return de.lerp(a, u).add(0, 4 * hauteur * u * (1 - u), 0);
        }
    }

    /** Un choc qui secoue l'image : quand, et à quel point. */
    private record Choc(double quand, float force) {
    }

    private record Replique(double debut, double fin, String qui, int couleur, String texte) {
    }

    private static Acteur erwin;
    private static Acteur eren;
    private static Horse chevalErwin;
    private static Vec3 posChevalErwin = Vec3.ZERO;
    private static boolean erwinMonte;
    private static boolean erwinGalop;
    private static boolean epee;
    private static final List<Soldat> SOLDATS = new ArrayList<>();
    private static final List<Acteur> FANTOMES = new ArrayList<>();
    private static final List<Rocher> ROCHERS = new ArrayList<>();
    private static final List<Choc> CHOCS = new ArrayList<>();
    private static final List<Replique> REPLIQUES = new ArrayList<>();
    private static final Geant MACHOIRE = new Geant(Titan.Sorte.MACHOIRE.id);
    private static final Geant BARBU = new Geant(Titan.Sorte.BARBU.id);
    private static final List<Geant> RONDE = new ArrayList<>();
    private static final Map<Long, Double> SOLS = new HashMap<>();
    /** Les vrais PNJ cachés pendant la scène : Eren (il a un double), et ceux qui se promènent sur l'avenue. */
    private static final Set<String> CACHES = Set.of("Eren", "Thierry", "Monique", "Ginette", "Louis");
    /** Le rocher de la Mâchoire : dans sa main jusqu'au lancer, puis en vol. */
    private static Vec3 rocherMain;
    private static Rocher rocherLance;

    // ---------------------------------------------------------------- les lieux
    /** Erwin, sur l'avenue du sud, face à la foule, dos à la grande porte (au loin, la palissade et la jungle). */
    private static final Vec3 TRIBUNE = v(0.5, 1, 36.5);
    private static final Vec3 PLACE_EREN_DISCOURS = v(2.4, 1, 36.9);
    /** Les rangs de la foule, entre les lampadaires de l'avenue (z = 22, 31) ; sept par rang, du milieu vers les côtés. */
    private static final double[] RANGS = {34.0, 32.6, 30.3, 28.7, 27.1, 25.5};
    private static final int[] COLONNES = {3, 2, 4, 1, 5, 0, 6};
    private static final int FOULE = 35;
    /** La charge : Erwin part d'ici, les rangs (cinq de front) derrière lui, au galop vers la grande porte. */
    private static final double DEPART_CHARGE = 91.43;
    private static final double Z_ERWIN = 20;
    private static final double VITESSE = 5.33;
    private static final double ERWIN_TOUCHE = 107.55;
    /** Eren, derrière la forge (la même place que son PNJ, HubEvents). */
    private static final Vec3 PLACE_EREN = v(70.5, 1, 0.5);
    /** La Mâchoire, au loin derrière la palissade, qui lance son rocher. */
    private static final Vec3 PLACE_MACHOIRE = v(-26, 1, 124);

    private static Vec3 v(double x, double y, double z) {
        return new Vec3(x, y, z);
    }

    private static Vec3 monde(Vec3 p) {
        return new Vec3(centre.getX() + p.x, centre.getY() + p.y, centre.getZ() + p.z);
    }

    private static ResourceLocation peau(String nom) {
        return Cabane.id("textures/entity/pnj/" + nom + ".png");
    }

    // ================================================================ le top départ

    public static void demarrer(Titans.Debut p) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || p.joueurs().isEmpty()) {
            return;
        }
        // une autre cinématique en cours s'arrête (chacune ne fait rien si elle ne joue pas)
        ClientCinema.arreter();
        ClientDecollage.arreter();
        ClientAccueil.arreter();
        couperBandeSon();
        niveau = mc.level;
        centre = p.centre();
        SOLS.clear();
        preparer(p.joueurs());
        debutNanos = System.nanoTime() - (long) (p.depart() * 1e9);
        t = p.depart();
        tPrecedent = t;
        actif = true;
        if (mc.screen != null && !(mc.screen instanceof ReceivingLevelScreen)) {
            mc.setScreen(null);
        }
        guiAvant = mc.options.hideGui;
        mc.options.hideGui = true;
        vueAvant = mc.options.getCameraType();
        mc.options.setCameraType(CameraType.FIRST_PERSON);
        objectif = EntityType.MARKER.create(mc.level);
        calculer(t);
        placerCamera();
        mc.setCameraEntity(objectif);
        mc.getMusicManager().stopPlaying();
        // la bande-son du discours : seulement dans le pack de sons privé (voix/cinematique/erwin.ogg)
        ResourceLocation son = Cabane.id("cine.erwin");
        avecBandeSon = mc.getSoundManager().getSoundEvent(son) != null;
        if (avecBandeSon && p.depart() < 1) {
            bandeSon = SimpleSoundInstance.forUI(SoundEvent.createVariableRangeEvent(son), 1F, 1F);
            mc.getSoundManager().play(bandeSon);
        }
    }

    private static void couperBandeSon() {
        if (bandeSon != null) {
            Minecraft.getInstance().getSoundManager().stop(bandeSon);
            bandeSon = null;
        }
    }

    public static void arreter() {
        if (!actif) {
            return;
        }
        actif = false;
        couperBandeSon();
        Minecraft mc = Minecraft.getInstance();
        mc.options.hideGui = guiAvant;
        if (vueAvant != null) {
            mc.options.setCameraType(vueAvant);
        }
        if (mc.player != null) {
            mc.setCameraEntity(mc.player);
        }
        objectif = null;
        niveau = null;
        SOLDATS.clear();
        FANTOMES.clear();
        chevalErwin = null;
    }

    private static Horse cheval(Variant robe) {
        Horse h = EntityType.HORSE.create(niveau);
        if (h != null) {
            h.setVariant(robe);
        }
        return h;
    }

    private static void preparer(List<Decollage.Passager> liste) {
        Minecraft mc = Minecraft.getInstance();
        erwin = new Acteur("Erwin", OR, peau("erwin"), false);
        eren = new Acteur("Eren", VERT, peau("eren"), true);
        chevalErwin = cheval(Variant.WHITE);
        SOLDATS.clear();
        Variant[] robes = {Variant.CHESTNUT, Variant.BROWN, Variant.BLACK, Variant.DARK_BROWN, Variant.GRAY, Variant.CREAMY};
        int total = Math.max(FOULE, liste.size());
        for (int i = 0; i < total; i++) {
            Soldat s = new Soldat();
            if (i < liste.size()) {
                // un joueur, avec son skin, au premier rang
                Decollage.Passager pa = liste.get(i);
                PlayerInfo info = mc.getConnection() == null ? null : mc.getConnection().getPlayerInfo(pa.id());
                PlayerSkin skin = info != null ? info.getSkin() : DefaultPlayerSkin.get(pa.id());
                s.a = new Acteur(pa.nom(), 0xFFFFFFFF, skin.texture(), skin.model() == PlayerSkin.Model.SLIM);
                s.joueur = true;
            } else {
                // un soldat du Bataillon, au fond
                s.a = new Acteur("", 0, peau("soldat_" + (1 + i % 5)), true);
            }
            s.cheval = cheval(robes[i % robes.length]);
            s.xPied = 0.5 + (COLONNES[i % 7] - 3) * 1.15;
            s.zPied = RANGS[Math.min(RANGS.length - 1, i / 7)];
            int rang = i / 5;
            s.xCharge = 0.5 + (new int[] {2, 1, 3, 0, 4}[i % 5] - 2) * 1.3;
            s.zCharge = Z_ERWIN - 4 - rang * 3.0;
            s.depart = DEPART_CHARGE + 0.3 + rang * 0.12;
            s.phase = i * 1.3;
            SOLDATS.add(s);
        }
        // les camarades morts, en bleu derrière Erwin
        FANTOMES.clear();
        for (int i = 0; i < 28; i++) {
            Acteur f = new Acteur("", 0, peau("soldat_" + (1 + (i * 3) % 5)), true);
            f.pos = v(0.5 + (i % 7 - 3) * 1.2, 1, 39.6 + (i / 7) * 1.4);
            f.alpha = 0.42F;
            f.teinte = 0xA8C0FF;
            FANTOMES.add(f);
        }
        // la ronde finale : tous les titans autour de Beauvais
        RONDE.clear();
        Titan.Sorte[] sortes = Titan.Sorte.values();
        for (int i = 0; i < 24; i++) {
            RONDE.add(new Geant(sortes[i % sortes.length].id));
        }
        rochers();
        repliques();
        ejections();
    }

    private static double zErwin(double instant) {
        return Z_ERWIN + Math.max(0, Math.min(instant, ERWIN_TOUCHE) - DEPART_CHARGE) * VITESSE;
    }

    /** Où un soldat en est de la charge : ils galopent, puis s'arrêtent net quand Erwin tombe. */
    private static double avance(Soldat s, double instant) {
        double dt = Math.min(instant, ERWIN_TOUCHE) - s.depart;
        if (dt <= 0) {
            return 0;
        }
        double d = dt * VITESSE;
        if (instant > ERWIN_TOUCHE) {
            double f = Math.min(instant - ERWIN_TOUCHE, 1.2);
            d += VITESSE * f - VITESSE / 2.4 * f * f;
        }
        return d;
    }

    private static Vec3 posCharge(Soldat s, double instant) {
        double d = avance(s, instant);
        double z = s.zCharge + d;
        double x = Mth.lerp(seg(d, 0, 8), s.xCharge, 0.5 + (s.xCharge - 0.5) * 0.85);
        return v(x, 0, z);
    }

    /** Les rochers : celui de la Mâchoire, ceux qui traversent le ciel, et ceux qui tombent sur la charge. */
    private static void rochers() {
        ROCHERS.clear();
        CHOCS.clear();
        // au-dessus de la foule, très haut : ils filent vers le nord
        ROCHERS.add(new Rocher(58.8, 63.8, v(-18, 18, 140), v(8, 20, -120), 18, 5.5F, false));
        ROCHERS.add(new Rocher(59.4, 64.2, v(14, 16, 150), v(-24, 18, -110), 20, 4.5F, false));
        ROCHERS.add(new Rocher(60.0, 64.6, v(0, 20, 160), v(16, 20, -100), 16, 3.2F, false));
        // sur la charge : cinq impacts, le dernier à côté d'Erwin
        double[][] impacts = {{104.6, -5.5, -6}, {105.2, 3.6, -9.5}, {105.8, -3, -20}, {106.4, 5.2, -3}, {ERWIN_TOUCHE, 2.6, 0.5}};
        for (int k = 0; k < impacts.length; k++) {
            double quand = impacts[k][0];
            double x = impacts[k][1];
            double z = zErwin(quand) + impacts[k][2];
            Vec3 a = v(x, 0, z);
            ROCHERS.add(new Rocher(quand - 2.6, quand, v(x * 1.6 + 6, 24, 136 + k * 5), a, 14, k == 4 ? 2.4F : 2.0F, true));
            CHOCS.add(new Choc(quand, k == 4 ? 1.5F : 0.9F));
        }
        // les pas du Barbu qui enjambe la palissade, et le grondement de la ronde finale
        for (double m = CLIP + 0.7; m < 120; m += 0.9) {
            CHOCS.add(new Choc(m, m > 117 && m < 118 ? 1.1F : 0.75F));
        }
        CHOCS.add(new Choc(120.5, 0.4F));
        for (double m = 125.3; m < 128.6; m += 1.1) {
            CHOCS.add(new Choc(m, 0.45F));
        }
        CHOCS.add(new Choc(128.6, 1.0F));
        rocherLance = null;
    }

    /** Les soldats (jamais les joueurs) assez près d'un impact pour être projetés. */
    private static void ejections() {
        for (Rocher r : ROCHERS) {
            if (!r.impact() || r.arrivee() >= ERWIN_TOUCHE) {
                continue;
            }
            int n = 0;
            for (Soldat s : SOLDATS) {
                if (s.joueur || !Double.isNaN(s.ejecte) || n >= 2) {
                    continue;
                }
                Vec3 p = posCharge(s, r.arrivee());
                double dx = p.x - r.a().x;
                double dz = p.z - r.a().z;
                double d = Math.hypot(dx, dz);
                if (d < 3.6 && s.depart < r.arrivee()) {
                    s.ejecte = r.arrivee();
                    s.dirEjecte = d < 0.1 ? v(1, 0, 0) : v(dx / d, 0, dz / d);
                    n++;
                }
            }
        }
    }

    /**
     * Les sous-titres : la bande-son est en japonais. Ce ne sont pas ceux de la vidéo mot pour mot, mais un résumé, avec nos
     * mots, de ce que dit Erwin (et de ce que lui répond le joueur du premier rang).
     */
    private static void repliques() {
        REPLIQUES.clear();
        String j0 = rang(0).a.nom.isEmpty() ? "Soldat" : rang(0).a.nom;
        dit(5.8, 11.8, "Erwin", "On ne peut pas rester ici à attendre leurs rochers.");
        dit(13.0, 18.4, j0, "Vous voulez qu'on leur fonce dessus ?");
        dit(18.8, 20.3, "Erwin", "Oui.");
        dit(21.0, 26.8, j0, "Pour servir d'appât… et y passer.");
        dit(27.3, 29.3, "Erwin", "Oui.");
        dit(30.5, 38.0, j0, "Et si on refuse, c'est pareil.");
        dit(38.6, 40.3, "Erwin", "Exactement.");
        dit(42.5, 56.5, "Erwin", "Tout le monde meurt un jour, d'une façon ou d'une autre.");
        dit(57.5, 65.0, "Erwin", "Alors… nos vies ne servent à rien ?");
        dit(65.6, 72.4, "Erwin", "Et nos camarades tombés avant nous ?");
        dit(72.8, 78.1, "Erwin", "Non !");
        dit(78.6, 91.0, "Erwin", "C'est à nous de donner un sens à leur mort.");
        dit(91.8, 97.1, "Erwin", "Se battre, c'est tout ce qu'il nous reste.");
        dit(97.4, 104.4, "Erwin", "CHARGEZ ! Jusqu'au bout !");
        REPLIQUES.add(new Replique(121.7, 124.8, "Eren", VERT, "On disait rien, hein. … Mais voilà. On savait."));
    }

    private static void dit(double a, double b, String qui, String texte) {
        REPLIQUES.add(new Replique(a, b, qui, qui.equals("Erwin") ? OR : 0xFFE0E0E0, texte));
    }

    // ================================================================ les outils du scénario

    private static double seg(double x, double a, double b) {
        return Mth.clamp((x - a) / (b - a), 0, 1);
    }

    private static double lisse(double u) {
        return u * u * (3 - 2 * u);
    }

    private static Vec3 entre(Vec3 a, Vec3 b, double u) {
        return a.lerp(b, lisse(Mth.clamp(u, 0, 1)));
    }

    private static double vers(Vec3 de, Vec3 a) {
        return Math.atan2(a.x - de.x, a.z - de.z);
    }

    /** La hauteur du sol ici (relative au centre), lissée sur cinq points pour ne pas sauter sur un lampadaire. */
    private static double sol(double x, double z) {
        double[] h = {sol1(x, z), sol1(x + 1.5, z), sol1(x - 1.5, z), sol1(x, z + 1.5), sol1(x, z - 1.5)};
        java.util.Arrays.sort(h);
        return h[2];
    }

    private static double sol1(double x, double z) {
        int bx = centre.getX() + Mth.floor(x);
        int bz = centre.getZ() + Mth.floor(z);
        long cle = ((long) bx << 32) ^ (bz & 0xFFFFFFFFL);
        Double h = SOLS.get(cle);
        if (h == null) {
            if (niveau == null || !niveau.hasChunk(bx >> 4, bz >> 4)) {
                return 1;
            }
            int y = niveau.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, bx, bz);
            if (y <= niveau.getMinBuildHeight() + 1) {
                return 1;
            }
            h = (double) (y - centre.getY());
            SOLS.put(cle, h);
        }
        return h;
    }

    private static Vec3 auSol(double x, double z) {
        return v(x, sol(x, z), z);
    }

    private static Vec3 auSol(Vec3 p) {
        return auSol(p.x, p.z);
    }

    private static Vec3 tete(Acteur a) {
        return a.pos.add(0, 1.52, 0);
    }

    private static Vec3 tete(Geant g) {
        float h = ModeleTitan.sommet(g.id);
        return g.pos.add(0, (h > 0 ? h : 15) - 1.5, 0);
    }

    private static Vec3 avant(double ang) {
        return v(Math.sin(ang), 0, Math.cos(ang));
    }

    /** Tourne la tête d'un acteur vers un point de la scène (le corps garde sa direction). */
    private static void regarder(Acteur a, Vec3 point) {
        Vec3 h = tete(a);
        double d = vers(h, point) - a.ang;
        d = Math.atan2(Math.sin(d), Math.cos(d));
        d = Mth.clamp(d, -1.25, 1.25);
        double plat = Math.hypot(point.x - h.x, point.z - h.z);
        a.teteX = (float) (-Math.atan2(point.y - h.y, plat) * 0.85);
        a.teteY = (float) d;
    }

    private static Posture avecTete(Acteur a, Posture p) {
        return new Posture(a.teteX, -a.teteY, p.penche(), p.brasDX(), p.brasDY(), p.brasDZ(), p.brasGX(), p.brasGY(), p.brasGZ(),
                p.jambeDX(), p.jambeDZ(), p.jambeGX(), p.jambeGZ());
    }

    private static Posture avec(Posture p, float penche, float brasDX, float brasDZ) {
        return new Posture(p.teteX(), p.teteY(), penche, brasDX, p.brasDY(), brasDZ, p.brasGX(), p.brasGY(), p.brasGZ(),
                p.jambeDX(), p.jambeDZ(), p.jambeGX(), p.jambeGZ());
    }

    /** Assis sur son cheval, les rênes en main ; au galop, penché en avant. */
    private static Posture enSelle(boolean galop, double phase) {
        float s = galop ? (float) Math.sin(phase) * 0.12F : 0;
        return new Posture(0, 0, galop ? 14 : 0, -0.6F + s, 0, 0.12F, -0.6F - s, 0, -0.12F, -1.35F, 0.32F, -1.35F, -0.32F);
    }

    /** Le cavalier sur sa selle : un peu en arrière du garrot, qui rebondit au galop. */
    private static Vec3 selle(Vec3 cheval, double ang, boolean galop, double phase) {
        double bond = galop ? Math.abs(Math.sin(t * 9.5 + phase)) * 0.14 : 0;
        return cheval.add(0, 0.52 + bond, 0).subtract(avant(ang).scale(0.15));
    }

    private static float bouche() {
        return (float) Math.sin(t * 15) * 0.05F;
    }

    // ================================================================ la scène à l'instant t

    private static void calculer(double maintenant) {
        t = maintenant;
        erwin.visible = false;
        eren.visible = false;
        for (Acteur a : List.of(erwin, eren)) {
            a.teteX = 0;
            a.teteY = 0;
            a.posture = Posture.DEBOUT;
            a.roulis = 0;
            a.alpha = 1;
            a.teinte = 0xFFFFFF;
        }
        erwinMonte = false;
        erwinGalop = false;
        epee = false;
        rocherMain = null;
        for (Soldat s : SOLDATS) {
            s.a.visible = false;
            s.a.teteX = 0;
            s.a.teteY = 0;
            s.a.roulis = 0;
            s.monte = false;
            s.galop = false;
            s.roulisCheval = 0;
        }
        for (Acteur f : FANTOMES) {
            f.visible = false;
        }
        MACHOIRE.visible = false;
        BARBU.visible = false;
        for (Geant g : RONDE) {
            g.visible = false;
        }
        voile = 0;
        if (t < 91.43) {
            discours();
        } else if (t < CLIP) {
            charge();
        } else {
            apres();
        }
        cameraEtEcran();
    }

    /** Le joueur (ou le soldat) du premier rang n° i : les deux premiers répondent à Erwin. */
    private static Soldat rang(int i) {
        return SOLDATS.get(Math.min(i, SOLDATS.size() - 1));
    }

    /** Le discours, à pied, sur l'avenue : Erwin et Eren face à la foule. */
    private static void discours() {
        boolean fantomes = t >= 65.23 && t < 72.67;
        erwin.visible = true;
        erwin.pos = auSol(TRIBUNE);
        erwin.ang = NORD;
        eren.visible = true;
        eren.pos = auSol(PLACE_EREN_DISCOURS);
        eren.ang = NORD - 0.15;
        // Eren, les bras croisés à côté de lui
        eren.posture = new Posture(0, 0, 0, -1.05F, -0.72F, 0.05F, -1.0F, 0.72F, -0.05F, 0, 0.05F, 0, -0.05F);
        Vec3 foule = v(0.5, 2.2, 29.5);
        regarder(erwin, foule);
        regarder(eren, foule);
        // Erwin parle : la bouche dans ses gros plans, et la colère de « Certainement pas ! »
        boolean parle = (t >= 18.7 && t < 20.43) || (t >= 27.2 && t < 29.47) || (t >= 38.47 && t < 50.93) || (t >= 65.23 && t < 81.8)
                || (t >= 86.07 && t < 88.43);
        if (parle) {
            erwin.teteX += bouche();
        }
        if (t >= 72.67 && t < 78.3) {
            erwin.posture = avec(erwin.posture, 6, -0.25F, 0.15F);
        }
        if (fantomes) {
            for (Acteur f : FANTOMES) {
                f.visible = true;
                f.pos = auSol(f.pos);
                f.ang = NORD;
                f.teteX = 0.05F;
            }
            voile = 0x7A0B1A4A;
            erwin.teinte = 0xC8D4FF;
            eren.teinte = 0xC8D4FF;
        } else {
            // la foule : les joueurs devant, les soldats derrière, tous tournés vers Erwin
            for (Soldat s : SOLDATS) {
                Acteur a = s.a;
                a.visible = true;
                a.pos = auSol(s.xPied, s.zPied);
                a.ang = SUD;
                a.posture = Posture.DEBOUT;
                regarder(a, tete(erwin));
                a.teteY += (float) Math.sin(t * 0.4 + s.phase) * 0.06F;
            }
            // les deux du premier rang qui lui répondent
            Acteur j0 = rang(0).a;
            Acteur j1 = rang(1).a;
            if ((t >= 12.2 && t < 18.7) || (t >= 20.43 && t < 27.2) || (t >= 29.47 && t < 38.47) || (t >= 40.43 && t < 41.67)) {
                j0.teteX += bouche();
                if (t >= 12.2 && t < 18.7) {
                    regarder(j1, tete(j0));
                }
            }
            if (t >= 62.43 && t < 65.23) {
                // celui qui crie, la tête en arrière
                j1.posture = Posture.CRI;
                j1.teteX = -0.45F + bouche();
            }
        }
        // au loin, derrière la palissade, la Mâchoire lance son rocher
        if (t >= 50.93 && t < 64) {
            lancer();
        }
        voileSombre();
    }

    /** Pendant « Certainement pas ! », l'image s'assombrit un peu. */
    private static void voileSombre() {
        if (t >= 72.67 && t < 78.3) {
            voile = 0x40000010;
        }
    }

    /** La Mâchoire lève son rocher au-dessus de sa tête, puis le lance vers le village. */
    private static void lancer() {
        Geant g = MACHOIRE;
        g.visible = true;
        g.pos = auSol(PLACE_MACHOIRE);
        g.ang = NORD;
        g.pas = (float) t;
        g.ampleur = 0;
        g.teteLacet = 0;
        g.teteTangage = -0.15F;
        g.penche = 0;
        g.brasLeves = 0;
        g.machoire = (float) Math.max(0, Math.sin(t * 3));
        double c = t < 54.9 ? 0.6 * lisse(seg(t, 50.93, 53.4)) : 0.6 + 0.4 * seg(t, 54.9, 55.35);
        g.coup = (float) c;
        double leve = c < 0.6 ? c / 0.6 : 1 - (c - 0.6) / 0.4 * 1.6;
        Vec3 f = avant(g.ang);
        Vec3 droite = v(-f.z, 0, f.x);
        Vec3 epaule = g.pos.add(0, 12.4, 0).add(droite.scale(1.8));
        Vec3 main = epaule.add(0, -6.75 * Math.cos(2.9 * leve), 0).add(f.scale(6.75 * Math.sin(2.9 * leve)));
        if (t < 55.1) {
            rocherMain = main.add(0, 0.8, 0);
        } else if (rocherLance == null) {
            rocherLance = new Rocher(55.1, 59.6, main.add(0, 0.8, 0), v(-14, 0, -60), 30, 2.8F, false);
        }
    }

    /** La charge : à cheval, au galop vers la grande porte, sous les fusées vertes ; les rochers tombent. */
    private static void charge() {
        // Erwin, l'épée levée, en tête
        double ze = zErwin(t);
        posChevalErwin = auSol(TRIBUNE.x, ze);
        erwin.visible = true;
        erwin.ang = SUD;
        if (t < ERWIN_TOUCHE) {
            erwinMonte = true;
            erwinGalop = true;
            erwin.pos = selle(posChevalErwin, SUD, true, 0.7);
            erwin.posture = avec(enSelle(true, t * 9.5), 10, -2.85F, 0.18F);
            epee = true;
            if (t >= 97.3 && t < 99.57) {
                // il hurle
                erwin.teteX = -0.35F + bouche() * 2;
            }
        } else {
            // touché : éjecté de son cheval, il retombe sur le dos, près de la grande porte
            Vec3 depart = selle(auSol(TRIBUNE.x, zErwin(ERWIN_TOUCHE)), SUD, false, 0);
            Vec3 dir = v(-0.95, 0, 0.3).normalize();
            double dt = t - ERWIN_TOUCHE;
            double u = Math.min(dt, 0.95);
            Vec3 p = depart.add(dir.scale(u * 3.4));
            double sol = sol(p.x, p.z);
            double y = Math.max(sol - 0.75 * seg(dt, 0.6, 0.95), depart.y + 3.2 * u - 4.4 * u * u);
            erwin.pos = v(p.x, y, p.z);
            erwin.ang = SUD + 0.4;
            erwin.posture = new Posture(0.2F, 0, (float) (-95 * lisse(seg(dt, 0, 0.9))), -0.4F, 0, 0.9F, -0.3F, 0, -1.0F, -0.3F, 0.2F, 0.4F, -0.3F);
            // son cheval continue sans lui
            erwinMonte = true;
            erwinGalop = true;
            posChevalErwin = auSol(TRIBUNE.x + 0.6 * dt, zErwin(ERWIN_TOUCHE) + dt * VITESSE * 1.2);
        }
        for (Soldat s : SOLDATS) {
            Acteur a = s.a;
            Vec3 p = posCharge(s, t);
            boolean galop = avance(s, t + 0.05) > avance(s, t) + 0.01;
            if (!Double.isNaN(s.ejecte) && t >= s.ejecte) {
                // projeté par le rocher : il vole, retombe, et reste à terre, son cheval couché à côté
                double dt = t - s.ejecte;
                double u = Math.min(dt, 1.0);
                Vec3 base = posCharge(s, s.ejecte);
                Vec3 q = base.add(s.dirEjecte.scale(u * 5.5));
                double sol = sol(q.x, q.z);
                double y = Math.max(sol, sol + 6.5 * u - 6.5 * u * u);
                s.posCheval = v(q.x, y, q.z);
                s.ang = SUD;
                s.monte = true;
                s.galop = false;
                s.roulisCheval = (float) (Math.min(1, dt / 1.0) * Math.PI / 2 * (s.dirEjecte.x >= 0 ? 1 : -1));
                a.visible = true;
                a.ang = SUD;
                a.pos = v(q.x + s.dirEjecte.x * 1.4 * u, y + (u < 1 ? 1.0 : 0.15), q.z + s.dirEjecte.z * 1.4 * u);
                a.axe = avant(SUD);
                a.roulis = (float) (dt < 1 ? dt * 7 : Math.PI / 2 * (s.dirEjecte.x >= 0 ? 1 : -1));
                a.posture = Posture.VOL;
                continue;
            }
            s.posCheval = auSol(p.x, p.z);
            s.ang = SUD;
            s.monte = true;
            s.galop = galop;
            a.visible = true;
            a.ang = SUD;
            a.pos = selle(s.posCheval, SUD, galop, s.phase);
            a.posture = enSelle(galop, t * 9.5 + s.phase);
            if (t > ERWIN_TOUCHE) {
                regarder(a, tete(erwin));
            }
        }
    }

    /** Après le discours : le Barbu à la palissade, Eren derrière la forge, et la ronde de tous les titans. */
    private static void apres() {
        if (t < 125) {
            if (t < 120) {
                marche(BARBU, CLIP, 120, 127, 5, 101, 5.5, 2.6);
            } else {
                marche(BARBU, 120, 125, 101, 5.5, 94, 5, 2.4);
            }
            BARBU.teteTangage = 0.3F;
        }
        if (t >= 120 && t < 125.2) {
            eren.visible = true;
            eren.pos = auSol(PLACE_EREN.x, PLACE_EREN.z);
            eren.ang = EST;
            eren.posture = new Posture(0, 0, 0, -1.05F, -0.72F, 0.05F, -1.0F, 0.72F, -0.05F, 0, 0.05F, 0, -0.05F);
            if (t < 121.5) {
                regarder(eren, tete(BARBU));
            } else {
                // il regarde l'objectif : il nous parle, à nous
                regarder(eren, v(74.4, eren.pos.y + 1.35, 1.8));
                if (t >= 121.7 && t < 124.8) {
                    eren.teteX += bouche();
                }
            }
        }
        if (t >= 125) {
            // tous les titans, en cercle autour de Beauvais : ils viennent d'enjamber la palissade
            double r = 102 - seg(t, 125, DUREE) * 6;
            for (int i = 0; i < RONDE.size(); i++) {
                Geant g = RONDE.get(i);
                double a = i * Math.PI * 2 / RONDE.size() + Math.sin(i * 7.3) * 0.08;
                double ri = r + Math.sin(i * 3.1) * 3;
                titan(g, auSol(Math.sin(a) * ri, Math.cos(a) * ri), a + Math.PI, 1.3);
                g.ampleur = 0.55F;
                g.pas = (float) (t * 1.3 + i);
                g.teteTangage = 0.25F;
                g.brasLeves = i % 6 == 1 ? 0.6F : 0;
                g.machoire = (float) Math.max(0, Math.sin(t * 2.5 + i));
            }
        }
    }

    private static void titan(Geant g, Vec3 pos, double ang, double vitesse) {
        g.visible = true;
        g.pos = pos;
        g.ang = ang;
        g.pas = (float) (t * vitesse * 0.9);
        g.ampleur = vitesse > 0 ? 1F : 0F;
        g.teteLacet = 0;
        g.teteTangage = 0.15F;
        g.penche = (float) Math.sin(t * 0.45 + g.id.length()) * 0.17F;
        g.brasLeves = 0;
        g.machoire = 0;
        g.coup = 0;
    }

    /** Un titan qui marche en ligne droite de a vers b entre t0 et t1, au sol. */
    private static void marche(Geant g, double t0, double t1, double ax, double az, double bx, double bz, double cadence) {
        double u = seg(t, t0, t1);
        titan(g, auSol(Mth.lerp(u, ax, bx), Mth.lerp(u, az, bz)), Math.atan2(bx - ax, bz - az), cadence);
    }

    // ================================================================ la caméra et l'écran

    private static void plan(double a, double b, float focale, Vec3 c0, Vec3 c1, Vec3 v0, Vec3 v1) {
        double u = seg(t, a, b);
        camera = entre(c0, c1, u);
        vise = entre(v0, v1, u);
        fov = focale;
    }

    /** Les plans du discours, calqués sur ceux de la vidéo (les mêmes coupes, à la seconde près). */
    private static void plansDiscours() {
        double g = sol(TRIBUNE.x, TRIBUNE.z);
        Vec3 e = tete(erwin);
        Vec3 j0 = tete(rang(0).a);
        Vec3 j1 = tete(rang(1).a);
        Vec3 j2 = tete(rang(2).a);
        Vec3 deux = j0.lerp(j1, 0.5);
        if (t < 1.37) {
            // de dos, Erwin face à la foule
            plan(0, 1.37, 50, v(0.9, g + 2.2, 38.6), v(0.85, g + 2.2, 38.4), v(0.5, g + 1.8, 29), v(0.5, g + 1.8, 29));
        } else if (t < 5.4) {
            // les rangs, de profil
            plan(1.37, 5.4, 48, v(-6.5, g + 1.9, 30.0), v(-6.3, g + 1.9, 31.2), v(2, g + 1.6, 31), v(2, g + 1.6, 31.6));
        } else if (t < 12.2) {
            // de haut, derrière la foule : Erwin et Eren devant tout le monde ; au loin, la grande porte
            plan(5.4, 12.2, 50, v(0.5, g + 11, 14), v(0.5, g + 10, 16), v(0.8, g + 1.5, 36.5), v(0.8, g + 1.8, 38));
        } else if (t < 18.7) {
            plan(12.2, 18.7, 42, v(-0.3, g + 1.6, 35.9), v(-0.25, g + 1.6, 35.6), deux, deux);
        } else if (t < 20.43) {
            plan(18.7, 20.43, 40, v(0.6, g + 1.65, 34.9), v(0.6, g + 1.65, 35.0), e, e);
        } else if (t < 27.2) {
            plan(20.43, 27.2, 38, v(0.7, g + 1.62, 35.4), v(0.65, g + 1.62, 35.2), j0, j0);
        } else if (t < 29.47) {
            plan(27.2, 29.47, 40, v(0.2, g + 1.6, 35.0), v(0.2, g + 1.6, 35.1), e, e);
        } else if (t < 38.47) {
            plan(29.47, 38.47, 44, v(0.0, g + 1.7, 36.2), v(-0.05, g + 1.68, 35.8), deux, deux);
        } else if (t < 40.43) {
            plan(38.47, 40.43, 40, v(1.1, g + 1.7, 35.1), v(1.05, g + 1.7, 35.2), e, e);
        } else if (t < 41.67) {
            plan(40.43, 41.67, 38, v(0.2, g + 1.6, 35.3), v(0.2, g + 1.6, 35.2), j0, j0);
        } else if (t < 50.93) {
            // Erwin et Eren, côte à côte
            plan(41.67, 50.93, 44, v(-1.3, g + 1.8, 35.3), v(-1.0, g + 1.78, 35.5), v(1.4, g + 1.6, 36.7), v(1.4, g + 1.6, 36.7));
        } else if (t < 56.73) {
            // au loin, la Mâchoire lève son rocher, et le lance
            Vec3 m = MACHOIRE.visible ? MACHOIRE.pos : auSol(PLACE_MACHOIRE);
            plan(50.93, 56.73, 34, v(-8, g + 3, 74), v(-8, g + 3.2, 75), m.add(0, 11, 0), m.add(0, 13, 0));
        } else if (t < 60.23) {
            plan(56.73, 60.23, 46, v(6.5, g + 1.8, 31.5), v(6.2, g + 1.8, 30.4), v(-1, g + 1.6, 30), v(-1, g + 1.6, 30.6));
        } else if (t < 62.43) {
            // le ciel : des rochers passent au-dessus de nous
            plan(60.23, 62.43, 60, v(0.5, g + 1.7, 28), v(0.5, g + 1.7, 28), v(0.5, 45, 60), v(0.5, 48, 50));
        } else if (t < 65.23) {
            plan(62.43, 65.23, 40, v(-0.6, g + 1.6, 35.4), v(-0.6, g + 1.6, 35.2), j1, j1);
        } else if (t < 72.67) {
            // les camarades morts, en bleu, derrière Erwin et Eren
            plan(65.23, 72.67, 46, v(1.2, g + 2.0, 31.5), v(1.2, g + 1.95, 32.6), v(1.3, g + 2.2, 40), v(1.3, g + 2.2, 40));
        } else if (t < 78.3) {
            plan(72.67, 78.3, 34, v(0.55, g + 1.55, 35.3), v(0.55, g + 1.55, 35.6), e, e);
        } else if (t < 81.8) {
            plan(78.3, 81.8, 52, v(3.6, g + 3, 37.5), v(3.4, g + 2.9, 37.2), v(0, g + 1.5, 29), v(0, g + 1.5, 29));
        } else if (t < 86.07) {
            Vec3 trois = j0.lerp(j2, 0.5).lerp(j1, 0.33);
            plan(81.8, 86.07, 46, v(0.5, g + 1.7, 35.7), v(0.45, g + 1.68, 35.4), trois, trois);
        } else if (t < 88.43) {
            // tout près de son œil
            plan(86.07, 88.43, 28, v(0.62, e.y + 0.08, 35.6), v(0.6, e.y + 0.08, 35.7), e.add(0.1, 0.1, 0), e.add(0.1, 0.1, 0));
        } else {
            plan(88.43, 91.43, 44, v(-4.6, g + 1.65, 35.4), v(-4.4, g + 1.65, 35.1), v(1.5, g + 1.6, 34), v(1.5, g + 1.6, 34));
        }
    }

    /** Les plans de la charge : au ras du sol derrière les chevaux, Erwin qui hurle, de face, de côté, les impacts, la chute. */
    private static void plansCharge() {
        Vec3 m = posChevalErwin;
        Vec3 te = tete(erwin);
        if (t < 97.3) {
            double z = 12 + (t - 91.43) * 3.5;
            double s = sol(1.7, z);
            camera = v(1.7, s + 0.55, z);
            vise = v(0.5, s + 2.5, z + 30);
            fov = 60;
        } else if (t < 99.57) {
            camera = m.add(0.7, 2.7, 2.5);
            vise = te;
            fov = 46;
        } else if (t < 102.07) {
            camera = m.add(0, 2.2, 8);
            vise = m.add(0, 1.6, -4);
            fov = 55;
        } else if (t < 104.5) {
            camera = m.add(6, 1.7, -1.2);
            vise = m.add(0, 1.4, 3);
            fov = 55;
        } else if (t < 105.3) {
            double s = sol(-9, m.z - 14);
            camera = v(-9, s + 2.5, m.z - 14);
            vise = v(-1, s + 1.5, m.z - 4);
            fov = 60;
        } else if (t < 106.1) {
            double z = zErwin(105.2) - 9.5;
            double s = sol(7.5, z - 3);
            camera = v(7.5, s + 2.2, z - 3);
            vise = v(3.5, s + 2.5, z);
            fov = 55;
        } else if (t < ERWIN_TOUCHE - 0.55) {
            camera = m.add(0, 2.3, 6);
            vise = m.add(0, 1.5, -6);
            fov = 58;
        } else if (t < 110.2) {
            double z = Math.min(zErwin(t), zErwin(ERWIN_TOUCHE)) - 6;
            double s = sol(9, z);
            camera = v(9, s + 3.5, z);
            vise = erwin.pos.add(0, 0.9, 0);
            fov = 50;
        } else if (t < 114.7) {
            // le joueur du premier rang, qui voit Erwin tomber
            Vec3 j = tete(rang(0).a);
            plan(110.2, 114.7, 38, j.add(1.6, 0.1, 1.0), j.add(1.4, 0.1, 0.9), j, j);
        } else {
            Vec3 f = avant(erwin.ang);
            Vec3 visage = erwin.pos.add(0, 0.9, 0).subtract(f.scale(0.75));
            camera = visage.add(0.15, 1.3, 0.1);
            vise = visage;
            fov = 40;
        }
    }

    private static void plansApres() {
        if (t < 120) {
            double s = sol(82, 9.5);
            plan(CLIP, 120, 58, v(82, s + 2.0, 9.6), v(83, s + 2.2, 9.1), tete(BARBU).add(0, -3, 0), tete(BARBU));
        } else if (t < 121.5) {
            double s = sol(69.2, 2.4);
            plan(120, 121.5, 50, v(69.1, s + 1.75, 2.5), v(69.3, s + 1.8, 2.3), tete(BARBU), tete(BARBU));
        } else if (t < 125) {
            Vec3 h = tete(eren);
            plan(121.5, 125, 40, v(74.6, h.y - 0.25, 1.9), v(74.2, h.y - 0.25, 1.7), h, h);
        } else if (t < 128.6) {
            // en contre-plongée depuis l'avenue : ceux du sud, au-dessus des toits
            double s = sol(4, 80);
            plan(125, 128.6, 72, v(4, s + 1.6, 80), v(3.5, s + 1.6, 82.5), v(1, s + 14, 99), v(1, s + 15, 99));
        } else {
            // d'en haut : Beauvais, encerclée
            plan(128.6, DUREE, 64, v(0.5, 34, 64), v(0.5, 55, 90), v(0.5, 0, 4), v(0.5, 0, -4));
        }
    }

    private static void cameraEtEcran() {
        if (t < 91.43) {
            plansDiscours();
        } else if (t < CLIP) {
            plansCharge();
        } else {
            plansApres();
        }
        // les impacts et les pas des géants secouent l'image
        float secousse = 0;
        for (Choc c : CHOCS) {
            if (t >= c.quand() && t < c.quand() + 0.5) {
                secousse += c.force() * (float) (1 - (t - c.quand()) / 0.5);
            }
        }
        if (secousse > 0) {
            camera = camera.add(Math.sin(t * 53) * secousse * 0.12, Math.sin(t * 47 + 1) * secousse * 0.1, Math.cos(t * 61) * secousse * 0.12);
        }
        camera = monde(camera);
        vise = monde(vise);

        // les fondus : l'ouverture, le noir de la fin du discours, le noir de la fin
        double n = 1 - seg(t, 0, 0.5);
        n = Math.max(n, seg(t, 115.0, 115.5) * (1 - seg(t, CLIP, CLIP + 0.6)));
        n = Math.max(n, 1 - Math.min(1, Math.abs(t - 125) / 0.12));
        n = Math.max(n, seg(t, DUREE - 0.7, DUREE));
        noir = (float) n;

        titre = "";
        sousTitre = "";
        titreAlpha = 0;
        if (t > 129.2) {
            titre = "LES TITANS ATTAQUENT BEAUVAIS";
            sousTitre = "Visez la nuque.";
            titreAlpha = (float) (seg(t, 129.2, 130.2) * (1 - seg(t, DUREE - 0.8, DUREE)));
        }
    }

    private static void placerCamera() {
        if (objectif == null) {
            return;
        }
        Vec3 c = camera;
        Vec3 d = vise.subtract(c);
        float lacet = (float) Math.toDegrees(Math.atan2(-d.x, d.z));
        float tangage = (float) Math.toDegrees(-Math.atan2(d.y, Math.sqrt(d.x * d.x + d.z * d.z)));
        objectif.setPos(c.x, c.y, c.z);
        objectif.xo = c.x;
        objectif.yo = c.y;
        objectif.zo = c.z;
        objectif.xOld = c.x;
        objectif.yOld = c.y;
        objectif.zOld = c.z;
        objectif.setYRot(lacet);
        objectif.setXRot(tangage);
        objectif.yRotO = lacet;
        objectif.xRotO = tangage;
    }

    private static double ecoule() {
        return (System.nanoTime() - debutNanos) / 1e9;
    }

    // ================================================================ les sons et les particules

    private static boolean passe(double m) {
        return tPrecedent < m && t >= m;
    }

    private static void son(SoundEvent s, float volume, float hauteur) {
        Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(s, hauteur, volume));
    }

    /** Les fusées vertes de la charge : quand, et de quel cavalier (ou d'Erwin, -1). */
    private static final double[][] FUSEES = {{91.6, 3}, {92.3, 8}, {93.1, 1}, {94.0, 12}, {95.2, 5}, {96.4, 15}, {102.3, -1}, {103.0, 6}};

    private static void evenements() {
        // sans la bande-son du pack privé, quelques bruitages de Minecraft la remplacent
        if (!avecBandeSon) {
            if (passe(55.0)) {
                son(SoundEvents.RAVAGER_ROAR, 0.8F, 0.5F);
            }
            if (passe(DEPART_CHARGE)) {
                son(SoundEvents.RAID_HORN.value(), 1F, 1F);
            }
            for (double m = DEPART_CHARGE + 0.2; m < ERWIN_TOUCHE; m += 0.28) {
                if (passe(m)) {
                    son(SoundEvents.HORSE_GALLOP, 0.75F, 0.85F + (float) ((m * 7) % 0.3));
                }
            }
        }
        for (Rocher r : ROCHERS) {
            if (r.impact() && passe(r.arrivee())) {
                impact(r);
            }
        }
        // les pas du Barbu, le grondement de la fin
        for (Choc c : CHOCS) {
            if (c.quand() > CLIP && passe(c.quand())) {
                son(SoundEvents.WARDEN_STEP, Math.min(1F, 0.5F + c.force() * 0.5F), 0.5F);
                son(SoundEvents.RAVAGER_STEP, Math.min(1F, 0.3F + c.force() * 0.6F), 0.45F);
            }
        }
        if (passe(121.7)) {
            son(SoundEvents.VILLAGER_AMBIENT, 0.4F, 1.15F);
        }
        if (passe(128.6)) {
            son(SoundEvents.RAVAGER_ROAR, 1F, 0.4F);
            son(SoundEvents.GENERIC_EXPLODE.value(), 0.7F, 0.5F);
        }
        if (passe(129.2)) {
            son(SoundEvents.RAID_HORN.value(), 0.9F, 0.8F);
        }
        fusees();
    }

    /** Un rocher touche le sol : explosion, poussière, éclats. */
    private static void impact(Rocher r) {
        if (!avecBandeSon) {
            son(SoundEvents.GENERIC_EXPLODE.value(), 0.9F, 0.7F);
        }
        if (niveau == null) {
            return;
        }
        Vec3 p = monde(auSol(r.a()));
        niveau.addParticle(ParticleTypes.EXPLOSION_EMITTER, true, p.x, p.y + 0.5, p.z, 0, 0, 0);
        for (int i = 0; i < 40; i++) {
            double a = i * 0.7;
            double v = 0.08 + (i % 5) * 0.03;
            niveau.addParticle(ParticleTypes.CAMPFIRE_COSY_SMOKE, true, p.x + Math.cos(a) * 1.5, p.y + 0.3, p.z + Math.sin(a) * 1.5,
                    Math.cos(a) * v, 0.03 + (i % 3) * 0.02, Math.sin(a) * v);
            niveau.addParticle(ParticleTypes.POOF, true, p.x, p.y + 0.6, p.z, Math.cos(a) * v * 3, 0.25 + (i % 4) * 0.08, Math.sin(a) * v * 3);
        }
    }

    /** Les fusées vertes : elles montent en sifflant, en laissant une traînée de fumée verte. */
    private static void fusees() {
        if (niveau == null || t < DEPART_CHARGE || t > CLIP) {
            return;
        }
        DustParticleOptions vert = new DustParticleOptions(new Vector3f(0.35F, 1F, 0.45F), 3F);
        for (double[] f : FUSEES) {
            double dt = t - f[0];
            if (dt < 0 || dt > 3) {
                continue;
            }
            int k = (int) f[1];
            Vec3 de;
            if (k < 0) {
                de = erwin.pos.add(0, 1.6, 0);
            } else {
                Soldat s = SOLDATS.get(Math.min(k, SOLDATS.size() - 1));
                de = posCharge(s, f[0]).add(0, sol(s.xCharge, s.zCharge) + 2.0, 0);
            }
            Vec3 p = monde(de.add(Math.sin(k + 1.0) * dt * 1.5, dt * 14, dt * 4));
            niveau.addParticle(vert, true, p.x, p.y, p.z, 0, 0, 0);
            niveau.addParticle(vert, true, p.x, p.y - 0.6, p.z, 0, 0, 0);
            if (dt > 0.1) {
                niveau.addParticle(ParticleTypes.CAMPFIRE_COSY_SMOKE, true, p.x, p.y - 1, p.z, 0, 0.01, 0);
            }
        }
    }

    // ================================================================ le dessin

    private static void dessinerMonde(PoseStack pose, Vec3 cam, MultiBufferSource.BufferSource buffers, float partiel) {
        for (Geant g : List.of(MACHOIRE, BARBU)) {
            dessiner(pose, buffers, g, cam);
        }
        for (Geant g : RONDE) {
            dessiner(pose, buffers, g, cam);
        }
        buffers.endBatch();
        if (erwinMonte && chevalErwin != null) {
            dessinerCheval(pose, buffers, chevalErwin, posChevalErwin, SUD, 0, cam, partiel);
        }
        for (Soldat s : SOLDATS) {
            if (s.monte) {
                dessinerCheval(pose, buffers, s.cheval, s.posCheval, s.ang, s.roulisCheval, cam, partiel);
            }
        }
        buffers.endBatch();
        dessiner(pose, buffers, erwin, cam);
        dessiner(pose, buffers, eren, cam);
        for (Soldat s : SOLDATS) {
            dessiner(pose, buffers, s.a, cam);
        }
        if (epee && erwin.visible) {
            epee(pose, buffers, cam);
        }
        // les rochers
        if (rocherMain != null) {
            rocher(pose, buffers, rocherMain, 2.8F, cam);
        }
        if (rocherLance != null && t >= rocherLance.depart() && t < rocherLance.arrivee()) {
            rocher(pose, buffers, rocherLance.position(t), rocherLance.taille(), cam);
        }
        for (Rocher r : ROCHERS) {
            if (t >= r.depart() && (t < r.arrivee() || (r.impact() && t < CLIP))) {
                Vec3 p = r.position(t);
                if (r.impact() && t >= r.arrivee()) {
                    p = auSol(r.a()).add(0, r.taille() * 0.3, 0);
                }
                rocher(pose, buffers, p, r.taille(), cam);
            }
        }
        buffers.endBatch();
        // les fantômes, à moitié transparents, après tout le reste
        for (Acteur f : FANTOMES) {
            dessiner(pose, buffers, f, cam);
        }
        buffers.endBatch();
        dessinerEtiquette(pose, buffers, erwin, cam);
        dessinerEtiquette(pose, buffers, eren, cam);
        for (Soldat s : SOLDATS) {
            dessinerEtiquette(pose, buffers, s.a, cam);
        }
        buffers.endBatch();
    }

    private static void dessiner(PoseStack pose, MultiBufferSource buffers, Geant g, Vec3 cam) {
        if (!g.visible) {
            return;
        }
        ModeleTitan.Pose p = new ModeleTitan.Pose(g.pas, g.ampleur, g.teteLacet, g.teteTangage, g.penche, g.coup, g.machoire, g.brasLeves, 0);
        ModeleTitan.dessiner(g.id, pose, buffers, monde(g.pos).subtract(cam), (float) -Math.toDegrees(g.ang), p, LightTexture.FULL_BRIGHT,
                OverlayTexture.NO_OVERLAY);
    }

    private static void dessiner(PoseStack pose, MultiBufferSource buffers, Acteur a, Vec3 cam) {
        if (!a.visible) {
            return;
        }
        Vec3 p = monde(a.pos).subtract(cam);
        float lacet = (float) -Math.toDegrees(a.ang);
        Posture po = avecTete(a, a.posture);
        if (a.roulis != 0) {
            // tout le corps bascule autour de la hanche
            pose.pushPose();
            pose.translate(p.x, p.y + 0.9, p.z);
            pose.mulPose(new Quaternionf().rotationAxis(a.roulis, (float) a.axe.x, (float) a.axe.y, (float) a.axe.z));
            p = v(0, -0.9, 0);
        }
        if (a.fin || a.alpha < 1 || a.teinte != 0xFFFFFF) {
            Effets.joueur(pose, buffers, a.skin, a.fin, p, lacet, po, a.teinte, a.alpha);
        } else {
            Effets.personnage(pose, buffers, a.skin, p, lacet, po, 1F, Effets.SANS, Effets.NOIR, 1F);
        }
        if (a.roulis != 0) {
            pose.popPose();
        }
    }

    /** Un vrai cheval de Minecraft, à la robe choisie, qui galope, attend, ou roule à terre. */
    private static void dessinerCheval(PoseStack pose, MultiBufferSource buffers, Horse h, Vec3 pos, double ang, float roulis, Vec3 cam, float partiel) {
        if (h == null) {
            return;
        }
        Vec3 w = monde(pos);
        float lacet = (float) -Math.toDegrees(ang);
        h.setPos(w.x, w.y, w.z);
        h.xo = w.x;
        h.yo = w.y;
        h.zo = w.z;
        h.xOld = w.x;
        h.yOld = w.y;
        h.zOld = w.z;
        h.setYRot(lacet);
        h.yRotO = lacet;
        h.yBodyRot = lacet;
        h.yBodyRotO = lacet;
        h.yHeadRot = lacet;
        h.yHeadRotO = lacet;
        h.setXRot(0);
        h.xRotO = 0;
        Vec3 r = w.subtract(cam);
        var rendu = Minecraft.getInstance().getEntityRenderDispatcher();
        if (roulis != 0) {
            Vec3 f = avant(ang);
            pose.pushPose();
            pose.translate(r.x, r.y + 0.8, r.z);
            pose.mulPose(new Quaternionf().rotationAxis(roulis, (float) f.x, 0, (float) f.z));
            rendu.render(h, 0, -0.8, 0, lacet, partiel, pose, buffers, LightTexture.FULL_BRIGHT);
            pose.popPose();
        } else {
            rendu.render(h, r.x, r.y, r.z, lacet, partiel, pose, buffers, LightTexture.FULL_BRIGHT);
        }
    }

    /** L'épée d'Erwin, levée au bout de son bras droit. */
    private static void epee(PoseStack pose, MultiBufferSource buffers, Vec3 cam) {
        Minecraft mc = Minecraft.getInstance();
        Vec3 f = avant(erwin.ang);
        Vec3 droite = v(-f.z, 0, f.x);
        Vec3 main = erwin.pos.add(0, 1.29 + 0.66, 0).add(droite.scale(0.33)).add(f.scale(0.08));
        Vec3 p = monde(main).subtract(cam);
        pose.pushPose();
        pose.translate(p.x, p.y + 0.42, p.z);
        pose.mulPose(Axis.YP.rotation((float) erwin.ang));
        pose.mulPose(Axis.ZP.rotationDegrees(-45));
        pose.scale(0.95F, 0.95F, 0.95F);
        mc.getItemRenderer().renderStatic(new ItemStack(Items.IRON_SWORD), ItemDisplayContext.FIXED, LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY,
                pose, buffers, niveau, 0);
        pose.popPose();
    }

    /** Un rocher : un gros bloc de pierre qui tourne, avec deux bosses. */
    private static void rocher(PoseStack pose, MultiBufferSource buffers, Vec3 relatif, float taille, Vec3 cam) {
        var blocs = Minecraft.getInstance().getBlockRenderer();
        Vec3 p = monde(relatif).subtract(cam);
        BlockState[] pierres = {Blocks.COBBLESTONE.defaultBlockState(), Blocks.STONE.defaultBlockState(), Blocks.ANDESITE.defaultBlockState()};
        pose.pushPose();
        pose.translate(p.x, p.y, p.z);
        pose.mulPose(Axis.XP.rotation((float) (t * 2.1 + relatif.x)));
        pose.mulPose(Axis.YP.rotation((float) (t * 1.3)));
        pose.scale(taille, taille, taille);
        float[][] morceaux = {{0, 0, 0, 1}, {0.35F, 0.3F, -0.2F, 0.6F}, {-0.3F, -0.25F, 0.25F, 0.55F}};
        for (int i = 0; i < morceaux.length; i++) {
            float[] m = morceaux[i];
            pose.pushPose();
            pose.translate(m[0] - m[3] / 2, m[1] - m[3] / 2, m[2] - m[3] / 2);
            pose.scale(m[3], m[3], m[3]);
            blocs.renderSingleBlock(pierres[i], pose, buffers, LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY);
            pose.popPose();
        }
        pose.popPose();
    }

    private static void dessinerEtiquette(PoseStack pose, MultiBufferSource buffers, Acteur a, Vec3 cam) {
        if (!a.visible || a.nom.isEmpty() || a.roulis != 0) {
            return;
        }
        Vec3 haut = monde(a.pos).add(0, 2.15, 0);
        double d = haut.distanceTo(cam);
        if (d < 4.5 || d > 30) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        Font font = mc.font;
        Vec3 p = haut.subtract(cam);
        pose.pushPose();
        pose.translate(p.x, p.y, p.z);
        pose.mulPose(mc.getEntityRenderDispatcher().cameraOrientation());
        pose.scale(0.02F, -0.02F, 0.02F);
        float x = -font.width(a.nom) / 2F;
        int fond = (int) (mc.options.getBackgroundOpacity(0.25F) * 255) << 24;
        font.drawInBatch(a.nom, x, 0, a.couleurNom, false, pose.last().pose(), buffers, Font.DisplayMode.NORMAL, fond, LightTexture.FULL_BRIGHT);
        pose.popPose();
    }

    /** Les bandes noires, le voile de couleur, la réplique en cours, le titre, les fondus. */
    private static void dessinerEcran(GuiGraphics g) {
        Minecraft mc = Minecraft.getInstance();
        int w = g.guiWidth();
        int h = g.guiHeight();
        if (voile != 0) {
            g.fill(0, 0, w, h, voile);
        }
        int bande = h / 9;
        g.fill(0, 0, w, bande, 0xFF000000);
        g.fill(0, h - bande, w, h, 0xFF000000);
        if (noir < 1) {
            for (Replique r : REPLIQUES) {
                if (t < r.debut() || t >= r.fin()) {
                    continue;
                }
                var lignes = mc.font.split(Component.literal("« " + r.texte() + " »"), Math.min(w - 60, 420));
                int y = h - bande - 8 - lignes.size() * 10 - 12;
                g.drawCenteredString(mc.font, r.qui().toUpperCase(), w / 2, y, r.couleur());
                for (int i = 0; i < lignes.size(); i++) {
                    g.drawCenteredString(mc.font, lignes.get(i), w / 2, y + 12 + i * 10, 0xFFFFFFFF);
                }
                break;
            }
        }
        if (noir > 0) {
            g.fill(0, 0, w, h, Mth.clamp((int) (noir * 255), 0, 255) << 24);
        }
        if (!titre.isEmpty() && titreAlpha > 0.02F) {
            int alpha = Mth.clamp((int) (titreAlpha * 255), 8, 255) << 24;
            Ios.texteCentre(g, mc.font, titre, w / 2F, h / 2F - 26, 2.2F, alpha | 0xD8261E, true);
            if (!sousTitre.isEmpty()) {
                Ios.texteCentre(g, mc.font, sousTitre, w / 2F, h / 2F + 8, 1F, alpha | 0xF0F0F0, true);
            }
        }
    }

    // ================================================================ branchements

    @EventBusSubscriber(modid = Cabane.MODID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
    public static final class Enregistrement {
        @SubscribeEvent
        public static void couches(RegisterGuiLayersEvent event) {
            event.registerAboveAll(Cabane.id("titans"), (g, delta) -> {
                if (actif) {
                    dessinerEcran(g);
                }
            });
        }
    }

    @EventBusSubscriber(modid = Cabane.MODID, value = Dist.CLIENT)
    public static final class Evenements {
        @SubscribeEvent
        public static void tick(ClientTickEvent.Post event) {
            Minecraft mc = Minecraft.getInstance();
            if (!actif) {
                return;
            }
            if (mc.level == null || mc.player == null || mc.level != niveau || ecoule() > DUREE + 0.5) {
                arreter();
                return;
            }
            calculer(ecoule());
            evenements();
            tPrecedent = t;
            // les chevaux : leurs jambes galopent ou se reposent
            if (chevalErwin != null) {
                chevalErwin.walkAnimation.update(erwinGalop ? 1F : 0F, 0.4F);
                chevalErwin.tickCount++;
            }
            for (Soldat s : SOLDATS) {
                if (s.cheval != null) {
                    s.cheval.walkAnimation.update(s.galop ? 1F : 0F, 0.4F);
                    s.cheval.tickCount++;
                }
            }
            if (mc.getCameraEntity() != objectif && objectif != null) {
                mc.setCameraEntity(objectif);
            }
        }

        /** La caméra se place à chaque image : les mouvements restent fluides. */
        @SubscribeEvent
        public static void image(RenderFrameEvent.Pre event) {
            if (!actif) {
                return;
            }
            double avant = tPrecedent;
            calculer(ecoule());
            tPrecedent = avant;
            placerCamera();
        }

        @SubscribeEvent
        public static void dessiner(RenderLevelStageEvent event) {
            if (!actif || event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) {
                return;
            }
            dessinerMonde(event.getPoseStack(), event.getCamera().getPosition(), Minecraft.getInstance().renderBuffers().bufferSource(),
                    event.getPartialTick().getGameTimeDeltaPartialTick(false));
        }

        /** Les vrais joueurs et quelques vrais PNJ s'effacent : leurs doubles jouent à leur place (les titans : RenduTitan). */
        @SubscribeEvent
        public static void cacher(RenderLivingEvent.Pre<?, ?> event) {
            if (!actif) {
                return;
            }
            var e = event.getEntity();
            if (e instanceof Player || (e.getCustomName() != null && CACHES.contains(e.getCustomName().getString()))) {
                event.setCanceled(true);
            }
        }

        @SubscribeEvent
        public static void pasDeNom(net.neoforged.neoforge.client.event.RenderNameTagEvent event) {
            if (actif) {
                event.setCanRender(net.neoforged.neoforge.common.util.TriState.FALSE);
            }
        }

        @SubscribeEvent
        public static void champ(ViewportEvent.ComputeFov event) {
            if (actif) {
                event.setFOV(fov);
            }
        }

        @SubscribeEvent
        public static void immobile(MovementInputUpdateEvent event) {
            if (actif) {
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
            if (actif) {
                event.setCanceled(true);
                event.setSwingHand(false);
            }
        }

        @SubscribeEvent
        public static void pasDeMain(RenderHandEvent event) {
            if (actif) {
                event.setCanceled(true);
            }
        }

        @SubscribeEvent
        public static void pasDInterface(RenderGuiLayerEvent.Pre event) {
            if (actif && !event.getName().equals(Cabane.id("titans"))) {
                event.setCanceled(true);
            }
        }
    }

    private ClientTitans() {
    }
}
