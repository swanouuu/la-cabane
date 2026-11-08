package fr.cabane.client.pouvoir;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import fr.cabane.pouvoir.DinoPose;
import fr.cabane.pouvoir.KaguyaDino;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.client.Camera;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import fr.cabane.Cabane;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * Le dinosaure d'os de l'ulti de Kaguya, chez chaque joueur (voir {@link KaguyaDino} et docs/kaguya/ulti) : le squelette
 * de assets/cabane/kaguya/dino.bin, posé comme l'aperçu ({@link DinoPose}) là où le serveur le dit ; elle debout sur son
 * crâne (c'est son jeu qui l'y pose, la caméra loin derrière) ; les blocs qui s'envolent, retombent, puis reviennent à
 * leur place ; les os qui volent quand il explose.
 */
public final class ClientKaguyaDino {
    private static final RandomSource HASARD = RandomSource.create();
    private static final BlockParticleOption OS_PART = new BlockParticleOption(ParticleTypes.BLOCK, Blocks.BONE_BLOCK.defaultBlockState());
    private static final double GRAV = 30;

    private static final class Dino {
        int age, agePrec;
        Vec3 pos, posPrec;
        float lacet, lacetPrec;
        float distance, distancePrec;
        int mord = -1;
        long recu;
        boolean fissure, eruption, explose;
        /** Le nombre de pas déjà faits par chaque pied (un pas qui tombe : des os jaillissent du sol autour du pied). */
        final int[] pas = {Integer.MIN_VALUE, Integer.MIN_VALUE};
        Vec3 ventre;
    }

    private static final Map<UUID, Dino> DINOS = new HashMap<>();

    /** Un bloc qui s'envole : d'où, son état, quand, sa vitesse, sa rotation, où il retombe ; ou un os de l'explosion. */
    private static final class Debris {
        UUID qui;
        BlockPos origine;
        BlockState etat;
        Vec3 depart, vitesse;
        double sol;
        long debut;
        float tourne, taille = 1;
        boolean os;
    }

    private static final List<Debris> DEBRIS = new ArrayList<>();
    /** Au plus tant de blocs qui volent (au-delà, ils disparaissent et reviennent sans voler). */
    private static final int MAX_DEBRIS = 2500;

    static double secondes(long depuis) {
        return (System.nanoTime() - depuis) / 1e9;
    }

    public static boolean actif(UUID qui) {
        return DINOS.containsKey(qui);
    }

    private static Vec3 vec(Vector3f v) {
        return new Vec3(v.x(), v.y(), v.z());
    }

    public static void etat(KaguyaDino.Etat p) {
        if (p.age() < 0) {
            DINOS.remove(p.joueur());
            // tout revient tout de suite
            DEBRIS.removeIf(d -> d.qui.equals(p.joueur()));
            return;
        }
        Dino d = DINOS.get(p.joueur());
        if (d == null) {
            d = new Dino();
            d.pos = d.posPrec = vec(p.pos());
            d.lacet = d.lacetPrec = p.lacet();
            d.distance = d.distancePrec = p.distance();
            d.age = d.agePrec = p.age();
            DINOS.put(p.joueur(), d);
        }
        d.agePrec = d.age;
        d.posPrec = d.pos;
        d.lacetPrec = d.lacet;
        d.distancePrec = d.distance;
        d.age = p.age();
        d.pos = vec(p.pos());
        d.lacet = p.lacet();
        d.distance = p.distance();
        d.mord = p.mord();
        d.recu = System.nanoTime();
        if (p.age() >= DinoPose.T_FIN * 20) {
            DINOS.remove(p.joueur());
        }
    }

