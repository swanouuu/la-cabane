package fr.cabane.client.pouvoir;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.math.Axis;
import fr.cabane.Cabane;
import fr.cabane.pouvoir.Hachibi;
import fr.cabane.pouvoir.Pouvoirs;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.PlayerSkin;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.player.PlayerModelPart;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.RenderHandEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.event.RenderPlayerEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * Hachibi, dessiné chez chaque joueur (voir {@link Hachibi}). Tout part du corps du joueur lui-même, avec sa pose et sa
 * marche. Le manteau : il rugit, puis une aura rouge très transparente l'épouse, des oreilles pointues lui poussent sur
 * la tête, et deux queues épaisses sortent du bas de son dos, l'une après l'autre, et bougent comme elles veulent. Dès
 * qu'il bouge, il marche à quatre pattes. La bête : une bulle de chakra l'avale, et il en ressort en silhouette rouge
 * sombre, opaque, un peu plus grande, avec une tête de démon (yeux ronds et dents qui brillent), deux oreilles et deux
 * queues de plus ; à l'arrêt, griffes levées ; en mouvement, à quatre pattes. Sa gueule charge la bombe de chakra (des
 * petites boules tournent et s'y jettent), qui file et explose. En première personne, on ne voit rien de soi.
 */
public final class ClientHachibi {
    private static final ResourceLocation BETE = Cabane.id("textures/entity/hachibi_bete.png");
    private static final ResourceLocation YEUX = Cabane.id("textures/entity/hachibi_yeux.png");
    static final ResourceLocation MANTEAU = Cabane.id("textures/entity/hachibi_manteau.png");
    /** Le manteau : le rugissement, puis la première queue, puis la seconde. */
    private static final double QUEUE_1 = 0.45;
    private static final double QUEUE_2 = 1.15;
    /** La bête : la bulle, puis la bête qui en sort, puis les deux nouvelles queues ; tout est fini à TRANSFORMATION. */
    private static final double TRANSFORMATION = Hachibi.TRANSFORMATION / 20.0;
    private static final double SORTIE = 0.6;
    private static final float TAILLE_BETE = 1.15F;
    /** La tête de la bête, ses oreilles et ses queues ; le manteau reprend exactement les mêmes tailles. */
    private static final float TETE_BETE = 1.35F;
    private static final float OREILLE = 1.3F;
    private static final float QUEUE_LARGEUR = 0.42F;
    private static final float QUEUE_LONGUEUR = 2.0F;
    private static final int QUEUE_MORCEAUX = 12;
    private static final double DISPARITION = 0.6;
    /** La bombe parcourt 3 × 0,45 bloc par tick, comme sur le serveur. */
    private static final double VITESSE_BOMBE = 1.35 * 20;
    private static final double TAILLE_BOMBE = 1.1;
    private static final RandomSource HASARD = RandomSource.create();

    private static final class Hachi {
        long debut;
        long fin = -1;
        /** 0 : Hachibi ; 1 à 4 : le niveau de Hachibi 2 (manteau, bête, bête aux os, poulpe), et depuis quand. */
        int forme;
        long formeDebut = -1;
        /** Niveau 4 : le dernier coup de tentacule (et sa cible, -1 : aucune), le dernier balayage. */
        long fouet = -1;
        int fouetCible = -1;
        Vec3 fouetVers;
        long balayage = -1;
        /** La saisie : ce que tient la tentacule (-1 : rien), depuis quand ; le dernier coup de serrage. */
        int prise = -1;
        long priseDebut = -1;
        long serre = -1;
        /** La bombe en cours : 0 la violette, 1 la grosse, 2 la noire. */
        int genre;
        boolean bete;
        long beteDebut = -1;
        long beteFin = -1;
        long course = -1;
        long courseFin = -1;
        long griffe = -1;
        int main;
        long charge = -1;
        long tir = -1;
        /** Le dernier tir (pour la recharge de la bombe). */
        long dernierTir = -1;
        Vec3 depart;
        Vec3 direction;
        long explosion = -1;
        Vec3 boum;
        /** De 0 (debout) à 1 (à quatre pattes), lissé d'une image à l'autre. */
        float quatre;
        double tPrecedent = -1;
        double tbPrecedent = -1;
    }

    private static final Map<UUID, Hachi> HACHIS = new HashMap<>();
    private static long dernierClic;
    private static boolean accroupiAvant;
    /** Niveau 4 : quand on s'est accroupi, et si on a lancé la bombe noire depuis (alors la tentacule n'attrape pas). */
    private static long accroupiDebut;
    private static boolean bombeFaite;
    private static long secousse = -1;

    private static double secondes(long depuis) {
        return (System.nanoTime() - depuis) / 1e9;
    }

    private static double lisse(double x) {
        x = Mth.clamp(x, 0, 1);
        return x * x * (3 - 2 * x);
    }

    private static boolean actif(Hachi h) {
        return h != null && h.fin < 0;
    }

    /** De 0 à 1 : la bête est sortie de sa bulle. */
    private static float sortie(Hachi h) {
        return !h.bete ? 0 : (float) lisse((secondes(h.beteDebut) - SORTIE) / 0.5);
    }

    private static void son(Player joueur, SoundEvent son, float volume, float hauteur) {
        joueur.level().playLocalSound(joueur.getX(), joueur.getY() + 1, joueur.getZ(), son, SoundSource.PLAYERS, volume, hauteur, false);
    }

    private static DustParticleOptions rouge(float taille) {
        return new DustParticleOptions(new Vector3f(0.95F, 0.12F, 0.05F), taille);
    }

    private static double aleat(double etendue) {
        return (HASARD.nextDouble() - 0.5) * 2 * etendue;
    }

    // ================================================================ ce que dit le serveur

