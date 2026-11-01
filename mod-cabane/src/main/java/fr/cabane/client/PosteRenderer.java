package fr.cabane.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import fr.cabane.drogue.Labos.Poste;
import fr.cabane.drogue.Labos.PosteBlockEntity;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

/**
 * Sur les postes des labos : la marchandise qui travaille, posée sur la table (pendue au séchoir, écrasée sous la presse,
 * qui flotte dans le bac, qui tourne dans le ballon du réacteur…), l'avancement en pourcentage, et le résultat qui
 * flotte au-dessus quand c'est prêt.
 */
public class PosteRenderer implements BlockEntityRenderer<PosteBlockEntity> {
    public PosteRenderer(BlockEntityRendererProvider.Context context) {
    }

    @Override
    public void render(PosteBlockEntity be, float partialTick, PoseStack ps, MultiBufferSource buf, int lumiere, int overlay) {
        if (be.getLevel() == null) {
            return;
        }
        int clair = LevelRenderer.getLightColor(be.getLevel(), be.getBlockPos().above());
        float t = be.getLevel().getGameTime() + partialTick;
        Poste poste = be.poste();
        ItemStack enCours = be.enCours();
        if (!enCours.isEmpty()) {
            float k = be.avancement();
            switch (poste) {
                case SECHOIR -> {
                    // les têtes pendues à la tringle, qui se balancent
                    for (int i = 0; i < 4; i++) {
                        ps.pushPose();
                        ps.translate(0.2 + i * 0.2, 0.62, 0.5);
                        ps.mulPose(Axis.ZP.rotationDegrees((float) Math.sin(t / 9F + i) * 8F));
                        ps.scale(0.3F, 0.3F, 0.3F);
                        Rendu3D.objet(ps, buf, enCours, be.getLevel(), clair);
                        ps.popPose();
                    }
                }
                case PRESSE -> {
                    // écrasé sous le plateau, de plus en plus plat
                    ps.pushPose();
                    ps.translate(0.5, 0.42, 0.5);
                    ps.mulPose(Axis.XP.rotationDegrees(90));
                    ps.scale(0.5F, 0.5F, 0.5F - 0.35F * k);
                    Rendu3D.objet(ps, buf, enCours, be.getLevel(), clair);
                    ps.popPose();
                }
                case BAC -> {
                    // les feuilles qui flottent et tournent dans le bac
                    for (int i = 0; i < 3; i++) {
                        ps.pushPose();
                        double a = t / 30F + i * 2.1;
                        ps.translate(0.5 + Math.cos(a) * 0.2, 0.84 + Math.sin(t / 7F + i) * 0.02, 0.5 + Math.sin(a) * 0.2);
                        ps.mulPose(Axis.XP.rotationDegrees(90));
                        ps.mulPose(Axis.ZP.rotationDegrees(t * 2 + i * 60));
                        ps.scale(0.35F, 0.35F, 0.35F);
                        Rendu3D.objet(ps, buf, enCours, be.getLevel(), clair);
                        ps.popPose();
                    }
                }
                case REACTEUR -> {
                    // dans le ballon : ça tourne de plus en plus vite
                    ps.pushPose();
                    ps.translate(0.5, 1.02, 0.5);
                    Rendu3D.tourner(ps, t * (4 + 14 * k));
                    ps.scale(0.32F, 0.32F, 0.32F);
                    Rendu3D.objet(ps, buf, enCours, be.getLevel(), clair);
                    ps.popPose();
                }
                default -> {
                    // à plat sur la table, à moitié chemin le produit fini apparaît
                    ps.pushPose();
                    ps.translate(0.5, 0.96, 0.5);
                    ps.mulPose(Axis.XP.rotationDegrees(90));
                    ps.mulPose(Axis.ZP.rotationDegrees(t * 0.6F));
                    ps.scale(0.45F, 0.45F, 0.45F);
                    Rendu3D.objet(ps, buf, enCours, be.getLevel(), clair);
                    ps.popPose();
                }
            }
            String pourcent = Math.round(k * 100) + " %";
            Rendu3D.etiquette(ps, buf, Component.literal(poste.nom + " · " + pourcent), 0.5, 1.6, 0.5, 0.02F, 0xFFFFD75E, 0x60000000, clair);
        }
        ItemStack sortie = be.sortie();
        if (!sortie.isEmpty()) {
            ps.pushPose();
            ps.translate(0.5, 1.3 + Math.sin(t / 10F) * 0.05, 0.5);
            Rendu3D.tourner(ps, t * 3F);
            ps.scale(0.45F, 0.45F, 0.45F);
            Rendu3D.objet(ps, buf, sortie, be.getLevel(), 0xF000F0);
            ps.popPose();
            Component nom = Component.literal(sortie.getCount() + " × ").append(sortie.getHoverName());
            Rendu3D.etiquette(ps, buf, nom, 0.5, 1.85, 0.5, 0.02F, 0xFFFFFFFF, 0x60000000, clair);
            Rendu3D.etiquette(ps, buf, Component.literal("Prêt ! Clic droit pour le prendre"), 0.5, 1.65, 0.5, 0.016F, 0xFF8CF08C, 0x60000000, clair);
        }
    }
}
