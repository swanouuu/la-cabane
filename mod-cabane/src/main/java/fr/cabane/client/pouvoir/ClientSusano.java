package fr.cabane.client.pouvoir;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.math.Axis;
import fr.cabane.Cabane;
import fr.cabane.pouvoir.Pouvoirs;
import fr.cabane.pouvoir.Pouvoirs.Couleur;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.lwjgl.glfw.GLFW;

/**
 * Le Susano, dessiné chez chaque joueur autour de ceux dont le pouvoir est éveillé (voir {@link Pouvoirs}) : une
 * colonne, une cage de côtes et un grand bras droit de chakra translucide, avec un contour clair. À l'éveil, le joueur
 * porte la main à son œil, puis les côtes jaillissent et le bras se forme os par os. Au niveau 2, le joueur rit, la
 * main toujours sur l'œil, puis tout grandit : la cage s'élargit et monte, une tête casquée sort de la colonne, ses
 * yeux s'allument, et un second bras se forme à gauche. Au repos, les bras flottent sur les côtés, mains ouvertes, et
 * respirent. Clic gauche : un bras balaie le devant (au niveau 2, gauche puis droite), avec un croissant et des traits
 * d'aura. Clic droit : la main va chercher sa proie et se referme dessus ; au niveau 2, un second clic droit la lève et
 * l'écrase au sol (onde de choc, l'écran tremble). Quand le pouvoir s'endort, tout se disperse en plumes noires.
 * Les temps suivent l'horloge réelle.
 */
public final class ClientSusano {
    public static final KeyMapping EVEIL = new KeyMapping("key.cabane.eveil", GLFW.GLFW_KEY_G, "key.categories.cabane");
    /** La main à l'œil, puis les côtes jaillissent ; le bras finit de se former à FORME. */
    private static final double JAILLISSEMENT = 1.4;
    private static final double FORME = 2.6;
    /** Niveau 2 : le rire, puis tout grandit ; la tête sort, ses yeux s'allument ; le bras gauche est formé à FORME_2. */
    private static final double MONTEE = 1.25;
    private static final double YEUX = 2.15;
    private static final double FORME_2 = 2.7;
    private static final double DISPARITION = 0.7;
    private static final double DUREE_COUP = 0.6;
    private static final double DUREE_ECRASE = 0.85;
    private static final double DUREE_PRISE = 0.45;
    private static final RandomSource HASARD = RandomSource.create();

    private static final class Susano {
        Couleur couleur = Couleur.VIOLET;
        /** Sa liste blanche (« susano » ou « susano2 ») et son niveau en ce moment. */
        String pouvoir = "susano";
        int niveau = 1;
        long debut;
        /** Quand il est passé au niveau 2 (-1 sinon). */
        long debut2 = -1;
        long fin = -1;
        /** Le dernier geste (frappe, charge, jet, écrasement), de quel bras, et quand il a commencé. */
        int geste = Pouvoirs.FRAPPE;
        int cote;
        long coup = -1;
        /** La main tient quelqu'un, depuis ce moment-là. */
        boolean tient;
        long prise = -1;
        /** La dernière prise au niveau 2 (elle se recharge). */
        long dernierePrise = -1;
        double tPrecedent = -1;
        double t2Precedent = -1;
        /** Le centre de chaque os dans le monde, à la dernière image (pour les particules). */
        final List<Vec3> os = new ArrayList<>();
    }

    private static final Map<UUID, Susano> SUSANOS = new HashMap<>();
    private static long dernierClic;
    private static long derniereCharge;
    private static boolean accroupiAvant;
    /** La charge se recharge comme sur le serveur (en nanosecondes). */
    private static final long RECHARGE_CHARGE = Pouvoirs.RECHARGE_CHARGE * 50_000_000L;
    /** L'écran tremble quand un écrasement frappe le sol près de nous : à partir de ce moment-là. */
    private static long secousse = -1;

    private static double secondes(long depuis) {
        return (System.nanoTime() - depuis) / 1e9;
    }

    private static double lisse(double x) {
        x = Mth.clamp(x, 0, 1);
        return x * x * (3 - 2 * x);
    }

    /** Monte un peu trop haut, puis revient (pour ce qui jaillit). */
    private static float rebond(double e) {
        if (e <= 0) {
            return 0;
        }
        return e >= 1 ? 1 : (float) (1 + 2.70158 * Math.pow(e - 1, 3) + 1.70158 * Math.pow(e - 1, 2));
    }

    private static boolean eveille(Susano s) {
        return s != null && s.fin < 0;
    }

    /** Le temps depuis le passage au niveau 2 (négatif s'il n'y est pas). */
    private static double t2(Susano s) {
        return s.niveau >= 2 && s.debut2 >= 0 ? secondes(s.debut2) : -1;
    }

    /** De 0 (niveau 1) à 1 (niveau 2 tout formé) : tout le squelette grandit avec. */
    private static float grandeur(Susano s) {
        return rebond((t2(s) - MONTEE) / 0.45);
    }

    // ================================================================ ce que dit le serveur

