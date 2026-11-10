package fr.cabane.hub;

import fr.cabane.Cabane;
import fr.cabane.CabaneBlocks;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.saveddata.SavedData;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.Tags;
import net.neoforged.neoforge.common.util.BlockSnapshot;
import net.neoforged.neoforge.event.entity.EntityMobGriefingEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.ExplosionEvent;

/**
 * Les bâtiments de Beauvais sont incassables : ni les joueurs, ni les explosions, ni le feu, ni les monstres ne les
 * abîment. Partout ailleurs dans le village (arbres, rues, jardins, décor), on casse et on construit librement ; même
 * autour d'un bâtiment, on peut creuser la terre, la pierre et le sable, cueillir et récolter.
 * <ul>
 * <li>Ce qu'un joueur a posé lui-même dans le village se recasse.</li>
 * <li>Une maison ou un terrain à vendre est protégé tant qu'il n'est pas acheté ; acheté, tout le monde peut le modifier.</li>
 * <li>Pendant la première partie de l'histoire, on ne creuse pas non plus sous la Mairie (la terre et la pierre autour
 * du puits d'échelle et du laboratoire) : le passage secret reste secret.</li>
 * <li>À partir de la deuxième partie de l'histoire, la Mairie (et son laboratoire) se casse.</li>
 * <li>Un hôte en créatif casse tout (pour réparer).</li>
 * </ul>
 */
@EventBusSubscriber(modid = Cabane.MODID)
public final class Protection {
    /** La Mairie, au-dessus du sol, et le laboratoire en dessous ({x0, y0, z0, x1, y1, z1} autour du centre). */
    private static final int[][] MAIRIE = {{3, -3, 41, 27, 24, 57}, {22, -30, 41, 49, -4, 63}};
    private static final List<int[]> LOTS = HubBuilder.lotsAVendre();
    private static final List<int[]> BATIMENTS = HubBuilder.batiments();

    /** Ce bloc fait partie d'un bâtiment du village (et ne doit pas être cassé). */
    public static boolean protege(Level level, BlockPos pos) {
        return protege(level, pos, level.getBlockState(pos));
    }

    public static boolean protege(Level level, BlockPos pos, BlockState etat) {
        if (!(level instanceof ServerLevel serveur) || level.dimension() != Level.OVERWORLD || etat.isAir()) {
            return false;
        }
        HubData hub = HubData.get(serveur.getServer());
        BlockPos c = hub.centre();
        if (c == null || !dedans(c, pos)) {
            return false;
        }
        if (etat.is(CabaneBlocks.BORNE_TERRAIN.get())) {
            return true; // la borne garde le nom du propriétaire
        }
        if (Poses.get(serveur).contient(pos)) {
            return false;
        }
        int x = pos.getX() - c.getX();
        int y = pos.getY() - c.getY();
        int z = pos.getZ() - c.getZ();
        if (sousLaMairie(x, y, z)) {
            // pendant la première partie, même la terre et la pierre : personne ne creuse jusqu'au passage secret
            return hub.partie() < 2;
        }
        if (naturel(etat)) {
            return false;
        }
        if (hub.partie() >= 2 && dansLaMairie(x, y, z)) {
            return false;
        }
        for (int[] lot : LOTS) {
            if (x >= lot[0] && x <= lot[2] && z >= lot[1] && z <= lot[3]) {
                return !(level.getBlockEntity(c.offset(lot[4], 1, lot[5])) instanceof fr.cabane.bloc.InteractifBlockEntity borne
                        && borne.proprietaire() != null);
            }
        }
        return batiment(x, y, z);
    }

    /** Sur un terrain à vendre ou acheté, dans un bâtiment du village, ou un bloc posé par un joueur : on n'y construit rien d'office. */
    public static boolean reserve(Level level, BlockPos pos) {
        if (!(level instanceof ServerLevel serveur) || level.dimension() != Level.OVERWORLD) {
            return false;
        }
        BlockPos c = HubData.get(serveur.getServer()).centre();
        if (c == null) {
            return false;
        }
        int x = pos.getX() - c.getX();
        int y = pos.getY() - c.getY();
        int z = pos.getZ() - c.getZ();
        for (int[] lot : LOTS) {
            if (x >= lot[0] && x <= lot[2] && z >= lot[1] && z <= lot[3]) {
                return true;
            }
        }
        return batiment(x, y, z) || (!level.getBlockState(pos).isAir() && Poses.get(serveur).contient(pos));
    }

    /** Dans un bâtiment du village (la Mairie, les grands édifices), ou dans la palissade qui l'entoure. */
    private static boolean batiment(int x, int y, int z) {
        if (dansLaMairie(x, y, z)) {
            return true;
        }
        for (int[] b : BATIMENTS) {
            if (x >= b[0] && x <= b[3] && y >= b[1] && y <= b[4] && z >= b[2] && z <= b[5]) {
                return true;
            }
        }
        return y >= -1 && y <= 4 && Math.abs(Math.sqrt(x * x + z * z) - (HubBuilder.rayon() - 1)) <= 1.5;
    }

    /** Dans la Mairie ou dans son laboratoire (coordonnées par rapport au centre du village). */
    private static boolean dansLaMairie(int x, int y, int z) {
        for (int[] b : MAIRIE) {
            if (x >= b[0] && x <= b[3] && y >= b[1] && y <= b[4] && z >= b[2] && z <= b[5]) {
                return true;
            }
        }
        return false;
    }

    /** Sous le sol de la Mairie : le puits d'échelle, le couloir et le laboratoire, avec la terre et la pierre autour. */
    private static boolean sousLaMairie(int x, int y, int z) {
        return y < 0 && dansLaMairie(x, y, z);
    }

