package fr.cabane.client.cinema;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import fr.cabane.Cabane;
import fr.cabane.client.cinema.Effets.Posture;
import fr.cabane.client.ui.Ios;
import fr.cabane.hub.Accueil;
import fr.cabane.hub.Decollage;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
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
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.client.resources.PlayerSkin;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
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
import org.joml.Matrix4f;

/**
 * L'accueil à Beauvais (68 s), joué sur l'écran de chacun en même temps, dans le vrai village. Les joueurs atterrissent en
 * parachute sur l'avenue du sud ; le Président descend les accueillir avec sa garde masquée, trop content de les voir, et
 * retient le pseudo de l'un d'eux ; un garde tourne la tête (« Ça brille fort, cette fois. ») ; un tour rapide du village
 * (Gribbs, Mehdi et son dossier, Lucas devant la lumière d'en bas de la Mairie, Marcel qui coupe la prophétie) ; et le Hejj,
 * sur son porche, qui les prévient à demi-mot. Rien n'est expliqué : on montre.
 *
 * <p>Les acteurs sont des doubles dessinés ici (les joueurs avec leur skin, les PNJ avec le leur) ; les vrais joueurs et les
 * vrais PNJ de la scène sont cachés le temps de la cinématique. Coordonnées relatives au centre de la place (x vers l'est,
 * z vers le sud, le sol en y = 1), comme dans HubBuilder et l'aperçu docs/accueil. Les angles des acteurs sont ceux de
 * l'aperçu (en radians, 0 = regarde le sud, π/2 = l'est) ; ils deviennent des angles Minecraft au moment du dessin.
 */
public final class ClientAccueil {
    private static final double DUREE = Accueil.DUREE;
    private static final double NORD = Math.PI;
    private static final double SUD = 0;
    private static final double EST = Math.PI / 2;
    private static final double OUEST = -Math.PI / 2;

    private static final int OR = 0xFFFFD24A;
    private static final int VOIX = 0xFFB9A8FF;
    private static final int VERT = 0xFF9FE0B4;
    private static final int BLEU = 0xFF8FD0FF;
    private static final int GRIS = 0xFFD9CFC5;

    static boolean actif;
    private static ClientLevel niveau;
    private static long debutNanos;
    private static double t;
    private static double tPrecedent;
    private static Entity objectif;
    private static CameraType vueAvant;
    private static boolean guiAvant;
    private static BlockPos centre = BlockPos.ZERO;

    private static Vec3 camera = Vec3.ZERO;
    private static Vec3 vise = Vec3.ZERO;
    private static float fov = 50;
    private static float noir;
    private static String titre = "";
    private static String sousTitre = "";
    private static float titreAlpha;

    /** Un personnage de la scène : sa place et sa posture, recalculées à chaque image (angles de l'aperçu). */
    private static final class Acteur {
        final String nom;
        final int couleurNom;
        final ResourceLocation skin;
        final boolean fin;
        final boolean joueur;
        final float taille;
        Vec3 pos = Vec3.ZERO;
        double ang;
        boolean visible = true;
        float teteX;
        float teteY;
        float penche;
        float bdx;
        float bdy;
        float bdz;
        float bgx;
        float bgy;
        float bgz;
        float jdx;
        float jgx;

        Acteur(String nom, int couleurNom, ResourceLocation skin, boolean fin, boolean joueur, float taille) {
            this.nom = nom;
            this.couleurNom = couleurNom;
            this.skin = skin;
            this.fin = fin;
            this.joueur = joueur;
            this.taille = taille;
        }

        Posture posture() {
            return new Posture(teteX, -teteY, penche, bdx, bdy, bdz, bgx, bgy, bgz, jdx, 0.03F, jgx, -0.03F);
        }
    }

    /** Un joueur de la scène : où il atterrit, où il finit (au porche), quand il touche le sol. */
    private static final class Arrivant {
        Acteur a;
        Vec3 sol;
        Vec3 porche;
        double pose;
        Vec3 derive;
        double phase;
        BlockState voile;
        double hVoile;
        Vec3 posVoile;
        double angVoile;
        double penteVoile;
        double ecraseVoile;
        boolean voileVisible;
    }

    /** Une réplique : qui parle, en quelle couleur, ce qu'il dit, et une précision (« à voix basse »). */
    private record Replique(double debut, double fin, String qui, int couleur, String texte, String note) {
    }

    private static final List<Arrivant> JOUEURS = new ArrayList<>();
    private static final List<Replique> REPLIQUES = new ArrayList<>();
    private static Acteur macron;
    private static final Acteur[] GARDES = new Acteur[4];
    private static Acteur hejj;
    private static Acteur gribbs;
    private static Acteur mehdi;
    private static Acteur marcel;
    private static Acteur nyota;
    private static Acteur sylvie;
    private static Acteur lucas;
    private static Acteur ginette;
    private static Acteur louis;
    private static Acteur gerard;
    private static final List<Acteur> PNJ = new ArrayList<>();
    /** Les vrais PNJ qui jouent dans la scène (par leur nom affiché) : cachés pendant qu'on montre leur double. */
    private static final Set<String> CACHES = Set.of("Macron, Président de Beauvais", "Garde présidentiel", "Le Hejj du Village",
            "Gribbs, cousin de Mouloud", "Mehdi de Pôle emploi", "Marcel le Conteur", "Nyota la Guérisseuse", "Sylvie, la femme de Pierre",
            "Lucas, le fils de Pierre", "Ginette", "Louis", "Gérard");
    private static final BlockState[] VOILES = {Blocks.RED_WOOL.defaultBlockState(), Blocks.BLUE_WOOL.defaultBlockState(),
            Blocks.YELLOW_WOOL.defaultBlockState(), Blocks.LIME_WOOL.defaultBlockState(), Blocks.PURPLE_WOOL.defaultBlockState(),
            Blocks.ORANGE_WOOL.defaultBlockState(), Blocks.CYAN_WOOL.defaultBlockState(), Blocks.MAGENTA_WOOL.defaultBlockState(),
            Blocks.GREEN_WOOL.defaultBlockState(), Blocks.LIGHT_BLUE_WOOL.defaultBlockState()};