    public static void recevoir(Pouvoirs.Etat p) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return;
        }
        Hachi h = HACHIS.get(p.joueur());
        Player joueur = mc.level.getPlayerByUUID(p.joueur());
        long maintenant = System.nanoTime();
        if (p.eveille()) {
            if (!actif(h)) {
                h = new Hachi();
                h.debut = maintenant - (p.annonce() ? 0 : 10_000_000_000L);
                h.tPrecedent = p.annonce() ? -1 : 10;
                HACHIS.put(p.joueur(), h);
            }
            int forme = p.pouvoir().equals(Hachibi.NOM2) ? p.niveau() : 0;
            if (forme != h.forme) {
                changerForme(h, forme, p.annonce() ? maintenant : maintenant - 10_000_000_000L, joueur);
            }
        } else if (h != null) {
            if (!p.annonce()) {
                HACHIS.remove(p.joueur());
                return;
            }
            h.fin = maintenant;
            if (joueur != null) {
                son(joueur, SoundEvents.FIRE_EXTINGUISH, 1F, 0.6F);
                for (int i = 0; i < 20; i++) {
                    mc.level.addParticle(rouge(1.2F), joueur.getX() + aleat(0.6), joueur.getY() + HASARD.nextDouble() * 2, joueur.getZ() + aleat(0.6), 0, 0.05, 0);
                }
            }
        }
    }

    /**
     * Hachibi 2 passe à un autre niveau : la bête sort de sa bulle (niveau 2), le squelette pousse sur elle avec deux
     * queues de plus (niveau 3), puis elle éclate et il en ressort lui-même, les tentacules dans le dos (niveau 4).
     */
    private static void changerForme(Hachi h, int forme, long quand, Player joueur) {
        Minecraft mc = Minecraft.getInstance();
        int avant = h.forme;
        h.forme = forme;
        h.formeDebut = quand;
        if ((forme == 2 || forme == 3) && !h.bete) {
            h.bete = true;
            h.beteDebut = quand;
            h.tbPrecedent = quand < System.nanoTime() - 1_000_000_000L ? 10 : -1;
            h.course = -1;
        } else if (forme != 2 && forme != 3 && h.bete) {
            h.bete = false;
            h.beteFin = quand;
            h.charge = -1;
        }
        if (joueur == null || quand < System.nanoTime() - 1_000_000_000L) {
            return;
        }
        if (forme == 2) {
            son(joueur, SoundEvents.RAVAGER_ROAR, 1F, 0.7F);
            son(joueur, SoundEvents.ENDER_DRAGON_GROWL, 0.7F, 1.2F);
        } else if (forme == 3) {
            son(joueur, SoundEvents.SKELETON_HORSE_DEATH, 1F, 0.5F);
            son(joueur, SoundEvents.RAVAGER_ROAR, 1F, 0.5F);
            son(joueur, SoundEvents.WITHER_SKELETON_AMBIENT, 1F, 0.6F);
        } else if (forme == 4) {
            son(joueur, SoundEvents.GENERIC_EXPLODE.value(), 0.7F, 1.3F);
            son(joueur, SoundEvents.SQUID_SQUIRT, 1.4F, 0.5F);
            son(joueur, SoundEvents.ELDER_GUARDIAN_AMBIENT, 1F, 0.8F);
        }
        if (forme > avant && forme >= 3) {
            for (int i = 0; i < 40; i++) {
                double a = HASARD.nextDouble() * Math.PI * 2;
                mc.level.addParticle(rouge(1.5F), joueur.getX() + Math.cos(a) * 0.6, joueur.getY() + 0.2 + HASARD.nextDouble() * 2, joueur.getZ() + Math.sin(a) * 0.6,
                        Math.cos(a) * 0.25, 0.05, Math.sin(a) * 0.25);
            }
        }
    }

    /** Un Susano s'éveille chez ce joueur : il n'est plus Hachibi. */
    public static void oublier(UUID joueur) {
        HACHIS.remove(joueur);
    }

    public static void geste(Pouvoirs.Geste p) {
        Hachi h = HACHIS.get(p.joueur());
        Minecraft mc = Minecraft.getInstance();
        if (!actif(h) || mc.level == null) {
            return;
        }
        Player joueur = mc.level.getPlayerByUUID(p.joueur());
        long maintenant = System.nanoTime();
        switch (p.geste()) {
            case Hachibi.GESTE_GRIFFE -> {
                h.griffe = maintenant;
                h.main = p.cible();
                if (joueur != null) {
                    son(joueur, SoundEvents.PLAYER_ATTACK_SWEEP, 1F, h.bete ? 0.7F : 1.3F);
                    son(joueur, SoundEvents.WOLF_GROWL, 0.8F, h.bete ? 0.5F : 0.8F);
                }
            }
            case Hachibi.GESTE_BETE -> {
                if (p.cible() == 1) {
                    h.bete = true;
                    h.beteDebut = maintenant;
                    h.tbPrecedent = -1;
                    h.course = -1;
                    if (joueur != null) {
                        son(joueur, SoundEvents.RAVAGER_ROAR, 1F, 0.7F);
                        son(joueur, SoundEvents.ENDER_DRAGON_GROWL, 0.7F, 1.2F);
                    }
                } else {
                    h.bete = false;
                    h.beteFin = maintenant;
                    h.charge = -1;
                    if (joueur != null) {
                        son(joueur, SoundEvents.FIRE_EXTINGUISH, 1F, 0.5F);
                        for (int i = 0; i < 25; i++) {
                            mc.level.addParticle(rouge(1.4F), joueur.getX() + aleat(0.8), joueur.getY() + HASARD.nextDouble() * 2, joueur.getZ() + aleat(0.8), 0, 0.04, 0);
                        }
                    }
                }
            }
            case Hachibi.GESTE_FOUET -> {
                h.fouet = maintenant;
                h.fouetCible = p.cible();
                h.fouetVers = null;
                if (joueur != null) {
                    son(joueur, SoundEvents.SLIME_JUMP, 1F, 0.5F);
                }
            }
            case Hachibi.GESTE_PRISE -> {
                if (p.cible() >= 0) {
                    h.prise = p.cible();
                    h.priseDebut = maintenant;
                    if (joueur != null) {
                        son(joueur, SoundEvents.SLIME_JUMP, 1.2F, 0.4F);
                    }
                } else {
                    h.prise = -1;
                }
            }
            case Hachibi.GESTE_SERRE -> {
                h.serre = maintenant;
                if (mc.level.getEntity(p.cible()) instanceof net.minecraft.world.entity.Entity proie) {
                    for (int i = 0; i < 20; i++) {
                        mc.level.addParticle(rouge(1.2F), proie.getX() + aleat(0.5), proie.getY() + HASARD.nextDouble() * proie.getBbHeight(),
                                proie.getZ() + aleat(0.5), aleat(0.1), 0.05, aleat(0.1));
                    }
                }
            }
            case Hachibi.GESTE_LANCE -> {
                h.prise = -1;
                if (joueur != null) {
                    son(joueur, SoundEvents.PLAYER_ATTACK_SWEEP, 1.2F, 0.6F);
                }
            }
            case Hachibi.GESTE_BALAYAGE -> {
                h.balayage = maintenant;
                if (joueur != null) {
                    son(joueur, SoundEvents.SLIME_SQUISH, 1.2F, 0.5F);
                }
            }
            case Hachibi.GESTE_COURSE -> {
                if (p.cible() == 1) {
                    h.course = maintenant;
                    h.courseFin = -1;
                    if (joueur != null) {
                        son(joueur, SoundEvents.ENDER_DRAGON_FLAP, 1F, 1.6F);
                        son(joueur, SoundEvents.FIRECHARGE_USE, 0.6F, 1.4F);
                    }
                } else {
                    h.course = -1;
                    h.courseFin = maintenant;
                }
            }
            default -> {
            }
        }
    }

    public static void boule(Hachibi.Boule p) {
        Hachi h = HACHIS.get(p.joueur());
        Minecraft mc = Minecraft.getInstance();
        if (h == null || mc.level == null) {
            return;
        }
        long maintenant = System.nanoTime();
        h.genre = p.genre();
        switch (p.etape()) {
            case 0 -> h.charge = maintenant;
            case 1 -> {
                h.charge = -1;
                h.tir = maintenant;
                h.dernierTir = maintenant;
                h.depart = new Vec3(p.pos());
                h.direction = new Vec3(p.direction());
            }
            default -> {
                h.tir = -1;
                h.explosion = maintenant;
                h.boum = new Vec3(p.pos());
                if (mc.player != null && mc.player.position().distanceTo(h.boum) < 32) {
                    secousse = maintenant;
                }
            }
        }
    }

    // ================================================================ la pose (appelé par PlayerModelMixin)

    /**
     * Au manteau : un rugissement (la tête renversée, les bras ouverts). Dès qu'il bouge : la marche à quatre pattes. Le corps est penché vers l'avant (voir {@link #pencher}), les bras et
     * les jambes descendent droit vers le sol en se croisant comme ceux d'un animal, la tête se relève pour regarder
     * devant.
     */
    public static void poser(PlayerModel<?> modele, Player joueur) {
        Hachi h = HACHIS.get(joueur.getUUID());
        if (!actif(h) || h.forme == 4) {
            return;
        }
        double t = secondes(h.debut);
        float q = h.quatre;
        float rugir = h.bete ? 0 : (float) (lisse(t / 0.2) * (1 - lisse((t - 0.9) / 0.35)));
        if (rugir > 0) {
            modele.head.xRot = Mth.lerp(rugir, modele.head.xRot, -0.7F);
            modele.rightArm.zRot = Mth.lerp(rugir, modele.rightArm.zRot, 1.1F);
            modele.leftArm.zRot = Mth.lerp(rugir, modele.leftArm.zRot, -1.1F);
            modele.rightArm.xRot = Mth.lerp(rugir, modele.rightArm.xRot, -0.4F);
            modele.leftArm.xRot = Mth.lerp(rugir, modele.leftArm.xRot, -0.4F);
        }
        if (q > 0.01F) {
            float penche = 1.4F * q;
            modele.head.xRot -= penche;
            modele.rightLeg.xRot -= penche;
            modele.leftLeg.xRot -= penche;
            // les bras marchent comme des pattes avant, en croisé avec les jambes
            float pas = joueur.walkAnimation.position() * 0.6662F;
            float ampleur = 1.1F * Math.min(1, joueur.walkAnimation.speed());
            modele.rightArm.xRot = Mth.lerp(q, modele.rightArm.xRot, Mth.cos(pas + Mth.PI) * ampleur - 1.4F);
            modele.leftArm.xRot = Mth.lerp(q, modele.leftArm.xRot, Mth.cos(pas) * ampleur - 1.4F);
            modele.rightArm.zRot *= 1 - q;
            modele.leftArm.zRot *= 1 - q;
            modele.rightArm.yRot *= 1 - q;
            modele.leftArm.yRot *= 1 - q;
        }
        if (rugir > 0 || q > 0.01F || h.bete) {
            modele.hat.copyFrom(modele.head);
            modele.rightSleeve.copyFrom(modele.rightArm);
            modele.leftSleeve.copyFrom(modele.leftArm);
            modele.rightPants.copyFrom(modele.rightLeg);
            modele.leftPants.copyFrom(modele.leftLeg);
        }
    }

    /** Penche tout le joueur vers l'avant, autour de ses hanches (q = 1 : à l'horizontale, ou presque). */
    private static void pencher(PoseStack ps, Player joueur, float partiel, float q) {
        if (q <= 0.01F) {
            return;
        }
        float lacet = Mth.rotLerp(partiel, joueur.yBodyRotO, joueur.yBodyRot);
        ps.translate(0, 0.75F, 0);
        ps.mulPose(Axis.YP.rotationDegrees(-lacet));
        ps.mulPose(Axis.XP.rotationDegrees(80 * q));
        ps.mulPose(Axis.YP.rotationDegrees(lacet));
        ps.translate(0, -0.75F - 0.1F * q, 0);
    }

    /** Passe dans le repère du modèle du joueur (comme le fait le jeu avant de le dessiner). */
    private static void repereModele(PoseStack ps, Player joueur, float partiel, float taille) {
        ps.mulPose(Axis.YP.rotationDegrees(180 - Mth.rotLerp(partiel, joueur.yBodyRotO, joueur.yBodyRot)));
        ps.scale(-1, -1, 1);
        ps.scale(0.9375F * taille, 0.9375F * taille, 0.9375F * taille);
        ps.translate(0, -1.501F, 0);
    }

    // ================================================================ les oreilles et les queues, en blocs texturés

    /** Le coin de texture où sont pris les blocs (une zone tachetée du corps). */
    private static final float U0 = 20 / 64F;
    private static final float V0 = 20 / 64F;
    private static final float U1 = 27 / 64F;
    private static final float V1 = 27 / 64F;

    /** Un point de texture d'une seule couleur (la case pleine ajoutée par hachibi.mjs) : pour un chakra uni. */
    private static final float U_UNI = 60 / 64F;
    private static final float V_UNI = 20 / 64F;

    private static void bloc(VertexConsumer c, PoseStack.Pose p, float x0, float y0, float z0, float x1, float y1, float z1, int lumiere, int couleur) {
        bloc(c, p, x0, y0, z0, x1, y1, z1, lumiere, couleur, false);
    }

    private static void bloc(VertexConsumer c, PoseStack.Pose p, float x0, float y0, float z0, float x1, float y1, float z1, int lumiere, int couleur, boolean uni) {
        face(c, p, lumiere, couleur, uni, 0, -1, 0, x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1);
        face(c, p, lumiere, couleur, uni, 0, 1, 0, x0, y1, z0, x0, y1, z1, x1, y1, z1, x1, y1, z0);
        face(c, p, lumiere, couleur, uni, 0, 0, -1, x0, y0, z0, x0, y1, z0, x1, y1, z0, x1, y0, z0);
        face(c, p, lumiere, couleur, uni, 0, 0, 1, x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1);
        face(c, p, lumiere, couleur, uni, -1, 0, 0, x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0);
        face(c, p, lumiere, couleur, uni, 1, 0, 0, x1, y0, z0, x1, y1, z0, x1, y1, z1, x1, y0, z1);
    }

    private static void face(VertexConsumer c, PoseStack.Pose p, int lumiere, int couleur, boolean uni, float nx, float ny, float nz, float... q) {
        float[] u = uni ? new float[]{U_UNI, U_UNI, U_UNI, U_UNI} : new float[]{U0, U0, U1, U1};
        float[] v = uni ? new float[]{V_UNI, V_UNI, V_UNI, V_UNI} : new float[]{V0, V1, V1, V0};
        for (int i = 0; i < 4; i++) {
            c.addVertex(p, q[i * 3], q[i * 3 + 1], q[i * 3 + 2]).setColor(couleur).setUv(u[i], v[i])
                    .setOverlay(OverlayTexture.NO_OVERLAY).setLight(lumiere).setNormal(p, nx, ny, nz);
        }
    }

    /**
     * Deux oreilles pointues sur le dessus de la tête (dans le repère de la tête : le haut est vers -Y), un peu
     * écartées vers l'extérieur ; trois blocs de plus en plus fins.
     */
    private static void oreilles(VertexConsumer c, PoseStack ps, float taille, float ecart, int lumiere, int couleur, boolean uni) {
        for (int cote = -1; cote <= 1; cote += 2) {
            ps.pushPose();
            ps.translate(cote * 0.15F, -0.48F, 0.03F);
            ps.mulPose(Axis.ZP.rotationDegrees(cote * ecart));
            ps.scale(taille, taille, taille);
            bloc(c, ps.last(), -0.09F, -0.12F, -0.05F, 0.09F, 0.0F, 0.05F, lumiere, couleur, uni);
            bloc(c, ps.last(), -0.065F, -0.24F, -0.04F, 0.065F, -0.12F, 0.04F, lumiere, couleur, uni);
            bloc(c, ps.last(), -0.035F, -0.36F, -0.03F, 0.035F, -0.24F, 0.03F, lumiere, couleur, uni);
            ps.popPose();
        }
    }

    /**
     * Une queue épaisse, qui sort du bas du dos (dans le repère du corps : le dos est vers +Z, le haut vers -Y) et part
     * en arrière en remontant. Elle est faite de beaucoup de morceaux qui se chevauchent (on ne voit pas les jointures),
     * et s'affine vers le bout ; une onde la parcourt de la base au bout, comme une vraie queue qui fouette doucement.
     * Elle reste en arrière même quand le joueur marche à quatre pattes ({@code contre} : l'angle dont il est penché).
     */
    private static void queue(VertexConsumer c, PoseStack ps, float lacet, float tangage, float largeur, float longueur, int n,
                              double t, int numero, float pousse, float contre, int lumiere, int couleur, boolean uni) {
        int sortis = (int) Math.ceil(pousse * n);
        if (sortis <= 0) {
            return;
        }
        float pas = longueur / n;
        float onde = 9F / n;
        ps.pushPose();
        ps.translate(0, 0.6F, 0.08F);
        ps.mulPose(Axis.XP.rotationDegrees(-contre));
        ps.mulPose(Axis.YP.rotationDegrees(lacet + (float) Math.sin(t * 0.9 + numero * 1.7) * 8));
        ps.mulPose(Axis.XP.rotationDegrees(tangage));
        for (int i = 0; i < sortis; i++) {
            float k = i / (float) n;
            // elle s'affine doucement, puis finit en pointe sur son dernier tiers
            float pointe = k < 0.65F ? 1 : 1 - (k - 0.65F) / 0.35F * 0.88F;
            float l = largeur * (1 - 0.4F * k) * pointe;
            ps.mulPose(Axis.YP.rotationDegrees((float) Math.sin(t * 2.0 - i * 0.42 + numero * 2.3) * 9 * onde));
            ps.mulPose(Axis.XP.rotationDegrees((float) (2.2 + Math.sin(t * 1.5 - i * 0.35 + numero * 1.1) * 5) * onde));
            bloc(c, ps.last(), -l / 2, -l / 2, -pas * 0.3F, l / 2, l / 2, pas * 1.3F, lumiere, couleur, uni);
            ps.translate(0, 0, pas);
        }
        ps.popPose();
    }

    // ================================================================ le manteau et la bête, sur le joueur

    /**
     * Pose le modèle du joueur comme le ferait le jeu (sa marche, son regard, son coup de bras), avec la pose de
     * Hachibi par-dessus (voir {@link #poser}). Dans la bête, toutes les parties sont visibles (la peau de la bête n'a pas
     * de deuxième couche) ; au manteau, celles que le joueur a choisies.
     */
    private static void preparer(PlayerModel<AbstractClientPlayer> modele, AbstractClientPlayer joueur, float partiel, boolean bete) {
        float corps = Mth.rotLerp(partiel, joueur.yBodyRotO, joueur.yBodyRot);
        float tete = Mth.rotLerp(partiel, joueur.yHeadRotO, joueur.yHeadRot);
        float marche = joueur.walkAnimation.position(partiel);
        float vitesse = Math.min(1, joueur.walkAnimation.speed(partiel));
        modele.attackTime = joueur.getAttackAnim(partiel);
        modele.riding = false;
        modele.young = false;
        modele.crouching = !bete && joueur.isCrouching();
        modele.rightArmPose = HumanoidModel.ArmPose.EMPTY;
        modele.leftArmPose = HumanoidModel.ArmPose.EMPTY;
        modele.setAllVisible(true);
        if (!bete) {
            modele.hat.visible = joueur.isModelPartShown(PlayerModelPart.HAT);
            modele.jacket.visible = joueur.isModelPartShown(PlayerModelPart.JACKET);
            modele.leftPants.visible = joueur.isModelPartShown(PlayerModelPart.LEFT_PANTS_LEG);
            modele.rightPants.visible = joueur.isModelPartShown(PlayerModelPart.RIGHT_PANTS_LEG);
            modele.leftSleeve.visible = joueur.isModelPartShown(PlayerModelPart.LEFT_SLEEVE);
            modele.rightSleeve.visible = joueur.isModelPartShown(PlayerModelPart.RIGHT_SLEEVE);
        }
        modele.prepareMobModel(joueur, marche, vitesse, partiel);
        modele.setupAnim(joueur, marche, vitesse, joueur.tickCount + partiel, Mth.wrapDegrees(tete - corps), Mth.lerp(partiel, joueur.xRotO, joueur.getXRot()));
    }

    /**
     * Le joueur au manteau, dessiné par nous (rien d'autre ne se dessine sur lui : ni objet, ni armure, ni accessoire,
     * rien ne dépasse de l'aura). Sa peau, puis l'aura : chaque partie du corps (tête, corps, bras, jambes) redessinée
     * autour de son propre centre, assez grosse pour recouvrir la deuxième couche de la peau, d'un rouge uni qui
     * brille ; un halo plus large et plus léger autour. Puis les oreilles et les deux queues, du même rouge.
     */
    private static void manteau(RenderPlayerEvent.Pre event, Hachi h, AbstractClientPlayer joueur) {
        PoseStack ps = event.getPoseStack();
        MultiBufferSource buffers = event.getMultiBufferSource();
        float partiel = event.getPartialTick();
        double t = secondes(h.debut);
        float force = (float) lisse(t / 0.5) * (h.fin < 0 ? 1 : 1 - (float) lisse(secondes(h.fin) / DISPARITION));
        float vacille = 0.9F + 0.1F * Mth.sin((float) t * 7);
        PlayerModel<AbstractClientPlayer> modele = event.getRenderer().getModel();
        boolean fin = joueur.getSkin().model() == PlayerSkin.Model.SLIM;
        ps.pushPose();
        pencher(ps, joueur, partiel, h.quatre);
        repereModele(ps, joueur, partiel, 1);
        preparer(modele, joueur, partiel, false);
        modele.renderToBuffer(ps, buffers.getBuffer(RenderType.entityCutoutNoCull(joueur.getSkin().texture())), event.getPackedLight(),
                LivingEntityRenderer.getOverlayCoords(joueur, 0), 0xFFFFFFFF);
        VertexConsumer c = buffers.getBuffer(RenderType.entityTranslucentEmissive(MANTEAU));
        enveloppe(modele, ps, c, fin, 1F, (Math.round(185 * force * vacille) << 24) | 0xF0281C);
        enveloppe(modele, ps, c, fin, 1.12F, (Math.round(50 * force * vacille) << 24) | 0xFF3A24);
        int couleur = (Math.round(195 * force * vacille) << 24) | 0xF02C1E;
        ps.pushPose();
        modele.head.translateAndRotate(ps);
        // les mêmes oreilles et les mêmes queues que la bête, à la même taille (la bête est plus grande, sa tête aussi)
        oreilles(c, ps, OREILLE * TETE_BETE * TAILLE_BETE, 20, LightTexture.FULL_BRIGHT, couleur, true);
        ps.popPose();
        ps.pushPose();
        modele.body.translateAndRotate(ps);
        float contre = 80 * h.quatre;
        queue(c, ps, -14, 40, QUEUE_LARGEUR * TAILLE_BETE, QUEUE_LONGUEUR * TAILLE_BETE, QUEUE_MORCEAUX, t, 0, (float) lisse((t - QUEUE_1) / 0.6), contre, LightTexture.FULL_BRIGHT, couleur, true);
        queue(c, ps, 14, 40, QUEUE_LARGEUR * TAILLE_BETE, QUEUE_LONGUEUR * TAILLE_BETE, QUEUE_MORCEAUX, t, 1, (float) lisse((t - QUEUE_2) / 0.6), contre, LightTexture.FULL_BRIGHT, couleur, true);
        ps.popPose();
        ps.popPose();
    }

    /**
     * Le corps redessiné partie par partie, chacune gonflée autour de son centre (en pixels de peau) un peu plus que sa
     * deuxième couche ({@code plus} : encore plus, pour le halo).
     */
    static void enveloppe(PlayerModel<?> m, PoseStack ps, VertexConsumer c, boolean fin, float plus, int couleur) {
        partie(m.head, ps, c, 0, -4, 0, 1.12F * plus, 1.12F * plus, 1.12F * plus, couleur);
        partie(m.body, ps, c, 0, 6, 0, 1.12F * plus, 1.07F * plus, 1.25F * plus, couleur);
        partie(m.rightArm, ps, c, fin ? -0.5F : -1, 4, 0, 1.24F * plus, 1.07F * plus, 1.2F * plus, couleur);
        partie(m.leftArm, ps, c, fin ? 0.5F : 1, 4, 0, 1.24F * plus, 1.07F * plus, 1.2F * plus, couleur);
        partie(m.rightLeg, ps, c, 0, 6, 0, 1.2F * plus, 1.06F * plus, 1.2F * plus, couleur);
        partie(m.leftLeg, ps, c, 0, 6, 0, 1.2F * plus, 1.06F * plus, 1.2F * plus, couleur);
    }

    private static void partie(ModelPart p, PoseStack ps, VertexConsumer c, float cx, float cy, float cz, float sx, float sy, float sz, int couleur) {
        if (!p.visible) {
            return;
        }
        ps.pushPose();
        p.translateAndRotate(ps);
        ps.translate(cx / 16, cy / 16, cz / 16);
        ps.scale(sx, sy, sz);
        ps.translate(-cx / 16, -cy / 16, -cz / 16);
        // la partie est déjà placée : on la dessine sans qu'elle se replace une deuxième fois
        PartPose avant = p.storePose();
        float xs = p.xScale, ys = p.yScale, zs = p.zScale;
        p.loadPose(PartPose.ZERO);
        p.xScale = p.yScale = p.zScale = 1;
        p.render(ps, c, LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY, couleur);
        p.loadPose(avant);
        p.xScale = xs;
        p.yScale = ys;
        p.zScale = zs;
        ps.popPose();
    }

    /**
     * La bête, à la place du joueur : son corps (posé comme lui, penché s'il marche), un peu plus grand, avec la peau de
     * la bête, ses yeux et ses dents qui brillent, ses oreilles de démon et ses quatre queues (les deux dernières
     * poussent pendant la transformation).
     */
    private static void bete(RenderPlayerEvent.Pre event, Hachi h, AbstractClientPlayer joueur) {
        PoseStack ps = event.getPoseStack();
        MultiBufferSource buffers = event.getMultiBufferSource();
        float partiel = event.getPartialTick();
        int lumiere = event.getPackedLight();
        double t = secondes(h.debut);
        double tb = secondes(h.beteDebut);
        PlayerModel<AbstractClientPlayer> modele = event.getRenderer().getModel();
        ps.pushPose();
        pencher(ps, joueur, partiel, h.quatre);
        repereModele(ps, joueur, partiel, TAILLE_BETE * (0.6F + 0.4F * sortie(h)));
        preparer(modele, joueur, partiel, true);
        // une grosse tête : le visage de démon doit se voir de loin
        grosseTete(modele, TETE_BETE);
        modele.renderToBuffer(ps, buffers.getBuffer(RenderType.entityCutoutNoCull(BETE)), lumiere, LivingEntityRenderer.getOverlayCoords(joueur, 0), 0xFFFFFFFF);
        modele.renderToBuffer(ps, buffers.getBuffer(RenderType.eyes(YEUX)), LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY, 0xFFFFFFFF);
        VertexConsumer c = buffers.getBuffer(RenderType.entityCutoutNoCull(BETE));
        ps.pushPose();
        modele.head.translateAndRotate(ps);
        oreilles(c, ps, OREILLE, 20, lumiere, 0xFFFFFFFF, false);
        ps.popPose();
        // Hachibi 2, niveau 3 : le squelette pousse sur la bête (la tête est encore grosse : le crâne la suit)
        boolean os = h.forme == 3;
        if (os) {
            ClientSquelette.dessiner(modele, ps, buffers, lumiere, joueur.getSkin().model() == PlayerSkin.Model.SLIM,
                    (float) lisse(secondes(h.formeDebut) / 0.8));
            c = buffers.getBuffer(RenderType.entityCutoutNoCull(BETE));
        }
        grosseTete(modele, 1);
        ps.pushPose();
        modele.body.translateAndRotate(ps);
        float contre = 80 * h.quatre;
        float nouvelles = (float) lisse((tb - 0.9) / (TRANSFORMATION - 0.9));
        // au niveau 3, six queues en éventail, plus épaisses ; les deux dernières poussent avec le squelette
        float[][] angles = os ? new float[][]{{-12, 46}, {12, 46}, {-34, 36}, {34, 36}, {-56, 26}, {56, 26}} : new float[][]{{-14, 40}, {14, 40}, {-38, 30}, {38, 30}};
        float epais = os ? 1.25F : 1;
        float sixieme = (float) lisse((secondes(h.formeDebut) - 0.2) / 0.8);
        for (int i = 0; i < angles.length; i++) {
            float pousse = i < 2 ? 1 : i < 4 ? Mth.clamp(nouvelles * 2 - (i - 2), 0, 1) : Mth.clamp(sixieme * 2 - (i - 4), 0, 1);
            queue(c, ps, angles[i][0], angles[i][1], QUEUE_LARGEUR * epais, QUEUE_LONGUEUR * (os ? 1.1F : 1), QUEUE_MORCEAUX, t, i, pousse, contre, lumiere,
                    0xFFFFFFFF, false);
        }
        ps.popPose();
        ps.popPose();
    }

    /**
     * Hachibi 2, niveau 4 : le joueur, dessiné par le jeu, et ses huit tentacules collées au bas de son dos (elles
     * suivent son corps). Celle qui fouette ou balaie est dessinée à part, dans le monde.
     */
    private static void tentacules(RenderPlayerEvent.Post event, Hachi h, AbstractClientPlayer joueur) {
        PoseStack ps = event.getPoseStack();
        float partiel = event.getPartialTick();
        PlayerModel<AbstractClientPlayer> modele = event.getRenderer().getModel();
        float k = (float) (1 - Math.pow(1 - Mth.clamp(secondes(h.formeDebut) / 0.8, 0, 1), 3));
        if (h.fin >= 0) {
            k *= 1 - (float) lisse(secondes(h.fin) / DISPARITION);
        }
        int sauf = (h.fouet >= 0 && secondes(h.fouet) < ClientPoulpe.DUREE_FOUET ? 1 << ClientPoulpe.FOUET : 0)
                | (h.balayage >= 0 && secondes(h.balayage) < ClientPoulpe.DUREE_BALAYAGE ? 1 << ClientPoulpe.BALAI : 0)
                | (h.prise >= 0 ? 1 << ClientPoulpe.PRISE : 0);
        ps.pushPose();
        repereModele(ps, joueur, partiel, 1);
        modele.body.translateAndRotate(ps);
        ps.scale(1 / 16F, 1 / 16F, 1 / 16F);
        ps.scale(1, -1, -1);
        ps.translate(0, -24, 0);
        ClientPoulpe.toutes(ClientPoulpe.tampon(event.getMultiBufferSource()), ps.last(), event.getPackedLight(), (joueur.tickCount + partiel) / 20.0, k, sauf);
        ps.popPose();
    }

    /** Grossit la tête (et sa deuxième couche) autour du cou ; 1 pour la remettre comme avant. */
    private static void grosseTete(PlayerModel<?> modele, float taille) {
        modele.head.xScale = modele.head.yScale = modele.head.zScale = taille;
        modele.hat.xScale = modele.hat.yScale = modele.hat.zScale = taille;
    }

    // ================================================================ dans le monde : la bulle et la bombe

    /** Une sphère en bandes (latitude par latitude), de deux couleurs qui alternent. */
    private static void sphere(VertexConsumer c, Matrix4f m, double cx, double cy, double cz, float r, float[] a1, float[] a2, float alpha) {
        int lat = 10;
        int lon = 16;
        for (int i = 0; i < lat; i++) {
            float t0 = Mth.PI * i / lat, t1 = Mth.PI * (i + 1) / lat;
            float[] rgb = (i % 2 == 0) ? a1 : a2;
            for (int j = 0; j < lon; j++) {
                float p0 = Mth.TWO_PI * j / lon, p1 = Mth.TWO_PI * (j + 1) / lon;
                point(c, m, cx, cy, cz, r, t0, p0, rgb, alpha);
                point(c, m, cx, cy, cz, r, t1, p0, rgb, alpha);
                point(c, m, cx, cy, cz, r, t1, p1, rgb, alpha);
                point(c, m, cx, cy, cz, r, t0, p1, rgb, alpha);
            }
        }
    }

    private static void point(VertexConsumer c, Matrix4f m, double cx, double cy, double cz, float r, float theta, float phi, float[] rgb, float a) {
        float s = Mth.sin(theta);
        c.addVertex(m, (float) (cx + r * s * Mth.cos(phi)), (float) (cy + r * Mth.cos(theta)), (float) (cz + r * s * Mth.sin(phi)))
                .setColor(rgb[0], rgb[1], rgb[2], a);
    }

    private static final float[] ROUGE = {0.62F, 0.06F, 0.05F};
    private static final float[] ROUGE_SOMBRE = {0.32F, 0.03F, 0.03F};
    private static final float[] LUEUR = {1F, 0.18F, 0.05F};
    private static final float[] VIOLET = {0.42F, 0.1F, 0.62F};
    private static final float[] VIOLET_SOMBRE = {0.26F, 0.04F, 0.42F};

    /** La gueule de la bête, là où se forme la bombe (devant elle, à hauteur de tête). */
    private static Vec3 gueule(Player joueur, float partiel) {
        float lacet = Mth.rotLerp(partiel, joueur.yBodyRotO, joueur.yBodyRot) * Mth.DEG_TO_RAD;
        return joueur.getPosition(partiel).add(-Mth.sin(lacet) * 2.0, 1.3, Mth.cos(lacet) * 2.0);
    }

    private static void dessiner(PoseStack ps, Vec3 camera, float partiel) {
        Minecraft mc = Minecraft.getInstance();
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        Matrix4f m = ps.last().pose();
        for (Map.Entry<UUID, Hachi> entree : HACHIS.entrySet()) {
            Hachi h = entree.getValue();
            Player joueur = mc.level.getPlayerByUUID(entree.getKey());
            // la bulle de chakra qui avale le joueur, avant que la bête en sorte
            if (joueur != null && h.bete && !joueur.isInvisible()) {
                double tb = secondes(h.beteDebut);
                float bulle = (float) (lisse(tb / 0.35) * (1 - lisse((tb - SORTIE) / 0.5)));
                if (bulle > 0.01F) {
                    Vec3 p = joueur.getPosition(partiel).subtract(camera);
                    sphere(buffers.getBuffer(Types.TRANSLUCIDE), m, p.x, p.y + 1, p.z, 1.3F * bulle, ROUGE, ROUGE_SOMBRE, 0.8F * bulle);
                    sphere(buffers.getBuffer(Types.LUMIERE), m, p.x, p.y + 1, p.z, 1.6F * bulle, LUEUR, LUEUR, 0.3F * bulle);
                }
            }
            dessinerBombe(buffers, m, camera, h, joueur, partiel);
            if (joueur != null && h.forme == 4 && !joueur.isInvisible()) {
                coups(ps, buffers, camera, h, joueur, partiel);
            }
        }
        buffers.endBatch(Types.TRANSLUCIDE);
        buffers.endBatch(Types.LUMIERE);
        buffers.endBatch(ClientPoulpe.type());
    }

    /**
     * Niveau 4 : la tentacule qui fouette (vers sa cible, ou droit devant à 9 blocs) et celle qui balaie, dessinées dans
     * le monde (on les voit aussi en première personne), dans le repère de son corps.
     */
    private static void coups(PoseStack ps, MultiBufferSource buffers, Vec3 camera, Hachi h, Player joueur, float partiel) {
        double tf = h.fouet < 0 ? 9 : secondes(h.fouet) / ClientPoulpe.DUREE_FOUET;
        double tb = h.balayage < 0 ? 9 : secondes(h.balayage) / ClientPoulpe.DUREE_BALAYAGE;
        if (tf >= 1 && tb >= 1 && h.prise < 0) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        float lacet = Mth.rotLerp(partiel, joueur.yBodyRotO, joueur.yBodyRot);
        Vec3 pied = joueur.getPosition(partiel);
        float echelle = 0.9375F / 16;
        int lumiere = net.minecraft.client.renderer.LevelRenderer.getLightColor(mc.level, joueur.blockPosition().above());
        ps.pushPose();
        ps.translate(pied.x - camera.x, pied.y - camera.y, pied.z - camera.z);
        ps.mulPose(Axis.YP.rotationDegrees(-lacet));
        ps.scale(echelle, echelle, echelle);
        if (tf < 1) {
            // la cible, ramenée dans le repère de son corps (en pixels du modèle)
            Vec3 vers = new Vec3(0, 20, 9 / echelle);
            if (h.fouetCible >= 0 && mc.level.getEntity(h.fouetCible) instanceof net.minecraft.world.entity.Entity cible) {
                Vec3 d = cible.getPosition(partiel).add(0, cible.getBbHeight() * 0.55, 0).subtract(pied);
                double a = Math.toRadians(lacet);
                vers = new Vec3(d.x * Math.cos(a) + d.z * Math.sin(a), d.y, -d.x * Math.sin(a) + d.z * Math.cos(a)).scale(1 / echelle);
            }
            ClientPoulpe.fouet(ClientPoulpe.tampon(buffers), ps.last(), lumiere, vers, tf);
        }
        if (tb < 1) {
            ClientPoulpe.balayage(ClientPoulpe.tampon(buffers), ps.last(), lumiere, tb);
        }
        if (h.prise >= 0 && mc.level.getEntity(h.prise) instanceof net.minecraft.world.entity.Entity proie) {
            Vec3 d = proie.getPosition(partiel).add(0, proie.getBbHeight() * 0.5, 0).subtract(pied);
            double a = Math.toRadians(lacet);
            Vec3 vers = new Vec3(d.x * Math.cos(a) + d.z * Math.sin(a), d.y, -d.x * Math.sin(a) + d.z * Math.cos(a)).scale(1 / echelle);
            double serre = h.serre < 0 ? 0 : Math.max(0, 1 - secondes(h.serre) / 0.35);
            ClientPoulpe.saisie(ClientPoulpe.tampon(buffers), ps.last(), lumiere, vers, secondes(h.priseDebut) / 0.3, serre);
        }
        ps.popPose();
    }

    private static final float[] NOIR = {0.03F, 0.02F, 0.05F};
    private static final float[] NOIR_BORD = {0.3F, 0.05F, 0.28F};

    /** La bombe : qui se charge devant la gueule (des petites boules tournent et s'y jettent), qui file, puis qui explose. */
    private static void dessinerBombe(MultiBufferSource buffers, Matrix4f m, Vec3 camera, Hachi h, Player joueur, float partiel) {
        double tailleBombe = h.genre == 2 ? 1.65 : h.genre == 1 ? 1.4 : TAILLE_BOMBE;
        float[] coeur = h.genre == 2 ? NOIR : VIOLET, ombre = h.genre == 2 ? NOIR : VIOLET_SOMBRE, halo = h.genre == 2 ? NOIR_BORD : VIOLET;
        if (h.charge >= 0 && joueur != null) {
            double k = Math.min(1, secondes(h.charge) / (Hachibi.CHARGE_BOMBE / 20.0));
            Vec3 p = gueule(joueur, partiel).subtract(camera);
            float r = (float) (tailleBombe * Math.sqrt(k));
            sphere(buffers.getBuffer(Types.TRANSLUCIDE), m, p.x, p.y, p.z, r, coeur, ombre, 0.95F);
            sphere(buffers.getBuffer(Types.LUMIERE), m, p.x, p.y, p.z, r * 1.3F, halo, halo, 0.3F);
            double t = secondes(h.charge);
            for (int i = 0; i < 9; i++) {
                double phase = (t * 0.9 + i / 9.0) % 1;
                double a = i * 2.39996 + t * 3;
                double d = 2.6 * (1 - phase);
                float[] couleur = i % 3 == 0 ? new float[]{0.95F, 0.15F, 0.2F} : new float[]{0.2F, 0.55F, 1F};
                sphere(buffers.getBuffer(Types.TRANSLUCIDE), m, p.x + Math.cos(a) * d, p.y + Math.sin(a * 1.3) * d * 0.6, p.z + Math.sin(a) * d,
                        0.12F, couleur, couleur, 0.9F);
            }
        }
        if (h.tir >= 0 && h.depart != null) {
            double s = secondes(h.tir);
            if (s < 3.2) {
                Vec3 p = h.depart.add(h.direction.scale(s * VITESSE_BOMBE)).subtract(camera);
                sphere(buffers.getBuffer(Types.TRANSLUCIDE), m, p.x, p.y, p.z, (float) tailleBombe, coeur, ombre, 0.95F);
                sphere(buffers.getBuffer(Types.LUMIERE), m, p.x, p.y, p.z, (float) tailleBombe * 1.35F, halo, halo, 0.3F);
            }
        }
        if (h.explosion >= 0 && h.boum != null) {
            double s = secondes(h.explosion);
            if (s < 0.7) {
                float k = (float) (s / 0.7);
                Vec3 p = h.boum.subtract(camera);
                sphere(buffers.getBuffer(Types.LUMIERE), m, p.x, p.y, p.z, 1.5F + 6.5F * (float) Math.sqrt(k) * (float) (tailleBombe / TAILLE_BOMBE), halo, h.genre == 2 ? NOIR_BORD : new float[]{0.9F, 0.8F, 1F}, 0.7F * (1 - k));
                sphere(buffers.getBuffer(Types.TRANSLUCIDE), m, p.x, p.y, p.z, (float) tailleBombe * (1 + 3 * k), coeur, ombre, 0.85F * (1 - k));
            }
        }
    }

    private static final class Types extends RenderType {
        /** La bulle et la bombe : translucides. */
        static final RenderType TRANSLUCIDE = create("cabane_hachibi_translucide", DefaultVertexFormat.POSITION_COLOR, VertexFormat.Mode.QUADS, 65536,
                false, true, CompositeState.builder().setShaderState(POSITION_COLOR_SHADER).setWriteMaskState(COLOR_WRITE)
                        .setTransparencyState(TRANSLUCENT_TRANSPARENCY).setCullState(NO_CULL).createCompositeState(false));
        /** Les lueurs : de la lumière qui s'ajoute. */
        static final RenderType LUMIERE = create("cabane_hachibi_lumiere", DefaultVertexFormat.POSITION_COLOR, VertexFormat.Mode.QUADS, 65536,
                false, true, CompositeState.builder().setShaderState(RENDERTYPE_LIGHTNING_SHADER).setWriteMaskState(COLOR_WRITE)
                        .setTransparencyState(LIGHTNING_TRANSPARENCY).setCullState(NO_CULL).createCompositeState(false));

        private Types() {
            super("cabane_hachibi_rien", DefaultVertexFormat.POSITION, VertexFormat.Mode.QUADS, 256, false, false, () -> { }, () -> { });
        }
    }

    // ================================================================ l'écran : un voile rouge, et les touches à gauche

    private static void ecran(GuiGraphics g) {
        Minecraft mc = Minecraft.getInstance();
        Hachi h = mc.player == null ? null : HACHIS.get(mc.player.getUUID());
        if (!actif(h)) {
            return;
        }
        int w = g.guiWidth();
        int hh = g.guiHeight();
        // un voile rouge sur les bords (bien plus fort dans la bête, léger avec les tentacules)
        float voile = h.bete ? 0.38F : h.forme == 4 ? 0.1F : 0.14F;
        int n = 12;
        for (int i = 0; i < n; i++) {
            float k = 1 - i / (float) n;
            int c = (Math.round(voile * k * k * 255) << 24) | 0x9A0A04;
            int lx = w / 5 * i / n, lx1 = w / 5 * (i + 1) / n, ly = hh / 5 * i / n, ly1 = hh / 5 * (i + 1) / n;
            g.fill(lx, 0, lx1, hh, c);
            g.fill(w - lx1, 0, w - lx, hh, c);
            g.fill(0, ly, w, ly1, c);
            g.fill(0, hh - ly1, w, hh - ly, c);
        }
        if (secondes(h.debut) < 0.6) {
            return;
        }
        double rechargeBete = h.beteFin < 0 ? 0 : Hachibi.RECHARGE_BETE / 20.0 - secondes(h.beteFin);
        double rechargeCourse = h.courseFin < 0 ? 0 : Hachibi.RECHARGE_COURSE / 20.0 - secondes(h.courseFin);
        double rechargeBombe = h.dernierTir < 0 ? 0 : Hachibi.RECHARGE_BOMBE / 20.0 - secondes(h.dernierTir);
        String titre;
        String[][] lignes;
        boolean[] gris = new boolean[5];
        String eveil = ClientSusano.EVEIL.getTranslatedKeyMessage().getString();
        if (h.forme > 0) {
            // Hachibi 2 : l'Éveil passe au niveau suivant (manteau, bête, bête aux os, tentacules), puis le rendort
            String suite = h.forme < 4 ? "Niveau " + (h.forme + 1) : "Rendormir";
            double bombeRecharge = h.dernierTir < 0 ? 0
                    : (h.genre == 2 ? Hachibi.RECHARGE_BOMBE_NOIRE : Hachibi.RECHARGE_BOMBE) / 20.0 - secondes(h.dernierTir);
            String bombe = h.charge >= 0 ? "Bombe (charge…)" : bombeRecharge > 0 ? String.format("Bombe (%.0f s)", Math.ceil(bombeRecharge)) : "Bombe de chakra";
            if (h.forme == 1) {
                titre = "HACHIBI 2  ·  MANTEAU";
                String course = h.course >= 0 ? String.format("Course (%.0f s)", Math.ceil(Hachibi.DUREE_COURSE / 20.0 - secondes(h.course)))
                        : rechargeCourse > 0 ? String.format("Course (recharge %.0f s)", Math.ceil(rechargeCourse)) : "Course";
                gris[1] = h.course < 0 && rechargeCourse > 0;
                lignes = new String[][]{{Clavier.gauche(), "Griffe"}, {Clavier.accroupi(), course}, {eveil, suite}};
            } else if (h.forme <= 3) {
                titre = h.forme == 3 ? "HACHIBI 2  ·  BÊTE AUX OS" : "HACHIBI 2  ·  BÊTE";
                gris[1] = h.charge >= 0 || bombeRecharge > 0;
                lignes = new String[][]{{Clavier.gauche(), "Griffe de la bête"}, {Clavier.accroupi(), h.forme == 3 && h.charge < 0 && bombeRecharge <= 0 ? "Grosse bombe" : bombe},
                        {eveil, suite}};
            } else {
                titre = "HACHIBI 2  ·  HUIT QUEUES";
                double fouet = h.fouet < 0 ? 0 : Hachibi.RECHARGE_FOUET / 20.0 - secondes(h.fouet);
                double balayage = h.balayage < 0 ? 0 : Hachibi.RECHARGE_BALAYAGE / 20.0 - secondes(h.balayage);
                double prise = h.priseDebut < 0 ? 0 : Hachibi.RECHARGE_PRISE / 20.0 - secondes(h.priseDebut);
                if (h.prise >= 0) {
                    lignes = new String[][]{{Clavier.gauche(), "Lancer"}, {Clavier.droite(), "Serrer fort"}, {eveil, "Rendormir"}};
                } else {
                    gris[0] = fouet > 0;
                    gris[1] = balayage > 0;
                    gris[2] = prise > 0;
                    gris[3] = h.charge >= 0 || bombeRecharge > 0;
                    lignes = new String[][]{{Clavier.gauche(), "Coup de tentacule"},
                            {Clavier.droite(), balayage > 0 ? String.format("Balayage (%.0f s)", Math.ceil(balayage)) : "Balayage"},
                            {Clavier.accroupi(), prise > 0 ? String.format("Attraper (%.0f s)", Math.ceil(prise)) : "Attraper"},
                            {Clavier.accroupi() + " + " + Clavier.droite(), h.charge >= 0 ? "Bombe noire (charge…)" : bombeRecharge > 0 ? String.format("Bombe noire (%.0f s)", Math.ceil(bombeRecharge)) : "Bombe noire"},
                            {eveil, "Rendormir"}};
                }
            }
        } else if (h.bete) {
            double reste = (Hachibi.TRANSFORMATION + Hachibi.DUREE_BETE) / 20.0 - secondes(h.beteDebut);
            titre = String.format("HACHIBI  ·  BÊTE (%d s)", (int) Math.ceil(Math.min(Hachibi.DUREE_BETE / 20.0, Math.max(0, reste))));
            String bombe = h.charge >= 0 ? "Bombe (charge…)" : rechargeBombe > 0 ? String.format("Bombe (%.0f s)", Math.ceil(rechargeBombe)) : "Bombe de chakra";
            gris[2] = h.charge >= 0 || rechargeBombe > 0;
            lignes = new String[][]{{Clavier.gauche(), "Griffe de la bête"}, {Clavier.droite(), "Redevenir soi"}, {Clavier.accroupi(), bombe},
                    {ClientSusano.EVEIL.getTranslatedKeyMessage().getString(), "Rendormir"}};
        } else {
            titre = "HACHIBI  ·  MANTEAU";
            String bete = rechargeBete > 0 ? String.format("Forme bête (%.0f s)", Math.ceil(rechargeBete)) : "Forme bête";
            gris[1] = rechargeBete > 0;
            String course = h.course >= 0 ? String.format("Course (%.0f s)", Math.ceil(Hachibi.DUREE_COURSE / 20.0 - secondes(h.course)))
                    : rechargeCourse > 0 ? String.format("Course (recharge %.0f s)", Math.ceil(rechargeCourse)) : "Course";
            gris[2] = h.course < 0 && rechargeCourse > 0;
            lignes = new String[][]{{Clavier.gauche(), "Griffe"}, {Clavier.droite(), bete}, {Clavier.accroupi(), course},
                    {ClientSusano.EVEIL.getTranslatedKeyMessage().getString(), "Rendormir"}};
        }
        Font font = mc.font;
        int largeurTouche = 0;
        int largeurAction = font.width(titre) - 10;
        for (String[] l : lignes) {
            largeurTouche = Math.max(largeurTouche, font.width(l[0]));
            largeurAction = Math.max(largeurAction, font.width(l[1]));
        }
        int pw = largeurTouche + largeurAction + 26;
        int ph = 22 + lignes.length * 13;
        int x = 6;
        int y = hh / 2 - ph / 2;
        int teinte = 0xFFFF5A3C;
        g.fill(x, y, x + pw, y + ph, 0xA0000000);
        g.fill(x, y, x + 2, y + ph, teinte);
        g.drawString(font, titre, x + 8, y + 6, teinte, true);
        for (int i = 0; i < lignes.length; i++) {
            int ly = y + 21 + i * 13;
            g.fill(x + 7, ly - 2, x + 11 + font.width(lignes[i][0]), ly + 9, 0x40FFFFFF);
            g.drawString(font, lignes[i][0], x + 9, ly, 0xFFFFFFFF, false);
            g.drawString(font, lignes[i][1], x + 18 + largeurTouche, ly, gris[i] ? 0xFF7A7A7A : 0xFFEDEDED, true);
        }
    }

    // ================================================================ branchements

    @EventBusSubscriber(modid = Cabane.MODID, value = Dist.CLIENT)
    public static final class Couches {
        @SubscribeEvent
        public static void enregistrer(RegisterGuiLayersEvent event) {
            event.registerAboveAll(Cabane.id("hachibi"), (g, delta) -> ecran(g));
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
                HACHIS.clear();
                return;
            }
            Hachi moi = HACHIS.get(mc.player.getUUID());
            boolean accroupi = mc.options.keyShift.isDown();
            if (actif(moi) && moi.forme == 4) {
                // au niveau 4, s'accroupir sert aussi à la bombe noire (avec le clic droit) : la tentacule attrape quand on
                // relâche un appui court, si la bombe n'est pas partie entre-temps
                if (accroupi && !accroupiAvant) {
                    accroupiDebut = System.nanoTime();
                    bombeFaite = false;
                }
                if (!accroupi && accroupiAvant && !bombeFaite && mc.screen == null && System.nanoTime() - accroupiDebut < 700_000_000L) {
                    PacketDistributor.sendToServer(new Pouvoirs.Charge());
                }
            } else if (accroupi && !accroupiAvant && mc.screen == null && actif(moi) && secondes(moi.debut) > 0.6) {
                PacketDistributor.sendToServer(new Pouvoirs.Charge());
            }
            accroupiAvant = accroupi;
            HACHIS.entrySet().removeIf(e -> e.getValue().fin >= 0 && secondes(e.getValue().fin) > DISPARITION);
            for (Map.Entry<UUID, Hachi> entree : HACHIS.entrySet()) {
                Hachi h = entree.getValue();
                Player joueur = mc.level.getPlayerByUUID(entree.getKey());
                if (joueur == null || !actif(h)) {
                    continue;
                }
                double t = secondes(h.debut);
                // le manteau : un rugissement, une gerbe rouge, puis chaque queue qui sort
                if (h.tPrecedent < 0 && t >= 0) {
                    son(joueur, SoundEvents.RAVAGER_ROAR, 1F, 1.3F);
                    son(joueur, SoundEvents.WARDEN_HEARTBEAT, 1F, 0.8F);
                    son(joueur, SoundEvents.FIRECHARGE_USE, 0.7F, 0.6F);
                    gerbe(mc, joueur, 25, 1.1);
                }
                if (h.tPrecedent < QUEUE_1 && t >= QUEUE_1 || h.tPrecedent < QUEUE_2 && t >= QUEUE_2) {
                    son(joueur, SoundEvents.BLAZE_SHOOT, 0.8F, 0.6F);
                    gerbe(mc, joueur, 10, 0.6);
                }
                h.tPrecedent = t;
                if (h.bete) {
                    double tb = secondes(h.beteDebut);
                    if (h.tbPrecedent < SORTIE && tb >= SORTIE) {
                        son(joueur, SoundEvents.GENERIC_EXPLODE.value(), 0.6F, 1.4F);
                        gerbe(mc, joueur, 35, 1.6);
                    }
                    double q3 = 0.9 + (TRANSFORMATION - 0.9) * 0.25;
                    double q4 = 0.9 + (TRANSFORMATION - 0.9) * 0.75;
                    if (h.tbPrecedent < q3 && tb >= q3 || h.tbPrecedent < q4 && tb >= q4) {
                        son(joueur, SoundEvents.BLAZE_SHOOT, 1F, 0.45F);
                    }
                    h.tbPrecedent = tb;
                }
                // la course laisse une traînée rouge derrière lui
                if (h.course >= 0) {
                    for (int i = 0; i < 3; i++) {
                        mc.level.addParticle(rouge(1.2F), joueur.getX() + aleat(0.4), joueur.getY() + 0.2 + HASARD.nextDouble() * 0.8, joueur.getZ() + aleat(0.4), 0, 0, 0);
                    }
                }
                if (HASARD.nextInt(4) == 0) {
                    mc.level.addParticle(rouge(0.8F), joueur.getX() + aleat(0.5), joueur.getY() + HASARD.nextDouble() * 1.8, joueur.getZ() + aleat(0.5), 0, 0.03, 0);
                }
            }
        }

        private static void gerbe(Minecraft mc, Player joueur, int nombre, double rayon) {
            for (int i = 0; i < nombre; i++) {
                double a = HASARD.nextDouble() * Math.PI * 2;
                mc.level.addParticle(rouge(1.3F), joueur.getX() + Math.cos(a) * rayon * 0.5, joueur.getY() + 0.2 + HASARD.nextDouble() * 1.8,
                        joueur.getZ() + Math.sin(a) * rayon * 0.5, Math.cos(a) * 0.2, 0.03, Math.sin(a) * 0.2);
            }
        }

        /** Pouvoir éveillé, les clics sont à Hachibi : le gauche griffe, le droit fait (ou défait) la bête. */
        @SubscribeEvent
        public static void clic(InputEvent.InteractionKeyMappingTriggered event) {
            Minecraft mc = Minecraft.getInstance();
            Hachi h = mc.player == null ? null : HACHIS.get(mc.player.getUUID());
            if (!(event.isAttack() || event.isUseItem()) || !actif(h) || secondes(h.debut) < 0.6) {
                return;
            }
            event.setCanceled(true);
            event.setSwingHand(event.isAttack());
            long recharge = event.isAttack() ? 450_000_000L : 700_000_000L;
            if (System.nanoTime() - dernierClic > recharge) {
                dernierClic = System.nanoTime();
                if (event.isUseItem() && h.forme == 4 && h.prise < 0 && mc.options.keyShift.isDown()) {
                    // niveau 4 : accroupi + clic droit, la bombe noire
                    bombeFaite = true;
                    PacketDistributor.sendToServer(new Hachibi.ClicAccroupi());
                } else {
                    PacketDistributor.sendToServer(event.isAttack() ? new Pouvoirs.Frappe() : new Pouvoirs.Attraper());
                }
            }
        }

        /**
         * Un Hachibi éveillé, c'est nous qui le dessinons (le jeu ne dessine rien de lui) : on décide s'il est à quatre
         * pattes (d'autant plus qu'il va vite), puis on dessine la bête, ou le joueur sous son manteau.
         */
        @SubscribeEvent
        public static void avant(RenderPlayerEvent.Pre event) {
            Hachi h = HACHIS.get(event.getEntity().getUUID());
            if (!actif(h) || h.forme == 4 || !(event.getEntity() instanceof AbstractClientPlayer joueur)) {
                return;
            }
            float partiel = event.getPartialTick();
            float cible = h.course >= 0 ? 1 : Mth.clamp(joueur.walkAnimation.speed(partiel) * 2.6F, 0, 1);
            if (!h.bete && secondes(h.debut) < 0.9) {
                cible = 0;
            }
            h.quatre = Mth.lerp(0.12F, h.quatre, cible);
            event.setCanceled(true);
            if (h.bete && sortie(h) > 0.01F) {
                bete(event, h, joueur);
            } else {
                manteau(event, h, joueur);
            }
        }

        /** Hachibi 2, niveau 4 : ses tentacules, une fois que le jeu l'a dessiné (elles se rétractent quand il se rendort). */
        @SubscribeEvent
        public static void apres(RenderPlayerEvent.Post event) {
            Hachi h = HACHIS.get(event.getEntity().getUUID());
            if (h != null && h.forme == 4 && event.getEntity() instanceof AbstractClientPlayer joueur && !joueur.isInvisible()) {
                tentacules(event, h, joueur);
            }
        }

        /** Dans la bête, on ne voit pas ses mains. */
        @SubscribeEvent
        public static void mains(RenderHandEvent event) {
            Minecraft mc = Minecraft.getInstance();
            Hachi h = mc.player == null ? null : HACHIS.get(mc.player.getUUID());
            if (actif(h) && h.bete) {
                event.setCanceled(true);
            }
        }

        @SubscribeEvent
        public static void tremblement(ViewportEvent.ComputeCameraAngles event) {
            if (secousse < 0) {
                return;
            }
            double apres = (System.nanoTime() - secousse) / 1e9;
            if (apres > 0.8) {
                secousse = -1;
                return;
            }
            float force = (float) (1 - apres / 0.8);
            event.setRoll(event.getRoll() + (HASARD.nextFloat() - 0.5F) * 8 * force);
            event.setPitch(event.getPitch() + (HASARD.nextFloat() - 0.5F) * 4 * force);
        }

        @SubscribeEvent
        public static void dessiner(RenderLevelStageEvent event) {
            if (HACHIS.isEmpty() || event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES || Minecraft.getInstance().level == null) {
                return;
            }
            ClientHachibi.dessiner(event.getPoseStack(), event.getCamera().getPosition(), event.getPartialTick().getGameTimeDeltaPartialTick(false));
        }

        private Evenements() {
        }
    }

    private ClientHachibi() {
    }
}
