package fr.cabane.client.pouvoir;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import fr.cabane.Cabane;
import fr.cabane.pouvoir.Minato;
import fr.cabane.pouvoir.Pouvoirs;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.RenderHandEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.event.RenderLivingEvent;
import net.neoforged.neoforge.client.event.RenderPlayerEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * Minato chez chaque joueur (voir {@link Minato}) : qui est éveillé, les kunais en vol ou plantés, le clic droit et le
 * panneau des touches. Le combo lui-même (la cinématique et ce que voient les autres) est dans {@link ClientMinatoCombo}.
 */
public final class ClientMinato {
    private static final Set<UUID> ELUS = new HashSet<>();
    private static final RandomSource HASARD = RandomSource.create();
    private static final DustParticleOptions JAUNE = new DustParticleOptions(new Vector3f(1F, 0.84F, 0.2F), 0.9F);

    /** Un kunai : d'où il part et à quelle vitesse (par tick), ou où il est planté. */
    private static final class Lame {
        Vec3 pos;
        Vec3 vitesse;
        long debut;
        boolean plante;
    }

    private static final Map<UUID, Lame> LAMES = new HashMap<>();
    /** Les kunais lancés à la main (accroupi + clic gauche), en vol : d'où et à quelle vitesse ils sont partis. */
    private static final Map<UUID, Lame> ENVOLS = new HashMap<>();
    /** Quand mon kunai est de nouveau prêt (System.nanoTime), -1 s'il l'est déjà. */
    private static long pret = -1;
    /** Quand je pourrai relancer un kunai à la main (System.nanoTime), -1 si c'est déjà possible. */
    private static long pretJet = -1;
    private static long dernierClic;
    private static boolean accroupiAvant;
    /** Quand on s'est accroupi la dernière fois (System.nanoTime) : deux fois de suite, vite, on rejoint la marque posée. */
    private static long dernierAccroupi;
    private static final long DOUBLE_ACCROUPI = 400_000_000L;
    /** Le clic gauche est resté enfoncé depuis la dernière fois (il ne compte qu'à l'appui). */
    private static boolean gaucheTenu;

    /** Le niveau de chaque Minato éveillé : 1, ou 2 (le mode Kurama de Minato 2). */
    private static final Map<UUID, Integer> NIVEAUX = new HashMap<>();
    /** Mode Kurama : l'entité sur laquelle ma vue est bloquée (-1 : libre). */
    private static int visee = -1;
    /** Mode Kurama : quand je pourrai me téléporter là où je regarde (System.nanoTime), -1 si c'est déjà possible. */
    private static long pretEclair = -1;
    /** Mode Kurama : depuis quand j'ai enfoncé accroupi, si un clic est venu pendant, et quand je l'ai lâché vite (un seul appui). */
    private static long appuiAccroupi;
    private static boolean clicPendantAccroupi;
    private static long toucheSeule = -1;

    private static int niveau(UUID joueur) {
        return NIVEAUX.getOrDefault(joueur, 0);
    }

    /** Un Minato est-il en train de jouer une cinématique (le combo ou la Bijuu Dama), avec moi dedans ? */
    private static boolean enScene() {
        return ClientMinatoCombo.participant() || ClientBijuuDama.participant();
    }

    private static boolean cache(Entity e) {
        return ClientMinatoCombo.cache(e) || ClientBijuuDama.cache(e);
    }

    /** Une marque d'un Minato : un kunai planté (1, la pointe vers dir s'il a été lancé) ou sur quelqu'un (2). */
    private record Marque(int type, String dim, Vec3 pos, Vec3 dir, int entite, String nom) {
    }

    /** La marque posée (accroupi + clic droit) de chaque Minato. */
    private static final Map<UUID, Marque> MARQUES = new HashMap<>();
    /** Le kunai lancé à la main (accroupi + clic gauche) de chaque Minato, une fois planté. */
    private static final Map<UUID, Marque> LANCES = new HashMap<>();

    public static void marque(Minato.MarqueEtat p) {
        Map<UUID, Marque> ou = p.lance() ? LANCES : MARQUES;
        if (p.marque() == 0) {
            ou.remove(p.joueur());
        } else {
            Vec3 dir = vec(p.dir());
            ou.put(p.joueur(), new Marque(p.marque(), p.dim(), vec(p.pos()), dir.lengthSqr() < 0.01 ? null : dir, p.entite(), p.nom()));
        }
    }

