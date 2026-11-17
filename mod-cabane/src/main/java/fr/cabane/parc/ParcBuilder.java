package fr.cabane.parc;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Clearable;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LanternBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.LightBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Construit le parc d'après la maquette (docs/parc/maquette.html, mêmes coordonnées : x vers l'est, z vers le sud,
 * y = 0 au ras du sol) : la place et sa fontaine, la grande avenue et ses stands, l'arche de l'entrée, la haie tout
 * autour, les arbres et les fleurs, et les manèges déjà là. Pour l'instant : Le Dragon (sa gare, ses piliers, son
 * tunnel et la tête de dragon de l'entrée du tunnel). Les rails eux-mêmes sont dessinés par le client (ClientParc).
 */
final class ParcBuilder {
    /** À changer quand on modifie la construction : le parc se reconstruit à la visite suivante. */
    static final int VERSION = 11;
    static final int SOL = Maneges.SOL;
    static final Vec3 ARRIVEE = new Vec3(0.5, SOL, 114.5);
    static final String TAG = "parc";
    private static final int X0 = -224, X1 = 166, Z0 = -184, Z1 = 124, HAUT = 80;
    private static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;

    final ServerLevel level;
    final String construction;
    /** Les cases déjà prises (allées, manèges…), pour poser les arbres ailleurs. Une case = 2 × 2 blocs. */
    private final boolean[][] pris = new boolean[(X1 - X0) / 2 + 20][(Z1 - Z0) / 2 + 20];
    private long graine = 9137;

    private ParcBuilder(ServerLevel level, int numero) {
        this.level = level;
        this.construction = tagConstruction(numero);
    }

    static void construire(ServerLevel level, int numero) {
        new ParcBuilder(level, numero).tout();
    }

    static String tagConstruction(int numero) {
        return "parc.n" + numero;
    }

    private void tout() {
        for (int cx = (X0 - 8) >> 4; cx <= (X1 + 8) >> 4; cx++) {
            for (int cz = (Z0 - 8) >> 4; cz <= (Z1 + 24) >> 4; cz++) {
                level.getChunk(cx, cz);
            }
        }
        nettoyer();
        place();
        avenue();
        entree();
        dragon();
        new ParcBuilder2(this).tout();
        new ParcBuilder3(this).tout();
        chemins();
        garnir();
        haie();
        arbres();
        gens();
    }

    // ================================================================== outils

    double alea() {
        graine = (graine * 16807) % 2147483647;
        return (graine - 1) / 2147483646.0;
    }

    void bloc(int x, int y, int z, BlockState s) {
        level.setBlock(new BlockPos(x, SOL + y, z), s, FLAGS);
    }

    void bloc(int x, int y, int z, Block b) {
        bloc(x, y, z, b.defaultBlockState());
    }

    void remplir(int x0, int y0, int z0, int x1, int y1, int z1, BlockState s) {
        for (int x = Math.min(x0, x1); x <= Math.max(x0, x1); x++) {
            for (int y = Math.min(y0, y1); y <= Math.max(y0, y1); y++) {
                for (int z = Math.min(z0, z1); z <= Math.max(z0, z1); z++) {
                    bloc(x, y, z, s);
                }
            }
        }
    }

    void remplir(int x0, int y0, int z0, int x1, int y1, int z1, Block b) {
        remplir(x0, y0, z0, x1, y1, z1, b.defaultBlockState());
    }

    void occuper(double x, double z, double r) {
        for (double i = x - r; i <= x + r; i += 1) {
            for (double j = z - r; j <= z + r; j += 1) {
                if ((i - x) * (i - x) + (j - z) * (j - z) <= r * r + 1) {
                    int ci = (int) Math.floor((i - X0) / 2), cj = (int) Math.floor((j - Z0) / 2);
                    if (ci >= 0 && cj >= 0 && ci < pris.length && cj < pris[0].length) {
                        pris[ci][cj] = true;
                    }
                }
            }
        }
    }

    private boolean libre(double x, double z) {
        int ci = (int) Math.floor((x - X0) / 2), cj = (int) Math.floor((z - Z0) / 2);
        return ci >= 0 && cj >= 0 && ci < pris.length && cj < pris[0].length && !pris[ci][cj];
    }

    /** Une bande de sol (une allée) entre deux points, au ras du sol. */
    void allee(double ax, double az, double bx, double bz, double larg, BlockState s) {
        double l = Math.hypot(bx - ax, bz - az);
        for (double t = 0; t <= l; t += 0.5) {
            double x = ax + (bx - ax) * t / l, z = az + (bz - az) * t / l;
            for (double i = -larg / 2; i <= larg / 2; i += 0.5) {
                double px = x - (bz - az) / l * i, pz = z + (bx - ax) / l * i;
                bloc((int) Math.floor(px), -1, (int) Math.floor(pz), s);
            }
            occuper(x, z, larg / 2 + 1.5);
        }
    }

