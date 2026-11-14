package fr.cabane.client.donjon;

import com.mojang.blaze3d.vertex.PoseStack;
import fr.cabane.Cabane;
import fr.cabane.client.cinema.ClientAccueil;
import fr.cabane.client.cinema.ClientCinema;
import fr.cabane.client.cinema.ClientDecollage;
import fr.cabane.client.cinema.Effets;
import fr.cabane.client.cinema.Effets.Posture;
import fr.cabane.client.titans.ClientTitans;
import fr.cabane.client.ui.Ios;
import fr.cabane.donjon.Donjon;
import fr.cabane.donjon.DonjonBuilder;
import fr.cabane.hub.Decollage;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.ReceivingLevelScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.client.resources.PlayerSkin;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
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
import org.joml.Vector3f;

/**
 * Le portail de rang S (30 s), sur l'écran de chacun de ceux qui entrent, sur la vraie avenue du sud du village.
 *
 * <ol>
 * <li>0–6 s : l'air se fend au-dessus de l'avenue, une fissure de lumière, puis le grand portail bleu s'ouvre d'un coup
 * (fenêtre du Système : « ALERTE, un portail vient d'apparaître ») ;</li>
 * <li>6–9,6 s : vu d'en bas, la spirale qui tourne (« Rang du portail : S ») ;</li>
 * <li>9,6–19,6 s : Sung Jin-woo se tourne vers le groupe et leur parle, le premier joueur répond « On y va. » ;</li>
 * <li>19,6–26,8 s : ils marchent dans le portail, l'un après l'autre, et disparaissent dans un éclat bleu ; Jin-woo,
 * les yeux violets, en dernier ;</li>
 * <li>26,8–30 s : la caméra plonge dans la spirale, le tunnel, la lumière blanche au bout. Le serveur les emmène alors
 * dans le donjon, et la fenêtre du Système l'annonce ({@link #arrivee}).</li>
 * </ol>
 *
 * <p>Les acteurs sont des doubles dessinés ici (chaque joueur avec son skin) ; les vrais joueurs et le vrai PNJ de
 * Jin-woo sont cachés pendant la scène. Coordonnées relatives au centre de la place (x vers l'est, z vers le sud) ;
 * angles : 0 = regarde le sud, π/2 = l'est. Le portail de sortie du donjon est dessiné ici aussi.
 */
public final class ClientPortail {
    private static final double DUREE = Donjon.DUREE;
    private static final double OUEST = -Math.PI / 2;
    private static final int VIOLET = 0xFFB98CFF;

    static boolean actif;
    private static boolean finiEnvoye;
    private static ClientLevel niveau;
    private static long debutNanos;
    private static double t;
    private static double tPrecedent;
    private static Entity objectif;
    private static CameraType vueAvant;
    private static boolean guiAvant;
    private static net.minecraft.core.BlockPos centre = net.minecraft.core.BlockPos.ZERO;

    private static Vec3 camera = Vec3.ZERO;
    private static Vec3 vise = Vec3.ZERO;
    private static float fov = 50;
    private static float noir;
    private static float blanc;
    private static float ouverture;
    private static float fissure;
    private static boolean tunnel;
    private static double profondeur;

    /** Un personnage de la scène : sa place (pieds), sa direction, sa posture, recalculées à chaque image. */
    private static final class Acteur {
        final String nom;
        final ResourceLocation skin;
        final boolean fin;
        Vec3 pos = Vec3.ZERO;
        Vec3 depart = Vec3.ZERO;
        double ang;
        boolean visible = true;
        Posture posture = Posture.DEBOUT;
        float teteX;
        float teteY;
        float alpha = 1;
        int teinte = 0xFFFFFF;
        double marche;
        boolean entre;
        /** Son éclat bleu a déjà été joué. */
        boolean eclate;

        Acteur(String nom, ResourceLocation skin, boolean fin) {
            this.nom = nom;
            this.skin = skin;
            this.fin = fin;
        }
    }

    private record Replique(double debut, double fin, String qui, int couleur, String texte) {
    }

    private static Acteur jinwoo;
    private static final List<Acteur> GROUPE = new ArrayList<>();
    private static final List<Replique> REPLIQUES = new ArrayList<>();

    // ---------------------------------------------------------------- les lieux
    private static final Vec3 PORTAIL = Donjon.PORTAIL;
    private static final double R = Donjon.RAYON_PORTAIL;
    /** Le portail regarde le nord (vers le village, d'où vient le groupe). */
    private static final Vec3 FACE = new Vec3(0, 0, -1);
    private static final Vec3 JINWOO = Donjon.JINWOO;
    /** Le groupe, en rangs de cinq face au portail. */
    private static final double Z_RANG = 51.0;
    private static final double VITESSE = 3.0;
    private static final double DEPART_MARCHE = 19.6;
    private static final double DEPART_JINWOO = 22.6;
    private static final double PLONGEE = 26.8;

    private static Vec3 v(double x, double y, double z) {
        return new Vec3(x, y, z);
    }

    private static Vec3 monde(Vec3 p) {
        return new Vec3(centre.getX() + p.x, centre.getY() + p.y, centre.getZ() + p.z);
    }

    // ================================================================ le top départ