    // le dossier de Mehdi, la lumière d'en bas de la Mairie, l'avion
    private static boolean dossierVisible;
    private static Vec3 dossier = Vec3.ZERO;
    private static boolean soupirailAllume = true;
    private static Vec3 avion = Vec3.ZERO;

    private static final Vec3 PLACE_MACRON = v(0.5, 1, 9.5);
    private static final Vec3[] PLACE_GARDES = {v(-1.5, 1, 8.5), v(2.5, 1, 8.5), v(-1.5, 1, 11.5), v(2.5, 1, 11.5)};
    private static final Vec3 ARRET = v(0.5, 1, 23.2);
    private static final Vec3[] ESCORTE = {v(-2.2, 0, -1.5), v(2.2, 0, -1.5), v(-2.6, 0, -3.5), v(2.6, 0, -3.5)};
    private static final Vec3 FEU = v(-19.5, 1.35, 30.5);

    private static Vec3 v(double x, double y, double z) {
        return new Vec3(x, y, z);
    }

    /** Une position de la scène dans le monde. */
    private static Vec3 monde(Vec3 p) {
        return new Vec3(centre.getX() + p.x, centre.getY() + p.y, centre.getZ() + p.z);
    }

    private static ResourceLocation peau(String nom) {
        return Cabane.id("textures/entity/pnj/" + nom + ".png");
    }

    // ================================================================ le top départ

