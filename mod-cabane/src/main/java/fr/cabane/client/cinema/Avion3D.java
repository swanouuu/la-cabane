package fr.cabane.client.cinema;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.SlabType;

/**
 * L'avion du vol BV2026, en blocs, mais dessiné par la cinématique (il roule, décolle, vole, brûle) : un moyen-courrier
 * blanc à bande bleue, « AIR BEAUVAIS » sur le flanc. Repère de l'avion : le nez vers +x, l'aile droite vers +z, le
 * plancher de la cabine en y = 0. Le bloc (x, y, z) occupe [x-½, x+½] × [y, y+1] × [z-½, z+½] : l'allée est centrée sur
 * z = 0, et garé avec son origine sur un coin de bloc, l'avion tombe pile sur la grille du monde.
 *
 * <p>Cabine : 6 rangées de 4 sièges (2 + 2, l'allée au milieu), la porte avant gauche (x = 8 et 9), l'office, le poste de
 * pilotage derrière une cloison (x = 11), les deux pilotes en x = 13.
 */
final class Avion3D {
    /** Les rangées (x du siège, de l'avant vers l'arrière) et les places (z). */
    static final int[] RANGEES = {2, -1, -4, -7, -10, -13};
    static final int[] PLACES = {-2, -1, 1, 2};
    /** Le poste de pilotage : les sièges des pilotes. */
    static final int X_PILOTES = 13;
    /** La porte avant gauche (côté passerelle). */
    static final int X_PORTE = 8;
    /** Le réacteur droit (celui qui prend les phantoms) : le centre de son entrée d'air. */
    static final double[] REACTEUR_DROIT = {4.6, -1, 6.5};

    private static final int CORPS = 0;
    private static final int TRAIN = 1;
    private static final int PORTE = 2;
    private static final int FEU = 3;

    private record Bloc(int x, int y, int z, BlockState etat, int partie) {
    }

    private static List<Bloc> blocs;

    private static void ajouter(List<Bloc> l, int x, int y, int z, BlockState s, int partie) {
        l.add(new Bloc(x, y, z, s, partie));
    }

