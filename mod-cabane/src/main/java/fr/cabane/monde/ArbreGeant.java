package fr.cabane.monde;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CocoaBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.VineBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;

/**
 * L'arbre géant de la jungle : un tronc de 4 x 4, des racines en contreforts, de grosses branches, une
 * canopée immense d'où pendent des lianes, du cacao sur l'écorce. Il pousse dans toutes les jungles du monde
 * (biome modifier « arbres_geants »), et le village en plante autour de lui. Il reste dans un rayon de
 * 14 blocs autour de son pied, pour ne jamais déborder des chunks que le monde le laisse modifier.
 */
public class ArbreGeant extends Feature<NoneFeatureConfiguration> {
    private static final int PORTEE = 14;

    public ArbreGeant() {
        super(NoneFeatureConfiguration.CODEC);
    }

    @Override
    public boolean place(FeaturePlaceContext<NoneFeatureConfiguration> contexte) {
        // l'origine est en haut de ce qui pousse déjà : on descend jusqu'au vrai sol
        WorldGenLevel level = contexte.level();
        BlockPos p = contexte.origin();
        for (int i = 0; i < 48 && p.getY() > level.getMinBuildHeight() + 2; i++) {
            BlockState s = level.getBlockState(p.below());
            if (s.is(BlockTags.LOGS)) {
                return false; // un autre arbre est déjà là
            }
            if (s.is(BlockTags.DIRT) || s.is(Blocks.GRASS_BLOCK) || s.is(Blocks.PODZOL) || s.is(Blocks.MUD)) {
                return planter(level, p, contexte.random(), 1F);
            }
            if (!s.isAir() && !s.is(BlockTags.LEAVES) && !s.canBeReplaced() && !s.is(BlockTags.REPLACEABLE_BY_TREES)) {
                return false; // de la pierre, de l'eau…
            }
            p = p.below();
        }
        return false;
    }

