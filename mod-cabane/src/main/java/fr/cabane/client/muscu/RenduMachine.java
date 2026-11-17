package fr.cabane.client.muscu;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import fr.cabane.muscu.Machine;
import fr.cabane.muscu.MachineBlockEntity;
import fr.cabane.muscu.Poses;
import fr.cabane.muscu.SiegeMuscu;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;

/**
 * Les pièces qui bougent sur les machines : la barre et les poignées dans les mains du joueur, les plaques qui montent,
 * les câbles, les pédales, les volants qui tournent, les bandes du tapis, le chariot de la presse. Le bâti, lui, est le
 * modèle du bloc (outils/muscu.mjs). Tout est dans le repère de la machine (en blocs, le joueur regarde +Z).
 */
public class RenduMachine implements BlockEntityRenderer<MachineBlockEntity> {
    private static final BlockState NOIR = Blocks.BLACK_CONCRETE.defaultBlockState();
    private static final BlockState ROUGE = Blocks.RED_CONCRETE.defaultBlockState();
    private static final BlockState CHROME = Blocks.IRON_BLOCK.defaultBlockState();
    private static final BlockState GRIS = Blocks.GRAY_CONCRETE.defaultBlockState();
    private static final BlockState CUIR = Blocks.BLACK_WOOL.defaultBlockState();
    private static final BlockState BANDE = Blocks.LIGHT_GRAY_CONCRETE.defaultBlockState();

    public RenduMachine(BlockEntityRendererProvider.Context context) {
    }

