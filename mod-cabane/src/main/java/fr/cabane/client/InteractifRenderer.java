package fr.cabane.client;

import com.mojang.blaze3d.vertex.PoseStack;
import fr.cabane.bloc.BlocInteractif;
import fr.cabane.bloc.InteractifBlockEntity;
import fr.cabane.boutique.Contrats;
import fr.cabane.economie.Economie;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.phys.AABB;

/**
 * Au-dessus du tableau des contrats : la liste de l'heure, ce qui est déjà fait, le temps avant les
 * nouveaux. Au-dessus des jeux du casino : le nom du jeu et ce qu'il y a dans la caisse.
 */
public class InteractifRenderer implements BlockEntityRenderer<InteractifBlockEntity> {
    private final net.minecraft.client.model.PlayerModel<net.minecraft.world.entity.LivingEntity> croupier;

    public InteractifRenderer(BlockEntityRendererProvider.Context context) {
        croupier = new net.minecraft.client.model.PlayerModel<>(context.bakeLayer(net.minecraft.client.model.geom.ModelLayers.PLAYER), false);
    }

    @Override
    public void render(InteractifBlockEntity be, float partialTick, PoseStack ps, MultiBufferSource buf, int lumiere, int overlay) {
        if (be.getLevel() == null || !(be.getBlockState().getBlock() instanceof BlocInteractif bloc)) {
            return;
        }
        int clair = LevelRenderer.getLightColor(be.getLevel(), be.getBlockPos().above());
        if (bloc.ouverture() == BlocInteractif.Ouverture.CONTRATS) {
            contrats(be, ps, buf, clair);
        } else if (bloc.ouverture() == BlocInteractif.Ouverture.TERRAIN) {
            if (be.proprietaire() == null) {
                Rendu3D.etiquette(ps, buf, Component.literal("À VENDRE").withStyle(s -> s.withBold(true)), 0.5, 1.75, 0.5, 0.026F, 0xFFFF5252, 0x70000000, clair);
                Rendu3D.etiquette(ps, buf, Component.literal(Economie.format(be.caisse())), 0.5, 1.5, 0.5, 0.022F, 0xFFFFD75E, 0x70000000, clair);
                Rendu3D.etiquette(ps, buf, Component.literal("clic droit pour acheter"), 0.5, 1.32, 0.5, 0.012F, 0xFFB4BCC8, 0x70000000, clair);
            } else {
                Rendu3D.etiquette(ps, buf, Component.literal("Terrain de " + be.nomProprietaire()), 0.5, 1.4, 0.5, 0.016F, 0xFF7CFF9B, 0x50000000, clair);
            }
        } else if (bloc.ouverture().casino()) {
            if (fr.cabane.bloc.GrandesTables.grande(bloc.ouverture())) {
                // les grandes tables : tout est en 3D, dans le repère du joueur qui les a posées
                Direction face = be.getBlockState().getValue(HorizontalDirectionalBlock.FACING);
                ps.pushPose();
                ps.translate(0.5, 0, 0.5);
                Rendu3D.tourner(ps, -face.toYRot());
                switch (bloc.ouverture()) {
                    case ROULETTE -> CasinoRendu.roulette(be, partialTick, ps, buf, clair);
                    case ROUE -> CasinoRendu.roue(be, partialTick, ps, buf, clair);
                    default -> CasinoRendu.blackjack(be, partialTick, ps, buf, clair, croupier);
                }
                // la caisse, au-dessus du nom du jeu
                String caisse = "Caisse : " + Economie.format(be.caisse());
                double[] ou = switch (bloc.ouverture()) {
                    case ROULETTE -> new double[] {0.5, 2.3, -0.5};
                    case ROUE -> new double[] {0, 6.32, 0};
                    default -> new double[] {0, 2.72, -2.0};
                };
                Rendu3D.etiquette(ps, buf, Component.literal(caisse), ou[0], ou[1], ou[2], 0.014F, be.caisse() > 0 ? 0xFFFFD75E : 0xFFFF6B6B, 0x60000000, clair);
                ps.popPose();
                return;
            }
            String caisse = "Caisse : " + Economie.format(be.caisse());
            Rendu3D.etiquette(ps, buf, Component.literal(caisse), 0.5, 1.45, 0.5, 0.016F, be.caisse() > 0 ? 0xFFFFD75E : 0xFFFF6B6B, 0x60000000, clair);
        }
    }

    private static void contrats(InteractifBlockEntity be, PoseStack ps, MultiBufferSource buf, int clair) {
        Direction face = be.getBlockState().getValue(HorizontalDirectionalBlock.FACING);
        ps.pushPose();
        ps.translate(0.5, 0, 0.5);
        Rendu3D.tourner(ps, -face.toYRot());
        ps.translate(0, 0, 0.52);
        var liste = ClientBoutique.contratsAffiches;
        long reste = Math.max(0, ClientBoutique.finContrats - System.currentTimeMillis());
        float y = 1.15F + 0.2F * (liste.size() + 3);
        Rendu3D.texte(ps, buf, Component.literal("CONTRATS DE L'HEURE").withStyle(s -> s.withBold(true)), 0, y, 0.022F, 0xFFFFD75E, 0x80000000, clair);
        y -= 0.2F;
        String chrono = ClientBoutique.finContrats == 0 ? "…" : String.format("nouveaux dans %02d:%02d", reste / 60000, reste / 1000 % 60);
        Rendu3D.texte(ps, buf, Component.literal(chrono), 0, y, 0.015F, 0xFFB4BCC8, 0x80000000, clair);
        for (Contrats.Vue v : liste) {
            y -= 0.22F;
            Component ligne = Component.literal(v.fait() ? "✔ " : "")
                    .append(v.demande().getCount() + " × ").append(v.demande().getHoverName())
                    .append("  →  " + Economie.format(v.recompense()));
            Rendu3D.texte(ps, buf, ligne, 0, y, 0.016F, v.fait() ? 0xFF7CFF9B : 0xFFFFFFFF, 0x80000000, clair);
        }
        y -= 0.2F;
        Rendu3D.texte(ps, buf, Component.literal("clic droit : livrer  ·  accroupi : détails"), 0, y, 0.012F, 0xFF9AA4B2, 0x80000000, clair);
        ps.popPose();
    }

    @Override
    public AABB getRenderBoundingBox(InteractifBlockEntity be) {
        return new AABB(be.getBlockPos()).inflate(3, 6, 3);
    }
}
