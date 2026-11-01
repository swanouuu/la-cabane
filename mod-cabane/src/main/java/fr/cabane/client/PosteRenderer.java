package fr.cabane.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import fr.cabane.drogue.Labos.Poste;
import fr.cabane.drogue.Labos.PosteBlockEntity;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.phys.AABB;

/**
 * Sur les postes des labos : la marchandise qui travaille, à sa place sur le grand modèle (pendue au séchoir, écrasée
 * sous la presse, qui flotte dans le bac, qui tourne dans la cuve du réacteur…), l'avancement en pourcentage, et le
 * résultat qui flotte au-dessus quand c'est prêt. Les coordonnées sont celles du modèle (en seizièmes), tourné comme le bloc.
 */
public class PosteRenderer implements BlockEntityRenderer<PosteBlockEntity> {
    public PosteRenderer(BlockEntityRendererProvider.Context context) {
    }

    /** Le milieu du poste, dans le repère du modèle (en seizièmes) : là où flottent les étiquettes et le résultat. */
    private static float[] milieu(Poste p) {
        return switch (p) {
            case SECHOIR -> new float[] {8, 34, 8};
            case PRESSE -> new float[] {0, 34, 8};
            case BAC -> new float[] {0, 18, 16};
            case RAFFINAGE -> new float[] {8, 20, 16};
            case REACTEUR -> new float[] {0, 34, 16};
            case DECOUPE -> new float[] {8, 20, 8};
        };
    }

    @Override
    public void render(PosteBlockEntity be, float partialTick, PoseStack ps, MultiBufferSource buf, int lumiere, int overlay) {
        if (be.getLevel() == null) {
            return;
        }
        int clair = LevelRenderer.getLightColor(be.getLevel(), be.getBlockPos().above());
        float t = be.getLevel().getGameTime() + partialTick;
        Poste poste = be.poste();
        Direction face = be.getBlockState().getValue(HorizontalDirectionalBlock.FACING);
        ps.pushPose();
        // le modèle est tourné comme le bloc (nord : pas de rotation, puis dans le sens des aiguilles d'une montre)
        ps.translate(0.5, 0, 0.5);
        ps.mulPose(Axis.YP.rotationDegrees(-face.toYRot() + 180F));
        ps.translate(-0.5, 0, -0.5);
        ps.scale(1 / 16F, 1 / 16F, 1 / 16F);
        ItemStack enCours = be.enCours();
        float[] m = milieu(poste);
        if (!enCours.isEmpty()) {
            float k = be.avancement();
            switch (poste) {
                case SECHOIR -> {
                    // les têtes pendues le long des cordes, qui se balancent
                    for (int i = 0; i < 6; i++) {
                        objet(ps, buf, be, enCours, clair, -9 + i * 7, 8.5F + (i % 2) * 9, 8, 0, (float) Math.sin(t / 9F + i) * 10F, 5F);
                    }
                }
                // écrasé sous le plateau, de plus en plus plat
                case PRESSE -> objetAPlat(ps, buf, be, enCours, clair, 0, 6, 8, t * 0.2F, 8F, 1F - 0.7F * k);
                case BAC -> {
                    // les feuilles qui flottent et tournent dans le bac
                    for (int i = 0; i < 3; i++) {
                        double a = t / 30F + i * 2.1;
                        objetAPlat(ps, buf, be, enCours, clair, 8 + (float) Math.cos(a) * 3, 12.6F + (float) Math.sin(t / 7F + i) * 0.3F,
                                8 + (float) Math.sin(a) * 3, t * 2 + i * 60, 5F, 1F);
                    }
                }
                // dans la cuve : ça tourne de plus en plus vite
                case REACTEUR -> objet(ps, buf, be, enCours, clair, 0, 13, 16, t * (4 + 14 * k), 0, 6F);
                case RAFFINAGE -> {
                    objetAPlat(ps, buf, be, enCours, clair, -4, 16, 8, t * 0.6F, 7F, 1F);
                    objetAPlat(ps, buf, be, enCours, clair, 20, 16, 8, -t * 0.6F, 7F, 1F);
                }
                default -> objetAPlat(ps, buf, be, enCours, clair, -5, 16, 8, t * 0.6F, 8F, 1F);
            }
        }
        ItemStack sortie = be.sortie();
        if (!sortie.isEmpty()) {
            ps.pushPose();
            ps.translate(m[0], m[1] - 6 + Math.sin(t / 10F) * 0.8F, m[2]);
            Rendu3D.tourner(ps, t * 3F);
            ps.scale(7F, 7F, 7F);
            Rendu3D.objet(ps, buf, sortie, be.getLevel(), 0xF000F0);
            ps.popPose();
        }
        ps.popPose();
        // les étiquettes, hors de la rotation du modèle (elles font face à la caméra)
        double angle = Math.toRadians(-face.toYRot() + 180F);
        double mx = m[0] / 16.0 - 0.5, mz = m[2] / 16.0 - 0.5;
        double x = 0.5 + mx * Math.cos(angle) + mz * Math.sin(angle);
        double z = 0.5 - mx * Math.sin(angle) + mz * Math.cos(angle);
        double y = m[1] / 16.0;
        if (!enCours.isEmpty()) {
            Rendu3D.etiquette(ps, buf, Component.literal(poste.nom + " · " + Math.round(be.avancement() * 100) + " %"), x, y, z, 0.02F, 0xFFFFD75E, 0x60000000, clair);
        }
        if (!sortie.isEmpty()) {
            Rendu3D.etiquette(ps, buf, Component.literal(sortie.getCount() + " × ").append(sortie.getHoverName()), x, y + 0.15, z, 0.02F, 0xFFFFFFFF, 0x60000000, clair);
            Rendu3D.etiquette(ps, buf, Component.literal("Prêt ! Clic droit pour le prendre"), x, y, z, 0.016F, 0xFF8CF08C, 0x60000000, clair);
        }
    }

    /** Un objet debout (qui peut tourner sur lui-même et se balancer), en x, y, z du modèle. */
    private static void objet(PoseStack ps, MultiBufferSource buf, PosteBlockEntity be, ItemStack stack, int clair, float x, float y, float z,
                              float tour, float balance, float taille) {
        ps.pushPose();
        ps.translate(x, y, z);
        ps.mulPose(Axis.ZP.rotationDegrees(balance));
        Rendu3D.tourner(ps, tour);
        ps.scale(taille, taille, taille);
        Rendu3D.objet(ps, buf, stack, be.getLevel(), clair);
        ps.popPose();
    }

    /** Un objet couché à plat (écrasé en hauteur par « plat »). */
    private static void objetAPlat(PoseStack ps, MultiBufferSource buf, PosteBlockEntity be, ItemStack stack, int clair, float x, float y, float z,
                                   float tour, float taille, float plat) {
        ps.pushPose();
        ps.translate(x, y, z);
        ps.mulPose(Axis.XP.rotationDegrees(90));
        ps.mulPose(Axis.ZP.rotationDegrees(tour));
        ps.scale(taille, taille, taille * plat);
        Rendu3D.objet(ps, buf, stack, be.getLevel(), clair);
        ps.popPose();
    }

    @Override
    public AABB getRenderBoundingBox(PosteBlockEntity be) {
        // le grand modèle déborde sur les cases voisines
        return new AABB(be.getBlockPos()).inflate(2.5);
    }
}