    public static void jet(Minato.Jet p) {
        if (!p.vole()) {
            ENVOLS.remove(p.joueur());
            return;
        }
        Lame l = new Lame();
        l.pos = vec(p.pos());
        l.vitesse = vec(p.vitesse());
        l.debut = System.nanoTime();
        ENVOLS.put(p.joueur(), l);
        if (moi(p.joueur())) {
            pretJet = System.nanoTime() + Minato.RECHARGE_JET * 50_000_000L;
        }
    }

    /** Où en est un kunai lancé à la main, t ticks après son départ (les mêmes pas que sur le serveur) : position et vitesse. */
    private static Vec3[] envol(Lame l, double t) {
        t = Math.min(t, Minato.JET_VIE + 1);
        Vec3 p = l.pos;
        Vec3 v = l.vitesse;
        int n = (int) t;
        for (int i = 0; i < n; i++) {
            p = p.add(v);
            v = v.scale(Minato.JET_FREIN).add(0, -Minato.JET_GRAVITE, 0);
        }
        return new Vec3[] {p.add(v.scale(t - n)), v};
    }

    private static boolean ici(Marque m) {
        Minecraft mc = Minecraft.getInstance();
        return mc.level != null && mc.level.dimension().location().toString().equals(m.dim());
    }

    private static double secondes(long depuis) {
        return (System.nanoTime() - depuis) / 1e9;
    }

    private static Vec3 vec(Vector3f v) {
        return new Vec3(v.x(), v.y(), v.z());
    }

    private static boolean moi(UUID joueur) {
        Minecraft mc = Minecraft.getInstance();
        return mc.player != null && mc.player.getUUID().equals(joueur);
    }

    // ================================================================ ce que dit le serveur

    /** Suis-je un Minato 2 (la touche Éveil me donnera le mode Kurama) ? */
    private static boolean minato2;

    public static void recevoir(Pouvoirs.Etat p) {
        Minecraft mc = Minecraft.getInstance();
        if (moi(p.joueur())) {
            minato2 = p.pouvoir().equals(Minato.NOM2);
        }
        int avant = niveau(p.joueur());
        if (!p.eveille()) {
            ELUS.remove(p.joueur());
            NIVEAUX.remove(p.joueur());
            // la fin du mode Kurama : l'anneau redescend et enlève la tenue
            if (avant >= 2) {
                ClientKurama.detransformer(p.joueur());
            }
            if (moi(p.joueur())) {
                visee = -1;
            }
            return;
        }
        NIVEAUX.put(p.joueur(), p.niveau());
        if (p.niveau() >= 2 && avant < 2) {
            if (p.annonce()) {
                ClientKurama.transformer(p.joueur());
            } else {
                ClientKurama.dejaLa(p.joueur());
            }
        }
        if (ELUS.add(p.joueur()) && p.annonce() && mc.level != null) {
            Player joueur = mc.level.getPlayerByUUID(p.joueur());
            if (joueur != null) {
                for (int i = 0; i < 30; i++) {
                    mc.level.addParticle(ParticleTypes.ELECTRIC_SPARK, joueur.getX() + aleat(0.8), joueur.getY() + HASARD.nextDouble() * 2,
                            joueur.getZ() + aleat(0.8), aleat(0.3), 0.1, aleat(0.3));
                }
            }
        }
    }

    public static void oublier(UUID joueur) {
        ELUS.remove(joueur);
        NIVEAUX.remove(joueur);
        ClientKurama.oublier(joueur);
    }

    /** Mode Kurama : ma vue se bloque sur cette entité (ou se libère). */
    public static void viseur(Minato.Viseur p) {
        visee = p.cible();
    }

    public static void bijuu(Minato.BijuuDama p) {
        if (p.points().isEmpty()) {
            ClientBijuuDama.arreter(p.joueur());
            return;
        }
        LAMES.remove(p.joueur());
        if (moi(p.joueur())) {
            pret = System.nanoTime() + (Minato.DUREE_BIJUU + Minato.RECHARGE) * 50_000_000L;
            visee = -1;
        }
        ClientBijuuDama.demarrer(p);
    }

    /** Ma vue suit la cible bloquée (le mode Kurama) : à chaque image, je la regarde droit dans les yeux. */
    private static void suivre(float partiel) {
        Minecraft mc = Minecraft.getInstance();
        if (visee < 0 || mc.player == null || mc.level == null || enScene()) {
            return;
        }
        Entity e = mc.level.getEntity(visee);
        if (e == null || !e.isAlive()) {
            visee = -1;
            return;
        }
        Vec3 d = e.getPosition(partiel).add(0, e.getEyeHeight(), 0).subtract(mc.player.getEyePosition(partiel));
        float lacet = (float) Math.toDegrees(Math.atan2(-d.x, d.z));
        float tangage = (float) Math.toDegrees(-Math.atan2(d.y, Math.sqrt(d.x * d.x + d.z * d.z)));
        mc.player.setYRot(lacet);
        mc.player.setXRot(tangage);
        mc.player.yRotO = lacet;
        mc.player.xRotO = tangage;
        mc.player.setYHeadRot(lacet);
    }

