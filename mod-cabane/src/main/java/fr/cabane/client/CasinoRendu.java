package fr.cabane.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import fr.cabane.Cabane;
import fr.cabane.bloc.InteractifBlockEntity;
import fr.cabane.casino.Blackjack;
import fr.cabane.casino.Casino;
import fr.cabane.economie.Economie;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.phys.Vec3;

/**
 * Les grandes tables du casino, dessinées en 3D : la roulette (son cylindre tourne, la bille court puis
 * tombe dans la case), la roue de la fortune (lumières, clous, languette qui claque, feu d'artifice au
 * jackpot), et le blackjack (le croupier, les cartes de chaque joueur posées sur le tapis).
 * Repère : x vers la droite du joueur, z vers lui, y vers le haut ; l'origine au centre du bloc posé.
 */
final class CasinoRendu {
    private static final int[] ORDRE = {0, 32, 15, 19, 4, 21, 2, 25, 17, 34, 6, 27, 13, 36, 11, 30, 8, 23, 10, 5,
            24, 16, 33, 1, 20, 14, 31, 9, 22, 18, 29, 7, 28, 12, 35, 3, 26};
    private static final String[] RANGS = {"A", "2", "3", "4", "5", "6", "7", "8", "9", "10", "V", "D", "R"};
    private static final String[] COULEURS = {"♠", "♥", "♦", "♣"};
    private static final ResourceLocation PEAU_CROUPIER = Cabane.id("textures/entity/pnj/croupier.png");

    private static final int BOIS = 0xFF4A2C18;
    private static final int BOIS_CLAIR = 0xFF6B4226;
    private static final int OR = 0xFFD4AF37;
    private static final int TAPIS = 0xFF1E6B3A;

    /** Pour ne fêter qu'une fois chaque tirage, et faire claquer la languette à chaque clou. */
    private static final Map<BlockPos, Long> FETE = new HashMap<>();
    private static final Map<BlockPos, Integer> CLOU = new HashMap<>();

    // ================================================================ le pinceau : des faces de couleur unie

    /** Dessine des quads de couleur (la texture du béton blanc, teintée), éclairés comme une entité. */
    private static final class Pinceau {
        final VertexConsumer vc;
        final PoseStack ps;
        final float u;
        final float v;
        final int lumiere;

        Pinceau(MultiBufferSource buf, PoseStack ps, int lumiere) {
            this.vc = buf.getBuffer(RenderType.entityCutoutNoCull(InventoryMenu.BLOCK_ATLAS));
            this.ps = ps;
            this.lumiere = lumiere;
            TextureAtlasSprite s = Minecraft.getInstance().getTextureAtlas(InventoryMenu.BLOCK_ATLAS)
                    .apply(ResourceLocation.withDefaultNamespace("block/white_concrete"));
            this.u = (s.getU0() + s.getU1()) / 2;
            this.v = (s.getV0() + s.getV1()) / 2;
        }

        void quad(int c, float nx, float ny, float nz, boolean clair, float... p) {
            int l = clair ? LightTexture.FULL_BRIGHT : lumiere;
            PoseStack.Pose pose = ps.last();
            for (int i = 0; i < 4; i++) {
                vc.addVertex(pose, p[i * 3], p[i * 3 + 1], p[i * 3 + 2])
                        .setColor((c >> 16) & 0xFF, (c >> 8) & 0xFF, c & 0xFF, (c >>> 24) & 0xFF)
                        .setUv(u, v).setOverlay(OverlayTexture.NO_OVERLAY).setLight(l).setNormal(pose, nx, ny, nz);
            }
        }

        void pave(int c, float x0, float y0, float z0, float x1, float y1, float z1) {
            pave(c, x0, y0, z0, x1, y1, z1, false);
        }

        void pave(int c, float x0, float y0, float z0, float x1, float y1, float z1, boolean clair) {
            quad(c, 0, 1, 0, clair, x0, y1, z0, x0, y1, z1, x1, y1, z1, x1, y1, z0);
            quad(c, 0, -1, 0, clair, x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1);
            quad(c, 0, 0, -1, clair, x0, y0, z0, x0, y1, z0, x1, y1, z0, x1, y0, z0);
            quad(c, 0, 0, 1, clair, x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1);
            quad(c, -1, 0, 0, clair, x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0);
            quad(c, 1, 0, 0, clair, x1, y0, z0, x1, y1, z0, x1, y1, z1, x1, y0, z1);
        }

        /** Un morceau d'anneau à plat (hauteur y), angles en degrés autour de (cx, cz). */
        void secteurH(int c, float cx, float y, float cz, float r0, float r1, float a0, float a1, boolean clair) {
            float c0 = Mth.cos(a0 * Mth.DEG_TO_RAD), s0 = Mth.sin(a0 * Mth.DEG_TO_RAD);
            float c1 = Mth.cos(a1 * Mth.DEG_TO_RAD), s1 = Mth.sin(a1 * Mth.DEG_TO_RAD);
            quad(c, 0, 1, 0, clair, cx + r0 * c0, y, cz + r0 * s0, cx + r1 * c0, y, cz + r1 * s0,
                    cx + r1 * c1, y, cz + r1 * s1, cx + r0 * c1, y, cz + r0 * s1);
        }

