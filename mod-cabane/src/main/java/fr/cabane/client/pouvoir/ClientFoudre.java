package fr.cabane.client.pouvoir;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import fr.cabane.Cabane;
import fr.cabane.CabaneSons;
import fr.cabane.client.lutte.CorpsCatcheur;
import fr.cabane.lutte.Equipement;
import fr.cabane.pouvoir.Foudre;
import fr.cabane.pouvoir.Pouvoirs;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.resources.ResourceLocation;
import java.util.Random;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import net.minecraft.client.resources.PlayerSkin;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.MovementInputUpdateEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * Ce qu'on voit du pouvoir Foudre (voir {@link Foudre} et docs/foudre). Éveillé : la foudre lui tombe dessus, puis des
 * éclairs courent sans cesse sur lui, avec un crépitement d'électricité statique, et ses yeux brillent (juste leurs deux
 * pixels). Au niveau 2, une aura bleu clair l'enveloppe (le manteau de Hachibi, sans oreilles ni queues) et les éclairs
 * vont jusqu'au sol. L'éclair en chaîne, le pas de l'éclair (une traînée, la foudre qui tombe là où il arrive), le choc
 * d'un coup. Et le Kirin : la main levée, un gros nuage noir plein d'éclairs au-dessus de la zone (marquée de petits
 * nuages blancs), la tête du Kirin qui en sort et rugit, la barre de charge ; la main baisse, il plonge d'un coup, un
 * dôme de lumière. Le Kirin est dessiné comme dans l'animé : des traits de foudre cyan, les yeux rouges.
 */
public final class ClientFoudre {
    private static final RandomSource HASARD = RandomSource.create();
    private static final double PLONGEE = Foudre.PLONGEE / 20.0;
    private static final double IMPACT = Foudre.IMPACT / 20.0;
    private static final double CHARGE = Foudre.CHARGE_KIRIN / 20.0;
    /** Le nuage du Kirin, au-dessus de la zone. */
    private static final double HAUT_NUAGE = 34;
    static final float[] CIEL = {0.55F, 0.85F, 1F};
    static final float[] BLANC = {1F, 1F, 1F};
    private static final float[] ROUGE = {1F, 0.15F, 0.1F};

    /** Les éveillés : leur liste blanche et leur niveau. */
    private record Eveil(String pouvoir, int niveau) {
    }

    private static final Map<UUID, Eveil> EVEILLES = new HashMap<>();
    /** Quand la foudre leur est tombée dessus (l'éveil, le passage au niveau 2). */
    private static final Map<UUID, Long> FRAPPES = new HashMap<>();
    private static final Map<UUID, Statique> STATIQUES = new HashMap<>();

    private record Chaine(List<Vec3> points, long debut, long graine) {
    }

    private static final List<Chaine> CHAINES = new ArrayList<>();

    private record Saut(Vec3 de, Vec3 a, boolean fort, boolean trois, long debut) {
    }

    private static final List<Saut> SAUTS = new ArrayList<>();

    private record Choc(boolean fort, long debut) {
    }

    private static final Map<Integer, Choc> CHOCS = new HashMap<>();

    /** Un Kirin : où il tombe, vers qui il regarde, depuis quand il se charge, quand il a été lâché (ou annulé). */
    private static final class Kirin {
        Vec3 zone;
        Vec3 vers;
        long debut;
        long lache = -1;
        long annule = -1;
        float charge;
        boolean sorti;
        boolean tombe;
        Bourdon bourdon;
    }

    private static final Map<UUID, Kirin> KIRINS = new HashMap<>();

    /** Pour moi : quand mon clic droit et mon pas seront prêts, si je tiens le clic droit (le Kirin). */
    private static long pretDroit;
    private static long pretPas;
    private static boolean tenu;
    private static boolean accroupiAvant;
    private static long secousse = -1;
    private static float forceSecousse;

    private static double secondes(long depuis) {
        return (System.nanoTime() - depuis) / 1e9;
    }

    static double lisse(double x) {
        x = Mth.clamp(x, 0, 1);
        return x * x * (3 - 2 * x);
    }

    private static Vec3 vec(Vector3f v) {
        return new Vec3(v.x(), v.y(), v.z());
    }

    // ================================================================ ce que dit le serveur

    public static void recevoir(Pouvoirs.Etat p) {
        // la Foudre 3 au niveau 3 : la tenue bleue (voir ClientTenueFoudre)
        ClientTenueFoudre.etat(p.joueur(), p.eveille() && Foudre.NOM3.equals(p.pouvoir()) && p.niveau() >= 3, p.annonce());
        Eveil avant = EVEILLES.get(p.joueur());
        Minecraft mc = Minecraft.getInstance();
        boolean moi = mc.player != null && p.joueur().equals(mc.player.getUUID());
        if (p.eveille()) {
            EVEILLES.put(p.joueur(), new Eveil(p.pouvoir(), p.niveau()));
            if (p.annonce() && (avant == null || avant.niveau() < p.niveau())) {
                FRAPPES.put(p.joueur(), System.nanoTime());
                if (moi) {
                    secouer(p.niveau() >= 2 ? 1F : 0.6F);
                }
            }
        } else {
            EVEILLES.remove(p.joueur());
            FRAPPES.remove(p.joueur());
            if (moi) {
                pretDroit = 0;
                pretPas = 0;
                tenu = false;
                ClientBetes.endormi();
            }
        }
    }

    public static void oublier(UUID joueur) {
        ClientTenueFoudre.oublier(joueur);
        EVEILLES.remove(joueur);
        FRAPPES.remove(joueur);
    }

    public static void chaine(Foudre.Chaine p) {
        CHAINES.add(new Chaine(p.points().stream().map(ClientFoudre::vec).toList(), System.nanoTime(), HASARD.nextLong()));
    }