    private static List<Bloc> blocs() {
        if (blocs != null) {
            return blocs;
        }
        List<Bloc> l = new ArrayList<>();
        BlockState blanc = Blocks.WHITE_CONCRETE.defaultBlockState();
        BlockState bleu = Blocks.BLUE_CONCRETE.defaultBlockState();
        BlockState gris = Blocks.LIGHT_GRAY_CONCRETE.defaultBlockState();
        BlockState noir = Blocks.BLACK_CONCRETE.defaultBlockState();
        BlockState vitre = Blocks.GLASS.defaultBlockState();
        BlockState jaune = Blocks.YELLOW_CONCRETE.defaultBlockState();
        // ---- le fuselage, de la cloison arrière (x = -17) au poste de pilotage (x = 15) : section octogonale
        for (int x = -17; x <= 15; x++) {
            boolean cabine = x >= -16 && x <= 15;
            for (int z = -2; z <= 2; z++) {
                ajouter(l, x, -1, z, gris, CORPS);
                // le plancher : moquette bleue dans l'allée
                ajouter(l, x, 0, z, z == 0 && x < 11 ? bleu : Blocks.GRAY_CONCRETE.defaultBlockState(), CORPS);
                // le plafond, ses plafonniers dans l'axe, et le dos arrondi par-dessus
                ajouter(l, x, 5, z, z == 0 && cabine && Math.floorMod(x, 3) == 0 ? Blocks.SEA_LANTERN.defaultBlockState() : blanc, CORPS);
            }
            for (int z = -1; z <= 1; z++) {
                ajouter(l, x, 6, z, blanc, CORPS);
            }
            for (int cote : new int[] {-3, 3}) {
                for (int y = 0; y <= 4; y++) {
                    BlockState s;
                    boolean porte = cote == -3 && (x == X_PORTE || x == X_PORTE + 1) && y >= 1 && y <= 3;
                    if (porte) {
                        ajouter(l, x, y, cote, Blocks.SMOOTH_QUARTZ.defaultBlockState(), PORTE);
                        continue;
                    }
                    if (y == 1) {
                        s = bleu;
                    } else if (y == 2 && Math.floorMod(x, 2) == 0 && x >= -15 && x <= 10) {
                        s = vitre;
                    } else if (y == 3 && x >= 13 && x <= 15) {
                        s = vitre;
                    } else if (y == 0 && x >= -6 && x <= 6) {
                        s = gris;
                    } else {
                        s = blanc;
                    }
                    ajouter(l, x, y, cote, s, CORPS);
                }
            }
            // les coffres à bagages, au-dessus des sièges
            if (x >= -15 && x <= 4) {
                for (int z : new int[] {-2, 2}) {
                    ajouter(l, x, 4, z, Blocks.SMOOTH_QUARTZ_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.TOP), CORPS);
                }
            }
        }
        // ---- le nez : le pare-brise du cockpit, puis le cône
        for (int z = -2; z <= 2; z++) {
            for (int y = -1; y <= 4; y++) {
                ajouter(l, 16, y, z, y == 3 ? vitre : blanc, CORPS);
            }
            ajouter(l, 16, 5, z, blanc, CORPS);
        }
        // le cône reste sous le pare-brise : les pilotes voient dehors
        for (int z = -1; z <= 1; z++) {
            for (int y = -1; y <= 2; y++) {
                ajouter(l, 17, y, z, blanc, CORPS);
            }
        }
        for (int y = 0; y <= 1; y++) {
            ajouter(l, 18, y, 0, blanc, CORPS);
        }
        ajouter(l, 19, 0, 0, gris, CORPS);
        // ---- le poste de pilotage : la cloison et sa porte, le tableau de bord, les deux sièges
        for (int z = -2; z <= 2; z++) {
            for (int y = 1; y <= 4; y++) {
                if (z != 0 || y > 2) {
                    ajouter(l, 11, y, z, blanc, CORPS);
                }
            }
            ajouter(l, 15, 1, z, noir, CORPS);
            ajouter(l, 15, 2, z, Blocks.DAYLIGHT_DETECTOR.defaultBlockState(), CORPS);
        }
        for (int z : new int[] {-1, 1}) {
            ajouter(l, X_PILOTES, 1, z, siege(Blocks.POLISHED_BLACKSTONE_STAIRS), CORPS);
        }
        // ---- l'office près de la porte, et l'arrière (les toilettes)
        ajouter(l, 6, 1, 2, Blocks.IRON_BLOCK.defaultBlockState(), CORPS);
        ajouter(l, 7, 1, 2, Blocks.IRON_BLOCK.defaultBlockState(), CORPS);
        ajouter(l, 6, 2, 2, Blocks.SMOOTH_QUARTZ_SLAB.defaultBlockState(), CORPS);
        for (int z = -2; z <= 2; z++) {
            for (int y = 1; y <= 4; y++) {
                if (Math.abs(z) == 2 || y > 2) {
                    ajouter(l, -16, y, z, blanc, CORPS);
                }
            }
        }
        // ---- les sièges des passagers
        for (int x : RANGEES) {
            for (int z : PLACES) {
                ajouter(l, x, 1, z, siege(Blocks.DARK_PRISMARINE_STAIRS), CORPS);
            }
        }
        // ---- la queue : le cône, la dérive bleue à bande jaune, les empennages
        int[][] cone = {{-18, 1, 5, 2}, {-19, 2, 5, 1}, {-20, 3, 5, 1}, {-21, 4, 5, 0}};
        for (int[] c : cone) {
            for (int y = c[1]; y <= c[2]; y++) {
                for (int z = -c[3]; z <= c[3]; z++) {
                    ajouter(l, c[0], y, z, y == c[1] ? gris : blanc, CORPS);
                }
            }
        }
        for (int x = -22; x <= -15; x++) {
            // la dérive repose sur le dos du fuselage (y = 6 jusqu'en x = -17), puis sur le cône
            int bas = x >= -17 || x == -22 ? 7 : 6;
            int haut = Math.min(13, 7 + (-15 - x));
            for (int y = bas; y <= haut; y++) {
                ajouter(l, x, y, 0, y == 9 ? jaune : bleu, CORPS);
            }
        }
        for (int z = 3; z <= 7; z++) {
            for (int x = -21; x <= -18 - (z - 2) / 2; x++) {
                ajouter(l, x, 5, z, blanc, CORPS);
                ajouter(l, x, 5, -z, blanc, CORPS);
            }
        }
        // ---- les ailes (basses, en flèche) et leurs saumons bleus
        for (int z = 4; z <= 16; z++) {
            int avant = (int) Math.round(4 - (z - 4) * 0.6);
            int arriere = (int) Math.round(-4 - (z - 4) * 0.3);
            for (int x = arriere; x <= avant; x++) {
                BlockState s = x == arriere ? gris : blanc;
                ajouter(l, x, 0, z, s, CORPS);
                ajouter(l, x, 0, -z, s, CORPS);
            }
            if (z == 16) {
                for (int x = arriere; x <= avant; x++) {
                    ajouter(l, x, 1, z, bleu, CORPS);
                    ajouter(l, x, 1, -z, bleu, CORPS);
                }
            }
        }
        // ---- les réacteurs, sous les ailes : nacelle grise, entrée d'air et tuyère noires
        for (int cote : new int[] {-1, 1}) {
            for (int x = -1; x <= 4; x++) {
                for (int y = -2; y <= -1; y++) {
                    for (int z = 6; z <= 7; z++) {
                        ajouter(l, x, y, cote * z, x == 4 || x == -1 ? noir : gris, CORPS);
                    }
                }
            }
        }
        // ---- le train d'atterrissage (rentré en vol)
        BlockState jambe = Blocks.IRON_BARS.defaultBlockState();
        BlockState roue = Blocks.BLACK_WOOL.defaultBlockState();
        ajouter(l, 13, -2, 0, jambe, TRAIN);
        ajouter(l, 13, -3, 0, roue, TRAIN);
        for (int z : new int[] {-2, 2}) {
            ajouter(l, -2, -2, z, jambe, TRAIN);
            ajouter(l, -2, -3, z, roue, TRAIN);
            ajouter(l, -3, -3, z, roue, TRAIN);
        }
        // ---- le feu, sur l'aile droite, après les phantoms
        for (int[] f : new int[][] {{3, 5}, {1, 7}, {0, 8}, {2, 8}, {-1, 9}, {0, 10}, {-2, 11}, {-3, 12}}) {
            ajouter(l, f[0], 1, f[1], Blocks.FIRE.defaultBlockState(), FEU);
        }
        for (int x = 0; x <= 3; x++) {
            ajouter(l, x, 0, 6, Blocks.MAGMA_BLOCK.defaultBlockState(), FEU);
        }
        blocs = l;
        return l;
    }

    /** Un siège tourné vers l'avant (le dossier du côté de l'arrière). */
    private static BlockState siege(Block escalier) {
        return escalier.defaultBlockState().setValue(StairBlock.FACING, Direction.WEST);
    }

    /**
     * Dessine l'avion dans le repère courant (déjà placé et tourné). lumiere : l'éclairage (plein jour, orage) ;
     * le train sorti ou rentré, la porte ouverte ou fermée, l'aile droite en feu ou non.
     */
    static void dessiner(PoseStack pose, MultiBufferSource buffers, int lumiere, boolean train, boolean porteOuverte, boolean feu) {
        BlockRenderDispatcher rendu = Minecraft.getInstance().getBlockRenderer();
        for (Bloc b : blocs()) {
            if (b.partie() == TRAIN && !train || b.partie() == PORTE && porteOuverte || b.partie() == FEU && !feu) {
                continue;
            }
            pose.pushPose();
            pose.translate(b.x() - 0.5, b.y(), b.z() - 0.5);
            // le magma recouvre le blanc de l'aile, à peine plus gros pour ne pas clignoter avec lui
            if (b.partie() == FEU && b.etat().is(Blocks.MAGMA_BLOCK)) {
                pose.translate(-0.002, -0.002, -0.002);
                pose.scale(1.004F, 1.004F, 1.004F);
            }
            rendu.renderSingleBlock(b.etat(), pose, buffers, lumiere, OverlayTexture.NO_OVERLAY);
            pose.popPose();
        }
        // la livrée : AIR BEAUVAIS sur les deux flancs
        Font font = Minecraft.getInstance().font;
        String nom = "AIR BEAUVAIS";
        float largeur = font.width(nom);
        for (int cote : new int[] {-1, 1}) {
            pose.pushPose();
            pose.translate(-4.5, 4.25, cote * 3.505);
            // le texte se lit de dehors : tourné d'un demi-tour sur le flanc gauche
            if (cote < 0) {
                pose.mulPose(Axis.YP.rotationDegrees(180));
            }
            float k = 0.085F;
            pose.scale(k, -k, k);
            font.drawInBatch(nom, -largeur / 2F, 0, 0x1C3C9A, false, pose.last().pose(), buffers, Font.DisplayMode.NORMAL, 0, lumiere);
            pose.popPose();
        }
    }

    private Avion3D() {
    }
}