    /** Un texte posé dans le décor (un panneau), lisible depuis la direction {@code lacet} (0 : du sud, 180 : du nord). */
    void texte(double x, double y, double z, float lacet, String json, float echelle, int fond) {
        Display.TextDisplay t = EntityType.TEXT_DISPLAY.create(level);
        if (t == null) {
            return;
        }
        CompoundTag tag = new CompoundTag();
        t.saveWithoutId(tag);
        tag.putString("text", json);
        tag.putInt("background", fond);
        tag.putString("billboard", "fixed");
        tag.putInt("line_width", 400);
        CompoundTag tr = new CompoundTag();
        tr.put("left_rotation", liste(0, 0, 0, 1));
        tr.put("right_rotation", liste(0, 0, 0, 1));
        tr.put("translation", liste(0, 0, 0));
        tr.put("scale", liste(echelle, echelle, echelle));
        tag.put("transformation", tr);
        CompoundTag lum = new CompoundTag();
        lum.putInt("block", 15);
        lum.putInt("sky", 15);
        tag.put("brightness", lum);
        t.load(tag);
        t.moveTo(x, SOL + y, z, lacet, 0F);
        t.addTag(TAG);
        t.addTag(construction);
        level.addFreshEntity(t);
    }

    private static ListTag liste(float... v) {
        ListTag t = new ListTag();
        for (float f : v) {
            t.add(FloatTag.valueOf(f));
        }
        return t;
    }

    static String json(String texte, String couleur) {
        return "{\"text\":\"" + texte + "\",\"color\":\"" + couleur + "\",\"bold\":true}";
    }

