package fr.cabane.labo;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.network.Filterable;
import net.minecraft.util.RandomSource;
import net.minecraft.world.Clearable;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.item.component.DyedItemColor;
import net.minecraft.world.item.component.WrittenBookContent;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.BrewingStandBlock;
import net.minecraft.world.level.block.ChainBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.EndRodBlock;
import net.minecraft.world.level.block.LanternBlock;
import net.minecraft.world.level.block.LecternBlock;
import net.minecraft.world.level.block.LightBlock;
import net.minecraft.world.level.block.LightningRodBlock;
import net.minecraft.world.level.block.RedstoneLampBlock;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.entity.BrewingStandBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.phys.AABB;

/**
 * Le Laboratoire, posé au milieu du vide. Au centre, la salle d'expériences : 43 blocs de côté, 15 de haut, la table
 * d'opération sur son estrade sous un scialytique éblouissant, une perceuse qui tourne au-dessus, quatre bobines Tesla
 * et quatre cuves géantes (des mutants qui flottent). Quatre ailes autour : au nord les cuves et le Sujet Zéro (un
 * Warden en cage), à l'est les cellules (chair vivante, sculk, prisonniers), à l'ouest la morgue, au sud la salle de
 * contrôle et le bureau du docteur.
 *
 * <p>Coordonnées relatives au centre de la salle, y = 0 étant le sol (au niveau {@link #SOL} du monde).
 */
final class LaboBuilder {
    /** À augmenter quand on change le labo : il se reconstruit tout seul à la visite suivante. */
    static final int VERSION = 5;
    static final int SOL = 64;
    static final String TAG = "cabane_labo";
    /** Les corps couchés (voir Labo.onSommeil). */
    static final String CORPS = "labo.corps";
    /** Les écrans animés par LaboVie (plus un second tag : labo.ecran.table, .cuves, .alerte). */
    static final String ECRAN = "labo.ecran";

    /** La salle d'expériences : murs à ±HALL, plafond à HAUT. Les ailes : jusqu'à AILE, largeur ±LARGE, plafond à HAUT_AILE. */
    static final int HALL = 22;
    static final int HAUT = 16;
    static final int AILE = 46;
    static final int LARGE = 16;
    static final int HAUT_AILE = 11;

    /** La tête de la table d'expériences (la table est le long de x, la tête à l'est). */
    static final BlockPos TABLE = new BlockPos(1, 2, 0);
    /** Le haut des bobines Tesla, d'où partent les arcs. */
    static final int[][] BOBINES = {{-9, -9}, {9, -9}, {-9, 9}, {9, 9}};
    static final int HAUT_BOBINE = 8;
    /** Les lampes du plafond de la salle qui grésillent et s'éteignent par moments. */
    static final int[][] LAMPES = {{-11, 12, -11}, {11, 12, -11}, {-11, 12, 11}, {11, 12, 11}, {12, 12, 0}, {0, 12, -12},
            {28, 9, 0}, {40, 9, 0}, {-30, 9, 0}, {-40, 9, -6}, {0, 9, -26}, {0, 9, 28}};
    /** Les cuves où l'eau fait des bulles (centre x, z, hauteur du liquide). */
    static final int[][] BULLES = {{-15, -15, 11}, {15, 15, 11}, {-10, -27, 7}, {10, -27, 7}, {-10, -39, 7}};
    /** Les gouttes qui tombent du plafond des cellules et de la morgue. */
    static final int[][] GOUTTES = {{27, 10, 1}, {33, 10, -1}, {39, 10, 2}, {44, 10, -2}, {-28, 10, 4}, {-37, 10, -5}};
    /** Le Sujet Zéro (la cage du Warden, dans l'aile des cuves). */
    static final BlockPos ZERO = new BlockPos(0, 1, -38);
    /** La porte d'isolement, au bout du couloir des cellules : quelque chose cogne derrière. */
    static final BlockPos ISOLEMENT = new BlockPos(45, 1, 0);
    /** La salle des rituels, une grotte dans l'angle nord-est : le centre du sceau, et ses rayons (le sceau, le cercle de pierres). */
    static final int RX = 34;
    static final int RZ = -38;
    static final double RAYON_SCEAU = 8.5;
    /** Jusqu'où s'étend le labo (salle des rituels comprise), pour le nettoyer avant de le reconstruire. */
    static final int BORD = 56;

    private static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;
    private static final BlockState AIR = Blocks.AIR.defaultBlockState();

    private enum Liquide { EAU, SANG, VIDE, CASSEE }

    /** Les ailes : u s'éloigne du centre, v va de côté. */
    private enum Aile {
        NORD(Direction.NORTH), EST(Direction.EAST), SUD(Direction.SOUTH), OUEST(Direction.WEST);

        final Direction sortie;

        Aile(Direction sortie) {
            this.sortie = sortie;
        }

        int x(int u, int v) {
            return switch (this) {
                case NORD -> v;
                case SUD -> -v;
                case EST -> u;
                case OUEST -> -u;
            };
        }

        int z(int u, int v) {
            return switch (this) {
                case NORD -> -u;
                case SUD -> u;
                case EST -> v;
                case OUEST -> -v;
            };
        }

        Direction versHall() {
            return sortie.getOpposite();
        }

        /** Le sens des v croissants. */
        Direction cote() {
            return sortie.getClockWise();
        }
    }

    private final ServerLevel level;
    private final BlockPos c;
    private final RandomSource rnd;
    private final List<BlockPos> poses = new ArrayList<>();
    /** Le tag de cette construction-ci : les entités d'une construction plus ancienne sont refusées (voir Labo.onArrivee). */
    private final String construction;

    private LaboBuilder(ServerLevel level, int numero) {
        this.level = level;
        this.c = new BlockPos(0, SOL, 0);
        this.rnd = RandomSource.create(0x1ABL);
        this.construction = tagConstruction(numero);
    }

    static void construire(ServerLevel level, int numero) {
        new LaboBuilder(level, numero).tout();
    }

    static String tagConstruction(int numero) {
        return "labo.n" + numero;
    }

    static BlockPos abs(int x, int y, int z) {
        return new BlockPos(x, SOL + y, z);
    }

    private void tout() {
        for (int cx = -BORD >> 4; cx <= BORD >> 4; cx++) {
            for (int cz = -BORD >> 4; cz <= BORD >> 4; cz++) {
                level.getChunk(cx, cz);
            }
        }
        nettoyer();
        coque();
        hall();
        cuves();
        cellules();
        morgue();
        controle();
        rituels();
        for (int[] l : LAMPES) {
            lampe(l[0], l[1], l[2], Math.abs(l[0]) <= HALL && Math.abs(l[2]) <= HALL ? HAUT : HAUT_AILE);
        }
        finitions();
    }

    /** On repart de zéro : plus aucun bloc ni aucune entité du labo d'avant. */
    private void nettoyer() {
        AABB zone = new AABB(c).inflate(BORD, 0, BORD).expandTowards(0, HAUT + 6, 0).move(0, -4, 0);
        for (Entity e : level.getEntities((Entity) null, zone, e -> e.getTags().contains(TAG))) {
            e.discard();
        }
        for (int x = -BORD; x <= BORD; x++) {
            for (int z = -BORD; z <= BORD; z++) {
                for (int y = -3; y <= HAUT + 5; y++) {
                    BlockPos p = c.offset(x, y, z);
                    if (!level.getBlockState(p).isAir()) {
                        // on vide d'abord les contenants (perfusions, pupitre…) : sinon ils sèment leur contenu par terre
                        Clearable.tryClear(level.getBlockEntity(p));
                        level.setBlock(p, AIR, FLAGS);
                    }
                }
            }
        }
        for (Entity e : level.getEntities((Entity) null, zone, e -> e instanceof ItemEntity)) {
            e.discard();
        }
    }

    // ================================================================== la coque : sols, murs, plafonds

    private void coque() {
        for (int x = -HALL; x <= HALL; x++) {
            for (int z = -HALL; z <= HALL; z++) {
                pose(x, -1, z, Blocks.POLISHED_DEEPSLATE.defaultBlockState());
                pose(x, 0, z, solHall(x, z));
                pose(x, HAUT, z, x % 7 == 0 || z % 7 == 0 ? Blocks.POLISHED_DEEPSLATE.defaultBlockState() : Blocks.GRAY_CONCRETE.defaultBlockState());
                pose(x, HAUT + 1, z, Blocks.DEEPSLATE_TILES.defaultBlockState());
                boolean bordX = Math.abs(x) == HALL, bordZ = Math.abs(z) == HALL;
                if (bordX || bordZ) {
                    for (int y = 1; y < HAUT; y++) {
                        pose(x, y, z, murHall(bordX ? z : x, y, bordX && bordZ, bordX ? (x > 0 ? Aile.EST : Aile.OUEST) : (z > 0 ? Aile.SUD : Aile.NORD)));
                    }
                }
            }
        }
        for (Aile a : Aile.values()) {
            for (int u = HALL + 1; u <= AILE; u++) {
                for (int v = -LARGE; v <= LARGE; v++) {
                    pa(a, u, -1, v, Blocks.POLISHED_DEEPSLATE.defaultBlockState());
                    pa(a, u, 0, v, solAile(a, u, v));
                    pa(a, u, HAUT_AILE, v, plafondAile(a, u, v));
                    pa(a, u, HAUT_AILE + 1, v, Blocks.DEEPSLATE_TILES.defaultBlockState());
                    if (u == AILE || Math.abs(v) == LARGE) {
                        for (int y = 1; y < HAUT_AILE; y++) {
                            boolean vitre = Math.abs(v) == LARGE && u >= 31 && u <= 37 && y >= 3 && y <= 6 && a != Aile.EST;
                            pa(a, u, y, v, vitre ? deco("quark:dirty_glass", Blocks.GRAY_STAINED_GLASS) : murAile(a, u, y, v));
                        }
                    }
                }
            }
        }
    }

    private BlockState solHall(int x, int z) {
        float r = rnd.nextFloat();
        if (r < 0.06F) {
            return Blocks.WHITE_TERRACOTTA.defaultBlockState();
        }
        if (r < 0.08F) {
            return Blocks.TUFF.defaultBlockState();
        }
        return ((Math.floorDiv(x, 2) + Math.floorDiv(z, 2)) & 1) == 0 ? Blocks.WHITE_CONCRETE.defaultBlockState() : Blocks.LIGHT_GRAY_CONCRETE.defaultBlockState();
    }

    /** Un bloc du mur de la salle ; {@code v} court le long du mur, {@code derriere} est l'aile de l'autre côté. */
    private BlockState murHall(int v, int y, boolean coin, Aile derriere) {
        int av = Math.abs(v);
        if (coin || (av % 6 == 0 && av != 0 && av <= 18)) {
            return Blocks.POLISHED_DEEPSLATE.defaultBlockState();
        }
        // les grandes portes vers les ailes, leur cadre « danger » et la herse restée à moitié levée
        if (av <= 2 && y <= 4) {
            return AIR;
        }
        if (av <= 2 && y == 5) {
            return Blocks.IRON_BARS.defaultBlockState();
        }
        if ((av == 3 && y <= 6) || (av <= 3 && y == 6)) {
            return (((v + y) & 1) == 0 ? Blocks.YELLOW_CONCRETE : Blocks.BLACK_CONCRETE).defaultBlockState();
        }
        // les vitres sur le vide, dans les angles
        if (av >= 18 && av <= 20 && y >= 8 && y <= 13) {
            return deco("quark:dirty_glass", Blocks.GRAY_STAINED_GLASS);
        }
        // les vitres d'observation sur les ailes (la salle de contrôle regarde à hauteur d'homme)
        boolean controle = derriere == Aile.SUD;
        if (av >= 7 && av <= 12 && (controle ? y >= 2 && y <= 4 : y >= 7 && y <= 9)) {
            return deco("quark:dirty_glass_pane", Blocks.GLASS_PANE);
        }
        if (y == 1) {
            return Blocks.POLISHED_DEEPSLATE.defaultBlockState();
        }
        float r = rnd.nextFloat();
        if (y <= 5) {
            return r < 0.1F ? Blocks.CALCITE.defaultBlockState() : r < 0.14F ? Blocks.WHITE_TERRACOTTA.defaultBlockState() : Blocks.QUARTZ_BRICKS.defaultBlockState();
        }
        if (y == 6) {
            return Blocks.LIGHT_GRAY_CONCRETE.defaultBlockState();
        }
        return r < 0.06F ? Blocks.LIGHT_GRAY_CONCRETE.defaultBlockState() : Blocks.WHITE_CONCRETE.defaultBlockState();
    }

