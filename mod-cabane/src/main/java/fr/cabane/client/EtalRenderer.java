package fr.cabane.client;

import com.mojang.blaze3d.vertex.PoseStack;
import fr.cabane.bloc.EtalBlockEntity;
import fr.cabane.economie.Economie;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;

/**
 * Au-dessus d'un étal : les objets en vente tournent en cercle et sur eux-mêmes ; l'un après
 * l'autre, celui qui passe devant affiche son nom et son prix. Plus haut, le nom du vendeur.
 */
public class EtalRenderer implements BlockEntityRenderer<EtalBlockEntity> {
    public EtalRenderer(BlockEntityRendererProvider.Context context) {
    }

    @Override
    public void render(EtalBlockEntity etal, float partialTick, PoseStack ps, MultiBufferSource buf, int lumiere, int overlay) {
        if (etal.getLevel() == null) {
            return;
        }
        int clair = LevelRenderer.getLightColor(etal.getLevel(), etal.getBlockPos().above());
        List<Integer> lots = new ArrayList<>();
        for (int i = 0; i < EtalBlockEntity.LOTS; i++) {
            if (!etal.stock().getStackInSlot(i).isEmpty() && etal.prix(i) > 0) {
                lots.add(i);
            }
        }
        float t = etal.getLevel().getGameTime() + partialTick;
        String vendeur = etal.nomProprietaire().isEmpty() ? "Étal" : "Étal de " + etal.nomProprietaire();
        Rendu3D.etiquette(ps, buf, Component.literal(vendeur).withStyle(s -> s.withBold(true)), 0.5, 2.25, 0.5, 0.022F, 0xFFFFD75E, 0x60000000, clair);
        if (lots.isEmpty()) {
            Rendu3D.etiquette(ps, buf, Component.literal("rien en vente pour l'instant"), 0.5, 2.0, 0.5, 0.016F, 0xFFB4BCC8, 0x60000000, clair);
            return;
        }
        int n = lots.size();
        float rayon = n == 1 ? 0F : 0.42F;
        for (int k = 0; k < n; k++) {
            ItemStack stack = etal.stock().getStackInSlot(lots.get(k));
            double angle = Math.toRadians(t * 1.2F + k * 360F / n);
            ps.pushPose();
            ps.translate(0.5 + Math.cos(angle) * rayon, 1.35 + Math.sin(t / 12F + k) * 0.05, 0.5 + Math.sin(angle) * rayon);
            Rendu3D.tourner(ps, t * 3F);
            float echelle = n == 1 ? 0.6F : 0.38F;
            ps.scale(echelle, echelle, echelle);
            Rendu3D.objet(ps, buf, stack, etal.getLevel(), clair);
            ps.popPose();
        }
        int courant = lots.get((int) (t / 50F) % n);
        ItemStack stack = etal.stock().getStackInSlot(courant);
        Component nom = Component.literal(stack.getCount() > 1 ? stack.getCount() + " × " : "").append(stack.getHoverName());
        Rendu3D.etiquette(ps, buf, nom, 0.5, 1.95, 0.5, 0.018F, 0xFFFFFFFF, 0x60000000, clair);
        Rendu3D.etiquette(ps, buf, Component.literal(Economie.format(etal.prix(courant))).withStyle(s -> s.withBold(true)),
                0.5, 1.78, 0.5, 0.02F, 0xFFFFD75E, 0x60000000, clair);
    }

    @Override
    public AABB getRenderBoundingBox(EtalBlockEntity etal) {
        return new AABB(etal.getBlockPos()).inflate(1, 2, 1);
    }
}