    @Override
    public void render(MachineBlockEntity be, float partiel, PoseStack ps, MultiBufferSource buf, int lum, int overlay) {
        if (be.getLevel() == null) {
            return;
        }
        Machine m = be.machine();
        Direction face = be.getBlockState().getValue(HorizontalDirectionalBlock.FACING);
        ps.pushPose();
        ps.translate(0.5, 0, 0.5);
        ps.mulPose(Axis.YP.rotation((float) Machine.angle(face)));
        if (m == Machine.RACK) {
            etiquettes(ps, buf, lum);
            ps.popPose();
            return;
        }
        // le joueur dessus (s'il y en a un) : sa répétition et sa phase
        Entity joueur = joueur(be);
        double k = joueur == null ? 0 : ClientMuscu.k(joueur.getId());
        double ph = joueur == null ? 0 : ClientMuscu.phase(joueur.getId(), m);
        Poses.Pose p = Poses.pose(m, k, ph);
        Vec3 mg = Poses.main(m, p, 1), md = Poses.main(m, p, -1), mains = mg.add(md).scale(0.5);
        Vec3 pg = Poses.pied(m, p, 1), pd = Poses.pied(m, p, -1);
        switch (m) {
            case TAPIS -> {
                // les bandes du tapis défilent sous les pieds
                for (int i = 0; i < 8; i++) {
                    double u = ((i / 8.0 - ph / (2 * Math.PI) * 0.45) % 1 + 1) % 1;
                    double z = 1.05 - u * 2.4;
                    cube(ps, buf, BANDE, -0.42, 0.28, z, 0.42, 0.285, z + 0.05, lum);
                }
            }
            case ELLIPTIQUE -> {
                roue(ps, buf, ROUGE, 0, 0.42, -0.25, 0.25, 0.47, ph, lum);
                for (int c : new int[] {1, -1}) {
                    Vec3 pied = c > 0 ? pg : pd, main = c > 0 ? mg : md;
                    Vec3 pedale = pied.add(0, -0.04, 0.05);
                    cubeCentre(ps, buf, ROUGE, pedale, 0.09, 0.02, 0.17, lum);
                    baton(ps, buf, NOIR, pedale, new Vec3(c * 0.25, 0.42, -0.25), 0.07, lum);
                    Vec3 pivot = new Vec3(c * 0.3, 0.3, 1.28);
                    baton(ps, buf, CHROME, pivot, main.add(main.subtract(pivot).normalize().scale(0.2)), 0.06, lum);
                }
            }
            case VELO -> {
                roue(ps, buf, ROUGE, 0, 0.45, 1.06, 0.32, 0.08, ph * 2, lum);
                Vec3 centre = pg.add(pd).scale(0.5);
                for (Vec3 pied : new Vec3[] {pg, pd}) {
                    Vec3 pedale = pied.add(0, -0.04, 0.04);
                    cubeCentre(ps, buf, ROUGE, pedale, 0.08, 0.02, 0.14, lum);
                    baton(ps, buf, CHROME, centre, pedale, 0.05, lum);
                }
            }
            case RAMEUR -> {
                double hz = Poses.hanche(m, p).z;
                cube(ps, buf, CUIR, -0.2, 0.25, hz - 0.18, 0.2, 0.34, hz + 0.18, lum);
                cube(ps, buf, ROUGE, -0.205, 0.27, hz - 0.17, 0.205, 0.31, hz + 0.17, lum);
                cubeCentre(ps, buf, NOIR, mains, 0.28, 0.03, 0.03, lum);
                baton(ps, buf, GRIS, mains, new Vec3(0, 0.44, 0.95), 0.02, lum);
                roue(ps, buf, ROUGE, 0, 0.44, 1.22, 0.3, 0.3, ph * 3, lum);
            }
            case TIRAGE_V -> {
                barre(ps, buf, mains, 0.85, 0.05, lum);
                baton(ps, buf, GRIS, mains, new Vec3(0, 1.88, 0.45), 0.02, lum);
                double haut = plaques(ps, buf, 0.775, 4, k * 0.45, 0.28, 1.32, 1.45, lum);
                baton(ps, buf, GRIS, new Vec3(0, haut, 1.39), new Vec3(0, 1.88, 1.39), 0.02, lum);
            }
            case TIRAGE_H -> {
                cubeCentre(ps, buf, CHROME, mains, 0.16, 0.03, 0.03, lum);
                cubeCentre(ps, buf, CUIR, mains.add(0.12, 0, 0), 0.045, 0.04, 0.04, lum);
                cubeCentre(ps, buf, CUIR, mains.add(-0.12, 0, 0), 0.045, 0.04, 0.04, lum);
                baton(ps, buf, GRIS, mains, new Vec3(0, 0.6, 0.98), 0.02, lum);
                double haut = plaques(ps, buf, 0.65, 4, k * 0.5, 0.28, 1.2, 1.36, lum);
                baton(ps, buf, GRIS, new Vec3(0, haut, 1.28), new Vec3(0, 1.85, 1.28), 0.02, lum);
            }
            case DEVELOPPE -> {
                barre(ps, buf, mains, 1.0, 0.045, lum);
                for (int c : new int[] {1, -1}) {
                    roue(ps, buf, ROUGE, mains.x + c * 0.72, mains.y, mains.z, 0.23, 0.06, 0, lum);
                    roue(ps, buf, NOIR, mains.x + c * 0.79, mains.y, mains.z, 0.18, 0.05, 0, lum);
                }
            }
            case BICEPS -> {
                barre(ps, buf, mains, 0.42, 0.05, lum);
                double haut = plaques(ps, buf, 0.625, 3, k * 0.35, 0.25, 1.16, 1.3, lum);
                roue(ps, buf, ROUGE, 0.5, 1.0, 0.6, 0.12, 0.06, -k * 2.2, lum);
                baton(ps, buf, NOIR, new Vec3(0.5, 1.0, 0.6), new Vec3(0.5, mains.y, mains.z), 0.06, lum);
                baton(ps, buf, GRIS, new Vec3(0.5, 1.05, 0.7), new Vec3(0.2, haut, 1.23), 0.02, lum);
            }
            case PRESSE -> {
                // les rails suivent le chemin des pieds, le chariot est sous les pieds
                Poses.Pose p0 = Poses.pose(m, 0, 0), p1 = Poses.pose(m, 1, 0);
                Vec3 a = Poses.pied(m, p0, 1).add(Poses.pied(m, p0, -1)).scale(0.5);
                Vec3 b = Poses.pied(m, p1, 1).add(Poses.pied(m, p1, -1)).scale(0.5);
                Vec3 dir = b.subtract(a).normalize();
                for (int c : new int[] {1, -1}) {
                    Vec3 r0 = a.add(dir.scale(-0.25)).add(c * 0.48, -0.1, 0), r1 = b.add(dir.scale(0.55)).add(c * 0.48, -0.1, 0);
                    baton(ps, buf, NOIR, r0, r1, 0.1, lum);
                    baton(ps, buf, NOIR, r1, new Vec3(r1.x, 0.1, r1.z), 0.1, lum);
                }
                Vec3 pieds = pg.add(pd).scale(0.5).add(dir.scale(0.1));
                ps.pushPose();
                ps.translate(pieds.x, pieds.y, pieds.z);
                ps.mulPose(new Quaternionf().rotationTo(0, 0, 1, (float) dir.x, (float) dir.y, (float) dir.z));
                cube(ps, buf, NOIR, -0.42, -0.42, -0.04, 0.42, 0.42, 0.06, lum);
                cube(ps, buf, ROUGE, -0.43, -0.43, 0.06, 0.43, 0.43, 0.09, lum);
                for (int c : new int[] {1, -1}) {
                    baton(ps, buf, CHROME, new Vec3(c * 0.42, 0, 0.15), new Vec3(c * 0.75, 0, 0.15), 0.06, lum);
                    roue(ps, buf, ROUGE, c * 0.55, 0, 0.15, 0.24, 0.06, 0, lum);
                    roue(ps, buf, NOIR, c * 0.63, 0, 0.15, 0.19, 0.06, 0, lum);
                }
                ps.popPose();
            }
            default -> {
            }
        }
        ps.popPose();
    }

