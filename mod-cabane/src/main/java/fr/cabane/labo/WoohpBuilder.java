package fr.cabane.labo;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.Clearable;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.DirectionalBlock;
import net.minecraft.world.level.block.LanternBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Le QG du WOOHP, comme dans Totally Spies : une grande salle bleu-violet au plafond quadrillé, le logo géant (le globe
 * et les lettres WOOHP) au fond, trois tubes d'arrivée (vert, rouge, jaune) qui descendent du plafond, un tapis rose
 * jusqu'au bureau de Jerry couvert de papiers, et devant le bureau, un tapis roulant où arrivent les gadgets que Jerry
 * met dans son coffre caché. Dehors, dans le vide, les trois toboggans de verre qu'empruntent les espionnes.
 *
 * <p>Coordonnées relatives à {@link WoohpTrajet#ORIGINE} (le centre de la salle), y = 0 étant le sol.
 */
final class WoohpBuilder {
    /** À augmenter quand on change le QG : il se reconstruit à la prochaine utilisation de /whoop. */
    static final int VERSION = 2;
    static final String TAG = "cabane_woohp";
    /** La salle : murs à ±X et ±Z, plafond à HAUT. */
    static final int X = 20;
    static final int Z = 16;
    static final int HAUT = 12;
    /** Le tapis roulant (de x = TAPIS_X0 à TAPIS_X1, en z = TAPIS_Z) et le distributeur à son départ. */
    static final int TAPIS_X0 = -6;
    static final int TAPIS_X1 = 6;
    static final int TAPIS_Z = 7;
    /** Le coffre caché de Jerry, glissé dans le bureau côté Jerry, et la place de Jerry. */
    static final BlockPos COFFRE = new BlockPos(-3, 1, 9);
    static final BlockPos JERRY = new BlockPos(0, 1, 10);
    /** Jusqu'où va le chantier (les toboggans compris). */
    static final int BORD_X = 26;
    static final int BORD_Z0 = -20;
    static final int BORD_Z1 = 90;

    private static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;
    private static final BlockState AIR = Blocks.AIR.defaultBlockState();
    /** Les couleurs des trois espionnes : le verre de leur tube, et ses anneaux de lumière. */
    private static final Block[] VERRES = {Blocks.LIME_STAINED_GLASS, Blocks.RED_STAINED_GLASS, Blocks.YELLOW_STAINED_GLASS};
    private static final Block[] LUMIERES = {Blocks.VERDANT_FROGLIGHT, Blocks.SHROOMLIGHT, Blocks.OCHRE_FROGLIGHT};

    private final ServerLevel level;
    private final BlockPos c;
    private final RandomSource rnd = RandomSource.create(0x700BL);
    private final List<BlockPos> poses = new ArrayList<>();
    private final String construction;

    private WoohpBuilder(ServerLevel level, int numero) {
        this.level = level;
        this.c = WoohpTrajet.ORIGINE;
        this.construction = tagConstruction(numero);
    }

    static void construire(ServerLevel level, int numero) {
        new WoohpBuilder(level, numero).tout();
    }

    static String tagConstruction(int numero) {
        return "woohp.n" + numero;
    }

    static BlockPos abs(int x, int y, int z) {
        return WoohpTrajet.ORIGINE.offset(x, y, z);
    }

    private void tout() {
        for (int cx = (c.getX() - BORD_X) >> 4; cx <= (c.getX() + BORD_X) >> 4; cx++) {
            for (int cz = (c.getZ() + BORD_Z0) >> 4; cz <= (c.getZ() + BORD_Z1) >> 4; cz++) {
                level.getChunk(cx, cz);
            }
        }
        nettoyer();
        salle();
        logo();
        tubesArrivee();
        bureau();
        toboggans();
        finitions();
    }

    private void nettoyer() {
        AABB zone = new AABB(abs(-BORD_X, -20, BORD_Z0).getCenter(), abs(BORD_X, 50, BORD_Z1).getCenter());
        for (Entity e : level.getEntities((Entity) null, zone, e -> e.getTags().contains(TAG) || e instanceof ItemEntity)) {
            e.discard();
        }
        for (int x = -BORD_X; x <= BORD_X; x++) {
            for (int z = BORD_Z0; z <= BORD_Z1; z++) {
                for (int y = -16; y <= 45; y++) {
                    BlockPos p = c.offset(x, y, z);
                    if (!level.getBlockState(p).isAir()) {
                        Clearable.tryClear(level.getBlockEntity(p));
                        level.setBlock(p, AIR, FLAGS);
                    }
                }
            }
        }
    }

    // ================================================================== la salle

    private void salle() {
        for (int x = -X; x <= X; x++) {
            for (int z = -Z; z <= Z; z++) {
                pose(x, -1, z, Blocks.POLISHED_DEEPSLATE.defaultBlockState());
                // un carrelage bleu profond, en grands carreaux
                boolean carreau = ((Math.floorDiv(x, 3) + Math.floorDiv(z, 3)) & 1) == 0;
                pose(x, 0, z, (carreau ? Blocks.BLUE_CONCRETE : Blocks.LAPIS_BLOCK).defaultBlockState());
                // le plafond sombre, quadrillé, et ses grandes barres de lumière
                boolean barre = (z == -12 || z == -3 || z == 5 || z == 13) && Math.abs(x) <= 15;
                boolean joint = Math.floorMod(x, 3) == 0 || Math.floorMod(z, 3) == 0;
                pose(x, HAUT, z, barre ? Blocks.SEA_LANTERN.defaultBlockState() : joint ? Blocks.GRAY_CONCRETE.defaultBlockState() : Blocks.BLACK_CONCRETE.defaultBlockState());
                pose(x, HAUT + 1, z, Blocks.WHITE_CONCRETE.defaultBlockState());
                boolean bordX = Math.abs(x) == X, bordZ = Math.abs(z) == Z;
                if (bordX || bordZ) {
                    int v = bordX ? z : x;
                    for (int y = 1; y < HAUT; y++) {
                        pose(x, y, z, mur(v, y, bordX && bordZ));
                    }
                }
            }
        }
        // un grand cercle rose sur le toit (on le voit en arrivant par les toboggans)
        for (int x = -9; x <= 9; x++) {
            for (int z = -9; z <= 9; z++) {
                double d = Math.sqrt(x * x + (z - 2) * (z - 2));
                if (d <= 8.5 && d > 6.5 || d <= 1.5) {
                    pose(x, HAUT + 1, z, Blocks.MAGENTA_CONCRETE.defaultBlockState());
                }
            }
        }
        // des appliques le long des murs
        for (int v = -15; v <= 15; v += 6) {
            pose(-X + 1, 7, v, vers(deco("mcwlights:square_wall_lamp", Blocks.LANTERN.defaultBlockState()), Direction.EAST));
            pose(X - 1, 7, v, vers(deco("mcwlights:square_wall_lamp", Blocks.LANTERN.defaultBlockState()), Direction.WEST));
        }
    }

    /** Un bloc de mur : de grands panneaux bleu-gris séparés par des joints, une plinthe claire. */
    private BlockState mur(int v, int y, boolean coin) {
        if (coin) {
            return Blocks.BLUE_TERRACOTTA.defaultBlockState();
        }
        if (y == 1) {
            return Blocks.LIGHT_GRAY_CONCRETE.defaultBlockState();
        }
        if (Math.floorMod(v, 7) == 0) {
            return Blocks.BLUE_TERRACOTTA.defaultBlockState();
        }
        return Blocks.LIGHT_BLUE_TERRACOTTA.defaultBlockState();
    }

    // ================================================================== le logo : le globe et les lettres

    private static final String[] W = {"X...X", "X...X", "X...X", "X.X.X", "X.X.X", "XX.XX", "X...X"};
    private static final String[] O = {".XXX.", "X...X", "X...X", "X...X", "X...X", "X...X", ".XXX."};
    private static final String[] H = {"X...X", "X...X", "X...X", "XXXXX", "X...X", "X...X", "X...X"};
    private static final String[] P = {"XXXX.", "X...X", "X...X", "XXXX.", "X....", "X....", "X...."};

    private void logo() {
        // le grand panneau clair au fond, et le globe dessiné dessus
        for (int x = -16; x <= 16; x++) {
            for (int y = 2; y <= 10; y++) {
                double gx = x / 13.0, gy = (y - 6.5) / 4.6;
                double e = gx * gx + gy * gy;
                BlockState b = Blocks.WHITE_CONCRETE.defaultBlockState();
                if (e <= 1.0) {
                    boolean contour = e > 0.8;
                    boolean parallele = Math.abs(y - 6.5) < 0.6 || Math.abs(Math.abs(y - 6.5) - 2.6) < 0.5;
                    double mx = Math.abs(x) / Math.sqrt(Math.max(0.01, 1 - gy * gy));
                    boolean meridien = x == 0 || Math.abs(mx - 6.5) < 0.7 || Math.abs(mx - 10.5) < 0.7;
                    b = contour || parallele || meridien ? Blocks.GRAY_CONCRETE.defaultBlockState() : Blocks.PURPLE_TERRACOTTA.defaultBlockState();
                }
                pose(x, y, -Z, b);
            }
        }
        // les lettres, en relief, posées au sol devant le panneau
        String[][] lettres = {W, O, O, H, P};
        BlockState glace = Blocks.PACKED_ICE.defaultBlockState();
        for (int i = 0; i < lettres.length; i++) {
            int x0 = -14 + i * 6;
            for (int ligne = 0; ligne < 7; ligne++) {
                String s = lettres[i][ligne];
                for (int k = 0; k < 5; k++) {
                    if (s.charAt(k) == 'X') {
                        for (int dz = 0; dz <= 1; dz++) {
                            pose(x0 + k, 7 - ligne, -13 - dz, glace);
                        }
                    }
                }
            }
        }
        texte(0.5, 9.6, -Z + 1.05, 0F, "{\"text\":\"World Organization Of Human Protection\",\"color\":\"dark_blue\"}", 0.9F, 0, "fixed");
    }

    // ================================================================== les tubes d'arrivée

    private void tubesArrivee() {
        for (int i = 0; i < 3; i++) {
            int x0 = WoohpTrajet.TUBES_X[i], z0 = WoohpTrajet.TUBE_Z;
            // du verre clair, pour voir le logo à travers ; la couleur de l'espionne est dans les anneaux de lumière
            BlockState verre = Blocks.GLASS.defaultBlockState();
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    int d = dx * dx + dz * dz;
                    if (d > 6) {
                        continue;
                    }
                    boolean dedans = d <= 2;
                    // le pad rose au sol, et le trou dans le plafond par où l'on arrive
                    pose(x0 + dx, 0, z0 + dz, dedans ? (d == 0 ? Blocks.PINK_CONCRETE : Blocks.MAGENTA_CONCRETE).defaultBlockState() : Blocks.SMOOTH_QUARTZ.defaultBlockState());
                    if (dedans) {
                        pose(x0 + dx, HAUT, z0 + dz, AIR);
                        pose(x0 + dx, HAUT + 1, z0 + dz, AIR);
                        continue;
                    }
                    for (int y = 1; y < HAUT; y++) {
                        // l'ouverture, côté bureau, pour sortir du tube
                        boolean porte = dz == 2 && y <= 3;
                        pose(x0 + dx, y, z0 + dz, porte ? AIR : y == 4 || y == 8 ? LUMIERES[i].defaultBlockState() : verre);
                    }
                }
            }
            // le tapis rose, du tube jusqu'à l'allée
            for (int z = z0 + 3; z <= -3; z++) {
                pose(x0, 1, z, Blocks.MAGENTA_CARPET.defaultBlockState());
            }
            for (int x = Math.min(x0, 0); x <= Math.max(x0, 0); x++) {
                pose(x, 1, -3, Blocks.MAGENTA_CARPET.defaultBlockState());
            }
        }
        // l'allée rose jusqu'au tapis roulant
        for (int x = -1; x <= 1; x++) {
            for (int z = -3; z <= TAPIS_Z - 1; z++) {
                pose(x, 1, z, Blocks.MAGENTA_CARPET.defaultBlockState());
            }
        }
        // le bouton du retour, à côté des tubes
        pose(11, 1, -6, Labo.BOUTON_RETOUR.get().defaultBlockState());
        texte(11.5, 2.55, -5.5, 0F, "[{\"text\":\"RETOUR\\n\",\"color\":\"light_purple\",\"bold\":true},{\"text\":\"fin de mission\",\"color\":\"gray\",\"bold\":false}]",
                0.5F, 0, "vertical");
    }

    // ================================================================== le bureau de Jerry et le tapis roulant

    private void bureau() {
        // le tapis roulant, à hauteur de comptoir, le distributeur au départ et la butée au bout
        for (int x = TAPIS_X0; x <= TAPIS_X1; x++) {
            pose(x, 1, TAPIS_Z, Labo.TAPIS.get().defaultBlockState().setValue(LaboBlocs.TapisRoulant.FACING, Direction.EAST));
        }
        pose(TAPIS_X0 - 1, 1, TAPIS_Z, Blocks.DROPPER.defaultBlockState().setValue(DirectionalBlock.FACING, Direction.EAST));
        pose(TAPIS_X0 - 1, 2, TAPIS_Z, Blocks.SMOOTH_QUARTZ_SLAB.defaultBlockState());
        pose(TAPIS_X1 + 1, 1, TAPIS_Z, Blocks.SMOOTH_QUARTZ.defaultBlockState());
        pose(TAPIS_X1 + 1, 2, TAPIS_Z, Blocks.SMOOTH_QUARTZ.defaultBlockState());
        texte(0.5, 2.6, TAPIS_Z - 0.05, 180F, "{\"text\":\"GADGETS\",\"color\":\"light_purple\",\"bold\":true}", 0.6F, 0, "fixed");
        // le bureau : deux rangées d'un blanc métallisé, en couches
        for (int x = -7; x <= 7; x++) {
            pose(x, 1, TAPIS_Z + 1, Blocks.SMOOTH_QUARTZ.defaultBlockState());
            pose(x, 1, TAPIS_Z + 2, Blocks.POLISHED_DIORITE.defaultBlockState());
        }
        pose(-7, 1, TAPIS_Z + 3, Blocks.SMOOTH_QUARTZ.defaultBlockState());
        pose(7, 1, TAPIS_Z + 3, Blocks.SMOOTH_QUARTZ.defaultBlockState());
        // le coffre caché, glissé dans le bureau (il s'ouvre côté Jerry), et le fauteuil de Jerry
        pose(COFFRE.getX(), COFFRE.getY(), COFFRE.getZ(), Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.SOUTH));
        pose(JERRY.getX(), JERRY.getY(), JERRY.getZ(), vers(deco("handcrafted:dark_oak_chair", escalier(Blocks.DARK_OAK_STAIRS, Direction.SOUTH)), Direction.NORTH));
        texte(0.5, 1.55, TAPIS_Z + 0.95, 180F, "{\"text\":\"JERRY\",\"color\":\"dark_blue\",\"bold\":true}", 0.5F, 0, "fixed");
        // sur le bureau : des écrans, des piles de dossiers, des papiers partout, un globe, une tasse
        objet(-5, 2, TAPIS_Z + 2, "refurbished_furniture:computer", Direction.SOUTH);
        objet(5, 2, TAPIS_Z + 2, "refurbished_furniture:computer", Direction.SOUTH);
        for (int x : new int[] {-2, 2}) {
            pose(x, 2, TAPIS_Z + 2, Blocks.IRON_BARS.defaultBlockState());
            pose(x, 3, TAPIS_Z + 2, Blocks.BLACK_STAINED_GLASS_PANE.defaultBlockState());
        }
        objet(4, 2, TAPIS_Z + 1, "supplementaries:globe", Direction.NORTH);
        objet(-6, 2, TAPIS_Z + 1, "handcrafted:stackable_book", Direction.NORTH);
        objet(-4, 2, TAPIS_Z + 1, "supplementaries:book_pile", Direction.NORTH);
        objet(6, 2, TAPIS_Z + 2, "handcrafted:wood_cup", Direction.NORTH);
        objet(1, 2, TAPIS_Z + 1, "handcrafted:stackable_book", Direction.NORTH);
        pose(-1, 2, TAPIS_Z + 2, Blocks.LANTERN.defaultBlockState().setValue(LanternBlock.HANGING, false));
        for (int[] p : new int[][] {{-3, 1}, {-1, 1}, {0, 2}, {2, 1}, {3, 2}, {5, 1}, {-6, 2}, {6, 1}, {-2, 1}}) {
            if (level.getBlockState(c.offset(p[0], 2, TAPIS_Z + p[1])).isAir()) {
                cadre(p[0], 2, TAPIS_Z + p[1], Direction.UP, new ItemStack(rnd.nextInt(4) == 0 ? Items.MAP : Items.PAPER), true);
            }
        }
        for (int[] p : new int[][] {{-4, 11}, {3, 12}, {6, 11}, {-8, 9}}) {
            cadre(p[0], 1, p[1], Direction.UP, new ItemStack(Items.PAPER), true);
        }
        // derrière Jerry : le grand écran des missions, des classeurs, des plantes
        for (int x = -6; x <= 6; x++) {
            for (int y = 3; y <= 8; y++) {
                pose(x, y, Z - 1, (Math.abs(x) == 6 || y == 3 || y == 8 ? Blocks.GRAY_CONCRETE : Blocks.BLACK_CONCRETE).defaultBlockState());
            }
        }
        texte(0.5, 6.2, Z - 1.06, 180F, "[{\"text\":\"WOOHP\\n\",\"color\":\"light_purple\",\"bold\":true},{\"text\":\"NOUVELLE MISSION\\n\",\"color\":\"white\",\"bold\":false},{\"text\":\"en attente de Jerry…\",\"color\":\"gray\",\"bold\":false}]",
                1.6F, 0, "fixed");
        for (int x : new int[] {-12, -10, 10, 12}) {
            pose(x, 1, Z - 1, vers(deco("handcrafted:dark_oak_drawer", Blocks.BOOKSHELF.defaultBlockState()), Direction.NORTH));
            pose(x, 2, Z - 1, vers(deco("handcrafted:dark_oak_drawer", Blocks.BOOKSHELF.defaultBlockState()), Direction.NORTH));
        }
        for (int[] p : new int[][] {{-X + 1, -Z + 1}, {X - 1, -Z + 1}, {-X + 1, Z - 1}, {X - 1, Z - 1}, {-8, Z - 1}, {8, Z - 1}}) {
            pose(p[0], 1, p[1], Blocks.POTTED_FERN.defaultBlockState());
        }
        // deux canapés pour attendre la mission
        for (int z = -1; z <= 1; z++) {
            pose(-14, 1, z, vers(deco("handcrafted:white_couch", escalier(Blocks.QUARTZ_STAIRS, Direction.WEST)), Direction.EAST));
            pose(14, 1, z, vers(deco("handcrafted:white_couch", escalier(Blocks.QUARTZ_STAIRS, Direction.EAST)), Direction.WEST));
        }
    }

    // ================================================================== les toboggans, dans le vide

    private void toboggans() {
        Set<BlockPos> creux = new HashSet<>();
        for (int i = 0; i < 3; i++) {
            List<Vec3> points = WoohpTrajet.points(i);
            BlockState verre = VERRES[i].defaultBlockState(), lumiere = LUMIERES[i].defaultBlockState();
            double parcouru = 0;
            for (int k = 0; k < points.size(); k++) {
                Vec3 p = points.get(k).add(0.5, 0, 0.5);
                Vec3 suite = points.get(Math.min(points.size() - 1, k + 1)).add(0.5, 0, 0.5);
                Vec3 avant = points.get(Math.max(0, k - 1)).add(0.5, 0, 0.5);
                if (k > 0) {
                    parcouru += p.distanceTo(avant);
                }
                Vec3 t = suite.subtract(avant).normalize();
                Vec3 ref = Math.abs(t.y) > 0.9 ? new Vec3(1, 0, 0) : new Vec3(0, 1, 0);
                Vec3 n = t.cross(ref).normalize(), b = t.cross(n).normalize();
                boolean anneau = parcouru % 7 < 0.6;
                for (int a = 0; a < 28; a++) {
                    double angle = a * Math.PI * 2 / 28;
                    Vec3 q = p.add(n.scale(Math.cos(angle) * 1.7)).add(b.scale(Math.sin(angle) * 1.7));
                    BlockPos bp = BlockPos.containing(q.x, q.y, q.z);
                    if (!dansLaSalle(bp)) {
                        pose(bp.getX(), bp.getY(), bp.getZ(), anneau ? lumiere : verre);
                    }
                }
                // l'intérieur du tube, à vider à la fin (les anneaux voisins y débordent dans les virages)
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dy = -1; dy <= 1; dy++) {
                        for (int dz = -1; dz <= 1; dz++) {
                            Vec3 q = p.add(dx * 0.55, dy * 0.55, dz * 0.55);
                            creux.add(BlockPos.containing(q.x, q.y, q.z));
                        }
                    }
                }
            }
        }
        for (BlockPos p : creux) {
            if (!dansLaSalle(p)) {
                pose(p.getX(), p.getY(), p.getZ(), AIR);
            }
        }
    }

    /** La salle et son toit : les toboggans s'y arrêtent, les tubes d'arrivée prennent le relais. */
    private static boolean dansLaSalle(BlockPos p) {
        return Math.abs(p.getX()) <= X && Math.abs(p.getZ()) <= Z && p.getY() <= HAUT + 1;
    }


    // ================================================================== outils

    private void pose(int dx, int dy, int dz, BlockState state) {
        BlockPos p = c.offset(dx, dy, dz);
        level.setBlock(p, state, FLAGS);
        poses.add(p);
    }

    private void finitions() {
        for (BlockPos p : poses) {
            BlockState s = level.getBlockState(p);
            BlockState raccorde = Block.updateFromNeighbourShapes(s, level, p);
            if (raccorde != s && !raccorde.isAir()) {
                level.setBlock(p, raccorde, FLAGS);
            }
        }
    }

    private static BlockState modOuNull(String id) {
        Block b = BuiltInRegistries.BLOCK.get(ResourceLocation.parse(id));
        return b == Blocks.AIR ? null : b.defaultBlockState();
    }

    private static BlockState deco(String id, BlockState repli) {
        BlockState s = modOuNull(id);
        return s == null ? repli : s;
    }

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

    private void objet(int x, int y, int z, String id, Direction face) {
        BlockState s = modOuNull(id);
        if (s != null) {
            pose(x, y, z, face == null ? s : vers(s, face));
        }
    }

    private static BlockState escalier(Block block, Direction facing) {
        return block.defaultBlockState().setValue(StairBlock.FACING, facing);
    }

    private CompoundTag base(String id) {
        CompoundTag tag = new CompoundTag();
        tag.putString("id", id);
        ListTag tags = new ListTag();
        tags.add(StringTag.valueOf(TAG));
        tags.add(StringTag.valueOf(construction));
        tag.put("Tags", tags);
        return tag;
    }

    /** Un texte flottant ; {@code panneau} : « fixed » ou « vertical » (il tourne avec celui qui regarde). */
    private void texte(double x, double y, double z, float yaw, String json, float echelle, int fond, String panneau) {
        CompoundTag tag = base("minecraft:text_display");
        tag.putString("text", json);
        tag.putString("billboard", panneau);
        tag.putString("alignment", "center");
        tag.putInt("background", fond);
        tag.putInt("line_width", 400);
        CompoundTag t = new CompoundTag();
        t.put("left_rotation", floats(0, 0, 0, 1));
        t.put("right_rotation", floats(0, 0, 0, 1));
        t.put("translation", floats(0, 0, 0));
        t.put("scale", floats(echelle, echelle, echelle));
        tag.put("transformation", t);
        Entity e = EntityType.loadEntityRecursive(tag, level, ent -> {
            ent.moveTo(c.getX() + x, c.getY() + y, c.getZ() + z, yaw, 0F);
            return ent;
        });
        if (e != null) {
            level.addFreshEntity(e);
        }
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