    public static void demarrer(Accueil.Debut p) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || p.joueurs().isEmpty()) {
            return;
        }
        if (ClientCinema.actif) {
            ClientCinema.arreter();
        }
        if (ClientDecollage.actif) {
            ClientDecollage.arreter();
        }
        niveau = mc.level;
        centre = p.centre();
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
    }

    public static void arreter() {
        if (!actif) {
            return;
        }
        actif = false;
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
    }

    private static Acteur pnj(String peau, String nom, int couleur, float taille) {
        Acteur a = new Acteur(nom, couleur, peau(peau), false, false, taille);
        PNJ.add(a);
        return a;
    }

    private static void preparer(List<Decollage.Passager> liste) {
        Minecraft mc = Minecraft.getInstance();
        PNJ.clear();
        macron = pnj("macron", "Macron, Président de Beauvais", 0xFFFFAA00, 1);
        for (int i = 0; i < 4; i++) {
            GARDES[i] = pnj("garde_" + (i + 1), "Garde présidentiel", 0xFFAA0000, 1);
        }
        hejj = pnj("hejj", "Le Hejj du Village", 0xFFFFAA00, 1);
        gribbs = pnj("gribbs", "Gribbs, cousin de Mouloud", 0xFF00AAAA, 1);
        mehdi = pnj("mehdi", "Mehdi de Pôle emploi", 0xFF5555FF, 1);
        marcel = pnj("conteur", "Marcel le Conteur", 0xFFFFAA00, 1);
        nyota = pnj("nyota", "Nyota la Guérisseuse", 0xFF00AA00, 1);
        sylvie = pnj("sylvie", "Sylvie, la femme de Pierre", 0xFFFF55FF, 1);
        lucas = pnj("lucas", "Lucas, le fils de Pierre", 0xFF55FFFF, 0.62F);
        ginette = pnj("ginette", "Ginette", 0xFFAAAAAA, 1);
        louis = pnj("louis", "Louis", 0xFFAAAAAA, 1);
        gerard = pnj("gerard", "Gérard", 0xFFAAAAAA, 1);

        JOUEURS.clear();
        int n = liste.size();
        int cols = Math.min(n, 5);
        int rangs = (n + 4) / 5;
        for (int i = 0; i < n; i++) {
            Decollage.Passager pa = liste.get(i);
            PlayerInfo info = mc.getConnection() == null ? null : mc.getConnection().getPlayerInfo(pa.id());
            PlayerSkin skin = info != null ? info.getSkin() : DefaultPlayerSkin.get(pa.id());
            Arrivant j = new Arrivant();
            j.a = new Acteur(pa.nom(), 0xFFFFFFFF, skin.texture(), skin.model() == PlayerSkin.Model.SLIM, true, 1);
            int c = i % 5;
            int rg = i / 5;
            int dansRang = rg == rangs - 1 ? n - rg * 5 : cols;
            j.sol = v((c - (dansRang - 1) / 2.0) * 2.0 + 0.5, 1, 27 + rg * 2.4);
            j.porche = Accueil.porche(i, n);
            j.pose = 7.6 + i * 0.42;
            Random r = new Random(i * 31L + 7);
            Vec3 d = v(r.nextDouble() - 0.5, 0, r.nextDouble() - 0.5);
            j.derive = d.lengthSqr() < 1e-4 ? v(1, 0, 0) : d.normalize();
            j.phase = r.nextDouble() * 6;
            j.voile = VOILES[i % VOILES.length];
            JOUEURS.add(j);
        }
        repliques();
    }

    private static Arrivant J(int k) {
        return JOUEURS.get(k % JOUEURS.size());
    }

    private static void repliques() {
        REPLIQUES.clear();
        String j1 = J(0).a.nom;
        dit(10.7, 12.9, "Ginette", GRIS, "Encore des gens du ciel…", "à voix basse");
        dit(12.9, 15.0, "Louis", GRIS, "Chut. Pas si fort.", null);
        dit(15.6, 18.8, "Macron", OR, "Mes chers compatriotes ! Enfin… mes chers nouveaux compatriotes !", null);
        dit(18.9, 22.3, "Macron", OR, "Un avion en feu au-dessus de chez nous ! Quelle entrée. C'est un signe.", null);
        dit(22.4, 25.5, "Macron", OR, "Un très bon signe. Pour vous. Pour nous. En même temps.", null);
        dit(25.7, 29.1, "Macron", OR, "Jeunes, en forme… pleins d'énergie. Beauvais avait besoin de gens comme vous.", null);
        dit(29.2, 32.0, "Macron", OR, "Et vous, c'est ? … " + j1 + ". Joli nom. Je le retiendrai.", null);
        dit(32.1, 34.5, "Macron", OR, "Logés, nourris, protégés. Tout est gratuit ! … Pour l'instant.", null);
        dit(35.3, 37.5, "Garde présidentiel", VOIX, "…Ça brille fort, cette fois.", "une voix qui n'est pas la sienne");
        dit(37.6, 39.4, "Macron", OR, "Il dit bonjour ! Ils sont timides, mes gardes.", null);
        dit(39.4, 41.0, "Macron", OR, "Allez, allez ! Visitez !", null);
        dit(41.4, 44.0, "Gribbs", VERT, "Psst. Un secret ? Quinze dollars. Le deuxième aussi.", null);
        dit(44.3, 47.0, "Mehdi", BLEU, "C'est rien ! Des vieux papiers. Une liste… de courses.", null);
        dit(48.2, 50.0, "Sylvie", GRIS, "Lucas ! On ne regarde pas la Mairie.", null);
        dit(50.1, 51.6, "Marcel", GRIS, "Quand le feu tombera du ciel, ceux qui en sortiront…", null);
        dit(51.7, 53.0, "Marcel", GRIS, "…auront faim. Voilà. Qui veut du poisson ?", null);
        dit(53.6, 56.2, "Le Hejj", OR, "Ah. Les gens du ciel. Asseyez-vous. … Non, pas là. C'est le banc de Gérard.", null);
        dit(56.3, 59.0, "Le Hejj", OR, "La dernière fois que le ciel nous a envoyé du monde…", null);
        dit(59.1, 62.2, "Le Hejj", OR, "Bah. Écoutez ce qu'il dit, le Président. Et regardez ce qu'il fait.", null);
    }

    private static void dit(double a, double b, String qui, int couleur, String texte, String note) {
        REPLIQUES.add(new Replique(a, b, qui, couleur, texte, note));
    }

    private static boolean parle(String qui) {
        for (Replique r : REPLIQUES) {
            if (r.qui().equals(qui) && t >= r.debut() && t < r.fin()) {
                return true;
            }
        }
        return false;
    }

    private static float bouche(String qui) {
        return parle(qui) ? (float) Math.sin(t * 15) * 0.05F : 0;
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

    /** Remet un acteur droit, puis le place : ses pieds, sa direction, son pas (0 : immobile), assis ou debout. */
    private static void poser(Acteur a, Vec3 pos, double ang, double marche, boolean assis) {
        a.visible = true;
        a.pos = assis ? pos.add(0, -0.45, 0) : pos;
        a.ang = ang;
        float pas = marche != 0 ? (float) Math.sin(marche) : 0;
        float ampl = marche != 0 ? 0.62F : 0;
        a.jdx = pas * ampl;
        a.jgx = -pas * ampl;
        a.bdx = -pas * ampl * 0.8F;
        a.bgx = pas * ampl * 0.8F;
        a.bdy = 0;
        a.bgy = 0;
        a.bdz = 0.08F;
        a.bgz = -0.08F;
        a.penche = 0;
        a.teteX = 0;
        a.teteY = 0;
        if (assis) {
            a.jdx = (float) (-Math.PI / 2);
            a.jgx = (float) (-Math.PI / 2);
        }
    }

    private static void poser(Acteur a, Vec3 pos, double ang) {
        poser(a, pos, ang, 0, false);
    }

    private static Vec3 tete(Acteur a) {
        return a.pos.add(0, 1.62 * a.taille, 0);
    }

    /** Tourne la tête vers un point de la scène (le corps garde sa direction). */
    private static void regarder(Acteur a, Vec3 point) {
        Vec3 h = tete(a);
        double d = vers(h, point) - a.ang;
        d = Math.atan2(Math.sin(d), Math.cos(d));
        d = Mth.clamp(d, -1.25, 1.25);
        double plat = Math.hypot(point.x - h.x, point.z - h.z);
        a.teteX = (float) (-Math.atan2(point.y - h.y, plat) * 0.85);
        a.teteY = (float) d;
    }

    private record Trajet(Vec3 pos, double ang, double marche, boolean enRoute) {
    }

    private static Trajet trajet(double t0, double t1, Vec3 a, Vec3 b) {
        double u = seg(t, t0, t1);
        boolean enRoute = t > t0 && t < t1;
        return new Trajet(a.lerp(b, u), vers(a, b), enRoute ? (t - t0) * 9 : 0, enRoute);
    }

    private static Vec3 gardePos(int i) {
        return ARRET.add(ESCORTE[i]);
    }

    // ================================================================ la scène à l'instant t

    private static void calculer(double maintenant) {
        t = maintenant;
        scenePnj();
        sceneJoueurs();
        effets();
        cameraEtEcran();
    }

    private static void scenePnj() {
        if (t < 41) {
            // le Président descend l'avenue, s'arrête devant le premier joueur, revient, puis se glisse devant son garde
            Vec3 j0 = J(0).sol;
            Vec3 devant = j0.add(0.15, 0, -1.65);
            Vec3 milieu = v(0.5, 1, 23.4);
            Vec3 glisse = v((gardePos(1).x + j0.x) / 2 + 0.4, 1, (gardePos(1).z + j0.z) / 2);
            boolean agite = false;
            if (t < 25.6) {
                Trajet tr = trajet(15, 19.5, PLACE_MACRON, ARRET);
                poser(macron, tr.pos(), t < 15 ? SUD : tr.ang(), tr.marche(), false);
            } else if (t < 33) {
                Trajet tr = trajet(25.6, 27.4, ARRET, devant);
                poser(macron, tr.pos(), tr.enRoute() ? tr.ang() : vers(tr.pos(), j0), tr.marche(), false);
            } else if (t < 37.4) {
                Trajet tr = trajet(33, 34.3, devant, milieu);
                poser(macron, tr.pos(), tr.enRoute() ? tr.ang() : SUD, tr.marche(), false);
            } else {
                Trajet tr = trajet(37.4, 38.0, milieu, glisse);
                poser(macron, tr.pos(), tr.enRoute() ? tr.ang() : vers(glisse, j0), tr.marche() * 1.6, false);
                agite = t > 37.9;
            }
            if (t >= 25.6 && t < 33) {
                regarder(macron, tete(J(0).a));
            } else if (t < 15) {
                regarder(macron, v(0.5, 3, 27));
            }
            // la main sur l'épaule du joueur
            float main = (float) (seg(t, 29.3, 29.9) * (1 - seg(t, 31.6, 32.2)));
            macron.bdx = Mth.lerp(main, macron.bdx, -1.35F);
            macron.bdz = Mth.lerp(main, macron.bdz, -0.2F);
            // les bras grands ouverts pour « mes chers compatriotes » et « c'est un signe »
            float ouvre = (float) (seg(t, 15.4, 15.9) * (1 - seg(t, 18.6, 19.2)) + seg(t, 19.6, 20.1) * (1 - seg(t, 22.0, 22.5)));
            if (t > 15.4 && t < 22.5 && t < 25.6) {
                macron.bdz += 0.95F * ouvre;
                macron.bgz -= 0.95F * ouvre;
                macron.bdx -= 0.35F * ouvre;
                macron.bgx -= 0.35F * ouvre;
            }
            if (agite) {
                macron.bdx = -1.6F + (float) Math.sin(t * 13) * 0.35F;
                macron.bgx = -1.6F + (float) Math.cos(t * 13) * 0.35F;
            }
            macron.teteX += bouche("Macron");
            for (int i = 0; i < 4; i++) {
                Trajet tr = trajet(15, 19.5, PLACE_GARDES[i], gardePos(i));
                poser(GARDES[i], tr.pos(), t < 15 ? SUD : tr.ang(), tr.marche(), false);
                if (t > 19.5) {
                    GARDES[i].ang = vers(GARDES[i].pos, v(0.5, 1, 27.5));
                }
            }
            // le garde 2 tourne la tête, lentement, vers le joueur ; les autres ne bougent pas
            Acteur g = GARDES[1];
            float u = (float) lisse(seg(t, 35.0, 36.6));
            if (u > 0 && t <= 38.2) {
                regarder(g, tete(J(0).a));
                g.teteY *= u;
                g.teteX *= u;
            }
        } else {
            poser(macron, PLACE_MACRON, SUD);
            for (int i = 0; i < 4; i++) {
                poser(GARDES[i], PLACE_GARDES[i], SUD);
            }
            // le Président salue de loin, au fond du plan du Hejj
            if (t > 59) {
                macron.bdz = 0.7F + (float) Math.sin(t * 2.2) * 0.25F;
                macron.bgz = -0.7F - (float) Math.sin(t * 2.2) * 0.25F;
            }
            // au feu de camp, un garde dans le fond : il tourne la tête vers Marcel
            if (t >= 50 && t < 53) {
                poser(GARDES[2], v(-31, 1, 25.5), EST - 0.6);
                if (t > 51.25) {
                    regarder(GARDES[2], tete(marcel));
                }
            }
        }

        // le Hejj, sur son porche
        poser(hejj, v(3.5, 1, -31.5), SUD);
        if (t >= 53 && t < 62.2) {
            regarder(hejj, tete(J(0).a));
            if (t > 54.6 && t < 55.8) {
                // « pas là » : la main droite vers le banc de Gérard, à l'ouest
                hejj.bdx = -1.1F;
                hejj.bdy = 0.6F;
            }
            if (t > 56.4 && t < 59) {
                // il baisse les yeux
                hejj.teteX = 0.42F;
                hejj.teteY = 0.1F;
            }
            if (t > 59.1) {
                regarder(hejj, v(0.5, 2.2, 9.5));
            }
            hejj.teteX += bouche("Le Hejj");
        }

        // Gribbs, à l'entrée du bazar : il se frotte les mains
        poser(gribbs, v(-27.5, 1, -4.5), EST + 0.35);
        if (t >= 41 && t < 44) {
            regarder(gribbs, tete(J(1).a));
            gribbs.penche = (float) (10 * seg(t, 41.2, 41.6));
            float f = (float) Math.sin(t * 16) * 0.12F;
            gribbs.bdx = -0.85F;
            gribbs.bdy = -0.45F - f;
            gribbs.bdz = 0;
            gribbs.bgx = -0.85F;
            gribbs.bgy = 0.45F + f;
            gribbs.bgz = 0;
            gribbs.teteX += bouche("Gribbs");
        }

        // Mehdi, devant le bazar, son dossier ouvert ; il le referme d'un coup et le cache
        poser(mehdi, v(-30.6, 1, 2.6), EST);
        {
            float cache = (float) seg(t, 45.6, 46.0);
            mehdi.bdx = -0.75F * (1 - cache) + 0.25F * cache;
            mehdi.bdy = -0.35F * (1 - cache);
            mehdi.bgx = -0.75F * (1 - cache) + 0.25F * cache;
            mehdi.bgy = 0.35F * (1 - cache);
            mehdi.teteX = t < 44.9 ? 0.55F : 0;
            if (t >= 44.9 && t < 47) {
                regarder(mehdi, tete(J(2).a));
            }
            mehdi.teteX += bouche("Mehdi");
            dossierVisible = cache < 1;
            double a = mehdi.ang;
            dossier = mehdi.pos.add(Math.sin(a) * 0.5, 0.95, Math.cos(a) * 0.5);
        }

        // Lucas fixe la lumière d'en bas de la Mairie ; Sylvie vient le chercher
        {
            Trajet sv = trajet(47.0, 48.4, v(11.6, 1, 37.0), v(15.3, 1, 39.8));
            Trajet part = trajet(49.0, 51.5, v(15.3, 1, 39.8), v(11.0, 1, 36.2));
            Trajet partL = trajet(49.0, 51.5, v(16.0, 1, 40.6), v(11.8, 1, 36.8));
            if (t < 49) {
                poser(sylvie, sv.pos(), sv.enRoute() ? sv.ang() : vers(sv.pos(), v(16, 1, 40.6)), sv.marche(), false);
                poser(lucas, v(16.0, 1, 40.6), SUD);
                lucas.teteX = 0.1F;
                if (t > 48.4) {
                    sylvie.bdx = -0.9F;
                }
            } else {
                poser(sylvie, part.pos(), part.ang(), part.marche(), false);
                poser(lucas, partL.pos(), partL.ang(), partL.marche() * 1.3, false);
                regarder(lucas, v(16.5, 1.3, 43)); // il se retourne vers la lumière
                lucas.bgx = -0.6F;
            }
            sylvie.teteX += bouche("Sylvie");
        }

        // le feu de camp
        poser(marcel, v(-24.5, 1, 30.5), EST);
        poser(nyota, v(-15.5, 1, 30.5), OUEST);
        poser(gerard, v(-24.5, 1, 35.5), EST - 0.6);
        if (t >= 50 && t < 53) {
            if (t > 51.35) {
                regarder(marcel, v(-31, 2.6, 25.5));
            }
            if (t < 51.4) {
                marcel.bdx = -0.8F;
                marcel.bdz = -0.4F;
            }
            marcel.teteX += bouche("Marcel");
            regarder(nyota, tete(marcel));
        }

        // Ginette et Louis, au bord de l'avenue
        poser(ginette, v(-4.4, 1, 36.6), NORD + 0.25);
        poser(louis, v(4.8, 1, 36.2), NORD - 0.25);
        if (t >= 10.6 && t < 15) {
            regarder(ginette, parle("Ginette") ? tete(louis) : v(0.5, 2.4, 28));
            regarder(louis, t > 12.9 ? tete(ginette) : v(0.5, 2.4, 28));
            ginette.teteX += bouche("Ginette");
            louis.teteX += bouche("Louis");
            if (t > 13.2) {
                louis.bdx = -0.5F;
            }
        } else if (t > 15 && t < 41) {
            regarder(ginette, tete(macron));
            regarder(louis, tete(macron));
        }
    }

    private static void sceneJoueurs() {
        int n = JOUEURS.size();
        for (int i = 0; i < n; i++) {
            Arrivant j = JOUEURS.get(i);
            Acteur a = j.a;
            j.voileVisible = false;
            if (t < 41) {
                double reste = j.pose - t;
                if (reste > 0) {
                    // la descente sous la voile
                    Vec3 pos = j.sol.add(j.derive.scale(reste * 1.1)).add(Math.sin(t * 1.3 + j.phase) * 0.4, reste * 4.2, 0);
                    poser(a, pos, NORD + Math.sin(t * 0.9 + j.phase) * 0.3);
                    a.bdx = -2.7F;
                    a.bdz = -0.25F;
                    a.bgx = -2.7F;
                    a.bgz = 0.25F;
                    a.jdx = (float) Math.sin(t * 2 + j.phase) * 0.25F;
                    a.jgx = -a.jdx;
                    j.voileVisible = true;
                    j.posVoile = pos;
                    j.angVoile = a.ang;
                    j.penteVoile = Math.sin(t * 1.3 + j.phase) * 0.08;
                    j.ecraseVoile = 1;
                    j.hVoile = 0;
                } else {
                    double atterri = -reste;
                    poser(a, j.sol, NORD);
                    // un petit choc à l'atterrissage, et la voile qui s'effondre derrière
                    double choc = Math.max(0, 1 - atterri / 0.35);
                    a.pos = a.pos.add(0, -choc * 0.18, 0);
                    if (atterri < 0.9) {
                        double k = seg(atterri, 0, 0.9);
                        j.voileVisible = true;
                        j.posVoile = j.sol.add(0, -2.2 * k, 0.6 * k);
                        j.angVoile = NORD;
                        j.penteVoile = 0.9 * k;
                        j.ecraseVoile = 1 - 0.85 * k;
                    }
                    if (t > 15) {
                        regarder(a, tete(macron));
                        if (t > 35.2 && t < 38 && i == 0) {
                            regarder(a, tete(GARDES[1]));
                        }
                    } else if (atterri > 0.6) {
                        regarder(a, v(Math.sin(t * 0.7 + j.phase) * 12, 4 + Math.sin(t * 0.5 + i) * 2, -10));
                    }
                }
                continue;
            }
            if (t < 53) {
                // le tour du village : seuls ceux du plan sont là
                a.visible = false;
                if (i == 1 % n && t >= 41 && t < 44) {
                    Trajet tr = trajet(41, 44, v(-19.0, 1, -0.6), v(-25.8, 1, -1.2));
                    poser(a, tr.pos(), tr.ang(), tr.marche(), false);
                    if (t > 42.2) {
                        regarder(a, tete(gribbs));
                    }
                }
                if (i == 2 % n && t >= 44 && t < 47) {
                    poser(a, v(-27.6, 1, 2.8), OUEST);
                    regarder(a, t < 44.9 ? dossier : tete(mehdi));
                }
                if (t >= 50 && t < 53) {
                    if (i == 3 % n) {
                        poser(a, v(-20.3, 1, 26.3), SUD, 0, true);
                        regarder(a, tete(marcel));
                    }
                    if (i == 4 % n && n > 1) {
                        Vec3 p = v(-16.9, 1, 33.2);
                        poser(a, p, vers(p, FEU), 0, true);
                        regarder(a, tete(marcel));
                    }
                }
                continue;
            }
            // au pied du porche du Hejj
            poser(a, j.porche, NORD);
            regarder(a, tete(hejj));
        }
    }

    private static void effets() {
        avion = v(-260 + t * 44, 150 - t * 1.6, -70);
        soupirailAllume = t < 64.8;
    }

    // ================================================================ la caméra et l'écran

    private static Vec3 focus() {
        if (t < 25.6) {
            return tete(macron);
        }
        if (t < 37.5) {
            return tete(macron).lerp(tete(J(0).a), 0.35);
        }
        return tete(macron).lerp(tete(GARDES[1]), 0.3);
    }

    private static void plan(double a, double b, float focale, Vec3 c0, Vec3 c1, Vec3 v0, Vec3 v1) {
        double u = seg(t, a, b);
        camera = entre(c0, c1, u);
        vise = entre(v0, v1, u);
        fov = focale;
    }

    private static void cameraEtEcran() {
        Vec3 suivi = focus();
        Vec3 j = J(0).sol;
        if (t < 7) {
            plan(0, 7, 50, v(56, 50, 86), v(34, 34, 62), v(0, 14, 0), v(0, 8, 20));
        } else if (t < 10.6) {
            plan(7, 10.6, 48, v(0.5, 2.2, 44), v(0.5, 2.4, 42.5), v(0.5, 8, 26), v(0.5, 2.2, 27));
        } else if (t < 15) {
            plan(10.6, 15, 44, v(0.5, 2.7, 41), v(0.5, 2.6, 40.2), v(0.5, 1.9, 29), v(0.5, 1.9, 29));
        } else if (t < 19.5) {
            plan(15, 19.5, 40, v(3.2, 3.1, 34.5), v(2.8, 3.0, 33.6), suivi, suivi);
        } else if (t < 25.6) {
            plan(19.5, 25.6, 36, v(1.9, 2.15, 26.2), v(1.6, 2.2, 25.4), suivi, suivi);
        } else if (t < 29.2) {
            plan(25.6, 29.2, 40, j.add(-2.3, 2.2, 1.8), j.add(-2.0, 2.15, 1.4), suivi, suivi);
        } else if (t < 34.5) {
            Vec3 m = j.add(0.15, 1.7, -0.9);
            plan(29.2, 34.5, 34, j.add(1.9, 1.75, -0.2), j.add(1.6, 1.8, -0.5), m, m);
        } else if (t < 37.5) {
            Vec3 g = gardePos(1);
            Vec3 d = j.subtract(g).multiply(1, 0, 1).normalize();
            Vec3 h = g.add(0, 1.6, 0);
            plan(34.5, 37.5, 30, g.add(d.scale(3.6)).add(0.9, 1.45, 0), g.add(d.scale(2.9)).add(0.6, 1.5, 0), h, h);
        } else if (t < 41) {
            plan(37.5, 41, 42, j.add(-2.4, 2.3, 3.4), j.add(-2.0, 2.2, 2.9), suivi, suivi);
        } else if (t < 44) {
            plan(41, 44, 40, v(-22.4, 2.45, -8.6), v(-22.9, 2.4, -8.1), v(-26.4, 2.05, -3.0), v(-26.4, 2.05, -3.0));
        } else if (t < 47) {
            plan(44, 47, 38, v(-26.2, 2.3, 5.6), v(-26.6, 2.25, 5.3), v(-30.6, 2.0, 2.6), v(-30.6, 2.0, 2.6));
        } else if (t < 50) {
            plan(47, 50, 40, v(19.6, 2.0, 37.2), v(19.2, 1.95, 37.5), v(15.6, 1.45, 41.2), v(15.6, 1.45, 41.2));
        } else if (t < 53) {
            plan(50, 53, 40, v(-18.4, 2.9, 32.2), v(-18.7, 2.85, 31.9), v(-24.5, 2.2, 30.2), v(-24.5, 2.2, 30.2));
        } else if (t < 56.2) {
            plan(53, 56.2, 40, v(11.8, 3.0, -25.4), v(11.0, 2.95, -26.0), v(4.2, 2.3, -30.4), v(4.2, 2.3, -30.4));
        } else if (t < 59.1) {
            plan(56.2, 59.1, 30, v(3.0, 2.45, -28.6), v(3.1, 2.5, -29.2), v(3.5, 2.55, -31.5), v(3.5, 2.55, -31.5));
        } else if (t < 62.2) {
            plan(59.1, 62.2, 42, v(2.0, 2.85, -33.2), v(2.2, 2.9, -32.9), v(2.5, 2.0, -12), v(1.0, 2.2, 6));
        } else {
            plan(62.2, DUREE, 40, v(4.5, 3.2, -28.5), v(3.5, 34, -14), v(3.5, 2.4, -10), v(14, 2, 42));
        }
        camera = monde(camera);
        vise = monde(vise);

        // les fondus au noir : l'ouverture, les coupes du tour du village, l'arrivée chez le Hejj, la fin
        double n = 1 - seg(t, 0, 0.8);
        if (t >= 53) {
            n = Math.max(n, 1 - seg(t, 53, 53.5));
        }
        for (double d : new double[] {41, 44, 47, 50}) {
            n = Math.max(n, 1 - Math.min(1, Math.abs(t - d) / 0.12));
        }
        if (t < 53) {
            n = Math.max(n, seg(t, 52.6, 53));
        }
        n = Math.max(n, seg(t, DUREE - 0.8, DUREE));
        noir = (float) n;

        titre = "";
        sousTitre = "";
        titreAlpha = 0;
        if (t > 1.2 && t < 6.6) {
            titre = "Beauvais";
            sousTitre = "QUELQUE PART DANS LA JUNGLE";
            titreAlpha = (float) (seg(t, 1.2, 2.2) * (1 - seg(t, 5.6, 6.6)));
        } else if (t > 63.4) {
            titre = "Bienvenue à Beauvais";
            titreAlpha = (float) (seg(t, 63.4, 64.6) * (1 - seg(t, DUREE - 0.7, DUREE)));
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

    // ================================================================ les sons

    private static boolean passe(double m) {
        return tPrecedent < m && t >= m;
    }

    private static void son(SoundEvent s, float volume, float hauteur) {
        Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(s, hauteur, volume));
    }

    private static void evenements() {
        for (Arrivant j : JOUEURS) {
            if (passe(j.pose)) {
                son(SoundEvents.GENERIC_SMALL_FALL, 0.5F, 0.9F);
                son(SoundEvents.WOOL_PLACE, 0.4F, 0.8F);
            }
        }
        // chacun a sa petite voix de villageois quand il commence à parler
        for (Replique r : REPLIQUES) {
            if (!passe(r.debut())) {
                continue;
            }
            switch (r.qui()) {
                case "Macron" -> son(SoundEvents.VILLAGER_TRADE, 0.55F, 1.15F);
                case "Le Hejj" -> son(SoundEvents.VILLAGER_AMBIENT, 0.55F, 0.75F);
                case "Garde présidentiel" -> son(SoundEvents.AMBIENT_CAVE.value(), 0.45F, 0.7F);
                case "Mehdi" -> son(SoundEvents.VILLAGER_NO, 0.5F, 1F);
                case "Gribbs" -> son(SoundEvents.VILLAGER_AMBIENT, 0.5F, 1.3F);
                case "Sylvie" -> son(SoundEvents.VILLAGER_AMBIENT, 0.45F, 1.35F);
                default -> son(SoundEvents.VILLAGER_AMBIENT, 0.35F, 1.1F);
            }
        }
        if (passe(45.0)) {
            son(SoundEvents.BOOK_PUT, 0.8F, 1F);
        }
        if (passe(64.8)) {
            son(SoundEvents.LEVER_CLICK, 0.25F, 0.6F);
        }
        // la fumée noire de l'avion en feu, tout là-haut
        if (t < 15 && niveau != null) {
            Vec3 a = monde(avion);
            for (int k = 0; k < 3; k++) {
                niveau.addParticle(ParticleTypes.CAMPFIRE_SIGNAL_SMOKE, true, a.x - k * 2, a.y + 1, a.z + 7, 0, 0.05, 0);
            }
            niveau.addParticle(ParticleTypes.FLAME, true, a.x, a.y - 1, a.z + 7, 0, 0.02, 0);
        }
    }

    // ================================================================ le dessin

    private static void dessinerMonde(PoseStack pose, Vec3 cam, MultiBufferSource.BufferSource buffers) {
        Minecraft mc = Minecraft.getInstance();
        // l'avion en feu qui passe, tout là-haut
        if (t < 15) {
            Vec3 a = monde(avion).subtract(cam);
            pose.pushPose();
            pose.translate(a.x, a.y, a.z);
            pose.mulPose(Axis.XP.rotationDegrees(-5));
            Avion3D.dessiner(pose, buffers, LightTexture.FULL_BRIGHT, false, false, true);
            pose.popPose();
            buffers.endBatch();
        }
        for (Acteur a : PNJ) {
            dessiner(pose, buffers, a, cam);
        }
        for (Arrivant j : JOUEURS) {
            dessiner(pose, buffers, j.a, cam);
            if (j.voileVisible) {
                voile(pose, buffers, j, cam);
            }
        }
        // le dossier de Mehdi
        if (dossierVisible && t >= 43.5 && t < 47) {
            Vec3 d = monde(dossier).subtract(cam);
            pose.pushPose();
            pose.translate(d.x, d.y, d.z);
            pose.mulPose(Axis.YP.rotationDegrees((float) Math.toDegrees(mehdi.ang)));
            pose.mulPose(Axis.XP.rotationDegrees(t < 45.0 ? 70 : 10));
            pose.scale(0.45F, 0.45F, 0.45F);
            mc.getItemRenderer().renderStatic(new ItemStack(t < 45.0 ? Items.WRITABLE_BOOK : Items.BOOK), ItemDisplayContext.FIXED,
                    LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY, pose, buffers, niveau, 0);
            pose.popPose();
        }
        // la lumière d'en bas de la Mairie : un soupirail sur la face nord, qui tremble, puis s'éteint
        {
            Vec3 s = monde(v(15.6, 1.08, 42.93)).subtract(cam);
            pose.pushPose();
            pose.translate(s.x, s.y, s.z);
            pose.scale(1.8F, 0.45F, 0.06F);
            BlockState b = soupirailAllume ? (Math.sin(t * 11) + Math.sin(t * 27.3) > -1.4 ? Blocks.OCHRE_FROGLIGHT : Blocks.GLOWSTONE).defaultBlockState()
                    : Blocks.BLACK_CONCRETE.defaultBlockState();
            mc.getBlockRenderer().renderSingleBlock(b, pose, buffers, soupirailAllume ? LightTexture.FULL_BRIGHT : LightTexture.pack(4, 4), OverlayTexture.NO_OVERLAY);
            pose.popPose();
        }
        buffers.endBatch();
        // les noms au-dessus des têtes : ni en gros plan, ni trop loin
        for (Acteur a : PNJ) {
            etiquette(pose, buffers, a, cam);
        }
        for (Arrivant j : JOUEURS) {
            etiquette(pose, buffers, j.a, cam);
        }
        buffers.endBatch();
    }

    private static void dessiner(PoseStack pose, MultiBufferSource buffers, Acteur a, Vec3 cam) {
        if (!a.visible) {
            return;
        }
        Vec3 p = monde(a.pos).subtract(cam);
        float lacet = (float) -Math.toDegrees(a.ang);
        if (a.joueur) {
            Effets.joueur(pose, buffers, a.skin, a.fin, p, lacet, a.posture(), 0xFFFFFF);
        } else if (a.taille != 1) {
            pose.pushPose();
            pose.translate(p.x, p.y, p.z);
            pose.scale(a.taille, a.taille, a.taille);
            Effets.personnage(pose, buffers, a.skin, Vec3.ZERO, lacet, a.posture(), 1F, Effets.SANS, Effets.NOIR, 1F);
            pose.popPose();
        } else {
            Effets.personnage(pose, buffers, a.skin, p, lacet, a.posture(), 1F, Effets.SANS, Effets.NOIR, 1F);
        }
    }

    /** La voile du kit de départ (comme ParachuteRenderer), à la couleur du joueur, qui s'effondre à l'atterrissage. */
    private static void voile(PoseStack pose, MultiBufferSource buffers, Arrivant j, Vec3 cam) {
        var blocs = Minecraft.getInstance().getBlockRenderer();
        Vec3 p = monde(j.posVoile).subtract(cam);
        pose.pushPose();
        pose.translate(p.x, p.y, p.z);
        pose.mulPose(Axis.YP.rotation((float) j.angVoile));
        pose.mulPose(Axis.XP.rotation((float) j.penteVoile));
        pose.scale(1F, (float) j.ecraseVoile, 1F);
        final float taille = 0.5F;
        for (int i = -3; i <= 3; i++) {
            float hauteur = 3.6F - (i * i) * 0.09F;
            for (int k = -1; k <= 1; k++) {
                pose.pushPose();
                pose.translate(i * taille - taille / 2, hauteur, k * taille - taille / 2);
                pose.scale(taille, taille * 0.4F, taille);
                blocs.renderSingleBlock(Math.floorMod(i, 2) == 0 ? j.voile : Blocks.WHITE_WOOL.defaultBlockState(), pose, buffers,
                        LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY);
                pose.popPose();
            }
        }
        VertexConsumer lignes = buffers.getBuffer(RenderType.lines());
        Matrix4f m = pose.last().pose();
        float[][] coins = {{-1.6F, 3.0F, -0.6F}, {1.6F, 3.0F, -0.6F}, {-1.6F, 3.0F, 0.6F}, {1.6F, 3.0F, 0.6F}};
        for (float[] c : coins) {
            lignes.addVertex(m, c[0], c[1], c[2]).setColor(40, 40, 40, 255).setNormal(pose.last(), 0, -1, 0);
            lignes.addVertex(m, 0, 1.3F, 0).setColor(40, 40, 40, 255).setNormal(pose.last(), 0, -1, 0);
        }
        pose.popPose();
    }

    private static void etiquette(PoseStack pose, MultiBufferSource buffers, Acteur a, Vec3 cam) {
        if (!a.visible) {
            return;
        }
        Vec3 haut = monde(a.pos).add(0, 2.15 * a.taille, 0);
        double d = haut.distanceTo(cam);
        if ((d < 7.5 || d > 40) && !(a.joueur && t < 7)) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        Font font = mc.font;
        Vec3 p = haut.subtract(cam);
        pose.pushPose();
        pose.translate(p.x, p.y, p.z);
        pose.mulPose(mc.getEntityRenderDispatcher().cameraOrientation());
        float e = t < 7 ? 0.09F : 0.018F;
        pose.scale(e, -e, e);
        float x = -font.width(a.nom) / 2F;
        int fond = (int) (mc.options.getBackgroundOpacity(0.25F) * 255) << 24;
        font.drawInBatch(a.nom, x, 0, a.couleurNom, false, pose.last().pose(), buffers, Font.DisplayMode.NORMAL, fond, LightTexture.FULL_BRIGHT);
        pose.popPose();
    }

    /** Les bandes noires, la réplique en cours, le titre, les fondus. */
    private static void dessinerEcran(GuiGraphics g) {
        Minecraft mc = Minecraft.getInstance();
        int w = g.guiWidth();
        int h = g.guiHeight();
        int bande = h / 9;
        g.fill(0, 0, w, bande, 0xFF000000);
        g.fill(0, h - bande, w, h, 0xFF000000);
        if (!titre.isEmpty() && titreAlpha > 0.02F) {
            int alpha = Mth.clamp((int) (titreAlpha * 255), 8, 255) << 24;
            Ios.texteCentre(g, mc.font, titre, w / 2F, h / 2F - 26, 2.8F, alpha | 0xFFC83A, true);
            if (!sousTitre.isEmpty()) {
                Ios.texteCentre(g, mc.font, sousTitre, w / 2F, h / 2F + 6, 1F, alpha | 0xF0F0F0, true);
            }
        }
        if (noir < 1) {
            for (Replique r : REPLIQUES) {
                if (t < r.debut() || t >= r.fin()) {
                    continue;
                }
                String qui = r.qui().toUpperCase() + (r.note() != null ? " (" + r.note() + ")" : "");
                var lignes = mc.font.split(Component.literal("« " + r.texte() + " »"), Math.min(w - 60, 420));
                int y = h - bande - 8 - lignes.size() * 10 - 12;
                g.drawCenteredString(mc.font, qui, w / 2, y, r.couleur());
                for (int i = 0; i < lignes.size(); i++) {
                    g.drawCenteredString(mc.font, lignes.get(i), w / 2, y + 12 + i * 10, r.couleur() == VOIX ? 0xFFE4DCFF : 0xFFFFFFFF);
                }
                break;
            }
        }
        if (noir > 0) {
            g.fill(0, 0, w, h, Mth.clamp((int) (noir * 255), 0, 255) << 24);
        }
    }

    // ================================================================ branchements

    @EventBusSubscriber(modid = Cabane.MODID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
    public static final class Enregistrement {
        @SubscribeEvent
        public static void couches(RegisterGuiLayersEvent event) {
            event.registerAboveAll(Cabane.id("accueil"), (g, delta) -> {
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
            dessinerMonde(event.getPoseStack(), event.getCamera().getPosition(), Minecraft.getInstance().renderBuffers().bufferSource());
        }

        /** Les vrais joueurs et les vrais PNJ de la scène s'effacent : leurs doubles jouent à leur place. */
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

        /** Les noms des vrais PNJ restés à leur place (Ovsie, Karim…) : pas d'énorme étiquette quand la caméra passe tout près. */
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
            if (actif && !event.getName().equals(Cabane.id("accueil"))) {
                event.setCanceled(true);
            }
        }
    }

    private ClientAccueil() {
    }
}