    public static void demarrer(Donjon.Debut p) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || p.joueurs().isEmpty()) {
            return;
        }
        ClientCinema.arreter();
        ClientDecollage.arreter();
        ClientAccueil.arreter();
        ClientTitans.arreter();
        arrivee = -1;
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
        finiEnvoye = false;
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
        GROUPE.clear();
    }

    private static void preparer(List<Decollage.Passager> liste) {
        Minecraft mc = Minecraft.getInstance();
        jinwoo = new Acteur("Sung Jin-woo", Cabane.id("textures/entity/pnj/jinwoo.png"), false);
        jinwoo.depart = JINWOO;
        GROUPE.clear();
        int n = liste.size();
        for (int i = 0; i < n; i++) {
            Decollage.Passager pa = liste.get(i);
            PlayerInfo info = mc.getConnection() == null ? null : mc.getConnection().getPlayerInfo(pa.id());
            PlayerSkin skin = info != null ? info.getSkin() : DefaultPlayerSkin.get(pa.id());
            Acteur a = new Acteur(pa.nom(), skin.texture(), skin.model() == PlayerSkin.Model.SLIM);
            int rang = i / 5;
            int dansRang = Math.min(5, n - rang * 5);
            int col = i % 5;
            double x = 0.5 + (col - (dansRang - 1) / 2.0) * 1.35;
            a.depart = v(x, 1, Z_RANG - rang * 1.7);
            // ils partent l'un après l'autre : le milieu d'abord, puis les côtés, rang par rang
            a.marche = DEPART_MARCHE + rang * 1.2 + Math.abs(col - (dansRang - 1) / 2.0) * 0.32 + (col % 2) * 0.12;
            GROUPE.add(a);
        }
        REPLIQUES.clear();
        String premier = n > 0 ? liste.get(0).nom() : "";
        REPLIQUES.add(new Replique(9.9, 12.9, "Sung Jin-woo", VIOLET, "Un portail de rang S vient de s'ouvrir."));
        REPLIQUES.add(new Replique(12.9, 15.7, "Sung Jin-woo", VIOLET, "Derrière, il y a un donjon. Et tout au fond, son boss."));
        REPLIQUES.add(new Replique(16.0, 17.8, premier, 0xFFFFFFFF, "On y va."));
        REPLIQUES.add(new Replique(17.9, 19.8, "Sung Jin-woo", VIOLET, "Restez groupés. Et ne mourez pas."));
    }

    // ================================================================ petits calculs

    private static double seg(double x, double a, double b) {
        return Mth.clamp((x - a) / (b - a), 0, 1);
    }

    private static double lisse(double u) {
        return u * u * (3 - 2 * u);
    }

    private static Vec3 entre(Vec3 a, Vec3 b, double u) {
        return a.lerp(b, u);
    }

    private static double vers(Vec3 de, Vec3 a) {
        return Math.atan2(a.x - de.x, a.z - de.z);
    }

    private static Vec3 tete(Acteur a) {
        return a.pos.add(0, 1.52, 0);
    }

    private static Vec3 avant(double ang) {
        return v(Math.sin(ang), 0, Math.cos(ang));
    }

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

    /** En marche : les bras et les jambes qui balancent ; un peu penché quand on va vite. */
    private static Posture marche(double phase, float ampleur) {
        float s = (float) Math.sin(phase) * ampleur;
        return new Posture(0, 0, ampleur * 6, s * 0.9F, 0, 0.08F, -s * 0.9F, 0, -0.08F, -s, 0.03F, s, -0.03F);
    }

    /** L'ouverture du portail : rien, puis il s'ouvre d'un coup avec un petit rebond. */
    private static float ouvertureA(double instant) {
        if (instant < 3.0) {
            return 0;
        }
        double u = seg(instant, 3.0, 4.4);
        double o = 1 - Math.pow(1 - u, 3) + Math.sin(u * Math.PI) * 0.12;
        return (float) o;
    }

    // ================================================================ la scène à l'instant t

    private static void calculer(double maintenant) {
        t = maintenant;
        fissure = (float) seg(t, 1.0, 3.0);
        ouverture = ouvertureA(t);
        Vec3 portailSol = v(PORTAIL.x, 1, PORTAIL.z);

        // Jin-woo : il regarde l'avenue, se tourne vers le groupe pour parler, puis entre le dernier
        jinwoo.visible = true;
        jinwoo.alpha = 1;
        jinwoo.teinte = 0xFFFFFF;
        jinwoo.posture = Posture.DEBOUT;
        jinwoo.teteX = 0;
        jinwoo.teteY = 0;
        Vec3 milieuGroupe = v(0.5, 1, Z_RANG - 0.8);
        if (t < DEPART_JINWOO) {
            jinwoo.pos = JINWOO;
            double versPortail = vers(JINWOO, portailSol);
            double versGroupe = vers(JINWOO, milieuGroupe);
            double ang = OUEST;
            if (t > 3.2) {
                ang = OUEST + (versPortail - OUEST) * lisse(seg(t, 3.2, 4.2));
            }
            if (t > 9.6) {
                ang = versPortail + (versGroupe - versPortail) * lisse(seg(t, 9.6, 10.4));
            }
            if (t > 19.8) {
                ang = versGroupe + (versPortail - versGroupe) * lisse(seg(t, 19.8, 20.5));
            }
            jinwoo.ang = ang;
            if (t > 3.2 && t < 9.6) {
                regarder(jinwoo, PORTAIL);
            } else if (t >= 9.6 && t < 19.8) {
                regarder(jinwoo, milieuGroupe.add(0, 1.5, 0));
            } else if (t >= 19.8) {
                regarder(jinwoo, PORTAIL.add(0, -2, 0));
            }
        } else {
            double d = (t - DEPART_JINWOO) * 4.0;
            Vec3 but = portailSol.add(0, 0, 0.6);
            double total = JINWOO.distanceTo(but);
            double u = Math.min(1, d / total);
            jinwoo.pos = entre(JINWOO, but, u);
            jinwoo.ang = vers(JINWOO, but);
            jinwoo.posture = marche(t * 9, 0.55F);
            regarder(jinwoo, PORTAIL);
            double reste = PORTAIL.z - jinwoo.pos.z;
            if (reste < 1.2) {
                jinwoo.alpha = (float) Mth.clamp(reste / 1.2, 0, 1);
                jinwoo.teinte = 0x9FB8FF;
            }
            jinwoo.visible = u < 1 && jinwoo.alpha > 0.03F;
        }

        // le groupe : debout, la tête levée vers le portail ; tourné vers Jin-woo quand il parle ; puis la marche
        for (int i = 0; i < GROUPE.size(); i++) {
            Acteur a = GROUPE.get(i);
            a.alpha = 1;
            a.teinte = 0xFFFFFF;
            a.posture = Posture.DEBOUT;
            a.teteX = 0;
            a.teteY = 0;
            a.visible = true;
            if (t < a.marche) {
                a.pos = a.depart;
                a.ang = 0;
                if (t > 1.2 && t < 9.6) {
                    regarder(a, PORTAIL);
                } else if (t >= 9.6 && t < 19.8) {
                    regarder(a, tete(jinwoo));
                } else {
                    regarder(a, PORTAIL);
                }
                // reflets bleus du portail sur les visages
                if (ouverture > 0.2) {
                    a.teinte = 0xD8E4FF;
                }
            } else {
                double d = (t - a.marche) * VITESSE;
                Vec3 but = v(a.depart.x + (PORTAIL.x - a.depart.x) * 0.45, 1, PORTAIL.z + 0.4);
                double total = a.depart.distanceTo(but);
                double u = Math.min(1, d / total);
                a.pos = entre(a.depart, but, u);
                a.ang = vers(a.depart, but);
                a.posture = marche((t - a.marche) * 10 + i, 0.7F);
                regarder(a, PORTAIL);
                double reste = PORTAIL.z - a.pos.z;
                a.teinte = 0xC8D8FF;
                if (reste < 1.3) {
                    a.alpha = (float) Mth.clamp(reste / 1.3, 0, 1);
                    a.teinte = 0x8FB0FF;
                }
                boolean dedans = reste < 0.05;
                a.visible = !dedans;
                a.entre = dedans;
            }
        }
        // celui qui répond : il hoche la tête
        if (!GROUPE.isEmpty() && t > 16.0 && t < 17.8) {
            Acteur p = GROUPE.get(0);
            p.teteX += (float) Math.sin((t - 16) * 9) * 0.12F;
        }

        cameraEtEcran();
    }

    // ================================================================ les plans

    private static void plan(double a, double b, float focale, Vec3 c0, Vec3 c1, Vec3 v0, Vec3 v1) {
        if (t >= a && t < b) {
            double u = lisse(seg(t, a, b));
            camera = entre(c0, c1, u);
            vise = entre(v0, v1, u);
            fov = focale;
        }
    }

    private static void cameraEtEcran() {
        tunnel = false;
        Vec3 c = PORTAIL;
        Vec3 jw = tete(jinwoo);
        // 1. l'avenue, de haut : l'air se fend, le portail s'ouvre
        plan(0, 6.0, 55, v(-9, 8, 35), v(-7, 6.6, 39.5), c.add(1, -1.5, 0), c.add(0, -0.5, 0));
        // 2. vu d'en bas, entre les jambes du groupe : la spirale
        plan(6.0, 9.6, 62, v(-2.6, 1.15, 52.6), v(-2.0, 1.35, 54.6), c.add(0, 1, 0), c.add(0, 0.5, 0));
        // 3. Jin-woo, en plan moyen, qui se tourne vers le groupe
        plan(9.6, 12.9, 45, v(2.6, 2.4, 49.4), v(3.3, 2.3, 48.8), jw.add(0, -0.25, 0), jw.add(0, -0.2, 0));
        plan(12.9, 15.8, 38, v(4.3, 2.1, 49.2), v(4.6, 2.0, 48.6), jw.add(0, -0.15, 0), jw.add(0, -0.1, 0));
        // 4. le contrechamp : les visages du groupe, éclairés par le portail
        plan(15.8, 17.9, 50, v(0.5, 2.3, Z_RANG + 4.4), v(0.5, 2.1, Z_RANG + 3.8), v(0.5, 1.6, Z_RANG - 0.6), v(0.5, 1.6, Z_RANG - 0.6));
        // 5. derrière le groupe, Jin-woo au bord du cadre : « Restez groupés »
        plan(17.9, 19.6, 58, v(-1.5, 3.6, Z_RANG - 8.5), v(-0.8, 3.2, Z_RANG - 7.5), c.add(0, -2, 0), c.add(0, -2.2, 0));
        // 6. ils marchent : la caméra les suit, basse, dans leur dos
        double zg = Z_RANG;
        for (Acteur a : GROUPE) {
            zg = Math.min(zg, a.pos.z);
        }
        if (t >= 19.6 && t < 23.0) {
            double u = lisse(seg(t, 19.6, 23.0));
            camera = v(1.8 - u * 0.6, 2.1, Math.min(zg - 4.2, PORTAIL.z - 6));
            vise = c.add(0, -2.5, 0);
            fov = 60;
        }
        // 7. de côté, tout près du portail : les silhouettes avalées par la spirale ; Jin-woo en dernier
        plan(23.0, PLONGEE, 52, v(9.5, 2.8, PORTAIL.z - 5.5), v(8.2, 2.6, PORTAIL.z - 4.2), c.add(-0.5, -3.2, -0.8), c.add(0, -2.8, -0.4));
        // 8. la plongée : la caméra fonce dans la spirale, puis dans le tunnel
        if (t >= PLONGEE) {
            double u = t - PLONGEE;
            // de l'avenue jusqu'au portail en 0,9 s, puis de plus en plus vite dans le tunnel
            double z = u < 0.9 ? PORTAIL.z - 7 + 7 * lisse(u / 0.9) : PORTAIL.z + (u - 0.9) * (u - 0.9) * 22 + (u - 0.9) * 6;
            double y = PORTAIL.y - 2.2 * (1 - seg(u, 0, 0.9));
            camera = v(PORTAIL.x, y, z);
            vise = v(PORTAIL.x, PORTAIL.y, z + 10);
            fov = (float) (60 + 45 * seg(u, 0.4, 1.4));
            tunnel = z > PORTAIL.z - 0.4;
            profondeur = z - PORTAIL.z;
        }
        // la caméra tremble quand le portail s'ouvre, et dans le tunnel
        float secousse = (float) (Math.max(0, 1 - Math.abs(t - 3.3) / 0.9) * 1.4 + (t > PLONGEE + 0.8 ? 0.35 : 0));
        if (t > 4.4 && t < 9.6) {
            secousse += 0.08F;
        }
        if (secousse > 0) {
            camera = camera.add(Math.sin(t * 53) * secousse * 0.1, Math.sin(t * 47 + 1) * secousse * 0.08, Math.cos(t * 61) * secousse * 0.1);
        }
        camera = monde(camera);
        vise = monde(vise);

        // les fondus : l'ouverture du noir, un flash au moment où le portail s'ouvre, le blanc de la fin
        noir = (float) (1 - seg(t, 0, 0.7));
        double flash = Math.max(0, 1 - Math.abs(t - 3.05) / 0.25) * 0.85;
        double fin = seg(t, PLONGEE + 1.7, PLONGEE + 2.5);
        blanc = (float) Math.max(flash, fin);
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

    private static void evenements() {
        // la fissure qui craque, puis le portail qui s'ouvre
        if (passe(1.0)) {
            son(SoundEvents.AMETHYST_BLOCK_RESONATE, 1F, 0.5F);
            son(SoundEvents.GLASS_BREAK, 0.6F, 0.5F);
        }
        if (passe(1.4)) {
            son(SoundEvents.NOTE_BLOCK_BELL.value(), 0.8F, 1.5F);
            son(SoundEvents.AMETHYST_BLOCK_CHIME, 1F, 0.8F);
        }
        if (passe(2.0)) {
            son(SoundEvents.GLASS_BREAK, 0.8F, 0.6F);
            son(SoundEvents.LIGHTNING_BOLT_THUNDER, 0.25F, 1.8F);
        }
        if (passe(2.6)) {
            son(SoundEvents.GLASS_BREAK, 1F, 0.7F);
        }
        if (passe(3.0)) {
            son(SoundEvents.WARDEN_SONIC_BOOM, 1F, 0.55F);
            son(SoundEvents.END_PORTAL_SPAWN, 0.7F, 1F);
            son(SoundEvents.BEACON_ACTIVATE, 1F, 0.6F);
        }
        // le souffle du portail, tant qu'il est ouvert
        for (double m = 4.2; m < PLONGEE; m += 3.4) {
            if (passe(m)) {
                son(SoundEvents.PORTAL_AMBIENT, 0.55F, 0.6F);
            }
        }
        if (passe(6.4)) {
            son(SoundEvents.NOTE_BLOCK_BELL.value(), 0.8F, 1.2F);
            son(SoundEvents.AMETHYST_BLOCK_CHIME, 1F, 0.6F);
        }
        // chacun qui entre : un éclat bleu
        for (Acteur a : GROUPE) {
            if (a.entre && !a.eclate) {
                a.eclate = true;
                son(SoundEvents.ENDERMAN_TELEPORT, 0.45F, 0.6F);
                son(SoundEvents.AMETHYST_BLOCK_RESONATE, 0.7F, 1.4F);
                eclat(a.pos);
            }
        }
        if (passe(DEPART_JINWOO + 4.0)) {
            son(SoundEvents.ENDERMAN_TELEPORT, 0.6F, 0.45F);
            eclat(v(PORTAIL.x, 1, PORTAIL.z));
        }
        if (passe(PLONGEE)) {
            son(SoundEvents.PORTAL_TRIGGER, 0.6F, 1.3F);
            son(SoundEvents.ELYTRA_FLYING, 0.8F, 1.3F);
        }
        if (passe(PLONGEE + 1.8)) {
            son(SoundEvents.BEACON_DEACTIVATE, 1F, 1.6F);
            son(SoundEvents.AMETHYST_BLOCK_RESONATE, 1F, 2F);
        }
        particules();
    }

    /** Un éclat bleu là où quelqu'un passe le portail. */
    private static void eclat(Vec3 relatif) {
        if (niveau == null) {
            return;
        }
        Vec3 p = monde(relatif.add(0, 1, 0));
        for (int i = 0; i < 26; i++) {
            double a = i * 0.9;
            niveau.addParticle(ParticleTypes.END_ROD, true, p.x, p.y + (i % 5) * 0.2, p.z, Math.cos(a) * 0.12, (i % 3 - 1) * 0.06, Math.sin(a) * 0.05 - 0.05);
            niveau.addParticle(ParticleTypes.ELECTRIC_SPARK, true, p.x, p.y, p.z, Math.cos(a) * 0.3, (i % 4) * 0.08, Math.sin(a) * 0.3);
        }
    }

    /** Le portail aspire : des étincelles et de la poussière bleue tirées vers son cœur ; de la lueur au sol. */
    private static void particules() {
        if (niveau == null || ouverture < 0.3 || t > PLONGEE + 0.6) {
            return;
        }
        Vec3 c = monde(PORTAIL);
        java.util.Random r = new java.util.Random((long) (t * 1000));
        DustParticleOptions bleu = new DustParticleOptions(new Vector3f(0.3F, 0.6F, 1F), 1.4F);
        for (int i = 0; i < 6; i++) {
            double a = r.nextDouble() * Math.PI * 2;
            double d = R * (1.15 + r.nextDouble() * 0.9);
            Vec3 p = c.add(Math.cos(a) * d, Math.sin(a) * d, -0.3 - r.nextDouble() * 2.5);
            Vec3 vers = c.subtract(p).normalize().scale(0.25 + r.nextDouble() * 0.2);
            niveau.addParticle(ParticleTypes.END_ROD, true, p.x, p.y, p.z, vers.x, vers.y, vers.z);
            niveau.addParticle(bleu, true, p.x, p.y, p.z, 0, 0, 0);
        }
        for (int i = 0; i < 3; i++) {
            double x = c.x + (r.nextDouble() - 0.5) * R * 2.2;
            double z = c.z - r.nextDouble() * 6;
            niveau.addParticle(ParticleTypes.SOUL_FIRE_FLAME, true, x, centre.getY() + 1.05, z, 0, 0.02, 0.04);
            niveau.addParticle(ParticleTypes.REVERSE_PORTAL, true, x, centre.getY() + 1.2, z, 0, 0.05, 0);
        }
    }

    // ================================================================ le dessin

    private static void dessinerMonde(PoseStack pose, Vec3 cam, MultiBufferSource.BufferSource buffers) {
        float temps = (float) t;
        Vec3 c = monde(PORTAIL).subtract(cam);
        dessiner(pose, buffers, jinwoo, cam);
        for (Acteur a : GROUPE) {
            dessiner(pose, buffers, a, cam);
        }
        buffers.endBatch();
        // les yeux de Jin-woo : deux lueurs violettes, quand il parle et quand il entre
        if (jinwoo.visible && t > 9.6) {
            Vec3 h = monde(jinwoo.pos).subtract(cam).add(0, 1.66 - jinwoo.teteX * 0.05, 0);
            double ang = jinwoo.ang + jinwoo.teteY;
            Vec3 f = avant(ang);
            Vec3 d = v(-f.z, 0, f.x);
            for (int s : new int[] {-1, 1}) {
                Vec3 oeil = h.add(f.scale(0.27)).add(d.scale(0.11 * s));
                Effets.sphere(pose, buffers, oeil, 0.045 * jinwoo.alpha, 0.62F, 0.4F, 1F, 0.9F * jinwoo.alpha);
            }
            Effets.aura(pose, buffers, monde(jinwoo.pos).subtract(cam), 0.25F * jinwoo.alpha, 0.35F, 0.12F, 0.75F, temps);
            buffers.endBatch();
        }
        if (tunnel) {
            RenduPortail.tunnel(pose, buffers, c, v(0, 0, 1), 4.2, 70, temps, (float) seg(t, PLONGEE + 1.3, PLONGEE + 2.3));
        } else {
            RenduPortail.dessiner(pose, buffers, c, FACE, R, ouverture, fissure, temps);
        }
    }

    private static void dessiner(PoseStack pose, MultiBufferSource buffers, Acteur a, Vec3 cam) {
        if (!a.visible || a.alpha <= 0.02F) {
            return;
        }
        Vec3 p = monde(a.pos).subtract(cam);
        float lacet = (float) -Math.toDegrees(a.ang);
        Effets.joueur(pose, buffers, a.skin, a.fin, p, lacet, avecTete(a, a.posture), a.teinte, a.alpha);
    }

    /** Une fenêtre du Système : un cadre bleu nuit au bord lumineux, un titre, des lignes ; elle se déplie en apparaissant. */
    private static void fenetre(GuiGraphics g, double depuis, double duree, String titre, String[] lignes, String grand, int couleurGrand) {
        if (depuis < 0 || depuis > duree) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        Font f = mc.font;
        float ouvre = (float) lisse(seg(depuis, 0, 0.28));
        float ferme = (float) (1 - seg(depuis, duree - 0.35, duree));
        float k = Math.min(ouvre, ferme);
        if (k <= 0.01F) {
            return;
        }
        int w = g.guiWidth();
        int h = g.guiHeight();
        int larg = 30;
        for (String l : lignes) {
            larg = Math.max(larg, f.width(l));
        }
        larg = Math.max(larg + 36, 190);
        int haut = 34 + lignes.length * 12 + (grand.isEmpty() ? 0 : 30);
        int x0 = w / 2 - larg / 2;
        int y0 = (int) (h * 0.2);
        int alpha = Mth.clamp((int) (k * 255), 4, 255);
        g.pose().pushPose();
        g.pose().translate(w / 2F, y0 + haut / 2F, 0);
        g.pose().scale(1F, k, 1F);
        g.pose().translate(-w / 2F, -(y0 + haut / 2F), 0);
        // le halo, le fond, le bord
        g.fill(x0 - 3, y0 - 3, x0 + larg + 3, y0 + haut + 3, (alpha / 5) << 24 | 0x4FA8FF);
        g.fill(x0, y0, x0 + larg, y0 + haut, (alpha * 205 / 255) << 24 | 0x07112A);
        int bord = alpha << 24 | 0x8FD3FF;
        g.fill(x0, y0, x0 + larg, y0 + 1, bord);
        g.fill(x0, y0 + haut - 1, x0 + larg, y0 + haut, bord);
        g.fill(x0, y0, x0 + 1, y0 + haut, bord);
        g.fill(x0 + larg - 1, y0, x0 + larg, y0 + haut, bord);
        // l'en-tête : un « ! » dans un cadre, le titre, un trait
        int cx = w / 2;
        int tw = f.width(titre) + 16;
        int bx = cx - tw / 2 - 2;
        g.fill(bx - 8, y0 + 7, bx + 2, y0 + 17, bord);
        g.fill(bx - 7, y0 + 8, bx + 1, y0 + 16, alpha << 24 | 0x07112A);
        g.drawString(f, "!", bx - 4, y0 + 8, alpha << 24 | 0xFFFFFF, false);
        Ios.texteCentre(g, f, titre, cx + 6, y0 + 8, 1F, alpha << 24 | 0xBFE6FF, true);
        g.fill(x0 + 10, y0 + 22, x0 + larg - 10, y0 + 23, (alpha / 2) << 24 | 0x8FD3FF);
        int y = y0 + 29;
        for (String l : lignes) {
            g.drawCenteredString(f, l, cx, y, alpha << 24 | 0xFFFFFF);
            y += 12;
        }
        if (!grand.isEmpty()) {
            Ios.texteCentre(g, f, grand, cx, y + 1, 2.6F, alpha << 24 | (couleurGrand & 0xFFFFFF), true);
        }
        g.pose().popPose();
    }

    /** Les bandes noires, la réplique en cours, les fenêtres du Système, les fondus. */
    private static void dessinerEcran(GuiGraphics g) {
        Minecraft mc = Minecraft.getInstance();
        int w = g.guiWidth();
        int h = g.guiHeight();
        int bande = h / 9;
        g.fill(0, 0, w, bande, 0xFF000000);
        g.fill(0, h - bande, w, h, 0xFF000000);
        if (noir < 1) {
            for (Replique r : REPLIQUES) {
                if (t < r.debut() || t >= r.fin() || r.qui().isEmpty()) {
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
        fenetre(g, t - 1.4, 3.8, "ALERTE", new String[] {"Un portail vient d'apparaître", "au village de Beauvais."}, "", 0);
        fenetre(g, t - 6.4, 3.0, "SYSTÈME", new String[] {"Rang du portail :"}, "S", 0xFFFF3B3B);
        if (noir > 0) {
            g.fill(0, 0, w, h, Mth.clamp((int) (noir * 255), 0, 255) << 24);
        }
        if (blanc > 0) {
            g.fill(0, 0, w, h, Mth.clamp((int) (blanc * 255), 0, 255) << 24 | 0xF2F8FF);
        }
    }

    // ================================================================ l'arrivée dans le donjon

    /** Quand on est arrivé (nanos), -1 sinon ; et combien on est entrés. */
    private static long arrivee = -1;
    /** Quand le paquet d'arrivée est venu : si le donjon ne se charge jamais, le blanc s'en va quand même. */
    private static long arriveeRecue = -1;
    private static int groupe;
    private static String nomDonjon = "";
    /** Après la scène : le blanc tient jusqu'à l'arrivée (au plus quelques secondes). */
    private static long blancTenu = -1;

    public static void arrivee(Donjon.Arrivee p) {
        arreter();
        arrivee = System.nanoTime();
        arriveeRecue = arrivee;
        groupe = p.groupe();
        nomDonjon = p.nom();
        blancTenu = -1;
    }

    private static double depuisArrivee() {
        return arrivee < 0 ? -1 : (System.nanoTime() - arrivee) / 1e9;
    }

    private static void dessinerArrivee(GuiGraphics g) {
        int w = g.guiWidth();
        int h = g.guiHeight();
        if (blancTenu > 0) {
            double d = (System.nanoTime() - blancTenu) / 1e9;
            if (d > 6) {
                blancTenu = -1;
            } else {
                g.fill(0, 0, w, h, 0xFFF2F8FF);
                return;
            }
        }
        double d = depuisArrivee();
        if (d < 0) {
            return;
        }
        // tant que le monde du donjon se charge, on reste dans le blanc
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.level.dimension() != Donjon.DIMENSION || mc.screen instanceof ReceivingLevelScreen) {
            if ((System.nanoTime() - arriveeRecue) / 1e9 > 10) {
                arrivee = -1;
                return;
            }
            g.fill(0, 0, w, h, 0xFFF2F8FF);
            arrivee = System.nanoTime();
            return;
        }
        float b = (float) (1 - seg(d, 0.4, 2.0));
        if (b > 0) {
            g.fill(0, 0, w, h, Mth.clamp((int) (b * 255), 0, 255) << 24 | 0xF2F8FF);
        }
        fenetre(g, d - 1.2, 6.5, "SYSTÈME", new String[] {"Vous êtes entrés dans un donjon de rang S :", nomDonjon.toUpperCase(), "Objectif : vaincre le boss du donjon.",
                groupe > 1 ? "Chasseurs : " + groupe : "Chasseur : seul"}, "", 0);
        if (d > 8) {
            arrivee = -1;
        }
    }

    // ================================================================ les annonces du boss

    private static fr.cabane.donjon.Boss.Annonce annonceEnCours;
    private static long annonceDebut = -1;
    /** Le boss est mort : le portail de sortie de l'arène est ouvert. */
    private static boolean sortieArene;

    public static void annonce(fr.cabane.donjon.Boss.Annonce p) {
        annonceEnCours = p;
        annonceDebut = System.nanoTime();
        son(SoundEvents.NOTE_BLOCK_BELL.value(), 0.8F, 1.3F);
        son(SoundEvents.AMETHYST_BLOCK_CHIME, 1F, 0.7F);
    }

    public static void victoire(fr.cabane.donjon.Boss.Victoire p) {
        sortieArene = p.ouverte();
    }

    private static void dessinerAnnonce(GuiGraphics g) {
        if (annonceEnCours == null) {
            return;
        }
        double d = (System.nanoTime() - annonceDebut) / 1e9;
        double duree = annonceEnCours.grand().isEmpty() ? 6.5 : 5.0;
        if (d > duree) {
            annonceEnCours = null;
            return;
        }
        fenetre(g, d, duree, annonceEnCours.titre(), annonceEnCours.lignes().toArray(new String[0]), annonceEnCours.grand(), annonceEnCours.couleur());
    }

    // ================================================================ le portail de sortie, dans le donjon

    private static void dessinerSortie(PoseStack pose, Vec3 cam, MultiBufferSource.BufferSource buffers) {
        fr.cabane.donjon.TypeDonjon type = fr.cabane.donjon.TypeDonjon.de(cam.x);
        Vec3 s = type.sortie();
        float temps = (float) ((System.nanoTime() / 1e9) % 10000);
        if (cam.distanceToSqr(s) < 90 * 90) {
            RenduPortail.dessiner(pose, buffers, s.subtract(cam), new Vec3(0, 0, 1), DonjonBuilder.RAYON_SORTIE, 1F, 0F, temps);
        }
        Vec3 a = type.sortieArene();
        if (sortieArene && cam.distanceToSqr(a) < 90 * 90) {
            RenduPortail.dessiner(pose, buffers, a.subtract(cam), new Vec3(0, 0, -1), fr.cabane.donjon.Boss.RAYON_SORTIE_ARENE, 1F, 0F, temps);
        }
    }

    private static void particulesSortie(ClientLevel l, Vec3 joueur) {
        Vec3 s = fr.cabane.donjon.TypeDonjon.de(joueur.x).sortie();
        if (joueur.distanceToSqr(s) > 40 * 40) {
            return;
        }
        net.minecraft.util.RandomSource r = l.random;
        double rr = DonjonBuilder.RAYON_SORTIE;
        for (int i = 0; i < 2; i++) {
            double a = r.nextDouble() * Math.PI * 2;
            double d = rr * (1.1 + r.nextDouble() * 0.8);
            Vec3 p = s.add(Math.cos(a) * d, Math.sin(a) * d, 0.3 + r.nextDouble() * 1.5);
            Vec3 vers = s.subtract(p).normalize().scale(0.18);
            l.addParticle(ParticleTypes.END_ROD, p.x, p.y, p.z, vers.x, vers.y, vers.z);
        }
        if (l.getGameTime() % 70 == 0) {
            l.playLocalSound(s.x, s.y, s.z, SoundEvents.PORTAL_AMBIENT, net.minecraft.sounds.SoundSource.AMBIENT, 0.35F, 0.6F, false);
        }
    }

    // ================================================================ branchements

    @EventBusSubscriber(modid = Cabane.MODID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
    public static final class Enregistrement {
        @SubscribeEvent
        public static void couches(RegisterGuiLayersEvent event) {
            event.registerAboveAll(Cabane.id("donjon_portail"), (g, delta) -> {
                if (actif) {
                    dessinerEcran(g);
                } else {
                    dessinerArrivee(g);
                    dessinerAnnonce(g);
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
                if (mc.level != null && mc.player != null && mc.level.dimension() == Donjon.DIMENSION && !mc.isPaused()) {
                    particulesSortie(mc.level, mc.player.position());
                }
                return;
            }
            if (mc.level == null || mc.player == null || mc.level != niveau || ecoule() > DUREE + 14) {
                // la fin de la scène : le blanc tient jusqu'à l'arrivée dans le donjon
                boolean fini = ecoule() > DUREE - 0.5;
                arreter();
                if (fini && arrivee < 0) {
                    blancTenu = System.nanoTime();
                }
                return;
            }
            calculer(Math.min(ecoule(), DUREE));
            evenements();
            tPrecedent = t;
            // le flash blanc : on prévient le serveur, qui nous emmène dans le donjon
            if (!finiEnvoye && t >= DUREE - 0.1) {
                finiEnvoye = true;
                net.neoforged.neoforge.network.PacketDistributor.sendToServer(new Donjon.Fini());
            }
            if (mc.getCameraEntity() != objectif && objectif != null) {
                mc.setCameraEntity(objectif);
            }
        }

        @SubscribeEvent
        public static void image(RenderFrameEvent.Pre event) {
            if (!actif) {
                return;
            }
            double avant = tPrecedent;
            calculer(Math.min(ecoule(), DUREE));
            tPrecedent = avant;
            placerCamera();
        }

        @SubscribeEvent
        public static void dessiner(RenderLevelStageEvent event) {
            // après la météo : les nuages ne passent pas par-dessus le portail
            if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_WEATHER) {
                return;
            }
            Minecraft mc = Minecraft.getInstance();
            if (actif) {
                dessinerMonde(event.getPoseStack(), event.getCamera().getPosition(), mc.renderBuffers().bufferSource());
            } else if (mc.level != null && mc.level.dimension() == Donjon.DIMENSION) {
                dessinerSortie(event.getPoseStack(), event.getCamera().getPosition(), mc.renderBuffers().bufferSource());
            }
        }

        /** Les vrais joueurs et le vrai Jin-woo s'effacent : leurs doubles jouent à leur place. */
        @SubscribeEvent
        public static void cacher(RenderLivingEvent.Pre<?, ?> event) {
            if (!actif) {
                return;
            }
            var e = event.getEntity();
            if (e instanceof Player || (e.getCustomName() != null && e.getCustomName().getString().equals("Sung Jin-woo"))) {
                event.setCanceled(true);
            }
        }

        /** Rien n'est « visé » pendant la scène : sinon des mods affichent la vie de ce qui est devant la caméra. */
        @SubscribeEvent
        public static void rienDeVise(net.neoforged.neoforge.client.event.RenderGuiEvent.Pre event) {
            if (actif) {
                Minecraft mc = Minecraft.getInstance();
                mc.hitResult = null;
                mc.crosshairPickEntity = null;
            }
        }

        /** L'écran « Chargement du terrain » entre le village et le donjon reste blanc, comme le flash. */
        @SubscribeEvent
        public static void chargementBlanc(net.neoforged.neoforge.client.event.ScreenEvent.Render.Post event) {
            if (event.getScreen() instanceof ReceivingLevelScreen && (blancTenu > 0 || arrivee > 0)) {
                event.getGuiGraphics().fill(0, 0, event.getScreen().width, event.getScreen().height, 0xFFF2F8FF);
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
            if (actif && !event.getName().equals(Cabane.id("donjon_portail"))) {
                event.setCanceled(true);
            }
            // pas de minicarte dans le donjon : elle montrerait le chemin, les culs-de-sac et les salles des énigmes
            if (dansLeDonjon() && event.getName().getNamespace().startsWith("xaero")) {
                event.setCanceled(true);
            }
        }

        /** Pas de carte du monde non plus (ni aucun écran de Xaero) dans le donjon. */
        @SubscribeEvent
        public static void pasDeCarte(net.neoforged.neoforge.client.event.ScreenEvent.Opening event) {
            if (dansLeDonjon() && event.getNewScreen() != null && event.getNewScreen().getClass().getName().startsWith("xaero.")) {
                event.setCanceled(true);
                Minecraft mc = Minecraft.getInstance();
                if (mc.player != null) {
                    mc.player.displayClientMessage(Component.literal("Pas de carte dans un donjon : trouvez le chemin vous-mêmes.")
                            .withStyle(net.minecraft.ChatFormatting.GRAY), true);
                }
            }
        }

        private static boolean dansLeDonjon() {
            Minecraft mc = Minecraft.getInstance();
            return mc.level != null && mc.level.dimension() == Donjon.DIMENSION;
        }
    }

    private ClientPortail() {
    }
}