    public static void kunai(Minato.Kunai p) {
        Lame l = new Lame();
        l.pos = vec(p.pos());
        l.vitesse = vec(p.dir());
        l.debut = System.nanoTime();
        switch (p.etape()) {
            case 0 -> LAMES.put(p.joueur(), l);
            case 1 -> {
                l.plante = true;
                LAMES.put(p.joueur(), l);
                if (moi(p.joueur())) {
                    pret = System.nanoTime() + Minato.RECHARGE_RATE * 50_000_000L;
                }
            }
            default -> {
                LAMES.remove(p.joueur());
                if (moi(p.joueur())) {
                    pret = System.nanoTime() + Minato.RECHARGE_RATE * 50_000_000L;
                }
            }
        }
    }

    public static void combo(Minato.Combo p) {
        if (p.points().isEmpty()) {
            ClientMinatoCombo.arreter(p.joueur());
            return;
        }
        LAMES.remove(p.joueur());
        if (moi(p.joueur())) {
            pret = System.nanoTime() + (Minato.DUREE + Minato.RECHARGE) * 50_000_000L;
        }
        ClientMinatoCombo.demarrer(p);
    }

    private static double aleat(double d) {
        return (HASARD.nextDouble() - 0.5) * 2 * d;
    }

    // ================================================================ le kunai en main

    private static ItemStack kunai;
    /** Ce que tenaient vraiment les joueurs à qui on a mis le kunai dans la main, le temps de les dessiner. */
    private static final Map<Player, ItemStack> TENUS = new HashMap<>();

    private static ItemStack kunaiEnMain() {
        if (kunai == null || kunai.isEmpty()) {
            kunai = new ItemStack(Minato.kunai());
        }
        return kunai;
    }

    private static void rendre(Player p) {
        ItemStack avant = TENUS.remove(p);
        if (avant != null) {
            p.getInventory().items.set(p.getInventory().selected, avant);
        }
    }

    /** Par sécurité : si un dessin a été interrompu, chacun récupère ce qu'il tenait. */
    private static void rendre() {
        for (Player p : TENUS.keySet().toArray(new Player[0])) {
            rendre(p);
        }
    }

    // ================================================================ le kunai en 3D

    private static void sommet(VertexConsumer c, Matrix4f m, Vec3 p, float r, float g, float b) {
        c.addVertex(m, (float) p.x, (float) p.y, (float) p.z).setColor(r, g, b, 1F);
    }

    /** Une boîte orientée : centre, trois axes (unitaires) et ses demi-longueurs le long de chacun. */
    private static void boite(VertexConsumer c, Matrix4f m, Vec3 o, Vec3[] axes, double a, double b, double e, float r, float g, float bl) {
        Vec3 u = axes[0].scale(a), v = axes[1].scale(b), w = axes[2].scale(e);
        Vec3[][] faces = {{u, v, w}, {u.reverse(), v, w.reverse()}, {v, w, u}, {v.reverse(), w, u.reverse()}, {w, u, v}, {w.reverse(), u, v.reverse()}};
        float[] ombre = {1F, 0.7F, 0.9F, 0.6F, 0.8F, 0.75F};
        for (int i = 0; i < 6; i++) {
            Vec3 n = faces[i][0], x = faces[i][1], y = faces[i][2];
            Vec3 centre = o.add(n);
            float k = ombre[i];
            sommet(c, m, centre.subtract(x).subtract(y), r * k, g * k, bl * k);
            sommet(c, m, centre.add(x).subtract(y), r * k, g * k, bl * k);
            sommet(c, m, centre.add(x).add(y), r * k, g * k, bl * k);
            sommet(c, m, centre.subtract(x).add(y), r * k, g * k, bl * k);
        }
    }

    /** Un kunai lancé s'enfonce de 0,14 bloc : sa base (où commence la lame de 0,42) est à 0,28 en arrière de là où il touche. */
    private static final double PLANTE = 0.28;

    private static final float[] METAL = {0.45F, 0.46F, 0.4F};
    private static final float[] ACIER = {0.8F, 0.82F, 0.86F};
    private static final float[] CROCHET = {0.62F, 0.64F, 0.62F};
    private static final float[] MANCHE = {0.87F, 0.74F, 0.52F};
    private static final float[] ENCRE = {0.08F, 0.07F, 0.06F};

