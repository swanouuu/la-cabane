package fr.cabane.client.pouvoir;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.math.Axis;
import fr.cabane.Cabane;
import fr.cabane.pouvoir.Pouvoirs;
import fr.cabane.pouvoir.Vent;
import fr.cabane.pouvoir.Vent3;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import net.minecraft.client.resources.PlayerSkin;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.HitResult;
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
import net.neoforged.neoforge.client.event.RenderHandEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.event.RenderPlayerEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;
import net.neoforged.neoforge.client.extensions.common.IClientItemExtensions;
import net.neoforged.neoforge.client.extensions.common.RegisterClientExtensionsEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Ce qu'on voit du pouvoir Vent (voir {@link Vent}, {@link Vent3} et docs/vent). Le vent se dessine comme sur les planches :
 * des rubans transparents vert pâle avec un cœur blanc qui brille ; les tranches sont des croissants, les tornades des
 * rubans en hélice avec des feuilles qui tournent, la répulsion une sphère claire sur les bords. La fée du vent tourne
 * autour de lui au niveau 1 (la même fée que sur les planches, ailes battantes, des paillettes derrière elle) ; ce qu'il
 * porte est dans {@link ClientTenueVent}. Les transformations : la fée naît d'un tourbillon dans sa main (éveil 1) ; elle
 * plonge dans son cœur, la boule de vent éclate (éveil 2) ; une tornade verte le soulève et explose en anneau (éveil 3) ;
 * à la fin, une rafale, tout s'envole en feuilles.
 */
public final class ClientVent {
    private static final RandomSource HASARD = RandomSource.create();
    static final float[] VERT = {110 / 255F, 240 / 255F, 150 / 255F};
    static final float[] VERT_C = {200 / 255F, 1F, 215 / 255F};
    static final float[] BLANC = {250 / 255F, 1F, 250 / 255F};
    private static final float[] LUEUR = {80 / 255F, 1F, 150 / 255F};
    private static final float[] PAILLETTE = {1F, 230 / 255F, 120 / 255F};
    private static final float[] DORE_DOUX = {230 / 255F, 1F, 200 / 255F};
    private static final float[] FEUILLE = {100 / 255F, 190 / 255F, 80 / 255F};
    private static final float[] FEUILLE_B = {150 / 255F, 110 / 255F, 70 / 255F};
    /** Un pixel du joueur, en blocs : les planches sont dessinées en pixels. */
    private static final double PX = 1 / 16.0;
    private static final Vec3 HAUT = new Vec3(0, 1, 0);
    /**
     * L'éveil 3 (en secondes) : ses pièces du niveau 3 poussent sous la lumière à ILLUMINE, la tornade explose en anneau
     * à ECLAT3 (la lumière commence à retomber), tout est fini à FIN3.
     */
    static final double ILLUMINE = 1.6, ECLAT3 = 2.6, FIN3 = 3.5;

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

    // ================================================================ qui est éveillé, et où en est sa transformation

    /** Un éveillé du Vent : sa liste, son niveau, quand chaque niveau a commencé, sa fin, et ses gestes en cours. */
    static final class Etat {
        String pouvoir;
        int niveau;
        long debut1 = -1, debut2 = -1, debut3 = -1;
        /** La fin de l'éveil (la rafale) et le niveau qu'il avait. */
        long fin = -1;
        int niveauFin;
        /** Où étaient ses pieds quand la tornade du niveau 3 l'a soulevé. */
        Vec3 sol3;
        /** La répulsion : le vent qui se ramasse (bras en T), puis l'éclat ; le piqué ; la tempête ; la tornade royale. */
        long ramasse = -1, eclat = -1, pique = -1, tempete = -1, tempeteFin = -1;
        Vec3 centreRep;
        int rayonRep;
        long ulti = -1, leve = -1, explose = -1, ultiFin = -1;
        Vec3 centreUlti;
        /** Son dernier saut en l'air (le grand coup d'aile). */
        long saut = -1;
        /** Les gerbes de particules de ses transformations, déjà lancées (une fois chacune). */
        final boolean[] gerbes = new boolean[8];
    }

    private static final Map<UUID, Etat> ETATS = new HashMap<>();
    /** Ceux qui ont le pouvoir Vent, éveillés ou non : leur fée tourne toujours autour d'eux. */
    private static final java.util.Set<UUID> PORTEURS = new java.util.HashSet<>();

    public static void porteurs(Vent.Porteurs p) {
        PORTEURS.clear();
        PORTEURS.addAll(p.joueurs());
    }

    /** De 0 à 1 : il vole couché en avant (la cape pend alors vers ses pieds). */
    static float vol(UUID joueur) {
        float[] v = INCLINAISONS.get(joueur);
        return v == null ? 0 : v[0] / VOL_MAX;
    }

    /** Son état (éveillé, ou qui vient de s'endormir), ou null. */
    static Etat etat(UUID joueur) {
        return ETATS.get(joueur);
    }

    public static void recevoir(Pouvoirs.Etat p) {
        Minecraft mc = Minecraft.getInstance();
        boolean moi = mc.player != null && p.joueur().equals(mc.player.getUUID());
        Etat e = ETATS.get(p.joueur());
        long maintenant = System.nanoTime(), avant = maintenant - 100_000_000_000L;
        if (p.eveille()) {
            if (e == null || e.fin >= 0) {
                e = new Etat();
                ETATS.put(p.joueur(), e);
            }
            e.pouvoir = p.pouvoir();
            for (int n = e.niveau + 1; n <= p.niveau(); n++) {
                long quand = p.annonce() ? maintenant : avant;
                if (n == 1) {
                    e.debut1 = quand;
                } else if (n == 2) {
                    e.debut2 = quand;
                } else {
                    e.debut3 = quand;
                    Player j = mc.level == null ? null : mc.level.getPlayerByUUID(p.joueur());
                    e.sol3 = j == null ? null : j.position();
                }
            }
            if (p.annonce() && p.niveau() > e.niveau && mc.level != null) {
                Player j = mc.level.getPlayerByUUID(p.joueur());
                if (j != null) {
                    float hauteur = p.niveau() >= 3 ? 0.6F : p.niveau() >= 2 ? 0.9F : 1.3F;
                    mc.level.playLocalSound(j.getX(), j.getY() + 1, j.getZ(), SoundEvents.BREEZE_IDLE_GROUND, SoundSource.PLAYERS, 1.2F, hauteur, false);
                }
            }
            e.niveau = p.niveau();
            if (moi) {
                sautsReste = Vent.SAUTS_EN_L_AIR;
            }
        } else {
            if (e != null && e.fin < 0) {
                e.fin = p.annonce() ? maintenant : avant;
                e.niveauFin = e.niveau;
                e.niveau = 0;
                rafale(mc, p.joueur());
            }
            if (moi) {
                pretDroit = pretAccroupi = pretTenu = 0;
                tenu = false;
            }
        }
    }

    public static void oublier(UUID joueur) {
        ETATS.remove(joueur);
        LANCES.remove(joueur);
    }

    /** La fin de l'éveil : une rafale, des feuilles qui s'envolent. */
    private static void rafale(Minecraft mc, UUID qui) {
        Player j = mc.level == null ? null : mc.level.getPlayerByUUID(qui);
        if (j == null) {
            return;
        }
        for (int i = 0; i < 30; i++) {
            mc.level.addParticle(ParticleTypes.COMPOSTER, j.getX() + HASARD.nextGaussian() * 0.4, j.getY() + HASARD.nextDouble() * 2, j.getZ() + HASARD.nextGaussian() * 0.4,
                    HASARD.nextGaussian() * 0.3, 0.2 + HASARD.nextDouble() * 0.3, HASARD.nextGaussian() * 0.3);
        }
        mc.level.addParticle(ParticleTypes.GUST, j.getX(), j.getY() + 1, j.getZ(), 0, 0, 0);
    }

    // ================================================================ les coups de vent qu'on voit

    private record Tranches(Vec3 depart, Vec3 regard, int n, boolean fort, long debut) {
    }

    /** Le faucon de vent : il suit le regard de son lanceur (le serveur dit où il est à chaque tick). */
    private static final class Faucon {
        Vec3 pos;
        Vec3 dir;
        long recu;
        long debut;
        long fin = -1;
        final ArrayDeque<Vec3> traine = new ArrayDeque<>();
    }

    private record Lame(Vec3 pos, Vec3 dir, long debut) {
    }

    private record Anneau(Vec3 pos, long debut) {
    }

    /** Un tourbillon (niveau 1) : où, vers où, s'il tient quelqu'un, quand il a fini. */
    private static final class Tourbillon {
        Vec3 pos;
        Vec3 vers;
        long debut;
        long fin = -1;
    }

    private static final List<Tranches> TRANCHES = new ArrayList<>();
    private static final Map<UUID, Faucon> FAUCONS = new HashMap<>();
    private static final List<Lame> LAMES = new ArrayList<>();
    private static final List<Anneau> ANNEAUX = new ArrayList<>();
    private static final Map<UUID, Tourbillon> TOURBILLONS = new HashMap<>();

    /** Les quatre lances en vol : leur position et leur direction, et la traînée de chacune. */
    private static final class Volee {
        final List<Vec3[]> lances = new ArrayList<>();
        final List<ArrayDeque<Vec3>> traines = new ArrayList<>();
    }

    private static final Map<UUID, Volee> LANCES = new HashMap<>();

    /** Ses lances sont-elles parties (elles ne flottent plus dans son dos) ? */
    static boolean lancesParties(UUID joueur) {
        return LANCES.containsKey(joueur);
    }

    public static void lances(Vent.Lances p) {
        if (p.points().isEmpty()) {
            LANCES.remove(p.joueur());
            return;
        }
        Volee v = LANCES.computeIfAbsent(p.joueur(), k -> new Volee());
        v.lances.clear();
        for (int i = 0; i + 1 < p.points().size(); i += 2) {
            v.lances.add(new Vec3[]{vec(p.points().get(i)), vec(p.points().get(i + 1))});
        }
        while (v.traines.size() < v.lances.size()) {
            v.traines.add(new ArrayDeque<>());
        }
        for (int i = 0; i < v.lances.size(); i++) {
            ArrayDeque<Vec3> t = v.traines.get(i);
            t.addLast(v.lances.get(i)[0]);
            while (t.size() > 7) {
                t.removeFirst();
            }
        }
    }

