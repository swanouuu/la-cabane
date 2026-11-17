package fr.cabane.client.muscu;

import com.mojang.blaze3d.vertex.PoseStack;
import fr.cabane.Cabane;
import fr.cabane.client.pouvoir.Clavier;
import fr.cabane.muscu.HaltereItem;
import fr.cabane.muscu.Machine;
import fr.cabane.muscu.Muscu;
import fr.cabane.muscu.Poses;
import fr.cabane.muscu.SiegeMuscu;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.renderer.entity.NoopRenderer;
import net.minecraft.core.Direction;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.MovementInputUpdateEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.RenderPlayerEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.joml.Quaternionf;
import org.lwjgl.glfw.GLFW;

/**
 * Ce qu'on voit de la salle de muscu (voir {@link Muscu}) : les petits QTE à l'écran (une touche au hasard qui se vide,
 * ou les touches qui défilent en rythme pour le cardio), le joueur qui bouge sur sa machine (vu par tout le monde), et
 * le rendu des pièces mobiles (dans {@link RenduMachine}). Les touches affichées sont celles que le joueur a choisies.
 */
@EventBusSubscriber(modid = Cabane.MODID, value = Dist.CLIENT)
public final class ClientMuscu {
    private static final Random HASARD = new Random();

    static double maintenant() {
        return System.nanoTime() / 1e9;
    }

    // ================================================================ ce que fait chaque joueur (pour l'animer)

    static final class Etat {
        boolean serie;
        int kg;
        double rep = -10;
        boolean repOk;
        int reps;
        double ph;
        double vu = -1;
        double vitesse = 1;
    }

    private static final Map<Integer, Etat> ETATS = new HashMap<>();

    static Etat etat(int id) {
        return ETATS.computeIfAbsent(id, i -> new Etat());
    }

    public static void anim(Muscu.Anim p) {
        Etat e = etat(p.joueur());
        double t = maintenant();
        switch (p.quoi()) {
            case Muscu.Anim.DEBUT -> {
                e.serie = true;
                e.kg = p.kg();
                e.reps = 0;
                e.vitesse = 1;
            }
            case Muscu.Anim.FIN -> e.serie = false;
            case Muscu.Anim.REP -> {
                e.rep = t;
                e.repOk = true;
                e.reps++;
                e.vitesse = Math.min(1.8, e.vitesse + 0.12);
            }
            case Muscu.Anim.RATE -> {
                e.rep = t;
                e.repOk = false;
                e.vitesse = 0.6;
            }
            default -> {
            }
        }
    }

    /** L'avancement de la répétition en cours du joueur id : 0 au repos, 1 tout en haut. */
    static double k(int id) {
        Etat e = ETATS.get(id);
        if (e == null) {
            return 0;
        }
        double u = (maintenant() - e.rep) / 0.85;
        if (u < 0 || u >= 1) {
            return 0;
        }
        return e.repOk ? Math.sin(Math.PI * u) : 0.4 * Math.sin(Math.PI * u) + 0.03 * Math.sin(u * 60);
    }

    /** La phase du cardio (radians) : elle avance tant que le joueur fait sa série. */
    static double phase(int id, Machine m) {
        Etat e = etat(id);
        double t = maintenant();
        if (e.vu > 0 && e.serie) {
            double dt = Math.min(0.1, t - e.vu);
            e.vitesse += (1 - e.vitesse) * Math.min(1, dt * 0.5);
            e.ph += dt * 2 * Math.PI * m.cadence * e.vitesse;
        }
        e.vu = t;
        return e.ph;
    }

