package fr.cabane.hub;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.logging.LogUtils;
import fr.cabane.Cabane;
import fr.cabane.CabaneBlocks;
import fr.cabane.CabaneItems;
import fr.cabane.histoire.Histoire;
import java.io.Reader;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.decoration.HangingEntity;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.decoration.Painting;
import net.minecraft.world.entity.npc.AbstractVillager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.VehicleEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.Tags;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;

/**
 * /commissariat : construit le commissariat de la maquette, bloc après bloc, devant soi ou à l'endroit donné.
 *
 * <p>Rien ne bouge si la zone contient déjà une construction, un coffre, un PNJ, un joueur ou un terrain réservé.
 * Avant la première pose, la zone entière est photographiée : {@code /commissariat annuler} la remet comme avant.
 *
 * <p>Le plan vient de {@code outils/commissariat.mjs}, qui lit la maquette. Dans le plan, l'avant du bâtiment
 * regarde vers les z négatifs et le milieu de l'entrée est en x = 11.
 */
@EventBusSubscriber(modid = Cabane.MODID)
public final class Commissariat {
    private static final Logger LOG = LogUtils.getLogger();
    private static final String TAG = "cabane_commissariat";
    private static final ResourceLocation PLAN = ResourceLocation.fromNamespaceAndPath(Cabane.MODID, "commissariat/plan.json");
    private static final ResourceLocation AVANT = ResourceLocation.fromNamespaceAndPath(Cabane.MODID, "commissariat_avant");

    /** Toute la zone concernée, dans les coordonnées du plan. */
    private static final int X_MIN = -1, X_MAX = 23, Y_MIN = -6, Y_MAX = 18, Z_MIN = -7, Z_MAX = 23;
    /** La distance entre le joueur et le milieu de la façade. */
    private static final int RECUL = 10;
    /** Le terrain peut descendre ou monter de ça sous le bâtiment. */
    private static final int TROU_MAX = 5, COLLINE_MAX = 10;

    private static final int BLOCS_PAR_TICK = 8, DEBLAI_PAR_TICK = 200;

    private static Chantier chantier;

    private Commissariat() {
    }

    // ================================================================ commandes