    public static void effet(Vent.Effet p) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return;
        }
        long maintenant = System.nanoTime();
        Vec3 pos = vec(p.pos()), dir = vec(p.dir());
        Etat e = ETATS.get(p.joueur());
        boolean moi = mc.player != null && p.joueur().equals(mc.player.getUUID());
        switch (p.sorte()) {
            case Vent.E_TRANCHES -> TRANCHES.add(new Tranches(pos, dir, p.n(), p.phase() >= 2, maintenant));
            case Vent.E_FAUCON -> {
                Faucon fa = p.phase() == 0 ? null : FAUCONS.get(p.joueur());
                if (fa == null || fa.fin >= 0) {
                    fa = new Faucon();
                    fa.debut = maintenant;
                    FAUCONS.put(p.joueur(), fa);
                }
                fa.pos = pos;
                fa.dir = dir;
                fa.recu = maintenant;
                fa.traine.addLast(pos);
                while (fa.traine.size() > 12) {
                    fa.traine.removeFirst();
                }
                if (p.phase() == 2) {
                    fa.fin = maintenant;
                }
            }
            case Vent.E_LAME -> LAMES.add(new Lame(pos, dir, maintenant));
            case Vent.E_SAUT -> {
                if (e != null) {
                    e.saut = maintenant;
                }
                ANNEAUX.add(new Anneau(pos, maintenant));
                for (int i = 0; i < 8; i++) {
                    mc.level.addParticle(paillette(i), pos.x + HASARD.nextGaussian() * 0.3, pos.y + 0.3, pos.z + HASARD.nextGaussian() * 0.3, 0, -0.05, 0);
                }
            }
            case Vent.E_TOURBILLON -> {
                Tourbillon t = TOURBILLONS.computeIfAbsent(p.joueur(), k -> {
                    Tourbillon n = new Tourbillon();
                    n.debut = maintenant;
                    return n;
                });
                if (t.fin >= 0) {
                    t.debut = maintenant;
                    t.fin = -1;
                }
                t.pos = pos;
                t.vers = dir;
                if (p.phase() == 3) {
                    t.fin = maintenant;
                }
            }
            case Vent.E_REPULSION -> {
                if (e != null) {
                    if (p.phase() == 0) {
                        e.ramasse = maintenant;
                        e.eclat = -1;
                    } else {
                        e.eclat = maintenant;
                        e.pique = -1;
                        if (e.ramasse < 0 || secondes(e.ramasse) > 2) {
                            e.ramasse = maintenant - 500_000_000L;
                        }
                        if (mc.player != null && mc.player.position().distanceTo(pos) < p.n() + 8) {
                            secouer(1.2F);
                        }
                        mc.level.addParticle(ParticleTypes.GUST_EMITTER_LARGE, pos.x, pos.y, pos.z, 0, 0, 0);
                        mc.level.addParticle(ParticleTypes.EXPLOSION, pos.x, pos.y, pos.z, 0, 0, 0);
                    }
                    e.centreRep = pos;
                    e.rayonRep = p.n();
                }
            }
            case Vent.E_PIQUE -> {
                if (e != null) {
                    e.pique = maintenant;
                }
            }
            case Vent.E_TEMPETE -> {
                if (e != null) {
                    if (p.phase() == 0) {
                        e.tempete = maintenant;
                        e.tempeteFin = -1;
                    } else if (e.tempete >= 0) {
                        e.tempeteFin = maintenant;
                        mc.getSoundManager().stop(SoundEvents.BREEZE_WHIRL.getLocation(), null);
                    }
                }
            }
            case Vent.E_ULTI -> {
                if (e == null) {
                    return;
                }
                switch (p.phase()) {
                    case 0 -> {
                        e.ulti = maintenant;
                        e.leve = e.explose = e.ultiFin = -1;
                        e.centreUlti = pos;
                    }
                    case 1 -> e.leve = maintenant;
                    case 2 -> {
                        e.explose = maintenant;
                        if (mc.player != null && mc.player.position().distanceTo(pos) < 60) {
                            secouer(1.4F);
                        }
                        mc.level.addParticle(ParticleTypes.EXPLOSION_EMITTER, pos.x, pos.y + 2, pos.z, 0, 0, 0);
                        mc.level.addParticle(ParticleTypes.GUST_EMITTER_LARGE, pos.x, pos.y + 6, pos.z, 0, 0, 0);
                        // le vent de la tornade se tait d'un coup (il ne traîne pas après l'attaque)
                        mc.getSoundManager().stop(SoundEvents.BREEZE_WHIRL.getLocation(), null);
                    }
                    default -> {
                        e.ultiFin = maintenant;
                        mc.getSoundManager().stop(SoundEvents.BREEZE_WHIRL.getLocation(), null);
                    }
                }
            }
            case Vent.E_SOUFFLE -> {
                mc.level.addParticle(ParticleTypes.SMALL_GUST, pos.x, pos.y, pos.z, 0, 0, 0);
                for (int i = 0; i < 4; i++) {
                    mc.level.addParticle(ParticleTypes.CLOUD, pos.x, pos.y, pos.z, dir.x * 0.25 + HASARD.nextGaussian() * 0.04, 0.03, dir.z * 0.25 + HASARD.nextGaussian() * 0.04);
                }
            }
            default -> { }
        }
        if (moi && p.sorte() == Vent.E_ULTI && p.phase() == 2) {
            secouer(1.6F);
        }
    }

    private static DustParticleOptions paillette(int i) {
        float[] c = i % 3 == 0 ? PAILLETTE : i % 3 == 1 ? VERT_C : BLANC;
        return new DustParticleOptions(new Vector3f(c[0], c[1], c[2]), 0.55F);
    }

    // ================================================================ les poses

    /**
     * Les bras : en T pour la répulsion ; tendus vers l'avant pour la tempête (il tient la hallebarde à deux mains) ; la
     * main tendue à l'éveil (la fée naît dans sa main, puis vient se poser devant lui) ; bras ouverts quand elle plonge dans
     * son cœur, puis quand la tornade le soulève ; le bras levé, hallebarde au ciel, pour la tornade royale.
     */
    public static void poser(PlayerModel<?> m, Player joueur) {
        // la marque 3 en vol : allongé à plat, les bras le long du corps, la tête qui regarde devant
        float[] couche = INCLINAISONS.get(joueur.getUUID());
        if (couche != null && couche[0] > 0.03F && ClientMarque3.vole(joueur.getUUID()) && !ETATS.containsKey(joueur.getUUID())) {
            float k = Math.min(1, couche[0] / VOL_MAX);
            m.head.xRot = Mth.lerp(k, m.head.xRot, -1.25F);
            m.rightArm.xRot = Mth.lerp(k, m.rightArm.xRot, 0.12F);
            m.rightArm.zRot = Mth.lerp(k, m.rightArm.zRot, 0.1F);
            m.leftArm.xRot = Mth.lerp(k, m.leftArm.xRot, 0.12F);
            m.leftArm.zRot = Mth.lerp(k, m.leftArm.zRot, -0.1F);
            m.rightLeg.xRot = Mth.lerp(k, m.rightLeg.xRot, 0.04F);
            m.leftLeg.xRot = Mth.lerp(k, m.leftLeg.xRot, -0.04F);
            m.hat.copyFrom(m.head);
            m.rightSleeve.copyFrom(m.rightArm);
            m.leftSleeve.copyFrom(m.leftArm);
            m.rightPants.copyFrom(m.rightLeg);
            m.leftPants.copyFrom(m.leftLeg);
            return;
        }
        Etat e = ETATS.get(joueur.getUUID());
        if (e == null || e.fin >= 0) {
            return;
        }
        float[] d = null, g = null;
        double t1 = e.debut1 < 0 ? 99 : secondes(e.debut1), t2 = e.debut2 < 0 ? 99 : secondes(e.debut2), t3 = e.debut3 < 0 ? 99 : secondes(e.debut3);
        float k = 1;
        if (e.ulti >= 0 && e.ultiFin < 0 && secondes(e.ulti) < (Vent3.ULTI_FIN + 10) / 20.0) {
            if (e.leve >= 0) {
                d = new float[]{-3.0F, -0.2F, 0};
                g = new float[]{-0.4F, 0.8F, 0};
                k = (float) lisse(secondes(e.leve) / 0.3);
            } else {
                d = new float[]{-0.4F, 1.2F, 0};
                g = new float[]{-0.4F, -1.2F, 0};
                k = (float) lisse(secondes(e.ulti) / 0.5);
            }
        } else if (e.ramasse >= 0 && (e.eclat < 0 ? secondes(e.ramasse) < 1.5 : secondes(e.eclat) < 0.6)) {
            d = new float[]{0, 1.52F, 0};
            g = new float[]{0, -1.52F, 0};
            k = (float) lisse(secondes(e.ramasse) / 0.2) * (e.eclat < 0 ? 1F : (float) (1 - lisse((secondes(e.eclat) - 0.35) / 0.25)));
        } else if (e.pique >= 0 && secondes(e.pique) < 2) {
            d = new float[]{-2.9F, 0.2F, 0};
            g = new float[]{-2.6F, -0.3F, 0};
        } else if (e.tempete >= 0 && (e.tempeteFin < 0 || secondes(e.tempeteFin) < 0.25)) {
            d = new float[]{-1.55F, -0.05F, 0};
            g = new float[]{-1.4F, -0.4F, 0.4F};
            k = (float) lisse(secondes(e.tempete) / 0.2) * (e.tempeteFin < 0 ? 1F : (float) (1 - secondes(e.tempeteFin) / 0.25));
        } else if (t3 < FIN3) {
            d = new float[]{-0.3F, 1.1F, 0};
            g = new float[]{-0.3F, -1.1F, 0};
            k = (float) (lisse(t3 / 0.3) * (1 - lisse((t3 - (FIN3 - 0.6)) / 0.5)));
        } else if (t2 < 2.0) {
            if (t2 < 0.75) {
                d = new float[]{-1.35F, 0.1F, 0};
                k = (float) lisse(t2 / 0.3);
            } else {
                d = new float[]{-0.35F, 0.6F, 0};
                g = new float[]{-0.35F, -0.6F, 0};
                k = (float) (1 - lisse((t2 - 1.7) / 0.3));
            }
        } else if (t1 < 1.1) {
            d = new float[]{-1.3F, 0.35F, 0};
            k = (float) (lisse(t1 / 0.2) * (1 - lisse((t1 - 0.85) / 0.25)));
        } else if (joueur == Minecraft.getInstance().player && tenu && System.nanoTime() - tenuDebut > 100_000_000L && e.niveau >= 2) {
            // il charge le faucon (ou la tempête) : la main se lève devant lui
            d = new float[]{-2.2F, 0.1F, 0};
            k = (float) lisse(secondes(tenuDebut) / 0.6);
        }
        // en vol : couché en avant comme un super-héros, le bras droit tendu devant, l'autre le long du corps
        float[] incl = INCLINAISONS.get(joueur.getUUID());
        float vol = incl == null ? 0 : incl[0] / VOL_MAX;
        if (d == null && vol > 0.03F) {
            d = new float[]{-3.05F, -0.12F, 0};
            g = new float[]{-0.12F, -0.15F, 0};
            k = vol;
            m.head.xRot = Mth.lerp(vol, m.head.xRot, -1.2F);
            m.hat.copyFrom(m.head);
            m.rightLeg.xRot = Mth.lerp(vol, m.rightLeg.xRot, 0.06F);
            m.leftLeg.xRot = Mth.lerp(vol, m.leftLeg.xRot, -0.1F);
            m.rightPants.copyFrom(m.rightLeg);
            m.leftPants.copyFrom(m.leftLeg);
        }
        if (d != null) {
            m.rightArm.xRot = Mth.lerp(k, m.rightArm.xRot, d[0]);
            m.rightArm.zRot = Mth.lerp(k, m.rightArm.zRot, d[1]);
            m.rightArm.yRot = Mth.lerp(k, m.rightArm.yRot, d[2]);
            m.rightSleeve.copyFrom(m.rightArm);
        }
        if (g != null) {
            m.leftArm.xRot = Mth.lerp(k, m.leftArm.xRot, g[0]);
            m.leftArm.zRot = Mth.lerp(k, m.leftArm.zRot, g[1]);
            m.leftArm.yRot = Mth.lerp(k, m.leftArm.yRot, g[2]);
            m.leftSleeve.copyFrom(m.leftArm);
        }
    }

    // ================================================================ dessiner le vent

    static final class Types extends RenderType {
        /** Les voiles du vent : translucides, visibles des deux côtés. */
        static final RenderType VOILE = create("cabane_vent_voile", DefaultVertexFormat.POSITION_COLOR, VertexFormat.Mode.QUADS, 262144,
                false, true, CompositeState.builder().setShaderState(POSITION_COLOR_SHADER).setWriteMaskState(COLOR_WRITE)
                        .setTransparencyState(TRANSLUCENT_TRANSPARENCY).setCullState(NO_CULL).createCompositeState(false));
        /** La lueur autour des cœurs : de la lumière qui s'ajoute. */
        static final RenderType LUMIERE = create("cabane_vent_lumiere", DefaultVertexFormat.POSITION_COLOR, VertexFormat.Mode.QUADS, 262144,
                false, true, CompositeState.builder().setShaderState(RENDERTYPE_LIGHTNING_SHADER).setWriteMaskState(COLOR_WRITE)
                        .setTransparencyState(LIGHTNING_TRANSPARENCY).setCullState(NO_CULL).createCompositeState(false));

        private Types() {
            super("cabane_vent_rien", DefaultVertexFormat.POSITION, VertexFormat.Mode.QUADS, 256, false, false, () -> { }, () -> { });
        }
    }

    /** 0 : les voiles et les cœurs ; 1 : la lueur qui s'ajoute. Tout est dessiné dans les deux passes. */
    private static int passe;

    private static void sommet(VertexConsumer c, Matrix4f m, Vec3 p, float[] col, float a) {
        c.addVertex(m, (float) p.x, (float) p.y, (float) p.z).setColor(col[0], col[1], col[2], a);
    }

    private static void quad(VertexConsumer c, Matrix4f m, Vec3 a, Vec3 b, Vec3 d, Vec3 e, float[] col, float al) {
        sommet(c, m, a, col, al);
        sommet(c, m, b, col, al);
        sommet(c, m, d, col, al);
        sommet(c, m, e, col, al);
    }

    /** Le côté d'un segment, face à l'œil (la caméra est à l'origine), ou dans le sens donné. */
    private static Vec3 cote(Vec3 a, Vec3 b, Vec3 sens) {
        Vec3 tg = b.subtract(a);
        if (tg.lengthSqr() < 1e-12) {
            return null;
        }
        tg = tg.normalize();
        Vec3 s = sens != null ? sens.subtract(tg.scale(sens.dot(tg))) : tg.cross(a.add(b).scale(-0.5));
        if (s.lengthSqr() < 1e-10) {
            s = tg.cross(a.add(b).scale(-0.5));
        }
        return s.lengthSqr() < 1e-12 ? null : s.normalize();
    }

    /**
     * Un ruban de vent le long de pts (relatifs à la caméra) : une bande transparente de largeur « large », plus fine aux
     * deux bouts, et un cœur blanc qui brille (épais de « coeur », 0 sans cœur) au milieu ; sa lueur dans la passe 1.
     * {@code sens} : le sens de sa largeur (null : face à l'œil). {@code fondu} : il s'efface vers le début.
     */
    private static void ruban(VertexConsumer c, Matrix4f m, List<Vec3> pts, double large, float alpha, float[] col, Vec3 sens, double coeur, double fondu,
            float[] lueur) {
        int n = pts.size();
        if (n < 2 || alpha <= 0.003F) {
            return;
        }
        for (int k = 0; k < n - 1; k++) {
            Vec3 a = pts.get(k), b = pts.get(k + 1);
            double t0 = k / (double) (n - 1), t1 = (k + 1) / (double) (n - 1);
            Vec3 s = cote(a, b, sens);
            if (s == null) {
                continue;
            }
            float f = fondu > 0 ? (float) Math.pow((t0 + t1) / 2, fondu) : 1;
            double e0 = Math.max(0.003, large * Math.pow(Math.sin(Math.PI * t0), 0.7) / 2), e1 = Math.max(0.003, large * Math.pow(Math.sin(Math.PI * t1), 0.7) / 2);
            if (passe == 0) {
                quad(c, m, a.add(s.scale(-e0)), a.add(s.scale(e0)), b.add(s.scale(e1)), b.add(s.scale(-e1)), col, alpha * f);
            }
            if (coeur > 0 && t0 >= 0.1 && t1 <= 0.92) {
                Vec3 sc = cote(a, b, null);
                if (sc == null) {
                    continue;
                }
                double w = coeur / 2;
                if (passe == 0) {
                    quad(c, m, a.add(sc.scale(-w)), a.add(sc.scale(w)), b.add(sc.scale(w)), b.add(sc.scale(-w)), BLANC, Math.min(1, alpha * 2.4F) * f);
                } else {
                    // la lueur : pleine au milieu, rien au bord
                    for (int cotes = -1; cotes <= 1; cotes += 2) {
                        Vec3 o = sc.scale(cotes * coeur * 3.2);
                        sommet(c, m, a, lueur, 0.55F * Math.min(1, alpha * 2.4F) * f);
                        sommet(c, m, b, lueur, 0.55F * Math.min(1, alpha * 2.4F) * f);
                        sommet(c, m, b.add(o), lueur, 0);
                        sommet(c, m, a.add(o), lueur, 0);
                    }
                }
            }
        }
    }

    private static void ruban(VertexConsumer c, Matrix4f m, List<Vec3> pts, double large, float alpha, Vec3 sens, double coeur) {
        ruban(c, m, pts, large, alpha, VERT_C, sens, coeur, 0, LUEUR);
    }

    /** Un trait qui brille seul (le bord d'une tranche) : le cœur blanc, et sa lueur. */
    private static void trait(VertexConsumer c, Matrix4f m, List<Vec3> pts, double coeur, float alpha) {
        for (int k = 0; k + 1 < pts.size(); k++) {
            Vec3 a = pts.get(k), b = pts.get(k + 1);
            Vec3 s = cote(a, b, null);
            if (s == null) {
                continue;
            }
            if (passe == 0) {
                quad(c, m, a.add(s.scale(-coeur / 2)), a.add(s.scale(coeur / 2)), b.add(s.scale(coeur / 2)), b.add(s.scale(-coeur / 2)), BLANC, Math.min(1, alpha));
            } else {
                for (int cotes = -1; cotes <= 1; cotes += 2) {
                    Vec3 o = s.scale(cotes * coeur * 3.2);
                    sommet(c, m, a, LUEUR, 0.6F * alpha);
                    sommet(c, m, b, LUEUR, 0.6F * alpha);
                    sommet(c, m, b.add(o), LUEUR, 0);
                    sommet(c, m, a.add(o), LUEUR, 0);
                }
            }
        }
    }

    /** Deux vecteurs perpendiculaires à l'axe (pour tourner autour). */
    private static Vec3[] base(Vec3 axe) {
        Vec3 e1 = axe.cross(new Vec3(0, 0, 1));
        if (e1.lengthSqr() < 0.04) {
            e1 = axe.cross(new Vec3(1, 0, 0));
        }
        e1 = e1.normalize();
        return new Vec3[]{e1, axe.cross(e1)};
    }

    /** Une hélice autour d'un axe : de r0 à r1, de la hauteur h0 à h1, tours tours, en partant de l'angle a0 (comme helice()). */
    private static List<Vec3> helice(Vec3 c, Vec3 axe, double a0, double tours, double r0, double r1, double h0, double h1, int n, double ex) {
        axe = axe.normalize();
        Vec3[] e = base(axe);
        List<Vec3> pts = new ArrayList<>(n + 1);
        for (int k = 0; k <= n; k++) {
            double t = k / (double) n, a = a0 + tours * Math.PI * 2 * t;
            double r = Mth.lerp(t, r0, r1), y = Mth.lerp(t, h0, h1);
            pts.add(c.add(axe.scale(y)).add(e[0].scale(Math.cos(a) * r * ex)).add(e[1].scale(Math.sin(a) * r)));
        }
        return pts;
    }

    /** Le hasard des planches (hache) : toujours le même nombre pour les mêmes i, j, k. */
    private static double hache(int i, int j, int k) {
        int h = i * 374761393 + j * 668265263 + k * 2147483647;
        h = (h ^ (h >>> 13)) * 1274126177;
        return ((h ^ (h >>> 16)) & 0xFFFFFFFFL) / 4294967296.0;
    }

    /** Une petite feuille (un cube tourné), qui tourne sur elle-même. */
    private static void feuille(VertexConsumer c, Matrix4f m, Vec3 o, double s, double angle, float[] col, float alpha) {
        if (passe != 0) {
            return;
        }
        double ca = Math.cos(angle), sa = Math.sin(angle);
        Vec3 x = new Vec3(ca, sa * 0.5, sa).normalize().scale(s / 2), y = new Vec3(-sa * 0.5, 1, 0.3).normalize().scale(s / 2), z = x.cross(y).normalize().scale(s / 2);
        float[][] ombres = {{1F}, {0.6F}, {0.85F}, {0.75F}, {0.9F}, {0.7F}};
        Vec3[][] faces = {{y, x, z}, {y.scale(-1), x, z}, {x, y, z}, {x.scale(-1), y, z}, {z, x, y}, {z.scale(-1), x, y}};
        for (int f = 0; f < 6; f++) {
            Vec3 cen = o.add(faces[f][0]), u = faces[f][1], v = faces[f][2];
            float[] cc = {col[0] * ombres[f][0], col[1] * ombres[f][0], col[2] * ombres[f][0]};
            quad(c, m, cen.subtract(u).subtract(v), cen.add(u).subtract(v), cen.add(u).add(v), cen.subtract(u).add(v), cc, alpha);
        }
    }

    /**
     * Une tornade (comme tornade() des planches) : des rubans en hélice de r0 (en bas) à r1 (en haut), haute de H, autour de
     * l'axe ; ils tournent (tourne : l'angle en plus), et des feuilles tournent avec.
     */
    private static void tornade(VertexConsumer c, Matrix4f m, Vec3 base, Vec3 axe, double h, double r0, double r1, int n, int graine, double tours,
            double tourne, float alpha, double large, double coeur, int feuilles, double taille) {
        axe = axe.normalize();
        for (int s = 0; s < n; s++) {
            double h0 = h * hache(s, 1, graine) * 0.6, h1 = Math.min(h, h0 + h * (0.3 + 0.5 * hache(s, 2, graine)));
            double ra = Mth.lerp(h0 / h, r0, r1) * (0.8 + 0.35 * hache(s, 4, graine)), rb = Mth.lerp(h1 / h, r0, r1) * (0.8 + 0.35 * hache(s, 5, graine));
            List<Vec3> pts = helice(base, axe, hache(s, 3, graine) * Math.PI * 2 + tourne * (1.2 - 0.4 * h0 / h), (h1 - h0) / h * tours, ra, rb, h0, h1, 40, 1);
            // face à l'œil (la tempête couchée se voit aussi de derrière, la tornade royale d'en haut)
            ruban(c, m, pts, (ra + rb) / 2 * large, alpha * (float) (0.7 + 0.5 * hache(s, 6, graine)), s % 3 != 0 ? VERT_C : BLANC, null, s % 2 == 1 ? coeur : 0, 0, LUEUR);
        }
        Vec3[] e = base(axe);
        for (int q = 0; q < feuilles; q++) {
            double t = hache(q, 7, graine), a = hache(q, 8, graine) * Math.PI * 2 + tourne * 1.4, r = Mth.lerp(t, r0, r1) * (0.9 + 0.3 * hache(q, 9, graine));
            Vec3 p = base.add(axe.scale(t * h)).add(e[0].scale(Math.cos(a) * r)).add(e[1].scale(Math.sin(a) * r));
            feuille(c, m, p, taille * (0.8 + 0.8 * hache(q, 10, graine)), tourne * 3 + q, q % 4 != 0 ? FEUILLE : FEUILLE_B, alpha * 2.5F);
        }
    }

    /** Un anneau de vent à plat (sous les pieds au saut, l'onde au sol) : un ruban rond qui brille, et des traits qui fusent. */
    private static void anneau(VertexConsumer c, Matrix4f m, Vec3 centre, double r, double large, float alpha, int traits, double monte, double coeur) {
        List<Vec3> pts = new ArrayList<>();
        for (int k = 0; k <= 48; k++) {
            double a = k * Math.PI * 2 / 48;
            pts.add(centre.add(Math.cos(a) * r, 0, Math.sin(a) * r));
        }
        // la bande : pleine au cercle, rien au bord (pas de pointes aux bouts : c'est un rond)
        for (int k = 0; k < 48; k++) {
            Vec3 a = pts.get(k), b = pts.get(k + 1);
            Vec3 da = a.subtract(centre).normalize().scale(large / 2), db = b.subtract(centre).normalize().scale(large / 2);
            if (passe == 0) {
                for (int cotes = -1; cotes <= 1; cotes += 2) {
                    sommet(c, m, a, VERT_C, alpha);
                    sommet(c, m, b, VERT_C, alpha);
                    sommet(c, m, b.add(db.scale(cotes)), VERT_C, 0);
                    sommet(c, m, a.add(da.scale(cotes)), VERT_C, 0);
                }
            }
        }
        trait(c, m, pts, coeur, alpha * 1.6F);
        for (int q = 0; q < traits; q++) {
            double a = q * Math.PI * 2 / traits + 0.2;
            Vec3 d = new Vec3(Math.cos(a), 0, Math.sin(a));
            List<Vec3> l = ligne(centre.add(d.scale(r * 1.05)), centre.add(d.scale(r * (1.5 + 0.4 * hache(q, 1, 3)))).add(0, monte * r, 0), 8);
            ruban(c, m, l, r * 0.08, alpha * 1.1F, null, coeur * 0.55);
        }
    }

    private static List<Vec3> ligne(Vec3 a, Vec3 b, int n) {
        List<Vec3> l = new ArrayList<>(n + 1);
        for (int k = 0; k <= n; k++) {
            l.add(a.lerp(b, k / (double) n));
        }
        return l;
    }

    /** La sphère de vent (la répulsion) : une coque claire sur les bords, des rubans qui en font le tour. */
    private static void sphere(VertexConsumer c, Matrix4f m, Vec3 centre, double r, float alpha, int rubans, int graine, double tourne) {
        int nl = 14, nm = 28;
        if (passe == 0) {
            for (int i = 0; i < nl; i++) {
                for (int j = 0; j < nm; j++) {
                    double t0 = Math.PI * i / nl, t1 = Math.PI * (i + 1) / nl, p0 = Math.PI * 2 * j / nm, p1 = Math.PI * 2 * (j + 1) / nm;
                    Vec3 mil = point(centre, r, (t0 + t1) / 2, (p0 + p1) / 2);
                    Vec3 n = mil.subtract(centre).normalize();
                    double v = mil.lengthSqr() < 1e-6 ? 1 : -n.dot(mil.normalize());
                    if (v <= 0) {
                        continue;
                    }
                    float a = alpha * (float) (0.05 + 0.5 * Math.pow(1 - v, 3));
                    float[] col = {Mth.lerp(0.35F, BLANC[0], VERT[0]), Mth.lerp(0.35F, BLANC[1], VERT[1]), Mth.lerp(0.35F, BLANC[2], VERT[2])};
                    quad(c, m, point(centre, r, t0, p0), point(centre, r, t0, p1), point(centre, r, t1, p1), point(centre, r, t1, p0), col, a);
                }
            }
        }
        for (int s = 0; s < rubans; s++) {
            Vec3 axe = new Vec3(hache(s, 1, graine) - 0.5, hache(s, 2, graine) - 0.3, hache(s, 3, graine) - 0.5).normalize();
            double rr = r * (0.92 + 0.12 * hache(s, 4, graine));
            List<Vec3> pts = helice(centre, axe, hache(s, 6, graine) * Math.PI * 2 + tourne, 0.35 + 0.3 * hache(s, 5, graine), rr, rr, 0, 0, 30, 1);
            ruban(c, m, pts, r * 0.09, alpha * 0.45F, axe, s % 2 == 1 ? Math.max(0.02, r * 0.012) : 0);
        }
    }

    private static Vec3 point(Vec3 c, double r, double t, double p) {
        return c.add(r * Math.sin(t) * Math.cos(p), r * Math.cos(t), r * Math.sin(t) * Math.sin(p));
    }

    /**
     * Une tranche de vent (comme tranche() des planches) : un croissant, le bord bombé devant, qui file vers dir, centré en
     * C, de rayon R, penché de roll autour de sa trajectoire ; un bord qui brille, des traînées derrière.
     */
    private static void tranche(VertexConsumer c, Matrix4f m, Vec3 centre, Vec3 dir, double rayon, double roll, float alpha, int trainees, double longues) {
        Vec3 d = dir.normalize();
        Vec3 s = d.cross(new Vec3(0, 1, 0));
        if (s.lengthSqr() < 0.04) {
            s = d.cross(new Vec3(1, 0, 0));
        }
        s = s.normalize();
        Vec3 haut = s.cross(d);
        s = s.scale(Math.cos(roll)).add(haut.scale(Math.sin(roll))).normalize();
        // le croissant bascule un peu vers l'arrière : il se voit aussi de derrière (celui qui tire le voit partir)
        d = d.scale(Math.cos(0.45)).add(s.cross(d).normalize().scale(Math.sin(0.45))).normalize();
        double span = 2.1, ep = rayon * 0.26;
        int n = 24;
        List<Vec3> ext = new ArrayList<>(), in = new ArrayList<>();
        for (int k = 0; k <= n; k++) {
            double th = -span / 2 + span * k / n;
            Vec3 p = centre.add(s.scale(rayon * Math.sin(th))).add(d.scale(rayon * (Math.cos(th) - 1)));
            double e = ep * Math.pow(Math.max(0, Math.cos(Math.PI * th / span)), 1.2) + 0.003;
            ext.add(p);
            in.add(p.add(d.scale(-e)));
        }
        if (passe == 0) {
            float[] clair = {Mth.lerp(0.35F, VERT_C[0], BLANC[0]), Mth.lerp(0.35F, VERT_C[1], BLANC[1]), Mth.lerp(0.35F, VERT_C[2], BLANC[2])};
            for (int k = 0; k < n; k++) {
                quad(c, m, ext.get(k), ext.get(k + 1), in.get(k + 1), in.get(k), clair, 0.62F * alpha);
                quad(c, m, in.get(k), in.get(k + 1), in.get(k + 1).add(d.scale(-ep * 1.4)), in.get(k).add(d.scale(-ep * 1.4)), VERT, 0.19F * alpha);
            }
        }
        trait(c, m, ext.subList(2, n - 1), Math.max(0.035, rayon * 0.065), alpha);
        for (int q = 0; q < trainees; q++) {
            Vec3 a = in.get((int) Math.round((q + 0.5) / trainees * n));
            ruban(c, m, ligne(a, a.add(d.scale(-longues * (0.5 + 0.6 * hache(q, 1, 7)))), 8), rayon * 0.07, alpha * 0.45F, null, q % 2 == 0 ? 0.016 : 0);
        }
    }

    // ================================================================ tout dessiner dans le monde

    private static void dessiner(PoseStack pose, Vec3 cam, float partiel) {
        Minecraft mc = Minecraft.getInstance();
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        // d'abord les modèles (la fée, le faucon, les lances, la hallebarde de la tornade royale), puis le vent
        mc.gameRenderer.lightTexture().turnOnLightLayer();
        modeles(pose, buffers, cam, partiel);
        buffers.endBatch(RenderType.entityCutoutNoCull(ModeleVent.BLANC));
        buffers.endBatch(RenderType.entityTranslucentEmissive(ModeleVent.BLANC));
        buffers.endBatch(RenderType.lightning());
        mc.gameRenderer.lightTexture().turnOffLightLayer();
        Matrix4f m = pose.last().pose();
        for (passe = 0; passe < 2; passe++) {
            RenderType type = passe == 0 ? Types.VOILE : Types.LUMIERE;
            tout(buffers.getBuffer(type), m, cam, partiel);
            buffers.endBatch(type);
        }
        passe = 0;
    }

    /** Ceux qui ont une fée : ceux qui ont le pouvoir (même endormis), et ceux que le serveur a dits éveillés. */
    private static java.util.Set<UUID> avecFee() {
        java.util.Set<UUID> l = new java.util.HashSet<>(PORTEURS);
        l.addAll(ETATS.keySet());
        return l;
    }

    /**
     * Où est la fée de ce joueur, vers où elle vole, et (x) sa taille de 0 à 1, (y) 1 si elle tourne simplement autour de
     * lui ; null s'il n'en a pas (aux niveaux 2 et 3, il l'a absorbée). Elle est toujours là pour celui qui a le pouvoir,
     * même endormi : elle tourne autour de lui. À l'éveil 1, elle va dans sa main (le petit tourbillon) puis revient ; à
     * l'éveil 2, elle vole devant sa main et plonge dans son cœur ; à la fin d'un éveil 2 ou 3, elle ressort de son cœur.
     */
    private static Vec3[] fee(Player j, Etat e, float partiel) {
        Vec3 pieds = j.getPosition(partiel);
        double temps = (j.tickCount + partiel) / 20.0;
        float lacet = Mth.lerp(partiel, j.yBodyRotO, j.yBodyRot) * Mth.DEG_TO_RAD;
        Vec3 devant = new Vec3(-Mth.sin(lacet), 0, Mth.cos(lacet)), droite = new Vec3(-Mth.cos(lacet), 0, -Mth.sin(lacet));
        Vec3 main = pieds.add(droite.scale(0.33)).add(devant.scale(0.62)).add(0, 1.25, 0);
        Vec3 coeur = pieds.add(0, 1.25, 0).add(devant.scale(0.14));
        double a = temps * 2.2 + j.getId();
        Vec3 orbite = pieds.add(Math.cos(a) * 0.95, 1.56 + 0.09 * Math.sin(a * 2), Math.sin(a) * 0.75);
        Vec3 sensOrbite = new Vec3(-Math.sin(a) * 0.95, 0, Math.cos(a) * 0.75);
        Vec3[] tourne = {orbite, sensOrbite, new Vec3(1, 1, 0)};
        if (e == null) {
            return PORTEURS.contains(j.getUUID()) ? tourne : null;
        }
        if (e.fin >= 0) {
            if (e.niveauFin <= 1) {
                return tourne;
            }
            // elle ressort de son cœur et se remet à tourner
            double k = lisse((secondes(e.fin) - 0.2) / 0.8);
            return k <= 0 ? null : k >= 1 ? tourne : new Vec3[]{coeur.lerp(orbite, k), sensOrbite, new Vec3(k, 0, 0)};
        }
        double t1 = e.debut1 < 0 ? 99 : secondes(e.debut1), t2 = e.debut2 < 0 ? 99 : secondes(e.debut2);
        if (e.niveau == 1) {
            if (t1 < 1.05) {
                // elle file dans sa main, tourne dans le petit tourbillon, puis repart tourner autour de lui
                Vec3 dansLaMain = main.add(0, 0.4 + 0.08 * Math.sin(t1 * 20), 0);
                double va = lisse(t1 / 0.3), revient = lisse((t1 - 0.8) / 0.25);
                Vec3 p = orbite.lerp(dansLaMain, va).lerp(orbite, revient);
                return new Vec3[]{p, revient > 0 ? sensOrbite : devant.scale(-1), new Vec3(1, 0, 0)};
            }
            return tourne;
        }
        if (e.niveau == 2 && t2 < 1.25) {
            Vec3 devantMain = main.add(devant.scale(0.15)).add(0, 0.42, 0);
            if (t2 < 0.6) {
                double k = lisse(t2 / 0.55);
                return new Vec3[]{orbite.lerp(devantMain, k), devant.scale(-1), new Vec3(1, 0, 0)};
            }
            // elle plonge en spirale dans son cœur
            double k = lisse((t2 - 0.6) / 0.6), ang = k * Math.PI * 3, r = 0.45 * (1 - k);
            Vec3 p = devantMain.lerp(coeur, k).add(droite.scale(Math.cos(ang) * r)).add(0, Math.sin(ang) * r, 0);
            return new Vec3[]{p, coeur.subtract(p), new Vec3(1 - 0.7 * k, 0, 0)};
        }
        return null;
    }

    private static void modele(PoseStack pose, MultiBufferSource buffers, ModeleVent mod, Vec3 ou, Vec3 dir, float echelle, float alpha) {
        modele(pose, buffers, mod, ou, dir, 0, echelle, alpha);
    }

    /** Un modèle posé en ou, son avant (+z) vers dir, penché de roulis autour de son avant. */
    private static void modele(PoseStack pose, MultiBufferSource buffers, ModeleVent mod, Vec3 ou, Vec3 dir, float roulis, float echelle, float alpha) {
        if (mod == null) {
            return;
        }
        pose.pushPose();
        pose.translate(ou.x, ou.y, ou.z);
        if (dir != null && dir.lengthSqr() > 1e-6) {
            Vec3 d = dir.normalize();
            pose.mulPose(Axis.YP.rotation((float) Math.atan2(d.x, d.z)));
            pose.mulPose(Axis.XP.rotation((float) -Math.asin(Mth.clamp(d.y, -1, 1))));
            pose.mulPose(Axis.ZP.rotation(roulis));
        }
        pose.scale(echelle, echelle, echelle);
        mod.dessiner(buffers, pose.last(), LightTexture.FULL_BRIGHT, 1, alpha);
        pose.popPose();
    }

    private static void modeles(PoseStack pose, MultiBufferSource buffers, Vec3 cam, float partiel) {
        Minecraft mc = Minecraft.getInstance();
        long battement = System.nanoTime() / 70_000_000L;
        int[] ailes = {0, 1, 2, 1};
        ModeleVent fee = ModeleVent.premier("fee_" + ailes[(int) (battement % 4)]);
        for (UUID qui : avecFee()) {
            Player j = mc.level.getPlayerByUUID(qui);
            if (j == null || j.isInvisible()) {
                continue;
            }
            Etat e = ETATS.get(qui);
            Vec3[] f = fee(j, e, partiel);
            if (f != null && f[2].x > 0.01) {
                modele(pose, buffers, fee, f[0].subtract(cam), f[1], (float) (0.9 * PX * Math.max(0.05, f[2].x)), 1);
            }
            if (e == null) {
                continue;
            }
            // la tornade royale : il lève la hallebarde, une boule de lumière au bout
            if (e.ulti >= 0 && e.leve >= 0 && e.explose < 0) {
                Vec3 tete = j.getPosition(partiel).add(0, 2.4, 0).subtract(cam);
                modele(pose, buffers, ModeleVent.premier("lance"), tete.add(0, 0.6, 0), new Vec3(0, 1, 0), (float) (PX * 1.6), 1);
            }
        }
        // le faucon de vent, là où il est (un peu en avant, le temps que la position suivante arrive)
        ModeleVent faucon = ModeleVent.premier("faucon");
        for (Faucon f : FAUCONS.values()) {
            double k = Math.min(1.5, secondes(f.recu) / 0.05);
            Vec3 p = f.pos.add(f.dir.scale(Vent.VITESSE_FAUCON * (f.fin >= 0 ? 0 : k - 1)));
            float a = f.fin < 0 ? 1 : (float) (1 - secondes(f.fin) / 0.3);
            double t = secondes(f.debut);
            if (a > 0.02) {
                // il penche en volant (ses ailes se voient aussi de derrière)
                modele(pose, buffers, faucon, p.subtract(cam), f.dir, (float) (0.55 + 0.15 * Math.sin(t * 7)), (float) (1.9 * PX * Math.min(1, t / 0.12)), a);
            }
        }
        // les lances en vol
        ModeleVent lance = ModeleVent.premier("lance");
        for (Volee v : LANCES.values()) {
            for (Vec3[] l : v.lances) {
                modele(pose, buffers, lance, l[0].subtract(cam), l[1], (float) (1.5 * PX), 1);
            }
        }
    }

    private static void tout(VertexConsumer c, Matrix4f m, Vec3 cam, float partiel) {
        Minecraft mc = Minecraft.getInstance();
        for (Map.Entry<UUID, Etat> en : ETATS.entrySet()) {
            Player j = mc.level.getPlayerByUUID(en.getKey());
            if (j == null) {
                continue;
            }
            Etat e = en.getValue();
            Vec3 pieds = j.getPosition(partiel).subtract(cam);
            double temps = (j.tickCount + partiel) / 20.0;
            if (!j.isInvisible()) {
                autourDeLui(c, m, j, e, pieds, temps, cam, partiel);
            }
            gestes(c, m, j, e, pieds, temps, cam, partiel);
        }
        // la traînée de paillettes des fées qui tournent
        for (UUID qui : avecFee()) {
            Player j = mc.level.getPlayerByUUID(qui);
            Vec3[] f = j == null || j.isInvisible() ? null : fee(j, ETATS.get(qui), partiel);
            if (f == null || f[2].y < 0.5) {
                continue;
            }
            Vec3 pieds = j.getPosition(partiel).subtract(cam);
            double a = (j.tickCount + partiel) / 20.0 * 2.2 + j.getId();
            List<Vec3> pts = new ArrayList<>();
            for (int k = 0; k <= 30; k++) {
                double b = a - 2.6 + 2.6 * k / 30;
                pts.add(pieds.add(Math.cos(b) * 0.95, 1.56 + 0.09 * Math.sin(b * 2), Math.sin(b) * 0.75));
            }
            ruban(c, m, pts, 1.2 * PX, 0.35F, DORE_DOUX, null, 0.18 * PX, 1.4, PAILLETTE);
        }
        // les tranches de vent
        for (Tranches t : TRANCHES) {
            double s = secondes(t.debut());
            double loin = Math.min(Vent.PORTEE_TRANCHES, s * Vent.VITESSE_TRANCHES * 20 + 0.6);
            float a = (float) (1 - lisse((s - Vent.PORTEE_TRANCHES / Vent.VITESSE_TRANCHES / 20) / 0.2));
            double ecart = t.fort() ? 15 : 18;
            for (int i = 0; i < t.n(); i++) {
                double ang = Math.toRadians((i - (t.n() - 1) / 2.0) * ecart), co = Math.cos(ang), si = Math.sin(ang);
                Vec3 r = t.regard();
                Vec3 d = new Vec3(r.x * co - r.z * si, r.y, r.x * si + r.z * co).normalize();
                // en X : une sur deux penchée d'un côté, l'autre de l'autre
                double roll = (i % 2 == 0 ? 0.7 : -0.7) + (i - (t.n() - 1) / 2.0) * 0.12;
                tranche(c, m, t.depart().add(d.scale(loin)).subtract(cam), d, t.fort() ? 1.0 : 0.7, roll, a, 5, (t.fort() ? 1.0 : 0.7) * 1.6);
            }
        }
        // la traînée du faucon
        for (Faucon f : FAUCONS.values()) {
            float a = f.fin < 0 ? 1 : (float) (1 - secondes(f.fin) / 0.4);
            List<Vec3> pts = new ArrayList<>();
            for (Vec3 p : f.traine) {
                pts.add(p.subtract(cam));
            }
            if (pts.size() >= 2 && a > 0) {
                ruban(c, m, pts, 6 * PX * 1.9, 0.28F * a, VERT_C, null, 0.5 * PX * 1.9, 0.6, LUEUR);
            }
        }
        // la lame de la hallebarde : deux croissants qui filent 8 blocs
        for (Lame l : LAMES) {
            double t = secondes(l.debut());
            float a = (float) (1 - lisse((t - 0.2) / 0.2));
            Vec3 d = l.dir().normalize();
            tranche(c, m, l.pos().add(d.scale(1.2 + 6.5 * lisse(t / 0.3))).subtract(cam), d, 1.4, 0.8, a, 5, 2.2);
            tranche(c, m, l.pos().add(d.scale(1 + 4.5 * lisse(t / 0.3))).subtract(cam), d, 1.0, -0.6, a * 0.6F, 3, 1.6);
        }
        // les anneaux de vent sous ses pieds (les sauts)
        for (Anneau an : ANNEAUX) {
            double t = secondes(an.debut());
            float a = (float) (1 - t / 0.5);
            anneau(c, m, an.pos().subtract(cam).add(0, -1 * PX, 0), 8 * PX * (0.7 + 0.5 * lisse(t / 0.2)), 2.2 * PX, 0.45F * a, 8, -0.3, 0.35 * PX * 1.5);
        }
        // les tourbillons
        for (Map.Entry<UUID, Tourbillon> en : TOURBILLONS.entrySet()) {
            Tourbillon t = en.getValue();
            double s = secondes(t.debut);
            float a = t.fin < 0 ? (float) lisse(s / 0.2) : (float) (1 - secondes(t.fin) / 0.4);
            tornade(c, m, t.pos.subtract(cam), new Vec3(0, 1, 0), 70 * PX, 4 * PX, 18 * PX, 18, 91, 3, s * 9, 0.3F * a, 0.35, 0.45 * PX, 14, 0.9 * PX);
        }
        // les lances : leur traînée de vent
        for (Volee v : LANCES.values()) {
            for (ArrayDeque<Vec3> tr : v.traines) {
                List<Vec3> pts = new ArrayList<>();
                for (Vec3 p : tr) {
                    pts.add(p.subtract(cam));
                }
                ruban(c, m, pts, 2.2 * PX, 0.32F, VERT_C, null, 0.35 * PX, 0.8, LUEUR);
            }
        }
    }

    /** Autour de lui, selon son niveau : la fée et sa traînée de paillettes, la brise, et ses transformations. */
    private static void autourDeLui(VertexConsumer c, Matrix4f m, Player j, Etat e, Vec3 pieds, double temps, Vec3 cam, float partiel) {
        double t1 = e.debut1 < 0 ? 99 : secondes(e.debut1), t2 = e.debut2 < 0 ? 99 : secondes(e.debut2), t3 = e.debut3 < 0 ? 99 : secondes(e.debut3);
        boolean premierePersonne = j == Minecraft.getInstance().player && Minecraft.getInstance().options.getCameraType().isFirstPerson();
        if (e.fin >= 0) {
            // la rafale : des feuilles qui s'envolent tout autour
            double t = secondes(e.fin);
            float a = (float) (1 - t / 1.2);
            if (a > 0) {
                for (int q = 0; q < 28; q++) {
                    double ang = hache(q, 1, 77) * Math.PI * 2, v = 1.5 + 2.5 * hache(q, 2, 77);
                    Vec3 p = pieds.add(Math.cos(ang) * (0.3 + v * t), 0.3 + 1.6 * hache(q, 3, 77) + v * t * 0.8, Math.sin(ang) * (0.3 + v * t));
                    feuille(c, m, p, 0.9 * PX * 1.6, t * 8 + q, q % 4 != 0 ? FEUILLE : FEUILLE_B, a);
                }
                for (int s = 0; s < 4; s++) {
                    ruban(c, m, helice(pieds, new Vec3(0, 1, 0), s * 1.6 + t * 6, 0.8, (8 + 10 * t) * PX * 1.6, (12 + 14 * t) * PX * 1.6, 2 * PX, 30 * PX, 30, 1),
                            1.6 * PX, 0.3F * a, new Vec3(0, 1, 0), s % 2 == 0 ? 0.3 * PX : 0);
                }
            }
            return;
        }
        // niveau 1 : la brise à ses pieds (la traînée de la fée est dessinée pour tous ceux qui en ont une)
        if (e.niveau == 1 && !premierePersonne) {
            for (int s = 0; s < 4; s++) {
                ruban(c, m, helice(pieds, new Vec3(0, 1, 0), hache(s, 5, 1) * Math.PI * 2 + temps * 1.5, 0.6, (9 + 3 * hache(s, 1, 1)) * PX, (11 + 4 * hache(s, 2, 1)) * PX,
                        (1 + 6 * hache(s, 3, 1)) * PX, (4 + 10 * hache(s, 4, 1)) * PX, 24, 1.15), 1.4 * PX, 0.22F, new Vec3(0, 1, 0), s % 2 == 1 ? 0.22 * PX : 0);
            }
            for (int q = 0; q < 6; q++) {
                double ang = hache(q, 6, 1) * Math.PI * 2 + temps * 1.3, r = (9 + 5 * hache(q, 7, 1)) * PX;
                feuille(c, m, pieds.add(Math.cos(ang) * r, (2 + 12 * hache(q, 8, 1)) * PX, Math.sin(ang) * r), 0.9 * PX, temps * 3 + q, q % 3 != 0 ? FEUILLE : FEUILLE_B, 0.9F);
            }
        }
        // éveil 1 : une colonne de vent se lève en spirale autour de lui, un anneau file au sol ; la fée sort d'un petit
        // tourbillon dans sa main, dans un éclat de lumière
        if (t1 < 1.4 && e.niveau >= 1) {
            float a = (float) (lisse(t1 / 0.12) * (1 - lisse((t1 - 0.85) / 0.5)));
            double haut = 44 * PX * lisse(t1 / 0.5);
            for (int s = 0; s < 10; s++) {
                ruban(c, m, helice(pieds, HAUT, s * 0.63 + t1 * 6, 1.8, (6 + 3 * (s % 3)) * PX, (16 + 4 * (s % 2)) * PX, 0, haut, 40, 1), 2.4 * PX, 0.3F * a,
                        null, s % 2 == 1 ? 0.35 * PX : 0);
            }
            anneau(c, m, pieds.add(0, 0.05, 0), 0.3 + 2.6 * lisse(t1 / 0.7), 4 * PX, 0.5F * a, 14, 0.12, 0.5 * PX);
            colonne(c, m, pieds, 0.6, 3.4, 0.4F * a);
            float lacet = j.yBodyRot * Mth.DEG_TO_RAD;
            Vec3 devant = new Vec3(-Mth.sin(lacet), 0, Mth.cos(lacet)), droite = new Vec3(-Mth.cos(lacet), 0, -Mth.sin(lacet));
            Vec3 main = pieds.add(droite.scale(0.33)).add(devant.scale(0.62)).add(0, 1.25, 0);
            float b = (float) (lisse((t1 - 0.1) / 0.2) * (1 - lisse((t1 - 0.75) / 0.3)));
            if (b > 0) {
                tornade(c, m, main, HAUT, 14 * PX, 0.8 * PX, 6 * PX, 12, 21, 2.5, t1 * 12, 0.55F * b, 0.5, 0.3 * PX, 8, 0.6 * PX);
            }
            double eclat = Math.sin(Math.PI * Mth.clamp((t1 - 0.3) / 0.35, 0, 1));
            if (eclat > 0) {
                boule(c, m, main.add(0, 0.4, 0), 0.12 + 0.3 * eclat, LUEUR, (float) (0.7 * eclat));
            }
        }
        // éveil 2 : le vent converge vers lui pendant qu'elle plonge en spirale dans son cœur ; un flash, des rayons, puis
        // la boule de vent éclate (deux coques), l'onde file au sol et l'anneau de lumière pose la tenue
        if (t2 < 2.5 && e.niveau >= 2) {
            float lacet = j.yBodyRot * Mth.DEG_TO_RAD;
            Vec3 devant = new Vec3(-Mth.sin(lacet), 0, Mth.cos(lacet));
            Vec3 coeur = pieds.add(0, 1.25, 0).add(devant.scale(0.14));
            if (t2 > 0.5 && t2 < 1.3) {
                double k = lisse((t2 - 0.6) / 0.6);
                List<Vec3> pts = helice(coeur, devant.add(0, 0.3, 0).add(devant.cross(HAUT).scale(0.2)), 0, 1.5, 7 * PX * 1.2, 0.5 * PX, 18 * PX * 1.2, 0, 50, 1);
                int fin = (int) Math.round(Mth.clamp(k, 0.05, 1) * 50);
                ruban(c, m, pts.subList(0, Math.max(2, fin)), 2.4 * PX, 0.5F * (float) (1 - lisse((t2 - 1.1) / 0.2)), DORE_DOUX, null, 0.6 * PX, 0, PAILLETTE);
                // le vent se ramasse vers son cœur
                float v = (float) Math.sin(Math.PI * Mth.clamp((t2 - 0.5) / 0.75, 0, 1));
                for (int q = 0; q < 14; q++) {
                    double ang = q * Math.PI * 2 / 14 + t2 * 2, ph = (t2 * 1.6 + q / 14.0) % 1;
                    Vec3 d = new Vec3(Math.cos(ang), (hache(q, 1, 43) - 0.5) * 1.2, Math.sin(ang)).normalize();
                    Vec3 de = coeur.add(d.scale(0.4 + 3.2 * (1 - ph)));
                    ruban(c, m, ligne(de, de.subtract(d.scale(0.9)), 6), 1.4 * PX, 0.4F * v, null, 0.25 * PX);
                }
            }
            if (t2 > 1.1 && t2 < 1.6) {
                // le flash : une boule de lumière et des rayons blancs qui partent de son cœur
                float a = (float) Math.sin(Math.PI * Mth.clamp((t2 - 1.1) / 0.5, 0, 1));
                boule(c, m, coeur, 0.2 + 0.9 * a, BLANC, 0.85F * a);
                for (int q = 0; q < 24; q++) {
                    double ang = q * Math.PI * 2 / 24;
                    Vec3 dd = devant.cross(HAUT).scale(Math.cos(ang)).add(0, Math.sin(ang) * 0.9, 0).add(devant.scale(0.35)).normalize();
                    ruban(c, m, ligne(coeur.add(dd.scale(2 * PX)), coeur.add(dd.scale((22 + 10 * hache(q, 1, 42)) * PX * 1.4)), 8), 1.6 * PX, 0.55F * a, BLANC, null, 0.35 * PX, 0, LUEUR);
                }
                colonne(c, m, pieds, 0.7, 6, 0.5F * a);
            }
            if (t2 > 1.2) {
                double k = (t2 - 1.2) / 0.8;
                float a = (float) (1 - lisse(k));
                if (a > 0) {
                    sphere(c, m, pieds.add(0, 16 * PX, 0), 2.6 * (0.25 + 0.75 * lisse(k * 2)), 0.75F * a, 14, 52, t2 * 2);
                    double k2 = Mth.clamp(k * 2 - 0.25, 0, 1);
                    if (k2 > 0) {
                        sphere(c, m, pieds.add(0, 16 * PX, 0), 3.6 * lisse(k2), 0.35F * a, 8, 53, -t2 * 2);
                    }
                    anneau(c, m, pieds.add(0, 0.4 * PX, 0), 4.2 * lisse(k * 1.6), 7 * PX, 0.5F * a, 22, 0.15, 0.5 * PX);
                }
                // l'anneau de lumière qui descend : la tenue se pose du haut vers le bas
                double kd = Mth.clamp((t2 - 1.2) / 0.4, 0, 1);
                if (kd < 1) {
                    anneau(c, m, pieds.add(0, (33 - 33 * kd) * PX, 0), 11 * PX, 3 * PX, 0.6F, 8, 0, 0.45 * PX);
                }
            }
        }
        // éveil 3 : une grande tornade verte le soulève (et une autre, plus large, tourne dans l'autre sens), une colonne
        // de lumière monte au ciel, des anneaux montent autour de lui ; il s'illumine de vert (voir ClientTenueVent), les
        // armes se forment dans le vent ; puis la tornade explose en anneau, et la lumière retombe sur sa forme
        if (t3 < FIN3 && e.niveau >= 3) {
            Vec3 sol = e.sol3 == null ? pieds : e.sol3.subtract(cam);
            float a = (float) (lisse(t3 / 0.3) * (1 - lisse((t3 - (ECLAT3 - 0.2)) / 0.25)));
            if (a > 0) {
                tornade(c, m, sol, HAUT, 7.5, 0.5, 2.4, 32, 61, 2.6, t3 * 8, 0.32F * a, 0.35, 0.03, 40, PX * 1.3);
                tornade(c, m, sol, HAUT, 9, 1.4, 3.6, 14, 62, 1.6, -t3 * 4, 0.14F * a, 0.3, 0, 0, PX);
                colonne(c, m, sol, 0.9, 18, 0.45F * a);
                for (int i = 0; i < 4; i++) {
                    double ph = (t3 * 0.8 + i / 4.0) % 1;
                    anneau(c, m, sol.add(0, 0.2 + ph * 6, 0), 1.6 + 0.8 * Math.sin(ph * Math.PI), 4 * PX, 0.4F * a * (float) Math.sin(ph * Math.PI), 0, 0, 0.35 * PX);
                }
            }
            if (t3 > ILLUMINE - 0.3 && t3 < ECLAT3 + 0.1) {
                float b = (float) Math.sin(Math.PI * Mth.clamp((t3 - (ILLUMINE - 0.3)) / (ECLAT3 - ILLUMINE + 0.4), 0, 1));
                float lacet = j.yBodyRot * Mth.DEG_TO_RAD;
                Vec3 droite = new Vec3(-Mth.cos(lacet), 0, -Mth.sin(lacet));
                Vec3 main = pieds.add(droite.scale(0.6)).add(0, 1.3, 0);
                for (int q = 0; q < 16; q++) {
                    double ang = q * Math.PI * 2 / 16 + t3;
                    Vec3 de = main.add(Math.cos(ang) * 22 * PX, (hache(q, 1, 71) - 0.5) * 34 * PX, Math.sin(ang) * 22 * PX);
                    ruban(c, m, ligne(de, main.add(0, (hache(q, 2, 71) - 0.5) * 20 * PX, 0), 8), 1.2 * PX, 0.5F * b, null, 0.3 * PX);
                }
            }
            if (t3 > ECLAT3 - 0.1) {
                double k = lisse((t3 - ECLAT3 + 0.1) / 0.5);
                float b = (float) (1 - lisse((t3 - ECLAT3 - 0.2) / 0.7));
                Vec3 sous = pieds.add(0, -0.4, 0);
                boule(c, m, pieds.add(0, 1, 0), 0.6 + 2.6 * k, LUEUR, 0.5F * (float) (1 - k));
                anneau(c, m, sous, 5.5 * (0.2 + 0.8 * k), 9 * PX, 0.55F * b, 26, 0, 0.7 * PX);
                anneau(c, m, sous.add(0, 0.6, 0), 3.4 * (0.2 + 0.8 * k), 5 * PX, 0.4F * b, 0, 0, 0.5 * PX);
                anneau(c, m, sol.add(0, 0.4 * PX, 0), 4 * (0.3 + 0.7 * k), 5 * PX, 0.4F * b, 16, 0.1, 0.4 * PX);
                for (int s = 0; s < 12; s++) {
                    double ang = s * Math.PI / 6;
                    ruban(c, m, helice(sol.add(Math.cos(ang) * 4 * k, 0.5, Math.sin(ang) * 4 * k), HAUT, ang + t3 * 6, 1.2, 4 * PX, 12 * PX, 0, 3, 24, 1), 2.4 * PX, 0.28F * b,
                            null, 0.3 * PX);
                }
                for (int q = 0; q < 36; q++) {
                    double ang = hache(q, 1, 73) * Math.PI * 2, v = 3 + 5 * hache(q, 2, 73), tt = t3 - ECLAT3 + 0.1;
                    Vec3 p = pieds.add(Math.cos(ang) * v * tt, 0.6 + 2.4 * hache(q, 3, 73) + v * tt * 0.4, Math.sin(ang) * v * tt);
                    feuille(c, m, p, 1.6 * PX, tt * 9 + q, q % 4 != 0 ? FEUILLE : FEUILLE_B, b);
                }
            }
        }
        // niveau 3 en vol : le vent derrière lui
        if (e.niveau >= 3 && t3 > 2 && j.getAbilities().flying && j.getDeltaMovement().horizontalDistance() > 0.15) {
            Vec3 v = j.getDeltaMovement().normalize();
            for (int q = 0; q < 5; q++) {
                Vec3 de = pieds.add(0, 0.4 + 0.3 * q, 0).add(v.cross(new Vec3(0, 1, 0)).scale((q - 2) * 0.22));
                ruban(c, m, ligne(de, de.subtract(v.scale(2.5 + hache(q, 2, 81) * 1.5)), 8), 2 * PX, 0.3F, null, q % 2 == 0 ? 0.3 * PX : 0);
            }
        }
    }

    /** Ses gestes qui durent : la répulsion, le piqué, la tempête, la tornade royale. */
    private static void gestes(VertexConsumer c, Matrix4f m, Player j, Etat e, Vec3 pieds, double temps, Vec3 cam, float partiel) {
        // la répulsion : le vent se ramasse autour de lui (bras en T, deux anneaux qui tournent, une boule qui grossit), puis
        // la sphère éclate : un flash, deux coques, l'onde au sol, des traits de vent et des feuilles qui partent de partout
        if (e.ramasse >= 0 && e.centreRep != null) {
            Vec3 cen = e.eclat >= 0 ? e.centreRep.subtract(cam) : pieds.add(0, 1.1, 0);
            double rayon = Math.max(4, e.rayonRep);
            if (e.eclat < 0 && secondes(e.ramasse) < 1.5) {
                double t = secondes(e.ramasse), k = lisse(t / (Vent.RAMASSE / 20.0));
                sphere(c, m, cen, 1.4 * k, 0.75F, 10, 101, temps * 4);
                boule(c, m, cen, 0.55 * k, LUEUR, 0.55F * (float) k);
                ruban(c, m, helice(cen, HAUT, temps * 7, 1, 1.9, 1.9, 0, 0, 40, 1), 4 * PX, 0.5F * (float) k, null, 0.5 * PX);
                ruban(c, m, helice(cen, new Vec3(1, 0.2, 0.4), -temps * 6, 1, 1.7, 1.7, 0, 0, 40, 1), 3 * PX, 0.45F * (float) k, null, 0.4 * PX);
                for (int q = 0; q < 18; q++) {
                    double ang = q * Math.PI * 2 / 18 + temps, ph = (t * 2.4 + q / 18.0) % 1;
                    Vec3 d = new Vec3(Math.cos(ang), (hache(q, 1, 102) - 0.5) * 0.9, Math.sin(ang)).normalize();
                    Vec3 de = cen.add(d.scale(1.4 + 4 * (1 - ph)));
                    ruban(c, m, ligne(de, de.subtract(d.scale(1.4)), 8), 2.2 * PX, 0.45F, null, 0.35 * PX);
                }
            } else if (e.eclat >= 0) {
                double t = secondes(e.eclat);
                float a = (float) (1 - lisse(t / 1.2));
                if (a > 0) {
                    double r = rayon * (0.15 + 0.85 * lisse(t / 0.22));
                    double flash = 1 - lisse(t / 0.3);
                    if (flash > 0) {
                        boule(c, m, cen, 0.4 + 2.4 * (1 - flash), BLANC, (float) flash);
                    }
                    sphere(c, m, cen, r, a, 20, 111, temps * 2);
                    double r2 = rayon * 1.3 * lisse(Math.max(0, t - 0.1) / 0.35);
                    if (r2 > 0.2) {
                        sphere(c, m, cen, r2, a * 0.4F, 10, 112, -temps * 2);
                    }
                    anneau(c, m, cen.add(0, -1.05, 0), rayon * 1.35 * lisse(t / 0.4), rayon * 0.2, 0.6F * a, 32, 0.12, 0.06);
                    anneau(c, m, cen.add(0, -1, 0), rayon * 0.75 * lisse(t / 0.25), rayon * 0.1, 0.45F * a, 0, 0, 0.05);
                    for (int q = 0; q < 28; q++) {
                        double ang = q * Math.PI * 2 / 28 + 0.13;
                        Vec3 d = new Vec3(Math.cos(ang), (hache(q, 2, 113) - 0.4) * 0.7, Math.sin(ang)).normalize();
                        double rr = r * (0.85 + 0.6 * lisse(t / 0.7));
                        ruban(c, m, ligne(cen.add(d.scale(rr)), cen.add(d.scale(rr + 3.5)), 8), 0.14, 0.45F * a, null, 0.035);
                    }
                    for (int q = 0; q < 40; q++) {
                        double ang = hache(q, 1, 114) * Math.PI * 2, v = 8 + 10 * hache(q, 2, 114);
                        Vec3 p = cen.add(Math.cos(ang) * v * t, (hache(q, 3, 114) - 0.3) * 2 + v * t * 0.3, Math.sin(ang) * v * t);
                        feuille(c, m, p, 2 * PX, t * 10 + q, q % 4 != 0 ? FEUILLE : FEUILLE_B, a);
                    }
                }
            }
        }
        // le piqué : des traits de vent au-dessus de lui pendant qu'il tombe
        if (e.pique >= 0 && secondes(e.pique) < 2 && e.eclat < 0) {
            for (int q = 0; q < 8; q++) {
                Vec3 de = pieds.add((q - 3.5) * 3 * PX * 1.4, 2.2, (hache(q, 1, 141) - 0.5) * 10 * PX * 1.4);
                ruban(c, m, ligne(de, de.add(0, 3.5 + 2 * hache(q, 2, 141), 0), 8), 2 * PX * 1.4, 0.35F, null, q % 2 == 1 ? 0.3 * PX : 0);
            }
        }
        // la tempête : une tornade couchée sort de la hallebarde, dans le sens de son regard
        if (e.tempete >= 0) {
            double t = secondes(e.tempete);
            float a = e.tempeteFin < 0 ? (float) lisse(t / 0.25) : (float) (1 - secondes(e.tempeteFin) / 0.4);
            if (a > 0 && t < Vent3.DUREE_TEMPETE / 20.0 + 1) {
                Vec3 regard = j.getViewVector(partiel);
                // elle sort de la pointe de la hallebarde, tenue à deux mains devant lui, à sa droite
                Vec3 droite = new Vec3(-regard.z, 0, regard.x).normalize();
                Vec3 bout = j.getEyePosition(partiel).subtract(cam).add(regard.scale(1.6)).add(droite.scale(0.45)).add(0, -0.35, 0);
                double l = Vent3.LONGUEUR_TEMPETE * lisse(t / 0.35);
                tornade(c, m, bout, regard, l, 0.3, 3.5, 22, 131, 4.5, t * 10, 0.3F * a, 0.35, 0.03, 16, 1.4 * PX);
            }
        }
        // la tornade royale : une vraie tempête. La tornade géante, deux de plus autour qui tournent dans l'autre sens, les
        // tranches qui tournent dedans, des nuages qui tournent au-dessus et autour, et des traits de vent qui tourbillonnent
        if (e.ulti >= 0 && e.centreUlti != null) {
            double t = secondes(e.ulti);
            Vec3 cen = e.centreUlti.subtract(cam);
            float fin = e.ultiFin >= 0 ? (float) (1 - secondes(e.ultiFin) / 0.5) : 1;
            float a = (float) (lisse(t / 0.6) * (e.explose >= 0 ? 1 - lisse(secondes(e.explose) / 0.35) : 1)) * Math.max(0, fin);
            double souffle = e.explose >= 0 ? secondes(e.explose) : 0;
            float nuages = (float) (lisse(t / 1.5) * (e.explose >= 0 ? 1 - lisse(souffle / 1.8) : 1)) * Math.max(0, fin);
            if (nuages > 0) {
                nuages(c, m, cen, t, souffle, nuages);
            }
            if (a > 0) {
                tornade(c, m, cen, HAUT, 66 * lisse(t / 0.8), 4, 30, 80, 151, 3.4, t * 4, 0.3F * a, 0.12, 0.16, 80, 0.4);
                tornade(c, m, cen, HAUT, 58 * lisse(t / 1.2), 16, 44, 30, 152, 1.4, -t * 2, 0.14F * a, 0.1, 0, 0, 0.4);
                for (int q = 0; q < 24; q++) {
                    double h = 4 + 52 * hache(q, 2, 153), ang = hache(q, 1, 153) * Math.PI * 2 + t * (1.5 + hache(q, 4, 153)), r = Mth.lerp(h / 66, 4, 30) * 0.8;
                    Vec3 p = cen.add(Math.cos(ang) * r, h, Math.sin(ang) * r);
                    tranche(c, m, p, new Vec3(-Math.sin(ang), 0.1, Math.cos(ang)), 3.4, hache(q, 3, 153) * 2, a, 2, 5);
                }
                // les traits de vent qui tourbillonnent tout autour (comme une pluie couchée par la tempête)
                for (int q = 0; q < 70; q++) {
                    double rr = 10 + 55 * hache(q, 1, 154), h = 1 + 52 * hache(q, 2, 154), ang = hache(q, 3, 154) * Math.PI * 2 + t * (2.4 - rr / 40);
                    Vec3 p = cen.add(Math.cos(ang) * rr, h, Math.sin(ang) * rr), tg = new Vec3(-Math.sin(ang), -0.15, Math.cos(ang));
                    ruban(c, m, ligne(p, p.add(tg.scale(7 + 6 * hache(q, 4, 154))), 6), 0.22, 0.35F * a, null, q % 3 == 0 ? 0.05 : 0);
                }
            }
            if (e.explose >= 0) {
                double tx = souffle;
                float b = (float) (1 - lisse((tx - 0.6) / 1.4));
                if (b > 0) {
                    double r = 52 * (0.2 + 0.8 * lisse(tx / 0.4));
                    double flash = 1 - lisse(tx / 0.4);
                    if (flash > 0) {
                        boule(c, m, cen.add(0, 22, 0), 6 + 22 * (1 - flash), BLANC, (float) flash);
                    }
                    sphere(c, m, cen.add(0, 12, 0), r * 0.85, b, 30, 161, temps);
                    sphere(c, m, cen.add(0, 12, 0), r * 1.2 * lisse(Math.max(0, tx - 0.12) / 0.45), b * 0.4F, 14, 163, -temps);
                    anneau(c, m, cen.add(0, 0.1, 0), r, 5, 0.5F * b, 44, 0.1, 0.18);
                    anneau(c, m, cen.add(0, 0.2, 0), r * 0.55, 2.8, 0.4F * b, 0, 0, 0.12);
                    for (int q = 0; q < 40; q++) {
                        double ang = hache(q, 1, 162) * Math.PI * 2, rr = 4 + 30 * hache(q, 2, 162) + tx * 24, h = 8 + 36 * hache(q, 3, 162) - tx * 16;
                        tranche(c, m, cen.add(Math.cos(ang) * rr, Math.max(0.5, h), Math.sin(ang) * rr), new Vec3(Math.cos(ang) * 0.4, -1, Math.sin(ang) * 0.4),
                                3, hache(q, 4, 162) * 3, b, 3, 6);
                    }
                }
            }
        }
    }

    /**
     * Les nuages de la tempête : de gros nuages gris qui tournent en spirale au-dessus de la tornade (plus sombres au milieu,
     * plus vite près du centre), et un anneau de poussière qui tourne au ras du sol. À l'explosion, ils sont soufflés au loin.
     */
    private static void nuages(VertexConsumer c, Matrix4f m, Vec3 cen, double t, double souffle, float alpha) {
        if (passe != 0) {
            return;
        }
        double pousse = 1 + 1.6 * lisse(souffle / 1.4);
        for (int q = 0; q < 220; q++) {
            boolean bas = q >= 160;
            double u = hache(q, 1, 171), bras = q % 5;
            double r = (bas ? 30 + 34 * u : 8 + 88 * u) * pousse;
            double ang = bras * Math.PI * 2 / 5 + u * 3.6 + t * (bas ? 0.7 : 0.9 / (0.45 + u));
            double h = bas ? 1 + 6 * hache(q, 2, 171) : 52 + 12 * hache(q, 2, 171) - 9 * u + 4 * souffle;
            double taille = (bas ? 4 + 5 * hache(q, 3, 171) : 7 + 8 * hache(q, 3, 171)) * (0.6 + 0.4 * lisse(t / 1.2));
            float gris = (float) (bas ? 0.62 + 0.15 * hache(q, 4, 171) : 0.3 + 0.45 * u + 0.1 * hache(q, 4, 171));
            float[] col = {gris * 0.92F, gris, gris * 0.97F};
            Vec3 p = cen.add(Math.cos(ang) * r, h, Math.sin(ang) * r);
            nuage(c, m, p, taille, taille * (bas ? 0.45 : 0.38), taille * 0.8, ang, col, alpha * (bas ? 0.4F : 0.55F));
        }
    }

    /** Un nuage : une boîte large et plate, tournée, chaque face un peu plus ou moins claire. */
    private static void nuage(VertexConsumer c, Matrix4f m, Vec3 o, double sx, double sy, double sz, double angle, float[] col, float alpha) {
        Vec3 x = new Vec3(Math.cos(angle), 0, Math.sin(angle)).scale(sx / 2), z = new Vec3(-Math.sin(angle), 0, Math.cos(angle)).scale(sz / 2), y = new Vec3(0, sy / 2, 0);
        float[] ombres = {1F, 0.62F, 0.85F, 0.8F, 0.9F, 0.75F};
        Vec3[][] faces = {{y, x, z}, {y.scale(-1), x, z}, {x, y, z}, {x.scale(-1), y, z}, {z, x, y}, {z.scale(-1), x, y}};
        for (int f = 0; f < 6; f++) {
            Vec3 cen = o.add(faces[f][0]), u = faces[f][1], v = faces[f][2];
            float[] cc = {col[0] * ombres[f], col[1] * ombres[f], col[2] * ombres[f]};
            quad(c, m, cen.subtract(u).subtract(v), cen.add(u).subtract(v), cen.add(u).add(v), cen.subtract(u).add(v), cc, alpha);
        }
    }

    /** Une boule de lumière : un voile plein (passe 0), et de la lumière qui s'ajoute (passe 1), plus forte au milieu. */
    private static void boule(VertexConsumer c, Matrix4f m, Vec3 centre, double r, float[] col, float alpha) {
        if (alpha <= 0.01F || r <= 0.01) {
            return;
        }
        int nl = 10, nm = 18;
        for (int i = 0; i < nl; i++) {
            for (int j = 0; j < nm; j++) {
                double t0 = Math.PI * i / nl, t1 = Math.PI * (i + 1) / nl, p0 = Math.PI * 2 * j / nm, p1 = Math.PI * 2 * (j + 1) / nm;
                Vec3 mil = point(centre, r, (t0 + t1) / 2, (p0 + p1) / 2);
                double v = mil.lengthSqr() < 1e-6 ? 1 : Math.abs(mil.subtract(centre).normalize().dot(mil.normalize()));
                float a = alpha * (float) (passe == 0 ? 0.25 * v : 0.5 * v * v);
                quad(c, m, point(centre, r, t0, p0), point(centre, r, t0, p1), point(centre, r, t1, p1), point(centre, r, t1, p0), passe == 0 ? BLANC : col, a);
            }
        }
    }

    /** Une colonne de lumière qui monte du sol (pleine en bas, rien en haut). */
    private static void colonne(VertexConsumer c, Matrix4f m, Vec3 pied, double r, double h, float alpha) {
        if (alpha <= 0.01F) {
            return;
        }
        float[] col = passe == 0 ? VERT_C : LUEUR;
        float a = alpha * (passe == 0 ? 0.25F : 0.6F);
        for (int k = 0; k < 16; k++) {
            double a0 = k * Math.PI * 2 / 16, a1 = (k + 1) * Math.PI * 2 / 16;
            Vec3 b0 = pied.add(Math.cos(a0) * r, 0, Math.sin(a0) * r), b1 = pied.add(Math.cos(a1) * r, 0, Math.sin(a1) * r);
            sommet(c, m, b0, col, a);
            sommet(c, m, b1, col, a);
            sommet(c, m, b1.add(0, h, 0), col, 0);
            sommet(c, m, b0.add(0, h, 0), col, 0);
        }
    }

    // ================================================================ l'écran : le panneau, la barre du clic tenu

    private static void ecran(GuiGraphics g) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.options.hideGui) {
            return;
        }
        Etat e = ETATS.get(mc.player.getUUID());
        if (e == null || e.fin >= 0) {
            return;
        }
        panneau(g, mc.font, e);
        if (tenu && e.niveau >= 2 && !tenuParti) {
            barre(g, mc.font, (float) Mth.clamp(secondes(tenuDebut) / (Vent.TENU / 20.0), 0, 1), e.niveau >= 3 ? "TEMPÊTE" : "FAUCON");
        }
    }

    private static String reste(long pret) {
        double s = (pret - System.nanoTime()) / 1e9;
        return s > 0 ? "  " + (int) Math.ceil(s) + " s" : "";
    }

    /** Le panneau, avec les touches de ce joueur (voir Clavier). */
    private static void panneau(GuiGraphics g, Font font, Etat e) {
        int n = e.niveau;
        boolean deux = Vent.NOM2.equals(e.pouvoir), trois = Vent.NOM3.equals(e.pouvoir);
        String eveil = n == 1 && (deux || trois) ? "Niveau 2" : n == 2 && trois ? "Niveau 3" : "Rendormir";
        String tenuNom = Clavier.droite() + " tenu";
        List<String[]> lignes = new ArrayList<>();
        List<Boolean> gris = new ArrayList<>();
        long now = System.nanoTime();
        if (n == 1) {
            lignes.add(new String[]{Clavier.gauche(), "Souffle"});
            lignes.add(new String[]{Clavier.droite(), "Tranches" + reste(pretDroit)});
            lignes.add(new String[]{Clavier.accroupi(), "Tourbillon" + reste(pretAccroupi)});
            gris.add(false);
            gris.add(pretDroit > now);
            gris.add(pretAccroupi > now);
        } else if (n == 2) {
            lignes.add(new String[]{Clavier.gauche(), "Souffle"});
            lignes.add(new String[]{Clavier.droite(), "Tranches" + reste(pretDroit)});
            lignes.add(new String[]{tenuNom, "Faucon" + reste(pretTenu)});
            lignes.add(new String[]{Clavier.accroupi(), "Répulsion céleste" + reste(pretAccroupi)});
            lignes.add(new String[]{Clavier.saut(), "Sauts  " + sautsReste});
            gris.add(false);
            gris.add(pretDroit > now);
            gris.add(pretTenu > now);
            gris.add(pretAccroupi > now);
            gris.add(sautsReste == 0);
        } else {
            lignes.add(new String[]{Clavier.gauche(), "Lame de vent"});
            lignes.add(new String[]{Clavier.droite(), "Lances" + reste(pretDroit)});
            lignes.add(new String[]{tenuNom, "Tempête" + reste(pretTenu)});
            lignes.add(new String[]{Clavier.accroupi(), "Répulsion" + reste(pretAccroupi)});
            lignes.add(new String[]{Clavier.saut() + " ×2", "Voler"});
            lignes.add(new String[]{Clavier.nom(fr.cabane.client.hachibi4.ClientHachibi4.ULTI), "Tornade royale"});
            gris.add(false);
            gris.add(pretDroit > now);
            gris.add(pretTenu > now);
            gris.add(pretAccroupi > now);
            gris.add(false);
            gris.add(false);
        }
        lignes.add(new String[]{Clavier.nom(ClientSusano.EVEIL), eveil});
        gris.add(false);
        String titre = "Vent" + (n >= 2 ? "  ·  niv. " + n : "");
        int teinte = 0xFF7DF0A0;
        boolean[] g2 = new boolean[gris.size()];
        for (int i = 0; i < g2.length; i++) {
            g2[i] = gris.get(i);
        }
        PanneauPouvoir.dessiner(g, font, titre, teinte, lignes.toArray(new String[0][]), g2);
    }

    /** La barre du clic tenu, au-dessus de la barre d'objets : pleine, le faucon (ou la tempête) part. */
    private static void barre(GuiGraphics g, Font font, float charge, String titre) {
        int w = 120, h = 7;
        int x = g.guiWidth() / 2 - w / 2, y = g.guiHeight() - 68;
        g.drawString(font, titre, g.guiWidth() / 2 - font.width(titre) / 2, y - 11, 0xFF7DF0A0, true);
        g.fill(x - 1, y - 1, x + w + 1, y + h + 1, 0xC0000000);
        g.fillGradient(x, y, x + Math.round(w * charge), y + h, 0xFF4FD88A, 0xFFC8FFD8);
    }

    // ================================================================ les touches (pour moi)

    private static long pretDroit, pretAccroupi, pretTenu;
    private static boolean tenu, tenuParti;
    private static long tenuDebut;
    private static boolean accroupiAvant, sautAvant, solAvant = true;
    private static int sautsReste = Vent.SAUTS_EN_L_AIR;
    private static long derniereLame;
    private static long secousse = -1;
    private static float forceSecousse;

    private static long dans(int ticks) {
        return System.nanoTime() + ticks * 50_000_000L;
    }

    private static void envoyer(int action) {
        PacketDistributor.sendToServer(new Vent.Touche(action));
    }

    static void secouer(float force) {
        secousse = System.nanoTime();
        forceSecousse = Math.max(forceSecousse, force);
    }

    /** Le clic droit lâché avant 1 s : les tranches (niveaux 1 et 2) ou les lances (niveau 3). */
    private static void clicCourt(Minecraft mc, Etat moi) {
        if (System.nanoTime() < pretDroit) {
            return;
        }
        pretDroit = dans(moi.niveau >= 3 ? Vent3.RECHARGE_LANCES : Vent.RECHARGE_TRANCHES);
        mc.player.swing(InteractionHand.MAIN_HAND);
        envoyer(moi.niveau >= 3 ? Vent.LANCES : Vent.TRANCHES);
    }

    @EventBusSubscriber(modid = Cabane.MODID, value = Dist.CLIENT)
    public static final class Couches {
        @SubscribeEvent
        public static void enregistrer(RegisterGuiLayersEvent event) {
            event.registerAboveAll(Cabane.id("vent"), (g, delta) -> ecran(g));
        }

        @SubscribeEvent
        public static void couches(EntityRenderersEvent.AddLayers event) {
            for (PlayerSkin.Model modele : event.getSkins()) {
                if (event.getSkin(modele) instanceof PlayerRenderer rendu) {
                    rendu.addLayer(new ClientTenueVent.Couche(rendu));
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
                ETATS.clear();
                PORTEURS.clear();
                TRANCHES.clear();
                FAUCONS.clear();
                INCLINAISONS.clear();
                LAMES.clear();
                ANNEAUX.clear();
                TOURBILLONS.clear();
                LANCES.clear();
                tenu = false;
                return;
            }
            Etat moi = ETATS.get(mc.player.getUUID());
            boolean eveille = moi != null && moi.fin < 0 && moi.niveau > 0;
            // au niveau 3, la hallebarde reste dans sa main : la barre d'objets se cale dessus
            if (eveille && moi.niveau >= 3 && !tientHallebarde(mc.player)) {
                for (int i = 0; i < 9; i++) {
                    if (mc.player.getInventory().getItem(i).is(Vent.hallebarde())) {
                        mc.player.getInventory().selected = i;
                        break;
                    }
                }
            }
            // le clic droit tenu : lâché avant 1 s, le coup court ; tenu 1 s, le faucon (niveau 2) ou la tempête (niveau 3)
            if (tenu) {
                if (!eveille) {
                    tenu = false;
                } else if (!mc.options.keyUse.isDown()) {
                    tenu = false;
                    if (!tenuParti) {
                        clicCourt(mc, moi);
                    }
                } else if (!tenuParti && secondes(tenuDebut) >= Vent.TENU / 20.0) {
                    tenuParti = true;
                    if (System.nanoTime() >= pretTenu) {
                        pretTenu = dans(moi.niveau >= 3 ? Vent3.RECHARGE_TEMPETE : Vent.RECHARGE_FAUCON);
                        mc.player.swing(InteractionHand.MAIN_HAND);
                        envoyer(moi.niveau >= 3 ? Vent.TEMPETE : Vent.FAUCON);
                    }
                }
            }
            // s'accroupir (l'appui) : le tourbillon, la répulsion céleste (en grand au niveau 3)
            boolean accroupi = mc.options.keyShift.isDown();
            if (eveille && accroupi && !accroupiAvant && mc.screen == null && System.nanoTime() >= pretAccroupi) {
                pretAccroupi = dans(moi.niveau >= 2 ? Vent.RECHARGE_REPULSION : Vent.RECHARGE_TOURBILLON);
                envoyer(Vent.ACCROUPI);
            }
            accroupiAvant = accroupi;
            // niveau 2 : sauter en l'air (3 fois), un anneau de vent sous les pieds
            boolean saut = mc.options.keyJump.isDown();
            boolean sol = mc.player.onGround();
            if (sol) {
                sautsReste = Vent.SAUTS_EN_L_AIR;
            }
            if (eveille && moi.niveau == 2 && saut && !sautAvant && !sol && !solAvant && sautsReste > 0 && mc.screen == null
                    && !mc.player.getAbilities().flying && !mc.player.isInWater() && !mc.player.onClimbable()) {
                sautsReste--;
                Vec3 v = mc.player.getDeltaMovement();
                mc.player.setDeltaMovement(v.x * 1.2, 0.85, v.z * 1.2);
                mc.player.fallDistance = 0;
                envoyer(Vent.SAUT);
            }
            sautAvant = saut;
            solAvant = sol;
            gerbes(mc);
            // les paillettes : autour de la fée (niveau 1), autour de lui (niveau 2), des éclats verts (niveau 3)
            for (Map.Entry<UUID, Etat> en : ETATS.entrySet()) {
                Player j = mc.level.getPlayerByUUID(en.getKey());
                Etat e = en.getValue();
                if (j == null || j.isInvisible() || e.fin >= 0 || j.distanceTo(mc.player) > 48) {
                    continue;
                }
                if (e.niveau == 1 && !PORTEURS.contains(en.getKey())) {
                    Vec3[] f = fee(j, e, 1);
                    if (f != null && HASARD.nextInt(2) == 0) {
                        mc.level.addParticle(paillette(HASARD.nextInt(3)), f[0].x + HASARD.nextGaussian() * 0.12, f[0].y + HASARD.nextGaussian() * 0.12,
                                f[0].z + HASARD.nextGaussian() * 0.12, 0, -0.02, 0);
                    }
                } else if (HASARD.nextInt(e.niveau >= 3 ? 2 : 4) == 0) {
                    mc.level.addParticle(paillette(HASARD.nextInt(3)), j.getX() + HASARD.nextGaussian() * 0.7, j.getY() + HASARD.nextDouble() * 2.2,
                            j.getZ() + HASARD.nextGaussian() * 0.7, 0, 0.01, 0);
                }
            }
            // les paillettes des fées qui tournent
            for (UUID qui : avecFee()) {
                Player j = mc.level.getPlayerByUUID(qui);
                Vec3[] f = j == null || j.isInvisible() || j.distanceTo(mc.player) > 48 ? null : fee(j, ETATS.get(qui), 1);
                if (f != null && HASARD.nextInt(2) == 0) {
                    mc.level.addParticle(paillette(HASARD.nextInt(3)), f[0].x + HASARD.nextGaussian() * 0.12, f[0].y + HASARD.nextGaussian() * 0.12,
                            f[0].z + HASARD.nextGaussian() * 0.12, 0, -0.02, 0);
                }
            }
            // ce qui est fini s'en va
            ETATS.values().removeIf(e -> e.fin >= 0 && secondes(e.fin) > 1.6);
            for (Etat e : ETATS.values()) {
                if (e.ulti >= 0 && (secondes(e.ulti) > 12 || e.ultiFin >= 0 && secondes(e.ultiFin) > 0.6)) {
                    e.ulti = -1;
                }
                if (e.tempete >= 0 && e.tempeteFin >= 0 && secondes(e.tempeteFin) > 0.5) {
                    e.tempete = -1;
                }
            }
            TRANCHES.removeIf(t -> secondes(t.debut()) > Vent.PORTEE_TRANCHES / Vent.VITESSE_TRANCHES / 20 + 0.3);
            FAUCONS.values().removeIf(f -> f.fin >= 0 && secondes(f.fin) > 0.45 || secondes(f.recu) > 3);
            LAMES.removeIf(l -> secondes(l.debut()) > 0.45);
            ANNEAUX.removeIf(a -> secondes(a.debut()) > 0.5);
            TOURBILLONS.values().removeIf(t -> t.fin >= 0 && secondes(t.fin) > 0.45);
        }

        /**
         * Éveillé, le clic droit est au vent (le coup court, ou tenu 1 s) ; au niveau 3, chaque coup de hallebarde lance
         * aussi la lame de vent (le coup lui-même reste celui de l'arme).
         */
        @SubscribeEvent
        public static void clic(InputEvent.InteractionKeyMappingTriggered event) {
            Minecraft mc = Minecraft.getInstance();
            Etat moi = mc.player == null ? null : ETATS.get(mc.player.getUUID());
            if (moi == null || moi.fin >= 0 || moi.niveau == 0) {
                return;
            }
            if (moi.niveau >= 3 && !tientHallebarde(mc.player)) {
                return;
            }
            if (event.isAttack()) {
                if (moi.niveau >= 3 && System.nanoTime() - derniereLame > Vent3.RECHARGE_LAME * 50_000_000L
                        && (mc.hitResult == null || mc.hitResult.getType() != HitResult.Type.BLOCK)) {
                    derniereLame = System.nanoTime();
                    envoyer(Vent.LAME);
                }
                return;
            }
            if (!event.isUseItem()) {
                return;
            }
            event.setCanceled(true);
            event.setSwingHand(false);
            if (moi.niveau == 1) {
                clicCourt(mc, moi);
                return;
            }
            if (!tenu) {
                tenu = true;
                tenuParti = false;
                tenuDebut = System.nanoTime();
            }
        }

        /** Bras en T, pendant que le vent se ramasse, et pendant la tornade royale : il ne bouge pas. */
        @SubscribeEvent
        public static void immobile(MovementInputUpdateEvent event) {
            Minecraft mc = Minecraft.getInstance();
            Etat e = mc.player == null ? null : ETATS.get(mc.player.getUUID());
            if (e == null || !(e.ramasse >= 0 && e.eclat < 0 && secondes(e.ramasse) < Vent.RAMASSE / 20.0 + 0.2 || e.ulti >= 0 && e.ultiFin < 0 && secondes(e.ulti) < Vent3.ULTI_FIN / 20.0)) {
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

        /** La répulsion qui éclate tout près, la tornade royale qui explose : l'écran tremble. */
        @SubscribeEvent
        public static void tremblement(net.neoforged.neoforge.client.event.ViewportEvent.ComputeCameraAngles event) {
            if (secousse < 0) {
                return;
            }
            double apres = secondes(secousse);
            if (apres > 0.5) {
                secousse = -1;
                forceSecousse = 0;
                return;
            }
            float force = forceSecousse * (float) (1 - apres / 0.5);
            event.setRoll(event.getRoll() + (HASARD.nextFloat() - 0.5F) * 4 * force);
            event.setPitch(event.getPitch() + (HASARD.nextFloat() - 0.5F) * 2.5F * force);
        }

        @SubscribeEvent
        public static void monde(RenderLevelStageEvent event) {
            Minecraft mc = Minecraft.getInstance();
            if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES || mc.level == null
                    || ETATS.isEmpty() && PORTEURS.isEmpty() && TRANCHES.isEmpty() && FAUCONS.isEmpty() && LAMES.isEmpty() && ANNEAUX.isEmpty() && TOURBILLONS.isEmpty() && LANCES.isEmpty()) {
                return;
            }
            dessiner(event.getPoseStack(), event.getCamera().getPosition(), event.getPartialTick().getGameTimeDeltaPartialTick(false));
        }

        private Evenements() {
        }
    }

    // ================================================================ en vol, la hallebarde en main, les gerbes des transformations

    /** En vol (niveau 3), il se couche en avant comme un super-héros : l'inclinaison de chacun (en radians) et quand elle a bougé. */
    private static final Map<UUID, float[]> INCLINAISONS = new HashMap<>();
    private static final float VOL_MAX = 1.3F;

    /** Ce joueur vole-t-il vite (niveau 3, transformation finie) ? */
    private static boolean enVol(Player j) {
        if (ClientMarque3.vole(j.getUUID())) {
            boolean enLair = j == Minecraft.getInstance().player ? j.getAbilities().flying : !j.onGround();
            return enLair && Math.hypot(j.getX() - j.xo, j.getZ() - j.zo) > 0.12;
        }
        Etat e = ETATS.get(j.getUUID());
        if (e == null || e.fin >= 0 || e.niveau < 3 || e.debut3 >= 0 && secondes(e.debut3) < FIN3 || e.ulti >= 0 && e.ultiFin < 0 && secondes(e.ulti) < 7) {
            return false;
        }
        boolean enLair = j == Minecraft.getInstance().player ? j.getAbilities().flying : !j.onGround();
        return enLair && Math.hypot(j.getX() - j.xo, j.getZ() - j.zo) > 0.22;
    }

    /** L'inclinaison de ce joueur, lissée (elle va vers 1,3 rad en vol rapide, vers 0 sinon). */
    private static float inclinaison(Player j) {
        float[] v = INCLINAISONS.computeIfAbsent(j.getUUID(), k -> new float[]{0, System.nanoTime()});
        double dt = Math.min(0.1, (System.nanoTime() - (long) v[1]) / 1e9);
        if (dt < 0) {
            dt = 0;
        }
        v[1] = System.nanoTime();
        // la marque 3 se couche tout à plat ; le vent, comme un super-héros
        float but = enVol(j) ? ClientMarque3.vole(j.getUUID()) ? 1.5F : VOL_MAX : 0;
        v[0] += (float) ((but - v[0]) * Math.min(1, dt * 5));
        return v[0];
    }

    private static final java.util.Set<Player> INCLINES = new java.util.HashSet<>();

    /** Il tient la hallebarde en main (un vrai objet, que le serveur lui donne au niveau 3). */
    static boolean tientHallebarde(Player p) {
        return p.getMainHandItem().is(Vent.hallebarde());
    }

    /**
     * La hallebarde de vent dessinée comme un objet : les mêmes faces que sur les planches (ModeleVent « hallebarde_seule »),
     * qui brillent. Tenue à la troisième personne, le manche passe dans le poing, droit le long du bras ; à la première
     * personne, elle se dresse devant lui, un peu penchée.
     */
    static final class RenduHallebarde extends BlockEntityWithoutLevelRenderer {
        RenduHallebarde() {
            super(Minecraft.getInstance().getBlockEntityRenderDispatcher(), Minecraft.getInstance().getEntityModels());
        }

        @Override
        public void renderByItem(ItemStack stack, ItemDisplayContext ctx, PoseStack ps, MultiBufferSource buffers, int lumiere, int recouvre) {
            ModeleVent m = ModeleVent.premier("hallebarde_seule");
            if (m == null) {
                return;
            }
            ps.pushPose();
            // comme une épée : dans son carré d'objet, le manche en bas à gauche, la pointe en haut à droite ; les réglages de
            // tenue sont ceux d'une épée (models/item/hallebarde_vent.json) : en main, elle se tient et frappe comme une épée
            float e;
            if (ctx == ItemDisplayContext.GUI) {
                ps.translate(0.5F, 0.5F, 0.5F);
                ps.mulPose(Axis.ZP.rotationDegrees(-45));
                e = 0.024F;
                ps.translate(0, -12 * e, 0);
            } else {
                ps.translate(0.3F, 0.3F, 0.5F);
                ps.mulPose(Axis.ZP.rotationDegrees(-45));
                e = 0.034F;
            }
            m.dessiner(buffers, ps.last(), LightTexture.FULL_BRIGHT, e, 1);
            ps.popPose();
        }
    }

    @EventBusSubscriber(modid = Cabane.MODID, value = Dist.CLIENT)
    public static final class Objets {
        @SubscribeEvent
        public static void extensions(RegisterClientExtensionsEvent event) {
            event.registerItem(new IClientItemExtensions() {
                private RenduHallebarde rendu;

                @Override
                public BlockEntityWithoutLevelRenderer getCustomRenderer() {
                    if (rendu == null) {
                        rendu = new RenduHallebarde();
                    }
                    return rendu;
                }
            }, Vent.hallebarde());
        }

        /** Vu de l'extérieur : en vol, il se couche en avant comme un super-héros. */
        @SubscribeEvent(priority = EventPriority.LOWEST)
        public static void avant(RenderPlayerEvent.Pre event) {
            Player p = event.getEntity();
            if (!ETATS.containsKey(p.getUUID()) && !ClientMarque3.vole(p.getUUID()) && !INCLINAISONS.containsKey(p.getUUID())) {
                return;
            }
            float incl = inclinaison(p);
            if (incl > 0.01F) {
                PoseStack ps = event.getPoseStack();
                ps.pushPose();
                INCLINES.add(p);
                float lacet = Mth.lerp(event.getPartialTick(), p.yBodyRotO, p.yBodyRot) * Mth.DEG_TO_RAD;
                ps.translate(0, 0.9F, 0);
                ps.mulPose(new Quaternionf().rotateAxis(-incl, -Mth.cos(lacet), 0, -Mth.sin(lacet)));
                ps.translate(0, -0.9F, 0);
            }
        }

        @SubscribeEvent
        public static void apres(RenderPlayerEvent.Post event) {
            if (INCLINES.remove(event.getEntity())) {
                event.getPoseStack().popPose();
            }
        }

        /** La tornade royale assombrit le ciel autour d'elle (le brouillard tourne au gris-vert de la tempête). */
        @SubscribeEvent
        public static void brouillard(ViewportEvent.ComputeFogColor event) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null) {
                return;
            }
            float k = 0;
            for (Etat e : ETATS.values()) {
                if (e.ulti < 0 || e.centreUlti == null) {
                    continue;
                }
                double t = secondes(e.ulti), d = mc.player.position().distanceTo(e.centreUlti);
                double fin = e.explose >= 0 ? 1 - lisse((secondes(e.explose) - 0.5) / 2) : e.ultiFin >= 0 ? 0 : 1;
                k = Math.max(k, (float) (lisse(t / 1.5) * fin * Mth.clamp(1 - (d - 60) / 90, 0, 1)));
            }
            if (k > 0) {
                k *= 0.7F;
                event.setRed(Mth.lerp(k, event.getRed(), 0.26F));
                event.setGreen(Mth.lerp(k, event.getGreen(), 0.33F));
                event.setBlue(Mth.lerp(k, event.getBlue(), 0.31F));
            }
        }

        private Objets() {
        }
    }

    /**
     * Les gerbes de particules des transformations (une fois chacune) et ce qui coule pendant qu'elles durent ; les nuages
     * qui tourbillonnent pendant la tornade royale ; l'écran tremble tout près.
     */
    private static void gerbes(Minecraft mc) {
        for (Map.Entry<UUID, Etat> en : ETATS.entrySet()) {
            Player j = mc.level.getPlayerByUUID(en.getKey());
            Etat e = en.getValue();
            if (j == null || e.fin >= 0) {
                continue;
            }
            boolean pres = j.distanceTo(mc.player) < 16;
            double t1 = e.debut1 < 0 ? 99 : secondes(e.debut1), t2 = e.debut2 < 0 ? 99 : secondes(e.debut2), t3 = e.debut3 < 0 ? 99 : secondes(e.debut3);
            Vec3 c = j.position().add(0, 1, 0);
            if (t1 < 1.2) {
                for (int i = 0; i < 3; i++) {
                    double a = HASARD.nextDouble() * Math.PI * 2;
                    mc.level.addParticle(ParticleTypes.COMPOSTER, j.getX() + Math.cos(a) * 0.9, j.getY() + HASARD.nextDouble() * 2.2, j.getZ() + Math.sin(a) * 0.9,
                            -Math.sin(a) * 0.15, 0.08, Math.cos(a) * 0.15);
                }
                if (t1 > 0.4 && !e.gerbes[0]) {
                    e.gerbes[0] = true;
                    for (int i = 0; i < 30; i++) {
                        mc.level.addParticle(paillette(i), c.x + HASARD.nextGaussian() * 0.4, c.y + 0.5 + HASARD.nextGaussian() * 0.3, c.z + HASARD.nextGaussian() * 0.4,
                                HASARD.nextGaussian() * 0.05, 0.05, HASARD.nextGaussian() * 0.05);
                    }
                    mc.level.addParticle(ParticleTypes.GUST, c.x, c.y, c.z, 0, 0, 0);
                }
            }
            if (t2 < 2.2) {
                if (t2 > 0.5 && t2 < 1.2) {
                    for (int i = 0; i < 4; i++) {
                        Vec3 d = new Vec3(HASARD.nextGaussian(), HASARD.nextGaussian() * 0.6, HASARD.nextGaussian()).normalize().scale(3);
                        mc.level.addParticle(paillette(i), c.x + d.x, c.y + 0.25 + d.y, c.z + d.z, -d.x * 0.08, -d.y * 0.08, -d.z * 0.08);
                    }
                }
                if (t2 > 1.15 && !e.gerbes[1]) {
                    e.gerbes[1] = true;
                    mc.level.addParticle(ParticleTypes.GUST_EMITTER_SMALL, c.x, c.y, c.z, 0, 0, 0);
                    for (int i = 0; i < 60; i++) {
                        Vec3 d = new Vec3(HASARD.nextGaussian(), HASARD.nextGaussian() * 0.7, HASARD.nextGaussian()).normalize();
                        mc.level.addParticle(i % 2 == 0 ? ParticleTypes.END_ROD : paillette(i), c.x, c.y + 0.25, c.z, d.x * 0.5, d.y * 0.5, d.z * 0.5);
                    }
                    mc.level.playLocalSound(c.x, c.y, c.z, SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.PLAYERS, 2F, 0.8F, false);
                    mc.level.playLocalSound(c.x, c.y, c.z, SoundEvents.WIND_CHARGE_BURST.value(), SoundSource.PLAYERS, 1.5F, 0.8F, false);
                    if (pres) {
                        secouer(0.7F);
                    }
                }
            }
            if (t3 < FIN3) {
                if (t3 < ECLAT3) {
                    for (int i = 0; i < 6; i++) {
                        double a = HASARD.nextDouble() * Math.PI * 2, r = 0.6 + HASARD.nextDouble() * 2.2;
                        mc.level.addParticle(i % 3 == 0 ? ParticleTypes.COMPOSTER : paillette(i), j.getX() + Math.cos(a) * r, j.getY() - 1 + HASARD.nextDouble() * 5,
                                j.getZ() + Math.sin(a) * r, -Math.sin(a) * 0.3, 0.12, Math.cos(a) * 0.3);
                    }
                }
                if (t3 > 0.05 && !e.gerbes[2]) {
                    e.gerbes[2] = true;
                    mc.level.addParticle(ParticleTypes.GUST_EMITTER_SMALL, j.getX(), j.getY(), j.getZ(), 0, 0, 0);
                    mc.level.playLocalSound(c.x, c.y, c.z, SoundEvents.BEACON_ACTIVATE, SoundSource.PLAYERS, 1.5F, 1.4F, false);
                }
                if (t3 > ECLAT3 && !e.gerbes[3]) {
                    e.gerbes[3] = true;
                    mc.level.addParticle(ParticleTypes.GUST_EMITTER_LARGE, c.x, c.y, c.z, 0, 0, 0);
                    for (int i = 0; i < 90; i++) {
                        Vec3 d = new Vec3(HASARD.nextGaussian(), HASARD.nextGaussian() * 0.5, HASARD.nextGaussian()).normalize();
                        mc.level.addParticle(i % 3 == 0 ? ParticleTypes.END_ROD : i % 3 == 1 ? ParticleTypes.COMPOSTER : paillette(i), c.x, c.y, c.z,
                                d.x * 0.8, d.y * 0.6, d.z * 0.8);
                    }
                    mc.level.playLocalSound(c.x, c.y, c.z, SoundEvents.WIND_CHARGE_BURST.value(), SoundSource.PLAYERS, 3F, 0.6F, false);
                    mc.level.playLocalSound(c.x, c.y, c.z, SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.PLAYERS, 2F, 1.3F, false);
                    if (pres) {
                        secouer(1.2F);
                    }
                }
            }
            // la tornade royale : des nuages de poussière qui tourbillonnent
            if (e.ulti >= 0 && e.centreUlti != null && e.explose < 0 && e.ultiFin < 0 && secondes(e.ulti) < 6) {
                Vec3 o = e.centreUlti;
                for (int i = 0; i < 8; i++) {
                    double a = HASARD.nextDouble() * Math.PI * 2, r = 6 + HASARD.nextDouble() * 44, h = HASARD.nextDouble() * 56;
                    mc.level.addParticle(i % 4 == 0 ? ParticleTypes.COMPOSTER : ParticleTypes.CLOUD, o.x + Math.cos(a) * r, o.y + h, o.z + Math.sin(a) * r,
                            -Math.sin(a) * 0.8, 0.1, Math.cos(a) * 0.8);
                }
            }
        }
    }

    private ClientVent() {
    }
}