        /** La paroi verticale d'un cylindre de rayon r, entre y0 et y1. */
        void paroiH(int c, float cx, float cz, float r, float y0, float y1, float a0, float a1) {
            float c0 = Mth.cos(a0 * Mth.DEG_TO_RAD), s0 = Mth.sin(a0 * Mth.DEG_TO_RAD);
            float c1 = Mth.cos(a1 * Mth.DEG_TO_RAD), s1 = Mth.sin(a1 * Mth.DEG_TO_RAD);
            float cm = (c0 + c1) / 2, sm = (s0 + s1) / 2;
            quad(c, cm, 0, sm, false, cx + r * c0, y0, cz + r * s0, cx + r * c1, y0, cz + r * s1,
                    cx + r * c1, y1, cz + r * s1, cx + r * c0, y1, cz + r * s0);
        }

        /** Un morceau d'anneau debout (plan z), angles en degrés depuis le haut, dans le sens des aiguilles d'une montre. */
        void secteurV(int c, float cx, float cy, float z, float r0, float r1, float a0, float a1, boolean clair) {
            float s0 = Mth.sin(a0 * Mth.DEG_TO_RAD), c0 = Mth.cos(a0 * Mth.DEG_TO_RAD);
            float s1 = Mth.sin(a1 * Mth.DEG_TO_RAD), c1 = Mth.cos(a1 * Mth.DEG_TO_RAD);
            quad(c, 0, 0, 1, clair, cx + r0 * s0, cy + r0 * c0, z, cx + r1 * s0, cy + r1 * c0, z,
                    cx + r1 * s1, cy + r1 * c1, z, cx + r0 * s1, cy + r0 * c1, z);
        }

        /** La tranche d'une roue debout, de rayon r, entre z0 et z1. */
        void paroiV(int c, float cx, float cy, float r, float z0, float z1, float a0, float a1) {
            float s0 = Mth.sin(a0 * Mth.DEG_TO_RAD), c0 = Mth.cos(a0 * Mth.DEG_TO_RAD);
            float s1 = Mth.sin(a1 * Mth.DEG_TO_RAD), c1 = Mth.cos(a1 * Mth.DEG_TO_RAD);
            quad(c, (s0 + s1) / 2, (c0 + c1) / 2, 0, false, cx + r * s0, cy + r * c0, z0, cx + r * s1, cy + r * c1, z0,
                    cx + r * s1, cy + r * c1, z1, cx + r * s0, cy + r * c0, z1);
        }
    }

    // ================================================================ petits outils

    /** Un texte posé à plat (lisible depuis le côté du joueur si angle = 0), centré en (x, y, z). */
    private static void texteAPlat(PoseStack ps, MultiBufferSource buf, Component t, float x, float y, float z, float angle,
                                   float echelle, int couleur, int lumiere) {
        ps.pushPose();
        ps.translate(x, y, z);
        ps.mulPose(Axis.YP.rotationDegrees(angle));
        ps.mulPose(Axis.XP.rotationDegrees(-90));
        Rendu3D.texte(ps, buf, t, 0, 4 * echelle, echelle, couleur, 0, lumiere);
        ps.popPose();
    }

    /** Un texte debout face au joueur, tourné de « angle » degrés dans son plan, centré en (x, y, z). */
    private static void texteDebout(PoseStack ps, MultiBufferSource buf, Component t, float x, float y, float z, float angle,
                                    float echelle, int couleur, int lumiere) {
        ps.pushPose();
        ps.translate(x, y, z);
        ps.mulPose(Axis.ZP.rotationDegrees(angle));
        Rendu3D.texte(ps, buf, t, 0, 4 * echelle, echelle, couleur, 0, lumiere);
        ps.popPose();
    }

    private static Component gras(String s) {
        return Component.literal(s).withStyle(st -> st.withBold(true));
    }

    private static float ralentir(float u, int puissance) {
        return 1F - (float) Math.pow(1F - Mth.clamp(u, 0F, 1F), puissance);
    }

    private static void pieds(Pinceau p, float x0, float z0, float x1, float z1) {
        for (float[] c : new float[][] {{x0, z0}, {x1 - 0.12F, z0}, {x0, z1 - 0.12F}, {x1 - 0.12F, z1 - 0.12F}}) {
            p.pave(BOIS, c[0], 0, c[1], c[0] + 0.12F, 0.8F, c[1] + 0.12F);
            p.pave(OR, c[0] - 0.01F, 0, c[1] - 0.01F, c[0] + 0.13F, 0.06F, c[1] + 0.13F);
        }
    }

    /** Le plateau d'une table : bois, tapis vert, liseré doré. */
    private static void plateau(Pinceau p, float x0, float z0, float x1, float z1) {
        p.pave(BOIS_CLAIR, x0, 0.8F, z0, x1, 0.92F, z1);
        p.pave(TAPIS, x0 + 0.08F, 0.92F, z0 + 0.08F, x1 - 0.08F, 0.93F, z1 - 0.08F);
        p.pave(OR, x0, 0.92F, z0, x1, 0.95F, z0 + 0.04F);
        p.pave(OR, x0, 0.92F, z1 - 0.04F, x1, 0.95F, z1);
        p.pave(OR, x0, 0.92F, z0 + 0.04F, x0 + 0.04F, 0.95F, z1 - 0.04F);
        p.pave(OR, x1 - 0.04F, 0.92F, z0 + 0.04F, x1, 0.95F, z1 - 0.04F);
    }

