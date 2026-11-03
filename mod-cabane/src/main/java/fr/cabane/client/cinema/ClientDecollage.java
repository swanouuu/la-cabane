package fr.cabane.client.cinema;

import com.mojang.blaze3d.vertex.PoseStack;
import fr.cabane.Cabane;
import fr.cabane.client.cinema.Effets.Posture;
import fr.cabane.client.ui.Ios;
import fr.cabane.hub.Decollage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import net.minecraft.client.Camera;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.ReceivingLevelScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.renderer.DimensionSpecialEffects;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.client.resources.PlayerSkin;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.Phantom;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.MovementInputUpdateEvent;
import net.neoforged.neoforge.client.event.RegisterDimensionSpecialEffectsEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.client.event.RenderGuiLayerEvent;
import net.neoforged.neoforge.client.event.RenderHandEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * La cinématique du décollage (vol BV2026 pour Beauvais), jouée sur l'écran de chaque passager en même temps, dans la
 * dimension de l'aéroport. Les joueurs embarquent par la passerelle avec leur skin (des inconnus remplissent les sièges
 * vides), les pilotes se préparent, l'avion quitte la porte 12 et décolle ; au-dessus des nuages on discute, l'hôtesse
 * passe avec son chariot ; puis l'orage, les secousses, une nuée de phantoms dans le réacteur droit, l'alarme… et le
 * noir : le serveur réveille tout le monde dans l'avion en feu.
 *
 * <p>Tout (l'avion, les personnages, la caméra, la météo) se calcule ici d'après le temps écoulé sur l'horloge réelle ;
 * le serveur n'envoie que le top départ, les passagers et une graine (pour que chacun voie les mêmes dialogues).
 */
public final class ClientDecollage {
    // ---- le déroulé (en secondes)
    private static final double AEROPORT = 4;
    private static final double PASSERELLE = 10;
    private static final double EMBARQUEMENT = 14.5;
    private static final double COCKPIT = 20;
    private static final double DEPART = 25;
    private static final double DECOLLAGE = 30;
    private static final double MONTEE = 37;
    private static final double NUAGES = 41.5;
    private static final double CABINE = 46.5;
    private static final double CONVERSATIONS = 52.5;
    private static final double DUREE_CONVERSATION = 4.6;
    private static final double HOTESSE = 66.3;
    private static final double ORAGE = 74.3;
    private static final double SECOUSSES = 79;
    private static final double PAF = 85.5;
    /** Le moment où les phantoms percutent le réacteur droit. */
    private static final double IMPACT = 87.5;
    private static final double ALARME = 90;
    private static final double PANIQUE = 93.5;
    private static final double CHUTE = 97;
    private static final double NOIR = 100.5;

    /** Le tarmac de l'aéroport (le niveau où l'on marche). */
    private static final int SOL = fr.cabane.hub.AeroportBuilder.SOL;
    /** L'avion garé à la porte 12 : son origine tombe sur un coin de bloc, le plancher 3 blocs au-dessus du tarmac. */
    private static final Vec3 PORTE_12 = new Vec3(0.5, SOL + 3, 0.5);
    /** Le cercle que décrit l'avion au-dessus des nuages (au-dessus de l'aéroport : le terrain y reste chargé). */
    private static final Vec3 CERCLE = new Vec3(0, 222, 20);
    private static final double RAYON_CERCLE = 95;
    private static final double VITESSE_CROISIERE = 15;

    private static final int OR = 0xFFFFD24A;
    private static final int EQUIPAGE = 0xFF8AC8FF;
    private static final int TOUR = 0xFF9AE07A;
    private static final int HOTESSE_COULEUR = 0xFFFF9AB0;
    private static final int INCONNU = 0xFFD8D8D8;

    private static final String[] NOMS_INCONNUS = {"Kevin", "Mamie Josette", "Jean-Marc", "Didier", "Sabrina", "Moussa", "Nadia", "Papy René"};
    /** Les discussions de voisins de siège (A, puis B lui répond). */
    private static final String[][] DISCUSSIONS = {
            {"Beauvais… c'est vraiment une ville, ou c'est une blague ?", "Le billet coûtait 12 euros. Pose pas de questions."},
            {"T'as pris de la crème solaire ?", "J'ai pris des chips. C'est presque pareil."},
            {"Il paraît qu'il y a une jungle autour de Beauvais.", "N'importe quoi. C'est dans l'Oise, frère."},
            {"Tu crois qu'il y a le wifi, ici ?", "À 10 000 mètres ? Rêve pas."},
            {"J'ai rêvé qu'on s'écrasait dans la jungle, cette nuit.", "Arrête, porte pas la poisse !"},
            {"Le pilote avait l'air de dormir à moitié, non ?", "T'inquiète, il connaît la route par cœur."},
            {"En arrivant, premier truc : un bon kebab.", "Grave. Et après, sieste."},
            {"Pourquoi on a pris le vol le moins cher, déjà ?", "Parce que c'est toi qui as réservé."},
            {"Si ça tourne mal, je prends ton parachute.", "Y a pas de parachutes dans un avion, gros malin."},
            {"Je me sens bien, là. Rien ne peut mal tourner.", "Dis pas ça… Jamais."},
    };

    static boolean actif;
    private static ClientLevel niveau;
    private static long debutNanos;
    private static double t;
    private static double tPrecedent;
    private static double tDernierTick;
    private static Entity objectif;
    private static CameraType vueAvant;
    private static boolean guiAvant;
    private static Phantom phantom;

    // ---- ce que montre l'image (recalculé à chaque image)
    private static Vec3 camera = Vec3.ZERO;
    private static Vec3 vise = Vec3.ZERO;
    /** Vrai si la caméra est dans l'avion (pas de pluie dans la cabine). */
    private static boolean dedans;
    /** La caméra suit l'inclinaison de l'avion (dans la cabine, l'horizon penche). */
    private static boolean penche;
    private static float roulisCamera;
    private static float fov = 70;
    private static float secousse;
    private static float flash;
    private static int couleurFlash = 0xFFFFFF;
    private static float noir;
    private static float rouge;
    private static float sombre;
    private static String titre = "";
    private static String sousTitre = "";
    private static float pluie;
    private static float orage;
    private static float brume = 0;
    private static int lumiere = LightTexture.FULL_BRIGHT;
    private static int teinte = 0xFFFFFF;

    // ---- l'avion
    private static Vec3 avion = PORTE_12;
    private static float cap = -90;
    private static float tangage;
    private static float roulis;
    private static boolean train = true;
    private static boolean porteOuverte = true;
    private static boolean feu;
    private static boolean masques;
    private static final Quaternionf ROT = new Quaternionf();

    /** Un personnage : un passager (joueur ou inconnu), un membre d'équipage, un agent de piste. */
    private static final class Acteur {
        final String nom;
        final ResourceLocation skin;
        final boolean fin;
        final boolean joueur;
        final int couleur;
        final double phase;
        /** Le siège (repère de l'avion) ; x = NaN pour qui n'a pas de siège. */
        double sx = Double.NaN;
        double sz;
        /** Quand il commence à embarquer (les inconnus sont déjà assis). */
        double embarque = -1;
        // ---- calculé à chaque image
        Vec3 pos = Vec3.ZERO;
        float lacet = -90;
        Posture posture = Posture.DEBOUT;
        boolean visible = true;
        /** Dans le repère de l'avion (sinon dans celui du monde). */
        boolean bord = true;

        Acteur(String nom, ResourceLocation skin, boolean fin, boolean joueur, int couleur, double phase) {
            this.nom = nom;
            this.skin = skin;
            this.fin = fin;
            this.joueur = joueur;
            this.couleur = couleur;
            this.phase = phase;
        }
    }

    /** Une réplique sous-titrée ; qui parle (ou null : un haut-parleur, la radio). */
    private record Replique(double debut, double fin, String nom, int couleur, String texte, Acteur qui) {
    }

    private static final List<Acteur> PASSAGERS = new ArrayList<>();
    private static final List<Replique> REPLIQUES = new ArrayList<>();
    /** Les discussions : les deux voisins (indices dans PASSAGERS) de chaque scène. */
    private static final List<int[]> SCENES = new ArrayList<>();
    private static Acteur pilote;
    private static Acteur copilote;
    private static Acteur hotesse;
    private static Acteur agent;
    private static Acteur bagagiste;

    private static ResourceLocation peau(String nom) {
        return Cabane.id("textures/entity/avion/" + nom + ".png");
    }

    // ================================================================ le top départ

    public static void demarrer(Decollage.Debut p) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            return;
        }
        if (ClientCinema.actif) {
            ClientCinema.arreter();
        }
        niveau = mc.level;
        preparer(p.passagers(), p.graine());
        debutNanos = System.nanoTime() - (long) (p.depart() * 1e9);
        t = p.depart();
        tPrecedent = t;
        tDernierTick = t;
        actif = true;
        if (mc.screen != null && !(mc.screen instanceof ReceivingLevelScreen)) {
            mc.setScreen(null);
        }
        guiAvant = mc.options.hideGui;
        mc.options.hideGui = true;
        vueAvant = mc.options.getCameraType();
        mc.options.setCameraType(CameraType.FIRST_PERSON);
        objectif = EntityType.MARKER.create(mc.level);
        phantom = EntityType.PHANTOM.create(mc.level);
        calculer(t);
        placerCamera();
        mc.setCameraEntity(objectif);
        for (int canal = 0; canal < Boucle.VOLUMES.length; canal++) {
            Boucle.VOLUMES[canal] = 0;
        }
        mc.getMusicManager().stopPlaying();
        mc.getSoundManager().play(new Boucle(SoundEvents.ELYTRA_FLYING, 0, true));
        mc.getSoundManager().play(new Boucle(SoundEvents.MINECART_INSIDE, 1, true));
        mc.getSoundManager().play(new Boucle(SoundEvents.WEATHER_RAIN, 2, true));
        // la musique de l'aéroport : seulement dans le pack de sons privé « Beauvais - voix » (voix/cinematique/aeroport.ogg)
        ResourceLocation musique = Cabane.id("cine.aeroport");
        if (p.depart() < DECOLLAGE && mc.getSoundManager().getSoundEvent(musique) != null) {
            mc.getSoundManager().play(new Boucle(SoundEvent.createVariableRangeEvent(musique), 3, false));
        }
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
        phantom = null;
        niveau = null;
    }

    /** Les passagers à leur place (les joueurs devant, deux par deux), les inconnus sur une partie des sièges restants, l'équipage. */
    private static void preparer(List<Decollage.Passager> liste, long graine) {
        Random r = new Random(graine);
        Minecraft mc = Minecraft.getInstance();
        PASSAGERS.clear();
        int n = Math.min(liste.size(), Decollage.SIEGES);
        for (int i = 0; i < n; i++) {
            Decollage.Passager pa = liste.get(i);
            PlayerInfo info = mc.getConnection() == null ? null : mc.getConnection().getPlayerInfo(pa.id());
            PlayerSkin skin = info != null ? info.getSkin() : DefaultPlayerSkin.get(pa.id());
            Acteur a = new Acteur(pa.nom(), skin.texture(), skin.model() == PlayerSkin.Model.SLIM, true, OR, r.nextDouble() * 10);
            placer(a, i);
            PASSAGERS.add(a);
        }
        // l'embarquement : du fond de l'avion vers l'avant (personne ne se croise dans l'allée)
        double ecart = Math.min(0.75, 9.0 / Math.max(1, n));
        for (int i = 0; i < n; i++) {
            PASSAGERS.get(i).embarque = 8 + (n - 1 - i) * ecart;
        }
        // les inconnus : au moins 18 sièges occupés (l'avion n'est jamais vide), quelques places libres au hasard
        List<Integer> libres = new ArrayList<>();
        for (int k = n; k < Decollage.SIEGES; k++) {
            libres.add(k);
        }
        Collections.shuffle(libres, r);
        // un joueur seul sur sa rangée a toujours un voisin inconnu, pour discuter
        if (n % 2 == 1 && libres.remove(Integer.valueOf(n))) {
            libres.add(0, n);
        }
        int inconnus = Math.max(0, Math.min(libres.size(), 18 - n));
        List<Integer> peaux = new ArrayList<>(List.of(0, 1, 2, 3, 4, 5, 6, 7));
        Collections.shuffle(peaux, r);
        for (int i = 0; i < inconnus; i++) {
            int v = peaux.get(i % peaux.size());
            Acteur a = new Acteur(NOMS_INCONNUS[v], peau("passager_" + (v + 1)), false, false, INCONNU, r.nextDouble() * 10);
            placer(a, libres.get(i));
            PASSAGERS.add(a);
        }
        pilote = new Acteur("Le commandant", peau("pilote"), false, false, EQUIPAGE, 1);
        pilote.sx = Avion3D.X_PILOTES;
        pilote.sz = -1;
        copilote = new Acteur("Le copilote", peau("copilote"), false, false, EQUIPAGE, 2);
        copilote.sx = Avion3D.X_PILOTES;
        copilote.sz = 1;
        hotesse = new Acteur("L'hôtesse", peau("hotesse"), false, false, HOTESSE_COULEUR, 3);
        agent = new Acteur("", peau("agent_piste"), false, false, INCONNU, 4);
        agent.bord = false;
        bagagiste = new Acteur("", peau("agent_piste"), false, false, INCONNU, 5);
        bagagiste.bord = false;
        repliques(r, n);
    }

    private static void placer(Acteur a, int siege) {
        a.sx = Avion3D.RANGEES[siege / 4];
        a.sz = Avion3D.PLACES[siege % 4];
    }

    /** Le voisin de siège d'un passager (même rangée, même côté de l'allée), ou null. */
    private static Acteur voisin(Acteur a) {
        for (Acteur b : PASSAGERS) {
            if (b != a && b.sx == a.sx && Math.signum(b.sz) == Math.signum(a.sz)) {
                return b;
            }
        }
        return null;
    }

    private static Acteur parleur(int k) {
        return PASSAGERS.isEmpty() ? hotesse : PASSAGERS.get(Math.floorMod(k, PASSAGERS.size()));
    }

    private static void dit(double debut, double fin, Acteur qui, String texte) {
        REPLIQUES.add(new Replique(debut, fin, qui.nom, qui.couleur, texte, qui));
    }

    private static void annonce(double debut, double fin, String nom, int couleur, String texte) {
        REPLIQUES.add(new Replique(debut, fin, nom, couleur, texte, null));
    }

    /** Tout ce qui se dit, réparti entre les joueurs (les mêmes répliques sur tous les écrans : même graine). */
    private static void repliques(Random r, int joueurs) {
        REPLIQUES.clear();
        SCENES.clear();
        dit(11.2, 13.8, hotesse, "Bonjour ! Bienvenue à bord du vol BV2026.");
        dit(15.2, 18.6, hotesse, "Installez-vous, on décolle dans quelques minutes !");
        dit(20.6, 22.6, pilote, "Tour, ici BV2026. Prêts à rouler.");
        annonce(22.8, 24.9, "La tour", TOUR, "BV2026, autorisé à décoller, piste 27. Bon vol !");
        annonce(26.0, 28.8, "L'hôtesse (haut-parleur)", HOTESSE_COULEUR, "Personnel navigant, armement des toboggans.");
        annonce(47.2, 51.9, "Le commandant (haut-parleur)", EQUIPAGE,
                "Mesdames et messieurs, nous survolons les nuages. Arrivée à Beauvais dans deux heures. Détendez-vous !");
        // les discussions : des voisins de siège, des joueurs de préférence (ils sont assis deux par deux, devant)
        List<int[]> paires = new ArrayList<>();
        for (int i = 0; i + 1 < Math.max(joueurs, 2) && i + 1 < PASSAGERS.size(); i += 2) {
            Acteur a = PASSAGERS.get(i);
            Acteur b = voisin(a);
            if (b != null) {
                paires.add(new int[] {i, PASSAGERS.indexOf(b)});
            }
        }
        if (paires.isEmpty() && PASSAGERS.size() >= 2) {
            paires.add(new int[] {0, 1});
        }
        List<Integer> sujets = new ArrayList<>();
        for (int i = 0; i < DISCUSSIONS.length; i++) {
            sujets.add(i);
        }
        Collections.shuffle(sujets, r);
        for (int s = 0; s < 3 && !paires.isEmpty(); s++) {
            int[] paire = paires.get(s % paires.size());
            boolean inverse = s >= paires.size();
            Acteur a = PASSAGERS.get(paire[inverse ? 1 : 0]);
            Acteur b = PASSAGERS.get(paire[inverse ? 0 : 1]);
            SCENES.add(new int[] {PASSAGERS.indexOf(a), PASSAGERS.indexOf(b)});
            double d = CONVERSATIONS + s * DUREE_CONVERSATION;
            String[] dialogue = DISCUSSIONS[sujets.get(s)];
            dit(d + 0.3, d + 2.35, a, dialogue[0]);
            dit(d + 2.45, d + 4.5, b, dialogue[1]);
        }
        // le chariot de l'hôtesse : ceux du premier rang commandent
        Acteur x = PASSAGERS.isEmpty() ? hotesse : PASSAGERS.get(0);
        Acteur y = PASSAGERS.size() > 2 ? PASSAGERS.get(2) : PASSAGERS.size() > 1 ? PASSAGERS.get(1) : x;
        dit(HOTESSE + 0.5, HOTESSE + 2.9, hotesse, "Boisson, en-cas ? Café, thé, jus d'orange ?");
        dit(HOTESSE + 3.1, HOTESSE + 4.6, x, "Un coca, s'il vous plaît !");
        dit(HOTESSE + 4.8, HOTESSE + 6.3, y, "Vous auriez des chips ?");
        dit(HOTESSE + 6.4, HOTESSE + 8.0, hotesse, "Ah… Il n'y en a plus, désolée.");
        annonce(ORAGE + 0.8, ORAGE + 4.6, "Le commandant (haut-parleur)", EQUIPAGE,
                "Zone de turbulences devant nous. Regagnez vos sièges et attachez vos ceintures.");
        int k = r.nextInt(Math.max(1, joueurs));
        Acteur inquiet = parleur(k);
        Acteur panique = parleur(k + 1 + (joueurs > 2 ? r.nextInt(joueurs - 1) : 0));
        dit(SECOUSSES + 0.8, SECOUSSES + 2.6, inquiet, "C'est normal que ça bouge autant ?!");
        dit(SECOUSSES + 2.8, SECOUSSES + 4.4, panique, "Je veux descendre !!");
        dit(SECOUSSES + 4.6, SECOUSSES + 6.4, hotesse, "Restez assis, tout va bien !");
        dit(PAF + 0.4, PAF + 1.9, copilote, "Attention ! Des phantoms, droit devant !!");
        dit(IMPACT + 0.3, ALARME - 0.1, pilote, "On a perdu le réacteur droit !");
        dit(ALARME + 0.3, ALARME + 2.3, pilote, "MAYDAY, MAYDAY, MAYDAY ! Ici BV2026, on tombe !");
        dit(ALARME + 2.4, PANIQUE, copilote, "On ne tiendra jamais jusqu'à Beauvais !");
        dit(PANIQUE + 0.3, CHUTE - 0.1, hotesse, "PRENEZ LES PARACHUTES, AU FOND DE L'AVION !!");
    }

    // ================================================================ le vol

    private static double ecoule() {
        return (System.nanoTime() - debutNanos) / 1e9;
    }

    private static double entre(double a, double b) {
        return Mth.clamp((t - a) / (b - a), 0, 1);
    }

    private static double lisse(double k) {
        return k * k * (3 - 2 * k);
    }

    private static Vec3 vers(Vec3 a, Vec3 b, double k) {
        return a.add(b.subtract(a).scale(k));
    }

    private static float lacetVers(Vec3 de, Vec3 a) {
        return (float) Math.toDegrees(Math.atan2(-(a.x - de.x), a.z - de.z));
    }

    /** La direction d'un cap (lacet de Minecraft : -90 = est, 90 = ouest). */
    private static Vec3 direction(double capDegres) {
        double c = Math.toRadians(capDegres);
        return new Vec3(-Math.sin(c), 0, Math.cos(c));
    }

    /** Un point du repère de l'avion, dans le monde. */
    private static Vec3 monde(double x, double y, double z) {
        Vector3f v = ROT.transform(new Vector3f((float) x, (float) y, (float) z));
        return avion.add(v.x, v.y, v.z);
    }

    /** Un point d'un repère qui suit l'avion sans pencher (seulement son cap) : pour filmer l'avion de dehors. */
    private static Vec3 autour(double x, double y, double z) {
        Vec3 d = direction(cap);
        Vec3 droite = new Vec3(-d.z, 0, d.x);
        return avion.add(d.scale(x)).add(0, y, 0).add(droite.scale(z));
    }

    /** Où est l'avion, à l'instant t : à la porte, qui roule, qui décolle, qui tourne au-dessus des nuages, qui tombe. */
    private static void vol() {
        tangage = 0;
        roulis = 0;
        train = true;
        feu = t >= IMPACT;
        masques = t >= IMPACT + 1;
        porteOuverte = t < 24.4;
        if (t < DEPART) {
            avion = PORTE_12;
            cap = -90;
        } else if (t < DECOLLAGE) {
            // il quitte la porte 12, tout doucement
            double s = t - DEPART;
            avion = PORTE_12.add(0.4 * s * s, 0, 0);
            cap = -90;
        } else if (t < NUAGES) {
            decollage(t - DECOLLAGE);
        } else {
            croisiere();
        }
        ROT.identity().rotateY((float) Math.toRadians(-(cap + 90))).rotateZ((float) Math.toRadians(tangage)).rotateX((float) Math.toRadians(roulis));
    }

    /** La course sur la piste (vers l'ouest), la rotation, la montée, puis un virage à droite au-dessus de l'aérogare. */
    private static void decollage(double s) {
        double z = fr.cabane.hub.AeroportBuilder.PISTE_Z + 0.5;
        double a = 7;
        double sDecolle = 5.6;
        double xDecolle = 120.5 - 0.5 * a * sDecolle * sDecolle;
        double v = a * sDecolle;
        cap = 90;
        tangage = (float) (10 * lisse(Mth.clamp((s - 5.0) / 0.8, 0, 1)));
        if (s < sDecolle) {
            avion = new Vec3(120.5 - 0.5 * a * s * s, SOL + 3, z);
            return;
        }
        double monte = s - sDecolle;
        double y = SOL + 3 + (monte < 1 ? 3.4 * monte * monte : 3.4 + 6.8 * (monte - 1));
        train = s < 8;
        double sVirage = 7;
        if (s < sVirage) {
            avion = new Vec3(xDecolle - v * monte, y, z);
            return;
        }
        // le virage à droite (le cap augmente) : le terrain reste chargé autour de l'aéroport
        double xVirage = xDecolle - v * (sVirage - sDecolle);
        double w = Math.toRadians(34);
        double u = s - sVirage;
        double c0 = Math.toRadians(90);
        double c = c0 + w * u;
        double vit = 30 + (v - 30) * Math.max(0, 1 - u);
        avion = new Vec3(xVirage + vit / w * (Math.cos(c) - Math.cos(c0)), y, z + vit / w * (Math.sin(c) - Math.sin(c0)));
        cap = (float) Math.toDegrees(c);
        roulis = (float) (25 * lisse(Mth.clamp(u / 0.6, 0, 1)));
    }

    /** Le grand cercle au-dessus des nuages ; les secousses de l'orage ; après l'impact, la chute en spirale. */
    private static void croisiere() {
        double w = VITESSE_CROISIERE / RAYON_CERCLE;
        double c = Math.toRadians(-20) + w * (t - NUAGES);
        cap = (float) Math.toDegrees(c);
        double y = CERCLE.y;
        roulis = 8;
        train = false;
        // l'orage : l'avion tangue, de plus en plus
        double agite = entre(ORAGE + 2, SECOUSSES) * 0.35 + entre(SECOUSSES, SECOUSSES + 0.6) * 0.65;
        if (t >= IMPACT) {
            double k = t - IMPACT;
            y -= 6 * k + 0.4 * k * k;
            roulis = (float) (8 + 30 * lisse(Math.min(1, k / 3)) + 6 * Math.sin(k * 1.7));
            tangage = (float) (-14 * lisse(Math.min(1, k / 2.5)));
            agite = Math.max(agite, 0.8);
        }
        roulis += (float) (agite * (6 * Math.sin(t * 7.3) + 4 * Math.sin(t * 11.1)));
        tangage += (float) (agite * 3 * Math.sin(t * 5.1));
        y += agite * 0.45 * Math.sin(t * 9.7);
        avion = new Vec3(CERCLE.x + RAYON_CERCLE * Math.cos(c), y, CERCLE.z + RAYON_CERCLE * Math.sin(c));
    }

    // ================================================================ les personnages

    /** Assis : les cuisses à l'horizontale, les mains sur les genoux. */
    private static final Posture ASSIS = new Posture(0, 0, 0, -0.7F, 0.05F, 0.1F, -0.7F, -0.05F, -0.1F, -1.45F, 0.06F, -1.45F, -0.06F);
    /** Aux commandes : les mains sur le manche. */
    private static final Posture PILOTE = new Posture(0.05F, 0, 0, -0.9F, 0.2F, 0, -0.9F, -0.2F, 0, -1.45F, 0.06F, -1.45F, -0.06F);
    /** Assis, les bras en l'air : la panique. */
    private static final Posture AFFOLE = new Posture(-0.35F, 0, 0, -2.7F, 0, 0.5F, -2.7F, 0, -0.5F, -1.45F, 0.15F, -1.45F, -0.15F);
    /** L'hôtesse pousse son chariot. */
    private static final Posture POUSSE = new Posture(0.1F, 0, 4, -1.2F, 0.15F, 0, -1.2F, -0.15F, 0, 0, 0.03F, 0, -0.03F);

    /** Une posture qui marche : les jambes et les bras se balancent selon la distance parcourue. */
    private static Posture marche(double d) {
        float b = (float) Math.sin(d * 2.4) * 0.7F;
        return new Posture(0.05F, 0, 0, -b * 0.8F, 0, 0.06F, b * 0.8F, 0, -0.06F, b, 0.03F, -b, -0.03F);
    }

    /** Assis, avec ce qu'il fait : il parle (hoche la tête, gesticule), il écoute (tourné vers l'autre), il panique. */
    private static Posture assis(Acteur a, boolean parle, float regard) {
        float x = 0;
        float bras = -0.7F;
        if (parle) {
            x = (float) (0.09 * Math.sin(t * 9 + a.phase));
            bras = (float) (-1.0 - 0.35 * Math.sin(t * 5 + a.phase));
        }
        // de petits mouvements de tête, chacun à son rythme
        float tete = regard + (float) (0.12 * Math.sin(t * 0.5 + a.phase));
        return new Posture(x, tete, 0, bras, 0.05F, 0.1F, -0.7F, -0.05F, -0.1F, -1.45F, 0.06F, -1.45F, -0.06F);
    }

    private static boolean parle(Acteur a) {
        for (Replique r : REPLIQUES) {
            if (r.qui() == a && t >= r.debut() && t < r.fin()) {
                return true;
            }
        }
        return false;
    }

    /** Le siège, vu du repère de l'avion : là où sont les pieds d'un passager assis. */
    private static Vec3 assise(Acteur a) {
        return new Vec3(a.sx + 0.12, 0.796, a.sz);
    }

    /** Le chemin d'un passager qui embarque : la passerelle, la porte, l'allée, sa rangée, puis il s'assoit. */
    private static void embarquer(Acteur a) {
        double s = t - a.embarque;
        Vec3[] chemin = {new Vec3(8.3, 1, -15), new Vec3(8.3, 1, -2.2), new Vec3(8.5, 1, 0), new Vec3(a.sx + 0.75, 1, 0),
                new Vec3(a.sx + 0.75, 1, a.sz)};
        double vitesse = 3.3;
        double d = s * vitesse;
        double total = 0;
        for (int i = 0; i + 1 < chemin.length; i++) {
            double l = chemin[i].distanceTo(chemin[i + 1]);
            if (d < total + l) {
                double k = (d - total) / l;
                a.pos = vers(chemin[i], chemin[i + 1], k);
                a.lacet = lacetVers(chemin[i], chemin[i + 1]);
                a.posture = marche(d);
                return;
            }
            total += l;
        }
        // arrivé : il se tourne vers l'avant et s'assoit
        double k = lisse(Mth.clamp((d - total) / vitesse / 0.5, 0, 1));
        a.pos = vers(chemin[chemin.length - 1], assise(a), k);
        a.lacet = (float) Mth.lerp(k, a.sz > 0 ? 0 : 180, -90);
        a.posture = Posture.DEBOUT.vers(ASSIS, (float) k);
    }

    private static void acteurs() {
        int scene = (int) ((t - CONVERSATIONS) / DUREE_CONVERSATION);
        int[] paire = t >= CONVERSATIONS && t < HOTESSE && scene < SCENES.size() ? SCENES.get(scene) : null;
        double agite = t >= SECOUSSES ? (t >= IMPACT ? 1 : 0.6) : 0;
        boolean affoles = t >= IMPACT + 0.5;
        for (int i = 0; i < PASSAGERS.size(); i++) {
            Acteur a = PASSAGERS.get(i);
            a.visible = true;
            if (a.joueur && t < a.embarque) {
                a.visible = false;
                continue;
            }
            if (a.joueur && t < CABINE && t < a.embarque + 20) {
                embarquer(a);
                continue;
            }
            a.pos = assise(a);
            a.lacet = -90;
            boolean parle = parle(a);
            float regard = 0;
            if (paire != null && (i == paire[0] || i == paire[1])) {
                // les deux voisins se tournent l'un vers l'autre
                Acteur autre = PASSAGERS.get(i == paire[0] ? paire[1] : paire[0]);
                regard = (float) Math.signum(autre.sz - a.sz) * 0.65F;
            } else if (parle) {
                regard = (float) -Math.signum(a.sz) * 0.4F;
            }
            a.posture = affoles ? AFFOLE : assis(a, parle, regard);
            if (affoles) {
                float tremble = (float) Math.sin(t * 13 + a.phase);
                a.posture = new Posture(-0.3F + 0.1F * tremble, 0.3F * (float) Math.sin(t * 7 + a.phase), 0, -2.6F + 0.3F * tremble, 0, 0.5F,
                        -2.6F - 0.3F * tremble, 0, -0.5F, -1.45F, 0.15F, -1.45F, -0.15F);
            }
            if (agite > 0) {
                a.pos = a.pos.add(0, agite * 0.05 * Math.max(0, Math.sin(t * 23 + a.phase * 3)), 0);
            }
        }
        // les pilotes, aux commandes
        for (Acteur p : new Acteur[] {pilote, copilote}) {
            p.pos = assise(p);
            p.lacet = -90;
            boolean parle = parle(p);
            float lutte = t >= IMPACT ? (float) Math.sin(t * 8 + p.phase) * 0.25F : 0;
            Posture base = PILOTE;
            if (parle) {
                base = new Posture((float) (0.08 * Math.sin(t * 9)), p.sz < 0 ? 0.25F : -0.25F, 0, -0.9F, 0.2F, 0, -0.9F, -0.2F, 0, -1.45F, 0.06F, -1.45F, -0.06F);
            }
            p.posture = new Posture(base.teteX(), base.teteY(), base.penche(), base.brasDX() + lutte, base.brasDY(), base.brasDZ(),
                    base.brasGX() - lutte, base.brasGY(), base.brasGZ(), base.jambeDX(), base.jambeDZ(), base.jambeGX(), base.jambeGZ());
        }
        hotesse();
        // au sol : l'agent qui guide l'avion avec ses bras, le bagagiste près de ses chariots
        agent.visible = t < DECOLLAGE;
        double ecarte = lisse(Mth.clamp((t - DEPART) / 1.5, 0, 1));
        agent.pos = vers(new Vec3(25.5, SOL, 0.5), new Vec3(26, SOL, 8), ecarte);
        agent.lacet = lacetVers(agent.pos, avion);
        float bras = (float) Math.sin(t * 4.5);
        agent.posture = new Posture(0, 0, 0, -2.75F, 0, 0.35F + 0.35F * bras, -2.75F, 0, -0.35F - 0.35F * bras, 0, 0.03F, 0, -0.03F);
        bagagiste.visible = t < DECOLLAGE;
        bagagiste.pos = new Vec3(-3.5, SOL, 11.5);
        bagagiste.lacet = 150;
        bagagiste.posture = new Posture(0.3F, 0, 10, -1.3F + 0.2F * (float) Math.sin(t * 2), 0, 0.1F, -1.3F, 0, -0.1F, 0, 0.03F, 0, -0.03F);
    }

    /** L'hôtesse : accueille à la porte, pousse son chariot dans l'allée, s'accroche pendant les secousses. */
    private static void hotesse() {
        Acteur h = hotesse;
        h.visible = true;
        boolean parle = parle(h);
        Posture accueil = new Posture(0, 0, 0, parle ? -1.6F + 0.3F * (float) Math.sin(t * 6) : -0.2F, 0, 0.1F, -0.2F, 0, -0.1F, 0, 0.03F, 0, -0.03F);
        if (t < HOTESSE) {
            h.pos = new Vec3(7.3, 1, 1.0);
            h.lacet = t < CABINE ? 160 : 90;
            h.posture = accueil;
        } else if (t < ORAGE) {
            double c = chariot();
            h.pos = new Vec3(c + 1.15, 1, 0);
            h.lacet = 90;
            h.posture = parle ? accueil : POUSSE;
        } else if (t < PANIQUE) {
            // elle se tient aux coffres à bagages
            h.pos = new Vec3(-2.2, 1, 0);
            h.lacet = 90;
            float s = (float) Math.sin(t * 9);
            h.posture = new Posture(0.1F * s, 0, 0, -2.9F, 0, 0.6F, -2.9F, 0, -0.6F, 0.1F * s, 0.1F, -0.1F * s, -0.1F);
        } else {
            h.pos = new Vec3(3.5, 1, 0);
            h.lacet = 90;
            float s = (float) Math.sin(t * 7);
            h.posture = new Posture(-0.3F, 0.3F * s, 0, -2.6F + 0.4F * s, 0, 0.3F, -1.6F - 0.4F * s, 0, -0.3F, 0.1F, 0.1F, -0.1F, -0.1F);
        }
        if (t >= SECOUSSES && t < PANIQUE) {
            h.pos = h.pos.add(0, 0.04 * Math.abs(Math.sin(t * 17)), 0);
        }
    }

    /** Où est le chariot dans l'allée (x, repère de l'avion). */
    private static double chariot() {
        double s = t - HOTESSE;
        return 4.5 - 0.9 * Math.min(s, 7.0);
    }

    // ================================================================ les plans

    /** Tout ce qui est à l'écran à l'instant t. */
    private static void calculer(double temps) {
        t = temps;
        titre = "";
        sousTitre = "";
        flash = 0;
        secousse = 0;
        fov = 62;
        noir = 0;
        rouge = 0;
        sombre = 0;
        dedans = false;
        penche = false;
        vol();
        meteo();
        acteurs();
        plans();
        if (penche) {
            roulisCamera = roulisVu();
        } else {
            roulisCamera = 0;
        }
    }

    /** La météo et la lumière : grand beau à l'aéroport, puis l'orage. */
    private static void meteo() {
        double o = entre(ORAGE, ORAGE + 3);
        pluie = (float) o;
        orage = (float) o;
        brume = o > 0 ? (float) Mth.lerp(o, 260, 70) : 0;
        int jour = 15 - (int) Math.round(6 * o);
        lumiere = LightTexture.pack(jour, jour);
        int g = (int) Mth.lerp(o, 255, 170);
        teinte = g << 16 | g << 8 | Math.min(255, g + 12);
        // les plafonniers qui vacillent pendant les secousses, la lumière rouge de l'alarme
        if (t >= SECOUSSES && t < NOIR && Math.sin(t * 31) * Math.sin(t * 7.7) > 0.55) {
            sombre = 0.35F;
        }
        if (t >= IMPACT + 0.5) {
            rouge = (float) (0.16 + 0.12 * Math.sin(t * 9));
        }
    }

    private static void plans() {
        if (t < AEROPORT) {
            // ---- l'écran noir : le titre
            noir = (float) (1 - entre(3.3, 4.0));
            if (t > 0.5 && t < 3.6) {
                titre = "VOL BV2026";
                sousTitre = "Porte 12 — destination : Beauvais";
            }
            camera = new Vec3(46, SOL + 10, 30);
            vise = new Vec3(-2, SOL + 4, -8);
            fov = 60;
        } else if (t < PASSERELLE) {
            // ---- l'aéroport : l'avion à la porte 12, la passerelle, l'aérogare, la tour, la piste au premier plan
            double k = lisse(entre(AEROPORT, PASSERELLE));
            camera = vers(new Vec3(46, SOL + 10, 30), new Vec3(36, SOL + 7.5, 24), k);
            vise = vers(new Vec3(-2, SOL + 4, -8), new Vec3(2, SOL + 4, -4), k);
            fov = 60;
        } else if (t < EMBARQUEMENT) {
            // ---- dans la passerelle : les passagers arrivent, un par un, vers la porte de l'avion
            camera = monde(9.15, 2.45, -3.7);
            vise = monde(8.1, 1.6, -15);
            fov = 66;
        } else if (t < COCKPIT) {
            // ---- dans la cabine, du fond : on s'installe, l'hôtesse accueille à la porte
            dedans = true;
            camera = monde(-14.6, 2.9, 0.3);
            vise = monde(8, 1.5, -0.4);
            fov = 64;
        } else if (t < DEPART) {
            // ---- le poste de pilotage : les deux pilotes de trois quarts (le commandant appelle la tour), puis, derrière
            // eux, le pare-brise : dehors, l'agent de piste guide l'avion
            dedans = true;
            if (t < 22.8) {
                camera = monde(14.75, 3.05, -2.05);
                vise = monde(12.9, 2.35, 0.3);
            } else {
                camera = monde(12.0, 3.25, 0);
                vise = monde(17, 3.1, 0);
            }
            fov = 72;
        } else if (t < DECOLLAGE) {
            // ---- l'avion quitte la porte 12
            camera = new Vec3(30, SOL + 1.2, 14);
            vise = monde(4, 2, 0);
            fov = 58;
        } else if (t < MONTEE) {
            // ---- le décollage, vu du bord de la piste : il arrive, passe en trombe, s'arrache du sol
            camera = new Vec3(4, SOL + 1.6, 60);
            vise = monde(6, 1, 0);
            fov = (float) Mth.lerp(entre(DECOLLAGE, MONTEE), 36, 62);
            secousse = (float) (0.35 * Math.max(0, 1 - Math.abs(t - DECOLLAGE - 5.6) / 1.2));
        } else if (t < NUAGES) {
            // ---- la montée, vue de derrière : l'aéroport rapetisse, l'avion vire au-dessus de l'aérogare
            camera = autour(-46, 8, -9);
            vise = autour(12, 0, 0);
            fov = 60;
        } else if (t < CABINE) {
            // ---- au-dessus des nuages
            double k = lisse(entre(NUAGES, CABINE));
            camera = autour(Mth.lerp(k, 26, 12), 4.5, Mth.lerp(k, 30, 26));
            vise = autour(-3, 1, 0);
            fov = 60;
        } else if (t < CONVERSATIONS) {
            // ---- la cabine, de l'avant : tout le monde est assis et discute
            dedans = true;
            penche = true;
            camera = monde(10.0, 2.95, 0);
            vise = monde(-14, 1.7, 0);
            fov = 66;
            double k = entre(CABINE, CONVERSATIONS);
            camera = monde(Mth.lerp(k, 10.0, 8.6), 2.95, 0);
        } else if (t < HOTESSE) {
            // ---- les discussions, de face, deux voisins de siège
            dedans = true;
            penche = true;
            int scene = Math.min(SCENES.size() - 1, (int) ((t - CONVERSATIONS) / DUREE_CONVERSATION));
            if (scene < 0) {
                camera = monde(10.0, 2.95, 0);
                vise = monde(-14, 1.7, 0);
            } else {
                // de face, au-dessus du dossier de la rangée de devant, entre les deux têtes de cette rangée
                Acteur a = PASSAGERS.get(SCENES.get(scene)[0]);
                Acteur b = PASSAGERS.get(SCENES.get(scene)[1]);
                double z = (a.sz + b.sz) / 2;
                double glisse = (t - CONVERSATIONS - scene * DUREE_CONVERSATION) / DUREE_CONVERSATION;
                camera = monde(a.sx + 2.2, 2.55, z + 0.12 - 0.24 * glisse);
                vise = monde(a.sx + 0.15, 2.2, z);
                fov = 62;
            }
        } else if (t < ORAGE) {
            // ---- l'hôtesse et son chariot, dans l'allée
            dedans = true;
            penche = true;
            double c = chariot();
            camera = monde(c - 3.3, 2.05, 0.05);
            vise = monde(c + 1.3, 2.1, 0);
            fov = 64;
        } else if (t < SECOUSSES) {
            // ---- dehors : le ciel noircit, l'orage, les éclairs
            camera = autour(30, 3, -16);
            vise = autour(-4, 1, 0);
            fov = 58;
        } else if (t < PAF) {
            // ---- les secousses, dans la cabine
            dedans = true;
            penche = true;
            camera = monde(4.2, 2.6, -0.5);
            vise = monde(-12, 1.6, 0.4);
            fov = 70;
            secousse = 0.9F;
        } else if (t < ALARME) {
            // ---- PAF : la nuée de phantoms surgit de l'orage et se jette dans le réacteur droit
            camera = monde(-11, 4.2, 23);
            vise = monde(14, -1, 6);
            fov = 66;
            secousse = t >= IMPACT ? (float) (1.4 * Math.max(0.3, 1 - (t - IMPACT) / 1.5)) : 0.3F;
        } else if (t < PANIQUE) {
            // ---- l'alarme dans le poste de pilotage
            dedans = true;
            penche = true;
            camera = monde(14.5, 3.3, 0);
            vise = monde(12.8, 2.35, 0);
            fov = 74;
            secousse = 1F;
        } else if (t < CHUTE) {
            // ---- la panique dans la cabine : les masques à oxygène, l'hôtesse qui crie
            dedans = true;
            penche = true;
            camera = monde(5.2, 2.85, 1.3);
            vise = monde(-10, 1.6, -0.6);
            fov = 72;
            secousse = 1.1F;
        } else {
            // ---- dehors : l'avion en feu plonge dans l'orage, vu de son flanc droit ; il s'éloigne, puis le noir
            double k = entre(CHUTE, NOIR);
            camera = autour(Mth.lerp(k, 8, 2), Mth.lerp(k, 9, 13), Mth.lerp(k, 28, 42));
            vise = monde(-2, 0, 5);
            fov = 62;
            secousse = 0.5F;
            flash = (float) Math.max(0, (t - (NOIR - 0.9)) / 0.4);
            couleurFlash = 0xFFF0D8;
            noir = (float) Math.max(0, (t - (NOIR - 0.45)) / 0.45);
        }
        // l'impact : un éclair orange à l'écran
        if (t >= IMPACT && t < IMPACT + 0.5) {
            flash = Math.max(flash, (float) (0.85 * (1 - (t - IMPACT) / 0.5)));
            couleurFlash = 0xFFB060;
        }
        for (double m : ECLAIRS) {
            if (t >= m && t < m + 0.18) {
                flash = Math.max(flash, dedans ? 0.25F : 0.4F);
                couleurFlash = 0xE8F0FF;
            }
        }
    }

    /** Les éclairs de l'orage (temps) : un flash, le tonnerre. */
    private static final double[] ECLAIRS = {ORAGE + 2.2, ORAGE + 3.9, SECOUSSES + 2.1, SECOUSSES + 5.0, PAF + 0.2, ALARME + 1.5, CHUTE + 1.2};

    /** Dans la cabine, l'horizon penche avec l'avion : l'angle du haut de l'avion, vu par la caméra. */
    private static float roulisVu() {
        Vec3 f = vise.subtract(camera).normalize();
        Vec3 droite = f.cross(new Vec3(0, 1, 0));
        if (droite.lengthSqr() < 1e-6) {
            return 0;
        }
        droite = droite.normalize();
        Vec3 haut = droite.cross(f);
        Vector3f h = ROT.transform(new Vector3f(0, 1, 0));
        Vec3 u = new Vec3(h.x, h.y, h.z);
        return (float) Math.toDegrees(Math.atan2(u.dot(droite), u.dot(haut)));
    }

    private static void placerCamera() {
        if (objectif == null) {
            return;
        }
        Vec3 c = camera;
        if (secousse > 0) {
            double s = secousse * 0.06;
            c = c.add((Math.random() - 0.5) * s, (Math.random() - 0.5) * s, (Math.random() - 0.5) * s);
        }
        Vec3 d = vise.subtract(c);
        float lacet = (float) Math.toDegrees(Math.atan2(-d.x, d.z));
        float tangageVue = (float) Math.toDegrees(-Math.atan2(d.y, Math.sqrt(d.x * d.x + d.z * d.z)));
        objectif.setPos(c.x, c.y, c.z);
        objectif.xo = c.x;
        objectif.yo = c.y;
        objectif.zo = c.z;
        objectif.xOld = c.x;
        objectif.yOld = c.y;
        objectif.zOld = c.z;
        objectif.setYRot(lacet);
        objectif.setXRot(tangageVue);
        objectif.yRotO = lacet;
        objectif.xRotO = tangageVue;
    }

    // ================================================================ les sons et les particules

    private static boolean passe(double moment) {
        return tPrecedent < moment && t >= moment;
    }

    private static void son(SoundEvent son, float volume, float hauteur) {
        Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(son, hauteur, volume));
    }

    /** Ding-dong : le signal des annonces. */
    private static void dingDong(double m) {
        if (passe(m)) {
            son(SoundEvents.NOTE_BLOCK_CHIME.value(), 0.6F, 1.5F);
        }
        if (passe(m + 0.45)) {
            son(SoundEvents.NOTE_BLOCK_CHIME.value(), 0.6F, 1.19F);
        }
    }

    private static void particules(ParticleOptions type, Vec3 p, int n, double etendue, double vitesse) {
        var r = niveau.random;
        for (int i = 0; i < n; i++) {
            niveau.addParticle(type, true, p.x + (r.nextDouble() - 0.5) * etendue, p.y + (r.nextDouble() - 0.5) * etendue, p.z + (r.nextDouble() - 0.5) * etendue,
                    (r.nextDouble() - 0.5) * vitesse, (r.nextDouble() - 0.5) * vitesse, (r.nextDouble() - 0.5) * vitesse);
        }
    }

    private static Vec3 reacteur() {
        double[] r = Avion3D.REACTEUR_DROIT;
        return monde(r[0] - 2, r[1], r[2]);
    }

    private static void evenements() {
        dingDong(CABINE + 0.3);
        dingDong(ORAGE + 0.3);
        if (passe(24.4)) {
            son(SoundEvents.IRON_DOOR_CLOSE, 0.5F, 0.8F);
        }
        if (passe(DECOLLAGE + 5.4)) {
            son(SoundEvents.ELYTRA_FLYING, 0.9F, 1.4F);
        }
        if (passe(DECOLLAGE + 8)) {
            son(SoundEvents.PISTON_CONTRACT, 0.5F, 0.6F);
        }
        for (double m : ECLAIRS) {
            if (passe(m)) {
                niveau.setSkyFlashTime(3);
                son(SoundEvents.LIGHTNING_BOLT_THUNDER, dedans ? 0.55F : 0.85F, 0.8F + niveau.random.nextFloat() * 0.3F);
            }
        }
        // les secousses : des chocs sourds dans la carlingue
        if (t >= SECOUSSES && t < NOIR && Math.floor(t / 0.9) != Math.floor(tPrecedent / 0.9)) {
            son(SoundEvents.ZOMBIE_ATTACK_WOODEN_DOOR, 0.35F, 0.5F + niveau.random.nextFloat() * 0.2F);
        }
        // les phantoms : leurs cris, puis l'explosion du réacteur
        if (passe(PAF + 0.2) || passe(PAF + 0.9) || passe(PAF + 1.4)) {
            son(SoundEvents.PHANTOM_AMBIENT, 1F, 0.8F + niveau.random.nextFloat() * 0.4F);
        }
        if (passe(IMPACT - 0.4)) {
            son(SoundEvents.PHANTOM_SWOOP, 1F, 0.9F);
        }
        if (passe(IMPACT)) {
            son(SoundEvents.GENERIC_EXPLODE.value(), 1F, 0.6F);
            son(SoundEvents.DRAGON_FIREBALL_EXPLODE, 0.9F, 0.7F);
            son(SoundEvents.PHANTOM_DEATH, 0.8F, 0.8F);
            Vec3 r = reacteur();
            particules(ParticleTypes.EXPLOSION_EMITTER, r, 2, 2, 0);
            particules(ParticleTypes.FLAME, r, 40, 3, 0.5);
            particules(ParticleTypes.LARGE_SMOKE, r, 30, 3, 0.3);
        }
        if (passe(IMPACT + 3.5) || passe(CHUTE + 1.8)) {
            son(SoundEvents.GENERIC_EXPLODE.value(), 0.7F, 0.5F);
        }
        // l'alarme du poste de pilotage
        if (t >= IMPACT + 0.4 && t < NOIR && Math.floor(t / 0.32) != Math.floor(tPrecedent / 0.32)) {
            boolean haut = ((int) Math.floor(t / 0.32)) % 2 == 0;
            son(SoundEvents.NOTE_BLOCK_BIT.value(), dedans ? 0.5F : 0.25F, haut ? 1.6F : 1.2F);
        }
        if (passe(NOIR - 0.9)) {
            son(SoundEvents.GENERIC_EXPLODE.value(), 1F, 0.45F);
        }
    }

    /** Les volumes des sons en boucle : le sifflement des réacteurs, le grondement, la pluie. */
    private static void boucles() {
        float moteur;
        float aigu;
        float grondement;
        if (t < DEPART) {
            moteur = 0.12F;
            aigu = 0.6F;
            grondement = 0;
        } else if (t < DECOLLAGE) {
            double k = entre(DEPART, DECOLLAGE);
            moteur = (float) Mth.lerp(k, 0.15, 0.35);
            aigu = (float) Mth.lerp(k, 0.6, 0.8);
            grondement = 0.15F;
        } else if (t < MONTEE) {
            double k = entre(DECOLLAGE, DECOLLAGE + 5.6);
            moteur = (float) Mth.lerp(k, 0.4, 1.0);
            aigu = (float) Mth.lerp(k, 0.8, 1.25);
            grondement = (float) Mth.lerp(k, 0.5, 0.9) * (t < DECOLLAGE + 5.8 ? 1 : 0.4F);
        } else if (dedans) {
            moteur = 0.18F;
            aigu = 0.55F;
            grondement = 0.12F;
        } else {
            moteur = 0.6F;
            aigu = 0.9F;
            grondement = 0.2F;
        }
        if (t >= SECOUSSES) {
            grondement = Math.max(grondement, 0.6F);
        }
        if (t >= IMPACT) {
            aigu *= 0.85F + 0.15F * (float) Math.sin(t * 6);
            grondement = 0.75F;
        }
        if (t >= NOIR) {
            moteur = 0;
            grondement = 0;
        }
        Boucle.VOLUMES[0] = moteur;
        Boucle.HAUTEURS[0] = aigu;
        Boucle.VOLUMES[1] = grondement;
        Boucle.HAUTEURS[1] = 0.55F;
        Boucle.VOLUMES[2] = t >= NOIR ? 0 : pluie * (dedans ? 0.3F : 0.8F);
        Boucle.HAUTEURS[2] = 1F;
        // la musique de l'aéroport, en fond : plus bas dans l'avion, elle s'efface quand les réacteurs rugissent
        float musique = dedans ? 0.2F : 0.3F;
        if (t < 1.5) {
            musique = (float) (0.3 * entre(0.2, 1.5));
        }
        if (t >= DECOLLAGE) {
            musique = (float) (0.3 * (1 - entre(DECOLLAGE, DECOLLAGE + 2)));
        }
        Boucle.VOLUMES[3] = musique;
        Boucle.HAUTEURS[3] = 1F;
    }

    /** Pendant les plans de l'avion en feu : la fumée et les flammes s'échappent de l'aile droite et font une traînée. */
    private static void ambiance() {
        if (!feu || t >= NOIR) {
            return;
        }
        var r = niveau.random;
        for (int i = 0; i < 4; i++) {
            Vec3 p = monde(r.nextDouble() * 4 - 2, 1 + r.nextDouble(), 6 + r.nextDouble() * 6);
            if (p.distanceTo(camera) > 4) {
                niveau.addParticle(ParticleTypes.LARGE_SMOKE, true, p.x, p.y, p.z, 0, 0.05, 0);
                niveau.addParticle(r.nextBoolean() ? ParticleTypes.FLAME : ParticleTypes.LAVA, true, p.x, p.y, p.z, 0, 0.02, 0);
            }
        }
        Vec3 p = reacteur();
        if (p.distanceTo(camera) > 4) {
            niveau.addParticle(ParticleTypes.CAMPFIRE_SIGNAL_SMOKE, true, p.x, p.y, p.z, 0, 0.02, 0);
        }
    }

    // ================================================================ le dessin

    private static void dessinerMonde(PoseStack pose, Vec3 cam, MultiBufferSource.BufferSource buffers) {
        Minecraft mc = Minecraft.getInstance();
        // l'avion et tout ce qu'il contient, dans son repère
        pose.pushPose();
        pose.translate(avion.x - cam.x, avion.y - cam.y, avion.z - cam.z);
        pose.mulPose(ROT);
        int lumiereBord = sombre > 0 ? LightTexture.pack(6, 6) : lumiere;
        Avion3D.dessiner(pose, buffers, lumiereBord, train, porteOuverte, feu);
        buffers.endBatch();
        int teinteBord = t >= IMPACT + 0.5 ? (teinte & 0xFFB4B4) | 0xFF0000 : teinte;
        for (Acteur a : PASSAGERS) {
            dessiner(pose, buffers, a, teinteBord);
        }
        for (Acteur a : new Acteur[] {pilote, copilote, hotesse}) {
            dessiner(pose, buffers, a, teinteBord);
        }
        if (t >= HOTESSE - 2 && t < ORAGE) {
            dessinerChariot(pose, buffers, lumiereBord);
        }
        if (masques) {
            dessinerMasques(pose, buffers, lumiereBord);
        }
        if (t >= PAF && t < ALARME) {
            dessinerPhantoms(pose, buffers);
        }
        pose.popPose();
        // au sol
        for (Acteur a : new Acteur[] {agent, bagagiste}) {
            if (a.visible) {
                Effets.personnage(pose, buffers, a.skin, a.pos.subtract(cam), a.lacet, a.posture, 1F, Effets.SANS, Effets.NOIR, 1F, teinte);
            }
        }
        // les éclairs de l'orage, au loin
        for (int i = 0; i < ECLAIRS.length; i++) {
            double m = ECLAIRS[i];
            if (t >= m && t < m + 0.25) {
                Random rr = new Random(i * 7919L);
                Vec3 d = direction(cap + 60 + rr.nextDouble() * 240);
                Vec3 haut = avion.add(d.scale(70 + rr.nextDouble() * 40)).add(0, 40, 0);
                Vec3 bas = haut.add(rr.nextDouble() * 20 - 10, -110, rr.nextDouble() * 20 - 10);
                Effets.eclair(pose, buffers, haut.subtract(cam), bas.subtract(cam), i * 31L + (long) (t * 20), 0.6, 0.85F, 0.9F, 1F);
            }
        }
        if (t >= IMPACT && t < IMPACT + 0.6) {
            double age = (t - IMPACT) / 0.6;
            Vec3 r = reacteur().subtract(cam);
            Effets.sphere(pose, buffers, r, 1 + 4 * Math.sqrt(age), 1F, 0.8F, 0.4F, (float) (1 - age));
            Effets.sphere(pose, buffers, r, 2 + 7 * Math.sqrt(age), 1F, 0.45F, 0.1F, (float) (0.6 * (1 - age)));
        }
        buffers.endBatch();
        mc.getEntityRenderDispatcher().setRenderShadow(mc.options.entityShadows().get());
    }

    private static void dessiner(PoseStack pose, MultiBufferSource buffers, Acteur a, int teinteBord) {
        if (!a.visible) {
            return;
        }
        if (a.joueur) {
            Effets.joueur(pose, buffers, a.skin, a.fin, a.pos, a.lacet, a.posture, teinteBord);
        } else {
            Effets.personnage(pose, buffers, a.skin, a.pos, a.lacet, a.posture, 1F, Effets.SANS, Effets.NOIR, 1F, teinteBord);
        }
    }

    /** Le chariot de l'hôtesse : un caisson de métal, deux bouteilles dessus. */
    private static void dessinerChariot(PoseStack pose, MultiBufferSource buffers, int lumiereBord) {
        Minecraft mc = Minecraft.getInstance();
        double c = t < HOTESSE ? 4.5 : chariot();
        pose.pushPose();
        pose.translate(c - 0.4, 1.0, -0.28);
        pose.scale(0.8F, 0.95F, 0.56F);
        mc.getBlockRenderer().renderSingleBlock(Blocks.IRON_BLOCK.defaultBlockState(), pose, buffers, lumiereBord, OverlayTexture.NO_OVERLAY);
        pose.popPose();
        ItemStack[] dessus = {new ItemStack(Items.POTION), new ItemStack(Items.HONEY_BOTTLE)};
        for (int i = 0; i < dessus.length; i++) {
            pose.pushPose();
            pose.translate(c - 0.2 + i * 0.4, 2.12, 0);
            pose.scale(0.5F, 0.5F, 0.5F);
            mc.getItemRenderer().renderStatic(dessus[i], ItemDisplayContext.GROUND, lumiereBord, OverlayTexture.NO_OVERLAY, pose, buffers, niveau, i);
            pose.popPose();
        }
    }

    /** Les masques à oxygène tombés des coffres à bagages, au-dessus de chaque siège. */
    private static void dessinerMasques(PoseStack pose, MultiBufferSource buffers, int lumiereBord) {
        Minecraft mc = Minecraft.getInstance();
        double chute = lisse(Mth.clamp((t - IMPACT - 1) / 0.4, 0, 1));
        for (int x : Avion3D.RANGEES) {
            for (int z : Avion3D.PLACES) {
                double balance = 0.08 * Math.sin(t * 5 + x + z);
                double y = 4.5 - 1.15 * chute;
                pose.pushPose();
                pose.translate(x + 0.55 + balance - 0.11, y, z - 0.11);
                pose.scale(0.22F, 0.18F, 0.22F);
                mc.getBlockRenderer().renderSingleBlock(Blocks.YELLOW_CONCRETE.defaultBlockState(), pose, buffers, lumiereBord, OverlayTexture.NO_OVERLAY);
                pose.popPose();
                pose.pushPose();
                pose.translate(x + 0.55 + balance - 0.5, y + 0.18, z - 0.5);
                pose.scale(1F, (float) (4.5 - y), 1F);
                mc.getBlockRenderer().renderSingleBlock(Blocks.CHAIN.defaultBlockState(), pose, buffers, lumiereBord, OverlayTexture.NO_OVERLAY);
                pose.popPose();
            }
        }
    }

    /** La nuée de phantoms : elle sort de l'orage droit devant ; cinq se jettent dans le réacteur droit, les autres frôlent l'avion. */
    private static void dessinerPhantoms(PoseStack pose, MultiBufferSource buffers) {
        if (phantom == null) {
            return;
        }
        EntityRenderDispatcher rendu = Minecraft.getInstance().getEntityRenderDispatcher();
        rendu.setRenderShadow(false);
        double[] r = Avion3D.REACTEUR_DROIT;
        Random rr = new Random(42);
        for (int i = 0; i < 16; i++) {
            boolean kamikaze = i < 5;
            double ox = rr.nextDouble() * 8;
            double oy = kamikaze ? rr.nextDouble() * 2 - 1 : rr.nextDouble() * 12 - 4;
            double oz = kamikaze ? rr.nextDouble() * 2 - 1 : rr.nextDouble() * 26 - 6;
            // ils arrivent à 22 blocs par seconde (vitesse de l'avion comprise)
            double avance = (IMPACT - t) * 22 + ox;
            if (kamikaze && t > IMPACT + i * 0.03) {
                continue;
            }
            Vec3 p = kamikaze ? new Vec3(r[0] + avance, r[1] + oy * Math.min(1, avance / 20), r[2] + oz * Math.min(1, avance / 20))
                    : new Vec3(r[0] + avance, r[1] + oy, r[2] + oz);
            if (p.x > 120 || p.x < -60) {
                continue;
            }
            phantom.tickCount = (int) (t * 20) + i * 7;
            phantom.yBodyRot = 90;
            phantom.yBodyRotO = 90;
            phantom.yHeadRot = 90;
            phantom.yHeadRotO = 90;
            phantom.setYRot(90);
            phantom.yRotO = 90;
            phantom.setXRot(0);
            phantom.xRotO = 0;
            rendu.render(phantom, p.x, p.y, p.z, 90, 0, pose, buffers, LightTexture.FULL_BRIGHT);
        }
    }

    /** Les bandes noires du cinéma, les sous-titres, le titre, les flashs, l'alarme, le fondu. */
    private static void dessinerEcran(GuiGraphics g) {
        Minecraft mc = Minecraft.getInstance();
        int w = g.guiWidth();
        int h = g.guiHeight();
        int bande = h / 9;
        if (sombre > 0) {
            g.fill(0, 0, w, h, (int) (sombre * 255) << 24);
        }
        if (rouge > 0) {
            g.fill(0, 0, w, h, (Mth.clamp((int) (rouge * 255), 0, 255) << 24) | 0xD01010);
        }
        if (flash > 0) {
            g.fill(0, 0, w, h, (Mth.clamp((int) (flash * 255), 0, 255) << 24) | couleurFlash);
        }
        g.fill(0, 0, w, bande, 0xFF000000);
        g.fill(0, h - bande, w, h, 0xFF000000);
        if (noir > 0) {
            g.fill(0, 0, w, h, Mth.clamp((int) (noir * 255), 0, 255) << 24);
        }
        if (!titre.isEmpty()) {
            Ios.texteCentre(g, mc.font, titre, w / 2F, h / 2F - 18, 2.6F, 0xFFFFC83A, true);
            if (!sousTitre.isEmpty()) {
                Ios.texteCentre(g, mc.font, sousTitre, w / 2F, h / 2F + 14, 1F, 0xFFD0D0D0, true);
            }
        }
        for (Replique r : REPLIQUES) {
            if (t < r.debut() || t >= r.fin() || noir >= 1) {
                continue;
            }
            var lignes = mc.font.split(net.minecraft.network.chat.Component.literal(r.texte()), Math.min(w - 60, 420));
            int y = h - bande + (bande - lignes.size() * 10 - 10) / 2;
            g.drawCenteredString(mc.font, r.nom().toUpperCase(), w / 2, y, r.couleur());
            for (int i = 0; i < lignes.size(); i++) {
                g.drawCenteredString(mc.font, lignes.get(i), w / 2, y + 11 + i * 10, 0xFFFFFFFF);
            }
            break;
        }
    }

    // ================================================================ les sons en boucle

    /**
     * Un son qui dure toute la cinématique (en boucle) ou un morceau joué une fois ; son volume suit ce qui est à l'écran.
     * Canaux : 0 les réacteurs, 1 le grondement, 2 la pluie, 3 la musique de l'aéroport.
     */
    private static final class Boucle extends AbstractTickableSoundInstance {
        static final float[] VOLUMES = new float[4];
        static final float[] HAUTEURS = {1, 1, 1, 1};
        private final int canal;

        Boucle(SoundEvent son, int canal, boolean boucle) {
            super(son, boucle ? SoundSource.AMBIENT : SoundSource.MASTER, SoundInstance.createUnseededRandom());
            this.looping = boucle;
            this.delay = 0;
            this.volume = 0;
            this.relative = true;
            this.attenuation = SoundInstance.Attenuation.NONE;
            this.canal = canal;
        }

        @Override
        public boolean canStartSilent() {
            return true;
        }

        @Override
        public void tick() {
            if (!actif) {
                stop();
                return;
            }
            volume = VOLUMES[canal];
            pitch = HAUTEURS[canal];
        }
    }

    // ================================================================ branchements

    /** Le ciel de l'aéroport : celui de l'Overworld, mais il ne pleut jamais dans la cabine de l'avion. */
    private static final class Ciel extends DimensionSpecialEffects {
        Ciel() {
            super(192F, true, SkyType.NORMAL, false, false);
        }

        @Override
        public Vec3 getBrightnessDependentFogColor(Vec3 couleur, float lumiereCiel) {
            return couleur.multiply(lumiereCiel * 0.94F + 0.06F, lumiereCiel * 0.94F + 0.06F, lumiereCiel * 0.91F + 0.09F);
        }

        @Override
        public boolean isFoggyAt(int x, int y) {
            return false;
        }

        @Override
        public boolean renderSnowAndRain(ClientLevel level, int ticks, float partialTick, LightTexture lumieres, double x, double y, double z) {
            return actif && dedans;
        }

        @Override
        public boolean tickRain(ClientLevel level, int ticks, Camera cam) {
            return actif && dedans;
        }
    }

    @EventBusSubscriber(modid = Cabane.MODID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
    public static final class Enregistrement {
        @SubscribeEvent
        public static void couches(RegisterGuiLayersEvent event) {
            event.registerAboveAll(Cabane.id("decollage"), (g, delta) -> {
                if (actif) {
                    dessinerEcran(g);
                }
            });
        }

        @SubscribeEvent
        public static void ciel(RegisterDimensionSpecialEffectsEvent event) {
            event.register(Cabane.id("aeroport"), new Ciel());
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
            // changement de monde (la fin : le réveil dans l'avion en feu), ou déconnexion
            if (mc.level == null || mc.player == null || mc.level != niveau || ecoule() > Decollage.DUREE + 6) {
                arreter();
                return;
            }
            calculer(ecoule());
            tPrecedent = tDernierTick;
            evenements();
            tDernierTick = t;
            boucles();
            ambiance();
            niveau.setRainLevel(pluie);
            niveau.setThunderLevel(orage);
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
            calculer(ecoule());
            placerCamera();
            if (niveau != null) {
                niveau.setRainLevel(pluie);
                niveau.setThunderLevel(orage);
            }
        }

        @SubscribeEvent
        public static void dessiner(RenderLevelStageEvent event) {
            if (!actif || event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) {
                return;
            }
            dessinerMonde(event.getPoseStack(), event.getCamera().getPosition(), Minecraft.getInstance().renderBuffers().bufferSource());
        }

        @SubscribeEvent
        public static void champ(ViewportEvent.ComputeFov event) {
            if (actif) {
                event.setFOV(fov);
            }
        }

        @SubscribeEvent
        public static void angles(ViewportEvent.ComputeCameraAngles event) {
            if (!actif) {
                return;
            }
            float r = roulisCamera;
            if (secousse > 0) {
                r += (float) ((Math.random() - 0.5) * secousse * 3);
            }
            event.setRoll(r);
        }

        /** L'orage : un brouillard gris qui se referme autour de l'avion. */
        @SubscribeEvent
        public static void brouillard(ViewportEvent.RenderFog event) {
            if (actif && brume > 0 && !dedans) {
                event.setFarPlaneDistance(Math.min(event.getFarPlaneDistance(), brume));
                event.setNearPlaneDistance(Math.min(event.getNearPlaneDistance(), brume * 0.2F));
                event.setCanceled(true);
            }
        }

        @SubscribeEvent
        public static void couleurBrouillard(ViewportEvent.ComputeFogColor event) {
            if (actif && orage > 0) {
                float k = orage * 0.6F;
                event.setRed(Mth.lerp(k, event.getRed(), 0.22F));
                event.setGreen(Mth.lerp(k, event.getGreen(), 0.24F));
                event.setBlue(Mth.lerp(k, event.getBlue(), 0.28F));
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
            if (actif && !event.getName().equals(Cabane.id("decollage"))) {
                event.setCanceled(true);
            }
        }
    }

    private ClientDecollage() {
    }
}