    @SubscribeEvent
    public static void commandes(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("commissariat").requires(Commissariat::hote)
                .executes(c -> devant(c.getSource(), false))
                .then(Commands.literal("forcer").executes(c -> devant(c.getSource(), true)))
                .then(Commands.literal("annuler").executes(c -> annuler(c.getSource())))
                .then(Commands.argument("pos", BlockPosArgument.blockPos())
                        .executes(c -> lancer(c.getSource(), BlockPosArgument.getBlockPos(c, "pos"), Rotation.NONE, false))
                        .then(Commands.argument("facade", StringArgumentType.word())
                                .suggests((c, b) -> {
                                    for (String s : List.of("nord", "est", "sud", "ouest")) {
                                        b.suggest(s);
                                    }
                                    return b.buildFuture();
                                })
                                .executes(c -> aFacade(c, false))
                                .then(Commands.literal("forcer").executes(c -> aFacade(c, true))))));
    }

    private static int aFacade(com.mojang.brigadier.context.CommandContext<CommandSourceStack> c, boolean forcer) {
        Rotation rot = facade(StringArgumentType.getString(c, "facade"));
        if (rot == null) {
            c.getSource().sendFailure(Component.literal("Choisis nord, est, sud ou ouest."));
            return 0;
        }
        return lancer(c.getSource(), BlockPosArgument.getBlockPos(c, "pos"), rot, forcer);
    }

    private static boolean hote(CommandSourceStack source) {
        if (source.hasPermission(2)) {
            return true;
        }
        ServerPlayer joueur = source.getPlayer();
        return joueur != null && source.getServer().isSingleplayerOwner(joueur.getGameProfile());
    }

    /** Le côté vers lequel regarde la façade. */
    private static Rotation facade(String mot) {
        return switch (mot.toLowerCase(Locale.ROOT)) {
            case "nord" -> Rotation.NONE;
            case "est" -> Rotation.CLOCKWISE_90;
            case "sud" -> Rotation.CLOCKWISE_180;
            case "ouest" -> Rotation.COUNTERCLOCKWISE_90;
            default -> null;
        };
    }

    private static int devant(CommandSourceStack source, boolean forcer) {
        ServerPlayer joueur = source.getPlayer();
        if (joueur == null) {
            source.sendFailure(Component.literal("Depuis la console, donne l'endroit : /commissariat <x> <y> <z> [nord|est|sud|ouest]"));
            return 0;
        }
        Direction regard = joueur.getDirection();
        Rotation rot = switch (regard) {
            case WEST -> Rotation.CLOCKWISE_90;
            case NORTH -> Rotation.CLOCKWISE_180;
            case EAST -> Rotation.COUNTERCLOCKWISE_90;
            default -> Rotation.NONE;
        };
        return lancer(source, joueur.blockPosition().below().relative(regard, RECUL), rot, forcer);
    }

    private static int lancer(CommandSourceStack source, BlockPos ancre, Rotation rot, boolean forcer) {
        MinecraftServer server = source.getServer();
        ServerLevel level = source.getLevel();
        if (chantier != null) {
            source.sendFailure(Component.literal("Un commissariat est déjà en train de se construire."));
            return 0;
        }
        Donnees donnees = Donnees.get(server);
        if (donnees.present) {
            source.sendFailure(Component.literal("Il y en a déjà un en " + donnees.ax + " " + donnees.ay + " " + donnees.az
                    + ". Fais /commissariat annuler avant d'en construire un autre."));
            return 0;
        }
        Pose pose = new Pose(ancre.getX(), ancre.getY(), ancre.getZ(), rot);
        String raison = obstacle(level, pose, forcer);
        if (raison != null) {
            source.sendFailure(Component.literal("Pas ici : " + raison + ". Rien n'a été touché."));
            return 0;
        }
        Plan plan;
        try {
            plan = lirePlan(server, rot);
        } catch (Exception e) {
            LOG.error("Plan du commissariat illisible", e);
            source.sendFailure(Component.literal("Le plan du commissariat est illisible. Rien n'a été touché."));
            return 0;
        }

        // la photo d'avant, écrite sur le disque avant de poser le moindre bloc
        Boite boite = pose.boite();
        StructureTemplateManager gestion = level.getStructureManager();
        StructureTemplate photo = gestion.getOrCreate(AVANT);
        photo.fillFromWorld(level, boite.min(), boite.taille(), false, null);
        if (!gestion.save(AVANT)) {
            source.sendFailure(Component.literal("Impossible d'enregistrer l'état du terrain. Rien n'a été touché."));
            return 0;
        }
        donnees.noter(level, pose, boite.min());

        chantier = new Chantier(level, pose, plan, source);
        source.sendSuccess(() -> Component.literal("Le commissariat se construit…").withStyle(ChatFormatting.GOLD), true);
        return 1;
    }

    private static int annuler(CommandSourceStack source) {
        MinecraftServer server = source.getServer();
        Donnees d = Donnees.get(server);
        if (!d.present) {
            source.sendFailure(Component.literal("Aucun commissariat à enlever."));
            return 0;
        }
        ServerLevel level = server.getLevel(ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(d.dim)));
        if (level == null) {
            source.sendFailure(Component.literal("La dimension du commissariat n'existe plus."));
            return 0;
        }
        if (chantier != null) {
            chantier.liberer();
            chantier = null;
        }
        var photo = level.getStructureManager().get(AVANT);
        if (photo.isEmpty()) {
            source.sendFailure(Component.literal("L'état d'origine du terrain est introuvable, je n'y touche pas."));
            return 0;
        }
        BlockPos min = new BlockPos(d.minX, d.minY, d.minZ);
        Vec3i taille = photo.get().getSize();
        AABB zone = new AABB(min.getX(), min.getY(), min.getZ(), min.getX() + taille.getX(), min.getY() + taille.getY(), min.getZ() + taille.getZ());
        for (Entity e : level.getEntities((Entity) null, zone, x -> x.getTags().contains(TAG))) {
            e.discard();
        }
        photo.get().placeInWorld(level, min, min, new StructurePlaceSettings().setIgnoreEntities(true), level.getRandom(),
                Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
        d.effacer();
        source.sendSuccess(() -> Component.literal("Commissariat enlevé, le terrain est revenu comme avant.").withStyle(ChatFormatting.GOLD), true);
        return 1;
    }

    // ================================================================ on ne touche à rien de ce qui existe

    /**
     * Ce qui empêche de construire ici, ou null. Ne modifie rien.
     *
     * <p>Dans le village, ce que les joueurs posent est noté : tout le reste (lampadaires, muret…) vient de la carte et
     * peut être remplacé. {@code forcer} passe aussi sur ce qu'un joueur a posé et sur un terrain encore à vendre, jamais
     * sur un coffre, un bâtiment du village, un terrain acheté, un joueur ou un PNJ.
     */
    private static String obstacle(ServerLevel level, Pose pose, boolean forcer) {
        Boite boite = pose.boite();
        if (level.isOutsideBuildHeight(boite.min()) || level.isOutsideBuildHeight(boite.max())) {
            return "c'est trop près du haut ou du bas du monde";
        }
        for (int z = Z_MIN; z <= Z_MAX; z++) {
            for (int x = X_MIN; x <= X_MAX; x++) {
                int sol = Integer.MIN_VALUE;
                for (int y = Y_MAX; y >= Y_MIN; y--) {
                    BlockPos p = pose.bloc(x, y, z);
                    BlockState etat = level.getBlockState(p);
                    if (Protection.reserve(level, p, forcer)) {
                        return "c'est sur un terrain réservé ou un bâtiment du village, en " + texte(p);
                    }
                    if (!etat.getFluidState().isEmpty()) {
                        return "il y a de l'eau ou de la lave en " + texte(p);
                    }
                    String nom = etat.getBlock().getName().getString();
                    if (etat.hasBlockEntity()) {
                        return "il y a déjà " + nom + " en " + texte(p);
                    }
                    if (!forcer && !naturel(level, p, etat)) {
                        if (Protection.poseParUnJoueur(level, p)) {
                            return "un joueur a posé " + nom + " en " + texte(p) + " (ajoute « forcer » à la commande pour construire quand même)";
                        }
                        if (!Protection.dansLeVillage(level, p)) {
                            return "il y a déjà " + nom + " en " + texte(p);
                        }
                    }
                    if (sol == Integer.MIN_VALUE && terrain(level, p, etat)) {
                        sol = y;
                    }
                }
                if (x >= 0 && x <= 22 && z >= 0 && z <= 22 && (sol < -TROU_MAX || sol > COLLINE_MAX)) {
                    return "le terrain est trop en pente vers " + texte(pose.bloc(x, 0, z));
                }
            }
        }
        for (Entity e : level.getEntities((Entity) null, boite.aabb(), x -> true)) {
            if (e instanceof Player joueur) {
                if (!joueur.isSpectator()) {
                    return joueur.getScoreboardName() + " est dans la zone";
                }
            } else if (aGarder(e)) {
                return "il y a " + e.getName().getString() + " en " + texte(e.blockPosition());
            }
        }
        return null;
    }

    private static String texte(BlockPos p) {
        return p.getX() + " " + p.getY() + " " + p.getZ();
    }

    /** Un bloc que le monde a posé tout seul : le reste, c'est que quelqu'un a construit là. */
    private static boolean naturel(ServerLevel level, BlockPos p, BlockState etat) {
        if (etat.isAir()) {
            return true;
        }
        if (etat.hasBlockEntity()) {
            return false;
        }
        if (etat.getBlock() instanceof LeavesBlock) {
            return !etat.getValue(LeavesBlock.PERSISTENT);
        }
        if (etat.is(BlockTags.LOGS)) {
            return dansUnArbre(level, p);
        }
        return etat.canBeReplaced() || etat.is(BlockTags.DIRT) || etat.is(BlockTags.BASE_STONE_OVERWORLD) || etat.is(BlockTags.SAND)
                || etat.is(Tags.Blocks.ORES) || etat.is(BlockTags.FLOWERS) || etat.is(BlockTags.SAPLINGS) || etat.is(BlockTags.SNOW)
                || etat.is(Blocks.GRAVEL) || etat.is(Blocks.CLAY) || etat.is(Blocks.ICE) || etat.is(Blocks.SANDSTONE) || etat.is(Blocks.RED_SANDSTONE)
                || etat.is(Blocks.MOSS_CARPET) || etat.is(Blocks.BROWN_MUSHROOM) || etat.is(Blocks.RED_MUSHROOM) || etat.is(Blocks.SUGAR_CANE)
                || etat.is(Blocks.CACTUS) || etat.is(Blocks.BAMBOO) || etat.is(Blocks.PUMPKIN) || etat.is(Blocks.MELON) || etat.is(Blocks.SWEET_BERRY_BUSH);
    }

    /** Un tronc naturel a des feuilles naturelles tout près ; un mur de bûches, non. */
    private static boolean dansUnArbre(ServerLevel level, BlockPos p) {
        int y = p.getY();
        while (level.getBlockState(new BlockPos(p.getX(), y + 1, p.getZ())).is(BlockTags.LOGS)) {
            y++;
        }
        BlockPos haut = new BlockPos(p.getX(), y, p.getZ());
        for (BlockPos q : BlockPos.betweenClosed(haut.offset(-3, -2, -3), haut.offset(3, 3, 3))) {
            BlockState s = level.getBlockState(q);
            if (s.getBlock() instanceof LeavesBlock && !s.getValue(LeavesBlock.PERSISTENT)) {
                return true;
            }
        }
        return false;
    }

    private static boolean terrain(ServerLevel level, BlockPos p, BlockState etat) {
        return etat.isSolidRender(level, p) && !etat.is(BlockTags.LOGS);
    }

    private static boolean aGarder(Entity e) {
        return e instanceof HangingEntity || e instanceof ArmorStand || e instanceof VehicleEntity || e instanceof AbstractVillager || e instanceof Display
                || (e instanceof Mob m && (m.isPersistenceRequired() || m.hasCustomName() || m.isLeashed()))
                || (e instanceof OwnableEntity o && o.getOwnerUUID() != null)
                || e.getTags().stream().anyMatch(t -> t.startsWith("pnj.") || t.startsWith("cabane"));
    }

    // ================================================================ le plan

    private record Plan(BlockState[] palette, int[] structure, int[] finitions, JsonObject brut) {
    }

    private static Plan lirePlan(MinecraftServer server, Rotation rot) throws Exception {
        Resource fichier = server.getResourceManager().getResource(PLAN).orElseThrow();
        JsonObject o;
        try (Reader lecteur = fichier.openAsReader()) {
            o = JsonParser.parseReader(lecteur).getAsJsonObject();
        }
        JsonArray noms = o.getAsJsonArray("palette");
        BlockState[] palette = new BlockState[noms.size()];
        HolderLookup<Block> blocs = BuiltInRegistries.BLOCK.asLookup();
        for (int i = 0; i < palette.length; i++) {
            palette[i] = tourner(BlockStateParser.parseForBlock(blocs, noms.get(i).getAsString(), false).blockState(), rot);
        }
        return new Plan(palette, entiers(o.getAsJsonArray("structure")), entiers(o.getAsJsonArray("finitions")), o);
    }

    private static int[] entiers(JsonArray a) {
        int[] t = new int[a.size()];
        for (int i = 0; i < t.length; i++) {
            t[i] = a.get(i).getAsInt();
        }
        return t;
    }

    /** Certains blocs de mods ne savent pas tourner tout seuls. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static BlockState tourner(BlockState etat, Rotation rot) {
        BlockState tourne = etat.rotate(rot);
        if (tourne != etat || rot == Rotation.NONE) {
            return tourne;
        }
        for (Property<?> p : etat.getProperties()) {
            if (p.getName().equals("facing") && etat.getValue(p) instanceof Direction d && d.getAxis().isHorizontal()) {
                return etat.setValue((Property) p, rot.rotate(d));
            }
        }
        return tourne;
    }

    // ================================================================ passer du plan au monde

    private record Boite(BlockPos min, BlockPos max) {
        Vec3i taille() {
            return new Vec3i(max.getX() - min.getX() + 1, max.getY() - min.getY() + 1, max.getZ() - min.getZ() + 1);
        }

        AABB aabb() {
            return new AABB(min.getX(), min.getY(), min.getZ(), max.getX() + 1, max.getY() + 1, max.getZ() + 1);
        }
    }

    /** (ax, ay, az) est la case du plan (11, 0, 0) : le sol au milieu de l'entrée. */
    private record Pose(int ax, int ay, int az, Rotation rot) {
        BlockPos bloc(int x, int y, int z) {
            return new BlockPos(x - 11, y, z).rotate(rot).offset(ax, ay, az);
        }

        Vec3 point(double x, double y, double z) {
            double dx = x - 11.5, dz = z - 0.5;
            return switch (rot) {
                case CLOCKWISE_90 -> new Vec3(ax + 0.5 - dz, ay + y, az + 0.5 + dx);
                case CLOCKWISE_180 -> new Vec3(ax + 0.5 - dx, ay + y, az + 0.5 - dz);
                case COUNTERCLOCKWISE_90 -> new Vec3(ax + 0.5 + dz, ay + y, az + 0.5 - dx);
                default -> new Vec3(ax + 0.5 + dx, ay + y, az + 0.5 + dz);
            };
        }

        /** L'inverse de {@link #bloc} : {x, y, z} dans le plan. */
        int[] plan(BlockPos p) {
            BlockPos d = new BlockPos(p.getX() - ax, p.getY() - ay, p.getZ() - az).rotate(switch (rot) {
                case CLOCKWISE_90 -> Rotation.COUNTERCLOCKWISE_90;
                case COUNTERCLOCKWISE_90 -> Rotation.CLOCKWISE_90;
                default -> rot;
            });
            return new int[] {d.getX() + 11, d.getY(), d.getZ()};
        }

        float yaw(float yaw) {
            return yaw + switch (rot) {
                case CLOCKWISE_90 -> 90F;
                case CLOCKWISE_180 -> 180F;
                case COUNTERCLOCKWISE_90 -> 270F;
                default -> 0F;
            };
        }

        Boite boite() {
            BlockPos a = bloc(X_MIN, Y_MIN, Z_MIN), b = bloc(X_MAX, Y_MAX, Z_MAX);
            return new Boite(new BlockPos(Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()), Math.min(a.getZ(), b.getZ())),
                    new BlockPos(Math.max(a.getX(), b.getX()), Math.max(a.getY(), b.getY()), Math.max(a.getZ(), b.getZ())));
        }
    }

    // ================================================================ le chantier, tick après tick

    @SubscribeEvent
    public static void tick(ServerTickEvent.Post event) {
        if (chantier == null) {
            return;
        }
        if (chantier.level.getServer() != event.getServer()) {
            chantier = null;
            return;
        }
        try {
            if (chantier.avancer()) {
                chantier.liberer();
                chantier = null;
            }
        } catch (RuntimeException e) {
            LOG.error("Chantier du commissariat interrompu", e);
            chantier.source.sendFailure(Component.literal("Le chantier s'est arrêté sur une erreur. /commissariat annuler remet le terrain comme avant."));
            chantier.liberer();
            chantier = null;
        }
    }

    private static final class Chantier {
        final ServerLevel level;
        final Pose pose;
        final Plan plan;
        final CommandSourceStack source;
        final List<BlockPos> deblai = new ArrayList<>();
        final List<BlockPos> remblai = new ArrayList<>();
        final List<BlockState> remblaiEtats = new ArrayList<>();
        final List<int[]> forces = new ArrayList<>();
        int etape;
        int i;

        Chantier(ServerLevel level, Pose pose, Plan plan, CommandSourceStack source) {
            this.level = level;
            this.pose = pose;
            this.plan = plan;
            this.source = source;
            Boite b = pose.boite();
            // les tronçons restent chargés même si personne n'est à côté (construction lancée depuis la console)
            for (int cx = b.min().getX() >> 4; cx <= b.max().getX() >> 4; cx++) {
                for (int cz = b.min().getZ() >> 4; cz <= b.max().getZ() >> 4; cz++) {
                    if (level.setChunkForced(cx, cz, true)) {
                        forces.add(new int[] {cx, cz});
                    }
                }
            }
            preparerLeTerrain();
        }

        void liberer() {
            for (int[] c : forces) {
                level.setChunkForced(c[0], c[1], false);
            }
            forces.clear();
        }

        private void preparerLeTerrain() {
            for (int y = Y_MAX; y >= 1; y--) {
                for (int z = Z_MIN; z <= Z_MAX; z++) {
                    for (int x = X_MIN; x <= X_MAX; x++) {
                        ajouterDeblai(x, y, z);
                    }
                }
            }
            for (int y = 0; y >= -4; y--) {
                for (int z = 11; z <= 22; z++) {
                    for (int x = 0; x <= 22; x++) {
                        ajouterDeblai(x, y, z);
                    }
                }
            }
            BlockState herbe = Blocks.GRASS_BLOCK.defaultBlockState();
            BlockState terre = Blocks.DIRT.defaultBlockState();
            for (int z = Z_MIN; z <= Z_MAX; z++) {
                for (int x = X_MIN; x <= X_MAX; x++) {
                    if (x >= 0 && x <= 22 && z >= 11 && z <= 22) {
                        continue; // le sous-sol
                    }
                    BlockPos p = pose.bloc(x, 0, z);
                    BlockState s = level.getBlockState(p);
                    if (terrain(level, p, s)) {
                        if (s.is(BlockTags.DIRT) && !s.is(Blocks.GRASS_BLOCK)) {
                            remblai.add(p);
                            remblaiEtats.add(herbe);
                        }
                        continue;
                    }
                    remblai.add(p);
                    remblaiEtats.add(herbe);
                    for (int y = -1; y >= -TROU_MAX; y--) {
                        BlockPos q = pose.bloc(x, y, z);
                        if (terrain(level, q, level.getBlockState(q))) {
                            break;
                        }
                        remblai.add(q);
                        remblaiEtats.add(terre);
                    }
                }
            }
        }

        private void ajouterDeblai(int x, int y, int z) {
            BlockPos p = pose.bloc(x, y, z);
            if (!level.getBlockState(p).isAir()) {
                deblai.add(p);
            }
        }

        /** @return vrai quand tout est fini */
        boolean avancer() {
            switch (etape) {
                case 0 -> {
                    int fin = Math.min(i + DEBLAI_PAR_TICK, deblai.size());
                    for (; i < fin; i++) {
                        level.setBlock(deblai.get(i), Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
                    }
                    suivanteSi(i >= deblai.size());
                }
                case 1 -> {
                    int fin = Math.min(i + DEBLAI_PAR_TICK, remblai.size());
                    for (; i < fin; i++) {
                        level.setBlock(remblai.get(i), remblaiEtats.get(i), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
                    }
                    suivanteSi(i >= remblai.size());
                }
                case 2 -> {
                    int fin = Math.min(i + BLOCS_PAR_TICK * 4, plan.structure().length);
                    for (; i < fin; i += 4) {
                        int[] s = plan.structure();
                        BlockPos p = pose.bloc(s[i], s[i + 1], s[i + 2]);
                        BlockState etat = Block.updateFromNeighbourShapes(plan.palette()[s[i + 3]], level, p);
                        level.setBlock(p, etat, Block.UPDATE_ALL);
                        if (i % 40 == 0) {
                            level.playSound(null, p, etat.getSoundType().getPlaceSound(), SoundSource.BLOCKS, 0.7F, 0.9F);
                        }
                    }
                    suivanteSi(i >= plan.structure().length);
                }
                case 3 -> {
                    // ce qui a besoin d'un support, et les blocs en deux morceaux : sans mise à jour, chaque moitié reste là où on la pose
                    int fin = Math.min(i + BLOCS_PAR_TICK * 4, plan.finitions().length);
                    for (; i < fin; i += 4) {
                        int[] s = plan.finitions();
                        level.setBlock(pose.bloc(s[i], s[i + 1], s[i + 2]), plan.palette()[s[i + 3]], Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
                    }
                    suivanteSi(i >= plan.finitions().length);
                }
                default -> {
                    entites();
                    source.sendSuccess(() -> Component.literal("Commissariat terminé. /commissariat annuler remet le terrain comme avant.")
                            .withStyle(ChatFormatting.GOLD), true);
                    return true;
                }
            }
            return false;
        }

        private void suivanteSi(boolean fini) {
            if (fini) {
                etape++;
                i = 0;
            }
        }

        private void ajouter(Entity e) {
            e.addTag(TAG);
            level.addFreshEntity(e);
        }

        private void entites() {
            JsonObject o = plan.brut();
            // les tenues de police sur leurs mannequins
            for (JsonElement m : o.getAsJsonArray("mannequins")) {
                int[] c = entiers(m.getAsJsonArray());
                Vec3 v = pose.point(c[0] + 0.5, c[1], c[2] + 0.5);
                ArmorStand stand = new ArmorStand(level, v.x, v.y, v.z);
                stand.moveTo(v.x, v.y, v.z, pose.yaw(c[3]), 0F);
                stand.setItemSlot(EquipmentSlot.HEAD, new ItemStack(CabaneItems.CASQUETTE_POLICE.get()));
                stand.setItemSlot(EquipmentSlot.CHEST, new ItemStack(CabaneItems.VESTE_POLICE.get()));
                stand.setItemSlot(EquipmentSlot.LEGS, new ItemStack(CabaneItems.PANTALON_POLICE.get()));
                stand.setItemSlot(EquipmentSlot.FEET, new ItemStack(CabaneItems.CHAUSSURES_POLICE.get()));
                CompoundTag t = stand.saveWithoutId(new CompoundTag());
                t.putBoolean("ShowArms", true);
                t.putBoolean("NoBasePlate", true);
                stand.load(t);
                ajouter(stand);
            }
            // les armes : des cadres fixes et invisibles, personne ne repart avec
            for (JsonElement e : o.getAsJsonArray("cadres")) {
                JsonArray c = e.getAsJsonArray();
                String sens = c.get(3).getAsString();
                Direction d = sens.equals("up") ? Direction.UP : pose.rot().rotate(Direction.byName(sens));
                ItemFrame cadre = new ItemFrame(level, pose.bloc(c.get(0).getAsInt(), c.get(1).getAsInt(), c.get(2).getAsInt()), d);
                cadre.setItem(new ItemStack(c.get(4).getAsString().equals("matraque") ? CabaneItems.MATRAQUE.get() : CabaneItems.PISTOLET.get()), false);
                CompoundTag t = cadre.saveWithoutId(new CompoundTag());
                t.putBoolean("Fixed", true);
                t.putBoolean("Invisible", true);
                cadre.load(t);
                ajouter(cadre);
            }
            // l'affiche du jardin
            int[] a = entiers(o.getAsJsonArray("affiche"));
            var variante = level.registryAccess().registryOrThrow(Registries.PAINTING_VARIANT).getHolder(
                    ResourceKey.create(Registries.PAINTING_VARIANT, ResourceLocation.fromNamespaceAndPath(Cabane.MODID, "affiche_beauvais")));
            if (variante.isPresent()) {
                ajouter(new Painting(level, pose.bloc(a[0], a[1], a[2]), pose.rot().rotate(Direction.NORTH), variante.get()));
            } else {
                LOG.warn("Affiche du commissariat introuvable");
            }
            enseigne(o.getAsJsonArray("enseigne"));
        }

        private void enseigne(JsonArray c) {
            Vec3 v = pose.point(c.get(0).getAsDouble(), c.get(1).getAsDouble(), c.get(2).getAsDouble());
            CompoundTag tag = new CompoundTag();
            tag.putString("id", "minecraft:text_display");
            tag.putString("text", "{\"text\":\"POLICE\",\"bold\":true,\"color\":\"white\"}");
            tag.putString("billboard", "fixed");
            tag.putString("alignment", "center");
            tag.putInt("background", 0);
            tag.putInt("line_width", 300);
            tag.putBoolean("shadow", true);
            CompoundTag lumiere = new CompoundTag();
            lumiere.putInt("block", 15);
            lumiere.putInt("sky", 15);
            tag.put("brightness", lumiere);
            CompoundTag t = new CompoundTag();
            t.put("left_rotation", flottants(0, 0, 0, 1));
            t.put("right_rotation", flottants(0, 0, 0, 1));
            t.put("translation", flottants(0, 0, 0));
            t.put("scale", flottants(7.5F, 7.5F, 7.5F));
            tag.put("transformation", t);
            ListTag etiquettes = new ListTag();
            etiquettes.add(StringTag.valueOf(TAG));
            tag.put("Tags", etiquettes);
            Entity e = EntityType.loadEntityRecursive(tag, level, x -> {
                x.moveTo(v.x, v.y, v.z, pose.yaw(180F), 0F);
                return x;
            });
            if (e != null) {
                level.addFreshEntity(e);
            }
        }

        private static ListTag flottants(float... v) {
            ListTag l = new ListTag();
            for (float f : v) {
                l.add(FloatTag.valueOf(f));
            }
            return l;
        }
    }

    // ================================================================ le passage secret

    /** Le pupitre du bureau ouvre la bibliothèque ; depuis la salle secrète, on l'ouvre en cliquant dessus. */
    @SubscribeEvent(priority = net.neoforged.bus.api.EventPriority.HIGHEST)
    public static void clic(PlayerInteractEvent.RightClickBlock event) {
        if (!(event.getLevel() instanceof ServerLevel level) || event.getHand() != InteractionHand.MAIN_HAND) {
            return;
        }
        Donnees d = Donnees.get(level.getServer());
        if (!d.present || !level.dimension().location().toString().equals(d.dim)) {
            return;
        }
        Pose pose = d.pose();
        int[] c = pose.plan(event.getPos());
        boolean pupitre = c[0] == 7 && c[1] == 6 && c[2] == 15;
        boolean bibliotheque = c[0] == 9 && (c[1] == 6 || c[1] == 7) && c[2] == 14;
        if (!pupitre && !bibliotheque) {
            return;
        }
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.SUCCESS);
        // de l'autre côté, c'est une étagère comme les autres
        if (bibliotheque && pose.plan(event.getEntity().blockPosition())[2] >= 14) {
            return;
        }
        Histoire.BibliothequeSecrete bloc = CabaneBlocks.BIBLIOTHEQUE_SECRETE.get();
        boolean ouvert = false;
        for (int y : new int[] {6, 7}) {
            BlockPos p = pose.bloc(9, y, 14);
            BlockState etat = level.getBlockState(p);
            if (etat.is(bloc) && !etat.getValue(Histoire.BibliothequeSecrete.OUVERTE)) {
                level.setBlock(p, etat.setValue(Histoire.BibliothequeSecrete.OUVERTE, true), Block.UPDATE_ALL);
                level.scheduleTick(p, bloc, 100);
                ouvert = true;
            }
        }
        if (ouvert) {
            level.playSound(null, pose.bloc(9, 6, 14), SoundEvents.PISTON_EXTEND, SoundSource.BLOCKS, 0.6F, 0.6F);
            event.getEntity().displayClientMessage(Component.literal("La bibliothèque pivote sans un bruit…").withStyle(ChatFormatting.DARK_GRAY), true);
        }
    }

    // ================================================================ ce qu'on retient

    public static final class Donnees extends SavedData {
        private static final String NOM = "cabane_commissariat";
        boolean present;
        String dim = "";
        int ax, ay, az, rot;
        int minX, minY, minZ;

        static Donnees get(MinecraftServer server) {
            return server.overworld().getDataStorage().computeIfAbsent(new SavedData.Factory<>(Donnees::new, Donnees::charger, null), NOM);
        }

        static Donnees charger(CompoundTag t, HolderLookup.Provider registres) {
            Donnees d = new Donnees();
            d.present = t.getBoolean("present");
            d.dim = t.getString("dim");
            d.ax = t.getInt("ax");
            d.ay = t.getInt("ay");
            d.az = t.getInt("az");
            d.rot = t.getInt("rot");
            d.minX = t.getInt("minX");
            d.minY = t.getInt("minY");
            d.minZ = t.getInt("minZ");
            return d;
        }

        @Override
        public CompoundTag save(CompoundTag t, HolderLookup.Provider registres) {
            t.putBoolean("present", present);
            t.putString("dim", dim);
            t.putInt("ax", ax);
            t.putInt("ay", ay);
            t.putInt("az", az);
            t.putInt("rot", rot);
            t.putInt("minX", minX);
            t.putInt("minY", minY);
            t.putInt("minZ", minZ);
            return t;
        }

        void noter(Level level, Pose pose, BlockPos min) {
            present = true;
            dim = level.dimension().location().toString();
            ax = pose.ax();
            ay = pose.ay();
            az = pose.az();
            rot = pose.rot().ordinal();
            minX = min.getX();
            minY = min.getY();
            minZ = min.getZ();
            setDirty();
        }

        void effacer() {
            present = false;
            setDirty();
        }

        Pose pose() {
            return new Pose(ax, ay, az, Rotation.values()[rot]);
        }
    }
}