    static BlockState feuilles(Block b) {
        return b.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true);
    }

    // ================================================================== nettoyage

    private void nettoyer() {
        AABB zone = new AABB(X0 - 8, SOL - 8, Z0 - 8, X1 + 8, SOL + HAUT, Z1 + 24);
        for (Entity e : level.getEntities((Entity) null, zone, e -> e.getTags().contains(TAG) || e instanceof ItemEntity)) {
            e.discard();
        }
        BlockState herbe = Blocks.GRASS_BLOCK.defaultBlockState(), terre = Blocks.DIRT.defaultBlockState();
        for (int x = X0 - 6; x <= X1 + 6; x++) {
            for (int z = Z0 - 6; z <= Z1 + 22; z++) {
                for (int y = -4; y < HAUT; y++) {
                    BlockPos p = new BlockPos(x, SOL + y, z);
                    BlockState voulu = y == -1 ? herbe : y < -1 ? terre : null;
                    BlockState ici = level.getBlockState(p);
                    if (voulu == null ? !ici.isAir() : ici != voulu) {
                        Clearable.tryClear(level.getBlockEntity(p));
                        level.setBlock(p, voulu == null ? Blocks.AIR.defaultBlockState() : voulu, FLAGS);
                    }
                }
            }
        }
    }

    // ================================================================== la place et la fontaine

    private void place() {
        for (int x = -22; x <= 22; x++) {
            for (int z = -22; z <= 22; z++) {
                double d = Math.sqrt(x * x + z * z);
                if (d > 21) {
                    continue;
                }
                Block b = d > 15 && d <= 16.2 ? Blocks.CUT_RED_SANDSTONE : d <= 12 && ((x + z) & 1) == 0 ? Blocks.CUT_SANDSTONE : Blocks.SMOOTH_SANDSTONE;
                bloc(x, -1, z, b);
            }
        }
        occuper(0, 0, 21);
        // la fontaine : un grand bassin, une vasque au milieu, une petite tout en haut
        for (int x = -8; x <= 8; x++) {
            for (int z = -8; z <= 8; z++) {
                double d = Math.sqrt(x * x + z * z);
                if (d <= 7.3 && d > 6.3) {
                    bloc(x, 0, z, Blocks.QUARTZ_BLOCK);
                } else if (d <= 6.3) {
                    bloc(x, -1, z, Blocks.PRISMARINE_BRICKS);
                    bloc(x, 0, z, Blocks.WATER);
                }
            }
        }
        remplir(0, 0, 0, 0, 2, 0, Blocks.QUARTZ_PILLAR);
        for (int x = -3; x <= 3; x++) {
            for (int z = -3; z <= 3; z++) {
                double d = Math.sqrt(x * x + z * z);
                if (d <= 3.2 && d > 2.2) {
                    bloc(x, 3, z, Blocks.QUARTZ_BLOCK);
                } else if (d <= 2.2) {
                    bloc(x, 2, z, Blocks.QUARTZ_BLOCK);
                    bloc(x, 3, z, Blocks.WATER);
                }
            }
        }
        remplir(0, 3, 0, 0, 5, 0, Blocks.QUARTZ_PILLAR);
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
                bloc(x, 6, z, x == 0 && z == 0 ? Blocks.WATER : Blocks.QUARTZ_SLAB);
            }
        }
        // les massifs de fleurs et les bancs
        Block[] fleurs = {Blocks.POPPY, Blocks.DANDELION, Blocks.ALLIUM, Blocks.OXEYE_DAISY, Blocks.CORNFLOWER, Blocks.PINK_TULIP, Blocks.ORANGE_TULIP};
        for (int k = 0; k < 8; k++) {
            double a = (k + 0.5) / 8 * Math.PI * 2;
            int cx = (int) Math.round(Math.cos(a) * 14), cz = (int) Math.round(Math.sin(a) * 14);
            for (int x = -2; x <= 2; x++) {
                for (int z = -2; z <= 2; z++) {
                    if (x * x + z * z <= 5) {
                        bloc(cx + x, -1, cz + z, Blocks.GRASS_BLOCK);
                        bloc(cx + x, 0, cz + z, fleurs[(int) (alea() * fleurs.length)]);
                    }
                }
            }
        }
        for (int k = 0; k < 8; k++) {
            double a = k / 8.0 * Math.PI * 2;
            int bx = (int) Math.round(Math.cos(a) * 19.5), bz = (int) Math.round(Math.sin(a) * 19.5);
            Direction vers = Math.abs(Math.cos(a)) > Math.abs(Math.sin(a)) ? (Math.cos(a) > 0 ? Direction.WEST : Direction.EAST) : (Math.sin(a) > 0 ? Direction.NORTH : Direction.SOUTH);
            banc(bx, bz, vers);
        }
    }

    /** Un banc (deux marches de bois) tourné vers {@code vers}. */
    private void banc(int x, int z, Direction vers) {
        BlockState s = Blocks.OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, vers.getOpposite());
        Direction cote = vers.getClockWise();
        bloc(x, 0, z, s);
        bloc(x + cote.getStepX(), 0, z + cote.getStepZ(), s);
    }

    void lampadaire(int x, int z) {
        remplir(x, 0, z, x, 3, z, Blocks.DARK_OAK_FENCE);
        bloc(x, 4, z, Blocks.LANTERN.defaultBlockState().setValue(LanternBlock.HANGING, false));
        occuper(x, z, 1);
    }

    /** Une fontaine qui coule : des gouttes et des éclaboussures en haut des vasques (appelé tous les 4 ticks). */
    static void fontaine(ServerLevel level) {
        level.sendParticles(ParticleTypes.SPLASH, 0.5, SOL + 7.2, 0.5, 14, 0.25, 0.4, 0.25, 0.25);
        level.sendParticles(ParticleTypes.FALLING_WATER, 0.5, SOL + 6.4, 0.5, 6, 1.4, 0.1, 1.4, 0);
        level.sendParticles(ParticleTypes.FALLING_WATER, 0.5, SOL + 3.6, 0.5, 8, 2.6, 0.1, 2.6, 0);
        level.sendParticles(ParticleTypes.SPLASH, 0.5, SOL + 1.0, 0.5, 10, 4.5, 0.1, 4.5, 0.1);
    }

    // ================================================================== l'avenue, les stands, l'entrée

    private void avenue() {
        for (int z = 19; z <= 145; z++) {
            for (int x = -6; x <= 5; x++) {
                bloc(x, -1, z, (x == -6 || x == 5) && z % 4 < 2 ? Blocks.CUT_SANDSTONE : Blocks.SMOOTH_SANDSTONE);
            }
            occuper(0, z, 8);
        }
        stand(-12, 48, true, "BARBE À PAPA", "#ff5c8a", Blocks.PINK_WOOL);
        stand(11, 48, false, "POPCORN", "#e8333f", Blocks.RED_WOOL);
        stand(-12, 66, true, "CHURROS", "#f39c12", Blocks.ORANGE_WOOL);
        stand(11, 66, false, "GLACES", "#2ec4b6", Blocks.CYAN_WOOL);
        stand(-12, 96, true, "CRÊPES", "#9b59b6", Blocks.PURPLE_WOOL);
        stand(11, 96, false, "BOISSONS", "#3498db", Blocks.LIGHT_BLUE_WOOL);
        stand(-12, 108, true, "BONBONS", "#e84393", Blocks.MAGENTA_WOOL);
        stand(11, 108, false, "HOT-DOGS", "#d35400", Blocks.ORANGE_WOOL);
    }

    /** Un stand de bouffe le long de l'avenue, ouvert vers l'avenue (à l'est s'il est à l'ouest de l'avenue). */
    private void stand(int x, int z, boolean ouest, String nom, String couleur, Block laine) {
        int dos = ouest ? x - 2 : x + 2, devant = ouest ? x + 2 : x - 2, s = ouest ? 1 : -1;
        remplir(dos, 0, z - 2, dos, 3, z + 2, Blocks.WHITE_TERRACOTTA);
        remplir(devant, 0, z - 2, devant, 0, z + 2, laine);
        for (int dz = -2; dz <= 2; dz++) {
            bloc(devant, 1, z + dz, Blocks.SMOOTH_STONE_SLAB);
            for (int i = 0; i <= 4; i++) {
                bloc(dos + s * i, 4, z + dz, ((dz + 2) & 1) == 0 ? laine : Blocks.WHITE_WOOL);
            }
        }
        remplir(devant, 1, z - 2, devant, 3, z - 2, Blocks.BIRCH_FENCE);
        remplir(devant, 1, z + 2, devant, 3, z + 2, Blocks.BIRCH_FENCE);
        texte(devant + 0.5 + s * 0.52, 4.15, z + 0.5, ouest ? -90F : 90F, json(nom, "#ffffff"), 1.3F, 0xDD000000 | Integer.parseInt(couleur.substring(1), 16));
        occuper(x, z, 4);
    }

    private void entree() {
        for (int s : new int[]{-1, 1}) {
            int cx = s * 10;
            for (int y = 0; y < 15; y++) {
                remplir(cx - 2, y, 120, cx + 2, y, 124, y % 3 == 2 ? Blocks.PINK_CONCRETE : Blocks.WHITE_CONCRETE);
            }
            remplir(cx - 3, 15, 119, cx + 3, 15, 125, Blocks.CYAN_CONCRETE);
            for (int k = 0; k < 4; k++) {
                remplir(cx - 2 + k / 2, 16 + k, 120 + k / 2, cx + 2 - k / 2, 16 + k, 124 - k / 2, Blocks.PINK_CONCRETE);
            }
            remplir(cx, 20, 122, cx, 22, 122, Blocks.END_ROD);
            // guichet
            int g0 = s * 16 - (s > 0 ? 2 : 2), g1 = g0 + 3;
            remplir(g0, 0, 121, g1, 2, 124, Blocks.WHITE_TERRACOTTA);
            remplir(g0 + 1, 0, 122, g1 - 1, 2, 123, Blocks.AIR);
            remplir(g0 + 1, 1, 124, g1 - 1, 1, 124, Blocks.GLASS_PANE);
            remplir(g0 + 1, 0, 124, g1 - 1, 0, 124, Blocks.SMOOTH_QUARTZ);
            remplir(g0, 3, 120, g1, 3, 125, Blocks.CYAN_CONCRETE);
            texte(g0 + 2, 3.05, 126.05, 0F, json("BILLETS", "#ffffff"), 0.9F, 0xDD2EC4B6);
            occuper(cx, 122, 5);
            occuper(s * 16, 123, 4);
        }
        remplir(-7, 16, 121, 7, 19, 123, Blocks.CYAN_CONCRETE);
        texte(0.5, 16.6, 124.1, 0F, json("PARC DE LA CABANE", "#ffffff"), 3.2F, 0xFFFF5C8A);
        texte(0.5, 16.6, 120.9, 180F, json("PARC DE LA CABANE", "#ffffff"), 3.2F, 0xFFFF5C8A);
        for (int x = -4; x <= 4; x += 2) {
            remplir(x, 0, 121, x, 0, 123, Blocks.IRON_BARS);
        }
        // le bouton du retour, juste à droite de l'arrivée
        bloc(3, -1, 116, Blocks.POLISHED_ANDESITE);
        bloc(3, 0, 116, Parc.BOUTON.get());
    }

    // ================================================================== Le Dragon

    private void dragon() {
        Trajet t = Maneges.trajet(Maneges.DRAGON);
        Trajet.Point p = new Trajet.Point();
        for (double s = 0; s < t.total; s += 1.5) {
            t.a(s, p);
            occuper(p.x, p.z, 3);
        }
        // la gare : le quai à l'ouest du rail (le dessus au niveau du rail), le toit vert et jaune, le nom
        remplir(116, 0, 30, 118, 3, 59, Blocks.SMOOTH_STONE);
        remplir(116, 3, 30, 116, 3, 59, Blocks.YELLOW_CONCRETE);
        for (int z = 30; z <= 59; z += 6) {
            remplir(115, 0, z, 115, 8, z, Blocks.DARK_OAK_LOG);
            remplir(124, 0, z, 124, 8, z, Blocks.DARK_OAK_LOG);
        }
        remplir(114, 9, 29, 125, 9, 60, Blocks.GREEN_CONCRETE);
        remplir(113, 10, 29, 126, 10, 60, Blocks.LIME_CONCRETE);
        for (int z = 29; z <= 60; z++) {
            bloc(113, 9, z, Blocks.YELLOW_CONCRETE);
            bloc(126, 9, z, Blocks.YELLOW_CONCRETE);
        }
        texte(112.9, 11.2, 45.5, 90F, json("LE DRAGON", "#ffd23f"), 4F, 0xFF2D8A4E);
        // l'escalier qui monte au quai, depuis l'allée
        for (int k = 0; k < 4; k++) {
            remplir(114 - k, 0, 43, 114 - k, 2 - k, 47, Blocks.SMOOTH_STONE);
            remplir(114 - k, 3 - k, 43, 114 - k, 3 - k, 47, Blocks.SMOOTH_STONE_SLAB.defaultBlockState());
            remplir(115, 0, 43, 115, 3, 47, Blocks.SMOOTH_STONE);
        }
        // les piliers sous le rail (pas sous le quai, pas dans le tunnel, pas quand le rail penche trop)
        for (double s = 0; s < t.total; s += 4) {
            t.a(s, p);
            if (p.uy < 0.5 || p.y < SOL + 2.6 || p.noir || (p.mode == Trajet.GARE && p.z > 28 && p.z < 61)) {
                continue;
            }
            int bx = (int) Math.floor(p.x - p.ux * 0.7), bz = (int) Math.floor(p.z - p.uz * 0.7);
            int haut = (int) Math.floor(p.y - p.uy * 0.7) - SOL - 1;
            remplir(bx, 0, bz, bx, haut, bz, Blocks.WHITE_CONCRETE);
            bloc(bx, -1, bz, Blocks.SMOOTH_STONE);
        }
        // les deux pieds du looping
        Vec3 sommet = sommetBoucle(t);
        for (int s = -1; s <= 1; s += 2) {
            Vec3 a = new Vec3(sommet.x + s * 11, SOL, sommet.z), b = new Vec3(sommet.x + s * 1.6, sommet.y - 0.6, sommet.z);
            for (double k = 0; k <= 1; k += 0.01) {
                Vec3 q = a.lerp(b, k);
                level.setBlock(BlockPos.containing(q), Blocks.WHITE_CONCRETE.defaultBlockState(), FLAGS);
            }
        }
        tunnelDragon(t);
    }

    /** Le haut du looping (le point le plus haut entre le plongeon et le premier virage). */
    private static Vec3 sommetBoucle(Trajet t) {
        Trajet.Point p = new Trajet.Point();
        Vec3 m = Vec3.ZERO;
        for (double s = 0; s < t.total; s += 0.5) {
            t.a(s, p);
            if (p.uy < -0.95 && p.y > m.y) {
                m = new Vec3(p.x, p.y, p.z);
            }
        }
        return m;
    }

    /** Le tunnel noir du dragon (les anneaux de lumière sont dessinés par le client) et la tête qui l'avale. */
    private void tunnelDragon(Trajet t) {
        Trajet.Point p = new Trajet.Point();
        double debut = -1, fin = -1;
        for (double s = 0; s < t.total; s += 0.5) {
            t.a(s, p);
            if (p.noir) {
                if (debut < 0) {
                    debut = s;
                }
                fin = s;
            }
        }
        Block[] ecailles = {Blocks.GREEN_TERRACOTTA, Blocks.MOSS_BLOCK, Blocks.GREEN_CONCRETE, Blocks.GREEN_TERRACOTTA};
        for (double s = debut; s <= fin; s += 0.5) {
            t.a(s, p);
            double rx = p.uy * p.fz - p.uz * p.fy, rz = p.ux * p.fy - p.uy * p.fx;
            for (double w = -3.5; w <= 3.5; w += 0.5) {
                for (double h = -1.5; h <= 6.5; h += 0.5) {
                    boolean dedans = Math.abs(w) < 2.9 && h > -0.6 && h < 5.4;
                    BlockPos q = BlockPos.containing(p.x + rx * w, p.y + h, p.z + rz * w);
                    if (dedans) {
                        level.setBlock(q, Blocks.AIR.defaultBlockState(), FLAGS);
                    } else if (level.getBlockState(q).isAir() || q.getY() < SOL) {
                        level.setBlock(q, ecailles[(int) (alea() * ecailles.length)].defaultBlockState(), FLAGS);
                    }
                }
            }
        }
        // un peu de lumière là où passent les anneaux (sinon on ne voit rien du tout)
        for (double s = debut + 1.5; s <= fin; s += 6) {
            t.a(s, p);
            level.setBlock(BlockPos.containing(p.x, p.y + 4.6, p.z), Blocks.LIGHT.defaultBlockState().setValue(LightBlock.LEVEL, 7), FLAGS);
        }
        // la tête du dragon autour de l'entrée du tunnel (le train entre par le nord, en allant vers le sud)
        t.a(debut, p);
        int cx = (int) Math.floor(p.x), cz = (int) Math.floor(p.z) - 1, y0 = (int) Math.floor(p.y) - SOL - 1;
        for (int x = -5; x <= 5; x++) {
            for (int y = 0; y <= 11; y++) {
                for (int z = -3; z <= 0; z++) {
                    boolean bouche = Math.abs(x) <= 2 && y >= 1 && y <= 6;
                    if (!bouche) {
                        bloc(cx + x, y0 + y, cz + z, y >= 9 ? Blocks.GREEN_CONCRETE : Blocks.LIME_TERRACOTTA);
                    }
                }
            }
        }
        // le museau, les yeux, les dents, les cornes
        remplir(cx - 4, y0 + 6, cz - 6, cx + 4, y0 + 8, cz - 4, Blocks.LIME_TERRACOTTA);
        for (int s : new int[]{-1, 1}) {
            remplir(cx + s * 3, y0 + 9, cz - 4, cx + s * 2, y0 + 10, cz - 4, Blocks.SHROOMLIGHT);
            bloc(cx + s * 2, y0 + 9, cz - 5, Blocks.BLACK_CONCRETE);
            remplir(cx + s * 3, y0 + 12, cz - 1, cx + s * 3, y0 + 14, cz - 1, Blocks.BONE_BLOCK);
            bloc(cx + s * 4, y0 + 15, cz - 1, Blocks.BONE_BLOCK);
            bloc(cx + s * 3, y0 + 7, cz - 7, Blocks.BLACK_CONCRETE);
            for (int y = 1; y <= 4; y++) {
                if (y % 2 == 1) {
                    bloc(cx + s * 2, y0 + y, cz - 4, Blocks.QUARTZ_BLOCK);
                }
            }
        }
        for (int x = -2; x <= 2; x += 2) {
            bloc(cx + x, y0 + 6, cz - 4, Blocks.QUARTZ_BLOCK);
        }
        for (double s = debut; s <= fin; s += 1) {
            t.a(s, p);
            occuper(p.x, p.z, 5);
        }
    }

    // ================================================================== les allées, la haie, les arbres

    private void chemins() {
        BlockState sable = Blocks.SMOOTH_SANDSTONE.defaultBlockState();
        // vers la gare du Dragon : on passe sous le grand huit
        double[] l = Guirlandes.ALLEE_DRAGON;
        for (int i = 0; i + 3 < l.length; i += 2) {
            allee(l[i], l[i + 1], l[i + 2], l[i + 3], 4.4, sable);
        }
    }

    // ================================================================== la fête : lampadaires, bancs, massifs, mâts

    /** Les bannières des lampadaires, à tour de rôle. */
    private static final Block[] BANNIERES = {Blocks.RED_WALL_BANNER, Blocks.YELLOW_WALL_BANNER, Blocks.LIGHT_BLUE_WALL_BANNER,
            Blocks.LIME_WALL_BANNER, Blocks.MAGENTA_WALL_BANNER, Blocks.ORANGE_WALL_BANNER, Blocks.CYAN_WALL_BANNER, Blocks.PINK_WALL_BANNER};

    /** Un coin d'herbe libre (rien dessus jusqu'à {@code haut} blocs) : pas une allée, pas un manège. */
    private boolean herbe(int x, int z, int haut) {
        if (!level.getBlockState(new BlockPos(x, SOL - 1, z)).is(Blocks.GRASS_BLOCK)) {
            return false;
        }
        for (int y = 0; y <= haut; y++) {
            if (!level.getBlockState(new BlockPos(x, SOL + y, z)).isAir()) {
                return false;
            }
        }
        return true;
    }

    private void garnir() {
        // les lampadaires (là où il y a de la place), chacun avec sa bannière tournée vers l'allée
        for (Guirlandes.Lampe l : Guirlandes.LAMPES) {
            if (!herbe(l.x(), l.z(), 4) || !level.getBlockState(new BlockPos(l.x() + l.dx(), SOL + 3, l.z() + l.dz())).isAir()) {
                continue;
            }
            bloc(l.x(), 0, l.z(), Blocks.POLISHED_BLACKSTONE_WALL);
            remplir(l.x(), 1, l.z(), l.x(), 3, l.z(), Blocks.DARK_OAK_FENCE);
            bloc(l.x(), 4, l.z(), Blocks.LANTERN.defaultBlockState().setValue(LanternBlock.HANGING, false));
            Direction vers = Direction.fromDelta(l.dx(), 0, l.dz());
            if (vers != null) {
                bloc(l.x() + l.dx(), 3, l.z() + l.dz(), BANNIERES[l.couleur() % BANNIERES.length].defaultBlockState()
                        .setValue(net.minecraft.world.level.block.WallBannerBlock.FACING, vers));
            }
            occuper(l.x(), l.z(), 1);
        }
        // le long de l'avenue : des massifs d'azalée en fleurs, des bancs, des poubelles (pas devant les stands)
        for (int z = 22; z <= 117; z++) {
            boolean stand = false;
            for (int[] st : Guirlandes.STANDS) {
                stand |= Math.abs(z - st[1]) <= 3;
            }
            if (stand) {
                continue;
            }
            for (int s = -1; s <= 1; s += 2) {
                int bord = s < 0 ? -7 : 6, loin = s < 0 ? -8 : 7;
                if (z % 6 == 3 && herbe(loin, z, 1)) {
                    bloc(loin, 0, z, Blocks.FLOWERING_AZALEA);
                } else if (z % 12 == 7 && herbe(bord, z, 0) && herbe(bord, z + 1, 0)) {
                    BlockState banc = Blocks.SPRUCE_STAIRS.defaultBlockState().setValue(StairBlock.FACING, s < 0 ? Direction.WEST : Direction.EAST);
                    bloc(bord, 0, z, banc);
                    bloc(bord, 0, z + 1, banc);
                } else if (z % 12 == 10 && herbe(bord, z, 0)) {
                    bloc(bord, 0, z, Blocks.CAULDRON);
                }
            }
        }
        // les mâts des drapeaux (les drapeaux flottent chez le client) : sur la place, au milieu des massifs
        for (int[] d : Guirlandes.DRAPEAUX) {
            if (d[2] == Guirlandes.MAT) {
                remplir(d[0], 0, d[1], d[0], d[2] - 1, d[1], Blocks.IRON_BARS);
                bloc(d[0], d[2], d[1], Blocks.GOLD_BLOCK);
            }
        }
    }

    private void haie() {
        BlockState f = feuilles(Blocks.OAK_LEAVES);
        for (int x = X0; x <= X1; x++) {
            remplir(x, 0, Z0, x, 1, Z0, f);
            if (Math.abs(x) > 19) {
                remplir(x, 0, Z1, x, 1, Z1, f);
            }
        }
        for (int z = Z0; z <= Z1; z++) {
            remplir(X0, 0, z, X0, 1, z, f);
            remplir(X1, 0, z, X1, 1, z, f);
        }
    }

    private void arbres() {
        int poses = 0;
        for (int i = 0; i < 9000 && poses < 300; i++) {
            double x = X0 + 4 + alea() * (X1 - X0 - 8), z = Z0 + 4 + alea() * (Z1 - Z0 - 8);
            if (!libre(x, z) || !libre(x + 3, z) || !libre(x - 3, z) || !libre(x, z + 3) || !libre(x, z - 3)) {
                continue;
            }
            arbre((int) Math.floor(x), (int) Math.floor(z));
            occuper(x, z, 3.5);
            poses++;
        }
        Block[] fleurs = {Blocks.POPPY, Blocks.DANDELION, Blocks.ALLIUM, Blocks.OXEYE_DAISY, Blocks.CORNFLOWER, Blocks.AZURE_BLUET, Blocks.SHORT_GRASS, Blocks.SHORT_GRASS};
        for (int i = 0; i < 5500; i++) {
            double x = X0 + 2 + alea() * (X1 - X0 - 4), z = Z0 + 2 + alea() * (Z1 - Z0 - 4);
            if (libre(x, z)) {
                BlockPos q = new BlockPos((int) Math.floor(x), SOL, (int) Math.floor(z));
                if (level.getBlockState(q).isAir() && level.getBlockState(q.below()).is(Blocks.GRASS_BLOCK)) {
                    level.setBlock(q, fleurs[(int) (alea() * fleurs.length)].defaultBlockState(), FLAGS);
                }
            }
        }
    }

    private void arbre(int x, int z) {
        double r = alea();
        if (r < 0.55) {
            int h = 4 + (int) (alea() * 3);
            remplir(x, 0, z, x, h - 1, z, Blocks.OAK_LOG);
            remplir(x - 2, h - 1, z - 2, x + 2, h, z + 2, feuilles(Blocks.OAK_LEAVES));
            remplir(x - 1, h + 1, z - 1, x + 1, h + 1, z + 1, feuilles(Blocks.OAK_LEAVES));
            bloc(x, h - 1, z, Blocks.OAK_LOG);
            bloc(x, h, z, Blocks.OAK_LOG);
        } else if (r < 0.8) {
            int h = 5 + (int) (alea() * 3);
            remplir(x, 0, z, x, h - 1, z, Blocks.BIRCH_LOG);
            remplir(x - 2, h - 1, z - 2, x + 2, h, z + 2, feuilles(Blocks.BIRCH_LEAVES));
            remplir(x - 1, h + 1, z - 1, x + 1, h + 1, z + 1, feuilles(Blocks.BIRCH_LEAVES));
            bloc(x, h - 1, z, Blocks.BIRCH_LOG);
        } else {
            int h = 7 + (int) (alea() * 4);
            remplir(x, 0, z, x, h - 1, z, Blocks.SPRUCE_LOG);
            for (int k = 0; k < 4; k++) {
                int rr = 3 - k;
                remplir(x - rr, 2 + k * 2, z - rr, x + rr, 2 + k * 2, z + rr, feuilles(Blocks.SPRUCE_LEAVES));
            }
            bloc(x, h, z, feuilles(Blocks.SPRUCE_LEAVES));
        }
    }

    // ================================================================== les gens

    /** Quelqu'un du parc, ici, tourné vers {@code lacet}. */
    VisiteurParc personne(double x, double z, float lacet) {
        VisiteurParc v = Parc.VISITEUR.get().create(level);
        if (v == null) {
            return null;
        }
        v.moveTo(x, SOL, z, lacet, 0F);
        v.setYHeadRot(lacet);
        v.setYBodyRot(lacet);
        v.addTag(TAG);
        v.addTag(construction);
        return v;
    }

    void employe(double x, double y, double z, float lacet) {
        VisiteurParc v = personne(x, z, lacet);
        if (v != null) {
            v.moveTo(x, SOL + y, z, lacet, 0F);
            v.employe();
            level.addFreshEntity(v);
        }
    }

    void promeneur(double x, double z, int rayon) {
        VisiteurParc v = personne(x, z, (float) (alea() * 360));
        if (v == null) {
            return;
        }
        double r = alea();
        // un goûter à la main, une fois sur trois
        ItemStack main = r < 0.08 ? new ItemStack(Items.COOKIE) : r < 0.16 ? new ItemStack(Items.MELON_SLICE) : r < 0.22 ? new ItemStack(Items.HONEY_BOTTLE)
                : r < 0.28 ? new ItemStack(Items.PUMPKIN_PIE) : r < 0.33 ? new ItemStack(Items.POPPY) : ItemStack.EMPTY;
        v.promeneur(rayon, main);
        level.addFreshEntity(v);
    }

    private void gens() {
        // aux guichets (derrière la vitre, tournés vers l'extérieur), et aux stands (derrière le comptoir)
        employe(16, 0, 123, 0F);
        employe(-16, 0, 123, 0F);
        for (int[] st : new int[][]{{-12, 48}, {11, 48}, {-12, 66}, {11, 66}, {-12, 96}, {11, 96}, {-12, 108}, {11, 108}}) {
            boolean ouest = st[0] < 0;
            employe(ouest ? st[0] + 1.5 : st[0] - 0.5, 0, st[1] + 0.5, ouest ? -90F : 90F);
        }
        // l'employé du Dragon, sur le quai
        employe(117.5, 4, 33.5, -90F);
        // les promeneurs : sur la place, le long de l'avenue, devant l'entrée, sur le chemin du Dragon
        for (int k = 0; k < 14; k++) {
            double a = alea() * Math.PI * 2, r = 9 + alea() * 10;
            promeneur(Math.cos(a) * r, Math.sin(a) * r, 18);
        }
        for (int k = 0; k < 16; k++) {
            promeneur(-4 + alea() * 8, 26 + alea() * 88, 12);
        }
        for (int k = 0; k < 4; k++) {
            promeneur(-6 + alea() * 12, 128 + alea() * 6, 6);
        }
        for (int k = 0; k < 8; k++) {
            double t = alea();
            if (t < 0.6) {
                promeneur(30 + alea() * 70, -4, 8);
            } else {
                promeneur(101 + alea() * 6, 5 + alea() * 36, 6);
            }
        }
    }
}