    public static void saut(Foudre.Saut p) {
        Vec3 a = vec(p.a());
        Eveil qui = EVEILLES.get(p.joueur());
        SAUTS.add(new Saut(vec(p.de()), a, p.fort(), qui != null && qui.niveau() >= 3, System.nanoTime()));
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && mc.player.position().distanceTo(a) < 12) {
            secouer(p.fort() ? 0.8F : 0.5F);
        }
        if (mc.level != null) {
            for (int i = 0; i < (p.fort() ? 40 : 25); i++) {
                mc.level.addParticle(ParticleTypes.ELECTRIC_SPARK, a.x + HASARD.nextGaussian() * 1.2, a.y + HASARD.nextDouble() * 2, a.z + HASARD.nextGaussian() * 1.2,
                        HASARD.nextGaussian() * 0.2, 0.1, HASARD.nextGaussian() * 0.2);
            }
        }
    }

    public static void choc(Foudre.Choc p) {
        CHOCS.put(p.entite(), new Choc(p.fort(), System.nanoTime()));
    }

    public static void kirin(Foudre.EtatKirin p) {
        Minecraft mc = Minecraft.getInstance();
        boolean moi = mc.player != null && p.joueur().equals(mc.player.getUUID());
        long maintenant = System.nanoTime();
        Kirin k = KIRINS.get(p.joueur());
        switch (p.phase()) {
            case Foudre.CHARGE -> {
                k = new Kirin();
                k.zone = vec(p.zone());
                k.debut = maintenant;
                Player lanceur = mc.level == null ? null : mc.level.getPlayerByUUID(p.joueur());
                Vec3 vers = lanceur == null ? new Vec3(0, 0, 1) : lanceur.position().subtract(k.zone);
                k.vers = new Vec3(vers.x, 0, vers.z).lengthSqr() < 0.01 ? new Vec3(0, 0, 1) : new Vec3(vers.x, 0, vers.z).normalize();
                KIRINS.put(p.joueur(), k);
                if (lanceur != null) {
                    k.bourdon = new Bourdon(lanceur, k);
                    mc.getSoundManager().play(k.bourdon);
                }
            }
            case Foudre.ANNULE -> {
                if (k != null && k.lache < 0) {
                    k.annule = maintenant;
                }
                if (moi) {
                    tenu = false;
                    pretDroit = maintenant + Foudre.RECHARGE_ANNULE * 50_000_000L;
                }
            }
            default -> {
                if (k == null) {
                    k = new Kirin();
                    k.zone = vec(p.zone());
                    k.vers = new Vec3(0, 0, 1);
                    k.debut = maintenant - (long) (CHARGE * 1e9);
                    k.sorti = true;
                    KIRINS.put(p.joueur(), k);
                }
                k.lache = maintenant;
                k.charge = p.charge();
                if (moi) {
                    tenu = false;
                    pretDroit = maintenant + Foudre.RECHARGE_KIRIN * 50_000_000L;
                }
            }
        }
    }

    /** Ce Kirin se charge-t-il encore (main levée, ni lâché ni annulé) ? */
    private static boolean enCharge(Kirin k) {
        return k != null && k.lache < 0 && k.annule < 0;
    }

    /** De 0 à 1 : la barre (pendant la charge), puis ce qu'elle valait au lâcher. */
    private static float charge(Kirin k) {
        return k.lache >= 0 ? k.charge : (float) Math.min(1, secondes(k.debut) / CHARGE);
    }

    static void secouer(float force) {
        secousse = System.nanoTime();
        forceSecousse = Math.max(forceSecousse, force);
    }

    // ================================================================ la pose : la main levée, puis baissée

    /** Pour le Kirin, il lève la main droite tout droit vers le ciel ; au lâcher, il la baisse vers la zone. */
    public static void poser(PlayerModel<?> modele, Player joueur) {
        ClientBetes.poser(modele, joueur);
        Kirin k = KIRINS.get(joueur.getUUID());
        if (k == null) {
            return;
        }
        float lever;
        float bas = -1.25F;
        if (k.annule >= 0) {
            lever = (float) (1 - lisse(secondes(k.annule) / 0.3));
        } else if (k.lache < 0) {
            lever = (float) lisse(secondes(k.debut) / 0.35);
        } else {
            double t = secondes(k.lache);
            if (t > IMPACT + 0.6) {
                return;
            }
            // la main baisse jusqu'à la zone (bras tendu devant, vers le bas), puis revient doucement
            float descend = (float) lisse(t / PLONGEE);
            float repos = (float) lisse((t - IMPACT - 0.1) / 0.5);
            modele.rightArm.xRot = Mth.lerp(repos, Mth.lerp(descend, -2.95F, bas), modele.rightArm.xRot);
            modele.rightArm.yRot = Mth.lerp(repos, 0, modele.rightArm.yRot);
            modele.rightArm.zRot = Mth.lerp(repos, 0.08F, modele.rightArm.zRot);
            modele.rightSleeve.copyFrom(modele.rightArm);
            return;
        }
        if (lever <= 0.001F) {
            return;
        }
        modele.rightArm.xRot = Mth.lerp(lever, modele.rightArm.xRot, -2.95F);
        modele.rightArm.yRot = Mth.lerp(lever, modele.rightArm.yRot, 0);
        modele.rightArm.zRot = Mth.lerp(lever, modele.rightArm.zRot, 0.12F);
        modele.head.xRot = Mth.lerp(lever * 0.6F, modele.head.xRot, -0.35F);
        modele.rightSleeve.copyFrom(modele.rightArm);
        modele.hat.copyFrom(modele.head);
    }

    // ================================================================ les sons qui durent

    /** L'électricité statique qui crépite sur un éveillé, tant qu'il l'est (plus fort au niveau 2). */
    private static final class Statique extends AbstractTickableSoundInstance {
        private final Player joueur;

        Statique(Player joueur, int niveau) {
            super(CabaneSons.FOUDRE_STATIQUE.get(), SoundSource.PLAYERS, SoundInstance.createUnseededRandom());
            this.joueur = joueur;
            this.looping = true;
            this.delay = 0;
            this.volume = niveau >= 2 ? 0.55F : 0.35F;
            this.pitch = niveau >= 2 ? 0.9F : 1F;
            suivre();
        }

        private void suivre() {
            x = joueur.getX();
            y = joueur.getY() + 1;
            z = joueur.getZ();
        }

        @Override
        public void tick() {
            Eveil e = EVEILLES.get(joueur.getUUID());
            if (e == null || joueur.isRemoved()) {
                stop();
                return;
            }
            volume = e.niveau() >= 2 ? 0.55F : 0.35F;
            suivre();
        }
    }

    /** Le bourdonnement de la charge du Kirin, qui monte avec la barre. */
    private static final class Bourdon extends AbstractTickableSoundInstance {
        private final Player joueur;
        private final Kirin k;

        Bourdon(Player joueur, Kirin k) {
            super(CabaneSons.FOUDRE_CHARGE.get(), SoundSource.PLAYERS, SoundInstance.createUnseededRandom());
            this.joueur = joueur;
            this.k = k;
            this.looping = true;
            this.delay = 0;
            this.volume = 0.6F;
            this.pitch = 0.6F;
            x = joueur.getX();
            y = joueur.getY() + 1;
            z = joueur.getZ();
        }

        @Override
        public void tick() {
            if (!enCharge(k) || joueur.isRemoved()) {
                stop();
                return;
            }
            pitch = 0.6F + 0.9F * charge(k);
            volume = 0.5F + 0.4F * charge(k);
            x = joueur.getX();
            y = joueur.getY() + 1;
            z = joueur.getZ();
        }
    }

    // ================================================================ dessiner des éclairs

    static final class Types extends RenderType {
        /** Le cœur des éclairs, le nuage, les voiles : translucides (visibles aussi en plein jour), visibles des deux côtés. */
        static final RenderType VOILE = create("cabane_foudre_voile", DefaultVertexFormat.POSITION_COLOR, VertexFormat.Mode.QUADS, 262144,
                false, true, CompositeState.builder().setShaderState(POSITION_COLOR_SHADER).setWriteMaskState(COLOR_WRITE)
                        .setTransparencyState(TRANSLUCENT_TRANSPARENCY).setCullState(NO_CULL).createCompositeState(false));
        /** La lueur autour des éclairs : de la lumière qui s'ajoute. */
        static final RenderType LUMIERE = create("cabane_foudre_lumiere", DefaultVertexFormat.POSITION_COLOR, VertexFormat.Mode.QUADS, 262144,
                false, true, CompositeState.builder().setShaderState(RENDERTYPE_LIGHTNING_SHADER).setWriteMaskState(COLOR_WRITE)
                        .setTransparencyState(LIGHTNING_TRANSPARENCY).setCullState(NO_CULL).createCompositeState(false));

        private Types() {
            super("cabane_foudre_rien", DefaultVertexFormat.POSITION, VertexFormat.Mode.QUADS, 256, false, false, () -> { }, () -> { });
        }
    }

    /**
     * On dessine tout deux fois : d'abord le translucide (0 : les cœurs, le nuage, les voiles), puis la lumière (1 : la
     * lueur des éclairs), chacun dans son tampon. Les éclairs changent au même rythme dans les deux passes.
     */
    static int passe;

    static void sommet(VertexConsumer c, Matrix4f m, Vec3 p, float[] col, float a) {
        c.addVertex(m, (float) p.x, (float) p.y, (float) p.z).setColor(col[0], col[1], col[2], a);
    }

    static void bande(VertexConsumer c, Matrix4f m, Vec3 a, Vec3 b, Vec3 o, float[] col, float al) {
        sommet(c, m, a.add(o), col, al);
        sommet(c, m, b.add(o), col, al);
        sommet(c, m, b.subtract(o), col, al);
        sommet(c, m, a.subtract(o), col, al);
    }

    /** Une lueur douce de part et d'autre du segment : pleine au milieu, rien au bord. */
    static void degrade(VertexConsumer c, Matrix4f m, Vec3 a, Vec3 b, Vec3 o, float[] col, float al) {
        for (Vec3 cote : new Vec3[]{o, o.scale(-1)}) {
            sommet(c, m, a, col, al);
            sommet(c, m, b, col, al);
            sommet(c, m, b.add(cote), col, 0);
            sommet(c, m, a.add(cote), col, 0);
        }
    }

    /** Une ligne brisée de A à B : le milieu de chaque morceau poussé de côté, puis encore, et encore. */
    static List<Vec3> zigzag(Vec3 a, Vec3 b, long graine, double tordu, int niveaux) {
        List<Vec3> pts = new ArrayList<>(List.of(a, b));
        Vec3 d = b.subtract(a);
        if (d.lengthSqr() < 1e-6) {
            return pts;
        }
        Vec3 n = d.normalize();
        Vec3 e1 = n.cross(Math.abs(n.y) < 0.9 ? new Vec3(0, 1, 0) : new Vec3(1, 0, 0)).normalize();
        Vec3 e2 = n.cross(e1);
        Random r = new Random(graine);
        for (int k = 0; k < niveaux; k++) {
            List<Vec3> suite = new ArrayList<>();
            suite.add(pts.get(0));
            for (int i = 0; i + 1 < pts.size(); i++) {
                Vec3 p = pts.get(i), q = pts.get(i + 1);
                double l = p.distanceTo(q);
                suite.add(p.add(q).scale(0.5).add(e1.scale((r.nextDouble() - 0.5) * 2 * tordu * l)).add(e2.scale((r.nextDouble() - 0.5) * 2 * tordu * l)));
                suite.add(q);
            }
            pts = suite;
        }
        return pts;
    }

    /**
     * Un éclair le long de pts (relatifs à la caméra, qui est à l'origine), face à l'œil : un cœur blanc et un léger halo
     * de la couleur (passe 0), puis une lueur qui s'ajoute (passe 1).
     */
    static void trait(VertexConsumer c, Matrix4f m, List<Vec3> pts, double e, float alpha, float[] couleur) {
        for (int i = 0; i + 1 < pts.size(); i++) {
            Vec3 a = pts.get(i), b = pts.get(i + 1);
            Vec3 cote = b.subtract(a).cross(a.add(b).scale(0.5));
            if (cote.lengthSqr() < 1e-9) {
                continue;
            }
            cote = cote.normalize();
            if (passe == 0) {
                degrade(c, m, a, b, cote.scale(e * 2.6), couleur, 0.3F * alpha);
                bande(c, m, a, b, cote.scale(e * 0.8), BLANC, 0.95F * alpha);
            } else {
                degrade(c, m, a, b, cote.scale(e * 5), couleur, 0.55F * alpha);
            }
        }
    }

    /** Un éclair de A à B, avec quelques branches. */
    static List<Vec3> eclair(VertexConsumer c, Matrix4f m, Vec3 a, Vec3 b, long graine, double e, float alpha, int branches, float[] couleur) {
        List<Vec3> pts = zigzag(a, b, graine, 0.24, 4);
        trait(c, m, pts, e, alpha, couleur);
        Random r = new Random(graine * 7 + 3);
        double l = a.distanceTo(b);
        Vec3 ax = b.subtract(a).normalize();
        for (int k = 0; k < branches; k++) {
            Vec3 p = pts.get(2 + r.nextInt(Math.max(1, pts.size() - 4)));
            Vec3 d = ax.add(r.nextGaussian() * 0.8, r.nextGaussian() * 0.5, r.nextGaussian() * 0.8).normalize();
            trait(c, m, zigzag(p, p.add(d.scale(l * (0.12 + 0.2 * r.nextDouble()))), graine + 31L * k + 7, 0.3, 3), e * 0.55, alpha * 0.8F, couleur);
        }
        return pts;
    }

    /**
     * Des éclairs qui courent sur un corps (un cylindre autour de lui, de pieds en haut, rayon r, hauteur h) : n d'un point
     * à un autre tout près ; libres qui s'échappent dans l'air ; sol qui descendent jusqu'au sol. Ils changent douze fois
     * par seconde.
     */
    static void crepite(VertexConsumer c, Matrix4f m, Vec3 pieds, double r, double h, int n, int libres, int sol, long graine, double e, float alpha) {
        long tranche = System.nanoTime() / 83_000_000L;
        Random rnd = new Random(tranche * 7919 + graine);
        for (int i = 0; i < n + libres + sol; i++) {
            double ang = rnd.nextDouble() * Math.PI * 2, y = h * (0.08 + 0.85 * rnd.nextDouble());
            Vec3 a = pieds.add(Math.cos(ang) * r, y, Math.sin(ang) * r);
            Vec3 b;
            if (i < n) {
                double ang2 = ang + (rnd.nextDouble() - 0.5) * 1.6, y2 = Mth.clamp(y + (rnd.nextDouble() - 0.5) * h * 0.4, 0.05, h);
                b = pieds.add(Math.cos(ang2) * r * (0.85 + 0.2 * rnd.nextDouble()), y2, Math.sin(ang2) * r * (0.85 + 0.2 * rnd.nextDouble()));
            } else if (i < n + libres) {
                // à peine décollés du corps : ils ne s'en éloignent jamais beaucoup
                b = a.add(new Vec3(Math.cos(ang), rnd.nextDouble() - 0.3, Math.sin(ang)).normalize().scale(0.18 + 0.25 * rnd.nextDouble()));
            } else {
                double ang2 = ang + (rnd.nextDouble() - 0.5) * 0.6, loin = r + 0.3 + rnd.nextDouble() * 0.5;
                b = pieds.add(Math.cos(ang2) * loin, 0.05, Math.sin(ang2) * loin);
            }
            eclair(c, m, a, b, rnd.nextLong(), e, alpha, 0, CIEL);
        }
    }

    /** Une boule translucide (le nuage noir). */
    private static void boule(VertexConsumer c, Matrix4f m, Vec3 o, double r, float[] col, float alpha) {
        if (passe != 0) {
            return;
        }
        int lat = 8, lon = 12;
        for (int i = 0; i < lat; i++) {
            double p0 = Math.PI * i / lat - Math.PI / 2, p1 = Math.PI * (i + 1) / lat - Math.PI / 2;
            for (int j = 0; j < lon; j++) {
                double q0 = Math.PI * 2 * j / lon, q1 = Math.PI * 2 * (j + 1) / lon;
                for (double[] pq : new double[][]{{p0, q0}, {p1, q0}, {p1, q1}, {p0, q1}}) {
                    sommet(c, m, o.add(Math.cos(pq[0]) * Math.cos(pq[1]) * r, Math.sin(pq[0]) * r * 0.6, Math.cos(pq[0]) * Math.sin(pq[1]) * r), col, alpha);
                }
            }
        }
    }

    /** Un anneau au sol (la zone du Kirin, l'onde de la foudre qui tombe). */
    static void anneau(VertexConsumer c, Matrix4f m, Vec3 centre, double rayon, double epaisseur, float[] col, float alpha) {
        // il brille aussi (la passe de lumière), un peu moins
        alpha *= passe == 0 ? 1 : 0.6F;
        int n = 64;
        for (int i = 0; i < n; i++) {
            double t0 = i * Math.PI * 2 / n, t1 = (i + 1) * Math.PI * 2 / n;
            sommet(c, m, centre.add(Math.cos(t0) * rayon, 0, Math.sin(t0) * rayon), col, alpha);
            sommet(c, m, centre.add(Math.cos(t0) * (rayon + epaisseur), 0, Math.sin(t0) * (rayon + epaisseur)), col, 0);
            sommet(c, m, centre.add(Math.cos(t1) * (rayon + epaisseur), 0, Math.sin(t1) * (rayon + epaisseur)), col, 0);
            sommet(c, m, centre.add(Math.cos(t1) * rayon, 0, Math.sin(t1) * rayon), col, alpha);
        }
    }

    /** Un dôme de lumière : la moitié d'une boule, plus vive sur les bords. */
    static void dome(VertexConsumer c, Matrix4f m, Vec3 centre, double r, float alpha) {
        // un voile, puis de la lumière par-dessus
        alpha *= passe == 0 ? 1 : 0.8F;
        int lat = 10, lon = 24;
        for (int i = 0; i < lat; i++) {
            double p0 = Math.PI / 2 * i / lat, p1 = Math.PI / 2 * (i + 1) / lat;
            float a0 = alpha * (float) (0.15 + 0.5 * (1 - i / (double) lat)), a1 = alpha * (float) (0.15 + 0.5 * (1 - (i + 1) / (double) lat));
            for (int j = 0; j < lon; j++) {
                double q0 = Math.PI * 2 * j / lon, q1 = Math.PI * 2 * (j + 1) / lon;
                sommet(c, m, centre.add(Math.cos(p0) * Math.cos(q0) * r, Math.sin(p0) * r, Math.cos(p0) * Math.sin(q0) * r), CIEL, a0);
                sommet(c, m, centre.add(Math.cos(p1) * Math.cos(q0) * r, Math.sin(p1) * r, Math.cos(p1) * Math.sin(q0) * r), CIEL, a1);
                sommet(c, m, centre.add(Math.cos(p1) * Math.cos(q1) * r, Math.sin(p1) * r, Math.cos(p1) * Math.sin(q1) * r), CIEL, a1);
                sommet(c, m, centre.add(Math.cos(p0) * Math.cos(q1) * r, Math.sin(p0) * r, Math.cos(p0) * Math.sin(q1) * r), CIEL, a0);
            }
        }
    }

    // ================================================================ le Kirin

    /** Les pièces de sa tête (en unités de tête) : le crâne, le museau, le bout du nez ; puis la mâchoire, ouverte. */
    private static final double[][] PIECES = {{-6, -5, -8, 6, 5, 5}, {-4.2, -2, 5, 4.2, 3.4, 16}, {-3, -1.2, 16, 3, 2.6, 20}};
    private static final double[] MACHOIRE = {-3.6, -4.6, 5, 3.6, -2, 17};
    private static final int[][] ARETES = {{0, 1}, {1, 2}, {2, 3}, {3, 0}, {4, 5}, {5, 6}, {6, 7}, {7, 4}, {0, 4}, {1, 5}, {2, 6}, {3, 7}};

    /** Un repère de tête : son centre o, où regarde son museau (f), son haut (y), sa droite (x), et sa taille k. */
    private record Tete(Vec3 o, Vec3 x, Vec3 y, Vec3 f, double k, double ouvre) {
        Vec3 p(double a, double b, double c) {
            return o.add(x.scale(a * k)).add(y.scale(b * k)).add(f.scale(c * k));
        }

        /** Un point de la mâchoire, tournée autour de sa charnière (au fond de la gueule). */
        Vec3 j(double a, double b, double c) {
            double yy = b + 2, zz = c - 5, cos = Math.cos(ouvre), sin = Math.sin(ouvre);
            return p(a, yy * cos - zz * sin - 2, yy * sin + zz * cos + 5);
        }
    }

    private static Tete tete(Vec3 o, Vec3 f, Vec3 haut, double k, double ouvre) {
        f = f.normalize();
        Vec3 y = haut.subtract(f.scale(haut.dot(f))).normalize();
        return new Tete(o, y.cross(f), y, f, k, ouvre);
    }

    private static void boite(VertexConsumer c, Matrix4f m, Tete t, double[] b, boolean machoire, long graine, float alpha) {
        Vec3[] coins = new Vec3[8];
        for (int i = 0; i < 8; i++) {
            double a = (i & 1) == 0 ^ (i & 2) != 0 ? b[0] : b[3];
            double yy = (i & 2) == 0 ? b[1] : b[4];
            double zz = i < 4 ? b[2] : b[5];
            coins[i] = machoire ? t.j(a, yy, zz) : t.p(a, yy, zz);
        }
        // un voile cyan, à peine rempli
        int[][] faces = {{0, 1, 2, 3}, {4, 5, 6, 7}, {0, 1, 5, 4}, {3, 2, 6, 7}, {0, 3, 7, 4}, {1, 2, 6, 5}};
        for (int[] f : faces) {
            for (int i : f) {
                if (passe == 0) {
                    sommet(c, m, coins[i], CIEL, 0.1F * alpha);
                }
            }
        }
        // les arêtes : des traits de foudre
        for (int e = 0; e < ARETES.length; e++) {
            trait(c, m, zigzag(coins[ARETES[e][0]], coins[ARETES[e][1]], graine + e, 0.06, 3), t.k() * 0.35, alpha, CIEL);
        }
    }

    /** La tête du Kirin : ses pièces en traits de foudre, les crocs, les yeux rouges, la crinière d'éclairs, les barbillons. */
    private static void teteKirin(VertexConsumer c, Matrix4f m, Tete t, float alpha, boolean rugit) {
        long tranche = System.nanoTime() / 70_000_000L;
        for (int i = 0; i < PIECES.length; i++) {
            boite(c, m, t, PIECES[i], false, tranche * 97 + i * 13, alpha);
        }
        boite(c, m, t, MACHOIRE, true, tranche * 97 + 60, alpha);
        for (int q = 0; q < 5; q++) {
            for (int s = -1; s <= 1; s += 2) {
                trait(c, m, List.of(t.p(s * 3.4, -2, 8 + q * 2), t.p(s * 3.4, -4.2, 8.4 + q * 2)), t.k() * 0.3, alpha, BLANC);
                trait(c, m, List.of(t.j(s * 3, -2, 8 + q * 2), t.j(s * 3, 0, 8.4 + q * 2)), t.k() * 0.25, alpha, BLANC);
            }
        }
        for (int s = -1; s <= 1; s += 2) {
            // les yeux rouges, et au-dessus un sourcil froncé (un trait, pas un bloc : pas d'oreilles)
            Vec3 oeil = t.p(s * 3.9, 4.1, 5.3);
            Vec3 cote = t.x().scale(t.k() * 1.7), haut = t.y().scale(t.k() * 0.55);
            // l'œil (translucide), puis sa lueur rouge (de la lumière)
            for (double[] k : new double[][]{{1, 0.95F}, {2.8, 0.6F}}) {
                if ((k[0] > 1) != (passe == 1)) {
                    continue;
                }
                float al = (float) k[1] * alpha;
                sommet(c, m, oeil.subtract(cote.scale(k[0])).add(haut.scale(k[0])), ROUGE, al);
                sommet(c, m, oeil.add(cote.scale(k[0])).add(haut.scale(k[0])), ROUGE, al);
                sommet(c, m, oeil.add(cote.scale(k[0])).subtract(haut.scale(k[0])), ROUGE, al);
                sommet(c, m, oeil.subtract(cote.scale(k[0])).subtract(haut.scale(k[0])), ROUGE, al);
            }
            trait(c, m, zigzag(t.p(s * 1.4, 4.9, 5.4), t.p(s * 6.6, 6.4, 4.4), tranche + s, 0.05, 2), t.k() * 0.45, alpha, CIEL);
            Vec3 b = t.p(s * 4.2, -0.5, 15);
            eclair(c, m, b, b.add(t.x().scale(s * 4 * t.k())).add(t.f().scale(-4 * t.k())).add(0, -1.5 * t.k() * 6, 0), tranche * 3 + s, t.k() * 0.3, alpha, 1, CIEL);
        }
        Random r = new Random(tranche);
        for (int q = 0; q < 16; q++) {
            double a = q / 16.0 * Math.PI * 1.4 - 0.7 * Math.PI;
            Vec3 base = t.p(Math.sin(a) * 5.5, Math.cos(a) * 4, -6 - 2 * r.nextDouble());
            Vec3 d = t.x().scale(Math.sin(a) * 1.3).add(t.y().scale(Math.cos(a) * 0.9)).add(t.f().scale(-0.9 - 0.6 * r.nextDouble())).normalize();
            eclair(c, m, base, base.add(d.scale((12 + 12 * r.nextDouble()) * t.k())), r.nextLong(), t.k() * 0.35, alpha, 1, CIEL);
        }
        if (rugit) {
            Vec3 g = t.p(0, -3, 16);
            for (int q = 0; q < 8; q++) {
                double a = q / 8.0 * Math.PI * 2;
                Vec3 d = t.x().scale(Math.cos(a) * 0.9).add(t.y().scale(Math.sin(a) * 0.9)).add(t.f()).normalize();
                eclair(c, m, g.add(d.scale(6 * t.k())), g.add(d.scale((13 + 5 * r.nextDouble()) * t.k())), r.nextLong(), t.k() * 0.2, alpha * 0.7F, 0, CIEL);
            }
        }
    }

    /** Le corps du Kirin : de sa nuque jusqu'au nuage, un ruban à peine bleu dont les bords sont des éclairs. */
    private static void corpsKirin(VertexConsumer c, Matrix4f m, Vec3 nuque, Vec3 ciel, double k, float alpha) {
        int n = 28;
        long tranche = System.nanoTime() / 70_000_000L;
        List<Vec3> milieu = new ArrayList<>();
        for (int i = 0; i <= n; i++) {
            double s = i / (double) n;
            milieu.add(nuque.lerp(ciel, s).add(Math.sin(s * 5.5 + 0.3) * s * 3, 0, Math.cos(s * 3.7) * s * 2.5));
        }
        List<Vec3> gauche = new ArrayList<>(), droite = new ArrayList<>();
        Random r = new Random(tranche);
        for (int i = 0; i <= n; i++) {
            double s = i / (double) n, rayon = 7 * k * (1 - 0.55 * s);
            Vec3 a = milieu.get(i), b = milieu.get(Math.min(n, i + 1)).equals(a) ? milieu.get(i - 1) : milieu.get(Math.min(n, i + 1));
            Vec3 cote = b.subtract(a).cross(a).normalize();
            if (i == n) {
                cote = cote.scale(-1);
            }
            gauche.add(a.add(cote.scale(rayon * (1 + 0.15 * (r.nextDouble() - 0.5)))));
            droite.add(a.subtract(cote.scale(rayon * (1 + 0.15 * (r.nextDouble() - 0.5)))));
            if (i < n && passe == 0) {
                Vec3 a1 = milieu.get(i + 1);
                double r1 = 7 * k * (1 - 0.55 * (i + 1) / (double) n);
                Vec3 c1 = (i + 2 <= n ? milieu.get(i + 2) : a1.add(a1.subtract(a))).subtract(a1).cross(a1).normalize();
                for (double[] w : new double[][]{{1, 0.2}, {0.45, 0.16}}) {
                    float[] col = w[0] < 1 ? BLANC : CIEL;
                    sommet(c, m, a.add(cote.scale(rayon * w[0])), col, (float) w[1] * alpha);
                    sommet(c, m, a1.add(c1.scale(r1 * w[0])), col, (float) w[1] * alpha);
                    sommet(c, m, a1.subtract(c1.scale(r1 * w[0])), col, (float) w[1] * alpha);
                    sommet(c, m, a.subtract(cote.scale(rayon * w[0])), col, (float) w[1] * alpha);
                }
            }
        }
        trait(c, m, gauche, k * 0.45, alpha, CIEL);
        trait(c, m, droite, k * 0.45, alpha, CIEL);
        for (int q = 0; q < 3; q++) {
            List<Vec3> dedans = new ArrayList<>();
            for (int i = 0; i <= n; i += 2) {
                double s = i / (double) n;
                dedans.add(gauche.get(i).lerp(droite.get(i), 0.5 + 0.38 * Math.sin(q * 2.1 + s * (5 + q)) + 0.12 * (r.nextDouble() - 0.5)));
            }
            trait(c, m, dedans, k * 0.25, alpha * 0.7F, CIEL);
        }
        // des éclairs qui s'échappent de ses flancs, comme une crinière qui court le long du dos
        for (int q = 0; q < 10; q++) {
            int i = 1 + r.nextInt(n - 2);
            Vec3 a = (q % 2 == 0 ? gauche : droite).get(i);
            Vec3 d = a.subtract(milieu.get(i)).normalize().add(milieu.get(i + 1).subtract(milieu.get(i)).normalize()).normalize();
            eclair(c, m, a, a.add(d.scale(5 * k * (1 + r.nextDouble()))), r.nextLong(), k * 0.25, alpha * 0.8F, 0, CIEL);
        }
    }

    private static void dessinerKirin(VertexConsumer c, Matrix4f m, UUID qui, Kirin k, Vec3 cam) {
        Vec3 z = k.zone.subtract(cam);
        Vec3 ciel = z.add(0, HAUT_NUAGE, 0);
        double t = secondes(k.debut);
        float charge = charge(k);
        double tl = k.lache >= 0 ? secondes(k.lache) : -1;
        float fond = k.annule >= 0 ? (float) (1 - secondes(k.annule) / 0.8) : tl > IMPACT + 1 ? (float) (1 - (tl - IMPACT - 1) / 1.2) : 1;
        if (fond <= 0) {
            return;
        }
        // le gros nuage noir, qui grossit avec la charge, et ses éclairs
        double gros = lisse(t / 1.2) * (0.6 + 0.4 * charge);
        Random r = new Random(qui.getLeastSignificantBits());
        long tranche = System.nanoTime() / 100_000_000L;
        List<Vec3> boules = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            double a = r.nextDouble() * Math.PI * 2, d = Math.sqrt(r.nextDouble()) * 13 * gros;
            Vec3 o = ciel.add(Math.cos(a) * d, (r.nextDouble() - 0.5) * 4, Math.sin(a) * d);
            boules.add(o);
            boule(c, m, o, (4 + 3.5 * r.nextDouble()) * (0.4 + 0.6 * gros), new float[]{0.07F, 0.07F, 0.1F}, 0.82F * fond);
        }
        Random e = new Random(tranche * 31 + qui.getMostSignificantBits());
        for (int i = 0; i < 6; i++) {
            Vec3 a = boules.get(e.nextInt(boules.size())), b = boules.get(e.nextInt(boules.size()));
            if (a.distanceToSqr(b) > 9) {
                eclair(c, m, a.add(0, -2, 0), b.add(0, -2, 0), e.nextLong(), 0.1, fond, 2, CIEL);
            }
        }
        boolean charge0 = k.lache < 0 && k.annule < 0;
        if (charge0 && e.nextInt(3) == 0) {
            // un éclair qui tombe du nuage vers la zone, de temps en temps
            Vec3 a = boules.get(e.nextInt(boules.size())).add(0, -1.5, 0);
            eclair(c, m, a, a.add(e.nextGaussian() * 3, -6 - 6 * e.nextDouble(), e.nextGaussian() * 3), e.nextLong(), 0.06, fond * 0.8F, 1, CIEL);
        }
        // la zone : un anneau blanc au sol (les petits nuages blancs sont des particules)
        if (tl < IMPACT) {
            anneau(c, m, z.add(0, 0.06, 0), Foudre.RAYON_KIRIN - 0.3, 0.6, BLANC, 0.5F * fond);
        }
        // la tête : elle sort du nuage à la moitié de la barre, et rugit ; au lâcher, elle plonge d'un coup
        // très gros : la tête fait 4 à 5 blocs de large, son museau 7 ou 8 de long
        double taille = 0.32 + 0.1 * charge;
        double sortie = lisse((t / CHARGE - 0.45) / 0.3);
        Vec3 haut = ciel.add(0, -6 - 10 * sortie, 0).add(k.vers.scale(3));
        Vec3 bas = z.add(0, 18 * taille + 1, 0);
        Vec3 o;
        double plonge = tl < 0 ? 0 : lisse((tl - PLONGEE) / (IMPACT - PLONGEE));
        if (tl < 0 && sortie <= 0.01) {
            return;
        }
        o = haut.lerp(bas, plonge * plonge);
        Vec3 f = k.vers.add(0, -1.3 - 2 * plonge, 0).normalize();
        float alphaTete = (float) (tl < 0 ? sortie : 1) * fond * (tl > IMPACT ? (float) Math.max(0, 1 - (tl - IMPACT) / 0.4) : 1);
        if (alphaTete > 0.01F) {
            Tete tt = tete(o, f, new Vec3(0, 1, 0).add(k.vers.scale(0.5)), taille, tl >= PLONGEE ? 0.85 : 0.55 + 0.2 * Math.sin(t * 9));
            corpsKirin(c, m, tt.p(0, 0, -8), ciel, taille, alphaTete);
            teteKirin(c, m, tt, alphaTete, tl < 0 || tl < PLONGEE + 0.2);
        }
        // l'impact : un dôme de lumière, un éclat blanc, des éclairs qui filent au sol
        if (tl >= IMPACT) {
            double ti = tl - IMPACT;
            double rayon = Foudre.RAYON_KIRIN * 1.2 * (0.7 + 0.3 * charge) * (1 - Math.pow(1 - Math.min(1, ti / 0.35), 3));
            float a = (float) Math.max(0, 1 - ti / 1.4) * fond;
            dome(c, m, z, rayon, 0.75F * a);
            if (ti < 0.25) {
                boule(c, m, z.add(0, 1, 0), rayon * 0.6, BLANC, (float) (0.7 * (1 - ti / 0.25)));
            }
            anneau(c, m, z.add(0, 0.08, 0), rayon * (1 + ti), 1.5, BLANC, a * 0.7F);
            Random s = new Random(tranche * 13 + 5);
            for (int i = 0; i < 10; i++) {
                double ang = s.nextDouble() * Math.PI * 2;
                eclair(c, m, z.add(0, 0.2, 0), z.add(Math.cos(ang) * rayon * 1.2, 0.2 + s.nextDouble(), Math.sin(ang) * rayon * 1.2), s.nextLong(), 0.07, a, 1, CIEL);
            }
        }
    }

    // ================================================================ tout dessiner

    private static void dessiner(PoseStack pose, Vec3 cam, float partiel) {
        MultiBufferSource.BufferSource buffers = Minecraft.getInstance().renderBuffers().bufferSource();
        Matrix4f m = pose.last().pose();
        for (passe = 0; passe < 2; passe++) {
            RenderType type = passe == 0 ? Types.VOILE : Types.LUMIERE;
            tout(buffers.getBuffer(type), m, cam, partiel);
            buffers.endBatch(type);
        }
        passe = 0;
    }

    private static void tout(VertexConsumer c, Matrix4f m, Vec3 cam, float partiel) {
        Minecraft mc = Minecraft.getInstance();
        // les éveillés : des éclairs qui courent sur eux (jusqu'au sol au niveau 2) ; la foudre qui leur tombe dessus
        for (Map.Entry<UUID, Eveil> en : EVEILLES.entrySet()) {
            Player j = mc.level.getPlayerByUUID(en.getKey());
            if (j == null || j.isInvisible()) {
                continue;
            }
            // les éclairs sur lui sont accrochés à ses membres (voir eclairsSurLui) ; ici, seulement la foudre de l'éveil
            Vec3 pieds = j.getPosition(partiel).subtract(cam);
            boolean fort = en.getValue().niveau() >= 2;
            Long frappe = FRAPPES.get(en.getKey());
            if (frappe != null) {
                // la transformation (la charge, la cage, l'orage et la colonne : voir ClientTransfoFoudre)
                double t = secondes(frappe);
                int niveau = en.getValue().niveau();
                if (t < ClientTransfoFoudre.fin(niveau)) {
                    ClientTransfoFoudre.dessiner(c, m, j, pieds, niveau, t, partiel);
                }
            }
        }
        // les chocs d'un coup : des éclairs sur la cible
        for (Map.Entry<Integer, Choc> en : CHOCS.entrySet()) {
            Entity cible = mc.level.getEntity(en.getKey());
            if (cible == null) {
                continue;
            }
            Choc ch = en.getValue();
            float a = (float) (1 - secondes(ch.debut()) / (ch.fort() ? 0.9 : 0.5));
            crepite(c, m, cible.getPosition(partiel).subtract(cam), cible.getBbWidth() * 0.7 + 0.1, cible.getBbHeight(), ch.fort() ? 10 : 5,
                    ch.fort() ? 4 : 1, ch.fort() ? 3 : 0, en.getKey() * 17L, 0.03, Math.max(0, a));
        }
        // l'éclair en chaîne
        for (Chaine ch : CHAINES) {
            double t = secondes(ch.debut());
            float a = (float) Math.max(0, 1 - t / 0.45);
            long scintille = (long) (t * 20);
            for (int i = 0; i + 1 < ch.points().size(); i++) {
                eclair(c, m, ch.points().get(i).subtract(cam), ch.points().get(i + 1).subtract(cam), ch.graine() + i * 101L + scintille, i == 0 ? 0.06 : 0.045, a, 3, CIEL);
            }
        }
        // le pas de l'éclair : la traînée, la foudre qui tombe là où il arrive, l'onde au sol
        for (Saut s : SAUTS) {
            double t = secondes(s.debut());
            Vec3 de = s.de().subtract(cam), a = s.a().subtract(cam);
            float fin = (float) Math.max(0, 1 - t / (s.trois() ? 1.0 : 0.6));
            for (int i = 0; i < 3; i++) {
                eclair(c, m, de.add(0, 0.4 + i * 0.6, 0), a.add(0, 0.4 + i * 0.6, 0), s.debut() + i + (s.trois() ? (long) (t * 12) * 7 : 0), i == 1 ? 0.06 : 0.035, fin, 3, CIEL);
            }
            if (s.trois()) {
                // le niveau 3 : un gros trait de foudre qui reste là où il est passé, qui crépite, des éclairs au sol
                eclair(c, m, de.add(0, 1, 0), a.add(0, 1, 0), s.debut() * 5 + (long) (t * 12), 0.16, fin, 8, CIEL);
                Random r = new Random(s.debut() + (long) (t * 12));
                for (int i = 0; i < 6; i++) {
                    Vec3 p = de.lerp(a, r.nextDouble());
                    eclair(c, m, p.add(0, 1, 0), p.add(r.nextGaussian() * 1.5, 0.05, r.nextGaussian() * 1.5), r.nextLong(), 0.04, fin, 1, CIEL);
                }
                anneau(c, m, de.add(0, 0.06, 0), 3 * Math.min(1, t / 0.3), 1, BLANC, 0.7F * fin);
            }
            if (t < 0.4) {
                eclair(c, m, a.add(2, 40, -1.5), a, s.debut() * 3 + (long) (t * 15), s.fort() ? 0.18 : 0.13, (float) (1 - t / 0.4), 6, CIEL);
            }
            double rayon = s.fort() ? 4 : 3;
            anneau(c, m, a.add(0, 0.06, 0), rayon * Math.min(1, t / 0.3), 1.2, BLANC, 0.7F * fin);
        }
        for (Map.Entry<UUID, Kirin> en : KIRINS.entrySet()) {
            dessinerKirin(c, m, en.getKey(), en.getValue(), cam);
        }
        // l'ultime de la Foudre 3 : l'orage ; ses bêtes : le guépard, l'oiseau-tonnerre
        ClientOrage.dessiner(c, m, cam, partiel);
        ClientBetes.dessiner(c, m, cam, partiel);
    }

    /** Les membres du joueur, et leurs pavés (en pixels, dans le repère de chacun) : tête, corps, bras droit, bras gauche, jambes. */
    private static final float[][] PAVES = {{-4, -8, -4, 4, 0, 4}, {-4, 0, -2, 4, 12, 2}, {-3, -2, -2, 1, 10, 2}, {-1, -2, -2, 3, 10, 2},
            {-2, 0, -2, 2, 12, 2}, {-2, 0, -2, 2, 12, 2}};
    /** Les bras fins (skin « slim ») : 3 pixels de large. */
    private static final float[][] BRAS_FINS = {{-2, -2, -2, 1, 10, 2}, {-1, -2, -2, 2, 10, 2}};
    /**
     * En tenue de catch, le corps normal est caché sous le corps gonflé (voir CorpsCatcheur) : les pavés de ses muscles,
     * et la tête sous le masque.
     */
    private static final float[][] CATCHEUR = {{-5, -9, -5, 5, 1, 5}, {-6.1F, -2.2F, -4.1F, 6.1F, 12.2F, 3}, {-3.6F, -2.3F, -3.9F, 2.5F, 16.6F, 3.2F},
            {-2.5F, -2.3F, -3.9F, 3.6F, 16.6F, 3.2F}, {-3, -0.5F, -3.3F, 3, 12, 3.1F}, {-3, -0.5F, -3.3F, 3, 12, 3.1F}};
    /** D'un membre à son voisin (ou sur lui-même) : corps-bras, corps-tête, corps-jambes, jambe-jambe… */
    private static final int[][] VOISINS = {{1, 2}, {1, 3}, {1, 0}, {1, 4}, {1, 5}, {4, 5}, {2, 2}, {3, 3}, {0, 0}, {1, 1}, {4, 4}, {5, 5}};

    /** Le pavé du membre i : bras fins ou non, et en tenue de catch (ou sous un masque), celui du corps gonflé. */
    private static float[] pave(int i, boolean fin, boolean tenue, boolean masque) {
        if (i == 0 ? masque : tenue) {
            return CATCHEUR[i];
        }
        return fin && (i == 2 || i == 3) ? BRAS_FINS[i - 2] : PAVES[i];
    }

    /** Un point juste à côté de la surface d'un membre (à {@code ecart} pixels), à une hauteur au hasard, là où est ce membre. */
    private static Vec3 surLeMembre(Matrix4f membre, float[] b, Random r, float ecart) {
        double a = r.nextDouble() * Math.PI * 2;
        double hx = (b[3] - b[0]) / 2, hz = (b[5] - b[2]) / 2;
        double s = 1 / Math.max(Math.abs(Math.cos(a)) / hx, Math.abs(Math.sin(a)) / hz);
        double x = (b[0] + b[3]) / 2 + Math.cos(a) * (s + ecart), z = (b[2] + b[5]) / 2 + Math.sin(a) * (s + ecart);
        double y = b[1] + (b[4] - b[1]) * (0.08 + 0.84 * r.nextDouble());
        Vector3f v = membre.transformPosition((float) x / 16, (float) y / 16, (float) z / 16, new Vector3f());
        return new Vec3(v.x(), v.y(), v.z());
    }

    /**
     * Les éclairs qui courent sur lui, accrochés à ses membres : ils suivent ses bras, ses jambes et sa tête dans toutes
     * les positions (il court, s'accroupit, nage, vole), et ne s'en écartent que d'un ou deux pixels. Au niveau 2, il y en a
     * plus, et quelques-uns descendent de ses pieds jusqu'au sol, juste autour (debout ou accroupi seulement).
     */
    private static void eclairsSurLui(PoseStack ps, MultiBufferSource buffers, PlayerModel<?> modele, AbstractClientPlayer joueur, boolean fort) {
        ModelPart[] membres = {modele.head, modele.body, modele.rightArm, modele.leftArm, modele.rightLeg, modele.leftLeg};
        boolean fin = joueur.getSkin().model() == PlayerSkin.Model.SLIM;
        boolean tenue = CorpsCatcheur.enTenue(joueur), masque = Equipement.masque(joueur) >= 0;
        Matrix4f[] ou = new Matrix4f[membres.length];
        for (int i = 0; i < membres.length; i++) {
            // en tenue de catch, ses membres sont cachés mais toujours là, sous les muscles
            if (!membres[i].visible && !(tenue && i > 0)) {
                continue;
            }
            ps.pushPose();
            membres[i].translateAndRotate(ps);
            ou[i] = new Matrix4f(ps.last().pose());
            ps.popPose();
        }
        Matrix4f racine = new Matrix4f(ps.last().pose());
        long tranche = System.nanoTime() / 83_000_000L;
        Random r = new Random(tranche * 7919 + joueur.getId());
        float ecart = fort ? 1F : 0.5F;
        List<List<Vec3>> eclairs = new ArrayList<>();
        for (int k = 0; k < (fort ? 13 : 8); k++) {
            int[] v = VOISINS[r.nextInt(VOISINS.length)];
            if (ou[v[0]] == null || ou[v[1]] == null) {
                continue;
            }
            eclairs.add(zigzag(surLeMembre(ou[v[0]], pave(v[0], fin, tenue, masque), r, ecart),
                    surLeMembre(ou[v[1]], pave(v[1], fin, tenue, masque), r, ecart), r.nextLong(), 0.3, 3));
        }
        // quelques-uns s'échappent, à peine : une vingtaine de centimètres au plus
        for (int k = 0; k < (fort ? 3 : 1); k++) {
            int i = r.nextInt(membres.length);
            if (ou[i] == null) {
                continue;
            }
            float[] p = pave(i, fin, tenue, masque);
            Vec3 a = surLeMembre(ou[i], p, r, ecart);
            Vec3 loin = surLeMembre(ou[i], p, r, ecart + 3 + 2 * r.nextFloat());
            eclairs.add(zigzag(a, a.lerp(loin, 0.7), r.nextLong(), 0.35, 2));
        }
        // niveau 2, debout : des pieds jusqu'au sol, à moins d'un demi-bloc
        Pose pose = joueur.getPose();
        if (fort && (pose == Pose.STANDING || pose == Pose.CROUCHING)) {
            for (int k = 0; k < 3; k++) {
                int jambe = k % 2 == 0 ? 4 : 5;
                if (ou[jambe] == null) {
                    continue;
                }
                Vector3f pied = ou[jambe].transformPosition((float) (r.nextDouble() - 0.5) * 3 / 16, 11.5F / 16, (float) (r.nextDouble() - 0.5) * 3 / 16, new Vector3f());
                double ang = r.nextDouble() * Math.PI * 2, d = 4 + 4 * r.nextDouble();
                Vector3f sol = racine.transformPosition((float) ((jambe == 4 ? -2 : 2) + Math.cos(ang) * d) / 16, 23.8F / 16, (float) (Math.sin(ang) * d) / 16, new Vector3f());
                eclairs.add(zigzag(new Vec3(pied.x(), pied.y(), pied.z()), new Vec3(sol.x(), sol.y(), sol.z()), r.nextLong(), 0.3, 3));
            }
        }
        // les points sont déjà là où il faut (le repère des membres) : on les dessine tels quels, cœurs puis lueur
        Matrix4f rien = new Matrix4f();
        double e = fort ? 0.03 : 0.025;
        passe = 0;
        VertexConsumer c = buffers.getBuffer(Types.VOILE);
        for (List<Vec3> pts : eclairs) {
            trait(c, rien, pts, e, 1, CIEL);
        }
        passe = 1;
        c = buffers.getBuffer(Types.LUMIERE);
        for (List<Vec3> pts : eclairs) {
            trait(c, rien, pts, e, 1, CIEL);
        }
        passe = 0;
    }

    /** Les pixels des yeux qui brillent, {x, y, largeur} sur la tête : par défaut la rangée des yeux, deux pixels de chaque côté. */
    private static final int[][] YEUX_NORMAUX = {{-3, -4, 2}, {1, -4, 2}};
    /** Le skin à la capuche de lapin : ses grands yeux sont plus bas, et le haut du coin extérieur est sous les cheveux. */
    private static final int[][] YEUX_LAPIN = {{-2, -4, 1}, {-3, -3, 2}, {1, -4, 1}, {1, -3, 2}};
    private static final Map<ResourceLocation, int[][]> YEUX = new HashMap<>();

    private static int[][] yeux(ResourceLocation texture) {
        // au niveau 3, la tenue remplace le skin : on garde les yeux du vrai skin, à leur place, à tous les niveaux
        ResourceLocation peau = ClientTenueFoudre.vraie(texture);
        int[][] y = YEUX.get(peau);
        if (y == null) {
            int[] px = ClientMaudit.lire(peau);
            if (px == null) {
                return YEUX_NORMAUX;
            }
            y = estLapin(px) ? YEUX_LAPIN : YEUX_NORMAUX;
            YEUX.put(peau, y);
        }
        return y;
    }

    /** Reconnaît ce skin aux couleurs de ses yeux (le noir de la pupille, son reflet, de chaque côté ; ABGR, comme NativeImage). */
    private static boolean estLapin(int[] px) {
        return (px[12 * 64 + 10] & 0xFFFFFF) == 0x2B2E34 && (px[12 * 64 + 13] & 0xFFFFFF) == 0x2B2E34
                && (px[13 * 64 + 9] & 0xFFFFFF) == 0x373A42 && (px[13 * 64 + 14] & 0xFFFFFF) == 0x373A42
                && (px[14 * 64 + 9] & 0xFFFFFF) == 0xACB7C4 && (px[14 * 64 + 14] & 0xFFFFFF) == 0xACB7C4;
    }

    /** Les yeux qui brillent (juste leurs pixels, sans lueur autour), les éclairs sur lui et, au niveau 2, l'aura bleu clair. */
    public static final class Couche extends RenderLayer<AbstractClientPlayer, PlayerModel<AbstractClientPlayer>> {
        public Couche(RenderLayerParent<AbstractClientPlayer, PlayerModel<AbstractClientPlayer>> parent) {
            super(parent);
        }

        @Override
        public void render(PoseStack ps, MultiBufferSource buffers, int lumiere, AbstractClientPlayer joueur, float marche, float vitesse,
                float partiel, float age, float tete, float tangage) {
            Eveil e = EVEILLES.get(joueur.getUUID());
            if (e == null || joueur.isInvisible()) {
                return;
            }
            PlayerModel<AbstractClientPlayer> modele = getParentModel();
            if (e.niveau() >= 2) {
                Long frappe = FRAPPES.get(joueur.getUUID());
                float monte = frappe == null ? 1 : (float) ClientTransfoFoudre.aura(secondes(frappe), e.niveau());
                float vacille = 0.85F + 0.15F * Mth.sin(age * 0.45F);
                boolean fin = joueur.getSkin().model() == PlayerSkin.Model.SLIM;
                VertexConsumer aura = buffers.getBuffer(RenderType.entityTranslucentEmissive(ClientHachibi.MANTEAU));
                ClientHachibi.enveloppe(modele, ps, aura, fin, 1F, (Math.round(62 * monte * vacille) << 24) | 0x8CD8FF);
                ClientHachibi.enveloppe(modele, ps, aura, fin, 1.14F, (Math.round(22 * monte * vacille) << 24) | 0xB8E8FF);
            }
            // pas à la première personne (on n'y voit pas son propre corps)
            if (!(joueur == Minecraft.getInstance().player && Minecraft.getInstance().options.getCameraType().isFirstPerson())) {
                eclairsSurLui(ps, buffers, modele, joueur, e.niveau() >= 2);
            }
            if (!modele.head.visible) {
                return;
            }
            ps.pushPose();
            modele.head.translateAndRotate(ps);
            Matrix4f mat = ps.last().pose();
            VertexConsumer c = buffers.getBuffer(Types.VOILE);
            // juste devant le visage
            float z = -4.55F / 16;
            float[] col = {0.88F, 0.97F, 1F};
            for (int[] o : yeux(joueur.getSkin().texture())) {
                float x0 = o[0] / 16F, x1 = (o[0] + o[2]) / 16F, y0 = o[1] / 16F, y1 = (o[1] + 1) / 16F;
                c.addVertex(mat, x0, y0, z).setColor(col[0], col[1], col[2], 1F);
                c.addVertex(mat, x1, y0, z).setColor(col[0], col[1], col[2], 1F);
                c.addVertex(mat, x1, y1, z).setColor(col[0], col[1], col[2], 1F);
                c.addVertex(mat, x0, y1, z).setColor(col[0], col[1], col[2], 1F);
            }
            ps.popPose();
        }
    }

    // ================================================================ l'écran : le panneau, la barre du Kirin

    private static void ecran(GuiGraphics g) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.options.hideGui) {
            return;
        }
        Eveil e = EVEILLES.get(mc.player.getUUID());
        if (e != null) {
            panneau(g, mc.font, e);
        }
        Kirin k = KIRINS.get(mc.player.getUUID());
        if (enCharge(k)) {
            barre(g, mc.font, charge(k));
        }
        ClientBetes.barre(g, mc.font);
    }

    /** Le panneau, avec les touches de ce joueur (voir Clavier). */
    private static void panneau(GuiGraphics g, Font font, Eveil e) {
        long maintenant = System.nanoTime();
        double droit = Math.max(0, (pretDroit - maintenant) / 1e9), pas = Math.max(0, (pretPas - maintenant) / 1e9);
        boolean fort = e.niveau() >= 2;
        boolean trois = Foudre.NOM3.equals(e.pouvoir());
        String eveil = !fort && (Foudre.NOM2.equals(e.pouvoir()) || trois) ? "Niveau 2" : trois && e.niveau() == 2 ? "Niveau 3" : "Rendormir";
        String[][] lignes = {
                {Clavier.gauche(), fort ? "Gros choc" : "Choc électrique"},
                {fort ? Clavier.droite() + " tenu" : Clavier.droite(), PanneauPouvoir.recharge(fort ? "Kirin" : "Éclair en chaîne", droit)},
                {Clavier.accroupi(), PanneauPouvoir.recharge("Pas de l'éclair", pas)},
                {Clavier.nom(ClientSusano.EVEIL), eveil},
        };
        boolean[] gris = {false, droit > 0, pas > 0, false, false};
        if (e.niveau() >= 3) {
            String[][] l3 = ClientBetes.lignes();
            lignes = new String[][]{l3[0], l3[1], l3[2], l3[3], {Clavier.nom(fr.cabane.client.hachibi4.ClientHachibi4.ULTI), "L'Orage"},
                    {Clavier.nom(ClientSusano.EVEIL), "Rendormir"}};
            gris = new boolean[]{false, false, false, false, false, false};
        }
        String titre = "Foudre" + (fort ? "  ·  niv. " + e.niveau() : "");
        int teinte = 0xFF8CD8FF;
        PanneauPouvoir.dessiner(g, font, titre, teinte, lignes, gris);
    }

    /** La barre du Kirin, au-dessus de la barre d'objets : plus elle est pleine, plus il fera mal. */
    private static void barre(GuiGraphics g, Font font, float charge) {
        int w = 182, h = 9;
        int x = g.guiWidth() / 2 - w / 2, y = g.guiHeight() - 72;
        boolean plein = charge >= 1;
        int degats = Math.round(6 + 10 * charge);
        String titre = "KIRIN";
        String droite = plein ? "MAX · " + degats + " dégâts" : degats + " dégâts";
        g.drawString(font, titre, x, y - 11, 0xFF8CD8FF, true);
        g.drawString(font, droite, x + w - font.width(droite), y - 11, plein ? 0xFFFFFFFF : 0xFFBFE8FF, true);
        g.fill(x - 1, y - 1, x + w + 1, y + h + 1, 0xC0000000);
        int rempli = Math.round(w * charge);
        boolean clignote = plein && (System.nanoTime() / 150_000_000L) % 2 == 0;
        g.fillGradient(x, y, x + rempli, y + h, clignote ? 0xFFFFFFFF : 0xFF3FA8FF, clignote ? 0xFFDFF4FF : 0xFFB8E8FF);
        for (int i = 1; i < 4; i++) {
            g.fill(x + w * i / 4, y, x + w * i / 4 + 1, y + h, 0x60000000);
        }
        String aide = "Lâche " + Clavier.droite() + " pour le faire tomber · un coup reçu annule tout";
        g.drawString(font, aide, g.guiWidth() / 2 - font.width(aide) / 2, y + h + 4, 0xFFD0D0D0, true);
    }

    // ================================================================ branchements

    @EventBusSubscriber(modid = Cabane.MODID, value = Dist.CLIENT)
    public static final class Couches {
        @SubscribeEvent
        public static void enregistrer(RegisterGuiLayersEvent event) {
            event.registerAboveAll(Cabane.id("foudre"), (g, delta) -> ecran(g));
        }

        @SubscribeEvent
        public static void couches(EntityRenderersEvent.AddLayers event) {
            for (PlayerSkin.Model modele : event.getSkins()) {
                if (event.getSkin(modele) instanceof PlayerRenderer rendu) {
                    rendu.addLayer(new Couche(rendu));
                }
            }
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
                EVEILLES.clear();
                FRAPPES.clear();
                STATIQUES.clear();
                CHAINES.clear();
                SAUTS.clear();
                CHOCS.clear();
                KIRINS.clear();
                ClientBetes.vider();
                tenu = false;
                return;
            }
            long maintenant = System.nanoTime();
            Eveil moi = EVEILLES.get(mc.player.getUUID());
            // le Kirin : il lâche quand le clic droit n'est plus tenu
            if (tenu && (!mc.options.keyUse.isDown() || moi == null)) {
                tenu = false;
                PacketDistributor.sendToServer(new Foudre.Kirin(false));
            }
            // s'accroupir (l'appui, pas le maintien) : le pas de l'éclair
            boolean accroupi = mc.options.keyShift.isDown();
            if (moi != null && moi.niveau() >= 3) {
                // le niveau 3 : un appui sur le clic droit (le guépard) ou tenu (l'oiseau) ; le TP a trois charges
                ClientBetes.tick(mc);
                if (accroupi && !accroupiAvant && mc.screen == null && !ClientBetes.enCharge() && ClientBetes.prendreCharge()) {
                    PacketDistributor.sendToServer(new Foudre.Pas());
                }
            } else if (accroupi && !accroupiAvant && moi != null && mc.screen == null && maintenant >= pretPas && !enCharge(KIRINS.get(mc.player.getUUID()))) {
                pretPas = maintenant + (moi.niveau() >= 2 ? Foudre.RECHARGE_PAS_2 : Foudre.RECHARGE_PAS) * 50_000_000L;
                PacketDistributor.sendToServer(new Foudre.Pas());
            }
            accroupiAvant = accroupi;
            // l'électricité statique : un son qui crépite sur chaque éveillé tout proche
            STATIQUES.entrySet().removeIf(e -> !mc.getSoundManager().isActive(e.getValue()) && !EVEILLES.containsKey(e.getKey()));
            for (Map.Entry<UUID, Eveil> e : EVEILLES.entrySet()) {
                Player j = mc.level.getPlayerByUUID(e.getKey());
                if (j == null || j.distanceTo(mc.player) > 32) {
                    continue;
                }
                Statique s = STATIQUES.get(e.getKey());
                if (s == null || !mc.getSoundManager().isActive(s)) {
                    s = new Statique(j, e.getValue().niveau());
                    STATIQUES.put(e.getKey(), s);
                    mc.getSoundManager().play(s);
                }
                // des étincelles qui sautent de lui
                if (!j.isInvisible() && HASARD.nextInt(e.getValue().niveau() >= 2 ? 2 : 4) == 0) {
                    mc.level.addParticle(ParticleTypes.ELECTRIC_SPARK, j.getX() + HASARD.nextGaussian() * 0.22, j.getY() + HASARD.nextDouble() * j.getBbHeight(),
                            j.getZ() + HASARD.nextGaussian() * 0.22, HASARD.nextGaussian() * 0.02, 0.02, HASARD.nextGaussian() * 0.02);
                }
            }
            // la transformation : ses sons, ses étincelles (voir ClientTransfoFoudre)
            for (Map.Entry<UUID, Long> e : FRAPPES.entrySet()) {
                Player j = mc.level.getPlayerByUUID(e.getKey());
                Eveil ev = EVEILLES.get(e.getKey());
                if (j != null && ev != null && !j.isInvisible() && j.distanceTo(mc.player) < 64) {
                    ClientTransfoFoudre.tick(mc, j, ev.niveau(), secondes(e.getValue()));
                }
            }
            FRAPPES.values().removeIf(t -> (maintenant - t) / 1e9 > 5);
            CHAINES.removeIf(ch -> secondes(ch.debut()) > 0.5);
            SAUTS.removeIf(s -> secondes(s.debut()) > (s.trois() ? 1.1 : 0.7));
            ClientBetes.nettoyer();
            CHOCS.values().removeIf(ch -> secondes(ch.debut()) > (ch.fort() ? 0.9 : 0.5));
            for (Map.Entry<UUID, Kirin> e : KIRINS.entrySet()) {
                tickKirin(mc, e.getKey(), e.getValue());
            }
            KIRINS.values().removeIf(k -> k.annule >= 0 && secondes(k.annule) > 0.9 || k.lache >= 0 && secondes(k.lache) > IMPACT + 2.3);
        }

        /** Les petits nuages blancs autour de la zone, le rugissement quand la tête sort, et l'impact (secousse, étincelles). */
        private static void tickKirin(Minecraft mc, UUID qui, Kirin k) {
            double tl = k.lache >= 0 ? secondes(k.lache) : -1;
            Vec3 z = k.zone;
            if (k.annule < 0 && tl < IMPACT) {
                for (int i = 0; i < 4; i++) {
                    double a = HASARD.nextDouble() * Math.PI * 2, r = Foudre.RAYON_KIRIN * (0.95 + 0.1 * HASARD.nextDouble());
                    mc.level.addParticle(ParticleTypes.CLOUD, z.x + Math.cos(a) * r, z.y + 0.2 + HASARD.nextDouble() * 0.4, z.z + Math.sin(a) * r,
                            0, 0.01, 0);
                }
            }
            if (!k.sorti && enCharge(k) && secondes(k.debut) / CHARGE >= 0.5) {
                k.sorti = true;
                Vec3 t = z.add(0, HAUT_NUAGE - 6, 0);
                mc.level.playLocalSound(t.x, t.y, t.z, SoundEvents.ENDER_DRAGON_GROWL, SoundSource.PLAYERS, 2.5F, 1.3F, false);
                mc.level.playLocalSound(t.x, t.y, t.z, SoundEvents.RAVAGER_ROAR, SoundSource.PLAYERS, 2F, 0.9F, false);
            }
            if (!k.tombe && tl >= IMPACT) {
                k.tombe = true;
                if (mc.player.position().distanceTo(z) < 40) {
                    secouer(mc.player.position().distanceTo(z) < 15 ? 1.4F : 0.7F);
                }
                for (int i = 0; i < 120; i++) {
                    mc.level.addParticle(ParticleTypes.ELECTRIC_SPARK, z.x + HASARD.nextGaussian() * 3, z.y + HASARD.nextDouble() * 4, z.z + HASARD.nextGaussian() * 3,
                            HASARD.nextGaussian() * 0.4, 0.2 + HASARD.nextDouble() * 0.4, HASARD.nextGaussian() * 0.4);
                }
                for (int i = 0; i < 30; i++) {
                    double a = HASARD.nextDouble() * Math.PI * 2;
                    mc.level.addParticle(ParticleTypes.CLOUD, z.x, z.y + 0.3, z.z, Math.cos(a) * 0.6, 0.05, Math.sin(a) * 0.6);
                }
                mc.level.addParticle(ParticleTypes.EXPLOSION_EMITTER, z.x, z.y + 0.5, z.z, 0, 0, 0);
            }
        }

        /** Éveillé, le clic droit est à la foudre : l'éclair en chaîne (niveau 1), ou le Kirin, tenu (niveau 2). */
        @SubscribeEvent
        public static void clic(InputEvent.InteractionKeyMappingTriggered event) {
            Minecraft mc = Minecraft.getInstance();
            Eveil moi = mc.player == null ? null : EVEILLES.get(mc.player.getUUID());
            if (moi == null || !event.isUseItem()) {
                return;
            }
            event.setCanceled(true);
            event.setSwingHand(false);
            long maintenant = System.nanoTime();
            if (moi.niveau() >= 3) {
                ClientBetes.appui();
                return;
            }
            if (moi.niveau() >= 2) {
                if (!tenu && maintenant >= pretDroit && !enCharge(KIRINS.get(mc.player.getUUID()))) {
                    tenu = true;
                    PacketDistributor.sendToServer(new Foudre.Kirin(true));
                }
                return;
            }
            if (maintenant >= pretDroit) {
                pretDroit = maintenant + Foudre.RECHARGE_CHAINE * 50_000_000L;
                mc.player.swing(InteractionHand.MAIN_HAND);
                PacketDistributor.sendToServer(new Foudre.Tir());
            }
        }

        /** Pendant la charge du Kirin, il ne bouge pas (il peut regarder autour de lui). */
        @SubscribeEvent
        public static void immobile(MovementInputUpdateEvent event) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null || !enCharge(KIRINS.get(mc.player.getUUID())) && !ClientBetes.enCharge()) {
                return;
            }
            event.getInput().leftImpulse = 0;
            event.getInput().forwardImpulse = 0;
            event.getInput().jumping = false;
            event.getInput().up = false;
            event.getInput().down = false;
            event.getInput().left = false;
            event.getInput().right = false;
        }

        /** La foudre qui tombe tout près, le Kirin qui touche le sol : l'écran tremble. */
        @SubscribeEvent
        public static void tremblement(ViewportEvent.ComputeCameraAngles event) {
            if (secousse < 0) {
                return;
            }
            double apres = (System.nanoTime() - secousse) / 1e9;
            if (apres > 0.5) {
                secousse = -1;
                forceSecousse = 0;
                return;
            }
            float force = forceSecousse * (float) (1 - apres / 0.5);
            event.setRoll(event.getRoll() + (HASARD.nextFloat() - 0.5F) * 5 * force);
            event.setPitch(event.getPitch() + (HASARD.nextFloat() - 0.5F) * 3 * force);
        }

        @SubscribeEvent
        public static void monde(RenderLevelStageEvent event) {
            Minecraft mc = Minecraft.getInstance();
            if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES || mc.level == null
                    || EVEILLES.isEmpty() && CHAINES.isEmpty() && SAUTS.isEmpty() && CHOCS.isEmpty() && KIRINS.isEmpty() && !ClientOrage.actif() && !ClientBetes.actif()) {
                return;
            }
            dessiner(event.getPoseStack(), event.getCamera().getPosition(), event.getPartialTick().getGameTimeDeltaPartialTick(false));
        }

        private Evenements() {
        }
    }

    private ClientFoudre() {
    }
}