    private static Entity joueur(MachineBlockEntity be) {
        BlockPos pos = be.getBlockPos();
        for (SiegeMuscu s : be.getLevel().getEntitiesOfClass(SiegeMuscu.class, new AABB(pos).inflate(1.5), s -> pos.equals(s.machinePos()))) {
            Entity e = s.getFirstPassenger();
            if (e != null) {
                return e;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ les briques

    private static void cube(PoseStack ps, MultiBufferSource buf, BlockState etat, double x0, double y0, double z0, double x1, double y1, double z1, int lum) {
        ps.pushPose();
        ps.translate(x0, y0, z0);
        ps.scale((float) (x1 - x0), (float) (y1 - y0), (float) (z1 - z0));
        Minecraft.getInstance().getBlockRenderer().renderSingleBlock(etat, ps, buf, lum, OverlayTexture.NO_OVERLAY);
        ps.popPose();
    }

    private static void cubeCentre(PoseStack ps, MultiBufferSource buf, BlockState etat, Vec3 c, double hx, double hy, double hz, int lum) {
        cube(ps, buf, etat, c.x - hx, c.y - hy, c.z - hz, c.x + hx, c.y + hy, c.z + hz, lum);
    }

    /** Une barre (câble, bielle, rail) entre deux points. */
    private static void baton(PoseStack ps, MultiBufferSource buf, BlockState etat, Vec3 a, Vec3 b, double ep, int lum) {
        Vec3 d = b.subtract(a);
        double l = d.length();
        if (l < 1e-4) {
            return;
        }
        ps.pushPose();
        ps.translate(a.x, a.y, a.z);
        ps.mulPose(new Quaternionf().rotationTo(0, 0, 1, (float) (d.x / l), (float) (d.y / l), (float) (d.z / l)));
        cube(ps, buf, etat, -ep / 2, -ep / 2, 0, ep / 2, ep / 2, l, lum);
        ps.popPose();
    }

    /** Un disque (volant, poulie, disque de fonte) d'axe X, tourné de {@code tour} radians : deux carrés croisés. */
    private static void roue(PoseStack ps, MultiBufferSource buf, BlockState etat, double x, double y, double z, double r, double ep, double tour, int lum) {
        ps.pushPose();
        ps.translate(x, y, z);
        ps.mulPose(Axis.XP.rotation((float) tour));
        double h = r * 0.82;
        cube(ps, buf, etat, -ep / 2, -h, -h, ep / 2, h, h, lum);
        ps.mulPose(Axis.XP.rotationDegrees(45));
        cube(ps, buf, etat, -ep / 2 + 0.001, -h, -h, ep / 2 - 0.001, h, h, lum);
        ps.popPose();
    }

    /** Une barre droite en travers (chromée, poignées noires aux bouts) centrée sur les mains. */
    private static void barre(PoseStack ps, MultiBufferSource buf, Vec3 c, double demi, double ep, int lum) {
        cubeCentre(ps, buf, CHROME, c, demi, ep / 2, ep / 2, lum);
        cubeCentre(ps, buf, CUIR, c.add(demi * 0.8, 0, 0), demi * 0.2, ep * 0.7, ep * 0.7, lum);
        cubeCentre(ps, buf, CUIR, c.add(-demi * 0.8, 0, 0), demi * 0.2, ep * 0.7, ep * 0.7, lum);
    }

    /** Les plaques du haut de la pile, levées de {@code levee} ; renvoie la hauteur du dessus (pour le câble). */
    private static double plaques(PoseStack ps, MultiBufferSource buf, double y0, int n, double levee, double hx, double z0, double z1, int lum) {
        for (int i = 0; i < n; i++) {
            double y = y0 + levee + i * 0.075;
            cube(ps, buf, i == n - 1 ? ROUGE : GRIS, -hx, y, z0, hx, y + 0.068, z1, lum);
        }
        return y0 + levee + n * 0.075;
    }

    /** Les poids écrits sur le devant du rack. */
    private static void etiquettes(PoseStack ps, MultiBufferSource buf, int lum) {
        Font font = Minecraft.getInstance().font;
        for (int i = 0; i < Machine.KILOS.length; i++) {
            String t = Machine.KILOS[i] + " KG";
            double x = 1.25 - i * 0.5;
            ps.pushPose();
            ps.translate(x, 0.41, -0.33);
            ps.mulPose(Axis.YP.rotationDegrees(180));
            ps.scale(0.012F, -0.012F, 0.012F);
            font.drawInBatch(t, -font.width(t) / 2F, 0, 0xFFFFFFFF, false, ps.last().pose(), buf, Font.DisplayMode.NORMAL, 0, lum);
            ps.popPose();
        }
    }

    @Override
    public AABB getRenderBoundingBox(MachineBlockEntity be) {
        return new AABB(be.getBlockPos()).inflate(2.5);
    }
}
