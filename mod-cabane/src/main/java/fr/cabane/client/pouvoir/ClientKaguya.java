package fr.cabane.client.pouvoir;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import fr.cabane.Cabane;
import fr.cabane.pouvoir.Kaguya;
import fr.cabane.pouvoir.KaguyaDino;
import fr.cabane.pouvoir.Pouvoirs;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import net.minecraft.client.resources.PlayerSkin;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.MovementInputUpdateEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.event.RenderPlayerEvent;
import net.neoforged.neoforge.client.extensions.common.IClientItemExtensions;
import net.neoforged.neoforge.client.extensions.common.RegisterClientExtensionsEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Ce qu'on voit du pouvoir Kaguya (voir {@link Kaguya} et docs/kaguya) : torse nu avec le bas de la marque maudite et le
 * haut qui pend à la taille, six os qui sortent du dos (des bosses d'abord), les piques des poings, l'épée colonne (un
 * objet), les lances des bras, la danse du saule, la peau de la marque maudite et la queue au niveau 3. Les os sont les
 * mêmes que sur les planches (docs/kaguya/generateur/jeu.mjs → assets/cabane/kaguya). Et les coups : les balles d'os, les
 * piques du sol, le fouet, le grand fouet, la forêt d'os, les pieux. Le dinosaure de l'ulti est dans {@link ClientKaguyaDino}.
 */
public final class ClientKaguya {
    private static final RandomSource HASARD = RandomSource.create();
    private static final BlockParticleOption OS_PART = new BlockParticleOption(ParticleTypes.BLOCK, Blocks.BONE_BLOCK.defaultBlockState());

    static double secondes(long depuis) {
        return (System.nanoTime() - depuis) / 1e9;
    }

    static double lisse(double x) {
        x = Mth.clamp(x, 0, 1);
        return x * x * (3 - 2 * x);
    }

    private static Vec3 vec(Vector3f v) {
        return new Vec3(v.x(), v.y(), v.z());
    }

    // ================================================================ qui est éveillée

    static final class Etat {
        String pouvoir;
        int niveau;
        long debut1 = -1, debut2 = -1, debut3 = -1;
        long fin = -1;
        int niveauFin;
        long lances = -1, lancesFin = -1;
        boolean grosses;
        long saule = -1, pieux = -1, queue = -1, phalanges = -1;
        /** Les moments des transformations déjà joués (une fois chacun). */
        final boolean[] fait = new boolean[24];
    }

    private static final Map<UUID, Etat> ETATS = new HashMap<>();

    static Etat etat(UUID qui) {
        return ETATS.get(qui);
    }

    /** Elle est éveillée (et pas en train de s'endormir). */
    public static boolean eveillee(UUID qui) {
        Etat e = ETATS.get(qui);
        return e != null && e.fin < 0 && e.niveau > 0;
    }

    public static void recevoir(Pouvoirs.Etat p) {
        long loin = System.nanoTime() - 60_000_000_000L;
        if (!p.eveille()) {
            Etat e = ETATS.get(p.joueur());
            if (e != null && e.fin < 0) {
                e.fin = System.nanoTime();
                e.niveauFin = e.niveau;
                e.niveau = 0;
            }
            return;
        }
        Etat e = ETATS.get(p.joueur());
        if (e == null || e.fin >= 0) {
            e = new Etat();
            ETATS.put(p.joueur(), e);
        }
        e.pouvoir = p.pouvoir();
        long maintenant = p.annonce() ? System.nanoTime() : loin;
        if (p.niveau() >= 1 && e.debut1 < 0) {
            e.debut1 = maintenant;
        }
        if (p.niveau() >= 2 && e.debut2 < 0) {
            e.debut2 = p.niveau() == 2 ? maintenant : loin;
        }
        if (p.niveau() >= 3 && e.debut3 < 0) {
            e.debut3 = maintenant;
        }
        // un éveil qui saute des niveaux (un joueur qui arrive) : ce qui précède est déjà là
        if (p.niveau() >= 2) {
            e.debut1 = Math.min(e.debut1, loin);
        }
        if (p.niveau() >= 3) {
            e.debut2 = Math.min(e.debut2, loin);
        }
        e.niveau = p.niveau();
        Minecraft mc = Minecraft.getInstance();
        if (p.annonce() && mc.player != null && p.joueur().equals(mc.player.getUUID()) && p.niveau() >= 3) {
            ultiFaite = false;
        }
    }

    public static void oublier(UUID joueur) {
        ETATS.remove(joueur);
    }

    // ================================================================ les coups (ce que le serveur annonce)

    private record Balle(int id, Vec3 depart, Vec3 dir, long debut) {
    }

    private record Ligne(Vec3 depart, Vec3 dir, int longueur, long debut, double[] sols) {
    }

    private record Fouet(UUID qui, Vec3 bout, int cible, long debut) {
    }

    private record GrandFouet(UUID qui, Vec3 dir, double rayon, long debut) {
    }

    /** Une forêt d'os : le centre, les piques (x, z, rayon, hauteur), leur sol, quand elle pousse, quand elle s'effrite. */
    private static final class Foret {
        Vec3 centre;
        float[][] pics;
        double[] sols;
        long debut, fin = -1;
        double duree = -1;
        int graine;
    }

    private record Pieu(UUID qui, int i, Vec3 depart, Vec3 point, int cible, long debut) {
    }

    private static final List<Balle> BALLES = new ArrayList<>();
    private static final List<Ligne> LIGNES = new ArrayList<>();
    private static final List<Fouet> FOUETS = new ArrayList<>();
    private static final List<GrandFouet> GRANDS = new ArrayList<>();
    private static final List<Foret> FORETS = new ArrayList<>();
    private static final List<Pieu> PIEUX = new ArrayList<>();

    public static void effet(Kaguya.Effet p) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return;
        }
        Vec3 pos = vec(p.pos()), dir = vec(p.dir());
        long t = System.nanoTime();
        Etat e = ETATS.get(p.joueur());
        Player j = mc.level.getPlayerByUUID(p.joueur());
        switch (p.sorte()) {
            case Kaguya.E_BALLE -> BALLES.add(new Balle(p.n(), pos, dir, t));
            case Kaguya.E_BALLE_FIN -> {
                BALLES.removeIf(b -> b.id() == p.n());
                for (int k = 0; k < 4; k++) {
                    mc.level.addParticle(OS_PART, pos.x, pos.y, pos.z, HASARD.nextGaussian() * 0.1, 0.1, HASARD.nextGaussian() * 0.1);
                }
            }
            case Kaguya.E_PHALANGES -> {
                if (e != null) {
                    e.phalanges = t;
                }
            }
            case Kaguya.E_PIQUES -> {
                double[] sols = new double[p.n() + 1];
                for (int k = 0; k <= p.n(); k++) {
                    sols[k] = sol(mc.level, pos.add(dir.scale(k)));
                }
                LIGNES.add(new Ligne(pos, dir, p.n(), t, sols));
                for (int k = 1; k <= p.n(); k += 2) {
                    jaillir(mc.level, new Vec3(pos.x + dir.x * k, sols[k] + 0.2, pos.z + dir.z * k), new Vec3(0, 1, 0), 2, 4, 0.18F, false);
                }
            }
            case Kaguya.E_FOUET -> {
                FOUETS.add(new Fouet(p.joueur(), dir, p.n(), t));
                if (j != null) {
                    j.swing(InteractionHand.MAIN_HAND);
                }
            }
            case Kaguya.E_SAULE -> {
                if (e != null) {
                    e.saule = t;
                }
                if (j != null) {
                    for (int k = 0; k < 8; k++) {
                        double a = k * Mth.TWO_PI / 8;
                        jaillir(mc.level, j.position().add(Math.cos(a) * 0.4, 0.6 + (k % 3) * 0.45, Math.sin(a) * 0.4), new Vec3(Math.cos(a), 0.3, Math.sin(a)), 4, 5, 0.16F, k == 0);
                    }
                }
                for (int k = 0; k < 30; k++) {
                    mc.level.addParticle(OS_PART, pos.x + HASARD.nextGaussian() * 0.6, pos.y + 1 + HASARD.nextGaussian() * 0.5, pos.z + HASARD.nextGaussian() * 0.6, 0, 0.1, 0);
                }
            }
            case Kaguya.E_LANCES -> {
                if (e != null) {
                    if (p.phase() == 0 && j != null) {
                        jaillir(mc.level, corps(j, 1, -6, 12, 1), corps(j, 1, -6, 2, 8).subtract(j.position()), 10, 4, 0.2F, true);
                        jaillir(mc.level, corps(j, 1, 6, 12, 1), corps(j, 1, 6, 2, 8).subtract(j.position()), 10, 4, 0.2F, false);
                    }
                    if (p.phase() == 0) {
                        e.lances = t;
                        e.lancesFin = -1;
                        e.grosses = p.n() == 1;
                    } else {
                        e.lancesFin = t;
                    }
                }
            }
            case Kaguya.E_FONCE -> {
                for (int k = 0; k < 20; k++) {
                    mc.level.addParticle(ParticleTypes.CLOUD, pos.x + HASARD.nextGaussian() * 0.3, pos.y + 0.2, pos.z + HASARD.nextGaussian() * 0.3, -dir.x * 0.2, 0.02, -dir.z * 0.2);
                }
            }
            case Kaguya.E_GRAND_FOUET -> GRANDS.add(new GrandFouet(p.joueur(), dir, p.n(), t));
            case Kaguya.E_FORET -> {
                if (p.phase() == 0) {
                    Foret f = foret(mc.level, pos, p.n(), Kaguya.foret(p.n()), t);
                    FORETS.add(f);
                    for (int k = 0; k < f.pics.length; k += 3) {
                        jaillir(mc.level, new Vec3(pos.x + f.pics[k][0], f.sols[k] + 0.2, pos.z + f.pics[k][1]), new Vec3(0, 1, 0), 3, 6, 0.25F, k % 12 == 0);
                    }
                    if (mc.player != null && mc.player.distanceTo(j == null ? mc.player : j) < 30) {
                        ClientVent.secouer(1.6F);
                    }
                } else {
                    for (Foret f : FORETS) {
                        if (f.graine == p.n() && f.fin < 0) {
                            f.fin = t;
                            for (int k = 0; k < f.pics.length; k += 2) {
                                float[] q = f.pics[k];
                                mc.level.addParticle(OS_PART, f.centre.x + q[0], f.sols[k] + q[3] * 0.4, f.centre.z + q[1], 0, 0.15, 0);
                            }
                        }
                    }
                }
            }
            case Kaguya.E_PIEU -> {
                PIEUX.add(new Pieu(p.joueur(), p.phase(), pos, dir, p.n(), t));
                jaillir(mc.level, pos, new Vec3(0, 1, 0), 4, 4, 0.16F, p.phase() == 0);
                if (e != null && p.phase() == 0) {
                    e.pieux = t;
                }
            }
            case Kaguya.E_QUEUE -> {
                if (e != null) {
                    e.queue = t;
                }
            }
            default -> { }
        }
    }

    private static Foret foret(Level level, Vec3 centre, int graine, float[][] pics, long debut) {
        Foret f = new Foret();
        f.centre = centre;
        f.pics = pics;
        f.graine = graine;
        f.debut = debut;
        f.sols = new double[pics.length];
        for (int k = 0; k < pics.length; k++) {
            f.sols[k] = sol(level, centre.add(pics[k][0], 0, pics[k][1]));
        }
        return f;
    }

    /** Des piques d'os sortent du sol autour d'un point, puis s'effritent d'elles-mêmes (le sol qui se fend, à l'ulti). */
    static void piquesAutour(Vec3 centre, int graine, double rayon, double duree) {
        piquesAutour(centre, graine, rayon, duree, 28, 1.8, 3.6, 0.8);
    }

    /**
     * n piques d os qui sortent du sol autour du centre (dans un anneau de « vide » à rayon) : jusqu à hmax blocs de haut et
     * rmax de large (les plus hautes près du centre), puis elles s effritent au bout de duree secondes.
     */
    static void piquesAutour(Vec3 centre, int graine, double rayon, double duree, int n, double vide, double hmax, double rmax) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return;
        }
        float[][] pics = new float[n][];
        for (int k = 0; k < n; k++) {
            double r = vide + (rayon - vide) * Math.sqrt(Kaguya.hache(k, 1, graine)), a = Kaguya.hache(k, 2, graine) * Mth.TWO_PI;
            double pres = 1 - 0.5 * (r - vide) / Math.max(0.1, rayon - vide);
            pics[k] = new float[]{(float) (Math.cos(a) * r), (float) (Math.sin(a) * r), (float) (rmax * (0.5 + 0.5 * Kaguya.hache(k, 3, graine))),
                    (float) (hmax * pres * (0.4 + 0.6 * Kaguya.hache(k, 4, graine)))};
        }
        Foret f = foret(mc.level, centre, graine, pics, System.nanoTime());
        f.duree = duree;
        FORETS.add(f);
    }

    /** Le dessus du sol sous ce point (on cherche un peu au-dessus, puis en dessous). */
    static double sol(Level level, Vec3 p) {
        BlockPos.MutableBlockPos pos = BlockPos.containing(p.x, p.y + 3, p.z).mutable();
        for (int i = 0; i < 12; i++) {
            if (!level.getBlockState(pos).getCollisionShape(level, pos).isEmpty()) {
                return pos.getY() + 1;
            }
            pos.move(0, -1, 0);
        }
        return p.y;
    }

    // ================================================================ la peau

    private static final class Peau {
        ResourceLocation nue, maudite;
        final Map<ResourceLocation, PlayerSkin> remplacees = new HashMap<>();
    }

    private static final Map<ResourceLocation, Peau> PEAUX = new HashMap<>();
    private static final Map<ResourceLocation, Long> RATEES = new HashMap<>();
    /** L'iris jaune du niveau 3 (ABGR). */
    private static final int IRIS = 0xFF24C4EE;

    /** Appelé à chaque {@code getSkin()} (AbstractClientPlayerMixin) : torse nu ; au niveau 3, la peau de la marque maudite. */
    public static PlayerSkin peau(AbstractClientPlayer joueur, PlayerSkin skin) {
        Etat e = ETATS.get(joueur.getUUID());
        if (e == null || e.fin >= 0 && secondes(e.fin) > 0.4) {
            return skin;
        }
        Peau p = PEAUX.get(skin.texture());
        if (p == null) {
            if (System.nanoTime() < RATEES.getOrDefault(skin.texture(), 0L)) {
                return skin;
            }
            p = preparer(skin);
            if (p == null) {
                RATEES.put(skin.texture(), System.nanoTime() + 2_000_000_000L);
                return skin;
            }
        }
        int niveau = e.fin >= 0 ? e.niveauFin : e.niveau;
        ResourceLocation id = niveau >= 3 && e.debut3 >= 0 && secondes(e.debut3) > 0.6 && p.maudite != null ? p.maudite : p.nue;
        if (id == null) {
            return skin;
        }
        PlayerSkin s = p.remplacees.get(id);
        if (s == null || s.model() != skin.model()) {
            s = new PlayerSkin(id, skin.textureUrl(), skin.capeTexture(), skin.elytraTexture(), skin.model(), skin.secure());
            p.remplacees.put(id, s);
        }
        return s;
    }

    private static Peau preparer(PlayerSkin skin) {
        int[] base = ClientMaudit.lire(skin.texture());
        int[] bas = ClientMaudit.basSasuke();
        if (base == null || bas == null) {
            return null;
        }
        boolean fin = skin.model() == PlayerSkin.Model.SLIM;
        double[] teint = ClientMaudit.teint(base);
        Peau p = new Peau();
        int[] nue = ClientMaudit.torseNu(base, bas, fin, teint);
        p.nue = ClientMaudit.enregistrer(nue, "kaguya_" + PEAUX.size());
        p.maudite = ClientMaudit.enregistrer(ClientMaudit.forme2(nue.clone(), fin, teint, IRIS), "kaguya3_" + PEAUX.size());
        PEAUX.put(skin.texture(), p);
        return p;
    }

    // ================================================================ sur le joueur

    /** Une pièce portée (un groupe par partie du joueur), grossie de k. */
    private static void piece(String nom, PoseStack ps, MultiBufferSource buffers, int lumiere, ModelPart[] parties, float k, float alpha) {
        if (k <= 0.001F || alpha <= 0.01F) {
            return;
        }
        ModeleVent[] l = ModeleVent.lire("kaguya", nom);
        for (int i = 0; i < l.length && i < parties.length; i++) {
            ps.pushPose();
            parties[i].translateAndRotate(ps);
            ps.scale(k, k, k);
            l[i].dessiner(buffers, ps.last(), lumiere, 1 / 16F, alpha);
            ps.popPose();
        }
    }

    /** La base de la queue sur le corps, et sa longueur (en pixels du joueur). */
    private static final float QUEUE_Y = 12, QUEUE_Z = 1.6F, QUEUE_L = 32.6F;

    /**
     * La queue est vivante : une ondulation la parcourt sans arrêt, nulle à la base, de plus en plus forte vers le bout
     * (environ un tiers de bloc), avec deux rythmes mêlés pour que ça ne fasse pas métronome. Chacune a son propre décalage.
     */
    private static ModeleVent.Deformation ondulation(AbstractClientPlayer joueur) {
        double t = System.nanoTime() / 1e9 + joueur.getId() * 1.7;
        return (x, y, z, s) -> {
            float dy = y - QUEUE_Y, dz = z - QUEUE_Z;
            double w = Math.min(1, Math.sqrt(x * x + dy * dy + dz * dz) / QUEUE_L), w2 = w * w;
            double lacet = 0.20 * w2 * Math.sin(1.6 * t - 2.4 * w) + 0.06 * w * Math.sin(3.3 * t - 5 * w);
            double tangage = 0.10 * w2 * Math.sin(1.2 * t - 2 * w + 0.8);
            float cl = (float) Math.cos(lacet), sl = (float) Math.sin(lacet), ct = (float) Math.cos(tangage), st = (float) Math.sin(tangage);
            float x1 = x * cl + dz * sl, z1 = -x * sl + dz * cl;
            s.set(x1, dy * ct - z1 * st + QUEUE_Y, dy * st + z1 * ct + QUEUE_Z);
        };
    }

    /** L'étape (1 à n) d'une pièce qui pousse de « de » à « a » secondes ; 0 avant. */
    private static int etape(double t, double de, double a, int n) {
        if (t < de) {
            return 0;
        }
        return Mth.clamp(1 + (int) ((t - de) / (a - de) * n), 1, n);
    }

    static void dessiner(PoseStack ps, MultiBufferSource buffers, int lumiere, AbstractClientPlayer joueur, PlayerModel<AbstractClientPlayer> m, Etat e) {
        ModelPart[] parties = {m.head, m.body, m.rightArm, m.leftArm, m.rightLeg, m.leftLeg};
        float efface = e.fin >= 0 ? (float) Math.max(0, 1 - secondes(e.fin) / 0.35) : 1;
        if (efface <= 0) {
            return;
        }
        int niveau = e.fin >= 0 ? e.niveauFin : e.niveau;
        double t1 = e.debut1 < 0 ? 99 : secondes(e.debut1), t3 = e.debut3 < 0 ? 99 : secondes(e.debut3);
        piece("robe", ps, buffers, lumiere, parties, 1, efface);
        // l'éveil 1 : des bosses gonflent dans son dos, puis les os percent et poussent
        if (t1 < 0.8) {
            // elles palpitent, de plus en plus fort, avant de percer
            float pal = (float) (lisse(t1 / 0.4) * (1 + 0.12 * Math.sin(t1 * 30) * t1));
            piece("bosses", ps, buffers, lumiere, parties, pal, efface);
        }
        int os = etape(t1, 0.8, 1.6, 4);
        // les pieux : les os du dos sont partis, puis repoussent en 5 s
        if (e.pieux >= 0) {
            double tp = secondes(e.pieux) - (Kaguya.PIEU_MONTE * 0.5) / 20.0;
            if (tp > 0) {
                os = Math.min(os, etape(tp, 1.2, 1.2 + Kaguya.REPOUSSE_PIEUX / 20.0, 4));
            }
        }
        if (os > 0) {
            piece("os_dos_" + os, ps, buffers, lumiere, parties, 1, efface);
        }
        // niveau 3 : la queue pousse du bas du dos ; elle ondule sans arrêt, et balaie quand elle frappe derrière
        if (niveau >= 3) {
            int q = etape(t3, 1.0, 2.2, 4);
            ModeleVent[] l = q > 0 ? ModeleVent.lire("kaguya", "queue_" + q) : new ModeleVent[0];
            if (l.length > 1) {
                ps.pushPose();
                m.body.translateAndRotate(ps);
                if (e.queue >= 0 && secondes(e.queue) < 0.6) {
                    float a = (float) Math.sin(secondes(e.queue) / 0.6 * Math.PI) * 0.9F;
                    ps.translate(0, QUEUE_Y / 16F, QUEUE_Z / 16F);
                    ps.mulPose(Axis.YP.rotation(a));
                    ps.translate(0, -QUEUE_Y / 16F, -QUEUE_Z / 16F);
                }
                l[1].dessiner(buffers, ps.last(), lumiere, 1 / 16F, efface, ondulation(joueur));
                ps.popPose();
            }
        }
        // les lances : elles poussent sur ses bras (l'épée est rentrée), puis rentrent
        if (e.lances >= 0) {
            double tl = secondes(e.lances);
            double sort = e.lancesFin >= 0 ? 1 - secondes(e.lancesFin) / 0.4 : 1;
            if (sort > 0) {
                int s = sort < 1 ? Mth.clamp((int) Math.ceil(sort * 3), 1, 3) : etape(tl, 0, 0.5, 3);
                if (s > 0) {
                    piece((e.grosses ? "lances3_" : "lances_") + s, ps, buffers, lumiere, parties, 1, efface);
                }
            }
        }
        // la danse du saule : des os sortent de tout son corps
        if (e.saule >= 0 && secondes(e.saule) < 1.1) {
            double ts = secondes(e.saule);
            float k = (float) (lisse(ts / 0.15) * (1 - lisse((ts - 0.8) / 0.3)));
            piece("saule", ps, buffers, lumiere, parties, k, efface);
        }
        // les poings d'os, à mains nues
        boolean lances = e.lances >= 0 && e.lancesFin < 0;
        if (niveau >= 1 && !lances && t1 > 1.0 && joueur.getMainHandItem().isEmpty() && !ClientKaguyaDino.monte(joueur.getUUID())) {
            piece("poings", ps, buffers, lumiere, new ModelPart[]{m.head, m.body, m.rightArm, m.leftArm, m.rightLeg, m.leftLeg}, 1, efface);
        }
    }

    public static final class Couche extends RenderLayer<AbstractClientPlayer, PlayerModel<AbstractClientPlayer>> {
        public Couche(RenderLayerParent<AbstractClientPlayer, PlayerModel<AbstractClientPlayer>> parent) {
            super(parent);
        }

        @Override
        public void render(PoseStack ps, MultiBufferSource buffers, int lumiere, AbstractClientPlayer joueur, float marche, float vitesse,
                float partiel, float age, float tete, float tangage) {
            Etat e = ETATS.get(joueur.getUUID());
            if (e == null || joueur.isInvisible()) {
                return;
            }
            dessiner(ps, buffers, lumiere, joueur, getParentModel(), e);
        }
    }

    /** Les poses : pliée en avant à l'éveil 1, la main dans le dos à l'éveil 2, les bras en croix pour le saule, la main au sol à l'ulti. */
    public static void poser(PlayerModel<?> m, Player joueur) {
        Etat e = ETATS.get(joueur.getUUID());
        if (e == null || e.fin >= 0) {
            return;
        }
        double t1 = e.debut1 < 0 ? 99 : secondes(e.debut1), t2 = e.debut2 < 0 ? 99 : secondes(e.debut2);
        boolean change = false;
        if (t1 < 1.6 && e.niveau == 1) {
            // elle se plie en avant (les bosses), puis se redresse d'un coup (les os percent)
            float k = (float) (t1 < 0.8 ? lisse(t1 / 0.3) : 1 - lisse((t1 - 0.8) / 0.2));
            m.head.xRot = Mth.lerp(k, m.head.xRot, 0.6F);
            m.body.xRot = Mth.lerp(k, m.body.xRot, 0.45F);
            m.rightArm.xRot = Mth.lerp(k, m.rightArm.xRot, 0.5F);
            m.leftArm.xRot = Mth.lerp(k, m.leftArm.xRot, 0.5F);
            m.rightArm.zRot = Mth.lerp(k, m.rightArm.zRot, 0.35F);
            m.leftArm.zRot = Mth.lerp(k, m.leftArm.zRot, -0.35F);
            change = true;
        }
        if (t2 < 1.4 && e.niveau == 2) {
            // la main passe dans son dos, attrape sa colonne par la nuque, et la tire
            float k = (float) (lisse(t2 / 0.3) * (1 - lisse((t2 - 1.1) / 0.3)));
            float tire = (float) lisse((t2 - 0.6) / 0.5);
            m.rightArm.xRot = Mth.lerp(k, m.rightArm.xRot, -2.9F + 1.6F * tire);
            m.rightArm.zRot = Mth.lerp(k, m.rightArm.zRot, 0.3F);
            m.rightArm.yRot = Mth.lerp(k, m.rightArm.yRot, -0.4F);
            m.head.xRot = Mth.lerp(k, m.head.xRot, 0.3F);
            change = true;
        }
        if (e.saule >= 0 && secondes(e.saule) < 1.0) {
            float k = (float) (lisse(secondes(e.saule) / 0.15) * (1 - lisse((secondes(e.saule) - 0.8) / 0.2)));
            m.rightArm.zRot = Mth.lerp(k, m.rightArm.zRot, 1.5F);
            m.leftArm.zRot = Mth.lerp(k, m.leftArm.zRot, -1.5F);
            m.rightArm.xRot = Mth.lerp(k, m.rightArm.xRot, 0);
            m.leftArm.xRot = Mth.lerp(k, m.leftArm.xRot, 0);
            change = true;
        }
        if (e.phalanges >= 0 && secondes(e.phalanges) < 0.5) {
            // le bras tendu, le bout des doigts vers la cible
            m.rightArm.xRot = m.head.xRot - Mth.HALF_PI;
            m.rightArm.yRot = m.head.yRot;
            change = true;
        }
        if (ClientKaguyaDino.poser(m, joueur)) {
            change = true;
        }
        if (change) {
            m.hat.copyFrom(m.head);
            m.jacket.copyFrom(m.body);
            m.rightSleeve.copyFrom(m.rightArm);
            m.leftSleeve.copyFrom(m.leftArm);
            m.rightPants.copyFrom(m.rightLeg);
            m.leftPants.copyFrom(m.leftLeg);
        }
    }

    // ================================================================ les coups, dans le monde

    private static void modele(PoseStack ps, MultiBufferSource buffers, String nom, int lumiere, float e, float alpha) {
        ModeleVent[] l = ModeleVent.lire("kaguya", nom);
        if (l.length > 0) {
            l[0].dessiner(buffers, ps.last(), lumiere, e, alpha);
        }
    }

    private static int lumiere(Level level, Vec3 p) {
        return LevelRenderer.getLightColor(level, BlockPos.containing(p));
    }

    /** Tourne +z (le sens des modèles « pieu », « vertèbre ») vers dir. */
    private static void viser(PoseStack ps, Vec3 dir) {
        Vector3f d = new Vector3f((float) dir.x, (float) dir.y, (float) dir.z);
        if (d.lengthSquared() < 1e-6) {
            return;
        }
        d.normalize();
        ps.mulPose(new Quaternionf().rotationTo(new Vector3f(0, 0, 1), d));
    }

    /** Une pique du sol (le modèle fait 16 pixels de haut, 3 de rayon) : haute de h blocs, large de r, sortie à k. */
    private static void pique(PoseStack ps, MultiBufferSource buffers, Level level, Vec3 cam, Vec3 pied, double h, double r, double k, double penche, int g) {
        if (k <= 0.01) {
            return;
        }
        ps.pushPose();
        ps.translate(pied.x - cam.x, pied.y - cam.y - 0.3, pied.z - cam.z);
        ps.mulPose(Axis.YP.rotation((float) (Kaguya.hache(g, 7, 3) * Mth.TWO_PI)));
        ps.mulPose(Axis.XP.rotation((float) penche));
        float sy = (float) (h * k / 16), sxz = (float) (r / 3);
        ps.scale(sxz, sy, sxz);
        modele(ps, buffers, "pique", lumiere(level, pied.add(0, 1, 0)), 1, 1);
        ps.popPose();
    }

    /** La main droite d'un joueur, dans le monde (à peu près). */
    private static Vec3 main(Player j, float partiel) {
        float lacet = Mth.lerp(partiel, j.yBodyRotO, j.yBodyRot) * Mth.DEG_TO_RAD;
        Vec3 droite = new Vec3(-Mth.cos(lacet), 0, -Mth.sin(lacet)), devant = new Vec3(-Mth.sin(lacet), 0, Mth.cos(lacet));
        return j.getPosition(partiel).add(0, 1.0, 0).add(droite.scale(0.4)).add(devant.scale(0.3));
    }

    /** Une chaîne de vertèbres de a à b (le fouet), qui pend un peu au milieu. */
    private static void chaine(PoseStack ps, MultiBufferSource buffers, Level level, Vec3 cam, Vec3 a, Vec3 b, double pend) {
        double d = a.distanceTo(b);
        int n = Math.max(2, (int) (d / 0.32));
        int l = lumiere(level, a.add(b).scale(0.5));
        Vec3 prec = a;
        for (int i = 1; i <= n; i++) {
            double u = i / (double) n;
            Vec3 p = a.lerp(b, u).add(0, -pend * 4 * u * (1 - u), 0);
            Vec3 mid = prec.add(p).scale(0.5);
            ps.pushPose();
            ps.translate(mid.x - cam.x, mid.y - cam.y, mid.z - cam.z);
            viser(ps, p.subtract(prec));
            ps.mulPose(Axis.ZP.rotation(i * 0.9F));
            float e = 0.13F * (float) (1 - 0.45 * u);
            modele(ps, buffers, "vertebre", l, e, 1);
            ps.popPose();
            prec = p;
        }
    }

    private static void monde(PoseStack ps, Vec3 cam, float partiel) {
        Minecraft mc = Minecraft.getInstance();
        Level level = mc.level;
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        // les balles d'os
        for (Balle b : BALLES) {
            double s = secondes(b.debut()) * 20 + partiel * 0;
            Vec3 p = b.depart().add(b.dir().scale(Math.min(Kaguya.PORTEE_BALLES, s * Kaguya.VITESSE_BALLE)));
            ps.pushPose();
            ps.translate(p.x - cam.x, p.y - cam.y, p.z - cam.z);
            viser(ps, b.dir());
            modele(ps, buffers, "pieu", lumiere(level, p), 0.032F, 1);
            ps.popPose();
        }
        // les piques du sol : deux par tick, elles restent 1,4 s puis rentrent
        for (Ligne l : LIGNES) {
            double t = secondes(l.debut());
            for (int k = 0; k <= l.longueur(); k++) {
                double tk = t - k * 0.025;
                double sort = lisse(tk / 0.12) * (1 - lisse((t - 1.4) / 0.3));
                Vec3 p = l.depart().add(l.dir().scale(k));
                double h = 1.6 + 0.9 * Kaguya.hache(k, 5, 9) + k * 0.05;
                pique(ps, buffers, level, cam, new Vec3(p.x, l.sols()[k], p.z), h, 0.45 + 0.15 * Kaguya.hache(k, 6, 9), sort, 0.25 * (Kaguya.hache(k, 8, 9) - 0.5), k);
            }
        }
        // le fouet : il file, s'enroule, puis revient (avec la cible)
        for (Fouet f : FOUETS) {
            Player j = level.getPlayerByUUID(f.qui());
            if (j == null) {
                continue;
            }
            double t = secondes(f.debut());
            Vec3 a = main(j, partiel);
            Vec3 bout = f.bout();
            Entity c = f.cible() >= 0 ? level.getEntity(f.cible()) : null;
            if (c != null) {
                bout = c.getPosition(partiel).add(0, c.getBbHeight() * 0.55, 0);
            }
            double k = t < 0.15 ? lisse(t / 0.15) : 1 - lisse((t - 0.35) / 0.25);
            if (k > 0.01) {
                chaine(ps, buffers, level, cam, a, a.lerp(bout, k), 0.4 * k);
            }
        }
        // le grand fouet : la colonne se déroule sur 14 blocs et fait un tour complet
        for (GrandFouet g : GRANDS) {
            Player j = level.getPlayerByUUID(g.qui());
            if (j == null) {
                continue;
            }
            double t = secondes(g.debut());
            double sort = lisse(t / 0.12) * (1 - lisse((t - 0.7) / 0.2));
            double angle = Math.atan2(g.dir().z, g.dir().x) + lisse((t - 0.05) / 0.6) * Mth.TWO_PI;
            Vec3 a = main(j, partiel);
            Vec3 b = new Vec3(j.getX() + Math.cos(angle) * g.rayon() * sort, j.getY() + 0.6, j.getZ() + Math.sin(angle) * g.rayon() * sort);
            if (sort > 0.01) {
                chaine(ps, buffers, level, cam, a, b, 0.6);
            }
            if (t < 0.7 && HASARD.nextInt(2) == 0) {
                level.addParticle(ParticleTypes.SWEEP_ATTACK, b.x, b.y, b.z, 0, 0, 0);
                level.addParticle(OS_PART, b.x, b.y, b.z, 0, 0.1, 0);
            }
        }
        // la forêt d'os : les piques géantes sortent du centre vers le bord, restent, puis s'effritent
        for (Foret f : FORETS) {
            double t = secondes(f.debut);
            double fin = f.fin >= 0 ? secondes(f.fin) : f.duree > 0 ? t - f.duree : -1;
            for (int k = 0; k < f.pics.length; k++) {
                float[] q = f.pics[k];
                double r = Math.hypot(q[0], q[1]);
                double sort = lisse((t - r * 0.02) / 0.18) * (1 - lisse(fin / 0.6));
                pique(ps, buffers, level, cam, new Vec3(f.centre.x + q[0], f.sols[k], f.centre.z + q[1]), q[3], q[2], sort,
                        0.3 * (Kaguya.hache(k, 8, f.graine) - 0.5), k + f.graine);
            }
        }
        // l'éveil 2 : elle s'arrache la colonne vertébrale par la nuque (elle sort de son dos et suit sa main)
        for (Map.Entry<UUID, Etat> en : ETATS.entrySet()) {
            Etat e = en.getValue();
            double t2 = e.debut2 < 0 ? 99 : secondes(e.debut2);
            if (e.fin >= 0 || t2 < 0.45 || t2 > 1.25) {
                continue;
            }
            Player j = level.getPlayerByUUID(en.getKey());
            if (j == null) {
                continue;
            }
            Vec3 nuque = corps(j, partiel, 0, 22.5, -2.2);
            double tire = lisse((t2 - 0.45) / 0.7);
            Vec3 main = corps(j, partiel, -2 - 5 * tire, 25 + 8 * tire, -3 - 4 * tire);
            chaine(ps, buffers, level, cam, nuque, main, 0.15);
        }
        // les éclats d'os qui volent (ils retombent, puis s'effritent)
        var blocs = Minecraft.getInstance().getBlockRenderer();
        var os = Blocks.BONE_BLOCK.defaultBlockState();
        for (Eclat x : ECLATS) {
            double s = secondes(x.debut);
            double vy = x.vitesse.y, dy = x.depart.y - x.sol;
            double tVol = (vy + Math.sqrt(Math.max(0, vy * vy + 2 * 22 * dy))) / 22;
            double sv = Math.min(s, tVol);
            Vec3 p = x.depart.add(x.vitesse.x * sv, vy * sv - 11 * sv * sv, x.vitesse.z * sv);
            float taille = x.taille * (float) (1 - lisse((s - x.vie + 0.4) / 0.4));
            if (taille < 0.01F) {
                continue;
            }
            float angle = (float) (x.tourne * sv);
            ps.pushPose();
            ps.translate(p.x - cam.x, p.y - cam.y + taille / 2, p.z - cam.z);
            ps.mulPose(Axis.YP.rotation(angle));
            ps.mulPose(Axis.XP.rotation(angle * 0.8F));
            ps.scale(taille, taille, taille);
            ps.translate(-0.5, -0.5, -0.5);
            blocs.renderSingleBlock(os, ps, buffers, lumiere(level, p.add(0, 0.5, 0)), net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY);
            ps.popPose();
        }
        // les pieux : ils s'arrachent du dos, montent au-dessus d'elle, puis plongent sur leur cible
        for (Pieu p : PIEUX) {
            double t = secondes(p.debut()) * 20;
            Vec3 haut = p.depart().add((p.i() % 2 * 2 - 1) * (0.8 + p.i() / 2 * 0.7), 3.5 + (p.i() / 2) * 0.8, 0);
            Vec3 point = p.point();
            Entity c = p.cible() >= 0 ? level.getEntity(p.cible()) : null;
            if (c != null) {
                point = c.getPosition(partiel).add(0, c.getBbHeight() * 0.5, 0);
            }
            double plonge = Kaguya.PIEU_MONTE + p.i() * Kaguya.PIEU_ECART;
            Vec3 pos, dir;
            if (t < Kaguya.PIEU_MONTE) {
                double k = lisse(t / Kaguya.PIEU_MONTE);
                pos = p.depart().lerp(haut, k);
                dir = new Vec3(0, 1, 0);
            } else if (t < plonge) {
                pos = haut;
                dir = point.subtract(haut).normalize().lerp(new Vec3(0, 1, 0), 1 - lisse((t - Kaguya.PIEU_MONTE) / 3));
            } else {
                double k = Math.min(1, (t - plonge) / Kaguya.PIEU_PLONGE);
                pos = haut.lerp(point, k * k);
                dir = point.subtract(haut);
            }
            ps.pushPose();
            ps.translate(pos.x - cam.x, pos.y - cam.y, pos.z - cam.z);
            viser(ps, dir);
            float alpha = (float) Mth.clamp(1 - (t - plonge - Kaguya.PIEU_PLONGE - 10) / 10, 0, 1);
            modele(ps, buffers, "pieu", lumiere(level, pos), 0.11F, alpha);
            ps.popPose();
        }
        buffers.endBatch();
    }

    // ================================================================ les os qui jaillissent (les transformations)

    /** Un éclat d'os : d'où, sa vitesse, son sol, quand, sa rotation, sa taille, combien de temps il reste. */
    private static final class Eclat {
        Vec3 depart, vitesse;
        double sol;
        long debut;
        float tourne, taille;
        double vie;
    }

    private static final List<Eclat> ECLATS = new ArrayList<>();
    private static final int MAX_ECLATS = 1400;

    /** Un point du corps d'un joueur (en pixels, comme les planches : les pieds en 0, sa gauche vers +x, l'avant vers +z). */
    static Vec3 corps(Player j, float partiel, double x, double y, double z) {
        float a = -Mth.lerp(partiel, j.yBodyRotO, j.yBodyRot) * Mth.DEG_TO_RAD;
        double c = Mth.cos(a), s = Mth.sin(a);
        x /= 16;
        y /= 16;
        z /= 16;
        return j.getPosition(partiel).add(x * c + z * s, y, -x * s + z * c);
    }

    /** Une gerbe d'os qui jaillit de p vers dir : des éclats qui volent et retombent, de la poussière d'os, un éclair blanc. */
    static void jaillir(Level level, Vec3 p, Vec3 dir, int n, double vitesse, float taille, boolean eclair) {
        double sol = sol(level, p);
        Vec3 d = dir.lengthSqr() < 1e-6 ? new Vec3(0, 1, 0) : dir.normalize();
        long t = System.nanoTime();
        for (int k = 0; k < n && ECLATS.size() < MAX_ECLATS; k++) {
            Eclat x = new Eclat();
            x.depart = p;
            Vec3 v = d.scale(vitesse * (0.5 + HASARD.nextDouble())).add(HASARD.nextGaussian() * vitesse * 0.35, HASARD.nextDouble() * vitesse * 0.4,
                    HASARD.nextGaussian() * vitesse * 0.35);
            x.vitesse = v;
            x.sol = Math.min(sol, p.y);
            x.debut = t;
            x.tourne = (float) HASARD.nextGaussian() * 10;
            x.taille = taille * (0.5F + HASARD.nextFloat());
            x.vie = 1.6 + HASARD.nextDouble() * 1.2;
            ECLATS.add(x);
        }
        for (int k = 0; k < n; k++) {
            level.addParticle(OS_PART, p.x, p.y, p.z, d.x * 0.3 + HASARD.nextGaussian() * 0.15, d.y * 0.3 + HASARD.nextGaussian() * 0.15,
                    d.z * 0.3 + HASARD.nextGaussian() * 0.15);
        }
        if (eclair) {
            level.addParticle(ParticleTypes.FLASH, p.x, p.y, p.z, 0, 0, 0);
        }
    }

    private static void son(Level level, Vec3 p, net.minecraft.sounds.SoundEvent son, float volume, float hauteur) {
        level.playLocalSound(p.x, p.y, p.z, son, net.minecraft.sounds.SoundSource.PLAYERS, volume, hauteur, false);
    }

    /** Elle tremble : l'écran de ceux qui sont tout près aussi. */
    private static void secouer(Minecraft mc, Vec3 p, float force, double portee) {
        double loin = mc.player.position().distanceTo(p);
        if (loin < portee) {
            ClientVent.secouer((float) (force * (1 - loin / portee)));
        }
    }

    /** Un moment de la transformation, joué une fois quand le temps t passe « a » (et pas trop tard : un joueur qui arrive ne le voit pas). */
    private static boolean moment(Etat e, int i, double t, double a) {
        if (e.fait[i] || t < a || t > a + 0.5) {
            return false;
        }
        e.fait[i] = true;
        return true;
    }

    /** Les six os du dos : leur base et leur sens (en pixels, comme OS_DOS sur les planches), de haut en bas, gauche puis droite. */
    private static final double[][] OS_DOS = {{2.2, 22.6, -1.6, 0.6, 0.9, -0.5}, {2.8, 19.4, -1.6, 1, 0.2, -0.5}, {2.3, 16.2, -1.6, 0.9, -0.3, -0.4}};

    private static void transformations(Minecraft mc, Player j, Etat e) {
        Level level = mc.level;
        double t1 = e.debut1 < 0 ? 99 : secondes(e.debut1), t2 = e.debut2 < 0 ? 99 : secondes(e.debut2), t3 = e.debut3 < 0 ? 99 : secondes(e.debut3);
        Vec3 dos = corps(j, 1, 0, 19, -3);
        // ---- éveil 1 : des bosses gonflent sous la peau (ça craque), puis les six os percent son dos, un par un
        if (moment(e, 0, t1, 0.25) || moment(e, 1, t1, 0.55)) {
            son(level, dos, net.minecraft.sounds.SoundEvents.BONE_BLOCK_HIT, 1.2F, 0.5F);
            son(level, dos, net.minecraft.sounds.SoundEvents.PLAYER_HURT, 0.6F, 0.7F);
            secouer(mc, dos, 0.4F, 12);
        }
        for (int k = 0; k < 6; k++) {
            if (moment(e, 2 + k, t1, 0.8 + k * 0.07)) {
                double[] o = OS_DOS[k / 2];
                double s = k % 2 == 0 ? 1 : -1;
                Vec3 base = corps(j, 1, o[0] * s, o[1], o[2] - 1.5);
                Vec3 sens = corps(j, 1, o[3] * s * 16, o[4] * 16, o[5] * 16).subtract(j.position());
                jaillir(level, base, sens, 7, 4.5, 0.22F, k % 2 == 0);
                sang(level, base, sens, 3);
                son(level, base, net.minecraft.sounds.SoundEvents.BONE_BLOCK_BREAK, 1.4F, 0.6F + k * 0.08F);
                if (k == 0) {
                    son(level, base, net.minecraft.sounds.SoundEvents.SKELETON_HURT, 1.2F, 0.5F);
                }
                secouer(mc, base, 0.7F, 16);
            }
        }
        if (moment(e, 8, t1, 1.25)) {
            // le souffle : la poussière qui part en anneau autour d'elle
            anneau(level, j.position(), 2.5, 24);
        }
        // ---- éveil 2 : la main dans le dos, ça craque, elle tire sa colonne... et l'épée colonne naît dans sa main
        if (moment(e, 9, t2, 0.45)) {
            Vec3 nuque = corps(j, 1, 0, 22.5, -3);
            jaillir(level, nuque, nuque.subtract(j.position().add(0, 1, 0)), 10, 3.5, 0.2F, false);
            sang(level, nuque, new Vec3(0, 0.5, 0), 5);
            son(level, nuque, net.minecraft.sounds.SoundEvents.BONE_BLOCK_BREAK, 1.6F, 0.5F);
            son(level, nuque, net.minecraft.sounds.SoundEvents.SKELETON_HURT, 1F, 0.6F);
            secouer(mc, nuque, 0.6F, 14);
        }
        if (moment(e, 10, t2, 0.8)) {
            son(level, dos, net.minecraft.sounds.SoundEvents.BONE_BLOCK_HIT, 1.2F, 0.7F);
        }
        if (moment(e, 11, t2, 1.2)) {
            Vec3 main = corps(j, 1, -6, 12, 2);
            jaillir(level, main, new Vec3(0, 1, 0), 18, 5, 0.22F, true);
            son(level, main, net.minecraft.sounds.SoundEvents.BONE_BLOCK_BREAK, 2F, 0.4F);
            son(level, main, net.minecraft.sounds.SoundEvents.TRIDENT_RETURN, 1.4F, 0.6F);
            piquesAutour(j.position(), j.getUUID().hashCode() + 7, 4, 1.3);
            anneau(level, j.position(), 2, 20);
            secouer(mc, main, 1.1F, 18);
        }
        // ---- éveil 3 : la marque maudite part de la nuque, des piques d'os géantes jaillissent du sol autour d'elle, la
        // peau change dans un éclair, la queue sort du bas de son dos, puis tout souffle autour d'elle
        if (moment(e, 12, t3, 0.1)) {
            Vec3 nuque = corps(j, 1, 0, 23, -2.5);
            jaillir(level, nuque, new Vec3(0, 1, 0), 8, 3, 0.18F, false);
            sang(level, nuque, new Vec3(0, 0.5, 0), 3);
            son(level, nuque, net.minecraft.sounds.SoundEvents.WITHER_SKELETON_AMBIENT, 1.4F, 0.5F);
            secouer(mc, nuque, 0.6F, 14);
        }
        if (moment(e, 13, t3, 0.4)) {
            piquesAutour(j.position(), j.getUUID().hashCode() + 31, 7.5, 2.4);
            anneau(level, j.position(), 3, 30);
            son(level, j.position(), net.minecraft.sounds.SoundEvents.BONE_BLOCK_BREAK, 2F, 0.4F);
            son(level, j.position(), net.minecraft.sounds.SoundEvents.GENERIC_EXPLODE.value(), 0.8F, 1.3F);
            secouer(mc, j.position(), 1.4F, 24);
        }
        if (moment(e, 14, t3, 0.6)) {
            Vec3 c = j.position().add(0, 1.1, 0);
            level.addParticle(ParticleTypes.FLASH, c.x, c.y, c.z, 0, 0, 0);
            son(level, c, net.minecraft.sounds.SoundEvents.WARDEN_HEARTBEAT, 2F, 0.6F);
        }
        if (moment(e, 15, t3, 1.0)) {
            Vec3 bas = corps(j, 1, 0, 12.5, -4);
            jaillir(level, bas, corps(j, 1, 0, 6, -16).subtract(j.position()), 14, 4.5, 0.22F, true);
            sang(level, bas, corps(j, 1, 0, 6, -16).subtract(j.position()), 4);
            son(level, bas, net.minecraft.sounds.SoundEvents.BONE_BLOCK_BREAK, 1.8F, 0.5F);
            son(level, bas, net.minecraft.sounds.SoundEvents.SKELETON_HURT, 1.2F, 0.4F);
            secouer(mc, bas, 0.9F, 16);
        }
        if (moment(e, 16, t3, 2.2)) {
            anneau(level, j.position(), 3.5, 36);
            jaillir(level, j.position().add(0, 1, 0), new Vec3(0, 1, 0), 16, 6, 0.2F, true);
            son(level, j.position(), net.minecraft.sounds.SoundEvents.WITHER_SKELETON_DEATH, 1.2F, 0.6F);
            secouer(mc, j.position(), 1.2F, 20);
        }
    }

    private static final net.minecraft.core.particles.DustParticleOptions SANG = new net.minecraft.core.particles.DustParticleOptions(new Vector3f(0.55F, 0.02F, 0.03F), 0.8F);

    /** Quelques petites gouttes de sang, là où un os perce la peau (un peu, pas beaucoup). */
    private static void sang(Level level, Vec3 p, Vec3 dir, int n) {
        Vec3 d = dir.lengthSqr() < 1e-6 ? Vec3.ZERO : dir.normalize();
        for (int k = 0; k < n; k++) {
            level.addParticle(SANG, p.x + HASARD.nextGaussian() * 0.08, p.y + HASARD.nextGaussian() * 0.08, p.z + HASARD.nextGaussian() * 0.08,
                    d.x * 0.15 + HASARD.nextGaussian() * 0.04, d.y * 0.15 - 0.05, d.z * 0.15 + HASARD.nextGaussian() * 0.04);
        }
    }

    /** Un anneau de poussière et de nuages qui part du sol autour de p. */
    private static void anneau(Level level, Vec3 p, double vitesse, int n) {
        double y = sol(level, p) + 0.1;
        for (int k = 0; k < n; k++) {
            double a = k * Mth.TWO_PI / n;
            level.addParticle(ParticleTypes.CLOUD, p.x, y, p.z, Math.cos(a) * vitesse * 0.12, 0.02, Math.sin(a) * vitesse * 0.12);
            level.addParticle(ParticleTypes.POOF, p.x + Math.cos(a), y, p.z + Math.sin(a), Math.cos(a) * vitesse * 0.08, 0.05, Math.sin(a) * vitesse * 0.08);
        }
    }

    // ================================================================ le panneau, les touches (pour moi)

    private static long pretDroit, pretAccroupi, pretTech, pretLances, pretFonce, pretUlti;
    private static boolean ultiFaite;
    private static boolean accroupiAvant, techniquePendant;
    private static long accroupiDebut;

    private static long dans(int ticks) {
        return System.nanoTime() + ticks * 50_000_000L;
    }

    private static void envoyer(int action) {
        PacketDistributor.sendToServer(new Kaguya.Touche(action));
    }

    private static String reste(long pret) {
        double s = (pret - System.nanoTime()) / 1e9;
        return s > 0 ? "  " + (int) Math.ceil(s) + " s" : "";
    }

    /** La touche technique (², lue dans ClientSharingan) : les lances (niveau 2), les pieux ou, accroupie, les grosses lances (niveau 3). */
    public static boolean technique() {
        Minecraft mc = Minecraft.getInstance();
        Etat e = mc.player == null ? null : ETATS.get(mc.player.getUUID());
        if (e == null || e.fin >= 0 || e.niveau == 0) {
            return false;
        }
        if (e.niveau < 2 || ClientKaguyaDino.actif(mc.player.getUUID())) {
            return true;
        }
        boolean accroupie = mc.options.keyShift.isDown();
        if (accroupie) {
            techniquePendant = true;
        }
        boolean lances = e.niveau == 2 || accroupie;
        if (lances && System.nanoTime() >= pretLances && !lancesActives(e)) {
            pretLances = dans(Kaguya.RECHARGE_LANCES);
            envoyer(e.niveau >= 3 ? Kaguya.LANCES3 : Kaguya.TECHNIQUE);
        } else if (!lances && System.nanoTime() >= pretTech) {
            pretTech = dans(Kaguya.RECHARGE_PIEUX);
            envoyer(Kaguya.TECHNIQUE);
        }
        return true;
    }

    /** La touche d'ulti (J, lue dans ClientHachibi4) : le dinosaure d'os, au niveau 3. */
    public static void toucheUlti() {
        Minecraft mc = Minecraft.getInstance();
        Etat e = mc.player == null ? null : ETATS.get(mc.player.getUUID());
        if (e == null || e.fin >= 0 || e.niveau < 3 || ClientKaguyaDino.actif(mc.player.getUUID())) {
            return;
        }
        if (!ultiFaite && System.nanoTime() >= pretUlti && mc.player.onGround()) {
            ultiFaite = true;
            pretUlti = dans(KaguyaDino.RECHARGE);
        }
        envoyer(Kaguya.ULTI);
    }

    private static boolean lancesActives(Etat e) {
        return e.lances >= 0 && e.lancesFin < 0;
    }

    private static void ecran(GuiGraphics g) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.options.hideGui) {
            return;
        }
        Etat e = ETATS.get(mc.player.getUUID());
        if (e == null || e.fin >= 0 || e.niveau == 0) {
            return;
        }
        panneau(g, mc.font, e);
    }

    private static void panneau(GuiGraphics g, Font font, Etat e) {
        int n = e.niveau;
        boolean deux = Kaguya.NOM2.equals(e.pouvoir), trois = Kaguya.NOM3.equals(e.pouvoir);
        String eveil = n == 1 && (deux || trois) ? "Niveau 2" : n == 2 && trois ? "Niveau 3" : "Rendormir";
        String tech = Clavier.nom(ClientSharingan.SUSANO);
        long now = System.nanoTime();
        List<String[]> lignes = new ArrayList<>();
        List<Boolean> gris = new ArrayList<>();
        boolean lances = lancesActives(e);
        if (lances) {
            double reste = Kaguya.DUREE_LANCES / 20.0 - secondes(e.lances);
            lignes.add(new String[]{Clavier.gauche(), "Coup de lance (traverse l'armure)"});
            lignes.add(new String[]{Clavier.droite(), "Foncer" + reste(pretFonce)});
            lignes.add(new String[]{"", "Lances : encore " + (int) Math.ceil(Math.max(0, reste)) + " s"});
            gris.add(false);
            gris.add(pretFonce > now);
            gris.add(false);
        } else if (n == 1) {
            lignes.add(new String[]{Clavier.gauche(), "Poings d'os"});
            lignes.add(new String[]{Clavier.droite(), "Phalanges" + reste(pretDroit)});
            lignes.add(new String[]{Clavier.accroupi(), "Piques du sol" + reste(pretAccroupi)});
            gris.add(false);
            gris.add(pretDroit > now);
            gris.add(pretAccroupi > now);
        } else if (n == 2) {
            lignes.add(new String[]{Clavier.gauche(), "Épée colonne"});
            lignes.add(new String[]{Clavier.droite(), "Fouet" + reste(pretDroit)});
            lignes.add(new String[]{Clavier.accroupi(), "Danse du saule" + reste(pretAccroupi)});
            lignes.add(new String[]{tech, "Lances (30 s)" + reste(pretLances)});
            gris.add(false);
            gris.add(pretDroit > now);
            gris.add(pretAccroupi > now);
            gris.add(pretLances > now);
        } else {
            lignes.add(new String[]{Clavier.gauche(), "Épée colonne"});
            lignes.add(new String[]{Clavier.droite(), "Grand fouet" + reste(pretDroit)});
            lignes.add(new String[]{Clavier.accroupi(), "Forêt d'os" + reste(pretAccroupi)});
            lignes.add(new String[]{tech, "Pluie de pieux" + reste(pretTech)});
            lignes.add(new String[]{Clavier.accroupi() + " + " + tech, "Grosses lances" + reste(pretLances)});
            lignes.add(new String[]{Clavier.nom(fr.cabane.client.hachibi4.ClientHachibi4.ULTI), ultiFaite && pretUlti <= now ? "Dinosaure d'os (déjà sorti)"
                    : "Dinosaure d'os" + reste(pretUlti)});
            gris.add(false);
            gris.add(pretDroit > now);
            gris.add(pretAccroupi > now);
            gris.add(pretTech > now);
            gris.add(pretLances > now);
            gris.add(ultiFaite || pretUlti > now);
        }
        lignes.add(new String[]{Clavier.nom(ClientSusano.EVEIL), eveil});
        gris.add(false);
        boolean[] g2 = new boolean[gris.size()];
        for (int i = 0; i < g2.length; i++) {
            g2[i] = gris.get(i);
        }
        PanneauPouvoir.dessiner(g, font, "Kaguya" + (n >= 2 ? "  ·  niv. " + n : ""), 0xFFEDE6D2, lignes.toArray(new String[0][]), g2);
    }

    // ================================================================ l'épée colonne (un objet)

    /** La lame de l'épée souple : tant de vertèbres, chacune de tant de pixels (comme jeu.mjs). */
    private static final int EPEE_N = 18;
    private static final float EPEE_SEG = 2.6F;
    /** Le joueur qu'on est en train de dessiner (son épée suit ses coups et sa marche). */
    private static Player porteur;

    static final class RenduEpee extends BlockEntityWithoutLevelRenderer {
        RenduEpee() {
            super(Minecraft.getInstance().getBlockEntityRenderDispatcher(), Minecraft.getInstance().getEntityModels());
        }

        /**
         * Le manche (rigide), puis la lame vertèbre par vertèbre : chacune tourne un peu sur la précédente, si bien que la
         * lame ondule doucement (plus quand elle marche) ; quand elle frappe, la lame traîne derrière, puis fouette.
         */
        @Override
        public void renderByItem(ItemStack stack, ItemDisplayContext ctx, PoseStack ps, MultiBufferSource buffers, int lumiere, int recouvre) {
            ModeleVent[] manche = ModeleVent.lire("kaguya", "epee_manche");
            if (manche.length == 0) {
                return;
            }
            Minecraft mc = Minecraft.getInstance();
            boolean gui = ctx == ItemDisplayContext.GUI;
            ps.pushPose();
            float e;
            if (gui) {
                ps.translate(0.5F, 0.5F, 0.5F);
                ps.mulPose(Axis.ZP.rotationDegrees(-45));
                e = 0.0145F;
                ps.translate(0, -26 * e, 0);
            } else {
                ps.translate(0.3F, 0.3F, 0.5F);
                ps.mulPose(Axis.ZP.rotationDegrees(-45));
                e = 0.032F;
            }
            ps.scale(e, e, e);
            Player j = gui ? null : ctx.firstPerson() ? mc.player : porteur;
            float partiel = mc.getTimer().getGameTimeDeltaPartialTick(false);
            float coup = j == null ? 0 : j.getAttackAnim(partiel);
            float marche = j == null ? 0 : Math.min(1, j.walkAnimation.speed(partiel) * 1.5F);
            float t = (float) ((System.nanoTime() / 1e9) % 10_000);
            manche[0].dessiner(buffers, ps.last(), lumiere, 1, 1);
            ps.translate(0, 2.9F, 0);
            for (int i = 0; i < EPEE_N; i++) {
                ModeleVent[] seg = ModeleVent.lire("kaguya", "epee_seg_" + i);
                if (seg.length == 0) {
                    break;
                }
                if (!gui) {
                    float u = i / (EPEE_N - 1F);
                    // elle ondule (comme une colonne vivante), plus fort vers la pointe et quand elle marche
                    float ax = (0.03F + 0.035F * marche) * Mth.sin(t * 2.4F - i * 0.42F) * u;
                    float az = (0.035F + 0.02F * marche) * Mth.sin(t * 1.8F - i * 0.5F + 1.3F) * u;
                    // le coup : la lame traîne derrière, puis fouette devant
                    ax += 0.065F * Mth.sin(coup * Mth.TWO_PI) * (1 - coup * 0.5F) * (0.35F + u);
                    ps.mulPose(Axis.XP.rotation(ax));
                    ps.mulPose(Axis.ZP.rotation(az));
                }
                seg[0].dessiner(buffers, ps.last(), lumiere, 1, 1);
                ps.translate(0, EPEE_SEG, 0);
            }
            ModeleVent[] pointe = ModeleVent.lire("kaguya", "epee_pointe");
            if (pointe.length > 0) {
                pointe[0].dessiner(buffers, ps.last(), lumiere, 1, 1);
            }
            ps.popPose();
        }
    }

    // ================================================================ les événements

    @EventBusSubscriber(modid = Cabane.MODID, value = Dist.CLIENT)
    public static final class Couches {
        @SubscribeEvent
        public static void enregistrer(RegisterGuiLayersEvent event) {
            event.registerAboveAll(Cabane.id("kaguya"), (g, delta) -> ecran(g));
        }

        @SubscribeEvent
        public static void couches(EntityRenderersEvent.AddLayers event) {
            for (PlayerSkin.Model modele : event.getSkins()) {
                if (event.getSkin(modele) instanceof PlayerRenderer rendu) {
                    rendu.addLayer(new Couche(rendu));
                }
            }
        }

        @SubscribeEvent
        public static void extensions(RegisterClientExtensionsEvent event) {
            event.registerItem(new IClientItemExtensions() {
                private RenduEpee rendu;

                @Override
                public BlockEntityWithoutLevelRenderer getCustomRenderer() {
                    if (rendu == null) {
                        rendu = new RenduEpee();
                    }
                    return rendu;
                }
            }, Kaguya.epee());
        }

        private Couches() {
        }
    }

    private static final Set<Player> TOURNES = new HashSet<>();

    @EventBusSubscriber(modid = Cabane.MODID, value = Dist.CLIENT)
    public static final class Evenements {
        /** Avant les touches du jeu : l'épée colonne ne se lâche pas. */
        @SubscribeEvent
        public static void avantTick(ClientTickEvent.Pre event) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null && mc.player.getMainHandItem().is(Kaguya.epee())) {
                while (mc.options.keyDrop.consumeClick()) {
                    // rien : elle est bloquée dans sa main
                }
            }
        }

        @SubscribeEvent
        public static void tick(ClientTickEvent.Post event) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.level == null || mc.player == null) {
                ETATS.clear();
                BALLES.clear();
                LIGNES.clear();
                FOUETS.clear();
                GRANDS.clear();
                FORETS.clear();
                PIEUX.clear();
                return;
            }
            Etat moi = ETATS.get(mc.player.getUUID());
            boolean eveillee = moi != null && moi.fin < 0 && moi.niveau > 0;
            // l'épée colonne est bloquée dans le slot 1 : la barre d'objets reste dessus
            if (eveillee && moi.niveau >= 2 && !lancesActives(moi) && mc.player.getInventory().getItem(0).is(Kaguya.epee())) {
                mc.player.getInventory().selected = 0;
            }
            // s'accroupir (un appui court, sans la touche technique entre-temps) : les piques, le saule, la forêt
            boolean accroupi = mc.options.keyShift.isDown();
            if (eveillee && accroupi && !accroupiAvant) {
                accroupiDebut = System.nanoTime();
                techniquePendant = false;
            }
            if (eveillee && !accroupi && accroupiAvant && !techniquePendant && mc.screen == null && secondes(accroupiDebut) < 0.6
                    && !lancesActives(moi) && !ClientKaguyaDino.actif(mc.player.getUUID()) && System.nanoTime() >= pretAccroupi) {
                pretAccroupi = dans(moi.niveau >= 3 ? Kaguya.RECHARGE_FORET : moi.niveau == 2 ? Kaguya.RECHARGE_SAULE : Kaguya.RECHARGE_PIQUES);
                envoyer(Kaguya.ACCROUPI);
            }
            accroupiAvant = accroupi;
            // ce qui est fini s'en va
            ETATS.values().removeIf(e -> e.fin >= 0 && secondes(e.fin) > 1);
            BALLES.removeIf(b -> secondes(b.debut()) * 20 * Kaguya.VITESSE_BALLE > Kaguya.PORTEE_BALLES + 3);
            LIGNES.removeIf(l -> secondes(l.debut()) > 1.8);
            FOUETS.removeIf(f -> secondes(f.debut()) > 0.65);
            GRANDS.removeIf(g -> secondes(g.debut()) > 0.95);
            FORETS.removeIf(f -> f.fin >= 0 && secondes(f.fin) > 0.7 || f.duree > 0 && secondes(f.debut) > f.duree + 0.7 || secondes(f.debut) > 30);
            PIEUX.removeIf(p -> secondes(p.debut()) > 3.5);
            for (Etat e : ETATS.values()) {
                if (e.lancesFin >= 0 && secondes(e.lancesFin) > 0.5) {
                    e.lances = -1;
                    e.lancesFin = -1;
                }
            }
            // les transformations : les os qui jaillissent de son corps (une fois chacun)
            for (Map.Entry<UUID, Etat> en : ETATS.entrySet()) {
                Player j = mc.level.getPlayerByUUID(en.getKey());
                Etat e = en.getValue();
                if (j == null || e.fin >= 0 || j.distanceTo(mc.player) > 64) {
                    continue;
                }
                transformations(mc, j, e);
            }
            ECLATS.removeIf(x -> secondes(x.debut) > x.vie);
        }

        /** Éveillée, le clic droit est à ses os (les phalanges, le fouet, le grand fouet, foncer avec les lances). */
        @SubscribeEvent
        public static void clic(InputEvent.InteractionKeyMappingTriggered event) {
            Minecraft mc = Minecraft.getInstance();
            Etat moi = mc.player == null ? null : ETATS.get(mc.player.getUUID());
            if (moi == null || moi.fin >= 0 || moi.niveau == 0 || !event.isUseItem()) {
                return;
            }
            event.setCanceled(true);
            event.setSwingHand(false);
            if (ClientKaguyaDino.actif(mc.player.getUUID())) {
                return;
            }
            if (lancesActives(moi)) {
                if (System.nanoTime() >= pretFonce) {
                    pretFonce = dans(Kaguya.RECHARGE_FONCE);
                    envoyer(Kaguya.DROIT);
                }
                return;
            }
            if (System.nanoTime() >= pretDroit) {
                pretDroit = dans(moi.niveau >= 3 ? Kaguya.RECHARGE_GRAND_FOUET : moi.niveau == 2 ? Kaguya.RECHARGE_FOUET : Kaguya.RECHARGE_PHALANGES);
                mc.player.swing(InteractionHand.MAIN_HAND);
                envoyer(Kaguya.DROIT);
            }
        }

        /** Pendant l'ulti, elle ne marche pas (elle a la main au sol, puis elle est sur le crâne). */
        @SubscribeEvent
        public static void immobile(MovementInputUpdateEvent event) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null || !ClientKaguyaDino.immobile(mc.player.getUUID())) {
                return;
            }
            event.getInput().leftImpulse = 0;
            event.getInput().forwardImpulse = 0;
            event.getInput().jumping = false;
            event.getInput().shiftKeyDown = false;
            event.getInput().up = false;
            event.getInput().down = false;
            event.getInput().left = false;
            event.getInput().right = false;
        }

        @SubscribeEvent
        public static void monde(RenderLevelStageEvent event) {
            if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES || Minecraft.getInstance().level == null) {
                return;
            }
            float partiel = event.getPartialTick().getGameTimeDeltaPartialTick(false);
            Vec3 cam = event.getCamera().getPosition();
            if (!(BALLES.isEmpty() && LIGNES.isEmpty() && FOUETS.isEmpty() && GRANDS.isEmpty() && FORETS.isEmpty() && PIEUX.isEmpty() && ECLATS.isEmpty()
                    && ETATS.isEmpty())) {
                ClientKaguya.monde(event.getPoseStack(), cam, partiel);
            }
            ClientKaguyaDino.dessiner(event.getPoseStack(), cam, partiel);
        }

        /** La danse du saule : elle tourne sur elle-même (deux tours). */
        @SubscribeEvent(priority = EventPriority.LOWEST)
        public static void avant(RenderPlayerEvent.Pre event) {
            Player p = event.getEntity();
            porteur = p;
            Etat e = ETATS.get(p.getUUID());
            if (e == null || e.saule < 0 || secondes(e.saule) > 0.7) {
                return;
            }
            float a = (float) (lisse(secondes(e.saule) / 0.7) * Mth.TWO_PI * 2);
            event.getPoseStack().pushPose();
            event.getPoseStack().mulPose(Axis.YP.rotation(-a));
            TOURNES.add(p);
        }

        @SubscribeEvent
        public static void apres(RenderPlayerEvent.Post event) {
            porteur = null;
            if (TOURNES.remove(event.getEntity())) {
                event.getPoseStack().popPose();
            }
        }

        private Evenements() {
        }
    }

    private ClientKaguya() {
    }
}
