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
    static final int VERSION = 3;
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

    /** Le couloir des salles d'entraînement : il part de la porte du mur ouest et va jusqu'en x = COULOIR_FIN. */
    static final int COULOIR_FIN = -93;
    static final int COULOIR_Z = 3;
    static final int COULOIR_HAUT = 6;
    /** Du mur du couloir au mur du fond d'une salle. */
    static final int PROFONDEUR = 20;
    /** Jusqu'où vont les salles d'entraînement, de part et d'autre du couloir. */
    static final int AILE_Z = COULOIR_Z + PROFONDEUR;

    /**
     * Une salle d'entraînement, d'un côté ou de l'autre du couloir ({@code sens} -1 au nord, 1 au sud). Dedans, {@code u}
     * va de 0 à largeur - 1 en partant de son mur ouest, et {@code v} de 1 (contre le couloir) à PROFONDEUR - 1 (au fond).
     */
    record Salle(String nom, String commande, String couleur, Block accent, int ouest, int largeur, int sens, int haut) {
        int x(int u) {
            return ouest + 1 + u;
        }

        int z(int v) {
            return sens * (COULOIR_Z + v);
        }

        int milieu() {
            return largeur / 2;
        }

        /** Tourné vers le couloir (les textes des murs, les robots, les mannequins). */
        float lacet() {
            return sens < 0 ? 0F : 180F;
        }

        /** La face visible du mur en {@code v}, pour quelqu'un qui le regarde depuis le couloir. */
        double face(int v) {
            return sens < 0 ? z(v) + 1.05 : z(v) - 0.05;
        }

        Direction versLeFond() {
            return sens < 0 ? Direction.NORTH : Direction.SOUTH;
        }
    }

    static final Salle LASER = new Salle("STAND DE TIR", "Clic gauche : le laser", "red", Blocks.RED_CONCRETE, -39, 17, -1, 10);
    static final Salle SOUFFLE = new Salle("SOUFFLERIE", "Clic droit : le sèche-cheveux", "aqua", Blocks.LIGHT_BLUE_CONCRETE, -57, 17, -1, 10);
    static final Salle PARFUM = new Salle("PARFUM", "Clic droit : le parfum", "dark_purple", Blocks.PURPLE_CONCRETE, -75, 17, -1, 10);
    static final Salle GUM = new Salle("CHEWING-GUM", "Clic droit : la bulle", "light_purple", Blocks.PINK_CONCRETE, -93, 17, -1, 10);
    static final Salle LUNETTES = new Salle("LABYRINTHE", "Clic droit : les lunettes infrarouges", "gold", Blocks.ORANGE_CONCRETE, -39, 17, 1, 8);
    static final Salle GRAPPIN = new Salle("GRAPPIN", "Accroupi, un appui court : le grappin", "green", Blocks.LIME_CONCRETE, -57, 17, 1, 28);
    static final Salle TALONS = new Salle("TALONS", "Saut en l'air : le double saut", "yellow", Blocks.YELLOW_CONCRETE, -93, 35, 1, 16);
    static final Salle[] SALLES = {LASER, SOUFFLE, PARFUM, GUM, LUNETTES, GRAPPIN, TALONS};

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
        for (int cx = (c.getX() + COULOIR_FIN - 1) >> 4; cx <= (c.getX() + BORD_X) >> 4; cx++) {
            for (int cz = (c.getZ() - AILE_Z - 1) >> 4; cz <= (c.getZ() + BORD_Z1) >> 4; cz++) {
                level.getChunk(cx, cz);
            }
        }
        nettoyer();
        salle();
        logo();
        tubesArrivee();
        bureau();
        toboggans();
        entrainement();
        finitions();
        for (WoohpEntrainement.Poste p : postes()) {
            WoohpEntrainement.creer(level, p, construction);
        }
    }

    /** Toute l'aile des salles d'entraînement, murs compris. */
    static AABB zoneSalles() {
        return new AABB(abs(COULOIR_FIN - 1, -8, -AILE_Z - 1).getCenter(), abs(-X, 32, AILE_Z + 1).getCenter());
    }

    private void nettoyer() {
        AABB zone = new AABB(abs(-BORD_X, -20, BORD_Z0).getCenter(), abs(BORD_X, 50, BORD_Z1).getCenter());
        for (AABB z : new AABB[] {zone, zoneSalles()}) {
            for (Entity e : level.getEntities((Entity) null, z, e -> e.getTags().contains(TAG) || e instanceof ItemEntity)) {
                e.discard();
            }
        }
        vider(-BORD_X, BORD_X, -16, 45, BORD_Z0, BORD_Z1);
        vider(COULOIR_FIN - 1, -X - 1, -8, 32, -AILE_Z - 1, AILE_Z + 1);
    }

    private void vider(int x0, int x1, int y0, int y1, int z0, int z1) {
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                for (int y = y0; y <= y1; y++) {
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

    // ================================================================== les salles d'entraînement

    private static final BlockState BLANC = Blocks.WHITE_CONCRETE.defaultBlockState();
    private static final BlockState GRIS = Blocks.LIGHT_GRAY_CONCRETE.defaultBlockState();

    private void entrainement() {
        couloir();
        for (Salle s : SALLES) {
            coque(s);
        }
        standDeTir();
        soufflerie();
        enclos(PARFUM, 3, 13, 6, 17, Blocks.MOSS_BLOCK.defaultBlockState(), null, Blocks.BIRCH_FENCE.defaultBlockState());
        // loin de la barrière, sinon les bêtes montent dessus et sautent par-dessus
        for (int[] p : new int[][] {{6, 15}, {10, 8}}) {
            pose(PARFUM, p[0], 1, p[1], Blocks.HAY_BLOCK.defaultBlockState());
        }
        for (int[] p : new int[][] {{0, 1}, {16, 1}, {0, 19}, {16, 19}}) {
            pose(PARFUM, p[0], 1, p[1], Blocks.POTTED_ALLIUM.defaultBlockState());
        }
        enclos(GUM, 1, 15, 6, 18, Blocks.PINK_CONCRETE.defaultBlockState(), BLANC, Blocks.CHERRY_FENCE.defaultBlockState());
        labyrinthe();
        tourDuGrappin();
        parcoursDesTalons();
        for (Salle s : SALLES) {
            porte(s);
        }
    }

    /** La porte du QG (mur ouest), puis le couloir blanc, son trait rose au sol et ses lumières. */
    private void couloir() {
        for (int z = -1; z <= 1; z++) {
            for (int y = 1; y <= 4; y++) {
                pose(-X, y, z, AIR);
            }
        }
        texte(-X + 1.05, 5.3, 0.5, -90F, "[{\"text\":\"ENTRAÎNEMENT\\n\",\"color\":\"light_purple\",\"bold\":true},{\"text\":\"une salle par gadget\",\"color\":\"white\",\"bold\":false}]",
                0.8F, 0, "fixed");
        for (int x = COULOIR_FIN; x <= -X - 1; x++) {
            for (int z = -COULOIR_Z; z <= COULOIR_Z; z++) {
                pose(x, -1, z, Blocks.POLISHED_DEEPSLATE.defaultBlockState());
                pose(x, 0, z, z == 0 ? Blocks.MAGENTA_CONCRETE.defaultBlockState() : GRIS);
                boolean lampe = Math.abs(z) <= 1 && Math.floorMod(x, 4) == 0 && x > COULOIR_FIN;
                pose(x, COULOIR_HAUT, z, lampe ? Blocks.SEA_LANTERN.defaultBlockState() : BLANC);
                if (Math.abs(z) == COULOIR_Z || x == COULOIR_FIN) {
                    for (int y = 1; y < COULOIR_HAUT; y++) {
                        pose(x, y, z, y == 1 ? GRIS : BLANC);
                    }
                }
            }
        }
        texte(COULOIR_FIN + 1.05, 2.4, 0.5, -90F, "[{\"text\":\"WOOHP\\n\",\"color\":\"light_purple\",\"bold\":true},{\"text\":\"centre d'entraînement\",\"color\":\"gray\",\"bold\":false}]",
                1.2F, 0, "fixed");
    }

    /** Le sol (carrelé, bordé de la couleur du gadget), les murs, le plafond et ses lumières. */
    private void coque(Salle s) {
        for (int u = -1; u <= s.largeur(); u++) {
            for (int v = 0; v <= PROFONDEUR; v++) {
                boolean mur = u == -1 || u == s.largeur() || v == PROFONDEUR;
                boolean dedans = !mur && v > 0;
                pose(s, u, -1, v, Blocks.POLISHED_DEEPSLATE.defaultBlockState());
                boolean bord = u == 0 || u == s.largeur() - 1 || v == 1 || v == PROFONDEUR - 1;
                boolean carreau = (((u >> 1) + (v >> 1)) & 1) == 0;
                pose(s, u, 0, v, !dedans ? GRIS : bord ? s.accent().defaultBlockState() : carreau ? BLANC : GRIS);
                // le bas du mur du couloir est déjà fait (blanc des deux côtés)
                if (mur || v == 0) {
                    for (int y = mur ? 1 : COULOIR_HAUT; y < s.haut(); y++) {
                        pose(s, u, y, v, y == 1 ? GRIS : BLANC);
                    }
                }
                boolean lampe = dedans && Math.floorMod(u - s.milieu(), 4) == 0 && v % 4 == 2;
                pose(s, u, s.haut(), v, lampe ? Blocks.SEA_LANTERN.defaultBlockState() : BLANC);
            }
        }
    }

    /** Le passage du couloir à la salle, son nom au-dessus, et son nom en grand sur le mur du fond. */
    private void porte(Salle s) {
        int m = s.milieu();
        for (int u = m - 1; u <= m + 1; u++) {
            for (int y = 1; y <= 3; y++) {
                pose(s, u, y, 0, AIR);
            }
        }
        String json = "[{\"text\":\"" + s.nom() + "\\n\",\"color\":\"" + s.couleur() + "\",\"bold\":true},{\"text\":\"" + s.commande() + "\",\"color\":\"gray\",\"bold\":false}]";
        texte(s.x(m) + 0.5, 4.02, s.face(0), s.lacet(), json, 0.85F, 0, "fixed");
        texte(s.x(m) + 0.5, Math.min(s.haut() - 3.2, 6.5), s.face(PROFONDEUR), s.lacet(), json, 2.2F, 0, "fixed");
    }

    /** Le stand de tir : un comptoir, quatre couloirs séparés par des vitres, un robot et une cible peinte au bout de chacun. */
    private void standDeTir() {
        Salle s = LASER;
        for (int u = 2; u <= 14; u++) {
            pose(s, u, 1, 5, Blocks.SMOOTH_QUARTZ.defaultBlockState());
        }
        for (int u : new int[] {4, 8, 12}) {
            for (int v = 9; v < PROFONDEUR; v++) {
                for (int y = 1; y <= 2; y++) {
                    pose(s, u, y, v, Blocks.RED_STAINED_GLASS_PANE.defaultBlockState());
                }
            }
        }
        for (int u : ROBOTS) {
            for (int du = -1; du <= 1; du++) {
                for (int y = 2; y <= 4; y++) {
                    int d = Math.abs(du) + Math.abs(y - 3);
                    pose(s, u + du, y, PROFONDEUR, (d == 0 ? Blocks.TARGET : d == 1 ? Blocks.RED_CONCRETE : Blocks.WHITE_CONCRETE).defaultBlockState());
                }
            }
            for (int v = 6; v < PROFONDEUR; v++) {
                pose(s, u, 0, v, Blocks.RED_CONCRETE.defaultBlockState());
            }
        }
    }

    /** La soufflerie : une ligne de départ, des bandes tous les deux blocs pour voir jusqu'où ils volent, un mur capitonné. */
    private void soufflerie() {
        Salle s = SOUFFLE;
        for (int u = 0; u < s.largeur(); u++) {
            pose(s, u, 0, 5, Blocks.BLUE_CONCRETE.defaultBlockState());
            for (int v = 10; v < PROFONDEUR; v += 2) {
                pose(s, u, 0, v, (v % 4 == 2 ? Blocks.LIGHT_BLUE_CONCRETE : Blocks.CYAN_CONCRETE).defaultBlockState());
            }
            for (int y = 1; y <= 4; y++) {
                pose(s, u, y, PROFONDEUR, ((u + y) % 2 == 0 ? Blocks.WHITE_WOOL : Blocks.LIGHT_BLUE_WOOL).defaultBlockState());
            }
        }
    }

    /** Un enclos (de u0 à u1, de v0 à v1) : son sol, en damier si {@code sol2} n'est pas nul, et sa barrière. */
    private void enclos(Salle s, int u0, int u1, int v0, int v1, BlockState sol, BlockState sol2, BlockState barriere) {
        for (int u = u0; u <= u1; u++) {
            for (int v = v0; v <= v1; v++) {
                pose(s, u, 0, v, sol2 != null && (u + v) % 2 == 1 ? sol2 : sol);
                if (u == u0 || u == u1 || v == v0 || v == v1) {
                    pose(s, u, 1, v, barriere);
                }
            }
        }
    }

    /** Le labyrinthe noir, presque sans lumière : sans les lunettes, on ne voit pas les intrus cachés dans les culs-de-sac. */
    private void labyrinthe() {
        Salle s = LUNETTES;
        boolean[][] mur = labyrintheMurs();
        for (int u = 0; u < s.largeur(); u++) {
            for (int v = 1; v < PROFONDEUR; v++) {
                pose(s, u, s.haut(), v, Blocks.BLACK_CONCRETE.defaultBlockState());
            }
        }
        for (int v = 1; v < PROFONDEUR; v++) {
            for (int u = 0; u < s.largeur(); u++) {
                boolean bord = u == 0 || u == s.largeur() - 1 || v == 1 || v == PROFONDEUR - 1;
                pose(s, u, 0, v, (bord ? Blocks.ORANGE_CONCRETE : Blocks.GRAY_CONCRETE).defaultBlockState());
                if (mur[v - 1][u]) {
                    for (int y = 1; y <= 3; y++) {
                        pose(s, u, y, v, Blocks.POLISHED_BLACKSTONE_BRICKS.defaultBlockState());
                    }
                } else if (v % 4 == 3 && u % 4 == 1) {
                    // de toutes petites lumières invisibles, juste de quoi deviner les murs
                    pose(s, u, 3, v, Blocks.LIGHT.defaultBlockState().setValue(net.minecraft.world.level.block.LightBlock.LEVEL, 4));
                }
            }
        }
    }

    /** La tour du grappin : des corniches de plus en plus haut, un balcon tout en haut et sa cloche, un bassin pour redescendre. */
    private void tourDuGrappin() {
        Salle s = GRAPPIN;
        for (int u = 5; u <= 11; u++) {
            for (int v = 7; v <= 13; v++) {
                pose(s, u, -1, v, Blocks.PRISMARINE_BRICKS.defaultBlockState());
                pose(s, u, 0, v, Blocks.WATER.defaultBlockState());
            }
        }
        // {u0, u1, v0, v1, hauteur}
        for (int[] c : new int[][] {{0, 1, 5, 7, 5}, {15, 16, 9, 11, 10}, {7, 9, 18, 19, 15}, {0, 1, 12, 14, 20}}) {
            for (int u = c[0]; u <= c[1]; u++) {
                for (int v = c[2]; v <= c[3]; v++) {
                    pose(s, u, c[4], v, Blocks.LIME_CONCRETE.defaultBlockState());
                    pose(s, u, c[4] - 1, v, Blocks.SEA_LANTERN.defaultBlockState());
                }
            }
        }
        for (int u = 0; u < s.largeur(); u++) {
            for (int v = 16; v < PROFONDEUR; v++) {
                pose(s, u, 24, v, Blocks.SMOOTH_QUARTZ.defaultBlockState());
            }
            pose(s, u, 25, 16, Blocks.GLASS_PANE.defaultBlockState());
        }
        pose(s, s.milieu(), 25, 18, Blocks.BELL.defaultBlockState());
        texte(s.x(s.milieu()) + 0.5, 26.3, s.z(18) + 0.5, s.lacet(), "{\"text\":\"Sonne la cloche !\",\"color\":\"green\",\"bold\":true}", 0.6F, 0, "vertical");
        // des lumières dans les murs, tout du long (pas dans celui du labyrinthe, à côté, qui doit rester dans le noir)
        for (int y = 6; y < s.haut(); y += 6) {
            for (int v = 4; v < PROFONDEUR; v += 6) {
                pose(s, -1, y, v, Blocks.SEA_LANTERN.defaultBlockState());
            }
            for (int u = 3; u < s.largeur(); u += 5) {
                pose(s, u, y, PROFONDEUR, Blocks.SEA_LANTERN.defaultBlockState());
            }
        }
    }

    /** Le parcours des talons : des îlots de plus en plus hauts au-dessus d'une fosse, puis la tour d'où l'on saute. */
    private void parcoursDesTalons() {
        Salle s = TALONS;
        for (int u = -1; u <= s.largeur(); u++) {
            for (int v = 4; v <= PROFONDEUR; v++) {
                boolean mur = u == -1 || u == s.largeur() || v == PROFONDEUR || v == 4;
                pose(s, u, -5, v, Blocks.POLISHED_DEEPSLATE.defaultBlockState());
                pose(s, u, -4, v, mur ? GRIS : Blocks.BLUE_CONCRETE.defaultBlockState());
                for (int y = -3; y <= 0; y++) {
                    pose(s, u, y, v, mur ? (v == 4 ? GRIS : BLANC) : AIR);
                }
            }
        }
        // des échelles pour remonter de la fosse
        for (int u : new int[] {2, 17, 32}) {
            for (int y = -3; y <= 0; y++) {
                pose(s, u, y, 5, Blocks.LADDER.defaultBlockState().setValue(net.minecraft.world.level.block.LadderBlock.FACING, s.versLeFond()));
            }
        }
        // {u0, v0, côté u, côté v, hauteur du dessus}
        for (int[] i : new int[][] {{16, 9, 3, 3, 0}, {9, 9, 3, 3, 1}, {2, 9, 3, 3, 2}, {2, 16, 3, 3, 3}, {10, 16, 3, 3, 4},
                {18, 16, 3, 3, 5}, {26, 16, 3, 3, 6}, {31, 11, 3, 3, 7}, {31, 6, 3, 2, 8}, {31, 1, 4, 3, 9}}) {
            for (int u = i[0]; u < i[0] + i[2]; u++) {
                for (int v = i[1]; v < i[1] + i[3]; v++) {
                    for (int y = v <= 4 ? 1 : -3; y < i[4]; y++) {
                        pose(s, u, y, v, BLANC);
                    }
                    pose(s, u, i[4], v, Blocks.YELLOW_CONCRETE.defaultBlockState());
                }
            }
        }
        pose(s, 32, 9, 2, Blocks.GOLD_BLOCK.defaultBlockState());
        texte(s.x(32) + 1, 11.6, s.z(2) + 0.5, s.lacet(), "[{\"text\":\"BRAVO !\\n\",\"color\":\"yellow\",\"bold\":true},{\"text\":\"saute, les talons amortissent\",\"color\":\"white\",\"bold\":false}]",
                0.6F, 0, "vertical");
    }

    /** Les robots du stand de tir, au bout de chaque couloir. */
    private static final int[] ROBOTS = {2, 6, 10, 14};
    private static List<WoohpEntrainement.Poste> postes;
    private static boolean[][] labyrinthe;

    /** Tous les postes des salles (toujours les mêmes, dans le même ordre). */
    static List<WoohpEntrainement.Poste> postes() {
        if (postes != null) {
            return postes;
        }
        List<WoohpEntrainement.Poste> l = new ArrayList<>();
        for (int u : ROBOTS) {
            poste(l, LASER, WoohpEntrainement.Genre.ROBOT, EntityType.IRON_GOLEM, u, 16, null);
        }
        for (int u : new int[] {2, 5, 8, 11, 14}) {
            poste(l, SOUFFLE, WoohpEntrainement.Genre.MANNEQUIN, EntityType.ARMOR_STAND, u, 8, null);
        }
        // le dedans des enclos (sans la barrière)
        AABB parfum = enclos(PARFUM, 4, 12, 7, 16), gum = enclos(GUM, 2, 14, 7, 17);
        for (int[] p : new int[][] {{6, 9}, {10, 10}, {8, 13}}) {
            poste(l, PARFUM, WoohpEntrainement.Genre.COBAYE, EntityType.PIG, p[0], p[1], parfum);
        }
        for (int[] p : new int[][] {{5, 14}, {11, 14}, {8, 11}}) {
            poste(l, PARFUM, WoohpEntrainement.Genre.COBAYE, EntityType.SHEEP, p[0], p[1], parfum);
        }
        for (int[] p : new int[][] {{4, 9}, {12, 9}, {4, 16}, {12, 16}}) {
            poste(l, GUM, WoohpEntrainement.Genre.COBAYE, EntityType.CHICKEN, p[0], p[1], gum);
        }
        for (int[] p : new int[][] {{8, 10}, {6, 13}, {10, 15}}) {
            poste(l, GUM, WoohpEntrainement.Genre.COBAYE, EntityType.RABBIT, p[0], p[1], gum);
        }
        labyrintheMurs();
        for (int[] p : CACHETTES) {
            poste(l, LUNETTES, WoohpEntrainement.Genre.INTRUS, EntityType.PILLAGER, p[0], p[1], null);
        }
        postes = l;
        return l;
    }

    private static void poste(List<WoohpEntrainement.Poste> l, Salle s, WoohpEntrainement.Genre genre, EntityType<? extends net.minecraft.world.entity.LivingEntity> type,
            int u, int v, AABB enclos) {
        l.add(new WoohpEntrainement.Poste(l.size(), genre, type, s.x(u) + 0.5, 1, s.z(v) + 0.5, s.lacet(), enclos));
    }

    /** Un enclos (de u0 à u1, de v0 à v1, en blocs), en coordonnées du monde. */
    private static AABB enclos(Salle s, int u0, int u1, int v0, int v1) {
        BlockPos o = WoohpTrajet.ORIGINE;
        int za = s.z(v0), zb = s.z(v1);
        return new AABB(o.getX() + s.x(u0), o.getY(), o.getZ() + Math.min(za, zb), o.getX() + s.x(u1) + 1, o.getY() + 4, o.getZ() + Math.max(za, zb) + 1);
    }

    /** Les culs-de-sac les plus loin de l'entrée, où se cachent les intrus ({u, v}). */
    private static final List<int[]> CACHETTES = new ArrayList<>();

    /**
     * Le labyrinthe ([v - 1][u], vrai = mur), toujours le même : une allée libre contre le couloir, puis un mur percé d'une
     * seule entrée en face de la porte, et des allées d'un bloc creusées au hasard (avec une graine fixe).
     */
    static boolean[][] labyrintheMurs() {
        if (labyrinthe != null) {
            return labyrinthe;
        }
        int largeur = LUNETTES.largeur(), lignes = PROFONDEUR - 1;
        boolean[][] mur = new boolean[lignes][largeur];
        for (int r = 1; r < lignes; r++) {
            java.util.Arrays.fill(mur[r], true);
        }
        // les cases : lignes 2, 4… 16 et colonnes 1, 3… 15
        int nr = 8, nc = 8;
        boolean[][] vue = new boolean[nr][nc];
        int[][] distance = new int[nr][nc];
        java.util.Random hasard = new java.util.Random(0x5B1E5L);
        java.util.ArrayDeque<int[]> pile = new java.util.ArrayDeque<>();
        pile.push(new int[] {0, 3});
        vue[0][3] = true;
        mur[1][7] = false;
        mur[2][7] = false;
        int[][] pas = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        while (!pile.isEmpty()) {
            int[] ici = pile.peek();
            List<int[]> libres = new ArrayList<>();
            for (int[] d : pas) {
                int a = ici[0] + d[0], b = ici[1] + d[1];
                if (a >= 0 && a < nr && b >= 0 && b < nc && !vue[a][b]) {
                    libres.add(new int[] {a, b});
                }
            }
            if (libres.isEmpty()) {
                pile.pop();
                continue;
            }
            int[] la = libres.get(hasard.nextInt(libres.size()));
            vue[la[0]][la[1]] = true;
            distance[la[0]][la[1]] = distance[ici[0]][ici[1]] + 1;
            mur[2 + 2 * la[0]][1 + 2 * la[1]] = false;
            mur[2 + ici[0] + la[0]][1 + ici[1] + la[1]] = false;
            pile.push(la);
        }
        // les culs-de-sac (une seule sortie), du plus loin au plus près ; on en garde cinq
        List<int[]> fonds = new ArrayList<>();
        for (int a = 0; a < nr; a++) {
            for (int b = 0; b < nc; b++) {
                int r = 2 + 2 * a, col = 1 + 2 * b, sorties = 0;
                for (int[] d : pas) {
                    if (!mur[r + d[0]][col + d[1]]) {
                        sorties++;
                    }
                }
                if (sorties == 1 && !(a == 0 && b == 3)) {
                    fonds.add(new int[] {col, r + 1, distance[a][b]});
                }
            }
        }
        fonds.sort((p, q) -> q[2] - p[2]);
        for (int i = 0; i < Math.min(5, fonds.size()); i++) {
            CACHETTES.add(new int[] {fonds.get(i)[0], fonds.get(i)[1]});
        }
        labyrinthe = mur;
        return mur;
    }


    // ================================================================== outils

    private void pose(int dx, int dy, int dz, BlockState state) {
        BlockPos p = c.offset(dx, dy, dz);
        level.setBlock(p, state, FLAGS);
        poses.add(p);
    }

    private void pose(Salle s, int u, int y, int v, BlockState state) {
        pose(s.x(u), y, s.z(v), state);
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