    private static void jetons(Pinceau p, float x, float y, float z, int nombre, int couleur) {
        for (int i = 0; i < nombre; i++) {
            float h = y + i * 0.014F;
            p.pave(couleur, x - 0.035F, h, z - 0.035F, x + 0.035F, h + 0.012F, z + 0.035F);
            p.pave(0xFFF4F4F4, x - 0.036F, h + 0.004F, z - 0.012F, x + 0.036F, h + 0.008F, z + 0.012F);
        }
    }

    private static Vec3 monde(BlockPos pos, Direction face, float x, float y, float z) {
        return new Vec3(x, y, z).yRot(-face.toYRot() * Mth.DEG_TO_RAD).add(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
    }

    private static void son(Level level, Vec3 ou, SoundEvent son, float volume, float hauteur) {
        level.playLocalSound(ou.x, ou.y, ou.z, son, SoundSource.BLOCKS, volume, hauteur, false);
    }

    private static void gerbe(Level level, Vec3 ou, ParticleOptions particule, int nombre, double vitesse) {
        for (int i = 0; i < nombre; i++) {
            double a = level.random.nextDouble() * Math.PI * 2;
            double b = level.random.nextDouble() * Math.PI - Math.PI / 2;
            level.addParticle(particule, ou.x, ou.y, ou.z, Math.cos(a) * Math.cos(b) * vitesse, Math.abs(Math.sin(b)) * vitesse + 0.05, Math.sin(a) * Math.cos(b) * vitesse);
        }
    }

    /** Vrai une seule fois par tirage, au moment où l'animation se termine (et pas si on arrive longtemps après). */
    private static boolean feter(InteractifBlockEntity be, float ecoule, int duree) {
        if (ecoule < duree || ecoule > duree + 40) {
            return false;
        }
        Long deja = FETE.get(be.getBlockPos());
        if (deja != null && deja == be.debut()) {
            return false;
        }
        FETE.put(be.getBlockPos(), be.debut());
        return true;
    }

    // ================================================================ la roulette (2 x 2)

    static void roulette(InteractifBlockEntity be, float partialTick, PoseStack ps, MultiBufferSource buf, int lumiere) {
        Level level = be.getLevel();
        float temps = level.getGameTime() + partialTick;
        float ecoule = temps - be.debut();
        float u = ecoule / Casino.DUREE_ROULETTE;
        Pinceau p = new Pinceau(buf, ps, lumiere);
        pieds(p, -0.45F, -1.45F, 1.45F, 0.45F);
        plateau(p, -0.48F, -1.48F, 1.48F, 0.48F);

        // le cylindre, sur la moitié gauche
        float cx = 0F, cz = -0.5F, y = 0.93F;
        for (int i = 0; i < 48; i++) {
            float a0 = i * 7.5F, a1 = a0 + 7.5F;
            p.secteurH(BOIS, cx, y + 0.08F, cz, 0.40F, 0.47F, a0, a1, false);
            p.paroiH(BOIS, cx, cz, 0.47F, y, y + 0.08F, a0, a1);
            p.secteurH(0xFFE9DFC6, cx, y + 0.05F, cz, 0.33F, 0.40F, a0, a1, false);
            p.paroiH(0xFFC9BC9C, cx, cz, 0.40F, y + 0.05F, y + 0.08F, a0, a1);
        }
        // il tourne doucement, et beaucoup plus vite pendant un tirage
        float rotation = temps * 0.8F + 1080F * ralentir(u, 3);
        float pas = 360F / ORDRE.length;
        for (int i = 0; i < ORDRE.length; i++) {
            int n = ORDRE[i];
            int c = n == 0 ? 0xFF1E8449 : Casino.rouge(n) ? 0xFFB3261E : 0xFF151515;
            float a = rotation + i * pas;
            p.secteurH(OR, cx, y + 0.028F, cz, 0.21F, 0.33F, a - pas / 2, a + pas / 2, false);
            p.secteurH(c, cx, y + 0.03F, cz, 0.215F, 0.33F, a - pas / 2 + 0.7F, a + pas / 2 - 0.7F, false);
            float r = 0.29F;
            texteAPlat(ps, buf, Component.literal(Integer.toString(n)), cx + r * Mth.cos(a * Mth.DEG_TO_RAD), y + 0.032F,
                    cz + r * Mth.sin(a * Mth.DEG_TO_RAD), -(a + 90F), 0.0042F, 0xFFFFFFFF, lumiere);
        }
        for (int i = 0; i < 24; i++) {
            p.secteurH(BOIS_CLAIR, cx, y + 0.05F, cz, 0F, 0.215F, i * 15F, i * 15F + 15F, false);
        }
        // la tourelle dorée au centre, avec sa croix qui tourne
        p.pave(OR, cx - 0.03F, y + 0.05F, cz - 0.03F, cx + 0.03F, y + 0.14F, cz + 0.03F);
        ps.pushPose();
        ps.translate(cx, 0, cz);
        ps.mulPose(Axis.YP.rotationDegrees(-rotation));
        p.pave(OR, -0.12F, y + 0.1F, -0.01F, 0.12F, y + 0.115F, 0.01F);
        p.pave(OR, -0.01F, y + 0.1F, -0.12F, 0.01F, y + 0.115F, 0.12F);
        ps.popPose();

        // la bille : elle court à l'envers sur la piste, ralentit, rebondit, tombe dans sa case
        int indice = -1;
        for (int i = 0; i < ORDRE.length; i++) {
            if (ORDRE[i] == be.tirage()) {
                indice = i;
            }
        }
        if (indice >= 0) {
            float dansLaCase = rotation + indice * pas;
            float angle = dansLaCase - 1440F * (1F - ralentir(u, 5));
            float r = 0.365F;
            float h = y + 0.055F;
            if (u >= 0.72F) {
                float k = Mth.clamp((u - 0.72F) / 0.14F, 0F, 1F);
                r = Mth.lerp(k, 0.365F, 0.27F) + Mth.sin(k * Mth.PI * 3) * 0.025F * (1 - k);
                h = Mth.lerp(k, y + 0.055F, y + 0.036F) + Math.abs(Mth.sin(k * Mth.PI * 3)) * 0.02F * (1 - k);
            }
            float bx = cx + r * Mth.cos(angle * Mth.DEG_TO_RAD), bz = cz + r * Mth.sin(angle * Mth.DEG_TO_RAD);
            p.pave(0xFFFAFAFA, bx - 0.017F, h, bz - 0.017F, bx + 0.017F, h + 0.034F, bz + 0.017F);
        }

        // le tapis de mise, sur la moitié droite : le zéro au fond, puis douze rangées de trois
        float x0 = 0.6F, z0 = -1.36F, lc = 0.25F, lr = 0.132F;
        p.pave(0xFF1E8449, x0 + 0.006F, 0.931F, z0 + 0.006F, x0 + 3 * lc - 0.006F, 0.934F, z0 + lr - 0.006F);
        texteAPlat(ps, buf, gras("0"), x0 + 1.5F * lc, 0.9345F, z0 + lr / 2, 0, 0.0055F, 0xFFFFFFFF, lumiere);
        for (int rang = 0; rang < 12; rang++) {
            for (int col = 0; col < 3; col++) {
                int n = rang * 3 + col + 1;
                float x = x0 + col * lc, z = z0 + (rang + 1) * lr;
                p.pave(Casino.rouge(n) ? 0xFFB3261E : 0xFF151515, x + 0.006F, 0.931F, z + 0.006F, x + lc - 0.006F, 0.934F, z + lr - 0.006F);
                texteAPlat(ps, buf, gras(Integer.toString(n)), x + lc / 2, 0.9345F, z + lr / 2, 0, 0.0055F, 0xFFFFFFFF, lumiere);
            }
        }
        jetons(p, 1.38F, 0.93F, 0.3F, 5, 0xFFC0392B);
        jetons(p, 1.3F, 0.93F, 0.36F, 3, 0xFF2471A3);
        jetons(p, 1.22F, 0.93F, 0.3F, 7, 0xFF1C1C1C);

        // au-dessus : le nom du jeu et le dernier numéro
        boolean tourne = u < 1F;
        Rendu3D.etiquette(ps, buf, gras("ROULETTE"), 0.5, 2.05, -0.5, 0.026F, 0xFFFFD75E, 0x70000000, lumiere);
        if (tourne && be.tirage() >= 0) {
            Rendu3D.etiquette(ps, buf, Component.literal("Rien ne va plus !"), 0.5, 1.8, -0.5, 0.018F, 0xFFFFFFFF, 0x70000000, lumiere);
        } else if (be.tirage() >= 0) {
            int n = be.tirage();
            String nom = n == 0 ? "VERT" : Casino.rouge(n) ? "ROUGE" : "NOIR";
            int couleur = n == 0 ? 0xFF5BE38C : Casino.rouge(n) ? 0xFFFF5A4E : 0xFFE0E0E0;
            Rendu3D.etiquette(ps, buf, gras(n + " " + nom), 0.5, 1.8, -0.5, 0.022F, couleur, 0x70000000, lumiere);
            if (ecoule < Casino.DUREE_ROULETTE + 200 && be.gain() > 0) {
                Rendu3D.etiquette(ps, buf, Component.literal(be.joueur() + " gagne " + Economie.format(be.gain()) + " !"), 0.5, 1.58, -0.5, 0.015F,
                        0xFF7CFF9B, 0x70000000, lumiere);
            }
        }
        // les sons : la bille qui claque en tombant, la cloche à la fin
        Direction face = be.getBlockState().getValue(HorizontalDirectionalBlock.FACING);
        Vec3 centre = monde(be.getBlockPos(), face, cx, y, cz);
        if (u >= 0.72F && u < 0.86F) {
            int rebond = (int) ((u - 0.72F) / 0.14F * 3);
            Integer avant = CLOU.get(be.getBlockPos());
            if (avant == null || avant != rebond + 1000 * (int) (be.debut() % 1000)) {
                CLOU.put(be.getBlockPos(), rebond + 1000 * (int) (be.debut() % 1000));
                son(level, centre, SoundEvents.NOTE_BLOCK_HAT.value(), 0.7F, 1.8F + rebond * 0.1F);
            }
        }
        if (feter(be, ecoule, Casino.DUREE_ROULETTE)) {
            son(level, centre, SoundEvents.NOTE_BLOCK_BELL.value(), 1F, 1.2F);
            if (be.gain() > 0) {
                son(level, centre, SoundEvents.PLAYER_LEVELUP, 0.8F, 1.1F);
                gerbe(level, centre.add(0, 0.3, 0), ParticleTypes.HAPPY_VILLAGER, 20, 0.15);
            }
        }
    }

    // ================================================================ la roue de la fortune (5 x 5, debout)

    private static int couleurRoue(int dixiemes, int i) {
        return switch (dixiemes) {
            case 0 -> i % 4 == 0 ? 0xFF2B2B33 : 0xFF1C1C22;
            case 15 -> 0xFF1F6FD1;
            case 20 -> 0xFF1FA34A;
            case 30 -> 0xFFE08A12;
            default -> 0xFFD4202A;
        };
    }

    private static String texteRoue(int dixiemes) {
        return switch (dixiemes) {
            case 0 -> "PERDU";
            case 15 -> "×1,5";
            case 20 -> "×2";
            case 30 -> "×3";
            default -> "×4 !";
        };
    }

    /** Angle de repos de la roue quand la case k est sous la languette. */
    private static float reposRoue(int k) {
        return k < 0 ? 0F : -k * 360F / Casino.ROUE_CASES.length;
    }

    static void roue(InteractifBlockEntity be, float partialTick, PoseStack ps, MultiBufferSource buf, int lumiere) {
        Level level = be.getLevel();
        float temps = level.getGameTime() + partialTick;
        float ecoule = temps - be.debut();
        float u = ecoule / Casino.DUREE_ROUE;
        boolean tourne = u < 1F && be.tirage() >= 0;
        Pinceau p = new Pinceau(buf, ps, lumiere);
        float cx = 0F, cy = 2.75F, z = 0.05F, rayon = 2.2F;

        // le pied : socle bordeaux et or, mât derrière la roue
        p.pave(0xFF4A0F18, -1.0F, 0F, -0.45F, 1.0F, 0.2F, 0.45F);
        p.pave(OR, -1.02F, 0.18F, -0.47F, 1.02F, 0.24F, 0.47F);
        p.pave(0xFF4A0F18, -0.6F, 0.24F, -0.35F, 0.6F, 0.5F, 0.3F);
        p.pave(0xFF3A1518, -0.2F, 0.5F, -0.3F, 0.2F, cy, -0.1F);

        // l'angle : depuis la case précédente, cinq tours et plus, freinage progressif
        float depart = reposRoue(be.precedent());
        float arrivee = reposRoue(be.tirage());
        float fin = depart + 360F * 5 + (((arrivee - depart) % 360F) + 360F) % 360F;
        float angle = u >= 1F ? arrivee : Mth.lerp(ralentir(u, 4), depart, fin);

        // le dos et la jante dorée (fixes)
        for (int i = 0; i < 40; i++) {
            p.secteurV(0xFF141018, cx, cy, z - 0.07F, 0F, rayon + 0.18F, i * 9F, i * 9F + 9F, false);
        }
        for (int i = 0; i < 60; i++) {
            p.secteurV(OR, cx, cy, z + 0.012F, rayon, rayon + 0.18F, i * 6F, i * 6F + 6F, false);
            p.paroiV(0xFFB08A2A, cx, cy, rayon + 0.18F, z - 0.07F, z + 0.012F, i * 6F, i * 6F + 6F);
        }
        // les vingt cases, leurs clous et leurs gains
        int n = Casino.ROUE_CASES.length;
        float pas = 360F / n;
        for (int i = 0; i < n; i++) {
            int m = Casino.ROUE_CASES[i];
            float a = angle + i * pas;
            p.secteurV(couleurRoue(m, i), cx, cy, z, 0.42F, rayon, a - pas / 2, a + pas / 2, false);
            p.secteurV(OR, cx, cy, z + 0.004F, 0.42F, rayon, a + pas / 2 - 0.6F, a + pas / 2 + 0.6F, false);
            float ac = (a + pas / 2) * Mth.DEG_TO_RAD;
            float px = cx + (rayon - 0.09F) * Mth.sin(ac), py = cy + (rayon - 0.09F) * Mth.cos(ac);
            p.pave(0xFFF2E6B0, px - 0.03F, py - 0.03F, z, px + 0.03F, py + 0.03F, z + 0.13F);
            float ar = a * Mth.DEG_TO_RAD;
            texteDebout(ps, buf, gras(texteRoue(m)), cx + 1.35F * Mth.sin(ar), cy + 1.35F * Mth.cos(ar), z + 0.008F, 90F - a,
                    0.024F, m >= 40 ? 0xFFFFF2A0 : 0xFFFFFFFF, lumiere);
        }
        // le moyeu
        for (int i = 0; i < 24; i++) {
            p.secteurV(OR, cx, cy, z + 0.02F, 0F, 0.42F, i * 15F, i * 15F + 15F, false);
            p.secteurV(0xFF8A1020, cx, cy, z + 0.024F, 0F, 0.3F, i * 15F, i * 15F + 15F, false);
        }
        texteDebout(ps, buf, gras("BEAUVAIS"), cx, cy, z + 0.03F, 0F, 0.0085F, 0xFFFFE08A, LightTexture.FULL_BRIGHT);

        // les ampoules de la jante : un chenillard, qui s'emballe quand la roue tourne
        for (int i = 0; i < 40; i++) {
            float a = i * 9F * Mth.DEG_TO_RAD;
            float bx = cx + (rayon + 0.09F) * Mth.sin(a), by = cy + (rayon + 0.09F) * Mth.cos(a);
            boolean allume = tourne ? (i + (int) (temps / 1.5F)) % 4 == 0 : (i + (int) (temps / 8F)) % 2 == 0;
            if (!tourne && be.tirage() >= 0 && ecoule < Casino.DUREE_ROUE + 60 && Casino.ROUE_CASES[be.tirage()] >= 30) {
                allume = ((int) (temps / 3F)) % 2 == 0;
            }
            int c = allume ? (i % 2 == 0 ? 0xFFFFF6C8 : 0xFFFFC83A) : 0xFF6A5420;
            p.pave(c, bx - 0.055F, by - 0.055F, z + 0.012F, bx + 0.055F, by + 0.055F, z + 0.1F, allume);
        }

        // la languette rouge en haut : un clou qui passe la fait basculer
        float phase = (((angle + pas / 2) % pas) + pas) % pas;
        float bascule = phase < pas * 0.35F ? (1F - phase / (pas * 0.35F)) * 24F : 0F;
        ps.pushPose();
        ps.translate(cx, cy + rayon + 0.2F, z + 0.16F);
        p.pave(OR, -0.08F, -0.02F, -0.2F, 0.08F, 0.14F, -0.02F);
        ps.mulPose(Axis.ZP.rotationDegrees(-bascule));
        p.quad(0xFFE0262E, 0, 0, 1, true, -0.17F, 0.12F, 0F, 0.17F, 0.12F, 0F, 0F, -0.34F, 0F, 0F, -0.34F, 0F);
        p.quad(0xFF8A1018, 0, 0, -1, false, -0.17F, 0.12F, -0.02F, 0F, -0.34F, -0.02F, 0F, -0.34F, -0.02F, 0.17F, 0.12F, -0.02F);
        ps.popPose();

        // l'enseigne et le résultat
        Rendu3D.etiquette(ps, buf, gras("★ ROUE DE LA FORTUNE ★"), 0, 6.0, 0, 0.034F, 0xFFFFD75E, 0x80000000, LightTexture.FULL_BRIGHT);
        if (tourne) {
            Rendu3D.etiquette(ps, buf, Component.literal(be.joueur() + " fait tourner la roue…"), 0, 5.62, 0, 0.02F, 0xFFFFFFFF, 0x70000000, lumiere);
        } else if (be.tirage() >= 0 && ecoule < Casino.DUREE_ROUE + 400) {
            int m = Casino.ROUE_CASES[be.tirage()];
            String texte = m == 0 ? be.joueur() + " : perdu !" : m >= 40 ? "JACKPOT ! " + be.joueur() + " gagne " + Economie.format(be.gain())
                    : be.joueur() + " gagne " + Economie.format(be.gain());
            Rendu3D.etiquette(ps, buf, gras(texte), 0, 5.62, 0, 0.022F, m == 0 ? 0xFFFF6B6B : m >= 30 ? 0xFFFFD75E : 0xFF7CFF9B, 0x80000000, lumiere);
        }

        // le clac de chaque clou, et la fête à la fin
        Direction face = be.getBlockState().getValue(HorizontalDirectionalBlock.FACING);
        Vec3 haut = monde(be.getBlockPos(), face, cx, cy + rayon, z + 0.2F);
        if (tourne) {
            int clou = Mth.floor((angle + pas / 2) / pas);
            Integer avant = CLOU.get(be.getBlockPos());
            if (avant != null && avant != clou) {
                son(level, haut, SoundEvents.NOTE_BLOCK_HAT.value(), 0.8F, 1.5F + level.random.nextFloat() * 0.2F);
            }
            CLOU.put(be.getBlockPos(), clou);
        }
        if (be.tirage() >= 0 && feter(be, ecoule, Casino.DUREE_ROUE)) {
            int m = Casino.ROUE_CASES[be.tirage()];
            Vec3 centre = monde(be.getBlockPos(), face, cx, cy, z + 0.4F);
            if (m >= 30) {
                // ça pète : feu d'artifice, totem, et tout le casino l'entend
                gerbe(level, centre, ParticleTypes.FIREWORK, 90, 0.35);
                gerbe(level, centre, ParticleTypes.TOTEM_OF_UNDYING, 60, 0.5);
                gerbe(level, haut.add(0, 1, 0), ParticleTypes.END_ROD, 30, 0.25);
                son(level, centre, SoundEvents.FIREWORK_ROCKET_LARGE_BLAST, 2F, 1F);
                son(level, centre, SoundEvents.FIREWORK_ROCKET_TWINKLE, 2F, 1F);
                son(level, centre, SoundEvents.PLAYER_LEVELUP, 1.5F, 0.8F);
                son(level, centre, SoundEvents.TOTEM_USE, 0.6F, 1.4F);
            } else if (m > 0) {
                gerbe(level, centre, ParticleTypes.HAPPY_VILLAGER, 30, 0.2);
                gerbe(level, centre, ParticleTypes.NOTE, 8, 0.3);
                son(level, centre, SoundEvents.PLAYER_LEVELUP, 1F, 1.2F);
            } else {
                gerbe(level, centre, ParticleTypes.SMOKE, 25, 0.08);
                son(level, centre, SoundEvents.NOTE_BLOCK_BASS.value(), 1F, 0.6F);
            }
        }
    }

    // ================================================================ le blackjack (3 x 2) et son croupier

    private static void carte(Pinceau p, PoseStack ps, MultiBufferSource buf, int c, boolean dos, float x, float y, float z, int lumiere) {
        float w = 0.17F, h = 0.24F;
        if (dos) {
            p.pave(0xFFD4AF37, x - w / 2, y, z - h / 2, x + w / 2, y + 0.002F, z + h / 2);
            p.pave(0xFF1F3F8C, x - w / 2 + 0.01F, y + 0.002F, z - h / 2 + 0.01F, x + w / 2 - 0.01F, y + 0.003F, z + h / 2 - 0.01F);
            p.pave(0xFF2E5BB8, x - 0.04F, y + 0.003F, z - 0.07F, x + 0.04F, y + 0.004F, z + 0.07F);
            return;
        }
        p.pave(0xFFB9B9B0, x - w / 2, y, z - h / 2, x + w / 2, y + 0.002F, z + h / 2);
        p.pave(0xFFFAFAF5, x - w / 2 + 0.004F, y + 0.002F, z - h / 2 + 0.004F, x + w / 2 - 0.004F, y + 0.003F, z + h / 2 - 0.004F);
        int couleur = (c / 13 == 1 || c / 13 == 2) ? 0xFFC0392B : 0xFF1C1C1C;
        texteAPlat(ps, buf, gras(RANGS[c % 13]), x - w / 2 + 0.035F, y + 0.0035F, z - h / 2 + 0.04F, 0, 0.0075F, couleur, lumiere);
        texteAPlat(ps, buf, Component.literal(COULEURS[c / 13]), x, y + 0.0035F, z + 0.02F, 0, 0.015F, couleur, lumiere);
    }

    private static void main(Pinceau p, PoseStack ps, MultiBufferSource buf, List<Integer> cartes, boolean dosCache,
                             float cx, float z, float y, int lumiere) {
        int total = cartes.size() + (dosCache ? 1 : 0);
        float ecart = 0.11F;
        float x0 = cx - (total - 1) * ecart / 2;
        for (int i = 0; i < cartes.size(); i++) {
            carte(p, ps, buf, cartes.get(i), false, x0 + i * ecart, y + i * 0.004F, z, lumiere);
        }
        if (dosCache) {
            carte(p, ps, buf, 0, true, x0 + cartes.size() * ecart, y + cartes.size() * 0.004F, z, lumiere);
        }
    }

    static void blackjack(InteractifBlockEntity be, float partialTick, PoseStack ps, MultiBufferSource buf, int lumiere,
                          PlayerModel<LivingEntity> croupier) {
        Level level = be.getLevel();
        long maintenant = level.getGameTime();
        float temps = maintenant + partialTick;
        Pinceau p = new Pinceau(buf, ps, lumiere);
        pieds(p, -1.45F, -1.45F, 1.45F, 0.45F);
        plateau(p, -1.48F, -1.48F, 1.48F, 0.48F);
        // l'accoudoir en cuir côté joueurs
        p.pave(0xFF1A1414, -1.48F, 0.92F, 0.34F, 1.48F, 1.0F, 0.5F);
        // les cases de mise
        for (int s = 0; s < 3; s++) {
            float x = -1F + s;
            p.pave(OR, x - 0.17F, 0.931F, -0.02F, x + 0.17F, 0.933F, 0.0F);
            p.pave(OR, x - 0.17F, 0.931F, 0.26F, x + 0.17F, 0.933F, 0.28F);
            p.pave(OR, x - 0.17F, 0.931F, 0.0F, x - 0.15F, 0.933F, 0.26F);
            p.pave(OR, x + 0.15F, 0.931F, 0.0F, x + 0.17F, 0.933F, 0.26F);
        }
        texteAPlat(ps, buf, gras("LE BLACKJACK PAIE 3 POUR 2"), 0, 0.9335F, -0.48F, 0, 0.0075F, 0xFFE8C15A, lumiere);
        texteAPlat(ps, buf, Component.literal("La banque tire jusqu'à 17"), 0, 0.9335F, -0.36F, 0, 0.0055F, 0xFFE8D9A0, lumiere);
        // le sabot de cartes et le rack de jetons du croupier
        p.pave(0xFF2A1A10, 0.95F, 0.93F, -1.32F, 1.3F, 1.06F, -1.05F);
        p.pave(0xFF1F3F8C, 0.98F, 0.96F, -1.05F, 1.27F, 1.04F, -1.03F);
        p.pave(0xFF2A1A10, -0.5F, 0.93F, -1.38F, 0.5F, 0.96F, -1.2F);
        int[] couleurs = {0xFFC0392B, 0xFF2471A3, 0xFF1C1C1C, 0xFF27AE60, 0xFFF1C40F};
        for (int i = 0; i < 5; i++) {
            jetons(p, -0.4F + i * 0.2F, 0.96F, -1.29F, 6, couleurs[i]);
        }

        // le croupier, debout de l'autre côté
        croupier(croupier, ps, buf, lumiere, temps, !be.places().isEmpty());
        Rendu3D.etiquette(ps, buf, gras("Le croupier"), 0, 2.15, -2.0, 0.014F, 0xFFFFD75E, 0x60000000, lumiere);
        Rendu3D.etiquette(ps, buf, gras("BLACKJACK"), 0, 2.45, -2.0, 0.026F, 0xFFFFD75E, 0x70000000, lumiere);

        // les mains de chaque joueur assis, et celle de la banque en face
        for (InteractifBlockEntity.Place place : be.places()) {
            boolean enCours = place.etat() == Blackjack.EN_COURS;
            if (!enCours && maintenant - place.fin() > 600) {
                continue;
            }
            float sx = -1F + place.siege();
            main(p, ps, buf, place.cartes(), false, sx, 0.12F, 0.934F, lumiere);
            main(p, ps, buf, place.banque(), place.cache(), sx * 0.72F, -0.9F, 0.934F, lumiere);
            int pile = (int) Mth.clamp(Math.log(Math.max(1, place.mise())) / Math.log(2.5), 1, 9);
            jetons(p, sx + 0.24F, 0.93F, 0.12F, pile, place.mise() >= 1000 ? 0xFF1C1C1C : place.mise() >= 100 ? 0xFF2471A3 : 0xFFC0392B);
            int total = Blackjack.valeur(place.cartes());
            String etat = switch (place.etat()) {
                case Blackjack.EN_COURS -> total > 21 ? "" : " : " + total;
                case Blackjack.BLACKJACK -> " : BLACKJACK !";
                case Blackjack.GAGNE -> " : gagné " + Economie.format(place.gain());
                case Blackjack.EGALITE -> " : égalité";
                default -> " : perdu";
            };
            int couleur = place.etat() == Blackjack.PERDU ? 0xFFFF6B6B : enCours ? 0xFFFFFFFF : 0xFF7CFF9B;
            Rendu3D.etiquette(ps, buf, Component.literal(place.nom() + etat), sx, 1.3, 0.15, 0.012F, couleur, 0x70000000, lumiere);
            String banque = "Banque : " + Blackjack.valeur(place.banque()) + (place.cache() ? " + ?" : "");
            Rendu3D.etiquette(ps, buf, Component.literal(banque), sx * 0.72F, 1.15, -0.9, 0.01F, 0xFFE8D9A0, 0x60000000, lumiere);
        }
    }

    private static void croupier(PlayerModel<LivingEntity> modele, PoseStack ps, MultiBufferSource buf, int lumiere, float temps, boolean distribue) {
        ps.pushPose();
        ps.translate(0, 0, -2.0);
        ps.mulPose(Axis.YP.rotationDegrees(180));
        ps.scale(-1F, -1F, 1F);
        ps.translate(0, -1.501F, 0);
        modele.young = false;
        modele.crouching = false;
        modele.riding = false;
        modele.head.yRot = Mth.sin(temps * 0.035F) * 0.45F;
        modele.head.xRot = 0.25F;
        modele.head.zRot = 0;
        modele.body.xRot = 0.08F;
        float geste = distribue ? Mth.sin(temps * 0.25F) * 0.12F : 0F;
        modele.rightArm.xRot = -0.95F + geste;
        modele.rightArm.yRot = -0.15F;
        modele.leftArm.xRot = -0.8F;
        modele.leftArm.yRot = 0.2F;
        modele.rightArm.zRot = 0;
        modele.leftArm.zRot = 0;
        modele.rightLeg.xRot = 0;
        modele.leftLeg.xRot = 0;
        modele.hat.copyFrom(modele.head);
        modele.jacket.copyFrom(modele.body);
        modele.rightSleeve.copyFrom(modele.rightArm);
        modele.leftSleeve.copyFrom(modele.leftArm);
        modele.rightPants.copyFrom(modele.rightLeg);
        modele.leftPants.copyFrom(modele.leftLeg);
        modele.renderToBuffer(ps, buf.getBuffer(modele.renderType(PEAU_CROUPIER)), lumiere, OverlayTexture.NO_OVERLAY);
        ps.popPose();
    }

    private CasinoRendu() {
    }
}
