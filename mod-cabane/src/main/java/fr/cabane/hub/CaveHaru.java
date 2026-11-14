package fr.cabane.hub;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import fr.cabane.Cabane;
import fr.cabane.CabaneBlocks;
import fr.cabane.bloc.InteractifBlockEntity;
import java.io.Reader;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
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
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.HangingEntity;
import net.minecraft.world.entity.decoration.Painting;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.ExplosionEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;

/**
 * /caveharu : construit la Cave à Haru (docs/cave-haru/cave.html) sur les deux terrains des maisons de la rue, quand
 * un même joueur les a achetés tous les deux. Les deux maisons laissent la place : le bar, la cave voûtée et, sous la
 * cave, la salle au trésor. Le terrain est photographié avant : {@code /caveharu annuler} remet les maisons.
 *
 * <p>Le fût n°2 de la cave cache une porte : un clic sur son couvercle l'ouvre, et elle s'ouvre toute seule devant
 * ceux qui remontent l'échelle de la salle au trésor.
 *
 * <p>Repère du plan (outils/cave-haru.mjs) : x 0..19 le long de la façade, z 0..9 vers le fond (la rue est côté z
 * négatif), y 0 = sol du bar, un bloc au-dessus du sol dehors.
 */
@EventBusSubscriber(modid = Cabane.MODID)
public final class CaveHaru {
    private static final Logger LOG = LogUtils.getLogger();
    private static final String TAG = "cabane_cave_haru";
    private static final ResourceLocation PLAN = ResourceLocation.fromNamespaceAndPath(Cabane.MODID, "cave_haru/plan.json");
    private static final ResourceLocation AVANT = ResourceLocation.fromNamespaceAndPath(Cabane.MODID, "cave_haru_avant");

    /**
     * Les deux terrains (coordonnées du plan) ; sous terre, seulement sous le bâtiment. Le premier rang côté rue (z -2)
     * reste comme il est : Martine y a sa place, devant la rangée nord.
     */
    private static final int X_MIN = -1, X_MAX = 20, Z_MIN = -1, Z_MAX = 9, Y_MIN = -13, Y_MAX = 14;
    private static final int BLOCS_PAR_TICK = 12, DEBLAI_PAR_TICK = 300;

    /** Les deux paires de maisons de la rue : leurs terrains dans {@link HubBuilder#lotsAVendre()}, et où tombe le plan. */
    private record Rangee(int lotA, int lotB, int ax, int az, Rotation rot) {
    }

    private static final Rangee[] RANGEES = {new Rangee(0, 1, 40, -5, Rotation.CLOCKWISE_180), new Rangee(2, 3, 21, 5, Rotation.NONE)};

    /** La porte camouflée dans le couvercle du fût n°2, et l'intérieur du fût (coordonnées du plan). */
    private static final int[][] PORTE = {{16, -4, 5}, {16, -3, 5}};
    /** Le couvercle reste ouvert 3 s, puis se referme dès que personne n'est dans le passage. */
    private static final int OUVERTE_TICKS = 60;

    private static Chantier chantier;
    private static long fermerA = -1;
    private static int compteur;

    private CaveHaru() {
    }

    static boolean enChantier() {
        return chantier != null;
    }

    // ================================================================ commandes

