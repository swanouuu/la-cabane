package fr.cabane.lutte;

import fr.cabane.lutte.LutteData.Ring;
import fr.cabane.lutte.RingBlocs.Couleur;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.LightBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * La salle de catch, comme à la télé (repris de docs/catch/generateur/salle.mjs) : le ring de 12 × 12 au milieu, ses
 * tapis bleus et ses barrières, la table des commentateurs et sa cloche, l'allée bordée de led jusqu'à la scène, l'écran
 * géant et ses colonnes de led, les tribunes en gradins pleines de monde tout autour, la herse de projecteurs, le tout
 * dans le noir sous un toit. Le ring est au centre (0, 0) ; la scène au nord.
 */
public final class SalleBuilder {
    /** À changer après toute modification : la salle se reconstruit à la prochaine visite. */
    public static final int VERSION = 1;
    /** Le dessus du sol de la salle. */
    public static final int SOL = 64;
    /** On arrive au milieu de la scène, face au ring. */
    public static final Vec3 ARRIVEE = new Vec3(0.5, SOL + 3, -27.5);
    private static final int BORD = 25, NORD = -38;

    static void construire(ServerLevel l) {
        LutteData data = LutteData.get(l.getServer());
        for (Ring r : List.copyOf(data.rings())) {
            if (r.salle()) {
                data.enlever(r);
            }
        }
        BlockState air = Blocks.AIR.defaultBlockState();
        boite(l, -BORD, SOL - 2, NORD, BORD, SOL + 20, BORD, air);
        BlockState noir = Blocks.BLACK_CONCRETE.defaultBlockState();
        // le sol, les tapis bleus autour du ring
        boite(l, -BORD, SOL - 1, NORD, BORD, SOL - 1, BORD, noir);
        boite(l, -9, SOL - 1, -9, 8, SOL - 1, 8, Blocks.BLUE_TERRACOTTA.defaultBlockState());
        // le ring
        Rings.construire(l, -6, SOL, -6, 12, 12, Couleur.ROUGE, true);
        // les barrières, avec l'ouverture de l'allée au nord
        for (int k = -10; k <= 9; k++) {
            for (int[] c : new int[][] {{k, -10}, {k, 9}, {-10, k}, {9, k}}) {
                if (!(c[1] == -10 && c[0] >= -2 && c[0] <= 1)) {
                    pose(l, c[0], SOL, c[1], noir);
                }
            }
        }
        // la table des commentateurs, ses écrans, la cloche
        boite(l, 7, SOL, -3, 7, SOL, 2, Blocks.POLISHED_BLACKSTONE.defaultBlockState());
        for (int z : new int[] {-2, 0, 2}) {
            pose(l, 7, SOL + 1, z, Lutte.ECRAN.get().defaultBlockState());
        }
        pose(l, 7, SOL + 1, -3, Lutte.CLOCHE.get().defaultBlockState());
        // l'allée vers la scène, bordée de led
        boite(l, -2, SOL, -23, 1, SOL, -10, Blocks.POLISHED_BLACKSTONE.defaultBlockState());
        for (int z = -23; z <= -11; z++) {
            pose(l, -3, SOL, z, Lutte.LED.get().defaultBlockState());
            pose(l, 2, SOL, z, Lutte.LED.get().defaultBlockState());
        }
        boite(l, -2, SOL, -24, 1, SOL + 1, -24, Blocks.POLISHED_BLACKSTONE.defaultBlockState());
        // la scène, l'écran géant, ses colonnes de led, le bouton du retour
        boite(l, -15, SOL, -34, 14, SOL + 1, -25, noir);
        boite(l, -15, SOL + 2, -34, 14, SOL + 2, -25, Blocks.POLISHED_BLACKSTONE.defaultBlockState());
        boite(l, -11, SOL + 3, -35, 10, SOL + 13, -35, Lutte.ECRAN.get().defaultBlockState());
        pose(l, -11, SOL + 3, -35, Lutte.ECRAN_LOGO.get().defaultBlockState());
        for (int x : new int[] {-14, -13, 12, 13}) {
            boite(l, x, SOL + 3, -35, x, SOL + 15, -34, Lutte.LED.get().defaultBlockState());
        }
        boite(l, -15, SOL + 15, -35, 14, SOL + 15, -35, Lutte.LED.get().defaultBlockState());
        pose(l, 11, SOL + 3, -33, Lutte.BOUTON.get().defaultBlockState());
        // les tribunes en gradins, et le public dessus (sauf dans les escaliers), tourné vers le ring
        BlockState gradin = Blocks.DEEPSLATE_TILES.defaultBlockState(), led = Lutte.LED.get().defaultBlockState();
        for (int x = -BORD + 1; x < BORD; x++) {
            for (int z = NORD + 1; z < BORD; z++) {
                int h = haut(x, z);
                if (h == 0) {
                    continue;
                }
                for (int y = 0; y < h; y++) {
                    pose(l, x, SOL + y, z, h == 1 && y == 0 ? led : gradin);
                }
                double ax = Math.abs(x + 0.5), az = Math.abs(z + 0.5);
                int leLong = ax > az ? z : x;
                if (Math.floorMod(leLong, 9) == 4 || Math.floorMod(Mth.getSeed(x, 0, z), 100L) < 6) {
                    continue;
                }
                Direction regard = ax > az ? (x > 0 ? Direction.WEST : Direction.EAST) : (z > 0 ? Direction.NORTH : Direction.SOUTH);
                pose(l, x, SOL + h, z, Lutte.PUBLIC.get().defaultBlockState().setValue(HorizontalDirectionalBlock.FACING, regard));
            }
        }
        // les murs et le toit
        for (int x = -BORD; x <= BORD; x++) {
            boite(l, x, SOL - 1, NORD, x, SOL + 18, NORD, noir);
            boite(l, x, SOL - 1, BORD, x, SOL + 18, BORD, noir);
        }
        for (int z = NORD; z <= BORD; z++) {
            boite(l, -BORD, SOL - 1, z, -BORD, SOL + 18, z, noir);
            boite(l, BORD, SOL - 1, z, BORD, SOL + 18, z, noir);
        }
        boite(l, -BORD, SOL + 19, NORD, BORD, SOL + 19, BORD, noir);
        // la herse de projecteurs au-dessus du ring, et la lumière (invisible) : forte sur le ring, douce ailleurs
        BlockState fer = Blocks.IRON_BLOCK.defaultBlockState(), projecteur = Lutte.PROJECTEUR.get().defaultBlockState();
        for (int k = -7; k <= 7; k++) {
            pose(l, k, SOL + 12, -7, fer);
            pose(l, k, SOL + 12, 7, fer);
            pose(l, -7, SOL + 12, k, fer);
            pose(l, 7, SOL + 12, k, fer);
        }
        for (int k = -6; k <= 6; k += 3) {
            pose(l, k, SOL + 11, -7, projecteur);
            pose(l, k, SOL + 11, 7, projecteur);
            pose(l, -7, SOL + 11, k, projecteur);
            pose(l, 7, SOL + 11, k, projecteur);
        }
        for (int x = -5; x <= 5; x += 3) {
            for (int z = -5; z <= 5; z += 3) {
                pose(l, x, SOL + 7, z, Blocks.LIGHT.defaultBlockState().setValue(LightBlock.LEVEL, 15));
            }
        }
        for (int x = -BORD + 3; x < BORD; x += 6) {
            for (int z = NORD + 3; z < BORD; z += 6) {
                if (l.getBlockState(new BlockPos(x, SOL + 17, z)).isAir()) {
                    pose(l, x, SOL + 17, z, Blocks.LIGHT.defaultBlockState().setValue(LightBlock.LEVEL, 10));
                }
            }
        }
    }