    /** La pose de ce joueur s'il est sur une machine (ou fait des curls), sinon null. */
    static Poses.Pose pose(Player p) {
        if (p.getVehicle() instanceof SiegeMuscu s) {
            Machine m = s.machine();
            if (m == null) {
                return null;
            }
            return Poses.pose(m, k(p.getId()), phase(p.getId(), m));
        }
        Etat e = ETATS.get(p.getId());
        if (e != null && e.serie && e.kg > 0) {
            boolean droitier = p.getMainArm() == HumanoidArm.RIGHT;
            boolean deux = p.getOffhandItem().getItem() instanceof HaltereItem;
            // avec un haltère dans chaque main on alterne ; sinon c'est toujours la main qui le tient
            boolean gauche = deux ? e.reps % 2 == 1 : !droitier;
            return Poses.haltere(k(p.getId()), gauche);
        }
        return null;
    }

    /** Appelé après la pose habituelle du modèle (PlayerModelMixin). */
    public static void poser(PlayerModel<?> m, Player p) {
        Poses.Pose pose = pose(p);
        if (pose == null) {
            return;
        }
        m.leftArm.xRot = (float) -Math.toRadians(pose.gA());
        m.leftArm.yRot = 0;
        m.leftArm.zRot = (float) -Math.toRadians(pose.gB());
        m.rightArm.xRot = (float) -Math.toRadians(pose.dA());
        m.rightArm.yRot = 0;
        m.rightArm.zRot = (float) Math.toRadians(pose.dB());
        if (p.getVehicle() instanceof SiegeMuscu) {
            m.leftLeg.xRot = (float) -Math.toRadians(pose.jgA());
            m.rightLeg.xRot = (float) -Math.toRadians(pose.jdA());
            m.leftLeg.yRot = m.rightLeg.yRot = 0;
            m.leftLeg.zRot = m.rightLeg.zRot = 0;
            m.body.xRot = 0;
            m.leftPants.copyFrom(m.leftLeg);
            m.rightPants.copyFrom(m.rightLeg);
            m.jacket.copyFrom(m.body);
        }
        m.leftSleeve.copyFrom(m.leftArm);
        m.rightSleeve.copyFrom(m.rightArm);
    }

    // ---- le corps : tourné dans le sens de la machine, couché sur le banc, à moitié allongé sur la presse

    private static final Set<Player> PENCHES = new HashSet<>();

    @SubscribeEvent
    public static void avant(RenderPlayerEvent.Pre event) {
        Player p = event.getEntity();
        if (!(p.getVehicle() instanceof SiegeMuscu s) || s.machine() == null) {
            return;
        }
        Direction face = s.face();
        float lacet = Machine.lacet(face);
        p.yBodyRot = p.yBodyRotO = lacet;
        Poses.Pose pose = pose(p);
        if (pose == null) {
            return;
        }
        PoseStack ps = event.getPoseStack();
        ps.pushPose();
        PENCHES.add(p);
        Vec3 d = Machine.versMonde(face, pose.dx(), pose.dy(), pose.dz());
        ps.translate(d.x, d.y, d.z);
        if (pose.bascule() != 0) {
            Vec3 axe = Machine.versMonde(face, 1, 0, 0);
            ps.translate(0, Poses.HANCHE, 0);
            ps.mulPose(new Quaternionf().rotationAxis((float) -Math.toRadians(pose.bascule()), (float) axe.x, 0, (float) axe.z));
            ps.translate(0, -Poses.HANCHE, 0);
        }
    }

    @SubscribeEvent
    public static void apres(RenderPlayerEvent.Post event) {
        if (PENCHES.remove(event.getEntity())) {
            event.getPoseStack().popPose();
        }
    }

    // ================================================================ ma série à moi : les QTE

    private enum Phase { RIEN, COMPTE, JEU, ATTENTE, FIN }

    private record Note(double t, int dir) {
    }

    private static Phase phase = Phase.RIEN;
    private static Machine machine;
    private static int kg;
    private static double debut;
    private static int reps, rates, total;
    private static final List<Note> NOTES = new ArrayList<>();
    private static final List<Integer> JUGEES = new ArrayList<>();
    private static int[] seq = new int[0];
    private static int idx;
    private static boolean attente;
    private static double limite, dureeInv, animFin, attenteDepuis;
    private static String message = "";
    private static boolean messageOk;
    private static double messageFin, flash;
    private static String finTitre = "", finTexte = "";
    private static boolean finOk;
    private static double finJusqua;
    private static int derniere = -1;