    private BlockState solAile(Aile a, int u, int v) {
        float r = rnd.nextFloat();
        return switch (a) {
            case NORD -> r < 0.25F ? deco("quark:rusty_iron_plate", Blocks.ANDESITE) : deco("quark:iron_plate", Blocks.POLISHED_ANDESITE);
            case EST -> Math.abs(v) <= 2 ? Blocks.POLISHED_DEEPSLATE.defaultBlockState()
                    : r < 0.5F ? Blocks.COBBLED_DEEPSLATE.defaultBlockState() : r < 0.8F ? Blocks.DEEPSLATE_TILES.defaultBlockState()
                    : r < 0.95F ? Blocks.CRACKED_DEEPSLATE_TILES.defaultBlockState() : Blocks.MOSSY_COBBLESTONE.defaultBlockState();
            case OUEST -> r < 0.05F ? Blocks.PACKED_ICE.defaultBlockState()
                    : ((Math.floorDiv(u, 2) + Math.floorDiv(v, 2)) & 1) == 0 ? Blocks.CALCITE.defaultBlockState() : Blocks.POLISHED_DIORITE.defaultBlockState();
            case SUD -> u >= 34 ? Blocks.DARK_OAK_PLANKS.defaultBlockState()
                    : (u % 4 == 0 || v % 4 == 0) ? Blocks.BLACK_CONCRETE.defaultBlockState() : Blocks.GRAY_CONCRETE.defaultBlockState();
        };
    }

    private BlockState murAile(Aile a, int u, int y, int v) {
        float r = rnd.nextFloat();
        if (y == 1 && a != Aile.SUD) {
            return Blocks.POLISHED_DEEPSLATE.defaultBlockState();
        }
        return switch (a) {
            case NORD -> y == 5 ? deco("quark:iron_plate", Blocks.POLISHED_ANDESITE) : r < 0.08F ? Blocks.ANDESITE.defaultBlockState() : Blocks.SMOOTH_STONE.defaultBlockState();
            case EST -> briquesSombres(r);
            case OUEST -> y == 4 ? Blocks.CYAN_TERRACOTTA.defaultBlockState() : r < 0.07F ? Blocks.LIGHT_GRAY_CONCRETE.defaultBlockState() : Blocks.WHITE_CONCRETE.defaultBlockState();
            case SUD -> u >= 34 ? (y <= 3 ? Blocks.DARK_OAK_PLANKS.defaultBlockState() : Blocks.BROWN_TERRACOTTA.defaultBlockState())
                    : y == 1 ? Blocks.BLACK_CONCRETE.defaultBlockState() : Blocks.GRAY_CONCRETE.defaultBlockState();
        };
    }

    private static BlockState briquesSombres(float r) {
        return (r < 0.25F ? Blocks.CRACKED_DEEPSLATE_BRICKS : r < 0.3F ? Blocks.DEEPSLATE_TILES : Blocks.DEEPSLATE_BRICKS).defaultBlockState();
    }

    private BlockState plafondAile(Aile a, int u, int v) {
        return switch (a) {
            case NORD -> u % 6 == 0 ? Blocks.POLISHED_DEEPSLATE.defaultBlockState() : Blocks.GRAY_CONCRETE.defaultBlockState();
            case EST -> Blocks.DEEPSLATE_TILES.defaultBlockState();
            case OUEST -> Blocks.WHITE_CONCRETE.defaultBlockState();
            case SUD -> u >= 34 ? Blocks.DARK_OAK_PLANKS.defaultBlockState() : Blocks.GRAY_CONCRETE.defaultBlockState();
        };
    }

    // ================================================================== la salle d'expériences

    private void hall() {
        estrade();
        scialytique();
        for (int[] b : BOBINES) {
            bobine(b[0], b[1]);
        }
        cuve(-15, -15, 2, 11, Liquide.EAU, Blocks.GLASS, "mutantmonsters:mutant_zombie", "SUJET 03", "« Goliath »", Direction.SOUTH);
        cuve(15, -15, 2, 11, Liquide.VIDE, Blocks.PURPLE_STAINED_GLASS, "mutantmonsters:mutant_enderman", "SUJET 05", "ne pas regarder dans les yeux", Direction.SOUTH);
        cuve(-15, 15, 2, 11, Liquide.SANG, Blocks.GLASS, "mutantmonsters:spider_pig", "SUJET 08", "hybride porc / araignée", Direction.NORTH);
        cuve(15, 15, 2, 11, Liquide.EAU, Blocks.GLASS, "mutantmonsters:mutant_creeper", "SUJET 02", "instable — ne pas secouer", Direction.NORTH);

        // le plafond : lampes au bout de chaînes, bouches d'aération, toiles d'araignée dans les coins
        for (int[] l : new int[][] {{-12, 0}, {0, 12}, {-4, -12}, {6, 12}}) {
            lampe(l[0], 12, l[1], HAUT);
        }
        for (int x = -18; x <= 18; x += 9) {
            for (int z = -18; z <= 18; z += 12) {
                if (Math.abs(x) > 4 || Math.abs(z) > 4) {
                    pose(x, HAUT - 1, z, Blocks.IRON_TRAPDOOR.defaultBlockState().setValue(TrapDoorBlock.HALF, Half.TOP));
                }
            }
        }
        for (int sx : new int[] {-1, 1}) {
            for (int sz : new int[] {-1, 1}) {
                for (int k = 0; k < 9; k++) {
                    int x = sx * (21 - rnd.nextInt(3)), z = sz * (21 - rnd.nextInt(3)), y = HAUT - 1 - rnd.nextInt(3);
                    if (level.getBlockState(c.offset(x, y, z)).isAir()) {
                        pose(x, y, z, Blocks.COBWEB.defaultBlockState());
                    }
                }
            }
        }
        // les appliques à flamme verte sur les murs
        for (Aile a : Aile.values()) {
            for (int v : new int[] {-8, 8}) {
                pa(a, HALL - 1, 4, v, mural("supplementaries:sconce_wall_green", Blocks.SOUL_WALL_TORCH, a.versHall()));
            }
        }
        // la zone d'arrivée (on y atterrit) et le bouton rouge du retour
        for (int x = -2; x <= 2; x++) {
            for (int z = 15; z <= 19; z++) {
                int d = x * x + (z - 17) * (z - 17);
                if (d <= 2) {
                    pose(x, 0, z, Blocks.CRYING_OBSIDIAN.defaultBlockState());
                } else if (d <= 5) {
                    pose(x, 0, z, Blocks.REINFORCED_DEEPSLATE.defaultBlockState());
                }
            }
        }
        pose(4, 1, 17, Labo.BOUTON_RETOUR.get().defaultBlockState());
        // de l'autre côté, un terminal comme celui qui t'a amené ici… mais celui-là ne répond plus
        pose(-4, 1, 17, Labo.TERMINAL.get().defaultBlockState().setValue(LaboBlocs.Terminal.FACING, Direction.NORTH));
        texte(4.5, 2.55, 17.5, 0F, "[{\"text\":\"RETOUR\\n\",\"color\":\"green\",\"bold\":true},{\"text\":\"là où tu étais\",\"color\":\"gray\",\"bold\":false}]",
                0.5F, 0x00000000, "vertical");
        texte(0.5, 1.02, 20.3, 0F, "{\"text\":\"ZONE D'ARRIVÉE\",\"color\":\"dark_purple\"}", 1.1F, 0, "fixed", -90F);
        // l'horloge du mur sud : ici, elle tourne n'importe comment
        cadre(0, 7, 21, Direction.NORTH, new ItemStack(Items.CLOCK), false);
        cadre(-1, 7, 21, Direction.NORTH, new ItemStack(Items.COMPASS), false);
        // les tags sur les murs
        tag(Aile.NORD, -5, "IL NOUS ENTEND", 3.2);
        tag(Aile.NORD, 5, "IIII IIII IIII IIII II", 2.6);
        tag(Aile.EST, -5, "NE TE COUCHE PAS\\nSUR LA TABLE", 3.0);
        tag(Aile.EST, 5, "LE 12 EST ENCORE ICI", 3.4);
        tag(Aile.OUEST, 5, "ON NOUS A MENTI", 3.0);
        tag(Aile.OUEST, -5, "AIDEZ-NOUS", 2.4);
        tag(Aile.SUD, -5, "PERSONNE NE SORT", 3.4);
        // le sang : la traînée d'un corps qu'on a tiré jusqu'à la morgue, et des éclaboussures sous la table
        for (int x = -6; x >= -HALL - 6; x--) {
            int z = (int) Math.round(Math.sin(x * 0.7) * 0.8);
            if (rnd.nextFloat() < 0.75F) {
                sang(x, 1, z);
            }
            if (rnd.nextFloat() < 0.3F) {
                sang(x, 1, z + (rnd.nextBoolean() ? 1 : -1));
            }
        }
        // les traces de pas du Sujet 12, des cuves jusqu'aux cellules
        for (int k = 0; k < 21; k++) {
            int z = -HALL + 1 + k;
            int x = k + (k % 2);
            if (k % 2 == 0) {
                sang(x, 1, z);
            }
        }
        pose(-8, 1, 1, Blocks.IRON_TRAPDOOR.defaultBlockState());
        pose(7, 1, -1, Blocks.IRON_TRAPDOOR.defaultBlockState());
        pose(-14, 1, 4, Blocks.IRON_TRAPDOOR.defaultBlockState());
        // des os dans un coin, une chaise renversée, des papiers par terre
        pose(18, 1, -6, Blocks.BONE_BLOCK.defaultBlockState().setValue(RotatedPillarBlock.AXIS, Direction.Axis.X));
        pose(19, 1, -5, Blocks.BONE_BLOCK.defaultBlockState().setValue(RotatedPillarBlock.AXIS, Direction.Axis.Z));
        pose(18, 2, -6, Blocks.SKELETON_SKULL.defaultBlockState().setValue(BlockStateProperties.ROTATION_16, 5));
        pose(17, 1, -4, Blocks.SKELETON_SKULL.defaultBlockState().setValue(BlockStateProperties.ROTATION_16, 11));
        pose(-17, 1, -6, vers(deco("handcrafted:dark_oak_chair", escalier(Blocks.DARK_OAK_STAIRS, Direction.EAST)), Direction.UP));
        for (int[] p : new int[][] {{-9, 7}, {-10, 8}, {6, 8}, {12, -5}, {-5, -9}}) {
            cadre(p[0], 1, p[1], Direction.UP, new ItemStack(Items.PAPER), true);
        }
        // les pendus : la tête en bas, au bout d'une chaîne
        pendu(-7, 10, 8.5, "minecraft:zombie_villager");
        pendu(8, -12, 8.5, "minecraft:villager");
    }