    /** La hauteur des gradins en (x, z) : une marche par bloc en s'éloignant du ring ; à côté de la scène, le long de x. */
    static int haut(int x, int z) {
        int ax = Math.abs(x + (x < 0 ? 1 : 0));
        if (z < -10) {
            return ax >= 17 && ax <= 23 && z >= NORD + 1 ? ax - 11 : 0;
        }
        int d = Math.max(ax, Math.abs(z + (z < 0 ? 1 : 0)));
        if (d < 12 || d > 23 || (z < -10 && ax <= 16)) {
            return 0;
        }
        return d - 11;
    }

    /** Les flammes de la scène quand une star arrive. */
    static void pyro(ServerLevel l) {
        for (int x : new int[] {-12, -5, 4, 11}) {
            l.sendParticles(ParticleTypes.FLAME, x + 0.5, SOL + 3.2, -25.0, 40, 0.15, 1.2, 0.15, 0.04);
            l.sendParticles(ParticleTypes.LAVA, x + 0.5, SOL + 3.2, -25.0, 4, 0.1, 0.3, 0.1, 0);
        }
        l.playSound(null, 0.5, SOL + 4, -26, SoundEvents.FIRECHARGE_USE, SoundSource.BLOCKS, 1F, 0.7F);
    }

    private static void pose(ServerLevel l, int x, int y, int z, BlockState s) {
        l.setBlock(new BlockPos(x, y, z), s, Block.UPDATE_CLIENTS);
    }

    private static void boite(ServerLevel l, int x0, int y0, int z0, int x1, int y1, int z1, BlockState s) {
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int x = Math.min(x0, x1); x <= Math.max(x0, x1); x++) {
            for (int y = Math.min(y0, y1); y <= Math.max(y0, y1); y++) {
                for (int z = Math.min(z0, z1); z <= Math.max(z0, z1); z++) {
                    l.setBlock(p.set(x, y, z), s, Block.UPDATE_CLIENTS);
                }
            }
        }
    }

    private SalleBuilder() {
    }
}