    private static boolean enSerie() {
        return phase == Phase.COMPTE || phase == Phase.JEU || phase == Phase.ATTENTE;
    }

    /** Clic droit avec un haltère en main. */
    public static void haltere(int poids) {
        if (!enSerie()) {
            auto = 0;
            demarrer(null, poids);
        }
    }

    private static void demarrer(Machine m, int poids) {
        machine = m;
        kg = poids;
        reps = rates = 0;
        total = m == null ? 8 : m.total;
        debut = maintenant() + 1.6;
        phase = Phase.COMPTE;
        NOTES.clear();
        JUGEES.clear();
        attente = false;
        animFin = 0;
        message = "";
        if (m != null && m.cardio()) {
            for (int i = 0; i < m.total; i++) {
                NOTES.add(new Note(debut + 0.5 + i * m.intervalle, dir(m.motif.charAt(i % m.motif.length()))));
                JUGEES.add(0);
            }
        }
        PacketDistributor.sendToServer(new Muscu.Action(Muscu.Action.DEBUT, poids));
    }

    private static int dir(char c) {
        return switch (c) {
            case 'Z' -> 0;
            case 'Q' -> 1;
            case 'S' -> 2;
            default -> 3;
        };
    }

    private static KeyMapping touche(int dir) {
        var o = Minecraft.getInstance().options;
        return switch (dir) {
            case 0 -> o.keyUp;
            case 1 -> o.keyLeft;
            case 2 -> o.keyDown;
            default -> o.keyRight;
        };
    }

    /** Le nom court de la touche (celle du joueur : Z sur un clavier AZERTY, W en QWERTY). */
    private static String nom(int dir) {
        String n = Clavier.nom(touche(dir));
        return n.length() > 6 ? n.substring(0, 6) : n;
    }

    private static boolean rythme() {
        return machine != null && machine.cardio();
    }

    private static double fenetre() {
        return machine == null ? Machine.fenetreHaltere(kg) : machine.intervalle;
    }

    private static double combo() {
        return machine == null ? Machine.comboHaltere(kg) : machine.combo();
    }

    private static void nouvelleInvite(double t) {
        int n = HASARD.nextDouble() < combo() ? 2 : 1;
        seq = new int[n];
        for (int i = 0; i < n; i++) {
            int c;
            do {
                c = HASARD.nextInt(4);
            } while (c == derniere);
            seq[i] = c;
            derniere = c;
        }
        idx = 0;
        dureeInv = fenetre() * (n == 2 ? 1.5 : 1);
        limite = t + dureeInv;
        attente = true;
    }

    private static void dire(String texte, boolean ok) {
        message = texte;
        messageOk = ok;
        messageFin = maintenant() + 0.7;
    }

    private static void reussi(boolean parfait) {
        reps++;
        dire(parfait ? "PARFAIT" : "BIEN", true);
        PacketDistributor.sendToServer(new Muscu.Action(Muscu.Action.REP, kg));
    }

    private static void rate() {
        rates++;
        flash = maintenant();
        dire("RATÉ  -1 ❤", false);
        PacketDistributor.sendToServer(new Muscu.Action(Muscu.Action.RATE, kg));
    }

    private static void appui(int dir) {
        double t = maintenant();
        if (phase != Phase.JEU) {
            return;
        }
        if (rythme()) {
            for (int i = 0; i < NOTES.size(); i++) {
                Note n = NOTES.get(i);
                if (JUGEES.get(i) == 0 && Math.abs(t - n.t()) <= 0.17) {
                    if (n.dir() == dir) {
                        JUGEES.set(i, 1);
                        reussi(Math.abs(t - n.t()) < 0.07);
                    } else {
                        JUGEES.set(i, 2);
                        rate();
                    }
                    return;
                }
            }
            return;
        }
        if (!attente) {
            return;
        }
        if (dir == seq[idx]) {
            idx++;
            if (idx == seq.length) {
                attente = false;
                animFin = t + 0.85;
                reussi(limite - t > dureeInv * 0.5);
            }
        } else {
            attente = false;
            animFin = t + 0.7;
            rate();
        }
    }

