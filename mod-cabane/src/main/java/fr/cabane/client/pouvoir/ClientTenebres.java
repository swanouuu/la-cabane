package fr.cabane.client.pouvoir;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import fr.cabane.Cabane;
import fr.cabane.pouvoir.ClairObscur;
import fr.cabane.pouvoir.Pouvoirs;
import fr.cabane.pouvoir.Tenebres;
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
import net.minecraft.core.particles.BlockParticleOption;
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
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
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
import net.neoforged.neoforge.network.PacketDistributor;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Ce qu'on voit des Ténèbres (voir {@link Tenebres} et docs/tenebres-lumiere) : les yeux violets qui brillent, la fumée
 * noire aux mains (niveau 1) ; son ombre, un double noir plus grand qui le suit et frappe avec lui, une aura noire
 * (niveau 2) ; la tenue (le skin de l'utilisateur, adapté), la couronne d'obsidienne et deux orbes (niveau 3). Et les
 * coups : la lame, la plongée, les mains, la prison, la ligne de piques, les trois ombres, les chaînes, la faux, et
 * l'Éclipse (le ciel noir, le géant d'ombre, le trou noir, l'implosion).
 */
public final class ClientTenebres {
    private static final RandomSource HASARD = RandomSource.create();
    static final ResourceLocation TENUE = Cabane.id("textures/pouvoir/tenebres3.png");
    private static final int VIOLET_YEUX = 0xC890FF, NOIR_OMBRE = 0xFF0C0614;
    private static final DustParticleOptions VIOLET = new DustParticleOptions(new Vector3f(0.6F, 0.25F, 1F), 1.2F);
    private static final DustParticleOptions NOIRE = new DustParticleOptions(new Vector3f(0.04F, 0.02F, 0.06F), 0.9F);

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
        long debut1 = -1, debut2 = -1, debut3 = -1, fin = -1;
        int niveauFin;
        /** Le dernier coup du clic gauche (son ombre frappe avec lui). */
        long coup = -1;
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
        if (p.annonce() && mc.level != null && mc.level.getPlayerByUUID(p.joueur()) instanceof Player j) {
            mc.level.playLocalSound(j.getX(), j.getY(), j.getZ(), SoundEvents.SCULK_CATALYST_BLOOM, SoundSource.PLAYERS, 1.6F, 0.6F, false);
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

    /** L'ulti d'un joueur : quand chaque phase a commencé, où il regarde, où est la zone. */
    static final class Ulti {
        long[] t = {-1, -1, -1, -1, -1};
        Vec3 pos, dir, zone, depart;
    }

    private static final Map<UUID, Ulti> ULTIS = new HashMap<>();

    public static void effet(Tenebres.Effet p) {
        Minecraft mc = Minecraft.getInstance();
        Level level = mc.level;
        if (level == null) {
            return;
        }
        Vec3 pos = vec(p.pos()), dir = vec(p.dir());
        if (p.sorte() == Tenebres.E_ULTI) {
            Ulti u = ULTIS.computeIfAbsent(p.joueur(), k -> new Ulti());
            if (p.phase() == 0) {
                u.t = new long[]{System.nanoTime(), -1, -1, -1, -1};
                u.pos = pos;
                u.dir = dir;
                u.zone = null;
            }
            u.t[p.phase()] = System.nanoTime();
            if (p.phase() == 2) {
                u.zone = pos;
                u.depart = dir;
            }
            if (p.phase() == 3) {
                u.zone = pos;
                secouer(mc, pos, 2.2F, 50);
                for (int k = 0; k < 120; k++) {
                    level.addParticle(k % 2 == 0 ? NOIRE : VIOLET, pos.x, pos.y + 4, pos.z, HASARD.nextGaussian() * 1.2, HASARD.nextGaussian() * 0.8, HASARD.nextGaussian() * 1.2);
                }
            }
            if (p.phase() == 1) {
                secouer(mc, pos, 0.8F, 40);
            }
            return;
        }
        Etat e = ETATS.get(p.joueur());
        if (p.sorte() == Tenebres.E_COUP && e != null) {
            e.coup = System.nanoTime();
        }
        ANIMS.add(new Anim(p.joueur(), p.sorte(), p.phase(), pos, dir, p.n(), System.nanoTime()));
        switch (p.sorte()) {
            case Tenebres.E_LAME_FIN -> {
                fumee(level, pos, 24, 0.5);
                ANIMS.removeIf(a -> a.sorte() == Tenebres.E_LAME && a.n() == p.n());
            }
            case Tenebres.E_PLONGE -> {
                fumee(level, pos.add(0, 0.3, 0), 40, 0.7);
                if (p.phase() == 1 && p.n() == 1) {
                    fumee(level, pos.add(0, 1, 0), 120, 2.4);
                    secouer(mc, pos, 1.2F, 16);
                }
            }
            case Tenebres.E_FAUX -> secouer(mc, pos, 0.8F, 14);
            case Tenebres.E_VOL -> {
                for (int k = 0; k < 16; k++) {
                    Vec3 d = dir.subtract(pos).scale(0.12);
                    level.addParticle(k % 2 == 0 ? new DustParticleOptions(new Vector3f(0.9F, 0.1F, 0.15F), 1F) : VIOLET, pos.x, pos.y, pos.z, d.x, d.y, d.z);
                }
            }
            default -> { }
        }
    }

    static void fumee(Level level, Vec3 p, int n, double r) {
        for (int k = 0; k < n; k++) {
            double x = p.x + HASARD.nextGaussian() * r, y = p.y + HASARD.nextDouble() * r, z = p.z + HASARD.nextGaussian() * r;
            level.addParticle(k % 3 == 0 ? VIOLET : k % 3 == 1 ? NOIRE : ParticleTypes.SMOKE, x, y, z, 0, 0.03 + HASARD.nextDouble() * 0.04, 0);
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

    // ================================================================ la tenue du niveau 3

    private static final Map<PlayerSkin, PlayerSkin> TENUES = new HashMap<>();

    /** Appelé à chaque {@code getSkin()} (AbstractClientPlayerMixin) : au niveau 3, la tenue des Ténèbres. */
    public static PlayerSkin peau(AbstractClientPlayer joueur, PlayerSkin skin) {
        Etat e = ETATS.get(joueur.getUUID());
        if (e == null || e.fin >= 0 || e.niveau < 3 || e.debut3 < 0 || secondes(e.debut3) < 0.9) {
            return skin;
        }
        return TENUES.computeIfAbsent(skin, s -> new PlayerSkin(TENUE, s.textureUrl(), s.capeTexture(), s.elytraTexture(), PlayerSkin.Model.WIDE, s.secure()));
    }

    // ================================================================ sur le joueur

    /** Les pavés des parties du joueur (comme PlayerModel, bras larges) : coin et taille, en pixels. */
    private static final float[][] BOITES = {{-4, -8, -4, 8, 8, 8}, {-4, 0, -2, 8, 12, 4}, {-3, -2, -2, 4, 12, 4}, {-1, -2, -2, 4, 12, 4},
            {-2, 0, -2, 4, 12, 4}, {-2, 0, -2, 4, 12, 4}};

    /** Une coque transparente autour de chaque partie du corps (l'aura), de m pixels. */
    static void aura(PoseStack ps, MultiBufferSource b, ModelPart[] parties, float m, int argb) {
        VertexConsumer c = DessinClair.voile(b);
        for (int i = 0; i < 6; i++) {
            float[] q = BOITES[i];
            ps.pushPose();
            parties[i].translateAndRotate(ps);
            DessinClair.pave(ps, c, q[0] - m, q[1] - m, q[2] - m, q[3] + 2 * m, q[4] + 2 * m, q[5] + 2 * m, argb, LightTexture.FULL_BRIGHT);
            ps.popPose();
        }
    }

    /** Les yeux qui brillent (rgb), sur la tête. */
    static void yeux(PoseStack ps, MultiBufferSource b, ModelPart tete, int rgb) {
        ps.pushPose();
        tete.translateAndRotate(ps);
        VertexConsumer c = DessinClair.plein(b);
        DessinClair.pave(ps, c, -3, -4.05F, -4.12F, 2, 1, 0.1F, 0xFF000000 | rgb, LightTexture.FULL_BRIGHT);
        DessinClair.pave(ps, c, 1, -4.05F, -4.12F, 2, 1, 0.1F, 0xFF000000 | rgb, LightTexture.FULL_BRIGHT);
        ps.popPose();
    }

    private static void piece(String dossier, String nom, PoseStack ps, MultiBufferSource b, int lumiere, ModelPart[] parties, float alpha) {
        ModeleVent[] l = ModeleVent.lire(dossier, nom);
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
            yeux(ps, buffers, m.head, VIOLET_YEUX);
            if (niveau >= 2) {
                aura(ps, buffers, parties, 1.1F, (int) ((niveau >= 3 ? 40 : 80) * efface) << 24 | 0x14081E);
                aura(ps, buffers, parties, 2.4F, (int) ((niveau >= 3 ? 26 : 36) * efface) << 24 | 0x5A2896);
            }
            if (niveau >= 3 && e.debut3 >= 0 && secondes(e.debut3) > 0.9) {
                piece("tenebres", "couronne", ps, buffers, lumiere, parties, efface);
                // deux orbes noirs tournent autour de lui
                double t = System.nanoTime() / 1e9 + joueur.getId();
                for (int k = 0; k < 2; k++) {
                    double a = t * 1.4 + k * Math.PI;
                    ps.pushPose();
                    ps.translate(Math.cos(a) * 0.85, -0.2 - 0.25 * Math.sin(t * 2 + k), Math.sin(a) * 0.85);
                    ps.mulPose(Axis.YP.rotation((float) t));
                    ModeleVent[] o = ModeleVent.lire("tenebres", "orbe");
                    if (o.length > 0) {
                        o[0].dessiner(buffers, ps.last(), lumiere, 1 / 16F, efface);
                    }
                    ps.popPose();
                }
            }
        }
    }

    /** Les poses : la tête basse et les bras ouverts à l'éveil 1, les bras levés à l'éveil 3 et à l'ulti, le bras tendu pour viser. */
    public static void poser(PlayerModel<?> m, Player joueur) {
        Etat e = ETATS.get(joueur.getUUID());
        if (e == null || e.fin >= 0) {
            return;
        }
        boolean change = false;
        double t1 = e.debut1 < 0 ? 99 : secondes(e.debut1), t3 = e.debut3 < 0 ? 99 : secondes(e.debut3);
        if (t1 < 1.6 && e.niveau == 1) {
            float k = (float) (lisse(t1 / 0.3) * (1 - lisse((t1 - 1.3) / 0.3)));
            m.head.xRot = Mth.lerp(k, m.head.xRot, 0.6F);
            m.rightArm.zRot = Mth.lerp(k, m.rightArm.zRot, 0.6F);
            m.leftArm.zRot = Mth.lerp(k, m.leftArm.zRot, -0.6F);
            change = true;
        }
        if (t3 < 1.6 && e.niveau >= 3) {
            float k = (float) (lisse(t3 / 0.3) * (1 - lisse((t3 - 1.3) / 0.3)));
            m.rightArm.xRot = Mth.lerp(k, m.rightArm.xRot, -2.6F);
            m.leftArm.xRot = Mth.lerp(k, m.leftArm.xRot, -2.6F);
            m.rightArm.zRot = Mth.lerp(k, m.rightArm.zRot, 0.4F);
            m.leftArm.zRot = Mth.lerp(k, m.leftArm.zRot, -0.4F);
            m.head.xRot = Mth.lerp(k, m.head.xRot, -0.5F);
            change = true;
        }
        Ulti u = ULTIS.get(joueur.getUUID());
        if (u != null && u.t[0] >= 0 && u.t[4] < 0) {
            double tu = secondes(u.t[0]);
            float k = (float) lisse(tu / 0.4);
            m.rightArm.xRot = Mth.lerp(k, m.rightArm.xRot, u.t[2] < 0 ? -2.9F : -1.6F);
            m.rightArm.yRot = 0;
            change = true;
        }
        for (Anim a : ANIMS) {
            if (a.qui().equals(joueur.getUUID()) && a.age() < 0.4 && a.sorte() != Tenebres.E_COUP && a.sorte() != Tenebres.E_VOL) {
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

    private static Vec3 horizontal(Vec3 d) {
        Vec3 h = new Vec3(d.x, 0, d.z);
        return h.lengthSqr() < 1e-6 ? new Vec3(0, 0, 1) : h.normalize();
    }

    /** La pose de son ombre : elle copie ses coups (le bras droit qui frappe) et sa marche. */
    private static DessinClair.Pose poseOmbre(Player j, float partiel, Etat e) {
        DessinClair.Pose p = new DessinClair.Pose();
        float marche = j.walkAnimation.position(partiel), vitesse = Math.min(1, j.walkAnimation.speed(partiel));
        p.jambes(Mth.cos(marche * 0.6662F) * 1.2F * vitesse, Mth.cos(marche * 0.6662F + Mth.PI) * 1.2F * vitesse);
        p.bras(Mth.cos(marche * 0.6662F + Mth.PI) * vitesse - 0.3F, 0.15F, Mth.cos(marche * 0.6662F) * vitesse - 0.2F, -0.15F);
        float coup = j.getAttackAnim(partiel);
        if (e.coup >= 0 && secondes(e.coup) < 0.35) {
            coup = (float) Math.sin(secondes(e.coup) / 0.35 * Math.PI);
        }
        if (coup > 0) {
            p.brasDX = -1.2F - 1.4F * Mth.sin(coup * Mth.PI);
            p.brasDZ = 0.3F;
        }
        return p;
    }

    private static void monde(PoseStack ps, Vec3 cam, float partiel) {
        Minecraft mc = Minecraft.getInstance();
        Level level = mc.level;
        MultiBufferSource.BufferSource b = mc.renderBuffers().bufferSource();
        // son ombre (niveaux 2 et 3) : un double noir derrière lui, à sa droite, qui le suit et frappe avec lui
        for (Map.Entry<UUID, Etat> en : ETATS.entrySet()) {
            Etat e = en.getValue();
            int niveau = e.fin >= 0 ? e.niveauFin : e.niveau;
            if (niveau < 2 || !(level.getPlayerByUUID(en.getKey()) instanceof Player j) || j.isInvisible()) {
                continue;
            }
            float efface = e.fin >= 0 ? (float) Math.max(0, 1 - secondes(e.fin) / 0.5) : 1;
            if (efface <= 0) {
                continue;
            }
            double t2 = e.debut2 < 0 ? 99 : secondes(e.debut2);
            // à l'éveil 2, il sort du sol
            double sort = e.niveau == 2 ? lisse((t2 - 0.3) / 1.2) : 1;
            float lacet = Mth.lerp(partiel, j.yBodyRotO, j.yBodyRot);
            float k = niveau >= 3 ? 1.55F : 1.3F;
            Ulti u = ULTIS.get(en.getKey());
            if (u != null && u.t[1] >= 0 && u.t[4] < 0) {
                continue;
            }
            Vec3 pieds = j.getPosition(partiel).add(corps(lacet, -1.45, 0, -0.35)).add(0, -(1 - sort) * 2.2 * k, 0);
            DessinClair.Pose p = poseOmbre(j, partiel, e);
            ps.pushPose();
            DessinClair.repere(ps, cam, pieds, lacet + 8, k, 0);
            DessinClair.pantin(ps, b, p, efface < 1 ? (int) (efface * 250) << 24 | 0x0C0614 : NOIR_OMBRE, VIOLET_YEUX, lumiere(level, pieds.add(0, 1, 0)));
            ps.popPose();

        }
        for (Anim a : ANIMS) {
            dessiner(ps, b, cam, level, a, partiel);
        }
        for (Map.Entry<UUID, Ulti> en : ULTIS.entrySet()) {
            ulti(ps, b, cam, level, en.getKey(), en.getValue(), partiel);
        }
        b.endBatch();
    }

    /** Un décalage dans le repère de son corps (x à sa gauche, z devant), en blocs. */
    static Vec3 corps(float lacetDeg, double x, double y, double z) {
        float a = -lacetDeg * Mth.DEG_TO_RAD;
        double c = Mth.cos(a), s = Mth.sin(a);
        return new Vec3(x * c + z * s, y, -x * s + z * c);
    }

    private static Entity entite(Level level, int id) {
        return id < 0 ? null : level.getEntity(id);
    }

    private static void dessiner(PoseStack ps, MultiBufferSource b, Vec3 cam, Level level, Anim a, float partiel) {
        double t = a.age();
        switch (a.sorte()) {
            case Tenebres.E_LAME -> {
                Vec3 p = a.pos().add(a.dir().scale(Math.min(Tenebres.PORTEE_LAME, t * 20 * Tenebres.VITESSE_LAME)));
                Quaternionf r = DessinClair.vers(a.dir()).rotateZ((float) (0.6));
                DessinClair.modele(ps, b, cam, "tenebres", "lame", p, r, 0.19F, LightTexture.FULL_BRIGHT, 1);
                DessinClair.trait(ps, DessinClair.lueur(b), cam, p.subtract(a.dir().scale(3)), p, 0.6, 0.55F, 0.2F, 1F, 0.5F);
                if (HASARD.nextInt(2) == 0) {
                    level.addParticle(NOIRE, p.x, p.y, p.z, 0, 0.01, 0);
                }
            }
            case Tenebres.E_PLONGE -> {
                float al = (float) (1 - lisse((t - 0.8) / 0.5));
                double r = (a.phase() == 0 ? 1.3 : 1.5) * lisse(t / 0.15);
                VertexConsumer v = DessinClair.voile(b);
                DessinClair.disque(ps, v, cam, a.pos().add(0, 0.02, 0), r, (int) (230 * al) << 24 | 0x08040C, true);
                DessinClair.anneauSol(ps, DessinClair.lueur(b), cam, a.pos().add(0, 0.03, 0), r, 0.12, 0.6F, 0.25F, 1F, 0.8F * al);
                if (a.phase() == 1 && a.n() == 1 && t < 0.7) {
                    double re = 4.5 * lisse(t / 0.25);
                    DessinClair.boule(ps, DessinClair.voile(b), cam, a.pos().add(0, 0.5, 0), re, (int) (110 * (1 - t / 0.7)) << 24 | 0x3C1464, LightTexture.FULL_BRIGHT);
                    DessinClair.anneauSol(ps, DessinClair.lueur(b), cam, a.pos().add(0, 0.05, 0), re, 0.3, 0.6F, 0.25F, 1F, (float) (1 - t / 0.7));
                }
            }
            case Tenebres.E_MAINS, Tenebres.E_PRISON -> {
                if (a.phase() == 1) {
                    return;
                }
                boolean prison = a.sorte() == Tenebres.E_PRISON;
                double duree = (prison ? Tenebres.DUREE_PRISON : Tenebres.DUREE_MAINS) / 20.0;
                double monte = lisse(t / 0.3), part = 1 - lisse((t - duree) / 0.3);
                if (part <= 0) {
                    return;
                }
                Vec3 c = a.pos();
                VertexConsumer v = DessinClair.voile(b);
                DessinClair.disque(ps, v, cam, c.add(0, 0.02, 0), 1.6, (int) (220 * part) << 24 | 0x08040C, true);
                VertexConsumer lu = DessinClair.lueur(b);
                DessinClair.anneauSol(ps, lu, cam, c.add(0, 0.03, 0), 1.6, 0.12, 0.6F, 0.25F, 1F, (float) part);
                VertexConsumer pl = DessinClair.plein(b);
                int l = lumiere(level, c.add(0, 1, 0));
                if (prison) {
                    // douze barreaux noirs qui sortent de la flaque et se referment en dôme au-dessus de lui
                    for (int q = 0; q < 12; q++) {
                        double ang = q * Mth.TWO_PI / 12;
                        Vec3 prec = null;
                        for (int s = 0; s <= 22; s++) {
                            double u = s / 22.0;
                            if (u > monte) {
                                break;
                            }
                            double h = u < 0.65 ? 0 : (u - 0.65) / 0.35;
                            double rr = u < 0.65 ? 1.25 : 1.25 * Math.cos(h * Math.PI / 2) + 0.05, y = u < 0.65 ? u / 0.65 * 2.6 : 2.6 + Math.sin(h * Math.PI / 2) * 1.1;
                            Vec3 p = c.add(Math.cos(ang) * rr, y, Math.sin(ang) * rr);
                            DessinClair.cube(ps, pl, cam, p, 0.16, s % 2 == 0 ? 0xFF0C0614 : 0xFF241236, l);
                            if (prec != null) {
                                DessinClair.trait(ps, lu, cam, prec, p, 0.05, 0.6F, 0.3F, 1F, (float) (0.7 * part));
                            }
                            prec = p;
                        }
                    }
                    for (double y : new double[]{0.05, 1.3, 2.6}) {
                        DessinClair.anneau(ps, lu, cam, c.add(0, y, 0), 1.25, 0.06, new Vec3(1, 0, 0), new Vec3(0, 0, 1), 0.6F, 0.25F, 1F, (float) (0.8 * part * monte));
                    }
                } else {
                    // deux mains noires sortent de la flaque, cinq doigts chacune, qui se referment sur lui
                    Entity cible = entite(level, a.n());
                    Vec3 corpsC = cible != null ? cible.position() : c;
                    for (int s = -1; s <= 1; s += 2) {
                        double h = s < 0 ? 0.9 : 1.6;
                        for (int f = 0; f < 5; f++) {
                            double ang = s * 0.4 + (f - 2) * 0.55 + (s > 0 ? Math.PI : 0);
                            Vec3 pied = c.add(Math.cos(ang) * 1.3, 0, Math.sin(ang) * 1.1);
                            Vec3 mi = corpsC.add(Math.cos(ang) * 1.05, h - 0.1, Math.sin(ang) * 0.85);
                            Vec3 bout = corpsC.add(Math.cos(ang) * 0.35, h + 0.2 - Math.abs(f - 2) * 0.06, Math.sin(ang) * 0.3);
                            Vec3 prec = pied;
                            for (int k = 1; k <= 14; k++) {
                                double u = k / 14.0;
                                if (u > monte) {
                                    break;
                                }
                                Vec3 p = u < 0.6 ? pied.lerp(mi, u / 0.6) : mi.lerp(bout, (u - 0.6) / 0.4);
                                DessinClair.cube(ps, pl, cam, p, 0.24 - 0.1 * u, k % 2 == 0 ? 0xFF0C0614 : 0xFF1C0E2A, l);
                                DessinClair.trait(ps, lu, cam, prec, p, 0.04, 0.6F, 0.3F, 1F, 0.6F);
                                prec = p;
                            }
                            if (monte >= 1) {
                                DessinClair.cube(ps, pl, cam, bout, 0.08, 0xFFD8B0FF, LightTexture.FULL_BRIGHT);
                            }
                        }
                    }
                }
            }
            case Tenebres.E_PIQUES -> {
                for (int k = 0; k <= a.n(); k++) {
                    double sortie = (t * 20 * 2 - k) / 2;
                    if (sortie <= 0) {
                        continue;
                    }
                    double kk = lisse(sortie) * (1 - lisse((t - 1.4) / 0.4));
                    if (kk <= 0.01) {
                        continue;
                    }
                    Vec3 p0 = a.pos().add(a.dir().scale(k));
                    double y = ClientKaguya.sol(level, p0);
                    for (int s = -1; s <= 1; s += 2) {
                        double h = (0.6 + k * 0.08) * kk;
                        Vec3 pied = new Vec3(p0.x, y - 0.1, p0.z).add(new Vec3(-a.dir().z, 0, a.dir().x).scale(s * (0.25 + 0.2 * Math.sin(k * 3.1))));
                        ps.pushPose();
                        ps.translate(pied.x - cam.x, pied.y - cam.y, pied.z - cam.z);
                        ps.mulPose(Axis.YP.rotation(k * 1.3F + s));
                        ps.mulPose(Axis.ZP.rotation(s * 0.12F));
                        ps.scale(1, (float) (h * 1.6), 1);
                        ModeleVent[] l = ModeleVent.lire("tenebres", "pique");
                        if (l.length > 0) {
                            l[0].dessiner(b, ps.last(), lumiere(level, pied.add(0, 1, 0)), 1 / 16F * 1.6F, 1);
                        }
                        ps.popPose();
                    }
                }
            }
            case Tenebres.E_OMBRES -> {
                Entity cible = entite(level, a.n());
                Vec3 c = cible != null ? cible.getPosition(partiel) : a.pos();
                if (t > 1.6) {
                    return;
                }
                for (int q = 0; q < 3; q++) {
                    double ang = 0.3 + q * Mth.TWO_PI / 3;
                    double sort = lisse((t - q * 0.1) / 0.3), part = 1 - lisse((t - 1.3) / 0.3);
                    if (sort <= 0 || part <= 0) {
                        continue;
                    }
                    Vec3 pieds = c.add(Math.cos(ang) * 1.6, -(1 - sort) * 2.2, Math.sin(ang) * 1.6);
                    float lacet = (float) (Math.atan2(c.x - pieds.x, c.z - pieds.z) * -Mth.RAD_TO_DEG);
                    double frappe = (t - 0.5 - q * 0.25) / 0.25;
                    DessinClair.Pose p = new DessinClair.Pose().bras(frappe > 0 && frappe < 1 ? (float) (-2.6 + 2.2 * frappe) : -2.6F, 0.2F, -0.4F, -0.2F);
                    ps.pushPose();
                    DessinClair.repere(ps, cam, pieds, lacet, 1.1F, 0);
                    DessinClair.pantin(ps, b, p, (int) (part * 250) << 24 | 0x0C0614, VIOLET_YEUX, lumiere(level, c.add(0, 1, 0)));
                    ps.popPose();
                    if (frappe > 0 && frappe < 1) {
                        croissant(ps, b, cam, c.add(0, 1, 0), horizontal(c.subtract(pieds)), 1.2, (float) (1 - frappe), 0.55F, 0.2F, 1F);
                    }
                }
                DessinClair.disque(ps, DessinClair.voile(b), cam, c.add(0, 0.02, 0), 2.4, (int) (200 * (1 - lisse((t - 1.3) / 0.3))) << 24 | 0x08040C, true);
            }
            case Tenebres.E_CHAINES -> {
                Entity cible = entite(level, a.n());
                Player j = level.getPlayerByUUID(a.qui());
                if (cible == null || j == null || t > 1.3) {
                    return;
                }
                float part = (float) (1 - lisse((t - 1.0) / 0.3));
                Vec3 de = j.getPosition(partiel).add(0, 1.2, 0), c = cible.getPosition(partiel).add(0, cible.getBbHeight() * 0.6, 0);
                double sort = lisse(t / 0.2);
                chaine(ps, b, cam, de, de.lerp(c, sort), level, part);
                Vec3 sol = cible.getPosition(partiel);
                chaine(ps, b, cam, sol.add(0.9, 0, 0.5), sol.add(0.9, 0, 0.5).lerp(c, sort), level, part);
                chaine(ps, b, cam, sol.add(-0.8, 0, 0.6), sol.add(-0.8, 0, 0.6).lerp(c, sort), level, part);
                DessinClair.anneau(ps, DessinClair.lueur(b), cam, c, 0.5, 0.06, new Vec3(1, 0, 0), new Vec3(0, 0, 1), 0.6F, 0.25F, 1F, part);
            }
            case Tenebres.E_FAUX -> faux(ps, b, cam, level, a, t);
            case Tenebres.E_COUP -> {
                if (a.phase() >= 2 && t < 0.35) {
                    croissant(ps, b, cam, a.pos().add(0, 0.2, 0), horizontal(a.dir()), 1.5, (float) (1 - t / 0.35), 0.35F, 0.12F, 0.6F);
                }
            }
            default -> { }
        }
    }

    /** Un croissant (un coup d'ombre) devant c, qui balaie vers dir. */
    private static void croissant(PoseStack ps, MultiBufferSource b, Vec3 cam, Vec3 c, Vec3 dir, double r, float al, float rr, float g, float bl) {
        Vec3 u = new Vec3(-dir.z, 0, dir.x);
        VertexConsumer v = DessinClair.lueur(b);
        Vec3 prec = null;
        for (int k = 0; k <= 16; k++) {
            double a = -1.2 + 2.4 * k / 16;
            Vec3 p = c.add(dir.scale(Math.cos(a) * r)).add(u.scale(Math.sin(a) * r)).add(0, Math.sin(a) * r * 0.3, 0);
            if (prec != null) {
                double e = Math.sin(Math.PI * k / 16);
                DessinClair.trait(ps, v, cam, prec, p, 0.35 * e + 0.05, rr, g, bl, al);
            }
            prec = p;
        }
    }

    /** Une chaîne de maillons de a à b. */
    private static void chaine(PoseStack ps, MultiBufferSource b, Vec3 cam, Vec3 a, Vec3 z, Level level, float alpha) {
        double d = a.distanceTo(z);
        int n = Math.max(1, (int) (d / 0.32));
        Quaternionf r = DessinClair.vers(z.subtract(a));
        int l = lumiere(level, a.lerp(z, 0.5));
        for (int i = 0; i < n; i++) {
            Vec3 p = a.lerp(z, (i + 0.5) / n);
            DessinClair.modele(ps, b, cam, "tenebres", "maillons", p, new Quaternionf(r).rotateZ(i * 0.5F), 1 / 16F, l, alpha);
        }
    }

    /** La faux : son ombre géante balaie tout autour (un grand croissant qui tourne), la lame au bout. */
    private static void faux(PoseStack ps, MultiBufferSource b, Vec3 cam, Level level, Anim a, double t) {
        if (t > 1.2) {
            return;
        }
        double tour = lisse(t / 0.45) * Mth.TWO_PI;
        double r = Tenebres.RAYON_FAUX;
        Vec3 c = a.pos().add(0, 1, 0), dir = horizontal(a.dir());
        double a0 = Math.atan2(dir.z, dir.x);
        float part = (float) (1 - lisse((t - 0.8) / 0.4));
        VertexConsumer v = DessinClair.voile(b), lu = DessinClair.lueur(b);
        int n = 40;
        for (int k = 0; k < n; k++) {
            double u0 = k / (double) n, u1 = (k + 1) / (double) n;
            double b0 = a0 + tour * u0 - tour, b1 = a0 + tour * u1 - tour;
            if (tour < 0.05) {
                break;
            }
            int al = (int) (150 * u1 * part);
            DessinClair.face(ps, v, cam, c.add(Math.cos(b0) * 2, 0, Math.sin(b0) * 2), c.add(Math.cos(b1) * 2, 0, Math.sin(b1) * 2),
                    c.add(Math.cos(b1) * r, 0, Math.sin(b1) * r), c.add(Math.cos(b0) * r, 0, Math.sin(b0) * r), new Vec3(0, 1, 0), al << 24 | 0x321060, LightTexture.FULL_BRIGHT);
            DessinClair.trait(ps, lu, cam, c.add(Math.cos(b0) * r, 0, Math.sin(b0) * r), c.add(Math.cos(b1) * r, 0, Math.sin(b1) * r), 0.25, 0.6F, 0.25F, 1F, (float) (u1 * part));
        }
        double bout = a0 + tour - tour;
        Vec3 lame = c.add(Math.cos(a0 + tour) * (r - 0.8), 0, Math.sin(a0 + tour) * (r - 0.8));
        Vec3 tangente = new Vec3(-Math.sin(a0 + tour), 0, Math.cos(a0 + tour));
        DessinClair.modele(ps, b, cam, "tenebres", "lame", lame, DessinClair.vers(tangente).rotateZ(1.2F), 0.16F, LightTexture.FULL_BRIGHT, part);
        // le manche, de lui jusqu'à la lame
        VertexConsumer pl = DessinClair.plein(b);
        for (int k = 1; k < 14; k++) {
            DessinClair.cube(ps, pl, cam, c.add(0, 0.6, 0).lerp(lame, k / 14.0), 0.2, 0xFF0C0614, LightTexture.FULL_BRIGHT);
        }
    }

    // ================================================================ l'Éclipse

    private static final double ECLIPSE_LOIN = 70, ECLIPSE_HAUT = 55;

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
        // le ciel devient noir : une grande boule sombre autour de la caméra (le décor proche reste devant)
        double noir = lisse(t / 1.2) * part;
        if (cam.distanceTo(u.pos) < 140) {
            VertexConsumer v = DessinClair.voile(b);
            int al = (int) (170 * noir) << 24 | 0x06030C;
            DessinClair.boule(ps, v, cam, cam, 48, al, LightTexture.FULL_BRIGHT);
        }
        // l'éclipse : un disque noir, une couronne de rayons violets
        Vec3 ciel = u.pos.add(dir.scale(ECLIPSE_LOIN)).add(0, ECLIPSE_HAUT, 0);
        eclipse(ps, b, cam, ciel, 12 * lisse(t / 1.5) + (u.t[3] >= 0 ? 3 : 0), (float) (noir));
        // le géant d'ombre
        // à sa droite et un peu derrière : on le voit en vue de dos comme de face
        Vec3 dos = u.pos.subtract(dir.scale(3)).add(new Vec3(-dir.z, 0, dir.x).scale(-7));
        float lacet = (float) (Math.atan2(-dir.x, dir.z) * Mth.RAD_TO_DEG);
        if (u.t[1] >= 0) {
            double tg = secondes(u.t[1]);
            double sort = lisse(tg / 1.0);
            Vec3 pieds = dos.add(0, -(1 - sort) * 7, 0);
            DessinClair.Pose p = new DessinClair.Pose();
            if (u.t[2] < 0) {
                p.bras(-2.9F, 0.35F, -2.9F, -0.35F).tete(-0.4F);
            } else {
                double tl = secondes(u.t[2]);
                float k = (float) lisse(tl / 0.3);
                p.bras(Mth.lerp(k, -2.9F, -1.4F), 0.2F, Mth.lerp(k, -2.9F, -0.5F), -0.4F).tete(0.1F);
            }
            ps.pushPose();
            DessinClair.repere(ps, cam, pieds, lacet, 4F, 0);
            DessinClair.pantin(ps, b, p, (int) (245 * part) << 24 | 0x0C0614, VIOLET_YEUX, LightTexture.FULL_BRIGHT);
            ps.popPose();
            // son contour violet, une aura
            ps.pushPose();
            DessinClair.repere(ps, cam, pieds.add(0, -0.15, 0), lacet, 4.15F, 0);
            DessinClair.pantin(ps, b, p, (int) (60 * part) << 24 | 0x6A30C8, -1, LightTexture.FULL_BRIGHT);
            ps.popPose();
            if (HASARD.nextInt(2) == 0) {
                fumee(level, pieds.add(HASARD.nextGaussian() * 2, HASARD.nextDouble() * 4, HASARD.nextGaussian() * 2), 2, 0.4);
            }
            // le trou noir : entre ses mains, puis lancé sur la zone
            Vec3 mains = DessinClair.mainPantin(pieds, lacet, 4F, p, false).add(DessinClair.mainPantin(pieds, lacet, 4F, p, true)).scale(0.5).add(0, 1.2, 0);
            Vec3 c;
            double r;
            if (u.t[2] < 0) {
                c = mains;
                r = 1.6 * lisse((tg - 0.6) / 1.0);
            } else {
                double tl = secondes(u.t[2]);
                Vec3 z = u.zone.add(0, 4, 0);
                c = mains.lerp(z, lisse(tl / 0.5));
                r = 1.6 + 1.8 * lisse(tl / 0.8);
                if (u.t[3] >= 0) {
                    double ti = secondes(u.t[3]);
                    r = r * (1 - lisse(ti / 0.12));
                }
            }
            if (r > 0.05) {
                trouNoir(ps, b, cam, c, r, t, part);
            }
            // il aspire : des blocs arrachés qui tournent vers lui, des traînées violettes
            if (u.t[2] >= 0 && u.t[3] < 0 && u.zone != null) {
                aspiration(ps, b, cam, level, u.zone.add(0, 4, 0), secondes(u.t[2]));
            }
        }
        if (u.t[3] >= 0 && u.zone != null) {
            implosion(ps, b, cam, u.zone, secondes(u.t[3]), part);
        }
    }

    private static void eclipse(PoseStack ps, MultiBufferSource b, Vec3 cam, Vec3 c, double r, float al) {
        if (al <= 0.01 || r < 0.1) {
            return;
        }
        Vec3 o = cam.subtract(c).normalize();
        Vec3 e1 = o.cross(new Vec3(0, 1, 0)).normalize(), e2 = e1.cross(o).normalize();
        VertexConsumer lu = DessinClair.lueur(b);
        double t = System.nanoTime() / 1e9;
        for (int q = 0; q < 64; q++) {
            double a = q * Mth.TWO_PI / 64 + 0.04 * Math.sin(t + q);
            double l = r * (0.2 + 0.8 * Math.pow(0.5 + 0.5 * Math.sin(q * 2.7 + t * 1.3), 2));
            Vec3 de = c.add(e1.scale(Math.cos(a) * r)).add(e2.scale(Math.sin(a) * r));
            Vec3 a2 = c.add(e1.scale(Math.cos(a + 0.03) * (r + l))).add(e2.scale(Math.sin(a + 0.03) * (r + l)));
            DessinClair.trait(ps, lu, cam, de, a2, q % 4 == 0 ? r * 0.08 : r * 0.04, 0.6F, 0.25F, 1F, al * (q % 4 == 0 ? 1 : 0.6F));
        }
        DessinClair.anneau(ps, lu, cam, c, r * 1.03, r * 0.1, e1, e2, 0.85F, 0.7F, 1F, al);
        DessinClair.anneau(ps, lu, cam, c, r * 1.25, r * 0.35, e1, e2, 0.5F, 0.2F, 0.9F, al * 0.35F);
        VertexConsumer pl = DessinClair.plein(b);
        int n = 40;
        for (int q = 0; q < n; q++) {
            double a0 = q * Mth.TWO_PI / n, a1 = (q + 1) * Mth.TWO_PI / n;
            DessinClair.face(ps, pl, cam, c, c, c.add(e1.scale(Math.cos(a1) * r)).add(e2.scale(Math.sin(a1) * r)),
                    c.add(e1.scale(Math.cos(a0) * r)).add(e2.scale(Math.sin(a0) * r)), o, 0xFF020104, LightTexture.FULL_BRIGHT);
        }
    }

    /** Le trou noir : une boule toute noire, un anneau de lumière autour, un disque de matière violette qui tourne. */
    private static void trouNoir(PoseStack ps, MultiBufferSource b, Vec3 cam, Vec3 c, double r, double t, float al) {
        Vec3 u = new Vec3(1, 0, 0), v = new Vec3(0, 0.32, 1).normalize();
        double tourne = t * 2.4;
        Vec3 uu = u.scale(Math.cos(tourne)).add(v.scale(Math.sin(tourne))), vv = v.scale(Math.cos(tourne)).subtract(u.scale(Math.sin(tourne)));
        VertexConsumer lu = DessinClair.lueur(b);
        for (int k = 0; k < 7; k++) {
            double rr = r * (1.25 + k * 0.28);
            float c0 = 1 - k / 7F;
            DessinClair.anneau(ps, lu, cam, c, rr, r * 0.22, uu, vv, 0.55F + 0.4F * c0, 0.25F + 0.5F * c0, 1F, al * (0.25F + 0.5F * c0));
        }
        DessinClair.boule(ps, DessinClair.plein(b), cam, c, r, 0xFF020104, LightTexture.FULL_BRIGHT);
        Vec3 o = cam.subtract(c).normalize();
        Vec3 e1 = o.cross(new Vec3(0, 1, 0)).normalize(), e2 = e1.cross(o).normalize();
        DessinClair.anneau(ps, lu, cam, c, r * 1.05, r * 0.12, e1, e2, 0.9F, 0.75F, 1F, al);
    }

    private static void aspiration(PoseStack ps, MultiBufferSource b, Vec3 cam, Level level, Vec3 c, double t) {
        VertexConsumer pl = DessinClair.plein(b), lu = DessinClair.lueur(b);
        BlockState sol = level.getBlockState(BlockPos.containing(c.x, ClientKaguya.sol(level, c) - 1, c.z));
        int couleur = 0xFF000000 | (sol.getMapColor(level, BlockPos.containing(c)).col);
        for (int q = 0; q < 46; q++) {
            double vie = 1.6 + Kag(q, 1) * 1.2, phase = ((t + Kag(q, 2) * vie) % vie) / vie;
            double r0 = 6 + 9 * Kag(q, 3), ang = Kag(q, 4) * Mth.TWO_PI + phase * 5;
            double r = r0 * (1 - phase * phase);
            double y = -4 + (4 - 0) * phase - 2 * (1 - phase) * Kag(q, 5);
            Vec3 p = c.add(Math.cos(ang) * r, y * (1 - phase), Math.sin(ang) * r * 0.7);
            int col = q % 3 == 0 ? 0xFF7A7A80 : q % 3 == 1 ? couleur : 0xFF6E5036;
            DessinClair.cube(ps, pl, cam, p, 0.5 * (1 - phase * 0.7), col, LightTexture.FULL_BRIGHT);
            Vec3 avant = c.add(Math.cos(ang - 0.4) * r * 1.15, y * (1 - phase) - 0.3, Math.sin(ang - 0.4) * r * 0.8);
            DessinClair.trait(ps, lu, cam, avant, p, 0.08, 0.6F, 0.25F, 1F, 0.5F);
        }
        if (HASARD.nextInt(2) == 0) {
            level.addParticle(new BlockParticleOption(ParticleTypes.BLOCK, sol), c.x + HASARD.nextGaussian() * 5, c.y - 3, c.z + HASARD.nextGaussian() * 5, 0, 0.4, 0);
        }
    }

    private static double Kag(int i, int j) {
        return fr.cabane.pouvoir.Kaguya.hache(i, j, 733);
    }

    private static void implosion(PoseStack ps, MultiBufferSource b, Vec3 cam, Vec3 z, double t, float part) {
        Vec3 c = z.add(0, 4, 0);
        VertexConsumer v = DessinClair.voile(b), lu = DessinClair.lueur(b);
        // la boule de lumière violette qui grandit et s'efface
        if (t < 1.4) {
            double r = 16 * lisse(t / 0.35);
            float al = (float) (1 - lisse((t - 0.2) / 1.2));
            DessinClair.boule(ps, v, cam, c, r, (int) (120 * al) << 24 | 0xB46AFF, LightTexture.FULL_BRIGHT);
            DessinClair.boule(ps, v, cam, c, r * 0.55, (int) (150 * al) << 24 | 0xF0DCFF, LightTexture.FULL_BRIGHT);
            // des éclairs violets dans tous les sens
            for (int q = 0; q < 22; q++) {
                double a = q * Mth.TWO_PI / 22, h = (Kag(q, 9) - 0.4) * 2;
                Vec3 bout = c.add(Math.cos(a) * 20, h * 14, Math.sin(a) * 20);
                Vec3[] pts = new Vec3[8];
                for (int k = 0; k < 8; k++) {
                    pts[k] = c.lerp(bout, k / 7.0).add(k == 0 || k == 7 ? Vec3.ZERO : new Vec3(Kag(q, 20 + k) - 0.5, Kag(q, 40 + k) - 0.5, Kag(q, 60 + k) - 0.5).scale(3));
                }
                DessinClair.ligne(ps, lu, cam, pts, 0.35, 0.7F, 0.4F, 1F, al);
            }
        }
        // la colonne noire qui monte au ciel
        float col = (float) (lisse(t / 0.2) * (1 - lisse((t - 1.0) / 1.0))) * part;
        if (col > 0.01) {
            Vec3 o = cam.subtract(c);
            Vec3 cote = new Vec3(-o.z, 0, o.x).normalize();
            for (double[] w : new double[][]{{7, 200}, {11, 70}}) {
                Vec3 s = cote.scale(w[0]);
                DessinClair.face(ps, v, cam, z.subtract(s), z.add(s), z.add(s).add(0, 160, 0), z.subtract(s).add(0, 160, 0), o.normalize(),
                        (int) (w[1] * col) << 24 | (w[0] < 8 ? 0x020104 : 0x3C1464), LightTexture.FULL_BRIGHT);
            }
        }
        // les ondes au sol
        for (int k = 0; k < 3; k++) {
            double tt = t - k * 0.15;
            if (tt < 0 || tt > 1.6) {
                continue;
            }
            double r = 4 + 26 * lisse(tt / 1.2);
            DessinClair.anneauSol(ps, lu, cam, z.add(0, 0.1, 0), r, 0.6 - k * 0.15, 0.65F, 0.3F, 1F, (float) (1 - tt / 1.6));
        }
        // le cratère d'ombre
        DessinClair.disque(ps, v, cam, z.add(0, 0.03, 0), 9 * lisse(t / 0.3), (int) (220 * part) << 24 | 0x08040C, true);
    }

    // ================================================================ les touches et le panneau

    private static long pretDroit, pretAccroupi, pretAccDroit, pretAccGauche, pretTech;
    private static boolean ultiFaite, accroupiAvant, clicPendant;
    private static long accroupiDebut;

    private static long dans(int ticks) {
        return System.nanoTime() + ticks * 50_000_000L;
    }

    private static void envoyer(int action) {
        PacketDistributor.sendToServer(new Tenebres.Touche(action));
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
        return u != null && u.t[0] >= 0 && u.t[4] < 0 && secondes(u.t[0]) < 10;
    }

    /** La touche technique (², lue dans ClientSharingan) : la faux, au niveau 3. */
    public static boolean technique() {
        Etat e = moi();
        if (e == null) {
            return false;
        }
        if (e.niveau >= 3 && !ultiEnCours() && System.nanoTime() >= pretTech) {
            pretTech = dans(Tenebres.RECHARGE_FAUX);
            envoyer(ClairObscur.TECHNIQUE);
        }
        return true;
    }

    /** La touche d'ulti (J, lue dans ClientHachibi4) : l'Éclipse, au niveau 3, une fois par éveil. */
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
        Etat e = moi();
        if (e == null || mc.options.hideGui) {
            return;
        }
        int n = e.niveau;
        boolean deux = Tenebres.NOM2.equals(e.pouvoir), trois = Tenebres.NOM3.equals(e.pouvoir);
        String eveil = n == 1 && (deux || trois) ? "Niveau 2" : n == 2 && trois ? "Niveau 3" : "Rendormir";
        String acc = Clavier.accroupi();
        long now = System.nanoTime();
        List<String[]> lignes = new ArrayList<>();
        List<Boolean> gris = new ArrayList<>();
        lignes.add(new String[]{Clavier.gauche(), n >= 3 ? "Coup d'ombre (vole 1 cœur)" : n == 2 ? "Coup d'ombre (son ombre frappe aussi)" : "Coup d'ombre (Obscurité)"});
        gris.add(false);
        lignes.add(new String[]{Clavier.droite(), (n >= 3 ? "Lances d'ombre" : n == 2 ? "Mains d'ombre" : "Lame d'ombre") + reste(pretDroit)});
        gris.add(pretDroit > now);
        lignes.add(new String[]{acc, (n >= 3 ? "Trois ombres" : n == 2 ? "Plongée (30 blocs, explose)" : "Plongée dans l'ombre") + reste(pretAccroupi)});
        gris.add(pretAccroupi > now);
        if (n >= 2) {
            lignes.add(new String[]{acc + " + " + Clavier.droite(), "Prison d'ombre" + reste(pretAccDroit)});
            gris.add(pretAccDroit > now);
        }
        if (n >= 3) {
            lignes.add(new String[]{acc + " + " + Clavier.gauche(), "Chaînes d'ombre" + reste(pretAccGauche)});
            gris.add(pretAccGauche > now);
            lignes.add(new String[]{Clavier.nom(ClientSharingan.SUSANO), "La faux" + reste(pretTech)});
            gris.add(pretTech > now);
            lignes.add(new String[]{Clavier.nom(fr.cabane.client.hachibi4.ClientHachibi4.ULTI), ultiFaite ? "L'Éclipse (déjà lancée)" : "L'Éclipse"});
            gris.add(ultiFaite);
        }
        lignes.add(new String[]{Clavier.nom(ClientSusano.EVEIL), eveil});
        gris.add(false);
        boolean[] g2 = new boolean[gris.size()];
        for (int i = 0; i < g2.length; i++) {
            g2[i] = gris.get(i);
        }
        PanneauPouvoir.dessiner(g, mc.font, "Ténèbres" + (n >= 2 ? "  ·  niv. " + n : ""), 0xFFC8A0FF, lignes.toArray(new String[0][]), g2);
    }

    // ================================================================ les transformations et la fumée (à chaque tick)

    private static void ambiance(Minecraft mc, Player j, Etat e) {
        Level level = mc.level;
        double t1 = e.debut1 < 0 ? 99 : secondes(e.debut1), t2 = e.debut2 < 0 ? 99 : secondes(e.debut2), t3 = e.debut3 < 0 ? 99 : secondes(e.debut3);
        Vec3 p = j.position();
        // l'éveil 1 : la flaque noire s'ouvre, l'ombre monte sur ses jambes
        if (t1 < 1.4 && e.niveau == 1) {
            for (int k = 0; k < 6; k++) {
                double a = HASARD.nextDouble() * Mth.TWO_PI, r = 0.3 + HASARD.nextDouble() * 1.2;
                level.addParticle(k % 2 == 0 ? NOIRE : ParticleTypes.SQUID_INK, p.x + Math.cos(a) * r, p.y + 0.05 + HASARD.nextDouble() * t1, p.z + Math.sin(a) * r, 0, 0.05, 0);
            }
        }
        // l'éveil 2 : son ombre sort du sol dans un nuage noir
        if (t2 < 1.5 && e.niveau == 2) {
            Vec3 o = p.add(corps(j.yBodyRot, -1.45, 0, -0.35));
            fumee(level, o, 5, 0.8);
        }
        // l'éveil 3 : la nuit tombe, un tourbillon noir l'avale
        if (t3 < 1.6 && e.niveau >= 3) {
            for (int k = 0; k < 10; k++) {
                double u = HASARD.nextDouble(), a = t3 * 9 + u * 18, r = 1.6 - u * 0.8;
                level.addParticle(k % 3 == 0 ? VIOLET : k % 3 == 1 ? NOIRE : ParticleTypes.LARGE_SMOKE, p.x + Math.cos(a) * r, p.y + u * 2.8, p.z + Math.sin(a) * r, 0, 0.02, 0);
            }
        }
        // son ombre fume un peu
        if (e.niveau >= 2 && HASARD.nextInt(3) == 0) {
            Vec3 o = p.add(corps(j.yBodyRot, -1.45, 0, -0.35));
            level.addParticle(ParticleTypes.SMOKE, o.x + HASARD.nextGaussian() * 0.3, o.y + HASARD.nextDouble() * 2.6, o.z + HASARD.nextGaussian() * 0.3, 0, 0.02, 0);
        }
        // la fumée noire des mains, et (niveau 3) des étincelles violettes
        if (HASARD.nextInt(e.niveau >= 3 ? 2 : 4) == 0 && !j.isInvisible()) {
            float lacet = j.yBodyRot;
            for (double x : new double[]{-0.38, 0.38}) {
                Vec3 m = p.add(corps(lacet, x, 0.7, 0.05));
                level.addParticle(HASARD.nextInt(3) == 0 ? NOIRE : ParticleTypes.SMOKE, m.x, m.y, m.z, 0, 0.03, 0);
            }
            if (e.niveau >= 3) {
                level.addParticle(VIOLET, p.x + HASARD.nextGaussian() * 0.4, p.y + HASARD.nextDouble() * 2, p.z + HASARD.nextGaussian() * 0.4, 0, 0.02, 0);
            }
        }
    }

    // ================================================================ les événements

    @EventBusSubscriber(modid = Cabane.MODID, value = Dist.CLIENT)
    public static final class Couches {
        @SubscribeEvent
        public static void enregistrer(RegisterGuiLayersEvent event) {
            event.registerAboveAll(Cabane.id("tenebres"), (g, delta) -> ecran(g));
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
            // s'accroupir (un appui court, sans clic entre-temps) : la plongée, les trois ombres
            if (moi != null && !accroupi && accroupiAvant && !clicPendant && mc.screen == null && secondes(accroupiDebut) < 0.6 && !ultiEnCours()
                    && System.nanoTime() >= pretAccroupi) {
                pretAccroupi = dans(moi.niveau >= 3 ? Tenebres.RECHARGE_OMBRES : Tenebres.RECHARGE_PLONGEE);
                envoyer(ClairObscur.ACCROUPI);
            }
            accroupiAvant = accroupi;
            ETATS.values().removeIf(e -> e.fin >= 0 && secondes(e.fin) > 1);
            ANIMS.removeIf(a -> a.age() > 4);
            ULTIS.values().removeIf(u -> u.t[0] < 0 || secondes(u.t[0]) > 14 || u.t[4] >= 0 && secondes(u.t[4]) > 1.5);
            for (Map.Entry<UUID, Etat> en : ETATS.entrySet()) {
                Player j = mc.level.getPlayerByUUID(en.getKey());
                if (j != null && en.getValue().fin < 0 && j.distanceTo(mc.player) < 64) {
                    ambiance(mc, j, en.getValue());
                }
            }
        }

        /** Éveillé, le clic droit est à ses ombres ; accroupi, les clics droit et gauche aussi (niveaux 2 et 3). */
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
                        pretAccDroit = dans(Tenebres.RECHARGE_PRISON);
                        mc.player.swing(InteractionHand.MAIN_HAND);
                        envoyer(ClairObscur.ACC_DROIT);
                    }
                } else if (System.nanoTime() >= pretDroit) {
                    clicPendant = accroupi || clicPendant;
                    pretDroit = dans(moi.niveau >= 3 ? Tenebres.RECHARGE_PIQUES : moi.niveau == 2 ? Tenebres.RECHARGE_MAINS : Tenebres.RECHARGE_LAME);
                    mc.player.swing(InteractionHand.MAIN_HAND);
                    envoyer(ClairObscur.DROIT);
                }
            } else if (event.isAttack() && accroupi && moi.niveau >= 3) {
                event.setCanceled(true);
                clicPendant = true;
                if (!ultiEnCours() && System.nanoTime() >= pretAccGauche) {
                    pretAccGauche = dans(Tenebres.RECHARGE_CHAINES);
                    mc.player.swing(InteractionHand.MAIN_HAND);
                    envoyer(ClairObscur.ACC_GAUCHE);
                }
            }
        }

        /** Pendant l'Éclipse, il ne bouge pas tant que le géant n'a pas lancé le trou noir. */
        @SubscribeEvent
        public static void immobile(MovementInputUpdateEvent event) {
            Minecraft mc = Minecraft.getInstance();
            Ulti u = mc.player == null ? null : ULTIS.get(mc.player.getUUID());
            if (u == null || u.t[0] < 0 || u.t[2] >= 0 || secondes(u.t[0]) > 5) {
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

        @SubscribeEvent
        public static void monde(RenderLevelStageEvent event) {
            if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES || Minecraft.getInstance().level == null) {
                return;
            }
            if (ETATS.isEmpty() && ANIMS.isEmpty() && ULTIS.isEmpty()) {
                return;
            }
            float partiel = event.getPartialTick().getGameTimeDeltaPartialTick(false);
            ClientTenebres.monde(event.getPoseStack(), event.getCamera().getPosition(), partiel);
        }

        private Evenements() {
        }
    }

    private ClientTenebres() {
    }
}
