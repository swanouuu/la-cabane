package fr.cabane.client.pouvoir;

import static fr.cabane.client.pouvoir.phenix.Effets.*;
import static fr.cabane.client.pouvoir.phenix.Feu.*;

import com.mojang.blaze3d.vertex.PoseStack;
import fr.cabane.Cabane;
import fr.cabane.client.pouvoir.phenix.Feu;
import fr.cabane.client.pouvoir.phenix.Oiseau;
import fr.cabane.client.pouvoir.phenix.Plumage;
import fr.cabane.pouvoir.Phenix;
import fr.cabane.pouvoir.Pouvoirs;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import net.minecraft.client.resources.PlayerSkin;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.levelgen.Heightmap;
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
import net.neoforged.neoforge.client.event.RenderPlayerEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Ce qu'on voit du phénix (voir {@link Phenix}), comme dans l'aperçu docs/phenix/apercu : son skin reste visible, une
 * aura fine bleue et orange l'entoure, quelques petites flammes, les avant-bras qui brûlent ; les ailes de flammes et les
 * serres (niveau 2) ; les grandes ailes, la crête, les yeux dorés, la queue (niveau 3), et en l'air le phénix complet.
 * Les coups, les transformations, le cocon, l'ulti et la renaissance sont dessinés avec le moteur de
 * {@link fr.cabane.client.pouvoir.phenix.Feu} (des rubans de flammes et des lueurs).
 */
public final class ClientPhenix {
    private static final RandomSource HASARD = RandomSource.create();
    /** Des effets qui ne viennent que du client : la transformation à l'éveil, l'éclat quand il devient oiseau. */
    private static final int E_TRANSFO = 100, E_ECLAT = 101;

    static double secondes(long depuis) {
        return (System.nanoTime() - depuis) / 1e9;
    }

    private static Vec3 vec(Vector3f v) {
        return new Vec3(v.x(), v.y(), v.z());
    }

    // ================================================================ qui est éveillé

    static final class Etat {
        String pouvoir;
        int niveau;
        /** Quand le niveau courant a commencé (sa transformation), quand il s'est endormi. */
        long debut = -1, fin = -1;
        int niveauFin;
        /** Le cocon : quand, combien de temps. */
        long cocon = -1;
        double coconDuree;
        /** Le coup d'aile : quand (les ailes se ramènent devant). */
        long aile = -1;
        /** Jusqu'à quand il est l'oiseau (le piqué, le vol bleu), et s'il l'est à cette image. */
        long oiseauJusqua;
        boolean oiseau;
    }

    private static final Map<UUID, Etat> ETATS = new HashMap<>();
    /** Depuis combien de ticks chaque joueur est en l'air. */
    private static final Map<UUID, Integer> AIR = new HashMap<>();

    public static boolean eveille(UUID qui) {
        Etat e = ETATS.get(qui);
        return e != null && e.fin < 0 && e.niveau > 0;
    }

    public static void recevoir(Pouvoirs.Etat p) {
        Minecraft mc = Minecraft.getInstance();
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
        if (p.niveau() != e.niveau) {
            // chaque niveau a sa transformation (si on la voit naître ; sinon il est déjà transformé)
            e.debut = p.annonce() ? System.nanoTime() : System.nanoTime() - 60_000_000_000L;
            if (p.annonce() && mc.level != null && mc.level.getPlayerByUUID(p.joueur()) instanceof Player j) {
                ANIMS.add(new Anim(p.joueur(), E_TRANSFO, p.niveau(), j.position(), Vec3.ZERO, 0, System.nanoTime()));
            }
        }
        e.niveau = p.niveau();
        if (p.annonce() && mc.player != null && p.joueur().equals(mc.player.getUUID()) && p.niveau() >= 3) {
            ultiFaite = false;
            volReste = Phenix.VOL_MAX;
        }
        if (p.annonce() && mc.level != null && mc.level.getPlayerByUUID(p.joueur()) instanceof Player j) {
            mc.level.playLocalSound(j.getX(), j.getY(), j.getZ(), SoundEvents.FIRECHARGE_USE, SoundSource.PLAYERS, 1F, 0.7F + 0.2F * p.niveau(), false);
        }
    }

    public static void oublier(UUID joueur) {
        ETATS.remove(joueur);
    }

    /** Le niveau du joueur local (0 : endormi, ou pas phénix). */
    public static int niveauLocal() {
        Etat e = moi();
        return e == null ? 0 : e.niveau;
    }

    // ================================================================ les coups (ce que le serveur annonce)

    private record Anim(UUID qui, int sorte, int phase, Vec3 pos, Vec3 dir, int n, long debut) {
        float age() {
            return (float) secondes(debut);
        }
    }

    private static final List<Anim> ANIMS = new ArrayList<>();
    /** Les plumes de l'éventail : leur numéro → l'annonce (départ, vitesse par tick, durée en ticks). */
    private static final Map<Integer, Anim> PLUMES_VOL = new HashMap<>();

    /** Le grand phénix de chaque joueur : sa position d'avant, la dernière reçue, sa direction, quand. */
    private static final class Grand {
        Vec3 avant, pos, dir;
        long recu = System.nanoTime();
        final long debut = System.nanoTime();
    }

    private static final Map<UUID, Grand> GRANDS = new HashMap<>();

    /** La renaissance du joueur local : son ancienne caméra (rendue à la fin), et quand elle sera de nouveau prête. */
    private static CameraType cameraAvant;
    private static long renaissanceMoi = -1, renaissancePrete;