    /** Une barre de a à b, dans le plan de la lame : demi-largeur w, demi-épaisseur ep (le long de e). */
    private static void barre(VertexConsumer c, Matrix4f m, Vec3 a, Vec3 b, Vec3 e, double w, double ep, float[] col) {
        Vec3 d = b.subtract(a);
        double l = d.length();
        if (l < 1.0E-5) {
            return;
        }
        Vec3 u = d.scale(1 / l);
        boite(c, m, a.add(b).scale(0.5), new Vec3[] {u, e.cross(u).normalize(), e}, l / 2, w, ep, col[0], col[1], col[2]);
    }

    /**
     * Le kunai de Minato, d'après l'image donnée par l'utilisateur : un anneau, un manche beige gravé de signes noirs,
     * une bague, la lame en pointe et ses deux crochets recourbés vers le manche. pos (relative à la caméra) est la base
     * de la lame, dir là où elle pointe ; il fait un peu moins d'un bloc de long.
     */
    public static void dessinerKunai(PoseStack pose, MultiBufferSource buffers, Vec3 pos, Vec3 dir) {
        if (dir.lengthSqr() < 1.0E-6) {
            return;
        }
        Vec3 d = dir.normalize();
        Vec3 a = Math.abs(d.y) < 0.9 ? new Vec3(0, 1, 0) : new Vec3(1, 0, 0);
        Vec3 l = d.cross(a).normalize();
        Vec3 e = d.cross(l).normalize();
        VertexConsumer c = buffers.getBuffer(RenderType.debugQuads());
        Matrix4f m = pose.last().pose();
        // l'anneau, au bout du manche
        for (int i = 0; i < 10; i++) {
            double q0 = i * Math.PI / 5, q1 = (i + 1) * Math.PI / 5;
            barre(c, m, pos.add(d.scale(-0.45 + 0.065 * Math.cos(q0))).add(l.scale(0.065 * Math.sin(q0))),
                    pos.add(d.scale(-0.45 + 0.065 * Math.cos(q1))).add(l.scale(0.065 * Math.sin(q1))), e, 0.014, 0.014, METAL);
        }
        barre(c, m, pos.add(d.scale(-0.385)), pos.add(d.scale(-0.3)), e, 0.016, 0.016, METAL);
        // le manche beige, et ses signes noirs sur les quatre faces
        barre(c, m, pos.add(d.scale(-0.3)), pos.add(d.scale(-0.06)), e, 0.034, 0.034, MANCHE);
        Vec3[] axes = {d, l, e};
        double[][] signes = {{-0.27, 0.01, 0.012}, {-0.235, -0.008, 0.008}, {-0.2, 0.012, 0.014}, {-0.16, -0.01, 0.01}, {-0.12, 0.006, 0.013}, {-0.085, -0.012, 0.008}};
        for (double[] s : signes) {
            for (int f = -1; f <= 1; f += 2) {
                boite(c, m, pos.add(d.scale(s[0])).add(e.scale(0.035 * f)).add(l.scale(s[1])), axes, s[2], 0.014, 0.003, ENCRE[0], ENCRE[1], ENCRE[2]);
                boite(c, m, pos.add(d.scale(s[0])).add(l.scale(0.035 * f)).add(e.scale(s[1])), axes, s[2], 0.003, 0.014, ENCRE[0], ENCRE[1], ENCRE[2]);
            }
        }
        barre(c, m, pos.add(d.scale(-0.06)), pos.add(d.scale(-0.01)), e, 0.045, 0.04, METAL);
        // la lame : un losange effilé, en tranches de plus en plus fines
        double[][] lame = {{-0.01, 0.06}, {0.08, 0.055}, {0.16, 0.045}, {0.24, 0.034}, {0.31, 0.022}, {0.37, 0.011}, {0.42, 0.002}};
        for (int i = 0; i + 1 < lame.length; i++) {
            barre(c, m, pos.add(d.scale(lame[i][0])), pos.add(d.scale(lame[i + 1][0])), e, (lame[i][1] + lame[i + 1][1]) / 2, 0.012, ACIER);
        }
        // les deux crochets : partis de la base de la lame, ils s'écartent puis se recourbent vers le manche
        for (int cote = -1; cote <= 1; cote += 2) {
            Vec3 prec = null;
            for (int i = 0; i <= 6; i++) {
                double k = i / 6.0;
                double x = (1 - k) * (1 - k) * 0.05 + 2 * (1 - k) * k * 0.07 + k * k * -0.12;
                double y = (1 - k) * (1 - k) * 0.03 + 2 * (1 - k) * k * 0.17 + k * k * 0.19;
                Vec3 q = pos.add(d.scale(x)).add(l.scale(cote * y));
                if (prec != null) {
                    barre(c, m, prec, q, e, 0.03 * (1 - k) + 0.006, 0.011, CROCHET);
                }
                prec = q;
            }
        }
    }

