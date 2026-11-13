package fr.cabane.client.pouvoir;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import fr.cabane.Cabane;
import fr.cabane.pouvoir.ClairObscur;
import fr.cabane.pouvoir.Lumiere;
import fr.cabane.pouvoir.Pouvoirs;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import net.minecraft.client.resources.PlayerSkin;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Ce qu'on voit de la Lumière (voir {@link Lumiere} et docs/tenebres-lumiere) : les yeux d'or, l'auréole, des étincelles
 * (niveau 1) ; deux ailes de lumière qui s'ouvrent, une aura dorée (niveau 2) ; la tenue (le skin de l'utilisateur,
 * adapté, bras fins), six ailes, le soleil dans le dos (niveau 3). Et les coups : la flèche, l'éclat, les lances, l'envol,
 * les plumes, le rayon solaire, la vitesse de la lumière, la croix, le chakram, et le Jugement (les nuages qui s'ouvrent,
 * l'ange géant, la pluie d'épées, la colonne de lumière).
 */
public final class ClientLumiere {
    private static final RandomSource HASARD = RandomSource.create();
    static final ResourceLocation TENUE = Cabane.id("textures/pouvoir/lumiere3.png");
    private static final int OR_YEUX = 0xFFE07A;
    private static final DustParticleOptions OR = new DustParticleOptions(new Vector3f(1F, 0.82F, 0.35F), 1.1F);
    private static final float R = 1F, G = 0.8F, B = 0.35F;

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

    // ================================================================ qui est éveillé

    static final class Etat {
        String pouvoir;
        int niveau;
        long debut1 = -1, debut2 = -1, debut3 = -1, fin = -1, rayon = -1, rayonFin = -1;
        int niveauFin;
    }

    private static final Map<UUID, Etat> ETATS = new HashMap<>();