    /** Avance la série (appelé à chaque tick et à chaque image). */
    private static void avancer() {
        Minecraft mc = Minecraft.getInstance();
        Player moi = mc.player;
        double t = maintenant();
        if (moi == null) {
            phase = Phase.RIEN;
            return;
        }
        if (phase == Phase.FIN && t > finJusqua) {
            phase = Phase.RIEN;
        }
        if (!enSerie()) {
            auto = 0;
            return;
        }
        // on est descendu de la machine, ou on a lâché l'haltère : la série s'arrête
        boolean surMachine = moi.getVehicle() instanceof SiegeMuscu;
        boolean tientHaltere = moi.getMainHandItem().getItem() instanceof HaltereItem h && h.kg == kg;
        if (machine != null ? !surMachine : !tientHaltere || moi.isPassenger()) {
            PacketDistributor.sendToServer(new Muscu.Action(Muscu.Action.ABANDON, kg));
            phase = Phase.RIEN;
            return;
        }
        if (phase == Phase.COMPTE) {
            if (t >= debut) {
                phase = Phase.JEU;
                if (!rythme()) {
                    nouvelleInvite(t);
                }
            }
            return;
        }
        if (auto == 1 && phase == Phase.JEU) {
            jouerSeul(t);
        }
        if (phase == Phase.ATTENTE) {
            // le serveur n'a pas répondu (une répétition refusée) : on arrête là
            if (t - attenteDepuis > 2.5) {
                PacketDistributor.sendToServer(new Muscu.Action(Muscu.Action.ABANDON, kg));
                phase = Phase.RIEN;
            }
            return;
        }
        if (rythme()) {
            boolean tout = true;
            for (int i = 0; i < NOTES.size(); i++) {
                if (JUGEES.get(i) == 0) {
                    if (t > NOTES.get(i).t() + 0.17) {
                        JUGEES.set(i, 2);
                        rate();
                    } else {
                        tout = false;
                    }
                }
            }
            if (rates >= 3 || tout) {
                phase = Phase.ATTENTE;
                attenteDepuis = t;
            }
            return;
        }
        if (attente && t > limite) {
            attente = false;
            animFin = t + 0.7;
            rate();
        } else if (!attente && t > animFin) {
            if (rates >= 3 || reps >= total) {
                phase = Phase.ATTENTE;
                attenteDepuis = t;
            } else {
                nouvelleInvite(t);
            }
        }
    }

    // ---- pour les tests (PhotoTour « act muscu ok|rate ») : la série se joue toute seule, réussie ou toute ratée

    private static int auto;

    public static void essai(boolean reussir) {
        Player moi = Minecraft.getInstance().player;
        if (moi == null || enSerie()) {
            return;
        }
        auto = reussir ? 1 : 2;
        if (moi.getVehicle() instanceof SiegeMuscu s && s.machine() != null) {
            demarrer(s.machine(), 0);
        } else if (moi.getMainHandItem().getItem() instanceof HaltereItem h) {
            demarrer(null, h.kg);
        }
    }

    private static void jouerSeul(double t) {
        if (rythme()) {
            for (int i = 0; i < NOTES.size(); i++) {
                if (JUGEES.get(i) == 0 && t >= NOTES.get(i).t() - 0.02) {
                    appui(NOTES.get(i).dir());
                    return;
                }
            }
        } else if (attente && t > limite - dureeInv * 0.55) {
            appui(seq[idx]);
        }
    }