    @SubscribeEvent
    public static void commandes(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("caveharu").requires(CaveHaru::hote)
                .executes(c -> lancer(c.getSource(), null, false))
                .then(Commands.literal("forcer").executes(c -> lancer(c.getSource(), null, true)))
                .then(Commands.literal("annuler").executes(c -> annuler(c.getSource())))
                // le labo « Breaking Célia » derrière le coffre-fort, pour une cave construite avant lui
                .then(Commands.literal("labo").executes(c -> CaveHaruLabo.construire(c.getSource()))
                        .then(Commands.literal("annuler").executes(c -> CaveHaruLabo.annuler(c.getSource())))
                        .then(Commands.literal("ouvrir").executes(c -> CaveHaruLabo.ouvrirCommande(c.getSource()))))
                // depuis la console : l'endroit d'où on la lancerait, dans la rue devant les deux maisons
                .then(Commands.argument("depuis", BlockPosArgument.blockPos())
                        .executes(c -> lancer(c.getSource(), BlockPosArgument.getBlockPos(c, "depuis"), false))
                        .then(Commands.literal("forcer").executes(c -> lancer(c.getSource(), BlockPosArgument.getBlockPos(c, "depuis"), true)))));
    }

    private static boolean hote(CommandSourceStack source) {
        if (source.hasPermission(2)) {
            return true;
        }
        ServerPlayer joueur = source.getPlayer();
        return joueur != null && source.getServer().isSingleplayerOwner(joueur.getGameProfile());
    }

    private static int lancer(CommandSourceStack source, BlockPos depuis, boolean forcer) {
        ServerPlayer joueur = source.getPlayer();
        MinecraftServer server = source.getServer();
        if (depuis == null && joueur == null) {
            source.sendFailure(Component.literal("Depuis la console : /caveharu <x> <y> <z> (un endroit de la rue devant les deux maisons)."));
            return 0;
        }
        Vec3 ou = depuis != null ? Vec3.atBottomCenterOf(depuis) : joueur.position();
        ServerLevel level = server.overworld();
        if (depuis == null && joueur.level() != level) {
            source.sendFailure(Component.literal("Lance la commande dans le monde du village, devant les deux maisons de la rue."));
            return 0;
        }
        BlockPos c = HubData.get(server).centre();
        if (c == null) {
            source.sendFailure(Component.literal("Le village n'existe pas dans ce monde."));
            return 0;
        }
        if (chantier != null || CaveHaruLabo.enChantier()) {
            source.sendFailure(Component.literal("La cave est déjà en train de se construire."));
            return 0;
        }
        Donnees donnees = Donnees.get(server);
        if (donnees.present) {
            source.sendFailure(Component.literal("La cave existe déjà en " + donnees.ax + " " + donnees.ay + " " + donnees.az + ". /caveharu annuler l'enlève."));
            return 0;
        }

        // les deux rangées de la rue se font face : d'abord celle dont les deux terrains sont au même joueur, sinon la plus proche
        List<int[]> lots = HubBuilder.lotsAVendre();
        Rangee rangee = null;
        double meilleure = Double.MAX_VALUE;
        boolean achetee = false;
        for (Rangee r : RANGEES) {
            int[] a = lots.get(r.lotA()), b = lots.get(r.lotB());
            double x0 = c.getX() + Math.min(a[0], b[0]), x1 = c.getX() + Math.max(a[2], b[2]) + 1;
            double z0 = c.getZ() + Math.min(a[1], b[1]), z1 = c.getZ() + Math.max(a[3], b[3]) + 1;
            double dx = Math.max(0, Math.max(x0 - ou.x, ou.x - x1)), dz = Math.max(0, Math.max(z0 - ou.z, ou.z - z1));
            double d = Math.hypot(dx, dz);
            if (d > 20) {
                continue;
            }
            UUID pa = proprietaire(level, c, a), pb = proprietaire(level, c, b);
            boolean memeProprio = pa != null && pa.equals(pb);
            if ((memeProprio && !achetee) || (memeProprio == achetee && d < meilleure)) {
                meilleure = d;
                rangee = r;
                achetee = memeProprio;
            }
        }
        if (rangee == null) {
            source.sendFailure(Component.literal("Mets-toi dans la rue, devant les deux maisons (les deux terrains côte à côte), puis relance."));
            return 0;
        }
        if (!forcer && !achetee) {
            source.sendFailure(Component.literal("Les deux terrains doivent être achetés par le même joueur (ajoute « forcer » pour construire quand même)."));
            return 0;
        }

        Pose pose = new Pose(c.getX() + rangee.ax(), c.getY() + 1, c.getZ() + rangee.az(), rangee.rot());
        String raison = obstacle(level, pose, joueur);
        if (raison != null) {
            source.sendFailure(Component.literal("Pas encore : " + raison + ". Rien n'a été touché."));
            return 0;
        }
        Plan plan;
        try {
            plan = lirePlan(server, pose.rot());
        } catch (Exception e) {
            LOG.error("Plan de la Cave à Haru illisible", e);
            source.sendFailure(Component.literal("Le plan de la cave est illisible. Rien n'a été touché."));
            return 0;
        }

        // la photo d'avant (avec les tableaux des maisons), écrite sur le disque avant de poser le moindre bloc
        Boite boite = pose.boite();
        StructureTemplateManager gestion = level.getStructureManager();
        StructureTemplate photo = gestion.getOrCreate(AVANT);
        photo.fillFromWorld(level, boite.min(), boite.taille(), true, null);
        if (!gestion.save(AVANT)) {
            source.sendFailure(Component.literal("Impossible d'enregistrer l'état du terrain. Rien n'a été touché."));
            return 0;
        }
        donnees.noter(level, pose, boite.min());

        chantier = new Chantier(level, pose, plan, source);
        source.sendSuccess(() -> Component.literal("La Cave à Haru se construit…").withStyle(ChatFormatting.GOLD), true);
        return 1;
    }

    private static UUID proprietaire(ServerLevel level, BlockPos c, int[] lot) {
        return level.getBlockEntity(c.offset(lot[4], 1, lot[5])) instanceof InteractifBlockEntity borne ? borne.proprietaire() : null;
    }

    private static int annuler(CommandSourceStack source) {
        MinecraftServer server = source.getServer();
        Donnees d = Donnees.get(server);
        if (!d.present) {
            source.sendFailure(Component.literal("Il n'y a pas de Cave à Haru à enlever."));
            return 0;
        }
        ServerLevel level = server.getLevel(ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(d.dim)));
        if (level == null) {
            source.sendFailure(Component.literal("La dimension de la cave n'existe plus."));
            return 0;
        }
        var photo = level.getStructureManager().get(AVANT);
        if (photo.isEmpty()) {
            source.sendFailure(Component.literal("L'état d'origine du terrain est introuvable, je n'y touche pas."));
            return 0;
        }
        if (chantier != null) {
            chantier.liberer();
            chantier = null;
        }
        // d'abord le labo (sa photo a été prise la cave déjà là), puis la cave
        if (!CaveHaruLabo.enlever(level, d)) {
            source.sendFailure(Component.literal("L'état d'origine du terrain sous la cave est introuvable, je n'y touche pas."));
            return 0;
        }
        BlockPos min = new BlockPos(d.minX, d.minY, d.minZ);
        Vec3i taille = photo.get().getSize();
        AABB zone = new AABB(min.getX(), min.getY(), min.getZ(), min.getX() + taille.getX(), min.getY() + taille.getY(), min.getZ() + taille.getZ());
        for (Entity e : level.getEntities((Entity) null, zone, x -> x.getTags().contains(TAG) || x instanceof HangingEntity || x instanceof ItemEntity)) {
            e.discard();
        }
        // les tonneaux et coffres de la cave se vident sans rien jeter par terre, puis les maisons reviennent
        for (BlockPos p : BlockPos.betweenClosed(min, min.offset(taille.getX() - 1, taille.getY() - 1, taille.getZ() - 1))) {
            if (level.getBlockEntity(p) instanceof Container coffre) {
                coffre.clearContent();
            }
        }
        photo.get().placeInWorld(level, min, min, new StructurePlaceSettings(), level.getRandom(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
        for (Entity e : level.getEntities((Entity) null, zone, x -> x instanceof ItemEntity)) {
            e.discard(); // les bougies et lanternes décrochées
        }
        d.effacer();
        fermerA = -1;
        source.sendSuccess(() -> Component.literal("Cave à Haru enlevée, les maisons sont revenues.").withStyle(ChatFormatting.GOLD), true);
        return 1;
    }

    // ================================================================ ce qui empêche de construire

    /** Ce qui empêche de construire, ou null. Ne modifie rien. */
    private static String obstacle(ServerLevel level, Pose pose, ServerPlayer lanceur) {
        Boite boite = pose.boite();
        if (level.isOutsideBuildHeight(boite.min()) || level.isOutsideBuildHeight(boite.max())) {
            return "c'est trop près du haut ou du bas du monde";
        }
        for (BlockPos p : BlockPos.betweenClosed(boite.min(), boite.max())) {
            BlockEntity be = level.getBlockEntity(p);
            if (be instanceof Container coffre && !coffre.isEmpty()) {
                return "il reste des affaires dans " + level.getBlockState(p).getBlock().getName().getString() + " en " + texte(p) + " (vide-le avant)";
            }
        }
        for (Entity e : level.getEntities((Entity) null, boite.aabb(), x -> true)) {
            if (e instanceof Player joueur) {
                if (!joueur.isSpectator()) {
                    return (joueur == lanceur ? "tu es" : joueur.getScoreboardName() + " est") + " sur les terrains (mets-toi dans la rue)";
                }
            } else if (bloquant(e)) {
                return "il y a " + e.getName().getString() + " en " + texte(e.blockPosition());
            }
        }
        return null;
    }

    /** Un PNJ, un animal apprivoisé, un mannequin… : on ne les déplace pas d'office. Les bêtes du village qui passent, si. */
    static boolean bloquant(Entity e) {
        return e.getTags().stream().anyMatch(t -> t.startsWith("pnj.") || t.equals("cabane_pnj"))
                || e instanceof net.minecraft.world.entity.npc.AbstractVillager || e instanceof net.minecraft.world.entity.decoration.ArmorStand
                || e instanceof net.minecraft.world.entity.Display || e instanceof net.minecraft.world.entity.vehicle.VehicleEntity
                || (e instanceof net.minecraft.world.entity.OwnableEntity o && o.getOwnerUUID() != null)
                || (e.hasCustomName() && !(e instanceof net.minecraft.world.entity.animal.Animal));
    }

    static String texte(BlockPos p) {
        return p.getX() + " " + p.getY() + " " + p.getZ();
    }

    // ================================================================ le plan

    record Plan(BlockState[] palette, int[] structure, int[] finitions, JsonObject brut) {
    }

    static Plan lirePlan(MinecraftServer server, Rotation rot) throws Exception {
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

    static int[] entiers(JsonArray a) {
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

    record Boite(BlockPos min, BlockPos max) {
        Vec3i taille() {
            return new Vec3i(max.getX() - min.getX() + 1, max.getY() - min.getY() + 1, max.getZ() - min.getZ() + 1);
        }

        AABB aabb() {
            return new AABB(min.getX(), min.getY(), min.getZ(), max.getX() + 1, max.getY() + 1, max.getZ() + 1);
        }
    }

    /** (ax, ay, az) est la case (0, 0, 0) du plan. */
    record Pose(int ax, int ay, int az, Rotation rot) {
        BlockPos bloc(int x, int y, int z) {
            return new BlockPos(x, y, z).rotate(rot).offset(ax, ay, az);
        }

        /** Un point du plan (pas forcément au milieu d'une case). */
        Vec3 point(double x, double y, double z) {
            double dx = x - 0.5, dz = z - 0.5;
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
            return new int[] {d.getX(), d.getY(), d.getZ()};
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
            return boite(X_MIN, Y_MIN, Z_MIN, X_MAX, Y_MAX, Z_MAX);
        }

        /** La boîte du monde qui couvre ces coordonnées du plan. */
        Boite boite(int x0, int y0, int z0, int x1, int y1, int z1) {
            BlockPos a = bloc(x0, y0, z0), b = bloc(x1, y1, z1);
            return new Boite(new BlockPos(Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()), Math.min(a.getZ(), b.getZ())),
                    new BlockPos(Math.max(a.getX(), b.getX()), Math.max(a.getY(), b.getY()), Math.max(a.getZ(), b.getZ())));
        }
    }

    private static boolean sousLeBatiment(int x, int z) {
        return x >= 0 && x <= 19 && z >= 0 && z <= 9;
    }

    // ================================================================ le chantier, tick après tick

    @SubscribeEvent
    public static void tick(ServerTickEvent.Post event) {
        CaveHaruLabo.tick(event.getServer());
        if (chantier != null) {
            if (chantier.level.getServer() != event.getServer()) {
                chantier = null;
            } else {
                try {
                    if (chantier.avancer()) {
                        chantier.liberer();
                        chantier = null;
                    }
                } catch (RuntimeException e) {
                    LOG.error("Chantier de la Cave à Haru interrompu", e);
                    chantier.source.sendFailure(Component.literal("Le chantier s'est arrêté sur une erreur. /caveharu annuler remet les maisons."));
                    chantier.liberer();
                    chantier = null;
                }
            }
            return;
        }
        garder(event.getServer());
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
            for (int cx = b.min().getX() >> 4; cx <= b.max().getX() >> 4; cx++) {
                for (int cz = b.min().getZ() >> 4; cz <= b.max().getZ() >> 4; cz++) {
                    if (level.setChunkForced(cx, cz, true)) {
                        forces.add(new int[] {cx, cz});
                    }
                }
            }
            // les tableaux des maisons et ce qui traîne par terre partent avec elles (la photo les garde)
            for (Entity e : level.getEntities((Entity) null, b.aabb(), x -> x instanceof HangingEntity || x instanceof ItemEntity
                    || x instanceof net.minecraft.world.entity.ExperienceOrb)) {
                e.discard();
            }
            // les bêtes qui se promènent sur les terrains : dans la rue
            Vec3 rue = pose.point(10, 0, -4);
            for (Entity e : level.getEntities((Entity) null, b.aabb(), x -> x instanceof LivingEntity && !(x instanceof Player) && !bloquant(x))) {
                e.teleportTo(rue.x, rue.y, rue.z);
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
            // tout ce qui dépasse du sol sur les deux terrains (sauf les bornes), puis le trou de la cave
            for (int y = Y_MAX; y >= Y_MIN; y--) {
                for (int z = Z_MIN; z <= Z_MAX; z++) {
                    for (int x = X_MIN; x <= X_MAX; x++) {
                        if (y >= 0 || sousLeBatiment(x, z)) {
                            BlockPos p = pose.bloc(x, y, z);
                            BlockState s = level.getBlockState(p);
                            if (!s.isAir() && !s.is(CabaneBlocks.BORNE_TERRAIN.get())) {
                                deblai.add(p.immutable());
                            }
                        }
                    }
                }
            }
            // de l'herbe tout autour du bâtiment, à la place des allées et des jardins
            BlockState herbe = Blocks.GRASS_BLOCK.defaultBlockState(), terre = Blocks.DIRT.defaultBlockState();
            for (int z = Z_MIN; z <= Z_MAX; z++) {
                for (int x = X_MIN; x <= X_MAX; x++) {
                    if (sousLeBatiment(x, z)) {
                        continue;
                    }
                    remblai.add(pose.bloc(x, -1, z));
                    remblaiEtats.add(herbe);
                    for (int y = -2; y >= -4; y--) {
                        BlockPos q = pose.bloc(x, y, z);
                        BlockState s = level.getBlockState(q);
                        if (s.isSolidRender(level, q)) {
                            break;
                        }
                        remblai.add(q);
                        remblaiEtats.add(terre);
                    }
                }
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
                    int[] s = plan.structure();
                    int fin = Math.min(i + BLOCS_PAR_TICK * 4, s.length);
                    for (; i < fin; i += 4) {
                        BlockPos p = pose.bloc(s[i], s[i + 1], s[i + 2]);
                        BlockState etat = Block.updateFromNeighbourShapes(plan.palette()[s[i + 3]], level, p);
                        level.setBlock(p, etat, Block.UPDATE_ALL);
                        if (i % 48 == 0) {
                            level.playSound(null, p, etat.getSoundType().getPlaceSound(), SoundSource.BLOCKS, 0.7F, 0.9F);
                        }
                    }
                    suivanteSi(i >= s.length);
                }
                case 3 -> {
                    int[] s = plan.finitions();
                    int fin = Math.min(i + BLOCS_PAR_TICK * 4, s.length);
                    for (; i < fin; i += 4) {
                        level.setBlock(pose.bloc(s[i], s[i + 1], s[i + 2]), plan.palette()[s[i + 3]], Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
                    }
                    suivanteSi(i >= s.length);
                }
                default -> {
                    entites();
                    source.sendSuccess(() -> Component.literal("La Cave à Haru est ouverte ! (/caveharu annuler remet les maisons)").withStyle(ChatFormatting.GOLD), true);
                    CaveHaruLabo.lancer(level, pose, plan, source); // et tout de suite, le labo sous la salle au trésor
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

        private void entites() {
            JsonObject o = plan.brut();
            accrocher(level, pose, o.getAsJsonArray("tableaux"));
            // le râtelier du bar : une boisson par colonne
            for (JsonElement t : o.getAsJsonArray("tonneaux")) {
                JsonArray a = t.getAsJsonArray();
                BlockPos p = pose.bloc(a.get(0).getAsInt(), a.get(1).getAsInt(), a.get(2).getAsInt());
                var objet = BuiltInRegistries.ITEM.getOptional(ResourceLocation.parse(a.get(3).getAsString()));
                if (objet.isPresent() && level.getBlockEntity(p) instanceof net.minecraft.world.level.block.entity.BarrelBlockEntity tonneau) {
                    ItemStack pile = new ItemStack(objet.get(), a.get(4).getAsInt());
                    for (int slot = 0; !pile.isEmpty() && slot < tonneau.getContainerSize(); slot++) {
                        ItemStack morceau = pile.split(Math.min(pile.getCount(), pile.getMaxStackSize()));
                        tonneau.setItem(slot, morceau);
                    }
                    tonneau.setChanged();
                } else {
                    LOG.warn("Tonneau de la cave introuvable en {} ({})", p, a.get(3).getAsString());
                }
            }
        }
    }

    /** Accroche les tableaux du plan ([variante, x, y, z, face]), marqués comme faisant partie de la cave. */
    static void accrocher(ServerLevel level, Pose pose, JsonArray tableaux) {
        var registre = level.registryAccess().registryOrThrow(Registries.PAINTING_VARIANT);
        for (JsonElement t : tableaux) {
            JsonArray a = t.getAsJsonArray();
            var variante = registre.getHolder(ResourceKey.create(Registries.PAINTING_VARIANT, ResourceLocation.fromNamespaceAndPath(Cabane.MODID, a.get(0).getAsString())));
            if (variante.isEmpty()) {
                LOG.warn("Tableau de la cave introuvable : {}", a.get(0).getAsString());
                continue;
            }
            Direction d = pose.rot().rotate(Direction.byName(a.get(4).getAsString()));
            Painting tableau = new Painting(level, pose.bloc(a.get(1).getAsInt(), a.get(2).getAsInt(), a.get(3).getAsInt()), d, variante.get());
            tableau.addTag(TAG);
            level.addFreshEntity(tableau);
        }
    }

    /**
     * Ce qui se recharge alors qu'il ne devrait plus être là : un tableau de la cave après une annulation (pas encore chargé
     * pendant l'annulation), le barman Haru des premières caves (le PNJ a été retiré à la demande du joueur), les
     * combinaisons du labo enlevé, et les morceaux de la porte du coffre-fort restés en route quand le serveur s'est arrêté.
     */
    @SubscribeEvent
    public static void rejoint(EntityJoinLevelEvent event) {
        Entity e = event.getEntity();
        if (!(event.getLevel() instanceof ServerLevel l)) {
            return;
        }
        if (e.getTags().contains(CaveHaruLabo.TAG_PORTE)) {
            if (event.loadedFromDisk()) {
                event.setCanceled(true); // un morceau resté en route quand le serveur s'est arrêté
            }
        } else if (e.getTags().contains(TAG)) {
            Donnees d = Donnees.get(l.getServer());
            boolean garde = e instanceof Painting ? d.present
                    : e instanceof net.minecraft.world.entity.decoration.ArmorStand && d.present && d.labo;
            if (!garde) {
                event.setCanceled(true);
            }
        }
    }

    // ================================================================ la porte du fût n°2

    /** La cave construite dans ce monde-là, ou null. */
    static Pose ici(Level level) {
        if (!(level instanceof ServerLevel serveur)) {
            return null;
        }
        Donnees d = Donnees.get(serveur.getServer());
        return d.present && level.dimension().location().toString().equals(d.dim) ? d.pose() : null;
    }

    private static boolean porte(int[] c) {
        return c[0] == 16 && c[2] == 5 && (c[1] == -4 || c[1] == -3);
    }

    /**
     * En train de remonter l'échelle, juste sous le fond du fût (coordonnées du plan, pieds du joueur). Celui qui se tient
     * dans le fût n'ouvre rien tout seul : sinon le couvercle restait ouvert tant qu'on était dedans, et tout le monde voyait
     * le passage. De l'intérieur, un clic droit sur le couvercle l'ouvre.
     */
    private static boolean remonte(int[] c) {
        return c[0] == 16 && c[2] == 8 && c[1] >= -7 && c[1] <= -5;
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void clic(PlayerInteractEvent.RightClickBlock event) {
        if (event.getHand() != InteractionHand.MAIN_HAND || !(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        Pose pose = ici(level);
        if (pose != null && porte(pose.plan(event.getPos()))) {
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.SUCCESS);
            ouvrir(level, pose, event.getEntity());
        }
    }

    private static void ouvrir(ServerLevel level, Pose pose, Player joueur) {
        boolean bouge = false;
        for (int[] p : PORTE) {
            BlockPos b = pose.bloc(p[0], p[1], p[2]);
            if (level.getBlockState(b).is(Blocks.STRIPPED_SPRUCE_WOOD)) {
                level.setBlock(b, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                bouge = true;
            }
        }
        fermerA = level.getGameTime() + OUVERTE_TICKS;
        if (bouge) {
            level.playSound(null, pose.bloc(16, -4, 5), SoundEvents.BARREL_OPEN, SoundSource.BLOCKS, 1F, 0.6F);
            joueur.displayClientMessage(Component.literal("Le couvercle du fût pivote…").withStyle(ChatFormatting.GOLD), true);
        }
    }

    /** Ouvre la porte devant ceux qui remontent l'échelle, la referme quand le passage est libre, la remet si elle a disparu. */
    private static void garder(MinecraftServer server) {
        if (++compteur % 10 != 0) {
            return;
        }
        Donnees d = Donnees.get(server);
        if (!d.present) {
            return;
        }
        ServerLevel level = server.getLevel(ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(d.dim)));
        Pose pose = d.pose();
        BlockPos bas = pose.bloc(16, -4, 5);
        if (level == null || !level.isLoaded(bas)) {
            return;
        }
        for (ServerPlayer p : level.players()) {
            if (!p.isSpectator() && p.distanceToSqr(bas.getCenter()) < 30 && remonte(pose.plan(p.blockPosition())) && fermerA < 0) {
                ouvrir(level, pose, p);
            }
        }
        if (fermerA >= 0 && level.getGameTime() >= fermerA) {
            BlockPos haut = pose.bloc(16, -3, 5);
            if (level.getEntitiesOfClass(LivingEntity.class, new AABB(bas).minmax(new AABB(haut))).isEmpty()) {
                fermer(level, pose);
                fermerA = -1;
                level.playSound(null, bas, SoundEvents.BARREL_CLOSE, SoundSource.BLOCKS, 1F, 0.6F);
            }
        } else if (fermerA < 0 && compteur % 100 == 0) {
            fermer(level, pose); // cassée ou explosée : elle revient
        }
    }

    private static void fermer(ServerLevel level, Pose pose) {
        for (int[] p : PORTE) {
            BlockPos b = pose.bloc(p[0], p[1], p[2]);
            if (level.getBlockState(b).canBeReplaced()) {
                level.setBlock(b, Blocks.STRIPPED_SPRUCE_WOOD.defaultBlockState(), Block.UPDATE_ALL);
            }
        }
    }

    /** La porte ne se casse pas (sauf en créatif). */
    @SubscribeEvent
    public static void casser(BlockEvent.BreakEvent event) {
        if (event.getPlayer().isCreative()) {
            return;
        }
        Pose pose = ici((Level) event.getLevel());
        if (pose != null && porte(pose.plan(event.getPos()))) {
            event.setCanceled(true);
            event.getPlayer().displayClientMessage(Component.literal("Ce couvercle ne se casse pas.").withStyle(ChatFormatting.GRAY), true);
        }
    }

    @SubscribeEvent
    public static void explosion(ExplosionEvent.Detonate event) {
        Pose pose = ici(event.getLevel());
        if (pose != null) {
            event.getAffectedBlocks().removeIf(p -> porte(pose.plan(p)));
        }
    }

    // ================================================================ ce qu'on retient

    public static final class Donnees extends SavedData {
        private static final String NOM = "cabane_cave_haru";
        boolean present;
        /** Le labo « Breaking Célia » est construit sous la cave (sa photo d'avant est sur le disque). */
        boolean labo;
        String dim = "";
        int ax, ay, az, rot;
        int minX, minY, minZ;

        static Donnees get(MinecraftServer server) {
            return server.overworld().getDataStorage().computeIfAbsent(new SavedData.Factory<>(Donnees::new, Donnees::charger, null), NOM);
        }

        static Donnees charger(CompoundTag t, HolderLookup.Provider registres) {
            Donnees d = new Donnees();
            d.present = t.getBoolean("present");
            d.labo = t.getBoolean("labo");
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
            t.putBoolean("labo", labo);
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
            labo = false;
            setDirty();
        }

        void labo(boolean construit) {
            labo = construit;
            setDirty();
        }

        Pose pose() {
            return new Pose(ax, ay, az, Rotation.values()[rot]);
        }
    }
}