    public static void blocs(KaguyaDino.Blocs p) {
        Minecraft mc = Minecraft.getInstance();
        Dino d = DINOS.get(p.joueur());
        if (mc.level == null || d == null) {
            return;
        }
        Vec3 centre = d.pos;
        long t = System.nanoTime();
        for (int i = 0; i < p.pos().size(); i++) {
            BlockPos b = p.pos().get(i);
            BlockState e = Block.stateById(p.etats().get(i));
            if (DEBRIS.size() >= MAX_DEBRIS) {
                return;
            }
            Debris x = new Debris();
            x.qui = p.joueur();
            x.origine = b;
            x.etat = e;
            x.depart = Vec3.atLowerCornerOf(b);
            Vec3 loin = new Vec3(b.getX() + 0.5 - centre.x, 0, b.getZ() + 0.5 - centre.z);
            loin = loin.lengthSqr() < 1e-3 ? new Vec3(HASARD.nextGaussian(), 0, HASARD.nextGaussian()) : loin;
            loin = loin.normalize();
            double v = 6 + HASARD.nextDouble() * 10;
            x.vitesse = new Vec3(loin.x * v + HASARD.nextGaussian() * 3, 8 + HASARD.nextDouble() * 12, loin.z * v + HASARD.nextGaussian() * 3);
            x.sol = d.pos.y;
            x.debut = t;
            x.tourne = (float) HASARD.nextGaussian() * 6;
            DEBRIS.add(x);
        }
    }

    // ================================================================ où il en est

    private static double partiel(Dino d) {
        return Mth.clamp(secondes(d.recu) / 0.05, 0, 1);
    }

    private static double t(Dino d) {
        return Mth.lerp(partiel(d), d.agePrec, d.age) / 20.0;
    }

    private static Vec3 pos(Dino d) {
        return d.posPrec.lerp(d.pos, partiel(d));
    }

    private static float lacet(Dino d) {
        return Mth.rotLerp((float) partiel(d), d.lacetPrec, d.lacet);
    }

    private static double mord(Dino d, double t) {
        if (d.mord < 0) {
            return 0;
        }
        return Mth.clamp(1 - Math.abs(t - d.mord / 20.0 - 0.1) / 0.45, 0, 1);
    }

    /** Elle est sur le crâne de son dinosaure. */
    public static boolean monte(UUID qui) {
        Dino d = DINOS.get(qui);
        return d != null && d.age / 20.0 >= DinoPose.T_SORT && d.age / 20.0 < DinoPose.T_SAUTE;
    }

    /** Elle ne marche pas : la main au sol, puis sur le crâne. */
    public static boolean immobile(UUID qui) {
        Dino d = DINOS.get(qui);
        return d != null && d.age / 20.0 < DinoPose.T_SAUTE;
    }

    /** La main au sol (avant qu'il sorte), puis debout sur son crâne, les bras un peu écartés. */
    static boolean poser(PlayerModel<?> m, Player joueur) {
        Dino d = DINOS.get(joueur.getUUID());
        if (d == null) {
            return false;
        }
        double t = t(d);
        if (t < DinoPose.T_SORT + 0.6) {
            float k = (float) (ClientKaguya.lisse(t / DinoPose.T_MAIN) * (1 - ClientKaguya.lisse((t - DinoPose.T_SORT - 0.2) / 0.4)));
            m.body.xRot = Mth.lerp(k, m.body.xRot, 0.5F);
            m.head.xRot = Mth.lerp(k, m.head.xRot, 0.5F);
            m.rightArm.xRot = Mth.lerp(k, m.rightArm.xRot, -0.9F);
            m.rightArm.zRot = Mth.lerp(k, m.rightArm.zRot, 0.1F);
            m.leftArm.xRot = Mth.lerp(k, m.leftArm.xRot, 0.3F);
            m.rightLeg.xRot = Mth.lerp(k, m.rightLeg.xRot, -0.5F);
            m.leftLeg.xRot = Mth.lerp(k, m.leftLeg.xRot, 0.4F);
            return true;
        }
        if (t < DinoPose.T_SAUTE) {
            m.rightArm.zRot = 0.5F;
            m.leftArm.zRot = -0.5F;
            m.rightArm.xRot = -0.2F;
            m.leftArm.xRot = -0.2F;
            return true;
        }
        return false;
    }

    private static void son(Minecraft mc, Vec3 p, net.minecraft.sounds.SoundEvent son, float volume, float hauteur) {
        mc.level.playLocalSound(p.x, p.y, p.z, son, net.minecraft.sounds.SoundSource.PLAYERS, volume, hauteur, false);
    }

    private static void secouer(Minecraft mc, Vec3 p, float force, double portee) {
        double loin = mc.player.position().distanceTo(p);
        if (loin < portee) {
            ClientVent.secouer((float) (force * (1 - loin / portee)));
        }
    }