    public static void effet(Phenix.Effet p) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return;
        }
        Vec3 pos = vec(p.pos()), dir = vec(p.dir());
        Anim a = new Anim(p.joueur(), p.sorte(), p.phase(), pos, dir, p.n(), System.nanoTime());
        boolean moi = mc.player != null && p.joueur().equals(mc.player.getUUID());
        Etat e = ETATS.get(p.joueur());
        switch (p.sorte()) {
            case Phenix.E_PLUME -> PLUMES_VOL.put(p.n(), a);
            case Phenix.E_PLUME_FIN -> PLUMES_VOL.remove(p.n());
            case Phenix.E_GRAND -> {
                if (p.phase() == 2) {
                    GRANDS.remove(p.joueur());
                    ANIMS.add(a);
                } else {
                    Grand g = GRANDS.computeIfAbsent(p.joueur(), k -> new Grand());
                    g.avant = g.pos == null ? pos : g.pos;
                    g.pos = pos;
                    g.dir = dir;
                    g.recu = System.nanoTime();
                }
            }
            case Phenix.E_COCON -> {
                if (e != null) {
                    e.cocon = System.nanoTime();
                    e.coconDuree = p.n() / 20.0;
                }
                ANIMS.add(a);
            }
            case Phenix.E_AILE -> {
                if (e != null) {
                    e.aile = System.nanoTime();
                }
                ANIMS.add(a);
            }
            case Phenix.E_PIQUE -> {
                if (e != null) {
                    e.oiseauJusqua = p.phase() == 2 ? System.nanoTime() : System.nanoTime() + 3_000_000_000L;
                }
                ANIMS.add(a);
            }
            case Phenix.E_VOL_BLEU -> {
                if (e != null && p.phase() == 0) {
                    e.oiseauJusqua = System.nanoTime() + 500_000_000L;
                }
                ANIMS.add(a);
            }
            case Phenix.E_ENVOL -> {
                if (moi && p.phase() == 1) {
                    // il a décollé : plus de battements, il plane
                    battements = 3;
                }
                ANIMS.add(a);
            }
            case Phenix.E_RENAISSANCE -> {
                ANIMS.add(a);
                if (moi && p.phase() == 0) {
                    renaissancePrete = System.nanoTime() + Phenix.RECHARGE_RENAISSANCE * 50_000_000L;
                    renaissanceMoi = System.nanoTime();
                    cameraAvant = mc.options.getCameraType();
                    mc.options.setCameraType(CameraType.THIRD_PERSON_BACK);
                }
                if (moi && p.phase() == 1) {
                    finirCamera();
                }
            }
            default -> ANIMS.add(a);
        }
    }

    private static void finirCamera() {
        Minecraft mc = Minecraft.getInstance();
        if (renaissanceMoi >= 0 && cameraAvant != null) {
            mc.options.setCameraType(cameraAvant);
        }
        renaissanceMoi = -1;
        cameraAvant = null;
    }

    // ================================================================ sur le joueur

    /** Ce qu'il porte à cette image : les intensités pendant la transformation, les ailes (au sol, en l'air, le cocon…). */
    private static Plumage.Tenue tenue(Etat e, int niv, float efface, Player joueur) {
        float t = (float) secondes(e.debut);
        Plumage.Tenue o = new Plumage.Tenue();
        o.niv = niv;
        o.k = efface;
        if (niv == 1) {
            o.k = efface * elastique(seg(t, 0, 0.7F));
        } else if (niv == 2) {
            o.kEpaules = efface * seg(t, 0, 0.4F);
            o.kAiles = efface * seg(t, 0.15F, 0.9F);
            o.kSerres = efface * seg(t, 0.8F, 1.3F);
        } else {
            o.kCorps = efface * elastique(seg(t, 0, 0.8F));
            o.kCrete = efface * seg(t, 0.3F, 1);
            o.kQueue = efface * seg(t, 0.2F, 1.1F);
            o.kAiles = efface;
        }
        double vx = joueur.getX() - joueur.xo, vy = joueur.getY() - joueur.yo, vz = joueur.getZ() - joueur.zo;
        double v = Math.sqrt(vx * vx + vy * vy + vz * vz);
        boolean air = !joueur.onGround() && !joueur.isInWater();
        if (air && v > 0.15) {
            o.vent = new Vector3f((float) -vx, (float) -vy, (float) -vz).normalize().mul((float) Math.min(1.3, v * 3));
        }
        if (niv >= 2) {
            float h = Feu.T + joueur.getId() * 0.37F;
            Oiseau.Aile a = new Oiseau.Aile();
            a.plume(niv == 2 ? 0.3F + 0.7F * lisse(seg(t, 0.15F, 0.9F)) : 1);
            if (niv >= 3) {
                a.taille(entre(0.74F, 1.12F, elastique(seg(t, 0, 0.8F))) + 0.0001F);
            }
            double ct = e.cocon >= 0 ? secondes(e.cocon) : 99, at = e.aile >= 0 ? secondes(e.aile) : 99;
            if (ct < e.coconDuree + 0.6) {
                // le cocon : elles s'enroulent devant lui, puis les pétales du cocon prennent la place ; elles se relèvent à l'ouverture
                float c = (float) ct;
                a.ouv(0.8F).enr(c < e.coconDuree ? lisse(c / 0.4F) : 0);
                if (c > 0.45F && c < e.coconDuree + 0.1F) {
                    o.kAiles = 0;
                } else if (c >= e.coconDuree) {
                    a.ouv(1).bat(0.35F * Math.abs((float) Math.sin((c - e.coconDuree) * 6)));
                }
            } else if (at < 1.2) {
                // le coup d'aile : armées loin derrière, ramenées devant d'un coup
                float c = (float) at;
                float av = c < 0.15F ? -0.9F * lisse(c / 0.15F) : c < 0.35F ? entre(-0.9F, 1, sortie((c - 0.15F) / 0.2F)) : 1 - seg(c, 0.5F, 1.2F);
                a.ouv(1).avance(av).bat(0.25F * (1 - seg(c, 0.3F, 0.6F)));
            } else if (air) {
                a.ouv(1).bat((float) Math.sin(h * 6) * 0.45F);
            } else {
                a.ouv(0.22F).bat((float) Math.sin(h * 1.8) * 0.04F);
            }
            o.ailes = a;
        }
        return o;
    }

    public static final class Couche extends RenderLayer<AbstractClientPlayer, PlayerModel<AbstractClientPlayer>> {
        public Couche(RenderLayerParent<AbstractClientPlayer, PlayerModel<AbstractClientPlayer>> parent) {
            super(parent);
        }

        @Override
        public void render(PoseStack ps, MultiBufferSource buffers, int lumiere, AbstractClientPlayer joueur, float marche, float vitesse,
                float partiel, float age, float tete, float tangage) {
            Etat e = ETATS.get(joueur.getUUID());
            if (e == null || joueur.isInvisible() || !Feu.pret()) {
                return;
            }
            float efface = e.fin >= 0 ? (float) Math.max(0, 1 - secondes(e.fin) / 0.6) : 1;
            int niv = e.fin >= 0 ? e.niveauFin : e.niveau;
            if (efface <= 0 || niv <= 0) {
                return;
            }
            Plumage.dessiner(getParentModel(), ps, joueur.getSkin().model() == PlayerSkin.Model.SLIM, tenue(e, niv, efface, joueur));
        }
    }

    // ================================================================ dans le monde

    private static Vec3 cam = Vec3.ZERO;

    private static Matrix4f cadre(Vec3 p, float lacet) {
        return Feu.monde(p.x, p.y, p.z, cam.x, cam.y, cam.z, lacet);
    }

    private static float lacet(Vec3 d) {
        return (float) Math.atan2(d.x, d.z);
    }

    /** Un repère en pixels en a, son +z vers b. */
    private static Matrix4f cadreVers(Vec3 a, Vec3 b) {
        Vec3 d = b.subtract(a).normalize();
        return new Matrix4f().translate((float) (a.x - cam.x), (float) (a.y - cam.y), (float) (a.z - cam.z))
                .rotate(new Quaternionf().rotationTo(0, 0, 1, (float) d.x, (float) d.y, (float) d.z)).scale(1 / 16F);
    }

    private static Vec3 suivre(Anim a, float partiel) {
        Player j = Minecraft.getInstance().level.getPlayerByUUID(a.qui());
        return j == null ? a.pos() : j.getPosition(partiel);
    }

    private static float lacetJoueur(Anim a) {
        Player j = Minecraft.getInstance().level.getPlayerByUUID(a.qui());
        return j == null ? 0 : -j.getYRot() * Mth.DEG_TO_RAD;
    }

    /** La hauteur du sol sous p, en pixels par rapport à p (négative s'il est en l'air). */
    private static float sol(Vec3 p) {
        int h = Minecraft.getInstance().level.getHeight(Heightmap.Types.MOTION_BLOCKING, Mth.floor(p.x), Mth.floor(p.z));
        return (float) Math.max(-400, Math.min(0, (h - p.y) * 16));
    }

    private static float fondu(float t, float duree, float bord) {
        return Math.min(lisse(t / bord), 1 - lisse((t - duree + bord) / bord));
    }

    private static void monde(float partiel) {
        Matrix4f avant = repere();
        for (Anim a : ANIMS) {
            dessiner(a, a.age(), partiel);
        }
        // les plumes de l'éventail
        for (Anim a : PLUMES_VOL.values()) {
            double ticks = Math.min(a.phase(), a.age() * 20);
            Vec3 p = a.pos().add(a.dir().scale(ticks));
            repere(cadre(p, 0));
            plume(0, 0, 0, new Vector3f((float) a.dir().x, (float) a.dir().y, (float) a.dir().z), 1, a.n() * 0.13F, 1.1F);
        }
        // le grand phénix : il glisse entre les deux dernières positions reçues (toutes les 2 ticks)
        for (Grand g : GRANDS.values()) {
            if (g.pos == null) {
                continue;
            }
            double k = Mth.clamp(secondes(g.recu) / 0.1, 0, 1);
            Vec3 p = g.avant.lerp(g.pos, k);
            Vec3 d = g.dir.normalize();
            repere(new Matrix4f().translate((float) (p.x - cam.x), (float) (p.y - cam.y), (float) (p.z - cam.z)).rotateY(lacet(d))
                    .rotateX((float) -Math.asin(Mth.clamp(d.y, -1, 1))).scale(1 / 16F));
            float age = (float) secondes(g.debut);
            Oiseau.oiseau(0.25F + 0.45F * lisse(age / 0.6F), (float) Math.sin(T * 9) * 0.6F, 1, 1, 0, 1);
            lueur(0, 0, 0, 90, CYAN, 0.25F);
        }
        repere(avant);
    }

    private static void dessiner(Anim a, float t, float partiel) {
        Vec3 c = a.pos();
        switch (a.sorte()) {
            case Phenix.E_JET -> jet(a, t);
            case Phenix.E_REGEN -> {
                if (t > 2.6F) {
                    return;
                }
                repere(cadre(suivre(a, partiel), 0));
                float k = seg(t, 0, 0.3F) * (1 - seg(t, 1.6F, 2.2F));
                spirale(0, 0, 0, 13, 34, 3, k, 4, 0);
                lueur(0, 16, 0, 70, CYAN, 0.2F * k);
                soins(0, 0, 0, t - 0.2F, 2.2F, 16);
                onde(0, 0, 0, 8 + 40 * sortie(seg(t, 0, 0.6F)), 1 - seg(t, 0, 0.7F), 6);
            }
            case Phenix.E_SERRE -> serre(a, t, partiel);
            case Phenix.E_PLUIE -> pluie(a, t);
            case Phenix.E_COUP -> {
                if (t > 0.4F) {
                    return;
                }
                repere(cadre(c, lacet(a.dir())));
                eclat(0, 0, 0, t, 0.3F, 0.7F, CYAN);
                for (int i = 0; i < 6; i++) {
                    float an = i / 6F * TOUR;
                    langue(0, 0, 0, v((float) Math.cos(an), 0.5F, (float) Math.sin(an)).normalize(), 12 * (1 - t / 0.4F), 4, i * 0.1F, 1 - t / 0.4F,
                            i % 3 == 0 ? 2 : 0.3F, 0.15F);
                }
            }
            case Phenix.E_CROIX -> {
                if (t > 0.45F) {
                    return;
                }
                repere(cadre(c, lacet(a.dir())));
                float k = 1 - t / 0.45F, g = Math.min(1, t / 0.1F);
                ruban((s, o) -> o.set(-14 + 28 * s * g, 14 - 28 * s * g, 0), s -> 5 * (float) Math.sin(Math.PI * (0.1 + 0.8 * s)), 0.2F, k * 1.2F, 1, 4, 1.5F, 0.1F);
                ruban((s, o) -> o.set(14 - 28 * s * g, 14 - 28 * s * g, 0), s -> 5 * (float) Math.sin(Math.PI * (0.1 + 0.8 * s)), 0.6F, k * 1.2F, 0.4F, 4, 1.5F, 0.1F);
                eclat(0, 0, 0, t, 0.4F, 1.4F, OR);
            }
            case Phenix.E_GRAND -> {
                if (t > 1.4F) {
                    return;
                }
                repere(cadre(c, 0));
                explosion(0, 0, 0, t, 1.2F, 80, sol(c));
                eclat(0, 0, 0, t, 0.5F, 3, CYAN);
                rayons(0, 0, 0, 160, cloche(t / 1.2F), 14, 2);
            }
            case Phenix.E_COCON -> cocon(a, t, partiel);
            case Phenix.E_PIQUE -> {
                if (a.phase() != 2 || t > 1.4F) {
                    return;
                }
                repere(cadre(c, 0));
                explosion(0, 8, 0, t, 1.2F, 64, 0);
                eclat(0, 14, 0, t, 0.5F, 3, CYAN);
                rayons(0, 10, 0, 200, cloche(t), 12, 4);
            }
            case Phenix.E_VOL_BLEU -> {
                if (a.phase() != 1 || t > 1.2F) {
                    return;
                }
                Vec3 dep = a.dir().add(0, 0.9, 0), fin = c.add(0, 0.9, 0);
                float L = (float) dep.distanceTo(fin) * 16;
                if (L < 4) {
                    return;
                }
                float k = 1 - t / 1.2F;
                repere(cadreVers(dep, fin));
                ruban((s, o) -> o.set(0, 0, L * s), s -> 16 * k * (0.4F + 0.6F * s), 0.2F, k, 0.2F, 4, L / 40, 0.2F);
                ruban((s, o) -> o.set(0, 0, L * s), s -> 7 * k, 0.7F, k, 2, 4, L / 30, 0.2F);
                eclat(0, 0, L, t, 0.4F, 1.6F, CYAN);
            }
            case Phenix.E_BRASIER -> {
                float D = a.n() / 20F;
                if (t > D + 0.3F) {
                    return;
                }
                repere(cadre(c, 0));
                onde(0, 0, 0, 70 * sortie(seg(t, 0, 0.8F)), cloche(seg(t, 0, 1.1F)), 14);
                braises(0, 0, 0, 70, 60, 40, seg(t, 0.2F, 0.8F) * (1 - seg(t, D - 0.5F, D)), 5);
                lueur(0, 4, 0, 160, CYAN, 0.12F * fondu(t, D, 0.4F));
                if (t < D) {
                    soins(0, 0, 0, t % 1.25F, 1.2F, 6);
                }
            }
            case Phenix.E_TOURBILLON -> tourbillon(a, t, partiel);
            case Phenix.E_ULTI -> {
                if (a.phase() == 0) {
                    ulti(a, t);
                }
            }
            case Phenix.E_RENAISSANCE -> {
                if (a.phase() == 0) {
                    renaissance(a, t + 0.6F);
                }
            }
            case Phenix.E_REPAS -> {
                float D = a.n() / 20F;
                if (t > D) {
                    return;
                }
                repere(cadre(suivre(a, partiel), 0));
                float u = t / D;
                spirale(0, 0, 0, 16 * (1 - u * 0.4F), 36, 3, cloche(u), 5, 0);
                braises(0, 0, 0, 14, 36, 16, cloche(u), 1);
            }
            case Phenix.E_BATTEMENT -> {
                if (t > 0.5F) {
                    return;
                }
                repere(cadre(c, 0));
                onde(0, -6, 0, 10 + 30 * seg(t, 0, 0.4F), 1 - seg(t, 0, 0.45F), 4);
                lueur(0, 10, 0, 40, CYAN, 0.3F * (1 - t / 0.5F));
            }
            case Phenix.E_AILE -> coupAile(a, t);
            case Phenix.E_ENVOL -> {
                if (a.phase() == 0) {
                    if (t > 0.45F) {
                        return;
                    }
                    repere(cadre(suivre(a, partiel), 0));
                    spirale(0, 0, 0, 12, 28, 3, seg(t, 0, 0.15F) * (1 - seg(t, 0.3F, 0.4F)), -6, 1);
                    lueur(0, 14, 0, 60 * (1 - seg(t, 0, 0.35F)) + 6, CYAN, 0.25F * seg(t, 0, 0.35F));
                } else {
                    if (t > 1.3F) {
                        return;
                    }
                    repere(cadre(c, 0));
                    pilier(0, 0, 0, 22, 150, seg(t, 0, 0.15F) * (1 - seg(t, 0.6F, 1.2F)));
                    eclat(0, 10, 0, t, 0.5F, 2, CYAN);
                    onde(0, 0, 0, 10 + 50 * sortie(seg(t, 0, 0.6F)), 1 - seg(t, 0, 0.7F), 12);
                }
            }
            case E_TRANSFO -> transformation(a, t, partiel);
            case E_ECLAT -> {
                if (t > 0.5F) {
                    return;
                }
                repere(cadre(c, 0));
                eclat(0, 0, 0, t, 0.4F, 1.6F, CYAN);
            }
            default -> { }
        }
    }

    /** Le jet (niveau 1) : des bouffées de flammes qui partent de ses mains jusqu'à la cible. */
    private static void jet(Anim a, float t) {
        Vec3 dep = a.pos(), fin = a.dir();
        float L = (float) dep.distanceTo(fin) * 16;
        if (t > 0.9F || L < 1) {
            return;
        }
        repere(cadreVers(dep, fin));
        for (int j = 0; j < 16; j++) {
            float age = t - j * 0.022F;
            if (age < 0 || age > 0.38F) {
                continue;
            }
            float u = age / 0.38F, z = L * u * 1.05F, gx = (hache(j, 1) - 0.5F) * 16 * u, gy = (hache(j, 2) - 0.5F) * 10 * u + 4 * u * u;
            Vector3f d = v(gx * 0.02F, 0.05F, 1).normalize();
            float len = 8 + 18 * u;
            ruban((s, o) -> o.set(gx - d.x * len * s, gy - d.y * len * s + s * s * 3, z - d.z * len * s),
                    s -> (4 + 12 * u) * (float) Math.sin(Math.PI * (0.15 + 0.85 * s)), j * 0.17F, (1 - u) * 1.1F, u > 0.5F ? 0.4F : 0, 3, 1.2F, 0.6F);
            if (j % 3 == 0) {
                lueur(gx, gy, z, 16 + 18 * u, CYAN, 0.25F * (1 - u));
            }
        }
        if (t < 0.4F) {
            lueur(0, 0, 0, 18, BLANC, 0.6F * (1 - t / 0.4F));
            lueur(0, 0, 0, 7, OR, 0.8F * (1 - t / 0.4F));
        }
        eclat(0, 0, L, t - 0.12F, 0.4F, 1.2F, CYAN);
    }

    /** Le coup de serre (niveau 2) : la traînée de la ruée, puis trois griffures dorées. */
    private static void serre(Anim a, float t, float partiel) {
        if (a.phase() == 0) {
            if (t > 0.45F) {
                return;
            }
            repere(cadre(suivre(a, partiel), lacet(a.dir())));
            float k = 1 - t / 0.45F;
            for (int i = 0; i < 3; i++) {
                float x = (i - 1) * 4;
                ruban((s, o) -> o.set(x, 6 - s * 2, -s * 40), s -> 6 * (1 - s), i * 0.3F, 0.8F * k, 0, 3, 2, 0.8F);
            }
            return;
        }
        if (t > 0.8F) {
            return;
        }
        repere(cadre(a.pos(), lacet(a.dir())));
        for (int i = 0; i < 3; i++) {
            float age = t - i * 0.05F;
            if (age < 0 || age > 0.55F) {
                continue;
            }
            float k = 1 - age / 0.55F, x = (i - 1) * 7;
            ruban((s, o) -> o.set(x + (float) Math.sin(s * 2.4 - 1.2) * 6, 18 - s * 30, -6 + (float) Math.cos(s * 2.4 - 1.2) * 8),
                    s -> 4.5F * (float) Math.sin(Math.PI * s), i * 0.2F, k * 1.2F, 1, 4, 1.5F, 0.1F);
        }
        eclat(0, 0, 0, t, 0.45F, 1.8F, OR);
    }

    /** La pluie de plumes (niveau 2) : 12 plumes lancées vers le ciel, puis une pluie sur la zone. */
    private static void pluie(Anim a, float t) {
        float D = a.n() / 20F;
        if (t > D + 1) {
            return;
        }
        Vec3 c = a.pos();
        repere(cadre(c, 0));
        onde(0, 0, 0, 64, 0.3F * fondu(t, D + 0.6F, 0.3F), 5);
        Vec3 rel = a.dir().subtract(c).scale(16);
        float[] dep = {(float) rel.x, (float) rel.y + 26, (float) rel.z};
        for (int i = 0; i < 12; i++) {
            float t0 = i * 0.02F, t1 = 0.75F + hache(i, 1) * 0.9F, u = seg(t, t0, t1);
            float[] fin = {(hache(i, 2) - 0.5F) * 120, 0, (hache(i, 3) - 0.5F) * 110};
            float[] som = {entre(dep[0], fin[0], 0.4F), 170 + 30 * hache(i, 4), entre(dep[2], fin[2], 0.4F)};
            if (u > 0 && u < 1) {
                float[] p = bez(dep, som, fin, u), q = bez(dep, som, fin, Math.min(1, u + 0.02F));
                plume(p[0], p[1], p[2], v(q[0] - p[0], q[1] - p[1], q[2] - p[2]), 1, i * 0.13F, 0.9F);
            }
            explosion(fin[0], 6, fin[2], t - t1, 0.5F, 18, 0);
        }
        for (int i = 0; i < 18; i++) {
            float t0 = 0.9F + hache(i, 7) * Math.max(0.1F, D - 1.0F), u = seg(t, t0, t0 + 0.5F);
            float fx = (hache(i, 8) - 0.5F) * 120, fz = (hache(i, 9) - 0.5F) * 110;
            if (u > 0 && u < 1) {
                plume(fx + 10 * (1 - u), 200 * (1 - u), fz, v(-0.1F, -1, 0), 1, i * 0.21F, 0.9F);
            }
            explosion(fx, 6, fz, t - t0 - 0.5F, 0.45F, 14, 0);
        }
    }

    private static float[] bez(float[] a, float[] b, float[] c, float u) {
        float[] r = new float[3];
        for (int i = 0; i < 3; i++) {
            r[i] = entre(entre(a[i], b[i], u), entre(b[i], c[i], u), u);
        }
        return r;
    }

    /** Le coup d'aile (niveau 2) : un croissant de flammes bleues qui avance de 8 blocs. */
    private static void coupAile(Anim a, float t) {
        if (t > 1.1F) {
            return;
        }
        repere(cadre(a.pos(), lacet(a.dir())));
        float u = seg(t, 0.15F, 0.95F), zc = 20 + 128 * sortie(u), k = u > 0 ? cloche(Math.min(1, u * 1.15F + 0.08F)) : 0;
        if (u <= 0 || u >= 1) {
            return;
        }
        for (int i = 0; i < 20; i++) {
            float an = (i / 19F - 0.5F) * 2.1F, r = 52;
            langue((float) Math.sin(an) * r, 2, zc + ((float) Math.cos(an) - 1) * r * 0.55F, v(0, 1, -0.9F).normalize(), 22 * k * (0.8F + 0.4F * hache(i, 1)), 9,
                    i * 0.07F, k, Math.abs(an) > 0.8F ? 0.8F : 0.2F, 0.22F, 3, 1);
        }
        ruban((s, o) -> {
            float an = (s - 0.5F) * 2.1F;
            o.set((float) Math.sin(an) * 52, 8, zc + ((float) Math.cos(an) - 1) * 52 * 0.55F);
        }, s -> 14 * k, 0.4F, k, 0.3F, 2, 3, 0, v(0, 0.6F, -1).normalize());
        lueur(0, 12, zc, 110, CYAN, 0.2F * k);
    }

    /** Le cocon (niveau 3) : un œuf de 2 × 12 plumes de feu qui bat comme un cœur, puis qui s'ouvre comme une fleur. */
    private static void cocon(Anim a, float t, float partiel) {
        float D = a.n() / 20F;
        if (t > D + 1.5F) {
            return;
        }
        repere(cadre(suivre(a, partiel), 0));
        float ouvre = seg(t, D, D + 0.6F);
        boolean dedans = t < D + 0.3F;
        float pousse = sortie(seg(t, 0.1F, 0.6F)), kc = seg(t, 0.1F, 0.35F) * (1 - seg(t, D + 0.4F, D + 0.9F));
        float coeur = 1 + 0.06F * (float) Math.sin(T * 7.5) + 0.04F * (float) Math.sin(T * 15);
        float beta = 1.75F * sortie(ouvre);
        float[][] couches = {{0, 24, 12, 1, 0}, {1, 27, 12, -1, 0.5F}};
        for (float[] cc : couches) {
            int couche = (int) cc[0], n = (int) cc[2];
            float R = cc[1], sens = cc[3], decal = cc[4];
            for (int i = 0; i < n; i++) {
                float ang = (i + decal) / n * TOUR + T * 0.5F * sens * (1 - ouvre), rr = R * coeur;
                float cx = (float) Math.cos(ang), cz = (float) Math.sin(ang), fin = 0.96F * pousse;
                int ii = i;
                ruban((s, o) -> {
                    float u = s * fin, rho = rr * (float) Math.sin(Math.PI * u), y = rr * 1.25F * (float) (1 - Math.cos(Math.PI * u));
                    float r2 = rho * (float) Math.cos(beta) + y * (float) Math.sin(beta), y2 = -rho * (float) Math.sin(beta) + y * (float) Math.cos(beta);
                    float flot = (float) Math.sin(T * 6 + ii + s * 5) * 0.8F;
                    o.set(cx * (r2 + 1.5F + flot), 1 + Math.max(-0.5F, y2), cz * (r2 + 1.5F + flot));
                }, s -> Math.max(5, 1.75F * (TOUR * rr * (float) Math.sin(Math.PI * s * fin)) / n) * (1 - 0.55F * s * s), couche * 0.5F + i * 0.083F,
                        kc * (couche == 1 ? 0.75F : 0.9F), couche == 1 ? 0.9F : 0.5F, 1.3F, 2.2F, 0.45F, v(-cz, 0, cx));
            }
        }
        float bat = 0.5F + 0.5F * (float) Math.pow(Math.max(0, Math.sin(T * 7.5)), 4);
        lueur(0, 22, 0, 70 * coeur, CYAN, 0.3F * kc);
        lueur(0, 20, 0, 30 + 22 * bat, BLANC, (0.25F + 0.35F * bat) * kc);
        lueur(0, 20, 0, 46, OR, 0.12F * kc * bat);
        if (dedans) {
            for (int j = 0; j < 3; j++) {
                int jj = j;
                ruban((s, o) -> {
                    float an = jj * 2.09F + T * 3 + s * 7;
                    o.set((float) Math.cos(an) * 10 * (1 - s * 0.5F), 2 + s * 50, (float) Math.sin(an) * 10 * (1 - s * 0.5F));
                }, s -> 3.5F * (float) Math.sin(Math.PI * s), j, kc * 0.8F, 2, 3, 2, 0.4F);
            }
        }
        soins(0, 0, 0, t - 0.5F, D, 20);
        for (int i = 0; i < 10; i++) {
            float x = T * 1.3F + hache(i, 1);
            int cyc = (int) Math.floor(x);
            float u = x - cyc, yy = 0.2F + 0.7F * hache(i, cyc, 2), an = hache(i, cyc, 3) * TOUR;
            float r = (float) Math.sqrt(Math.max(0, 1 - (yy * 2 - 1) * (yy * 2 - 1)));
            Vector3f d = v((float) Math.cos(an) * r, yy * 0.6F, (float) Math.sin(an) * r).normalize();
            if (dedans && t > 0.6F) {
                langue(d.x * 28, 1 + yy * 60, d.z * 28, new Vector3f(d).add(0, 1, 0).normalize(), 16 * cloche(u), 7, i * 0.2F + cyc * 0.1F, 1,
                        (i + cyc) % 3 == 0 ? 2 : 0.4F, 0.22F);
            }
        }
        eclat(0, 22, 0, t - D, 0.6F, 3, CYAN);
        onde(0, 0, 0, 20 + 110 * sortie(seg(t, D, D + 0.8F)), 1 - seg(t, D, D + 0.8F), 14);
        float v = seg(t, D + 0.05F, D + 1);
        if (v > 0 && v < 1) {
            for (int i = 0; i < 16; i++) {
                float an = i / 16F * TOUR + 0.2F;
                plume((float) Math.cos(an) * (24 + 150 * v), 30 + 30 * v - 40 * v * v, (float) Math.sin(an) * (24 + 150 * v), v((float) Math.cos(an), 0.5F - v,
                        (float) Math.sin(an)), 1 - v, i * 0.1F, 1);
            }
        }
    }

    /** Le tourbillon (niveau 3) : 24 plumes qui tournent autour de lui en montant, puis partent dans tous les sens. */
    private static void tourbillon(Anim a, float t, float partiel) {
        float D = a.n() / 20F;
        if (t > D + 1) {
            return;
        }
        repere(cadre(suivre(a, partiel), 0));
        float k = seg(t, 0, 0.4F);
        for (int i = 0; i < 24; i++) {
            int etage = i % 3;
            float a0 = i / 24F * TOUR;
            if (t < D) {
                float an = a0 + t * 4.5F + etage * 0.5F, r = 46 + etage * 6, y = 6 + etage * 14 + 6 * (float) Math.sin(T * 3 + i);
                plume((float) Math.cos(an) * r, y, (float) Math.sin(an) * r, v(-(float) Math.sin(an), 0.1F, (float) Math.cos(an)), k, i * 0.07F, 1);
            } else {
                float vv = seg(t, D, D + 0.9F), an = a0 + D * 4.5F + etage * 0.5F, r = 46 + etage * 6 + 200 * vv;
                if (vv < 1) {
                    plume((float) Math.cos(an) * r, 6 + etage * 14 + 20 * vv, (float) Math.sin(an) * r, v((float) Math.cos(an), 0.1F, (float) Math.sin(an)), 1 - vv,
                            i * 0.07F, 1);
                }
            }
        }
        lueur(0, 20, 0, 120, CYAN, 0.12F * k * (1 - seg(t, D, D + 0.5F)));
    }

    /** L'aube du phénix (l'ulti) : comme la scène « J » de l'aperçu, centrée là où il l'a lancée. */
    private static void ulti(Anim a, float t) {
        if (t > 11.2F) {
            return;
        }
        repere(cadre(a.pos(), lacet(a.dir())));
        float kp = seg(t, 0.4F, 0.8F) * (1 - seg(t, 2.6F, 3.4F));
        pilier(0, 0, 0, 30, 700, kp);
        spirale(0, 0, 0, 40, 600, 5, kp, 4, 7);
        onde(0, 0, 0, 50 + 20 * (float) Math.sin(T * 3), kp, 24);
        braises(0, 0, 0, 200, 300, 60, seg(t, 0.4F, 1) * (1 - seg(t, 9.5F, 11)), 9);
        // le phénix géant : il se forme dans la colonne, bat des ailes deux fois, monte et plonge
        float forme = elastique(seg(t, 1.6F, 3.0F)), kb = lisse(seg(t, 1.5F, 2.4F)) * (1 - seg(t, 6.45F, 6.6F));
        float bat = 0.55F + (float) Math.sin(T * 2) * 0.1F;
        if (t >= 3.4F && t < 3.8F) {
            bat = entre(0.85F, -0.75F, sortie(seg(t, 3.4F, 3.7F)));
        } else if (t >= 3.8F && t < 4.4F) {
            bat = entre(-0.75F, 0.85F, lisse(seg(t, 3.8F, 4.3F)));
        } else if (t >= 4.4F && t < 4.8F) {
            bat = entre(0.85F, -0.75F, sortie(seg(t, 4.4F, 4.7F)));
        } else if (t >= 4.8F && t < 5.4F) {
            bat = entre(-0.75F, 0.6F, lisse(seg(t, 4.8F, 5.4F)));
        }
        float monte = seg(t, 5.4F, 6.0F), plonge = seg(t, 6.0F, 6.6F);
        float[] pb = {0, 150, -120};
        float tang = -0.15F;
        if (t >= 5.4F && t < 6.0F) {
            pb = new float[]{0, entre(150, 520, sortie(monte)), entre(-120, -260, sortie(monte))};
            tang = entre(-0.15F, -0.9F, monte);
            bat = 0.9F * (float) Math.sin(monte * TOUR * 1.5);
        }
        if (t >= 6.0F) {
            float pp = plonge * plonge;
            pb = new float[]{0, entre(520, 30, pp), entre(-260, 0, pp)};
            tang = entre(-0.9F, 1.25F, Math.min(1, plonge * 2.5F));
            bat = -0.2F;
        }
        if (kb > 0.01F) {
            Matrix4f ici = repere();
            repere(new Matrix4f(ici).translate(pb[0], pb[1], pb[2]).rotateX(tang));
            Oiseau.oiseau(3.4F * (0.25F + 0.75F * forme), bat, kb, 1, 0, t >= 6.0F ? 1.6F : 1);
            repere(ici);
            lueur(pb[0], pb[1], pb[2], 600 * forme, BLEU, 0.22F * kb);
            rayons(pb[0], pb[1] + 40, pb[2] - 60, 520, cloche(seg(t, 2.2F, 5.4F)) * kb, 22, 3);
            if (t >= 6.0F && t < 6.6F) {
                float[] dep = {0, 520, -260}, ici2 = pb;
                ruban((s, o) -> {
                    float u = s * Math.min(1, plonge * 1.4F);
                    o.set(entre(ici2[0], dep[0], u), entre(ici2[1], dep[1], u), entre(ici2[2], dep[2], u));
                }, s -> 60 * (1 - s), 0.3F, 0.9F, 0.5F, 5, 6, 0.7F);
            }
        }
        // les deux battements : un mur de feu en anneau et une salve de plumes
        for (float[] vg : new float[][]{{3.55F, 0}, {4.55F, 1}}) {
            float tv = vg[0], n = vg[1], v = seg(t, tv, tv + 1.3F);
            if (v <= 0 || v >= 1) {
                continue;
            }
            onde(0, 0, 0, 30 + 290 * sortie(v), 1 - v * v, 48 * (1 - v * 0.6F));
            float u = seg(t, tv, tv + 0.9F);
            for (int i = 0; i < 28; i++) {
                float an = i / 28F * TOUR + n * 0.11F, rr = 60 + 300 * sortie(u), yy = 150 * (1 - sortie(u)) + 8;
                if (u < 1) {
                    plume((float) Math.cos(an) * rr, yy, -120 * (1 - u) + (float) Math.sin(an) * rr, v((float) Math.cos(an), -0.5F, (float) Math.sin(an)), 1,
                            i * 0.07F + n, 2.2F);
                }
                explosion((float) Math.cos(an) * 360, 6, (float) Math.sin(an) * 360, t - tv - 0.9F, 0.6F, 26, 0);
            }
        }
        // l'impact : le dôme, la colonne, trois ondes, les rayons, l'explosion
        float ti = 6.6F, d = seg(t, ti, ti + 1.0F), kd = t >= ti ? 1 - seg(t, ti + 1.4F, ti + 2.4F) : 0;
        if (kd > 0) {
            float R = 200 * sortie(d);
            for (int i = 0; i < 110; i++) {
                float yy = i / 109F, rr = (float) Math.sqrt(Math.max(0, 1 - yy * yy)), an = i * 2.39996F + T * 0.4F;
                Vector3f q = v((float) Math.cos(an) * rr, yy, (float) Math.sin(an) * rr);
                langue(q.x * R, q.y * R * 0.85F, q.z * R, new Vector3f(q).add(0, 1, 0).normalize(), 34 * kd, 22, i * 0.031F, kd * 0.85F,
                        i % 4 == 0 ? 2 : i % 3 == 0 ? 0.9F : 0.15F, 0.22F);
            }
            lueur(0, R * 0.4F, 0, R * 3, CYAN, 0.4F * kd);
            lueur(0, 20, 0, 160 * (1 - d) + 40, BLANC, kd);
            pilier(0, 0, 0, 50, 900 * sortie(d), kd);
            for (int k = 0; k < 3; k++) {
                onde(0, 0, 0, R * (0.6F + 0.4F * k) + 40 * k * d, kd * (1 - k * 0.25F), 30);
            }
            rayons(0, 40, 0, 700, kd, 26, 5);
        }
        explosion(0, 30, 0, t - ti, 1.4F, 160, 0);
        // la pluie de plumes dorées et bleues
        for (int i = 0; i < 70; i++) {
            float v = seg(t, 7.6F + hache(i, 1) * 1.2F, 10.8F);
            if (v > 0 && v < 1) {
                float an = hache(i, 2) * TOUR, rr = 20 + 300 * hache(i, 3);
                plume((float) Math.cos(an) * rr + (float) Math.sin(T * 2 + i) * 14, 380 * (1 - v), (float) Math.sin(an) * rr,
                        v((float) Math.sin(T + i) * 0.3F, -1, 0.2F), cloche(v), i * 0.05F, 1.1F);
            }
        }
    }

    /**
     * La renaissance (tp : le temps de la scène de l'aperçu, le coup mortel à 0,6 s) : il s'embrase, ses cendres, un cercle
     * d'or, l'œuf de flammes qui se fêle, le phénix qui jaillit, tourne et plonge, puis l'onde quand il renaît.
     */
    private static void renaissance(Anim a, float tp) {
        if (tp > 7.5F) {
            return;
        }
        repere(cadre(a.pos(), 0));
        pilier(0, 0, 0, 18, 60, cloche(seg(tp, 0.7F, 1.6F)));
        for (int i = 0; i < 40; i++) {
            float v = seg(tp, 1.1F + hache(i, 1) * 0.3F, 2.4F + hache(i, 2));
            if (v <= 0 || v >= 1 || tp > 3.6F) {
                continue;
            }
            float an = hache(i, 3) * TOUR, r = 4 + 18 * v * hache(i, 4);
            cube((float) Math.cos(an) * r, Math.max(0.5F, 22 * hache(i, 5) * (1 - v * 1.4F)), (float) Math.sin(an) * r, 1.2F * (1 - v * 0.5F), 0.3F);
        }
        float kc = seg(tp, 1.6F, 2.2F) * (1 - seg(tp, 5.2F, 5.8F));
        for (int i = 0; i < 48; i++) {
            float an = i / 48F * TOUR;
            lueur((float) Math.cos(an) * 34, 0.8F, (float) Math.sin(an) * 34, 8, OR, kc);
        }
        oeuf(0, 0, 0, 11 * lisse(seg(tp, 1.8F, 2.8F)), seg(tp, 1.8F, 2.3F) * (1 - seg(tp, 3.55F, 3.7F)), seg(tp, 3.0F, 3.6F));
        eclat(0, 16, 0, tp - 3.6F, 0.6F, 3, OR);
        explosion(0, 14, 0, tp - 3.6F, 0.9F, 40, 0);
        if (tp >= 3.6F && tp < 5.2F) {
            float u = seg(tp, 3.6F, 4.7F), an = u * TOUR - (float) Math.PI / 2;
            float[] p;
            float lac, tang, roul;
            if (tp < 4.7F) {
                float rr = 70 * sortie(Math.min(1, u * 3));
                p = new float[]{(float) Math.cos(an) * rr, 20 + 90 * (float) Math.sin(Math.min(1, u * 1.4F) * Math.PI / 2), (float) Math.sin(an) * rr};
                lac = (float) Math.atan2(-Math.sin(an), Math.cos(an));
                tang = -0.3F;
                roul = -0.4F;
            } else {
                float w = seg(tp, 4.7F, 5.2F);
                w *= w;
                p = new float[]{0, entre(110, 18, w), entre(-70, 0, w)};
                lac = 0;
                tang = 1;
                roul = 0;
            }
            Matrix4f ici = repere();
            repere(new Matrix4f(ici).translate(p[0], p[1], p[2]).rotateY(lac).rotateX(tang).rotateZ(roul));
            Oiseau.oiseau(1.3F, (float) Math.sin(T * 7) * 0.6F, 1, 1, 0, 1);
            repere(ici);
        }
        eclat(0, 18, 0, tp - 5.2F, 0.6F, 3, CYAN);
        onde(0, 0, 0, 20 + 80 * sortie(seg(tp, 5.2F, 6.0F)), 1 - seg(tp, 5.2F, 6.0F), 14);
    }

    /** La transformation à chaque éveil (niveau 1 : la spirale ; 2 : les ailes jaillissent ; 3 : la colonne et l'explosion). */
    private static void transformation(Anim a, float t, float partiel) {
        if (t > 1.6F) {
            return;
        }
        repere(cadre(suivre(a, partiel), lacetJoueur(a)));
        int niv = a.phase();
        if (niv <= 1) {
            spirale(0, 0, 0, 14, 34, 3, 1 - seg(t, 0, 0.5F), 5, 0);
            eclat(0, 18, 4, t, 0.6F, 1.5F, CYAN);
            onde(0, 0, 0, 12 + 80 * sortie(seg(t, 0, 0.7F)), 1 - seg(t, 0, 0.7F), 8);
        } else if (niv == 2) {
            for (int c = -1; c <= 1; c += 2) {
                lueur(c * 3, 21, -4, 30 * cloche(seg(t, 0, 0.6F)), CYAN, 0.6F);
            }
            eclat(0, 20, -8, t, 0.5F, 1.6F, CYAN);
            onde(0, 0, 0, 8 + 30 * sortie(seg(t, 0.8F, 1.3F)), (1 - seg(t, 0.8F, 1.4F)) * (t > 0.8F ? 1 : 0), 6);
        } else {
            pilier(0, 0, 0, 26, 120, 1 - seg(t, 0, 0.5F));
            eclat(0, 18, 0, t, 0.7F, 3, CYAN);
            explosion(0, 18, 0, t, 0.8F, 40, 0);
            onde(0, 0, 0, 20 + 160 * sortie(seg(t, 0, 1.1F)), 1 - seg(t, 0, 1.1F), 16);
            rayons(0, 20, 0, 160, cloche(seg(t, 0, 1.2F)), 12, 1);
        }
        int n = niv <= 1 ? 10 : niv == 2 ? 14 : 18;
        float u = seg(t, 0, 1.1F);
        if (u > 0 && u < 1) {
            for (int i = 0; i < n; i++) {
                float an = niv == 2 ? i / (float) (n - 1) * (float) Math.PI - (float) Math.PI / 2 : i * TOUR / n + 0.2F;
                float r = (niv >= 3 ? 110 : 60) * u;
                float x = (float) Math.sin(an) * r, z = niv == 2 ? -6 - (float) Math.cos(an) * 50 * u : (float) Math.cos(an) * r;
                plume(x, 22 + 30 * u - 30 * u * u, z, v((float) Math.sin(an), 0.5F - u, niv == 2 ? -(float) Math.cos(an) : (float) Math.cos(an)), 1 - u, i * 0.1F,
                        0.7F);
            }
        }
    }

    // ================================================================ l'écran pendant l'ulti : l'aube, le flash, la secousse

    /** {aube, flash, secousse (degrés)} pour le joueur local, d'après les ultis et les impacts autour de lui. */
    private static float[] ecranUlti() {
        Minecraft mc = Minecraft.getInstance();
        float aube = 0, flash = 0, sec = 0;
        if (mc.player == null) {
            return new float[]{0, 0, 0};
        }
        for (Anim a : ANIMS) {
            double d = mc.player.position().distanceTo(a.pos());
            float t = a.age();
            if (a.sorte() == Phenix.E_ULTI && a.phase() == 0 && d < 64 && t < 11.2F) {
                aube = Math.max(aube, lisse(seg(t, 1.8F, 3.4F)) * (1 - lisse(seg(t, 9, 11))));
                if (t >= 6.6F) {
                    flash = Math.max(flash, 0.85F * (1 - seg(t, 6.6F, 7.1F)));
                }
                float kp = seg(t, 0.4F, 0.8F) * (1 - seg(t, 2.6F, 3.4F));
                sec = Math.max(sec, 0.5F * kp);
                if (t > 2.4F && t < 3.0F) {
                    sec = Math.max(sec, 0.8F);
                }
                for (float tv : new float[]{3.55F, 4.55F}) {
                    float v = seg(t, tv, tv + 1.3F);
                    if (v > 0 && v < 0.3F) {
                        sec = Math.max(sec, 1 - v / 0.3F);
                    }
                }
                if (t >= 6.6F && t < 7.4F) {
                    sec = Math.max(sec, 2.2F * (1 - seg(t, 6.6F, 7.4F)));
                }
            }
            if (a.sorte() == Phenix.E_PIQUE && a.phase() == 2 && d < 24 && t < 0.5F) {
                sec = Math.max(sec, 1.2F * (1 - t / 0.5F));
            }
            if (a.sorte() == Phenix.E_GRAND && a.phase() == 2 && d < 24 && t < 0.4F) {
                sec = Math.max(sec, 0.8F * (1 - t / 0.4F));
            }
        }
        return new float[]{aube, flash, sec};
    }

    private static void voile(GuiGraphics g) {
        float[] e = ecranUlti();
        int w = g.guiWidth(), h = g.guiHeight();
        if (e[0] > 0.01F) {
            g.fillGradient(0, 0, w, h, (Math.round(0.3F * e[0] * 255) << 24) | 0x2A6CFF, (Math.round(0.26F * e[0] * 255) << 24) | 0xFFA040);
        }
        if (e[1] > 0.01F) {
            g.fill(0, 0, w, h, (Math.round(e[1] * 255) << 24) | 0xBFF0FF);
        }
    }

    // ================================================================ les touches et le panneau

    private static long pretDroit, pretAccroupi, pretTech, pretGrand, pretPique, pretVol, pretBrasier, pretTourbillon, pretAile, pretEnvol;
    private static boolean ultiFaite, accroupiAvant, accroupiAuSol, clicPendant, droitAvant, grandEnvoye, sautAvant;
    private static long accroupiDebut, droitDebut;
    private static int battements;
    /** La jauge de vol du niveau 3, comptée ici comme sur le serveur (pour le panneau). */
    private static int volReste = Phenix.VOL_MAX;

    private static long dans(int ticks) {
        return System.nanoTime() + ticks * 50_000_000L;
    }

    private static void envoyer(int action) {
        PacketDistributor.sendToServer(new Phenix.Touche(action));
    }

    private static String reste(long pret) {
        double s = (pret - System.nanoTime()) / 1e9;
        return s > 0 ? "  " + (int) Math.ceil(s) + " s" : "";
    }

    private static Etat moi() {
        Minecraft mc = Minecraft.getInstance();
        Etat e = mc.player == null ? null : ETATS.get(mc.player.getUUID());
        return e == null || e.fin >= 0 || e.niveau == 0 ? null : e;
    }

    private static boolean bloque() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.screen != null || renaissanceMoi >= 0) {
            return true;
        }
        for (Anim a : ANIMS) {
            if (a.sorte() == Phenix.E_ULTI && a.phase() == 0 && a.qui().equals(mc.player.getUUID()) && a.age() < Phenix.U_FIN / 20.0) {
                return true;
            }
        }
        return false;
    }

    /** La touche technique (², lue dans ClientSharingan) : le coup d'aile (niveau 2), le cocon (niveau 3). */
    public static boolean technique() {
        Etat e = moi();
        if (e == null) {
            return false;
        }
        if (e.niveau == 2 && !bloque() && System.nanoTime() >= pretAile) {
            pretAile = dans(Phenix.RECHARGE_AILE);
            envoyer(Phenix.COUP_AILE);
        } else if (e.niveau >= 3 && !bloque() && System.nanoTime() >= pretTech) {
            pretTech = dans(Phenix.RECHARGE_COCON);
            envoyer(Phenix.TECHNIQUE);
        }
        return true;
    }

    /**
     * Les touches L, I, virgule et point (lues dans ClientSharingan). Niveau 2 : L, l'envol ardent ; niveau 3 : le piqué,
     * le vol bleu, le brasier, le tourbillon. Rend vrai si le phénix la prend (il est éveillé).
     */
    public static boolean touche(int action) {
        Etat e = moi();
        if (e == null) {
            return false;
        }
        if (bloque()) {
            return true;
        }
        long maintenant = System.nanoTime();
        if (e.niveau == 2) {
            Minecraft mc = Minecraft.getInstance();
            if (action == Phenix.PIQUE && maintenant >= pretEnvol && mc.player.onGround()) {
                pretEnvol = dans(Phenix.RECHARGE_ENVOL);
                envoyer(Phenix.ENVOL);
            }
            return true;
        }
        if (e.niveau < 3) {
            return true;
        }
        switch (action) {
            case Phenix.PIQUE -> {
                if (maintenant >= pretPique) {
                    pretPique = dans(Phenix.RECHARGE_PIQUE);
                    envoyer(action);
                }
            }
            case Phenix.VOL_BLEU -> {
                if (maintenant >= pretVol) {
                    pretVol = dans(Phenix.RECHARGE_VOL);
                    envoyer(action);
                }
            }
            case Phenix.BRASIER -> {
                if (maintenant >= pretBrasier) {
                    pretBrasier = dans(Phenix.RECHARGE_BRASIER);
                    envoyer(action);
                }
            }
            case Phenix.TOURBILLON -> {
                if (maintenant >= pretTourbillon) {
                    pretTourbillon = dans(Phenix.RECHARGE_TOURBILLON);
                    envoyer(action);
                }
            }
            default -> { }
        }
        return true;
    }

    /** La touche d'ulti (J, lue dans ClientHachibi4) : l'aube du phénix, au niveau 3, une fois par éveil. */
    public static void toucheUlti() {
        Etat e = moi();
        if (e == null || e.niveau < 3 || bloque()) {
            return;
        }
        ultiFaite = true;
        envoyer(Phenix.ULTI);
    }

    private static void ecran(GuiGraphics g) {
        Minecraft mc = Minecraft.getInstance();
        Etat e = moi();
        if (e == null || mc.options.hideGui) {
            return;
        }
        int n = e.niveau;
        boolean deux = Phenix.NOM2.equals(e.pouvoir), trois = Phenix.NOM3.equals(e.pouvoir);
        String eveil = n == 1 && (deux || trois) ? "Niveau 2" : n == 2 && trois ? "Niveau 3" : "Rendormir";
        long now = System.nanoTime();
        List<String[]> lignes = new ArrayList<>();
        List<Boolean> gris = new ArrayList<>();
        if (n == 1) {
            lignes.add(new String[]{Clavier.gauche(), "Coup de flammes (brûle)"});
            gris.add(false);
            lignes.add(new String[]{Clavier.droite(), "Jet de flammes bleues" + reste(pretDroit)});
            gris.add(pretDroit > now);
            lignes.add(new String[]{Clavier.accroupi(), "Flammes de régénération" + reste(pretAccroupi)});
            gris.add(pretAccroupi > now);
        } else if (n == 2) {
            lignes.add(new String[]{Clavier.gauche(), "Coup de flammes (éclabousse)"});
            gris.add(false);
            lignes.add(new String[]{Clavier.droite(), "Coup de serre" + reste(pretDroit)});
            gris.add(pretDroit > now);
            lignes.add(new String[]{Clavier.accroupi(), "Pluie de plumes" + reste(pretAccroupi)});
            gris.add(pretAccroupi > now);
            lignes.add(new String[]{Clavier.nom(ClientSharingan.SUSANO), "Coup d'aile" + reste(pretAile)});
            gris.add(pretAile > now);
            lignes.add(new String[]{Clavier.nom(ClientSharingan.KAMUI), "Envol ardent" + reste(pretEnvol)});
            gris.add(pretEnvol > now);
            lignes.add(new String[]{Clavier.saut() + " en l'air", "Battements d'ailes (" + Math.max(0, 3 - battements) + "), puis il plane"});
            gris.add(battements >= 3);
        } else {
            lignes.add(new String[]{Clavier.gauche(), "Serres (au 3e coup : en croix)"});
            gris.add(false);
            lignes.add(new String[]{Clavier.droite(), "Éventail de plumes" + reste(pretDroit)});
            gris.add(pretDroit > now);
            lignes.add(new String[]{Clavier.droite() + " maintenu", "Grand phénix" + reste(pretGrand)});
            gris.add(pretGrand > now);
            lignes.add(new String[]{Clavier.nom(ClientSharingan.SUSANO), "Cocon de flammes" + reste(pretTech)});
            gris.add(pretTech > now);
            lignes.add(new String[]{Clavier.nom(ClientSharingan.KAMUI), "Piqué" + reste(pretPique)});
            gris.add(pretPique > now);
            lignes.add(new String[]{Clavier.nom(ClientSharingan.METEORITE), "Vol bleu" + reste(pretVol)});
            gris.add(pretVol > now);
            lignes.add(new String[]{Clavier.nom(ClientSharingan.CORBEAU), "Brasier" + reste(pretBrasier)});
            gris.add(pretBrasier > now);
            lignes.add(new String[]{Clavier.nom(ClientSharingan.FRACTURE), "Tourbillon de plumes" + reste(pretTourbillon)});
            gris.add(pretTourbillon > now);
            lignes.add(new String[]{Clavier.nom(fr.cabane.client.hachibi4.ClientHachibi4.ULTI), ultiFaite ? "L'aube du phénix (déjà lancée)" : "L'aube du phénix"});
            gris.add(ultiFaite);
            lignes.add(new String[]{Clavier.saut() + " ×2", "Voler  " + (volReste + 19) / 20 + " s"});
            gris.add(volReste <= 0);
        }
        lignes.add(new String[]{"", ""});
        gris.add(false);
        double min = (renaissancePrete - now) / 60e9;
        lignes.add(new String[]{"Passif", min > 0 ? "Renaissance dans " + (int) Math.ceil(min) + " min" : "Renaissance (prête)"});
        gris.add(min > 0);
        lignes.add(new String[]{Clavier.nom(ClientSusano.EVEIL), eveil});
        gris.add(false);
        boolean[] g2 = new boolean[gris.size()];
        for (int i = 0; i < g2.length; i++) {
            g2[i] = gris.get(i);
        }
        PanneauPouvoir.dessiner(g, mc.font, "Phénix" + (n >= 2 ? "  ·  niv. " + n : ""), 0xFF7CE8FF, lignes.toArray(new String[0][]), g2);
    }

    // ================================================================ les événements

    @EventBusSubscriber(modid = Cabane.MODID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
    public static final class Couches {
        @SubscribeEvent
        public static void enregistrer(RegisterGuiLayersEvent event) {
            event.registerAboveAll(Cabane.id("phenix_voile"), (g, delta) -> voile(g));
            event.registerAboveAll(Cabane.id("phenix"), (g, delta) -> ecran(g));
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
            LocalPlayer p = mc.player;
            if (mc.level == null || p == null) {
                ETATS.clear();
                ANIMS.clear();
                PLUMES_VOL.clear();
                GRANDS.clear();
                AIR.clear();
                finirCamera();
                return;
            }
            // depuis combien de ticks chacun est en l'air (au niveau 3, en l'air, il devient le phénix)
            for (Player j : mc.level.players()) {
                if (ETATS.containsKey(j.getUUID())) {
                    AIR.merge(j.getUUID(), 1, Integer::sum);
                    if (j.onGround() || j.isInWater() || j.isPassenger() || j.onClimbable()) {
                        AIR.put(j.getUUID(), 0);
                    }
                }
            }
            AIR.keySet().removeIf(id -> !ETATS.containsKey(id));
            Etat moi = moi();
            // la renaissance : la caméra sort de son corps et tourne autour de l'œuf
            if (renaissanceMoi >= 0) {
                if (secondes(renaissanceMoi) > 6) {
                    finirCamera();
                } else {
                    p.setYRot(p.getYRot() + 2.5F);
                    p.setXRot(Mth.lerp(0.1F, p.getXRot(), 22));
                }
            }
            boolean accroupi = mc.options.keyShift.isDown();
            // niveaux 1 et 2 : s'accroupir au sol (un appui court, sans clic) lance la régénération ou la pluie de plumes ;
            // en l'air, s'accroupir ne lance rien (il descend)
            if (moi != null && moi.niveau <= 2) {
                if (accroupi && !accroupiAvant) {
                    accroupiDebut = System.nanoTime();
                    clicPendant = false;
                    accroupiAuSol = p.onGround();
                }
                if (!accroupi && accroupiAvant && !clicPendant && accroupiAuSol && !bloque() && secondes(accroupiDebut) < 0.6
                        && System.nanoTime() >= pretAccroupi) {
                    pretAccroupi = dans(moi.niveau == 1 ? Phenix.RECHARGE_REGEN : Phenix.RECHARGE_PLUIE);
                    envoyer(Phenix.ACCROUPI);
                }
            }
            accroupiAvant = accroupi;
            // niveau 2 : sauter en l'air, 3 battements d'ailes, puis il plane (accroupi : il tombe)
            boolean saut = mc.options.keyJump.isDown();
            if (moi != null && moi.niveau == 2) {
                if (p.onGround() || p.isInWater() || p.onClimbable()) {
                    battements = 0;
                } else if (saut && !sautAvant && battements < 3 && !bloque() && !p.getAbilities().flying) {
                    battements++;
                    Vec3 d = p.getDeltaMovement();
                    p.setDeltaMovement(d.x * 1.1, 0.62, d.z * 1.1);
                    envoyer(Phenix.BATTEMENT);
                }
                Vec3 d = p.getDeltaMovement();
                if (battements > 0 && !p.onGround() && !accroupi && d.y < -0.11 && !p.isInWater() && !p.getAbilities().flying && !p.isPassenger()) {
                    Vec3 h = new Vec3(d.x, 0, d.z);
                    if (p.input.forwardImpulse > 0) {
                        Vec3 regard = p.getLookAngle();
                        h = h.lerp(new Vec3(regard.x, 0, regard.z).normalize().scale(0.42), 0.08);
                    }
                    p.setDeltaMovement(h.x, -0.11, h.z);
                }
            } else {
                battements = 0;
            }
            sautAvant = saut;
            // niveau 3 : clic droit court, l'éventail ; maintenu, le grand phénix (relâché, il explose)
            boolean droit = mc.options.keyUse.isDown() && mc.screen == null;
            if (moi != null && moi.niveau >= 3) {
                if (droit && !droitAvant) {
                    droitDebut = System.nanoTime();
                    grandEnvoye = false;
                }
                if (droit && !grandEnvoye && secondes(droitDebut) > 0.3 && System.nanoTime() >= pretGrand && !bloque()) {
                    grandEnvoye = true;
                    pretGrand = dans(Phenix.RECHARGE_GRAND);
                    p.swing(InteractionHand.MAIN_HAND);
                    envoyer(Phenix.GRAND);
                }
                if (!droit && droitAvant) {
                    if (grandEnvoye) {
                        envoyer(Phenix.GRAND_LACHE);
                    } else if (System.nanoTime() >= pretDroit && !bloque()) {
                        pretDroit = dans(Phenix.RECHARGE_EVENTAIL);
                        p.swing(InteractionHand.MAIN_HAND);
                        envoyer(Phenix.DROIT);
                    }
                    grandEnvoye = false;
                }
                // la jauge de vol (comme sur le serveur)
                if (p.getAbilities().flying) {
                    volReste = Math.max(0, volReste - 1);
                } else if (p.onGround()) {
                    volReste = Math.min(Phenix.VOL_MAX, volReste + 2);
                }
            }
            droitAvant = droit;
            ETATS.values().removeIf(e -> e.fin >= 0 && secondes(e.fin) > 1);
            ANIMS.removeIf(a -> a.age() > (a.sorte() == Phenix.E_ULTI ? 12 : a.sorte() == Phenix.E_RENAISSANCE ? 8 : 6));
            PLUMES_VOL.values().removeIf(a -> a.age() > a.phase() / 20.0 + 0.5);
            GRANDS.values().removeIf(g -> secondes(g.recu) > 1);
        }

        /** Éveillé, le clic droit est au phénix (le jet, la serre ; au niveau 3, voir tick). */
        @SubscribeEvent(priority = net.neoforged.bus.api.EventPriority.HIGHEST)
        public static void clic(InputEvent.InteractionKeyMappingTriggered event) {
            Minecraft mc = Minecraft.getInstance();
            Etat moi = moi();
            if (moi == null) {
                return;
            }
            if (event.isAttack() && mc.options.keyShift.isDown()) {
                clicPendant = true;
            }
            if (!event.isUseItem()) {
                return;
            }
            event.setCanceled(true);
            event.setSwingHand(false);
            clicPendant = true;
            if (moi.niveau >= 3 || bloque()) {
                return;
            }
            if (System.nanoTime() >= pretDroit) {
                pretDroit = dans(moi.niveau == 2 ? Phenix.RECHARGE_SERRE : Phenix.RECHARGE_JET);
                mc.player.swing(InteractionHand.MAIN_HAND);
                envoyer(Phenix.DROIT);
            }
        }

        /** Pendant sa renaissance, il ne bouge pas (il est en cendres). */
        @SubscribeEvent
        public static void immobile(MovementInputUpdateEvent event) {
            if (renaissanceMoi < 0) {
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

        /** Au niveau 3, en l'air (et pendant le piqué, le vol bleu), il est le phénix complet. */
        @SubscribeEvent
        public static void oiseau(RenderPlayerEvent.Pre event) {
            if (!Feu.pret()) {
                return;
            }
            Player j = event.getEntity();
            Etat e = ETATS.get(j.getUUID());
            if (e == null || e.fin >= 0 || e.niveau < 3 || j.isInvisible()) {
                return;
            }
            boolean force = System.nanoTime() < e.oiseauJusqua;
            // pendant le cocon, il reste lui-même (le cocon l'entoure)
            boolean cocon = e.cocon >= 0 && secondes(e.cocon) < e.coconDuree + 0.6;
            boolean b = !cocon && (force || (AIR.getOrDefault(j.getUUID(), 0) > 8 && !j.isInWater()));
            if (b != e.oiseau) {
                e.oiseau = b;
                ANIMS.add(new Anim(j.getUUID(), E_ECLAT, 0, j.position().add(0, 0.9, 0), Vec3.ZERO, 0, System.nanoTime()));
            }
            if (!b) {
                return;
            }
            event.setCanceled(true);
            float partiel = event.getPartialTick();
            Vec3 c = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
            Vec3 p = j.getPosition(partiel);
            double vx = j.getX() - j.xo, vy = j.getY() - j.yo, vz = j.getZ() - j.zo, hz = Math.sqrt(vx * vx + vz * vz);
            float tang = (float) Mth.clamp(Math.atan2(-vy, Math.max(hz, 0.08)) * 0.9, -0.9, 1.25);
            boolean pique = force && vy < -0.3;
            float yaw = j.getViewYRot(partiel) * Mth.DEG_TO_RAD;
            Matrix4f avant = repere(new Matrix4f().translate((float) (p.x - c.x), (float) (p.y - c.y) + 0.9F, (float) (p.z - c.z)).rotateY(-yaw).rotateX(tang)
                    .scale(1 / 16F));
            float bat = pique ? -0.15F : (float) Math.sin(T * 5.5 + j.getId()) * 0.55F;
            Oiseau.oiseau(0.9F, bat, 1, pique ? 0.4F : 1, 0, pique ? 1.5F : 1);
            if (hz + Math.abs(vy) > 0.4) {
                float k = (float) Math.min(1, (hz + Math.abs(vy) - 0.4) * 2);
                ruban((s, o) -> o.set(0, 1 - s * 2, -8 - s * 50), s -> 9 * (1 - s), 0.3F, 0.8F * k, 0.3F, 4, 3, 0.7F);
            }
            repere(avant);
        }

        @SubscribeEvent
        public static void monde(RenderLevelStageEvent event) {
            if (Minecraft.getInstance().level == null) {
                return;
            }
            if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_SKY) {
                Feu.debut(event.getCamera());
                return;
            }
            if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) {
                return;
            }
            cam = event.getCamera().getPosition();
            ClientPhenix.monde(event.getPartialTick().getGameTimeDeltaPartialTick(false));
            Feu.dessiner(event);
        }

        /** La secousse de l'ulti et des impacts. */
        @SubscribeEvent
        public static void secousse(ViewportEvent.ComputeCameraAngles event) {
            float s = ecranUlti()[2];
            if (s <= 0.01F) {
                return;
            }
            event.setYaw(event.getYaw() + (HASARD.nextFloat() - 0.5F) * s * 2);
            event.setPitch(event.getPitch() + (HASARD.nextFloat() - 0.5F) * s * 2);
            event.setRoll(event.getRoll() + (HASARD.nextFloat() - 0.5F) * s * 1.5F);
        }

        private Evenements() {
        }
    }

    private ClientPhenix() {
    }
}