    /** Dans l'enceinte du village (un peu plus large que la palissade), du fond du laboratoire jusqu'au ciel. */
    private static boolean dedans(BlockPos c, BlockPos pos) {
        long dx = pos.getX() - c.getX();
        long dz = pos.getZ() - c.getZ();
        int r = HubBuilder.rayon() + 3;
        int dy = pos.getY() - c.getY();
        return dx * dx + dz * dz <= (long) r * r && dy >= -32 && dy <= 96;
    }

    public static boolean dansLeVillage(Level level, BlockPos pos) {
        if (!(level instanceof ServerLevel serveur) || level.dimension() != Level.OVERWORLD) {
            return false;
        }
        BlockPos c = HubData.get(serveur.getServer()).centre();
        return c != null && dedans(c, pos);
    }

    /** Le sol et les plantes : on peut creuser, cueillir et récolter partout dans le village. */
    private static boolean naturel(BlockState s) {
        return s.is(BlockTags.DIRT) || s.is(BlockTags.SAND) || s.is(BlockTags.BASE_STONE_OVERWORLD) || s.is(Tags.Blocks.ORES)
                || s.is(Blocks.GRAVEL) || s.is(Blocks.CLAY) || s.is(Blocks.DIRT_PATH) || s.is(Blocks.FARMLAND)
                || s.is(Blocks.SNOW) || s.is(Blocks.SNOW_BLOCK) || s.is(Blocks.ICE) || s.is(Blocks.COBWEB)
                || s.is(BlockTags.REPLACEABLE) || s.is(BlockTags.CROPS) || s.is(BlockTags.SAPLINGS)
                || s.is(BlockTags.SMALL_FLOWERS) || s.is(BlockTags.TALL_FLOWERS)
                || s.is(Blocks.SUGAR_CANE) || s.is(Blocks.PUMPKIN) || s.is(Blocks.MELON) || s.is(Blocks.SWEET_BERRY_BUSH)
                || s.is(Blocks.BAMBOO) || s.is(Blocks.CACTUS) || s.is(Blocks.FIRE) || s.is(Blocks.SOUL_FIRE);
    }

    private static boolean hoteCreatif(Player joueur) {
        return joueur.isCreative() && joueur.hasPermissions(2);
    }

    // ================================================================ les joueurs

    @SubscribeEvent
    public static void onCasse(BlockEvent.BreakEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !(event.getPlayer() instanceof ServerPlayer joueur)) {
            return;
        }
        BlockPos pos = event.getPos();
        if (!hoteCreatif(joueur) && protege(level, pos, event.getState())) {
            event.setCanceled(true);
            joueur.displayClientMessage(Component.literal(naturel(event.getState())
                    ? "Ce terrain appartient à la Mairie : interdit d'y creuser."
                    : "Ce bâtiment appartient à Beauvais : impossible de le casser.").withStyle(ChatFormatting.RED), true);
            return;
        }
        if (dansLeVillage(level, pos)) {
            Poses.get(level).retirer(pos);
        }
    }

    /** Ce qu'un joueur pose dans le village, il pourra le recasser. */
    @SubscribeEvent
    public static void onPose(BlockEvent.EntityPlaceEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !(event.getEntity() instanceof Player) || !dansLeVillage(level, event.getPos())) {
            return;
        }
        Poses poses = Poses.get(level);
        if (event instanceof BlockEvent.EntityMultiPlaceEvent multi) {
            for (BlockSnapshot s : multi.getReplacedBlockSnapshots()) {
                poses.ajouter(s.getPos());
            }
        } else {
            poses.ajouter(event.getPos());
        }
    }

    // ================================================================ les explosions et les monstres

    @SubscribeEvent
    public static void onExplosion(ExplosionEvent.Detonate event) {
        if (event.getLevel() instanceof ServerLevel level && dansLeVillage(level, BlockPos.containing(event.getExplosion().center()))) {
            event.getAffectedBlocks().removeIf(p -> protege(level, p));
        }
    }

    /** Dans le village, les monstres ne cassent rien (portes, cultures piétinées par les ravageurs, creepers…). */
    @SubscribeEvent
    public static void onGriefing(EntityMobGriefingEvent event) {
        Entity e = event.getEntity();
        if (e instanceof Enemy && dansLeVillage(e.level(), e.blockPosition())) {
            event.setCanGrief(false);
        }
    }

    // ================================================================ les blocs posés par les joueurs

    /** Les positions des blocs posés par les joueurs dans le village. */
    public static final class Poses extends SavedData {
        private static final String NAME = "cabane_poses_village";
        private final LongOpenHashSet positions = new LongOpenHashSet();

        static Poses get(ServerLevel level) {
            return level.getServer().overworld().getDataStorage()
                    .computeIfAbsent(new SavedData.Factory<>(Poses::new, Poses::load, null), NAME);
        }

        private static Poses load(CompoundTag tag, HolderLookup.Provider registries) {
            Poses p = new Poses();
            for (long l : tag.getLongArray("positions")) {
                p.positions.add(l);
            }
            return p;
        }

        @Override
        public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
            tag.putLongArray("positions", positions.toLongArray());
            return tag;
        }

        boolean contient(BlockPos pos) {
            return positions.contains(pos.asLong());
        }

        void ajouter(BlockPos pos) {
            if (positions.add(pos.asLong())) {
                setDirty();
            }
        }

        void retirer(BlockPos pos) {
            if (positions.remove(pos.asLong())) {
                setDirty();
            }
        }
    }

    private Protection() {
    }
}