    // ================================================================ chaque tick

    private static CameraType vueAvant;

    @EventBusSubscriber(modid = Cabane.MODID, value = Dist.CLIENT)
    public static final class Evenements {
        @SubscribeEvent
        public static void tick(ClientTickEvent.Post event) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.level == null || mc.player == null) {
                DINOS.clear();
                DEBRIS.clear();
                return;
            }
            for (Map.Entry<UUID, Dino> en : DINOS.entrySet()) {
                Dino d = en.getValue();
                double t = d.age / 20.0;
                Matrix4f[] mat = DinoPose.poser(t, d.distance, mord(d, t));
                // le sol se fend autour d'elle, des piques d'os sortent
                if (!d.fissure && t >= DinoPose.T_FISSURE) {
                    d.fissure = true;
                    Player k = mc.level.getPlayerByUUID(en.getKey());
                    Vec3 c = k != null ? k.position() : d.pos;
                    ClientKaguya.piquesAutour(c, en.getKey().hashCode(), 10, DinoPose.T_DEBOUT - DinoPose.T_FISSURE, 34, 1.8, 4.5, 0.9);
                    ClientKaguya.jaillir(mc.level, c, new Vec3(0, 1, 0), 30, 7, 0.3F, true);
                    son(mc, c, SoundEvents.BONE_BLOCK_BREAK, 2F, 0.4F);
                    secouer(mc, c, 1.2F, 40);
                }
                // il jaillit du sol : une éruption d'os géantes tout autour, des éclats partout, tout tremble
                if (!d.eruption && t >= DinoPose.T_SORT) {
                    d.eruption = true;
                    Vec3 h = DinoPose.monde(DinoPose.poser(DinoPose.T_DEBOUT, 0, 0), DinoPose.HANCHES, new Vector3f(), d.pos, d.lacet);
                    Vec3 c = new Vec3(h.x, d.pos.y, h.z);
                    ClientKaguya.piquesAutour(c, en.getKey().hashCode() + 5, 26, 3.6, 110, 9, 14, 2.4);
                    for (int i = 0; i < 10; i++) {
                        double a = i * Mth.TWO_PI / 10;
                        ClientKaguya.jaillir(mc.level, c.add(Math.cos(a) * 7, 0.5, Math.sin(a) * 7), new Vec3(Math.cos(a), 1.4, Math.sin(a)), 14, 14, 0.5F, i % 3 == 0);
                    }
                    ClientKaguya.jaillir(mc.level, c.add(0, 1, 0), new Vec3(0, 1, 0), 60, 18, 0.6F, true);
                    for (int i = 0; i < 60; i++) {
                        mc.level.addParticle(ParticleTypes.EXPLOSION, c.x + HASARD.nextGaussian() * 8, c.y + HASARD.nextDouble() * 4, c.z + HASARD.nextGaussian() * 8, 0, 0, 0);
                    }
                    son(mc, c, SoundEvents.GENERIC_EXPLODE.value(), 4F, 0.5F);
                    son(mc, c, SoundEvents.BONE_BLOCK_BREAK, 4F, 0.4F);
                    son(mc, c, SoundEvents.WARDEN_EMERGE, 3F, 0.7F);
                    secouer(mc, c, 3F, 90);
                }
                // chaque pas qui tombe fait jaillir des os du sol autour du pied
                if (t > DinoPose.T_CHARGE && t < DinoPose.T_STOP) {
                    double phi = d.distance / DinoPose.FOULEE * Mth.TWO_PI;
                    for (int c = 0; c < 2; c++) {
                        int n = (int) Math.floor((phi + (c == 0 ? 0 : Math.PI) + Math.PI / 2) / Mth.TWO_PI);
                        if (d.pas[c] != Integer.MIN_VALUE && n > d.pas[c]) {
                            Vec3 p = DinoPose.monde(mat, c == 0 ? DinoPose.PIED_G : DinoPose.PIED_D, new Vector3f(0, -2.5F, 2), d.pos, d.lacet);
                            Vec3 sol = new Vec3(p.x, d.pos.y, p.z);
                            ClientKaguya.piquesAutour(sol, n * 31 + c, 9, 1.6, 22, 3, 7, 1.3);
                            ClientKaguya.jaillir(mc.level, sol.add(0, 0.5, 0), new Vec3(0, 1, 0), 24, 10, 0.4F, true);
                            son(mc, sol, SoundEvents.GENERIC_EXPLODE.value(), 2F, 0.55F);
                            son(mc, sol, SoundEvents.BONE_BLOCK_BREAK, 2.5F, 0.5F);
                            secouer(mc, sol, 1.5F, 50);
                        }
                        d.pas[c] = n;
                    }
                }
                // il sort de terre : la poussière, les mottes
                if (t > DinoPose.T_SORT && t < DinoPose.T_DEBOUT) {
                    Vec3 h = DinoPose.monde(mat, DinoPose.TETE, new Vector3f(), d.pos, d.lacet);
                    for (int i = 0; i < 6; i++) {
                        mc.level.addParticle(ParticleTypes.CAMPFIRE_COSY_SMOKE, h.x + HASARD.nextGaussian() * 4, d.pos.y + 0.2, h.z + HASARD.nextGaussian() * 4, 0, 0.05, 0);
                    }
                }
                // il marche : la poussière sous ses pieds
                if (t > DinoPose.T_CHARGE && t < DinoPose.T_STOP && d.age % 2 == 0) {
                    for (int c = 0; c < 2; c++) {
                        Vec3 p = DinoPose.monde(mat, c == 0 ? DinoPose.PIED_G : DinoPose.PIED_D, new Vector3f(0, -2.5F, 2), d.pos, d.lacet);
                        mc.level.addParticle(ParticleTypes.POOF, p.x, d.pos.y + 0.2, p.z, HASARD.nextGaussian() * 0.2, 0.05, HASARD.nextGaussian() * 0.2);
                    }
                }
                // il explose : ses os volent partout, et restent par terre jusqu'à la réparation
                if (!d.explose && t >= DinoPose.T_EXPLO) {
                    d.explose = true;
                    Vec3 v = DinoPose.monde(mat, DinoPose.HANCHES, new Vector3f(0, -3.5F, 4), d.pos, d.lacet);
                    long now = System.nanoTime();
                    for (int i = 0; i < 260; i++) {
                        Debris x = new Debris();
                        x.qui = en.getKey();
                        x.os = true;
                        x.etat = Blocks.BONE_BLOCK.defaultBlockState();
                        x.depart = v.add(HASARD.nextGaussian() * 5, HASARD.nextGaussian() * 5 + 3, HASARD.nextGaussian() * 5);
                        Vec3 dir = x.depart.subtract(v).normalize();
                        double vit = 10 + HASARD.nextDouble() * 18;
                        x.vitesse = new Vec3(dir.x * vit, 6 + HASARD.nextDouble() * 14, dir.z * vit);
                        x.sol = d.pos.y;
                        x.debut = now;
                        x.tourne = (float) HASARD.nextGaussian() * 8;
                        x.taille = 0.3F + HASARD.nextFloat() * 0.6F;
                        DEBRIS.add(x);
                    }
                    for (int i = 0; i < 40; i++) {
                        mc.level.addParticle(ParticleTypes.LARGE_SMOKE, v.x + HASARD.nextGaussian() * 4, v.y + HASARD.nextGaussian() * 4, v.z + HASARD.nextGaussian() * 4, 0, 0.1, 0);
                        mc.level.addParticle(ParticleTypes.FLAME, v.x + HASARD.nextGaussian() * 3, v.y + HASARD.nextGaussian() * 3, v.z + HASARD.nextGaussian() * 3,
                                HASARD.nextGaussian() * 0.3, HASARD.nextGaussian() * 0.3, HASARD.nextGaussian() * 0.3);
                    }
                    // et une forêt d'os géante sort du sol tout autour
                    Vec3 c = new Vec3(v.x, d.pos.y, v.z);
                    ClientKaguya.piquesAutour(c, en.getKey().hashCode() + 11, 30, KaguyaDino.REMIS - DinoPose.T_EXPLO, 150, 4, 18, 2.8);
                    for (int i = 0; i < 12; i++) {
                        double a = i * Mth.TWO_PI / 12;
                        ClientKaguya.jaillir(mc.level, c.add(Math.cos(a) * 9, 0.5, Math.sin(a) * 9), new Vec3(Math.cos(a), 1.2, Math.sin(a)), 12, 16, 0.5F, i % 4 == 0);
                    }
                    son(mc, c, SoundEvents.GENERIC_EXPLODE.value(), 4F, 0.4F);
                    son(mc, c, SoundEvents.WARDEN_SONIC_BOOM, 3F, 0.6F);
                    secouer(mc, c, 3F, 100);
                }
                if (t >= DinoPose.T_DEBOUT && t < DinoPose.T_DEBOUT + 0.1) {
                    double loin = mc.player.position().distanceTo(d.pos);
                    if (loin < 60) {
                        ClientVent.secouer((float) (1.4 * (1 - loin / 60)));
                    }
                }
            }
            // sur le crâne : c'est son jeu à elle qui l'y pose (le serveur la rattrape si elle s'en éloigne trop)
            Dino moi = DINOS.get(mc.player.getUUID());
            if (moi != null) {
                double t = moi.age / 20.0;
                if (t >= DinoPose.T_SORT && t < DinoPose.T_SAUTE) {
                    Matrix4f[] mat = DinoPose.poser(t, moi.distance, mord(moi, t));
                    Vec3 crane = DinoPose.monde(mat, DinoPose.TETE, DinoPose.CRANE, moi.pos, moi.lacet);
                    if (crane.y > mc.player.getY() - 0.5 || t > DinoPose.T_DEBOUT) {
                        mc.player.setPos(crane.x, crane.y, crane.z);
                        mc.player.setDeltaMovement(Vec3.ZERO);
                        mc.player.fallDistance = 0;
                    }
                }
                // la caméra loin derrière elle (pour voir le dinosaure qu'elle dirige)
                if (t >= DinoPose.T_SORT && t < DinoPose.T_EXPLO + 1.2) {
                    if (vueAvant == null) {
                        vueAvant = mc.options.getCameraType();
                    }
                    if (mc.options.getCameraType() != CameraType.THIRD_PERSON_BACK) {
                        mc.options.setCameraType(CameraType.THIRD_PERSON_BACK);
                    }
                }
            }
            if ((moi == null || moi.age / 20.0 >= DinoPose.T_EXPLO + 1.2) && vueAvant != null) {
                mc.options.setCameraType(vueAvant);
                vueAvant = null;
            }
            // les blocs reviennent à leur place (le serveur les remet) : on arrête de les dessiner un peu après
            DEBRIS.removeIf(x -> {
                if (secondes(x.debut) > 40) {
                    return true;
                }
                if (x.os) {
                    return secondes(x.debut) > (DinoPose.T_REPARE - DinoPose.T_EXPLO) + 1.4;
                }
                Dino d = DINOS.get(x.qui);
                return d == null || d.age / 20.0 > KaguyaDino.REMIS + 0.2;
            });
        }

        private Evenements() {
        }
    }

    /** CameraMixin : la caméra derrière elle, loin, quand elle est sur son dinosaure. */
    public static Vec3 camera(Camera camera, Entity entite, boolean detachee, float partiel) {
        Minecraft mc = Minecraft.getInstance();
        Dino d = mc.player == null ? null : DINOS.get(mc.player.getUUID());
        if (d == null || !detachee || entite != mc.player) {
            return null;
        }
        double t = t(d);
        if (t < DinoPose.T_SORT || t > DinoPose.T_EXPLO + 1.2) {
            return null;
        }
        double k = ClientKaguya.lisse((t - DinoPose.T_SORT) / 1.5) * (1 - ClientKaguya.lisse((t - DinoPose.T_EXPLO - 0.4) / 0.8));
        // derrière elle, haut, et un peu sur le côté : on voit le dinosaure de trois quarts (et les os qui jaillissent sous ses pas)
        double recul = Mth.lerp(k, 4, 46), haut = Mth.lerp(k, 1.6, 20), cote = k * 22;
        Vector3f f = camera.getLookVector(), g = camera.getLeftVector();
        Vec3 pied = entite.getPosition(partiel);
        return pied.add(0, haut, 0).subtract(f.x() * recul, f.y() * recul * 0.5, f.z() * recul).add(g.x() * cote, 0, g.z() * cote);
    }

    // ================================================================ le dessin

    static void dessiner(PoseStack ps, Vec3 cam, float partielJeu) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || DINOS.isEmpty() && DEBRIS.isEmpty()) {
            return;
        }
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        ModeleVent[] l = ModeleVent.lire("kaguya", "dino");
        for (Dino d : DINOS.values()) {
            double t = t(d);
            if (t < DinoPose.T_SORT - 0.05 || t >= DinoPose.T_EXPLO || l.length < DinoPose.N) {
                continue;
            }
            Vec3 pos = pos(d);
            float lacet = lacet(d);
            double distance = Mth.lerp(partiel(d), d.distancePrec, d.distance);
            Matrix4f[] mat = DinoPose.poser(t, distance, mord(d, t));
            Vec3 hanches = DinoPose.monde(mat, DinoPose.HANCHES, new Vector3f(), pos, lacet);
            int lumiere = LevelRenderer.getLightColor(mc.level, BlockPos.containing(hanches.x, pos.y + 2, hanches.z));
            for (int i = 0; i < DinoPose.N; i++) {
                ps.pushPose();
                ps.translate(pos.x - cam.x, pos.y - cam.y, pos.z - cam.z);
                ps.mulPose(Axis.YP.rotation(-lacet * Mth.DEG_TO_RAD));
                ps.last().pose().mul(mat[i]);
                ps.last().normal().mul(new Matrix3f(mat[i]).scale(1 / DinoPose.ECHELLE));
                l[i].dessiner(buffers, ps.last(), lumiere, 1, 1);
                ps.popPose();
            }
        }
        // les blocs qui volent, retombent, puis reviennent à leur place
        BlockRenderDispatcher blocs = mc.getBlockRenderer();
        for (Debris x : DEBRIS) {
            Dino d = DINOS.get(x.qui);
            double s = secondes(x.debut);
            // le temps de vol, jusqu'au sol
            double vy = x.vitesse.y, dy = x.depart.y - x.sol;
            double tVol = (vy + Math.sqrt(Math.max(0, vy * vy + 2 * GRAV * dy))) / GRAV;
            double sv = Math.min(s, tVol);
            Vec3 p = x.depart.add(x.vitesse.x * sv, vy * sv - GRAV / 2 * sv * sv, x.vitesse.z * sv);
            float angle = (float) (x.tourne * sv);
            // la réparation : les blocs remontent à leur place
            double retour = 0;
            if (!x.os && d != null) {
                double t = t(d);
                retour = ClientKaguya.lisse((t - DinoPose.T_REPARE) / (KaguyaDino.REMIS - DinoPose.T_REPARE));
            }
            float taille = x.taille;
            if (x.os) {
                double fond = ClientKaguya.lisse((s - (DinoPose.T_REPARE - DinoPose.T_EXPLO)) / 1.2);
                taille *= (float) (1 - fond);
                if (taille < 0.02F) {
                    continue;
                }
            }
            if (retour > 0) {
                Vec3 o = Vec3.atLowerCornerOf(x.origine);
                Vec3 haut = p.lerp(o, 0.5).add(0, 4, 0);
                // une courbe : il monte, puis se pose à sa place
                Vec3 a = p.lerp(haut, retour), b = haut.lerp(o, retour);
                p = a.lerp(b, retour);
                angle *= (float) (1 - retour);
            }
            ps.pushPose();
            ps.translate(p.x - cam.x + 0.5, p.y - cam.y + 0.5, p.z - cam.z + 0.5);
            ps.mulPose(Axis.YP.rotation(angle));
            ps.mulPose(Axis.XP.rotation(angle * 0.7F));
            ps.scale(taille, taille, taille);
            ps.translate(-0.5, -0.5, -0.5);
            int lumiere = LevelRenderer.getLightColor(mc.level, BlockPos.containing(p.x, p.y + 1, p.z));
            blocs.renderSingleBlock(x.etat, ps, buffers, lumiere, OverlayTexture.NO_OVERLAY);
            ps.popPose();
        }
        buffers.endBatch();
    }

    private ClientKaguyaDino() {
    }
}
