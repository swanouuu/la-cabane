package fr.cabane.client.pouvoir;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import fr.cabane.Cabane;
import fr.cabane.pouvoir.Ichibi;
import fr.cabane.pouvoir.IchibiTempete;
import fr.cabane.pouvoir.Pouvoirs;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.PlayerSkin;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.RenderHandEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.event.RenderPlayerEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.joml.Matrix3f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Ichibi chez chaque joueur (voir {@link Ichibi} et docs/ichibi/planche-refonte.png). Les formes (la jarre, le sable qui
 * tourne, les poings, les cercueils, le rocher, la statue, la vague, la bête) viennent des mêmes dessins que la planche
 * (docs/ichibi/generateur/jeu.mjs → assets/cabane/ichibi/*.bin, lus par {@link Maillage}).
 * <ul>
 * <li>toujours (même endormi) : la grosse jarre de Gaara sur le dos, fermée par son bouchon ; à l'éveil le bouchon saute ;</li>
 * <li>niveau 1 : le sable de Gaara tourne autour de lui ; les poings de sable sortent de la jarre ;</li>
 * <li>niveau 2 : la moitié droite du corps en sable d'Ichibi (le grand bras, l'oreille, l'œil, les piques, la queue) ;
 * à l'éveil, la jarre se brise et s'effondre (le sable coule sur l'épaule), puis il n'y en a plus ;</li>
 * <li>niveau 3 : toujours plus de jarre, la bête de sable (tête de bête, deux grands bras en arc, grosse queue)
 * mais le bas des jambes reste à lui ; il court penché ;</li>
 * <li>la tempête de l'ulti : le mur de sable qui arrive de l'horizon, puis le sable partout ; chez les autres, un
 * brouillard de sable épais (lui voit à travers).</li>
 * </ul>
 */
public final class ClientIchibi {
    /** Le sable du bras, de la queue, de l'oreille et du nuage : 256×256 pour une taille déclarée de 64×64. */
    private static final ResourceLocation SABLE = Cabane.id("textures/entity/ichibi/ichibi.png");
    /** La moitié du corps, sur la disposition d'une peau de joueur, quatre fois plus fine (pour l'œil). */
    private static final ResourceLocation MOITIE = Cabane.id("textures/entity/ichibi/moitie.png");
    /** Les modèles faits de faces colorées sont dessinés sur du blanc. */
    private static final ResourceLocation BLANC = Cabane.id("textures/marque/blanc.png");
    private static final ResourceLocation BOUFFEE = Cabane.id("textures/marque/bouffee.png");
    private static final RandomSource HASARD = RandomSource.create();
    private static final double DUREE_SERRE = 0.35;
    private static final double DUREE_COUP = 0.38;
    private static final double DUREE_LANCE = 0.45;
    /** Les poings de sable du niveau 1 retombent en sable 1 s après le dernier clic. */
    private static final double POINGS = 1.0;
    /** Le temps (en secondes) de la tempête : le mur arrive, puis elle dure. */
    private static final double FORME = IchibiTempete.FORME / 20.0, TEMPETE = IchibiTempete.DUREE / 20.0, EFFONDRE = IchibiTempete.EFFONDRE / 20.0, RETOMBE = 4.2;
    private static final float[] SABLE_C = {0.86F, 0.74F, 0.55F}, SABLE_F = {0.66F, 0.52F, 0.34F};

    /** Un Ichibi éveillé (en temps du client). */
    private static final class Ichi {
        int niveau;
        /** Le niveau le plus haut de sa liste blanche (ichibi, ichibi2, ichibi3). */
        int max = 3;
        /** Depuis quand il est à ce niveau (le sable le prend). */
        long debut;
        /** Le bouchon a sauté à ce moment-là (-1 : pas vu). */
        long bouchon = -1;
        long coup = -1;
        long lance = -1;
        long serre = -1;
        int prise = -1;
        long nuage = -1;
        long nuageFin = -1;
        boolean petitNuage;
        /** Niveau 1 : les deux poings (0 droit, 1 gauche) : quand ils sont partis, jusqu'où, et le dernier clic. */
        final long[] poing = {-1, -1};
        final Vec3[] bout = new Vec3[2];
        long dernierPoing = -1;
        /** Niveau 3 : la dernière griffe et son côté ; la vague ; les cercueils ; le rire (la gueule ouverte). */
        long griffe = -1;
        int cote;
        long vague = -1;
        long cercueils = -1;
        long rire = -1;
        /** Niveau 2 : il arrache un rocher. */
        long rocher = -1;
        /** La jarre s'est cassée à ce moment-là (en arrivant au niveau 2 ou plus) ; -1 : elle est entière. */
        long casse = -1;

        Ichi(long debut) {
            this.debut = debut;
        }
    }

    private static final Map<UUID, Ichi> ICHIS = new HashMap<>();
    /** Ceux qui portent la jarre (le pouvoir, même endormis). */
    private static final Set<UUID> PORTEURS = new HashSet<>();

    private static double secondes(long depuis) {
        return (System.nanoTime() - depuis) / 1e9;
    }

    private static float lisse(double t) {
        float k = (float) Mth.clamp(t, 0, 1);
        return k * k * (3 - 2 * k);
    }

    // ================================================================ ce que dit le serveur

    public static void porteurs(Ichibi.Porteurs p) {
        PORTEURS.clear();
        PORTEURS.addAll(p.joueurs());
    }

    public static void recevoir(Pouvoirs.Etat p) {
        Minecraft mc = Minecraft.getInstance();
        boolean moi = mc.player != null && p.joueur().equals(mc.player.getUUID());
        if (!p.eveille()) {
            ICHIS.remove(p.joueur());
            if (moi) {
                pretPrise = pretNuage = pretCercueil = pretBoule = pretRocher = pretMausolee = pretVague = finVol = 0;
                tempeteFaite = false;
            }
            return;
        }
        long maintenant = System.nanoTime();
        Ichi i = ICHIS.get(p.joueur());
        if (i == null) {
            i = new Ichi(p.annonce() ? maintenant : maintenant - 10_000_000_000L);
            ICHIS.put(p.joueur(), i);
            if (p.annonce()) {
                i.bouchon = maintenant;
            }
        } else if (i.niveau != p.niveau()) {
            i.debut = p.annonce() ? maintenant : maintenant - 10_000_000_000L;
        }
        if (p.annonce() && p.niveau() >= 2 && i.niveau != p.niveau()) {
            i.rire = maintenant;
        }
        if (p.niveau() >= 2 && i.niveau < 2) {
            i.casse = p.annonce() ? maintenant : maintenant - 10_000_000_000L;
        }
        i.niveau = p.niveau();
        i.max = Ichibi.max(p.pouvoir());
        i.prise = -1;
    }

    public static void oublier(UUID joueur) {
        ICHIS.remove(joueur);
    }

    public static void geste(Pouvoirs.Geste p) {
        Ichi i = ICHIS.get(p.joueur());
        if (i == null) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        boolean moi = mc.player != null && p.joueur().equals(mc.player.getUUID());
        long maintenant = System.nanoTime();
        switch (p.geste()) {
            case Ichibi.GESTE_COUP -> i.coup = maintenant;
            case Ichibi.GESTE_PRISE -> {
                i.prise = p.cible();
                if (moi && p.cible() >= 0) {
                    pretPrise = maintenant + ticks(Ichibi.RECHARGE_PRISE);
                }
            }
            case Ichibi.GESTE_SERRE -> i.serre = maintenant;
            case Ichibi.GESTE_LANCE -> {
                i.prise = -1;
                i.lance = maintenant;
            }
            case Ichibi.GESTE_NUAGE -> {
                if (p.cible() >= 1) {
                    i.nuage = maintenant;
                    i.nuageFin = -1;
                    i.petitNuage = p.cible() == 2;
                    if (moi) {
                        finVol = maintenant + Ichibi.dureeNuage(i.petitNuage ? 1 : 2) * 50_000_000L;
                    }
                } else {
                    i.nuageFin = maintenant;
                    if (moi) {
                        finVol = 0;
                        pretNuage = maintenant + ticks(Ichibi.RECHARGE_NUAGE);
                    }
                }
            }
            default -> {
            }
        }
    }

    /** Une recharge (en ticks) en nanosecondes. */
    private static long ticks(int t) {
        return t * 50_000_000L;
    }

    // ================================================================ les effets de sable

    /** Un cercueil : sur qui, depuis quand (le sable d'Ichibi ou de Gaara), quand il a serré, quand il s'effrite. */
    private static final class Cercueil {
        final UUID lanceur;
        final int cible;
        final long debut;
        final boolean ichibi;
        final Vec3 depart;
        long serre = -1;
        long fin = -1;
        Vec3 dernier;

        Cercueil(UUID lanceur, int cible, long debut, boolean ichibi, Vec3 depart) {
            this.lanceur = lanceur;
            this.cible = cible;
            this.debut = debut;
            this.ichibi = ichibi;
            this.depart = depart;
            this.dernier = depart;
        }
    }

    /** Une boule de sable ou un rocher en vol : rejoué ici (même départ, même vitesse que chez le serveur). */
    private static final class Tir {
        final int numero;
        final boolean rocher;
        final Vec3 depart;
        final Vec3 vitesse;
        final long debut;
        long fin = -1;
        Vec3 ou;

        Tir(int numero, boolean rocher, Vec3 depart, Vec3 vitesse, long debut) {
            this.numero = numero;
            this.rocher = rocher;
            this.depart = depart;
            this.vitesse = vitesse;
            this.debut = debut;
            this.ou = depart;
        }

        /** Où il est au bout de t ticks. */
        Vec3 position(double t) {
            Vec3 p = depart.add(vitesse.scale(t));
            return rocher ? p.add(0, -Ichibi.CHUTE_ROCHER * t * (t - 1) / 2, 0) : p;
        }
    }

    private record Statue(int cible, long debut, Vec3 pieds, Vec3 face, boolean trois, long[] fin) {
    }

    private record Vague(Vec3 depart, Vec3 dir, double longueur, long debut) {
    }

    /** La tempête d'un Ichibi : depuis quand, d'où arrive le mur, où (si on ne le voit plus), quand elle retombe. */
    private static final class Tempete {
        final long debut;
        final Vec3 dir;
        Vec3 centre;
        final Vec3 depart;
        long fin = -1;
        /** Elle s'est finie d'elle-même : elle explose. */
        boolean explose;
        /** L'ouragan s'effondre sur lui-même (juste avant d'exploser). */
        long effondre = -1;

        Tempete(long debut, Vec3 dir, Vec3 centre) {
            this.debut = debut;
            this.dir = dir;
            this.centre = centre;
            this.depart = centre;
        }
    }

    private static final List<Cercueil> CERCUEILS = new ArrayList<>();
    private static final List<Tir> TIRS = new ArrayList<>();
    private static final List<Statue> STATUES = new ArrayList<>();
    private static final List<Vague> VAGUES = new ArrayList<>();
    private static final Map<UUID, Tempete> TEMPETES = new HashMap<>();

    private record Griffure(Vec3 pos, Vec3 dir, int cote, long debut) {
    }

    /** Des pics de sable qui jaillissent (debut peut être un peu plus tard : ils sortent en vague). */
    private static final class Pic {
        final Vec3 pos;
        final long debut;
        final float taille;
        boolean vu;

        Pic(Vec3 pos, long debut, float taille) {
            this.pos = pos;
            this.debut = debut;
            this.taille = taille;
        }
    }

    /** Une main géante de la tempête : sur qui, depuis quand, où elle sort, vers où sa paume regarde. */
    private static final class MainG {
        final int cible;
        final long debut;
        final Vec3 pieds;
        final Vec3 face;
        Vec3 dernier;
        boolean abattue;

        MainG(int cible, long debut, Vec3 pieds, Vec3 face) {
            this.cible = cible;
            this.debut = debut;
            this.pieds = pieds;
            this.face = face;
            this.dernier = pieds;
        }
    }

    /** Un éclair de sable : d'où il tombe, sur qui, depuis quand. */
    private static final class EclairT {
        final Vec3 haut;
        final Vec3 pos;
        final int cible;
        final long debut;
        final int graine;
        boolean vu;

        EclairT(Vec3 haut, Vec3 pos, int cible, long debut, int graine) {
            this.haut = haut;
            this.pos = pos;
            this.cible = cible;
            this.debut = debut;
            this.graine = graine;
        }
    }

    private static final List<MainG> MAINS_T = new ArrayList<>();
    private static final List<EclairT> ECLAIRS_T = new ArrayList<>();
    private static final List<Griffure> GRIFFURES = new ArrayList<>();
    private static final List<Pic> PICS = new ArrayList<>();

    private static Vec3 v(Vector3f f) {
        return new Vec3(f.x, f.y, f.z);
    }

    public static void effet(Ichibi.Effet p) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return;
        }
        boolean moi = mc.player != null && p.joueur().equals(mc.player.getUUID());
        long maintenant = System.nanoTime();
        Ichi i = ICHIS.get(p.joueur());
        Vec3 pos = v(p.pos()), dir = v(p.dir());
        switch (p.sorte()) {
            case Ichibi.E_POING -> {
                if (i != null) {
                    int c = p.phase() & 1;
                    i.poing[c] = maintenant;
                    i.bout[c] = pos;
                    i.dernierPoing = maintenant;
                }
            }
            case Ichibi.E_GRIFFE -> {
                if (p.phase() == 2) {
                    // les deux bras s'abattent : une vague de pics jaillit devant lui
                    if (i != null) {
                        i.vague = maintenant;
                    }
                    Vec3 gauche = new Vec3(dir.z, 0, -dir.x);
                    for (int r = 0; r < 4; r++) {
                        double d = 2.2 + r * 1.25;
                        for (int k = -r - 1; k <= r + 1; k += 2) {
                            Vec3 ou = pos.add(dir.scale(d)).add(gauche.scale(k * 0.75));
                            PICS.add(new Pic(ou, maintenant + (long) (r * 0.06e9), 0.55F + r * 0.12F));
                        }
                    }
                    eclat(mc, pos.add(dir.scale(3)), 120, 2.5);
                } else {
                    if (i != null) {
                        i.griffe = maintenant;
                        i.cote = p.phase();
                    }
                    GRIFFURES.add(new Griffure(pos, dir, p.phase(), maintenant));
                    griffures(mc, p.joueur(), p.phase(), dir);
                }
            }
            case Ichibi.E_CERCUEIL -> {
                if (p.phase() == 0) {
                    CERCUEILS.add(new Cercueil(p.joueur(), p.n(), maintenant, dir.x > 0.5, pos));
                    if (i != null && i.niveau >= 3) {
                        i.cercueils = maintenant;
                    }
                    if (moi && i != null) {
                        pretCercueil = maintenant + ticks(i.niveau >= 3 ? Ichibi.RECHARGE_CERCUEILS : i.niveau == 2 ? Ichibi.RECHARGE_CERCUEIL_2 : Ichibi.RECHARGE_CERCUEIL);
                    }
                } else {
                    for (Cercueil c : CERCUEILS) {
                        if (c.lanceur.equals(p.joueur()) && c.cible == p.n()) {
                            if (p.phase() == 1) {
                                c.serre = maintenant;
                            } else if (c.fin < 0) {
                                c.fin = maintenant;
                            }
                        }
                    }
                }
            }
            case Ichibi.E_BOULE, Ichibi.E_ROCHER -> {
                boolean rocher = p.sorte() == Ichibi.E_ROCHER;
                if (p.phase() == 0) {
                    if (i != null) {
                        i.rocher = maintenant;
                        i.lance = -1;
                    }
                    if (moi) {
                        pretRocher = maintenant + ticks(Ichibi.RECHARGE_ROCHER);
                    }
                } else if (p.phase() == 1) {
                    TIRS.add(new Tir(p.n(), rocher, pos, dir, maintenant));
                    if (i != null && rocher) {
                        i.rocher = -1;
                        i.lance = maintenant;
                    }
                    if (moi && !rocher) {
                        pretBoule = maintenant + ticks(Ichibi.RECHARGE_BOULE);
                    }
                } else {
                    for (Tir t : TIRS) {
                        if (t.numero == p.n() && t.fin < 0) {
                            t.fin = maintenant;
                            t.ou = pos;
                        }
                    }
                    eclat(mc, pos, rocher ? 80 : 30, rocher ? 1.4 : 0.5);
                }
            }
            case Ichibi.E_STATUE -> {
                if (p.phase() < 2) {
                    STATUES.add(new Statue(p.n(), maintenant, pos, dir, p.phase() == 1, new long[] {-1}));
                    if (moi) {
                        pretMausolee = maintenant + ticks(Ichibi.RECHARGE_MAUSOLEE);
                    }
                } else {
                    for (Statue s : STATUES) {
                        if (s.cible() == p.n() && s.fin()[0] < 0) {
                            s.fin()[0] = maintenant;
                        }
                    }
                }
            }
            case Ichibi.E_VAGUE -> {
                VAGUES.add(new Vague(pos, dir, p.n(), maintenant));
                if (i != null) {
                    i.vague = maintenant;
                }
                if (moi) {
                    pretVague = maintenant + ticks(Ichibi.RECHARGE_VAGUE);
                }
            }
            case Ichibi.E_TEMPETE -> {
                if (p.phase() == 0) {
                    TEMPETES.put(p.joueur(), new Tempete(maintenant, dir, pos));
                    if (i != null) {
                        i.rire = maintenant;
                    }
                    if (moi) {
                        tempeteFaite = true;
                    }
                } else if (p.phase() == 2) {
                    PICS.add(new Pic(pos, maintenant, 1.3F));
                } else if (p.phase() == 3) {
                    // l'éclair tombe du haut de l'ouragan
                    Tempete t = TEMPETES.get(p.joueur());
                    Vec3 c = t == null ? pos : t.centre;
                    double a = HASARD.nextDouble() * Math.PI * 2;
                    Vec3 haut = c.add(pos.subtract(c).scale(0.4)).add(Math.cos(a) * 12, 34, Math.sin(a) * 12);
                    ECLAIRS_T.add(new EclairT(haut, pos, p.n(), maintenant, HASARD.nextInt(10_000)));
                } else if (p.phase() == 4) {
                    MAINS_T.add(new MainG(p.n(), maintenant, pos, dir));
                } else if (p.phase() == 5) {
                    Tempete t = TEMPETES.get(p.joueur());
                    if (t != null) {
                        t.effondre = maintenant;
                    }
                    if (i != null) {
                        i.rire = maintenant;
                    }
                } else {
                    Tempete t = TEMPETES.get(p.joueur());
                    if (t != null && t.fin < 0) {
                        t.fin = maintenant;
                        t.explose = p.n() == 1;
                        if (t.explose) {
                            eclat(mc, t.centre.add(0, 1, 0), 300, 6);
                        }
                    }
                }
            }
            default -> {
            }
        }
    }

    /** Du sable qui éclate là. */
    private static void eclat(Minecraft mc, Vec3 p, int n, double r) {
        BlockParticleOption sable = new BlockParticleOption(ParticleTypes.BLOCK, Blocks.SAND.defaultBlockState());
        for (int k = 0; k < n; k++) {
            mc.level.addParticle(sable, p.x + (HASARD.nextDouble() - 0.5) * r * 2, p.y + (HASARD.nextDouble() - 0.5) * r * 2, p.z + (HASARD.nextDouble() - 0.5) * r * 2,
                    (HASARD.nextDouble() - 0.5) * 0.6, HASARD.nextDouble() * 0.4, (HASARD.nextDouble() - 0.5) * 0.6);
        }
    }

    /** Les griffes de la bête : un arc de sable devant lui, de son côté vers l'autre. */
    private static void griffures(Minecraft mc, UUID qui, int cote, Vec3 dir) {
        Player j = mc.level.getPlayerByUUID(qui);
        if (j == null) {
            return;
        }
        Vec3 gauche = new Vec3(dir.z, 0, -dir.x);
        BlockParticleOption sable = new BlockParticleOption(ParticleTypes.BLOCK, Blocks.SAND.defaultBlockState());
        for (int k = 0; k < 46; k++) {
            double a = (k / 45.0 - 0.5) * 2.2 * (cote == 0 ? 1 : -1), r = 3 + HASARD.nextDouble() * 2.5;
            Vec3 p = j.position().add(dir.scale(Math.cos(a) * r)).add(gauche.scale(Math.sin(a) * r)).add(0, 0.6 + HASARD.nextDouble() * 1.4, 0);
            mc.level.addParticle(sable, p.x, p.y, p.z, dir.x * 0.3, 0.05, dir.z * 0.3);
        }
    }

    // ================================================================ les modèles

    private static boolean charges;
    private static Maillage[] jarre, jarreCassee, tourbillon, poingD, poingG, tube, cercueil, cercueilI, boule, rocher, statue, vague, bete, beteBras, pics, lame, mainGeante;

    private static Maillage[] lire(String nom) {
        return Maillage.lire(Cabane.id("ichibi/" + nom + ".bin"));
    }

    private static void charger() {
        if (charges) {
            return;
        }
        charges = true;
        jarre = lire("jarre");
        jarreCassee = lire("jarre_cassee");
        tourbillon = lire("tourbillon");
        poingD = lire("poing_d");
        poingG = lire("poing_g");
        tube = lire("tube");
        cercueil = lire("cercueil");
        cercueilI = lire("cercueil_ichibi");
        boule = lire("boule");
        rocher = lire("rocher");
        statue = lire("statue");
        vague = lire("vague");
        bete = lire("bete");
        beteBras = lire("bete_bras");
        pics = lire("pics");
        lame = lire("lame");
        mainGeante = lire("main_geante");
    }

    /** Le morceau k de ce modèle, ou null s'il manque. */
    private static Maillage m(Maillage[] l, int k) {
        return l == null || k < 0 || k >= l.length ? null : l[k];
    }

    /** Dessine ce modèle (en pixels) dans le repère courant. */
    private static void dessiner(Maillage m, VertexConsumer c, PoseStack ps, int lumiere, int dessous) {
        if (m != null) {
            m.dessiner(c, ps.last(), lumiere, dessous, 1 / 16F);
        }
    }

    /** Le repère d'un dessin de la planche dans le monde : en o, son +z vers f (à plat), y vers le haut. */
    private static void repere(PoseStack ps, Vec3 o, Vec3 f) {
        ps.translate(o.x, o.y, o.z);
        ps.mulPose(Axis.YP.rotation((float) Math.atan2(f.x, f.z)));
    }

    /** Un bout de tube de sable de a à b (dans le repère courant, en blocs), r fois l'épaisseur du tube (un demi-bloc). */
    private static void segment(PoseStack ps, VertexConsumer c, Maillage m, Vec3 a, Vec3 b, float r, int lumiere) {
        Vec3 d = b.subtract(a);
        double l = d.length();
        if (l < 1e-3 || m == null) {
            return;
        }
        ps.pushPose();
        ps.translate(a.x, a.y, a.z);
        ps.mulPose(new Quaternionf().rotationTo(0, 1, 0, (float) (d.x / l), (float) (d.y / l), (float) (d.z / l)));
        ps.scale(r, (float) l * 1.08F, r);
        dessiner(m, c, ps, lumiere, OverlayTexture.NO_OVERLAY);
        ps.popPose();
    }

    private static Vec3 bezier(Vec3 a, Vec3 b, Vec3 c, Vec3 d, double t) {
        double u = 1 - t;
        return a.scale(u * u * u).add(b.scale(3 * u * u * t)).add(c.scale(3 * u * t * t)).add(d.scale(t * t * t));
    }

    /** Un filet de sable le long d'une courbe (quatre points), de t0 à t1, de l'épaisseur r0 à r1. */
    private static void filet(PoseStack ps, VertexConsumer c, Maillage m, Vec3 a, Vec3 b, Vec3 cc, Vec3 d, double t0, double t1, float r0, float r1, int lumiere) {
        int n = Math.max(3, (int) (a.distanceTo(d) / 0.45));
        Vec3 avant = bezier(a, b, cc, d, t0);
        for (int k = 1; k <= n; k++) {
            double t = t0 + (t1 - t0) * k / n;
            Vec3 ici = bezier(a, b, cc, d, t);
            segment(ps, c, m, avant, ici, Mth.lerp((float) ((t - t0) / Math.max(1e-3, t1 - t0)), r0, r1), lumiere);
            avant = ici;
        }
    }

    /** Un point du modèle de ce joueur (en pixels de la planche : y depuis ses pieds, +z devant, +x à sa gauche), dans le monde. */
    private static Vec3 local(Player j, float partiel, double x, double y, double z) {
        float lacet = Mth.rotLerp(partiel, j.yBodyRotO, j.yBodyRot) * Mth.DEG_TO_RAD;
        Vec3 devant = new Vec3(-Mth.sin(lacet), 0, Mth.cos(lacet)), gauche = new Vec3(devant.z, 0, -devant.x);
        double e = 0.9375 / 16;
        return j.getPosition(partiel).add(gauche.scale(x * e)).add(0, y * e, 0).add(devant.scale(z * e));
    }

    /** Le goulot de sa jarre, dans le monde (à la première personne, en bas de la vue : sinon le sable cacherait tout). */
    private static Vec3 goulot(Player j, float partiel) {
        Minecraft mc = Minecraft.getInstance();
        if (j == mc.player && mc.options.getCameraType().isFirstPerson()) {
            Camera cam = mc.gameRenderer.getMainCamera();
            Vector3f d = new Vector3f(cam.getLookVector()).mul(-0.2F).add(new Vector3f(cam.getUpVector()).mul(-0.75F));
            return cam.getPosition().add(d.x, d.y, d.z);
        }
        return local(j, partiel, -2.7, 31.6, -7.9);
    }

    // ================================================================ les morceaux de sable du niveau 2 (comme avant)

    private static ModelPart haut, avant, segment, paume, doigt1, doigt2, griffe, pouce, pique1, pique2, boutQueue, nuage;
    private static ModelPart grandePaume, doigtFlanc, doigtDos, doigtDosLong, jointure, grandPouce;
    private static final ModelPart[] QUEUE = new ModelPart[12];
    private static final ModelPart[] OREILLE = new ModelPart[4];
    private static final Vector3f[] POINTS_QUEUE = new Vector3f[QUEUE.length + 1];

    private static ModelPart boite(int u, int v, float x, float y, float z, float w, float h, float d) {
        MeshDefinition m = new MeshDefinition();
        m.getRoot().addOrReplaceChild("b", CubeListBuilder.create().texOffs(u, v).addBox(x, y, z, w, h, d), PartPose.ZERO);
        return LayerDefinition.create(m, 64, 64).bakeRoot().getChild("b");
    }

    private static double bruit(double x, double y, int k) {
        int i = Mth.floor(x), j = Mth.floor(y);
        double fx = x - i, fy = y - j, lx = fx * fx * (3 - 2 * fx), ly = fy * fy * (3 - 2 * fy);
        double a = hache(i, j, k), b = hache(i + 1, j, k), c = hache(i, j + 1, k), d = hache(i + 1, j + 1, k);
        return Mth.lerp(ly, Mth.lerp(lx, a, b), Mth.lerp(lx, c, d)) * 2 - 1;
    }

    private static double hache(int i, int j, int k) {
        int h = i * 374761393 + j * 668265263 + k * 1274126177;
        h = (h ^ (h >>> 13)) * 1274126177;
        return ((h ^ (h >>> 16)) >>> 1) / (double) Integer.MAX_VALUE;
    }

    private static void modeles() {
        charger();
        if (haut != null) {
            return;
        }
        haut = boite(0, 0, -3.5F, -3, -3.5F, 7, 11, 7);
        avant = boite(28, 0, -4, -1, -4, 8, 10, 8);
        segment = boite(0, 20, -4, 0, -4, 8, 4.4F, 8);
        paume = boite(0, 34, -2, 0, -4, 4, 5, 8);
        doigt1 = boite(26, 34, -0.9F, 0, -0.9F, 1.8F, 3.4F, 1.8F);
        doigt2 = boite(34, 34, -0.8F, 0, -0.8F, 1.6F, 2.8F, 1.6F);
        griffe = boite(32, 48, -0.6F, 0, -0.6F, 1.2F, 2.6F, 1.2F);
        pouce = boite(42, 34, -0.95F, 0, -0.95F, 1.9F, 3.4F, 1.9F);
        pique1 = boite(50, 34, -0.9F, 0, -0.9F, 1.8F, 2.6F, 1.8F);
        pique2 = boite(58, 34, -0.5F, 0, -0.5F, 1F, 2.4F, 1F);
        grandePaume = boite(0, 0, 0, 0, 0, 13.2F, 3.8F, 14.8F);
        doigtFlanc = boite(0, 20, 0, 0, 0, 2.8F, 10.8F, 2.8F);
        doigtDos = boite(20, 20, 0, 0, 0, 7.8F, 2.8F, 2.8F);
        doigtDosLong = boite(20, 26, 0, 0, 0, 9F, 2.8F, 2.8F);
        jointure = boite(44, 20, 0, 0, 0, 1.5F, 3, 3.4F);
        grandPouce = boite(12, 34, 0, 0, 0, 2.8F, 7.6F, 3.4F);
        Vector3f p0 = new Vector3f(-0.8F, 11, 3.2F), p1 = new Vector3f(-1.2F, 18, 17), p2 = new Vector3f(-2.2F, -9, 25), p3 = new Vector3f(-1, -17, 13);
        for (int s = 0; s <= QUEUE.length; s++) {
            float t = s / (float) QUEUE.length, u = 1 - t;
            POINTS_QUEUE[s] = new Vector3f(p0).mul(u * u * u).add(new Vector3f(p1).mul(3 * u * u * t)).add(new Vector3f(p2).mul(3 * u * t * t))
                    .add(new Vector3f(p3).mul(t * t * t));
        }
        for (int s = 0; s < QUEUE.length; s++) {
            float w = 8.8F - 4.4F * s / (QUEUE.length - 1), l = POINTS_QUEUE[s].distance(POINTS_QUEUE[s + 1]);
            QUEUE[s] = boite(0, 20, -w / 2, -0.7F, -w / 2, w, l + 1.4F, w);
        }
        boutQueue = boite(48, 48, -1.8F, 0, -1.8F, 3.6F, 3.8F, 3.6F);
        for (int k = 0; k < OREILLE.length; k++) {
            float w = 4.4F - k, e = 1.6F - k * 0.15F;
            OREILLE[k] = k == 3 ? boite(48, 48, -w / 2, 0, -e / 2, w, 2.1F, e) : boite(44, 20, -w / 2, 0, -e / 2, w, 2.1F, e);
        }
        CubeListBuilder n = CubeListBuilder.create();
        int nombre = 0;
        for (int x = -24; x < 24; x += 3) {
            for (int z = -18; z < 18; z += 3) {
                double mx = x + 1.5, mz = z + 1.5;
                double r = Math.hypot(mx / 24, mz / 18) + 0.18 * bruit(mx * 0.3 + 50, mz * 0.3 + 50, 1);
                if (r > 1) {
                    continue;
                }
                double dessus = 1.2 + 1.8 * (1 - r) + 1.2 * bruit(mx * 0.45 + 50, mz * 0.45 + 50, 2);
                double dessous = -1.6 - 6.5 * (1 - r * r) + 1.2 * bruit(mx * 0.5 + 70, mz * 0.5 + 70, 3);
                n.texOffs(nombre * 7 % 20, 48).addBox(x, (float) -dessus, z, 3, (float) (dessus - dessous), 3);
                nombre++;
            }
        }
        MeshDefinition m = new MeshDefinition();
        m.getRoot().addOrReplaceChild("n", n, PartPose.ZERO);
        nuage = LayerDefinition.create(m, 64, 64).bakeRoot().getChild("n");
    }

    private static void orienter(PoseStack ps, Vector3f d) {
        Vector3f u = new Vector3f(d).normalize();
        ps.mulPose(new Quaternionf().rotationTo(0, 1, 0, u.x, u.y, u.z));
    }

    private static void pique(PoseStack ps, VertexConsumer c, int l, int o, float x, float y, float z, Vector3f dir, float taille) {
        ps.pushPose();
        ps.translate(x / 16, y / 16, z / 16);
        orienter(ps, dir);
        ps.scale(taille, taille, taille);
        pique1.render(ps, c, l, o);
        ps.translate(0, 2.4F / 16, 0);
        pique2.render(ps, c, l, o);
        ps.popPose();
    }

    private record Pose(float x, float y, float z, float coude, float poignet, float doigts) {
    }

    private static void bras(PoseStack ps, VertexConsumer c, int l, int o, Pose p, float k) {
        ps.mulPose(new Quaternionf().rotationZYX(p.z(), p.y(), p.x()));
        ps.scale(k, k, k);
        ps.translate(-2 / 16F, 0, 0);
        haut.render(ps, c, l, o);
        piquesBras(ps, c, l, o);
        ps.translate(0, 8 / 16F, 0);
        ps.mulPose(Axis.XP.rotation(p.coude()));
        avant.render(ps, c, l, o);
        ps.translate(0, 9 / 16F, 0);
        ps.mulPose(Axis.XP.rotation(p.poignet()));
        main(ps, c, l, o, p.doigts());
    }

    private static void piquesBras(PoseStack ps, VertexConsumer c, int l, int o) {
        for (int k = 0; k < 5; k++) {
            pique(ps, c, l, o, -3.1F, -1.8F + k * 2.2F, 2.4F, new Vector3f(-1, -0.45F + 0.1F * k, 0.75F), 1.05F - 0.12F * k);
        }
    }

    private static void main(PoseStack ps, VertexConsumer c, int l, int o, float doigts) {
        paume.render(ps, c, l, o);
        for (int k = 0; k < 4; k++) {
            ps.pushPose();
            ps.translate(0.4F / 16, 4.6F / 16, (-3 + 2 * k) / 16F);
            ps.mulPose(Axis.XP.rotation((k - 1.5F) * 0.1F * (1 - Math.min(1, doigts))));
            ps.mulPose(Axis.ZP.rotation(-doigts));
            doigt1.render(ps, c, l, o);
            ps.translate(0, 3.2F / 16, 0);
            ps.mulPose(Axis.ZP.rotation(-doigts * 1.05F));
            doigt2.render(ps, c, l, o);
            ps.translate(0, 2.6F / 16, 0);
            ps.mulPose(Axis.ZP.rotation(-0.35F));
            griffe.render(ps, c, l, o);
            ps.popPose();
        }
        ps.pushPose();
        ps.translate(1.2F / 16, 1.4F / 16, -4.2F / 16);
        ps.mulPose(Axis.XP.rotation(-0.35F));
        ps.mulPose(Axis.ZP.rotation(-0.4F - doigts * 0.6F));
        pouce.render(ps, c, l, o);
        ps.translate(0, 3.2F / 16, 0);
        griffe.render(ps, c, l, o);
        ps.popPose();
    }

    private static void poser(PoseStack ps, VertexConsumer c, int l, int o, ModelPart m, float x, float y, float z) {
        ps.pushPose();
        ps.translate(x / 16, y / 16, z / 16);
        m.render(ps, c, l, o);
        ps.popPose();
    }

    /** Le bras qui s'allonge jusqu'à sa prise et la main géante qui l'enserre (serre : de 0 à 1). */
    private static void tendre(PoseStack ps, VertexConsumer c, int l, int o, Vector3f vers, float serre) {
        float longueur = Math.max(16, vers.length());
        Vector3f y = new Vector3f(vers).normalize();
        Vector3f x = new Vector3f(y).cross(0, -1, 0);
        if (x.lengthSquared() < 1e-4F) {
            x.set(-1, 0, 0);
        }
        x.normalize();
        Vector3f z = new Vector3f(x).cross(y);
        ps.mulPose(new Quaternionf().setFromNormalized(new Matrix3f(x, y, z)));
        haut.render(ps, c, l, o);
        piquesBras(ps, c, l, o);
        float debut = 6.5F, fin = longueur - 5.8F;
        int n = Math.max(1, Math.round((fin - debut) / 4));
        for (int s = 0; s < n; s++) {
            float y0 = debut + (fin - debut) * s / n, grosseur = 1 + 0.45F * s / Math.max(1, n - 1);
            ps.pushPose();
            ps.translate(0, y0 / 16, 0);
            ps.scale(grosseur, (fin - debut) / n / 4, grosseur);
            segment.render(ps, c, l, o);
            ps.popPose();
        }
        float L = longueur, s = serre * 1.2F;
        poser(ps, c, l, o, grandePaume, -6.6F, L - 6.1F, -7.6F);
        for (int k = 0; k < 4; k++) {
            float zk = -7.2F + k * 3.65F, bout = 0.3F + s + 1.2F * (k % 2);
            poser(ps, c, l, o, doigtFlanc, 4.7F - s, L - 5.7F, zk);
            poser(ps, c, l, o, jointure, 6.7F - s, L - 1.5F, zk - 0.3F);
            poser(ps, c, l, o, k % 2 == 0 ? doigtDos : doigtDosLong, -bout, L + 2.3F - s, zk);
            ps.pushPose();
            ps.translate(-bout / 16, (L + 3.7F - s) / 16, (zk + 1.4F) / 16);
            orienter(ps, new Vector3f(-1, -0.45F, 0.1F));
            ps.scale(1.6F, 1.6F, 1.6F);
            griffe.render(ps, c, l, o);
            ps.popPose();
        }
        poser(ps, c, l, o, grandPouce, -7.5F + s, L - 5.7F, -1.7F);
        ps.translate((-6.1F + s) / 16, (L + 1.9F - s) / 16, 0);
        orienter(ps, new Vector3f(0.4F, 1, 0));
        ps.scale(1.5F, 1.5F, 1.5F);
        griffe.render(ps, c, l, o);
    }

    private static void oreille(PoseStack ps, VertexConsumer c, int l, int o) {
        Vector3f d = new Vector3f(-0.45F, -1, 0.1F).normalize();
        for (int k = 0; k < OREILLE.length; k++) {
            ps.pushPose();
            ps.translate((-2.6F + d.x * 1.8F * k) / 16, (-8.2F + d.y * 1.8F * k) / 16, (0.4F + d.z * 1.8F * k) / 16);
            orienter(ps, d);
            OREILLE[k].render(ps, c, l, o);
            ps.popPose();
        }
    }

    private static void piquesDos(PoseStack ps, VertexConsumer c, int l, int o) {
        float[][] P = {{-2.6F, 1, 2.3F, 1}, {-1.4F, 4, 2.3F, 0.85F}, {-3.2F, 6, 2.3F, 0.75F}, {-1, 7.8F, 2.3F, 0.6F}};
        for (float[] p : P) {
            pique(ps, c, l, o, p[0], p[1], p[2], new Vector3f(-0.35F, -0.55F, 1), p[3]);
        }
    }

    private static void queue(PoseStack ps, VertexConsumer c, int l, int o, float k, float age) {
        Vector3f base = POINTS_QUEUE[0];
        ps.pushPose();
        ps.translate(base.x / 16, base.y / 16, base.z / 16);
        ps.mulPose(Axis.YP.rotation(Mth.sin(age * 0.06F) * 0.12F));
        ps.mulPose(Axis.XP.rotation(Mth.sin(age * 0.045F + 1) * 0.05F));
        ps.scale(k, k, k);
        ps.translate(-base.x / 16, -base.y / 16, -base.z / 16);
        for (int s = 0; s < QUEUE.length; s++) {
            Vector3f a = POINTS_QUEUE[s];
            ps.pushPose();
            ps.translate(a.x / 16, a.y / 16, a.z / 16);
            orienter(ps, new Vector3f(POINTS_QUEUE[s + 1]).sub(a));
            QUEUE[s].render(ps, c, l, o);
            ps.popPose();
        }
        Vector3f bout = POINTS_QUEUE[QUEUE.length];
        ps.translate(bout.x / 16, bout.y / 16, bout.z / 16);
        orienter(ps, new Vector3f(bout).sub(POINTS_QUEUE[QUEUE.length - 1]));
        boutQueue.render(ps, c, l, o);
        ps.popPose();
    }

    /** La pose du bras au repos, pendant un coup, un lancer, ou quand il arrache un rocher (le bras levé, la main ouverte). */
    private static Pose pose(Ichi i, ModelPart bras) {
        float x = bras.xRot * 0.6F - 0.18F, y = bras.yRot, z = bras.zRot * 0.5F + 0.06F;
        float coude = -0.55F, poignet = -0.35F, doigts = 0.55F;
        double c = i.coup < 0 ? 9 : secondes(i.coup), l = i.lance < 0 ? 9 : secondes(i.lance), r = i.rocher < 0 ? 9 : secondes(i.rocher);
        if (c < DUREE_COUP) {
            float s = lisse(c < 0.1 ? c / 0.1 : 1 - (c - 0.1) / (DUREE_COUP - 0.1));
            x = Mth.lerp(s, x, -1.65F);
            y = Mth.lerp(s, y, 0.12F);
            coude = Mth.lerp(s, coude, -0.05F);
            poignet = Mth.lerp(s, poignet, 0);
            doigts = Mth.lerp(s, doigts, 1.55F);
        } else if (r < Ichibi.ARRACHE_ROCHER / 20.0 + 0.3) {
            float s = lisse(r / 0.15);
            x = Mth.lerp(s, x, -2.9F);
            z = Mth.lerp(s, z, 0.25F);
            coude = Mth.lerp(s, coude, -0.6F);
            doigts = Mth.lerp(s, doigts, 0.2F);
        } else if (l < DUREE_LANCE) {
            float s = lisse(l < 0.12 ? l / 0.12 : 1 - (l - 0.12) / (DUREE_LANCE - 0.12));
            x = Mth.lerp(s, x, -2.5F);
            coude = Mth.lerp(s, coude, -0.1F);
            doigts = Mth.lerp(s, doigts, 0);
        }
        return new Pose(x, y, z, coude, poignet, doigts);
    }

    private static Vector3f versModele(AbstractClientPlayer joueur, Entity cible, float partiel) {
        return versModele(joueur, cible.getPosition(partiel).add(0, cible.getBbHeight() / 2, 0), partiel);
    }

    private static Vector3f versModele(AbstractClientPlayer joueur, Vec3 point, float partiel) {
        Vec3 w = point.subtract(joueur.getPosition(partiel));
        float lacet = Mth.rotLerp(partiel, joueur.yBodyRotO, joueur.yBodyRot);
        return new Vector3f((float) w.x, (float) w.y, (float) w.z).rotateY((float) Math.toRadians(lacet - 180)).mul(-1, -1, 1)
                .div(0.9375F).add(0, 1.501F, 0).mul(16);
    }

    // ================================================================ la moitié du corps (niveau 2)

    @SuppressWarnings("unchecked")
    private static final PlayerModel<AbstractClientPlayer>[][] MOITIES = new PlayerModel[2][2];

    private static PlayerModel<AbstractClientPlayer> modeleMoitie(boolean fin, boolean epais) {
        int f = fin ? 1 : 0, e = epais ? 1 : 0;
        if (MOITIES[f][e] == null) {
            MOITIES[f][e] = new PlayerModel<>(LayerDefinition.create(PlayerModel.createMesh(new CubeDeformation(epais ? 1.2F : 0.6F), fin), 64, 64)
                    .bakeRoot(), fin);
        }
        return MOITIES[f][e];
    }

    private static boolean fin(AbstractClientPlayer joueur) {
        return joueur.getSkin().model() == PlayerSkin.Model.SLIM;
    }

    private static void moitie(PoseStack ps, MultiBufferSource buffers, int lumiere, AbstractClientPlayer joueur, PlayerModel<AbstractClientPlayer> parent,
            int dessous) {
        boolean fin = fin(joueur);
        PlayerModel<AbstractClientPlayer> mince = modeleMoitie(fin, false), epais = modeleMoitie(fin, true);
        parent.copyPropertiesTo(mince);
        parent.copyPropertiesTo(epais);
        PlayerModel<AbstractClientPlayer> tete = joueur.getItemBySlot(EquipmentSlot.HEAD).isEmpty() ? mince : epais;
        PlayerModel<AbstractClientPlayer> torse = joueur.getItemBySlot(EquipmentSlot.CHEST).isEmpty() ? mince : epais;
        PlayerModel<AbstractClientPlayer> jambes = joueur.getItemBySlot(EquipmentSlot.LEGS).isEmpty() && joueur.getItemBySlot(EquipmentSlot.FEET).isEmpty()
                ? mince : epais;
        VertexConsumer c = buffers.getBuffer(RenderType.entityCutoutNoCull(MOITIE));
        for (ModelPart p : new ModelPart[] {tete.head, torse.body, torse.leftArm, jambes.rightLeg, jambes.leftLeg}) {
            p.render(ps, c, lumiere, dessous);
        }
    }

    // ================================================================ ce qu'on voit sur lui

    /** Les points du filet de sable qui coule du goulot sur son épaule (repère du buste, en pixels du jeu). */
    private static final Vec3[] COULE = {new Vec3(-2.7, 24 - 31.2, 7.9), new Vec3(-3.6, 24 - 32.4, 5), new Vec3(-6.2, 24 - 25.6, 0.6), new Vec3(-6.4, 24 - 14, -1.2)};

    /** La pose des grands bras de la bête (le morceau de bete_bras). */
    private static int poseBete(Ichi i) {
        if (i.rire >= 0 && secondes(i.rire) < 2.6) {
            return 5;
        }
        if (i.griffe >= 0 && secondes(i.griffe) < 0.35) {
            return i.cote == 0 ? 1 : 2;
        }
        if (i.vague >= 0 && secondes(i.vague) < 0.7) {
            return 3;
        }
        if (i.cercueils >= 0 && secondes(i.cercueils) < 1.6) {
            return 4;
        }
        return 0;
    }

    public static final class Couche extends RenderLayer<AbstractClientPlayer, PlayerModel<AbstractClientPlayer>> {
        public Couche(PlayerRenderer parent) {
            super(parent);
        }

        @Override
        public void render(PoseStack ps, MultiBufferSource buffers, int lumiere, AbstractClientPlayer joueur, float marche, float vitesse,
                float partiel, float age, float tete, float tangage) {
            Ichi i = ICHIS.get(joueur.getUUID());
            boolean porteur = PORTEURS.contains(joueur.getUUID());
            if (i == null && !porteur || joueur.isInvisible()) {
                return;
            }
            modeles();
            int dessous = LivingEntityRenderer.getOverlayCoords(joueur, 0);
            PlayerModel<AbstractClientPlayer> parent = getParentModel();
            int niveau = i == null ? 0 : i.niveau;
            double t = i == null ? 99 : secondes(i.debut);
            VertexConsumer blanc = buffers.getBuffer(RenderType.entityCutoutNoCull(BLANC));
            // la jarre (niveaux 0 et 1), fermée tant qu'il dort ; dès le niveau 2 elle s'effondre puis il n'y en a plus
            double casse = i == null || i.casse < 0 ? 99 : secondes(i.casse);
            if (niveau < 2) {
                ps.pushPose();
                parent.body.translateAndRotate(ps);
                dessiner(m(jarre, 0), blanc, ps, lumiere, dessous);
                double b = i == null || i.bouchon < 0 ? -1 : secondes(i.bouchon);
                if (i == null) {
                    dessiner(m(jarre, 1), blanc, ps, lumiere, dessous);
                } else if (b >= 0 && b < 1.4) {
                    // le bouchon saute : il monte, part en arrière en tournant, retombe
                    ps.pushPose();
                    float s = (float) b;
                    ps.translate(0.3F * s, (-26 * s + 30 * s * s) / 16, 0.45F * s);
                    ps.translate(-2.7F / 16, -7.6F / 16, 7.9F / 16);
                    ps.mulPose(Axis.XP.rotation(s * 9));
                    ps.translate(2.7F / 16, 7.6F / 16, -7.9F / 16);
                    dessiner(m(jarre, 1), blanc, ps, lumiere, dessous);
                    ps.popPose();
                }
                ps.popPose();
            } else if (casse < 1.6) {
                // éveil 2 (ou 3 si on saute le 2) : la jarre fissurée s'affaisse et tombe en morceaux, une seule fois
                ps.pushPose();
                parent.body.translateAndRotate(ps);
                float s = (float) Math.max(0, casse - 0.25);
                ps.translate(0, s * s * 1.6F, s * 0.25F);
                float k = 1 - lisse((casse - 0.6) / 1.0) * 0.6F;
                ps.scale(k, k, k);
                dessiner(m(jarreCassee, 0), blanc, ps, lumiere, dessous);
                ps.popPose();
            }
            if (i == null) {
                return;
            }
            if (niveau == 2) {
                niveau2(ps, buffers, lumiere, joueur, parent, dessous, i, t, partiel, age, blanc);
            } else if (niveau >= 3) {
                niveau3(ps, lumiere, parent, dessous, i, t, blanc, joueur, partiel, buffers);
            }
        }
    }

    private static void niveau2(PoseStack ps, MultiBufferSource buffers, int lumiere, AbstractClientPlayer joueur, PlayerModel<AbstractClientPlayer> parent,
            int dessous, Ichi i, double t, float partiel, float age, VertexConsumer blanc) {
        // le sable coule du goulot sur son épaule, puis le prend petit à petit : le bras, la moitié du corps, la queue
        if (t < 2.2) {
            ps.pushPose();
            parent.body.translateAndRotate(ps);
            double f = lisse(t / 0.7), fin = 1 - lisse((t - 1.7) / 0.5);
            Vec3[] P = new Vec3[4];
            for (int k = 0; k < 4; k++) {
                P[k] = COULE[k].scale(1 / 16.0);
            }
            if (fin > 0.02) {
                filet(ps, blanc, m(tube, 1), P[0], P[1], P[2], P[3], 0, f, 0.7F * (float) fin, 0.95F * (float) fin, lumiere);
            }
            ps.popPose();
        }
        boolean entier = t > 1.0;
        if (entier) {
            moitie(ps, buffers, lumiere, joueur, parent, dessous);
        }
        VertexConsumer c = buffers.getBuffer(RenderType.entityCutoutNoCull(SABLE));
        ModelPart epaule = parent.rightArm;
        double serre = i.serre < 0 ? 9 : secondes(i.serre);
        Entity tenu = i.prise >= 0 ? joueur.level().getEntity(i.prise) : null;
        if (t > 0.35) {
            ps.pushPose();
            ps.translate(epaule.x / 16, epaule.y / 16, epaule.z / 16);
            if (tenu != null) {
                tendre(ps, c, lumiere, dessous, versModele(joueur, tenu, partiel).sub(epaule.x, epaule.y, epaule.z),
                        serre < DUREE_SERRE ? lisse(serre / (DUREE_SERRE * 0.6)) : 0);
            } else {
                bras(ps, c, lumiere, dessous, pose(i, epaule), (float) Mth.clamp((t - 0.35) / 0.9, 0.15, 1));
            }
            ps.popPose();
        }
        if (entier) {
            ps.pushPose();
            parent.head.translateAndRotate(ps);
            oreille(ps, c, lumiere, dessous);
            ps.popPose();
            ps.pushPose();
            parent.body.translateAndRotate(ps);
            piquesDos(ps, c, lumiere, dessous);
            queue(ps, c, lumiere, dessous, (float) Mth.clamp((t - 1.0) / 0.8, 0.05, 1), age);
            ps.popPose();
        }
    }

    /** Niveau 3 : la bête de sable (le buste, la tête, la queue, le haut des jambes, les deux grands bras). */
    private static void niveau3(PoseStack ps, int lumiere, PlayerModel<AbstractClientPlayer> parent, int dessous, Ichi i, double t, VertexConsumer blanc,
            AbstractClientPlayer joueur, float partiel, MultiBufferSource buffers) {
        // le sable de la jarre tombe sur lui : la bête grossit en 0,8 s
        float k = 0.35F + 0.65F * lisse((t - 0.15) / 0.8);
        boolean rit = i.rire >= 0 && secondes(i.rire) < 3.2;
        ps.pushPose();
        parent.body.translateAndRotate(ps);
        ps.scale(k, k, k);
        dessiner(m(bete, 0), blanc, ps, lumiere, dessous);
        Entity tenu = i.prise >= 0 ? joueur.level().getEntity(i.prise) : null;
        dessiner(m(beteBras, tenu != null ? 4 : poseBete(i)), blanc, ps, lumiere, dessous);
        ps.popPose();
        ps.pushPose();
        parent.head.translateAndRotate(ps);
        ps.scale(k, k, k);
        dessiner(m(bete, rit ? 2 : 1), blanc, ps, lumiere, dessous);
        ps.popPose();
        for (int s = 0; s < 2; s++) {
            ps.pushPose();
            (s == 0 ? parent.rightLeg : parent.leftLeg).translateAndRotate(ps);
            dessiner(m(bete, 3 + s), blanc, ps, lumiere, dessous);
            ps.popPose();
        }
        if (tenu != null) {
            VertexConsumer c = buffers.getBuffer(RenderType.entityCutoutNoCull(SABLE));
            ModelPart epaule = parent.rightArm;
            double serre = i.serre < 0 ? 9 : secondes(i.serre);
            ps.pushPose();
            ps.translate(epaule.x / 16, epaule.y / 16, epaule.z / 16);
            tendre(ps, c, lumiere, dessous, versModele(joueur, tenu, partiel).sub(epaule.x, epaule.y, epaule.z),
                    serre < DUREE_SERRE ? lisse(serre / (DUREE_SERRE * 0.6)) : 0);
            ps.popPose();
        }
    }

    /** À la première personne, quand il tient quelqu'un : le bras tendu jusqu'à lui, dans le monde. */
    private static void brasTendu(PoseStack ps, Camera camera, float partiel) {
        Minecraft mc = Minecraft.getInstance();
        AbstractClientPlayer moi = mc.player;
        Ichi i = moi == null ? null : ICHIS.get(moi.getUUID());
        Entity tenu = i == null || i.prise < 0 ? null : moi.level().getEntity(i.prise);
        if (tenu == null || moi.isInvisible() || !mc.options.getCameraType().isFirstPerson()) {
            return;
        }
        modeles();
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        RenderType type = RenderType.entityCutoutNoCull(SABLE);
        Vec3 cam = camera.getPosition();
        Vector3f d = new Vector3f(camera.getLookVector()).mul(0.1F).add(new Vector3f(camera.getLeftVector()).mul(-0.55F))
                .add(new Vector3f(camera.getUpVector()).mul(-0.55F));
        Vector3f epaule = versModele(moi, cam.add(d.x, d.y, d.z), partiel);
        Vec3 p = moi.getPosition(partiel).subtract(cam);
        ps.pushPose();
        ps.translate(p.x, p.y, p.z);
        ps.mulPose(Axis.YP.rotationDegrees(180 - Mth.rotLerp(partiel, moi.yBodyRotO, moi.yBodyRot)));
        ps.scale(-0.9375F, -0.9375F, 0.9375F);
        ps.translate(epaule.x / 16, epaule.y / 16 - 1.501F, epaule.z / 16);
        double serre = i.serre < 0 ? 9 : secondes(i.serre);
        tendre(ps, buffers.getBuffer(type), mc.getEntityRenderDispatcher().getPackedLightCoords(moi, partiel), LivingEntityRenderer.getOverlayCoords(moi, 0),
                versModele(moi, tenu, partiel).sub(epaule), serre < DUREE_SERRE ? lisse(serre / (DUREE_SERRE * 0.6)) : 0);
        ps.popPose();
        buffers.endBatch(type);
    }

    // ================================================================ dans le monde

    private static int lumiere(Vec3 p) {
        Minecraft mc = Minecraft.getInstance();
        return LevelRenderer.getLightColor(mc.level, BlockPos.containing(p.x, p.y + 0.3, p.z));
    }

    /** Le sable qui tourne autour d'eux, les poings, les cercueils, les tirs, les statues, les vagues, les nuages. */
    private static void monde(PoseStack ps, Camera camera, float partiel) {
        Minecraft mc = Minecraft.getInstance();
        modeles();
        Vec3 cam = camera.getPosition();
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        RenderType type = RenderType.entityCutoutNoCull(BLANC);
        VertexConsumer c = buffers.getBuffer(type);
        double horloge = System.nanoTime() / 1e9;
        for (Map.Entry<UUID, Ichi> e : ICHIS.entrySet()) {
            Player j = mc.level.getPlayerByUUID(e.getKey());
            Ichi i = e.getValue();
            if (j == null || j.isInvisible()) {
                continue;
            }
            Vec3 pieds = j.getPosition(partiel);
            int l = lumiere(pieds);
            boolean moi = j == mc.player && mc.options.getCameraType().isFirstPerson();
            // le sable tourne autour de lui (au niveau 1 le gros ruban, au niveau 2 un ruban fin) : il sort de la jarre à l'éveil
            if (i.niveau <= 2 && !moi) {
                double t = secondes(i.debut);
                float k = i.niveau == 1 ? lisse((t - 0.3) / 0.8) : 1;
                if (k > 0.02) {
                    ps.pushPose();
                    ps.translate(pieds.x - cam.x, pieds.y - cam.y, pieds.z - cam.z);
                    ps.mulPose(Axis.YP.rotation((float) (-horloge * (i.niveau == 1 ? 1.8 : 1.2))));
                    ps.scale(k, k, k);
                    dessiner(m(tourbillon, i.niveau == 1 ? 0 : 1), c, ps, l, OverlayTexture.NO_OVERLAY);
                    ps.popPose();
                }
            }
            if (i.niveau == 1) {
                poings(ps, c, j, i, partiel, cam, l);
            }
            rocherLeve(ps, c, j, i, partiel, cam, l);
        }
        cercueils(ps, c, partiel, cam);
        tirs(ps, c, partiel, cam);
        statues(ps, c, partiel, cam);
        vagues(ps, c, cam);
        tempetes(ps, c, partiel, cam);
        griffures(ps, c, cam);
        pics(ps, c, cam);
        buffers.endBatch(type);
        nuages(ps, cam, partiel);
        murs(ps, camera, partiel);
        eclairs(ps, camera, partiel);
    }

    /** Niveau 1 : les poings de sable, chacun au bout d'un bras de sable qui sort du goulot de la jarre. */
    private static void poings(PoseStack ps, VertexConsumer c, Player j, Ichi i, float partiel, Vec3 cam, int l) {
        if (i.dernierPoing < 0) {
            return;
        }
        double depuis = secondes(i.dernierPoing);
        if (depuis > POINGS + 0.5) {
            return;
        }
        // après 1 s sans clic, ils retombent en sable
        float use = (float) Mth.clamp((depuis - POINGS) / 0.5, 0, 1);
        if (use > 0 && HASARD.nextInt(2) == 0) {
            for (int s = 0; s < 2; s++) {
                if (i.bout[s] != null) {
                    Vec3 p = repos(j, partiel, s);
                    Minecraft.getInstance().level.addParticle(new BlockParticleOption(ParticleTypes.FALLING_DUST, Blocks.SAND.defaultBlockState()),
                            p.x + HASARD.nextGaussian() * 0.4, p.y, p.z + HASARD.nextGaussian() * 0.4, 0, 0, 0);
                }
            }
        }
        Vec3 col = goulot(j, partiel);
        Vec3 regard = j.getViewVector(partiel);
        for (int s = 0; s < 2; s++) {
            if (i.poing[s] < 0 || i.bout[s] == null) {
                continue;
            }
            double t = secondes(i.poing[s]);
            Vec3 repos = repos(j, partiel, s), bout = i.bout[s];
            // il part de la jarre jusqu'au bout en 0,12 s, y reste, puis revient flotter de son côté
            Vec3 p = t < 0.12 ? repos.lerp(bout, lisse(t / 0.12)) : t < 0.28 ? bout : repos.lerp(bout, 1 - lisse((t - 0.28) / 0.2));
            Vec3 cote = new Vec3(regard.z, 0, -regard.x).normalize().scale(s == 0 ? -1 : 1);
            Vec3 a = col.subtract(cam), d = p.subtract(cam);
            Vec3 b = a.add(0, 1.0, 0).add(cote.scale(0.9)), cc = d.subtract(regard.scale(2.2)).add(0, 0.5, 0);
            float r = 1 - use;
            filet(ps, c, m(tube, 0), a, b, cc, d.subtract(regard.scale(0.4)), 0, 1, 0.75F * r, 1.25F * r, l);
            ps.pushPose();
            ps.translate(d.x, d.y, d.z);
            ps.mulPose(Axis.YP.rotation((float) Math.atan2(regard.x, regard.z)));
            ps.mulPose(Axis.XP.rotation((float) -Math.asin(Mth.clamp(regard.y, -1, 1))));
            float k = 1.05F * r;
            ps.scale(k, k, k);
            dessiner(m(s == 0 ? poingD : poingG, 0), c, ps, l, OverlayTexture.NO_OVERLAY);
            ps.popPose();
        }
    }

    /** Où flotte le poing de ce côté (0 droit, 1 gauche) entre deux coups : devant lui, sur le côté. */
    private static Vec3 repos(Player j, float partiel, int s) {
        Vec3 regard = j.getViewVector(partiel);
        Vec3 plat = new Vec3(regard.x, 0, regard.z);
        plat = plat.lengthSqr() < 1e-4 ? new Vec3(0, 0, 1) : plat.normalize();
        Vec3 gauche = new Vec3(plat.z, 0, -plat.x);
        return j.getEyePosition(partiel).add(plat.scale(1.6)).add(gauche.scale(s == 0 ? -1.3 : 1.3)).add(0, -0.35, 0);
    }

    /** Niveau 2 : le bras d'Ichibi arrache un rocher du sol (il monte de son côté jusqu'au-dessus de son épaule). */
    private static void rocherLeve(PoseStack ps, VertexConsumer c, Player j, Ichi i, float partiel, Vec3 cam, int l) {
        if (i.rocher < 0) {
            return;
        }
        double t = secondes(i.rocher);
        if (t > Ichibi.ARRACHE_ROCHER / 20.0 + 0.3) {
            i.rocher = -1;
            return;
        }
        float k = lisse(t / (Ichibi.ARRACHE_ROCHER / 20.0));
        Vec3 bas = local(j, partiel, -20, 0, 6), haut = local(j, partiel, -14, 46, 4);
        Vec3 p = bas.lerp(haut, k).subtract(cam);
        ps.pushPose();
        ps.translate(p.x, p.y, p.z);
        ps.mulPose(Axis.YP.rotation((float) t * 3));
        float s = 0.4F + 0.6F * k;
        ps.scale(s, s, s);
        dessiner(m(rocher, 0), c, ps, l, OverlayTexture.NO_OVERLAY);
        ps.popPose();
    }

    /** Les cercueils : le sable vole jusqu'à lui, monte jusqu'au cou, la colonne le soulève, il serre, puis il s'effrite. */
    private static void cercueils(PoseStack ps, VertexConsumer c, float partiel, Vec3 cam) {
        Minecraft mc = Minecraft.getInstance();
        CERCUEILS.removeIf(x -> x.fin >= 0 && secondes(x.fin) > 0.6 || secondes(x.debut) > 6);
        for (Cercueil x : CERCUEILS) {
            Entity cible = mc.level.getEntity(x.cible);
            if (cible != null) {
                x.dernier = cible.getPosition(partiel);
            }
            Vec3 pieds = x.dernier;
            double t = secondes(x.debut) * 20;
            Maillage[] l = x.ichibi ? cercueilI : cercueil;
            int lum = lumiere(pieds);
            Player lanceur = mc.level.getPlayerByUUID(x.lanceur);
            // le sable part de la jarre (ou de la bête) et file jusqu'à lui
            if (lanceur != null && t < Ichibi.C_VOL + 4) {
                Ichi i = ICHIS.get(x.lanceur);
                Vec3 a = (i != null && i.niveau >= 2 ? local(lanceur, partiel, 0, 26, 6) : goulot(lanceur, partiel)).subtract(cam);
                Vec3 d = pieds.add(0, 0.4, 0).subtract(cam);
                Vec3 b = a.add(0, 1.2, 0), cc = d.add(a.subtract(d).scale(0.3)).add(0, 0.8, 0);
                double fin = Math.min(1, t / Ichibi.C_VOL), debut = Math.max(0, (t - Ichibi.C_VOL) / 4);
                filet(ps, c, m(x.ichibi ? tube : tube, x.ichibi ? 1 : 0), a, b, cc, d, debut, fin, 0.6F, 1.1F, lum);
            }
            if (t < Ichibi.C_VOL - 1) {
                continue;
            }
            double use = x.fin < 0 ? 0 : secondes(x.fin) / 0.6;
            Vec3 face = lanceur == null ? new Vec3(0, 0, 1) : new Vec3(lanceur.getX() - pieds.x, 0, lanceur.getZ() - pieds.z);
            face = face.lengthSqr() < 1e-4 ? new Vec3(0, 0, 1) : face.normalize();
            // la colonne qui le soulève, du sol jusqu'à ses pieds
            double leve = pieds.y - x.depart.y;
            if (leve > 0.05) {
                ps.pushPose();
                repere(ps, x.depart.subtract(cam), face);
                float k = (float) (1 - use);
                ps.scale(k, (float) (leve * 16 + 2) / 36F, k);
                dessiner(m(l, 5), c, ps, lum, OverlayTexture.NO_OVERLAY);
                ps.popPose();
            }
            int etape = x.serre >= 0 ? 4 : (int) Mth.clamp((t - Ichibi.C_VOL) / ((Ichibi.C_PLEIN - Ichibi.C_VOL) / 4.0), 0, 3);
            ps.pushPose();
            repere(ps, pieds.subtract(cam).add(0, -use * 1.2, 0), face);
            float k = (float) (1 - use * 0.5);
            ps.scale(k, k, k);
            dessiner(m(l, etape), c, ps, lum, OverlayTexture.NO_OVERLAY);
            ps.popPose();
            if (use > 0 && HASARD.nextInt(2) == 0) {
                eclat(mc, pieds.add(0, 1, 0), 3, 0.6);
            }
        }
    }

    /** Les boules et les rochers en vol. */
    private static void tirs(PoseStack ps, VertexConsumer c, float partiel, Vec3 cam) {
        TIRS.removeIf(t -> t.fin >= 0 || secondes(t.debut) > 4);
        for (Tir t : TIRS) {
            double ticks = secondes(t.debut) * 20;
            Vec3 p = t.position(ticks);
            int l = lumiere(p);
            ps.pushPose();
            ps.translate(p.x - cam.x, p.y - cam.y, p.z - cam.z);
            ps.mulPose(Axis.YP.rotation((float) ticks * 0.4F));
            ps.mulPose(Axis.XP.rotation((float) ticks * 0.3F));
            dessiner(m(t.rocher ? rocher : boule, 0), c, ps, l, OverlayTexture.NO_OVERLAY);
            ps.popPose();
            if (HASARD.nextInt(2) == 0) {
                Minecraft.getInstance().level.addParticle(new BlockParticleOption(ParticleTypes.FALLING_DUST, Blocks.SAND.defaultBlockState()),
                        p.x, p.y, p.z, 0, 0, 0);
            }
        }
    }

    /** Les statues du mausolée : elle sort du sol, referme ses mains sur lui, puis s'effondre en sable. */
    private static void statues(PoseStack ps, VertexConsumer c, float partiel, Vec3 cam) {
        Minecraft mc = Minecraft.getInstance();
        STATUES.removeIf(s -> s.fin()[0] >= 0 && secondes(s.fin()[0]) > 1.2 || secondes(s.debut()) > 12);
        for (Statue s : STATUES) {
            double t = secondes(s.debut()) * 20, use = s.fin()[0] < 0 ? 0 : secondes(s.fin()[0]) / 1.2;
            float taille = s.trois() ? 11 / 9F : 1;
            double monte = (1 - lisse(t / Ichibi.M_SORT)) * 92 * 1.6 / 16 * taille + use * use * 6 * taille;
            int etape = t < Ichibi.M_SORT ? 0 : t < (Ichibi.M_SORT + Ichibi.M_FERME) / 2.0 ? 1 : 2;
            int l = lumiere(s.pieds().add(0, 4, 0));
            ps.pushPose();
            repere(ps, s.pieds().subtract(cam).add(0, -monte, 0), s.face().scale(-1));
            ps.scale(taille, taille, taille);
            dessiner(m(statue, etape), c, ps, l, OverlayTexture.NO_OVERLAY);
            ps.popPose();
            if (t < Ichibi.M_SORT || use > 0) {
                for (int k = 0; k < 3; k++) {
                    double a = HASARD.nextDouble() * Math.PI * 2, r = 1 + HASARD.nextDouble() * 3 * taille;
                    mc.level.addParticle(new BlockParticleOption(ParticleTypes.BLOCK, Blocks.SAND.defaultBlockState()), s.pieds().x + Math.cos(a) * r,
                            s.pieds().y + 0.2 + (use > 0 ? HASARD.nextDouble() * 8 * taille : 0), s.pieds().z + Math.sin(a) * r, 0, 0.2, 0);
                }
            }
        }
    }

    /** Les grandes vagues : le front avance d'un bloc par tick, puis la vague s'affaisse. */
    private static void vagues(PoseStack ps, VertexConsumer c, Vec3 cam) {
        VAGUES.removeIf(v -> secondes(v.debut()) * 20 > v.longueur() + 16);
        for (Vague v : VAGUES) {
            double t = secondes(v.debut()) * 20, front = Math.min(v.longueur(), t + 1.5);
            double tombe = Math.max(0, t - v.longueur()) / 14;
            Vec3 p = v.depart().add(v.dir().scale(front));
            int l = lumiere(p.add(0, 2, 0));
            ps.pushPose();
            repere(ps, p.subtract(cam).add(0, -tombe * 4.5, 0), v.dir());
            float k = (float) (0.55 + 0.45 * lisse(t / 4));
            ps.scale(1, k, 1);
            dessiner(m(vague, 0), c, ps, l, OverlayTexture.NO_OVERLAY);
            ps.popPose();
        }
    }

    /** L'âge d'une tempête, en ticks du serveur (les tornades et les lames se placent pareil chez le serveur). */
    private static double age(Tempete t) {
        return secondes(t.debut) * 20;
    }

    /** Combien l'ouragan est formé (0 à 1), et combien il s'est effondré. */
    private static double forme(Tempete t) {
        return lisse((secondes(t.debut) - 0.1) / FORME);
    }

    private static double effondre(Tempete t) {
        return t.effondre < 0 ? 0 : lisse(secondes(t.effondre) / EFFONDRE);
    }

    /** Les lames de sable et les mains géantes (des modèles pleins). */
    private static void tempetes(PoseStack ps, VertexConsumer c, float partiel, Vec3 cam) {
        Minecraft mc = Minecraft.getInstance();
        TEMPETES.values().removeIf(t -> t.fin >= 0 && secondes(t.fin) > RETOMBE || secondes(t.debut) > FORME + TEMPETE + EFFONDRE + 15);
        for (Map.Entry<UUID, Tempete> e : TEMPETES.entrySet()) {
            Player j = mc.level.getPlayerByUUID(e.getKey());
            Tempete t = e.getValue();
            if (j != null) {
                t.centre = j.getPosition(partiel);
            }
            double age = age(t), k = lisse((secondes(t.debut) - FORME + 0.4) / 0.6) * (1 - effondre(t)) * (t.fin < 0 ? 1 : 0);
            if (k < 0.02) {
                continue;
            }
            // les lames : des croissants de sable qui tournent à plat au ras du sol, la pointe en avant
            for (int q = 0; q < IchibiTempete.LAMES; q++) {
                double a = IchibiTempete.angleLame(q, age), r = IchibiTempete.rayonLame(q);
                Vec3 p = t.centre.add(Math.cos(a) * r, IchibiTempete.hauteurLame(q), Math.sin(a) * r);
                ps.pushPose();
                ps.translate(p.x - cam.x, p.y - cam.y, p.z - cam.z);
                ps.mulPose(Axis.YP.rotation((float) (Math.PI / 2 - a)));
                ps.mulPose(Axis.XP.rotation(0.18F * Mth.sin((float) age * 0.3F + q)));
                ps.scale((float) k, (float) k, (float) k);
                dessiner(m(lame, 0), c, ps, LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY);
                ps.popPose();
            }
        }
        // les mains géantes : elles sortent du sable sous lui, se referment, le soulèvent, l'abattent, puis rentrent
        MAINS_T.removeIf(x -> secondes(x.debut) * 20 > IchibiTempete.M_ABAT + 18);
        for (MainG x : MAINS_T) {
            double u = secondes(x.debut) * 20;
            Entity cible = mc.level.getEntity(x.cible);
            if (cible != null && u < IchibiTempete.M_ABAT) {
                x.dernier = cible.getPosition(partiel);
            }
            Vec3 p = x.dernier.subtract(x.face.scale(1.7)).add(0, -2.1, 0);
            p = p.add(0, -(1 - lisse(u / IchibiTempete.M_SORT)) * 7 - lisse((u - IchibiTempete.M_ABAT - 2) / 14) * 9, 0);
            ps.pushPose();
            repere(ps, p.subtract(cam), x.face);
            dessiner(m(mainGeante, u < IchibiTempete.M_SORT + 3 ? 0 : 1), c, ps, lumiere(x.dernier.add(0, 1, 0)), OverlayTexture.NO_OVERLAY);
            ps.popPose();
            if (u < IchibiTempete.M_SORT && HASARD.nextInt(2) == 0) {
                eclat(mc, x.pieds.add(0, 0.3, 0), 6, 1.6);
            }
            if (!x.abattue && u >= IchibiTempete.M_ABAT) {
                x.abattue = true;
                eclat(mc, x.dernier.add(0, 0.3, 0), 140, 2.2);
            }
        }
    }

    /** Une bouffée de sable tournée vers la caméra. */
    private static void bouffee(VertexConsumer v, PoseStack.Pose pose, Vector3f droite, Vector3f haut, Vec3 p, float taille, float[] couleur, float alpha) {
        int r = (int) (couleur[0] * 255), g = (int) (couleur[1] * 255), b = (int) (couleur[2] * 255), a = (int) (Mth.clamp(alpha, 0, 1) * 255);
        float[][] coins = {{-1, -1, 0, 1}, {-1, 1, 0, 0}, {1, 1, 1, 0}, {1, -1, 1, 1}};
        for (float[] q : coins) {
            v.addVertex(pose, (float) p.x + (droite.x * q[0] + haut.x * q[1]) * taille, (float) p.y + (droite.y * q[0] + haut.y * q[1]) * taille,
                    (float) p.z + (droite.z * q[0] + haut.z * q[1]) * taille).setColor(r, g, b, a).setUv(q[2], q[3]).setOverlay(OverlayTexture.NO_OVERLAY)
                    .setLight(LightTexture.FULL_BRIGHT).setNormal(pose, 0, 1, 0);
        }
    }

    private record Nuee(Vec3 p, float taille, float[] couleur, float alpha) {
    }

    private static final float[] SABLE_S = {0.43F, 0.29F, 0.13F}, SABLE_M = {0.66F, 0.48F, 0.25F};

    /** Une bouffée s'efface tout près de la caméra (sinon elle cache tout l'écran). */
    private static float pres(Vec3 p, Vec3 cam, double d0, double d1) {
        return (float) Mth.clamp((p.distanceTo(cam) - d0) / (d1 - d0), 0, 1);
    }

    /**
     * L'ouragan (docs/ichibi/apercu/tempete.html) : le sable se soulève du sol autour de lui et forme un mur géant qui
     * tourne (40 à 50 blocs, jusqu'à 90 de haut, des bandes sombres et claires qui montent en spirale, le haut qui
     * s'étale) ; au ras du sol, des bandes de sable s'enroulent vers lui ; quatre tornades serpentent. À la fin, le mur
     * s'effondre en une colonne, puis toutes ses bouffées partent d'un coup vers le dehors (un anneau énorme, un
     * champignon au milieu). Chez lui, quand il est dedans, le mur est à moitié transparent (il voit à travers).
     */
    private static void murs(PoseStack ps, Camera camera, float partiel) {
        if (TEMPETES.isEmpty()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        Vec3 cam = camera.getPosition();
        float jour = 0.4F + 0.6F * mc.level.getSkyDarken(partiel);
        List<Nuee> l = new ArrayList<>();
        for (Map.Entry<UUID, Tempete> e : TEMPETES.entrySet()) {
            Tempete t = e.getValue();
            boolean sienne = mc.player != null && e.getKey().equals(mc.player.getUUID()) && !TEMPETE_D_UN_AUTRE;
            double s = secondes(t.debut), age = s * 20, forme = forme(t), eff = effondre(t), tasse = 1 - 0.95 * eff;
            boolean dedans = Math.hypot(cam.x - t.centre.x, cam.z - t.centre.z) < IchibiTempete.RAYON * tasse;
            float voile = sienne && dedans ? 0.45F : 1;
            double ub = t.fin < 0 || !t.explose ? -1 : secondes(t.fin);
            boolean fini = t.fin >= 0 && !t.explose;
            float retombe = fini ? 1 - lisse(secondes(t.fin) / 2.5) : 1;
            // le mur
            for (int q = 0; q < 480; q++) {
                double etage = hache(q, 80, 1), a0 = hache(q, 81, 1) * Math.PI * 2, y0 = Math.pow(etage, 1.1) * 90;
                double taille = (9 + 14 * etage + 7 * hache(q, 84, 1)) / 2;
                float bande = (float) (0.5 + 0.5 * Math.sin(y0 * 0.22 + a0 * 3));
                float[] coul = teinte(mixer(SABLE_S, SABLE_C, (float) Math.min(1, Math.pow(etage, 0.8) * 0.55 + bande * 0.45)), jour);
                Vec3 p;
                float alpha;
                if (ub >= 0) {
                    // l'explosion : les bouffées du bas filent au ras du sol, celles du haut font un champignon
                    if (ub > 4) {
                        continue;
                    }
                    boolean champi = etage > 0.7;
                    double rr = champi ? 6 + 30 * lisse(ub / 1.6) * etage : (4 + 95 * lisse(ub / 2.2)) * (0.45 + 0.55 * hache(q, 3, 1));
                    double yy = champi ? 10 + 70 * lisse(ub / 1.4) * etage : 1 + 12 * etage * Math.sin(Math.PI * Math.min(1, ub / 2.6));
                    p = t.centre.add(Math.cos(a0) * rr, yy, Math.sin(a0) * rr);
                    taille *= 1 + 0.8 * lisse(ub / 1.5);
                    alpha = 0.95F * (1 - lisse((ub - 2) / 2));
                } else {
                    if (forme < 0.01) {
                        continue;
                    }
                    double vit = 0.55 - 0.3 * (y0 / 90), a = a0 + s * vit + forme * 2;
                    double r = (Mth.lerp(forme, 5, 40 + 10 * hache(q, 82, 1)) + (y0 > 72 ? (y0 - 72) * 0.9 * forme : 0)) * tasse + 1.5 * Math.sin(s * 2 + q);
                    double y = Math.max(taille * 0.5, y0 * forme * (1 - eff * 0.4) + 3 * Math.sin(s * 1.5 + q));
                    p = t.centre.add(Math.cos(a) * r, y, Math.sin(a) * r);
                    taille *= (0.6 + 0.4 * forme) * (1 - eff * 0.5);
                    alpha = (float) (0.95 * lisse(forme / 0.25)) * voile * retombe;
                }
                l.add(new Nuee(p, (float) taille, coul, alpha * pres(p, cam, 6, 16)));
            }
            if (ub >= 0) {
                continue;
            }
            double la = forme * retombe;
            // les bandes de la spirale, au ras du sol
            for (int q = 0; q < 120; q++) {
                double u = hache(q, 52, 2), rr = Mth.lerp(u, 40, 4) * tasse, a = (q % 5) * (Math.PI * 2 / 5) + u * 4.2 + s * 0.9;
                Vec3 p = t.centre.add(Math.cos(a) * rr, 1 + 9 * Math.pow(hache(q, 53, 2), 1.6), Math.sin(a) * rr);
                l.add(new Nuee(p, (float) (2 + 4 * hache(q, 54, 2)), teinte(mixer(SABLE_M, SABLE_C, (float) hache(q, 51, 2)), jour),
                        (float) (0.3 * la) * (sienne ? 0.5F : 1) * pres(p, cam, 4, 10)));
            }
            // les tornades : des entonnoirs qui serpentent, plus larges en haut, qui penchent un peu
            double tor = lisse((s - FORME + 0.6) / 0.7) * (1 - eff) * retombe;
            if (tor > 0.02) {
                for (int k = 0; k < IchibiTempete.TORNADES; k++) {
                    Vec3 base = t.centre.add(IchibiTempete.tornade(k, age));
                    for (int q = 0; q < 46; q++) {
                        double h = q / 46.0, y = h * IchibiTempete.HAUTEUR_TORNADE, rr = 0.9 + y * 0.3 + 0.4 * Math.sin(s * 4 + q);
                        double a = -s * 9 + h * 14 + q * 0.7, plie = Math.sin(s * 0.8 + k) * y * 0.12;
                        Vec3 p = base.add(Math.cos(a) * rr + plie, y, Math.sin(a) * rr);
                        l.add(new Nuee(p, (float) (1 + y * 0.16), teinte(mixer(SABLE_S, SABLE_C, (float) (h * 0.85)), jour),
                                (float) (0.9 * tor) * pres(p, cam, 3, 8)));
                    }
                }
            }
        }
        if (l.isEmpty()) {
            return;
        }
        l.sort((a, b) -> Double.compare(b.p().distanceToSqr(cam), a.p().distanceToSqr(cam)));
        Quaternionf q = camera.rotation();
        Vector3f droite = new Vector3f(1, 0, 0).rotate(q), haut = new Vector3f(0, 1, 0).rotate(q);
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        RenderType type = RenderType.entityTranslucent(BOUFFEE);
        VertexConsumer v = buffers.getBuffer(type);
        PoseStack.Pose pose = ps.last();
        for (Nuee n : l) {
            if (n.alpha() > 0.01F) {
                bouffee(v, pose, droite, haut, n.p().subtract(cam), n.taille(), n.couleur(), n.alpha());
            }
        }
        buffers.endBatch(type);
    }

    /** Un éclair en zigzag de a à b (et trois branches), toujours le même pour la même graine. */
    private static List<Vec3[]> zigzag(Vec3 a, Vec3 b, int graine) {
        List<Vec3[]> segs = new ArrayList<>();
        int n = 14;
        Vec3[] pts = new Vec3[n + 1];
        for (int i = 0; i <= n; i++) {
            double k = i / (double) n, ampl = Math.sin(k * Math.PI);
            pts[i] = a.lerp(b, k).add(i > 0 && i < n ? new Vec3((hache(i, graine, 1) - 0.5) * 5 * ampl, (hache(i, graine, 2) - 0.5) * 2, (hache(i, graine, 3) - 0.5) * 5 * ampl) : Vec3.ZERO);
            if (i > 0) {
                segs.add(new Vec3[] {pts[i - 1], pts[i]});
            }
        }
        for (int br = 0; br < 3; br++) {
            Vec3 o = pts[3 + (int) (hache(br, graine, 9) * 9)];
            Vec3 fin = o.add((hache(br, graine, 10) - 0.5) * 10, -3 - 5 * hache(br, graine, 11), (hache(br, graine, 12) - 0.5) * 10);
            Vec3 mid = o.lerp(fin, 0.5).add(hache(br, graine, 13) * 2 - 1, 0, hache(br, graine, 14) * 2 - 1);
            segs.add(new Vec3[] {o, mid});
            segs.add(new Vec3[] {mid, fin});
        }
        return segs;
    }

    /** Un segment d'éclair : un ruban tourné vers la caméra (les points sont déjà comptés depuis la caméra). */
    private static void ruban(VertexConsumer v, PoseStack.Pose pose, Vec3 a, Vec3 b, float largeur, int r, int g, int bl, int al) {
        Vec3 d = b.subtract(a), cote = d.cross(a).normalize().scale(largeur / 2);
        if (!Double.isFinite(cote.x)) {
            return;
        }
        // les deux faces (on ne sait pas de quel côté la caméra le voit)
        Vec3[] q = {a.subtract(cote), a.add(cote), b.add(cote), b.subtract(cote)};
        for (int sens = 0; sens < 2; sens++) {
            for (int k = 0; k < 4; k++) {
                Vec3 p = q[sens == 0 ? k : 3 - k];
                v.addVertex(pose, (float) p.x, (float) p.y, (float) p.z).setColor(r, g, bl, al).setUv(0.5F, 0.5F).setOverlay(OverlayTexture.NO_OVERLAY)
                        .setLight(LightTexture.FULL_BRIGHT).setNormal(pose, 0, 1, 0);
            }
        }
    }

    /** Les éclairs de sable : sur ceux qu'ils touchent, et de temps en temps dans le mur (on les voit de loin). */
    private static void eclairs(PoseStack ps, Camera camera, float partiel) {
        if (TEMPETES.isEmpty() && ECLAIRS_T.isEmpty()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        Vec3 cam = camera.getPosition();
        ECLAIRS_T.removeIf(x -> secondes(x.debut) > 0.5);
        List<List<Vec3[]>> tous = new ArrayList<>();
        List<Float> forces = new ArrayList<>();
        for (EclairT x : ECLAIRS_T) {
            double u = secondes(x.debut);
            Entity cible = mc.level.getEntity(x.cible);
            Vec3 bas = cible != null ? cible.getPosition(partiel).add(0, cible.getBbHeight() * 0.6, 0) : x.pos.add(0, 1, 0);
            if (!x.vu) {
                x.vu = true;
                mc.level.addParticle(ParticleTypes.FLASH, bas.x, bas.y, bas.z, 0, 0, 0);
                eclat(mc, bas, 30, 0.6);
            }
            float on = Math.sin(u * 60) > -0.3 ? (float) (1 - u / 0.5) : 0.15F;
            tous.add(zigzag(x.haut, bas, x.graine));
            forces.add(on);
        }
        // dans le mur : un éclair toutes les 0,6 s environ, à un endroit qui change
        for (Tempete t : TEMPETES.values()) {
            double s = secondes(t.debut), la = forme(t) * (1 - effondre(t)) * (t.fin < 0 ? 1 : 0);
            if (la < 0.5) {
                continue;
            }
            for (int k = 0; k < 4; k++) {
                double cyc = (s * 1.3 + k * 0.37) % 2.2;
                int n = (int) Math.floor((s * 1.3 + k * 0.37) / 2.2);
                if (cyc > 0.18 || Math.sin(cyc * 90) < -0.4) {
                    continue;
                }
                double a = hache(n, k, 400) * Math.PI * 2, y = 30 + 40 * hache(n, k, 401);
                Vec3 haut = t.centre.add(Math.cos(a) * 44, y + 14, Math.sin(a) * 44), bas = t.centre.add(Math.cos(a + 0.15) * 44, y - 10, Math.sin(a + 0.15) * 44);
                tous.add(zigzag(haut, bas, 500 + n * 7 + k));
                forces.add(1F);
            }
        }
        if (tous.isEmpty()) {
            return;
        }
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        RenderType type = RenderType.entityTranslucentEmissive(BLANC);
        VertexConsumer v = buffers.getBuffer(type);
        PoseStack.Pose pose = ps.last();
        for (int i = 0; i < tous.size(); i++) {
            float f = forces.get(i);
            for (Vec3[] sg : tous.get(i)) {
                Vec3 a = sg[0].subtract(cam), b = sg[1].subtract(cam);
                ruban(v, pose, a, b, 1.8F, 255, 200, 90, (int) (90 * f));
                ruban(v, pose, a, b, 0.55F, 255, 250, 225, (int) (255 * f));
            }
        }
        buffers.endBatch(type);
    }

    /** Les griffes géantes : quatre traînées de sable qui balaient devant lui en diagonale, puis s'effritent. */
    private static void griffures(PoseStack ps, VertexConsumer c, Vec3 cam) {
        GRIFFURES.removeIf(g -> secondes(g.debut()) > 0.55);
        for (Griffure g : GRIFFURES) {
            double t = secondes(g.debut()), p = lisse(t / 0.14), use = lisse((t - 0.16) / 0.35);
            if (use >= 1) {
                continue;
            }
            Vec3 gauche = new Vec3(g.dir().z, 0, -g.dir().x);
            int l = lumiere(g.pos().add(0, 1, 0));
            double sens = g.cote() == 0 ? 1 : -1;
            for (int k = 0; k < 4; k++) {
                double R = 3.1 + k * 0.55;
                int n = 16;
                Vec3 avant = null;
                for (int s = 0; s <= n; s++) {
                    double u = s / (double) n * p;
                    double a = sens * (-1.3 + 2.6 * u);
                    Vec3 ici = g.pos().add(g.dir().scale(Math.cos(a) * R)).add(gauche.scale(Math.sin(a) * R)).add(0, 2.6 - 1.9 * u + k * 0.32, 0).subtract(cam);
                    if (avant != null) {
                        float r = (float) (0.75 * Math.pow(Math.sin(Math.PI * Mth.clamp(u / Math.max(p, 1e-3), 0, 1)), 0.6) * (1 - use)) + 0.05F;
                        segment(ps, c, m(tube, 1), avant, ici, r, l);
                    }
                    avant = ici;
                }
            }
        }
    }

    /** Les pics de sable qui jaillissent du sol (sous les victimes de la tempête, et devant lui quand il abat les deux bras). */
    private static void pics(PoseStack ps, VertexConsumer c, Vec3 cam) {
        Minecraft mc = Minecraft.getInstance();
        PICS.removeIf(x -> secondes(x.debut) > 1.4);
        for (Pic x : PICS) {
            double t = secondes(x.debut);
            if (t < 0) {
                continue;
            }
            if (!x.vu) {
                x.vu = true;
                eclat(mc, x.pos.add(0, 0.5, 0), (int) (30 * x.taille), 0.8 * x.taille);
            }
            double monte = lisse(t / 0.12), tombe = lisse((t - 0.8) / 0.5);
            ps.pushPose();
            ps.translate(x.pos.x - cam.x, x.pos.y - cam.y + (-(1 - monte) * 2.6 - tombe * 2.9) * x.taille, x.pos.z - cam.z);
            ps.mulPose(Axis.YP.rotation((float) (hache((int) (x.pos.x * 7), (int) (x.pos.z * 7), 5) * Math.PI * 2)));
            ps.scale(x.taille, x.taille, x.taille);
            dessiner(m(pics, 0), c, ps, lumiere(x.pos.add(0, 1, 0)), OverlayTexture.NO_OVERLAY);
            ps.popPose();
        }
    }

    private static float[] mixer(float[] a, float[] b, float k) {
        return new float[] {Mth.lerp(k, a[0], b[0]), Mth.lerp(k, a[1], b[1]), Mth.lerp(k, a[2], b[2])};
    }

    private static float[] teinte(float[] c, float k) {
        return new float[] {c[0] * k, c[1] * k, c[2] * k};
    }

    /** Pour les tests en solo : voir sa propre tempête comme la voient les autres (CABANE_TEMPETE_AUTRE). */
    private static final boolean TEMPETE_D_UN_AUTRE = System.getenv("CABANE_TEMPETE_AUTRE") != null;

    /** Ce qu'une tempête fait à la vue de ce joueur : 0 rien, 1 le brouillard de sable épais (pas pour celui qui l'a lancée). */
    private static float brouillard(boolean sienne) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return 0;
        }
        float k = 0;
        for (Map.Entry<UUID, Tempete> e : TEMPETES.entrySet()) {
            Tempete t = e.getValue();
            boolean elle = e.getKey().equals(mc.player.getUUID()) && !TEMPETE_D_UN_AUTRE;
            if (elle != sienne || t.fin >= 0) {
                continue;
            }
            double d = Math.hypot(mc.player.getX() - t.centre.x, mc.player.getZ() - t.centre.z), tasse = 1 - 0.95 * effondre(t);
            float dans = (float) Mth.clamp((IchibiTempete.RAYON * tasse + 6 - d) / 8, 0, 1);
            k = Math.max(k, dans * (float) lisse((secondes(t.debut) - FORME * 0.6) / (FORME * 0.4)));
        }
        return k;
    }

    // ================================================================ le nuage

    private static void nuages(PoseStack ps, Vec3 cam, float partiel) {
        Minecraft mc = Minecraft.getInstance();
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        RenderType type = RenderType.entityCutout(SABLE);
        boolean rien = true;
        for (Map.Entry<UUID, Ichi> e : ICHIS.entrySet()) {
            Ichi i = e.getValue();
            if (i.nuage < 0) {
                continue;
            }
            double fin = i.nuageFin < 0 ? -1 : secondes(i.nuageFin);
            Player j = mc.level.getPlayerByUUID(e.getKey());
            if (fin > 0.6 || j == null) {
                if (fin > 0.6) {
                    i.nuage = -1;
                }
                continue;
            }
            float k = (float) Math.min(1, secondes(i.nuage) / 0.4) * (fin < 0 ? 1 : (float) (1 - fin / 0.6)) * (i.petitNuage ? 0.7F : 1);
            Vec3 p = j.getPosition(partiel);
            ps.pushPose();
            ps.translate(p.x - cam.x, p.y - cam.y - 0.02, p.z - cam.z);
            ps.mulPose(Axis.YP.rotationDegrees(-Mth.rotLerp(partiel, j.yBodyRotO, j.yBodyRot)));
            ps.mulPose(Axis.XP.rotationDegrees(180));
            ps.scale(k, k, k);
            nuage.render(ps, buffers.getBuffer(type), LevelRenderer.getLightColor(mc.level, BlockPos.containing(p.x, p.y + 0.3, p.z)), OverlayTexture.NO_OVERLAY);
            ps.popPose();
            rien = false;
        }
        if (!rien) {
            buffers.endBatch(type);
        }
    }

    // ================================================================ les touches et le panneau

    private static long pretPrise, pretNuage, pretCercueil, pretBoule, pretRocher, pretMausolee, pretVague, finVol, dernierClic;
    private static boolean tempeteFaite;
    private static boolean accroupiAvant;
    /** Quand il s'est accroupi (un appui court, sans clic entre-temps, fait le nuage) ; le dernier appui (deux rapides : quitter le nuage). */
    private static long accroupiDebut, dernierAccroupi;
    private static boolean clicAccroupi;
    private static boolean utiliserRelache = true;

    /** La touche technique (²) : le mausolée. Vrai si c'était pour Ichibi (la touche ne fait alors rien d'autre). */
    public static boolean technique() {
        Minecraft mc = Minecraft.getInstance();
        Ichi i = mc.player == null ? null : ICHIS.get(mc.player.getUUID());
        if (i == null) {
            return false;
        }
        PacketDistributor.sendToServer(new Ichibi.Touche(Ichibi.T_MAUSOLEE));
        return true;
    }

    private static void ecran(GuiGraphics g) {
        Minecraft mc = Minecraft.getInstance();
        Ichi i = mc.player == null ? null : ICHIS.get(mc.player.getUUID());
        if (i == null || mc.options.hideGui) {
            return;
        }
        panneau(g, mc.font, i);
    }

    private static double reste(long pret) {
        return Math.max(0, (pret - System.nanoTime()) / 1e9);
    }

    private static void panneau(GuiGraphics g, Font font, Ichi i) {
        double prise = reste(pretPrise), nuageR = reste(pretNuage), vol = reste(finVol), cerc = reste(pretCercueil), bou = reste(pretBoule),
                roc = reste(pretRocher), maus = reste(pretMausolee), vag = reste(pretVague);
        boolean tient = i.prise >= 0;
        String acc = Clavier.accroupi(), gauche = Clavier.gauche(), droite = Clavier.droite(), tech = Clavier.nom(ClientSharingan.SUSANO);
        String eveil = Clavier.nom(ClientSusano.EVEIL);
        String nuageL = vol > 0 ? "Nuage  " + (int) Math.ceil(vol) + " s" : PanneauPouvoir.recharge(i.niveau == 1 ? "Petit nuage" : "Nuage de sable", nuageR);
        String[][] lignes;
        boolean[] gris;
        if (i.niveau == 1) {
            lignes = new String[][] {{gauche, "Poing de sable"}, {droite, PanneauPouvoir.recharge("Cercueil de sable", cerc)},
                    {acc + " + " + gauche, PanneauPouvoir.recharge("Boule de sable", bou)}, {acc, nuageL},
                    {Clavier.saut() + " / " + acc, vol > 0 ? "Monter / descendre (2× : quitter)" : ""}, {eveil, i.max > 1 ? "Niveau 2" : "Rendormir"}};
            gris = new boolean[] {false, cerc > 0, bou > 0, vol <= 0 && nuageR > 0};
        } else if (i.niveau == 2) {
            lignes = new String[][] {{gauche, tient ? "Jeter" : "Bras d'Ichibi"}, {droite, tient ? "Serrer" : PanneauPouvoir.recharge("Attraper", prise)},
                    {acc + " + " + gauche, PanneauPouvoir.recharge("Rocher de sable", roc)}, {acc + " + " + droite, PanneauPouvoir.recharge("Cercueil de sable", cerc)},
                    {tech, PanneauPouvoir.recharge("Mausolée", maus)}, {acc, nuageL},
                    {Clavier.saut() + " / " + acc, vol > 0 ? "Monter / descendre (2× : quitter)" : ""}, {eveil, i.max > 2 ? "Niveau 3" : "Rendormir"}};
            gris = new boolean[] {false, !tient && prise > 0, roc > 0, cerc > 0, maus > 0, vol <= 0 && nuageR > 0};
        } else {
            Tempete t = Minecraft.getInstance().player == null ? null : TEMPETES.get(Minecraft.getInstance().player.getUUID());
            String ulti = t != null && t.fin < 0 ? "Tempête  " + (int) Math.ceil(Math.max(0, FORME + TEMPETE - secondes(t.debut))) + " s"
                    : tempeteFaite ? "Tempête (faite)" : "Tempête de sable";
            lignes = new String[][] {{gauche, tient ? "Jeter" : "Griffes"}, {droite, tient ? "Serrer" : PanneauPouvoir.recharge("Attraper", prise)},
                    {acc + " + " + gauche, PanneauPouvoir.recharge("Grande vague", vag)}, {acc + " + " + droite, PanneauPouvoir.recharge("3 cercueils", cerc)},
                    {tech, PanneauPouvoir.recharge("Mausolée", maus)}, {Clavier.nom(fr.cabane.client.hachibi4.ClientHachibi4.ULTI), ulti}, {acc, nuageL},
                    {Clavier.saut() + " / " + acc, vol > 0 ? "Monter / descendre (2× : quitter)" : ""}, {eveil, "Rendormir"}};
            gris = new boolean[] {false, !tient && prise > 0, vag > 0, cerc > 0, maus > 0, tempeteFaite && (t == null || t.fin >= 0), vol <= 0 && nuageR > 0};
        }
        PanneauPouvoir.dessiner(g, font, "Ichibi · niveau " + i.niveau, 0xFFD6BA96, lignes, gris);
    }

    /** Le sable qui tombe de lui ; dans une tempête, le sable qui vole autour de la caméra. */
    private static void particules(Minecraft mc, Player j, Ichi i) {
        BlockParticleOption sable = new BlockParticleOption(ParticleTypes.FALLING_DUST, Blocks.SAND.defaultBlockState());
        double t = secondes(i.debut);
        if (t < 1.6 && i.niveau >= 2) {
            for (int k = 0; k < (i.niveau >= 3 ? 12 : 6); k++) {
                double a = HASARD.nextDouble() * Math.PI * 2, r = 0.7 + HASARD.nextDouble() * 0.5;
                mc.level.addParticle(new BlockParticleOption(ParticleTypes.BLOCK, Blocks.SAND.defaultBlockState()), j.getX() + Math.cos(a) * r,
                        j.getY() + HASARD.nextDouble() * 2, j.getZ() + Math.sin(a) * r, -Math.sin(a) * 0.3, 0.1, Math.cos(a) * 0.3);
            }
        }
        if (i.niveau == 1 && HASARD.nextInt(2) == 0) {
            double a = HASARD.nextDouble() * Math.PI * 2;
            mc.level.addParticle(sable, j.getX() + Math.cos(a) * 1.1, j.getY() + HASARD.nextDouble() * 1.4, j.getZ() + Math.sin(a) * 1.1, 0, 0, 0);
        }
        if (i.niveau >= 2 && HASARD.nextInt(3) == 0) {
            float lacet = j.yBodyRot * Mth.DEG_TO_RAD;
            double dx = -Mth.cos(lacet), dz = -Mth.sin(lacet);
            mc.level.addParticle(sable, j.getX() + dx * 0.7 + (HASARD.nextDouble() - 0.5) * 0.5, j.getY() + 0.4 + HASARD.nextDouble() * 1.1,
                    j.getZ() + dz * 0.7 + (HASARD.nextDouble() - 0.5) * 0.5, 0, 0, 0);
        }
    }

    private static void sableTempete(Minecraft mc) {
        for (Tempete t : TEMPETES.values()) {
            double age = age(t), la = lisse((secondes(t.debut) - FORME + 0.4) / 0.6) * (1 - effondre(t)) * (t.fin < 0 ? 1 : 0);
            if (la < 0.1 || mc.player == null || t.centre.distanceToSqr(mc.player.position()) > 90 * 90) {
                continue;
            }
            BlockParticleOption sable = new BlockParticleOption(ParticleTypes.BLOCK, Blocks.SAND.defaultBlockState());
            for (int q = 0; q < IchibiTempete.LAMES; q++) {
                double a = IchibiTempete.angleLame(q, age), r = IchibiTempete.rayonLame(q);
                mc.level.addParticle(sable, t.centre.x + Math.cos(a) * r, t.centre.y + IchibiTempete.hauteurLame(q), t.centre.z + Math.sin(a) * r,
                        Math.sin(a) * 0.2, 0.05, -Math.cos(a) * 0.2);
            }
            for (int k = 0; k < IchibiTempete.TORNADES; k++) {
                Vec3 b = t.centre.add(IchibiTempete.tornade(k, age));
                for (int q = 0; q < 3; q++) {
                    double a = HASARD.nextDouble() * Math.PI * 2;
                    mc.level.addParticle(sable, b.x + Math.cos(a) * 1.5, b.y + HASARD.nextDouble() * 2, b.z + Math.sin(a) * 1.5, -Math.sin(a) * 0.5, 0.4, Math.cos(a) * 0.5);
                }
            }
        }
        float k = Math.max(brouillard(false), brouillard(true) * 0.5F);
        if (k <= 0 || mc.player == null) {
            return;
        }
        Tempete proche = null;
        for (Tempete t : TEMPETES.values()) {
            if (proche == null || t.centre.distanceToSqr(mc.player.position()) < proche.centre.distanceToSqr(mc.player.position())) {
                proche = t;
            }
        }
        if (proche == null) {
            return;
        }
        Vec3 cam = mc.gameRenderer.getMainCamera().getPosition();
        for (int q = 0; q < 26 * k; q++) {
            Vec3 p = cam.add((HASARD.nextDouble() - 0.5) * 16, (HASARD.nextDouble() - 0.4) * 8, (HASARD.nextDouble() - 0.5) * 16);
            Vec3 d = p.subtract(proche.centre);
            double r = Math.max(0.5, Math.hypot(d.x, d.z));
            mc.level.addParticle(new BlockParticleOption(ParticleTypes.BLOCK, Blocks.SAND.defaultBlockState()), p.x, p.y, p.z, -d.z / r * 0.9, 0.05,
                    d.x / r * 0.9);
        }
    }

    @EventBusSubscriber(modid = Cabane.MODID, value = Dist.CLIENT)
    public static final class Couches {
        @SubscribeEvent
        public static void enregistrer(RegisterGuiLayersEvent event) {
            event.registerAboveAll(Cabane.id("ichibi"), (g, delta) -> ecran(g));
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
        /** Ce que ses mains tiennent ne se dessine pas (le grand bras ou la bête les remplace) : main droite, main gauche. */
        private static final Map<Player, ItemStack[]> CACHES = new IdentityHashMap<>();
        /** Ceux qu'on a penchés (la bête court penchée) : il faut redresser après. */
        private static final Set<Player> PENCHES = java.util.Collections.newSetFromMap(new IdentityHashMap<>());

        @SubscribeEvent
        public static void tick(ClientTickEvent.Post event) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.level == null || mc.player == null) {
                ICHIS.clear();
                PORTEURS.clear();
                CERCUEILS.clear();
                TIRS.clear();
                STATUES.clear();
                VAGUES.clear();
                TEMPETES.clear();
                MAINS_T.clear();
                ECLAIRS_T.clear();
                GRIFFURES.clear();
                PICS.clear();
                return;
            }
            Ichi moi = ICHIS.get(mc.player.getUUID());
            boolean accroupi = mc.options.keyShift.isDown();
            long maintenant = System.nanoTime();
            if (moi != null && mc.screen == null && secondes(moi.debut) >= Ichibi.TRANSFORMATION / 20.0) {
                boolean surNuage = moi.nuage >= 0 && moi.nuageFin < 0;
                if (accroupi && !accroupiAvant) {
                    accroupiDebut = maintenant;
                    clicAccroupi = false;
                    // sur le nuage : un appui le fait descendre (le vol) ; deux appuis rapides, il quitte le nuage
                    if (surNuage && maintenant - dernierAccroupi < 350_000_000L && secondes(moi.nuage) > 0.5) {
                        PacketDistributor.sendToServer(new Pouvoirs.Charge());
                    }
                    dernierAccroupi = maintenant;
                }
                // un appui court sur s'accroupir, sans clic pendant : le nuage (accroupi + clic, c'est une autre attaque)
                if (!accroupi && accroupiAvant && !surNuage && !clicAccroupi && secondes(accroupiDebut) < 0.6) {
                    PacketDistributor.sendToServer(new Pouvoirs.Charge());
                }
            }
            accroupiAvant = accroupi;
            if (!mc.options.keyUse.isDown()) {
                utiliserRelache = true;
            }
            for (Map.Entry<UUID, Ichi> e : ICHIS.entrySet()) {
                Player j = mc.level.getPlayerByUUID(e.getKey());
                if (j == null) {
                    continue;
                }
                Ichi i = e.getValue();
                if (i.nuage >= 0 && i.nuageFin < 0) {
                    j.walkAnimation.setSpeed(0);
                    j.walkAnimation.update(0, 1);
                }
                if (!j.isInvisible() && !(j == mc.player && mc.options.getCameraType().isFirstPerson())) {
                    particules(mc, j, i);
                }
            }
            sableTempete(mc);
        }

        /** Éveillé, les clics sont à Ichibi : le gauche frappe (ou jette), le droit attrape (ou serre). */
        @SubscribeEvent
        public static void clic(InputEvent.InteractionKeyMappingTriggered event) {
            Minecraft mc = Minecraft.getInstance();
            Ichi i = mc.player == null ? null : ICHIS.get(mc.player.getUUID());
            if (i == null || !(event.isAttack() || event.isUseItem())) {
                return;
            }
            event.setCanceled(true);
            event.setSwingHand(event.isAttack() && i.niveau >= 2);
            if (mc.options.keyShift.isDown()) {
                clicAccroupi = true;
            }
            long maintenant = System.nanoTime();
            if (secondes(i.debut) < Ichibi.TRANSFORMATION / 20.0 || maintenant - dernierClic < (event.isAttack() ? 200_000_000L : 400_000_000L)
                    || (event.isUseItem() && !utiliserRelache)) {
                return;
            }
            dernierClic = maintenant;
            if (event.isUseItem()) {
                utiliserRelache = false;
            }
            PacketDistributor.sendToServer(event.isAttack() ? new Pouvoirs.Frappe() : new Pouvoirs.Attraper());
        }

        @SubscribeEvent(priority = EventPriority.LOWEST)
        public static void avant(RenderPlayerEvent.Pre event) {
            Player p = event.getEntity();
            Ichi i = ICHIS.get(p.getUUID());
            if (i == null || i.niveau < 2 || p.isInvisible() || CACHES.containsKey(p) || event.isCanceled()) {
                return;
            }
            PlayerModel<AbstractClientPlayer> m = event.getRenderer().getModel();
            m.rightArm.visible = false;
            m.rightSleeve.visible = false;
            boolean bete = i.niveau >= 3;
            if (bete) {
                // la bête : tout est en sable sauf le bas des jambes
                m.head.visible = m.hat.visible = m.body.visible = m.jacket.visible = m.leftArm.visible = m.leftSleeve.visible = false;
            }
            Inventory inv = p.getInventory();
            ItemStack[] cache = new ItemStack[2];
            boolean droitier = p.getMainArm() == HumanoidArm.RIGHT;
            if (droitier || bete) {
                if (inv.selected >= 0 && inv.selected < inv.items.size()) {
                    cache[0] = inv.items.set(inv.selected, ItemStack.EMPTY);
                }
            }
            if (!droitier || bete) {
                cache[1] = inv.offhand.set(0, ItemStack.EMPTY);
            }
            CACHES.put(p, cache);
            if (bete) {
                // il court penché
                PoseStack ps = event.getPoseStack();
                ps.pushPose();
                PENCHES.add(p);
                float lacet = Mth.lerp(event.getPartialTick(), p.yBodyRotO, p.yBodyRot) * Mth.DEG_TO_RAD;
                float penche = 0.18F + Math.min(0.25F, p.walkAnimation.speed(event.getPartialTick()) * 0.3F);
                ps.translate(0, 0.75F, 0);
                ps.mulPose(new Quaternionf().rotateAxis(penche, Mth.cos(lacet), 0, Mth.sin(lacet)));
                ps.translate(0, -0.75F, 0);
            }
        }

        @SubscribeEvent
        public static void apres(RenderPlayerEvent.Post event) {
            Player p = event.getEntity();
            if (PENCHES.remove(p)) {
                event.getPoseStack().popPose();
            }
            ItemStack[] cache = CACHES.remove(p);
            if (cache == null) {
                return;
            }
            Inventory inv = p.getInventory();
            if (cache[0] != null && inv.selected >= 0 && inv.selected < inv.items.size()) {
                inv.items.set(inv.selected, cache[0]);
            }
            if (cache[1] != null) {
                inv.offhand.set(0, cache[1]);
            }
            PlayerModel<AbstractClientPlayer> m = event.getRenderer().getModel();
            m.rightArm.visible = m.rightSleeve.visible = true;
            m.head.visible = m.hat.visible = m.body.visible = m.jacket.visible = m.leftArm.visible = m.leftSleeve.visible = true;
        }

        /** À la première personne (niveaux 2 et 3) : le grand bras, qui frappe avec le coup. */
        @SubscribeEvent
        public static void premiere(RenderHandEvent event) {
            Minecraft mc = Minecraft.getInstance();
            AbstractClientPlayer moi = mc.player;
            Ichi i = moi == null ? null : ICHIS.get(moi.getUUID());
            if (i == null || i.niveau < 2) {
                return;
            }
            HumanoidArm cote = event.getHand() == InteractionHand.MAIN_HAND ? moi.getMainArm() : moi.getMainArm().getOpposite();
            if (cote != HumanoidArm.RIGHT) {
                if (i.niveau >= 3) {
                    event.setCanceled(true);
                }
                return;
            }
            event.setCanceled(true);
            if (i.prise >= 0 && moi.level().getEntity(i.prise) != null) {
                return;
            }
            modeles();
            PoseStack ps = event.getPoseStack();
            float coup = event.getSwingProgress(), equipe = event.getEquipProgress(), racine = Mth.sqrt(coup);
            ps.pushPose();
            ps.translate(-0.3F * Mth.sin(racine * Mth.PI) + 0.64F, 0.4F * Mth.sin(racine * Mth.TWO_PI) - 0.6F + equipe * -0.6F,
                    -0.4F * Mth.sin(coup * Mth.PI) - 0.72F);
            ps.mulPose(Axis.YP.rotationDegrees(45F));
            ps.mulPose(Axis.YP.rotationDegrees(Mth.sin(racine * Mth.PI) * 70F));
            ps.mulPose(Axis.ZP.rotationDegrees(Mth.sin(coup * coup * Mth.PI) * -20F));
            ps.translate(-1F, 3.6F, 3.5F);
            ps.mulPose(Axis.ZP.rotationDegrees(120F));
            ps.mulPose(Axis.XP.rotationDegrees(200F));
            ps.mulPose(Axis.YP.rotationDegrees(-135F));
            ps.translate(5.6F, 0, 0);
            ps.translate(-5 / 16F, (fin(moi) ? 2.5F : 2F) / 16, 0);
            boolean poing = i.prise >= 0 || coup > 0;
            float k = (float) Mth.clamp((secondes(i.debut) - 0.35) / 0.9, 0.2, 1) * 0.72F * (i.niveau >= 3 ? 1.2F : 1);
            VertexConsumer c = event.getMultiBufferSource().getBuffer(RenderType.entityCutoutNoCull(SABLE));
            bras(ps, c, event.getPackedLight(), OverlayTexture.NO_OVERLAY, new Pose(0, 0, 0.05F, -0.08F, 0, poing ? 1.55F : 0.45F), k);
            ps.popPose();
        }

        @SubscribeEvent
        public static void monde(RenderLevelStageEvent event) {
            if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_ENTITIES || Minecraft.getInstance().level == null
                    || ICHIS.isEmpty() && CERCUEILS.isEmpty() && TIRS.isEmpty() && STATUES.isEmpty() && VAGUES.isEmpty() && TEMPETES.isEmpty() && GRIFFURES.isEmpty() && PICS.isEmpty() && MAINS_T.isEmpty() && ECLAIRS_T.isEmpty()) {
                return;
            }
            float partiel = event.getPartialTick().getGameTimeDeltaPartialTick(false);
            ClientIchibi.monde(event.getPoseStack(), event.getCamera(), partiel);
            brasTendu(event.getPoseStack(), event.getCamera(), partiel);
        }

        /** Dans la tempête d'un autre : un brouillard de sable épais, on ne voit presque rien. */
        @SubscribeEvent
        public static void brume(ViewportEvent.RenderFog event) {
            float k = brouillard(false);
            if (k > 0) {
                event.setFarPlaneDistance(Mth.lerp(k, event.getFarPlaneDistance(), 9));
                event.setNearPlaneDistance(Mth.lerp(k, event.getNearPlaneDistance(), -2));
                event.setCanceled(true);
            }
        }

        @SubscribeEvent
        public static void couleur(ViewportEvent.ComputeFogColor event) {
            float k = Math.max(brouillard(false), brouillard(true) * 0.45F);
            if (k > 0) {
                event.setRed(Mth.lerp(k, event.getRed(), 0.62F));
                event.setGreen(Mth.lerp(k, event.getGreen(), 0.45F));
                event.setBlue(Mth.lerp(k, event.getBlue(), 0.25F));
            }
        }

        private Evenements() {
        }
    }

    private ClientIchibi() {
    }
}