    /** L'estrade ronde, la table au milieu, les plateaux d'instruments, les perfusions, le moniteur. */
    private void estrade() {
        for (int x = -9; x <= 9; x++) {
            for (int z = -9; z <= 9; z++) {
                double d = Math.sqrt(x * x + z * z);
                if (d <= 8.5 && d > 7.5) {
                    double angle = Math.atan2(z, x);
                    pose(x, 0, z, ((int) Math.floor(angle * 8 / Math.PI + 8) & 1) == 0 ? Blocks.YELLOW_CONCRETE.defaultBlockState() : Blocks.BLACK_CONCRETE.defaultBlockState());
                } else if (d <= 7.5) {
                    pose(x, 0, z, Blocks.SMOOTH_QUARTZ.defaultBlockState());
                }
                if (d <= 4.5) {
                    pose(x, 1, z, Blocks.SMOOTH_QUARTZ.defaultBlockState());
                } else if (d <= 5.6) {
                    pose(x, 1, z, Blocks.SMOOTH_QUARTZ_SLAB.defaultBlockState());
                }
            }
        }
        // la table : le long de x, la tête à l'est
        BlockState table = Labo.TABLE.get().defaultBlockState().setValue(LaboBlocs.TableExperiences.FACING, Direction.EAST);
        pose(TABLE.getX() - 1, TABLE.getY(), TABLE.getZ(), table.setValue(LaboBlocs.TableExperiences.PART, BedPart.FOOT));
        pose(TABLE.getX(), TABLE.getY(), TABLE.getZ(), table.setValue(LaboBlocs.TableExperiences.PART, BedPart.HEAD));
        // les plateaux d'instruments
        for (int x = 0; x <= 1; x++) {
            pose(x, 2, -2, deco("quark:iron_plate", Blocks.IRON_BLOCK));
        }
        cadre(0, 3, -2, Direction.UP, new ItemStack(Items.SHEARS), true);
        cadre(1, 3, -2, Direction.UP, new ItemStack(Items.IRON_SWORD), true);
        // les perfusions : un pied, et des poches de sang (des fioles rouges)
        perfusion(-1, 1, Potions.HEALING);
        perfusion(2, 1, Potions.POISON);
        // les quatre pieds de lampe autour de la table
        for (int[] p : new int[][] {{-2, -3}, {3, -3}, {-2, 3}, {3, 3}}) {
            for (int y = 2; y <= 4; y++) {
                pose(p[0], y, p[1], Blocks.IRON_BARS.defaultBlockState());
            }
            pose(p[0], 5, p[1], Blocks.PEARLESCENT_FROGLIGHT.defaultBlockState());
        }
        // le moniteur cardiaque, tourné vers l'arrivée
        pose(3, 2, -1, Blocks.POLISHED_BLACKSTONE_WALL.defaultBlockState());
        pose(3, 3, -1, Blocks.POLISHED_BLACKSTONE.defaultBlockState());
        ecran(3.5, 3.05, 0.04, 0F, "table", 0.4F);
        // les deux électrodes pointées sur le sujet
        pose(-1, 3, 0, Blocks.LIGHTNING_ROD.defaultBlockState().setValue(LightningRodBlock.FACING, Direction.EAST));
        pose(2, 3, 0, Blocks.LIGHTNING_ROD.defaultBlockState().setValue(LightningRodBlock.FACING, Direction.WEST));
        // des éclaboussures sur l'estrade
        for (int k = 0; k < 14; k++) {
            int x = rnd.nextInt(7) - 3, z = rnd.nextInt(7) - 3;
            if (level.getBlockState(c.offset(x, 2, z)).isAir() && x * x + z * z <= 16) {
                sang(x, 2, z);
            }
        }
        // de la lumière, beaucoup de lumière, sur la table
        for (int[] p : new int[][] {{0, 4, 1}, {1, 4, -1}, {-1, 4, -1}, {2, 4, -1}, {0, 5, -1}, {1, 5, 1}}) {
            if (level.getBlockState(c.offset(p[0], p[1], p[2])).isAir()) {
                pose(p[0], p[1], p[2], Blocks.LIGHT.defaultBlockState().setValue(LightBlock.LEVEL, 15));
            }
        }
    }

    /** Le scialytique au-dessus de la table : un anneau de lumières, et au milieu une perceuse qui tourne (Create). */
    private void scialytique() {
        for (int x = -2; x <= 4; x++) {
            for (int z = -3; z <= 3; z++) {
                boolean anneau1 = x == -2 || x == 4 || Math.abs(z) == 3;
                boolean anneau2 = !anneau1 && (x == -1 || x == 3 || Math.abs(z) == 2);
                if (anneau1) {
                    pose(x, 11, z, Blocks.POLISHED_DEEPSLATE.defaultBlockState());
                } else if (anneau2) {
                    pose(x, 10, z, Blocks.SEA_LANTERN.defaultBlockState());
                    pose(x, 11, z, deco("quark:iron_plate", Blocks.IRON_BLOCK));
                    if (((x + z) & 1) == 0) {
                        pose(x, 9, z, Blocks.END_ROD.defaultBlockState().setValue(EndRodBlock.FACING, Direction.DOWN));
                    }
                }
            }
        }
        for (int[] p : new int[][] {{-2, -3}, {4, -3}, {-2, 3}, {4, 3}}) {
            for (int y = 12; y < HAUT; y++) {
                pose(p[0], y, p[1], Blocks.CHAIN.defaultBlockState());
            }
        }
        BlockState moteur = modOuNull("create:creative_motor");
        if (moteur != null) {
            pose(1, 11, 0, vers(moteur, Direction.DOWN));
            for (int y = 8; y <= 10; y++) {
                pose(1, y, 0, avec(deco("create:shaft", Blocks.CHAIN.defaultBlockState()), "axis", "y"));
            }
            pose(1, 7, 0, vers(deco("create:mechanical_drill", Blocks.LIGHTNING_ROD.defaultBlockState()), Direction.DOWN));
        } else {
            for (int y = 7; y <= 11; y++) {
                pose(1, y, 0, Blocks.CHAIN.defaultBlockState());
            }
        }
    }