    // ================================================================ le panneau des touches

    private static void ecran(GuiGraphics g) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.options.hideGui || !ELUS.contains(mc.player.getUUID()) || ClientMinatoCombo.cinematique() || ClientBijuuDama.participant()) {
            return;
        }
        UUID moi = mc.player.getUUID();
        boolean kurama = niveau(moi) >= 2;
        double reste = pret < 0 ? 0 : -secondes(pret);
        boolean vol = LAMES.containsKey(moi) && !LAMES.get(moi).plante;
        String kunai = kurama ? (reste > 0 ? String.format("Bijuu Dama (%.0f s)", Math.ceil(reste)) : "Bijuu Dama")
                : vol ? "Kunai (en vol…)" : reste > 0 ? String.format("Kunai (%.0f s)", Math.ceil(reste)) : "Lancer le kunai";
        String titre = kurama ? "MINATO  ·  MODE KURAMA" : "MINATO  ·  ÉCLAIR JAUNE";
        Marque m = MARQUES.get(moi);
        String marque = m == null ? "Aucun kunai posé" : m.type() == 2 ? "Rejoindre " + m.nom() : "Revenir au kunai";
        // le kunai lancé à la main : en vol ou planté, on y va ; sinon on en lance un (après la recharge)
        Marque lance = LANCES.get(moi);
        boolean envol = ENVOLS.containsKey(moi);
        double resteJet = pretJet < 0 ? 0 : -secondes(pretJet);
        String jet = envol ? "Attraper le kunai en vol" : lance != null ? (lance.type() == 2 ? "Rejoindre " + lance.nom() : "Aller au kunai lancé")
                : resteJet > 0 ? String.format("Lancer un kunai (%.0f s)", Math.ceil(resteJet)) : "Lancer un kunai";
        double resteEclair = pretEclair < 0 ? 0 : -secondes(pretEclair);
        String eveil = ClientSusano.EVEIL.getTranslatedKeyMessage().getString();
        // Minato 2 au niveau 1 : la touche Éveil donne le mode Kurama
        boolean monte = !kurama && Minecraft.getInstance().getConnection() != null && minato2;
        String[][] lignes = kurama
                ? new String[][] {{Clavier.gauche(), "Frapper (TP autour)"}, {Clavier.droite(), kunai},
                        {Clavier.accroupi() + " + " + Clavier.droite(), resteEclair > 0 ? String.format("TP où tu regardes (%.0f s)", Math.ceil(resteEclair)) : "TP où tu regardes"},
                        {Clavier.accroupi() + " + " + Clavier.gauche(), jet}, {Clavier.accroupi() + " ×1", "Planter un kunai"}, {Clavier.accroupi() + " ×2", marque}, {eveil, "Rendormir"}}
                : new String[][] {{Clavier.gauche(), "Frapper au kunai"}, {Clavier.droite(), kunai}, {Clavier.accroupi() + " + " + Clavier.gauche(), jet},
                        {Clavier.accroupi() + " + " + Clavier.droite(), "Poser la marque"}, {Clavier.accroupi() + " ×2", marque}, {eveil, monte ? "Mode Kurama" : "Rendormir"}};
        boolean[] gris = kurama
                ? new boolean[] {false, reste > 0, resteEclair > 0, !envol && lance == null && resteJet > 0, false, m == null, false}
                : new boolean[] {false, vol || reste > 0, !envol && lance == null && resteJet > 0, false, m == null, false};
        Font font = mc.font;
        int largeurTouche = 0;
        int largeurAction = font.width(titre) - 10;
        for (String[] l : lignes) {
            largeurTouche = Math.max(largeurTouche, font.width(l[0]));
            largeurAction = Math.max(largeurAction, font.width(l[1]));
        }
        int pw = largeurTouche + largeurAction + 26;
        int ph = 22 + lignes.length * 13;
        int x = 6;
        int y = g.guiHeight() / 2 - ph / 2;
        int teinte = kurama ? 0xFFFF9A2A : 0xFFFFD23A;
        g.fill(x, y, x + pw, y + ph, 0xA0000000);
        g.fill(x, y, x + 2, y + ph, teinte);
        g.drawString(font, titre, x + 8, y + 6, teinte, true);
        for (int i = 0; i < lignes.length; i++) {
            int ly = y + 21 + i * 13;
            g.fill(x + 7, ly - 2, x + 11 + font.width(lignes[i][0]), ly + 9, 0x40FFFFFF);
            g.drawString(font, lignes[i][0], x + 9, ly, 0xFFFFFFFF, false);
            g.drawString(font, lignes[i][1], x + 18 + largeurTouche, ly, gris[i] ? 0xFF7A7A7A : 0xFFEDEDED, true);
        }
    }

    // ================================================================ branchements

    @EventBusSubscriber(modid = Cabane.MODID, value = Dist.CLIENT)
    public static final class Couches {
        @SubscribeEvent
        public static void enregistrer(RegisterGuiLayersEvent event) {
            event.registerAboveAll(Cabane.id("minato"), (g, delta) -> ecran(g));
        }

        private Couches() {
        }
    }

    @EventBusSubscriber(modid = Cabane.MODID, value = Dist.CLIENT)
    public static final class Evenements {
        @SubscribeEvent
        public static void tick(ClientTickEvent.Post event) {
            Minecraft mc = Minecraft.getInstance();
            rendre();
            if (mc.level == null || mc.player == null) {
                ELUS.clear();
                LAMES.clear();
                ENVOLS.clear();
                MARQUES.clear();
                LANCES.clear();
                pret = -1;
                pretJet = -1;
                return;
            }
            // s'accroupir deux fois de suite, vite (les appuis, pas le maintien), avec une marque posée : on y va
            boolean accroupi = mc.options.keyShift.isDown();
            UUID moi = mc.player.getUUID();
            boolean libre = mc.screen == null && ELUS.contains(moi) && !enScene();
            long t = System.nanoTime();
            if (accroupi && !accroupiAvant && libre) {
                appuiAccroupi = t;
                clicPendantAccroupi = false;
                // un second appui annule le kunai qu'un seul appui allait planter
                toucheSeule = -1;
                if (t - dernierAccroupi < DOUBLE_ACCROUPI && MARQUES.containsKey(moi)) {
                    PacketDistributor.sendToServer(new Minato.Rejoindre());
                    dernierAccroupi = 0;
                } else {
                    dernierAccroupi = t;
                }
            }
            // mode Kurama : un seul appui, court, sans clic pendant, et rien derrière : un kunai se plante à ses pieds
            if (!accroupi && accroupiAvant && libre && niveau(moi) >= 2 && dernierAccroupi == appuiAccroupi
                    && t - appuiAccroupi < 300_000_000L && !clicPendantAccroupi) {
                toucheSeule = t;
            }
            if (toucheSeule > 0 && t - appuiAccroupi > DOUBLE_ACCROUPI) {
                toucheSeule = -1;
                if (libre && niveau(moi) >= 2) {
                    PacketDistributor.sendToServer(new Minato.PoserPieds());
                }
            }
            accroupiAvant = accroupi;
            if (!ELUS.contains(moi) || niveau(moi) < 2) {
                visee = -1;
            }
            if (pretEclair >= 0 && secondes(pretEclair) > 0) {
                pretEclair = -1;
            }
            if (!mc.options.keyAttack.isDown()) {
                gaucheTenu = false;
            }
            ENVOLS.values().removeIf(l -> secondes(l.debut) > Minato.JET_VIE / 20.0 + 1);
            // ses propres marques luisent doucement : des grains jaunes sur celui qui la porte, une étincelle au-dessus du kunai
            if (mc.level.getGameTime() % 6 == 0) {
                for (Marque m : new Marque[] {MARQUES.get(moi), LANCES.get(moi)}) {
                    if (m == null || !ici(m)) {
                        continue;
                    }
                    Entity porteur = m.type() == 2 && m.entite() >= 0 ? mc.level.getEntity(m.entite()) : null;
                    if (porteur != null) {
                        mc.level.addParticle(JAUNE, porteur.getX() + aleat(0.3), porteur.getY() + porteur.getBbHeight() + 0.2, porteur.getZ() + aleat(0.3), 0, 0.02, 0);
                    } else if (m.type() == 1 && m.pos().distanceToSqr(mc.player.position()) < 64 * 64) {
                        mc.level.addParticle(ParticleTypes.END_ROD, m.pos().x + aleat(0.1), m.pos().y + 0.6, m.pos().z + aleat(0.1), 0, 0.04, 0);
                    }
                }
            }
            if (pret >= 0 && secondes(pret) > 0) {
                pret = -1;
            }
            if (pretJet >= 0 && secondes(pretJet) > 0) {
                pretJet = -1;
            }
            LAMES.values().removeIf(l -> secondes(l.debut) > (l.plante ? Minato.RECHARGE_RATE / 20.0 : 1.2));
            // un peu de chakra jaune qui crépite autour de ceux qui sont éveillés
            if (mc.level.getGameTime() % 4 == 0) {
                for (UUID id : ELUS) {
                    Player joueur = mc.level.getPlayerByUUID(id);
                    // pas autour de soi en vue à la première personne : les grains passeraient devant les yeux
                    boolean yeux = joueur == mc.player && mc.options.getCameraType().isFirstPerson();
                    if (joueur != null && !yeux && !cache(joueur) && !ClientKurama.mode(id)) {
                        mc.level.addParticle(JAUNE, joueur.getX() + aleat(0.45), joueur.getY() + 0.2 + HASARD.nextDouble() * 1.6,
                                joueur.getZ() + aleat(0.45), 0, 0.02, 0);
                    }
                }
            }
        }

        /**
         * Éveillé, le clic droit lance le kunai ; accroupi, il pose la marque (et il ne fait rien d'autre). Accroupi, le
         * clic gauche lance un kunai à la main, ou emmène à celui déjà lancé (le serveur choisit) ; debout, c'est un coup
         * de kunai normal.
         */
        @SubscribeEvent
        public static void clic(InputEvent.InteractionKeyMappingTriggered event) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null || !ELUS.contains(mc.player.getUUID()) || enScene()) {
                return;
            }
            if (mc.options.keyShift.isDown()) {
                clicPendantAccroupi = true;
            }
            // mode Kurama, accroupi + clic droit : se téléporter là où il regarde
            if (event.isUseItem() && mc.options.keyShift.isDown() && niveau(mc.player.getUUID()) >= 2) {
                event.setCanceled(true);
                event.setSwingHand(false);
                if (System.nanoTime() - dernierClic > 300_000_000L) {
                    dernierClic = System.nanoTime();
                    if (pretEclair < 0) {
                        pretEclair = System.nanoTime() + Minato.RECHARGE_ECLAIR * 50_000_000L;
                    }
                    PacketDistributor.sendToServer(new Minato.Eclair());
                }
                return;
            }
            if (event.isAttack() && mc.options.keyShift.isDown()) {
                event.setCanceled(true);
                event.setSwingHand(false);
                if (!gaucheTenu) {
                    PacketDistributor.sendToServer(new Minato.Jeter());
                }
                gaucheTenu = true;
                return;
            }
            if (!event.isUseItem()) {
                return;
            }
            event.setCanceled(true);
            event.setSwingHand(false);
            if (System.nanoTime() - dernierClic > 300_000_000L) {
                dernierClic = System.nanoTime();
                PacketDistributor.sendToServer(mc.options.keyShift.isDown() ? new Minato.Poser() : new Minato.Lancer());
            }
        }

        /** Pendant un combo ou une Bijuu Dama, les vrais lanceur et cible ne sont pas dessinés : leurs doubles jouent la scène. */
        @SubscribeEvent
        public static void cacher(RenderLivingEvent.Pre<?, ?> event) {
            if (cache(event.getEntity())) {
                event.setCanceled(true);
            }
        }

        /** Mode Kurama : la vue bloquée sur la cible, à chaque image. */
        @SubscribeEvent
        public static void image(net.neoforged.neoforge.client.event.RenderFrameEvent.Pre event) {
            suivre(event.getPartialTick().getGameTimeDeltaPartialTick(false));
        }

        /** Éveillé, on tient le kunai de Minato, quoi qu'on ait vraiment en main (ici à la première personne). */
        @SubscribeEvent(priority = EventPriority.LOWEST)
        public static void main(RenderHandEvent event) {
            Minecraft mc = Minecraft.getInstance();
            ItemStack kunai = kunaiEnMain();
            if (event.getHand() != InteractionHand.MAIN_HAND || mc.player == null || !ELUS.contains(mc.player.getUUID()) || kunai.isEmpty()) {
                return;
            }
            event.setCanceled(true);
            // tenu comme n'importe quel objet (ItemInHandRenderer.renderArmWithItem)
            HumanoidArm bras = mc.player.getMainArm();
            int i = bras == HumanoidArm.RIGHT ? 1 : -1;
            float coup = event.getSwingProgress();
            float racine = Mth.sqrt(coup);
            float s = Mth.sin(racine * Mth.PI);
            PoseStack pose = event.getPoseStack();
            pose.pushPose();
            pose.translate(i * -0.4F * s, 0.2F * Mth.sin(racine * Mth.TWO_PI), -0.2F * Mth.sin(coup * Mth.PI));
            pose.translate(i * 0.56F, -0.52F + event.getEquipProgress() * -0.6F, -0.72F);
            pose.mulPose(Axis.YP.rotationDegrees(i * (45F - 20F * Mth.sin(coup * coup * Mth.PI))));
            pose.mulPose(Axis.ZP.rotationDegrees(i * s * -20F));
            pose.mulPose(Axis.XP.rotationDegrees(s * -80F));
            pose.mulPose(Axis.YP.rotationDegrees(i * -45F));
            mc.getEntityRenderDispatcher().getItemInHandRenderer().renderItem(mc.player, kunai,
                    bras == HumanoidArm.RIGHT ? ItemDisplayContext.FIRST_PERSON_RIGHT_HAND : ItemDisplayContext.FIRST_PERSON_LEFT_HAND,
                    bras == HumanoidArm.LEFT, pose, event.getMultiBufferSource(), event.getPackedLight());
            pose.popPose();
        }

        /** Vu de l'extérieur (et en vue F5) : le temps de dessiner un Minato éveillé, on lui met le kunai dans la main. */
        @SubscribeEvent(priority = EventPriority.LOWEST)
        public static void prendre(RenderPlayerEvent.Pre event) {
            Player p = event.getEntity();
            ItemStack kunai = kunaiEnMain();
            Inventory inv = p.getInventory();
            if (ELUS.contains(p.getUUID()) && !kunai.isEmpty() && !cache(p) && !TENUS.containsKey(p)
                    && inv.selected >= 0 && inv.selected < inv.items.size()) {
                TENUS.put(p, inv.items.set(inv.selected, kunai));
            }
        }

        @SubscribeEvent
        public static void reposer(RenderPlayerEvent.Post event) {
            rendre(event.getEntity());
        }

        @SubscribeEvent
        public static void dessiner(RenderLevelStageEvent event) {
            if ((LAMES.isEmpty() && ENVOLS.isEmpty() && MARQUES.isEmpty() && LANCES.isEmpty() && ELUS.isEmpty())
                    || event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) {
                return;
            }
            Minecraft mc = Minecraft.getInstance();
            Vec3 cam = event.getCamera().getPosition();
            MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
            // une aura jaune très légère autour de ceux qui sont éveillés (pas autour de soi en vue à la première personne)
            float partiel = event.getPartialTick().getGameTimeDeltaPartialTick(false);
            float temps = (mc.level.getGameTime() + partiel) / 20F;
            for (UUID id : ELUS) {
                Player joueur = mc.level.getPlayerByUUID(id);
                if (joueur == null || cache(joueur) || ClientKurama.mode(id) || joueur.isInvisible()
                        || (joueur == mc.player && mc.options.getCameraType().isFirstPerson())) {
                    continue;
                }
                fr.cabane.client.cinema.Effets.aura(event.getPoseStack(), buffers, joueur.getPosition(partiel).subtract(cam), 0.16F, 1F, 0.86F, 0.3F, temps);
            }
            buffers.endBatch();
            // les kunais de marque plantés (visibles par tous) : posé, la pointe en bas ; lancé, enfoncé de biais dans le bloc
            for (Marque m : java.util.stream.Stream.concat(MARQUES.values().stream(), LANCES.values().stream()).toList()) {
                if (m.type() == 1 && ici(m) && m.pos().distanceToSqr(cam) < 96 * 96) {
                    if (m.dir() == null) {
                        dessinerKunai(event.getPoseStack(), buffers, m.pos().add(0, 0.36, 0).subtract(cam), new Vec3(0.05, -1, 0.03));
                    } else {
                        dessinerKunai(event.getPoseStack(), buffers, m.pos().subtract(m.dir().scale(PLANTE)).subtract(cam), m.dir());
                    }
                }
            }
            // les kunais lancés à la main, la pointe dans le sens du vol
            for (Lame l : ENVOLS.values()) {
                Vec3[] pv = envol(l, secondes(l.debut) * 20);
                Vec3 dir = pv[1].normalize();
                dessinerKunai(event.getPoseStack(), buffers, pv[0].subtract(dir.scale(PLANTE)).subtract(cam), dir);
            }
            for (Lame l : LAMES.values()) {
                if (l.plante) {
                    dessinerKunai(event.getPoseStack(), buffers, l.pos.subtract(l.vitesse.normalize().scale(0.33)).subtract(cam), l.vitesse);
                    continue;
                }
                // le même vol que sur le serveur : il avance, et retombe de 0,03 bloc par tick
                double n = Math.min(secondes(l.debut) * 20, 14);
                Vec3 p = l.pos.add(l.vitesse.scale(n)).add(0, -0.015 * n * (n - 1), 0);
                dessinerKunai(event.getPoseStack(), buffers, p.subtract(cam), l.vitesse.add(0, -0.03 * n, 0));
            }
            buffers.endBatch(RenderType.debugQuads());
        }

        private Evenements() {
        }
    }

    private ClientMinato() {
    }
}