    public static void resultat(Muscu.Resultat r) {
        auto = 0;
        phase = Phase.FIN;
        finOk = r.ok();
        finTitre = r.titre();
        finTexte = r.texte();
        finJusqua = maintenant() + 3.5;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && !r.ok()) {
            mc.player.playSound(SoundEvents.ANVIL_LAND, 0.3F, 0.6F);
        }
    }

    // ---- les touches

    @SubscribeEvent
    public static void touche(InputEvent.Key event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.screen != null || event.getAction() != GLFW.GLFW_PRESS) {
            return;
        }
        var o = mc.options;
        if (phase == Phase.JEU) {
            for (int d = 0; d < 4; d++) {
                if (touche(d).matches(event.getKey(), event.getScanCode())) {
                    appui(d);
                    return;
                }
            }
            return;
        }
        if ((phase == Phase.RIEN || phase == Phase.FIN) && mc.player.getVehicle() instanceof SiegeMuscu s && s.machine() != null
                && o.keyJump.matches(event.getKey(), event.getScanCode())) {
            auto = 0;
            demarrer(s.machine(), 0);
        }
    }

    /** Pendant une série d'haltères, on ne marche pas (les touches servent au QTE). */
    @SubscribeEvent
    public static void immobile(MovementInputUpdateEvent event) {
        if (enSerie()) {
            var in = event.getInput();
            in.forwardImpulse = 0;
            in.leftImpulse = 0;
            in.up = in.down = in.left = in.right = in.jumping = false;
        }
    }

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post event) {
        avancer();
    }

    // ---- l'écran

    @SubscribeEvent
    public static void ecran(RenderGuiEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        avancer();
        if (mc.player == null || mc.options.hideGui) {
            return;
        }
        GuiGraphics g = event.getGuiGraphics();
        Font f = mc.font;
        int w = g.guiWidth(), h = g.guiHeight();
        double t = maintenant();
        int cx = w / 2, cy = (int) (h * 0.66);

        // le flash rouge d'un raté
        double fl = t - flash;
        if (fl < 0.35) {
            int a = (int) (110 * (1 - fl / 0.35));
            int c = (a << 24) | 0xE01B2F;
            g.fillGradient(0, 0, w, 40, c, c & 0x00FFFFFF);
            g.fillGradient(0, h - 40, w, h, c & 0x00FFFFFF, c);
        }

        if (phase == Phase.RIEN && mc.player.getVehicle() instanceof SiegeMuscu s && s.machine() != null) {
            String l1 = s.machine().nom.toUpperCase();
            String l2 = "[" + Clavier.saut() + "] commencer la série  ·  [" + Clavier.accroupi() + "] descendre";
            g.drawCenteredString(f, l1, cx, h - 104, 0xFFFF4A5C);
            g.drawCenteredString(f, l2, cx, h - 92, 0xFFDDDDDD);
            return;
        }
        if (phase == Phase.FIN) {
            gros(g, f, finTitre.toUpperCase(), cx, cy - 20, 2.4F, finOk ? 0xFF3DDC84 : 0xFFFF4A5C);
            g.drawCenteredString(f, finTexte, cx, cy + 6, 0xFFEEEEEE);
            return;
        }
        if (!enSerie()) {
            return;
        }
        String titre = machine != null ? machine.nom : "Haltère " + kg + " kg";
        g.drawCenteredString(f, titre.toUpperCase() + "   " + reps + "/" + total + (rates > 0 ? "   ✖ " + rates + "/3" : ""), cx, cy - 50,
                rates > 0 ? 0xFFFF8A95 : 0xFFDDDDDD);
        if (phase == Phase.COMPTE) {
            double reste = debut - t;
            gros(g, f, reste > 1.07 ? "3" : reste > 0.53 ? "2" : "1", cx, cy - 22, 4F, 0xFFFFFFFF);
            return;
        }
        if (t < messageFin) {
            gros(g, f, message, cx, cy + 34, 1.6F, messageOk ? 0xFF3DDC84 : 0xFFFF4A5C);
        }
        if (rythme()) {
            piste(g, f, cx, cy, t);
        } else if (phase == Phase.JEU) {
            invite(g, f, cx, cy, t);
        }
    }

    private static void gros(GuiGraphics g, Font f, String s, int x, int y, float echelle, int couleur) {
        g.pose().pushPose();
        g.pose().translate(x, y, 0);
        g.pose().scale(echelle, echelle, 1);
        g.drawCenteredString(f, s, 0, 0, couleur);
        g.pose().popPose();
    }

    /** Une touche dessinée comme une touche de clavier. */
    private static void cap(GuiGraphics g, Font f, String s, int x, int y, int taille, int bord, int texte) {
        int l = Math.max(taille, f.width(s) + 10);
        g.fill(x - l / 2 - 2, y - taille / 2 - 2, x + l / 2 + 2, y + taille / 2 + 4, 0xFF000000);
        g.fill(x - l / 2 - 2, y - taille / 2 - 2, x + l / 2 + 2, y + taille / 2 + 2, bord);
        g.fill(x - l / 2, y - taille / 2, x + l / 2, y + taille / 2, 0xFF1B1B21);
        g.drawCenteredString(f, s, x, y - 4, texte);
    }

    private static void invite(GuiGraphics g, Font f, int cx, int cy, double t) {
        int n = seq.length;
        for (int i = 0; i < n; i++) {
            int x = cx + (int) ((i - (n - 1) / 2.0) * 34);
            boolean fait = i < idx;
            cap(g, f, nom(seq[i]), x, cy - 4, 26, fait ? 0xFF3DDC84 : 0xFFF4F1F1, fait ? 0xFF3DDC84 : 0xFFFFFFFF);
        }
        // le temps qui reste : une barre rouge qui se vide
        double u = attente ? Math.max(0, Math.min(1, (limite - t) / dureeInv)) : 0;
        int l = 90;
        g.fill(cx - l / 2 - 1, cy + 16, cx + l / 2 + 1, cy + 22, 0xFF000000);
        g.fill(cx - l / 2, cy + 17, cx - l / 2 + (int) (l * u), cy + 21, u < 0.3 ? 0xFFFF4A5C : 0xFFE01B2F);
    }

    private static void piste(GuiGraphics g, Font f, int cx, int cy, double t) {
        int l = 240, x0 = cx - l / 2, cible = x0 + 26;
        g.fill(x0 - 1, cy - 19, x0 + l + 1, cy + 15, 0xFF33333B);
        g.fill(x0, cy - 18, x0 + l, cy + 14, 0xC0101014);
        g.enableScissor(x0, cy - 18, x0 + l, cy + 14);
        // la cible : un carré rouge
        g.fill(cible - 14, cy - 16, cible + 14, cy - 14, 0xFFE01B2F);
        g.fill(cible - 14, cy + 10, cible + 14, cy + 12, 0xFFE01B2F);
        g.fill(cible - 14, cy - 16, cible - 12, cy + 12, 0xFFE01B2F);
        g.fill(cible + 12, cy - 16, cible + 14, cy + 12, 0xFFE01B2F);
        for (int i = 0; i < NOTES.size(); i++) {
            Note n = NOTES.get(i);
            int x = cible + (int) ((n.t() - t) * 150);
            if (x < x0 - 30 || x > x0 + l + 30) {
                continue;
            }
            int j = JUGEES.get(i);
            int bord = j == 1 ? 0xFF3DDC84 : j == 2 ? 0xFFE01B2F : 0xFFF4F1F1;
            cap(g, f, nom(n.dir()), x, cy - 2, 20, bord, j == 0 ? 0xFFFFFFFF : bord);
        }
        g.disableScissor();
    }

    // ================================================================ les rendus

    @EventBusSubscriber(modid = Cabane.MODID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
    public static final class Enregistrement {
        @SubscribeEvent
        public static void rendus(EntityRenderersEvent.RegisterRenderers event) {
            event.registerEntityRenderer(Muscu.SIEGE.get(), NoopRenderer::new);
            event.registerBlockEntityRenderer(Muscu.MACHINE_BE.get(), RenduMachine::new);
        }
    }

    private ClientMuscu() {
    }
}
