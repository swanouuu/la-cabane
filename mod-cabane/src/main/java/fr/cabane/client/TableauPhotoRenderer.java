package fr.cabane.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import fr.cabane.photo.TableauPhoto;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;

/**
 * Une photo accrochée : l'image sur la face avant (rognée au format du cadre, sans être déformée), et un fin cadre de
 * bois sombre sur les côtés et au dos. Chaque bloc prend la lumière de sa case, comme les tableaux.
 */
public class TableauPhotoRenderer extends EntityRenderer<TableauPhoto> {
    private static final ResourceLocation BOIS = ResourceLocation.withDefaultNamespace("textures/block/dark_oak_planks.png");
    private static final ResourceLocation PAPIER = ResourceLocation.withDefaultNamespace("textures/block/white_concrete.png");
    private static final float E = 0.03125F;

    public TableauPhotoRenderer(EntityRendererProvider.Context context) {
        super(context);
    }

    @Override
    public void render(TableauPhoto tableau, float lacet, float partialTick, PoseStack pose, MultiBufferSource buffers, int lumiere) {
        pose.pushPose();
        pose.mulPose(Axis.YP.rotationDegrees(180F - lacet));
        int w = tableau.largeur();
        int h = tableau.hauteur();
        long id = tableau.photo();
        ResourceLocation image = ClientPiaf.photo(id);
        // la photo garde ses proportions : on la rogne au format du cadre
        float u0 = 0, u1 = 1, v0 = 0, v1 = 1;
        if (image != null) {
            int[] t = ClientPiaf.taille(id);
            float cadre = w / (float) h;
            float photo = t[0] / (float) t[1];
            if (photo > cadre) {
                u0 = (1 - cadre / photo) / 2;
                u1 = 1 - u0;
            } else {
                v0 = (1 - photo / cadre) / 2;
                v1 = 1 - v0;
            }
        }
        PoseStack.Pose p = pose.last();
        float gauche = -w / 2F;
        float bas = -h / 2F;
        // la face avant, case par case pour la lumière
        VertexConsumer avant = buffers.getBuffer(RenderType.entitySolid(image != null ? image : PAPIER));
        for (int i = 0; i < w; i++) {
            for (int j = 0; j < h; j++) {
                float xa = gauche + i + 1, xb = gauche + i;
                float ya = bas + j + 1, yb = bas + j;
                int l = lumiere(tableau, (xa + xb) / 2, (ya + yb) / 2);
                float ua = u0 + (u1 - u0) * (w - i - 1) / w, ub = u0 + (u1 - u0) * (w - i) / w;
                float vb = v0 + (v1 - v0) * (h - j) / h, va = v0 + (v1 - v0) * (h - j - 1) / h;
                sommet(p, avant, xa, yb, ua, vb, -E, 0, 0, -1, l);
                sommet(p, avant, xb, yb, ub, vb, -E, 0, 0, -1, l);
                sommet(p, avant, xb, ya, ub, va, -E, 0, 0, -1, l);
                sommet(p, avant, xa, ya, ua, va, -E, 0, 0, -1, l);
            }
        }
        // le dos et les quatre côtés : du bois sombre
        VertexConsumer bois = buffers.getBuffer(RenderType.entitySolid(BOIS));
        float xd = -gauche, xg = gauche, yh = -bas, yb = bas;
        sommet(p, bois, xd, yh, 0, 0, E, 0, 0, 1, lumiere);
        sommet(p, bois, xg, yh, 1, 0, E, 0, 0, 1, lumiere);
        sommet(p, bois, xg, yb, 1, 1, E, 0, 0, 1, lumiere);
        sommet(p, bois, xd, yb, 0, 1, E, 0, 0, 1, lumiere);
        // dessus et dessous
        sommet(p, bois, xd, yh, 0, 0, -E, 0, 1, 0, lumiere);
        sommet(p, bois, xg, yh, 1, 0, -E, 0, 1, 0, lumiere);
        sommet(p, bois, xg, yh, 1, 0.0625F, E, 0, 1, 0, lumiere);
        sommet(p, bois, xd, yh, 0, 0.0625F, E, 0, 1, 0, lumiere);
        sommet(p, bois, xd, yb, 0, 0, E, 0, -1, 0, lumiere);
        sommet(p, bois, xg, yb, 1, 0, E, 0, -1, 0, lumiere);
        sommet(p, bois, xg, yb, 1, 0.0625F, -E, 0, -1, 0, lumiere);
        sommet(p, bois, xd, yb, 0, 0.0625F, -E, 0, -1, 0, lumiere);
        // les côtés
        sommet(p, bois, xd, yh, 0.0625F, 0, E, -1, 0, 0, lumiere);
        sommet(p, bois, xd, yb, 0.0625F, 1, E, -1, 0, 0, lumiere);
        sommet(p, bois, xd, yb, 0, 1, -E, -1, 0, 0, lumiere);
        sommet(p, bois, xd, yh, 0, 0, -E, -1, 0, 0, lumiere);
        sommet(p, bois, xg, yh, 0.0625F, 0, -E, 1, 0, 0, lumiere);
        sommet(p, bois, xg, yb, 0.0625F, 1, -E, 1, 0, 0, lumiere);
        sommet(p, bois, xg, yb, 0, 1, E, 1, 0, 0, lumiere);
        sommet(p, bois, xg, yh, 0, 0, E, 1, 0, 0, lumiere);
        pose.popPose();
        super.render(tableau, lacet, partialTick, pose, buffers, lumiere);
    }

    /** La lumière de la case du mur derrière ce point de la photo (même calcul que les tableaux). */
    private static int lumiere(TableauPhoto t, float x, float y) {
        int bx = t.getBlockX();
        int by = Mth.floor(t.getY() + y);
        int bz = t.getBlockZ();
        Direction d = t.getDirection();
        if (d == Direction.NORTH) {
            bx = Mth.floor(t.getX() + x);
        } else if (d == Direction.WEST) {
            bz = Mth.floor(t.getZ() - x);
        } else if (d == Direction.SOUTH) {
            bx = Mth.floor(t.getX() - x);
        } else if (d == Direction.EAST) {
            bz = Mth.floor(t.getZ() + x);
        }
        return LevelRenderer.getLightColor(t.level(), new BlockPos(bx, by, bz));
    }

    private static void sommet(PoseStack.Pose p, VertexConsumer c, float x, float y, float u, float v, float z, int nx, int ny, int nz, int lumiere) {
        c.addVertex(p, x, y, z).setColor(-1).setUv(u, v).setOverlay(OverlayTexture.NO_OVERLAY).setLight(lumiere).setNormal(p, nx, ny, nz);
    }

    @Override
    public ResourceLocation getTextureLocation(TableauPhoto tableau) {
        ResourceLocation image = ClientPiaf.photo(tableau.photo());
        return image != null ? image : PAPIER;
    }
}