    /** Fait pousser un arbre géant dont le tronc commence en {@code pied} (coin nord-ouest du 4 x 4). */
    public static boolean planter(WorldGenLevel level, BlockPos pied, RandomSource r, float taille) {
        // le sol sous le tronc : on comble les creux
        int creux = 0;
        for (int dx = 0; dx < 4; dx++) {
            for (int dz = 0; dz < 4; dz++) {
                BlockState sol = level.getBlockState(pied.offset(dx, -1, dz));
                if (!sol.isSolid()) {
                    creux++;
                }
            }
        }
        if (creux > 6) {
            return false;
        }
        int hauteur = (int) ((28 + r.nextInt(16)) * taille);
        BlockState bois = Blocks.JUNGLE_LOG.defaultBlockState();
        BlockState ecorce = Blocks.JUNGLE_WOOD.defaultBlockState();
        // le tronc : 4 x 4, les coins s'arrondissent en haut
        for (int y = -3; y < hauteur; y++) {
            for (int dx = 0; dx < 4; dx++) {
                for (int dz = 0; dz < 4; dz++) {
                    boolean coin = (dx == 0 || dx == 3) && (dz == 0 || dz == 3);
                    if (coin && y > hauteur * 0.8F) {
                        continue;
                    }
                    bois(level, pied.offset(dx, y, dz), coin && y < 4 ? ecorce : bois, true);
                }
            }
        }
        // les racines en contreforts, qui plongent dans le sol
        for (int i = 0; i < 6 + r.nextInt(4); i++) {
            double angle = (i + r.nextFloat() * 0.6) * Math.PI * 2 / 8;
            int longueur = 3 + r.nextInt(4);
            double dx = Math.cos(angle), dz = Math.sin(angle);
            double y0 = 2 + r.nextInt(3);
            for (int k = 1; k <= longueur; k++) {
                int x = (int) Math.round(1.5 + dx * (1.6 + k));
                int z = (int) Math.round(1.5 + dz * (1.6 + k));
                int y = (int) Math.round(y0 - k * 0.8);
                for (int yy = y; yy >= -2; yy--) {
                    BlockPos q = pied.offset(x, yy, z);
                    if (level.getBlockState(q).isSolid() && yy < y) {
                        break;
                    }
                    bois(level, q, ecorce, false);
                }
            }
        }
        // les grosses branches, avec chacune son bouquet de feuilles
        List<BlockPos> bouquets = new ArrayList<>();
        int nb = 3 + r.nextInt(3);
        for (int i = 0; i < nb; i++) {
            double angle = (i + r.nextFloat() * 0.5) * Math.PI * 2 / nb;
            int y = (int) (hauteur * (0.55F + r.nextFloat() * 0.3F));
            int longueur = 5 + r.nextInt(4);
            double x = 1.5, z = 1.5, yy = y;
            for (int k = 0; k < longueur; k++) {
                x += Math.cos(angle);
                z += Math.sin(angle);
                yy += 0.35 + r.nextFloat() * 0.3;
                Direction.Axis axe = Math.abs(Math.cos(angle)) > Math.abs(Math.sin(angle)) ? Direction.Axis.X : Direction.Axis.Z;
                bois(level, pied.offset((int) Math.round(x), (int) Math.round(yy), (int) Math.round(z)),
                        bois.setValue(RotatedPillarBlock.AXIS, axe), false);
            }
            bouquets.add(pied.offset((int) Math.round(x), (int) Math.round(yy), (int) Math.round(z)));
        }
        // la canopée : un grand dôme aplati, irrégulier
        BlockState feuille = Blocks.JUNGLE_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true);
        List<BlockPos> bords = new ArrayList<>();
        int rayon = Math.min(PORTEE - 3, 8 + r.nextInt(3));
        BlockPos sommet = pied.offset(1, hauteur - 2, 1);
        for (int dx = -rayon; dx <= rayon + 1; dx++) {
            for (int dz = -rayon; dz <= rayon + 1; dz++) {
                for (int dy = -3; dy <= 4; dy++) {
                    double ex = (dx - 0.5) / rayon, ez = (dz - 0.5) / rayon, ey = dy / (dy < 0 ? 3.0 : 4.5);
                    double d = ex * ex + ez * ez + ey * ey;
                    if (d > 1 || (d > 0.75 && r.nextFloat() < 0.35F)) {
                        continue;
                    }
                    BlockPos q = sommet.offset(dx, dy, dz);
                    if (feuille(level, q, feuille) && dy <= -1 && d > 0.55) {
                        bords.add(q);
                    }
                }
            }
        }
        for (BlockPos b : bouquets) {
            int rb = 2 + r.nextInt(2);
            for (int dx = -rb; dx <= rb; dx++) {
                for (int dz = -rb; dz <= rb; dz++) {
                    for (int dy = -1; dy <= 2; dy++) {
                        if (dx * dx + dz * dz + dy * dy * 2 <= rb * rb + 1 && dentro(pied, b.offset(dx, dy, dz))) {
                            BlockPos q = b.offset(dx, dy, dz);
                            if (feuille(level, q, feuille) && dy == -1) {
                                bords.add(q);
                            }
                        }
                    }
                }
            }
        }
        // les lianes qui pendent sous la canopée
        for (BlockPos f : bords) {
            if (r.nextFloat() > 0.3F) {
                continue;
            }
            for (Direction d : Direction.Plane.HORIZONTAL) {
                BlockPos v = f.relative(d);
                if (!level.isEmptyBlock(v) || r.nextBoolean()) {
                    continue;
                }
                BlockState liane = Blocks.VINE.defaultBlockState().setValue(VineBlock.getPropertyForFace(d.getOpposite()), true);
                int longueur = 2 + r.nextInt(9);
                for (int k = 0; k < longueur && level.isEmptyBlock(v) && dentro(pied, v); k++) {
                    level.setBlock(v, liane, 2);
                    v = v.below();
                }
            }
        }
        // les lianes et le cacao sur le tronc
        for (int y = 2; y < hauteur - 3; y++) {
            for (Direction d : Direction.Plane.HORIZONTAL) {
                for (int k = 0; k < 4; k++) {
                    if (r.nextFloat() > 0.18F) {
                        continue;
                    }
                    BlockPos face = cote(pied, d, k, y);
                    if (!level.isEmptyBlock(face)) {
                        continue;
                    }
                    if (r.nextInt(12) == 0 && y < 10) {
                        level.setBlock(face, Blocks.COCOA.defaultBlockState().setValue(CocoaBlock.FACING, d.getOpposite())
                                .setValue(CocoaBlock.AGE, r.nextInt(3)), 2);
                    } else {
                        level.setBlock(face, Blocks.VINE.defaultBlockState().setValue(VineBlock.getPropertyForFace(d.getOpposite()), true), 2);
                    }
                }
            }
        }
        return true;
    }

    /** La case juste à côté du tronc, sur la face {@code d}, à la position {@code k} (0 à 3) et à la hauteur y. */
    private static BlockPos cote(BlockPos pied, Direction d, int k, int y) {
        return switch (d) {
            case NORTH -> pied.offset(k, y, -1);
            case SOUTH -> pied.offset(k, y, 4);
            case WEST -> pied.offset(-1, y, k);
            default -> pied.offset(4, y, k);
        };
    }

    private static boolean dentro(BlockPos pied, BlockPos q) {
        return Math.abs(q.getX() - pied.getX() - 1.5) <= PORTEE && Math.abs(q.getZ() - pied.getZ() - 1.5) <= PORTEE;
    }

    private static void bois(WorldGenLevel level, BlockPos q, BlockState etat, boolean force) {
        BlockState ici = level.getBlockState(q);
        if (force || ici.isAir() || ici.canBeReplaced() || ici.is(BlockTags.LEAVES) || ici.is(BlockTags.REPLACEABLE_BY_TREES) || ici.is(Blocks.VINE)
                || (ici.is(BlockTags.DIRT) && etat.is(Blocks.JUNGLE_WOOD))) {
            level.setBlock(q, etat, 2);
        }
    }

    private static boolean feuille(WorldGenLevel level, BlockPos q, BlockState feuille) {
        BlockState ici = level.getBlockState(q);
        if (ici.isAir() || ici.is(Blocks.VINE) || ici.canBeReplaced() && !ici.liquid()) {
            level.setBlock(q, feuille, 2);
            return true;
        }
        return false;
    }

}