    public static void recevoir(Pouvoirs.Etat p) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return;
        }
        // Hachibi et la marque maudite ont leur propre dessin
        if (p.pouvoir().equals(fr.cabane.pouvoir.Hachibi.NOM)) {
            SUSANOS.remove(p.joueur());
            ClientMaudit.oublier(p.joueur());
            ClientHachibi.recevoir(p);
            return;
        }
        if (fr.cabane.pouvoir.Maudit.est(p.pouvoir())) {
            SUSANOS.remove(p.joueur());
            ClientHachibi.oublier(p.joueur());
            ClientMaudit.recevoir(p);
            return;
        }
        ClientHachibi.oublier(p.joueur());
        ClientMaudit.oublier(p.joueur());
        Couleur couleur = Couleur.values()[Mth.clamp(p.couleur(), 0, Couleur.values().length - 1)];
        Susano s = SUSANOS.get(p.joueur());
        Player joueur = mc.level.getPlayerByUUID(p.joueur());
        long maintenant = System.nanoTime();
        if (p.eveille()) {
            if (!eveille(s)) {
                s = new Susano();
                s.debut = maintenant - (p.annonce() ? 0 : (long) (FORME * 1e9));
                s.tPrecedent = p.annonce() ? -1 : FORME;
                s.niveau = 1;
                SUSANOS.put(p.joueur(), s);
                if (p.annonce() && joueur != null) {
                    son(joueur, SoundEvents.EVOKER_PREPARE_SUMMON, 0.8F, 1.3F);
                }
            }
            if (p.niveau() >= 2 && s.niveau < 2) {
                s.niveau = 2;
                s.debut2 = maintenant - (p.annonce() ? 0 : 10_000_000_000L);
                s.t2Precedent = p.annonce() ? -1 : 10;
            } else if (p.niveau() < 2) {
                s.niveau = 1;
                s.debut2 = -1;
            }
            s.couleur = couleur;
            s.pouvoir = p.pouvoir();
        } else if (s != null) {
            if (!p.annonce()) {
                SUSANOS.remove(p.joueur());
                return;
            }
            s.fin = maintenant;
            if (joueur != null) {
                son(joueur, SoundEvents.BEACON_DEACTIVATE, 1F, 0.7F);
                son(joueur, SoundEvents.PHANTOM_FLAP, 1F, 0.8F);
            }
            // il se disperse en plumes noires (et en poussière de sa couleur)
            DustParticleOptions poussiere = poussiere(s.couleur, 1.4F);
            for (Vec3 o : s.os) {
                if (HASARD.nextInt(3) == 0) {
                    mc.level.addParticle(ParticleTypes.SQUID_INK, o.x + aleat(0.3), o.y + aleat(0.3), o.z + aleat(0.3), aleat(0.15), 0.05 + HASARD.nextDouble() * 0.12, aleat(0.15));
                }
                mc.level.addParticle(poussiere, o.x, o.y, o.z, 0, 0.05, 0);
            }
        }
    }

    public static void geste(Pouvoirs.Geste p) {
        if (p.geste() >= fr.cabane.pouvoir.Hachibi.GESTE_GRIFFE) {
            ClientHachibi.geste(p);
            return;
        }
        Susano s = SUSANOS.get(p.joueur());
        Minecraft mc = Minecraft.getInstance();
        if (!eveille(s) || mc.level == null) {
            return;
        }
        Player joueur = mc.level.getPlayerByUUID(p.joueur());
        long maintenant = System.nanoTime();
        if (p.geste() == Pouvoirs.PRISE) {
            s.tient = p.cible() >= 0;
            s.prise = s.tient ? maintenant : -1;
            if (s.tient && s.niveau >= 2) {
                s.dernierePrise = maintenant;
            }
            if (s.tient && joueur != null) {
                son(joueur, SoundEvents.ENDER_DRAGON_FLAP, 0.7F, 1.4F);
            }
            return;
        }
        s.geste = p.geste();
        s.coup = maintenant;
        if (p.geste() == Pouvoirs.FRAPPE) {
            s.cote = p.cible() == 1 ? 1 : 0;
        }
        if (p.geste() == Pouvoirs.JET) {
            s.tient = false;
        }
        if (p.geste() == Pouvoirs.ECRASE) {
            s.tient = false;
            // l'écran tremble chez ceux qui sont assez près quand ça touche le sol
            if (joueur != null && mc.player != null && mc.player.distanceTo(joueur) < 18) {
                secousse = maintenant + (long) (Pouvoirs.IMPACT * 50_000_000L);
            }
        }
        if (joueur == null) {
            return;
        }
        boolean grand = s.niveau >= 2;
        switch (p.geste()) {
            case Pouvoirs.CHARGE -> {
                son(joueur, SoundEvents.ENDER_DRAGON_FLAP, 1F, 1.4F);
                son(joueur, SoundEvents.PLAYER_ATTACK_KNOCKBACK, 1F, 0.6F);
                son(joueur, SoundEvents.BLAZE_SHOOT, 0.6F, 0.8F);
            }
            case Pouvoirs.JET -> {
                son(joueur, SoundEvents.PLAYER_ATTACK_STRONG, 1F, 0.5F);
                son(joueur, SoundEvents.ENDER_DRAGON_FLAP, 0.8F, 1.1F);
            }
            case Pouvoirs.ECRASE -> {
                son(joueur, SoundEvents.ENDER_DRAGON_FLAP, 1F, 0.6F);
                son(joueur, SoundEvents.EVOKER_PREPARE_ATTACK, 0.8F, 0.6F);
            }
            default -> {
                son(joueur, SoundEvents.PLAYER_ATTACK_SWEEP, 1F, grand ? 0.45F : 0.55F);
                son(joueur, SoundEvents.ENDER_DRAGON_FLAP, 0.8F, grand ? 1F : 1.3F);
                son(joueur, SoundEvents.BLAZE_SHOOT, 0.5F, grand ? 0.55F : 0.7F);
            }
        }
    }

    /** Combien de temps dure chaque geste, en secondes. */
    private static double duree(int geste) {
        return geste == Pouvoirs.CHARGE ? 0.8 : geste == Pouvoirs.ECRASE ? DUREE_ECRASE : DUREE_COUP;
    }

    private static void son(Player joueur, SoundEvent son, float volume, float hauteur) {
        joueur.level().playLocalSound(joueur.getX(), joueur.getY() + 1, joueur.getZ(), son, SoundSource.PLAYERS, volume, hauteur, false);
    }

    private static double aleat(double etendue) {
        return (HASARD.nextDouble() - 0.5) * 2 * etendue;
    }

    private static DustParticleOptions poussiere(Couleur c, float taille) {
        int t = c == Couleur.NOIR ? 0x18181C : c.corps;
        return new DustParticleOptions(new Vector3f((t >> 16 & 255) / 255F, (t >> 8 & 255) / 255F, (t & 255) / 255F), taille);
    }

    // ================================================================ le joueur lui-même (appelé par PlayerModelMixin)

    /**
     * Pendant l'éveil, le bras droit du joueur monte devant son œil, puis redescend quand les côtes jaillissent. Au
     * passage au niveau 2, il garde la main sur l'œil et rit, la tête renversée en arrière, l'autre bras ouvert.
     */
    public static void poserBras(PlayerModel<?> modele, Player joueur) {
        Susano s = SUSANOS.get(joueur.getUUID());
        if (!eveille(s)) {
            return;
        }
        double t = secondes(s.debut);
        double t2 = t2(s);
        float k = (float) (lisse(t / 0.35) * (1 - lisse((t - JAILLISSEMENT - 0.1) / 0.4)));
        float rire = t2 < 0 ? 0 : (float) (lisse(t2 / 0.3) * (1 - lisse((t2 - MONTEE - 0.05) / 0.35)));
        k = Math.max(k, rire);
        if (k <= 0) {
            return;
        }
        modele.rightArm.xRot = Mth.lerp(k, modele.rightArm.xRot, -2.35F);
        modele.rightArm.yRot = Mth.lerp(k, modele.rightArm.yRot, -0.55F);
        modele.rightArm.zRot = Mth.lerp(k, modele.rightArm.zRot, 0.1F);
        modele.rightSleeve.copyFrom(modele.rightArm);
        if (rire > 0) {
            modele.head.xRot = Mth.lerp(rire, modele.head.xRot, -0.55F + Mth.sin((float) t2 * 28) * 0.09F);
            modele.hat.copyFrom(modele.head);
            modele.leftArm.xRot = Mth.lerp(rire, modele.leftArm.xRot, 0.15F);
            modele.leftArm.zRot = Mth.lerp(rire, modele.leftArm.zRot, -0.45F);
            modele.leftSleeve.copyFrom(modele.leftArm);
        }
    }

    // ================================================================ les poses des bras

    /** Les angles d'un bras, en degrés (le bras gauche est le droit vu dans un miroir). */
    private record Pose(float epauleLacet, float epauleTangage, float coude, float poignet, float doigts) {
        static final Pose REPOS = new Pose(-68, 34, 52, -6, 14);
        static final Pose REPOS_2 = new Pose(-72, 24, 58, -6, 18);
        static final Pose ARME = new Pose(-118, 6, 8, -4, 55);
        static final Pose FRAPPE = new Pose(68, 14, 0, 0, 50);
        /** La main tendue vers sa proie, grande ouverte, puis refermée dessus. */
        static final Pose ATTEINDRE = new Pose(-6, 8, 4, -12, -18);
        static final Pose TIENT = new Pose(-4, 10, 8, 8, 92);
        static final Pose TIENT_2 = new Pose(0, 20, 8, 8, 92);
        /** Le bras ramené en arrière et levé, puis lancé droit devant. */
        static final Pose ELAN = new Pose(-62, -22, 40, -10, 75);
        static final Pose LANCER = new Pose(-6, 8, 0, 0, 0);
        /** L'écrasement : la prise levée bien haut, puis abattue. */
        static final Pose LEVER = new Pose(-18, -58, 22, -12, 92);
        static final Pose ABATTRE = new Pose(-8, 62, 4, 22, 92);
        /** Le piqué : le bras tendu droit devant, la main en lance. */
        static final Pose PIQUE = new Pose(-8, 4, 0, 0, 2);

        Pose vers(Pose b, double k) {
            float f = (float) lisse(k);
            return new Pose(Mth.lerp(f, epauleLacet, b.epauleLacet), Mth.lerp(f, epauleTangage, b.epauleTangage),
                    Mth.lerp(f, coude, b.coude), Mth.lerp(f, poignet, b.poignet), Mth.lerp(f, doigts, b.doigts));
        }

        Pose plus(float lacet, float tangage, float doigts) {
            return new Pose(epauleLacet + lacet, epauleTangage + tangage, coude, poignet, this.doigts + doigts);
        }
    }

    /** Le repos, qui respire (le bras gauche respire en décalé). */
    private static Pose repos(Susano s, double t, boolean gauche) {
        double phase = gauche ? 1.7 : 0;
        Pose base = Pose.REPOS.vers(Pose.REPOS_2, grandeur(s));
        return base.plus((float) Math.sin(t * 1.3 + phase) * 4, (float) Math.sin(t * 1.1 + phase) * 3, (float) Math.sin(t * 2 + phase) * 6);
    }

    private static Pose poseDroite(Susano s, double t) {
        Pose repos = repos(s, t, false);
        // en se formant, la main sort fermée et s'ouvre
        if (t < FORME + 0.4) {
            repos = new Pose(repos.epauleLacet, repos.epauleTangage, repos.coude, repos.poignet,
                    Mth.lerp((float) lisse((t - FORME + 0.6) / 1.0), 85, repos.doigts));
        }
        Pose tient = (s.niveau >= 2 ? Pose.TIENT_2 : Pose.TIENT).plus(0, (float) Math.sin(t * 1.6) * 2, (float) Math.sin(t * 9) * 4);
        if (s.tient) {
            // la main part chercher sa proie, grande ouverte, puis se referme dessus en serrant
            double kp = s.prise < 0 ? 1 : secondes(s.prise) / DUREE_PRISE;
            Pose atteindre = Pose.ATTEINDRE.plus(0, s.niveau >= 2 ? 10 : 0, 0);
            repos = kp < 0.45 ? repos.vers(atteindre, kp / 0.45) : kp < 1 ? atteindre.vers(tient, (kp - 0.45) / 0.55) : tient;
        }
        if (s.coup < 0) {
            return repos;
        }
        double k = secondes(s.coup) / duree(s.geste);
        if (k >= 1) {
            return repos;
        }
        return switch (s.geste) {
            case Pouvoirs.CHARGE -> k < 0.15 ? repos.vers(Pose.PIQUE, k / 0.15) : k < 0.65 ? Pose.PIQUE : Pose.PIQUE.vers(repos, (k - 0.65) / 0.35);
            case Pouvoirs.JET -> k < 0.2 ? tient.vers(Pose.ELAN, k / 0.2) : k < 0.45 ? Pose.ELAN.vers(Pose.LANCER, (k - 0.2) / 0.25)
                    : Pose.LANCER.vers(repos, (k - 0.45) / 0.55);
            case Pouvoirs.ECRASE -> k < 0.38 ? tient.vers(Pose.LEVER, k / 0.38) : k < 0.48 ? Pose.LEVER.vers(Pose.ABATTRE, (k - 0.38) / 0.1)
                    : k < 0.75 ? Pose.ABATTRE : Pose.ABATTRE.vers(repos, (k - 0.75) / 0.25);
            default -> s.cote != 0 ? repos : frappe(repos, k);
        };
    }

    private static Pose poseGauche(Susano s, double t) {
        Pose repos = repos(s, t, true);
        double t2 = t2(s);
        // en se formant, la main gauche sort fermée et s'ouvre
        if (t2 >= 0 && t2 < FORME_2 + 0.4) {
            repos = new Pose(repos.epauleLacet, repos.epauleTangage, repos.coude, repos.poignet,
                    Mth.lerp((float) lisse((t2 - FORME_2 + 0.6) / 1.0), 85, repos.doigts));
        }
        if (s.coup < 0) {
            return repos;
        }
        double k = secondes(s.coup) / duree(s.geste);
        if (k >= 1) {
            return repos;
        }
        return switch (s.geste) {
            case Pouvoirs.CHARGE -> k < 0.15 ? repos.vers(Pose.PIQUE, k / 0.15) : k < 0.65 ? Pose.PIQUE : Pose.PIQUE.vers(repos, (k - 0.65) / 0.35);
            case Pouvoirs.FRAPPE -> s.cote != 1 ? repos : frappe(repos, k);
            default -> repos;
        };
    }

    private static Pose frappe(Pose repos, double k) {
        return k < 0.18 ? repos.vers(Pose.ARME, k / 0.18) : k < 0.45 ? Pose.ARME.vers(Pose.FRAPPE, (k - 0.18) / 0.27)
                : Pose.FRAPPE.vers(repos, (k - 0.45) / 0.55);
    }

    // ================================================================ le squelette

    private static final int BRAS = 0;
    private static final int CAGE = 1;
    private static final int OEIL = 2;
    private static final int SOMBRE = 3;

    /** Un os : une boîte, dans son repère ; {@code force} de 0 à 1 ; {@code genre} : bras, cage, œil ou partie sombre. */
    private record Os(PoseStack.Pose repere, float x0, float y0, float z0, float x1, float y1, float z1, float force, int genre) {
    }

    private static final Vector3f AVANT = new Vector3f(0, 0, 1);

    /** Ajoute une boîte (si elle est assez apparue). */
    private static void boite(List<Os> liste, PoseStack ps, float x0, float y0, float z0, float x1, float y1, float z1, float force, int genre) {
        if (force > 0.01F) {
            liste.add(new Os(ps.last().copy(), x0, y0, z0, x1, y1, z1, force, genre));
        }
    }

    /** Un os tendu entre deux points (largeur à plat, hauteur à la verticale), qui pousse de a vers b. */
    private static void segment(List<Os> liste, PoseStack ps, Vector3f a, Vector3f b, float largeur, float hauteur, float pousse, float force, int genre) {
        Vector3f d = new Vector3f(b).sub(a);
        float longueur = d.length() * pousse;
        if (longueur < 0.01F) {
            return;
        }
        ps.pushPose();
        ps.translate(a.x, a.y, a.z);
        ps.mulPose(new Quaternionf().rotationTo(AVANT, d.normalize()));
        boite(liste, ps, -largeur / 2, -hauteur / 2, 0, largeur / 2, hauteur / 2, longueur, force, genre);
        ps.popPose();
    }

    /** Une partie de bras qui apparaît à son tour : {@code x} est le temps depuis le début de la pousse, {@code ordre} de 0 (l'épaule) à 1 (le bout des doigts). */
    private static float pousse(double x, double ordre, double etalement) {
        return (float) lisse((x - ordre * etalement) / 0.22);
    }

    /**
     * Tout le squelette, dans le repère du joueur (les pieds à l'origine, il regarde vers +Z, sa droite est en -X).
     * {@code g} va de 0 (niveau 1) à 1 (niveau 2) ; la tête suit le regard du joueur.
     */
    private static void squelette(List<Os> liste, PoseStack ps, Susano s, double t, float lacetTete, float tangageTete) {
        // les côtes jaillissent : elles s'ouvrent trop loin, puis reviennent
        double e = (t - JAILLISSEMENT) / 0.35;
        if (e <= 0) {
            return;
        }
        float ouverture = rebond(e);
        float force = (float) lisse(e * 2);
        float g = grandeur(s);
        double t2 = t2(s);

        // la colonne
        float haut = Mth.lerp(g, 2.6F, 3.7F);
        float v = Mth.lerp(g, 0.13F, 0.19F);
        for (float y = 0.75F; y < haut; y += Mth.lerp(g, 0.3F, 0.36F)) {
            boite(liste, ps, -v, y - v * 0.85F, -0.56F - v, v, y + v * 0.85F, -0.56F + v, force, CAGE);
        }
        // les côtes, de chaque côté, de la colonne jusque devant (ouvertes devant) ; deux de plus au niveau 2
        for (int n = 0; n < 6; n++) {
            float forceCote = n < 4 ? force : force * Math.min(1, g);
            if (forceCote <= 0.01F) {
                continue;
            }
            float h = Mth.lerp(g, n < 4 ? 1.05F + n * 0.35F : 2.1F, 0.95F + n * 0.43F);
            boolean milieu = n >= 1 && n <= (g > 0.5F ? 4 : 2);
            float rayon = Mth.lerp(g, 1.0F + (milieu ? 0.14F : 0), 1.45F + (milieu ? 0.18F : 0)) * ouverture;
            float largeur = Mth.lerp(g, 0.26F, 0.44F);
            float epaisseur = Mth.lerp(g, 0.19F, 0.3F);
            for (int cote = -1; cote <= 1; cote += 2) {
                Vector3f precedent = null;
                for (int i = 0; i <= 4; i++) {
                    float phi = Mth.lerp(i / 4F, 168, 38) * Mth.DEG_TO_RAD;
                    Vector3f p = new Vector3f(cote * Mth.sin(phi) * rayon, h - 0.18F * (1 - phi / Mth.PI), Mth.cos(phi) * rayon - 0.1F);
                    if (precedent != null) {
                        segment(liste, ps, precedent, p, largeur, epaisseur, 1, forceCote, CAGE);
                    }
                    precedent = p;
                }
            }
        }

        // les épaules : la droite, puis la gauche au niveau 2
        float echelleBras = Mth.lerp(g, 1F, 1.35F);
        Vector3f epaule = new Vector3f(Mth.lerp(g, -1.15F, -1.65F), Mth.lerp(g, 2.3F, 3.2F), -0.25F);
        Vector3f cou = new Vector3f(0, haut - 0.1F, -0.56F);
        double poussee = t - JAILLISSEMENT - 0.25;
        segment(liste, ps, cou, epaule, 0.24F * echelleBras, 0.2F * echelleBras, pousse(poussee, 0, 0.85), 1, CAGE);
        bras(liste, ps, poseDroite(s, t), epaule, echelleBras, poussee, 0.85);
        if (t2 >= 0) {
            double pousseeGauche = t2 - MONTEE - 0.5;
            ps.pushPose();
            ps.scale(-1, 1, 1);
            segment(liste, ps, cou, epaule, 0.24F * echelleBras, 0.2F * echelleBras, pousse(pousseeGauche, 0, 0.7), 1, CAGE);
            bras(liste, ps, poseGauche(s, t), epaule, echelleBras, pousseeGauche, 0.7);
            ps.popPose();
            // la tête sort de la colonne, puis ses yeux s'allument
            float tete = (float) lisse((t2 - 1.55) / 0.5);
            if (tete > 0.01F) {
                tete(liste, ps, new Vector3f(0, haut + 0.45F + 0.8F * tete, -0.3F), tete, lacetTete, tangageTete, t2);
            }
        }
    }

    /** Un bras : l'épaule, le bras, l'avant-bras, la main (le bras gauche passe par le miroir). */
    private static void bras(List<Os> liste, PoseStack ps, Pose pose, Vector3f epaule, float echelle, double poussee, double etalement) {
        float a1 = pousse(poussee, 0.05, etalement);
        if (a1 <= 0) {
            return;
        }
        ps.pushPose();
        ps.translate(epaule.x, epaule.y, epaule.z);
        ps.scale(echelle, echelle, echelle);
        boite(liste, ps, -0.3F * a1, -0.3F * a1, -0.3F * a1, 0.3F * a1, 0.3F * a1, 0.3F * a1, 1, BRAS);
        ps.mulPose(Axis.YP.rotationDegrees(pose.epauleLacet()));
        ps.mulPose(Axis.XP.rotationDegrees(pose.epauleTangage()));
        boite(liste, ps, -0.22F, -0.22F, 0, 0.22F, 0.22F, 1.25F * a1, 1, BRAS);
        float a2 = pousse(poussee, 0.3, etalement);
        if (a2 > 0) {
            ps.translate(0, 0, 1.25F * a1);
            boite(liste, ps, -0.25F * a2, -0.25F * a2, -0.25F * a2, 0.25F * a2, 0.25F * a2, 0.25F * a2, 1, BRAS);
            ps.mulPose(Axis.YP.rotationDegrees(pose.coude()));
            boite(liste, ps, -0.19F, -0.19F, 0, 0.19F, 0.19F, 1.2F * a2, 1, BRAS);
            float a3 = pousse(poussee, 0.55, etalement);
            if (a3 > 0) {
                ps.translate(0, 0, 1.2F * a2);
                ps.mulPose(Axis.XP.rotationDegrees(pose.poignet()));
                ps.scale(a3, a3, a3);
                main(liste, ps, pose, poussee, etalement);
            }
        }
        ps.popPose();
    }

    /**
     * La main, paume vers le sol : la paume, quatre doigts de deux phalanges, et le pouce vers l'avant. Quand elle se
     * ferme, les doigts s'enroulent dessous et le pouce vient par-dessus eux : un vrai poing autour de la prise.
     */
    private static void main(List<Os> liste, PoseStack ps, Pose pose, double poussee, double etalement) {
        boite(liste, ps, -0.5F, -0.13F, 0, 0.5F, 0.13F, 0.85F, 1, BRAS);
        float doigts = pose.doigts();
        for (int i = 0; i < 4; i++) {
            float x = -0.37F + i * 0.245F;
            float longueur = i == 0 ? 0.36F : i == 3 ? 0.42F : 0.48F;
            float apparition = pousse(poussee, 0.75 + i * 0.05, etalement);
            if (apparition <= 0) {
                continue;
            }
            ps.pushPose();
            ps.translate(x, 0, 0.85F);
            ps.mulPose(Axis.XP.rotationDegrees(doigts));
            boite(liste, ps, -0.1F, -0.1F, 0, 0.1F, 0.1F, longueur * apparition, 1, BRAS);
            ps.translate(0, 0, longueur * apparition);
            ps.mulPose(Axis.XP.rotationDegrees(doigts * 1.3F));
            boite(liste, ps, -0.09F, -0.09F, 0, 0.09F, 0.09F, longueur * 0.85F * apparition, 1, BRAS);
            ps.popPose();
        }
        float pouce = pousse(poussee, 0.95, etalement);
        if (pouce > 0) {
            float ferme = Mth.clamp(doigts / 92F, 0, 1);
            ps.pushPose();
            ps.translate(0.5F, -0.05F * ferme, 0.2F);
            ps.mulPose(Axis.YP.rotationDegrees(45 - 40 * ferme));
            ps.mulPose(Axis.XP.rotationDegrees(doigts * 0.75F));
            boite(liste, ps, -0.11F, -0.11F, 0, 0.11F, 0.11F, 0.42F * pouce, 1, BRAS);
            ps.translate(0, 0, 0.42F * pouce);
            ps.mulPose(Axis.YP.rotationDegrees(-20 - 50 * ferme));
            boite(liste, ps, -0.1F, -0.1F, 0, 0.1F, 0.1F, 0.34F * pouce, 1, BRAS);
            ps.popPose();
        }
    }

    /**
     * La tête du niveau 2 : un crâne de squelette. Le dôme, deux grandes orbites où brillent les yeux, le trou du nez,
     * les pommettes, la mâchoire du haut avec ses dents, et la mâchoire du bas, à part, qui s'ouvre et se ferme
     * doucement (et s'ouvre grand quand les yeux s'allument). Deux vertèbres font le cou.
     */
    private static void tete(List<Os> liste, PoseStack ps, Vector3f centre, float apparition, float lacet, float tangage, double t2) {
        ps.pushPose();
        ps.translate(centre.x, centre.y, centre.z);
        ps.mulPose(Axis.YP.rotationDegrees(-lacet));
        ps.mulPose(Axis.XP.rotationDegrees(tangage));
        ps.scale(apparition, apparition, apparition);
        float f = 1;
        // le cou : deux vertèbres
        boite(liste, ps, -0.24F, -1.42F, -0.42F, 0.24F, -1.18F, -0.02F, f, CAGE);
        boite(liste, ps, -0.22F, -1.12F, -0.4F, 0.22F, -0.88F, -0.04F, f, CAGE);
        // le dôme du crâne, et son sommet plus étroit
        boite(liste, ps, -0.7F, -0.05F, -0.7F, 0.7F, 0.92F, 0.58F, f, CAGE);
        boite(liste, ps, -0.58F, 0.92F, -0.6F, 0.58F, 1.1F, 0.46F, f, CAGE);
        // l'arcade, fine, au-dessus des orbites
        boite(liste, ps, -0.72F, 0.16F, 0.54F, 0.72F, 0.3F, 0.68F, f, CAGE);
        // les pommettes, et entre elles la mâchoire du haut, plus étroite
        boite(liste, ps, -0.72F, -0.46F, -0.1F, -0.5F, -0.2F, 0.62F, f, CAGE);
        boite(liste, ps, 0.5F, -0.46F, -0.1F, 0.72F, -0.2F, 0.62F, f, CAGE);
        boite(liste, ps, -0.5F, -0.62F, -0.3F, 0.5F, -0.2F, 0.6F, f, CAGE);
        // les orbites, grandes et sombres, et le trou du nez
        boite(liste, ps, -0.56F, -0.2F, 0.56F, -0.1F, 0.2F, 0.62F, f, SOMBRE);
        boite(liste, ps, 0.1F, -0.2F, 0.56F, 0.56F, 0.2F, 0.62F, f, SOMBRE);
        boite(liste, ps, -0.1F, -0.44F, 0.58F, 0.1F, -0.24F, 0.64F, f, SOMBRE);
        // les dents du haut
        for (int i = 0; i < 4; i++) {
            float x = -0.36F + i * 0.24F;
            boite(liste, ps, x - 0.09F, -0.72F, 0.48F, x + 0.09F, -0.62F, 0.6F, f, CAGE);
        }
        // les yeux s'allument d'un coup, puis brillent, au fond des orbites
        float yeux = (float) lisse((t2 - YEUX) / 0.12);
        boite(liste, ps, -0.46F, -0.11F, 0.58F, -0.2F, 0.11F, 0.66F, Math.max(0.25F, yeux), yeux > 0.5F ? OEIL : SOMBRE);
        boite(liste, ps, 0.2F, -0.11F, 0.58F, 0.46F, 0.11F, 0.66F, Math.max(0.25F, yeux), yeux > 0.5F ? OEIL : SOMBRE);
        // la mâchoire du bas, accrochée sous les pommettes : elle respire, et s'ouvre en grand quand les yeux s'allument
        float cri = (float) (lisse((t2 - YEUX + 0.05) / 0.15) * (1 - lisse((t2 - YEUX - 0.6) / 0.4)));
        float ouverture = 6 + (float) Math.sin(t2 * 2.2) * 4 + cri * 30;
        ps.pushPose();
        ps.translate(0, -0.6F, -0.25F);
        ps.mulPose(Axis.XP.rotationDegrees(ouverture));
        boite(liste, ps, -0.5F, -0.06F, -0.1F, -0.36F, 0.2F, 0.3F, f, CAGE);
        boite(liste, ps, 0.36F, -0.06F, -0.1F, 0.5F, 0.2F, 0.3F, f, CAGE);
        boite(liste, ps, -0.48F, -0.34F, 0.0F, 0.48F, -0.12F, 0.86F, f, CAGE);
        for (int i = 0; i < 4; i++) {
            float x = -0.36F + i * 0.24F;
            boite(liste, ps, x - 0.08F, -0.12F, 0.72F, x + 0.08F, -0.04F, 0.84F, f, CAGE);
        }
        ps.popPose();
        ps.popPose();
    }

    // ================================================================ le dessin

    private static void dessiner(PoseStack ps, Vec3 camera, float partiel) {
        Minecraft mc = Minecraft.getInstance();
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        for (Map.Entry<UUID, Susano> entree : SUSANOS.entrySet()) {
            Player joueur = mc.level.getPlayerByUUID(entree.getKey());
            Susano s = entree.getValue();
            s.os.clear();
            if (joueur == null || joueur.isInvisible()) {
                continue;
            }
            double t = secondes(s.debut);
            double t2 = t2(s);
            float g = grandeur(s);
            float disparition = s.fin < 0 ? 0 : (float) lisse(secondes(s.fin) / DISPARITION);
            float eclat = t < JAILLISSEMENT ? 0 : (float) Math.exp(-(t - JAILLISSEMENT) * 4);
            if (t2 >= MONTEE) {
                eclat = Math.max(eclat, (float) Math.exp(-(t2 - MONTEE) * 3));
            }
            if (t2 >= YEUX) {
                eclat = Math.max(eclat, 0.6F * (float) Math.exp(-(t2 - YEUX) * 5));
            }
            boolean premierePersonne = joueur == mc.player && mc.options.getCameraType().isFirstPerson();
            float corpsLacet = Mth.rotLerp(partiel, joueur.yBodyRotO, joueur.yBodyRot);
            float lacetTete = Mth.clamp(Mth.wrapDegrees(Mth.rotLerp(partiel, joueur.yHeadRotO, joueur.yHeadRot) - corpsLacet), -50, 50);
            float tangageTete = Mth.clamp(joueur.getViewXRot(partiel) * 0.5F, -25, 25);

            ps.pushPose();
            Vec3 pos = joueur.getPosition(partiel).subtract(camera);
            ps.translate(pos.x, pos.y, pos.z);
            ps.mulPose(Axis.YP.rotationDegrees(-corpsLacet));
            if (s.geste == Pouvoirs.ECRASE && s.coup >= 0) {
                onde(buffers, ps, s, g);
            }
            // un peu plus bas que le joueur : la cage tombe sur le torse
            ps.translate(0, -0.35F, 0);
            float echelle = 1 + disparition * 0.25F;
            ps.scale(echelle, echelle, echelle);
            List<Os> liste = new ArrayList<>();
            squelette(liste, ps, s, t, lacetTete, tangageTete);
            float opacite = 1 - disparition;
            for (Os o : liste) {
                Vector3f c = o.repere().pose().transformPosition(new Vector3f((o.x0() + o.x1()) / 2, (o.y0() + o.y1()) / 2, (o.z0() + o.z1()) / 2));
                s.os.add(new Vec3(c.x + camera.x, c.y + camera.y, c.z + camera.z));
            }
            dessinerOs(buffers, liste, s.couleur, opacite, eclat, premierePersonne, g);
            aura(buffers, ps, s, t, g, opacite * (premierePersonne ? 0.35F : 1));
            if (s.coup >= 0 && s.fin < 0 && s.geste == Pouvoirs.FRAPPE) {
                ps.pushPose();
                if (s.cote == 1) {
                    ps.scale(-1, 1, 1);
                }
                float lacetMain = (s.cote == 1 ? poseGauche(s, t) : poseDroite(s, t)).epauleLacet();
                croissant(buffers, ps, s, secondes(s.coup) / DUREE_COUP, lacetMain, Mth.lerp(g, 1F, 1.35F), Mth.lerp(g, 0F, 0.8F));
                ps.popPose();
            }
            ps.popPose();
        }
        buffers.endBatch(Types.CORPS);
        buffers.endBatch(Types.LUEUR);
        buffers.endBatch(RenderType.lines());
    }

    private static float[] rvb(int c) {
        return new float[]{(c >> 16 & 255) / 255F, (c >> 8 & 255) / 255F, (c & 255) / 255F};
    }

    /** Les yeux : jaune pétant, quelle que soit la couleur du Susano. */
    private static final float[] JAUNE = {1F, 0.9F, 0.15F};
    private static final float[] JAUNE_LUEUR = {1F, 0.75F, 0.05F};
    /** Les traits du contour : la couleur du Susano en plus foncé (sauf le noir, où ils sont blancs). */
    private static final float[] BLANC = {1F, 1F, 1F};

    private static void dessinerOs(MultiBufferSource buffers, List<Os> liste, Couleur couleur, float opacite, float eclat, boolean premierePersonne, float g) {
        float[] corps = rvb(couleur.corps);
        float[] sombre = {corps[0] * 0.18F, corps[1] * 0.18F, corps[2] * 0.18F};
        float[] contour = rvb(couleur.contour);
        // au niveau 2, le chakra s'épaissit : on voit beaucoup moins à travers
        float base = couleur == Couleur.NOIR ? 0.92F : Mth.lerp(Mth.clamp(g, 0, 1), 0.5F, 0.85F);
        float[] trait = couleur == Couleur.NOIR ? BLANC : new float[]{corps[0] * 0.42F, corps[1] * 0.42F, corps[2] * 0.42F};
        VertexConsumer c = buffers.getBuffer(Types.CORPS);
        for (Os o : liste) {
            float transparence = premierePersonne && o.genre() == CAGE ? 0.35F : 1;
            switch (o.genre()) {
                case OEIL -> faces(c, o, JAUNE, opacite);
                case SOMBRE -> faces(c, o, sombre, Math.min(1, 0.95F * o.force() * opacite));
                default -> faces(c, o, corps, Math.min(1, base * o.force() * opacite * transparence * (1 + eclat)));
            }
        }
        VertexConsumer l = buffers.getBuffer(Types.LUEUR);
        for (Os o : liste) {
            if (o.genre() == OEIL) {
                // les yeux brillent fort, même dans le noir : un cœur presque blanc, puis un halo jaune qui déborde
                faces(l, o, JAUNE, 0.9F * opacite);
                faces(l, gonfle(o, 0.06F), JAUNE_LUEUR, 0.6F * opacite);
                faces(l, gonfle(o, 0.2F), JAUNE_LUEUR, 0.3F * opacite);
                faces(l, gonfle(o, 0.42F), JAUNE_LUEUR, 0.12F * opacite);
            } else if (couleur != Couleur.NOIR) {
                float a = 0.14F * o.force() * opacite * (premierePersonne && o.genre() == CAGE ? 0.3F : 1) * (1 + eclat * 3);
                faces(l, gonfle(o, 0.05F), contour, a);
            }
        }
        // les traits, dans la couleur du Susano en plus foncé (en blanc sur le noir)
        VertexConsumer lignes = buffers.getBuffer(RenderType.lines());
        for (Os o : liste) {
            if (o.genre() != SOMBRE) {
                aretes(lignes, o, trait, Math.min(1, 0.9F * o.force() * opacite * (premierePersonne && o.genre() == CAGE ? 0.4F : 1)));
            }
        }
    }

    private static Os gonfle(Os o, float d) {
        return new Os(o.repere(), o.x0() - d, o.y0() - d, o.z0() - d, o.x1() + d, o.y1() + d, o.z1() + d, o.force(), o.genre());
    }

    /** Les six faces, un peu plus sombres sur les côtés et dessous : ça donne du volume. */
    private static void faces(VertexConsumer c, Os o, float[] rgb, float a) {
        Matrix4f m = o.repere().pose();
        float x0 = o.x0(), y0 = o.y0(), z0 = o.z0(), x1 = o.x1(), y1 = o.y1(), z1 = o.z1();
        quad(c, m, rgb, 1F, a, x0, y1, z0, x0, y1, z1, x1, y1, z1, x1, y1, z0);
        quad(c, m, rgb, 0.55F, a, x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1);
        quad(c, m, rgb, 0.8F, a, x0, y0, z0, x0, y1, z0, x1, y1, z0, x1, y0, z0);
        quad(c, m, rgb, 0.8F, a, x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1);
        quad(c, m, rgb, 0.68F, a, x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0);
        quad(c, m, rgb, 0.68F, a, x1, y0, z0, x1, y1, z0, x1, y1, z1, x1, y0, z1);
    }

    private static void quad(VertexConsumer c, Matrix4f m, float[] rgb, float ombre, float a, float... p) {
        for (int i = 0; i < 12; i += 3) {
            c.addVertex(m, p[i], p[i + 1], p[i + 2]).setColor(rgb[0] * ombre, rgb[1] * ombre, rgb[2] * ombre, a);
        }
    }

    private static void aretes(VertexConsumer c, Os o, float[] rgb, float a) {
        float[] xs = {o.x0(), o.x1()};
        float[] ys = {o.y0(), o.y1()};
        float[] zs = {o.z0(), o.z1()};
        for (int i = 0; i < 2; i++) {
            for (int j = 0; j < 2; j++) {
                arete(c, o.repere(), rgb, a, xs[0], ys[i], zs[j], xs[1], ys[i], zs[j]);
                arete(c, o.repere(), rgb, a, xs[i], ys[0], zs[j], xs[i], ys[1], zs[j]);
                arete(c, o.repere(), rgb, a, xs[i], ys[j], zs[0], xs[i], ys[j], zs[1]);
            }
        }
    }

    private static void arete(VertexConsumer c, PoseStack.Pose p, float[] rgb, float a, float x0, float y0, float z0, float x1, float y1, float z1) {
        Vector3f n = new Vector3f(x1 - x0, y1 - y0, z1 - z0).normalize();
        c.addVertex(p, x0, y0, z0).setColor(rgb[0], rgb[1], rgb[2], a).setNormal(p, n.x, n.y, n.z);
        c.addVertex(p, x1, y1, z1).setColor(rgb[0], rgb[1], rgb[2], a).setNormal(p, n.x, n.y, n.z);
    }

    /**
     * Le croissant de chakra que laisse la main en balayant, de la droite jusqu'à où elle est arrivée ; et autour,
     * des traits d'aura plus fins, au-dessus et en dessous ({@code traits} : combien ils sont marqués).
     */
    private static void croissant(MultiBufferSource buffers, PoseStack ps, Susano s, double k, float lacetMain, float echelle, float traits) {
        if (k < 0.15 || k > 0.8) {
            return;
        }
        float a = (float) (1 - lisse((k - 0.4) / 0.4));
        float debut = Pose.ARME.epauleLacet() * Mth.DEG_TO_RAD;
        float fin = Math.max(debut, Math.min(lacetMain, Pose.FRAPPE.epauleLacet()) * Mth.DEG_TO_RAD);
        if (fin - debut < 0.05F) {
            return;
        }
        Couleur couleur = s.couleur;
        Matrix4f m = ps.last().pose();
        for (int passe = 0; passe < 2; passe++) {
            boolean lueur = passe == 1;
            if (lueur && couleur == Couleur.NOIR) {
                continue;
            }
            float[] rgb = rvb(lueur ? couleur.contour : couleur.corps);
            VertexConsumer c = buffers.getBuffer(lueur ? Types.LUEUR : Types.CORPS);
            arc(c, m, rgb, debut, fin, 4.1F * echelle, 1.5F * echelle, 0.9F * echelle, a * (lueur ? 0.5F : 0.6F));
        }
        // les traits d'aura : des arcs fins, plus courts, qui filent derrière la main
        float force = 0.35F + traits;
        VertexConsumer c = buffers.getBuffer(Types.LUEUR);
        float[] rgb = rvb(couleur.contour);
        float[][] traitsAura = {{3.2F, 2.1F, 0.6F}, {4.6F, 1.1F, 0.4F}, {3.6F, 0.7F, 0.75F}, {4.9F, 2.0F, 0.5F}, {2.8F, 1.4F, 0.55F}};
        for (float[] trait : traitsAura) {
            float debutTrait = Math.max(debut, fin - trait[2] * (fin - debut) - 0.2F);
            arc(c, m, rgb, debutTrait, fin, trait[0] * echelle, trait[1] * echelle, 0.07F * echelle, a * 0.7F * force);
        }
    }

    /** Un arc à plat (un ruban qui s'affine aux deux bouts), du lacet a0 au lacet a1, à ce rayon et à cette hauteur. */
    private static void arc(VertexConsumer c, Matrix4f m, float[] rgb, float a0, float a1, float rayon, float y, float epaisseur, float alpha) {
        int n = 24;
        for (int i = 0; i < n; i++) {
            float u0 = i / (float) n, u1 = (i + 1) / (float) n;
            float e0 = (Mth.sin(u0 * Mth.PI) * 0.95F + 0.05F) * epaisseur, e1 = (Mth.sin(u1 * Mth.PI) * 0.95F + 0.05F) * epaisseur;
            float b0 = Mth.lerp(u0, a0, a1), b1 = Mth.lerp(u1, a0, a1);
            float aa = alpha * (0.3F + 0.7F * u1);
            c.addVertex(m, Mth.sin(b0) * (rayon - e0), y, Mth.cos(b0) * (rayon - e0)).setColor(rgb[0], rgb[1], rgb[2], aa);
            c.addVertex(m, Mth.sin(b0) * rayon, y, Mth.cos(b0) * rayon).setColor(rgb[0], rgb[1], rgb[2], aa);
            c.addVertex(m, Mth.sin(b1) * rayon, y, Mth.cos(b1) * rayon).setColor(rgb[0], rgb[1], rgb[2], aa);
            c.addVertex(m, Mth.sin(b1) * (rayon - e1), y, Mth.cos(b1) * (rayon - e1)).setColor(rgb[0], rgb[1], rgb[2], aa);
        }
    }

    /**
     * Des traits d'aura qui montent tout autour du Susano : légers au niveau 2, forts juste après chaque geste et
     * quand il grandit.
     */
    private static void aura(MultiBufferSource buffers, PoseStack ps, Susano s, double t, float g, float opacite) {
        float intensite = 0.4F * Math.min(1, g);
        if (s.coup >= 0) {
            intensite += 1.1F * (float) Math.exp(-secondes(s.coup) * 2.5);
        }
        double t2 = t2(s);
        if (t2 >= MONTEE) {
            intensite += 1.4F * (float) Math.exp(-(t2 - MONTEE) * 1.5);
        }
        intensite *= opacite;
        if (intensite < 0.02F || s.fin >= 0) {
            return;
        }
        float echelle = Mth.lerp(g, 1F, 1.4F);
        float[] rgb = rvb(s.couleur.contour);
        VertexConsumer c = buffers.getBuffer(Types.LUEUR);
        Matrix4f m = ps.last().pose();
        for (int i = 0; i < 20; i++) {
            double hasard = (i * 0.6180339887) % 1;
            double phase = (t * (0.7 + hasard * 0.5) + hasard) % 1;
            float angle = i * 2.39996F;
            float rayon = (1.25F + (float) hasard * 0.6F) * echelle;
            float x = Mth.sin(angle) * rayon;
            float z = Mth.cos(angle) * rayon;
            float y0 = (float) phase * 4.4F * echelle;
            float y1 = y0 + 0.9F * echelle;
            float a = Mth.sin((float) phase * Mth.PI) * intensite * 0.55F;
            float l = 0.05F * echelle;
            // deux plans en croix : le trait se voit de partout
            trait(c, m, rgb, x - l * Mth.cos(angle), z + l * Mth.sin(angle), x + l * Mth.cos(angle), z - l * Mth.sin(angle), y0, y1, a);
            trait(c, m, rgb, x - l * Mth.sin(angle), z - l * Mth.cos(angle), x + l * Mth.sin(angle), z + l * Mth.cos(angle), y0, y1, a);
        }
    }

    private static void trait(VertexConsumer c, Matrix4f m, float[] rgb, float xa, float za, float xb, float zb, float y0, float y1, float a) {
        c.addVertex(m, xa, y0, za).setColor(rgb[0], rgb[1], rgb[2], 0F);
        c.addVertex(m, xb, y0, zb).setColor(rgb[0], rgb[1], rgb[2], 0F);
        c.addVertex(m, xb, y1, zb).setColor(rgb[0], rgb[1], rgb[2], a);
        c.addVertex(m, xa, y1, za).setColor(rgb[0], rgb[1], rgb[2], a);
    }

    /** L'onde de choc de l'écrasement : deux anneaux de lumière qui filent au ras du sol, là où la main a frappé. */
    private static void onde(MultiBufferSource buffers, PoseStack ps, Susano s, float g) {
        double apres = secondes(s.coup) - Pouvoirs.IMPACT / 20.0;
        if (apres < 0 || apres > 0.7) {
            return;
        }
        float k = (float) (apres / 0.7);
        float cx = -Mth.lerp(g, 1.3F, 1.7F);
        float cz = Mth.lerp(g, 2.7F, 3.5F);
        float[] rgb = rvb(s.couleur.contour);
        VertexConsumer c = buffers.getBuffer(Types.LUEUR);
        Matrix4f m = ps.last().pose();
        for (int anneau = 0; anneau < 2; anneau++) {
            float r = (0.5F + 6.5F * (float) Math.sqrt(k)) * (anneau == 0 ? 1 : 0.6F);
            float largeur = 0.7F * (1 - k) + 0.1F;
            float a = (1 - k) * (anneau == 0 ? 0.9F : 0.6F);
            int n = 40;
            for (int i = 0; i < n; i++) {
                float b0 = Mth.TWO_PI * i / n, b1 = Mth.TWO_PI * (i + 1) / n;
                c.addVertex(m, cx + Mth.sin(b0) * (r - largeur), 0.08F, cz + Mth.cos(b0) * (r - largeur)).setColor(rgb[0], rgb[1], rgb[2], 0F);
                c.addVertex(m, cx + Mth.sin(b0) * r, 0.08F, cz + Mth.cos(b0) * r).setColor(rgb[0], rgb[1], rgb[2], a);
                c.addVertex(m, cx + Mth.sin(b1) * r, 0.08F, cz + Mth.cos(b1) * r).setColor(rgb[0], rgb[1], rgb[2], a);
                c.addVertex(m, cx + Mth.sin(b1) * (r - largeur), 0.08F, cz + Mth.cos(b1) * (r - largeur)).setColor(rgb[0], rgb[1], rgb[2], 0F);
            }
        }
    }

    private static final class Types extends RenderType {
        /** Le chakra : translucide, visible des deux côtés, sans cacher ce qui est derrière. */
        static final RenderType CORPS = create("cabane_susano_corps", DefaultVertexFormat.POSITION_COLOR, VertexFormat.Mode.QUADS, 65536,
                false, true, CompositeState.builder().setShaderState(POSITION_COLOR_SHADER).setWriteMaskState(COLOR_WRITE)
                        .setTransparencyState(TRANSLUCENT_TRANSPARENCY).setCullState(NO_CULL).createCompositeState(false));
        /** La lueur autour : de la lumière qui s'ajoute. */
        static final RenderType LUEUR = create("cabane_susano_lueur", DefaultVertexFormat.POSITION_COLOR, VertexFormat.Mode.QUADS, 65536,
                false, true, CompositeState.builder().setShaderState(RENDERTYPE_LIGHTNING_SHADER).setWriteMaskState(COLOR_WRITE)
                        .setTransparencyState(LIGHTNING_TRANSPARENCY).setCullState(NO_CULL).createCompositeState(false));

        private Types() {
            super("cabane_susano_rien", DefaultVertexFormat.POSITION, VertexFormat.Mode.QUADS, 256, false, false, () -> { }, () -> { });
        }
    }

    // ================================================================ le panneau des touches

    /** À gauche de l'écran, tant que le pouvoir est éveillé : les touches, et ce qu'elles font en ce moment. */
    private static void panneau(GuiGraphics g) {
        Minecraft mc = Minecraft.getInstance();
        Susano s = mc.player == null ? null : SUSANOS.get(mc.player.getUUID());
        if (!eveille(s) || secondes(s.debut) < JAILLISSEMENT) {
            return;
        }
        boolean grand = s.niveau >= 2;
        double recharge = (RECHARGE_CHARGE - (System.nanoTime() - derniereCharge)) / 1e9;
        double rechargePrise = grand && s.dernierePrise >= 0 && !s.tient ? Pouvoirs.RECHARGE_PRISE_2 / 20.0 - secondes(s.dernierePrise) : 0;
        String[][] lignes = {
                {"Clic gauche", s.tient ? "Jeter" : grand ? "Frappe (gauche, droite)" : "Frappe"},
                {"Clic droit", s.tient ? (grand ? "Écraser au sol" : "Lâcher") : rechargePrise > 0 ? String.format("Attraper (%.0f s)", Math.ceil(rechargePrise)) : "Attraper"},
                {"Accroupi", recharge > 0 ? String.format("Charge (%.1f s)", recharge) : "Charge"},
                {EVEIL.getTranslatedKeyMessage().getString(), !grand && s.pouvoir.equals("susano2") ? "Niveau 2" : "Rendormir"}};
        Font font = mc.font;
        String titre = grand ? "SUSANO  ·  NIVEAU 2" : "SUSANO";
        int largeurTouche = 0;
        int largeurAction = font.width(titre) - 10;
        for (String[] l : lignes) {
            largeurTouche = Math.max(largeurTouche, font.width(l[0]));
            largeurAction = Math.max(largeurAction, font.width(l[1]));
        }
        int w = largeurTouche + largeurAction + 26;
        int h = 22 + lignes.length * 13;
        int x = 6;
        int y = g.guiHeight() / 2 - h / 2;
        int teinte = 0xFF000000 | (s.couleur == Couleur.NOIR ? 0xFFFFFF : s.couleur.contour);
        g.fill(x, y, x + w, y + h, 0xA0000000);
        g.fill(x, y, x + 2, y + h, teinte);
        g.drawString(font, titre, x + 8, y + 6, teinte, true);
        for (int i = 0; i < lignes.length; i++) {
            int ly = y + 21 + i * 13;
            boolean grise = i == 2 && recharge > 0 || i == 1 && rechargePrise > 0;
            // la touche, dans un petit cadre
            g.fill(x + 7, ly - 2, x + 11 + font.width(lignes[i][0]), ly + 9, 0x40FFFFFF);
            g.drawString(font, lignes[i][0], x + 9, ly, 0xFFFFFFFF, false);
            g.drawString(font, lignes[i][1], x + 18 + largeurTouche, ly, grise ? 0xFF7A7A7A : 0xFFEDEDED, true);
        }
    }

    // ================================================================ branchements

    @EventBusSubscriber(modid = Cabane.MODID, value = Dist.CLIENT)
    public static final class Touches {
        @SubscribeEvent
        public static void enregistrer(RegisterKeyMappingsEvent event) {
            event.register(EVEIL);
        }

        @SubscribeEvent
        public static void couches(RegisterGuiLayersEvent event) {
            event.registerAboveAll(Cabane.id("pouvoir"), (g, delta) -> panneau(g));
        }

        private Touches() {
        }
    }

    @EventBusSubscriber(modid = Cabane.MODID, value = Dist.CLIENT)
    public static final class Evenements {
        @SubscribeEvent
        public static void tick(ClientTickEvent.Post event) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.level == null || mc.player == null) {
                SUSANOS.clear();
                return;
            }
            while (EVEIL.consumeClick()) {
                PacketDistributor.sendToServer(new Pouvoirs.Eveil());
            }
            // s'accroupir (l'appui, pas le maintien) lance la charge
            boolean accroupi = mc.options.keyShift.isDown();
            Susano moi = SUSANOS.get(mc.player.getUUID());
            if (accroupi && !accroupiAvant && mc.screen == null && eveille(moi) && secondes(moi.debut) >= FORME
                    && System.nanoTime() - derniereCharge > RECHARGE_CHARGE) {
                derniereCharge = System.nanoTime();
                PacketDistributor.sendToServer(new Pouvoirs.Charge());
            }
            accroupiAvant = accroupi;
            SUSANOS.entrySet().removeIf(e -> e.getValue().fin >= 0 && secondes(e.getValue().fin) > DISPARITION);
            for (Map.Entry<UUID, Susano> entree : SUSANOS.entrySet()) {
                Susano s = entree.getValue();
                Player joueur = mc.level.getPlayerByUUID(entree.getKey());
                if (joueur == null || s.fin >= 0) {
                    continue;
                }
                double t = secondes(s.debut);
                if (s.tPrecedent < JAILLISSEMENT && t >= JAILLISSEMENT) {
                    son(joueur, SoundEvents.BEACON_ACTIVATE, 1F, 0.6F);
                    son(joueur, SoundEvents.FIRECHARGE_USE, 0.8F, 0.5F);
                    son(joueur, SoundEvents.ENDER_DRAGON_FLAP, 1F, 0.7F);
                    gerbe(mc, joueur, s, 50, 1.2);
                }
                s.tPrecedent = t;
                double t2 = t2(s);
                if (t2 >= 0) {
                    // le niveau 2 : il rit, puis tout grandit, puis les yeux s'allument
                    if (s.t2Precedent < 0 && t2 >= 0) {
                        son(joueur, SoundEvents.WITCH_CELEBRATE, 1F, 0.55F);
                        son(joueur, SoundEvents.EVOKER_PREPARE_SUMMON, 0.8F, 0.8F);
                    }
                    if (s.t2Precedent < 0.5 && t2 >= 0.5) {
                        son(joueur, SoundEvents.WITCH_CELEBRATE, 0.9F, 0.5F);
                    }
                    if (s.t2Precedent < MONTEE && t2 >= MONTEE) {
                        son(joueur, SoundEvents.BEACON_ACTIVATE, 1F, 0.5F);
                        son(joueur, SoundEvents.WITHER_SPAWN, 0.35F, 1.4F);
                        son(joueur, SoundEvents.ENDER_DRAGON_FLAP, 1F, 0.5F);
                        gerbe(mc, joueur, s, 90, 2);
                    }
                    if (s.t2Precedent < YEUX && t2 >= YEUX) {
                        son(joueur, SoundEvents.ELDER_GUARDIAN_CURSE, 0.5F, 1.3F);
                    }
                    s.t2Precedent = t2;
                }
                // la charge laisse une traînée de chakra derrière elle
                if (s.geste == Pouvoirs.CHARGE && s.coup >= 0 && secondes(s.coup) < 0.5) {
                    DustParticleOptions poussiere = poussiere(s.couleur, 1.6F);
                    for (int i = 0; i < 8; i++) {
                        mc.level.addParticle(poussiere, joueur.getX() + aleat(0.6), joueur.getY() + 0.2 + HASARD.nextDouble() * 1.8, joueur.getZ() + aleat(0.6), 0, 0, 0);
                    }
                    mc.level.addParticle(ParticleTypes.SWEEP_ATTACK, joueur.getX(), joueur.getY() + 1, joueur.getZ(), 0, 0, 0);
                }
                // le chakra s'effrite doucement
                if (!s.os.isEmpty() && HASARD.nextInt(s.niveau >= 2 ? 1 : 2) == 0) {
                    Vec3 o = s.os.get(HASARD.nextInt(s.os.size()));
                    mc.level.addParticle(poussiere(s.couleur, 0.9F), o.x + aleat(0.2), o.y + aleat(0.2), o.z + aleat(0.2), 0, 0.03, 0);
                }
            }
        }

        /** Une gerbe de chakra qui part en cercle autour du joueur. */
        private static void gerbe(Minecraft mc, Player joueur, Susano s, int nombre, double rayon) {
            DustParticleOptions poussiere = poussiere(s.couleur, 2F);
            for (int i = 0; i < nombre; i++) {
                double a = HASARD.nextDouble() * Math.PI * 2;
                mc.level.addParticle(poussiere, joueur.getX() + Math.cos(a) * rayon, joueur.getY() + 0.3 + HASARD.nextDouble() * 2.2 * rayon,
                        joueur.getZ() + Math.sin(a) * rayon, Math.cos(a) * 0.3, 0.02, Math.sin(a) * 0.3);
            }
        }

        /** Pouvoir éveillé, les clics sont au Susano : le gauche frappe (ou jette), le droit attrape (ou lâche, ou écrase). */
        @SubscribeEvent
        public static void clic(InputEvent.InteractionKeyMappingTriggered event) {
            Minecraft mc = Minecraft.getInstance();
            Susano s = mc.player == null ? null : SUSANOS.get(mc.player.getUUID());
            if (!(event.isAttack() || event.isUseItem()) || !eveille(s) || secondes(s.debut) < FORME) {
                return;
            }
            event.setCanceled(true);
            event.setSwingHand(event.isAttack());
            long recharge = s.niveau >= 2 && event.isAttack() ? 400_000_000L : 600_000_000L;
            if (System.nanoTime() - dernierClic > recharge) {
                dernierClic = System.nanoTime();
                PacketDistributor.sendToServer(event.isAttack() ? new Pouvoirs.Frappe() : new Pouvoirs.Attraper());
            }
        }

        /** Quand un écrasement frappe le sol tout près, l'écran tremble. */
        @SubscribeEvent
        public static void tremblement(ViewportEvent.ComputeCameraAngles event) {
            if (secousse < 0) {
                return;
            }
            double apres = (System.nanoTime() - secousse) / 1e9;
            if (apres < 0) {
                return;
            }
            if (apres > 0.6) {
                secousse = -1;
                return;
            }
            float force = (float) (1 - apres / 0.6);
            event.setRoll(event.getRoll() + (HASARD.nextFloat() - 0.5F) * 6 * force);
            event.setPitch(event.getPitch() + (HASARD.nextFloat() - 0.5F) * 3 * force);
        }

        @SubscribeEvent
        public static void dessiner(RenderLevelStageEvent event) {
            if (SUSANOS.isEmpty() || event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES || Minecraft.getInstance().level == null) {
                return;
            }
            ClientSusano.dessiner(event.getPoseStack(), event.getCamera().getPosition(), event.getPartialTick().getGameTimeDeltaPartialTick(false));
        }

        private Evenements() {
        }
    }

    private ClientSusano() {
    }
}