    /** Une bobine Tesla : socle, enroulement de cuivre, couronne de paratonnerres et ampoule. */
    private void bobine(int x, int z) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                pose(x + dx, 1, z + dz, Blocks.POLISHED_BLACKSTONE.defaultBlockState());
            }
        }
        pose(x, 2, z, Blocks.WAXED_COPPER_BLOCK.defaultBlockState());
        for (int y = 3; y <= 6; y++) {
            pose(x, y, z, Blocks.WAXED_COPPER_GRATE.defaultBlockState());
        }
        pose(x, 7, z, Blocks.WAXED_COPPER_BULB.defaultBlockState().setValue(BlockStateProperties.LIT, true));
        for (Direction d : Direction.Plane.HORIZONTAL) {
            pose(x + d.getStepX(), 7, z + d.getStepZ(), Blocks.LIGHTNING_ROD.defaultBlockState().setValue(LightningRodBlock.FACING, d));
        }
        pose(x, HAUT_BOBINE, z, Blocks.LIGHTNING_ROD.defaultBlockState());
        // le câble jusqu'à l'estrade
        int cx = x - Integer.signum(x) * 2, cz = z - Integer.signum(z) * 2;
        boolean tour = false;
        while (cx * cx + cz * cz > 36) {
            pose(cx, 1, cz, Blocks.BLACK_CARPET.defaultBlockState());
            if (tour) {
                cx -= Integer.signum(cx);
            } else {
                cz -= Integer.signum(cz);
            }
            tour = !tour;
        }
    }

    /**
     * Une cuve ronde : socle lumineux, paroi de verre, liquide, couvercle et tuyau jusqu'au plafond, un sujet qui flotte
     * dedans et son étiquette. {@code rayon} 1 : 3 blocs de large dedans ; 2 : 5 blocs.
     */
    private void cuve(int x, int z, int rayon, int haut, Liquide liquide, Block verre, String mob, String nom, String legende, Direction regard) {
        double dedans = (rayon + 0.5) * (rayon + 0.5), bord = (rayon + 1.5) * (rayon + 1.5);
        int r = rayon + 1;
        BlockState sang = deco("biomesoplenty:blood", Blocks.WATER);
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                int d = dx * dx + dz * dz;
                if (d > bord) {
                    continue;
                }
                boolean interieur = d <= dedans;
                pose(x + dx, 1, z + dz, interieur ? Blocks.SEA_LANTERN.defaultBlockState() : Blocks.POLISHED_DEEPSLATE.defaultBlockState());
                for (int y = 2; y <= haut; y++) {
                    BlockState s;
                    if (!interieur) {
                        boolean brise = liquide == Liquide.CASSEE && dz * Integer.signum(regard.getStepZ()) + dx * Integer.signum(regard.getStepX()) > 0
                                && y <= 2 + rnd.nextInt(haut - 1);
                        s = brise ? AIR : verre.defaultBlockState();
                    } else {
                        s = switch (liquide) {
                            case EAU -> Blocks.WATER.defaultBlockState();
                            case SANG -> sang;
                            case VIDE, CASSEE -> AIR;
                        };
                    }
                    pose(x + dx, y, z + dz, s);
                }
                pose(x + dx, haut + 1, z + dz, interieur ? deco("quark:iron_plate", Blocks.IRON_BLOCK) : Blocks.POLISHED_DEEPSLATE.defaultBlockState());
            }
        }
        int plafond = Math.abs(x) <= HALL && Math.abs(z) <= HALL ? HAUT : HAUT_AILE;
        for (int y = haut + 2; y < plafond; y++) {
            pose(x, y, z, avec(deco("create:fluid_pipe", Blocks.IRON_BARS.defaultBlockState()), "axis", "y"));
        }
        if (liquide == Liquide.CASSEE) {
            // la vitre a éclaté vers l'extérieur : du verre et du sang par terre
            for (int k = 0; k < 10; k++) {
                int px = x + regard.getStepX() * (r + 1 + rnd.nextInt(3)) + rnd.nextInt(5) - 2;
                int pz = z + regard.getStepZ() * (r + 1 + rnd.nextInt(3)) + rnd.nextInt(5) - 2;
                if (level.getBlockState(c.offset(px, 1, pz)).isAir()) {
                    if (rnd.nextBoolean()) {
                        sang(px, 1, pz);
                    } else {
                        pose(px, 1, pz, deco("quark:dirty_glass_pane", Blocks.GLASS_PANE));
                    }
                }
            }
        } else if (!mob.isEmpty()) {
            figurant(mob, x + 0.5, liquide == Liquide.VIDE ? 2 : 2.6 + (haut - 2) * 0.25, z + 0.5, regard.toYRot(), true, liquide != Liquide.VIDE, null);
        }
        texte(x + 0.5, haut + 1.6, z + 0.5, 0F, "[{\"text\":\"" + nom + "\\n\",\"color\":\"red\",\"bold\":true},{\"text\":\"" + legende
                + "\",\"color\":\"gray\",\"bold\":false}]", 0.55F, 0x55000000, "vertical");
    }

    /** Une lampe qui pend au bout d'une chaîne, allumée. */
    private void lampe(int x, int y, int z, int plafond) {
        for (int k = y + 1; k < plafond; k++) {
            pose(x, k, z, Blocks.CHAIN.defaultBlockState());
        }
        pose(x, y, z, Blocks.REDSTONE_LAMP.defaultBlockState().setValue(RedstoneLampBlock.LIT, true));
    }

    private void perfusion(int x, int z, net.minecraft.core.Holder<net.minecraft.world.item.alchemy.Potion> potion) {
        pose(x, 2, z, Blocks.IRON_BARS.defaultBlockState());
        pose(x, 3, z, Blocks.IRON_BARS.defaultBlockState());
        BlockState stand = Blocks.BREWING_STAND.defaultBlockState();
        for (var bouteille : BrewingStandBlock.HAS_BOTTLE) {
            stand = stand.setValue(bouteille, true);
        }
        pose(x, 4, z, stand);
        if (level.getBlockEntity(c.offset(x, 4, z)) instanceof BrewingStandBlockEntity be) {
            for (int i = 0; i < 3; i++) {
                be.setItem(i, PotionContents.createItemStack(Items.POTION, potion));
            }
        }
    }

    /** Un graffiti rouge sur un mur de la salle (côté intérieur), à {@code v} le long du mur. */
    private void tag(Aile a, int v, String mots, double y) {
        murTexte(a, HALL - 1, v, y, a.sortie, "{\"text\":\"" + mots + "\",\"color\":\"dark_red\"}", 1.5F);
    }

    /** Un corps pendu par les pieds (le nom « Dinnerbone » le retourne), au bout d'une chaîne. */
    private void pendu(int x, int z, double y, String id) {
        for (int k = (int) Math.ceil(y + 2); k < HAUT; k++) {
            pose(x, k, z, Blocks.CHAIN.defaultBlockState());
        }
        figurant(id, x + 0.5, y, z + 0.5, rnd.nextFloat() * 360F, true, true, e -> e.setCustomName(Component.literal("Dinnerbone")));
    }

    // ================================================================== l'aile nord : les cuves et le Sujet Zéro

    private void cuves() {
        Aile a = Aile.NORD;
        cuveAile(a, 27, -10, Liquide.EAU, "minecraft:zombie_villager", "SUJET 01", "le premier");
        cuveAile(a, 27, 10, Liquide.EAU, "alexsmobs:farseer", "SUJET 04", "venu d'ailleurs");
        cuveAile(a, 33, -10, Liquide.SANG, "alexsmobs:murmur", "SUJET 06", "il chuchote la nuit");
        cuveAile(a, 33, 10, Liquide.CASSEE, "", "SUJET 12", "ÉVADÉ");
        cuveAile(a, 39, -10, Liquide.EAU, "alexsmobs:enderiophage", "SUJET 09", "souche virale");
        cuveAile(a, 39, 10, Liquide.SANG, "alexsmobs:underminer", "SUJET 11", "ne dort jamais");
        // les tuyaux au plafond qui relient les cuves
        for (int v : new int[] {-10, 10}) {
            for (int u = HALL + 1; u <= 44; u++) {
                pa(a, u, HAUT_AILE - 1, v, avec(deco("create:fluid_pipe", Blocks.IRON_BARS.defaultBlockState()), "axis", "z"));
            }
        }
        // le Sujet Zéro : un Warden dans une cage de verre et de deepslate renforcée, le sculk tout autour
        int u0 = -ZERO.getZ();
        for (int du = -4; du <= 4; du++) {
            for (int dv = -4; dv <= 4; dv++) {
                int d = du * du + dv * dv;
                if (d <= 6) {
                    pa(a, u0 + du, 0, dv, Blocks.SCULK.defaultBlockState());
                    pa(a, u0 + du, 7, dv, Blocks.REINFORCED_DEEPSLATE.defaultBlockState());
                } else if (d <= 12) {
                    // des piliers en diagonale : de face, on voit le Sujet Zéro à travers la vitre
                    boolean pilier = du != 0 && dv != 0 && Math.abs(Math.abs(du) - Math.abs(dv)) <= 1;
                    for (int y = 1; y <= 6; y++) {
                        pa(a, u0 + du, y, dv, pilier ? Blocks.REINFORCED_DEEPSLATE.defaultBlockState() : Blocks.GLASS.defaultBlockState());
                    }
                    pa(a, u0 + du, 7, dv, Blocks.REINFORCED_DEEPSLATE.defaultBlockState());
                }
            }
        }
        for (int y = 8; y < HAUT_AILE; y++) {
            pa(a, u0 - 2, y, -2, Blocks.CHAIN.defaultBlockState());
            pa(a, u0 + 2, y, 2, Blocks.CHAIN.defaultBlockState());
            pa(a, u0 - 2, y, 2, Blocks.CHAIN.defaultBlockState());
            pa(a, u0 + 2, y, -2, Blocks.CHAIN.defaultBlockState());
        }
        figurant("minecraft:warden", ZERO.getX() + 0.5, 1, ZERO.getZ() + 0.5, a.versHall().toYRot(), false, false, null);
        texte(ZERO.getX() + 0.5, 8.6, ZERO.getZ() + 0.5, 0F,
                "[{\"text\":\"SUJET ZÉRO\\n\",\"color\":\"dark_red\",\"bold\":true},{\"text\":\"NE PAS RÉVEILLER\",\"color\":\"gray\",\"bold\":false}]",
                0.8F, 0x66000000, "vertical");
        for (int k = 0; k < 70; k++) {
            int du = rnd.nextInt(17) - 8, dv = rnd.nextInt(17) - 8;
            int d = du * du + dv * dv;
            if (d > 14 && d < 64 && Math.abs(dv) < 9) {
                pa(a, u0 + du, 0, dv, Blocks.SCULK.defaultBlockState());
            }
        }
        // les capteurs et le hurleur : ils réagissent aux pas (sans jamais appeler de Warden)
        pa(a, u0 + 5, 1, -3, Blocks.SCULK_SENSOR.defaultBlockState());
        pa(a, u0 - 5, 1, 4, Blocks.SCULK_SENSOR.defaultBlockState());
        pa(a, u0 - 5, 1, -1, Blocks.SCULK_SHRIEKER.defaultBlockState());
        pa(a, u0 + 6, 1, 4, Blocks.SCULK_CATALYST.defaultBlockState());
        // au fond : deux volants qui tournent (Create), des réservoirs, et des étagères de bocaux à l'entrée
        BlockState moteur = modOuNull("create:creative_motor");
        for (int v : new int[] {-6, 6}) {
            if (moteur != null) {
                pa(a, AILE - 1, 5, v, vers(moteur, a.versHall()));
                pa(a, AILE - 2, 5, v, avec(deco("create:flywheel", AIR), "axis", "z"));
            }
            for (int y = 1; y <= 4; y++) {
                for (int du = 0; du <= 1; du++) {
                    for (int dv = 0; dv <= 1; dv++) {
                        pa(a, AILE - 2 - du, y, (v < 0 ? -14 : 13) + dv, deco("create:fluid_tank", Blocks.TINTED_GLASS.defaultBlockState()));
                    }
                }
            }
        }
        etageres(a);
        // une lampe qui grésille au milieu de l'allée, des lumières vertes au plafond
        for (int u = 30; u <= 44; u += 7) {
            pa(a, u, HAUT_AILE, 0, Blocks.VERDANT_FROGLIGHT.defaultBlockState());
        }
        porte(a, "[{\"text\":\"SALLE DES CUVES\\n\",\"color\":\"gold\",\"bold\":true},{\"text\":\"niveau de confinement 4\",\"color\":\"gray\",\"bold\":false}]");
    }

    private void cuveAile(Aile a, int u, int v, Liquide liquide, String mob, String nom, String legende) {
        Direction regard = v < 0 ? a.cote() : a.cote().getOpposite();
        cuve(a.x(u, v), a.z(u, v), 1, 7, liquide, Blocks.GLASS, mob, nom, legende, regard);
    }

    /** Les bocaux de l'entrée des cuves : des yeux, de la chair, des choses dans du formol. */
    private void etageres(Aile a) {
        ItemStack[] bocaux = {new ItemStack(Items.SPIDER_EYE), new ItemStack(Items.ROTTEN_FLESH), new ItemStack(Items.SLIME_BALL),
                new ItemStack(Items.FERMENTED_SPIDER_EYE), new ItemStack(Items.PUFFERFISH), new ItemStack(Items.ENDER_EYE),
                new ItemStack(Items.NETHER_WART), new ItemStack(Items.HEART_OF_THE_SEA), new ItemStack(Items.CHORUS_FRUIT), new ItemStack(Items.BONE)};
        BlockState bocal = deco("supplementaries:jar", Blocks.GLASS.defaultBlockState());
        int k = 0;
        for (int v = -14; v <= 14; v++) {
            if (Math.abs(v) <= 4) {
                continue;
            }
            for (int y : new int[] {2, 4}) {
                pa(a, HALL + 1, y, v, Blocks.SPRUCE_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.TOP));
                if ((v + y) % 3 != 0) {
                    pa(a, HALL + 1, y + 1, v, bocal);
                    objetFlottant(bocaux[k++ % bocaux.length], a.x(HALL + 1, v) + 0.5, y + 1.32, a.z(HALL + 1, v) + 0.5, 0.32F, rnd.nextFloat() * 360F);
                }
            }
        }
    }

    // ================================================================== l'aile est : les cellules

    private void cellules() {
        Aile a = Aile.EST;
        int[] debuts = {23, 29, 35, 41};
        for (int u = HALL + 1; u <= AILE - 1; u++) {
            boolean cloison = u == 28 || u == 34 || u == 40;
            for (int s : new int[] {-1, 1}) {
                // la façade de barreaux
                for (int y = 1; y < HAUT_AILE; y++) {
                    pa(a, u, y, 3 * s, cloison || y > 5 ? briquesSombres(rnd.nextFloat()) : Blocks.IRON_BARS.defaultBlockState());
                }
                // le fond, épais, et les cloisons entre les cellules
                for (int v = 9; v < LARGE; v++) {
                    for (int y = 1; y < HAUT_AILE; y++) {
                        pa(a, u, y, v * s, briquesSombres(rnd.nextFloat()));
                    }
                }
                if (cloison) {
                    for (int v = 4; v <= 8; v++) {
                        for (int y = 1; y < HAUT_AILE; y++) {
                            pa(a, u, y, v * s, briquesSombres(rnd.nextFloat()));
                        }
                    }
                }
            }
        }
        // les plafonds bas des cellules
        for (int u = HALL + 1; u <= AILE - 1; u++) {
            for (int v = 4; v <= 8; v++) {
                pa(a, u, 6, v, Blocks.DEEPSLATE_TILES.defaultBlockState());
                pa(a, u, 6, -v, Blocks.DEEPSLATE_TILES.defaultBlockState());
            }
        }
        // les huit cellules
        prisonnier(a, debuts[0], -1);
        chair(a, debuts[1], -1);
        squelette(a, debuts[2], -1);
        evasion(a, debuts[3], -1);
        sculk(a, debuts[0], 1);
        pleureur(a, debuts[1], 1);
        cellulePendu(a, debuts[2], 1);
        ecritures(a, debuts[3], 1);
        // le couloir : lanternes d'âmes, deux lampes qui grésillent, la porte d'isolement au fond
        for (int u = 25; u <= 43; u += 6) {
            pa(a, u, HAUT_AILE - 1, 0, Blocks.SOUL_LANTERN.defaultBlockState().setValue(LanternBlock.HANGING, true));
        }
        BlockState porte = Blocks.IRON_DOOR.defaultBlockState().setValue(DoorBlock.FACING, a.versHall());
        pose(ISOLEMENT.getX(), 1, ISOLEMENT.getZ(), porte.setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER));
        pose(ISOLEMENT.getX(), 2, ISOLEMENT.getZ(), porte.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
        for (int v = -2; v <= 2; v++) {
            for (int y = 1; y <= 4; y++) {
                if (v != 0 || y > 2) {
                    pa(a, AILE - 1, y, v, Blocks.POLISHED_BLACKSTONE_BRICKS.defaultBlockState());
                }
            }
        }
        murTexte(a, AILE - 2, 0, 3.6, a.sortie, "[{\"text\":\"ISOLEMENT\\n\",\"color\":\"red\",\"bold\":true},{\"text\":\"ne pas ouvrir, quoi qu'il dise\",\"color\":\"gray\",\"bold\":false}]", 0.6F);
        porte(a, "[{\"text\":\"CELLULES\\n\",\"color\":\"gold\",\"bold\":true},{\"text\":\"sujets en observation\",\"color\":\"gray\",\"bold\":false}]");
    }

    /** Pose dans une cellule : {@code du} depuis le début de la cellule, {@code p} vers le fond (0 = derrière les barreaux). */
    private void pc(Aile a, int u0, int s, int du, int y, int p, BlockState state) {
        pa(a, u0 + du, y, (4 + p) * s, state);
    }

    /** Le centre d'une case de cellule, en coordonnées relatives (x, z). */
    private double[] centre(Aile a, int u0, int s, int du, int p) {
        int u = u0 + du, v = (4 + p) * s;
        return new double[] {a.x(u, v) + 0.5, a.z(u, v) + 0.5};
    }

    private Direction versCouloir(Aile a, int s) {
        return s < 0 ? a.cote() : a.cote().getOpposite();
    }

    private void prisonnier(Aile a, int u0, int s) {
        double[] p = centre(a, u0, s, 2, 0);
        figurant("minecraft:zombie_villager", p[0], 1, p[1], versCouloir(a, s).toYRot(), true, false, null);
        for (int y = 2; y <= 4; y++) {
            pc(a, u0, s, 1, y, 4, Blocks.CHAIN.defaultBlockState());
            pc(a, u0, s, 3, y, 4, Blocks.CHAIN.defaultBlockState());
        }
        pc(a, u0, s, 0, 1, 4, Blocks.BROWN_CARPET.defaultBlockState());
        pc(a, u0, s, 0, 1, 3, Blocks.BROWN_CARPET.defaultBlockState());
        pc(a, u0, s, 4, 1, 4, Blocks.CAULDRON.defaultBlockState());
        pc(a, u0, s, 4, 1, 1, Blocks.SKELETON_SKULL.defaultBlockState().setValue(BlockStateProperties.ROTATION_16, 3));
    }

    /** La cellule de chair : tout est vivant, des yeux poussent par terre. */
    private void chair(Aile a, int u0, int s) {
        BlockState chair = deco("biomesoplenty:flesh", Blocks.NETHER_WART_BLOCK.defaultBlockState());
        BlockState poreuse = deco("biomesoplenty:porous_flesh", Blocks.CRIMSON_NYLIUM.defaultBlockState());
        for (int du = -1; du <= 5; du++) {
            for (int p = 0; p <= 5; p++) {
                for (int y = 0; y <= 6; y++) {
                    boolean paroi = du == -1 || du == 5 || p == 5 || y == 0 || y == 6;
                    if (paroi && !(p == 0 && y > 0 && y < 6 && du >= 0 && du <= 4)) {
                        pc(a, u0, s, du, y, p, rnd.nextFloat() < 0.3F ? poreuse : chair);
                    }
                }
            }
        }
        BlockState tendons = modOuNull("biomesoplenty:flesh_tendons");
        BlockState brin = modOuNull("biomesoplenty:flesh_tendons_strand");
        if (tendons != null) {
            for (int[] t : new int[][] {{1, 2}, {3, 3}, {0, 4}, {4, 1}}) {
                int bas = 3 + rnd.nextInt(2);
                for (int y = 5; y > bas; y--) {
                    pc(a, u0, s, t[0], y, t[1], tendons);
                }
                if (brin != null) {
                    pc(a, u0, s, t[0], bas, t[1], brin);
                }
            }
        }
        BlockState oeil = modOuNull("biomesoplenty:eyebulb");
        if (oeil != null) {
            for (int[] o : new int[][] {{1, 4}, {3, 1}, {2, 3}}) {
                pc(a, u0, s, o[0], 1, o[1], avec(oeil, "half", "lower"));
                pc(a, u0, s, o[0], 2, o[1], avec(oeil, "half", "upper"));
            }
        }
        objet(a, u0, s, 0, 1, 2, "biomesoplenty:pus_bubble");
        objet(a, u0, s, 4, 1, 3, "biomesoplenty:hair");
        double[] p = centre(a, u0, s, 2, 2);
        texte(p[0], 4.6, p[1], 0F, "{\"text\":\"NE PAS NOURRIR\",\"color\":\"red\",\"bold\":true}", 0.5F, 0x55000000, "vertical");
    }

    private void objet(Aile a, int u0, int s, int du, int y, int p, String id) {
        BlockState b = modOuNull(id);
        if (b != null) {
            pc(a, u0, s, du, y, p, b);
        }
    }

    /** Un squelette étendu par terre, des marques de jours sur le mur. */
    private void squelette(Aile a, int u0, int s) {
        double[] p = centre(a, u0, s, 2, 2);
        corps("minecraft:skeleton", p[0], 1.1, p[1], c.offset((int) Math.floor(p[0]), 1, (int) Math.floor(p[1])), null);
        for (int k = 0; k < 6; k++) {
            int du = rnd.nextInt(5), pp = rnd.nextInt(5);
            BlockPos q = c.offset(a.x(u0 + du, (4 + pp) * s), 1, a.z(u0 + du, (4 + pp) * s));
            if (level.getBlockState(q).isAir()) {
                sang(q.getX() - c.getX(), 1, q.getZ() - c.getZ());
            }
        }
        murTexte(a, u0 + 2, 8 * s, 2.6, versCouloir(a, s).getOpposite(),
                "{\"text\":\"IIII IIII IIII IIII\\nIIII IIII III\\nJOUR 26\",\"color\":\"dark_red\"}", 0.7F);
    }

    /** La cellule du Sujet 12 : des barreaux arrachés, une traînée de sang qui sort vers la salle. */
    private void evasion(Aile a, int u0, int s) {
        for (int du = 1; du <= 3; du++) {
            for (int y = 1; y <= 3 + rnd.nextInt(2); y++) {
                pa(a, u0 + du, y, 3 * s, AIR);
            }
        }
        pc(a, u0, s, 0, 1, -2, Blocks.IRON_BARS.defaultBlockState());
        pc(a, u0, s, 3, 1, -3, Blocks.IRON_BARS.defaultBlockState());
        for (int u = u0 + 2; u >= HALL - 1; u -= 2) {
            pa(a, u, 1, rnd.nextInt(3) - 1, Blocks.REDSTONE_WIRE.defaultBlockState());
        }
        murTexte(a, u0 + 2, 8 * s, 2.4, versCouloir(a, s).getOpposite(), "{\"text\":\"IL EST SORTI\",\"color\":\"dark_red\",\"bold\":true}", 1.2F);
    }

    /** Le sujet a fondu : il n'en reste que du sculk, qui crie quand on marche dessus. */
    private void sculk(Aile a, int u0, int s) {
        for (int du = 0; du <= 4; du++) {
            for (int p = 0; p <= 4; p++) {
                pc(a, u0, s, du, 0, p, Blocks.SCULK.defaultBlockState());
            }
        }
        pc(a, u0, s, 2, 1, 2, Blocks.SCULK_SHRIEKER.defaultBlockState());
        pc(a, u0, s, 0, 1, 4, Blocks.SCULK_CATALYST.defaultBlockState());
        pc(a, u0, s, 4, 1, 0, Blocks.SCULK_SENSOR.defaultBlockState());
        double[] p = centre(a, u0, s, 2, 2);
        texte(p[0], 3.8, p[1], 0F, "[{\"text\":\"SUJET 10\\n\",\"color\":\"red\",\"bold\":true},{\"text\":\"dissous\",\"color\":\"gray\",\"bold\":false}]",
                0.5F, 0x55000000, "vertical");
    }

    /** Un villageois recroquevillé face au mur. */
    private void pleureur(Aile a, int u0, int s) {
        double[] p = centre(a, u0, s, 1, 4);
        figurant("minecraft:villager", p[0], 1, p[1], versCouloir(a, s).getOpposite().toYRot(), true, false, null);
        pc(a, u0, s, 3, 1, 4, Blocks.BROWN_CARPET.defaultBlockState());
        pc(a, u0, s, 4, 1, 4, Blocks.BROWN_CARPET.defaultBlockState());
    }

    private void cellulePendu(Aile a, int u0, int s) {
        double[] p = centre(a, u0, s, 2, 2);
        for (int y = 4; y <= 5; y++) {
            pose((int) Math.floor(p[0]), y, (int) Math.floor(p[1]), Blocks.CHAIN.defaultBlockState());
        }
        figurant("minecraft:zombie_villager", p[0], 2.0, p[1], rnd.nextFloat() * 360F, true, true, e -> e.setCustomName(Component.literal("Dinnerbone")));
    }

    private void ecritures(Aile a, int u0, int s) {
        Direction fond = versCouloir(a, s).getOpposite();
        murTexte(a, u0 + 2, 8 * s, 4.2, fond, "{\"text\":\"LAISSEZ-MOI SORTIR\",\"color\":\"dark_red\"}", 0.9F);
        murTexte(a, u0 + 1, 8 * s, 3.2, fond, "{\"text\":\"LAISSEZ-MOI SORTIR\",\"color\":\"dark_red\"}", 0.6F);
        murTexte(a, u0 + 3, 8 * s, 2.5, fond, "{\"text\":\"laissez-moi sortir\",\"color\":\"dark_red\"}", 0.45F);
        murTexte(a, u0 + 2, 8 * s, 1.6, fond, "{\"text\":\"IL N'Y A PAS DE SORTIE\",\"color\":\"dark_red\",\"bold\":true}", 0.7F);
        pc(a, u0, s, 1, 1, 3, Blocks.SKELETON_SKULL.defaultBlockState().setValue(BlockStateProperties.ROTATION_16, 7));
        pc(a, u0, s, 3, 1, 1, Blocks.BONE_BLOCK.defaultBlockState());
    }

    // ================================================================== l'aile ouest : la morgue

    private void morgue() {
        Aile a = Aile.OUEST;
        // le mur des tiroirs réfrigérés
        // chaque tiroir : une porte de métal (une trappe de fer ouverte, plaquée au mur), et son numéro
        BlockState porte = Blocks.IRON_TRAPDOOR.defaultBlockState().setValue(TrapDoorBlock.OPEN, true).setValue(TrapDoorBlock.FACING, a.versHall());
        int n = 1;
        for (int v = -13; v <= 13; v++) {
            for (int y = 1; y <= 7; y++) {
                pa(a, AILE - 1, y, v, Blocks.SMOOTH_STONE.defaultBlockState());
                boolean face = (v + 13) % 2 == 0 && y % 2 == 0;
                if (face && !(v == 1 && y == 2)) {
                    pa(a, AILE - 2, y, v, porte);
                    if ((v + y) % 5 == 0) {
                        double x = a.x(AILE - 2, v) + 0.5 + a.sortie.getStepX() * 0.3, z = a.z(AILE - 2, v) + 0.5 + a.sortie.getStepZ() * 0.3;
                        texte(x, y + 0.7, z, a.versHall().toYRot(), "{\"text\":\"N°" + (100 + n * 7) + "\",\"color\":\"white\"}", 0.25F, 0, "fixed");
                    }
                    n++;
                }
            }
        }
        // un tiroir resté ouvert, et ce qu'il contient
        tableAile(a, AILE - 3, 1, 1, a.sortie, "minecraft:zombie_villager");
        // les tables d'autopsie, avec leurs corps
        tableAile(a, 33, 1, -6, a.sortie, "minecraft:villager");
        tableAile(a, 33, 1, 0, a.sortie, "minecraft:husk");
        tableAile(a, 33, 1, 6, a.sortie, "");
        // les frigos à organes, le long du mur
        for (int u = 27; u <= 41; u += 2) {
            pa(a, u, 1, LARGE - 1, vers(deco("refurbished_furniture:light_fridge", Blocks.SMOOTH_STONE.defaultBlockState()), a.cote().getOpposite()));
            pa(a, u, 2, LARGE - 1, vers(deco("refurbished_furniture:light_freezer", Blocks.SMOOTH_STONE.defaultBlockState()), a.cote().getOpposite()));
        }
        // le bain de sang, creusé dans le sol
        BlockState sang = deco("biomesoplenty:blood", Blocks.WATER.defaultBlockState());
        for (int u = 26; u <= 29; u++) {
            for (int v = -14; v <= -10; v++) {
                boolean bord = u == 26 || u == 29 || v == -14 || v == -10;
                pa(a, u, -1, v, Blocks.POLISHED_DIORITE.defaultBlockState());
                pa(a, u, 0, v, bord ? Blocks.SMOOTH_QUARTZ.defaultBlockState() : sang);
            }
        }
        // les crochets du boucher, le froid, la lumière bleue
        for (int v = -6; v <= 6; v += 6) {
            for (int y = 6; y < HAUT_AILE; y++) {
                pa(a, 39, y, v, Blocks.CHAIN.defaultBlockState());
            }
            pa(a, 39, 5, v, Blocks.IRON_BARS.defaultBlockState());
        }
        for (int u = 26; u <= 44; u += 6) {
            for (int v = -10; v <= 10; v += 10) {
                pa(a, u, HAUT_AILE, v, Blocks.SEA_LANTERN.defaultBlockState());
            }
        }
        pa(a, 30, HAUT_AILE - 1, 6, Blocks.SOUL_LANTERN.defaultBlockState().setValue(LanternBlock.HANGING, true));
        for (int k = 0; k < 24; k++) {
            int u = 24 + rnd.nextInt(20), v = rnd.nextInt(27) - 13;
            if (level.getBlockState(c.offset(a.x(u, v), 1, a.z(u, v))).isAir()) {
                pa(a, u, 1, v, rnd.nextInt(4) == 0 ? Blocks.SNOW.defaultBlockState() : Blocks.REDSTONE_WIRE.defaultBlockState());
            }
        }
        porte(a, "[{\"text\":\"MORGUE\\n\",\"color\":\"gold\",\"bold\":true},{\"text\":\"chambre froide — 4 °C\",\"color\":\"gray\",\"bold\":false}]");
    }

    /** Une table d'autopsie le long de u (la tête vers {@code tete}), avec un corps dessus si {@code mob} n'est pas vide. */
    private void tableAile(Aile a, int u, int y, int v, Direction tete, String mob) {
        BlockState t = Labo.TABLE.get().defaultBlockState().setValue(LaboBlocs.TableExperiences.FACING, tete);
        int du = tete == a.sortie ? 1 : -1;
        pa(a, u, y, v, t.setValue(LaboBlocs.TableExperiences.PART, BedPart.FOOT));
        pa(a, u + du, y, v, t.setValue(LaboBlocs.TableExperiences.PART, BedPart.HEAD));
        if (!mob.isEmpty()) {
            BlockPos lit = c.offset(a.x(u + du, v), y, a.z(u + du, v));
            corps(mob, lit.getX() + 0.5 - c.getX(), y + 0.6875, lit.getZ() + 0.5 - c.getZ(), lit, null);
        }
    }

    // ================================================================== l'aile sud : la salle de contrôle et le bureau du docteur

    private void controle() {
        Aile a = Aile.SUD;
        // la cloison entre la salle de contrôle et le bureau
        for (int v = -LARGE + 1; v < LARGE; v++) {
            for (int y = 1; y < HAUT_AILE; y++) {
                if (!(Math.abs(v) <= 1 && y <= 3)) {
                    pa(a, 33, y, v, y <= 3 ? Blocks.DARK_OAK_PLANKS.defaultBlockState() : Blocks.GRAY_CONCRETE.defaultBlockState());
                }
            }
        }
        // la console : une rangée de bureaux, des ordinateurs, face à la vitre sur la salle
        for (int v = -11; v <= 11; v++) {
            if (Math.abs(v) <= 2) {
                continue;
            }
            pa(a, 24, 1, v, Blocks.POLISHED_BLACKSTONE.defaultBlockState());
            pa(a, 24, 2, v, Blocks.POLISHED_BLACKSTONE_SLAB.defaultBlockState());
            if (v % 3 == 0) {
                pa(a, 24, 2, v, vers(deco("refurbished_furniture:computer", Blocks.POLISHED_BLACKSTONE_SLAB.defaultBlockState()), a.sortie));
                pa(a, 25, 1, v, vers(deco("handcrafted:dark_oak_chair", escalier(Blocks.DARK_OAK_STAIRS, a.sortie)), a.versHall()));
            } else if (v % 3 == 1) {
                pa(a, 24, 3, v, Blocks.LEVER.defaultBlockState().setValue(BlockStateProperties.ATTACH_FACE, AttachFace.FLOOR)
                        .setValue(BlockStateProperties.HORIZONTAL_FACING, a.sortie));
            }
        }
        // les grands écrans au-dessus de la vitre
        ecranAile(a, HALL + 1, -10, 5.4, a.versHall(), "table", 1.1F);
        ecranAile(a, HALL + 1, 0, 7.0, a.versHall(), "alerte", 1.2F);
        ecranAile(a, HALL + 1, 10, 5.4, a.versHall(), "cuves", 1.1F);
        // des tubes Nixie rouges au mur (Create)
        BlockState nixie = modOuNull("create:red_nixie_tube");
        if (nixie != null) {
            for (int v = -14; v <= -12; v++) {
                pa(a, 30, 4, v, vers(nixie, a.cote()));
            }
        }
        for (int v = -12; v <= 12; v += 8) {
            pa(a, 29, HAUT_AILE, v, Blocks.REDSTONE_LAMP.defaultBlockState().setValue(RedstoneLampBlock.LIT, true));
        }
        // le bureau du docteur : son bureau, son fauteuil… et lui, toujours assis
        for (int v = -2; v <= 2; v++) {
            pa(a, 40, 1, v, vers(deco("handcrafted:dark_oak_desk", Blocks.DARK_OAK_PLANKS.defaultBlockState()), a.versHall()));
        }
        pa(a, 41, 1, 0, vers(deco("handcrafted:dark_oak_chair", escalier(Blocks.DARK_OAK_STAIRS, a.versHall())), a.versHall()));
        pa(a, 40, 2, -2, Blocks.LANTERN.defaultBlockState());
        pa(a, 40, 2, 2, vers(deco("refurbished_furniture:computer", AIR), a.versHall()));
        assis("minecraft:skeleton", a.x(41, 0) + 0.5, 1.15, a.z(41, 0) + 0.5, a.versHall().toYRot(), 55F, true);
        pa(a, 38, 1, 4, Blocks.LECTERN.defaultBlockState().setValue(LecternBlock.FACING, a.versHall()));
        BlockPos pupitre = c.offset(a.x(38, 4), 1, a.z(38, 4));
        LecternBlock.tryPlaceBook(null, level, pupitre, level.getBlockState(pupitre), journal());
        // les bibliothèques, le tableau noir, le coffre-fort
        for (int v = -14; v <= -6; v++) {
            for (int y = 1; y <= 3; y++) {
                pa(a, 44, y, v, Blocks.BOOKSHELF.defaultBlockState());
            }
        }
        pa(a, 44, 1, -5, deco("supplementaries:safe", Blocks.IRON_BLOCK.defaultBlockState()));
        for (int v = 4; v <= 6; v++) {
            pa(a, AILE - 1, 4, v, vers(deco("supplementaries:blackboard", Blocks.BLACK_CONCRETE.defaultBlockState()), a.versHall()));
        }
        // la collection du docteur : des trophées au mur
        String[] trophees = {"handcrafted:creeper_trophy", "handcrafted:spider_trophy", "handcrafted:witch_trophy", "handcrafted:skeleton_trophy",
                "handcrafted:wither_skeleton_trophy", "handcrafted:phantom_trophy"};
        for (int k = 0; k < trophees.length; k++) {
            pa(a, 35 + k * 2, 5, -LARGE + 1, vers(deco(trophees[k], AIR), a.cote()));
        }
        // la chaise électrique, dans le coin, avec son dernier occupant
        int u = 43, v = 12;
        pa(a, u, 1, v, Blocks.POLISHED_BLACKSTONE.defaultBlockState());
        pa(a, u, 2, v, escalier(Blocks.DARK_OAK_STAIRS, a.cote().getOpposite()));
        pa(a, u, 3, v + 1, Blocks.DARK_OAK_FENCE.defaultBlockState());
        pa(a, u, 4, v + 1, Blocks.WAXED_COPPER_BULB.defaultBlockState().setValue(BlockStateProperties.LIT, true));
        pa(a, u, 5, v + 1, Blocks.LIGHTNING_ROD.defaultBlockState());
        pa(a, u - 1, 2, v, Blocks.LIGHTNING_ROD.defaultBlockState().setValue(LightningRodBlock.FACING, a.versHall()));
        pa(a, u + 1, 2, v, Blocks.LIGHTNING_ROD.defaultBlockState().setValue(LightningRodBlock.FACING, a.sortie));
        for (int y = 6; y < HAUT_AILE; y++) {
            pa(a, u, y, v + 1, Blocks.CHAIN.defaultBlockState());
        }
        assis("minecraft:skeleton", a.x(u, v) + 0.5, 2.15, a.z(u, v) + 0.5, a.cote().getOpposite().toYRot(), 20F, false);
        pa(a, 40, HAUT_AILE - 1, 0, Blocks.LANTERN.defaultBlockState().setValue(LanternBlock.HANGING, true));
        pa(a, 36, HAUT_AILE - 1, -9, Blocks.LANTERN.defaultBlockState().setValue(LanternBlock.HANGING, true));
        objetAile(a, 42, 1, -12, "mcwlights:lava_lamp");
        porte(a, "[{\"text\":\"CONTRÔLE\\n\",\"color\":\"gold\",\"bold\":true},{\"text\":\"personnel autorisé\",\"color\":\"gray\",\"bold\":false}]");
        murTexte(a, 32, 0, 4.6, a.sortie, "{\"text\":\"Dr. VORACE — DIRECTEUR\",\"color\":\"white\"}", 0.5F);
    }

    private void objetAile(Aile a, int u, int y, int v, String id) {
        BlockState b = modOuNull(id);
        if (b != null) {
            pa(a, u, y, v, b);
        }
    }

    /** Le journal du docteur, sur son pupitre. */
    private static ItemStack journal() {
        List<Filterable<Component>> pages = List.of(
                "Journal du Dr. Vorace\n\nJour 1.\nLe site est enfin isolé du monde. Ici, pas de lois, pas de témoins. Juste le vide autour.",
                "Jour 40.\nLe Sujet 03 a triplé de volume. Il ne répond plus à son nom. Il répond au mien.",
                "Jour 77.\nLa table fonctionne. Les sujets en ressortent… différents. Plus petits, plus grands, plus légers. Jamais pareils.",
                "Jour 112.\nLe Sujet 12 a brisé sa cuve cette nuit. Nous ne l'avons pas retrouvé. Les gardes disent entendre frapper derrière la porte d'isolement.",
                "Jour 113.\nJe ne dors plus. Le Sujet Zéro me regarde même les yeux fermés.\n\nS'il se réveille, appuyez sur le bouton rouge. Et ne revenez pas.")
                .stream().map(t -> Filterable.passThrough((Component) Component.literal(t))).toList();
        ItemStack livre = new ItemStack(Items.WRITTEN_BOOK);
        livre.set(DataComponents.WRITTEN_BOOK_CONTENT, new WrittenBookContent(Filterable.passThrough("Journal du Dr. Vorace"), "Dr. Vorace", 0, pages, true));
        return livre;
    }

    // ================================================================== la salle des rituels

    /**
     * Une grotte très sombre dans l'angle nord-est, qu'on rejoint par le fond de la salle des cuves : un grand sceau
     * rouge qui luit au sol, un cercle de menhirs (deux portent un linteau), des silhouettes encapuchonnées dans l'ombre
     * et, au milieu, un autel de pierre sur une estrade ronde, où l'on peut s'allonger.
     */
    private void rituels() {
        int x0 = RX - 17, x1 = RX + 16, z0 = RZ - 15, z1 = RZ + 15;
        int[][] voutes = new int[x1 - x0 + 1][z1 - z0 + 1];
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                int voute = voute(x, z);
                voutes[x - x0][z - z0] = voute;
                pose(x, -1, z, roche());
                if (voute > 0) {
                    pose(x, 0, z, solRituel());
                }
                for (int y = voute > 0 ? voute : 0; y <= HAUT + 2; y++) {
                    pose(x, y, z, roche());
                }
            }
        }
        // le passage depuis le fond de la salle des cuves (mur est de l'aile nord, x = 16)
        for (int x = LARGE; x <= RX - 13; x++) {
            for (int z = RZ - 1; z <= RZ + 1; z++) {
                pose(x, 0, z, solRituel());
                for (int y = 1; y <= 3; y++) {
                    pose(x, y, z, AIR);
                }
            }
        }
        for (int y = 1; y <= 4; y++) {
            pose(LARGE, y, RZ - 2, Blocks.POLISHED_BLACKSTONE_BRICKS.defaultBlockState());
            pose(LARGE, y, RZ + 2, Blocks.POLISHED_BLACKSTONE_BRICKS.defaultBlockState());
        }
        for (int z = RZ - 2; z <= RZ + 2; z++) {
            pose(LARGE, 4, z, Blocks.POLISHED_BLACKSTONE_BRICKS.defaultBlockState());
        }
        pose(LARGE - 1, 1, RZ - 2, Blocks.RED_CANDLE.defaultBlockState().setValue(BlockStateProperties.CANDLES, 3).setValue(BlockStateProperties.LIT, true));
        pose(LARGE - 1, 1, RZ + 2, Blocks.RED_CANDLE.defaultBlockState().setValue(BlockStateProperties.CANDLES, 2).setValue(BlockStateProperties.LIT, true));
        texte(LARGE - 0.06, 5.0, RZ + 0.5, Direction.WEST.toYRot(),
                "[{\"text\":\"SALLE DES RITUELS\\n\",\"color\":\"dark_red\",\"bold\":true},{\"text\":\"n'y va jamais seul\",\"color\":\"gray\",\"bold\":false}]",
                0.8F, 0, "fixed");

        // l'estrade ronde et l'autel
        for (int x = RX - 4; x <= RX + 4; x++) {
            for (int z = RZ - 4; z <= RZ + 4; z++) {
                double d = Math.sqrt((x - RX) * (x - RX) + (z - RZ) * (z - RZ));
                if (d <= 3.6) {
                    pose(x, 1, z, Blocks.POLISHED_BLACKSTONE_BRICKS.defaultBlockState());
                }
                if (d <= 2.6) {
                    pose(x, 2, z, Blocks.POLISHED_BLACKSTONE.defaultBlockState());
                }
            }
        }
        BlockState autel = Labo.AUTEL.get().defaultBlockState().setValue(LaboBlocs.TableExperiences.FACING, Direction.EAST);
        pose(RX, 3, RZ, autel.setValue(LaboBlocs.TableExperiences.PART, BedPart.FOOT));
        pose(RX + 1, 3, RZ, autel.setValue(LaboBlocs.TableExperiences.PART, BedPart.HEAD));
        // le bouton du sceau, au pied de l'autel : il libère le Hachibi derrière le mur est (voir Scellement)
        pose(Scellement.BOUTON.getX(), Scellement.BOUTON.getY(), Scellement.BOUTON.getZ(), Blocks.CRIMSON_BUTTON.defaultBlockState()
                .setValue(BlockStateProperties.ATTACH_FACE, AttachFace.FLOOR).setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.EAST));
        for (int k = 0; k < 10; k++) {
            int x = RX + rnd.nextInt(9) - 4, z = RZ + rnd.nextInt(9) - 4;
            int y = Math.abs(x - RX) <= 2 && Math.abs(z - RZ) <= 2 ? 3 : 2;
            if (level.getBlockState(c.offset(x, y, z)).isAir() && !level.getBlockState(c.offset(x, y - 1, z)).isAir()) {
                sang(x, y, z);
            }
        }
        // le sceau, qui luit au sol
        sceau(RX + 0.5, 1.02, RZ + 0.5);

        // le cercle de pierres levées, ouvert du côté du passage ; deux trilithes
        int[][] sommets = new int[14][];
        for (int k = 0; k < 14; k++) {
            if (k == 7) {
                continue;
            }
            double a = k * Math.PI * 2 / 14 + (rnd.nextDouble() - 0.5) * 0.12;
            int px = RX + (int) Math.round(Math.cos(a) * 11.5), pz = RZ + (int) Math.round(Math.sin(a) * 10.2);
            int h = 3 + rnd.nextInt(4);
            boolean large = rnd.nextInt(3) == 0;
            int tx = (int) Math.round(-Math.sin(a)), tz = (int) Math.round(Math.cos(a));
            for (int y = 1; y <= h; y++) {
                pose(px, y, pz, pierre());
                if (large) {
                    pose(px + tx, y, pz + tz, pierre());
                }
            }
            sommets[k] = new int[] {px, h, pz};
            int cx = px - (int) Math.round(Math.cos(a)), cz = pz - (int) Math.round(Math.sin(a));
            if (k % 2 == 0 && level.getBlockState(c.offset(cx, 1, cz)).isAir()) {
                pose(cx, 1, cz, Blocks.RED_CANDLE.defaultBlockState().setValue(BlockStateProperties.CANDLES, 1 + rnd.nextInt(3))
                        .setValue(BlockStateProperties.LIT, true));
            } else if (k % 3 == 0 && level.getBlockState(c.offset(cx, 1, cz)).isAir()) {
                pose(cx, 1, cz, Blocks.SKELETON_SKULL.defaultBlockState().setValue(BlockStateProperties.ROTATION_16, rnd.nextInt(16)));
            }
        }
        linteau(sommets[2], sommets[3]);
        linteau(sommets[10], sommets[11]);
        for (int k : new int[] {0, 5, 9, 13}) {
            int[] s = sommets[k];
            pose(s[0], s[1] + 1, s[2], Blocks.REDSTONE_TORCH.defaultBlockState());
        }
        // les fidèles, encapuchonnés, qui regardent l'autel depuis l'ombre
        for (int k = 0; k < 7; k++) {
            double a = (k + 0.5) * Math.PI * 2 / 7 + 0.2;
            if (Math.abs(Math.cos(a) + 1) < 0.15) {
                continue;
            }
            int px = RX + (int) Math.round(Math.cos(a) * 13.4), pz = RZ + (int) Math.round(Math.sin(a) * 11.6);
            if (level.getBlockState(c.offset(px, 1, pz)).isAir() && level.getBlockState(c.offset(px, 2, pz)).isAir()) {
                float yaw = (float) Math.toDegrees(Math.atan2(-(RX - px), RZ - pz));
                figurant("minecraft:evoker", px + 0.5, 1, pz + 0.5, yaw, true, false, null);
            }
        }
        // des racines rouges qui pendent de la voûte, des stalactites, et juste assez de lumière pour deviner les formes
        for (int k = 0; k < 40; k++) {
            int x = x0 + 2 + rnd.nextInt(x1 - x0 - 3), z = z0 + 2 + rnd.nextInt(z1 - z0 - 3);
            int voute = voutes[x - x0][z - z0];
            double r = Math.sqrt((x - RX) * (x - RX) + (z - RZ) * (z - RZ));
            if (voute < 8 || r < 5 || !level.getBlockState(c.offset(x, voute - 1, z)).isAir()) {
                continue;
            }
            if (k % 3 == 0) {
                pose(x, voute - 1, z, Blocks.POINTED_DRIPSTONE.defaultBlockState()
                        .setValue(BlockStateProperties.VERTICAL_DIRECTION, Direction.DOWN)
                        .setValue(BlockStateProperties.DRIPSTONE_THICKNESS, net.minecraft.world.level.block.state.properties.DripstoneThickness.TIP));
            } else {
                int longueur = 2 + rnd.nextInt(5);
                for (int i = 1; i <= longueur; i++) {
                    pose(x, voute - i, z, i == longueur
                            ? Blocks.WEEPING_VINES.defaultBlockState().setValue(BlockStateProperties.AGE_25, 25)
                            : Blocks.WEEPING_VINES_PLANT.defaultBlockState());
                }
            }
        }
    }

    /** Un linteau posé sur deux pierres levées (la plus basse est rehaussée). */
    private void linteau(int[] a, int[] b) {
        if (a == null || b == null) {
            return;
        }
        int y = Math.max(a[1], b[1]) + 1;
        for (int[] s : new int[][] {a, b}) {
            for (int k = s[1] + 1; k < y; k++) {
                pose(s[0], k, s[2], pierre());
            }
        }
        int pas = Math.max(Math.abs(b[0] - a[0]), Math.abs(b[2] - a[2]));
        for (int i = 0; i <= pas; i++) {
            int x = (int) Math.round(a[0] + (b[0] - a[0]) * (double) i / pas), z = (int) Math.round(a[2] + (b[2] - a[2]) * (double) i / pas);
            pose(x, y, z, pierre());
        }
    }

    /** La hauteur de la voûte de la grotte des rituels en (x, z) : la roche commence là ; 0 hors de la grotte. */
    static int voute(int x, int z) {
        double dx = (x - RX) / 15.5, dz = (z - RZ) / 13.5;
        double e = dx * dx + dz * dz + (bruit(x, z) - 0.5) * 0.18;
        return e < 1 ? (int) Math.round(9 + 6 * Math.sqrt(1 - e) + bruit(x * 7, z * 7) * 2) : 0;
    }

    private BlockState roche() {
        return roche(rnd);
    }

    /** Un bloc de la roche de la grotte des rituels. */
    static BlockState roche(RandomSource rnd) {
        float r = rnd.nextFloat();
        if (r < 0.3F) {
            return Blocks.DEEPSLATE.defaultBlockState();
        }
        if (r < 0.5F) {
            return Blocks.COBBLED_DEEPSLATE.defaultBlockState();
        }
        if (r < 0.7F) {
            return Blocks.BLACKSTONE.defaultBlockState();
        }
        if (r < 0.8F) {
            return Blocks.TUFF.defaultBlockState();
        }
        if (r < 0.88F) {
            return Blocks.BASALT.defaultBlockState();
        }
        return r < 0.96F ? Blocks.RED_TERRACOTTA.defaultBlockState() : Blocks.NETHER_WART_BLOCK.defaultBlockState();
    }

    private BlockState solRituel() {
        float r = rnd.nextFloat();
        return (r < 0.35F ? Blocks.PACKED_MUD : r < 0.6F ? Blocks.BROWN_TERRACOTTA : r < 0.8F ? Blocks.RED_TERRACOTTA
                : r < 0.9F ? Blocks.TERRACOTTA : Blocks.COARSE_DIRT).defaultBlockState();
    }

    private BlockState pierre() {
        float r = rnd.nextFloat();
        return (r < 0.4F ? Blocks.STONE : r < 0.65F ? Blocks.ANDESITE : r < 0.85F ? Blocks.TUFF : Blocks.COBBLESTONE).defaultBlockState();
    }

    /** Un bruit fixe entre 0 et 1, pour que la grotte ait toujours la même forme. */
    private static double bruit(int x, int z) {
        return ((net.minecraft.util.Mth.getSeed(x, 0, z) >> 16) & 0xFFFF) / 65535.0;
    }

    /** Le grand sceau : un seul objet très agrandi (texture de 256 px), couché au sol et lumineux. */
    private void sceau(double x, double y, double z) {
        CompoundTag tag = base("minecraft:item_display");
        tag.put("item", new ItemStack(Labo.SCEAU.get()).save(level.registryAccess()));
        tag.putString("item_display", "none");
        float taille = (float) (RAYON_SCEAU * 2);
        CompoundTag t = new CompoundTag();
        t.put("left_rotation", floats(0, 0, 0, 1));
        t.put("right_rotation", floats(0, 0, 0, 1));
        t.put("translation", floats(0, 0, 0));
        t.put("scale", floats(taille, taille, taille));
        tag.put("transformation", t);
        CompoundTag lumiere = new CompoundTag();
        lumiere.putInt("sky", 15);
        lumiere.putInt("block", 15);
        tag.put("brightness", lumiere);
        tag.putFloat("view_range", 4F);
        ajouter(tag, x, y, z, 0F, 0F);
    }

    // ================================================================== outils : blocs

    private void pose(int dx, int dy, int dz, BlockState state) {
        BlockPos p = c.offset(dx, dy, dz);
        level.setBlock(p, state, FLAGS);
        poses.add(p);
    }

    private void pa(Aile a, int u, int y, int v, BlockState state) {
        pose(a.x(u, v), y, a.z(u, v), state);
    }

    private void sang(int x, int y, int z) {
        pose(x, y, z, Blocks.REDSTONE_WIRE.defaultBlockState());
    }

    private void finitions() {
        for (BlockPos p : poses) {
            BlockState s = level.getBlockState(p);
            if (s.getBlock() instanceof LaboBlocs.TableExperiences) {
                continue;
            }
            BlockState raccorde = Block.updateFromNeighbourShapes(s, level, p);
            if (raccorde != s && !raccorde.isAir()) {
                level.setBlock(p, raccorde, FLAGS);
            }
        }
    }

    /** Un bloc d'un mod de déco, ou {@code null} s'il n'est pas installé. */
    private static BlockState modOuNull(String id) {
        Block b = BuiltInRegistries.BLOCK.get(ResourceLocation.parse(id));
        return b == Blocks.AIR ? null : b.defaultBlockState();
    }

    private static BlockState deco(String id, BlockState repli) {
        BlockState s = modOuNull(id);
        return s == null ? repli : s;
    }

    private static BlockState deco(String id, Block repli) {
        return deco(id, repli.defaultBlockState());
    }

    /** Fixe une propriété par son nom (« facing », « axis »…), seulement si le bloc l'a. */
    private static BlockState avec(BlockState s, String nom, String valeur) {
        net.minecraft.world.level.block.state.properties.Property<?> p = s.getBlock().getStateDefinition().getProperty(nom);
        return p == null ? s : regler(s, p, valeur);
    }

    private static <T extends Comparable<T>> BlockState regler(BlockState s, net.minecraft.world.level.block.state.properties.Property<T> p, String valeur) {
        return p.getValue(valeur).map(v -> s.setValue(p, v)).orElse(s);
    }

    private static BlockState vers(BlockState s, Direction d) {
        return avec(s, "facing", d.getSerializedName());
    }

    /** Un objet accroché au mur : la version du mod, ou une torche murale vanilla. */
    private static BlockState mural(String id, Block repli, Direction face) {
        BlockState s = modOuNull(id);
        if (s == null) {
            return repli.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, face);
        }
        return avec(vers(s, face), "lit", "true");
    }

    private static BlockState escalier(Block block, Direction facing) {
        return block.defaultBlockState().setValue(StairBlock.FACING, facing);
    }

    // ================================================================== outils : entités

    private CompoundTag base(String id) {
        CompoundTag tag = new CompoundTag();
        tag.putString("id", id);
        ListTag tags = new ListTag();
        tags.add(StringTag.valueOf(TAG));
        tags.add(StringTag.valueOf(construction));
        tag.put("Tags", tags);
        return tag;
    }

    private Entity ajouter(CompoundTag tag, double x, double y, double z, float yaw, float pitch) {
        Entity e = EntityType.loadEntityRecursive(tag, level, ent -> {
            ent.moveTo(c.getX() + x, c.getY() + y, c.getZ() + z, yaw, pitch);
            return ent;
        });
        if (e == null) {
            return null;
        }
        if (e instanceof LivingEntity l) {
            l.setYHeadRot(yaw);
            l.setYBodyRot(yaw);
        }
        level.addFreshEntity(e);
        return e;
    }

    /** Un mob immobile, éternel et muet (sauf {@code bruyant}) ; {@code flotte} : sans gravité, au milieu d'une cuve. */
    private void figurant(String id, double x, double y, double z, float yaw, boolean muet, boolean flotte, Consumer<Entity> reglage) {
        CompoundTag tag = base(id);
        tag.putBoolean("NoAI", true);
        tag.putBoolean("Silent", muet);
        tag.putBoolean("Invulnerable", true);
        tag.putBoolean("PersistenceRequired", true);
        tag.putBoolean("NoGravity", flotte);
        Entity e = ajouter(tag, x, y, z, yaw, 0F);
        if (e != null && reglage != null) {
            reglage.accept(e);
        }
    }

    /** Un corps étendu (la pose « endormi »), sur une table ou à même le sol. */
    private void corps(String id, double x, double y, double z, BlockPos lit, Consumer<Entity> reglage) {
        CompoundTag tag = base(id);
        tag.getList("Tags", 8).add(StringTag.valueOf(CORPS));
        tag.putBoolean("NoAI", true);
        tag.putBoolean("Silent", true);
        tag.putBoolean("Invulnerable", true);
        tag.putBoolean("PersistenceRequired", true);
        tag.putInt("SleepingX", lit.getX());
        tag.putInt("SleepingY", lit.getY());
        tag.putInt("SleepingZ", lit.getZ());
        Entity e = ajouter(tag, x, y, z, 0F, 0F);
        if (e != null && reglage != null) {
            reglage.accept(e);
        }
    }

    /** Un squelette assis (sur un support d'armure invisible), en blouse blanche si {@code blouse}, la tête penchée. */
    private void assis(String id, double x, double y, double z, float yaw, float tete, boolean blouse) {
        CompoundTag siege = base("minecraft:armor_stand");
        siege.putBoolean("Invisible", true);
        siege.putBoolean("Marker", true);
        siege.putBoolean("NoGravity", true);
        siege.putBoolean("Invulnerable", true);
        Entity support = ajouter(siege, x, y, z, yaw, 0F);
        CompoundTag tag = base(id);
        tag.putBoolean("NoAI", true);
        tag.putBoolean("Silent", true);
        tag.putBoolean("Invulnerable", true);
        tag.putBoolean("PersistenceRequired", true);
        Entity e = ajouter(tag, x, y, z, yaw, tete);
        if (e instanceof Mob mob) {
            mob.setXRot(tete);
            mob.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
            if (blouse) {
                ItemStack veste = new ItemStack(Items.LEATHER_CHESTPLATE);
                veste.set(DataComponents.DYED_COLOR, new DyedItemColor(0xF0F0F0, false));
                mob.setItemSlot(EquipmentSlot.CHEST, veste);
            }
            if (support != null) {
                mob.startRiding(support, true);
            }
        }
    }

    /** Un objet qui flotte (item display), dans un bocal par exemple. */
    private void objetFlottant(ItemStack stack, double x, double y, double z, float echelle, float yaw) {
        CompoundTag tag = base("minecraft:item_display");
        tag.put("item", stack.save(level.registryAccess()));
        CompoundTag t = new CompoundTag();
        t.put("left_rotation", floats(0, 0, 0, 1));
        t.put("right_rotation", floats(0, 0, 0, 1));
        t.put("translation", floats(0, 0, 0));
        t.put("scale", floats(echelle, echelle, echelle));
        tag.put("transformation", t);
        ajouter(tag, x, y, z, yaw, 0F);
    }

    /** Un texte flottant ; {@code panneau} : « fixed », « vertical » (tourne avec le joueur) ou « center ». */
    private void texte(double x, double y, double z, float yaw, String json, float echelle, int fond, String panneau) {
        texte(x, y, z, yaw, json, echelle, fond, panneau, 0F);
    }

    private void texte(double x, double y, double z, float yaw, String json, float echelle, int fond, String panneau, float pitch) {
        CompoundTag tag = base("minecraft:text_display");
        tag.putString("text", json);
        tag.putString("billboard", panneau);
        tag.putString("alignment", "center");
        tag.putInt("background", fond);
        tag.putInt("line_width", 260);
        tag.putBoolean("shadow", fond == 0 && !json.contains("dark_red"));
        CompoundTag t = new CompoundTag();
        t.put("left_rotation", floats(0, 0, 0, 1));
        t.put("right_rotation", floats(0, 0, 0, 1));
        t.put("translation", floats(0, 0, 0));
        t.put("scale", floats(echelle, echelle, echelle));
        tag.put("transformation", t);
        ajouter(tag, x, y, z, yaw, pitch);
    }

    /**
     * Un texte collé contre un mur : au centre du bloc (u, v), poussé de presque un demi-bloc vers {@code versMur},
     * lisible depuis la pièce.
     */
    private void murTexte(Aile a, int u, int v, double y, Direction versMur, String json, float echelle) {
        double x = a.x(u, v) + 0.5 + versMur.getStepX() * 0.46, z = a.z(u, v) + 0.5 + versMur.getStepZ() * 0.46;
        texte(x, y, z, versMur.getOpposite().toYRot(), json, echelle, 0, "fixed");
    }

    /** L'étiquette au-dessus de la porte d'une aile, côté salle et côté aile. */
    private void porte(Aile a, String json) {
        murTexte(a, HALL - 1, 0, 7.6, a.sortie, json, 0.9F);
        // côté contrôle, ce mur-là porte les écrans
        if (a != Aile.SUD) {
            murTexte(a, HALL + 1, 0, 7.6, a.versHall(), json, 0.9F);
        }
    }

    /** Un écran (texte vert sur fond noir) animé par LaboVie ; {@code type} : table, cuves ou alerte. */
    private void ecran(double x, double y, double z, float yaw, String type, float echelle) {
        CompoundTag tag = base("minecraft:text_display");
        tag.getList("Tags", 8).add(StringTag.valueOf(ECRAN));
        tag.getList("Tags", 8).add(StringTag.valueOf(ECRAN + "." + type));
        tag.putString("text", "{\"text\":\"…\",\"color\":\"green\"}");
        tag.putString("billboard", "fixed");
        tag.putString("alignment", "left");
        tag.putInt("background", 0xEE020A04);
        tag.putInt("line_width", 400);
        CompoundTag lumiere = new CompoundTag();
        lumiere.putInt("sky", 15);
        lumiere.putInt("block", 15);
        tag.put("brightness", lumiere);
        CompoundTag t = new CompoundTag();
        t.put("left_rotation", floats(0, 0, 0, 1));
        t.put("right_rotation", floats(0, 0, 0, 1));
        t.put("translation", floats(0, 0, 0));
        t.put("scale", floats(echelle, echelle, echelle));
        tag.put("transformation", t);
        ajouter(tag, x, y, z, yaw, 0F);
    }

    /** Un écran contre un mur d'une aile, au bloc (u, v), tourné vers la pièce. */
    private void ecranAile(Aile a, int u, int v, double y, Direction versMur, String type, float echelle) {
        double x = a.x(u, v) + 0.5 + versMur.getStepX() * 0.47, z = a.z(u, v) + 0.5 + versMur.getStepZ() * 0.47;
        ecran(x, y, z, versMur.getOpposite().toYRot(), type, echelle);
    }

    private void cadre(int x, int y, int z, Direction face, ItemStack contenu, boolean invisible) {
        ItemFrame cadre = new ItemFrame(level, c.offset(x, y, z), face);
        cadre.setItem(contenu, false);
        CompoundTag t = new CompoundTag();
        cadre.saveWithoutId(t);
        t.putBoolean("Fixed", true);
        t.putBoolean("Invisible", invisible);
        cadre.load(t);
        cadre.addTag(TAG);
        cadre.addTag(construction);
        level.addFreshEntity(cadre);
    }

    private static ListTag floats(float... v) {
        ListTag l = new ListTag();
        for (float f : v) {
            l.add(FloatTag.valueOf(f));
        }
        return l;
    }
}