    public static boolean eveille(UUID qui) {
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
            e.debut1 = p.niveau() == 1 ? maintenant : loin;
        }
        if (p.niveau() >= 2 && e.debut2 < 0) {
            e.debut2 = p.niveau() == 2 ? maintenant : loin;
        }
        if (p.niveau() >= 3 && e.debut3 < 0) {
            e.debut3 = maintenant;
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

    private record Anim(UUID qui, int sorte, int phase, Vec3 pos, Vec3 dir, int n, long debut) {
        double age() {
            return secondes(debut);
        }
    }

    private static final List<Anim> ANIMS = new ArrayList<>();

    static final class Ulti {
        long[] t = {-1, -1, -1, -1, -1};
        Vec3 pos, dir, zone;
    }

    private static final Map<UUID, Ulti> ULTIS = new HashMap<>();
    /** Le flash blanc à l'écran (l'éclat tout près, la colonne du Jugement). */
    private static long flash = -1;
    private static float flashForce;

    public static void effet(Lumiere.Effet p) {
        Minecraft mc = Minecraft.getInstance();
        Level level = mc.level;
        if (level == null) {
            return;
        }
        Vec3 pos = vec(p.pos()), dir = vec(p.dir());
        if (p.sorte() == Lumiere.E_ULTI) {
            Ulti u = ULTIS.computeIfAbsent(p.joueur(), k -> new Ulti());
            if (p.phase() == 0) {
                u.t = new long[]{System.nanoTime(), -1, -1, -1, -1};
                u.pos = pos;
                u.dir = dir;
                u.zone = null;
            }
            u.t[p.phase()] = System.nanoTime();
            if (p.phase() >= 2 && p.phase() <= 3) {
                u.zone = pos;
            }
            if (p.phase() == 3) {
                secouer(mc, pos, 2F, 50);
                eclairer(mc, pos, 40, 0.9F);
                for (int k = 0; k < 160; k++) {
                    level.addParticle(k % 2 == 0 ? ParticleTypes.END_ROD : OR, pos.x + HASARD.nextGaussian() * 4, pos.y + HASARD.nextDouble() * 8, pos.z + HASARD.nextGaussian() * 4,
                            HASARD.nextGaussian() * 0.5, HASARD.nextDouble() * 0.8, HASARD.nextGaussian() * 0.5);
                }
            }
            return;
        }
        Etat e = ETATS.get(p.joueur());
        if (p.sorte() == Lumiere.E_RAYON && e != null) {
            e.rayon = System.nanoTime();
            e.rayonFin = System.nanoTime() + p.n() * 50_000_000L;
            return;
        }
        ANIMS.add(new Anim(p.joueur(), p.sorte(), p.phase(), pos, dir, p.n(), System.nanoTime()));
        switch (p.sorte()) {
            case Lumiere.E_COUP -> etincelles(level, pos, p.phase() * 6, 0.3);
            case Lumiere.E_FLECHE -> etincelles(level, dir, 16, 0.3);
            case Lumiere.E_FLASH -> {
                level.addParticle(ParticleTypes.FLASH, pos.x, pos.y + 1, pos.z, 0, 0, 0);
                etincelles(level, pos.add(0, 1, 0), 60, 1.5);
                eclairer(mc, pos, 7, 0.85F);
            }
            case Lumiere.E_ENVOL -> etincelles(level, pos, 50, 1.5);
            case Lumiere.E_VITESSE -> {
                for (int k = 0; k < 40; k++) {
                    Vec3 q = pos.lerp(dir, HASARD.nextDouble()).add(0, 1 + HASARD.nextGaussian() * 0.3, 0);
                    level.addParticle(ParticleTypes.END_ROD, q.x, q.y, q.z, 0, 0.02, 0);
                }
            }
            default -> { }
        }
    }

    static void etincelles(Level level, Vec3 p, int n, double r) {
        for (int k = 0; k < n; k++) {
            level.addParticle(k % 2 == 0 ? ParticleTypes.END_ROD : OR, p.x + HASARD.nextGaussian() * r, p.y + HASARD.nextGaussian() * r, p.z + HASARD.nextGaussian() * r,
                    HASARD.nextGaussian() * 0.05, 0.03 + HASARD.nextDouble() * 0.05, HASARD.nextGaussian() * 0.05);
        }
    }

    private static void secouer(Minecraft mc, Vec3 p, float force, double portee) {
        if (mc.player == null) {
            return;
        }
        double loin = mc.player.position().distanceTo(p);
        if (loin < portee) {
            ClientVent.secouer((float) (force * (1 - loin / portee)));
        }
    }

    private static void eclairer(Minecraft mc, Vec3 p, double portee, float force) {
        if (mc.player != null && mc.player.position().distanceTo(p) < portee) {
            flash = System.nanoTime();
            flashForce = force;
        }
    }

    // ================================================================ la tenue du niveau 3

    private static final Map<PlayerSkin, PlayerSkin> TENUES = new HashMap<>();

    /** Appelé à chaque {@code getSkin()} (AbstractClientPlayerMixin) : au niveau 3, la tenue de la Lumière (bras fins). */
    public static PlayerSkin peau(AbstractClientPlayer joueur, PlayerSkin skin) {
        Etat e = ETATS.get(joueur.getUUID());
        if (e == null || e.fin >= 0 || e.niveau < 3 || e.debut3 < 0 || secondes(e.debut3) < 0.9) {
            return skin;
        }
        return TENUES.computeIfAbsent(skin, s -> new PlayerSkin(TENUE, s.textureUrl(), s.capeTexture(), s.elytraTexture(), PlayerSkin.Model.SLIM, s.secure()));
    }

    // ================================================================ sur le joueur

    private static void piece(String nom, PoseStack ps, MultiBufferSource b, int lumiere, ModelPart[] parties, float alpha) {
        ModeleVent[] l = ModeleVent.lire("lumiere", nom);
        for (int i = 0; i < l.length && i < parties.length; i++) {
            ps.pushPose();
            parties[i].translateAndRotate(ps);
            l[i].dessiner(b, ps.last(), lumiere, 1 / 16F, alpha);
            ps.popPose();
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
            float efface = e.fin >= 0 ? (float) Math.max(0, 1 - secondes(e.fin) / 0.35) : 1;
            if (efface <= 0) {
                return;
            }
            PlayerModel<AbstractClientPlayer> m = getParentModel();
            ModelPart[] parties = {m.head, m.body, m.rightArm, m.leftArm, m.rightLeg, m.leftLeg};
            int niveau = e.fin >= 0 ? e.niveauFin : e.niveau;
            double t1 = e.debut1 < 0 ? 99 : secondes(e.debut1), t2 = e.debut2 < 0 ? 99 : secondes(e.debut2), t3 = e.debut3 < 0 ? 99 : secondes(e.debut3);
            ClientTenebres.yeux(ps, buffers, m.head, OR_YEUX);
            if (t1 > 0.5) {
                piece(niveau >= 2 ? "aureole" : "aureole1", ps, buffers, LightTexture.FULL_BRIGHT, parties, efface);
            }
            if (niveau >= 2) {
                ClientTenebres.aura(ps, buffers, parties, 1.1F, (int) (55 * efface) << 24 | 0xFFDC82);
                ClientTenebres.aura(ps, buffers, parties, 2.4F, (int) (22 * efface) << 24 | 0xFFF8E0);
            }
            if (niveau >= 3 && t3 > 0.9) {
                piece("ailes3", ps, buffers, LightTexture.FULL_BRIGHT, parties, efface);
                piece("soleil", ps, buffers, LightTexture.FULL_BRIGHT, parties, efface);
            } else if (niveau >= 2) {
                // à l'éveil 2, les ailes s'ouvrent en une seconde
                int k = niveau == 2 && t2 < 1.2 ? Mth.clamp(1 + (int) (t2 / 0.4), 1, 3) : 3;
                piece("ailes2_" + k, ps, buffers, LightTexture.FULL_BRIGHT, parties, efface);
            }
        }
    }

    /** Les poses : les bras ouverts et la tête levée aux éveils, les deux mains en avant pour le rayon, les bras levés au Jugement. */
    public static void poser(PlayerModel<?> m, Player joueur) {
        Etat e = ETATS.get(joueur.getUUID());
        if (e == null || e.fin >= 0) {
            return;
        }
        boolean change = false;
        double t1 = e.debut1 < 0 ? 99 : secondes(e.debut1), t3 = e.debut3 < 0 ? 99 : secondes(e.debut3);
        double te = e.niveau == 1 ? t1 : e.niveau >= 3 ? t3 : 99;
        if (te < 1.6) {
            float k = (float) (lisse(te / 0.3) * (1 - lisse((te - 1.3) / 0.3)));
            m.rightArm.zRot = Mth.lerp(k, m.rightArm.zRot, 0.9F);
            m.leftArm.zRot = Mth.lerp(k, m.leftArm.zRot, -0.9F);
            m.head.xRot = Mth.lerp(k, m.head.xRot, -0.6F);
            change = true;
        }
        if (e.rayonFin >= 0 && System.nanoTime() < e.rayonFin) {
            m.rightArm.xRot = m.head.xRot - Mth.HALF_PI;
            m.leftArm.xRot = m.head.xRot - Mth.HALF_PI;
            m.rightArm.yRot = m.head.yRot + 0.15F;
            m.leftArm.yRot = m.head.yRot - 0.15F;
            change = true;
        }
        Ulti u = ULTIS.get(joueur.getUUID());
        if (u != null && u.t[0] >= 0 && u.t[4] < 0) {
            float k = (float) lisse(secondes(u.t[0]) / 0.4);
            m.rightArm.xRot = Mth.lerp(k, m.rightArm.xRot, u.t[3] >= 0 ? -1.4F : -2.9F);
            m.leftArm.xRot = Mth.lerp(k, m.leftArm.xRot, u.t[3] >= 0 ? -1.4F : -2.9F);
            change = true;
        }
        for (Anim a : ANIMS) {
            if (a.qui().equals(joueur.getUUID()) && a.age() < 0.4 && a.sorte() != Lumiere.E_COUP && a.sorte() != Lumiere.E_ENVOL) {
                m.rightArm.xRot = m.head.xRot - Mth.HALF_PI;
                m.rightArm.yRot = m.head.yRot;
                change = true;
            }
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

    // ================================================================ dans le monde

    private static int lumiere(Level level, Vec3 p) {
        return LevelRenderer.getLightColor(level, BlockPos.containing(p));
    }

    private static Entity entite(Level level, int id) {
        return id < 0 ? null : level.getEntity(id);
    }

    private static Vec3 horizontal(Vec3 d) {
        Vec3 h = new Vec3(d.x, 0, d.z);
        return h.lengthSqr() < 1e-6 ? new Vec3(0, 0, 1) : h.normalize();
    }

    private static void monde(PoseStack ps, Vec3 cam, float partiel) {
        Minecraft mc = Minecraft.getInstance();
        Level level = mc.level;
        MultiBufferSource.BufferSource b = mc.renderBuffers().bufferSource();
        // le rayon solaire : des deux mains, là où il regarde
        for (Map.Entry<UUID, Etat> en : ETATS.entrySet()) {
            Etat e = en.getValue();
            if (e.rayonFin < 0 || System.nanoTime() > e.rayonFin + 200_000_000L || !(level.getPlayerByUUID(en.getKey()) instanceof Player j)) {
                continue;
            }
            float al = (float) (lisse(secondes(e.rayon) / 0.15) * (1 - lisse((System.nanoTime() - e.rayonFin) / 2e8)));
            Vec3 regard = j.getViewVector(partiel);
            Vec3 de = j.getEyePosition(partiel).add(0, -0.45, 0).add(regard.scale(0.7));
            Vec3 a = de.add(regard.scale(Lumiere.PORTEE_RAYON));
            HitResult h = level.clip(new ClipContext(de, a, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, j));
            if (h.getType() != HitResult.Type.MISS) {
                a = h.getLocation();
            }
            DessinClair.rayon(ps, b, cam, de, a, 0.5, R, G, B, al);
            VertexConsumer lu = DessinClair.lueur(b);
            double t = System.nanoTime() / 1e9;
            Vec3 u = regard.cross(new Vec3(0, 1, 0)).normalize(), v = u.cross(regard).normalize();
            for (int k = 0; k < 2; k++) {
                Vec3[] pts = new Vec3[40];
                for (int q = 0; q < 40; q++) {
                    double s = q / 39.0, ang = s * 30 - t * 14 + k * Math.PI;
                    pts[q] = de.lerp(a, s).add(u.scale(Math.cos(ang) * 0.6)).add(v.scale(Math.sin(ang) * 0.6));
                }
                DessinClair.ligne(ps, lu, cam, pts, 0.08, R, G, B, al * 0.8F);
            }
            if (HASARD.nextInt(2) == 0) {
                etincelles(level, a, 3, 0.3);
            }
        }
        for (Anim a : ANIMS) {
            dessiner(ps, b, cam, level, a, partiel);
        }
        for (Map.Entry<UUID, Ulti> en : ULTIS.entrySet()) {
            ulti(ps, b, cam, level, en.getKey(), en.getValue(), partiel);
        }
        b.endBatch();
    }

    private static void dessiner(PoseStack ps, MultiBufferSource b, Vec3 cam, Level level, Anim a, float partiel) {
        double t = a.age();
        switch (a.sorte()) {
            case Lumiere.E_COUP -> {
                if (a.phase() >= 2 && t < 0.4) {
                    DessinClair.anneau(ps, DessinClair.lueur(b), cam, a.pos(), 0.4 + 1.6 * lisse(t / 0.3), 0.15, horizontal(a.dir()).cross(new Vec3(0, 1, 0)),
                            new Vec3(0, 1, 0), R, G, B, (float) (1 - t / 0.4));
                }
            }
            case Lumiere.E_FLECHE -> {
                if (t < 0.4) {
                    DessinClair.rayon(ps, b, cam, a.pos(), a.dir(), 0.18, R, G, B, (float) (1 - t / 0.4));
                }
            }
            case Lumiere.E_FLASH -> {
                if (t < 0.6) {
                    double r = 6.5 * lisse(t / 0.2);
                    DessinClair.boule(ps, DessinClair.voile(b), cam, a.pos().add(0, 1, 0), r, (int) (130 * (1 - t / 0.6)) << 24 | 0xFFFCEC, LightTexture.FULL_BRIGHT);
                }
            }
            case Lumiere.E_LANCE -> lance(ps, b, cam, level, a, t, partiel);
            case Lumiere.E_ENVOL -> {
                for (int k = 0; k < 3; k++) {
                    double tt = t - k * 0.12;
                    if (tt > 0 && tt < 0.9) {
                        DessinClair.anneauSol(ps, DessinClair.lueur(b), cam, a.pos().add(0, 0.05 + k * 0.4, 0), 1 + 4 * lisse(tt / 0.6), 0.15, R, G, B, (float) (1 - tt / 0.9));
                    }
                }
            }
            case Lumiere.E_VITESSE -> {
                if (t < 0.8) {
                    float al = (float) (1 - t / 0.8);
                    for (int k = 0; k < 5; k++) {
                        Vec3 o = new Vec3(0, 0.3 + k * 0.35, 0);
                        DessinClair.rayon(ps, b, cam, a.pos().add(o), a.dir().add(o), k == 2 ? 0.25 : 0.1, R, G, B, al);
                    }
                    // trois doubles de lumière le long du chemin
                    Vec3 d = horizontal(a.dir().subtract(a.pos()));
                    float lacet = (float) (Math.atan2(-d.x, d.z) * Mth.RAD_TO_DEG);
                    for (int k = 1; k <= 3; k++) {
                        Vec3 pieds = a.pos().lerp(a.dir(), k / 4.0);
                        ps.pushPose();
                        DessinClair.repere(ps, cam, pieds, lacet, 1, 0.35F);
                        DessinClair.pantin(ps, b, new DessinClair.Pose().bras(0.8F, 0.2F, 0.8F, -0.2F).jambes(-0.6F, 0.6F), (int) (90 * al * k / 3) << 24 | 0xFFE8A0, -1,
                                LightTexture.FULL_BRIGHT);
                        ps.popPose();
                    }
                }
            }
            case Lumiere.E_PLUMES -> {
                if (t > 0.7) {
                    return;
                }
                Vec3 d = a.dir().normalize();
                Vec3 u = d.cross(new Vec3(0, 1, 0)).normalize(), v = u.cross(d).normalize();
                for (int q = 0; q < 26; q++) {
                    double s = q % 2 == 0 ? 1 : -1;
                    Vec3 dep = a.pos().add(u.scale(s * (0.6 + 1.2 * Kag(q, 1)))).add(v.scale(-0.2 + 0.8 * Kag(q, 2)));
                    Vec3 dq = d.add(u.scale((Kag(q, 3) - 0.5) * 0.5)).add(v.scale((Kag(q, 4) - 0.5) * 0.35)).normalize();
                    double vol = Math.max(0, t - Kag(q, 5) * 0.15) * 30;
                    if (vol <= 0 || vol > 16) {
                        continue;
                    }
                    Vec3 p = dep.add(dq.scale(vol));
                    DessinClair.modele(ps, b, cam, "lumiere", "plume", p, DessinClair.vers(dq), 1 / 16F * 1.6F, LightTexture.FULL_BRIGHT, 1);
                    DessinClair.trait(ps, DessinClair.lueur(b), cam, p.subtract(dq.scale(1.2)), p, 0.05, R, G, B, 0.6F);
                }
            }
            case Lumiere.E_CROIX -> {
                Vec3 c = a.pos();
                double attente = Lumiere.CROIX_DELAI / 20.0;
                VertexConsumer lu = DessinClair.lueur(b);
                float pre = (float) (lisse(t / 0.2) * (1 - lisse((t - attente - 0.6) / 0.6)));
                // la croix au sol (elle prévient), puis la grande croix qui jaillit
                DessinClair.trait(ps, lu, cam, c.add(-2.5, 0.05, 0), c.add(2.5, 0.05, 0), 0.25, R, G, B, pre);
                DessinClair.trait(ps, lu, cam, c.add(0, 0.05, -2.5), c.add(0, 0.05, 2.5), 0.25, R, G, B, pre);
                DessinClair.anneauSol(ps, lu, cam, c.add(0, 0.05, 0), 2.6, 0.12, R, G, B, pre);
                double te = t - attente;
                if (te > 0 && te < 1.4) {
                    float al = (float) (1 - lisse((te - 0.6) / 0.8));
                    double h = 9 * lisse(te / 0.15);
                    DessinClair.rayon(ps, b, cam, c, c.add(0, h, 0), 0.9, R, G, B, al);
                    Vec3 o = cam.subtract(c);
                    Vec3 cote = new Vec3(-o.z, 0, o.x).normalize().scale(3.2 * lisse((te - 0.08) / 0.15));
                    DessinClair.rayon(ps, b, cam, c.add(0, 5.5, 0).subtract(cote), c.add(0, 5.5, 0).add(cote), 0.7, R, G, B, al);
                }
            }
            case Lumiere.E_CHAKRAM -> chakram(ps, b, cam, level, a, t, partiel);
            default -> { }
        }
    }

    private static double Kag(int i, int j) {
        return fr.cabane.pouvoir.Kaguya.hache(i, j, 911);
    }

    /** Une lance de lumière qui tombe du ciel sur la cible, puis reste plantée un instant. */
    private static void lance(PoseStack ps, MultiBufferSource b, Vec3 cam, Level level, Anim a, double t, float partiel) {
        double impact = (10 + a.phase() * 4) / 20.0;
        Entity cible = entite(level, a.n());
        Vec3 sol = cible != null && t < impact ? cible.getPosition(partiel) : a.pos();
        Vec3 dec = new Vec3((Kag(a.phase(), 1) - 0.5) * 1.2, 0, (Kag(a.phase(), 2) - 0.5) * 1.2);
        Vec3 pointe = sol.add(dec);
        double chute = lisse((t - (impact - 0.3)) / 0.3);
        double haut = 22 * (1 - chute);
        float al = (float) (lisse(t / 0.15) * (1 - lisse((t - impact - 0.6) / 0.4)));
        if (al <= 0.01) {
            return;
        }
        Vec3 p = pointe.add(0, haut + 0.3, 0);
        DessinClair.modele(ps, b, cam, "lumiere", "lance", p, DessinClair.vers(new Vec3(0.05, -1, 0.02)), 1 / 16F * 2.2F, LightTexture.FULL_BRIGHT, al);
        if (t < impact) {
            DessinClair.trait(ps, DessinClair.lueur(b), cam, p, p.add(0, 6, 0), 0.25, R, G, B, al * 0.5F);
        } else {
            DessinClair.anneauSol(ps, DessinClair.lueur(b), cam, pointe.add(0, 0.05, 0), 0.4 + 1.6 * lisse((t - impact) / 0.3), 0.12, R, G, B, al);
        }
    }

    /** Le chakram : il file tout droit 25 blocs, puis revient dans sa main, en tournant. */
    private static void chakram(PoseStack ps, MultiBufferSource b, Vec3 cam, Level level, Anim a, double t, float partiel) {
        double aller = Lumiere.PORTEE_CHAKRAM / Lumiere.VITESSE_CHAKRAM / 20.0;
        Player j = level.getPlayerByUUID(a.qui());
        Vec3 loin = a.pos().add(a.dir().scale(Lumiere.PORTEE_CHAKRAM));
        Vec3 p;
        if (t <= aller) {
            p = a.pos().add(a.dir().scale(Lumiere.PORTEE_CHAKRAM * t / aller));
        } else {
            if (j == null) {
                return;
            }
            Vec3 main = j.getEyePosition(partiel).add(0, -0.2, 0);
            double retour = loin.distanceTo(main) / (Lumiere.VITESSE_CHAKRAM * 1.2 * 20);
            double s = (t - aller) / retour;
            if (s >= 1) {
                return;
            }
            p = loin.lerp(main, s);
        }
        Quaternionf r = new Quaternionf().rotateY((float) (t * 25));
        DessinClair.modele(ps, b, cam, "lumiere", "chakram", p, r, 1 / 16F * 1.4F, LightTexture.FULL_BRIGHT, 1);
        if (HASARD.nextInt(2) == 0) {
            level.addParticle(ParticleTypes.END_ROD, p.x, p.y, p.z, 0, 0, 0);
        }
    }

    // ================================================================ le Jugement

    private static void ulti(PoseStack ps, MultiBufferSource b, Vec3 cam, Level level, UUID qui, Ulti u, float partiel) {
        if (u.t[0] < 0) {
            return;
        }
        double t = secondes(u.t[0]);
        double fin = u.t[4] >= 0 ? secondes(u.t[4]) : -1;
        float part = fin < 0 ? 1 : (float) (1 - lisse(fin / 1.2));
        if (part <= 0) {
            return;
        }
        Vec3 dir = horizontal(u.dir);
        Player j = level.getPlayerByUUID(qui);
        Vec3 lui = j != null ? j.getPosition(partiel) : u.pos;
        // les nuages s'ouvrent au-dessus de lui : un anneau de gros blocs blancs, un trou de lumière, des rayons qui tombent
        Vec3 ciel = u.pos.add(dir.scale(6)).add(0, 32, 0);
        double ouvre = lisse(t / 1.2);
        VertexConsumer pl = DessinClair.plein(b);
        for (int q = 0; q < 70; q++) {
            double a = Kag(q, 1) * Mth.TWO_PI, r = (7 + 12 * Kag(q, 2)) * (0.4 + 0.6 * ouvre);
            Vec3 p = ciel.add(Math.cos(a) * r, (Kag(q, 3) - 0.5) * 3, Math.sin(a) * r);
            int g = 150 + (int) (100 * (1 - (r - 7) / 12));
            DessinClair.cube(ps, pl, cam, p, 2 + 3 * Kag(q, 4), (int) (255 * part) << 24 | Mth.clamp(g, 120, 250) << 16 | Mth.clamp(g, 120, 250) << 8 | Mth.clamp(g - 10, 110, 240),
                    LightTexture.FULL_BRIGHT);
        }
        DessinClair.boule(ps, DessinClair.voile(b), cam, ciel, 6 * ouvre, (int) (170 * part) << 24 | 0xFFF6D2, LightTexture.FULL_BRIGHT);
        for (int k = 0; k < 3; k++) {
            Vec3 o = new Vec3((k - 1) * 2.5, 0, (k - 1) * 1.2);
            DessinClair.rayon(ps, b, cam, ciel.add(o), lui.add(o.scale(0.3)), k == 1 ? 1.2 : 0.5, R, G, B, (float) (0.6 * ouvre * part));
        }
        // l'ange géant de lumière, derrière lui, l'épée levée (puis abattue sur la zone)
        if (u.t[1] >= 0) {
            double ta = secondes(u.t[1]);
            float app = (float) lisse(ta / 0.8) * part;
            Vec3 pieds = u.pos.subtract(dir.scale(3)).add(new Vec3(-dir.z, 0, dir.x).scale(-7));
            float lacet = (float) (Math.atan2(-dir.x, dir.z) * Mth.RAD_TO_DEG);
            DessinClair.Pose p = new DessinClair.Pose();
            Vec3 bout;
            if (u.t[3] < 0) {
                p.bras(-3F, 0.15F, u.t[2] >= 0 ? -1.4F : -0.5F, -0.4F).tete(-0.2F);
            } else {
                float k = (float) lisse(secondes(u.t[3]) / 0.2);
                p.bras(Mth.lerp(k, -3F, -1.0F), 0.1F, -1.2F, -0.3F).tete(0.3F).penche(0.25F * k);
            }
            ps.pushPose();
            DessinClair.repere(ps, cam, pieds, lacet, 4F, p.penche);
            DessinClair.pantin(ps, b, p, (int) (120 * app) << 24 | 0xFFE6A0, OR_YEUX, LightTexture.FULL_BRIGHT);
            ModeleVent[] ailes = ModeleVent.lire("lumiere", "ailes3");
            if (ailes.length > 1) {
                ailes[1].dessiner(b, ps.last(), LightTexture.FULL_BRIGHT, 1 / 16F, app);
            }
            ModeleVent[] aur = ModeleVent.lire("lumiere", "aureole");
            if (aur.length > 0) {
                aur[0].dessiner(b, ps.last(), LightTexture.FULL_BRIGHT, 1 / 16F, app);
            }
            ps.popPose();
            Vec3 main = DessinClair.mainPantin(pieds, lacet, 4F, p, false);
            if (u.t[3] < 0) {
                bout = main.add(0, 14 * lisse(ta / 0.6), 0);
            } else {
                bout = u.zone == null ? main.add(dir.scale(14)) : main.lerp(u.zone, lisse(secondes(u.t[3]) / 0.15));
            }
            DessinClair.rayon(ps, b, cam, main, bout, 0.7, R, G, B, app);
            Vec3 garde = main.lerp(bout, 0.08), cote = bout.subtract(main).cross(new Vec3(0, 1, 0.01)).normalize().scale(1.4);
            DessinClair.rayon(ps, b, cam, garde.subtract(cote), garde.add(cote), 0.35, R, G, B, app);
        }
        // le cercle d'or, la pluie d'épées
        if (u.t[2] >= 0 && u.zone != null) {
            double tz = secondes(u.t[2]);
            Vec3 z = u.zone;
            float al = (float) lisse(tz / 0.4) * part;
            VertexConsumer lu = DessinClair.lueur(b);
            double rz = Lumiere.RAYON_ULTI;
            DessinClair.anneauSol(ps, lu, cam, z.add(0, 0.06, 0), rz, 0.35, R, G, B, al);
            DessinClair.anneauSol(ps, lu, cam, z.add(0, 0.06, 0), rz * 0.82, 0.2, R, G, B, al);
            for (int k = 0; k < 12; k++) {
                double a = k * Mth.TWO_PI / 12 + tz * 0.3;
                DessinClair.trait(ps, lu, cam, z.add(Math.cos(a) * rz * 0.2, 0.07, Math.sin(a) * rz * 0.2), z.add(Math.cos(a) * rz * 0.82, 0.07, Math.sin(a) * rz * 0.82), 0.12, R, G, B, al * 0.7F);
                Vec3 pied = z.add(Math.cos(k * Mth.TWO_PI / 12) * rz, 0, Math.sin(k * Mth.TWO_PI / 12) * rz);
                double tombe = lisse((tz - k * 0.05) / 0.3);
                DessinClair.modele(ps, b, cam, "lumiere", "epee", pied.add(0, 12 * (1 - tombe), 0), new Quaternionf(), 1 / 16F * 2.4F, LightTexture.FULL_BRIGHT, al);
            }
            for (int q = 0; q < 30; q++) {
                double arrive = 0.3 + Kag(q, 6) * 1.6, ts = tz - arrive + 0.3;
                if (ts < 0) {
                    continue;
                }
                double a = Kag(q, 7) * Mth.TWO_PI, r = rz * Math.sqrt(Kag(q, 8));
                Vec3 pied = z.add(Math.cos(a) * r, 0, Math.sin(a) * r);
                double h = 18 * (1 - lisse(ts / 0.3));
                Quaternionf rot = new Quaternionf().rotateZ(Mth.PI).rotateY((float) a);
                DessinClair.modele(ps, b, cam, "lumiere", "epee", pied.add(0, h + 1.6, 0), rot, 1 / 16F * 1.8F, LightTexture.FULL_BRIGHT, al);
            }
        }
        // la colonne du Jugement
        if (u.t[3] >= 0 && u.zone != null) {
            double tc = secondes(u.t[3]);
            Vec3 z = u.zone;
            float col = (float) (lisse(tc / 0.12) * (1 - lisse((tc - 1.2) / 1.0))) * part;
            if (col > 0.01) {
                DessinClair.rayon(ps, b, cam, z, z.add(0, 180, 0), 5, R, G, B, col);
                DessinClair.rayon(ps, b, cam, z, z.add(0, 180, 0), 2, 1, 1, 1, col);
            }
            VertexConsumer lu = DessinClair.lueur(b);
            for (int k = 0; k < 3; k++) {
                double tt = tc - k * 0.15;
                if (tt > 0 && tt < 1.6) {
                    DessinClair.anneauSol(ps, lu, cam, z.add(0, 0.1, 0), 3 + 24 * lisse(tt / 1.2), 0.7 - k * 0.15, R, G, B, (float) (1 - tt / 1.6));
                }
            }
            if (tc < 0.8) {
                DessinClair.boule(ps, DessinClair.voile(b), cam, z, 12 * lisse(tc / 0.2), (int) (110 * (1 - tc / 0.8)) << 24 | 0xFFFAE6, LightTexture.FULL_BRIGHT);
            }
        }
    }

    // ================================================================ les touches et le panneau

    private static long pretDroit, pretAccroupi, pretAccDroit, pretAccGauche, pretTech;
    private static boolean ultiFaite, accroupiAvant, clicPendant;
    private static long accroupiDebut;

    private static long dans(int ticks) {
        return System.nanoTime() + ticks * 50_000_000L;
    }

    private static void envoyer(int action) {
        PacketDistributor.sendToServer(new Lumiere.Touche(action));
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

    private static boolean ultiEnCours() {
        Minecraft mc = Minecraft.getInstance();
        Ulti u = mc.player == null ? null : ULTIS.get(mc.player.getUUID());
        return u != null && u.t[0] >= 0 && u.t[4] < 0 && secondes(u.t[0]) < 9;
    }

    /** La touche technique (², lue dans ClientSharingan) : le chakram, au niveau 3. */
    public static boolean technique() {
        Etat e = moi();
        if (e == null) {
            return false;
        }
        if (e.niveau >= 3 && !ultiEnCours() && System.nanoTime() >= pretTech) {
            pretTech = dans(Lumiere.RECHARGE_CHAKRAM);
            envoyer(ClairObscur.TECHNIQUE);
        }
        return true;
    }

    /** La touche d'ulti (J, lue dans ClientHachibi4) : le Jugement, au niveau 3, une fois par éveil. */
    public static void toucheUlti() {
        Etat e = moi();
        if (e == null || e.niveau < 3 || ultiEnCours()) {
            return;
        }
        ultiFaite = true;
        envoyer(ClairObscur.ULTI);
    }

    private static void ecran(GuiGraphics g) {
        Minecraft mc = Minecraft.getInstance();
        // le flash blanc
        if (flash >= 0 && secondes(flash) < 0.6) {
            int a = (int) (255 * flashForce * (1 - secondes(flash) / 0.6));
            g.fill(0, 0, g.guiWidth(), g.guiHeight(), a << 24 | 0xFFFAEA);
        }
        Etat e = moi();
        if (e == null || mc.options.hideGui) {
            return;
        }
        int n = e.niveau;
        boolean deux = Lumiere.NOM2.equals(e.pouvoir), trois = Lumiere.NOM3.equals(e.pouvoir);
        String eveil = n == 1 && (deux || trois) ? "Niveau 2" : n == 2 && trois ? "Niveau 3" : "Rendormir";
        String acc = Clavier.accroupi();
        long now = System.nanoTime();
        List<String[]> lignes = new ArrayList<>();
        List<Boolean> gris = new ArrayList<>();
        lignes.add(new String[]{Clavier.gauche(), n >= 3 ? "Coup de lumière (feu sacré)" : n == 2 ? "Coup de lumière (repousse)" : "Coup de lumière (surbrillance)"});
        gris.add(false);
        lignes.add(new String[]{Clavier.droite(), (n >= 3 ? "Rayon solaire" : n == 2 ? "Lances de lumière" : "Flèche de lumière") + reste(pretDroit)});
        gris.add(pretDroit > now);
        lignes.add(new String[]{acc, (n >= 3 ? "Vitesse de la lumière" : n == 2 ? "Envol" : "Éclat aveuglant") + reste(pretAccroupi)});
        gris.add(pretAccroupi > now);
        if (n >= 2) {
            lignes.add(new String[]{acc + " + " + Clavier.droite(), "Pluie de plumes" + reste(pretAccDroit)});
            gris.add(pretAccDroit > now);
        }
        if (n >= 3) {
            lignes.add(new String[]{acc + " + " + Clavier.gauche(), "Croix de lumière" + reste(pretAccGauche)});
            gris.add(pretAccGauche > now);
            lignes.add(new String[]{Clavier.nom(ClientSharingan.SUSANO), "Chakram" + reste(pretTech)});
            gris.add(pretTech > now);
            lignes.add(new String[]{Clavier.nom(fr.cabane.client.hachibi4.ClientHachibi4.ULTI), ultiFaite ? "Le Jugement (déjà lancé)" : "Le Jugement"});
            gris.add(ultiFaite);
        }
        lignes.add(new String[]{Clavier.nom(ClientSusano.EVEIL), eveil});
        gris.add(false);
        boolean[] g2 = new boolean[gris.size()];
        for (int i = 0; i < g2.length; i++) {
            g2[i] = gris.get(i);
        }
        PanneauPouvoir.dessiner(g, mc.font, "Lumière" + (n >= 2 ? "  ·  niv. " + n : ""), 0xFFFFD27A, lignes.toArray(new String[0][]), g2);
    }

    // ================================================================ les transformations et les étincelles (à chaque tick)

    private static void ambiance(Minecraft mc, Player j, Etat e) {
        Level level = mc.level;
        double t1 = e.debut1 < 0 ? 99 : secondes(e.debut1), t2 = e.debut2 < 0 ? 99 : secondes(e.debut2), t3 = e.debut3 < 0 ? 99 : secondes(e.debut3);
        Vec3 p = j.position();
        // l'éveil 1 : un rayon du ciel tombe sur lui
        if (t1 < 1.2 && e.niveau == 1) {
            for (int k = 0; k < 8; k++) {
                level.addParticle(ParticleTypes.END_ROD, p.x + HASARD.nextGaussian() * 0.3, p.y + HASARD.nextDouble() * 12, p.z + HASARD.nextGaussian() * 0.3, 0, -0.3, 0);
            }
        }
        // l'éveil 2 : les plumes volent quand les ailes s'ouvrent
        if (t2 < 1.2 && e.niveau == 2) {
            etincelles(level, p.add(0, 1.4, 0), 6, 1.2);
        }
        // l'éveil 3 : une colonne de lumière, l'or l'habille de la tête aux pieds
        if (t3 < 1.6 && e.niveau >= 3) {
            for (int k = 0; k < 10; k++) {
                double a = HASARD.nextDouble() * Mth.TWO_PI;
                level.addParticle(k % 2 == 0 ? ParticleTypes.END_ROD : OR, p.x + Math.cos(a) * 1.2, p.y + 2.2 - t3 * 1.4, p.z + Math.sin(a) * 1.2, 0, 0.02, 0);
            }
        }
        if (HASARD.nextInt(e.niveau >= 3 ? 2 : 4) == 0 && !j.isInvisible()) {
            level.addParticle(OR, p.x + HASARD.nextGaussian() * 0.45, p.y + HASARD.nextDouble() * 2, p.z + HASARD.nextGaussian() * 0.45, 0, 0.04, 0);
        }
    }

    // ================================================================ les événements

    @EventBusSubscriber(modid = Cabane.MODID, value = Dist.CLIENT)
    public static final class Couches {
        @SubscribeEvent
        public static void enregistrer(RegisterGuiLayersEvent event) {
            event.registerAboveAll(Cabane.id("lumiere"), (g, delta) -> ecran(g));
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

    /** Le rayon de l'éveil 1 et de l'éveil 3 : un trait de lumière du ciel jusqu'à lui (dessiné dans le monde). */
    private static void rayonsEveil(PoseStack ps, MultiBufferSource b, Vec3 cam, Level level, float partiel) {
        for (Map.Entry<UUID, Etat> en : ETATS.entrySet()) {
            Etat e = en.getValue();
            if (e.fin >= 0 || !(level.getPlayerByUUID(en.getKey()) instanceof Player j)) {
                continue;
            }
            double te = e.niveau == 1 ? secondes(e.debut1) : e.niveau >= 3 ? secondes(e.debut3) : 99;
            if (te > 1.6) {
                continue;
            }
            float al = (float) (lisse(te / 0.15) * (1 - lisse((te - 1.0) / 0.6)));
            Vec3 p = j.getPosition(partiel);
            DessinClair.rayon(ps, b, cam, p, p.add(0, 60, 0), e.niveau >= 3 ? 1.6 : 0.9, R, G, B, al);
            DessinClair.anneauSol(ps, DessinClair.lueur(b), cam, p.add(0, 0.05, 0), 1.2, 0.2, R, G, B, al);
            if (e.niveau >= 3) {
                // l'anneau d'or qui descend de la tête aux pieds
                double h = 2.1 * (1 - lisse(te / 1.0));
                DessinClair.anneau(ps, DessinClair.lueur(b), cam, p.add(0, h, 0), 0.75, 0.08, new Vec3(1, 0, 0), new Vec3(0, 0, 1), R, G, B, al);
            }
        }
    }

    @EventBusSubscriber(modid = Cabane.MODID, value = Dist.CLIENT)
    public static final class Evenements {
        @SubscribeEvent
        public static void tick(ClientTickEvent.Post event) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.level == null || mc.player == null) {
                ETATS.clear();
                ANIMS.clear();
                ULTIS.clear();
                return;
            }
            Etat moi = moi();
            boolean accroupi = mc.options.keyShift.isDown();
            if (moi != null && accroupi && !accroupiAvant) {
                accroupiDebut = System.nanoTime();
                clicPendant = false;
            }
            if (moi != null && !accroupi && accroupiAvant && !clicPendant && mc.screen == null && secondes(accroupiDebut) < 0.6 && !ultiEnCours()
                    && System.nanoTime() >= pretAccroupi) {
                pretAccroupi = dans(moi.niveau >= 3 ? Lumiere.RECHARGE_VITESSE : moi.niveau == 2 ? Lumiere.RECHARGE_ENVOL : Lumiere.RECHARGE_FLASH);
                envoyer(ClairObscur.ACCROUPI);
            }
            accroupiAvant = accroupi;
            ETATS.values().removeIf(e -> e.fin >= 0 && secondes(e.fin) > 1);
            ANIMS.removeIf(a -> a.age() > 4);
            ULTIS.values().removeIf(u -> u.t[0] < 0 || secondes(u.t[0]) > 12 || u.t[4] >= 0 && secondes(u.t[4]) > 1.5);
            for (Map.Entry<UUID, Etat> en : ETATS.entrySet()) {
                Player j = mc.level.getPlayerByUUID(en.getKey());
                if (j != null && en.getValue().fin < 0 && j.distanceTo(mc.player) < 64) {
                    ambiance(mc, j, en.getValue());
                }
            }
        }

        @SubscribeEvent(priority = net.neoforged.bus.api.EventPriority.HIGHEST)
        public static void clic(InputEvent.InteractionKeyMappingTriggered event) {
            Minecraft mc = Minecraft.getInstance();
            Etat moi = moi();
            if (moi == null) {
                return;
            }
            boolean accroupi = mc.options.keyShift.isDown();
            if (event.isUseItem()) {
                event.setCanceled(true);
                event.setSwingHand(false);
                if (ultiEnCours()) {
                    return;
                }
                if (accroupi && moi.niveau >= 2) {
                    clicPendant = true;
                    if (System.nanoTime() >= pretAccDroit) {
                        pretAccDroit = dans(Lumiere.RECHARGE_PLUMES);
                        mc.player.swing(InteractionHand.MAIN_HAND);
                        envoyer(ClairObscur.ACC_DROIT);
                    }
                } else if (System.nanoTime() >= pretDroit) {
                    clicPendant = accroupi || clicPendant;
                    pretDroit = dans(moi.niveau >= 3 ? Lumiere.RECHARGE_RAYON : moi.niveau == 2 ? Lumiere.RECHARGE_LANCES : Lumiere.RECHARGE_FLECHE);
                    mc.player.swing(InteractionHand.MAIN_HAND);
                    envoyer(ClairObscur.DROIT);
                }
            } else if (event.isAttack() && accroupi && moi.niveau >= 3) {
                event.setCanceled(true);
                clicPendant = true;
                if (!ultiEnCours() && System.nanoTime() >= pretAccGauche) {
                    pretAccGauche = dans(Lumiere.RECHARGE_CROIX);
                    mc.player.swing(InteractionHand.MAIN_HAND);
                    envoyer(ClairObscur.ACC_GAUCHE);
                }
            }
        }

        @SubscribeEvent
        public static void monde(RenderLevelStageEvent event) {
            if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES || Minecraft.getInstance().level == null) {
                return;
            }
            if (ETATS.isEmpty() && ANIMS.isEmpty() && ULTIS.isEmpty()) {
                return;
            }
            float partiel = event.getPartialTick().getGameTimeDeltaPartialTick(false);
            Vec3 cam = event.getCamera().getPosition();
            Minecraft mc = Minecraft.getInstance();
            rayonsEveil(event.getPoseStack(), mc.renderBuffers().bufferSource(), cam, mc.level, partiel);
            ClientLumiere.monde(event.getPoseStack(), cam, partiel);
        }

        private Evenements() {
        }
    }

    private ClientLumiere() {
    }
}
